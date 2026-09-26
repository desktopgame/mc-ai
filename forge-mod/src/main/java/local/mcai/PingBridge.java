package local.mcai;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.ServerChatEvent;
import org.apache.logging.log4j.LogManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Social work has its own bounded queue, shared by user chat and Skill-terminal notifications. */
public final class PingBridge {
    private final PingClient client;
    private final ActionBridge actions;
    private final IoExecutors io;
    private final ConversationQueue queue = new ConversationQueue();
    private final boolean verbose;
    private MinecraftServer server;
    private EntityPlayerMP owner;
    private String session = UUID.randomUUID().toString(), forgetSession;
    private long conversationEpoch;
    private volatile boolean inFlight;
    private volatile Completion completion;
    private volatile TerminalDone terminalDone;
    private final Map<String, TerminalRequest> terminalRequests = new HashMap<String, TerminalRequest>();
    private final LinkedHashSet<String> seenTerminals = new LinkedHashSet<String>();
    private static final int MAX_SEEN_TERMINALS = 256;
    private static final long TERMINAL_FALLBACK_NANOS = 12000000000L;

    /** A terminal notification waiting to be presented, displayed and acknowledged. */
    public static final class TerminalRequest {
        public final String daemonEpoch, execSession, skillInstanceId, terminalId, fallback, deliveryId;
        public final List<String> candidateSays;
        String conversationSession, player;
        public TerminalRequest(String daemonEpoch, String execSession, String skillInstanceId, String terminalId,
                               String fallback, List<String> candidateSays, String deliveryId) {
            this.daemonEpoch = daemonEpoch; this.execSession = execSession; this.skillInstanceId = skillInstanceId;
            this.terminalId = terminalId; this.fallback = fallback; this.candidateSays = candidateSays;
            this.deliveryId = deliveryId;
        }
    }
    private static final class Completion {
        final ConversationQueue.Turn turn; final PingClient.Reply reply; final long received = System.nanoTime();
        Completion(ConversationQueue.Turn turn, PingClient.Reply reply) { this.turn = turn; this.reply = reply; }
    }
    private static final class TerminalDone {
        final long epoch; final String identity, say, variantId; final TerminalRequest request;
        TerminalDone(long epoch, String identity, TerminalRequest request, String say, String variantId) {
            this.epoch = epoch; this.identity = identity; this.request = request; this.say = say; this.variantId = variantId;
        }
    }
    public PingBridge(String url, ActionBridge actions, IoExecutors io, boolean verbose) {
        client = new PingClient(url); this.actions = actions; this.io = io; this.verbose = verbose;
    }
    private void reset(EntityPlayerMP player) {
        queue.reset(); session = UUID.randomUUID().toString(); owner = player;
        seenTerminals.clear(); conversationEpoch++;
        for (TerminalRequest request : terminalRequests.values()) { ackAsync(request, "suppressed", null); }
        terminalRequests.clear();
    }
    private void ensureContext(EntityPlayerMP player) {
        if (server != MinecraftServer.getServer() || owner != player) { server = MinecraftServer.getServer(); reset(player); }
    }
    private void reply(String text) { if (owner != null) { owner.addChatMessage(new ChatComponentText("[Companion] " + text)); } }
    private void debugReply(String text) { if (verbose) { reply(text); } }
    public boolean thinking() { return inFlight; }

    /**
     * Hands a finalized terminal fallback to the shared conversation queue. The queue's SOCIAL worker
     * asks the Daemon to choose a variant, re-checks the text is one of our own candidates, displays
     * it, then sends the displayed ACK. Duplicate identities and a full terminal slot degrade to an
     * immediate one-time fallback display plus a best-effort ACK, never a second queue entry.
     */
    public boolean enqueueTerminal(EntityPlayerMP player, TerminalRequest request) {
        if (request == null) { return false; }
        ensureContext(player);
        if (owner == null) { return false; }
        request.player = owner.getCommandSenderName();
        request.conversationSession = session;
        if (!seenTerminals.add(request.terminalId)) { return false; }
        while (seenTerminals.size() > MAX_SEEN_TERMINALS) {
            Iterator<String> iterator = seenTerminals.iterator(); iterator.next(); iterator.remove();
        }
        terminalRequests.put(request.terminalId, request);
        if (!queue.offerTerminal(request.terminalId, request.fallback, System.nanoTime())) {
            reply(request.fallback);
            terminalRequests.remove(request.terminalId);
            ackAsync(request, "displayed", "fallback");
        }
        return true;
    }

    private void ackAsync(final TerminalRequest request, final String outcome, final String variantId) {
        if (request == null) { return; }
        io.execute(IoExecutors.Lane.SOCIAL, new Runnable() {
            @Override public void run() {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        if (client.deliverTerminal(request.player, request.conversationSession, request.deliveryId,
                                request.daemonEpoch, request.execSession, request.skillInstanceId, request.terminalId,
                                outcome, variantId)) { return; }
                    } catch (Exception e) { /* best effort; never roll back the display */ }
                }
                LogManager.getLogger(CompanionMod.MOD_ID).warn("Terminal delivery ACK failed outcome={}", outcome);
            }
        });
    }

    private void displayPendingTerminals() {
        for (ConversationQueue.Turn turn : queue.snapshot()) {
            if (turn.kind == ConversationQueue.Kind.SKILL_TERMINAL) {
                reply(turn.text);
                ackAsync(terminalRequests.remove(turn.identity), "displayed", "fallback");
            }
        }
    }

    private void showExpiredTerminals(long now) {
        for (ConversationQueue.Turn turn : new ArrayList<ConversationQueue.Turn>(queue.snapshot())) {
            if (turn.kind == ConversationQueue.Kind.SKILL_TERMINAL && now - turn.enqueuedAt >= TERMINAL_FALLBACK_NANOS) {
                queue.remove(turn);
                if (queue.current(turn)) { reply(turn.text); }
                ackAsync(terminalRequests.remove(turn.identity), "displayed", "fallback");
            }
        }
    }

    private void processTerminal(final ConversationQueue.Turn turn) {
        final TerminalRequest request = terminalRequests.get(turn.identity);
        if (request == null) { reply(turn.text); return; }
        final long epoch = conversationEpoch;
        inFlight = true;
        boolean accepted = io.execute(IoExecutors.Lane.SOCIAL, new Runnable() {
            @Override public void run() {
                String say = request.fallback, variantId = "fallback";
                try {
                    PingClient.Presentation presentation = client.presentTerminal(request.player,
                            request.conversationSession, request.deliveryId, request.daemonEpoch,
                            request.execSession, request.skillInstanceId, request.terminalId);
                    // Fact invariance: only accept a social variant that matches one of our own candidates.
                    if (presentation.mode.equals("social") && request.candidateSays.contains(presentation.say)) {
                        say = presentation.say; variantId = presentation.variantId;
                    }
                } catch (Exception e) {
                    LogManager.getLogger(CompanionMod.MOD_ID).warn("Terminal presentation fallback ({})", e.getClass().getSimpleName());
                }
                // Presentation only. Display and the displayed/suppressed ACK happen on the game thread,
                // so history is registered only for a terminal that was actually shown.
                terminalDone = new TerminalDone(epoch, turn.identity, request, say, variantId);
            }
        });
        if (!accepted) {
            inFlight = false; reply(request.fallback); terminalRequests.remove(turn.identity);
            ackAsync(request, "displayed", "fallback");
        }
    }

    @SubscribeEvent public void onChat(ServerChatEvent event) {
        String message = event.message.trim();
        if (!(message.equals("!agent ping") || message.equals("!agent forget") || message.equals("!agent chat") || message.startsWith("!agent chat "))) { return; }
        event.setCanceled(true);
        ensureContext(event.player);
        if (message.equals("!agent forget")) {
            displayPendingTerminals();
            forgetSession = session; reset(event.player);
            reply("会話をリセットしました。待機中の発言と古い返答も取り消しました。");
        } else if (message.equals("!agent chat")) { reply("使い方: !agent chat メッセージ"); }
        else if (message.startsWith("!agent chat ") && ImmediateStop.matches(message.substring(12))) {
            actions.stopFromChat(event.player);
        }
        else if (!queue.offer(message, actions.captureIntent(event.player))) { reply("会話の待機枠がいっぱいか、文章が長すぎます。少し待って短く送ってください。"); }
        else { debugReply("受け付けました。"); }
    }

    @SubscribeEvent public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) { return; }
        MinecraftServer now = MinecraftServer.getServer();
        if (now != server || (owner != null && (now == null || !now.getConfigurationManager().playerEntityList.contains(owner)))) {
            server = now; reset(null);
        }
        Completion done = completion;
        if (done != null) {
            completion = null; inFlight = false;
            if (done.turn != null && queue.current(done.turn) && owner != null) {
                if (done.reply.intent.equals("none")) { reply(done.reply.say); }
                else if (System.nanoTime() - done.received > 5000000000L) { debugReply("時間が経過した指示は取り消しました。必要ならもう一度依頼してください。"); }
                else if (actions.acceptIntent(owner, done.turn.intentTicket, done.reply.intent)) { reply(done.reply.say); }
            }
        }
        TerminalDone finished = terminalDone;
        if (finished != null) {
            terminalDone = null; inFlight = false;
            if (TerminalAckPolicy.shouldDisplay(finished.epoch, conversationEpoch, owner != null)) {
                reply(finished.say);   // display first...
                ackAsync(finished.request, "displayed", finished.variantId);   // ...then the displayed ACK
            } else {
                // reset / forget / owner or world change happened before the completion: never display
                // and never register history; a duplicate suppressed ACK is idempotent on the Daemon.
                ackAsync(finished.request, "suppressed", null);
            }
            terminalRequests.remove(finished.identity);
        }
        if (owner != null) { showExpiredTerminals(System.nanoTime()); }
        if (inFlight || owner == null) { return; }
        ConversationQueue.Turn next = queue.poll();
        final String cleanup = forgetSession;
        if (next == null && cleanup == null) { return; }
        forgetSession = null;
        if (next != null && next.kind == ConversationQueue.Kind.SKILL_TERMINAL) {
            if (queue.current(next)) { processTerminal(next); }
            return;
        }
        final ConversationQueue.Turn turn = next;
        final String conversation = session, player = owner.getCommandSenderName();
        inFlight = true;
        boolean accepted = io.execute(IoExecutors.Lane.SOCIAL, new Runnable() {
            @Override public void run() {
                PingClient.Reply answer = new PingClient.Reply("応答を取得できませんでした。Daemon・モデル・接続設定を確認してください。", "none");
                try {
                    if (cleanup != null) {
                        try { client.turn(player, "!agent forget", cleanup); }
                        catch (Exception e) { LogManager.getLogger(CompanionMod.MOD_ID).warn("Old conversation cleanup unavailable"); }
                    }
                    if (turn != null) { answer = client.socialTurn(player, turn.text, conversation); }
                } catch (Exception e) { LogManager.getLogger(CompanionMod.MOD_ID).warn("Social turn failed ({})", e.getClass().getSimpleName()); }
                finally { completion = new Completion(turn, answer); }
            }
        });
        if (!accepted) {
            inFlight = false;
            if (cleanup != null) { forgetSession = cleanup; }
            reply("会話の通信を開始できませんでした。少し待って、もう一度送ってください。");
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Social executor rejected request");
        }
    }
}

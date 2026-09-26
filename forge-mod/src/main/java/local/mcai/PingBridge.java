package local.mcai;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.ServerChatEvent;
import org.apache.logging.log4j.LogManager;
import java.util.ArrayDeque;
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
    private String session = UUID.randomUUID().toString();
    private long conversationEpoch;
    private volatile boolean inFlight;
    private volatile Completion completion;
    private long nextCleanupPoll, nextAckPoll;

    // Terminal delivery is tracked independently of the chat queue so a 12s display deadline also
    // covers in-flight presentation and a forget can still show an in-flight result once.
    private final TerminalDeliveryState deliveries = new TerminalDeliveryState();
    private final Map<String, TerminalRequest> terminalRequests = new HashMap<String, TerminalRequest>();
    private final LinkedHashSet<String> seenTerminals = new LinkedHashSet<String>();
    private volatile TerminalDone terminalDone;
    private final ArrayDeque<Ack> pendingAcks = new ArrayDeque<Ack>();
    private boolean ackInFlight;
    private volatile Ack ackDone;
    private String pendingCleanup, pendingCleanupPlayer;
    private boolean cleanupInFlight;
    private volatile boolean cleanupDone;

    private static final int MAX_SEEN_TERMINALS = 256;
    private static final int MAX_PENDING_ACKS = 32;
    private static final long TERMINAL_FALLBACK_NANOS = 12000000000L;
    private static final long PUMP_INTERVAL_NANOS = 1000000000L;

    /** A finalised terminal waiting to be presented, displayed and acknowledged. */
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
    private static final class Ack {
        final TerminalRequest request; final String outcome, variantId;
        Ack(TerminalRequest request, String outcome, String variantId) {
            this.request = request; this.outcome = outcome; this.variantId = variantId;
        }
    }
    public PingBridge(String url, ActionBridge actions, IoExecutors io, boolean verbose) {
        client = new PingClient(url); this.actions = actions; this.io = io; this.verbose = verbose;
    }

    private void reset(EntityPlayerMP player) {
        queue.reset(); session = UUID.randomUUID().toString(); owner = player; conversationEpoch++;
        for (Map.Entry<String, TerminalRequest> entry : terminalRequests.entrySet()) {
            if (!deliveries.resolved(entry.getKey())) {
                deliveries.suppress(entry.getKey());
                ackAsync(entry.getValue(), "suppressed", null);
            }
        }
        terminalRequests.clear();
        seenTerminals.clear();   // dedupe is per conversation binding; never carry it across a reset
    }
    private void ensureContext(EntityPlayerMP player) {
        if (server != MinecraftServer.getServer() || owner != player) { server = MinecraftServer.getServer(); reset(player); }
    }
    private void reply(String text) { if (owner != null) { owner.addChatMessage(new ChatComponentText("[Companion] " + text)); } }
    private void debugReply(String text) { if (verbose) { reply(text); } }
    public boolean thinking() { return inFlight; }

    /**
     * Hands a finalized terminal to the shared conversation queue. Presentation runs on the SOCIAL
     * worker; display and the displayed/suppressed ACK happen on the game thread, so history is only
     * registered for a terminal that was actually shown. Duplicate identities and a full terminal slot
     * degrade to an immediate one-time fallback display plus a best-effort ACK.
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
        long now = System.nanoTime();
        if (!deliveries.accept(request.terminalId, request.fallback, now, TERMINAL_FALLBACK_NANOS)) { return false; }
        if (!queue.offerTerminal(request.terminalId, request.fallback, now)) { resolveTerminalFallback(request.terminalId); }
        return true;
    }

    /** First-wins fallback display: the exact text is settled once and ACKed once. */
    private void resolveTerminalFallback(String identity) {
        TerminalRequest request = terminalRequests.remove(identity);
        String text = deliveries.finishFallback(identity);
        if (request != null && text != null) { reply(text); ackAsync(request, "displayed", "fallback"); }
    }

    private void expireTerminals(long now) {
        if (owner == null) { return; }
        for (String identity : deliveries.expired(now)) {
            TerminalRequest request = terminalRequests.remove(identity);
            String text = deliveries.finishFallback(identity);
            if (request != null && text != null) { reply(text); ackAsync(request, "displayed", "fallback"); }
        }
    }

    private void displayAllPendingTerminals() {
        if (owner == null) { return; }
        for (String identity : new ArrayList<String>(terminalRequests.keySet())) {
            TerminalRequest request = terminalRequests.remove(identity);
            String text = deliveries.finishFallback(identity);
            if (request != null && text != null) { reply(text); ackAsync(request, "displayed", "fallback"); }
        }
    }

    private void ackAsync(final TerminalRequest request, final String outcome, final String variantId) {
        if (request == null) { return; }
        if (pendingAcks.size() >= MAX_PENDING_ACKS) {
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Terminal ACK backlog full; dropping outcome={}", outcome);
            return;
        }
        pendingAcks.add(new Ack(request, outcome, variantId));
    }

    private void startAck(long now) {
        final Ack ack = pendingAcks.peek();
        ackInFlight = true; nextAckPoll = now + PUMP_INTERVAL_NANOS;
        boolean accepted = io.execute(IoExecutors.Lane.SOCIAL, new Runnable() {
            @Override public void run() {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        if (client.deliverTerminal(ack.request.player, ack.request.conversationSession,
                                ack.request.deliveryId, ack.request.daemonEpoch, ack.request.execSession,
                                ack.request.skillInstanceId, ack.request.terminalId, ack.outcome, ack.variantId)) { break; }
                    } catch (Exception e) { /* bounded retry; never roll back the display */ }
                }
                ackDone = ack;
            }
        });
        if (!accepted) { ackInFlight = false; }
    }

    private void startCleanup(long now) {
        final String target = pendingCleanup, player = pendingCleanupPlayer;
        cleanupInFlight = true; nextCleanupPoll = now + PUMP_INTERVAL_NANOS;
        boolean accepted = io.execute(IoExecutors.Lane.SOCIAL, new Runnable() {
            @Override public void run() {
                try { client.turn(player, "!agent forget", target); }
                catch (Exception e) { LogManager.getLogger(CompanionMod.MOD_ID).warn("Old conversation cleanup unavailable"); }
                finally { cleanupDone = true; }
            }
        });
        if (!accepted) { cleanupInFlight = false; }
    }

    private void processTerminal(final ConversationQueue.Turn turn) {
        final TerminalRequest request = terminalRequests.get(turn.identity);
        if (request == null || deliveries.resolved(turn.identity)) { terminalRequests.remove(turn.identity); return; }
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
                terminalDone = new TerminalDone(epoch, turn.identity, request, say, variantId);
            }
        });
        if (!accepted) { inFlight = false; resolveTerminalFallback(turn.identity); }
    }

    @SubscribeEvent public void onChat(ServerChatEvent event) {
        String message = event.message.trim();
        if (!(message.equals("!agent ping") || message.equals("!agent forget") || message.equals("!agent chat") || message.startsWith("!agent chat "))) { return; }
        event.setCanceled(true);
        ensureContext(event.player);
        if (message.equals("!agent forget")) {
            // Pending terminals (including one still generating) belong to the old conversation: show
            // each fixed result once, then retire the conversation and switch to a new one.
            displayAllPendingTerminals();
            pendingCleanup = session; pendingCleanupPlayer = event.player.getCommandSenderName();
            reset(event.player);
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
        long now = System.nanoTime();
        MinecraftServer current = MinecraftServer.getServer();
        if (current != server || (owner != null && (current == null || !current.getConfigurationManager().playerEntityList.contains(owner)))) {
            server = current; reset(null);
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
            String displayed = null;
            if (TerminalAckPolicy.shouldDisplay(finished.epoch, conversationEpoch, owner != null)) {
                displayed = deliveries.finishSocial(finished.identity, finished.say);
            }
            if (displayed != null) {
                reply(displayed);                                   // display first...
                ackAsync(finished.request, "displayed", finished.variantId);   // ...then the displayed ACK
            } else if (!deliveries.resolved(finished.identity)) {
                deliveries.suppress(finished.identity);             // reset/forget/timeout already resolved it
                ackAsync(finished.request, "suppressed", null);
            }
            terminalRequests.remove(finished.identity);
        }
        Ack ackFinished = ackDone;
        if (ackFinished != null) { ackDone = null; ackInFlight = false; if (pendingAcks.peek() == ackFinished) { pendingAcks.poll(); } }
        if (cleanupDone) { cleanupDone = false; cleanupInFlight = false; pendingCleanup = null; pendingCleanupPlayer = null; }
        expireTerminals(now);
        // ACK and forget cleanup are delivered before the next Social generation (one at a time).
        if (!inFlight) {
            if (pendingCleanup != null && !cleanupInFlight && now >= nextCleanupPoll) { startCleanup(now); return; }
            if (!pendingAcks.isEmpty() && !ackInFlight && now >= nextAckPoll) { startAck(now); return; }
        }
        if (inFlight || owner == null) { return; }
        ConversationQueue.Turn next = queue.poll();
        if (next == null) { return; }
        if (next.kind == ConversationQueue.Kind.SKILL_TERMINAL) {
            if (queue.current(next)) { processTerminal(next); }
            return;
        }
        final ConversationQueue.Turn turn = next;
        final String conversation = session, player = owner.getCommandSenderName();
        inFlight = true;
        boolean accepted = io.execute(IoExecutors.Lane.SOCIAL, new Runnable() {
            @Override public void run() {
                PingClient.Reply answer = new PingClient.Reply("応答を取得できませんでした。Daemon・モデル・接続設定を確認してください。", "none");
                try { answer = client.socialTurn(player, turn.text, conversation); }
                catch (Exception e) { LogManager.getLogger(CompanionMod.MOD_ID).warn("Social turn failed ({})", e.getClass().getSimpleName()); }
                finally { completion = new Completion(turn, answer); }
            }
        });
        if (!accepted) {
            inFlight = false;
            reply("会話の通信を開始できませんでした。少し待って、もう一度送ってください。");
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Social executor rejected request");
        }
    }
}

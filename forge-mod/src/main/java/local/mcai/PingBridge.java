package local.mcai;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.ServerChatEvent;
import org.apache.logging.log4j.LogManager;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
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
    private volatile boolean inFlight;
    private volatile Completion completion;
    // Terminal identities already handed to this conversation; terminal delivery itself is at-most-once.
    private final LinkedHashSet<String> seenTerminals = new LinkedHashSet<String>();
    private static final int MAX_SEEN_TERMINALS = 256;
    /** A queued terminal waiting this long is displayed out of FIFO so a long chat cannot starve it. */
    private static final long TERMINAL_FALLBACK_NANOS = 12000000000L;
    private static final class Completion {
        final ConversationQueue.Turn turn; final PingClient.Reply reply; final long received = System.nanoTime();
        Completion(ConversationQueue.Turn turn, PingClient.Reply reply) { this.turn = turn; this.reply = reply; }
    }
    public PingBridge(String url, ActionBridge actions, IoExecutors io, boolean verbose) {
        client = new PingClient(url); this.actions = actions; this.io = io; this.verbose = verbose;
    }
    private void reset(EntityPlayerMP player) {
        queue.reset(); session = UUID.randomUUID().toString(); owner = player; seenTerminals.clear();
    }
    private void ensureContext(EntityPlayerMP player) {
        if (server != MinecraftServer.getServer() || owner != player) { server = MinecraftServer.getServer(); reset(player); }
    }
    private void reply(String text) { if (owner != null) { owner.addChatMessage(new ChatComponentText("[Companion] " + text)); } }
    /** Internal lifecycle chatter; the on-screen icon covers this by default. */
    private void debugReply(String text) { if (verbose) { reply(text); } }
    /** Social turn in flight, read cross-thread by the HUD icon. */
    public boolean thinking() { return inFlight; }

    /**
     * Hands a finalized terminal fallback to the shared conversation queue. Initializes the chat
     * context if the Skill was started without a prior chat. Duplicate identities and a full terminal
     * slot degrade to an immediate one-time display, never a second queue entry.
     */
    public boolean enqueueTerminal(EntityPlayerMP player, String identity, String say) {
        if (identity == null || say == null) { return false; }
        ensureContext(player);
        if (owner == null) { return false; }
        if (!seenTerminals.add(identity)) { return false; }
        while (seenTerminals.size() > MAX_SEEN_TERMINALS) {
            Iterator<String> iterator = seenTerminals.iterator(); iterator.next(); iterator.remove();
        }
        if (!queue.offerTerminal(identity, say, System.nanoTime())) { reply(say); }
        return true;
    }

    private void displayPendingTerminals() {
        for (ConversationQueue.Turn turn : queue.snapshot()) {
            if (turn.kind == ConversationQueue.Kind.SKILL_TERMINAL) { reply(turn.text); }
        }
    }

    private void showExpiredTerminals(long now) {
        for (ConversationQueue.Turn turn : new ArrayList<ConversationQueue.Turn>(queue.snapshot())) {
            if (turn.kind == ConversationQueue.Kind.SKILL_TERMINAL && now - turn.enqueuedAt >= TERMINAL_FALLBACK_NANOS) {
                queue.remove(turn);
                if (queue.current(turn)) { reply(turn.text); }
            }
        }
    }

    @SubscribeEvent public void onChat(ServerChatEvent event) {
        String message = event.message.trim();
        if (!(message.equals("!agent ping") || message.equals("!agent forget") || message.equals("!agent chat") || message.startsWith("!agent chat "))) { return; }
        event.setCanceled(true);
        ensureContext(event.player);
        if (message.equals("!agent forget")) {
            // Pending terminal fallbacks belong to the old conversation: show them once, then discard.
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
        // Terminal timeout exception runs even while a chat is generating, so an old Skill's result
        // is not starved by a long conversation. Terminal notifications never use the Social worker.
        if (owner != null) { showExpiredTerminals(System.nanoTime()); }
        if (inFlight || owner == null) { return; }
        ConversationQueue.Turn next = queue.poll();
        final String cleanup = forgetSession;
        if (next == null && cleanup == null) { return; }
        forgetSession = null;
        if (next != null && next.kind == ConversationQueue.Kind.SKILL_TERMINAL) {
            if (queue.current(next)) { reply(next.text); }
            return;
        }
        final ConversationQueue.Turn turn = next;   // user chat, or null for a cleanup-only tick
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

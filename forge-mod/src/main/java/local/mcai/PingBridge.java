package local.mcai;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.ServerChatEvent;
import org.apache.logging.log4j.LogManager;
import java.util.UUID;

/** Social work has its own bounded queue, independent of action/control traffic. */
public final class PingBridge {
    private final PingClient client;
    private final ActionBridge actions;
    private final IoExecutors io;
    private final ConversationQueue queue = new ConversationQueue();
    private MinecraftServer server;
    private EntityPlayerMP owner;
    private String session = UUID.randomUUID().toString(), forgetSession;
    private boolean inFlight;
    private volatile Completion completion;
    private static final class Completion {
        final ConversationQueue.Turn turn; final PingClient.Reply reply; final long received = System.nanoTime();
        Completion(ConversationQueue.Turn turn, PingClient.Reply reply) { this.turn = turn; this.reply = reply; }
    }
    public PingBridge(String url, ActionBridge actions, IoExecutors io) { client = new PingClient(url); this.actions = actions; this.io = io; }
    private void reset(EntityPlayerMP player) {
        queue.reset(); session = UUID.randomUUID().toString(); owner = player;
    }
    private void reply(String text) { if (owner != null) { owner.addChatMessage(new ChatComponentText("[Companion] " + text)); } }

    @SubscribeEvent public void onChat(ServerChatEvent event) {
        String message = event.message.trim();
        if (!(message.equals("!agent ping") || message.equals("!agent forget") || message.equals("!agent chat") || message.startsWith("!agent chat "))) { return; }
        event.setCanceled(true);
        if (server != MinecraftServer.getServer() || owner != event.player) {
            server = MinecraftServer.getServer(); reset(event.player);
        }
        if (message.equals("!agent forget")) {
            forgetSession = session; reset(event.player);
            reply("会話をリセットしました。待機中の発言と古い返答も取り消しました。");
        } else if (message.equals("!agent chat")) { reply("使い方: !agent chat メッセージ"); }
        else if (message.startsWith("!agent chat ") && ImmediateStop.matches(message.substring(12))) {
            actions.stopFromChat(event.player);
        }
        else if (!queue.offer(message, actions.captureIntent(event.player))) { reply("会話の待機枠がいっぱいか、文章が長すぎます。少し待って短く送ってください。"); }
        else { reply("受け付けました。"); }
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
                else if (System.nanoTime() - done.received > 5000000000L) { reply("時間が経過した指示は取り消しました。必要ならもう一度依頼してください。"); }
                else if (actions.acceptIntent(owner, done.turn.intentTicket, done.reply.intent)) { reply(done.reply.say); }
            }
        }
        if (inFlight || owner == null) { return; }
        final ConversationQueue.Turn turn = queue.poll();
        final String cleanup = forgetSession;
        if (turn == null && cleanup == null) { return; }
        forgetSession = null;
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

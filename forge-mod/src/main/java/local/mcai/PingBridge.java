package local.mcai;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.ServerChatEvent;
import org.apache.logging.log4j.LogManager;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

public final class PingBridge {
    private final PingClient client;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Runnable> replies = new ConcurrentLinkedQueue<Runnable>();
    private MinecraftServer sessionServer;
    private String sessionId;

    public PingBridge(String url) { client = new PingClient(url); }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        final String message = event.message.trim();
        if (!(message.equals("!agent ping") || message.equals("!agent forget")
                || message.equals("!agent chat") || message.startsWith("!agent chat "))) { return; }
        event.setCanceled(true);
        final EntityPlayerMP player = event.player;
        if (message.equals("!agent chat")) {
            player.addChatMessage(new ChatComponentText("[Companion] 使い方: !agent chat メッセージ"));
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            player.addChatMessage(new ChatComponentText("[Companion] 通信中です。少し待ってください。"));
            return;
        }
        final MinecraftServer server = MinecraftServer.getServer();
        if (sessionServer != server) { sessionServer = server; sessionId = UUID.randomUUID().toString(); }
        final String session = sessionId;
        final String playerName = event.username;
        if (message.startsWith("!agent chat ")) {
            player.addChatMessage(new ChatComponentText("[Companion] 考えています…"));
        }
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                long started = System.nanoTime();
                String result;
                try {
                    result = client.turn(playerName, message, session);
                    LogManager.getLogger(CompanionMod.MOD_ID).info("Daemon turn succeeded, RTT={} ms",
                            (System.nanoTime() - started) / 1000000);
                } catch (IOException | RuntimeException error) {
                    result = "応答を取得できませんでした。Daemon・モデル・接続設定を確認してください。";
                    LogManager.getLogger(CompanionMod.MOD_ID).warn("Daemon turn failed ({})",
                            error.getClass().getSimpleName());
                }
                final String reply = result;
                replies.add(new Runnable() {
                    @Override public void run() {
                        try {
                            // Discard results from a disconnected player or an earlier world session.
                            if (MinecraftServer.getServer() == server
                                    && server.getConfigurationManager().playerEntityList.contains(player)) {
                                player.addChatMessage(new ChatComponentText("[Companion] " + reply));
                            }
                        } finally { busy.set(false); }
                    }
                });
            }
        }, "mc-ai-ping");
        worker.setDaemon(true);
        worker.start();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) { return; }
        Runnable reply;
        while ((reply = replies.poll()) != null) { reply.run(); }
    }
}

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

public final class PingBridge {
    private final PingClient client;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Runnable> replies = new ConcurrentLinkedQueue<Runnable>();

    public PingBridge(String url) { client = new PingClient(url); }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        if (!event.message.trim().equals("!agent ping")) { return; }
        event.setCanceled(true);
        final EntityPlayerMP player = event.player;
        if (!busy.compareAndSet(false, true)) {
            player.addChatMessage(new ChatComponentText("[Companion] 通信中です。少し待ってください。"));
            return;
        }
        final MinecraftServer server = MinecraftServer.getServer();
        final String playerName = event.username;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                long started = System.nanoTime();
                String result;
                try {
                    result = client.ping(playerName);
                    LogManager.getLogger(CompanionMod.MOD_ID).info("Daemon ping succeeded, RTT={} ms",
                            (System.nanoTime() - started) / 1000000);
                } catch (IOException | RuntimeException error) {
                    result = "Daemonと通信できませんでした。起動と接続設定を確認してください。";
                    LogManager.getLogger(CompanionMod.MOD_ID).warn("Daemon ping failed ({})",
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

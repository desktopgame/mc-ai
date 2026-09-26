package local.mcai;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.ServerChatEvent;
import org.apache.logging.log4j.LogManager;
import java.util.Locale;

public final class CompanionCommands {
    private final ActionBridge actions;
    public CompanionCommands(ActionBridge actions) { this.actions = actions; }
    @SubscribeEvent public void onChat(ServerChatEvent event) {
        String input = event.message.trim();
        if (!(input.equals("!agent") || input.startsWith("!agent ")) || input.equals("!agent ping")
                || input.equals("!agent chat") || input.startsWith("!agent chat ") || input.equals("!agent forget")
                || input.equals("!agent do") || input.startsWith("!agent do ")) { return; }
        event.setCanceled(true);
        try {
            DebugCommand command = DebugCommand.parse(input);
            execute(event.player, command);
        } catch (IllegalArgumentException error) {
            reply(event.player, error.getMessage());
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Manual command rejected: invalid parameters");
        }
    }

    private void execute(EntityPlayerMP player, DebugCommand command) {
        if (command.type.equals("help")) {
            reply(player, "!agent spawn / follow / stop / look / say メッセージ / status / ping / chat メッセージ / forget / do follow|look|stop");
            return;
        }
        CompanionEntity companion = find(player);
        if (command.type.equals("stop")) {
            actions.manualOverride(player);
            if (companion != null) { companion.stop(); }
            reply(player, "停止しました。待機中の判断も取り消しました。"); return;
        }
        if (command.type.equals("spawn")) {
            if (companion != null) { reply(player, "Companionは既にいます。!agent status で確認できます。"); return; }
            if (!CompanionRegistry.get().find(player.getUniqueID().toString()).isEmpty()) {
                reply(player, "Companionは未読込の場所にいます。最後にいた場所へ戻ってください。"); return;
            }
            companion = spawn(player);
            if (companion == null) { reply(player, "近くに安全な出現場所がありません。平らで広い場所で試してください。"); return; }
            reply(player, "Companionを呼びました。");
        } else {
            if (companion == null) { reply(player, "Companionが読み込まれていません。!agent spawn で確認してください。"); return; }
            if (companion.worldObj != player.worldObj) { reply(player, "Companionは別のディメンションにいます。"); return; }
            if (command.type.equals("follow") || command.type.equals("look")) { actions.manualOverride(player); }
            if (command.type.equals("follow")) { companion.follow(); reply(player, "ついていきます。"); }
            else if (command.type.equals("stop")) { companion.stop(); reply(player, "ここで待ちます。"); }
            else if (command.type.equals("look")) { companion.look(); reply(player, "そちらを向きます。"); }
            else if (command.type.equals("say")) { reply(player, command.text); }
            else if (command.type.equals("status")) {
                reply(player, String.format(Locale.ROOT, "HP %.0f/%.0f | (%.1f, %.1f, %.1f) | task=%s | result=%s",
                        companion.getHealth(), companion.getMaxHealth(), companion.posX, companion.posY, companion.posZ,
                        companion.task(), companion.lastResult()));
            }
        }
        LogManager.getLogger(CompanionMod.MOD_ID).info("Manual action={} accepted", command.type);
    }

    public static CompanionEntity find(EntityPlayerMP player) {
        String owner = player.getUniqueID().toString();
        for (WorldServer world : MinecraftServer.getServer().worldServers) {
            if (world == null) { continue; }
            for (Object entity : world.loadedEntityList) {
                if (entity instanceof CompanionEntity) {
                    CompanionEntity companion = (CompanionEntity) entity;
                    if (companion.isEntityAlive() && owner.equals(companion.ownerId())) { return companion; }
                }
            }
        }
        return null;
    }

    private CompanionEntity spawn(EntityPlayerMP player) {
        World world = player.worldObj;
        CompanionEntity companion = new CompanionEntity(world);
        int px = MathHelper.floor_double(player.posX), py = MathHelper.floor_double(player.posY), pz = MathHelper.floor_double(player.posZ);
        for (int radius = 2; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) { continue; }
                    for (int dy = -1; dy <= 1; dy++) {
                        int x = px + dx, y = py + dy, z = pz + dz;
                        if (y < 1 || y > 253 || !world.blockExists(x, y, z)
                                || !World.doesBlockHaveSolidTopSurface(world, x, y - 1, z)
                                || world.getBlock(x, y - 1, z) == Blocks.cactus
                                || world.getBlock(x, y, z) == Blocks.fire
                                || world.getBlock(x, y + 1, z) == Blocks.fire) { continue; }
                        companion.setLocationAndAngles(x + 0.5D, y, z + 0.5D, player.rotationYaw, 0);
                        if (!world.getCollidingBoundingBoxes(companion, companion.boundingBox).isEmpty()
                                || world.isAnyLiquid(companion.boundingBox)
                                || !world.checkNoEntityCollision(companion.boundingBox)) { continue; }
                        companion.setOwner(player);
                        if (!world.spawnEntityInWorld(companion)) { return null; }
                        CompanionRegistry.get().register(player.getUniqueID().toString(), companion.getUniqueID().toString());
                        return companion;
                    }
                }
            }
        }
        return null;
    }

    private void reply(EntityPlayerMP player, String text) { player.addChatMessage(new ChatComponentText("[Companion] " + text)); }
}

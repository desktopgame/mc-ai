package local.mcai;

import com.google.gson.*;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Observations are captured on the server thread; the worker receives JSON only. */
public final class ObservationBridge {
    private final String baseUrl;
    private final IoExecutors io;
    private MinecraftServer server;
    private String session = UUID.randomUUID().toString();
    private String ownerId = "";
    private EntityPlayerMP ownerPlayer;
    private JsonObject baseline;
    private int sequence = -1, ticks;
    private long nextSend;
    private boolean inFlight;
    private volatile Completion completion;

    public ObservationBridge(String url, IoExecutors io) { baseUrl = url; this.io = io; }

    /** Called only on the game thread. No action may use an unacknowledged session. */
    public String actionSession(EntityPlayerMP player) {
        return server == MinecraftServer.getServer() && baseline != null
                && ownerPlayer == player && baseline.get("dimension").getAsInt() == player.dimension
                && ownerId.equals(player.getUniqueID().toString()) ? session : null;
    }

    private static final class Completion {
        final String session;
        final ObservationDiff diff;
        final int sequence;
        final boolean ok;
        Completion(String s, ObservationDiff d, int n, boolean success) { session = s; diff = d; sequence = n; ok = success; }
    }

    private void reset() {
        session = UUID.randomUUID().toString(); baseline = null; sequence = -1; nextSend = 0;
    }

    @SubscribeEvent public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) { return; }
        MinecraftServer current = MinecraftServer.getServer();
        if (current != server) { server = current; ownerId = ""; reset(); }
        Completion done = completion;
        if (done != null) {
            completion = null; inFlight = false;
            if (done.session.equals(session)) {
                if (done.ok) { baseline = done.diff.state; sequence = done.sequence; nextSend = System.currentTimeMillis() + 5000; }
                else { reset(); nextSend = System.currentTimeMillis() + 5000; }
            }
        }
        if (++ticks % 20 != 0 || inFlight || server == null) { return; }
        List players = server.getConfigurationManager().playerEntityList;
        // Phase 5 supports the local single-player owner, not multiplayer sessions.
        if (players.size() != 1) { if (!ownerId.isEmpty()) { ownerId = ""; reset(); } return; }
        EntityPlayerMP player = (EntityPlayerMP) players.get(0);
        String id = player.getUniqueID().toString();
        if (!id.equals(ownerId) || ownerPlayer != player) { ownerId = id; ownerPlayer = player; reset(); }
        if (baseline == null && System.currentTimeMillis() < nextSend) { return; }
        final ObservationDiff diff = new ObservationDiff(baseline, capture(player));
        // Identity/dimension changes revoke all actions from the previous observation session.
        if (diff.snapshot && baseline != null) { reset(); }
        if (!diff.snapshot && diff.events.size() == 0 && System.currentTimeMillis() < nextSend) { return; }
        final String sentSession = session;
        final int sentSequence = sequence + 1;
        JsonObject envelope = new JsonObject();
        envelope.addProperty("version", 1); envelope.addProperty("session", sentSession); envelope.addProperty("sequence", sentSequence);
        envelope.add(diff.snapshot ? "state" : "events", diff.snapshot ? diff.state : diff.events);
        final String body = envelope.toString();
        inFlight = true;
        boolean accepted = io.execute(IoExecutors.Lane.OBSERVATION, new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try {
                    send(diff.snapshot ? "/v1/snapshot" : "/v1/events", body, sentSequence);
                    ok = true;
                    if (diff.snapshot || diff.events.size() > 0) {
                        LogManager.getLogger(CompanionMod.MOD_ID).info("Observation {} sequence={} events={} bytes={}",
                                diff.snapshot ? "snapshot" : "delta", sentSequence, diff.events.size(), body.getBytes(StandardCharsets.UTF_8).length);
                    }
                } catch (IOException | RuntimeException error) {
                    LogManager.getLogger(CompanionMod.MOD_ID).warn("Observation sync failed ({}); resync after 5s", error.getClass().getSimpleName());
                } finally { completion = new Completion(sentSession, diff, sentSequence, ok); }
            }
        });
        if (!accepted) {
            completion = new Completion(sentSession, diff, sentSequence, false);
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Observation executor rejected request");
        }
    }

    private void send(String path, String body, int expectedSequence) throws IOException {
        URL url = new URL(baseUrl.replaceAll("/+$", "") + path);
        if (!(url.getProtocol().equals("http") || url.getProtocol().equals("https")) || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null) {
            throw new IOException("Invalid daemon URL");
        }
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(2000); conn.setReadTimeout(3000); conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("POST"); conn.setDoOutput(true); conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        if (data.length > 32768) { throw new IOException("Observation too large"); }
        conn.setFixedLengthStreamingMode(data.length);
        try {
            try (OutputStream out = conn.getOutputStream()) { out.write(data); }
            if (conn.getResponseCode() != 200) { throw new IOException("Observation rejected"); }
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[512]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 2048) { throw new IOException("Invalid acknowledgement size"); }
                    out.write(buffer, 0, n);
                }
                JsonObject ack = new JsonParser().parse(new String(out.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!ack.has("version") || !ack.get("version").toString().equals("1") || !ack.has("sequence")
                        || !ack.get("sequence").toString().equals(Integer.toString(expectedSequence))
                        || !ack.has("synced") || !ack.get("synced").toString().equals("true")) { throw new IOException("Invalid acknowledgement"); }
            }
        } finally { conn.disconnect(); }
    }

    private JsonArray position(Entity entity) {
        JsonArray result = new JsonArray();
        result.add(new JsonPrimitive(entity.posX)); result.add(new JsonPrimitive(entity.posY)); result.add(new JsonPrimitive(entity.posZ)); return result;
    }

    private JsonObject capture(EntityPlayerMP player) {
        JsonObject state = new JsonObject(), owner = new JsonObject(), inventory = new JsonObject(),
                hostiles = new JsonObject(), items = new JsonObject();
        state.addProperty("dimension", player.dimension);
        owner.add("position", position(player)); owner.addProperty("health", player.getHealth());
        for (ItemStack stack : player.inventory.mainInventory) { addItem(inventory, stack); }
        for (ItemStack stack : player.inventory.armorInventory) { addItem(inventory, stack); }
        owner.add("inventory", inventory); state.add("owner", owner);
        CompanionEntity companion = null;
        for (Object item : player.worldObj.loadedEntityList) {
            if (item instanceof CompanionEntity && ((CompanionEntity) item).isEntityAlive()
                    && ((CompanionEntity) item).ownerId().equals(ownerId)) { companion = (CompanionEntity) item; break; }
        }
        if (companion == null) { state.add("companion", JsonNull.INSTANCE); }
        else {
            JsonObject value = new JsonObject(); value.addProperty("id", companion.getUniqueID().toString());
            value.add("position", position(companion)); value.addProperty("health", companion.getHealth());
            value.addProperty("task", companion.task()); value.addProperty("result", companion.lastResult());
            JsonObject carried = new JsonObject();
            for (Map.Entry<String, Integer> entry : companion.inventoryCounts().entrySet()) {
                carried.addProperty(entry.getKey(), entry.getValue());
            }
            value.add("inventory", carried); state.add("companion", value);
            final CompanionEntity center = companion;
            List<EntityLivingBase> nearby = new ArrayList<EntityLivingBase>();
            for (Object item : player.worldObj.getEntitiesWithinAABB(EntityLivingBase.class, companion.boundingBox.expand(16, 16, 16))) {
                EntityLivingBase mob = (EntityLivingBase) item;
                if (mob instanceof IMob && mob.isEntityAlive() && mob.getDistanceSqToEntity(companion) <= 256) { nearby.add(mob); }
            }
            Collections.sort(nearby, new Comparator<EntityLivingBase>() {
                @Override public int compare(EntityLivingBase a, EntityLivingBase b) { return Double.compare(a.getDistanceSqToEntity(center), b.getDistanceSqToEntity(center)); }
            });
            for (int i = 0; i < Math.min(16, nearby.size()); i++) {
                EntityLivingBase mob = nearby.get(i); JsonObject value2 = new JsonObject();
                String type = EntityList.getEntityString(mob);
                value2.addProperty("type", type == null ? "unknown" : type.replaceAll("[^A-Za-z0-9_.:-]", "_"));
                value2.addProperty("distance", Math.floor(mob.getDistanceToEntity(companion) / 2) * 2);
                hostiles.add("mob-" + mob.getEntityId(), value2);
            }
            List<EntityItem> dropped = new ArrayList<EntityItem>();
            for (Object item : player.worldObj.getEntitiesWithinAABB(EntityItem.class, companion.boundingBox.expand(16, 16, 16))) {
                EntityItem drop = (EntityItem) item;
                ItemStack stack = drop.getEntityItem();
                if (!drop.isDead && stack != null && stack.stackSize > 0
                        && drop.getDistanceSqToEntity(companion) <= CompanionEntity.ITEM_RANGE_SQUARED) { dropped.add(drop); }
            }
            Collections.sort(dropped, new Comparator<EntityItem>() {
                @Override public int compare(EntityItem a, EntityItem b) { return Double.compare(a.getDistanceSqToEntity(center), b.getDistanceSqToEntity(center)); }
            });
            for (int i = 0; i < Math.min(16, dropped.size()); i++) {
                EntityItem drop = dropped.get(i); JsonObject value2 = new JsonObject();
                Object name = Item.itemRegistry.getNameForObject(drop.getEntityItem().getItem());
                value2.addProperty("type", name == null ? "unknown" : name.toString().replaceAll("[^A-Za-z0-9_.:-]", "_"));
                value2.addProperty("distance", Math.floor(drop.getDistanceToEntity(companion) / 2) * 2);
                items.add("item-" + drop.getEntityId(), value2);
            }
        }
        state.add("hostiles", hostiles); state.add("items", items); return state;
    }

    private void addItem(JsonObject inventory, ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) { return; }
        Object name = Item.itemRegistry.getNameForObject(stack.getItem());
        if (name == null) { return; }
        String key = name.toString();
        inventory.addProperty(key, stack.stackSize + (inventory.has(key) ? inventory.get(key).getAsInt() : 0));
    }
}

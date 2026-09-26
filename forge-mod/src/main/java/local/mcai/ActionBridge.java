package local.mcai;

import com.google.gson.*;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.event.ServerChatEvent;
import org.apache.logging.log4j.LogManager;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;

/** Game-thread authority; control, results and Social each have independent bounded workers. */
public final class ActionBridge {
    private final DaemonClient client;
    private final ObservationBridge observations;
    private final GoalState state = new GoalState();
    private final IntentOrder intentOrder = new IntentOrder();
    private EntityPlayerMP owner;
    private CompanionEntity active;
    private String expectedCompanion;
    private int expectedDimension, sentRevision = -1;
    private long nextPoll, deadline;
    private boolean inFlight, resultInFlight;
    private volatile Completion completion;
    private volatile ResultCompletion resultCompletion;
    private final ArrayDeque<ResultJob> results = new ArrayDeque<ResultJob>();

    private static final class Completion {
        final String session; final int revision; final JsonObject response; final long received;
        Completion(String s, int r, JsonObject response) {
            session = s; revision = r; this.response = response; received = System.nanoTime();
        }
    }
    private static final class ResultJob {
        final JsonObject body; final String path; int attempts; long retryAt;
        ResultJob(JsonObject body) { this(body, "/v1/action-result"); }
        ResultJob(JsonObject body, String path) { this.body = body; this.path = path; }
    }
    private static final class ResultCompletion {
        final ResultJob job; final boolean ok;
        ResultCompletion(ResultJob job, boolean ok) { this.job = job; this.ok = ok; }
    }

    public ActionBridge(String url, ObservationBridge observations) {
        client = new DaemonClient(url); this.observations = observations;
    }
    private void reply(String text) { if (owner != null) { owner.addChatMessage(new ChatComponentText("[Companion] " + text)); } }
    private JsonObject envelope(String session, int revision) {
        JsonObject body = new JsonObject(); body.addProperty("version", 1);
        body.addProperty("session", session); body.addProperty("goalRevision", revision); return body;
    }

    private void synchronize(EntityPlayerMP player) {
        String session = player == null ? null : observations.actionSession(player);
        if (owner != player || !Objects.equals(state.session, session)) {
            cancelActive("disconnected");
            if (state.session != null && state.goal != null && state.revision < Integer.MAX_VALUE) {
                JsonObject cancel = envelope(state.session, state.revision + 1); cancel.add("goal", JsonNull.INSTANCE);
                if (results.size() < 64) { results.add(new ResultJob(cancel, "/v1/goal")); }
                else { LogManager.getLogger(CompanionMod.MOD_ID).warn("Old goal revocation could not be queued"); }
            }
            state.reset(session); intentOrder.reset(); sentRevision = -1; nextPoll = 0;
            owner = player; expectedCompanion = null;
        }
    }

    /** Manual actions invalidate AI work immediately, even when the daemon is unavailable. */
    public void manualOverride(EntityPlayerMP player) {
        synchronize(player); intentOrder.manual(); cancelActive("replaced"); state.replace(null); nextPoll = 0;
    }

    public IntentOrder.Ticket captureIntent(EntityPlayerMP player) {
        synchronize(player); return intentOrder.capture();
    }

    /** Returns false for stale/rejected intents so their optimistic Social reply is not displayed. */
    public boolean acceptIntent(EntityPlayerMP player, IntentOrder.Ticket ticket, String goal) {
        synchronize(player);
        if (!intentOrder.current(ticket)) { reply("以前の指示への返答は取り消しました。"); return false; }
        return requestGoal(player, goal, ticket);
    }

    public void stopFromChat(EntityPlayerMP player) { requestGoal(player, "stop", null); }

    @SubscribeEvent public void onChat(ServerChatEvent event) {
        String message = event.message.trim();
        if (!(message.equals("!agent do") || message.startsWith("!agent do "))) { return; }
        event.setCanceled(true);
        synchronize(event.player);
        String[] parts = message.split("\\s+");
        if (parts.length != 3 || !(parts[2].equals("follow") || parts[2].equals("look") || parts[2].equals("stop"))) {
            reply("使い方: !agent do follow / look / stop"); return;
        }
        requestGoal(event.player, parts[2].equals("follow") ? "follow_owner" : parts[2].equals("look") ? "look_at_owner" : "stop", null);
    }

    private boolean requestGoal(EntityPlayerMP player, String goal, IntentOrder.Ticket ticket) {
        synchronize(player);
        if (!(goal.equals("follow_owner") || goal.equals("look_at_owner") || goal.equals("stop"))) { return false; }
        if (goal.equals("stop")) {
            // A delayed natural stop must not cancel a newer explicit action.
            if (ticket != null) { intentOrder.accept(ticket); cancelActive("replaced"); state.replace(null); nextPoll = 0; }
            else { manualOverride(player); }
            CompanionEntity companion = CompanionCommands.find(player);
            if (companion != null) { companion.stop(); }
            reply("停止しました。待機中の判断も取り消しました。"); return true;
        }
        MinecraftServer server = MinecraftServer.getServer();
        CompanionEntity companion = CompanionCommands.find(player);
        if (server.getConfigurationManager().playerEntityList.size() != 1 || !player.isEntityAlive()
                || state.session == null || companion == null || companion.worldObj != player.worldObj) {
            reply("Companionと観測の同期を確認してください。ワールド内で数秒待ってから試せます。"); return false;
        }
        if (results.size() >= 60) { reply("実行結果の送信が混雑しています。少し待ってください。"); return false; }
        if (ticket == null) { intentOrder.manual(); } else { intentOrder.accept(ticket); }
        cancelActive("replaced"); companion.stop();
        state.replace(goal);
        expectedCompanion = companion.getUniqueID().toString(); expectedDimension = player.dimension;
        deadline = System.nanoTime() + 60000000000L; nextPoll = 0;
        reply("新しい指示を受け付けました。判断を待っています。");
        return true;
    }

    private void result(String status, String reason) {
        if (state.session == null || state.actionId == null) { return; }
        JsonObject body = envelope(state.session, state.revision);
        body.addProperty("actionId", state.actionId); body.addProperty("status", status); body.addProperty("reason", reason);
        if (results.size() >= 64) {
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Action result queue full; notification lost");
            reply("実行結果を送信できませんでした。接続を確認してください。"); return;
        }
        results.add(new ResultJob(body));
        LogManager.getLogger(CompanionMod.MOD_ID).info("Action result revision={} status={} reason={}", state.revision, status, reason);
    }
    private void cancelActive(String reason) {
        if (active != null) {
            active.stop();
            if (state.finish(state.session, state.revision, state.actionId, "cancelled")) { result("cancelled", reason); }
            active = null;
        }
    }
    private void fail(String reason) {
        intentOrder.manual(); // Pending natural instructions must not restart a failed/disconnected action.
        if (active != null) { active.stop(); active = null; }
        if (state.actionId != null && state.status.equals("running")) {
            state.finish(state.session, state.revision, state.actionId, "failed"); result("failed", reason);
        }
        state.replace(null); nextPoll = 0;
        reply("指示を完了できなかったため停止しました（" + reason + "）。");
    }

    private void consume(Completion done) {
        if (!state.current(done.session, done.revision)) { return; }
        if (done.response == null) {
            if (state.goal != null) { fail("disconnected"); }
            return;
        }
        try {
            ActionProtocol.validateEnvelope(done.response, done.session, done.revision);
            sentRevision = done.revision;
            String status = ActionProtocol.string(done.response, "status");
            if (status.equals("failed") && (state.status.equals("thinking") || state.status.equals("running"))) { fail("unsafe_state"); return; }
            if (!status.equals("ready") || !state.status.equals("thinking")) { return; }
            ActionProtocol action = new ActionProtocol(done.response.getAsJsonObject("action"));
            if (!state.claim(done.session, done.revision, action.id)) { return; }
            CompanionEntity companion = CompanionCommands.find(owner);
            if (System.nanoTime() - done.received > 5000000000L || companion == null || !owner.isEntityAlive()
                    || companion.worldObj != owner.worldObj || owner.dimension != expectedDimension
                    || !action.companionId.equals(expectedCompanion)
                    || !action.safe(state.goal, companion.getUniqueID().toString(), owner.dimension,
                                    companion.getHealth(), companion.getDistanceSqToEntity(owner))) {
                fail("unsafe_state"); return;
            }
            active = companion;
            if (action.type.equals("follow")) { companion.follow(); result("running", "accepted"); reply("追従を始めます。"); }
            else if (action.type.equals("look")) { companion.look(); result("running", "accepted"); reply("そちらを向きます。"); }
            else {
                companion.stop(); state.finish(state.session, state.revision, action.id, "succeeded");
                result("succeeded", "completed"); active = null; reply("判断結果に従って待機します。");
            }
        } catch (RuntimeException e) {
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Rejected action response ({})", e.getClass().getSimpleName());
            if (state.goal != null) { fail("unsafe_state"); }
        }
    }

    @SubscribeEvent public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) { return; }
        MinecraftServer server = MinecraftServer.getServer();
        List players = server == null ? null : server.getConfigurationManager().playerEntityList;
        synchronize(players != null && players.size() == 1 ? (EntityPlayerMP) players.get(0) : null);
        Completion done = completion;
        if (done != null) { completion = null; inFlight = false; consume(done); }
        if (state.status.equals("thinking") && System.nanoTime() > deadline) { fail("expired"); }
        if (active != null && state.status.equals("running")) {
            if (!active.isEntityAlive() || owner == null || !owner.isEntityAlive() || active.worldObj != owner.worldObj) { fail("companion_unavailable"); }
            else if (active.getHealth() <= 6 || active.getDistanceSqToEntity(owner) > 1024) { fail("unsafe_state"); }
            else if (active.lastResult().equals("path_not_found")) { fail("path_not_found"); }
            else if (active.task().equals("idle")) {
                boolean success = active.lastResult().equals("look_completed");
                state.finish(state.session, state.revision, state.actionId, success ? "succeeded" : "failed");
                result(success ? "succeeded" : "failed", success ? "completed" : "owner_unavailable"); active = null;
            }
        }
        sendResults();
        if (owner == null || state.session == null || inFlight || System.nanoTime() < nextPoll) { return; }
        if (sentRevision == state.revision && !(state.status.equals("thinking") || state.status.equals("running"))) { return; }
        final String session = state.session; final int revision = state.revision;
        final JsonObject body = envelope(session, revision);
        body.add("goal", state.goal == null ? JsonNull.INSTANCE : new JsonPrimitive(state.goal));
        inFlight = true; nextPoll = System.nanoTime() + 1000000000L;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                JsonObject response = null;
                try { response = client.post("/v1/goal", body); }
                catch (Exception e) { LogManager.getLogger(CompanionMod.MOD_ID).warn("Goal control unavailable ({})", e.getClass().getSimpleName()); }
                finally { completion = new Completion(session, revision, response); }
            }
        }, "mc-ai-control");
        worker.setDaemon(true); worker.start();
    }

    private void sendResults() {
        ResultCompletion done = resultCompletion;
        if (done != null) {
            resultCompletion = null; resultInFlight = false;
            if (results.peek() == done.job) {
                if (done.ok || ++done.job.attempts >= 3) {
                    results.poll();
                    if (!done.ok) {
                        LogManager.getLogger(CompanionMod.MOD_ID).warn("Action result delivery failed after 3 attempts");
                        reply("実行結果の通知に失敗しました。ゲーム内の状態は !agent status で確認できます。");
                    }
                } else { done.job.retryAt = System.nanoTime() + 1000000000L; }
            }
        }
        if (resultInFlight || results.isEmpty() || System.nanoTime() < results.peek().retryAt) { return; }
        final ResultJob job = results.peek(); resultInFlight = true;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try {
                    JsonObject ack = client.post(job.path, job.body);
                    if (job.path.equals("/v1/goal")) {
                        ActionProtocol.validateEnvelope(ack, job.body.get("session").getAsString(), job.body.get("goalRevision").getAsInt());
                        ok = ActionProtocol.string(ack, "status").equals("idle");
                    } else {
                        ok = ActionProtocol.integer(ack, "version", 1) && ack.has("accepted") && ack.get("accepted").toString().equals("true");
                    }
                } catch (Exception e) { /* Bounded retries; never re-execute the action. */ }
                finally { resultCompletion = new ResultCompletion(job, ok); }
            }
        }, "mc-ai-results");
        worker.setDaemon(true); worker.start();
    }
}

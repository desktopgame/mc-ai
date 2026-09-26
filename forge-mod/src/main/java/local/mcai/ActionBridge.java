package local.mcai;

import com.google.gson.*;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
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
    private final IoExecutors io;
    private final GoalState state = new GoalState();
    private final IntentOrder intentOrder = new IntentOrder();
    private final boolean verbose;
    private EntityPlayerMP owner;
    private CompanionEntity active;
    private String expectedCompanion;
    private int expectedDimension, sentRevision = -1;
    private long nextPoll, deadline;
    private boolean inFlight, resultInFlight;
    private volatile Completion completion;
    private volatile ResultCompletion resultCompletion;
    private volatile String displayState = "idle";
    private final ArrayDeque<ResultJob> results = new ArrayDeque<ResultJob>();
    // v2 Skill dialogue. Kept separate from the legacy GoalState so a Skill never claims a legacy action.
    private final SkillExecutionState skillState = new SkillExecutionState();
    private boolean skillActive, skillCancelPending, skillInFlight, openInFlight, openDone, skillDone, skillCancelSent;
    private String skillEpoch, skillItem, skillField, claimedActionId, claimedItem, claimedField, lastIssuedActionId;
    private int skillCount, claimedSequence, claimedMaxCount, lastIssuedSequence;
    private long claimedDeadline;
    private JsonObject skillGoal, pendingSkillResult;
    private volatile JsonObject openResponse, skillResponse;
    /** Control lease: a verified same-epoch/session/revision response keeps the action alive this long. */
    private static final long CONTROL_LEASE_NANOS = 5000000000L;

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

    public ActionBridge(String url, ObservationBridge observations, IoExecutors io, boolean verbose) {
        client = new DaemonClient(url); this.observations = observations; this.io = io; this.verbose = verbose;
    }
    private void reply(String text) { if (owner != null) { owner.addChatMessage(new ChatComponentText("[Companion] " + text)); } }
    /** Internal lifecycle chatter; the on-screen icon covers this by default. */
    private void debugReply(String text) { if (verbose) { reply(text); } }
    /** thinking/running/idle for the HUD icon, cross-thread via the volatile field. */
    public String displayState() { return displayState; }
    private JsonObject envelope(String session, int revision) {
        JsonObject body = new JsonObject(); body.addProperty("version", 1);
        body.addProperty("session", session); body.addProperty("goalRevision", revision); return body;
    }

    private void synchronize(EntityPlayerMP player) {
        String session = player == null ? null : observations.actionSession(player);
        if (owner != player || !Objects.equals(state.session, session)) {
            cancelActive("disconnected");
            closeSkill("disconnected", false);
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
        synchronize(player); intentOrder.manual(); cancelActive("replaced");
        closeSkill("replaced", true);
        state.replace(null); nextPoll = 0;
    }

    public IntentOrder.Ticket captureIntent(EntityPlayerMP player) {
        synchronize(player); return intentOrder.capture();
    }

    /** Returns false for stale/rejected intents so their optimistic Social reply is not displayed. */
    public boolean acceptIntent(EntityPlayerMP player, IntentOrder.Ticket ticket, String goal) {
        synchronize(player);
        if (!intentOrder.current(ticket)) { debugReply("以前の指示への返答は取り消しました。"); return false; }
        return requestGoal(player, goal, ticket);
    }

    public void stopFromChat(EntityPlayerMP player) { requestGoal(player, "stop", null); }

    @SubscribeEvent public void onChat(ServerChatEvent event) {
        String message = event.message.trim();
        if (!(message.equals("!agent do") || message.startsWith("!agent do "))) { return; }
        event.setCanceled(true);
        synchronize(event.player);
        String[] parts = message.split("\\s+");
        if (parts.length == 5 && parts[2].equals("collect_drop")) {
            int count;
            try { count = Integer.parseInt(parts[4]); } catch (NumberFormatException error) { reply("使い方: !agent do collect_drop <アイテム> <個数>"); return; }
            if (!SkillProtocol.ITEMS.contains(parts[3]) || count < 1 || count > 64) {
                reply("使い方: !agent do collect_drop <アイテム> <個数> (1-64)"); return;
            }
            requestSkill(event.player, parts[3], count);
            return;
        }
        if (parts.length == 4 && parts[2].equals("mine")) {
            if (!SkillProtocol.BLOCKS.contains(parts[3])) { reply("使い方: !agent do mine <ブロック>"); return; }
            requestMine(event.player, parts[3]);
            return;
        }
        if (parts.length != 3 || !(parts[2].equals("follow") || parts[2].equals("look") || parts[2].equals("stop")
                || parts[2].equals("pickup") || parts[2].equals("deposit"))) {
            reply("使い方: !agent do follow / look / stop / pickup / deposit / collect_drop <アイテム> <個数> / mine <ブロック>"); return;
        }
        requestGoal(event.player, parts[2].equals("follow") ? "follow_owner" : parts[2].equals("look") ? "look_at_owner"
                : parts[2].equals("pickup") ? "pickup_item" : parts[2].equals("deposit") ? "deposit_items" : "stop", null);
    }

    private boolean requestGoal(EntityPlayerMP player, String goal, IntentOrder.Ticket ticket) {
        synchronize(player);
        if (!(goal.equals("follow_owner") || goal.equals("look_at_owner") || goal.equals("stop")
                || goal.equals("pickup_item") || goal.equals("deposit_items"))) { return false; }
        if (goal.equals("stop")) {
            // A delayed natural stop must not cancel a newer explicit action.
            if (ticket != null) { intentOrder.accept(ticket); cancelActive("replaced"); closeSkill("replaced", true); state.replace(null); nextPoll = 0; }
            else { manualOverride(player); }
            CompanionEntity companion = CompanionCommands.find(player);
            if (companion != null) { companion.stop(); }
            debugReply("停止しました。待機中の判断も取り消しました。"); return true;
        }
        MinecraftServer server = MinecraftServer.getServer();
        CompanionEntity companion = CompanionCommands.find(player);
        if (server.getConfigurationManager().playerEntityList.size() != 1 || !player.isEntityAlive()
                || state.session == null || companion == null || companion.worldObj != player.worldObj) {
            reply("Companionと観測の同期を確認してください。ワールド内で数秒待ってから試せます。"); return false;
        }
        if (results.size() >= 60) { reply("実行結果の送信が混雑しています。少し待ってください。"); return false; }
        if (ticket == null) { intentOrder.manual(); } else { intentOrder.accept(ticket); }
        cancelActive("replaced"); closeSkill("replaced", true); companion.stop();
        state.replace(goal);
        expectedCompanion = companion.getUniqueID().toString(); expectedDimension = player.dimension;
        deadline = System.nanoTime() + 60000000000L; nextPoll = 0;
        debugReply("新しい指示を受け付けました。判断を待っています。");
        return true;
    }

    // ---- v2 Skill: collect_drop / mine ----------------------------------
    private boolean requestSkill(EntityPlayerMP player, String item, int count) {
        JsonObject goal = new JsonObject();
        goal.addProperty("type", "collect_drop");
        JsonObject target = new JsonObject(); target.addProperty("item", item); goal.add("target", target);
        goal.addProperty("count", count); goal.add("constraints", new JsonArray());
        return startSkill(player, goal, "item", item, count, "collect_drop");
    }

    private boolean requestMine(EntityPlayerMP player, String block) {
        JsonObject goal = new JsonObject();
        goal.addProperty("type", "mine");
        JsonObject target = new JsonObject(); target.addProperty("block", block); goal.add("target", target);
        goal.addProperty("count", 1); goal.add("constraints", new JsonArray());
        return startSkill(player, goal, "block", block, 1, "mine");
    }

    private boolean startSkill(EntityPlayerMP player, JsonObject goal, String field, String name, int count, String label) {
        synchronize(player);
        MinecraftServer server = MinecraftServer.getServer();
        CompanionEntity companion = CompanionCommands.find(player);
        if (server.getConfigurationManager().playerEntityList.size() != 1 || !player.isEntityAlive()
                || state.session == null || companion == null || companion.worldObj != player.worldObj) {
            reply("Companionと観測の同期を確認してください。ワールド内で数秒待ってから試せます。"); return false;
        }
        if (results.size() >= 60) { reply("実行結果の送信が混雑しています。少し待ってください。"); return false; }
        cancelActive("replaced"); closeSkill("replaced", false); intentOrder.manual();
        companion.stop(); active = null;
        state.replace(label);
        skillState.reset(state.session); skillState.revision = state.revision;
        skillActive = true; skillCancelPending = false; skillEpoch = null;
        claimedActionId = null; claimedItem = null; claimedField = null; lastIssuedActionId = null;
        skillGoal = goal; skillField = field; skillItem = name; skillCount = count;
        expectedCompanion = companion.getUniqueID().toString(); expectedDimension = player.dimension;
        nextPoll = 0;
        debugReply("新しい指示を受け付けました。判断を待っています。");
        return true;
    }

    private JsonObject skillEnvelope() {
        JsonObject body = new JsonObject();
        body.addProperty("version", 2);
        body.addProperty("session", state.session);
        body.addProperty("daemonEpoch", skillEpoch);
        body.addProperty("goalRevision", state.revision);
        return body;
    }

    /** Stops local Skill execution. notifyNull leaves the cancel handshake to finish before clearing state. */
    private void closeSkill(String reason, boolean notifyNull) {
        if (!skillActive) { return; }
        // A terminal receipt that is already prepared must be delivered, not replaced by a cancel.
        if (pendingSkillResult == null && skillEpoch != null && skillState.skillInstanceId != null) {
            if (claimedActionId != null && !skillState.known(claimedActionId)) {
                if (enqueueSkillResult(claimedActionId, claimedSequence, "cancelled", reason, claimedField, claimedItem, 0)) {
                    skillState.complete("cancelled", reason, 0);
                }
            } else if (lastIssuedActionId != null && !skillState.known(lastIssuedActionId)) {
                enqueueSkillResult(lastIssuedActionId, lastIssuedSequence, "cancelled", reason, skillField, skillItem, 0);
            }
        }
        if (active != null) { active.stop(); active = null; }
        claimedActionId = null; claimedItem = null; claimedField = null;
        if (notifyNull && skillEpoch != null && state.session != null) {
            skillCancelPending = true; skillCancelSent = false;
        } else {
            skillActive = false; skillCancelPending = false; skillEpoch = null; lastIssuedActionId = null;
        }
    }

    private boolean enqueueSkillResult(String actionId, int sequence, String status, String reason,
                                       String field, String name, int count) {
        if (state.session == null || skillEpoch == null || skillState.skillInstanceId == null || name == null) { return false; }
        JsonObject body = skillEnvelope();
        body.addProperty("skillInstanceId", skillState.skillInstanceId);
        body.addProperty("actionId", actionId);
        body.addProperty("actionSequence", sequence);
        body.addProperty("status", status);
        body.addProperty("reason", reason);
        JsonObject payload = new JsonObject();
        payload.addProperty(field, name); payload.addProperty("count", count);
        body.add(field.equals("block") ? "destroyed" : "acquired", payload);
        return enqueueSkillResultBody(body);
    }

    /** Reserves a bounded result slot. Returns false only when the queue is full, never silently discards. */
    private boolean enqueueSkillResultBody(JsonObject body) {
        if (results.size() >= 64) {
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Skill result queue full; receipt retained for retry");
            return false;
        }
        results.add(new ResultJob(body, "/v2/action-result"));
        return true;
    }

    private void startSkillOpen() {
        final String session = state.session;
        final JsonObject body = new JsonObject();
        body.addProperty("version", 2); body.addProperty("session", session);
        openInFlight = true; openDone = false; nextPoll = System.nanoTime() + 1000000000L;
        boolean accepted = io.execute(IoExecutors.Lane.CONTROL, new Runnable() {
            @Override public void run() {
                JsonObject response = null;
                try { response = client.post("/v2/execution/open", body); }
                catch (Exception e) { LogManager.getLogger(CompanionMod.MOD_ID).warn("Skill open unavailable ({})", e.getClass().getSimpleName()); }
                finally { openResponse = response; openDone = true; }
            }
        });
        if (!accepted) {
            openResponse = null; openDone = true;
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Skill open executor rejected request");
        }
    }

    private void startSkillGoal(final JsonObject goal) {
        final JsonObject body = skillEnvelope();
        body.add("goal", goal == null ? JsonNull.INSTANCE : goal);
        skillInFlight = true; skillDone = false; nextPoll = System.nanoTime() + 1000000000L;
        boolean accepted = io.execute(IoExecutors.Lane.CONTROL, new Runnable() {
            @Override public void run() {
                JsonObject response = null;
                try { response = client.post("/v2/goal", body); }
                catch (Exception e) { LogManager.getLogger(CompanionMod.MOD_ID).warn("Skill control unavailable ({})", e.getClass().getSimpleName()); }
                finally { skillResponse = response; skillDone = true; }
            }
        });
        if (!accepted) {
            skillResponse = null; skillDone = true;
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Skill control executor rejected request");
        }
    }

    private void skillTick() {
        if (owner == null || state.session == null) { closeSkill("disconnected", false); return; }
        long now = System.nanoTime();
        if (claimedActionId != null && active != null) {
            if (!active.isEntityAlive() || !owner.isEntityAlive() || active.worldObj != owner.worldObj) {
                finishSkillAction("failed", "companion_unavailable", 0);
            } else if (active.getHealth() <= 6 || active.getDistanceSqToEntity(owner) > 1024) {
                active.stop(); finishSkillAction("failed", "unsafe_state", 0);
            } else if ("block".equals(claimedField)) {
                if (active.mineResolved()) {
                    int mined = Math.min(active.lastMined(), claimedMaxCount);
                    String outcome = active.mineOutcome();
                    if (mined > 0) { finishSkillAction("succeeded", outcome == null || outcome.isEmpty() ? "completed" : outcome, mined); }
                    else { finishSkillAction("failed", outcome == null || outcome.isEmpty() ? "tool_unavailable" : outcome, 0); }
                } else if (now >= claimedDeadline) {
                    active.stop(); finishSkillAction("failed", "expired", 0);
                } else if (active.lastResult().equals("path_not_found")) {
                    active.stop(); finishSkillAction("failed", "path_not_found", 0);
                }
            } else if (active.pickupResolved()) {
                int stored = Math.min(active.lastPickupStored(), claimedMaxCount);
                String outcome = active.pickupOutcome();
                if (stored > 0) { finishSkillAction("succeeded", outcome == null || outcome.isEmpty() ? "completed" : outcome, stored); }
                else { finishSkillAction("failed", outcome == null || outcome.isEmpty() ? "inventory_full" : outcome, 0); }
            } else if (now >= claimedDeadline) {
                active.stop(); finishSkillAction("failed", "expired", 0);
            } else if (active.lastResult().equals("path_not_found")) {
                active.stop(); finishSkillAction("failed", "path_not_found", 0);
            }
        }
        if (openDone) {
            openDone = false; openInFlight = false;
            JsonObject response = openResponse; openResponse = null;
            if (response == null) { failSkill("disconnected"); return; }
            try {
                if (!ActionProtocol.integer(response, "version", 2)
                        || !ActionProtocol.string(response, "session").equals(state.session)) {
                    throw new IllegalArgumentException("bad open response");
                }
                skillEpoch = ActionProtocol.string(response, "daemonEpoch");
            } catch (RuntimeException e) { failSkill("disconnected"); return; }
        }
        if (skillEpoch == null) {
            if (!openInFlight && now >= nextPoll) { startSkillOpen(); }
            return;
        }
        if (skillCancelPending) {
            if (skillDone) {
                skillDone = false; skillInFlight = false; skillResponse = null;
                if (skillCancelSent) {
                    skillActive = false; skillCancelPending = false; skillEpoch = null;
                    lastIssuedActionId = null; skillCancelSent = false;
                    return;
                }
                // A response that predates the cancel is discarded; the null goal is still due.
            }
            if (!skillInFlight && now >= nextPoll && !skillCancelSent) {
                startSkillGoal(null);
                skillCancelSent = true;
            }
            return;
        }
        if (skillDone) {
            skillDone = false; skillInFlight = false;
            JsonObject response = skillResponse; skillResponse = null;
            consumeSkill(response);
        }
        if (!skillActive || skillInFlight || now < nextPoll) { return; }
        // Poll even while an action is outstanding: the response renews the control lease and lets
        // the Forge observe a Daemon-side cancellation before committing world changes.
        startSkillGoal(skillGoal);
    }

    private void consumeSkill(JsonObject response) {
        if (response == null) { failSkill("disconnected"); return; }
        try {
            SkillProtocol.validateView(response, state.session, state.revision, skillEpoch);
            // A verified control response renews the lease for the action that is running.
            if (active != null) { active.setControlDeadline(System.nanoTime() + CONTROL_LEASE_NANOS); }
            SkillProtocol.Skill skill = SkillProtocol.skill(response);
            if (skill != null) { skillState.skillInstanceId = skill.skillInstanceId; }
            if (skill != null && skill.resultStatus != null) { finishSkill(skill.resultStatus, skill.resultReason, skill.achieved); return; }
            SkillProtocol.Action action = SkillProtocol.action(response);
            if (action == null) { return; }
            lastIssuedActionId = action.actionId; lastIssuedSequence = action.sequence;
            if (claimedActionId != null || skillState.known(action.actionId)) { return; }
            if (owner == null) { failSkill("owner_unavailable"); return; }
            CompanionEntity companion = CompanionCommands.find(owner);
            if (companion == null || !owner.isEntityAlive() || companion.worldObj != owner.worldObj
                    || owner.dimension != action.dimension
                    || !action.companionId.equals(companion.getUniqueID().toString())) {
                failSkill("unsafe_state"); return;
            }
            if (action.field().equals("block")) {
                if (!blockMatches(companion, action.targetRef, action.block)) {
                    if (enqueueSkillResult(action.actionId, action.sequence, "failed", "target_lost", "block", action.block, 0)
                            && skillState.claim(action.actionId, action.sequence)) { skillState.complete("failed", "target_lost", 0); }
                    return;
                }
            } else {
                EntityItem target = findTarget(companion, action.targetRef, action.item);
                if (target == null) {
                    if (enqueueSkillResult(action.actionId, action.sequence, "failed", "target_lost", "item", action.item, 0)
                            && skillState.claim(action.actionId, action.sequence)) { skillState.complete("failed", "target_lost", 0); }
                    return;
                }
                if (target.delayBeforeCanPickup > 0) {
                    if (enqueueSkillResult(action.actionId, action.sequence, "failed", "target_not_ready", "item", action.item, 0)
                            && skillState.claim(action.actionId, action.sequence)) { skillState.complete("failed", "target_not_ready", 0); }
                    return;
                }
            }
            // Reserve room for the running and terminal receipts before mutating the world.
            if (results.size() >= 60) { return; }
            if (!skillState.claim(action.actionId, action.sequence)) { return; }
            claimedActionId = action.actionId; claimedSequence = action.sequence;
            claimedItem = action.name(); claimedField = action.field(); claimedMaxCount = action.maxCount;
            claimedDeadline = System.nanoTime() + (long) action.timeoutMs * 1000000L;
            state.status = "running";
            active = companion;
            companion.setControlDeadline(System.nanoTime() + CONTROL_LEASE_NANOS);
            if (action.field().equals("block")) { companion.mineBlock(action.targetRef, action.block); }
            else { companion.pickupItem(action.targetRef, action.maxCount); }
            enqueueSkillResult(action.actionId, action.sequence, "running", "accepted", action.field(), action.name(), 0);
        } catch (RuntimeException e) {
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Rejected skill view ({})", e.getClass().getSimpleName());
            failSkill("action_failed");
        }
    }

    private void finishSkillAction(String status, String reason, int count) {
        if (claimedActionId == null) { return; }
        if (active != null) { active.stop(); active = null; }
        JsonObject body = skillEnvelope();
        body.addProperty("skillInstanceId", skillState.skillInstanceId);
        body.addProperty("actionId", claimedActionId);
        body.addProperty("actionSequence", claimedSequence);
        body.addProperty("status", status);
        body.addProperty("reason", reason);
        JsonObject payload = new JsonObject();
        payload.addProperty(claimedField, claimedItem); payload.addProperty("count", count);
        body.add(claimedField.equals("block") ? "destroyed" : "acquired", payload);
        // Never mark the action settled unless its terminal receipt has a reserved slot.
        if (enqueueSkillResultBody(body)) {
            skillState.complete(status, reason, count);
            claimedActionId = null; claimedItem = null; claimedField = null;
        } else {
            pendingSkillResult = body;
        }
    }

    private void finishSkill(String status, String reason, int achieved) {
        if ("completed".equals(status)) {
            reply("block".equals(skillField) ? skillItem + " を採掘しました。" : skillItem + "を" + skillCount + "個集めました。");
        } else if ("cancelled".equals(status)) {
            debugReply("指示を取り消しました。");
        } else {
            reply(skillItem + " を完了できませんでした（" + reason + "、" + achieved + "/" + skillCount + "）。");
        }
        if (active != null) { active.stop(); active = null; }
        claimedActionId = null; claimedItem = null; claimedField = null; lastIssuedActionId = null;
        skillActive = false; skillCancelPending = false; skillEpoch = null;
        state.goal = null; state.actionId = null; state.status = "idle";
        nextPoll = 0;
    }

    private void failSkill(String reason) {
        intentOrder.manual();
        if (pendingSkillResult != null) {
            // A real collection already happened; deliver its receipt via flushPendingSkillResult.
            if (active != null) { active.stop(); active = null; }
            claimedActionId = null; claimedItem = null; claimedField = null; lastIssuedActionId = null;
            skillActive = false; skillCancelPending = false; skillEpoch = null;
            state.goal = null; state.actionId = null; state.status = "idle";
            nextPoll = 0;
            return;
        }
        if (skillActive && skillEpoch != null && claimedActionId != null && !skillState.known(claimedActionId)) {
            String field = claimedField != null ? claimedField : skillField;
            String name = claimedItem != null ? claimedItem : skillItem;
            if (enqueueSkillResult(claimedActionId, claimedSequence, "failed", reason, field, name, 0)) {
                skillState.complete("failed", reason, 0);
            }
        }
        if (active != null) { active.stop(); active = null; }
        claimedActionId = null; claimedItem = null; claimedField = null; lastIssuedActionId = null;
        skillActive = false; skillCancelPending = false; skillEpoch = null;
        state.goal = null; state.actionId = null; state.status = "idle";
        nextPoll = 0;
        debugReply("指示を完了できなかったため停止しました（" + reason + "）。");
    }

    /** Retries a terminal receipt that could not be queued when the world was changed. */
    private void flushPendingSkillResult() {
        if (pendingSkillResult == null) { return; }
        JsonObject body = pendingSkillResult;
        if (!enqueueSkillResultBody(body)) { return; }
        pendingSkillResult = null;
        String status = body.get("status").getAsString();
        String reason = body.get("reason").getAsString();
        JsonObject payload = body.has("destroyed") ? body.getAsJsonObject("destroyed") : body.getAsJsonObject("acquired");
        int count = payload.get("count").getAsInt();
        skillState.complete(status, reason, count);
        claimedActionId = null; claimedItem = null; claimedField = null;
    }

    private EntityItem findTarget(CompanionEntity companion, String targetRef, String item) {
        String uuid = targetRef.startsWith("item-") ? targetRef.substring("item-".length()) : targetRef;
        for (Object value : companion.worldObj.getEntitiesWithinAABB(EntityItem.class, companion.boundingBox.expand(16, 16, 16))) {
            EntityItem entity = (EntityItem) value;
            if (entity.isDead || !entity.getUniqueID().toString().equals(uuid)) { continue; }
            ItemStack stack = entity.getEntityItem();
            if (stack == null || stack.stackSize <= 0) { continue; }
            Object name = Item.itemRegistry.getNameForObject(stack.getItem());
            if (name == null || !name.toString().equals(item)) { continue; }
            if (entity.getDistanceSqToEntity(companion) > CompanionEntity.ITEM_RANGE_SQUARED) { continue; }
            return entity;
        }
        return null;
    }

    /** The fixed mine target must still be the expected block within observation range. */
    private boolean blockMatches(CompanionEntity companion, String targetRef, String blockName) {
        String body = targetRef.startsWith("block-") ? targetRef.substring("block-".length()) : targetRef;
        String[] parts = body.split("_");
        if (parts.length != 3) { return false; }
        int x, y, z;
        try { x = Integer.parseInt(parts[0]); y = Integer.parseInt(parts[1]); z = Integer.parseInt(parts[2]); }
        catch (NumberFormatException error) { return false; }
        if (!companion.worldObj.blockExists(x, y, z)) { return false; }
        Block block = companion.worldObj.getBlock(x, y, z);
        if (block == null || block == Blocks.air) { return false; }
        Object name = Block.blockRegistry.getNameForObject(block);
        if (name == null || !name.toString().equals(blockName)) { return false; }
        return companion.getDistanceSq(x + 0.5D, y + 0.5D, z + 0.5D) <= CompanionEntity.ITEM_RANGE_SQUARED;
    }

    private void result(String status, String reason) {
        if (state.session == null || state.actionId == null) { return; }
        JsonObject body = envelope(state.session, state.revision);
        body.addProperty("actionId", state.actionId); body.addProperty("status", status); body.addProperty("reason", reason);
        if (results.size() >= 64) {
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Action result queue full; notification lost");
            debugReply("実行結果を送信できませんでした。接続を確認してください。"); return;
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
        debugReply("指示を完了できなかったため停止しました（" + reason + "）。");
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
            // Items are volatile: re-check just before execution, not only when the decision was made.
            if (action.type.equals("pickup") && !companion.hasItemInRange()) { fail("no_item_in_range"); return; }
            if (action.type.equals("deposit") && companion.carriedCount() <= 0) { fail("inventory_empty"); return; }
            active = companion;
            if (action.type.equals("follow")) { companion.follow(); result("running", "accepted"); debugReply("追従を始めます。"); }
            else if (action.type.equals("look")) { companion.look(); result("running", "accepted"); debugReply("そちらを向きます。"); }
            else if (action.type.equals("pickup")) { companion.pickup(); result("running", "accepted"); debugReply("落ちているものを拾いに行きます。"); }
            else if (action.type.equals("deposit")) { companion.deposit(); result("running", "accepted"); debugReply("持っているものを渡しに行きます。"); }
            else {
                companion.stop(); state.finish(state.session, state.revision, action.id, "succeeded");
                result("succeeded", "completed"); active = null; debugReply("判断結果に従って待機します。");
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
        flushPendingSkillResult();
        if (skillActive) {
            skillTick();
            displayState = state.status.equals("thinking") ? "thinking" : state.status.equals("running") ? "running" : "idle";
            sendResults();
            return;
        }
        Completion done = completion;
        if (done != null) { completion = null; inFlight = false; consume(done); }
        if (state.status.equals("thinking") && System.nanoTime() > deadline) { fail("expired"); }
        if (active != null && state.status.equals("running")) {
            if (!active.isEntityAlive() || owner == null || !owner.isEntityAlive() || active.worldObj != owner.worldObj) { fail("companion_unavailable"); }
            else if (active.getHealth() <= 6 || active.getDistanceSqToEntity(owner) > 1024) { fail("unsafe_state"); }
            else if (active.lastResult().equals("path_not_found")) { fail("path_not_found"); }
            else if (active.task().equals("idle")) {
                String last = active.lastResult();
                boolean success = last.equals("look_completed") || last.equals("pickup_completed") || last.equals("deposit_completed");
                String reason = success ? "completed"
                        : last.equals("no_item_in_range") || last.equals("inventory_full")
                          || last.equals("inventory_empty") || last.equals("owner_inventory_full") ? last : "owner_unavailable";
                state.finish(state.session, state.revision, state.actionId, success ? "succeeded" : "failed");
                result(success ? "succeeded" : "failed", reason); active = null;
            }
        }
        displayState = state.status.equals("thinking") ? "thinking" : state.status.equals("running") ? "running" : "idle";
        sendResults();
        if (owner == null || state.session == null || inFlight || System.nanoTime() < nextPoll) { return; }
        if (sentRevision == state.revision && !(state.status.equals("thinking") || state.status.equals("running"))) { return; }
        final String session = state.session; final int revision = state.revision;
        final JsonObject body = envelope(session, revision);
        body.add("goal", state.goal == null ? JsonNull.INSTANCE : new JsonPrimitive(state.goal));
        inFlight = true; nextPoll = System.nanoTime() + 1000000000L;
        boolean accepted = io.execute(IoExecutors.Lane.CONTROL, new Runnable() {
            @Override public void run() {
                JsonObject response = null;
                try { response = client.post("/v1/goal", body); }
                catch (Exception e) { LogManager.getLogger(CompanionMod.MOD_ID).warn("Goal control unavailable ({})", e.getClass().getSimpleName()); }
                finally { completion = new Completion(session, revision, response); }
            }
        });
        if (!accepted) {
            completion = new Completion(session, revision, null);
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Control executor rejected request");
        }
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
                        debugReply("実行結果の通知に失敗しました。ゲーム内の状態は !agent status で確認できます。");
                    }
                } else { done.job.retryAt = System.nanoTime() + 1000000000L; }
            }
        }
        if (resultInFlight || results.isEmpty() || System.nanoTime() < results.peek().retryAt) { return; }
        final ResultJob job = results.peek(); resultInFlight = true;
        boolean accepted = io.execute(IoExecutors.Lane.RESULTS, new Runnable() {
            @Override public void run() {
                boolean ok = false;
                try {
                    JsonObject ack = client.post(job.path, job.body);
                    if (job.path.equals("/v1/goal")) {
                        ActionProtocol.validateEnvelope(ack, job.body.get("session").getAsString(), job.body.get("goalRevision").getAsInt());
                        ok = ActionProtocol.string(ack, "status").equals("idle");
                    } else if (job.path.equals("/v2/action-result")) {
                        ok = ActionProtocol.integer(ack, "version", 2) && ack.has("accepted")
                                && ack.get("accepted").toString().equals("true");
                    } else {
                        ok = ActionProtocol.integer(ack, "version", 1) && ack.has("accepted") && ack.get("accepted").toString().equals("true");
                    }
                } catch (Exception e) { /* Bounded retries; never re-execute the action. */ }
                finally { resultCompletion = new ResultCompletion(job, ok); }
            }
        });
        if (!accepted) {
            resultCompletion = new ResultCompletion(job, false);
            LogManager.getLogger(CompanionMod.MOD_ID).warn("Result executor rejected request");
        }
    }
}

package local.mcai;

import com.google.gson.*;
import java.util.*;

/** Strict v2 Skill view/action parsing with no game dependencies. */
public final class SkillProtocol {
    public static final List<String> ITEMS = Collections.unmodifiableList(Arrays.asList(
            "minecraft:log", "minecraft:cobblestone", "minecraft:iron_ingot",
            "minecraft:planks", "minecraft:stick"));
    public static final List<String> BLOCKS = Collections.unmodifiableList(Arrays.asList(
            "minecraft:log", "minecraft:log2", "minecraft:cobblestone", "minecraft:stone",
            "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gold_ore",
            "minecraft:diamond_ore", "minecraft:dirt", "minecraft:sand", "minecraft:gravel"));
    /** collect_block MVP: the explicit block -> collected item mapping (mirrors the Daemon table). */
    public static final Map<String, String> COLLECT_BLOCK_ITEMS;
    static {
        Map<String, String> collect = new LinkedHashMap<String, String>();
        collect.put("minecraft:log", "minecraft:log");
        COLLECT_BLOCK_ITEMS = Collections.unmodifiableMap(collect);
    }
    public static final List<String> COLLECT_BLOCKS = Collections.unmodifiableList(
            new ArrayList<String>(COLLECT_BLOCK_ITEMS.keySet()));
    public static final List<String> STATUS = Collections.unmodifiableList(Arrays.asList(
            "idle", "thinking", "running", "completed", "failed", "cancelled"));
    public static final List<String> PHASES = Collections.unmodifiableList(Arrays.asList(
            "selecting", "waiting_action", "cancelling", "terminal"));

    /** The item a collect_block goal collects, or null when the block is unsupported. */
    public static String collectItemFor(String block) { return COLLECT_BLOCK_ITEMS.get(block); }

    /** The capability a Skill needs from the Daemon; collect_drop/mine reuse their v1 capability. */
    public static String capabilityFor(String type) {
        if (type.equals("collect_drop")) { return "collect_drop_v1"; }
        if (type.equals("mine")) { return "mine_v1"; }
        if (type.equals("collect_block")) { return "collect_block_v1"; }
        return null;
    }

    private static void keys(JsonObject o, String... names) {
        Set<String> actual = new HashSet<String>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) { actual.add(e.getKey()); }
        if (!actual.equals(new HashSet<String>(Arrays.asList(names)))) { throw new IllegalArgumentException("Unexpected fields"); }
    }

    private static String string(JsonObject o, String key) {
        JsonElement value = o.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Expected string");
        }
        return value.getAsString();
    }

    private static int integer(JsonObject o, String key, int minimum, int maximum) {
        JsonElement value = o.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Expected integer");
        }
        if (!value.toString().matches("-?[0-9]+")) { throw new IllegalArgumentException("Expected integer"); }
        int parsed = value.getAsInt();
        if (parsed < minimum || parsed > maximum) { throw new IllegalArgumentException("Out of range"); }
        return parsed;
    }

    public static final class Action {
        public final String type, actionId, skillInstanceId, companionId, targetRef, item, block;
        public final int sequence, dimension, maxCount, observationSequence, timeoutMs;
        Action(JsonObject o) {
            type = string(o, "type");
            if (type.equals("pickup_target")) {
                keys(o, "type", "actionId", "actionSequence", "skillInstanceId", "companionId", "dimension",
                        "targetRef", "item", "maxCount", "timeoutMs", "observationSequence");
                item = string(o, "item"); block = null;
                if (!ITEMS.contains(item)) { throw new IllegalArgumentException("Unsupported item"); }
                maxCount = integer(o, "maxCount", 1, 64);
            } else if (type.equals("mine_target")) {
                keys(o, "type", "actionId", "actionSequence", "skillInstanceId", "companionId", "dimension",
                        "targetRef", "block", "timeoutMs", "observationSequence");
                block = string(o, "block"); item = null;
                if (!BLOCKS.contains(block)) { throw new IllegalArgumentException("Unsupported block"); }
                maxCount = 1;
            } else {
                throw new IllegalArgumentException("Unknown action type");
            }
            actionId = string(o, "actionId");
            skillInstanceId = string(o, "skillInstanceId");
            companionId = string(o, "companionId");
            targetRef = string(o, "targetRef");
            if (!actionId.matches("[A-Za-z0-9_-]{1,80}") || !skillInstanceId.matches("[A-Za-z0-9_-]{1,80}")
                    || !companionId.matches("[A-Za-z0-9_-]{1,80}")) { throw new IllegalArgumentException("Invalid identity"); }
            sequence = integer(o, "actionSequence", 1, 100000);
            dimension = integer(o, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE);
            observationSequence = integer(o, "observationSequence", 0, Integer.MAX_VALUE);
            timeoutMs = integer(o, "timeoutMs", 1, 600000);
        }
        public String name() { return item != null ? item : block; }
        public String field() { return item != null ? "item" : "block"; }
    }

    /** Validates the v2 view envelope; leaves skill.result to the caller. */
    public static void validateView(JsonObject o, String session, int revision, String epoch) {
        keys(o, "version", "session", "daemonEpoch", "goalRevision", "status", "error", "skill", "action");
        integer(o, "version", 2, 2);
        integer(o, "goalRevision", revision, revision);
        if (!string(o, "session").equals(session) || !string(o, "daemonEpoch").equals(epoch)
                || !STATUS.contains(string(o, "status"))) {
            throw new IllegalArgumentException("Stale or malformed view");
        }
    }

    public static String status(JsonObject view) { return string(view, "status"); }

    public static Action action(JsonObject view) {
        JsonElement value = view.get("action");
        if (value == null || value.isJsonNull()) { return null; }
        return new Action(value.getAsJsonObject());
    }

    public static final class Progress {
        public final int requested, acquired, mined;
        public final boolean complete;
        Progress(int requested, int acquired, int mined, boolean complete) {
            this.requested = requested; this.acquired = acquired; this.mined = mined; this.complete = complete;
        }
        public boolean sameAs(Progress other) {
            return other != null && requested == other.requested && acquired == other.acquired
                    && mined == other.mined && complete == other.complete;
        }
    }

    /** Strict parse of a Skill progress object, keyed by Skill type. Used for outer and result progress. */
    private static Progress parseProgress(JsonObject progress, String type) {
        Set<String> seen = new HashSet<String>();
        for (Map.Entry<String, JsonElement> e : progress.entrySet()) { seen.add(e.getKey()); }
        Set<String> expected;
        if (type.equals("collect_drop")) { expected = new HashSet<String>(Arrays.asList("requested", "acquired", "complete")); }
        else if (type.equals("mine")) { expected = new HashSet<String>(Arrays.asList("requested", "mined", "complete")); }
        else { expected = new HashSet<String>(Arrays.asList("requested", "acquired", "mined", "complete")); }
        if (!seen.equals(expected)) { throw new IllegalArgumentException("Unexpected progress fields"); }
        int requested = integer(progress, "requested", 1, 64);
        int acquired, mined;
        if (type.equals("mine")) { mined = integer(progress, "mined", 0, 64); acquired = 0; }
        else if (type.equals("collect_drop")) { acquired = integer(progress, "acquired", 0, 64); mined = 0; }
        else { acquired = integer(progress, "acquired", 0, 64); mined = integer(progress, "mined", 0, 64); }
        if (acquired > requested || mined > requested) { throw new IllegalArgumentException("Progress over requested"); }
        JsonElement done = progress.get("complete");
        if (done == null || !done.isJsonPrimitive() || !done.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Expected boolean");
        }
        return new Progress(requested, acquired, mined, done.getAsBoolean());
    }

    public static final class Skill {
        public final String skillInstanceId, type, name, field, phase;
        public final int requested, achieved, acquired, mined;
        public final boolean complete;
        public final String resultStatus, resultReason;
        Skill(JsonObject o) {
            keys(o, "skillInstanceId", "type", "target", "phase", "progress", "result");
            skillInstanceId = string(o, "skillInstanceId");
            type = string(o, "type");
            JsonObject target = o.getAsJsonObject("target");
            if (type.equals("collect_drop")) {
                field = "item"; keys(target, "item"); name = string(target, "item");
                if (!ITEMS.contains(name)) { throw new IllegalArgumentException("Unsupported target"); }
            } else if (type.equals("mine")) {
                field = "block"; keys(target, "block"); name = string(target, "block");
                if (!BLOCKS.contains(name)) { throw new IllegalArgumentException("Unsupported target"); }
            } else if (type.equals("collect_block")) {
                field = "block"; keys(target, "block"); name = string(target, "block");
                if (!COLLECT_BLOCKS.contains(name)) { throw new IllegalArgumentException("Unsupported target"); }
            } else {
                throw new IllegalArgumentException("Unknown skill");
            }
            Progress outer = parseProgress(o.getAsJsonObject("progress"), type);
            requested = outer.requested; acquired = outer.acquired; mined = outer.mined; complete = outer.complete;
            achieved = type.equals("mine") ? mined : acquired;
            phase = string(o, "phase");
            if (!PHASES.contains(phase)) { throw new IllegalArgumentException("Unknown phase"); }
            JsonElement result = o.get("result");
            if (result == null || result.isJsonNull()) {
                // A terminal view must carry its immutable result; a running view must not.
                if (phase.equals("terminal")) { throw new IllegalArgumentException("Terminal phase without result"); }
                resultStatus = null; resultReason = null;
            } else {
                if (!phase.equals("terminal")) { throw new IllegalArgumentException("Result on a non-terminal phase"); }
                JsonObject r = result.getAsJsonObject();
                keys(r, "skillInstanceId", "status", "reason", "progress");
                if (!string(r, "skillInstanceId").equals(skillInstanceId)) {
                    throw new IllegalArgumentException("Result skillInstanceId mismatch");
                }
                resultStatus = string(r, "status");
                if (!resultStatus.equals("completed") && !resultStatus.equals("failed") && !resultStatus.equals("cancelled")) {
                    throw new IllegalArgumentException("Unknown result status");
                }
                resultReason = string(r, "reason");
                Progress terminal = parseProgress(r.getAsJsonObject("progress"), type);
                if (!outer.sameAs(terminal)) { throw new IllegalArgumentException("Result progress mismatch"); }
                if (resultStatus.equals("completed")) {
                    if (!complete) { throw new IllegalArgumentException("Completed without complete=true"); }
                    int success = type.equals("mine") ? mined : acquired;
                    // collect_block completes on acquired only; mined is an independent side-effect count.
                    if (success != requested) { throw new IllegalArgumentException("Completed before requested"); }
                }
            }
        }
    }

    public static Skill skill(JsonObject view) {
        JsonElement value = view.get("skill");
        if (value == null || value.isJsonNull()) { return null; }
        return new Skill(value.getAsJsonObject());
    }

    /**
     * Cross-check that a view's Skill and Action describe the same operation. Individual schema
     * parsing is not enough: a mismatched pair would otherwise mutate the world before the Daemon
     * rejects the receipt. Pure so it is unit-testable.
     */
    public static void validateBinding(Skill skill, Action action) {
        if (skill == null || action == null) { throw new IllegalArgumentException("Binding needs skill and action"); }
        if (!action.skillInstanceId.equals(skill.skillInstanceId)) {
            throw new IllegalArgumentException("Skill/action instance mismatch");
        }
        if (skill.type.equals("collect_drop")) {
            if (!action.type.equals("pickup_target") || !skill.name.equals(action.item)) {
                throw new IllegalArgumentException("collect_drop binding mismatch");
            }
        } else if (skill.type.equals("mine")) {
            if (!action.type.equals("mine_target") || !skill.name.equals(action.block)) {
                throw new IllegalArgumentException("mine binding mismatch");
            }
        } else if (skill.type.equals("collect_block")) {
            if (action.type.equals("mine_target")) {
                if (!skill.name.equals(action.block)) { throw new IllegalArgumentException("collect_block mine binding mismatch"); }
            } else if (action.type.equals("pickup_target")) {
                String expected = collectItemFor(skill.name);
                if (expected == null || !expected.equals(action.item)) {
                    throw new IllegalArgumentException("collect_block pickup binding mismatch");
                }
            } else {
                throw new IllegalArgumentException("collect_block action mismatch");
            }
        } else {
            throw new IllegalArgumentException("Unknown skill type");
        }
    }

    /** Cross-check the parsed view against the goal this Forge actually started. */
    public static void validateGoalBinding(Skill skill, String type, String target, int requested) {
        if (skill == null) { throw new IllegalArgumentException("Missing skill"); }
        if (!skill.type.equals(type) || !skill.name.equals(target) || skill.requested != requested) {
            throw new IllegalArgumentException("Goal binding mismatch");
        }
    }

    /** Parses the open response capability list. Missing/unexpected entries are ignored defensively. */
    public static Set<String> capabilities(JsonObject open) {
        Set<String> result = new HashSet<String>();
        JsonElement value = open.get("capabilities");
        if (value != null && value.isJsonArray()) {
            for (JsonElement entry : value.getAsJsonArray()) {
                if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) { result.add(entry.getAsString()); }
            }
        }
        return result;
    }
}

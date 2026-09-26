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
    public static final List<String> STATUS = Collections.unmodifiableList(Arrays.asList(
            "idle", "thinking", "running", "completed", "failed", "cancelled"));

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

    public static final class Skill {
        public final String skillInstanceId, type, name, field, phase;
        public final int requested, achieved;
        public final boolean complete;
        public final String resultStatus, resultReason;
        Skill(JsonObject o) {
            keys(o, "skillInstanceId", "type", "target", "phase", "progress", "result");
            skillInstanceId = string(o, "skillInstanceId");
            type = string(o, "type");
            if (type.equals("collect_drop")) { field = "item"; }
            else if (type.equals("mine")) { field = "block"; }
            else { throw new IllegalArgumentException("Unknown skill"); }
            JsonObject target = o.getAsJsonObject("target");
            keys(target, field);
            name = string(target, field);
            if (field.equals("item") ? !ITEMS.contains(name) : !BLOCKS.contains(name)) {
                throw new IllegalArgumentException("Unsupported target");
            }
            phase = string(o, "phase");
            JsonObject progress = o.getAsJsonObject("progress");
            Set<String> progressKeys = new HashSet<String>();
            for (Map.Entry<String, JsonElement> e : progress.entrySet()) { progressKeys.add(e.getKey()); }
            if (!progressKeys.equals(new HashSet<String>(Arrays.asList("requested", field.equals("item") ? "acquired" : "mined", "complete")))) {
                throw new IllegalArgumentException("Unexpected progress fields");
            }
            requested = integer(progress, "requested", 1, 64);
            achieved = integer(progress, field.equals("item") ? "acquired" : "mined", 0, 64);
            JsonElement done = progress.get("complete");
            if (done == null || !done.isJsonPrimitive() || !done.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("Expected boolean");
            }
            complete = done.getAsBoolean();
            JsonElement result = o.get("result");
            if (result == null || result.isJsonNull()) { resultStatus = null; resultReason = null; }
            else {
                JsonObject r = result.getAsJsonObject();
                keys(r, "skillInstanceId", "status", "reason", "progress");
                resultStatus = string(r, "status");
                resultReason = string(r, "reason");
            }
        }
    }

    public static Skill skill(JsonObject view) {
        JsonElement value = view.get("skill");
        if (value == null || value.isJsonNull()) { return null; }
        return new Skill(value.getAsJsonObject());
    }
}

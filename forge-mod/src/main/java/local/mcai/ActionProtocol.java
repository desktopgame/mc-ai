package local.mcai;

import com.google.gson.*;
import java.util.*;

/** Strict action schema and current-state validation, with no game dependencies. */
public final class ActionProtocol {
    public final String id, companionId, type;
    public final int dimension;
    private static void keys(JsonObject o, String... names) {
        Set<String> actual = new HashSet<String>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) { actual.add(e.getKey()); }
        if (!actual.equals(new HashSet<String>(Arrays.asList(names)))) { throw new IllegalArgumentException("Unexpected fields"); }
    }
    public static String string(JsonObject o, String key) {
        JsonElement value = o.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) { throw new IllegalArgumentException("Expected string"); }
        return value.getAsString();
    }
    public static boolean integer(JsonObject o, String key, int value) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber()
                && o.get(key).toString().equals(Integer.toString(value));
    }
    public static void validateEnvelope(JsonObject o, String session, int revision) {
        keys(o, "version", "session", "goalRevision", "status", "error", "action");
        if (!integer(o, "version", 1) || !integer(o, "goalRevision", revision) || !string(o, "session").equals(session)
                || !Arrays.asList("idle", "thinking", "ready", "running", "succeeded", "failed", "cancelled").contains(string(o, "status"))) {
            throw new IllegalArgumentException("Stale or malformed envelope");
        }
    }
    public ActionProtocol(JsonObject o) {
        keys(o, "actionId", "companionId", "dimension", "decision", "reasonCode");
        id = string(o, "actionId"); companionId = string(o, "companionId");
        if (!id.matches("[A-Za-z0-9_-]{1,80}") || !companionId.matches("[A-Za-z0-9_-]{1,80}")) { throw new IllegalArgumentException("Invalid identity"); }
        dimension = o.get("dimension").getAsInt();
        if (!integer(o, "dimension", dimension)) { throw new IllegalArgumentException("Invalid dimension"); }
        if (!Arrays.asList("goal_follow", "goal_stop", "goal_look", "owner_near", "low_health", "owner_out_of_range", "unavailable_action").contains(string(o, "reasonCode"))) {
            throw new IllegalArgumentException("Invalid reason");
        }
        JsonObject d = o.getAsJsonObject("decision"); type = string(d, "action");
        if (type.equals("stop")) { keys(d, "action"); }
        else if (type.equals("follow") || type.equals("look")) {
            keys(d, "action", "target");
            if (!string(d, "target").equals("owner")) { throw new IllegalArgumentException("Invalid target"); }
        } else { throw new IllegalArgumentException("Unknown action"); }
    }
    public boolean safe(String goal, String companion, int dim, double health, double distanceSquared) {
        if (!companionId.equals(companion) || dimension != dim || !Double.isFinite(health) || !Double.isFinite(distanceSquared)) { return false; }
        if (type.equals("stop")) { return true; }
        if (health <= 6) { return false; }
        if (type.equals("look")) { return "look_at_owner".equals(goal); }
        return "follow_owner".equals(goal) && distanceSquared > 4 && distanceSquared <= 1024;
    }
}

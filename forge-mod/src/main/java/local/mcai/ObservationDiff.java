package local.mcai;

import com.google.gson.*;
import java.util.Map;

/** Compares against the acknowledged baseline, including accumulated sub-threshold movement. */
public final class ObservationDiff {
    public final JsonObject state;
    public final JsonArray events = new JsonArray();
    public final boolean snapshot;

    public ObservationDiff(JsonObject previous, JsonObject current) {
        state = new JsonParser().parse(current.toString()).getAsJsonObject();
        snapshot = previous == null || !previous.get("dimension").equals(state.get("dimension"))
                || !id(previous.get("companion")).equals(id(state.get("companion")));
        if (snapshot) { return; }
        for (String entity : new String[] {"owner", "companion"}) {
            if (state.get(entity).isJsonNull()) { continue; }
            JsonObject old = previous.getAsJsonObject(entity), now = state.getAsJsonObject(entity);
            double distance = 0;
            for (int i = 0; i < 3; i++) {
                double d = old.getAsJsonArray("position").get(i).getAsDouble() - now.getAsJsonArray("position").get(i).getAsDouble();
                distance += d * d;
            }
            if (distance >= 4.0D) { field("position_changed_significantly", entity, "position", now.get("position")); }
            else { now.add("position", old.get("position")); }
            if (!old.get("health").equals(now.get("health"))) { field("health_changed", entity, "health", now.get("health")); }
        }
        JsonObject before = previous.getAsJsonObject("owner").getAsJsonObject("inventory");
        JsonObject after = state.getAsJsonObject("owner").getAsJsonObject("inventory");
        if (!before.equals(after)) {
            JsonObject added = new JsonObject(), removed = new JsonObject();
            for (Map.Entry<String, JsonElement> item : after.entrySet()) {
                int delta = item.getValue().getAsInt() - (before.has(item.getKey()) ? before.get(item.getKey()).getAsInt() : 0);
                if (delta > 0) { added.addProperty(item.getKey(), delta); }
            }
            for (Map.Entry<String, JsonElement> item : before.entrySet()) {
                int delta = item.getValue().getAsInt() - (after.has(item.getKey()) ? after.get(item.getKey()).getAsInt() : 0);
                if (delta > 0) { removed.addProperty(item.getKey(), delta); }
            }
            JsonObject event = event("inventory_changed"); event.add("added", added); event.add("removed", removed);
        }
        if (!state.get("companion").isJsonNull()) {
            JsonObject old = previous.getAsJsonObject("companion"), now = state.getAsJsonObject("companion");
            if (!old.get("task").equals(now.get("task")) || !old.get("result").equals(now.get("result"))) {
                String result = now.get("result").getAsString();
                String type = result.equals("look_completed") ? "task_completed"
                        : (result.equals("path_not_found") || result.equals("owner_unavailable") || result.equals("owner_out_of_range")) ? "task_failed" : "task_changed";
                JsonObject event = event(type); event.add("task", now.get("task")); event.add("result", now.get("result"));
            }
        }
        before = previous.getAsJsonObject("hostiles"); after = state.getAsJsonObject("hostiles");
        // Remove first so a capped observation set never grows beyond its limit while applying a batch.
        for (Map.Entry<String, JsonElement> mob : before.entrySet()) {
            if (!after.has(mob.getKey())) { event("hostile_left_range").addProperty("id", mob.getKey()); }
        }
        for (Map.Entry<String, JsonElement> mob : after.entrySet()) {
            if (!before.has(mob.getKey()) || !before.get(mob.getKey()).equals(mob.getValue())) {
                JsonObject event = event(before.has(mob.getKey()) ? "hostile_updated" : "hostile_entered_range");
                event.addProperty("id", mob.getKey()); event.add("observation", mob.getValue());
            }
        }
    }
    private String id(JsonElement entity) { return entity.isJsonNull() ? "" : entity.getAsJsonObject().get("id").getAsString(); }
    private JsonObject event(String type) {
        JsonObject event = new JsonObject(); event.addProperty("type", type); events.add(event); return event;
    }
    private void field(String type, String entity, String field, JsonElement value) {
        JsonObject event = event(type); event.addProperty("entity", entity); event.add(field, value);
    }
}

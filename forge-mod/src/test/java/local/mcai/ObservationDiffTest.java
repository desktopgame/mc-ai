package local.mcai;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ObservationDiffTest {
    private JsonObject state() throws Exception {
        return new JsonParser().parse(new String(Files.readAllBytes(Paths.get("../protocol/examples/snapshot-request.json")), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("state");
    }
    @Test public void initialAndIdentityChangesRequireSnapshot() throws Exception {
        JsonObject old = state(), now = state();
        assertTrue(new ObservationDiff(null, now).snapshot);
        now.addProperty("dimension", -1); assertTrue(new ObservationDiff(old, now).snapshot);
        now = state(); now.add("companion", JsonNull.INSTANCE); assertTrue(new ObservationDiff(old, now).snapshot);
        now = state(); now.getAsJsonObject("companion").addProperty("id", "new-entity"); assertTrue(new ObservationDiff(old, now).snapshot);
    }
    @Test public void smallMovementAccumulatesAgainstAcknowledgedPosition() throws Exception {
        JsonObject old = state(), now = state();
        now.getAsJsonObject("owner").add("position", new JsonParser().parse("[9,64,0]"));
        ObservationDiff first = new ObservationDiff(old, now);
        assertEquals(0, first.events.size());
        assertEquals(8, first.state.getAsJsonObject("owner").getAsJsonArray("position").get(0).getAsInt());
        now.getAsJsonObject("owner").add("position", new JsonParser().parse("[10,64,0]"));
        ObservationDiff second = new ObservationDiff(first.state, now);
        assertEquals(1, second.events.size());
        assertEquals("position_changed_significantly", second.events.get(0).getAsJsonObject().get("type").getAsString());
    }
    @Test public void inventoryHealthTasksAndHostilesAreDeltas() throws Exception {
        JsonObject old = state(), now = state();
        now.getAsJsonObject("owner").getAsJsonObject("inventory").addProperty("minecraft:stone", 2);
        now.getAsJsonObject("companion").addProperty("health", 18);
        now.getAsJsonObject("companion").addProperty("task", "follow");
        now.getAsJsonObject("companion").addProperty("result", "path_not_found");
        now.getAsJsonObject("hostiles").add("mob-1", new JsonParser().parse("{\"type\":\"Skeleton\",\"distance\":8}"));
        ObservationDiff diff = new ObservationDiff(old, now);
        assertFalse(diff.snapshot); assertEquals(4, diff.events.size());
        String events = diff.events.toString();
        assertTrue(events.contains("inventory_changed")); assertTrue(events.contains("task_failed"));
        assertTrue(events.contains("hostile_entered_range")); assertFalse(events.contains("position"));
        assertEquals(0, new ObservationDiff(diff.state, now).events.size());
        now.getAsJsonObject("hostiles").remove("mob-1");
        assertTrue(new ObservationDiff(diff.state, now).events.toString().contains("hostile_left_range"));
    }
}

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
        assertTrue(events.contains("inventory_changed")); assertTrue(events.contains("\"entity\":\"owner\""));
        assertTrue(events.contains("task_failed"));
        assertTrue(events.contains("hostile_entered_range")); assertFalse(events.contains("position"));
        assertEquals(0, new ObservationDiff(diff.state, now).events.size());
        now.getAsJsonObject("hostiles").remove("mob-1");
        assertTrue(new ObservationDiff(diff.state, now).events.toString().contains("hostile_left_range"));
    }
    @Test public void droppedItemsEnterUpdateAndLeaveRangeLikeHostiles() throws Exception {
        JsonObject old = state(), now = state();
        now.getAsJsonObject("items").add("item-7", new JsonParser().parse("{\"type\":\"minecraft:diamond\",\"distance\":6}"));
        ObservationDiff entered = new ObservationDiff(old, now);
        assertEquals(1, entered.events.size());
        assertEquals("item_entered_range", entered.events.get(0).getAsJsonObject().get("type").getAsString());
        now.getAsJsonObject("items").add("item-7", new JsonParser().parse("{\"type\":\"minecraft:diamond\",\"distance\":2}"));
        ObservationDiff updated = new ObservationDiff(entered.state, now);
        assertEquals(1, updated.events.size());
        assertEquals("item_updated", updated.events.get(0).getAsJsonObject().get("type").getAsString());
        now.getAsJsonObject("items").remove("item-7");
        ObservationDiff left = new ObservationDiff(updated.state, now);
        assertEquals(1, left.events.size());
        assertEquals("item_left_range", left.events.get(0).getAsJsonObject().get("type").getAsString());
    }
    @Test public void mineableBlocksEnterUpdateAndLeaveRange() throws Exception {
        JsonObject old = state(), now = state();
        now.getAsJsonObject("blocks").add("block-1_64_2", new JsonParser().parse("{\"type\":\"minecraft:log\",\"distance\":6}"));
        ObservationDiff entered = new ObservationDiff(old, now);
        assertEquals(1, entered.events.size());
        assertEquals("block_entered_range", entered.events.get(0).getAsJsonObject().get("type").getAsString());
        now.getAsJsonObject("blocks").add("block-1_64_2", new JsonParser().parse("{\"type\":\"minecraft:log\",\"distance\":2}"));
        assertEquals("block_updated", new ObservationDiff(entered.state, now).events.get(0).getAsJsonObject().get("type").getAsString());
        now.getAsJsonObject("blocks").remove("block-1_64_2");
        assertEquals("block_left_range", new ObservationDiff(entered.state, now).events.get(0).getAsJsonObject().get("type").getAsString());
    }

    @Test public void mineResultsMapToTaskCompletion() throws Exception {
        JsonObject old = state(), now = state();
        now.getAsJsonObject("companion").addProperty("task", "mine");
        now.getAsJsonObject("companion").addProperty("result", "mine_completed");
        assertTrue(new ObservationDiff(old, now).events.toString().contains("task_completed"));
        for (String failure : new String[] {"no_block_in_range", "tool_unavailable", "path_not_found"}) {
            now = state(); now.getAsJsonObject("companion").addProperty("result", failure);
            assertTrue(failure, new ObservationDiff(old, now).events.toString().contains("task_failed"));
        }
    }

    @Test public void companionInventoryAndPickupResultsAreReported() throws Exception {
        JsonObject old = state(), now = state();
        now.getAsJsonObject("companion").getAsJsonObject("inventory").addProperty("minecraft:diamond", 1);
        now.getAsJsonObject("companion").addProperty("task", "idle");
        now.getAsJsonObject("companion").addProperty("result", "pickup_completed");
        String events = new ObservationDiff(old, now).events.toString();
        assertTrue(events.contains("inventory_changed")); assertTrue(events.contains("\"entity\":\"companion\""));
        assertTrue(events.contains("task_completed"));
        for (String failure : new String[] {"inventory_full", "inventory_empty", "owner_inventory_full"}) {
            now = state(); now.getAsJsonObject("companion").addProperty("result", failure);
            assertTrue(failure, new ObservationDiff(old, now).events.toString().contains("task_failed"));
        }
        now = state(); now.getAsJsonObject("companion").addProperty("result", "deposit_completed");
        assertTrue(new ObservationDiff(old, now).events.toString().contains("task_completed"));
    }
}

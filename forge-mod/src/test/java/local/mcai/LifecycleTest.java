package local.mcai;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class LifecycleTest {
    private JsonObject action() {
        return new JsonParser().parse("{\"actionId\":\"a\",\"companionId\":\"c\",\"dimension\":0,\"decision\":{\"action\":\"follow\",\"target\":\"owner\"},\"reasonCode\":\"goal_follow\"}").getAsJsonObject();
    }
    @Test public void stopRejectsLateInferenceAndManualReplacement() {
        GoalState state = new GoalState(); state.reset("world"); state.replace("follow_owner");
        int old = state.revision;
        state.replace(null); // manual stop without waiting for any worker
        assertFalse(state.claim("world", old, "late"));
        state.replace("look_at_owner");
        assertTrue(state.claim("world", state.revision, "new"));
        assertFalse(state.claim("world", old, "late"));
    }
    @Test public void reversedAndDuplicateDeliveryNeverReexecutesOrFinishesNewAction() {
        GoalState state = new GoalState(); state.reset("world"); state.replace("follow_owner");
        int first = state.revision;
        assertTrue(state.claim("world", first, "A"));
        state.replace("look_at_owner"); int second = state.revision;
        assertTrue(state.claim("world", second, "B"));
        assertFalse(state.claim("world", first, "A"));
        assertFalse(state.finish("world", first, "A", "succeeded"));
        assertEquals("running", state.status);
        assertTrue(state.finish("world", second, "B", "succeeded"));
        assertFalse(state.claim("world", second, "B"));
        assertFalse(state.claim("world", second, "malicious-new-id"));
    }
    @Test public void reconnectionRejectsOldSessionEvenWithSameRevision() {
        GoalState state = new GoalState(); state.reset("old"); state.replace("follow_owner");
        state.reset("new"); state.replace("follow_owner");
        assertFalse(state.claim("old", 1, "old-action"));
        assertTrue(state.claim("new", 1, "new-action"));
    }
    @Test public void conversationQueueIsBoundedOrderedAndDoesNotCancelAction() {
        GoalState state = new GoalState(); state.reset("world"); state.replace("follow_owner");
        ConversationQueue queue = new ConversationQueue();
        assertTrue(queue.offer("first")); ConversationQueue.Turn inFlight = queue.poll();
        for (int i = 0; i < 4; i++) { assertTrue(queue.offer("queued-" + i)); }
        assertFalse(queue.offer("overflow"));
        assertEquals("queued-0", queue.poll().text);
        assertTrue(queue.current(inFlight));
        assertTrue(state.claim("world", 1, "action"));
        queue.reset(); // forget / world disconnect
        assertFalse(queue.current(inFlight)); assertNull(queue.poll());
        assertEquals("running", state.status);
        state.replace(null); // stop remains possible regardless of conversation work
        assertEquals("idle", state.status);
    }
    @Test public void conversationCharacterLimitAndTurnIdentity() {
        ConversationQueue queue = new ConversationQueue();
        String text = new String(new char[520]).replace('\0', 'x');
        assertTrue(queue.offer(text)); assertTrue(queue.offer(text)); assertTrue(queue.offer(text));
        assertFalse(queue.offer(text));
        ConversationQueue.Turn first = queue.poll(), second = queue.poll();
        assertTrue(second.id > first.id);
    }
    @Test public void actionValidatesLiveStateAndGoal() {
        ActionProtocol a = new ActionProtocol(action());
        assertTrue(a.safe("follow_owner", "c", 0, 20, 64));
        assertFalse(a.safe("look_at_owner", "c", 0, 20, 64));
        assertFalse(a.safe("follow_owner", "changed", 0, 20, 64));
        assertFalse(a.safe("follow_owner", "c", 1, 20, 64));
        assertFalse(a.safe("follow_owner", "c", 0, 6, 64));
        assertFalse(a.safe("follow_owner", "c", 0, 20, 1025));
        assertTrue(a.safe("follow_owner", "c", 0, 20, 4));
        assertTrue(a.safe("follow_owner", "c", 0, 20, 0));
        assertFalse(a.safe("follow_owner", "c", 0, 20, -1));
        assertFalse(a.safe("follow_owner", "c", 0, Double.NaN, 64));
    }
    private void reject(JsonObject value) {
        try { new ActionProtocol(value); fail("Accepted invalid action"); }
        catch (IllegalArgumentException | IllegalStateException | NullPointerException expected) { }
    }
    @Test public void pickupMatchesOnlyItsGoalAndKeepsTheOwnerLeash() {
        JsonObject o = action();
        o.getAsJsonObject("decision").remove("target");
        o.getAsJsonObject("decision").addProperty("action", "pickup");
        o.addProperty("reasonCode", "goal_pickup");
        ActionProtocol a = new ActionProtocol(o);
        assertTrue(a.safe("pickup_item", "c", 0, 20, 64));
        assertFalse(a.safe("follow_owner", "c", 0, 20, 64));
        assertFalse(a.safe("pickup_item", "c", 0, 6, 64));
        assertFalse(a.safe("pickup_item", "c", 0, 20, 1025));
        assertFalse(a.safe("pickup_item", "changed", 0, 20, 64));
    }
    @Test public void depositMatchesOnlyItsGoalAndKeepsTheOwnerLeash() {
        JsonObject o = action();
        o.getAsJsonObject("decision").remove("target");
        o.getAsJsonObject("decision").addProperty("action", "deposit");
        o.addProperty("reasonCode", "goal_deposit");
        ActionProtocol a = new ActionProtocol(o);
        assertTrue(a.safe("deposit_items", "c", 0, 20, 64));
        assertFalse(a.safe("pickup_item", "c", 0, 20, 64));
        assertFalse(a.safe("follow_owner", "c", 0, 20, 64));
        assertFalse(a.safe("deposit_items", "c", 0, 6, 64));
        assertFalse(a.safe("deposit_items", "c", 0, 20, 1025));
        JsonObject withTarget = action();
        withTarget.getAsJsonObject("decision").addProperty("action", "deposit");
        withTarget.addProperty("reasonCode", "goal_deposit");
        reject(withTarget);
    }
    @Test public void pickupRejectsTargetsAndUnknownReasons() {
        JsonObject o = action();
        o.getAsJsonObject("decision").addProperty("action", "pickup"); // target still present
        o.addProperty("reasonCode", "goal_pickup");
        reject(o);
        o = action();
        o.getAsJsonObject("decision").remove("target");
        o.getAsJsonObject("decision").addProperty("action", "pickup");
        o.addProperty("reasonCode", "mine_everything");
        reject(o);
    }
    @Test public void unknownMissingAndInjectedActionsAreRejected() {
        JsonObject o = action(); o.getAsJsonObject("decision").addProperty("action", "shell"); reject(o);
        o = action(); o.getAsJsonObject("decision").remove("target"); reject(o);
        o = action(); o.getAsJsonObject("decision").addProperty("target", "another-player"); reject(o);
        o = action(); o.addProperty("command", "say injected"); reject(o);
        o = action(); o.addProperty("dimension", "0"); reject(o);
        o = action(); o.addProperty("dimension", 0.5); reject(o);
    }
    @Test public void replyMustMatchSessionRevisionAndStrictVersion() {
        JsonObject reply = new JsonParser().parse("{\"version\":1,\"session\":\"w\",\"goalRevision\":2,\"status\":\"thinking\",\"error\":null,\"action\":null}").getAsJsonObject();
        ActionProtocol.validateEnvelope(reply, "w", 2);
        for (String s : new String[] {"old", "w"}) {
            try { ActionProtocol.validateEnvelope(reply, s, 1); fail("stale"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}

package local.mcai;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;

public class IntentTest {
    @Test public void transientPathFailureRecoversButPermanentFailureIsBounded() {
        FollowRetry retry = new FollowRetry();
        assertFalse(retry.exhausted(false)); assertFalse(retry.exhausted(false));
        assertFalse(retry.exhausted(true)); // successful retry clears earlier failures
        assertFalse(retry.exhausted(false)); assertFalse(retry.exhausted(false));
        assertTrue(retry.exhausted(false));
        retry.reset(); assertFalse(retry.exhausted(false)); // near owner / new task
    }
    @Test public void manualStopInvalidatesInFlightAndQueuedInstructions() {
        IntentOrder order = new IntentOrder();
        IntentOrder.Ticket inFlight = order.capture(), queued = order.capture();
        order.manual();
        assertFalse(order.current(inFlight)); assertFalse(order.current(queued));
        assertTrue(order.current(order.capture()));
    }
    @Test public void newerAcceptedIntentWinsAndOrdinaryConversationDoesNotInvalidate() {
        IntentOrder order = new IntentOrder();
        IntentOrder.Ticket first = order.capture(), second = order.capture();
        order.capture(); // ordinary conversation may arrive without advancing the action barrier
        assertTrue(order.current(first)); order.accept(first);
        assertTrue(order.current(second)); order.accept(second);
        assertFalse(order.current(first)); assertFalse(order.current(second));
    }
    @Test public void outOfOrderAndReconnectRejectOldIntents() {
        IntentOrder order = new IntentOrder();
        IntentOrder.Ticket first = order.capture(), second = order.capture();
        order.accept(second); assertFalse(order.current(first));
        IntentOrder.Ticket next = order.capture(); order.reset(); assertFalse(order.current(next));
    }
    @Test public void immediateStopIsWholeMessageOnly() {
        for (String text : new String[] {"止まって", " 止まってください！ ", "キャンセル", "やっぱやめて。", "ちょっと待って"}) {
            assertTrue(text, ImmediateStop.matches(text));
        }
        for (String text : new String[] {"止まらないで", "『止まって』", "止まってって言ったらどうする？", "敵が来たら止まって", "止まってからついてきて", "止まって？", "危ない", "戻って"}) {
            assertFalse(text, ImmediateStop.matches(text));
        }
    }
    /** Every intent the daemon may emit must round-trip, or the reply fails as a transport error. */
    @Test public void everySupportedIntentIsAccepted() throws Exception {
        String template = "{\"version\":1,\"say\":\"OK\",\"actions\":[],\"intent\":\"%s\"}";
        for (String intent : new String[] {"none", "follow_owner", "stop", "look_at_owner", "pickup_item"}) {
            assertEquals(intent, PingClient.parseSocialReply(String.format(template, intent)).intent);
        }
    }
    @Test public void unknownOrMalformedIntentNeverBecomesAnAction() throws Exception {
        String valid = "{\"version\":1,\"say\":\"OK\",\"actions\":[],\"intent\":\"follow_owner\"}";
        assertEquals("follow_owner", PingClient.parseSocialReply(valid).intent);
        for (String text : new String[] {valid.replace("follow_owner", "mine"), valid.replace("[]", "[{\"type\":\"follow\"}]"),
                "{\"version\":1,\"say\":\"OK\",\"actions\":[]}", valid.replace("\"follow_owner\"", "null"),
                valid.replace("\"follow_owner\"", "{\"type\":\"follow_owner\"}")}) {
            try { PingClient.parseSocialReply(text); fail("Invalid intent accepted"); }
            catch (IOException expected) { }
        }
    }
    @Test public void forgettingConversationCancelsIntentEvenIfActionOrderIsStillCurrent() {
        IntentOrder order = new IntentOrder(); ConversationQueue queue = new ConversationQueue();
        queue.offer("!agent chat ついてきて", order.capture()); ConversationQueue.Turn turn = queue.poll();
        assertTrue(order.current(turn.intentTicket)); queue.reset(); assertFalse(queue.current(turn));
    }
}

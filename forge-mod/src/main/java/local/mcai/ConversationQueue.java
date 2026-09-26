package local.mcai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded FIFO shared by user chat and Skill-terminal notifications. Both kinds are processed in one
 * enqueue order, but they have independent capacities: chat keeps its 4-turn / 2048-char limit,
 * terminal notifications get a separate 8-entry wait slot. Only the game thread uses this class.
 */
public final class ConversationQueue {
    public enum Kind { USER_CHAT, SKILL_TERMINAL }
    public static final int CHAT_CAPACITY = 4;
    public static final int CHAT_CHAR_LIMIT = 2048;
    public static final int TERMINAL_CAPACITY = 8;

    public static final class Turn {
        public final Kind kind;
        public final long epoch, id, enqueuedAt;
        public final String text;
        public final IntentOrder.Ticket intentTicket;
        public final String identity;   // terminal identity; null for chat
        Turn(Kind kind, long epoch, long id, long enqueuedAt, String text, IntentOrder.Ticket ticket, String identity) {
            this.kind = kind; this.epoch = epoch; this.id = id; this.enqueuedAt = enqueuedAt;
            this.text = text; this.intentTicket = ticket; this.identity = identity;
        }
    }

    private final ArrayDeque<Turn> queue = new ArrayDeque<Turn>();
    private long epoch, nextId;
    private int chars, chatCount, terminalCount;

    public boolean offer(String text) {
        return offer(text, null);
    }

    public boolean offer(String text, IntentOrder.Ticket ticket) {
        if (text.length() > 524 || chatCount >= CHAT_CAPACITY || chars + text.length() > CHAT_CHAR_LIMIT) { return false; }
        queue.add(new Turn(Kind.USER_CHAT, epoch, ++nextId, System.nanoTime(), text, ticket, null));
        chars += text.length(); chatCount++;
        return true;
    }

    /** Queues a terminal notification. Duplicate identities and a full terminal slot are rejected. */
    public boolean offerTerminal(String identity, String text, long now) {
        if (identity == null || text == null || terminalCount >= TERMINAL_CAPACITY || containsTerminal(identity)) { return false; }
        queue.add(new Turn(Kind.SKILL_TERMINAL, epoch, ++nextId, now, text, null, identity));
        terminalCount++;
        return true;
    }

    public boolean containsTerminal(String identity) {
        if (identity == null) { return false; }
        for (Turn turn : queue) { if (identity.equals(turn.identity)) { return true; } }
        return false;
    }

    public Turn poll() {
        Turn turn = queue.poll();
        if (turn != null) { release(turn); }
        return turn;
    }

    public Turn peek() { return queue.peek(); }

    /** Removes a specific queued turn (used by the terminal timeout exception). */
    public void remove(Turn turn) {
        if (turn != null && queue.remove(turn)) { release(turn); }
    }

    /** A FIFO-ordered copy for timeout scanning; does not change the queue. */
    public List<Turn> snapshot() { return new ArrayList<Turn>(queue); }

    public boolean current(Turn turn) { return turn.epoch == epoch; }

    public void reset() { epoch++; queue.clear(); chars = 0; chatCount = 0; terminalCount = 0; }

    private void release(Turn turn) {
        if (turn.kind == Kind.USER_CHAT) { chars -= turn.text.length(); chatCount--; }
        else { terminalCount--; }
    }
}

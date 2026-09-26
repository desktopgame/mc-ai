package local.mcai;

import java.util.ArrayDeque;

/** Bounded FIFO and reply generation. Only the game thread uses this class. */
public final class ConversationQueue {
    public static final class Turn {
        public final long epoch, id;
        public final String text;
        Turn(long epoch, long id, String text) { this.epoch = epoch; this.id = id; this.text = text; }
    }
    private final ArrayDeque<Turn> queue = new ArrayDeque<Turn>();
    private long epoch, nextId;
    private int chars;
    public boolean offer(String text) {
        if (text.length() > 524 || queue.size() >= 4 || chars + text.length() > 2048) { return false; }
        queue.add(new Turn(epoch, ++nextId, text)); chars += text.length(); return true;
    }
    public Turn poll() {
        Turn turn = queue.poll(); if (turn != null) { chars -= turn.text.length(); } return turn;
    }
    public boolean current(Turn turn) { return turn.epoch == epoch; }
    public void reset() { epoch++; queue.clear(); chars = 0; }
}

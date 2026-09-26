package local.mcai;

/** Input ordering is separate from goal revisions: ordinary conversation never cancels a goal. */
public final class IntentOrder {
    public static final class Ticket {
        private final long epoch, order;
        private Ticket(long epoch, long order) { this.epoch = epoch; this.order = order; }
    }
    private long epoch, next, barrier;
    public Ticket capture() { return new Ticket(epoch, ++next); }
    public boolean current(Ticket ticket) { return ticket != null && ticket.epoch == epoch && ticket.order > barrier; }
    public void accept(Ticket ticket) {
        if (!current(ticket)) { throw new IllegalArgumentException("Stale intent"); }
        barrier = ticket.order;
    }
    public void manual() { barrier = ++next; }
    public void reset() { epoch++; barrier = ++next; }
}

package local.mcai;

/**
 * Decides the outcome of an in-flight v2 Skill action on the Forge tick.
 *
 * The important rule is ordering: once the companion has actually resolved the action, its recorded
 * outcome is final. A later unsafe state, deadline or path failure must never rewrite a positive
 * world mutation to {@code count = 0}. The Forge tick checks are only a safety net; the entity's
 * mutation guard is the authority on whether the mutation happened. Pure and unit-testable.
 */
public final class SkillTickPolicy {
    private SkillTickPolicy() { }

    public static final class Decision {
        public final String status;   // "succeeded" | "failed" | "wait"
        public final String reason;
        public final int count;
        Decision(String status, String reason, int count) {
            this.status = status; this.reason = reason; this.count = count;
        }
        public boolean waiting() { return "wait".equals(status); }
    }

    public static Decision decide(boolean mine, boolean resolved, int stored, String outcome,
                                  boolean companionAlive, boolean ownerAlive, boolean sameWorld,
                                  boolean unsafe, boolean expired, boolean pathNotFound) {
        if (resolved) {
            if (stored > 0) {
                return new Decision("succeeded", outcome == null || outcome.isEmpty() ? "completed" : outcome, stored);
            }
            String fallback = mine ? "tool_unavailable" : "inventory_full";
            return new Decision("failed", outcome == null || outcome.isEmpty() ? fallback : outcome, 0);
        }
        if (!companionAlive || !ownerAlive || !sameWorld) { return new Decision("failed", "companion_unavailable", 0); }
        if (unsafe) { return new Decision("failed", "unsafe_state", 0); }
        if (expired) { return new Decision("failed", "expired", 0); }
        if (pathNotFound) { return new Decision("failed", "path_not_found", 0); }
        return new Decision("wait", null, 0);
    }
}

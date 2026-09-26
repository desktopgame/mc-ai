package local.mcai;

/** Game-thread lifecycle. A revision can claim at most one action, even after completion. */
public final class GoalState {
    public String session;
    public int revision;
    public String goal, actionId, status = "idle";
    public void reset(String value) {
        session = value; revision = 0; goal = null; actionId = null; status = "idle";
    }
    public void replace(String value) {
        if (revision == Integer.MAX_VALUE) { throw new IllegalStateException("Revision exhausted"); }
        revision++; goal = value; actionId = null; status = value == null ? "idle" : "thinking";
    }
    public boolean current(String s, int r) { return session != null && session.equals(s) && revision == r; }
    public boolean claim(String s, int r, String id) {
        if (!current(s, r) || !status.equals("thinking") || actionId != null || goal == null) { return false; }
        actionId = id; status = "running"; return true;
    }
    public boolean finish(String s, int r, String id, String outcome) {
        if (!current(s, r) || actionId == null || !actionId.equals(id) || !status.equals("running")) { return false; }
        status = outcome; return true;
    }
}

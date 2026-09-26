package local.mcai;

/** Three consecutive path attempts may fail; success or reaching the owner resets the budget. */
public final class FollowRetry {
    private int failures;
    public void reset() { failures = 0; }
    public boolean exhausted(boolean pathFound) {
        if (pathFound) { reset(); return false; }
        return ++failures >= 3;
    }
}

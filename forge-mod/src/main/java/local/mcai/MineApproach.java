package local.mcai;

/**
 * Finds a nearby stand position from which a mine target is actually accessible (KI-12/KI-13), instead
 * of always closing distance to the raw target coordinate. This deliberately stays local: it enumerates
 * a small ring of candidates around the target at the companion's current height, reuses
 * {@link MineObstruction#accessible} to test each one, and hands the winner to the existing
 * PathNavigate. It never searches terrain height, builds a node graph, or reimplements pathfinding —
 * the real walk (including any 1-block step-ups the vanilla navigator already performs) is still done
 * by {@code EntityLiving.getNavigator().tryMoveToXYZ}.
 */
public final class MineApproach {
    private MineApproach() { }

    private static final int[] RADII = {1, 2, 3, 4};

    public static final class Candidate {
        public final int x, y, z;
        Candidate(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    /**
     * Returns a stand position around (tx,ty,tz) with a clear line of sight to the target, or null when
     * no ring candidate has one (the caller should keep its existing fallback behavior, e.g. approaching
     * the raw target coordinate as before). Tries the smallest radius shell first — so an ordinary,
     * unobstructed target is still approached the old way, right up close — and only backs off to a
     * larger radius when every candidate at the smaller radius is blocked. Within a shell, the candidate
     * nearest the companion's current position wins (KI-12/KI-13/FR-02's "prefer an executable candidate").
     */
    public static Candidate bestStandPosition(MineObstruction.MaterialLookup lookup, double cx, double cy, double cz,
                                              int tx, int ty, int tz, double eyeHeight) {
        int standY = (int) Math.floor(cy);
        for (int radius : RADII) {
            Candidate best = null;
            double bestDistanceSq = Double.MAX_VALUE;
            for (int[] offset : ApproachCandidates.ring(radius)) {
                int x = tx + offset[0], z = tz + offset[1];
                double ex = x + 0.5D, ey = standY + eyeHeight, ez = z + 0.5D;
                if (!MineObstruction.accessible(ex, ey, ez, tx, ty, tz, lookup)) { continue; }
                double dx = ex - cx, dy = standY - cy, dz = ez - cz;
                double distanceSq = dx * dx + dy * dy + dz * dz;
                if (distanceSq < bestDistanceSq) { bestDistanceSq = distanceSq; best = new Candidate(x, standY, z); }
            }
            if (best != null) { return best; }
        }
        return null;
    }
}

package local.mcai;

import java.util.Collections;
import java.util.Set;

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
    // A candidate must be comfortably inside MineTargetTask's own mining-reach gate (MAX_REACH_SQUARED,
    // real distance ~4.5), not just barely at the boundary: the idealized block-center this search
    // evaluates rarely matches the companion's exact post-arrival position, and a boundary-line
    // candidate that reads as "in reach" on paper can read as "out of reach" once actually stood on
    // (observed in KI-13: a diagonal radius-2 candidate scored exactly at the hard limit, 20.25, while
    // the axis-aligned radius-2 candidates for the same target scored a comfortable 16.25 — the margin
    // below rejects the former and lets the tie-break fall through to one of the latter instead).
    private static final double REACH_MARGIN_SQUARED = 18.0D;

    public static final class Candidate {
        public final int x, y, z;
        Candidate(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    /**
     * Returns a stand position around (tx,ty,tz) that is standable, within mining reach and has a clear
     * line of sight to the target, or null when no ring candidate qualifies (the caller should keep its
     * existing fallback behavior, e.g. approaching the raw target coordinate as before). A candidate
     * with a clear line of sight but nowhere to actually stand (mid-air, inside a wall, over a hole), or
     * one whose real distance to the target exceeds mining reach, is never returned: the navigator could
     * never deliver a usable mining position there, so "accessible" alone is not "reachable" (KI-13).
     * Tries the smallest radius shell first — so an ordinary, unobstructed target
     * is still approached the old way, right up close — and only backs off to a larger radius when
     * every candidate at the smaller radius fails either check. Within a shell, the candidate nearest
     * the companion's current position wins (KI-12/KI-13/FR-02's "prefer an executable candidate").
     *
     * <p>The pre-check evaluates a candidate at its idealized block center, but real collision/pathing
     * can settle the companion anywhere within that cell; near an obstruction's edge that difference can
     * flip the real, post-arrival line of sight from clear to blocked. {@code excludedCells} lets the
     * caller rule out a cell it already stood in and confirmed does not work, so a single bad prediction
     * cannot make the search return the same unusable spot forever.
     */
    public static Candidate bestStandPosition(MineObstruction.MaterialLookup lookup, double cx, double cy, double cz,
                                              int tx, int ty, int tz, double eyeHeight) {
        return bestStandPosition(lookup, cx, cy, cz, tx, ty, tz, eyeHeight, Collections.<Long>emptySet());
    }

    public static Candidate bestStandPosition(MineObstruction.MaterialLookup lookup, double cx, double cy, double cz,
                                              int tx, int ty, int tz, double eyeHeight, Set<Long> excludedCells) {
        int standY = (int) Math.floor(cy);
        for (int radius : RADII) {
            Candidate best = null;
            double bestDistanceSq = Double.MAX_VALUE;
            for (int[] offset : ApproachCandidates.ring(radius)) {
                int x = tx + offset[0], z = tz + offset[1];
                if (excludedCells.contains(ApproachCandidates.key(x, z))) { continue; }
                if (!MineObstruction.standable(lookup, x, standY, z)) { continue; }
                double ex = x + 0.5D, ez = z + 0.5D;
                double reachDx = ex - (tx + 0.5D), reachDy = standY - (ty + 0.5D), reachDz = ez - (tz + 0.5D);
                if (reachDx * reachDx + reachDy * reachDy + reachDz * reachDz > REACH_MARGIN_SQUARED) { continue; }
                double ey = standY + eyeHeight;
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

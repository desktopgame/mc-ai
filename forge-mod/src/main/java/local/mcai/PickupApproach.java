package local.mcai;

import java.util.Collections;
import java.util.Set;

/**
 * Finds a nearby standable ground position next to a dropped item (KI-11), instead of pathing directly
 * to the item entity itself. A companion that already satisfies the pickup distance (2.25 blocksq) from
 * a standable adjacent tile does not need to physically reach the item's exact (possibly elevated or
 * still-settling) position; handing PathNavigate a concrete ground coordinate is more reliable than an
 * entity target. This stays local: no terrain-height search, no custom pathfinding.
 */
public final class PickupApproach {
    private PickupApproach() { }

    private static final int[] RADII = {1, 2};

    public static final class Candidate {
        public final int x, y, z;
        Candidate(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    /**
     * Returns a standable tile around the item's horizontal position, or null when none of the ring
     * candidates is standable (the caller should keep its existing fallback, e.g. pathing to the item
     * entity directly). Tries the smallest radius first, same rationale as {@link MineApproach}: an
     * ordinary reachable item is still approached the old way, and only a wider ring is tried when the
     * immediate neighborhood offers no footing (KI-11/FR-02's "prefer an executable candidate").
     *
     * <p>{@code excludedCells} lets the caller rule out a cell it already stood in and confirmed still
     * does not get it close enough to collect, so a single bad prediction (the pre-check evaluates a
     * candidate at its idealized block center, real collision may settle the companion elsewhere in
     * that cell) cannot make the search return the same unusable spot forever (mirrors MineApproach).
     */
    public static Candidate bestStandPosition(MineObstruction.MaterialLookup lookup, double cx, double cy, double cz,
                                              double ix, double iy, double iz) {
        return bestStandPosition(lookup, cx, cy, cz, ix, iy, iz, Collections.<Long>emptySet());
    }

    public static Candidate bestStandPosition(MineObstruction.MaterialLookup lookup, double cx, double cy, double cz,
                                              double ix, double iy, double iz, Set<Long> excludedCells) {
        int tx = (int) Math.floor(ix), tz = (int) Math.floor(iz);
        int standY = (int) Math.floor(cy);
        for (int radius : RADII) {
            Candidate best = null;
            double bestDistanceSq = Double.MAX_VALUE;
            for (int[] offset : ApproachCandidates.ring(radius)) {
                int x = tx + offset[0], z = tz + offset[1];
                if (excludedCells.contains(ApproachCandidates.key(x, z))) { continue; }
                if (!MineObstruction.standable(lookup, x, standY, z)) { continue; }
                double dx = (x + 0.5D) - cx, dy = standY - cy, dz = (z + 0.5D) - cz;
                double distanceSq = dx * dx + dy * dy + dz * dz;
                if (distanceSq < bestDistanceSq) { bestDistanceSq = distanceSq; best = new Candidate(x, standY, z); }
            }
            if (best != null) { return best; }
        }
        return null;
    }
}

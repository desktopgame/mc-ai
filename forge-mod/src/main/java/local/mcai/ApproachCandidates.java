package local.mcai;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure horizontal offset generator shared by mine/pickup approach search (KI-11/12/13). Produces a
 * small, deterministic set of (dx, dz) offsets around a target: 8 compass directions at each of the
 * given radii, nearest radius first. This is not a pathfinder and knows nothing about the world; it
 * only enumerates candidate destinations for the caller to test and hand to the existing PathNavigate.
 */
public final class ApproachCandidates {
    private ApproachCandidates() { }

    private static final int[][] DIRECTIONS = {
        {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** Offsets for every radius, in radius-ascending order, without duplicates (radius 0 is never included). */
    public static List<int[]> ring(int... radii) {
        List<int[]> result = new ArrayList<int[]>();
        for (int radius : radii) {
            if (radius <= 0) { continue; }
            for (int[] direction : DIRECTIONS) {
                result.add(new int[] {direction[0] * radius, direction[1] * radius});
            }
        }
        return result;
    }

    /** Packs a horizontal cell into one key, so callers can remember/exclude specific (x,z) columns. */
    public static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}

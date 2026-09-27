package local.mcai;

import org.junit.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

/** Pure offset generator shared by MineApproach/PickupApproach; no world or navigation involved. */
public class ApproachCandidatesTest {
    @Test public void eightDistinctCompassOffsetsPerRadius() {
        List<int[]> ring = ApproachCandidates.ring(1);
        assertEquals(8, ring.size());
        Set<String> seen = new HashSet<String>();
        for (int[] offset : ring) {
            assertEquals(1, Math.max(Math.abs(offset[0]), Math.abs(offset[1])));
            assertTrue("duplicate offset", seen.add(offset[0] + "," + offset[1]));
        }
    }

    @Test public void multipleRadiiAreConcatenatedNearestFirstWithoutOverlap() {
        List<int[]> ring = ApproachCandidates.ring(1, 2);
        assertEquals(16, ring.size());
        for (int i = 0; i < 8; i++) {
            assertEquals(1, Math.max(Math.abs(ring.get(i)[0]), Math.abs(ring.get(i)[1])));
        }
        for (int i = 8; i < 16; i++) {
            assertEquals(2, Math.max(Math.abs(ring.get(i)[0]), Math.abs(ring.get(i)[1])));
        }
    }

    @Test public void nonPositiveRadiiAreIgnored() {
        assertTrue(ApproachCandidates.ring(0).isEmpty());
        assertTrue(ApproachCandidates.ring(-1).isEmpty());
        assertEquals(8, ApproachCandidates.ring(0, 1, -3).size());
    }
}

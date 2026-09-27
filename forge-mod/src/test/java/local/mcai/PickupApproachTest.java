package local.mcai;

import net.minecraft.block.material.Material;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

/**
 * Stand-position search for picking up a dropped item (KI-11): prefers a nearby standable ground tile
 * over pathing directly to the item entity. Pure MaterialLookup fixtures only; no live world or entity.
 */
public class PickupApproachTest {
    /** Flat floor at y=-1 everywhere (so every (x,0,z) column is standable), no obstructions. */
    private MineObstruction.MaterialLookup flatFloor() {
        return new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) { return y < 0 ? Material.rock : Material.air; }
        };
    }

    @Test public void standableRingTileIsFoundOnFlatGround() {
        // Item sitting at (10, 0.1, 10); companion 3 blocks north on the same flat floor.
        PickupApproach.Candidate best = PickupApproach.bestStandPosition(flatFloor(), 10.5D, 0.0D, 7.5D, 10.5D, 0.1D, 10.5D);
        assertNotNull(best);
        assertTrue(MineObstruction.standable(flatFloor(), best.x, best.y, best.z));
        // Nearest radius-1 tile to a companion standing north of the item is directly between them.
        assertEquals(10, best.x);
        assertEquals(9, best.z);
        assertEquals(0, best.y);
    }

    @Test public void aPitRightNextToTheItemIsSkippedForAStandableNeighbor() {
        // Same flat floor, except the tile immediately north of the item (where the naive nearest
        // candidate would be) is a bottomless pit: nothing to stand on there.
        final MineObstruction.MaterialLookup floor = flatFloor();
        MineObstruction.MaterialLookup withPit = new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) {
                if (x == 10 && z == 9) { return Material.air; }   // no floor under the pit tile
                return floor.get(x, y, z);
            }
        };
        assertFalse("scenario sanity check: the pit tile must not be standable",
                MineObstruction.standable(withPit, 10, 0, 9));

        PickupApproach.Candidate best = PickupApproach.bestStandPosition(withPit, 10.5D, 0.0D, 7.5D, 10.5D, 0.1D, 10.5D);
        assertNotNull(best);
        assertTrue(MineObstruction.standable(withPit, best.x, best.y, best.z));
        assertFalse("must not return the pit tile", best.x == 10 && best.z == 9);
    }

    @Test public void anExcludedCellIsNeverReturnedEvenIfItWouldOtherwiseWin() {
        // Same fixture as the first test, where the winner would normally be (10,0,9). Excluding that
        // exact cell (as CompanionEntity does after standing there and still not getting within pickup
        // range) must produce a different candidate instead of the same dead end forever (KI-11).
        PickupApproach.Candidate first = PickupApproach.bestStandPosition(flatFloor(), 10.5D, 0.0D, 7.5D, 10.5D, 0.1D, 10.5D);
        assertEquals(10, first.x); assertEquals(9, first.z);

        PickupApproach.Candidate second = PickupApproach.bestStandPosition(flatFloor(), 10.5D, 0.0D, 7.5D, 10.5D, 0.1D, 10.5D,
                Collections.singleton(ApproachCandidates.key(10, 9)));
        assertNotNull(second);
        assertFalse(second.x == 10 && second.z == 9);
        assertTrue(MineObstruction.standable(flatFloor(), second.x, second.y, second.z));
    }

    @Test public void noStandableNeighborReturnsNullRatherThanAGuess() {
        // No floor anywhere: nothing in the ring is standable at any radius.
        MineObstruction.MaterialLookup voidEverywhere = new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) { return Material.air; }
        };
        assertNull(PickupApproach.bestStandPosition(voidEverywhere, 0.5D, 0.0D, 0.5D, 0.5D, 0.1D, 0.5D));
    }
}

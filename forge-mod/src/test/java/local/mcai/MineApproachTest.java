package local.mcai;

import net.minecraft.block.material.Material;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Stand-position search for mine_target (KI-12/KI-13): prefers a nearby position with a clear line of
 * sight over blindly closing distance to the raw target coordinate. Pure MaterialLookup fixtures only;
 * no live world, no Minecraft entity.
 */
public class MineApproachTest {
    private static final double EYE = 1.62D;

    private MineObstruction.MaterialLookup blockAt(final int bx, final int by, final int bz) {
        return new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) {
                return (x == bx && y == by && z == bz) ? Material.rock : Material.air;
            }
        };
    }

    private MineObstruction.MaterialLookup solidExcept(final int ex, final int ey, final int ez) {
        return new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) {
                return (x == ex && y == ey && z == ez) ? Material.air : Material.rock;
            }
        };
    }

    @Test public void openGroundPicksTheNearestCandidateOnTheCompanionsSide() {
        // Target at (10,4,10); companion 5 blocks north at ground y=0. Nothing obstructs anything.
        MineObstruction.MaterialLookup air = new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) { return Material.air; }
        };
        MineApproach.Candidate best = MineApproach.bestStandPosition(air, 10.5D, 0.0D, 5.5D, 10, 4, 10, EYE);
        assertNotNull(best);
        // The nearest ring candidate to the companion is directly between it and the target (radius 1).
        assertEquals(10, best.x);
        assertEquals(9, best.z);
        assertEquals(0, best.y);
    }

    @Test public void elevatedTargetOnAThinColumnIsUnreachableFromDirectlyBelowButReachableFromFartherBack() {
        // A 3-tall 1x1 column at (0,0..2,0) with the target log on top at (0,3,0), KI-13's exact repro.
        MineObstruction.MaterialLookup column = new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) {
                return (x == 0 && z == 0 && y >= 0 && y <= 2) ? Material.rock : Material.air;
            }
        };
        // Sanity check on the scenario itself: standing right next to the column, the column's own
        // upper body blocks the steep line of sight up to the log (this is the bug).
        assertFalse(MineObstruction.accessible(1.5D, EYE, 0.5D, 0, 3, 0, column));

        MineApproach.Candidate best = MineApproach.bestStandPosition(column, 14.5D, 0.0D, 0.5D, 0, 3, 0, EYE);
        assertNotNull("no operable stand position found for an elevated target", best);
        assertTrue("winner must itself have a clear line of sight",
                MineObstruction.accessible(best.x + 0.5D, best.y + EYE, best.z + 0.5D, 0, 3, 0, column));
        // The fix must not just repeat the blocked adjacent tile.
        assertTrue("winner should back off rather than sit directly against the column",
                Math.abs(best.x) + Math.abs(best.z) > 1);
    }

    @Test public void obstructionCanBeWalkedAroundInsteadOfDeclaringBlocked() {
        // Same wall fixture as MineObstructionTest: blocked head-on, clear from the side (KI-12).
        MineObstruction.MaterialLookup wall = blockAt(0, 1, 1);
        assertFalse(MineObstruction.accessible(0.5D, 1.5D, 0.5D, 0, 1, 3, wall));

        MineApproach.Candidate best = MineApproach.bestStandPosition(wall, 0.5D, 0.0D, 0.5D, 0, 1, 3, 1.5D);
        assertNotNull(best);
        assertTrue(MineObstruction.accessible(best.x + 0.5D, best.y + 1.5D, best.z + 0.5D, 0, 1, 3, wall));
    }

    @Test public void noAccessibleCandidateReturnsNullRatherThanAGuess() {
        // Every position is solid except the target cell itself: even the candidates' own footing is
        // blocked, so nothing in the ring can have a clear line of sight.
        MineObstruction.MaterialLookup sealed = solidExcept(5, 5, 5);
        assertNull(MineApproach.bestStandPosition(sealed, 20.5D, 0.0D, 20.5D, 5, 5, 5, EYE));
    }
}

package local.mcai;

import net.minecraft.block.material.Material;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Stand-position search for mine_target (KI-12/KI-13): prefers a nearby position that is both standable
 * and has a clear line of sight, over blindly closing distance to the raw target coordinate. Pure
 * MaterialLookup fixtures only; no live world, no Minecraft entity.
 */
public class MineApproachTest {
    private static final double EYE = 1.62D;

    /** Solid floor at y=-1 everywhere (companions in these fixtures stand at y=0), open air above it. */
    private MineObstruction.MaterialLookup floorAt(final int floorY) {
        return new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) { return y == floorY ? Material.rock : Material.air; }
        };
    }

    private MineObstruction.MaterialLookup withExtra(final MineObstruction.MaterialLookup base,
                                                      final int bx, final int by, final int bz, final Material material) {
        return new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) {
                return (x == bx && y == by && z == bz) ? material : base.get(x, y, z);
            }
        };
    }

    @Test public void openGroundPicksTheNearestCandidateOnTheCompanionsSide() {
        // Target at (10,4,10); companion 5 blocks north at ground y=0. Nothing obstructs anything.
        MineApproach.Candidate best = MineApproach.bestStandPosition(floorAt(-1), 10.5D, 0.0D, 5.5D, 10, 4, 10, EYE);
        assertNotNull(best);
        assertTrue(MineObstruction.standable(floorAt(-1), best.x, best.y, best.z));
        // The nearest ring candidate to the companion is directly between it and the target (radius 1).
        assertEquals(10, best.x);
        assertEquals(9, best.z);
        assertEquals(0, best.y);
    }

    @Test public void elevatedTargetOnAThinColumnIsUnreachableFromDirectlyBelowButReachableFromFartherBack() {
        // A 3-tall 1x1 column at (0,0..2,0) with the target log on top at (0,3,0), KI-13's exact repro.
        // Floor at y=-1 everywhere so every ring candidate is standable; only the column obstructs sight.
        final MineObstruction.MaterialLookup floor = floorAt(-1);
        MineObstruction.MaterialLookup column = new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) {
                if (x == 0 && z == 0 && y >= 0 && y <= 2) { return Material.rock; }
                return floor.get(x, y, z);
            }
        };
        // Sanity check on the scenario itself: standing right next to the column, the column's own
        // upper body blocks the steep line of sight up to the log (this is the bug).
        assertFalse(MineObstruction.accessible(1.5D, EYE, 0.5D, 0, 3, 0, column));

        MineApproach.Candidate best = MineApproach.bestStandPosition(column, 14.5D, 0.0D, 0.5D, 0, 3, 0, EYE);
        assertNotNull("no operable stand position found for an elevated target", best);
        assertTrue("winner must be standable", MineObstruction.standable(column, best.x, best.y, best.z));
        assertTrue("winner must itself have a clear line of sight",
                MineObstruction.accessible(best.x + 0.5D, best.y + EYE, best.z + 0.5D, 0, 3, 0, column));
        // The fix must not just repeat the blocked adjacent tile.
        assertTrue("winner should back off rather than sit directly against the column",
                Math.abs(best.x) + Math.abs(best.z) > 1);
    }

    @Test public void obstructionCanBeWalkedAroundInsteadOfDeclaringBlocked() {
        // Same wall fixture as MineObstructionTest: blocked head-on, clear from the side (KI-12).
        MineObstruction.MaterialLookup wall = withExtra(floorAt(-1), 0, 1, 1, Material.rock);
        assertFalse(MineObstruction.accessible(0.5D, 1.5D, 0.5D, 0, 1, 3, wall));

        MineApproach.Candidate best = MineApproach.bestStandPosition(wall, 0.5D, 0.0D, 0.5D, 0, 1, 3, 1.5D);
        assertNotNull(best);
        assertTrue(MineObstruction.standable(wall, best.x, best.y, best.z));
        assertTrue(MineObstruction.accessible(best.x + 0.5D, best.y + 1.5D, best.z + 0.5D, 0, 1, 3, wall));
    }

    @Test public void aLineOfSightClearButUnstandableCandidateIsSkippedForAStandableOne() {
        // Open sky (no LoS obstruction anywhere), floor at y=-1, except the single nearest ring tile
        // (the one directly between companion and target) has no floor: a sheer drop, not standable.
        MineObstruction.MaterialLookup withPit = new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) {
                if (x == 10 && z == 9 && y == -1) { return Material.air; }
                return floorAt(-1).get(x, y, z);
            }
        };
        assertFalse("scenario sanity check: the pit tile must not be standable",
                MineObstruction.standable(withPit, 10, 0, 9));
        assertTrue("scenario sanity check: the pit tile still has clear line of sight",
                MineObstruction.accessible(10.5D, EYE, 9.5D, 10, 4, 10, withPit));

        MineApproach.Candidate best = MineApproach.bestStandPosition(withPit, 10.5D, 0.0D, 5.5D, 10, 4, 10, EYE);
        assertNotNull(best);
        assertTrue(MineObstruction.standable(withPit, best.x, best.y, best.z));
        assertFalse("must not return the unstandable pit tile", best.x == 10 && best.z == 9);
    }

    @Test public void noQualifyingCandidateReturnsNullRatherThanAGuess() {
        // Every position is solid except the target cell itself: even the candidates' own footing is
        // blocked, so nothing in the ring can be both standable and have a clear line of sight.
        MineObstruction.MaterialLookup sealed = new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) {
                return (x == 5 && y == 5 && z == 5) ? Material.air : Material.rock;
            }
        };
        assertNull(MineApproach.bestStandPosition(sealed, 20.5D, 0.0D, 20.5D, 5, 5, 5, EYE));
    }
}

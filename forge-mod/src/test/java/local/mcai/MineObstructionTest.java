package local.mcai;

import net.minecraft.block.material.Material;
import org.junit.Test;
import static org.junit.Assert.*;

/** The passability rule used by mine_target reachability, independent of a live world. */
public class MineObstructionTest {
    private MineObstruction.MaterialLookup single(final int bx, final int by, final int bz, final Material material) {
        return new MineObstruction.MaterialLookup() {
            @Override public Material get(int x, int y, int z) {
                return (x == bx && y == by && z == bz) ? material : Material.air;
            }
        };
    }

    @Test public void airNonSolidsAndLeavesArePassable() {
        assertFalse(MineObstruction.isBlockingMaterial(Material.air));
        assertFalse(MineObstruction.isBlockingMaterial(Material.plants));   // grass, flowers
        assertFalse(MineObstruction.isBlockingMaterial(Material.leaves));   // soft obstruction in this MVP
        assertFalse(MineObstruction.isBlockingMaterial(null));
    }

    @Test public void glassStoneWoodAndOreBlock() {
        assertTrue(MineObstruction.isBlockingMaterial(Material.glass));
        assertTrue(MineObstruction.isBlockingMaterial(Material.rock));      // stone, dirt, ore
        assertTrue(MineObstruction.isBlockingMaterial(Material.wood));      // log, planks
    }

    @Test public void solidWallBetweenEyeAndTargetBlocks() {
        // Eye (0.5,1.5,0.5) -> target block (0,1,3); a rock at (0,1,1) is directly on the line.
        assertFalse(MineObstruction.accessible(0.5D, 1.5D, 0.5D, 0, 1, 3, single(0, 1, 1, Material.rock)));
        assertFalse(MineObstruction.accessible(0.5D, 1.5D, 0.5D, 0, 1, 3, single(0, 1, 1, Material.glass)));
    }

    @Test public void leavesBetweenEyeAndTargetArePassable() {
        assertTrue(MineObstruction.accessible(0.5D, 1.5D, 0.5D, 0, 1, 3, single(0, 1, 1, Material.leaves)));
    }

    @Test public void targetBehindAWalkAroundableWallIsReachableFromTheNewPosition() {
        // Same target and wall: blocked from the original spot, clear after walking around it.
        MineObstruction.MaterialLookup wall = single(0, 1, 1, Material.rock);
        assertFalse(MineObstruction.accessible(0.5D, 1.5D, 0.5D, 0, 1, 3, wall));
        assertTrue(MineObstruction.accessible(2.5D, 1.5D, 0.5D, 0, 1, 3, wall));
    }
}

package local.mcai;

import net.minecraft.block.material.Material;
import org.junit.Test;
import static org.junit.Assert.*;

/** The passability rule used by mine_target reachability, independent of a live world. */
public class MineObstructionTest {
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
}

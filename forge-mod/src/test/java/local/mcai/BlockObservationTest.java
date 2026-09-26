package local.mcai;

import net.minecraft.block.material.Material;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** MVP exposed-surface candidate rules: the exposure helper and the bounded type-fair selection. */
public class BlockObservationTest {
    /** Candidate is at (0,0,0); every neighbour is rock except the one face this lookup overrides. */
    private BlockExposure.MaterialLookup onlyFace(final int fx, final int fy, final int fz, final Material value) {
        return new BlockExposure.MaterialLookup() {
            @Override public Material get(int x, int y, int z) {
                return (x == fx && y == fy && z == fz) ? value : Material.rock;
            }
        };
    }

    private BlockExposure.MaterialLookup allSolid() {
        return new BlockExposure.MaterialLookup() {
            @Override public Material get(int x, int y, int z) { return Material.rock; }
        };
    }

    @Test public void fullyBuriedBlockIsNotExposed() {
        assertFalse(BlockExposure.exposed(0, 0, 0, allSolid()));
    }

    @Test public void anyPassableFaceExposesTheBlock() {
        assertTrue(BlockExposure.exposed(0, 0, 0, onlyFace(0, 1, 0, Material.air)));      // top air
        assertTrue(BlockExposure.exposed(0, 0, 0, onlyFace(1, 0, 0, Material.air)));      // side air
        assertTrue(BlockExposure.exposed(0, 0, 0, onlyFace(0, 0, -1, Material.plants)));  // non-solid neighbour
        assertTrue(BlockExposure.exposed(0, 0, 0, onlyFace(0, 1, 0, Material.leaves)));   // leaves neighbour
    }

    @Test public void solidNeighbourOnlyIsNotExposure() {
        // Glass is non-air and solid, so a glass-only neighbour must not count as a surface.
        assertFalse(BlockExposure.exposed(0, 0, 0, onlyFace(0, 1, 0, Material.glass)));
    }

    @Test public void perTypeLimitKeepsOnlyTheNearestFour() {
        BlockCandidates candidates = new BlockCandidates();
        for (int i = 1; i <= 10; i++) { candidates.offer(dirt(i, i)); }
        List<BlockCandidates.Candidate> selected = candidates.select();
        assertEquals(BlockCandidates.PER_TYPE, selected.size());
        for (BlockCandidates.Candidate c : selected) { assertTrue("kept a farther candidate", c.distance <= 4.0D); }
    }

    @Test public void selectionIsTypeFairThenGloballyBounded() {
        BlockCandidates candidates = new BlockCandidates();
        for (int type = 0; type < 12; type++) {
            for (int i = 1; i <= 6; i++) { candidates.offer(new BlockCandidates.Candidate(type, i, 0, type * 10 + i, "t" + type)); }
        }
        List<BlockCandidates.Candidate> selected = candidates.select();
        assertEquals(BlockCandidates.GLOBAL_LIMIT, selected.size());
        // Each type still contributes at most PER_TYPE, so no single common block can crowd the rest out.
        int[] counts = new int[12];
        for (BlockCandidates.Candidate c : selected) { counts[Integer.parseInt(c.type.substring(1))]++; }
        for (int count : counts) { assertTrue(count <= BlockCandidates.PER_TYPE); }
    }

    private BlockCandidates.Candidate dirt(int x, double distance) {
        return new BlockCandidates.Candidate(x, 64, 0, distance, "minecraft:dirt");
    }
}

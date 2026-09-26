package local.mcai;

import net.minecraft.block.material.Material;

/**
 * MVP "surface exposure" rule for mine candidates.
 *
 * A block is exposed when at least one of its six neighbours is passable: air, a non-solid material
 * (grass/flowers), or leaves. Glass is solid, so it is NOT exposure. This only decides which blocks
 * are worth *observing*; it is deliberately separate from {@link MineObstruction}, which remains the
 * execution-side authority for whether the block can actually be reached. Pure and unit-testable.
 */
public final class BlockExposure {
    private BlockExposure() { }

    /** Supplies the material at a neighbour position, so the rule can be tested without a live world. */
    public interface MaterialLookup {
        Material get(int x, int y, int z);
    }

    public static boolean passable(Material material) {
        if (material == null || material == Material.air || material == Material.leaves) { return true; }
        return !material.isSolid();
    }

    /** True when at least one of the six face neighbours is passable. */
    public static boolean exposed(int x, int y, int z, MaterialLookup lookup) {
        return passable(lookup.get(x + 1, y, z)) || passable(lookup.get(x - 1, y, z))
                || passable(lookup.get(x, y + 1, z)) || passable(lookup.get(x, y - 1, z))
                || passable(lookup.get(x, y, z + 1)) || passable(lookup.get(x, y, z - 1));
    }
}

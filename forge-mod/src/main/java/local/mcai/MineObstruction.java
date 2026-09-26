package local.mcai;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * MVP reachability for mine_target: a direct line from the companion's eye to the target block.
 * Leaves are treated as soft obstruction (passable); other solid materials block. This is deliberately
 * separate from "is the block a visible/observed candidate".
 *
 * The Forge-side authority is {@code MineTargetTask}, which runs this check only after the companion
 * has moved within mining reach. Checking from a far starting position would wrongly reject targets
 * that can be reached by walking around a wall.
 */
public final class MineObstruction {
    private MineObstruction() { }

    /** Supplies the material at a block position, so the sampler can be tested without a live world. */
    public interface MaterialLookup {
        Material get(int x, int y, int z);
    }

    /** Air, non-solid materials (grass/flowers) and leaves are passable; other solid materials block. */
    public static boolean isBlockingMaterial(Material material) {
        if (material == null || material == Material.air || material == Material.leaves) { return false; }
        return material.isSolid();
    }

    public static boolean isBlocking(Block block) {
        if (block == null || block == Blocks.air) { return false; }
        return isBlockingMaterial(block.getMaterial());
    }

    public static boolean accessible(final World world, double ex, double ey, double ez, int tx, int ty, int tz) {
        return accessible(ex, ey, ez, tx, ty, tz, new MaterialLookup() {
            @Override public Material get(int x, int y, int z) { return world.getBlock(x, y, z).getMaterial(); }
        });
    }

    /** Samples the segment from the eye to the target center. Any other blocking block on the way fails it. */
    public static boolean accessible(double ex, double ey, double ez, int tx, int ty, int tz, MaterialLookup lookup) {
        double cx = tx + 0.5D, cy = ty + 0.5D, cz = tz + 0.5D;
        double dx = cx - ex, dy = cy - ey, dz = cz - ez;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = (int) Math.ceil(distance / 0.2D);
        if (steps < 1) { steps = 1; }
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            int bx = (int) Math.floor(ex + dx * t);
            int by = (int) Math.floor(ey + dy * t);
            int bz = (int) Math.floor(ez + dz * t);
            if (bx == tx && by == ty && bz == tz) { break; }
            if (isBlockingMaterial(lookup.get(bx, by, bz))) { return false; }
        }
        return true;
    }
}

package local.mcai;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;

/**
 * MVP reachability for mine_target: a direct line from the companion's eye to the target block.
 * Leaves are treated as soft obstruction (passable); other solid materials block. This is deliberately
 * separate from "is the block a visible/observed candidate".
 */
public final class MineObstruction {
    private MineObstruction() { }

    /** Air, non-solid materials (grass/flowers) and leaves are passable; other solid materials block. */
    public static boolean isBlockingMaterial(Material material) {
        if (material == null || material == Material.air || material == Material.leaves) { return false; }
        return material.isSolid();
    }

    public static boolean isBlocking(Block block) {
        if (block == null || block == Blocks.air) { return false; }
        return isBlockingMaterial(block.getMaterial());
    }

    /** Samples the segment from the eye to the target center. Any other blocking block on the way fails it. */
    public static boolean accessible(World world, double ex, double ey, double ez, int tx, int ty, int tz) {
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
            if (isBlocking(world.getBlock(bx, by, bz))) { return false; }
        }
        return true;
    }
}

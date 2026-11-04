package com.sumutiu.goingnuclear.power;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Block entity that overhead wires can be tied to (see {@link WireStringing}).
 */
public interface WireAnchor {
    WireSet wires();

    /**
     * Where the wire attaches, in world coordinates.
     */
    static Vec3 attachPoint(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof PowerPoleBlock) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.93, pos.getZ() + 0.5);
        }
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.03, pos.getZ() + 0.5);
    }
}

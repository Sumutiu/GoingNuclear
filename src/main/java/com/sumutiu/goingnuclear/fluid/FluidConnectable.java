package com.sumutiu.goingnuclear.fluid;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Marker for blocks that pipes visually and logically connect to.
 */
public interface FluidConnectable {
    default boolean connectsFluid(BlockState state, Direction side) {
        return true;
    }
}

package com.sumutiu.goingnuclear.power;

import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A pole only conducts; it neither produces nor consumes power.
 */
public class PowerPoleBlockEntity extends ElectricBlockEntity {
    public PowerPoleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.POWER_POLE, pos, state);
    }
}

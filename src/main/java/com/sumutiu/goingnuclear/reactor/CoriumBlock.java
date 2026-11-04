package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.machine.MachineBlock;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Molten reactor core left behind by a full meltdown. Radiates everything around it until it cools
 * into obsidian (duration and radius are configurable).
 */
public class CoriumBlock extends MachineBlock {
    public CoriumBlock(Properties properties) {
        super(properties, _ -> Visuals.model("corium"), _ -> Blocks.MAGMA_BLOCK.defaultBlockState());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CoriumBlockEntity(pos, state);
    }
}

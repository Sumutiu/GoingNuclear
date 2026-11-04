package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.power.ElectricConnectable;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jspecify.annotations.Nullable;

/**
 * Electric lamp. Lights up while the grid supplies it; a redstone signal switches it off.
 */
public class ElectricLampBlock extends MachineBlock implements ElectricConnectable {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public ElectricLampBlock(Properties properties) {
        super(properties,
                state -> Visuals.model(state.getValue(LIT) ? "electric_lamp_on" : "electric_lamp"),
                state -> Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, state.getValue(LIT)));
        this.registerDefaultState(this.stateDefinition.any().setValue(LIT, false));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ElectricLampBlockEntity(pos, state);
    }
}

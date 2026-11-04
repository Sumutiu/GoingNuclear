package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.fluid.FluidConnectable;
import com.sumutiu.goingnuclear.power.ElectricConnectable;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jspecify.annotations.Nullable;

/**
 * Electric pump that draws water from adjacent water source blocks and pushes it out of its front face.
 * Takes power only through its power-in port (see {@link ElectricConnectable#powerPort}).
 */
public class WaterPumpBlock extends MachineBlock implements ElectricConnectable, FluidConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public WaterPumpBlock(Properties properties) {
        super(properties,
                state -> Visuals.sixWay("water_pump", state.getValue(FACING), state.getValue(POWER_PORT)),
                state -> Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, state.getValue(FACING)));
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.UP).setValue(POWER_PORT, Direction.NORTH));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWER_PORT);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // An upright pump gets its power-in port on the side facing you.
        return this.defaultBlockState()
                .setValue(FACING, ctx.getNearestLookingDirection().getOpposite())
                .setValue(POWER_PORT, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public boolean connectsFluid(BlockState state, Direction side) {
        return side == state.getValue(FACING);
    }

    @Override
    public boolean connectsPower(BlockState state, Direction side) {
        return side == ElectricConnectable.powerPort(state.getValue(FACING), state.getValue(POWER_PORT));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WaterPumpBlockEntity(pos, state);
    }
}

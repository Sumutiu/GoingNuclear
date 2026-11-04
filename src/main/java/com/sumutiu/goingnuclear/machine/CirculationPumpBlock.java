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
 * In-line electric pump for closed loops. Draws any fluid from the back face (including the coolant
 * outlets of heat exchangers and the condensate outlets of condensers, even through pipes) and pushes it out of the front face. Takes power through
 * the four faces around its flow (the yellow power-in ports) and passes it on to whatever touches them, so a
 * row of pumps side by side needs only one connection.
 */
public class CirculationPumpBlock extends MachineBlock implements ElectricConnectable, FluidConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public CirculationPumpBlock(Properties properties) {
        super(properties,
                state -> Visuals.facing("circulation_pump", state.getValue(FACING)),
                state -> Blocks.DROPPER.defaultBlockState().setValue(BlockStateProperties.FACING, state.getValue(FACING)));
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection().getOpposite());
    }

    @Override
    public boolean connectsFluid(BlockState state, Direction side) {
        return side.getAxis() == state.getValue(FACING).getAxis();
    }

    /**
     * Power on every face except the two pipe faces (front and back).
     */
    @Override
    public boolean connectsPower(BlockState state, Direction side) {
        return side.getAxis() != state.getValue(FACING).getAxis();
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CirculationPumpBlockEntity(pos, state);
    }
}

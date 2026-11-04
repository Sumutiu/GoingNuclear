package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.fluid.FluidConnectable;
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
 * Turns the exhaust steam of turbines back into water, cooled by river water. Every face has one job:
 * <ul>
 *     <li>Top: exhaust steam in (a turbine sitting on it, or a pipe from the turbines' bottoms).</li>
 *     <li>Back: cold cooling water in (from a water pump).</li>
 *     <li>Front (faces you when placed): the cooling water out, a little warmer (pipe it to a Water Outlet).</li>
 *     <li>Bottom: condensate (water) out: back to the heat exchangers' feedwater, or to a Water Outlet.</li>
 *     <li>Left and right: nothing.</li>
 * </ul>
 * See {@link CondenserBlockEntity}.
 */
public class CondenserBlock extends MachineBlock implements FluidConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public CondenserBlock(Properties properties) {
        super(properties,
                state -> Visuals.facing("condenser", state.getValue(FACING)),
                state -> Blocks.SMOKER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(FACING)));
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Front (cooling water out) towards you.
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    /**
     * Pipes connect to the top, bottom, front and back.
     */
    @Override
    public boolean connectsFluid(BlockState state, Direction side) {
        return side.getAxis() == Direction.Axis.Y || side.getAxis() == state.getValue(FACING).getAxis();
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CondenserBlockEntity(pos, state);
    }
}

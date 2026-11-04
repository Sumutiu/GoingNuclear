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
 * Steam generator for pressurized (closed loop) reactors. Every face has one job:
 * <ul>
 *     <li>Top: coolant in (from the reactor's OUTPUT port).</li>
 *     <li>Bottom: feedwater in (from a water pump).</li>
 *     <li>Front: steam out (to the turbines).</li>
 *     <li>The other three sides: coolant out, but only to a Circulation Pump drawing from that side
 *     (directly or through a pipe), which sends it back to the reactor's INPUT port.</li>
 * </ul>
 * Steam is only made from coolant hotter than {@code heatExchangerMinTemp} (150 °C by default).
 * The front always faces sideways (it faces you when you place it), so top and bottom stay free.
 */
public class HeatExchangerBlock extends MachineBlock implements FluidConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public HeatExchangerBlock(Properties properties) {
        super(properties,
                state -> Visuals.facing("heat_exchanger", state.getValue(FACING)),
                _ -> Blocks.CUT_COPPER.weathering().weathered().defaultBlockState());
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HeatExchangerBlockEntity(pos, state);
    }
}

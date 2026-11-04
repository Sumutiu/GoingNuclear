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
 * Lets water out of a pipe system back into the world, like a drain pipe into a river. Water piped into any
 * face but the front leaves through the front ({@link #FACING}, the way you looked when you placed it):
 * into the water there, or, in front of open air, as a water block it pours out (and takes back once no
 * water has come out for a while). In front of anything else nothing comes out, so the pipes back up.
 * It only takes water, never coolant or steam. See {@link WaterOutletBlockEntity}.
 */
public class WaterOutletBlock extends MachineBlock implements FluidConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public WaterOutletBlock(Properties properties) {
        super(properties,
                state -> Visuals.facing("water_outlet", state.getValue(FACING)),
                state -> Blocks.DROPPER.defaultBlockState().setValue(BlockStateProperties.FACING, state.getValue(FACING)));
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // The water goes the way you are looking.
        return this.defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection());
    }

    /**
     * Pipes connect to every face but the front.
     */
    @Override
    public boolean connectsFluid(BlockState state, Direction side) {
        return side != state.getValue(FACING);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WaterOutletBlockEntity(pos, state);
    }
}

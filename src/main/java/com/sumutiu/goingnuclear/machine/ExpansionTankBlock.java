package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.fluid.FluidConnectable;
import com.sumutiu.goingnuclear.fluid.TankGroups;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import org.jspecify.annotations.Nullable;

/**
 * Expansion tank: stores water for a loop. Water in at the front (faces you when placed), water out at the back,
 * a sight glass on the sides. Tanks touching each other on any face, side by side or stacked, share one store
 * of water. With an Overflow check valve into its front and a Top up check valve out of its back, it takes the
 * water a loop has too much of and gives it back when the loop runs short. See {@link ExpansionTankBlockEntity}.
 */
public class ExpansionTankBlock extends MachineBlock implements FluidConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /**
     * What the sight glass shows: 0 = empty, 1..4 = up to a quarter, half, three quarters, full.
     */
    public static final IntegerProperty LEVEL = IntegerProperty.create("level", 0, 4);

    public ExpansionTankBlock(Properties properties) {
        super(properties,
                state -> Visuals.facing("expansion_tank_" + state.getValue(LEVEL), state.getValue(FACING)),
                _ -> Blocks.COPPER_BLOCK.weathering().unaffected().defaultBlockState());
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LEVEL, 0));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LEVEL);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Water in (the front) towards you.
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    /**
     * Pipes connect to the front (water in) and the back (water out).
     */
    @Override
    public boolean connectsFluid(BlockState state, Direction side) {
        return side.getAxis() == state.getValue(FACING).getAxis();
    }

    // Placing or breaking a tank changes which tanks are joined. Sight glass and turning are changes of the same
    // block and leave the groups alone.

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, level, pos, oldState, notify);
        if (!oldState.is(this) && level instanceof ServerLevel serverLevel) TankGroups.invalidate(serverLevel);
    }

    @Override
    public void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        if (!level.getBlockState(pos).is(this)) TankGroups.invalidate(level);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ExpansionTankBlockEntity(pos, state);
    }
}

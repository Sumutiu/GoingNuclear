package com.sumutiu.goingnuclear.power;

import com.sumutiu.goingnuclear.model.AnchorModel;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import org.jspecify.annotations.Nullable;

/**
 * Wooden power pole. Stack several to make a tall pole; the top one gets a cross-arm with insulators.
 * Overhead wires are tied to any pole block with Copper Wire, like a lead (see {@link WireStringing}).
 */
public class PowerPoleBlock extends Block implements PolymerBlock, BlockWithElementHolder, EntityBlock, ElectricConnectable {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final BooleanProperty TOP = BooleanProperty.create("top");

    public PowerPoleBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(TOP, true));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, TOP);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Direction facing = ctx.getHorizontalDirection();
        boolean top = !(ctx.getLevel().getBlockState(ctx.getClickedPos().above()).getBlock() instanceof PowerPoleBlock);
        return this.defaultBlockState().setValue(AXIS, facing.getClockWise().getAxis()).setValue(TOP, top);
    }

    @Override
    public BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess tickView, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        if (direction == Direction.UP) {
            return state.setValue(TOP, !(neighborState.getBlock() instanceof PowerPoleBlock));
        }
        return state;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, level, pos, oldState, notify);
        if (level instanceof ServerLevel serverLevel) PowerGrid.of(serverLevel).markDirty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state) {
        return true;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        return Blocks.BARRIER.defaultBlockState();
    }

    @Override
    public BlockState getPolymerBreakEventBlockState(BlockState state, PacketContext context) {
        return Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PowerPoleBlockEntity(pos, state);
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        return new PoleModel(initialBlockState);
    }

    @Override
    public boolean tickElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        return true;
    }

    public static final class PoleModel extends AnchorModel {
        PoleModel(BlockState state) {
            this.setPartViewRange(1.0f);
            this.rebuild(state);
        }

        @Override
        protected void rebuild(BlockState state) {
            this.clearParts();
            if (!(state.getBlock() instanceof PowerPoleBlock)) return;
            BlockState wood = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
            BlockState arm = Blocks.SPRUCE_PLANKS.defaultBlockState();
            BlockState insulator = Blocks.CONCRETE.white().defaultBlockState();
            boolean top = state.getValue(TOP);
            this.box(wood, 0.375, 0.0, 0.375, 0.625, top ? 0.86 : 1.0, 0.625);
            if (!top) return;
            boolean alongX = state.getValue(AXIS) == Direction.Axis.X;
            if (alongX) {
                this.box(arm, -0.25, 0.74, 0.43, 1.25, 0.84, 0.57);
                this.box(insulator, -0.17, 0.84, 0.45, -0.07, 0.96, 0.55);
                this.box(insulator, 1.07, 0.84, 0.45, 1.17, 0.96, 0.55);
            } else {
                this.box(arm, 0.43, 0.74, -0.25, 0.57, 0.84, 1.25);
                this.box(insulator, 0.45, 0.84, -0.17, 0.55, 0.96, -0.07);
                this.box(insulator, 0.45, 0.84, 1.07, 0.55, 0.96, 1.17);
            }
            this.box(insulator, 0.44, 0.86, 0.44, 0.56, 0.98, 0.56);
        }
    }
}

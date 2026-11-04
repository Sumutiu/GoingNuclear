package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.fluid.FluidConnectable;
import com.sumutiu.goingnuclear.fluid.PipeNetworks;
import com.sumutiu.goingnuclear.model.BoxModel;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jspecify.annotations.Nullable;

/**
 * Copper fluid pipe. Rendered with block displays, so it looks right with or without the resource pack.
 * Pipes form passive networks (see {@link com.sumutiu.goingnuclear.fluid.PipeNetwork}).
 */
public class FluidPipeBlock extends Block implements PolymerBlock, BlockWithElementHolder, EntityBlock, FluidConnectable {
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");

    public FluidPipeBlock(Properties properties) {
        super(properties);
        BlockState state = this.stateDefinition.any();
        for (Direction direction : Direction.values()) state = state.setValue(connection(direction), false);
        this.registerDefaultState(state);
    }

    public static BooleanProperty connection(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return computeState(ctx.getLevel(), ctx.getClickedPos(), this.defaultBlockState());
    }

    public static BlockState computeState(LevelReader level, BlockPos pos, BlockState state) {
        for (Direction dir : Direction.values()) {
            BlockState neighbor = level.getBlockState(pos.relative(dir));
            state = state.setValue(connection(dir), canConnect(level, pos, dir, neighbor));
        }
        return state;
    }

    public static boolean canConnect(LevelReader level, BlockPos pos, Direction dir, BlockState neighbor) {
        if (level.getBlockEntity(pos) instanceof FluidPipeBlockEntity self && self.isDisabled(dir)) return false;
        if (neighbor.getBlock() instanceof FluidPipeBlock) {
            BlockPos other = pos.relative(dir);
            return !(level.getBlockEntity(other) instanceof FluidPipeBlockEntity pipe && pipe.isDisabled(dir.getOpposite()));
        }
        if (neighbor.getBlock() instanceof FluidConnectable connectable) {
            return connectable.connectsFluid(neighbor, dir.getOpposite());
        }
        return false;
    }

    /**
     * Pipe wrench action: enable or disable one side of the pipe.
     */
    public static boolean toggleSide(ServerLevel level, BlockPos pos, Direction side) {
        if (!(level.getBlockEntity(pos) instanceof FluidPipeBlockEntity pipe)) return false;
        pipe.toggle(side);
        BlockState state = level.getBlockState(pos);
        level.setBlock(pos, computeState(level, pos, state), Block.UPDATE_ALL);
        BlockPos other = pos.relative(side);
        BlockState otherState = level.getBlockState(other);
        if (otherState.getBlock() instanceof FluidPipeBlock) {
            level.setBlock(other, computeState(level, other, otherState), Block.UPDATE_ALL);
        }
        PipeNetworks.invalidate(level);
        return !pipe.isDisabled(side);
    }

    @Override
    public BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess tickView, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        BlockState updated = state.setValue(connection(direction), canConnect(level, pos, direction, neighborState));
        if (updated != state && level instanceof ServerLevel serverLevel) PipeNetworks.invalidate(serverLevel);
        return updated;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, level, pos, oldState, notify);
        if (level instanceof ServerLevel serverLevel) PipeNetworks.invalidate(serverLevel);
    }

    @Override
    public void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        PipeNetworks.invalidate(level);
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
        return Blocks.COPPER_BLOCK.weathering().unaffected().defaultBlockState();
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        return new PipeModel(initialBlockState);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FluidPipeBlockEntity(pos, state);
    }

    public static final class PipeModel extends BoxModel {
        private static final double CORE_MIN = 5.0 / 16.0;
        private static final double CORE_MAX = 11.0 / 16.0;
        private static final double ARM_MIN = 5.5 / 16.0;
        private static final double ARM_MAX = 10.5 / 16.0;

        PipeModel(BlockState state) {
            this.setPartViewRange(0.5f);
            this.rebuild(state);
        }

        @Override
        protected void rebuild(BlockState state) {
            this.clearParts();
            if (!(state.getBlock() instanceof FluidPipeBlock)) return;
            BlockState look = Blocks.COPPER_BLOCK.weathering().unaffected().defaultBlockState();
            BlockState ring = Blocks.CUT_COPPER.weathering().unaffected().defaultBlockState();
            boolean ns = state.getValue(NORTH) && state.getValue(SOUTH);
            boolean ew = state.getValue(EAST) && state.getValue(WEST);
            boolean ud = state.getValue(UP) && state.getValue(DOWN);
            int count = 0;
            for (Direction dir : Direction.values()) {
                if (state.getValue(connection(dir))) count++;
            }
            if (count == 2 && (ns || ew || ud)) {
                if (ns) this.box(look, ARM_MIN, ARM_MIN, 0.0, ARM_MAX, ARM_MAX, 1.0);
                if (ew) this.box(look, 0.0, ARM_MIN, ARM_MIN, 1.0, ARM_MAX, ARM_MAX);
                if (ud) this.box(look, ARM_MIN, 0.0, ARM_MIN, ARM_MAX, 1.0, ARM_MAX);
                return;
            }
            this.box(ring, CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX);
            for (Direction dir : Direction.values()) {
                if (!state.getValue(connection(dir))) continue;
                switch (dir) {
                    case NORTH -> this.box(look, ARM_MIN, ARM_MIN, 0.0, ARM_MAX, ARM_MAX, CORE_MIN);
                    case SOUTH -> this.box(look, ARM_MIN, ARM_MIN, CORE_MAX, ARM_MAX, ARM_MAX, 1.0);
                    case WEST -> this.box(look, 0.0, ARM_MIN, ARM_MIN, CORE_MIN, ARM_MAX, ARM_MAX);
                    case EAST -> this.box(look, CORE_MAX, ARM_MIN, ARM_MIN, 1.0, ARM_MAX, ARM_MAX);
                    case DOWN -> this.box(look, ARM_MIN, 0.0, ARM_MIN, ARM_MAX, CORE_MIN, ARM_MAX);
                    case UP -> this.box(look, ARM_MIN, CORE_MAX, ARM_MIN, ARM_MAX, 1.0, ARM_MAX);
                }
            }
        }
    }
}

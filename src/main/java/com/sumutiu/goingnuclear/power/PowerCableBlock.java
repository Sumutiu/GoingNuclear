package com.sumutiu.goingnuclear.power;

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
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jspecify.annotations.Nullable;

/**
 * Insulated ground cable. Connects to other cables, poles, every electric machine and vanilla furnaces.
 */
public class PowerCableBlock extends Block implements PolymerBlock, BlockWithElementHolder {
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");

    public PowerCableBlock(Properties properties) {
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

    /**
     * Whether a cable connects to {@code neighbor}, which lies in direction {@code toward} from the cable.
     */
    private static boolean connects(BlockState neighbor, Direction toward) {
        Block block = neighbor.getBlock();
        if (block instanceof ElectricConnectable connectable) return connectable.connectsPower(neighbor, toward.getOpposite());
        return block instanceof PowerCableBlock || block instanceof AbstractFurnaceBlock;
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState state = this.defaultBlockState();
        for (Direction dir : Direction.values()) {
            state = state.setValue(connection(dir), connects(ctx.getLevel().getBlockState(ctx.getClickedPos().relative(dir)), dir));
        }
        return state;
    }

    @Override
    public BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess tickView, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        BlockState updated = state.setValue(connection(direction), connects(neighborState, direction));
        if (level instanceof ServerLevel serverLevel) PowerGrid.of(serverLevel).markDirty();
        return updated;
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean notify) {
        super.onPlace(state, level, pos, oldState, notify);
        if (level instanceof ServerLevel serverLevel) PowerGrid.of(serverLevel).markDirty();
    }

    @Override
    public void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean moved) {
        super.affectNeighborsAfterRemoval(state, level, pos, moved);
        PowerGrid.of(level).markDirty();
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
        return Blocks.WOOL.black().defaultBlockState();
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        return new CableModel(initialBlockState);
    }

    public static final class CableModel extends BoxModel {
        private static final double MIN = 6.5 / 16.0;
        private static final double MAX = 9.5 / 16.0;
        private static final double CORE_MIN = 6.0 / 16.0;
        private static final double CORE_MAX = 10.0 / 16.0;

        CableModel(BlockState state) {
            this.setPartViewRange(0.5f);
            this.rebuild(state);
        }

        @Override
        protected void rebuild(BlockState state) {
            this.clearParts();
            if (!(state.getBlock() instanceof PowerCableBlock)) return;
            BlockState look = Blocks.CONCRETE.black().defaultBlockState();
            BlockState core = Blocks.CONCRETE.gray().defaultBlockState();
            this.box(core, CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX);
            for (Direction dir : Direction.values()) {
                if (!state.getValue(connection(dir))) continue;
                switch (dir) {
                    case NORTH -> this.box(look, MIN, MIN, 0.0, MAX, MAX, CORE_MIN);
                    case SOUTH -> this.box(look, MIN, MIN, CORE_MAX, MAX, MAX, 1.0);
                    case WEST -> this.box(look, 0.0, MIN, MIN, CORE_MIN, MAX, MAX);
                    case EAST -> this.box(look, CORE_MAX, MIN, MIN, 1.0, MAX, MAX);
                    case DOWN -> this.box(look, MIN, 0.0, MIN, MAX, CORE_MIN, MAX);
                    case UP -> this.box(look, MIN, CORE_MAX, MIN, MAX, 1.0, MAX);
                }
            }
        }
    }
}

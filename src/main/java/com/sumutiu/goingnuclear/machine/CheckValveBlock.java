package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.fluid.FluidConnectable;
import com.sumutiu.goingnuclear.fluid.PipeNetworks;
import com.sumutiu.goingnuclear.gui.CheckValveGui;
import com.sumutiu.goingnuclear.model.BoxModel;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.virtualentity.api.BlockWithElementHolder;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * One-way valve that sits in a pipe line. Fluid only goes from its back to its front ({@link #FACING},
 * the direction you were looking when you placed it; the red ring marks the outlet), never back.
 * <p>
 * It is a make-up valve: it only lets fluid through while the side it feeds is less full than its top-up
 * level (80% when placed, {@code checkValveTopUp}; right-click to set it, 100% for a plain one-way valve).
 * Feeding a closed loop from a supply line through it tops the loop up when it runs low without ever pushing
 * in more than the loop has room for, and the loop's water can never flow back into the supply line. The
 * Pipe Wrench turns it around.
 */
public class CheckValveBlock extends Block implements PolymerBlock, BlockWithElementHolder, EntityBlock, FluidConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;

    public CheckValveBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Fluid flows the way you are looking.
        return this.defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection());
    }

    @Override
    public boolean connectsFluid(BlockState state, Direction side) {
        return side.getAxis() == state.getValue(FACING).getAxis();
    }

    /**
     * The screen opens with an empty hand only: with an item in hand the item does its own thing (the Pipe
     * Wrench turns the valve around, a pipe is placed against it).
     */
    @Override
    public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.isEmpty()) return InteractionResult.PASS;
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof CheckValveBlockEntity valve) {
            new CheckValveGui(serverPlayer, valve).open();
        }
        return InteractionResult.SUCCESS_SERVER;
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
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CheckValveBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide()) return null;
        return (tickLevel, pos, tickState, be) -> {
            if (be instanceof MachineBlock.TickingMachine machine && tickLevel instanceof ServerLevel serverLevel) {
                machine.serverTick(serverLevel, pos, tickState);
            }
        };
    }

    @Override
    public @Nullable ElementHolder createElementHolder(ServerLevel level, BlockPos pos, BlockState initialBlockState) {
        return new ValveModel(initialBlockState);
    }

    /**
     * A pipe with a valve body in the middle and a red ring on the outlet side. Shapes are written for a
     * valve that flows south (+Z) and turned to the actual direction.
     */
    public static final class ValveModel extends BoxModel {
        private static final double ARM_MIN = 5.5 / 16.0;
        private static final double ARM_MAX = 10.5 / 16.0;

        ValveModel(BlockState state) {
            this.setPartViewRange(0.5f);
            this.rebuild(state);
        }

        @Override
        protected void rebuild(BlockState state) {
            this.clearParts();
            if (!(state.getBlock() instanceof CheckValveBlock)) return;
            Direction flow = state.getValue(FACING);
            BlockState copper = Blocks.COPPER_BLOCK.weathering().unaffected().defaultBlockState();
            BlockState body = Blocks.IRON_BLOCK.defaultBlockState();
            BlockState outlet = Blocks.CONCRETE.red().defaultBlockState();
            this.turned(flow, copper, ARM_MIN, ARM_MIN, 0.0, ARM_MAX, ARM_MAX, 0.3);
            this.turned(flow, body, 0.28, 0.28, 0.3, 0.72, 0.72, 0.66);
            this.turned(flow, outlet, 0.31, 0.31, 0.66, 0.69, 0.69, 0.76);
            this.turned(flow, copper, ARM_MIN, ARM_MIN, 0.76, ARM_MAX, ARM_MAX, 1.0);
            // Handle on top of the body.
            this.turned(flow, outlet, 0.46, 0.72, 0.42, 0.54, 0.84, 0.54);
        }

        /**
         * Adds a box given for a valve flowing south (+Z), turned so that +Z points along {@code flow}.
         */
        private void turned(Direction flow, BlockState look, double x1, double y1, double z1, double x2, double y2, double z2) {
            double[] a = turn(flow, x1, y1, z1);
            double[] b = turn(flow, x2, y2, z2);
            this.box(look, Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                    Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
        }

        private static double[] turn(Direction flow, double x, double y, double z) {
            return switch (flow) {
                case SOUTH -> new double[]{x, y, z};
                case NORTH -> new double[]{1.0 - x, y, 1.0 - z};
                case EAST -> new double[]{z, y, 1.0 - x};
                case WEST -> new double[]{1.0 - z, y, x};
                case UP -> new double[]{x, z, 1.0 - y};
                case DOWN -> new double[]{x, 1.0 - z, y};
            };
        }
    }
}

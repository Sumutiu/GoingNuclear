package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.fluid.FluidConnectable;
import com.sumutiu.goingnuclear.gui.SteamDumpValveGui;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * Steam dump valve (turbine bypass): live steam in at the top, dumped steam out at the back (into a pipe to the
 * turbines' exhaust line or a Condenser's top), a handwheel and gauge on the front, nothing on the other sides.
 * Right-click to set it. See {@link SteamDumpValveBlockEntity}.
 */
public class SteamDumpValveBlock extends MachineBlock implements FluidConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    /**
     * What the gauge on the front shows: 0 = shut, 1 = partly open, 2 = fully open.
     */
    public static final IntegerProperty OPENING = IntegerProperty.create("opening", 0, 2);

    public SteamDumpValveBlock(Properties properties) {
        super(properties,
                state -> Visuals.facing("steam_dump_valve_" + state.getValue(OPENING), state.getValue(FACING)),
                state -> Blocks.SMOKER.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(FACING))
                        .setValue(BlockStateProperties.LIT, state.getValue(OPENING) > 0));
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(OPENING, 0));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, OPENING);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        // Handwheel towards you.
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    /**
     * Pipes connect to the top (live steam in) and the back (dumped steam out).
     */
    @Override
    public boolean connectsFluid(BlockState state, Direction side) {
        return side == Direction.UP || side == state.getValue(FACING).getOpposite();
    }

    /**
     * The screen opens with an empty hand only, so pipes can be placed against the valve's top and back.
     */
    @Override
    public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.isEmpty()) return InteractionResult.PASS;
        return super.useItemOn(stack, state, level, pos, player, hand, hit);
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof SteamDumpValveBlockEntity valve) {
            new SteamDumpValveGui(serverPlayer, valve).open();
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SteamDumpValveBlockEntity(pos, state);
    }
}

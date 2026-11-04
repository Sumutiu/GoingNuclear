package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.power.ElectricConnectable;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.PlayerItems;
import com.sumutiu.goingnuclear.util.Visuals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Small fuel-burning generator used to bootstrap a power plant (pumps need power before the reactor runs).
 * Right-click with fuel to load it; sneak-right-click with an empty hand to take the fuel back.
 * A redstone signal switches it off.
 */
public class BackupGeneratorBlock extends MachineBlock implements ElectricConnectable {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    public BackupGeneratorBlock(Properties properties) {
        super(properties,
                state -> Visuals.facing(state.getValue(LIT) ? "backup_generator_on" : "backup_generator", state.getValue(FACING)),
                state -> Blocks.FURNACE.defaultBlockState()
                        .setValue(BlockStateProperties.HORIZONTAL_FACING, state.getValue(FACING))
                        .setValue(BlockStateProperties.LIT, state.getValue(LIT)));
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LIT, false));
    }

    @Override
    public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LIT);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (ModItems.isTool(stack)) return InteractionResult.PASS;
        if (BackupGeneratorBlockEntity.fuelTicks(stack) <= 0) return InteractionResult.TRY_WITH_EMPTY_HAND;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof BackupGeneratorBlockEntity generator) {
            int moved = generator.insertFuel(stack, !player.hasInfiniteMaterials());
            if (moved > 0) return InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.FAIL;
    }

    @Override
    public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof BackupGeneratorBlockEntity generator)) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            ItemStack fuel = generator.takeFuel();
            if (!fuel.isEmpty()) PlayerItems.give(player, fuel);
            return InteractionResult.SUCCESS_SERVER;
        }
        List<Component> lines = new ArrayList<>();
        generator.appendInfo(lines);
        for (Component line : lines) serverPlayer.sendSystemMessage(line);
        return InteractionResult.SUCCESS_SERVER;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BackupGeneratorBlockEntity(pos, state);
    }
}

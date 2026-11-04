package com.sumutiu.goingnuclear.item;

import com.sumutiu.goingnuclear.GoingNuclear;
import com.sumutiu.goingnuclear.fluid.PipeNetworks;
import com.sumutiu.goingnuclear.machine.CheckValveBlock;
import com.sumutiu.goingnuclear.machine.FluidPipeBlock;
import com.sumutiu.goingnuclear.power.ElectricConnectable;
import com.sumutiu.goingnuclear.power.PowerGrid;
import com.sumutiu.goingnuclear.reactor.CoriumBlock;
import com.sumutiu.goingnuclear.reactor.ReactorPortBlock;
import com.sumutiu.goingnuclear.reactor.ReactorRegistry;
import com.sumutiu.goingnuclear.util.Sounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Pipe wrench:
 * <ul>
 *     <li>Right-click a pipe face: connect / disconnect that side.</li>
 *     <li>Right-click a reactor port: switch between INPUT and OUTPUT.</li>
 *     <li>Right-click a machine: rotate it (an upright water pump clicked on a side: move its power-in port there).</li>
 *     <li>Sneak-right-click any Going Nuclear block: dismantle it.</li>
 * </ul>
 */
public class PipeWrenchItem extends NuclearItem {
    public PipeWrenchItem(Properties properties, Item fallback) {
        super(properties, fallback,
                "Right-click pipe side: connect / disconnect",
                "Right-click reactor port: input / output",
                "Right-click check valve: turn it around",
                "Right-click machine: rotate",
                "Right-click side of an upright water pump: move its power port there",
                "Sneak + right-click: dismantle");
    }

    public static boolean isOurs(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(GoingNuclear.MOD_ID);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        BlockPos pos = context.getClickedPos();
        Direction face = context.getClickedFace();
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (!isOurs(block)) return InteractionResult.PASS;
        if (block instanceof CoriumBlock) {
            tell(player, "You cannot dismantle molten corium. Wait for it to cool.", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        if (player.isShiftKeyDown()) {
            level.destroyBlock(pos, !player.isCreative(), player);
            Sounds.at(level, pos, SoundEvents.ITEM_PICKUP, 0.6f, 0.8f);
            return InteractionResult.SUCCESS_SERVER;
        }

        switch (block) {
            case FluidPipeBlock _ -> {
                boolean open = FluidPipeBlock.toggleSide(level, pos, face);
                tell(player, "Pipe side " + face.getName() + (open ? " connected" : " disconnected"), open ? ChatFormatting.GREEN : ChatFormatting.GOLD);
                Sounds.at(level, pos, SoundEvents.STONE_HIT, 0.6f, open ? 1.2f : 0.8f);
                return InteractionResult.SUCCESS_SERVER;
            }
            case CheckValveBlock _ -> {
                Direction flow = state.getValue(CheckValveBlock.FACING).getOpposite();
                level.setBlock(pos, state.setValue(CheckValveBlock.FACING, flow), Block.UPDATE_ALL);
                PipeNetworks.invalidate(level);
                tell(player, "Valve now lets fluid flow " + flow.getName() + " only", ChatFormatting.AQUA);
                Sounds.at(level, pos, SoundEvents.STONE_HIT, 0.6f, 1.0f);
                return InteractionResult.SUCCESS_SERVER;
            }
            case ReactorPortBlock _ -> {
                ReactorPortBlock.Mode next = state.getValue(ReactorPortBlock.MODE) == ReactorPortBlock.Mode.INPUT ? ReactorPortBlock.Mode.OUTPUT : ReactorPortBlock.Mode.INPUT;
                level.setBlock(pos, state.setValue(ReactorPortBlock.MODE, next), Block.UPDATE_ALL);
                ReactorRegistry.notifyChange(level, pos);
                tell(player, "Reactor port set to " + (next == ReactorPortBlock.Mode.INPUT ? "INPUT (water in)" : "OUTPUT (steam / coolant out)"), ChatFormatting.AQUA);
                Sounds.at(level, pos, SoundEvents.STONE_HIT, 0.6f, 1.0f);
                return InteractionResult.SUCCESS_SERVER;
            }
            default -> {
                // Anything else turns (below).
            }
        }

        if (state.hasProperty(ElectricConnectable.POWER_PORT) && state.hasProperty(BlockStateProperties.FACING)
                && state.getValue(BlockStateProperties.FACING).getAxis() == Direction.Axis.Y && face.getAxis() != Direction.Axis.Y) {
            // Upright pump, clicked on a side: the power-in port goes there.
            level.setBlock(pos, state.setValue(ElectricConnectable.POWER_PORT, face), Block.UPDATE_ALL);
            rotated(level);
            tell(player, "Power-in port moved to the " + face.getName() + " side", ChatFormatting.AQUA);
            return InteractionResult.SUCCESS_SERVER;
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            Direction current = state.getValue(BlockStateProperties.FACING);
            Direction next = Direction.from3DDataValue((current.get3DDataValue() + 1) % 6);
            level.setBlock(pos, state.setValue(BlockStateProperties.FACING, next), Block.UPDATE_ALL);
            rotated(level);
            tell(player, "Now facing " + next.getName(), ChatFormatting.AQUA);
            return InteractionResult.SUCCESS_SERVER;
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction next = state.getValue(BlockStateProperties.HORIZONTAL_FACING).getClockWise();
            level.setBlock(pos, state.setValue(BlockStateProperties.HORIZONTAL_FACING, next), Block.UPDATE_ALL);
            rotated(level);
            tell(player, "Now facing " + next.getName(), ChatFormatting.AQUA);
            return InteractionResult.SUCCESS_SERVER;
        }
        return InteractionResult.PASS;
    }

    /**
     * A turned machine may connect differently now (which faces take pipes, a battery's input side).
     */
    private static void rotated(ServerLevel level) {
        PipeNetworks.invalidate(level);
        PowerGrid.of(level).markDirty();
    }

    static void tell(Player player, String text, ChatFormatting color) {
        if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendOverlayMessage(Component.literal(text).withStyle(color));
    }

    @Override
    public boolean isPolymerBlockInteraction(BlockState state, ServerPlayer player, InteractionHand hand, ItemStack stack, ServerLevel world, BlockHitResult blockHitResult, InteractionResult actionResult) {
        return true;
    }
}

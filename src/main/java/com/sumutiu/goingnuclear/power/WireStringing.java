package com.sumutiu.goingnuclear.power;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.Sounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Stringing overhead wires, the way you tie a lead: right-click a pole or electric machine with Copper Wire
 * (or pliers) and the wire hangs from it to your hand. Walk to the next pole or machine and right-click it
 * to tie the other end. You keep carrying a new wire from there, so you can run a line pole after pole.
 * Sneak + right-click the air (or the same pole again) to let go.
 */
public final class WireStringing {
    private WireStringing() {
    }

    /**
     * The player right-clicked a block while holding copper wire or pliers (not sneaking).
     */
    public static InteractionResult clickBlock(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof PowerCableBlock) {
            tell(player, "Ground cables join their neighbours on their own. Wires go between poles and machines.", ChatFormatting.GRAY);
            return InteractionResult.SUCCESS_SERVER;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof WireAnchor anchor)) {
            tell(player, "Wires tie on to power poles and electric machines.", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        PendingWires.Pending pending = PendingWires.get(player);
        if (pending == null || !pending.dimension().equals(level.dimension())) {
            PendingWires.start(player, level, pos);
            Sounds.at(level, pos, Sounds.leadTied(), 0.8f, 1.0f);
            tell(player, "Wire tied on. Walk to the next pole or machine and right-click it (max " + NuclearConfig.get().wireMaxLength + " blocks).",
                    ChatFormatting.YELLOW);
            return InteractionResult.SUCCESS_SERVER;
        }
        if (pending.pos().equals(pos)) {
            cancel(player);
            return InteractionResult.SUCCESS_SERVER;
        }
        if (!level.isLoaded(pending.pos()) || !(level.getBlockEntity(pending.pos()) instanceof WireAnchor start)) {
            PendingWires.start(player, level, pos);
            tell(player, "The pole you started from is gone; the wire now hangs from here.", ChatFormatting.YELLOW);
            return InteractionResult.SUCCESS_SERVER;
        }
        if (start.wires().contains(pos)) {
            tell(player, "These two are already connected.", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }

        Vec3 a = WireAnchor.attachPoint(level.getBlockState(pending.pos()), pending.pos());
        Vec3 b = WireAnchor.attachPoint(state, pos);
        double length = a.distanceTo(b);
        int maxLength = NuclearConfig.get().wireMaxLength;
        if (length > maxLength) {
            tell(player, String.format(Locale.ROOT, "Too far: %.1f blocks (max %d). Put a pole in between.", length, maxLength), ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        int cost = ElectricBlockEntity.wireCost(length);
        if (!player.hasInfiniteMaterials()) {
            if (wireCount(player.getInventory()) < cost) {
                tell(player, "You need " + cost + " Copper Wire for this span.", ChatFormatting.RED);
                return InteractionResult.FAIL;
            }
            takeWire(player, cost);
        }

        start.wires().add(pos);
        anchor.wires().add(pending.pos());
        ((BlockEntity) start).setChanged();
        be.setChanged();
        PowerGrid.of(level).markDirty();
        Sounds.at(level, pos, Sounds.leadTied(), 0.8f, 1.0f);
        // Keep carrying a wire from here, ready for the next pole.
        PendingWires.start(player, level, pos);
        player.sendOverlayMessage(Component.literal(String.format(Locale.ROOT,
                        "Connected (%.1f blocks, %d copper wire). Continue to the next pole, or sneak + right-click the air to let go.", length, cost))
                .withStyle(ChatFormatting.GREEN));
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * Lets go of the carried wire.
     *
     * @return false when the player was not carrying one
     */
    public static boolean cancel(ServerPlayer player) {
        if (PendingWires.get(player) == null) return false;
        PendingWires.clear(player);
        tell(player, "You let go of the wire.", ChatFormatting.GRAY);
        return true;
    }

    private static int wireCount(Inventory inventory) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(ModItems.COPPER_WIRE)) total += stack.getCount();
        }
        return total;
    }

    /**
     * Takes wire from the hands first (the stack you are using), then from the rest of the inventory.
     */
    private static void takeWire(ServerPlayer player, int amount) {
        amount = takeWire(player.getMainHandItem(), amount);
        amount = takeWire(player.getOffhandItem(), amount);
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize() && amount > 0; slot++) {
            amount = takeWire(inventory.getItem(slot), amount);
        }
    }

    private static int takeWire(ItemStack stack, int amount) {
        if (amount <= 0 || !stack.is(ModItems.COPPER_WIRE)) return amount;
        int taken = Math.min(amount, stack.getCount());
        stack.shrink(taken);
        return amount - taken;
    }

    private static void tell(ServerPlayer player, String text, ChatFormatting color) {
        player.sendOverlayMessage(Component.literal(text).withStyle(color));
    }
}

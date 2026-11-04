package com.sumutiu.goingnuclear.util;

import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class PlayerItems {
    private PlayerItems() {
    }

    /**
     * Puts the stack in the player's inventory, dropping whatever does not fit.
     */
    public static void give(Player player, ItemStack stack) {
        if (stack.isEmpty()) return;
        player.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
    }
}

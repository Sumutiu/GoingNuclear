package com.sumutiu.goingnuclear.item;

import com.sumutiu.goingnuclear.reactor.FuelRods;
import com.sumutiu.goingnuclear.util.Fmt;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * Fuel rod. Unused rods stack (16 per stack); the tooltip shows how much fuel is left. Partly used rods only
 * stack with rods that are burnt up exactly as far (see {@link FuelRods}).
 */
public class FuelRodItem extends NuclearItem {
    public FuelRodItem(Properties properties, Item fallback, String... tooltip) {
        super(properties, fallback, tooltip);
    }

    // Vanilla marks this deprecated (tooltips are moving to item components), but it is still how
    // Polymer builds the tooltip that vanilla clients see, so we keep using it.
    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, lines, flag);
        int tier = FuelRods.tierOf(stack);
        if (FuelRods.isActive(tier)) {
            lines.accept(Component.literal("Fuel left: " + Fmt.pct(FuelRods.remainingFraction(stack))).withStyle(ChatFormatting.YELLOW));
            lines.accept(Component.literal("Base power: " + Fmt.power(FuelRods.basePower(tier))).withStyle(ChatFormatting.GOLD));
        }
    }
}

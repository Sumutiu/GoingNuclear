package com.sumutiu.goingnuclear.item;

import com.sumutiu.goingnuclear.util.Visuals;
import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.ChatFormatting;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Block item with the same resource-pack / vanilla look-alike switch as {@link NuclearItem}.
 */
public class NuclearBlockItem extends PolymerBlockItem {
    private final Item fallback;
    private final List<String> tooltip;

    public NuclearBlockItem(Block block, Properties properties, Item fallback, String... tooltip) {
        super(block, properties, Items.TRIAL_KEY, true);
        this.fallback = fallback;
        this.tooltip = List.of(tooltip);
    }

    @Override
    public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
        return Visuals.hasPack(context) ? Items.TRIAL_KEY : this.fallback;
    }

    @Override
    public @Nullable Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
        return Visuals.hasPack(context) ? super.getPolymerItemModel(stack, context, lookup) : null;
    }

    // Vanilla marks this deprecated (tooltips are moving to item components), but it is still how
    // Polymer builds the tooltip that vanilla clients see, so we keep using it.
    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
        for (String line : this.tooltip) lines.accept(Component.literal(line).withStyle(ChatFormatting.GRAY));
    }
}

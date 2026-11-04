package com.sumutiu.goingnuclear.gui;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.machine.EnrichmentCentrifugeBlockEntity;
import com.sumutiu.goingnuclear.machine.EnrichmentCentrifugeBlockEntity.Input;
import com.sumutiu.goingnuclear.machine.EnrichmentCentrifugeBlockEntity.Kind;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.PlayerItems;
import com.sumutiu.goingnuclear.util.Sounds;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen of the Enrichment Centrifuge: one row per kind of fuel, with the depleted rods, the dust, the drum
 * and the enriched rods. The centrifuge keeps its contents as counts, so there are buttons instead of item
 * slots: left-click an input to put in all of it from your inventory, right-click to take it back out, click
 * the enriched rods to collect them.
 */
public class EnrichmentCentrifugeGui extends SimpleGui {
    private static final int STATUS_SLOT = 4;
    private static final int[] ROW = {9, 18};
    private final EnrichmentCentrifugeBlockEntity centrifuge;
    private int ticks;

    public EnrichmentCentrifugeGui(ServerPlayer player, EnrichmentCentrifugeBlockEntity centrifuge) {
        super(MenuType.GENERIC_9x3, player, false);
        this.centrifuge = centrifuge;
        this.setTitle(Component.literal("Enrichment Centrifuge"));
        GuiElementBuilder filler = new GuiElementBuilder(Items.STAINED_GLASS_PANE.gray()).setName(Component.literal(" ")).hideTooltip();
        for (int i = 0; i < 27; i++) this.setSlot(i, filler.build());
        this.refresh();
    }

    @Override
    public void onTick() {
        super.onTick();
        if (this.centrifuge.isRemoved()) {
            this.close();
            return;
        }
        if (++this.ticks % 10 == 0) this.refresh();
    }

    private void click() {
        Sounds.toPlayer(this.getPlayer(), SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.0f);
    }

    private static MutableComponent gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent hint(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    private void refresh() {
        this.setSlot(STATUS_SLOT, this.statusItem());
        for (Kind kind : Kind.values()) {
            int row = ROW[kind.ordinal()];
            this.setSlot(row + 1, this.inputItem(kind, Input.RODS));
            this.setSlot(row + 2, this.inputItem(kind, Input.DUST));
            this.setSlot(row + 4, this.drumItem(kind));
            this.setSlot(row + 6, this.doneItem(kind));
        }
    }

    private GuiElement statusItem() {
        NuclearConfig config = NuclearConfig.get();
        EnrichmentCentrifugeBlockEntity c = this.centrifuge;
        String waiting = c.waitingFor();
        List<Component> lore = new ArrayList<>();
        lore.add(gray("Each rod: 1 depleted rod + " + config.centrifugeDust + " dust of its kind,"));
        lore.add(gray(Fmt.num(config.centrifugeTicks / 20.0, 0) + " s at " + Fmt.power(config.centrifugePower) + " (power in: the back)."));
        lore.add(gray("Enriched rods give more power from the same fuel and burn down into"));
        lore.add(gray("Depleted Fuel Rods, which cannot be enriched again."));
        lore.add(gray("A redstone signal stops the centrifuge."));
        return new GuiElementBuilder(waiting == null ? Items.CONCRETE.lime() : c.isSwitchedOff() ? Items.CONCRETE.red() : Items.CONCRETE.yellow())
                .setName(Component.literal(waiting == null ? "Running" : "Idle: " + waiting)
                        .withStyle(waiting == null ? ChatFormatting.GREEN : ChatFormatting.YELLOW))
                .setLore(lore)
                .build();
    }

    private GuiElement inputItem(Kind kind, Input input) {
        EnrichmentCentrifugeBlockEntity c = this.centrifuge;
        boolean rods = input == Input.RODS;
        int count = rods ? c.rods(kind) : c.dust(kind);
        String what = rods ? "Depleted " + kind.word() + " rods" : kind.title() + " dust";
        List<Component> lore = new ArrayList<>();
        lore.add(gray(count + " / " + EnrichmentCentrifugeBlockEntity.room(input)));
        lore.add(hint("Left click: put in all from your inventory."));
        lore.add(hint("Right click: take them back."));
        return new GuiElementBuilder(count > 0 ? (rods ? kind.depleted() : kind.dust()) : Items.STAINED_GLASS_PANE.lightGray())
                .setName(Component.literal(what + ": " + (count > 0 ? count : "none")).withStyle(kind.color()))
                .setCount(Math.clamp(count, 1, 64))
                .setLore(lore)
                .hideDefaultTooltip()
                .setCallback((_, type, _, _) -> {
                    this.click();
                    if (type.isRight) {
                        PlayerItems.give(this.getPlayer(), this.centrifuge.takeInput(kind, input));
                    } else {
                        this.putIn(rods ? kind.depleted() : kind.dust());
                    }
                    this.refresh();
                })
                .build();
    }

    /**
     * Moves every stack of {@code item} in the player's inventory into the centrifuge, as far as there is room.
     */
    private void putIn(Item item) {
        Inventory inventory = this.getPlayer().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item) && this.centrifuge.insert(stack, true) <= 0) break;
        }
    }

    private GuiElement drumItem(Kind kind) {
        NuclearConfig config = NuclearConfig.get();
        EnrichmentCentrifugeBlockEntity c = this.centrifuge;
        List<Component> lore = new ArrayList<>();
        if (c.job() == kind) {
            double left = (1.0 - c.progress()) * config.centrifugeTicks / 20.0;
            lore.add(gray(c.isSpinning() ? "About " + Fmt.num(left, 0) + " s left at full power." : "Stopped: " + c.waitingFor()));
            return new GuiElementBuilder(c.isSpinning() ? Items.CONCRETE.lime() : Items.CONCRETE.yellow())
                    .setName(Component.literal("Enriching: " + Fmt.pct(c.progress())).withStyle(ChatFormatting.GREEN))
                    .setCount(Math.clamp((int) Math.ceil(c.progress() * 64.0), 1, 64))
                    .setLore(lore)
                    .build();
        }
        lore.add(gray("1 depleted " + kind.word() + " rod + " + config.centrifugeDust + " " + kind.word() + " dust"));
        lore.add(gray("make 1 enriched " + kind.word() + " rod."));
        return new GuiElementBuilder(Items.IRON_BARS)
                .setName(Component.literal(kind.title() + ": idle").withStyle(ChatFormatting.GRAY))
                .setLore(lore)
                .build();
    }

    private GuiElement doneItem(Kind kind) {
        int count = this.centrifuge.done(kind);
        List<Component> lore = new ArrayList<>();
        lore.add(gray(count + " / " + EnrichmentCentrifugeBlockEntity.ROD_ROOM + " (the centrifuge stops when full)"));
        lore.add(hint("Click to take them."));
        return new GuiElementBuilder(count > 0 ? kind.enriched() : Items.STAINED_GLASS_PANE.lightGray())
                .setName(Component.literal("Enriched " + kind.word() + " rods: " + (count > 0 ? count : "none")).withStyle(kind.color()))
                .setCount(Math.clamp(count, 1, 64))
                .setLore(lore)
                .hideDefaultTooltip()
                .setCallback((_, _, _, _) -> {
                    this.click();
                    PlayerItems.give(this.getPlayer(), this.centrifuge.takeDone(kind));
                    this.refresh();
                })
                .build();
    }
}

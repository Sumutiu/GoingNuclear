package com.sumutiu.goingnuclear.gui;

import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.machine.CheckValveBlockEntity;
import com.sumutiu.goingnuclear.machine.CheckValveBlockEntity.Mode;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.Sounds;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen of a Check Valve: its mode (Top up or Overflow), the level it works to (5% steps), and what it is
 * passing.
 */
public class CheckValveGui extends SimpleGui {
    private final CheckValveBlockEntity valve;
    private int ticks;

    public CheckValveGui(ServerPlayer player, CheckValveBlockEntity valve) {
        super(MenuType.GENERIC_9x1, player, false);
        this.valve = valve;
        this.setTitle(Component.literal("Check Valve"));
        GuiElementBuilder filler = new GuiElementBuilder(Items.STAINED_GLASS_PANE.gray()).setName(Component.literal(" ")).hideTooltip();
        for (int i = 0; i < 9; i++) this.setSlot(i, filler.build());
        this.refresh();
    }

    @Override
    public void onTick() {
        super.onTick();
        if (this.valve.isRemoved()) {
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

    private void refresh() {
        this.setSlot(0, this.modeItem());
        this.setSlot(2, this.adjust(-10));
        this.setSlot(3, this.adjust(-5));
        this.setSlot(4, this.levelItem());
        this.setSlot(5, this.adjust(5));
        this.setSlot(6, this.adjust(10));
        this.setSlot(8, this.statusItem());
    }

    private GuiElement modeItem() {
        Mode mode = this.valve.mode();
        List<Component> lore = new ArrayList<>();
        for (Mode m : Mode.values()) {
            lore.add(Component.literal((m == mode ? "▶ " : "  ") + m.title() + ": " + m.description())
                    .withStyle(m == mode ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        lore.add(gray("Either way, fluid only goes from the back to the front."));
        lore.add(Component.literal("Click to switch.").withStyle(ChatFormatting.YELLOW));
        return new GuiElementBuilder(mode == Mode.TOP_UP ? Items.WATER_BUCKET : Items.BUCKET)
                .setName(Component.literal("Mode: " + mode.title()).withStyle(ChatFormatting.AQUA))
                .setLore(lore)
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.valve.setMode(this.valve.mode().next());
                    this.refresh();
                })
                .build();
    }

    private GuiElement levelItem() {
        int level = this.valve.setting();
        boolean topUp = this.valve.mode() == Mode.TOP_UP;
        List<Component> lore = new ArrayList<>();
        if (topUp) {
            lore.add(gray("Lets fluid through while the side it feeds is"));
            lore.add(gray("less than " + level + "% full (for a reactor INPUT port:"));
            lore.add(gray("the vessel's coolant level)."));
            lore.add(gray(level >= CheckValveBlockEntity.MAX_SETTING ? "At 100% it is a plain one-way valve." : "100% makes it a plain one-way valve."));
        } else {
            lore.add(gray("Lets fluid through while the side it takes from"));
            lore.add(gray("is more than " + level + "% full, only what is over it."));
            lore.add(gray("With a Top up valve a few % lower on the way back"));
            lore.add(gray("(90% and 85%, say), the fluid never goes back and forth."));
        }
        return new GuiElementBuilder(Items.CLOCK)
                .setName(Component.literal((topUp ? "Tops up to " : "Spills above ") + level + "%").withStyle(ChatFormatting.AQUA))
                .setLore(lore)
                .build();
    }

    private GuiElement adjust(int delta) {
        return new GuiElementBuilder(delta > 0 ? Items.DYE.red() : Items.DYE.blue())
                .setCount(Math.abs(delta))
                .setName(Component.literal((delta > 0 ? "+" : "") + delta + "%").withStyle(delta > 0 ? ChatFormatting.RED : ChatFormatting.AQUA))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.valve.setSetting(this.valve.setting() + delta);
                    this.refresh();
                })
                .build();
    }

    private GuiElement statusItem() {
        CheckValveBlockEntity v = this.valve;
        Direction out = v.outlet();
        FluidType type = v.lastType();
        boolean passing = v.lastFlow() > 0.0 && type != null;
        List<Component> lore = new ArrayList<>();
        lore.add(gray("Flow: from " + out.getOpposite().getName() + " to " + out.getName() + " only"));
        lore.add(gray("Passing: " + (passing ? Fmt.rate(v.lastFlow()) + " of " + type.displayName().getString() : "nothing")));
        lore.add(Component.literal("Inlet side (" + out.getOpposite().getName() + "): " + fill(v.lastSourceFill()) + " full"
                + (v.mode() == Mode.OVERFLOW ? " (watched)" : "")).withStyle(v.mode() == Mode.OVERFLOW ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        lore.add(Component.literal("Outlet side (" + out.getName() + "): " + fill(v.lastFill()) + " full"
                + (v.mode() == Mode.TOP_UP ? " (watched)" : "")).withStyle(v.mode() == Mode.TOP_UP ? ChatFormatting.GOLD : ChatFormatting.GRAY));
        return new GuiElementBuilder(passing ? Items.CONCRETE.lime() : Items.CONCRETE.white())
                .setName(Component.literal(passing ? "Passing fluid" : "Not passing").withStyle(passing ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                .setLore(lore)
                .build();
    }

    private static String fill(double fill) {
        return fill < 0.0 ? "unknown" : Fmt.pct(fill);
    }
}

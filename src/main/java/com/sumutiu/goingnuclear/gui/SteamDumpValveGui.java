package com.sumutiu.goingnuclear.gui;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.machine.SteamDumpValveBlockEntity;
import com.sumutiu.goingnuclear.machine.SteamDumpValveBlockEntity.Mode;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.Sounds;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen of the Steam Dump Valve: the mode (auto, closed, open), the steam temperature it opens above, and
 * what it is doing.
 */
public class SteamDumpValveGui extends SimpleGui {
    private final SteamDumpValveBlockEntity valve;
    private int ticks;

    public SteamDumpValveGui(ServerPlayer player, SteamDumpValveBlockEntity valve) {
        super(MenuType.GENERIC_9x1, player, false);
        this.valve = valve;
        this.setTitle(Component.literal("Steam Dump Valve"));
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
        this.setSlot(2, this.adjust(-100));
        this.setSlot(3, this.adjust(-10));
        this.setSlot(4, this.settingItem());
        this.setSlot(5, this.adjust(10));
        this.setSlot(6, this.adjust(100));
        this.setSlot(8, this.statusItem());
    }

    private GuiElement modeItem() {
        Mode mode = this.valve.mode();
        Item icon = switch (mode) {
            case AUTO -> Items.COMPARATOR;
            case CLOSED -> Items.IRON_BARS;
            case OPEN -> Items.LEVER;
        };
        List<Component> lore = new ArrayList<>();
        for (Mode m : Mode.values()) {
            lore.add(Component.literal((m == mode ? "▶ " : "  ") + m.title() + ": " + m.description())
                    .withStyle(m == mode ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        lore.add(Component.literal("Click to switch.").withStyle(ChatFormatting.YELLOW));
        return new GuiElementBuilder(icon)
                .setName(Component.literal("Mode: " + mode.title()).withStyle(ChatFormatting.AQUA))
                .setLore(lore)
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.valve.setMode(this.valve.mode().next());
                    this.refresh();
                })
                .build();
    }

    private GuiElement settingItem() {
        double setting = this.valve.setting();
        List<Component> lore = new ArrayList<>();
        lore.add(gray("Opens when the steam coming in is hotter than this,"));
        lore.add(gray("fully open " + Fmt.num(SteamDumpValveBlockEntity.PROPORTIONAL_BAND, 0) + " °C above it."));
        lore.add(gray("That is: " + Fmt.steam(setting) + "."));
        lore.add(gray("Steam is about 20 °C cooler than the reactor core."));
        lore.add(gray("Running: set it a little above your normal steam."));
        lore.add(gray("Cooling down: shut the reactor down, turn it down."));
        if (this.valve.mode() != Mode.AUTO) lore.add(Component.literal("Only used in Auto mode.").withStyle(ChatFormatting.GOLD));
        return new GuiElementBuilder(Items.CLOCK)
                .setName(Component.literal("Opens above " + Fmt.temp(setting)).withStyle(ChatFormatting.WHITE))
                .setLore(lore)
                .build();
    }

    private GuiElement adjust(int delta) {
        return new GuiElementBuilder(delta > 0 ? Items.DYE.red() : Items.DYE.blue())
                .setCount(Math.min(64, Math.abs(delta) / 10))
                .setName(Component.literal((delta > 0 ? "+" : "") + delta + " °C").withStyle(delta > 0 ? ChatFormatting.RED : ChatFormatting.AQUA))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.valve.setSetting(this.valve.setting() + delta);
                    this.refresh();
                })
                .build();
    }

    private GuiElement statusItem() {
        NuclearConfig config = NuclearConfig.get();
        SteamDumpValveBlockEntity v = this.valve;
        boolean dumping = v.lastDumped() > 1.0e-3;
        List<Component> lore = new ArrayList<>();
        lore.add(gray("Steam in: " + Fmt.steam(v.inletTemperature())));
        lore.add(gray("Valve: " + Fmt.pct(v.opening()) + " open"));
        lore.add(gray("Dumping: " + Fmt.rate(v.lastDumped()) + " of " + Fmt.rate(config.steamDumpRate)
                + " (" + Fmt.power(v.lastDumped() * SteamDumpValveBlockEntity.heatPerSteam(config)) + " of heat)"));
        lore.add(gray(v.dumpsIntoTheAir() ? "Out: into the air (the water is lost)"
                : "Out: " + Fmt.bar(v.exhaustPressure()) + (v.flowFactor() < 0.999 ? ", backing up" : ", condenser vacuum")));
        lore.addAll(v.warnings());
        Item icon = v.dumpingForLong() ? Items.CONCRETE.red() : dumping ? Items.CONCRETE.lightBlue() : Items.CONCRETE.white();
        return new GuiElementBuilder(icon)
                .setName(Component.literal(dumping ? "Dumping steam" : "Shut").withStyle(dumping ? ChatFormatting.AQUA : ChatFormatting.GRAY))
                .setLore(lore)
                .build();
    }
}

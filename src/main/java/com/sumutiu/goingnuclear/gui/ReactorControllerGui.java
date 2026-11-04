package com.sumutiu.goingnuclear.gui;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.reactor.FuelRods;
import com.sumutiu.goingnuclear.reactor.ReactorControllerBlockEntity;
import com.sumutiu.goingnuclear.reactor.ReactorControllerBlockEntity.CoreCell;
import com.sumutiu.goingnuclear.reactor.Xenon;
import com.sumutiu.goingnuclear.registry.ModItems;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.Sounds;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reactor control box. One window with tabs along the bottom row (the open tab is marked), and START and
 * SCRAM always one click away there. Switching tabs only redraws the window, so it is instant and the cursor
 * stays where it is.
 * <ul>
 *     <li>Overview: what you watch while it runs, and the control rods.</li>
 *     <li>Rod control: who moves the rods (manual, hold temperature, hold power, redstone) and the target.</li>
 *     <li>Safety: automatic SCRAM and its threshold, the relief valve, boiling or pressurized mode.</li>
 *     <li>Core map: the core seen from above, one slot per column of channels (see {@link #drawCoreMap}).</li>
 *     <li>Refuel: loading and unloading fuel and control rods.</li>
 *     <li>Details: heat balance, neutronics, xenon, structure, and how to run a reactor.</li>
 * </ul>
 */
public class ReactorControllerGui extends SimpleGui {
    private static final int MAP_COLUMNS = 8;
    private static final int MAP_ROWS = 5;

    private final ServerLevel level;
    private final ReactorControllerBlockEntity controller;
    private int ticks;
    private Page page = Page.OVERVIEW;
    private boolean powerView;
    private int panRow;
    private int panColumn;

    public ReactorControllerGui(ServerPlayer player, ServerLevel level, ReactorControllerBlockEntity controller) {
        super(MenuType.GENERIC_9x6, player, false);
        this.level = level;
        this.controller = controller;
        this.setTitle(Component.literal("☢ Reactor Control"));
        this.refresh();
    }

    private enum Page {
        OVERVIEW("Overview", "Temperature, power, coolant and the control rods."),
        RODS("Rod control", "Manual, hold temperature, hold power or redstone."),
        SAFETY("Safety", "Automatic SCRAM, relief valve, boiling or pressurized."),
        CORE("Core map", "Every channel seen from above: fuel left or heat."),
        REFUEL("Refuel", "Load and unload fuel and control rods."),
        DETAILS("Details", "Heat balance, neutronics, xenon, structure, guide.");

        private final String title;
        private final String description;

        Page(String title, String description) {
            this.title = title;
            this.description = description;
        }
    }

    @Override
    public void onTick() {
        super.onTick();
        if (this.controller.isRemoved() || this.controller.getLevel() == null) {
            this.close();
            return;
        }
        BlockPos pos = this.controller.getBlockPos();
        if (!this.getPlayer().blockPosition().closerThan(pos, 10.0)) {
            this.close();
            return;
        }
        if (++this.ticks % 10 == 0) this.refresh();
    }

    private void click() {
        Sounds.toPlayer(this.getPlayer(), SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.0f);
    }

    private void tell(String text, ChatFormatting color) {
        this.getPlayer().sendSystemMessage(Component.literal(text).withStyle(color));
    }

    private static List<Component> lore(Component... lines) {
        return new ArrayList<>(Arrays.asList(lines));
    }

    private static MutableComponent gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent hint(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    private void refresh() {
        GuiElementBuilder filler = new GuiElementBuilder(Items.STAINED_GLASS_PANE.gray()).setName(Component.literal(" ")).hideTooltip();
        for (int i = 0; i < 54; i++) this.setSlot(i, filler.build());
        switch (this.page) {
            case OVERVIEW -> this.drawOverview();
            case RODS -> this.drawRods();
            case SAFETY -> this.drawSafety();
            case CORE -> this.drawCoreMap();
            case REFUEL -> this.drawRefuel();
            case DETAILS -> this.drawDetails();
        }
        this.drawNavigation();
    }

    private void open(Page page) {
        this.click();
        this.page = page;
        this.refresh();
    }

    // =====================================================================================
    // Bottom row: tabs, START, SCRAM, close
    // =====================================================================================

    private void drawNavigation() {
        this.setSlot(45, this.tab(Page.OVERVIEW, Items.CLOCK));
        this.setSlot(46, this.tab(Page.RODS, Items.STICK));
        this.setSlot(47, this.tab(Page.SAFETY, Items.SHIELD));
        this.setSlot(48, this.tab(Page.CORE, Items.MAP));
        this.setSlot(49, this.tab(Page.REFUEL, Items.HOPPER));
        this.setSlot(50, this.tab(Page.DETAILS, Items.BOOK));
        this.setSlot(51, new GuiElementBuilder(Items.DYE.lime())
                .setName(Component.literal("START / RESET").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD))
                .setLore(lore(gray("Clears the SCRAM so the control rods follow your setting.")))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    String error = this.controller.reset(this.level);
                    if (error != null) this.tell(error, ChatFormatting.RED);
                    this.refresh();
                })
                .build());
        this.setSlot(52, new GuiElementBuilder(Items.TNT)
                .setName(Component.literal("SCRAM").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
                .setLore(lore(gray("Emergency shutdown: drops every control rod in."),
                        gray("Decay heat keeps the core warm, so keep the coolant flowing!")))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.controller.scram(this.level, "manual SCRAM by " + this.getPlayer().getName().getString());
                    this.refresh();
                })
                .build());
        this.setSlot(53, new GuiElementBuilder(Items.BARRIER)
                .setName(Component.literal("Close").withStyle(ChatFormatting.RED))
                .setCallback((_, _, _, _) -> this.close())
                .build());
    }

    private GuiElement tab(Page target, Item icon) {
        boolean open = this.page == target;
        MutableComponent name = open
                ? Component.literal("▶ " + target.title).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                : Component.literal(target.title).withStyle(ChatFormatting.AQUA);
        return new GuiElementBuilder(icon)
                .setName(name)
                .setLore(lore(gray(target.description), open ? Component.literal("(open)").withStyle(ChatFormatting.GREEN) : hint("Click to open.")))
                .hideDefaultTooltip()
                .setCallback((_, _, _, _) -> {
                    if (this.page != target) this.open(target);
                })
                .build();
    }

    // =====================================================================================
    // Overview
    // =====================================================================================

    private void drawOverview() {
        ReactorControllerBlockEntity c = this.controller;
        boolean formed = c.isFormed();

        // Row 0: readings.
        double temp = c.temperature();
        this.setSlot(0, new GuiElementBuilder(temp >= 700 ? Items.MAGMA_BLOCK : temp >= 100 ? Items.MAGMA_CREAM : Items.SNOWBALL)
                .setName(Component.literal("Core temperature: " + Fmt.temp(temp)).withStyle(Fmt.tempColor(temp)))
                .setLore(lore(gray("Fuel damage starts at 1200 °C."), gray("SCRAM threshold: " + Fmt.temp(c.scramTemp()))))
                .build());
        this.setSlot(1, new GuiElementBuilder(Items.BLAZE_POWDER)
                .setName(Component.literal("Thermal power: " + Fmt.power(c.thermalPower())).withStyle(ChatFormatting.GOLD))
                .setLore(lore(gray("Decay heat: " + Fmt.power(c.decayHeat())),
                        gray("Average multiplication k: " + Fmt.num(c.averageK(), 3)),
                        gray("Active fuel rods: " + c.activeRods())))
                .build());
        this.setSlot(2, this.coolantItem());
        this.setSlot(3, this.outputItem());
        double integrity = c.integrity();
        this.setSlot(4, new GuiElementBuilder(integrity > 75 ? Items.IRON_CHESTPLATE : integrity > 30 ? Items.CHAINMAIL_CHESTPLATE : Items.LEATHER_CHESTPLATE)
                .setName(Component.literal("Core integrity: " + Fmt.pct(integrity / 100.0))
                        .withStyle(integrity > 75 ? ChatFormatting.GREEN : integrity > 30 ? ChatFormatting.GOLD : ChatFormatting.RED))
                .setLore(lore(gray("Drops above 1200 °C or over-pressure."), gray("At 0% the reactor melts down.")))
                .hideDefaultTooltip()
                .build());
        if (NuclearConfig.get().xenonPoisoning) this.setSlot(5, this.xenonItem());
        List<Component> fuel = fuelLines(c);
        fuel.add(gray("Average fuel left: " + Fmt.pct(averageFuel(c))));
        fuel.add(hint("Core map and Refuel tabs below."));
        this.setSlot(6, new GuiElementBuilder(Items.BLAZE_ROD)
                .setName(fuelTitle(c))
                .setLore(fuel)
                .build());
        this.setSlot(7, this.structureItem());
        boolean running = formed && !c.isScrammed();
        this.setSlot(8, new GuiElementBuilder(running ? Items.CONCRETE.lime() : Items.CONCRETE.red())
                .setName(Component.literal(running ? "RUNNING" : "SHUT DOWN").withStyle(running ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD))
                .setLore(lore(gray(c.isScrammed() && !c.scramReason().isEmpty() ? "Last SCRAM: " + c.scramReason() : " "),
                        gray(c.isPressurized() ? "Mode: pressurized (closed loop)" : "Mode: boiling (direct steam)")))
                .build());

        // Row 2: the control rods.
        this.drawInsertionRow(18);

        // Row 3: shortcuts to the other tabs, showing their main setting.
        this.setSlot(29, this.shortcut(Page.RODS, Items.COMPARATOR, "Rod control: " + c.controlModeText(), ChatFormatting.AQUA));
        this.setSlot(31, this.shortcut(Page.SAFETY, c.autoScram() ? Items.REDSTONE_TORCH : Items.LEVER,
                "Automatic SCRAM " + (c.autoScram() ? "at " + Fmt.temp(c.scramTemp()) : "OFF") + ", relief valve "
                        + (c.reliefValve() ? "automatic" : "closed"), c.autoScram() ? ChatFormatting.GREEN : ChatFormatting.RED));
        this.setSlot(33, this.shortcut(Page.CORE, Items.MAP, "Core map: " + c.fuelChannels() + " fuel / " + c.controlChannels() + " control channels",
                ChatFormatting.YELLOW));

        // Row 4: a warning, if there is one.
        Component warning = c.xenonWarning();
        if (warning != null) {
            this.setSlot(40, new GuiElementBuilder(Items.DRAGON_BREATH).setName(Component.literal("Xenon").withStyle(ChatFormatting.GOLD))
                    .setLore(lore(warning)).build());
        }
    }

    private GuiElement shortcut(Page target, Item icon, String text, ChatFormatting color) {
        return new GuiElementBuilder(icon)
                .setName(Component.literal(text).withStyle(color))
                .setLore(lore(hint("Click to open the " + target.title + " tab.")))
                .hideDefaultTooltip()
                .setCallback((_, _, _, _) -> this.open(target))
                .build();
    }

    private GuiElement coolantItem() {
        ReactorControllerBlockEntity c = this.controller;
        List<Component> coolantLore = lore(gray("In through INPUT ports: " + Fmt.rate(c.waterIn())),
                gray("Low coolant = less moderation = less power,"),
                gray("but also nothing to carry the heat away!"));
        if (c.isPressurized()) {
            coolantLore.add(gray("Coolant coming back is always taken;"));
            coolantLore.add(gray("fresh water only below " + Fmt.pct(NuclearConfig.get().makeUpLevel) + " full (make-up)."));
        }
        if (c.isVentingCoolant()) {
            coolantLore.add(Component.literal("Relief valve venting: the loop was too full to move.").withStyle(ChatFormatting.GOLD));
        }
        return new GuiElementBuilder(Items.WATER_BUCKET)
                .setName(Component.literal("Coolant: " + Fmt.mb(c.coolantInVessel()) + " / " + Fmt.mb(c.vesselCapacity())).withStyle(ChatFormatting.BLUE))
                .setLore(coolantLore)
                .build();
    }

    private GuiElement outputItem() {
        ReactorControllerBlockEntity c = this.controller;
        List<Component> outLore;
        String outTitle;
        if (c.isPressurized()) {
            double minimum = NuclearConfig.get().heatExchangerMinTemp;
            outTitle = "Coolant: " + Fmt.temp(c.coolantTemperature());
            outLore = lore(gray("Heat into the coolant: " + Fmt.power(c.heatRemoved())),
                    gray("Out through ports: " + Fmt.rate(c.outFlow())),
                    gray("Pressure (pressurizer): " + Fmt.bar(c.pressureBar())),
                    gray("Heat exchangers make steam only from coolant"),
                    gray("hotter than " + Fmt.temp(minimum) + "."));
        } else {
            double fill = c.steamFill();
            outTitle = "Steam: making " + Fmt.rate(c.made());
            outLore = lore(gray("Made in the core: " + Fmt.rate(c.made())),
                    gray("Out through ports: " + Fmt.rate(c.outFlow())),
                    gray("In the core now: " + Fmt.mb(c.steam().amount()) + " (it leaves as fast as it is made)"),
                    gray("Pressure (boiling at " + Fmt.temp(Math.max(100.0, c.temperature())) + "): " + Fmt.bar(c.pressureBar())),
                    Component.literal("Steam space: " + Fmt.pct(fill) + " full").withStyle(fill > 0.9 ? ChatFormatting.RED : fill > 0.5 ? ChatFormatting.GOLD : ChatFormatting.GRAY),
                    gray("Relief valve opens at 95%, the vessel is damaged"),
                    gray("above 100% and ruptures above 150%."),
                    gray("Vented by relief valve: " + Fmt.rate(c.vented())));
        }
        return new GuiElementBuilder(c.isPressurized() ? Items.LAVA_BUCKET : Items.POWDER_SNOW_BUCKET)
                .setName(Component.literal(outTitle).withStyle(c.isPressurized() ? Fmt.tempColor(c.coolantTemperature()) : ChatFormatting.WHITE))
                .setLore(outLore)
                .build();
    }

    private GuiElement xenonItem() {
        ReactorControllerBlockEntity c = this.controller;
        Xenon xenon = c.xenon();
        double powerFactor = xenon.powerFactor();
        List<Component> xenonLore = lore(
                gray("Costs " + Fmt.pct(1.0 - xenon.reactivityFactor()) + " of the multiplication: power "
                        + Fmt.pct(powerFactor) + " at the same rods."),
                gray("Heading: " + ReactorControllerBlockEntity.xenonTrend(xenon.forecast(c.powerLevel())) + "."),
                gray("Running steadily at full power it settles at 1.00x;"),
                gray("at lower power, lower."),
                gray("Fission makes iodine, which turns into xenon, a neutron"),
                gray("poison that a running reactor burns off. After a shutdown"),
                gray("or a power cut it builds up for a few minutes, then fades:"),
                gray("until it has, the reactor may not reach full power."));
        Component warning = c.xenonWarning();
        if (warning != null) xenonLore.add(warning);
        return new GuiElementBuilder(Items.DRAGON_BREATH)
                .setName(Component.literal("Xenon: " + Fmt.num(xenon.level(), 2) + "x the full-power level")
                        .withStyle(powerFactor < 0.85 ? ChatFormatting.GOLD : ChatFormatting.LIGHT_PURPLE))
                .setLore(xenonLore)
                .build();
    }

    private GuiElement structureItem() {
        ReactorControllerBlockEntity c = this.controller;
        boolean formed = c.isFormed();
        return new GuiElementBuilder(formed ? Items.IRON_BLOCK : Items.BARRIER)
                .setName(Component.literal(formed ? "Structure " + c.sizeText() : "Structure incomplete").withStyle(formed ? ChatFormatting.GREEN : ChatFormatting.RED))
                .setLore(lore(c.status(),
                        gray("Interior volume: " + c.interiorVolume() + " blocks"),
                        gray("Ports: " + c.inputPorts() + " input / " + c.outputPorts() + " output"),
                        gray("Control rods: " + c.controlRodsInstalled() + " / " + c.controlChannels() + " channels")))
                .build();
    }

    /**
     * The control rod row (9 slots from {@code first}): -10, -1, the insertion bar, +1, +10.
     */
    private void drawInsertionRow(int first) {
        ReactorControllerBlockEntity c = this.controller;
        double insertion = c.insertion();
        this.setSlot(first, this.insertionButton(-10));
        this.setSlot(first + 1, this.insertionButton(-1));
        for (int i = 0; i < 5; i++) {
            double segment = (i + 1) * 20.0;
            boolean filled = insertion >= segment - 10.0;
            this.setSlot(first + 2 + i, new GuiElementBuilder(filled ? Items.STAINED_GLASS_PANE.black() : Items.STAINED_GLASS_PANE.white())
                    .setName(Component.literal("Control rods: " + Fmt.num(insertion, 0) + "% inserted").withStyle(ChatFormatting.WHITE))
                    .setLore(lore(gray("100% = fully in (minimum power)."), gray("0% = fully out (maximum power)."),
                            gray("Control: " + c.controlModeText()),
                            gray(c.isScrammed() ? "SCRAM active: rods are forced fully in." : " ")))
                    .build());
        }
        this.setSlot(first + 7, this.insertionButton(1));
        this.setSlot(first + 8, this.insertionButton(10));
    }

    // =====================================================================================
    // Rod control
    // =====================================================================================

    private void drawRods() {
        ReactorControllerBlockEntity c = this.controller;
        ReactorControllerBlockEntity.ControlMode mode = c.controlMode();

        // Row 0: one button per mode.
        int slot = 1;
        for (ReactorControllerBlockEntity.ControlMode option : ReactorControllerBlockEntity.ControlMode.values()) {
            boolean selected = option == mode;
            Item icon = switch (option) {
                case MANUAL -> Items.LEVER;
                case TEMPERATURE -> Items.MAGMA_CREAM;
                case POWER -> Items.BLAZE_POWDER;
                case REDSTONE -> Items.REDSTONE_TORCH;
            };
            this.setSlot(slot, new GuiElementBuilder(icon)
                    .setName(selected ? Component.literal("▶ " + option.title()).withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                            : Component.literal(option.title()).withStyle(ChatFormatting.AQUA))
                    .setLore(lore(gray(option.description()),
                            selected ? Component.literal("(selected)").withStyle(ChatFormatting.GREEN) : hint("Click to select."),
                            gray("Automatic modes never go past a SCRAM; press START after one."),
                            gray("In Redstone mode a signal no longer triggers a SCRAM.")))
                    .hideDefaultTooltip()
                    .setCallback((_, _, _, _) -> {
                        this.click();
                        this.controller.setControlMode(option);
                        this.refresh();
                    })
                    .build());
            slot += 2;
        }

        // Row 2: the target of the selected mode.
        switch (mode) {
            case TEMPERATURE -> {
                this.setSlot(20, this.targetButton(-50));
                this.setSlot(21, this.targetButton(-10));
                this.setSlot(22, new GuiElementBuilder(Items.COMPARATOR)
                        .setName(Component.literal("Target temperature: " + Fmt.temp(c.targetTemp())).withStyle(ChatFormatting.WHITE))
                        .setLore(lore(gray("The rods move to bring the core to this temperature."),
                                gray("With plenty of coolant the core stays cooler than this:"),
                                gray("then the rods simply come all the way out."),
                                gray("Keep it below the SCRAM threshold (" + Fmt.temp(c.scramTemp()) + ").")))
                        .build());
                this.setSlot(23, this.targetButton(10));
                this.setSlot(24, this.targetButton(50));
            }
            case POWER -> {
                this.setSlot(20, this.targetButton(-25));
                this.setSlot(21, this.targetButton(-5));
                this.setSlot(22, new GuiElementBuilder(Items.COMPARATOR)
                        .setName(Component.literal("Target power: " + Fmt.num(c.targetPower(), 0) + "% (" + Fmt.power(c.nominalPower() * c.targetPower() / 100.0) + ")")
                                .withStyle(ChatFormatting.WHITE))
                        .setLore(lore(gray("Fission power as a share of the fuel's nominal power"),
                                gray("(" + Fmt.power(c.nominalPower()) + " for the rods loaded now)."),
                                gray("Above 100% only works if the fuel is packed closely.")))
                        .build());
                this.setSlot(23, this.targetButton(5));
                this.setSlot(24, this.targetButton(25));
            }
            case REDSTONE -> this.setSlot(22, new GuiElementBuilder(Items.REDSTONE)
                    .setName(Component.literal("Redstone signal: " + this.level.getBestNeighborSignal(c.getBlockPos())).withStyle(ChatFormatting.RED))
                    .setLore(lore(gray("Power the control box with redstone:"),
                            gray("0 = rods fully in, 15 = rods fully out."),
                            gray("Losing the signal shuts the reactor down.")))
                    .build());
            case MANUAL -> this.setSlot(22, new GuiElementBuilder(Items.LEVER)
                    .setName(Component.literal("Manual: you set the rods").withStyle(ChatFormatting.WHITE))
                    .setLore(lore(gray("Use the rod buttons at the bottom of this page"),
                            gray("(or on the Overview tab). Pressing one in an"),
                            gray("automatic mode switches to manual.")))
                    .build());
        }

        // Row 4: the control rods themselves.
        this.drawInsertionRow(36);
    }

    // =====================================================================================
    // Safety
    // =====================================================================================

    private void drawSafety() {
        ReactorControllerBlockEntity c = this.controller;
        this.setSlot(10, new GuiElementBuilder(c.autoScram() ? Items.REDSTONE_TORCH : Items.LEVER)
                .setName(Component.literal("Automatic SCRAM: " + (c.autoScram() ? "ON" : "OFF")).withStyle(c.autoScram() ? ChatFormatting.GREEN : ChatFormatting.RED))
                .setLore(lore(gray("Shuts the reactor down when the core reaches the threshold."), hint("Click to switch.")))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.controller.setAutoScram(!this.controller.autoScram());
                    this.refresh();
                })
                .build());
        this.setSlot(12, new GuiElementBuilder(Items.DYE.blue())
                .setName(Component.literal("Threshold -50 °C").withStyle(ChatFormatting.AQUA))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.controller.setScramTemp(this.controller.scramTemp() - 50.0);
                    this.refresh();
                })
                .build());
        this.setSlot(13, new GuiElementBuilder(Items.COMPARATOR)
                .setName(Component.literal("SCRAM threshold: " + Fmt.temp(c.scramTemp())).withStyle(ChatFormatting.WHITE))
                .setLore(lore(gray("Fuel damage starts at 1200 °C.")))
                .build());
        this.setSlot(14, new GuiElementBuilder(Items.DYE.red())
                .setName(Component.literal("Threshold +50 °C").withStyle(ChatFormatting.RED))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.controller.setScramTemp(this.controller.scramTemp() + 50.0);
                    this.refresh();
                })
                .build());
        this.setSlot(16, new GuiElementBuilder(c.reliefValve() ? Items.TRIPWIRE_HOOK : Items.IRON_TRAPDOOR)
                .setName(Component.literal("Relief valve: " + (c.reliefValve() ? "AUTOMATIC" : "CLOSED")).withStyle(c.reliefValve() ? ChatFormatting.GREEN : ChatFormatting.RED))
                .setLore(lore(gray("Boiling mode: vents steam above 95% pressure."),
                        gray("Closed: pressure keeps rising until the vessel ruptures."),
                        gray("Pressurized mode: if the loop is so full that the"),
                        gray("coolant cannot move and the core overheats, it lets"),
                        gray("some out (down to 95%)."),
                        hint("Click to switch.")))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.controller.setReliefValve(!this.controller.reliefValve());
                    this.refresh();
                })
                .build());
        this.setSlot(31, new GuiElementBuilder(c.isPressurized() ? Items.HEAVY_CORE : Items.CAULDRON)
                .setName(Component.literal(c.isPressurized() ? "Mode: Pressurized (PWR)" : "Mode: Boiling (BWR)").withStyle(ChatFormatting.AQUA))
                .setLore(lore(gray("Boiling: output ports give steam straight to turbines."),
                        gray("Pressurized: output ports give coolant for a heat exchanger;"),
                        gray("needs a circulation pump to return the water."),
                        hint("Click to switch (reactor must be shut down and cool).")))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    String error = this.controller.togglePressurized();
                    if (error != null) this.tell(error, ChatFormatting.RED);
                    this.refresh();
                })
                .build());
        this.setSlot(40, new GuiElementBuilder(Items.REDSTONE)
                .setName(Component.literal(c.isScrammed() && !c.scramReason().isEmpty() ? "Last SCRAM: " + c.scramReason() : "No SCRAM")
                        .withStyle(c.isScrammed() ? ChatFormatting.RED : ChatFormatting.GREEN))
                .setLore(lore(gray("A redstone pulse into the control box also triggers a SCRAM"),
                        gray("(except in Redstone rod control)."),
                        gray("Comparators read the core temperature (1 per 100 °C).")))
                .build());
    }

    // =====================================================================================
    // Core map
    // =====================================================================================

    /**
     * The core seen from above, as from the control box (its wall is at the bottom), one slot per column of
     * channels: 8 x 5 of it at a time, with arrows on the right for bigger cores. A column of stacked channels
     * is one slot; its number is how many channels are stacked there, and its tooltip lists every level from
     * the top. Coloured by the fuel left in its most spent rod, or (heat view) by the heat it makes compared
     * with the hottest column.
     */
    private void drawCoreMap() {
        ReactorControllerBlockEntity c = this.controller;
        if (!c.isFormed()) {
            this.setSlot(22, new GuiElementBuilder(Items.BARRIER)
                    .setName(Component.literal("No reactor formed").withStyle(ChatFormatting.RED))
                    .setLore(lore(c.status()))
                    .build());
            return;
        }
        Direction forward = c.viewDirection();
        Direction right = forward.getClockWise();
        BlockPos lo = c.coreMin();
        BlockPos hi = c.coreMax();
        int forwardMin = Integer.MAX_VALUE;
        int forwardMax = Integer.MIN_VALUE;
        int rightMin = Integer.MAX_VALUE;
        int rightMax = Integer.MIN_VALUE;
        for (int x : new int[]{lo.getX(), hi.getX()}) {
            for (int z : new int[]{lo.getZ(), hi.getZ()}) {
                int f = along(forward, x, z);
                int r = along(right, x, z);
                forwardMin = Math.min(forwardMin, f);
                forwardMax = Math.max(forwardMax, f);
                rightMin = Math.min(rightMin, r);
                rightMax = Math.max(rightMax, r);
            }
        }
        int rows = forwardMax - forwardMin + 1;
        int columns = rightMax - rightMin + 1;
        this.panRow = Math.clamp(this.panRow, 0, Math.max(0, rows - MAP_ROWS));
        this.panColumn = Math.clamp(this.panColumn, 0, Math.max(0, columns - MAP_COLUMNS));

        // Channels by column, top first.
        Map<Long, List<CoreCell>> byColumn = new HashMap<>();
        for (CoreCell cell : c.coreCells()) {
            byColumn.computeIfAbsent(columnKey(cell.pos().getX(), cell.pos().getZ()), _ -> new ArrayList<>()).add(cell);
        }
        double hottest = 0.0;
        for (List<CoreCell> column : byColumn.values()) {
            column.sort(Comparator.comparingInt((CoreCell cell) -> cell.pos().getY()).reversed());
            hottest = Math.max(hottest, columnPower(column));
        }

        for (int row = 0; row < MAP_ROWS; row++) {
            for (int col = 0; col < MAP_COLUMNS; col++) {
                int mapRow = this.panRow + row;
                int mapColumn = this.panColumn + col;
                if (mapRow >= rows || mapColumn >= columns) continue;
                int f = forwardMax - mapRow;
                int r = rightMin + mapColumn;
                int x = f * forward.getStepX() + r * right.getStepX();
                int z = f * forward.getStepZ() + r * right.getStepZ();
                List<CoreCell> column = byColumn.get(columnKey(x, z));
                this.setSlot(row * 9 + col, column == null ? emptyColumn() : this.columnItem(column, hottest));
            }
        }

        // Right-hand column: scrolling, and the view switch with the legend.
        boolean fits = rows <= MAP_ROWS && columns <= MAP_COLUMNS;
        this.setSlot(8, this.panButton("▲ Scroll up", this.panRow > 0, -1, 0));
        this.setSlot(17, this.panButton("◀ Scroll left", this.panColumn > 0, 0, -1));
        this.setSlot(26, this.panButton("▶ Scroll right", this.panColumn + MAP_COLUMNS < columns, 0, 1));
        this.setSlot(35, this.panButton("▼ Scroll down", this.panRow + MAP_ROWS < rows, 1, 0));
        List<Component> legend = new ArrayList<>();
        legend.add(gray("Seen from above, from the control box: its wall is"));
        legend.add(gray("at the bottom. One slot per column of channels; the"));
        legend.add(gray("number is how many are stacked there."));
        legend.add(Component.literal(" "));
        if (this.powerView) {
            legend.add(Component.literal("Red: over 75% of the hottest column").withStyle(ChatFormatting.RED));
            legend.add(Component.literal("Orange: 50-75%").withStyle(ChatFormatting.GOLD));
            legend.add(Component.literal("Yellow: 25-50%").withStyle(ChatFormatting.YELLOW));
            legend.add(Component.literal("White: under 25%").withStyle(ChatFormatting.WHITE));
            legend.add(gray("Light grey: no heat (no fuel, or shut down)"));
        } else {
            legend.add(Component.literal("Green: over 50% fuel left").withStyle(ChatFormatting.GREEN));
            legend.add(Component.literal("Yellow: 20-50%").withStyle(ChatFormatting.YELLOW));
            legend.add(Component.literal("Orange: 10-20%").withStyle(ChatFormatting.GOLD));
            legend.add(Component.literal("Red: under 10% (losing power)").withStyle(ChatFormatting.RED));
            legend.add(gray("Grey: a spent rod to replace"));
            legend.add(Component.literal("White pane: empty fuel channel").withStyle(ChatFormatting.WHITE));
        }
        legend.add(gray("Iron bars: control rods only. Light blue: coolant only."));
        if (!fits) {
            legend.add(gray("Showing rows " + (this.panRow + 1) + "-" + Math.min(rows, this.panRow + MAP_ROWS) + " of " + rows
                    + ", columns " + (this.panColumn + 1) + "-" + Math.min(columns, this.panColumn + MAP_COLUMNS) + " of " + columns + "."));
        }
        legend.add(hint("Click to show " + (this.powerView ? "the fuel left." : "the heat each column makes.")));
        this.setSlot(44, new GuiElementBuilder(this.powerView ? Items.BLAZE_POWDER : Items.BLAZE_ROD)
                .setName(Component.literal("Showing: " + (this.powerView ? "heat" : "fuel left")).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD))
                .setLore(legend)
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.powerView = !this.powerView;
                    this.refresh();
                })
                .build());
    }

    private static int along(Direction direction, int x, int z) {
        return x * direction.getStepX() + z * direction.getStepZ();
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static double columnPower(List<CoreCell> column) {
        double power = 0.0;
        for (CoreCell cell : column) power += cell.power();
        return power;
    }

    private static GuiElement emptyColumn() {
        return new GuiElementBuilder(Items.STAINED_GLASS_PANE.lightBlue())
                .setName(Component.literal("Coolant only").withStyle(ChatFormatting.AQUA))
                .setLore(lore(gray("No fuel or control rod channels in this column.")))
                .build();
    }

    private GuiElement panButton(String text, boolean possible, int rows, int columns) {
        if (!possible) {
            return new GuiElementBuilder(Items.STAINED_GLASS_PANE.gray()).setName(Component.literal(text).withStyle(ChatFormatting.DARK_GRAY))
                    .setLore(lore(gray("Nothing more that way."))).build();
        }
        return new GuiElementBuilder(Items.ARROW)
                .setName(Component.literal(text).withStyle(ChatFormatting.YELLOW))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.panRow += rows;
                    this.panColumn += columns;
                    this.refresh();
                })
                .build();
    }

    /**
     * One column of the core map: its colour, the number of channels stacked there, and one tooltip line per
     * level from the top.
     */
    private GuiElement columnItem(List<CoreCell> column, double hottest) {
        double worst = Double.MAX_VALUE;
        boolean anyFuel = false;
        boolean anyActive = false;
        boolean anySpent = false;
        int controls = 0;
        int rods = 0;
        double inserted = 0.0;
        for (CoreCell cell : column) {
            if (cell.isFuel()) {
                anyFuel = true;
                if (FuelRods.isActive(cell.tier())) {
                    anyActive = true;
                    worst = Math.min(worst, cell.fuelLeft());
                } else if (FuelRods.isSpent(cell.tier())) {
                    anySpent = true;
                }
            } else {
                controls++;
                if (cell.hasRod()) {
                    rods++;
                    inserted += cell.inserted();
                }
            }
        }
        double power = columnPower(column);

        Item icon;
        MutableComponent name;
        if (!anyFuel) {
            icon = Items.IRON_BARS;
            name = Component.literal(rods > 0 ? "Control rods: " + Fmt.pct(inserted / rods) + " in" : "Control channel without a rod")
                    .withStyle(rods > 0 ? ChatFormatting.WHITE : ChatFormatting.GRAY);
        } else if (this.powerView) {
            double share = hottest > 0.0 ? power / hottest : 0.0;
            icon = power <= 0.0 ? Items.CONCRETE.lightGray() : share >= 0.75 ? Items.CONCRETE.red() : share >= 0.5 ? Items.CONCRETE.orange()
                    : share >= 0.25 ? Items.CONCRETE.yellow() : Items.CONCRETE.white();
            name = Component.literal("Heat: " + Fmt.power(power) + (hottest > 0.0 ? " (" + Fmt.pct(share) + " of the hottest)" : ""))
                    .withStyle(share >= 0.75 ? ChatFormatting.RED : share >= 0.5 ? ChatFormatting.GOLD : ChatFormatting.YELLOW);
        } else if (anySpent) {
            icon = Items.CONCRETE.gray();
            name = Component.literal("Fuel: a spent rod to replace").withStyle(ChatFormatting.GRAY);
        } else if (anyActive) {
            icon = worst > 0.5 ? Items.CONCRETE.lime() : worst > 0.2 ? Items.CONCRETE.yellow() : worst > 0.1 ? Items.CONCRETE.orange() : Items.CONCRETE.red();
            name = Component.literal("Fuel: " + Fmt.pct(worst) + " left" + (column.size() > 1 ? " in the most spent rod" : ""))
                    .withStyle(worst > 0.5 ? ChatFormatting.GREEN : worst > 0.2 ? ChatFormatting.YELLOW : worst > 0.1 ? ChatFormatting.GOLD : ChatFormatting.RED);
        } else {
            icon = Items.STAINED_GLASS_PANE.white();
            name = Component.literal("Empty fuel channel" + (column.size() > 1 ? "s" : "")).withStyle(ChatFormatting.WHITE);
        }

        List<Component> lines = new ArrayList<>();
        for (int i = 0; i < column.size(); i++) {
            String level = column.size() == 1 ? "" : i == 0 ? "Top: " : i == column.size() - 1 ? "Bottom: " : "Level " + (i + 1) + ": ";
            lines.add(gray(level + describe(column.get(i))));
        }
        if (anyFuel && column.size() > 1) lines.add(gray("Column: " + Fmt.power(power)));
        if (controls > 0 && anyFuel) lines.add(gray(controls + " control rod channel" + (controls > 1 ? "s" : "") + " in this column"));
        return new GuiElementBuilder(icon)
                .setName(name)
                .setCount(Math.clamp(column.size(), 1, 64))
                .setLore(lines)
                .build();
    }

    private static String describe(CoreCell cell) {
        if (!cell.isFuel()) {
            return cell.hasRod() ? "control rod, " + Fmt.pct(cell.inserted()) + " in" : "control channel, no rod";
        }
        int tier = cell.tier();
        if (FuelRods.isActive(tier)) return FuelRods.name(tier) + ", " + Fmt.pct(cell.fuelLeft()) + " left, " + Fmt.power(cell.power());
        if (FuelRods.isSpent(tier)) return FuelRods.name(tier) + " (replace it)";
        return "empty fuel channel";
    }

    /**
     * "Fuel: 12 rods" (the rods that still have fuel in them).
     */
    private static MutableComponent fuelTitle(ReactorControllerBlockEntity c) {
        int rods = c.countTier(FuelRods.REDSTONE) + c.countTier(FuelRods.ENRICHED_REDSTONE)
                + c.countTier(FuelRods.GLOWSTONE) + c.countTier(FuelRods.ENRICHED_GLOWSTONE);
        return Component.literal("Fuel: " + rods + " rod" + (rods == 1 ? "" : "s")).withStyle(ChatFormatting.YELLOW);
    }

    /**
     * The rods in the core, by kind.
     */
    private static List<Component> fuelLines(ReactorControllerBlockEntity c) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Redstone: " + c.countTier(FuelRods.REDSTONE) + ", enriched: " + c.countTier(FuelRods.ENRICHED_REDSTONE))
                .withStyle(ChatFormatting.RED));
        lines.add(Component.literal("Glowstone: " + c.countTier(FuelRods.GLOWSTONE) + ", enriched: " + c.countTier(FuelRods.ENRICHED_GLOWSTONE))
                .withStyle(ChatFormatting.GOLD));
        lines.add(gray("Depleted rods: " + c.countSpent()));
        return lines;
    }

    // =====================================================================================
    // Refuel
    // =====================================================================================

    private void drawRefuel() {
        ReactorControllerBlockEntity c = this.controller;
        List<Component> fuel = fuelLines(c);
        fuel.add(gray("Fuel channels: " + c.fuelChannels()));
        fuel.add(gray("Average fuel left: " + Fmt.pct(averageFuel(c))));
        fuel.add(gray("Control rods: " + c.controlRodsInstalled() + " / " + c.controlChannels() + " channels"));
        this.setSlot(4, new GuiElementBuilder(Items.BLAZE_ROD)
                .setName(fuelTitle(c))
                .setLore(fuel)
                .build());
        this.setSlot(19, this.loadButton(ModItems.REDSTONE_FUEL_ROD, "Load redstone rods", ChatFormatting.RED));
        this.setSlot(20, this.loadButton(ModItems.ENRICHED_REDSTONE_FUEL_ROD, "Load enriched redstone rods", ChatFormatting.RED));
        this.setSlot(21, this.loadButton(ModItems.GLOWSTONE_FUEL_ROD, "Load glowstone rods", ChatFormatting.GOLD));
        this.setSlot(22, this.loadButton(ModItems.ENRICHED_GLOWSTONE_FUEL_ROD, "Load enriched glowstone rods", ChatFormatting.GOLD));
        this.setSlot(24, new GuiElementBuilder(Items.STICK)
                .setName(Component.literal("Insert control rods").withStyle(ChatFormatting.WHITE))
                .setLore(lore(gray("Fills empty control rod channels from your inventory.")))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    int count = this.controller.loadControlRods(this.level, this.getPlayer());
                    this.tell("Inserted " + count + " control rod(s).", count > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY);
                    this.refresh();
                })
                .build());
        this.setSlot(25, new GuiElementBuilder(Items.SHEARS)
                .setName(Component.literal("Remove control rods").withStyle(ChatFormatting.WHITE))
                .setLore(lore(Component.literal("Careful: without control rods you cannot slow the reaction.").withStyle(ChatFormatting.RED)))
                .hideDefaultTooltip()
                .setCallback((_, _, _, _) -> {
                    this.click();
                    int count = this.controller.unloadControlRods(this.level, this.getPlayer());
                    this.tell("Removed " + count + " control rod(s).", ChatFormatting.GRAY);
                    this.refresh();
                })
                .build());
        this.setSlot(29, new GuiElementBuilder(Items.BONE)
                .setName(Component.literal("Unload depleted rods").withStyle(ChatFormatting.GRAY))
                .setLore(lore(gray("Moves spent rods to your inventory (core must be below 200 °C)."),
                        gray("Enrich depleted redstone and glowstone rods in an Enrichment Centrifuge."),
                        Component.literal("Spent fuel is radioactive!").withStyle(ChatFormatting.RED)))
                .setCallback((_, _, _, _) -> this.unload(true))
                .build());
        this.setSlot(30, new GuiElementBuilder(Items.HOPPER)
                .setName(Component.literal("Unload all fuel").withStyle(ChatFormatting.GRAY))
                .setLore(lore(gray("Core must be below 200 °C.")))
                .setCallback((_, _, _, _) -> this.unload(false))
                .build());
        this.setSlot(40, this.shortcut(Page.CORE, Items.MAP, "See which rods are spent on the core map", ChatFormatting.YELLOW));
    }

    // =====================================================================================
    // Details
    // =====================================================================================

    private void drawDetails() {
        ReactorControllerBlockEntity c = this.controller;
        this.setSlot(10, new GuiElementBuilder(Items.IRON_BARS)
                .setName(Component.literal("Heat balance").withStyle(ChatFormatting.AQUA))
                .setLore(lore(gray("Thermal power: " + Fmt.power(c.thermalPower())),
                        gray("Removed by coolant: " + Fmt.power(c.heatRemoved())),
                        gray("Lost through casing: " + Fmt.power(c.heatLoss())),
                        gray("Steady state: heat removed = thermal power.")))
                .build());
        this.setSlot(12, new GuiElementBuilder(Items.BLAZE_POWDER)
                .setName(Component.literal("Neutronics").withStyle(ChatFormatting.GOLD))
                .setLore(lore(gray("Average multiplication k: " + Fmt.num(c.averageK(), 3)),
                        gray("Active fuel rods: " + c.activeRods()),
                        gray("Nominal power of the fuel: " + Fmt.power(c.nominalPower())),
                        gray("Fission power now: " + Fmt.pct(c.powerLevel()) + " of nominal"),
                        gray("Decay heat: " + Fmt.power(c.decayHeat()))))
                .build());
        if (NuclearConfig.get().xenonPoisoning) this.setSlot(14, this.xenonItem());
        this.setSlot(16, this.structureItem());
        this.setSlot(30, new GuiElementBuilder(Items.SPYGLASS)
                .setName(Component.literal("Re-scan structure").withStyle(ChatFormatting.AQUA))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.controller.scan(this.level, this.controller.getBlockPos());
                    this.tell(this.controller.status().getString(), this.controller.isFormed() ? ChatFormatting.GREEN : ChatFormatting.RED);
                    this.refresh();
                })
                .build());
        this.setSlot(32, new GuiElementBuilder(Items.BOOK)
                .setName(Component.literal("How to run a reactor").withStyle(ChatFormatting.GOLD))
                .setLore(lore(gray("1. Feed water into an INPUT port (water pump + pipes)."),
                        gray("2. Take steam (or coolant) from an OUTPUT port."),
                        gray("3. Load fuel and control rods, then press START."),
                        gray("4. Pull the control rods out slowly and watch the temperature,"),
                        gray("   or pick an automatic rod control mode."),
                        gray("5. Steady state: heat removed = thermal power."),
                        gray("Fuel close together boosts each other. Control rods"),
                        gray("absorb neutrons from fuel up to 2 blocks away."),
                        gray("After a shutdown, xenon builds up for a few minutes and"),
                        gray("can hold the power down until it fades.")))
                .build());
    }

    // =====================================================================================
    // Shared buttons
    // =====================================================================================

    private static double averageFuel(ReactorControllerBlockEntity c) {
        double[] left = c.rodFuelLeft();
        double sum = 0.0;
        int count = 0;
        for (double value : left) {
            if (value > 0.0) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    private GuiElement insertionButton(int delta) {
        String text = (delta > 0 ? "Insert rods +" : "Withdraw rods ") + delta + "%";
        return new GuiElementBuilder(delta > 0 ? Items.IRON_INGOT : Items.GOLD_NUGGET)
                .setCount(Math.abs(delta))
                .setName(Component.literal(text).withStyle(delta > 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW))
                .setLore(lore(gray(delta > 0 ? "More insertion = less power." : "Less insertion = more power.")))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    if (this.controller.controlMode() != ReactorControllerBlockEntity.ControlMode.MANUAL) {
                        this.controller.setControlMode(ReactorControllerBlockEntity.ControlMode.MANUAL);
                        this.tell("Switched rod control to manual.", ChatFormatting.YELLOW);
                    }
                    this.controller.setInsertion(this.controller.insertion() + delta);
                    this.refresh();
                })
                .build();
    }

    private GuiElement targetButton(int delta) {
        boolean temperature = this.controller.controlMode() == ReactorControllerBlockEntity.ControlMode.TEMPERATURE;
        String text = (delta > 0 ? "+" : "") + delta + (temperature ? " °C" : "%");
        return new GuiElementBuilder(delta > 0 ? Items.DYE.red() : Items.DYE.blue())
                .setCount(Math.min(64, Math.abs(delta)))
                .setName(Component.literal("Target " + text).withStyle(delta > 0 ? ChatFormatting.RED : ChatFormatting.AQUA))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    if (temperature) {
                        this.controller.setTargetTemp(this.controller.targetTemp() + delta);
                    } else {
                        this.controller.setTargetPower(this.controller.targetPower() + delta);
                    }
                    this.refresh();
                })
                .build();
    }

    private GuiElement loadButton(Item rod, String name, ChatFormatting color) {
        int tier = FuelRods.tierOf(new ItemStack(rod));
        return new GuiElementBuilder(rod)
                .setName(Component.literal(name).withStyle(color))
                .setLore(lore(gray("Fills empty fuel channels from your inventory."),
                        gray("Base power " + Fmt.power(FuelRods.basePower(tier)) + ", lasts about "
                                + Fmt.num(FuelRods.lifetimeTicks(tier) / 1200.0, 0) + " min at multiplication 1.")))
                .hideDefaultTooltip()
                .setCallback((_, _, _, _) -> {
                    this.click();
                    int count = this.controller.loadFuel(this.level, this.getPlayer(), rod);
                    this.tell("Loaded " + count + " fuel rod(s).", count > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY);
                    this.refresh();
                })
                .build();
    }

    private void unload(boolean depletedOnly) {
        this.click();
        int count = this.controller.unloadFuel(this.level, this.getPlayer(), depletedOnly);
        if (count < 0) {
            this.tell("The core is too hot to open (must be below 200 °C).", ChatFormatting.RED);
        } else {
            this.tell("Unloaded " + count + " fuel rod(s).", ChatFormatting.GRAY);
        }
        this.refresh();
    }
}

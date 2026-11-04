package com.sumutiu.goingnuclear.gui;

import com.sumutiu.goingnuclear.machine.TemperatureSensorBlockEntity;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.Sounds;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Configure a temperature sensor: threshold mode (on/off) or analog mode (0..15 over a range).
 */
public class TemperatureSensorGui extends SimpleGui {
    private final TemperatureSensorBlockEntity sensor;
    private int ticks;

    public TemperatureSensorGui(ServerPlayer player, TemperatureSensorBlockEntity sensor) {
        super(MenuType.GENERIC_9x1, player, false);
        this.sensor = sensor;
        this.setTitle(Component.literal("Temperature Sensor"));
        this.refresh();
    }

    @Override
    public void onTick() {
        super.onTick();
        if (this.sensor.isRemoved()) {
            this.close();
            return;
        }
        if (++this.ticks % 10 == 0) this.refresh();
    }

    private void click() {
        Sounds.toPlayer(this.getPlayer(), SoundEvents.UI_BUTTON_CLICK.value(), 0.4f, 1.0f);
    }

    private void refresh() {
        TemperatureSensorBlockEntity s = this.sensor;
        boolean threshold = s.thresholdMode();
        this.setSlot(0, new GuiElementBuilder(threshold ? Items.REDSTONE_TORCH : Items.COMPARATOR)
                .setName(Component.literal(threshold ? "Mode: threshold" : "Mode: analog").withStyle(ChatFormatting.AQUA))
                .setLore(List.of(
                        Component.literal(threshold ? "Signal 15 at or above the threshold, 0 below." : "Signal grows from 0 to 15 across the range.").withStyle(ChatFormatting.GRAY),
                        Component.literal("Click to switch.").withStyle(ChatFormatting.YELLOW)))
                .setCallback((_, _, _, _) -> {
                    this.click();
                    this.sensor.setThresholdMode(!this.sensor.thresholdMode());
                    this.refresh();
                })
                .build());
        this.setSlot(2, this.adjust(-100, threshold));
        this.setSlot(3, this.adjust(-10, threshold));
        double reading = s.lastTemperature();
        String setting = threshold ? "Threshold: " + s.threshold() + " °C" : "Range: " + s.rangeMin() + " - " + s.rangeMax() + " °C";
        this.setSlot(4, new GuiElementBuilder(Items.CLOCK)
                .setName(Component.literal(setting).withStyle(ChatFormatting.WHITE))
                .setLore(List.of(
                        Component.literal("Reading: " + (Double.isNaN(reading) ? "nothing adjacent" : Fmt.temp(reading)))
                                .withStyle(Double.isNaN(reading) ? ChatFormatting.RED : Fmt.tempColor(reading)),
                        Component.literal(threshold ? "Left/right buttons change the threshold." : "Left click: change minimum. Right click: change maximum.")
                                .withStyle(ChatFormatting.GRAY)))
                .build());
        this.setSlot(5, this.adjust(10, threshold));
        this.setSlot(6, this.adjust(100, threshold));
    }

    private eu.pb4.sgui.api.elements.GuiElement adjust(int delta, boolean threshold) {
        return new GuiElementBuilder(delta > 0 ? Items.DYE.red() : Items.DYE.blue())
                .setCount(Math.min(64, Math.abs(delta) / 10))
                .setName(Component.literal((delta > 0 ? "+" : "") + delta + " °C").withStyle(delta > 0 ? ChatFormatting.RED : ChatFormatting.AQUA))
                .setCallback((_, type, _, _) -> {
                    this.click();
                    if (threshold) {
                        this.sensor.setThreshold(this.sensor.threshold() + delta);
                    } else if (type.isRight) {
                        this.sensor.setRange(this.sensor.rangeMin(), this.sensor.rangeMax() + delta);
                    } else {
                        this.sensor.setRange(this.sensor.rangeMin() + delta, this.sensor.rangeMax());
                    }
                    this.refresh();
                })
                .build();
    }
}

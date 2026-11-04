package com.sumutiu.goingnuclear.fluid;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

public enum FluidType {
    WATER("water", "Water", ChatFormatting.BLUE, 20.0),
    STEAM("steam", "Steam", ChatFormatting.WHITE, 120.0),
    /**
     * Pressurized water of the primary loop. It carries its temperature with it (see {@link FluidTank} and
     * {@link PipeNetwork}): the reactor heats it, the heat exchanger cools it while making steam.
     */
    COOLANT("coolant", "Coolant", ChatFormatting.AQUA, 20.0);

    private final String id;
    private final String displayName;
    private final ChatFormatting color;
    private final double defaultTemperature;

    FluidType(String id, String displayName, ChatFormatting color, double defaultTemperature) {
        this.id = id;
        this.displayName = displayName;
        this.color = color;
        this.defaultTemperature = defaultTemperature;
    }

    public String id() {
        return this.id;
    }

    public Component displayName() {
        return Component.literal(this.displayName).withStyle(this.color);
    }

    /**
     * Temperature (°C) of this fluid when nothing says otherwise (fresh water, for example).
     */
    public double defaultTemperature() {
        return this.defaultTemperature;
    }

    public static @Nullable FluidType byId(String id) {
        for (FluidType type : values()) {
            if (type.id.equals(id)) return type;
        }
        return null;
    }
}

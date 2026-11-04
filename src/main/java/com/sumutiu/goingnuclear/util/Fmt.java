package com.sumutiu.goingnuclear.util;

import com.sumutiu.goingnuclear.fluid.Steam;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/**
 * Number and text formatting helpers.
 */
public final class Fmt {
    private Fmt() {
    }

    /**
     * Power from J/t to a W / kW / MW string.
     */
    public static String power(double joulesPerTick) {
        double watts = joulesPerTick * 20.0;
        if (Math.abs(watts) >= 1.0e6) return String.format(Locale.ROOT, "%.2f MW", watts / 1.0e6);
        if (Math.abs(watts) >= 1.0e3) return String.format(Locale.ROOT, "%.1f kW", watts / 1.0e3);
        return String.format(Locale.ROOT, "%.0f W", watts);
    }

    public static String energy(double joules) {
        if (Math.abs(joules) >= 1.0e9) return String.format(Locale.ROOT, "%.2f GJ", joules / 1.0e9);
        if (Math.abs(joules) >= 1.0e6) return String.format(Locale.ROOT, "%.2f MJ", joules / 1.0e6);
        if (Math.abs(joules) >= 1.0e3) return String.format(Locale.ROOT, "%.1f kJ", joules / 1.0e3);
        return String.format(Locale.ROOT, "%.0f J", joules);
    }

    public static String mb(double amount) {
        if (amount >= 10000.0) return String.format(Locale.ROOT, "%.1f B", amount / 1000.0);
        return String.format(Locale.ROOT, "%.0f mB", amount);
    }

    public static String rate(double mbPerTick) {
        return String.format(Locale.ROOT, "%.1f mB/t", mbPerTick);
    }

    public static String temp(double celsius) {
        return String.format(Locale.ROOT, "%.0f °C", celsius);
    }

    /**
     * An absolute pressure (measured from a perfect vacuum, as in steam tables) with the gauge pressure a
     * normal gauge would show next to it (relative to the air around it), e.g. "0.05 bar abs (-0.96 bar gauge)".
     * Two decimals below 10 bar, so a condenser's vacuum shows.
     */
    public static String bar(double absolute) {
        return number(absolute) + " bar abs (" + number(absolute - Steam.ATMOSPHERIC) + " bar gauge)";
    }

    private static String number(double bar) {
        return String.format(Locale.ROOT, Math.abs(bar) < 10.0 ? "%.2f" : "%.0f", bar);
    }

    /**
     * Steam temperature with its pressure, e.g. "280 °C, 64 bar" ("supercritical" above 374 °C).
     */
    public static String steam(double celsius) {
        if (celsius > Steam.CRITICAL_TEMP) return temp(celsius) + ", supercritical";
        return temp(celsius) + ", " + bar(Steam.pressure(celsius));
    }

    public static String pct(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100.0);
    }

    public static String num(double value, int decimals) {
        String format = switch (decimals) {
            case 0 -> "%.0f";
            case 1 -> "%.1f";
            case 2 -> "%.2f";
            case 3 -> "%.3f";
            default -> throw new IllegalArgumentException("Up to 3 decimals, not " + decimals);
        };
        return String.format(Locale.ROOT, format, value);
    }

    public static ChatFormatting tempColor(double celsius) {
        if (celsius >= 1000.0) return ChatFormatting.DARK_RED;
        if (celsius >= 700.0) return ChatFormatting.RED;
        if (celsius >= 350.0) return ChatFormatting.GOLD;
        if (celsius >= 100.0) return ChatFormatting.YELLOW;
        return ChatFormatting.AQUA;
    }

    public static MutableComponent label(String label, String value, ChatFormatting valueColor) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(valueColor));
    }

    public static MutableComponent label(String label, Component value) {
        return Component.literal(label + ": ").withStyle(ChatFormatting.GRAY).append(value);
    }

    public static MutableComponent title(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }
}

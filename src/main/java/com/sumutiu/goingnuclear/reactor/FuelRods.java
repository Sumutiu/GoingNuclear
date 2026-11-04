package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Fuel rod helpers. Burn-up (0 = fresh, 1 = spent) is stored in the rod's custom data, so fresh rods have
 * none and stack like any other item.
 * <p>
 * The fuel cycle: a redstone or glowstone rod burns down into a depleted rod of its kind. The Enrichment
 * Centrifuge turns that, with fresh dust, into an enriched rod (more power, the same energy as a fresh rod),
 * and an enriched rod burns down into a plain {@link #DEPLETED} rod, which cannot be enriched again.
 */
public final class FuelRods {
    public static final int EMPTY = 0;
    public static final int REDSTONE = 1;
    public static final int GLOWSTONE = 2;
    /**
     * Spent enriched fuel: the end of the cycle (smelt it for iron).
     */
    public static final int DEPLETED = 3;
    public static final int DEPLETED_REDSTONE = 4;
    public static final int DEPLETED_GLOWSTONE = 5;
    public static final int ENRICHED_REDSTONE = 6;
    public static final int ENRICHED_GLOWSTONE = 7;
    private static final String BURNUP = "goingnuclear_burnup";

    private FuelRods() {
    }

    public static int tierOf(ItemStack stack) {
        if (stack.isEmpty()) return EMPTY;
        if (stack.is(ModItems.REDSTONE_FUEL_ROD)) return REDSTONE;
        if (stack.is(ModItems.GLOWSTONE_FUEL_ROD)) return GLOWSTONE;
        if (stack.is(ModItems.DEPLETED_FUEL_ROD)) return DEPLETED;
        if (stack.is(ModItems.DEPLETED_REDSTONE_FUEL_ROD)) return DEPLETED_REDSTONE;
        if (stack.is(ModItems.DEPLETED_GLOWSTONE_FUEL_ROD)) return DEPLETED_GLOWSTONE;
        if (stack.is(ModItems.ENRICHED_REDSTONE_FUEL_ROD)) return ENRICHED_REDSTONE;
        if (stack.is(ModItems.ENRICHED_GLOWSTONE_FUEL_ROD)) return ENRICHED_GLOWSTONE;
        return EMPTY;
    }

    /**
     * Whether rods of this tier still have fuel in them (and so take part in fission).
     */
    public static boolean isActive(int tier) {
        return tier == REDSTONE || tier == GLOWSTONE || tier == ENRICHED_REDSTONE || tier == ENRICHED_GLOWSTONE;
    }

    /**
     * Whether this tier is a burnt-out rod of any kind.
     */
    public static boolean isSpent(int tier) {
        return tier == DEPLETED || tier == DEPLETED_REDSTONE || tier == DEPLETED_GLOWSTONE;
    }

    public static boolean isEnriched(int tier) {
        return tier == ENRICHED_REDSTONE || tier == ENRICHED_GLOWSTONE;
    }

    /**
     * Whether this is glowstone fuel (fresh or enriched), as opposed to redstone.
     */
    private static boolean isGlowstone(int tier) {
        return tier == GLOWSTONE || tier == ENRICHED_GLOWSTONE;
    }

    /**
     * Total energy a fresh rod of this tier gives before it is spent (J). Enriched rods have the same as a fresh
     * rod of their kind (times {@code enrichedEnergyFactor}).
     */
    public static double energy(int tier) {
        NuclearConfig config = NuclearConfig.get();
        double energy = isGlowstone(tier) ? config.glowstoneRodEnergy : config.redstoneRodEnergy;
        return isEnriched(tier) ? energy * config.enrichedEnergyFactor : energy;
    }

    /**
     * Thermal power at multiplication 1 (J/t).
     */
    public static double basePower(int tier) {
        NuclearConfig config = NuclearConfig.get();
        return switch (tier) {
            case ENRICHED_REDSTONE -> config.enrichedRedstoneRodPower;
            case ENRICHED_GLOWSTONE -> config.enrichedGlowstoneRodPower;
            case GLOWSTONE -> config.glowstoneRodPower;
            default -> config.redstoneRodPower;
        };
    }

    /**
     * How strongly a rod drives the fission in the rods around it (0 for anything without fuel).
     */
    public static double neutronFactor(int tier) {
        if (!isActive(tier)) return 0.0;
        NuclearConfig config = NuclearConfig.get();
        return isGlowstone(tier) ? config.glowstoneNeutronFactor : config.redstoneNeutronFactor;
    }

    /**
     * Lifetime of a rod at multiplication 1, in ticks.
     */
    public static double lifetimeTicks(int tier) {
        double power = basePower(tier);
        return power > 0.0 ? energy(tier) / power : 0.0;
    }

    /**
     * What a rod of this tier turns into once it is spent: a depleted rod of its kind, or the plain depleted rod
     * for enriched fuel.
     */
    public static Item depletedItem(int tier) {
        return switch (tier) {
            case REDSTONE -> ModItems.DEPLETED_REDSTONE_FUEL_ROD;
            case GLOWSTONE -> ModItems.DEPLETED_GLOWSTONE_FUEL_ROD;
            default -> ModItems.DEPLETED_FUEL_ROD;
        };
    }

    /**
     * Short name for the reactor screens ("redstone rod", "enriched glowstone rod", ...).
     */
    public static String name(int tier) {
        return switch (tier) {
            case REDSTONE -> "redstone rod";
            case GLOWSTONE -> "glowstone rod";
            case ENRICHED_REDSTONE -> "enriched redstone rod";
            case ENRICHED_GLOWSTONE -> "enriched glowstone rod";
            case DEPLETED_REDSTONE -> "depleted redstone rod";
            case DEPLETED_GLOWSTONE -> "depleted glowstone rod";
            case DEPLETED -> "depleted rod";
            default -> "empty";
        };
    }

    /**
     * How far the rod is burnt up: 0 = fresh, 1 = spent.
     */
    public static double burnup(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null ? clamp(data.copyTag().getDoubleOr(BURNUP, 0.0)) : 0.0;
    }

    public static double usedEnergy(ItemStack stack) {
        int tier = tierOf(stack);
        if (!isActive(tier)) return 0.0;
        return burnup(stack) * energy(tier);
    }

    public static double remainingFraction(ItemStack stack) {
        int tier = tierOf(stack);
        if (!isActive(tier)) return 0.0;
        return 1.0 - burnup(stack);
    }

    public static void setUsedEnergy(ItemStack stack, double used) {
        int tier = tierOf(stack);
        if (!isActive(tier)) return;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        CompoundTag tag = data != null ? data.copyTag() : new CompoundTag();
        double fraction = clamp(used / energy(tier));
        if (fraction <= 0.0) {
            tag.remove(BURNUP);
        } else {
            tag.putDouble(BURNUP, fraction);
        }
        if (tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    private static double clamp(double value) {
        return Math.clamp(value, 0.0, 1.0);
    }
}

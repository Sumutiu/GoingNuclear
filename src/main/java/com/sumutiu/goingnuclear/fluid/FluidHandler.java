package com.sumutiu.goingnuclear.fluid;

import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * Anything that can exchange fluid through one of its faces.
 * {@code side} is always the face of this handler that is being accessed.
 */
public interface FluidHandler {
    /**
     * @return the amount accepted
     */
    double insert(Direction side, FluidType type, double amount, boolean simulate);

    /**
     * Like {@link #insert(Direction, FluidType, double, boolean)}, for fluid at {@code temperature} (°C).
     * Handlers that keep track of temperature (anything that holds coolant) override this.
     */
    default double insert(Direction side, FluidType type, double amount, double temperature, boolean simulate) {
        return this.insert(side, type, amount, simulate);
    }

    /**
     * Temperature (°C) of the fluid that would come out of this side.
     */
    default double temperature(Direction side, FluidType type) {
        return type.defaultTemperature();
    }

    /**
     * @param pumped true when the caller is a circulation pump. Pump-only outlets (the heat exchanger's
     *               coolant outlets) only give fluid to pumps.
     * @return the amount extracted
     */
    double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate);

    /**
     * Fluid that could be extracted from this side right now, or null.
     */
    @Nullable FluidType available(Direction side, boolean pumped);

    /**
     * Fluid this side gives out, even while it is empty right now (null if it gives nothing). Lets a pump keep
     * its even share of an outlet that the pumps drain completely every tick.
     */
    default @Nullable FluidType offers(Direction side, boolean pumped) {
        return this.available(side, pumped);
    }

    /**
     * How full the storage behind this side is (0..1), or -1 when it has no single level to report.
     * Used by check valves, which only top up the far side while it is low.
     */
    default double fillLevel(Direction side) {
        return -1.0;
    }
}

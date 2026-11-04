package com.sumutiu.goingnuclear.fluid;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A single-fluid buffer used inside machines. It also keeps the temperature of what it holds: fluid poured in
 * mixes with what is there, taking fluid out does not change it.
 */
public final class FluidTank {
    private final FluidType fixedType;
    private double capacity;
    private double amount;
    private double temperature;

    public FluidTank(FluidType type, double capacity) {
        this.fixedType = type;
        this.capacity = capacity;
        this.temperature = type.defaultTemperature();
    }

    public FluidType type() {
        return this.fixedType;
    }

    public double amount() {
        return this.amount;
    }

    public double capacity() {
        return this.capacity;
    }

    public void setCapacity(double capacity) {
        this.capacity = Math.max(0.0, capacity);
    }

    public double space() {
        return Math.max(0.0, this.capacity - this.amount);
    }

    public double fill() {
        return this.capacity <= 0.0 ? 0.0 : this.amount / this.capacity;
    }

    public void set(double amount) {
        this.amount = Math.max(0.0, amount);
    }

    /**
     * Temperature (°C) of the fluid in the tank.
     */
    public double temperature() {
        return this.temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    /**
     * Pours fluid in at the fluid's default temperature.
     */
    public double insert(@Nullable FluidType type, double amount, boolean simulate) {
        return this.insert(type, amount, type != null ? type.defaultTemperature() : 20.0, simulate);
    }

    /**
     * Pours fluid in at {@code temperature}; it mixes with what is in the tank.
     */
    public double insert(@Nullable FluidType type, double amount, double temperature, boolean simulate) {
        if (type != this.fixedType || amount <= 0.0) return 0.0;
        double accepted = Math.min(amount, this.space());
        if (!simulate && accepted > 0.0) {
            this.temperature = mix(this.amount, this.temperature, accepted, temperature);
            this.amount += accepted;
        }
        return accepted;
    }

    public double extract(@Nullable FluidType type, double max, boolean simulate) {
        if (type != this.fixedType || max <= 0.0) return 0.0;
        double taken = Math.min(max, this.amount);
        if (!simulate) {
            this.amount -= taken;
            if (this.amount < 1.0e-6) this.amount = 0.0;
        }
        return taken;
    }

    /**
     * Temperature of {@code a} mB at {@code ta} mixed with {@code b} mB at {@code tb}.
     */
    public static double mix(double a, double ta, double b, double tb) {
        double total = a + b;
        return total > 1.0e-9 ? (a * ta + b * tb) / total : tb;
    }

    public void save(ValueOutput output, String key) {
        output.putDouble(key, this.amount);
        output.putDouble(key + "_temp", this.temperature);
    }

    public void load(ValueInput input, String key) {
        this.amount = Math.max(0.0, input.getDoubleOr(key, 0.0));
        this.temperature = input.getDoubleOr(key + "_temp", this.fixedType.defaultTemperature());
    }
}

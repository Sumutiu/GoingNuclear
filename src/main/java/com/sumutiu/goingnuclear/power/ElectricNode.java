package com.sumutiu.goingnuclear.power;

import net.minecraft.core.Direction;

/**
 * Block entity that takes part in an electric network.
 * All values are energy per tick (J/t). 1 J/t = 20 W.
 */
public interface ElectricNode {
    /**
     * Energy this node wants to consume this tick.
     */
    default double powerDemand() {
        return 0.0;
    }

    /**
     * Called once per tick with the fraction (0..1) of the demand that the grid could supply.
     */
    default void receivePower(double fraction) {
    }

    /**
     * Energy this node produced since the last grid tick. The grid collects it and calls {@link #clearOutput()}.
     */
    default double powerOutput() {
        return 0.0;
    }

    default void clearOutput() {
    }

    /**
     * Whether this producer follows the load (steam turbines): instead of making all it can, it makes what the
     * grid asks of it through {@link #setLoadTarget}.
     */
    default boolean followsLoad() {
        return false;
    }

    /**
     * The most a load-following producer could make in one tick right now.
     */
    default double loadCapacity() {
        return 0.0;
    }

    /**
     * How much energy the grid wants this load-following producer to make next tick.
     */
    default void setLoadTarget(double energy) {
    }

    default boolean isStorage() {
        return false;
    }

    /**
     * @return energy accepted
     */
    default double charge(double max) {
        return 0.0;
    }

    /**
     * @return energy released
     */
    default double discharge(double max) {
        return 0.0;
    }

    default double maxChargeRate() {
        return 0.0;
    }

    default double stored() {
        return 0.0;
    }

    default double capacity() {
        return 0.0;
    }

    /**
     * Moves energy into ({@code amount} > 0) or out of ({@code amount} < 0) a storage to even it out with the
     * other batteries of its bank (see PowerNetwork). Counts against the same rate limits as charging and
     * discharging.
     */
    default void shareWithBank(double amount) {
        if (amount >= 0.0) {
            this.charge(amount);
        } else {
            this.discharge(-amount);
        }
    }

    /**
     * Energy a storage could still take this tick (its rate limit and the room left).
     */
    default double chargeRoom() {
        return 0.0;
    }

    /**
     * Energy a storage could still give this tick (its rate limit and what is left in it).
     */
    default double dischargeAvailable() {
        return 0.0;
    }

    // ---------- Terminals ----------
    // Most nodes have one terminal: every face and every overhead wire is the same connection, and power
    // passes through the block. A node with several terminals keeps them apart, so it can sit between two
    // networks (a battery takes power in through its back and gives it out through its other faces).

    default int terminals() {
        return 1;
    }

    /**
     * Terminal ({@code 0 .. terminals() - 1}) that face {@code face} belongs to, or -1 when nothing connects
     * electrically on that face. Demand and output are counted on terminal 0.
     */
    default int terminalAt(Direction face) {
        return 0;
    }

    /**
     * Terminal that overhead wires tied to this node belong to.
     */
    default int wireTerminal() {
        return 0;
    }

    /**
     * Whether a storage can be charged through this terminal.
     */
    default boolean chargesThrough(int terminal) {
        return true;
    }

    /**
     * Whether a storage can give energy out through this terminal.
     */
    default boolean dischargesThrough(int terminal) {
        return true;
    }
}

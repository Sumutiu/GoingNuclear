package com.sumutiu.goingnuclear.fluid;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Splits something several machines draw on in the same tick (the fluid in a pipe or machine outlet, or the
 * room left in it) into equal parts, so the machine that happens to tick first cannot take it all.
 * <p>
 * The amount is measured once per tick, when the first machine asks. Each machine then gets an equal part of
 * it, minus what it already took this tick. Machines are counted by position, and the count from the tick
 * before is used as well, so machines that tick later in the tick still find their part waiting. Without a
 * count from the tick before (new pipe, or nothing used it for a while) a cautious guess of how many machines
 * could be sharing is used. Whatever a machine leaves stays for the next tick.
 */
public final class FairShare {
    private long tick = Long.MIN_VALUE;
    private double pool;
    private int sharersBefore = 1;
    private final Map<BlockPos, Double> used = new HashMap<>();

    /**
     * How much the machine at {@code who} may still take (or put) this tick.
     *
     * @param expected how many machines could be sharing, used while there is no count from the tick before
     * @param poolNow  measures the amount to split; only called on the first request of a tick
     */
    public double allowance(long gameTime, BlockPos who, int expected, DoubleSupplier poolNow) {
        if (gameTime != this.tick) {
            boolean counted = gameTime == this.tick + 1L && !this.used.isEmpty();
            this.sharersBefore = counted ? this.used.size() : Math.max(1, expected);
            this.used.clear();
            this.tick = gameTime;
            this.pool = Math.max(0.0, poolNow.getAsDouble());
        }
        Double already = this.used.get(who);
        if (already == null) {
            already = 0.0;
            this.used.put(who.immutable(), already);
        }
        int sharers = Math.max(this.sharersBefore, this.used.size());
        return Math.max(0.0, this.pool / sharers - already);
    }

    /**
     * Records what the machine at {@code who} took (or put) after asking for its {@link #allowance}.
     */
    public void used(BlockPos who, double amount) {
        if (amount > 0.0) this.used.merge(who.immutable(), amount, Double::sum);
    }

    /**
     * Game time of the last request, so shares nobody uses any more can be forgotten.
     */
    public long lastTick() {
        return this.tick;
    }
}

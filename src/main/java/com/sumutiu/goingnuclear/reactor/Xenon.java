package com.sumutiu.goingnuclear.reactor;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Xenon poisoning of a reactor core, as one pair of numbers for the whole core.
 * <p>
 * Fission makes iodine-135, which decays into xenon-135 (a little xenon is also made directly). Xenon soaks up
 * neutrons, which costs reactivity, and it goes away two ways: it decays, and neutrons burn it off, faster the
 * higher the power. So at steady power it settles at a level that depends on the power, and after a shutdown
 * or a power cut it first <em>rises</em> (the iodine already made keeps turning into xenon, but nothing burns
 * it off any more), peaks, and only then fades: the "iodine pit" that can keep a reactor from coming back to
 * full power for a while. When the reactor runs again, the xenon burns off and the power climbs by itself.
 * <p>
 * Real half-lives are hours; here {@code xenonTimeScale} game minutes stand for one real hour (6.6 and 9.1
 * minutes by default). Both amounts are scaled so that xenon is 1.0 at the equilibrium for full nominal power.
 */
public final class Xenon {
    private static final double IODINE_HALF_LIFE_HOURS = 6.6;
    private static final double XENON_HALF_LIFE_HOURS = 9.1;
    /**
     * Share of the xenon that fission makes directly (the rest comes through iodine).
     */
    private static final double DIRECT_YIELD = 0.05;
    private static final double TICKS_PER_GAME_MINUTE = 1200.0;
    /**
     * Below this change per minute (of the full-power level) the xenon counts as steady.
     */
    private static final double STEADY = 0.003;
    /**
     * Within this of the level the power leaves it at, the xenon counts as settled.
     */
    private static final double SETTLED = 0.03;

    private double iodine;
    private double xenon;

    /**
     * Xenon, as a multiple of the full-power equilibrium.
     */
    public double level() {
        return this.xenon;
    }

    /**
     * Advances one tick at fission power {@code power} (a share of the fuel's nominal power).
     */
    public void tick(double power) {
        NuclearConfig config = NuclearConfig.get();
        if (!config.xenonPoisoning) {
            this.iodine = 0.0;
            this.xenon = 0.0;
            return;
        }
        Rates rates = Rates.of(config);
        double[] next = step(rates, this.iodine, this.xenon, Math.max(0.0, power), 1.0);
        this.iodine = next[0];
        this.xenon = next[1];
    }

    /**
     * Clears the core of iodine and xenon (after a meltdown).
     */
    public void reset() {
        this.iodine = 0.0;
        this.xenon = 0.0;
    }

    /**
     * What the multiplication of every fuel rod is multiplied by (1 = no poisoning).
     */
    public double reactivityFactor() {
        NuclearConfig config = NuclearConfig.get();
        if (!config.xenonPoisoning) return 1.0;
        return Math.max(0.0, 1.0 - config.xenonWorth * this.xenon);
    }

    /**
     * What the fission power is multiplied by at the same rod position (power goes with the square of the
     * multiplication).
     */
    public double powerFactor() {
        double factor = this.reactivityFactor();
        return factor * factor;
    }

    /**
     * The xenon level the core settles at when it runs steadily at {@code power} (1.0 at full nominal power).
     */
    public static double equilibrium(double power) {
        NuclearConfig config = NuclearConfig.get();
        if (!config.xenonPoisoning || power <= 0.0) return 0.0;
        Rates rates = Rates.of(config);
        return rates.source * power / (rates.xenonDecay + rates.burnout * power);
    }

    /**
     * How fast the xenon changes right now at {@code power}, in multiples of the full-power level per game
     * minute.
     */
    public double changePerMinute(double power) {
        NuclearConfig config = NuclearConfig.get();
        if (!config.xenonPoisoning) return 0.0;
        Rates rates = Rates.of(config);
        return derivative(rates, this.iodine, this.xenon, Math.max(0.0, power))[1] * TICKS_PER_GAME_MINUTE;
    }

    /**
     * Where the xenon goes if the power stays at {@code power}: it runs the next few hours of game time
     * ahead until the xenon turns around, settles, or (shut down) has nearly cleared.
     */
    public Forecast forecast(double power) {
        NuclearConfig config = NuclearConfig.get();
        double p = Math.max(0.0, power);
        double now = this.changePerMinute(p);
        double settled = equilibrium(p);
        if (!config.xenonPoisoning || Math.abs(now) < STEADY && Math.abs(this.xenon - settled) < SETTLED) {
            return new Forecast(Trend.STEADY, this.xenon, 0.0);
        }
        Rates rates = Rates.of(config);
        boolean rising = now > 0.0;
        double iodine = this.iodine;
        double xenon = this.xenon;
        double dt = 20.0;
        double maxTicks = 240.0 * TICKS_PER_GAME_MINUTE;
        for (double t = dt; t <= maxTicks; t += dt) {
            double[] next = step(rates, iodine, xenon, p, dt);
            iodine = next[0];
            xenon = next[1];
            double change = derivative(rates, iodine, xenon, p)[1] * TICKS_PER_GAME_MINUTE;
            double minutes = t / TICKS_PER_GAME_MINUTE;
            if (!rising && p <= 1.0e-3 && xenon <= 0.05) return new Forecast(Trend.CLEARS, xenon, minutes);
            // Close to where this power leaves it: it settles there. Anywhere else it is only turning around.
            if (Math.abs(change) < STEADY && Math.abs(xenon - settled) < SETTLED) {
                return new Forecast(rising ? Trend.SETTLES_UP : Trend.SETTLES_DOWN, xenon, minutes);
            }
            if (rising && change <= 0.0) return new Forecast(Trend.PEAKS, xenon, minutes);
            if (!rising && change >= 0.0) return new Forecast(Trend.BOTTOMS_OUT, xenon, minutes);
        }
        return new Forecast(rising ? Trend.SETTLES_UP : Trend.SETTLES_DOWN, xenon, maxTicks / TICKS_PER_GAME_MINUTE);
    }

    private static double[] derivative(Rates rates, double iodine, double xenon, double power) {
        double made = rates.source * power;
        double iodineChange = (1.0 - DIRECT_YIELD) * made - rates.iodineDecay * iodine;
        double xenonChange = DIRECT_YIELD * made + rates.iodineDecay * iodine - rates.xenonDecay * xenon - rates.burnout * power * xenon;
        return new double[]{iodineChange, xenonChange};
    }

    private static double[] step(Rates rates, double iodine, double xenon, double power, double ticks) {
        double[] change = derivative(rates, iodine, xenon, power);
        return new double[]{Math.max(0.0, iodine + change[0] * ticks), Math.max(0.0, xenon + change[1] * ticks)};
    }

    public void save(ValueOutput output) {
        output.putDouble("iodine", this.iodine);
        output.putDouble("xenon", this.xenon);
    }

    public void load(ValueInput input) {
        this.iodine = Math.max(0.0, input.getDoubleOr("iodine", 0.0));
        this.xenon = Math.max(0.0, input.getDoubleOr("xenon", 0.0));
    }

    /**
     * Decay and burn-off rates per tick, and the production at full nominal power that makes the full-power
     * equilibrium xenon 1.0.
     */
    private record Rates(double iodineDecay, double xenonDecay, double burnout, double source) {
        static Rates of(NuclearConfig config) {
            double ticksPerHour = TICKS_PER_GAME_MINUTE * config.xenonTimeScale;
            double iodineDecay = Math.log(2.0) / (IODINE_HALF_LIFE_HOURS * ticksPerHour);
            double xenonDecay = Math.log(2.0) / (XENON_HALF_LIFE_HOURS * ticksPerHour);
            double burnout = config.xenonBurnout * xenonDecay;
            return new Rates(iodineDecay, xenonDecay, burnout, xenonDecay + burnout);
        }
    }

    public enum Trend {
        STEADY, PEAKS, BOTTOMS_OUT, SETTLES_UP, SETTLES_DOWN, CLEARS
    }

    /**
     * Where the xenon is heading: {@code level} after {@code minutes} of game time.
     */
    public record Forecast(Trend trend, double level, double minutes) {
    }
}

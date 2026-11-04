package com.sumutiu.goingnuclear.fluid;

import com.sumutiu.goingnuclear.config.NuclearConfig;

/**
 * Steam properties, kept simple: steam carries a temperature (like coolant), and its pressure is the
 * boiling (saturation) pressure of water at that temperature. So hot live steam from a heat exchanger is at
 * high pressure, and the cool exhaust steam behind a turbine is at a vacuum while a condenser keeps up with it.
 * <p>
 * A turbine gets more out of hotter steam ({@link #temperatureFactor}) and less when its exhaust cannot get
 * away ({@link #exhaustFactor}): from a condenser's vacuum (full power) up to the open air (half power).
 * <p>
 * All pressures here are absolute (from a perfect vacuum, as in steam tables); the gauge pressure a normal
 * gauge would show is that minus {@link #ATMOSPHERIC} (see Fmt.bar).
 */
public final class Steam {
    /**
     * Exhaust pressure (bar) while a condenser keeps up: a good vacuum.
     */
    public static final double VACUUM = 0.05;
    /**
     * Exhaust pressure (bar) of a turbine that blows its steam into the open air.
     */
    public static final double ATMOSPHERIC = 1.013;
    /**
     * Above this temperature (°C) water has no boiling point any more (the critical point).
     */
    public static final double CRITICAL_TEMP = 374.0;

    private static final double[] TEMPS = {0.0, 20.0, 33.0, 40.0, 60.0, 80.0, 100.0, 120.0, 150.0, 180.0, 200.0, 220.0, 240.0, 260.0, 270.0,
            280.0, 290.0, 300.0, 310.0, 320.0, 330.0, 340.0, 350.0, 360.0, 370.0, 374.0};
    private static final double[] BARS = {0.0061, 0.0234, 0.0503, 0.0738, 0.1994, 0.4739, 1.0142, 1.9867, 4.7616, 10.026, 15.549, 23.196, 33.469,
            46.923, 55.030, 64.166, 74.418, 85.879, 98.651, 112.84, 128.58, 146.01, 165.29, 186.66, 210.43, 220.64};

    private Steam() {
    }

    /**
     * Saturation pressure (bar) at {@code celsius} (from steam tables, interpolated).
     */
    public static double pressure(double celsius) {
        if (celsius <= TEMPS[0]) return BARS[0];
        for (int i = 1; i < TEMPS.length; i++) {
            if (celsius <= TEMPS[i]) {
                double t = (celsius - TEMPS[i - 1]) / (TEMPS[i] - TEMPS[i - 1]);
                return Math.exp(Math.log(BARS[i - 1]) + t * (Math.log(BARS[i]) - Math.log(BARS[i - 1])));
            }
        }
        return BARS[BARS.length - 1];
    }

    /**
     * Saturation temperature (°C) at {@code bar}: the temperature of steam at that pressure.
     */
    public static double temperature(double bar) {
        if (bar <= BARS[0]) return TEMPS[0];
        for (int i = 1; i < BARS.length; i++) {
            if (bar <= BARS[i]) {
                double t = (Math.log(bar) - Math.log(BARS[i - 1])) / (Math.log(BARS[i]) - Math.log(BARS[i - 1]));
                return TEMPS[i - 1] + t * (TEMPS[i] - TEMPS[i - 1]);
            }
        }
        return CRITICAL_TEMP;
    }

    /**
     * Share of a turbine's rated power per mB of live steam at {@code celsius}: 100% at {@code steamDesignTemp},
     * less with cooler steam (nothing at all from steam no hotter than the exhaust), up to {@code steamMaxBonus}.
     */
    public static double temperatureFactor(double celsius) {
        NuclearConfig config = NuclearConfig.get();
        double exhaust = temperature(VACUUM);
        double factor = (celsius - exhaust) / Math.max(1.0, config.steamDesignTemp - exhaust);
        return Math.clamp(factor, 0.0, config.steamMaxBonus);
    }

    /**
     * Share of a turbine's power per mB left with its exhaust at {@code bar}: all of it at a condenser's
     * vacuum, half of it when the exhaust goes into the open air.
     */
    public static double exhaustFactor(double bar) {
        double factor = 1.0 - 0.5 * (bar - VACUUM) / (ATMOSPHERIC - VACUUM);
        return Math.clamp(factor, 0.5, 1.0);
    }
}

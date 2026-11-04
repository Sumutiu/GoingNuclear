package com.sumutiu.goingnuclear.power;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import net.minecraft.world.phys.Vec3;

/**
 * Shape of a hanging wire, shared by strung wires and the wire a player is carrying.
 * The wire follows a parabola (very close to a real hanging cable) that dips {@link #sag} below the
 * straight line between its ends in the middle; a longer horizontal span hangs lower, like a lead.
 */
public final class WireShape {
    private WireShape() {
    }

    public static double sag(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        NuclearConfig cfg = NuclearConfig.get();
        return cfg.wireSagBase + cfg.wireSagPerBlock * horizontal;
    }

    /**
     * Point at {@code t} (0 = start, 1 = end) along the hanging wire.
     */
    public static Vec3 point(Vec3 from, Vec3 to, double sag, double t) {
        double x = from.x + (to.x - from.x) * t;
        double y = from.y + (to.y - from.y) * t - sag * 4.0 * t * (1.0 - t);
        double z = from.z + (to.z - from.z) * t;
        return new Vec3(x, y, z);
    }

    /**
     * Number of straight pieces used to draw a wire of this length (about two per block).
     */
    public static int segments(double length) {
        return Math.clamp((int) Math.ceil(length * 1.8), 6, 40);
    }
}

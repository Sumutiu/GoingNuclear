package com.sumutiu.goingnuclear.power;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * Marker for blocks that ground cables connect to (machines, poles, generators, batteries...). Power passes
 * through such a block between all of its electric faces, so machines touching each other (a row of turbines
 * or circulation pumps) share one connection.
 */
public interface ElectricConnectable {
    /**
     * Side of an upright (pointing up or down) water pump that its power-in port is on. Ignored while the pump
     * lies on its side, where the port is on top.
     */
    EnumProperty<Direction> POWER_PORT = EnumProperty.create("port", Direction.class, Direction.Plane.HORIZONTAL);

    /**
     * Whether face {@code side} connects electrically. Most blocks connect on every face; pumps not on their
     * pipe faces (the water pump only through its power-in port).
     */
    default boolean connectsPower(BlockState state, Direction side) {
        return true;
    }

    /**
     * Face of the power-in port of a machine with a six-way {@code facing}: its top, or (when it points up or
     * down, so its pipes are on the top and bottom) the side {@code port}.
     */
    static Direction powerPort(Direction facing, Direction port) {
        return facing.getAxis() == Direction.Axis.Y ? port : Direction.UP;
    }
}

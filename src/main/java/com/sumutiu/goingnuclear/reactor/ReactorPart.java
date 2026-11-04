package com.sumutiu.goingnuclear.reactor;

/**
 * Marker for reactor blocks. Structural parts form the casing shell; the others go inside.
 */
public interface ReactorPart {
    boolean isStructural();
}

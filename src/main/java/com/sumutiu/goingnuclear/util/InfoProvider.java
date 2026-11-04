package com.sumutiu.goingnuclear.util;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Block entities that can describe themselves to the Thermal Probe.
 */
public interface InfoProvider {
    void appendInfo(List<Component> lines);
}

package com.sumutiu.goingnuclear.power;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Overhead wire endpoints stored by a block entity (both ends store each other).
 */
public final class WireSet {
    private final Set<BlockPos> targets = new LinkedHashSet<>();
    private int version;

    public Set<BlockPos> targets() {
        return Collections.unmodifiableSet(this.targets);
    }

    public boolean add(BlockPos pos) {
        boolean added = this.targets.add(pos.immutable());
        if (added) this.version++;
        return added;
    }

    public boolean remove(BlockPos pos) {
        boolean removed = this.targets.remove(pos);
        if (removed) this.version++;
        return removed;
    }

    public void clear() {
        if (!this.targets.isEmpty()) this.version++;
        this.targets.clear();
    }

    public boolean contains(BlockPos pos) {
        return this.targets.contains(pos);
    }

    public int size() {
        return this.targets.size();
    }

    public int version() {
        return this.version;
    }

    public void save(ValueOutput output) {
        output.store("wires", BlockPos.CODEC.listOf(), new ArrayList<>(this.targets));
    }

    public void load(ValueInput input) {
        this.targets.clear();
        List<BlockPos> list = input.read("wires", BlockPos.CODEC.listOf()).orElse(List.of());
        for (BlockPos pos : list) this.targets.add(pos.immutable());
        this.version++;
    }
}

package com.sumutiu.goingnuclear.power;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One connected electric network. Solved once per tick: production first feeds consumers, the surplus
 * charges batteries; a deficit is covered by batteries, and anything still missing browns everyone out evenly.
 * <p>
 * A battery takes power in only through its back, so it can sit on two networks: on the one at its back it
 * charges (and gives power back when that network runs short), on the one at its power-out ports it only
 * discharges. A battery whose power-out port feeds another battery's back charges that one with whatever the
 * network does not need.
 * <p>
 * Batteries whose power-out ports are linked (side by side, stacked, or through cables) work as one bank:
 * after each tick the fuller ones top up the emptier ones, so charge that comes in through any of their backs
 * spreads over the whole bank.
 * <p>
 * Batteries doing the same job on a network always charge or discharge by the same amount; one that is
 * full, empty or at its rate limit hands the rest to the others.
 * <p>
 * Steam turbines follow the load (like a real turbine governor): after each tick the network works out what
 * it will need next tick, the consumption plus what its batteries can still take, less what generators that
 * cannot throttle make anyway, and splits that evenly between its turbines. A turbine only takes the steam it
 * needs for its part, so unused power is not made and its steam is not burned.
 */
public final class PowerNetwork {
    private static final int CHARGES = 1;
    private static final int DISCHARGES = 2;
    /**
     * The battery has an output-only terminal (its front) on this network.
     */
    private static final int OUTPUT_ONLY = 4;

    final List<Member> members = new ArrayList<>();
    final List<BlockPos> furnaces = new ArrayList<>();
    final List<BlockPos> cables = new ArrayList<>();

    private double lastSupply;
    private double lastDemand;
    private double lastSatisfaction = 1.0;
    private double lastStored;
    private double lastCapacity;
    private int lastBankSize;

    public double lastSupply() {
        return this.lastSupply;
    }

    public double lastDemand() {
        return this.lastDemand;
    }

    public double lastSatisfaction() {
        return this.lastSatisfaction;
    }

    public double lastStored() {
        return this.lastStored;
    }

    public double lastCapacity() {
        return this.lastCapacity;
    }

    /**
     * Batteries linked through their power-out ports on this network (they share their charge).
     */
    public int lastBankSize() {
        return this.lastBankSize;
    }

    public int nodeCount() {
        return this.members.size() + this.furnaces.size();
    }

    void solve(ServerLevel level) {
        List<ElectricNode> consumers = new ArrayList<>();
        List<Double> demands = new ArrayList<>();
        // What each battery may do on this network (charge, discharge or both), from the terminals it has here.
        Map<ElectricNode, Integer> storages = new LinkedHashMap<>();
        List<ElectricNode> governed = new ArrayList<>();
        double supply = 0.0;
        double fixedSupply = 0.0;
        double demand = 0.0;
        for (Member member : this.members) {
            if (!level.isLoaded(member.pos())) continue;
            BlockEntity be = level.getBlockEntity(member.pos());
            if (!(be instanceof ElectricNode node)) continue;
            if (member.terminal() == 0) {
                double output = node.powerOutput();
                supply += output;
                node.clearOutput();
                if (node.followsLoad()) {
                    governed.add(node);
                } else {
                    fixedSupply += output;
                }
                double wanted = Math.max(0.0, node.powerDemand());
                consumers.add(node);
                demands.add(wanted);
                demand += wanted;
            }
            if (node.isStorage()) {
                boolean charges = node.chargesThrough(member.terminal());
                boolean discharges = node.dischargesThrough(member.terminal());
                int role = (charges ? CHARGES : 0) | (discharges ? DISCHARGES : 0) | (discharges && !charges ? OUTPUT_ONLY : 0);
                storages.merge(node, role, (a, b) -> a | b);
            }
        }

        List<AbstractFurnaceBlockEntity> furnaceEntities = new ArrayList<>();
        List<BlockPos> furnacePositions = new ArrayList<>();
        for (BlockPos pos : this.furnaces) {
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockEntity(pos) instanceof AbstractFurnaceBlockEntity furnace) {
                double furnaceDemand = FurnacePower.demand(furnace);
                if (furnaceDemand > 0.0) {
                    furnaceEntities.add(furnace);
                    furnacePositions.add(pos);
                    demand += furnaceDemand;
                }
            }
        }

        List<ElectricNode> chargeable = new ArrayList<>();
        List<ElectricNode> dischargeable = new ArrayList<>();
        // For one bank feeding the next: batteries that only have their power-out ports here, and batteries that
        // only have their back here (one with both on the same network just works both ways).
        List<ElectricNode> inputsOnly = new ArrayList<>();
        List<ElectricNode> outputsOnly = new ArrayList<>();
        // Batteries with a power-out port here: one bank.
        List<ElectricNode> bank = new ArrayList<>();
        for (Map.Entry<ElectricNode, Integer> entry : storages.entrySet()) {
            int role = entry.getValue();
            boolean charges = (role & CHARGES) != 0;
            boolean outputOnly = (role & OUTPUT_ONLY) != 0;
            if (charges) chargeable.add(entry.getKey());
            if ((role & DISCHARGES) != 0) dischargeable.add(entry.getKey());
            if (charges && !outputOnly) inputsOnly.add(entry.getKey());
            if (outputOnly && !charges) outputsOnly.add(entry.getKey());
            if (outputOnly) bank.add(entry.getKey());
        }

        double satisfaction;
        if (supply >= demand) {
            satisfaction = 1.0;
            spread(chargeable, supply - demand, true);
        } else {
            double covered = spread(dischargeable, demand - supply, false);
            satisfaction = demand <= 0.0 ? 1.0 : Math.min(1.0, (supply + covered) / demand);
        }

        // Battery outputs charge the battery inputs on this network with what is left (one bank feeding the next).
        double room = 0.0;
        for (ElectricNode node : inputsOnly) room += node.chargeRoom();
        double available = 0.0;
        for (ElectricNode node : outputsOnly) available += node.dischargeAvailable();
        double transfer = Math.min(room, available);
        if (transfer > 1.0e-9) spread(inputsOnly, spread(outputsOnly, transfer, false), true);
        balance(bank);

        for (int i = 0; i < consumers.size(); i++) {
            consumers.get(i).receivePower(demands.get(i) > 0.0 ? satisfaction : 1.0);
        }
        if (satisfaction >= 0.999) {
            for (int i = 0; i < furnaceEntities.size(); i++) {
                FurnacePower.power(level, furnacePositions.get(i), furnaceEntities.get(i));
            }
        }

        // Next tick the turbines make what is needed: the consumption plus what the batteries can take.
        if (!governed.isEmpty()) {
            double needed = demand - fixedSupply;
            for (ElectricNode node : chargeable) {
                needed += Math.clamp(node.capacity() - node.stored(), 0.0, node.maxChargeRate());
            }
            shareLoad(governed, Math.max(0.0, needed));
        }

        double stored = 0.0;
        double capacity = 0.0;
        for (ElectricNode storage : storages.keySet()) {
            stored += storage.stored();
            capacity += storage.capacity();
        }
        this.lastSupply = supply;
        this.lastDemand = demand;
        this.lastSatisfaction = satisfaction;
        this.lastStored = stored;
        this.lastCapacity = capacity;
        this.lastBankSize = bank.size();
    }

    /**
     * Evens out the charge of the batteries of one bank: each one above the bank's average fill gives, each
     * one below it takes, as far as their rate limits for this tick allow. Nothing is lost on the way.
     */
    private static void balance(List<ElectricNode> bank) {
        int count = bank.size();
        if (count < 2) return;
        double stored = 0.0;
        double capacity = 0.0;
        for (ElectricNode node : bank) {
            stored += node.stored();
            capacity += node.capacity();
        }
        if (capacity <= 0.0) return;
        double fill = stored / capacity;
        // Positive: what a battery can give towards the even fill; negative: what it can take.
        double[] surplus = new double[count];
        double give = 0.0;
        double take = 0.0;
        for (int i = 0; i < count; i++) {
            ElectricNode node = bank.get(i);
            double difference = node.stored() - node.capacity() * fill;
            if (difference > 0.0) {
                surplus[i] = Math.min(difference, node.dischargeAvailable());
                give += surplus[i];
            } else {
                surplus[i] = -Math.min(-difference, node.chargeRoom());
                take -= surplus[i];
            }
        }
        double moved = Math.min(give, take);
        if (moved < 1.0e-6) return;
        // Everyone moves the same share of what they could, so what is given is exactly what is taken.
        for (int i = 0; i < count; i++) {
            if (surplus[i] > 0.0) {
                bank.get(i).shareWithBank(-surplus[i] * moved / give);
            } else if (surplus[i] < 0.0) {
                bank.get(i).shareWithBank(-surplus[i] * moved / take);
            }
        }
    }

    /**
     * Splits {@code load} evenly between load-following producers; one that cannot make its whole part hands
     * the rest to the others.
     */
    private static void shareLoad(List<ElectricNode> producers, double load) {
        int count = producers.size();
        double[] targets = new double[count];
        boolean[] open = new boolean[count];
        Arrays.fill(open, true);
        int openCount = count;
        double left = load;
        for (int pass = 0; pass < count && left > 1.0e-9 && openCount > 0; pass++) {
            double part = left / openCount;
            for (int i = 0; i < count; i++) {
                if (!open[i]) continue;
                double give = Math.clamp(producers.get(i).loadCapacity() - targets[i], 0.0, part);
                targets[i] += give;
                left -= give;
                if (give < part - 1.0e-9) {
                    open[i] = false;
                    openCount--;
                }
            }
        }
        for (int i = 0; i < count; i++) producers.get(i).setLoadTarget(targets[i]);
    }

    /**
     * Charges (or discharges) the batteries with {@code amount} in equal parts. A battery that cannot take its
     * whole part (full, empty, or at its rate limit for this tick) drops out, and what it left is split between
     * the rest.
     *
     * @return the energy actually stored (or released)
     */
    private static double spread(List<ElectricNode> storages, double amount, boolean charge) {
        int count = storages.size();
        if (count == 0 || amount <= 0.0) return 0.0;
        boolean[] open = new boolean[count];
        Arrays.fill(open, true);
        int openCount = count;
        double left = amount;
        // Every pass either places everything that is left or closes at least one battery.
        for (int pass = 0; pass < count && left > 1.0e-9 && openCount > 0; pass++) {
            double part = left / openCount;
            for (int i = 0; i < count; i++) {
                if (!open[i]) continue;
                ElectricNode storage = storages.get(i);
                double done = charge ? storage.charge(part) : storage.discharge(part);
                left -= done;
                if (done < part - 1.0e-9) {
                    open[i] = false;
                    openCount--;
                }
            }
        }
        return amount - left;
    }

    /**
     * One terminal of a node on this network (see {@link ElectricNode#terminals}).
     */
    record Member(BlockPos pos, int terminal) {
    }
}

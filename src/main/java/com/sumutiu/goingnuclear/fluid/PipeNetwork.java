package com.sumutiu.goingnuclear.fluid;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.machine.FluidPipeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A connected group of pipes. Acts as one passive fluid buffer: machines push into it and pull out of it.
 * A network holds one fluid type at a time, and keeps its temperature (fluid pushed in mixes with what is
 * there; the pipes are insulated, so it keeps its heat).
 * <p>
 * The amount in the network is only what waits in the pipes (what came in and was not taken out yet). Fluid
 * that is taken out as fast as it comes in, or that pumps draw straight through the pipes from machine
 * outlets ({@link #suction}), leaves nothing waiting but still flows through them, so the network also
 * measures its flow over the last second (what the Thermal Probe shows).
 */
public final class PipeNetwork implements FluidHandler {
    /**
     * Flow is averaged over this many ticks (one slot per tick).
     */
    private static final int FLOW_WINDOW = 20;

    private final ServerLevel level;
    private final Set<BlockPos> pipes;
    private final List<Boundary> boundary;
    private final double capacity;
    private @Nullable FluidType type;
    private double amount;
    private double temperature = 20.0;
    private final FairShare pushShare = new FairShare();
    private final FairShare pullShare = new FairShare();
    private final long[] flowTick = new long[FLOW_WINDOW];
    private final double[] flowIn = new double[FLOW_WINDOW];
    private final double[] flowOut = new double[FLOW_WINDOW];
    private final double[] flowHeat = new double[FLOW_WINDOW];
    private @Nullable FluidType flowType;

    public PipeNetwork(ServerLevel level, Set<BlockPos> pipes, List<Boundary> boundary) {
        this.level = level;
        this.pipes = pipes;
        this.boundary = boundary;
        this.capacity = pipes.size() * NuclearConfig.get().pipeCapacity;
        Arrays.fill(this.flowTick, Long.MIN_VALUE);
    }

    public Set<BlockPos> pipes() {
        return this.pipes;
    }

    public List<Boundary> boundary() {
        return this.boundary;
    }

    public @Nullable FluidType type() {
        return this.amount > 1.0e-6 ? this.type : null;
    }

    public double amount() {
        return this.amount;
    }

    /**
     * Temperature (°C) of the fluid in the pipes.
     */
    public double temperature() {
        return this.temperature;
    }

    @Override
    public double temperature(Direction side, FluidType type) {
        return this.temperature;
    }

    @Override
    public double fillLevel(Direction side) {
        return this.capacity > 0.0 ? this.amount / this.capacity : 1.0;
    }

    public double capacity() {
        return this.capacity;
    }

    /**
     * Fluid that went into the pipes, mB per tick averaged over the last second.
     */
    public double flowIn() {
        return this.windowSum(this.flowIn) / FLOW_WINDOW;
    }

    /**
     * Fluid that came out of the pipes (or was drawn through them), mB per tick averaged over the last second.
     */
    public double flowOut() {
        return this.windowSum(this.flowOut) / FLOW_WINDOW;
    }

    /**
     * The fluid flowing through the pipes over the last second, or null if nothing moved. Unlike
     * {@link #type()}, this also knows the fluid when none of it stays in the pipes.
     */
    public @Nullable FluidType flowingType() {
        return this.flowIn() + this.flowOut() > 1.0e-4 ? this.flowType : null;
    }

    /**
     * Temperature (°C) of what came out of the pipes over the last second, or of what is in them.
     */
    public double flowTemperature() {
        double out = this.windowSum(this.flowOut);
        return out > 1.0e-9 ? this.windowSum(this.flowHeat) / out : this.temperature;
    }

    private double windowSum(double[] values) {
        long oldest = this.level.getGameTime() - FLOW_WINDOW;
        double sum = 0.0;
        for (int i = 0; i < FLOW_WINDOW; i++) {
            if (this.flowTick[i] > oldest) sum += values[i];
        }
        return sum;
    }

    /**
     * This tick's slot in the flow window (cleared when a new tick starts using it).
     */
    private int flowSlot() {
        long now = this.level.getGameTime();
        int slot = Math.floorMod(now, FLOW_WINDOW);
        if (this.flowTick[slot] != now) {
            this.flowTick[slot] = now;
            this.flowIn[slot] = 0.0;
            this.flowOut[slot] = 0.0;
            this.flowHeat[slot] = 0.0;
        }
        return slot;
    }

    private void flowedIn(FluidType type, double amount) {
        if (amount <= 0.0) return;
        this.flowIn[this.flowSlot()] += amount;
        this.flowType = type;
    }

    private void flowedOut(FluidType type, double amount, double temperature) {
        if (amount <= 0.0) return;
        int slot = this.flowSlot();
        this.flowOut[slot] += amount;
        this.flowHeat[slot] += amount * temperature;
        this.flowType = type;
    }

    /**
     * The room in this network, shared evenly between everything pushing into it (see {@link FluidAccess#push}).
     */
    FairShare pushShare() {
        return this.pushShare;
    }

    /**
     * The fluid in this network, shared evenly between everything drawing from it (see {@link FluidAccess#pull}).
     */
    FairShare pullShare() {
        return this.pullShare;
    }

    void load(ServerLevel level) {
        Map<FluidType, Double> totals = new EnumMap<>(FluidType.class);
        Map<FluidType, Double> heat = new EnumMap<>(FluidType.class);
        for (BlockPos pos : this.pipes) {
            if (level.getBlockEntity(pos) instanceof FluidPipeBlockEntity pipe && pipe.storedType() instanceof FluidType stored
                    && pipe.storedAmount() > 0.0) {
                totals.merge(stored, pipe.storedAmount(), Double::sum);
                heat.merge(stored, pipe.storedAmount() * pipe.storedTemperature(), Double::sum);
            }
        }
        FluidType best = null;
        double bestAmount = 0.0;
        for (Map.Entry<FluidType, Double> entry : totals.entrySet()) {
            if (entry.getValue() > bestAmount) {
                best = entry.getKey();
                bestAmount = entry.getValue();
            }
        }
        this.type = best;
        this.amount = Math.min(bestAmount, this.capacity);
        this.temperature = best != null ? heat.get(best) / bestAmount : 20.0;
    }

    void writeBack(ServerLevel level) {
        if (this.pipes.isEmpty()) return;
        double share = this.amount / this.pipes.size();
        FluidType stored = this.type();
        for (BlockPos pos : this.pipes) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof FluidPipeBlockEntity pipe) {
                pipe.setStored(stored, stored == null ? 0.0 : share, this.temperature);
            }
        }
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, boolean simulate) {
        return this.insert(side, type, amount, type.defaultTemperature(), simulate);
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, double temperature, boolean simulate) {
        if (amount <= 0.0) return 0.0;
        FluidType current = this.type();
        if (current != null && current != type) return 0.0;
        double accepted = Math.min(amount, this.capacity - this.amount);
        if (accepted <= 0.0) return 0.0;
        if (!simulate) {
            this.temperature = current == null ? temperature : FluidTank.mix(this.amount, this.temperature, accepted, temperature);
            this.amount += accepted;
            this.type = type;
            this.flowedIn(type, accepted);
        }
        return accepted;
    }

    @Override
    public double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate) {
        if (max <= 0.0 || this.type() != type) return 0.0;
        double taken = Math.min(max, this.amount);
        if (!simulate) {
            this.amount -= taken;
            this.flowedOut(type, taken, this.temperature);
            if (this.amount < 1.0e-6) {
                this.amount = 0.0;
                this.type = null;
            }
        }
        return taken;
    }

    @Override
    public @Nullable FluidType available(Direction side, boolean pumped) {
        return this.type();
    }

    /**
     * Pulls fluid straight out of the machine outlets on this network (circulation pumps use it, so a pump can
     * draw from heat exchanger or turbine outlets through a stretch of pipe). Each outlet is split evenly
     * between the pumps drawing on it, and each pump takes the same amount from every outlet that has some.
     *
     * @return what was drawn and its mixed temperature
     */
    public Drawn suction(ServerLevel level, FluidType type, double max, BlockPos puller) {
        List<Outlet> outlets = new ArrayList<>();
        for (Boundary edge : this.boundary) {
            BlockPos at = edge.pipe().relative(edge.direction());
            if (at.equals(puller)) continue;
            FluidHandler handler = FluidAccess.getMachine(level, at);
            Direction side = edge.direction().getOpposite();
            // Outlets of this fluid count the pump in even while they are empty, so the pumps keep their even
            // shares when the outlets are drained completely every tick.
            if (handler != null && handler.offers(side, true) == type) outlets.add(new Outlet(handler, at, side));
        }
        double got = 0.0;
        double heat = 0.0;
        // A second and third pass hand the part of an outlet that ran dry to the others.
        for (int pass = 0; pass < 3 && got < max - 1.0e-9 && !outlets.isEmpty(); pass++) {
            double portion = (max - got) / outlets.size();
            Iterator<Outlet> iterator = outlets.iterator();
            while (iterator.hasNext()) {
                Outlet outlet = iterator.next();
                double allowed = FluidAccess.pullAllowance(level, puller, outlet.handler(), outlet.pos(), outlet.side(), type, true);
                double ask = Math.min(portion, allowed);
                double taken = ask > 0.0 ? outlet.handler().extract(outlet.side(), type, ask, true, false) : 0.0;
                FluidAccess.pulled(level, puller, outlet.handler(), outlet.pos(), type, true, taken);
                got += taken;
                heat += taken * outlet.handler().temperature(outlet.side(), type);
                if (taken < portion - 1.0e-9) iterator.remove();
            }
        }
        // It never waits in the pipes, but it does go through them.
        double temperature = got > 0.0 ? heat / got : type.defaultTemperature();
        this.flowedIn(type, got);
        this.flowedOut(type, got, temperature);
        return new Drawn(got, temperature);
    }

    /**
     * A fluid that machine outlets on this network have for pumps right now, or null.
     */
    public @Nullable FluidType suctionType(ServerLevel level, BlockPos exclude) {
        return this.outletFluid(level, exclude, false);
    }

    /**
     * The fluid machine outlets on this network give pumps, even if they are empty right now, or null.
     */
    public @Nullable FluidType outletType(ServerLevel level, BlockPos exclude) {
        return this.outletFluid(level, exclude, true);
    }

    private @Nullable FluidType outletFluid(ServerLevel level, BlockPos exclude, boolean evenIfEmpty) {
        for (Boundary edge : this.boundary) {
            BlockPos target = edge.pipe().relative(edge.direction());
            if (target.equals(exclude)) continue;
            FluidHandler handler = FluidAccess.getMachine(level, target);
            if (handler == null) continue;
            Direction side = edge.direction().getOpposite();
            FluidType type = evenIfEmpty ? handler.offers(side, true) : handler.available(side, true);
            if (type != null) return type;
        }
        return null;
    }

    /**
     * True if this network holds, carries or is fed with a fluid other than {@code type}, even while it is
     * empty right now. Machines that push on their own check this, so they never push a second fluid into an
     * empty steam, water or coolant line (one fluid per pipe: it would block that line).
     */
    public boolean otherFluidThan(ServerLevel level, FluidType type) {
        FluidType held = this.type();
        if (held != null) return held != type;
        FluidType flowing = this.flowingType();
        if (flowing != null && flowing != type) return true;
        for (Boundary edge : this.boundary) {
            FluidHandler handler = FluidAccess.getMachine(level, edge.pipe().relative(edge.direction()));
            if (handler == null) continue;
            FluidType offered = handler.offers(edge.direction().getOpposite(), false);
            if (offered != null && offered != type) return true;
        }
        return false;
    }

    private record Outlet(FluidHandler handler, BlockPos pos, Direction side) {
    }

    /**
     * An amount of fluid and its temperature.
     */
    public record Drawn(double amount, double temperature) {
    }

    public record Boundary(BlockPos pipe, Direction direction) {
    }

    public static List<Boundary> newBoundaryList() {
        return new ArrayList<>();
    }
}

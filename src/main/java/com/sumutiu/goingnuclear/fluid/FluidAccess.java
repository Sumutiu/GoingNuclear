package com.sumutiu.goingnuclear.fluid;

import com.sumutiu.goingnuclear.machine.FluidPipeBlock;
import com.sumutiu.goingnuclear.reactor.ReactorPortBlock;
import com.sumutiu.goingnuclear.reactor.ReactorRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.ToDoubleFunction;

/**
 * Finds the fluid handler next to a machine (a pipe network, a reactor port or another machine) and moves
 * fluid to and from it.
 * <p>
 * Every move is fair: everything pushing the same fluid into one pipe or machine shares the room in it
 * evenly, and everything drawing the same fluid from one pipe or machine outlet shares what is in it evenly
 * (see {@link FairShare}). So parallel heat exchangers, pumps, turbines and reactor ports all carry an equal
 * part of the load.
 */
public final class FluidAccess {
    private static final double UNLIMITED = 1.0e12;
    /**
     * A machine has six faces, so at most six others can share it. Only used until the real count is known.
     */
    private static final int MACHINE_SHARERS_GUESS = 6;
    private static final int PRUNE_TICKS = 200;
    private static final Map<ServerLevel, Map<ShareKey, FairShare>> MACHINE_SHARES = new WeakHashMap<>();

    private FluidAccess() {
    }

    /**
     * Handler at {@code pos}, as seen from the neighbour on face {@code side} of that position.
     */
    public static @Nullable FluidHandler get(ServerLevel level, BlockPos pos, Direction side) {
        if (!level.isLoaded(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FluidPipeBlock) {
            if (!state.getValue(FluidPipeBlock.connection(side))) return null;
            return PipeNetworks.of(level).networkAt(pos);
        }
        return getMachine(level, pos);
    }

    /**
     * Handler of a non-pipe block (machine block entity or reactor port).
     */
    public static @Nullable FluidHandler getMachine(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ReactorPortBlock) {
            return ReactorRegistry.portHandler(level, pos);
        }
        if (level.getBlockEntity(pos) instanceof FluidHandler handler) return handler;
        return null;
    }

    /**
     * Push fluid out of the block at {@code from} through its face {@code dir}, sharing the room on the other
     * side evenly with everything else pushing into it.
     */
    public static double push(ServerLevel level, BlockPos from, Direction dir, FluidType type, double amount) {
        return push(level, from, dir, type, amount, type.defaultTemperature());
    }

    /**
     * Like {@link #push(ServerLevel, BlockPos, Direction, FluidType, double)}, for fluid at {@code temperature} (°C).
     */
    public static double push(ServerLevel level, BlockPos from, Direction dir, FluidType type, double amount, double temperature) {
        if (amount <= 0.0) return 0.0;
        BlockPos to = from.relative(dir);
        Direction side = dir.getOpposite();
        FluidHandler target = get(level, to, side);
        if (target == null) return 0.0;
        double allowed = pushAllowance(level, from, target, to, side, type);
        if (allowed <= 0.0) return 0.0;
        double moved = target.insert(side, type, Math.min(amount, allowed), temperature, false);
        pushed(level, from, target, to, type, moved);
        return moved;
    }

    /**
     * Pull fluid into the block at {@code from} through its face {@code dir}, sharing what is on the other side
     * evenly with everything else drawing on it.
     */
    public static double pull(ServerLevel level, BlockPos from, Direction dir, FluidType type, double max, boolean pumped) {
        if (max <= 0.0) return 0.0;
        BlockPos at = from.relative(dir);
        Direction side = dir.getOpposite();
        FluidHandler source = get(level, at, side);
        if (source == null) return 0.0;
        double allowed = pullAllowance(level, from, source, at, side, type, pumped);
        if (allowed <= 0.0) return 0.0;
        double moved = source.extract(side, type, Math.min(max, allowed), pumped, false);
        pulled(level, from, source, at, type, pumped, moved);
        return moved;
    }

    /**
     * How much the machine at {@code from} may still push into {@code target} (the handler at
     * {@code targetPos}, entered through its face {@code side}) this tick. Asking also counts the machine
     * among those sharing the target, even when there is no room right now. A pipe holding another fluid gives
     * nothing and does not count it. Report what was moved with {@link #pushed}.
     */
    public static double pushAllowance(ServerLevel level, BlockPos from, FluidHandler target, BlockPos targetPos, Direction side, FluidType type) {
        if (target instanceof PipeNetwork network) {
            if (network.type() != null && network.type() != type) return 0.0;
            return network.pushShare().allowance(level.getGameTime(), from, network.boundary().size(),
                    () -> network.insert(side, type, UNLIMITED, true));
        }
        return machineShare(level, targetPos, type, true, false).allowance(level.getGameTime(), from, MACHINE_SHARERS_GUESS,
                () -> mostOverSides(dir -> target.insert(dir, type, UNLIMITED, true)));
    }

    public static void pushed(ServerLevel level, BlockPos from, FluidHandler target, BlockPos targetPos, FluidType type, double amount) {
        if (amount <= 0.0) return;
        FairShare share = target instanceof PipeNetwork network ? network.pushShare() : machineShare(level, targetPos, type, true, false);
        share.used(from, amount);
    }

    /**
     * How much the machine at {@code from} may still draw from {@code source} (the handler at
     * {@code sourcePos}, reached through its face {@code side}) this tick. Asking also counts the machine
     * among those sharing the source, even while it is empty. A pipe holding another fluid gives nothing and
     * does not count it. Report what was moved with {@link #pulled}.
     */
    public static double pullAllowance(ServerLevel level, BlockPos from, FluidHandler source, BlockPos sourcePos, Direction side,
                                       FluidType type, boolean pumped) {
        if (source instanceof PipeNetwork network) {
            if (network.type() != null && network.type() != type) return 0.0;
            return network.pullShare().allowance(level.getGameTime(), from, network.boundary().size(),
                    () -> network.extract(side, type, UNLIMITED, pumped, true));
        }
        return machineShare(level, sourcePos, type, false, pumped).allowance(level.getGameTime(), from, MACHINE_SHARERS_GUESS,
                () -> mostOverSides(dir -> source.extract(dir, type, UNLIMITED, pumped, true)));
    }

    public static void pulled(ServerLevel level, BlockPos from, FluidHandler source, BlockPos sourcePos, FluidType type, boolean pumped, double amount) {
        if (amount <= 0.0) return;
        FairShare share = source instanceof PipeNetwork network ? network.pullShare() : machineShare(level, sourcePos, type, false, pumped);
        share.used(from, amount);
    }

    /**
     * Pulls up to {@code max} into the block at {@code from} through several of its faces, the same amount
     * through each face that has some; what a face that ran dry could not give is asked of the others.
     *
     * @return the total pulled; the caller must store all of it
     */
    public static double pullEvenly(ServerLevel level, BlockPos from, Direction[] faces, FluidType type, double max, boolean pumped) {
        List<Direction> open = new ArrayList<>();
        for (Direction face : faces) {
            if (get(level, from.relative(face), face.getOpposite()) != null) open.add(face);
        }
        double got = 0.0;
        for (int pass = 0; pass < 3 && got < max - 1.0e-9 && !open.isEmpty(); pass++) {
            double part = (max - got) / open.size();
            Iterator<Direction> iterator = open.iterator();
            while (iterator.hasNext()) {
                double taken = pull(level, from, iterator.next(), type, part, pumped);
                got += taken;
                if (taken < part - 1.0e-9) iterator.remove();
            }
        }
        return got;
    }

    /**
     * Temperature (°C) of the fluid that the block at {@code from} would get through its face {@code dir}.
     */
    public static double temperatureAt(ServerLevel level, BlockPos from, Direction dir, FluidType type) {
        FluidHandler handler = get(level, from.relative(dir), dir.getOpposite());
        return handler != null ? handler.temperature(dir.getOpposite(), type) : type.defaultTemperature();
    }

    /**
     * True if the pipe next to {@code from} on face {@code dir} belongs to a line of another fluid than
     * {@code type} (see {@link PipeNetwork#otherFluidThan}); false for anything that is not a pipe.
     */
    public static boolean isOtherFluidLine(ServerLevel level, BlockPos from, Direction dir, FluidType type) {
        return get(level, from.relative(dir), dir.getOpposite()) instanceof PipeNetwork network && network.otherFluidThan(level, type);
    }

    /**
     * Forgets the shares of machines nobody has used for a while. Called every tick by the pipe networks.
     */
    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now % PRUNE_TICKS != 0L) return;
        Map<ShareKey, FairShare> shares = MACHINE_SHARES.get(level);
        if (shares != null) shares.values().removeIf(share -> now - share.lastTick() > PRUNE_TICKS);
    }

    public static void clearAll() {
        MACHINE_SHARES.clear();
    }

    /**
     * Shares of a machine are kept per fluid and per direction (so a heat exchanger's coolant inlet and its
     * coolant outlets are shared separately), and pump-only outlets separately from the rest.
     */
    private static FairShare machineShare(ServerLevel level, BlockPos pos, FluidType type, boolean push, boolean pumped) {
        return MACHINE_SHARES.computeIfAbsent(level, _ -> new HashMap<>())
                .computeIfAbsent(new ShareKey(pos.immutable(), type, push, !push && pumped), _ -> new FairShare());
    }

    /**
     * The most a machine has through any of its faces (outlets on different faces usually share one tank).
     */
    private static double mostOverSides(ToDoubleFunction<Direction> amount) {
        double most = 0.0;
        for (Direction dir : Direction.values()) most = Math.max(most, amount.applyAsDouble(dir));
        return most;
    }

    private record ShareKey(BlockPos pos, FluidType type, boolean push, boolean pumped) {
    }
}

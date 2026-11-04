package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidAccess;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import com.sumutiu.goingnuclear.util.Sounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Takes water in through every face but the front (pushed in by a condenser or a pump, or drawn from the pipes
 * on its own, up to {@code waterOutletRate} mB/t) and lets it out of the front, where it is gone for good.
 * <ul>
 *     <li>Water in front (a river, a lake, a waterlogged block): it just flows into it.</li>
 *     <li>Open air (or something water washes away, like grass): it pours out a water block there, and takes
 *     it back after {@link #DRY_TICKS} ticks without any water coming out, or when the outlet is broken or
 *     turned. A water block that was already there is never taken.</li>
 *     <li>Anything else (a solid block, lava): nothing comes out, and the pipes back up.</li>
 * </ul>
 */
public class WaterOutletBlockEntity extends BlockEntity implements MachineBlock.TickingMachine, FluidHandler, InfoProvider {
    /**
     * Ticks without any water coming out before the outlet takes back the water block it poured.
     */
    private static final int DRY_TICKS = 40;
    /**
     * For each facing: the faces water comes in through (all but the front).
     */
    private static final Map<Direction, Direction[]> INLETS = new EnumMap<>(Direction.class);

    static {
        for (Direction front : Direction.values()) {
            INLETS.put(front, Arrays.stream(Direction.values()).filter(dir -> dir != front).toArray(Direction[]::new));
        }
    }

    /**
     * The tick {@link #usedThisTick} counts for.
     */
    private long tick = Long.MIN_VALUE;
    private double usedThisTick;
    /**
     * Water taken in since the outlet's last tick (drawn by itself, or pushed in by others).
     */
    private double pending;
    private double lastFlow;
    private double flowThisSecond;
    private double flowRate;
    private int dryTicks;
    /**
     * Where the outlet poured a water block of its own, or null.
     */
    private @Nullable BlockPos poured;

    public WaterOutletBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WATER_OUTLET, pos, state);
    }

    private Direction front() {
        return this.getBlockState().getValue(WaterOutletBlock.FACING);
    }

    /**
     * What is in front of the outlet, as far as letting water out goes.
     */
    private enum Front {
        /**
         * Water already: it flows into it.
         */
        WATER,
        /**
         * Air, or something water washes away: it pours out a water block.
         */
        OPEN,
        /**
         * Anything else: no water comes out.
         */
        BLOCKED
    }

    private static Front frontAt(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) return Front.BLOCKED;
        BlockState state = level.getBlockState(at);
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.WATER)) return Front.WATER;
        // Lava and other fluids.
        if (!fluid.isEmpty()) return Front.BLOCKED;
        return state.isAir() || state.canBeReplaced() ? Front.OPEN : Front.BLOCKED;
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        Direction front = state.getValue(WaterOutletBlock.FACING);
        BlockPos out = pos.relative(front);
        // Turned with the wrench: take back the water poured on the old side.
        if (this.poured != null && !this.poured.equals(out)) this.takeBack(level);

        Front status = frontAt(level, out);
        if (status != Front.BLOCKED) {
            double room = this.room(level);
            if (room > 0.0) this.took(FluidAccess.pullEvenly(level, pos, INLETS.get(front), FluidType.WATER, room, false));
        }

        this.lastFlow = this.pending;
        this.pending = 0.0;
        this.flowThisSecond += this.lastFlow;
        if (this.lastFlow > 1.0e-6) {
            this.dryTicks = 0;
            if (status == Front.OPEN) this.pour(level, out);
            this.effects(level, pos, front);
        } else {
            this.dryTicks = Math.min(DRY_TICKS, this.dryTicks + 1);
            if (this.dryTicks >= DRY_TICKS && this.poured != null) this.takeBack(level);
        }
        if (level.getGameTime() % 20L == 0L) {
            this.flowRate = this.flowThisSecond / 20.0;
            this.flowThisSecond = 0.0;
            this.setChanged();
        }
    }

    /**
     * How much more water the outlet can let out this tick.
     */
    private double room(ServerLevel level) {
        long now = level.getGameTime();
        if (now != this.tick) {
            this.tick = now;
            this.usedThisTick = 0.0;
        }
        return Math.max(0.0, NuclearConfig.get().waterOutletRate - this.usedThisTick);
    }

    private void took(double amount) {
        if (amount <= 0.0) return;
        this.usedThisTick += amount;
        this.pending += amount;
    }

    /**
     * Pours out a water block in front (washing away grass and the like, which drop as items).
     */
    private void pour(ServerLevel level, BlockPos out) {
        if (!level.getBlockState(out).isAir()) level.destroyBlock(out, true);
        level.setBlock(out, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        this.poured = out.immutable();
        this.setChanged();
    }

    /**
     * Takes back the water block the outlet poured, if it is still there (the water flowing from it then
     * dries up by itself).
     */
    private void takeBack(ServerLevel level) {
        BlockPos at = this.poured;
        this.poured = null;
        this.setChanged();
        if (at == null || !level.isLoaded(at)) return;
        BlockState state = level.getBlockState(at);
        if (state.is(Blocks.WATER) && state.getFluidState().isSource()) {
            level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    /**
     * Splashes in front while water comes out, and the sound of running water now and then.
     */
    private void effects(ServerLevel level, BlockPos pos, Direction front) {
        if (level.getGameTime() % 4L == 0L) {
            double x = pos.getX() + 0.5 + front.getStepX() * 0.6;
            double y = pos.getY() + 0.5 + front.getStepY() * 0.6;
            double z = pos.getZ() + 0.5 + front.getStepZ() * 0.6;
            int count = 1 + (int) Math.min(4.0, this.lastFlow / 20.0);
            level.sendParticles(ParticleTypes.SPLASH, x, y, z, count, 0.15, 0.15, 0.15, 0.05);
        }
        if (Sounds.due(level, pos, 60)) {
            float flow = (float) Math.min(1.0, this.lastFlow / Math.max(1.0, NuclearConfig.get().waterOutletRate));
            Sounds.machine(level, pos, Sounds.waterGurgle(), 0.25f + 0.25f * flow, 1.1f);
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState oldState) {
        super.preRemoveSideEffects(pos, oldState);
        if (this.level instanceof ServerLevel serverLevel) this.takeBack(serverLevel);
    }

    private boolean blocked(ServerLevel level) {
        return frontAt(level, this.getBlockPos().relative(this.front())) == Front.BLOCKED;
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, boolean simulate) {
        if (type != FluidType.WATER || amount <= 0.0 || side == this.front() || !(this.level instanceof ServerLevel serverLevel)) return 0.0;
        if (this.blocked(serverLevel)) return 0.0;
        double accepted = Math.min(amount, this.room(serverLevel));
        if (!simulate) this.took(accepted);
        return accepted;
    }

    @Override
    public double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate) {
        return 0.0;
    }

    @Override
    public @Nullable FluidType available(Direction side, boolean pumped) {
        return null;
    }

    /**
     * It never fills up: always empty while water can get out, full while the front is blocked (so a check
     * valve feeding it stops).
     */
    @Override
    public double fillLevel(Direction side) {
        if (side == this.front() || !(this.level instanceof ServerLevel serverLevel)) return -1.0;
        return this.blocked(serverLevel) ? 1.0 : 0.0;
    }

    @Override
    public void appendInfo(List<Component> lines) {
        Direction front = this.front();
        lines.add(Fmt.title("Water Outlet"));
        lines.add(Fmt.label("Letting out", Fmt.rate(this.flowRate) + " of water (max " + Fmt.rate(NuclearConfig.get().waterOutletRate) + ")",
                ChatFormatting.AQUA));
        lines.add(Fmt.label("Water in", "every face but the front", ChatFormatting.BLUE));
        if (!(this.level instanceof ServerLevel serverLevel)) return;
        switch (frontAt(serverLevel, this.getBlockPos().relative(front))) {
            case WATER -> lines.add(Fmt.label("Water out (" + front.getName() + ")",
                    this.poured != null ? "pouring out a water block" : "into the water there", ChatFormatting.AQUA));
            case OPEN -> lines.add(Fmt.label("Water out (" + front.getName() + ")",
                    "open: it pours out a water block while water comes out", ChatFormatting.AQUA));
            case BLOCKED -> {
                lines.add(Fmt.label("Water out (" + front.getName() + ")", "blocked", ChatFormatting.RED));
                lines.add(Component.literal("Water only comes out into water or open air. Clear the front or turn the outlet"
                        + " with the Pipe Wrench; until then nothing comes out and the pipes back up.").withStyle(ChatFormatting.RED));
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (this.poured != null) output.store("poured", BlockPos.CODEC, this.poured);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.poured = input.read("poured", BlockPos.CODEC).orElse(null);
    }
}

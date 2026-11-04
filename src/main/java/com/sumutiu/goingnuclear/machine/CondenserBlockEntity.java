package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidAccess;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import com.sumutiu.goingnuclear.fluid.FluidTank;
import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.fluid.PipeNetwork;
import com.sumutiu.goingnuclear.fluid.Steam;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import com.sumutiu.goingnuclear.util.Sounds;
import com.sumutiu.goingnuclear.util.TemperatureSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Condenses turbine exhaust steam back into water. Each tick it takes exhaust steam in at the top (a turbine
 * on top of it pushes it in, or it draws it from the pipe there) and cold water in at the back, condenses up
 * to {@code condenserRate} mB of steam, using {@code condenserCoolingWater} mB of cooling water per mB, and
 * sends both waters on by themselves: the cooling water out of the front (a little warmer) and the condensate
 * out of the bottom (all of the water that was boiled into that steam).
 * <p>
 * When it gets more steam than it can condense, the exhaust backs up (in the pipe above it, or in the turbine
 * on top of it), the pressure rises and the turbines lose power (see SteamTurbineBlockEntity). Like a real
 * condenser it then also condenses more, as the steam condenses hotter: up to twice its rate at the open
 * air's pressure. So a plant with a few condensers too few settles at somewhat less power, rather than none.
 * Without cooling water, or with nowhere for the waters to go, it stops condensing. A plume of vapour rising
 * high above it, like over a cooling tower, shows how hard it works; a thick plume means the steam is
 * backing up.
 */
public class CondenserBlockEntity extends BlockEntity implements MachineBlock.TickingMachine, FluidHandler, InfoProvider, TemperatureSource {
    private static final Direction STEAM_IN = Direction.UP;
    private static final Direction CONDENSATE_OUT = Direction.DOWN;
    /**
     * How much warmer (°C) the cooling water leaves at full load.
     */
    private static final double WARMING = 10.0;

    private final FluidTank steam = new FluidTank(FluidType.STEAM, 200.0);
    private final FluidTank cold = new FluidTank(FluidType.WATER, 1000.0);
    private final FluidTank warm = new FluidTank(FluidType.WATER, 1000.0);
    private final FluidTank condensate = new FluidTank(FluidType.WATER, 1000.0);
    private double lastCondensed;
    private double lastDrained;
    /**
     * How far the exhaust has backed up past half full (0..1): the pressure rise.
     */
    private double rise;
    /**
     * The pipe below carries steam or coolant, so the condensate is kept out of it.
     */
    private boolean otherFluidBelow;

    public CondenserBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONDENSER, pos, state);
    }

    private Direction front() {
        return this.getBlockState().getValue(CondenserBlock.FACING);
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        NuclearConfig config = NuclearConfig.get();
        Direction front = state.getValue(CondenserBlock.FACING);
        Direction back = front.getOpposite();
        double rate = config.condenserRate;
        double ratio = config.condenserCoolingWater;

        // Exhaust steam in at the top and cold water in at the back, each with its temperature.
        if (this.steam.space() > 0.0) {
            double temperature = FluidAccess.temperatureAt(level, pos, STEAM_IN, FluidType.STEAM);
            double got = FluidAccess.pull(level, pos, STEAM_IN, FluidType.STEAM, Math.min(this.steam.space(), rate * 4.0), false);
            this.steam.insert(FluidType.STEAM, got, temperature, false);
        }
        if (this.cold.space() > 0.0 && ratio > 0.0) {
            double temperature = FluidAccess.temperatureAt(level, pos, back, FluidType.WATER);
            double got = FluidAccess.pull(level, pos, back, FluidType.WATER, Math.min(this.cold.space(), rate * ratio * 4.0), false);
            this.cold.insert(FluidType.WATER, got, temperature, false);
        }

        // The pressure of the exhaust: from the pipe above (which the turbines feel too), or from its own
        // steam space when a turbine sits right on it. Higher pressure condenses faster.
        FluidHandler above = FluidAccess.get(level, pos.relative(STEAM_IN), STEAM_IN.getOpposite());
        double backlog = above instanceof PipeNetwork network && network.type() != FluidType.WATER && network.type() != FluidType.COOLANT
                ? network.fillLevel(STEAM_IN.getOpposite()) : this.steam.fill();
        this.rise = Math.clamp((backlog - 0.5) / 0.5, 0.0, 1.0);
        double capacity = rate * (1.0 + this.rise);

        // Condense as much as the cooling water allows (and as there is room for both waters).
        double condensed = Math.min(this.steam.amount(), capacity);
        condensed = Math.min(condensed, this.condensate.space() * config.steamPerWater);
        if (ratio > 0.0) condensed = Math.min(condensed, Math.min(this.cold.amount(), this.warm.space()) / ratio);
        condensed = Math.max(0.0, condensed);
        double cooling = condensed * ratio;
        this.steam.extract(FluidType.STEAM, condensed, false);
        this.cold.extract(FluidType.WATER, cooling, false);
        this.warm.insert(FluidType.WATER, cooling, this.cold.temperature() + WARMING * Math.min(2.0, condensed / rate), false);
        this.condensate.insert(FluidType.WATER, condensed / config.steamPerWater, this.steam.temperature(), false);
        this.lastCondensed = condensed;

        // Both waters leave by themselves (never into a line of another fluid): the cooling water at the front,
        // the condensate at the bottom.
        if (this.warm.amount() > 0.0 && !FluidAccess.isOtherFluidLine(level, pos, front, FluidType.WATER)) {
            double pushed = FluidAccess.push(level, pos, front, FluidType.WATER, this.warm.amount(), this.warm.temperature());
            this.warm.extract(FluidType.WATER, pushed, false);
        }
        this.lastDrained = 0.0;
        this.otherFluidBelow = this.condensate.amount() > 0.0 && FluidAccess.isOtherFluidLine(level, pos, CONDENSATE_OUT, FluidType.WATER);
        if (this.condensate.amount() > 0.0 && !this.otherFluidBelow) {
            this.lastDrained = FluidAccess.push(level, pos, CONDENSATE_OUT, FluidType.WATER, this.condensate.amount(), this.condensate.temperature());
            this.condensate.extract(FluidType.WATER, this.lastDrained, false);
        }

        this.effects(level, pos, front, config);
        if (level.getGameTime() % 20L == 0L) this.setChanged();
    }

    /**
     * A plume of vapour rising high above the condenser, like over a cooling tower: the long-lived smoke of a
     * signal fire, a thin trail at light load, a steady column at full load, and a thick, fast plume with a
     * hiss when the steam backs up. Each puff starts at one of its sides (a turbine may sit on its top).
     */
    private void effects(ServerLevel level, BlockPos pos, Direction front, NuclearConfig config) {
        double load = config.condenserRate > 0.0 ? Math.min(1.0, this.lastCondensed / config.condenserRate) : 0.0;
        boolean overloaded = this.rise > 0.8;
        if (load <= 1.0e-3 && !overloaded) return;
        // The plume lives for about 15 seconds, so a puff every few ticks makes a tall column.
        int period = overloaded ? 4 : Math.max(5, 20 - (int) Math.round(15.0 * load));
        if (level.getGameTime() % period == 0L) {
            int puffs = overloaded ? 2 : 1;
            Direction left = front.getCounterClockWise();
            for (int i = 0; i < puffs; i++) {
                Direction side = level.getRandom().nextBoolean() ? left : left.getOpposite();
                double along = (level.getRandom().nextDouble() - 0.5) * 0.8;
                double x = pos.getX() + 0.5 + side.getStepX() * 0.6 + front.getStepX() * along;
                double z = pos.getZ() + 0.5 + side.getStepZ() * 0.6 + front.getStepZ() * along;
                double speed = overloaded ? 0.12 : 0.06 + 0.04 * load;
                level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x, pos.getY() + 0.9, z, 0, 0.0, 1.0, 0.0, speed);
            }
        }
        if (overloaded) {
            if (Sounds.due(level, pos, 25, 5)) Sounds.machine(level, pos, Sounds.steamHiss(), 0.3f, 0.6f);
        } else if (load > 0.05 && Sounds.due(level, pos, 80, 20)) {
            Sounds.machine(level, pos, Sounds.waterGurgle(), 0.15f + 0.15f * (float) load, 0.7f);
        }
    }

    /**
     * Pressure (bar) of the exhaust steam it gets.
     */
    public double pressure() {
        return Steam.VACUUM + (Steam.ATMOSPHERIC - Steam.VACUUM) * this.rise;
    }

    /**
     * For temperature sensors: the steam in it (or, without any, its cooling water).
     */
    @Override
    public double temperature() {
        return this.steam.amount() > 1.0 ? this.steam.temperature() : this.cold.temperature();
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, boolean simulate) {
        return this.insert(side, type, amount, type.defaultTemperature(), simulate);
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, double temperature, boolean simulate) {
        if (side == STEAM_IN && type == FluidType.STEAM) return this.steam.insert(type, amount, temperature, simulate);
        if (side == this.front().getOpposite() && type == FluidType.WATER) return this.cold.insert(type, amount, temperature, simulate);
        return 0.0;
    }

    @Override
    public double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate) {
        if (type != FluidType.WATER) return 0.0;
        if (side == this.front()) return this.warm.extract(type, max, simulate);
        if (side == CONDENSATE_OUT) return this.condensate.extract(type, max, simulate);
        return 0.0;
    }

    @Override
    public double temperature(Direction side, FluidType type) {
        if (type == FluidType.WATER && side == this.front()) return this.warm.temperature();
        if (type == FluidType.WATER && side == CONDENSATE_OUT) return this.condensate.temperature();
        if (type == FluidType.STEAM && side == STEAM_IN) return this.steam.temperature();
        return type.defaultTemperature();
    }

    @Override
    public @Nullable FluidType available(Direction side, boolean pumped) {
        if (side == this.front()) return this.warm.amount() > 0.0 ? FluidType.WATER : null;
        if (side == CONDENSATE_OUT) return this.condensate.amount() > 0.0 ? FluidType.WATER : null;
        return null;
    }

    @Override
    public @Nullable FluidType offers(Direction side, boolean pumped) {
        return side == this.front() || side == CONDENSATE_OUT ? FluidType.WATER : null;
    }

    /**
     * How full the steam side is (turbines above feel it as back-pressure), or the cooling water side (for a
     * check valve feeding it).
     */
    @Override
    public double fillLevel(Direction side) {
        if (side == STEAM_IN) return this.steam.fill();
        if (side == this.front().getOpposite()) return this.cold.fill();
        return -1.0;
    }

    @Override
    public void appendInfo(List<Component> lines) {
        NuclearConfig config = NuclearConfig.get();
        Direction front = this.front();
        lines.add(Fmt.title("Condenser"));
        lines.add(Fmt.label("Condensing", Fmt.rate(this.lastCondensed) + " of steam (rated " + Fmt.rate(config.condenserRate)
                + ", up to twice that while the steam backs up)", ChatFormatting.AQUA));
        lines.add(Fmt.label("Exhaust steam (in: top)", Fmt.mb(this.steam.amount()) + " / " + Fmt.mb(this.steam.capacity()) + ", "
                + Fmt.bar(this.pressure()) + (this.rise <= 0.0 ? " (vacuum)" : " (backing up)"), this.rise > 0.8 ? ChatFormatting.RED : ChatFormatting.WHITE));
        lines.add(Fmt.label("Cooling water (in: back, " + front.getOpposite().getName() + ")", Fmt.mb(this.cold.amount()) + " / "
                + Fmt.mb(this.cold.capacity()) + " (needs " + Fmt.rate(config.condenserRate * config.condenserCoolingWater) + " at full load)",
                ChatFormatting.BLUE));
        lines.add(Fmt.label("Cooling water out (front, " + front.getName() + ")", Fmt.mb(this.warm.amount()) + " / " + Fmt.mb(this.warm.capacity())
                + " at " + Fmt.temp(this.warm.temperature()), this.warm.fill() > 0.9 ? ChatFormatting.RED : ChatFormatting.AQUA));
        lines.add(Fmt.label("Condensate (out: bottom)", Fmt.mb(this.condensate.amount()) + " / " + Fmt.mb(this.condensate.capacity())
                + ", draining " + Fmt.rate(this.lastDrained), ChatFormatting.BLUE));
        if (this.steam.amount() > 1.0 && this.cold.amount() < 1.0e-3 && config.condenserCoolingWater > 0.0) {
            lines.add(Component.literal("No cooling water: pump river water into the back.").withStyle(ChatFormatting.RED));
        }
        if (this.warm.fill() > 0.9) {
            lines.add(Component.literal("The cooling water can't get out of the front: pipe it to a Water Outlet.").withStyle(ChatFormatting.RED));
        }
        if (this.otherFluidBelow) {
            lines.add(Component.literal("The pipe below carries steam or coolant, so the condensate can't drain into it (one fluid per pipe).")
                    .withStyle(ChatFormatting.GOLD));
        } else if (this.condensate.fill() > 0.9) {
            lines.add(Component.literal("Condensate is backing up: pipe it from the bottom to the heat exchangers' feedwater or to a Water Outlet.")
                    .withStyle(ChatFormatting.GOLD));
        }
        if (this.rise > 0.0) {
            lines.add(Component.literal("Steam is backing up: more exhaust than the condensers can take at a vacuum, so the turbines lose"
                    + " power. Add Condensers (about one per turbine) or cooling water.").withStyle(ChatFormatting.GOLD));
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        this.steam.save(output, "steam");
        this.cold.save(output, "cold");
        this.warm.save(output, "warm");
        this.condensate.save(output, "condensate");
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.steam.load(input, "steam");
        this.cold.load(input, "cold");
        this.warm.load(input, "warm");
        this.condensate.load(input, "condensate");
    }
}

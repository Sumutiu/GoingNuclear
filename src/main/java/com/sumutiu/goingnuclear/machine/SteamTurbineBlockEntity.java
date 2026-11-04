package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidAccess;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import com.sumutiu.goingnuclear.fluid.FluidTank;
import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.fluid.Steam;
import com.sumutiu.goingnuclear.power.ElectricBlockEntity;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Steam turbine with generator. It follows the load like a turbine with a governor: the grid tells it how much
 * power to make each tick (its even share of the consumption plus battery charging, see PowerNetwork), and it
 * only takes the steam it needs for that. Steam it does not need stays in its buffer and backs up the line.
 * <p>
 * One job per face (see {@link SteamTurbineBlock}): live steam in at the top, exhaust steam out at the
 * bottom, power out on the left, right and back, and the rotor on the front. What one mB of steam gives
 * depends on:
 * <ul>
 *     <li>the live steam's temperature ({@link Steam#temperatureFactor}): hotter steam, more power;</li>
 *     <li>the exhaust's back-pressure ({@link Steam#exhaustFactor}): a Condenser below (directly or through a
 *     pipe) keeps a vacuum, which gives full power. As the exhaust line fills up (not enough condensing, or
 *     no cooling water) the back-pressure rises and the power per mB drops. With nothing below that takes
 *     the exhaust, or a full exhaust line, the steam goes into the open air: half power, and its water is
 *     lost.</li>
 * </ul>
 * A small share of the steam it uses ({@code turbineSteamLoss}) leaks out at the shaft seals instead of going
 * on as exhaust, so a closed secondary loop slowly runs short and needs make-up water.
 */
public class SteamTurbineBlockEntity extends ElectricBlockEntity implements MachineBlock.TickingMachine, FluidHandler, InfoProvider, TemperatureSource {
    private static final Direction STEAM_IN = Direction.UP;
    private static final Direction EXHAUST_OUT = Direction.DOWN;

    /**
     * Live steam on its way through: a few ticks' worth at full flow, so a turbine stops within seconds when the
     * steam stops (like a real one) instead of running on for minutes from a store of old steam.
     */
    private final FluidTank steam = new FluidTank(FluidType.STEAM, 100.0);
    /**
     * Exhaust waiting to get away; when it is full, the rest goes out through the relief into the air.
     */
    private final FluidTank exhaust = new FluidTank(FluidType.STEAM, 500.0);
    private double spin;
    private double produced;
    private double lastOutput;
    private double lastSteam;
    private double lastVented;
    /**
     * Steam lost at the shaft seals last tick.
     */
    private double lastLeak;
    /**
     * Back-pressure (bar) at the exhaust, from how backed up it is.
     */
    private double exhaustPressure = Steam.VACUUM;
    /**
     * Nothing below takes the exhaust: it goes straight into the air.
     */
    private boolean openExhaust;
    /**
     * The pipe below carries water or coolant, so the exhaust is kept out of it.
     */
    private boolean otherFluidBelow;
    /**
     * Energy the grid asked for this tick (set by the grid at the end of the previous tick).
     */
    private double loadTarget;

    public SteamTurbineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STEAM_TURBINE, pos, state);
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        NuclearConfig config = NuclearConfig.get();
        // Live steam comes in at the top, with its temperature (turbines on one steam pipe share it evenly).
        double room = Math.min(this.steam.space(), config.turbineSteamRate * 2.0);
        if (room > 0.0) {
            double temperature = FluidAccess.temperatureAt(level, pos, STEAM_IN, FluidType.STEAM);
            double got = FluidAccess.pull(level, pos, STEAM_IN, FluidType.STEAM, room, false);
            this.steam.insert(FluidType.STEAM, got, temperature, false);
        }
        this.checkExhaust(level, pos);

        // Governor: only the steam needed for what the grid asked for (never more than the turbine's rate).
        double perSteam = this.energyPerSteam(config);
        double wanted = perSteam > 0.0 ? Math.min(config.turbineSteamRate, this.loadTarget / perSteam) : 0.0;
        double use = Math.min(this.steam.amount(), wanted);
        this.steam.extract(FluidType.STEAM, use, false);
        // The rotor stays up to speed as long as it turns at all; with no load at all it winds down.
        if (use > 1.0e-4) {
            this.spin = Math.min(1.0, this.spin + 0.01);
        } else {
            this.spin = Math.max(0.0, this.spin - 0.02);
        }
        double energy = use * perSteam;
        this.produced += energy;
        this.lastOutput = energy;
        this.lastSteam = use;

        // The used steam leaves at the bottom as exhaust, less what leaks out at the shaft seals; what cannot get
        // away goes into the air.
        this.lastLeak = use * config.turbineSteamLoss;
        double exhaustSteam = use - this.lastLeak;
        double exhaustTemp = Steam.temperature(this.exhaustPressure);
        this.lastVented = 0.0;
        if (this.openExhaust) {
            this.lastVented = exhaustSteam + this.exhaust.amount();
            this.exhaust.set(0.0);
        } else {
            double kept = this.exhaust.insert(FluidType.STEAM, exhaustSteam, exhaustTemp, false);
            this.lastVented = exhaustSteam - kept;
            if (this.exhaust.amount() > 0.0 && !this.otherFluidBelow) {
                double pushed = FluidAccess.push(level, pos, EXHAUST_OUT, FluidType.STEAM, this.exhaust.amount(), this.exhaust.temperature());
                this.exhaust.extract(FluidType.STEAM, pushed, false);
            }
        }

        if (this.spin > 0.2 && use > 1.0e-4 && level.getGameTime() % 8L == 0L) {
            level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 1, 0.2, 0.05, 0.2, 0.01);
        }
        if (this.lastVented > 1.0e-3) this.ventEffects(level, pos, config);
        if (this.spin > 0.05) this.sounds(level, pos, config, use);
        if (level.getGameTime() % 20L == 0L) this.setChanged();
    }

    /**
     * Where the exhaust can go and how much back-pressure that gives: a vacuum while what is below (a
     * condenser, or the pipe to one) takes it all, rising towards the open air's pressure as that fills up
     * past half full.
     */
    private void checkExhaust(ServerLevel level, BlockPos pos) {
        FluidHandler below = FluidAccess.get(level, pos.relative(EXHAUST_OUT), EXHAUST_OUT.getOpposite());
        this.openExhaust = below == null;
        this.otherFluidBelow = !this.openExhaust && FluidAccess.isOtherFluidLine(level, pos, EXHAUST_OUT, FluidType.STEAM);
        if (this.openExhaust) {
            this.exhaustPressure = Steam.ATMOSPHERIC;
            return;
        }
        double backlog = Math.max(this.exhaust.fill(), Math.max(0.0, below.fillLevel(EXHAUST_OUT.getOpposite())));
        double rise = Math.clamp((backlog - 0.5) / 0.5, 0.0, 1.0);
        this.exhaustPressure = Steam.VACUUM + (Steam.ATMOSPHERIC - Steam.VACUUM) * rise;
    }

    /**
     * A plume of steam around the bottom of a turbine that blows its exhaust into the air, and its hiss.
     */
    private void ventEffects(ServerLevel level, BlockPos pos, NuclearConfig config) {
        float amount = config.turbineSteamRate > 0.0 ? (float) Math.min(1.0, this.lastVented / config.turbineSteamRate) : 1.0f;
        if (level.getGameTime() % 3L == 0L) {
            int puffs = 1 + Math.round(2.0f * amount);
            for (int i = 0; i < puffs; i++) {
                double x = pos.getX() + 0.5 + (level.getRandom().nextDouble() - 0.5) * 1.2;
                double z = pos.getZ() + 0.5 + (level.getRandom().nextDouble() - 0.5) * 1.2;
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, pos.getY() + 0.2, z, 0, 0.0, 1.0, 0.0, 0.06);
            }
        }
        if (Sounds.due(level, pos, 20, 7)) {
            Sounds.machine(level, pos, Sounds.steamHiss(), 0.2f + 0.3f * amount, 0.5f + 0.15f * level.getRandom().nextFloat());
        }
    }

    /**
     * The rotor whines louder and higher as it spins up (and winds down when the steam stops), with a hiss
     * of steam while steam is going through it.
     */
    private void sounds(ServerLevel level, BlockPos pos, NuclearConfig config, double use) {
        float spin = (float) this.spin;
        float load = config.turbineSteamRate > 0.0 ? (float) Math.min(1.0, use / config.turbineSteamRate) : 0.0f;
        if (Sounds.due(level, pos, 40)) {
            // Higher as it spins up, louder with more load.
            Sounds.machine(level, pos, Sounds.rotorWhine(), (0.15f + 0.35f * spin) * (0.5f + 0.5f * load) + 0.1f * load, 0.9f + spin);
        }
        if (use > 0.01 && Sounds.due(level, pos, 30, 15)) {
            float steam = config.turbineSteamRate > 0.0 ? (float) Math.min(1.0, use / config.turbineSteamRate) : 1.0f;
            Sounds.machine(level, pos, Sounds.steamHiss(), 0.08f + 0.12f * steam, 0.55f + 0.2f * level.getRandom().nextFloat());
        }
    }

    /**
     * Energy one mB of steam gives right now: less while the rotor spins up, more with hotter steam, less
     * with back-pressure at the exhaust.
     */
    private double energyPerSteam(NuclearConfig config) {
        return config.steamEnergy * config.turbineEfficiency * (0.25 + 0.75 * this.spin)
                * Steam.temperatureFactor(this.steam.temperature()) * Steam.exhaustFactor(this.exhaustPressure);
    }

    @Override
    public double powerOutput() {
        return this.produced;
    }

    @Override
    public boolean followsLoad() {
        return true;
    }

    /**
     * What it could make next tick with the steam it has (a turbine without steam leaves its part to the others).
     */
    @Override
    public double loadCapacity() {
        NuclearConfig config = NuclearConfig.get();
        return Math.min(config.turbineSteamRate, this.steam.amount()) * this.energyPerSteam(config);
    }

    /**
     * Whether some of its exhaust went into the air last tick (nothing below takes it, or the line is full).
     */
    public boolean isVenting() {
        return this.lastVented > 1.0e-6;
    }

    @Override
    public void setLoadTarget(double energy) {
        this.loadTarget = Math.max(0.0, energy);
    }

    @Override
    public void clearOutput() {
        this.produced = 0.0;
    }

    /**
     * For temperature sensors: the live steam it holds (or, without any, the rotor warming up).
     */
    @Override
    public double temperature() {
        return this.steam.amount() > 1.0 ? this.steam.temperature() : 20.0 + 80.0 * this.spin;
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, boolean simulate) {
        return this.insert(side, type, amount, type.defaultTemperature(), simulate);
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, double temperature, boolean simulate) {
        // Live steam only goes in at the top.
        if (type != FluidType.STEAM || side != STEAM_IN) return 0.0;
        return this.steam.insert(type, amount, temperature, simulate);
    }

    @Override
    public double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate) {
        if (side != EXHAUST_OUT || type != FluidType.STEAM) return 0.0;
        return this.exhaust.extract(type, max, simulate);
    }

    @Override
    public double temperature(Direction side, FluidType type) {
        if (side == EXHAUST_OUT && type == FluidType.STEAM) return this.exhaust.temperature();
        return type.defaultTemperature();
    }

    @Override
    public @Nullable FluidType available(Direction side, boolean pumped) {
        return side == EXHAUST_OUT && this.exhaust.amount() > 0.0 ? FluidType.STEAM : null;
    }

    @Override
    public @Nullable FluidType offers(Direction side, boolean pumped) {
        return side == EXHAUST_OUT ? FluidType.STEAM : null;
    }

    @Override
    public void appendInfo(List<Component> lines) {
        NuclearConfig config = NuclearConfig.get();
        Direction front = this.getBlockState().getValue(SteamTurbineBlock.FACING);
        lines.add(Fmt.title("Steam Turbine"));
        lines.add(Fmt.label("Output", Fmt.power(this.lastOutput) + " (grid asks " + Fmt.power(this.loadTarget) + ", max "
                + Fmt.power(this.loadCapacity()) + ")", ChatFormatting.GREEN));
        lines.add(Fmt.label("Steam use", Fmt.rate(this.lastSteam) + " (max " + Fmt.rate(config.turbineSteamRate) + ")", ChatFormatting.WHITE));
        if (config.turbineSteamLoss > 0.0) {
            lines.add(Fmt.label("Shaft seal leak", Fmt.num(this.lastLeak, 2) + " mB/t (" + Fmt.num(config.turbineSteamLoss * 100.0, 1)
                    + "% of the steam used; make-up water replaces it)", ChatFormatting.GRAY));
        }
        lines.add(Fmt.label("Rotor speed", Fmt.pct(this.spin), ChatFormatting.AQUA));
        lines.add(Fmt.label("Live steam (in: top)", Fmt.mb(this.steam.amount()) + " / " + Fmt.mb(this.steam.capacity())
                + (this.steam.amount() > 1.0 ? " at " + Fmt.steam(this.steam.temperature()) : ""), ChatFormatting.WHITE));
        String exhaust = this.openExhaust ? "into the air, " + Fmt.bar(this.exhaustPressure)
                : Fmt.bar(this.exhaustPressure) + (this.exhaustPressure <= Steam.VACUUM + 1.0e-3 ? " (condenser vacuum)" : " (backing up)");
        lines.add(Fmt.label("Exhaust (out: bottom)", exhaust, this.exhaustPressure <= Steam.VACUUM + 1.0e-3 ? ChatFormatting.AQUA : ChatFormatting.GOLD));
        lines.add(Fmt.label("Power per mB", Fmt.num(this.energyPerSteam(config), 0) + " J (steam temperature "
                + Fmt.pct(Steam.temperatureFactor(this.steam.temperature())) + ", exhaust " + Fmt.pct(Steam.exhaustFactor(this.exhaustPressure))
                + ", rotor " + Fmt.pct(0.25 + 0.75 * this.spin) + ")", ChatFormatting.YELLOW));
        lines.add(Fmt.label("Power out (red ports)", front.getCounterClockWise().getName() + ", " + front.getOpposite().getName() + ", "
                + front.getClockWise().getName() + " (passed on to turbines beside it); rotor: front", ChatFormatting.YELLOW));
        if (this.openExhaust) {
            lines.add(Component.literal("Nothing below takes the exhaust, so it goes into the air: half the power per mB of steam, and its"
                    + " water is lost. Put a Condenser under the turbine (directly or through a pipe).").withStyle(ChatFormatting.GOLD));
        } else if (this.otherFluidBelow) {
            lines.add(Component.literal("The pipe below carries water or coolant, so the exhaust can't go into it (one fluid per pipe) and"
                    + " goes into the air instead. Give the exhaust its own pipe to a Condenser.").withStyle(ChatFormatting.GOLD));
        } else if (this.isVenting()) {
            lines.add(Component.literal("The exhaust line is full: not enough Condensers, or they have no cooling water (or what is below"
                    + " takes no steam). The rest goes into the air.").withStyle(ChatFormatting.GOLD));
        } else if (this.exhaustPressure > Steam.VACUUM + 1.0e-3) {
            lines.add(Component.literal("The exhaust is backing up: the Condensers can't keep up, so the power per mB drops. Add Condensers"
                    + " or cooling water.").withStyle(ChatFormatting.GOLD));
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        this.steam.save(output, "steam");
        this.exhaust.save(output, "exhaust");
        output.putDouble("spin", this.spin);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.steam.load(input, "steam");
        this.exhaust.load(input, "exhaust");
        this.spin = input.getDoubleOr("spin", 0.0);
    }
}

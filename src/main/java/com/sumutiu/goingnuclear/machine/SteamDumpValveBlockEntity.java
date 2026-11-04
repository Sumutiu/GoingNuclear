package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidAccess;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import com.sumutiu.goingnuclear.fluid.FluidTank;
import com.sumutiu.goingnuclear.fluid.FluidType;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Steam dump (turbine bypass): sends live steam around the turbines, straight into the exhaust line and the
 * Condensers, so the reactor can shed heat without making electricity (to cool it down faster, or when the
 * grid needs less than the reactor makes). Live steam in at the top, dumped steam out at the back, into a pipe
 * to the turbines' exhaust line or a Condenser's top.
 * <p>
 * It takes up to {@code steamDumpRate} mB/t (two turbines' worth by default). In {@link Mode#AUTO} it opens by
 * itself when the steam coming in is hotter than its setting, fully {@link #PROPORTIONAL_BAND} degrees above
 * it; it can also be held {@link Mode#CLOSED} or {@link Mode#OPEN}. Like a turbine it feels the back-pressure of
 * its exhaust: as the exhaust line backs up (the Condensers cannot keep up) less steam gets through. With
 * nothing behind it that takes steam it dumps into the air (and that water is lost).
 */
public class SteamDumpValveBlockEntity extends BlockEntity implements MachineBlock.TickingMachine, FluidHandler, InfoProvider, TemperatureSource {
    public static final Direction STEAM_IN = Direction.UP;
    public static final double MIN_SETTING = 100.0;
    public static final double MAX_SETTING = 700.0;
    /**
     * Degrees above the setting at which the valve is fully open.
     */
    public static final double PROPORTIONAL_BAND = 10.0;
    /**
     * How far the valve can move in one tick (a full stroke takes 2.5 s).
     */
    private static final double STROKE_PER_TICK = 0.02;
    /**
     * Dumping without a break for this long in automatic mode is worth a warning (two minutes).
     */
    private static final int LONG_DUMP_TICKS = 2400;

    public enum Mode {
        AUTO("Auto", "opens by itself above the setting"),
        CLOSED("Closed", "never opens"),
        OPEN("Open", "dumps at full flow");

        private final String title;
        private final String description;

        Mode(String title, String description) {
            this.title = title;
            this.description = description;
        }

        public String title() {
            return this.title;
        }

        public String description() {
            return this.description;
        }

        public Mode next() {
            return values()[(this.ordinal() + 1) % values().length];
        }
    }

    private final FluidTank steam = new FluidTank(FluidType.STEAM, 200.0);
    /**
     * Dumped steam waiting to get away down the exhaust line.
     */
    private final FluidTank exhaust = new FluidTank(FluidType.STEAM, 500.0);
    private Mode mode = Mode.AUTO;
    private double setting = 400.0;
    /**
     * How far the valve is open (0..1).
     */
    private double opening;
    private double inletTemperature = FluidType.STEAM.defaultTemperature();
    private double exhaustPressure = Steam.VACUUM;
    /**
     * Share of the open valve's flow the back-pressure still lets through (1 = all of it).
     */
    private double flowFactor = 1.0;
    private boolean openExhaust;
    private boolean otherFluidBehind;
    private double lastDumped;
    private double lastVented;
    private int dumpingTicks;

    public SteamDumpValveBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STEAM_DUMP_VALVE, pos, state);
    }

    /**
     * Dumped steam goes out of the back (the side opposite the handwheel).
     */
    private Direction dumpOut() {
        return this.getBlockState().getValue(SteamDumpValveBlock.FACING).getOpposite();
    }

    // ---------- Settings ----------

    public Mode mode() {
        return this.mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        this.dumpingTicks = 0;
        this.setChanged();
    }

    public double setting() {
        return this.setting;
    }

    public void setSetting(double value) {
        this.setting = Math.clamp(value, MIN_SETTING, MAX_SETTING);
        this.dumpingTicks = 0;
        this.setChanged();
    }

    // ---------- Readings ----------

    public double opening() {
        return this.opening;
    }

    public double lastDumped() {
        return this.lastDumped;
    }

    public double inletTemperature() {
        return this.inletTemperature;
    }

    public double exhaustPressure() {
        return this.exhaustPressure;
    }

    public double flowFactor() {
        return this.flowFactor;
    }

    public boolean dumpsIntoTheAir() {
        return this.openExhaust || this.otherFluidBehind;
    }

    /**
     * Whether it has been dumping steam in automatic mode for a long time without a break: the setting is
     * probably below the steam the reactor makes, so the reactor keeps replacing the heat it throws away.
     */
    public boolean dumpingForLong() {
        return this.mode == Mode.AUTO && this.dumpingTicks >= LONG_DUMP_TICKS;
    }

    /**
     * How far the valve wants to be open right now.
     */
    private double wantedOpening() {
        return switch (this.mode) {
            case CLOSED -> 0.0;
            case OPEN -> 1.0;
            case AUTO -> Math.clamp((this.inletTemperature - this.setting) / PROPORTIONAL_BAND, 0.0, 1.0);
        };
    }

    // ---------- Running ----------

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        NuclearConfig config = NuclearConfig.get();
        this.inletTemperature = FluidAccess.temperatureAt(level, pos, STEAM_IN, FluidType.STEAM);
        double step = Math.clamp(this.wantedOpening() - this.opening, -STROKE_PER_TICK, STROKE_PER_TICK);
        this.opening = Math.clamp(this.opening + step, 0.0, 1.0);
        if (this.opening < 1.0e-3) this.opening = 0.0;
        this.checkExhaust(level, pos);

        // What may go through this tick: the opening, less as the exhaust backs up, and never more than the
        // exhaust line has room for (dumping into the air has no such limit).
        double allowed = this.opening * config.steamDumpRate * this.flowFactor;
        if (!this.dumpsIntoTheAir()) allowed = Math.min(allowed, this.exhaust.space());
        // Steam comes in at the top only while the valve is open, so a shut valve takes no share of the line.
        double room = Math.min(this.steam.space(), allowed);
        if (room > 1.0e-6) {
            double got = FluidAccess.pull(level, pos, STEAM_IN, FluidType.STEAM, room, false);
            this.steam.insert(FluidType.STEAM, got, this.inletTemperature, false);
        }
        double dumped = this.steam.extract(FluidType.STEAM, Math.min(this.steam.amount(), allowed), false);
        this.lastDumped = dumped;
        this.lastVented = 0.0;
        if (this.dumpsIntoTheAir()) {
            this.lastVented = dumped + this.exhaust.amount();
            this.exhaust.set(0.0);
        } else {
            this.exhaust.insert(FluidType.STEAM, dumped, Steam.temperature(this.exhaustPressure), false);
            if (this.exhaust.amount() > 0.0) {
                double pushed = FluidAccess.push(level, pos, this.dumpOut(), FluidType.STEAM, this.exhaust.amount(), this.exhaust.temperature());
                this.exhaust.extract(FluidType.STEAM, pushed, false);
            }
        }
        this.dumpingTicks = dumped > 1.0e-3 ? this.dumpingTicks + 1 : 0;

        int look = this.opening <= 0.0 ? 0 : this.opening >= 0.99 ? 2 : 1;
        if (state.getValue(SteamDumpValveBlock.OPENING) != look) {
            level.setBlock(pos, state.setValue(SteamDumpValveBlock.OPENING, look), Block.UPDATE_ALL);
        }
        if (dumped > 1.0e-3) this.effects(level, pos, config, dumped);
        if (level.getGameTime() % 20L == 0L) this.setChanged();
    }

    /**
     * Back-pressure at the back, the same way a turbine feels it: a vacuum while what is behind takes it all,
     * rising towards the open air's pressure as the exhaust line fills up past half full; the flow falls with
     * it, to nothing when the line is full.
     */
    private void checkExhaust(ServerLevel level, BlockPos pos) {
        Direction out = this.dumpOut();
        FluidHandler behind = FluidAccess.get(level, pos.relative(out), out.getOpposite());
        this.openExhaust = behind == null;
        this.otherFluidBehind = !this.openExhaust && FluidAccess.isOtherFluidLine(level, pos, out, FluidType.STEAM);
        if (behind == null || this.otherFluidBehind) {
            this.exhaustPressure = Steam.ATMOSPHERIC;
            this.flowFactor = 1.0;
            return;
        }
        double backlog = Math.max(this.exhaust.fill(), Math.max(0.0, behind.fillLevel(out.getOpposite())));
        double rise = Math.clamp((backlog - 0.5) / 0.5, 0.0, 1.0);
        this.exhaustPressure = Steam.VACUUM + (Steam.ATMOSPHERIC - Steam.VACUUM) * rise;
        this.flowFactor = 1.0 - rise;
    }

    /**
     * Steam rushing through (louder the more goes through), and a plume blowing out of the back when it dumps
     * into the air.
     */
    private void effects(ServerLevel level, BlockPos pos, NuclearConfig config, double dumped) {
        float flow = config.steamDumpRate > 0.0 ? (float) Math.min(1.0, dumped / config.steamDumpRate) : 1.0f;
        if (Sounds.due(level, pos, 16)) {
            Sounds.machine(level, pos, Sounds.steamHiss(), 0.15f + 0.45f * flow, 0.45f + 0.15f * level.getRandom().nextFloat());
        }
        if (this.lastVented > 1.0e-3 && level.getGameTime() % 2L == 0L) {
            Direction out = this.dumpOut();
            int puffs = 1 + Math.round(3.0f * flow);
            for (int i = 0; i < puffs; i++) {
                double across = (level.getRandom().nextDouble() - 0.5) * 0.8;
                double x = pos.getX() + 0.5 + out.getStepX() * 0.6 + out.getStepZ() * across;
                double z = pos.getZ() + 0.5 + out.getStepZ() * 0.6 + out.getStepX() * across;
                double y = pos.getY() + 0.3 + level.getRandom().nextDouble() * 0.4;
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 0, out.getStepX(), 0.6, out.getStepZ(), 0.08);
            }
        } else if (level.getGameTime() % 10L == 0L) {
            level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5, 1, 0.2, 0.0, 0.2, 0.01);
        }
    }

    // ---------- Fluids ----------

    @Override
    public double temperature() {
        return this.inletTemperature;
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, boolean simulate) {
        return this.insert(side, type, amount, type.defaultTemperature(), simulate);
    }

    /**
     * Takes no steam pushed into it: it only draws what it lets through, so a shut valve holds nothing back
     * from the turbines.
     */
    @Override
    public double insert(Direction side, FluidType type, double amount, double temperature, boolean simulate) {
        return 0.0;
    }

    @Override
    public double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate) {
        if (side != this.dumpOut() || type != FluidType.STEAM) return 0.0;
        return this.exhaust.extract(type, max, simulate);
    }

    @Override
    public double temperature(Direction side, FluidType type) {
        if (side == this.dumpOut() && type == FluidType.STEAM) return this.exhaust.temperature();
        if (side == STEAM_IN && type == FluidType.STEAM) return this.inletTemperature;
        return type.defaultTemperature();
    }

    @Override
    public @Nullable FluidType available(Direction side, boolean pumped) {
        return side == this.dumpOut() && this.exhaust.amount() > 0.0 ? FluidType.STEAM : null;
    }

    @Override
    public @Nullable FluidType offers(Direction side, boolean pumped) {
        return side == this.dumpOut() ? FluidType.STEAM : null;
    }

    // ---------- Probe ----------

    @Override
    public void appendInfo(List<Component> lines) {
        NuclearConfig config = NuclearConfig.get();
        lines.add(Fmt.title("Steam Dump Valve"));
        lines.add(Fmt.label("Mode", this.mode.title() + " (" + this.mode.description() + ")", ChatFormatting.AQUA));
        if (this.mode == Mode.AUTO) {
            lines.add(Fmt.label("Setting", "opens above " + Fmt.steam(this.setting) + ", fully open at "
                    + Fmt.temp(this.setting + PROPORTIONAL_BAND), ChatFormatting.WHITE));
        }
        lines.add(Fmt.label("Steam in (top)", Fmt.steam(this.inletTemperature), Fmt.tempColor(this.inletTemperature)));
        lines.add(Fmt.label("Valve", Fmt.pct(this.opening) + " open, dumping " + Fmt.rate(this.lastDumped) + " (max "
                + Fmt.rate(config.steamDumpRate) + ")", this.lastDumped > 1.0e-3 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        lines.add(Fmt.label("Heat carried away", Fmt.power(this.lastDumped * heatPerSteam(config)), ChatFormatting.GOLD));
        String exhaust = this.dumpsIntoTheAir() ? "into the air" : Fmt.bar(this.exhaustPressure)
                + (this.flowFactor < 0.999 ? " (backing up: " + Fmt.pct(this.flowFactor) + " of the flow gets through)" : " (condenser vacuum)");
        lines.add(Fmt.label("Dump (out: back)", exhaust, this.flowFactor < 0.999 || this.dumpsIntoTheAir() ? ChatFormatting.GOLD : ChatFormatting.AQUA));
        lines.addAll(warnings());
    }

    /**
     * Heat the reactor loses with each mB of steam dumped (what it took to make it).
     */
    public static double heatPerSteam(NuclearConfig config) {
        double efficiency = Math.max(0.01, config.heatExchangerEfficiency);
        return config.boilHeatPerWater / Math.max(0.01, config.steamPerWater) / efficiency;
    }

    /**
     * Things the player should know, for the probe and the valve's screen.
     */
    public List<Component> warnings() {
        List<Component> lines = new ArrayList<>();
        if (this.otherFluidBehind) {
            lines.add(Component.literal("The pipe behind it carries water or coolant, so the steam can't go into it and goes into the air instead."
                    + " Give it its own pipe to the turbines' exhaust line or a Condenser.").withStyle(ChatFormatting.GOLD));
        } else if (this.openExhaust && this.lastDumped > 1.0e-3) {
            lines.add(Component.literal("Nothing behind it takes the steam, so it goes into the air and its water is lost."
                    + " Pipe the back to the turbines' exhaust line or a Condenser's top.").withStyle(ChatFormatting.GOLD));
        } else if (this.opening > 0.0 && this.flowFactor < 0.5) {
            lines.add(Component.literal("The exhaust line is backing up: the Condensers can't keep up, so less steam gets through."
                    + " Add Condensers or cooling water.").withStyle(ChatFormatting.GOLD));
        }
        if (this.dumpingForLong()) {
            lines.add(Component.literal("It has been dumping for over 2 minutes. If the reactor holds a temperature, the setting is"
                    + " probably below the steam it makes: the reactor keeps replacing the heat thrown away (wasting fuel)."
                    + " Set it above your normal steam temperature.").withStyle(ChatFormatting.RED));
        }
        return lines;
    }

    // ---------- Saving ----------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        this.steam.save(output, "steam");
        this.exhaust.save(output, "exhaust");
        output.putInt("mode", this.mode.ordinal());
        output.putDouble("setting", this.setting);
        output.putDouble("opening", this.opening);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.steam.load(input, "steam");
        this.exhaust.load(input, "exhaust");
        int mode = input.getIntOr("mode", 0);
        this.mode = mode >= 0 && mode < Mode.values().length ? Mode.values()[mode] : Mode.AUTO;
        this.setting = Math.clamp(input.getDoubleOr("setting", 400.0), MIN_SETTING, MAX_SETTING);
        this.opening = Math.clamp(input.getDoubleOr("opening", 0.0), 0.0, 1.0);
    }
}

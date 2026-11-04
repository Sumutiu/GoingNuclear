package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidAccess;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import com.sumutiu.goingnuclear.fluid.FluidTank;
import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import com.sumutiu.goingnuclear.util.TemperatureSource;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Heat exchanger with one job per face (see {@link HeatExchangerBlock}): coolant in at the top, feedwater in at
 * the bottom, steam out of the front, coolant out of the other sides. The coolant carries its temperature: on
 * its way through, it gives heat to the feedwater only while it is hotter than {@code heatExchangerMinTemp}
 * (by default 150 °C; colder water cannot boil the feedwater into useful steam), and leaves no colder than that.
 * Coolant that is too cold, or that finds no feedwater or no room for steam, just passes through unchanged.
 * The two fluids never mix. The steam comes out {@code steamApproachTemp} (20 °C) cooler than the coolant
 * that made it, so a hotter reactor gives hotter steam (at a higher pressure), which turbines get more out of.
 * A small share of the coolant going through ({@code heatExchangerCoolantLoss}) leaks away, so the primary loop
 * slowly runs short and needs make-up water.
 * <p>
 * Steam is pushed out, but the coolant is not: it waits until a Circulation Pump draws it from a side
 * (directly or through a pipe). Without a running pump the exchanger fills up and stops.
 */
public class HeatExchangerBlockEntity extends BlockEntity implements MachineBlock.TickingMachine, FluidHandler, InfoProvider, TemperatureSource {
    private static final Direction COOLANT_IN = Direction.UP;
    private static final Direction WATER_IN = Direction.DOWN;

    private final FluidTank hot = new FluidTank(FluidType.COOLANT, 1000.0);
    private final FluidTank cold = new FluidTank(FluidType.COOLANT, 1000.0);
    private final FluidTank feed = new FluidTank(FluidType.WATER, 2000.0);
    /**
     * Steam waiting to go out of the front (it is pushed into the line every tick, so a few ticks' worth is enough).
     */
    private final FluidTank steam = new FluidTank(FluidType.STEAM, 200.0);
    private double lastHeat;
    private double lastFlow;
    /**
     * Coolant that leaked away last tick.
     */
    private double lastLeak;
    /**
     * Coolant the pumps drew from the outlets: counted over one second, then kept as a rate (mB per tick).
     */
    private double drawnThisSecond;
    private double drawnRate;

    public HeatExchangerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HEAT_EXCHANGER, pos, state);
    }

    private Direction front() {
        return this.getBlockState().getValue(HeatExchangerBlock.FACING);
    }

    /**
     * The three sides that are neither the front, the top nor the bottom.
     */
    private boolean isColdOutlet(Direction side) {
        return side.getAxis().isHorizontal() && side != this.front();
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        NuclearConfig config = NuclearConfig.get();
        Direction front = state.getValue(HeatExchangerBlock.FACING);
        double rate = config.heatExchangerRate;

        // Coolant in through the top (with its temperature), feedwater in through the bottom.
        if (this.hot.space() > 0.0) {
            double temperature = FluidAccess.temperatureAt(level, pos, COOLANT_IN, FluidType.COOLANT);
            double got = FluidAccess.pull(level, pos, COOLANT_IN, FluidType.COOLANT, Math.min(this.hot.space(), rate), false);
            this.hot.insert(FluidType.COOLANT, got, temperature, false);
        }
        if (this.feed.space() > 0.0) {
            double got = FluidAccess.pull(level, pos, WATER_IN, FluidType.WATER, Math.min(this.feed.space(), rate * 2.0), false);
            this.feed.insert(FluidType.WATER, got, false);
        }

        // Coolant flows through (as long as there is room on the way out) and gives up the heat it has above
        // the minimum temperature, as far as there is feedwater and room for the steam.
        double flow = Math.min(Math.min(this.hot.amount(), rate), this.cold.space());
        double inTemp = this.hot.temperature();
        double heatCapacity = flow * config.coolantHeatCapacity;
        double heatAvailable = heatCapacity * Math.max(0.0, inTemp - config.heatExchangerMinTemp);
        double waterNeeded = heatAvailable * config.heatExchangerEfficiency / config.boilHeatPerWater;
        double water = Math.min(waterNeeded, Math.min(this.feed.amount(), this.steam.space() / config.steamPerWater));
        double heatTaken = waterNeeded > 0.0 ? heatAvailable * (water / waterNeeded) : 0.0;
        double outTemp = heatCapacity > 0.0 ? inTemp - heatTaken / heatCapacity : inTemp;
        // A little of the coolant going through leaks away; the make-up water replaces it.
        this.lastLeak = flow * config.heatExchangerCoolantLoss;
        this.hot.extract(FluidType.COOLANT, flow, false);
        this.cold.insert(FluidType.COOLANT, flow - this.lastLeak, outTemp, false);
        this.feed.extract(FluidType.WATER, water, false);
        double steamTemp = Math.max(100.0, inTemp - config.steamApproachTemp);
        this.steam.insert(FluidType.STEAM, water * config.steamPerWater, steamTemp, false);
        this.lastHeat = water * config.boilHeatPerWater;
        this.lastFlow = flow;

        // Steam out of the front. Coolant stays until a circulation pump draws it from a side.
        if (this.steam.amount() > 0.0) {
            double pushed = FluidAccess.push(level, pos, front, FluidType.STEAM, this.steam.amount(), this.steam.temperature());
            this.steam.extract(FluidType.STEAM, pushed, false);
        }
        if (level.getGameTime() % 20L == 0L) {
            this.drawnRate = this.drawnThisSecond / 20.0;
            this.drawnThisSecond = 0.0;
            this.setChanged();
        }
    }

    /**
     * For temperature sensors: the coolant coming in (or, without any, the coolant going out).
     */
    @Override
    public double temperature() {
        if (this.hot.amount() > 1.0) return this.hot.temperature();
        if (this.cold.amount() > 1.0) return this.cold.temperature();
        return 20.0;
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, boolean simulate) {
        return this.insert(side, type, amount, type.defaultTemperature(), simulate);
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, double temperature, boolean simulate) {
        if (side == COOLANT_IN && type == FluidType.COOLANT) return this.hot.insert(type, amount, temperature, simulate);
        if (side == WATER_IN && type == FluidType.WATER) return this.feed.insert(type, amount, simulate);
        return 0.0;
    }

    @Override
    public double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate) {
        if (side == this.front()) return type == FluidType.STEAM ? this.steam.extract(type, max, simulate) : 0.0;
        // Only a pump can draw the coolant out.
        if (pumped && this.isColdOutlet(side) && type == FluidType.COOLANT) {
            double taken = this.cold.extract(type, max, simulate);
            if (!simulate) this.drawnThisSecond += taken;
            return taken;
        }
        return 0.0;
    }

    @Override
    public double temperature(Direction side, FluidType type) {
        if (this.isColdOutlet(side) && type == FluidType.COOLANT) return this.cold.temperature();
        if (side == this.front() && type == FluidType.STEAM) return this.steam.temperature();
        return type.defaultTemperature();
    }

    @Override
    public @Nullable FluidType available(Direction side, boolean pumped) {
        if (side == this.front()) return this.steam.amount() > 0.0 ? FluidType.STEAM : null;
        if (this.isColdOutlet(side)) return pumped && this.cold.amount() > 0.0 ? FluidType.COOLANT : null;
        return null;
    }

    @Override
    public @Nullable FluidType offers(Direction side, boolean pumped) {
        if (side == this.front()) return FluidType.STEAM;
        return pumped && this.isColdOutlet(side) ? FluidType.COOLANT : null;
    }

    @Override
    public void appendInfo(List<Component> lines) {
        NuclearConfig config = NuclearConfig.get();
        Direction front = this.front();
        lines.add(Fmt.title("Heat Exchanger"));
        lines.add(Fmt.label("Heat transferred", Fmt.power(this.lastHeat) + " (" + Fmt.rate(this.lastFlow) + " of coolant)", ChatFormatting.GOLD));
        if (config.heatExchangerCoolantLoss > 0.0) {
            lines.add(Fmt.label("Coolant leak", Fmt.num(this.lastLeak, 2) + " mB/t (" + Fmt.num(config.heatExchangerCoolantLoss * 100.0, 1)
                    + "% of the coolant going through; make-up water replaces it)", ChatFormatting.GRAY));
        }
        lines.add(Fmt.label("Coolant in (top)", Fmt.mb(this.hot.amount()) + " / " + Fmt.mb(this.hot.capacity()) + " at "
                + Fmt.temp(this.hot.temperature()), Fmt.tempColor(this.hot.temperature())));
        lines.add(Fmt.label("Feedwater (in: bottom)", Fmt.mb(this.feed.amount()) + " / " + Fmt.mb(this.feed.capacity()), ChatFormatting.BLUE));
        lines.add(Fmt.label("Steam (out: " + front.getName() + ")", Fmt.mb(this.steam.amount()) + " / " + Fmt.mb(this.steam.capacity())
                + (this.steam.amount() > 1.0 ? " at " + Fmt.steam(this.steam.temperature()) : ""), ChatFormatting.WHITE));
        // Full but drawn away by the pumps just means the loop holds plenty of coolant; full and standing still
        // means the pumps are missing or blocked.
        boolean stuck = this.cold.fill() > 0.9 && this.drawnRate < 0.1;
        lines.add(Fmt.label("Coolant out (pump: " + front.getCounterClockWise().getName() + ", " + front.getOpposite().getName() + ", "
                        + front.getClockWise().getName() + ")", Fmt.mb(this.cold.amount()) + " / " + Fmt.mb(this.cold.capacity()) + " at "
                        + Fmt.temp(this.cold.temperature()) + ", pumps draw " + Fmt.rate(this.drawnRate),
                stuck ? ChatFormatting.RED : ChatFormatting.AQUA));
        if (this.hot.amount() > 1.0 && this.hot.temperature() <= config.heatExchangerMinTemp) {
            lines.add(Component.literal("Coolant too cold to make steam: it must be hotter than " + Fmt.temp(config.heatExchangerMinTemp) + ".")
                    .withStyle(ChatFormatting.GOLD));
        }
        if (stuck) {
            lines.add(Component.literal("Coolant out is full and the pumps draw none of it: a Circulation Pump must draw it from a side"
                    + " and send it to the reactor. If one is there, check it with the probe: it says what blocks it.").withStyle(ChatFormatting.RED));
        }
        if (this.feed.amount() <= 0.0 && this.hot.amount() > 0.0) {
            lines.add(Component.literal("No feedwater: pipe water into the bottom.").withStyle(ChatFormatting.RED));
        }
        if (this.steam.fill() > 0.9) {
            lines.add(Component.literal("Steam is backing up: the turbines need no more right now (or the front is not connected)."
                    + " The exchanger only takes the heat they use, so the coolant goes back hotter and the reactor turns down.")
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        this.hot.save(output, "hot");
        this.cold.save(output, "return");
        this.feed.save(output, "feed");
        this.steam.save(output, "steam");
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.hot.load(input, "hot");
        this.cold.load(input, "return");
        this.feed.load(input, "feed");
        this.steam.load(input, "steam");
    }
}

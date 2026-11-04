package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidAccess;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import com.sumutiu.goingnuclear.fluid.FluidTank;
import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.power.ElectricBlockEntity;
import com.sumutiu.goingnuclear.power.ElectricConnectable;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import com.sumutiu.goingnuclear.util.Sounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class WaterPumpBlockEntity extends ElectricBlockEntity implements MachineBlock.TickingMachine, FluidHandler, InfoProvider {
    private final FluidTank tank = new FluidTank(FluidType.WATER, 1000.0);
    private double demand;
    private int sources;
    private double lastRate;

    public WaterPumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.WATER_PUMP, pos, state);
    }

    private Direction output() {
        return this.getBlockState().getValue(WaterPumpBlock.FACING);
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        NuclearConfig config = NuclearConfig.get();
        Direction out = state.getValue(WaterPumpBlock.FACING);
        this.sources = 0;
        for (Direction dir : Direction.values()) {
            if (dir == out) continue;
            FluidState fluid = level.getFluidState(pos.relative(dir));
            if (fluid.is(FluidTags.WATER) && fluid.isSource()) this.sources++;
        }
        double potential = Math.min(config.waterPumpRate, this.sources * config.waterPerSourceBlock);
        boolean canWork = potential > 0.0 && this.tank.space() > 0.0;
        this.demand = canWork ? config.waterPumpPower : 0.0;
        this.lastRate = 0.0;
        if (canWork && this.powerFraction > 0.0) {
            this.lastRate = this.tank.insert(FluidType.WATER, potential * this.powerFraction, false);
        }
        if (this.tank.amount() > 0.0) {
            double pushed = FluidAccess.push(level, pos, out, FluidType.WATER, this.tank.amount());
            this.tank.extract(FluidType.WATER, pushed, false);
        }
        if (this.lastRate > 0.0) this.sounds(level, pos, config);
        if (level.getGameTime() % 20L == 0L) this.setChanged();
    }

    /**
     * While it pumps: a piston stroke every second (out, then back in) and the gurgle of water being drawn in,
     * both livelier with more flow.
     */
    private void sounds(ServerLevel level, BlockPos pos, NuclearConfig config) {
        float flow = config.waterPumpRate > 0.0 ? (float) Math.min(1.0, this.lastRate / config.waterPumpRate) : 1.0f;
        if (Sounds.due(level, pos, 20)) {
            Sounds.machine(level, pos, Sounds.pumpStroke(Sounds.due(level, pos, 40)), 0.2f, 0.5f + 0.2f * flow);
        }
        if (Sounds.due(level, pos, 40, 10)) {
            Sounds.machine(level, pos, Sounds.waterGurgle(), 0.35f + 0.25f * flow, 0.85f + 0.25f * flow);
        }
    }

    @Override
    public double powerDemand() {
        return this.demand;
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, boolean simulate) {
        return 0.0;
    }

    @Override
    public double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate) {
        if (side != this.output()) return 0.0;
        return this.tank.extract(type, max, simulate);
    }

    @Override
    public @Nullable FluidType available(Direction side, boolean pumped) {
        return side == this.output() && this.tank.amount() > 0.0 ? FluidType.WATER : null;
    }

    @Override
    public @Nullable FluidType offers(Direction side, boolean pumped) {
        return side == this.output() ? FluidType.WATER : null;
    }

    @Override
    public void appendInfo(List<Component> lines) {
        lines.add(Fmt.title("Water Pump"));
        lines.add(Fmt.label("Water sources", String.valueOf(this.sources), this.sources > 0 ? ChatFormatting.AQUA : ChatFormatting.RED));
        lines.add(Fmt.label("Pumping", Fmt.rate(this.lastRate), ChatFormatting.AQUA));
        lines.add(Fmt.label("Buffer", Fmt.mb(this.tank.amount()) + " / " + Fmt.mb(this.tank.capacity()), ChatFormatting.WHITE));
        lines.add(Fmt.label("Power", Fmt.pct(this.powerFraction) + " of " + Fmt.power(NuclearConfig.get().waterPumpPower), this.powerFraction >= 0.99 ? ChatFormatting.GREEN : ChatFormatting.RED));
        lines.add(Fmt.label("Output face", this.output().getName(), ChatFormatting.WHITE));
        BlockState state = this.getBlockState();
        Direction port = ElectricConnectable.powerPort(state.getValue(WaterPumpBlock.FACING), state.getValue(ElectricConnectable.POWER_PORT));
        lines.add(Fmt.label("Power in", "yellow port, " + port.getName() + " face", ChatFormatting.YELLOW));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        this.tank.save(output, "water");
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.tank.load(input, "water");
    }
}

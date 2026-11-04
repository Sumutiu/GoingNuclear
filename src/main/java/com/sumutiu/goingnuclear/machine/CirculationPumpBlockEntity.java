package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidAccess;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import com.sumutiu.goingnuclear.fluid.FluidTank;
import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.fluid.PipeNetwork;
import com.sumutiu.goingnuclear.power.ElectricBlockEntity;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import com.sumutiu.goingnuclear.util.Sounds;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.List;

public class CirculationPumpBlockEntity extends ElectricBlockEntity implements MachineBlock.TickingMachine, FluidHandler, InfoProvider {
    private static final double BUFFER = 200.0;
    private @Nullable FluidType bufferType;
    private double bufferAmount;
    /**
     * Temperature of what is in the buffer (coolant keeps its temperature through the pump).
     */
    private double bufferTemp = 20.0;
    private double demand;
    private double lastRate;
    /**
     * What it pumped last tick (the buffer is usually empty again by the end of the tick).
     */
    private @Nullable FluidType lastType;
    /**
     * Ticks in a row the pump had fluid to send but could not push any out of its front.
     */
    private int blockedTicks;

    public CirculationPumpBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CIRCULATION_PUMP, pos, state);
    }

    private Direction output() {
        return this.getBlockState().getValue(CirculationPumpBlock.FACING);
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        NuclearConfig config = NuclearConfig.get();
        Direction out = state.getValue(CirculationPumpBlock.FACING);
        Direction in = out.getOpposite();
        FluidHandler source = FluidAccess.get(level, pos.relative(in), out);

        FluidType type = this.bufferAmount > 0.0 ? this.bufferType : null;
        if (type == null && source != null) type = source.available(out, true);
        if (type == null && source instanceof PipeNetwork network) type = network.suctionType(level, pos);
        this.demand = type != null ? config.circulationPumpPower : 0.0;

        // Even with nothing to pump right now it asks for what its source gives, so it keeps its place among the
        // pumps sharing that source and gets its even share as soon as there is some (see FairShare).
        FluidType wanted = type;
        if (wanted == null && source != null) {
            wanted = source instanceof PipeNetwork network ? network.outletType(level, pos) : source.offers(out, true);
        }
        this.lastRate = 0.0;
        if (wanted != null && this.powerFraction > 0.0) {
            double want = Math.min(config.circulationPumpRate * this.powerFraction, BUFFER - this.bufferAmount);
            // Pumps drawing on the same pipe or outlet share it evenly: first what sits in the pipe, then what
            // the machine outlets on the pipe hold.
            double temperature = source != null ? source.temperature(out, wanted) : wanted.defaultTemperature();
            double got = FluidAccess.pull(level, pos, in, wanted, want, true);
            double heat = got * temperature;
            if (got < want && source instanceof PipeNetwork network) {
                PipeNetwork.Drawn drawn = network.suction(level, wanted, want - got, pos);
                got += drawn.amount();
                heat += drawn.amount() * drawn.temperature();
            }
            if (got > 0.0) {
                this.bufferTemp = FluidTank.mix(this.bufferAmount, this.bufferTemp, got, heat / got);
                this.bufferType = wanted;
                this.bufferAmount += got;
                this.lastRate = got;
                this.lastType = wanted;
            }
        }

        if (this.bufferAmount > 0.0 && this.bufferType != null) {
            // Pumps pushing into the same pipe share it evenly (FluidAccess.push), so parallel pumps all run.
            double pushed = FluidAccess.push(level, pos, out, this.bufferType, this.bufferAmount, this.bufferTemp);
            this.blockedTicks = pushed > 0.0 ? 0 : this.blockedTicks + 1;
            this.bufferAmount -= pushed;
            if (this.bufferAmount < 1.0e-6) {
                this.bufferAmount = 0.0;
                this.bufferType = null;
            }
        } else {
            this.blockedTicks = 0;
        }
        if (this.demand > 0.0 && this.powerFraction > 0.0) this.sounds(level, pos, config);
        if (level.getGameTime() % 20L == 0L) this.setChanged();
    }

    /**
     * The motor hums whenever it is powered and has something to pump (deeper when it is short of power);
     * the swirl of water only plays while fluid is really moving, so a blocked pump sounds different.
     */
    private void sounds(ServerLevel level, BlockPos pos, NuclearConfig config) {
        float power = (float) Math.min(1.0, this.powerFraction);
        if (Sounds.due(level, pos, 80)) {
            Sounds.machine(level, pos, Sounds.motorHum(), 0.55f, 0.6f + 0.4f * power);
        }
        if (this.lastRate > 0.0 && Sounds.due(level, pos, 40, 20)) {
            float flow = config.circulationPumpRate > 0.0 ? (float) Math.min(1.0, this.lastRate / config.circulationPumpRate) : 1.0f;
            Sounds.machine(level, pos, Sounds.waterSwirl(), 0.25f + 0.25f * flow, 0.8f + 0.3f * flow);
        }
    }

    @Override
    public double powerDemand() {
        return this.demand;
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, boolean simulate) {
        return this.insert(side, type, amount, type.defaultTemperature(), simulate);
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, double temperature, boolean simulate) {
        if (side != this.output().getOpposite()) return 0.0;
        if (this.bufferAmount > 0.0 && this.bufferType != type) return 0.0;
        double accepted = Math.min(amount, BUFFER - this.bufferAmount);
        if (accepted <= 0.0) return 0.0;
        if (!simulate) {
            this.bufferTemp = FluidTank.mix(this.bufferAmount, this.bufferTemp, accepted, temperature);
            this.bufferAmount += accepted;
            this.bufferType = type;
        }
        return accepted;
    }

    @Override
    public double temperature(Direction side, FluidType type) {
        return side == this.output() && this.bufferType == type ? this.bufferTemp : type.defaultTemperature();
    }

    @Override
    public double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate) {
        if (side != this.output() || this.bufferType != type) return 0.0;
        double taken = Math.min(max, this.bufferAmount);
        if (!simulate) {
            this.bufferAmount -= taken;
            if (this.bufferAmount < 1.0e-6) {
                this.bufferAmount = 0.0;
                this.bufferType = null;
            }
        }
        return taken;
    }

    @Override
    public @Nullable FluidType available(Direction side, boolean pumped) {
        return side == this.output() && this.bufferAmount > 0.0 ? this.bufferType : null;
    }

    @Override
    public void appendInfo(List<Component> lines) {
        lines.add(Fmt.title("Circulation Pump"));
        // Whatever it draws in it usually sends on in the same tick, so its buffer is mostly empty while it pumps.
        FluidType pumped = this.bufferType != null ? this.bufferType : this.lastRate > 0.0 ? this.lastType : null;
        Component pumping = pumped == null ? Component.literal("nothing").withStyle(ChatFormatting.DARK_GRAY)
                : pumped == FluidType.COOLANT ? pumped.displayName().copy().append(Component.literal(" at " + Fmt.temp(this.bufferTemp)))
                : pumped.displayName();
        lines.add(Fmt.label("Pumping", pumping));
        lines.add(Fmt.label("Flow", Fmt.rate(this.lastRate), ChatFormatting.AQUA));
        lines.add(Fmt.label("Power", Fmt.pct(this.powerFraction) + " of " + Fmt.power(NuclearConfig.get().circulationPumpPower), this.powerFraction >= 0.99 ? ChatFormatting.GREEN : ChatFormatting.RED));
        lines.add(Fmt.label("Flow direction", this.output().getOpposite().getName() + " -> " + this.output().getName(), ChatFormatting.WHITE));
        lines.add(Fmt.label("Power in", "any face but the front and back (passed on to neighbours)", ChatFormatting.YELLOW));
        if (this.blockedTicks >= 20 && this.level instanceof ServerLevel serverLevel) {
            lines.add(Component.literal("Blocked: " + this.blockedReason(serverLevel)).withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Why nothing gets out of the front, in words for the probe.
     */
    private String blockedReason(ServerLevel level) {
        Direction out = this.output();
        FluidHandler target = FluidAccess.get(level, this.getBlockPos().relative(out), out.getOpposite());
        if (target == null) return "nothing at the front (" + out.getName() + ") takes fluid.";
        if (target instanceof PipeNetwork network && network.type() != null && this.bufferType != null && network.type() != this.bufferType) {
            return "the pipe at the front holds " + network.type().displayName().getString() + ", so it can't take "
                    + this.bufferType.displayName().getString() + ". A pipe carries one fluid: give the make-up water its own reactor INPUT port.";
        }
        return "everything at the front is full. If this is a closed loop, it may be overfilled: the reactor's relief valve lets some out.";
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("buffer_type", this.bufferType == null ? "" : this.bufferType.id());
        output.putDouble("buffer", this.bufferAmount);
        output.putDouble("buffer_temp", this.bufferTemp);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.bufferType = FluidType.byId(input.getStringOr("buffer_type", ""));
        this.bufferAmount = this.bufferType == null ? 0.0 : input.getDoubleOr("buffer", 0.0);
        this.bufferTemp = input.getDoubleOr("buffer_temp", 20.0);
    }
}

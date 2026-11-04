package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.fluid.FluidAccess;
import com.sumutiu.goingnuclear.fluid.FluidHandler;
import com.sumutiu.goingnuclear.fluid.FluidTank;
import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.fluid.TankGroup;
import com.sumutiu.goingnuclear.fluid.TankGroups;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * One block of an expansion tank. Water goes in at its front (it draws it from the pipe there, up to
 * {@code expansionTankRate}) and out at its back (it pushes it into the pipe there); tanks touching each other
 * share their water (see {@link TankGroup}), so every front and back works for the whole group. This block only
 * keeps its share of the group's water, for saving.
 */
public class ExpansionTankBlockEntity extends BlockEntity implements MachineBlock.TickingMachine, FluidHandler, InfoProvider {
    private double storedAmount;
    private double storedTemperature = FluidType.WATER.defaultTemperature();

    public ExpansionTankBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.EXPANSION_TANK, pos, state);
    }

    public double storedAmount() {
        return this.storedAmount;
    }

    public double storedTemperature() {
        return this.storedTemperature;
    }

    public void setStored(double amount, double temperature) {
        this.storedAmount = Math.max(0.0, amount);
        this.storedTemperature = temperature;
        this.setChanged();
    }

    private Direction front() {
        return this.getBlockState().getValue(ExpansionTankBlock.FACING);
    }

    private @Nullable FluidTank water() {
        if (!(this.level instanceof ServerLevel serverLevel)) return null;
        TankGroup group = TankGroups.of(serverLevel).groupAt(this.getBlockPos());
        return group != null ? group.water() : null;
    }

    private @Nullable TankGroup group() {
        return this.level instanceof ServerLevel serverLevel ? TankGroups.of(serverLevel).groupAt(this.getBlockPos()) : null;
    }

    /**
     * Draws water in through the front from the pipe there and pushes it out of the back into the pipe there (a
     * check valve right at the front or back moves the water itself), and shows how full the group is on the
     * sight glass.
     */
    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        FluidTank water = this.water();
        if (water == null) return;
        double rate = NuclearConfig.get().expansionTankRate;
        Direction front = state.getValue(ExpansionTankBlock.FACING);
        Direction back = front.getOpposite();
        double room = Math.min(water.space(), rate);
        if (room > 1.0e-6) {
            double temperature = FluidAccess.temperatureAt(level, pos, front, FluidType.WATER);
            double got = FluidAccess.pull(level, pos, front, FluidType.WATER, room, false);
            water.insert(FluidType.WATER, got, temperature, false);
        }
        // Never into a pipe of another fluid (an empty coolant line, say), which the water would block.
        double out = Math.min(water.amount(), rate);
        if (out > 1.0e-6 && !FluidAccess.isOtherFluidLine(level, pos, back, FluidType.WATER)) {
            double pushed = FluidAccess.push(level, pos, back, FluidType.WATER, out, water.temperature());
            water.extract(FluidType.WATER, pushed, false);
        }

        if (level.getGameTime() % 20L != Math.floorMod(pos.hashCode(), 20)) return;
        int glass = (int) Math.min(4, Math.ceil(water.fill() * 4.0 - 1.0e-9));
        if (state.getValue(ExpansionTankBlock.LEVEL) != glass) {
            level.setBlock(pos, state.setValue(ExpansionTankBlock.LEVEL, glass), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public double insert(Direction side, FluidType type, double amount, boolean simulate) {
        return this.insert(side, type, amount, type.defaultTemperature(), simulate);
    }

    /**
     * Water in at the front only.
     */
    @Override
    public double insert(Direction side, FluidType type, double amount, double temperature, boolean simulate) {
        if (side != this.front() || type != FluidType.WATER) return 0.0;
        FluidTank water = this.water();
        return water != null ? water.insert(type, amount, temperature, simulate) : 0.0;
    }

    /**
     * Water out at the back only.
     */
    @Override
    public double extract(Direction side, FluidType type, double max, boolean pumped, boolean simulate) {
        if (side != this.front().getOpposite() || type != FluidType.WATER) return 0.0;
        FluidTank water = this.water();
        return water != null ? water.extract(type, max, simulate) : 0.0;
    }

    @Override
    public double temperature(Direction side, FluidType type) {
        FluidTank water = this.water();
        return water != null && type == FluidType.WATER ? water.temperature() : type.defaultTemperature();
    }

    @Override
    public @Nullable FluidType available(Direction side, boolean pumped) {
        FluidTank water = this.water();
        return side == this.front().getOpposite() && water != null && water.amount() > 0.0 ? FluidType.WATER : null;
    }

    @Override
    public @Nullable FluidType offers(Direction side, boolean pumped) {
        return side == this.front().getOpposite() ? FluidType.WATER : null;
    }

    /**
     * How full the whole group is, seen from its front or back (for check valves).
     */
    @Override
    public double fillLevel(Direction side) {
        FluidTank water = this.water();
        if (water == null || side.getAxis() != this.front().getAxis()) return -1.0;
        return water.fill();
    }

    @Override
    public void appendInfo(List<Component> lines) {
        TankGroup group = this.group();
        Direction front = this.front();
        lines.add(Fmt.title("Expansion Tank"));
        if (group == null) return;
        FluidTank water = group.water();
        lines.add(Fmt.label("Water", Fmt.mb(water.amount()) + " / " + Fmt.mb(water.capacity()) + " (" + Fmt.pct(water.fill()) + ")"
                + (water.amount() > 1.0 ? " at " + Fmt.temp(water.temperature()) : ""), ChatFormatting.AQUA));
        lines.add(Fmt.label("Tanks joined", group.size() + (group.size() == 1 ? " (place more next to it or on top for more room)" : ""),
                ChatFormatting.WHITE));
        lines.add(Fmt.label("Faces", "water in: front (" + front.getName() + "), water out: back (" + front.getOpposite().getName() + ")",
                ChatFormatting.YELLOW));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putDouble("water", this.storedAmount);
        output.putDouble("water_temp", this.storedTemperature);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.storedAmount = Math.max(0.0, input.getDoubleOr("water", 0.0));
        this.storedTemperature = input.getDoubleOr("water_temp", FluidType.WATER.defaultTemperature());
    }
}

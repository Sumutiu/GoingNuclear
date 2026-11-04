package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.fluid.PipeNetworks;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

public class FluidPipeBlockEntity extends BlockEntity {
    private @Nullable FluidType storedType;
    private double storedAmount;
    private double storedTemperature = 20.0;
    private int disabledSides;

    public FluidPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FLUID_PIPE, pos, state);
    }

    public @Nullable FluidType storedType() {
        return this.storedType;
    }

    public double storedAmount() {
        return this.storedAmount;
    }

    public double storedTemperature() {
        return this.storedTemperature;
    }

    public void setStored(@Nullable FluidType type, double amount, double temperature) {
        if (type == this.storedType && Math.abs(amount - this.storedAmount) < 1.0e-6 && Math.abs(temperature - this.storedTemperature) < 0.05) return;
        this.storedType = amount > 0.0 ? type : null;
        this.storedAmount = Math.max(0.0, amount);
        this.storedTemperature = temperature;
        this.setChanged();
    }

    public boolean isDisabled(Direction side) {
        return (this.disabledSides & (1 << side.get3DDataValue())) != 0;
    }

    public void toggle(Direction side) {
        this.disabledSides ^= 1 << side.get3DDataValue();
        this.setChanged();
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel serverLevel) PipeNetworks.invalidate(serverLevel);
    }

    @Override
    public void setRemoved() {
        if (this.level instanceof ServerLevel serverLevel) PipeNetworks.invalidate(serverLevel);
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("fluid", this.storedType == null ? "" : this.storedType.id());
        output.putDouble("amount", this.storedAmount);
        output.putDouble("temp", this.storedTemperature);
        output.putInt("disabled_sides", this.disabledSides);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.storedType = FluidType.byId(input.getStringOr("fluid", ""));
        this.storedAmount = input.getDoubleOr("amount", 0.0);
        this.storedTemperature = input.getDoubleOr("temp", this.storedType != null ? this.storedType.defaultTemperature() : 20.0);
        this.disabledSides = input.getIntOr("disabled_sides", 0);
    }
}

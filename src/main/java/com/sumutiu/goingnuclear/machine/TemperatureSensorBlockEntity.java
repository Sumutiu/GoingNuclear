package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.reactor.ReactorControllerBlockEntity;
import com.sumutiu.goingnuclear.reactor.ReactorRegistry;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import com.sumutiu.goingnuclear.util.TemperatureSource;
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

import java.util.List;

public class TemperatureSensorBlockEntity extends BlockEntity implements MachineBlock.TickingMachine, InfoProvider {
    private boolean thresholdMode = true;
    private int threshold = 600;
    private int rangeMin = 100;
    private int rangeMax = 1200;
    private double lastTemperature = Double.NaN;

    public TemperatureSensorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TEMPERATURE_SENSOR, pos, state);
    }

    public boolean thresholdMode() {
        return this.thresholdMode;
    }

    public void setThresholdMode(boolean thresholdMode) {
        this.thresholdMode = thresholdMode;
        this.setChanged();
    }

    public int threshold() {
        return this.threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = Math.clamp(threshold, 0, 5000);
        this.setChanged();
    }

    public int rangeMin() {
        return this.rangeMin;
    }

    public int rangeMax() {
        return this.rangeMax;
    }

    public void setRange(int min, int max) {
        this.rangeMin = Math.clamp(min, 0, 4900);
        this.rangeMax = Math.clamp(max, this.rangeMin + 50, 5000);
        this.setChanged();
    }

    public double lastTemperature() {
        return this.lastTemperature;
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.getGameTime() % 10L != 0L) return;
        double temperature = Double.NaN;
        for (Direction dir : Direction.values()) {
            BlockPos other = pos.relative(dir);
            if (!level.isLoaded(other)) continue;
            double value = Double.NaN;
            if (level.getBlockEntity(other) instanceof TemperatureSource source) {
                value = source.temperature();
            } else {
                ReactorControllerBlockEntity controller = ReactorRegistry.controllerAt(level, other);
                if (controller != null) value = controller.temperature();
            }
            if (!Double.isNaN(value) && (Double.isNaN(temperature) || value > temperature)) temperature = value;
        }
        this.lastTemperature = temperature;

        int power = 0;
        if (!Double.isNaN(temperature)) {
            if (this.thresholdMode) {
                power = temperature >= this.threshold ? 15 : 0;
            } else {
                double t = (temperature - this.rangeMin) / (double) (this.rangeMax - this.rangeMin);
                power = (int) Math.round(Math.clamp(t, 0.0, 1.0) * 15.0);
            }
        }
        if (state.getValue(TemperatureSensorBlock.POWER) != power) {
            level.setBlock(pos, state.setValue(TemperatureSensorBlock.POWER, power), Block.UPDATE_ALL);
        }
    }

    @Override
    public void appendInfo(List<Component> lines) {
        lines.add(Fmt.title("Temperature Sensor"));
        if (Double.isNaN(this.lastTemperature)) {
            lines.add(Component.literal("Nothing to measure next to it").withStyle(ChatFormatting.RED));
        } else {
            lines.add(Fmt.label("Reading", Fmt.temp(this.lastTemperature), Fmt.tempColor(this.lastTemperature)));
        }
        if (this.thresholdMode) {
            lines.add(Fmt.label("Mode", "threshold: signal 15 at " + this.threshold + " °C or above", ChatFormatting.WHITE));
        } else {
            lines.add(Fmt.label("Mode", "analog: 0 at " + this.rangeMin + " °C to 15 at " + this.rangeMax + " °C", ChatFormatting.WHITE));
        }
        lines.add(Fmt.label("Output", String.valueOf(this.getBlockState().getValue(TemperatureSensorBlock.POWER)), ChatFormatting.RED));
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("threshold_mode", this.thresholdMode);
        output.putInt("threshold", this.threshold);
        output.putInt("range_min", this.rangeMin);
        output.putInt("range_max", this.rangeMax);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.thresholdMode = input.getBooleanOr("threshold_mode", true);
        this.threshold = input.getIntOr("threshold", 600);
        this.rangeMin = input.getIntOr("range_min", 100);
        this.rangeMax = input.getIntOr("range_max", 1200);
    }
}

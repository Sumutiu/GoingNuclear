package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.power.ElectricBlockEntity;
import com.sumutiu.goingnuclear.power.PowerGrid;
import com.sumutiu.goingnuclear.power.PowerNetwork;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

/**
 * Battery bank. It has two terminals: {@link #INPUT} on its back, where it charges and, when the grid on
 * that side runs short (generators or turbines stopped), gives its power back; and {@link #OUTPUT} on its
 * left, right, top and bottom (the red power-out ports) and its overhead wires, where it only discharges.
 * The front only shows the charge and connects nothing.
 * <p>
 * Batteries whose power-out ports touch (side by side or stacked) or are linked by cables form one bank and
 * even out their charge (see PowerNetwork), so a whole bank charges through any of its backs.
 */
public class BatteryBankBlockEntity extends ElectricBlockEntity implements MachineBlock.TickingMachine, InfoProvider {
    public static final int OUTPUT = 0;
    public static final int INPUT = 1;

    private double stored;
    private double lastCharge;
    private double lastDischarge;
    private double chargeThisTick;
    private double dischargeThisTick;
    /**
     * Part of this tick's charging and discharging that only evened out the bank (not shown as either).
     */
    private double sharedInThisTick;
    private double sharedOutThisTick;
    private double lastShared;

    public BatteryBankBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BATTERY_BANK, pos, state);
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        this.lastCharge = this.chargeThisTick - this.sharedInThisTick;
        this.lastDischarge = this.dischargeThisTick - this.sharedOutThisTick;
        this.lastShared = this.sharedInThisTick - this.sharedOutThisTick;
        this.chargeThisTick = 0.0;
        this.dischargeThisTick = 0.0;
        this.sharedInThisTick = 0.0;
        this.sharedOutThisTick = 0.0;
        if (level.getGameTime() % 20L == 0L) {
            int charge = (int) Math.round(this.stored / this.capacity() * 4.0);
            charge = Math.clamp(charge, 0, 4);
            if (state.getValue(BatteryBankBlock.CHARGE) != charge) {
                level.setBlock(pos, state.setValue(BatteryBankBlock.CHARGE, charge), Block.UPDATE_ALL);
            }
            this.setChanged();
        }
    }

    @Override
    public boolean isStorage() {
        return true;
    }

    @Override
    public double capacity() {
        return NuclearConfig.get().batteryCapacity;
    }

    @Override
    public double stored() {
        return this.stored;
    }

    @Override
    public double maxChargeRate() {
        return NuclearConfig.get().batteryRate;
    }

    public double maxDischargeRate() {
        return NuclearConfig.get().batteryRate;
    }

    @Override
    public double charge(double max) {
        double accepted = Math.clamp(max, 0.0, this.chargeRoom());
        this.stored += accepted;
        this.chargeThisTick += accepted;
        return accepted;
    }

    @Override
    public double discharge(double max) {
        double given = Math.clamp(max, 0.0, this.dischargeAvailable());
        this.stored -= given;
        this.dischargeThisTick += given;
        return given;
    }

    @Override
    public void shareWithBank(double amount) {
        if (amount >= 0.0) {
            this.sharedInThisTick += this.charge(amount);
        } else {
            this.sharedOutThisTick += this.discharge(-amount);
        }
    }

    @Override
    public double chargeRoom() {
        double room = Math.min(this.maxChargeRate() - this.chargeThisTick, this.capacity() - this.stored);
        return Math.max(0.0, room);
    }

    @Override
    public double dischargeAvailable() {
        double available = Math.min(this.maxDischargeRate() - this.dischargeThisTick, this.stored);
        return Math.max(0.0, available);
    }

    /**
     * The face with the charge gauge (connects nothing).
     */
    public Direction front() {
        BlockState state = this.getBlockState();
        return state.hasProperty(BatteryBankBlock.FACING) ? state.getValue(BatteryBankBlock.FACING) : Direction.NORTH;
    }

    /**
     * The face power comes in through.
     */
    public Direction back() {
        return this.front().getOpposite();
    }

    @Override
    public int terminals() {
        return 2;
    }

    @Override
    public int terminalAt(Direction face) {
        Direction front = this.front();
        if (face == front) return -1;
        return face == front.getOpposite() ? INPUT : OUTPUT;
    }

    @Override
    public int wireTerminal() {
        return OUTPUT;
    }

    @Override
    public boolean chargesThrough(int terminal) {
        return terminal == INPUT;
    }

    /**
     * Both terminals give power when their grid needs it: the back too, so a battery keeps the grid it
     * charges from running when that grid's generators stop.
     */
    @Override
    public boolean dischargesThrough(int terminal) {
        return true;
    }

    @Override
    public void appendInfo(List<Component> lines) {
        lines.add(Fmt.title("Battery Bank"));
        lines.add(Fmt.label("Stored", Fmt.energy(this.stored) + " / " + Fmt.energy(this.capacity()) + " (" + Fmt.pct(this.stored / this.capacity()) + ")", ChatFormatting.GREEN));
        Direction front = this.front();
        lines.add(Fmt.label("Charging", Fmt.power(this.lastCharge), ChatFormatting.AQUA));
        lines.add(Fmt.label("Discharging", Fmt.power(this.lastDischarge), ChatFormatting.GOLD));
        lines.add(Fmt.label("Power in (yellow port)", "back, " + this.back().getName() + " (gives power back there only when that side is short)",
                ChatFormatting.YELLOW));
        lines.add(Fmt.label("Power out (red ports)", front.getCounterClockWise().getName() + ", " + front.getClockWise().getName()
                + ", top, bottom (front: charge gauge only)", ChatFormatting.YELLOW));
        if (this.level instanceof ServerLevel serverLevel) {
            PowerNetwork output = PowerGrid.of(serverLevel).networkAt(this.getBlockPos(), OUTPUT);
            int bank = output != null ? output.lastBankSize() : 1;
            if (bank > 1) {
                String sharing = Math.abs(this.lastShared) < 1.0e-6 ? "even"
                        : this.lastShared > 0.0 ? "taking " + Fmt.power(this.lastShared) : "giving " + Fmt.power(-this.lastShared);
                lines.add(Fmt.label("Bank", bank + " batteries linked by their red ports share their charge (" + sharing + ")", ChatFormatting.WHITE));
            }
            PowerNetwork input = PowerGrid.of(serverLevel).networkAt(this.getBlockPos(), INPUT);
            if (input != null && input.nodeCount() <= 1) {
                lines.add(Component.literal(bank > 1 ? "Its back is not connected: it charges from the other batteries of its bank."
                        : "Nothing to charge from: connect a generator, turbine or cable to its back.").withStyle(ChatFormatting.GRAY));
            } else if (input != null) {
                lines.add(Fmt.label("Charging from", Fmt.power(input.lastSupply()) + " supplied on the input side", ChatFormatting.WHITE));
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putDouble("stored", this.stored);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.stored = Math.max(0.0, input.getDoubleOr("stored", 0.0));
    }
}

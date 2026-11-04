package com.sumutiu.goingnuclear.machine;

import com.sumutiu.goingnuclear.config.NuclearConfig;
import com.sumutiu.goingnuclear.power.ElectricBlockEntity;
import com.sumutiu.goingnuclear.registry.ModBlockEntities;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class ElectricLampBlockEntity extends ElectricBlockEntity implements MachineBlock.TickingMachine, InfoProvider {
    private boolean switchedOff;

    public ElectricLampBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ELECTRIC_LAMP, pos, state);
    }

    @Override
    public void serverTick(ServerLevel level, BlockPos pos, BlockState state) {
        this.switchedOff = level.hasNeighborSignal(pos);
        boolean lit = !this.switchedOff && this.powerFraction >= 0.999;
        if (state.getValue(ElectricLampBlock.LIT) != lit) {
            level.setBlock(pos, state.setValue(ElectricLampBlock.LIT, lit), Block.UPDATE_ALL);
        }
    }

    @Override
    public double powerDemand() {
        return this.switchedOff ? 0.0 : NuclearConfig.get().lampPower;
    }

    @Override
    public void receivePower(double fraction) {
        super.receivePower(this.switchedOff ? 0.0 : fraction);
    }

    @Override
    public void appendInfo(List<Component> lines) {
        lines.add(Fmt.title("Electric Lamp"));
        lines.add(Fmt.label("State", this.switchedOff ? "switched off (redstone)" : this.powerFraction >= 0.999 ? "on" : "no power",
                this.powerFraction >= 0.999 && !this.switchedOff ? ChatFormatting.GREEN : ChatFormatting.RED));
        lines.add(Fmt.label("Consumption", Fmt.power(NuclearConfig.get().lampPower), ChatFormatting.WHITE));
    }
}

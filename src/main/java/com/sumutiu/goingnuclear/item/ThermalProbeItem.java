package com.sumutiu.goingnuclear.item;

import com.sumutiu.goingnuclear.fluid.FluidType;
import com.sumutiu.goingnuclear.fluid.PipeNetwork;
import com.sumutiu.goingnuclear.fluid.PipeNetworks;
import com.sumutiu.goingnuclear.machine.FluidPipeBlock;
import com.sumutiu.goingnuclear.power.ElectricNode;
import com.sumutiu.goingnuclear.power.FurnacePower;
import com.sumutiu.goingnuclear.power.PowerCableBlock;
import com.sumutiu.goingnuclear.power.PowerGrid;
import com.sumutiu.goingnuclear.power.PowerNetwork;
import com.sumutiu.goingnuclear.power.WireAnchor;
import com.sumutiu.goingnuclear.reactor.ReactorControllerBlockEntity;
import com.sumutiu.goingnuclear.reactor.ReactorPart;
import com.sumutiu.goingnuclear.reactor.ReactorRegistry;
import com.sumutiu.goingnuclear.util.Fmt;
import com.sumutiu.goingnuclear.util.InfoProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Handheld multimeter / thermometer. Right-click anything from this mod (or a vanilla furnace on the grid)
 * to read temperatures, fluids, flows and power.
 */
public class ThermalProbeItem extends NuclearItem {
    public ThermalProbeItem(Properties properties, Item fallback) {
        super(properties, fallback, "Right-click a reactor, machine, pipe, cable", "or furnace to read its values");
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer player) || !(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);
        List<Component> lines = new ArrayList<>();

        if (be instanceof InfoProvider provider && !(be instanceof ReactorControllerBlockEntity)) {
            provider.appendInfo(lines);
        } else if (state.getBlock() instanceof FluidPipeBlock) {
            PipeNetwork network = PipeNetworks.of(level).networkAt(pos);
            lines.add(Fmt.title("Fluid Pipe"));
            if (network != null) appendPipeInfo(lines, network);
        } else if (state.getBlock() instanceof ReactorPart || be instanceof ReactorControllerBlockEntity) {
            ReactorControllerBlockEntity controller = be instanceof ReactorControllerBlockEntity c ? c : ReactorRegistry.controllerAt(level, pos);
            if (controller != null) {
                controller.appendInfo(lines);
            } else {
                lines.add(Component.literal("Not part of a complete reactor.").withStyle(ChatFormatting.RED));
            }
        } else if (be instanceof AbstractFurnaceBlockEntity furnace) {
            lines.add(Fmt.title("Furnace"));
            PowerNetwork network = PowerGrid.of(level).networkAt(pos);
            if (network == null) {
                lines.add(Component.literal("Not connected to the grid (place a cable or machine next to it).").withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Fmt.label("Electric demand", Fmt.power(FurnacePower.demand(furnace)), ChatFormatting.WHITE));
                lines.add(Fmt.label("Burning", FurnacePower.isElectricallyLit(furnace) ? "yes" : "no", ChatFormatting.WHITE));
            }
        } else if (state.getBlock() instanceof PowerCableBlock) {
            lines.add(Fmt.title("Power Cable"));
        }

        if (be instanceof ElectricNode || state.getBlock() instanceof PowerCableBlock || be instanceof AbstractFurnaceBlockEntity) {
            PowerNetwork network = PowerGrid.of(level).networkAt(pos);
            if (network != null) {
                lines.add(Fmt.label("Grid supply", Fmt.power(network.lastSupply()), ChatFormatting.GREEN));
                lines.add(Fmt.label("Grid demand", Fmt.power(network.lastDemand()), ChatFormatting.GOLD));
                lines.add(Fmt.label("Grid satisfied", Fmt.pct(network.lastSatisfaction()), network.lastSatisfaction() >= 0.999 ? ChatFormatting.GREEN : ChatFormatting.RED));
                if (network.lastCapacity() > 0.0) {
                    lines.add(Fmt.label("Grid storage", Fmt.energy(network.lastStored()) + " / " + Fmt.energy(network.lastCapacity()), ChatFormatting.AQUA));
                }
                lines.add(Fmt.label("Grid size", network.nodeCount() + " devices", ChatFormatting.WHITE));
            }
        }
        if (be instanceof WireAnchor anchor && anchor.wires().size() > 0) {
            lines.add(Fmt.label("Overhead wires", String.valueOf(anchor.wires().size()), ChatFormatting.WHITE));
        }

        if (lines.isEmpty()) {
            lines.add(Component.literal("Nothing to measure here.").withStyle(ChatFormatting.GRAY));
        }
        player.sendSystemMessage(Component.literal("----------------").withStyle(ChatFormatting.DARK_GRAY));
        for (Component line : lines) player.sendSystemMessage(line);
        return InteractionResult.SUCCESS_SERVER;
    }

    /**
     * What a pipe network carries: the fluid flowing through it (averaged over the last second, so fluid that
     * goes straight through counts too) and what waits in the pipes.
     */
    private static void appendPipeInfo(List<Component> lines, PipeNetwork network) {
        FluidType flowing = network.flowingType();
        FluidType type = flowing != null ? flowing : network.type();
        if (type == null) {
            lines.add(Fmt.label("Carrying", Component.literal("nothing").withStyle(ChatFormatting.DARK_GRAY)));
        } else {
            lines.add(Fmt.label("Carrying", type.displayName().copy()
                    .append(Component.literal(flowing != null ? " (flowing)" : " (standing still)").withStyle(ChatFormatting.GRAY))));
            double temperature = flowing != null ? network.flowTemperature() : network.temperature();
            if (type == FluidType.COOLANT) {
                lines.add(Fmt.label("Temperature", Fmt.temp(temperature), Fmt.tempColor(temperature)));
            } else if (type == FluidType.STEAM) {
                lines.add(Fmt.label("Temperature, pressure", Fmt.steam(temperature), Fmt.tempColor(temperature)));
            }
        }
        double in = network.flowIn();
        double out = network.flowOut();
        String flow;
        if (in + out <= 1.0e-4) {
            flow = "none";
        } else if (Math.abs(in - out) <= Math.max(0.5, 0.05 * Math.max(in, out))) {
            flow = Fmt.rate(Math.max(in, out));
        } else {
            flow = Fmt.rate(in) + " in, " + Fmt.rate(out) + " out" + (in > out ? " (filling up)" : " (emptying)");
        }
        lines.add(Fmt.label("Flow", flow, ChatFormatting.AQUA));
        lines.add(Fmt.label("Waiting in pipes", Fmt.mb(network.amount()) + " / " + Fmt.mb(network.capacity()), ChatFormatting.WHITE));
        lines.add(Fmt.label("Pipes in network", String.valueOf(network.pipes().size()), ChatFormatting.WHITE));
        if (flowing != null && network.amount() < 1.0) {
            lines.add(Component.literal("Everything that comes in goes straight on, so nothing has to wait in the pipes.")
                    .withStyle(ChatFormatting.GRAY));
        } else if (type != null && network.amount() > 0.95 * network.capacity()) {
            lines.add(Component.literal("The pipes are full: whatever takes fluid out of them can't keep up (or nothing does).")
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    @Override
    public boolean isPolymerBlockInteraction(BlockState state, ServerPlayer player, InteractionHand hand, ItemStack stack, ServerLevel world, BlockHitResult blockHitResult, InteractionResult actionResult) {
        return true;
    }
}

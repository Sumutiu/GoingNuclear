package com.sumutiu.goingnuclear.registry;

import com.sumutiu.goingnuclear.GoingNuclear;
import com.sumutiu.goingnuclear.machine.BackupGeneratorBlock;
import com.sumutiu.goingnuclear.machine.BatteryBankBlock;
import com.sumutiu.goingnuclear.machine.CheckValveBlock;
import com.sumutiu.goingnuclear.machine.CirculationPumpBlock;
import com.sumutiu.goingnuclear.machine.CondenserBlock;
import com.sumutiu.goingnuclear.machine.ElectricLampBlock;
import com.sumutiu.goingnuclear.machine.EnrichmentCentrifugeBlock;
import com.sumutiu.goingnuclear.machine.ExpansionTankBlock;
import com.sumutiu.goingnuclear.machine.FluidPipeBlock;
import com.sumutiu.goingnuclear.machine.HeatExchangerBlock;
import com.sumutiu.goingnuclear.machine.PipeWallPortBlock;
import com.sumutiu.goingnuclear.machine.SteamDumpValveBlock;
import com.sumutiu.goingnuclear.machine.SteamTurbineBlock;
import com.sumutiu.goingnuclear.machine.TemperatureSensorBlock;
import com.sumutiu.goingnuclear.machine.WaterOutletBlock;
import com.sumutiu.goingnuclear.machine.WaterPumpBlock;
import com.sumutiu.goingnuclear.power.CableWallPortBlock;
import com.sumutiu.goingnuclear.power.PowerCableBlock;
import com.sumutiu.goingnuclear.power.PowerPoleBlock;
import com.sumutiu.goingnuclear.reactor.ControlRodChannelBlock;
import com.sumutiu.goingnuclear.reactor.CoriumBlock;
import com.sumutiu.goingnuclear.reactor.FuelChannelBlock;
import com.sumutiu.goingnuclear.reactor.ReactorCasingBlock;
import com.sumutiu.goingnuclear.reactor.ReactorControllerBlock;
import com.sumutiu.goingnuclear.reactor.ReactorGlassBlock;
import com.sumutiu.goingnuclear.reactor.ReactorPortBlock;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.function.Function;

public final class ModBlocks {
    // ---------- Reactor ----------
    public static final ReactorCasingBlock REACTOR_CASING = register("reactor_casing", ReactorCasingBlock::new, metal(5.0f));
    public static final ReactorGlassBlock REACTOR_GLASS = register("reactor_glass", ReactorGlassBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.GLASS).strength(4.0f, 8.0f).pushReaction(PushReaction.IMMOVEABLE));
    public static final ReactorControllerBlock REACTOR_CONTROLLER = register("reactor_controller", ReactorControllerBlock::new, metal(5.0f));
    public static final ReactorPortBlock REACTOR_PORT = register("reactor_port", ReactorPortBlock::new, metal(5.0f));
    public static final FuelChannelBlock FUEL_CHANNEL = register("fuel_channel", FuelChannelBlock::new,
            metal(4.0f).noOcclusion().lightLevel(FuelChannelBlock::lightLevel));
    public static final ControlRodChannelBlock CONTROL_ROD_CHANNEL = register("control_rod_channel", ControlRodChannelBlock::new, metal(4.0f).noOcclusion());
    public static final CoriumBlock CORIUM = register("corium", CoriumBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(50.0f, 1200.0f).lightLevel(_ -> 12)
                    .sound(SoundType.STONE).noLootTable().pushReaction(PushReaction.IMMOVEABLE));

    // ---------- Fluids ----------
    public static final FluidPipeBlock FLUID_PIPE = register("fluid_pipe", FluidPipeBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(1.5f, 4.0f).sound(SoundType.COPPER).noOcclusion());
    public static final CheckValveBlock CHECK_VALVE = register("check_valve", CheckValveBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(1.5f, 4.0f).sound(SoundType.COPPER).noOcclusion());
    public static final PipeWallPortBlock PIPE_WALL_PORT = register("pipe_wall_port", PipeWallPortBlock::new, metal(3.0f).sound(SoundType.COPPER));
    public static final WaterPumpBlock WATER_PUMP = register("water_pump", WaterPumpBlock::new, metal(3.5f));
    public static final CirculationPumpBlock CIRCULATION_PUMP = register("circulation_pump", CirculationPumpBlock::new, metal(3.5f));
    public static final HeatExchangerBlock HEAT_EXCHANGER = register("heat_exchanger", HeatExchangerBlock::new, metal(4.0f).sound(SoundType.COPPER));
    public static final SteamTurbineBlock STEAM_TURBINE = register("steam_turbine", SteamTurbineBlock::new, metal(5.0f));
    public static final CondenserBlock CONDENSER = register("condenser", CondenserBlock::new, metal(4.0f));
    public static final SteamDumpValveBlock STEAM_DUMP_VALVE = register("steam_dump_valve", SteamDumpValveBlock::new, metal(4.0f));
    public static final WaterOutletBlock WATER_OUTLET = register("water_outlet", WaterOutletBlock::new, metal(3.0f));
    public static final ExpansionTankBlock EXPANSION_TANK = register("expansion_tank", ExpansionTankBlock::new, metal(3.5f).sound(SoundType.COPPER));
    public static final EnrichmentCentrifugeBlock ENRICHMENT_CENTRIFUGE = register("enrichment_centrifuge", EnrichmentCentrifugeBlock::new, metal(4.0f));

    // ---------- Electricity ----------
    public static final PowerCableBlock POWER_CABLE = register("power_cable", PowerCableBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(0.5f).sound(SoundType.WOOL).noOcclusion());
    public static final CableWallPortBlock CABLE_WALL_PORT = register("cable_wall_port", CableWallPortBlock::new, metal(3.0f));
    public static final PowerPoleBlock POWER_POLE = register("power_pole", PowerPoleBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0f, 3.0f).sound(SoundType.WOOD).noOcclusion());
    public static final BatteryBankBlock BATTERY_BANK = register("battery_bank", BatteryBankBlock::new, metal(4.0f));
    public static final BackupGeneratorBlock BACKUP_GENERATOR = register("backup_generator", BackupGeneratorBlock::new, metal(3.5f));
    public static final ElectricLampBlock ELECTRIC_LAMP = register("electric_lamp", ElectricLampBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.QUARTZ).strength(1.0f).sound(SoundType.GLASS)
                    .lightLevel(state -> state.getValue(ElectricLampBlock.LIT) ? 15 : 0));
    public static final TemperatureSensorBlock TEMPERATURE_SENSOR = register("temperature_sensor", TemperatureSensorBlock::new, metal(2.0f));

    private ModBlocks() {
    }

    private static BlockBehaviour.Properties metal(float strength) {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(strength, 6.0f)
                .sound(SoundType.IRON)
                .requiresCorrectToolForDrops();
    }

    private static <T extends Block> T register(String name, Function<BlockBehaviour.Properties, T> factory, BlockBehaviour.Properties properties) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, GoingNuclear.id(name));
        T block = factory.apply(properties.setId(key));
        return Registry.register(BuiltInRegistries.BLOCK, key, block);
    }

    public static void init() {
        // Loads the class, which registers everything above.
    }
}

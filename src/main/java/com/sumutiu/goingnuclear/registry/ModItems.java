package com.sumutiu.goingnuclear.registry;

import com.sumutiu.goingnuclear.GoingNuclear;
import com.sumutiu.goingnuclear.item.CopperWireItem;
import com.sumutiu.goingnuclear.item.FuelRodItem;
import com.sumutiu.goingnuclear.item.NuclearBlockItem;
import com.sumutiu.goingnuclear.item.NuclearItem;
import com.sumutiu.goingnuclear.item.PipeWrenchItem;
import com.sumutiu.goingnuclear.item.PliersItem;
import com.sumutiu.goingnuclear.item.ThermalProbeItem;
import com.sumutiu.goingnuclear.util.RadiationHandler;
import eu.pb4.polymer.core.api.item.PolymerCreativeModeTabUtils;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class ModItems {
    private static final List<Item> TAB_ORDER = new ArrayList<>();

    // ---------- Tools ----------
    public static final PipeWrenchItem PIPE_WRENCH = register("pipe_wrench", p -> new PipeWrenchItem(p.stacksTo(1), Items.TRIAL_KEY));
    public static final PliersItem PLIERS = register("pliers", p -> new PliersItem(p.stacksTo(1), Items.OMINOUS_TRIAL_KEY));
    public static final ThermalProbeItem THERMAL_PROBE = register("thermal_probe", p -> new ThermalProbeItem(p.stacksTo(1), Items.CLOCK));
    public static final NuclearItem GEIGER_COUNTER = register("geiger_counter", p -> new NuclearItem(p.stacksTo(1), Items.COMPASS,
            "Hold it to measure radiation", "Clicks faster near radioactive material"));

    // ---------- Materials ----------
    public static final CopperWireItem COPPER_WIRE = register("copper_wire", p -> new CopperWireItem(p, Items.LEAD));
    // Fuel cycle, in creative tab order.
    public static final FuelRodItem REDSTONE_FUEL_ROD = register("redstone_fuel_rod", p -> new FuelRodItem(p.stacksTo(16).rarity(Rarity.UNCOMMON),
            Items.BLAZE_ROD, "Redstone fuel: gentle and long lasting"));
    public static final NuclearItem DEPLETED_REDSTONE_FUEL_ROD = register("depleted_redstone_fuel_rod", p -> new NuclearItem(p.stacksTo(16),
            Items.BONE, "Spent redstone fuel. Radioactive while carried!", "Enrichment Centrifuge: enrich it with redstone dust",
            "Or smelt it in a blast furnace to recover iron"));
    public static final FuelRodItem ENRICHED_REDSTONE_FUEL_ROD = register("enriched_redstone_fuel_rod", p -> new FuelRodItem(p.stacksTo(16).rarity(Rarity.RARE),
            Items.BLAZE_ROD, "Enriched redstone fuel: more power from the same fuel", "Burns down into a Depleted Fuel Rod"));
    public static final FuelRodItem GLOWSTONE_FUEL_ROD = register("glowstone_fuel_rod", p -> new FuelRodItem(p.stacksTo(16).rarity(Rarity.RARE),
            Items.BREEZE_ROD, "Glowstone fuel: hot, powerful and harder to control"));
    public static final NuclearItem DEPLETED_GLOWSTONE_FUEL_ROD = register("depleted_glowstone_fuel_rod", p -> new NuclearItem(p.stacksTo(16),
            Items.BONE, "Spent glowstone fuel. Radioactive while carried!", "Enrichment Centrifuge: enrich it with glowstone dust",
            "Or smelt it in a blast furnace to recover iron"));
    public static final FuelRodItem ENRICHED_GLOWSTONE_FUEL_ROD = register("enriched_glowstone_fuel_rod", p -> new FuelRodItem(p.stacksTo(16).rarity(Rarity.EPIC),
            Items.BREEZE_ROD, "Enriched glowstone fuel: more power from the same fuel", "Burns down into a Depleted Fuel Rod"));
    public static final NuclearItem DEPLETED_FUEL_ROD = register("depleted_fuel_rod", p -> new NuclearItem(p.stacksTo(16),
            Items.BONE, "Spent enriched fuel. Radioactive while carried!", "Cannot be enriched again.", "Smelt in a blast furnace to recover iron"));
    public static final NuclearItem CONTROL_ROD = register("control_rod", p -> new NuclearItem(p.stacksTo(16),
            Items.CHARCOAL, "Absorbs neutrons from fuel within 2 blocks"));

    // ---------- Block items (registered only: nothing refers to them by field) ----------
    static {
        block(ModBlocks.REACTOR_CASING, Items.IRON_BLOCK, "Walls of the reactor box (3x3x3 up to 15x15x15)");
        block(ModBlocks.REACTOR_GLASS, Items.GLASS, "Casing you can see through");
        block(ModBlocks.REACTOR_CONTROLLER, Items.OBSERVER, "Place in one wall of the reactor, right-click to control");
        block(ModBlocks.REACTOR_PORT, Items.CONCRETE.cyan(), "Fluid port in the reactor wall", "Pipe wrench: switch input / output");
        block(ModBlocks.FUEL_CHANNEL, Items.IRON_BARS, "Goes inside the reactor, holds one fuel rod");
        block(ModBlocks.CONTROL_ROD_CHANNEL, Items.IRON_BARS, "Goes inside the reactor, holds one control rod");
        block(ModBlocks.ENRICHMENT_CENTRIFUGE, Items.BLAST_FURNACE,
                "Enriches depleted redstone and glowstone rods", "with dust of their kind. Power in: the back (20 kW).",
                "Right-click with rods or dust to load it.", "Sneak-right-click to take the enriched rods.");
        block(ModBlocks.FLUID_PIPE, Items.COPPER_INGOT, "Carries water, steam or coolant", "Pipe wrench: connect / disconnect a side");
        block(ModBlocks.CHECK_VALVE, Items.LEVER, "One-way valve: fluid flows the way you look", "when you place it (red ring = outlet).",
                "Only tops up the far side while it is below its level", "(80% when placed; right-click it with an empty hand to set it)", "Pipe Wrench: turn it around");
        block(ModBlocks.PIPE_WALL_PORT, Items.COPPER_BLOCK.weathering().unaffected(),
                "A pipe inside a solid block: build it into a wall", "to take a pipe line through without a hole");
        block(ModBlocks.WATER_PUMP, Items.DISPENSER, "Pumps water from adjacent source blocks", "out of its front face. Power in: the yellow port (on top, or on a side when upright).");
        block(ModBlocks.CIRCULATION_PUMP, Items.DROPPER, "Moves fluid from its back to its front", "(closed loops, condensate). Power in: the yellow ports around it.");
        block(ModBlocks.HEAT_EXCHANGER, Items.COPPER_BLOCK.weathering().unaffected(),
                "Top: coolant in. Bottom: water in. Front: steam out.", "Other sides: coolant out, drawn by a Circulation Pump");
        block(ModBlocks.STEAM_TURBINE, Items.BLAST_FURNACE, "Steam in, electricity out",
                "Top: steam in. Bottom: exhaust steam out (to a Condenser).", "Left, right and back: power out (red ports)");
        block(ModBlocks.CONDENSER, Items.SMOKER, "Turns turbine exhaust back into water",
                "Top: exhaust steam in. Back: river water in.", "Front: river water out. Bottom: condensate (water) out.");
        block(ModBlocks.STEAM_DUMP_VALVE, Items.SMOKER, "Sends steam around the turbines, into the Condensers",
                "Top: steam in. Back: out, to the turbines' exhaust line.", "Opens by itself when the steam is hotter than its setting.",
                "Right-click it with an empty hand to set it. Flow: two turbines' worth.");
        block(ModBlocks.WATER_OUTLET, Items.DROPPER, "Lets piped water out of its front, into a river",
                "(the way you look when you place it). In front of air", "it pours out a water block; a solid block stops it.");
        block(ModBlocks.EXPANSION_TANK, Items.CAULDRON, "Stores water for a loop (8,000 mB per block)",
                "Front: water in. Back: water out.", "Tanks touching each other share their water.",
                "Overflow check valve in, Top up check valve out.");
        block(ModBlocks.POWER_CABLE, Items.FLINT, "Laid block by block; joins its neighbours",
                "For overhead wires, use Copper Wire on poles");
        block(ModBlocks.CABLE_WALL_PORT, Items.CONCRETE.gray(),
                "A ground cable inside a solid block: build it into", "a wall to take a cable line through without a hole");
        block(ModBlocks.POWER_POLE, Items.STICK, "Stack to make tall poles",
                "Right-click with Copper Wire to tie on a wire");
        block(ModBlocks.BATTERY_BANK, Items.LODESTONE, "Stores electricity. Back: power in (yellow).",
                "Left, right, top, bottom: power out (red). Front: charge.", "Batteries touching on their red ports share their charge");
        block(ModBlocks.BACKUP_GENERATOR, Items.FURNACE, "Burns coal, wood or blaze rods for power", "Use it to start up your pumps");
        block(ModBlocks.ELECTRIC_LAMP, Items.REDSTONE_LAMP, "Lights up when powered", "A redstone signal switches it off");
        block(ModBlocks.TEMPERATURE_SENSOR, Items.TARGET, "Outputs redstone from an adjacent", "reactor, heat exchanger or turbine");
    }

    private ModItems() {
    }

    public static boolean isTool(ItemStack stack) {
        return stack.is(PIPE_WRENCH) || stack.is(PLIERS) || stack.is(THERMAL_PROBE) || stack.is(COPPER_WIRE);
    }

    private static <T extends Item> T register(String name, Function<Item.Properties, T> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, GoingNuclear.id(name));
        T item = factory.apply(new Item.Properties().setId(key));
        Registry.register(BuiltInRegistries.ITEM, key, item);
        TAB_ORDER.add(item);
        return item;
    }

    private static void block(Block block, Item fallback, String... tooltip) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, BuiltInRegistries.BLOCK.getKey(block));
        Item item = new NuclearBlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix(), fallback, tooltip);
        Registry.register(BuiltInRegistries.ITEM, key, item);
        TAB_ORDER.add(item);
    }

    public static void init() {
        PolymerCreativeModeTabUtils.registerPolymerCreativeModeTab(GoingNuclear.id("main"), CreativeModeTab.builder(CreativeModeTab.Row.BOTTOM, -1)
                .icon(() -> new ItemStack(GLOWSTONE_FUEL_ROD))
                .title(Component.translatable("itemGroup.goingnuclear"))
                .displayItems((_, entries) -> {
                    for (Item item : TAB_ORDER) entries.accept(item);
                    // The hazmat suit is dyed leather armor (crafted from leather armor + yellow dye + slime ball).
                    entries.accept(RadiationHandler.hazmatPiece(Items.LEATHER_HELMET, "Hazmat Helmet"));
                    entries.accept(RadiationHandler.hazmatPiece(Items.LEATHER_CHESTPLATE, "Hazmat Jacket"));
                    entries.accept(RadiationHandler.hazmatPiece(Items.LEATHER_LEGGINGS, "Hazmat Trousers"));
                    entries.accept(RadiationHandler.hazmatPiece(Items.LEATHER_BOOTS, "Hazmat Boots"));
                })
                .build());
    }
}

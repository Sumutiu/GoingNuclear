package com.sumutiu.goingnuclear.command;

import com.mojang.brigadier.CommandDispatcher;
import com.sumutiu.goingnuclear.config.NuclearConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /goingnuclear help   - quick start guide
 * /goingnuclear reload - reload this world's config/GoingNuclear_Seed_&lt;seed&gt;/GoingNuclear.json (operators)
 */
public final class NuclearCommands {
    private static final String[] GUIDE = {
            "§6§l☢ Going Nuclear - quick start",
            "§e1. Reactor: §7build a hollow box of Reactor Casing (3x3x3 up to 15x15x15). Put a Reactor Controller and at least two Reactor Ports in the walls (not on edges).",
            "§e2. Core: §7inside, place Fuel Channels (and Control Rod Channels within 2 blocks of the fuel). Air or water fills the rest.",
            "§e3. Ports: §7use the Pipe Wrench to set one port to INPUT (water in) and one to OUTPUT (steam or coolant out).",
            "§e4. Water: §7a Water Pump next to water source blocks pushes water out of its front into Fluid Pipes leading to the input port.",
            "§e5. Power: §7Steam Turbines turn steam into electricity: steam in at the top, power out of the red ports on the sides and back (turbines side by side link up). Start with a Backup Generator to run the pumps. A turbine's exhaust steam leaves at its bottom: a Condenser under it (cooled by river water through its back and front) gives full power and turns it back into water; without one it goes into the air at half power. A Steam Dump Valve (steam in at the top, out at the back into the exhaust line) sends steam around the turbines into the condensers to shed heat without making power: set the steam temperature it opens above on its screen.",
            "§e6. Wiring: §7hold Copper Wire and right-click a Power Pole or machine, walk to the next one and right-click it: the wire hangs between them like a lead. Ground Cables join neighbouring blocks. Pipe and Cable Wall Ports take lines through walls. Furnaces, blast furnaces and smokers touching the grid smelt with electricity.",
            "§e7. Run it: §7load rods on the Refuel tab of the controller GUI, press START, then withdraw the control rods slowly. Watch the temperature! After a shutdown, xenon (a neutron poison) builds up for a few minutes and can keep the reactor from full power until it fades; once running, it burns off and the power climbs by itself.",
            "§e8. Closed loop: §7switch the controller to Pressurized. Heat Exchanger: coolant from an OUTPUT port into the top, water into the bottom, steam out of the front; a Circulation Pump draws the coolant from a side and sends it back to an INPUT port. Steam is only made from coolant hotter than 150 °C. Pipe water into a second INPUT port to fill the loop: the reactor only takes it below 90% full. Keep the water and coolant lines apart (one fluid per pipe). An Expansion Tank (water in at the front, out at the back; touching tanks share their water) keeps a loop topped up: an Overflow check valve into it at 90%, a Top up check valve out of it at 85%. Turbines leak 0.5% of their steam at the shaft seals and heat exchangers 0.1% of the coolant, so keep make-up water on both loops.",
            "§e9. Autopilot: §7the Rod control tab of the controller GUI lets the rods hold a temperature, hold a power level, or follow a redstone signal.",
            "§e10. Fuel cycle: §7spent Redstone and Glowstone Fuel Rods come out as depleted rods of their kind. An Enrichment Centrifuge (20 kW, power in at the back) turns each, with 8 dust of its kind, into an Enriched Fuel Rod: more power from the same fuel as a fresh rod (redstone 30 kW instead of 20, glowstone 75 kW instead of 55), so it burns out sooner. Enriched rods burn down into Depleted Fuel Rods, which cannot be enriched again (smelt them for iron).",
            "§e11. Radiation: §7a Geiger Counter measures it; a hazmat suit (leather armor + yellow dye + slime ball) blocks it. Every kind of depleted rod is radioactive while carried.",
            "§c Above 1200 °C the core gets damaged. At 0% integrity it melts down."
    };

    private NuclearCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("goingnuclear")
                .executes(ctx -> help(ctx.getSource()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("reload")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(ctx -> {
                            NuclearConfig.reload();
                            ctx.getSource().sendSuccess(() -> Component.literal("Going Nuclear config reloaded.").withStyle(ChatFormatting.GREEN), true);
                            return 1;
                        })));
    }

    private static int help(CommandSourceStack source) {
        for (String line : GUIDE) source.sendSystemMessage(Component.literal(line));
        return 1;
    }
}

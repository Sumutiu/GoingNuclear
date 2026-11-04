"""
Generates block/item models, item definitions, language files, recipes, loot tables and tags.
Run from the project root:  python3 tools/gen_data.py
"""
import json
import os
import shutil

NS = "goingnuclear"
RES = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
ASSETS = os.path.join(RES, "assets", NS)
DATA = os.path.join(RES, "data", NS)


# Everything in these folders is written by this script: they are emptied first, so nothing it no longer makes
# is left behind.
for owned in (os.path.join(ASSETS, "models"), os.path.join(ASSETS, "items"), os.path.join(ASSETS, "lang"),
              os.path.join(DATA, "recipe"), os.path.join(DATA, "loot_table"), os.path.join(DATA, "lang"),
              os.path.join(RES, "data", "minecraft", "tags")):
    shutil.rmtree(owned, ignore_errors=True)


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def t(name):
    return f"{NS}:block/{name}"


# ---------------------------------------------------------------- block models
def cube_all(name, texture):
    write(os.path.join(ASSETS, "models", "block", name + ".json"),
          {"parent": "minecraft:block/cube_all", "textures": {"all": t(texture)}})


def cube(name, north, south, east, west, up, down, particle=None):
    write(os.path.join(ASSETS, "models", "block", name + ".json"), {
        "parent": "minecraft:block/cube",
        "textures": {
            "particle": t(particle or east),
            "north": t(north), "south": t(south), "east": t(east),
            "west": t(west), "up": t(up), "down": t(down),
        },
    })


cube_all("reactor_casing", "reactor_casing")
cube_all("pipe_wall_port", "pipe_wall_port")
cube_all("cable_wall_port", "cable_wall_port")
cube_all("reactor_port_input", "reactor_port_input")
cube_all("reactor_port_output", "reactor_port_output")
cube_all("corium", "corium")
cube_all("electric_lamp", "electric_lamp")
cube_all("electric_lamp_on", "electric_lamp_on")
cube_all("temperature_sensor", "temperature_sensor")
cube_all("temperature_sensor_on", "temperature_sensor_on")
cube("reactor_controller", "reactor_controller_front", "reactor_casing", "reactor_casing", "reactor_casing", "reactor_casing", "reactor_casing")
# Water pump: takes power only through the yellow power-in port: on top, or on the north face when it points up
# or down (separate models for those, so the port is exactly where the code expects it, turned to its side).
cube("water_pump", "water_pump_front", "machine_side", "machine_side", "machine_side", "machine_power_in", "machine_top")
cube("water_pump_up", "machine_power_in", "machine_side", "machine_side", "machine_side", "water_pump_front", "machine_side", particle="machine_side")
cube("water_pump_down", "machine_power_in", "machine_side", "machine_side", "machine_side", "machine_side", "water_pump_front", particle="machine_side")
# Circulation pump: pipes front and back, power in on the four faces around them.
cube("circulation_pump", "circulation_pump_front", "circulation_pump_back", "machine_power_in", "machine_power_in", "machine_power_in",
     "machine_power_in", particle="machine_side")
cube("heat_exchanger", "heat_exchanger_front", "heat_exchanger_side_out", "heat_exchanger_side_out", "heat_exchanger_side_out", "heat_exchanger_top", "heat_exchanger_bottom")
# Steam turbine: rotor on the front, steam in at the top, condensate (water) out at the bottom, power out on the
# left, right and back.
cube("steam_turbine", "steam_turbine_front", "steam_turbine_power_out", "steam_turbine_power_out", "steam_turbine_power_out",
     "steam_turbine_top", "steam_turbine_bottom", particle="steam_turbine_power_out")
# Condenser: cooling water out on the front, cooling water in at the back, exhaust steam in at the top,
# condensate out at the bottom, tube bundle on the sides.
cube("condenser", "condenser_front", "condenser_back", "condenser_side", "condenser_side", "condenser_top", "condenser_bottom",
     particle="condenser_side")
# Steam dump valve: gauge and handwheel on the front (one model per gauge reading), steam in at the top, out
# at the back, the valve body on the left and right, a plain bottom.
for opening in range(3):
    side = "steam_dump_valve_side"
    cube(f"steam_dump_valve_{opening}", f"steam_dump_valve_front_{opening}", "steam_dump_valve_back", side, side,
         "steam_dump_valve_top", "steam_dump_valve_bottom", particle=side)
# Water outlet: drain spout on the front, water in on every other face.
# Expansion tank: water in at the front, out at the back, a sight glass on the sides (one model per level shown).
for level in range(5):
    side = f"expansion_tank_side_{level}"
    cube(f"expansion_tank_{level}", "expansion_tank_front", "expansion_tank_back", side, side, "expansion_tank_top",
         "expansion_tank_top", particle=side)
cube("water_outlet", "water_outlet_front", "water_outlet_in", "water_outlet_in", "water_outlet_in", "water_outlet_in", "water_outlet_in",
     particle="water_outlet_in")
# Battery bank: charge gauge on the front, power in on the back, power out on the left, right, top and bottom.
for level in range(5):
    gauge = f"battery_side_{level}"
    out = "battery_power_out"
    cube(f"battery_bank_{level}", gauge, "battery_back", out, out, out, out, particle=gauge)
# Enrichment centrifuge: window on the front, power in on the back, lid on top.
for suffix in ("", "_on"):
    cube("enrichment_centrifuge" + suffix, "enrichment_centrifuge_front" + suffix, "machine_power_in", "enrichment_centrifuge_side",
         "enrichment_centrifuge_side", "enrichment_centrifuge_top", "machine_top", particle="enrichment_centrifuge_side")
cube("backup_generator", "backup_generator_front", "machine_side", "machine_side", "machine_side", "machine_top", "machine_top")
cube("backup_generator_on", "backup_generator_front_on", "machine_side", "machine_side", "machine_side", "machine_top", "machine_top")

# ---------------------------------------------------------------- items
GENERATED = {
    "copper_wire": "minecraft:item/generated",
    "redstone_fuel_rod": "minecraft:item/handheld",
    "glowstone_fuel_rod": "minecraft:item/handheld",
    "depleted_fuel_rod": "minecraft:item/handheld",
    "depleted_redstone_fuel_rod": "minecraft:item/handheld",
    "depleted_glowstone_fuel_rod": "minecraft:item/handheld",
    "enriched_redstone_fuel_rod": "minecraft:item/handheld",
    "enriched_glowstone_fuel_rod": "minecraft:item/handheld",
    "control_rod": "minecraft:item/handheld",
    "pipe_wrench": "minecraft:item/handheld",
    "pliers": "minecraft:item/handheld",
    "thermal_probe": "minecraft:item/generated",
    "geiger_counter": "minecraft:item/generated",
    "check_valve": "minecraft:item/generated",
    "fluid_pipe": "minecraft:item/generated",
    "power_cable": "minecraft:item/generated",
    "power_pole": "minecraft:item/generated",
}
for name, parent in GENERATED.items():
    write(os.path.join(ASSETS, "models", "item", name + ".json"),
          {"parent": parent, "textures": {"layer0": f"{NS}:item/{name}"}})
    write(os.path.join(ASSETS, "items", name + ".json"),
          {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})

def element(frm, to, texture):
    faces = {face: {"texture": texture} for face in ("north", "south", "east", "west", "up", "down")}
    return {"from": frm, "to": to, "faces": faces}


def element_item_model(name, textures, elements):
    """3D item model built from boxes (coordinates in pixels, 0..16)."""
    write(os.path.join(ASSETS, "models", "item", name + ".json"), {
        "parent": "minecraft:block/block",
        "textures": textures,
        "elements": elements,
    })
    write(os.path.join(ASSETS, "items", name + ".json"),
          {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})


# Fuel channel: tie plates with a 3x3 bundle of fuel pins, like the placed block with a rod loaded.
fuel = [
    element([1.5, 0, 1.5], [14.5, 1.5, 14.5], "#plate"),
    element([1.5, 14.5, 1.5], [14.5, 16, 14.5], "#plate"),
]
for x in (3, 7, 11):
    for z in (3, 7, 11):
        fuel.append(element([x, 1.5, z], [x + 2, 14.5, z + 2], "#pin"))
element_item_model("fuel_channel", {
    "particle": "minecraft:block/iron_block",
    "plate": "minecraft:block/iron_block",
    "pin": "minecraft:block/redstone_block",
}, fuel)

# Control rod channel: guide posts, drive housing on top and the black cross-shaped absorber blade.
control = []
for x in (1.5, 13):
    for z in (1.5, 13):
        control.append(element([x, 0, z], [x + 1.5, 16, z + 1.5], "#frame"))
control.append(element([2.5, 14.5, 2.5], [13.5, 16, 13.5], "#housing"))
control.append(element([3, 1, 7.25], [13, 14.5, 8.75], "#blade"))
control.append(element([7.25, 1, 3], [8.75, 14.5, 13], "#blade"))
element_item_model("control_rod_channel", {
    "particle": "minecraft:block/iron_block",
    "frame": "minecraft:block/gray_concrete",
    "housing": "minecraft:block/iron_block",
    "blade": "minecraft:block/coal_block",
}, control)

# Reactor core models drawn by item display entities for players with the resource pack (one entity per
# block instead of a dozen block displays). Same shapes as the block display versions in the Java code.
def core_model(name, textures, elements):
    write(os.path.join(ASSETS, "models", "core", name + ".json"), {"textures": textures, "elements": elements})
    write(os.path.join(ASSETS, "items", "core", name + ".json"),
          {"model": {"type": "minecraft:model", "model": f"{NS}:core/{name}"}})


# Pins per rod (the names are the fuel channel's rod states). Enriched fuel gets gold tie plates.
CORE_PINS = {"redstone": "minecraft:block/redstone_block", "glowstone": "minecraft:block/glowstone",
             "depleted": "minecraft:block/gray_concrete",
             "depleted_redstone": "minecraft:block/nether_wart_block", "depleted_glowstone": "minecraft:block/end_stone",
             "enriched_redstone": "minecraft:block/redstone_block", "enriched_glowstone": "minecraft:block/glowstone"}
# Stacked fuel channels join into one assembly: only the bottom of a stack keeps its lower tie plate and only
# the top keeps its upper one. Suffix: "" = lone channel, _bottom / _middle / _top = place in a stack.
FUEL_STACK = {"": (True, True), "_bottom": (True, False), "_middle": (False, False), "_top": (False, True)}
for rod in ("empty",) + tuple(CORE_PINS):
    for suffix, (bottom_plate, top_plate) in FUEL_STACK.items():
        low = 0.96 if bottom_plate else 0
        high = 15.04 if top_plate else 16
        parts = []
        if bottom_plate:
            parts.append(element([1.6, 0, 1.6], [14.4, 0.96, 14.4], "#plate"))
        if top_plate:
            parts.append(element([1.6, 15.04, 1.6], [14.4, 16, 14.4], "#plate"))
        plate = "minecraft:block/gold_block" if rod.startswith("enriched") else "minecraft:block/iron_block"
        textures = {"particle": plate, "plate": plate}
        if rod == "empty":
            textures["post"] = "minecraft:block/light_gray_concrete"
            for x in (1.92, 12.8):
                for z in (1.92, 12.8):
                    parts.append(element([x, low, z], [x + 1.28, high, z + 1.28], "#post"))
        else:
            textures["pin"] = CORE_PINS[rod]
            for x in (4, 8, 12):
                for z in (4, 8, 12):
                    parts.append(element([x - 0.96, low, z - 0.96], [x + 0.96, high, z + 0.96], "#pin"))
        core_model("fuel_" + rod + suffix, textures, parts)

frame = []
for x in (1.6, 13.12):
    for z in (1.6, 13.12):
        frame.append(element([x, 0, z], [x + 1.28, 16, z + 1.28], "#frame"))
# Lower channels of a stack: just the posts (the drive housing sits on top of the stack).
core_model("control_frame_lower", {"particle": "minecraft:block/iron_block", "frame": "minecraft:block/gray_concrete"},
           list(frame))
frame.append(element([2.88, 15.04, 2.88], [13.12, 16, 13.12], "#housing"))
core_model("control_frame", {"particle": "minecraft:block/iron_block", "frame": "minecraft:block/gray_concrete",
                             "housing": "minecraft:block/iron_block"}, frame)
core_model("control_blade", {"particle": "minecraft:block/coal_block", "blade": "minecraft:block/coal_block"}, [
    element([3.2, 0, 7.44], [12.8, 16, 8.56], "#blade"),
    element([7.44, 0, 3.2], [8.56, 16, 12.8], "#blade"),
])

BLOCK_ITEM_MODELS = {
    "reactor_casing": t("reactor_casing"),
    "pipe_wall_port": t("pipe_wall_port"),
    "cable_wall_port": t("cable_wall_port"),
    "reactor_glass": "minecraft:block/glass",
    "reactor_controller": t("reactor_controller"),
    "reactor_port": t("reactor_port_input"),
    "water_pump": t("water_pump"),
    "circulation_pump": t("circulation_pump"),
    "heat_exchanger": t("heat_exchanger"),
    "steam_turbine": t("steam_turbine"),
    "condenser": t("condenser"),
    "steam_dump_valve": t("steam_dump_valve_0"),
    "enrichment_centrifuge": t("enrichment_centrifuge"),
    "water_outlet": t("water_outlet"),
    "expansion_tank": t("expansion_tank_0"),
    "battery_bank": t("battery_bank_4"),
    "backup_generator": t("backup_generator"),
    "electric_lamp": t("electric_lamp_on"),
    "temperature_sensor": t("temperature_sensor"),
}
for name, model in BLOCK_ITEM_MODELS.items():
    write(os.path.join(ASSETS, "items", name + ".json"), {"model": {"type": "minecraft:model", "model": model}})

# ---------------------------------------------------------------- language
LANG = {
    "itemGroup.goingnuclear": "Going Nuclear",
    "block.goingnuclear.reactor_casing": "Reactor Casing",
    "block.goingnuclear.reactor_glass": "Reactor Glass",
    "block.goingnuclear.reactor_controller": "Reactor Control Box",
    "block.goingnuclear.reactor_port": "Reactor Port",
    "block.goingnuclear.fuel_channel": "Fuel Channel",
    "block.goingnuclear.control_rod_channel": "Control Rod Channel",
    "block.goingnuclear.corium": "Corium",
    "block.goingnuclear.fluid_pipe": "Fluid Pipe",
    "block.goingnuclear.pipe_wall_port": "Pipe Wall Port",
    "block.goingnuclear.check_valve": "Check Valve",
    "block.goingnuclear.cable_wall_port": "Cable Wall Port",
    "block.goingnuclear.water_pump": "Water Pump",
    "block.goingnuclear.circulation_pump": "Circulation Pump",
    "block.goingnuclear.heat_exchanger": "Heat Exchanger",
    "block.goingnuclear.steam_turbine": "Steam Turbine Generator",
    "block.goingnuclear.condenser": "Condenser",
    "block.goingnuclear.steam_dump_valve": "Steam Dump Valve",
    "block.goingnuclear.enrichment_centrifuge": "Enrichment Centrifuge",
    "block.goingnuclear.water_outlet": "Water Outlet",
    "block.goingnuclear.expansion_tank": "Expansion Tank",
    "block.goingnuclear.power_cable": "Ground Cable",
    "block.goingnuclear.power_pole": "Power Pole",
    "block.goingnuclear.battery_bank": "Battery Bank",
    "block.goingnuclear.backup_generator": "Backup Generator",
    "block.goingnuclear.electric_lamp": "Electric Lamp",
    "block.goingnuclear.temperature_sensor": "Temperature Sensor",
    "item.goingnuclear.pipe_wrench": "Pipe Wrench",
    "item.goingnuclear.pliers": "Pliers",
    "item.goingnuclear.thermal_probe": "Thermal Probe",
    "item.goingnuclear.geiger_counter": "Geiger Counter",
    "item.goingnuclear.copper_wire": "Copper Wire",
    "item.goingnuclear.redstone_fuel_rod": "Redstone Fuel Rod",
    "item.goingnuclear.depleted_redstone_fuel_rod": "Depleted Redstone Fuel Rod",
    "item.goingnuclear.enriched_redstone_fuel_rod": "Enriched Redstone Fuel Rod",
    "item.goingnuclear.glowstone_fuel_rod": "Glowstone Fuel Rod",
    "item.goingnuclear.depleted_glowstone_fuel_rod": "Depleted Glowstone Fuel Rod",
    "item.goingnuclear.enriched_glowstone_fuel_rod": "Enriched Glowstone Fuel Rod",
    "item.goingnuclear.depleted_fuel_rod": "Depleted Fuel Rod",
    "item.goingnuclear.control_rod": "Control Rod",
}
# Assets copy: used by the resource pack. Data copy: used by Server Translations API for players without the pack.
write(os.path.join(ASSETS, "lang", "en_us.json"), LANG)
write(os.path.join(DATA, "lang", "en_us.json"), LANG)

# ---------------------------------------------------------------- recipes
def shaped(name, pattern, key, result, count=1):
    write(os.path.join(DATA, "recipe", name + ".json"), {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": pattern,
        "key": key,
        "result": {"id": result, "count": count},
    })


def shapeless(name, ingredients, result, count=1):
    write(os.path.join(DATA, "recipe", name + ".json"), {
        "type": "minecraft:crafting_shapeless",
        "category": "misc",
        "ingredients": ingredients,
        "result": {"id": result, "count": count},
    })


N = NS + ":"
shaped("reactor_casing", ["SIS", "I I", "SIS"], {"S": "minecraft:smooth_stone", "I": "minecraft:iron_ingot"}, N + "reactor_casing", 4)
shapeless("reactor_glass", [N + "reactor_casing", "minecraft:glass"], N + "reactor_glass", 2)
shaped("reactor_controller", ["CCC", "RXR", "CCC"], {"C": N + "reactor_casing", "R": "minecraft:redstone", "X": "minecraft:comparator"}, N + "reactor_controller")
shaped("reactor_port", ["CHC"], {"C": N + "reactor_casing", "H": "minecraft:hopper"}, N + "reactor_port", 2)
shaped("fuel_channel", ["IGI", "I I", "IGI"], {"I": "minecraft:iron_ingot", "G": "minecraft:glass"}, N + "fuel_channel", 2)
shaped("control_rod_channel", ["IBI", "I I", "IBI"], {"I": "minecraft:iron_ingot", "B": "minecraft:iron_bars"}, N + "control_rod_channel", 2)
shaped("redstone_fuel_rod", ["IRI", "IBI", "IRI"], {"I": "minecraft:iron_ingot", "R": "minecraft:redstone", "B": "minecraft:redstone_block"}, N + "redstone_fuel_rod")
shaped("glowstone_fuel_rod", ["IGI", "IBI", "IGI"], {"I": "minecraft:iron_ingot", "G": "minecraft:glowstone_dust", "B": "minecraft:glowstone"}, N + "glowstone_fuel_rod")
shaped("control_rod", ["ICI", "ICI", "ICI"], {"I": "minecraft:iron_ingot", "C": "#minecraft:coals"}, N + "control_rod", 2)
shaped("fluid_pipe", ["CGC"], {"C": "minecraft:copper_ingot", "G": "minecraft:glass"}, N + "fluid_pipe", 8)
shaped("water_pump", ["IPI", "XBX", "IRI"], {"I": "minecraft:iron_ingot", "P": N + "fluid_pipe", "X": "minecraft:piston", "B": "minecraft:bucket", "R": "minecraft:redstone"}, N + "water_pump")
shaped("circulation_pump", ["IPI", "XRX", "IPI"], {"I": "minecraft:iron_ingot", "P": N + "fluid_pipe", "X": "minecraft:piston", "R": "minecraft:redstone_block"}, N + "circulation_pump")
shaped("heat_exchanger", ["IPI", "CBC", "IPI"], {"I": "minecraft:iron_ingot", "P": N + "fluid_pipe", "C": "minecraft:copper_block", "B": "minecraft:cauldron"}, N + "heat_exchanger")
shaped("steam_turbine", ["IPI", "XGX", "IWI"], {"I": "minecraft:iron_ingot", "P": N + "fluid_pipe", "X": "minecraft:iron_block", "G": "minecraft:redstone_block", "W": N + "copper_wire"}, N + "steam_turbine")
shaped("condenser", ["IPI", "CBC", "IPI"], {"I": "minecraft:iron_ingot", "P": N + "fluid_pipe", "C": "minecraft:copper_ingot", "B": "minecraft:bucket"}, N + "condenser")
shaped("enrichment_centrifuge", ["IGI", "CPC", "IWI"], {"I": "minecraft:iron_ingot", "G": "minecraft:glass", "C": "minecraft:copper_ingot", "P": N + "circulation_pump", "W": N + "copper_wire"}, N + "enrichment_centrifuge")
shaped("steam_dump_valve", ["ILI", "ICI", "IPI"], {"I": "minecraft:iron_ingot", "L": "minecraft:lever", "C": N + "check_valve", "P": N + "fluid_pipe"}, N + "steam_dump_valve")
shaped("expansion_tank", ["IGI", "PBP", "IGI"], {"I": "minecraft:iron_ingot", "G": "minecraft:glass", "P": N + "fluid_pipe", "B": "minecraft:bucket"}, N + "expansion_tank")
shaped("water_outlet", ["IPI", " B "], {"I": "minecraft:iron_ingot", "P": N + "fluid_pipe", "B": "minecraft:iron_bars"}, N + "water_outlet")
shapeless("copper_wire", ["minecraft:copper_ingot", "minecraft:copper_ingot"], N + "copper_wire", 8)
shaped("power_cable", ["KWK"], {"K": "minecraft:dried_kelp", "W": N + "copper_wire"}, N + "power_cable", 4)
shaped("check_valve", [" L ", "PIP"], {"L": "minecraft:lever", "P": N + "fluid_pipe", "I": "minecraft:iron_ingot"}, N + "check_valve")
shaped("pipe_wall_port", [" S ", "SPS", " S "], {"S": "minecraft:smooth_stone", "P": N + "fluid_pipe"}, N + "pipe_wall_port")
shaped("cable_wall_port", [" S ", "SCS", " S "], {"S": "minecraft:smooth_stone", "C": N + "power_cable"}, N + "cable_wall_port")
shaped("power_pole", ["NWN", " L ", " L "], {"N": "minecraft:iron_nugget", "W": N + "copper_wire", "L": "#minecraft:logs"}, N + "power_pole", 3)
shaped("battery_bank", ["IWI", "RCR", "IWI"], {"I": "minecraft:iron_ingot", "W": N + "copper_wire", "R": "minecraft:redstone_block", "C": "minecraft:copper_block"}, N + "battery_bank")
shaped("backup_generator", ["IWI", "IFI", "IRI"], {"I": "minecraft:iron_ingot", "W": N + "copper_wire", "F": "minecraft:furnace", "R": "minecraft:redstone"}, N + "backup_generator")
shaped("electric_lamp", [" G ", "GWG", " I "], {"G": "minecraft:glass", "W": N + "copper_wire", "I": "minecraft:iron_ingot"}, N + "electric_lamp", 2)
shaped("temperature_sensor", ["Q", "C", "I"], {"Q": "minecraft:quartz", "C": "minecraft:comparator", "I": "minecraft:iron_ingot"}, N + "temperature_sensor")
shaped("pipe_wrench", ["I I", " I ", " I "], {"I": "minecraft:iron_ingot"}, N + "pipe_wrench")
shaped("pliers", ["I I", " I ", "S S"], {"I": "minecraft:iron_ingot", "S": "minecraft:stick"}, N + "pliers")
shaped("thermal_probe", [" G ", "IRI", "ICI"], {"G": "minecraft:glass", "I": "minecraft:iron_ingot", "R": "minecraft:redstone", "C": "minecraft:copper_ingot"}, N + "thermal_probe")
shaped("geiger_counter", ["IGI", "CRC", "III"], {"G": "minecraft:glass_pane", "I": "minecraft:iron_ingot", "R": "minecraft:redstone", "C": "minecraft:copper_ingot"}, N + "geiger_counter")

# Hazmat suit: leather armor + yellow dye + slime ball (sealant). The result is dyed leather armor marked with
# custom data that RadiationHandler.isHazmat() looks for; RadiationHandler.hazmatPiece() builds the same stack.
HAZMAT_COLOR = 0xE8C21E
for piece, name in (("helmet", "Hazmat Helmet"), ("chestplate", "Hazmat Jacket"), ("leggings", "Hazmat Trousers"), ("boots", "Hazmat Boots")):
    write(os.path.join(DATA, "recipe", "hazmat_" + piece + ".json"), {
        "type": "minecraft:crafting_shapeless",
        "category": "equipment",
        "ingredients": ["minecraft:leather_" + piece, "minecraft:yellow_dye", "minecraft:slime_ball"],
        "result": {
            "id": "minecraft:leather_" + piece,
            "count": 1,
            "components": {
                "minecraft:item_name": {"text": name},
                "minecraft:dyed_color": HAZMAT_COLOR,
                "minecraft:lore": [
                    {"text": "☢ Radiation protection: 25%", "color": "yellow", "italic": False},
                    {"text": "Wear all four pieces for full protection", "color": "gray", "italic": False},
                ],
                "minecraft:custom_data": {"goingnuclear_hazmat": True},
            },
        },
    })
# Every kind of depleted rod smelts back into iron (the redstone and glowstone ones can be enriched instead).
for spent in ("depleted_fuel_rod", "depleted_redstone_fuel_rod", "depleted_glowstone_fuel_rod"):
    write(os.path.join(DATA, "recipe", "iron_from_" + spent + ".json"), {
        "type": "minecraft:blasting",
        "category": "misc",
        "cookingtime": 200,
        "experience": 0.2,
        "ingredient": N + spent,
        "result": {"id": "minecraft:iron_ingot", "count": 2},
    })

# ---------------------------------------------------------------- loot tables + tags
BLOCKS = ["reactor_casing", "reactor_glass", "reactor_controller", "reactor_port", "fuel_channel", "control_rod_channel",
          "fluid_pipe", "water_pump", "circulation_pump", "heat_exchanger", "steam_turbine", "power_cable", "power_pole",
          "battery_bank", "backup_generator", "electric_lamp", "temperature_sensor", "pipe_wall_port", "cable_wall_port", "check_valve",
          "water_outlet", "condenser", "enrichment_centrifuge", "steam_dump_valve", "expansion_tank"]
for name in BLOCKS:
    write(os.path.join(DATA, "loot_table", "blocks", name + ".json"), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": N + name}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })

pickaxe = [N + b for b in BLOCKS if b not in ("power_pole", "power_cable")]
pickaxe.append(N + "corium")
write(os.path.join(RES, "data", "minecraft", "tags", "block", "mineable", "pickaxe.json"), {"replace": False, "values": pickaxe})
write(os.path.join(RES, "data", "minecraft", "tags", "block", "mineable", "axe.json"), {"replace": False, "values": [N + "power_pole"]})
write(os.path.join(RES, "data", "minecraft", "tags", "block", "needs_diamond_tool.json"), {"replace": False, "values": [N + "corium"]})
print("data written")

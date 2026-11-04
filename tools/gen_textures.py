"""
Generates the 16x16 pixel-art textures and the mod icon for Going Nuclear.
Run from the project root:  python3 tools/gen_textures.py

Every texture gets its own random noise, seeded by its name (see texture()), so adding, removing or changing
one texture never changes any other.
"""
import math
import os
import random
import shutil
from PIL import Image

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "goingnuclear")
BLOCK = os.path.join(ROOT, "textures", "block")
ITEM = os.path.join(ROOT, "textures", "item")
# Every texture is written by this script: the folders are emptied first, so nothing it no longer makes is left
# behind.
for owned in (BLOCK, ITEM):
    shutil.rmtree(owned, ignore_errors=True)
    os.makedirs(owned)

rng = random.Random()

STEEL = (118, 124, 132)
STEEL_DARK = (78, 83, 90)
STEEL_LIGHT = (160, 166, 174)
RIVET = (190, 195, 200)
COPPER = (192, 108, 70)
COPPER_DARK = (140, 72, 46)
COPPER_LIGHT = (226, 150, 102)
YELLOW = (236, 196, 40)
BLACK = (30, 30, 34)
RED = (200, 40, 36)
BLUE = (60, 120, 220)
ORANGE = (240, 130, 30)
GREEN = (70, 200, 80)
WHITE = (235, 235, 235)
GOLD = (222, 178, 52)
GOLD_LIGHT = (255, 226, 120)
# Reactor walls: dark gunmetal plating.
REACTOR = (66, 70, 76)
REACTOR_DARK = (42, 45, 50)
REACTOR_LIGHT = (92, 97, 104)
REACTOR_RIVET = (126, 131, 138)

# Panel colours of the machines.
MACHINE_PANEL = dict(base=(100, 106, 114))
BATTERY_PANEL = dict(base=(70, 74, 82))
COPPER_PANEL = dict(base=COPPER, dark=COPPER_DARK, light=COPPER_LIGHT)
CONDENSER_PANEL = dict(base=(104, 116, 128), dark=(70, 80, 92), light=(150, 162, 176))
CENTRIFUGE_PANEL = dict(base=(96, 102, 110), dark=(64, 68, 74), light=(140, 146, 154))
VALVE_PANEL = dict(base=(92, 100, 112), dark=(60, 66, 76), light=(134, 142, 154))

# Port colours (flange around the socket). Faces with one fluid show that fluid; pumps show the direction.
FLANGE_WATER = ((60, 120, 220), (36, 78, 160), (110, 160, 240))      # blue: water / coolant in
FLANGE_HOT = ((240, 130, 30), (180, 90, 16), (255, 180, 90))         # orange: hot coolant
FLANGE_COLD = ((40, 200, 215), (24, 140, 155), (120, 230, 240))      # cyan: cold coolant
FLANGE_STEAM = ((214, 217, 223), (160, 164, 172), (245, 246, 248))   # white: steam
FLANGE_OUT = ((70, 200, 80), (40, 140, 50), (130, 230, 140))         # green: pump outlet
FLANGE_PLAIN = ((84, 89, 96), (56, 60, 66), (112, 118, 126))         # dark steel: pipe through a wall
FLANGE_RUBBER = ((48, 48, 52), (26, 26, 28), (74, 74, 80))           # black rubber: cable through a wall
FLANGE_EXHAUST = ((160, 186, 198), (110, 134, 146), (200, 220, 228))  # misty grey-blue: exhaust steam (turbine -> condenser)
FLANGE_WARM = ((150, 90, 200), (100, 56, 140), (190, 140, 230))       # purple: warm cooling water out
FLANGE_POWER = ((236, 196, 40), (176, 140, 20), (255, 226, 110))      # yellow: power in
FLANGE_POWER_OUT = ((200, 50, 40), (140, 30, 26), (236, 104, 92))     # red: power out
SEAM = (34, 36, 40)


# ---------------- drawing ----------------

def img(size=16, color=(0, 0, 0, 0)):
    return Image.new("RGBA", (size, size), color)


def noise(c, amount=8):
    d = rng.randint(-amount, amount)
    return tuple(max(0, min(255, v + d)) for v in c[:3]) + ((c[3],) if len(c) == 4 else (255,))


def fill(im, x0, y0, x1, y1, c, jitter=0):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            im.putpixel((x, y), noise(c, jitter) if jitter else (c + (255,) if len(c) == 3 else c))


def px(im, x, y, c):
    im.putpixel((x, y), c + (255,) if len(c) == 3 else c)


def panel(base=STEEL, dark=STEEL_DARK, light=STEEL_LIGHT, rivets=True, rivet=RIVET):
    im = img()
    fill(im, 0, 0, 15, 15, base, 6)
    for i in range(16):
        px(im, i, 0, light)
        px(im, 0, i, light)
        px(im, i, 15, dark)
        px(im, 15, i, dark)
    if rivets:
        for (x, y) in [(2, 2), (13, 2), (2, 13), (13, 13)]:
            px(im, x, y, rivet)
            px(im, x + 1, y + 1, dark)
    return im


def reactor_panel():
    return panel(REACTOR, REACTOR_DARK, REACTOR_LIGHT, rivet=REACTOR_RIVET)


def flanged(im, flange, seam=SEAM, socket="pipe", bolt=REACTOR_RIVET):
    """
    Square flange around a square socket, drawn over a panel. A pipe socket is the size and colour of a
    pipe's middle piece (so connected pipes line up with it), a cable socket is a rubber grommet with
    the copper conductor in the middle.
    """
    ring, ring_dark, ring_light = flange
    fill(im, 2, 2, 13, 13, ring)
    for i in range(2, 14):
        px(im, i, 2, ring_light)
        px(im, 2, i, ring_light)
        px(im, i, 13, ring_dark)
        px(im, 13, i, ring_dark)
    for (x, y) in [(3, 3), (12, 3), (3, 12), (12, 12)]:
        px(im, x, y, bolt)
    # Dark seam between the flange and the socket.
    fill(im, 4, 4, 11, 11, seam)
    if socket == "pipe":
        fill(im, 5, 5, 10, 10, COPPER)
        for i in range(5, 11):
            px(im, i, 5, COPPER_LIGHT)
            px(im, 5, i, COPPER_LIGHT)
            px(im, i, 10, COPPER_DARK)
            px(im, 10, i, COPPER_DARK)
        fill(im, 7, 7, 8, 8, (46, 26, 18))
    else:
        fill(im, 5, 5, 10, 10, (30, 30, 34))
        for i in range(5, 11):
            px(im, i, 5, (58, 58, 64))
            px(im, 5, i, (58, 58, 64))
            px(im, i, 10, (18, 18, 20))
            px(im, 10, i, (18, 18, 20))
        fill(im, 7, 7, 8, 8, COPPER_LIGHT)
    return im


def port(style, flange, **kwargs):
    """A flanged port on a panel of the given style."""
    return lambda: flanged(panel(**style), flange, **kwargs)


def texture(folder, name, draw, *args):
    """Draws one texture with its own noise, seeded by its name, and saves it."""
    rng.seed(name)
    draw(*args).save(os.path.join(folder, name + ".png"))


# ---------------- reactor ----------------

def controller_front():
    im = reactor_panel()
    fill(im, 3, 3, 12, 8, (20, 40, 30))
    for x in range(4, 12):
        px(im, x, 6 - (x % 3 == 0), (80, 230, 120))
    px(im, 4, 11, GREEN)
    px(im, 7, 11, YELLOW)
    px(im, 10, 11, RED)
    fill(im, 3, 13, 12, 13, REACTOR_DARK)
    return im


def corium():
    im = img()
    for x in range(16):
        for y in range(16):
            r = rng.random()
            if r < 0.25:
                px(im, x, y, (255, 200, 60))
            elif r < 0.6:
                px(im, x, y, (230, 100, 20))
            elif r < 0.85:
                px(im, x, y, (120, 40, 10))
            else:
                px(im, x, y, (50, 30, 25))
    return im


texture(BLOCK, "reactor_casing", reactor_panel)
texture(BLOCK, "reactor_controller_front", controller_front)
texture(BLOCK, "reactor_port_input", lambda: flanged(reactor_panel(), FLANGE_WATER, seam=REACTOR_DARK))
texture(BLOCK, "reactor_port_output", lambda: flanged(reactor_panel(), FLANGE_HOT, seam=REACTOR_DARK))
texture(BLOCK, "corium", corium)


# ---------------- machines ----------------

def machine_side():
    im = panel(**MACHINE_PANEL)
    fill(im, 5, 5, 10, 10, STEEL_DARK)
    fill(im, 6, 6, 9, 9, (60, 64, 70))
    return im


def machine_top():
    im = panel(**MACHINE_PANEL)
    for y in (5, 7, 9):
        fill(im, 3, y, 12, y, STEEL_DARK)
    return im


def turbine_front():
    im = panel()
    for x in range(16):
        for y in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if d <= 6.2:
                px(im, x, y, (55, 58, 64))
    for (x, y) in [(7, 3), (8, 4), (12, 7), (11, 8), (8, 12), (7, 11), (3, 8), (4, 7), (7, 7), (8, 8), (7, 8), (8, 7)]:
        px(im, x, y, STEEL_LIGHT)
    return im


def battery_gauge(level):
    """Battery front: the charge gauge, with 0 to 4 bars lit."""
    im = panel(**BATTERY_PANEL)
    fill(im, 4, 3, 11, 12, (30, 32, 36))
    for i in range(4):
        y = 11 - i * 2
        fill(im, 5, y, 10, y, GREEN if i < level else (60, 64, 70))
    return im


def generator(on):
    im = panel(base=(90, 92, 96))
    fill(im, 3, 7, 12, 13, (30, 30, 30))
    if on:
        for x in range(4, 12):
            for y in range(9, 13):
                px(im, x, y, ORANGE if rng.random() < 0.6 else YELLOW)
    for x in range(3, 13, 2):
        fill(im, x, 2, x, 5, STEEL_DARK)
    return im


def lamp(on):
    im = img()
    fill(im, 0, 0, 15, 15, (70, 70, 74))
    inner = (255, 236, 160) if on else (110, 104, 90)
    fill(im, 2, 2, 13, 13, inner, 6)
    for i in range(2, 14, 4):
        fill(im, i, 2, i, 13, (90, 90, 94))
        fill(im, 2, i, 13, i, (90, 90, 94))
    return im


def sensor(on):
    im = panel()
    fill(im, 7, 3, 8, 11, WHITE)
    fill(im, 6, 11, 9, 13, RED if on else (120, 30, 30))
    fill(im, 7, 7 if on else 10, 8, 11, RED if on else (120, 30, 30))
    px(im, 3, 3, RED if on else (60, 20, 20))
    return im


def outlet_front():
    """Water outlet front: a drain spout with a grate, water showing in its lower half."""
    im = panel(**MACHINE_PANEL)
    fill(im, 3, 3, 12, 12, STEEL_DARK)
    fill(im, 4, 4, 11, 11, (22, 24, 28))
    for x in range(4, 12):
        for y in range(8, 12):
            px(im, x, y, noise(BLUE, 12))
    fill(im, 4, 8, 11, 8, (110, 160, 240))
    for x in (6, 9):
        fill(im, x, 4, x, 11, STEEL_LIGHT)
    fill(im, 3, 13, 12, 13, STEEL_LIGHT)
    return im


def condenser_side():
    """Condenser sides: the tube bundle."""
    im = panel(**CONDENSER_PANEL)
    for y in (3, 6, 9, 12):
        fill(im, 2, y, 13, y, (70, 80, 92))
        fill(im, 2, y - 1, 13, y - 1, (160, 172, 186))
    return im


texture(BLOCK, "machine_side", machine_side)
texture(BLOCK, "machine_top", machine_top)
# Pumps: the power-in port (their only electric face).
texture(BLOCK, "machine_power_in", port(MACHINE_PANEL, FLANGE_POWER, socket="cable"))
texture(BLOCK, "water_pump_front", port(MACHINE_PANEL, FLANGE_WATER))
# Circulation pump: blue inlet at the back, green outlet at the front.
texture(BLOCK, "circulation_pump_front", port(MACHINE_PANEL, FLANGE_OUT))
texture(BLOCK, "circulation_pump_back", port(MACHINE_PANEL, FLANGE_WATER))
# Heat exchanger: one job per face (top: hot coolant in, bottom: water in, sides: cold coolant out, front: steam).
texture(BLOCK, "heat_exchanger_front", port(COPPER_PANEL, FLANGE_STEAM))
texture(BLOCK, "heat_exchanger_top", port(COPPER_PANEL, FLANGE_HOT))
texture(BLOCK, "heat_exchanger_bottom", port(COPPER_PANEL, FLANGE_WATER))
texture(BLOCK, "heat_exchanger_side_out", port(COPPER_PANEL, FLANGE_COLD))
# Steam turbine: rotor on the front, steam in at the top, exhaust out at the bottom (to a condenser), power out
# on the sides and back.
texture(BLOCK, "steam_turbine_front", turbine_front)
texture(BLOCK, "steam_turbine_top", port({}, FLANGE_STEAM))
texture(BLOCK, "steam_turbine_bottom", port({}, FLANGE_EXHAUST))
texture(BLOCK, "steam_turbine_power_out", port({}, FLANGE_POWER_OUT, socket="cable"))
# Battery: charge gauge on the front, power in on the back, power out on the other faces.
for level in range(5):
    texture(BLOCK, "battery_side_" + str(level), battery_gauge, level)
texture(BLOCK, "battery_back", port(BATTERY_PANEL, FLANGE_POWER, socket="cable"))
texture(BLOCK, "battery_power_out", port(BATTERY_PANEL, FLANGE_POWER_OUT, socket="cable"))
texture(BLOCK, "backup_generator_front", generator, False)
texture(BLOCK, "backup_generator_front_on", generator, True)
texture(BLOCK, "electric_lamp", lamp, False)
texture(BLOCK, "electric_lamp_on", lamp, True)
texture(BLOCK, "temperature_sensor", sensor, False)
texture(BLOCK, "temperature_sensor_on", sensor, True)
texture(BLOCK, "pipe_wall_port", port({}, FLANGE_PLAIN))
texture(BLOCK, "cable_wall_port", port({}, FLANGE_RUBBER, socket="cable", bolt=(120, 124, 130)))
# Water outlet: water in on every face but the front.
texture(BLOCK, "water_outlet_front", outlet_front)
texture(BLOCK, "water_outlet_in", port(MACHINE_PANEL, FLANGE_WATER))
# Condenser: exhaust steam in at the top (misty grey-blue, like the turbine bottom it sits under), cooling water
# in at the back (blue) and out at the front (purple, warmed), condensate out at the bottom (blue).
texture(BLOCK, "condenser_top", port(CONDENSER_PANEL, FLANGE_EXHAUST))
texture(BLOCK, "condenser_back", port(CONDENSER_PANEL, FLANGE_WATER))
texture(BLOCK, "condenser_front", port(CONDENSER_PANEL, FLANGE_WARM))
texture(BLOCK, "condenser_bottom", port(CONDENSER_PANEL, FLANGE_WATER))
texture(BLOCK, "condenser_side", condenser_side)


# ---------------- enrichment centrifuge ----------------
# A round window onto the drum on the front (still, or a green blur while it spins), a bolted lid on top, vents
# and a hazard stripe on the sides. Power in at the back (machine_power_in).
ENRICH_GREEN = (120, 230, 90)


def centrifuge_front(on):
    im = panel(**CENTRIFUGE_PANEL)
    cx, cy = 7.5, 6.5
    for x in range(16):
        for y in range(16):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d > 5.6:
                continue
            if d > 4.6:
                px(im, x, y, STEEL_DARK)
            elif d < 1.2:
                px(im, x, y, STEEL_LIGHT)
            elif on:
                # Motion blur of the spinning drum.
                px(im, x, y, noise(ENRICH_GREEN, 30) if rng.random() < 0.55 else (36, 70, 40))
            else:
                spoke = abs(x - cx) < 0.6 or abs(y - cy) < 0.6
                px(im, x, y, STEEL if spoke else (28, 34, 40))
    fill(im, 3, 13, 9, 13, (40, 42, 46))
    fill(im, 11, 13, 12, 13, GREEN if on else (40, 64, 42))
    return im


def centrifuge_top():
    im = panel(**CENTRIFUGE_PANEL)
    for x in range(16):
        for y in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if 4.6 < d <= 5.8:
                px(im, x, y, STEEL_DARK)
            elif d <= 4.6:
                px(im, x, y, noise((126, 132, 140), 5))
    for (x, y) in [(7, 2), (8, 13), (2, 8), (13, 7)]:
        px(im, x, y, RIVET)
    fill(im, 5, 7, 10, 8, STEEL_LIGHT)
    fill(im, 5, 8, 10, 8, STEEL_DARK)
    return im


def centrifuge_side():
    im = panel(**CENTRIFUGE_PANEL)
    for y in (3, 5, 7, 9):
        fill(im, 3, y, 12, y, (40, 42, 46))
    for x in range(1, 15):
        for y in (12, 13):
            px(im, x, y, YELLOW if (x + y) % 4 < 2 else BLACK)
    return im


texture(BLOCK, "enrichment_centrifuge_front", centrifuge_front, False)
texture(BLOCK, "enrichment_centrifuge_front_on", centrifuge_front, True)
texture(BLOCK, "enrichment_centrifuge_top", centrifuge_top)
texture(BLOCK, "enrichment_centrifuge_side", centrifuge_side)


# ---------------- steam dump valve ----------------
# Steam in at the top (white flange), dumped steam out at the back (the exhaust's misty grey-blue), a red
# handwheel and a gauge on the front whose needle shows how far it is open (0 shut, 1 partly, 2 fully), the
# valve body with its pipe coming down from the top on the sides, and a plain bottom.
HANDWHEEL = (196, 44, 40)
HANDWHEEL_DARK = (130, 26, 24)


def dump_valve_front(opening):
    im = panel(**VALVE_PANEL)
    # Gauge: white dial in a dark rim, needle from shut (left) through half (up) to open (right).
    gx, gy = 7.5, 3.5
    for x in range(16):
        for y in range(8):
            d = ((x - gx) ** 2 + (y - gy) ** 2) ** 0.5
            if d <= 2.2:
                px(im, x, y, (232, 232, 224))
            elif d <= 3.1:
                px(im, x, y, (40, 42, 46))
    tip = {0: (5, 4), 1: (7, 1), 2: (10, 4)}[opening]
    centre = (7, 3) if opening < 2 else (8, 3)
    px(im, centre[0], centre[1], BLACK)
    mx, my = (centre[0] + tip[0]) // 2, (centre[1] + tip[1]) // 2
    needle = RED if opening else BLACK
    px(im, mx, my, needle)
    px(im, tip[0], tip[1], needle)
    # Handwheel: rim, four spokes and a hub, on a valve stem.
    hx, hy = 7.5, 10.5
    for x in range(16):
        for y in range(6, 16):
            d = ((x - hx) ** 2 + (y - hy) ** 2) ** 0.5
            if 3.4 < d <= 4.4:
                px(im, x, y, HANDWHEEL if y < hy else HANDWHEEL_DARK)
            elif d <= 3.4 and (abs(x - hx) < 0.6 or abs(y - hy) < 0.6):
                px(im, x, y, HANDWHEEL_DARK)
    fill(im, 7, 10, 8, 11, STEEL_LIGHT)
    # Lamp: green while open.
    px(im, 13, 2, GREEN if opening else (40, 64, 42))
    return im


def dump_valve_side():
    im = panel(**VALVE_PANEL)
    fill(im, 6, 0, 9, 5, COPPER)
    for y in range(6):
        px(im, 6, y, COPPER_LIGHT)
        px(im, 9, y, COPPER_DARK)
    fill(im, 4, 5, 11, 10, STEEL_DARK)
    fill(im, 5, 6, 10, 9, (70, 76, 86))
    fill(im, 4, 5, 11, 5, STEEL_LIGHT)
    for (x, y) in [(5, 6), (10, 6), (5, 9), (10, 9)]:
        px(im, x, y, RIVET)
    return im


texture(BLOCK, "steam_dump_valve_top", port(VALVE_PANEL, FLANGE_STEAM))
texture(BLOCK, "steam_dump_valve_back", port(VALVE_PANEL, FLANGE_EXHAUST))
texture(BLOCK, "steam_dump_valve_bottom", panel, VALVE_PANEL["base"], VALVE_PANEL["dark"], VALVE_PANEL["light"])
for opening in range(3):
    texture(BLOCK, "steam_dump_valve_front_" + str(opening), dump_valve_front, opening)
texture(BLOCK, "steam_dump_valve_side", dump_valve_side)


# ---------------- expansion tank ----------------
# A painted blue-grey tank: water in at the front (blue flange), out at the back (green), a sight glass on the
# sides that shows how full it is (0 = empty .. 4 = full), and a bolted lid on top and bottom.
TANK_PANEL = dict(base=(84, 112, 138), dark=(56, 78, 100), light=(124, 152, 178))


def tank_side(level):
    im = panel(**TANK_PANEL)
    # Welded seams across the shell.
    for y in (4, 11):
        fill(im, 1, y, 14, y, TANK_PANEL["dark"])
    # Sight glass: a frame, the glass, and water up to the level.
    fill(im, 6, 1, 9, 14, (40, 44, 50))
    water_top = 13 - round(12 * level / 4)
    for y in range(2, 14):
        for x in (7, 8):
            px(im, x, y, noise(BLUE, 10) if y > water_top else (150, 178, 196))
    if 0 < level < 4:
        px(im, 7, water_top + 1, (130, 180, 245))
        px(im, 8, water_top + 1, (130, 180, 245))
    return im


def tank_lid():
    im = panel(**TANK_PANEL)
    for x in range(16):
        for y in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if 4.6 < d <= 5.6:
                px(im, x, y, TANK_PANEL["dark"])
            elif d <= 4.6:
                px(im, x, y, noise((104, 132, 158), 5))
    for (x, y) in [(7, 2), (8, 13), (2, 8), (13, 7)]:
        px(im, x, y, RIVET)
    return im


texture(BLOCK, "expansion_tank_front", port(TANK_PANEL, FLANGE_WATER))
texture(BLOCK, "expansion_tank_back", port(TANK_PANEL, FLANGE_OUT))
for level in range(5):
    texture(BLOCK, "expansion_tank_side_" + str(level), tank_side, level)
texture(BLOCK, "expansion_tank_top", tank_lid)


# ---------------- items ----------------

def item_rod(color, tip):
    im = img()
    for i in range(3, 13):
        px(im, i, 15 - i, STEEL_DARK)
        px(im, i + 1, 15 - i, color)
        px(im, i + 1, 14 - i, color)
        px(im, i + 2, 14 - i, STEEL_DARK)
    px(im, 2, 13, STEEL_LIGHT)
    px(im, 14, 1, tip)
    px(im, 13, 2, tip)
    return im


def item_rod_enriched(color):
    """An enriched rod: brighter fuel held by gold bands (like the gold tie plates of its fuel assembly)."""
    im = item_rod(color, GOLD_LIGHT)
    for i in (4, 5, 8, 9, 12):
        px(im, i, 15 - i, GOLD)
        px(im, i + 2, 14 - i, GOLD)
    px(im, 2, 13, GOLD)
    return im


def pipe_wrench():
    im = img()
    for i in range(4, 13):
        px(im, i, 15 - i, STEEL_DARK)
        px(im, i + 1, 15 - i, STEEL)
    for (x, y) in [(11, 1), (12, 1), (13, 1), (14, 2), (14, 3), (14, 4), (12, 3), (11, 4), (10, 2)]:
        px(im, x, y, STEEL_LIGHT)
    fill(im, 2, 12, 4, 14, RED)
    return im


def pliers():
    im = img()
    for i in range(3, 9):
        px(im, i, 15 - i, RED)
        px(im, 15 - i, 15 - i, RED)
    for i in range(9, 14):
        px(im, 7, 15 - i, STEEL_LIGHT)
        px(im, 8, 15 - i, STEEL)
    px(im, 7, 7, STEEL_DARK)
    px(im, 8, 7, STEEL_DARK)
    return im


def thermal_probe():
    im = img()
    fill(im, 4, 6, 11, 14, (50, 54, 60))
    fill(im, 5, 7, 10, 10, (20, 60, 40))
    px(im, 6, 8, GREEN)
    px(im, 8, 9, GREEN)
    fill(im, 7, 1, 8, 5, STEEL_LIGHT)
    px(im, 7, 1, RED)
    return im


def copper_wire():
    im = img()
    for x in range(16):
        for y in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if 3.0 <= d <= 6.5 and (int(d * 2) % 2 == 0):
                px(im, x, y, COPPER if d < 5 else COPPER_LIGHT)
            elif 3.0 <= d <= 6.5:
                px(im, x, y, COPPER_DARK)
    return im


def fluid_pipe():
    im = img()
    fill(im, 1, 6, 14, 9, COPPER)
    fill(im, 1, 6, 14, 6, COPPER_LIGHT)
    fill(im, 1, 9, 14, 9, COPPER_DARK)
    fill(im, 0, 5, 1, 10, COPPER_DARK)
    fill(im, 14, 5, 15, 10, COPPER_DARK)
    return im


def power_cable():
    im = img()
    for x in range(16):
        for y in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if 3.0 <= d <= 6.5:
                px(im, x, y, (40, 40, 44) if int(d * 2) % 2 == 0 else (20, 20, 22))
    fill(im, 7, 7, 8, 8, COPPER)
    return im


def power_pole():
    im = img()
    fill(im, 7, 3, 8, 15, (110, 80, 50))
    fill(im, 2, 3, 13, 4, (90, 64, 40))
    for x in (2, 7, 13):
        px(im, x, 2, WHITE)
    return im


def check_valve():
    im = img()
    fill(im, 0, 6, 15, 9, COPPER)
    fill(im, 0, 6, 15, 6, COPPER_LIGHT)
    fill(im, 0, 9, 15, 9, COPPER_DARK)
    fill(im, 4, 4, 10, 11, STEEL)
    fill(im, 4, 4, 10, 4, STEEL_LIGHT)
    fill(im, 4, 11, 10, 11, STEEL_DARK)
    fill(im, 11, 5, 11, 10, RED)
    fill(im, 7, 1, 7, 3, STEEL_DARK)
    fill(im, 5, 1, 9, 1, RED)
    # Arrow pointing to the outlet (red ring).
    fill(im, 5, 7, 7, 8, WHITE)
    fill(im, 8, 5, 8, 10, WHITE)
    fill(im, 9, 6, 9, 9, WHITE)
    fill(im, 10, 7, 10, 8, WHITE)
    return im


def geiger_counter():
    im = img()
    fill(im, 2, 6, 11, 14, YELLOW)
    fill(im, 2, 14, 11, 14, (176, 140, 20))
    fill(im, 11, 6, 11, 14, (176, 140, 20))
    fill(im, 4, 3, 9, 4, (50, 54, 60))
    fill(im, 4, 5, 4, 5, (50, 54, 60))
    fill(im, 9, 5, 9, 5, (50, 54, 60))
    fill(im, 3, 7, 8, 10, (232, 232, 214))
    for x, y in ((4, 10), (5, 9), (6, 8)):
        px(im, x, y, RED)
    for x in (4, 6, 8):
        px(im, x, 12, BLACK)
    fill(im, 13, 2, 14, 11, STEEL_LIGHT)
    fill(im, 13, 11, 14, 11, STEEL_DARK)
    px(im, 12, 12, BLACK)
    px(im, 12, 13, BLACK)
    return im


# Fuel cycle: fresh rods, their depleted kinds (the fuel colour gone dull), enriched rods, spent enriched fuel.
texture(ITEM, "redstone_fuel_rod", item_rod, (210, 40, 40), RIVET)
texture(ITEM, "depleted_redstone_fuel_rod", item_rod, (120, 46, 40), STEEL_DARK)
texture(ITEM, "enriched_redstone_fuel_rod", item_rod_enriched, (255, 64, 52))
texture(ITEM, "glowstone_fuel_rod", item_rod, (250, 220, 90), (255, 255, 200))
texture(ITEM, "depleted_glowstone_fuel_rod", item_rod, (150, 136, 82), STEEL_DARK)
texture(ITEM, "enriched_glowstone_fuel_rod", item_rod_enriched, (255, 246, 176))
texture(ITEM, "depleted_fuel_rod", item_rod, (90, 90, 80), STEEL_DARK)
texture(ITEM, "control_rod", item_rod, (30, 30, 30), STEEL_LIGHT)
texture(ITEM, "pipe_wrench", pipe_wrench)
texture(ITEM, "pliers", pliers)
texture(ITEM, "thermal_probe", thermal_probe)
texture(ITEM, "copper_wire", copper_wire)
texture(ITEM, "fluid_pipe", fluid_pipe)
texture(ITEM, "power_cable", power_cable)
texture(ITEM, "power_pole", power_pole)
texture(ITEM, "check_valve", check_valve)
texture(ITEM, "geiger_counter", geiger_counter)


# ---------------- icon ----------------

def icon():
    """The mod icon: a black trefoil on a yellow disc."""
    im = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    for x in range(128):
        for y in range(128):
            d = ((x - 63.5) ** 2 + (y - 63.5) ** 2) ** 0.5
            if d <= 60:
                im.putpixel((x, y), (236, 196, 40, 255))
            if d <= 10:
                im.putpixel((x, y), (20, 20, 20, 255))
            elif 14 <= d <= 54:
                angle = (math.degrees(math.atan2(y - 63.5, x - 63.5)) + 360 + 90) % 360
                if (angle % 120) < 60:
                    im.putpixel((x, y), (20, 20, 20, 255))
    return im


icon().save(os.path.join(ROOT, "icon.png"))
print("textures written")

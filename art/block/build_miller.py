"""Miller & Sons, hardware and auto repair: the shop on the street, the
storeroom behind it, the workshop with its pit, the loft office over the
storeroom, the scrapyard and the service court.

  blender --background --factory-startup --python art/block/build_miller.py

Writes art/block/miller.blend and fps/assets/block/miller.glb. Positions are
the plan sketch's (art/block/draw_plan.py): metres, x east, z south, y up.

  What it was     a family hardware store since 1954 with a two-bay garage behind;
                  the old man keeps the books in the loft, the son works the pit
  Half an hour    the son was under a car in the pit when the lights surged: the drop
  ago             lamp is still burning down there, his tools where he let go of them;
                  on the top shelves of the shop the power tools have left their boxes;
                  upstairs the telephone hangs off its hook
  The player      comes in from the street; the storeroom is locked (the spare key is
                  at the Anchor); with the key: storeroom, workshop, down into the pit
                  for the belt and the jack (the red toolbox is not a toolbox), then
                  out by the vehicle door into the service court and through its gate:
                  two shortcuts, opened from inside

The model brings its own collision ("-nocol" only seen, "-colonly" only bumped into).
"""
import math
import os
import random
import sys

import bpy

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "town", "blender"))
import lotkit  # noqa: E402
from lotkit import QUARTERS, Part, decal_set, flat, light, texture_set, turn  # noqa: E402

random.seed(1954)
lotkit.reset_scene()

for name, metres in {
		"Asphalt015": 3.0, "Concrete033": 3.0, "Concrete040": 2.5, "red_brick": 2.0, "WoodFloor051": 2.0, "D16_TinCeiling": 2.4,
		"PaintedMetal012": 2.0, "PaintedMetal006": 2.0, "PaintedMetal016": 0.8, "PaintedMetal007": 2.0, "Metal053B": 1.5,
		"CorrugatedSteel003": 2.0, "bitumen": 3.0, "oak_veneer_01": 1.5, "Plastic010": 1.0, "Plastic012B": 1.0, "Rubber004": 1.0,
		"worn_planks": 1.6, "ChainLink": 0.25, "PaintedPlaster006": 4.0, "D10_LatexDrywall": 2.8, "grass_path_3": 3.0,
		"G45_ToolOutlineWall": 1.5, "C48_StripedCanvasAwning": 1.6}.items():
	texture_set(name, metres)

flat("glass", (0.55, 0.70, 0.74), rough=0.05, alpha=0.16)
flat("glass_dark", (0.02, 0.03, 0.04), rough=0.08, alpha=0.82)
flat("lamp_cool", (0.9, 0.95, 1.0), emit=(0.85, 0.93, 1.0), strength=2.4)
flat("lamp_warm", (1.0, 0.8, 0.5), emit=(1.0, 0.72, 0.4), strength=2.2)
flat("lamp_off", (0.42, 0.43, 0.40), rough=0.5)
flat("chrome", (0.75, 0.75, 0.76), rough=0.22, metal=1.0)
flat("black", (0.02, 0.02, 0.022), rough=0.6)
flat("oil", (0.006, 0.006, 0.008), rough=0.06)
flat("tyre", (0.015, 0.015, 0.016), rough=0.92)
flat("car_grey", (0.2, 0.22, 0.24), rough=0.35, metal=0.3)
flat("car_rust", (0.22, 0.11, 0.06), rough=0.8)
flat("tool_red", (0.5, 0.05, 0.04), rough=0.35, metal=0.3)
flat("paper", (0.78, 0.76, 0.68), rough=0.9)
flat("cardboard", (0.42, 0.31, 0.19), rough=0.9)
flat("sign_green", (0.05, 0.14, 0.08), rough=0.7)
flat("display", (0.02, 0.035, 0.03), rough=0.12)
flat("paint_yellow", (0.72, 0.52, 0.05), rough=0.55)
flat("night", (0.006, 0.008, 0.012), rough=1.0)
flat("bark", (0.05, 0.045, 0.04), rough=1.0)
flat("trash_bag", (0.02, 0.02, 0.022), rough=0.32)

decal_set("M27_EngineOilStains", "A39_OilDripSpots", "A36_PuddleMudRim", "L16ba_BlackOilDrips", "L16bb_BlackOilDrips", "L16bc_BlackOilDrips",
		"L16bd_BlackOilDrips", "M07a_RustStreaks", "M07b_RustStreaks", "M07c_RustStreaks", "M13a_WallCracks", "M13d_WallCracks",
		"M05a_CeilingMould", "Leaking002", "Leaking005", "Leaking007", "G02_GoldLeafDoorLettering", "C05_FadedPaintedWallAd", "M31_LitterAtlas")

BRICK = "red_brick"
RENDER = "PaintedPlaster006"
WHITE = "PaintedMetal012"
GREEN = "PaintedMetal006"
STEEL = "Metal053B"
DARK = "Plastic012B"
RUSTY = "PaintedMetal016"
BLUE = "PaintedMetal007"
UP = (0, 1, 0)

F = 0.15                 # floors and pavements
SHOP_CEIL = F + 4.05
SHOP_ROOF = 4.4          # underside of the shop's roof slab
SHOP_TOP = 5.5           # its parapet
LOFT = 3.45              # the loft floor
BACK_TOP = 6.4           # underside of the roof over the loft
HALL = 6.6               # underside of the workshop roof
PIT = F - 1.5

# The printed sheets, cell by cell (tools/sheet_cells.py finds these).
G12 = [(0.062, 0.066, 0.332, 0.496), (0.363, 0.066, 0.641, 0.496), (0.672, 0.070, 0.934, 0.496), (0.055, 0.512, 0.344, 0.930),
		(0.375, 0.512, 0.633, 0.930), (0.668, 0.512, 0.938, 0.930)]
G30 = [(0.098, 0.184, 0.480, 0.391), (0.520, 0.184, 0.902, 0.398), (0.098, 0.402, 0.484, 0.602), (0.516, 0.406, 0.902, 0.602),
		(0.098, 0.609, 0.477, 0.812), (0.516, 0.613, 0.898, 0.812)]
G53 = [(0.434, 0.289, 0.598, 0.500), (0.598, 0.285, 0.762, 0.504), (0.770, 0.289, 0.973, 0.504), (0.598, 0.504, 0.777, 0.723), (0.781, 0.508, 0.973, 0.723)]
E31 = [(0.023, 0.137, 0.219, 0.496), (0.262, 0.141, 0.461, 0.496), (0.492, 0.137, 0.734, 0.496), (0.766, 0.219, 0.980, 0.496),
		(0.055, 0.512, 0.188, 0.863), (0.266, 0.516, 0.395, 0.867), (0.449, 0.512, 0.680, 0.855), (0.738, 0.523, 0.953, 0.863)]
E35 = [(0.039, 0.188, 0.258, 0.492), (0.277, 0.191, 0.496, 0.492), (0.508, 0.191, 0.727, 0.492), (0.738, 0.191, 0.965, 0.492),
		(0.039, 0.504, 0.254, 0.812), (0.273, 0.504, 0.492, 0.812), (0.508, 0.504, 0.727, 0.812), (0.746, 0.504, 0.961, 0.812)]
G20_BAGS = [(0.141, 0.176, 0.473, 0.566), (0.516, 0.172, 0.859, 0.570)]
G20_PACKETS = [(0.012, 0.582, 0.172, 0.820), (0.180, 0.582, 0.336, 0.820), (0.340, 0.582, 0.496, 0.820), (0.504, 0.582, 0.660, 0.820),
		(0.672, 0.582, 0.824, 0.820), (0.832, 0.582, 0.984, 0.820)]
G59_POSTER = (0.027, 0.223, 0.445, 0.789)
G59_PRICES = (0.473, 0.324, 0.973, 0.695)
G11_WALL = (0.051, 0.203, 0.949, 0.797)
G65_DESK = (0.031, 0.207, 0.977, 0.805)
G28_CARD = (0.016, 0.215, 0.594, 0.793)
G02_ALL = (0.055, 0.184, 0.953, 0.82)
C05_ALL = (0.027, 0.328, 0.973, 0.688)
CALENDAR_MILLER = (0.52, 0.50, 0.90, 0.97)
NOTE_KEY = (0.61, 0.52, 0.795, 0.72)                 # "Storeroom key is at the Anchor. Ask Lou."
SIGN_EYES = (0.53, 0.51, 0.73, 0.73)
SIGN_KEEP_OUT = (0.74, 0.51, 0.98, 0.73)
SIGN_EXTINGUISHER = (0.53, 0.27, 0.73, 0.5)


def shrink(r, by=0.04):
	w, h = r[2] - r[0], r[3] - r[1]
	return (r[0] + w * by, r[1] + h * by, r[2] - w * by, r[3] - h * by)


CARDS = [("E35_BatteriesAndHardwarePegs", shrink(r), 0.17, 0.03) for r in E35]
TOOLS = [("G12_HandToolPegPacks", shrink(r), 0.3, 0.05) for r in G12]
MOTOR = [("E31_CarAccessories", shrink(r), 0.16, 0.11) for r in E31]
PARTS = [("G53_PartsShelfCardboardBoxes", shrink(r), 0.28, 0.24) for r in G53]
SEEDS = [("G20_FertiliserAndSeedBags", shrink(r), 0.1, 0.01) for r in G20_PACKETS]


class Shifted:
	"""A part seen from a local frame: every shape call gets the matrix."""

	def __init__(self, part, m):
		self.part = part
		self.m = m

	def __getattr__(self, name):
		call = getattr(self.part, name)
		return lambda *a, **k: call(*a, m=self.m, **k)


def place(x, z, yaw=0.0, y=0.0):
	return lotkit.Matrix.Translation((x, y, z)) @ lotkit.Matrix.Rotation(math.radians(yaw), 4, "Y")


def marker(name, at):
	obj = bpy.data.objects.new("marker_" + name, None)
	obj.empty_display_type = "PLAIN_AXES"
	obj.location = (at[0], -at[2], at[1])
	bpy.context.scene.collection.objects.link(obj)


def wall(part, mat, along, lo, hi, a, b, y0, y1, openings=()):
	"""A wall with holes: `along` "x" runs east-west and is lo..hi thick in z, "z" the other way.
	Each opening is (from, to, sill, head), heights measured from y0."""
	def piece(p, q, ya, yb):
		if q - p < 0.01 or yb - ya < 0.01:
			return
		if along == "x":
			part.box(mat, (p, ya, lo), (q, yb, hi))
		else:
			part.box(mat, (lo, ya, p), (hi, yb, q))
	at = a
	for p, q, sill, head in sorted(openings):
		piece(at, p, y0, y1)
		piece(p, q, y0, y0 + sill)
		piece(p, q, y0 + head, y1)
		at = q
	piece(at, b, y0, y1)


def stock(part, m, x0, x1, y, z_front, items, gaps=0.12, max_h=0.42):
	"""A row of goods along local x on a shelf, faces towards local +z."""
	x = x0 + 0.03
	while x < x1 - 0.1:
		image, rect, w, d = random.choice(items)
		h = min(w * (rect[3] - rect[1]) / (rect[2] - rect[0]), max_h)
		for _ in range(random.randint(2, 4)):
			if x + w > x1 - 0.02:
				break
			if random.random() > gaps:
				back = random.uniform(0.0, 0.04)
				part.image_box(image, (x, y, z_front - d - back), (x + w, y + h, z_front - back), "+z", rect, m=m)
			x += w + 0.015
		x += random.uniform(0.0, 0.08)


ground = Part("miller_ground")
shell = Part("miller_shell")
trim = Part("miller_trim-nocol")
glass = Part("miller_glass")
fit = Part("miller_fittings")
goods = Part("miller_goods-nocol")
yard = Part("miller_yard")
dirt = Part("miller_dirt-nocol")
col = Part("miller_props-colonly")


def solid(a, b, m=None):
	col.box("black", a, b, m=m)


# =============================================================================
# Ground: the strip in front, the yard, the service court
# =============================================================================
ground.box("Concrete040", (56.0, 0.0, 9.0), (57.0, F, 34.0), skip="-y")                    # the shop's own strip of pavement
ground.box("grass_path_3", (56.0, -0.4, 6.0), (78.0, 0.012, 10.0), skip="-y")                 # weeds along the north side
ground.box("grass_path_3", (71.0, -0.4, 10.0), (78.0, 0.012, 33.0), skip="-y")                # the scrapyard
ground.box("grass_path_3", (65.5, -0.4, 27.0), (71.0, 0.012, 33.0), skip="-y")
ground.box("Concrete033", (56.0, -0.4, 49.0), (78.0, 0.012, 63.0), skip="-y")                 # the service court
ground.box("Concrete033", (56.0, -0.4, 34.0), (57.0, 0.012, 49.0), skip="-y")
ground.box("Concrete040", (64.2, 0.0, 49.0), (70.2, 0.04, 51.5), skip="-y")                 # apron at the vehicle door

# =============================================================================
# The shop: brick, a stepped parapet, big windows, an awning over the pavement
# =============================================================================
wall(shell, BRICK, "z", 57.0, 57.35, 10.0, 27.0, F, SHOP_TOP, [(11.5, 16.4, 0.6, 3.0), (17.6, 19.4, 0.0, 2.45), (20.6, 25.5, 0.6, 3.0)])
wall(shell, BRICK, "z", 70.65, 71.0, 10.0, 27.0, F, SHOP_TOP)
wall(shell, BRICK, "x", 10.0, 10.35, 57.35, 70.65, F, SHOP_TOP)
wall(shell, BRICK, "x", 26.65, 27.0, 57.35, 70.65, F, SHOP_ROOF + 0.25, [(60.9, 62.1, 0.0, 2.25)])
shell.box(BRICK, (65.5, SHOP_ROOF + 0.25, 26.65), (71.0, SHOP_TOP, 27.0))
shell.box("WoodFloor051", (57.0, -0.4, 10.0), (71.0, F, 27.0), skip="-y")
shell.box("D16_TinCeiling", (57.35, SHOP_CEIL, 10.35), (70.65, SHOP_CEIL + 0.05, 26.65), skip="+y")
shell.box("bitumen", (57.0, SHOP_ROOF, 10.0), (71.0, SHOP_ROOF + 0.25, 27.0))
shell.box(BRICK, (56.9, SHOP_TOP, 14.5), (57.45, SHOP_TOP + 0.8, 22.5))                       # the raised middle of the front parapet
for a, b in (((56.9, SHOP_TOP, 9.9), (57.45, SHOP_TOP + 0.1, 14.5)), ((56.9, SHOP_TOP, 22.5), (57.45, SHOP_TOP + 0.1, 27.1)),
		((56.85, SHOP_TOP + 0.8, 14.4), (57.5, SHOP_TOP + 0.92, 22.6)), ((56.9, SHOP_TOP, 9.9), (71.1, SHOP_TOP + 0.1, 10.4)),
		((70.6, SHOP_TOP, 9.9), (71.1, SHOP_TOP + 0.1, 27.0))):
	trim.box("Concrete033", a, b, bevel=0.02)
for z in (10.0, 16.75, 19.75, 26.5):                                                         # brick piers on the front
	shell.box(BRICK, (56.86, F, z), (57.0, SHOP_TOP, z + 0.5))
trim.box("Concrete033", (56.84, F + 3.0, 10.0), (57.0, F + 3.22, 27.0))                        # stone band over the windows
trim.box("Concrete033", (56.9, 0.0, 10.0), (57.0, F + 0.6, 17.6))
trim.box("Concrete033", (56.9, 0.0, 19.4), (57.0, F + 0.6, 27.0))
# the sign board and its two lamps
trim.box("sign_green", (56.82, 3.75, 13.0), (56.9, 5.25, 24.0), bevel=0.02)
sw = 1.3 * (C05_ALL[2] - C05_ALL[0]) / (C05_ALL[3] - C05_ALL[1])
trim.decal("C05_FadedPaintedWallAd", (56.82, 4.5, 18.5), (-1, 0, 0), sw, 1.3, rect=C05_ALL, lift=0.004)
for z in (15.2, 21.8):
	trim.tube(STEEL, [(56.86, 5.4, z), (56.4, 5.75, z), (56.2, 5.5, z)], 0.02, seg=5, steps=3)
	trim.cyl(GREEN, (56.2, 5.5, z), (56.15, 5.32, z), 0.05, seg=8, r2=0.16)
light("sign_warm", (55.9, 4.9, 15.2))
light("sign_warm", (55.9, 4.9, 21.8))
# the awning: striped canvas on a pipe frame, sagging at one end
trim.prism("C48_StripedCanvasAwning", [(57.0, 3.3), (57.0, 3.36), (55.1, 2.75), (55.1, 2.69)], "z", 10.6, 26.4)
trim.box("C48_StripedCanvasAwning", (55.08, 2.45, 10.6), (55.12, 2.72, 26.4))
for z in (10.7, 18.5, 26.3):
	trim.tube(STEEL, [(57.0, 2.5, z), (55.15, 2.68, z)], 0.02, seg=5, steps=1)
# the ghost sign on the north wall, for whoever comes down the street
gw = 3.0 * (C05_ALL[2] - C05_ALL[0]) / (C05_ALL[3] - C05_ALL[1])
dirt.decal("C05_FadedPaintedWallAd", (62.2, 3.3, 10.0), (0, 0, -1), gw, 3.0, rect=C05_ALL, lift=0.005)
# windows, the door standing open, gold lettering on its glass
for z0, z1 in ((11.5, 16.4), (20.6, 25.5)):
	glass.box("glass", (57.16, F + 0.6, z0), (57.19, F + 3.0, z1))
	for i in range(4):
		z = z0 + (z1 - z0) * i / 3
		trim.box("worn_planks", (57.08, F + 0.6, z - 0.04), (57.27, F + 3.0, z + 0.04))
	trim.box("worn_planks", (57.08, F + 2.3, z0), (57.27, F + 2.38, z1))
	trim.box("worn_planks", (57.02, F + 0.52, z0 - 0.05), (57.4, F + 0.6, z1 + 0.05))
	shell.box("worn_planks", (57.35, F, z0), (58.0, F + 0.6, z1))                              # the display step inside the window
for z, a in ((17.6, 100), (19.4, -82)):
	trim.box("worn_planks", (57.02, F, z - 0.05), (57.38, F + 2.5, z + 0.05))
	s = 1 if z < 18.5 else -1
	m = turn("y", a, (57.17, 0, z))
	fit.box("worn_planks", (57.15, F + 0.02, min(z, z + s * 0.88)), (57.2, F + 2.4, max(z, z + s * 0.88)), m=m)
	if s > 0:
		gw2 = 0.62
		trim.decal("G02_GoldLeafDoorLettering", (57.145, F + 1.5, z + 0.44), (-1, 0, 0), gw2, gw2 * 0.71, rect=G02_ALL, lift=0.004, m=m)
trim.box("worn_planks", (57.02, F + 2.45, 17.55), (57.38, F + 2.55, 19.45))
glass.box("glass", (57.16, F + 2.55, 17.6), (57.19, F + 3.0, 19.4))
light("door", (56.4, 2.5, 18.5))


def rack(m, length, levels, face_a, face_b, gaps, top_boxes=True):
	"""Steel shelving along local x, 0.7 deep about local z = 0, 3 m tall, stocked on both faces."""
	s = Shifted(fit, m)
	n = max(2, round(length / 1.6) + 1)
	for i in range(n):
		x = (length - 0.05) * i / (n - 1)
		for z in (-0.33, 0.29):
			s.box(STEEL, (x, F, z), (x + 0.05, F + 3.0, z + 0.04))
	s.box(STEEL, (0.0, F + 0.1, -0.02), (length, F + 3.0, 0.02))                               # the back panel between the two faces
	for face, items in ((1, face_a), (-1, face_b)):
		mm = m @ lotkit.Matrix.Rotation(math.radians(0 if face > 0 else 180), 4, "Y") @ lotkit.Matrix.Translation((0 if face > 0 else -length, 0, 0))
		for i in range(levels):
			y = F + 0.12 + i * 0.5
			fit.box(WHITE, (0.0, y - 0.03, 0.02), (length, y, 0.35), m=mm)
			stock(goods, mm, 0.05, length - 0.05, y, 0.34, items[i % len(items)], gaps=gaps + 0.03 * i)
	if top_boxes:
		x = 0.2
		while x < length - 0.6:                                                               # what is kept on top: the power tools
			w = random.uniform(0.4, 0.6)
			if random.random() > 0.35:
				goods.box("cardboard", (x, F + 3.0, -0.25), (x + w, F + 3.0 + random.uniform(0.25, 0.4), 0.25), bevel=0.005,
						m=m @ turn("y", random.uniform(-8, 8), (x + w / 2, 0, 0)))
			x += w + random.uniform(0.1, 0.5)


# Six runs across the shop, three either side of the middle aisle: tall, and only 1.9 m apart.
for x in (59.9, 62.6, 65.3):
	rack(place(x + 0.35, 16.8, 90), 6.0, 6, [CARDS, TOOLS, PARTS, MOTOR, CARDS, PARTS], [TOOLS, CARDS, MOTOR, PARTS, TOOLS, CARDS], 0.08)
	rack(place(x + 0.35, 26.2, 90), 6.0, 6, [MOTOR, PARTS, CARDS, TOOLS, PARTS, MOTOR], [PARTS, TOOLS, CARDS, MOTOR, CARDS, TOOLS], 0.14)
# what came down from the tops: opened cartons in the aisle, a drill still in none of them
for x, z, yaw, tip in ((63.6, 18.0, 20, 0), (64.3, 19.2, 110, 90), (61.8, 17.6, -30, 0), (66.2, 18.9, 60, 90), (62.9, 19.6, 5, 0)):
	goods.box("cardboard", (x, F, z), (x + 0.5, F + 0.32, z + 0.4), bevel=0.005, m=turn("y", yaw, (x, 0, z)) @ turn("x", tip, (x, F, z)), skip="+y")
fit.box("worn_planks", (60.9, F, 20.5), (61.0, F + 0.06, 23.1), m=turn("y", 14, (60.9, 0, 20.5)))      # a ladder, down
fit.box("worn_planks", (61.3, F, 20.5), (61.4, F + 0.06, 23.1), m=turn("y", 14, (60.9, 0, 20.5)))
# the counter at the far end, the wall of tool outlines behind it
fit.box("oak_veneer_01", (67.6, F, 14.5), (68.4, F + 1.0, 22.5))
fit.box("worn_planks", (67.52, F + 1.0, 14.42), (68.48, F + 1.06, 22.58), bevel=0.01)
fit.box("oak_veneer_01", (68.4, F, 14.5), (70.0, F + 1.0, 15.2))
goods.box(DARK, (67.75, F + 1.06, 18.2), (68.25, F + 1.4, 18.8), bevel=0.02)                    # the till
goods.box("display", (67.72, F + 1.4, 18.3), (67.78, F + 1.58, 18.7))
goods.box(BLUE, (67.7, F + 1.06, 20.4), (68.3, F + 1.36, 21.1), bevel=0.02)                     # the key cutter
goods.cyl(STEEL, (68.0, F + 1.36, 20.6), (68.0, F + 1.5, 20.6), 0.07, seg=10)
goods.quad("G28_FlashlightBatteryDisplay", (67.9, F + 1.42, 16.0), (-1, 0, 0), 0.7, 0.7, G28_CARD)
goods.box("cardboard", (67.86, F + 1.06, 15.65), (67.9, F + 1.78, 16.35))
trim.box("G45_ToolOutlineWall", (70.6, F + 1.0, 12.0), (70.65, F + 3.3, 25.0))
for i, z in enumerate((12.6, 13.5, 14.4, 16.2, 17.1, 19.0, 21.4, 22.3, 24.1)):
	r = G12[i % len(G12)]
	goods.quad("G12_HandToolPegPacks", (70.595, F + 2.6 - (i % 3) * 0.5, z), (-1, 0, 0), 0.34, 0.34 * (r[3] - r[1]) / (r[2] - r[0]), r)
goods.quad("N16_Calendars", (70.59, F + 2.0, 18.3), (-1, 0, 0), 0.4, 0.5, CALENDAR_MILLER)
goods.quad("G11_PaintCanWallColourChips", (67.0, F + 1.9, 10.36), (0, 0, 1), 4.4, 4.4 * 0.66, G11_WALL)      # paints, on the north wall
for i in range(9):
	x = 65.2 + (i % 5) * 0.42 + (0.2 if i > 4 else 0)
	goods.cyl(random.choice((WHITE, GREEN, BLUE)), (x, F + (0.26 if i > 4 else 0), 10.75), (x, F + (0.52 if i > 4 else 0.26), 10.75), 0.19, seg=10)
for i, (r, z) in enumerate(zip(G20_BAGS + G20_BAGS, (11.2, 11.2, 12.0, 12.0))):                # feed and seed by the window
	goods.image_box("G20_FertiliserAndSeedBags", (58.2, F + (i % 2) * 0.16, z), (58.9, F + 0.16 + (i % 2) * 0.16, z + 0.7), "+y", r)
fit.box("worn_planks", (58.15, F, 13.0), (58.25, F + 1.5, 14.6))                               # the seed stand
for i in range(4):
	for k, z in enumerate((13.1, 13.5, 13.9, 14.3)):
		r = G20_PACKETS[(i + k) % 6]
		goods.quad("G20_FertiliserAndSeedBags", (58.26, F + 0.35 + i * 0.3, z + 0.1), (1, 0, 0), 0.14, 0.2, r)
for i, (x, z) in enumerate(((60.0, 18.5), (62.5, 18.3), (65.0, 18.7), (58.6, 18.4))):           # hand-lettered cards on strings over the aisle
	r = G30[i]
	for n in ((1, 0, 0), (-1, 0, 0)):
		goods.quad("G30_HandwrittenPromoSigns", (x + n[0] * 0.003, F + 3.3, z), n, 0.9, 0.9 * (r[3] - r[1]) / (r[2] - r[0]), r)
	for dz in (-0.35, 0.35):
		goods.cyl("black", (x, F + 3.5, z + dz), (x, SHOP_CEIL, z + dz), 0.003, seg=3)
goods.quad("N23_HandwrittenNotes", (61.5, F + 1.5, 26.64), (0, 0, -1), 0.24, 0.26, NOTE_KEY, m=turn("z", 3, (61.5, F + 1.5, 26.64)))   # on the storeroom door frame
goods.quad("N12_SafetySigns", (62.6, F + 2.0, 26.64), (0, 0, -1), 0.34, 0.3, SIGN_KEEP_OUT)
# light: hanging strip lamps, two dead, the far end of the shop left to the dark
for x, z, on in ((60.0, 13.5, True), (64.5, 13.5, False), (68.8, 13.2, True), (59.6, 18.5, True), (64.5, 18.5, True), (60.0, 23.5, False), (65.0, 23.5, True)):
	trim.box(WHITE, (x - 0.6, SHOP_CEIL - 0.5, z - 0.08), (x + 0.6, SHOP_CEIL - 0.42, z + 0.08))
	trim.box("lamp_cool" if on else "lamp_off", (x - 0.58, SHOP_CEIL - 0.53, z - 0.05), (x + 0.58, SHOP_CEIL - 0.5, z + 0.05))
	for dx in (-0.5, 0.5):
		trim.cyl("black", (x + dx, SHOP_CEIL - 0.42, z), (x + dx, SHOP_CEIL, z), 0.004, seg=3)
	if on:
		light("tube", (x, SHOP_CEIL - 0.8, z))
marker("miller_door", (56.4, F, 18.5))
marker("miller_shop", (64.0, F, 18.5))
marker("shelf_ambush", (63.0, F + 3.2, 14.0))

# =============================================================================
# The storeroom, and the loft office over it
# =============================================================================
wall(shell, BRICK, "z", 57.0, 57.35, 27.0, 33.35, F, BACK_TOP + 0.6)
wall(shell, BRICK, "z", 65.15, 65.5, 27.0, 33.0, F, BACK_TOP + 0.6)
wall(shell, BRICK, "x", 26.65, 27.0, 57.0, 65.5, SHOP_ROOF + 0.25, BACK_TOP + 0.6, [(60.0, 62.0, 0.0, 1.7)])   # over the shop roof: the loft window, sash gone
wall(shell, RENDER, "x", 33.0, 33.35, 57.35, 65.5, F, LOFT - 0.2, [(62.6, 63.8, 0.0, 2.25)])
shell.box("Concrete033", (57.0, -0.4, 27.0), (65.5, F, 33.35), skip="-y")
shell.box("worn_planks", (57.35, LOFT - 0.2, 27.0), (65.8, LOFT, 34.9))                         # the loft floor, with its gallery over the workshop
shell.box("bitumen", (57.0, BACK_TOP, 26.65), (65.5, BACK_TOP + 0.25, 33.35))
for x in (58.0, 65.4):
	shell.box(STEEL, (x, F, 34.75), (x + 0.12, LOFT - 0.2, 34.87))                              # posts under the gallery
trim.box("worn_planks", (59.94, SHOP_ROOF + 0.19, 26.6), (62.06, SHOP_ROOF + 0.25, 27.05))        # the sill you climb out over
for x0, x1, levels in ((57.4, 60.4, 5), (62.6, 65.1, 5)):                                        # stock along the back wall
	for x in (x0, (x0 + x1) / 2, x1 - 0.05):
		fit.box(STEEL, (x, F, 27.05), (x + 0.05, F + 2.6, 27.6))
	for i in range(levels):
		y = F + 0.12 + i * 0.52
		fit.box(STEEL, (x0, y - 0.03, 27.05), (x1, y, 27.6))
		stock(goods, place(x0, 27.05, 0), 0.05, x1 - x0 - 0.05, y, 0.52, PARTS if i % 2 else MOTOR, gaps=0.2)
for i, (x, z, w) in enumerate(((57.6, 31.4, 0.8), (57.6, 30.4, 0.7), (58.5, 31.6, 0.6), (64.2, 29.6, 0.7), (64.1, 30.6, 0.8))):
	fit.box("cardboard", (x, F, z), (x + w, F + 0.6, z + 0.7), bevel=0.006)
	if i % 2 == 0:
		goods.box("cardboard", (x + 0.05, F + 0.6, z + 0.05), (x + w - 0.1, F + 1.1, z + 0.6), bevel=0.006)
light("bulb", (61.2, LOFT - 0.75, 30.2))
trim.cyl("lamp_warm", (61.2, LOFT - 0.5, 30.2), (61.2, LOFT - 0.36, 30.2), 0.1, seg=10)
for z, a in ((27.0, -100),):
	fit.box(BLUE, (60.9, F + 0.02, 26.8), (62.08, F + 2.2, 26.86), m=turn("y", a, (60.9, 0, 26.83)))    # the storeroom door: open in this build
fit.box(BLUE, (62.6, F + 0.02, 33.15), (63.78, F + 2.2, 33.21), m=turn("y", 95, (62.6, 0, 33.18)))
marker("storeroom", (61.2, F, 30.2))
# the loft: the old man's office
fit.box("oak_veneer_01", (58.0, LOFT + 0.74, 28.0), (60.2, LOFT + 0.79, 29.0), bevel=0.008)
fit.box("oak_veneer_01", (58.05, LOFT, 28.05), (58.6, LOFT + 0.74, 28.95))
fit.box("oak_veneer_01", (59.65, LOFT, 28.05), (60.15, LOFT + 0.74, 28.95))
goods.quad("G65_LedgerAndInvoices", (59.1, LOFT + 0.795, 28.5), UP, 1.5, 0.95, G65_DESK, up=(0, 0, -1))
goods.box(DARK, (58.2, LOFT + 0.79, 28.15), (58.5, LOFT + 0.9, 28.4), bevel=0.02)                 # the telephone; its handset hangs
goods.tube("black", [(58.25, LOFT + 0.85, 28.4), (58.1, LOFT + 0.6, 28.9), (57.95, LOFT + 0.25, 29.05)], 0.008, seg=4)
goods.box(DARK, (57.88, LOFT + 0.05, 29.0), (57.98, LOFT + 0.27, 29.08), bevel=0.01)
goods.cyl(GREEN, (59.9, LOFT + 0.79, 28.2), (59.9, LOFT + 1.2, 28.2), 0.02, seg=6)
goods.cyl(GREEN, (59.9, LOFT + 1.2, 28.2), (59.75, LOFT + 1.12, 28.35), 0.03, seg=8, r2=0.12)
light("bulb", (59.6, LOFT + 1.2, 28.6))
mc = turn("y", 200, (59.2, LOFT, 29.7))
fit.box(DARK, (58.95, LOFT + 0.42, 29.45), (59.45, LOFT + 0.5, 29.95), bevel=0.02, m=mc)
fit.box(DARK, (58.95, LOFT + 0.5, 29.45), (59.45, LOFT + 0.95, 29.51), bevel=0.02, m=mc)
fit.cyl(STEEL, (59.2, LOFT + 0.06, 29.7), (59.2, LOFT + 0.42, 29.7), 0.03, seg=6, m=mc)
for z in (30.6, 31.15, 31.7):
	fit.box(GREEN, (57.4, LOFT, z), (58.0, LOFT + 1.35, z + 0.5), bevel=0.01)                    # filing cabinets
fit.box(DARK, (64.2, LOFT, 27.5), (65.0, LOFT + 0.9, 28.3), bevel=0.02)                          # the safe
goods.cyl("chrome", (64.6, LOFT + 0.5, 28.3), (64.6, LOFT + 0.5, 28.33), 0.07, seg=10)
goods.quad("N16_Calendars", (57.36, LOFT + 1.7, 29.5), (1, 0, 0), 0.4, 0.5, CALENDAR_MILLER)
marker("loft", (61.0, LOFT, 30.5))
marker("awning_window", (61.0, SHOP_ROOF + 0.25, 26.8))
# rails round the gallery (seen), and what stops you walking off it (felt)
for y in (0.55, 1.05):
	trim.tube(STEEL, [(57.4, LOFT + y, 34.86), (65.8, LOFT + y, 34.86)], 0.022, seg=5, steps=1)
for x in (57.4, 59.5, 61.6, 63.7, 65.8):
	trim.cyl(STEEL, (x, LOFT, 34.86), (x, LOFT + 1.05, 34.86), 0.022, seg=5)
solid((57.35, LOFT, 34.82), (65.8, LOFT + 1.1, 34.9))

# =============================================================================
# The workshop: one tall room, the pit, the stair up to the gallery
# =============================================================================
wall(shell, RENDER, "z", 57.0, 57.35, 33.35, 49.0, F, HALL)
wall(shell, RENDER, "z", 76.65, 77.0, 33.0, 49.0, F, HALL)
wall(shell, RENDER, "x", 33.0, 33.35, 65.5, 77.0, F, HALL, [(73.2, 74.4, 0.0, 2.25)])
wall(shell, RENDER, "x", 33.0, 33.35, 57.0, 65.5, BACK_TOP, HALL)
wall(shell, RENDER, "x", 48.65, 49.0, 57.0, 77.0, F, HALL, [(64.7, 69.7, 0.0, 3.8)])
# the floor is four thick blocks round the pit, so the pit is a real hole
for a, b in (((57.0, 33.35), (66.6, 49.0)), ((67.8, 33.0), (77.0, 49.0)), ((66.6, 33.0), (67.8, 37.5)), ((66.6, 45.5), (67.8, 49.0))):   # under the yard door too
	shell.box("Concrete033", (a[0], -1.9, a[1]), (b[0], F, b[1]))
shell.box("oil", (66.3, -1.9, 37.2), (68.1, PIT, 45.8))
shell.prism("Concrete033", [(43.3, PIT), (45.5, F), (45.5, PIT)], "x", 66.6, 67.8)                # the way down, as one slope underfoot
for i in range(8):
	trim.box("Concrete033", (66.6, PIT + i * 0.1875, 43.3 + i * 0.275), (67.8, PIT + (i + 1) * 0.1875, 45.5))
for x in (66.56, 67.76):                                                                         # steel angle along the pit's edges
	trim.box("paint_yellow", (x, F, 37.5), (x + 0.08, F + 0.012, 45.5))
shell.prism("CorrugatedSteel003", [(32.6, HALL), (49.4, HALL), (49.4, HALL + 0.25), (41.0, HALL + 1.5), (32.6, HALL + 0.25)], "x", 56.6, 77.4)
for x in (61.0, 67.2, 73.4):                                                                     # trusses
	trim.box(STEEL, (x - 0.06, HALL - 0.5, 33.35), (x + 0.06, HALL - 0.38, 48.65))
	for z in (36.0, 41.0, 46.0):
		trim.box(STEEL, (x - 0.04, HALL - 0.5, z - 0.04), (x + 0.04, HALL, z + 0.04))
# the vehicle door, rolled up, and its rails
trim.box("CorrugatedSteel003", (64.6, F + 3.8, 48.55), (69.8, F + 4.45, 48.75))
for x in (64.62, 69.7):
	trim.box(STEEL, (x, F, 48.6), (x + 0.08, F + 3.8, 48.7))
trim.box("paint_yellow", (64.7, F, 48.62), (69.7, F + 0.012, 49.0))
fit.box(BLUE, (73.2, F + 0.02, 33.12), (74.38, F + 2.2, 33.18), m=turn("y", -110, (73.2, 0, 33.15)))    # the yard door, open
# the stair: a steel plate you walk on, treads you see, a side you cannot step through
shell.prism(STEEL, [(71.2, F), (65.8, LOFT), (65.8, LOFT - 0.2), (71.0, F)], "z", 33.35, 34.85)
for i in range(18):
	x = 71.2 - (i + 1) * 0.3
	trim.box("worn_planks", (x, F + (i + 1) * 0.1833 - 0.04, 33.37), (x + 0.3, F + (i + 1) * 0.1833, 34.83))
for dy in (0.55, 1.05):
	trim.tube(STEEL, [(70.5, F + 0.37 + dy, 34.86), (65.8, LOFT + dy, 34.86)], 0.022, seg=5, steps=1)
col.prism("black", [(70.5, F + 0.3), (70.5, F + 1.5), (65.8, LOFT + 1.1), (65.8, LOFT)], "z", 34.82, 34.9)
# the car over the pit, the lamp hanging down under it
def hulk(m, body, wheels=True):
	c = Shifted(fit, m)
	t = Shifted(trim, m)

	def arch(cx, r=0.39, cy=0.36):
		return [(cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))) for a in range(0, 181, 30)]
	outline = [(-2.38, 0.40), (-2.40, 0.62), (-2.37, 0.88), (-2.22, 0.965), (-1.0, 0.99), (1.2, 0.98), (2.22, 0.89),
			(2.38, 0.80), (2.40, 0.56), (2.36, 0.38), (2.0, 0.30)] + arch(1.45) + arch(-1.45) + [(-2.0, 0.30)]
	c.prism(body, outline, "z", -0.93, 0.93)
	c.hull("glass_dark", (-1.05, 0.985, -0.88, 1.3, 0.88), (-0.5, 1.44, -0.73, 0.72, 0.73), faces={"+y": body, "-y": "black"})
	for x0, x1 in ((2.34, 2.46), (-2.46, -2.34)):
		t.box("chrome", (x0, 0.38, -0.95), (x1, 0.56, 0.95), bevel=0.02)
	for cx in (-1.45, 1.45):
		for z0, z1 in ((-0.94, -0.72), (0.72, 0.94)):
			if wheels:
				t.cyl("tyre", (cx, 0.33, z0), (cx, 0.33, z1), 0.33, seg=14)
			else:
				c.box("Concrete033", (cx - 0.2, 0.0, z0 - 0.05), (cx + 0.2, 0.3, z1 + 0.05))


hulk(place(67.2, 39.9, 90, F + 0.32), "car_grey")
for x in (66.1, 67.9):
	for z in (38.1, 41.0):
		fit.box("worn_planks", (x, F, z), (x + 0.4, F + 0.32, z + 0.7))                         # up on timber blocks
trim.box("car_grey", (66.3, F + 1.32, 37.55), (68.1, F + 1.36, 38.9), m=turn("x", -55, (0, F + 1.32, 38.9)))    # bonnet up
trim.tube("black", [(67.5, HALL - 0.5, 41.0), (67.6, 2.0, 41.2), (67.75, 0.4, 41.6), (67.6, PIT + 1.0, 41.4)], 0.01, seg=4, steps=4)
trim.cyl("paint_yellow", (67.6, PIT + 0.78, 41.4), (67.6, PIT + 1.0, 41.4), 0.05, seg=8)
trim.cyl("lamp_warm", (67.6, PIT + 0.6, 41.4), (67.6, PIT + 0.78, 41.4), 0.04, seg=8)
light("bulb", (67.2, PIT + 0.7, 41.2))
fit.box("tool_red", (66.7, PIT, 37.9), (67.3, PIT + 0.34, 38.25), bevel=0.02)                     # the red toolbox that is not one
goods.box(DARK, (66.95, PIT + 0.34, 38.0), (67.05, PIT + 0.38, 38.15))
goods.tube("Rubber004", [(67.0, PIT + 0.02, 42.2), (67.3, PIT + 0.02, 42.5), (67.2, PIT + 0.02, 42.9), (66.9, PIT + 0.02, 42.6), (67.0, PIT + 0.02, 42.2)], 0.012, seg=5)
goods.box(BLUE, (67.3, PIT, 39.6), (67.7, PIT + 0.18, 40.1), bevel=0.02)                          # the jack
goods.cyl(STEEL, (67.5, PIT + 0.18, 39.85), (67.5, PIT + 0.32, 39.85), 0.05, seg=8)
for i in range(4):
	goods.box(STEEL, (66.75 + i * 0.12, PIT + 0.005, 40.6 + (i % 2) * 0.2), (66.78 + i * 0.12, PIT + 0.02, 40.85 + (i % 2) * 0.2),
			m=turn("y", i * 25, (66.8 + i * 0.12, 0, 40.7)))                                    # spanners where they fell
marker("pit", (67.2, PIT, 40.5))
marker("belt", (67.1, PIT + 0.05, 42.5))
marker("jack", (67.5, PIT + 0.2, 39.85))
marker("toolbox_mimic", (67.0, PIT + 0.2, 38.1))
marker("workshop", (62.0, F, 41.0))
# the second bay: an engine on a hoist, a real tool chest, stands, a bench the length of the wall
fit.box("worn_planks", (75.85, F + 0.86, 35.0), (76.65, F + 0.92, 47.0), bevel=0.008)
for z in (35.1, 38.0, 41.0, 44.0, 46.84):
	fit.box(STEEL, (75.9, F, z), (75.96, F + 0.86, z + 0.06))
fit.box(STEEL, (75.9, F + 0.2, 35.1), (76.6, F + 0.23, 46.9))
goods.box(BLUE, (76.0, F + 0.92, 37.0), (76.3, F + 1.12, 37.35), bevel=0.02)                      # vise
goods.box(DARK, (76.0, F + 0.92, 43.6), (76.5, F + 1.18, 44.3), bevel=0.02)                       # the radio
goods.quad("display", (75.995, F + 1.08, 43.95), (-1, 0, 0), 0.3, 0.07)
trim.box("G45_ToolOutlineWall", (76.6, F + 1.2, 36.0), (76.65, F + 2.8, 46.0))
fit.box("tool_red", (72.4, F, 46.6), (73.6, F + 1.05, 47.3), bevel=0.02)
for i in range(4):
	goods.box(DARK, (72.5, F + 0.14 + i * 0.22, 46.585), (73.5, F + 0.3 + i * 0.22, 46.6))
for x, z in ((71.2, 39.4), (71.9, 39.4), (71.2, 42.6), (71.9, 42.6)):                             # axle stands, nothing on them
	fit.cyl("tool_red", (x, F, z), (x, F + 0.42, z), 0.16, seg=6, r2=0.05)
mh = place(73.6, 41.0, 20)
h = Shifted(fit, mh)
h.box("tool_red", (-0.9, F, -0.5), (0.9, F + 0.1, -0.42))
h.box("tool_red", (-0.9, F, 0.42), (0.9, F + 0.1, 0.5))
h.box("tool_red", (-0.95, F, -0.5), (-0.85, F + 1.9, 0.5))
fit.box("tool_red", (-0.9, F + 1.8, -0.06), (0.7, F + 1.9, 0.06), m=mh @ turn("z", 16, (-0.9, F + 1.85, 0)))
Shifted(trim, mh).cyl("black", (0.5, F + 2.2, 0.0), (0.5, F + 1.25, 0.0), 0.012, seg=4)
h.box(RUSTY, (0.15, F + 0.75, -0.3), (0.85, F + 1.25, 0.3), bevel=0.04)                            # the engine it lifted out
for x in (58.0, 58.9, 59.8):                                                                       # tyres on the west wall
	for y in (0.0, 0.26, 0.52, 0.78):
		if random.random() > 0.2:
			fit.cyl("tyre", (x, F + y, 44.6), (x, F + y + 0.24, 44.6), 0.36, seg=12)
for x, z in ((58.0, 47.9), (58.75, 48.0), (63.6, 48.0)):                                            # drums by the door
	fit.cyl(BLUE if x < 60 else DARK, (x, F, z), (x, F + 0.88, z), 0.29, seg=14)
fit.cyl(STEEL, (60.4, F + 0.5, 34.2), (62.2, F + 0.5, 34.2), 0.4, seg=14)                           # compressor under the gallery
fit.box(DARK, (60.9, F + 0.9, 33.95), (61.7, F + 1.3, 34.45), bevel=0.03)
for x0 in (58.0, 60.6):                                                                             # oils and fluids on the south wall
	for x in (x0, x0 + 2.35):
		fit.box(STEEL, (x, F, 48.1), (x + 0.05, F + 2.2, 48.6))
	for i in range(4):
		y = F + 0.15 + i * 0.52
		fit.box(STEEL, (x0, y - 0.03, 48.1), (x0 + 2.4, y, 48.6))
		stock(goods, place(x0 + 2.4, 48.6, 180), 0.05, 2.35, y, 0.5, MOTOR, gaps=0.25)
pr = G59_POSTER
goods.quad("G59_TyreBrandPostersPriceList", (57.36, F + 2.2, 40.0), (1, 0, 0), 1.6, 1.6 * (pr[3] - pr[1]) / (pr[2] - pr[0]), pr)
pp = G59_PRICES
goods.quad("G59_TyreBrandPostersPriceList", (71.0, F + 2.4, 33.36), (0, 0, 1), 2.2, 2.2 * (pp[3] - pp[1]) / (pp[2] - pp[0]), pp)
goods.quad("N12_SafetySigns", (75.4, F + 2.3, 33.36), (0, 0, 1), 0.4, 0.44, SIGN_EYES)
goods.quad("N12_SafetySigns", (64.2, F + 2.0, 48.64), (0, 0, -1), 0.36, 0.42, SIGN_EXTINGUISHER)
goods.cyl("tool_red", (64.2, F + 0.9, 48.5), (64.2, F + 1.5, 48.5), 0.08, seg=8)
for x, z, on in ((62.0, 41.0, True), (72.0, 38.0, False), (72.5, 45.0, True)):
	trim.cyl(GREEN, (x, HALL - 1.1, z), (x, HALL - 1.4, z), 0.08, seg=10, r2=0.3)
	trim.cyl("lamp_warm" if on else "lamp_off", (x, HALL - 1.41, z), (x, HALL - 1.4, z), 0.2, seg=10)
	trim.cyl("black", (x, HALL - 1.1, z), (x, HALL - 0.5, z), 0.01, seg=4)
	if on:
		light("highbay", (x, HALL - 1.6, z))
dirt.decal("M27_EngineOilStains", (72.6, F, 40.8), UP, 2.2, 2.2, 30, QUARTERS[1])
dirt.decal("M27_EngineOilStains", (63.5, F, 44.0), UP, 1.8, 1.8, 200, QUARTERS[2])
dirt.decal("L16bc_BlackOilDrips", (66.0, F, 46.6), UP, 1.3, 1.3, 120, lift=0.006)
dirt.decal("L16bd_BlackOilDrips", (68.6, F, 43.6), UP, 1.2, 1.2, 10, lift=0.006)
dirt.decal("A39_OilDripSpots", (74.0, F, 44.5), UP, 1.0, 1.0, 0, QUARTERS[0])
dirt.decal("Leaking005", (57.355, 5.0, 38.0), (1, 0, 0), 3.4, 1.7)
dirt.decal("Leaking002", (70.0, 5.2, 48.645), (0, 0, -1), 3.2, 1.6)
dirt.decal("M13a_WallCracks", (76.645, 3.6, 41.0), (-1, 0, 0), 1.8, 1.8)

# =============================================================================
# The scrapyard beside the shop, reached from the workshop
# =============================================================================
hulk(place(74.2, 16.0, 78), "car_rust", wheels=False)
hulk(place(74.6, 24.5, 100), "car_grey", wheels=False)
trim.box("car_rust", (73.2, 1.0, 13.9), (75.0, 1.04, 15.2), m=turn("x", 12, (0, 1.0, 13.9)) @ turn("y", 10, (74.1, 0, 14.5)))
for x, z, n in ((72.0, 30.5, 4), (72.9, 31.2, 3), (72.2, 31.8, 2), (76.5, 11.5, 5), (68.0, 31.0, 3)):
	for i in range(n):
		yard.cyl("tyre", (x + (i % 2) * 0.06, i * 0.24, z), (x + (i % 2) * 0.06, i * 0.24 + 0.23, z), 0.36, seg=12)
yard.box(RUSTY, (66.2, 0.0, 28.0), (67.4, 0.7, 29.0), bevel=0.05, m=turn("y", 25, (66.8, 0, 28.5)))        # an engine block in the weeds
yard.box(RUSTY, (69.2, 0.0, 28.4), (70.4, 0.5, 30.6), bevel=0.03)
for i in range(7):
	x, z = random.uniform(71.5, 77.0), random.uniform(10.5, 32.0)
	trim.cyl(RUSTY, (x, 0.03, z), (x + random.uniform(0.8, 2.0), 0.03, z), 0.02, seg=5, m=turn("y", random.uniform(0, 180), (x, 0, z)))
trim.cyl(STEEL, (77.6, 0.0, 20.0), (77.6, 4.2, 20.0), 0.05, seg=8)                                           # the yard lamp: dead
trim.box("lamp_off", (77.0, 4.1, 19.85), (77.6, 4.2, 20.15))
dirt.decal("A36_PuddleMudRim", (73.0, 0.004, 20.5), UP, 3.2, 3.2, 40, QUARTERS[1])
dirt.decal("L16ba_BlackOilDrips", (74.0, 0.004, 17.6), UP, 1.4, 1.4, 0, lift=0.006)
marker("yard", (72.5, 0.05, 26.0))

# =============================================================================
# The service court behind: the vehicle door opens onto it, its gate onto the corner
# =============================================================================
def fence(ax, az, bx, bz, y1=2.4):
	length = math.hypot(bx - ax, bz - az)
	n = max(1, round(length / 2.5))
	for i in range(n + 1):
		t = i / n
		trim.cyl(STEEL, (ax + (bx - ax) * t, 0.0, az + (bz - az) * t), (ax + (bx - ax) * t, y1 + 0.06, az + (bz - az) * t), 0.03, seg=6)
	trim.cyl(STEEL, (ax, y1, az), (bx, y1, bz), 0.02, seg=6)
	corners = ((ax, 0.04, az), (bx, 0.04, bz), (bx, y1, bz), (ax, y1, az))
	trim._face([trim._v(q) for q in corners], "ChainLink")
	trim._face([trim._v(q) for q in reversed(corners)], "ChainLink")
	if abs(bx - ax) > abs(bz - az):
		solid((min(ax, bx), 0.0, az - 0.04), (max(ax, bx), y1, az + 0.04))
	else:
		solid((ax - 0.04, 0.0, min(az, bz)), (ax + 0.04, y1, max(az, bz)))


fence(56.15, 49.0, 56.15, 53.0)
fence(56.15, 58.0, 56.15, 61.5)
fence(78.0, 6.0, 78.0, 61.5)                     # east: beyond it the ditch
fence(56.3, 6.0, 78.0, 6.0)                      # north
fence(56.3, 6.0, 56.3, 10.0)
fence(71.0, 10.0, 71.0, 6.0)
for z, a in ((53.0, 96), (58.0, -84)):           # the gate onto the corner, both leaves swung in
	s = 1 if z < 55 else -1
	m = turn("y", a, (56.15, 0, z))
	for zz in (z, z + s * 2.45):
		trim.cyl(STEEL, (56.15, 0.08, zz), (56.15, 2.3, zz), 0.025, seg=6, m=m)
	for y in (0.08, 2.3):
		trim.cyl(STEEL, (56.15, y, z), (56.15, y, z + s * 2.45), 0.025, seg=6, m=m)
	corners = ((56.15, 0.08, z), (56.15, 0.08, z + s * 2.45), (56.15, 2.3, z + s * 2.45), (56.15, 2.3, z))
	trim._face([trim._v(q, m) for q in corners], "ChainLink")
	trim._face([trim._v(q, m) for q in reversed(corners)], "ChainLink")
	solid((56.11, 0.0, min(z, z + s * 2.45)), (56.19, 2.3, max(z, z + s * 2.45)), m=m)
trim.box(STEEL, (66.9, 4.3, 49.0), (67.5, 4.5, 49.16), bevel=0.01)
trim.quad("lamp_warm", (67.2, 4.295, 49.08), (0, -1, 0), 0.24, 0.12, up=(0, 0, 1))
light("wallpack", (67.2, 4.1, 49.7))
yard.prism(BLUE, [(59.4, 0.16), (60.55, 0.16), (60.55, 1.27), (59.38, 1.02)], "x", 70.6, 72.4)              # a bin against the back wall
for i, yaw in enumerate((0, 6, -4)):
	mm = turn("y", yaw, (75.6, 0, 52.5)) @ lotkit.Matrix.Translation((75.0, i * 0.14, 52.0))
	for dz in (0.0, 0.45, 0.9):
		trim.box("worn_planks", (0, 0.02, dz), (1.2, 0.11, dz + 0.1), m=mm)
	for k in range(5):
		trim.box("worn_planks", (k * 0.275, 0.11, 0), (k * 0.275 + 0.1, 0.13, 1.0), m=mm)
solid((74.95, 0.0, 51.95), (76.25, 0.42, 53.05))
for x, z in ((59.0, 50.2), (59.75, 50.0)):
	yard.cyl(DARK, (x, 0.0, z), (x, 0.88, z), 0.29, seg=14)
for c, s, yaw in (((73.0, 0.3, 59.8), (0.7, 0.55, 0.6), 20), ((72.2, 0.25, 59.4), (0.6, 0.45, 0.55), 70), ((69.6, 0.28, 60.4), (0.6, 0.5, 0.6), 130)):
	trim.blob("trash_bag", c, s, yaw=yaw)
dirt.decal("M27_EngineOilStains", (67.0, 0.045, 50.4), UP, 2.0, 2.0, 60, QUARTERS[3])
dirt.decal("A36_PuddleMudRim", (63.0, 0.004, 56.5), UP, 3.4, 3.4, 20, QUARTERS[0])
dirt.decal("A36_PuddleMudRim", (72.5, 0.004, 55.0), UP, 2.8, 2.8, 140, QUARTERS[2])
dirt.decal("Leaking007", (61.0, 5.4, 49.005), (0, 0, 1), 2.4, 0.8)
dirt.decal("M13d_WallCracks", (73.0, 1.6, 49.005), (0, 0, 1), 1.4, 1.4)
dirt.decal("M31_LitterAtlas", (58.0, 0.004, 59.0), UP, 1.0, 1.0, 80)
marker("court", (64.0, 0.05, 55.5))
marker("gate_b", (57.4, 0.05, 55.5))
# the edges of what exists so far
solid((77.9, 0.0, 5.7), (78.2, 4.0, 62.0))
solid((56.0, 0.0, 5.7), (78.2, 4.0, 6.0))
solid((56.0, 0.0, 0.0), (56.3, 4.0, 10.0))        # the street's east side north of the shop

for part in (ground, shell, trim, glass, fit, goods, yard, dirt, col):
	part.finish()

os.makedirs(os.path.join(lotkit.ROOT, "fps", "assets", "block"), exist_ok=True)
lotkit.save(os.path.join(HERE, "miller.blend"), os.path.join(lotkit.ROOT, "fps", "assets", "block", "miller.glb"))

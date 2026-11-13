"""The brick row on the corner: the Rusty Anchor tavern and the coin laundry,
the alley behind them and the lane round the east end to the service court.

  blender --background --factory-startup --python art/block/build_brick_row.py

Writes art/block/brick_row.blend and fps/assets/block/brick_row.glb. Positions
are the plan sketch's (art/block/draw_plan.py): metres, x east, z south, y up.

  What it was     the corner bar the mill and the yards drank in, Lou behind its U of a
                  counter; next door the laundry that never shuts, two islands of
                  washers, dryers down one wall
  Half an hour    last orders ran late; when the lights dipped the jukebox went on
  ago             playing by itself; the stools went over in a line towards the kitchen
                  and the back door, drinks left on the bar; next door the washers are
                  turning with nobody's washing in them, and one has let itself out
  The player      comes for the spare key to Miller's storeroom, which is wherever Lou
                  is now (the jukebox); the U of the bar is a ring to fight round; out
                  through the kitchen to the alley, and by the lane to the service
                  court: the third shortcut

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

random.seed(77)
lotkit.reset_scene()

for name, metres in {
		"Concrete033": 3.0, "Concrete040": 2.5, "red_brick": 2.0, "WoodFloor051": 2.0, "D16_TinCeiling": 2.4, "D27_PatternedOldCarpet": 1.5,
		"D03_StripedWallpaper": 1.0, "H29_TavernRestroom": 2.0, "D24_VCTFloorTiles": 2.0, "OfficeCeiling001": 2.4, "D10_LatexDrywall": 2.8,
		"long_white_tiles": 1.2, "PaintedMetal012": 2.0, "PaintedMetal006": 2.0, "PaintedMetal016": 0.8, "PaintedMetal007": 2.0,
		"Metal053B": 1.5, "bitumen": 3.0, "oak_veneer_01": 1.5, "Plastic010": 1.0, "Plastic012B": 1.0, "K04_YellowedABSPlastic": 0.4,
		"worn_planks": 1.6, "ChainLink": 0.25, "Rubber004": 1.0}.items():
	texture_set(name, metres)

flat("glass", (0.55, 0.70, 0.74), rough=0.05, alpha=0.16)
flat("glass_dark", (0.02, 0.03, 0.04), rough=0.08, alpha=0.82)
flat("lamp_cool", (0.9, 0.95, 1.0), emit=(0.85, 0.93, 1.0), strength=2.4)
flat("lamp_warm", (1.0, 0.8, 0.5), emit=(1.0, 0.72, 0.4), strength=2.2)
flat("lamp_off", (0.42, 0.43, 0.40), rough=0.5)
flat("chrome", (0.75, 0.75, 0.76), rough=0.22, metal=1.0)
flat("black", (0.02, 0.02, 0.022), rough=0.6)
flat("vinyl_red", (0.3, 0.04, 0.04), rough=0.45)
flat("beer", (0.5, 0.3, 0.05), rough=0.1, alpha=0.8)
flat("paper", (0.78, 0.76, 0.68), rough=0.9)
flat("cardboard", (0.42, 0.31, 0.19), rough=0.9)
flat("tool_red", (0.5, 0.05, 0.04), rough=0.35, metal=0.3)
flat("screen", (0.25, 0.3, 0.3), rough=0.2, emit=(0.5, 0.62, 0.66), strength=0.9)
flat("night", (0.006, 0.008, 0.012), rough=1.0)
flat("trash_bag", (0.02, 0.02, 0.022), rough=0.32)
flat("basket_blue", (0.1, 0.25, 0.5), rough=0.5)
flat("cloth", (0.5, 0.48, 0.42), rough=0.95)

decal_set("M27_EngineOilStains", "A36_PuddleMudRim", "L16ba_BlackOilDrips", "L16bc_BlackOilDrips", "L16bd_BlackOilDrips", "M07a_RustStreaks",
		"M07c_RustStreaks", "M13a_WallCracks", "M05a_CeilingMould", "Leaking002", "Leaking005", "Leaking007", "M28_DrinkSpills",
		"M31_LitterAtlas", "M23d_ApplianceDragMarks", "I02_WindowDecal", "N10_DoorWindowStickers")

BRICK = "red_brick"
WHITE = "PaintedMetal012"
GREEN = "PaintedMetal006"
STEEL = "Metal053B"
DARK = "Plastic012B"
RUSTY = "PaintedMetal016"
BLUE = "PaintedMetal007"
WOOD = "oak_veneer_01"
DRYWALL = "D10_LatexDrywall"
UP = (0, 1, 0)

F = 0.15
CEIL = F + 4.05          # ceilings of both shops
FLOOR2 = 4.5             # the flats over them: outside only
TOP = 8.6                # parapet

H01 = (0.059, 0.094, 0.930, 0.875)
H02 = [(0.062, 0.047, 0.473, 0.508), (0.500, 0.055, 0.926, 0.516), (0.062, 0.504, 0.461, 0.926), (0.477, 0.531, 0.953, 0.918)]
H10 = [(0.035, 0.191, 0.164, 0.488), (0.207, 0.180, 0.320, 0.488), (0.363, 0.184, 0.484, 0.488), (0.520, 0.191, 0.648, 0.488),
		(0.691, 0.176, 0.789, 0.488), (0.836, 0.191, 0.965, 0.488), (0.035, 0.504, 0.168, 0.812), (0.535, 0.500, 0.633, 0.812),
		(0.688, 0.500, 0.785, 0.812), (0.844, 0.500, 0.945, 0.812), (0.199, 0.527, 0.340, 0.812), (0.371, 0.512, 0.484, 0.812)]
H16 = (0.043, 0.0, 0.957, 0.996)
H19_GLASS = (0.184, 0.18, 0.422, 0.82)
H19_FIELD = (0.508, 0.184, 0.82, 0.77)
H21 = (0.172, 0.008, 0.828, 0.992)
H23 = (0.004, 0.176, 0.996, 0.824)
H25 = (0.0, 0.164, 1.0, 0.836)
H27 = (0.0, 0.25, 1.0, 0.73)
I01 = (0.027, 0.418, 0.973, 0.566)
I02 = (0.02, 0.16, 0.98, 0.84)
I06 = (0.004, 0.004, 0.996, 0.996)
I14 = (0.004, 0.172, 0.996, 0.828)
I15 = (0.172, 0.008, 0.824, 0.992)
I22 = (0.008, 0.172, 0.992, 0.828)
I24 = (0.031, 0.035, 0.961, 0.98)
SIGN_EMPLOYEES = (0.27, 0.51, 0.5, 0.73)
STICKER_NO_RESTROOM = (0.76, 0.66, 0.98, 0.82)
BOTTLES = [("H10_BottlesAndLabels", r, 0.09, 0.09) for r in H10]


def aspect(r):
	return (r[3] - r[1]) / (r[2] - r[0])


class Shifted:
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


def stock(part, m, x0, x1, y, z_front, items, gaps=0.12, max_h=0.34):
	x = x0 + 0.03
	while x < x1 - 0.1:
		image, rect, w, d = random.choice(items)
		h = min(w * aspect(rect), max_h)
		for _ in range(random.randint(1, 3)):
			if x + w > x1 - 0.02:
				break
			if random.random() > gaps:
				part.image_box(image, (x, y, z_front - d), (x + w, y + h, z_front), "+z", rect, m=m)
			x += w + 0.02
		x += random.uniform(0.0, 0.08)


ground = Part("row_ground")
shell = Part("row_shell")
trim = Part("row_trim-nocol")
glass = Part("row_glass")
fit = Part("row_fittings")
goods = Part("row_goods-nocol")
alley = Part("row_alley")
dirt = Part("row_dirt-nocol")
col = Part("row_props-colonly")


def solid(a, b, m=None):
	col.box("black", a, b, m=m)


# =============================================================================
# Ground: a strip in front, the alley behind, the lane round the east end
# =============================================================================
ground.box("Concrete040", (37.0, 0.0, 61.5), (56.0, F, 62.0), skip="-y")
ground.box("Concrete033", (36.5, -0.4, 82.0), (78.0, 0.012, 88.5), skip="-y")
ground.box("Concrete033", (70.0, -0.4, 61.5), (78.0, 0.012, 82.0), skip="-y")

# =============================================================================
# The shell: one brick block, two shops below, dark flats above
# =============================================================================
# north front: the tavern's door and windows; the laundry's door is the only piece of it on the street
wall(shell, BRICK, "x", 62.0, 62.35, 37.0, 70.0, F, TOP, [(38.6, 40.4, 0.0, 2.5), (41.6, 45.4, 1.0, 3.0), (46.6, 51.6, 1.0, 3.0),
		(53.6, 55.4, 0.0, 2.5), (57.6, 61.0, 2.3, 3.5), (62.0, 65.4, 2.3, 3.5), (66.4, 68.8, 2.3, 3.5)])
wall(shell, BRICK, "x", 81.65, 82.0, 37.0, 70.0, F, TOP, [(43.2, 44.4, 0.0, 2.25), (64.5, 65.7, 0.0, 2.25)])
wall(shell, BRICK, "z", 37.0, 37.35, 62.0, 82.0, F, TOP, [(65.0, 67.2, 1.4, 2.8), (69.4, 71.6, 1.4, 2.8), (73.0, 75.2, 1.4, 2.8)])
wall(shell, BRICK, "z", 69.65, 70.0, 62.0, 82.0, F, TOP)
wall(shell, BRICK, "z", 52.8, 53.15, 62.35, 81.65, F, FLOOR2)                              # the wall between the two
shell.box("WoodFloor051", (37.0, -0.4, 62.0), (52.8, F, 82.0), skip="-y")
shell.box("D24_VCTFloorTiles", (52.8, -0.4, 62.0), (70.0, F, 82.0), skip="-y")
shell.box("D16_TinCeiling", (37.35, CEIL, 62.35), (52.8, CEIL + 0.05, 81.65), skip="+y")
shell.box("OfficeCeiling001", (53.15, CEIL, 62.35), (69.65, CEIL + 0.05, 81.65), skip="+y")
shell.box("bitumen", (37.0, FLOOR2 - 0.2, 62.0), (70.0, FLOOR2, 82.0))
shell.box("bitumen", (37.35, TOP - 0.6, 62.35), (69.65, TOP - 0.4, 81.65))
# what gives the block an outline: stone bands, a cornice, piers, the flats' windows, downpipes, a fire escape
for y, d in ((F + 3.55, 0.1), (FLOOR2 + 0.05, 0.14), (TOP - 0.35, 0.2)):
	trim.box("Concrete033", (36.9 - d * 0.3, y, 62.0 - d), (70.1, y + 0.22, 62.0))
	trim.box("Concrete033", (37.0 - d, y, 62.0), (37.0, y + 0.22, 82.0))
trim.box("Concrete033", (36.85, TOP, 61.85), (70.15, TOP + 0.12, 62.4), bevel=0.02)
trim.box("Concrete033", (36.85, TOP, 61.85), (37.4, TOP + 0.12, 82.15), bevel=0.02)
for x in (37.0, 40.8, 45.7, 52.6, 55.8, 69.6):
	shell.box(BRICK, (x, F, 61.86), (x + 0.45, TOP, 62.0))
for i, x in enumerate((38.4, 42.2, 46.0, 49.8, 54.4, 58.4, 62.4, 66.4)):                    # the flats: dark, but for one
	lit = i == 6
	trim.box("screen" if lit else "night", (x, FLOOR2 + 0.9, 61.97), (x + 1.3, FLOOR2 + 2.9, 62.0))
	trim.box("Concrete033", (x - 0.1, FLOOR2 + 0.78, 61.9), (x + 1.4, FLOOR2 + 0.9, 62.0))
	trim.box("Concrete033", (x - 0.1, FLOOR2 + 2.9, 61.9), (x + 1.4, FLOOR2 + 3.1, 62.0))
	trim.box(WHITE, (x + 0.62, FLOOR2 + 0.9, 61.95), (x + 0.68, FLOOR2 + 2.9, 61.98))
	if lit:
		light("cooler", (x + 0.65, FLOOR2 + 1.9, 61.2))
for x in (37.6, 69.3):
	trim.cyl(WHITE, (x, 0.3, 61.9), (x, TOP - 0.2, 61.9), 0.05, seg=8)
for y in (FLOOR2 + 0.6, FLOOR2 + 3.3):                                                       # the fire escape on the back
	alley_y = y
	trim.box(STEEL, (46.0, alley_y, 82.0), (50.0, alley_y + 0.05, 83.0))
	for x in (46.0, 48.0, 50.0):
		trim.cyl(STEEL, (x, alley_y, 82.98), (x, alley_y + 1.0, 82.98), 0.02, seg=5)
	trim.tube(STEEL, [(46.0, alley_y + 1.0, 82.98), (50.0, alley_y + 1.0, 82.98)], 0.02, seg=5, steps=1)
trim.tube(STEEL, [(46.3, FLOOR2 + 0.6, 82.7), (49.7, FLOOR2 + 3.3, 82.7)], 0.03, seg=5, steps=1)
trim.tube(STEEL, [(46.5, FLOOR2 + 0.6, 82.9), (46.5, 2.6, 82.9)], 0.025, seg=5, steps=1)          # its ladder stops short of the ground

# =============================================================================
# THE RUSTY ANCHOR
# =============================================================================
# front: the neon over the corner, the door standing open, beer signs in the windows
nw = 3.3
trim.box(DARK, (45.2, 4.9, 61.72), (45.2 + nw + 0.3, 4.9 + nw * aspect(H01) + 0.3, 61.86), bevel=0.02)
trim.quad("H01_TavernNeonSign", (45.35 + nw / 2, 5.05 + nw * aspect(H01) / 2, 61.715), (0, 0, -1), nw, nw * aspect(H01), H01)
light("neon", (47.0, 5.6, 60.6))
light("sign_blue", (47.0, 6.8, 60.8))
for z0, a in ((38.6, 100), (40.4, -78)):
	trim.box(WOOD, (z0 - 0.05, F, 62.02), (z0 + 0.05, F + 2.55, 62.38))
	s = 1 if z0 < 39.5 else -1
	fit.box(WOOD, (min(z0, z0 + s * 0.88), F + 0.02, 62.15), (max(z0, z0 + s * 0.88), F + 2.45, 62.2), m=turn("y", a, (z0, 0, 62.17)))
trim.box(WOOD, (38.55, F + 2.5, 62.02), (40.45, F + 2.6, 62.38))
light("door", (39.5, 2.8, 61.3))
for (x0, x1), signs in (((41.6, 45.4), (0, 2)), ((46.6, 51.6), (1, 3, 0))):
	glass.box("glass_dark", (x0, F + 1.0, 62.16), (x1, F + 3.0, 62.19))
	trim.box(WOOD, (x0 - 0.06, F + 0.92, 62.02), (x1 + 0.06, F + 1.0, 62.4))
	trim.box(WOOD, (x0 - 0.06, F + 3.0, 62.02), (x1 + 0.06, F + 3.08, 62.4))
	for k, i in enumerate(signs):
		x = x0 + (x1 - x0) * (k + 0.5) / len(signs)
		goods.quad("H02_BeerNeon", (x, F + 2.1, 62.14), (0, 0, -1), 1.15, 1.15 * aspect(H02[i]), H02[i])
		goods.quad("H02_BeerNeon", (x, F + 2.1, 62.21), (0, 0, 1), 1.15, 1.15 * aspect(H02[i]), H02[i])
light("neon", (43.5, F + 2.1, 63.2))
light("sign_green", (50.4, F + 2.1, 63.2))
light("sign_warm", (47.8, F + 2.1, 63.2))
for z0, z1 in ((65.0, 67.2), (69.4, 71.6), (73.0, 75.2)):
	glass.box("glass_dark", (37.16, F + 1.4, z0), (37.19, F + 2.8, z1))
	trim.box(WOOD, (37.02, F + 1.32, z0 - 0.05), (37.4, F + 1.4, z1 + 0.05))
# inside: panelling to shoulder height, striped paper above it, the back rooms behind a partition
for a, b in (((37.35, 62.35), (52.8, 62.4)), ((37.35, 62.35), (37.4, 76.0)), ((52.75, 62.35), (52.8, 76.0))):
	trim.box(WOOD, (a[0], F, a[1]), (b[0], F + 1.3, b[1]))
	trim.box("D03_StripedWallpaper", (a[0], F + 1.3, a[1]), (b[0], CEIL, b[1]))
	trim.box(WOOD, (a[0] - 0.01, F + 1.3, a[1] - 0.01), (b[0] + 0.01, F + 1.36, b[1] + 0.01))
wall(shell, WOOD, "x", 76.0, 76.15, 37.35, 52.8, F, CEIL, [(40.0, 41.2, 0.0, 2.25), (48.0, 49.2, 0.0, 2.25)])
wall(shell, DRYWALL, "z", 45.0, 45.15, 76.15, 81.65, F, CEIL)
shell.box("D27_PatternedOldCarpet", (37.4, F, 63.6), (40.0, F + 0.012, 75.8), skip="-y")             # carpet under the booths
# the U of the bar, open towards the kitchen; the back bar stands in the middle of it
for a, b in (((43.0, 66.0), (49.0, 66.8)), ((43.0, 66.8), (43.8, 72.0)), ((48.2, 66.8), (49.0, 72.0))):
	fit.box(WOOD, (a[0], F, a[1]), (b[0], F + 1.08, b[1]))
	fit.box("worn_planks", (a[0] - 0.12, F + 1.08, a[1] - 0.12), (b[0] + 0.12, F + 1.14, b[1] + 0.12), bevel=0.02)
trim.tube("chrome", [(42.75, F + 0.2, 72.0), (42.75, F + 0.2, 65.75), (49.25, F + 0.2, 65.75), (49.25, F + 0.2, 72.0)], 0.025, seg=6, steps=1)
fit.box(WOOD, (45.0, F, 68.2), (47.0, F + 1.0, 71.6))
fit.box(WOOD, (45.4, F + 1.0, 68.6), (46.6, F + 2.4, 71.2))
for face, zf in ((0, 68.6), (180, 71.2)):
	for y in (1.05, 1.5, 1.95):
		mm = place(46.6 if face == 0 else 45.4, zf, 180 if face == 0 else 0)
		goods.box("glass", (0.0, F + y - 0.02, 0.0), (1.2, F + y, 0.2), m=mm)
		stock(goods, mm, 0.02, 1.18, F + y, 0.18, BOTTLES, gaps=0.15)
for side, x in ((270, 45.4), (90, 46.6)):
	for y in (1.05, 1.5, 1.95):
		mm = place(x, 68.7 if side == 270 else 71.1, side)
		goods.box("glass", (0.0, F + y - 0.02, 0.0), (2.4, F + y, 0.14), m=mm)
		stock(goods, mm, 0.05, 2.35, F + y, 0.12, BOTTLES, gaps=0.2)
for x, z, toppled, yaw in ((42.4, 67.4, False, 0), (42.4, 68.8, False, 0), (42.3, 70.3, True, 80), (44.3, 65.4, False, 0), (45.9, 65.4, False, 0),
		(47.6, 65.3, True, 200), (49.6, 67.6, False, 0), (49.7, 69.2, True, 170), (49.5, 71.0, False, 0), (47.4, 73.2, True, 150),
		(45.6, 74.4, True, 190), (44.4, 75.3, True, 160)):
	m = place(x, z, yaw) @ (turn("z", 90, (0, F + 0.02, 0)) if toppled else lotkit.Matrix.Identity(4))
	s = Shifted(fit, m)
	s.cyl("chrome", (0, F, 0), (0, F + 0.74, 0), 0.03, seg=6)
	s.cyl("black", (0, F, 0), (0, F + 0.03, 0), 0.2, seg=10)
	s.cyl("vinyl_red", (0, F + 0.74, 0), (0, F + 0.82, 0), 0.19, seg=12)
for x, z, full in ((43.4, 67.6, True), (43.3, 69.5, False), (44.6, 66.4, True), (46.4, 66.3, True), (48.6, 68.2, False), (48.5, 70.4, True), (47.5, 66.5, False)):
	goods.cyl("glass", (x, F + 1.14, z), (x, F + 1.3, z), 0.04, seg=8)
	if full:
		goods.cyl("beer", (x, F + 1.145, z), (x, F + 1.25, z), 0.035, seg=8)
for x, z in ((43.5, 68.6), (46.0, 66.5), (48.6, 69.4)):
	goods.cyl("glass_dark", (x, F + 1.14, z), (x, F + 1.17, z), 0.07, seg=10)                       # ashtrays
dirt.decal("M28_DrinkSpills", (47.0, F, 73.6), UP, 1.2, 1.2, 150, QUARTERS[3])
dirt.decal("M28_DrinkSpills", (42.2, F, 70.6), UP, 0.9, 0.9, 40, QUARTERS[1])
for x in (44.4, 46.0, 47.6):                                                                      # lamps over the bar, one out
	on = x != 46.0
	trim.cyl("black", (x, F + 2.6, 66.4), (x, CEIL, 66.4), 0.006, seg=3)
	trim.cyl(GREEN, (x, F + 2.6, 66.4), (x, F + 2.45, 66.4), 0.05, seg=8, r2=0.2)
	trim.cyl("lamp_warm" if on else "lamp_off", (x, F + 2.44, 66.4), (x, F + 2.45, 66.4), 0.12, seg=8)
	if on:
		light("pendant", (x, F + 2.2, 66.4))
goods.quad("H27_HolidayLights", (46.0, F + 2.75, 68.55), (0, 0, -1), 2.6, 2.6 * aspect(H27), H27)
goods.quad("H27_HolidayLights", (46.0, F + 2.75, 71.25), (0, 0, 1), 2.6, 2.6 * aspect(H27), H27)
light("sign_warm", (46.0, F + 2.5, 72.2))
goods.box(DARK, (45.5, F + 2.45, 68.9), (46.5, F + 3.2, 69.7), bevel=0.02)                          # the television over the back bar: on, snow
goods.quad("screen", (46.0, F + 2.82, 68.895), (0, 0, -1), 0.8, 0.6)
# booths down the west wall
for z in (64.0, 67.0, 70.0, 73.0):
	for dz in (0.0, 1.75):
		fit.box("vinyl_red", (37.42, F, z + dz), (39.3, F + 0.48, z + dz + 0.55), bevel=0.03)
		fit.box("vinyl_red", (37.42, F + 0.48, z + dz + (0.0 if dz == 0 else 0.43)), (39.3, F + 1.25, z + dz + (0.12 if dz == 0 else 0.55)), bevel=0.02)
	fit.box(STEEL, (38.3, F, z + 1.1), (38.4, F + 0.72, z + 1.2))
	fit.box(WOOD, (37.5, F + 0.72, z + 0.72), (39.2, F + 0.78, z + 1.58), bevel=0.01)
	goods.quad("H23_BoothTabletop", (38.35, F + 0.784, z + 1.15), UP, 0.82, 0.82 / aspect(H23) * 0.66, H23, up=(1, 0, 0))
trim.cyl("black", (38.3, F + 2.4, 68.2), (38.3, CEIL, 68.2), 0.006, seg=3)
trim.cyl("lamp_warm", (38.3, F + 2.3, 68.2), (38.3, F + 2.4, 68.2), 0.14, seg=8)
light("pendant", (38.4, F + 2.1, 68.2))
goods.quad("H25_PhotoWall", (37.405, F + 2.45, 72.3), (1, 0, 0), 2.0, 2.0 * aspect(H25), H25)
# the east wall: the jukebox (where Lou is now), the pinball, the pay phone
mj = place(52.3, 69.4, -90)
Shifted(fit, mj).box(WOOD, (-0.6, F, -0.4), (0.6, F + 1.55, 0.4), bevel=0.03)
Shifted(goods, mj).quad("H16_Jukebox", (0, F + 0.78, 0.405), (0, 0, 1), 1.2, 1.2 * aspect(H16), H16)
light("vending", (51.2, F + 1.0, 69.4))
marker("jukebox", (51.6, F + 0.8, 69.4))
marker("spare_key", (51.7, F + 0.6, 69.4))
dirt.decal("L16ba_BlackOilDrips", (51.4, F, 69.6), UP, 1.3, 1.3, 30, lift=0.006)
dirt.decal("L16bc_BlackOilDrips", (50.2, F, 70.6), UP, 1.0, 1.0, 200, lift=0.007)
mp = place(52.25, 65.2, -90)
Shifted(fit, mp).box(DARK, (-0.38, F, -0.7), (0.38, F + 0.95, 0.7))
Shifted(goods, mp).quad("H19_PinballMachine", (0, F + 0.96, 0.0), UP, 0.7, 0.7 * aspect(H19_FIELD), H19_FIELD, up=(0, 0, -1))
Shifted(fit, mp).box(DARK, (-0.38, F + 0.95, -0.7), (0.38, F + 1.75, -0.55))
Shifted(goods, mp).quad("H19_PinballMachine", (0, F + 1.36, -0.545), (0, 0, 1), 0.7, 0.78, H19_GLASS)
goods.quad("H21_Payphone", (52.74, F + 1.45, 73.0), (-1, 0, 0), 0.6, 0.6 * aspect(H21), H21)
# kitchen: the way out
light("tube", (41.0, CEIL - 0.5, 78.8))
trim.box("lamp_cool", (40.4, CEIL - 0.03, 78.6), (41.6, CEIL - 0.01, 79.0))
fit.box(STEEL, (37.4, F, 77.0), (38.2, F + 0.9, 81.0))
fit.box(STEEL, (38.6, F, 80.8), (42.4, F + 0.9, 81.6))
fit.box(WHITE, (44.2, F, 76.3), (44.95, F + 1.9, 77.6), bevel=0.02)                                   # a cooler, door ajar
for i in range(5):
	alley_keg_z = 77.0 + i * 0.0
	fit.cyl(STEEL, (42.8 + (i % 3) * 0.5, F + (i // 3) * 0.6, 77.0), (42.8 + (i % 3) * 0.5, F + 0.58 + (i // 3) * 0.6, 77.0), 0.22, seg=12)
fit.box(BLUE, (43.2, F + 0.02, 81.8), (44.38, F + 2.2, 81.86), m=turn("y", 105, (43.2, 0, 81.83)))     # back door, open
for z0, a in ((40.0, -95), (48.0, 100)):
	fit.box(WOOD, (z0, F + 0.02, 76.05), (z0 + 1.18, F + 2.2, 76.1), m=turn("y", a, (z0, 0, 76.07)))
# the washrooms
trim.box("H29_TavernRestroom", (45.15, F, 76.15), (45.2, CEIL, 81.65))
trim.box("H29_TavernRestroom", (45.15, F, 81.6), (52.8, CEIL, 81.65))
trim.box("H29_TavernRestroom", (52.75, F, 76.15), (52.8, CEIL, 81.65))
for x in (46.6, 48.2):
	fit.box(GREEN, (x, F + 0.15, 79.6), (x + 0.05, F + 2.0, 81.6))
fit.box(WHITE, (45.7, F, 80.8), (46.3, F + 0.42, 81.5), bevel=0.04)
fit.box(WHITE, (47.1, F, 80.8), (47.7, F + 0.42, 81.5), bevel=0.04)
fit.box(WHITE, (50.6, F + 0.7, 81.1), (52.4, F + 0.9, 81.6), bevel=0.03)
goods.quad("glass_dark", (51.5, F + 1.7, 81.59), (0, 0, -1), 1.6, 0.9)
light("tube", (49.5, CEIL - 0.5, 78.6))
trim.box("lamp_cool", (48.9, CEIL - 0.03, 78.4), (50.1, CEIL - 0.01, 78.8))
dirt.decal("A36_PuddleMudRim", (49.4, F, 79.4), UP, 1.8, 1.8, 0, QUARTERS[2])
marker("tavern_door", (39.5, F, 61.6))
marker("tavern", (41.0, F, 64.5))
marker("bar", (44.6, F, 70.0))
marker("tavern_kitchen", (41.5, F, 79.0))
marker("restroom", (49.5, F, 78.5))

# =============================================================================
# THE COIN LAUNDRY
# =============================================================================
lw = 5.4
trim.box(DARK, (53.3, 3.55, 61.8), (53.3 + lw + 0.2, 3.75 + lw * aspect(I01) + 0.2, 61.9), bevel=0.02)
trim.quad("I01_PlasticLetterSign", (53.4 + lw / 2, 3.75 + lw * aspect(I01) / 2, 61.795), (0, 0, -1), lw, lw * aspect(I01), I01)
light("sign_warm", (55.0, 3.9, 60.8))
for z0, a in ((53.6, 95), (55.4, -80)):
	trim.box(STEEL, (z0 - 0.05, F, 62.02), (z0 + 0.05, F + 2.55, 62.38))
	s = 1 if z0 < 54.5 else -1
	m = turn("y", a, (z0, 0, 62.17))
	fit.box(STEEL, (min(z0, z0 + s * 0.88), F + 0.02, 62.15), (max(z0, z0 + s * 0.88), F + 2.45, 62.2), m=m)
	if s > 0:
		trim.decal("I02_WindowDecal", (z0 + 0.44, F + 1.5, 62.145), (0, 0, -1), 0.8, 0.8 * aspect(I02), rect=I02, lift=0.004, m=m)
trim.box(STEEL, (53.55, F + 2.5, 62.02), (55.45, F + 2.6, 62.38))
light("door", (54.5, 2.8, 61.3))
for x0, x1 in ((57.6, 61.0), (62.0, 65.4), (66.4, 68.8)):
	glass.box("glass", (x0, F + 2.3, 62.16), (x1, F + 3.5, 62.19))
	trim.box(STEEL, (x0 - 0.05, F + 2.22, 62.02), (x1 + 0.05, F + 2.3, 62.4))
# walls: white tile to shoulder height, paint above; a back room and a passage to the rear door
for a, b in (((53.15, 62.35), (53.2, 77.0)), ((69.6, 62.35), (69.65, 77.0)), ((53.15, 62.35), (69.65, 62.4))):
	trim.box("long_white_tiles", (a[0], F, a[1]), (b[0], F + 1.5, b[1]))
	trim.box(DRYWALL, (a[0], F + 1.5, a[1]), (b[0], CEIL, b[1]))
wall(shell, DRYWALL, "x", 77.0, 77.15, 53.15, 69.65, F, CEIL, [(57.0, 58.2, 0.0, 2.25), (64.0, 65.4, 0.0, 2.25)])
wall(shell, DRYWALL, "z", 61.0, 61.15, 77.15, 81.65, F, CEIL)
TUBES = [(56.5, 65.5, True), (61.0, 65.5, True), (66.0, 65.5, False), (56.5, 70.5, True), (61.0, 70.5, True), (66.0, 70.5, True),
		(56.5, 75.0, False), (61.0, 75.0, True), (66.0, 75.0, True)]
for x, z, on in TUBES:
	trim.box("lamp_cool" if on else "lamp_off", (x - 0.6, CEIL - 0.012, z - 0.3), (x + 0.6, CEIL - 0.002, z + 0.3))
	if on:
		light("tube", (x, CEIL - 0.4, z))


def washer(m, burst=False):
	"""One front-loader, 0.85 wide, door facing local +z."""
	s = Shifted(fit, m)
	g = Shifted(goods, m)
	s.box("K04_YellowedABSPlastic", (-0.42, F, -0.36), (0.42, F + 1.05, 0.36), bevel=0.02)
	g.box(STEEL, (-0.42, F + 1.05, -0.36), (0.42, F + 1.2, -0.2))
	if burst:
		g.quad("night", (0, F + 0.55, 0.365), (0, 0, 1), 0.56, 0.56)
	else:
		g.quad("I06_RoundPorthole", (0, F + 0.55, 0.365), (0, 0, 1), 0.62, 0.62, I06)
	g.quad("I14_SlideCoinSlot", (0.1, F + 1.125, -0.195), (0, 0, 1), 0.3, 0.3 * aspect(I14) * 0.5, I14)


for x, yaw_a, yaw_b in ((58.3, -90, 90), (63.3, -90, 90)):                                           # two islands, back to back
	for i in range(10):
		z = 65.9 + i * 0.95
		washer(place(x - 0.37, z, yaw_a), burst=(x < 60 and i == 6))
		washer(place(x + 0.37, z, yaw_b))
	fit.box(STEEL, (x - 0.05, F + 1.05, 65.45), (x + 0.05, F + 1.5, 74.95))
# the one that let itself out: its door on the floor, water and something darker, the marks of where it went
goods.cyl("chrome", (56.6, F + 0.01, 72.6), (56.6, F + 0.05, 72.6), 0.3, seg=14)
dirt.decal("A36_PuddleMudRim", (56.6, F, 71.9), UP, 2.4, 2.4, 0, QUARTERS[0])
dirt.decal("L16bc_BlackOilDrips", (56.2, F, 73.4), UP, 1.2, 1.2, 100, lift=0.006)
for (ax, az), (bx, bz) in zip(((56.4, 73.6), (56.0, 76.0), (57.2, 78.2), (61.6, 79.6)), ((56.0, 76.0), (57.2, 78.2), (61.6, 79.6), (64.8, 80.6))):
	dirt.decal("M23d_ApplianceDragMarks", ((ax + bx) / 2, F, (az + bz) / 2), UP, 1.0, 2.2, math.degrees(math.atan2(bx - ax, bz - az)), lift=0.005)
marker("washers", (60.8, F, 70.0))
for i in range(12):                                                                                  # dryers down the east wall, two high
	z = 64.4 + i * 0.95
	for y in (0.25, 1.3):
		fit.box("K04_YellowedABSPlastic", (68.8, F + y, z - 0.45), (69.6, F + y + 1.0, z + 0.45), bevel=0.015)
		goods.quad("I06_RoundPorthole", (68.795, F + y + 0.5, z), (-1, 0, 0), 0.7, 0.7, I06)
fit.box(STEEL, (68.8, F, 63.9), (69.6, F + 0.25, 75.9))
for z in (67.5, 71.5):                                                                               # folding tables on the west side
	fit.box(WHITE, (53.5, F + 0.86, z), (54.7, F + 0.9, z + 2.4), bevel=0.01)
	for dz in (0.1, 2.25):
		fit.box(STEEL, (53.6, F, z + dz), (53.65, F + 0.86, z + dz + 0.05))
		fit.box(STEEL, (54.55, F, z + dz), (54.6, F + 0.86, z + dz + 0.05))
goods.blob("cloth", (54.1, F + 1.0, 68.4), (0.7, 0.25, 0.5))
goods.blob("cloth", (54.0, F + 0.98, 72.6), (0.5, 0.2, 0.6), yaw=40)
for x, z, yaw, tipped in ((55.9, 69.6, 20, False), (60.8, 76.0, 70, True), (66.4, 66.0, -10, False)):   # laundry carts
	m = place(x, z, yaw) @ (turn("x", 90, (0, F + 0.3, 0.3)) if tipped else lotkit.Matrix.Identity(4))
	s = Shifted(fit, m)
	s.box("basket_blue", (-0.35, F + 0.25, -0.3), (0.35, F + 0.75, 0.3), skip="+y")
	for cx in (-0.3, 0.3):
		for cz in (-0.25, 0.25):
			s.cyl("chrome", (cx, F, cz), (cx, F + 0.25, cz), 0.015, seg=4)
goods.blob("cloth", (60.9, F + 0.15, 75.2), (0.8, 0.25, 0.6), yaw=30)
for z in (63.2, 63.9, 64.6):                                                                          # waiting chairs by the door
	fit.box("basket_blue", (53.3, F + 0.42, z), (53.8, F + 0.46, z + 0.5), bevel=0.02)
	fit.box("basket_blue", (53.25, F + 0.46, z), (53.3, F + 0.9, z + 0.5), bevel=0.02)
	for dz in (0.05, 0.45):
		fit.cyl("chrome", (53.75, F, z + dz), (53.75, F + 0.42, z + dz), 0.012, seg=4)
goods.quad("I15_ChangeMachine", (53.21, F + 1.25, 66.0), (1, 0, 0), 0.75, 0.75 * aspect(I15), I15)
fit.box(STEEL, (53.2, F + 0.6, 65.55), (53.45, F + 1.9, 66.45))
light("vending", (54.0, F + 1.6, 66.0))
goods.quad("I22_CorkBoard", (53.21, F + 1.9, 75.0), (1, 0, 0), 1.6, 1.6 * aspect(I22), I22)
goods.quad("I24_StoppedWallClock", (61.4, F + 3.3, 76.99), (0, 0, -1), 0.6, 0.6, I24)
goods.box(DARK, (68.6, F + 2.9, 76.3), (69.5, F + 3.6, 76.95), bevel=0.02)                              # a television on a bracket: snow
goods.quad("screen", (69.05, F + 3.25, 76.295), (0, 0, -1), 0.7, 0.52)
goods.quad("N12_SafetySigns", (57.6, F + 2.45, 76.99), (0, 0, -1), 0.36, 0.3, SIGN_EMPLOYEES)
goods.box(WOOD, (57.0, F + 0.02, 77.05), (58.18, F + 2.2, 77.1))                                         # the office door: shut
solid((57.0, F, 77.0), (58.2, F + 2.25, 77.15))
fit.box(BLUE, (64.5, F + 0.02, 81.8), (65.68, F + 2.2, 81.86), m=turn("y", -100, (65.68, 0, 81.83)))     # the rear door, open
fit.box(WHITE, (62.0, F, 78.0), (63.4, F + 1.6, 79.0), bevel=0.03)                                      # the boiler
trim.cyl(STEEL, (62.7, F + 1.6, 78.5), (62.7, CEIL, 78.5), 0.1, seg=8)
light("bulb", (65.4, CEIL - 0.6, 79.4))
trim.cyl("lamp_warm", (65.4, CEIL - 0.35, 79.4), (65.4, CEIL - 0.2, 79.4), 0.1, seg=8)
dirt.decal("M05a_CeilingMould", (53.21, F + 0.5, 70.0), (1, 0, 0), 1.0, 1.0, 180)
dirt.decal("Leaking005", (69.59, F + 3.2, 70.0), (-1, 0, 0), 3.0, 1.5)
marker("laundry_door", (54.5, F, 61.6))
marker("laundromat", (55.8, F, 64.2))
marker("laundry_back", (65.2, F, 79.5))

# =============================================================================
# The alley behind, the lane to the service court
# =============================================================================
for x0, x1 in ((36.5, 46.0), (46.0, 56.0), (56.0, 66.0), (66.0, 78.0)):                                  # the neighbours' board fence
	alley.box("worn_planks", (x0, 0.0, 88.0), (x1, 2.1 + random.uniform(-0.1, 0.15), 88.12))
	for x in (x0 + 0.1, (x0 + x1) / 2):
		alley.box("worn_planks", (x, 0.0, 88.12), (x + 0.1, 2.0, 88.22))
alley.box("worn_planks", (77.9, 0.0, 61.5), (78.02, 2.3, 88.1))
alley.box(BLUE, (39.0, 0.16, 84.6), (40.8, 1.2, 85.8), bevel=0.03)                                        # bins
alley.box(GREEN, (57.5, 0.16, 82.2), (59.3, 1.2, 83.4), bevel=0.03)
for x in (41.4, 42.0, 41.7):
	alley.cyl(STEEL, (x, 0.0 if x != 41.7 else 0.6, 82.5), (x, 0.58 if x != 41.7 else 1.18, 82.5), 0.22, seg=12)     # kegs by the kitchen door
for c, s, yaw in (((38.4, 0.3, 83.2), (0.7, 0.55, 0.6), 20), ((60.0, 0.25, 82.6), (0.6, 0.45, 0.55), 70), ((52.0, 0.28, 87.4), (0.6, 0.5, 0.6), 130),
		((67.5, 0.3, 87.3), (0.7, 0.5, 0.6), 0)):
	trim.blob("trash_bag", c, s, yaw=yaw)
# the hydrant in the dark part of the alley, and what it was eating
alley.cyl("tool_red", (61.0, 0.0, 86.4), (61.0, 0.62, 86.4), 0.13, seg=10)
alley.cyl("tool_red", (61.0, 0.62, 86.4), (61.0, 0.8, 86.4), 0.13, seg=10, r2=0.05)
trim.cyl("tool_red", (60.82, 0.42, 86.4), (61.18, 0.42, 86.4), 0.06, seg=8)
trim.box(DARK, (60.2, 0.0, 86.0), (60.5, 0.14, 86.3), m=turn("y", 30, (60.35, 0, 86.15)))
dirt.decal("L16ba_BlackOilDrips", (60.6, 0.012, 86.2), UP, 1.3, 1.3, 60, lift=0.006)
marker("hydrant", (61.0, 0.5, 86.4))
for x, kind in ((43.8, "yard"), (65.1, "yard")):
	trim.box(STEEL, (x - 0.15, 2.7, 82.0), (x + 0.15, 2.9, 82.16), bevel=0.01)
	trim.quad("lamp_warm", (x, 2.695, 82.08), (0, -1, 0), 0.24, 0.12, up=(0, 0, 1))
	light(kind, (x, 2.55, 82.6))
for i, (kind, dx) in enumerate((("cardboard", 0.0), ("cardboard", 0.04), ("cardboard", -0.03))):          # the lane: stacked cartons, a hand truck
	alley.box(kind, (76.6 + dx, i * 0.45, 70.0), (77.5 + dx, 0.44 + i * 0.45, 70.8), bevel=0.01)
trim.tube(STEEL, [(70.3, 0.0, 74.0), (70.25, 1.2, 74.0)], 0.02, seg=5, steps=1)
trim.box(STEEL, (70.2, 0.02, 73.8), (70.6, 0.05, 74.2))
for x, z, size, q in ((45.0, 85.0, 2.6, 0), (55.0, 86.0, 3.0, 1), (68.0, 84.5, 2.6, 2), (74.0, 76.0, 3.0, 3), (74.0, 66.0, 2.4, 1)):
	dirt.decal("A36_PuddleMudRim", (x, 0.016, z), UP, size, size, 30 * q, QUARTERS[q])
dirt.decal("Leaking002", (48.0, 3.4, 82.005), (0, 0, 1), 3.2, 1.6)
dirt.decal("Leaking007", (60.0, 3.6, 82.005), (0, 0, 1), 2.4, 0.8)
dirt.decal("Leaking005", (70.005, 3.4, 72.0), (1, 0, 0), 3.4, 1.7)
dirt.decal("M13a_WallCracks", (70.005, 1.6, 66.0), (1, 0, 0), 1.6, 1.6)
dirt.decal("M31_LitterAtlas", (43.0, 0.016, 83.4), UP, 1.0, 1.0, 20)
dirt.decal("M07a_RustStreaks", (46.5, 2.0, 82.005), (0, 0, 1), 0.4, 1.4)
marker("rear_alley", (50.0, 0.05, 85.0))
marker("lane", (74.0, 0.05, 72.0))
# the edges of what exists so far
solid((36.2, 0.0, 88.2), (78.2, 4.0, 88.5))
solid((77.95, 0.0, 61.5), (78.25, 4.0, 88.5))
solid((36.3, 0.0, 82.0), (36.6, 4.0, 88.5))          # the alley's west end: the street it opens onto is not built yet
solid((36.7, 0.0, 61.5), (37.0, 4.0, 62.0))

for part in (ground, shell, trim, glass, fit, goods, alley, dirt, col):
	part.finish()

os.makedirs(os.path.join(lotkit.ROOT, "fps", "assets", "block"), exist_ok=True)
lotkit.save(os.path.join(HERE, "brick_row.blend"), os.path.join(lotkit.ROOT, "fps", "assets", "block", "brick_row.glb"))

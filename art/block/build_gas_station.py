"""The Northline gas station on the corner, with the street in front of it and
the alley behind: the first hand-built space of the block.

  blender --background --factory-startup --python art/block/build_gas_station.py

Writes art/block/gas_station.blend and fps/assets/block/gas_station.glb.
Positions are the plan sketch's (art/block/draw_plan.py): metres, x east,
z south, y up. The rules followed are in docs/DIRECTION.md.

  What it was     a corner gas station with a small 24-hour store; one clerk on nights
  Half an hour    the power surged; the clerk pulled every plug in the back room and went
  ago             out for a smoke; the drinks machine by the door tore itself off the wall
                  and dragged itself round to the alley; the clerk did not come back in
  The player      wakes the place up from the dead patrol car under the canopy: store,
                  staff room (map, boot key), back to the boot (shotgun), out of the back
                  door to the alley (the pipe, the first thing that moves)

The model brings its own collision: every object is solid as built, unless its
name ends in "-nocol" (only seen) or "-colonly" (only bumped into).
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

random.seed(303)
lotkit.reset_scene()

for name, metres in {
		"Asphalt015": 3.0, "Concrete033": 3.0, "Concrete040": 2.5, "D10_LatexDrywall": 2.8, "D24_VCTFloorTiles": 2.0,
		"OfficeCeiling001": 2.4, "PaintedMetal012": 2.0, "PaintedMetal006": 2.0, "PaintedMetal016": 0.8, "PaintedMetal007": 2.0,
		"Metal053B": 1.5, "CorrugatedSteel003": 2.0, "bitumen": 3.0, "oak_veneer_01": 1.5, "Plastic010": 1.0, "Plastic012B": 1.0,
		"Rubber004": 1.0, "long_white_tiles": 1.2, "worn_planks": 1.6, "ChainLink": 0.25, "PaintedPlaster006": 4.0,
		"red_brick": 2.0, "WoodSiding009": 2.0, "grey_roof_01": 3.0, "grass_path_3": 3.0}.items():
	texture_set(name, metres)

flat("glass", (0.55, 0.70, 0.74), rough=0.05, alpha=0.16)
flat("glass_dark", (0.02, 0.03, 0.04), rough=0.08, alpha=0.82)
flat("lamp_cool", (0.9, 0.95, 1.0), emit=(0.85, 0.93, 1.0), strength=2.4)
flat("lamp_warm", (1.0, 0.8, 0.5), emit=(1.0, 0.72, 0.4), strength=2.2)
flat("lamp_off", (0.42, 0.43, 0.40), rough=0.5)
flat("cooler_white", (0.72, 0.74, 0.74), rough=0.5)
flat("chrome", (0.75, 0.75, 0.76), rough=0.22, metal=1.0)
flat("black", (0.02, 0.02, 0.022), rough=0.6)
flat("tyre", (0.015, 0.015, 0.016), rough=0.92)
flat("car_black", (0.012, 0.014, 0.02), rough=0.24, metal=0.3)
flat("car_white", (0.74, 0.75, 0.74), rough=0.3, metal=0.1)
flat("truck_red", (0.32, 0.07, 0.05), rough=0.45, metal=0.2)
flat("lens_red", (0.55, 0.02, 0.02), rough=0.2)
flat("lens_blue", (0.03, 0.06, 0.6), rough=0.2)
flat("paint_yellow", (0.72, 0.52, 0.05), rough=0.55)
flat("road_paint", (0.55, 0.52, 0.40), rough=0.8)
flat("paper", (0.78, 0.76, 0.68), rough=0.9)
flat("cardboard", (0.42, 0.31, 0.19), rough=0.9)
flat("cork", (0.42, 0.28, 0.16), rough=0.95)
flat("trash_bag", (0.02, 0.02, 0.022), rough=0.32)
flat("crate_blue", (0.05, 0.12, 0.42), rough=0.5)
flat("crate_red", (0.5, 0.06, 0.05), rough=0.5)
flat("display", (0.02, 0.035, 0.03), rough=0.12)
flat("screen", (0.25, 0.3, 0.3), rough=0.2, emit=(0.5, 0.62, 0.66), strength=0.9)
flat("led_red", (0.9, 0.1, 0.05), emit=(1.0, 0.12, 0.05), strength=6.0)
flat("clean_patch", (0.42, 0.41, 0.38), rough=0.85)
flat("rust_ring", (0.24, 0.10, 0.04), rough=0.9)
flat("ash_can", (0.5, 0.12, 0.1), rough=0.5, metal=0.4)
flat("night", (0.006, 0.008, 0.012), rough=1.0)
flat("bark", (0.05, 0.045, 0.04), rough=1.0)

decal_set("M27_EngineOilStains", "A39_OilDripSpots", "A40_SkidMarks", "A36_PuddleMudRim", "M28_DrinkSpills", "M31_LitterAtlas",
		"L16ba_BlackOilDrips", "L16bb_BlackOilDrips", "L16bc_BlackOilDrips", "L16bd_BlackOilDrips", "L16ab_BlackOilDrips",
		"M07a_RustStreaks", "M07b_RustStreaks", "M07c_RustStreaks", "M07d_RustStreaks", "M23d_ApplianceDragMarks",
		"M35a_Graffiti", "M37a_UtilitySprayPaintMarks", "M37b_UtilitySprayPaintMarks", "M37d_UtilitySprayPaintMarks",
		"A12c_ParkingBaysAndAccessibleBay", "A12d_ParkingBaysAndAccessibleBay", "M05a_CeilingMould", "M05c_CeilingMould",
		"M13a_WallCracks", "M13d_WallCracks", "Leaking002", "Leaking005", "Leaking007",
		"F02_DoorBadgeAndUnitNumber", "N10_DoorWindowStickers")

WHITE = "PaintedMetal012"
GREEN = "PaintedMetal006"
STEEL = "Metal053B"
DARK = "Plastic012B"
RUSTY = "PaintedMetal016"
RENDER = "PaintedPlaster006"
DRYWALL = "D10_LatexDrywall"
UP = (0, 1, 0)

F = 0.15                 # pavements and the shop floor
CEIL = F + 3.6           # dropped ceiling of the sales floor
ROOF = 4.3               # underside of the main roof slab
PARAPET = 5.1
WING = 3.5               # underside of the lower roof over the back rooms
CANOPY = 4.9             # underside of the canopy

# Pieces of the images, as an image editor shows them: (x0, y0, x1, y1), 0..1.
PYLON_BRAND = (0.301, 0.05, 0.696, 0.39)
PYLON_STORE = (0.301, 0.42, 0.696, 0.572)
PRICES = (0.095, 0.14, 0.906, 0.87)
CANOPY_STRIP = (0.021, 0.448, 0.979, 0.556)
STORE_STRIP = (0.024, 0.433, 0.976, 0.578)
PUMP_TOPS = [(0.029, 0.397, 0.473, 0.617), (0.527, 0.397, 0.971, 0.617)]
NEON = (0.104, 0.21, 0.887, 0.781)
VENDING = (0.281, 0.04, 0.719, 0.961)
CIGS = (0.057, 0.341, 0.49, 0.666)
LOTTO = (0.51, 0.341, 0.942, 0.666)
EXIT = (0.093, 0.271, 0.906, 0.738)
NOTES = [(x0, y0, x0 + 0.185, y0 + 0.2) for y0 in (0.292, 0.52) for x0 in (0.012, 0.212, 0.412, 0.61, 0.805)]
ROTA = (0.06, 0.2, 0.59, 0.57)
RIGHTS = (0.61, 0.18, 0.94, 0.59)
WASH_HANDS = (0.07, 0.6, 0.48, 0.81)
CUSTOMER_FIRST = (0.51, 0.61, 0.93, 0.81)
CALENDAR = (0.10, 0.03, 0.48, 0.48)
FLYERS = [(x0, 0.37, x0 + 0.185, 0.63) for x0 in (0.012, 0.21, 0.405, 0.6, 0.805)]
RECEIPTS = [(x0, 0.33, x0 + 0.18, 0.67) for x0 in (0.02, 0.21, 0.405, 0.6, 0.795)]
SIGNS = {"voltage": (0.02, 0.27, 0.29, 0.5), "flammable": (0.3, 0.27, 0.52, 0.5), "extinguisher": (0.53, 0.27, 0.73, 0.5),
		"no_smoking": (0.74, 0.27, 0.98, 0.5), "employees": (0.27, 0.51, 0.5, 0.73), "keep_out": (0.74, 0.51, 0.98, 0.73)}
STICKERS = {"hours": (0.52, 0.18, 0.74, 0.42), "camera": (0.02, 0.66, 0.24, 0.83), "we_id": (0.51, 0.66, 0.74, 0.82),
		"no_restroom": (0.76, 0.66, 0.98, 0.82), "push": (0.76, 0.22, 0.99, 0.4)}
BADGE = (0.028, 0.192, 0.981, 0.801)
PLATE = (0.08, 0.06, 0.49, 0.235)
GRILLE = (0.0, 0.348, 1.0, 0.651)
HEADLAMP_L = (0.0, 0.337, 0.5, 0.661)
HEADLAMP_R = (0.5, 0.337, 1.0, 0.661)
TAIL = (0.003, 0.339, 0.997, 0.66)
IT_HUMS = (0.04, 0.76, 0.97, 0.985)


def shrink(r, by=0.05):
	w, h = r[2] - r[0], r[3] - r[1]
	return (r[0] + w * by, r[1] + h * by, r[2] - w * by, r[3] - h * by)


SNACKS = [("E26_SnackPackaging", shrink(r), w, d) for r, w, d in [
		((0.027, 0.146, 0.245, 0.424), 0.19, 0.07), ((0.273, 0.144, 0.486, 0.424), 0.19, 0.07),
		((0.512, 0.144, 0.727, 0.424), 0.19, 0.07), ((0.743, 0.246, 0.977, 0.347), 0.15, 0.04),
		((0.019, 0.45, 0.276, 0.552), 0.16, 0.04), ((0.02, 0.583, 0.247, 0.858), 0.19, 0.06),
		((0.295, 0.45, 0.529, 0.553), 0.15, 0.04), ((0.549, 0.451, 0.749, 0.553), 0.13, 0.04),
		((0.274, 0.585, 0.486, 0.858), 0.19, 0.07), ((0.513, 0.585, 0.721, 0.856), 0.19, 0.07)]]
DRY = [("E27_DryGoodsPackaging", shrink(r, 0.07), w, d) for r, w, d in [
		((0.075, 0.137, 0.232, 0.363), 0.075, 0.075), ((0.305, 0.137, 0.46, 0.363), 0.075, 0.075),
		((0.53, 0.137, 0.695, 0.363), 0.075, 0.075), ((0.06, 0.395, 0.2, 0.615), 0.09, 0.09),
		((0.27, 0.395, 0.46, 0.615), 0.14, 0.11), ((0.51, 0.395, 0.70, 0.615), 0.19, 0.06),
		((0.07, 0.63, 0.225, 0.865), 0.085, 0.085), ((0.475, 0.65, 0.725, 0.855), 0.21, 0.06)]]
DRINKS = [("E28_DrinkBottlesCans", shrink((x0, y0, x1, y1), 0.06), 0.066, 0.066)
		for y0, y1 in ((0.188, 0.475), (0.521, 0.81))
		for x0, x1 in ((0.052, 0.205), (0.244, 0.395), (0.428, 0.576), (0.61, 0.761), (0.799, 0.95))]


class Shifted:
	"""A part seen from a local frame: every shape call gets the matrix."""

	def __init__(self, part, m):
		self.part = part
		self.m = m

	def __getattr__(self, name):
		call = getattr(self.part, name)
		return lambda *a, **k: call(*a, m=self.m, **k)


def place(x, z, yaw=0.0, y=0.0):
	"""Local frame at a spot on the ground, turned about the vertical."""
	return lotkit.Matrix.Translation((x, y, z)) @ lotkit.Matrix.Rotation(math.radians(yaw), 4, "Y")


def marker(name, at):
	obj = bpy.data.objects.new("marker_" + name, None)
	obj.empty_display_type = "PLAIN_AXES"
	obj.location = (at[0], -at[2], at[1])
	bpy.context.scene.collection.objects.link(obj)


def wall(part, mat, along, lo, hi, a, b, y0, y1, openings=()):
	"""A wall with holes. `along` is "x" (it runs east-west and is `lo`..`hi`
	thick in z) or "z". `a`..`b` is its run; each opening is (from, to, sill, head)
	measured from y0."""
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


ground = Part("ground")
kerbs = Part("kerb_ramps-colonly")
shell = Part("store_shell")
trim = Part("store_trim-nocol")
glass = Part("glass")
canopy = Part("canopy")
pumps = Part("pumps")
fit = Part("fittings")
goods = Part("goods-nocol")
alley = Part("alley")
car = Part("vehicles")
street = Part("street")
backdrop = Part("backdrop")
dirt = Part("dirt-nocol")
col = Part("props-colonly")


def solid(a, b, m=None):
	col.box("black", a, b, m=m)


def ramp(x0, z0, x1, z1, side):
	"""An unseen wedge along a kerb, so feet (and whatever else walks) slide up
	the 15 cm instead of stopping at it. `side` says where the road is."""
	run = 0.45
	if side in ("+x", "-x"):
		s = 1 if side == "+x" else -1
		x = x1 if s > 0 else x0
		kerbs.prism("black", [(x, 0.0), (x, F), (x + s * run, 0.0)] if s > 0 else [(x + s * run, 0.0), (x, F), (x, 0.0)], "z", z0, z1)
	else:
		s = 1 if side == "+z" else -1
		z = z1 if s > 0 else z0
		kerbs.prism("black", [(z, 0.0), (z, F), (z + s * run, 0.0)] if s > 0 else [(z + s * run, 0.0), (z, F), (z, 0.0)], "x", x0, x1)


# =============================================================================
# Ground: the corner of two streets, the lot, the alley
# =============================================================================
ground.box("Asphalt015", (-6, -0.4, -4), (62, 0.0, 66), skip="-y")
ground.box("Concrete033", (25.6, 0.0, 14.6), (42.4, 0.02, 30.4), skip="-y")              # pad under the canopy
ground.box("Concrete040", (3.0, 0.0, 6.0), (8.0, 0.015, 51.0), skip="-y")                # the alley
ground.box("Concrete040", (21.0, 0.0, 11.4), (23.7, F, 34.3), bevel=0.02, skip="-y")     # the walk in front of the store
ramp(21.0, 11.4, 23.7, 34.3, "+x")
# first leg of the street (north-south): pavements, cut where cars turn in
for z0, z1 in ((0.0, 13.0), (33.0, 51.0)):
	ground.box("Concrete040", (45.0, 0.0, z0), (47.0, F, z1), skip="-y")
	ramp(45.0, z0, 47.0, z1, "+x")
	ramp(45.0, z0, 47.0, z1, "-x")
ground.box("Concrete033", (45.0, 0.0, 13.0), (47.0, 0.03, 33.0), skip="-y")              # the driveway apron
ground.box("Concrete040", (54.0, 0.0, 0.0), (56.0, F, 51.0), skip="-y")
ramp(54.0, 0.0, 56.0, 51.0, "-x")
# the corner and the short leg west
for x0, x1 in ((27.0, 30.0), (42.0, 47.0)):
	ground.box("Concrete040", (x0, 0.0, 51.0), (x1, F, 52.5), skip="-y")
	ramp(x0, 51.0, x1, 52.5, "+z")
	ramp(x0, 51.0, x1, 52.5, "-z")
ground.box("Concrete033", (30.0, 0.0, 51.0), (42.0, 0.03, 52.5), skip="-y")
ground.box("Concrete040", (27.0, 0.0, 60.0), (56.0, F, 61.5), skip="-y")
ramp(27.0, 60.0, 56.0, 61.5, "-z")
ground.box("Concrete040", (54.0, 0.0, 51.0), (56.0, F, 52.5), skip="-y")
# road paint: a worn centre line down the first leg, a stop bar at the corner
for z in range(4, 48, 6):
	dirt.quad("road_paint", (50.5, 0.006, z + 1.5), UP, 0.12, 3.0, up=(0, 0, 1))
dirt.quad("road_paint", (52.2, 0.006, 51.6), UP, 3.2, 0.4, up=(0, 0, 1))
# parking bays against the south side of the lot
for x in (10.5, 13.3, 16.1, 18.9):
	dirt.quad("road_paint", (x, 0.006, 47.5), UP, 0.1, 5.0, up=(0, 0, 1))

# =============================================================================
# The store: a main hall with a parapet, a lower wing for the back rooms
# =============================================================================
# outer walls, 0.3 thick: x 8..21, z 12..34; the wing is z 28..34
wall(shell, RENDER, "z", 20.7, 21.0, 12.0, 28.0, F, PARAPET, [(13.5, 19.8, 0.7, 2.7), (21.0, 23.2, 0.0, 2.45), (24.4, 27.2, 0.7, 2.7)])
wall(shell, RENDER, "z", 20.7, 21.0, 28.0, 34.0, F, WING + 0.55)
wall(shell, RENDER, "z", 8.0, 8.3, 12.0, 28.0, F, PARAPET)
wall(shell, RENDER, "z", 8.0, 8.3, 28.0, 34.0, F, WING + 0.55, [(30.2, 31.4, 0.0, 2.2)])
wall(shell, RENDER, "x", 12.0, 12.3, 8.3, 20.7, F, PARAPET)
wall(shell, RENDER, "x", 33.7, 34.0, 8.3, 20.7, F, WING + 0.55)
wall(shell, RENDER, "x", 27.85, 28.15, 8.3, 20.7, WING, PARAPET)                         # the hall's south wall above the wing
# inside: the wall between the hall and the back rooms, and the one between the two rooms
wall(shell, DRYWALL, "x", 27.85, 28.0, 8.3, 20.7, F, WING, [(12.6, 13.8, 0.0, 2.2), (19.2, 20.4, 0.0, 2.2)])
wall(shell, DRYWALL, "z", 14.4, 14.55, 28.0, 33.7, F, WING, [(31.6, 32.8, 0.0, 2.2)])
# floors, ceilings, roofs
shell.box("D24_VCTFloorTiles", (8.3, 0.0, 12.3), (20.7, F, 33.7), skip="-y")
shell.box("D24_VCTFloorTiles", (20.7, 0.0, 21.0), (21.0, F, 23.2), skip="-y")              # the threshold
shell.box("OfficeCeiling001", (8.3, CEIL, 12.3), (20.7, CEIL + 0.05, 27.85), skip="+y")
shell.box(DRYWALL, (8.3, WING - 0.05, 28.0), (20.7, WING, 33.7), skip="+y")
shell.box("bitumen", (8.0, ROOF, 12.0), (21.0, ROOF + 0.25, 28.0))
shell.box("bitumen", (8.0, WING, 28.0), (21.0, WING + 0.25, 34.0))
# what gives it an outline: coping, tiled plinth, corner piers, the fascia over the walk, downpipes, roof plant
for a, b in (((7.9, PARAPET, 11.9), (21.1, PARAPET + 0.1, 12.35)), ((7.9, PARAPET, 27.8), (21.1, PARAPET + 0.1, 28.2)),
		((7.9, PARAPET, 11.9), (8.35, PARAPET + 0.1, 28.2)), ((20.65, PARAPET, 11.9), (21.1, PARAPET + 0.1, 28.2)),
		((7.9, WING + 0.55, 33.65), (21.1, WING + 0.65, 34.1)), ((7.9, WING + 0.55, 28.0), (8.35, WING + 0.65, 34.1)),
		((20.65, WING + 0.55, 28.0), (21.1, WING + 0.65, 34.1))):
	trim.box(WHITE, a, b, bevel=0.02)
for a, b in (((21.0, F, 12.0), (21.04, F + 0.72, 13.5)), ((21.0, F, 19.8), (21.04, F + 0.72, 21.0)), ((21.0, F, 23.2), (21.04, F + 0.72, 24.4)),
		((21.0, F, 27.2), (21.04, F + 0.72, 34.0)), ((7.96, 0.0, 12.0), (8.0, 0.9, 30.2)), ((7.96, 0.0, 31.4), (8.0, 0.9, 34.0)),
		((8.0, 0.0, 11.96), (21.0, 0.9, 12.0)), ((8.0, 0.0, 34.0), (21.0, 0.9, 34.04))):
	trim.box("long_white_tiles", a, b)
for x, z in ((20.95, 11.9), (20.95, 27.95), (7.85, 11.9), (7.85, 27.95)):                  # corner piers
	shell.box(RENDER, (x, 0.0, z), (x + 0.2, PARAPET, z + 0.2))
shell.box(WHITE, (21.0, 3.25, 11.6), (23.5, 4.5, 28.3), skip="-x")                         # the boxed fascia
trim.box(GREEN, (20.98, 3.28, 11.58), (23.52, 3.46, 28.32))
for z in (12.3, 20.2, 27.6):
	shell.cyl(WHITE, (23.3, F, z), (23.3, 3.25, z), 0.07, seg=8)
sign_h = 0.95
sign_w = sign_h * (STORE_STRIP[2] - STORE_STRIP[0]) / (STORE_STRIP[3] - STORE_STRIP[1])
trim.box(DARK, (23.5, 3.5, 22.1 - sign_w / 2 - 0.06), (23.58, 4.46, 22.1 + sign_w / 2 + 0.06), bevel=0.015)
trim.quad("N03_ConvenienceStoreLightbox", (23.585, 3.98, 22.1), (1, 0, 0), sign_w, sign_h, STORE_STRIP)
light("sign_blue", (24.3, 3.8, 22.1))
for z in (15.5, 22.1, 26.7):
	trim.cyl("lamp_warm", (22.3, 3.23, z), (22.3, 3.25, z), 0.1, seg=10)
light("door", (22.3, 2.9, 22.1))
light("door", (22.3, 2.9, 26.7))                                                           # over the place the machine left
for z in (12.6, 27.4):
	trim.cyl(WHITE, (21.07, 0.2, z), (21.07, PARAPET - 0.1, z), 0.05, seg=8)
for z in (16.0, 33.4):
	trim.cyl(WHITE, (7.93, 0.2, z), (7.93, (PARAPET if z < 28 else WING + 0.5) - 0.1, z), 0.05, seg=8)
shell.box(STEEL, (12.5, ROOF + 0.25, 17.0), (15.3, ROOF + 1.35, 19.4), bevel=0.03)          # the roof unit
trim.box(DARK, (12.7, ROOF + 0.5, 16.97), (15.1, ROOF + 1.2, 17.0))
trim.cyl(STEEL, (17.5, ROOF + 0.25, 24.0), (17.5, ROOF + 0.9, 24.0), 0.18, seg=10)
trim.cyl(STEEL, (17.5, ROOF + 0.9, 24.0), (17.5, ROOF + 1.0, 24.0), 0.26, seg=10)
# windows and doors: aluminium frames proud of the wall, glass you cannot walk through, leaves standing open
for z0, z1 in ((13.5, 19.8), (24.4, 27.2)):
	glass.box("glass", (20.83, F + 0.7, z0), (20.86, F + 2.7, z1))
	trim.box(STEEL, (20.66, F + 0.62, z0 - 0.06), (21.06, F + 0.7, z1 + 0.06))
	trim.box(STEEL, (20.66, F + 2.7, z0 - 0.06), (21.06, F + 2.78, z1 + 0.06))
	n = 3 if z1 - z0 > 4 else 2
	for i in range(n + 1):
		z = z0 + (z1 - z0) * i / n
		trim.box(STEEL, (20.72, F + 0.7, z - 0.035), (21.0, F + 2.7, z + 0.035))
for z, a in ((21.0, 78), (23.2, -82)):                                                      # the entrance, both leaves open inwards
	trim.box(STEEL, (20.66, F, z - 0.05), (21.06, F + 2.5, z + 0.05))
	m = turn("y", a, (20.8, 0, z))
	s = 1 if z < 22 else -1
	fit.box(STEEL, (20.78, F + 0.02, min(z, z + s * 1.08)), (20.82, F + 2.4, max(z, z + s * 1.08)), m=m)
	trim.box("glass", (20.795, F + 0.25, min(z + s * 0.08, z + s * 1.0)), (20.805, F + 2.3, max(z + s * 0.08, z + s * 1.0)), m=m)
trim.box(STEEL, (20.66, F + 2.45, 20.95), (21.06, F + 2.55, 23.25))
trim.quad("E16_TwentyFourHourNeon", (20.8, F + 1.9, 25.8), (1, 0, 0), 1.3, 0.95, NEON)
light("neon", (21.3, F + 1.9, 25.8))
m = turn("y", 104, (8.15, 0, 30.2))                                                          # the back door, propped open
fit.box("PaintedMetal007", (8.12, F + 0.02, 30.2), (8.18, F + 2.18, 31.38), m=m)
for z, a in ((12.6, -95), (19.2, 100)):                                                      # inner doors, left open
	m = turn("y", a, (z, 0, 27.92))
	fit.box("oak_veneer_01", (z, F + 0.02, 27.9), (z + 1.18, F + 2.18, 27.95), m=m)
m = turn("y", 80, (14.47, 0, 31.6))
fit.box("oak_veneer_01", (14.45, F + 0.02, 31.6), (14.5, F + 2.18, 32.78), m=m)

# =============================================================================
# The sales floor
# =============================================================================
TROFFERS = [(11.5, 15.0, True), (16.5, 15.0, True), (11.5, 19.5, False), (16.5, 19.5, True), (11.5, 24.0, True), (17.3, 23.6, True),
		(10.5, 26.4, False)]
for x, z, on in TROFFERS:
	trim.box("lamp_cool" if on else "lamp_off", (x - 0.6, CEIL - 0.012, z - 0.3), (x + 0.6, CEIL - 0.002, z + 0.3))
	if on:
		light("tube", (x, CEIL - 0.4, z))


def cooler_bank(m, doors, lit=True):
	"""Glass-door coolers along local x, doors facing local +z, 0.95 deep."""
	s = Shifted(fit, m)
	g = Shifted(goods, m)
	length = doors * 1.25
	s.box("cooler_white", (0, F, -0.95), (length, F + 2.3, -0.12))
	s.box(DARK, (0, F, -0.12), (length, F + 0.22, 0.0))
	s.box(DARK, (0, F + 2.08, -0.12), (length, F + 2.3, 0.0))
	for i in range(doors):
		x = i * 1.25
		g.box(STEEL, (x + 0.02, F + 0.22, -0.06), (x + 0.07, F + 2.08, 0.0))
		g.box("glass", (x + 0.07, F + 0.24, -0.04), (x + 1.2, F + 2.06, -0.03))
		g.cyl("chrome", (x + 1.1, F + 0.9, 0.02), (x + 1.1, F + 1.4, 0.02), 0.012, seg=6)
		for y in (0.3, 0.75, 1.2, 1.65):
			g.box(WHITE, (x + 0.08, F + y - 0.02, -0.7), (x + 1.2, F + y, -0.1))
			if (i + int(y * 10)) % 5:
				stock(g, None, x + 0.1, x + 1.18, F + y, -0.12, DRINKS, gaps=0.25, max_h=0.26)
	if lit:
		g.box("lamp_cool", (0.05, F + 2.02, -0.1), (length - 0.05, F + 2.05, -0.06))


def stock(part, m, x0, x1, y, z_front, items, gaps=0.12, max_h=0.3):
	"""A row of goods along local x on a shelf, faces towards local +z."""
	x = x0 + 0.03
	while x < x1 - 0.1:
		image, rect, w, d = random.choice(items)
		h = min(w * (rect[3] - rect[1]) / (rect[2] - rect[0]), max_h)
		for _ in range(random.randint(2, 5)):
			if x + w > x1 - 0.02:
				break
			if random.random() > gaps:
				back = random.uniform(0.0, 0.04)
				if m is None:
					part.image_box(image, (x, y, z_front - d - back), (x + w, y + h, z_front - back), "+z", rect)
				else:
					part.image_box(image, (x, y, z_front - d - back), (x + w, y + h, z_front - back), "+z", rect, m=m)
			x += w + 0.012
		x += random.uniform(0.0, 0.06)


def gondola(m, length, front, back, gaps):
	"""A shelving run along local x, 0.9 deep about local z = 0, goods on both faces."""
	s = Shifted(fit, m)
	s.box("Plastic010", (0, F, -0.45), (length, F + 0.16, 0.45))
	s.box("Plastic010", (0, F + 0.16, -0.02), (length, F + 1.5, 0.02))
	for x in (0.0, length - 0.03):
		s.box("Plastic010", (x, F + 0.16, -0.43), (x + 0.03, F + 1.5, 0.43))
	for face, items in ((1, front), (-1, back)):
		mm = m @ lotkit.Matrix.Rotation(math.radians(0 if face > 0 else 180), 4, "Y") @ lotkit.Matrix.Translation((0 if face > 0 else -length, 0, 0))
		for i, y in enumerate((F + 0.16, F + 0.5, F + 0.84, F + 1.18)):
			if i > 0:
				goods.box(WHITE, (0.03, y - 0.025, 0.02), (length - 0.03, y, 0.42), m=mm)
				goods.box("paper", (0.03, y - 0.03, 0.42), (length - 0.03, y + 0.005, 0.424), m=mm)
			stock(goods, mm, 0.04, length - 0.04, y, 0.41, items[i % len(items)], gaps=gaps + 0.06 * i)


cooler_bank(place(9.4, 13.25), 8)                                                             # along the north wall
cooler_bank(place(9.25, 21.2, 90), 6)                                                         # along the west wall
light("cooler", (14.5, F + 1.4, 14.2))
light("cooler", (10.2, F + 1.4, 17.5))
gondola(place(11.2, 15.65), 7.0, [SNACKS, SNACKS, DRY, SNACKS], [DRY, DRY, SNACKS, DRY], 0.1)
gondola(place(11.6, 18.75), 6.2, [DRY, SNACKS, SNACKS, DRY], [DRINKS, DRY, SNACKS, SNACKS], 0.16)
gondola(place(10.4, 22.05, -4), 4.2, [SNACKS, SNACKS, SNACKS, SNACKS], [DRY, SNACKS, DRY, DRY], 0.3)   # knocked askew
for i in range(9):                                                                             # some of it is on the floor
	image, rect, w, d = random.choice(SNACKS + DRY)
	x, z = random.uniform(11.0, 15.2), random.uniform(20.0, 21.3)
	h = min(w * (rect[3] - rect[1]) / (rect[2] - rect[0]), 0.3)
	goods.image_box(image, (x, F + 0.002, z), (x + w, F + 0.002 + d, z + h), "+y", rect, m=turn("y", random.uniform(0, 360), (x, F, z)))
# checkout: customers on the north side, a gap at the east end for the clerk
fit.box("oak_veneer_01", (15.2, F, 24.6), (19.2, F + 1.0, 25.4))
fit.box(WHITE, (15.14, F + 1.0, 24.52), (19.26, F + 1.05, 25.48), bevel=0.01)
fit.box("oak_veneer_01", (15.2, F, 25.4), (15.9, F + 1.0, 27.2))
fit.box(WHITE, (15.14, F + 1.0, 25.4), (15.96, F + 1.05, 27.26), bevel=0.01)
goods.box(DARK, (17.5, F + 1.05, 24.75), (18.0, F + 1.32, 25.25), bevel=0.02)                   # the till, drawer out
goods.box(DARK, (17.52, F + 1.07, 24.45), (17.98, F + 1.15, 24.78))
goods.box("display", (17.55, F + 1.32, 24.8), (17.95, F + 1.5, 24.86))
goods.box(WHITE, (16.0, F + 1.05, 24.7), (16.5, F + 1.45, 25.2), bevel=0.02)                    # the hot-dog roller, still turning nothing
for i in range(3):
	goods.cyl("crate_red", (18.5 + i * 0.16, F + 1.05, 25.1), (18.5 + i * 0.16, F + 1.22, 25.1), 0.05, seg=8)
goods.box("PaintedMetal007", (15.4, F + 1.1, 27.7), (18.8, F + 2.5, 27.85))                     # cigarettes behind the clerk
for row in range(5):
	for i in range(16):
		if random.random() > 0.2:
			goods.box(random.choice(("crate_red", "paper", "PaintedMetal006")), (15.48 + i * 0.205, F + 1.18 + row * 0.26, 27.6),
					(15.66 + i * 0.205, F + 1.4 + row * 0.26, 27.7))
goods.quad("E17_CigaretteLotteryLightbox", (16.2, F + 2.95, 27.84), (0, 0, -1), 1.1, 0.8, CIGS)
goods.quad("E17_CigaretteLotteryLightbox", (17.9, F + 2.95, 27.84), (0, 0, -1), 1.1, 0.8, LOTTO)
light("sign_warm", (17.0, F + 2.6, 27.0))
# coffee by the window, a newspaper rack, the wet-floor sign that did its job
fit.box("oak_veneer_01", (19.75, F, 13.4), (20.6, F + 0.92, 16.6))
goods.box(WHITE, (19.7, F + 0.92, 13.35), (20.62, F + 0.96, 16.65), bevel=0.01)
goods.box(DARK, (19.95, F + 0.96, 13.8), (20.45, F + 1.4, 14.25), bevel=0.02)
goods.cyl("glass_dark", (20.2, F + 1.0, 14.02), (20.2, F + 1.2, 14.02), 0.08, seg=10)
for i in range(6):
	goods.cyl("paper", (20.1 + (i % 2) * 0.12, F + 0.96, 14.9 + i * 0.11), (20.1 + (i % 2) * 0.12, F + 1.09, 14.9 + i * 0.11), 0.04, seg=8, r2=0.045)
fit.box(STEEL, (19.0, F, 23.5), (19.6, F + 1.1, 23.95))
goods.box("paper", (19.04, F + 0.5, 23.46), (19.56, F + 1.0, 23.5))
goods.prism("paint_yellow", [(14.0, F), (14.32, F), (14.18, F + 0.62), (14.14, F + 0.62)], "z", 23.6, 23.9, m=turn("y", 25, (14.15, F, 23.75)))
goods.quad("N11_ExitLightbox", (20.69, F + 2.6, 22.1), (-1, 0, 0), 0.6, 0.3, EXIT)
goods.quad("N12_SafetySigns", (13.2, F + 2.45, 27.84), (0, 0, -1), 0.36, 0.3, rect=SIGNS["employees"])
dirt.decal("M28_DrinkSpills", (13.4, F, 20.6), UP, 1.1, 1.1, 90, QUARTERS[2])

# =============================================================================
# The staff room: where somebody unplugged everything and wrote it down
# =============================================================================
light("bulb", (11.3, WING - 0.5, 30.8))
trim.cyl("lamp_warm", (11.3, WING - 0.2, 30.8), (11.3, WING - 0.06, 30.8), 0.12, seg=10)
fit.box("oak_veneer_01", (9.9, F + 0.72, 32.85), (11.9, F + 0.76, 33.65), bevel=0.006)            # desk on the south wall
fit.box(WHITE, (9.94, F, 32.9), (10.4, F + 0.72, 33.62))
fit.box(WHITE, (11.82, F, 32.9), (11.86, F + 0.72, 33.62))
goods.box("Plastic010", (10.1, F + 0.76, 33.05), (10.55, F + 1.12, 33.5), bevel=0.015)            # the security monitor: on, showing nothing
goods.quad("screen", (10.325, F + 0.95, 33.045), (0, 0, -1), 0.32, 0.26)
goods.box(DARK, (10.65, F + 0.76, 33.1), (11.0, F + 0.84, 33.4), bevel=0.006)
goods.quad("led_red", (10.7, F + 0.8, 33.095), (0, 0, -1), 0.012, 0.012)
light("cooler", (10.4, F + 1.1, 32.4))
for k, (x, z, yaw) in enumerate(((11.3, 33.1, 20), (11.55, 33.3, -12))):
	goods.quad("N13_ReceiptsTickets", (x, F + 0.764, z), UP, 0.08, 0.15, rect=RECEIPTS[3 + k],
			up=(math.sin(math.radians(yaw)), 0, math.cos(math.radians(yaw))))
mc = turn("y", 150, (10.9, F, 32.0))                                                               # the chair, shoved back
fit.box(DARK, (10.65, F + 0.42, 31.75), (11.15, F + 0.5, 32.25), bevel=0.02, m=mc)
fit.box(DARK, (10.65, F + 0.5, 31.75), (11.15, F + 0.95, 31.81), bevel=0.02, m=mc)
fit.cyl(STEEL, (10.9, F + 0.06, 32.0), (10.9, F + 0.42, 32.0), 0.03, seg=6, m=mc)
fit.cyl("black", (10.9, F, 32.0), (10.9, F + 0.06, 32.0), 0.27, seg=5, m=mc)
dirt.decal("M28_DrinkSpills", (11.6, F, 31.6), UP, 0.8, 0.8, 40, QUARTERS[2])
fit.box(WHITE, (12.4, F, 33.1), (12.95, F + 0.85, 33.66), bevel=0.012)                             # fridge, microwave on it: plugs pulled
fit.box("Plastic010", (12.4, F + 0.85, 33.15), (12.95, F + 1.13, 33.64), bevel=0.01)
goods.box("glass_dark", (12.45, F + 0.89, 33.14), (12.75, F + 1.1, 33.15))
goods.quad("N23_HandwrittenNotes", (12.6, F + 1.0, 33.135), (0, 0, -1), 0.2, 0.21, rect=NOTES[0], m=turn("z", -5, (12.6, F + 1.0, 33.135)))
goods.tube("black", [(12.95, F + 0.95, 33.5), (13.05, F + 0.5, 33.4), (13.2, F + 0.03, 33.2), (13.5, F + 0.02, 32.8)], 0.008, seg=5)
goods.tube("black", [(12.95, F + 0.3, 33.5), (13.1, F + 0.05, 33.5), (13.4, F + 0.02, 33.3), (13.8, F + 0.02, 33.35)], 0.008, seg=5)
goods.box("Plastic010", (13.3, F + 0.3, 33.685), (13.38, F + 0.42, 33.7))
goods.quad("cork", (11.0, F + 1.6, 33.69), (0, 0, -1), 1.3, 0.85)                                  # the board over the desk
for (dx, dy, yaw), note in zip(((-0.42, 0.17, 4), (-0.02, 0.2, -6), (0.4, 0.14, 3), (-0.2, -0.17, -2), (0.24, -0.2, 5)), (2, 3, 6, 7, 9)):
	goods.quad("N23_HandwrittenNotes", (11.0 + dx, F + 1.6 + dy, 33.685), (0, 0, -1), 0.26, 0.27, rect=NOTES[note],
			m=turn("z", yaw, (11.0 + dx, F + 1.6 + dy, 33.685)))
goods.quad("N16_Calendars", (9.2, F + 1.65, 33.69), (0, 0, -1), 0.36, 0.43, rect=CALENDAR)
for i in range(3):                                                                                 # lockers on the east partition
	fit.box("PaintedMetal007", (13.88, F + 0.02, 28.3 + i * 0.6), (14.4, F + 1.85, 28.88 + i * 0.6), bevel=0.008)
	goods.box("black", (13.872, F + 1.5, 28.4 + i * 0.6), (13.878, F + 1.62, 28.78 + i * 0.6))
for x in (8.5, 10.26):                                                                              # shelving on the north wall
	fit.box(WHITE, (x, F, 28.02), (x + 0.04, F + 1.9, 28.5))
for y in (0.12, 0.62, 1.12, 1.62):
	fit.box(WHITE, (8.5, F + y, 28.02), (10.3, F + y + 0.025, 28.5))
for (x, y, w, h), mat in zip(((8.58, 0.145, 0.5, 0.36), (9.15, 0.145, 0.42, 0.3), (9.65, 0.145, 0.55, 0.4), (8.6, 0.645, 0.6, 0.3),
		(9.35, 0.645, 0.36, 0.28), (8.7, 1.145, 0.45, 0.32), (9.5, 1.145, 0.7, 0.26), (8.9, 1.645, 0.5, 0.22)),
		("cardboard", "crate_red", "cardboard", "cardboard", "crate_blue", "paper", "cardboard", "cardboard")):
	goods.box(mat, (x, F + y, 28.06), (x + w, F + y + h, 28.44), bevel=0.004)
goods.box(DARK, (12.0, F + 1.3, 28.0), (12.25, F + 1.62, 28.1), bevel=0.01)                         # time clock by the door
goods.box(STEEL, (11.2, F + 1.2, 28.0), (11.7, F + 1.7, 28.03))
for i in range(5):
	for j in range(2):
		goods.quad("paper", (11.31 + j * 0.24, F + 1.27 + i * 0.09, 28.032), (0, 0, 1), 0.16, 0.07)
goods.quad("E54_RotaAndLabourPoster", (8.31, F + 1.6, 29.3), (1, 0, 0), 0.66, 0.46, rect=ROTA, m=turn("x", -2, (8.31, F + 1.6, 29.3)))
goods.quad("E54_RotaAndLabourPoster", (8.31, F + 1.65, 32.4), (1, 0, 0), 0.44, 0.54, rect=RIGHTS)
goods.quad("E54_RotaAndLabourPoster", (8.31, F + 2.45, 30.8), (1, 0, 0), 0.5, 0.26, rect=WASH_HANDS)
marker("staff_room", (11.5, F, 30.5))
marker("paper_map", (11.4, F + 0.8, 33.2))
marker("trunk_key", (13.85, F + 1.4, 28.6))

# =============================================================================
# The stock room: the way round behind the counter
# =============================================================================
light("bulb", (17.6, WING - 0.5, 30.8))
trim.cyl("lamp_warm", (17.6, WING - 0.2, 30.8), (17.6, WING - 0.06, 30.8), 0.12, seg=10)
for z0, z1 in ((33.2, 33.68),):
	for x in (15.0, 17.0, 18.96):
		fit.box(STEEL, (x, F, z0), (x + 0.04, F + 2.2, z1))
	for y in (0.15, 0.75, 1.35, 1.95):
		fit.box(STEEL, (15.0, F + y, z0), (19.0, F + y + 0.03, z1))
		x = 15.1
		while x < 18.6:
			w = random.uniform(0.35, 0.6)
			if random.random() > 0.25:
				goods.box(random.choice(("cardboard", "cardboard", "crate_blue", "crate_red")), (x, F + y + 0.03, z0 + 0.04),
						(x + w, F + y + 0.03 + random.uniform(0.22, 0.4), z1 - 0.03), bevel=0.004)
			x += w + 0.05
for i, (kind, dx) in enumerate((("crate_blue", 0.0), ("crate_red", 0.03), ("crate_blue", -0.02), ("crate_red", 0.01))):
	fit.box(kind, (19.9 + dx, F + i * 0.3, 29.0), (20.4 + dx, F + 0.29 + i * 0.3, 29.5))
fit.box(WHITE, (14.6, F, 28.2), (15.4, F + 0.5, 29.0), bevel=0.02)                                  # mop sink
goods.cyl("paint_yellow", (15.8, F, 28.6), (15.8, F + 0.32, 28.6), 0.17, seg=10, r2=0.2)
goods.cyl("worn_planks", (15.8, F + 0.1, 28.6), (15.45, F + 1.5, 28.3), 0.016, seg=6)
fit.box(STEEL, (20.55, F + 1.1, 31.0), (20.7, F + 1.9, 31.6), bevel=0.01)                           # the breaker panel
goods.quad("N12_SafetySigns", (20.545, F + 2.15, 31.3), (-1, 0, 0), 0.34, 0.3, rect=SIGNS["voltage"])
for i, yaw in enumerate((0, 5, -4)):
	fit.box("cardboard", (16.0, F + i * 0.42, 31.3), (16.7, F + 0.4 + i * 0.42, 31.9), bevel=0.006, m=turn("y", yaw, (16.35, 0, 31.6)))
marker("stock_room", (17.5, F, 30.5))

# =============================================================================
# Out front under the fascia
# =============================================================================
fit.box(WHITE, (21.05, F, 14.2), (21.8, F + 1.85, 16.4), bevel=0.025)                               # ice box
for z in (14.3, 15.35):
	goods.box(STEEL, (21.8, F + 0.35, z), (21.825, F + 1.6, z + 0.95), bevel=0.008)
fit.box("PaintedMetal007", (21.1, F + 0.12, 18.6), (21.55, F + 1.15, 19.1), bevel=0.02)              # paper box
goods.box("paper", (21.555, F + 0.63, 18.66), (21.56, F + 1.02, 19.04))
fit.cyl(DARK, (21.5, F, 24.0), (21.5, F + 0.9, 24.0), 0.26, seg=12, r2=0.29)                         # bin
goods.cyl("black", (21.5, F + 0.9, 24.0), (21.5, F + 0.97, 24.0), 0.3, seg=12, r2=0.2)
goods.box(STEEL, (21.0, F + 0.95, 19.9), (21.26, F + 1.75, 20.4))                                    # pay phone
goods.box("black", (21.05, F + 1.05, 20.0), (21.2, F + 1.6, 20.3), bevel=0.01)
for key, z, y, w, h in (("no_restroom", 20.5, 1.95, 0.3, 0.2), ("camera", 23.6, 1.9, 0.3, 0.22), ("we_id", 23.6, 1.62, 0.3, 0.2),
		("hours", 23.6, 1.25, 0.3, 0.33)):
	dirt.decal("N10_DoorWindowStickers", (21.0, F + y, z), (1, 0, 0), w, h, rect=STICKERS[key], lift=0.045)
dirt.quad("N19_CommunityFlyersYardSale", (21.045, F + 1.55, 24.05), (1, 0, 0), 0.3, 0.42, rect=FLYERS[4])   # the power company's notice
dirt.quad("N19_CommunityFlyersYardSale", (21.045, F + 1.5, 23.72), (1, 0, 0), 0.24, 0.34, rect=FLYERS[2], m=turn("x", 4, (21.045, F + 1.5, 23.72)))
dirt.decal("M31_LitterAtlas", (22.2, F, 24.6), UP, 0.9, 0.9, 200)
dirt.decal("M28_DrinkSpills", (22.3, F, 15.4), UP, 0.8, 0.8, 0, QUARTERS[0])
# the place the drinks machine stood: clean concrete, four rust feet, the cord it tore out
dirt.quad("clean_patch", (21.55, F + 0.003, 30.2), UP, 0.85, 1.05, up=(0, 0, 1))
for x in (21.2, 21.9):
	for z in (29.75, 30.65):
		dirt.cyl("rust_ring", (x, F + 0.004, z), (x, F + 0.006, z), 0.045, seg=8)
goods.box("Plastic010", (21.0, F + 0.32, 29.5), (21.015, F + 0.44, 29.62))
goods.tube("black", [(21.02, F + 0.38, 29.56), (21.1, F + 0.12, 29.4), (21.3, F + 0.02, 29.1), (21.7, F + 0.015, 28.9), (22.2, F + 0.015, 28.95)], 0.012, seg=5)
light("door", (22.0, 2.9, 30.2))
trim.box(WHITE, (21.0, 2.95, 28.3), (22.8, 3.15, 32.2))                                             # a small lean-to roof over that end
# propane cage on the south wall
fit.box(STEEL, (16.0, 0.0, 34.02), (18.0, 0.08, 34.7))
goods.box("ChainLink", (16.0, 0.08, 34.02), (18.0, 1.7, 34.7), skip="-y -z")
for x in (16.0, 17.0, 18.0):
	goods.cyl(STEEL, (x, 0.0, 34.68), (x, 1.72, 34.68), 0.02, seg=6)
for x in (16.3, 16.75, 17.3, 17.7):
	goods.cyl(WHITE, (x, 0.08, 34.35), (x, 0.5, 34.35), 0.16, seg=10)
solid((16.0, 0.0, 34.0), (18.0, 1.7, 34.7))
dirt.quad("N12_SafetySigns", (17.0, 1.95, 34.045), (0, 0, 1), 0.4, 0.34, rect=SIGNS["flammable"])

# =============================================================================
# The canopy and the pumps
# =============================================================================
canopy.box(WHITE, (27.0, CANOPY, 16.0), (41.0, CANOPY + 0.7, 29.0))
trim.box(GREEN, (26.97, CANOPY + 0.08, 15.97), (41.03, CANOPY + 0.26, 29.03))
strip_w = 0.5 * (CANOPY_STRIP[2] - CANOPY_STRIP[0]) / (CANOPY_STRIP[3] - CANOPY_STRIP[1])
for at, n in (((41.035, CANOPY + 0.42, 22.5), (1, 0, 0)), ((34.0, CANOPY + 0.42, 29.035), (0, 0, 1)), ((34.0, CANOPY + 0.42, 15.965), (0, 0, -1)),
		((26.965, CANOPY + 0.42, 22.5), (-1, 0, 0))):
	trim.quad("E03_FuelCanopyLightbox", at, n, strip_w, 0.5, CANOPY_STRIP)
light("sign_green", (42.0, CANOPY + 0.2, 22.5))
light("sign_green", (34.0, CANOPY + 0.2, 30.0))
LAMPS = [(30.0, 19.2, True), (34.0, 19.2, True), (38.0, 19.2, False), (30.0, 25.8, True), (34.0, 25.8, True), (38.0, 25.8, True)]
for x, z, on in LAMPS:
	trim.box("lamp_cool" if on else "lamp_off", (x - 0.6, CANOPY - 0.012, z - 0.3), (x + 0.6, CANOPY - 0.002, z + 0.3))
	if on:
		light("canopy", (x, CANOPY - 0.3, z))
trim.box(WHITE, (36.7, CANOPY - 0.5, 16.3), (38.7, CANOPY - 0.46, 18.3), m=turn("x", 24, (37.7, CANOPY, 16.3)))    # one soffit sheet hangs
for cx, cz in ((29.5, 19.0), (38.5, 19.0), (29.5, 26.0), (38.5, 26.0)):
	canopy.box(WHITE, (cx - 0.2, 0.18, cz - 0.2), (cx + 0.2, CANOPY, cz + 0.2))
	canopy.box("Concrete033", (cx - 0.32, 0.18, cz - 0.32), (cx + 0.32, 0.75, cz + 0.32), bevel=0.03)
	trim.box("paint_yellow", (cx - 0.325, 0.75, cz - 0.325), (cx + 0.325, 1.0, cz + 0.325))


def pump(m, top):
	s = Shifted(pumps, m)
	g = Shifted(trim, m)
	s.box(GREEN, (-0.45, 0.18, -0.28), (0.45, 1.0, 0.28), bevel=0.02)
	s.box(WHITE, (-0.45, 1.0, -0.28), (0.45, 1.6, 0.28), bevel=0.02)
	s.box(WHITE, (-0.5, 1.72, -0.3), (0.5, 2.1, 0.3), bevel=0.02)
	for side in (1, -1):
		g.quad("display", (0, 1.3, side * 0.283), (0, 0, side), 0.5, 0.36)
		g.quad("E09_PumpTopLightbox", (0, 1.91, side * 0.303), (0, 0, side), 0.84, 0.3, top)
		g.box(DARK, (0.47, 0.75, side * 0.1 - 0.05), (0.55, 1.2, side * 0.1 + 0.05), bevel=0.01)
		g.tube("Rubber004", [(0.5, 1.2, side * 0.1), (0.62, 1.5, side * 0.1), (0.7, 0.9, side * 0.16), (0.62, 0.3, side * 0.2), (0.5, 0.6, side * 0.1)], 0.018, seg=5)


for iz in (19.0, 26.0):
	pumps.box("Concrete033", (28.6, 0.0, iz - 0.6), (39.4, 0.18, iz + 0.6), bevel=0.04)
	for k, px in enumerate((31.8, 36.2)):
		pump(place(px, iz), PUMP_TOPS[(k + int(iz)) % 2])
	for x in (28.3, 39.7):                                                                          # hoop bollards at the ends
		pumps.tube("paint_yellow", [(x, 0.0, iz - 0.4), (x, 0.85, iz - 0.4), (x, 0.95, iz - 0.3), (x, 0.95, iz + 0.3), (x, 0.85, iz + 0.4), (x, 0.0, iz + 0.4)],
				0.05, seg=6, steps=1)
	pumps.box(DARK, (33.8, 0.18, iz - 0.25), (34.2, 0.95, iz + 0.25), bevel=0.02)                     # bin and squeegee
	trim.cyl(STEEL, (34.3, 0.18, iz), (34.3, 1.2, iz), 0.015, seg=5)
light("sign_green", (34.0, 2.3, 22.5))
dirt.quad("N12_SafetySigns", (29.705, 2.0, 19.0), (1, 0, 0), 0.36, 0.3, rect=SIGNS["no_smoking"])

# =============================================================================
# The patrol car, nose to the store, dead between the islands
# =============================================================================
def sedan(m, body, roof, police):
	c = Shifted(car, m)
	t = Shifted(trim, m)
	zl, zr = -0.93, 0.93

	def arch(cx, r=0.39, cy=0.36):
		return [(cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))) for a in range(0, 181, 30)]
	outline = [(-2.38, 0.40), (-2.40, 0.62), (-2.37, 0.88), (-2.22, 0.965), (-1.0, 0.99), (1.2, 0.98), (2.22, 0.89),
			(2.38, 0.80), (2.40, 0.56), (2.36, 0.38), (2.0, 0.30)] + arch(1.45) + arch(-1.45) + [(-2.0, 0.30)]
	c.prism(body, outline, "z", zl, zr)
	c.box("black", (-2.0, 0.3, zl + 0.25), (2.0, 0.8, zr - 0.25))
	c.hull("glass_dark", (-1.05, 0.985, zl + 0.05, 1.3, zr - 0.05), (-0.5, 1.44, zl + 0.2, 0.72, zr - 0.2), faces={"+y": roof, "-y": "black"})
	t.box(roof, (-0.54, 1.44, zl + 0.17), (0.76, 1.475, zr - 0.17), bevel=0.012)
	for x0, x1 in ((2.34, 2.46), (-2.46, -2.34)):
		t.box("chrome", (x0, 0.38, zl - 0.02), (x1, 0.56, zr + 0.02), bevel=0.02)
	t.quad("F16_FrontGrille", (2.405, 0.69, 0.0), (1, 0, 0), 0.94, 0.27, rect=GRILLE)
	t.quad("F17_Headlight", (2.407, 0.69, 0.65), (1, 0, 0), 0.4, 0.26, rect=HEADLAMP_L)
	t.quad("F17_Headlight", (2.407, 0.69, -0.65), (1, 0, 0), 0.4, 0.26, rect=HEADLAMP_R)
	t.quad("F18_TailLight", (-2.39, 0.77, 0.0), (-1, 0, 0), 1.72, 0.2, rect=TAIL)
	t.quad("B44_LicensePlates", (-2.465, 0.485, 0.0), (-1, 0, 0), 0.3, 0.15, rect=PLATE)
	for z in (zl - 0.06, zr + 0.06):
		t.box(body, (1.0, 1.0, z - 0.07), (1.1, 1.1, z + 0.07), bevel=0.015)
	if police:
		for z0, z1 in ((zl - 0.012, zl), (zr, zr + 0.012)):
			t.box("car_white", (-1.0, 0.44, z0), (1.12, 0.975, z1))
		t.decal("F02_DoorBadgeAndUnitNumber", (0.06, 0.72, zl - 0.012), (0, 0, -1), 0.72, 0.46, rect=BADGE, lift=0.003)
		t.decal("F02_DoorBadgeAndUnitNumber", (0.06, 0.72, zr + 0.012), (0, 0, 1), 0.72, 0.46, rect=BADGE, lift=0.003)
		t.box("black", (-0.04, 1.475, -0.57), (0.24, 1.51, 0.57))
		t.box("lens_red", (-0.06, 1.5, -0.6), (0.26, 1.62, -0.05), bevel=0.02)
		t.box("lens_blue", (-0.06, 1.5, 0.05), (0.26, 1.62, 0.6), bevel=0.02)
		for z in (zl + 0.25, zr - 0.25):
			t.box("black", (2.46, 0.34, z - 0.03), (2.52, 0.92, z + 0.03), bevel=0.01)
		t.box("black", (2.47, 0.72, zl + 0.25), (2.51, 0.78, zr - 0.25))
		t.cyl("black", (-2.05, 0.965, 0.55), (-2.1, 1.75, 0.57), 0.006, seg=4)
	for cx in (-1.45, 1.45):
		for z0, z1, s in ((zl - 0.01, zl + 0.21, -1), (zr - 0.21, zr + 0.01, 1)):
			flat_tyre = police and cx > 0 and s > 0
			cy = 0.27 if flat_tyre else 0.33
			mm = m @ lotkit.Matrix.Translation((cx, cy, 0)) @ lotkit.Matrix.Diagonal((1.0, 0.8 if flat_tyre else 1.0, 1.0, 1.0))
			trim.cyl("tyre", (0, 0, z0), (0, 0, z1), 0.33, seg=14, m=mm)
			rim = z0 - 0.004 if s < 0 else z1 + 0.004
			trim.cyl("chrome", (0, 0, rim), (0, 0, rim + s * 0.003), 0.2, seg=12, m=mm)


sedan(place(33.4, 22.5, 180), "car_black", "car_white", True)
marker("spawn", (35.6, 0.05, 24.3))
marker("police_car", (33.4, 1.0, 22.5))
marker("car_trunk", (36.0, 1.0, 22.5))
dirt.decal("M27_EngineOilStains", (33.0, 0.02, 22.4), UP, 1.5, 1.5, 20, QUARTERS[0])
dirt.decal("L16bc_BlackOilDrips", (30.6, 0.02, 22.1), UP, 1.3, 1.3, 300, lift=0.006)             # not motor oil
dirt.decal("L16bb_BlackOilDrips", (31.6, 0.02, 22.3), UP, 0.7, 0.7, 40, lift=0.007)
for (x, z, size, yaw), q in zip(((36.0, 17.3, 1.7, 100), (31.0, 27.6, 1.4, 200), (38.0, 27.4, 1.6, 310)), (1, 2, 3)):
	dirt.decal("M27_EngineOilStains", (x, 0.02, z), UP, size, size, yaw, QUARTERS[q])
for (x, z, yaw), q in zip(((33.2, 17.0, 0), (36.9, 27.9, 90), (29.4, 22.3, 180)), (0, 1, 2)):
	dirt.decal("A39_OilDripSpots", (x, 0.02, z), UP, 0.9, 0.9, yaw, QUARTERS[q], lift=0.005)
dirt.decal("A40_SkidMarks", (44.0, 0.03, 23.0), UP, 2.4, 5.0, 96)
for (cx, cz), (nx, nz), mat in zip(((29.5, 19), (38.5, 19), (29.5, 26), (38.5, 26)), ((0, -1), (1, 0), (-1, 0), (0, 1)),
		("M07a_RustStreaks", "M07b_RustStreaks", "M07d_RustStreaks", "M07c_RustStreaks")):
	dirt.decal(mat, (cx + nx * 0.2, 3.9, cz + nz * 0.2), (nx, 0, nz), 0.36, 1.5)

# the pickup that stalled in the street: what stops the eye half way up the first leg
sedan(place(50.4, 33.6, 62), "truck_red", "truck_red", False)
dirt.decal("L16ba_BlackOilDrips", (50.8, 0.0, 34.6), UP, 1.4, 1.4, 15, lift=0.006)

# =============================================================================
# The pylon on the corner: the thing you steer by
# =============================================================================
mp = place(43.0, 48.5, 45)
py = Shifted(street, mp)
pt = Shifted(trim, mp)
py.box(WHITE, (-0.18, 0.0, -0.18), (0.18, 9.4, 0.18))
py.box("Concrete033", (-0.5, 0.0, -0.5), (0.5, 0.5, 0.5), bevel=0.04)
bw = 3.3
bh = bw * (PYLON_BRAND[3] - PYLON_BRAND[1]) / (PYLON_BRAND[2] - PYLON_BRAND[0])
sh = bw * (PYLON_STORE[3] - PYLON_STORE[1]) / (PYLON_STORE[2] - PYLON_STORE[0])
ph = 2.4 * (PRICES[3] - PRICES[1]) / (PRICES[2] - PRICES[0])
pt.box(DARK, (-bw / 2 - 0.08, 9.4 - bh - sh - 0.25, -0.2), (bw / 2 + 0.08, 9.5, 0.2), bevel=0.03)
pt.box(DARK, (-1.28, 4.9 - 0.08, -0.16), (1.28, 4.9 + ph + 0.08, 0.16), bevel=0.03)
for s in (1, -1):
	pt.quad("E15_MainPylonSign", (0, 9.4 - bh / 2, s * 0.205), (0, 0, s), bw, bh, PYLON_BRAND)
	pt.quad("E15_MainPylonSign", (0, 9.4 - bh - 0.12 - sh / 2, s * 0.205), (0, 0, s), bw, sh, PYLON_STORE)
	pt.quad("E14_PricePylon", (0, 4.9 + ph / 2, s * 0.165), (0, 0, s), 2.4, ph, PRICES)
light("sign_green", (44.4, 8.2, 49.9))
light("sign_green", (41.6, 8.2, 47.1))
light("sign_warm", (44.0, 5.6, 49.5))
dirt.decal("M37a_UtilitySprayPaintMarks", (44.2, 0.0, 44.0), UP, 0.8, 0.8, 60)
dirt.decal("M37d_UtilitySprayPaintMarks", (41.0, 0.0, 50.2), UP, 0.6, 0.6, 5)

# =============================================================================
# The alley behind the store: fence, bins, the machine that walked, the smoking spot
# =============================================================================
def fence(ax, az, bx, bz, y1=2.2):
	length = math.hypot(bx - ax, bz - az)
	n = max(1, round(length / 2.5))
	for i in range(n + 1):
		t = i / n
		trim.cyl(STEEL, (ax + (bx - ax) * t, 0.0, az + (bz - az) * t), (ax + (bx - ax) * t, y1 + 0.06, az + (bz - az) * t), 0.03, seg=6)
	trim.cyl(STEEL, (ax, y1, az), (bx, y1, bz), 0.02, seg=6)
	corners = ((ax, 0.04, az), (bx, 0.04, bz), (bx, y1, bz), (ax, y1, az))
	trim._face([trim._v(q) for q in corners], "ChainLink")
	trim._face([trim._v(q) for q in reversed(corners)], "ChainLink")              # seen from both sides
	if abs(bx - ax) > abs(bz - az):
		solid((min(ax, bx), 0.0, az - 0.04), (max(ax, bx), y1, az + 0.04))
	else:
		solid((ax - 0.04, 0.0, min(az, bz)), (ax + 0.04, y1, max(az, bz)))


fence(3.0, 6.0, 3.0, 51.0)                    # west: the woods
fence(3.0, 6.0, 45.0, 6.0)                    # north
fence(3.0, 51.0, 27.0, 51.0)                  # south, as far as the driveway
fence(45.0, 6.0, 45.0, 0.0)
for i in range(16):                           # trunks beyond the wire, so the dark has depth
	x = random.uniform(-4.5, 1.8)
	z = random.uniform(2.0, 60.0)
	trim.cyl("bark", (x, 0.0, z), (x + random.uniform(-0.3, 0.3), 9.0, z), random.uniform(0.14, 0.3), seg=6)
for i in range(10):
	x = random.uniform(6.0, 43.0)
	trim.cyl("bark", (x, 0.0, random.uniform(0.5, 4.2)), (x, 9.0, random.uniform(0.5, 4.2)), random.uniform(0.14, 0.3), seg=6)


def dumpster(m, lid_open):
	s = Shifted(alley, m)
	s.prism("PaintedMetal007", [(-0.6, 0.16), (0.55, 0.16), (0.55, 1.27), (-0.62, 1.02)], "x", 0.03, 1.77)
	s.box("PaintedMetal007", (0.0, 0.98, -0.66), (1.8, 1.04, -0.6))
	slope = math.degrees(math.atan2(1.27 - 1.02, 1.17))
	for k, xa in enumerate((0.04, 0.91)):
		mm = m @ turn("x", 105 if (lid_open and k == 1) else -slope, (0, 1.275, 0.55))
		trim.box(DARK, (xa, 1.275, -0.64), (xa + 0.85, 1.3, 0.55), m=mm)
	for x in (0.2, 1.6):
		for z in (-0.4, 0.4):
			Shifted(trim, m).cyl("black", (x - 0.03, 0.1, z), (x + 0.03, 0.1, z), 0.07, seg=8)


dumpster(place(4.6, 8.4, -90), False)
dumpster(place(4.5, 11.0, -84), True)
for c, s, yaw in (((4.2, 0.3, 13.3), (0.7, 0.55, 0.6), 20), ((5.0, 0.25, 13.8), (0.6, 0.45, 0.55), 70), ((4.5, 0.62, 13.5), (0.55, 0.45, 0.5), 130),
		((6.6, 0.27, 9.0), (0.6, 0.5, 0.6), 10), ((4.4, 1.25, 10.3), (0.7, 0.4, 0.6), 40)):
	trim.blob("trash_bag", c, s, yaw=yaw)
for x, z, length, yaw in ((5.6, 14.6, 1.9, 98), (6.1, 14.9, 1.5, 76), (5.2, 15.4, 2.1, 120), (6.5, 15.8, 1.2, 165)):      # scrap pipe
	trim.cyl(RUSTY, (x, 0.04, z), (x + length, 0.04, z), 0.024, seg=6, m=turn("y", yaw, (x, 0, z)))
trim.cyl(RUSTY, (5.25, 0.02, 12.2), (5.35, 1.3, 12.1), 0.024, seg=6)
marker("pipe", (5.9, 0.3, 15.0))
alley.cyl(DARK, (6.9, 0.0, 16.6), (6.9, 0.88, 16.6), 0.29, seg=14)                                    # waste-oil drum
trim.cyl("black", (6.9, 0.88, 16.6), (6.9, 0.9, 16.6), 0.3, seg=14)
dirt.decal("L16bd_BlackOilDrips", (6.5, 0.015, 17.3), UP, 1.2, 1.2, 200)
# a yard lamp on a pole in the fence corner, a wall lamp half way down, a lamp over the back door
trim.cyl(STEEL, (3.25, 0.0, 6.25), (3.25, 3.6, 6.25), 0.045, seg=8)
trim.tube(STEEL, [(3.25, 3.6, 6.25), (3.4, 3.72, 6.4), (3.9, 3.65, 6.9)], 0.03, seg=6, steps=3)
trim.box(STEEL, (3.8, 3.5, 6.8), (4.1, 3.62, 7.1), bevel=0.02)
trim.quad("lamp_warm", (3.95, 3.495, 6.95), (0, -1, 0), 0.2, 0.2, up=(0, 0, 1))
light("yard", (4.3, 3.2, 7.4))
for z, kind in ((20.0, "wallpack"), (40.0, "yard")):
	trim.box(STEEL, (7.84, 3.0, z - 0.15), (8.0, 3.2, z + 0.15), bevel=0.01)
	trim.quad("lamp_warm", (7.91, 2.995, z), (0, -1, 0), 0.12, 0.24, up=(0, 0, 1))
	light(kind, (7.4, 2.85, z))
trim.box(STEEL, (7.86, 2.5, 30.65), (8.0, 2.68, 30.95), bevel=0.01)
trim.quad("lamp_warm", (7.93, 2.495, 30.8), (0, -1, 0), 0.1, 0.22, up=(0, 0, 1))
light("door", (7.5, 2.4, 30.8))
# the machine: it came round the south end of the store and stopped here, facing the wall lamp
mv = place(7.3, 23.2, -90)
Shifted(alley, mv).box(DARK, (-0.5, 0.0, -0.4), (0.5, 2.0, 0.4), bevel=0.02)
Shifted(trim, mv).quad("E21_VendingMachine", (0, 1.02, 0.405), (0, 0, 1), 0.93, 1.94, VENDING)
light("vending", (6.3, 1.3, 23.2))
marker("first_creature", (6.4, 1.0, 23.2))
dirt.decal("L16ba_BlackOilDrips", (6.5, 0.015, 23.2), UP, 1.25, 1.25, 15, lift=0.007)
dirt.decal("M35a_Graffiti", (7.995, 1.5, 25.6), (-1, 0, 0), 1.55, 0.37, rect=IT_HUMS)                # "IT HUMS", with an arrow at it


def trail(mat, points, w, h, y, rect=None):
	for (ax, az), (bx, bz) in zip(points, points[1:]):
		dirt.decal(mat, ((ax + bx) / 2, y, (az + bz) / 2), UP, w, h, turn_deg=math.degrees(math.atan2(bx - ax, bz - az)), rect=rect)


route = [(21.6, 30.6), (22.4, 33.2), (21.4, 35.6), (18.6, 36.4), (15.0, 36.6), (11.4, 36.4), (8.6, 35.6), (6.6, 33.6), (5.8, 31.0),
		(5.6, 28.2), (5.8, 25.6), (6.4, 23.9)]
trail("M23d_ApplianceDragMarks", route[:2], 1.2, 2.2, F)
trail("M23d_ApplianceDragMarks", route[2:], 1.2, 2.4, 0.015)
for x, z, yaw in ((19.8, 36.2, 10), (12.6, 36.6, 80), (7.4, 34.6, 200), (5.6, 29.4, 260)):
	dirt.decal("L16bc_BlackOilDrips", (x, 0.015, z), UP, 1.1, 1.1, yaw, lift=0.007)
# the clerk's smoking spot, outside the back door
alley.box("crate_blue", (6.9, 0.0, 31.9), (7.26, 0.3, 32.26), m=turn("y", -12, (7.08, 0, 32.08)))
trim.cyl("ash_can", (6.6, 0.0, 32.5), (6.6, 0.17, 32.5), 0.08, seg=10)
trim.cyl("paper", (7.2, 0.0, 32.6), (7.2, 0.11, 32.6), 0.04, seg=8, r2=0.045)
trim.cyl("paper", (7.0, 0.305, 32.0), (7.09, 0.31, 32.05), 0.004, seg=4)
dirt.decal("M31_LitterAtlas", (6.8, 0.015, 32.5), UP, 0.9, 0.9, 30)
alley.box(WHITE, (6.7, 0.0, 26.6), (7.9, 0.95, 27.5), bevel=0.02)                                    # condenser
trim.cyl("black", (7.3, 0.95, 27.05), (7.3, 0.97, 27.05), 0.33, seg=12)
for i, (kind, dx) in enumerate((("crate_blue", 0.0), ("crate_red", 0.02), ("crate_blue", -0.02))):
	alley.box(kind, (7.5 + dx, i * 0.3, 28.6), (7.9 + dx, 0.29 + i * 0.3, 29.0))
for i, yaw in enumerate((0, 4, -3, 7)):                                                              # pallets
	mm = turn("y", yaw, (4.6, 0, 44.5)) @ lotkit.Matrix.Translation((4.0, i * 0.14, 44.0))
	for dz in (0.0, 0.45, 0.9):
		trim.box("worn_planks", (0, 0.02, dz), (1.2, 0.11, dz + 0.1), m=mm)
	for k in range(5):
		trim.box("worn_planks", (k * 0.275, 0.11, 0), (k * 0.275 + 0.1, 0.13, 1.0), m=mm)
solid((3.95, 0.0, 43.95), (5.25, 0.56, 45.05))
# walls weather: leaks under the coping, cracks, mould at the foot
dirt.decal("Leaking002", (7.995, 4.2, 18.0), (-1, 0, 0), 3.2, 1.6)
dirt.decal("Leaking005", (7.995, 4.1, 24.5), (-1, 0, 0), 3.0, 1.5)
dirt.decal("Leaking007", (14.0, 4.3, 11.995), (0, 0, -1), 2.4, 0.8)
dirt.decal("Leaking002", (14.5, 2.9, 34.045), (0, 0, 1), 3.2, 1.6)
dirt.decal("M13a_WallCracks", (7.995, 1.7, 21.0), (-1, 0, 0), 1.5, 1.5)
dirt.decal("M13d_WallCracks", (12.0, 1.4, 34.045), (0, 0, 1), 1.2, 1.2)
dirt.decal("M05a_CeilingMould", (7.955, 0.5, 19.0), (-1, 0, 0), 1.0, 1.0, 180)
dirt.decal("M05c_CeilingMould", (7.955, 0.45, 37.0), (-1, 0, 0), 0.9, 0.9, 180)
for (x, z, size, yaw), q in zip(((12.0, 41.0, 2.6, 0), (25.2, 37.6, 3.2, 70), (36.0, 44.0, 3.0, 160), (5.4, 38.0, 2.2, 30), (40.0, 9.5, 3.0, 250)),
		(0, 1, 2, 3, 1)):
	dirt.decal("A36_PuddleMudRim", (x, 0.004 if x > 8 else 0.018, z), UP, size, size, yaw, QUARTERS[q])
marker("alley", (5.6, 0.05, 20.0))
marker("corner", (44.0, 0.2, 51.8))

# =============================================================================
# The street: lamp posts, and stand-ins for what is across the road and round the corner
# =============================================================================
for (x, z), side in (((46.0, 9.0), 1), ((55.0, 26.0), -1), ((46.0, 43.0), 1), ((40.0, 51.7), 0)):
	street.cyl("worn_planks", (x, 0.0, z), (x, 9.0, z), 0.14, seg=8)
	trim.box("worn_planks", (x - 1.1, 8.4, z - 0.06), (x + 1.1, 8.52, z + 0.06))
	hx, hz = (x + side * 2.2, z) if side else (x, z + 2.2)
	trim.tube(STEEL, [(x, 7.2, z), ((x + hx) / 2, 7.5, (z + hz) / 2), (hx, 7.3, hz)], 0.035, seg=6, steps=3)
	trim.box(STEEL, (hx - 0.3, 7.16, hz - 0.14), (hx + 0.3, 7.3, hz + 0.14), bevel=0.03)
	trim.quad("lamp_warm", (hx, 7.155, hz), (0, -1, 0), 0.4, 0.2, up=(0, 0, 1))
	light("street", (hx, 7.0, hz))
for a, b in (((46.0, 8.45, 9.0), (55.0, 8.45, 26.0)), ((55.0, 8.45, 26.0), (46.0, 8.45, 43.0)), ((46.0, 8.45, 43.0), (40.0, 8.45, 51.7))):
	mid = ((a[0] + b[0]) / 2, 7.7, (a[2] + b[2]) / 2)
	for dx in (-0.9, 0.9):
		trim.tube("black", [(a[0] + dx, a[1], a[2]), (mid[0] + dx, mid[1], mid[2]), (b[0] + dx, b[1], b[2])], 0.012, seg=4, steps=5)
# Not built yet: the brick row facing up the street. A dark mass for now, so the corner has a
# side; it gets its own script later. (Miller's, across the street, is build_miller.py.)
backdrop.box("red_brick", (37.0, 0.0, 62.0), (70.0, 7.8, 82.0))
backdrop.box("red_brick", (36.8, 7.8, 61.8), (70.2, 8.2, 82.2))
for x in (39.0, 47.0, 55.0, 63.0):
	trim.box("night", (x, 0.8, 61.97), (x + 5.0, 3.0, 62.0))
	trim.box("night", (x + 0.5, 4.6, 61.97), (x + 4.5, 6.6, 62.0))
# the edges of what exists so far
solid((44.0, 0.0, 1.5), (57.0, 4.0, 1.8))            # north: the fog
solid((24.0, 0.0, 61.5), (57.0, 4.0, 61.8))          # south
solid((26.2, 0.0, 51.0), (26.5, 4.0, 62.0))          # west end of the short leg
solid((2.7, 0.0, 5.7), (3.0, 4.0, 51.3))
solid((2.7, 0.0, 5.7), (45.3, 4.0, 6.0))

for part in (ground, kerbs, shell, trim, glass, canopy, pumps, fit, goods, alley, car, street, backdrop, dirt, col):
	part.finish()

os.makedirs(os.path.join(lotkit.ROOT, "fps", "assets", "block"), exist_ok=True)
lotkit.save(os.path.join(HERE, "gas_station.blend"), os.path.join(lotkit.ROOT, "fps", "assets", "block", "gas_station.glb"))

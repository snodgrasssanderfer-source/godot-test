"""Northline gas station and Night Shift Food Mart, modelled for real.

  blender --background --factory-startup --python art/town/blender/build_gas_station.py

Writes art/town/blender/gas_station.blend (open it, change it by hand, export
again) and fps/assets/town/gas_station.glb (what the game shows in place of
the greybox boxes of this lot).

The footprint is the greybox's, number for number (town_layout.gd,
_gas_station): the game keeps the greybox boxes as the collision, so walls,
doors, counters and shelves here must stay where they are there. New solid
things (bollards, the ice box, bins) get their own simple colliders in the
"props-colonly" object.
"""
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lotkit  # noqa: E402
from lotkit import QUARTERS, Part, decal_set, flat, frame, light, texture_set, turn  # noqa: E402

random.seed(1996)
lotkit.reset_scene()

G = 0.03                 # forecourt level
F = 0.17                 # shop floor
TOP = F + 3.4            # underside of the roof slab
CEIL = F + 2.95          # dropped ceiling
PARAPET = 4.1

for name, metres in {
		"Asphalt015": 3.0, "Concrete033": 3.0, "Concrete040": 2.5,
		"D10_LatexDrywall": 2.8, "D24_VCTFloorTiles": 2.0, "OfficeCeiling001": 2.4, "PaintedMetal012": 2.0,
		"PaintedMetal006": 2.0, "PaintedMetal016": 0.8, "PaintedMetal007": 2.0, "Metal053B": 1.5,
		"CorrugatedSteel003": 2.0, "bitumen": 3.0, "oak_veneer_01": 1.5, "Plastic010": 1.0, "Plastic012B": 1.0,
		"Rubber004": 1.0, "long_white_tiles": 1.2, "worn_planks": 1.6, "ChainLink": 0.25, "PaintedPlaster006": 4.0}.items():
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
flat("lens_red", (0.55, 0.02, 0.02), rough=0.2)
flat("lens_blue", (0.03, 0.06, 0.6), rough=0.2)
flat("headlight", (0.8, 0.8, 0.76), rough=0.12, metal=0.4)
flat("paint_yellow", (0.72, 0.52, 0.05), rough=0.55)
flat("road_paint", (0.55, 0.52, 0.40), rough=0.8)
flat("paper", (0.78, 0.76, 0.68), rough=0.9)
flat("cardboard", (0.42, 0.31, 0.19), rough=0.9)
flat("cork", (0.42, 0.28, 0.16), rough=0.95)
flat("trash_bag", (0.02, 0.02, 0.022), rough=0.32)
flat("crate_blue", (0.05, 0.12, 0.42), rough=0.5)
flat("crate_red", (0.5, 0.06, 0.05), rough=0.5)
flat("pack_red", (0.55, 0.06, 0.05), rough=0.5)
flat("pack_white", (0.8, 0.8, 0.76), rough=0.5)
flat("pack_green", (0.08, 0.32, 0.16), rough=0.5)
flat("display", (0.02, 0.035, 0.03), rough=0.12)
flat("screen", (0.25, 0.3, 0.3), rough=0.2, emit=(0.5, 0.62, 0.66), strength=0.9)
flat("led_red", (0.9, 0.1, 0.05), emit=(1.0, 0.12, 0.05), strength=6.0)
flat("coffee", (0.07, 0.03, 0.01), rough=0.1, alpha=0.9)
flat("clean_patch", (0.42, 0.41, 0.38), rough=0.85)
flat("rust_ring", (0.24, 0.10, 0.04), rough=0.9)
flat("ash_can", (0.5, 0.12, 0.1), rough=0.5, metal=0.4)

# Stains, cracks and painted marks: see-through panels a few millimetres off the surface.
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
BLOCK = "PaintedPlaster006"            # painted render, flaking; the library's "block wall" is rough stone
DRYWALL = "D10_LatexDrywall"

col = Part("props-colonly")          # colliders for the solid things the greybox does not have


def solid(a, b, m=None):
	col.box("black", a, b, m=m)


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
COUNTER_FRONT = (0.012, 0.19, 0.494, 0.374)


def shrink(r, by=0.05):
	w = (r[2] - r[0]) * by
	h = (r[3] - r[1]) * by
	return (r[0] + w, r[1] + h, r[2] - w, r[3] - h)


# The printed sheets, cell by cell.
NOTES = [(x0, y0, x0 + 0.185, y0 + 0.2) for y0 in (0.292, 0.52) for x0 in (0.012, 0.212, 0.412, 0.61, 0.805)]
ROTA = (0.06, 0.2, 0.59, 0.57)
RIGHTS = (0.61, 0.18, 0.94, 0.59)
WASH_HANDS = (0.07, 0.6, 0.48, 0.81)
CUSTOMER_FIRST = (0.51, 0.61, 0.93, 0.81)
CALENDAR = (0.10, 0.03, 0.48, 0.48)
FLYERS = [(x0, 0.37, x0 + 0.185, 0.63) for x0 in (0.012, 0.21, 0.405, 0.6, 0.805)]       # bake sale, yard sale, football, bingo, outage
RECEIPTS = [(x0, 0.33, x0 + 0.18, 0.67) for x0 in (0.02, 0.21, 0.405, 0.6, 0.795)]
SIGNS = {"voltage": (0.02, 0.27, 0.29, 0.5), "flammable": (0.3, 0.27, 0.52, 0.5), "extinguisher": (0.53, 0.27, 0.73, 0.5),
		"no_smoking": (0.74, 0.27, 0.98, 0.5), "wet_floor": (0.02, 0.51, 0.26, 0.73), "employees": (0.27, 0.51, 0.5, 0.73),
		"keep_out": (0.74, 0.51, 0.98, 0.73)}
STICKERS = {"open": (0.02, 0.2, 0.25, 0.42), "hours": (0.52, 0.18, 0.74, 0.42), "push": (0.76, 0.22, 0.99, 0.4),
		"pull": (0.03, 0.45, 0.25, 0.62), "no_smoking": (0.78, 0.45, 0.98, 0.63), "camera": (0.02, 0.66, 0.24, 0.83),
		"no_shirt": (0.26, 0.66, 0.49, 0.82), "we_id": (0.51, 0.66, 0.74, 0.82), "no_restroom": (0.76, 0.66, 0.98, 0.82)}
BADGE = (0.028, 0.192, 0.981, 0.801)
PLATE = (0.08, 0.06, 0.49, 0.235)
GRILLE = (0.0, 0.348, 1.0, 0.651)
HEADLAMP_L = (0.0, 0.337, 0.5, 0.661)
HEADLAMP_R = (0.5, 0.337, 1.0, 0.661)
TAIL = (0.003, 0.339, 0.997, 0.66)
IT_HUMS = (0.04, 0.76, 0.97, 0.985)

# Goods: (image, rectangle, width in metres, depth). The height follows from the picture.
SNACKS = [("E26_SnackPackaging", shrink(r), w, d) for r, w, d in [
		((0.027, 0.146, 0.245, 0.424), 0.19, 0.07), ((0.273, 0.144, 0.486, 0.424), 0.19, 0.07),
		((0.512, 0.144, 0.727, 0.424), 0.19, 0.07), ((0.743, 0.246, 0.977, 0.347), 0.15, 0.04),
		((0.019, 0.45, 0.276, 0.552), 0.16, 0.04), ((0.02, 0.583, 0.247, 0.858), 0.19, 0.06),
		((0.295, 0.45, 0.529, 0.553), 0.15, 0.04), ((0.549, 0.451, 0.749, 0.553), 0.13, 0.04),
		((0.77, 0.45, 0.978, 0.554), 0.13, 0.04), ((0.274, 0.585, 0.486, 0.858), 0.19, 0.07),
		((0.513, 0.585, 0.721, 0.856), 0.19, 0.07), ((0.748, 0.609, 0.978, 0.835), 0.17, 0.05)]]
DRY = [("E27_DryGoodsPackaging", shrink(r, 0.07), w, d) for r, w, d in [
		((0.075, 0.137, 0.232, 0.363), 0.075, 0.075), ((0.305, 0.137, 0.46, 0.363), 0.075, 0.075),
		((0.53, 0.137, 0.695, 0.363), 0.075, 0.075), ((0.76, 0.137, 0.925, 0.363), 0.075, 0.075),
		((0.06, 0.395, 0.2, 0.615), 0.09, 0.09), ((0.27, 0.395, 0.46, 0.615), 0.14, 0.11),
		((0.51, 0.395, 0.70, 0.615), 0.19, 0.06), ((0.745, 0.395, 0.94, 0.615), 0.19, 0.06),
		((0.07, 0.63, 0.225, 0.865), 0.085, 0.085), ((0.275, 0.66, 0.425, 0.865), 0.085, 0.085),
		((0.475, 0.65, 0.725, 0.855), 0.21, 0.06), ((0.77, 0.63, 0.94, 0.865), 0.14, 0.05)]]
DRINKS = [("E28_DrinkBottlesCans", shrink((x0, y0, x1, y1), 0.06), 0.066, 0.066)
		for y0, y1 in ((0.188, 0.475), (0.521, 0.81))
		for x0, x1 in ((0.052, 0.205), (0.244, 0.395), (0.428, 0.576), (0.61, 0.761), (0.799, 0.95))]


def stock(part, m, x0, x1, y, z_front, goods, gaps=0.12, max_h=0.3):
	"""A row of goods along local x on a shelf, faces towards local +z."""
	x = x0 + 0.03
	while x < x1 - 0.1:
		image, rect, w, d = random.choice(goods)
		h = min(w * (rect[3] - rect[1]) / (rect[2] - rect[0]), max_h)
		for _ in range(random.randint(2, 5)):
			if x + w > x1 - 0.02:
				break
			if random.random() > gaps:
				back = random.uniform(0.0, 0.04)
				part.image_box(image, (x, y, z_front - d - back), (x + w, y + h, z_front - back), "+z", rect, m=m)
			x += w + 0.012
		x += random.uniform(0.0, 0.06)


# =============================================================================
# Ground
# =============================================================================

ground = Part("ground-nocol")
ground.box("Asphalt015", (0, -0.3, -2.3), (46, G, 40), skip="-y")
ground.box("Concrete033", (9.0, G, 5.0), (23.0, G + 0.015, 16.0), skip="-y")          # pad under the canopy
ground.box("Concrete040", (13, G, 18.6), (33, F, 20.0), skip="-y +z")                 # walk in front of the store
ground.box("Concrete033", (14.0, G, 35.9), (20.2, G + 0.012, 38.0), skip="-y")        # bin pad
for x in (36.0, 38.7, 41.4, 44.1):                                                    # parking bays south of the store
	ground.quad("road_paint", (x, G + 0.006, 23.5), (0, 1, 0), 0.1, 5.0, up=(0, 0, 1))

# =============================================================================
# Canopy, columns
# =============================================================================

canopy = Part("canopy-nocol")
canopy.box(WHITE, (8.5, 4.55, 5.5), (23.5, 5.45, 15.5), faces={"-y": "CorrugatedSteel003"})
for a, b in (((8.48, 4.60, 5.48), (23.52, 4.78, 5.50)), ((8.48, 4.60, 15.50), (23.52, 4.78, 15.52)),
		((8.48, 4.60, 5.5), (8.50, 4.78, 15.5)), ((23.50, 4.60, 5.5), (23.52, 4.78, 15.5))):
	canopy.box(GREEN, a, b)
strip_h = 0.55
strip_w = strip_h * (CANOPY_STRIP[2] - CANOPY_STRIP[0]) / (CANOPY_STRIP[3] - CANOPY_STRIP[1])
canopy.quad("E03_FuelCanopyLightbox", (16.0, 5.11, 5.485), (0, 0, -1), strip_w, strip_h, CANOPY_STRIP)
canopy.quad("E03_FuelCanopyLightbox", (16.0, 5.11, 15.515), (0, 0, 1), strip_w, strip_h, CANOPY_STRIP)
canopy.quad("E03_FuelCanopyLightbox", (8.485, 5.11, 10.5), (-1, 0, 0), strip_w, strip_h, CANOPY_STRIP)
canopy.quad("E03_FuelCanopyLightbox", (23.515, 5.11, 10.5), (1, 0, 0), strip_w, strip_h, CANOPY_STRIP)
light("sign_green", (16.0, 5.0, 4.6))

DEAD_LAMP = (11.0, 13.0)
for x in (11.0, 16.0, 21.0):
	for z in (8.0, 13.0):
		canopy.box(STEEL, (x - 0.66, 4.5, z - 0.36), (x + 0.66, 4.56, z + 0.36), skip="-y +y")
		alive = (x, z) != DEAD_LAMP
		canopy.quad("lamp_cool" if alive else "lamp_off", (x, 4.515, z), (0, -1, 0), 1.26, 0.66, up=(0, 0, 1))
		if alive:
			light("canopy", (x, 4.42, z))
# One soffit sheet has let go at a corner and hangs.
canopy.box("CorrugatedSteel003", (21.3, 4.52, 5.9), (23.1, 4.54, 7.1), m=turn("x", -34, (22.2, 4.54, 7.1)))

for cx, cz in ((10, 7), (22, 7), (10, 14), (22, 14)):
	canopy.box(WHITE, (cx - 0.15, 0.7, cz - 0.15), (cx + 0.15, 4.56, cz + 0.15), bevel=0.02)
	canopy.box("Concrete033", (cx - 0.24, G, cz - 0.24), (cx + 0.24, 0.72, cz + 0.24), bevel=0.04)
	canopy.box("PaintedMetal016", (cx - 0.165, 0.72, cz - 0.165), (cx + 0.165, 1.25, cz + 0.165))
	canopy.cyl(STEEL, (cx + (0.2 if cx < 16 else -0.2), 0.72, cz), (cx + (0.2 if cx < 16 else -0.2), 4.56, cz), 0.04, seg=8)

# =============================================================================
# Pump islands
# =============================================================================

pumps = Part("pumps-nocol")
for row, z0 in enumerate((7.4, 12.6)):
	pumps.box("Concrete033", (12, G, z0), (20, 0.2, z0 + 1.0), bevel=0.05)
	for x in (12.3, 19.7):                                    # hoop guards at the ends
		for z in (z0 + 0.22, z0 + 0.78):
			pumps.cyl("paint_yellow", (x, 0.19, z), (x, 1.08, z), 0.055, seg=8)
		pumps.cyl("paint_yellow", (x, 1.06, z0 + 0.2), (x, 1.06, z0 + 0.8), 0.055, seg=8)
		solid((x - 0.07, 0.2, z0 + 0.16), (x + 0.07, 1.1, z0 + 0.84))
	# Bin and paper-towel box between the pumps.
	pumps.box(DARK, (15.72, 0.2, z0 + 0.28), (16.28, 1.02, z0 + 0.72), bevel=0.025)
	pumps.box("black", (15.84, 0.84, z0 + 0.27), (16.16, 0.96, z0 + 0.73))
	pumps.cyl(STEEL, (16.0, 1.0, z0 + 0.5), (16.0, 1.55, z0 + 0.5), 0.025, seg=6)
	pumps.box(WHITE, (15.85, 1.5, z0 + 0.4), (16.15, 1.85, z0 + 0.6), bevel=0.01)
	solid((15.72, 0.2, z0 + 0.28), (16.28, 1.05, z0 + 0.72))
	for n, px in enumerate((13.5, 17.7)):
		za = z0 + 0.2
		zb = z0 + 0.8
		pumps.box(GREEN, (px, 0.2, za), (px + 0.8, 1.05, zb), bevel=0.015)
		pumps.box("black", (px + 0.02, 0.2, za - 0.004), (px + 0.78, 0.3, zb + 0.004))
		pumps.box(WHITE, (px + 0.03, 1.05, za + 0.03), (px + 0.77, 1.56, zb - 0.03), bevel=0.012)
		pumps.box(DARK, (px + 0.1, 1.56, za + 0.1), (px + 0.7, 1.6, zb - 0.1))
		pumps.box(WHITE, (px - 0.02, 1.6, za - 0.02), (px + 0.82, 1.99, zb + 0.02), bevel=0.012)
		for s, zf in ((-1, za), (1, zb)):                     # both faces of the pump
			face = (0, 0, s)
			pumps.quad("E09_PumpTopLightbox", (px + 0.4, 1.795, zf + s * 0.023), face, 0.74, 0.365, PUMP_TOPS[(n + row) % 2])
			pumps.quad("display", (px + 0.3, 1.31, zf - s * 0.027), face, 0.42, 0.3)
			pumps.box(DARK, (px + 0.56, 1.1, zf - s * 0.03), (px + 0.76, 1.5, zf - s * 0.022))
			# nozzle boot, nozzle, hose
			pumps.box("black", (px + 0.52, 0.62, zf - 0.01), (px + 0.74, 0.98, zf + s * 0.05), bevel=0.01)
			pumps.box(DARK, (px + 0.59, 0.72, zf + s * 0.05), (px + 0.67, 0.95, zf + s * 0.13), bevel=0.01)
			pumps.cyl(STEEL, (px + 0.63, 0.74, zf + s * 0.09), (px + 0.63, 0.58, zf + s * 0.16), 0.014, seg=6)
			sag = 0.34 if (n + row + (s > 0)) % 2 else 0.5
			pumps.tube("Rubber004", [(px + 0.14, 1.5, zf - s * 0.02), (px + 0.12, 1.2, zf + s * 0.12),
					(px + 0.2, sag, zf + s * 0.2), (px + 0.45, sag - 0.06, zf + s * 0.2), (px + 0.6, 0.6, zf + s * 0.16),
					(px + 0.63, 0.93, zf + s * 0.1)], 0.017)

# =============================================================================
# Pylon sign, turned to face along the street
# =============================================================================

pylon = Part("pylon-nocol")
pylon.box("Concrete033", (2.7, G, 1.3), (3.4, 0.45, 2.0), bevel=0.05)
pylon.box(WHITE, (2.9, 0.45, 1.5), (3.2, 7.0, 1.8), bevel=0.02)
for (y0, y1, z0, z1, image, rect) in ((4.9, 7.1, 0.35, 2.95, "E15_MainPylonSign", PYLON_BRAND),
		(3.87, 4.82, 0.4, 2.9, "E15_MainPylonSign", PYLON_STORE), (1.98, 3.78, 0.65, 2.65, "E14_PricePylon", PRICES)):
	pylon.box(DARK, (2.86, y0, z0), (3.24, y1, z1), bevel=0.03)
	for s in (-1, 1):
		pylon.quad(image, (3.05 + s * 0.193, (y0 + y1) / 2, (z0 + z1) / 2), (s, 0, 0), z1 - z0 - 0.06, y1 - y0 - 0.06, rect)
light("sign_green", (4.4, 5.6, 1.65))
light("sign_warm", (1.7, 3.2, 1.65))

# =============================================================================
# Store: shell
# =============================================================================

shell = Part("store_shell-nocol")


def ext_wall(a, b, out):
	shell.box(BLOCK, a, b, faces={{"-x": "+x", "+x": "-x", "-z": "+z", "+z": "-z"}[out]: DRYWALL}, skip="-y")


# front (z 20.0 .. 20.2): windows x 15-21 and 25-31, door x 22-23.6
for x0, x1 in ((14, 15), (21, 22), (23.6, 25), (31, 32)):
	ext_wall((x0, G, 20.0), (x1, PARAPET, 20.2), "-z")
for x0 in (15.0, 25.0):
	ext_wall((x0, G, 20.0), (x0 + 6, F + 0.6, 20.2), "-z")
	ext_wall((x0, F + 2.6, 20.0), (x0 + 6, PARAPET, 20.2), "-z")
ext_wall((22, F + 2.2, 20.0), (23.6, PARAPET, 20.2), "-z")
shell.box("long_white_tiles", (14.0, F, 19.975), (22.0, F + 0.6, 20.0), skip="+z")    # tiled base under the glass
shell.box("long_white_tiles", (23.6, F, 19.975), (32.0, F + 0.6, 20.0), skip="+z")
# back (z 29.8 .. 30.0): steel door x 29-29.9
ext_wall((14, G, 29.8), (29, PARAPET, 30.0), "+z")
ext_wall((29.9, G, 29.8), (32, PARAPET, 30.0), "+z")
ext_wall((29, F + 2.05, 29.8), (29.9, PARAPET, 30.0), "+z")
shell.box(WHITE, (29.0, F, 29.86), (29.9, F + 2.05, 29.92))
for x in (28.96, 29.9):
	shell.box(STEEL, (x, F, 29.78), (x + 0.04, F + 2.09, 30.02))
shell.box(STEEL, (28.96, F + 2.05, 29.78), (29.94, F + 2.09, 30.02))
shell.cyl("chrome", (29.1, F + 1.0, 29.92), (29.1, F + 1.0, 29.99), 0.03, seg=8)
shell.cyl("chrome", (29.1, F + 1.0, 29.79), (29.1, F + 1.0, 29.86), 0.03, seg=8)
# sides
ext_wall((14.0, G, 20.2), (14.2, PARAPET, 29.8), "-x")
ext_wall((31.8, G, 20.2), (32.0, PARAPET, 29.8), "+x")
# coping, roof, roof unit
for a, b in (((13.95, PARAPET, 19.95), (32.05, PARAPET + 0.06, 20.25)), ((13.95, PARAPET, 29.75), (32.05, PARAPET + 0.06, 30.05)),
		((13.95, PARAPET, 20.25), (14.25, PARAPET + 0.06, 29.75)), ((31.75, PARAPET, 20.25), (32.05, PARAPET + 0.06, 29.75))):
	shell.box(STEEL, a, b)
shell.box("bitumen", (14.2, TOP, 20.2), (31.8, TOP + 0.25, 29.8), skip="-y -x +x -z +z")
shell.box(WHITE, (20.0, TOP + 0.25, 24.0), (22.2, 4.95, 25.6), bevel=0.04)
shell.cyl("black", (21.1, 4.95, 24.8), (21.1, 5.0, 24.8), 0.6, seg=14)
shell.cyl(STEEL, (27.0, TOP + 0.25, 27.5), (27.0, 4.6, 27.5), 0.09, seg=8)
shell.cyl(STEEL, (16.5, TOP + 0.25, 22.0), (16.5, 4.4, 22.0), 0.07, seg=8)

# The boxed fascia over the walk, with the store's lightbox.
shell.box(WHITE, (13.7, 2.85, 19.2), (32.3, 4.16, 20.0), skip="+z")
for a, b in (((13.68, 2.88, 19.18), (32.32, 3.05, 19.2)), ((13.68, 2.88, 19.2), (13.7, 3.05, 20.0)), ((32.3, 2.88, 19.2), (32.32, 3.05, 20.0))):
	shell.box(GREEN, a, b)
sign_h = 0.86
sign_w = sign_h * (STORE_STRIP[2] - STORE_STRIP[0]) / (STORE_STRIP[3] - STORE_STRIP[1])
shell.box(DARK, (22.8 - sign_w / 2 - 0.05, 3.12, 19.13), (22.8 + sign_w / 2 + 0.05, 4.08, 19.2), bevel=0.015)
shell.quad("N03_ConvenienceStoreLightbox", (22.8, 3.6, 19.123), (0, 0, -1), sign_w, sign_h, STORE_STRIP)
light("sign_blue", (22.8, 3.5, 18.5))
for x in (17.5, 22.8, 28.0):
	shell.cyl("lamp_warm", (x, 2.83, 19.6), (x, 2.85, 19.6), 0.1, seg=10)
light("door", (22.8, 2.6, 19.55))

# Shop windows: aluminium frames, glass.
for x0 in (15.0, 25.0):
	shell.box(STEEL, (x0, F + 0.6, 20.03), (x0 + 6, F + 0.66, 20.17))
	shell.box(STEEL, (x0, F + 2.54, 20.03), (x0 + 6, F + 2.6, 20.17))
	for k in range(5):
		x = x0 + k * 1.5 - (0.0 if k == 0 else (0.06 if k == 4 else 0.03))
		shell.box(STEEL, (x, F + 0.66, 20.03), (x + 0.06, F + 2.54, 20.17))
	shell.box("glass", (x0, F + 0.66, 20.09), (x0 + 6, F + 2.54, 20.11), skip="-x +x -y +y")
neon_w = 1.15
shell.quad("E16_TwentyFourHourNeon", (26.5, F + 1.75, 20.13), (0, 0, -1), neon_w, neon_w * 0.73, NEON)
light("neon", (26.5, F + 1.75, 19.7))
# Entrance: frame, sill plate, two leaves standing open into the shop.
shell.box(STEEL, (22.0, G, 20.0), (23.6, F + 0.005, 20.2), skip="-y")
shell.box(STEEL, (22.0, F + 2.14, 20.03), (23.6, F + 2.2, 20.17))
for hinge_x, side in ((22.0, -1), (23.6, 1)):
	xa = hinge_x + side * 0.005
	xb = hinge_x + side * 0.045
	shell.box(STEEL, (xa, F + 0.02, 20.2), (xb, F + 0.12, 20.98))
	shell.box(STEEL, (xa, F + 2.02, 20.2), (xb, F + 2.12, 20.98))
	shell.box(STEEL, (xa, F + 0.12, 20.2), (xb, F + 2.02, 20.27))
	shell.box(STEEL, (xa, F + 0.12, 20.91), (xb, F + 2.02, 20.98))
	shell.box("glass", (hinge_x + side * 0.02, F + 0.12, 20.27), (hinge_x + side * 0.03, F + 2.02, 20.91), skip="-y +y -z +z")
	shell.cyl("chrome", (hinge_x - side * 0.03, F + 1.0, 20.33), (hinge_x - side * 0.03, F + 1.0, 20.85), 0.015, seg=6)
	solid((min(xa, xb), F, 20.2), (max(xa, xb), F + 2.12, 20.98))

# Inside: floor, ceiling with its light panels, the staff room's partition.
shell.box("D24_VCTFloorTiles", (14.2, G, 20.2), (31.8, F, 29.8), skip="-y -x +x -z +z")
shell.box("Rubber004", (22.0, F, 20.25), (23.6, F + 0.008, 21.3), skip="-y")
shell.box("OfficeCeiling001", (14.2, CEIL, 20.2), (31.8, CEIL + 0.02, 29.8), skip="+y -x +x -z +z")
TROFFERS = [(17.5, 22.5, True), (17.5, 27.0, True), (23.0, 24.5, True), (28.5, 21.6, True), (23.0, 21.6, False),
		(23.0, 27.6, True), (28.5, 24.5, False)]
for x, z, alive in TROFFERS:
	shell.box(STEEL, (x - 0.63, CEIL - 0.012, z - 0.33), (x + 0.63, CEIL + 0.001, z + 0.33), skip="+y -y")
	shell.quad("lamp_cool" if alive else "lamp_off", (x, CEIL - 0.006, z), (0, -1, 0), 1.2, 0.6, up=(0, 0, 1))
	if alive:
		light("tube", (x, CEIL - 0.25, z))
shell.box(DRYWALL, (26.0, F, 25.94), (27.0, CEIL, 26.06), skip="-y +y")
shell.box(DRYWALL, (28.0, F, 25.94), (31.8, CEIL, 26.06), skip="-y +y")
shell.box(DRYWALL, (27.0, F + 2.05, 25.94), (28.0, CEIL, 26.06), skip="+y")
shell.box(DRYWALL, (25.94, F, 26.06), (26.06, CEIL, 29.8), skip="-y +y")
for x in (26.99, 27.97):                                       # door casing; the leaf stands open inside the room
	shell.box("oak_veneer_01", (x, F, 25.92), (x + 0.04, F + 2.05, 26.08))
shell.box("oak_veneer_01", (26.99, F + 2.01, 25.92), (28.01, F + 2.05, 26.08))
shell.box("oak_veneer_01", (28.01, F + 0.01, 26.06), (28.05, F + 2.0, 26.96), bevel=0.004)
shell.cyl("chrome", (27.98, F + 1.0, 26.88), (28.08, F + 1.0, 26.88), 0.025, seg=8)
solid((28.0, F, 26.06), (28.06, F + 2.0, 26.96))
shell.cyl(DARK, (29.0, CEIL - 0.06, 28.0), (29.0, CEIL, 28.0), 0.06, seg=8)
shell.cyl("lamp_warm", (29.0, CEIL - 0.16, 28.0), (29.0, CEIL - 0.06, 28.0), 0.035, seg=8)
light("bulb", (29.0, CEIL - 0.3, 28.0))
shell.quad("N11_ExitLightbox", (29.45, F + 2.3, 29.79), (0, 0, -1), 0.5, 0.29, EXIT)

# Out front: ice box, paper box, bin, pay phone.
shell.box(WHITE, (16.2, F, 19.22), (18.2, F + 1.85, 19.96), bevel=0.025)
for x in (16.3, 17.25):
	shell.box(STEEL, (x, F + 0.35, 19.2), (x + 0.85, F + 1.6, 19.225), bevel=0.008)
	shell.cyl("chrome", (x + 0.76, F + 0.8, 19.18), (x + 0.76, F + 1.2, 19.18), 0.012, seg=6)
solid((16.2, F, 19.22), (18.2, F + 1.85, 19.96))
shell.box("PaintedMetal007", (20.6, F + 0.12, 19.45), (21.1, F + 1.15, 19.9), bevel=0.02)
shell.box("glass_dark", (20.66, F + 0.6, 19.44), (21.04, F + 1.05, 19.455))
shell.box("paper", (20.69, F + 0.63, 19.437), (21.01, F + 1.02, 19.445))
for x in (20.66, 21.0):
	shell.cyl(STEEL, (x, F, 19.675), (x, F + 0.12, 19.675), 0.02, seg=6)
solid((20.6, F, 19.45), (21.1, F + 1.15, 19.9))
shell.cyl(DARK, (24.05, F, 19.45), (24.05, F + 0.9, 19.45), 0.26, seg=12, r2=0.29)
shell.cyl("black", (24.05, F + 0.9, 19.45), (24.05, F + 0.97, 19.45), 0.3, seg=12, r2=0.2)
solid((23.79, F, 19.19), (24.31, F + 0.97, 19.71))
shell.box(STEEL, (24.5, F + 0.95, 19.72), (24.95, F + 1.75, 19.975), skip="-z")
shell.box("black", (24.58, F + 1.05, 19.83), (24.87, F + 1.6, 19.95), bevel=0.01)
shell.box(DARK, (24.6, F + 1.2, 19.79), (24.66, F + 1.5, 19.84), bevel=0.01)
shell.tube(STEEL, [(24.63, F + 1.2, 19.82), (24.6, F + 0.85, 19.84), (24.72, F + 0.8, 19.88), (24.8, F + 1.1, 19.9)], 0.008, seg=5)
# Propane cage on the south wall.
shell.box(STEEL, (32.02, G, 22.0), (32.7, G + 0.08, 24.0))
shell.box("ChainLink", (32.02, G + 0.08, 22.0), (32.7, G + 1.7, 24.0), skip="-y -x")
for z in (22.0, 23.0, 24.0):
	shell.cyl(STEEL, (32.68, G, z), (32.68, G + 1.72, z), 0.02, seg=6)
for z in (22.3, 22.75, 23.3, 23.7):
	shell.cyl(WHITE, (32.35, G + 0.08, z), (32.35, G + 0.5, z), 0.16, seg=10)
	shell.cyl(WHITE, (32.35, G + 0.5, z), (32.35, G + 0.58, z), 0.16, seg=10, r2=0.05)
solid((32.0, G, 22.0), (32.7, G + 1.7, 24.0))

# =============================================================================
# Store: fittings
# =============================================================================

fit = Part("store_fittings-nocol")


def gondola(x0, z0, z1, left, right):
	"""Shelving 0.9 m wide along z, goods on both sides."""
	fit.box("Plastic010", (x0, F, z0), (x0 + 0.9, F + 0.16, z1))
	fit.box("Plastic010", (x0 + 0.43, F + 0.16, z0), (x0 + 0.47, F + 1.5, z1))
	for z in (z0, z1 - 0.03):
		fit.box("Plastic010", (x0 + 0.04, F + 0.16, z), (x0 + 0.86, F + 1.5, z + 0.03))
	length = z1 - z0
	for side, goods in ((90, right), (-90, left)):
		# local x runs along the shelf, local +z is the aisle side
		m = frame((x0 + 0.45, 0, z1 if side == 90 else z0), side)
		for i, y in enumerate((F + 0.16, F + 0.5, F + 0.84, F + 1.18)):
			if i > 0:
				fit.box(WHITE, (0.03, y - 0.025, 0.02), (length - 0.03, y, 0.42), m=m)
				fit.box("paper", (0.03, y - 0.03, 0.42), (length - 0.03, y + 0.005, 0.424), m=m)
			stock(fit, m, 0.04, length - 0.04, y, 0.41, goods[i % len(goods)], gaps=0.1 + 0.08 * i)


gondola(16.6, 22.5, 28.0, [SNACKS, SNACKS, DRY, SNACKS], [DRY, DRY, SNACKS, DRY])
gondola(19.2, 22.5, 28.0, [DRY, SNACKS, SNACKS, DRY], [DRINKS, DRY, SNACKS, SNACKS])
gondola(24.4, 23.6, 25.6, [SNACKS, SNACKS, SNACKS, SNACKS], [DRY, SNACKS, DRY, DRY])
for i in range(7):                                             # some of it is on the floor
	image, rect, w, d = random.choice(SNACKS + DRY)
	x = random.uniform(18.0, 18.9)
	z = random.uniform(24.0, 27.5)
	h = min(w * (rect[3] - rect[1]) / (rect[2] - rect[0]), 0.3)
	fit.image_box(image, (x, F + 0.002, z), (x + w, F + 0.002 + d, z + h), "+y", rect,
			m=turn("y", random.uniform(0, 360), (x, F, z)))


class Shifted:
	"""A part seen from a local frame: every shape call gets the matrix."""

	def __init__(self, part, m):
		self.part = part
		self.m = m

	def __getattr__(self, name):
		call = getattr(self.part, name)
		return lambda *a, **k: call(*a, m=self.m, **k)


def coolers(m, length, doors):
	"""A bank of glass-door coolers 0.8 m deep and 2.1 m high, front to local +z."""
	p = Shifted(fit, m)
	p.box(DARK, (0, F, 0), (length, F + 0.2, 0.8))
	p.box("Plastic010", (0, F + 1.92, 0), (length, F + 2.1, 0.8))
	p.box("cooler_white", (0, F + 0.2, 0), (length, F + 1.92, 0.05))
	for x in (0.0, length - 0.03):
		p.box("cooler_white", (x, F + 0.2, 0.05), (x + 0.03, F + 1.92, 0.76))
	w = length / doors
	for i in range(doors + 1):
		x = min(max(i * w - 0.03, 0.0), length - 0.06)
		p.box(DARK, (x, F + 0.2, 0.72), (x + 0.06, F + 1.92, 0.8))
	p.box(DARK, (0, F + 1.86, 0.72), (length, F + 1.92, 0.8))
	p.box(DARK, (0, F + 0.2, 0.72), (length, F + 0.26, 0.8))
	for i in range(doors):
		x0 = i * w
		lit = random.random() > 0.2
		p.box("lamp_cool" if lit else "lamp_off", (x0 + 0.035, F + 0.3, 0.68), (x0 + 0.06, F + 1.84, 0.705))
		p.cyl("chrome", (x0 + w - 0.1, F + 0.9, 0.815), (x0 + w - 0.1, F + 1.3, 0.815), 0.012, seg=6)
		for y in (F + 0.26, F + 0.66, F + 1.06, F + 1.46):
			p.box("cooler_white", (x0 + 0.03, y, 0.08), (x0 + w - 0.03, y + 0.02, 0.66))
			stock(fit, m, x0 + 0.05, x0 + w - 0.03, y + 0.02, 0.64, DRINKS, gaps=0.22, max_h=0.26)
	p.box("glass", (0, F + 0.26, 0.755), (length, F + 1.86, 0.765), skip="-x +x -y +y")


for origin, yaw, length, doors in (((14.2, 0, 29.0), 90, 8.0, 10), ((25.5, 0, 29.8), 180, 10.2, 13)):
	coolers(frame(origin, yaw), length, doors)
light("cooler", (15.9, F + 1.3, 25.0))
light("cooler", (20.5, F + 1.3, 28.1))

# Checkout counter: customers stand on the door side (-z).
fit.box("oak_veneer_01", (26.4, F, 22.62), (30.6, F + 1.0, 23.3))
for x in (27.45, 29.55):
	fit.quad("E46_CheckoutCounter", (x, F + 0.5, 22.612), (0, 0, -1), 2.1, 1.0, COUNTER_FRONT)
fit.box("Plastic010", (26.35, F + 1.0, 22.55), (30.65, F + 1.05, 23.35), bevel=0.01)
fit.box("Plastic010", (27.5, F + 1.05, 22.8), (27.95, F + 1.2, 23.2), bevel=0.015)                 # till
fit.box("Plastic010", (27.55, F + 1.2, 23.0), (27.9, F + 1.4, 23.2), m=turn("x", 20, (27.7, F + 1.2, 23.1)))
fit.quad("display", (27.72, F + 1.33, 23.215), (0, 0.34, 0.94), 0.28, 0.1, up=(0, 0.94, -0.34))
fit.box("black", (29.85, F + 1.05, 22.8), (30.3, F + 1.42, 23.28), bevel=0.02)                     # security monitor
fit.quad("screen", (30.075, F + 1.24, 23.285), (0, 0, 1), 0.36, 0.28)
fit.box("Rubber004", (28.4, F + 1.05, 22.7), (29.0, F + 1.058, 23.1))
for i in range(5):                                             # gum and bars by the till
	image, rect, w, d = SNACKS[3 + i % 6]
	h = w * (rect[3] - rect[1]) / (rect[2] - rect[0])
	fit.image_box(image, (26.6 + i * 0.17, F + 1.05, 22.62), (26.6 + i * 0.17 + w, F + 1.05 + h, 22.62 + d), "-z", rect)
# Cigarettes on the wall behind the clerk, the two lightboxes over them.
fit.box(DARK, (28.4, F + 1.1, 25.76), (31.5, F + 2.15, 25.94))
for row in range(4):
	y = F + 1.14 + row * 0.25
	fit.box(STEEL, (28.42, y - 0.012, 25.72), (31.48, y, 25.9))
	x = 28.45
	while x < 31.4:
		kind = random.choice(("pack_red", "pack_white", "pack_green", "pack_white"))
		for _ in range(random.randint(3, 7)):
			if x < 31.4 and random.random() > 0.08:
				fit.box(kind, (x, y, 25.725 - random.uniform(0, 0.012)), (x + 0.056, y + 0.088, 25.758))
			x += 0.062
fit.quad("E17_CigaretteLotteryLightbox", (29.2, F + 2.52, 25.93), (0, 0, -1), 0.92, 0.69, CIGS)
fit.quad("E17_CigaretteLotteryLightbox", (30.6, F + 2.52, 25.93), (0, 0, -1), 0.92, 0.69, LOTTO)
light("sign_warm", (29.9, F + 2.4, 25.3))

# Coffee by the window.
fit.box("oak_veneer_01", (29.6, F, 20.37), (31.7, F + 0.9, 21.0))
fit.box("Plastic010", (29.57, F + 0.9, 20.35), (31.72, F + 0.95, 21.03), bevel=0.01)
fit.box(STEEL, (30.3, F + 0.95, 20.42), (30.9, F + 1.4, 20.78), bevel=0.015)
fit.box("black", (30.3, F + 1.0, 20.78), (30.9, F + 1.03, 20.98))
for x in (30.45, 30.75):
	fit.cyl("coffee", (x, F + 1.03, 20.88), (x, F + 1.15, 20.88), 0.065, seg=10)
	fit.cyl("black", (x, F + 1.15, 20.88), (x, F + 1.17, 20.88), 0.05, seg=10)
fit.cyl("led_red", (30.6, F + 1.3, 20.78), (30.6, F + 1.3, 20.79), 0.012, seg=6)
for i, x in enumerate((29.8, 29.92, 30.04)):
	fit.cyl("paper", (x, F + 0.95, 20.6), (x, F + 1.12 + 0.06 * i, 20.6), 0.042, seg=8, r2=0.048)
fit.box(DARK, (31.1, F + 0.95, 20.5), (31.5, F + 1.1, 20.8), bevel=0.01)
fit.box("paper", (31.14, F + 1.1, 20.54), (31.46, F + 1.12, 20.76))
# A wet-floor sign that did its job.
fit.prism("paint_yellow", [(22.3, F), (22.62, F), (22.48, F + 0.62), (22.44, F + 0.62)], "z", 22.0, 22.3,
		m=turn("y", 25, (22.45, F, 22.15)))

# Staff room: steel desk with papers, a chair pushed back, lockers, boxes, mop.
fit.box("oak_veneer_01", (30.3, F + 0.71, 26.3), (31.7, F + 0.75, 27.1), bevel=0.006)
fit.box(WHITE, (30.34, F, 26.34), (30.8, F + 0.71, 27.06))
fit.box(WHITE, (31.62, F, 26.34), (31.66, F + 0.71, 27.06))
for i in range(3):
	fit.box(STEEL, (30.36, F + 0.08 + i * 0.21, 27.06), (30.78, F + 0.26 + i * 0.21, 27.075))
for x, z, yaw in ((31.0, 26.7, 12), (30.75, 26.55, -30), (31.35, 26.75, 50), (30.5, 26.8, 5)):
	fit.box("paper", (x - 0.105, F + 0.751, z - 0.15), (x + 0.105, F + 0.753, z + 0.15), m=turn("y", yaw, (x, F, z)), skip="-y")
mc = turn("y", -35, (29.75, F, 26.75))
fit.box(DARK, (29.5, F + 0.42, 26.5), (30.0, F + 0.5, 27.0), bevel=0.02, m=mc)
fit.box(DARK, (29.5, F + 0.5, 26.5), (30.0, F + 0.95, 26.56), bevel=0.02, m=mc)
fit.cyl(STEEL, (29.75, F + 0.06, 26.75), (29.75, F + 0.42, 26.75), 0.03, seg=6, m=mc)
fit.cyl("black", (29.75, F, 26.75), (29.75, F + 0.06, 26.75), 0.27, seg=5, m=mc)
solid((29.5, F, 26.5), (30.0, F + 0.95, 27.0), m=mc)
for i in range(3):
	fit.box("PaintedMetal007", (26.2, F + 0.02, 28.2 + i * 0.5), (26.7, F + 1.8, 28.69 + i * 0.5), bevel=0.008)
	fit.box("black", (26.701, F + 1.5, 28.3 + i * 0.5), (26.704, F + 1.62, 28.6 + i * 0.5))
	fit.cyl("chrome", (26.7, F + 1.0, 28.62 + i * 0.5), (26.73, F + 1.0, 28.62 + i * 0.5), 0.015, seg=6)
for a, b in (((31.15, F, 29.1), (31.75, F + 0.42, 29.7)), ((31.2, F + 0.42, 29.15), (31.7, F + 0.8, 29.68)),
		((30.5, F, 29.25), (31.05, F + 0.38, 29.72))):
	fit.box("cardboard", a, b, bevel=0.006)
solid((31.15, F, 29.1), (31.75, F + 0.8, 29.7))
fit.quad("cork", (31.795, F + 1.5, 28.2), (-1, 0, 0), 1.2, 0.8)
for (dz, dy, yaw), note in zip(((-0.38, 0.17, 4), (0.02, 0.2, -6), (0.38, 0.14, 3), (-0.2, -0.17, -2), (0.22, -0.2, 5)), (2, 3, 6, 7, 9)):
	fit.quad("N23_HandwrittenNotes", (31.79, F + 1.5 + dy, 28.2 + dz), (-1, 0, 0), 0.26, 0.27, rect=NOTES[note],
			m=turn("x", yaw, (31.79, F + 1.5 + dy, 28.2 + dz)))
fit.cyl("paint_yellow", (26.55, F, 26.55), (26.55, F + 0.32, 26.55), 0.17, seg=10, r2=0.2)
fit.cyl("worn_planks", (26.55, F + 0.1, 26.55), (26.2, F + 1.5, 26.3), 0.016, seg=6)

# =============================================================================
# Back alley
# =============================================================================

alley = Part("alley-nocol")


def fence(ax, az, bx, bz, y0=G, y1=2.1):
	length = math.hypot(bx - ax, bz - az)
	n = max(1, round(length / 2.4))
	for i in range(n + 1):
		t = i / n
		x = ax + (bx - ax) * t
		z = az + (bz - az) * t
		alley.cyl(STEEL, (x, y0, z), (x, y1 + 0.06, z), 0.03, seg=6)
	alley.cyl(STEEL, (ax, y1, az), (bx, y1, bz), 0.02, seg=6)
	p = [alley._v(q) for q in ((ax, y0 + 0.04, az), (bx, y0 + 0.04, bz), (bx, y1, bz), (ax, y1, az))]
	alley._face(p, "ChainLink")


fence(12, 38, 34, 38)
fence(12, 30, 12, 38)
fence(12, 30, 14, 30)
fence(32, 30, 34, 30)
fence(34, 30, 34, 31)
fence(34, 33, 34, 38)
# The gate stands open, swung out into the lot.
mg = turn("y", -78, (34.0, 0, 33.0))
for z in (31.05, 32.97):
	alley.cyl(STEEL, (34.0, G + 0.1, z), (34.0, 2.05, z), 0.025, seg=6, m=mg)
for y in (G + 0.1, 2.05):
	alley.cyl(STEEL, (34.0, y, 31.05), (34.0, y, 32.97), 0.025, seg=6, m=mg)
alley._face([alley._v(q, mg) for q in ((34.0, G + 0.1, 31.05), (34.0, G + 0.1, 32.97), (34.0, 2.05, 32.97), (34.0, 2.05, 31.05))], "ChainLink")
solid((33.97, G, 31.05), (34.03, 2.05, 32.97), m=mg)


def dumpster(x0, lid_open):
	body = [(36.45, 0.16), (37.58, 0.16), (37.58, 1.27), (36.42, 1.02)]
	alley.prism("PaintedMetal007", body, "x", x0 + 0.03, x0 + 1.77)
	alley.box("PaintedMetal007", (x0 + 0.0, 0.98, 36.4), (x0 + 1.8, 1.04, 36.46))
	for x in (x0 - 0.05, x0 + 1.77):
		alley.box("PaintedMetal007", (x, 0.72, 36.6), (x + 0.08, 0.9, 37.4))
	slope = math.degrees(math.atan2(1.27 - 1.02, 37.58 - 36.42))
	for k, xa in enumerate((x0 + 0.04, x0 + 0.91)):
		m = turn("x", -slope, (0, 1.275, 37.58))
		if lid_open and k == 1:
			m = turn("x", 105, (0, 1.275, 37.58))
		alley.box(DARK, (xa, 1.275, 36.38), (xa + 0.85, 1.3, 37.58), m=m)
	for x in (x0 + 0.2, x0 + 1.6):
		for z in (36.6, 37.4):
			alley.cyl("black", (x - 0.03, 0.1, z), (x + 0.03, 0.1, z), 0.07, seg=8)


dumpster(15.0, False)
dumpster(17.4, True)
for c, s, yaw in (((19.75, 0.3, 37.3), (0.7, 0.55, 0.6), 20), ((20.3, 0.25, 36.9), (0.6, 0.45, 0.55), 70),
		((19.9, 0.62, 37.35), (0.55, 0.45, 0.5), 130), ((14.5, 0.27, 37.0), (0.6, 0.5, 0.6), 10),
		((18.3, 1.25, 37.0), (0.7, 0.4, 0.6), 40)):
	alley.blob("trash_bag", c, s, yaw=yaw)

# The machine by the back wall: the first one that moves.
alley.box(DARK, (21.1, G, 30.1), (22.1, 2.0, 30.88), bevel=0.02)
alley.quad("E21_VendingMachine", (21.6, 1.02, 30.89), (0, 0, 1), 0.93, 1.94, VENDING)
light("vending", (21.6, 1.3, 31.5))
# Wall lamp, back-door lamp, conduit, breaker box, downpipes, condenser.
alley.box(STEEL, (23.85, 3.0, 30.0), (24.15, 3.2, 30.16), bevel=0.01)
alley.quad("lamp_warm", (24.0, 2.995, 30.09), (0, -1, 0), 0.24, 0.12, up=(0, 0, 1))
light("wallpack", (24.0, 2.85, 30.5))
alley.box(STEEL, (27.0, 1.2, 30.0), (27.6, 1.95, 30.16), bevel=0.01)
alley.tube(STEEL, [(27.3, 1.95, 30.05), (27.3, 3.3, 30.05), (26.0, 3.4, 30.05), (24.15, 3.12, 30.06)], 0.02, seg=6, steps=2)
alley.tube(STEEL, [(27.3, 1.2, 30.05), (27.3, 0.1, 30.05)], 0.025, seg=6, steps=1)
for x in (14.35, 31.65):
	alley.cyl(WHITE, (x, 0.15, 30.07), (x, PARAPET - 0.1, 30.07), 0.05, seg=8)
	alley.tube(WHITE, [(x, 0.15, 30.07), (x, 0.06, 30.1), (x, 0.05, 30.3)], 0.05, seg=8, steps=3)
alley.box("Concrete033", (15.8, G, 30.05), (17.3, G + 0.1, 31.05))
alley.box(WHITE, (15.95, G + 0.1, 30.15), (17.15, G + 0.95, 30.95), bevel=0.02)
alley.cyl("black", (16.55, G + 0.95, 30.55), (16.55, G + 0.97, 30.55), 0.33, seg=12)
alley.tube("Rubber004", [(16.2, 0.6, 30.15), (16.2, 0.9, 30.04), (16.2, 2.6, 30.03)], 0.02, seg=5, steps=2)
solid((15.95, G, 30.15), (17.15, G + 0.95, 30.95))


def pallet(x, y, z, yaw):
	m = turn("y", yaw, (x + 0.6, y, z + 0.5))
	for dz in (0.0, 0.45, 0.9):
		alley.box("worn_planks", (x, y + 0.02, z + dz), (x + 1.2, y + 0.11, z + dz + 0.1), m=m)
	for i in range(5):
		alley.box("worn_planks", (x + i * 0.275, y + 0.11, z), (x + i * 0.275 + 0.1, y + 0.13, z + 1.0), m=m)
	for i in (0, 2, 4):
		alley.box("worn_planks", (x + i * 0.275, y, z), (x + i * 0.275 + 0.1, y + 0.02, z + 1.0), m=m)


for i, yaw in enumerate((0, 4, -3, 7)):
	pallet(26.0, G + i * 0.14, 36.6, yaw)
solid((25.95, G, 36.55), (27.25, G + 0.56, 37.65))
for i, (kind, dx) in enumerate((("crate_blue", 0.0), ("crate_red", 0.02), ("crate_blue", -0.02))):
	alley.box(kind, (28.0 + dx, G + i * 0.3, 30.08), (28.36 + dx, G + 0.29 + i * 0.3, 30.4), skip="+y")
alley.box("crate_red", (28.5, G, 30.1), (28.86, G + 0.29, 30.42), m=turn("y", 15, (28.68, 0, 30.26)), skip="+y")
solid((28.0, G, 30.08), (28.36, G + 0.9, 30.4))

# =============================================================================
# The patrol car, nose to +x. Stays inside the greybox's two boxes.
# =============================================================================

car = Part("police_car-nocol")
ZL = 9.62                # left side
ZR = 11.48               # right side (the flat tyre is on this one, at the front)


def arch(cx, r=0.39, cy=0.36):
	return [(cx + r * math.cos(math.radians(t)), cy + r * math.sin(math.radians(t))) for t in range(0, 181, 30)]


outline = [(13.62, 0.40), (13.60, 0.62), (13.63, 0.88), (13.78, 0.965), (15.0, 0.99), (17.2, 0.98), (18.22, 0.89),
		(18.38, 0.80), (18.40, 0.56), (18.36, 0.38), (18.0, 0.30)] + arch(17.45) + arch(14.55) + [(14.0, 0.30)]
car.prism("car_black", outline, "z", ZL, ZR)
car.box("black", (14.0, 0.3, ZL + 0.25), (18.0, 0.8, ZR - 0.25))
for z0, z1 in ((ZL - 0.012, ZL), (ZR, ZR + 0.012)):                                   # white doors
	car.box("car_white", (15.0, 0.44, z0), (17.12, 0.975, z1))
car.hull("glass_dark", (14.95, 0.985, ZL + 0.05, 17.3, ZR - 0.05), (15.5, 1.44, ZL + 0.2, 16.72, ZR - 0.2),
		faces={"+y": "car_white", "-y": "black"})
car.box("car_white", (15.46, 1.44, ZL + 0.17), (16.76, 1.475, ZR - 0.17), bevel=0.012)
for xa, xb in ((15.42, 15.5), (16.08, 16.16), (16.72, 16.8)):                         # pillars
	for z in (ZL + 0.19, ZR - 0.19):
		car.box("car_white", (xa, 0.985, z - 0.02), (xb, 1.445, z + 0.02),
				m=turn("x", 17 if z < 10.55 else -17, (0, 1.445, z)))
car.box("black", (15.96, 1.475, 9.98), (16.24, 1.51, 11.12))                          # light bar
car.box("lens_red", (15.94, 1.5, 9.95), (16.26, 1.62, 10.5), bevel=0.02)
car.box("lens_blue", (15.94, 1.5, 10.6), (16.26, 1.62, 11.15), bevel=0.02)
car.box("chrome", (15.98, 1.51, 10.5), (16.22, 1.6, 10.6))
car.box("chrome", (18.34, 0.38, ZL - 0.02), (18.46, 0.55, ZR + 0.02), bevel=0.02)       # bumpers
car.box("chrome", (13.54, 0.4, ZL - 0.02), (13.66, 0.57, ZR + 0.02), bevel=0.02)
car.quad("F16_FrontGrille", (18.405, 0.69, 10.55), (1, 0, 0), 0.94, 0.27, rect=GRILLE)
# seen from in front, the picture's left lamp is the one towards +z
car.quad("F17_Headlight", (18.407, 0.69, 11.2), (1, 0, 0), 0.4, 0.26, rect=HEADLAMP_L)
car.quad("F17_Headlight", (18.407, 0.69, 9.9), (1, 0, 0), 0.4, 0.26, rect=HEADLAMP_R)
car.quad("F18_TailLight", (13.61, 0.77, 10.55), (-1, 0, 0), 1.72, 0.2, rect=TAIL)
car.quad("B44_LicensePlates", (13.535, 0.485, 10.55), (-1, 0, 0), 0.3, 0.15, rect=PLATE)
car.decal("F02_DoorBadgeAndUnitNumber", (16.06, 0.72, ZL - 0.012), (0, 0, -1), 0.72, 0.46, rect=BADGE, lift=0.003)
car.decal("F02_DoorBadgeAndUnitNumber", (16.06, 0.72, ZR + 0.012), (0, 0, 1), 0.72, 0.46, rect=BADGE, lift=0.003)
for z in (ZL - 0.06, ZR + 0.06):                                                       # wing mirrors
	car.box("car_black", (17.0, 1.0, z - 0.07), (17.1, 1.1, z + 0.07), bevel=0.015)
for z in (ZL + 0.25, ZR - 0.25):                                                       # push bar
	car.box("black", (18.46, 0.34, z - 0.03), (18.52, 0.92, z + 0.03), bevel=0.01)
car.box("black", (18.47, 0.72, ZL + 0.25), (18.51, 0.78, ZR - 0.25))
car.cyl("black", (13.95, 0.965, 11.1), (13.9, 1.75, 11.12), 0.006, seg=4)                # aerial
car.cyl("chrome", (17.22, 1.05, ZL - 0.05), (17.32, 1.05, ZL - 0.05), 0.06, seg=8)        # spot lamp
for cx in (14.55, 17.45):
	for z0, z1, s in ((ZL - 0.01, ZL + 0.21, -1), (ZR - 0.21, ZR + 0.01, 1)):
		is_flat = cx > 16 and s > 0
		cy = G + (0.27 if is_flat else 0.33)
		m = lotkit.Matrix.Translation((cx, cy, 0)) @ lotkit.Matrix.Diagonal((1.0, 0.8 if is_flat else 1.0, 1.0, 1.0))
		car.cyl("tyre", (0, 0, z0), (0, 0, z1), 0.33, seg=14, m=m)
		rim = z0 - 0.004 if s < 0 else z1 + 0.004
		car.cyl("chrome", (0, 0, rim), (0, 0, rim + s * 0.003), 0.2, seg=12, m=m)

# =============================================================================


# =============================================================================
# Dirt, wear and the traces of the last half hour
# =============================================================================
# Laid on last, largest things first. Three small scenes carry the story:
#   1. the vending machine that used to stand out front is not there: a clean
#      rectangle, a cord torn out of the wall, and drag marks round the south
#      side of the store, through the gate, to where it now stands glowing;
#   2. the clerk's smoking spot by the back door, left mid-cigarette;
#   3. the staff room, where somebody unplugged everything and wrote it down.

dirt = Part("dirt-nocol")
PAD = G + 0.015
UP = (0, 1, 0)


def trail(mat, points, w, h, y, rect=None):
	"""A decal on the ground at the middle of every leg of a path, turned along it."""
	for (ax, az), (bx, bz) in zip(points, points[1:]):
		dirt.decal(mat, ((ax + bx) / 2, y, (az + bz) / 2), UP, w, h, turn_deg=math.degrees(math.atan2(bx - ax, bz - az)), rect=rect)


# --- forecourt: years of cars ---------------------------------------------------
for (x, z, size, yaw), q in zip(((16.9, 10.5, 1.5, 20), (16.0, 6.4, 1.7, 100), (13.0, 14.6, 1.4, 200), (19.0, 14.5, 1.6, 310)), (0, 1, 2, 3)):
	dirt.decal("M27_EngineOilStains", (x, PAD, z), UP, size, size, yaw, QUARTERS[q])
for (x, z, yaw), q in zip(((15.2, 6.1, 0), (17.9, 14.9, 90), (12.6, 10.3, 180), (20.6, 6.6, 45)), (0, 1, 2, 3)):
	dirt.decal("A39_OilDripSpots", (x, PAD, z), UP, 0.9, 0.9, yaw, QUARTERS[q], lift=0.005)
dirt.decal("A40_SkidMarks", (4.6, G, 10.6), UP, 2.4, 5.0, 96)
dirt.decal("A40_SkidMarks", (28.5, G, 11.8), UP, 2.0, 4.2, 72)
for (x, z, size, yaw), q in zip(((6.0, 17.2, 2.6, 0), (27.2, 16.6, 3.2, 70), (39.0, 12.0, 3.0, 160), (29.5, 34.6, 2.4, 30),
		(22.0, 34.0, 2.0, 250), (8.0, 28.0, 3.4, 110)), (0, 1, 2, 3, 1, 2)):
	dirt.decal("A36_PuddleMudRim", (x, G, z), UP, size, size, yaw, QUARTERS[q])
dirt.decal("A12c_ParkingBaysAndAccessibleBay", (37.35, G, 22.3), UP, 1.5, 1.5, 180)
dirt.decal("A12d_ParkingBaysAndAccessibleBay", (34.4, G, 23.4), UP, 1.4, 3.6, 0)
dirt.decal("M37a_UtilitySprayPaintMarks", (3.6, G, 4.2), UP, 0.8, 0.8, 60)
dirt.decal("M37b_UtilitySprayPaintMarks", (6.4, G, 2.4), UP, 0.6, 0.6, 85)
dirt.decal("M37d_UtilitySprayPaintMarks", (1.5, G, 6.2), UP, 0.6, 0.6, 5)
# rust weeping down the canopy columns from the brackets
for (cx, cz), (nx, nz), mat in zip(((10, 7), (22, 7), (10, 14), (22, 14)), ((0, -1), (1, 0), (-1, 0), (0, 1)),
		("M07a_RustStreaks", "M07b_RustStreaks", "M07d_RustStreaks", "M07c_RustStreaks")):
	dirt.decal(mat, (cx + nx * 0.2, 3.7, cz + nz * 0.2), (nx, 0, nz), 0.36, 1.5)
	dirt.decal("M07c_RustStreaks", (cx - nz * 0.2, 0.75, cz + nx * 0.2), (-nz, 0, nx), 0.3, 1.1)
# the car is bleeding something that is not motor oil (the last scene of the game comes back to this)
dirt.decal("L16bc_BlackOilDrips", (18.9, PAD, 10.9), UP, 1.3, 1.3, 135, lift=0.006)
dirt.decal("L16bb_BlackOilDrips", (17.8, PAD, 10.75), UP, 0.7, 0.7, 40, lift=0.007)

# --- the walls weather --------------------------------------------------------
dirt.decal("Leaking002", (32.0, 3.1, 26.5), (1, 0, 0), 3.2, 1.6)
dirt.decal("Leaking007", (32.0, 3.2, 21.3), (1, 0, 0), 2.4, 0.8)
dirt.decal("Leaking005", (14.0, 3.05, 25.2), (-1, 0, 0), 3.4, 1.7)
dirt.decal("Leaking002", (19.4, 3.1, 30.0), (0, 0, 1), 3.2, 1.6)
dirt.decal("Leaking005", (26.0, 3.0, 30.0), (0, 0, 1), 3.0, 1.5)
dirt.decal("M13a_WallCracks", (25.4, 1.7, 30.0), (0, 0, 1), 1.5, 1.5)
dirt.decal("M13d_WallCracks", (32.0, 1.2, 28.4), (1, 0, 0), 1.2, 1.2)
dirt.decal("M05a_CeilingMould", (18.9, 0.5, 30.0), (0, 0, 1), 1.0, 1.0, 180)
dirt.decal("M05c_CeilingMould", (30.6, 0.45, 30.0), (0, 0, 1), 0.9, 0.9, 180)
dirt.decal("M35a_Graffiti", (19.75, 1.45, 30.0), (0, 0, 1), 1.55, 0.37, rect=IT_HUMS)      # the arrow points at the machine

# --- 1. the machine that walked ------------------------------------------------
# What is missing has to be seen: a soffit lamp over the empty place, a wall lamp along the way it went.
shell.cyl("lamp_warm", (31.3, 2.83, 19.6), (31.3, 2.85, 19.6), 0.1, seg=10)
light("door", (31.3, 2.6, 19.45))
shell.box(STEEL, (32.0, 3.0, 26.35), (32.16, 3.2, 26.65), bevel=0.01)
shell.quad("lamp_warm", (32.09, 2.995, 26.5), (0, -1, 0), 0.12, 0.24, up=(0, 0, 1))
light("yard", (32.55, 2.85, 26.5))
dirt.quad("clean_patch", (31.45, F + 0.003, 19.58), UP, 1.0, 0.8, up=(0, 0, 1))
for x in (31.03, 31.87):
	for z in (19.26, 19.9):
		dirt.cyl("rust_ring", (x, F + 0.004, z), (x, F + 0.006, z), 0.045, seg=8)
shell.box("Plastic010", (31.6, F + 0.32, 19.985), (31.72, F + 0.44, 20.0))                   # the outlet it was plugged into
shell.tube("black", [(31.66, F + 0.38, 19.98), (31.7, F + 0.12, 19.9), (31.95, F + 0.02, 19.75), (32.3, F + 0.015, 19.55),
		(32.72, F + 0.015, 19.62)], 0.012, seg=5)
shell.box("black", (32.7, F + 0.005, 19.59), (32.78, F + 0.035, 19.65))                       # the plug, pins bent
route = [(31.6, 19.55), (32.9, 19.5), (33.45, 21.4), (33.5, 24.2), (33.6, 27.0), (34.7, 29.6), (34.6, 31.9), (32.2, 32.2),
		(29.2, 31.9), (26.2, 31.5), (23.6, 31.1), (22.0, 30.95)]
trail("M23d_ApplianceDragMarks", route[:2], 1.2, 1.5, F)
trail("M23d_ApplianceDragMarks", route[1:], 1.2, 2.4, G)
for (x, z, yaw) in ((33.5, 25.5, 10), (34.7, 30.8, 80), (30.6, 32.1, 200), (25.0, 31.3, 260)):
	dirt.decal("L16bc_BlackOilDrips", (x, G, z), UP, 1.1, 1.1, yaw, lift=0.007)
dirt.decal("L16ba_BlackOilDrips", (21.6, G, 31.35), UP, 1.25, 1.25, 15, lift=0.007)
dirt.decal("L16ab_BlackOilDrips", (21.6, 0.62, 30.892), (0, 0, 1), 0.7, 0.7, lift=0.003)     # it leaks down its own front

# --- 2. the clerk's smoking spot -----------------------------------------------
alley.box("crate_blue", (30.2, G, 30.45), (30.56, G + 0.3, 30.78), m=turn("y", -12, (30.38, 0, 30.6)), skip="-y")
alley.cyl("ash_can", (30.9, G, 30.5), (30.9, G + 0.17, 30.5), 0.08, seg=10)
alley.cyl("paper", (30.72, G, 30.82), (30.72, G + 0.11, 30.82), 0.04, seg=8, r2=0.045)       # coffee, gone cold
alley.cyl("paper", (30.6, G + 0.305, 30.62), (30.69, G + 0.31, 30.6), 0.004, seg=4)          # one left burning on the crate
dirt.decal("M31_LitterAtlas", (30.75, G, 30.75), UP, 0.9, 0.9, 30)
alley.box(STEEL, (29.3, 2.32, 30.0), (29.6, 2.5, 30.14), bevel=0.01)                         # bulkhead lamp over the back door
alley.quad("lamp_warm", (29.45, 2.315, 30.07), (0, -1, 0), 0.22, 0.1, up=(0, 0, 1))
light("door", (29.45, 2.2, 30.45))

# --- the bins end of the alley: enough light to find the pipe by ---------------------
alley.box(STEEL, (17.85, 3.0, 30.0), (18.15, 3.2, 30.16), bevel=0.01)
alley.quad("lamp_warm", (18.0, 2.995, 30.09), (0, -1, 0), 0.24, 0.12, up=(0, 0, 1))
light("yard", (18.0, 2.85, 30.5))
# a yard lamp on a pole in the fence corner: the wall lamps do not reach the bins
alley.cyl(STEEL, (12.25, G, 37.75), (12.25, 3.5, 37.75), 0.045, seg=8)
alley.tube(STEEL, [(12.25, 3.5, 37.75), (12.4, 3.62, 37.6), (12.8, 3.55, 37.25)], 0.03, seg=6, steps=3)
alley.box(STEEL, (12.7, 3.42, 37.1), (13.0, 3.54, 37.4), bevel=0.02, m=turn("y", 45, (12.85, 0, 37.25)))
alley.quad("lamp_warm", (12.85, 3.415, 37.25), (0, -1, 0), 0.2, 0.2, up=(0, 0, 1))
light("yard", (13.1, 3.2, 37.0))
for x, z, length, yaw, tilt in ((15.4, 35.3, 1.9, 8, 0), (15.9, 35.55, 1.5, -14, 0), (16.5, 35.2, 2.1, 30, 0), (16.2, 35.8, 1.2, 75, 0)):
	m = turn("y", yaw, (x, 0, z))
	alley.cyl("PaintedMetal016", (x, G + 0.03, z), (x + length, G + 0.03, z), 0.024, seg=6, m=m)
alley.cyl("PaintedMetal016", (16.85, G + 0.02, 36.3), (16.95, 1.3, 36.38), 0.024, seg=6)       # one leaning on the bin
alley.cyl(DARK, (22.9, G, 37.2), (22.9, G + 0.88, 37.2), 0.29, seg=14)                       # waste-oil drum
alley.cyl("black", (22.9, G + 0.88, 37.2), (22.9, G + 0.9, 37.2), 0.3, seg=14)
dirt.decal("M07b_RustStreaks", (22.9, G + 0.45, 36.91), (0, 0, -1), 0.3, 0.8, lift=0.003)
solid((22.6, G, 36.9), (23.2, G + 0.9, 37.5))
dirt.decal("L16bd_BlackOilDrips", (22.75, G, 36.5), UP, 1.2, 1.2, 200)
for i, (dx, dz, yaw) in enumerate(((0.0, 0.0, 3), (0.05, -0.04, -6), (-0.03, 0.03, 9))):       # flattened cartons by the gate
	alley.box("cardboard", (31.0 + dx, G + i * 0.03, 36.5 + dz), (32.3 + dx, G + 0.028 + i * 0.03, 37.6 + dz),
			m=turn("y", yaw, (31.6, 0, 37.0)))
dirt.decal("M28_DrinkSpills", (19.0, G, 36.0), UP, 1.0, 1.0, 0, QUARTERS[3])

# --- shop front: what a store sticks on its door ---------------------------------
for key, x, y, w, h in (("no_restroom", 21.5, F + 1.72, 0.3, 0.2), ("camera", 21.5, F + 1.48, 0.3, 0.22), ("we_id", 21.5, F + 1.24, 0.3, 0.2),
		("hours", 24.0, F + 1.95, 0.3, 0.33)):
	dirt.decal("N10_DoorWindowStickers", (x, y, 20.0), (0, 0, -1), w, h, rect=STICKERS[key], lift=0.003)
dirt.quad("N19_CommunityFlyersYardSale", (24.02, F + 1.5, 19.996), (0, 0, -1), 0.3, 0.42, rect=FLYERS[4])     # the power company's notice
dirt.quad("N19_CommunityFlyersYardSale", (23.74, F + 1.47, 19.996), (0, 0, -1), 0.24, 0.34, rect=FLYERS[2],
		m=turn("z", 4, (23.74, F + 1.47, 19.996)))
dirt.decal("M28_DrinkSpills", (17.2, F, 18.95), UP, 0.8, 0.8, 0, QUARTERS[0])
dirt.decal("M31_LitterAtlas", (24.5, F, 19.1), UP, 0.9, 0.9, 200)
dirt.decal("M28_DrinkSpills", (18.45, F, 25.6), UP, 1.0, 1.0, 90, QUARTERS[2])               # among what fell off the shelves
dirt.quad("N12_SafetySigns", (27.5, F + 2.3, 25.935), (0, 0, -1), 0.34, 0.3, rect=SIGNS["employees"])    # over the staff-room door
dirt.quad("N12_SafetySigns", (32.004, 1.9, 23.0), (1, 0, 0), 0.4, 0.34, rect=SIGNS["flammable"])         # on the propane cage wall
dirt.quad("N12_SafetySigns", (10.205, 2.0, 7.0), (1, 0, 0), 0.36, 0.3, rect=SIGNS["no_smoking"])         # on a canopy column

# --- 3. the staff room -------------------------------------------------------------
# shelving on the back wall, clear of the back door
for x in (27.0, 28.76):
	fit.box(WHITE, (x, F, 29.32), (x + 0.04, F + 1.9, 29.78))
for y in (0.12, 0.62, 1.12, 1.62):
	fit.box(WHITE, (27.0, F + y, 29.32), (28.8, F + y + 0.025, 29.78))
for (x, y, w, h), mat in zip(((27.08, 0.145, 0.5, 0.36), (27.65, 0.145, 0.42, 0.3), (28.15, 0.145, 0.55, 0.4), (27.1, 0.645, 0.6, 0.3),
		(27.85, 0.645, 0.36, 0.28), (28.3, 0.645, 0.4, 0.38), (27.2, 1.145, 0.45, 0.32), (28.0, 1.145, 0.7, 0.26), (27.4, 1.645, 0.5, 0.22)),
		("cardboard", "crate_red", "cardboard", "cardboard", "crate_blue", "cardboard", "paper", "cardboard", "cardboard")):
	fit.box(mat, (x, F + y, 29.38), (x + w, F + y + h, 29.74), bevel=0.004)
solid((27.0, F, 29.32), (28.8, F + 1.9, 29.78))
# the fridge and the microwave on it: both pulled out of the wall, with a note
fit.box(WHITE, (31.28, F, 27.22), (31.76, F + 0.85, 27.74), bevel=0.012)
fit.box("Plastic010", (31.3, F + 0.85, 27.26), (31.76, F + 1.13, 27.72), bevel=0.01)
fit.box("glass_dark", (31.295, F + 0.89, 27.3), (31.3, F + 1.1, 27.58))
fit.box("display", (31.295, F + 0.93, 27.61), (31.3, F + 1.07, 27.7))
fit.quad("N23_HandwrittenNotes", (31.292, F + 1.0, 27.44), (-1, 0, 0), 0.2, 0.21, rect=NOTES[0], m=turn("x", -5, (31.292, F + 1.0, 27.44)))
fit.tube("black", [(31.74, F + 0.95, 27.74), (31.7, F + 0.5, 27.82), (31.5, F + 0.03, 27.9), (31.1, F + 0.02, 28.0)], 0.008, seg=5)
fit.tube("black", [(31.74, F + 0.3, 27.74), (31.72, F + 0.05, 27.84), (31.45, F + 0.02, 27.95), (31.0, F + 0.02, 27.86)], 0.008, seg=5)
fit.box("Plastic010", (31.78, F + 0.3, 27.9), (31.795, F + 0.42, 27.98))                       # the empty socket
solid((31.28, F, 27.22), (31.76, F + 1.13, 27.74))
# the security monitor on the desk, still on, showing nothing
fit.box("Plastic010", (31.22, F + 0.75, 26.36), (31.64, F + 1.08, 26.74), bevel=0.015)
fit.quad("screen", (31.215, F + 0.93, 26.55), (-1, 0, 0), 0.3, 0.24)
fit.box(DARK, (30.86, F + 0.75, 26.38), (31.2, F + 0.83, 26.66), bevel=0.006)                  # recorder
fit.quad("led_red", (30.9, F + 0.79, 26.661), (0, 0, 1), 0.012, 0.012)
light("cooler", (30.7, F + 1.0, 26.6))
# time clock and card rack by the door, what the walls say
fit.box(DARK, (28.45, F + 1.3, 26.07), (28.7, F + 1.62, 26.17), bevel=0.01)
fit.quad("display", (28.575, F + 1.52, 26.172), (0, 0, 1), 0.14, 0.06)
fit.box(STEEL, (28.8, F + 1.2, 26.07), (29.25, F + 1.7, 26.1))
for i in range(5):
	for j in range(2):
		fit.quad("paper", (28.89 + j * 0.22, F + 1.27 + i * 0.09, 26.102), (0, 0, 1), 0.16, 0.07)
fit.quad("E54_RotaAndLabourPoster", (30.95, F + 1.55, 26.07), (0, 0, 1), 0.66, 0.46, rect=ROTA, m=turn("z", -2, (30.95, F + 1.55, 26.07)))
fit.quad("E54_RotaAndLabourPoster", (30.05, F + 1.6, 26.07), (0, 0, 1), 0.44, 0.54, rect=RIGHTS)
fit.quad("E54_RotaAndLabourPoster", (29.6, F + 2.2, 26.07), (0, 0, 1), 0.5, 0.26, rect=CUSTOMER_FIRST)
fit.quad("E54_RotaAndLabourPoster", (26.07, F + 1.6, 27.4), (1, 0, 0), 0.5, 0.26, rect=WASH_HANDS)
fit.quad("N16_Calendars", (31.795, F + 1.62, 26.75), (-1, 0, 0), 0.36, 0.43, rect=CALENDAR)
for k, (x, z, yaw) in enumerate(((30.62, 26.95, 20), (30.9, 26.98, -12))):
	fit.quad("N13_ReceiptsTickets", (x, F + 0.754, z), UP, 0.08, 0.15, rect=RECEIPTS[3 + k], up=(math.sin(math.radians(yaw)), 0, math.cos(math.radians(yaw))))
dirt.decal("M28_DrinkSpills", (30.2, F, 27.6), UP, 0.7, 0.7, 40, QUARTERS[2])                # coffee went over when the chair went back

for part in (ground, canopy, pumps, pylon, shell, fit, alley, car, col, dirt):
	part.finish()

HERE = os.path.dirname(os.path.abspath(__file__))
os.makedirs(os.path.join(lotkit.ROOT, "fps", "assets", "town"), exist_ok=True)
lotkit.save(os.path.join(HERE, "gas_station.blend"), os.path.join(lotkit.ROOT, "fps", "assets", "town", "gas_station.glb"))

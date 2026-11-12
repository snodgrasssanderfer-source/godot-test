class_name TownLayout
extends RefCounted
## The demo town of the design document (docs/GDD.md, section 6), as data: a
## 250 x 250 m map with one gently curving main street running north-south, a
## short cross street, and four buildings that can be entered - the gas
## station and its store, Miller's hardware and auto shop across the road, the
## tavern and laundromat on the corner, and the Clark house at the far end.
## Everything else is solid filler.
##
## World axes: +X east, +Z south, 1 unit = 1 m. Positions along the street are
## given as s, which is as good as world z (the street bends by 13 m over its
## whole length). Each lot has its own frame: origin on the back edge of the
## pavement, +Z into the lot, +X along the street front (southwards on the
## west side, northwards on the east side), sizes in metres.

const O := TownBuilder.Opening
const HALF := 125.0
## How far the middle of the street bulges east of its two ends.
const BULGE := 13.0
const ROAD_HALF := 3.6
const WALK := 2.4
const CURB := 0.17
## Centre line to the back edge of the pavement.
const LOT_OFFSET := ROAD_HALF + WALK
## How far a lot's ground slab reaches back under the pavement.
const UNDER := 2.3
const SHOP_FLOOR := 0.17
const SHOP_HEIGHT := 3.4
const HOUSE_FLOOR := 0.6
const STOREY := 2.7
const CROSS_HALF := 3.0            # half width of the cross street's roadway
const CROSS_REACH := 51.0          # how far it runs either side of the main street
const WEST := -1.0
const EAST := 1.0
## Where the pavement drops to road level for cars: [side, s from, s to].
const DRIVEWAYS := [
	[WEST, -98.0, -89.0], [WEST, -71.0, -62.0],         # gas station, in and out
	[EAST, -86.0, -78.0],                               # Miller's yard
	[WEST, 93.0, 97.6],                                 # the Clarks' drive
]
## Utility poles: s, side, has a street lamp.
const POLES := [
	[-108.0, WEST, true], [-90.0, EAST, false], [-72.0, WEST, true], [-54.0, EAST, true], [-36.0, WEST, false],
	[-18.0, EAST, true], [9.0, WEST, true], [27.0, EAST, false], [45.0, WEST, true], [63.0, EAST, true],
	[81.0, WEST, false], [99.0, EAST, true], [110.0, WEST, true],
]


# --- the street's shape ----------------------------------------------------------

static func centre(s: float) -> Vector3:
	return Vector3(BULGE * (1.0 - (s / HALF) * (s / HALF)), 0.0, s)


## Angle of the street's direction, measured from south (+Z) towards east.
static func heading(s: float) -> float:
	return atan(-2.0 * BULGE * s / (HALF * HALF))


static func east_of(s: float) -> Vector3:
	var a := heading(s)
	return Vector3(cos(a), 0.0, -sin(a))


## A point beside the street: `across` metres east of the centre line.
static func beside(s: float, across: float, y := 0.0) -> Vector3:
	return centre(s) + east_of(s) * across + Vector3.UP * y


## The frame of a lot on one side of the street spanning s0..s1.
static func lot_frame(side: float, s0: float, s1: float) -> Transform3D:
	var mid := (s0 + s1) * 0.5
	var z := east_of(mid) * side
	var x := Vector3.UP.cross(z)
	return Transform3D(Basis(x, Vector3.UP, z), centre(mid) + z * LOT_OFFSET - x * ((s1 - s0) * 0.5))


# --- the whole town --------------------------------------------------------------

## Fills the builder. Returns what is not boxes: {lights: [...], spawn: Transform3D}.
static func build(b: TownBuilder) -> Dictionary:
	var lights: Array[Dictionary] = []
	_street(b, lights)
	_gas_station(b, lights)
	_miller(b, lights)
	_brick_row(b, lights)
	_clark(b, lights)
	_fillers(b)
	_ends_and_edges(b)
	# The pit in Miller's workshop goes below ground level, so the terrain needs a hole there.
	var miller := b.find_lot("miller")
	var hole := AABB(miller.to_world(Vector3(7.2, 0.0, 18.3)), Vector3.ZERO)
	for corner: Vector3 in [Vector3(8.7, 0, 18.3), Vector3(8.7, 0, 25.7), Vector3(7.2, 0, 25.7)]:
		hole = hole.expand(miller.to_world(corner))
	hole = hole.grow(0.25)
	var ground := b.add_lot("terrain", Transform3D.IDENTITY)
	var x0 := -150.0
	var x1 := 170.0
	var z0 := -150.0
	var z1 := 150.0
	var hx0 := hole.position.x
	var hx1 := hole.end.x
	var hz0 := hole.position.z
	var hz1 := hole.end.z
	ground.box(&"terrain", Vector3(x0, -2.0, z0), Vector3(x1, 0.0, hz0))
	ground.box(&"terrain", Vector3(x0, -2.0, hz1), Vector3(x1, 0.0, z1))
	ground.box(&"terrain", Vector3(x0, -2.0, hz0), Vector3(hx0, 0.0, hz1))
	ground.box(&"terrain", Vector3(hx1, -2.0, hz0), Vector3(x1, 0.0, hz1))
	ground.box(&"terrain", Vector3(x0, -2.6, z0), Vector3(x1, -2.0, z1))

	var station := b.find_lot("gas_station")
	var spawn := Transform3D(Basis(Vector3.UP, station.xform.basis.get_euler().y + PI), station.to_world(Vector3(15.8, 0.1, 8.95)))
	return {"lights": lights, "spawn": spawn}


static func _light(lights: Array[Dictionary], lot: TownBuilder.Lot, at: Vector3, kind: StringName, energy: float,
		color: Color, reach: float) -> void:
	lights.append({"kind": kind, "at": lot.to_world(at), "energy": energy, "color": color, "range": reach})


# --- street ----------------------------------------------------------------------

static func _driveway(side: float, s: float) -> bool:
	for d: Array in DRIVEWAYS:
		if d[0] == side and s >= d[1] and s <= d[2]:
			return true
	return false


static func _street(b: TownBuilder, lights: Array[Dictionary]) -> void:
	var lot := b.add_lot("street", Transform3D.IDENTITY, "主街")
	lot.set_level(0, 0.02)
	const STEP := 3.0
	var s := -HALF
	var i := 0
	while s < HALF:
		var mid := s + STEP * 0.5
		var yaw := rad_to_deg(heading(mid))
		lot.rbox(&"asphalt", beside(mid, 0.0, -0.13), Vector3(ROAD_HALF * 2.0, 0.3, STEP + 0.08), Vector3(0, yaw, 0))
		if i % 4 == 0:
			lot.rbox(&"road_paint", beside(mid, 0.0, 0.023), Vector3(0.12, 0.01, 2.4), Vector3(0, yaw, 0), false).plan = false
		if absf(mid) > CROSS_HALF + 1.5 + STEP * 0.5:                   # the cross street cuts through the pavements
			for side: float in [WEST, EAST]:
				var top := 0.055 if _driveway(side, mid) else CURB
				lot.rbox(&"sidewalk", beside(mid, side * (ROAD_HALF + WALK * 0.5), top - 0.15),
						Vector3(WALK, 0.3, STEP + 0.08), Vector3(0, yaw, 0))
		s += STEP
		i += 1

	# The cross street at s = 0, where the main street runs due north-south.
	var cx := centre(0.0).x
	lot.box(&"asphalt", Vector3(cx - CROSS_REACH, -0.28, -CROSS_HALF), Vector3(cx + CROSS_REACH, 0.015, CROSS_HALF))
	for z_side: float in [-1.0, 1.0]:
		for x_side: float in [-1.0, 1.0]:
			lot.box(&"sidewalk", Vector3(cx + x_side * ROAD_HALF, -0.13, z_side * CROSS_HALF),
					Vector3(cx + x_side * CROSS_REACH, CURB, z_side * (CROSS_HALF + 1.5)))
	# Fill the four corners, where neither street's pavement reaches.
	for z_side: float in [-1.0, 1.0]:
		for x_side: float in [-1.0, 1.0]:
			lot.box(&"sidewalk", Vector3(cx + x_side * ROAD_HALF, -0.13, z_side * (CROSS_HALF + 1.5)),
					Vector3(cx + x_side * (LOT_OFFSET + 0.3), CURB, z_side * 6.2))
	# Both ends are shut: concrete barriers, a chain fence behind them.
	for x_side: float in [-1.0, 1.0]:
		var end := cx + x_side * (CROSS_REACH - 3.0)
		for k in 4:
			lot.box(&"concrete", Vector3(end - 0.3, 0.0, -4.4 + k * 2.3), Vector3(end + 0.3, 0.85, -4.4 + k * 2.3 + 2.0))
		lot.box(&"fence", Vector3(end + x_side * 1.5 - 0.04, 0.0, -9.0), Vector3(end + x_side * 1.5 + 0.04, 2.4, 9.0))
		lot.box(&"fence", Vector3(end + x_side * 2.5 - 0.5, 0.0, -30.0), Vector3(end + x_side * 2.5 + 0.5, 6.0, 30.0), true, false).plan = false
		lot.marker(StringName("cross_end_%s" % ("w" if x_side < 0 else "e")), Vector3(end, 1.0, 0.0), "road closed", "横街封死")

	# Poles along the curb; every other one carries a lamp over the road.
	for p: Array in POLES:
		var ps: float = p[0]
		var side: float = p[1]
		var yaw := rad_to_deg(heading(ps))
		var foot := beside(ps, side * (ROAD_HALF + 0.45))
		lot.rbox(&"pole", foot + Vector3.UP * 4.6, Vector3(0.26, 9.2, 0.26), Vector3(0, yaw, 0))
		lot.rbox(&"pole", foot + Vector3.UP * 8.6, Vector3(2.2, 0.12, 0.12), Vector3(0, yaw, 0), false).plan = false
		if p[2]:
			var over := beside(ps, side * (ROAD_HALF - 1.5), 7.0)
			lot.rbox(&"metal", (foot + Vector3.UP * 7.1 + over) * 0.5 + Vector3.UP * 0.05,
					Vector3(2.0, 0.07, 0.07), Vector3(0, yaw, 0), false).plan = false
			lights.append({"kind": &"street", "at": over, "energy": 18.0, "color": Color(1.0, 0.50, 0.14), "range": 24.0})
			lot.marker(StringName("lamp_%d" % int(ps)), foot + Vector3.UP * 0.2, "street lamp", "路灯")


# --- 1. gas station and store (west, north end): the start -------------------------

static func _gas_station(b: TownBuilder, lights: Array[Dictionary]) -> void:
	var lot := b.add_lot("gas_station", lot_frame(WEST, -101.0, -55.0), "北线加油站 & 夜班便利店（起点 / 安全屋）")
	lot.set_level(0, SHOP_FLOOR)
	lot.box(&"concrete", Vector3(0, -0.3, -UNDER), Vector3(46, 0.03, 40))

	# Canopy over two pump islands; the police car stands in the lane between them.
	lot.box(&"canopy", Vector3(8.5, 4.6, 5.5), Vector3(23.5, 5.1, 15.5))
	for c: Vector2 in [Vector2(10, 7), Vector2(22, 7), Vector2(10, 14), Vector2(22, 14)]:
		lot.box(&"metal", Vector3(c.x - 0.2, 0.03, c.y - 0.2), Vector3(c.x + 0.2, 4.6, c.y + 0.2))
	for z: float in [7.4, 12.6]:
		lot.box(&"concrete", Vector3(12, 0.03, z), Vector3(20, 0.2, z + 1.0))
		for x: float in [13.5, 17.7]:
			lot.box(&"appliance", Vector3(x, 0.2, z + 0.2), Vector3(x + 0.8, 1.9, z + 0.8))
	for x: float in [11.0, 16.0, 21.0]:
		for z: float in [8.0, 13.0]:
			_light(lights, lot, Vector3(x, 4.35, z), &"tube", 2.6, Color(0.82, 0.92, 1.0), 9.0)
	# Police car, nose to the south (+X).
	lot.box(&"car", Vector3(13.6, 0.28, 9.6), Vector3(18.4, 0.98, 11.5))
	lot.box(&"car_white", Vector3(14.9, 0.98, 9.7), Vector3(17.3, 1.48, 11.4))
	lot.box(&"sign", Vector3(15.9, 1.48, 9.9), Vector3(16.3, 1.6, 11.2), false).plan = false
	lot.marker(&"police_car", Vector3(16.0, 1.7, 10.55), "police car: start, workbench on the bonnet", "警车（起点；引擎盖 = 改装台）")
	lot.marker(&"flat_tyre", Vector3(17.6, 0.5, 11.7), "flat tyre: jack + spare", "右前轮瘪胎（千斤顶换胎）")
	lot.marker(&"car_trunk", Vector3(13.4, 1.1, 10.55), "trunk: shotgun, duty bag", "后备箱（霰弹枪、勤务包）")
	# Price sign by the road.
	lot.box(&"metal", Vector3(2.9, 0.03, 1.5), Vector3(3.2, 7.0, 1.8))
	lot.box(&"sign", Vector3(1.5, 4.6, 1.55), Vector3(4.6, 6.9, 1.75), false)

	# The store: 18 x 10 m, sales floor with a staff room in the back corner.
	var f := SHOP_FLOOR
	var top := f + SHOP_HEIGHT
	lot.box(&"sidewalk", Vector3(13, 0.03, 18.6), Vector3(33, f, 20))
	lot.box(&"floor_tile", Vector3(14, 0.03, 20), Vector3(32, f, 30))
	lot.wall_x(&"block", 20.1, 14, 32, f, top, 0.2, [[O.WINDOW, 15.0, 6.0, 0.6, 2.6], [O.DOOR, 22.0, 1.6, 0.0, 2.2],
			[O.WINDOW, 25.0, 6.0, 0.6, 2.6]])
	lot.wall_x(&"block", 29.9, 14, 32, f, top, 0.2, [[O.DOOR_CLOSED, 29.0, 0.9]])
	lot.wall_z(&"block", 14.1, 20, 30, f, top)
	lot.wall_z(&"block", 31.9, 20, 30, f, top)
	lot.box(&"roof", Vector3(13.7, top, 19.3), Vector3(32.3, top + 0.25, 30.3))
	lot.wall_x(&"wall_in", 26.0, 26, 31.8, f, top, 0.12, [[O.DOOR, 27.0, 1.0]])
	lot.wall_z(&"wall_in", 26.0, 26, 29.8, f, top, 0.12)
	for x: float in [16.6, 19.2]:
		lot.box(&"shelf", Vector3(x, f, 22.5), Vector3(x + 0.9, f + 1.5, 28.0))
	lot.box(&"shelf", Vector3(24.4, f, 23.6), Vector3(25.3, f + 1.5, 25.6))
	lot.box(&"appliance", Vector3(14.2, f, 21.0), Vector3(15.0, f + 2.1, 29.0))             # coolers, side wall
	lot.box(&"appliance", Vector3(15.3, f, 29.0), Vector3(25.5, f + 2.1, 29.8))             # coolers, back wall
	lot.box(&"counter", Vector3(26.4, f, 22.6), Vector3(30.6, f + 1.05, 23.3))              # checkout
	lot.box(&"counter", Vector3(29.6, f, 20.35), Vector3(31.7, f + 0.95, 21.0))             # coffee station
	lot.box(&"furniture", Vector3(30.3, f, 26.3), Vector3(31.7, f + 0.75, 27.1))            # staff desk
	lot.box(&"metal", Vector3(26.2, f, 28.2), Vector3(26.7, f + 1.8, 29.7))                 # lockers
	for p: Vector2 in [Vector2(17.5, 22.5), Vector2(17.5, 27.0), Vector2(23.0, 24.5), Vector2(28.5, 21.6)]:
		_light(lights, lot, Vector3(p.x, top - 0.3, p.y), &"tube", 1.6, Color(0.85, 0.93, 1.0), 8.0)
	_light(lights, lot, Vector3(29.0, top - 0.3, 28.0), &"bulb", 0.8, Color(1.0, 0.82, 0.6), 5.0)
	lot.marker(&"store_door", Vector3(22.8, f + 0.1, 20.1), "store entrance", "便利店入口")
	lot.marker(&"coffee", Vector3(30.6, f + 1.2, 20.7), "coffee pot (still steaming)", "咖啡壶")
	lot.marker(&"paper_map", Vector3(31.0, f + 1.0, 26.7), "hand-drawn flyer map", "手绘宣传单地图")
	lot.marker(&"trunk_key", Vector3(26.45, f + 1.4, 29.0), "trunk key", "后备箱钥匙")
	lot.marker(&"staff_room", Vector3(29.0, f + 0.1, 28.0), "staff room", "员工室")

	# Back alley: bins behind a chain fence, reached round the south side of the store.
	lot.wall_x(&"fence", 38.0, 12, 34, 0.03, 2.1, 0.06)
	lot.wall_z(&"fence", 12.0, 30, 38, 0.03, 2.1, 0.06)
	lot.wall_x(&"fence", 30.0, 12, 14, 0.03, 2.1, 0.06)
	lot.wall_z(&"fence", 34.0, 30, 38, 0.03, 2.1, 0.06, [[O.DOOR, 31.0, 2.0, 0.0, 2.1]])
	lot.wall_x(&"fence", 30.0, 32, 34, 0.03, 2.1, 0.06)
	lot.box(&"metal", Vector3(15.0, 0.03, 36.4), Vector3(16.8, 1.35, 37.6))
	lot.box(&"metal", Vector3(17.4, 0.03, 36.4), Vector3(19.2, 1.35, 37.6))
	lot.box(&"appliance", Vector3(21.0, 0.03, 30.1), Vector3(22.2, 2.0, 30.9))
	_light(lights, lot, Vector3(24.0, 3.2, 30.4), &"bulb", 1.0, Color(1.0, 0.7, 0.4), 7.0)
	lot.marker(&"pipe", Vector3(16.0, 0.4, 35.6), "rusty pipe", "生锈水管")
	lot.marker(&"first_creature", Vector3(21.6, 1.2, 31.4), "ice chest / vending machine: first creature (tutorial fight)", "冰柜 / 售货机怪（教学战）")
	lot.marker(&"back_alley", Vector3(24.0, 0.2, 34.0), "back alley, bins", "后巷垃圾站")


# --- 2. Miller & Sons hardware and auto repair (east, across from the station) ------

static func _miller(b: TownBuilder, lights: Array[Dictionary]) -> void:
	var lot := b.add_lot("miller", lot_frame(EAST, -92.0, -62.0), "米勒父子五金与汽修行")
	var f := SHOP_FLOOR
	lot.set_level(0, f)
	# Ground: the yard to the north, and strips round the building (which has its own floors).
	lot.box(&"concrete", Vector3(15, -0.3, -UNDER), Vector3(30, 0.03, 34))
	lot.box(&"concrete", Vector3(0, -0.3, -UNDER), Vector3(15, 0.03, 1))
	lot.box(&"concrete", Vector3(0, -0.3, 1), Vector3(1, 0.03, 34))
	lot.box(&"concrete", Vector3(1, -0.3, 31), Vector3(15, 0.03, 34))

	# Front shop, 12 x 14 m: tall shelving, narrow aisles.
	var top := f + SHOP_HEIGHT
	lot.box(&"floor", Vector3(1, -0.3, 1), Vector3(15, f, 15))
	lot.wall_x(&"brick", 1.1, 2, 14, f, top, 0.2, [[O.WINDOW, 3.0, 3.6, 0.7, 2.5], [O.DOOR, 7.4, 1.2, 0.0, 2.1],
			[O.WINDOW, 9.4, 3.6, 0.7, 2.5]])
	lot.wall_z(&"brick", 2.1, 1, 15, f, top)
	lot.wall_z(&"brick", 13.9, 1, 15, f, top)
	lot.box(&"roof", Vector3(1.7, top, 0.1), Vector3(14.3, top + 0.2, 14.9))              # the front edge is the awning
	for x: float in [2.9, 4.6, 6.3, 9.1, 10.8, 12.5]:
		lot.box(&"shelf", Vector3(x, f, 3.0), Vector3(x + 0.6, f + 2.6, 12.0))
	lot.box(&"counter", Vector3(3.0, f, 13.2), Vector3(7.0, f + 1.05, 13.9))
	for z: float in [4.5, 10.5]:
		_light(lights, lot, Vector3(8.0, top - 0.3, z), &"tube", 1.1, Color(1.0, 0.9, 0.7), 7.0)
	lot.marker(&"miller_door", Vector3(8.0, f + 0.1, 1.1), "hardware shop entrance", "五金店入口")
	lot.marker(&"shelf_ambush", Vector3(8.0, f + 2.9, 7.5), "small creatures drop from the shelf tops", "小型怪从货架顶上扑下")

	# Workshop behind it, 14 x 16 m and 5.3 m high, with the inspection pit.
	var roof := f + 5.3
	lot.box(&"concrete", Vector3(1, -0.3, 15), Vector3(7.4, f, 31))
	lot.box(&"concrete", Vector3(8.5, -0.3, 15), Vector3(15, f, 31))
	lot.box(&"concrete", Vector3(7.4, -0.3, 15), Vector3(8.5, f, 18.5))
	lot.box(&"concrete", Vector3(7.4, -0.3, 25.5), Vector3(8.5, f, 31))
	lot.box(&"oil", Vector3(7.2, -1.6, 18.3), Vector3(8.7, f - 1.5, 25.7))                 # pit floor, 1.5 m down
	lot.box(&"concrete", Vector3(7.2, -1.6, 18.3), Vector3(7.4, -0.3, 25.7))
	lot.box(&"concrete", Vector3(8.5, -1.6, 18.3), Vector3(8.7, -0.3, 25.7))
	lot.box(&"concrete", Vector3(7.2, -1.6, 18.3), Vector3(8.7, -0.3, 18.5))
	lot.box(&"concrete", Vector3(7.2, -1.6, 25.5), Vector3(8.7, -0.3, 25.7))
	lot.stairs(&"concrete", Vector3(7.95, f - 1.5, 23.3), Vector3.BACK, 1.5, 2.2, 1.1)
	lot.wall_x(&"block", 15.0, 1, 15, f, roof, 0.2, [[O.DOOR, 10.0, 1.0], [O.WINDOW_OPEN, 2.5, 2.0, 3.63, 5.3]])
	lot.wall_x(&"block", 30.9, 1, 15, f, roof, 0.2, [[O.DOOR_CLOSED, 3.0, 0.9]])
	lot.wall_z(&"block", 1.1, 15, 31, f, roof)
	lot.wall_z(&"block", 14.9, 15, 31, f, roof, 0.2, [[O.SHUTTER, 17.5, 4.0, 0.0, 3.2]])
	lot.box(&"roof", Vector3(0.7, roof, 14.7), Vector3(15.3, roof + 0.25, 31.3))
	# A car over the pit, up on its wheels.
	lot.box(&"car", Vector3(6.95, 0.62, 18.6), Vector3(8.95, 1.25, 22.2))
	lot.box(&"car", Vector3(7.1, 1.25, 19.4), Vector3(8.8, 1.75, 21.6))
	for x: float in [6.95, 8.7]:
		for z: float in [18.9, 21.3]:
			lot.box(&"oil", Vector3(x, f, z), Vector3(x + 0.25, f + 0.66, z + 0.66))
	lot.box(&"shelf", Vector3(1.2, f, 23.6), Vector3(1.9, f + 1.05, 30.5))                 # workbench
	lot.box(&"metal", Vector3(11.0, f, 15.3), Vector3(14.6, f + 1.9, 16.0))                # tyre rack
	lot.wall_x(&"wall_in", 27.0, 10, 14.8, f, f + 3.0, 0.12, [[O.DOOR_CLOSED, 11.0, 0.9]])
	lot.wall_z(&"wall_in", 10.0, 27, 30.8, f, f + 3.0, 0.12)
	lot.box(&"ceiling", Vector3(9.94, f + 3.0, 26.94), Vector3(14.8, f + 3.1, 30.8))
	for p: Vector2 in [Vector2(4.0, 26.0), Vector2(11.5, 20.5)]:
		_light(lights, lot, Vector3(p.x, roof - 0.5, p.y), &"bulb", 1.3, Color(1.0, 0.78, 0.5), 10.0)
	_light(lights, lot, Vector3(7.95, f - 0.5, 20.0), &"bulb", 0.35, Color(1.0, 0.6, 0.3), 3.5)
	lot.marker(&"pit", Vector3(7.95, f - 1.4, 24.6), "inspection pit, 1.5 m deep (steps at this end)", "修车地沟（深 1.5 米，这头有台阶）")
	lot.marker(&"belt", Vector3(7.95, f - 1.3, 21.8), "alternator belt", "发电机皮带")
	lot.marker(&"jack", Vector3(7.95, f - 1.3, 20.4), "jack", "千斤顶")
	lot.marker(&"toolbox_mimic", Vector3(7.7, f - 1.3, 18.9), "red toolbox: a mimic", "红色工具箱（纯拟态怪）")
	lot.marker(&"storeroom", Vector3(12.5, f + 0.1, 29.0), "storeroom (locked: key is in the tavern)", "库房（锁着，钥匙在酒馆）")
	lot.marker(&"bay_door", Vector3(15.2, f + 0.1, 19.5), "vehicle door to the yard", "车间卷帘门")

	# Loft office over the south-west corner, open to the workshop, with a
	# window onto the shop's flat roof (the awning).
	var loft := f + 2.73
	lot.set_level(1, loft)
	lot.box(&"floor", Vector3(1.2, loft - 0.2, 15.1), Vector3(6.0, loft, 23.0))
	lot.box(&"metal", Vector3(5.94, loft, 15.1), Vector3(6.0, loft + 1.0, 23.0))
	lot.box(&"metal", Vector3(1.2, loft, 22.94), Vector3(4.4, loft + 1.0, 23.0))
	lot.box(&"furniture", Vector3(1.4, loft, 17.6), Vector3(2.2, loft + 0.75, 19.4))
	lot.box(&"metal", Vector3(1.4, loft, 20.2), Vector3(1.9, loft + 1.3, 21.6))
	lot.marker(&"loft", Vector3(3.6, loft + 0.1, 19.0), "loft office: overlooks the workshop", "账房阁楼（可俯射车间）")
	lot.marker(&"awning_window", Vector3(3.5, loft + 1.0, 15.0), "window out onto the shop roof", "翻窗上遮雨棚")
	_light(lights, lot, Vector3(3.5, loft + 1.9, 19.0), &"bulb", 0.7, Color(1.0, 0.8, 0.55), 5.0)
	lot.set_level(0, f)
	lot.stairs(&"metal", Vector3(5.2, f, 26.9), Vector3.FORWARD, 2.73, 3.9, 1.5, 0, true)

	# Yard: a dead car, tyres, chain fence.
	lot.box(&"car", Vector3(21.5, 0.3, 21.0), Vector3(23.4, 1.0, 25.4))
	lot.box(&"car", Vector3(21.6, 1.0, 22.2), Vector3(23.3, 1.5, 24.6))
	lot.box(&"oil", Vector3(26.0, 0.03, 28.0), Vector3(27.4, 1.0, 29.4))
	lot.wall_x(&"fence", 34.0, 0, 30, 0.03, 2.1, 0.06)
	lot.wall_z(&"fence", 30.0, 0, 34, 0.03, 2.1, 0.06)
	lot.wall_z(&"fence", 0.0, 1, 34, 0.03, 2.1, 0.06)


# --- 3. the brick row on the corner: tavern, laundromat, two dead shops -------------

static func _brick_row(b: TownBuilder, lights: Array[Dictionary]) -> void:
	var lot := b.add_lot("brick_row", lot_frame(EAST, 4.5, 50.0), "锈锚小酒馆 & 24 小时投币洗衣房")
	var f := SHOP_FLOOR
	var top := f + SHOP_HEIGHT
	var parapet := top + 0.7
	lot.set_level(0, f)
	lot.box(&"concrete", Vector3(0, -0.3, -UNDER), Vector3(47.5, 0.03, 26))        # runs on under the corner pavement
	# Two shut shops at the south end: solid, with dressed fronts.
	lot.box(&"brick", Vector3(0, 0.03, 0.3), Vector3(19.5, parapet, 16.3))
	for x: float in [2.0, 12.0]:
		lot.box(&"door", Vector3(x, f, 0.22), Vector3(x + 1.0, f + 2.1, 0.3), false)
		lot.box(&"glass", Vector3(x + 2.0, f + 0.7, 0.24), Vector3(x + 6.5, f + 2.5, 0.3), false)

	lot.box(&"floor_tile", Vector3(19.5, 0.03, 0.3), Vector3(31.5, f, 16.3))
	lot.box(&"floor", Vector3(31.5, 0.03, 0.3), Vector3(45.5, f, 16.3))
	lot.wall_x(&"brick", 0.4, 19.5, 45.5, f, parapet, 0.2, [
			[O.WINDOW, 20.3, 4.0, 0.5, 2.6], [O.DOOR, 24.9, 1.2, 0.0, 2.1], [O.WINDOW, 26.7, 4.0, 0.5, 2.6],
			[O.WINDOW, 32.5, 3.0, 1.0, 2.2], [O.DOOR, 37.0, 1.1, 0.0, 2.1], [O.WINDOW, 39.5, 5.0, 1.0, 2.2]])
	lot.wall_x(&"brick", 16.2, 19.5, 45.5, f, parapet, 0.2, [[O.DOOR_CLOSED, 22.0, 0.9], [O.DOOR_CLOSED, 43.0, 0.9]])
	lot.wall_z(&"brick", 19.6, 0.3, 16.3, f, parapet)
	lot.wall_z(&"brick", 31.5, 0.3, 16.3, f, parapet)
	lot.wall_z(&"brick", 45.4, 0.3, 16.3, f, parapet, 0.2, [[O.WINDOW, 3.0, 3.0, 1.0, 2.2], [O.WINDOW, 9.0, 3.0, 1.0, 2.2]])
	lot.box(&"roof", Vector3(19.5, top, 0.3), Vector3(45.5, top + 0.2, 16.3))

	# Laundromat: twelve front-loaders along the side walls, dryers behind them.
	for i in 6:
		var z := 1.6 + i * 0.85
		lot.box(&"appliance", Vector3(19.72, f, z), Vector3(20.47, f + 0.95, z + 0.75))
		lot.box(&"appliance", Vector3(30.63, f, z), Vector3(31.38, f + 0.95, z + 0.75))
	for i in 4:
		var z := 7.4 + i * 0.9
		lot.box(&"appliance", Vector3(19.72, f, z), Vector3(20.52, f + 1.9, z + 0.8))
		lot.box(&"appliance", Vector3(30.58, f, z), Vector3(31.38, f + 1.9, z + 0.8))
	for z: float in [3.6, 7.6]:
		lot.box(&"furniture", Vector3(24.5, f, z), Vector3(26.5, f + 0.9, z + 1.1))            # folding tables
	lot.box(&"appliance", Vector3(27.6, f, 12.6), Vector3(28.4, f + 1.9, 13.3))                # change machine
	lot.wall_x(&"wall_in", 13.5, 19.7, 31.4, f, top, 0.12, [[O.DOOR, 25.0, 1.0]])
	for z: float in [3.0, 9.0]:
		_light(lights, lot, Vector3(25.5, top - 0.3, z), &"tube", 1.8, Color(0.85, 0.95, 1.0), 8.0)
	lot.marker(&"laundry_door", Vector3(25.5, f + 0.1, 0.4), "laundromat entrance", "洗衣房入口")
	lot.marker(&"washers", Vector3(20.1, f + 1.2, 4.0), "washers spinning on their own; one is alive", "空转的洗衣机（真怪混在其中）")

	# Tavern: a U-shaped bar in the middle, booths down both sides, office at the back.
	lot.box(&"counter", Vector3(36.0, f, 6.0), Vector3(41.0, f + 1.1, 6.7))
	lot.box(&"counter", Vector3(36.0, f, 6.7), Vector3(36.7, f + 1.1, 11.0))
	lot.box(&"counter", Vector3(40.3, f, 6.7), Vector3(41.0, f + 1.1, 11.0))
	lot.box(&"shelf", Vector3(37.6, f, 9.4), Vector3(39.4, f + 1.9, 10.0))                     # back bar
	for i in 3:
		var z := 1.6 + i * 3.3
		for x0: float in [31.7, 44.0]:
			lot.box(&"furniture", Vector3(x0, f, z), Vector3(x0 + 1.3, f + 1.2, z + 0.5))
			lot.box(&"furniture", Vector3(x0 + 0.1, f, z + 0.95), Vector3(x0 + 1.2, f + 0.75, z + 1.65))
			lot.box(&"furniture", Vector3(x0, f, z + 2.1), Vector3(x0 + 1.3, f + 1.2, z + 2.6))
	lot.box(&"furniture", Vector3(33.4, f, 12.6), Vector3(35.8, f + 0.8, 13.9))                # pool table
	lot.box(&"appliance", Vector3(36.6, f, 15.3), Vector3(37.5, f + 1.5, 16.0))                # jukebox
	lot.wall_x(&"wall_in", 13.0, 39, 45.3, f, top, 0.12, [[O.DOOR, 40.0, 1.0]])
	lot.wall_z(&"wall_in", 39.0, 13, 16.1, f, top, 0.12)
	lot.box(&"furniture", Vector3(43.6, f, 14.9), Vector3(45.1, f + 0.75, 15.8))               # office desk
	for p: Vector2 in [Vector2(38.5, 3.5), Vector2(38.5, 8.3), Vector2(34.5, 13.0)]:
		_light(lights, lot, Vector3(p.x, top - 0.6, p.y), &"bulb", 0.9, Color(1.0, 0.62, 0.3), 7.0)
	_light(lights, lot, Vector3(42.5, top - 0.5, 14.6), &"bulb", 0.6, Color(1.0, 0.8, 0.55), 4.0)
	lot.marker(&"tavern_door", Vector3(37.55, f + 0.1, 0.4), "tavern entrance", "酒馆入口")
	lot.marker(&"bar", Vector3(38.5, f + 1.3, 8.2), "U-shaped bar: fight in a ring round it", "U 型吧台（环形 CQB）")
	lot.marker(&"spare_key", Vector3(44.3, f + 0.9, 15.3), "spare key to Miller's storeroom", "汽修店库房备用钥匙")
	# Back alley.
	lot.box(&"metal", Vector3(27.0, 0.03, 17.0), Vector3(28.8, 1.35, 18.2))
	lot.wall_x(&"fence", 26.0, 0, 45.5, 0.03, 2.1, 0.06)


# --- 4. the Clark house (west, far south): the nest and the battery -----------------

static func _clark(b: TownBuilder, lights: Array[Dictionary]) -> void:
	var lot := b.add_lot("clark", lot_frame(WEST, 78.0, 106.0), "克拉克家（终局）")
	var f := HOUSE_FLOOR
	var f1 := f + STOREY                    # upstairs floor
	var top := f1 + STOREY                  # eaves
	lot.set_level(0, f)
	lot.box(&"grass", Vector3(0, -0.3, -UNDER), Vector3(28, 0.04, 40))
	lot.box(&"concrete", Vector3(15, 0.0, -0.05), Vector3(19.4, 0.06, 8))                      # drive
	lot.box(&"concrete", Vector3(10.0, 0.0, -0.05), Vector3(11.2, 0.06, 3.9))                   # path
	lot.box(&"concrete", Vector3(4, 0.04, 7), Vector3(14, f - 0.15, 16))
	lot.box(&"floor", Vector3(4, f - 0.15, 7), Vector3(14, f, 16))
	# Porch.
	lot.box(&"wood", Vector3(8, 0.04, 5), Vector3(13, f, 7))
	lot.stairs(&"wood", Vector3(10.6, 0.04, 3.9), Vector3.BACK, f - 0.04, 1.1, 1.6, 3)
	for x: float in [8.1, 12.78]:
		lot.box(&"wood", Vector3(x, f, 5.05), Vector3(x + 0.12, 3.0, 5.17))
	lot.box(&"roof", Vector3(7.8, 3.0, 4.8), Vector3(13.2, 3.12, 7.0))
	_light(lights, lot, Vector3(10.5, 2.8, 6.2), &"bulb", 0.7, Color(1.0, 0.75, 0.45), 5.0)

	# Ground floor: living room and kitchen on the north side, hall and stairs on the south.
	lot.wall_x(&"siding", 7.1, 4, 14, f, f1, 0.2, [[O.WINDOW, 5.0, 3.0], [O.DOOR, 10.0, 1.0]])
	lot.wall_x(&"siding", 15.9, 4, 14, f, f1, 0.2, [[O.WINDOW, 5.5, 2.0], [O.DOOR_CLOSED, 10.5, 0.9]])
	lot.wall_z(&"siding", 4.1, 7, 16, f, f1, 0.2, [[O.WINDOW, 8.5, 2.0], [O.WINDOW, 13.0, 1.5]])
	lot.wall_z(&"siding", 13.9, 7, 16, f, f1)
	lot.wall_z(&"wall_in", 9.0, 7.2, 15.8, f, f1 - 0.2, 0.12, [[O.DOOR, 8.0, 1.0], [O.DOOR, 13.0, 1.0]])
	lot.wall_x(&"wall_in", 12.0, 4.2, 9.0, f, f1 - 0.2, 0.12, [[O.DOOR, 5.0, 1.0]])
	lot.stairs(&"wood", Vector3(13.05, f, 14.6), Vector3.FORWARD, STOREY, 4.0, 1.5, 0, true)
	lot.box(&"furniture", Vector3(4.4, f, 8.0), Vector3(5.3, f + 0.8, 10.2))                    # sofa
	lot.box(&"appliance", Vector3(8.2, f, 9.6), Vector3(8.85, f + 1.2, 10.6))                   # television
	lot.box(&"furniture", Vector3(5.5, f, 13.3), Vector3(7.5, f + 0.75, 14.5))                  # table, a meal left on it
	lot.box(&"counter", Vector3(4.3, f, 15.2), Vector3(8.6, f + 0.9, 15.75))
	_light(lights, lot, Vector3(7.6, f + 1.1, 10.1), &"screen", 0.9, Color(0.6, 0.72, 1.0), 5.0)
	_light(lights, lot, Vector3(6.5, f1 - 0.5, 13.8), &"bulb", 0.6, Color(1.0, 0.8, 0.55), 5.0)
	lot.marker(&"clark_door", Vector3(10.5, f + 0.1, 7.1), "front door", "克拉克家正门")
	lot.marker(&"tv_snow", Vector3(7.9, f + 1.4, 10.1), "television showing snow", "闪雪花的电视")
	lot.marker(&"leftovers", Vector3(6.5, f + 0.9, 13.9), "a meal left half eaten", "桌上的剩饭")
	lot.marker(&"drag_marks", Vector3(11.5, f + 0.05, 12.5), "drag marks across the floor, up the stairs", "家电爬行的拖痕（通向二楼）")

	# Upstairs: the nest bedroom, a walk-in closet with the gun cabinet, a bathroom.
	lot.set_level(1, f1)
	lot.box(&"floor", Vector3(4, f1 - 0.2, 7), Vector3(12.3, f1, 16))
	lot.box(&"floor", Vector3(12.3, f1 - 0.2, 7), Vector3(14, f1, 10.6))
	lot.box(&"floor", Vector3(12.3, f1 - 0.2, 14.8), Vector3(14, f1, 16))
	lot.box(&"wall_in", Vector3(12.2, f1, 10.6), Vector3(12.3, f1 + 1.0, 14.8))                  # stair rail
	lot.wall_x(&"siding", 7.1, 4, 14, f1, top, 0.2, [[O.WINDOW, 5.0, 2.5], [O.WINDOW, 10.5, 2.0]])
	lot.wall_x(&"siding", 15.9, 4, 14, f1, top, 0.2, [[O.WINDOW, 8.0, 2.0]])
	lot.wall_z(&"siding", 4.1, 7, 16, f1, top, 0.2, [[O.WINDOW, 9.0, 2.0]])
	lot.wall_z(&"siding", 13.9, 7, 16, f1, top, 0.2, [[O.WINDOW, 8.0, 1.5]])
	lot.wall_z(&"wall_in", 9.5, 7.2, 13.0, f1, top, 0.12, [[O.DOOR, 11.6, 1.0]])
	lot.wall_x(&"wall_in", 13.0, 4.2, 12.3, f1, top, 0.12, [[O.DOOR, 5.0, 1.0], [O.DOOR, 10.8, 1.0]])
	lot.wall_z(&"wall_in", 7.0, 13.0, 15.8, f1, top, 0.12)
	lot.box(&"furniture", Vector3(4.3, f1, 7.4), Vector3(6.3, f1 + 0.55, 9.6))                  # bed
	lot.box(&"appliance", Vector3(6.6, f1, 10.4), Vector3(7.6, f1 + 1.9, 11.3))                 # the refrigerator
	for p: Vector2 in [Vector2(5.4, 11.2), Vector2(8.1, 10.0), Vector2(7.6, 12.1), Vector2(5.9, 9.9)]:
		lot.box(&"appliance", Vector3(p.x, f1, p.y), Vector3(p.x + 0.45, f1 + 0.4, p.y + 0.4))   # what crawled up to it
	lot.box(&"metal", Vector3(4.25, f1, 14.5), Vector3(4.8, f1 + 1.8, 15.7))                    # gun cabinet
	lot.box(&"appliance", Vector3(7.3, f1, 15.0), Vector3(9.0, f1 + 0.6, 15.75))                # bath
	_light(lights, lot, Vector3(7.1, f1 + 1.2, 10.0), &"nest", 1.6, Color(0.62, 0.25, 1.0), 6.0)
	lot.marker(&"nest", Vector3(7.1, f1 + 2.1, 10.85), "the nest: appliances gathered round a pulsing refrigerator", "家电巢穴（搏动的大冰箱）")
	lot.marker(&"gun_cabinet", Vector3(4.6, f1 + 1.0, 15.1), "gun cabinet: SMG, garage key", "枪柜（冲锋枪、车库钥匙）")
	lot.marker(&"landing", Vector3(11.3, f1 + 0.1, 9.0), "landing", "二楼楼梯口")
	lot.box(&"ceiling", Vector3(4, top, 7), Vector3(14, top + 0.15, 16))
	_gable_roof(lot, 3.5, 14.5, 6.5, 16.5, top + 0.15, 2.3, &"roof", &"siding")
	lot.set_level(0, f)

	# Garage on the south side: this is where the battery is.
	var g := 0.1
	var g_top := 2.9
	lot.box(&"concrete", Vector3(14, 0.04, 8), Vector3(20, g, 15))
	lot.wall_x(&"siding", 8.1, 14, 20, g, g_top, 0.2, [[O.SHUTTER, 15.0, 4.0, 0.0, 2.3]])
	lot.wall_x(&"siding", 14.9, 14, 20, g, g_top)
	lot.wall_z(&"siding", 19.9, 8, 15, g, g_top)
	lot.box(&"roof", Vector3(13.9, g_top, 7.7), Vector3(20.3, g_top + 0.2, 15.3))
	lot.box(&"shelf", Vector3(14.3, g, 12.2), Vector3(14.95, g + 0.9, 14.7))                    # bench
	lot.box(&"shelf", Vector3(19.2, g, 9.0), Vector3(19.75, g + 1.8, 14.5))
	lot.marker(&"battery", Vector3(14.6, g + 1.1, 13.4), "charged car battery", "满电蓄电池")
	lot.marker(&"garage_door", Vector3(17.0, g + 0.1, 8.1),
			"roller door: shut in the real game (garage key); raising it wakes the nest and the street",
			"车库卷帘门（成品里锁着；拉起会惊醒巢穴和整条街）")

	# Picket fence along the front, open at the path and the drive.
	for span: Vector2 in [Vector2(0.2, 9.8), Vector2(11.4, 14.8), Vector2(19.6, 27.8)]:
		lot.box(&"fence", Vector3(span.x, 0.04, 0.3), Vector3(span.y, 1.0, 0.36))
	lot.box(&"fence", Vector3(0.2, 0.04, 0.3), Vector3(0.26, 1.0, 22.0))
	lot.box(&"fence", Vector3(27.74, 0.04, 0.3), Vector3(27.8, 1.0, 22.0))


## A pitched roof over a rectangle, ridge along X: two slanted slabs and
## stepped ends standing in for the gable triangles.
static func _gable_roof(lot: TownBuilder.Lot, x0: float, x1: float, z0: float, z1: float, y: float, rise: float,
		roof_tag: StringName, wall_tag: StringName) -> void:
	var half := (z1 - z0) * 0.5
	var mid := (z0 + z1) * 0.5
	var slope := sqrt(half * half + rise * rise)
	var pitch := rad_to_deg(atan2(rise, half))
	for sign: float in [-1.0, 1.0]:
		var slab := lot.rbox(roof_tag, Vector3((x0 + x1) * 0.5, y + rise * 0.5, mid + sign * half * 0.5),
				Vector3(x1 - x0, 0.14, slope + 0.3), Vector3(sign * pitch, 0, 0))
		slab.plan = false
	const STEPS := 5
	for i in STEPS:
		var inset := half * (i + 0.5) / STEPS
		for x: float in [x0 + 0.5, x1 - 0.7]:
			lot.box(wall_tag, Vector3(x, y + rise * i / STEPS, z0 + inset), Vector3(x + 0.2, y + rise * (i + 1) / STEPS, z1 - inset),
					false).plan = false


# --- everything that cannot be entered -----------------------------------------------

static func _fillers(b: TownBuilder) -> void:
	# [side, s0, s1, siding tag, storeys, depth, setback]
	var houses := [
		[WEST, -50.0, -32.0, &"siding_b", 2, 9.0, 6.0], [WEST, -29.0, -8.0, &"siding", 1, 10.0, 7.0],
		[WEST, 8.0, 27.0, &"siding_c", 2, 9.0, 6.0], [WEST, 30.0, 50.0, &"siding", 2, 10.0, 7.0],
		[WEST, 53.0, 74.0, &"siding_b", 1, 9.0, 6.0],
		[EAST, -58.0, -41.0, &"siding_c", 2, 9.0, 5.0], [EAST, -38.0, -8.0, &"brick", 1, 14.0, 0.5],
		[EAST, 54.0, 73.0, &"siding", 2, 9.0, 6.0], [EAST, 76.0, 94.0, &"siding_b", 1, 10.0, 7.0],
		[EAST, 97.0, 112.0, &"siding_c", 2, 9.0, 6.0],
	]
	for h: Array in houses:
		var s0: float = h[1]
		var s1: float = h[2]
		var width := s1 - s0
		var tag: StringName = h[3]
		var depth: float = h[5]
		var back: float = h[6]
		var lot := b.add_lot("filler_%s_%d" % ["w" if h[0] == WEST else "e", int(s0)], lot_frame(h[0], s0, s1))
		lot.box(&"grass" if tag != &"brick" else &"concrete", Vector3(0, -0.3, -UNDER), Vector3(width, 0.04, depth + back + 14.0))
		var x0 := 2.0
		var x1 := width - 2.0
		var base := HOUSE_FLOOR if tag != &"brick" else SHOP_FLOOR
		var top := base + STOREY * int(h[4]) + (0.9 if tag == &"brick" else 0.0)
		lot.box(&"concrete", Vector3(x0, 0.04, back), Vector3(x1, base, back + depth))
		lot.box(tag, Vector3(x0, base, back), Vector3(x1, top, back + depth))
		if tag == &"brick":
			lot.box(&"roof", Vector3(x0, top, back), Vector3(x1, top + 0.15, back + depth))
		else:
			_gable_roof(lot, x0 - 0.4, x1 + 0.4, back - 0.4, back + depth + 0.4, top, 2.2, &"roof", tag)
			lot.box(&"wood", Vector3(x0 + 1.0, 0.04, back - 1.6), Vector3(x0 + 4.0, base, back))              # porch
			lot.stairs(&"wood", Vector3(x0 + 2.5, 0.04, back - 2.6), Vector3.BACK, base - 0.04, 1.0, 1.4, 3)
		# Dressing on the front: a door and dark windows, a few centimetres proud of the wall.
		lot.box(&"door", Vector3(x0 + 2.0, base, back - 0.06), Vector3(x0 + 3.0, base + 2.05, back), false)
		for storey in int(h[4]):
			var y := base + storey * STOREY
			var x := x0 + (4.2 if storey == 0 else 1.2)
			while x + 1.6 < x1 - 0.8:
				lot.box(&"glass", Vector3(x, y + 0.9, back - 0.05), Vector3(x + 1.6, y + 2.1, back), false)
				x += 3.0


static func _ends_and_edges(b: TownBuilder) -> void:
	var lot := b.add_lot("edges", Transform3D.IDENTITY, "")
	# South: a logging truck on its side across the road, its load everywhere.
	var at := beside(114.0, 0.0)
	lot.rbox(&"metal", at + Vector3(0.5, 1.3, 0.0), Vector3(12.5, 2.6, 2.5), Vector3(0, 12, 0))
	lot.rbox(&"car", at + Vector3(-7.6, 1.5, -2.6), Vector3(2.6, 3.0, 3.2), Vector3(0, 40, 0))
	var logs := [[-3.0, -3.4, 20.0, 0.0], [1.5, -4.2, -15.0, 0.0], [4.5, -2.6, 55.0, 8.0], [-5.5, -5.6, 80.0, 0.0],
			[2.6, -6.4, 5.0, 0.0], [6.8, -5.0, -40.0, 0.0], [-1.0, -7.6, 30.0, 0.0], [7.5, 2.4, 70.0, 12.0],
			[-8.5, 2.0, -25.0, 0.0], [0.5, -2.6, 100.0, 6.0]]
	for l: Array in logs:
		var tilt: float = l[3]
		lot.rbox(&"log", at + Vector3(l[0], 0.3 + tilt * 0.06, l[1]), Vector3(0.55, 0.55, 7.5), Vector3(tilt, l[2], 0))
	lot.marker(&"south_block", at + Vector3(0, 1.5, -8.0), "south: overturned logging truck blocks the road", "南端：运木半挂车翻车封路")
	lot.marker(&"north_block", beside(-110.0, 0.0, 1.5), "north: the fog shuts the road (the ramp is beyond it)", "北端：浓雾封路（匝道在雾里）")

	# The edge of the world: walls nobody sees, and tree trunks in the fog beyond the back yards.
	var wall := func(a: Vector3, c: Vector3) -> void:
		lot.box(&"fence", a, c, true, false).plan = false
	wall.call(Vector3(-90, 0, -113.5), Vector3(110, 6, -112.5))
	wall.call(Vector3(-90, 0, 119.0), Vector3(110, 6, 120.0))
	wall.call(Vector3(-76, 0, -113), Vector3(-75, 6, 120))
	wall.call(Vector3(94, 0, -113), Vector3(95, 6, 120))
	var rng := RandomNumberGenerator.new()
	rng.seed = 20261112
	for i in 150:
		var s := rng.randf_range(-124.0, 124.0)
		var side := WEST if rng.randf() < 0.5 else EAST
		var p := beside(s, side * rng.randf_range(58.0, 84.0))
		var h := rng.randf_range(8.0, 15.0)
		var w := rng.randf_range(0.35, 0.6)
		lot.box(&"bark", Vector3(p.x - w * 0.5, 0.0, p.z - w * 0.5), Vector3(p.x + w * 0.5, h, p.z + w * 0.5)).plan = false

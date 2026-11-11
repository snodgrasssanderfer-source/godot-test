class_name Town
extends Node3D
## Builds the whole map at load time: ground, roads, buildings, props, lights,
## and the navigation grid zombies path on.

const HALF := 96.0        # playable half-extent (metres)
const CELL := 0.5
const GRID := 384
const CHUNK := 32.0

const LOTS := [
	{"type": "house", "pos": Vector2(-20, 44), "size": Vector2(10, 8), "front": "n", "name": "安全屋", "power": true, "safe": true},
	{"type": "store", "pos": Vector2(-21, -14.5), "size": Vector2(14, 11), "front": "s", "name": "便利店", "power": true},
	{"type": "diner", "pos": Vector2(-3, -14), "size": Vector2(12, 10), "front": "s", "name": "老乔酒馆"},
	{"type": "hardware", "pos": Vector2(17, -14.5), "size": Vector2(14, 11), "front": "s", "name": "五金店"},
	{"type": "police", "pos": Vector2(-18, 15), "size": Vector2(16, 12), "front": "n", "name": "警察局", "power": true},
	{"type": "clinic", "pos": Vector2(12, 14.5), "size": Vector2(14, 11), "front": "n", "name": "镇卫生所"},
	{"type": "gas", "pos": Vector2(-64, -24), "size": Vector2(10, 8), "front": "s", "name": "加油站", "power": true},
	{"type": "garage", "pos": Vector2(66, -17.5), "size": Vector2(18, 13), "front": "s", "name": "汽修厂"},
	{"type": "church", "pos": Vector2(16, -74), "size": Vector2(10, 16), "front": "s", "name": "教堂", "power": true},
	{"type": "house", "pos": Vector2(12, 46), "size": Vector2(11, 9), "front": "n"},
	{"type": "house", "pos": Vector2(-14, 74), "size": Vector2(9, 9), "front": "n"},
	{"type": "house", "pos": Vector2(16, 76), "size": Vector2(10, 8), "front": "n"},
	{"type": "house", "pos": Vector2(-20, -42), "size": Vector2(10, 9), "front": "s"},
	{"type": "house", "pos": Vector2(6, -44), "size": Vector2(12, 9), "front": "s"},
	{"type": "house", "pos": Vector2(-12, -70), "size": Vector2(9, 8), "front": "s"},
	{"type": "house", "pos": Vector2(-70, -50), "size": Vector2(10, 9), "front": "e"},
	{"type": "house", "pos": Vector2(-66, -76), "size": Vector2(11, 8), "front": "e"},
	{"type": "house", "pos": Vector2(70, -48), "size": Vector2(10, 9), "front": "w"},
	{"type": "house", "pos": Vector2(66, -76), "size": Vector2(12, 9), "front": "w"},
	{"type": "house", "pos": Vector2(-66, 18), "size": Vector2(11, 9), "front": "n"},
	{"type": "house", "pos": Vector2(-70, 46), "size": Vector2(10, 9), "front": "e"},
	{"type": "house", "pos": Vector2(-64, 74), "size": Vector2(12, 8), "front": "e"},
	{"type": "house", "pos": Vector2(66, 18), "size": Vector2(11, 9), "front": "n"},
	{"type": "house", "pos": Vector2(70, 46), "size": Vector2(9, 10), "front": "w"},
	{"type": "house", "pos": Vector2(64, 74), "size": Vector2(12, 9), "front": "w"},
]
const ROADS := [Rect2(-160, -5, 320, 10), Rect2(-44, -160, 8, 155), Rect2(-44, 5, 8, 155), Rect2(36, -160, 8, 155), Rect2(36, 5, 8, 155)]

var rng := RandomNumberGenerator.new()
var astar := AStarGrid2D.new()
var buildings: Array = []
var doors: Array = []
var windows: Array = []
var containers: Array = []
var beds: Array = []
var portal_map := {}                 # Vector2i -> Door / WindowPane
var _portal_cells: Array = []
var obstacles: Array = []            # AABBs blocking the nav grid
var lights: Array = []               # {l, base, flick, next}
var power_on := true
var spawn_pos := Vector3.ZERO
var spawn_yaw := 0.0
var escape_car: Props.EscapeCar
var walk_rects: Array = []           # roads + sidewalks (hard footsteps)
var _chunks := {}
var _col := MeshBuilder.new()
var _t := 0.0


func build(seed_value: int) -> void:
	rng.seed = seed_value
	_ground()
	for lot in LOTS:
		var info := BuildingGen.new().build(self, lot)
		info["safe"] = lot.get("safe", false)
		buildings.append(info)
		if info["safe"]:
			spawn_pos = info["entry"]
			spawn_yaw = info["entry_yaw"]
			for c in containers:
				if c.get_parent() == info["node"]:
					c.set_meta("safe", true)
	_street_props()
	_checkpoint()
	_gas_canopy()
	_graveyard()
	_trees()
	_border()
	for key in _chunks:
		(_chunks[key] as MeshBuilder).commit(self, G.VIS_OUT, false)
	_col.make_body(self)
	for c in _col.col:
		add_obstacle(c[0], c[1])
	_build_nav()
	_quest_loot()


func _process(delta: float) -> void:
	_t += delta
	for e in lights:
		if not e["flick"] or not power_on:
			continue
		if _t >= e["next"]:
			var l: Light3D = e["l"]
			if randf() < 0.35:
				l.light_energy = e["base"] * randf_range(0.0, 0.35)
				e["next"] = _t + randf_range(0.03, 0.16)
			else:
				l.light_energy = e["base"]
				e["next"] = _t + randf_range(0.08, 2.6)


# --- registration (called by BuildingGen) ---------------------------------------
func add_obstacle(xf: Transform3D, size: Vector3) -> void:
	var bb := xf * AABB(-size * 0.5, size)
	if bb.position.y < 1.5 and bb.end.y > 0.3:
		obstacles.append(bb)


func add_light(l: Light3D, flick: bool) -> void:
	lights.append({"l": l, "base": l.light_energy, "flick": flick, "next": 0.0})


func set_power(on: bool) -> void:
	power_on = on
	for e in lights:
		(e["l"] as Light3D).visible = on


func register_portal(obj: Node, centre: Vector3, along_x: bool, width: float) -> void:
	if obj is Door:
		doors.append(obj)
	else:
		windows.append(obj)
	for cell in _opening_cells(centre, along_x, width):
		portal_map[cell] = obj
		_portal_cells.append(cell)


func register_gap(centre: Vector3, along_x: bool, width: float) -> void:
	for cell in _opening_cells(centre, along_x, width):
		_portal_cells.append(cell)


func _opening_cells(centre: Vector3, along_x: bool, width: float) -> Array:
	var out := {}
	var dir := Vector3.RIGHT if along_x else Vector3.BACK
	var nrm := Vector3.BACK if along_x else Vector3.RIGHT
	var half := maxf(width * 0.5 - 0.2, 0.1)
	var a := -half
	while a <= half + 0.001:
		var b := -0.75
		while b <= 0.751:
			out[cell_of(centre + dir * a + nrm * b)] = true
			b += 0.25
		a += 0.2
	return out.keys()


func place_escape_car(pos: Vector3, yaw: float, parent: Node3D) -> void:
	escape_car = Props.EscapeCar.new()
	parent.add_child(escape_car)
	escape_car.setup(pos, yaw)
	add_obstacle(Transform3D(Basis(Vector3.UP, yaw), pos + Vector3.UP * 0.7), Vector3(1.9, 1.4, 4.4))


# --- ground ----------------------------------------------------------------------------
func _cb(pos: Vector3) -> MeshBuilder:
	var key := Vector2i(int(floor(pos.x / CHUNK)), int(floor(pos.z / CHUNK)))
	if not _chunks.has(key):
		_chunks[key] = MeshBuilder.new()
	return _chunks[key]


## Flat rectangle on the ground, split along chunk borders.
func _gq(mat: String, r: Rect2, y: float, color: Color = Color.WHITE) -> void:
	var x := r.position.x
	while x < r.end.x - 0.001:
		var x1 := minf(r.end.x, (floor(x / CHUNK) + 1.0) * CHUNK)
		var z := r.position.y
		while z < r.end.y - 0.001:
			var z1 := minf(r.end.y, (floor(z / CHUNK) + 1.0) * CHUNK)
			_cb(Vector3((x + x1) * 0.5, 0, (z + z1) * 0.5)).quad(mat, Vector3(x, y, z1), Vector3(x1, y, z1), Vector3(x1, y, z), Vector3(x, y, z), color)
			z = z1
		x = x1


func _deco(xf: Transform3D, size: Vector3, mat: String, color: Color = Color.WHITE, solid: bool = true, face_mats: Dictionary = {}) -> void:
	_cb(xf.origin).box(xf, size, mat, color, MeshBuilder.ALL, face_mats)
	if solid:
		_col.collide(xf, size)


func _ground() -> void:
	_gq("grass", Rect2(-160, -160, 320, 320), 0.0)
	for r: Rect2 in ROADS:
		_gq("asphalt", r, 0.05)
	var sw := 2.5
	# sidewalks along Main St (broken where the cross streets pass) and along the cross streets
	for seg in [[-160.0, -46.5], [-33.5, 33.5], [46.5, 160.0]]:
		_gq("sidewalk", Rect2(seg[0], -5 - sw, seg[1] - seg[0], sw), 0.05)
		_gq("sidewalk", Rect2(seg[0], 5, seg[1] - seg[0], sw), 0.05)
	for x0 in [-44.0, 36.0]:
		for zr in [[-160.0, -5.0], [5.0, 160.0]]:
			_gq("sidewalk", Rect2(x0 - sw, zr[0], sw, zr[1] - zr[0]), 0.05)
			_gq("sidewalk", Rect2(x0 + 8.0, zr[0], sw, zr[1] - zr[0]), 0.05)
	walk_rects = [Rect2(-160, -7.5, 320, 15), Rect2(-46.5, -160, 13, 320), Rect2(33.5, -160, 13, 320)]
	# worn centre lines
	var x := -94.0
	while x < 94.0:
		if absf(x + 40.0) > 7.0 and absf(x - 40.0) > 7.0 and rng.randf() < 0.85:
			_gq("paint_yellow", Rect2(x, -0.1, 2.2, 0.2), 0.08)
		x += 5.0
	for cx in [-40.0, 40.0]:
		var z := -94.0
		while z < 94.0:
			if absf(z) > 8.0 and rng.randf() < 0.8:
				_gq("paint_white", Rect2(cx - 0.1, z, 0.2, 2.2), 0.08)
			z += 5.0
	# dirt paths from every front door towards the nearest street
	for lot in LOTS:
		if lot["type"] != "house":
			continue
		var p: Vector2 = lot["pos"]
		var s: Vector2 = lot["size"]
		match lot["front"]:
			"n": _gq("dirt", Rect2(p.x - 0.8, p.y - s.y * 0.5 - 6.0, 1.6, 6.0), 0.02)
			"s": _gq("dirt", Rect2(p.x - 0.8, p.y + s.y * 0.5, 1.6, 6.0), 0.02)
			"e": _gq("dirt", Rect2(p.x + s.x * 0.5, p.y - 0.8, 6.0, 1.6), 0.02)
			_: _gq("dirt", Rect2(p.x - s.x * 0.5 - 6.0, p.y - 0.8, 6.0, 1.6), 0.02)
	# collision: one big slab
	var body := StaticBody3D.new()
	body.collision_layer = G.L_WORLD
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = Vector3(400, 2, 400)
	cs.shape = bs
	cs.position.y = -1.0
	body.add_child(cs)
	add_child(body)


# --- props -------------------------------------------------------------------------------
func _model(mname: String, pos: Vector3, yaw: float, atlas: String = "props0", parent: Node3D = null, scale: float = 1.0) -> Node3D:
	var m: Node3D = load("res://models/%s.glb" % mname).instantiate()
	Mat.skin(m, Mat.atlas(atlas), G.VIS_OUT, not G.low_spec)
	m.position = pos
	m.rotation.y = yaw
	m.scale = Vector3.ONE * scale
	(parent if parent else self).add_child(m)
	return m


func _car(pos: Vector3, yaw: float) -> void:
	var lc := LootContainer.new()
	add_child(lc)
	lc.setup("car", Vector3(1.8, 1.4, 4.3), Transform3D(Basis(Vector3.UP, yaw), pos))
	_model("car", Vector3.ZERO, 0.0, "props%d" % rng.randi_range(0, 2), lc)
	containers.append(lc)
	add_obstacle(Transform3D(Basis(Vector3.UP, yaw), pos + Vector3.UP * 0.7), Vector3(1.9, 1.4, 4.4))


func _prop_box(mname: String, pos: Vector3, yaw: float, size: Vector3, atlas: String = "props0") -> void:
	_model(mname, pos, yaw, atlas)
	_col.collide(Transform3D(Basis(Vector3.UP, yaw), pos + Vector3.UP * size.y * 0.5), size)


func _lamp(pos: Vector3, yaw: float) -> void:
	_model("lamp", pos, yaw)
	_col.collide(Transform3D(Basis(), pos + Vector3.UP * 2.5), Vector3(0.22, 5.0, 0.22))
	if rng.randf() < 0.4:
		var l := OmniLight3D.new()
		l.position = pos + Basis(Vector3.UP, yaw) * Vector3(0, 4.6, -1.1)
		l.omni_range = 14.0
		l.light_energy = 2.4
		l.light_color = Color(1.0, 0.7, 0.36)
		l.omni_attenuation = 1.2
		l.shadow_enabled = false
		l.distance_fade_enabled = true
		l.distance_fade_begin = 60.0
		l.distance_fade_length = 10.0
		add_child(l)
		add_light(l, rng.randf() < 0.45)


func _street_props() -> void:
	var i := 0
	var x := -84.0
	while x <= 84.0:
		if absf(absf(x) - 40.0) > 8.0:
			if i % 2 == 0:
				_lamp(Vector3(x, 0, 6.3), 0.0)
			else:
				_lamp(Vector3(x, 0, -6.3), PI)
		i += 1
		x += 21.0
	for cx in [-40.0, 40.0]:
		for z in [-78.0, -52.0, -26.0, 26.0, 52.0, 78.0]:
			if i % 2 == 0:
				_lamp(Vector3(cx + 5.3, 0, z), PI * 0.5)
			else:
				_lamp(Vector3(cx - 5.3, 0, z), -PI * 0.5)
			i += 1
	for c in [[-58, 2.2, 1.4], [-27, -2.4, 1.75], [-8, 2.6, 1.5], [9, -1.5, 0.6], [26, 2.8, 1.62], [55, -2.2, 1.3],
			[-41.8, -30, 0.1], [-38.2, -62, 3.0], [-41.5, 36, 0.3], [-39, 66, 2.6], [41.8, -40, 3.3], [38.4, -70, 0.2],
			[41.6, 30, 0.0], [39, 58, 0.5], [-52, -14, 0.2], [30.5, -16, 0.1]]:
		_car(Vector3(c[0] + rng.randf_range(-1, 1), 0.02, c[1]), c[2] + rng.randf_range(-0.15, 0.15))
	for dpos in [Vector3(-21, 0, -22.5), Vector3(-3, 0, -21.5), Vector3(17, 0, -22.5), Vector3(-18, 0, 23.5), Vector3(12, 0, 22.5), Vector3(72, 0, -26.5)]:
		var lc := LootContainer.new()
		add_child(lc)
		var yaw := rng.randf_range(-0.2, 0.2)
		lc.setup("dumpster", Vector3(2.0, 1.2, 1.0), Transform3D(Basis(Vector3.UP, yaw), dpos))
		_model("dumpster", Vector3.ZERO, 0.0, "props0", lc)
		containers.append(lc)
		add_obstacle(Transform3D(Basis(Vector3.UP, yaw), dpos + Vector3.UP * 0.6), Vector3(2.0, 1.2, 1.0))
	for b in [[-12, 6.6, 0.0], [4, 6.6, 0.0], [24, -6.6, PI], [-30, -6.6, PI], [58, 6.6, 0.0]]:
		_prop_box("bench", Vector3(b[0], 0, b[1]), b[2], Vector3(1.6, 0.8, 0.5))
	for b in [[-26.5, -22, 0], [-10.5, -20.5, 0], [9, -21, 0], [25.5, -20, 0], [57.5, -12, 0], [58.2, -12.6, 0], [-9, 22, 0], [20, 21.5, 0], [-58, -21, 0]]:
		_prop_box("barrel", Vector3(b[0], 0, b[1]), rng.randf() * TAU, Vector3(0.62, 0.9, 0.62))
	# concrete barriers + wrecks where the roads leave town
	for e in [[-92, 0, true], [-40, -92, false], [-40, 92, false], [40, -92, false], [40, 92, false]]:
		for k in [-1, 0, 1]:
			var p := Vector3(e[0], 0.45, e[1]) + (Vector3(0, 0, k * 3.3) if e[2] else Vector3(k * 2.9, 0, 0))
			var yaw := (PI * 0.5 if e[2] else 0.0) + rng.randf_range(-0.12, 0.12)
			_deco(Transform3D(Basis(Vector3.UP, yaw), p), Vector3(2.6, 0.9, 0.5), "concrete")
	# a few fence runs around back yards
	for f in [[-27, 40, -27, 50], [-27, 50, -13, 50], [5, 51.5, 19, 51.5], [-26, -47.5, -14, -47.5], [61, 80, 71, 80], [-76, 51.5, -64, 51.5], [59, -53.5, 75, -53.5]]:
		var a := Vector3(f[0], 0, f[1])
		var b := Vector3(f[2], 0, f[3])
		var n := int(a.distance_to(b) / 2.0)
		var dir := (b - a).normalized()
		var yaw := atan2(-dir.z, dir.x)
		for k in n:
			if rng.randf() < 0.15:
				continue
			var p := a + dir * (k * 2.0 + 1.0)
			_prop_box("fence", p, yaw, Vector3(2.0, 1.1, 0.12))


func _crate(pos: Vector3, yaw: float, kind: String) -> void:
	var xf := Transform3D(Basis(Vector3.UP, yaw), pos)
	_cb(pos).box(xf * Transform3D(Basis(), Vector3(0, 0.3, 0)), Vector3(1.2, 0.6, 0.7), "metal_green")
	var lc := LootContainer.new()
	add_child(lc)
	lc.setup(kind, Vector3(1.2, 0.6, 0.7), xf)
	containers.append(lc)
	add_obstacle(xf * Transform3D(Basis(), Vector3(0, 0.3, 0)), Vector3(1.2, 0.6, 0.7))


func _checkpoint() -> void:
	# abandoned army roadblock at the east end of Main St
	_prop_box("sandbags", Vector3(76, 0, -3.4), 0.0, Vector3(2.5, 0.9, 0.5))
	_prop_box("sandbags", Vector3(76, 0, 3.4), 0.0, Vector3(2.5, 0.9, 0.5))
	_prop_box("sandbags", Vector3(79.5, 0, 0), PI * 0.5, Vector3(2.5, 0.9, 0.5))
	_prop_box("sandbags", Vector3(83, 0, 5.6), 0.2, Vector3(2.5, 0.9, 0.5))
	_model("truck", Vector3(87, 0, -1.6), 0.35)
	_col.collide(Transform3D(Basis(Vector3.UP, 0.35), Vector3(87, 1.4, -1.9)), Vector3(2.4, 2.8, 6.4))
	_crate(Vector3(83.5, 0, 3.6), 0.3, "military")
	_crate(Vector3(89.5, 0, 3.0), -0.2, "military")
	_crate(Vector3(84.5, 0, -4.2), 1.4, "military")
	for k in [-1, 1]:
		_deco(Transform3D(Basis(Vector3.UP, 0.0), Vector3(92.5, 0.45, k * 2.6)), Vector3(0.5, 0.9, 4.6), "concrete")
	# road-closed board
	var xf := Transform3D(Basis(Vector3.UP, PI * 0.5), Vector3(74.2, 1.0, 0))
	_deco(xf, Vector3(3.2, 0.5, 0.06), "wood", Color.WHITE, false, {"pz": "sign_exit", "nz": "sign_exit"})
	for k in [-1, 1]:
		_deco(Transform3D(Basis(), Vector3(74.2, 0.4, k * 1.4)), Vector3(0.08, 0.8, 0.08), "wood", Color.WHITE, false)
	var l := OmniLight3D.new()
	l.position = Vector3(82, 3.0, 0)
	l.omni_range = 13.0
	l.light_energy = 2.0
	l.light_color = Color(0.85, 0.95, 1.0)
	l.shadow_enabled = false
	add_child(l)
	add_light(l, true)


func _gas_canopy() -> void:
	var c := Vector3(-64, 0, -12.5)
	for sx in [-1, 1]:
		for sz in [-1, 1]:
			_deco(Transform3D(Basis(), c + Vector3(sx * 4.4, 2.1, sz * 2.4)), Vector3(0.3, 4.2, 0.3), "metal")
	_deco(Transform3D(Basis(), c + Vector3(0, 4.4, 0)), Vector3(10.4, 0.45, 6.4), "metal", Color.WHITE, false)
	_prop_box("pump", c + Vector3(-2.2, 0, 0), 0.0, Vector3(0.9, 1.7, 0.5))
	_prop_box("pump", c + Vector3(2.2, 0, 0), 0.0, Vector3(0.9, 1.7, 0.5))
	var l := OmniLight3D.new()
	l.position = c + Vector3(0, 3.9, 0)
	l.omni_range = 11.0
	l.light_energy = 2.2
	l.light_color = Color(0.8, 0.95, 1.0)
	l.shadow_enabled = false
	add_child(l)
	add_light(l, true)


func _graveyard() -> void:
	for i in 4:
		for j in 6:
			if rng.randf() < 0.2:
				continue
			var p := Vector3(24.5 + i * 2.4 + rng.randf_range(-0.2, 0.2), 0, -85 + j * 3.4 + rng.randf_range(-0.3, 0.3))
			if rng.randf() < 0.75:
				_prop_box("gravestone", p, rng.randf_range(-0.15, 0.15), Vector3(0.7, 0.9, 0.2))
			else:
				_prop_box("cross", p, rng.randf_range(-0.2, 0.2), Vector3(0.2, 1.4, 0.2))
	_tree(Vector3(33.5, 0, -80), "deadtree")
	_tree(Vector3(23.5, 0, -62), "deadtree")


func _tree(pos: Vector3, mname: String) -> void:
	var s := rng.randf_range(0.85, 1.35)
	_model(mname, pos, rng.randf() * TAU, "props0", null, s)
	_col.collide(Transform3D(Basis(), pos + Vector3.UP * 1.5), Vector3(0.36, 3.0, 0.36))


func _free_spot(p: Vector2, margin: float) -> bool:
	for b in buildings:
		if (b["rect"] as Rect2).grow(margin).has_point(p):
			return false
	for r: Rect2 in walk_rects:
		if r.grow(0.6).has_point(p):
			return false
	if Rect2(-70, -17, 12, 9).has_point(p) or Rect2(22, -88, 13, 28).has_point(p):
		return false
	return true


func _trees() -> void:
	var placed := 0
	var guard := 0
	while placed < 55 and guard < 2000:
		guard += 1
		var p := Vector2(rng.randf_range(-92, 92), rng.randf_range(-92, 92))
		if not _free_spot(p, 3.0):
			continue
		_tree(Vector3(p.x, 0, p.y), "pine" if rng.randf() < 0.6 else "deadtree")
		placed += 1


func _border() -> void:
	# invisible walls just past the playable area
	for e in [[Vector3(HALF + 1, 5, 0), Vector3(2, 10, 220)], [Vector3(-HALF - 1, 5, 0), Vector3(2, 10, 220)],
			[Vector3(0, 5, HALF + 1), Vector3(220, 10, 2)], [Vector3(0, 5, -HALF - 1), Vector3(220, 10, 2)]]:
		_col.collide(Transform3D(Basis(), e[0]), e[1])
	# thick ring of pines beyond it (MultiMesh per side so they cull)
	var pine_scene: Node3D = load("res://models/pine.glb").instantiate()
	var mesh: Mesh = (pine_scene.find_child("pine", true, false) as MeshInstance3D).mesh
	pine_scene.free()
	for side in 4:
		var xfs: Array = []
		for i in (90 if G.low_spec else 150):
			var a := rng.randf_range(-130, 130)
			var b := rng.randf_range(HALF + 2.5, HALF + 32)
			var p: Vector3
			match side:
				0: p = Vector3(a, 0, -b)
				1: p = Vector3(a, 0, b)
				2: p = Vector3(-b, 0, a)
				_: p = Vector3(b, 0, a)
			var on_road := false
			for r: Rect2 in ROADS:
				if r.grow(1.5).has_point(Vector2(p.x, p.z)):
					on_road = true
			if on_road:
				continue
			var s := rng.randf_range(0.9, 1.7)
			xfs.append(Transform3D(Basis(Vector3.UP, rng.randf() * TAU).scaled(Vector3(s, s * rng.randf_range(0.9, 1.3), s)), p))
		var mm := MultiMesh.new()
		mm.transform_format = MultiMesh.TRANSFORM_3D
		mm.mesh = mesh
		mm.instance_count = xfs.size()
		for i in xfs.size():
			mm.set_instance_transform(i, xfs[i])
		var mmi := MultiMeshInstance3D.new()
		mmi.multimesh = mm
		mmi.material_override = Mat.atlas("props0")
		mmi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		add_child(mmi)


# --- navigation ----------------------------------------------------------------------------
func _build_nav() -> void:
	astar.region = Rect2i(0, 0, GRID, GRID)
	astar.cell_size = Vector2(CELL, CELL)
	astar.offset = Vector2(-HALF + CELL * 0.5, -HALF + CELL * 0.5)
	astar.diagonal_mode = AStarGrid2D.DIAGONAL_MODE_ONLY_IF_NO_OBSTACLES
	astar.default_compute_heuristic = AStarGrid2D.HEURISTIC_OCTILE
	astar.default_estimate_heuristic = AStarGrid2D.HEURISTIC_OCTILE
	astar.update()
	var pad := 0.3
	for bb: AABB in obstacles:
		var c0 := cell_of(Vector3(bb.position.x - pad + CELL * 0.5, 0, bb.position.z - pad + CELL * 0.5))
		var c1 := cell_of(Vector3(bb.end.x + pad - CELL * 0.5, 0, bb.end.z + pad - CELL * 0.5))
		for cx in range(c0.x, c1.x + 1):
			for cy in range(c0.y, c1.y + 1):
				if cx >= 0 and cy >= 0 and cx < GRID and cy < GRID:
					astar.set_point_solid(Vector2i(cx, cy), true)
	for cell: Vector2i in _portal_cells:
		if astar.is_in_boundsv(cell):
			astar.set_point_solid(cell, false)


func cell_of(pos: Vector3) -> Vector2i:
	return Vector2i(int(floor((pos.x + HALF) / CELL)), int(floor((pos.z + HALF) / CELL)))


func cell_pos(c: Vector2i) -> Vector3:
	return Vector3(-HALF + (c.x + 0.5) * CELL, 0, -HALF + (c.y + 0.5) * CELL)


func free_cell(c: Vector2i) -> Vector2i:
	c = Vector2i(clampi(c.x, 0, GRID - 1), clampi(c.y, 0, GRID - 1))
	if not astar.is_point_solid(c):
		return c
	for r in range(1, 9):
		for dx in range(-r, r + 1):
			for dy in [-r, r]:
				for q in [Vector2i(c.x + dx, c.y + dy), Vector2i(c.x + dy, c.y + dx)]:
					if astar.is_in_boundsv(q) and not astar.is_point_solid(q):
						return q
	return c


func is_free(pos: Vector3) -> bool:
	var c := cell_of(pos)
	return astar.is_in_boundsv(c) and not astar.is_point_solid(c)


func find_path(from: Vector3, to: Vector3) -> PackedVector3Array:
	var out := PackedVector3Array()
	var a := free_cell(cell_of(from))
	var b := free_cell(cell_of(to))
	if astar.is_point_solid(a) or astar.is_point_solid(b):
		return out
	for p in astar.get_point_path(a, b, true):
		out.append(Vector3(p.x, 0, p.y))
	return out


func portal_at(pos: Vector3):
	return portal_map.get(cell_of(pos))


func building_at(pos: Vector3):
	var p := Vector2(pos.x, pos.z)
	for b in buildings:
		if (b["rect"] as Rect2).has_point(p):
			return b
	return null


func is_indoor(pos: Vector3) -> bool:
	return building_at(pos) != null


func surface_at(pos: Vector3) -> String:
	if is_indoor(pos):
		return "wood"
	var p := Vector2(pos.x, pos.z)
	for r: Rect2 in walk_rects:
		if r.has_point(p):
			return "hard"
	return "grass"


## Random walkable outdoor point (used for zombie placement / wandering).
func random_point(avoid: Vector3 = Vector3.INF, min_dist: float = 0.0) -> Vector3:
	for attempt in 30:
		var p := Vector3(rng.randf_range(-HALF + 3, HALF - 3), 0, rng.randf_range(-HALF + 3, HALF - 3))
		if not is_free(p):
			continue
		if avoid != Vector3.INF and p.distance_to(avoid) < min_dist:
			continue
		var b = building_at(p)
		if b != null and (b["safe"] or rng.randf() < 0.7):
			continue
		return p
	return Vector3(rng.randf_range(-80, 80), 0, 0)


func edge_point() -> Vector3:
	var roads := [Vector3(-HALF + 3, 0, 0), Vector3(-40, 0, -HALF + 3), Vector3(-40, 0, HALF - 3), Vector3(40, 0, -HALF + 3), Vector3(40, 0, HALF - 3)]
	if rng.randf() < 0.5:
		return roads[rng.randi() % roads.size()] + Vector3(rng.randf_range(-3, 3), 0, rng.randf_range(-3, 3))
	var t := rng.randf_range(-HALF + 4, HALF - 4)
	var e := HALF - 3.0
	var p: Vector3 = [Vector3(t, 0, -e), Vector3(t, 0, e), Vector3(-e, 0, t), Vector3(e, 0, t)][rng.randi() % 4]
	return cell_pos(free_cell(cell_of(p)))


# --- guaranteed quest items -----------------------------------------------------------------
func _quest_loot() -> void:
	_force(["house"], ["drawer", "wardrobe", "counter"], "car_key")
	_force(["hardware"], ["tool_shelf", "toolbench", "crate"], "car_battery")
	_force(["gas"], ["food_shelf", "crate", "desk", "register"], "gas_can")
	_force(["police"], ["desk", "filing", "locker"], "police_key")
	_force(["clinic"], ["med_shelf", "medicine"], "serum")
	_force(["hardware", "garage"], ["tool_shelf", "toolbench"], "hammer")
	_force(["hardware"], ["tool_shelf", "toolbench", "crate"], "plank", 3)


func _force(btypes: Array, kinds: Array, id: String, n: int = 1) -> void:
	var cands: Array = []
	var fallback: Array = []
	for c: LootContainer in containers:
		if c.has_meta("safe") or not c.has_meta("btype") or not (c.get_meta("btype") in btypes):
			continue
		fallback.append(c)
		if c.kind in kinds:
			cands.append(c)
	if cands.is_empty():
		cands = fallback
	if cands.is_empty():
		push_warning("no container for quest item " + id)
		return
	var c: LootContainer = cands[rng.randi() % cands.size()]
	if c.items != null:
		c.items = null     # an "already looted" shelf gets its roll back
	c.forced.append({"id": id, "n": n})

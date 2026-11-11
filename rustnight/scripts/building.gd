class_name BuildingGen
extends RefCounted
## Generates one enterable building: room layout, walls with door/window
## openings, floors, roof, furniture, containers and lights.
##
## The layout is produced in a canonical frame (front wall at b = D, "a" runs
## along the front) and then mapped to the lot's real orientation.

const H := 2.8          # ceiling height
const T := 0.2          # wall thickness
const DOOR_W := 1.1
const DOOR_H := 2.1

# furniture: width (along wall), depth, height, container kind
const FURN := {
	"fridge": [0.8, 0.7, 1.8, "fridge"], "counter": [1.4, 0.6, 0.9, "counter"], "sofa": [2.0, 0.85, 0.8, ""],
	"sideboard": [1.3, 0.45, 0.65, "drawer"], "bookshelf": [1.1, 0.35, 1.8, "shelf"], "bed": [1.3, 2.0, 0.5, "bed"],
	"wardrobe": [1.2, 0.6, 1.9, "wardrobe"], "nightstand": [0.5, 0.45, 0.55, "drawer"], "toilet": [0.5, 0.7, 0.75, ""],
	"medcab": [0.6, 0.2, 1.7, "medicine"], "bathtub": [1.6, 0.75, 0.55, ""], "store_shelf": [2.0, 0.5, 1.8, "store_shelf"],
	"checkout": [2.0, 0.7, 1.0, "register"], "freezer": [1.6, 0.7, 0.9, "drink_fridge"], "desk": [1.4, 0.7, 0.76, "desk"],
	"filing": [0.5, 0.6, 1.3, "filing"], "locker": [0.9, 0.5, 1.9, "locker"], "gunlocker": [0.9, 0.5, 1.9, "gunlocker"],
	"crate": [1.0, 1.0, 0.9, "crate"], "toolbench": [1.8, 0.7, 0.9, "toolbench"], "barshelf": [2.0, 0.4, 1.8, "bar"],
}
const ROOM_FURN := {
	"living": ["sofa", "sideboard", "bookshelf", "nightstand"],
	"living_kitchen": ["sofa", "fridge", "counter", "sideboard"],
	"kitchen": ["fridge", "counter", "counter", "sideboard"],
	"bedroom": ["bed", "wardrobe", "nightstand", "bookshelf"],
	"bathroom": ["toilet", "medcab", "bathtub"],
	"storage": ["crate", "store_shelf", "crate", "crate"],
	"office": ["desk", "filing", "locker", "filing"],
	"shop_store": ["checkout", "freezer", "*store_shelf"],
	"shop_hardware": ["checkout", "toolbench", "*store_shelf"],
	"shop_gas": ["checkout", "freezer", "*store_shelf"],
	"diner": ["barshelf", "barshelf", "freezer", "sofa"],
	"waiting": ["desk", "sofa", "sofa", "filing"],
	"exam": ["bed", "medcab", "store_shelf"],
	"pharmacy": ["*store_shelf"],
	"lobby": ["desk", "sofa", "filing", "locker"],
	"armory": ["gunlocker", "gunlocker", "gunlocker", "crate"],
	"cells": ["bed", "bed", "toilet"],
	"hall_garage": ["toolbench", "toolbench", "locker", "crate", "crate", "store_shelf"],
	"hall_church": ["bookshelf", "sideboard"],
}
const FLOORS := {
	"living": "wood_floor", "living_kitchen": "wood_floor", "bedroom": "carpet", "kitchen": "tile_floor",
	"bathroom": "tile_floor", "storage": "concrete_floor", "office": "carpet", "waiting": "tile_floor",
	"exam": "tile_floor", "pharmacy": "tile_floor", "lobby": "tile_floor", "armory": "concrete_floor",
	"cells": "concrete_floor", "hall_garage": "concrete_floor", "hall_church": "wood_floor", "diner": "wood_floor",
}
const NO_WINDOW := ["storage", "armory", "cells", "pharmacy"]
const STYLE := {
	"house": {"ext": ["siding", "siding", "brick"], "wall": "wallpaper", "gable": true},
	"store": {"ext": ["brick"], "wall": "plaster", "sign": "sign_store", "light": Color(0.75, 0.9, 1.0)},
	"diner": {"ext": ["brick_dark"], "wall": "wallpaper", "sign": "sign_diner", "light": Color(1.0, 0.7, 0.4)},
	"hardware": {"ext": ["concrete"], "wall": "plaster", "sign": "sign_hardware", "light": Color(0.75, 0.9, 1.0)},
	"police": {"ext": ["brick_dark"], "wall": "plaster", "sign": "sign_police", "light": Color(0.75, 0.9, 1.0)},
	"clinic": {"ext": ["concrete"], "wall": "tiles_wall", "sign": "sign_clinic", "light": Color(0.8, 1.0, 0.9)},
	"gas": {"ext": ["concrete"], "wall": "plaster", "sign": "sign_gas", "light": Color(0.75, 0.9, 1.0)},
	"garage": {"ext": ["metal_ribbed"], "wall": "metal_ribbed", "sign": "sign_garage", "light": Color(1.0, 0.8, 0.5)},
	"church": {"ext": ["siding"], "wall": "plaster", "gable": true, "light": Color(1.0, 0.75, 0.45)},
}
const HOUSE_TINTS := [Color(0.9, 0.9, 0.84), Color(0.72, 0.8, 0.84), Color(0.86, 0.8, 0.64), Color(0.66, 0.7, 0.62), Color(0.84, 0.7, 0.66), Color(0.6, 0.6, 0.64)]

var town: Town
var rng: RandomNumberGenerator
var def: Dictionary
var btype: String
var node: Node3D
var o: Vector3                 # world position of local (0, 0)
var w: float
var d: float
var front: String
var W: float                   # canonical width / depth
var D: float
var ext := MeshBuilder.new()   # outdoor-lit geometry + all static collision
var inr := MeshBuilder.new()   # indoor geometry
var rooms: Array = []          # {rect, role, doors:[Vector2], wins:[Vector2]} (local coords)
var iwalls: Array = []         # {p0, p1, doors:[Vector2]} (local coords)
var door_pts: Array = []       # canonical while laying out
var ext_ops := {"n": [], "s": [], "w": [], "e": []}
var style: Dictionary
var ext_mat: String
var wall_mat: String
var tint := Color.WHITE
var powered := false
var info := {}
var _c_front := Vector2.ZERO


func build(p_town: Town, p_def: Dictionary) -> Dictionary:
	town = p_town
	rng = town.rng
	def = p_def
	btype = def["type"]
	front = def["front"]
	w = def["size"].x
	d = def["size"].y
	o = Vector3(def["pos"].x - w * 0.5, 0, def["pos"].y - d * 0.5)
	W = w if front in ["n", "s"] else d
	D = d if front in ["n", "s"] else w
	style = STYLE[btype]
	ext_mat = style["ext"][rng.randi() % style["ext"].size()]
	wall_mat = style["wall"]
	if btype == "house":
		tint = HOUSE_TINTS[rng.randi() % HOUSE_TINTS.size()] if ext_mat == "siding" else Color.WHITE
		if rng.randf() < 0.4:
			wall_mat = "plaster"
	powered = def.get("power", rng.randf() < 0.3)
	node = Node3D.new()
	node.name = "%s_%d" % [btype, town.buildings.size()]
	town.add_child(node)

	_layout()
	_exterior_openings()
	_emit_walls()
	_emit_floors_and_roof()
	for r in rooms:
		_furnish(r)
	_lights()
	_sign()

	inr.commit(node, G.VIS_IN, false)
	ext.commit(node, G.VIS_OUT, true)
	ext.make_body(node)
	for c in ext.col:
		town.add_obstacle(c[0], c[1])
	var el := _cmap(Vector2(_c_front.x, D - 1.6))
	info = {"rect": Rect2(o.x, o.z, w, d), "type": btype, "name": def.get("name", ""), "rooms": rooms,
		"origin": o, "powered": powered, "node": node, "entry": P(el.x, 0.05, el.y),
		"entry_yaw": {"s": 0.0, "n": PI, "e": PI * 0.5, "w": -PI * 0.5}[front]}
	return info


# --- coordinate helpers -----------------------------------------------------
func P(u: float, y: float, v: float) -> Vector3:
	return Vector3(o.x + u, y, o.z + v)


func _cmap(p: Vector2) -> Vector2:
	match front:
		"s": return p
		"n": return Vector2(W - p.x, D - p.y)
		"e": return Vector2(p.y, p.x)
	return Vector2(D - p.y, p.x)


func _cmap_rect(r: Rect2) -> Rect2:
	var a := _cmap(r.position)
	var b := _cmap(r.end)
	return Rect2(Vector2(minf(a.x, b.x), minf(a.y, b.y)), Vector2(absf(a.x - b.x), absf(a.y - b.y)))


# --- layout --------------------------------------------------------------------
func _layout() -> void:
	var c_rooms: Array = []
	var c_walls: Array = []
	var fd := Vector2(snappedf(W * rng.randf_range(0.35, 0.65), 0.5), D)
	door_pts = [fd]
	match btype:
		"house":
			_bsp(Rect2(0, 0, W, D), 0, 3 if W * D > 85.0 else 2, 2.9, c_rooms, c_walls)
			var others: Array = []
			for r in c_rooms:
				var rc: Rect2 = r["rect"]
				if absf(rc.end.y - D) < 0.01 and fd.x > rc.position.x and fd.x < rc.end.x:
					r["role"] = "living"
				else:
					others.append(r)
			others.sort_custom(func(p, q): return p["rect"].get_area() > q["rect"].get_area())
			if others.size() == 0:
				c_rooms[0]["role"] = "living_kitchen"
			elif others.size() == 1:
				others[0]["role"] = "bedroom"
				for r in c_rooms:
					if r["role"] == "living":
						r["role"] = "living_kitchen"
			else:
				var roles := ["kitchen", "bedroom", "bedroom", "bedroom", "storage", "storage", "storage"]
				for i in others.size():
					others[i]["role"] = roles[i]
				if others.size() >= 3:
					others[others.size() - 1]["role"] = "bathroom"
		"store", "hardware", "gas":
			_strip(3.5, 2, ["storage", "office"], "shop_" + btype, c_rooms, c_walls)
		"diner":
			_strip(3.5, 2, ["kitchen", "storage"], "diner", c_rooms, c_walls)
		"clinic":
			_strip(D - 4.5, 3, ["exam", "pharmacy", "exam"], "waiting", c_rooms, c_walls)
		"police":
			_strip(D - 5.0, 3, ["office", "armory", "cells"], "lobby", c_rooms, c_walls)
		"garage":
			var sx := W - 4.5
			fd = Vector2(W - 2.25, D)
			door_pts = [fd]
			c_rooms.append({"rect": Rect2(0, 0, sx, D), "role": "hall_garage"})
			c_rooms.append({"rect": Rect2(sx, 0, 4.5, D * 0.5), "role": "storage"})
			c_rooms.append({"rect": Rect2(sx, D * 0.5, 4.5, D * 0.5), "role": "office"})
			c_walls.append({"p0": Vector2(sx, 0), "p1": Vector2(sx, D), "doors": [Vector2(sx, snappedf(D * 0.25, 0.5)), Vector2(sx, snappedf(D * 0.75, 0.5))]})
			c_walls.append({"p0": Vector2(sx, D * 0.5), "p1": Vector2(W, D * 0.5), "doors": []})
			def["garage_gap"] = Vector2(sx * 0.5, D)
		_:
			c_rooms.append({"rect": Rect2(0, 0, W, D), "role": "hall_" + btype})
	_c_front = fd
	# back door for houses, well away from interior wall ends
	var back_door = null
	if btype == "house" and rng.randf() < 0.6:
		for attempt in 10:
			var c := snappedf(rng.randf_range(1.5, W - 1.5), 0.5)
			var ok := true
			for cw in c_walls:
				for e in [cw["p0"], cw["p1"]]:
					if absf(e.y) < 0.01 and absf(e.x - c) < 1.1:
						ok = false
			if ok:
				back_door = Vector2(c, 0)
				break
	# map to local space
	for r in c_rooms:
		rooms.append({"rect": _cmap_rect(r["rect"]), "role": r["role"], "doors": [], "wins": []})
	for cw in c_walls:
		var ds: Array = []
		for q in cw["doors"]:
			ds.append(_cmap(q))
		iwalls.append({"p0": _cmap(cw["p0"]), "p1": _cmap(cw["p1"]), "doors": ds})
	var metal := btype != "house" and btype != "church"
	_add_ext_door(_cmap(fd), metal)
	if back_door != null:
		_add_ext_door(_cmap(back_door), false)
	if def.has("garage_gap"):
		var g: Vector2 = _cmap(def["garage_gap"])
		ext_ops[_side_of(g)].append({"c": _along(g), "w": 3.4, "y0": 0.0, "y1": 2.6, "kind": "gap", "pt": g})
	for iw in iwalls:
		for q in iw["doors"]:
			_note_door(q)


func _bsp(r: Rect2, depth: int, max_depth: int, min_size: float, out_rooms: Array, out_walls: Array) -> void:
	var can_a := r.size.x >= min_size * 2.0
	var can_b := r.size.y >= min_size * 2.0
	if depth >= max_depth or (not can_a and not can_b):
		out_rooms.append({"rect": r, "role": ""})
		return
	var cut_a := can_a and (not can_b or r.size.x >= r.size.y)
	for attempt in 10:
		if cut_a:
			var s := snappedf(rng.randf_range(r.position.x + min_size, r.end.x - min_size), 0.5)
			if _near_door(Vector2(s, r.position.y)) or _near_door(Vector2(s, r.end.y)):
				continue
			var dc := Vector2(s, snappedf(rng.randf_range(r.position.y + 1.0, r.end.y - 1.0), 0.5))
			door_pts.append(dc)
			out_walls.append({"p0": Vector2(s, r.position.y), "p1": Vector2(s, r.end.y), "doors": [dc]})
			_bsp(Rect2(r.position.x, r.position.y, s - r.position.x, r.size.y), depth + 1, max_depth, min_size, out_rooms, out_walls)
			_bsp(Rect2(s, r.position.y, r.end.x - s, r.size.y), depth + 1, max_depth, min_size, out_rooms, out_walls)
			return
		else:
			var s2 := snappedf(rng.randf_range(r.position.y + min_size, r.end.y - min_size), 0.5)
			if _near_door(Vector2(r.position.x, s2)) or _near_door(Vector2(r.end.x, s2)):
				continue
			var dc2 := Vector2(snappedf(rng.randf_range(r.position.x + 1.0, r.end.x - 1.0), 0.5), s2)
			door_pts.append(dc2)
			out_walls.append({"p0": Vector2(r.position.x, s2), "p1": Vector2(r.end.x, s2), "doors": [dc2]})
			_bsp(Rect2(r.position.x, r.position.y, r.size.x, s2 - r.position.y), depth + 1, max_depth, min_size, out_rooms, out_walls)
			_bsp(Rect2(r.position.x, s2, r.size.x, r.end.y - s2), depth + 1, max_depth, min_size, out_rooms, out_walls)
			return
	out_rooms.append({"rect": r, "role": ""})


func _near_door(p: Vector2) -> bool:
	for q: Vector2 in door_pts:
		if q.distance_to(p) < 1.1:
			return true
	return false


## Back strip (b in [0, back]) cut into n rooms, each with a door into the main room.
func _strip(back: float, n: int, roles: Array, main_role: String, out_rooms: Array, out_walls: Array) -> void:
	var cuts: Array = [0.0]
	for i in range(1, n):
		cuts.append(snappedf(W * i / n + rng.randf_range(-0.5, 0.5), 0.5))
	cuts.append(W)
	var doors: Array = []
	for i in n:
		out_rooms.append({"rect": Rect2(cuts[i], 0, cuts[i + 1] - cuts[i], back), "role": roles[i]})
		var dc := Vector2(snappedf((cuts[i] + cuts[i + 1]) * 0.5, 0.5), back)
		if dc.distance_to(door_pts[0]) < 1.4:
			dc.x += 1.5
		doors.append(dc)
		if i > 0:
			out_walls.append({"p0": Vector2(cuts[i], 0), "p1": Vector2(cuts[i], back), "doors": []})
	out_walls.append({"p0": Vector2(0, back), "p1": Vector2(W, back), "doors": doors})
	out_rooms.append({"rect": Rect2(0, back, W, D - back), "role": main_role})


func _side_of(p: Vector2) -> String:
	if absf(p.y) < 0.01:
		return "n"
	if absf(p.y - d) < 0.01:
		return "s"
	if absf(p.x) < 0.01:
		return "w"
	return "e"


func _along(p: Vector2) -> float:
	return p.x if _side_of(p) in ["n", "s"] else p.y


func _add_ext_door(p: Vector2, metal: bool) -> void:
	ext_ops[_side_of(p)].append({"c": _along(p), "w": DOOR_W, "y0": 0.0, "y1": DOOR_H, "kind": "door", "metal": metal, "pt": p})
	_note_door(p)


## Remember a door point on every room it touches (furniture keeps clear of it).
func _note_door(p: Vector2) -> void:
	for r in rooms:
		if (r["rect"] as Rect2).grow(0.05).has_point(p):
			r["doors"].append(p)


func _room_touching(p: Vector2):
	for r in rooms:
		if (r["rect"] as Rect2).grow(0.05).has_point(p):
			return r
	return null


func _exterior_openings() -> void:
	var ends: Array = []
	for iw in iwalls:
		ends.append(iw["p0"])
		ends.append(iw["p1"])
	for side in ["n", "s", "w", "e"]:
		var length := w if side in ["n", "s"] else d
		var c := 1.6 + rng.randf_range(0.0, 0.8)
		while c < length - 1.5:
			var p: Vector2
			match side:
				"n": p = Vector2(c, 0)
				"s": p = Vector2(c, d)
				"w": p = Vector2(0, c)
				_: p = Vector2(w, c)
			var ok := true
			for op in ext_ops[side]:
				if absf(op["c"] - c) < (op["w"] + WindowPane.W) * 0.5 + 0.5:
					ok = false
			for e: Vector2 in ends:
				if e.distance_to(p) < 1.0:
					ok = false
			var room = _room_touching(p)
			if room == null or room["role"] in NO_WINDOW:
				ok = false
			if btype == "garage" and room != null and room["role"] == "hall_garage":
				ok = false
			if ok:
				ext_ops[side].append({"c": c, "w": WindowPane.W, "y0": WindowPane.Y0, "y1": WindowPane.Y1, "kind": "window", "pt": p})
				room["wins"].append(p)
				c += rng.randf_range(2.6, 3.8)
			else:
				c += 0.5


# --- walls -------------------------------------------------------------------------
func _emit_walls() -> void:
	_wall(true, 0.0, -T * 0.5, w + T * 0.5, ext_ops["n"], true, -1.0)
	_wall(true, d, -T * 0.5, w + T * 0.5, ext_ops["s"], true, 1.0)
	_wall(false, 0.0, T * 0.5, d - T * 0.5, ext_ops["w"], true, -1.0)
	_wall(false, w, T * 0.5, d - T * 0.5, ext_ops["e"], true, 1.0)
	for iw in iwalls:
		var p0: Vector2 = iw["p0"]
		var p1: Vector2 = iw["p1"]
		var along_x := absf(p1.x - p0.x) > absf(p1.y - p0.y)
		var ops: Array = []
		for q: Vector2 in iw["doors"]:
			ops.append({"c": q.x if along_x else q.y, "w": DOOR_W, "y0": 0.0, "y1": DOOR_H, "kind": "door", "metal": false, "pt": q})
		if along_x:
			_wall(true, p0.y, minf(p0.x, p1.x) + T * 0.5, maxf(p0.x, p1.x) - T * 0.5, ops, false, 1.0)
		else:
			_wall(false, p0.x, minf(p0.y, p1.y) + T * 0.5, maxf(p0.y, p1.y) - T * 0.5, ops, false, 1.0)


func _wall(along_x: bool, line: float, a: float, b: float, ops: Array, exterior: bool, out_dir: float) -> void:
	ops.sort_custom(func(p, q): return p["c"] < q["c"])
	var cur := a
	for op in ops:
		var s0: float = op["c"] - op["w"] * 0.5
		var s1: float = op["c"] + op["w"] * 0.5
		if s0 > cur + 0.01:
			_piece(along_x, line, cur, s0, 0.0, H, exterior, out_dir)
		if op["y0"] > 0.01:
			_piece(along_x, line, s0, s1, 0.0, op["y0"], exterior, out_dir)
		if op["y1"] < H - 0.01:
			_piece(along_x, line, s0, s1, op["y1"], H, exterior, out_dir)
		cur = s1
		var pt: Vector2 = op["pt"]
		var centre := P(pt.x, 0.0, pt.y)
		if op["kind"] == "door":
			var door := Door.new()
			node.add_child(door)
			door.setup(centre, along_x, op["metal"], exterior)
			town.register_portal(door, centre, along_x, DOOR_W)
		elif op["kind"] == "window":
			var win := WindowPane.new()
			node.add_child(win)
			win.setup(centre, along_x)
			town.register_portal(win, centre, along_x, WindowPane.W)
		else:
			town.register_gap(centre, along_x, op["w"])
	if cur < b - 0.01:
		_piece(along_x, line, cur, b, 0.0, H, exterior, out_dir)


func _piece(along_x: bool, line: float, a: float, b: float, y0: float, y1: float, exterior: bool, out_dir: float) -> void:
	var size: Vector3
	var c: Vector3
	var inner: int
	if along_x:
		size = Vector3(b - a, y1 - y0, T)
		c = P((a + b) * 0.5, (y0 + y1) * 0.5, line)
		inner = MeshBuilder.NZ if out_dir > 0.0 else MeshBuilder.PZ
	else:
		size = Vector3(T, y1 - y0, b - a)
		c = P(line, (y0 + y1) * 0.5, (a + b) * 0.5)
		inner = MeshBuilder.NX if out_dir > 0.0 else MeshBuilder.PX
	var xf := Transform3D(Basis(), c)
	if exterior:
		ext.box(xf, size, ext_mat, tint, MeshBuilder.ALL & ~inner)
		inr.box(xf, size, wall_mat, Color.WHITE, inner)
	else:
		inr.box(xf, size, wall_mat)
	ext.collide(xf, size)


# --- floors, ceiling, roof ---------------------------------------------------------------
func _emit_floors_and_roof() -> void:
	for r in rooms:
		var rc: Rect2 = r["rect"]
		var fm: String = FLOORS.get(r["role"], "tile_floor")
		if r["role"] in ["living", "bedroom"] and rng.randf() < 0.4:
			fm = "carpet" if fm == "wood_floor" else "wood_floor"
		inr.quad(fm, P(rc.position.x, 0.03, rc.end.y), P(rc.end.x, 0.03, rc.end.y), P(rc.end.x, 0.03, rc.position.y), P(rc.position.x, 0.03, rc.position.y))
	inr.quad("ceiling", P(0, H, 0), P(w, H, 0), P(w, H, d), P(0, H, d))
	if style.get("gable", false):
		var along_x := w >= d
		var span := (d if along_x else w) * 0.5
		var length := w if along_x else d
		var e := 0.45
		var rh := span * 0.55 + 0.4
		var y0 := H - 0.2
		var q := func(a: float, y: float, b: float) -> Vector3:
			return P(a, y, b) if along_x else P(b, y, a)
		var s := span * 2.0
		var quads := [
			[q.call(-e, y0, s + e), q.call(length + e, y0, s + e), q.call(length + e, H + rh, span), q.call(-e, H + rh, span)],
			[q.call(length + e, y0, -e), q.call(-e, y0, -e), q.call(-e, H + rh, span), q.call(length + e, H + rh, span)],
		]
		for qd in quads:
			if along_x:
				ext.quad("roof", qd[0], qd[1], qd[2], qd[3])
			else:
				ext.quad("roof", qd[1], qd[0], qd[3], qd[2])
		var g0 := [q.call(0, H, 0), q.call(0, H, s), q.call(0, H + rh * span / (span + e), span)]
		var g1 := [q.call(length, H, s), q.call(length, H, 0), q.call(length, H + rh * span / (span + e), span)]
		for g in [g0, g1]:
			if along_x:
				ext.tri(ext_mat, g[0], g[1], g[2], tint)
			else:
				ext.tri(ext_mat, g[1], g[0], g[2], tint)
	else:
		# flat roof with a parapet
		ext.box(Transform3D(Basis(), P(w * 0.5, H + 0.1, d * 0.5)), Vector3(w + T, 0.2, d + T), "roof", Color.WHITE, MeshBuilder.PY)
		var ph := 0.9
		for sd in [["n", w, Vector3(w * 0.5, 0, 0), true], ["s", w, Vector3(w * 0.5, 0, d), true], ["w", d, Vector3(0, 0, d * 0.5), false], ["e", d, Vector3(w, 0, d * 0.5), false]]:
			var c: Vector3 = sd[2]
			var size := Vector3(sd[1] + T, ph, T) if sd[3] else Vector3(T, ph, sd[1] - T)
			ext.box(Transform3D(Basis(), P(c.x, H + ph * 0.5, c.z)), size, ext_mat, tint)


# --- furniture -----------------------------------------------------------------------------
func _fb(xf: Transform3D, c: Vector3, s: Vector3, mat: String, face_mats: Dictionary = {}, color: Color = Color.WHITE) -> void:
	inr.box(xf * Transform3D(Basis(), c), s, mat, color, MeshBuilder.ALL & ~MeshBuilder.NY, face_mats)


func _furnish(room: Dictionary) -> void:
	var role: String = room["role"]
	var rc: Rect2 = room["rect"]
	var list: Array = ROOM_FURN.get(role, []).duplicate()
	# free intervals along each wall: [side, start, end]
	var slots: Array = []
	for side in ["n", "s", "w", "e"]:
		var length := rc.size.x if side in ["n", "s"] else rc.size.y
		var lo := 0.2 if side in ["n", "s"] else 1.15
		var ivs: Array = [[lo, length - lo]]
		for q: Vector2 in room["doors"]:
			var t := _room_t(rc, side, q)
			if t > -900.0:
				ivs = _cut(ivs, t - 1.0, t + 1.0)
			else:
				# door on a perpendicular wall close to this wall's corner
				var near := _corner_block(rc, side, q)
				if near == 0:
					ivs = _cut(ivs, 0.0, 1.35)
				elif near == 1:
					ivs = _cut(ivs, length - 1.35, length)
		var wins: Array = []
		for q: Vector2 in room["wins"]:
			var t := _room_t(rc, side, q)
			if t > -900.0:
				wins.append(t)
		for iv in ivs:
			slots.append({"side": side, "a": iv[0], "b": iv[1], "wins": wins})
	# shuffle
	for i in range(slots.size() - 1, 0, -1):
		var j := rng.randi_range(0, i)
		var tmp = slots[i]
		slots[i] = slots[j]
		slots[j] = tmp
	var fill := ""
	for item: String in list:
		if item.begins_with("*"):
			fill = item.substr(1)
			continue
		_place_on_wall(room, slots, item)
	if fill != "":
		for i in 14:
			if not _place_on_wall(room, slots, fill):
				break
	_centre_pieces(room)


func _room_t(rc: Rect2, side: String, q: Vector2) -> float:
	match side:
		"n":
			if absf(q.y - rc.position.y) < 0.15 and q.x > rc.position.x - 0.1 and q.x < rc.end.x + 0.1:
				return q.x - rc.position.x
		"s":
			if absf(q.y - rc.end.y) < 0.15 and q.x > rc.position.x - 0.1 and q.x < rc.end.x + 0.1:
				return q.x - rc.position.x
		"w":
			if absf(q.x - rc.position.x) < 0.15 and q.y > rc.position.y - 0.1 and q.y < rc.end.y + 0.1:
				return q.y - rc.position.y
		"e":
			if absf(q.x - rc.end.x) < 0.15 and q.y > rc.position.y - 0.1 and q.y < rc.end.y + 0.1:
				return q.y - rc.position.y
	return -999.0


## 0 = door sits near this wall's start corner, 1 = near its end corner, -1 = not near.
func _corner_block(rc: Rect2, side: String, q: Vector2) -> int:
	var wall_pt_start: Vector2
	var wall_pt_end: Vector2
	match side:
		"n":
			wall_pt_start = rc.position
			wall_pt_end = Vector2(rc.end.x, rc.position.y)
		"s":
			wall_pt_start = Vector2(rc.position.x, rc.end.y)
			wall_pt_end = rc.end
		"w":
			wall_pt_start = rc.position
			wall_pt_end = Vector2(rc.position.x, rc.end.y)
		_:
			wall_pt_start = Vector2(rc.end.x, rc.position.y)
			wall_pt_end = rc.end
	if q.distance_to(wall_pt_start) < 1.5:
		return 0
	if q.distance_to(wall_pt_end) < 1.5:
		return 1
	return -1


func _cut(ivs: Array, a: float, b: float) -> Array:
	var out: Array = []
	for iv in ivs:
		if b <= iv[0] or a >= iv[1]:
			out.append(iv)
			continue
		if a - iv[0] > 0.45:
			out.append([iv[0], a])
		if iv[1] - b > 0.45:
			out.append([b, iv[1]])
	return out


func _place_on_wall(room: Dictionary, slots: Array, item: String) -> bool:
	var f: Array = FURN[item]
	var fw: float = f[0]
	var fd: float = f[1]
	var fh: float = f[2]
	var rc: Rect2 = room["rect"]
	for sl in slots:
		if fd > (rc.size.y if sl["side"] in ["n", "s"] else rc.size.x) - 0.8:
			continue
		var s: float = sl["a"]
		var placed := false
		while s + fw <= sl["b"] + 0.001:
			var clash := false
			if fh > 0.95:
				for wt: float in sl["wins"]:
					if s < wt + 0.8 and s + fw > wt - 0.8:
						clash = true
						s = wt + 0.82
						break
			if not clash:
				placed = true
				break
		if not placed:
			continue
		sl["a"] = s + fw + 0.1
		var t := s + fw * 0.5
		var off := fd * 0.5 + T * 0.5 + 0.02
		var pos: Vector3
		var yaw: float
		match sl["side"]:
			"n":
				pos = P(rc.position.x + t, 0, rc.position.y + off)
				yaw = PI
			"s":
				pos = P(rc.position.x + t, 0, rc.end.y - off)
				yaw = 0.0
			"w":
				pos = P(rc.position.x + off, 0, rc.position.y + t)
				yaw = -PI * 0.5
			_:
				pos = P(rc.end.x - off, 0, rc.position.y + t)
				yaw = PI * 0.5
		_furniture(item, Transform3D(Basis(Vector3.UP, yaw), pos), room)
		return true
	return false


func _centre_pieces(room: Dictionary) -> void:
	var rc: Rect2 = room["rect"]
	var role: String = room["role"]
	var c := rc.get_center()
	if role in ["kitchen", "living", "living_kitchen", "office"] and minf(rc.size.x, rc.size.y) >= 4.2:
		if _clear_of_doors(room, c, 1.6):
			_table(Transform3D(Basis(Vector3.UP, 0.0 if rc.size.x > rc.size.y else PI * 0.5), P(c.x, 0, c.y)), 1.5, 0.9)
	elif role.begins_with("shop_") or role == "pharmacy":
		var inner := rc.grow(-2.1)
		var along_x := inner.size.x >= inner.size.y
		var long := inner.size.x if along_x else inner.size.y
		var short := inner.size.y if along_x else inner.size.x
		if long >= 2.0 and short >= 0.0:
			var rows := int(floor(short / 2.3)) + 1
			var cols := int(floor((long - 2.0) / 2.3)) + 1
			for i in rows:
				for j in cols:
					var a := (inner.position.x if along_x else inner.position.y) + 1.0 + j * 2.3
					var b := (inner.position.y if along_x else inner.position.x) + i * 2.3
					var p := Vector2(a, b) if along_x else Vector2(b, a)
					if not _clear_of_doors(room, p, 1.9):
						continue
					_furniture("aisle", Transform3D(Basis(Vector3.UP, 0.0 if along_x else PI * 0.5), P(p.x, 0, p.y)), room)
	elif role == "diner":
		var inner := rc.grow(-1.9)
		var nx := int(inner.size.x / 2.6) + 1
		var nz := int(inner.size.y / 2.6) + 1
		for i in nx:
			for j in nz:
				var p := inner.position + Vector2(i * 2.6, j * 2.6)
				if _clear_of_doors(room, p, 1.5) and rng.randf() < 0.8:
					_table(Transform3D(Basis(Vector3.UP, rng.randf_range(-0.2, 0.2)), P(p.x, 0, p.y)), 1.1, 1.1)
	elif role == "hall_church":
		var along_z := front in ["n", "s"]
		var length := rc.size.y if along_z else rc.size.x
		var n := int((length - 6.0) / 1.5)
		for i in n:
			for sgn in [-1.0, 1.0]:
				var t := 3.5 + i * 1.5
				var p := Vector2(c.x + sgn * 2.2, rc.position.y + t) if along_z else Vector2(rc.position.x + t, c.y + sgn * 2.2)
				var xf := Transform3D(Basis(Vector3.UP, 0.0 if along_z else PI * 0.5), P(p.x, 0, p.y))
				_fb(xf, Vector3(0, 0.42, 0), Vector3(2.4, 0.08, 0.45), "wood")
				_fb(xf, Vector3(0, 0.6, 0.2), Vector3(2.4, 0.5, 0.06), "wood")
				_fb(xf, Vector3(-1.15, 0.21, 0), Vector3(0.08, 0.42, 0.45), "wood")
				_fb(xf, Vector3(1.15, 0.21, 0), Vector3(0.08, 0.42, 0.45), "wood")
				_collide_local(xf, Vector3(0, 0.45, 0), Vector3(2.4, 0.9, 0.5))
		# altar at the back (opposite the front door)
		var ap: Vector2
		match front:
			"s": ap = Vector2(c.x, rc.position.y + 1.4)
			"n": ap = Vector2(c.x, rc.end.y - 1.4)
			"e": ap = Vector2(rc.position.x + 1.4, c.y)
			_: ap = Vector2(rc.end.x - 1.4, c.y)
		_table(Transform3D(Basis(Vector3.UP, 0.0 if along_z else PI * 0.5), P(ap.x, 0, ap.y)), 2.0, 0.8)
	elif role == "hall_garage":
		town.place_escape_car(P(c.x, 0.03, c.y), 0.0 if front in ["n", "s"] else PI * 0.5, node)


func _clear_of_doors(room: Dictionary, p: Vector2, r: float) -> bool:
	for q: Vector2 in room["doors"]:
		if q.distance_to(p) < r:
			return false
	return true


func _collide_local(xf: Transform3D, c: Vector3, s: Vector3) -> void:
	ext.collide(xf * Transform3D(Basis(), c), s)


func _table(xf: Transform3D, tw: float, td: float) -> void:
	_fb(xf, Vector3(0, 0.72, 0), Vector3(tw, 0.06, td), "wood")
	for sx in [-1.0, 1.0]:
		for sz in [-1.0, 1.0]:
			_fb(xf, Vector3(sx * (tw * 0.5 - 0.08), 0.345, sz * (td * 0.5 - 0.08)), Vector3(0.07, 0.69, 0.07), "wood")
	_collide_local(xf, Vector3(0, 0.375, 0), Vector3(tw, 0.75, td))


## Emits the furniture mesh; local -Z faces into the room.
func _furniture(item: String, xf: Transform3D, room: Dictionary) -> void:
	var fw := 2.0
	var fd := 0.9
	var fh := 1.5
	var kind := "store_shelf"
	if item != "aisle":
		var f: Array = FURN[item]
		fw = f[0]
		fd = f[1]
		fh = f[2]
		kind = f[3]
	var goods := "shelf_goods" if rng.randf() < 0.7 else "shelf_empty"
	match item:
		"fridge":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "appliance", {"nz": "appliance"})
		"counter":
			_fb(xf, Vector3(0, 0.43, 0), Vector3(fw, 0.86, fd), "wood")
			_fb(xf, Vector3(0, 0.88, -0.01), Vector3(fw + 0.02, 0.05, fd + 0.04), "concrete_floor", {}, Color(1.3, 1.3, 1.3))
		"sofa":
			_fb(xf, Vector3(0, 0.22, -0.05), Vector3(fw, 0.44, fd - 0.1), "fabric")
			_fb(xf, Vector3(0, 0.55, fd * 0.5 - 0.11), Vector3(fw, 0.5, 0.22), "fabric")
			_fb(xf, Vector3(-fw * 0.5 + 0.1, 0.4, -0.05), Vector3(0.2, 0.3, fd - 0.1), "fabric")
			_fb(xf, Vector3(fw * 0.5 - 0.1, 0.4, -0.05), Vector3(0.2, 0.3, fd - 0.1), "fabric")
		"sideboard", "nightstand":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "wood")
			_fb(xf, Vector3(0, fh * 0.55, -fd * 0.5 - 0.01), Vector3(fw * 0.3, 0.04, 0.03), "metal_dark")
		"bookshelf":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "wood", {"nz": "shelf_goods"})
		"bed":
			_fb(xf, Vector3(0, 0.15, 0), Vector3(fw, 0.3, fd), "wood")
			_fb(xf, Vector3(0, 0.4, -0.02), Vector3(fw - 0.06, 0.2, fd - 0.1), "mattress")
			_fb(xf, Vector3(0, 0.53, fd * 0.5 - 0.3), Vector3(fw - 0.4, 0.1, 0.35), "mattress", {}, Color(1.2, 1.2, 1.2))
			_fb(xf, Vector3(0, 0.44, -0.45), Vector3(fw - 0.02, 0.14, fd * 0.5), "fabric_red" if room["role"] != "cells" else "fabric")
			_fb(xf, Vector3(0, 0.45, fd * 0.5 - 0.03), Vector3(fw, 0.9, 0.06), "wood")
		"wardrobe":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "wood", {"nz": "door"})
		"toilet":
			_fb(xf, Vector3(0, 0.2, -0.08), Vector3(0.4, 0.4, 0.5), "appliance")
			_fb(xf, Vector3(0, 0.55, 0.23), Vector3(0.45, 0.4, 0.2), "appliance")
		"medcab":
			_fb(xf, Vector3(0, 1.4, 0), Vector3(fw, 0.6, fd), "appliance")
			_fb(xf, Vector3(0, 0.42, -0.1), Vector3(fw, 0.84, 0.4), "appliance")
		"bathtub":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "appliance")
			_fb(xf, Vector3(0, fh, 0), Vector3(fw - 0.2, 0.02, fd - 0.2), "metal_dark")
		"store_shelf", "barshelf":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "metal", {"nz": goods})
		"aisle":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "metal", {"nz": goods, "pz": goods})
		"checkout":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "wood")
			_fb(xf, Vector3(0, fh + 0.02, 0), Vector3(fw + 0.04, 0.05, fd + 0.04), "metal")
			_fb(xf, Vector3(fw * 0.25, fh + 0.2, 0), Vector3(0.4, 0.3, 0.4), "metal_dark")
		"freezer":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "appliance")
			_fb(xf, Vector3(0, fh + 0.02, 0), Vector3(fw - 0.1, 0.04, fd - 0.1), "glass")
		"desk":
			_fb(xf, Vector3(0, 0.73, 0), Vector3(fw, 0.06, fd), "wood")
			_fb(xf, Vector3(-fw * 0.5 + 0.2, 0.35, 0), Vector3(0.4, 0.7, fd - 0.06), "wood")
			_fb(xf, Vector3(fw * 0.5 - 0.04, 0.35, 0), Vector3(0.06, 0.7, fd - 0.06), "wood")
		"filing":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "metal")
			for i in 3:
				_fb(xf, Vector3(0, 0.3 + i * 0.4, -fd * 0.5 - 0.01), Vector3(0.2, 0.03, 0.03), "metal_dark")
		"locker":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "metal_green", {"nz": "metal_door"})
		"gunlocker":
			_fb(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd), "metal_dark", {"nz": "metal_door"})
		"crate":
			var s := rng.randf_range(0.8, 1.0)
			_fb(xf, Vector3(0, fh * 0.5 * s, 0), Vector3(fw * s, fh * s, fd * s), "wood", {}, Color(1.25, 1.15, 0.95))
		"toolbench":
			_fb(xf, Vector3(0, 0.86, 0), Vector3(fw, 0.08, fd), "wood")
			_fb(xf, Vector3(0, 0.41, 0.05), Vector3(fw - 0.1, 0.82, fd - 0.2), "metal_dark")
			_fb(xf, Vector3(0, 1.4, fd * 0.5 - 0.03), Vector3(fw, 0.9, 0.04), "metal")
	var cont_xf := xf
	if kind == "bed":
		var bed := Props.Bed.new()
		node.add_child(bed)
		bed.setup(Vector3(fw, fh, fd), cont_xf)
		town.beds.append(bed)
		town.add_obstacle(xf * Transform3D(Basis(), Vector3(0, fh * 0.5, 0)), Vector3(fw, fh, fd))
		return
	if kind == "":
		_collide_local(xf, Vector3(0, fh * 0.5, 0), Vector3(fw, fh, fd))
		return
	if kind == "store_shelf":
		match btype:
			"store", "gas", "diner": kind = "food_shelf"
			"hardware", "garage": kind = "tool_shelf"
			"clinic": kind = "med_shelf"
			"police": kind = "locker"
			_: kind = "crate"
	var lc := LootContainer.new()
	node.add_child(lc)
	lc.setup(kind, Vector3(fw, fh, fd), cont_xf)
	if kind == "gunlocker":
		lc.locked_key = "police_key"
	elif item in ["aisle", "store_shelf"] and goods == "shelf_empty":
		lc.items = []          # already picked clean
	lc.set_meta("btype", btype)
	lc.set_meta("role", room["role"])
	town.containers.append(lc)
	town.add_obstacle(xf * Transform3D(Basis(), Vector3(0, fh * 0.5, 0)), Vector3(fw, fh, fd))


# --- lights & signs ---------------------------------------------------------------------------
func _lights() -> void:
	var col: Color = style.get("light", Color(1.0, 0.78, 0.5))
	var n := 0
	for r in rooms:
		var rc: Rect2 = r["rect"]
		if rc.get_area() < 9.0 or n >= 4:
			continue
		var c := rc.get_center()
		var lit := powered and (n == 0 or rng.randf() < 0.6)
		# fixture
		inr.box(Transform3D(Basis(), P(c.x, H - 0.04, c.y)), Vector3(0.9, 0.06, 0.25), "appliance", Color(2.0, 2.0, 1.8) if lit else Color(0.5, 0.5, 0.5))
		if not lit:
			continue
		n += 1
		var l := OmniLight3D.new()
		l.position = P(c.x, H - 0.45, c.y)
		l.omni_range = clampf(maxf(rc.size.x, rc.size.y) * 0.9, 5.0, 9.0)
		l.light_energy = 1.5
		l.light_color = col
		l.omni_attenuation = 1.3
		l.shadow_enabled = false
		l.distance_fade_enabled = true
		l.distance_fade_begin = 38.0
		l.distance_fade_length = 8.0
		node.add_child(l)
		town.add_light(l, rng.randf() < 0.45)


func _sign() -> void:
	if not style.has("sign"):
		return
	var sw := minf(W - 2.0, 5.0)
	var y0 := H + 0.05
	var y1 := H + 0.85
	var off := T * 0.5 + 0.03
	var m: String = style["sign"]
	match front:
		"s": ext.quad(m, P(w * 0.5 - sw * 0.5, y0, d + off), P(w * 0.5 + sw * 0.5, y0, d + off), P(w * 0.5 + sw * 0.5, y1, d + off), P(w * 0.5 - sw * 0.5, y1, d + off), Color.WHITE, true)
		"n": ext.quad(m, P(w * 0.5 + sw * 0.5, y0, -off), P(w * 0.5 - sw * 0.5, y0, -off), P(w * 0.5 - sw * 0.5, y1, -off), P(w * 0.5 + sw * 0.5, y1, -off), Color.WHITE, true)
		"e": ext.quad(m, P(w + off, y0, d * 0.5 + sw * 0.5), P(w + off, y0, d * 0.5 - sw * 0.5), P(w + off, y1, d * 0.5 - sw * 0.5), P(w + off, y1, d * 0.5 + sw * 0.5), Color.WHITE, true)
		_: ext.quad(m, P(-off, y0, d * 0.5 - sw * 0.5), P(-off, y0, d * 0.5 + sw * 0.5), P(-off, y1, d * 0.5 + sw * 0.5), P(-off, y1, d * 0.5 - sw * 0.5), Color.WHITE, true)

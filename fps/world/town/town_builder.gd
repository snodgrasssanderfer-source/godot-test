class_name TownBuilder
extends RefCounted
## Builds greybox level geometry from boxes. A level is a set of lots; a lot is
## a Node3D with its own frame (so a building is laid out square to its street
## front, wherever the street curves) and a list of boxes given by two corners.
##
## Every box carries a material tag (&"asphalt", &"brick", &"glass", ...). On
## commit each lot gets one mesh per tag and one StaticBody3D holding a
## BoxShape3D per solid box: solid shapes, which cannot be walked into the way
## a hollow triangle shell can. Tags are flat colours for now; texturing later
## means swapping the material of a tag. UVs are in metres.
##
## Everything built is also kept as plain data (dump()) so that floor plans can
## be drawn outside the engine and tests can ask where things are.

enum Opening { DOOR, DOOR_CLOSED, WINDOW, WINDOW_OPEN, SHUTTER }

const LAYER_WORLD := 1
## Tag -> colour. Unknown tags come out magenta, on purpose.
const COLORS := {
	&"terrain": Color(0.10, 0.12, 0.09), &"grass": Color(0.13, 0.17, 0.10),
	&"asphalt": Color(0.07, 0.07, 0.075), &"road_paint": Color(0.55, 0.52, 0.40),
	&"sidewalk": Color(0.30, 0.30, 0.29), &"concrete": Color(0.24, 0.24, 0.23),
	&"brick": Color(0.30, 0.11, 0.08), &"siding": Color(0.42, 0.43, 0.39),
	&"siding_b": Color(0.33, 0.37, 0.38), &"siding_c": Color(0.44, 0.38, 0.30),
	&"block": Color(0.36, 0.36, 0.34), &"roof": Color(0.09, 0.09, 0.10),
	&"wall_in": Color(0.47, 0.44, 0.38), &"floor": Color(0.26, 0.21, 0.15),
	&"floor_tile": Color(0.38, 0.38, 0.36), &"ceiling": Color(0.40, 0.39, 0.36),
	&"wood": Color(0.25, 0.16, 0.09), &"metal": Color(0.18, 0.19, 0.20),
	&"furniture": Color(0.20, 0.15, 0.11), &"shelf": Color(0.33, 0.30, 0.25),
	&"counter": Color(0.36, 0.20, 0.12), &"appliance": Color(0.62, 0.61, 0.56),
	&"door": Color(0.20, 0.13, 0.08), &"shutter": Color(0.34, 0.35, 0.36),
	&"glass": Color(0.42, 0.60, 0.68, 0.28), &"canopy": Color(0.62, 0.60, 0.55),
	&"car": Color(0.05, 0.06, 0.09), &"car_white": Color(0.66, 0.67, 0.68),
	&"log": Color(0.21, 0.13, 0.07), &"pole": Color(0.15, 0.10, 0.06),
	&"fence": Color(0.33, 0.34, 0.33), &"sign": Color(0.55, 0.12, 0.10),
	&"bark": Color(0.07, 0.06, 0.05), &"oil": Color(0.02, 0.02, 0.022),
}
## Tags that reflect a little (wet-looking), the rest are matt.
## Tags that wear a real material: [set in res://assets/materials (put there by
## tools/install_texture.py), metres one repeat of the texture covers, tint].
## Tags not listed here, or whose set is not installed, stay a flat colour.
const TEXTURED := {
	&"asphalt": ["Asphalt015", 3.0, Color(0.62, 0.62, 0.64)],
	&"sidewalk": ["Concrete040", 2.5, Color(0.9, 0.9, 0.88)],
	&"concrete": ["Concrete033", 3.0, Color(0.85, 0.85, 0.83)],
	&"grass": ["grass_path_3", 3.0, Color(0.75, 0.8, 0.7)],
	&"terrain": ["grass_path_3", 4.0, Color(0.5, 0.55, 0.48)],
	&"brick": ["red_brick", 2.0, Color(0.9, 0.82, 0.8)],
	&"block": ["concrete_block_wall_02", 2.0, Color(0.9, 0.9, 0.88)],
	&"siding": ["C14_PeelingClapboard", 2.0, Color(0.95, 0.95, 0.92)],
	&"siding_b": ["WoodSiding009", 2.0, Color(0.72, 0.8, 0.84)],
	&"siding_c": ["WoodSiding009", 2.0, Color(0.92, 0.82, 0.66)],
	&"roof": ["grey_roof_01", 2.0, Color(0.7, 0.7, 0.72)],
	&"wall_in": ["D10_LatexDrywall", 2.8, Color(0.95, 0.92, 0.84)],
	&"floor": ["WoodFloor051", 2.0, Color(0.8, 0.74, 0.66)],
	&"floor_tile": ["D24_VCTFloorTiles", 2.0, Color(0.9, 0.9, 0.88)],
	&"ceiling": ["OfficeCeiling001", 2.4, Color(0.85, 0.84, 0.8)],
	&"wood": ["worn_planks", 2.0, Color(0.8, 0.72, 0.62)],
}
## Set false to see the plain greybox colours again.
static var use_textures := true
const SMOOTH := [&"asphalt", &"glass", &"car", &"car_white", &"appliance", &"oil", &"metal"]


class Box:
	var tag: StringName
	var xform: Transform3D          # lot space, at the centre of the box
	var size: Vector3
	var solid := true
	var visible := true
	var level := 0                  # storey it belongs to, for floor plans
	var plan := true                # false: leave it off floor plans


class Marker:
	var id: StringName
	var position: Vector3           # lot space
	var label: String               # English, shown in the engine
	var label_zh: String            # Chinese, for the drawn plans
	var level := 0


class Lot:
	var name: String
	var title_zh: String
	var xform: Transform3D          # lot space -> world
	var boxes: Array[Box] = []
	var markers: Array[Marker] = []
	## Height of each storey's floor in this lot, by level.
	var floors: Array[float] = [0.0]
	var level := 0                  # storey that boxes added from now on belong to
	var node: Node3D


	func set_level(index: int, floor_y: float) -> void:
		while floors.size() <= index:
			floors.append(floor_y)
		floors[index] = floor_y
		level = index


	## An axis-aligned box between two opposite corners, in lot space.
	func box(tag: StringName, a: Vector3, b: Vector3, solid := true, visible := true) -> Box:
		var lo := Vector3(minf(a.x, b.x), minf(a.y, b.y), minf(a.z, b.z))
		var hi := Vector3(maxf(a.x, b.x), maxf(a.y, b.y), maxf(a.z, b.z))
		var piece := Box.new()
		piece.tag = tag
		piece.xform = Transform3D(Basis.IDENTITY, (lo + hi) * 0.5)
		piece.size = hi - lo
		piece.solid = solid
		piece.visible = visible
		piece.level = level
		boxes.append(piece)
		return piece


	## A box of a given size turned any way (ramps, toppled things).
	func rbox(tag: StringName, centre: Vector3, size: Vector3, euler_deg: Vector3, solid := true,
			visible := true) -> Box:
		var piece := Box.new()
		piece.tag = tag
		piece.xform = Transform3D(Basis.from_euler(euler_deg * (PI / 180.0)), centre)
		piece.size = size
		piece.solid = solid
		piece.visible = visible
		piece.level = level
		boxes.append(piece)
		return piece


	## A wall along X at depth z (its centre line), from x0 to x1 and y0 to y1.
	## openings: [[kind, start, width, sill = 0.9, head = 2.05]]; start is the x
	## coordinate where the opening begins; sill and head are heights above y0.
	func wall_x(tag: StringName, z: float, x0: float, x1: float, y0: float, y1: float, thickness := 0.2,
			openings: Array = []) -> void:
		_wall(tag, true, z, x0, x1, y0, y1, thickness, openings)


	## The same along Z at a given x.
	func wall_z(tag: StringName, x: float, z0: float, z1: float, y0: float, y1: float, thickness := 0.2,
			openings: Array = []) -> void:
		_wall(tag, false, x, z0, z1, y0, y1, thickness, openings)


	func _wall(tag: StringName, along_x: bool, at: float, from: float, to: float, y0: float, y1: float,
			thickness: float, openings: Array) -> void:
		var h := thickness * 0.5
		var span := func(a: float, b: float, ya: float, yb: float, piece_tag: StringName, thick: float) -> void:
			if b - a < 0.005 or yb - ya < 0.005:
				return
			if along_x:
				box(piece_tag, Vector3(a, ya, at - thick), Vector3(b, yb, at + thick))
			else:
				box(piece_tag, Vector3(at - thick, ya, a), Vector3(at + thick, yb, b))
		var sorted := openings.duplicate()
		sorted.sort_custom(func(p: Array, q: Array) -> bool: return p[1] < q[1])
		var cursor := from
		for o: Array in sorted:
			var kind: Opening = o[0]
			var start: float = float(o[1])
			var end: float = start + float(o[2])
			var is_window := kind == Opening.WINDOW or kind == Opening.WINDOW_OPEN
			var sill: float = y0 + (float(o[3]) if o.size() > 3 else (0.9 if is_window else 0.0))
			var head: float = y0 + (float(o[4]) if o.size() > 4 else 2.05)
			head = minf(head, y1)
			span.call(cursor, start, y0, y1, tag, h)                # solid wall up to the opening
			span.call(start, end, y0, sill, tag, h)                 # under a window
			span.call(start, end, head, y1, tag, h)                 # the lintel
			match kind:
				Opening.DOOR_CLOSED:
					span.call(start, end, sill, head, &"door", h * 0.5)
				Opening.WINDOW:
					span.call(start, end, sill, head, &"glass", 0.02)
				Opening.SHUTTER:                                    # rolled up: the curtain hangs at the top
					span.call(start, end, head - 0.35, head, &"shutter", h * 0.6)
			cursor = end
		span.call(cursor, to, y0, y1, tag, h)


	## A flight of stairs. `foot` is the middle of the bottom edge, `dir` the
	## way it climbs (a unit axis in lot space). The treads are for show; what
	## feet and navigation use is one slanted slab under them.
	## `rails` adds a solid balustrade down both sides, so that nothing (player,
	## creature, navigation mesh) gets onto the flight except at its foot or head.
	func stairs(tag: StringName, foot: Vector3, dir: Vector3, rise: float, run: float, width: float,
			steps := 0, rails := false) -> void:
		var n := steps if steps > 0 else maxi(int(ceil(rise / 0.19)), 1)
		var side := Vector3.UP.cross(dir).normalized() * (width * 0.5)
		for i in n:
			var a := foot + dir * (run * i / n) - side
			var b := foot + dir * (run * (i + 1) / n) + side + Vector3.UP * (rise * (i + 1) / n)
			box(tag, a, b, false)
		var length := sqrt(rise * rise + run * run)
		var pitch := atan2(rise, run)
		var basis := Basis.looking_at(-dir, Vector3.UP).rotated(Vector3.UP.cross(dir).normalized(), -pitch)
		var slab := Box.new()
		slab.tag = tag
		slab.size = Vector3(width, 0.2, length)
		# the slab's top face runs from the foot to the head of the flight
		slab.xform = Transform3D(basis, foot + dir * (run * 0.5) + Vector3.UP * (rise * 0.5) - basis.y * 0.1)
		slab.visible = false
		slab.level = level
		slab.plan = false
		boxes.append(slab)
		if rails:
			for sgn: float in [-1.0, 1.0]:
				var rail := Box.new()
				rail.tag = tag
				# Stops 0.7 m short of the foot: the panel leans back over its lower end,
				# and a full-length one would stick out across the floor in front of the flight.
				rail.size = Vector3(0.05, 1.0, length - 0.7)
				rail.xform = Transform3D(basis, slab.xform.origin + basis.y * 0.6 + basis.z * 0.35
						+ basis.x * sgn * (width * 0.5 + 0.025))
				rail.level = level
				rail.plan = false
				boxes.append(rail)


	func marker(id: StringName, at: Vector3, label: String, label_zh: String) -> void:
		var m := Marker.new()
		m.id = id
		m.position = at
		m.label = label
		m.label_zh = label_zh
		m.level = level
		markers.append(m)


	func to_world(point: Vector3) -> Vector3:
		return xform * point


var lots: Array[Lot] = []
var _materials := {}


func add_lot(lot_name: String, xform: Transform3D, title_zh := "") -> Lot:
	var lot := Lot.new()
	lot.name = lot_name
	lot.title_zh = title_zh
	lot.xform = xform
	lots.append(lot)
	return lot


func find_lot(lot_name: String) -> Lot:
	for lot in lots:
		if lot.name == lot_name:
			return lot
	return null


## World position of a marker, or Vector3.INF.
func marker_position(id: StringName) -> Vector3:
	for lot in lots:
		for m in lot.markers:
			if m.id == id:
				return lot.xform * m.position
	return Vector3.INF


func material(tag: StringName) -> BaseMaterial3D:
	if not _materials.has(tag) and use_textures and TEXTURED.has(tag):
		var entry: Array = TEXTURED[tag]
		var path := "res://assets/materials/%s.tres" % entry[0]
		if ResourceLoader.exists(path):
			var textured: BaseMaterial3D = load(path).duplicate()
			# box UVs are in metres (see _add_box), so the scale is repeats per metre
			textured.uv1_scale = Vector3(1.0 / float(entry[1]), 1.0 / float(entry[1]), 1.0)
			textured.albedo_color = entry[2]
			textured.resource_name = String(tag)
			_materials[tag] = textured
	if not _materials.has(tag):
		var mat := StandardMaterial3D.new()
		var color: Color = COLORS.get(tag, Color.MAGENTA)
		mat.albedo_color = color
		mat.roughness = 0.35 if tag in SMOOTH else 0.9
		if color.a < 1.0:
			mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
			mat.cull_mode = BaseMaterial3D.CULL_DISABLED
		mat.resource_name = String(tag)
		_materials[tag] = mat
	return _materials[tag]


## Turns every lot into nodes under `parent`: one mesh per tag, one static body.
func commit(parent: Node3D) -> void:
	for lot in lots:
		var node := Node3D.new()
		node.name = lot.name
		parent.add_child(node)
		node.transform = lot.xform
		lot.node = node

		var by_tag := {}
		for piece in lot.boxes:
			if not piece.visible:
				continue
			if not by_tag.has(piece.tag):
				var st := SurfaceTool.new()
				st.begin(Mesh.PRIMITIVE_TRIANGLES)
				by_tag[piece.tag] = st
			_add_box(by_tag[piece.tag], piece)
		for tag: StringName in by_tag:
			var mi := MeshInstance3D.new()
			mi.name = String(tag)
			(by_tag[tag] as SurfaceTool).generate_tangents()        # normal maps need them
			mi.mesh = (by_tag[tag] as SurfaceTool).commit()
			mi.material_override = material(tag)
			if tag == &"glass":
				mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
			node.add_child(mi)

		var body := StaticBody3D.new()
		body.name = "Collision"
		body.collision_layer = LAYER_WORLD
		body.collision_mask = 0
		node.add_child(body)
		for piece in lot.boxes:
			if not piece.solid:
				continue
			var shape := BoxShape3D.new()
			shape.size = piece.size
			var cs := CollisionShape3D.new()
			cs.shape = shape
			cs.transform = piece.xform
			body.add_child(cs)


static func _add_box(st: SurfaceTool, piece: Box) -> void:
	var h := piece.size * 0.5
	# each face: normal, then the two in-plane axes (u, v) so that u x v = normal
	var faces := [
		[Vector3.RIGHT, Vector3.BACK, Vector3.UP], [Vector3.LEFT, Vector3.FORWARD, Vector3.UP],
		[Vector3.UP, Vector3.RIGHT, Vector3.BACK], [Vector3.DOWN, Vector3.RIGHT, Vector3.FORWARD],
		[Vector3.BACK, Vector3.LEFT, Vector3.UP], [Vector3.FORWARD, Vector3.RIGHT, Vector3.UP],
	]
	for f: Array in faces:
		var n: Vector3 = f[0]
		var u: Vector3 = f[1]
		var v: Vector3 = f[2]
		var hu := absf(u.dot(h))
		var hv := absf(v.dot(h))
		var centre := n * absf(n.dot(h))
		var corners := [centre - u * hu - v * hv, centre + u * hu - v * hv, centre + u * hu + v * hv, centre - u * hu + v * hv]
		var world_n := (piece.xform.basis * n).normalized()
		# UVs are the corner's position in the lot, in metres, measured along the
		# face: a texture then runs unbroken across the separate boxes of one wall.
		var lot_u := (piece.xform.basis * u).normalized()
		var lot_v := (piece.xform.basis * v).normalized()
		var uvs := []
		for c: Vector3 in corners:
			var at := piece.xform * c
			uvs.append(Vector2(at.dot(lot_u), -at.dot(lot_v)))
		# Godot's front faces wind clockwise seen from outside.
		var order := [0, 2, 1, 0, 3, 2]
		if (corners[2] - corners[0]).cross(corners[1] - corners[0]).dot(n) > 0.0:
			order = [0, 1, 2, 0, 2, 3]
		for i: int in order:
			st.set_normal(world_n)
			st.set_uv(uvs[i])
			st.add_vertex(piece.xform * corners[i])


## Everything as plain data, in world space: for drawing plans and for tests.
func dump() -> Dictionary:
	var out := {"lots": []}
	for lot in lots:
		var entry := {"name": lot.name, "title_zh": lot.title_zh, "floors": lot.floors,
				"origin": _v(lot.xform.origin), "yaw": lot.xform.basis.get_euler().y, "boxes": [], "markers": []}
		for piece in lot.boxes:
			if not piece.plan:
				continue
			var world := lot.xform * piece.xform
			# footprint corners (the box's local XZ rectangle), for boxes that stand upright
			var h := piece.size * 0.5
			var foot: Array = []
			for c: Vector2 in [Vector2(-1, -1), Vector2(1, -1), Vector2(1, 1), Vector2(-1, 1)]:
				var p := world * Vector3(c.x * h.x, 0.0, c.y * h.z)
				foot.append([snappedf(p.x, 0.001), snappedf(p.z, 0.001)])
			var upright := absf(world.basis.y.normalized().y) > 0.98
			entry["boxes"].append({"tag": String(piece.tag), "level": piece.level, "solid": piece.solid,
					"visible": piece.visible, "upright": upright, "foot": foot,
					"y0": snappedf(world.origin.y - h.y, 0.001), "y1": snappedf(world.origin.y + h.y, 0.001)})
		for m in lot.markers:
			entry["markers"].append({"id": String(m.id), "label": m.label, "label_zh": m.label_zh,
					"level": m.level, "at": _v(lot.xform * m.position)})
		out["lots"].append(entry)
	return out


static func _v(p: Vector3) -> Array:
	return [snappedf(p.x, 0.001), snappedf(p.y, 0.001), snappedf(p.z, 0.001)]

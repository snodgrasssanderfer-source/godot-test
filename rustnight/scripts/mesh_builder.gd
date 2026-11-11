class_name MeshBuilder
extends RefCounted
## Batches boxes/quads into one mesh (one surface per material) plus a list of
## collision boxes. The whole town is built with this at load time.

const PX := 1
const NX := 2
const PY := 4
const NY := 8
const PZ := 16
const NZ := 32
const ALL := 63
const SIDES := PX | NX | PZ | NZ

var _v := {}     # material name -> PackedVector3Array
var _n := {}
var _uv := {}
var _c := {}
var col: Array = []   # [Transform3D, Vector3 size]


func _ensure(mat: String) -> void:
	if not _v.has(mat):
		_v[mat] = PackedVector3Array()
		_n[mat] = PackedVector3Array()
		_uv[mat] = PackedVector2Array()
		_c[mat] = PackedColorArray()


## Corners are bottom-left, bottom-right, top-right, top-left as seen from the front.
func quad(mat: String, bl: Vector3, br: Vector3, tr: Vector3, tl: Vector3, color: Color = Color.WHITE, fit: bool = false, uvs: PackedVector2Array = PackedVector2Array()) -> void:
	_ensure(mat)
	var n := (br - bl).cross(tl - bl).normalized()
	var u: PackedVector2Array = uvs
	if u.is_empty():
		if fit:
			u = PackedVector2Array([Vector2(0, 1), Vector2(1, 1), Vector2(1, 0), Vector2(0, 0)])
		else:
			var t := Mat.tile(mat)
			u = PackedVector2Array()
			for p: Vector3 in [bl, br, tr, tl]:
				if absf(n.y) > 0.7:
					u.append(Vector2(p.x / t.x, p.z / t.y))
				elif absf(n.x) > 0.7:
					u.append(Vector2(p.z / t.x, 1.0 - p.y / t.y))
				else:
					u.append(Vector2(p.x / t.x, 1.0 - p.y / t.y))
	var vs: PackedVector3Array = _v[mat]
	var ns: PackedVector3Array = _n[mat]
	var us: PackedVector2Array = _uv[mat]
	var cs: PackedColorArray = _c[mat]
	for i: int in [0, 3, 2, 0, 2, 1]:
		vs.append([bl, br, tr, tl][i])
		ns.append(n)
		us.append(u[i])
		cs.append(color)
	_v[mat] = vs
	_n[mat] = ns
	_uv[mat] = us
	_c[mat] = cs


func tri(mat: String, a: Vector3, b: Vector3, c: Vector3, color: Color = Color.WHITE) -> void:
	_ensure(mat)
	var n := (b - a).cross(c - a).normalized()
	var t := Mat.tile(mat)
	for p: Vector3 in [a, c, b]:
		_v[mat].append(p)
		_n[mat].append(n)
		_uv[mat].append(Vector2((p.x + p.z) / t.x, 1.0 - p.y / t.y))
		_c[mat].append(color)


## Box centred on xf.origin. 'faces' selects which sides are emitted,
## 'face_mats' overrides the material per face ("px","nx","py","ny","pz","nz");
## overridden faces stretch the whole texture over the face.
func box(xf: Transform3D, size: Vector3, mat: String, color: Color = Color.WHITE, faces: int = ALL, face_mats: Dictionary = {}, fit_all: bool = false) -> void:
	var h := size * 0.5
	var defs := [
		[PX, "px", Vector3(h.x, -h.y, h.z), Vector3(h.x, -h.y, -h.z), Vector3(h.x, h.y, -h.z), Vector3(h.x, h.y, h.z)],
		[NX, "nx", Vector3(-h.x, -h.y, -h.z), Vector3(-h.x, -h.y, h.z), Vector3(-h.x, h.y, h.z), Vector3(-h.x, h.y, -h.z)],
		[PY, "py", Vector3(-h.x, h.y, h.z), Vector3(h.x, h.y, h.z), Vector3(h.x, h.y, -h.z), Vector3(-h.x, h.y, -h.z)],
		[NY, "ny", Vector3(-h.x, -h.y, -h.z), Vector3(h.x, -h.y, -h.z), Vector3(h.x, -h.y, h.z), Vector3(-h.x, -h.y, h.z)],
		[PZ, "pz", Vector3(-h.x, -h.y, h.z), Vector3(h.x, -h.y, h.z), Vector3(h.x, h.y, h.z), Vector3(-h.x, h.y, h.z)],
		[NZ, "nz", Vector3(h.x, -h.y, -h.z), Vector3(-h.x, -h.y, -h.z), Vector3(-h.x, h.y, -h.z), Vector3(h.x, h.y, -h.z)],
	]
	for d in defs:
		if faces & int(d[0]) == 0:
			continue
		var m: String = face_mats.get(d[1], mat)
		quad(m, xf * (d[2] as Vector3), xf * (d[3] as Vector3), xf * (d[4] as Vector3), xf * (d[5] as Vector3), color, fit_all or face_mats.has(d[1]))


func collide(xf: Transform3D, size: Vector3) -> void:
	col.append([xf, size])


func solid(xf: Transform3D, size: Vector3, mat: String, color: Color = Color.WHITE, faces: int = ALL, face_mats: Dictionary = {}) -> void:
	box(xf, size, mat, color, faces, face_mats)
	collide(xf, size)


func is_empty() -> bool:
	for k in _v:
		if _v[k].size() > 0:
			return false
	return true


func commit(parent: Node3D, layers: int = 1, shadows: bool = true) -> MeshInstance3D:
	var mesh := ArrayMesh.new()
	for mat: String in _v:
		if _v[mat].is_empty():
			continue
		var arr := []
		arr.resize(Mesh.ARRAY_MAX)
		arr[Mesh.ARRAY_VERTEX] = _v[mat]
		arr[Mesh.ARRAY_NORMAL] = _n[mat]
		arr[Mesh.ARRAY_TEX_UV] = _uv[mat]
		arr[Mesh.ARRAY_COLOR] = _c[mat]
		mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arr)
		mesh.surface_set_material(mesh.get_surface_count() - 1, Mat.get_mat(mat))
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.layers = layers
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON if shadows else GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	parent.add_child(mi)
	return mi


func make_body(parent: Node3D, layer: int = G.L_WORLD) -> StaticBody3D:
	var body := StaticBody3D.new()
	body.collision_layer = layer
	body.collision_mask = 0
	for c in col:
		var cs := CollisionShape3D.new()
		var shape := BoxShape3D.new()
		shape.size = c[1]
		cs.shape = shape
		cs.transform = c[0]
		body.add_child(cs)
	parent.add_child(body)
	return body

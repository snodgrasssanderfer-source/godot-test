class_name Fx
extends Node3D
## Pooled throw-away visuals: tracers, impact puffs, bullet holes, blood
## spray and splats, shell casings, wood debris.

var _tracers: Array = []
var _ti := 0
var _decals: Array = []
var _di := 0
var _bits: Array = []        # {n: MeshInstance3D, v: Vector3, life: float, grav: float, kind: String}
var _free_bits: Array = []
var _puffs: Array = []       # {n, life}
var _m_blood: StandardMaterial3D
var _m_brass: StandardMaterial3D
var _m_wood: StandardMaterial3D
var _m_puff: StandardMaterial3D
var _bit_mesh: BoxMesh
var _quad: QuadMesh


func _ready() -> void:
	var tm := BoxMesh.new()
	tm.size = Vector3(0.012, 0.012, 1.0)
	var tmat := Mat.unshaded("", true, Color(1.0, 0.85, 0.5, 0.7))
	for i in 12:
		var t := MeshInstance3D.new()
		t.mesh = tm
		t.material_override = tmat
		t.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		t.visible = false
		add_child(t)
		_tracers.append([t, 0.0])
	_quad = QuadMesh.new()
	_quad.size = Vector2(1, 1)
	for i in (30 if G.low_spec else 64):
		var d := MeshInstance3D.new()
		d.mesh = _quad
		d.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		d.layers = G.VIS_OUT | G.VIS_IN
		d.visible = false
		add_child(d)
		_decals.append(d)
	_bit_mesh = BoxMesh.new()
	_bit_mesh.size = Vector3(1, 1, 1)
	_m_blood = _flat(Color(0.42, 0.03, 0.03))
	_m_brass = _flat(Color(0.75, 0.58, 0.22))
	_m_wood = _flat(Color(0.36, 0.26, 0.16))
	_m_puff = Mat.unshaded("puff").duplicate()
	_m_puff.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	_m_puff.albedo_color = Color(0.6, 0.58, 0.54)


func _flat(c: Color) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = c
	m.roughness = 1.0
	m.metallic_specular = 0.0
	return m


func _process(delta: float) -> void:
	for t in _tracers:
		if t[1] > 0.0:
			t[1] -= delta
			if t[1] <= 0.0:
				(t[0] as MeshInstance3D).visible = false
	for i in range(_bits.size() - 1, -1, -1):
		var b: Dictionary = _bits[i]
		b["life"] -= delta
		var n: MeshInstance3D = b["n"]
		b["v"].y -= b["grav"] * delta
		n.position += b["v"] * delta
		n.rotation += Vector3(7, 5, 3) * delta
		if n.position.y < 0.03:
			n.position.y = 0.03
			if b["kind"] == "brass" and b["v"].y < -1.0:
				Sfx.play_at("shell_drop", n.position, -8.0, randf_range(0.9, 1.2), 12.0)
				b["v"] = Vector3(b["v"].x * 0.3, -b["v"].y * 0.3, b["v"].z * 0.3)
			else:
				b["v"] = Vector3.ZERO
				b["grav"] = 0.0
		if b["life"] <= 0.0:
			n.visible = false
			_free_bits.append(n)
			_bits.remove_at(i)
	for i in range(_puffs.size() - 1, -1, -1):
		var pf: Dictionary = _puffs[i]
		pf["life"] -= delta
		var pn: MeshInstance3D = pf["n"]
		pn.scale += Vector3.ONE * delta * 1.6
		pn.position.y += delta * 0.3
		if pf["life"] <= 0.0:
			pn.visible = false
			_free_bits.append(pn)
			_puffs.remove_at(i)


func _bit(mat: Material, size: float) -> MeshInstance3D:
	var n: MeshInstance3D
	if _free_bits.is_empty():
		n = MeshInstance3D.new()
		n.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		n.layers = G.VIS_OUT | G.VIS_IN
		add_child(n)
	else:
		n = _free_bits.pop_back()
	n.mesh = _bit_mesh
	n.material_override = mat
	n.scale = Vector3.ONE * size
	n.rotation = Vector3(randf() * 3.0, randf() * 3.0, 0)
	n.visible = true
	return n


func tracer(from: Vector3, to: Vector3) -> void:
	var d := to - from
	var l := d.length()
	if l < 1.5:
		return
	var e: Array = _tracers[_ti]
	_ti = (_ti + 1) % _tracers.size()
	var t: MeshInstance3D = e[0]
	# a streak along the last part of the path looks more like a round in flight
	var seg := minf(l, 14.0)
	var mid := from + d / l * (minf(l, 4.0 + randf() * 6.0) + seg * 0.5)
	if (mid - from).length() + seg * 0.5 > l:
		mid = to - d / l * seg * 0.5
	t.global_transform = Transform3D(Basis.looking_at(d / l, Vector3.UP if absf(d.y / l) < 0.98 else Vector3.RIGHT), mid)
	t.scale = Vector3(1, 1, seg)
	t.visible = true
	e[1] = 0.045


func _decal(pos: Vector3, normal: Vector3, tex: String, size: float) -> void:
	var d: MeshInstance3D = _decals[_di]
	_di = (_di + 1) % _decals.size()
	d.material_override = Mat.get_mat(tex)
	var up := Vector3.UP if absf(normal.y) < 0.9 else Vector3.RIGHT
	d.global_transform = Transform3D(Basis.looking_at(-normal, up), pos + normal * 0.012)
	d.rotate_object_local(Vector3.FORWARD, randf() * TAU)
	d.scale = Vector3.ONE * size
	d.visible = true


func impact(pos: Vector3, normal: Vector3, wood: bool) -> void:
	_decal(pos, normal, "bullet_hole", 0.09)
	var n := _bit(_m_puff, 0.18)
	n.mesh = _quad
	n.rotation = Vector3.ZERO
	n.position = pos + normal * 0.06
	_puffs.append({"n": n, "life": 0.22})
	for i in 2:
		var b := _bit(_m_wood if wood else _m_puff, 0.025)
		if not wood:
			b.material_override = _m_wood
		b.position = pos + normal * 0.03
		_bits.append({"n": b, "v": (normal + Vector3(randf_range(-0.6, 0.6), randf_range(0.2, 1.0), randf_range(-0.6, 0.6))) * 2.2, "life": 0.6, "grav": 9.0, "kind": "chip"})
	Sfx.play_at("hit_wall", pos, -6.0, randf_range(0.85, 1.2), 30.0)


func blood(pos: Vector3, dir: Vector3, big: bool) -> void:
	for i in (7 if big else 4):
		var b := _bit(_m_blood, randf_range(0.025, 0.06) * (1.4 if big else 1.0))
		b.position = pos
		var v := (dir * randf_range(0.5, 2.6) + Vector3(randf_range(-1, 1), randf_range(0.2, 2.2), randf_range(-1, 1))) * (1.4 if big else 1.0)
		_bits.append({"n": b, "v": v, "life": 0.7, "grav": 9.5, "kind": "blood"})
	Sfx.play_at("hit_head" if big else "hit_flesh", pos, -3.0, randf_range(0.9, 1.15), 30.0)
	# splat on the ground under / behind the target
	var g := pos + G.flat(dir).normalized() * randf_range(0.3, 1.1)
	_decal(Vector3(g.x, 0.055 + randf() * 0.01, g.z), Vector3.UP, "blood%d" % (randi() % 3), randf_range(0.5, 1.1) * (1.4 if big else 1.0))
	# and on a wall close behind
	var q := PhysicsRayQueryParameters3D.create(pos, pos + dir * 2.6, G.L_WORLD)
	var hit := get_world_3d().direct_space_state.intersect_ray(q)
	if not hit.is_empty() and absf((hit["normal"] as Vector3).y) < 0.5:
		_decal(hit["position"], hit["normal"], "blood%d" % (randi() % 3), randf_range(0.5, 1.0))


func casing(pos: Vector3, vel: Vector3) -> void:
	var b := _bit(_m_brass, 0.014)
	b.scale = Vector3(0.012, 0.012, 0.03)
	b.position = pos
	_bits.append({"n": b, "v": vel, "life": 1.6, "grav": 9.8, "kind": "brass"})


func debris(pos: Vector3, count: int) -> void:
	for i in count:
		var b := _bit(_m_wood, 1.0)
		b.scale = Vector3(randf_range(0.04, 0.12), randf_range(0.02, 0.04), randf_range(0.1, 0.3))
		b.position = pos + Vector3(randf_range(-0.4, 0.4), randf_range(-0.8, 0.8), randf_range(-0.4, 0.4))
		_bits.append({"n": b, "v": Vector3(randf_range(-2, 2), randf_range(0.5, 3), randf_range(-2, 2)), "life": 6.0, "grav": 9.8, "kind": "chip"})

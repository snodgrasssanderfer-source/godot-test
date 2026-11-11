class_name Fx
extends Node3D
## World-space effects for weapons: muzzle flash, tracers, bullet holes, impact
## puffs and spent casings. Everything is pooled or capped so a long firefight
## cannot pile up nodes. Lives under the WeaponRig but ignores its transform.

const MAX_HOLES := 80
const MAX_SHELLS := 24
const TRACERS := 12
const PUFFS := 10
const FLASH_TIME := 0.05
const TRACER_TIME := 0.06
const SHELL_LIFE := 5.0

var _soft_dot: GradientTexture2D
var _holes: Array[Decal] = []
var _next_hole := 0
var _tracers: Array[MeshInstance3D] = []
var _tracer_left: Array[float] = []
var _next_tracer := 0
var _dust: Array[CPUParticles3D] = []
var _blood: Array[CPUParticles3D] = []
var _next_dust := 0
var _next_blood := 0
var _shells: Array[RigidBody3D] = []
var _shell_meshes := {}
var _flash_light: OmniLight3D
var _flash_quad: MeshInstance3D
var _flash_left := 0.0
var _flash_anchor: Node3D


func _init() -> void:
	top_level = true


func _ready() -> void:
	global_transform = Transform3D.IDENTITY
	_soft_dot = _radial([0.0, 0.35, 1.0], [Color.WHITE, Color(1, 1, 1, 0.75), Color(1, 1, 1, 0)])

	_flash_light = OmniLight3D.new()
	_flash_light.omni_range = 7.0
	_flash_light.light_color = Color(1.0, 0.78, 0.45)
	_flash_light.light_volumetric_fog_energy = 0.6
	_flash_light.visible = false
	add_child(_flash_light)
	var flash_mat := StandardMaterial3D.new()
	flash_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	flash_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	flash_mat.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	flash_mat.billboard_mode = BaseMaterial3D.BILLBOARD_ENABLED
	flash_mat.billboard_keep_scale = true
	flash_mat.albedo_texture = _radial([0.0, 0.12, 0.45, 1.0],
			[Color.WHITE, Color(1, 1, 1, 0.7), Color(1, 1, 1, 0.12), Color(1, 1, 1, 0)])
	flash_mat.albedo_color = Color(1.0, 0.74, 0.40) * 1.8
	flash_mat.no_depth_test = true
	var flash_mesh := QuadMesh.new()
	flash_mesh.material = flash_mat
	_flash_quad = MeshInstance3D.new()
	_flash_quad.mesh = flash_mesh
	_flash_quad.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_flash_quad.visible = false
	add_child(_flash_quad)

	var tracer_mat := StandardMaterial3D.new()
	tracer_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	tracer_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	tracer_mat.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	tracer_mat.albedo_color = Color(1.0, 0.82, 0.5, 0.55)
	var tracer_mesh := BoxMesh.new()
	tracer_mesh.size = Vector3(0.012, 0.012, 1.0)
	tracer_mesh.material = tracer_mat
	for i in TRACERS:
		var t := MeshInstance3D.new()
		t.mesh = tracer_mesh
		t.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		t.visible = false
		add_child(t)
		_tracers.append(t)
		_tracer_left.append(0.0)

	for i in PUFFS:
		_dust.append(_make_puff(Color(0.55, 0.53, 0.50, 0.55), 10, 0.45, Vector3(0, -5.0, 0), 1.2, 3.2, 0.07))
		_blood.append(_make_puff(Color(0.22, 0.015, 0.012, 0.9), 14, 0.5, Vector3(0, -9.0, 0), 1.0, 3.6, 0.05))

	var brass := StandardMaterial3D.new()
	brass.albedo_color = Color(0.72, 0.52, 0.20)
	brass.metallic = 1.0
	brass.roughness = 0.35
	var hull := StandardMaterial3D.new()
	hull.albedo_color = Color(0.45, 0.04, 0.03)
	hull.roughness = 0.6
	_shell_meshes[WeaponData.Kind.PISTOL] = _cylinder(0.0048, 0.019, brass)
	_shell_meshes[WeaponData.Kind.SHOTGUN] = _cylinder(0.0100, 0.058, hull)


func _radial(offsets: Array, colors: Array) -> GradientTexture2D:
	var g := Gradient.new()
	g.offsets = PackedFloat32Array(offsets)
	g.colors = PackedColorArray(colors)
	var tex := GradientTexture2D.new()
	tex.gradient = g
	tex.fill = GradientTexture2D.FILL_RADIAL
	tex.fill_from = Vector2(0.5, 0.5)
	tex.fill_to = Vector2(1.0, 0.5)
	tex.width = 64
	tex.height = 64
	return tex


func _cylinder(radius: float, height: float, mat: Material) -> CylinderMesh:
	var m := CylinderMesh.new()
	m.top_radius = radius
	m.bottom_radius = radius
	m.height = height
	m.radial_segments = 8
	m.rings = 1
	m.material = mat
	return m


func _make_puff(color: Color, amount: int, life: float, grav: Vector3, v_min: float, v_max: float,
		size: float) -> CPUParticles3D:
	var mat := StandardMaterial3D.new()
	mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	mat.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	mat.billboard_keep_scale = true
	mat.vertex_color_use_as_albedo = true
	mat.albedo_texture = _soft_dot
	mat.albedo_color = color
	var quad := QuadMesh.new()
	quad.size = Vector2(size, size)
	quad.material = mat
	var fade := Gradient.new()
	fade.offsets = PackedFloat32Array([0.0, 0.6, 1.0])
	fade.colors = PackedColorArray([Color.WHITE, Color(1, 1, 1, 0.7), Color(1, 1, 1, 0)])
	var p := CPUParticles3D.new()
	p.mesh = quad
	p.amount = amount
	p.lifetime = life
	p.one_shot = true
	p.explosiveness = 1.0
	p.emitting = false
	p.spread = 38.0
	p.gravity = grav
	p.initial_velocity_min = v_min
	p.initial_velocity_max = v_max
	p.scale_amount_min = 0.6
	p.scale_amount_max = 1.6
	p.color_ramp = fade
	p.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(p)
	return p


func _process(delta: float) -> void:
	if _flash_left > 0.0:
		_flash_left -= delta
		if _flash_left <= 0.0 or not is_instance_valid(_flash_anchor):
			_flash_light.visible = false
			_flash_quad.visible = false
		else:
			_flash_light.global_position = _flash_anchor.global_position
			_flash_quad.global_position = _flash_anchor.global_position
	for i in TRACERS:
		if _tracer_left[i] > 0.0:
			_tracer_left[i] -= delta
			_tracers[i].transparency = 1.0 - clampf(_tracer_left[i] / TRACER_TIME, 0.0, 1.0)
			if _tracer_left[i] <= 0.0:
				_tracers[i].visible = false


## A burst of light at the muzzle marker. size: about 1 for a pistol.
func muzzle_flash(anchor: Node3D, size := 1.0) -> void:
	if anchor == null:
		return
	_flash_anchor = anchor
	_flash_left = FLASH_TIME
	_flash_light.light_energy = 2.2 * size
	_flash_light.global_position = anchor.global_position
	_flash_light.visible = true
	var s := 0.11 * size * randf_range(0.8, 1.2)
	_flash_quad.scale = Vector3(s, s, s)
	_flash_quad.global_position = anchor.global_position
	_flash_quad.visible = true


func tracer(from: Vector3, to: Vector3) -> void:
	var length := from.distance_to(to)
	if length < 3.0:
		return
	var dir := (to - from) / length
	from += dir * 1.2
	length -= 1.2
	var t := _tracers[_next_tracer]
	_tracer_left[_next_tracer] = TRACER_TIME
	_next_tracer = (_next_tracer + 1) % TRACERS
	var up := Vector3.UP if absf(dir.y) < 0.99 else Vector3.RIGHT
	t.global_transform = Transform3D(Basis.looking_at(dir, up).scaled_local(Vector3(1.0, 1.0, length)),
			from + dir * (length * 0.5))
	t.transparency = 0.0
	t.visible = true


## Something was struck at point. Flesh bleeds; the static world gets a puff of
## dust and (for bullets) a hole.
func impact(point: Vector3, normal: Vector3, collider: Object, flesh: bool, hole := true,
		sound := true) -> void:
	if flesh:
		_puff(_blood[_next_blood], point, normal)
		_next_blood = (_next_blood + 1) % PUFFS
		if sound:
			Sfx.play_at(&"hit_flesh", point, -4.0)
		return
	_puff(_dust[_next_dust], point, normal)
	_next_dust = (_next_dust + 1) % PUFFS
	if sound:
		Sfx.play_at(&"impact", point, -6.0)
	if hole and collider is StaticBody3D:
		_bullet_hole(point, normal)


func _puff(p: CPUParticles3D, point: Vector3, normal: Vector3) -> void:
	p.global_position = point + normal * 0.02
	p.direction = normal
	p.restart()
	p.emitting = true


func _bullet_hole(point: Vector3, normal: Vector3) -> void:
	var d: Decal
	if _holes.size() < MAX_HOLES:
		d = Decal.new()
		d.texture_albedo = _hole_texture()
		d.size = Vector3(0.15, 0.10, 0.15)       # the dark core is about a third of this
		d.normal_fade = 0.3
		d.distance_fade_enabled = true
		d.distance_fade_begin = 35.0
		d.distance_fade_length = 10.0
		add_child(d)
		_holes.append(d)
	else:
		d = _holes[_next_hole]
		_next_hole = (_next_hole + 1) % MAX_HOLES
	# A decal projects along its own -Y, so +Y is the surface normal.
	var x := normal.cross(Vector3.UP if absf(normal.y) < 0.99 else Vector3.RIGHT).normalized()
	var basis := Basis(x, normal, x.cross(normal)).rotated(normal, randf() * TAU)
	d.global_transform = Transform3D(basis, point)


var _hole_tex: GradientTexture2D

func _hole_texture() -> GradientTexture2D:
	if _hole_tex == null:
		_hole_tex = _radial([0.0, 0.32, 0.6, 1.0], [Color(0.01, 0.01, 0.01, 1.0),
				Color(0.02, 0.02, 0.02, 0.95), Color(0.06, 0.055, 0.05, 0.4), Color(0.1, 0.1, 0.1, 0.0)])
	return _hole_tex


## Throws a spent casing out of the ejection port. It rings once when it lands.
func shell(port: Transform3D, velocity: Vector3, kind: WeaponData.Kind) -> void:
	if not _shell_meshes.has(kind):
		return
	while _shells.size() >= MAX_SHELLS:
		var old: RigidBody3D = _shells.pop_front()
		if is_instance_valid(old):
			old.queue_free()
	var mesh: CylinderMesh = _shell_meshes[kind]
	var body := RigidBody3D.new()
	body.collision_layer = 0
	body.collision_mask = 1
	body.mass = 0.02
	body.contact_monitor = true
	body.max_contacts_reported = 1
	var shape := CylinderShape3D.new()
	shape.radius = mesh.top_radius
	shape.height = mesh.height
	var cs := CollisionShape3D.new()
	cs.shape = shape
	body.add_child(cs)
	var mi := MeshInstance3D.new()
	mi.mesh = mesh
	mi.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	body.add_child(mi)
	add_child(body)
	# Casings lie along the barrel: the cylinder's long axis (Y) onto the port's Z.
	body.global_transform = Transform3D(port.basis.orthonormalized() * Basis(Vector3.RIGHT, PI * 0.5), port.origin)
	body.linear_velocity = velocity
	body.angular_velocity = Vector3(randf_range(-20, 20), randf_range(-8, 8), randf_range(-20, 20))
	body.body_entered.connect(_on_shell_landed.bind(body), CONNECT_ONE_SHOT)
	_shells.append(body)
	get_tree().create_timer(SHELL_LIFE).timeout.connect(_free_shell.bind(body))


func _on_shell_landed(_other: Node, body: RigidBody3D) -> void:
	if is_instance_valid(body):
		Sfx.play_at(&"shell_tink", body.global_position, -10.0, 0.15)


func _free_shell(body: RigidBody3D) -> void:
	if is_instance_valid(body):
		_shells.erase(body)
		body.queue_free()

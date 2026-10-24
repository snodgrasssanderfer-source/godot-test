extends Node3D
## Builds the town (house, farm, path, trees, flowers...) and drives the
## day/night lighting. Layout: the house faces +Z, the farm is to its
## south-west, and a path loops east past the lamp posts.

const HOUSE_POS := Vector3(0, 0, -8)
const FARM_CENTER := Vector3(-4.5, 0, -1.0)
const BIN_POS := Vector3(-1.5, 0, -4.4)
const BED_POS := Vector3(-2.0, 0, -9.25)
const WAKE_POS := Vector3(-0.3, 0, -8.6)
const WAKE_YAW := PI                  # facing the door (+Z)
const LAMP_POSITIONS := [Vector3(1.2, 0, -4.6), Vector3(7.4, 0, 5.0)]
const PATH_LOOP := [Vector3(2.2, 0, -3.0), Vector3(2.2, 0, 3.2), Vector3(7.0, 0, 6.2),
	Vector3(13.0, 0, 6.6), Vector3(12.0, 0, 0.5), Vector3(6.0, 0, -2.0), Vector3(2.2, 0, -3.0)]
const PATH_SPUR := [Vector3(0, 0, -5.3), Vector3(0, 0, -3.0), Vector3(2.2, 0, -3.0)]
const TREES := [
	[-10, -6, 0, 1.1], [-12, 2, 1, 1.0], [-9.5, 8, 0, 1.2], [-4, 11, 1, 0.9], [3, 12.5, 0, 1.0],
	[9.5, 11, 1, 1.2], [17, 8, 0, 1.1], [18, -1, 1, 1.0], [14, -8, 0, 1.3], [7, -11, 1, 1.1],
	[-1, -14, 0, 1.2], [-8, -13, 1, 1.0], [-15, -9, 0, 1.1], [-17, 4, 1, 1.3], [-14, 13, 0, 1.0],
	[6, 16, 1, 1.1], [20, 12, 0, 1.2], [22, -10, 1, 1.0], [-20, -16, 0, 1.1], [0, -19, 1, 1.2],
	[12, -17, 0, 1.0], [-22, -3, 1, 1.1], [-6, 18, 0, 1.2], [15, 18, 1, 0.9], [-19, 17, 1, 1.0],
]
const BOUNDS := 26.0

# Daylight keyframes: hour, sky, sun color, sun energy, ambient color, ambient
# energy, sun elevation (deg), sun azimuth (deg).
const DAYLIGHT := [
	[5.0, Color("39457a"), Color("8fa3e6"), 0.25, Color("6c78b8"), 0.5, 35.0, -60.0],
	[6.0, Color("f7cfb5"), Color("ffc89a"), 0.5, Color("e9c4b8"), 0.45, 8.0, -80.0],
	[8.0, Color("bfe3f5"), Color("fff0c8"), 0.85, Color("cfe2ec"), 0.38, 25.0, -60.0],
	[12.5, Color("9fd4f5"), Color("fff6e0"), 0.9, Color("d8ecf5"), 0.4, 60.0, 0.0],
	[16.5, Color("f9dca6"), Color("ffd28a"), 0.85, Color("f1dcc0"), 0.38, 30.0, 55.0],
	[18.5, Color("f6a7a0"), Color("ff9e7a"), 0.65, Color("e8b0b4"), 0.42, 10.0, 75.0],
	[19.6, Color("8f86c2"), Color("c49acb"), 0.4, Color("9c95cc"), 0.45, 3.0, 80.0],
	[21.0, Color("3b4a86"), Color("9bb0f0"), 0.3, Color("6f7fc4"), 0.5, 35.0, -60.0],
	[24.0, Color("2c3874"), Color("8fa3e6"), 0.25, Color("6574b6"), 0.5, 35.0, -60.0],
]

var plots: Array[FarmPlot] = []
var villager: Node3D
var _sun: DirectionalLight3D
var _env: Environment
var _night_lights: Array[OmniLight3D] = []
var _glow_materials: Array[StandardMaterial3D] = []
var _house_light: OmniLight3D
var _clouds: Array[MeshInstance3D] = []
var _cloud_material: StandardMaterial3D
var _star_material: StandardMaterial3D
var _rng := RandomNumberGenerator.new()
var _material_cache := {}


func _ready() -> void:
	_rng.seed = 1234
	_build_environment()
	_build_ground()
	_build_path(PATH_LOOP)
	_build_path(PATH_SPUR)
	_build_house()
	_build_farm()
	_build_props()
	_build_trees()
	_build_flowers()
	_build_sky_decor()
	villager = preload("res://scripts/villager.gd").new()
	add_child(villager)
	update_daylight(Game.hour())


func _process(delta: float) -> void:
	update_daylight(Game.hour())
	for c in _clouds:
		c.position.x += delta * 0.6
		if c.position.x > 70.0:
			c.position.x = -70.0


# --- Day / night -----------------------------------------------------------------

## 0 during the day, 1 at night (used for lamps, windows and stars).
static func night_factor(h: float) -> float:
	if h < 12.0:
		return clampf((6.5 - h) / 1.5, 0.0, 1.0)
	return clampf((h - 18.3) / 1.7, 0.0, 1.0)


func update_daylight(h: float) -> void:
	var a: Array = DAYLIGHT[0]
	var b: Array = DAYLIGHT[DAYLIGHT.size() - 1]
	for i in DAYLIGHT.size() - 1:
		if h >= DAYLIGHT[i][0] and h <= DAYLIGHT[i + 1][0]:
			a = DAYLIGHT[i]
			b = DAYLIGHT[i + 1]
			break
	var t := 0.0 if b[0] == a[0] else clampf((h - a[0]) / (b[0] - a[0]), 0.0, 1.0)
	t = smoothstep(0.0, 1.0, t)
	var sky: Color = a[1].lerp(b[1], t)
	_env.background_color = sky
	_env.fog_light_color = sky.lerp(Color.WHITE, 0.15)
	_env.ambient_light_color = a[4].lerp(b[4], t)
	_env.ambient_light_energy = lerpf(a[5], b[5], t)
	_sun.light_color = a[2].lerp(b[2], t)
	_sun.light_energy = lerpf(a[3], b[3], t)
	_sun.rotation = Vector3(deg_to_rad(-lerpf(a[6], b[6], t)), deg_to_rad(lerpf(a[7], b[7], t)), 0)

	var night := night_factor(h)
	for l in _night_lights:
		l.light_energy = night * 1.6
		l.visible = night > 0.01
	_house_light.light_energy = 0.5 + night * 0.9
	for m in _glow_materials:
		m.emission_energy_multiplier = night * 0.7
	_cloud_material.albedo_color = Color.WHITE.lerp(sky, 0.35) * Color(1, 1, 1, 0.9)
	_star_material.albedo_color.a = night


func _build_environment() -> void:
	_env = Environment.new()
	_env.background_mode = Environment.BG_COLOR
	_env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	_env.fog_enabled = true
	_env.fog_density = 0.018
	_env.fog_sky_affect = 0.0
	var we := WorldEnvironment.new()
	we.environment = _env
	add_child(we)

	_sun = DirectionalLight3D.new()
	_sun.shadow_enabled = true
	_sun.directional_shadow_mode = DirectionalLight3D.SHADOW_ORTHOGONAL
	_sun.directional_shadow_max_distance = 30.0
	_sun.shadow_blur = 0.0
	add_child(_sun)


func _build_ground() -> void:
	var ground := MeshInstance3D.new()
	var plane := PlaneMesh.new()
	plane.size = Vector2(90, 90)
	ground.mesh = plane
	ground.material_override = PixelTex.material("grass", Vector3(90, 90, 1))
	add_child(ground)
	_add_static_box(Vector3(90, 1, 90), Vector3(0, -0.5, 0))
	for side in [Vector3(1, 0, 0), Vector3(-1, 0, 0), Vector3(0, 0, 1), Vector3(0, 0, -1)]:
		var size := Vector3(1, 6, BOUNDS * 2) if side.x != 0 else Vector3(BOUNDS * 2, 6, 1)
		_add_static_box(size, side * BOUNDS + Vector3(0, 3, 0))


func _build_path(points: Array) -> void:
	var mat := PixelTex.material("path", Vector3(1, 1, 1), true)
	for i in points.size() - 1:
		var a: Vector3 = points[i]
		var b: Vector3 = points[i + 1]
		var seg := MeshInstance3D.new()
		var box := BoxMesh.new()
		box.size = Vector3(1.5, 0.02, a.distance_to(b) + 1.5)
		seg.mesh = box
		seg.material_override = mat
		seg.position = (a + b) / 2.0 + Vector3(0, 0.005 + i * 0.0005, 0)
		seg.rotation.y = atan2(b.x - a.x, b.z - a.z)
		add_child(seg)


func _build_house() -> void:
	var house := _place_model(preload("res://assets/models/house.glb"), HOUSE_POS)
	_house_light = OmniLight3D.new()
	_house_light.light_color = Color("ffd9a0")
	_house_light.omni_range = 6.5
	_house_light.position = HOUSE_POS + Vector3(0, 2.2, 0)
	add_child(_house_light)
	var bed := preload("res://scripts/bed.gd").new()
	bed.position = BED_POS
	add_child(bed)
	_apply_pixel_materials(bed)
	house.name = "House"


func _build_farm() -> void:
	for i in Game.PLOT_COUNT:
		var plot := FarmPlot.new(i)
		var col := i % 4
		var row := floori(i / 4.0)
		plot.position = FARM_CENTER + Vector3(col - 1.5, 0, row - 1.5)
		add_child(plot)
		plots.append(plot)
	var fence_scene := preload("res://assets/models/fence.glb")
	var x0 := FARM_CENTER.x - 2.5
	var z0 := FARM_CENTER.z - 2.5
	for k in 5:
		_place_model(fence_scene, Vector3(x0 + 0.5 + k, 0, z0))                 # north
		_place_model(fence_scene, Vector3(x0 + 0.5 + k, 0, z0 + 5))             # south
		_place_model(fence_scene, Vector3(x0, 0, z0 + 0.5 + k), PI / 2)         # west
		if k != 2 and k != 3:                                                   # east, with a gate gap
			_place_model(fence_scene, Vector3(x0 + 5, 0, z0 + 0.5 + k), PI / 2)


func _build_props() -> void:
	var bin := preload("res://scripts/shipping_bin.gd").new()
	bin.position = BIN_POS
	add_child(bin)
	_apply_pixel_materials(bin)
	for p in LAMP_POSITIONS:
		_place_model(preload("res://assets/models/lamp_post.glb"), p)
		var light := OmniLight3D.new()
		light.light_color = Color("ffcf8a")
		light.omni_range = 7.0
		light.position = p + Vector3(0, 2.5, 0)
		add_child(light)
		_night_lights.append(light)


func _build_trees() -> void:
	var scenes := [preload("res://assets/models/tree_round.glb"), preload("res://assets/models/tree_pine.glb")]
	for t in TREES:
		var tree := _place_model(scenes[t[2]], Vector3(t[0], 0, t[1]), _rng.randf() * TAU)
		tree.scale = Vector3.ONE * t[3]


func _build_flowers() -> void:
	var colors := [Color("ff9fb2"), Color("ffe27a"), Color("ffffff"), Color("c9a7ff"), Color("ffb36b")]
	var stems: Array[Transform3D] = []
	var heads: Array[Transform3D] = []
	var head_colors: Array[Color] = []
	var tufts: Array[Transform3D] = []
	var tries := 0
	while (heads.size() < 220 or tufts.size() < 500) and tries < 6000:
		tries += 1
		var p := Vector3(_rng.randf_range(-BOUNDS + 1, BOUNDS - 1), 0, _rng.randf_range(-BOUNDS + 1, BOUNDS - 1))
		if not _is_open_ground(p):
			continue
		var rot := Basis(Vector3.UP, _rng.randf() * TAU)
		if heads.size() < 220 and _rng.randf() < 0.35:
			var color: Color = colors[_rng.randi() % colors.size()]
			for k in _rng.randi_range(1, 3):        # little clusters
				var q := p + Vector3(_rng.randf_range(-0.3, 0.3), 0, _rng.randf_range(-0.3, 0.3))
				stems.append(Transform3D(rot, q + Vector3(0, 0.09, 0)))
				heads.append(Transform3D(rot, q + Vector3(0, 0.2, 0)))
				head_colors.append(color)
		elif tufts.size() < 500:
			for k in 3:
				var q := p + Vector3(_rng.randf_range(-0.08, 0.08), 0, _rng.randf_range(-0.08, 0.08))
				var tilt := Basis(Vector3(_rng.randf_range(-1, 1), 0, _rng.randf_range(-1, 1)).normalized(), 0.25)
				tufts.append(Transform3D(tilt * rot, q + Vector3(0, 0.07, 0)))
	_add_multimesh(Vector3(0.03, 0.18, 0.03), stems, [], Color("6fb45a"))
	_add_multimesh(Vector3(0.1, 0.06, 0.1), heads, head_colors, Color.WHITE)
	_add_multimesh(Vector3(0.04, 0.16, 0.04), tufts, [], Color("7cc463"))


func _is_open_ground(p: Vector3) -> bool:
	if p.x > -4.5 and p.x < 4.5 and p.z > -12.0 and p.z < -4.0:      # house + bin
		return false
	if p.x > -7.6 and p.x < -1.4 and p.z > -4.2 and p.z < 2.2:       # farm
		return false
	for path in [PATH_LOOP, PATH_SPUR]:
		for i in path.size() - 1:
			var closest := Geometry3D.get_closest_point_to_segment(p, path[i], path[i + 1])
			if closest.distance_to(p) < 1.3:
				return false
	for t in TREES:
		if Vector2(t[0], t[1]).distance_to(Vector2(p.x, p.z)) < 1.0:
			return false
	return true


func _build_sky_decor() -> void:
	_cloud_material = StandardMaterial3D.new()
	_cloud_material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	_cloud_material.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_cloud_material.disable_fog = true
	for i in 9:
		var cloud := MeshInstance3D.new()
		var box := BoxMesh.new()
		box.size = Vector3(_rng.randf_range(6, 12), _rng.randf_range(1.0, 2.0), _rng.randf_range(4, 7))
		cloud.mesh = box
		cloud.material_override = _cloud_material
		cloud.position = Vector3(_rng.randf_range(-70, 70), _rng.randf_range(24, 32), _rng.randf_range(-60, 60))
		cloud.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
		add_child(cloud)
		_clouds.append(cloud)

	_star_material = StandardMaterial3D.new()
	_star_material.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	_star_material.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	_star_material.disable_fog = true
	_star_material.albedo_color = Color(1, 0.97, 0.85, 0)
	var stars: Array[Transform3D] = []
	for i in 160:
		var dir := Vector3(_rng.randf_range(-1, 1), _rng.randf_range(0.25, 1), _rng.randf_range(-1, 1)).normalized()
		stars.append(Transform3D(Basis(), dir * 95.0))
	var mm := _add_multimesh(Vector3.ONE * 0.45, stars, [], Color.WHITE)
	mm.material_override = _star_material
	mm.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF


# --- Helpers -------------------------------------------------------------------

## Instances a Blender model and swaps its flat materials for pixel textures.
func _place_model(scene: PackedScene, pos: Vector3, yaw := 0.0) -> Node3D:
	var node: Node3D = scene.instantiate()
	node.position = pos
	node.rotation.y = yaw
	add_child(node)
	_apply_pixel_materials(node)
	return node


func _apply_pixel_materials(node: Node) -> void:
	if node is MeshInstance3D:
		var mi := node as MeshInstance3D
		for i in mi.mesh.get_surface_count():
			var mat := mi.mesh.surface_get_material(i)
			if mat == null:
				continue
			var replacement: Material = _material_for(mat)
			if replacement:
				mi.set_surface_override_material(i, replacement)
	for c in node.get_children():
		_apply_pixel_materials(c)


func _material_for(mat: Material) -> Material:
	match mat.resource_name:
		"Wood": return _cached_material("wood", Vector3(0.5, 1.0, 0.5))
		"WoodDark": return _cached_material("wood_dark", Vector3(1.0, 2.0, 1.0))
		"FloorWood": return _cached_material("floor", Vector3(0.5, 1.0, 1.0))
		"Roof": return _cached_material("roof", Vector3.ONE * 1.5)
		"Glass", "LampGlass":
			var glow := (mat as StandardMaterial3D).duplicate() as StandardMaterial3D
			glow.emission_enabled = true
			glow.emission = Color("ffb760")
			_glow_materials.append(glow)
			return glow
	return null



func _cached_material(tex: String, uv_scale: Vector3) -> Material:
	if not _material_cache.has(tex):
		_material_cache[tex] = PixelTex.material(tex, uv_scale, true)
	return _material_cache[tex]


func _add_static_box(size: Vector3, pos: Vector3) -> void:
	var body := StaticBody3D.new()
	body.collision_layer = 1
	var shape := CollisionShape3D.new()
	var box := BoxShape3D.new()
	box.size = size
	shape.shape = box
	body.add_child(shape)
	body.position = pos
	add_child(body)


func _add_multimesh(size: Vector3, transforms: Array[Transform3D], colors: Array[Color], color: Color) -> MultiMeshInstance3D:
	var mat := StandardMaterial3D.new()
	mat.albedo_color = color
	mat.vertex_color_use_as_albedo = not colors.is_empty()
	mat.roughness = 1.0
	var box := BoxMesh.new()
	box.size = size
	box.material = mat
	var mm := MultiMesh.new()
	mm.transform_format = MultiMesh.TRANSFORM_3D
	mm.use_colors = not colors.is_empty()
	mm.mesh = box
	mm.instance_count = transforms.size()
	for i in transforms.size():
		mm.set_instance_transform(i, transforms[i])
		if mm.use_colors:
			mm.set_instance_color(i, colors[i])
	var inst := MultiMeshInstance3D.new()
	inst.multimesh = mm
	inst.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(inst)
	return inst

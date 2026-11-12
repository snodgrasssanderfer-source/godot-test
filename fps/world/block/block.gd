extends Node3D
## The block: the hand-built map (docs/DIRECTION.md). So far it is the gas
## station on the corner with the street in front and the alley behind; each
## further place is a model of its own, listed in MODELS, built by a script in
## art/block/. The models bring their own collision, lamps and markers.
##
##   WASD move    Shift sprint    Ctrl / C crouch    Space jump, climb, vault
##   Q / E lean   F flashlight    LMB fire    RMB aim    R reload    1 2 3 weapons
##
##   F1 help    F2 numbers    F3 shooting range    F5 back to the car
##   P  power on / off    V  volumetric fog    - =  fog thinner / thicker
##   F11 fullscreen    Esc free the mouse    F10 quit
##
## Command line (after "--"):
##   --res=1280x720   render size (default 1920x1080, scaled to the window)
##   --selftest       loads the block, checks every marked place can be walked to, then quits

const RANGE_SCENE := "res://test/range_street.tscn"
const MODELS: Array[String] = ["res://assets/block/gas_station.glb"]
const HELP := """WASD move   Shift sprint   Ctrl/C crouch   Space jump / climb   Q E lean   F flashlight   LMB fire   RMB aim   R reload
F1 help   F2 numbers   F3 shooting range   F5 back to the car   P power   V fog on/off   - = fog thinner/thicker   F11 fullscreen   F10 quit"""
## What a "light_<kind>_<n>" empty in a model becomes:
## [spot or omni, energy, colour, range, casts shadows].
const LIGHTS := {
	&"canopy": ["spot", 9.0, Color(0.82, 0.92, 1.0), 10.0, true],
	&"street": ["spot", 16.0, Color(1.0, 0.50, 0.14), 22.0, true],
	&"tube": ["omni", 1.5, Color(0.85, 0.93, 1.0), 7.5, false],
	&"bulb": ["omni", 0.9, Color(1.0, 0.82, 0.6), 6.0, true],
	&"door": ["omni", 0.7, Color(1.0, 0.8, 0.55), 5.0, false],
	&"wallpack": ["omni", 1.1, Color(1.0, 0.68, 0.38), 8.0, true],
	&"yard": ["omni", 1.3, Color(1.0, 0.68, 0.38), 10.0, false],
	&"cooler": ["omni", 0.45, Color(0.75, 0.9, 1.0), 4.5, false],
	&"vending": ["omni", 0.6, Color(0.95, 0.75, 0.6), 4.0, false],
	&"neon": ["omni", 0.5, Color(1.0, 0.25, 0.3), 4.0, false],
	&"sign_green": ["omni", 0.7, Color(0.75, 1.0, 0.8), 7.0, false],
	&"sign_warm": ["omni", 0.6, Color(1.0, 0.85, 0.6), 6.0, false],
	&"sign_blue": ["omni", 0.6, Color(0.7, 0.8, 1.0), 6.0, false],
}
## The night here: you stop seeing things at 25 to 40 m (DIRECTION.md, section 5).
const FOG_DENSITY := 0.05
const FOG_VOLUMETRIC := 0.035
## What the navigation mesh covers (the part of the block that exists).
const NAV_BOUNDS := AABB(Vector3(0.0, -1.0, 0.0), Vector3(58.0, 6.0, 63.0))

var atmosphere: Atmosphere
var player: Player
var hud: Hud
var models: Array[TownModel] = []
var markers := {}                        # StringName -> Vector3
var nav_region: NavigationRegion3D

var _overlay: Label
var _help: Label
var _automated := false
var _fog := 1.0
var _frame_ms := 0.0


func _ready() -> void:
	var render_size := Vector2i(1920, 1080)
	for arg in OS.get_cmdline_user_args():
		if arg.begins_with("--res="):
			var parts := arg.trim_prefix("--res=").split("x")
			if parts.size() == 2 and int(parts[0]) > 0 and int(parts[1]) > 0:
				render_size = Vector2i(int(parts[0]), int(parts[1]))
		elif arg == "--selftest":
			_automated = true
	var window := get_window()
	window.content_scale_mode = Window.CONTENT_SCALE_MODE_VIEWPORT
	window.content_scale_aspect = Window.CONTENT_SCALE_ASPECT_KEEP
	window.content_scale_size = render_size
	if OS.has_feature("template") and not _automated:
		window.mode = Window.MODE_EXCLUSIVE_FULLSCREEN

	atmosphere = (load("res://atmosphere/atmosphere.tscn") as PackedScene).instantiate() as Atmosphere
	add_child(atmosphere)
	atmosphere.day_length_minutes = 0.0
	atmosphere.power_on = true
	atmosphere.night_powered = atmosphere.night_powered.duplicate()
	atmosphere.night_blackout = atmosphere.night_blackout.duplicate()
	_set_fog(1.0)
	atmosphere.time_of_day = 3.0

	for path in MODELS:
		if not ResourceLoader.exists(path):
			push_error("Block: %s is missing (build it with its script in art/block/)" % path)
			continue
		var scene := (load(path) as PackedScene).instantiate() as Node3D
		var model := TownModel.from_scene(scene, self)
		models.append(model)
		markers.merge(model.markers)
		_make_lights(model)
	Grid.reset()

	player = Player.new()
	player.name = "Player"
	add_child(player)
	player.rig.infinite_reserve = true
	respawn()
	player.body.died.connect(_on_player_died)

	hud = Hud.new()
	add_child(hud)
	hud.setup(player)

	var layer := CanvasLayer.new()
	layer.layer = 110
	add_child(layer)
	_overlay = _label(layer)
	_overlay.position = Vector2(16, 12)
	_overlay.visible = false
	_help = _label(layer)
	_help.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_LEFT, Control.PRESET_MODE_MINSIZE, 16)
	_help.grow_vertical = Control.GROW_DIRECTION_BEGIN
	_help.text = HELP

	atmosphere.set_time(atmosphere.time_of_day)      # the lamps exist now: sync them
	if _automated:
		_help.visible = false
		_selftest.call_deferred()
	else:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED


func _label(layer: CanvasLayer) -> Label:
	var label := Label.new()
	label.add_theme_font_size_override(&"font_size", 18)
	label.add_theme_color_override(&"font_outline_color", Color.BLACK)
	label.add_theme_constant_override(&"outline_size", 6)
	layer.add_child(label)
	return label


func _set_fog(scale: float) -> void:
	_fog = clampf(scale, 0.2, 3.0)
	for preset: AtmospherePreset in [atmosphere.night_powered, atmosphere.night_blackout]:
		preset.fog_density = FOG_DENSITY * _fog
		preset.volumetric_density = FOG_VOLUMETRIC * _fog
	atmosphere.set_time(atmosphere.time_of_day)


func _make_lights(model: TownModel) -> void:
	for m: Dictionary in model.lights:
		var spec: Array = LIGHTS.get(m["kind"], LIGHTS[&"tube"])
		var light: Light3D
		if spec[0] == "spot":
			var spot := SpotLight3D.new()
			spot.rotation_degrees.x = -90.0
			spot.spot_angle = 70.0
			spot.spot_angle_attenuation = 0.55
			spot.spot_range = spec[3]
			spot.light_volumetric_fog_energy = 1.6
			light = spot
		else:
			var omni := OmniLight3D.new()
			omni.omni_range = spec[3]
			omni.omni_attenuation = 1.4
			omni.light_volumetric_fog_energy = 0.6
			light = omni
		light.shadow_enabled = spec[4]
		light.light_color = spec[2]
		light.light_energy = spec[1]
		light.position = m["at"]
		light.add_to_group(&"street_lamp")           # the atmosphere's power switch works on this group
		add_child(light)


func respawn() -> void:
	player.body.reset()
	player.global_position = markers.get(&"spawn", Vector3(35.6, 0.2, 24.3))
	player.velocity = Vector3.ZERO
	player.set_look(PI * 0.5)                         # facing the store


func _on_player_died(_cause: StringName) -> void:
	if _automated:
		return
	await get_tree().create_timer(5.0).timeout
	if player.body.sim.dead:
		respawn()


func _input(event: InputEvent) -> void:
	if _automated:
		return
	if event is InputEventMouseButton and event.pressed and Input.mouse_mode != Input.MOUSE_MODE_CAPTURED:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
		get_viewport().set_input_as_handled()
		return
	if event is InputEventKey and event.pressed and not event.echo:
		match event.physical_keycode:
			KEY_ESCAPE: Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
			KEY_P: atmosphere.power_on = not atmosphere.power_on
			KEY_V: atmosphere.volumetric_fog = not atmosphere.volumetric_fog
			KEY_MINUS: _set_fog(_fog / 1.2)
			KEY_EQUAL: _set_fog(_fog * 1.2)
			KEY_F1: _help.visible = not _help.visible
			KEY_F2: _overlay.visible = not _overlay.visible
			KEY_F3: get_tree().change_scene_to_file(RANGE_SCENE)
			KEY_F5: respawn()
			KEY_F10: get_tree().quit()
			KEY_F11:
				var window := get_window()
				var full := window.mode == Window.MODE_EXCLUSIVE_FULLSCREEN or window.mode == Window.MODE_FULLSCREEN
				window.mode = Window.MODE_WINDOWED if full else Window.MODE_EXCLUSIVE_FULLSCREEN


func _process(delta: float) -> void:
	if _overlay == null or not _overlay.visible:
		return
	var ms := delta * 1000.0
	_frame_ms = lerpf(_frame_ms, ms, 0.05) if _frame_ms > 0.0 else ms
	var p := player.global_position
	_overlay.text = "%d fps   %.1f ms\nx %.1f  z %.1f  height %.2f   %.1f m/s\nfog x%.2f (%.3f)   power %s" % [
		Engine.get_frames_per_second(), _frame_ms, p.x, p.z, p.y, player.horizontal_speed(), _fog, FOG_DENSITY * _fog,
		"on" if atmosphere.power_on else "OFF"]


# --- self test -----------------------------------------------------------------

func _selftest() -> void:
	var passed := 0
	var failed := 0
	var check := func(what: String, ok: bool, detail := "") -> void:
		print("%s  %s%s" % ["PASS" if ok else "FAIL", what, ("   [" + detail + "]") if detail != "" else ""])
	var meshes := 0
	var colliders := 0
	var kept: Array[String] = []
	for model in models:
		meshes += model.mesh_count
		colliders += model.collider_count
		for key: String in model.materials:
			if model.materials[key][1] == "kept" and key != "":
				kept.append(key)
	var results: Array = []
	results.append(["the models load, with their own collision", models.size() == MODELS.size() and meshes > 8 and colliders > 8,
			"%d meshes, %d colliders" % [meshes, colliders]])
	results.append(["the lamps and the markers came with them", get_tree().get_nodes_in_group(&"street_lamp").size() >= 20
			and markers.has(&"spawn") and markers.has(&"staff_room") and markers.has(&"alley"),
			"%d lamps, %d markers" % [get_tree().get_nodes_in_group(&"street_lamp").size(), markers.size()]])
	for i in 180:
		await get_tree().physics_frame
	var p := player.global_position
	results.append(["the player stands on the forecourt by the patrol car", p.y > -0.05 and p.y < 0.3
			and p.distance_to(markers[&"spawn"]) < 1.0, "at %.1f %.2f %.1f" % [p.x, p.y, p.z]])
	nav_region = Nav.bake(self, NAV_BOUNDS, 1, 0.25, 0.125)
	var map := get_world_3d().navigation_map
	for i in 1200:
		await get_tree().physics_frame
		if NavigationServer3D.map_get_iteration_id(map) > 0 and not NavigationServer3D.map_get_path(map, p, p + Vector3(2, 0, 0), true).is_empty():
			break
	results.append(["a navigation mesh bakes from the models' collision", nav_region.navigation_mesh.get_polygon_count() > 100,
			"%d polygons" % nav_region.navigation_mesh.get_polygon_count()])
	var unreachable: Array[String] = []
	for id: StringName in [&"staff_room", &"stock_room", &"alley", &"pipe", &"first_creature", &"corner"]:
		var goal: Vector3 = markers.get(id, Vector3.INF)
		goal.y = minf(goal.y, 0.2)
		var path := NavigationServer3D.map_get_path(map, p, goal, true)
		var end := path[path.size() - 1] if not path.is_empty() else Vector3.INF
		if path.is_empty() or Vector2(end.x - goal.x, end.z - goal.z).length() > 1.0:
			unreachable.append("%s (stops %.1f m short)" % [id, Vector2(end.x - goal.x, end.z - goal.z).length()])
	results.append(["on foot from the car: staff room, stock room, alley, the pipe, the machine, the street corner",
			unreachable.is_empty(), "; ".join(unreachable)])
	var out := NavigationServer3D.map_get_path(map, p, Vector3(50.5, 0.0, -6.0), true)
	results.append(["nothing leads out past the fog at the north end", out.is_empty() or out[out.size() - 1].z > 1.0])
	if not kept.is_empty():
		print("NOTE  materials the game has no set for (they keep Blender's flat colour): ", ", ".join(kept))
	for r: Array in results:
		check.call(r[0], r[1], r[2] if r.size() > 2 else "")
		if r[1]:
			passed += 1
		else:
			failed += 1
	print("BLOCK SELFTEST %d passed, %d failed" % [passed, failed])
	get_tree().quit(1 if failed > 0 else 0)

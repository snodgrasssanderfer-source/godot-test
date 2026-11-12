extends Node3D
## The demo town as a greybox you can walk: the 250 x 250 m map of the design
## document built from TownLayout, at three in the morning with the power on.
## Nothing lives here yet (no creatures, no pickups); it is for judging the
## layout, distances and sight lines. Markers show where things will go.
##
##   WASD move    Shift sprint    Ctrl / C crouch    Space jump, climb, vault
##   Q / E lean   F flashlight    LMB fire    RMB aim    R reload    1 2 3 weapons
##
##   F6 F7 F8 F9  jump to: gas station / Miller's / tavern + laundromat / Clark house
##   L    marker labels on / off      P  power on / off       [ ]  time -/+ half an hour
##   F1   help    F2  numbers     F3  back to the shooting range     F5  back to the start
##   F11  fullscreen     Esc  free the mouse     F10 quit
##
## Command line (after "--"):
##   --res=1280x720   render size (default 1920x1080, scaled to the window)
##   --dump=<file>    write every box and marker as JSON (for tools/draw_town_plan.py), then quit
##   --selftest       scripted walk through all four buildings, then quit
##   --shoot=<dir>    scripted screenshots, then quit

const RANGE_SCENE := "res://test/range_street.tscn"
const HELP := """WASD move   Shift sprint   Ctrl/C crouch   Space jump / climb   Q E lean   F flashlight   LMB fire   RMB aim
F6 gas station   F7 Miller's   F8 tavern + laundromat   F9 Clark house   L labels   P power   [ ] time
F1 help   F2 numbers   F3 shooting range   F5 back to start   F11 fullscreen   Esc mouse   F10 quit"""
## Where the F-keys put you: marker, metres back from it towards the street.
const STOPS := {KEY_F7: &"miller_door", KEY_F8: &"tavern_door", KEY_F9: &"clark_door"}

var builder: TownBuilder
var extras: Dictionary
var atmosphere: Atmosphere
var player: Player
var hud: Hud
var nav_region: NavigationRegion3D
var labels: Array[Label3D] = []

var _overlay: Label
var _help: Label
var _automated := false
var _frame_ms := 0.0


func _ready() -> void:
	var render_size := Vector2i(1920, 1080)
	var auto_mode := ""
	var auto_arg := ""
	for arg in OS.get_cmdline_user_args():
		if arg.begins_with("--res="):
			var parts := arg.trim_prefix("--res=").split("x")
			if parts.size() == 2 and int(parts[0]) > 0 and int(parts[1]) > 0:
				render_size = Vector2i(int(parts[0]), int(parts[1]))
		elif arg == "--selftest":
			auto_mode = "selftest"
		elif arg.begins_with("--shoot="):
			auto_mode = "shoot"
			auto_arg = arg.trim_prefix("--shoot=")
		elif arg.begins_with("--dump="):
			auto_mode = "dump"
			auto_arg = arg.trim_prefix("--dump=")
	_automated = auto_mode != ""

	builder = TownBuilder.new()
	extras = TownLayout.build(builder)
	if auto_mode == "dump":
		var file := FileAccess.open(auto_arg, FileAccess.WRITE)
		file.store_string(JSON.stringify(builder.dump()))
		file.close()
		print("DUMP ", auto_arg)
		get_tree().quit()
		return

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
	atmosphere.time_of_day = 3.0

	var town := Node3D.new()
	town.name = "Town"
	add_child(town)
	builder.commit(town)
	_make_lights()
	_make_labels()
	Grid.reset()

	player = Player.new()
	player.name = "Player"
	add_child(player)
	player.rig.infinite_reserve = true
	respawn()

	hud = Hud.new()
	add_child(hud)
	hud.setup(player)

	var layer := CanvasLayer.new()
	layer.layer = 110
	add_child(layer)
	_overlay = _label(layer)
	_overlay.position = Vector2(16, 12)
	_help = _label(layer)
	_help.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_LEFT, Control.PRESET_MODE_MINSIZE, 16)
	_help.grow_vertical = Control.GROW_DIRECTION_BEGIN
	_help.text = HELP

	atmosphere.set_time(atmosphere.time_of_day)      # the lamps exist now: sync them

	if not _automated:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
	else:
		_help.visible = false
		var auto := TownAuto.new()
		add_child(auto)
		auto.start(self, auto_mode, auto_arg)


func _label(layer: CanvasLayer) -> Label:
	var label := Label.new()
	label.add_theme_font_size_override(&"font_size", 18)
	label.add_theme_color_override(&"font_outline_color", Color.BLACK)
	label.add_theme_constant_override(&"outline_size", 6)
	layer.add_child(label)
	return label


## Street lamps are shadowed spots over the road; everything indoors is a
## plain point light. All of them follow the atmosphere's power switch.
func _make_lights() -> void:
	for l: Dictionary in extras["lights"]:
		var light: Light3D
		if l["kind"] == &"street":
			var spot := SpotLight3D.new()
			spot.rotation_degrees.x = -90.0
			spot.spot_angle = 64.0
			spot.spot_angle_attenuation = 0.6
			spot.spot_range = l["range"]
			spot.light_volumetric_fog_energy = 2.0
			spot.shadow_enabled = true
			light = spot
			var bulb := MeshInstance3D.new()
			var mesh := BoxMesh.new()
			mesh.size = Vector3(0.5, 0.18, 0.04)
			var mat := StandardMaterial3D.new()
			mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
			mat.albedo_color = Color(1.0, 0.55, 0.18) * 6.0
			mesh.material = mat
			bulb.mesh = mesh
			bulb.position = Vector3(0.0, 0.0, 0.07)
			spot.add_child(bulb)
		else:
			var omni := OmniLight3D.new()
			omni.omni_range = l["range"]
			omni.omni_attenuation = 1.4
			omni.light_volumetric_fog_energy = 0.6
			light = omni
		light.light_color = l["color"]
		light.light_energy = l["energy"]
		light.position = l["at"]
		light.add_to_group(&"street_lamp")
		add_child(light)


func _make_labels() -> void:
	for lot in builder.lots:
		for m in lot.markers:
			var label := Label3D.new()
			label.text = m.label
			label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
			label.no_depth_test = true
			label.fixed_size = true
			label.pixel_size = 0.0011
			label.modulate = Color(1.0, 0.9, 0.4)
			label.outline_size = 8
			label.position = lot.xform * m.position + Vector3.UP * 0.25
			label.visible = false
			add_child(label)
			labels.append(label)


func respawn() -> void:
	place(extras["spawn"])


## Stands the player at a transform (its -Z is the way they face).
func place(at: Transform3D) -> void:
	player.body.reset()
	player.global_position = at.origin
	player.velocity = Vector3.ZERO
	player.set_look(at.basis.get_euler().y)


## A spot on the pavement in front of a door marker, facing the door.
func stop_at(marker: StringName, back := 2.5) -> Transform3D:
	for lot in builder.lots:
		for m in lot.markers:
			if m.id == marker:
				var into := lot.xform.basis.z                     # a lot's +Z points away from the street
				var at := lot.xform * m.position - into * back
				at.y = 0.4
				return Transform3D(Basis.looking_at(into, Vector3.UP), at)
	return extras["spawn"]


func _input(event: InputEvent) -> void:
	if _automated:
		return
	if event is InputEventMouseButton and event.pressed and Input.mouse_mode != Input.MOUSE_MODE_CAPTURED:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
		get_viewport().set_input_as_handled()
		return
	if event is InputEventKey and event.pressed and not event.echo:
		var key: Key = event.physical_keycode
		if STOPS.has(key):
			place(stop_at(STOPS[key]))
			return
		match key:
			KEY_ESCAPE: Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
			KEY_BRACKETLEFT: atmosphere.time_of_day = fposmod(atmosphere.time_of_day - 0.5, 24.0)
			KEY_BRACKETRIGHT: atmosphere.time_of_day = fposmod(atmosphere.time_of_day + 0.5, 24.0)
			KEY_P: atmosphere.power_on = not atmosphere.power_on
			KEY_L:
				for label in labels:
					label.visible = not label.visible
			KEY_F1: _help.visible = not _help.visible
			KEY_F2: _overlay.visible = not _overlay.visible
			KEY_F3: get_tree().change_scene_to_file(RANGE_SCENE)
			KEY_F5, KEY_F6: respawn()
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
	var hour := int(atmosphere.time_of_day)
	var minute := int(fposmod(atmosphere.time_of_day, 1.0) * 60.0)
	_overlay.text = "%d fps   %.1f ms\n%02d:%02d   power %s\nx %.1f  z %.1f  height %.2f   %.1f m/s" % [
		Engine.get_frames_per_second(), _frame_ms, hour, minute, "on" if atmosphere.power_on else "OFF",
		p.x, p.z, p.y, player.horizontal_speed()]

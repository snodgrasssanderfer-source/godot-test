extends Node3D
## Test bed for the atmosphere: the concept-art street as a flat-colour greybox.
##
## Fly around:  WASD + mouse, Shift = fast, Q / E = down / up
##   [ ]   time -/+ half an hour        T   run / freeze the clock
##   P     power on / off               F   flashlight
##   F1    hide the overlay             Esc release the mouse
##
## Capture the four reference shots without a window:
##   godot --path fps res://test/tone_street.tscn -- --shoot=<dir>

const VIEW_FROM := Vector3(-0.9, 1.62, -3.0)
const VIEW_AT := Vector3(0.9, 3.3, -50.0)
## name, hour, power, flashlight
const SHOTS := [
	["A_cold_fog", 11.0, true, false], ["B_rust_dusk", 18.0, true, false],
	["C_sodium_night", 23.0, true, false], ["D_blackout", 23.0, false, true],
]

@onready var atmosphere: Atmosphere = $Atmosphere

var _camera: Camera3D
var _flashlight: SpotLight3D
var _overlay: Label
var _yaw := 0.0
var _pitch := 0.0
var _frame_ms := 0.0
var _worst_ms := 0.0
var _worst_age := 0.0


func _ready() -> void:
	_camera = Camera3D.new()
	_camera.fov = 51.5      # same framing as the concept renders (21 mm lens)
	_camera.far = 400.0
	add_child(_camera)
	_camera.look_at_from_position(VIEW_FROM, VIEW_AT)
	_yaw = _camera.rotation.y
	_pitch = _camera.rotation.x

	_flashlight = SpotLight3D.new()
	_flashlight.spot_angle = 18.0
	_flashlight.spot_angle_attenuation = 0.5
	_flashlight.spot_range = 45.0
	_flashlight.light_color = Color(0.92, 0.96, 1.0)
	_flashlight.light_energy = 22.0
	_flashlight.light_volumetric_fog_energy = 0.5
	_flashlight.shadow_enabled = true
	_flashlight.visible = false
	_camera.add_child(_flashlight)
	_flashlight.position = Vector3(0.30, -0.32, -0.25)     # held low and to the right
	_flashlight.look_at(Vector3(-0.45, 1.45, -15.0))

	var layer := CanvasLayer.new()
	layer.layer = 110       # above the film pass, so the numbers stay crisp
	_overlay = Label.new()
	_overlay.position = Vector2(16, 12)
	_overlay.add_theme_font_size_override(&"font_size", 18)
	_overlay.add_theme_color_override(&"font_outline_color", Color.BLACK)
	_overlay.add_theme_constant_override(&"outline_size", 6)
	layer.add_child(_overlay)
	add_child(layer)

	atmosphere.set_time(atmosphere.time_of_day)      # lamps exist now: sync them

	var shoot_dir := ""
	for arg in OS.get_cmdline_user_args():
		if arg.begins_with("--shoot="):
			shoot_dir = arg.trim_prefix("--shoot=")
	if shoot_dir != "":
		_overlay.visible = false
		_shoot(shoot_dir)
	else:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED


func _shoot(dir: String) -> void:
	atmosphere.day_length_minutes = 0.0
	DirAccess.make_dir_recursive_absolute(dir)
	for shot in SHOTS:
		atmosphere.power_on = shot[2]
		atmosphere.time_of_day = shot[1]
		_flashlight.visible = shot[3]
		for i in 60:        # let fog reprojection settle (it keeps a long history)
			await get_tree().process_frame
		await RenderingServer.frame_post_draw
		var path := dir.path_join("engine_%s.png" % shot[0])
		get_viewport().get_texture().get_image().save_png(path)
		print("SHOT ", path)
	get_tree().quit()


func _unhandled_input(event: InputEvent) -> void:
	if event is InputEventMouseMotion and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		_yaw -= event.relative.x * 0.0022
		_pitch = clampf(_pitch - event.relative.y * 0.0022, -1.5, 1.5)
		_camera.rotation = Vector3(_pitch, _yaw, 0.0)
	elif event is InputEventMouseButton and event.pressed:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
	elif event is InputEventKey and event.pressed and not event.echo:
		match event.keycode:
			KEY_ESCAPE: Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
			KEY_BRACKETLEFT: atmosphere.time_of_day = fposmod(atmosphere.time_of_day - 0.5, 24.0)
			KEY_BRACKETRIGHT: atmosphere.time_of_day = fposmod(atmosphere.time_of_day + 0.5, 24.0)
			KEY_T: atmosphere.day_length_minutes = 0.0 if atmosphere.day_length_minutes > 0.0 else 2.0
			KEY_P: atmosphere.power_on = not atmosphere.power_on
			KEY_F: _flashlight.visible = not _flashlight.visible
			KEY_F1: _overlay.visible = not _overlay.visible


func _process(delta: float) -> void:
	var move := Vector3(
		float(Input.is_key_pressed(KEY_D)) - float(Input.is_key_pressed(KEY_A)),
		float(Input.is_key_pressed(KEY_E)) - float(Input.is_key_pressed(KEY_Q)),
		float(Input.is_key_pressed(KEY_S)) - float(Input.is_key_pressed(KEY_W)))
	if move != Vector3.ZERO:
		var speed := 14.0 if Input.is_key_pressed(KEY_SHIFT) else 4.0
		_camera.position += _camera.basis * move.normalized() * speed * delta

	# Smoothed frame time plus the worst frame of the last two seconds: the
	# worst frame is what a 120 fps target actually has to survive.
	var ms := delta * 1000.0
	_frame_ms = lerpf(_frame_ms, ms, 0.05) if _frame_ms > 0.0 else ms
	_worst_age += delta
	if ms > _worst_ms or _worst_age > 2.0:
		_worst_ms = ms
		_worst_age = 0.0
	if _overlay.visible:
		var hour := int(atmosphere.time_of_day)
		var minute := int(fposmod(atmosphere.time_of_day, 1.0) * 60.0)
		_overlay.text = "%d fps   %.1f ms   worst %.1f ms\n%02d:%02d   power %s   flashlight %s" % [
			Engine.get_frames_per_second(), _frame_ms, _worst_ms, hour, minute,
			"on" if atmosphere.power_on else "OFF", "on" if _flashlight.visible else "off"]

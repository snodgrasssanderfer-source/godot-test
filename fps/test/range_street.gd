extends Node3D
## Shooting range on the greybox street: the player, the three weapons and
## seven targets, for tuning movement and gun feel. Reserve ammo is endless.
## Two television sets sleep by every street lamp; enough gunfire on their
## stretch of the power grid wakes them.
##
##   WASD move    Shift sprint    Ctrl / C crouch (while sprinting: slide)
##   Space jump, climb, vault     Q / E lean      F flashlight
##   LMB fire     RMB aim         R reload        1 2 3 / wheel  weapons
##
##   [ ]  time -/+ half an hour   T  run / freeze the clock   P  power on / off
##   G    SDFGI on / off          V  volumetric fog on / off
##   - =  mouse sensitivity down / up
##   B    benchmark (about two minutes; results stay on screen)
##   F6 F7 F8 F9  hurt a leg / the support arm / the gun arm / the torso
##   J    tape the worst bleeding   K  painkiller   H  heal everything
##   N    jolt the nearest grid cell (+25)   M  creatures on / off
##   F1   help    F2  numbers     F5  back to the start       F11 fullscreen
##   Esc  free the mouse          F10 quit
##
## Command line (after "--"):
##   --res=1280x720   render size (default 1920x1080, scaled to the window)
##   --bench[=secs]   run the benchmark and quit
##   --selftest       scripted checks of movement and weapons, then quit
##   --shoot=<dir>    scripted screenshots of the weapons, then quit

const SPAWN := Vector3(-0.9, 0.1, -3.0)
const CRT: CreatureData = preload("res://creatures/data/crt.tres")
## What the navigation mesh covers: the road, pavements and front gardens, up
## to head height (roofs are not for walking on).
const NAV_BOUNDS := AABB(Vector3(-30.0, -1.0, -110.0), Vector3(60.0, 3.5, 120.0))
const HELP := """WASD move   Shift sprint   Ctrl/C crouch, slide   Space jump / climb / vault   Q E lean
LMB fire   RMB aim   R reload   1 2 3 or wheel: weapons   F flashlight
[ ] time   T clock   P power   G SDFGI   V fog   B benchmark   - = mouse sensitivity
F6 F7 F8 F9 hurt leg / support arm / gun arm / torso   J tape   K painkiller   H heal
N jolt the power grid here (+25)   M creatures on / off
F1 help   F2 numbers   F5 respawn   F11 fullscreen   Esc mouse   F10 quit"""

@onready var atmosphere: Atmosphere = $Atmosphere
@onready var street: StreetSet = $StreetSet

var player: Player
var hud: Hud
var benchmark: Benchmark

var _overlay: Label
var _help: Label
var _report: Label
var _automated := false
var _frame_ms := 0.0
var _worst_ms := 0.0
var _worst_age := 0.0
var _noise := 0.0
var _noise_age := 99.0

## The power grid on this street: one cell per lamp, wired in a line.
var grid_nodes: Array[GridNode] = []
## The sleepers hanging on it (empty when the range runs without creatures).
var creatures: Array[Creature] = []
var nav_region: NavigationRegion3D


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
		elif arg.begins_with("--bench"):
			auto_mode = "bench"
			auto_arg = arg.trim_prefix("--bench").trim_prefix("=")
	_automated = auto_mode != ""

	# Always render at a fixed size, whatever the monitor: frame rates measured
	# on different screens stay comparable, and 1080p is the size we target.
	var window := get_window()
	window.content_scale_mode = Window.CONTENT_SCALE_MODE_VIEWPORT
	window.content_scale_aspect = Window.CONTENT_SCALE_ASPECT_KEEP
	window.content_scale_size = render_size
	if OS.has_feature("template") and not _automated:
		window.mode = Window.MODE_EXCLUSIVE_FULLSCREEN

	atmosphere.day_length_minutes = 0.0
	atmosphere.time_of_day = 11.0

	player = Player.new()
	player.name = "Player"
	add_child(player)
	player.rig.infinite_reserve = true
	player.noise_made.connect(func(radius: float) -> void:
		if radius >= _noise or _noise_age > 0.6:
			_noise = radius
			_noise_age = 0.0)
	_respawn()
	# Scripted runs start without creatures: the weapon checks fire a lot, and
	# woken sets wandering in would spoil them. The creature checks add their own.
	if not _automated:
		bake_nav()
	build_grid(not _automated)
	player.body.died.connect(_on_player_died)

	hud = Hud.new()
	add_child(hud)
	hud.setup(player)
	hud.show_damage = true

	benchmark = Benchmark.new()
	add_child(benchmark)
	benchmark.finished.connect(_on_benchmark_finished)

	var layer := CanvasLayer.new()
	layer.layer = 110       # above the film pass, so the numbers stay crisp
	add_child(layer)
	_overlay = _label(layer, 18)
	_overlay.position = Vector2(16, 12)
	_help = _label(layer, 18)
	_help.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_LEFT, Control.PRESET_MODE_MINSIZE, 16)
	_help.grow_vertical = Control.GROW_DIRECTION_BEGIN
	_help.text = HELP
	_report = _label(layer, 26)
	_report.set_anchors_and_offsets_preset(Control.PRESET_CENTER)
	_report.grow_horizontal = Control.GROW_DIRECTION_BOTH
	_report.grow_vertical = Control.GROW_DIRECTION_BOTH
	var mono := SystemFont.new()
	mono.font_names = PackedStringArray(["Consolas", "DejaVu Sans Mono", "Courier New", "monospace"])
	_report.add_theme_font_override(&"font", mono)
	_report.visible = false

	atmosphere.set_time(atmosphere.time_of_day)      # lamps exist now: sync them

	match auto_mode:
		"":
			Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
		"bench":
			_overlay.visible = false
			_help.visible = false
			if auto_arg.is_valid_float():
				benchmark.seconds_per_run = float(auto_arg)
			_start_benchmark()
		_:
			_help.visible = false
			var auto := RangeAuto.new()
			add_child(auto)
			auto.start(self, auto_mode, auto_arg)


func _label(layer: CanvasLayer, font_size: int) -> Label:
	var label := Label.new()
	label.add_theme_font_size_override(&"font_size", font_size)
	label.add_theme_color_override(&"font_outline_color", Color.BLACK)
	label.add_theme_constant_override(&"outline_size", 6)
	layer.add_child(label)
	return label


## Bakes the navigation mesh creatures walk on, from the street's collision.
## Does nothing the second time. The map takes a physics frame to pick it up.
func bake_nav() -> void:
	if nav_region == null:
		nav_region = Nav.bake(street, NAV_BOUNDS)


## (Re)builds the power grid from scratch: one GridNode under every street
## lamp. With creatures, two CRT sets sleep on the pavement by each lamp post,
## tied to that cell; without, the cells carry a default roster that exists
## only as numbers.
func build_grid(with_creatures := false) -> void:
	for old in creatures:
		old.queue_free()
	creatures.clear()
	for old in grid_nodes:
		old.queue_free()
	grid_nodes.clear()
	Grid.reset()
	for lamp: Light3D in get_tree().get_nodes_in_group(&"street_lamp"):
		var node := GridNode.new()
		node.position = Vector3(lamp.global_position.x, 0.0, lamp.global_position.z)
		node.lamp = lamp
		if with_creatures:
			node.roster = {CRT.grid_kind: 2}
		add_child(node)
		if not grid_nodes.is_empty():
			node.link_to(grid_nodes.back())
		grid_nodes.append(node)
		if with_creatures:
			# The lamp head hangs over the road; its post stands on the pavement behind it.
			var side := signf(lamp.global_position.x)
			for entry in 2:
				var at := Vector3(side * 4.75, 0.3, lamp.global_position.z + (1.3 if entry == 0 else -1.3))
				creatures.append(Creature.spawn(CRT, self, at, side * PI * 0.5, node.id, entry))


func _respawn() -> void:
	player.body.reset()
	player.global_position = SPAWN
	player.velocity = Vector3.ZERO
	player.set_look(0.0)


## On the range, dying just puts you back at the start a few seconds later.
func _on_player_died(_cause: StringName) -> void:
	if _automated:
		return
	await get_tree().create_timer(5.0).timeout
	if player.body.sim.dead:
		_respawn()


## Debug keys: a hit hard enough to drop the part one step, as a cut (it bleeds).
func _hurt(part: BodySim.Part, damage: float, kind: BodySim.Kind) -> void:
	player.body.hit_part(part, damage, kind)


func _tape_worst() -> void:
	var sim := player.body.sim
	var id := sim.worst_bleeding()
	if id > 0 and not sim.tape(id):
		sim.towel_tape(id)


func _input(event: InputEvent) -> void:
	if _automated:
		return
	if event is InputEventKey and event.pressed and event.physical_keycode == KEY_F10:
		get_tree().quit()
		return
	if benchmark.running:
		get_viewport().set_input_as_handled()
		return
	if _report.visible and event.is_pressed() and (event is InputEventKey or event is InputEventMouseButton):
		_report.visible = false
		get_viewport().set_input_as_handled()
		return
	if event is InputEventMouseButton and event.pressed and Input.mouse_mode != Input.MOUSE_MODE_CAPTURED:
		# The click that grabs the mouse must not also fire the gun.
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
		get_viewport().set_input_as_handled()
		return
	if event is InputEventKey and event.pressed and not event.echo:
		match event.physical_keycode:
			KEY_ESCAPE: Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
			KEY_BRACKETLEFT: atmosphere.time_of_day = fposmod(atmosphere.time_of_day - 0.5, 24.0)
			KEY_BRACKETRIGHT: atmosphere.time_of_day = fposmod(atmosphere.time_of_day + 0.5, 24.0)
			KEY_T: atmosphere.day_length_minutes = 0.0 if atmosphere.day_length_minutes > 0.0 else 2.0
			KEY_P: atmosphere.power_on = not atmosphere.power_on
			KEY_G: atmosphere.sdfgi = not atmosphere.sdfgi
			KEY_V: atmosphere.volumetric_fog = not atmosphere.volumetric_fog
			KEY_B: _start_benchmark()
			KEY_MINUS: Controls.mouse_sensitivity = maxf(Controls.mouse_sensitivity / 1.1, 0.0003)
			KEY_EQUAL: Controls.mouse_sensitivity = minf(Controls.mouse_sensitivity * 1.1, 0.012)
			KEY_F1: _help.visible = not _help.visible
			KEY_F2: _overlay.visible = not _overlay.visible
			KEY_F5: _respawn()
			KEY_F6: _hurt(BodySim.Part.LEG_L, 30.0, BodySim.Kind.CUT)
			KEY_F7: _hurt(BodySim.Part.ARM_L, 25.0, BodySim.Kind.CUT)
			KEY_F8: _hurt(BodySim.Part.ARM_R, 25.0, BodySim.Kind.CUT)
			KEY_F9: _hurt(BodySim.Part.TORSO, 30.0, BodySim.Kind.CRUSH)
			KEY_J: _tape_worst()
			KEY_K: player.body.sim.take_painkiller()
			KEY_H: player.body.reset()
			KEY_N: Grid.sim.inject_node(Grid.sim.nearest(player.global_position), 25.0)
			KEY_M: build_grid(creatures.is_empty())
			KEY_F11:
				var window := get_window()
				var full := window.mode == Window.MODE_EXCLUSIVE_FULLSCREEN or window.mode == Window.MODE_FULLSCREEN
				window.mode = Window.MODE_WINDOWED if full else Window.MODE_EXCLUSIVE_FULLSCREEN


func _start_benchmark() -> void:
	player.process_mode = Node.PROCESS_MODE_DISABLED
	hud.visible = false
	_report.visible = false
	benchmark.run(atmosphere)


func _on_benchmark_finished(report: String) -> void:
	if _automated:
		get_tree().quit()
		return
	player.process_mode = Node.PROCESS_MODE_INHERIT
	hud.visible = true
	_report.text = report + "\n\n(any key to close)"
	_report.visible = true


func _process(delta: float) -> void:
	# Smoothed frame time plus the worst frame of the last two seconds: the
	# worst frame is what a 120 fps target actually has to survive.
	var ms := delta * 1000.0
	_frame_ms = lerpf(_frame_ms, ms, 0.05) if _frame_ms > 0.0 else ms
	_worst_age += delta
	_noise_age += delta
	if ms > _worst_ms or _worst_age > 2.0:
		_worst_ms = ms
		_worst_age = 0.0
	if not _overlay.visible:
		return
	if benchmark.running:
		_overlay.text = benchmark.status
		return
	var hour := int(atmosphere.time_of_day)
	var minute := int(fposmod(atmosphere.time_of_day, 1.0) * 60.0)
	var grid_text := ""
	for node in grid_nodes:
		grid_text += ("  --" if Grid.sim.is_tripped(node.id) else "  %2d" % int(node.charge()))
	var body_text := "DEAD" if player.body.sim.dead else player.body.sim.summary()
	if creatures.is_empty():
		grid_text += "   (no creatures)"
	_overlay.text = "%d fps   %.1f ms   worst %.1f ms\n%02d:%02d   power %s   SDFGI %s   fog %s\n%.1f m/s   noise %s   mouse %.0f%%\ngrid%s   awake %d / %d\n%s" % [
		Engine.get_frames_per_second(), _frame_ms, _worst_ms, hour, minute,
		"on" if atmosphere.power_on else "OFF", "on" if atmosphere.sdfgi else "off",
		"on" if atmosphere.volumetric_fog else "off", player.horizontal_speed(),
		("%d m" % _noise) if _noise_age < 1.5 else "-", Controls.mouse_sensitivity / 0.0021 * 100.0,
		grid_text, Grid.sim.count(true), Grid.sim.count(), body_text]

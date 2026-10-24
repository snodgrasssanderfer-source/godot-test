extends Control
## Entry point: renders the 3D world at low resolution (pixel look), puts the
## UI on top at full resolution, routes the action button and runs sleep.

## The 3D view renders at 1/PIXEL_SCALE of the 1280x720 canvas (320x180) and is
## scaled back up with nearest-neighbour filtering.
const PIXEL_SCALE := 4
const DIALOGUE_RANGE := 4.0

var world: Node3D
var player: CharacterBody3D
var hud: Control
var touch: Control
var _sleeping := false


func _ready() -> void:
	var container := SubViewportContainer.new()
	container.set_anchors_preset(PRESET_FULL_RECT)
	container.stretch = true
	container.stretch_shrink = PIXEL_SCALE
	container.texture_filter = CanvasItem.TEXTURE_FILTER_NEAREST
	container.mouse_filter = MOUSE_FILTER_IGNORE
	add_child(container)
	var viewport := SubViewport.new()
	viewport.msaa_3d = Viewport.MSAA_DISABLED
	container.add_child(viewport)

	world = preload("res://scripts/world.gd").new()
	viewport.add_child(world)
	player = preload("res://scripts/player.gd").new()
	world.add_child(player)
	player.target_changed.connect(func(t): hud.set_target(t != null))

	var ui := CanvasLayer.new()
	add_child(ui)
	touch = preload("res://scripts/touch_controls.gd").new()
	touch.player = player
	touch.action_pressed.connect(_on_action)
	ui.add_child(touch)
	hud = preload("res://scripts/hud.gd").new()     # above the controls, so the fade covers them
	ui.add_child(hud)

	_intro()


func _intro() -> void:
	player.teleport(world.WAKE_POS, world.WAKE_YAW)
	_apply_debug_args()
	hud.cover_instantly()
	hud.set_day_card("像素小镇", "第 %d 天" % Game.day)
	player.controls_enabled = false
	await get_tree().create_timer(1.2).timeout
	await hud.fade_to(0.0, 1.0)
	player.controls_enabled = true
	print("Pixel Town ready: day %d, gold %d" % [Game.day, Game.gold])


## Debug start options for testing, e.g. `godot -- --pt-pos=-2.4,-0.5,-90,-40 --pt-hour=19`:
## --pt-pos=x,z,yaw_deg,pitch_deg places the player; --pt-hour=h sets the clock.
func _apply_debug_args() -> void:
	for arg in OS.get_cmdline_args() + OS.get_cmdline_user_args():
		if arg.begins_with("--pt-pos="):
			var v := arg.trim_prefix("--pt-pos=").split_floats(",")
			player.teleport(Vector3(v[0], 0, v[1]), deg_to_rad(v[2]), deg_to_rad(v[3]))
		elif arg.begins_with("--pt-hour="):
			Game.minutes = clampf(arg.trim_prefix("--pt-hour=").to_float() * 60.0, Game.DAY_START, Game.DAY_END)
		elif arg == "--pt-demo-farm":          # every growth stage, for screenshots
			for i in Game.PLOT_COUNT:
				var stage := i % 5 - 1
				Game.plots[i] = {"tilled": stage >= 0 or i % 2 == 0, "stage": stage, "watered": i % 3 == 0}
				Game.plot_changed.emit(i)


func _unhandled_input(event: InputEvent) -> void:
	if event.is_action_pressed("interact"):
		_on_action()


func _process(_delta: float) -> void:
	if hud.dialogue_open():
		var v: Node3D = world.villager
		if not v.visible or v.global_position.distance_to(player.global_position) > DIALOGUE_RANGE:
			hud.close_dialogue()
		touch.set_action("继续", true)
		return
	var t: Node = player.target
	if t and not _sleeping:
		touch.set_action(t.action_label(), t.action_enabled())
	else:
		touch.set_action("", false)


func _on_action() -> void:
	if _sleeping:
		return
	if hud.dialogue_open():
		Sfx.play("ui", -6.0)
		hud.advance_dialogue()
		return
	var t: Node = player.target
	if t == null:
		return
	if t.action_enabled():
		t.interact(self)
	else:
		Sfx.play("ui", -10.0)


func start_sleep() -> void:
	if _sleeping:
		return
	_sleeping = true
	player.controls_enabled = false
	player.move_input = Vector2.ZERO
	Game.clock_running = false
	Sfx.play("sleep")
	hud.set_day_card("晚安……", "")
	await hud.fade_to(1.0, 1.2)

	var income: int = Game.sleep()
	world.villager.reset_for_new_day()
	player.teleport(world.WAKE_POS, world.WAKE_YAW)
	var sub := "已自动存档"
	if income > 0:
		sub = "出货收入 +%d 金币  ·  已自动存档" % income
	hud.set_day_card("第 %d 天" % Game.day, sub)
	await get_tree().create_timer(2.0).timeout

	Sfx.play("wake")
	if income > 0:
		Sfx.play("coin")
	await hud.fade_to(0.0, 1.2)
	if income > 0:
		hud.toast("出货收入 +%d 金币" % income)
	Game.clock_running = true
	player.controls_enabled = true
	_sleeping = false

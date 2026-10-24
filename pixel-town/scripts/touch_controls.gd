extends Control
## Phone controls (multi-touch):
## - left half: floating joystick (rests at the bottom-left corner)
## - right half: swipe to look around
## - bottom-right: one big context button (锄地/播种/浇水/收获/对话/睡觉...)

signal action_pressed

const JOY_RADIUS := 105.0
const KNOB_RADIUS := 46.0
const JOY_HOME := Vector2(190, -180)          # from the bottom-left corner
const ACTION_RADIUS := 100.0
const ACTION_POS := Vector2(-185, -185)       # from the bottom-right corner
const CREAM := Color("fff6e4")
const BROWN := Color("6b4a35")

var player: Node
var action_label := ""
var action_enabled := false

var _joy_index := -1
var _joy_center := Vector2.ZERO
var _joy_vec := Vector2.ZERO
var _look_index := -1
var _look_last := Vector2.ZERO
var _action_index := -1
var _action_scale := 1.0


func _ready() -> void:
	set_anchors_and_offsets_preset(PRESET_FULL_RECT)
	mouse_filter = MOUSE_FILTER_IGNORE


func set_action(label: String, enabled: bool) -> void:
	if label == action_label and enabled == action_enabled:
		return
	action_label = label
	action_enabled = enabled
	queue_redraw()


func _joy_home() -> Vector2:
	return Vector2(JOY_HOME.x, size.y + JOY_HOME.y)


func _action_center() -> Vector2:
	return Vector2(size.x + ACTION_POS.x, size.y + ACTION_POS.y)


func _input(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		if event.pressed:
			_touch_down(event.index, event.position)
		else:
			_touch_up(event.index)
	elif event is InputEventScreenDrag:
		if event.index == _joy_index:
			_joy_vec = ((event.position - _joy_center) / JOY_RADIUS).limit_length(1.0)
			player.move_input = _joy_vec
			queue_redraw()
		elif event.index == _look_index:
			player.add_look(event.position - _look_last)
			_look_last = event.position


func _touch_down(index: int, pos: Vector2) -> void:
	if pos.distance_to(_action_center()) <= ACTION_RADIUS * 1.2:
		_action_index = index
		_action_scale = 0.88
		queue_redraw()
		action_pressed.emit()
	elif pos.x < size.x * 0.5:
		if _joy_index == -1:
			_joy_index = index
			# The stick appears under the thumb, kept fully on screen.
			_joy_center = pos.clamp(Vector2(JOY_RADIUS, JOY_RADIUS), size - Vector2(JOY_RADIUS, JOY_RADIUS))
			_joy_vec = ((pos - _joy_center) / JOY_RADIUS).limit_length(1.0)
			player.move_input = _joy_vec
			queue_redraw()
	elif _look_index == -1:
		_look_index = index
		_look_last = pos


func _touch_up(index: int) -> void:
	if index == _joy_index:
		_joy_index = -1
		_joy_vec = Vector2.ZERO
		player.move_input = Vector2.ZERO
		queue_redraw()
	elif index == _look_index:
		_look_index = -1
	elif index == _action_index:
		_action_index = -1
		queue_redraw()


func _process(delta: float) -> void:
	var target_scale := 0.88 if _action_index != -1 else 1.0
	if absf(_action_scale - target_scale) > 0.001:
		_action_scale = lerpf(_action_scale, target_scale, 1.0 - exp(-18.0 * delta))
		queue_redraw()


func _draw() -> void:
	# Joystick
	var center := _joy_center if _joy_index != -1 else _joy_home()
	draw_circle(center, JOY_RADIUS, Color(CREAM, 0.22))
	draw_arc(center, JOY_RADIUS, 0, TAU, 40, Color(CREAM, 0.6), 4)
	draw_circle(center + _joy_vec * JOY_RADIUS, KNOB_RADIUS, Color(CREAM, 0.75))
	draw_arc(center + _joy_vec * JOY_RADIUS, KNOB_RADIUS, 0, TAU, 32, Color(BROWN, 0.5), 3)

	# Action button
	if action_label == "":
		return
	var c := _action_center()
	var r := ACTION_RADIUS * _action_scale
	var fill := Color("ffd479") if action_enabled else Color(CREAM, 0.45)
	draw_circle(c + Vector2(0, 6), r, Color(BROWN, 0.35))
	draw_circle(c, r, fill)
	draw_arc(c, r, 0, TAU, 48, Color(BROWN, 0.85 if action_enabled else 0.4), 5)
	var font := get_theme_default_font()
	var font_size := 36 if action_label.length() <= 2 else 24
	var text_color := BROWN if action_enabled else Color(BROWN, 0.55)
	draw_string(font, c + Vector2(-r, font_size * 0.35), action_label,
		HORIZONTAL_ALIGNMENT_CENTER, r * 2, font_size, text_color)

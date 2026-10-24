extends Control
## On-screen buttons for phones. Supports multi-touch (e.g. hold right + jump)
## and sliding a finger between the left/right buttons.

const BUTTON_RADIUS := 60.0
const MARGIN := 30.0

# action name -> label
const BUTTONS := {"move_left": "<", "move_right": ">", "jump": "^"}

# touch index -> action currently held by that finger ("" for none)
var _touches := {}


func _ready() -> void:
	resized.connect(queue_redraw)


func _button_centers() -> Dictionary:
	var r := BUTTON_RADIUS
	var bottom := size.y - MARGIN - r
	return {
		"move_left": Vector2(MARGIN + r, bottom),
		"move_right": Vector2(MARGIN + r * 3 + 20, bottom),
		"jump": Vector2(size.x - MARGIN - r, bottom),
	}


func _action_at(point: Vector2) -> String:
	var centers := _button_centers()
	for action in centers:
		# Slightly larger hit area than the drawn circle for fat fingers.
		if point.distance_to(centers[action]) <= BUTTON_RADIUS * 1.25:
			return action
	return ""


func _input(event: InputEvent) -> void:
	if event is InputEventScreenTouch:
		if event.pressed:
			_set_touch(event.index, _action_at(event.position))
		else:
			_set_touch(event.index, "")
			_touches.erase(event.index)
	elif event is InputEventScreenDrag and _touches.has(event.index):
		_set_touch(event.index, _action_at(event.position))


func _set_touch(index: int, action: String) -> void:
	var old: String = _touches.get(index, "")
	if old == action:
		return
	_touches[index] = action
	if old != "" and not _touches.values().has(old):
		Input.action_release(old)
	if action != "":
		Input.action_press(action)
	queue_redraw()


func _draw() -> void:
	var centers := _button_centers()
	var font := get_theme_default_font()
	for action in centers:
		var held: bool = _touches.values().has(action)
		var alpha := 0.6 if held else 0.3
		draw_circle(centers[action], BUTTON_RADIUS, Color(1, 1, 1, alpha))
		draw_string(font, centers[action] + Vector2(-BUTTON_RADIUS, 16), BUTTONS[action],
			HORIZONTAL_ALIGNMENT_CENTER, BUTTON_RADIUS * 2, 48, Color(0, 0, 0, 0.7))

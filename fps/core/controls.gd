extends Node
## Default input bindings, registered in code. Keeping them out of project.godot
## means a rebinding menu can later rewrite this same map and save it.

const KEYS := {
	&"move_forward": [KEY_W], &"move_back": [KEY_S], &"move_left": [KEY_A], &"move_right": [KEY_D],
	&"jump": [KEY_SPACE], &"sprint": [KEY_SHIFT], &"crouch": [KEY_CTRL, KEY_C],
	&"lean_left": [KEY_Q], &"lean_right": [KEY_E], &"reload": [KEY_R], &"flashlight": [KEY_F],
	&"weapon_1": [KEY_1], &"weapon_2": [KEY_2], &"weapon_3": [KEY_3],
}
const MOUSE := {
	&"fire": MOUSE_BUTTON_LEFT, &"aim": MOUSE_BUTTON_RIGHT,
	&"weapon_next": MOUSE_BUTTON_WHEEL_DOWN, &"weapon_prev": MOUSE_BUTTON_WHEEL_UP,
}

## Radians of view rotation per pixel of mouse travel.
var mouse_sensitivity := 0.0021


func _enter_tree() -> void:
	for action: StringName in KEYS:
		if InputMap.has_action(action):
			continue
		InputMap.add_action(action)
		for key: Key in KEYS[action]:
			var event := InputEventKey.new()
			event.physical_keycode = key
			InputMap.action_add_event(action, event)
	for action: StringName in MOUSE:
		if InputMap.has_action(action):
			continue
		InputMap.add_action(action)
		var event := InputEventMouseButton.new()
		event.button_index = MOUSE[action]
		InputMap.action_add_event(action, event)

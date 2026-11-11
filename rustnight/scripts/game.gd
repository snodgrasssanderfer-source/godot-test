extends Node
## Global game state (autoload "G"): shared constants, references, the clock,
## the noise bus that zombies listen to, and input-map setup.

signal noise_made(pos: Vector3, radius: float, kind: String)

# physics layers
const L_WORLD := 1
const L_PLAYER := 2
const L_ZOMBIE := 4
const L_INTERACT := 8
const L_GLASS := 16
# render layers: outdoor things are lit by sun/moon, indoor things are not
const VIS_OUT := 1
const VIS_IN := 2
const VIS_VM := 4

const MINUTE_SECONDS := 0.8      # real seconds per in-game minute (one day = 19.2 min)
const START_CLOCK := 8.0 * 60.0  # day 1, 08:00

var main: Node
var world: Node3D
var player: Player
var town: Town
var zombies: ZombieManager
var hud: HUD
var fx: Fx
var sky: DayNight
var dread: Dread

var is_touch := false
var low_spec := false
var playing := false          # a run is in progress (not menu / dead / escaped)
var ui_open := false          # inventory or pause is open: no look / fire
var clock := START_CLOCK      # in-game minutes since day 1 00:00
var night := 0.0              # 0 = full day, 1 = deep night
var rain := 0.0
var kills := 0
var look_delta := Vector2.ZERO   # accumulated mouse / touch look input (radians)
var touch_move := Vector2.ZERO   # virtual stick
var sens := 1.0
var rng := RandomNumberGenerator.new()


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	rng.randomize()
	is_touch = DisplayServer.is_touchscreen_available() or OS.has_feature("web_android") or OS.has_feature("web_ios") or OS.has_feature("mobile")
	low_spec = is_touch
	_setup_input()


func _setup_input() -> void:
	var keys := {
		"move_fwd": [KEY_W, KEY_UP], "move_back": [KEY_S, KEY_DOWN],
		"move_left": [KEY_A, KEY_LEFT], "move_right": [KEY_D, KEY_RIGHT],
		"jump": [KEY_SPACE], "sprint": [KEY_SHIFT], "crouch": [KEY_C, KEY_CTRL],
		"reload": [KEY_R], "interact": [KEY_E], "melee": [KEY_V], "flashlight": [KEY_F],
		"inventory": [KEY_TAB, KEY_I], "slot1": [KEY_1], "slot2": [KEY_2], "slot3": [KEY_3],
		"swap": [KEY_Q], "barricade": [KEY_B], "map": [KEY_M], "pause": [KEY_ESCAPE, KEY_P],
		"heal": [KEY_H], "fire": [], "ads": [],
	}
	for a in keys:
		if not InputMap.has_action(a):
			InputMap.add_action(a, 0.3)
		for k in keys[a]:
			var ev := InputEventKey.new()
			ev.physical_keycode = k
			InputMap.action_add_event(a, ev)


func reset_run() -> void:
	clock = START_CLOCK
	night = 0.0
	rain = 0.0
	kills = 0
	look_delta = Vector2.ZERO
	touch_move = Vector2.ZERO
	ui_open = false


func hour() -> float:
	return fmod(clock, 1440.0) / 60.0


func day() -> int:
	return int(clock / 1440.0) + 1


func clock_text() -> String:
	var m := int(fmod(clock, 1440.0))
	return "%02d:%02d" % [m / 60, m % 60]


func survived_text() -> String:
	var m := int(clock - START_CLOCK)
	return "%d 天 %d 小时 %d 分" % [m / 1440, (m % 1440) / 60, m % 60]


## Anything that makes sound in the world goes through here; zombies subscribe.
func emit_noise(pos: Vector3, radius: float, kind: String = "") -> void:
	noise_made.emit(pos, radius * (1.0 - rain * 0.3), kind)


func msg(text: String, color: Color = Color(0.85, 0.83, 0.75)) -> void:
	if hud:
		hud.push_message(text, color)


func capture_mouse(on: bool) -> void:
	if is_touch:
		return
	Input.mouse_mode = Input.MOUSE_MODE_CAPTURED if on else Input.MOUSE_MODE_VISIBLE


func flat(v: Vector3) -> Vector3:
	return Vector3(v.x, 0.0, v.z)

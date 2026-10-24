extends AnimatableBody3D
## 小葵, a villager who strolls along the path, chats, and goes home at night.

const NAME := "小葵"
const SPEED := 1.1
const HOME_HOUR := 20.5         # goes home in the evening...
const OUT_HOUR := 6.5           # ...and comes back after breakfast
const WAYPOINTS := [
	Vector3(2.2, 0, -3.0), Vector3(2.2, 0, 3.2), Vector3(7.0, 0, 6.2),
	Vector3(13.0, 0, 6.6), Vector3(12.0, 0, 0.5), Vector3(6.0, 0, -2.0),
]
# One small set of lines per day, cycling every week.
const DAILY_LINES := [
	["早上好呀！我是住在小路那头的小葵。", "这块田空了好久，有人来照顾真好。", "种子要记得每天浇水哦～"],
	["我刚在小路边看到一只小松鼠。", "它抱着橡果跑得好快，好可爱！"],
	["傍晚的天空是粉色的，我最喜欢了。", "等太阳下山，记得早点回家休息。"],
	["听说粉萝卜生吃是甜甜的！", "收获了就放进出货箱吧，第二天早上会有金币。"],
	["风里有青草的味道呢。", "住在这里，连时间都变慢了。"],
	["晚上的虫鸣声你听到了吗？", "像小小的音乐会一样。"],
	["你来小镇已经好几天啦。", "感觉你越来越像个真正的农夫了！"],
]
const AGAIN_LINES := ["今天也辛苦啦，慢慢来就好。", "要不要去看看你的田？", "天气真好，适合散步呢。"]

var talking := false
var _target := 0
var _wait := 1.0
var _walk_t := 0.0
var _model: Node3D
var _face_yaw := 0.0


func _ready() -> void:
	collision_layer = 1 | Interact.LAYER
	collision_mask = 0
	sync_to_physics = false
	set_meta("target", self)
	_model = preload("res://assets/models/villager.glb").instantiate()
	add_child(_model)
	var shape := CollisionShape3D.new()
	var capsule := CapsuleShape3D.new()
	capsule.radius = 0.35
	capsule.height = 1.6
	shape.shape = capsule
	shape.position.y = 0.8
	add_child(shape)
	position = WAYPOINTS[0]


func is_home() -> bool:
	var h := Game.hour()
	return h >= HOME_HOUR or h < OUT_HOUR


func action_label() -> String:
	return "对话"


func action_enabled() -> bool:
	return true


func interact(main: Node) -> void:
	var lines: Array
	if Game.talked_today:
		lines = [AGAIN_LINES[randi() % AGAIN_LINES.size()]]
	else:
		lines = DAILY_LINES[(Game.day - 1) % DAILY_LINES.size()]
		Game.talked_today = true
	talking = true
	_face(main.player.global_position - global_position)
	main.hud.show_dialogue(NAME, lines, func(): talking = false)


func reset_for_new_day() -> void:
	_target = 0
	position = WAYPOINTS[0]
	_wait = 1.0


func _physics_process(delta: float) -> void:
	var home := is_home()
	visible = not home
	collision_layer = 0 if home else (1 | Interact.LAYER)
	if home:
		return
	rotation.y = lerp_angle(rotation.y, _face_yaw, 1.0 - exp(-8.0 * delta))
	if talking:
		_animate(delta, false)
		return
	if _wait > 0.0:
		_wait -= delta
		_animate(delta, false)
		return
	var goal: Vector3 = WAYPOINTS[_target]
	var to_goal := goal - position
	to_goal.y = 0
	if to_goal.length() < 0.1:
		_target = (_target + 1) % WAYPOINTS.size()
		_wait = randf_range(1.5, 4.0)
		return
	position += to_goal.normalized() * minf(SPEED * delta, to_goal.length())
	_face(to_goal)
	_animate(delta, true)


func _face(dir: Vector3) -> void:
	if dir.length() > 0.01:
		_face_yaw = atan2(dir.x, dir.z)


func _animate(delta: float, walking: bool) -> void:
	if walking:
		_walk_t += delta * 9.0
		_model.position.y = absf(sin(_walk_t)) * 0.05
		_model.rotation.z = sin(_walk_t) * 0.05
	else:
		_walk_t = 0.0
		_model.position.y = lerpf(_model.position.y, 0.0, 1.0 - exp(-10.0 * delta))
		_model.rotation.z = lerpf(_model.rotation.z, 0.0, 1.0 - exp(-10.0 * delta))

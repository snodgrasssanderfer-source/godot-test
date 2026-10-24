extends CharacterBody3D
## First-person player: smooth movement and look, gentle head bob with
## footsteps, and a center-screen ray that picks what the action button does.

signal target_changed(target: Node)

const SPEED := 3.0
const ACCEL := 9.0               # how quickly walking speed eases in and out
const LOOK_SMOOTH := 16.0        # how quickly the camera follows the finger
const LOOK_SENSITIVITY := 0.0042 # radians per (virtual) pixel of swipe
const EYE_HEIGHT := 1.5
const REACH := 3.0
const BOB_FREQ := 2.4
const BOB_AMOUNT := Vector2(0.018, 0.035)
const GRAVITY := 18.0

var move_input := Vector2.ZERO   # set by the on-screen joystick
var controls_enabled := true
var target: Node = null

var _yaw := 0.0
var _pitch := -0.15
var _target_yaw := 0.0
var _target_pitch := -0.15
var _bob_t := 0.0
var _bob_weight := 0.0
var _last_step_sign := 1.0
var _nudge := 0.0
var _head: Node3D
var camera: Camera3D
var _ray: RayCast3D


func _ready() -> void:
	collision_layer = 0
	collision_mask = 1
	var shape := CollisionShape3D.new()
	var capsule := CapsuleShape3D.new()
	capsule.radius = 0.3
	capsule.height = 1.6
	shape.shape = capsule
	shape.position.y = 0.8
	add_child(shape)

	_head = Node3D.new()
	_head.position.y = EYE_HEIGHT
	add_child(_head)
	camera = Camera3D.new()
	camera.fov = 70.0
	camera.near = 0.05
	camera.far = 120.0
	camera.current = true
	_head.add_child(camera)
	_ray = RayCast3D.new()
	_ray.target_position = Vector3(0, 0, -REACH)
	_ray.collision_mask = 1 | Interact.LAYER
	_ray.add_exception(self)
	camera.add_child(_ray)


func add_look(delta_px: Vector2) -> void:
	if not controls_enabled:
		return
	_target_yaw -= delta_px.x * LOOK_SENSITIVITY
	_target_pitch = clampf(_target_pitch - delta_px.y * LOOK_SENSITIVITY, -1.25, 1.1)


func teleport(pos: Vector3, yaw: float, pitch := -0.15) -> void:
	global_position = pos
	velocity = Vector3.ZERO
	_yaw = yaw
	_target_yaw = yaw
	_pitch = pitch
	_target_pitch = pitch
	rotation.y = _yaw
	_head.rotation.x = _pitch


## A small camera dip used as feedback for actions.
func nudge() -> void:
	_nudge = 1.0


func _physics_process(delta: float) -> void:
	var input := Vector2.ZERO
	if controls_enabled:
		input = move_input + Input.get_vector("move_left", "move_right", "move_forward", "move_back")
		input = input.limit_length(1.0)
	var wish := (transform.basis * Vector3(input.x, 0, input.y)) * SPEED
	var blend := 1.0 - exp(-ACCEL * delta)
	velocity.x = lerpf(velocity.x, wish.x, blend)
	velocity.z = lerpf(velocity.z, wish.z, blend)
	velocity.y = 0.0 if is_on_floor() else velocity.y - GRAVITY * delta
	move_and_slide()

	var look_blend := 1.0 - exp(-LOOK_SMOOTH * delta)
	_yaw = lerp_angle(_yaw, _target_yaw, look_blend)
	_pitch = lerpf(_pitch, _target_pitch, look_blend)
	rotation.y = _yaw
	_head.rotation.x = _pitch

	_update_bob(delta)
	_update_target()


func _update_bob(delta: float) -> void:
	var speed := Vector2(velocity.x, velocity.z).length()
	var moving := is_on_floor() and speed > 0.4
	_bob_weight = lerpf(_bob_weight, 1.0 if moving else 0.0, 1.0 - exp(-6.0 * delta))
	if moving:
		_bob_t += delta * BOB_FREQ * TAU * (speed / SPEED)
		var s := signf(sin(_bob_t))
		if s != _last_step_sign and s < 0:
			Sfx.play("step", -14.0, 0.15)
		_last_step_sign = s
	_nudge = move_toward(_nudge, 0.0, delta * 4.0)
	camera.position = Vector3(
		cos(_bob_t * 0.5) * BOB_AMOUNT.x * _bob_weight,
		(absf(sin(_bob_t)) - 0.5) * BOB_AMOUNT.y * _bob_weight - sin(_nudge * PI) * 0.04,
		0)


func _update_target() -> void:
	var found: Node = null
	if controls_enabled and _ray.is_colliding():
		var col := _ray.get_collider()
		if col and col.has_meta("target"):
			found = col.get_meta("target")
	if found != target:
		target = found
		target_changed.emit(target)

class_name TargetDummy
extends Node3D
## A shooting-range target wearing one of the greybox zombie statues. It
## flinches when hit, falls over when its health runs out and stands back up a
## few seconds later. Stands in for real zombies until they exist; anything
## that can be shot implements the same take_hit().

signal died(dummy: TargetDummy)

enum State { ALIVE, FALLING, DOWN, RISING }

const LAYER_HITBOX := 4
const FALL_TIME := 0.45
const DOWN_TIME := 3.0
const RISE_TIME := 0.8

@export var max_health := 100.0

var health := 100.0
var state := State.ALIVE
## Hits above this height (in the dummy's own space) are headshots.
var head_height := 1.58

var _pivot: Node3D
var _body: StaticBody3D
var _tilt := Vector2.ZERO          # flinch spring: x = pitch, y = roll, radians
var _tilt_v := Vector2.ZERO
var _fall_axis := Vector3.RIGHT
var _t := 0.0


## Wraps a zombie mesh from the street model in a new dummy, in place.
static func adopt(mesh: MeshInstance3D) -> TargetDummy:
	var dummy := TargetDummy.new()
	dummy.name = "Target_" + String(mesh.name)
	var xf := mesh.global_transform
	var parent := mesh.get_parent()
	parent.add_child(dummy)
	dummy.global_transform = xf
	dummy._build(mesh)
	return dummy


func _build(mesh: MeshInstance3D) -> void:
	_pivot = Node3D.new()
	_pivot.name = "Pivot"
	add_child(_pivot)
	mesh.reparent(_pivot, false)
	mesh.transform = Transform3D.IDENTITY

	# The statues are all one sculpt at slightly different sizes (1.8 m at 1.0),
	# hunched forwards: the head sits about 26 cm in front of the feet.
	var s := mesh.get_aabb().end.y / 1.8
	head_height = 1.58 * s
	_body = StaticBody3D.new()
	_body.collision_layer = LAYER_HITBOX
	_body.collision_mask = 0
	_pivot.add_child(_body)
	var head := SphereShape3D.new()
	head.radius = 0.135 * s
	_add_shape(head, Vector3(0.0, 1.71, -0.265) * s, 0.0)
	var torso := BoxShape3D.new()
	torso.size = Vector3(0.62, 0.70, 0.32) * s
	_add_shape(torso, Vector3(0.0, 1.23, -0.09) * s, -15.0)
	var legs := BoxShape3D.new()
	legs.size = Vector3(0.40, 0.92, 0.34) * s
	_add_shape(legs, Vector3(0.0, 0.47, -0.02) * s, 0.0)
	health = max_health


## Back on its feet at full health, at once.
func reset() -> void:
	state = State.ALIVE
	health = max_health
	_tilt = Vector2.ZERO
	_tilt_v = Vector2.ZERO
	_body.collision_layer = LAYER_HITBOX


## World-space points to aim at (tests, and later AI debugging).
func head_point() -> Vector3:
	return _pivot.to_global(Vector3(0.0, 1.71, -0.265) * (head_height / 1.58))


func chest_point() -> Vector3:
	return _pivot.to_global(Vector3(0.0, 1.25, -0.09) * (head_height / 1.58))


func _add_shape(shape: Shape3D, at: Vector3, pitch_deg: float) -> void:
	var cs := CollisionShape3D.new()
	cs.shape = shape
	cs.position = at
	cs.rotation_degrees.x = pitch_deg
	_body.add_child(cs)


## damage is before the headshot multiplier; point and dir are in world space.
## Returns {head, killed, damage} for hit markers, or {} if nothing was hurt.
func take_hit(damage: float, point: Vector3, dir: Vector3, weapon: WeaponData) -> Dictionary:
	if state != State.ALIVE:
		return {}
	var head := _pivot.to_local(point).y > head_height
	if head:
		damage *= weapon.head_multiplier
	health -= damage
	var local_dir := global_basis.inverse() * dir
	local_dir.y = 0.0
	if local_dir.length() > 0.01:
		local_dir = local_dir.normalized()
		var shove := clampf(damage / 60.0, 0.25, 1.6)
		_tilt_v += Vector2(local_dir.z, -local_dir.x) * shove * (1.6 if head else 1.0)
	var killed := health <= 0.0
	if killed:
		state = State.FALLING
		_t = 0.0
		_body.collision_layer = 0
		_fall_axis = Vector3.UP.cross(local_dir).normalized() if local_dir.length() > 0.01 else Vector3.RIGHT
		died.emit(self)
	return {"head": head, "killed": killed, "damage": damage}


func _process(delta: float) -> void:
	delta = minf(delta, 0.05)
	_tilt_v += (-_tilt * 120.0 - _tilt_v * 9.0) * delta
	_tilt += _tilt_v * delta
	var fall := 0.0
	match state:
		State.ALIVE:
			pass
		State.FALLING:
			_t += delta
			fall = ease(clampf(_t / FALL_TIME, 0.0, 1.0), 2.2)
			if _t >= FALL_TIME:
				state = State.DOWN
				_t = 0.0
				Sfx.play_at(&"land", global_position, -6.0)
		State.DOWN:
			_t += delta
			fall = 1.0
			if _t >= DOWN_TIME:
				state = State.RISING
				_t = 0.0
		State.RISING:
			_t += delta
			fall = 1.0 - smoothstep(0.0, 1.0, clampf(_t / RISE_TIME, 0.0, 1.0))
			if _t >= RISE_TIME:
				reset()
	var flinch := Basis.from_euler(Vector3(_tilt.x, 0.0, _tilt.y) * (1.0 - fall))
	_pivot.basis = Basis(_fall_axis, fall * deg_to_rad(87.0)) * flinch

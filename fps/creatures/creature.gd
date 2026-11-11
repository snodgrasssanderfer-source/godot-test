class_name Creature
extends CharacterBody3D
## A household appliance with a townsperson's meat inside it (GDD, section 4).
## It sleeps on its power-grid cell looking like junk; enough disturbance on
## that cell wakes it, and then it goes to wherever the noise came from, hunts
## what it sees and jumps at it.
##
## Two layers to shoot through: the casing shrugs off most of a bullet, the
## tube face is glass that has to be shot out, and behind it is the meat that
## actually dies. Implements the same take_hit() as every other target.

signal state_changed(creature: Creature, state: State)
signal attacked(creature: Creature, landed: bool)
signal died(creature: Creature)

enum State { DORMANT, WAKING, INVESTIGATE, CHASE, WINDUP, LUNGE, RECOVER, STAGGER, DEAD }

const LAYER_WORLD := 1
const LAYER_PLAYER := 2
const LAYER_HITBOX := 4
const GRAVITY := 22.0
const STAGGER_TIME := 0.7
const RAGE_TIME := 15.0            # after its cell trips (design supplement 4.3)
const RAGE_SPEED := 1.25
## Upward speed of the hop that clears low clutter (about 0.45 m).
const HOP_SPEED := 4.5
## Grid level from which a sleeping set lights up with snow (charge 35).
const SNOW_LEVEL := 2

@export var data: CreatureData

var state := State.DORMANT
var health := 0.0
var glass := 0.0
var player: Player
## Where it sits on the power grid; -1 = not on it.
var grid_node := -1
var grid_entry := -1

var _body: Node3D                   # the model's Body node: the casing and all that rides on it
var _legs: Array[Node3D] = []
var _glass_node: Node3D
var _hole_node: Node3D
var _meat_node: Node3D
var _screen: MeshInstance3D
var _screen_mat: ShaderMaterial
var _screen_at := Vector3.ZERO      # centre of the tube face, in Body space
var _glow: OmniLight3D
var _box: CollisionShape3D

var _t := 0.0
var _dur := 0.0
var _goal := Vector3.ZERO
var _heard := Vector3.INF           # last noise that reached it, asleep or awake
var _last_seen := Vector3.ZERO
var _unseen := 99.0
var _path := PackedVector3Array()
var _path_i := 0
var _repath := 0.0
var _lunge_dir := Vector3.ZERO
var _lunge_hit := false
var _rage := 0.0
var _home := Vector3.ZERO
var _idle := 0.0
var _stuck := 0.0                   # seconds of pushing without getting anywhere
var _hop_wait := 0.0
var _dodge := 0.0
var _dodge_side := 1.0

# presentation
var _rise := 0.0
var _gait := 0.0
var _power := 0.0
var _flash := 0.0
var _shake := 0.0
var _dead_roll := 0.0


## Puts a sleeping creature into the world. node / entry tie it to one roster
## entry of a grid cell, which is what wakes it.
static func spawn(creature_data: CreatureData, parent: Node, at: Vector3, yaw: float,
		node: int = -1, entry: int = -1) -> Creature:
	var c := Creature.new()
	c.data = creature_data
	c.grid_node = node
	c.grid_entry = entry
	c.name = "%s_%d_%d" % [creature_data.id, node, entry]
	parent.add_child(c)
	c.global_position = at
	c.rotation.y = yaw
	c._home = at
	return c


func _ready() -> void:
	collision_layer = LAYER_HITBOX
	collision_mask = LAYER_WORLD | LAYER_PLAYER | LAYER_HITBOX
	floor_max_angle = deg_to_rad(62.0)      # the round foot has to ride up curbs
	floor_snap_length = 0.3
	health = data.core_health
	glass = data.glass_health

	var model := data.model.instantiate() as Node3D
	add_child(model)
	_body = model.find_child("Body", true, false) as Node3D
	for leg_name: String in ["LegFL", "LegFR", "LegBL", "LegBR"]:
		var leg := model.find_child(leg_name, true, false) as Node3D
		if leg != null:
			_legs.append(leg)
	_glass_node = model.find_child("Glass", true, false) as Node3D
	_hole_node = model.find_child("Hole", true, false) as Node3D
	_meat_node = model.find_child("Meat", true, false) as Node3D
	var marker := model.find_child("Screen", true, false) as Node3D
	_screen_at = marker.position if marker != null else Vector3(0.0, data.size.y * 0.5, -data.size.z * 0.5)
	if _hole_node:
		_hole_node.visible = false
	if _meat_node:
		_meat_node.visible = false

	# The lit picture: a quad just in front of the glass.
	_screen_mat = ShaderMaterial.new()
	_screen_mat.shader = preload("res://creatures/screen.gdshader")
	var quad := QuadMesh.new()
	quad.size = data.screen_size * 0.96
	quad.material = _screen_mat
	_screen = MeshInstance3D.new()
	_screen.mesh = quad
	_screen.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_body.add_child(_screen)
	_screen.position = _screen_at + Vector3(0.0, 0.0, -0.004)
	_screen.rotation.y = PI                                   # a quad faces +Z; the set faces -Z
	_glow = OmniLight3D.new()
	_glow.light_color = Color(0.62, 0.72, 1.0)
	_glow.omni_range = 5.0
	_glow.light_energy = 0.0
	_glow.visible = false
	_body.add_child(_glow)
	_glow.position = _screen_at + Vector3(0.0, 0.0, -0.35)

	# Collision: a round foot to travel on and the casing above it.
	var foot := CollisionShape3D.new()
	var ball := SphereShape3D.new()
	ball.radius = 0.28
	foot.shape = ball
	foot.position.y = 0.28
	add_child(foot)
	_box = CollisionShape3D.new()
	var shape := BoxShape3D.new()
	shape.size = data.size
	_box.shape = shape
	add_child(_box)
	_place_box()

	player = get_tree().get_first_node_in_group(&"player") as Player
	NoiseBus.heard.connect(_on_noise)
	Grid.woke.connect(_on_grid_woke)
	Grid.slept.connect(_on_grid_slept)
	Grid.level_changed.connect(_on_grid_level)
	Grid.tripped.connect(_on_grid_tripped)
	_pose(0.0)


func is_awake() -> bool:
	return state != State.DORMANT and state != State.DEAD


## World-space points to aim at (tests, later the "light-seeking eye" attachment).
func screen_point() -> Vector3:
	return _body.to_global(_screen_at)


func shell_point() -> Vector3:
	return _body.to_global(Vector3(0.0, data.size.y * 0.5, 0.0))


func _enter(next: State, duration := 0.0) -> void:
	state = next
	_t = 0.0
	_dur = duration
	state_changed.emit(self, next)


# --- the power grid ---------------------------------------------------------------

func _mine(node: int, entry: int) -> bool:
	return node == grid_node and entry == grid_entry


func _on_grid_woke(node: int, entry: int, _kind: StringName) -> void:
	if _mine(node, entry):
		wake()


func _on_grid_slept(node: int, entry: int, _kind: StringName) -> void:
	if _mine(node, entry) and state in [State.INVESTIGATE, State.WAKING]:
		_enter(State.DORMANT)
		velocity = Vector3.ZERO


func _on_grid_level(node: int, _level: int) -> void:
	if node == grid_node and state == State.DORMANT and _lit_by_grid():
		Sfx.play_at(&"crt_static", global_position, -14.0)


func _on_grid_tripped(node: int) -> void:
	if node == grid_node and is_awake():
		_rage = RAGE_TIME


func _lit_by_grid() -> bool:
	return grid_node >= 0 and grid_node < Grid.sim.cells.size() and Grid.sim.level(grid_node) >= SNOW_LEVEL \
			and not Grid.sim.is_tripped(grid_node)


## Gets up. Called by the grid, or by being shot at in its sleep.
func wake() -> void:
	if state != State.DORMANT:
		return
	_enter(State.WAKING, data.wake_time)
	Sfx.play_at(&"crt_wake", global_position, -2.0)
	if grid_node >= 0:
		Grid.sim.rouse(grid_node, grid_entry)


# --- senses -----------------------------------------------------------------------

func _on_noise(position: Vector3, radius: float, _energy: float, source: StringName, _occlusion: float) -> void:
	if state == State.DEAD or source == &"creature":
		return
	if global_position.distance_to(position) > radius:
		return
	_heard = position
	if state == State.INVESTIGATE:
		_set_goal(position)


func _eye() -> Vector3:
	return _body.to_global(_screen_at)


func _sees_player() -> bool:
	if player == null or player.body.sim.dead:
		return false
	var eye := _eye()
	var target := player.global_position + Vector3.UP * (0.6 if player.crouched else 1.1)
	var to := target - eye
	var dist := to.length()
	if dist > data.sight_range:
		return false
	if dist > data.close_range:
		var flat := Vector3(to.x, 0.0, to.z).normalized()
		if (-global_basis.z).angle_to(flat) > deg_to_rad(data.sight_cone):
			return false
	var q := PhysicsRayQueryParameters3D.create(eye, target, LAYER_WORLD)
	return get_world_3d().direct_space_state.intersect_ray(q).is_empty()


func _flat_to_player() -> Vector3:
	var d := player.global_position - global_position
	d.y = 0.0
	return d


# --- getting shot -----------------------------------------------------------------

## damage is the weapon's; point and dir are in world space. Returns
## {head, killed, damage, flesh}: head = through the broken screen (the weak
## point), flesh = it bled rather than sparked.
func take_hit(damage: float, point: Vector3, dir: Vector3, weapon: WeaponData) -> Dictionary:
	if state == State.DEAD:
		return {}
	var local := _body.to_local(point)
	var melee := weapon != null and weapon.kind == WeaponData.Kind.MELEE
	var dealt := 0.0
	var flesh := false
	var weak := false
	var broke := false
	var on_screen := (local.z < _screen_at.z + 0.07
			and absf(local.x - _screen_at.x) < data.screen_size.x * 0.5 + 0.02
			and absf(local.y - _screen_at.y) < data.screen_size.y * 0.5 + 0.02)
	if local.y < -0.02:
		dealt = damage * data.leg_multiplier           # the legs: bare meat
		flesh = true
	elif on_screen and glass <= 0.0:
		dealt = damage * data.weak_multiplier          # into the open tube
		flesh = true
		weak = true
	else:
		dealt = damage * (data.melee_through if melee else data.shell_through)
		if on_screen:
			glass -= damage
			if glass <= 0.0:
				broke = true
				_break_glass()
		if not broke:
			Sfx.play_at(&"shell_hit", point, -6.0)
	health -= dealt
	_shake = minf(_shake + dealt / 60.0, 1.0)

	# Whoever shot it is roughly back along the bullet.
	_heard = point - dir * 12.0
	if state == State.DORMANT:
		wake()
	elif state == State.INVESTIGATE:
		_set_goal(_heard)

	var killed := health <= 0.0
	if killed:
		_die(dir)
	elif (broke or dealt >= data.stagger_damage) and state not in [State.WAKING, State.LUNGE]:
		_enter(State.STAGGER, STAGGER_TIME)
		var push := Vector3(dir.x, 0.0, dir.z).normalized()
		velocity = push * 3.0
	return {"head": weak, "killed": killed, "damage": dealt, "flesh": flesh}


func _break_glass() -> void:
	glass = 0.0
	if _glass_node:
		_glass_node.visible = false
	if _hole_node:
		_hole_node.visible = true
	if _meat_node:
		_meat_node.visible = true
	_screen.visible = false
	Sfx.play_at(&"glass_break", screen_point(), -2.0)


func _die(dir: Vector3) -> void:
	_enter(State.DEAD)
	velocity = Vector3.ZERO
	_dead_roll = -1.0 if (global_basis.x.dot(dir) > 0.0) else 1.0
	collision_layer = LAYER_WORLD          # the corpse is furniture again: cover, and climbable
	collision_mask = LAYER_WORLD
	Sfx.play_at(&"crt_die", global_position, -1.0)
	if grid_node >= 0:
		Grid.sim.kill(grid_node, grid_entry)
	died.emit(self)


# --- behaviour --------------------------------------------------------------------

func _set_goal(goal: Vector3) -> void:
	_goal = goal
	_repath = 0.0


func _physics_process(delta: float) -> void:
	_t += delta
	_rage = maxf(_rage - delta, 0.0)
	match state:
		State.DORMANT, State.DEAD:
			if not is_on_floor():
				velocity.y -= GRAVITY * delta
				move_and_slide()
			return
		State.WAKING:
			velocity.x = 0.0
			velocity.z = 0.0
			if _t >= _dur:
				_set_goal(_heard if _heard != Vector3.INF else _home)
				_enter(State.INVESTIGATE)
		State.INVESTIGATE:
			if _sees_player():
				_engage()
			else:
				_tick_investigate(delta)
		State.CHASE:
			_tick_chase(delta)
		State.WINDUP:
			velocity.x = move_toward(velocity.x, 0.0, 30.0 * delta)
			velocity.z = move_toward(velocity.z, 0.0, 30.0 * delta)
			_face(_flat_to_player(), delta, 1.6)
			if _t >= _dur:
				_lunge_dir = _flat_to_player().normalized()
				_lunge_hit = false
				velocity = _lunge_dir * data.lunge_speed + Vector3.UP * 3.2
				_enter(State.LUNGE, data.lunge_time)
		State.LUNGE:
			velocity.x = _lunge_dir.x * data.lunge_speed
			velocity.z = _lunge_dir.z * data.lunge_speed
			if not _lunge_hit and _flat_to_player().length() < data.hit_reach \
					and absf(player.global_position.y - global_position.y) < 1.6:
				_lunge_hit = true
				_land_hit()
			elif _t >= _dur:
				attacked.emit(self, false)
				_enter(State.RECOVER, data.recover_miss)
		State.RECOVER, State.STAGGER:
			velocity.x = move_toward(velocity.x, 0.0, 14.0 * delta)
			velocity.z = move_toward(velocity.z, 0.0, 14.0 * delta)
			if _t >= _dur:
				_unseen = 0.0
				_enter(State.CHASE)
	velocity.y -= GRAVITY * delta
	move_and_slide()


func _engage() -> void:
	_unseen = 0.0
	_last_seen = player.global_position
	if grid_node >= 0:
		Grid.sim.set_engaged(grid_node, grid_entry, true)
	_enter(State.CHASE)


func _tick_investigate(delta: float) -> void:
	var to_goal := _goal - global_position
	to_goal.y = 0.0
	if to_goal.length() > 0.9:
		_idle = 0.0
		_travel(_goal, data.walk_speed, delta)
		return
	# Arrived and found nothing: stand, turn slowly, then drift somewhere near.
	velocity.x = move_toward(velocity.x, 0.0, 8.0 * delta)
	velocity.z = move_toward(velocity.z, 0.0, 8.0 * delta)
	rotation.y += 0.6 * delta
	_idle += delta
	if _idle > 4.0:
		_idle = 0.0
		var a := randf() * TAU
		_set_goal(_goal + Vector3(cos(a), 0.0, sin(a)) * randf_range(2.0, 5.0))


func _tick_chase(delta: float) -> void:
	if player == null or player.body.sim.dead:
		_set_goal(global_position)
		_enter(State.INVESTIGATE)
		return
	if _sees_player():
		_unseen = 0.0
		_last_seen = player.global_position
		if _flat_to_player().length() < data.attack_range:
			_enter(State.WINDUP, data.windup)
			Sfx.play_at(&"crt_screech", global_position, -1.0)
			return
	else:
		_unseen += delta
		if _unseen > data.memory:
			_set_goal(_last_seen)
			_enter(State.INVESTIGATE)
			return
	_travel(_last_seen, data.run_speed * (RAGE_SPEED if _rage > 0.0 else 1.0), delta)


func _land_hit() -> void:
	player.body.hit(randf_range(data.damage_min, data.damage_max), data.wound, data.attack)
	player.velocity += _lunge_dir * 2.5
	velocity.x *= 0.2
	velocity.z *= 0.2
	attacked.emit(self, true)
	_enter(State.RECOVER, data.recover_hit)


## Walks towards a goal along the navigation mesh (straight at it if there is
## no route), turning to face the way it goes.
func _travel(goal: Vector3, speed: float, delta: float) -> void:
	_repath -= delta
	if _repath <= 0.0:
		_repath = 0.35
		_path = Nav.path(get_world_3d(), global_position, goal)
		_path_i = 0
	var next := goal
	while _path_i < _path.size():
		var step := _path[_path_i] - global_position
		step.y = 0.0
		if step.length() > 0.45:
			next = _path[_path_i]
			break
		_path_i += 1
	var dir := next - global_position
	dir.y = 0.0
	if dir.length() < 0.05:
		return
	dir = dir.normalized()

	# Clutter the navigation mesh does not know about (or another set in the
	# way): first hop it, and if that does not help, go round it.
	_hop_wait = maxf(_hop_wait - delta, 0.0)
	if Vector2(velocity.x, velocity.z).length() < speed * 0.25:
		_stuck += delta
	else:
		_stuck = 0.0
	if _stuck > 0.35 and _hop_wait <= 0.0 and is_on_floor():
		velocity.y = HOP_SPEED
		_hop_wait = 0.9
	if _stuck > 1.5:
		_stuck = 0.6
		_dodge = 0.7
		_dodge_side = -_dodge_side
	if _dodge > 0.0:
		_dodge -= delta
		dir = dir.rotated(Vector3.UP, _dodge_side * deg_to_rad(75.0))

	var k := 1.0 - exp(-8.0 * delta)
	velocity.x = lerpf(velocity.x, dir.x * speed, k)
	velocity.z = lerpf(velocity.z, dir.z * speed, k)
	_face(dir, delta)


func _face(dir: Vector3, delta: float, rate_scale := 1.0) -> void:
	if dir.length() < 0.01:
		return
	rotation.y = rotate_toward(rotation.y, atan2(-dir.x, -dir.z), data.turn_rate * rate_scale * delta)


# --- presentation -----------------------------------------------------------------

func _place_box() -> void:
	_box.position.y = data.stand_height * _rise + data.size.y * 0.5


func _process(delta: float) -> void:
	delta = minf(delta, 0.05)
	_pose(delta)


func _pose(delta: float) -> void:
	var k := func(rate: float) -> float: return 1.0 - exp(-rate * delta)
	var up := 1.0
	match state:
		State.DORMANT:
			up = 0.0
		State.WAKING:
			up = smoothstep(0.15, 0.85, _t / maxf(_dur, 0.01))
		State.DEAD:
			up = 0.10
	_rise = lerpf(_rise, up, k.call(9.0)) if delta > 0.0 else up
	_place_box()

	var speed := Vector2(velocity.x, velocity.z).length()
	_gait += delta * (3.0 + speed * 3.6)
	var stride := clampf(speed / maxf(data.walk_speed, 0.1), 0.0, 1.6)
	var pos := Vector3(0.0, data.stand_height * _rise, 0.0)
	var rot := Vector3.ZERO
	# scuttling: the casing bobs and rolls over the legs
	pos.y += absf(sin(_gait)) * 0.03 * stride
	rot.z += sin(_gait) * deg_to_rad(4.5) * stride
	rot.x -= deg_to_rad(5.0) * minf(stride, 1.3)
	match state:
		State.WAKING:
			rot.z += sin(_t * 31.0) * deg_to_rad(5.0) * (1.0 - up)
		State.WINDUP:
			var w := smoothstep(0.0, 1.0, _t / maxf(_dur, 0.01))
			pos += Vector3(0.0, -0.13, 0.16) * w           # crouches back on its haunches
			rot.x += deg_to_rad(20.0) * w
		State.LUNGE:
			rot.x -= deg_to_rad(26.0)
		State.STAGGER:
			rot.z += sin(_t * 40.0) * deg_to_rad(9.0) * (1.0 - _t / STAGGER_TIME)
		State.DEAD:
			rot.z += deg_to_rad(17.0) * _dead_roll
			rot.x += deg_to_rad(-6.0)
	_shake = maxf(_shake - delta * 3.0, 0.0)
	rot.z += sin(_gait * 9.0) * deg_to_rad(3.0) * _shake
	if delta > 0.0:
		_body.position = _body.position.lerp(pos, k.call(18.0))
		_body.rotation = _body.rotation.lerp(rot, k.call(18.0))
	else:
		_body.position = pos
		_body.rotation = rot

	# Legs: gone while it sleeps, diagonal pairs swinging when it moves.
	for i in _legs.size():
		var leg := _legs[i]
		var grown := clampf(_rise * 1.25, 0.0, 1.0)
		leg.scale = Vector3.ONE * maxf(grown, 0.001)
		leg.visible = grown > 0.02
		var pair := 1.0 if i == 0 or i == 3 else -1.0
		var swing := sin(_gait) * pair * deg_to_rad(30.0) * minf(stride, 1.3)
		var splay := 0.0
		match state:
			State.WINDUP:
				swing = deg_to_rad(-24.0 if i < 2 else 20.0)
			State.LUNGE:
				swing = deg_to_rad(40.0)
			State.DEAD:
				swing = 0.0
				splay = deg_to_rad(62.0) * (-1.0 if i % 2 == 0 else 1.0)
		leg.rotation = Vector3(swing, 0.0, splay)

	# The picture and the light it throws.
	var power := 0.0
	var flash := 0.0
	match state:
		State.DORMANT:
			power = 0.8 if _lit_by_grid() else 0.0
		State.WAKING:
			power = 1.0 + 0.6 * sin(_t * 40.0)
		State.DEAD:
			power = 0.0
		State.WINDUP:
			power = 1.5
			flash = smoothstep(0.25, 1.0, _t / maxf(_dur, 0.01))
		_:
			power = 1.2 + (0.4 if _rage > 0.0 else 0.0)
	_power = lerpf(_power, power, k.call(14.0)) if delta > 0.0 else power
	_flash = lerpf(_flash, flash, k.call(25.0)) if delta > 0.0 else flash
	_screen_mat.set_shader_parameter(&"power", _power)
	_screen_mat.set_shader_parameter(&"flash", _flash)
	var lit := (_power * 0.9 + _flash * 4.0) * (1.0 if glass > 0.0 else 0.25)
	_glow.light_energy = lit
	_glow.visible = lit > 0.02

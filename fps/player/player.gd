class_name Player
extends CharacterBody3D
## First-person body: walking, sprinting, crouching, sliding, jumping, mantling
## and vaulting, leaning, stamina, and all the camera motion that sells them.
## Weapons live in the WeaponRig (a child of the camera), which reads state from
## here and writes aim_amount / aim_zoom / move_scale back. Injuries live in
## Body; everything here that an injury can change is multiplied by body.mods.

signal stamina_changed(value: float, maximum: float)
## Something the player did made a sound that carries this far, in metres.
## Only the test range's noise readout listens so far; zombie hearing will.
signal noise_made(radius: float)
signal landed(fall_speed: float)

enum Mode { NORMAL, SLIDE, MANTLE }

const LAYER_WORLD := 1
const LAYER_PLAYER := 2
const LAYER_HITBOX := 4

const RADIUS := 0.32
const STAND_HEIGHT := 1.80
const CROUCH_HEIGHT := 1.10
const EYE_STAND := 1.62
const EYE_CROUCH := 0.98
const EYE_SLIDE := 0.74
const STEP_HEIGHT := 0.38

@export_group("Speed")
@export var walk_speed := 4.3
@export var sprint_speed := 6.6
@export var crouch_speed := 2.3
## Speed multiplier while fully aimed down sights.
@export var ads_move_scale := 0.62
@export var ground_accel := 11.0
@export var ground_friction := 13.0
@export var air_accel := 2.6
@export var jump_height := 1.05
@export var gravity := 22.0

@export_group("Slide")
@export var slide_speed := 8.6
@export var slide_friction := 6.2
@export var slide_end_speed := 3.4
@export var slide_max_time := 1.15

@export_group("Mantle")
@export var mantle_min := 0.45
@export var mantle_max := 2.10
## Anything this low and thin is vaulted over instead of climbed onto.
@export var vault_max := 1.25

@export_group("Stamina")
@export var stamina_max := 100.0
@export var sprint_drain := 11.0
@export var stamina_regen := 20.0
@export var jump_cost := 7.0
@export var slide_cost := 12.0
@export var mantle_cost := 9.0

@export_group("View")
## Vertical field of view. 65 is about 97 degrees horizontal at 16:9.
@export var base_fov := 65.0

var mode := Mode.NORMAL
var crouched := false
var sprinting := false
var exhausted := false
var stamina := 100.0

# Written by WeaponRig every frame.
var aim_amount := 0.0
var aim_zoom := 1.0
var move_scale := 1.0

# Read by WeaponRig.
var bob_phase := 0.0
var bob_amount := 0.0

# Written by Body every frame: the aim wandering off, in radians (pitch, yaw).
var view_wobble := Vector2.ZERO

var head: Node3D
var camera: Camera3D
var body: Body
var rig: WeaponRig
var flashlight: SpotLight3D

var _shape: CapsuleShape3D
var _shape_node: CollisionShape3D
var _yaw := 0.0
var _pitch := 0.0
var _look_delta := Vector2.ZERO
var _eye := EYE_STAND
var _step_offset := 0.0
var _dip := 0.0
var _dip_v := 0.0
var _lean := 0.0
var _roll := 0.0
var _fov_extra := 0.0
var _recoil := Vector2.ZERO
var _recoil_v := Vector2.ZERO
var _recoil_target := Vector2.ZERO
var _recoil_recover := 10.0
var _recoil_hold := 0.0
var _jump_buffer := 0.0
var _coyote := 0.0
var _sprint_block := 0.0
var _stamina_delay := 0.0
var _slide_dir := Vector3.ZERO
var _slide_speed := 0.0
var _slide_time := 0.0
var _slide_cooldown := 0.0
var _slide_air := 0.0
var _mantle_from := Vector3.ZERO
var _mantle_to := Vector3.ZERO
var _mantle_peak := 0.0
var _mantle_t := 0.0
var _mantle_time := 0.4
var _mantle_dir := Vector3.ZERO
var _mantle_cooldown := 0.0
var _step_index := 0


func _ready() -> void:
	add_to_group(&"player")           # creatures find their prey through this
	collision_layer = LAYER_PLAYER
	collision_mask = LAYER_WORLD | LAYER_HITBOX
	floor_snap_length = 0.25
	floor_max_angle = deg_to_rad(46.0)

	_shape = CapsuleShape3D.new()
	_shape.radius = RADIUS
	_shape.height = STAND_HEIGHT
	_shape_node = CollisionShape3D.new()
	_shape_node.shape = _shape
	_shape_node.position.y = STAND_HEIGHT * 0.5
	add_child(_shape_node)

	head = Node3D.new()
	head.name = "Head"
	head.position.y = EYE_STAND
	add_child(head)
	camera = Camera3D.new()
	camera.name = "Camera"
	camera.fov = base_fov
	camera.near = 0.03
	camera.far = 400.0
	head.add_child(camera)
	camera.make_current()

	flashlight = SpotLight3D.new()
	flashlight.spot_angle = 19.0
	flashlight.spot_angle_attenuation = 0.5
	flashlight.spot_range = 45.0
	flashlight.light_color = Color(0.92, 0.96, 1.0)
	flashlight.light_energy = 22.0
	flashlight.light_volumetric_fog_energy = 0.5
	flashlight.shadow_enabled = true
	flashlight.position = Vector3(0.22, -0.20, -0.15)
	flashlight.visible = false
	camera.add_child(flashlight)

	_yaw = rotation.y
	stamina = stamina_max

	body = Body.new()
	body.name = "Body"
	add_child(body)
	body.setup(self)

	rig = WeaponRig.new()
	rig.name = "WeaponRig"
	camera.add_child(rig)
	rig.setup(self)


func _unhandled_input(event: InputEvent) -> void:
	if body.sim.dead:
		return
	if event is InputEventMouseMotion and Input.mouse_mode == Input.MOUSE_MODE_CAPTURED:
		var sens: float = Controls.mouse_sensitivity * lerpf(1.0, 1.0 / aim_zoom, aim_amount)
		_yaw -= event.relative.x * sens
		_pitch = clampf(_pitch - event.relative.y * sens, deg_to_rad(-88.0), deg_to_rad(88.0))
		_look_delta += event.relative
	elif event.is_action_pressed(&"flashlight"):
		flashlight.visible = not flashlight.visible
		Sfx.play(&"dry_fire", -10.0)


# --- queries used by the weapon rig and HUD ---------------------------------

func horizontal_speed() -> float:
	return Vector2(velocity.x, velocity.z).length()


## Mouse movement since the last call, in pixels.
func take_look_delta() -> Vector2:
	var d := _look_delta
	_look_delta = Vector2.ZERO
	return d


## Turns the player to face a yaw / pitch in radians (spawning, tests).
func set_look(yaw: float, pitch := 0.0) -> void:
	_yaw = yaw
	_pitch = pitch
	rotation.y = _yaw


## Turns the view by a small angle in radians (Body: drifting aim).
func nudge_look(yaw: float, pitch: float) -> void:
	_yaw += yaw
	_pitch = clampf(_pitch + pitch, deg_to_rad(-88.0), deg_to_rad(88.0))


## Stamina ceiling right now: a hurt torso lowers it.
func stamina_cap() -> float:
	return stamina_max * body.mods.stamina_max_scale


## Kicks the view up (degrees) and sideways; it then settles back on its own.
func add_view_kick(pitch_deg: float, yaw_deg: float, recover_deg_per_sec := 10.0) -> void:
	_recoil_target += Vector2(deg_to_rad(pitch_deg), deg_to_rad(yaw_deg))
	_recoil_target.x = minf(_recoil_target.x, deg_to_rad(14.0))
	_recoil_recover = deg_to_rad(recover_deg_per_sec)
	_recoil_hold = 0.07


## Shooting or aiming breaks a sprint for a moment.
func interrupt_sprint(seconds := 0.25) -> void:
	_sprint_block = maxf(_sprint_block, seconds)
	sprinting = false


## Every sound the player makes goes through here: the signal is for local
## listeners (HUD, tests), NoiseBus is for the world.
func make_noise(radius: float, grid_energy: float = 0.0, source: StringName = &"player") -> void:
	noise_made.emit(radius)
	NoiseBus.emit(global_position, radius, grid_energy, source)


func use_stamina(amount: float) -> void:
	stamina = maxf(stamina - amount, 0.0)
	_stamina_delay = 0.9
	if stamina <= 0.0:
		exhausted = true
	stamina_changed.emit(stamina, stamina_cap())


# --- simulation --------------------------------------------------------------

func _physics_process(delta: float) -> void:
	_jump_buffer = maxf(_jump_buffer - delta, 0.0)
	if Input.is_action_just_pressed(&"jump"):
		_jump_buffer = 0.13
	_slide_cooldown = maxf(_slide_cooldown - delta, 0.0)
	_mantle_cooldown = maxf(_mantle_cooldown - delta, 0.0)
	_sprint_block = maxf(_sprint_block - delta, 0.0)

	match mode:
		Mode.MANTLE:
			_tick_mantle(delta)
		Mode.SLIDE:
			_tick_slide(delta)
		_:
			_tick_normal(delta)

	var height := CROUCH_HEIGHT if crouched else STAND_HEIGHT
	if not is_equal_approx(_shape.height, height):
		_shape.height = height
		_shape_node.position.y = height * 0.5
	_tick_stamina(delta)


func _tick_normal(delta: float) -> void:
	var m := body.mods
	var input := Input.get_vector(&"move_left", &"move_right", &"move_forward", &"move_back")
	if m.input_lost:
		input = Vector2.ZERO
		_jump_buffer = 0.0
	var wish := global_basis * Vector3(input.x, 0.0, input.y)
	wish.y = 0.0
	var grounded := is_on_floor()
	_coyote = 0.1 if grounded else maxf(_coyote - delta, 0.0)

	var moving_forward := input.y < -0.5 and absf(input.x) < 0.75
	sprinting = (grounded and not crouched and moving_forward and not exhausted and m.can_sprint
			and _sprint_block <= 0.0 and Input.is_action_pressed(&"sprint"))

	if (grounded and sprinting and m.can_slide and Input.is_action_just_pressed(&"crouch")
			and _slide_cooldown <= 0.0 and horizontal_speed() > walk_speed * 1.1):
		_start_slide()
		return

	if grounded:
		if Input.is_action_pressed(&"crouch"):
			crouched = true
		elif crouched and _capsule_fits(global_position, STAND_HEIGHT):
			crouched = false
		var speed := walk_speed * m.walk_scale
		if sprinting:
			speed = sprint_speed * m.sprint_scale
		elif crouched:
			speed = minf(crouch_speed, speed)
		speed *= lerpf(1.0, ads_move_scale, aim_amount) * move_scale
		var hv := Vector3(velocity.x, 0.0, velocity.z)
		var rate := ground_accel if wish != Vector3.ZERO else ground_friction
		hv = hv.lerp(wish * speed, 1.0 - exp(-rate * delta))
		velocity = Vector3(hv.x, 0.0, hv.z)

		if _jump_buffer > 0.0:
			_jump_buffer = 0.0
			if _try_mantle():
				return
			if m.jump_scale > 0.0:
				if crouched and _capsule_fits(global_position, STAND_HEIGHT):
					crouched = false
				velocity.y = sqrt(2.0 * gravity * jump_height * m.jump_scale)
				_coyote = 0.0
				use_stamina(jump_cost)
				_footstep(-4.0)
				body.exert(&"jump")
		elif wish != Vector3.ZERO and _try_step_up(hv, delta):
			return
	else:
		if _jump_buffer > 0.0 and _coyote > 0.0 and m.jump_scale > 0.0:       # just walked off an edge
			_jump_buffer = 0.0
			_coyote = 0.0
			velocity.y = sqrt(2.0 * gravity * jump_height * m.jump_scale)
			use_stamina(jump_cost)
		velocity.y -= gravity * delta
		# Air control: you can add speed towards where you steer, but only up to
		# a walking pace in that direction, so jumps keep their momentum.
		if wish != Vector3.ZERO:
			var dir := wish.normalized()
			var along := Vector3(velocity.x, 0.0, velocity.z).dot(dir)
			var add := clampf(walk_speed * 0.9 - along, 0.0, air_accel * walk_speed * delta)
			velocity += dir * add
		# Holding forward into a ledge while airborne grabs it.
		if moving_forward and velocity.y < 3.5 and _try_mantle():
			return

	var fall_speed := -velocity.y
	var was_grounded := grounded
	move_and_slide()
	if not was_grounded and is_on_floor():
		_on_land(fall_speed)


## Walks up curbs and steps: if the way ahead is blocked low down but clear at
## step height, lift the body onto it. The camera catches up smoothly.
func _try_step_up(hv: Vector3, delta: float) -> bool:
	if hv.length() < 0.4:
		return false
	var ahead := hv.normalized() * maxf(hv.length() * delta, 0.08)
	var from := global_transform
	if not test_move(from, ahead):
		return false                                   # nothing in the way
	if test_move(from, Vector3.UP * STEP_HEIGHT):
		return false                                   # no headroom
	from.origin.y += STEP_HEIGHT
	if test_move(from, ahead):
		return false                                   # a wall, not a step
	from.origin += ahead
	var down := KinematicCollision3D.new()
	if not test_move(from, Vector3.DOWN * STEP_HEIGHT, down):
		return false
	if down.get_normal().y < cos(floor_max_angle):
		return false
	var before := global_position.y
	global_position = from.origin + down.get_travel()
	_step_offset -= global_position.y - before
	return true


func _on_land(fall_speed: float) -> void:
	if fall_speed < 2.0:
		return
	_dip_v -= clampf(fall_speed * 0.17, 0.3, 2.2)
	Sfx.play(&"land", clampf(-14.0 + fall_speed, -14.0, 0.0))
	make_noise(clampf(fall_speed * 1.6, 4.0, 16.0))
	landed.emit(fall_speed)
	if fall_speed > 5.0:
		body.exert(&"land")


func _start_slide() -> void:
	mode = Mode.SLIDE
	crouched = true
	sprinting = false
	var hv := Vector3(velocity.x, 0.0, velocity.z)
	_slide_dir = hv.normalized()
	_slide_speed = maxf(hv.length(), slide_speed)
	_slide_time = 0.0
	_slide_air = 0.0
	use_stamina(slide_cost * body.mods.slide_cost_scale)
	Sfx.play(&"slide", -5.0)
	make_noise(9.0)
	body.exert(&"slide")


func _tick_slide(delta: float) -> void:
	_slide_time += delta
	var input := Input.get_vector(&"move_left", &"move_right", &"move_forward", &"move_back")
	if absf(input.x) > 0.1:                              # a little steering
		_slide_dir = _slide_dir.rotated(Vector3.UP, -input.x * 0.9 * delta).normalized()
	var grounded := is_on_floor()
	if grounded:
		_slide_air = 0.0
		var downhill := Vector3.DOWN.slide(get_floor_normal()).dot(_slide_dir)
		_slide_speed += (gravity * downhill - slide_friction) * delta
		velocity = _slide_dir * _slide_speed
	else:
		_slide_air += delta
		velocity.y -= gravity * delta

	if grounded and _jump_buffer > 0.0:                  # slide-jump keeps the speed
		_jump_buffer = 0.0
		_end_slide()
		if _capsule_fits(global_position, STAND_HEIGHT):
			crouched = false
		velocity.y = sqrt(2.0 * gravity * jump_height * maxf(body.mods.jump_scale, 0.3))
		use_stamina(jump_cost)
	elif (_slide_speed < slide_end_speed or _slide_time > slide_max_time or _slide_air > 0.2
			or (_slide_time > 0.3 and not Input.is_action_pressed(&"crouch"))):
		_end_slide()

	move_and_slide()
	if is_on_wall():
		_slide_speed = minf(_slide_speed, horizontal_speed())


func _end_slide() -> void:
	mode = Mode.NORMAL
	_slide_cooldown = 0.45
	if not Input.is_action_pressed(&"crouch") and _capsule_fits(global_position, STAND_HEIGHT):
		crouched = false


# --- mantling ----------------------------------------------------------------

## Looks for something climbable straight ahead and, if found, starts the move.
func _try_mantle() -> bool:
	if _mantle_cooldown > 0.0:
		return false
	var fwd := -global_basis.z
	fwd.y = 0.0
	fwd = fwd.normalized()
	var from := global_transform
	from.origin.y += 0.3                                 # ignore curbs and floor bumps
	var wall_hit := KinematicCollision3D.new()
	if not test_move(from, fwd * 0.6, wall_hit):
		return false
	var n := wall_hit.get_normal()
	if absf(n.y) > 0.5 or n.dot(fwd) > -0.45:
		return false                                     # must be faced roughly head on
	var wall := wall_hit.get_position()
	var feet := global_position.y
	var probe_top := feet + mantle_max + 0.3
	# A hurt leg cannot push as high.
	var reach := mantle_max
	if body.mods.mantle_max_override > 0.0:
		reach = minf(reach, body.mods.mantle_max_override)

	var near := _surface_below(wall + fwd * 0.08, probe_top, mantle_max + 1.0)
	if is_nan(near):
		return false
	var h := near - feet
	if h < mantle_min or h > reach:
		return false
	var far := _surface_below(wall + fwd * 0.55, probe_top, mantle_max + 4.0)
	var target := wall + fwd * 0.48
	var vault := false
	if not is_nan(far) and far >= near - 0.25:
		target.y = maxf(near, far)                       # a ledge: climb onto it
	elif h <= vault_max and not is_nan(far):
		vault = true                                     # thin and low: go over it
		target = wall + fwd * 0.95
		var ground := _surface_below(target, near + 0.3, mantle_max + 4.0)
		if is_nan(ground):
			return false
		target.y = ground
	else:
		return false

	# The space above the obstacle must be open (this also rejects solid walls,
	# whose collision is a hollow shell the probes above can start inside).
	var space := get_world_3d().direct_space_state
	var clear_y := near + 0.25
	for lift: float in [0.0, 0.7]:
		var a := Vector3(global_position.x, clear_y + lift, global_position.z)
		var b := Vector3(target.x, clear_y + lift, target.z)
		var q := PhysicsRayQueryParameters3D.create(a, b, LAYER_WORLD, [get_rid()])
		if not space.intersect_ray(q).is_empty():
			return false
	# Find where a crouched body actually comes to rest there: uneven ground
	# (a lumpy lawn) holds a wide capsule higher than the thin probe found.
	target.y = _settle(target + Vector3.UP * 0.35, CROUCH_HEIGHT, 0.7)
	if is_nan(target.y):
		return false

	mode = Mode.MANTLE
	_mantle_from = global_position
	_mantle_to = target
	_mantle_peak = maxf(near, target.y) + (0.12 if vault else 0.0)
	_mantle_dir = fwd
	_mantle_t = 0.0
	_mantle_time = lerpf(0.30, 0.62, inverse_lerp(mantle_min, mantle_max, h)) * (1.15 if vault else 1.0) \
			* body.mods.mantle_time_scale
	velocity = Vector3.ZERO
	crouched = true
	sprinting = false
	use_stamina(mantle_cost)
	Sfx.play(&"mantle", -4.0)
	make_noise(7.0)
	body.exert(&"mantle")
	_dip_v -= 0.5
	return true


func _tick_mantle(delta: float) -> void:
	_mantle_t = minf(_mantle_t + delta / _mantle_time, 1.0)
	var t := _mantle_t
	var rise := 1.0 - pow(1.0 - clampf(t / 0.6, 0.0, 1.0), 2.0)      # up first...
	var travel := smoothstep(0.3, 1.0, t)                             # ...then across
	var p := _mantle_from.lerp(_mantle_to, travel)
	p.y = lerpf(_mantle_from.y, _mantle_peak, rise)
	p.y = lerpf(p.y, _mantle_to.y, smoothstep(0.6, 1.0, t))
	global_position = p
	if t >= 1.0:
		mode = Mode.NORMAL
		_mantle_cooldown = 0.25
		velocity = _mantle_dir * walk_speed * 0.6
		if not Input.is_action_pressed(&"crouch") and _capsule_fits(global_position, STAND_HEIGHT):
			crouched = false


## Height of the first walkable surface under a point, or NAN. A small sphere is
## swept down rather than a ray so that it cannot slip between fence pickets.
func _surface_below(point: Vector3, from_y: float, max_drop: float) -> float:
	var sphere := SphereShape3D.new()
	sphere.radius = 0.10
	var q := PhysicsShapeQueryParameters3D.new()
	q.shape = sphere
	q.transform = Transform3D(Basis.IDENTITY, Vector3(point.x, from_y, point.z))
	q.motion = Vector3.DOWN * max_drop
	q.collision_mask = LAYER_WORLD
	q.exclude = [get_rid()]
	var r := get_world_3d().direct_space_state.cast_motion(q)
	if r[0] >= 1.0 or r[1] <= 0.0:
		return NAN
	return from_y - max_drop * r[1] - sphere.radius


## Lowers a capsule of the given height from `feet` by up to max_drop and
## returns the height its feet stop at, or NAN if it does not fit at the start.
func _settle(feet: Vector3, height: float, max_drop: float) -> float:
	var capsule := CapsuleShape3D.new()
	capsule.radius = RADIUS
	capsule.height = height
	var q := PhysicsShapeQueryParameters3D.new()
	q.shape = capsule
	q.transform = Transform3D(Basis.IDENTITY, feet + Vector3.UP * (height * 0.5))
	q.motion = Vector3.DOWN * max_drop
	q.collision_mask = LAYER_WORLD | LAYER_HITBOX
	q.exclude = [get_rid()]
	var space := get_world_3d().direct_space_state
	if not space.intersect_shape(q, 1).is_empty():
		return NAN
	return feet.y - max_drop * space.cast_motion(q)[0]


func _capsule_fits(feet: Vector3, height: float) -> bool:
	var capsule := CapsuleShape3D.new()
	capsule.radius = RADIUS - 0.03
	capsule.height = height - 0.06
	var q := PhysicsShapeQueryParameters3D.new()
	q.shape = capsule
	q.transform = Transform3D(Basis.IDENTITY, feet + Vector3.UP * (height * 0.5 + 0.03))
	q.collision_mask = LAYER_WORLD | LAYER_HITBOX
	q.exclude = [get_rid()]
	return get_world_3d().direct_space_state.intersect_shape(q, 1).is_empty()


func _tick_stamina(delta: float) -> void:
	var m := body.mods
	var cap := stamina_cap()
	var before := stamina
	if sprinting and horizontal_speed() > walk_speed * m.walk_scale:
		stamina = maxf(stamina - sprint_drain * m.sprint_drain_scale * delta, 0.0)
		_stamina_delay = 0.8
		if stamina <= 0.0:
			exhausted = true
	elif _stamina_delay > 0.0:
		_stamina_delay -= delta
	elif stamina < cap:
		stamina = minf(stamina + stamina_regen * m.stamina_regen_scale * (1.3 if crouched else 1.0) * delta, cap)
	stamina = minf(stamina, cap)
	if exhausted and stamina >= cap * 0.25:
		exhausted = false
	if not is_equal_approx(before, stamina):
		stamina_changed.emit(stamina, cap)


func _footstep(volume_db: float) -> void:
	_step_index = (_step_index + randi_range(1, 3)) % 4
	Sfx.play(StringName("footstep_%d" % (_step_index + 1)), volume_db, 0.08)


# --- camera ------------------------------------------------------------------

func _process(delta: float) -> void:
	# Turning is applied every rendered frame, not every physics tick, so the
	# view never lags the mouse. A long hitch must not blow up the springs.
	rotation.y = _yaw
	delta = minf(delta, 0.05)
	var k := func(rate: float) -> float: return 1.0 - exp(-rate * delta)
	var hs := horizontal_speed()
	var walking := mode == Mode.NORMAL and is_on_floor() and hs > 0.7

	var eye := EYE_STAND
	if body.sim.collapsing or body.sim.dead:
		eye = EYE_SLIDE                      # on the knees
	elif mode == Mode.SLIDE:
		eye = EYE_SLIDE
	elif crouched:
		eye = EYE_CROUCH
	_eye = lerpf(_eye, eye, k.call(13.0))
	_step_offset = lerpf(_step_offset, 0.0, k.call(14.0))

	# Bob. One full phase turn is two steps; a footstep lands each half turn.
	bob_amount = lerpf(bob_amount, 1.0 if walking else 0.0, k.call(9.0))
	if walking:
		var before := int(floor(bob_phase / PI))
		bob_phase += delta * PI * (1.9 + hs * 0.32)
		if int(floor(bob_phase / PI)) != before:
			if sprinting:
				_footstep(-3.0)
				make_noise(12.0)
			elif crouched:
				_footstep(-16.0)
				make_noise(2.0)
			else:
				_footstep(-8.0)
				make_noise(6.0)
	var stride := clampf(hs / walk_speed, 0.4, 1.7) * bob_amount * lerpf(1.0, 0.25, aim_amount)
	var bob := Vector3(cos(bob_phase) * 0.013, sin(bob_phase * 2.0) * 0.019, 0.0) * stride
	# A limp: every other step the body drops onto the bad leg and tips over it.
	var limp := body.mods.limp * bob_amount
	var bad_step := maxf(sin(bob_phase), 0.0)
	bob.y -= bad_step * bad_step * 0.045 * limp

	# Landing dip: a damped spring kicked by _on_land.
	_dip_v += (-_dip * 190.0 - _dip_v * 20.0) * delta
	_dip += _dip_v * delta

	# Lean, shortened when a wall is in the way.
	var lean_in := 0.0
	if mode == Mode.NORMAL and is_on_floor() and not sprinting and not body.mods.input_lost:
		lean_in = Input.get_axis(&"lean_left", &"lean_right")
	if lean_in != 0.0:
		var from := global_position + Vector3.UP * _eye
		var q := PhysicsRayQueryParameters3D.create(from, from + global_basis.x * lean_in * 0.6,
				LAYER_WORLD, [get_rid()])
		var hit := get_world_3d().direct_space_state.intersect_ray(q)
		if not hit.is_empty():
			lean_in *= clampf((from.distance_to(hit.position) - 0.22) / 0.38, 0.0, 1.0)
	_lean = lerpf(_lean, lean_in, k.call(11.0))

	# Recoil: the view chases a kick target that drains back to zero.
	_recoil_hold -= delta
	if _recoil_hold <= 0.0:
		_recoil_target = _recoil_target.move_toward(Vector2.ZERO, _recoil_recover * delta)
	_recoil_v += ((_recoil_target - _recoil) * 320.0 - _recoil_v * 30.0) * delta
	_recoil += _recoil_v * delta

	var strafe := velocity.dot(global_basis.x) / walk_speed
	var roll := -strafe * deg_to_rad(1.3) - _lean * deg_to_rad(9.0) - bad_step * deg_to_rad(2.6) * limp
	if mode == Mode.SLIDE:
		roll -= deg_to_rad(4.5)
	elif mode == Mode.MANTLE:
		roll += deg_to_rad(3.0) * sin(_mantle_t * PI)
	_roll = lerpf(_roll, roll, k.call(10.0))

	head.position = Vector3(_lean * 0.34, _eye + _step_offset, 0.0)
	head.rotation = Vector3(clampf(_pitch + _recoil.x + view_wobble.x, deg_to_rad(-89.0), deg_to_rad(89.0)),
			_recoil.y + view_wobble.y, 0.0)
	camera.position = bob + Vector3(0.0, _dip, 0.0)
	camera.rotation = Vector3(0.0, 0.0, _roll)

	var extra := 0.0
	if mode == Mode.SLIDE:
		extra = 6.0
	elif sprinting:
		extra = 4.0
	_fov_extra = lerpf(_fov_extra, extra, k.call(7.0))
	var zoom := lerpf(1.0, aim_zoom, aim_amount)
	camera.fov = rad_to_deg(2.0 * atan(tan(deg_to_rad(base_fov) * 0.5) / zoom)) + _fov_extra

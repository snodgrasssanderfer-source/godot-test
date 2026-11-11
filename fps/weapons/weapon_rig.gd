class_name WeaponRig
extends Node3D
## The player's hands. A child of the camera that owns the carried weapons,
## runs the state machine for firing, pumping, reloading, swinging and
## switching, and poses the current weapon every frame from layered procedural
## motion (there are no keyframed animations).
##
## Shots are rays from the centre of the camera, so they land where the
## crosshair points; the model's recoil is for show.

signal weapon_changed(weapon: Weapon)
signal ammo_changed(weapon: Weapon)
## A shot or swing hurt something. damage is the total dealt.
signal hit_confirmed(head: bool, killed: bool, damage: float)

enum State { READY, COOLDOWN, PUMP, RELOAD, HOLSTER, EQUIP, SWING }
enum Shell { START, INSERT, END }

const LOADOUT: Array[WeaponData] = [
	preload("res://weapons/data/pistol.tres"),
	preload("res://weapons/data/shotgun.tres"),
	preload("res://weapons/data/pipe.tres"),
]
const RAY_MASK := Player.LAYER_WORLD | Player.LAYER_HITBOX
## A fire press this long before the weapon is ready still counts.
const FIRE_BUFFER := 0.2
## Model recoil spring. KICK_IMPULSE turns "peak displacement" into the
## starting velocity that reaches it with this stiffness and damping.
const KICK_STIFFNESS := 240.0
const KICK_DAMPING := 22.0
const KICK_IMPULSE := 34.0

var player: Player
var fx: Fx
var weapons: Array[Weapon] = []
var current: Weapon
var state := State.EQUIP
## Reserve ammo never runs out (the shooting range turns this on).
var infinite_reserve := false
## Total spread half-angle right now, degrees. The crosshair draws this.
var spread := 0.0
## 0 = hip, 1 = fully aimed down sights (eased).
var aim := 0.0

var _holder: Node3D
var _index := 0
var _pending := -1
var _t := 0.0
var _dur := 0.0
var _rate := 1.0                   # how fast this state's clock runs (injuries slow the hands)
var _events := 0                   # one bit per sound / effect already done in this state
var _fire_buffer := 0.0
var _reload_buffer := 0.0
var _bloom := 0.0
var _centre_spread := 0.0
var _pellet_cone := 0.0
var _aim_linear := 0.0
var _sprint_lock := 0.0
var _sprint_pose := 0.0
var _reload_empty := false
var _shell_step := Shell.START
var _shell_stop := false
var _pump_ejects := true
var _slide_kick := 0.0
var _swing_scale := 1.0

var _look_vel := Vector2.ZERO
var _sway := Vector2.ZERO
var _breath := 0.0
var _lift := 0.0
var _lift_v := 0.0
var _strafe := 0.0
var _kick := Vector3.ZERO          # x = back (m), y = muzzle rise (rad), z = roll (rad)
var _kick_v := Vector3.ZERO
var _act_pos := Vector3.ZERO
var _act_rot := Vector3.ZERO       # degrees
var _wall := 0.0


func setup(owner_player: Player) -> void:
	player = owner_player
	_holder = Node3D.new()
	_holder.name = "Holder"
	add_child(_holder)
	fx = Fx.new()
	fx.name = "Fx"
	add_child(fx)
	for data in LOADOUT:
		var w := Weapon.new()
		_holder.add_child(w)
		w.setup(data)
		w.visible = false
		weapons.append(w)
	player.landed.connect(_on_landed)
	_equip(0)


func _unhandled_input(event: InputEvent) -> void:
	if current == null:
		return
	if event.is_action_pressed(&"fire"):
		_fire_buffer = FIRE_BUFFER
	elif event.is_action_pressed(&"reload"):
		_reload_buffer = 0.3
	elif event.is_action_pressed(&"weapon_1"):
		select(0)
	elif event.is_action_pressed(&"weapon_2"):
		select(1)
	elif event.is_action_pressed(&"weapon_3"):
		select(2)
	elif event.is_action_pressed(&"weapon_next"):
		select(posmod(_target_index() + 1, weapons.size()))
	elif event.is_action_pressed(&"weapon_prev"):
		select(posmod(_target_index() - 1, weapons.size()))


## Switches to the weapon in a slot as soon as the current action allows.
func select(index: int) -> void:
	if index < 0 or index >= weapons.size():
		return
	_pending = -1 if index == _index else index


func _target_index() -> int:
	return _pending if _pending >= 0 else _index


func is_reloading() -> bool:
	return state == State.RELOAD


func _process(delta: float) -> void:
	if current == null:
		return
	delta = minf(delta, 0.05)
	var d := current.data
	_fire_buffer = maxf(_fire_buffer - delta, 0.0)
	_reload_buffer = maxf(_reload_buffer - delta, 0.0)
	if player.body.mods.input_lost:        # staggering: the hands do nothing
		_fire_buffer = 0.0
		_reload_buffer = 0.0
	if player.sprinting:
		_sprint_lock = d.sprint_out_time
	else:
		_sprint_lock = maxf(_sprint_lock - delta, 0.0)

	_tick_state(delta)
	_tick_aim(delta)
	_tick_spread(delta)
	_tick_parts(delta)
	_tick_pose(delta)

	d = current.data
	player.aim_amount = aim
	player.aim_zoom = d.aim_zoom
	player.move_scale = d.move_scale


# --- state machine -------------------------------------------------------------

## `slowdown` stretches the whole state: its clock runs that many times slower,
## so every time written into the state (durations, sound cues, poses) stays
## in the weapon's own units.
func _enter(next: State, duration := 0.0, slowdown := 1.0) -> void:
	state = next
	_t = 0.0
	_dur = duration
	_rate = 1.0 / maxf(slowdown, 0.01)
	_events = 0


## Pump time factor from the body: the support hand works the action; a long
## gun that cannot be shouldered is slower still.
func _pump_slowdown() -> float:
	var m := player.body.mods
	return m.pump_scale * (m.long_pump_scale if current.data.long_gun else 1.0)


## True the first time it is called for a given bit after `when` has passed.
func _once(bit: int, when: float) -> bool:
	if _t < when or _events & bit:
		return false
	_events |= bit
	return true


func _switch_wanted() -> bool:
	return _pending >= 0 and _pending != _index


func _tick_state(delta: float) -> void:
	_t += delta * _rate
	var d := current.data
	match state:
		State.READY:
			_act()
		State.COOLDOWN:
			if _t >= _dur:
				_enter(State.READY)
				_act()
		State.PUMP:
			if _once(1, _dur * 0.22):
				Sfx.play(&"pump_back", -3.0)
				_kick_v += Vector3(0.012, 0.0, 0.0) * KICK_IMPULSE
				if _pump_ejects:
					_eject_shell()
			if _once(2, _dur * 0.60):
				Sfx.play(&"pump_forward", -3.0)
				_kick_v -= Vector3(0.008, 0.0, 0.0) * KICK_IMPULSE
			if _t >= _dur:
				_enter(State.READY)
				_act()
		State.RELOAD:
			if d.pump_time > 0.0:
				_tick_shell_reload()
			else:
				_tick_mag_reload()
		State.HOLSTER:
			if _t >= _dur:
				_equip(_pending if _pending >= 0 else _index)
		State.EQUIP:
			if _switch_wanted():
				_enter(State.HOLSTER, d.holster_time * 0.5)
			elif _t >= _dur:
				_enter(State.READY)
				_act()
		State.SWING:
			if _once(1, d.hit_time * _swing_scale):
				_melee_hit()
			if _t >= _dur:
				_enter(State.READY)
				_act()


## Starts whatever the player has asked for, if the weapon is free to do it.
func _act() -> void:
	var d := current.data
	if _switch_wanted():
		_enter(State.HOLSTER, d.holster_time)
		return
	_pending = -1
	if _fire_buffer > 0.0:
		if _sprint_lock > 0.0:
			# Still coming up out of a sprint: stop sprinting and hold the shot.
			player.interrupt_sprint(0.3)
			_fire_buffer = maxf(_fire_buffer, 0.05)
			return
		_fire_buffer = 0.0
		if d.kind == WeaponData.Kind.MELEE:
			_swing()
		elif current.mag > 0:
			_fire()
		elif _can_reload():
			_start_reload()
		else:
			Sfx.play(&"dry_fire", -4.0)
			_enter(State.COOLDOWN, 0.2)
	elif _reload_buffer > 0.0 and _can_reload():
		_start_reload()


func _equip(index: int) -> void:
	if current:
		current.visible = false
	_index = index
	_pending = -1
	current = weapons[index]
	current.visible = true
	_aim_linear = 0.0
	_bloom = 0.0
	Sfx.play(&"equip", -6.0)
	_enter(State.EQUIP, current.data.equip_time)
	weapon_changed.emit(current)
	ammo_changed.emit(current)


# --- shooting ------------------------------------------------------------------

## A random direction inside a cone around the basis' forward (-Z) axis.
static func _in_cone(basis: Basis, half_angle_deg: float) -> Vector3:
	var r := tan(deg_to_rad(half_angle_deg)) * sqrt(randf())
	var a := randf() * TAU
	return (basis * Vector3(cos(a) * r, sin(a) * r, -1.0)).normalized()


## The thing that takes damage for a collider: the nearest ancestor (or the
## collider itself) with a take_hit() method.
static func _target_of(collider: Object) -> Node:
	var n := collider as Node
	while n != null and not n.has_method(&"take_hit"):
		n = n.get_parent()
	return n


func _fire() -> void:
	var w := current
	var d := w.data
	w.mag -= 1
	var cam := player.camera.global_transform
	var origin := cam.origin
	var from := w.muzzle.global_position if w.muzzle else origin
	var space := player.get_world_3d().direct_space_state
	var centre := _in_cone(cam.basis, _centre_spread)
	var pellet_basis := Basis.looking_at(centre, cam.basis.y)
	var hurt := false
	var head := false
	var killed := false
	var total := 0.0
	for i in d.pellets:
		var dir := centre if d.pellets == 1 else _in_cone(pellet_basis, _pellet_cone)
		var q := PhysicsRayQueryParameters3D.create(origin, origin + dir * d.max_range, RAY_MASK,
				[player.get_rid()])
		var hit := space.intersect_ray(q)
		if hit.is_empty():
			fx.tracer(from, origin + dir * 150.0)
			continue
		var at: Vector3 = hit.position
		fx.tracer(from, at)
		var target := _target_of(hit.collider)
		if target == null:
			fx.impact(at, hit.normal, hit.collider, false, true, i < 2)
			continue
		fx.impact(at, hit.normal, hit.collider, true, false, i < 2)
		var result: Dictionary = target.take_hit(d.damage_at(origin.distance_to(at)), at, dir, d)
		if not result.is_empty():
			hurt = true
			head = head or result.get("head", false)
			killed = killed or result.get("killed", false)
			total += result.get("damage", 0.0)
	if hurt:
		hit_confirmed.emit(head, killed, total)

	var m := player.body.mods
	var steady := lerpf(1.0, d.aim_kick_scale, aim) * (0.85 if player.crouched else 1.0)
	player.add_view_kick(d.kick_pitch * m.kick_scale * steady, randf_range(-d.kick_yaw, d.kick_yaw) * steady,
			d.kick_recover * m.kick_recover_scale)
	_kick_v += Vector3(d.model_kick_back, deg_to_rad(d.model_kick_up), deg_to_rad(randf_range(-1.5, 1.5))) \
			* KICK_IMPULSE * lerpf(1.0, 0.6, aim)
	_bloom = minf(_bloom + d.bloom_per_shot, d.bloom_max)
	Sfx.play(d.fire_sound, d.fire_volume, 0.04)
	# Aimed, the muzzle sits on the line of sight: keep the flash off the target.
	fx.muzzle_flash(w.muzzle, (1.7 if d.kind == WeaponData.Kind.SHOTGUN else 1.0) * lerpf(1.0, 0.5, aim))
	player.make_noise(d.noise_radius, d.grid_energy, d.id)
	player.interrupt_sprint(0.3)
	if d.pump_time > 0.0:
		_pump_ejects = true
		_enter(State.PUMP, d.pump_time, _pump_slowdown())
	else:
		_slide_kick = 1.0
		_eject_shell()
		w.slide_locked = w.mag == 0
		_enter(State.COOLDOWN, d.fire_interval)
	ammo_changed.emit(w)


func _eject_shell() -> void:
	if current.eject == null:
		return
	var b := global_basis
	var v := b.x * randf_range(1.6, 2.4) + b.y * randf_range(1.3, 2.0) + b.z * randf_range(0.0, 0.5)
	fx.shell(current.eject.global_transform, v + player.velocity, current.data.kind)


func _swing() -> void:
	var d := current.data
	_swing_scale = 1.5 if player.exhausted else 1.0
	player.use_stamina(d.swing_stamina)
	player.interrupt_sprint(0.4)
	player.body.exert(&"swing")
	Sfx.play(&"pipe_swing", -3.0)
	_enter(State.SWING, d.fire_interval * _swing_scale)


## The moment a swing connects: a fan of rays across the arc. Each target in
## reach is hit once; if there is none, the nearest wall takes the blow.
func _melee_hit() -> void:
	var d := current.data
	var cam := player.camera.global_transform
	var space := player.get_world_3d().direct_space_state
	var struck := {}
	var wall := {}
	var head := false
	var killed := false
	var total := 0.0
	const RAYS := 7
	for i in RAYS:
		var yaw := deg_to_rad(lerpf(d.melee_arc, -d.melee_arc, i / float(RAYS - 1)))
		var dir := cam.basis * Basis(Vector3.UP, yaw) * Vector3.FORWARD
		var q := PhysicsRayQueryParameters3D.create(cam.origin, cam.origin + dir * d.max_range, RAY_MASK,
				[player.get_rid()])
		var hit := space.intersect_ray(q)
		if hit.is_empty():
			continue
		var target := _target_of(hit.collider)
		if target == null:
			if wall.is_empty() or cam.origin.distance_to(hit.position) < cam.origin.distance_to(wall.position):
				wall = hit
		elif not struck.has(target):
			struck[target] = true
			fx.impact(hit.position, hit.normal, hit.collider, true, false)
			var result: Dictionary = target.take_hit(d.damage, hit.position, -cam.basis.z, d)
			if not result.is_empty():
				head = head or result.get("head", false)
				killed = killed or result.get("killed", false)
				total += result.get("damage", 0.0)
	if not struck.is_empty():
		Sfx.play(&"pipe_hit", -1.0)
		player.add_view_kick(-1.2, 0.8, 14.0)
		player.make_noise(d.noise_radius, d.grid_energy, d.id)
		if total > 0.0:
			hit_confirmed.emit(head, killed, total)
	elif not wall.is_empty():
		fx.impact(wall.position, wall.normal, wall.collider, false, false)
		Sfx.play(&"pipe_hit", -5.0)
		player.add_view_kick(-0.8, 0.5, 14.0)
		player.make_noise(d.noise_radius, d.grid_energy, d.id)
	else:
		player.make_noise(3.0)


# --- reloading -----------------------------------------------------------------

func _can_reload() -> bool:
	var d := current.data
	return (d.kind != WeaponData.Kind.MELEE and current.mag < d.mag_size
			and (current.reserve > 0 or infinite_reserve))


func _start_reload() -> void:
	var d := current.data
	_reload_buffer = 0.0
	_reload_empty = current.mag == 0
	var slowdown := player.body.mods.reload_scale
	player.body.exert(&"reload")
	if d.pump_time > 0.0:
		_enter(State.RELOAD, 0.0, slowdown)
		_shell_step = Shell.START
		_shell_stop = false
	else:
		_enter(State.RELOAD, d.reload_empty_time if _reload_empty else d.reload_time, slowdown)


func _load(rounds: int) -> void:
	var take := rounds if infinite_reserve else mini(rounds, current.reserve)
	current.mag += take
	if not infinite_reserve:
		current.reserve -= take
	ammo_changed.emit(current)


## Magazine reload. The rounds count from the moment the new magazine goes in;
## after that, firing cuts the rest of the animation short.
func _tick_mag_reload() -> void:
	var d := current.data
	if _switch_wanted():
		_enter(State.HOLSTER, d.holster_time)
		return
	if _once(1, d.reload_time * 0.20):
		Sfx.play(&"mag_out", -3.0)
	if _once(2, d.reload_time * 0.60):
		if randf() < player.body.mods.mag_drop_chance:
			# A useless support hand fumbles the magazine: start over.
			Sfx.play(&"mag_out", -1.0)
			_kick_v += Vector3(0.0, deg_to_rad(-3.0), deg_to_rad(4.0)) * KICK_IMPULSE
			_enter(State.RELOAD, _dur, 1.0 / _rate)
			return
		_load(d.mag_size - current.mag)
		Sfx.play(&"mag_in", -2.0)
		_kick_v += Vector3(0.0, deg_to_rad(2.5), 0.0) * KICK_IMPULSE
	if _reload_empty and _once(4, d.reload_empty_time - 0.24):
		Sfx.play(&"slide_rack", -3.0)
		current.slide_locked = false
		_slide_kick = 1.0
		_kick_v += Vector3(0.012, 0.0, deg_to_rad(-3.0)) * KICK_IMPULSE
	var loaded := _events & 2 != 0
	if _t >= _dur or (loaded and _fire_buffer > 0.0):
		current.slide_locked = false
		_enter(State.READY)
		_act()


## Shell-by-shell reload: lift, one shell at a time, lower. Firing (or
## switching) stops after the shell in hand. Loading from empty ends with a
## pump to chamber the first shell.
func _tick_shell_reload() -> void:
	var d := current.data
	if _fire_buffer > 0.0:
		_shell_stop = true
		_fire_buffer = maxf(_fire_buffer, 0.05)        # keep the shot for when the gun is back up
	if _switch_wanted():
		_shell_stop = true
	match _shell_step:
		Shell.START:
			if _t >= d.shell_start_time:
				_shell_goto(Shell.END if _shell_stop and current.mag > 0 else Shell.INSERT)
		Shell.INSERT:
			if _once(1, d.shell_time * 0.55):
				_load(1)
				Sfx.play(&"shell_insert", -3.0)
				_kick_v += Vector3(0.006, deg_to_rad(1.5), 0.0) * KICK_IMPULSE
			if _t >= d.shell_time:
				var more := current.mag < d.mag_size and (current.reserve > 0 or infinite_reserve)
				_shell_goto(Shell.INSERT if more and not _shell_stop else Shell.END)
		Shell.END:
			if _t >= d.shell_end_time:
				if _reload_empty and current.mag > 0:
					_pump_ejects = false
					_enter(State.PUMP, d.pump_time, _pump_slowdown())
				else:
					_enter(State.READY)
					_act()


func _shell_goto(step: Shell) -> void:
	_shell_step = step
	_t = 0.0
	_events = 0


# --- aim, spread, moving parts -------------------------------------------------

func _tick_aim(delta: float) -> void:
	var d := current.data
	var m := player.body.mods
	var want := (Input.is_action_pressed(&"aim") and d.kind != WeaponData.Kind.MELEE and not m.input_lost
			and state in [State.READY, State.COOLDOWN, State.PUMP])
	if want:
		player.interrupt_sprint(0.15)
		if _sprint_lock > 0.0:
			want = false
	_aim_linear = move_toward(_aim_linear, 1.0 if want else 0.0, delta / maxf(d.aim_time * m.aim_time_scale, 0.01))
	aim = smoothstep(0.0, 1.0, _aim_linear)


func _tick_spread(delta: float) -> void:
	var d := current.data
	_bloom = move_toward(_bloom, 0.0, d.bloom_recovery * delta)
	var hip := d.hip_spread + _bloom
	hip += d.move_spread * clampf(player.horizontal_speed() / player.walk_speed, 0.0, 1.6)
	if not player.is_on_floor():
		hip += d.air_spread
	elif player.crouched:
		hip *= 0.8
	var aimed := d.aim_spread * (player.body.mods.long_aim_spread_scale if d.long_gun else 1.0)
	_centre_spread = lerpf(hip, aimed, aim)
	_pellet_cone = lerpf(d.pellet_spread, d.pellet_spread_aim, aim)
	spread = _centre_spread + _pellet_cone


func _tick_parts(delta: float) -> void:
	var w := current
	var d := w.data
	_slide_kick = maxf(_slide_kick - delta / 0.07, 0.0)
	w.set_slide(1.0 if w.slide_locked else _slide_kick)
	var pump := 0.0
	if state == State.PUMP:
		var f := _t / _dur
		pump = smoothstep(0.12, 0.28, f) * (1.0 - smoothstep(0.52, 0.68, f))
	w.set_pump(pump)
	var mag_out := 0.0
	if state == State.RELOAD and d.pump_time <= 0.0:
		var base := d.reload_time
		mag_out = smoothstep(base * 0.20, base * 0.20 + 0.12, _t) * (1.0 - smoothstep(base * 0.46, base * 0.60, _t))
	w.set_mag_out(mag_out)


# --- pose ----------------------------------------------------------------------

func _on_landed(fall_speed: float) -> void:
	_lift_v -= clampf(fall_speed * 0.03, 0.08, 0.4)


## Where the current action wants the weapon, as an offset from its rest pose.
func _action_pose() -> Array[Vector3]:
	var d := current.data
	var pos := Vector3.ZERO
	var rot := Vector3.ZERO
	match state:
		State.RELOAD:
			var r := 0.0
			if d.pump_time > 0.0:
				match _shell_step:
					Shell.START:
						r = smoothstep(0.0, 1.0, _t / d.shell_start_time)
					Shell.INSERT:
						r = 1.0
						var push := sin(clampf(_t / d.shell_time, 0.0, 1.0) * PI)
						pos += Vector3(0.0, 0.010, -0.018) * push
					Shell.END:
						r = 1.0 - smoothstep(0.0, 1.0, _t / d.shell_end_time)
			else:
				r = smoothstep(0.0, 0.22, _t) * (1.0 - smoothstep(d.reload_time * 0.70, d.reload_time * 0.95, _t))
				if _reload_empty:        # tip the gun over for the slide rack
					var rack := smoothstep(d.reload_time * 0.9, d.reload_empty_time - 0.22, _t) \
							* (1.0 - smoothstep(d.reload_empty_time - 0.14, d.reload_empty_time, _t))
					pos += Vector3(-0.01, 0.01, 0.03) * rack
					rot += Vector3(6.0, 8.0, -22.0) * rack
			pos += d.reload_position * r
			rot += d.reload_rotation * r
		State.HOLSTER, State.EQUIP:
			var f := clampf(_t / maxf(_dur, 0.01), 0.0, 1.0)
			var h := f * f if state == State.HOLSTER else (1.0 - f) * (1.0 - f)
			pos = Vector3(0.02, -0.24, 0.07) * h
			rot = Vector3(-42.0, 14.0, 0.0) * h
		State.SWING:
			var t := _t / _swing_scale
			var hit := d.hit_time
			var wind_pos := Vector3(0.06, 0.07, 0.07)
			var wind_rot := Vector3(24.0, -22.0, -18.0)
			var strike_pos := Vector3(-0.24, -0.06, -0.16)
			var strike_rot := Vector3(-92.0, 46.0, 24.0)
			if t < hit * 0.6:
				var f := smoothstep(0.0, 1.0, t / (hit * 0.6))
				pos = wind_pos * f
				rot = wind_rot * f
			elif t < hit * 1.25:
				var f := ease(inverse_lerp(hit * 0.6, hit * 1.25, t), 0.6)
				pos = wind_pos.lerp(strike_pos, f)
				rot = wind_rot.lerp(strike_rot, f)
			else:
				var f := smoothstep(hit * 1.25, d.fire_interval, t)
				pos = strike_pos * (1.0 - f)
				rot = strike_rot * (1.0 - f)
	return [pos, rot]


func _tick_pose(delta: float) -> void:
	var d := current.data
	var k := func(rate: float) -> float: return 1.0 - exp(-rate * delta)
	var walk := player.walk_speed
	var loose := 1.0 - aim * 0.85          # how much hip-fire looseness survives aiming

	# Rest pose: hip, or sights on the centre of the screen.
	var s := current.sight_offset
	var aim_pos := Vector3(-s.x, -s.y, -d.aim_distance - s.z) + d.aim_offset
	var pos := d.hip_position.lerp(aim_pos, aim)
	var rot := d.hip_rotation.lerp(d.aim_rotation, aim) * (PI / 180.0)

	# Sprint: lowered and turned across the body.
	var sprint_on := player.sprinting and state in [State.READY, State.COOLDOWN, State.EQUIP]
	_sprint_pose = move_toward(_sprint_pose, 1.0 if sprint_on else 0.0,
			delta / (0.22 if sprint_on else maxf(d.sprint_out_time, 0.05)))
	var sprint := smoothstep(0.0, 1.0, _sprint_pose)
	pos += d.sprint_position * sprint
	rot += d.sprint_rotation * (PI / 180.0) * sprint

	# Sway: the weapon trails behind the view when it turns.
	var look := player.take_look_delta() * Controls.mouse_sensitivity      # radians this frame
	_look_vel = _look_vel.lerp(look / maxf(delta, 0.001), k.call(20.0))
	var sway := (_look_vel * 0.016).limit_length(deg_to_rad(4.5))
	_sway = _sway.lerp(sway, k.call(10.0))
	rot += Vector3(_sway.y, _sway.x, _sway.x * 0.7) * loose
	pos += Vector3(_sway.x * 0.03, -_sway.y * 0.03, 0.0) * loose

	# Walk bob, in step with the camera's.
	var stride := clampf(player.horizontal_speed() / walk, 0.4, 1.7) * player.bob_amount \
			* lerpf(1.0, 0.12, aim) * (1.0 + sprint * 0.9)
	var ph := player.bob_phase
	pos += Vector3(sin(ph) * 0.0048, -absf(cos(ph)) * 0.0052, 0.0) * stride
	rot += Vector3(cos(ph * 2.0) * 0.008, sin(ph) * 0.006, sin(ph) * 0.014) * stride

	# Breathing, and the shake of hurt or bloodless hands (which aiming does not steady).
	_breath += delta
	var shake := player.body.mods.sway_scale
	pos.y += sin(_breath * 1.5) * 0.0013 * loose * shake
	rot.x += sin(_breath * 1.5 + 0.7) * deg_to_rad(0.22) * loose * shake
	if shake > 1.0:
		var tremble := (shake - 1.0) * deg_to_rad(0.35)
		rot += Vector3(sin(_breath * 7.3) + sin(_breath * 11.9 + 1.0), sin(_breath * 8.7 + 2.0) + sin(_breath * 13.1),
				sin(_breath * 6.1 + 0.5)) * tremble * 0.5

	# Jumping and landing: the weapon is heavy and arrives late.
	var lift_to := 0.0
	if not player.is_on_floor():
		lift_to = clampf(-player.velocity.y * 0.0038, -0.022, 0.028)
	_lift_v += ((lift_to - _lift) * 170.0 - _lift_v * 16.0) * delta
	_lift += _lift_v * delta
	pos.y += _lift * maxf(loose, 0.35)
	rot.x += _lift * 1.6 * maxf(loose, 0.35)

	# Strafing tilts it into the move.
	var strafe := clampf(player.velocity.dot(player.global_basis.x) / walk, -1.5, 1.5)
	_strafe = lerpf(_strafe, strafe, k.call(8.0))
	rot.z -= _strafe * deg_to_rad(2.4) * loose
	pos.x += _strafe * 0.003 * loose

	# Recoil spring.
	_kick_v += (-_kick * KICK_STIFFNESS - _kick_v * KICK_DAMPING) * delta
	_kick += _kick_v * delta
	pos += Vector3(0.0, _kick.y * 0.03, _kick.x)
	rot += Vector3(_kick.y, 0.0, _kick.z)

	# Reload, swap and swing poses.
	var action := _action_pose()
	var rate := 60.0 if state == State.SWING else 24.0
	_act_pos = _act_pos.lerp(action[0], k.call(rate))
	_act_rot = _act_rot.lerp(action[1], k.call(rate))
	pos += _act_pos
	rot += _act_rot * (PI / 180.0)

	# Up against a wall the muzzle lifts out of the way. There is no separate
	# render layer for the weapon, so this is what keeps it out of the plaster.
	var wall := 0.0
	if d.kind != WeaponData.Kind.MELEE:
		var reach := -(d.hip_position.z + current.muzzle_offset.z) + 0.10
		var cam := player.camera.global_transform
		var q := PhysicsRayQueryParameters3D.create(cam.origin, cam.origin - cam.basis.z * reach,
				Player.LAYER_WORLD, [player.get_rid()])
		var hit := player.get_world_3d().direct_space_state.intersect_ray(q)
		if not hit.is_empty():
			wall = clampf((1.0 - cam.origin.distance_to(hit.position) / reach) * 1.6, 0.0, 1.0)
	_wall = lerpf(_wall, wall, k.call(10.0))
	pos += Vector3(0.0, -0.03, 0.10) * _wall
	rot.x += deg_to_rad(50.0) * _wall

	_holder.position = pos
	_holder.rotation = rot

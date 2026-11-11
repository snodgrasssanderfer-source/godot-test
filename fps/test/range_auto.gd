class_name RangeAuto
extends Node
## Scripted runs of the shooting range, driven through the real input path:
##
##   --selftest      walks, jumps, climbs and shoots, checks the results and
##                   exits with 0 if everything passed
##                   (--only=creatures,grid picks sections)
##   --shoot=<dir>   poses each weapon and saves screenshots for review
##                   (--shots=pistol,shotgun,pipe,holes,hurt,night picks sections)
##
## Run with --fixed-fps 60 so that time is deterministic (and, headless, much
## faster than real time):
##   godot --headless --fixed-fps 60 --path fps -- --selftest

const TICKS := 120.0        # physics ticks per second (see project.godot)

var scene: Node3D
var player: Player
var rig: WeaponRig
var targets: Array[TargetDummy]

var _failed := 0
var _passed := 0
var _held := {}
var _noises: Array[float] = []
var _hits: Array = []


func start(range_scene: Node3D, mode: String, arg: String) -> void:
	scene = range_scene
	player = scene.player
	rig = player.rig
	targets = scene.street.targets
	player.noise_made.connect(func(r: float) -> void: _noises.append(r))
	rig.hit_confirmed.connect(func(head: bool, killed: bool, damage: float) -> void:
		_hits.append([head, killed, damage]))
	await _secs(0.5)
	if mode == "shoot":
		await _shoot(arg)
		get_tree().quit()
	else:
		await _selftest()
		print("SELFTEST %d passed, %d failed" % [_passed, _failed])
		get_tree().quit(1 if _failed > 0 else 0)


# --- helpers -----------------------------------------------------------------

func _secs(seconds: float) -> void:
	for i in maxi(int(round(seconds * TICKS)), 1):
		await get_tree().physics_frame


func _frames(count: int) -> void:
	for i in count:
		await get_tree().process_frame


func _send(action: StringName, pressed: bool) -> void:
	var e := InputEventAction.new()
	e.action = action
	e.pressed = pressed
	Input.parse_input_event(e)


func _hold(action: StringName) -> void:
	_held[action] = true
	_send(action, true)


func _release(action: StringName) -> void:
	_held.erase(action)
	_send(action, false)


func _release_all() -> void:
	for action: StringName in _held.keys():
		_send(action, false)
	_held.clear()


func _tap(action: StringName) -> void:
	_send(action, true)
	await _secs(0.03)
	_send(action, false)


func _check(what: String, ok: bool, detail := "") -> void:
	if ok:
		_passed += 1
	else:
		_failed += 1
	print("%s  %s%s" % ["PASS" if ok else "FAIL", what, ("   [" + detail + "]") if detail != "" else ""])


## Puts the player somewhere, standing still, looking along yaw (0 = -Z, 90 = -X).
func _place(at: Vector3, yaw_deg: float) -> void:
	_release_all()
	player.global_position = at
	player.velocity = Vector3.ZERO
	player.mode = Player.Mode.NORMAL
	player.crouched = false
	player.body.reset()
	player.stamina = player.stamina_max
	player.exhausted = false
	player.set_look(deg_to_rad(yaw_deg))
	await _secs(0.4)


func _look_at(point: Vector3) -> void:
	var dir := point - player.camera.global_position
	player.set_look(atan2(-dir.x, -dir.z), asin(dir.normalized().y))


func _floor_y(x: float, z: float) -> float:
	var q := PhysicsRayQueryParameters3D.create(Vector3(x, 3.0, z), Vector3(x, -1.0, z), 1)
	var hit := player.get_world_3d().direct_space_state.intersect_ray(q)
	return hit.position.y if not hit.is_empty() else NAN


func _select(index: int) -> void:
	await _tap([&"weapon_1", &"weapon_2", &"weapon_3"][index])
	for i in 400:
		if rig.current == rig.weapons[index] and rig.state == WeaponRig.State.READY:
			return
		await get_tree().physics_frame


func _wait_ready(limit := 6.0) -> float:
	var t := 0.0
	while rig.state != WeaponRig.State.READY and t < limit:
		await get_tree().physics_frame
		t += 1.0 / TICKS
	return t


## Waits for a target to get back up, then tops up its health.
func _wait_alive(dummy: TargetDummy) -> void:
	for i in 900:
		if dummy.state == TargetDummy.State.ALIVE:
			break
		await get_tree().physics_frame
	dummy.reset()


func _pos() -> String:
	var p := player.global_position
	return "pos %.2f %.2f %.2f" % [p.x, p.y, p.z]


# --- self test -----------------------------------------------------------------

func _selftest() -> void:
	# --only=creatures,grid runs just those sections (handy while working on one).
	var only := PackedStringArray()
	for arg in OS.get_cmdline_user_args():
		if arg.begins_with("--only="):
			only = arg.trim_prefix("--only=").split(",")
	for section: String in ["movement", "climbing", "pistol", "shotgun", "pipe", "rig", "grid_model", "grid_in_scene",
			"body_model", "body_in_scene", "creatures"]:
		if only.is_empty() or only.has(section) or only.has(section.get_slice("_", 0)):
			await call("_test_" + section)


func _test_movement() -> void:
	_check("spawns standing on the road", player.is_on_floor() and absf(player.global_position.y) < 0.03, _pos())

	var z0 := player.global_position.z
	_hold(&"move_forward")
	await _secs(2.0)
	var walked := z0 - player.global_position.z
	_check("walks forward at walking speed", walked > 7.4 and walked < 8.7 and absf(player.horizontal_speed() - 4.3) < 0.15,
			"%.2f m in 2 s, %.2f m/s" % [walked, player.horizontal_speed()])
	_check("footsteps are heard", _noises.has(6.0), str(_noises.slice(0, 4)))
	_release(&"move_forward")
	await _secs(0.5)
	_check("stops when the key is released", player.horizontal_speed() < 0.05, "%.3f m/s" % player.horizontal_speed())

	await _place(Vector3(-0.9, 0.05, 2.0), 0.0)
	_hold(&"move_forward")
	_hold(&"sprint")
	await _secs(1.5)
	_check("sprints", player.sprinting and absf(player.horizontal_speed() - 6.6) < 0.2, "%.2f m/s" % player.horizontal_speed())
	_check("sprinting drains stamina", player.stamina < player.stamina_max - 8.0, "%.1f" % player.stamina)
	_check("the weapon lowers while sprinting", rig._sprint_pose > 0.9, "%.2f" % rig._sprint_pose)
	await _tap(&"crouch")
	await _secs(0.1)
	var slide_speed := player.horizontal_speed()
	_check("crouch while sprinting starts a slide", player.mode == Player.Mode.SLIDE and slide_speed > 7.5,
			"mode %d, %.2f m/s" % [player.mode, slide_speed])
	await _secs(1.4)
	_check("the slide ends on its own", player.mode == Player.Mode.NORMAL and not player.crouched,
			"mode %d crouched %s" % [player.mode, player.crouched])
	_release_all()
	await _secs(0.5)

	await _place(Vector3(-0.9, 0.05, 2.0), 0.0)
	_hold(&"move_forward")
	_hold(&"sprint")
	await _secs(1.0)
	await _tap(&"crouch")
	await _secs(0.25)
	await _tap(&"jump")
	await _secs(0.1)
	_check("jumping out of a slide carries more than sprint speed", player.mode == Player.Mode.NORMAL and not player.is_on_floor()
			and player.horizontal_speed() > 6.7, "mode %d, %.2f m/s" % [player.mode, player.horizontal_speed()])
	_release_all()
	await _secs(1.0)

	await _place(Vector3(-0.9, 0.05, 2.0), 0.0)
	await _tap(&"jump")
	var peak := 0.0
	for i in 120:
		await get_tree().physics_frame
		peak = maxf(peak, player.global_position.y)
	_check("jumps about 1.05 m", absf(peak - 1.05) < 0.08 and player.is_on_floor(), "peak %.2f" % peak)

	_hold(&"crouch")
	await _secs(0.4)
	_check("crouches", player.crouched and is_equal_approx(player._shape.height, Player.CROUCH_HEIGHT)
			and player.head.position.y < 1.1, "eye %.2f" % player.head.position.y)
	_hold(&"move_forward")
	await _secs(1.0)
	_check("crouch-walks slowly", absf(player.horizontal_speed() - 2.3) < 0.15, "%.2f m/s" % player.horizontal_speed())
	_release_all()
	await _secs(0.5)
	_check("stands back up", not player.crouched and player.head.position.y > 1.55, "eye %.2f" % player.head.position.y)

	_hold(&"lean_right")
	await _secs(0.5)
	_check("leans right", player.head.position.x > 0.25, "head x %.2f" % player.head.position.x)
	_release(&"lean_right")

	# Up the 15 cm curb onto the pavement, without jumping.
	await _place(Vector3(-2.6, 0.05, 2.0), 90.0)
	_hold(&"move_forward")
	await _secs(0.55)
	_release_all()
	await _secs(0.3)
	var p := player.global_position
	_check("walks up the curb", p.x < -3.9 and absf(p.y - 0.155) < 0.03 and player.is_on_floor(), _pos())
	# And back down.
	await _place(Vector3(-4.3, 0.2, 2.0), -90.0)
	_hold(&"move_forward")
	await _secs(0.6)
	_release_all()
	await _secs(0.3)
	_check("walks down the curb", player.global_position.x > -3.0 and absf(player.global_position.y) < 0.03, _pos())


func _test_climbing() -> void:
	# Vault the picket fence between the pavement and the first garden.
	await _place(Vector3(-4.9, 0.2, -2.0), 90.0)
	_hold(&"move_forward")
	await _secs(0.3)
	await _tap(&"jump")
	await _secs(0.08)
	_check("jumping at a fence starts a vault", player.mode == Player.Mode.MANTLE, "mode %d, %s" % [player.mode, _pos()])
	await _secs(1.2)
	_release_all()
	await _secs(0.4)
	var p := player.global_position
	_check("the vault lands on the far side", p.x < -6.1 and p.y < 0.35 and player.is_on_floor()
			and player.mode == Player.Mode.NORMAL, _pos())

	# Vault a road barricade (two rails, open underneath).
	await _place(Vector3(-1.75, 0.05, -19.6), 0.0)
	_hold(&"move_forward")
	await _secs(0.3)
	await _tap(&"jump")
	await _secs(1.4)
	_release_all()
	await _secs(0.4)
	p = player.global_position
	_check("vaults a barricade", p.z < -21.4 and p.y < 0.1 and player.is_on_floor(), _pos())

	# Climb onto the bonnet of the car parked along the road.
	await _place(Vector3(-2.5, 0.05, -34.6), 180.0)
	_hold(&"move_forward")
	await _secs(0.5)
	await _tap(&"jump")
	await _secs(1.2)
	_release_all()
	await _secs(0.4)
	p = player.global_position
	_check("climbs onto a car bonnet", absf(p.y - 0.91) < 0.06 and p.z > -33.3 and player.is_on_floor(), _pos())

	# A house wall is far too tall: jumping at it must not climb.
	var z := -24.0
	for candidate: float in [-24.0, -25.0, -23.0, -22.0, -26.0]:
		var a := _floor_y(-10.3, candidate)
		var b := _floor_y(-10.9, candidate)
		if not is_nan(a) and not is_nan(b) and a < 0.25 and b < 0.25:
			z = candidate
			break
	await _place(Vector3(-10.2, 0.3, z), 90.0)
	_hold(&"move_forward")
	var climbed := false
	var top := 0.0
	for attempt in 3:
		await _secs(0.4)
		await _tap(&"jump")
		for i in 110:
			await get_tree().physics_frame
			climbed = climbed or player.mode == Player.Mode.MANTLE
			top = maxf(top, player.global_position.y)
	_release_all()
	await _secs(0.3)
	_check("does not climb a house wall", not climbed and top < 1.4 and player.global_position.x > -11.2,
			"peak %.2f, %s" % [top, _pos()])
	_check("a wall in front lifts the muzzle", rig._wall > 0.5, "%.2f" % rig._wall)

	# Running into a tree trunk just stops.
	await _place(Vector3(-7.5, 0.3, -11.0), 0.0)
	_hold(&"move_forward")
	await _secs(1.5)
	_release_all()
	_check("tree trunks are solid", player.global_position.z > -12.8, _pos())


func _test_pistol() -> void:
	var dummy := targets[0]
	await _select(0)
	await _place(Vector3(-1.4, 0.05, -9.0), 0.0)
	var pistol := rig.current
	_check("starts with a full pistol", pistol.data.id == &"pistol" and pistol.mag == 15, "mag %d" % pistol.mag)

	_hold(&"aim")
	await _secs(0.4)
	_check("aims down sights", rig.aim > 0.99 and player.camera.fov < 56.0, "aim %.2f fov %.1f" % [rig.aim, player.camera.fov])
	_look_at(dummy.chest_point())
	await _secs(0.1)
	_noises.clear()
	_hits.clear()
	await _tap(&"fire")
	await _secs(0.1)
	_check("a body shot does 34", is_equal_approx(dummy.health, 66.0) and pistol.mag == 14,
			"health %.1f mag %d" % [dummy.health, pistol.mag])
	_check("the shot is heard across the block", _noises.has(70.0), str(_noises))
	_check("the hit is confirmed", _hits.size() == 1 and _hits[0] == [false, false, 34.0], str(_hits))
	_check("the view kicks up", player._recoil_target.x > 0.0 or player._recoil.x > 0.0)
	await _secs(0.5)
	_look_at(dummy.chest_point())
	await _tap(&"fire")
	await _secs(0.3)
	_look_at(dummy.chest_point())
	await _tap(&"fire")
	await _secs(0.1)
	_check("three body shots put it down", dummy.state != TargetDummy.State.ALIVE, "health %.1f" % dummy.health)
	await _wait_alive(dummy)
	_check("the target stands back up", is_equal_approx(dummy.health, 100.0))

	await _secs(0.3)
	_look_at(dummy.head_point())
	await _secs(0.1)
	_hits.clear()
	await _tap(&"fire")
	await _secs(0.1)
	_check("one headshot kills", dummy.state != TargetDummy.State.ALIVE and _hits.size() == 1 and _hits[0][0] and _hits[0][1],
			str(_hits))
	_release(&"aim")

	# Rate of fire: hammering the trigger cannot beat the fire interval.
	await _secs(0.5)
	player.set_look(0.0, deg_to_rad(40.0))
	var before := pistol.mag
	for i in 12:
		_send(&"fire", true)
		await _secs(0.02)
		_send(&"fire", false)
		await _secs(0.02)
	var shots := before - pistol.mag
	_check("fire rate is capped", shots >= 3 and shots <= 5, "%d shots in 0.48 s" % shots)
	await _secs(0.5)

	# Tactical reload.
	before = pistol.mag
	await _tap(&"reload")
	await _secs(0.5)
	_check("the magazine is out mid-reload", rig.state == WeaponRig.State.RELOAD and pistol.mag == before)
	await _secs(0.5)
	_check("rounds count when the magazine goes in", pistol.mag == 15, "mag %d" % pistol.mag)
	var rest := await _wait_ready()
	_check("a reload takes 1.55 s", absf(1.0 + 0.03 + rest - 1.55) < 0.06, "%.2f s" % (1.03 + rest))

	# Empty reload, started by pulling the trigger on an empty gun.
	pistol.mag = 0
	pistol.slide_locked = true
	await _tap(&"fire")
	await _secs(0.1)
	_check("firing empty starts a reload", rig.state == WeaponRig.State.RELOAD)
	rest = await _wait_ready()
	_check("an empty reload takes 1.95 s and frees the slide", absf(0.13 + rest - 1.95) < 0.06 and pistol.mag == 15
			and not pistol.slide_locked, "%.2f s" % (0.13 + rest))

	# Firing cuts a reload short once the magazine is in.
	pistol.mag = 5
	await _tap(&"reload")
	await _secs(1.0)
	await _tap(&"fire")
	await _secs(0.05)
	_check("firing cancels the tail of a reload", pistol.mag == 14, "mag %d state %d" % [pistol.mag, rig.state])

	# Sprinting delays the shot.
	await _wait_ready()
	await _place(Vector3(-0.9, 0.05, 2.0), 0.0)
	_hold(&"move_forward")
	_hold(&"sprint")
	await _secs(0.8)
	before = pistol.mag
	_send(&"fire", true)
	await _secs(0.05)
	var early := pistol.mag
	await _secs(0.3)
	_send(&"fire", false)
	_check("a shot out of a sprint waits for the gun to come up", early == before and pistol.mag == before - 1,
			"early %d later %d" % [before - early, before - pistol.mag])
	_release_all()
	await _secs(0.5)


func _test_shotgun() -> void:
	var dummy := targets[0]
	await _wait_alive(dummy)
	await _select(1)
	var shotgun := rig.current
	_check("switches to the shotgun", shotgun.data.id == &"shotgun" and shotgun.mag == 5)
	await _place(Vector3(-1.4, 0.05, -12.0), 0.0)
	_hold(&"aim")
	await _secs(0.5)
	_look_at(dummy.chest_point())
	await _secs(0.1)
	_hits.clear()
	await _tap(&"fire")
	await _secs(0.1)
	var dealt: float = _hits[0][2] if _hits.size() == 1 else 0.0
	_check("a close blast lands most of its pellets", dealt >= 17.0 * 5.0 and shotgun.mag == 4, "%.0f damage" % dealt)
	_check("it has to be pumped", rig.state == WeaponRig.State.PUMP)
	var rest := await _wait_ready()
	_check("the pump takes 0.62 s", absf(0.13 + rest - 0.62) < 0.05, "%.2f s" % (0.13 + rest))
	_release(&"aim")
	await _wait_alive(dummy)

	# Far away the pellets spread out and lose most of their damage.
	await _place(Vector3(-1.4, 0.05, 8.0), 0.0)
	_hold(&"aim")
	await _secs(0.5)
	_look_at(dummy.chest_point())
	await _secs(0.1)
	_hits.clear()
	await _tap(&"fire")
	await _secs(0.1)
	dealt = _hits[0][2] if _hits.size() == 1 else 0.0
	_check("at 25 m it barely hurts", dealt < 45.0, "%.0f damage" % dealt)
	_release(&"aim")
	await _wait_ready()

	# Shell-by-shell reload from 3 to 5.
	_check("has three shells left", shotgun.mag == 3)
	await _tap(&"reload")
	await _secs(0.35 + 0.48 * 0.55 + 0.05)
	_check("the first shell goes in", shotgun.mag == 4, "mag %d" % shotgun.mag)
	rest = await _wait_ready()
	var total := 0.35 + 0.48 * 0.55 + 0.08 + rest
	_check("two shells take 1.71 s", shotgun.mag == 5 and absf(total - (0.35 + 0.96 + 0.40)) < 0.06, "%.2f s" % total)

	# From empty: five shells, then a pump.
	shotgun.mag = 0
	await _tap(&"reload")
	await _secs(0.2)
	rest = await _wait_ready()
	total = 0.23 + rest
	_check("loading from empty ends with a pump", shotgun.mag == 5 and absf(total - (0.35 + 2.4 + 0.4 + 0.62)) < 0.08,
			"%.2f s" % total)

	# Firing interrupts after the shell in hand, then shoots.
	shotgun.mag = 1
	await _tap(&"reload")
	await _secs(0.9)
	_send(&"fire", true)
	await _secs(0.05)
	_send(&"fire", false)
	await _secs(1.2)
	_check("firing interrupts a shell reload", shotgun.mag == 2, "mag %d state %d" % [shotgun.mag, rig.state])
	await _wait_ready()

	# Switching away mid-reload works and keeps what was loaded.
	await _tap(&"reload")
	await _secs(0.7)
	await _tap(&"weapon_1")
	await _secs(1.6)
	_check("switching weapons interrupts a reload", rig.current.data.id == &"pistol" and shotgun.mag == 3,
			"%s, shotgun mag %d" % [rig.current.data.id, shotgun.mag])


func _test_pipe() -> void:
	var dummy := targets[0]
	await _wait_alive(dummy)
	await _select(2)
	_check("switches to the pipe", rig.current.data.id == &"pipe")
	var chest := dummy.chest_point()
	await _place(Vector3(chest.x, 0.05, chest.z + 1.5), 0.0)
	_look_at(dummy.chest_point())
	var stamina := player.stamina
	_hits.clear()
	_noises.clear()
	await _tap(&"fire")
	await _secs(0.15)
	_check("the swing has a wind-up", dummy.health == 100.0 and rig.state == WeaponRig.State.SWING)
	await _secs(0.15)
	_check("the pipe does 55", is_equal_approx(dummy.health, 45.0) and _hits.size() == 1, "health %.1f" % dummy.health)
	_check("swinging costs stamina and is quiet", player.stamina < stamina and _noises.has(12.0) and not _noises.has(70.0),
			"%.1f, %s" % [player.stamina, str(_noises)])
	await _wait_ready()
	_look_at(dummy.chest_point())
	await _tap(&"fire")
	await _secs(0.4)
	_check("two blows put it down", dummy.state != TargetDummy.State.ALIVE)
	await _wait_ready()
	# Out of reach.
	await _wait_alive(dummy)
	await _place(Vector3(chest.x, 0.05, chest.z + 3.2), 0.0)
	_look_at(dummy.chest_point())
	await _tap(&"fire")
	await _secs(0.5)
	_check("the pipe misses beyond its reach", dummy.health == 100.0)
	await _wait_ready()


func _test_rig() -> void:
	await _tap(&"weapon_next")
	await _secs(1.2)
	_check("the wheel cycles weapons", rig.current.data.id == &"pistol", String(rig.current.data.id))
	await _tap(&"weapon_prev")
	await _secs(1.2)
	_check("and back", rig.current.data.id == &"pipe", String(rig.current.data.id))
	await _select(0)
	await _tap(&"flashlight")
	_check("the flashlight toggles", player.flashlight.visible)
	await _tap(&"flashlight")
	# Hip fire is looser when moving, tighter when crouched.
	await _place(Vector3(-0.9, 0.05, 2.0), 0.0)
	var still := rig.spread
	_hold(&"move_forward")
	await _secs(0.8)
	var moving := rig.spread
	_release_all()
	_hold(&"crouch")
	await _secs(0.8)
	var low := rig.spread
	_release_all()
	_check("spread: crouched < standing < walking", low < still and still < moving, "%.2f < %.2f < %.2f" % [low, still, moving])
	var pose := rig._holder.position
	_check("the weapon pose is finite", is_finite(pose.x) and is_finite(pose.y) and is_finite(pose.z)
			and is_finite(player.camera.global_position.y))


# The power grid model on its own (docs/design_supplement.md, section 4).
# No scene needed: build a GridSim, feed it, step it.
func _test_grid_model() -> void:
	# 15 pistol shots, 10 m from one pole and 30 m from the next one down the wire
	var g := GridSim.new()
	var near := g.add_node(Vector3(0, 0, -10))
	var far := g.add_node(Vector3(0, 0, -30))
	g.link(near, far)
	g.add_roster(near, &"small", 3)
	g.add_roster(near, &"medium", 2)
	var woken: Array = []
	g.woke.connect(func(n: int, _e: int, k: StringName) -> void: woken.append([n, k]))
	for i in 15:
		g.inject(Vector3.ZERO, 70.0, 6.0)
		g.advance(0.13)
	_check("grid: a pistol magazine at 10 m brings the nearest cell to 60-70", g.charge(near) > 60.0 and g.charge(near) < 70.0,
			"%.1f" % g.charge(near))
	_check("grid: and the next cell, 30 m off, to 25-35", g.charge(far) > 25.0 and g.charge(far) < 35.0, "%.1f" % g.charge(far))
	_check("grid: that wakes the small ones but nothing bigger", woken == [[near, &"small"], [near, &"small"], [near, &"small"]]
			and g.count(true) == 3, str(woken))
	_check("grid: the street shows it (level 3 near, level 1 far)", g.level(near) == 3 and g.level(far) == 1,
			"%d %d" % [g.level(near), g.level(far)])

	# drain: nothing for 6 s, then 1.2 per second
	g = GridSim.new()
	var a := g.add_node(Vector3.ZERO)
	g.inject_node(a, 50.0)
	g.advance(5.9)
	_check("grid: a cell holds its charge for 6 quiet seconds", is_equal_approx(g.charge(a), 50.0), "%.2f" % g.charge(a))
	g.advance(10.1)
	_check("grid: then drains at 1.2 per second", absf(g.charge(a) - 38.0) < 0.2, "%.2f" % g.charge(a))

	# creatures doze off again, unless they have seen the player
	g = GridSim.new()
	a = g.add_node(Vector3.ZERO)
	var ids := g.add_roster(a, &"small", 2)
	g.inject_node(a, 56.0)
	g.advance(0.1)
	_check("grid: two small ones wake at 50 and 55", g.count(true) == 2)
	g.set_engaged(a, ids[1], true)
	g.cells[a].charge = 20.0
	g.advance(9.8)
	_check("grid: still awake 9.8 s after the charge fell", g.count(true) == 2)
	g.advance(0.3)
	_check("grid: after 10 s the one that never saw the player sleeps again", g.count(true) == 1
			and g.cells[a].roster[ids[1]]["awake"])

	# kills short the cell, and the roster never refills
	g = GridSim.new()
	a = g.add_node(Vector3.ZERO)
	ids = g.add_roster(a, &"small", 1)
	g.inject_node(a, 60.0)
	g.advance(0.1)
	g.kill(a, ids[0])
	_check("grid: a kill takes 8 off the cell", is_equal_approx(g.charge(a), 52.0), "%.2f" % g.charge(a))
	woken.clear()
	g.woke.connect(func(n: int, _e: int, k: StringName) -> void: woken.append([n, k]))
	g.inject_node(a, 40.0)
	g.advance(1.0)
	_check("grid: an emptied cell wakes nothing more", woken.is_empty() and g.count() == 0)

	# breaker trip
	g = GridSim.new()
	a = g.add_node(Vector3.ZERO)
	var events: Array = []
	g.tripped.connect(func(n: int) -> void: events.append("trip"))
	g.restored.connect(func(n: int) -> void: events.append("back"))
	g.inject_node(a, 200.0)
	g.advance(4.8)
	_check("grid: 4.8 s at 100 is not yet a trip", is_equal_approx(g.charge(a), 100.0) and events.is_empty())
	g.advance(0.3)
	_check("grid: 5 s at 100 trips the cell", g.is_tripped(a) and events == ["trip"] and g.charge(a) == 0.0)
	g.inject_node(a, 50.0)
	_check("grid: a tripped cell takes no charge", g.charge(a) == 0.0)
	g.advance(44.5)
	_check("grid: it stays dead for 45 s", g.is_tripped(a))
	g.advance(0.6)
	_check("grid: and comes back at 30", not g.is_tripped(a) and events == ["trip", "back"] and absf(g.charge(a) - 30.0) < 0.2,
			"%.2f %s" % [g.charge(a), str(events)])

	# pulled breaker box, scripted jolt
	g = GridSim.new()
	a = g.add_node(Vector3.ZERO)
	var b := g.add_node(Vector3(0, 0, -25))
	g.set_breaker(a, true)
	g.inject_node(a, 90.0)
	_check("grid: a pulled breaker caps the cell at 40", is_equal_approx(g.charge(a), 40.0))
	g.raise_all(70.0)
	_check("grid: a scripted jolt lifts every cell (the capped one stays capped)", is_equal_approx(g.charge(b), 70.0)
			and is_equal_approx(g.charge(a), 40.0))


# The grid wired into the range: real shots, through NoiseBus.
func _test_grid_in_scene() -> void:
	scene.build_grid()
	await _secs(0.1)
	var nodes: Array[GridNode] = scene.grid_nodes
	_check("grid: the range has one cell per street lamp", nodes.size() == 4 and Grid.sim.cells.size() == 4
			and Grid.sim.cells[1].links.size() == 2, "%d nodes" % nodes.size())
	var heard: Array = []
	var listen := func(_p: Vector3, r: float, e: float, src: StringName, _o: float) -> void: heard.append([r, e, src])
	NoiseBus.heard.connect(listen)
	await _select(0)
	await _place(Vector3(2.35, 0.05, 0.0), 180.0)       # 10 m from the first lamp, facing away from the targets
	_hold(&"move_back")
	await _secs(1.0)
	_release_all()
	_check("grid: footsteps do not disturb it", is_zero_approx(nodes[0].charge()) and heard.size() > 0, "%.2f" % nodes[0].charge())
	await _place(Vector3(2.35, 0.05, 0.0), 180.0)
	await _wait_ready()
	heard.clear()
	await _tap(&"fire")
	await _secs(0.05)
	var want := 6.0 * pow(1.0 - 10.0 / 70.0, 2.0)
	_check("grid: one pistol shot at 10 m adds about 4.4 to the nearest cell", absf(nodes[0].charge() - want) < 0.25,
			"%.2f, expected %.2f" % [nodes[0].charge(), want])
	_check("grid: the shot went over NoiseBus as a pistol", heard.has([70.0, 6.0, &"pistol"]), str(heard))
	_check("grid: cells further down the street got less", nodes[1].charge() < nodes[0].charge() and nodes[1].charge() > 0.0
			and is_zero_approx(nodes[3].charge()), "%.2f %.2f %.2f" % [nodes[1].charge(), nodes[2].charge(), nodes[3].charge()])
	var before := nodes[0].charge()
	await _select(1)
	await _tap(&"fire")
	await _secs(0.05)
	_check("grid: a shotgun blast adds far more than a pistol shot", nodes[0].charge() - before > 10.0,
			"+%.2f" % (nodes[0].charge() - before))
	NoiseBus.heard.disconnect(listen)
	await _select(0)
	scene.build_grid()


# --- injuries --------------------------------------------------------------------

## Runs a BodySim until `done` says so; returns the seconds it took (or `limit`).
func _body_until(b: BodySim, limit: float, done: Callable) -> float:
	var t := 0.0
	while t < limit and not done.call():
		b.advance(0.25)
		t += 0.25
	return t


# The injury model on its own: no scene, fixed seed, simulated time.
func _test_body_model() -> void:
	const HEAD := BodySim.Part.HEAD
	const TORSO := BodySim.Part.TORSO
	const ARM_R := BodySim.Part.ARM_R
	const ARM_L := BodySim.Part.ARM_L
	const LEG_L := BodySim.Part.LEG_L
	const LEG_R := BodySim.Part.LEG_R
	const CUT := BodySim.Kind.CUT
	const CRUSH := BodySim.Kind.CRUSH
	const BURN := BodySim.Kind.BURN
	const FINE := BodySim.Tier.FINE
	const LIGHT := BodySim.Tier.LIGHT
	const HEAVY := BodySim.Tier.HEAVY
	const DEAD := BodySim.Tier.DEAD

	var b := BodySim.new(7)
	var m := b.mods
	_check("body: starts whole, every modifier at 1", is_equal_approx(b.blood, 100.0) and b.wounds.is_empty()
			and m.can_sprint and m.can_slide and is_equal_approx(m.walk_scale * m.sprint_scale * m.reload_scale
			* m.pump_scale * m.sway_scale * m.kick_scale * m.stamina_max_scale * m.stamina_regen_scale, 1.0))
	var levels: Array = []
	for dmg: float in [11.9, 12.0, 25.0, 25.1]:
		b.reset()
		levels.append(b.hit_part(TORSO, dmg, CUT)["level"])
	_check("body: wound level follows the damage of the hit", levels == [1, 2, 2, 3], str(levels))

	# Bleeding.
	b.reset()
	b.hit_part(ARM_L, 20.0, CUT)
	var t := _body_until(b, 400.0, func() -> bool: return b.blood <= 25.0)
	_check("body: one level 2 cut bleeds to the brink in about 167 s", absf(t - 166.7) < 2.0, "%.1f s" % t)
	b.reset()
	b.hit_part(ARM_L, 30.0, CUT)
	t = _body_until(b, 400.0, func() -> bool: return b.blood <= 25.0)
	_check("body: a level 3 cut takes about 68 s", absf(t - 68.2) < 2.0, "%.1f s" % t)
	t = _body_until(b, 400.0, func() -> bool: return b.collapsing)
	var cause: Array = []
	b.died.connect(func(c: StringName) -> void: cause.append(c))
	var down := _body_until(b, 60.0, func() -> bool: return b.dead)
	_check("body: out of blood means four seconds on the knees, then death", absf(t - 22.7) < 1.5 and absf(down - 4.0) < 0.5
			and cause == [&"blood"] and b.mods.input_lost, "collapse after %.1f s more, dead %.1f s later, %s" % [t, down, cause])
	b = BodySim.new(7)
	b.hit_part(LEG_R, 8.0, CUT)
	b.advance(59.0)
	var still := b.bleed_rate()
	b.advance(2.0)
	_check("body: a level 1 cut closes by itself after a minute", is_equal_approx(still, 0.15) and is_zero_approx(b.bleed_rate())
			and absf(b.blood - 91.0) < 0.3, "%.2f/s then %.2f/s, blood %.1f" % [still, b.bleed_rate(), b.blood])
	var low := b.blood
	b.advance(100.0)
	var plain := b.blood - low
	b.sports_drink()
	low = b.blood
	b.advance(100.0)
	_check("body: blood comes back at 0.02/s, three times faster on a sports drink", absf(plain - 2.0) < 0.1
			and absf(b.blood - low - 6.0) < 0.2, "+%.2f then +%.2f per 100 s" % [plain, b.blood - low])

	# Dying, and damage spilling out of a dead limb.
	b.reset()
	for i in 3:
		b.hit_part(ARM_L, 25.0, CUT)
	_check("body: a limb at zero is useless, not fatal, and half the excess reaches the torso", b.tier(ARM_L) == DEAD
			and not b.dead and is_equal_approx(b.function[TORSO], 117.5), "torso %.1f" % b.function[TORSO])
	var causes: Array = []
	for part: BodySim.Part in [HEAD, TORSO]:
		var x := BodySim.new(7)
		x.died.connect(func(c: StringName) -> void: causes.append(c))
		for i in 5:
			x.hit_part(part, 30.0, CUT)
	_check("body: head or torso at zero kills", causes == [&"head", &"torso"], str(causes))

	# Legs.
	b.reset()
	b.hit_part(LEG_L, 30.0, CUT)
	m = b.mods
	_check("body: a lightly hurt leg sprints at 5.8, jumps lower, slides dearer", b.tier(LEG_L) == LIGHT and m.can_sprint
			and absf(m.sprint_scale * 6.6 - 5.8) < 0.01 and is_equal_approx(m.jump_scale, 0.85)
			and is_equal_approx(m.slide_cost_scale, 1.5) and is_equal_approx(m.walk_scale, 1.0))
	b.hit_part(LEG_R, 30.0, CUT)
	_check("body: both legs hurt costs another tenth", absf(b.mods.sprint_scale * 6.6 - 5.8 * 0.9) < 0.01
			and is_equal_approx(b.mods.walk_scale, 0.9))
	b.reset()
	b.hit_part(LEG_L, 30.0, CUT)
	b.hit_part(LEG_L, 30.0, CUT)
	m = b.mods
	_check("body: a badly hurt leg cannot sprint or slide, walks at 3.4 and climbs 1.25 m at most", b.tier(LEG_L) == HEAVY
			and not m.can_sprint and not m.can_slide and absf(m.walk_scale * 4.3 - 3.4) < 0.01
			and is_equal_approx(m.mantle_max_override, 1.25) and is_equal_approx(m.mantle_time_scale, 1.5) and m.limp > 0.0)
	b.hit_part(LEG_L, 30.0, CUT)
	m = b.mods
	_check("body: a useless leg walks at 2.4 and cannot jump", b.tier(LEG_L) == DEAD and absf(m.walk_scale * 4.3 - 2.4) < 0.01
			and is_zero_approx(m.jump_scale) and is_equal_approx(m.limp, 1.0))

	# Arms. Crush wounds here: they hurt but do not bleed, so only the arm is in play.
	b.reset()
	b.hit_part(ARM_L, 15.0, CRUSH)
	m = b.mods
	_check("body: a lightly hurt support arm reloads 1.25x and pumps 1.3x slower", b.tier(ARM_L) == LIGHT
			and is_equal_approx(m.reload_scale, 1.25) and is_equal_approx(m.pump_scale, 1.3) and is_zero_approx(b.bleed_rate()),
			"function %.0f" % b.function[ARM_L])
	b.hit_part(ARM_L, 15.0, CRUSH)
	m = b.mods
	_check("body: badly hurt, 1.6x and 1.7x", b.tier(ARM_L) == HEAVY and is_equal_approx(m.reload_scale, 1.6)
			and is_equal_approx(m.pump_scale, 1.7) and is_zero_approx(m.mag_drop_chance))
	b.hit_part(ARM_L, 15.0, CRUSH)
	m = b.mods
	_check("body: useless, 2.2x, fumbles magazines, cannot carry or use the stun gun", b.tier(ARM_L) == DEAD
			and is_equal_approx(m.reload_scale, 2.2) and is_equal_approx(m.mag_drop_chance, 0.15)
			and not m.can_carry and not m.can_use_stun_gun)
	b.reset()
	b.hit_part(ARM_R, 15.0, CRUSH)
	m = b.mods
	_check("body: a lightly hurt gun arm sways 1.4x and recovers from recoil slower", b.tier(ARM_R) == LIGHT
			and is_equal_approx(m.sway_scale, 1.4) and is_equal_approx(m.kick_recover_scale, 0.85)
			and is_equal_approx(m.aim_time_scale, 1.0), "sway %.2f" % m.sway_scale)
	b.hit_part(ARM_R, 15.0, CRUSH)
	m = b.mods
	_check("body: badly hurt, sways 2.2x, aims 1.35x slower, kicks 1.25x harder", b.tier(ARM_R) == HEAVY
			and is_equal_approx(m.sway_scale, 2.2) and is_equal_approx(m.aim_time_scale, 1.35)
			and is_equal_approx(m.kick_scale, 1.25) and is_equal_approx(m.kick_recover_scale, 0.85)
			and is_equal_approx(m.long_aim_spread_scale, 1.0))
	b.hit_part(ARM_R, 15.0, CRUSH)
	m = b.mods
	_check("body: useless, long guns cannot be shouldered", b.tier(ARM_R) == DEAD
			and is_equal_approx(m.long_aim_spread_scale, 3.0) and is_equal_approx(m.long_pump_scale, 1.8))

	# Torso, head, blood.
	b.reset()
	b.hit_part(TORSO, 30.0, CRUSH)
	m = b.mods
	_check("body: a hurt torso has less stamina and gets it back slower", b.tier(TORSO) == LIGHT
			and is_equal_approx(m.stamina_max_scale, 0.8) and is_equal_approx(m.stamina_regen_scale, 0.7))
	b.hit_part(TORSO, 26.0, CUT)
	b.towel_tape(b.worst_bleeding())
	var coughs: Array = []
	b.vocalised.connect(func(what: StringName) -> void: coughs.append(what))
	b.advance(120.0)
	_check("body: badly hurt, stamina at 60% and a cough every 20 to 40 s", b.tier(TORSO) == HEAVY
			and is_equal_approx(b.mods.stamina_max_scale, 0.6) and coughs.size() >= 3 and coughs.size() <= 6
			and coughs.count(&"cough") == coughs.size(), "%d coughs in 120 s" % coughs.size())
	b = BodySim.new(7)
	b.hit_part(HEAD, 20.0, CRUSH)
	var ring := b.mods.ear_ring
	b.advance(2.5)
	_check("body: a blow to the head rings in the ears for two seconds", b.tier(HEAD) == LIGHT and is_equal_approx(ring, 2.0)
			and is_zero_approx(b.mods.ear_ring))
	b.hit_part(HEAD, 6.0, CRUSH)
	var blackouts: Array = []
	var dark := [0.0]
	b.blackout_started.connect(func() -> void: blackouts.append(1))
	for i in 2000:
		b.advance(0.05)
		dark[0] += 0.05 * b.mods.blackout
	_check("body: badly hurt, 0.4 s blackouts every 15 to 25 s", b.tier(HEAD) == HEAVY and blackouts.size() >= 4
			and blackouts.size() <= 7 and absf(dark[0] / maxf(blackouts.size(), 1) - 0.4) < 0.06,
			"%d in 100 s, %.2f s each" % [blackouts.size(), dark[0] / maxf(blackouts.size(), 1)])
	b = BodySim.new(7)
	b.blood = 60.0
	b.advance(0.1)
	var at60 := b.mods
	b.blood = 40.0
	b.advance(0.1)
	var at40 := b.mods
	b.blood = 20.0
	var stumbles := [0, 0.0]
	b.stumbled.connect(func() -> void: stumbles[0] += 1)
	for i in 1200:
		b.advance(0.05)
		stumbles[1] += 0.05 if b.mods.input_lost else 0.0
	_check("body: under 70 blood stamina returns slower and the colour drains", is_equal_approx(at60.stamina_regen_scale, 0.8)
			and at60.desaturate > 0.0 and is_equal_approx(at60.sway_scale, 1.0))
	_check("body: under 45 the hands shake and sprinting costs more", is_equal_approx(at40.sway_scale, 1.3)
			and is_equal_approx(at40.sprint_drain_scale, 1.4) and at40.tunnel > 0.0)
	_check("body: under 25 the view drifts and the controls drop out for 0.3 s every 8 to 14 s", b.mods.aim_drift > 0.0
			and b.mods.muffled and stumbles[0] >= 4 and stumbles[0] <= 8 and absf(stumbles[1] / maxf(stumbles[0], 1) - 0.3) < 0.06,
			"%d in 60 s, %.2f s each" % [stumbles[0], stumbles[1] / maxf(stumbles[0], 1)])

	# Supplies.
	b = BodySim.new(7)
	var cut3: int = b.hit_part(LEG_L, 30.0, CUT)["wound"]
	var open_rate := b.bleed_rate()
	var glued := b.glue(cut3)
	b.tape(cut3)
	var taped := b.bleed_rate()
	b.towel_tape(cut3)
	_check("body: tape slows a level 3 cut to 0.15/s; only a towel under it closes it; glue cannot", is_equal_approx(open_rate, 1.1)
			and not glued and is_equal_approx(taped, 0.15) and is_zero_approx(b.bleed_rate()),
			"%.2f -> %.2f -> %.2f" % [open_rate, taped, b.bleed_rate()])
	var cut2: int = b.hit_part(LEG_R, 20.0, CUT)["wound"]
	var before := b.pain()
	_check("body: tape closes a level 2 cut, and a closed wound hurts half as much", b.tape(cut2) and is_zero_approx(b.bleed_rate())
			and is_equal_approx(before, 19.0 + 24.0) and is_equal_approx(b.pain(), 19.0 + 12.0), "%.0f -> %.0f" % [before, b.pain()])
	var arm: int = b.hit_part(ARM_L, 20.0, CUT)["wound"]
	b.tape(arm)
	var none := b.strain_legs(0.0)
	var tore := b.strain_legs(1.0)
	_check("body: hard use of the legs can tear their closed wounds open, and only theirs", not none and tore
			and b.wound(cut2).state == BodySim.WoundState.TORN and b.wound(cut3).state == BodySim.WoundState.TORN
			and b.wound(arm).state == BodySim.WoundState.SEALED and is_equal_approx(b.bleed_rate(), 0.30))
	var screams: Array = []
	b.vocalised.connect(func(what: StringName) -> void: screams.append(what))
	b.tape(cut2)
	b.tape(cut3)
	b.pour_whiskey(cut2)
	b.pour_whiskey(cut3)
	_check("body: a wound doused in whiskey never tears again (and the pouring is loud)", not b.strain_legs(1.0)
			and is_zero_approx(b.bleed_rate()) and screams == [&"scream", &"scream"] and b.strain_arms(1.0), str(screams))
	b = BodySim.new(7)
	b.hit_part(ARM_R, 15.0, CRUSH)
	b.take_painkiller()
	b.advance(19.0)
	var early := b.mods.sway_scale
	b.advance(2.0)
	var working := b.mods.sway_scale
	var numb := b.mods.numb
	b.advance(180.0)
	_check("body: a painkiller does nothing for 20 s, then takes 60% off for three minutes", is_equal_approx(early, 1.4)
			and absf(working - (1.0 + 0.4 * 0.4)) < 0.001 and numb and is_equal_approx(b.mods.sway_scale, 1.4) and not b.mods.numb,
			"%.2f -> %.2f -> %.2f" % [early, working, b.mods.sway_scale])
	b.take_painkiller()
	b.advance(61.0)
	b.take_painkiller()
	var spaced := b.mods.woozy
	b.advance(30.0)
	b.take_painkiller()
	_check("body: two pills inside a minute make the view swim", not spaced and b.mods.woozy)
	b = BodySim.new(7)
	b.hit_part(ARM_R, 15.0, CRUSH)
	b.drink_whiskey()
	var drunk := b.mods.sway_scale
	var eased := b.relief()
	b.take_painkiller()
	b.advance(21.0)
	var both := b.relief()
	b.advance(100.0)
	_check("body: whiskey takes 40% off the pain for two minutes but unsteadies the hands; with a pill, 76%",
			is_equal_approx(eased, 0.4) and absf(drunk - (1.0 + 0.4 * 0.6) * 1.15) < 0.001 and absf(both - 0.76) < 0.001
			and is_equal_approx(b.relief(), 0.6), "sway %.3f, relief %.2f / %.2f / %.2f" % [drunk, eased, both, b.relief()])
	b = BodySim.new(7)
	b.hit_part(LEG_L, 20.0, CRUSH)
	b.hit_part(LEG_L, 20.0, CRUSH)
	b.hit_part(LEG_R, 30.0, CUT)
	var was := b.tier(LEG_L)
	_check("body: a splint makes a crushed limb count one step better; it does nothing for a cut", was == HEAVY
			and b.splint(LEG_L) and b.tier(LEG_L) == LIGHT and b.mods.can_sprint and not b.splint(LEG_R) and not b.splint(TORSO))
	b = BodySim.new(7)
	b.hit_part(ARM_L, 30.0, CUT)
	b.hit_part(ARM_L, 30.0, CUT)
	_check("body: a first aid kit stops all bleeding on a part and gives 40 function back", b.first_aid(ARM_L)
			and is_zero_approx(b.bleed_rate()) and is_equal_approx(b.function[ARM_L], 50.0))
	b = BodySim.new(7)
	b.coffee()
	var perky := b.mods
	b.advance(91.0)
	_check("body: coffee brings stamina back 1.5x faster for 90 s, with slightly shakier hands",
			is_equal_approx(perky.stamina_regen_scale, 1.5) and is_equal_approx(perky.sway_scale, 1.1)
			and is_equal_approx(b.mods.stamina_regen_scale, 1.0))

	# Electric burns.
	b = BodySim.new(7)
	var drops: Array = []
	b.dropped_item.connect(func() -> void: drops.append(1))
	var burn1: Dictionary = b.hit_part(TORSO, 8.0, BURN)
	var shaking := b.mods.sway_scale
	b.advance(8.5)
	var calm := b.mods.sway_scale
	var burn3: Dictionary = b.hit_part(TORSO, 26.0, BURN)
	b.advance(24.0)
	var late := b.mods.sway_scale
	b.advance(2.0)
	_check("body: a burn shakes the hands for 8 / 25 s by level, and a bad one always empties them",
			is_equal_approx(shaking, 1.6) and is_equal_approx(calm, 1.0) and b.wounds.is_empty() and not burn1["dropped"]
			and burn3["dropped"] and drops.size() == 1 and is_equal_approx(late, 1.6) and is_equal_approx(b.mods.sway_scale, 1.0))

	# Where attacks land.
	b = BodySim.new(7)
	var share := func(attack: BodySim.Attack, aiming: bool) -> Array:
		var counts := [0, 0, 0, 0, 0, 0]
		for i in 4000:
			b.reset()
			counts[b.hit(1.0, CUT, attack, aiming)["part"]] += 1
		return counts.map(func(c: int) -> float: return c / 4000.0)
	var bite: Array = share.call(BodySim.Attack.LOW_BITE, false)
	var pounce: Array = share.call(BodySim.Attack.POUNCE, false)
	var pounce_aim: Array = share.call(BodySim.Attack.POUNCE, true)
	var lash: Array = share.call(BodySim.Attack.LASH, false)
	_check("body: a low bite goes for the legs 70% of the time", absf(bite[LEG_L] + bite[LEG_R] - 0.70) < 0.03
			and absf(bite[ARM_L] - 0.20) < 0.03 and absf(bite[TORSO] - 0.10) < 0.03 and bite[HEAD] == 0.0, str(bite))
	_check("body: a pounce lands on the arms half the time, 65% if they are up aiming", absf(pounce[ARM_L] + pounce[ARM_R] - 0.50) < 0.03
			and absf(pounce_aim[ARM_L] + pounce_aim[ARM_R] - 0.65) < 0.03 and absf(pounce[TORSO] - 0.40) < 0.03,
			"%.2f / %.2f" % [pounce[ARM_L] + pounce[ARM_R], pounce_aim[ARM_L] + pounce_aim[ARM_R]])
	_check("body: a lash from above finds the head 30% of the time", absf(lash[HEAD] - 0.30) < 0.03, str(lash))


# Injuries acting on the real player and weapons.
func _test_body_in_scene() -> void:
	var body := player.body
	await _select(0)
	await _place(Vector3(-0.9, 0.05, 2.0), 0.0)
	_check("body: the player has one, unhurt", body != null and body.sim.wounds.is_empty() and player.stamina_cap() == 100.0)

	# Legs.
	body.hit_part(BodySim.Part.LEG_L, 15.0, BodySim.Kind.CRUSH)
	_hold(&"move_forward")
	_hold(&"sprint")
	await _secs(1.5)
	_check("body: a lightly hurt leg sprints at 5.8 m/s", player.sprinting and absf(player.horizontal_speed() - 5.8) < 0.2,
			"%.2f m/s" % player.horizontal_speed())
	body.hit_part(BodySim.Part.LEG_L, 15.0, BodySim.Kind.CRUSH)
	await _secs(1.0)
	_check("body: badly hurt, holding sprint only walks, at 3.4 m/s", not player.sprinting
			and absf(player.horizontal_speed() - 3.4) < 0.15, "%.2f m/s, tier %d" % [player.horizontal_speed(),
			body.sim.tier(BodySim.Part.LEG_L)])
	await _tap(&"crouch")
	await _secs(0.1)
	_check("body: and crouch does not slide", player.mode == Player.Mode.NORMAL, "mode %d" % player.mode)
	_release_all()
	await _secs(0.4)

	# A leg that bad still gets over a low barricade, just slowly.
	await _place(Vector3(-1.75, 0.05, -19.6), 0.0)
	body.hit_part(BodySim.Part.LEG_L, 30.0, BodySim.Kind.CRUSH)
	body.hit_part(BodySim.Part.LEG_L, 15.0, BodySim.Kind.CRUSH)
	_hold(&"move_forward")
	await _secs(0.3)
	await _tap(&"jump")
	await _secs(0.08)
	var slow := player._mantle_time
	await _secs(2.2)
	_release_all()
	await _secs(0.4)
	_check("body: a badly hurt leg still vaults a barricade, half as fast again", player.global_position.z < -21.4
			and player.is_on_floor() and slow > 0.5, "%s, %.2f s" % [_pos(), slow])

	await _place(Vector3(-0.9, 0.05, 2.0), 0.0)
	for i in 3:
		body.hit_part(BodySim.Part.LEG_R, 30.0, BodySim.Kind.CRUSH)
	await _tap(&"jump")
	await _secs(0.15)
	var hopped := not player.is_on_floor()
	_hold(&"move_forward")
	await _secs(1.2)
	_check("body: a useless leg cannot jump and drags along at 2.4 m/s", not hopped and absf(player.horizontal_speed() - 2.4) < 0.15,
			"%.2f m/s" % player.horizontal_speed())
	_release_all()

	# Torso.
	await _place(Vector3(-0.9, 0.05, 2.0), 0.0)
	body.hit_part(BodySim.Part.TORSO, 30.0, BodySim.Kind.CRUSH)
	await _secs(0.2)
	_check("body: a hurt torso caps stamina at 80", absf(player.stamina - 80.0) < 0.01 and player.stamina_cap() == 80.0,
			"%.1f" % player.stamina)

	# Support arm: reloading and pumping.
	await _place(Vector3(-0.9, 0.05, 2.0), 180.0)
	await _wait_ready()
	player.set_look(deg_to_rad(180.0), deg_to_rad(40.0))
	await _tap(&"fire")
	await _secs(0.3)
	body.hit_part(BodySim.Part.ARM_L, 15.0, BodySim.Kind.CRUSH)
	await _tap(&"reload")
	var rest := await _wait_ready()
	_check("body: a lightly hurt support arm reloads the pistol in 1.55 x 1.25 s", absf(0.03 + rest - 1.55 * 1.25) < 0.06
			and rig.current.mag == 15, "%.2f s" % (0.03 + rest))
	await _select(1)
	await _tap(&"fire")
	rest = await _wait_ready()
	var pump_time: float = rig.current.data.pump_time
	_check("body: and pumps the shotgun 1.3x slower", absf(0.03 + rest - pump_time * 1.3) < 0.06, "%.2f s, base %.2f" % [0.03 + rest, pump_time])

	# Gun arm: aiming.
	await _select(0)
	body.reset()
	_hold(&"aim")
	var sound := 0.0
	while rig.aim < 0.999 and sound < 2.0:
		await get_tree().physics_frame
		sound += 1.0 / TICKS
	_release(&"aim")
	await _secs(0.5)
	body.hit_part(BodySim.Part.ARM_R, 15.0, BodySim.Kind.CRUSH)
	body.hit_part(BodySim.Part.ARM_R, 15.0, BodySim.Kind.CRUSH)
	_hold(&"aim")
	var hurt := 0.0
	while rig.aim < 0.999 and hurt < 2.0:
		await get_tree().physics_frame
		hurt += 1.0 / TICKS
	await _secs(0.3)
	# Compared as a difference: both times carry the same frame or two of slack,
	# which a ratio would magnify when frames are long.
	var extra: float = rig.current.data.aim_time * 0.35
	_check("body: a badly hurt gun arm takes 1.35x as long to aim, and the aim wanders", absf(hurt - sound - extra) < 0.03
			and player.view_wobble.length() > 0.0, "%.3f s vs %.3f s, wobble %.4f" % [hurt, sound, player.view_wobble.length()])
	_release(&"aim")

	# Out loud.
	body.reset()
	var voices: Array = []
	var listen := func(_p: Vector3, r: float, _e: float, src: StringName, _o: float) -> void: voices.append([src, r])
	NoiseBus.heard.connect(listen)
	await _select(2)
	var cut: int = body.hit_part(BodySim.Part.ARM_R, 20.0, BodySim.Kind.CUT)["wound"]
	await _secs(0.2)
	voices.clear()
	await _tap(&"fire")
	await _secs(0.3)
	_check("body: swinging with a wounded arm draws a grunt heard 3 m away", voices.has([&"grunt", 3.0]), str(voices))
	body.sim.pour_whiskey(cut)
	await _secs(0.1)
	_check("body: pouring whiskey on a wound is heard 8 m away", voices.has([&"scream", 8.0]), str(voices))
	NoiseBus.heard.disconnect(listen)

	# Dying.
	await _wait_ready()
	body.reset()
	var deaths: Array = []
	var on_died := func(c: StringName) -> void: deaths.append(c)
	body.died.connect(on_died)
	body.hit_part(BodySim.Part.HEAD, 40.0, BodySim.Kind.CRUSH)
	_hold(&"move_forward")
	await _secs(0.8)
	_check("body: a dead player does not move", deaths == [&"head"] and player.horizontal_speed() < 0.05
			and player.head.position.y < 1.0, "%s, %.2f m/s, eye %.2f" % [deaths, player.horizontal_speed(), player.head.position.y])
	body.died.disconnect(on_died)
	_release_all()
	body.reset()
	await _select(0)
	await _place(Vector3(-0.9, 0.05, 2.0), 0.0)
	_hold(&"move_forward")
	_hold(&"sprint")
	await _secs(1.5)
	_check("body: healed, everything is back to normal", absf(player.horizontal_speed() - 6.6) < 0.2, "%.2f m/s" % player.horizontal_speed())
	_release_all()


# --- creatures -----------------------------------------------------------------

## Fresh street with the sets asleep on it, the player parked out of their sight.
func _creature_setup() -> Array[Creature]:
	scene.bake_nav()
	scene.build_grid(true)
	await _place(Vector3(-0.9, 0.05, -52.0), 0.0)
	await _secs(0.4)
	return scene.creatures


func _wait_state(c: Creature, want: Creature.State, limit: float) -> float:
	var t := 0.0
	while c.state != want and t < limit:
		await get_tree().physics_frame
		t += 1.0 / TICKS
	return t


## Puts a grid cell at a charge, as if it had just been disturbed. (Injecting
## exact amounts is no good for thresholds: charge starts leaking to the
## neighbouring cells at once.)
func _charge(node: int, value: float) -> void:
	Grid.sim.cells[node].charge = value
	Grid.sim.cells[node].idle = 0.0


func _flat_distance(a: Vector3, b: Vector3) -> float:
	return Vector2(a.x - b.x, a.z - b.z).length()


func _test_creatures() -> void:
	const S := Creature.State
	var crts: Array[Creature] = await _creature_setup()
	var region: NavigationRegion3D = scene.nav_region
	var polygons: int = region.navigation_mesh.get_polygon_count() if region != null else 0
	_check("creature: the street has a navigation mesh", polygons > 50, "%d polygons" % polygons)
	var asleep := true
	for c in crts:
		asleep = asleep and c.state == S.DORMANT and c.is_on_floor() and not c._legs[0].visible
	_check("creature: two sets sleep by each of the four lamps, legs tucked away", crts.size() == 8 and asleep
			and Grid.sim.count() == 8 and Grid.sim.count(true) == 0, "%d" % crts.size())

	# Asleep: it does not move, and its screen follows the grid.
	var c := crts[0]                                # lamp 1, the one that wakes at 70
	var node := c.grid_node
	var rest := c.global_position
	NoiseBus.emit(rest + Vector3(-3.0, 0.0, 0.0), 30.0, 0.0, &"test")
	await _secs(1.0)
	_check("creature: asleep it ignores noise and stays put", c.state == S.DORMANT and c.global_position.distance_to(rest) < 0.01
			and c._power < 0.05, "moved %.3f" % c.global_position.distance_to(rest))
	_charge(node, 45.0)
	await _secs(0.5)
	_check("creature: at 35 on its cell the screen fills with snow, and it sleeps on", c.state == S.DORMANT and c._power > 0.6
			and c._glow.visible, "power %.2f" % c._power)

	# Woken by the grid, it goes to the last noise it heard, round whatever is in the way.
	var noise_at := Vector3(1.3, 0.0, -24.5)        # behind the car and a road barricade from where it sits
	NoiseBus.emit(noise_at, 40.0, 0.0, &"test")
	_charge(node, 74.0)
	await _secs(0.15)
	_check("creature: at 70 the first set wakes and the second (80) does not", c.state == S.WAKING
			and crts[1].state == S.DORMANT and Grid.sim.count(true) == 1, "states %d %d" % [c.state, crts[1].state])
	var took := 0.15 + await _wait_state(c, S.INVESTIGATE, 3.0)
	_check("creature: it takes 1.3 s to get up", absf(took - 1.3) < 0.1 and c._legs[0].visible and c._rise > 0.5, "%.2f s" % took)
	var straight := _flat_distance(c.global_position, noise_at)
	var walked := 0.0
	var last := c.global_position
	var t := 0.0
	while _flat_distance(c.global_position, noise_at) > 1.2 and t < 25.0:
		await _secs(0.25)
		t += 0.25
		_charge(node, 74.0)                         # or the cell cools and it dozes off on the way
		walked += _flat_distance(c.global_position, last)
		last = c.global_position
	_check("creature: it walks to the noise", _flat_distance(c.global_position, noise_at) <= 1.2 and c.state == S.INVESTIGATE,
			"%.1f m away after %.1f s" % [_flat_distance(c.global_position, noise_at), t])
	_check("creature: and went round the obstacles to get there", walked > straight + 0.5 and walked < straight * 1.8,
			"walked %.1f m for %.1f m as the crow flies" % [walked, straight])

	# Nothing found and the grid quiet again: back to sleep.
	_charge(node, 20.0)
	took = await _wait_state(c, S.DORMANT, 14.0)
	_check("creature: 10 s after the cell goes quiet it sleeps again, screen dark", c.state == S.DORMANT and absf(took - 10.0) < 0.6
			and Grid.sim.count(true) == 0, "%.1f s" % took)
	await _secs(0.6)
	_check("creature: legs gone again", not c._legs[0].visible and c._power < 0.05, "power %.2f" % c._power)

	# Seeing the player: chase, and no more going back to sleep.
	var events: Array = []
	var on_state := func(_who: Creature, st: Creature.State) -> void: events.append([st, Engine.get_physics_frames()])
	var attacks: Array = []
	var on_attack := func(_who: Creature, landed: bool) -> void: attacks.append(landed)
	c.state_changed.connect(on_state)
	c.attacked.connect(on_attack)
	_charge(node, 74.0)
	await _wait_state(c, S.INVESTIGATE, 3.0)
	var ahead := c.global_position - c.global_basis.z * 9.0
	await _place(Vector3(ahead.x, c.global_position.y + 0.05, ahead.z), 0.0)
	var seen := await _wait_state(c, S.CHASE, 2.0)
	_check("creature: it sees a player 9 m in front of it and gives chase", c.state != S.INVESTIGATE and seen < 0.3
			and Grid.sim.cells[node].roster[c.grid_entry]["engaged"], "%.2f s" % seen)
	var wounds := player.body.sim.wounds.size()
	await _wait_state(c, S.RECOVER, 8.0)
	var windup_at := -1
	var lunge_at := -1
	for e in events:
		if e[0] == S.WINDUP and windup_at < 0:
			windup_at = e[1]
		elif e[0] == S.LUNGE and lunge_at < 0:
			lunge_at = e[1]
	var warning := (lunge_at - windup_at) / TICKS
	_check("creature: it winds up for at least half a second before it jumps", windup_at > 0 and lunge_at > windup_at
			and warning >= 0.49 and warning < 0.6, "%.2f s" % warning)
	_check("creature: the jump lands and leaves a wound", attacks == [true] and player.body.sim.wounds.size() == wounds + 1
			and player.body.sim.bleed_rate() > 0.0, "%s, %d wounds" % [str(attacks), player.body.sim.wounds.size()])
	Grid.sim.cells[node].charge = 0.0
	await _secs(0.2)

	# Step out of the way during the wind-up and it misses.
	player.body.reset()
	attacks.clear()
	await _wait_state(c, S.WINDUP, 8.0)
	await _secs(0.35)
	var side := c.global_basis.x * 7.5
	player.global_position += Vector3(side.x, 0.0, side.z)
	await _wait_state(c, S.RECOVER, 3.0)
	_check("creature: a jump at where the player was hits nothing", attacks == [false] and player.body.sim.wounds.is_empty(),
			"%s, %d wounds" % [str(attacks), player.body.sim.wounds.size()])
	_check("creature: having seen the player it stays awake on a dead quiet cell", c.is_awake() and Grid.sim.charge(node) < 30.0)
	c.state_changed.disconnect(on_state)
	c.attacked.disconnect(on_attack)

	# Two layers: casing, glass, and the meat behind the glass.
	crts = await _creature_setup()
	c = crts[2]                                      # lamp 2
	node = c.grid_node
	var pistol_data: WeaponData = rig.weapons[0].data
	var pipe_data: WeaponData = rig.weapons[2].data
	var into := -c.global_basis.z * -1.0             # a shot from the front travels along its +Z
	var r: Dictionary = c.take_hit(34.0, c.shell_point() + Vector3.UP * 0.2, Vector3.DOWN, pistol_data)
	_check("creature: the casing lets 30 % through and sparks", is_equal_approx(r.damage, 10.2) and not r.flesh and not r.head
			and is_equal_approx(c.health, 99.8), str(r))
	_check("creature: being shot wakes it", c.state == S.WAKING and Grid.sim.count(true) == 1)
	r = c.take_hit(55.0, c.shell_point() + Vector3.UP * 0.2, Vector3.DOWN, pipe_data)
	_check("creature: a pipe dents 60 % through", is_equal_approx(r.damage, 33.0) and not r.flesh, str(r))
	r = c.take_hit(34.0, c._body.to_global(Vector3(0.15, -0.2, 0.1)), into, pistol_data)
	_check("creature: the legs are bare meat at 80 %", is_equal_approx(r.damage, 27.2) and r.flesh and not r.head, str(r))
	r = c.take_hit(20.0, c.screen_point(), into, pistol_data)
	_check("creature: the screen is shell until its 30 points of glass are gone", is_equal_approx(r.damage, 6.0) and not r.flesh
			and is_equal_approx(c.glass, 10.0) and c._glass_node.visible, str(r))
	r = c.take_hit(20.0, c.screen_point(), into, pistol_data)
	_check("creature: then the glass breaks and the meat shows", c.glass == 0.0 and not c._glass_node.visible and c._meat_node.visible
			and not c._screen.visible and not r.flesh, str(r))
	var before := c.health
	r = c.take_hit(10.0, c.screen_point(), into, pistol_data)
	_check("creature: through the hole it is the weak point at 150 %", is_equal_approx(r.damage, 15.0) and r.flesh and r.head
			and is_equal_approx(before - c.health, 15.0) and not r.killed, str(r))
	_charge(node, 50.0)
	var deaths: Array = []
	c.died.connect(func(who: Creature) -> void: deaths.append(who))
	r = c.take_hit(34.0, c.screen_point(), into, pistol_data)
	_check("creature: it dies when the meat does", r.killed and c.state == S.DEAD and deaths == [c], str(r))
	_check("creature: its death takes 8 off the cell and it leaves the roster", absf(Grid.sim.charge(node) - 42.0) < 0.3
			and Grid.sim.count() == 7, "%.1f, %d left" % [Grid.sim.charge(node), Grid.sim.count()])
	rest = c.global_position
	_charge(node, 85.0)
	await _secs(1.5)
	_check("creature: the dead stay dead", c.state == S.DEAD and c.take_hit(34.0, c.shell_point(), into, pistol_data).is_empty()
			and _flat_distance(c.global_position, rest) < 0.05 and not c.is_awake())
	_check("creature: its neighbour on the same cell woke at 80 instead", crts[3].state != S.DORMANT, "state %d" % crts[3].state)

	# The whole chain with real bullets: three aimed pistol shots into the screen.
	crts = await _creature_setup()
	c = crts[4]                                      # lamp 3, on the +X pavement, facing the road
	await _select(0)
	await _wait_ready()
	var front := c.global_position - c.global_basis.z * 6.0
	await _place(Vector3(front.x, 0.05, front.z), 0.0)
	_hold(&"aim")
	await _secs(0.4)
	_hits.clear()
	for shot in 3:
		_look_at(c.screen_point())
		await _secs(0.05)
		await _tap(&"fire")
		await _secs(0.22)
	_release(&"aim")
	_check("creature: three aimed pistol shots at the screen kill it", c.state == S.DEAD and _hits.size() == 3
			and _hits[0] == [false, false, 10.2] and _hits[1] == [true, false, 51.0] and _hits[2] == [true, true, 51.0], str(_hits))
	_check("creature: the corpse is scenery now, not a target", c.collision_layer == 1)

	# Overloading the cell until the breaker trips enrages whatever is awake on it.
	c = crts[6]                                      # lamp 4
	node = c.grid_node
	await _place(Vector3(-0.9, 0.05, -40.0), 0.0)
	_charge(node, 75.0)
	await _wait_state(c, S.INVESTIGATE, 3.0)
	for i in 14:                                    # sustained fire: a jolt every 0.4 s for 5.6 s
		Grid.sim.inject_node(node, 30.0)
		await _secs(0.4)
	_check("creature: when its cell trips, one that is awake goes into a rage", Grid.sim.is_tripped(node) and c._rage > 5.0,
			"rage %.1f" % c._rage)

	scene.build_grid()
	await _place(Vector3(-0.9, 0.05, 2.0), 0.0)


# --- screenshots ---------------------------------------------------------------

func _snap(dir: String, shot: String, settle := 8) -> void:
	await _frames(settle)
	await RenderingServer.frame_post_draw
	var path := dir.path_join(shot + ".png")
	get_viewport().get_texture().get_image().save_png(path)
	print("SHOT ", path)


func _shoot(dir: String) -> void:
	DirAccess.make_dir_recursive_absolute(dir)
	# --shots=pistol,night limits the run to some sections.
	var only := PackedStringArray()
	for arg in OS.get_cmdline_user_args():
		if arg.begins_with("--shots="):
			only = arg.trim_prefix("--shots=").split(",")
	await _snap(dir, "00_settle", 70)              # the fog needs about 60 frames to settle
	for section: String in ["pistol", "shotgun", "pipe", "holes", "hurt", "night"]:
		if only.is_empty() or only.has(section):
			await call("_shoot_" + section, dir)


func _shoot_pistol(dir: String) -> void:
	var dummy := targets[0]
	await _select(0)
	await _place(Vector3(-1.2, 0.05, -11.0), 0.0)
	_look_at(dummy.chest_point() + Vector3(0.5, 0.2, 0.0))
	await _snap(dir, "01_pistol_hip", 20)
	_hold(&"aim")
	_look_at(dummy.head_point())
	await _snap(dir, "02_pistol_aim", 30)
	await _tap(&"fire")
	await _snap(dir, "03_pistol_fired", 1)
	_release(&"aim")
	await _secs(0.4)
	await _tap(&"reload")
	await _secs(0.55)
	await _snap(dir, "04_pistol_reload", 1)
	await _wait_ready()
	_hold(&"move_forward")
	_hold(&"sprint")
	await _secs(0.7)
	await _snap(dir, "05_pistol_sprint", 1)
	_release_all()
	dummy.reset()


func _shoot_shotgun(dir: String) -> void:
	var dummy := targets[0]
	await _place(Vector3(-1.2, 0.05, -11.0), 0.0)
	await _select(1)
	_look_at(dummy.chest_point() + Vector3(0.5, 0.2, 0.0))
	await _snap(dir, "06_shotgun_hip", 20)
	_hold(&"aim")
	_look_at(dummy.chest_point())
	await _snap(dir, "07_shotgun_aim", 30)
	await _tap(&"fire")
	await _secs(0.22)
	await _snap(dir, "08_shotgun_pump", 1)
	_release(&"aim")
	await _wait_ready()
	await _tap(&"reload")
	await _secs(0.6)
	await _snap(dir, "09_shotgun_reload", 1)
	await _wait_ready()
	dummy.reset()


func _shoot_pipe(dir: String) -> void:
	var dummy := targets[0]
	await _select(2)
	var chest := dummy.chest_point()
	await _place(Vector3(chest.x + 0.1, 0.05, chest.z + 1.6), 0.0)
	_look_at(dummy.chest_point())
	await _snap(dir, "10_pipe_hip", 20)
	await _tap(&"fire")
	await _secs(0.08)
	await _snap(dir, "11_pipe_windup", 1)
	await _secs(0.10)
	await _snap(dir, "12_pipe_strike", 1)
	await _wait_ready()
	dummy.reset()


## Bullet holes in a car, from close by.
func _shoot_holes(dir: String) -> void:
	await _select(0)
	await _place(Vector3(2.0, 0.05, -10.0), 0.0)
	var car := Vector3(2.9, 0.75, -13.5)
	_hold(&"aim")
	await _secs(0.4)
	for offset: Vector3 in [Vector3(-0.25, 0.1, 0.0), Vector3(0.2, 0.05, 0.0), Vector3(-0.5, -0.3, 0.6),
			Vector3(-0.15, -0.32, 0.7), Vector3(-0.75, -0.36, 0.5)]:
		_look_at(car + offset)
		await _secs(0.1)
		await _tap(&"fire")
		await _secs(0.3)
	_release(&"aim")
	_look_at(car + Vector3(-0.2, -0.15, 0.3))
	await _snap(dir, "13_bullet_holes", 20)


## What losing blood does to the picture: the same view whole, at 40 and at 20.
func _shoot_hurt(dir: String) -> void:
	var dummy := targets[0]
	await _select(0)
	await _place(Vector3(-1.2, 0.05, -11.0), 0.0)
	_look_at(dummy.chest_point() + Vector3(0.5, 0.2, 0.0))
	await _snap(dir, "16_body_whole", 20)
	player.body.hit_part(BodySim.Part.ARM_R, 20.0, BodySim.Kind.CUT)
	player.body.sim.blood = 40.0
	_look_at(dummy.chest_point() + Vector3(0.5, 0.2, 0.0))
	await _snap(dir, "17_body_blood_40", 20)
	player.body.sim.blood = 20.0
	_look_at(dummy.chest_point() + Vector3(0.5, 0.2, 0.0))
	await _snap(dir, "18_body_blood_20", 20)
	player.body.reset()


## Night, power out, flashlight.
func _shoot_night(dir: String) -> void:
	var dummy := targets[0]
	await _select(0)
	await _place(Vector3(-1.2, 0.05, -11.0), 0.0)
	scene.atmosphere.power_on = false
	scene.atmosphere.time_of_day = 23.0
	await _tap(&"flashlight")
	_look_at(dummy.chest_point() + Vector3(0.4, 0.1, 0.0))
	await _snap(dir, "14_blackout_pistol", 70)
	await _tap(&"fire")
	await _snap(dir, "15_blackout_fired", 1)

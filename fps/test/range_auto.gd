class_name RangeAuto
extends Node
## Scripted runs of the shooting range, driven through the real input path:
##
##   --selftest      walks, jumps, climbs and shoots, checks the results and
##                   exits with 0 if everything passed
##   --shoot=<dir>   poses each weapon and saves screenshots for review
##                   (--shots=pistol,shotgun,pipe,holes,night picks sections)
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
	await _test_movement()
	await _test_climbing()
	await _test_pistol()
	await _test_shotgun()
	await _test_pipe()
	await _test_rig()


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
	for section: String in ["pistol", "shotgun", "pipe", "holes", "night"]:
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
	for i in 5:
		_look_at(car + Vector3(randf_range(-0.3, 0.3), randf_range(-0.15, 0.15), 0.0))
		await _tap(&"fire")
		await _secs(0.25)
	_look_at(car)
	await _snap(dir, "13_bullet_holes", 20)


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

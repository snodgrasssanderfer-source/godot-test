class_name Player
extends CharacterBody3D
## First-person controller with modern-shooter mobility: snappy acceleration,
## sprint, crouch, slide (and slide-cancel), jump with coyote time, and
## mantling / vaulting over ledges and through broken windows.

const WALK := 4.4
const SPRINT := 6.5
const CROUCH := 2.2
const ACCEL := 52.0
const AIR_ACCEL := 9.0
const GRAVITY := 17.0
const JUMP_V := 5.4
const STAND_H := 1.8
const CROUCH_H := 1.05
const EYE_STAND := 1.62
const EYE_CROUCH := 0.95
const EYE_SLIDE := 0.7
const BASE_FOV := 74.0
const LOOK := 0.0022

var head: Node3D
var cam: Camera3D
var weapons: WeaponSystem
var surv: Survival
var inv := Inventory.new()
var flashlight: SpotLight3D
var _shape: CollisionShape3D
var _capsule: CapsuleShape3D

var yaw := 0.0
var pitch := 0.0
var crouching := false
var sprinting := false
var sliding := false
var mantling := false
var dead := false
var sleeping := false
var indoor := false
var flash_on := false
var battery := 100.0
var look_vel := Vector2.ZERO        # smoothed look speed (for weapon sway)

var _slide_t := 0.0
var _slide_dir := Vector3.ZERO
var _slide_cd := 0.0
var _coyote := 0.0
var _jump_buf := 0.0
var _eye := EYE_STAND
var _bob := 0.0
var _dip := 0.0
var _dip_v := 0.0
var _roll := 0.0
var _shake := 0.0
var _step := 0.0
var _was_floor := true
var _fall_v := 0.0
var _hit_slow := 0.0
var _mantle_t := 0.0
var _mantle_dur := 0.45
var _m_from := Vector3.ZERO
var _m_mid := Vector3.ZERO
var _m_to := Vector3.ZERO
var _indoor_t := 0.0
var _focus: Object = null
var _flick_t := 0.0
var _ads_was := false


func _ready() -> void:
	collision_layer = G.L_PLAYER
	collision_mask = G.L_WORLD | G.L_GLASS | G.L_ZOMBIE
	floor_snap_length = 0.25
	_shape = CollisionShape3D.new()
	_capsule = CapsuleShape3D.new()
	_capsule.radius = 0.34
	_capsule.height = STAND_H
	_shape.shape = _capsule
	_shape.position.y = STAND_H * 0.5
	add_child(_shape)
	head = Node3D.new()
	head.position.y = EYE_STAND
	add_child(head)
	cam = Camera3D.new()
	cam.fov = BASE_FOV
	cam.near = 0.02
	cam.far = 110.0
	cam.current = true
	head.add_child(cam)
	flashlight = SpotLight3D.new()
	flashlight.position = Vector3(0.1, -0.08, -0.35)
	flashlight.spot_range = 26.0
	flashlight.spot_angle = 27.0
	flashlight.spot_angle_attenuation = 0.7
	flashlight.spot_attenuation = 1.1
	flashlight.light_color = Color(1.0, 0.96, 0.82)
	flashlight.light_energy = 0.0
	flashlight.shadow_enabled = not G.low_spec
	flashlight.visible = false
	cam.add_child(flashlight)
	surv = Survival.new()
	surv.p = self
	add_child(surv)
	weapons = WeaponSystem.new()
	weapons.p = self
	cam.add_child(weapons)


func starting_kit() -> void:
	weapons.give("crowbar")
	weapons.give("k45", 8)
	inv.add("a45", 16)
	inv.add("bandage", 2)
	inv.add("water", 1)
	inv.add("beans", 1)
	weapons.switch_to(1, true)


func h_speed() -> float:
	return Vector2(velocity.x, velocity.z).length()


func total_weight() -> float:
	return inv.weight() + weapons.weight()


func over_weight() -> float:
	return total_weight() / Inventory.CAPACITY


func can_barricade() -> bool:
	return inv.has("plank") and inv.has("hammer")


func _process(delta: float) -> void:
	if dead or not G.playing:
		return
	if not sleeping:
		surv.tick(delta)
	_flashlight(delta)


func _physics_process(delta: float) -> void:
	if dead or sleeping or not G.playing:
		G.look_delta = Vector2.ZERO
		return
	_look(delta)
	if mantling:
		_mantle_step(delta)
		_camera(delta)
		return
	var locked := G.ui_open
	var iv := Vector2.ZERO
	if not locked:
		iv = Input.get_vector("move_left", "move_right", "move_fwd", "move_back") + G.touch_move
		if iv.length() > 1.0:
			iv = iv.normalized()
	var wish := (global_transform.basis * Vector3(iv.x, 0, iv.y))
	wish.y = 0.0
	var on_floor := is_on_floor()
	_slide_cd -= delta
	_hit_slow -= delta
	_jump_buf -= delta
	_coyote = 0.12 if on_floor else _coyote - delta

	var has_gun := weapons.has_gun()
	var busy_aim := not locked and (Input.is_action_pressed("ads") or (Input.is_action_pressed("fire") and has_gun)) and not weapons.is_melee()
	var want_sprint := not locked and Input.is_action_pressed("sprint") and iv.y < -0.3
	if not locked and Input.is_action_just_pressed("crouch"):
		if sprinting and on_floor and h_speed() > 5.0 and _slide_cd <= 0.0:
			_start_slide()
		elif not sliding:
			if crouching:
				if _headroom():
					crouching = false
			else:
				crouching = true
	if want_sprint and crouching and not sliding and _headroom():
		crouching = false
	sprinting = want_sprint and not crouching and not sliding and not busy_aim and surv.endurance > 2.0 \
		and over_weight() < 1.25 and not weapons.reloading()

	var speed := WALK
	if sprinting:
		speed = SPRINT
	elif crouching:
		speed = CROUCH
	speed *= weapons.move_mult() * surv.move_mult()
	if over_weight() > 1.0:
		speed *= clampf(1.7 - over_weight() * 0.7, 0.45, 1.0)
	if _hit_slow > 0.0:
		speed *= 0.55

	if sliding:
		_slide_t += delta
		var sv := lerpf(8.6, 3.2, clampf(_slide_t / 0.75, 0.0, 1.0))
		velocity.x = _slide_dir.x * sv
		velocity.z = _slide_dir.z * sv
		if _slide_t >= 0.75 or not on_floor:
			sliding = false
			crouching = true
			_slide_cd = 0.35
	else:
		var tv := wish * speed
		var acc := ACCEL if on_floor else AIR_ACCEL
		var hv := Vector3(velocity.x, 0, velocity.z).move_toward(tv, acc * delta)
		velocity.x = hv.x
		velocity.z = hv.z

	if not locked and Input.is_action_just_pressed("jump"):
		_jump_buf = 0.14
	if _jump_buf > 0.0:
		if _try_mantle():
			_jump_buf = 0.0
		elif _coyote > 0.0:
			_jump_buf = 0.0
			_coyote = 0.0
			if sliding:
				sliding = false
				_slide_cd = 0.35
				crouching = false
			elif crouching and _headroom():
				crouching = false
			velocity.y = JUMP_V
			surv.spend(3.0)
			Sfx.play_var("step_%s%d" % [_surface(), randi() % 4], -6.0)
	if not on_floor:
		velocity.y -= GRAVITY * delta
		_fall_v = velocity.y
		# grab a ledge mid-air if still pushing forward into it
		if not locked and Input.is_action_pressed("jump") and iv.y < -0.3 and velocity.y < 2.0:
			_try_mantle()
	move_and_slide()

	if is_on_floor() and not _was_floor:
		_dip_v -= clampf(-_fall_v * 0.35, 0.6, 3.5)
		Sfx.play_var("land", -8.0 + clampf(-_fall_v, 0.0, 12.0) * 0.5)
		G.emit_noise(global_position, 7.0, "step")
		if _fall_v < -13.0:
			surv.health -= (-_fall_v - 13.0) * 6.0
			surv.cause = "摔死"
	_was_floor = is_on_floor()

	# capsule + eye height
	var th := CROUCH_H if (crouching or sliding) else STAND_H
	if absf(_capsule.height - th) > 0.01:
		_capsule.height = move_toward(_capsule.height, th, delta * 6.0)
		_shape.position.y = _capsule.height * 0.5
	var te := EYE_SLIDE if sliding else (EYE_CROUCH if crouching else EYE_STAND)
	_eye = lerpf(_eye, te, clampf(delta * 12.0, 0.0, 1.0))

	_footsteps(delta)
	_camera(delta)
	_interaction(locked)
	_indoor_t -= delta
	if _indoor_t <= 0.0:
		_indoor_t = 0.25
		var now := G.town.is_indoor(global_position)
		if now != indoor:
			indoor = now
			weapons.set_indoor(indoor)
	if G.is_touch:
		_aim_assist()


func _look(delta: float) -> void:
	var d := G.look_delta
	G.look_delta = Vector2.ZERO
	if G.ui_open:
		d = Vector2.ZERO
	var s := LOOK * G.sens * weapons.sens_mult()
	var dy := -d.y * s
	# pulling down against recoil eats the kick instead of moving the aim
	if dy < 0.0 and weapons.recoil_target.y > 0.0:
		var eat := minf(-dy, weapons.recoil_target.y)
		weapons.recoil_target.y -= eat
		weapons.recoil.y = maxf(0.0, weapons.recoil.y - eat)
	yaw -= d.x * s
	pitch = clampf(pitch + dy, -1.5, 1.5)
	rotation.y = yaw
	look_vel = look_vel.lerp(d * s / maxf(delta, 0.001), clampf(delta * 14.0, 0.0, 1.0))


func _camera(delta: float) -> void:
	# landing dip spring
	_dip_v += (-_dip * 90.0 - _dip_v * 14.0) * delta
	_dip += _dip_v * delta
	var sp := h_speed()
	var grounded := is_on_floor() and not sliding
	if grounded and sp > 0.5:
		_bob += delta * sp * 1.9
	var bob_amp := clampf(sp / WALK, 0.0, 1.6) * (0.2 if weapons.ads > 0.5 else 1.0) * (1.0 if grounded else 0.0)
	var bob_y := sin(_bob * 2.0) * 0.022 * bob_amp
	var bob_x := sin(_bob) * 0.016 * bob_amp
	var lv := global_transform.basis.inverse() * velocity
	var roll_t := clampf(-lv.x * 0.006, -0.03, 0.03) + (0.09 if sliding else 0.0)
	_roll = lerpf(_roll, roll_t, clampf(delta * 9.0, 0.0, 1.0))
	_shake = maxf(0.0, _shake - delta * 3.5)
	var sh := Vector3(randf_range(-1, 1), randf_range(-1, 1), randf_range(-1, 1)) * _shake * 0.012
	head.position = Vector3(bob_x, _eye + bob_y + clampf(_dip, -0.35, 0.1), 0)
	head.rotation.x = pitch + weapons.recoil.y + sh.x
	head.rotation.y = weapons.recoil.x + sh.y
	head.rotation.z = _roll + sh.z
	var fov := BASE_FOV + (5.0 if sprinting else 0.0) + (4.0 if sliding else 0.0)
	fov = lerpf(fov, BASE_FOV / weapons.zoom(), weapons.ads_eased())
	cam.fov = lerpf(cam.fov, fov, clampf(delta * 14.0, 0.0, 1.0))


func shake(a: float) -> void:
	_shake = minf(1.6, _shake + a)


func _start_slide() -> void:
	sliding = true
	crouching = true
	_slide_t = 0.0
	_slide_dir = G.flat(velocity).normalized()
	surv.spend(5.0)
	Sfx.play_var("slide_move", -6.0)
	G.emit_noise(global_position, 9.0, "step")


func _headroom() -> bool:
	var q := PhysicsRayQueryParameters3D.create(global_position + Vector3.UP * 0.9, global_position + Vector3.UP * (STAND_H + 0.1), G.L_WORLD)
	q.exclude = [get_rid()]
	return get_world_3d().direct_space_state.intersect_ray(q).is_empty()


func _ray(from: Vector3, to: Vector3, mask: int) -> Dictionary:
	var q := PhysicsRayQueryParameters3D.create(from, to, mask)
	q.exclude = [get_rid()]
	return get_world_3d().direct_space_state.intersect_ray(q)


## Ledge / window detection. Starts the climb and returns true if possible.
func _try_mantle() -> bool:
	if mantling or sliding:
		return false
	var fwd := -global_transform.basis.z
	fwd.y = 0.0
	fwd = fwd.normalized()
	var feet := global_position
	var solid := G.L_WORLD | G.L_GLASS
	var hit := _ray(feet + Vector3.UP * 0.45, feet + Vector3.UP * 0.45 + fwd * 0.95, solid)
	if hit.is_empty():
		hit = _ray(feet + Vector3.UP * 1.0, feet + Vector3.UP * 1.0 + fwd * 0.95, solid)
	if hit.is_empty():
		return false
	var hp: Vector3 = hit["position"]
	var probe := hp + fwd * 0.12
	var top := _ray(Vector3(probe.x, feet.y + 2.05, probe.z), Vector3(probe.x, feet.y + 0.3, probe.z), solid)
	if top.is_empty():
		return false
	var top_y: float = top["position"].y - feet.y
	if top_y < 0.4 or top_y > 1.3:
		return false
	# room to pass over the top (crouched)?
	var c0 := Vector3(feet.x, feet.y + top_y + 0.3, feet.z)
	if not _ray(c0, c0 + fwd * 1.6, solid).is_empty():
		return false
	c0.y = feet.y + top_y + 0.85
	if not _ray(c0, c0 + fwd * 1.6, solid).is_empty():
		return false
	# thin obstacle with floor behind it -> vault over; otherwise climb on top
	var beyond := hp + fwd * 1.1
	var down := _ray(Vector3(beyond.x, feet.y + top_y + 0.4, beyond.z), Vector3(beyond.x, feet.y - 0.6, beyond.z), G.L_WORLD)
	var land_y: float = down["position"].y if not down.is_empty() else feet.y
	_m_from = feet
	if land_y - feet.y < top_y - 0.3:
		_m_to = Vector3(beyond.x, land_y + 0.02, beyond.z)
		_mantle_dur = 0.42 + top_y * 0.12
	else:
		var on_top := hp + fwd * 0.5
		_m_to = Vector3(on_top.x, feet.y + top_y + 0.02, on_top.z)
		_mantle_dur = 0.34 + top_y * 0.16
	_m_mid = Vector3((feet.x + _m_to.x) * 0.5, feet.y + top_y + 0.12, (feet.z + _m_to.z) * 0.5)
	mantling = true
	_mantle_t = 0.0
	crouching = true
	sprinting = false
	velocity = Vector3.ZERO
	surv.spend(4.0)
	weapons.on_mantle(_mantle_dur)
	Sfx.play_var("land", -12.0, 0.15)
	return true


func _mantle_step(delta: float) -> void:
	_mantle_t += delta / _mantle_dur
	var q := clampf(_mantle_t, 0.0, 1.0)
	var e := q * q * (3.0 - 2.0 * q)
	var a := _m_from.lerp(_m_mid, e)
	var b := _m_mid.lerp(_m_to, e)
	global_position = a.lerp(b, e)
	_eye = lerpf(_eye, EYE_CROUCH - 0.1, clampf(delta * 14.0, 0.0, 1.0))
	if q >= 1.0:
		mantling = false
		var fwd := -global_transform.basis.z
		velocity = Vector3(fwd.x, 0, fwd.z) * 2.5
		_dip_v -= 0.8
		if _headroom():
			crouching = false
		G.emit_noise(global_position, 6.0, "step")


func _surface() -> String:
	return G.town.surface_at(global_position)


func _footsteps(delta: float) -> void:
	if not is_on_floor() or sliding:
		return
	var sp := h_speed()
	if sp < 0.8:
		return
	_step += sp * delta
	var stride := 2.3 if sprinting else (1.5 if crouching else 1.9)
	if _step >= stride:
		_step = 0.0
		var vol := -3.0 if sprinting else (-16.0 if crouching else -9.0)
		Sfx.play_var("step_%s%d" % [_surface(), randi() % 4], vol, 0.1)
		G.emit_noise(global_position, 11.0 if sprinting else (1.6 if crouching else 5.5), "step")


func _interaction(locked: bool) -> void:
	_focus = null
	if locked:
		G.hud.set_prompt("")
		return
	var from := cam.global_position
	var hit := _ray(from, from - cam.global_transform.basis.z * 2.5, G.L_WORLD | G.L_INTERACT | G.L_GLASS)
	var text := ""
	if not hit.is_empty() and hit["collider"].has_method("get_prompt"):
		_focus = hit["collider"]
		text = _focus.get_prompt(self)
	G.hud.set_prompt(text)
	if _focus == null:
		return
	if Input.is_action_just_pressed("interact") and _focus.has_method("interact"):
		_focus.interact(self)
	elif Input.is_action_just_pressed("barricade") and _focus.has_method("barricade"):
		if not can_barricade():
			G.msg("加固需要木板和锤子。", Color(0.8, 0.7, 0.5))
			Sfx.play("deny")
		elif _focus.barricade(self):
			inv.remove("plank", 1)
			surv.spend(8.0)


func _flashlight(delta: float) -> void:
	if not G.ui_open and Input.is_action_just_pressed("flashlight"):
		if battery <= 0.0 and not flash_on:
			G.msg("手电筒没电了。", Color(0.8, 0.7, 0.5))
			Sfx.play("deny")
		else:
			flash_on = not flash_on
			Sfx.play("flashlight")
	if flash_on:
		battery = maxf(0.0, battery - delta * (100.0 / 540.0))
		if battery <= 0.0:
			flash_on = false
			G.msg("手电筒闪了两下，灭了。", Color(0.8, 0.7, 0.5))
	flashlight.visible = flash_on
	if flash_on:
		var e := 2.6
		if battery < 18.0:
			_flick_t -= delta
			if _flick_t <= 0.0:
				_flick_t = randf_range(0.03, 0.5)
				flashlight.light_energy = e * (randf_range(0.1, 0.5) if randf() < 0.4 else 0.8)
		else:
			flashlight.light_energy = e


## Light touch-screen aim assist: when you start aiming, snap a little
## towards the nearest zombie that is already close to the crosshair.
func _aim_assist() -> void:
	var a := Input.is_action_pressed("ads")
	if a and not _ads_was:
		var fwd := -cam.global_transform.basis.z
		var best: Zombie = null
		var bdot := cos(deg_to_rad(9.0))
		for z: Zombie in G.zombies.active:
			if not is_instance_valid(z) or z.state == Zombie.S.DEAD:
				continue
			var to := (z.global_position + Vector3.UP * 1.35 - cam.global_position)
			if to.length() > 30.0:
				continue
			var dt := fwd.dot(to.normalized())
			if dt > bdot:
				bdot = dt
				best = z
		if best:
			var to2 := best.global_position + Vector3.UP * 1.35 - cam.global_position
			var ty := atan2(-to2.x, -to2.z)
			var tp := atan2(to2.y, Vector2(to2.x, to2.z).length())
			yaw = lerp_angle(yaw, ty, 0.7)
			pitch = lerpf(pitch, tp, 0.7)
	_ads_was = a


# --- damage / death / sleep ------------------------------------------------------------
func take_zombie_hit(z: Zombie, dmg: float) -> void:
	if dead:
		return
	shake(1.0)
	_hit_slow = 0.6
	Sfx.play_var("hurt%d" % (randi() % 2), 0.0)
	Sfx.play_var("hit_flesh", -4.0)
	G.hud.damage_from(z.global_position)
	surv.zombie_hit(dmg)


func die(cause: String) -> void:
	if dead:
		return
	dead = true
	surv.cause = cause
	flashlight.visible = false
	G.main.end_run(false)


func try_sleep() -> void:
	if G.zombies.count_near(global_position, 16.0) > 0:
		G.msg("附近有动静。现在睡下就再也醒不过来了。", Color(0.85, 0.5, 0.4))
		Sfx.play("deny")
		return
	if surv.fatigue < 22.0:
		G.msg("你还不困。")
		return
	if surv.bleeding():
		G.msg("你在流血。先包扎。", Color(0.85, 0.5, 0.4))
		return
	sleeping = true
	var hours := clampf(surv.fatigue / 11.0, 2.0, 8.0)
	G.hud.fade_to(1.0, 1.2)
	await get_tree().create_timer(1.6).timeout
	if dead:
		return
	G.clock += hours * 60.0
	surv.sleep(hours)
	G.zombies.time_skip(hours)
	G.hud.fade_to(0.0, 1.5)
	sleeping = false
	G.msg("你睡了 %d 个小时。现在是第 %d 天 %s。" % [int(hours), G.day(), G.clock_text()])
	if surv.health <= 0.0:
		die(surv.cause)

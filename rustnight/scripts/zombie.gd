class_name Zombie
extends CharacterBody3D
## One active zombie: perception (sight + hearing), grid path following,
## door bashing / window climbing, attacks, and a procedural segmented-body
## animation. Far-away zombies are not nodes at all (see ZombieManager).

enum S { IDLE, WANDER, INVESTIGATE, CHASE, WINDUP, BREAK, VAULT, STAGGER, SCREAM, DEAD }

const KINDS := {
	"walker": {"hp": 100.0, "walk": 0.85, "run": 2.0, "dmg": [8.0, 14.0], "atlas": [0, 1, 3], "scale": 1.0},
	"runner": {"hp": 70.0, "walk": 1.3, "run": 5.3, "dmg": [7.0, 12.0], "atlas": [1, 3], "scale": 0.94},
	"screamer": {"hp": 130.0, "walk": 0.8, "run": 2.5, "dmg": [10.0, 16.0], "atlas": [2], "scale": 1.08},
}

var kind := "walker"
var cfg: Dictionary
var hp := 100.0
var state: S = S.IDLE
var prev_state: S = S.IDLE
var state_t := 0.0
var target := Vector3.ZERO
var hurry := false
var path := PackedVector3Array()
var path_i := 0
var repath_t := 0.0
var think_t := 0.0
var seen_t := 99.0
var last_known := Vector3.ZERO
var attack_cd := 0.0
var portal: Node = null
var bash_t := 0.0
var idle_for := 3.0
var sep := Vector3.ZERO
var move_dir := Vector3.ZERO
var knock := Vector3.ZERO
var slow_t := 0.0
var scream_cd := 0.0
var moan_t := 3.0
var stuck_t := 0.0
var stuck_ref := Vector3.ZERO
var indoor := false
var vault_from := Vector3.ZERO
var vault_to := Vector3.ZERO
var model: Node3D
var parts := {}
var anim := 0.0
var seed_f := 0.0
var facing := 0.0
var _fall := 0.0
var _fall_dir := 1.0
var _corpse_t := 0.0
var _shape: CollisionShape3D


func setup(p_kind: String, pos: Vector3, p_hp: float = -1.0) -> void:
	kind = p_kind
	cfg = KINDS[kind]
	hp = p_hp if p_hp > 0.0 else float(cfg["hp"])
	seed_f = randf() * 100.0
	position = Vector3(pos.x, 0.0, pos.z)
	facing = randf() * TAU
	collision_layer = G.L_ZOMBIE
	collision_mask = G.L_WORLD | G.L_GLASS
	motion_mode = CharacterBody3D.MOTION_MODE_FLOATING
	_shape = CollisionShape3D.new()
	var cap := CapsuleShape3D.new()
	cap.radius = 0.3
	cap.height = 1.7
	_shape.shape = cap
	_shape.position.y = 0.92
	add_child(_shape)
	model = load("res://models/zombie.glb").instantiate()
	var atl: Array = cfg["atlas"]
	Mat.skin(model, Mat.atlas("zombie%d" % atl[randi() % atl.size()]), G.VIS_OUT, false)
	var sc: float = float(cfg["scale"]) * randf_range(0.95, 1.05)
	model.scale = Vector3(sc * randf_range(0.92, 1.08), sc, sc)
	add_child(model)
	for n in ["torso", "head", "stump", "uarm_l", "uarm_r", "farm_l", "farm_r", "thigh_l", "thigh_r", "shin_l", "shin_r"]:
		parts[n] = model.find_child(n, true, false)
	parts["stump"].visible = false
	idle_for = randf_range(1.0, 6.0)
	think_t = randf() * 0.3
	moan_t = randf_range(2.0, 12.0)
	stuck_ref = position


func _set_state(s: S) -> void:
	if state == S.DEAD:
		return
	prev_state = state
	state = s
	state_t = 0.0


func _physics_process(delta: float) -> void:
	if state == S.DEAD:
		_dead_process(delta)
		return
	state_t += delta
	attack_cd -= delta
	scream_cd -= delta
	slow_t -= delta
	seen_t += delta
	think_t -= delta
	if think_t <= 0.0:
		think_t = 0.22 + randf() * 0.08
		_think()
	var speed := 0.0
	var want := Vector3.ZERO
	var pl := G.player
	match state:
		S.IDLE:
			if state_t > idle_for:
				_pick_wander()
		S.WANDER, S.INVESTIGATE:
			want = _nav_dir(delta)
			speed = float(cfg["run"]) * 0.8 if hurry else float(cfg["walk"]) * (1.3 if state == S.INVESTIGATE else 1.0)
			if G.flat(target - global_position).length() < 1.3 or (path_i >= path.size() and state_t > 1.0 and want == Vector3.ZERO):
				idle_for = randf_range(3.0, 9.0)
				hurry = false
				_set_state(S.IDLE)
		S.CHASE:
			target = pl.global_position if seen_t < 1.5 else last_known
			var d := G.flat(pl.global_position - global_position).length()
			if d > 0.95:
				if seen_t < 0.5 and d < 7.0 and not indoor and not pl.indoor:
					want = G.flat(pl.global_position - global_position).normalized()
				else:
					want = _nav_dir(delta)
				speed = float(cfg["run"]) * (1.0 + G.night * 0.12)
			if d < 1.3 and attack_cd <= 0.0 and seen_t < 1.0 and absf(pl.global_position.y - global_position.y) < 1.6:
				_set_state(S.WINDUP)
				Sfx.play_at("z_attack%d" % (randi() % 2), global_position + Vector3.UP * 1.5, 0.0, _pitch())
			elif seen_t > 8.0:
				target = last_known
				_set_state(S.INVESTIGATE)
		S.WINDUP:
			if state_t > 0.42:
				var d2 := global_position.distance_to(pl.global_position)
				if d2 < 1.75 and not pl.dead:
					var dm: Array = cfg["dmg"]
					pl.take_zombie_hit(self, randf_range(dm[0], dm[1]))
				attack_cd = 1.0
				_set_state(S.CHASE)
		S.BREAK:
			_break_process(delta)
		S.VAULT:
			var q := clampf(state_t / 1.1, 0.0, 1.0)
			global_position = vault_from.lerp(vault_to, q) + Vector3.UP * sin(q * PI) * 0.55
			if q >= 1.0:
				global_position.y = 0.0
				_shape.disabled = false
				_set_state(prev_state if prev_state in [S.CHASE, S.INVESTIGATE] else S.IDLE)
				repath_t = 0.0
		S.STAGGER:
			if state_t > 0.6:
				_set_state(S.CHASE if seen_t < 10.0 else S.INVESTIGATE)
		S.SCREAM:
			if state_t > 2.6:
				_set_state(S.CHASE)
	if state != S.VAULT:
		if slow_t > 0.0:
			speed *= 0.45
		move_dir = want
		var v := want * speed + sep * 1.4 + knock
		knock = knock.move_toward(Vector3.ZERO, delta * 12.0)
		velocity = velocity.move_toward(v, delta * 12.0)
		velocity.y = 0.0
		if velocity.length_squared() > 0.0004:
			move_and_slide()
		global_position.y = 0.0
		if want != Vector3.ZERO:
			facing = lerp_angle(facing, atan2(-want.x, -want.z), clampf(delta * 6.0, 0.0, 1.0))
		elif state in [S.WINDUP, S.SCREAM] or (state == S.CHASE and seen_t < 1.0):
			var to := pl.global_position - global_position
			facing = lerp_angle(facing, atan2(-to.x, -to.z), clampf(delta * 8.0, 0.0, 1.0))
	rotation.y = facing
	_animate(delta)
	_voice(delta)


# --- thinking -------------------------------------------------------------------
func _think() -> void:
	var pl := G.player
	if pl == null:
		return
	var to := pl.global_position - global_position
	var d := to.length()
	var now_indoor := G.town.is_indoor(global_position)
	if now_indoor != indoor:
		indoor = now_indoor
		Mat.set_layers(model, G.VIS_IN if indoor else G.VIS_OUT)
	if not pl.dead and _can_see(pl, to, d):
		seen_t = 0.0
		last_known = pl.global_position
		if state in [S.IDLE, S.WANDER, S.INVESTIGATE]:
			_alert()
	# separation from the crowd
	sep = Vector3.ZERO
	for z: Zombie in G.zombies.active:
		if z == self or z.state == S.DEAD:
			continue
		var off := global_position - z.global_position
		var l := off.length()
		if l < 0.75 and l > 0.001:
			sep += off / l * (0.75 - l) * 2.0
	sep.y = 0.0
	# doors and windows in the way
	if state in [S.CHASE, S.INVESTIGATE, S.WANDER] and move_dir != Vector3.ZERO:
		var p = G.town.portal_at(global_position + move_dir * 0.75)
		if p == null:
			p = G.town.portal_at(global_position + move_dir * 0.3)
		if p != null and is_instance_valid(p):
			_handle_portal(p)
	# unstick
	if state in [S.CHASE, S.INVESTIGATE, S.WANDER]:
		stuck_t += 0.25
		if stuck_t > 2.0:
			if global_position.distance_to(stuck_ref) < 0.35:
				if state == S.WANDER:
					_set_state(S.IDLE)
				else:
					repath_t = 0.0
					knock = Vector3(randf_range(-1, 1), 0, randf_range(-1, 1)).normalized() * 2.5
			stuck_ref = global_position
			stuck_t = 0.0


func _handle_portal(p: Node) -> void:
	if p is Door:
		if p.blocks():
			if state == S.WANDER:
				_set_state(S.IDLE)
			else:
				portal = p
				_set_state(S.BREAK)
	elif p is WindowPane:
		var across := move_dir.dot(p.normal)
		if absf(across) < 0.25:
			return
		if p.blocks():
			if state == S.WANDER:
				_set_state(S.IDLE)
			else:
				portal = p
				_set_state(S.BREAK)
		else:
			var side := signf((global_position - p.centre).dot(p.normal))
			if side == 0.0:
				side = -signf(across)
			vault_from = Vector3(p.centre.x, 0, p.centre.z) + p.normal * side * 0.75
			vault_to = Vector3(p.centre.x, 0, p.centre.z) - p.normal * side * 0.95
			if G.flat(global_position - vault_from).length() < 1.1:
				_shape.disabled = true
				_set_state(S.VAULT)


func _break_process(_delta: float) -> void:
	if portal == null or not is_instance_valid(portal) or not portal.blocks():
		portal = null
		_set_state(prev_state if prev_state in [S.CHASE, S.INVESTIGATE] else S.IDLE)
		repath_t = 0.0
		return
	var c: Vector3 = portal.centre
	facing = lerp_angle(facing, atan2(-(c.x - global_position.x), -(c.z - global_position.z)), 0.2)
	bash_t -= get_physics_process_delta_time()
	if bash_t <= 0.0:
		bash_t = randf_range(0.8, 1.2)
		state_t = 0.0
		portal.damage(randf_range(11.0, 19.0))
	var pl := G.player
	if global_position.distance_to(pl.global_position) < 1.3 and seen_t < 1.0:
		_set_state(S.CHASE)
	elif seen_t > 14.0 and prev_state != S.INVESTIGATE:
		_set_state(S.IDLE)


func _can_see(pl: Player, to: Vector3, d: float) -> bool:
	var rng_m := lerpf(30.0, 12.0, G.night)
	if pl.flash_on and G.night > 0.3:
		rng_m += 9.0
	if pl.indoor != indoor:
		rng_m *= 0.6
	if pl.crouching:
		rng_m *= 0.55
	rng_m *= 1.0 - G.rain * 0.2
	if d > rng_m:
		return false
	if d > 2.2 and state != S.CHASE:
		var fwd := Vector3(-sin(facing), 0, -cos(facing))
		if fwd.dot(G.flat(to).normalized()) < 0.2:
			return false
	var q := PhysicsRayQueryParameters3D.create(global_position + Vector3.UP * 1.5, pl.global_position + Vector3.UP * (0.6 if pl.crouching else 1.2), G.L_WORLD)
	return get_world_3d().direct_space_state.intersect_ray(q).is_empty()


func _alert() -> void:
	hurry = false
	if kind == "screamer" and scream_cd <= 0.0:
		scream_cd = 30.0
		_set_state(S.SCREAM)
		Sfx.play_at("z_scream", global_position + Vector3.UP * 1.6, 6.0, randf_range(0.95, 1.05), 130.0)
		G.emit_noise(global_position, 95.0, "scream")
		G.msg("尖啸声撕开了寂静——它们都听见了。", Color(0.85, 0.3, 0.25))
		return
	_set_state(S.CHASE)
	repath_t = 0.0
	if kind == "runner":
		Sfx.play_at("z_runner", global_position + Vector3.UP * 1.5, 2.0, randf_range(0.9, 1.1), 60.0)
	else:
		Sfx.play_at("z_alert%d" % (randi() % 2), global_position + Vector3.UP * 1.5, 0.0, _pitch(), 55.0)
	G.emit_noise(global_position, 11.0, "groan")


## Called by the manager for every noise event.
func hear(pos: Vector3, radius: float, nkind: String) -> void:
	if state in [S.CHASE, S.WINDUP, S.BREAK, S.VAULT, S.STAGGER, S.SCREAM, S.DEAD]:
		return
	var d := global_position.distance_to(pos)
	if d > radius or d < 0.6:
		return
	if nkind == "groan" and state == S.INVESTIGATE:
		return
	if randf() > 1.0 - pow(d / radius, 2.0) * 0.6:
		return
	var jit := 1.5 + d * 0.06
	target = pos + Vector3(randf_range(-jit, jit), 0, randf_range(-jit, jit))
	hurry = nkind in ["gunshot", "scream", "engine", "glass", "break"]
	repath_t = 0.0
	_set_state(S.INVESTIGATE)


func _pick_wander() -> void:
	for attempt in 6:
		var p := global_position + Vector3(randf_range(-12, 12), 0, randf_range(-12, 12))
		if absf(p.x) < Town.HALF - 3 and absf(p.z) < Town.HALF - 3 and G.town.is_free(p):
			target = p
			hurry = false
			repath_t = 0.0
			_set_state(S.WANDER)
			return
	idle_for = randf_range(2.0, 6.0)
	state_t = 0.0


func _nav_dir(delta: float) -> Vector3:
	repath_t -= delta
	if repath_t <= 0.0:
		path = G.town.find_path(global_position, target)
		path_i = 1
		repath_t = randf_range(0.5, 0.9) if state == S.CHASE else randf_range(2.5, 4.0)
	while path_i < path.size() and G.flat(path[path_i] - global_position).length() < 0.4:
		path_i += 1
	var goal := target
	if path_i < path.size():
		goal = path[path_i]
	var to := G.flat(goal - global_position)
	if to.length() < 0.25:
		return Vector3.ZERO
	return to.normalized()


# --- damage ----------------------------------------------------------------------
## Returns true if this hit killed it.
func take_hit(dmg: float, pos: Vector3, dir: Vector3, head: bool, stagger: float) -> bool:
	if state == S.DEAD:
		return false
	hp -= dmg
	G.fx.blood(pos, dir, head or dmg > 60.0)
	if hp <= 0.0:
		_die(dir, head)
		return true
	slow_t = 0.5
	seen_t = minf(seen_t, 2.0)
	last_known = G.player.global_position
	if state == S.VAULT:
		return false
	if randf() < stagger:
		knock = G.flat(dir).normalized() * 2.5
		_set_state(S.STAGGER)
	elif state in [S.IDLE, S.WANDER, S.INVESTIGATE]:
		_set_state(S.CHASE)
		repath_t = 0.0
	return false


func shove(dir: Vector3) -> void:
	if state in [S.DEAD, S.VAULT]:
		return
	knock = G.flat(dir).normalized() * 5.5
	seen_t = minf(seen_t, 2.0)
	last_known = G.player.global_position
	attack_cd = 1.2
	_set_state(S.STAGGER)
	state_t = -0.35     # longer stun than a bullet flinch


func _die(dir: Vector3, head: bool) -> void:
	state = S.DEAD
	state_t = 0.0
	G.kills += 1
	collision_layer = 0
	_shape.disabled = true
	if head and randf() < 0.7:
		parts["head"].visible = false
		parts["stump"].visible = true
		G.fx.blood(global_position + Vector3.UP * 1.6, Vector3.UP, true)
	var fwd := Vector3(-sin(facing), 0, -cos(facing))
	_fall_dir = 1.0 if fwd.dot(dir) < 0.0 else -1.0
	Sfx.play_at("z_die%d" % (randi() % 2), global_position + Vector3.UP * 1.4, -2.0, _pitch(), 40.0)
	G.zombies.on_death(self)
	if randf() < 0.14:
		var loot := Items.roll("corpse", G.rng)
		if not loot.is_empty():
			var pk := Props.Pickup.new()
			G.world.add_child(pk)
			pk.setup(loot[0], global_position + Vector3(randf_range(-0.4, 0.4), 0.05, randf_range(-0.4, 0.4)))


func _dead_process(delta: float) -> void:
	_corpse_t += delta
	if _fall < 1.0:
		_fall = minf(1.0, _fall + delta * 2.6)
		var e := _fall * _fall
		model.rotation.x = e * PI * 0.5 * _fall_dir
		model.position.y = e * 0.14
		parts["uarm_l"].rotation.x = lerpf(parts["uarm_l"].rotation.x, 0.3, 0.2)
		parts["uarm_r"].rotation.x = lerpf(parts["uarm_r"].rotation.x, -0.2, 0.2)
		parts["thigh_l"].rotation.x = lerpf(parts["thigh_l"].rotation.x, 0.15, 0.2)
		parts["thigh_r"].rotation.x = lerpf(parts["thigh_r"].rotation.x, -0.1, 0.2)
	var keep := 25.0 if G.low_spec else 60.0
	if _corpse_t > keep:
		model.position.y -= delta * 0.12
		if _corpse_t > keep + 3.0:
			queue_free()


# --- presentation -------------------------------------------------------------------
func _pitch() -> float:
	return randf_range(0.9, 1.1) * (1.2 if kind == "runner" else (0.82 if kind == "screamer" else 1.0))


func _voice(delta: float) -> void:
	moan_t -= delta
	if moan_t > 0.0:
		return
	moan_t = randf_range(4.0, 13.0)
	var d := global_position.distance_to(G.player.global_position)
	if d > 34.0:
		return
	if kind == "screamer" and state in [S.IDLE, S.WANDER]:
		Sfx.play_at("z_sob", global_position + Vector3.UP * 1.5, 2.0, randf_range(0.95, 1.05), 45.0)
	elif state == S.CHASE:
		Sfx.play_at("z_alert%d" % (randi() % 2), global_position + Vector3.UP * 1.5, -3.0, _pitch(), 45.0)
		moan_t = randf_range(2.5, 6.0)
	else:
		Sfx.play_at("z_moan%d" % (randi() % 4), global_position + Vector3.UP * 1.5, -5.0, _pitch(), 35.0)


func _animate(delta: float) -> void:
	if global_position.distance_squared_to(G.player.global_position) > 2500.0:
		return
	var sp := Vector2(velocity.x, velocity.z).length()
	anim += delta * (1.6 + sp * 3.4)
	var t := anim
	var amp := clampf(sp * 0.4, 0.05, 0.8)
	var sw := sin(t)
	var chase := state in [S.CHASE, S.WINDUP, S.BREAK]
	var lean := -0.1
	var arm_l := -sw * amp * 0.5
	var arm_r := sw * amp * 0.5
	var fore := 0.2
	var head_x := 0.1
	var spread := 0.0
	if chase:
		lean = -0.5 if kind == "runner" else -0.28
		arm_l = 1.35 + sin(t * 0.7 + seed_f) * 0.14
		arm_r = 1.0 + sin(t * 0.9 + seed_f * 2.0) * 0.18
		fore = 0.3
		head_x = 0.3
	match state:
		S.WINDUP:
			var q := clampf(state_t / 0.42, 0.0, 1.0)
			arm_l = lerpf(2.4, 0.7, q * q)
			arm_r = lerpf(2.5, 0.6, q * q)
			lean = lerpf(0.05, -0.55, q)
		S.BREAK:
			var q2 := clampf(state_t / 0.35, 0.0, 1.0)
			arm_l = lerpf(2.3, 1.0, q2)
			arm_r = lerpf(2.4, 1.1, q2)
			lean = lerpf(-0.05, -0.4, q2)
		S.STAGGER:
			lean = 0.45 * (1.0 - clampf(state_t / 0.6, 0.0, 1.0))
			arm_l = 0.4
			arm_r = -0.2
			spread = 0.5
		S.SCREAM:
			lean = 0.3
			head_x = -0.6 + sin(t * 30.0) * 0.04
			arm_l = -0.3
			arm_r = -0.3
			spread = 0.7
		S.VAULT:
			lean = -1.1
			arm_l = 2.2
			arm_r = 2.2
	var torso: Node3D = parts["torso"]
	torso.rotation.x = lerpf(torso.rotation.x, lean + sin(t * 2.0) * 0.03, 0.25)
	torso.rotation.z = sin(t) * 0.07 + sin(seed_f) * 0.08
	var head: Node3D = parts["head"]
	head.rotation.x = head_x
	head.rotation.z = sin(seed_f * 3.0) * 0.3 + sin(t * 0.6 + seed_f) * 0.07
	if kind == "runner" and Engine.get_physics_frames() % 5 == 0:
		head.rotation.y = randf_range(-0.5, 0.5)
	(parts["uarm_l"] as Node3D).rotation.x = lerpf(parts["uarm_l"].rotation.x, arm_l, 0.3)
	(parts["uarm_r"] as Node3D).rotation.x = lerpf(parts["uarm_r"].rotation.x, arm_r, 0.3)
	(parts["uarm_l"] as Node3D).rotation.z = -spread
	(parts["uarm_r"] as Node3D).rotation.z = spread
	(parts["farm_l"] as Node3D).rotation.x = fore
	(parts["farm_r"] as Node3D).rotation.x = fore + 0.15
	(parts["thigh_l"] as Node3D).rotation.x = sw * amp
	(parts["thigh_r"] as Node3D).rotation.x = -sw * amp
	(parts["shin_l"] as Node3D).rotation.x = -(0.5 - 0.5 * sin(t + 1.3)) * amp * 1.3
	(parts["shin_r"] as Node3D).rotation.x = -(0.5 + 0.5 * sin(t + 1.3)) * amp * 1.3
	model.position.y = absf(cos(t)) * 0.03 * amp

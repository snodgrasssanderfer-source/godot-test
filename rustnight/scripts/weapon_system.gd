class_name WeaponSystem
extends Node3D
## Guns and melee: hip-fire / ADS, recoil with recovery, spread bloom,
## hitscan with damage falloff and headshots, magazine and shell-by-shell
## reloads, sprint-out, weapon swap, shove, and the fully procedural
## first-person viewmodel animation.

enum St { NONE, DRAW, READY, RELOAD, SWING, SHOVE, HOLSTER }

const VM_SCALE := 0.22      # the viewmodel is rendered tiny and close so it never clips into walls

var p: Player
var slots: Array = [null, null, null]      # {id, mag}
var cur := -1
var last := -1
var pending := -1
var def: Dictionary = {}
var st: St = St.NONE
var st_t := 0.0
var st_dur := 0.0
var ads := 0.0
var sprint_b := 0.0
var recoil := Vector2.ZERO           # view offset currently applied (x yaw, y pitch up), radians
var recoil_target := Vector2.ZERO
var bloom := 0.0

var _holder: Node3D
var _models := {}
var _model: Node3D
var _n := {}
var _rest := {}
var _fire_cd := 0.0
var _since_shot := 9.0
var _shots := 0
var _trigger_free := true
var _kick := 0.0
var _kick_v := 0.0
var _rkick := 0.0
var _slide := 0.0
var _pump_t := -1.0
var _t := 0.0
var _bob := 0.0
var _sway := Vector2.ZERO
var _aim_sway := Vector2.ZERO
var _flash: MeshInstance3D
var _flash_light: OmniLight3D
var _flash_t := 0.0
var _hit_done := false
var _r_empty := false
var _r_marks := {}
var _lower := 0.0
var _indoor := false


func _ready() -> void:
	_holder = Node3D.new()
	_holder.scale = Vector3.ONE * VM_SCALE
	add_child(_holder)
	_flash = MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(0.3, 0.3)
	_flash.mesh = q
	_flash.material_override = Mat.unshaded("muzzle", true)
	_flash.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_flash.visible = false
	_holder.add_child(_flash)
	_flash_light = OmniLight3D.new()
	_flash_light.position = Vector3(0, 0, -0.8)
	_flash_light.omni_range = 10.0
	_flash_light.light_color = Color(1.0, 0.75, 0.4)
	_flash_light.light_energy = 3.0
	_flash_light.shadow_enabled = false
	_flash_light.visible = false
	add_child(_flash_light)


# --- inventory side ------------------------------------------------------------------------
func give(id: String, mag: int = -1) -> void:
	var w: Dictionary = Items.WEAPONS[id]
	var slot: int = w["slot"]
	if slots[slot] != null:
		var old: Dictionary = slots[slot]
		p.inv.add(old["id"], 1, old.get("mag", -1))
	slots[slot] = {"id": id, "mag": (mag if mag >= 0 else 0)}
	if cur == slot:
		_equip(slot)
	elif cur == -1:
		switch_to(slot, true)


func unequip(slot: int) -> void:
	if slots[slot] == null:
		return
	var old: Dictionary = slots[slot]
	p.inv.add(old["id"], 1, old.get("mag", -1))
	slots[slot] = null
	if cur == slot:
		cur = -1
		def = {}
		st = St.NONE
		if _model:
			_model.visible = false
		for i in 3:
			if slots[i] != null:
				switch_to(i, true)
				break


func weight() -> float:
	var w := 0.0
	for s in slots:
		if s != null:
			w += Items.weight(s["id"])
	return w


func has_gun() -> bool:
	return cur >= 0 and not def.has("melee")


func is_melee() -> bool:
	return cur >= 0 and def.has("melee")


func reloading() -> bool:
	return st == St.RELOAD


func mag() -> int:
	return slots[cur]["mag"] if cur >= 0 else 0


func reserve() -> int:
	return p.inv.count(def["ammo"]) if has_gun() else 0


func move_mult() -> float:
	if cur < 0:
		return 1.0
	return lerpf(float(def["move"]), float(def.get("ads_move", 1.0)), ads)


func zoom() -> float:
	return float(def.get("zoom", 1.0)) if cur >= 0 else 1.0


func ads_eased() -> float:
	return ads * ads * (3.0 - 2.0 * ads)


func sens_mult() -> float:
	return lerpf(1.0, 1.0 / zoom() * 0.9, ads)


func set_indoor(v: bool) -> void:
	_indoor = v
	Mat.set_layers(_holder, G.VIS_IN if v else G.VIS_OUT)


func on_mantle(dur: float) -> void:
	_lower = dur + 0.1


func switch_to(slot: int, instant: bool = false) -> void:
	if slot < 0 or slot > 2 or slots[slot] == null or slot == cur:
		return
	if instant or cur < 0:
		_equip(slot)
		return
	if st in [St.HOLSTER, St.SWING, St.SHOVE]:
		return
	pending = slot
	st = St.HOLSTER
	st_t = 0.0
	st_dur = 0.16


func _equip(slot: int) -> void:
	if cur >= 0 and cur != slot:
		last = cur
	cur = slot
	var id: String = slots[slot]["id"]
	def = Items.WEAPONS[id]
	if _model:
		_model.visible = false
	if not _models.has(id):
		var m: Node3D = load("res://models/%s.glb" % def["model"]).instantiate()
		Mat.skin(m, Mat.atlas("weapons", 0.14), G.VIS_IN if _indoor else G.VIS_OUT, false)
		_holder.add_child(m)
		var nodes := {}
		for nm in ["slide", "mag", "pump", "muzzle", "eject", "arm_l", "arm_r"]:
			var nd := m.find_child(nm, true, false)
			if nd:
				nodes[nm] = nd
				nodes[nm + "_rest"] = (nd as Node3D).position
		_models[id] = [m, nodes]
	_model = _models[id][0]
	_n = _models[id][1]
	_model.visible = true
	st = St.DRAW
	st_t = 0.0
	st_dur = def["draw"]
	ads = 0.0
	bloom = 0.0
	_pump_t = -1.0
	Sfx.play_var("equip", -8.0)


# --- main loop -----------------------------------------------------------------------------
func _process(delta: float) -> void:
	if p == null or p.dead or not G.playing:
		return
	_t += delta
	_fire_cd -= delta
	_since_shot += delta
	_lower = maxf(0.0, _lower - delta)
	if _flash_t > 0.0:
		_flash_t -= delta
		if _flash_t <= 0.0:
			_flash.visible = false
			_flash_light.visible = false
	_update_recoil(delta)
	if cur < 0:
		return
	var free := not G.ui_open and not p.sleeping
	if free:
		for i in 3:
			if Input.is_action_just_pressed("slot%d" % (i + 1)):
				switch_to(i)
		if Input.is_action_just_pressed("swap"):
			var nxt := last if last >= 0 and slots[last] != null and last != cur else -1
			if nxt < 0:
				for k in range(1, 3):
					if slots[(cur + k) % 3] != null:
						nxt = (cur + k) % 3
						break
			if nxt >= 0:
				switch_to(nxt)
	var gun := has_gun()
	var want_ads := free and gun and Input.is_action_pressed("ads") and st in [St.READY, St.RELOAD] and not p.mantling
	if want_ads and st == St.RELOAD:
		want_ads = false
	ads = move_toward(ads, 1.0 if want_ads else 0.0, delta / maxf(float(def.get("ads_time", 0.2)), 0.05))
	sprint_b = move_toward(sprint_b, 1.0 if p.sprinting else 0.0, delta / maxf(float(def.get("sprint_out", 0.2)), 0.08))
	bloom = maxf(0.0, bloom - delta * 5.0)
	if _since_shot > 0.25:
		_shots = 0

	if free and Input.is_action_pressed("fire") and not p.mantling:
		if gun:
			if st == St.READY and _fire_cd <= 0.0 and sprint_b < 0.25 and (def["auto"] or _trigger_free):
				_fire()
			elif st == St.RELOAD and def.has("shell_reload") and mag() > 0 and _trigger_free:
				st = St.READY
		elif st == St.READY:
			_start(St.SWING, def["swing"])
			_hit_done = false
			p.surv.spend(def["end_cost"])
			Sfx.play_var("swing", -4.0)
		_trigger_free = false
	else:
		_trigger_free = true
	if free and gun and Input.is_action_just_pressed("reload"):
		_try_reload()
	if free and Input.is_action_just_pressed("melee") and st in [St.READY, St.RELOAD] and not p.mantling:
		_start(St.SHOVE, 0.48)
		_hit_done = false
		p.surv.spend(5.0)
		Sfx.play_var("swing", -7.0, 0.15)

	st_t += delta
	match st:
		St.DRAW:
			if st_t >= st_dur:
				st = St.READY
				if gun and mag() == 0 and reserve() > 0:
					_try_reload()
		St.HOLSTER:
			if st_t >= st_dur:
				_equip(pending)
		St.RELOAD:
			_reload_step()
		St.SWING:
			if not _hit_done and st_t >= float(def["hit_t"]):
				_hit_done = true
				_melee_hit()
			if st_t >= st_dur:
				st = St.READY
		St.SHOVE:
			if not _hit_done and st_t >= 0.12:
				_hit_done = true
				_shove_hit()
			if st_t >= st_dur:
				st = St.READY
	if _pump_t >= 0.0:
		_pump_t += delta
		if _pump_t > 0.7:
			_pump_t = -1.0
	_pose(delta)


func _start(s: St, dur: float) -> void:
	st = s
	st_t = 0.0
	st_dur = dur


func _update_recoil(delta: float) -> void:
	# kick is applied fast, then the view settles back to where you aimed
	recoil = recoil.lerp(recoil_target, clampf(delta * 28.0, 0.0, 1.0))
	if _since_shot > 0.08:
		recoil_target = recoil_target.lerp(Vector2.ZERO, clampf(delta * 7.0, 0.0, 1.0))
	_kick_v += (-_kick * 260.0 - _kick_v * 22.0) * delta
	_kick += _kick_v * delta
	_rkick = lerpf(_rkick, 0.0, clampf(delta * 13.0, 0.0, 1.0))
	_slide = maxf(0.0, _slide - delta * 14.0)


# --- shooting ---------------------------------------------------------------------------------
func spread() -> float:
	if not has_gun():
		return 0.0
	var hip := float(def["hip"]) * (1.0 + p.h_speed() / Player.WALK * 0.7)
	if not p.is_on_floor():
		hip *= 1.6
	if p.crouching:
		hip *= 0.75
	hip += bloom
	return lerpf(hip, float(def["ads_spread"]), ads_eased())


func _fire() -> void:
	var s: Dictionary = slots[cur]
	if s["mag"] <= 0:
		Sfx.play("dry", -2.0)
		_fire_cd = 0.3
		if reserve() > 0:
			_try_reload()
		return
	s["mag"] -= 1
	_fire_cd = 60.0 / float(def["rpm"])
	_since_shot = 0.0
	var sp := spread()
	var cam := p.cam
	var basis := cam.global_transform.basis
	# the bullet goes where the (swaying) sights point
	basis = basis * Basis.from_euler(Vector3(_aim_sway.y, _aim_sway.x, 0.0))
	var pellets: int = def["pellets"]
	var any_hit := false
	for i in pellets:
		var ang := deg_to_rad(sp + float(def.get("pellet_spread", 0.0))) * sqrt(randf())
		var rot := randf() * TAU
		var dir := (basis * Vector3(sin(ang) * cos(rot), sin(ang) * sin(rot), -cos(ang))).normalized()
		if _hitscan(cam.global_position, dir, i == 0 or randf() < 0.4):
			any_hit = true
	# recoil
	var k := (1.0 - 0.25 * ads) * (0.85 if p.crouching else 1.0)
	recoil_target.y += deg_to_rad(float(def["recoil_v"])) * randf_range(0.85, 1.15) * k
	recoil_target.x += deg_to_rad(float(def["recoil_h"])) * (sin(_shots * 1.3) * 0.6 + randf_range(-0.6, 0.6)) * k
	recoil_target.y = minf(recoil_target.y, 0.35)
	_shots += 1
	_kick_v += float(def["kick"]) * 34.0 * (1.0 - 0.45 * ads)
	_rkick += deg_to_rad(float(def["kick_rot"])) * (1.0 - 0.6 * ads)
	_slide = 1.0
	bloom = minf(bloom + float(def["bloom"]), 4.0)
	p.shake(0.12 + float(def["kick"]) * 3.0)
	Sfx.play(def["sound"], 0.0, randf_range(0.96, 1.04))
	G.emit_noise(p.global_position, def["noise"], "gunshot")
	# muzzle flash
	if _n.has("muzzle"):
		_flash.global_position = (_n["muzzle"] as Node3D).global_position
	_flash.rotation = Vector3(0, 0, randf() * TAU)
	var fs := randf_range(0.8, 1.3) * (1.6 if pellets > 1 else 1.0)
	_flash.scale = Vector3(fs, fs, fs)
	_flash.visible = true
	_flash_light.visible = true
	_flash_t = 0.05
	if def.has("pump"):
		_pump_t = 0.0
		get_tree().create_timer(0.3).timeout.connect(func(): Sfx.play("pump", -3.0))
	elif _n.has("eject"):
		var ej: Node3D = _n["eject"]
		G.fx.casing(ej.global_position, cam.global_transform.basis * Vector3(randf_range(1.4, 2.2), randf_range(1.0, 1.8), randf_range(-0.3, 0.3)) + p.velocity)
	if any_hit:
		pass


func _hitscan(from: Vector3, dir: Vector3, tracer: bool) -> bool:
	var space := get_world_3d().direct_space_state
	var excl: Array[RID] = [p.get_rid()]
	var end := from + dir * 105.0
	var hit_zombie := false
	for n in 3:
		var q := PhysicsRayQueryParameters3D.create(from, from + dir * 105.0, G.L_WORLD | G.L_ZOMBIE | G.L_GLASS)
		q.exclude = excl
		var hit := space.intersect_ray(q)
		if hit.is_empty():
			break
		var c: Object = hit["collider"]
		end = hit["position"]
		if c is WindowPane:
			if (c as WindowPane).planks > 0:
				(c as WindowPane).damage(6.0)
				G.fx.impact(end, hit["normal"], true)
				break
			(c as WindowPane).smash()
			excl.append((c as CollisionObject3D).get_rid())
			continue
		if c is Zombie:
			var z := c as Zombie
			var head: bool = (end.y - z.global_position.y) > 1.44 * z.model.scale.y
			var d := from.distance_to(end)
			var dmg := lerpf(float(def["dmg"]), float(def["dmg_min"]), clampf((d - float(def["r0"])) / (float(def["r1"]) - float(def["r0"])), 0.0, 1.0))
			if head:
				dmg *= float(def["head"])
			var killed := z.take_hit(dmg, end, dir, head, float(def["stagger"]) * (1.0 if int(def["pellets"]) == 1 else 0.4))
			G.hud.hitmarker(head, killed)
			hit_zombie = true
			break
		if c is Door:
			(c as Door).damage(4.0)
		G.fx.impact(end, hit["normal"], c is Door)
		break
	if tracer and _n.has("muzzle"):
		G.fx.tracer((_n["muzzle"] as Node3D).global_position, end)
	return hit_zombie


func _try_reload() -> void:
	if st != St.READY or not has_gun():
		return
	if mag() >= int(def["mag"]) or reserve() <= 0:
		return
	_r_empty = mag() == 0
	_r_marks = {}
	if def.has("shell_reload"):
		_start(St.RELOAD, def["reload"])
	else:
		_start(St.RELOAD, def["reload_empty"] if _r_empty else def["reload"])


func _mark(key: String, at: float) -> bool:
	if not _r_marks.has(key) and st_t >= at:
		_r_marks[key] = true
		return true
	return false


func _reload_step() -> void:
	var s: Dictionary = slots[cur]
	if def.has("shell_reload"):
		if _r_marks.has("end"):
			if st_t >= st_dur:
				st = St.READY
			return
		if _mark("in", st_dur * 0.55):
			if p.inv.remove(def["ammo"], 1) > 0:
				s["mag"] += 1
			Sfx.play_var("shell_in", -3.0)
		if st_t >= st_dur:
			if s["mag"] < int(def["mag"]) and reserve() > 0:
				_r_marks = {}
				st_t = 0.0
			else:
				_r_marks = {"end": true}
				st_t = 0.0
				st_dur = 0.4 if _r_empty else 0.15
				if _r_empty:
					_pump_t = 0.0
					Sfx.play("pump", -3.0)
		return
	if _mark("out", st_dur * 0.14):
		Sfx.play_var("mag_out", -3.0)
	if _mark("in", st_dur * (0.56 if _r_empty else 0.66)):
		Sfx.play_var("mag_in", -3.0)
		var need: int = int(def["mag"]) - int(s["mag"])
		s["mag"] += p.inv.remove(def["ammo"], need)
	if _r_empty and _mark("rack", st_dur * 0.84):
		Sfx.play_var("slide", -3.0)
		_slide = 1.0
	if st_t >= st_dur:
		st = St.READY


# --- melee ----------------------------------------------------------------------------------------
func _melee_hit() -> void:
	var cam := p.cam
	var fwd := -cam.global_transform.basis.z
	var targets: Array = G.zombies.in_arc(cam.global_position, fwd, def["range"], def["arc"])
	var n := 0
	for z: Zombie in targets:
		if n >= 2:
			break
		n += 1
		var head := randf() < float(def["head_kill"])
		var dmg := 999.0 if head else float(def["dmg"]) * randf_range(0.8, 1.2) * (0.6 if p.surv.endurance < 12.0 else 1.0)
		var killed := z.take_hit(dmg, z.global_position + Vector3.UP * (1.6 if head else 1.3), fwd, head, 1.0)
		z.knock = G.flat(fwd).normalized() * 3.0
		Sfx.play_at("hit_head" if head else "hit_flesh", z.global_position + Vector3.UP * 1.4, 2.0, randf_range(0.9, 1.1))
		G.hud.hitmarker(head, killed)
	if n > 0:
		p.shake(0.5)
	else:
		var hit := p._ray(cam.global_position, cam.global_position + fwd * float(def["range"]), G.L_WORLD | G.L_GLASS)
		if not hit.is_empty():
			var c: Object = hit["collider"]
			if c is WindowPane:
				(c as WindowPane).damage(40.0)
			elif c is Door:
				(c as Door).damage(30.0)
			else:
				Sfx.play_at("hit_metal" if randf() < 0.3 else "hit_wall", hit["position"], 0.0)
				G.fx.impact(hit["position"], hit["normal"], false)
			p.shake(0.3)
	G.emit_noise(p.global_position, def["noise"] + (6.0 if n > 0 else 0.0), "melee")


func _shove_hit() -> void:
	var cam := p.cam
	var fwd := -cam.global_transform.basis.z
	var targets: Array = G.zombies.in_arc(cam.global_position, fwd, 1.9, 60.0)
	var n := 0
	for z: Zombie in targets:
		if n >= 3:
			break
		n += 1
		z.take_hit(6.0, z.global_position + Vector3.UP * 1.3, fwd, false, 0.0)
		z.shove(fwd)
	if n > 0:
		Sfx.play_var("hit_flesh", -2.0)
		p.shake(0.4)
	else:
		var hit := p._ray(cam.global_position, cam.global_position + fwd * 1.8, G.L_WORLD | G.L_GLASS)
		if not hit.is_empty() and hit["collider"] is WindowPane:
			(hit["collider"] as WindowPane).damage(40.0)
	G.emit_noise(p.global_position, 5.0, "melee")


# --- viewmodel pose -------------------------------------------------------------------------------
func _bump(q: float, a: float, b: float) -> float:
	if q <= a or q >= b:
		return 0.0
	return sin((q - a) / (b - a) * PI)


func _pose(delta: float) -> void:
	var a := ads_eased()
	var gun := has_gun()
	var pos: Vector3 = def["hip_pos"]
	if gun:
		pos = pos.lerp(Vector3(0, 0, -float(def["ads_dist"])), a)
	var rot := Vector3.ZERO
	var hipf := 1.0 - a

	# breathing + unsteadiness (panic, pain, exhaustion make the sights wander)
	var uns := p.surv.unsteady()
	var sw_amp := (0.0035 + uns * 0.012) * (0.6 if p.crouching else 1.0)
	_aim_sway = Vector2(sin(_t * 1.3) + sin(_t * 2.9 + 1.0) * 0.5, sin(_t * 1.7 + 2.0) + sin(_t * 3.7) * 0.5) * sw_amp
	rot.x += _aim_sway.y
	rot.y += _aim_sway.x
	pos.y += sin(_t * 1.6) * 0.0025 * hipf

	# look sway (the gun lags behind the view)
	var lv := Vector2(clampf(p.look_vel.x, -6.0, 6.0), clampf(p.look_vel.y, -6.0, 6.0))
	_sway = _sway.lerp(lv, clampf(delta * 9.0, 0.0, 1.0))
	rot.y += _sway.x * 0.012 * (0.35 + 0.65 * hipf)
	rot.x += _sway.y * 0.012 * (0.35 + 0.65 * hipf)
	rot.z += _sway.x * 0.015 * hipf
	pos.x += _sway.x * 0.0025 * hipf

	# walk bob
	var sp := p.h_speed()
	var grounded := p.is_on_floor() and not p.sliding
	if grounded and sp > 0.5:
		_bob += delta * sp * 1.9
	var amp := clampf(sp / Player.WALK, 0.0, 1.5) * (0.12 + 0.88 * hipf) * (1.0 if grounded else 0.2)
	pos.x += sin(_bob) * 0.008 * amp
	pos.y += -absf(cos(_bob)) * 0.007 * amp + 0.0035 * amp
	rot.z += sin(_bob) * 0.012 * amp

	# strafing tilt, air offset
	var lvel := p.global_transform.basis.inverse() * p.velocity
	rot.z += clampf(-lvel.x * 0.012, -0.07, 0.07) * (0.3 + 0.7 * hipf)
	pos.y += clampf(-p.velocity.y * 0.004, -0.03, 0.035)

	# sprint pose
	var s := sprint_b * sprint_b * (3.0 - 2.0 * sprint_b)
	if s > 0.0:
		if def.has("melee"):
			pos += Vector3(0.0, -0.03, 0.02) * s
			rot.x += 0.25 * s
		elif int(def["slot"]) == 1:
			pos += Vector3(-0.02, -0.02, 0.05) * s
			rot += Vector3(0.55, 0.1, 0.0) * s
		else:
			pos += Vector3(-0.05, -0.02, 0.06) * s
			rot += Vector3(-0.2, 0.75, 0.3) * s
		pos.y += sin(_bob * 2.0) * 0.012 * s
		pos.x += sin(_bob) * 0.012 * s
	if p.sliding:
		rot.z += 0.25 * hipf
		pos.y -= 0.02 * hipf

	# recoil
	pos.z += _kick
	rot.x += _rkick

	# state animations
	var q := clampf(st_t / maxf(st_dur, 0.01), 0.0, 1.0)
	var lower := 0.0
	match st:
		St.DRAW:
			lower = 1.0 - q
		St.HOLSTER:
			lower = q
		St.RELOAD:
			if def.has("shell_reload"):
				rot += Vector3(0.22, -0.2, -0.5)
				pos += Vector3(-0.02, -0.035, 0.03)
				pos.y += _bump(q, 0.3, 0.7) * 0.012
			else:
				var d := sin(q * PI)
				d = pow(d, 0.6)
				rot += Vector3(0.3, -0.25, 0.55) * d
				pos += Vector3(-0.02, -0.05, 0.02) * d
				pos.y -= _bump(q, 0.5, 0.72) * 0.018
				var m := _bump(q, 0.1, 0.66)
				if _n.has("mag"):
					(_n["mag"] as Node3D).position = _n["mag_rest"] + Vector3(0, -0.22 * m, -0.03 * m)
				if _n.has("arm_l") and int(def["slot"]) != 1:
					(_n["arm_l"] as Node3D).position = _n["arm_l_rest"] + Vector3(0, -0.2 * m, -0.12 * m)
		St.SWING:
			var ht := float(def["hit_t"]) / st_dur
			if q < ht:
				var u := q / ht
				rot += Vector3(0.7, 0.3, -0.3) * u
				pos += Vector3(0.03, 0.06, 0.05) * u
			else:
				var u2 := clampf((q - ht) / 0.22, 0.0, 1.0)
				var back := clampf((q - ht - 0.22) / (1.0 - ht - 0.22), 0.0, 1.0)
				var sw := u2 * (1.0 - back * back)
				rot += Vector3(lerpf(0.7, -1.5, u2) * (1.0 - back), 0.3 - 0.8 * sw, -0.3 + 0.5 * sw)
				pos += Vector3(0.03 - 0.16 * sw, 0.06 - 0.12 * sw, 0.05 - 0.22 * sw)
		St.SHOVE:
			var b := sin(q * PI)
			pos += Vector3(-0.04, 0.03, -0.2) * b
			rot += Vector3(0.35, 0.3, 0.4) * b
	if _lower > 0.0:
		lower = maxf(lower, clampf(_lower * 5.0, 0.0, 1.0))
	if lower > 0.0:
		pos.y -= 0.22 * lower * lower
		rot.x -= 0.7 * lower * lower

	# moving parts
	if st != St.RELOAD:
		if _n.has("mag"):
			(_n["mag"] as Node3D).position = _n["mag_rest"]
		if _n.has("arm_l"):
			(_n["arm_l"] as Node3D).position = _n["arm_l_rest"]
	if _n.has("slide"):
		var back_amt := _slide
		if gun and mag() == 0 and int(def["slot"]) == 1 and st != St.RELOAD:
			back_amt = 1.0        # slide locks open on an empty pistol
		(_n["slide"] as Node3D).position = _n["slide_rest"] + Vector3(0, 0, 0.035 * back_amt)
	if _n.has("pump"):
		var pp := 0.0
		if _pump_t >= 0.0:
			pp = _bump(_pump_t, 0.22, 0.62)
		(_n["pump"] as Node3D).position = _n["pump_rest"] + Vector3(0, 0, 0.09 * pp)
		if pp > 0.0:
			rot.x += 0.05 * pp
	_model.transform = Transform3D(Basis.from_euler(rot), pos)

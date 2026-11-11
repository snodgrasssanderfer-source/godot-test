class_name ZombieManager
extends Node3D
## The town's undead population. Only zombies near the player exist as nodes;
## the rest are cheap records that still wander, migrate towards noise and
## trickle in from the map edges day after day.

const ACTIVATE_R := 46.0
const DEACTIVATE_R := 58.0

var active: Array = []          # Zombie nodes (alive)
var virtual: Array = []         # {pos, kind, target (Vector3 or null), hp}
var max_active := 26
var _tick := 0.0
var _last_hour := -1
var _hours := 0


func _ready() -> void:
	max_active = 15 if G.low_spec else 26
	G.noise_made.connect(_on_noise)


func populate() -> void:
	var town := G.town
	var n := 42 if G.low_spec else 62
	for i in n:
		var p: Vector3
		if i % 3 == 0:    # bias towards the streets
			p = Vector3(randf_range(-85, 85), 0, randf_range(-7, 7)) if randf() < 0.5 else Vector3([-40.0, 40.0][randi() % 2] + randf_range(-6, 6), 0, randf_range(-85, 85))
			p = town.cell_pos(town.free_cell(town.cell_of(p)))
		else:
			p = town.random_point(town.spawn_pos, 30.0)
		if p.distance_to(town.spawn_pos) < 28.0:
			continue
		_add_virtual(p, _roll_kind())
	for i in 7:     # the dead garrison of the roadblock
		_add_virtual(Vector3(82 + randf_range(-6, 6), 0, randf_range(-5, 5)), "walker")
	# a few shut inside buildings
	for b in town.buildings:
		if b["safe"]:
			continue
		var cnt := 0
		match b["type"]:
			"police": cnt = 3
			"clinic": cnt = 2
			"store", "hardware", "diner", "garage", "church": cnt = randi_range(1, 2)
			_: cnt = 1 if randf() < 0.4 else 0
		for k in cnt:
			var r: Rect2 = (b["rect"] as Rect2).grow(-1.5)
			var p := Vector3(randf_range(r.position.x, r.end.x), 0, randf_range(r.position.y, r.end.y))
			_add_virtual(town.cell_pos(town.free_cell(town.cell_of(p))), "walker" if randf() < 0.85 else "runner")


func _roll_kind() -> String:
	var d := G.day() - 1
	var r := randf()
	var screamers := 0
	for z: Zombie in active:
		if z.kind == "screamer":
			screamers += 1
	for v in virtual:
		if v["kind"] == "screamer":
			screamers += 1
	if r < 0.05 and screamers < 3:
		return "screamer"
	var run_p := minf(0.45, lerpf(0.07, 0.2, G.night) + d * 0.035)
	return "runner" if randf() < run_p else "walker"


func _add_virtual(pos: Vector3, kind: String, target = null, hp: float = -1.0) -> void:
	virtual.append({"pos": pos, "kind": kind, "target": target, "hp": hp})


func total() -> int:
	return active.size() + virtual.size()


func _process(delta: float) -> void:
	if not G.playing or G.player == null:
		return
	_tick += delta
	if _tick >= 0.5:
		_tick = 0.0
		_step_virtual(0.5)
		_swap_lod()
	var h := int(G.clock / 60.0)
	if h != _last_hour:
		if _last_hour >= 0:
			_hourly()
		_last_hour = h


func _step_virtual(dt: float) -> void:
	for v in virtual:
		if v["target"] != null:
			var to: Vector3 = v["target"] - v["pos"]
			var l := to.length()
			if l < 3.0:
				v["target"] = null
			else:
				v["pos"] += to / l * 1.25 * dt
		elif randf() < 0.02:
			v["target"] = v["pos"] + Vector3(randf_range(-18, 18), 0, randf_range(-18, 18))
			v["target"].x = clampf(v["target"].x, -Town.HALF + 4, Town.HALF - 4)
			v["target"].z = clampf(v["target"].z, -Town.HALF + 4, Town.HALF - 4)


func _swap_lod() -> void:
	var pp := G.player.global_position
	# wake up nearby records
	var i := virtual.size() - 1
	while i >= 0 and active.size() < max_active:
		var v: Dictionary = virtual[i]
		if (v["pos"] as Vector3).distance_to(pp) < ACTIVATE_R:
			var cell := G.town.free_cell(G.town.cell_of(v["pos"]))
			var pos := G.town.cell_pos(cell)
			if pos.distance_to(pp) > 9.0:
				var z := Zombie.new()
				add_child(z)
				z.setup(v["kind"], pos, v["hp"])
				if v["target"] != null:
					z.target = v["target"]
					z.hurry = true
					z._set_state(Zombie.S.INVESTIGATE)
				active.append(z)
				virtual.remove_at(i)
		i -= 1
	# put far-away idle ones back to sleep
	for j in range(active.size() - 1, -1, -1):
		var z: Zombie = active[j]
		if not is_instance_valid(z):
			active.remove_at(j)
			continue
		if z.state in [Zombie.S.CHASE, Zombie.S.WINDUP, Zombie.S.VAULT, Zombie.S.BREAK]:
			continue
		if z.global_position.distance_to(pp) > DEACTIVATE_R:
			_add_virtual(z.global_position, z.kind, z.target if z.state == Zombie.S.INVESTIGATE else null, z.hp)
			active.remove_at(j)
			z.queue_free()


func on_death(z: Zombie) -> void:
	active.erase(z)


func _on_noise(pos: Vector3, radius: float, kind: String) -> void:
	for z: Zombie in active:
		if is_instance_valid(z):
			z.hear(pos, radius, kind)
	if radius < 20.0:
		return
	for v in virtual:
		var d := (v["pos"] as Vector3).distance_to(pos)
		if d < radius and randf() < 0.75:
			var jit := 2.0 + d * 0.08
			v["target"] = pos + Vector3(randf_range(-jit, jit), 0, randf_range(-jit, jit))


## Once per in-game hour: migration and slow population growth.
func _hourly() -> void:
	_hours += 1
	var cap := (90 if G.low_spec else 150)
	var want := mini(cap, int((40 if G.low_spec else 60) + (G.clock - G.START_CLOCK) / 1440.0 * 26.0 + G.night * 10.0))
	var add := clampi(want - total(), 0, 7)
	for i in add:
		var p := G.town.edge_point()
		if p.distance_to(G.player.global_position) < 40.0:
			continue
		var tgt: Vector3 = G.town.random_point()
		if randf() < 0.35:
			tgt = G.player.global_position + Vector3(randf_range(-25, 25), 0, randf_range(-25, 25))
		_add_virtual(p, _roll_kind(), tgt)
	if _hours % 3 == 0:      # a loose herd drifts across town
		var dest: Vector3 = G.town.random_point()
		var moved := 0
		for v in virtual:
			if moved >= 12:
				break
			if randf() < 0.25:
				v["target"] = dest + Vector3(randf_range(-8, 8), 0, randf_range(-8, 8))
				moved += 1


## Time skip while the player sleeps: shuffle everyone a bit.
func time_skip(hours: float) -> void:
	for v in virtual:
		if randf() < 0.5:
			v["pos"] = G.town.random_point(G.player.global_position, 30.0)
			v["target"] = null
	for i in int(hours):
		_hourly()


func in_arc(origin: Vector3, fwd: Vector3, rng_m: float, arc_deg: float) -> Array:
	var out: Array = []
	var f := G.flat(fwd).normalized()
	var cosv := cos(deg_to_rad(arc_deg))
	for z: Zombie in active:
		if not is_instance_valid(z) or z.state == Zombie.S.DEAD:
			continue
		var to := G.flat(z.global_position - origin)
		var d := to.length()
		if d > rng_m:
			continue
		if d > 0.5 and f.dot(to / d) < cosv:
			continue
		out.append(z)
	out.sort_custom(func(a, b): return a.global_position.distance_squared_to(origin) < b.global_position.distance_squared_to(origin))
	return out


func count_near(pos: Vector3, r: float, chasing_only: bool = false) -> int:
	var n := 0
	for z: Zombie in active:
		if not is_instance_valid(z) or z.state == Zombie.S.DEAD:
			continue
		if chasing_only and not (z.state in [Zombie.S.CHASE, Zombie.S.WINDUP, Zombie.S.BREAK]):
			continue
		if z.global_position.distance_to(pos) < r:
			n += 1
	return n


func nearest(pos: Vector3, max_r: float) -> Zombie:
	var best: Zombie = null
	var bd := max_r
	for z: Zombie in active:
		if not is_instance_valid(z) or z.state == Zombie.S.DEAD:
			continue
		var d := z.global_position.distance_to(pos)
		if d < bd:
			bd = d
			best = z
	return best

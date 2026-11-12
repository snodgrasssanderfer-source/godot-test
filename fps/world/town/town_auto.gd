class_name TownAuto
extends Node
## Scripted runs of the town:
##
##   --selftest      checks the layout, bakes the navigation mesh and walks the
##                   player (through the real input path) into every building,
##                   up both staircases and down into the pit; exits 0 on success
##   --shoot=<dir>   screenshots for review (--shots=01,05,air picks some)
##
##   godot --headless --fixed-fps 60 --path fps res://world/town/town.tscn -- --selftest

const TICKS := 120.0
## What the navigation mesh covers: the whole walkable town, pit to upper floors.
const NAV_BOUNDS := AABB(Vector3(-78.0, -2.0, -116.0), Vector3(176.0, 9.0, 238.0))
## The town is laid out at an angle to the grid and has 1 m doors: on the
## range's 0.25 m cells a door can close up, so the town bakes at half that.
const NAV_RADIUS := 0.25
const NAV_CELL := 0.125

var scene: Node3D
var player: Player
var builder: TownBuilder

var _passed := 0
var _failed := 0
var _held := {}


func start(town_scene: Node3D, mode: String, arg: String) -> void:
	scene = town_scene
	player = scene.player
	builder = scene.builder
	await _secs(0.5)
	if mode == "shoot":
		await _shoot(arg)
		get_tree().quit()
	else:
		await _selftest()
		print("TOWN SELFTEST %d passed, %d failed" % [_passed, _failed])
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
	if not _held.has(action):
		_held[action] = true
		_send(action, true)


func _release_all() -> void:
	for action: StringName in _held.keys():
		_send(action, false)
	_held.clear()


func _check(what: String, ok: bool, detail := "") -> void:
	if ok:
		_passed += 1
	else:
		_failed += 1
	print("%s  %s%s" % ["PASS" if ok else "FAIL", what, ("   [" + detail + "]") if detail != "" else ""])


## A point given in a lot's own frame, in world space.
func _at(lot_name: String, local: Vector3) -> Vector3:
	return builder.find_lot(lot_name).to_world(local)


func _flat(a: Vector3, b: Vector3) -> float:
	return Vector2(a.x - b.x, a.z - b.z).length()


func _place(at: Vector3, facing: Vector3 = Vector3.FORWARD) -> void:
	_release_all()
	scene.place(Transform3D(Basis.looking_at(Vector3(facing.x, 0.0, facing.z), Vector3.UP), at))
	player.mode = Player.Mode.NORMAL
	player.crouched = false
	player.stamina = player.stamina_max
	player.exhausted = false
	await _secs(0.4)


## The route to a goal, or empty if the navigation mesh does not get there.
func _route(from: Vector3, goal: Vector3) -> PackedVector3Array:
	var path := Nav.path(scene.get_world_3d(), from, goal)
	if path.is_empty():
		return path
	var end := path[path.size() - 1]
	if _flat(end, goal) > 0.8 or absf(end.y - goal.y) > 0.7:
		return PackedVector3Array()
	return path


## Walks the player to a point along the navigation mesh, steering at each
## corner of the route in turn. Returns the seconds it took, or -1 if there
## was no route or the player stopped making progress.
func _walk_to(goal: Vector3, sprint := false, limit := 120.0) -> float:
	var path := _route(player.global_position, goal)
	if path.is_empty():
		return -1.0
	if OS.has_environment("TOWN_DEBUG"):
		var text := ""
		for q in path:
			text += "  (%.1f %.2f %.1f)" % [q.x, q.y, q.z]
		print("ROUTE from %s:%s" % [_pos(), text])
	var i := 0
	var t := 0.0
	var best := INF
	var since_best := 0.0
	_hold(&"move_forward")
	if sprint:
		_hold(&"sprint")
	while i < path.size() and t < limit:
		var to := path[i] - player.global_position
		var flat := Vector2(to.x, to.z).length()
		if flat < (0.3 if i == path.size() - 1 else 0.4) and absf(to.y) < 1.2:
			i += 1
			best = INF
			since_best = 0.0
			continue
		player.set_look(atan2(-to.x, -to.z))
		await get_tree().physics_frame
		t += 1.0 / TICKS
		if flat < best - 0.02:
			best = flat
			since_best = 0.0
		else:
			since_best += 1.0 / TICKS
			if since_best > 2.5:
				break
	_release_all()
	await _secs(0.3)
	return t if i >= path.size() else -1.0


func _pos() -> String:
	var p := player.global_position
	return "at %.1f %.2f %.1f" % [p.x, p.y, p.z]


## Where the player stands, in a lot's own frame.
func _in_lot(lot_name: String) -> Vector3:
	return builder.find_lot(lot_name).xform.affine_inverse() * player.global_position


# --- self test -----------------------------------------------------------------

func _selftest() -> void:
	_test_layout()
	await _test_navigation()
	await _test_walks()


func _test_layout() -> void:
	var unknown := {}
	var solid := 0
	for lot in builder.lots:
		for piece in lot.boxes:
			if not TownBuilder.COLORS.has(piece.tag):
				unknown[piece.tag] = true
			if piece.solid:
				solid += 1
	_check("layout: every box has a known material tag", unknown.is_empty(), str(unknown.keys()))
	_check("layout: the four buildings, the street and its ends are there", builder.find_lot("gas_station") != null
			and builder.find_lot("miller") != null and builder.find_lot("brick_row") != null and builder.find_lot("clark") != null
			and builder.find_lot("street") != null and solid > 600, "%d solid boxes in %d lots" % [solid, builder.lots.size()])
	var needed: Array[StringName] = [&"police_car", &"flat_tyre", &"car_trunk", &"paper_map", &"trunk_key", &"pipe",
			&"first_creature", &"belt", &"jack", &"toolbox_mimic", &"spare_key", &"nest", &"gun_cabinet", &"battery",
			&"garage_door", &"south_block", &"north_block"]
	var missing: Array[StringName] = []
	for id in needed:
		if builder.marker_position(id) == Vector3.INF:
			missing.append(id)
	_check("layout: every story item of the design document has a marker", missing.is_empty(), str(missing))
	var car := builder.marker_position(&"police_car")
	var run := _flat(car, builder.marker_position(&"battery"))
	_check("layout: the battery is 150 to 200 m from the police car", run > 150.0 and run < 200.0, "%.0f m" % run)
	var lamps := get_tree().get_nodes_in_group(&"street_lamp")
	var shadowed := 0
	for lamp: Light3D in lamps:
		if lamp.shadow_enabled:
			shadowed += 1
	_check("layout: street lamps and indoor lights are all on the power switch", lamps.size() > 30 and shadowed == 9,
			"%d lights, %d with shadows" % [lamps.size(), shadowed])
	_check("layout: the player starts on the forecourt beside the police car", player.is_on_floor()
			and absf(player.global_position.y - 0.03) < 0.03 and _flat(player.global_position, car) < 3.0, _pos())


## Destinations: [name, lot, point in the lot's frame].
func _destinations() -> Array:
	var shop := TownLayout.SHOP_FLOOR
	var house := TownLayout.HOUSE_FLOOR
	var up := house + TownLayout.STOREY
	return [
		["the store's sales floor", "gas_station", Vector3(21.0, shop, 25.5)],
		["the staff room", "gas_station", Vector3(29.0, shop, 28.0)],
		["the bins in the back alley", "gas_station", Vector3(18.0, 0.03, 34.5)],
		["Miller's shop counter", "miller", Vector3(8.0, shop, 12.6)],
		["Miller's workshop", "miller", Vector3(11.5, shop, 22.0)],
		["the bottom of the pit", "miller", Vector3(7.95, shop - 1.5, 20.0)],
		["the loft office", "miller", Vector3(3.6, shop + 2.73, 19.0)],
		["Miller's yard", "miller", Vector3(22.0, 0.03, 12.0)],
		["the laundromat", "brick_row", Vector3(25.5, shop, 6.5)],
		["the room behind the laundromat", "brick_row", Vector3(24.0, shop, 15.0)],
		["the tavern, inside the U of the bar", "brick_row", Vector3(38.5, shop, 8.3)],
		["the tavern office", "brick_row", Vector3(42.5, shop, 14.5)],
		["the Clarks' living room", "clark", Vector3(6.5, house, 9.5)],
		["the Clarks' kitchen", "clark", Vector3(4.9, house, 13.6)],
		["the nest bedroom upstairs", "clark", Vector3(8.6, up, 8.6)],
		["the closet with the gun cabinet", "clark", Vector3(5.6, up, 14.5)],
		["the upstairs bathroom", "clark", Vector3(10.5, up, 14.5)],
		["the garage, at the battery", "clark", Vector3(15.6, 0.1, 13.4)],
		["the far end of the cross street", "street", Vector3(TownLayout.centre(0.0).x + 44.0, 0.02, 0.0)],
	]


func _test_navigation() -> void:
	var town: Node3D = scene.get_node("Town")
	var started := Time.get_ticks_msec()
	scene.nav_region = Nav.bake(town, NAV_BOUNDS, 1, NAV_RADIUS, NAV_CELL)
	var took := (Time.get_ticks_msec() - started) / 1000.0
	# The map takes the new region in on its own time (asynchronously): wait until it has.
	var map := scene.get_world_3d().navigation_map
	for i in 1200:
		await get_tree().physics_frame
		if NavigationServer3D.map_get_iteration_id(map) > 0 and not NavigationServer3D.map_get_path(
				map, scene.extras["spawn"].origin, scene.extras["spawn"].origin + Vector3(3, 0, 0), true).is_empty():
			break
	var polygons: int = scene.nav_region.navigation_mesh.get_polygon_count()
	_check("navigation: the mesh bakes from the town's collision", polygons > 300, "%d polygons in %.1f s" % [polygons, took])
	var from: Vector3 = scene.extras["spawn"].origin
	var unreachable: Array[String] = []
	for d: Array in _destinations():
		var goal := _at(d[1], d[2])
		if _route(from, goal).is_empty():
			var raw := Nav.path(scene.get_world_3d(), from, goal)
			var end := raw[raw.size() - 1] if not raw.is_empty() else Vector3.INF
			unreachable.append("%s (the route stops %.1f m short and %.1f m lower, at %.1f %.1f %.1f; goal %.1f %.1f %.1f)" % [
					d[0], _flat(end, goal), goal.y - end.y, end.x, end.y, end.z, goal.x, goal.y, goal.z])
	_check("navigation: one mesh joins the police car to every room of every building", unreachable.is_empty(),
			"no route to: " + "; ".join(unreachable))
	var north := Nav.path(scene.get_world_3d(), from, TownLayout.beside(-122.0, 0.0))
	_check("navigation: nothing leads out past the fog at the north end", north.is_empty()
			or north[north.size() - 1].z > -114.0)


func _test_walks() -> void:
	var shop := TownLayout.SHOP_FLOOR
	var house := TownLayout.HOUSE_FLOOR
	var up := house + TownLayout.STOREY

	# Gas station: forecourt -> store -> staff room; then round the side into the back alley.
	scene.respawn()
	await _secs(0.4)
	var t := await _walk_to(_at("gas_station", Vector3(21.0, shop, 25.5)))
	_check("walk: from the car into the store", t > 0.0 and absf(player.global_position.y - shop) < 0.05, "%.1f s, %s" % [t, _pos()])
	t = await _walk_to(_at("gas_station", Vector3(29.0, shop, 28.0)))
	_check("walk: through to the staff room", t > 0.0, "%.1f s, %s" % [t, _pos()])
	t = await _walk_to(_at("gas_station", Vector3(18.0, 0.03, 34.5)))
	_check("walk: out, round the building and through the gate to the bins", t > 0.0, "%.1f s, %s" % [t, _pos()])

	# Miller's: shop -> workshop -> down the pit steps -> back up -> loft -> out of the window.
	await _place(scene.stop_at(&"miller_door").origin)
	t = await _walk_to(_at("miller", Vector3(8.0, shop, 12.6)))
	_check("walk: into Miller's and down an aisle to the counter", t > 0.0, "%.1f s, %s" % [t, _pos()])
	t = await _walk_to(_at("miller", Vector3(7.95, shop - 1.5, 20.0)))
	_check("walk: through the workshop and down into the pit, standing under the car", t > 0.0
			and absf(player.global_position.y - (shop - 1.5)) < 0.06 and not player.crouched, "%.1f s, %s" % [t, _pos()])
	t = await _walk_to(_at("miller", Vector3(3.6, shop + 2.73, 19.0)))
	_check("walk: out of the pit and up to the loft", t > 0.0 and absf(player.global_position.y - (shop + 2.73)) < 0.06,
			"%.1f s, %s" % [t, _pos()])
	await _place(_at("miller", Vector3(3.5, shop + 2.73, 16.4)), -builder.find_lot("miller").xform.basis.z)
	_hold(&"move_forward")
	await _secs(0.3)
	_send(&"jump", true)
	await _secs(0.05)
	_send(&"jump", false)
	await _secs(1.5)
	_release_all()
	await _secs(0.4)
	var roof := _in_lot("miller")
	_check("walk: the loft window climbs out onto the awning roof", roof.z < 14.9 and absf(roof.y - (shop + 3.6)) < 0.1,
			"lot %.1f %.2f %.1f" % [roof.x, roof.y, roof.z])

	# The brick row.
	await _place(scene.stop_at(&"laundry_door").origin)
	t = await _walk_to(_at("brick_row", Vector3(24.0, shop, 15.0)))
	_check("walk: through the laundromat to its back room", t > 0.0, "%.1f s, %s" % [t, _pos()])
	t = await _walk_to(_at("brick_row", Vector3(42.5, shop, 14.5)))
	_check("walk: out, next door into the tavern and to its office", t > 0.0, "%.1f s, %s" % [t, _pos()])

	# The Clark house: porch, hall, kitchen, stairs, bedroom, closet; then the garage.
	await _place(scene.stop_at(&"clark_door", 5.0).origin)
	t = await _walk_to(_at("clark", Vector3(4.9, house, 13.6)))
	_check("walk: up the Clarks' porch and through to the kitchen", t > 0.0 and absf(player.global_position.y - house) < 0.06,
			"%.1f s, %s" % [t, _pos()])
	t = await _walk_to(_at("clark", Vector3(8.6, up, 8.6)))
	_check("walk: upstairs into the nest bedroom", t > 0.0 and absf(player.global_position.y - up) < 0.06, "%.1f s, %s" % [t, _pos()])
	t = await _walk_to(_at("clark", Vector3(5.6, up, 14.5)))
	_check("walk: to the gun cabinet in the closet", t > 0.0, "%.1f s, %s" % [t, _pos()])
	t = await _walk_to(_at("clark", Vector3(15.6, 0.1, 13.4)))
	_check("walk: back down, out and into the garage to the battery", t > 0.0, "%.1f s, %s" % [t, _pos()])

	# The run the finale is built on: battery to police car, sprinting while there is breath for it.
	t = await _walk_to(scene.extras["spawn"].origin, true, 200.0)
	_check("walk: garage to police car takes about a minute", t > 30.0 and t < 75.0, "%.0f s, %s" % [t, _pos()])

	# The edges hold.
	await _place(TownLayout.beside(-108.0, 0.0, 0.1), Vector3.FORWARD)
	_hold(&"move_forward")
	_hold(&"sprint")
	await _secs(4.0)
	_release_all()
	_check("walk: the north end is closed", player.global_position.z > -113.2 and player.is_on_floor(), _pos())
	await _place(TownLayout.beside(104.0, -2.5, 0.1), Vector3.BACK)
	_hold(&"move_forward")
	_hold(&"sprint")
	await _secs(4.0)
	_release_all()
	_check("walk: the south end is closed", player.global_position.z < 119.5, _pos())


# --- screenshots ---------------------------------------------------------------

func _snap(dir: String, shot: String, settle := 8) -> void:
	await _frames(settle)
	await RenderingServer.frame_post_draw
	var path := dir.path_join(shot + ".png")
	get_viewport().get_texture().get_image().save_png(path)
	print("SHOT ", path)


func _shoot(dir: String) -> void:
	DirAccess.make_dir_recursive_absolute(dir)
	var only := PackedStringArray()
	for arg in OS.get_cmdline_user_args():
		if arg.begins_with("--shots="):
			only = arg.trim_prefix("--shots=").split(",")
	scene.hud.visible = false
	scene._overlay.visible = false
	var shop := TownLayout.SHOP_FLOOR
	var house := TownLayout.HOUSE_FLOOR
	var up := house + TownLayout.STOREY
	var cx := TownLayout.centre(0.0).x
	# name, lot, where the player stands (feet), what to look at
	var shots := [
		["01_start_at_the_pumps", "gas_station", Vector3(12.0, 0.05, 4.0), Vector3(22.0, 1.8, 22.0)],
		["02_store_inside", "gas_station", Vector3(22.8, shop, 21.2), Vector3(19.0, 1.2, 28.5)],
		["03_main_street_south", "street", TownLayout.beside(-84.0, 0.5, 0.05), TownLayout.beside(-40.0, 0.5, 1.6)],
		["04_miller_front", "miller", Vector3(9.0, 0.2, -7.0), Vector3(8.0, 2.0, 6.0)],
		["05_miller_workshop_from_loft", "miller", Vector3(4.6, shop + 2.73, 21.5), Vector3(9.5, 0.2, 22.5)],
		["06_miller_pit", "miller", Vector3(7.95, shop - 1.5, 24.2), Vector3(7.95, -0.6, 19.0)],
		["07_tavern", "brick_row", Vector3(37.5, shop, 1.6), Vector3(38.5, 1.2, 9.0)],
		["08_laundromat", "brick_row", Vector3(25.5, shop, 1.4), Vector3(25.5, 1.0, 12.0)],
		["09_cross_street_corner", "street", Vector3(cx - 2.0, 0.05, -9.0), Vector3(cx + 14.0, 2.5, 8.0)],
		["10_clark_house", "clark", Vector3(11.0, 0.2, -6.5), Vector3(11.0, 3.5, 9.0)],
		["11_clark_hall", "clark", Vector3(11.0, house, 8.0), Vector3(12.0, house + 1.6, 14.5)],
		["12_clark_nest", "clark", Vector3(8.9, up, 7.8), Vector3(6.6, up + 0.9, 10.8)],
		["13_south_end", "street", TownLayout.beside(96.0, -1.0, 0.05), TownLayout.beside(114.0, 0.0, 1.5)],
	]
	for shot: Array in shots:
		if not only.is_empty() and not only.has(String(shot[0]).left(2)):
			continue
		await _place(_at(shot[1], shot[2]))
		var dir_to := _at(shot[1], shot[3]) - player.camera.global_position
		player.set_look(atan2(-dir_to.x, -dir_to.z), asin(dir_to.normalized().y))
		await _snap(dir, shot[0], 70)                # the fog needs about 60 frames after a big jump

	# Two views from the air in flat daylight with the fog off: the model, not the mood.
	if only.is_empty() or only.has("air"):
		var atmosphere: Atmosphere = scene.atmosphere
		atmosphere.time_of_day = 12.0
		atmosphere.volumetric_fog = false
		atmosphere._env.fog_enabled = false
		var cam := Camera3D.new()
		cam.fov = 50.0
		cam.far = 600.0
		scene.add_child(cam)
		cam.make_current()
		for view: Array in [["14_air_north_half", Vector3(-70.0, 75.0, -25.0), Vector3(8.0, 0.0, -68.0)],
				["15_air_south_half", Vector3(-70.0, 75.0, 95.0), Vector3(10.0, 0.0, 45.0)]]:
			cam.look_at_from_position(view[1], view[2])
			await _snap(dir, view[0], 20)
		cam.queue_free()

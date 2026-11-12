extends Node3D
## The demo town as a greybox you can walk: the 250 x 250 m map of the design
## document built from TownLayout, at three in the morning with the power on.
## The power grid runs through it (a cell under every street lamp, one circuit
## per building) and television sets sleep in the four buildings; there are no
## pickups yet. Markers show where things will go.
##
##   WASD move    Shift sprint    Ctrl / C crouch    Space jump, climb, vault
##   Q / E lean   F flashlight    LMB fire    RMB aim    R reload    1 2 3 weapons
##
##   F6 F7 F8 F9  jump to: gas station / Miller's / tavern + laundromat / Clark house
##   L    marker labels on / off      P  power on / off       [ ]  time -/+ half an hour
##   N    jolt the nearest grid cell (+25)      M  creatures on / off
##   F1   help    F2  numbers     F3  back to the shooting range     F5  back to the start
##   F11  fullscreen     Esc  free the mouse     F10 quit
##
## Command line (after "--"):
##   --res=1280x720   render size (default 1920x1080, scaled to the window)
##   --dump=<file>    write every box and marker as JSON (for tools/draw_town_plan.py), then quit
##   --selftest       scripted walk through all four buildings, then quit
##   --shoot=<dir>    scripted screenshots, then quit

const RANGE_SCENE := "res://test/range_street.tscn"
const HELP := """WASD move   Shift sprint   Ctrl/C crouch   Space jump / climb   Q E lean   F flashlight   LMB fire   RMB aim
F6 gas station   F7 Miller's   F8 tavern + laundromat   F9 Clark house   L labels   P power   [ ] time
N jolt the power grid here (+25)   M creatures on / off
F1 help   F2 numbers   F3 shooting range   F5 back to start   F11 fullscreen   Esc mouse   F10 quit"""
## Where the F-keys put you: marker, metres back from it towards the street.
const CRT: CreatureData = preload("res://creatures/data/crt.tres")
## What the navigation mesh covers: the whole walkable town, pit to upper floors.
const NAV_BOUNDS := AABB(Vector3(-78.0, -2.0, -116.0), Vector3(176.0, 9.0, 238.0))
## The town is laid out at an angle to the grid and has 1 m doors: on the
## range's 0.25 m cells a door can close up, so the town bakes at half that.
const NAV_RADIUS := 0.25
const NAV_CELL := 0.125
## The buildings with a circuit of their own, north to south.
const CIRCUITS: Array[String] = ["gas_station", "miller", "brick_row", "clark"]
## Where the television sets sleep: lot, spot on the floor (lot space). Each is
## moved to the nearest point of the navigation mesh, so it can always walk off.
## Only the CRT exists so far; it stands in for everything the design puts here.
static var SLEEPERS: Array = [
	["gas_station", Vector3(29.0, TownLayout.SHOP_FLOOR, 28.0)],           # staff room
	["gas_station", Vector3(18.0, 0.03, 34.5)],                            # by the bins (the tutorial fight)
	["miller", Vector3(8.0, TownLayout.SHOP_FLOOR, 12.6)],                 # behind the shop counter
	["miller", Vector3(3.6, TownLayout.SHOP_FLOOR + 2.73, 19.0)],          # loft office
	["brick_row", Vector3(24.0, TownLayout.SHOP_FLOOR, 15.0)],             # room behind the laundromat
	["brick_row", Vector3(38.5, TownLayout.SHOP_FLOOR, 8.3)],              # inside the U of the bar
	["clark", Vector3(6.5, TownLayout.HOUSE_FLOOR, 9.5)],                  # living room
	["clark", Vector3(8.6, TownLayout.HOUSE_FLOOR + TownLayout.STOREY, 8.6)],    # the nest
	["clark", Vector3(8.7, TownLayout.HOUSE_FLOOR + TownLayout.STOREY, 11.7)],   # the nest
]
const STOPS := {KEY_F7: &"miller_door", KEY_F8: &"tavern_door", KEY_F9: &"clark_door"}

var builder: TownBuilder
var extras: Dictionary
var atmosphere: Atmosphere
var player: Player
var hud: Hud
var nav_region: NavigationRegion3D
var labels: Array[Label3D] = []
## The power grid: street-lamp cells north to south, then one per circuit.
var grid_nodes: Array[GridNode] = []
var circuit_nodes := {}                  # lot name -> GridNode
var creatures: Array[Creature] = []
var lights_by_lot := {}                  # lot name -> Array[Light3D]

var _overlay: Label
var _help: Label
var _automated := false
var _frame_ms := 0.0


func _ready() -> void:
	var render_size := Vector2i(1920, 1080)
	var auto_mode := ""
	var auto_arg := ""
	for arg in OS.get_cmdline_user_args():
		if arg.begins_with("--res="):
			var parts := arg.trim_prefix("--res=").split("x")
			if parts.size() == 2 and int(parts[0]) > 0 and int(parts[1]) > 0:
				render_size = Vector2i(int(parts[0]), int(parts[1]))
		elif arg == "--selftest":
			auto_mode = "selftest"
		elif arg.begins_with("--shoot="):
			auto_mode = "shoot"
			auto_arg = arg.trim_prefix("--shoot=")
		elif arg.begins_with("--dump="):
			auto_mode = "dump"
			auto_arg = arg.trim_prefix("--dump=")
		elif arg.begins_with("--export-gltf="):
			auto_mode = "gltf"
			auto_arg = arg.trim_prefix("--export-gltf=")
		elif arg.begins_with("--check-model="):
			auto_mode = "model"
			auto_arg = arg.trim_prefix("--check-model=")
	_automated = auto_mode != ""

	builder = TownBuilder.new()
	extras = TownLayout.build(builder)
	if auto_mode == "dump":
		var file := FileAccess.open(auto_arg, FileAccess.WRITE)
		file.store_string(JSON.stringify(builder.dump()))
		file.close()
		print("DUMP ", auto_arg)
		get_tree().quit()
		return

	if auto_mode == "gltf":
		_export_gltf(auto_arg)
		get_tree().quit()
		return
	if auto_mode == "model":
		var ok := await _check_model(auto_arg)
		get_tree().quit(0 if ok else 1)
		return

	var window := get_window()
	window.content_scale_mode = Window.CONTENT_SCALE_MODE_VIEWPORT
	window.content_scale_aspect = Window.CONTENT_SCALE_ASPECT_KEEP
	window.content_scale_size = render_size
	if OS.has_feature("template") and not _automated:
		window.mode = Window.MODE_EXCLUSIVE_FULLSCREEN

	atmosphere = (load("res://atmosphere/atmosphere.tscn") as PackedScene).instantiate() as Atmosphere
	add_child(atmosphere)
	atmosphere.day_length_minutes = 0.0
	atmosphere.power_on = true
	atmosphere.time_of_day = 3.0

	var town := Node3D.new()
	town.name = "Town"
	add_child(town)
	builder.commit(town)
	_make_lights()
	_make_labels()
	build_grid(false)

	player = Player.new()
	player.name = "Player"
	add_child(player)
	player.rig.infinite_reserve = true
	respawn()
	player.body.died.connect(_on_player_died)

	hud = Hud.new()
	add_child(hud)
	hud.setup(player)

	var layer := CanvasLayer.new()
	layer.layer = 110
	add_child(layer)
	_overlay = _label(layer)
	_overlay.position = Vector2(16, 12)
	_help = _label(layer)
	_help.set_anchors_and_offsets_preset(Control.PRESET_BOTTOM_LEFT, Control.PRESET_MODE_MINSIZE, 16)
	_help.grow_vertical = Control.GROW_DIRECTION_BEGIN
	_help.text = HELP

	atmosphere.set_time(atmosphere.time_of_day)      # the lamps exist now: sync them

	if not _automated:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
		# The sets need the navigation mesh to stand on: a few seconds, once.
		await bake_nav()
		build_grid(true)
	else:
		_help.visible = false
		var auto := TownAuto.new()
		add_child(auto)
		auto.start(self, auto_mode, auto_arg)


## The greybox as a .glb for Blender: one object per lot and material tag (flat
## colours, materials named after the tags), plus an empty for every marker.
## See docs/blender_town_workflow.md.
func _export_gltf(path: String) -> void:
	TownBuilder.use_textures = false
	var root := Node3D.new()
	root.name = "Town"
	add_child(root)
	builder.commit(root)
	for lot in builder.lots:
		for child in lot.node.get_children():
			if child is MeshInstance3D:
				child.name = "%s__%s" % [lot.name, child.name]
			else:
				child.free()                        # collision bodies are rebuilt from the Blender model
		for m in lot.markers:
			var empty := Node3D.new()
			empty.name = "marker_%s" % m.id
			lot.node.add_child(empty)
			empty.position = m.position
	var doc := GLTFDocument.new()
	var state := GLTFState.new()
	var err := doc.append_from_scene(root, state)
	if err == OK:
		err = doc.write_to_filesystem(state, path)
	print("GLTF %s %s" % [path, "ok" if err == OK else "FAILED %d" % err])


## Loads a town model that came back from Blender and checks it against the
## greybox it started from: is everything where the layout says it is?
func _check_model(path: String) -> bool:
	var model := TownModel.load_file(path, self)
	if model == null:
		print("MODEL cannot load ", path)
		return false
	print("MODEL ", model.summary())
	var failed := 0
	var worst := 0.0
	var missing: Array[String] = []
	for lot in builder.lots:
		for m in lot.markers:
			if not model.markers.has(m.id):
				missing.append(String(m.id))
				continue
			var moved := (model.markers[m.id] as Vector3).distance_to(lot.to_world(m.position))
			worst = maxf(worst, moved)
			if moved >= 0.01:
				print("NOTE  model: marker %s is %.2f m from where the greybox layout has it" % [m.id, moved])
	# Moved markers are reported above but are not a failure: rooms may be rearranged on purpose.
	print("NOTE  model: the furthest any marker has moved is %.3f m" % worst)
	var checks := [
		["every marker of the layout is in the model", missing.is_empty(), ", ".join(missing)],
		["the model has meshes and every one got a collider", model.mesh_count > 50 and model.collider_count == model.mesh_count, ""],
	]
	# physics: there is ground to stand on beside the police car (ray from under the canopy)
	await get_tree().physics_frame
	await get_tree().physics_frame
	var space := get_world_3d().direct_space_state
	var car: Vector3 = builder.marker_position(&"police_car")
	var down := space.intersect_ray(PhysicsRayQueryParameters3D.create(car + Vector3(3, 1.5, 0), car + Vector3(3, -6, 0), 1))
	checks.append(["a ray down beside the police car lands on the forecourt", not down.is_empty() and absf(down.position.y) < 0.3,
			"hit %s" % (str(down.position) if not down.is_empty() else "nothing")])
	var textured := 0
	for key: String in model.materials:
		if model.materials[key][1] != "kept":
			textured += 1
	checks.append(["greybox tags found their material sets", textured >= 10, "%d of %d materials" % [textured, model.materials.size()]])
	for c: Array in checks:
		print("%s  model: %s%s" % ["PASS" if c[1] else "FAIL", c[0], ("   [" + c[2] + "]") if c[2] != "" else ""])
		if not c[1]:
			failed += 1
	print("MODEL CHECK %d passed, %d failed" % [checks.size() - failed, failed])
	return failed == 0


func _label(layer: CanvasLayer) -> Label:
	var label := Label.new()
	label.add_theme_font_size_override(&"font_size", 18)
	label.add_theme_color_override(&"font_outline_color", Color.BLACK)
	label.add_theme_constant_override(&"outline_size", 6)
	layer.add_child(label)
	return label


## Street lamps are shadowed spots over the road; everything indoors is a
## plain point light. All of them follow the atmosphere's power switch.
func _make_lights() -> void:
	for l: Dictionary in extras["lights"]:
		var light: Light3D
		if l["kind"] == &"street":
			var spot := SpotLight3D.new()
			spot.rotation_degrees.x = -90.0
			spot.spot_angle = 64.0
			spot.spot_angle_attenuation = 0.6
			spot.spot_range = l["range"]
			spot.light_volumetric_fog_energy = 2.0
			spot.shadow_enabled = true
			light = spot
			var bulb := MeshInstance3D.new()
			var mesh := BoxMesh.new()
			mesh.size = Vector3(0.5, 0.18, 0.04)
			var mat := StandardMaterial3D.new()
			mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
			mat.albedo_color = Color(1.0, 0.55, 0.18) * 6.0
			mesh.material = mat
			bulb.mesh = mesh
			bulb.position = Vector3(0.0, 0.0, 0.07)
			spot.add_child(bulb)
		else:
			var omni := OmniLight3D.new()
			omni.omni_range = l["range"]
			omni.omni_attenuation = 1.4
			omni.light_volumetric_fog_energy = 0.6
			light = omni
		light.light_color = l["color"]
		light.light_energy = l["energy"]
		light.position = l["at"]
		light.add_to_group(&"street_lamp")
		add_child(light)
		if not lights_by_lot.has(l["lot"]):
			lights_by_lot[l["lot"]] = []
		lights_by_lot[l["lot"]].append(light)


## Bakes the navigation mesh from the town's collision and waits until the
## navigation map has taken it in (it does that on its own time). Does nothing
## the second time.
func bake_nav() -> void:
	if nav_region != null:
		return
	nav_region = Nav.bake(get_node("Town"), NAV_BOUNDS, 1, NAV_RADIUS, NAV_CELL)
	var map := get_world_3d().navigation_map
	var from: Vector3 = extras["spawn"].origin
	for i in 1200:
		await get_tree().physics_frame
		if NavigationServer3D.map_get_iteration_id(map) > 0 and not NavigationServer3D.map_get_path(
				map, from, from + Vector3(3, 0, 0), true).is_empty():
			return


## (Re)builds the power grid from scratch. A cell under every street lamp,
## wired in a line from north to south; one cell per building circuit, wired
## to the nearest lamp and driving every light in that building. With
## creatures (needs the navigation mesh), the sets of SLEEPERS hang on their
## building's circuit; without, the grid is wires and lamps only.
func build_grid(with_creatures := false) -> void:
	for old in creatures:
		old.queue_free()
	creatures.clear()
	for old in grid_nodes:
		old.queue_free()
	grid_nodes.clear()
	circuit_nodes.clear()
	Grid.reset()
	var street: Array = lights_by_lot.get("street", []).duplicate()
	street.sort_custom(func(a: Light3D, b: Light3D) -> bool: return a.position.z < b.position.z)
	for lamp: Light3D in street:
		var node := GridNode.new()
		node.position = Vector3(lamp.position.x, 0.0, lamp.position.z)
		node.lamp = lamp
		node.roster = {}
		add_child(node)
		if not grid_nodes.is_empty():
			node.link_to(grid_nodes.back())
		grid_nodes.append(node)
	var poles := grid_nodes.duplicate()
	for lot_name in CIRCUITS:
		var lamps: Array = lights_by_lot.get(lot_name, [])
		var middle := Vector3.ZERO
		for l: Light3D in lamps:
			middle += l.position
		middle /= maxf(lamps.size(), 1.0)
		var sleepers := 0
		for s: Array in SLEEPERS:
			if s[0] == lot_name:
				sleepers += 1
		var node := GridNode.new()
		node.position = Vector3(middle.x, 0.0, middle.z)
		node.lamps.assign(lamps)
		node.roster = {CRT.grid_kind: sleepers} if with_creatures else {}
		add_child(node)
		var best: GridNode = null
		for pole: GridNode in poles:
			if best == null or pole.position.distance_squared_to(node.position) < best.position.distance_squared_to(node.position):
				best = pole
		if best != null:
			node.link_to(best)
		grid_nodes.append(node)
		circuit_nodes[lot_name] = node
	if not with_creatures:
		return
	var map := get_world_3d().navigation_map
	var entries := {}
	for s: Array in SLEEPERS:
		var lot := builder.find_lot(s[0])
		var want: Vector3 = lot.xform * (s[1] as Vector3)
		var at := NavigationServer3D.map_get_closest_point(map, want)
		if at.distance_to(want) > 1.5:
			push_warning("Town: no floor for a sleeper near %s in %s" % [s[1], s[0]])
			at = want
		var node: GridNode = circuit_nodes[s[0]]
		var entry: int = entries.get(s[0], 0)
		entries[s[0]] = entry + 1
		# Facing the middle of the building, more or less: towards its circuit.
		var to_middle := node.position - at
		creatures.append(Creature.spawn(CRT, self, at + Vector3.UP * 0.3, atan2(-to_middle.x, -to_middle.z), node.id, entry))


func _make_labels() -> void:
	for lot in builder.lots:
		for m in lot.markers:
			var label := Label3D.new()
			label.text = m.label
			label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
			label.no_depth_test = true
			label.fixed_size = true
			label.pixel_size = 0.0011
			label.modulate = Color(1.0, 0.9, 0.4)
			label.outline_size = 8
			label.position = lot.xform * m.position + Vector3.UP * 0.25
			label.visible = false
			add_child(label)
			labels.append(label)


func respawn() -> void:
	place(extras["spawn"])


## Nothing is at stake in the greybox: dying puts you back at the police car.
func _on_player_died(_cause: StringName) -> void:
	if _automated:
		return
	await get_tree().create_timer(5.0).timeout
	if player.body.sim.dead:
		respawn()


## Stands the player at a transform (its -Z is the way they face).
func place(at: Transform3D) -> void:
	player.body.reset()
	player.global_position = at.origin
	player.velocity = Vector3.ZERO
	player.set_look(at.basis.get_euler().y)


## A spot on the pavement in front of a door marker, facing the door.
func stop_at(marker: StringName, back := 2.5) -> Transform3D:
	for lot in builder.lots:
		for m in lot.markers:
			if m.id == marker:
				var into := lot.xform.basis.z                     # a lot's +Z points away from the street
				var at := lot.xform * m.position - into * back
				at.y = 0.4
				return Transform3D(Basis.looking_at(into, Vector3.UP), at)
	return extras["spawn"]


func _input(event: InputEvent) -> void:
	if _automated:
		return
	if event is InputEventMouseButton and event.pressed and Input.mouse_mode != Input.MOUSE_MODE_CAPTURED:
		Input.mouse_mode = Input.MOUSE_MODE_CAPTURED
		get_viewport().set_input_as_handled()
		return
	if event is InputEventKey and event.pressed and not event.echo:
		var key: Key = event.physical_keycode
		if STOPS.has(key):
			place(stop_at(STOPS[key]))
			return
		match key:
			KEY_ESCAPE: Input.mouse_mode = Input.MOUSE_MODE_VISIBLE
			KEY_BRACKETLEFT: atmosphere.time_of_day = fposmod(atmosphere.time_of_day - 0.5, 24.0)
			KEY_BRACKETRIGHT: atmosphere.time_of_day = fposmod(atmosphere.time_of_day + 0.5, 24.0)
			KEY_P: atmosphere.power_on = not atmosphere.power_on
			KEY_L:
				for label in labels:
					label.visible = not label.visible
			KEY_N: Grid.sim.inject_node(Grid.sim.nearest(player.global_position), 25.0)
			KEY_M:
				if nav_region != null:
					build_grid(creatures.is_empty())
			KEY_F1: _help.visible = not _help.visible
			KEY_F2: _overlay.visible = not _overlay.visible
			KEY_F3: get_tree().change_scene_to_file(RANGE_SCENE)
			KEY_F5, KEY_F6: respawn()
			KEY_F10: get_tree().quit()
			KEY_F11:
				var window := get_window()
				var full := window.mode == Window.MODE_EXCLUSIVE_FULLSCREEN or window.mode == Window.MODE_FULLSCREEN
				window.mode = Window.MODE_WINDOWED if full else Window.MODE_EXCLUSIVE_FULLSCREEN


func _process(delta: float) -> void:
	if _overlay == null or not _overlay.visible:
		return
	var ms := delta * 1000.0
	_frame_ms = lerpf(_frame_ms, ms, 0.05) if _frame_ms > 0.0 else ms
	var p := player.global_position
	var hour := int(atmosphere.time_of_day)
	var minute := int(fposmod(atmosphere.time_of_day, 1.0) * 60.0)
	var grid_text := ""
	for lot_name in CIRCUITS:
		var node: GridNode = circuit_nodes.get(lot_name)
		if node != null:
			grid_text += ("  %s --" if Grid.sim.is_tripped(node.id) else "  %s %d") % [lot_name, int(node.charge())]
	var here := Grid.sim.nearest(p)
	_overlay.text = "%d fps   %.1f ms\n%02d:%02d   power %s\nx %.1f  z %.1f  height %.2f   %.1f m/s\ngrid here %d  %s   awake %d / %d%s" % [
		Engine.get_frames_per_second(), _frame_ms, hour, minute, "on" if atmosphere.power_on else "OFF",
		p.x, p.z, p.y, player.horizontal_speed(), int(Grid.sim.charge(here)) if here >= 0 else 0, grid_text,
		Grid.sim.count(true), Grid.sim.count(), "" if not creatures.is_empty() else "   (no creatures)"]

class_name Props
extends RefCounted
## Small interactable world objects: beds, dropped items, the escape car.


class Bed extends StaticBody3D:
	func setup(size: Vector3, xf: Transform3D) -> void:
		transform = xf
		collision_layer = G.L_WORLD | G.L_INTERACT
		collision_mask = 0
		var cs := CollisionShape3D.new()
		var bs := BoxShape3D.new()
		bs.size = size
		cs.shape = bs
		cs.position = Vector3(0, size.y * 0.5, 0)
		add_child(cs)

	func get_prompt(_p: Player) -> String:
		return "[E] 睡觉"

	func interact(p: Player) -> void:
		p.try_sleep()


class Pickup extends StaticBody3D:
	var entry: Dictionary

	func setup(e: Dictionary, pos: Vector3) -> void:
		entry = e
		position = pos
		collision_layer = G.L_INTERACT
		collision_mask = 0
		var cs := CollisionShape3D.new()
		var bs := BoxShape3D.new()
		bs.size = Vector3(0.5, 0.4, 0.5)
		cs.shape = bs
		cs.position.y = 0.2
		add_child(cs)
		var id: String = e["id"]
		if Items.WEAPONS.has(id):
			var m: Node3D = load("res://models/%s.glb" % Items.WEAPONS[id]["model"]).instantiate()
			for n in ["arm_r", "arm_l"]:
				var a := m.find_child(n, true, false)
				if a:
					a.queue_free()
			Mat.skin(m, Mat.atlas("weapons"), G.VIS_OUT | G.VIS_IN, false)
			m.rotation = Vector3(0, randf() * TAU, PI * 0.5)
			m.position.y = 0.06
			add_child(m)
		else:
			var mi := MeshInstance3D.new()
			var bm := BoxMesh.new()
			bm.size = Vector3(0.22, 0.16, 0.16)
			mi.mesh = bm
			mi.position.y = 0.08
			mi.rotation.y = randf() * TAU
			var t: String = Items.DB[id]["t"]
			var c := Color(0.5, 0.42, 0.3)
			match t:
				"ammo": c = Color(0.55, 0.45, 0.2)
				"food": c = Color(0.5, 0.25, 0.2)
				"drink": c = Color(0.25, 0.35, 0.5)
				"med": c = Color(0.7, 0.7, 0.65)
			var mm := StandardMaterial3D.new()
			mm.albedo_texture = Mat.tex("fabric")
			mm.albedo_color = c * 1.6
			mm.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
			mm.roughness = 1.0
			mi.material_override = mm
			mi.layers = G.VIS_OUT | G.VIS_IN
			add_child(mi)

	func get_prompt(_p: Player) -> String:
		var n: int = entry["n"]
		return "[E] 拾取 %s%s" % [Items.item_name(entry["id"]), (" ×%d" % n) if n > 1 else ""]

	func interact(p: Player) -> void:
		p.inv.add_entry(entry)
		Sfx.play_var("pickup", -4.0)
		queue_free()


class EscapeCar extends StaticBody3D:
	const NEED := ["car_key", "car_battery", "gas_can"]
	var installed := {}
	var starting := false
	var countdown := 0.0
	var _noise_t := 0.0

	func setup(pos: Vector3, yaw: float) -> void:
		position = pos
		rotation.y = yaw
		collision_layer = G.L_WORLD | G.L_INTERACT
		collision_mask = 0
		var cs := CollisionShape3D.new()
		var bs := BoxShape3D.new()
		bs.size = Vector3(1.8, 1.4, 4.3)
		cs.shape = bs
		cs.position.y = 0.7
		add_child(cs)
		var m: Node3D = load("res://models/car.glb").instantiate()
		Mat.skin(m, Mat.atlas("props2"), G.VIS_IN, false)
		add_child(m)

	func missing() -> Array:
		var out: Array = []
		for id in NEED:
			if not installed.has(id):
				out.append(id)
		return out

	func get_prompt(p: Player) -> String:
		if starting:
			return "引擎预热中… %d 秒" % int(ceil(countdown))
		var miss := missing()
		if miss.is_empty():
			return "[E] 发动汽车，离开这里"
		var names: Array = []
		var can := false
		for id in miss:
			names.append(Items.item_name(id))
			if p.inv.has(id):
				can = true
		return ("[E] 安装零件　" if can else "") + "还缺：" + "、".join(names)

	func interact(p: Player) -> void:
		if starting:
			return
		var miss := missing()
		if miss.is_empty():
			starting = true
			countdown = 45.0
			Sfx.play_at("engine", global_position + Vector3.UP, 4.0, 1.0, 90.0)
			G.emit_noise(global_position, 95.0, "engine")
			G.msg("引擎咳嗽着转了起来——整个镇子都听见了。守住 45 秒！", Color(0.9, 0.6, 0.3))
			return
		var did := false
		for id in miss:
			if p.inv.has(id):
				p.inv.remove(id, 1)
				installed[id] = true
				did = true
				G.msg("装上了%s。" % Items.item_name(id))
		if did:
			Sfx.play("mag_in", -3.0, 0.7)
			G.emit_noise(global_position, 10.0, "door")
		else:
			Sfx.play("deny")
			G.msg("这辆车还能开，但缺零件。", Color(0.8, 0.7, 0.5))

	func _process(delta: float) -> void:
		if not starting or not G.playing:
			return
		countdown -= delta
		_noise_t -= delta
		if _noise_t <= 0.0:
			_noise_t = 2.4
			Sfx.play_at("engine", global_position + Vector3.UP, 2.0, randf_range(0.9, 1.15), 90.0)
			G.emit_noise(global_position, 95.0, "engine")
		if countdown <= 0.0:
			starting = false
			G.main.end_run(true)

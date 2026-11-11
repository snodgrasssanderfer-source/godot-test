class_name WindowPane
extends StaticBody3D
## A glazed window opening. Glass can be smashed (loud); once broken both the
## player and zombies can climb through, unless it is boarded up.

const W := 1.4
const Y0 := 0.9
const Y1 := 2.1

var broken := false
var planks := 0
var plank_hp := 0.0
var centre := Vector3.ZERO     # opening centre at floor level
var normal := Vector3.BACK
var along_x := true
var _glass: MeshInstance3D
var _plank_mesh: MeshInstance3D
var _shake := 0.0


func setup(p_centre: Vector3, p_along_x: bool) -> void:
	centre = p_centre
	along_x = p_along_x
	normal = Vector3.BACK if along_x else Vector3.RIGHT
	position = centre
	rotation.y = 0.0 if along_x else -PI * 0.5
	collision_layer = G.L_GLASS
	collision_mask = 0
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = Vector3(W, Y1 - Y0, 0.1)
	cs.shape = bs
	cs.position = Vector3(0, (Y0 + Y1) * 0.5, 0)
	add_child(cs)
	_glass = MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(W, Y1 - Y0)
	_glass.mesh = q
	_glass.position = Vector3(0, (Y0 + Y1) * 0.5, 0)
	_glass.material_override = Mat.get_mat("glass")
	_glass.layers = G.VIS_OUT | G.VIS_IN
	_glass.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(_glass)


func _process(delta: float) -> void:
	if _shake > 0.0:
		_shake = maxf(0.0, _shake - delta * 4.0)
		_glass.position.z = sin(_shake * 40.0) * 0.02 * _shake


func blocks() -> bool:
	return not broken or planks > 0


func get_prompt(p: Player) -> String:
	if planks > 0:
		return "[E] 拆下木板（已加固 %d/3）" % planks + ("　[B] 继续加固" if planks < 3 and p.can_barricade() else "")
	var s := "[空格] 翻窗" if broken else "[V] 砸碎玻璃"
	if p.can_barricade():
		s += "　[B] 用木板加固"
	return s


func interact(p: Player) -> void:
	if planks > 0:
		planks -= 1
		plank_hp = planks * 70.0
		p.inv.add("plank", 1)
		Sfx.play_at("wood_break", centre + Vector3.UP * 1.5, -8.0, 1.3)
		G.emit_noise(centre, 8.0, "door")
		_refresh()


func barricade(p: Player) -> bool:
	if planks >= 3:
		return false
	planks += 1
	plank_hp = planks * 70.0
	Sfx.play_at("hammer", centre + Vector3.UP * 1.5, 0.0)
	G.emit_noise(centre, 16.0, "hammer")
	_refresh()
	return true


func smash() -> void:
	if broken:
		return
	broken = true
	_glass.material_override = Mat.get_mat("glass_broken")
	Sfx.play_at("glass_break", centre + Vector3.UP * 1.5, 2.0, randf_range(0.9, 1.1), 70.0)
	G.emit_noise(centre, 26.0, "glass")
	_refresh()


func damage(amount: float) -> void:
	_shake = 1.0
	if planks > 0:
		plank_hp -= amount
		if amount >= 8.0:
			Sfx.play_at("door_bang", centre + Vector3.UP * 1.5, -2.0, randf_range(1.0, 1.25), 60.0)
			G.emit_noise(centre, 14.0, "door")
		var left := int(ceil(plank_hp / 70.0))
		if left < planks:
			planks = maxi(left, 0)
			Sfx.play_at("wood_break", centre + Vector3.UP * 1.5, 2.0, 1.1, 60.0)
			G.emit_noise(centre, 20.0, "break")
			_refresh()
	elif not broken:
		smash()


func _refresh() -> void:
	if _plank_mesh:
		_plank_mesh.queue_free()
		_plank_mesh = null
	if planks > 0:
		var mb := MeshBuilder.new()
		for i in planks:
			var y := Y0 + 0.2 + i * 0.4
			var xf := Transform3D(Basis(Vector3.BACK, [0.07, -0.05, 0.03][i]), Vector3(0, y, 0))
			mb.box(xf, Vector3(W + 0.5, 0.22, 0.16), "wood")
		_plank_mesh = mb.commit(self, G.VIS_IN | G.VIS_OUT, false)
	# intact glass blocks like glass; planks block like a wall; an open hole blocks nothing
	if planks > 0:
		collision_layer = G.L_WORLD
	elif broken:
		collision_layer = 0
	else:
		collision_layer = G.L_GLASS

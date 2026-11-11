class_name Door
extends AnimatableBody3D
## Hinged door. Players open/close it, zombies have to break it down.
## Can be reinforced with planks (needs a hammer).

const W := 1.1
const HGT := 2.1

var is_open := false
var broken := false
var metal := false
var hp := 110.0
var planks := 0
var centre := Vector3.ZERO      # opening centre on the floor
var normal := Vector3.BACK      # wall normal
var _base_yaw := 0.0
var _mesh: MeshInstance3D
var _plank_mesh: MeshInstance3D
var _shape: CollisionShape3D
var _shake := 0.0


func setup(p_centre: Vector3, along_x: bool, p_metal: bool, exterior: bool) -> void:
	centre = p_centre
	metal = p_metal
	hp = 220.0 if metal else 110.0
	normal = Vector3.BACK if along_x else Vector3.RIGHT
	var dir := Vector3.RIGHT if along_x else Vector3.BACK
	_base_yaw = 0.0 if along_x else -PI * 0.5
	position = centre - dir * (W * 0.5)
	rotation.y = _base_yaw
	collision_layer = G.L_WORLD
	collision_mask = 0
	sync_to_physics = false
	var mb := MeshBuilder.new()
	var tex := "metal_door" if metal else "door"
	mb.box(Transform3D(Basis(), Vector3(W * 0.5, HGT * 0.5, 0)), Vector3(W - 0.04, HGT - 0.02, 0.06),
		"metal_dark" if metal else "wood", Color.WHITE, MeshBuilder.ALL, {"pz": tex, "nz": tex})
	_mesh = mb.commit(self, G.VIS_OUT if exterior else G.VIS_IN, false)
	_shape = CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = Vector3(W, HGT, 0.1)
	_shape.shape = bs
	_shape.position = Vector3(W * 0.5, HGT * 0.5, 0)
	add_child(_shape)


func _process(delta: float) -> void:
	if _shake > 0.0:
		_shake = maxf(0.0, _shake - delta * 4.0)
		_mesh.position.z = sin(_shake * 40.0) * 0.03 * _shake
	elif _mesh and _mesh.position.z != 0.0:
		_mesh.position.z = 0.0


func blocks() -> bool:
	return not broken and not is_open


func get_prompt(p: Player) -> String:
	if broken:
		return ""
	if planks > 0:
		return "[E] 拆下木板（已加固 %d/3）" % planks + ("　[B] 继续加固" if planks < 3 and p.can_barricade() else "")
	var s := "[E] 关门" if is_open else "[E] 开门"
	if not is_open and p.can_barricade():
		s += "　[B] 用木板加固"
	return s


func interact(p: Player) -> void:
	if broken:
		return
	if planks > 0:
		planks -= 1
		hp -= 80.0
		p.inv.add("plank", 1)
		Sfx.play_at("wood_break", centre + Vector3.UP, -8.0, 1.3)
		G.emit_noise(centre, 8.0, "door")
		_refresh_planks()
		return
	toggle(p.global_position)


func toggle(from: Vector3) -> void:
	is_open = not is_open
	var target := _base_yaw
	if is_open:
		var side := (from - centre).dot(normal)
		target = _base_yaw + (1.75 if side > 0.0 else -1.75)
	var tw := create_tween()
	tw.tween_property(self, "rotation:y", target, 0.22).set_trans(Tween.TRANS_SINE)
	Sfx.play_at("door_open" if is_open else "door_close", centre + Vector3.UP, -4.0, randf_range(0.9, 1.1))
	G.emit_noise(centre, 5.0 if is_open else 8.0, "door")


func barricade(p: Player) -> bool:
	if broken or is_open or planks >= 3:
		return false
	planks += 1
	hp += 80.0
	Sfx.play_at("hammer", centre + Vector3.UP, 0.0)
	G.emit_noise(centre, 16.0, "hammer")
	_refresh_planks()
	return true


func _refresh_planks() -> void:
	if _plank_mesh:
		_plank_mesh.queue_free()
		_plank_mesh = null
	if planks <= 0:
		return
	var mb := MeshBuilder.new()
	for i in planks:
		var y := 0.6 + i * 0.55
		var xf := Transform3D(Basis(Vector3.BACK, [0.08, -0.06, 0.04][i]), Vector3(W * 0.5, y, 0))
		mb.box(xf, Vector3(W + 0.5, 0.2, 0.14), "wood")
	_plank_mesh = mb.commit(self, G.VIS_IN | G.VIS_OUT, false)


## Zombies (and bullets) wear the door down.
func damage(amount: float) -> void:
	if broken or is_open:
		return
	hp -= amount
	_shake = 1.0
	if amount >= 8.0:
		Sfx.play_at("door_bang", centre + Vector3.UP, 0.0, randf_range(0.85, 1.1), 60.0)
		G.emit_noise(centre, 14.0, "door")
	if hp <= 0.0:
		_break()


func _break() -> void:
	broken = true
	planks = 0
	_refresh_planks()
	collision_layer = 0
	_shape.disabled = true
	_mesh.visible = false
	Sfx.play_at("wood_break", centre + Vector3.UP, 3.0, 1.0, 70.0)
	G.emit_noise(centre, 24.0, "break")
	if G.fx:
		G.fx.debris(centre + Vector3.UP, 10)

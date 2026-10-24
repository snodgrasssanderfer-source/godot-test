class_name FarmPlot
extends Node3D
## One 1x1 m farm tile. Reads its state from Game.plots[index].
## Interactables implement action_label(), action_enabled() and interact(main).

const CROP_SCENES := [
	preload("res://assets/models/crop_0.glb"), preload("res://assets/models/crop_1.glb"),
	preload("res://assets/models/crop_2.glb"), preload("res://assets/models/crop_3.glb"),
]

var index := 0
var _soil: MeshInstance3D
var _crop_root: Node3D
var _shown_stage := -2
var _materials := {}


func _init(plot_index: int) -> void:
	index = plot_index


func _ready() -> void:
	for tex in ["farm_grass", "soil_dry", "soil_wet"]:
		_materials[tex] = PixelTex.material(tex, Vector3.ONE, true)
	_soil = MeshInstance3D.new()
	var mesh := BoxMesh.new()
	mesh.size = Vector3(0.94, 0.04, 0.94)
	_soil.mesh = mesh
	_soil.position.y = 0.02
	add_child(_soil)
	_crop_root = Node3D.new()
	_crop_root.position.y = 0.04
	_crop_root.rotation.y = randf() * TAU
	add_child(_crop_root)
	Interact.add_hitbox(self, Vector3(1.0, 0.25, 1.0), Vector3(0, 0.12, 0))
	Game.plot_changed.connect(func(i): if i == index: refresh(true))
	refresh(false)


func _state() -> Dictionary:
	return Game.plots[index]


func action_label() -> String:
	var s := _state()
	if not s.tilled:
		return "锄地"
	if s.stage < 0:
		return "播种"
	if s.stage >= Game.MATURE_STAGE:
		return "收获"
	return "已浇水" if s.watered else "浇水"


func action_enabled() -> bool:
	var s := _state()
	return not (s.tilled and s.stage >= 0 and s.stage < Game.MATURE_STAGE and s.watered)


func interact(main: Node) -> void:
	var top := global_position + Vector3(0, 0.1, 0)
	match action_label():
		"锄地":
			Game.till(index)
			Sfx.play("hoe")
			Fx.burst(get_parent(), top, "dirt")
		"播种":
			Game.plant(index)
			Sfx.play("seed")
			Fx.burst(get_parent(), top, "seed")
		"浇水":
			Game.water(index)
			Sfx.play("water")
			Fx.burst(get_parent(), top, "water")
		"收获":
			Game.harvest(index)
			Sfx.play("harvest")
			Fx.burst(get_parent(), top + Vector3(0, 0.2, 0), "harvest")
			main.hud.toast("获得 %s ×1" % Game.CROP_NAME)
	main.player.nudge()


func refresh(animate: bool) -> void:
	var s := _state()
	var tex := "farm_grass"
	if s.tilled:
		tex = "soil_wet" if s.watered else "soil_dry"
	_soil.material_override = _materials[tex]
	if animate:
		_squash(_soil)
	if s.stage == _shown_stage:
		return
	_shown_stage = s.stage
	for c in _crop_root.get_children():
		c.queue_free()
	if s.stage >= 0:
		var crop: Node3D = CROP_SCENES[s.stage].instantiate()
		_crop_root.add_child(crop)
		if animate:
			crop.scale = Vector3.ONE * 0.3
			create_tween().tween_property(crop, "scale", Vector3.ONE, 0.35) \
				.set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)


func _squash(node: Node3D) -> void:
	var t := create_tween()
	node.scale = Vector3(1.08, 0.6, 1.08)
	t.tween_property(node, "scale", Vector3.ONE, 0.25).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)

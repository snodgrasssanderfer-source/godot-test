class_name StreetSet
extends Node3D
## The greybox street as a playable set: it owns the street lamps, builds the
## collision for the imported model and turns its zombie statues into targets.
## Both test scenes instance this.

const LAYER_WORLD := 1
## Street lamps: position, relative brightness (the second one is failing).
const LAMPS := [
	[Vector3(2.35, 7.0, -10.0), 1.0], [Vector3(-2.35, 7.0, -38.0), 0.12],
	[Vector3(2.35, 7.0, -66.0), 1.0], [Vector3(-2.35, 7.0, -94.0), 1.0],
]

## Leave the zombie statues as plain meshes (the atmosphere stills want them
## exactly where the concept art has them).
@export var make_targets := true

var targets: Array[TargetDummy] = []
var world_body: StaticBody3D


func _ready() -> void:
	_make_lamps()
	_make_collision()


func _make_lamps() -> void:
	for entry in LAMPS:
		var lamp := SpotLight3D.new()
		lamp.position = entry[0]
		lamp.rotation_degrees.x = -90.0
		lamp.spot_angle = 64.0
		lamp.spot_angle_attenuation = 0.6
		lamp.spot_range = 24.0
		lamp.light_color = Color(1.0, 0.50, 0.14)
		lamp.light_energy = 18.0 * entry[1]
		lamp.light_volumetric_fog_energy = 2.0
		lamp.shadow_enabled = true
		lamp.add_to_group(&"street_lamp")
		add_child(lamp)
		# The glowing lamp head: a child, so it goes dark with the light.
		var bulb := MeshInstance3D.new()
		var bulb_mesh := BoxMesh.new()
		bulb_mesh.size = Vector3(0.5, 0.18, 0.04)
		var bulb_mat := StandardMaterial3D.new()
		bulb_mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
		bulb_mat.albedo_color = Color(1.0, 0.55, 0.18) * (6.0 * entry[1] + 0.6)
		bulb_mesh.material = bulb_mat
		bulb.mesh = bulb_mesh
		bulb.position = Vector3(0.0, 0.0, 0.07)
		lamp.add_child(bulb)


## One static body for the whole street. Every greybox part is a convex solid
## (boxes, cylinders, prisms, lumps), so each gets a convex hull: solid shapes
## cannot be entered the way a hollow triangle shell can. Names come from the
## Blender script (glTF import turns "tree.001" into "tree_001").
func _make_collision() -> void:
	world_body = StaticBody3D.new()
	world_body.name = "World"
	world_body.collision_layer = LAYER_WORLD
	world_body.collision_mask = 0
	add_child(world_body)
	var to_body := world_body.global_transform.affine_inverse()
	var zombies: Array[MeshInstance3D] = []
	for node in find_children("*", "MeshInstance3D", true, false):
		var mi := node as MeshInstance3D
		var n := String(mi.name)
		if mi.mesh == null or n.begins_with("grass") or n.begins_with("wire"):
			continue
		if n.begins_with("zombie"):
			zombies.append(mi)
			continue
		if mi.get_parent() is Light3D:
			continue                                       # lamp bulbs
		var shape: Shape3D
		var xf := mi.global_transform
		if n.begins_with("tree"):
			var trunk := CylinderShape3D.new()             # the trunk only
			trunk.radius = 0.28
			trunk.height = 3.4
			shape = trunk
			xf = Transform3D(Basis.IDENTITY, xf.origin + Vector3.UP * 1.7)
		elif n.begins_with("lawn"):
			shape = mi.mesh.create_trimesh_shape()         # bumpy ground
		else:
			shape = mi.mesh.create_convex_shape(true, false)
		var cs := CollisionShape3D.new()
		cs.shape = shape
		world_body.add_child(cs)
		cs.transform = to_body * xf
	if make_targets:
		for mi in zombies:
			targets.append(TargetDummy.adopt(mi))

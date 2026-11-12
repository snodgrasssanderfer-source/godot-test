class_name TownModel
extends RefCounted
## Brings a town modelled in Blender into the game. The rules the model has to
## follow are in docs/blender_town_workflow.md; in short:
##
##   materials   a material named like a set in res://assets/materials (say
##               "red_brick") gets that set, on the model's own UVs. One named
##               like a greybox tag ("brick", "asphalt", ...) gets the set
##               TownBuilder.TEXTURED gives that tag, projected in world space,
##               so untouched greybox pieces keep working. Anything else keeps
##               the material it came with.
##   collision   every mesh is solid (exact triangles) unless its object name
##               ends in "-nocol"; "-colonly" makes an invisible collider.
##   markers     empties named "marker_<id>" say where things are.

const MATERIALS := "res://assets/materials/%s.tres"

var root: Node3D
var markers := {}              # StringName -> Vector3 (world)
var mesh_count := 0
var collider_count := 0
var materials := {}            # material name -> "set" / "tag" / "kept"


## Loads a .glb / .gltf straight from disk (no import step) and prepares it.
static func load_file(path: String, parent: Node3D) -> TownModel:
	var doc := GLTFDocument.new()
	var state := GLTFState.new()
	if doc.append_from_file(path, state) != OK:
		return null
	var scene := doc.generate_scene(state) as Node3D
	if scene == null:
		return null
	parent.add_child(scene)
	var model := TownModel.new()
	model.root = scene
	model._prepare(scene)
	return model


static func resolve(material_name: String) -> Array:
	var base := material_name.get_slice(".", 0)            # Blender's "brick.001"
	if ResourceLoader.exists(MATERIALS % base):
		return [load(MATERIALS % base), "set"]
	if TownBuilder.TEXTURED.has(StringName(base)):
		var entry: Array = TownBuilder.TEXTURED[StringName(base)]
		if ResourceLoader.exists(MATERIALS % entry[0]):
			var mat: BaseMaterial3D = load(MATERIALS % entry[0]).duplicate()
			mat.uv1_triplanar = true
			mat.uv1_world_triplanar = true
			mat.uv1_scale = Vector3.ONE / float(entry[1])
			mat.albedo_color = entry[2]
			return [mat, "tag"]
	return [null, "kept"]


func _prepare(node: Node) -> void:
	for child in node.get_children():
		_prepare(child)
	var node_name := String(node.name)
	if node is MeshInstance3D:
		var mi := node as MeshInstance3D
		mesh_count += 1
		for i in mi.mesh.get_surface_count():
			var mat := mi.mesh.surface_get_material(i)
			var mat_name := mat.resource_name if mat != null else ""
			if not materials.has(mat_name):
				materials[mat_name] = resolve(mat_name)
			var found: Array = materials[mat_name]
			if found[0] != null:
				mi.set_surface_override_material(i, found[0])
		if not node_name.ends_with("-nocol"):
			mi.create_trimesh_collision()
			collider_count += 1
		if node_name.ends_with("-colonly"):
			mi.visible = false
			for body in mi.get_children():
				if body is StaticBody3D:
					# the collider must outlive its hidden mesh: hang it on the parent
					var xf := (body as Node3D).global_transform
					mi.remove_child(body)
					mi.get_parent().add_child(body)
					(body as Node3D).global_transform = xf
	elif node is Node3D and node_name.begins_with("marker_"):
		markers[StringName(node_name.trim_prefix("marker_"))] = (node as Node3D).global_position


func summary() -> String:
	var kinds := {"set": 0, "tag": 0, "kept": 0}
	for key: String in materials:
		kinds[materials[key][1]] += 1
	return "%d meshes, %d colliders, %d markers; materials: %d by set name, %d by greybox tag, %d kept as they came" % [
			mesh_count, collider_count, markers.size(), kinds["set"], kinds["tag"], kinds["kept"]]

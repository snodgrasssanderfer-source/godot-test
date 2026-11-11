class_name Nav
extends RefCounted
## Navigation for whatever walks: bakes a navigation mesh from the static
## collision already in the scene (so greybox and real levels work the same
## way) and answers path queries.

const CELL_SIZE := 0.25
const CELL_HEIGHT := 0.1


## Bakes everything under `root` that is a static collider on `mask`, inside
## `bounds`, into a new NavigationRegion3D child of `root`. Takes a moment.
static func bake(root: Node3D, bounds: AABB, mask: int = 1, agent_radius: float = 0.5) -> NavigationRegion3D:
	var mesh := NavigationMesh.new()
	mesh.geometry_parsed_geometry_type = NavigationMesh.PARSED_GEOMETRY_STATIC_COLLIDERS
	mesh.geometry_collision_mask = mask
	mesh.cell_size = CELL_SIZE
	mesh.cell_height = CELL_HEIGHT
	mesh.agent_radius = agent_radius
	mesh.agent_height = 1.0
	mesh.agent_max_climb = 0.3          # curbs and door sills, not car bonnets
	mesh.agent_max_slope = 40.0
	mesh.filter_baking_aabb = bounds
	var map := root.get_world_3d().navigation_map
	NavigationServer3D.map_set_cell_size(map, CELL_SIZE)
	NavigationServer3D.map_set_cell_height(map, CELL_HEIGHT)
	var source := NavigationMeshSourceGeometryData3D.new()
	NavigationServer3D.parse_source_geometry_data(mesh, source, root)
	NavigationServer3D.bake_from_source_geometry_data(mesh, source)
	var region := NavigationRegion3D.new()
	region.name = "Navigation"
	region.navigation_mesh = mesh
	root.add_child(region)
	return region


## Corner points of a walkable route, or empty if there is none (no mesh yet,
## or the goal is cut off). Callers fall back to walking straight at the goal.
static func path(world: World3D, from: Vector3, to: Vector3) -> PackedVector3Array:
	var map := world.navigation_map
	if NavigationServer3D.map_get_iteration_id(map) == 0:
		return PackedVector3Array()
	return NavigationServer3D.map_get_path(map, from, to, true)

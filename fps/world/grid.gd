extends Node
## Autoload "Grid": owns the running GridSim for the current scene, feeds it
## from the NoiseBus autoload and steps it with the physics clock. Scenes put GridNode
## markers down; those register here.

signal level_changed(node: int, level: int)
signal woke(node: int, entry: int, kind: StringName)
signal slept(node: int, entry: int, kind: StringName)
signal tripped(node: int)
signal restored(node: int)

var sim: GridSim
var nodes: Array[GridNode] = []      # index = cell id in the sim


func _ready() -> void:
	reset()
	NoiseBus.heard.connect(_on_noise)


## Start an empty grid (call when a new map loads, before its GridNodes register).
func reset() -> void:
	sim = GridSim.new()
	nodes.clear()
	sim.level_changed.connect(func(n: int, l: int) -> void: level_changed.emit(n, l))
	sim.woke.connect(func(n: int, e: int, k: StringName) -> void: woke.emit(n, e, k))
	sim.slept.connect(func(n: int, e: int, k: StringName) -> void: slept.emit(n, e, k))
	sim.tripped.connect(func(n: int) -> void: tripped.emit(n))
	sim.restored.connect(func(n: int) -> void: restored.emit(n))


func register(node: GridNode) -> int:
	var id := sim.add_node(node.global_position)
	nodes.append(node)
	return id


func _physics_process(delta: float) -> void:
	sim.advance(delta)


func _on_noise(position: Vector3, radius: float, energy: float, source: StringName, occlusion: float) -> void:
	if energy <= 0.0 or sim.cells.is_empty():
		return
	if source == &"taser":
		sim.inject_node(sim.nearest(position), energy)
	else:
		sim.inject(position, radius, energy, occlusion)


## Charge of the cell closest to a point (what the dead radio on the belt crackles to).
func charge_near(position: Vector3) -> float:
	var n := sim.nearest(position)
	return sim.charge(n) if n >= 0 else 0.0

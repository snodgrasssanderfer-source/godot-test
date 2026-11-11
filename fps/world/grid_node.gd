class_name GridNode
extends Node3D
## One cell of the power grid placed in a scene: a pole, or a building's
## circuit. Registers with the Grid autoload and shows its state on a lamp:
## flicker from level 1, over-bright at overload, dark while tripped.

@export var links: Array[NodePath] = []
## How many sleeping creatures hang on this cell, by kind.
@export var roster := {&"small": 3, &"medium": 2, &"large": 1}
## Optional light that shows the state of the cell.
@export var lamp: Light3D

var id := -1
var _flicker := 1.0
var _next := 0.0
var _t := 0.0


func _ready() -> void:
	id = Grid.register(self)
	for kind: StringName in roster:
		Grid.sim.add_roster(id, kind, int(roster[kind]))
	_link.call_deferred()


func _link() -> void:
	for path in links:
		var other := get_node_or_null(path) as GridNode
		if other != null and other.id >= 0:
			Grid.sim.link(id, other.id)


func link_to(other: GridNode) -> void:
	Grid.sim.link(id, other.id)


func charge() -> float:
	return Grid.sim.charge(id)


func _process(delta: float) -> void:
	if lamp == null or id < 0:
		return
	# The atmosphere sets the lamp for the time of day and remembers that value.
	if not lamp.has_meta(&"powered_energy"):
		return
	var base: float = lamp.get_meta(&"powered_energy")
	var c := Grid.sim.charge(id)
	var scale := 1.0
	if Grid.sim.is_tripped(id):
		scale = 0.0
	elif c >= GridSim.LEVELS[0]:
		_t += delta
		if _t >= _next:
			# dips come more often and deeper as the charge climbs
			var k := clampf((c - GridSim.LEVELS[0]) / 80.0, 0.0, 1.0)
			_flicker = randf_range(0.15, 0.6) if randf() < 0.25 + k * 0.4 else 1.0
			_next = _t + randf_range(0.03, lerpf(0.9, 0.12, k))
		scale = _flicker * (1.6 if c >= GridSim.LEVELS[4] else 1.0)
	lamp.light_energy = base * scale
	lamp.visible = lamp.light_energy > 0.001

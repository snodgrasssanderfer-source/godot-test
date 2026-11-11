class_name GridSim
extends RefCounted
## The street's power grid as a disturbance model (design supplement, section 4).
## Pure logic, no nodes: every pole and every building circuit is a cell with a
## charge of 0..100. Noise adds charge, charge leaks along the wires to the
## neighbours and drains away when things go quiet. Sleeping appliances hang
## on a cell and wake when its charge passes their threshold; the roster is
## fixed, so more gunfire brings them sooner, never more of them.
##
## Time advances in fixed steps, so the same inputs always give the same result.

signal level_changed(node: int, level: int)
signal woke(node: int, entry: int, kind: StringName)
signal slept(node: int, entry: int, kind: StringName)
signal tripped(node: int)
signal restored(node: int)

const STEP := 0.1
## What the street shows at each level: lamps flicker / first TV lights up /
## small ones wake / whole house lit, medium ones wake / overload, large ones wake.
const LEVELS: Array[float] = [20.0, 35.0, 50.0, 70.0, 90.0]
const CONDUCT := 0.08          # share of the difference passed to a neighbour per second
const DECAY_DELAY := 6.0       # quiet seconds before a cell starts to drain
const DECAY_RATE := 1.2        # charge per second
const KILL_RELIEF := 8.0       # a dead creature shorts its cell
const TRIP_HOLD := 5.0         # seconds at 100 before the breaker trips
const TRIP_TIME := 45.0        # seconds a tripped cell stays dead
const TRIP_RESTART := 30.0     # charge it comes back with
const BREAKER_CAP := 40.0      # ceiling of a cell whose breaker box was pulled
const SLEEP_MARGIN := 20.0     # a creature dozes off this far below its threshold...
const SLEEP_DELAY := 10.0      # ...after this many seconds
## Wake thresholds per kind. Several of a kind on one cell take these in turn,
## so they come one after another rather than all at once.
const THRESHOLDS := {
	&"small": [50.0, 55.0, 62.0],
	&"medium": [70.0, 80.0],
	&"large": [90.0],
	&"lamp": [90.0],
}


class Cell:
	var position := Vector3.ZERO
	var charge := 0.0
	var links: Array[int] = []
	var roster: Array[Dictionary] = []     # {kind, threshold, awake, dead, engaged, below}
	var idle := 99.0
	var full := 0.0
	var trip := 0.0
	var breaker_off := false
	var level := 0


var cells: Array[Cell] = []
var _acc := 0.0


# --- building the grid ---------------------------------------------------------
func add_node(position: Vector3) -> int:
	var c := Cell.new()
	c.position = position
	cells.append(c)
	return cells.size() - 1


func link(a: int, b: int) -> void:
	if a == b or cells[a].links.has(b):
		return
	cells[a].links.append(b)
	cells[b].links.append(a)


## Hangs `count` sleeping creatures of a kind on a cell; returns their entry indices.
func add_roster(node: int, kind: StringName, count: int = 1) -> Array[int]:
	var out: Array[int] = []
	var th: Array = THRESHOLDS.get(kind, [50.0])
	var same := 0
	for e in cells[node].roster:
		if e["kind"] == kind:
			same += 1
	for i in count:
		cells[node].roster.append({"kind": kind, "threshold": float(th[(same + i) % th.size()]),
			"awake": false, "dead": false, "engaged": false, "below": 0.0})
		out.append(cells[node].roster.size() - 1)
	return out


# --- input -------------------------------------------------------------------------
## A noise of `energy` at `position`, heard out to `radius`.
func inject(position: Vector3, radius: float, energy: float, occlusion: float = 1.0) -> void:
	if radius <= 0.0 or energy <= 0.0:
		return
	for i in cells.size():
		var d := cells[i].position.distance_to(position)
		if d < radius:
			var f := 1.0 - d / radius
			inject_node(i, energy * f * f * occlusion)


## Charge straight into one cell (a stun gun is electricity already).
func inject_node(node: int, amount: float) -> void:
	var c := cells[node]
	if c.trip > 0.0 or amount <= 0.0:
		return
	c.charge = minf(c.charge + amount, _cap(c))
	c.idle = 0.0


func nearest(position: Vector3) -> int:
	var best := -1
	var bd := INF
	for i in cells.size():
		var d := cells[i].position.distance_squared_to(position)
		if d < bd:
			bd = d
			best = i
	return best


func kill(node: int, entry: int) -> void:
	var e: Dictionary = cells[node].roster[entry]
	if e["dead"]:
		return
	e["dead"] = true
	e["awake"] = false
	cells[node].charge = maxf(0.0, cells[node].charge - KILL_RELIEF)


## A creature that has seen the player does not go back to sleep.
func set_engaged(node: int, entry: int, on: bool) -> void:
	cells[node].roster[entry]["engaged"] = on


func set_breaker(node: int, off: bool) -> void:
	cells[node].breaker_off = off
	cells[node].charge = minf(cells[node].charge, _cap(cells[node]))


## Scripted jolts (the garage door, the engine): every live cell to at least this.
func raise_all(minimum: float) -> void:
	for c in cells:
		if c.trip <= 0.0 and c.charge < minimum:
			c.charge = minf(minimum, _cap(c))
			c.idle = 0.0


# --- queries -----------------------------------------------------------------------
func charge(node: int) -> float:
	return cells[node].charge


func level(node: int) -> int:
	return cells[node].level


func is_tripped(node: int) -> bool:
	return cells[node].trip > 0.0


func count(awake_only: bool = false) -> int:
	var n := 0
	for c in cells:
		for e in c.roster:
			if not e["dead"] and (e["awake"] or not awake_only):
				n += 1
	return n


func _cap(c: Cell) -> float:
	return BREAKER_CAP if c.breaker_off else 100.0


# --- time ------------------------------------------------------------------------------
func advance(delta: float) -> void:
	_acc += delta
	while _acc >= STEP - 0.00001:
		_acc -= STEP
		_step(STEP)


func _step(dt: float) -> void:
	# conduction along the wires, from a snapshot so the order of cells does not matter
	var flow := PackedFloat32Array()
	flow.resize(cells.size())
	for i in cells.size():
		var a := cells[i]
		if a.trip > 0.0:
			continue
		for j in a.links:
			var b := cells[j]
			if b.trip > 0.0 or a.charge <= b.charge:
				continue
			var f := (a.charge - b.charge) * CONDUCT * dt
			flow[i] -= f
			flow[j] += f
	for i in cells.size():
		var c := cells[i]
		if c.trip > 0.0:
			c.trip -= dt
			if c.trip <= 0.0:
				c.trip = 0.0
				c.charge = minf(TRIP_RESTART, _cap(c))
				c.idle = 0.0
				restored.emit(i)
				_set_level(i, c)
			continue
		c.charge = clampf(c.charge + flow[i], 0.0, _cap(c))
		c.idle += dt
		if c.idle >= DECAY_DELAY:
			c.charge = maxf(0.0, c.charge - DECAY_RATE * dt)
		if c.charge >= 99.999:
			c.full += dt
			if c.full >= TRIP_HOLD - 0.00001:
				c.full = 0.0
				c.charge = 0.0
				c.trip = TRIP_TIME
				tripped.emit(i)
				_set_level(i, c)
				continue
		else:
			c.full = 0.0
		for k in c.roster.size():
			var e: Dictionary = c.roster[k]
			if e["dead"]:
				continue
			if not e["awake"]:
				if c.charge >= e["threshold"]:
					e["awake"] = true
					e["below"] = 0.0
					woke.emit(i, k, e["kind"])
			elif not e["engaged"] and c.charge < e["threshold"] - SLEEP_MARGIN:
				e["below"] += dt
				if e["below"] >= SLEEP_DELAY - 0.00001:
					e["awake"] = false
					slept.emit(i, k, e["kind"])
			else:
				e["below"] = 0.0
		_set_level(i, c)


func _set_level(i: int, c: Cell) -> void:
	var lv := 0
	for t in LEVELS:
		if c.charge >= t:
			lv += 1
	if lv != c.level:
		c.level = lv
		level_changed.emit(i, lv)

class_name Inventory
extends RefCounted
## Weight-limited stack inventory. Weapons are single entries that remember
## the rounds left in their magazine ("mag").

signal changed

const CAPACITY := 20.0

var items: Array = []   # [{id, n, mag?}]


func add(id: String, n: int = 1, mag: int = -1) -> void:
	if Items.DB[id]["t"] == "weapon":
		for i in n:
			var e := {"id": id, "n": 1}
			if Items.WEAPONS[id].has("mag"):
				e["mag"] = mag if mag >= 0 else 0
			items.append(e)
	else:
		var found := false
		for e in items:
			if e["id"] == id:
				e["n"] += n
				found = true
				break
		if not found:
			items.append({"id": id, "n": n})
	changed.emit()


func add_entry(e: Dictionary) -> void:
	add(e["id"], e["n"], e.get("mag", -1))


func count(id: String) -> int:
	var c := 0
	for e in items:
		if e["id"] == id:
			c += e["n"]
	return c


func has(id: String) -> bool:
	return count(id) > 0


## Removes up to n, returns how many were actually removed.
func remove(id: String, n: int = 1) -> int:
	var left := n
	for i in range(items.size() - 1, -1, -1):
		var e: Dictionary = items[i]
		if e["id"] != id:
			continue
		var take: int = mini(left, e["n"])
		e["n"] -= take
		left -= take
		if e["n"] <= 0:
			items.remove_at(i)
		if left <= 0:
			break
	if left != n:
		changed.emit()
	return n - left


func remove_entry(e: Dictionary) -> void:
	items.erase(e)
	changed.emit()


func weight() -> float:
	var w := 0.0
	for e in items:
		w += Items.weight(e["id"]) * e["n"]
	return w

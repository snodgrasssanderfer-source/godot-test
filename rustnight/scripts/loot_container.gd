class_name LootContainer
extends StaticBody3D
## Any searchable furniture / wreck. Loot is rolled the first time it is opened.

var kind := "crate"
var title := ""
var items = null            # Array of {id, n, mag?} once rolled
var forced: Array = []      # guaranteed items (quest parts etc.)
var searched := false
var locked_key := ""
var solid := true


func setup(p_kind: String, size: Vector3, xf: Transform3D, p_solid: bool = true) -> void:
	kind = p_kind
	title = Items.CONTAINER_NAMES.get(kind, "容器")
	solid = p_solid
	transform = xf
	collision_layer = (G.L_WORLD if solid else 0) | G.L_INTERACT
	collision_mask = 0
	var cs := CollisionShape3D.new()
	var bs := BoxShape3D.new()
	bs.size = size
	cs.shape = bs
	cs.position = Vector3(0, size.y * 0.5, 0)
	add_child(cs)


func ensure_items() -> Array:
	if items == null:
		items = Items.roll(kind, G.rng)
		for f in forced:
			items.append(f)
		for e in items:
			if Items.DB[e["id"]]["t"] == "weapon" and Items.WEAPONS[e["id"]].has("mag"):
				e["mag"] = G.rng.randi_range(0, Items.WEAPONS[e["id"]]["mag"])
	return items


func get_prompt(_p: Player) -> String:
	if locked_key != "":
		return "[E] %s（上锁）" % title
	if searched and items != null and items.is_empty():
		return "%s（空）" % title
	return "[E] 搜索 %s" % title


func interact(p: Player) -> void:
	if locked_key != "":
		if p.inv.has(locked_key):
			G.msg("用%s打开了%s。" % [Items.item_name(locked_key), title])
			Sfx.play("mag_in", -6.0, 0.8)
			locked_key = ""
		else:
			G.msg("%s锁着。需要%s。" % [title, Items.item_name(locked_key)], Color(0.8, 0.5, 0.4))
			Sfx.play("deny")
			return
	G.hud.open_container(self)

class_name HUD
extends CanvasLayer
## All 2D UI: crosshair / hitmarker / bars (custom drawn), status tags,
## messages, interaction prompt, inventory + container panel, menus.

const C_TEXT := Color(0.85, 0.83, 0.75)
const C_DIM := Color(0.55, 0.54, 0.5)
const C_RED := Color(0.78, 0.18, 0.12)

class Overlay extends Control:
	var hud: HUD

	func _draw() -> void:
		hud.draw_overlay(self)


var root: Control
var overlay: Overlay
var ammo_lbl: Label
var weapon_lbl: Label
var clock_lbl: Label
var goal_lbl: Label
var prompt_lbl: Label
var moodles: VBoxContainer
var msgs: VBoxContainer
var fade: ColorRect
var inv_panel: Panel
var left_list: ItemList
var right_list: ItemList
var left_title: Label
var right_title: Label
var desc_lbl: Label
var btn_row: HBoxContainer
var screen: Control           # menu / pause / end screen
var touch: Control
var _left: Array = []
var _right: Array = []
var _container: LootContainer = null
var _search_t := 0.0
var _hit_t := 0.0
var _hit_head := false
var _hit_kill := false
var _dmg: Array = []          # [angle_world_pos, life]
var _fade_tw: Tween
var _moodle_t := 0.0
var paused := false


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	root = Control.new()
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	add_child(root)
	overlay = Overlay.new()
	overlay.hud = self
	overlay.set_anchors_preset(Control.PRESET_FULL_RECT)
	overlay.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(overlay)

	ammo_lbl = _label(48, C_TEXT, Control.PRESET_BOTTOM_RIGHT, Vector2(-330, -110), Vector2(300, 56), HORIZONTAL_ALIGNMENT_RIGHT)
	weapon_lbl = _label(24, C_DIM, Control.PRESET_BOTTOM_RIGHT, Vector2(-430, -56), Vector2(400, 30), HORIZONTAL_ALIGNMENT_RIGHT)
	clock_lbl = _label(24, C_TEXT, Control.PRESET_TOP_RIGHT, Vector2(-430, 16), Vector2(400, 30), HORIZONTAL_ALIGNMENT_RIGHT)
	goal_lbl = _label(24, C_DIM, Control.PRESET_TOP_RIGHT, Vector2(-630, 46), Vector2(600, 30), HORIZONTAL_ALIGNMENT_RIGHT)
	prompt_lbl = _label(24, C_TEXT, Control.PRESET_CENTER_BOTTOM, Vector2(-500, -210), Vector2(1000, 30), HORIZONTAL_ALIGNMENT_CENTER)
	moodles = VBoxContainer.new()
	moodles.set_anchors_preset(Control.PRESET_BOTTOM_LEFT)
	moodles.position = Vector2(30, -400)
	moodles.size = Vector2(260, 300)
	moodles.alignment = BoxContainer.ALIGNMENT_END
	moodles.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_anchor(moodles, Control.PRESET_BOTTOM_LEFT, Vector2(30, -400), Vector2(260, 300))
	root.add_child(moodles)
	msgs = VBoxContainer.new()
	msgs.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_anchor(msgs, Control.PRESET_TOP_LEFT, Vector2(30, 70), Vector2(800, 260))
	root.add_child(msgs)

	_build_inventory()
	touch = TouchControls.new()
	touch.set_anchors_preset(Control.PRESET_FULL_RECT)
	touch.visible = false
	root.add_child(touch)
	fade = ColorRect.new()
	fade.color = Color(0, 0, 0, 0)
	fade.set_anchors_preset(Control.PRESET_FULL_RECT)
	fade.mouse_filter = Control.MOUSE_FILTER_IGNORE
	root.add_child(fade)
	screen = Control.new()
	screen.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.add_child(screen)
	set_ingame(false)


func _anchor(c: Control, preset: int, pos: Vector2, size: Vector2) -> void:
	c.set_anchors_preset(preset)
	c.offset_left = pos.x
	c.offset_top = pos.y
	c.offset_right = pos.x + size.x
	c.offset_bottom = pos.y + size.y


func _label(size: int, color: Color, preset: int, pos: Vector2, lsize: Vector2, align: int, parent: Control = null) -> Label:
	var l := Label.new()
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	l.add_theme_color_override("font_shadow_color", Color(0, 0, 0, 0.9))
	l.add_theme_constant_override("shadow_offset_x", 2)
	l.add_theme_constant_override("shadow_offset_y", 2)
	l.horizontal_alignment = align
	l.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_anchor(l, preset, pos, lsize)
	(parent if parent else root).add_child(l)
	return l


func _button(text: String, cb: Callable, size: int = 24) -> Button:
	var b := Button.new()
	b.text = text
	b.add_theme_font_size_override("font_size", size)
	b.focus_mode = Control.FOCUS_NONE
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.1, 0.1, 0.1, 0.9)
	sb.border_color = Color(0.4, 0.38, 0.33)
	sb.set_border_width_all(2)
	sb.set_content_margin_all(10)
	var sh := sb.duplicate()
	sh.bg_color = Color(0.3, 0.1, 0.08, 0.95)
	b.add_theme_stylebox_override("normal", sb)
	b.add_theme_stylebox_override("hover", sh)
	b.add_theme_stylebox_override("pressed", sh)
	b.add_theme_color_override("font_color", C_TEXT)
	b.pressed.connect(func():
		Sfx.play("click")
		cb.call())
	return b


func set_ingame(on: bool) -> void:
	for c in [overlay, ammo_lbl, weapon_lbl, clock_lbl, goal_lbl, prompt_lbl, moodles, msgs]:
		c.visible = on
	touch.visible = on and G.is_touch
	if not on:
		inv_panel.visible = false


# --- per-frame ---------------------------------------------------------------------------
func _process(delta: float) -> void:
	_hit_t = maxf(0.0, _hit_t - delta)
	for d in _dmg:
		d[1] -= delta
	_dmg = _dmg.filter(func(d): return d[1] > 0.0)
	for m in msgs.get_children():
		var life: float = m.get_meta("life") - delta
		m.set_meta("life", life)
		m.modulate.a = clampf(life, 0.0, 1.0)
		if life <= 0.0:
			m.queue_free()
	if not G.playing or G.player == null:
		return
	var p := G.player
	overlay.queue_redraw()
	var w := p.weapons
	if w.has_gun():
		ammo_lbl.text = "%d / %d" % [w.mag(), w.reserve()]
		ammo_lbl.add_theme_color_override("font_color", C_RED if w.mag() <= int(w.def["mag"]) / 4 else C_TEXT)
	else:
		ammo_lbl.text = ""
	var names: Array = []
	for i in 3:
		if w.slots[i] != null:
			var nm: String = Items.item_name(w.slots[i]["id"])
			names.append(("▶" + nm) if i == w.cur else nm)
	weapon_lbl.text = "   ".join(names)
	clock_lbl.text = "第 %d 天  %s   手电 %d%%" % [G.day(), G.clock_text(), int(p.battery)]
	var car := G.town.escape_car
	if car:
		var miss: Array = car.missing()
		if car.starting:
			goal_lbl.text = "守住！引擎预热 %d 秒" % int(ceil(car.countdown))
		elif miss.is_empty():
			goal_lbl.text = "目标：回汽修厂发动汽车"
		else:
			var parts: Array = []
			for id in miss:
				parts.append(Items.item_name(id) + ("(已有)" if p.inv.has(id) else ""))
			goal_lbl.text = "目标：修好汽修厂的车 — 缺 " + "、".join(parts)
	_moodle_t -= delta
	if _moodle_t <= 0.0:
		_moodle_t = 0.5
		for c in moodles.get_children():
			c.queue_free()
		for m in p.surv.moodles():
			var l := Label.new()
			l.text = m[0]
			l.add_theme_font_size_override("font_size", 24)
			l.add_theme_color_override("font_color", [Color(0.85, 0.75, 0.4), Color(0.9, 0.5, 0.25), C_RED][int(m[1]) - 1])
			l.add_theme_color_override("font_shadow_color", Color(0, 0, 0, 0.9))
			moodles.add_child(l)
	if inv_panel.visible:
		if _container != null:
			if not is_instance_valid(_container) or _container.global_position.distance_to(p.global_position) > 3.5:
				close_inventory()
			elif _search_t > 0.0:
				_search_t -= delta
				if _search_t <= 0.0:
					_container.searched = true
					_refresh_inventory()


func draw_overlay(c: Control) -> void:
	if not G.playing or G.player == null:
		return
	var p := G.player
	var size := c.size
	var mid := size * 0.5
	var w := p.weapons
	# crosshair: gap follows the real spread cone
	if not G.ui_open and w.cur >= 0:
		var a := 1.0 - clampf(w.ads * 2.0, 0.0, 1.0)
		a *= 1.0 - w.sprint_b * 0.8
		if w.has_gun() and a > 0.02:
			var gap := tan(deg_to_rad(w.spread())) / tan(deg_to_rad(p.cam.fov * 0.5)) * size.y * 0.5 + 4.0
			var col := Color(0.9, 0.9, 0.85, 0.85 * a)
			for d in [Vector2.RIGHT, Vector2.LEFT, Vector2.UP, Vector2.DOWN]:
				c.draw_line(mid + d * gap, mid + d * (gap + 12.0), Color(0, 0, 0, 0.6 * a), 5.0)
				c.draw_line(mid + d * gap, mid + d * (gap + 12.0), col, 2.0)
		elif w.is_melee():
			c.draw_circle(mid, 3.0, Color(0.9, 0.9, 0.85, 0.7))
	if _hit_t > 0.0:
		var ha := clampf(_hit_t / 0.12, 0.0, 1.0)
		var hc := (Color(0.9, 0.15, 0.1) if _hit_kill else Color(1, 1, 1))
		hc.a = ha
		var r0 := 10.0 + (1.0 - ha) * 4.0
		var r1 := r0 + (16.0 if _hit_head else 11.0)
		for d in [Vector2(1, 1), Vector2(-1, 1), Vector2(1, -1), Vector2(-1, -1)]:
			c.draw_line(mid + d.normalized() * r0, mid + d.normalized() * r1, hc, 4.0 if _hit_head else 3.0)
	# damage direction
	for d in _dmg:
		var to: Vector3 = (d[0] as Vector3) - p.global_position
		var local := p.global_transform.basis.inverse() * to
		var ang := atan2(local.x, -local.z)
		var col2 := Color(0.8, 0.1, 0.05, clampf(d[1], 0.0, 1.0) * 0.8)
		c.draw_arc(mid, 150.0, ang - PI * 0.5 - 0.35, ang - PI * 0.5 + 0.35, 12, col2, 10.0)
	# health + endurance
	var bx := 30.0
	var by := size.y - 70.0
	c.draw_rect(Rect2(bx - 2, by - 2, 264, 24), Color(0, 0, 0, 0.6))
	var hp := clampf(p.surv.health / 100.0, 0.0, 1.0)
	c.draw_rect(Rect2(bx, by, 260.0 * hp, 20), Color(0.55, 0.12, 0.1) if hp > 0.3 else Color(0.85, 0.1, 0.05))
	for i in range(1, 10):
		c.draw_rect(Rect2(bx + i * 26.0 - 1, by, 2, 20), Color(0, 0, 0, 0.5))
	c.draw_rect(Rect2(bx - 2, by + 26, 264, 10), Color(0, 0, 0, 0.6))
	c.draw_rect(Rect2(bx, by + 28, 260.0 * p.surv.endurance / 100.0, 6), Color(0.75, 0.72, 0.5))


# --- small API used by gameplay code ---------------------------------------------------------
func push_message(text: String, color: Color) -> void:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", 24)
	l.add_theme_color_override("font_color", color)
	l.add_theme_color_override("font_shadow_color", Color(0, 0, 0, 0.9))
	l.add_theme_constant_override("shadow_offset_x", 2)
	l.add_theme_constant_override("shadow_offset_y", 2)
	l.set_meta("life", 7.0)
	msgs.add_child(l)
	if msgs.get_child_count() > 6:
		msgs.get_child(0).queue_free()


func set_prompt(text: String) -> void:
	if prompt_lbl.text != text:
		prompt_lbl.text = text


func hitmarker(head: bool, killed: bool) -> void:
	_hit_t = 0.22
	_hit_head = head
	_hit_kill = killed
	Sfx.play("kill" if killed else ("headshot" if head else "hitmarker"), -4.0)


func damage_from(pos: Vector3) -> void:
	_dmg.append([pos, 1.4])


func fade_to(v: float, dur: float) -> void:
	if _fade_tw:
		_fade_tw.kill()
	_fade_tw = create_tween()
	_fade_tw.tween_property(fade, "color:a", v, dur)


# --- inventory ---------------------------------------------------------------------------------
func _build_inventory() -> void:
	inv_panel = Panel.new()
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.04, 0.045, 0.045, 0.93)
	sb.border_color = Color(0.35, 0.33, 0.28)
	sb.set_border_width_all(2)
	inv_panel.add_theme_stylebox_override("panel", sb)
	_anchor(inv_panel, Control.PRESET_CENTER, Vector2(-520, -300), Vector2(1040, 600))
	inv_panel.visible = false
	root.add_child(inv_panel)
	left_title = _label(24, C_TEXT, Control.PRESET_TOP_LEFT, Vector2(20, 12), Vector2(480, 30), HORIZONTAL_ALIGNMENT_LEFT, inv_panel)
	right_title = _label(24, C_TEXT, Control.PRESET_TOP_LEFT, Vector2(540, 12), Vector2(480, 30), HORIZONTAL_ALIGNMENT_LEFT, inv_panel)
	left_list = _item_list(Vector2(20, 48))
	right_list = _item_list(Vector2(540, 48))
	left_list.item_selected.connect(func(_i):
		right_list.deselect_all()
		_refresh_actions())
	right_list.item_selected.connect(func(_i):
		left_list.deselect_all()
		_refresh_actions())
	left_list.item_activated.connect(func(i): _primary_left(i))
	right_list.item_activated.connect(func(i): _take(i))
	desc_lbl = _label(24, C_DIM, Control.PRESET_TOP_LEFT, Vector2(20, 456), Vector2(1000, 60), HORIZONTAL_ALIGNMENT_LEFT, inv_panel)
	desc_lbl.autowrap_mode = TextServer.AUTOWRAP_ARBITRARY
	btn_row = HBoxContainer.new()
	btn_row.position = Vector2(20, 528)
	btn_row.size = Vector2(1000, 56)
	btn_row.add_theme_constant_override("separation", 12)
	inv_panel.add_child(btn_row)


func _item_list(pos: Vector2) -> ItemList:
	var il := ItemList.new()
	il.position = pos
	il.size = Vector2(480, 400)
	il.add_theme_font_size_override("font_size", 24)
	il.add_theme_color_override("font_color", C_TEXT)
	il.focus_mode = Control.FOCUS_NONE
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.08, 0.08, 0.08, 0.9)
	il.add_theme_stylebox_override("panel", sb)
	inv_panel.add_child(il)
	return il


func toggle_inventory() -> void:
	if inv_panel.visible:
		close_inventory()
	else:
		_container = null
		_open_panel()


func open_container(c: LootContainer) -> void:
	_container = c
	_search_t = 0.0 if c.searched else 0.7
	if not c.searched:
		Sfx.play("search", -4.0)
		G.emit_noise(c.global_position, 4.0, "search")
	c.ensure_items()
	_open_panel()


func _open_panel() -> void:
	inv_panel.visible = true
	G.ui_open = true
	G.capture_mouse(false)
	_refresh_inventory()


func close_inventory() -> void:
	inv_panel.visible = false
	_container = null
	if not paused:
		G.ui_open = false
		G.capture_mouse(true)


func _entry_text(e: Dictionary) -> String:
	var s: String = Items.item_name(e["id"])
	if e.has("mag"):
		s += "  [%d发]" % e["mag"]
	elif e["n"] > 1:
		s += "  ×%d" % e["n"]
	return s


func _refresh_inventory() -> void:
	var p := G.player
	left_list.clear()
	right_list.clear()
	_left.clear()
	_right.clear()
	for i in 3:
		var s = p.weapons.slots[i]
		if s != null:
			left_list.add_item("[%s] %s" % [["主武器", "副武器", "近战"][i], _entry_text({"id": s["id"], "n": 1, "mag": s["mag"]} if Items.WEAPONS[s["id"]].has("mag") else {"id": s["id"], "n": 1})])
			_left.append({"slot": i})
	for e in p.inv.items:
		left_list.add_item(_entry_text(e))
		_left.append({"entry": e})
	left_title.text = "背包  %.1f / %d kg" % [p.total_weight(), int(Inventory.CAPACITY)]
	left_title.add_theme_color_override("font_color", C_RED if p.over_weight() > 1.0 else C_TEXT)
	if _container != null:
		right_title.text = _container.title
		if _search_t > 0.0:
			right_list.add_item("搜索中……")
			right_list.set_item_disabled(0, true)
		else:
			for e in _container.items:
				right_list.add_item(_entry_text(e))
				_right.append(e)
			if _right.is_empty():
				right_list.add_item("（空）")
				right_list.set_item_disabled(0, true)
	else:
		right_title.text = "Tab 关闭 · 双击 使用/装备"
	_refresh_actions()


func _refresh_actions() -> void:
	for c in btn_row.get_children():
		c.queue_free()
	desc_lbl.text = ""
	var ls := left_list.get_selected_items()
	var rs := right_list.get_selected_items()
	if ls.size() > 0 and ls[0] < _left.size():
		var it: Dictionary = _left[ls[0]]
		if it.has("slot"):
			var sid: String = G.player.weapons.slots[it["slot"]]["id"]
			desc_lbl.text = Items.DB[sid].get("d", "")
			btn_row.add_child(_button("卸下", func():
				G.player.weapons.unequip(it["slot"])
				_refresh_inventory()))
		else:
			var e: Dictionary = it["entry"]
			var db: Dictionary = Items.DB[e["id"]]
			desc_lbl.text = "%s　%.2f kg" % [db.get("d", ""), Items.weight(e["id"]) * e["n"]]
			if db["t"] == "weapon":
				btn_row.add_child(_button("装备", func(): _primary_left(ls[0])))
			elif db["t"] in ["food", "drink", "med"] or e["id"] == "battery":
				btn_row.add_child(_button("使用", func(): _primary_left(ls[0])))
			if _container != null and _search_t <= 0.0:
				btn_row.add_child(_button("存入", func(): _store(e)))
			btn_row.add_child(_button("丢弃", func(): _drop(e)))
	elif rs.size() > 0 and rs[0] < _right.size():
		var re: Dictionary = _right[rs[0]]
		desc_lbl.text = Items.DB[re["id"]].get("d", "")
		btn_row.add_child(_button("拿取", func(): _take(rs[0])))
	if _container != null and not _right.is_empty():
		btn_row.add_child(_button("全部拿取", func(): _take_all()))
	btn_row.add_child(_button("关闭", func(): close_inventory()))


func _primary_left(i: int) -> void:
	if i >= _left.size() or not _left[i].has("entry"):
		return
	var e: Dictionary = _left[i]["entry"]
	var p := G.player
	var db: Dictionary = Items.DB[e["id"]]
	if db["t"] == "weapon":
		p.inv.remove_entry(e)
		p.weapons.give(e["id"], e.get("mag", -1))
		Sfx.play("equip", -4.0)
	elif p.surv.use(e["id"]):
		p.inv.remove(e["id"], 1)
	_refresh_inventory()


func _take(i: int) -> void:
	if _container == null or i >= _right.size():
		return
	var e: Dictionary = _right[i]
	_container.items.erase(e)
	G.player.inv.add_entry(e)
	Sfx.play_var("pickup", -4.0)
	_refresh_inventory()


func _take_all() -> void:
	if _container == null:
		return
	for e in _container.items:
		G.player.inv.add_entry(e)
	_container.items.clear()
	Sfx.play_var("pickup", -4.0)
	_refresh_inventory()


func _store(e: Dictionary) -> void:
	if _container == null:
		return
	G.player.inv.remove_entry(e)
	_container.items.append(e)
	Sfx.play_var("pickup", -4.0)
	_refresh_inventory()


func _drop(e: Dictionary) -> void:
	var p := G.player
	p.inv.remove_entry(e)
	var pk := Props.Pickup.new()
	G.world.add_child(pk)
	var f := -p.global_transform.basis.z
	pk.setup(e, p.global_position + Vector3(f.x, 0, f.z) * 0.9 + Vector3.UP * 0.05)
	Sfx.play_var("pickup", -4.0, 0.2)
	_refresh_inventory()


# --- screens -----------------------------------------------------------------------------------
func _clear_screen() -> void:
	for c in screen.get_children():
		c.queue_free()
	screen.visible = false


func _screen_base(alpha: float) -> VBoxContainer:
	_clear_screen()
	screen.visible = true
	var bg := ColorRect.new()
	bg.color = Color(0.01, 0.012, 0.014, alpha)
	bg.set_anchors_preset(Control.PRESET_FULL_RECT)
	screen.add_child(bg)
	var vb := VBoxContainer.new()
	vb.alignment = BoxContainer.ALIGNMENT_CENTER
	vb.add_theme_constant_override("separation", 14)
	_anchor(vb, Control.PRESET_CENTER, Vector2(-460, -330), Vector2(920, 660))
	screen.add_child(vb)
	return vb


func _text(vb: VBoxContainer, text: String, size: int, color: Color) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", size)
	l.add_theme_color_override("font_color", color)
	l.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	l.autowrap_mode = TextServer.AUTOWRAP_ARBITRARY
	vb.add_child(l)
	return l


func show_menu() -> void:
	set_ingame(false)
	var vb := _screen_base(1.0)
	_text(vb, "锈 夜", 96, C_RED)
	_text(vb, "R U S T N I G H T", 24, C_DIM)
	_text(vb, "灰溪镇已经死了三个星期。你还没有。\n搜刮、躲藏、开枪——每一声枪响，全镇都听得见。\n找到车钥匙、电瓶和汽油，修好汽修厂的车，离开这里。", 24, C_TEXT)
	vb.add_child(_button("开 始", func(): G.main.start_run(), 36))
	if G.is_touch:
		_text(vb, "左下摇杆移动（推到底冲刺）· 右半屏滑动瞄准 · 右下按钮开火/瞄准/换弹/跳跃/蹲滑", 24, C_DIM)
	else:
		_text(vb, "WASD 移动 · Shift 冲刺 · C 蹲/冲刺中滑铲 · 空格 跳跃/翻越\n左键 开火 · 右键 瞄准 · R 换弹 · V 推搡 · 1/2/3 Q 切枪\nE 互动 · Tab 背包 · F 手电 · B 加固门窗 · Esc 暂停", 24, C_DIM)


func show_loading() -> void:
	var vb := _screen_base(1.0)
	_text(vb, "正在进入灰溪镇……", 36, C_TEXT)


func hide_screen() -> void:
	_clear_screen()


func toggle_pause() -> void:
	if not G.playing:
		return
	if paused:
		paused = false
		_clear_screen()
		get_tree().paused = false
		if not inv_panel.visible:
			G.ui_open = false
			G.capture_mouse(true)
		return
	paused = true
	get_tree().paused = true
	G.ui_open = true
	G.capture_mouse(false)
	var vb := _screen_base(0.75)
	_text(vb, "暂 停", 48, C_TEXT)
	vb.add_child(_button("继续", func(): toggle_pause(), 36))
	var row := HBoxContainer.new()
	row.alignment = BoxContainer.ALIGNMENT_CENTER
	var sl := Label.new()
	sl.text = "视角灵敏度  "
	sl.add_theme_color_override("font_color", C_TEXT)
	row.add_child(sl)
	var s := HSlider.new()
	s.min_value = 0.3
	s.max_value = 3.0
	s.step = 0.05
	s.value = G.sens
	s.custom_minimum_size = Vector2(360, 30)
	s.value_changed.connect(func(v): G.sens = v)
	row.add_child(s)
	vb.add_child(row)
	vb.add_child(_button("重新开始", func():
		paused = false
		get_tree().paused = false
		G.main.start_run(), 24))


func show_end(win: bool) -> void:
	set_ingame(false)
	var vb := _screen_base(0.86)
	var p := G.player
	if win:
		_text(vb, "你 逃 出 去 了", 72, C_TEXT)
		_text(vb, "后视镜里，灰溪镇沉进了雾里。", 24, C_DIM)
	else:
		_text(vb, "这 就 是 你 的 结 局", 72, C_RED)
		_text(vb, "死因：%s" % p.surv.cause, 36, C_TEXT)
	_text(vb, "存活 %s　　击杀 %d" % [G.survived_text(), G.kills], 36, C_TEXT)
	vb.add_child(_button("再来一次", func(): G.main.start_run(), 36))

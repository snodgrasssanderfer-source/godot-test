extends Control
## On-screen info: day/time/gold, backpack, crosshair, toasts, dialogue box and
## the sleep fade. Built in code; sizes are in the 1280x720 virtual canvas.

const CREAM := Color("fff6e4")
const BROWN := Color("6b4a35")
const TEXT_SPEED := 28.0          # characters per second in dialogue

var _time_label: Label
var _gold_label: Label
var _crops_label: Label
var _toast: Label
var _toast_tween: Tween
var _crosshair: Control
var _has_target := false
var _dialog: PanelContainer
var _dialog_name: Label
var _dialog_text: Label
var _dialog_hint: Label
var _dialog_lines: Array = []
var _dialog_index := 0
var _dialog_on_close: Callable
var _fade: ColorRect
var _day_title: Label
var _day_sub: Label


func _ready() -> void:
	set_anchors_and_offsets_preset(PRESET_FULL_RECT)
	mouse_filter = MOUSE_FILTER_IGNORE

	var status := _panel(Vector2(20, 18))
	var box := VBoxContainer.new()
	status.add_child(box)
	_time_label = _label("", 24)
	_gold_label = _label("", 24, Color("b07a1e"))
	_crops_label = _label("", 24, Color("c4566e"))
	box.add_child(_time_label)
	box.add_child(_gold_label)
	box.add_child(_crops_label)

	_crosshair = Control.new()
	_crosshair.set_anchors_preset(PRESET_CENTER)
	_crosshair.mouse_filter = MOUSE_FILTER_IGNORE
	_crosshair.draw.connect(_draw_crosshair)
	add_child(_crosshair)

	_toast = _label("", 24, CREAM)
	_toast.add_theme_color_override("font_outline_color", BROWN)
	_toast.add_theme_constant_override("outline_size", 8)
	_toast.set_anchors_preset(PRESET_CENTER_TOP)
	_toast.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_toast.position = Vector2(-400, 110)
	_toast.size = Vector2(800, 40)
	_toast.modulate.a = 0.0
	add_child(_toast)

	_build_dialog()
	_build_fade()

	Game.time_changed.connect(func(_m): _refresh())
	Game.gold_changed.connect(func(_g): _refresh())
	Game.crops_changed.connect(func(_c): _refresh())
	_refresh()


func _refresh() -> void:
	var icon := "☀" if Game.hour() >= 6.0 and Game.hour() < 19.0 else "☾"
	_time_label.text = "第 %d 天  %s %s" % [Game.day, icon, Game.clock_text()]
	_gold_label.text = "金币 %d" % Game.gold
	_crops_label.text = "%s ×%d" % [Game.CROP_NAME, Game.crops]
	_crops_label.visible = Game.crops > 0


func set_target(has_target: bool) -> void:
	_has_target = has_target
	_crosshair.queue_redraw()


func _draw_crosshair() -> void:
	if _has_target:
		_crosshair.draw_arc(Vector2.ZERO, 11, 0, TAU, 20, Color(BROWN, 0.6), 6)
		_crosshair.draw_arc(Vector2.ZERO, 11, 0, TAU, 20, Color("ffe08a"), 3)
	_crosshair.draw_circle(Vector2.ZERO, 4.5, Color(BROWN, 0.6))
	_crosshair.draw_circle(Vector2.ZERO, 3, CREAM)


func toast(text: String) -> void:
	_toast.text = text
	if _toast_tween:
		_toast_tween.kill()
	_toast.modulate.a = 1.0
	_toast.position.y = 110
	_toast_tween = create_tween()
	_toast_tween.tween_interval(1.6)
	_toast_tween.tween_property(_toast, "modulate:a", 0.0, 0.6)


# --- Dialogue ------------------------------------------------------------------

func dialogue_open() -> bool:
	return _dialog.visible


func show_dialogue(speaker: String, lines: Array, on_close: Callable) -> void:
	_dialog_name.text = speaker
	_dialog_lines = lines
	_dialog_index = 0
	_dialog_on_close = on_close
	_dialog.visible = true
	_dialog.scale = Vector2(0.9, 0.9)
	create_tween().tween_property(_dialog, "scale", Vector2.ONE, 0.18).set_trans(Tween.TRANS_BACK).set_ease(Tween.EASE_OUT)
	_show_line()


func advance_dialogue() -> void:
	if _dialog_text.visible_ratio < 1.0:
		_dialog_text.visible_ratio = 1.0
		return
	_dialog_index += 1
	if _dialog_index >= _dialog_lines.size():
		close_dialogue()
	else:
		_show_line()


func close_dialogue() -> void:
	if not _dialog.visible:
		return
	_dialog.visible = false
	if _dialog_on_close.is_valid():
		_dialog_on_close.call()


func _show_line() -> void:
	var line: String = _dialog_lines[_dialog_index]
	_dialog_text.text = line
	_dialog_text.visible_ratio = 0.0
	_dialog_hint.text = "▼" if _dialog_index < _dialog_lines.size() - 1 else "♪"
	Sfx.play("talk", -4.0, 0.1)
	var t := create_tween()
	t.tween_property(_dialog_text, "visible_ratio", 1.0, line.length() / TEXT_SPEED)


func _process(_delta: float) -> void:
	# Soft "blip" while text is typing, like a little voice.
	if _dialog.visible and _dialog_text.visible_ratio < 1.0 and Engine.get_process_frames() % 6 == 0:
		Sfx.play("talk", -16.0, 0.2)


func _build_dialog() -> void:
	_dialog = PanelContainer.new()
	_dialog.add_theme_stylebox_override("panel", _style(CREAM, 0.96, 18))
	_dialog.set_anchors_preset(PRESET_CENTER_BOTTOM)
	_dialog.custom_minimum_size = Vector2(720, 150)
	_dialog.position = Vector2(-360, -190)
	_dialog.pivot_offset = Vector2(360, 150)
	_dialog.mouse_filter = MOUSE_FILTER_IGNORE
	_dialog.visible = false
	add_child(_dialog)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 6)
	_dialog.add_child(v)
	_dialog_name = _label("", 24, Color("c4566e"))
	_dialog_text = _label("", 24)
	_dialog_text.autowrap_mode = TextServer.AUTOWRAP_ARBITRARY
	_dialog_text.custom_minimum_size = Vector2(680, 64)
	_dialog_hint = _label("", 24, Color(BROWN, 0.6))
	_dialog_hint.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	v.add_child(_dialog_name)
	v.add_child(_dialog_text)
	v.add_child(_dialog_hint)


# --- Sleep fade ------------------------------------------------------------------

func _build_fade() -> void:
	_fade = ColorRect.new()
	_fade.color = Color("1d2240")
	_fade.set_anchors_preset(PRESET_FULL_RECT)
	_fade.mouse_filter = MOUSE_FILTER_IGNORE
	_fade.modulate.a = 0.0
	add_child(_fade)
	var v := VBoxContainer.new()
	v.set_anchors_preset(PRESET_FULL_RECT)
	v.alignment = BoxContainer.ALIGNMENT_CENTER
	_fade.add_child(v)
	_day_title = _label("", 48, CREAM)
	_day_title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	_day_sub = _label("", 24, Color(CREAM, 0.8))
	_day_sub.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(_day_title)
	v.add_child(_day_sub)


func fade_to(alpha: float, seconds: float) -> void:
	var t := create_tween()
	t.tween_property(_fade, "modulate:a", alpha, seconds)
	await t.finished


func set_day_card(title: String, subtitle: String) -> void:
	_day_title.text = title
	_day_sub.text = subtitle


func cover_instantly() -> void:
	_fade.modulate.a = 1.0


# --- Helpers -------------------------------------------------------------------

func _style(bg: Color, alpha: float, pad: int) -> StyleBoxFlat:
	var s := StyleBoxFlat.new()
	s.bg_color = Color(bg, alpha)
	s.border_color = BROWN
	s.set_border_width_all(3)
	s.set_corner_radius_all(12)
	s.set_content_margin_all(pad)
	return s


func _panel(pos: Vector2) -> PanelContainer:
	var p := PanelContainer.new()
	p.add_theme_stylebox_override("panel", _style(CREAM, 0.88, 12))
	p.position = pos
	p.mouse_filter = MOUSE_FILTER_IGNORE
	add_child(p)
	return p


func _label(text: String, font_size: int, color := BROWN) -> Label:
	var l := Label.new()
	l.text = text
	l.add_theme_font_size_override("font_size", font_size)
	l.add_theme_color_override("font_color", color)
	l.mouse_filter = MOUSE_FILTER_IGNORE
	return l

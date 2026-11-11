class_name Hud
extends CanvasLayer
## The in-game HUD, kept to the bare minimum: a crosshair that shows the real
## spread, a hit marker, an ammo count that only appears while it matters
## (aiming, reloading, just after a shot or a weapon change) and a stamina bar
## that only appears while stamina is not full. Everything is drawn in code and
## scaled to the height of the screen.

## How long the ammo count stays up after it last changed.
const AMMO_LINGER := 2.5
const HIT_TIME := 0.22

## Show the damage of the last hit next to the crosshair (shooting range).
var show_damage := false

var player: Player
var rig: WeaponRig

var _canvas: Control
var _font: Font
var _ammo_linger := 0.0
var _ammo_alpha := 0.0
var _name_alpha := 0.0
var _stamina_alpha := 0.0
var _cross_alpha := 1.0
var _hit_left := 0.0
var _hit_head := false
var _hit_kill := false
var _damage_text := ""
var _damage_left := 0.0


func _init() -> void:
	layer = 105         # above the film grade (100), so the HUD stays clean


func setup(for_player: Player) -> void:
	player = for_player
	rig = player.rig
	_font = ThemeDB.fallback_font
	_canvas = Control.new()
	_canvas.set_anchors_preset(Control.PRESET_FULL_RECT)
	_canvas.mouse_filter = Control.MOUSE_FILTER_IGNORE
	_canvas.draw.connect(_draw_hud)
	add_child(_canvas)
	rig.ammo_changed.connect(func(_w: Weapon) -> void: _ammo_linger = AMMO_LINGER)
	rig.weapon_changed.connect(func(_w: Weapon) -> void:
		_ammo_linger = AMMO_LINGER
		_name_alpha = 1.6)
	rig.hit_confirmed.connect(_on_hit)
	_ammo_linger = AMMO_LINGER
	_name_alpha = 1.6


func _on_hit(head: bool, killed: bool, damage: float) -> void:
	_hit_left = HIT_TIME * (1.5 if killed else 1.0)
	_hit_head = head
	_hit_kill = killed
	_damage_text = "%d%s" % [roundi(damage), "  HEAD" if head else ""]
	_damage_left = 0.9
	Sfx.play(&"hitmarker", -5.0 if killed else -9.0, 0.02)


func _process(delta: float) -> void:
	if rig == null or rig.current == null:
		return
	_ammo_linger = maxf(_ammo_linger - delta, 0.0)
	var show_ammo := _ammo_linger > 0.0 or rig.aim > 0.3 or rig.is_reloading()
	_ammo_alpha = move_toward(_ammo_alpha, 1.0 if show_ammo else 0.0, delta * (8.0 if show_ammo else 1.6))
	_name_alpha = maxf(_name_alpha - delta, 0.0)
	var tired := player.stamina < player.stamina_max - 0.5
	_stamina_alpha = move_toward(_stamina_alpha, 1.0 if tired else 0.0, delta * (6.0 if tired else 1.5))
	var cross := 0.0 if player.sprinting or rig.state in [WeaponRig.State.HOLSTER, WeaponRig.State.EQUIP] else 1.0
	_cross_alpha = move_toward(_cross_alpha, cross, delta * 7.0)
	_hit_left = maxf(_hit_left - delta, 0.0)
	_damage_left = maxf(_damage_left - delta, 0.0)
	_canvas.queue_redraw()


func _draw_hud() -> void:
	if rig == null or rig.current == null:
		return
	var size := _canvas.size
	var u := size.y / 1080.0               # one design pixel
	var c := (size * 0.5).round()
	var d := rig.current.data
	var white := Color(0.93, 0.94, 0.92)
	var shade := Color(0.0, 0.0, 0.0, 0.55)

	# Crosshair. The gap is the actual spread cone projected onto the screen.
	var alpha := _cross_alpha * (1.0 - smoothstep(0.25, 0.75, rig.aim))
	if alpha > 0.01:
		if d.kind == WeaponData.Kind.MELEE:
			_canvas.draw_circle(c, 3.0 * u, Color(shade, shade.a * alpha))
			_canvas.draw_circle(c, 1.8 * u, Color(white, 0.8 * alpha))
		else:
			var half_fov := deg_to_rad(player.camera.fov) * 0.5
			var gap := maxf(tan(deg_to_rad(rig.spread)) / tan(half_fov) * size.y * 0.5, 5.0 * u)
			var length := 9.0 * u
			for dir: Vector2 in [Vector2.RIGHT, Vector2.LEFT, Vector2.UP, Vector2.DOWN]:
				var a := c + dir * gap
				var b := c + dir * (gap + length)
				_canvas.draw_line(a - dir * u, b + dir * u, Color(shade, shade.a * alpha), 4.0 * u)
				_canvas.draw_line(a, b, Color(white, 0.9 * alpha), 2.0 * u)
			_canvas.draw_circle(c, 1.2 * u, Color(white, 0.6 * alpha))

	# Hit marker: four ticks. White for a hit, amber for the head, red for a kill.
	if _hit_left > 0.0:
		var t := _hit_left / HIT_TIME
		var col := Color(1.0, 0.25, 0.2) if _hit_kill else (Color(1.0, 0.82, 0.35) if _hit_head else white)
		col.a = clampf(t * 1.6, 0.0, 1.0)
		var inner := (7.0 + 5.0 * clampf(t, 0.0, 1.0)) * u
		var outer := inner + (11.0 if _hit_kill or _hit_head else 8.0) * u
		for dir: Vector2 in [Vector2(1, 1), Vector2(-1, 1), Vector2(1, -1), Vector2(-1, -1)]:
			var n := dir.normalized()
			_canvas.draw_line(c + n * inner, c + n * outer, Color(0, 0, 0, col.a * 0.5), 4.5 * u)
			_canvas.draw_line(c + n * inner, c + n * outer, col, 2.4 * u)
	if show_damage and _damage_left > 0.0:
		_text(_damage_text, c + Vector2(26.0, -18.0) * u, 22.0 * u,
				Color(white, clampf(_damage_left * 2.5, 0.0, 1.0)), HORIZONTAL_ALIGNMENT_LEFT)

	# Ammo, bottom right.
	var right := size.x - 70.0 * u
	var base := size.y - 70.0 * u
	if d.kind != WeaponData.Kind.MELEE and _ammo_alpha > 0.01:
		var mag := rig.current.mag
		var col := white
		if mag == 0:
			col = Color(1.0, 0.3, 0.25)
		elif mag <= ceili(d.mag_size * 0.25):
			col = Color(1.0, 0.75, 0.3)
		var reserve := "  / ∞" if rig.infinite_reserve else "  / %d" % rig.current.reserve
		var reserve_w := _font.get_string_size(reserve, HORIZONTAL_ALIGNMENT_LEFT, -1, int(26.0 * u)).x
		_text(reserve, Vector2(right, base), 26.0 * u, Color(white, 0.7 * _ammo_alpha), HORIZONTAL_ALIGNMENT_RIGHT)
		_text(str(mag), Vector2(right - reserve_w, base), 48.0 * u, Color(col, _ammo_alpha), HORIZONTAL_ALIGNMENT_RIGHT)
	if _name_alpha > 0.01:
		_text(d.display_name, Vector2(right, base - 58.0 * u), 20.0 * u,
				Color(white, 0.8 * clampf(_name_alpha, 0.0, 1.0)), HORIZONTAL_ALIGNMENT_RIGHT)

	# Stamina, bottom centre.
	if _stamina_alpha > 0.01:
		var w := 240.0 * u
		var h := 5.0 * u
		var at := Vector2(c.x - w * 0.5, size.y - 64.0 * u)
		var fill := player.stamina / player.stamina_max
		var col := Color(1.0, 0.35, 0.28) if player.exhausted else white
		_canvas.draw_rect(Rect2(at - Vector2(u, u), Vector2(w + 2.0 * u, h + 2.0 * u)), Color(0, 0, 0, 0.45 * _stamina_alpha))
		_canvas.draw_rect(Rect2(at, Vector2(w * fill, h)), Color(col, 0.85 * _stamina_alpha))


## Draws outlined text. `at` is the baseline; right-aligned text ends there.
func _text(text: String, at: Vector2, font_size: float, color: Color, align: HorizontalAlignment) -> void:
	var px := maxi(int(font_size), 1)
	if align == HORIZONTAL_ALIGNMENT_RIGHT:
		at.x -= _font.get_string_size(text, HORIZONTAL_ALIGNMENT_LEFT, -1, px).x
	_canvas.draw_string_outline(_font, at, text, HORIZONTAL_ALIGNMENT_LEFT, -1, px, maxi(px / 6, 2),
			Color(0, 0, 0, 0.6 * color.a))
	_canvas.draw_string(_font, at, text, HORIZONTAL_ALIGNMENT_LEFT, -1, px, color)

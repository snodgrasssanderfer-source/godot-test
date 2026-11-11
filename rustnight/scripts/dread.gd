class_name Dread
extends Node3D
## Atmosphere director: ambient loops, heartbeat / breathing, distant sounds
## that also pull the horde around, and the things you only think you see.

var _wind: AudioStreamPlayer
var _drone: AudioStreamPlayer
var _crickets: AudioStreamPlayer
var _rain: AudioStreamPlayer
var _hb_t := 0.0
var _br_t := 0.0
var _event_t := 40.0
var _figure: MeshInstance3D
var _fig_life := 0.0
var _fig_stare := 0.0
var _stung := false


func _ready() -> void:
	_wind = Sfx.make_loop("amb_wind", self)
	_drone = Sfx.make_loop("amb_drone", self)
	_crickets = Sfx.make_loop("amb_night", self)
	_rain = Sfx.make_loop("amb_rain", self)
	_figure = MeshInstance3D.new()
	var q := QuadMesh.new()
	q.size = Vector2(1.0, 2.3)
	_figure.mesh = q
	var m: StandardMaterial3D = Mat.unshaded("figure").duplicate()
	m.billboard_mode = BaseMaterial3D.BILLBOARD_FIXED_Y
	_figure.material_override = m
	_figure.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	_figure.visible = false
	add_child(_figure)


func _vol(pl: AudioStreamPlayer, target_db: float, delta: float) -> void:
	pl.volume_db = move_toward(pl.volume_db, target_db, delta * 14.0)


func _process(delta: float) -> void:
	var p := G.player
	if p == null or not G.playing:
		for l in [_wind, _drone, _crickets, _rain]:
			_vol(l, -60.0, delta)
		return
	var ind := p.indoor
	var panic := p.surv.panic
	_vol(_wind, (-24.0 if ind else -15.0) + G.rain * 3.0, delta)
	_vol(_drone, -30.0 + G.night * 9.0 + (4.0 if ind else 0.0) + panic * 0.08, delta)
	_vol(_crickets, -60.0 if (ind or G.rain > 0.2 or G.night < 0.5) else -26.0, delta)
	_vol(_rain, -60.0 if G.rain < 0.05 else (-22.0 if ind else -11.0) + G.rain * 4.0, delta)

	# heartbeat when terrified or badly hurt, breathing when winded
	if panic > 45.0 or p.surv.health < 30.0:
		_hb_t -= delta
		if _hb_t <= 0.0:
			var lvl := maxf(panic / 100.0, 1.0 - p.surv.health / 30.0)
			_hb_t = lerpf(1.15, 0.6, clampf(lvl, 0.0, 1.0))
			Sfx.play("heartbeat", lerpf(-14.0, -3.0, clampf(lvl, 0.0, 1.0)))
	if p.surv.endurance < 25.0:
		_br_t -= delta
		if _br_t <= 0.0:
			_br_t = 1.45
			Sfx.play("breath", -8.0, randf_range(0.95, 1.05))

	_event_t -= delta * (1.0 + G.night * 0.8 + panic * 0.01)
	if _event_t <= 0.0:
		_event_t = randf_range(28.0, 75.0)
		_event()
	_figure_step(delta)


func _event() -> void:
	var p := G.player
	var r := randf()
	if r < 0.3:
		# gunfire somewhere else in town: someone is still alive out there (for now)
		var pos := G.town.random_point(p.global_position, 55.0)
		Sfx.play_at("shot_far", pos + Vector3.UP * 2.0, 8.0, randf_range(0.9, 1.1), 400.0)
		G.emit_noise(pos, 75.0, "far")
		if randf() < 0.3:
			G.msg("远处传来枪声。然后安静了。", Color(0.6, 0.62, 0.6))
	elif r < 0.45 and G.night > 0.4:
		var pos2 := G.town.random_point(p.global_position, 50.0)
		Sfx.play_at("z_scream_far", pos2 + Vector3.UP * 2.0, 8.0, randf_range(0.85, 1.05), 400.0)
		G.emit_noise(pos2, 60.0, "far")
	elif r < 0.65 and (p.surv.panic > 30.0 or (p.indoor and G.night > 0.5)):
		Sfx.play("whisper", -12.0, randf_range(0.8, 1.1))
	elif r < 0.9 and (G.night > 0.5 or p.surv.panic > 50.0):
		_spawn_figure()


## A tall dark shape at the edge of the fog. It is never there when you get close.
func _spawn_figure() -> void:
	var p := G.player
	if _figure.visible:
		return
	var fwd := -p.global_transform.basis.z
	for attempt in 8:
		var ang := randf_range(-0.8, 0.8)
		var dist := randf_range(13.0, minf(22.0, G.sky.fog_end * 0.7))
		var pos := p.global_position + fwd.rotated(Vector3.UP, ang) * dist
		if not G.town.is_free(pos) or G.town.is_indoor(pos) != p.indoor:
			continue
		var q := PhysicsRayQueryParameters3D.create(p.cam.global_position, pos + Vector3.UP * 1.4, G.L_WORLD)
		if not get_world_3d().direct_space_state.intersect_ray(q).is_empty():
			continue
		_figure.global_position = Vector3(pos.x, 1.15, pos.z)
		_figure.visible = true
		_fig_life = randf_range(8.0, 18.0)
		_fig_stare = 0.0
		return


func _figure_step(delta: float) -> void:
	if not _figure.visible:
		return
	var p := G.player
	_fig_life -= delta
	var to := _figure.global_position - p.cam.global_position
	var d := to.length()
	var looking := (-p.cam.global_transform.basis.z).dot(to / d) > 0.985
	if looking:
		_fig_stare += delta
	if d < 9.0 or _fig_life <= 0.0 or _fig_stare > 0.7 or (p.flash_on and looking and d < 20.0 and _fig_stare > 0.25):
		_figure.visible = false
		if _fig_stare > 0.2:
			p.surv.panic = minf(100.0, p.surv.panic + 14.0)
			if not _stung or randf() < 0.25:
				_stung = true
				Sfx.play("sting", -6.0)

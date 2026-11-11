class_name DayNight
extends Node3D
## Runs the clock and everything that follows from it: sun / moon, ambient
## light, distance fog (which doubles as the draw distance), rain and the
## town's power going out.

var env: Environment
var sun: DirectionalLight3D
var rain_fx: CPUParticles3D
var _rain_target := 0.0
var _rain_hours := 0.0
var _last_hour := -1
var _flash := 0.0
var _thunder_t := 20.0
var _power_done := false
var fog_end := 70.0


func _ready() -> void:
	env = Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.fog_enabled = true
	env.fog_mode = Environment.FOG_MODE_DEPTH
	env.fog_density = 1.0
	env.fog_depth_curve = 1.0
	env.fog_depth_begin = 3.0
	env.tonemap_mode = Environment.TONE_MAPPER_LINEAR
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
	sun = DirectionalLight3D.new()
	sun.light_cull_mask = G.VIS_OUT
	sun.shadow_enabled = not G.low_spec
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_ORTHOGONAL
	sun.directional_shadow_max_distance = 45.0
	sun.shadow_bias = 0.06
	sun.shadow_normal_bias = 1.5
	add_child(sun)
	rain_fx = CPUParticles3D.new()
	rain_fx.amount = 140 if G.low_spec else 320
	rain_fx.lifetime = 0.55
	rain_fx.emission_shape = CPUParticles3D.EMISSION_SHAPE_BOX
	rain_fx.emission_box_extents = Vector3(9, 0.5, 9)
	rain_fx.direction = Vector3(0.08, -1, 0.04)
	rain_fx.spread = 2.0
	rain_fx.gravity = Vector3.ZERO
	rain_fx.initial_velocity_min = 15.0
	rain_fx.initial_velocity_max = 18.0
	var q := QuadMesh.new()
	q.size = Vector2(0.03, 0.55)
	rain_fx.mesh = q
	var rm := Mat.unshaded("", true, Color(0.6, 0.66, 0.7, 0.35)).duplicate()
	rm.billboard_mode = BaseMaterial3D.BILLBOARD_FIXED_Y
	rm.disable_fog = false
	rain_fx.material_override = rm
	rain_fx.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	rain_fx.emitting = false
	rain_fx.local_coords = false
	add_child(rain_fx)
	_apply(0.0)


func _process(delta: float) -> void:
	if G.playing and G.player and not G.player.sleeping:
		G.clock += delta / G.MINUTE_SECONDS
	var h := int(G.clock / 60.0)
	if h != _last_hour:
		_last_hour = h
		_hourly()
	G.rain = move_toward(G.rain, _rain_target, delta * 0.08)
	_apply(delta)
	if G.player:
		var pp := G.player.global_position
		rain_fx.global_position = pp + Vector3(0, 7.5, 0)
		rain_fx.emitting = G.rain > 0.15 and not G.player.indoor
	if G.rain > 0.5 and G.playing:
		_thunder_t -= delta
		if _thunder_t <= 0.0:
			_thunder_t = randf_range(25.0, 70.0)
			_flash = 1.0
			Sfx.play("thunder", randf_range(-8.0, -2.0), randf_range(0.85, 1.1))


func _hourly() -> void:
	if not G.playing:
		return
	if _rain_target > 0.0:
		_rain_hours -= 1.0
		if _rain_hours <= 0.0:
			_rain_target = 0.0
	elif randf() < 0.14 and G.clock > G.START_CLOCK + 120.0:
		_rain_target = randf_range(0.6, 1.0)
		_rain_hours = randf_range(1.0, 4.0)
		G.msg("下雨了。雨声会盖住你的脚步——也盖住它们的。", Color(0.6, 0.7, 0.8))
	# the grid fails on the evening of day 2
	if not _power_done and G.clock >= 1440.0 + 21.0 * 60.0:
		_power_done = true
		G.town.set_power(false)
		Sfx.play("flashlight", 0.0, 0.5)
		G.msg("远处传来一声闷响，所有的灯同时熄灭了。镇上停电了。", Color(0.85, 0.6, 0.4))


func _apply(_delta: float) -> void:
	var h := G.hour()
	var day_f := smoothstep(5.2, 7.6, h) * (1.0 - smoothstep(18.2, 20.6, h))
	G.night = 1.0 - day_f
	var dusk := maxf(0.0, 1.0 - absf(h - 19.0) / 1.6) + maxf(0.0, 1.0 - absf(h - 6.2) / 1.4)
	var gloom := 1.0 - G.rain * 0.35
	# sun travels east -> west; at night a weak moon hangs high
	var a := clampf((h - 6.0) / 13.0, 0.0, 1.0)
	var elev := lerpf(0.25, 1.0, sin(a * PI)) * 0.95
	if day_f > 0.02:
		sun.rotation = Vector3(-elev, lerpf(-1.6, 1.6, a), 0)
	else:
		sun.rotation = Vector3(-1.05, 0.7, 0)
	var day_col := Color(0.82, 0.8, 0.74).lerp(Color(0.95, 0.55, 0.3), clampf(dusk, 0.0, 1.0) * 0.7)
	sun.light_color = Color(0.4, 0.5, 0.75).lerp(day_col, day_f)
	sun.light_energy = lerpf(0.07, 0.85, day_f) * gloom
	var amb_day := Color(0.36, 0.38, 0.39).lerp(Color(0.4, 0.3, 0.26), clampf(dusk, 0.0, 1.0) * 0.6)
	var amb := Color(0.035, 0.042, 0.06).lerp(amb_day, day_f) * gloom
	var fog_day := Color(0.4, 0.42, 0.42).lerp(Color(0.42, 0.3, 0.24), clampf(dusk, 0.0, 1.0) * 0.6)
	var fog := Color(0.012, 0.016, 0.024).lerp(fog_day, day_f) * gloom
	if _flash > 0.0:
		_flash = maxf(0.0, _flash - 0.06)
		var f := _flash * _flash
		amb = amb.lerp(Color(0.6, 0.65, 0.8), f * 0.8)
		fog = fog.lerp(Color(0.5, 0.55, 0.7), f * 0.6)
	env.ambient_light_color = amb
	env.ambient_light_energy = 1.0
	env.fog_light_color = fog
	env.background_color = fog
	fog_end = lerpf(30.0, 74.0, day_f) * (1.0 - G.rain * 0.22)
	env.fog_depth_end = fog_end
	if G.player:
		G.player.cam.far = fog_end + 8.0

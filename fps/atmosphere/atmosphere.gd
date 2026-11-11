class_name Atmosphere
extends Node3D
## Owns the look of the world: sun and moon, sky, fog, exposure and the final
## colour grade. Blends between four presets as the clock runs:
##
##   night -> (dawn) -> day -> (late afternoon) -> dusk -> night
##
## "night" is night_powered while the town still has electricity and
## night_blackout after it fails. Lights in the "street_lamp" group follow the
## current preset's lamp_power.

signal power_changed(on: bool)

@export var day: AtmospherePreset
@export var dusk: AtmospherePreset
@export var night_powered: AtmospherePreset
@export var night_blackout: AtmospherePreset

@export_group("Clock")
@export_range(0.0, 24.0, 0.01) var time_of_day := 10.0: set = set_time
## Real minutes for one in-game day. 0 freezes the clock.
@export var day_length_minutes := 20.0
@export var power_on := true: set = set_power

@export_group("Sun path")
@export var sunrise := 6.0
@export var sunset := 18.5
@export var sun_max_elevation := 38.0
## Compass direction the sun sets in, degrees. 0 looks down -Z, 90 down +X.
@export var sunset_azimuth := 12.0

@export_group("Quality")
@export var volumetric_fog := true: set = set_volumetric_fog
@export var ssao := true: set = set_ssao
@export var sdfgi := false: set = set_sdfgi

## The blended preset currently applied. Read-only for other systems.
var current: AtmospherePreset
## Final film pass. Sanity effects can drive its extra_* uniforms.
var grade: ShaderMaterial

var _env: Environment
var _sun: DirectionalLight3D
var _moon: DirectionalLight3D


func _ready() -> void:
	_env = Environment.new()
	var sky_mat := ProceduralSkyMaterial.new()
	sky_mat.sun_angle_max = 0.0
	var sky := Sky.new()
	sky.sky_material = sky_mat
	_env.sky = sky
	_env.background_mode = Environment.BG_SKY
	_env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	_env.reflected_light_source = Environment.REFLECTION_SOURCE_SKY
	_env.tonemap_mode = Environment.TONE_MAPPER_AGX
	_env.fog_enabled = true
	_env.fog_sky_affect = 1.0
	_env.fog_aerial_perspective = 0.0
	_env.volumetric_fog_enabled = volumetric_fog
	_env.volumetric_fog_length = 80.0
	_env.volumetric_fog_ambient_inject = 0.4
	_env.glow_enabled = true
	_env.glow_blend_mode = Environment.GLOW_BLEND_MODE_SCREEN
	_env.ssao_enabled = ssao
	_env.sdfgi_enabled = sdfgi
	var world := WorldEnvironment.new()
	world.environment = _env
	add_child(world)

	_sun = _make_directional("Sun")
	_moon = _make_directional("Moon")
	_moon.basis = Basis.looking_at(-_direction(42.0, 160.0), Vector3.UP)

	grade = ShaderMaterial.new()
	grade.shader = preload("res://atmosphere/post_grade.gdshader")
	var layer := CanvasLayer.new()
	layer.layer = 100
	var rect := ColorRect.new()
	rect.material = grade
	rect.mouse_filter = Control.MOUSE_FILTER_IGNORE
	rect.set_anchors_preset(Control.PRESET_FULL_RECT)
	layer.add_child(rect)
	add_child(layer)

	_apply()


func _process(delta: float) -> void:
	if day_length_minutes > 0.0:
		time_of_day = fposmod(time_of_day + delta * 24.0 / (day_length_minutes * 60.0), 24.0)


func set_time(hours: float) -> void:
	time_of_day = hours
	if is_node_ready():
		_apply()


func set_power(on: bool) -> void:
	if power_on == on:
		return
	power_on = on
	power_changed.emit(on)
	if is_node_ready():
		_apply()


func set_volumetric_fog(on: bool) -> void:
	volumetric_fog = on
	if _env:
		_env.volumetric_fog_enabled = on


func set_ssao(on: bool) -> void:
	ssao = on
	if _env:
		_env.ssao_enabled = on


func set_sdfgi(on: bool) -> void:
	sdfgi = on
	if _env:
		_env.sdfgi_enabled = on


func _make_directional(light_name: String) -> DirectionalLight3D:
	var light := DirectionalLight3D.new()
	light.name = light_name
	light.shadow_enabled = true
	light.directional_shadow_max_distance = 120.0
	light.light_angular_distance = 1.5
	add_child(light)
	return light


## Unit vector pointing at a light in the sky. Azimuth 0 = -Z, 90 = +X.
static func _direction(elevation_deg: float, azimuth_deg: float) -> Vector3:
	var e := deg_to_rad(elevation_deg)
	var a := deg_to_rad(azimuth_deg)
	return Vector3(cos(e) * sin(a), sin(e), -cos(e) * cos(a))


func _preset_at(h: float) -> AtmospherePreset:
	var night := night_powered if power_on else night_blackout
	var dawn_start := sunrise - 0.5
	var dawn_end := sunrise + 1.0
	var dusk_start := sunset - 2.0
	var dusk_peak := sunset - 0.5
	var dusk_end := sunset + 1.0
	if h < dawn_start or h >= dusk_end:
		return night
	if h < dawn_end:
		return AtmospherePreset.blend(night, day, smoothstep(dawn_start, dawn_end, h))
	if h < dusk_start:
		return day
	if h < dusk_peak:
		return AtmospherePreset.blend(day, dusk, smoothstep(dusk_start, dusk_peak, h))
	return AtmospherePreset.blend(dusk, night, smoothstep(dusk_peak, dusk_end, h))


func _apply() -> void:
	if day == null or dusk == null or night_powered == null or night_blackout == null:
		push_warning("Atmosphere: assign all four presets.")
		return
	var p := _preset_at(time_of_day)
	current = p

	# The sun rides an arc from sunrise to sunset; it is never allowed to go
	# fully horizontal, which would stretch its shadows to infinity.
	var t := clampf(inverse_lerp(sunrise, sunset, time_of_day), 0.0, 1.0)
	var elevation := maxf(sun_max_elevation * sin(PI * t), 2.0)
	_sun.basis = Basis.looking_at(-_direction(elevation, sunset_azimuth + 180.0 * (1.0 - t)), Vector3.UP)
	_sun.light_color = p.sun_color
	_sun.light_energy = p.sun_energy
	_sun.light_volumetric_fog_energy = p.sun_fog_energy
	_sun.visible = p.sun_energy > 0.001
	_moon.light_color = p.moon_color
	_moon.light_energy = p.moon_energy
	_moon.visible = p.moon_energy > 0.001

	var sky_mat := _env.sky.sky_material as ProceduralSkyMaterial
	sky_mat.sky_top_color = p.sky_color
	sky_mat.sky_horizon_color = p.sky_color
	sky_mat.ground_horizon_color = p.sky_color
	sky_mat.ground_bottom_color = p.sky_color
	_env.background_energy_multiplier = p.sky_energy
	_env.ambient_light_color = p.ambient_color
	_env.ambient_light_energy = p.ambient_energy
	_env.fog_light_color = p.fog_color
	_env.fog_density = p.fog_density
	_env.fog_sun_scatter = p.fog_sun_scatter
	_env.volumetric_fog_density = p.volumetric_density
	_env.volumetric_fog_albedo = p.volumetric_albedo
	_env.volumetric_fog_anisotropy = p.volumetric_anisotropy
	_env.tonemap_exposure = p.exposure
	_env.glow_intensity = p.glow_intensity
	_env.glow_hdr_threshold = p.glow_threshold

	grade.set_shader_parameter(&"saturation", p.saturation)
	grade.set_shader_parameter(&"shadow_tint", Vector3(p.shadow_tint.r, p.shadow_tint.g, p.shadow_tint.b))
	grade.set_shader_parameter(&"highlight_tint", Vector3(p.highlight_tint.r, p.highlight_tint.g, p.highlight_tint.b))
	grade.set_shader_parameter(&"contrast", p.contrast)
	grade.set_shader_parameter(&"gain", p.gain)
	grade.set_shader_parameter(&"vignette", p.vignette)
	grade.set_shader_parameter(&"grain", p.grain)

	for lamp: Light3D in get_tree().get_nodes_in_group(&"street_lamp"):
		if not lamp.has_meta(&"base_energy"):
			lamp.set_meta(&"base_energy", lamp.light_energy)
		lamp.light_energy = lamp.get_meta(&"base_energy") * p.lamp_power
		lamp.set_meta(&"powered_energy", lamp.light_energy)      # GridNode flickers around this
		lamp.visible = lamp.light_energy > 0.001

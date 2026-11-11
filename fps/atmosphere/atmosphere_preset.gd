class_name AtmospherePreset
extends Resource
## One complete look: light, fog, exposure and colour grade.
## Atmosphere blends between presets over the day, so every value here must be
## a float, Color or Vector3 (anything else is copied, not blended).

@export_group("Sun and moon")
@export var sun_color := Color(0.85, 0.92, 1.0)
@export var sun_energy := 0.6
## How strongly the sun lights the volumetric fog (shafts, glow around the sun).
@export var sun_fog_energy := 1.0
@export var moon_color := Color(0.55, 0.72, 1.0)
@export var moon_energy := 0.0

@export_group("Sky and ambient")
@export var sky_color := Color(0.50, 0.58, 0.66)
@export var sky_energy := 1.0
@export var ambient_color := Color(0.50, 0.58, 0.66)
@export var ambient_energy := 1.0

@export_group("Fog")
## Distance fog: hides the far end of the street. Roughly 3 / density = metres to near-white.
@export var fog_color := Color(0.60, 0.66, 0.72)
@export var fog_density := 0.03
## Extra glow in the distance fog towards the sun (dusk).
@export_range(0.0, 1.0) var fog_sun_scatter := 0.0
## Volumetric fog: what light cones and shafts are drawn into, close to the camera.
@export var volumetric_density := 0.02
@export var volumetric_albedo := Color(0.8, 0.85, 0.9)
@export_range(-0.9, 0.9) var volumetric_anisotropy := 0.2

@export_group("Artificial light")
## Multiplier for every light in the "street_lamp" group (0 = off).
@export_range(0.0, 1.0) var lamp_power := 0.0

@export_group("Camera")
@export var exposure := 1.0
@export var glow_intensity := 0.4
@export var glow_threshold := 1.0

@export_group("Grade")
@export_range(0.0, 1.5) var saturation := 0.72
@export var shadow_tint := Color(0.94, 1.0, 1.07)
@export var highlight_tint := Color(1.0, 1.0, 0.98)
@export var contrast := 1.10
@export var gain := 1.0
@export_range(0.0, 1.0) var vignette := 0.35
@export_range(0.0, 0.1) var grain := 0.022


## Returns a new preset that is `t` of the way from `a` to `b`.
static func blend(a: AtmospherePreset, b: AtmospherePreset, t: float) -> AtmospherePreset:
	if t <= 0.0:
		return a
	if t >= 1.0:
		return b
	var out := AtmospherePreset.new()
	for p in a.get_property_list():
		if not (p.usage & PROPERTY_USAGE_SCRIPT_VARIABLE):
			continue
		var va = a.get(p.name)
		var vb = b.get(p.name)
		match typeof(va):
			TYPE_FLOAT:
				out.set(p.name, lerpf(va, vb, t))
			TYPE_COLOR, TYPE_VECTOR3:
				out.set(p.name, va.lerp(vb, t))
			_:
				out.set(p.name, va if t < 0.5 else vb)
	return out

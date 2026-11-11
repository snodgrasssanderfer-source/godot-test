class_name Mat
extends RefCounted
## Material cache. Everything is nearest-filtered, fully rough, no specular:
## the look comes from the low-res textures, fog and the post shader.

static var _cache := {}
static var _tex := {}

# world-space size (metres) of one texture repeat for the tiling materials
const TILE := {
	"asphalt": Vector2(4, 4), "sidewalk": Vector2(2, 2), "grass": Vector2(5, 5), "dirt": Vector2(4, 4),
	"brick": Vector2(2, 2), "brick_dark": Vector2(2, 2), "siding": Vector2(2.4, 2.4),
	"concrete": Vector2(3, 3), "concrete_floor": Vector2(3, 3),
	"plaster": Vector2(2.8, 2.8), "wallpaper": Vector2(2.8, 2.8), "tiles_wall": Vector2(2.8, 2.8),
	"wood_floor": Vector2(2.4, 2.4), "wood": Vector2(1.6, 1.6), "tile_floor": Vector2(2, 2),
	"carpet": Vector2(2, 2), "ceiling": Vector2(2.4, 2.4), "roof": Vector2(2.4, 2.4),
	"metal": Vector2(2, 2), "metal_ribbed": Vector2(2, 2), "metal_green": Vector2(2, 2), "metal_dark": Vector2(2, 2),
	"fabric": Vector2(1, 1), "fabric_red": Vector2(1, 1), "mattress": Vector2(1, 1), "appliance": Vector2(1, 1),
}
const ALPHA_CUT := ["planks", "paint_yellow", "paint_white", "blood0", "blood1", "blood2", "bullet_hole", "glass_broken", "figure", "puff"]


static func tex(tname: String) -> Texture2D:
	if not _tex.has(tname):
		_tex[tname] = load("res://tex/%s.png" % tname)
	return _tex[tname]


static func tile(mname: String) -> Vector2:
	return TILE.get(mname, Vector2(2, 2))


## Standard world material for texture 'mname'. Vertex colours tint it.
static func get_mat(mname: String) -> StandardMaterial3D:
	if _cache.has(mname):
		return _cache[mname]
	var m := StandardMaterial3D.new()
	m.albedo_texture = tex(mname)
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
	m.vertex_color_use_as_albedo = true
	m.roughness = 1.0
	m.metallic_specular = 0.0
	if mname in ALPHA_CUT:
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA_SCISSOR
		m.alpha_scissor_threshold = 0.5
		m.cull_mode = BaseMaterial3D.CULL_DISABLED
	elif mname == "glass":
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		m.cull_mode = BaseMaterial3D.CULL_DISABLED
	elif mname.begins_with("sign_"):
		m.emission_enabled = true
		m.emission_texture = tex(mname)
		m.emission_energy_multiplier = 0.25
	_cache[mname] = m
	return m


## Atlas material for the Blender-built models. 'glow' keeps first-person
## models faintly readable in pitch darkness.
static func atlas(tname: String, glow: float = 0.0) -> StandardMaterial3D:
	var key := "%s@%.2f" % [tname, glow]
	if _cache.has(key):
		return _cache[key]
	var m := StandardMaterial3D.new()
	m.albedo_texture = tex(tname)
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
	m.roughness = 1.0
	m.metallic_specular = 0.0
	if glow > 0.0:
		m.emission_enabled = true
		m.emission_texture = tex(tname)
		m.emission_energy_multiplier = glow
	_cache[key] = m
	return m


static func unshaded(tname: String, additive: bool = false, color: Color = Color.WHITE) -> StandardMaterial3D:
	var key := "u:%s:%s:%s" % [tname, additive, color]
	if _cache.has(key):
		return _cache[key]
	var m := StandardMaterial3D.new()
	m.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	if tname != "":
		m.albedo_texture = tex(tname)
	m.albedo_color = color
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST
	m.cull_mode = BaseMaterial3D.CULL_DISABLED
	m.disable_fog = additive
	if additive:
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
		m.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	elif tname != "":
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA_SCISSOR
	_cache[key] = m
	return m


## Applies an atlas material to every mesh under 'node' and sets render layers.
static func skin(node: Node, m: Material, layers: int = 1, shadows: bool = true) -> void:
	if node is MeshInstance3D:
		node.material_override = m
		node.layers = layers
		node.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_ON if shadows else GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	for c in node.get_children():
		skin(c, m, layers, shadows)


static func set_layers(node: Node, layers: int) -> void:
	if node is VisualInstance3D:
		node.layers = layers
	for c in node.get_children():
		set_layers(c, layers)

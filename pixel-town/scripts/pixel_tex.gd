class_name PixelTex
## Small procedural pixel-art textures (16x16) and nearest-filtered materials.
## Generated with fixed seeds, so they look the same every run.

const SIZE := 16
static var _cache := {}


static func material(tex_name: String, uv_scale := Vector3.ONE, triplanar := false) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_texture = texture(tex_name)
	m.texture_filter = BaseMaterial3D.TEXTURE_FILTER_NEAREST_WITH_MIPMAPS
	m.roughness = 1.0
	m.uv1_scale = uv_scale
	m.uv1_triplanar = triplanar
	m.uv1_world_triplanar = triplanar
	return m


static func texture(tex_name: String) -> ImageTexture:
	if not _cache.has(tex_name):
		var img: Image
		match tex_name:
			"grass": img = _grass()
			"farm_grass": img = _farm_grass()
			"path": img = _path()
			"soil_dry": img = _soil_dry()
			"soil_wet": img = _soil_wet()
			"wood": img = _wood()
			"wood_dark": img = _wood_dark()
			"floor": img = _floor()
			"roof": img = _roof()
			_: push_error("Unknown pixel texture: " + tex_name)
		img.generate_mipmaps()
		_cache[tex_name] = ImageTexture.create_from_image(img)
	return _cache[tex_name]


static func _rng(seed_name: String) -> RandomNumberGenerator:
	var r := RandomNumberGenerator.new()
	r.seed = hash(seed_name)
	return r


static func _speckle(base: Color, shades: Array, chances: Array, seed_name: String, width := SIZE) -> Image:
	var img := Image.create(width, SIZE, false, Image.FORMAT_RGBA8)
	var r := _rng(seed_name)
	for y in SIZE:
		for x in width:
			var c := base
			var roll := r.randf()
			var acc := 0.0
			for i in shades.size():
				acc += chances[i]
				if roll < acc:
					c = shades[i]
					break
			img.set_pixel(x, y, c)
	return img


static func _grass() -> Image:
	var img := _speckle(Color("8fcf6f"), [Color("7fc063"), Color("a3db7c"), Color("76b85d")],
		[0.25, 0.12, 0.06], "grass")
	var r := _rng("grass-blades")
	for i in 7:                                   # little 2px blades
		var x := r.randi_range(0, SIZE - 1)
		var y := r.randi_range(1, SIZE - 1)
		img.set_pixel(x, y, Color("b8e68c"))
		img.set_pixel(x, y - 1, Color("b8e68c"))
	return img


static func _farm_grass() -> Image:
	return _speckle(Color("9ccd72"), [Color("8cbf66"), Color("aed882"), Color("b9a06a")],
		[0.25, 0.1, 0.05], "farm_grass")


static func _path() -> Image:
	var img := _speckle(Color("e2c99a"), [Color("d6ba88"), Color("ead7ae"), Color("c9ab7a")],
		[0.3, 0.12, 0.05], "path")
	var r := _rng("pebbles")
	for i in 3:
		var x := r.randi_range(0, SIZE - 2)
		var y := r.randi_range(0, SIZE - 2)
		img.set_pixel(x, y, Color("b9b0a4"))
		img.set_pixel(x + 1, y, Color("cfc7bb"))
	return img


static func _soil(base: Color, dark: Color, light: Color, seed_name: String) -> Image:
	var img := _speckle(base, [dark, light], [0.2, 0.1], seed_name)
	for y in range(3, SIZE, 4):                   # furrows
		for x in SIZE:
			img.set_pixel(x, y, dark)
	return img


static func _soil_dry() -> Image:
	return _soil(Color("a87650"), Color("8c5f3e"), Color("bb8a62"), "soil")


static func _soil_wet() -> Image:
	var img := _soil(Color("6e4a35"), Color("5a3b2a"), Color("7d5741"), "soil_wet")
	var r := _rng("shine")
	for i in 5:
		img.set_pixel(r.randi_range(0, SIZE - 1), r.randi_range(0, SIZE - 1), Color("9a7a66"))
	return img


## 32x16: four rows of long boards. Use uv scale (0.5, 1, 0.5) with triplanar
## mapping to keep the pixels square on walls.
static func _planks(base: Color, seam: Color, light: Color, seed_name: String) -> Image:
	var w := SIZE * 2
	var img := _speckle(base, [light, base.darkened(0.06)], [0.08, 0.15], seed_name, w)
	var r := _rng(seed_name + "-seams")
	for row in 4:
		var y0 := row * 4
		for x in w:
			img.set_pixel(x, y0 + 3, seam)
		var cut := (r.randi_range(3, 12) + row * 11) % w
		for y in range(y0, y0 + 3):
			img.set_pixel(cut, y, seam)
		img.set_pixel((cut + 7) % w, y0 + 1, base.darkened(0.15))     # a knot
	return img


static func _wood() -> Image:
	return _planks(Color("d39a64"), Color("9a6a43"), Color("e2ae7a"), "wood")


static func _wood_dark() -> Image:
	return _planks(Color("8a5a3a"), Color("5f3d27"), Color("9c6a47"), "wood_dark")


static func _floor() -> Image:
	return _planks(Color("c08c5c"), Color("8c6240"), Color("cf9d6c"), "floor")


static func _roof() -> Image:
	var img := Image.create(SIZE, SIZE, false, Image.FORMAT_RGBA8)
	var base := Color("e07a64")
	var shade := Color("c4604e")
	var light := Color("ef9580")
	for y in SIZE:
		for x in SIZE:
			var row := floori(y / 4.0)
			var lx := (x + (row % 2) * 2) % 4
			var c := base
			if y % 4 == 3 or lx == 0:
				c = shade
			elif y % 4 == 0:
				c = light
			img.set_pixel(x, y, c)
	return img

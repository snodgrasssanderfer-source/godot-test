"""Modelling helpers for the town's lots; build_*.py in this folder use them.

Everything is given in LOT SPACE, the numbers fps/world/town/town_layout.gd
uses: x along the street, y up, z into the lot, metres. finish() turns that
into Blender's axes (x, -z, y), so the exported .glb sits right when it is made
a child of the lot's node in the game.

Materials go by name (docs/blender_town_workflow.md):
  texture_set("red_brick", 2.0)   a set installed in fps/assets/materials; faces
                                  get UVs in metres / 2.0, projected along the
                                  face's main axis, so the pattern runs on
                                  across neighbouring pieces
  flat("glass", ...)              a plain material that travels inside the .glb
Faces that show one piece of an image (signs, packaging) are mapped by hand
with a rectangle given the way an image editor shows it: (x0, y0, x1, y1),
0..1, y down.
"""
import math
import os

import bmesh
import bpy
from mathutils import Matrix, Vector

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
LIBRARY = [os.path.join(ROOT, "textures", "materials"), os.path.join(ROOT, "textures", "generated"),
		os.path.join(ROOT, "fps", "assets", "textures")]

TILE = {}        # material name -> metres one repeat covers
DECALS = set()   # materials that are see-through where their image is (stains, cracks, painted lines)
QUARTERS = [(0.0, 0.0, 0.5, 0.5), (0.5, 0.0, 1.0, 0.5), (0.0, 0.5, 0.5, 1.0), (0.5, 0.5, 1.0, 1.0)]   # of a sheet of four variants
FLAT = {}        # material name -> settings
_light_count = {}

UP = Vector((0.0, 1.0, 0.0))
AXES = {"-x": Vector((-1, 0, 0)), "+x": Vector((1, 0, 0)), "-y": Vector((0, -1, 0)),
		"+y": Vector((0, 1, 0)), "-z": Vector((0, 0, -1)), "+z": Vector((0, 0, 1))}
BOX_FACES = {"-x": (0, 4, 6, 2), "+x": (1, 3, 7, 5), "-y": (0, 1, 5, 4),
		"+y": (2, 6, 7, 3), "-z": (0, 2, 3, 1), "+z": (4, 5, 7, 6)}


def texture_set(name, metres=2.0):
	TILE[name] = metres


def decal_set(*names):
	"""Images laid on as see-through panels. Installed with tools/install_texture.py <name>+alpha."""
	DECALS.update(names)


def flat(name, color, rough=0.6, metal=0.0, emit=None, strength=0.0, alpha=1.0):
	FLAT[name] = dict(color=color, rough=rough, metal=metal, emit=emit, strength=strength, alpha=alpha)


def frame(origin, yaw_deg=0.0):
	"""A local frame for furniture: built with its front towards local +z, then
	turned about the vertical (90 = front towards lot +x) and put at `origin`."""
	return Matrix.Translation(Vector(origin)) @ Matrix.Rotation(math.radians(yaw_deg), 4, "Y")


def turn(axis, deg, pivot):
	"""Rotation about an axis ("x", "y", "z" or a vector) through `pivot`."""
	ax = {"x": Vector((1, 0, 0)), "y": Vector((0, 1, 0)), "z": Vector((0, 0, 1))}.get(axis, axis)
	p = Vector(pivot)
	return Matrix.Translation(p) @ Matrix.Rotation(math.radians(deg), 4, Vector(ax)) @ Matrix.Translation(-p)


def reset_scene():
	bpy.ops.wm.read_factory_settings(use_empty=True)


def _image(name):
	for lib in LIBRARY:
		for ch in ("Color", "Emission"):
			for ext in (".jpg", ".png"):
				p = os.path.join(lib, name, "%s_%s%s" % (name, ch, ext))
				if os.path.exists(p):
					return p
	# one decal out of a set of variants, or a script-made one
	for lib in LIBRARY + [os.path.join(ROOT, "textures", "procedural")]:
		for d, _, files in os.walk(lib):
			if name + "_Color.png" in files:
				return os.path.join(d, name + "_Color.png")
	return None


def material(name):
	mat = bpy.data.materials.get(name)
	if mat is not None:
		return mat
	mat = bpy.data.materials.new(name)
	mat.use_nodes = True
	bsdf = mat.node_tree.nodes["Principled BSDF"]
	if name in FLAT:
		s = FLAT[name]
		c = s["color"]
		bsdf.inputs["Base Color"].default_value = (c[0], c[1], c[2], 1.0)
		bsdf.inputs["Roughness"].default_value = s["rough"]
		bsdf.inputs["Metallic"].default_value = s["metal"]
		if s["emit"] is not None:
			e = s["emit"]
			bsdf.inputs["Emission Color"].default_value = (e[0], e[1], e[2], 1.0)
			bsdf.inputs["Emission Strength"].default_value = s["strength"]
		if s["alpha"] < 1.0:
			bsdf.inputs["Alpha"].default_value = s["alpha"]
			mat.surface_render_method = "BLENDED"
		mat.diffuse_color = (c[0], c[1], c[2], s["alpha"])
	else:
		# A set from the library: the game swaps in the real material by name.
		# Here the colour map is wired up so the .blend shows something close.
		path = _image(name) or _image(name.rsplit("_", 1)[0])          # "<set>_pale": a variant of <set>
		if path is not None:
			tex = mat.node_tree.nodes.new("ShaderNodeTexImage")
			tex.image = bpy.data.images.load(path, check_existing=True)
			tex.location = (-360, 300)
			mat.node_tree.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
			if name in DECALS:
				mat.node_tree.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
				mat.surface_render_method = "BLENDED"
			if "_Emission" in path:
				mat.node_tree.links.new(tex.outputs["Color"], bsdf.inputs["Emission Color"])
				bsdf.inputs["Emission Strength"].default_value = 1.5
	return mat


def light(kind, at):
	"""An empty the game turns into a lamp: light_<kind>_<n>. The kinds and what
	they give are listed in fps/world/town/town.gd (MODEL_LIGHTS)."""
	_light_count[kind] = _light_count.get(kind, 0) + 1
	obj = bpy.data.objects.new("light_%s_%d" % (kind, _light_count[kind]), None)
	obj.empty_display_type = "SPHERE"
	obj.empty_display_size = 0.25
	obj.location = (at[0], -at[2], at[1])
	bpy.context.scene.collection.objects.link(obj)
	return obj


class Part:
	"""One object of the model. Its name decides how the game treats it:
	"...-nocol" is only seen, "...-colonly" is only bumped into."""

	def __init__(self, name):
		self.name = name
		self.bm = bmesh.new()
		self.uv = self.bm.loops.layers.uv.new("UVMap")
		self.fixed = self.bm.faces.layers.int.new("fixed")
		self.mats = []

	# --- plumbing ---

	def _mi(self, mat):
		if mat not in self.mats:
			self.mats.append(mat)
		return self.mats.index(mat)

	def _v(self, p, m=None):
		v = Vector(p)
		return self.bm.verts.new(m @ v if m is not None else v)

	def _face(self, verts, mat, smooth=False):
		f = self.bm.faces.new(verts)
		f.material_index = self._mi(mat)
		f.smooth = smooth
		return f

	def map_rect(self, face, centre, right, up, w, h, rect):
		x0, y0, x1, y1 = rect
		for loop in face.loops:
			d = loop.vert.co - centre
			u = d.dot(right) / w + 0.5
			v = d.dot(up) / h + 0.5
			loop[self.uv].uv = (x0 + u * (x1 - x0), 1.0 - (y1 - v * (y1 - y0)))
		face[self.fixed] = 1

	def map_point(self, face, rect):
		"""The whole face takes the colour in the middle of `rect`."""
		at = ((rect[0] + rect[2]) * 0.5, 1.0 - (rect[1] + rect[3]) * 0.5)
		for loop in face.loops:
			loop[self.uv].uv = at
		face[self.fixed] = 1

	# --- shapes ---

	def box(self, mat, a, b, bevel=0.0, m=None, faces=None, skip=""):
		"""Box between two corners. `faces` gives single faces another material
		({"-z": "glass"}), `skip` leaves faces out ("-y +y"). Returns the faces by
		side, or None after a bevel."""
		lo = [min(a[i], b[i]) for i in range(3)]
		hi = [max(a[i], b[i]) for i in range(3)]
		vs = [self._v((hi[0] if i & 1 else lo[0], hi[1] if i & 2 else lo[1], hi[2] if i & 4 else lo[2]), m)
				for i in range(8)]
		left_out = skip.split()
		made = {}
		for key, idx in BOX_FACES.items():
			if key in left_out:
				continue
			made[key] = self._face([vs[i] for i in idx], (faces or {}).get(key, mat))
		for v in vs:
			if not v.link_faces:
				self.bm.verts.remove(v)
		if bevel > 0.0 and not left_out:
			edges = list({e for f in made.values() for e in f.edges})
			bmesh.ops.bevel(self.bm, geom=edges, offset=bevel, segments=1, affect="EDGES", profile=0.5)
			return None
		return made

	def image_box(self, mat, a, b, side, rect, m=None):
		"""A box whose `side` shows `rect` of an image; the other sides take a flat
		colour sampled from the middle of it."""
		made = self.box(mat, a, b, m=m)
		n = AXES[side]
		up = Vector((0, 0, -1)) if side in ("+y", "-y") else UP
		right = up.cross(n)
		size = [abs(b[i] - a[i]) for i in range(3)]
		w = abs(right.x) * size[0] + abs(right.y) * size[1] + abs(right.z) * size[2]
		h = abs(up.x) * size[0] + abs(up.y) * size[1] + abs(up.z) * size[2]
		if m is not None:
			right = m.to_3x3() @ right
			up = m.to_3x3() @ up
		for key, f in made.items():
			if key == side:
				self.map_rect(f, f.calc_center_median(), right, up, w, h, rect)
			else:
				self.map_point(f, rect)

	def quad(self, mat, centre, normal, w, h, rect=None, up=(0, 1, 0), m=None):
		"""A one-sided panel seen from where `normal` points. With `rect` it shows
		that piece of the material's image."""
		n = Vector(normal).normalized()
		u = Vector(up).normalized()
		r = u.cross(n)
		c = Vector(centre)
		pts = [c - r * w / 2 - u * h / 2, c + r * w / 2 - u * h / 2, c + r * w / 2 + u * h / 2, c - r * w / 2 + u * h / 2]
		f = self._face([self._v(p, m) for p in pts], mat)
		if rect is not None:
			if m is not None:
				c = m @ c
				r = m.to_3x3() @ r
				u = m.to_3x3() @ u
			self.map_rect(f, c, r, u, w, h, rect)
		return f

	def decal(self, mat, at, normal, w, h, turn_deg=0.0, rect=None, lift=0.004, m=None):
		"""A stain, crack or painted mark: a see-through panel lying `lift` off the
		surface whose outward direction is `normal`, turned about that direction.
		On a floor (normal up) the image's top points to +z before turning; on a
		wall it points up."""
		n = Vector(normal).normalized()
		up = Vector((0, 0, 1)) if abs(n.y) > 0.9 else Vector((0, 1, 0))
		up = Matrix.Rotation(math.radians(turn_deg), 3, n) @ up
		return self.quad(mat, Vector(at) + n * lift, n, w, h, rect=rect or (0.0, 0.0, 1.0, 1.0), up=up, m=m)

	def cyl(self, mat, a, b, r, seg=10, r2=None, m=None, cap_mat=None):
		"""Cylinder (or cone, with r2) from point a to point b."""
		a = Vector(a)
		b = Vector(b)
		axis = (b - a).normalized()
		ref = UP if abs(axis.y) < 0.9 else Vector((1, 0, 0))
		u = axis.cross(ref).normalized()
		v = axis.cross(u)

		def ring(c, rad):
			return [self._v(c + (u * math.cos(2 * math.pi * i / seg) + v * math.sin(2 * math.pi * i / seg)) * rad, m)
					for i in range(seg)]
		ra = ring(a, r)
		rb = ring(b, r if r2 is None else r2)
		for i in range(seg):
			j = (i + 1) % seg
			self._face([ra[i], ra[j], rb[j], rb[i]], mat, True)
		self._face(list(reversed(ra)), cap_mat or mat)
		self._face(rb, cap_mat or mat)

	def tube(self, mat, pts, r, seg=6, steps=4, m=None):
		"""A round hose or pipe through the points, smoothed."""
		p = [Vector(q) for q in pts]
		path = []
		for i in range(len(p) - 1):
			p0 = p[max(i - 1, 0)]
			p1 = p[i]
			p2 = p[i + 1]
			p3 = p[min(i + 2, len(p) - 1)]
			for k in range(steps):
				t = k / steps
				path.append(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t
						+ (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t))
		path.append(p[-1])
		rings = []
		u = None
		for i, c in enumerate(path):
			t = (path[min(i + 1, len(path) - 1)] - path[max(i - 1, 0)]).normalized()
			if u is None:
				ref = UP if abs(t.y) < 0.9 else Vector((1, 0, 0))
				u = t.cross(ref).normalized()
			else:
				u = (u - t * u.dot(t)).normalized()
			v = t.cross(u)
			rings.append([self._v(c + (u * math.cos(2 * math.pi * k / seg) + v * math.sin(2 * math.pi * k / seg)) * r, m)
					for k in range(seg)])
		for ra, rb in zip(rings, rings[1:]):
			for i in range(seg):
				j = (i + 1) % seg
				self._face([ra[i], ra[j], rb[j], rb[i]], mat, True)
		self._face(list(reversed(rings[0])), mat)
		self._face(rings[-1], mat)

	def prism(self, mat, pts, axis, lo, hi, m=None, cap_mat=None, smooth=False):
		"""A flat outline pushed along an axis. The outline's points are (x, y)
		for axis "z", (z, y) for axis "x", (x, z) for axis "y"."""
		def at(p, t):
			if axis == "z":
				return (p[0], p[1], t)
			if axis == "x":
				return (t, p[1], p[0])
			return (p[0], t, p[1])
		ra = [self._v(at(p, lo), m) for p in pts]
		rb = [self._v(at(p, hi), m) for p in pts]
		made = [self._face(list(reversed(ra)), cap_mat or mat), self._face(rb, cap_mat or mat)]
		for i in range(len(pts)):
			j = (i + 1) % len(pts)
			made.append(self._face([ra[i], ra[j], rb[j], rb[i]], mat, smooth))
		bmesh.ops.recalc_face_normals(self.bm, faces=made)
		return made

	def hull(self, mat, bottom, top, faces=None, m=None):
		"""A tapered block: `bottom` and `top` are (x0, y, z0, x1, z1). `faces`
		like box()."""
		x0, y0, z0, x1, z1 = bottom
		a0, y1, c0, a1, c1 = top
		vs = [self._v(p, m) for p in ((x0, y0, z0), (x1, y0, z0), (a0, y1, c0), (a1, y1, c0),
				(x0, y0, z1), (x1, y0, z1), (a0, y1, c1), (a1, y1, c1))]
		for key, idx in BOX_FACES.items():
			self._face([vs[i] for i in idx], (faces or {}).get(key, mat))

	def blob(self, mat, centre, size, subdivisions=1, yaw=0.0):
		"""A lumpy rounded shape (a full bin bag)."""
		m = Matrix.Translation(Vector(centre)) @ Matrix.Rotation(math.radians(yaw), 4, "Y") @ Matrix.Diagonal(
				(size[0] / 2, size[1] / 2, size[2] / 2, 1.0))
		made = bmesh.ops.create_icosphere(self.bm, subdivisions=subdivisions, radius=1.0, matrix=m)
		idx = self._mi(mat)
		for v in made["verts"]:
			for f in v.link_faces:
				f.material_index = idx
				f.smooth = True

	# --- done ---

	def finish(self):
		bm = self.bm
		bm.normal_update()
		for f in bm.faces:
			if f[self.fixed]:
				continue
			tile = TILE.get(self.mats[f.material_index], 1.0)
			n = f.normal
			ax = max(range(3), key=lambda i: abs(n[i]))
			for loop in f.loops:
				c = loop.vert.co
				uv = (c.x, c.z) if ax == 1 else ((c.z, c.y) if ax == 0 else (c.x, c.y))
				loop[self.uv].uv = (uv[0] / tile, uv[1] / tile)
		for v in bm.verts:
			v.co = (v.co.x, -v.co.z, v.co.y)
		mesh = bpy.data.meshes.new(self.name)
		bm.to_mesh(mesh)
		faces = len(bm.faces)
		bm.free()
		for name in self.mats:
			mesh.materials.append(material(name))
		obj = bpy.data.objects.new(self.name, mesh)
		bpy.context.scene.collection.objects.link(obj)
		print("PART %-28s %5d faces, %d materials" % (self.name, faces, len(self.mats)))
		return obj


def save(blend_path, glb_path):
	bpy.ops.wm.save_as_mainfile(filepath=blend_path)
	bpy.ops.file.make_paths_relative()
	bpy.ops.wm.save_mainfile()
	# No images in the .glb: the game has its own, VRAM-compressed, and finds them by material name.
	bpy.ops.export_scene.gltf(filepath=glb_path, export_format="GLB", export_apply=True, export_yup=True,
			export_cameras=False, export_lights=False, export_image_format="NONE")
	print("SAVED %s (%d KB), %s (%d KB)" % (os.path.relpath(blend_path, ROOT), os.path.getsize(blend_path) // 1024,
			os.path.relpath(glb_path, ROOT), os.path.getsize(glb_path) // 1024))

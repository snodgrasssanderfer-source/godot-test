"""Builds every 3D model used by RUSTNIGHT and exports them as .glb.

Usage:
  blender --background --factory-startup --python art/rustnight/build_models.py
  (add "-- --preview" to also render preview sheets into art/rustnight/preview/)

All models are low-poly box/prism assemblies UV-mapped onto 8x8-cell atlases
(rustnight/tex/weapons.png, zombie*.png, props*.png, written by gen_textures.py).
Materials are assigned in Godot, so the .glb files only carry geometry + UVs.

Conventions (Blender space): +Y is "forward", +Z up, +X right.  The glTF
exporter turns that into Godot's -Z forward / +Y up.
Gun models have their origin at the rear sight on the sight line, so aiming
down sights is simply "put the origin in front of the camera".
"""
import math
import os
import sys

import bmesh
import bpy
from mathutils import Euler, Vector

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "rustnight", "models")
TEX = os.path.join(ROOT, "rustnight", "tex")
PREVIEW_DIR = os.path.join(ROOT, "art", "rustnight", "preview")
PREVIEW = "--preview" in sys.argv
os.makedirs(OUT, exist_ok=True)

# atlas cells -------------------------------------------------------------------
GUNMETAL, POLY, WOOD, STEEL, SLEEVE, GLOVE, BRASS, RED = [(i, 0) for i in range(8)]
IRON, HANDSKIN, TAPE, WHITE = [(i, 1) for i in range(4)]
SKIN, FACE, HAIR, SHIRT, PANTS, SHOES, GORE, SHIRT_B = [(i, 0) for i in range(8)]
SKULL = (0, 1)
PAINT, CGLASS, TYRE, CHROME, BARK, NEEDLE, LAMPGLASS, RUST = [(i, 0) for i in range(8)]
STONE, LEAVES, HEADLIGHT, TAILLIGHT, DARKMETAL, GREENPAINT, SANDBAG, OLIVE = [(i, 1) for i in range(8)]


def uv_rect(cell):
    c, r = cell
    e = 0.004
    return (c / 8 + e, 1 - (r + 1) / 8 + e, (c + 1) / 8 - e, 1 - r / 8 - e)


class Part:
    """Accumulates boxes/prisms (given in model space) into one mesh object."""

    def __init__(self, name):
        self.name = name
        self.bm = bmesh.new()
        self.uv = self.bm.loops.layers.uv.new("UVMap")

    def _face(self, pts, cell):
        vs = [self.bm.verts.new(p) for p in pts]
        f = self.bm.faces.new(vs)
        u0, v0, u1, v1 = uv_rect(cell)
        if len(pts) == 4:
            uvs = ((u0, v0), (u1, v0), (u1, v1), (u0, v1))
        else:
            n = len(pts)
            uvs = [((u0 + u1) / 2 + math.cos(i / n * math.tau) * (u1 - u0) / 2,
                    (v0 + v1) / 2 + math.sin(i / n * math.tau) * (v1 - v0) / 2) for i in range(n)]
        for loop, uv in zip(f.loops, uvs):
            loop[self.uv].uv = uv

    def box(self, center, size, cell, rot=(0, 0, 0), taper=(1, 1), faces=None, skip=()):
        """Box centred at 'center'. taper scales the +Z face in x/y. 'faces'
        overrides the atlas cell per face (front/back/left/right/top/bottom)."""
        c = Vector(center)
        hx, hy, hz = size[0] / 2, size[1] / 2, size[2] / 2
        R = Euler([math.radians(a) for a in rot], "XYZ").to_matrix()

        def p(sx, sy, sz):
            tx, ty = (taper if sz > 0 else (1, 1))
            return c + R @ Vector((sx * hx * tx, sy * hy * ty, sz * hz))

        quads = {
            "front": (p(1, 1, -1), p(-1, 1, -1), p(-1, 1, 1), p(1, 1, 1)),
            "back": (p(-1, -1, -1), p(1, -1, -1), p(1, -1, 1), p(-1, -1, 1)),
            "right": (p(1, -1, -1), p(1, 1, -1), p(1, 1, 1), p(1, -1, 1)),
            "left": (p(-1, 1, -1), p(-1, -1, -1), p(-1, -1, 1), p(-1, 1, 1)),
            "top": (p(-1, -1, 1), p(1, -1, 1), p(1, 1, 1), p(-1, 1, 1)),
            "bottom": (p(-1, 1, -1), p(1, 1, -1), p(1, -1, -1), p(-1, -1, -1)),
        }
        for name, q in quads.items():
            if name in skip:
                continue
            self._face(q, (faces or {}).get(name, cell))
        return self

    def prism(self, base, length, r0, cell, r1=None, sides=8, rot=(0, 0, 0), cap_cell=None):
        """Prism/cone starting at 'base', extending 'length' along local +Z
        (rotated by rot). r1 = radius at the far end (0 makes a cone)."""
        if r1 is None:
            r1 = r0
        b = Vector(base)
        R = Euler([math.radians(a) for a in rot], "XYZ").to_matrix()
        ring0, ring1 = [], []
        for i in range(sides):
            a = (i + 0.5) / sides * math.tau
            ring0.append(b + R @ Vector((math.cos(a) * r0, math.sin(a) * r0, 0)))
            ring1.append(b + R @ Vector((math.cos(a) * r1, math.sin(a) * r1, length)))
        for i in range(sides):
            j = (i + 1) % sides
            if r1 > 1e-6:
                self._face((ring0[i], ring0[j], ring1[j], ring1[i]), cell)
            else:
                self._face((ring0[i], ring0[j], ring1[i]), cell)
        self._face(list(reversed(ring0)), cap_cell or cell)
        if r1 > 1e-6:
            self._face(ring1, cap_cell or cell)
        return self

    def rod(self, a, b, r, cell, sides=6, r1=None):
        """Prism between two points."""
        a, b = Vector(a), Vector(b)
        d = b - a
        e = d.to_track_quat("Z", "Y").to_euler("XYZ")
        return self.prism(a, d.length, r, cell, r1=r1, sides=sides, rot=[math.degrees(x) for x in e])

    def beam(self, a, b, w, h, cell, faces=None):
        """Box stretched between two points (local Y along a->b)."""
        a, b = Vector(a), Vector(b)
        d = b - a
        e = d.to_track_quat("Y", "Z").to_euler("XYZ")
        return self.box((a + b) / 2, (w, d.length, h), cell, rot=[math.degrees(x) for x in e], faces=faces)

    def finish(self, origin=(0, 0, 0), parent=None):
        o = Vector(origin)
        for v in self.bm.verts:
            v.co -= o
        me = bpy.data.meshes.new(self.name)
        self.bm.to_mesh(me)
        self.bm.free()
        ob = bpy.data.objects.new(self.name, me)
        bpy.context.scene.collection.objects.link(ob)
        ob["_origin"] = tuple(o)
        if parent is not None:
            ob.parent = parent
            ob.location = o - Vector(parent["_origin"])
        else:
            ob.location = o
        return ob


def empty(name, loc, parent=None):
    ob = bpy.data.objects.new(name, None)
    bpy.context.scene.collection.objects.link(ob)
    ob["_origin"] = tuple(loc)
    if parent is not None:
        ob.parent = parent
        ob.location = Vector(loc) - Vector(parent["_origin"])
    else:
        ob.location = loc
    return ob


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)


def export(name, atlas=None):
    if PREVIEW and atlas:
        render_preview(name, atlas)
    for ob in bpy.data.objects:
        for k in list(ob.keys()):
            del ob[k]
    bpy.ops.export_scene.gltf(
        filepath=os.path.join(OUT, name + ".glb"), export_format="GLB",
        export_materials="NONE", export_yup=True, export_apply=False,
        export_animations=False, export_cameras=False, export_lights=False)
    tris = sum(len(p.vertices) - 2 for o in bpy.data.objects if o.type == "MESH" for p in o.data.polygons)
    print("exported %-12s %5d tris" % (name, tris))


def render_preview(name, atlas):
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    sc = bpy.context.scene
    mat = bpy.data.materials.new("prev")
    mat.use_nodes = True
    nt = mat.node_tree
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = bpy.data.images.load(os.path.join(TEX, atlas + ".png"))
    tex.interpolation = "Closest"
    bsdf = nt.nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value = 0.9
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    lo, hi = Vector((1e9,) * 3), Vector((-1e9,) * 3)
    meshes = [o for o in bpy.data.objects if o.type == "MESH"]
    bpy.context.view_layer.update()
    for o in meshes:
        o.data.materials.append(mat)
        for v in o.data.vertices:
            w = o.matrix_world @ v.co
            lo = Vector(map(min, lo, w))
            hi = Vector(map(max, hi, w))
    c = (lo + hi) / 2
    rad = (hi - lo).length / 2
    cam_d = bpy.data.cameras.new("cam")
    cam_d.lens = 60
    cam = bpy.data.objects.new("cam", cam_d)
    sc.collection.objects.link(cam)
    cam.location = c + Vector((1.5, -2.0, 0.9)).normalized() * rad * 3.6
    cam.rotation_euler = (c - cam.location).to_track_quat("-Z", "Y").to_euler()
    sc.camera = cam
    sun_d = bpy.data.lights.new("sun", "SUN")
    sun_d.energy = 3.5
    sun = bpy.data.objects.new("sun", sun_d)
    sc.collection.objects.link(sun)
    sun.rotation_euler = (math.radians(50), 0, math.radians(30))
    w = bpy.data.worlds.new("w")
    w.use_nodes = True
    w.node_tree.nodes["Background"].inputs[0].default_value = (0.25, 0.26, 0.28, 1)
    sc.world = w
    sc.render.engine = "CYCLES"
    sc.cycles.samples = 8
    sc.cycles.device = "CPU"
    sc.render.resolution_x, sc.render.resolution_y = 480, 360
    sc.view_settings.view_transform = "Standard"
    sc.render.filepath = os.path.join(PREVIEW_DIR, name + ".png")
    bpy.ops.render.render(write_still=True)
    for o in (cam, sun):
        bpy.data.objects.remove(o)
    for o in meshes:
        o.data.materials.clear()


# --- first-person arms ----------------------------------------------------------
def arm(name, hand, elbow, parent, hand_size=(0.07, 0.085, 0.075)):
    p = Part(name)
    h, e = Vector(hand), Vector(elbow)
    p.box(h, hand_size, GLOVE)
    d = (e - h).normalized()
    p.beam(h + d * 0.035, e, 0.075, 0.08, SLEEVE)
    p.beam(h + d * 0.03, h + d * 0.07, 0.082, 0.087, TAPE)   # cuff
    return p.finish(hand, parent)


# --- weapons --------------------------------------------------------------------
def build_k45():
    reset()
    body = Part("body")
    body.box((0, 0.085, -0.041), (0.024, 0.17, 0.018), GUNMETAL)                 # frame/dust cover
    body.box((0, 0.012, -0.095), (0.027, 0.046, 0.105), WOOD, rot=(-13, 0, 0),
             faces={"front": GUNMETAL, "back": GUNMETAL, "bottom": GUNMETAL})    # grip w/ wooden panels
    body.box((0, 0.072, -0.058), (0.006, 0.012, 0.022), STEEL, rot=(20, 0, 0))   # trigger
    body.box((0, 0.075, -0.083), (0.008, 0.06, 0.004), GUNMETAL)                 # trigger guard bottom
    body.box((0, 0.104, -0.066), (0.008, 0.004, 0.036), GUNMETAL)                # trigger guard front
    body.box((0, -0.012, -0.03), (0.01, 0.014, 0.016), STEEL, rot=(-30, 0, 0))   # hammer
    body.box((0, -0.006, -0.055), (0.02, 0.03, 0.03), GUNMETAL)                  # beavertail
    body.prism((0, 0.17, -0.02), 0.022, 0.0065, STEEL, sides=6, rot=(-90, 0, 0))  # barrel tip
    body = body.finish()
    slide = Part("slide")
    slide.box((0, 0.085, -0.019), (0.027, 0.2, 0.027), GUNMETAL)
    slide.box((0, 0.02, -0.019), (0.0285, 0.035, 0.022), STEEL)                  # serrations
    slide.box((0.0135, 0.075, -0.014), (0.002, 0.04, 0.012), POLY)               # ejection port
    slide.box((-0.007, 0.0, -0.002), (0.005, 0.01, 0.008), POLY)
    slide.box((0.007, 0.0, -0.002), (0.005, 0.01, 0.008), POLY)
    slide.box((0, 0.172, -0.002), (0.004, 0.008, 0.007), WHITE)
    slide.finish((0, 0, 0), body)
    mag = Part("mag")
    mag.box((0, 0.012, -0.1), (0.02, 0.036, 0.11), STEEL, rot=(-13, 0, 0))
    mag.box((0, 0.0, -0.152), (0.026, 0.044, 0.008), POLY, rot=(-13, 0, 0))
    mag.finish((0, 0.012, -0.1), body)
    empty("muzzle", (0, 0.2, -0.02), body)
    empty("eject", (0.02, 0.075, -0.012), body)
    arm("arm_r", (0.004, 0.006, -0.1), (0.1, -0.4, -0.2), body, (0.062, 0.07, 0.09))
    arm("arm_l", (-0.028, 0.03, -0.112), (-0.24, -0.34, -0.24), body, (0.06, 0.075, 0.08))
    export("k45", "weapons")


def build_smg():
    reset()
    body = Part("body")
    body.box((0, 0.1, -0.05), (0.04, 0.3, 0.055), GUNMETAL)                      # receiver
    body.box((0, 0.2, -0.052), (0.046, 0.12, 0.05), POLY)                        # handguard
    body.prism((0, 0.25, -0.045), 0.09, 0.009, STEEL, sides=6, rot=(-90, 0, 0))  # barrel
    body.prism((0, 0.31, -0.045), 0.04, 0.013, GUNMETAL, sides=6, rot=(-90, 0, 0))
    body.box((0, 0.0, -0.105), (0.03, 0.04, 0.085), POLY, rot=(-14, 0, 0))       # grip
    body.box((0, 0.055, -0.083), (0.008, 0.07, 0.004), GUNMETAL)                 # trigger guard
    body.box((0, 0.045, -0.072), (0.006, 0.01, 0.018), STEEL)
    body.box((0, 0.0, -0.011), (0.03, 0.03, 0.022), GUNMETAL, faces={"back": POLY})   # rear sight block
    body.box((-0.008, -0.002, 0.0005), (0.005, 0.012, 0.009), POLY)
    body.box((0.008, -0.002, 0.0005), (0.005, 0.012, 0.009), POLY)
    body.box((0, 0.235, -0.012), (0.01, 0.02, 0.02), GUNMETAL)                   # front sight tower
    body.box((0, 0.235, 0.0), (0.003, 0.006, 0.008), WHITE)
    body.box((-0.014, 0.235, -0.004), (0.003, 0.02, 0.014), GUNMETAL)
    body.box((0.014, 0.235, -0.004), (0.003, 0.02, 0.014), GUNMETAL)
    for sx in (-1, 1):                                                           # wire stock
        body.box((sx * 0.02, -0.14, -0.04), (0.005, 0.2, 0.006), STEEL)
    body.box((0, -0.245, -0.06), (0.05, 0.012, 0.09), POLY)
    body = body.finish()
    bolt = Part("slide")
    bolt.box((0.022, 0.1, -0.04), (0.006, 0.06, 0.016), POLY)
    bolt.box((0.03, 0.125, -0.04), (0.012, 0.01, 0.01), STEEL)
    bolt.finish((0, 0, 0), body)
    mag = Part("mag")
    mag.box((0, 0.12, -0.15), (0.022, 0.032, 0.16), STEEL, rot=(12, 0, 0))
    mag.finish((0, 0.12, -0.1), body)
    empty("muzzle", (0, 0.36, -0.045), body)
    empty("eject", (0.026, 0.1, -0.035), body)
    arm("arm_r", (0.004, -0.006, -0.11), (0.11, -0.4, -0.2), body, (0.06, 0.07, 0.085))
    arm("arm_l", (-0.012, 0.2, -0.095), (-0.22, -0.2, -0.24), body, (0.07, 0.085, 0.06))
    export("smg", "weapons")


def build_ar():
    reset()
    body = Part("body")
    body.box((0, 0.04, -0.07), (0.045, 0.25, 0.07), GUNMETAL)                    # receiver
    body.box((0, 0.3, -0.066), (0.05, 0.27, 0.055), POLY)                        # handguard
    for y in (0.21, 0.27, 0.33, 0.39):
        body.box((0, y, -0.066), (0.052, 0.012, 0.03), GUNMETAL)                 # vents
    body.prism((0, 0.435, -0.062), 0.14, 0.008, STEEL, sides=6, rot=(-90, 0, 0))
    body.prism((0, 0.555, -0.062), 0.045, 0.012, GUNMETAL, sides=6, rot=(-90, 0, 0))
    body.box((0, -0.035, -0.14), (0.03, 0.042, 0.095), POLY, rot=(-18, 0, 0))    # grip
    body.box((0, 0.02, -0.112), (0.008, 0.07, 0.004), GUNMETAL)
    body.box((0, 0.012, -0.1), (0.006, 0.01, 0.018), STEEL)
    body.box((0, -0.15, -0.062), (0.03, 0.14, 0.03), GUNMETAL)                   # buffer tube
    body.box((0, -0.25, -0.085), (0.042, 0.2, 0.1), POLY, taper=(1, 1))          # stock
    body.box((0, -0.355, -0.085), (0.046, 0.012, 0.11), TAPE)
    # carry-handle style rear sight
    body.box((0, 0.03, -0.022), (0.022, 0.16, 0.014), GUNMETAL)
    body.box((0, -0.03, -0.028), (0.012, 0.02, 0.02), GUNMETAL)
    body.box((0, 0.09, -0.028), (0.012, 0.02, 0.02), GUNMETAL)
    body.box((-0.008, 0.0, -0.004), (0.005, 0.014, 0.022), POLY)
    body.box((0.008, 0.0, -0.004), (0.005, 0.014, 0.022), POLY)
    body.box((0, 0.0, -0.012), (0.012, 0.012, 0.006), POLY)
    # front sight A-frame
    body.box((0, 0.425, -0.02), (0.008, 0.012, 0.045), GUNMETAL)
    body.box((0, 0.44, -0.026), (0.008, 0.012, 0.04), GUNMETAL, rot=(25, 0, 0))
    body.box((0, 0.425, 0.0015), (0.003, 0.004, 0.009), WHITE)
    body.box((-0.009, 0.425, 0.0), (0.003, 0.012, 0.014), GUNMETAL)
    body.box((0.009, 0.425, 0.0), (0.003, 0.012, 0.014), GUNMETAL)
    body = body.finish()
    bolt = Part("slide")
    bolt.box((0.0235, 0.06, -0.058), (0.002, 0.055, 0.016), BRASS)               # bolt carrier seen through port
    bolt.box((0.03, 0.02, -0.05), (0.012, 0.012, 0.008), STEEL)
    bolt.finish((0, 0, 0), body)
    mag = Part("mag")
    mag.box((0, 0.09, -0.165), (0.024, 0.055, 0.1), STEEL, rot=(8, 0, 0))
    mag.box((0, 0.108, -0.24), (0.024, 0.055, 0.08), STEEL, rot=(20, 0, 0))
    mag.finish((0, 0.09, -0.12), body)
    empty("muzzle", (0, 0.61, -0.062), body)
    empty("eject", (0.028, 0.06, -0.055), body)
    arm("arm_r", (0.004, -0.04, -0.145), (0.12, -0.42, -0.24), body, (0.06, 0.07, 0.085))
    arm("arm_l", (-0.01, 0.3, -0.112), (-0.24, -0.12, -0.27), body, (0.075, 0.09, 0.06))
    export("ar", "weapons")


def build_shotgun():
    reset()
    body = Part("body")
    body.box((0, 0.06, -0.042), (0.044, 0.22, 0.06), GUNMETAL)                   # receiver
    body.prism((0, 0.17, -0.024), 0.44, 0.011, STEEL, sides=6, rot=(-90, 0, 0))  # barrel
    body.prism((0, 0.17, -0.05), 0.36, 0.011, GUNMETAL, sides=6, rot=(-90, 0, 0))  # mag tube
    body.box((0, 0.53, -0.037), (0.012, 0.012, 0.03), GUNMETAL)                  # barrel band
    body.box((0, 0.6, -0.006), (0.004, 0.006, 0.012), BRASS)                     # bead
    body.box((0, 0.0, -0.008), (0.012, 0.01, 0.008), GUNMETAL)                   # rear ridge (below the line)
    body.box((0, 0.025, -0.082), (0.008, 0.07, 0.004), GUNMETAL)
    body.box((0, 0.02, -0.072), (0.006, 0.01, 0.016), STEEL)
    body.box((0, -0.07, -0.075), (0.036, 0.07, 0.055), WOOD, rot=(-25, 0, 0))    # wrist
    body.beam((0, -0.08, -0.07), (0, -0.36, -0.11), 0.038, 0.1, WOOD)            # stock
    body.box((0, -0.365, -0.112), (0.042, 0.014, 0.11), TAPE, rot=(-8, 0, 0))
    body.box((0.0225, 0.07, -0.04), (0.002, 0.07, 0.02), POLY)                   # ejection port
    body = body.finish()
    pump = Part("pump")
    pump.box((0, 0.34, -0.052), (0.046, 0.16, 0.044), WOOD)
    for y in (0.29, 0.32, 0.35, 0.38):
        pump.box((0, y, -0.052), (0.048, 0.008, 0.036), IRON)
    pump = pump.finish((0, 0.34, -0.052), body)
    empty("muzzle", (0, 0.63, -0.024), body)
    empty("eject", (0.028, 0.07, -0.04), body)
    arm("arm_r", (0.004, -0.06, -0.09), (0.12, -0.42, -0.22), body, (0.06, 0.075, 0.085))
    arm("arm_l", (-0.008, 0.34, -0.09), (-0.24, -0.08, -0.27), pump, (0.075, 0.1, 0.06))
    export("shotgun", "weapons")


def build_crowbar():
    reset()
    body = Part("body")
    body.rod((0, 0, -0.12), (0, 0.02, 0.5), 0.011, IRON)
    body.rod((0, 0.02, 0.5), (0, 0.07, 0.56), 0.011, IRON)
    body.rod((0, 0.07, 0.56), (0, 0.13, 0.53), 0.011, IRON, r1=0.004)
    body.rod((0, 0, -0.12), (0, -0.03, -0.17), 0.011, IRON, r1=0.005)
    body.box((0, 0.004, 0.05), (0.026, 0.026, 0.12), RED)
    body = body.finish()
    arm("arm_r", (0, 0.0, 0.0), (0.1, -0.36, -0.14), body, (0.06, 0.065, 0.09))
    export("crowbar", "weapons")


def build_axe():
    reset()
    body = Part("body")
    body.box((0, 0, 0.22), (0.026, 0.038, 0.78), WOOD)
    body.box((0, 0.03, 0.56), (0.03, 0.12, 0.1), RED)
    body.box((0, 0.12, 0.56), (0.022, 0.07, 0.15), STEEL, rot=(0, 0, 0))
    body.box((0, 0.165, 0.56), (0.008, 0.03, 0.18), WHITE)
    body.box((0, -0.045, 0.56), (0.03, 0.03, 0.06), RED)
    body.box((0, 0, -0.16), (0.03, 0.044, 0.04), TAPE)
    body = body.finish()
    arm("arm_r", (0, 0.0, 0.0), (0.1, -0.36, -0.14), body, (0.06, 0.07, 0.09))
    arm("arm_l", (0, 0.0, 0.2), (-0.2, -0.3, -0.02), body, (0.06, 0.07, 0.09))
    export("axe", "weapons")


# --- zombie (rigid segments, animated in code) -----------------------------------
def build_zombie():
    reset()
    root = empty("zombie", (0, 0, 0))
    torso = Part("torso")
    torso.box((0, 0, 1.02), (0.34, 0.2, 0.2), PANTS)                             # pelvis
    torso.box((0, 0, 1.28), (0.34, 0.2, 0.36), SHIRT, taper=(1.16, 1.05), faces={"front": SHIRT_B})
    torso.box((0, 0, 1.485), (0.11, 0.1, 0.06), SKIN)                            # neck
    torso = torso.finish((0, 0, 0.95), root)
    head = Part("head")
    head.box((0, 0.01, 1.63), (0.2, 0.22, 0.24), SKIN, faces={"front": FACE, "top": HAIR, "back": HAIR})
    head.box((0, -0.02, 1.745), (0.21, 0.2, 0.03), HAIR)
    head.box((0, 0.09, 1.535), (0.14, 0.06, 0.06), SKIN, faces={"front": GORE, "bottom": GORE})   # hanging jaw
    head.finish((0, 0, 1.5), torso)
    stump = Part("stump")                                                        # shown after the head is blown off
    stump.box((0, 0, 1.52), (0.1, 0.09, 0.03), GORE)
    stump.finish((0, 0, 1.5), torso)
    for s, sx in (("l", -1), ("r", 1)):
        ua = Part("uarm_" + s)
        ua.box((sx * 0.245, 0, 1.3), (0.1, 0.11, 0.3), SHIRT)
        ua = ua.finish((sx * 0.245, 0, 1.42), torso)
        fa = Part("farm_" + s)
        fa.box((sx * 0.245, 0, 1.02), (0.085, 0.09, 0.28), SKIN)
        fa.box((sx * 0.245, 0.005, 0.84), (0.09, 0.07, 0.1), SKIN, faces={"bottom": GORE})
        fa.finish((sx * 0.245, 0, 1.15), ua)
        th = Part("thigh_" + s)
        th.box((sx * 0.095, 0, 0.72), (0.15, 0.16, 0.46), PANTS)
        th = th.finish((sx * 0.095, 0, 0.93), root)
        sh = Part("shin_" + s)
        sh.box((sx * 0.095, 0, 0.3), (0.125, 0.135, 0.42), PANTS)
        sh.box((sx * 0.095, 0.045, 0.045), (0.13, 0.26, 0.09), SHOES)
        sh.finish((sx * 0.095, 0, 0.5), th)
    export("zombie", "zombie0")


# --- props ------------------------------------------------------------------------
def build_car():
    reset()
    p = Part("car")
    p.box((0, 0, 0.56), (1.76, 4.3, 0.5), PAINT, faces={"bottom": DARKMETAL})
    p.box((0, -0.25, 1.06), (1.62, 2.3, 0.5), CGLASS, taper=(0.86, 0.72), faces={"top": PAINT})
    p.box((0, 2.16, 0.42), (1.8, 0.1, 0.16), CHROME)
    p.box((0, -2.16, 0.42), (1.8, 0.1, 0.16), CHROME)
    for sx in (-1, 1):
        p.box((sx * 0.62, 2.155, 0.66), (0.34, 0.02, 0.14), HEADLIGHT)
        p.box((sx * 0.64, -2.155, 0.68), (0.36, 0.02, 0.12), TAILLIGHT)
        for sy in (-1, 1):
            p.prism((sx * 0.9 - sx * 0.24, sy * 1.4, 0.32), 0.24, 0.32, TYRE, sides=8, rot=(0, sx * 90, 0), cap_cell=CHROME)
    p.box((0, 2.158, 0.56), (0.7, 0.02, 0.12), DARKMETAL)
    p.finish()
    export("car", "props0")


def build_truck():
    reset()
    p = Part("truck")
    p.box((0, 2.2, 1.45), (2.2, 1.7, 1.6), OLIVE, faces={"front": OLIVE})
    p.box((0, 2.75, 1.85), (2.0, 0.62, 0.6), CGLASS, faces={"top": OLIVE, "back": OLIVE, "bottom": OLIVE})
    p.box((0, -0.9, 1.0), (2.3, 4.4, 0.3), DARKMETAL)
    p.box((0, -0.9, 2.0), (2.3, 4.4, 1.7), SANDBAG, taper=(0.9, 1.0), skip=("back",))   # canvas cover, open at the back
    p.box((0, 3.08, 0.75), (2.2, 0.14, 0.3), DARKMETAL)
    for sx in (-1, 1):
        p.box((sx * 0.8, 3.06, 1.05), (0.3, 0.03, 0.2), HEADLIGHT)
        for y in (2.2, -1.2, -2.3):
            p.prism((sx * 1.1 - sx * 0.3, y, 0.5), 0.3, 0.5, TYRE, sides=8, rot=(0, sx * 90, 0), cap_cell=DARKMETAL)
    p.finish()
    export("truck", "props0")


def build_pine():
    reset()
    p = Part("pine")
    p.prism((0, 0, 0), 2.0, 0.14, BARK, r1=0.1, sides=6)
    p.prism((0, 0, 1.3), 2.2, 1.5, NEEDLE, r1=0.0, sides=6)
    p.prism((0, 0, 2.6), 2.0, 1.15, NEEDLE, r1=0.0, sides=6)
    p.prism((0, 0, 3.8), 1.9, 0.8, NEEDLE, r1=0.0, sides=6)
    p.finish()
    export("pine", "props0")


def build_deadtree():
    reset()
    p = Part("deadtree")
    p.prism((0, 0, 0), 2.6, 0.17, BARK, r1=0.1, sides=6)
    p.rod((0, 0, 2.5), (0.5, 0.2, 3.9), 0.09, BARK, r1=0.03)
    p.rod((0, 0, 2.3), (-0.8, -0.3, 3.4), 0.08, BARK, r1=0.03)
    p.rod((0, 0, 1.8), (0.3, -0.9, 2.9), 0.06, BARK, r1=0.02)
    p.rod((0, 0, 2.0), (-0.2, 0.9, 3.2), 0.06, BARK, r1=0.02)
    p.rod((0.35, 0.14, 3.5), (1.0, -0.2, 4.2), 0.04, BARK, r1=0.01)
    p.rod((-0.55, -0.2, 3.05), (-1.1, 0.4, 3.9), 0.04, BARK, r1=0.01)
    p.finish()
    export("deadtree", "props0")


def build_lamp():
    reset()
    p = Part("lamp")
    p.prism((0, 0, 0), 0.5, 0.11, DARKMETAL, sides=6)
    p.prism((0, 0, 0.5), 4.5, 0.06, DARKMETAL, r1=0.05, sides=6)
    p.beam((0, -0.05, 4.95), (0, 1.25, 5.05), 0.07, 0.07, DARKMETAL)
    p.box((0, 1.1, 4.97), (0.26, 0.55, 0.1), DARKMETAL, faces={"bottom": LAMPGLASS})
    p.finish()
    export("lamp", "props0")


def build_small_props():
    reset()
    p = Part("gravestone")
    p.box((0, 0, 0.45), (0.6, 0.14, 0.9), STONE, taper=(0.8, 1.0))
    p.box((0, 0, 0.04), (0.8, 0.3, 0.08), STONE)
    p.finish()
    export("gravestone", "props0")

    reset()
    p = Part("cross")
    p.box((0, 0, 0.7), (0.1, 0.1, 1.4), BARK)
    p.box((0, 0, 1.0), (0.7, 0.1, 0.1), BARK)
    p.finish()
    export("cross", "props0")

    reset()
    p = Part("barrel")
    p.prism((0, 0, 0), 0.9, 0.3, RUST, sides=8, cap_cell=DARKMETAL)
    p.prism((0, 0, 0.28), 0.04, 0.315, DARKMETAL, sides=8)
    p.prism((0, 0, 0.6), 0.04, 0.315, DARKMETAL, sides=8)
    p.finish()
    export("barrel", "props0")

    reset()
    p = Part("dumpster")
    p.box((0, 0, 0.6), (1.9, 0.95, 1.0), GREENPAINT, taper=(1.06, 1.1))
    p.box((0, -0.05, 1.14), (2.04, 1.0, 0.07), DARKMETAL, rot=(6, 0, 0))
    for sx in (-1, 1):
        p.box((sx * 0.8, 0, 0.06), (0.12, 0.7, 0.12), DARKMETAL)
        p.box((sx * 1.0, 0, 0.85), (0.08, 0.3, 0.08), DARKMETAL)
    p.finish()
    export("dumpster", "props0")

    reset()
    p = Part("bench")
    for i in range(3):
        p.box((0, -0.14 + i * 0.14, 0.45), (1.6, 0.11, 0.04), BARK)
    for i in range(2):
        p.box((0, 0.22, 0.62 + i * 0.16), (1.6, 0.04, 0.11), BARK, rot=(-8, 0, 0))
    for sx in (-1, 1):
        p.box((sx * 0.7, 0, 0.22), (0.06, 0.42, 0.44), DARKMETAL)
        p.box((sx * 0.7, 0.22, 0.6), (0.06, 0.05, 0.5), DARKMETAL, rot=(-8, 0, 0))
    p.finish()
    export("bench", "props0")

    reset()
    p = Part("sandbags")
    for row in range(4):
        n = 5 if row % 2 == 0 else 4
        for i in range(n):
            x = (i - (n - 1) / 2) * 0.5
            p.box((x, 0, 0.11 + row * 0.2), (0.48, 0.34, 0.2), SANDBAG, taper=(0.9, 0.85))
    p.finish()
    export("sandbags", "props0")

    reset()
    p = Part("pump")
    p.box((0, 0, 0.08), (0.9, 0.5, 0.16), STONE)
    p.box((0, 0, 0.85), (0.6, 0.36, 1.4), PAINT, faces={"front": CHROME, "back": CHROME})
    p.box((0, 0, 1.3), (0.5, 0.38, 0.3), DARKMETAL)
    p.box((0, 0, 1.62), (0.66, 0.4, 0.14), PAINT)
    p.rod((0.32, 0, 1.2), (0.36, 0, 0.5), 0.02, TYRE)
    p.finish()
    export("pump", "props0")

    reset()
    p = Part("fence")          # 2 m wooden picket section, origin at its centre on the ground
    for i in range(8):
        h = 1.1 + (0.06 if i % 3 == 0 else 0) - (0.3 if i == 5 else 0)
        p.box((-0.875 + i * 0.25, 0, h / 2), (0.19, 0.03, h), BARK, taper=(0.5, 1.0) if i != 5 else (1, 1))
    p.box((0, -0.03, 0.3), (2.0, 0.04, 0.08), BARK)
    p.box((0, -0.03, 0.85), (2.0, 0.04, 0.08), BARK)
    p.finish()
    export("fence", "props0")


def main():
    build_k45(); build_smg(); build_ar(); build_shotgun(); build_crowbar(); build_axe()
    build_zombie()
    build_car(); build_truck(); build_pine(); build_deadtree(); build_lamp(); build_small_props()
    print("models written to", OUT)


main()

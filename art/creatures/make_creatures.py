"""Creature models, generated from code. First one: the CRT television.

A boxy 90s set that sits on the ground like dumped furniture until it wakes,
then stands up on four legs of meat. Front (the screen) faces Godot's -Z, the
origin is on the ground under the middle of the casing.

Nodes the game drives:
  Body                      everything that lifts off the ground when it stands
  LegFL LegFR LegBL LegBR   hip pivots; scaled to zero while it sleeps
  Glass                     the dark tube face, hidden once it is shot out
  Hole, Meat                what is behind it, shown once it is shot out
  Screen                    marker at the centre of the tube face (the game puts
                            the lit picture there)

  blender --background --factory-startup --python art/creatures/make_creatures.py -- fps/assets/creatures
"""
import math
import sys

import bmesh
import bpy
from mathutils import Vector, noise

out_dir = sys.argv[sys.argv.index("--") + 1] if "--" in sys.argv else "fps/assets/creatures"
MAT = {}


def material(name, color, rough, metallic=0.0):
    m = bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except Exception:
        pass
    b = next(n for n in m.node_tree.nodes if n.type == 'BSDF_PRINCIPLED')
    b.inputs['Base Color'].default_value = (*color, 1)
    b.inputs['Roughness'].default_value = rough
    b.inputs['Metallic'].default_value = metallic
    return m


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    MAT.clear()
    MAT.update(
        casing=material("casing", (0.085, 0.055, 0.035), 0.55),         # brown wood-effect plastic
        casing_dark=material("casing_dark", (0.020, 0.018, 0.016), 0.6),
        glass=material("glass", (0.012, 0.016, 0.014), 0.06),
        void=material("void", (0.001, 0.001, 0.001), 1.0),
        chrome=material("chrome", (0.30, 0.30, 0.31), 0.35, 1.0),
        rubber=material("rubber", (0.008, 0.008, 0.008), 0.8),
        meat=material("meat", (0.30, 0.035, 0.030), 0.28),               # wet
        flesh=material("flesh", (0.38, 0.15, 0.12), 0.45),
        sinew=material("sinew", (0.52, 0.40, 0.32), 0.5),
    )


def node(name, loc=(0, 0, 0), rot=(0, 0, 0), parent=None):
    e = bpy.data.objects.new(name, None)
    e.location, e.rotation_euler, e.parent = loc, rot, parent
    bpy.context.scene.collection.objects.link(e)
    return e


def finish(bm, mat, loc, rot, parent, name):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    ob.location, ob.rotation_euler, ob.parent = loc, rot, parent
    me.materials.append(MAT[mat])
    bpy.context.scene.collection.objects.link(ob)
    return ob


def box(size, loc, mat, parent, rot=(0, 0, 0), bevel=0.0, taper_y=None, name="part"):
    """taper_y=(sx, sz): scale the +Y end of the box."""
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    for v in bm.verts:
        front = v.co.y > 0
        v.co.x *= size[0]
        v.co.y *= size[1]
        v.co.z *= size[2]
        if taper_y and front:
            v.co.x *= taper_y[0]
            v.co.z *= taper_y[1]
    if bevel:
        bmesh.ops.bevel(bm, geom=list(bm.edges), offset=bevel, segments=2, affect='EDGES', profile=0.5)
    return finish(bm, mat, loc, rot, parent, name)


def tube(r, length, loc, mat, parent, rot=(0, 0, 0), seg=12, r2=None, name="part"):
    """A cylinder along local Z, then rotated."""
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=seg,
                          radius1=r, radius2=r if r2 is None else r2, depth=length)
    for f in bm.faces:
        f.smooth = len(f.verts) == 4
    return finish(bm, mat, loc, rot, parent, name)


def lump(radius, scale, loc, mat, parent, lumpy=0.25, sub=3, name="part"):
    bm = bmesh.new()
    bmesh.ops.create_icosphere(bm, subdivisions=sub, radius=radius)
    for v in bm.verts:
        v.co *= 1.0 + lumpy * noise.noise(v.co * 9.0 + Vector(loc) * 3.0)
        v.co.x *= scale[0]
        v.co.y *= scale[1]
        v.co.z *= scale[2]
    for f in bm.faces:
        f.smooth = True
    return finish(bm, mat, loc, (0, 0, 0), parent, name)


def skin(points, radii, mat, parent, loc=(0, 0, 0), sub=2, name="part"):
    """A tube of meat through a chain of points (Skin modifier + subdivision)."""
    me = bpy.data.meshes.new(name)
    me.from_pydata(points, [(i, i + 1) for i in range(len(points) - 1)], [])
    me.update()
    ob = bpy.data.objects.new(name, me)
    ob.location, ob.parent = loc, parent
    me.materials.append(MAT[mat])
    bpy.context.scene.collection.objects.link(ob)
    sk = ob.modifiers.new("skin", 'SKIN')
    sk.use_smooth_shade = True
    for i, r in enumerate(radii):
        me.skin_vertices[0].data[i].radius = (r, r)
    me.skin_vertices[0].data[0].use_root = True
    s = ob.modifiers.new("sub", 'SUBSURF')
    s.levels = sub
    s.render_levels = sub
    return ob


def export(name):
    path = f"{out_dir}/{name}.glb"
    bpy.ops.export_scene.gltf(filepath=path, export_format='GLB', export_apply=True, export_yup=True,
                              export_texcoords=False, export_lights=False, export_cameras=False)
    print("EXPORTED", path)


def crt():
    reset()
    root = node("crt")
    body = node("Body", parent=root)
    # Casing: a deep front box and the tapering back that holds the tube's neck.
    box((0.64, 0.30, 0.50), (0, 0.13, 0.25), 'casing', body, bevel=0.012)
    box((0.58, 0.30, 0.46), (0, -0.17, 0.24), 'casing', body, rot=(0, 0, math.pi), bevel=0.012,
        taper_y=(0.62, 0.66))
    for i in range(6):       # cooling slots across the back of the top
        box((0.30, 0.012, 0.004), (0, -0.07 - i * 0.03, 0.478 - i * 0.008), 'void', body)
    # Front: dark fascia, the tube face left of centre, the controls on the right.
    box((0.60, 0.012, 0.46), (0, 0.286, 0.25), 'casing_dark', body, bevel=0.003)
    box((0.46, 0.010, 0.36), (-0.06, 0.290, 0.27), 'casing', body, bevel=0.004)          # bezel lip
    box((0.44, 0.010, 0.34), (-0.06, 0.294, 0.27), 'glass', body, bevel=0.012, name="Glass")
    hole = box((0.44, 0.006, 0.34), (-0.06, 0.288, 0.27), 'void', body, name="Hole")
    meat = lump(0.15, (1.25, 0.42, 0.95), (-0.06, 0.262, 0.27), 'meat', body, lumpy=0.45, name="Meat")
    for z in (0.40, 0.33):
        tube(0.022, 0.022, (0.235, 0.298, z), 'chrome', body, rot=(math.pi / 2, 0, 0), seg=14)
        box((0.004, 0.004, 0.030), (0.235, 0.310, z), 'casing_dark', body)
    for i in range(6):       # speaker slots
        box((0.075, 0.004, 0.006), (0.235, 0.293, 0.10 + i * 0.022), 'void', body)
    box((0.05, 0.004, 0.012), (0.235, 0.293, 0.26), 'chrome', body)
    # Rabbit ears.
    box((0.07, 0.05, 0.02), (0.05, -0.02, 0.51), 'casing_dark', body, bevel=0.004)
    for sx in (-1, 1):
        tube(0.0035, 0.46, (0.05 + sx * 0.115, -0.02, 0.715), 'chrome', body, rot=(0, sx * math.radians(30), 0), seg=6)
        tube(0.007, 0.012, (0.05 + sx * 0.232, -0.02, 0.918), 'chrome', body, seg=6)
    # What it is made of shows underneath and where the cord comes out.
    lump(0.16, (1.5, 1.3, 0.34), (0, 0.02, 0.0), 'meat', body, lumpy=0.4)
    lump(0.05, (1.0, 1.0, 0.9), (0.0, -0.325, 0.13), 'flesh', body, lumpy=0.3)
    skin([(0, -0.33, 0.13), (0.03, -0.46, 0.05), (-0.05, -0.66, 0.012), (0.03, -0.88, 0.012), (0.0, -1.02, 0.012)],
         [0.022, 0.016, 0.011, 0.009, 0.009], 'rubber', body, sub=1)
    box((0.03, 0.05, 0.02), (0.0, -1.05, 0.012), 'rubber', body, bevel=0.003)
    for sx in (-1, 1):
        tube(0.0025, 0.022, (sx * 0.007, -1.085, 0.012), 'chrome', body, rot=(math.pi / 2, 0, 0), seg=5)
    # Legs: hip, knee, ankle, foot. Each is its own pivot at the hip.
    for name, sx, sy in (("LegFL", -1, 1), ("LegFR", 1, 1), ("LegBL", -1, -1), ("LegBR", 1, -1)):
        hip = node(name, (sx * 0.24, sy * 0.19 + 0.02, 0.05), parent=body)
        pts = [(0, 0, 0.03), (sx * 0.15, sy * 0.03, -0.11), (sx * 0.10, sy * 0.01, -0.33),
               (sx * 0.12, 0.04, -0.435), (sx * 0.13, 0.15, -0.445)]
        skin(pts, [0.060, 0.045, 0.030, 0.034, 0.020], 'flesh', hip, sub=2)
        skin([(sx * 0.02, 0, 0.0), (sx * 0.17, sy * 0.035, -0.10), (sx * 0.115, sy * 0.012, -0.30)],
             [0.012, 0.014, 0.010], 'sinew', hip, sub=1)
    node("Screen", (-0.06, 0.300, 0.27), parent=body)
    export("crt")


crt()

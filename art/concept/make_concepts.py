"""Mood / tone concept renders for the survival-horror FPS.

Builds one procedural suburban street (and one safehouse interior) and renders
it under several lighting + fog setups so the overall tone can be chosen before
any production art is made. Everything is generated from code: no external
assets.

  blender --background --factory-startup --python art/concept/make_concepts.py -- \
      --variant A_cold_fog --out /tmp/a.png [--scale 50] [--samples 32]

Variants: A_cold_fog, B_rust_dusk, C_sodium_night, D_blackout, E_safehouse
Run art/concept/post.py on the result for grain / bloom / grade.
"""
import argparse
import math
import random
import sys

import bmesh
import bpy
from mathutils import Vector, noise

ap = argparse.ArgumentParser()
ap.add_argument("--variant", required=True)
ap.add_argument("--out", default="")
ap.add_argument("--scale", type=int, default=100)
ap.add_argument("--samples", type=int, default=0)
ap.add_argument("--save-blend", default="")
ap.add_argument("--export-glb", default="", help="write the street as a flat-colour greybox .glb instead of rendering")
args = ap.parse_args(sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else [])

VARIANTS = {
    # Overcast morning, heavy cold fog, everything drained of colour.
    "A_cold_fog": dict(
        scene="street", world=((0.52, 0.60, 0.68), 1.7),
        sun=dict(energy=1.2, color=(0.85, 0.92, 1.0), elev=38, azim=215, angle=50),
        fog=dict(density=0.030, color=(0.78, 0.84, 0.90), aniso=0.15),
        lamps=0.0, wet=0.7, exposure=0.5, samples=96),
    # Low sun straight down the street through dust: rust, amber, long shadows.
    "B_rust_dusk": dict(
        scene="street", world=((0.60, 0.36, 0.22), 0.30),
        sun=dict(energy=9.0, color=(1.0, 0.48, 0.16), elev=5.0, azim=12, angle=3),
        fog=dict(density=0.016, color=(1.0, 0.82, 0.62), aniso=0.65),
        lamps=0.0, wet=0.15, exposure=-0.4, samples=128),
    # Power still on: sodium street lamps punching cones into wet black night.
    "C_sodium_night": dict(
        scene="street", world=((0.020, 0.035, 0.07), 0.03),
        sun=dict(energy=0.02, color=(0.6, 0.75, 1.0), elev=50, azim=150, angle=5),
        fog=dict(density=0.011, color=(0.9, 0.9, 0.9), aniso=0.35),
        lamps=1.0, lit_window=True, wet=1.0, exposure=-0.2, samples=192),
    # After the blackout: moonlight and your flashlight, nothing else.
    "D_blackout": dict(
        scene="street", world=((0.025, 0.045, 0.085), 0.13),
        sun=dict(energy=0.16, color=(0.55, 0.72, 1.0), elev=42, azim=160, angle=4),
        fog=dict(density=0.008, color=(0.9, 0.92, 0.95), aniso=0.45),
        lamps=0.0, flashlight=True, wet=1.0, exposure=0.3, samples=192),
    # Safehouse interior: boarded window, light shafts, a lantern.
    "E_safehouse": dict(scene="interior", exposure=2.3, samples=224),
}
V = VARIANTS[args.variant]
random.seed(11)

bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene


# --------------------------------------------------------------------------
# Geometry helpers (real-size meshes, object scale stays 1 so procedural
# textures in object space keep a constant texel density).
# --------------------------------------------------------------------------
def link(ob, parent=None):
    scene.collection.objects.link(ob)
    if parent is not None:
        ob.parent = parent
    return ob


def empty(loc=(0, 0, 0), yaw=0.0, parent=None):
    e = bpy.data.objects.new("grp", None)
    e.location = loc
    e.rotation_euler = (0, 0, yaw)
    return link(e, parent)


def mesh_obj(bm, mat=None, loc=(0, 0, 0), rot=(0, 0, 0), parent=None, name="m"):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    ob.location = loc
    ob.rotation_euler = rot
    if mat is not None:
        me.materials.append(mat)
    return link(ob, parent)


def box(size, loc, rot=(0, 0, 0), mat=None, parent=None, bevel=0.0, taper=None):
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    for v in bm.verts:
        top = v.co.z > 0
        v.co.x *= size[0]
        v.co.y *= size[1]
        v.co.z *= size[2]
        if taper and top:
            v.co.x *= taper[0]
            v.co.y *= taper[1]
    ob = mesh_obj(bm, mat, loc, rot, parent, "box")
    if bevel:
        m = ob.modifiers.new("bev", 'BEVEL')
        m.width = bevel
        m.segments = 2
    return ob


def cyl(r, h, loc, rot=(0, 0, 0), mat=None, parent=None, seg=14, r2=None):
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=seg,
                          radius1=r, radius2=r if r2 is None else r2, depth=h)
    for f in bm.faces:
        f.smooth = len(f.verts) == 4
    return mesh_obj(bm, mat, loc, rot, parent, "cyl")


def blob(radius, scale, loc, rot=(0, 0, 0), mat=None, parent=None, lumpy=0.0, sub=2):
    bm = bmesh.new()
    bmesh.ops.create_icosphere(bm, subdivisions=sub, radius=radius)
    for v in bm.verts:
        if lumpy:
            v.co *= 1.0 + lumpy * noise.noise(v.co * 3.0 + Vector(loc))
        v.co.x *= scale[0]
        v.co.y *= scale[1]
        v.co.z *= scale[2]
    for f in bm.faces:
        f.smooth = True
    return mesh_obj(bm, mat, loc, rot, parent, "blob")


def aim(ob, target):
    d = Vector(target) - Vector(ob.location)
    ob.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()


def skin_obj(verts, edges, radii, mat, loc=(0, 0, 0), rot=(0, 0, 0), parent=None, sub=1):
    me = bpy.data.meshes.new("skin")
    me.from_pydata(verts, edges, [])
    me.update()
    ob = bpy.data.objects.new("skin", me)
    ob.location = loc
    ob.rotation_euler = rot
    me.materials.append(mat)
    link(ob, parent)
    sk = ob.modifiers.new("skin", 'SKIN')
    sk.use_smooth_shade = True
    data = me.skin_vertices[0].data
    for i, r in enumerate(radii):
        data[i].radius = (r, r)
    data[0].use_root = True
    if sub:
        s = ob.modifiers.new("sub", 'SUBSURF')
        s.levels = sub
        s.render_levels = sub
    return ob


# --------------------------------------------------------------------------
# Material helpers
# --------------------------------------------------------------------------
FLAT = {      # name -> (base colour, roughness): flat stand-ins for the procedural shaders in the greybox export
    'asphalt': ((0.030, 0.030, 0.032), 0.40), 'concrete': ((0.13, 0.13, 0.125), 0.85),
    'ground': ((0.045, 0.040, 0.022), 1.0), 'deadgrass': ((0.16, 0.14, 0.06), 0.9),
    'brick': ((0.11, 0.045, 0.03), 0.9), 'roof': ((0.040, 0.036, 0.034), 0.8),
    'trim': ((0.30, 0.29, 0.26), 0.7), 'glass': ((0.010, 0.012, 0.016), 0.1),
    'stripes': ((0.50, 0.30, 0.20), 0.7),
}


class M:
    def __init__(self, name):
        self.mat = bpy.data.materials.new(name)
        try:
            self.mat.use_nodes = True
        except Exception:
            pass
        self.nt = self.mat.node_tree
        nodes = self.nt.nodes
        self.out = next((n for n in nodes if n.type == 'OUTPUT_MATERIAL'), None) \
            or nodes.new('ShaderNodeOutputMaterial')
        self.bsdf = next((n for n in nodes if n.type == 'BSDF_PRINCIPLED'), None)
        if self.bsdf is None:
            self.bsdf = nodes.new('ShaderNodeBsdfPrincipled')
            self.nt.links.new(self.bsdf.outputs[0], self.out.inputs['Surface'])
        self.tc = nodes.new('ShaderNodeTexCoord')

    def put(self, sock, v):
        if v is None:
            return
        if isinstance(v, bpy.types.NodeSocket):
            self.nt.links.new(v, sock)
            return
        try:
            n = len(sock.default_value)
        except TypeError:
            n = 0
        if n == 4 and isinstance(v, (tuple, list)) and len(v) == 3:
            v = (*v, 1.0)
        sock.default_value = v

    def coords(self, scale=(1, 1, 1), kind='Object'):
        mp = self.nt.nodes.new('ShaderNodeMapping')
        self.nt.links.new(self.tc.outputs[kind], mp.inputs['Vector'])
        mp.inputs['Scale'].default_value = scale
        return mp.outputs[0]

    def noise(self, scale, detail=5.0, rough=0.55, vec=None):
        n = self.nt.nodes.new('ShaderNodeTexNoise')
        n.inputs['Scale'].default_value = scale
        n.inputs['Detail'].default_value = detail
        n.inputs['Roughness'].default_value = rough
        self.put(n.inputs['Vector'], vec if vec is not None else self.tc.outputs['Object'])
        return n.outputs[0]

    def ramp(self, fac, stops):
        n = self.nt.nodes.new('ShaderNodeValToRGB')
        el = n.color_ramp.elements
        while len(el) < len(stops):
            el.new(0.5)
        for e, (pos, col) in zip(el, stops):
            e.position = pos
            e.color = (col, col, col, 1) if isinstance(col, (int, float)) else (*col, 1)
        self.put(n.inputs[0], fac)
        return n.outputs[0]

    def mix(self, fac, a, b, blend='MIX'):
        n = self.nt.nodes.new('ShaderNodeMix')
        n.data_type = 'RGBA'
        n.blend_type = blend
        ins = {s.identifier: s for s in n.inputs}
        self.put(ins['Factor_Float'], fac)
        self.put(ins['A_Color'], a)
        self.put(ins['B_Color'], b)
        return next(s for s in n.outputs if s.identifier == 'Result_Color')

    def math(self, op, a, b=None):
        n = self.nt.nodes.new('ShaderNodeMath')
        n.operation = op
        self.put(n.inputs[0], a)
        if b is not None:
            self.put(n.inputs[1], b)
        return n.outputs[0]

    def sep(self, vec=None):
        n = self.nt.nodes.new('ShaderNodeSeparateXYZ')
        self.put(n.inputs[0], vec if vec is not None else self.tc.outputs['Object'])
        return n.outputs

    def bump(self, height, strength=0.5, dist=0.02):
        n = self.nt.nodes.new('ShaderNodeBump')
        n.inputs['Strength'].default_value = strength
        n.inputs['Distance'].default_value = dist
        self.put(n.inputs['Height'], height)
        self.nt.links.new(n.outputs[0], self.bsdf.inputs['Normal'])

    def set(self, **kw):
        for k, v in kw.items():
            name = k.replace('_', ' ')
            if name in self.bsdf.inputs:
                self.put(self.bsdf.inputs[name], v)
        return self.mat


def mat_asphalt(wet):
    m = M("asphalt")
    grit = m.noise(55, 3, 0.6)
    mid = m.noise(1.6, 6, 0.65)
    patches = m.noise(0.11, 4, 0.6)
    col = m.ramp(mid, [(0.30, (0.012, 0.012, 0.014)), (0.78, (0.050, 0.049, 0.050))])
    vor = m.nt.nodes.new('ShaderNodeTexVoronoi')
    vor.feature = 'DISTANCE_TO_EDGE'
    vor.inputs['Scale'].default_value = 3.6
    m.put(vor.inputs['Vector'], m.tc.outputs['Object'])
    crack = m.ramp(vor.outputs['Distance'], [(0.0, 0.5), (0.02, 1.0)])
    col = m.mix(1.0, col, crack, 'MULTIPLY')
    pud = m.ramp(patches, [(0.56, 0.0), (0.63, 1.0)]) if wet > 0.3 else 0.0
    col = m.mix(pud, col, (0.006, 0.007, 0.009)) if wet > 0.3 else col
    rough = m.mix(pud, (0.85 - 0.45 * wet,) * 3, (0.02, 0.02, 0.02))
    height = m.math('MULTIPLY', grit, m.math('SUBTRACT', 1.0, pud))
    m.bump(m.math('ADD', height, m.math('MULTIPLY', crack, 0.8)), 0.5, 0.01)
    return m.set(Base_Color=col, Roughness=rough)


def mat_concrete(wet=0.0):
    m = M("concrete")
    n1 = m.noise(3.0, 6, 0.7)
    n2 = m.noise(40, 2, 0.5)
    col = m.ramp(n1, [(0.25, (0.07, 0.07, 0.068)), (0.8, (0.20, 0.20, 0.19))])
    m.bump(n2, 0.35, 0.01)
    return m.set(Base_Color=col, Roughness=0.9 - 0.35 * wet)


def mat_ground():
    m = M("ground")
    n1 = m.noise(0.9, 6, 0.7)
    col = m.ramp(n1, [(0.25, (0.020, 0.018, 0.010)), (0.55, (0.045, 0.042, 0.022)),
                      (0.85, (0.070, 0.058, 0.032))])
    m.bump(m.noise(12, 4, 0.7), 0.8, 0.05)
    return m.set(Base_Color=col, Roughness=1.0)


def mat_grass():
    m = M("deadgrass")
    n1 = m.noise(0.6, 3, 0.6)
    col = m.ramp(n1, [(0.3, (0.07, 0.065, 0.025)), (0.6, (0.20, 0.17, 0.07)),
                      (0.85, (0.30, 0.25, 0.12))])
    return m.set(Base_Color=col, Roughness=0.9)


def mat_siding(name, color):
    FLAT[name] = (tuple(c * 0.75 for c in color), 0.85)
    m = M(name)
    xyz = m.sep()
    lap = m.math('FRACT', m.math('MULTIPLY', xyz[2], 5.5))
    streak = m.noise(1.0, 5, 0.7, m.coords((7, 7, 0.5)))
    grime = m.ramp(streak, [(0.30, 0.35), (0.70, 1.0)])
    col = m.mix(1.0, color, grime, 'MULTIPLY')
    peel = m.ramp(m.noise(2.2, 8, 0.75), [(0.60, 0.0), (0.66, 1.0)])
    col = m.mix(peel, col, (0.085, 0.075, 0.062))
    low = m.ramp(xyz[2], [(0.0, 1.0), (0.22, 0.0)])     # dirt splashed up from ground
    col = m.mix(m.math('MULTIPLY', low, 0.7), col, (0.03, 0.026, 0.02))
    m.bump(m.math('ADD', lap, m.math('MULTIPLY', peel, 0.3)), 0.6, 0.03)
    return m.set(Base_Color=col, Roughness=0.85)


def mat_brick():
    m = M("brick")
    b = m.nt.nodes.new('ShaderNodeTexBrick')
    m.put(b.inputs['Vector'], m.coords((1, 1, 1)))
    b.inputs['Scale'].default_value = 3.2
    b.inputs['Color1'].default_value = (0.16, 0.055, 0.035, 1)
    b.inputs['Color2'].default_value = (0.09, 0.040, 0.030, 1)
    b.inputs['Mortar'].default_value = (0.10, 0.10, 0.09, 1)
    b.inputs['Mortar Size'].default_value = 0.025
    grime = m.ramp(m.noise(1.5, 6, 0.7), [(0.3, 0.45), (0.75, 1.0)])
    m.bump(b.outputs['Fac'], 0.6, -0.02)
    return m.set(Base_Color=m.mix(1.0, b.outputs['Color'], grime, 'MULTIPLY'), Roughness=0.9)


def mat_roof():
    m = M("roof")
    b = m.nt.nodes.new('ShaderNodeTexBrick')
    m.put(b.inputs['Vector'], m.tc.outputs['Generated'])
    b.inputs['Scale'].default_value = 26.0
    b.inputs['Color1'].default_value = (0.030, 0.028, 0.028, 1)
    b.inputs['Color2'].default_value = (0.055, 0.045, 0.040, 1)
    b.inputs['Mortar'].default_value = (0.010, 0.010, 0.010, 1)
    moss = m.ramp(m.noise(1.2, 6, 0.7), [(0.55, 0.0), (0.7, 1.0)])
    col = m.mix(m.math('MULTIPLY', moss, 0.6), b.outputs['Color'], (0.035, 0.05, 0.02))
    m.bump(b.outputs['Fac'], 0.5, -0.02)
    return m.set(Base_Color=col, Roughness=0.8)


def mat_trim():
    m = M("trim")
    col = m.ramp(m.noise(3.0, 6, 0.7), [(0.3, (0.16, 0.15, 0.13)), (0.75, (0.45, 0.44, 0.40))])
    return m.set(Base_Color=col, Roughness=0.7)


def mat_glass():
    m = M("glass")
    dirt = m.ramp(m.noise(2.5, 6, 0.75), [(0.35, 0.03), (0.75, 0.45)])
    return m.set(Base_Color=(0.010, 0.012, 0.016), Roughness=dirt)


def mat_void():
    return M("void").set(Base_Color=(0.002, 0.002, 0.002), Roughness=1.0)


def mat_emit(name, color, strength, mottled=False):
    m = M(name)
    col = color
    if mottled:
        col = m.mix(1.0, color, m.ramp(m.noise(1.3, 3, 0.5), [(0.35, 0.25), (0.7, 1.0)]), 'MULTIPLY')
    return m.set(Base_Color=(0, 0, 0), Emission_Color=col, Emission_Strength=strength)


def mat_wood(name="wood", tone=(0.10, 0.065, 0.035), stretch=(1, 14, 14)):
    FLAT[name] = (tuple(c * 0.65 for c in tone), 0.75)
    m = M(name)
    grain = m.noise(2.0, 6, 0.7, m.coords(stretch))
    dark = tuple(c * 0.35 for c in tone)
    col = m.ramp(grain, [(0.25, dark), (0.75, tone)])
    m.bump(grain, 0.4, 0.01)
    return m.set(Base_Color=col, Roughness=0.75)


def mat_paint(name, color, rust=0.5):
    FLAT[name] = (tuple(c * 0.8 for c in color), 0.4)
    m = M(name)
    r = m.ramp(m.noise(2.5, 8, 0.75), [(0.75 - 0.25 * rust, 0.0), (0.82 - 0.2 * rust, 1.0)])
    dirt = m.ramp(m.noise(0.8, 5, 0.7), [(0.3, 0.45), (0.8, 1.0)])
    col = m.mix(r, m.mix(1.0, color, dirt, 'MULTIPLY'), (0.09, 0.035, 0.015))
    rough = m.mix(r, (0.32, 0.32, 0.32), (0.9, 0.9, 0.9))
    return m.set(Base_Color=col, Roughness=rough, Metallic=0.35, Coat_Weight=0.25)


def mat_plain(name, color, rough=0.7, metallic=0.0):
    FLAT[name] = (color, rough)
    return M(name).set(Base_Color=color, Roughness=rough, Metallic=metallic)


def mat_metal(name="metal", color=(0.16, 0.17, 0.17)):
    FLAT[name] = (tuple(c * 0.85 for c in color), 0.55)
    m = M(name)
    r = m.ramp(m.noise(4.0, 8, 0.75), [(0.55, 0.0), (0.68, 1.0)])
    col = m.mix(r, color, (0.10, 0.04, 0.018))
    return m.set(Base_Color=col, Roughness=m.mix(r, (0.45,) * 3, (0.9,) * 3), Metallic=0.7)


def mat_cloth(name, color):
    FLAT[name] = (tuple(c * 0.65 for c in color), 0.95)
    m = M(name)
    stain = m.ramp(m.noise(5.0, 6, 0.7), [(0.3, 0.3), (0.75, 1.0)])
    m.bump(m.noise(60, 2, 0.5), 0.2, 0.005)
    return m.set(Base_Color=m.mix(1.0, color, stain, 'MULTIPLY'), Roughness=0.95)


def mat_stripes():
    m = M("stripes")
    x = m.sep()[0]
    s = m.math('GREATER_THAN', m.math('FRACT', m.math('MULTIPLY', x, 2.2)), 0.5)
    col = m.mix(s, (0.55, 0.52, 0.45), (0.50, 0.11, 0.02))
    dirt = m.ramp(m.noise(4, 6, 0.7), [(0.3, 0.4), (0.8, 1.0)])
    return m.set(Base_Color=m.mix(1.0, col, dirt, 'MULTIPLY'), Roughness=0.7)


def mat_wallpaper():
    m = M("wallpaper")
    xyz = m.sep()
    stripe = m.math('GREATER_THAN', m.math('FRACT', m.math('MULTIPLY', m.math('ADD', xyz[0], xyz[1]), 4.0)), 0.5)
    col = m.mix(stripe, (0.22, 0.24, 0.17), (0.17, 0.19, 0.13))
    stain = m.ramp(m.noise(1.1, 7, 0.75), [(0.30, 0.25), (0.75, 1.0)])
    col = m.mix(1.0, col, stain, 'MULTIPLY')
    peel = m.ramp(m.noise(0.9, 8, 0.8), [(0.63, 0.0), (0.67, 1.0)])
    col = m.mix(peel, col, (0.30, 0.28, 0.24))
    damp = m.ramp(xyz[2], [(0.72, 0.0), (1.0, 0.75)])     # water marks under the ceiling
    col = m.mix(damp, col, (0.045, 0.04, 0.03))
    m.bump(peel, 0.5, 0.01)
    return m.set(Base_Color=col, Roughness=0.85)


def mat_floor():
    m = M("floor")
    b = m.nt.nodes.new('ShaderNodeTexBrick')
    m.put(b.inputs['Vector'], m.coords((1, 1, 1)))
    b.inputs['Scale'].default_value = 1.0
    b.inputs['Brick Width'].default_value = 1.8
    b.inputs['Row Height'].default_value = 0.13
    b.inputs['Mortar Size'].default_value = 0.004
    b.inputs['Color1'].default_value = (0.085, 0.050, 0.026, 1)
    b.inputs['Color2'].default_value = (0.050, 0.030, 0.016, 1)
    b.inputs['Mortar'].default_value = (0.004, 0.003, 0.002, 1)
    grain = m.noise(2.0, 6, 0.7, m.coords((14, 1, 1)))
    col = m.mix(1.0, b.outputs['Color'], m.ramp(grain, [(0.2, 0.5), (0.8, 1.0)]), 'MULTIPLY')
    wear = m.ramp(m.noise(0.8, 6, 0.7), [(0.3, 0.25), (0.75, 0.6)])
    m.bump(b.outputs['Fac'], 0.4, -0.01)
    return m.set(Base_Color=col, Roughness=wear)


def volume_box(size, loc, density, color, aniso):
    ob = box(size, loc)
    mat = bpy.data.materials.new("fog")
    try:
        mat.use_nodes = True
    except Exception:
        pass
    nt = mat.node_tree
    for n in list(nt.nodes):
        if n.type != 'OUTPUT_MATERIAL':
            nt.nodes.remove(n)
    out = next((n for n in nt.nodes if n.type == 'OUTPUT_MATERIAL'), None) \
        or nt.nodes.new('ShaderNodeOutputMaterial')
    vol = nt.nodes.new('ShaderNodeVolumeScatter')
    vol.inputs['Color'].default_value = (*color, 1)
    vol.inputs['Density'].default_value = density
    vol.inputs['Anisotropy'].default_value = aniso
    nt.links.new(vol.outputs[0], out.inputs['Volume'])
    ob.data.materials.append(mat)
    ob.visible_shadow = False
    ob.name = "fog_volume"
    return ob


def set_world(color, strength):
    w = bpy.data.worlds.new("world")
    try:
        w.use_nodes = True
    except Exception:
        pass
    bg = next(n for n in w.node_tree.nodes if n.type == 'BACKGROUND')
    bg.inputs['Color'].default_value = (*color, 1)
    bg.inputs['Strength'].default_value = strength
    scene.world = w


def add_light(kind, loc, energy, color, parent=None, **kw):
    li = bpy.data.lights.new(kind.lower(), kind)
    li.energy = energy
    li.color = color
    for k, v in kw.items():
        setattr(li, k, v)
    ob = bpy.data.objects.new(kind.lower(), li)
    ob.location = loc
    return link(ob, parent)


def add_sun(energy, color, elev, azim, angle):
    e, a = math.radians(elev), math.radians(azim)
    to_sun = Vector((math.cos(e) * math.sin(a), math.cos(e) * math.cos(a), math.sin(e)))
    ob = add_light('SUN', (0, 0, 50), energy, color, angle=math.radians(angle))
    ob.rotation_euler = (-to_sun).to_track_quat('-Z', 'Y').to_euler()
    return ob


# --------------------------------------------------------------------------
# Street set pieces
# --------------------------------------------------------------------------
def fbox(parent, mat, axis, sign, wall, u, z, w, h, thick, out, rot=0.0, bevel=0.0):
    """Box lying on a facade. axis 'x': wall plane at x = sign*wall, u runs along Y."""
    c = sign * (wall + out)
    if axis == 'x':
        return box((thick, w, h), (c, u, z), (rot, 0, 0), mat, parent, bevel)
    return box((w, thick, h), (u, c, z), (0, rot, 0), mat, parent, bevel)


def window(parent, axis, sign, wall, u, z, w, h, state):
    fbox(parent, MAT['trim'], axis, sign, wall, u, z, w + 0.22, h + 0.22, 0.06, 0.03)
    pane = {'lit': MAT['lit'], 'broken': MAT['void']}.get(state, MAT['glass'])
    fbox(parent, pane, axis, sign, wall, u, z, w, h, 0.04, 0.055)
    fbox(parent, MAT['trim'], axis, sign, wall, u, z, 0.05, h, 0.03, 0.075)
    fbox(parent, MAT['trim'], axis, sign, wall, u, z + h * 0.12, w, 0.05, 0.03, 0.075)
    fbox(parent, MAT['trim'], axis, sign, wall, u, z - h / 2 - 0.12, w + 0.3, 0.07, 0.16, 0.08)
    if state == 'boarded':
        n = random.choice((3, 4))
        for k in range(n):
            zz = z - h / 2 + (k + 0.5) * h / n + random.uniform(-0.06, 0.06)
            fbox(parent, MAT['plank'], axis, sign, wall, u + random.uniform(-0.08, 0.08), zz,
                 w + random.uniform(0.3, 0.6), 0.17, 0.035, 0.13 + 0.004 * k,
                 rot=random.uniform(-0.16, 0.16))


def house(x, y, side, siding, stories, depth, width, lit=False):
    """side=-1: left of the road (front faces +X); side=+1: right (front faces -X)."""
    g = empty((x, y, 0.13), random.uniform(-0.03, 0.03) + (math.pi if side > 0 else 0.0))
    dx, wy = depth, width
    base = 0.5
    H = 2.9 * stories
    box((dx + 0.25, wy + 0.25, base), (0, 0, base / 2), mat=MAT['concrete'], parent=g)
    box((dx, wy, H), (0, 0, base + H / 2), mat=siding, parent=g)
    ztop = base + H
    pitch = math.radians(random.uniform(33, 44))
    ridge_y = random.random() < 0.5
    span, length = (dx, wy) if ridge_y else (wy, dx)
    rise = span / 2 * math.tan(pitch)
    ov, th = 0.5, 0.14
    sl = span / 2 / math.cos(pitch) + ov
    hl = length / 2
    # gable infill
    me = bpy.data.meshes.new("gable")
    if ridge_y:
        vs = [(-span / 2, -hl, 0), (span / 2, -hl, 0), (0, -hl, rise),
              (-span / 2, hl, 0), (span / 2, hl, 0), (0, hl, rise)]
    else:
        vs = [(-hl, -span / 2, 0), (-hl, span / 2, 0), (-hl, 0, rise),
              (hl, -span / 2, 0), (hl, span / 2, 0), (hl, 0, rise)]
    me.from_pydata(vs, [], [(0, 1, 2), (3, 5, 4), (0, 3, 4, 1), (1, 4, 5, 2), (2, 5, 3, 0)])
    me.materials.append(siding)
    gab = bpy.data.objects.new("gable", me)
    gab.location = (0, 0, ztop)
    link(gab, g)
    c, s = math.cos(pitch), math.sin(pitch)
    for sg in (1, -1):
        along = sl / 2 - 0.02
        if ridge_y:
            loc = (sg * (c * along + s * th / 2), 0, ztop + rise - s * along + c * th / 2)
            box((sl, length + 2 * ov, th), loc, (0, sg * pitch, 0), MAT['roof'], g)
        else:
            loc = (0, sg * (c * along + s * th / 2), ztop + rise - s * along + c * th / 2)
            box((length + 2 * ov, sl, th), loc, (-sg * pitch, 0, 0), MAT['roof'], g)
    # chimney + downpipe
    box((0.6, 0.6, rise + 1.4), (random.uniform(-dx / 4, dx / 4), random.uniform(-wy / 4, wy / 4),
                                 ztop + (rise + 1.4) / 2 - 0.2), mat=MAT['brick'], parent=g)
    cyl(0.045, H + 0.3, (dx / 2 + 0.06, wy / 2 - 0.15, base + H / 2), mat=MAT['metal'], parent=g, seg=8)
    # front facade (+X local)
    door_u = random.choice((-1, 0, 1)) * wy * 0.28
    states = ['ok', 'ok', 'boarded', 'broken', 'ok', 'boarded']
    for st in range(stories):
        zc = base + st * 2.9 + 1.55
        for u in (-wy * 0.30, 0.0, wy * 0.30):
            if st == 0 and abs(u - door_u) < 0.8:
                continue
            state = random.choice(states)
            if lit and st == stories - 1 and u > 0:
                state = 'lit'
            window(g, 'x', 1, dx / 2, u, zc, random.choice((1.0, 1.15)), 1.35, state)
        for sgn in (1, -1):     # side facades
            for u in (-dx * 0.22, dx * 0.24):
                if random.random() < 0.75:
                    window(g, 'y', sgn, wy / 2, u, zc, 0.95, 1.3, random.choice(states))
    # door
    fbox(g, MAT['trim'], 'x', 1, dx / 2, door_u, base + 1.08, 1.15, 2.2, 0.06, 0.03)
    if random.random() < 0.3:
        fbox(g, MAT['void'], 'x', 1, dx / 2, door_u, base + 1.04, 0.95, 2.05, 0.04, 0.055)
        a = math.radians(random.uniform(40, 80))
        hy = door_u - 0.475
        box((0.05, 0.95, 2.05), (dx / 2 + 0.08 + math.sin(a) * 0.475, hy + math.cos(a) * 0.475, base + 1.04),
            (0, 0, -a), MAT['door'], g)
    else:
        fbox(g, MAT['door'], 'x', 1, dx / 2, door_u, base + 1.04, 0.95, 2.05, 0.05, 0.06)
        if random.random() < 0.4:
            for k in range(2):
                fbox(g, MAT['plank'], 'x', 1, dx / 2, door_u, base + 0.7 + k * 0.75, 1.5, 0.17, 0.035,
                     0.12, rot=random.uniform(-0.2, 0.2))
    # porch + steps
    pd = random.choice((1.4, 1.8))
    box((pd, 2.6, base), (dx / 2 + pd / 2, door_u, base / 2), mat=MAT['plank'], parent=g)
    for k in range(2):
        box((0.3, 1.4, base * (2 - k) / 3), (dx / 2 + pd + 0.15 + 0.3 * k, door_u, base * (2 - k) / 6),
            mat=MAT['concrete'], parent=g)
    if random.random() < 0.65:
        for sgn in (1, -1):
            box((0.1, 0.1, 2.3), (dx / 2 + pd - 0.1, door_u + sgn * 1.2, base + 1.15), mat=MAT['trim'], parent=g)
        box((pd + 0.3, 2.9, 0.1), (dx / 2 + pd / 2, door_u, base + 2.35), (0, 0.12, 0), MAT['roof'], g)
    return g


def fence(x, y0, y1, height=1.0):
    n = int(abs(y1 - y0) / 0.16)
    g = empty((x, min(y0, y1), 0.13))
    p = box((0.025, 0.1, height), (0, 0, height / 2), mat=MAT['trim'], parent=g)
    arr = p.modifiers.new("arr", 'ARRAY')
    arr.count = n
    arr.use_relative_offset = False
    arr.use_constant_offset = True
    arr.constant_offset_displace = (0, 0.16, 0)
    for z in (0.3, 0.75):
        box((0.04, abs(y1 - y0), 0.07), (-0.03, abs(y1 - y0) / 2, z * height), mat=MAT['trim'], parent=g)
    return g


def lamp_post(x, y, side, power):
    g = empty((x, y, 0.15))
    cyl(0.09, 7.0, (0, 0, 3.5), mat=MAT['metal'], parent=g, r2=0.06)
    box((1.9, 0.07, 0.07), (-side * 0.9, 0, 6.95), (0, side * 0.08, 0), MAT['metal'], g)
    box((0.7, 0.26, 0.12), (-side * 1.9, 0, 7.0), mat=MAT['metal'], parent=g, bevel=0.03)
    on = power > 0.01
    box((0.5, 0.18, 0.03), (-side * 1.9, 0, 6.93),
        mat=MAT['sodium'] if on else MAT['glass'], parent=g)
    if on:
        li = add_light('SPOT', (-side * 1.9, 0, 6.85), 3200 * power, (1.0, 0.50, 0.14), g,
                       spot_size=math.radians(128), spot_blend=0.55, shadow_soft_size=0.12)
        li.rotation_euler = (0, 0, 0)
    return g


def utility_pole(x, y):
    g = empty((x, y, 0.13), random.uniform(-0.05, 0.05))
    g.rotation_euler.x = random.uniform(-0.03, 0.03)
    cyl(0.14, 9.5, (0, 0, 4.75), mat=MAT['pole'], parent=g, r2=0.10, seg=10)
    box((2.2, 0.1, 0.12), (0, 0, 8.9), mat=MAT['pole'], parent=g)
    box((1.4, 0.1, 0.1), (0, 0, 8.2), mat=MAT['pole'], parent=g)
    cyl(0.16, 0.5, (0.3, 0.15, 7.6), mat=MAT['metal'], parent=g, seg=10)
    return [Vector((x + dx, y, 0.13 + z)) for dx, z in ((-1.0, 9.0), (0.0, 9.0), (1.0, 9.0), (-0.6, 8.3), (0.6, 8.3))]


def wire(a, b, sag=0.6, r=0.012):
    cu = bpy.data.curves.new("wire", 'CURVE')
    cu.dimensions = '3D'
    sp = cu.splines.new('POLY')
    n = 14
    sp.points.add(n)
    for i in range(n + 1):
        t = i / n
        p = a.lerp(b, t)
        p.z -= sag * 4 * t * (1 - t)
        sp.points[i].co = (*p, 1)
    cu.bevel_depth = r
    cu.bevel_resolution = 1
    cu.materials.append(MAT['rubber'])
    return link(bpy.data.objects.new("wire", cu))


def car(x, y, yaw, paint, door_open=False, hood_open=False):
    g = empty((x, y, 0.0), yaw)
    box((1.78, 4.4, 0.58), (0, 0, 0.62), mat=paint, parent=g, bevel=0.10)
    box((1.60, 2.3, 0.50), (0, -0.25, 1.14), mat=MAT['glass'], parent=g, taper=(0.84, 0.70))
    box((1.34, 1.55, 0.05), (0, -0.25, 1.41), mat=paint, parent=g, bevel=0.02)
    for sx in (-1, 1):       # pillars
        for py in (-1.02, -0.25, 0.52):
            box((0.05, 0.07, 0.52), (sx * 0.71, py * 0.86 - 0.03, 1.14), (0, -sx * 0.16, 0), paint, g)
        for sy in (-1.38, 1.38):
            cyl(0.33, 0.22, (sx * 0.84, sy, 0.33), (0, math.pi / 2, 0), MAT['rubber'], g, seg=18)
            cyl(0.19, 0.24, (sx * 0.84, sy, 0.33), (0, math.pi / 2, 0), MAT['metal'], g, seg=12)
    for sy in (-2.2, 2.2):
        box((1.82, 0.14, 0.2), (0, sy, 0.42), mat=MAT['bumper'], parent=g, bevel=0.03)
    for sx in (-0.62, 0.62):
        box((0.36, 0.05, 0.16), (sx, 2.2, 0.72), mat=MAT['headlamp'], parent=g)
        box((0.32, 0.05, 0.14), (sx, -2.2, 0.74), mat=MAT['taillamp'], parent=g)
    if door_open:
        a = math.radians(62)
        box((0.06, 1.05, 0.55), (-0.90 - math.sin(a) * 0.52, 0.75 - math.cos(a) * 0.52, 0.66),
            (0, 0, -a), paint, g, bevel=0.02)
    if hood_open:
        box((1.6, 1.25, 0.05), (0, 1.0, 1.42), (math.radians(-55), 0, 0), paint, g, bevel=0.02)
    return g


def tree(x, y, height, seed):
    rnd = random.Random(seed)
    verts, edges, radii = [Vector((0, 0, 0))], [], [height * 0.035]

    def grow(i0, d, length, r, depth):
        idx, p = i0, verts[i0].copy()
        segs = 3
        for _ in range(segs):
            d = (d + Vector((rnd.uniform(-.22, .22), rnd.uniform(-.22, .22), rnd.uniform(-.05, .12)))).normalized()
            p = p + d * (length / segs)
            verts.append(p.copy())
            radii.append(max(r, 0.012))
            edges.append((idx, len(verts) - 1))
            idx = len(verts) - 1
            r *= 0.86
        if depth < 5:
            for _ in range(3 if depth < 2 else 2):
                side = Vector((rnd.uniform(-1, 1), rnd.uniform(-1, 1), rnd.uniform(-0.15, 0.5))).normalized()
                nd = (d * rnd.uniform(0.6, 1.0) + side * rnd.uniform(0.6, 1.0)).normalized()
                grow(idx, nd, length * rnd.uniform(0.62, 0.8), r * 0.72, depth + 1)

    grow(0, Vector((0, 0, 1)), height * 0.36, height * 0.028, 0)
    return skin_obj([tuple(v) for v in verts], edges, radii, MAT['bark'], (x, y, 0.1), sub=0)


def zombie(x, y, yaw, cloth, lean=0.0, scale=1.0):
    j = lambda a=0.03: random.uniform(-a, a)
    drop = random.uniform(0.0, 0.07)
    P = [
        (0, 0, 0.96), (j(), 0.04, 1.20), (j(), 0.10, 1.42), (j(), 0.17, 1.55),            # 0-3 pelvis..neck
        (j(.04), 0.25 + j(), 1.66), (j(.04), 0.28, 1.78),                                # 4-5 head
        (-0.20, 0.09, 1.45), (-0.27 + j(), 0.12 + j(.06), 1.15), (-0.25 + j(), 0.22 + j(.08), 0.88),
        (0.20, 0.09, 1.42 - drop), (0.28 + j(), 0.16 + j(.06), 1.12 - drop), (0.30 + j(), 0.30 + j(.1), 0.92 - drop),
        (-0.10, 0, 0.93), (-0.12 + j(), 0.10 + j(.05), 0.50), (-0.13, 0.02 + j(.05), 0.06), (-0.14, 0.20, 0.04),
        (0.10, 0, 0.93), (0.12 + j(), -0.02 + j(.05), 0.50), (0.13, -0.14 + j(.05), 0.06), (0.14, 0.05, 0.04),
    ]
    E = [(0, 1), (1, 2), (2, 3), (3, 4), (4, 5), (2, 6), (6, 7), (7, 8), (2, 9), (9, 10), (10, 11),
         (0, 12), (12, 13), (13, 14), (14, 15), (0, 16), (16, 17), (17, 18), (18, 19)]
    R = [.15, .14, .16, .06, .10, .09, .075, .05, .04, .075, .05, .04, .095, .065, .05, .04, .095, .065, .05, .04]
    P = [tuple(c * scale for c in p) for p in P]
    R = [r * scale for r in R]
    ob = skin_obj(P, E, R, cloth, (x, y, 0.0), (lean, 0, yaw), sub=2)
    return ob


def body_bag(x, y, yaw):
    blob(0.5, (0.58, 1.85, 0.42), (x, y, 0.33), (0, 0, yaw), MAT['bag'], lumpy=0.28, sub=3)
    blob(0.16, (1.0, 1.0, 0.9), (x + math.sin(yaw) * -0.72, y + math.cos(yaw) * 0.72, 0.38), mat=MAT['bag'], lumpy=0.2)


def barricade(x, y, yaw):
    g = empty((x, y, 0), yaw)
    box((2.6, 0.04, 0.22), (0, 0, 0.95), mat=MAT['stripes'], parent=g)
    box((2.6, 0.04, 0.14), (0, 0, 0.55), mat=MAT['stripes'], parent=g)
    for sx in (-1.1, 1.1):
        for sg in (1, -1):
            box((0.06, 0.05, 1.15), (sx, sg * 0.2, 0.55), (sg * 0.33, 0, 0), MAT['plank'], g)


def build_street():
    wet = V['wet']
    MAT.update(
        asphalt=mat_asphalt(wet), concrete=mat_concrete(wet), ground=mat_ground(), grass=mat_grass(),
        brick=mat_brick(), roof=mat_roof(), trim=mat_trim(), glass=mat_glass(), void=mat_void(),
        lit=mat_emit("lit", (1.0, 0.55, 0.22), 2.2, mottled=True),
        sodium=mat_emit("sodium", (1.0, 0.5, 0.14), 35.0),
        plank=mat_wood("plank", (0.13, 0.10, 0.07)), door=mat_wood("door", (0.06, 0.035, 0.03), (10, 10, 1)),
        pole=mat_wood("pole", (0.05, 0.04, 0.035), (12, 12, 1)), bark=mat_wood("bark", (0.035, 0.03, 0.025), (8, 8, 1)),
        metal=mat_metal(), rubber=mat_plain("rubber", (0.008, 0.008, 0.008), 0.85),
        bumper=mat_plain("bumper", (0.03, 0.03, 0.03), 0.5, 0.5),
        headlamp=mat_plain("headlamp", (0.5, 0.5, 0.45), 0.15, 0.3),
        taillamp=mat_plain("taillamp", (0.25, 0.01, 0.01), 0.2),
        bag=mat_plain("bag", (0.30, 0.30, 0.29), 0.4), trash=mat_plain("trash", (0.012, 0.012, 0.014), 0.35),
        paper=mat_plain("paper", (0.45, 0.43, 0.38), 0.9), stripes=mat_stripes(),
        paintline=mat_plain("paintline", (0.15, 0.14, 0.10), 0.8),
        carton=mat_plain("carton", (0.16, 0.11, 0.06), 0.9),
    )
    sidings = [mat_siding(f"siding{i}", c) for i, c in enumerate([
        (0.42, 0.40, 0.34), (0.30, 0.34, 0.36), (0.36, 0.30, 0.18), (0.26, 0.10, 0.08),
        (0.22, 0.27, 0.20), (0.44, 0.42, 0.40), (0.16, 0.19, 0.24)])]
    paints = [mat_paint("car_red", (0.20, 0.02, 0.02), 0.5), mat_paint("car_blue", (0.03, 0.06, 0.12), 0.35),
              mat_paint("car_white", (0.40, 0.40, 0.38), 0.7)]
    cloths = [mat_cloth(f"cloth{i}", c) for i, c in enumerate(
        [(0.05, 0.05, 0.06), (0.07, 0.05, 0.04), (0.04, 0.06, 0.05), (0.09, 0.09, 0.08)])]

    # ground, road, sidewalks
    box((240, 300, 0.2), (0, 60, -0.12), mat=MAT['ground'])
    box((7.0, 260, 0.1), (0, 60, -0.05), mat=MAT['asphalt'])
    for sg in (-1, 1):
        box((2.0, 260, 0.15), (sg * 4.5, 60, 0.075), mat=MAT['concrete'], bevel=0.02)
        bm = bmesh.new()
        bmesh.ops.create_grid(bm, x_segments=40, y_segments=160, size=0.5)
        for v in bm.verts:
            v.co.x = sg * (5.5 + (v.co.x + 0.5) * 60)
            v.co.y = -40 + (v.co.y + 0.5) * 200
            v.co.z = 0.13 + 0.07 * noise.noise(v.co * 0.25) + 0.025 * noise.noise(v.co * 1.7)
        for f in bm.faces:
            f.smooth = True
        mesh_obj(bm, MAT['ground'], name="lawn")
    for k in range(-2, 18):      # worn centre dashes
        if random.random() < 0.85:
            box((0.12, 2.6, 0.01), (0.0, k * 7.0, 0.004), mat=MAT['paintline'])

    # dead grass and weeds as one mesh of thin blades
    bm = bmesh.new()

    def blade(px, py, pz, h, w, emit=True):
        a = random.uniform(0, math.tau)
        dx, dy = math.cos(a) * w, math.sin(a) * w
        lx, ly = random.uniform(-.5, .5) * h, random.uniform(-.5, .5) * h
        if not emit:        # still draws the same random numbers, so the rest of the street is identical
            return
        vs = [bm.verts.new((px - dx, py - dy, pz)), bm.verts.new((px + dx, py + dy, pz)),
              bm.verts.new((px + lx, py + ly, pz + h))]
        bm.faces.new(vs)

    for i in range(70000):
        sg = random.choice((-1, 1))
        py = -14 + 110 * random.random() ** 1.6
        px = sg * (5.5 + 24 * random.random() ** 1.5)
        blade(px, py, 0.10, random.uniform(0.15, 0.5), 0.028, emit=not args.export_glb or i % 4 == 0)
    for _ in range(2500):        # weeds along curbs and in sidewalk seams
        sg = random.choice((-1, 1))
        px = sg * random.choice((3.5, 3.52, 5.5, 5.48, 4.5)) + random.uniform(-0.05, 0.05)
        blade(px, -14 + 100 * random.random(), 0.0 if abs(px) < 3.6 else 0.14, random.uniform(0.06, 0.3), 0.015)
    mesh_obj(bm, MAT['grass'], name="grass")

    # houses
    rows = {-1: [4, 21, 38, 56, 74, 92, 110], 1: [-5, 12, 30, 48, 66, 84, 102]}
    for side, ys in rows.items():
        for i, y in enumerate(ys):
            depth = random.choice((7.5, 8.5, 9.5))
            width = random.choice((9.0, 10.0, 11.5))
            setback = random.uniform(4.5, 7.0)
            x = side * (5.5 + setback + depth / 2)
            stories = random.choice((1, 2, 2))
            house(x, y, side, random.choice(sidings), stories, depth, width,
                  lit=(V.get('lit_window', False) and side == -1 and i == 2))
            if random.random() < 0.6:
                fence(side * 5.7, y - width / 2 - 2.0, y + width / 2 + 2.0, random.choice((0.9, 1.1)))
            box((0.08, 0.08, 1.1), (side * 5.65, y + width / 2 + 2.6, 0.68), mat=MAT['plank'])     # mailbox
            box((0.2, 0.45, 0.22), (side * 5.65, y + width / 2 + 2.6, 1.28), (random.uniform(-.3, .3), 0, 0),
                MAT['metal'], bevel=0.04)
            if random.random() < 0.7:
                blob(0.9, (1.0, 1.3, 0.8), (side * (7.2 + random.uniform(0, 2)), y + random.uniform(-4, 4), 0.6),
                     mat=MAT['bark'], lumpy=0.35, sub=3)

    # trees
    for i, (tx, ty, th) in enumerate([(-7.5, 13, 9), (7.8, 22, 11), (-8.5, 47, 10), (8.0, 57, 9),
                                      (-7.2, 66, 12), (7.6, 77, 10), (-16, -3, 10), (17, 3, 9)]):
        tree(tx, ty, th, 100 + i)

    # street furniture
    for (lx, ly, side, pw) in [(4.25, 10, 1, 1.0), (-4.25, 38, -1, 0.12), (4.25, 66, 1, 1.0), (-4.25, 94, -1, 1.0)]:
        lamp_post(lx, ly, side, pw * V['lamps'])
    tops = [utility_pole(-5.2, y) for y in (-12, 18, 48, 78, 108)]
    for a, b in zip(tops, tops[1:]):
        for pa, pb in zip(a, b):
            wire(pa, pb, sag=random.uniform(0.5, 0.9))
    wire(tops[1][2], Vector((11.0, 14.0, 5.6)), sag=0.5)
    wire(tops[1][0], Vector((-11.5, 21.0, 5.2)), sag=0.3)
    wire(tops[2][2], Vector((11.0, 46.0, 5.6)), sag=0.6)

    # vehicles and street clutter
    car(2.9, 13.5, math.radians(152), paints[0], door_open=True)        # slewed onto the curb
    car(-2.5, 31.0, math.radians(4), paints[1])
    car(2.4, 47.0, math.radians(-12), paints[2], hood_open=True)
    car(-9.5, 7.0, math.radians(92), paints[2])
    barricade(-1.6, 21.0, math.radians(8))
    barricade(1.3, 22.2, math.radians(-21))
    for i, (bx, by) in enumerate([(-4.6, 9.2), (-4.4, 11.4), (-4.7, 13.9)]):
        body_bag(bx, by, math.radians(72 + random.uniform(-14, 14)))
    for _ in range(9):
        blob(0.33, (1, 1, 0.75), (random.choice((-3.9, 3.8)) + random.uniform(-0.3, 0.3),
                                  random.uniform(0, 45), 0.36), mat=MAT['trash'], lumpy=0.3)
    for _ in range(26):
        s = random.uniform(0.2, 0.32)
        box((s, s * 1.4, 0.002), (random.uniform(-4.5, 4.5), random.uniform(-4, 30), 0.006 + random.random() * 0.01),
            (0, 0, random.uniform(0, 6.3)), MAT['paper'])
    for _ in range(7):
        s = random.uniform(0.3, 0.55)
        box((s, s * 1.2, s * 0.8), (random.uniform(-3.3, 3.3), random.uniform(3, 34), s * 0.4),
            (0, 0, random.uniform(0, 6.3)), MAT['carton'])
    box((0.55, 0.75, 0.32), (0.9, 10.5, 0.16), (0, 0, 0.5), MAT['bumper'], bevel=0.04)      # dropped suitcase

    # the dead
    zs = [(-1.5, 17.5, 3.35, 0.10), (-0.8, 25.5, 3.3, 0.10), (1.6, 34.0, 2.9, 0.05), (-4.3, 41.0, 3.5, 0.12), (0.4, 53.0, 3.0, 0.08),
          (3.2, 28.0, 2.4, 0.06), (-2.2, 62.0, 3.2, 0.1)]
    if V.get('flashlight'):
        zs = [(-0.9, 12.3, 3.25, 0.10), (1.2, 17.0, 2.8, 0.06), (-3.4, 18.5, 3.6, 0.12), (0.4, 24.5, 3.1, 0.08)] + zs[3:]
    for i, (zx, zy, yaw, lean) in enumerate(zs):
        zombie(zx, zy, yaw, cloths[i % len(cloths)], lean, random.uniform(0.95, 1.05))

    # atmosphere + light
    set_world(*V['world'])
    add_sun(**V['sun'])
    f = V['fog']
    volume_box((200, 340, 60), (0, 80, 29.8), f['density'], f['color'], f['aniso'])
    cam_pos = Vector((-0.9, 3.0, 1.62))
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    cam.data.lens = 21
    cam.data.clip_end = 600
    cam.location = cam_pos
    link(cam)
    aim(cam, (0.9, 50, 3.3))
    scene.camera = cam
    if V.get('flashlight'):
        fl = add_light('SPOT', cam_pos + Vector((0.30, 0.25, -0.32)), 3400, (0.92, 0.96, 1.0),
                       spot_size=math.radians(36), spot_blend=0.4, shadow_soft_size=0.02)
        aim(fl, (-0.45, 15.0, 1.45))


# --------------------------------------------------------------------------
# Safehouse interior
# --------------------------------------------------------------------------
def build_interior():
    MAT.update(
        wall=mat_wallpaper(), floor=mat_floor(), trim=mat_trim(), void=mat_void(),
        plank=mat_wood("plank", (0.13, 0.10, 0.07)), dark=mat_wood("darkwood", (0.07, 0.04, 0.025), (1, 12, 12)),
        ceiling=mat_concrete(), metal=mat_metal(), rubber=mat_plain("rubber", (0.01, 0.01, 0.01), 0.8),
        sheet=mat_cloth("sheet", (0.36, 0.34, 0.28)), blanket=mat_cloth("blanket", (0.10, 0.13, 0.11)),
        pack=mat_cloth("pack", (0.07, 0.085, 0.05)), paper=mat_plain("paper", (0.5, 0.47, 0.38), 0.9),
        tin=mat_plain("tin", (0.45, 0.45, 0.42), 0.3, 0.9), label_r=mat_plain("label_r", (0.35, 0.04, 0.03), 0.6),
        label_y=mat_plain("label_y", (0.45, 0.33, 0.05), 0.6), label_g=mat_plain("label_g", (0.07, 0.2, 0.08), 0.6),
        bottle=mat_plain("bottle", (0.15, 0.25, 0.3), 0.1), steel=mat_plain("steel", (0.05, 0.05, 0.055), 0.35, 0.9),
        flame=mat_emit("flame", (1.0, 0.55, 0.18), 40.0), shell=mat_plain("shell", (0.4, 0.03, 0.03), 0.5),
        rug=mat_cloth("rug", (0.16, 0.05, 0.04)),
    )
    W, D, Hh = 5.0, 6.2, 2.6
    box((W + 1, D + 1, 0.2), (0, D / 2, -0.1), mat=MAT['floor'])
    box((W + 1, D + 1, 0.2), (0, D / 2, Hh + 0.1), mat=MAT['ceiling'])
    box((0.25, D + 1, Hh), (W / 2 + 0.125, D / 2, Hh / 2), mat=MAT['wall'])      # right wall
    box((W + 1, 0.25, Hh), (0, -0.125, Hh / 2), mat=MAT['wall'])                 # wall behind camera
    # left wall with a window opening (y 2.7..4.5, z 0.9..2.2)
    xl = -W / 2 - 0.125
    wy0, wy1, wz0, wz1 = 2.7, 4.5, 0.9, 2.2
    box((0.25, wy0 + 0.5, Hh), (xl, (wy0 - 0.5) / 2, Hh / 2), mat=MAT['wall'])
    box((0.25, D + 0.5 - wy1, Hh), (xl, (wy1 + D + 0.5) / 2, Hh / 2), mat=MAT['wall'])
    box((0.25, wy1 - wy0, wz0), (xl, (wy0 + wy1) / 2, wz0 / 2), mat=MAT['wall'])
    box((0.25, wy1 - wy0, Hh - wz1), (xl, (wy0 + wy1) / 2, (wz1 + Hh) / 2), mat=MAT['wall'])
    yc, zc = (wy0 + wy1) / 2, (wz0 + wz1) / 2
    for dz in (-0.7, 0.7):
        box((0.12, wy1 - wy0 + 0.2, 0.1), (-W / 2 + 0.02, yc, zc + dz), mat=MAT['trim'])
    for dy in (-0.95, 0.0, 0.95):
        box((0.10, 0.07 if dy == 0 else 0.1, wz1 - wz0), (-W / 2 + 0.02, yc + dy, zc), mat=MAT['trim'])
    for k in range(6):       # boards nailed across from the inside, uneven gaps
        z = wz0 + 0.1 + k * 0.215 + random.uniform(-0.02, 0.02)
        box((0.035, wy1 - wy0 + random.uniform(0.3, 0.7), random.uniform(0.15, 0.18)),
            (-W / 2 + 0.10 + 0.004 * k, yc + random.uniform(-0.1, 0.1), z),
            (random.uniform(-0.07, 0.07), 0, 0), MAT['plank'])
    # far wall: door, barricaded
    box((W + 1, 0.25, Hh), (0, D + 0.125, Hh / 2), mat=MAT['wall'])
    box((1.1, 0.08, 2.15), (1.2, D - 0.03, 1.075), mat=MAT['trim'])
    box((0.92, 0.06, 2.05), (1.2, D - 0.07, 1.03), mat=MAT['dark'])
    for z, r in ((0.7, 0.10), (1.25, -0.13), (1.75, 0.06)):
        box((1.6, 0.035, 0.17), (1.2, D - 0.12, z), (0, r, 0), MAT['plank'])
    box((1.5, 0.55, 1.0), (1.25, D - 0.45, 0.5), (0, 0, 0.06), MAT['dark'], bevel=0.015)       # dresser shoved over
    for z in (0.3, 0.62):
        box((1.3, 0.03, 0.24), (1.26, D - 0.74, z + 0.08), (0, 0, 0.06), MAT['plank'])
    box((0.07, W, 0.12), (0, 0, 0), mat=MAT['trim'])
    for wall_y in (0.06, D - 0.06):
        box((W, 0.03, 0.11), (0, wall_y, 0.055), mat=MAT['trim'])
    # shelf with supplies on far wall
    for z in (1.15, 1.55):
        box((1.5, 0.24, 0.03), (-1.1, D - 0.13, z), mat=MAT['dark'])
        xk = -1.75
        while xk < -0.45:
            if random.random() < 0.8:
                lab = random.choice((MAT['label_r'], MAT['label_y'], MAT['label_g'], MAT['tin']))
                h = random.choice((0.11, 0.11, 0.08))
                cyl(0.037, h, (xk, D - 0.13 + random.uniform(-0.03, 0.03), z + 0.015 + h / 2), mat=lab, seg=12)
            xk += random.uniform(0.085, 0.14)
    # table under the window
    tx, ty = -1.85, 3.55
    box((0.75, 1.5, 0.04), (tx, ty, 0.76), mat=MAT['dark'], bevel=0.005)
    for sx in (-0.32, 0.32):
        for sy in (-0.68, 0.68):
            box((0.05, 0.05, 0.74), (tx + sx, ty + sy, 0.37), mat=MAT['dark'])
    for (cx, cy, lab) in ((-0.15, -0.5, 'label_r'), (-0.05, -0.42, 'tin'), (-0.2, -0.36, 'label_y')):
        cyl(0.037, 0.11, (tx + cx, ty + cy, 0.835), mat=MAT[lab], seg=12)
    cyl(0.037, 0.11, (tx + 0.12, ty - 0.55, 0.817), (math.pi / 2, 0, 0.6), MAT['tin'], seg=12)
    for (bx, by) in ((0.2, 0.55), (0.1, 0.62)):
        cyl(0.04, 0.24, (tx + bx, ty + by, 0.90), mat=MAT['bottle'], seg=12)
    box((0.13, 0.09, 0.07), (tx - 0.1, ty + 0.3, 0.815), (0, 0, 0.3), MAT['shell'])
    for _ in range(7):       # loose shells
        cyl(0.01, 0.06, (tx + random.uniform(-0.05, 0.2), ty + random.uniform(0.15, 0.4), 0.79),
            (math.pi / 2, 0, random.uniform(0, 3)), MAT['shell'], seg=8)
    # shotgun lying across the table
    g = empty((tx + 0.08, ty - 0.02, 0.80), math.radians(78))
    cyl(0.013, 0.66, (0.33, 0, 0.02), (0, math.pi / 2, 0), MAT['steel'], g, seg=10)
    cyl(0.013, 0.50, (0.26, 0, -0.008), (0, math.pi / 2, 0), MAT['steel'], g, seg=10)
    cyl(0.021, 0.20, (0.22, 0, -0.008), (0, math.pi / 2, 0), MAT['dark'], g, seg=10)
    box((0.24, 0.035, 0.06), (-0.08, 0, 0.008), mat=MAT['steel'], parent=g, bevel=0.006)
    box((0.34, 0.035, 0.10), (-0.36, 0, -0.02), (0, -0.12, 0), MAT['dark'], g, bevel=0.01, taper=(1, 1))
    # lantern
    lx, ly = tx + 0.12, ty - 0.2
    cyl(0.05, 0.03, (lx, ly, 0.795), mat=MAT['steel'], seg=12)
    for ax, ay in ((0.036, 0), (-0.036, 0), (0, 0.036), (0, -0.036)):     # open cage, so the flame lights the room
        cyl(0.003, 0.10, (lx + ax, ly + ay, 0.86), mat=MAT['steel'], seg=6)
    cyl(0.045, 0.025, (lx, ly, 0.92), mat=MAT['steel'], seg=12)
    blob(0.014, (1, 1, 2.2), (lx, ly, 0.85), mat=MAT['flame'])
    add_light('POINT', (lx, ly, 0.87), 14.0, (1.0, 0.55, 0.2), shadow_soft_size=0.015)
    fill = add_light('AREA', (0.6, 2.2, Hh - 0.05), 2.6, (0.62, 0.72, 0.85), size=2.5)      # stand-in for sky bounce
    fill.rotation_euler = (0, 0, 0)
    # chair
    cx, cy = -1.05, 3.2
    box((0.42, 0.42, 0.04), (cx, cy, 0.45), (0, 0, 0.5), MAT['dark'])
    box((0.04, 0.42, 0.5), (cx + 0.17, cy + 0.09, 0.72), (0, 0, 0.5), MAT['dark'])
    for sx, sy in ((-.17, -.17), (.17, -.17), (-.17, .17), (.17, .17)):
        box((0.04, 0.04, 0.45), (cx + sx * 0.9 - sy * 0.45, cy + sy * 0.9 + sx * 0.45, 0.225), mat=MAT['dark'])
    # mattress on the floor, far right
    box((0.95, 1.95, 0.2), (1.75, 3.6, 0.1), (0, 0, -0.04), MAT['sheet'], bevel=0.05)
    blob(0.5, (0.9, 1.25, 0.16), (1.72, 3.3, 0.24), (0, 0, 0.1), MAT['blanket'], lumpy=0.22, sub=3)
    blob(0.3, (0.75, 1.1, 0.3), (1.78, 4.35, 0.27), (0, 0, 1.5), MAT['sheet'], lumpy=0.1, sub=3)
    # backpack, pipe, water jug, papers on the wall
    blob(0.26, (0.8, 1.0, 1.2), (0.55, 5.35, 0.28), mat=MAT['pack'], lumpy=0.12, sub=3)
    cyl(0.02, 1.1, (-2.35, 1.6, 0.55), (0.0, -0.18, 0), MAT['metal'], seg=10)
    cyl(0.14, 0.34, (-0.2, 5.75, 0.17), mat=MAT['bottle'], seg=14)
    box((0.9, 0.012, 0.65), (-0.35, D - 0.008, 1.95), (0, 0.03, 0), MAT['paper'])
    for (px, pz, r) in ((0.45, 1.85, -0.12), (0.52, 1.62, 0.2)):
        box((0.2, 0.01, 0.27), (px, D - 0.008, pz), (0, r, 0), MAT['paper'])
    box((1.6, 2.3, 0.012), (-0.2, 2.9, 0.006), (0, 0, 0.12), MAT['rug'])
    # bare bulb, dead
    cyl(0.004, 0.5, (0, 3.2, Hh - 0.25), mat=MAT['rubber'], seg=6)
    blob(0.035, (1, 1, 1.3), (0, 3.2, Hh - 0.53), mat=MAT['bottle'])
    # light: overcast day outside, low sun raking in through the gaps
    set_world((0.62, 0.70, 0.80), 3.0)
    sun = add_light('SUN', (0, 0, 10), 14.0, (1.0, 0.90, 0.76), angle=math.radians(1.2))
    sun.rotation_euler = Vector((1.0, 0.22, -0.52)).to_track_quat('-Z', 'Y').to_euler()
    volume_box((W - 0.02, D - 0.02, Hh - 0.02), (0, D / 2, Hh / 2), 0.045, (0.95, 0.93, 0.88), 0.55)
    cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam"))
    cam.data.lens = 20
    cam.location = (1.75, 0.45, 1.5)
    link(cam)
    aim(cam, (-1.2, 5.0, 1.02))
    scene.camera = cam


# --------------------------------------------------------------------------
MAT = {}
if V['scene'] == 'street':
    build_street()
else:
    build_interior()

r = scene.render
r.engine = 'CYCLES'
r.resolution_x, r.resolution_y = 1600, 900
r.resolution_percentage = args.scale
r.image_settings.file_format = 'PNG'
r.filepath = args.out
c = scene.cycles
c.device = 'CPU'
c.samples = args.samples or V['samples']
c.use_adaptive_sampling = True
c.adaptive_threshold = 0.02
c.use_denoising = True
c.max_bounces = 6
c.diffuse_bounces = 3
c.glossy_bounces = 3
c.volume_bounces = 1
c.sample_clamp_indirect = 5.0
c.caustics_reflective = False
c.caustics_refractive = False
vs = scene.view_settings
for vt in ('AgX', 'Filmic', 'Standard'):
    try:
        vs.view_transform = vt
        break
    except TypeError:
        continue
for look in ('AgX - Medium High Contrast', 'Medium High Contrast', 'AgX - Base Contrast'):
    try:
        vs.look = look
        break
    except TypeError:
        continue
vs.exposure = V['exposure']
print("VIEW", vs.view_transform, vs.look, "samples", c.samples)
if args.save_blend:
    bpy.ops.wm.save_as_mainfile(filepath=args.save_blend)
if args.export_glb:
    # Realtime greybox: geometry only, flat colours, no lights / camera / fog.
    for ob in list(scene.objects):
        if ob.type in {'LIGHT', 'CAMERA'} or ob.name.startswith("fog_volume"):
            bpy.data.objects.remove(ob, do_unlink=True)
    for mat in bpy.data.materials:
        key = mat.name.split('.')[0]
        if key not in FLAT or not mat.node_tree:
            continue
        bsdf = next((n for n in mat.node_tree.nodes if n.type == 'BSDF_PRINCIPLED'), None)
        if bsdf is None:
            continue
        for sock in ('Base Color', 'Roughness', 'Normal'):
            for l in list(bsdf.inputs[sock].links):
                mat.node_tree.links.remove(l)
        bsdf.inputs['Base Color'].default_value = (*FLAT[key][0], 1.0)
        bsdf.inputs['Roughness'].default_value = FLAT[key][1]
    bpy.ops.export_scene.gltf(filepath=args.export_glb, export_format='GLB', export_apply=True,
                              export_lights=False, export_cameras=False, export_yup=True, export_texcoords=False)
    print("EXPORTED", args.export_glb)
else:
    bpy.ops.render.render(write_still=True)
    print("RENDERED", args.out)

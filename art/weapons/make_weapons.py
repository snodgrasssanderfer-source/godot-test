"""First-pass viewmodels, generated from code: pistol, pump shotgun, steel pipe.

Original designs (no real or licensed firearm is copied). Each weapon is
exported as its own .glb with the barrel along Godot's -Z and the origin at the
grip. Moving parts are separate nodes so the game can animate them:

  Slide / Pump   translate along Z          Mag    drops along its own -Y
  Muzzle, Eject, Sight   empty markers (flash, shell port, rear sight line)

  blender --background --factory-startup --python art/weapons/make_weapons.py -- fps/assets/weapons
"""
import math
import sys

import bmesh
import bpy

out_dir = sys.argv[sys.argv.index("--") + 1] if "--" in sys.argv else "fps/assets/weapons"
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
        steel=material("steel", (0.035, 0.036, 0.040), 0.38, 1.0),
        worn=material("worn_steel", (0.16, 0.16, 0.17), 0.45, 1.0),
        polymer=material("polymer", (0.012, 0.012, 0.013), 0.62),
        dark=material("dark", (0.002, 0.002, 0.002), 0.9),
        wood=material("wood", (0.11, 0.050, 0.022), 0.48),
        rust=material("rust", (0.10, 0.060, 0.040), 0.72, 0.6),
        tape=material("tape", (0.20, 0.19, 0.16), 0.95),
        white=material("sight_dot", (0.65, 0.65, 0.60), 0.6),
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
    """taper_y=(sx, sz): scale the +Y end (the front) of the box."""
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


def tube(r, length, loc, mat, parent, axis='y', seg=20, r2=None, name="part"):
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=True, cap_tris=False, segments=seg,
                          radius1=r, radius2=r if r2 is None else r2, depth=length)
    for f in bm.faces:
        f.smooth = len(f.verts) == 4
    rot = {'y': (-math.pi / 2, 0, 0), 'x': (0, math.pi / 2, 0), 'z': (0, 0, 0)}[axis]
    return finish(bm, mat, loc, rot, parent, name)


def export(name):
    path = f"{out_dir}/{name}.glb"
    bpy.ops.export_scene.gltf(filepath=path, export_format='GLB', export_apply=True, export_yup=True,
                              export_texcoords=False, export_lights=False, export_cameras=False)
    print("EXPORTED", path)


def pistol():
    reset()
    root = node("pistol")
    tilt = math.radians(-14)
    # frame, dust cover with rail notches, grip
    box((0.026, 0.176, 0.017), (0, 0.058, -0.004), 'polymer', root, bevel=0.002)
    for i in range(3):
        box((0.028, 0.004, 0.004), (0, 0.092 + i * 0.016, -0.0125), 'polymer', root)
    box((0.027, 0.047, 0.102), (0, -0.016, -0.060), 'polymer', root, rot=(tilt, 0, 0), bevel=0.005)
    box((0.028, 0.006, 0.060), (0, -0.036, -0.045), 'polymer', root, rot=(tilt, 0, 0), bevel=0.002)   # backstrap
    for i in range(5):       # grip texture
        box((0.0285, 0.030, 0.003), (0, -0.016 - 0.0033 * i, -0.040 - 0.013 * i), 'dark', root, rot=(tilt, 0, 0))
    # trigger guard + trigger
    box((0.008, 0.050, 0.005), (0, 0.036, -0.049), 'polymer', root, bevel=0.0015)
    box((0.008, 0.005, 0.040), (0, 0.060, -0.030), 'polymer', root, rot=(math.radians(12), 0, 0), bevel=0.0015)
    box((0.005, 0.006, 0.022), (0, 0.030, -0.026), 'steel', root, rot=(math.radians(-16), 0, 0), bevel=0.001)
    # barrel inside the slide
    tube(0.0068, 0.150, (0, 0.079, 0.019), 'worn', root, seg=14)
    tube(0.0045, 0.004, (0, 0.1545, 0.019), 'dark', root, seg=12)
    # slide
    slide = node("Slide", parent=root)
    box((0.0275, 0.188, 0.029), (0, 0.059, 0.0185), 'steel', slide, bevel=0.0028)
    box((0.0125, 0.036, 0.012), (0.0085, 0.058, 0.0285), 'dark', slide)                 # ejection port
    for sx in (-1, 1):
        for i in range(7):
            box((0.0012, 0.0022, 0.021), (sx * 0.0139, -0.030 + i * 0.0048, 0.0185), 'dark', slide)
    box((0.0036, 0.007, 0.0065), (0, 0.143, 0.0355), 'steel', slide)                    # front sight
    box((0.0022, 0.0008, 0.0022), (0, 0.1393, 0.0362), 'white', slide)
    for sx in (-1, 1):                                                                  # rear sight
        box((0.0050, 0.008, 0.0065), (sx * 0.0058, -0.028, 0.0355), 'steel', slide)
        box((0.0020, 0.0008, 0.0020), (sx * 0.0058, -0.0322, 0.0362), 'white', slide)
    # magazine: its own node, tilted with the grip, so it can drop along local -Z
    mag = node("Mag", (0, -0.016, -0.060), (tilt, 0, 0), root)
    box((0.021, 0.033, 0.112), (0, 0.001, -0.004), 'worn', mag, bevel=0.002)
    box((0.028, 0.050, 0.009), (0, 0.002, -0.0585), 'polymer', mag, bevel=0.002)
    node("Muzzle", (0, 0.158, 0.019), parent=root)
    node("Eject", (0.018, 0.058, 0.030), parent=root)
    node("Sight", (0, -0.028, 0.0388), parent=root)
    export("pistol")


def shotgun():
    reset()
    root = node("shotgun")
    # receiver
    box((0.042, 0.232, 0.062), (0, 0.076, 0.025), 'steel', root, bevel=0.004)
    box((0.004, 0.072, 0.020), (0.0205, 0.095, 0.040), 'dark', root)                    # ejection port
    box((0.030, 0.090, 0.004), (0, 0.090, -0.0065), 'dark', root)                       # loading port
    box((0.010, 0.200, 0.003), (0, 0.076, 0.0565), 'dark', root)                        # sighting groove
    # barrel, magazine tube, clamp, bead
    tube(0.0108, 0.520, (0, 0.450, 0.040), 'steel', root, seg=18)
    tube(0.0085, 0.004, (0, 0.7115, 0.040), 'dark', root, seg=14)
    tube(0.0120, 0.400, (0, 0.390, 0.013), 'steel', root, seg=16)
    tube(0.0135, 0.022, (0, 0.600, 0.013), 'worn', root, seg=16)
    box((0.016, 0.022, 0.050), (0, 0.560, 0.027), 'steel', root, bevel=0.003)
    tube(0.0026, 0.007, (0, 0.700, 0.0532), 'white', root, axis='z', seg=8)
    # pump: forend plus the action bars that reach back into the receiver
    pump = node("Pump", (0, 0.380, 0.013), parent=root)
    box((0.047, 0.200, 0.047), (0, 0, 0), 'wood', pump, bevel=0.010)
    for i in range(7):
        box((0.0485, 0.006, 0.030), (0, -0.075 + i * 0.025, 0), 'dark', pump)
    for sx in (-1, 1):
        box((0.003, 0.180, 0.008), (sx * 0.0175, -0.150, 0.004), 'steel', pump)
    # trigger group
    box((0.008, 0.066, 0.005), (0, 0.034, -0.040), 'steel', root, bevel=0.0015)
    box((0.008, 0.005, 0.036), (0, 0.066, -0.023), 'steel', root, rot=(math.radians(10), 0, 0), bevel=0.0015)
    box((0.005, 0.006, 0.022), (0, 0.030, -0.020), 'worn', root, rot=(math.radians(-14), 0, 0), bevel=0.001)
    # stock: wrist, comb, butt pad
    box((0.036, 0.110, 0.052), (0, -0.085, 0.004), 'wood', root, rot=(math.radians(-20), 0, 0), bevel=0.012)
    box((0.038, 0.250, 0.110), (0, -0.265, -0.040), 'wood', root, rot=(math.radians(-7), 0, 0),
        bevel=0.012, taper_y=(0.9, 0.55))
    box((0.040, 0.018, 0.118), (0, -0.392, -0.056), 'polymer', root, rot=(math.radians(-7), 0, 0), bevel=0.004)
    node("Muzzle", (0, 0.716, 0.040), parent=root)
    node("Eject", (0.030, 0.095, 0.042), parent=root)
    node("Sight", (0, -0.036, 0.0580), parent=root)
    export("shotgun")


def pipe():
    reset()
    root = node("pipe")
    tube(0.0165, 0.780, (0, 0.270, 0), 'rust', root, seg=16)
    tube(0.0125, 0.004, (0, -0.1215, 0), 'dark', root, seg=12)
    tube(0.0195, 0.170, (0, -0.030, 0), 'tape', root, seg=16)
    for i in range(6):       # overlapping tape edges
        tube(0.0203, 0.006, (0, -0.105 + i * 0.030, 0), 'tape', root, seg=16)
    tube(0.0235, 0.050, (0, 0.640, 0), 'worn', root, seg=8)                              # coupling
    tube(0.0210, 0.110, (0, 0.690, 0), 'rust', root, axis='x', seg=16)                   # tee at the business end
    tube(0.0240, 0.016, (0.055, 0.690, 0), 'worn', root, axis='x', seg=8)
    tube(0.0240, 0.016, (-0.055, 0.690, 0), 'worn', root, axis='x', seg=8)
    node("Tip", (0, 0.690, 0), parent=root)
    export("pipe")


pistol()
shotgun()
pipe()

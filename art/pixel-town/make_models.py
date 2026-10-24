"""Builds all low-poly models for 像素小镇, exports them as .glb and renders a preview.

Run from the repo root:
    blender --background --factory-startup --python art/pixel-town/make_models.py

Outputs:
    pixel-town/assets/models/*.glb      models used by the game
    art/pixel-town/models_preview.png   Cycles CPU lineup render

Conventions:
- Blender is Z-up; the glTF exporter converts to Godot's Y-up. "Front" of a model
  is Blender -Y, which becomes Godot +Z.
- Object names ending in "-col" become static trimesh collisions in Godot.
- Material names matter: the game swaps "Wood", "WoodDark", "Roof" and
  "FloorWood" for pixel-art textures, and lights up "Glass"/"LampGlass" at night.
"""
import math
import os

import bmesh
import bpy
from mathutils import Vector

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_DIR = os.path.join(ROOT, "pixel-town", "assets", "models")
OUT_PREVIEW = os.path.join(ROOT, "art", "pixel-town", "models_preview.png")

PALETTE = {
    "Wood": (0.80, 0.56, 0.34),
    "WoodDark": (0.46, 0.29, 0.17),
    "FloorWood": (0.70, 0.50, 0.32),
    "Roof": (0.82, 0.42, 0.34),
    "Glass": (0.70, 0.86, 0.95),
    "Stone": (0.62, 0.60, 0.58),
    "Rug": (0.90, 0.58, 0.60),
    "Mattress": (0.97, 0.94, 0.86),
    "Blanket": (0.55, 0.70, 0.90),
    "Pillow": (1.0, 1.0, 0.97),
    "Bark": (0.50, 0.34, 0.22),
    "Leaves": (0.46, 0.74, 0.38),
    "LeavesLight": (0.62, 0.82, 0.42),
    "Pine": (0.30, 0.58, 0.40),
    "Metal": (0.35, 0.33, 0.33),
    "LampGlass": (1.0, 0.86, 0.55),
    "Skin": (0.98, 0.82, 0.70),
    "Hair": (0.45, 0.27, 0.18),
    "Dress": (0.98, 0.84, 0.45),
    "Apron": (1.0, 0.97, 0.92),
    "Shoes": (0.40, 0.28, 0.22),
    "Hat": (0.95, 0.83, 0.52),
    "Ribbon": (0.93, 0.50, 0.55),
    "Eyes": (0.18, 0.13, 0.12),
    "Blush": (0.98, 0.62, 0.62),
    "Seed": (0.90, 0.80, 0.58),
    "Sprout": (0.52, 0.82, 0.38),
    "CropLeaf": (0.40, 0.72, 0.32),
    "Radish": (0.96, 0.52, 0.62),
    "Soil": (0.48, 0.33, 0.22),
}
_materials = {}


def srgb_to_linear(c):
    """PALETTE is written in sRGB (what you'd pick in a paint program); Blender
    and glTF base colors are linear."""
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def mat(name):
    if name not in _materials:
        m = bpy.data.materials.new(name)
        m.use_nodes = True
        bsdf = m.node_tree.nodes["Principled BSDF"]
        bsdf.inputs["Base Color"].default_value = (*(srgb_to_linear(c) for c in PALETTE[name]), 1.0)
        bsdf.inputs["Roughness"].default_value = 0.9
        if name in ("Glass", "LampGlass"):
            bsdf.inputs["Roughness"].default_value = 0.3
        _materials[name] = m
    return _materials[name]


def _finish(obj, name, material):
    obj.name = name
    obj.data.materials.append(mat(material))
    return obj


def box(name, center, size, material, rot=(0, 0, 0)):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center, rotation=rot)
    obj = bpy.context.active_object
    obj.scale = size
    return _finish(obj, name, material)


def cyl(name, center, radius, depth, material, verts=8, radius_top=None, rot=(0, 0, 0)):
    if radius_top is None:
        bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=radius, depth=depth,
                                            location=center, rotation=rot)
    else:
        bpy.ops.mesh.primitive_cone_add(vertices=verts, radius1=radius, radius2=radius_top,
                                        depth=depth, location=center, rotation=rot)
    return _finish(bpy.context.active_object, name, material)


def ico(name, center, scale, material, subdiv=1):
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=subdiv, radius=1, location=center)
    obj = bpy.context.active_object
    obj.scale = scale
    return _finish(obj, name, material)


def prism_x(name, points_yz, x0, x1, material):
    """Extrudes a convex polygon given in the YZ plane from x0 to x1."""
    mesh = bpy.data.meshes.new(name)
    bm = bmesh.new()
    front = [bm.verts.new((x0, y, z)) for y, z in points_yz]
    back = [bm.verts.new((x1, y, z)) for y, z in points_yz]
    bm.faces.new(list(reversed(front)))
    bm.faces.new(back)
    n = len(points_yz)
    for i in range(n):
        j = (i + 1) % n
        bm.faces.new([front[i], front[j], back[j], back[i]])
    bm.normal_update()
    bm.to_mesh(mesh)
    bm.free()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    obj.data.materials.append(mat(material))
    return obj


def join(name, objs):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
    if len(objs) > 1:
        bpy.ops.object.join()
    obj = bpy.context.active_object
    obj.name = name
    return obj


def export(model_name, objs):
    for o in objs:
        bpy.ops.object.select_all(action="DESELECT")
        o.select_set(True)
        bpy.context.view_layer.objects.active = o
        bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    path = os.path.join(OUT_DIR, model_name + ".glb")
    bpy.ops.export_scene.gltf(filepath=path, export_format="GLB", use_selection=True,
                              export_apply=True, export_yup=True)
    tris = sum(len(p.vertices) - 2 for o in objs for p in o.data.polygons)
    print(f"Exported {model_name}.glb ({tris} tris)")
    for o in objs:
        bpy.data.objects.remove(o, do_unlink=True)


# --- Models ------------------------------------------------------------------

def build_house():
    W, D, H, T = 6.0, 5.0, 2.6, 0.2          # width (x), depth (y), wall height, thickness
    DOOR_W, DOOR_H = 1.3, 2.1
    hx, hy = W / 2, D / 2
    walls = [
        box("w", (0, hy - T / 2, H / 2), (W, T, H), "Wood"),                      # back
        box("w", (-hx + T / 2, 0, H / 2), (T, D, H), "Wood"),                     # left
        box("w", (hx - T / 2, 0, H / 2), (T, D, H), "Wood"),                      # right
    ]
    side = (W - DOOR_W) / 2
    walls.append(box("w", (-hx + side / 2, -hy + T / 2, H / 2), (side, T, H), "Wood"))
    walls.append(box("w", (hx - side / 2, -hy + T / 2, H / 2), (side, T, H), "Wood"))
    walls.append(box("w", (0, -hy + T / 2, (H + DOOR_H) / 2), (DOOR_W, T, H - DOOR_H), "Wood"))
    rise = 1.4
    gable = [(-hy, H), (hy, H), (0, H + rise)]       # ridge runs along X
    walls.append(prism_x("g", gable, -hx, -hx + T, "Wood"))
    walls.append(prism_x("g", gable, hx - T, hx, "Wood"))
    # Corner posts and door frame.
    trim = []
    for sx in (-1, 1):
        for sy in (-1, 1):
            trim.append(box("p", (sx * (hx - 0.05), sy * (hy - 0.05), H / 2), (0.28, 0.28, H), "WoodDark"))
    trim.append(box("f", (-DOOR_W / 2 - 0.06, -hy - 0.02, DOOR_H / 2), (0.14, 0.12, DOOR_H), "WoodDark"))
    trim.append(box("f", (DOOR_W / 2 + 0.06, -hy - 0.02, DOOR_H / 2), (0.14, 0.12, DOOR_H), "WoodDark"))
    trim.append(box("f", (0, -hy - 0.02, DOOR_H + 0.06), (DOOR_W + 0.26, 0.12, 0.14), "WoodDark"))
    # Fake windows (frame + glass on both faces of a wall).
    glass = []
    windows = [((-1.7, -hy), "y"), ((1.7, -hy), "y"), ((-hx, 0.6), "x"), ((hx, 0.6), "x"), ((0, hy), "y")]
    for (px, py), axis in windows:
        for face in (-1, 1):
            if axis == "y":
                c = (px, py + face * (T / 2 + 0.03) + (T / 2 if py < 0 else -T / 2), 1.5)
                trim.append(box("wf", c, (1.0, 0.05, 0.9), "WoodDark"))
                glass.append(box("wg", (c[0], c[1] + face * 0.02, c[2]), (0.8, 0.04, 0.7), "Glass"))
            else:
                c = (px + face * (T / 2 + 0.03) + (T / 2 if px < 0 else -T / 2), py, 1.5)
                trim.append(box("wf", c, (0.05, 1.0, 0.9), "WoodDark"))
                glass.append(box("wg", (c[0] + face * 0.02, c[1], c[2]), (0.04, 0.8, 0.7), "Glass"))
    # Gable roof (ridge along X) with overhang.
    ov = 0.45
    slope = math.atan2(rise, hy)
    slab_w = (hy + ov) / math.cos(slope)
    roof = []
    for s in (-1, 1):
        mid_y = s * (hy + ov) / 2
        mid_z = H + rise - math.tan(slope) * (hy + ov) / 2 + 0.1
        roof.append(box("r", (0, mid_y, mid_z), (W + 2 * ov, slab_w, 0.16), "Roof",
                        rot=(-s * slope, 0, 0)))
    roof.append(box("r", (0, 0, H + rise + 0.08), (W + 2 * ov, 0.22, 0.16), "WoodDark"))
    chimney = [box("c", (1.6, 1.0, H + rise), (0.5, 0.5, 1.5), "Stone")]
    # Interior.
    floor = [box("fl", (0, 0, 0.01), (W - 2 * T, D - 2 * T, 0.02), "FloorWood")]
    furniture = [
        box("t", (1.8, 1.4, 0.72), (1.1, 0.7, 0.08), "WoodDark"),
        box("t", (1.8, 1.4, 0.36), (0.9, 0.5, 0.68), "Wood"),
        box("s", (-0.4, hy - T - 0.2, 0.9), (1.4, 0.35, 1.8), "WoodDark"),
        box("s", (-0.4, hy - T - 0.2, 0.9), (1.25, 0.37, 0.06), "Wood"),
        box("s", (-0.4, hy - T - 0.2, 1.4), (1.25, 0.37, 0.06), "Wood"),
    ]
    rug = [box("rug", (0.4, -0.6, 0.025), (2.0, 1.4, 0.01), "Rug")]
    # Open door leaf resting against the inside of the front wall.
    door = [box("d", (DOOR_W / 2 + 0.6, -hy + T + 0.05, DOOR_H / 2), (DOOR_W - 0.1, 0.08, DOOR_H - 0.05), "WoodDark")]
    export("house", [
        join("Walls-col", walls + trim),
        join("Glass", glass),
        join("Roof", roof + chimney),
        join("Floor", floor + rug),
        join("Furniture-col", furniture + door),
    ])


def build_bed():
    frame = [
        box("b", (0, 0, 0.18), (1.1, 2.1, 0.3), "WoodDark"),
        box("b", (0, 1.02, 0.5), (1.1, 0.1, 1.0), "WoodDark"),
        box("b", (0, -1.02, 0.35), (1.1, 0.1, 0.7), "WoodDark"),
    ]
    soft = [
        box("m", (0, 0, 0.4), (1.0, 1.95, 0.16), "Mattress"),
        box("bl", (0, -0.35, 0.5), (1.04, 1.25, 0.08), "Blanket"),
        box("pl", (0, 0.72, 0.55), (0.7, 0.35, 0.12), "Pillow"),
    ]
    export("bed", [join("Bed-col", frame), join("Bedding", soft)])


def build_trees():
    trunk = cyl("t", (0, 0, 0.9), 0.2, 1.8, "Bark", radius_top=0.14)
    leaves = [
        ico("l", (0, 0, 2.3), (1.15, 1.15, 0.95), "Leaves"),
        ico("l", (0.45, 0.25, 2.9), (0.75, 0.75, 0.65), "LeavesLight"),
        ico("l", (-0.4, -0.2, 2.75), (0.7, 0.7, 0.6), "Leaves"),
    ]
    export("tree_round", [join("Trunk-col", [trunk]), join("Leaves", leaves)])

    trunk = cyl("t", (0, 0, 0.6), 0.18, 1.2, "Bark", radius_top=0.14)
    cones = [cyl("c", (0, 0, 1.2 + i * 0.75), 1.1 - i * 0.28, 1.2, "Pine", verts=7, radius_top=0.05)
             for i in range(3)]
    export("tree_pine", [join("Trunk-col", [trunk]), join("Needles", cones)])


def build_shipping_bin():
    body = [
        box("b", (0, 0, 0.32), (1.1, 0.7, 0.6), "Wood"),
        box("b", (0, 0, 0.05), (1.16, 0.76, 0.1), "WoodDark"),
        box("b", (0, 0, 0.6), (1.16, 0.76, 0.06), "WoodDark"),
    ]
    for sx in (-1, 1):
        body.append(box("c", (sx * 0.56, -0.36, 0.32), (0.08, 0.06, 0.62), "Metal"))
        body.append(box("c", (sx * 0.56, 0.36, 0.32), (0.08, 0.06, 0.62), "Metal"))
    lid = [box("l", (0, 0.2, 0.86), (1.16, 0.76, 0.08), "WoodDark", rot=(math.radians(-35), 0, 0))]
    export("shipping_bin", [join("Bin-col", body), join("Lid", lid)])


def build_lamp_post():
    pole = [cyl("p", (0, 0, 1.2), 0.07, 2.4, "WoodDark", verts=6),
            box("p", (0, 0, 0.1), (0.3, 0.3, 0.2), "Stone")]
    lantern = [box("lg", (0, 0, 2.55), (0.26, 0.26, 0.3), "LampGlass")]
    cap = [box("c", (0, 0, 2.74), (0.36, 0.36, 0.08), "Metal"),
           box("c", (0, 0, 2.38), (0.32, 0.32, 0.05), "Metal")]
    export("lamp_post", [join("Pole-col", pole + cap), join("LampGlass", lantern)])


def build_fence():
    parts = [
        box("p", (-0.5, 0, 0.4), (0.12, 0.12, 0.8), "WoodDark"),
        box("r", (0, 0, 0.55), (1.0, 0.06, 0.1), "Wood"),
        box("r", (0, 0, 0.28), (1.0, 0.06, 0.1), "Wood"),
    ]
    export("fence", [join("Fence-col", parts)])


def build_villager():
    p = []
    for sx in (-1, 1):
        p.append(box("leg", (sx * 0.09, 0, 0.22), (0.11, 0.12, 0.44), "Skin"))
        p.append(box("shoe", (sx * 0.09, -0.03, 0.04), (0.13, 0.18, 0.08), "Shoes"))
    p.append(cyl("dress", (0, 0, 0.72), 0.3, 0.62, "Dress", verts=8, radius_top=0.17))
    p.append(box("apron", (0, -0.2, 0.68), (0.3, 0.06, 0.42), "Apron", rot=(math.radians(-14), 0, 0)))
    for sx in (-1, 1):
        p.append(box("arm", (sx * 0.24, 0, 0.82), (0.09, 0.09, 0.38), "Skin", rot=(0, sx * math.radians(12), 0)))
    p.append(box("head", (0, 0, 1.24), (0.38, 0.36, 0.36), "Skin"))
    p.append(box("hair", (0, 0.05, 1.38), (0.42, 0.34, 0.14), "Hair"))
    p.append(box("hair", (0, 0.16, 1.18), (0.42, 0.1, 0.36), "Hair"))
    for sx in (-1, 1):
        p.append(box("braid", (sx * 0.22, 0.08, 1.05), (0.08, 0.08, 0.3), "Hair"))
        p.append(box("eye", (sx * 0.08, -0.185, 1.25), (0.05, 0.02, 0.07), "Eyes"))
        p.append(box("blush", (sx * 0.13, -0.185, 1.19), (0.06, 0.02, 0.03), "Blush"))
    p.append(cyl("brim", (0, 0, 1.45), 0.36, 0.04, "Hat", verts=10))
    p.append(cyl("crown", (0, 0, 1.53), 0.2, 0.16, "Hat", verts=10, radius_top=0.17))
    p.append(cyl("ribbon", (0, 0, 1.48), 0.205, 0.05, "Ribbon", verts=10))
    export("villager", [join("Villager", p)])


def build_crops():
    # Stage 0: a few seeds on the soil.
    seeds = [ico("s", (x, y, 0.03), (0.035, 0.035, 0.025), "Seed") for x, y in
             ((-0.12, 0.05), (0.08, -0.1), (0.1, 0.12))]
    export("crop_0", [join("Crop", seeds)])

    def leaf(cx, cy, length, width, angle, tilt, material="CropLeaf"):
        c = (cx + math.cos(angle) * length * 0.45, cy + math.sin(angle) * length * 0.45, 0.04 + tilt * length * 0.4)
        return box("leaf", c, (length, width, 0.02), material,
                   rot=(0, -math.atan(tilt), angle))

    sprout = [cyl("stem", (0, 0, 0.06), 0.012, 0.12, "Sprout", verts=5)]
    sprout += [leaf(0, 0, 0.12, 0.06, a, 0.6, "Sprout") for a in (0.3, 0.3 + math.pi)]
    for o in sprout[1:]:
        o.location.z += 0.08
    export("crop_1", [join("Crop", sprout)])

    young = [leaf(0, 0, 0.22, 0.09, a, 0.9) for a in (0.2, 1.8, 3.4, 5.0)]
    export("crop_2", [join("Crop", young)])

    mature = [leaf(0, 0, 0.32, 0.12, a, 1.2) for a in (0.0, 1.25, 2.5, 3.75, 5.0)]
    mature.append(ico("radish", (0, 0, 0.06), (0.13, 0.13, 0.12), "Radish"))
    export("crop_3", [join("Crop", mature)])


# --- Preview -------------------------------------------------------------------

def render_preview():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    lineup = [("house", (-5.5, 3.0, 0), 0), ("tree_round", (1.2, 4.2, 0), 0),
              ("tree_pine", (3.6, 4.6, 0), 0), ("lamp_post", (0.0, 1.2, 0), 0),
              ("bed", (-1.0, -0.6, 0), 90), ("shipping_bin", (1.4, -0.4, 0), 0),
              ("villager", (3.2, -0.4, 0), 0), ("fence", (4.6, 0.8, 0), 0),
              ("crop_0", (-0.4, -2.2, 0), 0), ("crop_1", (0.4, -2.2, 0), 0),
              ("crop_2", (1.2, -2.2, 0), 0), ("crop_3", (2.1, -2.2, 0), 0)]
    for name, loc, rot in lineup:
        bpy.ops.import_scene.gltf(filepath=os.path.join(OUT_DIR, name + ".glb"))
        for obj in bpy.context.selected_objects:
            if obj.parent is None:
                obj.location = Vector(loc) + obj.location
                obj.rotation_euler.z += math.radians(rot)
    ground = bpy.data.materials.new("Ground")
    ground.use_nodes = True
    ground.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (
        *(srgb_to_linear(c) for c in (0.56, 0.81, 0.44)), 1)
    bpy.ops.mesh.primitive_plane_add(size=40)
    bpy.context.active_object.data.materials.append(ground)
    for x, y in ((-0.6, -2.2), (0.2, -2.2), (1.0, -2.2), (1.9, -2.2)):
        bpy.ops.mesh.primitive_plane_add(size=0.7, location=(x + 0.2, y, 0.005))
        bpy.context.active_object.data.materials.append(mat("Soil"))

    bpy.ops.object.light_add(type="SUN", rotation=(math.radians(50), 0, math.radians(-35)))
    bpy.context.active_object.data.energy = 3.2
    bpy.context.active_object.data.color = (1.0, 0.93, 0.8)
    world = bpy.data.worlds.new("World")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (
        *(srgb_to_linear(c) for c in (0.62, 0.8, 0.95)), 1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.9
    scene = bpy.context.scene
    scene.world = world
    bpy.ops.object.camera_add(location=(2.5, -11.5, 6.0))
    cam = bpy.context.active_object
    cam.rotation_euler = (Vector((-0.5, 1.2, 1.2)) - cam.location).to_track_quat("-Z", "Y").to_euler()
    cam.data.lens = 30
    scene.camera = cam
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 24
    scene.cycles.use_denoising = False
    scene.view_settings.view_transform = "Standard"
    scene.render.resolution_x = 960
    scene.render.resolution_y = 540
    scene.render.filepath = OUT_PREVIEW
    bpy.ops.render.render(write_still=True)
    print("Preview saved:", OUT_PREVIEW)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    bpy.ops.wm.read_factory_settings(use_empty=True)
    for build in (build_house, build_bed, build_trees, build_shipping_bin, build_lamp_post,
                  build_fence, build_villager, build_crops):
        build()
    render_preview()


main()

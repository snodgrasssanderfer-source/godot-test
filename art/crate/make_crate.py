"""Builds a low-poly wooden crate, renders a Cycles preview and exports a .glb.

Run from the repo root:
    blender --background --factory-startup --python art/crate/make_crate.py
Outputs:
    art/crate/crate.blend         editable source
    art/crate/crate_preview.png   Cycles CPU preview render
    game/assets/crate.glb         model used by the Godot game
"""
import math
import os

import bpy
from mathutils import Vector

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_BLEND = os.path.join(ROOT, "art", "crate", "crate.blend")
OUT_PREVIEW = os.path.join(ROOT, "art", "crate", "crate_preview.png")
OUT_GLB = os.path.join(ROOT, "game", "assets", "crate.glb")

SIZE = 1.0           # crate is a 1 m cube, origin at the bottom center
BEAM = 0.12          # thickness of the frame beams
INSET = 0.03         # how far the panels sit behind the frame


def make_material(name, color, roughness=0.8):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes["Principled BSDF"]
    bsdf.inputs["Base Color"].default_value = (*color, 1.0)
    bsdf.inputs["Roughness"].default_value = roughness
    mat.diffuse_color = (*color, 1.0)
    return mat


def add_box(name, center, dims, mat, rotation=(0, 0, 0)):
    bpy.ops.mesh.primitive_cube_add(size=1, location=center, rotation=rotation)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = dims
    obj.data.materials.append(mat)
    return obj


def build_crate(light, dark):
    h = SIZE / 2
    parts = []
    # Panels (slightly inset so the frame stands out).
    parts.append(add_box("Panels", (0, 0, h), [SIZE - 2 * INSET] * 3, light))
    # 12 frame beams along the cube edges.
    for axis in range(3):
        for a in (-1, 1):
            for b in (-1, 1):
                center = [0.0, 0.0, 0.0]
                dims = [BEAM, BEAM, BEAM]
                # X beams run full length, Y/Z beams fit between them, so the
                # beams never overlap at the corners.
                dims[axis] = SIZE if axis == 0 else SIZE - 2 * BEAM
                others = [i for i in range(3) if i != axis]
                center[others[0]] = a * (h - BEAM / 2)
                center[others[1]] = b * (h - BEAM / 2)
                center[2] += h
                parts.append(add_box("Beam", center, dims, dark))
    # One diagonal brace on each of the four sides.
    diag = math.hypot(SIZE - 2 * BEAM, SIZE - 2 * BEAM)
    angle = math.atan2(1, 1)
    for i, (normal, rot_z) in enumerate([((0, -1), 0), ((1, 0), math.pi / 2),
                                         ((0, 1), math.pi), ((-1, 0), -math.pi / 2)]):
        offset = h - INSET / 2
        center = (normal[0] * offset, normal[1] * offset, h)
        tilt = angle if i % 2 == 0 else -angle
        parts.append(add_box("Brace", center, (diag, INSET * 1.6, BEAM * 0.9), dark,
                             rotation=(0, tilt, rot_z)))

    bpy.ops.object.select_all(action="DESELECT")
    for p in parts:
        p.select_set(True)
    bpy.context.view_layer.objects.active = parts[0]
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
    bpy.ops.object.join()
    crate = bpy.context.active_object
    crate.name = "Crate"
    # Small bevel so edges catch the light; keeps the poly count low.
    bevel = crate.modifiers.new("Bevel", "BEVEL")
    bevel.width = 0.012
    bevel.segments = 1
    bevel.limit_method = "ANGLE"
    return crate


def setup_preview_scene(crate):
    scene = bpy.context.scene
    ground_mat = make_material("Ground", (0.35, 0.45, 0.3), roughness=1.0)
    bpy.ops.mesh.primitive_plane_add(size=8, location=(0, 0, 0))
    ground = bpy.context.active_object
    ground.name = "PreviewGround"
    ground.data.materials.append(ground_mat)

    bpy.ops.object.light_add(type="SUN", rotation=(math.radians(50), 0, math.radians(30)))
    bpy.context.active_object.data.energy = 3.0

    world = bpy.data.worlds.new("PreviewWorld")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.55, 0.72, 0.9, 1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = 0.8
    scene.world = world

    bpy.ops.object.camera_add(location=(2.6, -3.0, 2.1))
    cam = bpy.context.active_object
    direction = Vector((0, 0, 0.45)) - cam.location
    cam.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    cam.data.lens = 45
    scene.camera = cam

    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 32
    scene.cycles.use_denoising = False
    scene.view_settings.view_transform = "Standard"
    scene.render.resolution_x = 800
    scene.render.resolution_y = 600
    scene.render.filepath = OUT_PREVIEW
    return [ground]


def main():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    light = make_material("WoodLight", (0.62, 0.38, 0.16))
    dark = make_material("WoodDark", (0.28, 0.15, 0.06))
    crate = build_crate(light, dark)
    print("Crate triangles:", sum(len(p.vertices) - 2 for p in crate.data.polygons))

    preview_only = setup_preview_scene(crate)
    bpy.ops.render.render(write_still=True)
    print("Preview saved:", OUT_PREVIEW)

    os.makedirs(os.path.dirname(OUT_GLB), exist_ok=True)
    bpy.ops.object.select_all(action="DESELECT")
    crate.select_set(True)
    bpy.ops.export_scene.gltf(filepath=OUT_GLB, export_format="GLB", use_selection=True,
                              export_apply=True)
    print("GLB exported:", OUT_GLB)

    for obj in preview_only:
        obj.hide_render = True
    bpy.ops.wm.save_as_mainfile(filepath=OUT_BLEND)
    print("Blend saved:", OUT_BLEND)


main()

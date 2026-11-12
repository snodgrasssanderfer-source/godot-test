"""Turns the greybox exported from Godot into a .blend to start remodelling from.

  godot --headless --path fps res://world/town/town.tscn -- --export-gltf=$PWD/art/town/blender/town_greybox.glb
  blender --background --factory-startup --python art/town/blender/make_blend.py

Writes art/town/blender/town_greybox.blend (one collection per lot, one object
per lot and material tag, a "markers" collection of named empties) and a
top-down preview, town_greybox_preview.jpg. Rules for sending a remodelled
town back to the game: docs/blender_town_workflow.md.
"""
import math
import os

import bpy
from mathutils import Vector

HERE = os.path.dirname(os.path.abspath(__file__))
bpy.ops.wm.read_factory_settings(use_empty=True)
bpy.ops.import_scene.gltf(filepath=os.path.join(HERE, "town_greybox.glb"))
scene = bpy.context.scene
scene.unit_settings.system = "METRIC"

markers = bpy.data.collections.new("markers")
scene.collection.children.link(markers)
lots = {}
for ob in list(scene.objects):
    if ob.type == "EMPTY" and ob.name.startswith("marker_"):
        target = markers
        ob.empty_display_type = "PLAIN_AXES"
        ob.empty_display_size = 0.6
        ob.show_name = True
    elif ob.type == "MESH" and "__" in ob.name:
        lot = ob.name.split("__")[0]
        if lot not in lots:
            lots[lot] = bpy.data.collections.new(lot)
            scene.collection.children.link(lots[lot])
        target = lots[lot]
    else:
        continue
    world = ob.matrix_world.copy()
    for c in list(ob.users_collection):
        c.objects.unlink(ob)
    target.objects.link(ob)
    ob.parent = None                      # keep everything in world space: positions must not move
    ob.matrix_world = world
for ob in list(scene.objects):            # the now-empty lot and root nodes
    if ob.type == "EMPTY" and not ob.name.startswith("marker_") and not ob.children:
        bpy.data.objects.remove(ob)
for ob in list(scene.objects):
    if ob.type == "EMPTY" and not ob.name.startswith("marker_") and not ob.children:
        bpy.data.objects.remove(ob)

meshes = [o for o in scene.objects if o.type == "MESH"]
print("BLEND %d lots, %d mesh objects, %d markers, materials: %s" % (
    len(lots), len(meshes), len(markers.objects), ", ".join(sorted(m.name for m in bpy.data.materials))))
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(HERE, "town_greybox.blend"))

# preview from above (north is up: Godot -Z is Blender +Y)
cam_d = bpy.data.cameras.new("preview")
cam_d.type = "ORTHO"
cam_d.ortho_scale = 260.0
cam = bpy.data.objects.new("preview", cam_d)
cam.location = Vector((10.0, 0.0, 200.0))
scene.collection.objects.link(cam)
scene.camera = cam
sun_d = bpy.data.lights.new("sun", "SUN")
sun_d.energy = 3.0
sun = bpy.data.objects.new("sun", sun_d)
sun.rotation_euler = (math.radians(40), 0, math.radians(30))
scene.collection.objects.link(sun)
world = bpy.data.worlds.new("w")
world.use_nodes = True
world.node_tree.nodes["Background"].inputs[0].default_value = (0.5, 0.52, 0.55, 1)
world.node_tree.nodes["Background"].inputs[1].default_value = 0.6
scene.world = world
scene.render.engine = "CYCLES"
scene.cycles.samples = 12
scene.cycles.device = "CPU"
scene.render.resolution_x = scene.render.resolution_y = 1100
scene.view_settings.view_transform = "Standard"
scene.render.filepath = os.path.join(HERE, "town_greybox_preview.jpg")
bpy.ops.render.render(write_still=True)

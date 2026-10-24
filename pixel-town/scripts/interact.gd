class_name Interact
## Helpers for things the crosshair can target. The player's ray hits bodies on
## physics layer 2 ("interact") and calls the node stored in their "target" meta.

const LAYER := 2


static func add_hitbox(target: Node3D, size: Vector3, offset := Vector3.ZERO) -> StaticBody3D:
	var body := StaticBody3D.new()
	body.collision_layer = LAYER
	body.collision_mask = 0
	body.set_meta("target", target)
	var shape := CollisionShape3D.new()
	var box := BoxShape3D.new()
	box.size = size
	shape.shape = box
	shape.position = offset
	body.add_child(shape)
	target.add_child(body)
	return body

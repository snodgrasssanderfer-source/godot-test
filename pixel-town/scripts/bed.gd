extends Node3D
## The bed: sleeping ends the day and saves the game.


func _ready() -> void:
	add_child(preload("res://assets/models/bed.glb").instantiate())
	Interact.add_hitbox(self, Vector3(1.3, 0.9, 2.3), Vector3(0, 0.4, 0))


func action_label() -> String:
	return "睡觉"


func action_enabled() -> bool:
	return true


func interact(main: Node) -> void:
	main.start_sleep()

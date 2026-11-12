class_name TownAuto
extends Node
## Scripted runs of the town. Placeholder while the layout is being checked.

func start(_scene: Node3D, _mode: String, _arg: String) -> void:
	await get_tree().create_timer(0.5).timeout
	get_tree().quit()

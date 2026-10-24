extends Node3D
## Shipping bin: everything harvested goes in, and is paid out next morning.


func _ready() -> void:
	add_child(preload("res://assets/models/shipping_bin.glb").instantiate())
	Interact.add_hitbox(self, Vector3(1.3, 1.0, 0.95), Vector3(0, 0.45, 0))


func action_label() -> String:
	return "出货" if Game.crops > 0 else "出货箱"


func action_enabled() -> bool:
	return Game.crops > 0


func interact(main: Node) -> void:
	var count := Game.ship_all()
	Sfx.play("ship")
	Fx.burst(get_parent(), global_position + Vector3(0, 0.8, 0), "sparkle")
	main.hud.toast("出货 %s ×%d，明早结算 %d 金币" % [Game.CROP_NAME, count, count * Game.CROP_PRICE])
	main.player.nudge()

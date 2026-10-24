extends Node2D
## Builds the level from a list of rectangles and resets the player on falling.

const FALL_LIMIT_Y := 900.0

# x, y, width, height (top-left corner, in pixels)
const PLATFORMS := [
	Rect2(-200, 500, 900, 80),   # ground, left part
	Rect2(850, 500, 700, 80),    # ground, right part (gap in between)
	Rect2(300, 380, 160, 24),
	Rect2(560, 290, 160, 24),
	Rect2(820, 200, 160, 24),
	Rect2(1100, 330, 200, 24),
]

@onready var level: Node2D = $Level
@onready var player: CharacterBody2D = $Player

var spawn_point: Vector2


func _ready() -> void:
	spawn_point = player.position
	for rect in PLATFORMS:
		level.add_child(_make_platform(rect))
	print("Box Jumper ready: %d platforms" % PLATFORMS.size())


func _physics_process(_delta: float) -> void:
	if player.position.y > FALL_LIMIT_Y:
		player.reset_to(spawn_point)


func _make_platform(rect: Rect2) -> StaticBody2D:
	var body := StaticBody2D.new()
	body.position = rect.position + rect.size / 2.0

	var shape := RectangleShape2D.new()
	shape.size = rect.size
	var collision := CollisionShape2D.new()
	collision.shape = shape
	body.add_child(collision)

	var visual := ColorRect.new()
	visual.color = Color(0.3, 0.55, 0.25)
	visual.size = rect.size
	visual.position = -rect.size / 2.0
	body.add_child(visual)
	return body

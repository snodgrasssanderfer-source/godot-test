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

const CRATE_SIZE := 48.0

# Bottom-center positions of the crates (on the ground / platforms).
const CRATES := [
	Vector2(640, 500),     # obstacle before the gap
	Vector2(1260, 330),    # on the high right platform
	Vector2(1440, 500),    # little stack at the end
	Vector2(1440, 452),
	Vector2(1492, 500),
]

@onready var crate_render: SubViewport = $CrateRender
@onready var level: Node2D = $Level
@onready var player: CharacterBody2D = $Player

var spawn_point: Vector2


func _ready() -> void:
	spawn_point = player.position
	for rect in PLATFORMS:
		level.add_child(_make_platform(rect))
	for point in CRATES:
		level.add_child(_make_crate(point))
	print("Box Jumper ready: %d platforms, %d crates" % [PLATFORMS.size(), CRATES.size()])


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


## A solid crate whose picture is the Blender model rendered by $CrateRender.
func _make_crate(bottom_center: Vector2) -> StaticBody2D:
	var body := StaticBody2D.new()
	body.position = bottom_center - Vector2(0, CRATE_SIZE / 2.0)

	var shape := RectangleShape2D.new()
	shape.size = Vector2(CRATE_SIZE, CRATE_SIZE)
	var collision := CollisionShape2D.new()
	collision.shape = shape
	body.add_child(collision)

	var sprite := Sprite2D.new()
	sprite.texture = crate_render.get_texture()
	sprite.scale = Vector2.ONE * (CRATE_SIZE * 1.4 / crate_render.size.x)
	body.add_child(sprite)
	return body

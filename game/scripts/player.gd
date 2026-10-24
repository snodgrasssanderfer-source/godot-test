extends CharacterBody2D
## A box that moves left/right and jumps.

const SIZE := Vector2(40, 40)
const SPEED := 300.0
const JUMP_VELOCITY := -620.0

var gravity: float = ProjectSettings.get_setting("physics/2d/default_gravity")


func _ready() -> void:
	var shape := RectangleShape2D.new()
	shape.size = SIZE
	var collision := CollisionShape2D.new()
	collision.shape = shape
	add_child(collision)

	var visual := ColorRect.new()
	visual.color = Color(0.9, 0.3, 0.2)
	visual.size = SIZE
	visual.position = -SIZE / 2.0
	add_child(visual)


func _physics_process(delta: float) -> void:
	if not is_on_floor():
		velocity.y += gravity * delta
	if Input.is_action_just_pressed("jump") and is_on_floor():
		velocity.y = JUMP_VELOCITY
	velocity.x = Input.get_axis("move_left", "move_right") * SPEED
	move_and_slide()


func reset_to(point: Vector2) -> void:
	position = point
	velocity = Vector2.ZERO
	print("Player fell, reset to spawn")

class_name Fx
## One-shot particle bursts used as feedback for farm actions.

const KINDS := {
	"dirt": {"colors": [Color("8c5f3e"), Color("b98a5e")], "amount": 14, "speed": 2.2,
		"spread": 50.0, "gravity": -9.0, "life": 0.6, "size": 0.06},
	"seed": {"colors": [Color("f2e2b0"), Color("fff6d8")], "amount": 8, "speed": 1.4,
		"spread": 40.0, "gravity": -6.0, "life": 0.5, "size": 0.04},
	"water": {"colors": [Color("8fd3ff"), Color("c9ecff")], "amount": 22, "speed": 0.6,
		"spread": 25.0, "gravity": -9.0, "life": 0.55, "size": 0.05, "from_above": true},
	"harvest": {"colors": [Color("7ccd5a"), Color("ffe27a"), Color("ff9fb2")], "amount": 18,
		"speed": 2.6, "spread": 45.0, "gravity": -5.0, "life": 0.8, "size": 0.06},
	"sparkle": {"colors": [Color("fff1a8"), Color("ffffff")], "amount": 14, "speed": 1.8,
		"spread": 70.0, "gravity": -1.5, "life": 0.9, "size": 0.05},
}


static func burst(parent: Node3D, pos: Vector3, kind: String) -> void:
	var k: Dictionary = KINDS[kind]
	var mat := StandardMaterial3D.new()
	mat.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	mat.vertex_color_use_as_albedo = true
	var mesh := BoxMesh.new()
	mesh.size = Vector3.ONE * k.size
	mesh.material = mat
	for color in k.colors:
		var p := CPUParticles3D.new()
		p.mesh = mesh
		p.one_shot = true
		p.explosiveness = 0.85
		p.amount = maxi(2, k.amount / k.colors.size())
		p.lifetime = k.life
		p.direction = Vector3.DOWN if k.get("from_above", false) else Vector3.UP
		p.spread = k.spread
		p.initial_velocity_min = k.speed * 0.6
		p.initial_velocity_max = k.speed
		p.gravity = Vector3(0, k.gravity, 0)
		p.scale_amount_min = 0.7
		p.scale_amount_max = 1.2
		p.color = color
		p.emission_shape = CPUParticles3D.EMISSION_SHAPE_BOX
		p.emission_box_extents = Vector3(0.3, 0.05, 0.3)
		parent.add_child(p)
		p.global_position = pos + (Vector3(0, 1.0, 0) if k.get("from_above", false) else Vector3.ZERO)
		p.emitting = true
		p.finished.connect(p.queue_free)

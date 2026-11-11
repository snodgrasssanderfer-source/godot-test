class_name Body
extends Node
## The player's injuries, as a child of Player. The rules live in BodySim; this
## node keeps it running, tells it what the player is doing (so wounds can tear
## and hurt), turns its noises into real noise, and feeds the state back out
## through the camera and the screen. There is no health bar: what you see and
## hear is all you get.
##
## Player and WeaponRig only ever read `mods` (a BodySim.Mods), never the wounds.

signal died(cause: StringName)
## The character grunted, coughed or screamed (radius in metres).
signal voiced(what: StringName, radius: float)

const GRUNT_RADIUS := 3.0
const COUGH_RADIUS := 6.0
const SCREAM_RADIUS := 8.0
const GRUNT_GAP := 3.0             # seconds between grunts at most
const SPRINT_STRAIN_EVERY := 5.0   # seconds of sprinting per chance to tear a leg wound
const LEG_TEAR_CHANCE := 0.15
const ARM_TEAR_CHANCE := 0.10
## Degrees the aim wanders per unit of sway above normal.
const WOBBLE_PER_SWAY := 0.18

var sim := BodySim.new()
var mods: BodySim.Mods:
	get:
		return sim.mods
var player: Player

var _grunt_wait := 0.0
var _sprint_time := 0.0
var _clock := 0.0
var _drift := Vector2.ZERO
var _drift_to := Vector2.ZERO
var _drift_in := 0.0
var _blackout := 0.0
var _atmosphere: Node


func setup(owner_player: Player) -> void:
	player = owner_player
	sim.rng.randomize()
	sim.vocalised.connect(_on_vocalised)
	sim.died.connect(func(cause: StringName) -> void: died.emit(cause))
	sim.stumbled.connect(func() -> void: player.add_view_kick(-2.5, randf_range(-2.0, 2.0), 6.0))


## A creature's attack. See BodySim.hit(); also jolts the view.
func hit(damage: float, kind: BodySim.Kind, attack: BodySim.Attack) -> Dictionary:
	var result := sim.hit(damage, kind, attack, player.aim_amount > 0.5)
	_react(result, damage)
	return result


func hit_part(part: BodySim.Part, damage: float, kind: BodySim.Kind) -> Dictionary:
	var result := sim.hit_part(part, damage, kind)
	_react(result, damage)
	return result


func _react(result: Dictionary, damage: float) -> void:
	if result.is_empty():
		return
	var jolt := clampf(damage * 0.12, 0.8, 4.5)
	player.add_view_kick(-jolt, randf_range(-jolt, jolt), 9.0)
	Sfx.play(&"hit_flesh", -2.0)


## Whole again (respawning, tests).
func reset() -> void:
	sim.reset()
	_grunt_wait = 0.0
	_sprint_time = 0.0
	_drift = Vector2.ZERO


## Called by Player and WeaponRig when the body does something strenuous:
## &"slide", &"jump", &"mantle", &"land" for the legs, &"swing", &"reload" for the arms.
func exert(what: StringName) -> void:
	var legs := what in [&"slide", &"jump", &"mantle", &"land"]
	if what == &"slide":
		sim.strain_legs(LEG_TEAR_CHANCE)
	elif what == &"swing":
		sim.strain_arms(ARM_TEAR_CHANCE)
	var hurts := 0.0
	if legs:
		hurts = sim.pain(BodySim.Part.LEG_L) + sim.pain(BodySim.Part.LEG_R)
	else:
		hurts = sim.pain(BodySim.Part.ARM_L) + sim.pain(BodySim.Part.ARM_R)
	if hurts > 0.0:
		_grunt()


func _grunt() -> void:
	if _grunt_wait > 0.0 or sim.mods.numb or sim.dead:
		return
	_grunt_wait = GRUNT_GAP
	player.make_noise(GRUNT_RADIUS, 0.0, &"grunt")
	voiced.emit(&"grunt", GRUNT_RADIUS)


func _on_vocalised(what: StringName) -> void:
	var radius := SCREAM_RADIUS if what == &"scream" else COUGH_RADIUS
	player.make_noise(radius, 0.0, what)
	voiced.emit(what, radius)


func _physics_process(delta: float) -> void:
	if player == null:
		return
	sim.advance(delta)
	_grunt_wait = maxf(_grunt_wait - delta, 0.0)
	if player.sprinting and player.horizontal_speed() > player.walk_speed:
		_sprint_time += delta
		if _sprint_time >= SPRINT_STRAIN_EVERY:
			_sprint_time = 0.0
			sim.strain_legs(LEG_TEAR_CHANCE)
			if sim.pain(BodySim.Part.LEG_L) + sim.pain(BodySim.Part.LEG_R) > 0.0:
				_grunt()
	else:
		_sprint_time = 0.0


func _process(delta: float) -> void:
	if player == null:
		return
	delta = minf(delta, 0.05)
	var m := sim.mods
	_clock += delta

	# The aim wanders with sway: two slow sines per axis, never quite repeating.
	var amp := deg_to_rad(WOBBLE_PER_SWAY) * maxf(m.sway_scale - 1.0, 0.0)
	if m.woozy:
		amp += deg_to_rad(0.6)
	var t := _clock
	player.view_wobble = Vector2(sin(t * 2.3 + 2.0) + 0.5 * sin(t * 3.7), sin(t * 1.9) + 0.5 * sin(t * 3.1 + 1.0)) \
			* (amp / 1.5)

	# Close to passing out, the view itself slides off target.
	if m.aim_drift > 0.0 and not sim.dead:
		_drift_in -= delta
		if _drift_in <= 0.0:
			_drift_in = randf_range(1.2, 2.6)
			_drift_to = Vector2.from_angle(randf() * TAU) * deg_to_rad(0.9) * m.aim_drift
		_drift = _drift.lerp(_drift_to, 1.0 - exp(-1.5 * delta))
		player.nudge_look(_drift.x * delta, _drift.y * delta)
	else:
		_drift = Vector2.ZERO

	# Screen: colour drains with blood, the edges close in with the pulse.
	if _atmosphere == null or not is_instance_valid(_atmosphere):
		_atmosphere = get_tree().get_first_node_in_group(&"atmosphere")
	_blackout = lerpf(_blackout, m.blackout, 1.0 - exp(-(30.0 if m.blackout > _blackout else 9.0) * delta))
	if _atmosphere != null and _atmosphere.get(&"grade") != null:
		var grade: ShaderMaterial = _atmosphere.grade
		var pulse := 0.85 + 0.15 * sin(_clock * TAU * lerpf(1.1, 2.2, m.heartbeat))
		grade.set_shader_parameter(&"extra_desaturate", m.desaturate)
		grade.set_shader_parameter(&"extra_vignette", m.tunnel * pulse)
		grade.set_shader_parameter(&"blackout", _blackout)

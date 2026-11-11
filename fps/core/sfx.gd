extends Node
## Plays sound effects by name from res://assets/sfx/. Flat sounds are for the
## player's own hands and feet; world sounds are positioned in 3D.

const DIR := "res://assets/sfx/"

var _streams := {}
var _flat: Array[AudioStreamPlayer] = []
var _world: Array[AudioStreamPlayer3D] = []
var _next_flat := 0
var _next_world := 0


func _ready() -> void:
	for i in 14:
		var p := AudioStreamPlayer.new()
		add_child(p)
		_flat.append(p)
	for i in 18:
		var p := AudioStreamPlayer3D.new()
		p.unit_size = 5.0
		p.max_distance = 90.0
		add_child(p)
		_world.append(p)


func _stream(sound: StringName) -> AudioStream:
	if not _streams.has(sound):
		var path := DIR + String(sound) + ".wav"
		_streams[sound] = load(path) if ResourceLoader.exists(path) else null
		if _streams[sound] == null:
			push_warning("Sfx: missing sound '%s'" % sound)
	return _streams[sound]


func play(sound: StringName, volume_db := 0.0, pitch_jitter := 0.05) -> void:
	var stream := _stream(sound)
	if stream == null:
		return
	var p := _flat[_next_flat]
	_next_flat = (_next_flat + 1) % _flat.size()
	p.stream = stream
	p.volume_db = volume_db
	p.pitch_scale = 1.0 + randf_range(-pitch_jitter, pitch_jitter)
	p.play()


func play_at(sound: StringName, at: Vector3, volume_db := 0.0, pitch_jitter := 0.08) -> void:
	var stream := _stream(sound)
	if stream == null:
		return
	var p := _world[_next_world]
	_next_world = (_next_world + 1) % _world.size()
	p.stream = stream
	p.volume_db = volume_db
	p.pitch_scale = 1.0 + randf_range(-pitch_jitter, pitch_jitter)
	p.global_position = at
	p.play()

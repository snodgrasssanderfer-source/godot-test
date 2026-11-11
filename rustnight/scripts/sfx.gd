extends Node
## Sound helper (autoload "Sfx"): cached streams + small pools of players.
## 3D players have to live inside the game's SubViewport (that is where the
## audio listener is), so the 3D pool is re-created per run via attach().

var _cache := {}
var _pool2d: Array[AudioStreamPlayer] = []
var _pool3d: Array[AudioStreamPlayer3D] = []
var _i2 := 0
var _i3 := 0


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	for i in 12:
		var p := AudioStreamPlayer.new()
		add_child(p)
		_pool2d.append(p)


func attach(world: Node) -> void:
	_pool3d.clear()
	for i in (14 if G.low_spec else 24):
		var p := AudioStreamPlayer3D.new()
		p.attenuation_model = AudioStreamPlayer3D.ATTENUATION_INVERSE_DISTANCE
		p.unit_size = 5.0
		p.panning_strength = 1.2
		world.add_child(p)
		_pool3d.append(p)


func stream(sname: String) -> AudioStream:
	if not _cache.has(sname):
		_cache[sname] = load("res://sfx/%s.wav" % sname)
	return _cache[sname]


func play(sname: String, vol_db: float = 0.0, pitch: float = 1.0) -> void:
	var p := _pool2d[_i2]
	_i2 = (_i2 + 1) % _pool2d.size()
	p.stream = stream(sname)
	p.volume_db = vol_db
	p.pitch_scale = pitch
	p.play()


func play_var(sname: String, vol_db: float = 0.0, spread: float = 0.08) -> void:
	play(sname, vol_db, randf_range(1.0 - spread, 1.0 + spread))


func play_at(sname: String, pos: Vector3, vol_db: float = 0.0, pitch: float = 1.0, max_dist: float = 45.0) -> void:
	if _pool3d.is_empty() or not is_instance_valid(_pool3d[0]):
		return
	var p := _pool3d[_i3]
	_i3 = (_i3 + 1) % _pool3d.size()
	p.stream = stream(sname)
	p.volume_db = vol_db
	p.pitch_scale = pitch
	p.max_distance = max_dist
	p.global_position = pos
	p.play()


## Dedicated looping 2D player (ambience). Caller owns volume.
func make_loop(sname: String, parent: Node) -> AudioStreamPlayer:
	var s: AudioStreamWAV = stream(sname).duplicate()
	s.loop_mode = AudioStreamWAV.LOOP_FORWARD
	s.loop_begin = 0
	s.loop_end = int(s.get_length() * s.mix_rate)
	var p := AudioStreamPlayer.new()
	p.stream = s
	p.volume_db = -60.0
	parent.add_child(p)
	p.play()
	return p

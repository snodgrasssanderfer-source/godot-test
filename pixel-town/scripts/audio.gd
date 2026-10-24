extends Node
## Sound effects and ambience (autoload "Sfx").
## Birds + wind by day, crickets at night, crossfaded by the in-game hour.

const SFX_NAMES := ["hoe", "seed", "water", "harvest", "ship", "coin", "talk", "step",
	"ui", "sleep", "wake"]
const POOL_SIZE := 8

var _streams := {}
var _pool: Array[AudioStreamPlayer] = []
var _next := 0
var _amb_day: AudioStreamPlayer
var _amb_night: AudioStreamPlayer


func _ready() -> void:
	for n in SFX_NAMES:
		_streams[n] = load("res://assets/audio/%s.wav" % n)
	for i in POOL_SIZE:
		var p := AudioStreamPlayer.new()
		add_child(p)
		_pool.append(p)
	_amb_day = _make_loop("res://assets/audio/amb_day.wav")
	_amb_night = _make_loop("res://assets/audio/amb_night.wav")
	update_ambience(Game.hour())
	Game.time_changed.connect(func(_m): update_ambience(Game.hour()))


func play(sfx_name: String, volume_db := 0.0, pitch_jitter := 0.06) -> void:
	var p := _pool[_next]
	_next = (_next + 1) % POOL_SIZE
	p.stream = _streams[sfx_name]
	p.volume_db = volume_db
	p.pitch_scale = 1.0 + randf_range(-pitch_jitter, pitch_jitter)
	p.play()


## 1.0 during the day, fading to 0.0 through the evening (19:00-20:30) and
## back up at dawn (05:30-06:30).
static func day_weight(h: float) -> float:
	if h < 19.0:
		return clampf((h - 5.5) / 1.0, 0.0, 1.0)
	return clampf(1.0 - (h - 19.0) / 1.5, 0.0, 1.0)


func update_ambience(h: float) -> void:
	var w := day_weight(h)
	_amb_day.volume_db = linear_to_db(maxf(w, 0.0001)) - 6.0
	_amb_night.volume_db = linear_to_db(maxf(1.0 - w, 0.0001)) - 4.0


## Looping is set in the .wav.import files (edit/loop_mode=2); restarting on
## `finished` is a fallback in case the import setting is lost.
func _make_loop(path: String) -> AudioStreamPlayer:
	var p := AudioStreamPlayer.new()
	p.stream = load(path)
	add_child(p)
	p.finished.connect(p.play)
	p.play()
	return p


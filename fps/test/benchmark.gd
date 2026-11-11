class_name Benchmark
extends Node
## Flies a camera down the street on a fixed path through every lighting mood,
## once per quality setup, and reports average fps, 1% low and the worst frame
## for each. The numbers go to the screen, the log and user://benchmark.txt.
##
## The target is 120 fps average and 1% lows of at least 100 at 1080p.

signal finished(report: String)

## name, hour, power on, flashlight on
const MOODS := [
	["Day fog", 11.0, true, false], ["Dusk", 18.0, true, false],
	["Night, lamps", 23.0, true, false], ["Blackout + torch", 23.0, false, true],
]
## name, volumetric fog, SDFGI
const SETUPS := [
	["High", true, false], ["High + SDFGI", true, true], ["No volumetric fog", false, false],
]
const PATH_FROM := Vector3(-0.9, 1.62, 4.0)
const PATH_TO := Vector3(0.9, 1.62, -72.0)
const TARGET_AVG := 120.0
const TARGET_LOW := 100.0

var seconds_per_run := 8.0
var running := false
var report := ""
## Progress line for the overlay while it runs.
var status := ""

var _camera: Camera3D
var _torch: SpotLight3D


func run(atmosphere: Atmosphere) -> void:
	if running:
		return
	running = true
	var saved := [atmosphere.time_of_day, atmosphere.power_on, atmosphere.day_length_minutes,
			atmosphere.volumetric_fog, atmosphere.sdfgi]
	var previous := get_viewport().get_camera_3d()
	atmosphere.day_length_minutes = 0.0

	_camera = Camera3D.new()
	_camera.fov = 65.0
	_camera.far = 400.0
	add_child(_camera)
	_camera.make_current()
	_torch = SpotLight3D.new()
	_torch.spot_angle = 19.0
	_torch.spot_angle_attenuation = 0.5
	_torch.spot_range = 45.0
	_torch.light_color = Color(0.92, 0.96, 1.0)
	_torch.light_energy = 22.0
	_torch.light_volumetric_fog_energy = 0.5
	_torch.shadow_enabled = true
	_torch.position = Vector3(0.22, -0.20, -0.15)
	_camera.add_child(_torch)

	var size := get_viewport().get_visible_rect().size
	var lines: Array[String] = [
		"Greyfog benchmark   %s" % Time.get_datetime_string_from_system(false, true),
		"%s   %s %s" % [RenderingServer.get_video_adapter_name(), RenderingServer.get_current_rendering_driver_name(),
				RenderingServer.get_video_adapter_api_version()],
		"%d x %d   Godot %s   %s" % [size.x, size.y, Engine.get_version_info().string, OS.get_name()],
		"",
		"%-20s %-18s %8s %8s %8s" % ["setup", "scene", "avg", "1% low", "worst"],
	]
	var all_ok := true
	var run_index := 0
	for setup in SETUPS:
		atmosphere.volumetric_fog = setup[1]
		atmosphere.sdfgi = setup[2]
		for mood in MOODS:
			run_index += 1
			status = "Benchmark %d / %d   %s, %s" % [run_index, SETUPS.size() * MOODS.size(), setup[0], mood[0]]
			atmosphere.power_on = mood[2]
			atmosphere.time_of_day = mood[1]
			_torch.visible = mood[3]
			_place(0.0)
			# Let the fog history and any shader compilation settle first.
			await _wait(minf(1.5, seconds_per_run * 0.25))
			var times := await _measure()
			var stats := _stats(times)
			var is_target: bool = setup[0] == SETUPS[0][0]
			if is_target and (stats[0] < TARGET_AVG or stats[1] < TARGET_LOW):
				all_ok = false
			lines.append("%-20s %-18s %8.1f %8.1f %8.1f" % [setup[0], mood[0], stats[0], stats[1], stats[2]])
		lines.append("")
	lines.append("Target (High): avg >= %d, 1%% low >= %d   ->   %s" % [TARGET_AVG, TARGET_LOW,
			"PASS" if all_ok else "BELOW TARGET"])
	report = "\n".join(lines)

	atmosphere.time_of_day = saved[0]
	atmosphere.power_on = saved[1]
	atmosphere.day_length_minutes = saved[2]
	atmosphere.volumetric_fog = saved[3]
	atmosphere.sdfgi = saved[4]
	_camera.queue_free()
	if is_instance_valid(previous):
		previous.make_current()

	var file := FileAccess.open("user://benchmark.txt", FileAccess.WRITE)
	if file:
		file.store_string(report + "\n")
		file.close()
	print(report)
	print("Saved to ", ProjectSettings.globalize_path("user://benchmark.txt"))
	status = ""
	running = false
	finished.emit(report)


## Camera pose at t = 0..1 along the street, with a slow look from side to side.
func _place(t: float) -> void:
	var at := PATH_FROM.lerp(PATH_TO, t)
	var yaw := sin(t * TAU * 1.5) * deg_to_rad(28.0)
	_camera.global_transform = Transform3D(Basis(Vector3.UP, yaw) * Basis(Vector3.RIGHT, deg_to_rad(2.0)), at)


func _wait(seconds: float) -> void:
	var start := Time.get_ticks_usec()
	while Time.get_ticks_usec() - start < seconds * 1e6:
		await get_tree().process_frame


## One pass down the street. Returns every frame's duration in milliseconds.
func _measure() -> PackedFloat32Array:
	var times := PackedFloat32Array()
	var start := Time.get_ticks_usec()
	var last := start
	while true:
		await get_tree().process_frame
		var now := Time.get_ticks_usec()
		times.append((now - last) / 1000.0)
		last = now
		var t := (now - start) / (seconds_per_run * 1e6)
		if t >= 1.0:
			break
		_place(t)
	return times


## [average fps, 1% low fps, slowest frame as fps]
static func _stats(times: PackedFloat32Array) -> Array[float]:
	if times.is_empty():
		return [0.0, 0.0, 0.0]
	var total := 0.0
	for ms in times:
		total += ms
	var sorted := times.duplicate()
	sorted.sort()
	var worst_count := maxi(int(ceil(sorted.size() * 0.01)), 1)
	var worst_total := 0.0
	for i in worst_count:
		worst_total += sorted[sorted.size() - 1 - i]
	var slowest: float = maxf(sorted[sorted.size() - 1], 0.001)
	return [times.size() * 1000.0 / maxf(total, 0.001), worst_count * 1000.0 / maxf(worst_total, 0.001),
			1000.0 / slowest]

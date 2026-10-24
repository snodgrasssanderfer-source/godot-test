extends Node
## Global game state (autoload "Game"): clock, money, backpack, farm plots and saving.

signal time_changed(minutes: float)
signal gold_changed(gold: int)
signal crops_changed(count: int)
signal plot_changed(index: int)

const SAVE_PATH := "user://save.json"
const DAY_START := 6.0 * 60.0          # 06:00
const DAY_END := 24.0 * 60.0           # the clock rests at midnight; no forced sleep
const REAL_SECONDS_PER_DAY := 600.0    # 06:00 -> 24:00 takes about 10 real minutes
const MINUTES_PER_SECOND := (DAY_END - DAY_START) / REAL_SECONDS_PER_DAY
const PLOT_COUNT := 16
const MATURE_STAGE := 3
const CROP_NAME := "粉萝卜"
const CROP_PRICE := 40

var day := 1
var minutes := DAY_START
var gold := 0
var crops := 0            # harvested radishes in the backpack
var shipped := 0          # radishes in the shipping bin, paid out next morning
var talked_today := false
var clock_running := true
# Each plot: {"tilled": bool, "stage": int (-1 = empty), "watered": bool}
var plots: Array[Dictionary] = []


func _ready() -> void:
	_reset()
	load_game()


func _process(delta: float) -> void:
	if clock_running and minutes < DAY_END:
		minutes = minf(minutes + delta * MINUTES_PER_SECOND, DAY_END)
		time_changed.emit(minutes)


func hour() -> float:
	return minutes / 60.0


func clock_text() -> String:
	var m := floori(minutes / 10.0) * 10
	return "%02d:%02d" % [floori(m / 60.0) % 24, m % 60]


# --- Farming -------------------------------------------------------------------

func till(i: int) -> void:
	plots[i].tilled = true
	plot_changed.emit(i)


func plant(i: int) -> void:
	plots[i].stage = 0
	plots[i].watered = false
	plot_changed.emit(i)


func water(i: int) -> void:
	plots[i].watered = true
	plot_changed.emit(i)


func harvest(i: int) -> void:
	plots[i].stage = -1
	plots[i].watered = false
	crops += 1
	crops_changed.emit(crops)
	plot_changed.emit(i)


func ship_all() -> int:
	var count := crops
	shipped += count
	crops = 0
	crops_changed.emit(crops)
	return count


## Ends the day: watered crops grow one stage, shipped crops are paid out,
## the clock goes back to morning and the game is saved. Returns the income.
func sleep() -> int:
	for p in plots:
		if p.stage >= 0 and p.stage < MATURE_STAGE and p.watered:
			p.stage += 1
		p.watered = false
	var income := shipped * CROP_PRICE
	gold += income
	shipped = 0
	day += 1
	minutes = DAY_START
	talked_today = false
	save_game()
	gold_changed.emit(gold)
	for i in PLOT_COUNT:
		plot_changed.emit(i)
	time_changed.emit(minutes)
	return income


# --- Saving --------------------------------------------------------------------

func save_game() -> void:
	var data := {
		"version": 1, "day": day, "gold": gold, "crops": crops,
		"shipped": shipped, "plots": plots,
	}
	var file := FileAccess.open(SAVE_PATH, FileAccess.WRITE)
	if file == null:
		push_warning("Could not save: %s" % error_string(FileAccess.get_open_error()))
		return
	file.store_string(JSON.stringify(data))
	print("Saved day %d" % day)


func load_game() -> void:
	if not FileAccess.file_exists(SAVE_PATH):
		return
	var data = JSON.parse_string(FileAccess.get_file_as_string(SAVE_PATH))
	if typeof(data) != TYPE_DICTIONARY:
		push_warning("Save file is unreadable, starting fresh")
		return
	day = int(data.get("day", 1))
	gold = int(data.get("gold", 0))
	crops = int(data.get("crops", 0))
	shipped = int(data.get("shipped", 0))
	var saved: Array = data.get("plots", [])
	for i in mini(saved.size(), PLOT_COUNT):
		plots[i] = {
			"tilled": bool(saved[i].get("tilled", false)),
			"stage": int(saved[i].get("stage", -1)),
			"watered": bool(saved[i].get("watered", false)),
		}
	print("Loaded day %d, gold %d" % [day, gold])


func _reset() -> void:
	plots.clear()
	for i in PLOT_COUNT:
		plots.append({"tilled": false, "stage": -1, "watered": false})

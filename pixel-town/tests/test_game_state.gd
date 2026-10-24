extends SceneTree
## Headless check of the farming loop. Run from the repo root:
##   godot --headless --path pixel-town --script res://tests/test_game_state.gd
## Note: writes and then deletes user://save.json.

var _failures := 0


func _check(cond: bool, what: String) -> void:
	print(("  ok   " if cond else "  FAIL ") + what)
	if not cond:
		_failures += 1


func _init() -> void:
	var g = load("res://scripts/game_state.gd").new()
	g._reset()
	g.till(0)
	g.plant(0)
	_check(g.plots[0].stage == 0, "planted seed is stage 0")
	g.sleep()
	_check(g.plots[0].stage == 0, "unwatered crop does not grow")
	for d in 3:
		g.water(0)
		g.sleep()
	_check(g.plots[0].stage == g.MATURE_STAGE, "3 watered days -> mature")
	_check(not g.plots[0].watered, "watering resets each morning")
	g.harvest(0)
	_check(g.crops == 1 and g.plots[0].stage == -1 and g.plots[0].tilled, "harvest -> backpack, soil stays tilled")
	_check(g.ship_all() == 1 and g.crops == 0, "shipping empties the backpack")
	var income: int = g.sleep()
	_check(income == g.CROP_PRICE and g.gold == g.CROP_PRICE, "payout next morning")
	_check(g.day == 6 and is_equal_approx(g.minutes, g.DAY_START), "new day starts at 06:00")

	var loaded = load("res://scripts/game_state.gd").new()
	loaded._reset()
	loaded.load_game()
	_check(loaded.day == 6 and loaded.gold == g.CROP_PRICE and loaded.plots[0].tilled, "save/load round trip")
	DirAccess.remove_absolute(ProjectSettings.globalize_path(g.SAVE_PATH))
	g.free()
	loaded.free()
	print("FAILED: %d" % _failures if _failures else "ALL PASSED")
	quit(1 if _failures else 0)

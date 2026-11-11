class_name Survival
extends Node
## The body: health, hunger, thirst, fatigue, panic, endurance, wounds,
## infection. All rates are per in-game minute.

var p: Player
var health := 100.0
var hunger := 14.0
var thirst := 12.0
var fatigue := 8.0
var panic := 0.0
var endurance := 100.0
var pain := 0.0
var wounds: Array = []        # {kind, bleed, bandaged, age}
var infected := false
var infection := 0.0
var sick := 0.0
var calm_t := 0.0
var painkill_t := 0.0
var drunk := 0.0
var cause := "未知"
var _warned := {}


func tick(delta: float) -> void:
	var gm := delta / G.MINUTE_SECONDS
	hunger = minf(100.0, hunger + gm * 0.046)
	thirst = minf(100.0, thirst + gm * 0.072)
	fatigue = minf(100.0, fatigue + gm * 0.058)
	calm_t = maxf(0.0, calm_t - gm)
	painkill_t = maxf(0.0, painkill_t - gm)
	drunk = maxf(0.0, drunk - gm * 0.4)
	sick = maxf(0.0, sick - gm)

	# endurance (real-time, it is about sprinting and swinging)
	var rec := 0.0
	if p.sprinting:
		rec = -6.5
	elif p.h_speed() < 0.5:
		rec = 13.0
	else:
		rec = 8.0
	if rec > 0.0:
		rec *= clampf(1.25 - fatigue / 100.0, 0.35, 1.0)
	endurance = clampf(endurance + rec * delta, 0.0, 100.0)

	# panic (real-time)
	var threat := G.zombies.count_near(p.global_position, 9.0, true) if G.zombies else 0
	var gain := threat * 7.0
	if G.night > 0.6 and not p.flash_on and not p.indoor:
		gain += 1.2 if panic < 35.0 else 0.0
	if gain > 0.0:
		panic = minf(100.0, panic + gain * delta * (0.35 if calm_t > 0.0 else 1.0))
	else:
		panic = maxf(0.0, panic - delta * (9.0 if calm_t > 0.0 else 3.5))

	# wounds
	var bleed := 0.0
	var ptarget := 0.0
	for i in range(wounds.size() - 1, -1, -1):
		var w: Dictionary = wounds[i]
		w["age"] += gm
		if not w["bandaged"]:
			bleed += w["bleed"]
			if w["kind"] == "scratch" and w["age"] > 150.0:
				w["bleed"] = 0.0
		ptarget += {"scratch": 10.0, "cut": 24.0, "bite": 38.0}[w["kind"]] * (0.5 if w["bandaged"] else 1.0)
		if w["age"] > (360.0 if w["bandaged"] else 900.0):
			wounds.remove_at(i)
	if bleed > 0.0:
		health -= bleed * gm
		cause = "失血过多"
	if painkill_t > 0.0:
		ptarget *= 0.2
	pain = move_toward(pain, minf(ptarget, 100.0), gm * 2.0)

	# deprivation
	if hunger > 85.0:
		health -= gm * 0.03 * (hunger - 85.0) / 15.0
		cause = "饿死"
	if thirst > 85.0:
		health -= gm * 0.06 * (thirst - 85.0) / 15.0
		cause = "渴死"
	if sick > 0.0:
		health -= gm * 0.02

	# infection: quiet at first, then fever, then the end
	if infected:
		infection += gm * 100.0 / 1080.0
		if infection > 25.0:
			_warn("fever", "你觉得浑身发烫，伤口周围的皮肤在发黑……", Color(0.8, 0.5, 0.3))
			fatigue = minf(100.0, fatigue + gm * 0.04)
		if infection > 55.0:
			_warn("fever2", "高烧。视线发花。你知道这意味着什么。", Color(0.85, 0.3, 0.25))
			health -= gm * 0.07 * (infection - 55.0) / 45.0 * 3.0
			cause = "感染"
		if infection >= 100.0:
			health = 0.0
			cause = "感染"

	# natural recovery
	if bleed <= 0.0 and hunger < 65.0 and thirst < 65.0 and infection < 55.0 and sick <= 0.0:
		health = minf(100.0, health + gm * (0.07 if hunger < 35.0 and thirst < 35.0 else 0.035))

	if hunger > 70.0:
		_warn("hungry", "你饿得胃疼。", Color(0.8, 0.7, 0.45))
	if thirst > 70.0:
		_warn("thirsty", "嘴唇干裂。你需要水。", Color(0.8, 0.7, 0.45))
	if fatigue > 75.0:
		_warn("tired", "你困得睁不开眼。找张床。", Color(0.8, 0.7, 0.45))
	if health <= 0.0 and not p.dead:
		p.die(cause)


func _warn(key: String, text: String, color: Color) -> void:
	if not _warned.has(key):
		_warned[key] = true
		G.msg(text, color)


func bleeding() -> bool:
	for w in wounds:
		if not w["bandaged"] and w["bleed"] > 0.0:
			return true
	return false


## Zombie hit: damage plus a wound that may carry the infection.
func zombie_hit(dmg: float) -> void:
	health -= dmg
	cause = "被活活咬死"
	panic = minf(100.0, panic + 18.0)
	var r := randf()
	if r < 0.5:
		wounds.append({"kind": "scratch", "bleed": 0.035, "bandaged": false, "age": 0.0})
		G.msg("你被抓伤了。", Color(0.85, 0.6, 0.45))
		if randf() < 0.06:
			infected = true
	elif r < 0.83:
		wounds.append({"kind": "cut", "bleed": 0.11, "bandaged": false, "age": 0.0})
		G.msg("深深的撕裂伤——血流不止，需要绷带！", Color(0.9, 0.4, 0.3))
		if randf() < 0.22:
			infected = true
	else:
		wounds.append({"kind": "bite", "bleed": 0.17, "bandaged": false, "age": 0.0})
		G.msg("你被咬了。", Color(0.95, 0.2, 0.15))
		if randf() < 0.85:
			infected = true
	if health <= 0.0 and not p.dead:
		p.die(cause)


func spend(amount: float) -> void:
	endurance = maxf(0.0, endurance - amount)


## Extra weapon sway from the state you are in (1 = steady).
func unsteady() -> float:
	return panic / 100.0 * 1.6 + pain / 100.0 * 1.0 + (0.7 if endurance < 20.0 else 0.0) + drunk * 0.02 + (0.4 if fatigue > 80.0 else 0.0)


func move_mult() -> float:
	var m := 1.0
	if pain > 45.0:
		m *= 0.9
	if endurance < 8.0:
		m *= 0.85
	if fatigue > 85.0:
		m *= 0.9
	if infection > 55.0:
		m *= 0.88
	return m


func sleep(hours: float) -> void:
	fatigue = maxf(0.0, fatigue - hours * 14.0)
	hunger = minf(100.0, hunger + hours * 60.0 * 0.03)
	thirst = minf(100.0, thirst + hours * 60.0 * 0.045)
	endurance = 100.0
	panic = 0.0
	if not bleeding() and infection < 55.0:
		health = minf(100.0, health + hours * 4.0)
	for w in wounds:
		w["age"] += hours * 60.0
	if infected:
		infection += hours * 60.0 * 100.0 / 1080.0
	_warned.erase("tired")


## Uses one item; returns true if it was consumed.
func use(id: String) -> bool:
	var it: Dictionary = Items.DB[id]
	match it["t"]:
		"food", "drink":
			hunger = clampf(hunger + float(it.get("hunger", 0.0)), 0.0, 100.0)
			thirst = clampf(thirst + float(it.get("thirst", 0.0)), 0.0, 100.0)
			fatigue = clampf(fatigue + float(it.get("fatigue", 0.0)), 0.0, 100.0)
			panic = clampf(panic + float(it.get("panic", 0.0)), 0.0, 100.0)
			if id == "beer":
				drunk += 25.0
			if it.has("sick") and randf() < float(it["sick"]):
				sick = 240.0
				G.msg("胃里一阵翻涌。那东西不该吃的。", Color(0.75, 0.7, 0.4))
			Sfx.play("eat" if it["t"] == "food" else "drink", -4.0)
			G.emit_noise(p.global_position, 3.0, "eat")
			_warned.erase("hungry")
			_warned.erase("thirsty")
			return true
	match id:
		"bandage":
			var worst = null
			for w in wounds:
				if not w["bandaged"] and (worst == null or w["bleed"] > worst["bleed"]):
					worst = w
			if worst == null:
				G.msg("没有需要包扎的伤口。")
				return false
			worst["bandaged"] = true
			worst["age"] = 0.0
			Sfx.play("bandage", -3.0)
			G.msg("包扎好了。")
			return true
		"medkit":
			for w in wounds:
				w["bandaged"] = true
			health = minf(100.0, health + 45.0)
			Sfx.play("bandage", -3.0)
			G.msg("处理了所有伤口。")
			return true
		"pills":
			painkill_t = 240.0
			Sfx.play("pills", -3.0)
			G.msg("疼痛渐渐退去。")
			return true
		"calm":
			calm_t = 180.0
			panic *= 0.3
			Sfx.play("pills", -3.0)
			G.msg("心跳慢了下来。")
			return true
		"cigs":
			panic = maxf(0.0, panic - 22.0)
			endurance = maxf(0.0, endurance - 15.0)
			Sfx.play("cough", -6.0)
			return true
		"serum":
			Sfx.play("pills", -3.0, 0.7)
			if infected:
				infected = false
				infection = 0.0
				_warned.erase("fever")
				_warned.erase("fever2")
				G.msg("针剂推入静脉。灼烧感沿着手臂退去——你觉得你能活下来。", Color(0.6, 0.85, 0.6))
			else:
				G.msg("你注射了血清。没什么感觉。")
			return true
		"battery":
			p.battery = 100.0
			Sfx.play("mag_in", -6.0, 1.4)
			G.msg("换上了新电池。")
			return true
	return false


## Status tags for the HUD: [text, level 1..3]
func moodles() -> Array:
	var out: Array = []
	if bleeding():
		out.append(["流血", 3])
	if hunger > 45.0:
		out.append(["饥饿" if hunger < 70.0 else ("很饿" if hunger < 85.0 else "饥饿濒死"), 1 if hunger < 70.0 else (2 if hunger < 85.0 else 3)])
	if thirst > 45.0:
		out.append(["口渴" if thirst < 70.0 else ("脱水" if thirst < 85.0 else "严重脱水"), 1 if thirst < 70.0 else (2 if thirst < 85.0 else 3)])
	if fatigue > 55.0:
		out.append(["困倦" if fatigue < 75.0 else "精疲力竭", 1 if fatigue < 75.0 else 2])
	if panic > 25.0:
		out.append(["紧张" if panic < 50.0 else ("恐慌" if panic < 80.0 else "极度恐慌"), 1 if panic < 50.0 else (2 if panic < 80.0 else 3)])
	if pain > 20.0:
		out.append(["疼痛" if pain < 50.0 else "剧痛", 1 if pain < 50.0 else 2])
	if endurance < 25.0:
		out.append(["气喘", 1 if endurance > 8.0 else 2])
	if infection > 25.0:
		out.append(["发烧" if infection < 55.0 else "高烧", 2 if infection < 55.0 else 3])
	if sick > 0.0:
		out.append(["反胃", 1])
	if drunk > 20.0:
		out.append(["微醺", 1])
	if p.over_weight() > 1.0:
		out.append(["负重过大", 2 if p.over_weight() > 1.25 else 1])
	return out

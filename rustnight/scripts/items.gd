class_name Items
extends RefCounted
## Item database, weapon stats and loot tables.

# t: weapon / ammo / food / drink / med / tool / quest      w: weight in kg
const DB := {
	"k45": {"n": "K45 手枪", "w": 1.1, "t": "weapon", "d": ".45 口径半自动手枪。可靠，后坐力大，声音能传出很远。"},
	"viper": {"n": "蝰蛇冲锋枪", "w": 2.6, "t": "weapon", "d": "9mm 全自动。射速极快，近距离泼水。"},
	"kestrel": {"n": "红隼突击步枪", "w": 3.4, "t": "weapon", "d": "5.56 全自动步枪。远近皆宜，一枪爆头。全镇都听得见。"},
	"warden": {"n": "典狱长霰弹枪", "w": 3.2, "t": "weapon", "d": "12 号泵动霰弹枪。贴脸一发清场，逐发装填。"},
	"crowbar": {"n": "撬棍", "w": 1.8, "t": "weapon", "d": "安静。挥起来不快，但不需要子弹。"},
	"axe": {"n": "消防斧", "w": 2.6, "t": "weapon", "d": "沉重的斧头。常常一击毙命，很耗体力。"},
	"a45": {"n": ".45 手枪弹", "w": 0.02, "t": "ammo", "stack": 999},
	"a9": {"n": "9mm 子弹", "w": 0.012, "t": "ammo", "stack": 999},
	"a556": {"n": "5.56 步枪弹", "w": 0.012, "t": "ammo", "stack": 999},
	"a12": {"n": "12 号霰弹", "w": 0.04, "t": "ammo", "stack": 999},
	"beans": {"n": "豆子罐头", "w": 0.4, "t": "food", "hunger": -32.0, "d": "冷的也能吃。"},
	"meat": {"n": "午餐肉罐头", "w": 0.4, "t": "food", "hunger": -42.0, "thirst": 6.0, "d": "很咸，很顶饿。"},
	"chips": {"n": "薯片", "w": 0.15, "t": "food", "hunger": -14.0, "thirst": 8.0, "d": "袋子的响声让你心惊。"},
	"cereal": {"n": "干麦片", "w": 0.3, "t": "food", "hunger": -22.0, "thirst": 5.0},
	"choco": {"n": "巧克力", "w": 0.1, "t": "food", "hunger": -12.0, "panic": -8.0, "fatigue": -4.0, "d": "一点甜味，稍微安心。"},
	"bread": {"n": "发霉的面包", "w": 0.3, "t": "food", "hunger": -20.0, "sick": 0.35, "d": "饿极了才会吃。可能会吃坏肚子。"},
	"dogfood": {"n": "狗粮罐头", "w": 0.4, "t": "food", "hunger": -30.0, "panic": 6.0, "d": "……至少是肉。"},
	"water": {"n": "瓶装水", "w": 0.5, "t": "drink", "thirst": -45.0},
	"soda": {"n": "汽水", "w": 0.35, "t": "drink", "thirst": -28.0, "hunger": -4.0, "fatigue": -5.0},
	"beer": {"n": "啤酒", "w": 0.4, "t": "drink", "thirst": -14.0, "panic": -28.0, "fatigue": 6.0, "d": "壮胆。手会变得不太稳。"},
	"coffee": {"n": "罐装咖啡", "w": 0.25, "t": "drink", "thirst": -10.0, "fatigue": -32.0, "d": "还能再撑一夜。"},
	"bandage": {"n": "绷带", "w": 0.1, "t": "med", "d": "包扎一处伤口，止血。"},
	"pills": {"n": "止痛药", "w": 0.05, "t": "med", "d": "压住疼痛，手不再抖。"},
	"calm": {"n": "镇静剂", "w": 0.05, "t": "med", "d": "迅速压下恐慌，持续一段时间。"},
	"medkit": {"n": "急救包", "w": 0.8, "t": "med", "d": "包扎所有伤口并恢复大量生命。"},
	"serum": {"n": "抗病毒血清", "w": 0.1, "t": "med", "d": "卫生所的实验性药剂。据说能在发病前清除感染。"},
	"cigs": {"n": "香烟", "w": 0.05, "t": "med", "d": "抽一根，缓一缓。伤肺。"},
	"battery": {"n": "电池", "w": 0.1, "t": "tool", "d": "给手电筒换上，电量恢复。"},
	"plank": {"n": "木板", "w": 1.2, "t": "tool", "d": "配合锤子，对准门窗按 B 加固。"},
	"hammer": {"n": "锤子", "w": 0.9, "t": "tool", "d": "加固门窗用。不会消耗。"},
	"car_key": {"n": "车钥匙", "w": 0.05, "t": "quest", "d": "钥匙扣上写着：汽修厂 3 号车位。"},
	"car_battery": {"n": "汽车电瓶", "w": 6.0, "t": "quest", "d": "沉。汽修厂那辆车需要它。"},
	"gas_can": {"n": "汽油桶", "w": 5.0, "t": "quest", "d": "半桶，够开出这个镇子。"},
	"police_key": {"n": "军械库钥匙", "w": 0.05, "t": "quest", "d": "能打开警察局的枪柜。"},
}

# slot: 0 primary, 1 sidearm, 2 melee
const WEAPONS := {
	"k45": {"slot": 1, "model": "k45", "ammo": "a45", "mag": 8, "dmg": 40.0, "dmg_min": 24.0, "r0": 14.0, "r1": 40.0,
		"head": 3.5, "rpm": 400.0, "auto": false, "pellets": 1, "hip": 2.2, "ads_spread": 0.1, "bloom": 0.9,
		"recoil_v": 2.0, "recoil_h": 0.6, "kick": 0.05, "kick_rot": 8.0, "ads_time": 0.15, "zoom": 1.18,
		"ads_dist": 0.34, "sprint_out": 0.14, "reload": 1.45, "reload_empty": 1.85, "draw": 0.3, "move": 1.0,
		"ads_move": 0.74, "noise": 60.0, "sound": "shot_pistol", "hip_pos": Vector3(0.1, -0.075, -0.36), "stagger": 0.45},
	"viper": {"slot": 0, "model": "smg", "ammo": "a9", "mag": 30, "dmg": 24.0, "dmg_min": 13.0, "r0": 10.0, "r1": 32.0,
		"head": 3.0, "rpm": 800.0, "auto": true, "pellets": 1, "hip": 2.8, "ads_spread": 0.22, "bloom": 0.3,
		"recoil_v": 0.8, "recoil_h": 0.5, "kick": 0.022, "kick_rot": 2.0, "ads_time": 0.19, "zoom": 1.2,
		"ads_dist": 0.2, "sprint_out": 0.18, "reload": 1.95, "reload_empty": 2.45, "draw": 0.4, "move": 0.97,
		"ads_move": 0.8, "noise": 55.0, "sound": "shot_smg", "hip_pos": Vector3(0.1, -0.075, -0.24), "stagger": 0.2},
	"kestrel": {"slot": 0, "model": "ar", "ammo": "a556", "mag": 30, "dmg": 36.0, "dmg_min": 26.0, "r0": 28.0, "r1": 70.0,
		"head": 3.5, "rpm": 640.0, "auto": true, "pellets": 1, "hip": 3.4, "ads_spread": 0.06, "bloom": 0.28,
		"recoil_v": 1.1, "recoil_h": 0.55, "kick": 0.03, "kick_rot": 2.4, "ads_time": 0.25, "zoom": 1.32,
		"ads_dist": 0.16, "sprint_out": 0.24, "reload": 2.2, "reload_empty": 2.8, "draw": 0.5, "move": 0.93,
		"ads_move": 0.7, "noise": 85.0, "sound": "shot_rifle", "hip_pos": Vector3(0.1, -0.08, -0.16), "stagger": 0.35},
	"warden": {"slot": 0, "model": "shotgun", "ammo": "a12", "mag": 6, "dmg": 17.0, "dmg_min": 4.0, "r0": 7.0, "r1": 20.0,
		"head": 1.8, "rpm": 68.0, "auto": false, "pellets": 9, "pellet_spread": 3.4, "hip": 1.6, "ads_spread": 0.0, "bloom": 0.0,
		"recoil_v": 5.5, "recoil_h": 1.4, "kick": 0.1, "kick_rot": 11.0, "ads_time": 0.27, "zoom": 1.15,
		"ads_dist": 0.2, "sprint_out": 0.24, "reload": 0.52, "shell_reload": true, "pump": true, "draw": 0.5, "move": 0.94,
		"ads_move": 0.76, "noise": 90.0, "sound": "shot_shotgun", "hip_pos": Vector3(0.1, -0.08, -0.16), "stagger": 1.0},
	"crowbar": {"slot": 2, "model": "crowbar", "melee": true, "dmg": 55.0, "range": 2.0, "arc": 50.0, "swing": 0.72,
		"hit_t": 0.2, "head_kill": 0.12, "end_cost": 6.0, "draw": 0.35, "move": 1.0, "noise": 5.0,
		"hip_pos": Vector3(0.2, -0.3, -0.4), "stagger": 1.0},
	"axe": {"slot": 2, "model": "axe", "melee": true, "dmg": 95.0, "range": 2.1, "arc": 55.0, "swing": 1.0,
		"hit_t": 0.3, "head_kill": 0.35, "end_cost": 10.0, "draw": 0.45, "move": 0.97, "noise": 6.0,
		"hip_pos": Vector3(0.2, -0.38, -0.44), "stagger": 1.0},
}

const CONTAINER_NAMES := {
	"fridge": "冰箱", "counter": "橱柜", "drawer": "抽屉", "wardrobe": "衣柜", "shelf": "书架",
	"medicine": "药柜", "food_shelf": "货架", "drink_fridge": "冷柜", "tool_shelf": "工具货架",
	"med_shelf": "药品架", "register": "收银台", "desk": "办公桌", "filing": "文件柜", "locker": "储物柜",
	"gunlocker": "枪柜", "crate": "木箱", "toolbench": "工作台", "car": "废弃车辆", "dumpster": "垃圾箱",
	"bar": "吧台", "military": "军用物资箱", "corpse": "尸体",
}

# [id, weight, min, max]
const LOOT := {
	"fridge": {"rolls": [1, 3], "items": [["water", 5, 1, 2], ["soda", 4, 1, 2], ["beer", 3, 1, 2], ["meat", 2, 1, 1], ["bread", 3, 1, 1], ["beans", 2, 1, 1]]},
	"counter": {"rolls": [0, 2], "items": [["beans", 4, 1, 2], ["cereal", 3, 1, 1], ["chips", 3, 1, 1], ["coffee", 2, 1, 1], ["dogfood", 1.5, 1, 1], ["battery", 1, 1, 1], ["water", 1.5, 1, 1]]},
	"drawer": {"rolls": [0, 2], "items": [["battery", 3, 1, 1], ["cigs", 3, 1, 1], ["pills", 2, 1, 1], ["bandage", 2.5, 1, 2], ["a45", 1.5, 5, 12], ["choco", 2, 1, 1], ["a9", 1, 8, 18]]},
	"wardrobe": {"rolls": [0, 2], "items": [["bandage", 3, 1, 2], ["a12", 1.2, 3, 7], ["a45", 2, 6, 12], ["cigs", 2, 1, 1], ["k45", 0.4, 1, 1], ["battery", 2, 1, 1], ["warden", 0.3, 1, 1]]},
	"shelf": {"rolls": [0, 1], "items": [["cigs", 2, 1, 1], ["battery", 2, 1, 1], ["choco", 1, 1, 1], ["pills", 1, 1, 1]]},
	"medicine": {"rolls": [1, 3], "items": [["bandage", 5, 1, 2], ["pills", 4, 1, 1], ["calm", 3, 1, 1], ["medkit", 0.8, 1, 1]]},
	"food_shelf": {"rolls": [1, 3], "items": [["beans", 4, 1, 2], ["meat", 2.5, 1, 1], ["chips", 4, 1, 2], ["cereal", 3, 1, 1], ["choco", 3, 1, 2], ["water", 3, 1, 2], ["soda", 3, 1, 1], ["dogfood", 2, 1, 1], ["battery", 1, 1, 1]]},
	"drink_fridge": {"rolls": [2, 4], "items": [["water", 5, 1, 2], ["soda", 5, 1, 2], ["beer", 3, 1, 2], ["coffee", 3, 1, 2]]},
	"tool_shelf": {"rolls": [1, 3], "items": [["battery", 5, 1, 2], ["plank", 5, 1, 3], ["hammer", 2, 1, 1], ["axe", 0.5, 1, 1], ["crowbar", 0.8, 1, 1], ["bandage", 1, 1, 1]]},
	"med_shelf": {"rolls": [1, 3], "items": [["bandage", 5, 1, 3], ["pills", 4, 1, 2], ["calm", 3, 1, 2], ["medkit", 1.5, 1, 1], ["serum", 0.35, 1, 1]]},
	"register": {"rolls": [0, 2], "items": [["cigs", 4, 1, 2], ["battery", 2, 1, 1], ["choco", 2, 1, 1], ["a45", 1, 5, 10], ["k45", 0.3, 1, 1]]},
	"desk": {"rolls": [0, 2], "items": [["battery", 3, 1, 1], ["cigs", 2, 1, 1], ["pills", 2, 1, 1], ["a45", 1.5, 6, 12], ["coffee", 2, 1, 1], ["choco", 1, 1, 1]]},
	"filing": {"rolls": [0, 1], "items": [["battery", 2, 1, 1], ["cigs", 2, 1, 1], ["bandage", 1, 1, 1]]},
	"locker": {"rolls": [1, 2], "items": [["a45", 4, 8, 20], ["a9", 3, 12, 28], ["bandage", 2, 1, 2], ["battery", 2, 1, 1], ["k45", 0.7, 1, 1], ["pills", 1, 1, 1], ["a12", 2, 4, 8]]},
	"gunlocker": {"rolls": [3, 4], "items": [["a556", 5, 24, 45], ["a9", 4, 24, 40], ["a12", 4, 6, 12], ["a45", 3, 12, 24], ["kestrel", 1.4, 1, 1], ["viper", 1.6, 1, 1], ["warden", 1.5, 1, 1], ["medkit", 1, 1, 1]]},
	"crate": {"rolls": [0, 2], "items": [["plank", 4, 1, 3], ["beans", 2, 1, 2], ["water", 2, 1, 2], ["battery", 2, 1, 1], ["a12", 1, 3, 6], ["hammer", 1, 1, 1]]},
	"toolbench": {"rolls": [1, 3], "items": [["hammer", 3, 1, 1], ["plank", 3, 1, 3], ["battery", 3, 1, 2], ["crowbar", 1.2, 1, 1], ["axe", 0.7, 1, 1]]},
	"car": {"rolls": [0, 2], "items": [["water", 3, 1, 1], ["chips", 2, 1, 1], ["battery", 2, 1, 1], ["cigs", 2, 1, 1], ["a45", 1.2, 4, 10], ["bandage", 2, 1, 1], ["a9", 1, 6, 14]]},
	"dumpster": {"rolls": [0, 2], "items": [["plank", 3, 1, 2], ["bread", 2, 1, 1], ["dogfood", 1, 1, 1], ["battery", 1, 1, 1], ["beer", 1, 1, 1]]},
	"bar": {"rolls": [2, 4], "items": [["beer", 6, 1, 3], ["soda", 2, 1, 2], ["chips", 3, 1, 2], ["cigs", 2, 1, 1], ["coffee", 1, 1, 1]]},
	"military": {"rolls": [3, 4], "items": [["a556", 5, 25, 50], ["a9", 3, 20, 40], ["medkit", 2, 1, 1], ["kestrel", 1.5, 1, 1], ["serum", 0.8, 1, 1], ["meat", 2, 1, 2], ["bandage", 2, 1, 3], ["a12", 2, 6, 12]]},
	"corpse": {"rolls": [0, 1], "items": [["cigs", 3, 1, 1], ["a45", 2, 2, 6], ["a9", 1.5, 3, 8], ["bandage", 2, 1, 1], ["choco", 1.5, 1, 1], ["battery", 1.5, 1, 1], ["pills", 1, 1, 1]]},
}


static func item_name(id: String) -> String:
	return DB[id]["n"]


static func weight(id: String) -> float:
	return DB[id]["w"]


static func roll(kind: String, rng: RandomNumberGenerator) -> Array:
	var out: Array = []
	if not LOOT.has(kind):
		return out
	var t: Dictionary = LOOT[kind]
	var total := 0.0
	for it in t["items"]:
		total += float(it[1])
	for i in rng.randi_range(t["rolls"][0], t["rolls"][1]):
		var r := rng.randf() * total
		for it in t["items"]:
			r -= float(it[1])
			if r <= 0.0:
				var n := rng.randi_range(it[2], it[3])
				var merged := false
				if DB[it[0]]["t"] != "weapon":
					for o in out:
						if o["id"] == it[0]:
							o["n"] += n
							merged = true
				if not merged:
					out.append({"id": it[0], "n": n})
				break
	return out

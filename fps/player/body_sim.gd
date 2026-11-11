class_name BodySim
extends RefCounted
## The player's body as an injury model (design supplement, section 1).
## Pure logic, no nodes: six parts with a hidden "function" value, one pool of
## blood that only bleeding drains, a list of wounds with their own little state
## machine, and the everyday medical supplies that act on them. There is no
## health bar anywhere; everything the game needs is read from `mods`.
##
## Time advances in fixed steps and all chance goes through one seeded
## generator, so the same inputs always give the same result.

signal wounded(part: Part, kind: Kind, level: int)
signal wound_changed(id: int)
## Something the body did out loud: &"cough" or &"scream". Grunts are raised by
## the Body node, which knows what the player was doing at the time.
signal vocalised(what: StringName)
signal blackout_started
signal stumbled
## An electric burn made the hands let go of whatever they held.
signal dropped_item
signal collapsed
signal died(cause: StringName)

enum Part { HEAD, TORSO, ARM_R, ARM_L, LEG_L, LEG_R }
enum Kind { CUT, CRUSH, BURN }
## How a creature attacks decides where it lands (see HIT_TABLE).
enum Attack { LOW_BITE, POUNCE, SLAM, LASH }
enum Tier { FINE, LIGHT, HEAVY, DEAD }
enum WoundState { OPEN, PATCHED, SEALED, CLOTTED, TORN }

const STEP := 0.05
const PART_NAMES: Array[StringName] = [&"head", &"torso", &"arm_r", &"arm_l", &"leg_l", &"leg_r"]
const FUNCTION_MAX: Array[float] = [60.0, 120.0, 70.0, 70.0, 80.0, 80.0]
const LIMB_OVERFLOW := 0.5         # share of damage past a dead limb that reaches the torso
const LIGHT_BELOW := 0.7           # function ratio under which a part is lightly hurt
const HEAVY_BELOW := 0.4
## Damage of one hit decides the wound level: under 12 is 1, 12 to 25 is 2, more is 3.
const LEVEL_2_FROM := 12.0
const LEVEL_3_ABOVE := 25.0

const BLOOD_MAX := 100.0
const BLOOD_REGEN := 0.02          # per second, and only while nothing is bleeding
const CUT_BLEED: Array[float] = [0.15, 0.45, 1.1]
const CUT_CLOT_TIME := 60.0        # a level 1 cut closes by itself
const PATCHED_BLEED := 0.15        # a level 3 cut under tape alone
const TORN_BLEED := 0.15           # a reopened wound bleeds like a level 1 cut
const CRUSH_EXTRA: Array[float] = [5.0, 12.0, 20.0]
const INTERNAL_BLEED := 0.2        # level 3 crush
const BURN_TREMOR: Array[float] = [8.0, 15.0, 25.0]
const BURN_DROP_CHANCE: Array[float] = [0.0, 0.2, 1.0]
const PAIN: Array[float] = [10.0, 24.0, 38.0]
const PAIN_CLOSED := 0.5           # a closed wound hurts half as much
const COLLAPSE_TIME := 4.0         # on the knees, then dark

## Share of hits per part for each kind of attack, in Part order.
const HIT_TABLE := {
	Attack.LOW_BITE: [0.0, 0.10, 0.0, 0.20, 0.35, 0.35],
	Attack.POUNCE: [0.10, 0.40, 0.25, 0.25, 0.0, 0.0],
	Attack.SLAM: [0.15, 0.55, 0.15, 0.15, 0.0, 0.0],
	Attack.LASH: [0.30, 0.40, 0.15, 0.15, 0.0, 0.0],
}
## A pounce on someone who is aiming lands on the raised arms 65% of the time.
const POUNCE_AIMING: Array[float] = [0.10, 0.25, 0.325, 0.325, 0.0, 0.0]

# Supplies. Use times are for whatever plays the animation; nothing here waits.
const USE_TIME := {
	&"tape": 3.5, &"towel_tape": 5.0, &"glue": 2.0, &"whiskey_drink": 2.0, &"whiskey_pour": 2.5,
	&"painkiller": 1.5, &"splint": 6.0, &"sports_drink": 1.5, &"coffee": 2.0, &"first_aid": 8.0,
}
const WHISKEY_RELIEF := 0.4
const WHISKEY_TIME := 120.0
const WHISKEY_SWAY := 1.15
const PILL_DELAY := 20.0
const PILL_RELIEF := 0.6
const PILL_TIME := 180.0
const PILL_OVERDOSE_WINDOW := 60.0
const SPORTS_REGEN := 3.0
const SPORTS_TIME := 120.0
const COFFEE_REGEN := 1.5
const COFFEE_SWAY := 1.1
const COFFEE_TIME := 90.0
const FIRST_AID_FUNCTION := 40.0

# Numbers the design leaves open; first guesses, flagged in the supplement.
## Pain at which the sway penalties of the arm table apply in full.
const PAIN_FULL := 24.0
const TREMOR_SWAY := 1.6


class Wound:
	var id := 0
	var part := Part.TORSO
	var kind := Kind.CUT
	var level := 1
	var state := WoundState.OPEN
	var age := 0.0
	## Doused in whiskey: once closed it will not tear open again.
	var sterile := false


## Everything the rest of the game reads. Multipliers apply to the named
## parameter of Player / WeaponData; 1 means untouched.
class Mods:
	var walk_scale := 1.0
	var sprint_scale := 1.0
	var can_sprint := true
	var can_slide := true
	var jump_scale := 1.0              # 0 = cannot jump
	var slide_cost_scale := 1.0
	var mantle_max_override := 0.0     # metres; 0 = no limit from the body
	var mantle_time_scale := 1.0
	var reload_scale := 1.0            # reload_time, reload_empty_time, shell_*_time
	var pump_scale := 1.0              # pump_time, support hand only
	var long_pump_scale := 1.0         # extra pump_time factor for long guns (gun arm gone)
	var long_aim_spread_scale := 1.0   # aim_spread of long guns (cannot be shouldered)
	var aim_time_scale := 1.0
	var sway_scale := 1.0
	var kick_scale := 1.0              # kick_pitch
	var kick_recover_scale := 1.0
	var mag_drop_chance := 0.0
	var stamina_max_scale := 1.0
	var stamina_regen_scale := 1.0
	var sprint_drain_scale := 1.0
	var can_use_stun_gun := true
	var can_carry := true
	var limp := 0.0                    # 0..1, camera
	var input_lost := false            # a stagger: controls are dead for a moment
	var aim_drift := 0.0               # 0..1, the view wanders
	var woozy := false                 # two pills too close together
	var desaturate := 0.0              # 0..1, screen
	var tunnel := 0.0                  # 0..1, screen
	var blackout := 0.0                # 0..1, screen
	var ear_ring := 0.0                # seconds of tinnitus left
	var muffled := false               # hearing dulled (blood under 25)
	var heartbeat := 0.0               # 0..1, how loud and fast
	var numb := false                  # painkillers hide heartbeat and grunts


var function: Array[float] = []
var blood := BLOOD_MAX
var wounds: Array[Wound] = []
var splinted: Array[bool] = []
var mods := Mods.new()
var dead := false
var collapsing := false
var rng := RandomNumberGenerator.new()

var _acc := 0.0
var _next_id := 1
var _whiskey := 0.0
var _pills: Array[float] = []          # age of each pill still in the system
var _sports := 0.0
var _coffee := 0.0
var _tremor := 0.0
var _ear_ring := 0.0
var _cough_in := 0.0
var _blackout_in := 0.0
var _blackout := 0.0
var _stumble_in := 0.0
var _stumble := 0.0
var _collapse := 0.0


func _init(random_seed: int = 1) -> void:
	rng.seed = random_seed
	reset()


## Whole and unhurt again.
func reset() -> void:
	function = FUNCTION_MAX.duplicate()
	splinted.assign([false, false, false, false, false, false])
	blood = BLOOD_MAX
	wounds.clear()
	dead = false
	collapsing = false
	_acc = 0.0
	_whiskey = 0.0
	_pills.clear()
	_sports = 0.0
	_coffee = 0.0
	_tremor = 0.0
	_ear_ring = 0.0
	_cough_in = 0.0
	_blackout_in = 0.0
	_blackout = 0.0
	_stumble_in = 0.0
	_stumble = 0.0
	_collapse = 0.0
	_refresh()


# --- getting hurt ---------------------------------------------------------------

## A creature's attack: picks the part from the attack's table. `aiming` is
## whether the player had the weapon up. Returns what hit() returns.
func hit(damage: float, kind: Kind, attack: Attack, aiming: bool = false) -> Dictionary:
	var table: Array = POUNCE_AIMING if attack == Attack.POUNCE and aiming else HIT_TABLE[attack]
	var roll := rng.randf()
	var part := Part.TORSO
	for i in table.size():
		roll -= table[i]
		if roll < 0.0:
			part = i as Part
			break
	return hit_part(part, damage, kind)


## Damage to one named part. Returns {part, level, wound, dropped}; wound is
## the new wound's id.
func hit_part(part: Part, damage: float, kind: Kind) -> Dictionary:
	if dead or damage <= 0.0:
		return {}
	var level := 1
	if damage > LEVEL_3_ABOVE:
		level = 3
	elif damage >= LEVEL_2_FROM:
		level = 2
	var total := damage + (CRUSH_EXTRA[level - 1] if kind == Kind.CRUSH else 0.0)
	_damage(part, total)

	var w := Wound.new()
	w.id = _next_id
	_next_id += 1
	w.part = part
	w.kind = kind
	w.level = level
	wounds.append(w)
	var dropped := false
	if kind == Kind.BURN:
		_tremor = maxf(_tremor, BURN_TREMOR[level - 1])
		dropped = rng.randf() < BURN_DROP_CHANCE[level - 1]
		if dropped:
			dropped_item.emit()
	if part == Part.HEAD and tier(Part.HEAD) != Tier.FINE:
		_ear_ring = 2.0
	wounded.emit(part, kind, level)
	_check_death()
	_refresh()
	return {"part": part, "level": level, "wound": w.id, "dropped": dropped}


func _damage(part: Part, amount: float) -> void:
	var left := function[part] - amount
	if left < 0.0 and part != Part.HEAD and part != Part.TORSO:
		function[Part.TORSO] = maxf(function[Part.TORSO] + left * LIMB_OVERFLOW, 0.0)
	function[part] = maxf(left, 0.0)


func _check_death() -> void:
	if dead:
		return
	if function[Part.HEAD] <= 0.0:
		_die(&"head")
	elif function[Part.TORSO] <= 0.0:
		_die(&"torso")


func _die(cause: StringName) -> void:
	dead = true
	collapsing = false
	_refresh()
	died.emit(cause)


# --- reading the state ------------------------------------------------------------

func tier(part: Part) -> Tier:
	if function[part] <= 0.0:
		return Tier.DEAD
	var ratio := function[part] / FUNCTION_MAX[part]
	var t := Tier.FINE
	if ratio < HEAVY_BELOW:
		t = Tier.HEAVY
	elif ratio < LIGHT_BELOW:
		t = Tier.LIGHT
	# A splint on a crushed limb makes it count one step better.
	if splinted[part] and t != Tier.FINE:
		t = (t - 1) as Tier
	return t


func wound(id: int) -> Wound:
	for w in wounds:
		if w.id == id:
			return w
	return null


func bleed_rate_of(w: Wound) -> float:
	match w.state:
		WoundState.PATCHED:
			return PATCHED_BLEED
		WoundState.TORN:
			return TORN_BLEED
		WoundState.OPEN:
			if w.kind == Kind.CUT:
				return CUT_BLEED[w.level - 1]
			if w.kind == Kind.CRUSH and w.level == 3:
				return INTERNAL_BLEED
	return 0.0


## Blood lost per second right now.
func bleed_rate() -> float:
	var total := 0.0
	for w in wounds:
		total += bleed_rate_of(w)
	return total


## The wound losing the most blood, or 0 if nothing bleeds (the quick-bandage key).
func worst_bleeding() -> int:
	var best := 0
	var rate := 0.0
	for w in wounds:
		var r := bleed_rate_of(w)
		if r > rate:
			rate = r
			best = w.id
	return best


func is_closed(w: Wound) -> bool:
	return w.state == WoundState.SEALED or w.state == WoundState.CLOTTED


## Pain relief from drink and pills, 0..1. They stack by what each leaves over.
func relief() -> float:
	var left := 1.0
	if _whiskey > 0.0:
		left *= 1.0 - WHISKEY_RELIEF
	if _pill_active():
		left *= 1.0 - PILL_RELIEF
	return 1.0 - left


## Pain felt right now: every wound, halved once closed, less whatever is dulling it.
func pain(part: int = -1) -> float:
	var total := 0.0
	for w in wounds:
		if part >= 0 and w.part != part:
			continue
		total += PAIN[w.level - 1] * (PAIN_CLOSED if is_closed(w) else 1.0)
	return total * (1.0 - relief())


func _pill_active() -> bool:
	for age in _pills:
		if age >= PILL_DELAY and age < PILL_DELAY + PILL_TIME:
			return true
	return false


# --- supplies ---------------------------------------------------------------------
# Each returns false when it had nothing to act on (and so should not be used up).

## Duct tape: closes a level 1 or 2 cut, slows a level 3 one to a trickle, and
## closes a torn wound again.
func tape(id: int) -> bool:
	var w := wound(id)
	if w == null or w.kind != Kind.CUT:
		return false
	if w.state == WoundState.TORN or (w.state == WoundState.OPEN and w.level < 3):
		w.state = WoundState.SEALED
	elif w.state == WoundState.OPEN:
		w.state = WoundState.PATCHED
	else:
		return false
	_changed(w)
	return true


## A towel under the tape: the only thing that fully closes a level 3 cut.
func towel_tape(id: int) -> bool:
	var w := wound(id)
	if w == null or w.kind != Kind.CUT or is_closed(w):
		return false
	w.state = WoundState.SEALED
	_changed(w)
	return true


## Superglue: closes a level 1 or 2 cut at once. It stings; expect a grunt.
func glue(id: int) -> bool:
	var w := wound(id)
	if w == null or w.kind != Kind.CUT or w.level > 2 or is_closed(w) or w.state == WoundState.PATCHED:
		return false
	w.state = WoundState.SEALED
	_changed(w)
	return true


func drink_whiskey() -> bool:
	_whiskey = WHISKEY_TIME
	_refresh()
	return true


## Whiskey on the wound: it will never tear open again. Loud.
func pour_whiskey(id: int) -> bool:
	var w := wound(id)
	if w == null or w.kind != Kind.CUT or w.sterile:
		return false
	w.sterile = true
	_changed(w)
	vocalised.emit(&"scream")
	return true


## Starts working after PILL_DELAY. Two inside a minute and the view swims.
func take_painkiller() -> bool:
	_pills.append(0.0)
	_refresh()
	return true


## A ruler or a magazine taped on: a crushed limb counts one step better.
func splint(part: Part) -> bool:
	if part == Part.HEAD or part == Part.TORSO or splinted[part] or function[part] <= 0.0:
		return false
	var crushed := false
	for w in wounds:
		crushed = crushed or (w.part == part and w.kind == Kind.CRUSH)
	if not crushed:
		return false
	splinted[part] = true
	_refresh()
	return true


func sports_drink() -> bool:
	_sports = SPORTS_TIME
	return true


func coffee() -> bool:
	_coffee = COFFEE_TIME
	_refresh()
	return true


## The one proper kit: stops all bleeding on a part and gives back some function.
func first_aid(part: Part) -> bool:
	if function[part] <= 0.0 and (part == Part.HEAD or part == Part.TORSO):
		return false
	for w in wounds:
		if w.part == part and not is_closed(w):
			w.state = WoundState.SEALED
	function[part] = minf(function[part] + FIRST_AID_FUNCTION, FUNCTION_MAX[part])
	_refresh()
	return true


func _changed(w: Wound) -> void:
	_refresh()
	wound_changed.emit(w.id)


# --- strain -----------------------------------------------------------------------

## Hard use of the legs (a slide, five seconds of sprinting): each closed,
## untreated-by-whiskey wound there tears with this chance. True if one did.
func strain_legs(chance: float) -> bool:
	return _strain([Part.LEG_L, Part.LEG_R], chance)


## Hard use of the arms (a melee swing).
func strain_arms(chance: float) -> bool:
	return _strain([Part.ARM_R, Part.ARM_L], chance)


func _strain(parts: Array, chance: float) -> bool:
	var tore := false
	for w in wounds:
		if w.state == WoundState.SEALED and not w.sterile and w.kind == Kind.CUT and parts.has(w.part):
			if rng.randf() < chance:
				w.state = WoundState.TORN
				tore = true
				wound_changed.emit(w.id)
	if tore:
		_refresh()
	return tore


# --- time -------------------------------------------------------------------------

func advance(delta: float) -> void:
	_acc += delta
	while _acc >= STEP:
		_acc -= STEP
		_step(STEP)


func _step(dt: float) -> void:
	if dead:
		return
	for w in wounds:
		w.age += dt
		if w.kind == Kind.CUT and w.level == 1 and w.state == WoundState.OPEN and w.age >= CUT_CLOT_TIME:
			w.state = WoundState.CLOTTED
			wound_changed.emit(w.id)
	# A burn is only a wound for as long as the hands shake.
	_tremor = maxf(_tremor - dt, 0.0)
	if _tremor <= 0.0:
		for i in range(wounds.size() - 1, -1, -1):
			if wounds[i].kind == Kind.BURN:
				wounds.remove_at(i)

	var bleeding := bleed_rate()
	if bleeding > 0.0:
		blood = maxf(blood - bleeding * dt, 0.0)
	else:
		blood = minf(blood + BLOOD_REGEN * (SPORTS_REGEN if _sports > 0.0 else 1.0) * dt, BLOOD_MAX)

	_whiskey = maxf(_whiskey - dt, 0.0)
	_sports = maxf(_sports - dt, 0.0)
	_coffee = maxf(_coffee - dt, 0.0)
	_ear_ring = maxf(_ear_ring - dt, 0.0)
	for i in range(_pills.size() - 1, -1, -1):
		_pills[i] += dt
		if _pills[i] >= PILL_DELAY + PILL_TIME:
			_pills.remove_at(i)

	if blood <= 0.0:
		if not collapsing:
			collapsing = true
			_collapse = 0.0
			collapsed.emit()
		_collapse += dt
		if _collapse >= COLLAPSE_TIME:
			_die(&"blood")
			return
	else:
		collapsing = false

	# Recurring troubles, each on its own irregular clock.
	if tier(Part.TORSO) >= Tier.HEAVY:
		_cough_in = _tick_clock(_cough_in, dt, 20.0, 40.0)
		if _cough_in <= 0.0:
			_cough_in = rng.randf_range(20.0, 40.0)
			vocalised.emit(&"cough")
	else:
		_cough_in = 0.0
	_blackout = maxf(_blackout - dt, 0.0)
	if tier(Part.HEAD) >= Tier.HEAVY:
		_blackout_in = _tick_clock(_blackout_in, dt, 15.0, 25.0)
		if _blackout_in <= 0.0:
			_blackout_in = rng.randf_range(15.0, 25.0)
			_blackout = 0.4
			blackout_started.emit()
	else:
		_blackout_in = 0.0
	_stumble = maxf(_stumble - dt, 0.0)
	if blood < 25.0 and not collapsing:
		_stumble_in = _tick_clock(_stumble_in, dt, 8.0, 14.0)
		if _stumble_in <= 0.0:
			_stumble_in = rng.randf_range(8.0, 14.0)
			_stumble = 0.3
			stumbled.emit()
	else:
		_stumble_in = 0.0
	_refresh()


## Counts a clock down; an unset one (0) is first wound up to a random interval.
func _tick_clock(clock: float, dt: float, shortest: float, longest: float) -> float:
	if clock <= 0.0:
		clock = rng.randf_range(shortest, longest)
	return clock - dt


# --- the table --------------------------------------------------------------------

func _refresh() -> void:
	var m := Mods.new()
	var gun := tier(Part.ARM_R)
	var support := tier(Part.ARM_L)
	var leg_l := tier(Part.LEG_L)
	var leg_r := tier(Part.LEG_R)
	var legs := maxi(leg_l, leg_r) as Tier
	var torso := tier(Part.TORSO)

	# Gun arm. Each step keeps what the step before it cost.
	var arm_sway := 1.0
	if gun >= Tier.LIGHT:
		arm_sway = 1.4
		m.kick_recover_scale = 0.85
	if gun >= Tier.HEAVY:
		arm_sway = 2.2
		m.aim_time_scale = 1.35
		m.kick_scale = 1.25
	if gun == Tier.DEAD:
		m.long_aim_spread_scale = 3.0
		m.long_pump_scale = 1.8

	# Support arm.
	match support:
		Tier.LIGHT:
			m.reload_scale = 1.25
			m.pump_scale = 1.3
		Tier.HEAVY:
			m.reload_scale = 1.6
			m.pump_scale = 1.7
		Tier.DEAD:
			m.reload_scale = 2.2
			m.pump_scale = 1.7
			m.mag_drop_chance = 0.15
			m.can_use_stun_gun = false
			m.can_carry = false

	# Legs: the worse one counts, and both hurt costs a little more.
	if legs >= Tier.LIGHT:
		m.sprint_scale = 5.8 / 6.6
		m.slide_cost_scale = 1.5
		m.jump_scale = 0.85
	if legs >= Tier.HEAVY:
		m.can_sprint = false
		m.can_slide = false
		m.walk_scale = 3.4 / 4.3
		m.mantle_max_override = 1.25
		m.mantle_time_scale = 1.5
		m.limp = 0.5
	if legs == Tier.DEAD:
		m.walk_scale = 2.4 / 4.3
		m.jump_scale = 0.0
		m.limp = 1.0
	if leg_l >= Tier.LIGHT and leg_r >= Tier.LIGHT:
		m.walk_scale *= 0.9
		m.sprint_scale *= 0.9

	# Torso.
	if torso >= Tier.LIGHT:
		m.stamina_max_scale = 0.8
		m.stamina_regen_scale = 0.7
	if torso >= Tier.HEAVY:
		m.stamina_max_scale = 0.6

	# Blood.
	var blood_sway := 1.0
	if blood < 70.0:
		m.stamina_regen_scale *= 0.8
		m.desaturate = 0.25
		m.heartbeat = 0.35
	if blood < 45.0:
		blood_sway = 1.3
		m.sprint_drain_scale = 1.4
		m.desaturate = 0.45
		m.tunnel = 0.35
		m.heartbeat = 0.65
	if blood < 25.0:
		m.desaturate = 0.7
		m.tunnel = 0.7
		m.aim_drift = 1.0
		m.muffled = true
		m.heartbeat = 1.0
	if _coffee > 0.0:
		m.stamina_regen_scale *= COFFEE_REGEN

	# Sway: the arm's share scales with how much it hurts right now; shaking
	# from blood loss, drink, coffee and electric burns comes on top.
	var hurting := clampf(pain() / PAIN_FULL, 0.0, 1.0)
	m.sway_scale = (1.0 + (arm_sway - 1.0) * hurting) * blood_sway
	if _whiskey > 0.0:
		m.sway_scale *= WHISKEY_SWAY
	if _coffee > 0.0:
		m.sway_scale *= COFFEE_SWAY
	if _tremor > 0.0:
		m.sway_scale *= TREMOR_SWAY

	m.numb = _pill_active()
	if m.numb:
		m.heartbeat = 0.0
	var recent := 0
	for age in _pills:
		if age < PILL_OVERDOSE_WINDOW:
			recent += 1
	m.woozy = recent >= 2
	m.ear_ring = _ear_ring
	# 0.001: counting a timer down in steps leaves float crumbs behind.
	m.blackout = 1.0 if _blackout > 0.001 else 0.0
	m.input_lost = _stumble > 0.001

	if collapsing or dead:
		m.can_sprint = false
		m.can_slide = false
		m.jump_scale = 0.0
		m.walk_scale = 0.0
		m.sprint_scale = 0.0
		m.input_lost = true
		m.blackout = 1.0 if dead else clampf(_collapse / COLLAPSE_TIME, 0.0, 1.0)
	mods = m


## One line for debug read-outs.
func summary() -> String:
	var parts := ""
	for i in function.size():
		parts += "%s %d  " % [["H", "T", "Ar", "Al", "Ll", "Lr"][i], roundi(function[i])]
	return "%sblood %d  bleed %.2f/s  pain %d  wounds %d" % [parts, roundi(blood), bleed_rate(), roundi(pain()),
			wounds.size()]

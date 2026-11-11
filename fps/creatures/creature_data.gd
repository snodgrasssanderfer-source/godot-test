class_name CreatureData
extends Resource
## Every number that defines one kind of creature. The values in data/*.tres
## are first guesses: the design documents give damage and wake thresholds
## only, the rest has never been played.

@export var id := &"crt"
@export var display_name := "CRT"
## Which wake thresholds it takes on its grid cell: &"small", &"medium", &"large", &"lamp".
@export var grid_kind := &"medium"
@export var model: PackedScene

@export_group("Body")
## The casing as a hit box (metres), resting on the model's Body node.
@export var size := Vector3(0.64, 0.50, 0.58)
## How far the casing lifts off the ground when it stands.
@export var stand_height := 0.42
## The tube face: what bullets have to find. Centre comes from the model's Screen marker.
@export var screen_size := Vector2(0.44, 0.34)

@export_group("Health")
## The meat inside. This is its life.
@export var core_health := 110.0
## The tube face. Until it is shot out, the screen is just more shell.
@export var glass_health := 30.0
## Share of a bullet's damage that gets through the casing.
@export_range(0.0, 1.0) var shell_through := 0.3
## Same for a blunt weapon, which dents the casing into the meat.
@export_range(0.0, 1.0) var melee_through := 0.6
## Damage multiplier through the broken screen.
@export var weak_multiplier := 1.5
## Legs are bare meat but there is not much of it.
@export var leg_multiplier := 0.8
## One hit doing this much to the core knocks it off its stride.
@export var stagger_damage := 35.0

@export_group("Senses")
@export var sight_range := 26.0
## Half-angle of what it sees ahead, degrees.
@export var sight_cone := 110.0
## Inside this it notices the player whichever way it faces.
@export var close_range := 5.0
## Seconds it keeps coming after losing sight.
@export var memory := 6.0

@export_group("Movement")
@export var walk_speed := 1.9
@export var run_speed := 4.9
@export var turn_rate := 7.0
@export var wake_time := 1.3

@export_group("Attack")
@export var attack := BodySim.Attack.POUNCE
@export var wound := BodySim.Kind.CUT
@export var damage_min := 18.0
@export var damage_max := 25.0
## Starts winding up inside this distance.
@export var attack_range := 3.3
## The warning: it crouches back and the screen flares before it jumps.
@export var windup := 0.5
@export var lunge_speed := 10.0
@export var lunge_time := 0.38
## Centre-to-centre distance at which a lunge connects.
@export var hit_reach := 1.0
@export var recover_hit := 0.9
@export var recover_miss := 1.25

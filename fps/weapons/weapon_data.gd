class_name WeaponData
extends Resource
## Every number that defines how one weapon behaves and feels. One .tres per
## weapon in weapons/data/. Angles are in degrees, distances in metres, times
## in seconds. Spread values are the half-angle of the cone.

enum Kind { PISTOL, SHOTGUN, MELEE }

@export var id := &"pistol"
@export var display_name := "Pistol"
@export var kind := Kind.PISTOL
## Barrel along -Z, origin at the grip. Optional child nodes by name: Slide,
## Pump, Mag (moving parts), Muzzle, Eject, Sight, Tip (markers).
@export var model: PackedScene

@export_group("Damage")
@export var damage := 34.0
@export var pellets := 1
@export var head_multiplier := 3.0
## Full damage up to falloff_start, then it drops in a straight line to
## damage * falloff_floor at falloff_end and stays there.
@export var falloff_start := 18.0
@export var falloff_end := 45.0
@export_range(0.0, 1.0) var falloff_floor := 0.6
## How far a bullet travels. For melee: reach.
@export var max_range := 300.0
## Melee only: half-width of the swing, degrees.
@export var melee_arc := 28.0

@export_group("Timing")
## Minimum time between shots. For melee: the length of the whole swing.
@export var fire_interval := 0.13
## Pump-action cycle after each shot. 0 = self-loading.
@export var pump_time := 0.0
## Melee only: moment in the swing when it connects.
@export var hit_time := 0.2
@export var swing_stamina := 8.0
@export var equip_time := 0.36
@export var holster_time := 0.20
## After a sprint the weapon needs this long before it can fire or aim.
@export var sprint_out_time := 0.16
@export var aim_time := 0.18

@export_group("Ammo")
@export var mag_size := 15
@export var reserve := 45
## Magazine weapons: full reload, and the longer one from empty (slide rack).
@export var reload_time := 1.55
@export var reload_empty_time := 1.95
## Shell-by-shell weapons (pump_time > 0): lift, each shell, lower.
@export var shell_start_time := 0.35
@export var shell_time := 0.48
@export var shell_end_time := 0.40

@export_group("Accuracy")
@export var hip_spread := 1.1
## Added per shot from the hip, up to bloom_max, shrinking at bloom_recovery per second.
@export var bloom_per_shot := 0.9
@export var bloom_max := 4.0
@export var bloom_recovery := 8.0
@export var aim_spread := 0.08
## Added from the hip at walking speed, and while airborne.
@export var move_spread := 1.0
@export var air_spread := 3.0
## Shotguns: the cone the pellets scatter in, around the shot direction.
@export var pellet_spread := 0.0
@export var pellet_spread_aim := 0.0

@export_group("Recoil")
## View kick: up, and a random amount left or right up to kick_yaw.
@export var kick_pitch := 1.5
@export var kick_yaw := 0.45
## How fast the view settles back, degrees per second.
@export var kick_recover := 11.0
@export var aim_kick_scale := 0.85
## The weapon model's own jolt: backwards (metres) and muzzle rise (degrees).
@export var model_kick_back := 0.03
@export var model_kick_up := 5.0

@export_group("Pose")
## Where the weapon sits at rest, in camera space.
@export var hip_position := Vector3(0.115, -0.115, -0.33)
@export var hip_rotation := Vector3.ZERO
## Distance from the eye to the rear sight when aiming.
@export var aim_distance := 0.34
@export var aim_zoom := 1.22
## Fine adjustment of the aimed pose, on top of "rear sight on the centre of
## the screen": a shift in camera space and a tilt. A shotgun wants the eye a
## little above the receiver so that its top and the bead can be seen.
@export var aim_offset := Vector3.ZERO
@export var aim_rotation := Vector3.ZERO
## Offsets added while sprinting and at the middle of a reload.
@export var sprint_position := Vector3(-0.03, -0.035, 0.04)
@export var sprint_rotation := Vector3(16.0, 28.0, -8.0)
@export var reload_position := Vector3(-0.02, -0.05, 0.03)
@export var reload_rotation := Vector3(24.0, 16.0, -30.0)
## Movement speed multiplier while this weapon is out.
@export var move_scale := 1.0

@export_group("Noise")
## How far the shot (or a melee hit) is heard.
@export var noise_radius := 70.0
## How hard a shot (or a melee hit) shakes the power grid; see GridSim.
@export var grid_energy := 6.0
@export var fire_sound := &"pistol_shot"
@export var fire_volume := 0.0


func damage_at(distance: float) -> float:
	var t := clampf(inverse_lerp(falloff_start, falloff_end, distance), 0.0, 1.0)
	return damage * lerpf(1.0, falloff_floor, t)

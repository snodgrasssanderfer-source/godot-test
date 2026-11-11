extends Node
## Autoload "NoiseBus": the one place every sound that matters to the world goes
## through. Anything that listens (the power grid now, creatures later)
## connects to `heard`.
##
##   radius     how far it carries, metres
##   energy     how hard it shakes the power grid (docs/design_supplement.md,
##              section 4.2). 0 for footsteps and the like.
##   source     who made it (&"pistol", &"player", &"taser", ...)
##   occlusion  1 in the open; 0.6 for a shot fired indoors and heard outside

signal heard(position: Vector3, radius: float, energy: float, source: StringName, occlusion: float)


func emit(position: Vector3, radius: float, energy: float = 0.0, source: StringName = &"", occlusion: float = 1.0) -> void:
	heard.emit(position, radius, energy, source, occlusion)

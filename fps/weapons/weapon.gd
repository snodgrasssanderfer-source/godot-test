class_name Weapon
extends Node3D
## One weapon in the player's hands: the model, its moving parts and its ammo.
## It has no behaviour of its own; WeaponRig drives it.

const SLIDE_TRAVEL := 0.030
const PUMP_TRAVEL := 0.075
const MAG_DROP := 0.17

var data: WeaponData
var mag := 0
var reserve := 0
## A pistol's slide stays back after the last round until the next reload.
var slide_locked := false

var muzzle: Node3D
var eject: Node3D
var tip: Node3D
## Rear sight, muzzle and tip in this weapon's own space.
var sight_offset := Vector3.ZERO
var muzzle_offset := Vector3(0.0, 0.0, -0.3)
var tip_offset := Vector3(0.0, 0.0, -0.6)

var _slide: Node3D
var _pump: Node3D
var _mag: Node3D
var _slide_rest := Vector3.ZERO
var _pump_rest := Vector3.ZERO
var _mag_rest := Transform3D.IDENTITY


func setup(weapon_data: WeaponData) -> void:
	data = weapon_data
	name = String(data.id)
	mag = data.mag_size
	reserve = data.reserve
	if data.model == null:
		push_warning("Weapon '%s' has no model." % data.id)
		return
	var model := data.model.instantiate() as Node3D
	add_child(model)
	_slide = model.find_child("Slide", true, false) as Node3D
	_pump = model.find_child("Pump", true, false) as Node3D
	_mag = model.find_child("Mag", true, false) as Node3D
	muzzle = model.find_child("Muzzle", true, false) as Node3D
	eject = model.find_child("Eject", true, false) as Node3D
	tip = model.find_child("Tip", true, false) as Node3D
	if _slide:
		_slide_rest = _slide.position
	if _pump:
		_pump_rest = _pump.position
	if _mag:
		_mag_rest = _mag.transform
	var sight := model.find_child("Sight", true, false) as Node3D
	if sight:
		sight_offset = _offset_of(sight)
	if muzzle:
		muzzle_offset = _offset_of(muzzle)
	if tip:
		tip_offset = _offset_of(tip)
	# Viewmodels sit centimetres from the lens: no shadows onto the world.
	for mesh in model.find_children("*", "MeshInstance3D", true, false):
		(mesh as MeshInstance3D).cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF


## Position of a marker in this weapon's space (works outside the tree too).
func _offset_of(node: Node3D) -> Vector3:
	var xf := Transform3D.IDENTITY
	var n := node
	while n != null and n != self:
		xf = n.transform * xf
		n = n.get_parent() as Node3D
	return xf.origin


## 0 = in battery, 1 = fully back.
func set_slide(t: float) -> void:
	if _slide:
		_slide.position = _slide_rest + Vector3(0.0, 0.0, SLIDE_TRAVEL * t)


func set_pump(t: float) -> void:
	if _pump:
		_pump.position = _pump_rest + Vector3(0.0, 0.0, PUMP_TRAVEL * t)


## 0 = seated, 1 = out of the gun (and hidden).
func set_mag_out(t: float) -> void:
	if _mag:
		_mag.position = _mag_rest.origin - _mag_rest.basis.y * (MAG_DROP * t)
		_mag.visible = t < 0.98

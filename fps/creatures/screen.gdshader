shader_type spatial;
render_mode unshaded, cull_back;
// The picture on a creature's tube: off, snow, or the white flare before it jumps.

uniform float power = 0.0;      // 0 dark, 1 snow, above 1 brighter
uniform float flash = 0.0;      // 0..1 white-out
uniform vec3 tint : source_color = vec3(0.72, 0.80, 1.0);

float hash(vec2 p) {
	vec3 p3 = fract(vec3(p.xyx) * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

void fragment() {
	float frame = floor(TIME * 30.0);
	vec2 cell = floor(UV * vec2(150.0, 112.0));
	float snow = hash(cell + frame * 17.0);
	// a rolling bar and scan lines, so it reads as a television and not as noise
	float bar = smoothstep(0.0, 0.08, abs(fract(UV.y * 0.9 - TIME * 0.23) - 0.5) - 0.38);
	float scan = 0.82 + 0.18 * sin(UV.y * 112.0 * PI);
	float edge = smoothstep(0.0, 0.10, UV.x) * smoothstep(0.0, 0.10, 1.0 - UV.x)
			* smoothstep(0.0, 0.12, UV.y) * smoothstep(0.0, 0.12, 1.0 - UV.y);
	vec3 picture = tint * (0.18 + 0.82 * snow) * scan * (0.75 + 0.25 * bar) * (0.45 + 0.55 * edge);
	vec3 col = mix(vec3(0.004, 0.006, 0.006), picture * min(power, 1.0) * (0.9 + 0.5 * max(power - 1.0, 0.0)),
			clamp(power, 0.0, 1.0));
	ALBEDO = mix(col, vec3(1.9, 2.0, 2.2), flash);
}

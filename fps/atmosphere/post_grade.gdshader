shader_type canvas_item;
// Final film pass, drawn over the whole screen: lens fringing, split-tone
// grade, vignette, grain. Same maths as art/concept/post.py, so the stills and
// the game agree. Bloom is done by the Environment glow, not here.

uniform sampler2D screen_tex : hint_screen_texture, repeat_disable, filter_linear;
uniform float saturation = 0.72;
uniform vec3 shadow_tint = vec3(0.94, 1.0, 1.07);
uniform vec3 highlight_tint = vec3(1.0, 1.0, 0.98);
uniform float contrast = 1.10;
uniform float gain = 1.0;
uniform float vignette = 0.35;
uniform float grain = 0.022;
uniform float aberration = 0.0018;
// 0 = off. Driven by the player's Body: blood loss drains the colour and closes
// the edges in, a blow to the head or passing out takes the picture away.
uniform float extra_desaturate = 0.0;
uniform float extra_vignette = 0.0;
uniform float blackout = 0.0;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float hash(vec2 p) {
	vec3 p3 = fract(vec3(p.xyx) * 0.1031);
	p3 += dot(p3, p3.yzx + 33.33);
	return fract((p3.x + p3.y) * p3.z);
}

void fragment() {
	vec2 c = SCREEN_UV - 0.5;
	vec3 col;
	col.r = texture(screen_tex, 0.5 + c * (1.0 - aberration * 2.0)).r;
	col.g = texture(screen_tex, 0.5 + c * (1.0 - aberration)).g;
	col.b = texture(screen_tex, SCREEN_UV).b;

	float l = dot(col, LUMA);
	col = mix(vec3(l), col, saturation * (1.0 - extra_desaturate));
	float t = smoothstep(0.0, 0.6, l);
	col *= mix(shadow_tint, highlight_tint, t);
	col = (col * gain - 0.36) * contrast + 0.36;
	col = clamp(col, 0.0, 1.0);

	float r2 = dot(c * 2.0, c * 2.0);
	col *= 1.0 - vignette * pow(clamp(r2 * 0.5, 0.0, 1.0), 1.3);
	col *= 1.0 - clamp(extra_vignette * pow(clamp(r2 * 0.62, 0.0, 1.0), 0.9) * 1.25, 0.0, 0.97);

	// Grain is re-seeded every frame and is strongest in the mid-tones.
	float n = hash(FRAGCOORD.xy + fract(TIME) * 917.0) - 0.5;
	l = dot(col, LUMA);
	col += n * 2.0 * grain * (0.35 + 0.65 * sin(clamp(l, 0.0, 1.0) * PI));

	col *= 1.0 - blackout;

	COLOR = vec4(col, 1.0);
}

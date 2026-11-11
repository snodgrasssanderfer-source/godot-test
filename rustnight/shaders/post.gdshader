shader_type canvas_item;
// Final look: the 3D view is rendered at ~240p and blown up with nearest
// filtering; this pass adds colour-depth reduction with ordered dithering,
// film grain, desaturation, vignette and the hurt / panic / fade effects.

uniform float levels = 20.0;
uniform float dither = 1.0;
uniform float grain = 0.07;
uniform float desat = 0.3;
uniform float vignette = 0.55;
uniform float hurt = 0.0;
uniform float panic = 0.0;
uniform float fade = 0.0;
uniform float fever = 0.0;
uniform vec2 px_size = vec2(426.0, 240.0);

float bayer(vec2 p) {
	int x = int(mod(p.x, 4.0));
	int y = int(mod(p.y, 4.0));
	int i = x + y * 4;
	float m[16] = float[](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
	return m[i] / 16.0;
}

void fragment() {
	vec2 uv = UV;
	float wob = panic * 0.004 + fever * 0.006;
	uv += wob * vec2(sin(TIME * 2.3 + uv.y * 14.0), cos(TIME * 1.9 + uv.x * 12.0));
	vec3 c = texture(TEXTURE, uv).rgb;
	float ca = panic * 0.004 + hurt * 0.003;
	c.r = mix(c.r, texture(TEXTURE, uv + vec2(ca, 0.0)).r, step(0.0005, ca));
	c.b = mix(c.b, texture(TEXTURE, uv - vec2(ca, 0.0)).b, step(0.0005, ca));
	float l = dot(c, vec3(0.299, 0.587, 0.114));
	c = mix(c, vec3(l), desat + panic * 0.3);
	c *= vec3(1.0, 0.99, 0.95);
	c = mix(c, c * vec3(1.1, 0.95, 0.7), fever);
	vec2 px = floor(uv * px_size);
	float n = fract(sin(dot(px + floor(TIME * 20.0) * vec2(17.0, 31.0), vec2(12.9898, 78.233))) * 43758.5453);
	c += (n - 0.5) * grain * (0.6 + 0.8 * (1.0 - l));
	c = floor(c * levels + bayer(px) * dither) / levels;
	vec2 q = uv - 0.5;
	float v = dot(q, q);
	c *= clamp(1.0 - v * (vignette + panic * 0.9) * 2.0, 0.0, 1.0);
	float pulse = 0.75 + 0.25 * sin(TIME * 6.0);
	c = mix(c, vec3(0.32, 0.0, 0.0), clamp(hurt * smoothstep(0.03, 0.42, v) * pulse * 1.6, 0.0, 0.85));
	c *= 1.0 - fade;
	COLOR = vec4(c, 1.0);
}

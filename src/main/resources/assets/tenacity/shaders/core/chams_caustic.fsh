#version 330



#moj_import <minecraft:globals.glsl>
// The colour comes from a one pixel texture the client keeps in the theme colour (or the picked colour).
uniform sampler2D ChamsColor;
#define chamsAccent vec4(texture(ChamsColor, vec2(0.5)).rgb, 1.0)
#define timeAlpha vec4(GameTime*1200.0,1.0,0.0,0.0)
#define center vec4(0.5,0.5,0.0,0.0)
// Underwater caustic flow on a 2D quad. The classic ~10 sin/cos analytic
// pattern — cheap and recognisably "watery".

in vec2 TexCoord;
in vec2 PatternCoord;
#define ColorModulator vec4(1.0,1.0,1.0,timeAlpha.y)
#define FragColor vec4(1.0)

uniform sampler2D Sampler0;



out vec4 OutColor;

float caustic(vec2 uv, float t) {
    // The pattern is fed a world-space coordinate, which grows without bound. Two things keep it identical everywhere: the coordinate is
    // folded into whole periods of every trig coupling below (so the pattern tiles seamlessly, with a tile far larger than a player), and
    // the explicit factor of the bolt term is the constant -250 the maths was tuned for instead of the coordinate itself.
    vec2 p = mod(uv * 6.28318, 50.26548) - 250.0;
    vec2 i = p;
    float c = 1.0;
    float inten = 0.005;
    for (int n = 0; n < 5; n++) {
        float tn = t * 0.5 + float(n);
        i = p + vec2(cos(tn - i.x) + sin(tn + i.y),
                     sin(tn - i.y) + cos(tn + i.x));
        c += 1.0 / length(vec2(-250.0 / (sin(i.x + tn) / inten),
                               -250.0 / (cos(i.y + tn) / inten)));
    }
    c /= 5.0;
    c = 1.17 - pow(c, 1.4);
    return clamp(pow(abs(c), 8.0), 0.0, 5.0);
}

void main() {
    vec4 mask = texture(Sampler0, TexCoord);
    if (mask.a < 0.01) discard;

    vec3 accent = max(chamsAccent.rgb, vec3(0.05));
    float t = timeAlpha.x;

    // Recentre UVs on the item so the pattern visually follows it.
    vec2 uv = PatternCoord;

    float c1 = caustic(uv * 1.6, t);
    float c2 = caustic(uv * 2.4 + vec2(0.3, -0.2), t * 0.7);
    float c  = max(c1, c2 * 0.7);

    vec3 deep   = accent * 0.18;
    vec3 mid    = accent * 0.85;
    vec3 bright = mix(accent, vec3(1.0), 0.55);

    vec3 col = mix(deep, mid, smoothstep(0.0, 0.5, c));
    col = mix(col, bright * 2.4, smoothstep(0.5, 1.4, c));

    float edge = smoothstep(0.05, 0.35, mask.a);

    OutColor = vec4(col * edge, mask.a) * ColorModulator;
}

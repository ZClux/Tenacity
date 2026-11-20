#version 330



#moj_import <minecraft:globals.glsl>
// The colour comes from a one pixel texture the client keeps in the theme colour (or the picked colour).
uniform sampler2D ChamsColor;
#define chamsAccent vec4(texture(ChamsColor, vec2(0.5)).rgb, 1.0)
#define timeAlpha vec4(GameTime*1200.0,1.0,0.0,0.0)
#define center vec4(0.5,0.5,0.0,0.0)
// Lightning — caustic-style warp tuned for sharp electrical streaks.
// Output alpha is driven by the lightning intensity itself (not by the
// silhouette alone), so everywhere outside the bolts the shader is fully
// transparent and the underlying item stays visible — no more black fill
// covering the rest of the surface.

in vec2 TexCoord;
in vec2 PatternCoord;
#define ColorModulator vec4(1.0,1.0,1.0,timeAlpha.y)
#define FragColor vec4(1.0)

uniform sampler2D Sampler0;



out vec4 OutColor;

float lightning(vec2 uv, float t) {
    // The pattern is fed a world-space coordinate, which grows without bound. Two things keep it identical everywhere: the coordinate is
    // folded into whole periods of every trig coupling below (so the pattern tiles seamlessly, with a tile far larger than a player), and
    // the explicit factor of the bolt term is the constant -250 the maths was tuned for instead of the coordinate itself.
    vec2 p = mod(uv * 7.3, 50.26548) - 250.0;
    vec2 i = p;
    float c = 1.0;
    float inten = 0.0040;
    for (int n = 0; n < 6; n++) {
        float tn = t * 0.55 + float(n);
        i = p + vec2(cos(tn - i.x) + sin(tn + i.y),
                     sin(tn - i.y) + cos(tn + i.x));
        c += 1.0 / length(vec2(-250.0 / (sin(i.x + tn) / inten),
                               -250.0 / (cos(i.y + tn) / inten)));
    }
    c /= 6.0;
    c = 1.20 - pow(c, 1.55);
    // Final pow=10: between the original 12 (too few bolts) and 8 (too many).
    return clamp(pow(abs(c), 10.0), 0.0, 5.0);
}

void main() {
    vec4 mask = texture(Sampler0, TexCoord);
    if (mask.a < 0.01) discard;

    vec3 accent = max(chamsAccent.rgb, vec3(0.05));
    float t = timeAlpha.x;

    vec2 uv = PatternCoord;
    float c = lightning(uv * 1.4, t);

    // Quick irregular flashes via two off-tempo sines.
    float flash = 0.75 + 0.25 * sin(t * 7.0) * sin(t * 5.3);

    // Bolts are PURE accent, just brightened. No mix with white — that was
    // what was bleaching the user's chosen colour. Channels saturate
    // independently so red accent → red bolt, blue accent → blue bolt;
    // only a near-white accent will give white bolts.
    vec3 bolt = accent * (1.8 + flash * 0.5);
    vec3 halo = accent * 0.9;

    // Two zones: bright core (sharp), soft halo around it. Both feed the
    // alpha so anywhere there's no bolt → fully transparent → item below
    // shows through unchanged.
    float core = smoothstep(0.50, 1.2, c);
    float glow = smoothstep(0.10, 0.50, c) * 0.50;
    float a    = max(core, glow);

    vec3 col = mix(halo, bolt, core);

    float silhouette = smoothstep(0.05, 0.35, mask.a);
    OutColor = vec4(col, a * silhouette) * ColorModulator;
    // The hand effect overlays an existing item; preserve the skin beneath its transparent gaps too.
    OutColor = vec4(mix(mask.rgb, OutColor.rgb, OutColor.a), mask.a);
}

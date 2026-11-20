package tenacity.gfx;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.texture.GlTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import tenacity.Tenacity;

/**
 * Hand shaders. While the first person hand (and held item) renders, its output is redirected into a private framebuffer;
 * afterwards that picture is composited back over the scene through a shader that can make it transparent, tint it,
 * colour it with a moving chroma gradient and draw a soft glowing outline around it.
 */
public final class HandFx {
    /** Settings, written by the Hand Shaders module. */
    public static boolean enabled;
    public static float opacity = 1f, tintAmount, glowSize = 4f, glowStrength = 1f, saturation = 1f;
    public static int tint = 0xFFFFFFFF, glowColor = 0xFFFFFFFF;
    public static boolean chromaTint, chromaGlow, glow, hideHand, scanlines, rim, pulse;
    /** Surface effect: 0 none, 1 caustic, 2 plasma, 3 lightning, 4 aurora, 5 galaxy, 6 chrome, 7 holo foil. */
    public static int fx, fxColor = 0xFFFFFFFF;
    public static boolean fxChroma;
    public static float fxAmount = 0.8f, fxSpeed = 1f, fxScale = 1f, aberration, breathe, sweep;
    /** Frosted glass hand (blurred scene behind), refraction, uv distortions and colour grades. */
    public static float frost, refraction, pixelate, posterize, glitch, dissolve, wobble, fresnel;
    public static int frostTint = 0xFFF3F5F8, grade;
    private static float sFrost, sRefr, sPix, sPost, sGlitch, sDiss, sWob, sFres;
    /** Seasonal looks (Seasonal Hand module): 0 off, 1..12 Christmas, 13..24 Halloween. Everything eases in Java. */
    public static int season, seasonColor = 0xFFFFFFFF, seasonAccent = 0xFFFFFFFF;
    public static float seasonAmount = 1f, seasonSpeed = 1f, seasonScale = 1f, seasonGlow = 0.5f, seasonSparkle = 0.5f, seasonEdge = 0.5f, seasonPulse, seasonBright = 1f;
    private static int curSeason;
    private static float sSeason, sSGlow, sSSpark, sSEdge, sSPulse, sSBright, seasonTime;
    private static final KawaseChain CHAIN = new KawaseChain();
    private static final KawaseChain GLOW_CHAIN = new KawaseChain();
    private static boolean glowChainOk, glowChainTried;
    private static int sceneTex, sceneW, sceneH, readFbo, copyFbo;
    private static boolean chainOk, chainTried;
    // smoothed values actually sent to the shader, so every setting change glides instead of snapping
    private static float sOp = 1f, sTint, sGlowSize = 4f, sGlowStr = 1f, sSat = 1f, sFx, sAb, sBreathe, sSweep, fxTime;
    private static int curFx;
    private static long lastNs;

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uHand;
            uniform int uItemGlow, uRingW, uShimmer;
            uniform vec2 uTexel;
            uniform float uOpacity, uTintAmt, uGlowSize, uGlowStr, uSat, uTime, uRingR, uHaloR;
            uniform vec3 uTint, uGlowCol;
            uniform int uChromaTint, uChromaGlow, uGlow, uHide, uScan, uRim, uPulse, uFx, uFxChroma;
            uniform float uFxAmt, uFxTime, uFxScale, uAberr, uBreathe, uSweep;
            uniform vec3 uFxCol, uFrostTint;
            uniform sampler2D uScene, uBlur, uGlowTex;
            uniform float uFrost, uRefr, uPix, uPost, uGlitch, uDiss, uWob, uFres;
            uniform int uGrade;
            uniform int uSeason;
            uniform float uSeasonAmt, uSeasonTime, uSeasonScale, uSGlow, uSSpark, uSEdge, uSPulse, uSBright;
            uniform vec3 uSeasonCol, uSeasonAcc;

            // Crypto's hand_caustic / hand_plasma / hand_lava flow (the analytic sin/cos warp)
            float flow(vec2 p, float t, int it, float inten, float sc, float pw, float ex, float cr) {
                vec2 i = p; float c = 1.0;
                for (int n = 0; n < 6; n++) {
                    if (n >= it) break;
                    float tn = t + float(n) * 0.9;
                    i = p + vec2(cos(tn - i.x) + sin(tn + i.y * cr), sin(tn - i.y) + cos(tn + i.x * cr));
                    c += 1.0 / length(vec2(p.x / (sin(i.x + tn) / inten), p.y / (cos(i.y + tn) / inten)));
                }
                c /= float(it);
                c = sc - pow(c, pw);
                return clamp(pow(abs(c), ex), 0.0, 5.0);
            }
            float h21(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            float vnoise(vec2 p) {
                vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
                return mix(mix(h21(i), h21(i + vec2(1, 0)), f.x), mix(h21(i + vec2(0, 1)), h21(i + vec2(1, 1)), f.x), f.y);
            }
            float fbm(vec2 p) { float v = 0.0, w = 0.5; for (int i = 0; i < 5; i++) { v += w * vnoise(p); p = p * 2.03 + 7.1; w *= 0.5; } return v; }

            vec3 chroma(float t) { return 0.5 + 0.5 * cos(6.2831 * (t + vec3(0.0, 0.33, 0.67))); }

""" + SeasonHand.FUNCS + """
            void main() {
                // uv distortions of the hand picture itself
                vec2 huv = vUv;
                if (uWob > 0.001) huv += vec2(sin(vUv.y * 38.0 + uTime * 3.1), cos(vUv.x * 31.0 + uTime * 2.4)) * uWob * 5.0 * uTexel;
                if (uGlitch > 0.001) {
                    float slice = floor(vUv.y * 42.0), tt = floor(uTime * 9.0);
                    float on = step(1.0 - uGlitch * 0.35, fract(sin(slice * 12.9898 + tt * 78.233) * 43758.5453));
                    huv.x += on * (fract(sin(slice * 3.7 + tt) * 9631.2) - 0.5) * uGlitch * 60.0 * uTexel.x;
                }
                if (uPix > 0.5) { vec2 ps = uPix * uTexel; huv = (floor(huv / ps) + 0.5) * ps; }
                vec4 h = texture(uHand, huv);
                float a = h.a;
                vec3 col = h.rgb / max(a, 1e-4);
                if (uAberr > 0.001) { // chromatic split of the hand itself
                    vec2 o = (vUv - 0.5) * uAberr * 14.0 * uTexel;
                    vec4 hr = texture(uHand, vUv + o), hb = texture(uHand, vUv - o);
                    col = vec3(hr.r / max(hr.a, 1e-4), col.g, hb.b / max(hb.a, 1e-4));
                    a = max(a, max(hr.a, hb.a) * 0.85);
                }
                float l = dot(col, vec3(0.299, 0.587, 0.114));
                col = mix(vec3(l), col, uSat);
                vec3 tint = uChromaTint == 1 ? chroma(vUv.x * 0.6 + vUv.y * 0.3 + uTime * 0.25) : uTint;
                col = mix(col, col * 0.35 + tint * 0.75, uTintAmt);
                if (uRim == 1 && a > 0.01) {
                    // light the inner silhouette edge (where the hand ends inside a small ring)
                    float mn = 1.0;
                    for (int i = 0; i < 12; i++) {
                        float an = float(i) * 0.5235988;
                        mn = min(mn, texture(uHand, vUv + vec2(cos(an), sin(an)) * 3.0 * uTexel).a);
                    }
                    vec3 rc = uChromaGlow == 1 ? chroma(vUv.y - uTime * 0.3) : uGlowCol;
                    col = mix(col, rc, (1.0 - mn) * 0.85);
                }
                // surface effects, faded in by uFxAmt and soft at the silhouette edge
                if (uFxAmt > 0.001 && a > 0.01) {
                    vec2 uv = vUv * vec2(uTexel.y / uTexel.x, 1.0) * uFxScale;
                    float t = uFxTime;
                    vec3 ac = uFxChroma == 1 ? chroma(vUv.x * 0.5 + vUv.y * 0.3 + t * 0.08) : max(uFxCol, vec3(0.05));
                    vec3 fc = col; float add = 0.0, k = 1.0;
                    if (uFx == 1) {
                        float c = max(flow(uv * 6.28 * 1.6 - 250.0, t * 0.5, 5, 0.005, 1.17, 1.4, 8.0, 1.0),
                                      0.7 * flow(uv * 6.28 * 2.4 - 249.0, t * 0.35, 5, 0.005, 1.17, 1.4, 8.0, 1.0));
                        fc = mix(ac * 0.18, ac * 0.85, smoothstep(0.0, 0.5, c));
                        fc = mix(fc, mix(ac, vec3(1.0), 0.55) * 2.4, smoothstep(0.5, 1.4, c));
                    } else if (uFx == 2) {
                        float c = max(flow(uv * 7.0 * 1.8 - 250.0, t * 0.7, 6, 0.006, 1.16, 1.35, 8.0, 1.3),
                                      0.7 * flow(uv * 7.0 * 2.6 - 255.0, t * 0.91, 6, 0.006, 1.16, 1.35, 8.0, 1.3));
                        fc = mix(ac * 0.18, ac, smoothstep(0.0, 0.5, c));
                        fc = mix(fc, mix(ac, ac.gbr * 1.4, 0.5), smoothstep(0.4, 1.0, c));
                        fc = mix(fc, mix(ac, vec3(1.0), 0.55) * 2.2, smoothstep(0.85, 1.6, c));
                    } else if (uFx == 3) { // lightning: additive bolts, the hand stays visible between them
                        float c = flow(uv * 5.5 * 1.4 - 250.0, t * 0.55, 6, 0.004, 1.2, 1.55, 10.0, 1.0);
                        float flash = 0.75 + 0.25 * sin(t * 7.0) * sin(t * 5.3);
                        float core = smoothstep(0.5, 1.2, c);
                        add = max(core, smoothstep(0.1, 0.5, c) * 0.5);
                        fc = mix(ac * 0.9, ac * (1.8 + flash * 0.5), core);
                    } else if (uFx == 4) { // aurora: soft flowing curtains
                        float n = fbm(vec2(uv.x * 2.0 + t * 0.15, uv.y * 0.6 - t * 0.05));
                        float band = smoothstep(0.0, 0.5, sin(uv.x * 4.0 + n * 6.0 + t * 0.6) * 0.5 + 0.5);
                        vec3 a2 = mix(ac.brg, vec3(0.3, 1.0, 0.7), 0.5);
                        fc = mix(col * 0.25, mix(ac, a2, n), band) + band * band * 0.25;
                    } else if (uFx == 5) { // galaxy: nebula + twinkling stars
                        vec2 p = uv * 3.0 + vec2(t * 0.04, 0.0);
                        float neb = fbm(p + fbm(p + t * 0.05) * 1.5);
                        fc = mix(vec3(0.02, 0.0, 0.06), mix(ac, ac.brg, neb), smoothstep(0.3, 0.9, neb));
                        vec2 g = uv * 60.0; float s = h21(floor(g));
                        fc += vec3(step(0.965, s) * smoothstep(0.35, 0.0, length(fract(g) - 0.5)) * (0.6 + 0.4 * sin(t * 3.0 + s * 40.0)));
                    } else if (uFx == 6) { // liquid chrome: fake environment reflection from the shading
                        float l2 = dot(col, vec3(0.333));
                        float r = sin(l2 * 9.0 + uv.y * 3.0 - t * 0.8) * 0.5 + 0.5;
                        fc = mix(vec3(0.05), mix(ac, vec3(1.0), 0.6), smoothstep(0.25, 0.75, r)) + pow(r, 12.0) * 0.6;
                    } else if (uFx == 7) { // holographic foil: iridescence that slides with time
                        float l2 = dot(col, vec3(0.333));
                        fc = mix(col, chroma(l2 * 1.5 + uv.x * 0.6 + uv.y * 0.4 + t * 0.12), 0.75) + 0.08;
                        k = 0.85;
                    }
                    float edge = smoothstep(0.05, 0.35, a);
                    if (uFx == 3) col += fc * add * uFxAmt * edge;
                    else col = mix(col, fc, uFxAmt * edge * k);
                }
                if (uSeason > 0 && uSeasonAmt > 0.001 && a > 0.01) {
                    float eMin = 1.0;
                    if (uSEdge > 0.01) {
                        for (int i = 0; i < 8; i++) { float an = float(i) * 0.7853982; eMin = min(eMin, texture(uHand, vUv + vec2(cos(an), sin(an)) * 4.0 * uTexel).a); }
                    }
                    col = seasonal(col, a, vUv, eMin);
                }
                if (uSweep > 0.001) { // soft specular sweep crossing the hand
                    float d = (vUv.x * 0.7 + vUv.y) - fract(uTime * 0.18) * 3.0 + 0.8;
                    col += exp(-d * d * 90.0) * uSweep * 0.9;
                }
                // colour grades
                if (uGrade == 1) { float L = dot(col, vec3(0.299, 0.587, 0.114));                    // thermal
                    col = L < 0.33 ? mix(vec3(0.05, 0.0, 0.3), vec3(0.8, 0.0, 0.6), L / 0.33)
                        : L < 0.66 ? mix(vec3(0.8, 0.0, 0.6), vec3(1.0, 0.55, 0.0), (L - 0.33) / 0.33)
                        : mix(vec3(1.0, 0.55, 0.0), vec3(1.0, 1.0, 0.75), (L - 0.66) / 0.34); }
                else if (uGrade == 2) col = 1.0 - col;                                                          // invert
                else if (uGrade == 3) { float L = dot(col, vec3(0.299, 0.587, 0.114)); col = vec3(L * 1.07, L * 0.87, L * 0.64); } // sepia
                else if (uGrade == 4) { float L = dot(col, vec3(0.299, 0.587, 0.114)); col = vec3(0.1, 1.0, 0.25) * (L * 1.4 + 0.05) + (fract(sin(dot(gl_FragCoord.xy + uTime, vec2(12.9, 78.2))) * 43758.5) - 0.5) * 0.08; } // night vision
                else if (uGrade == 5) { float L = dot(col, vec3(0.299, 0.587, 0.114)); col = mix(vec3(0.08, 0.05, 0.2), uFxCol, smoothstep(0.1, 0.9, L)); } // duotone
                else if (uGrade == 6) { float L = dot(col, vec3(0.299, 0.587, 0.114)); col = vec3(L); }               // mono
                if (uPost > 0.5) col = floor(col * uPost + 0.5) / uPost;
                // frosted glass / refraction: the scene behind the hand, seen through it
                float glassK = 0.0;
                if ((uFrost > 0.001 || uRefr > 0.001) && a > 0.01) {
                    float lx = dot(texture(uHand, huv + vec2(uTexel.x * 2.0, 0)).rgb - texture(uHand, huv - vec2(uTexel.x * 2.0, 0)).rgb, vec3(0.333));
                    float ly = dot(texture(uHand, huv + vec2(0, uTexel.y * 2.0)).rgb - texture(uHand, huv - vec2(0, uTexel.y * 2.0)).rgb, vec3(0.333));
                    vec2 off = vec2(lx, ly) * uRefr * 60.0 * uTexel + (vUv - 0.5) * uRefr * 0.03;
                    vec3 sharp = texture(uScene, vUv + off).rgb;
                    // same frosted glass as Player Transparency: jittered blur (rough surface), cool white, grain, sheen, rim
                    vec2 jit = (vec2(fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453),
                                     fract(sin(dot(gl_FragCoord.yx + 7.0, vec2(12.9898, 78.233))) * 43758.5453)) - 0.5) * uTexel * 6.0;
                    vec3 soft = texture(uBlur, vUv + off * 1.5 + jit).rgb;
                    vec3 frosted = soft * 0.82 + uFrostTint * 0.30;
                    frosted += (fract(sin(dot(gl_FragCoord.xy + uTime, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) * 0.035;
                    frosted += vec3(0.06) * smoothstep(0.0, 1.0, 0.5 + 0.5 * sin((vUv.x * 2.0 + vUv.y * 5.0) * 3.0 - uTime * 0.8));
                    float mn = 1.0;
                    for (int r = 1; r <= 3; r++)
                        for (int i = 0; i < 12; i++) { float an = float(i) * 0.5235988; mn = min(mn, texture(uHand, huv + vec2(cos(an), sin(an)) * float(r) * 2.0 * uTexel).a); }
                    frosted += uFrostTint * (1.0 - mn) * 0.55;
                    // a whisper of the hand's shading so fingers / item shape still read
                    frosted *= 0.92 + 0.16 * dot(col, vec3(0.333));
                    vec3 glass = mix(sharp, frosted, clamp(uFrost, 0.0, 1.0));
                    glassK = max(uFrost, uRefr * 0.85);
                    col = mix(col, glass, glassK);
                }
                if (uFres > 0.001 && a > 0.01) {
                    float mn = 1.0;
                    for (int i = 0; i < 12; i++) { float an = float(i) * 0.5235988; mn = min(mn, texture(uHand, huv + vec2(cos(an), sin(an)) * 6.0 * uTexel).a); }
                    vec3 fc = uChromaGlow == 1 ? chroma(vUv.y - uTime * 0.2) : uGlowCol;
                    col += fc * pow(1.0 - mn, 1.5) * uFres;
                }
                float ha = uHide == 1 ? 0.0 : a * mix(uOpacity, 1.0, glassK);
                if (uDiss > 0.001) { // dissolve: noise eats the hand away with a glowing burn edge
                    float n = fbm(vUv * 9.0 + vec2(0.0, uTime * 0.12));
                    float th = uDiss * (0.55 + 0.45 * (0.5 + 0.5 * sin(uTime * 0.9)));
                    float edgeB = smoothstep(th, th + 0.06, n);
                    col += (uChromaGlow == 1 ? chroma(n + uTime * 0.2) : uGlowCol) * (1.0 - smoothstep(th + 0.0, th + 0.06, n)) * step(th - 0.04, n) * 2.0;
                    ha *= smoothstep(th - 0.04, th, n) * mix(1.0, edgeB, 0.0) ;
                }
                ha *= 1.0 - uBreathe * (0.5 - 0.5 * cos(uTime * 1.6));
                if (uScan == 1) {
                    float y = gl_FragCoord.y;
                    // smooth scan: a continuous gradient instead of hard 3px stripes, so nobody gets the harsh line pattern
                    float lines = 0.78 + 0.22 * sin(y * 1.35 + uTime * 1.6);
                    float band = smoothstep(0.0, 0.08, abs(fract(vUv.y * 1.5 - uTime * 0.35) - 0.5));
                    ha *= lines * (0.78 + 0.22 * band);
                    col += (1.0 - band) * 0.25;
                }

                // Crypto's hand glow (HandCapture + GlassOutlineShader): the hand mask goes through 3 dual Kawase levels,
                // the glow is the blurred mask minus the sharp one (glowMode 0) and an optional outline ring of width uRingW
                // gaussian blur of the hand mask: 4 rings x 20 directions, radius from the glow size
                // Crypto's hand glow (GlassOutlineShader): the hand mask through a dual Kawase chain,
                // glow = blurred mask minus the sharp mask, plus an optional crisp outline ring
                vec4 blurred = (uGlow == 1 || uItemGlow == 1) ? texture(uGlowTex, vUv) : vec4(0.0);
                float g = 0.0;
                if (uGlow == 1) {
                    g = max(0.0, blurred.a - a) * 2.2 * uGlowStr;
                    if (uRingW > 0 && a < 0.5 && blurred.a > 0.01) {
                        for (int x = -uRingW; x <= uRingW && g < 1.0; x++)
                            for (int y = -uRingW; y <= uRingW; y++)
                                if ((x != 0 || y != 0) && texture(uHand, vUv + vec2(x, y) * uTexel).a > 0.5) { g = 1.0; break; }
                    }
                }
                vec3 gc = uChromaGlow == 1 ? chroma(vUv.y * 0.8 - uTime * 0.3) : uGlowCol;
                if (uItemGlow == 1 && blurred.a > 0.001) { // colour of the hand / held item right next to the glow
                    vec3 ic = blurred.rgb / blurred.a;
                    float mx = max(ic.r, max(ic.g, ic.b));
                    if (mx > 0.04) gc = clamp(ic / mx, 0.0, 1.0);
                }
                if (uShimmer == 1) {
                    float sh = smoothstep(0.08, 0.0, abs(vUv.y - fract(uTime * 0.45) * 1.2 + 0.1)) * 0.65;
                    gc = mix(gc, vec3(1.0), sh);
                }
                float pulsePh = sin(uTime * 1.9);
                float pulseK = uPulse == 1 ? 0.6 + 0.4 * (0.5 + 0.5 * pulsePh) : 1.0;
                // the strength already lives inside g (halo term only, like Crypto); multiplying again would crush
                // the falloff flat so the glow reads as a solid outline
                float ga = clamp(g * pulseK, 0.0, 1.0);
                // premultiplied: hand over glow
                vec3 rgb = col * ha + gc * ga * (1.0 - ha);
                float outA = ha + ga * (1.0 - ha);
                fragColor = vec4(rgb, outA);
            }
            """;

    private static final GlState GL = new GlState();
    public static boolean itemGlow, shimmer;
    public static int ringWidth = 1;
    private static SimpleFramebuffer target;
    private static int prog, vao, fbo;
    private static boolean failed, capturing;
    private static Object oldColor, oldDepth;
    private static final long START = System.nanoTime();

    private HandFx() {
    }

    public static boolean active() {
        return enabled && !failed;
    }

    /** Redirects drawing into the hand buffer. */
    public static void begin() {
        if (!active()) return;
        try {
            var mc = MinecraftClient.getInstance();
            int w = mc.getWindow().getFramebufferWidth(), h = mc.getWindow().getFramebufferHeight();
            if (target == null || target.textureWidth != w || target.textureHeight != h) {
                if (target != null) target.delete();
                target = new SimpleFramebuffer("Tenacity hand", w, h, true);
            }
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorAttachment(), 0, target.getDepthAttachment(), 1);
            oldColor = RenderSystem.outputColorTextureOverride;
            oldDepth = RenderSystem.outputDepthTextureOverride;
            RenderSystem.outputColorTextureOverride = target.getColorAttachmentView();
            RenderSystem.outputDepthTextureOverride = target.getDepthAttachmentView();
            capturing = true;
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("hand shaders failed - disabled", t);
        }
    }

    /** Stops the redirect (after the hand draws were flushed) and composites the hand over the scene. */
    public static void end() {
        if (!capturing) return;
        capturing = false;
        RenderSystem.outputColorTextureOverride = (com.mojang.blaze3d.textures.GpuTextureView) oldColor;
        RenderSystem.outputDepthTextureOverride = (com.mojang.blaze3d.textures.GpuTextureView) oldDepth;
        composite();
    }

    private static void composite() {
        var mc = MinecraftClient.getInstance();
        if (!(mc.getFramebuffer().getColorAttachment() instanceof GlTexture main) || main.isClosed()) return;
        if (!(target.getColorAttachment() instanceof GlTexture hand)) return;
        int w = main.getWidth(0), h = main.getHeight(0);
        GL.save();
        try {
            if (prog == 0) {
                prog = Glass.program(VERT, FRAG);
                if (prog == 0) {
                    failed = true;
                    return;
                }
                vao = GL30.glGenVertexArrays();
                fbo = GL30.glGenFramebuffers();
            }
            int blurTex = 0;
            boolean needScene = frost > 0.001f || refraction > 0.001f || sFrost > 0.001f || sRefr > 0.001f;
            if (needScene) {
                if (!chainTried) {
                    chainTried = true;
                    chainOk = CHAIN.init();
                    readFbo = GL30.glGenFramebuffers();
                    copyFbo = GL30.glGenFramebuffers();
                }
                if (sceneTex == 0 || sceneW != w || sceneH != h) {
                    if (sceneTex != 0) GL11.glDeleteTextures(sceneTex);
                    sceneTex = GL11.glGenTextures();
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTex);
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
                    sceneW = w;
                    sceneH = h;
                }
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glDisable(GL11.GL_STENCIL_TEST);
                GL11.glDepthMask(false);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                org.lwjgl.opengl.GL33.glBindSampler(0, 0);
                GL11.glColorMask(true, true, true, true);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.getGlId(), 0);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sceneTex, 0);
                GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                blurTex = chainOk ? CHAIN.blur(sceneTex, w, h, 4, 1.6f) : sceneTex;
            }
            int glowTex = hand.getGlId();
            if (glow || itemGlow || sGlowStr > 0.01f) {
                if (!glowChainTried) {
                    glowChainTried = true;
                    glowChainOk = GLOW_CHAIN.init();
                }
                if (glowChainOk) {
                    GL11.glDisable(GL11.GL_SCISSOR_TEST);
                    GL11.glDisable(GL11.GL_BLEND);
                    GL11.glDisable(GL11.GL_DEPTH_TEST);
                    GL11.glDisable(GL11.GL_CULL_FACE);
                    GL11.glDisable(GL11.GL_STENCIL_TEST);
                    GL11.glColorMask(true, true, true, true);
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    org.lwjgl.opengl.GL33.glBindSampler(0, 0);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, hand.getGlId());
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
                    float gsz = Math.max(1f, sGlowSize) * (h / 1080f);
                    int levels = gsz < 3f ? 2 : gsz < 7f ? 3 : 4;
                    glowTex = GLOW_CHAIN.blur(hand.getGlId(), w, h, levels, 0.6f + gsz * 0.28f);
                }
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.getGlId(), 0);
            GL11.glViewport(0, 0, w, h);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            org.lwjgl.opengl.GL33.glBindSampler(0, 0);
            org.lwjgl.opengl.GL33.glBindSampler(1, 0);
            org.lwjgl.opengl.GL33.glBindSampler(2, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, needScene ? sceneTex : hand.getGlId());
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, needScene ? blurTex : hand.getGlId());
            org.lwjgl.opengl.GL33.glBindSampler(3, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE3);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glowTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, hand.getGlId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL20.glUseProgram(prog);
            u1i("uHand", 0);
            u1i("uItemGlow", itemGlow ? 1 : 0);
            u1i("uRingW", glow ? ringWidth : 0);
            u1i("uShimmer", shimmer ? 1 : 0);
            GL20.glUniform2f(GL20.glGetUniformLocation(prog, "uTexel"), 1f / w, 1f / h);
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 1f : Math.min(0.1f, (now - lastNs) / 1e9f);
            lastNs = now;
            float k = 1f - (float) Math.exp(-dt * 9f); // ~110 ms glide
            sOp += (opacity - sOp) * k;
            sTint += (tintAmount - sTint) * k;
            sGlowSize += (glowSize - sGlowSize) * k;
            sGlowStr += ((glow ? glowStrength : 0f) - sGlowStr) * k;
            sSat += (saturation - sSat) * k;
            sAb += (aberration - sAb) * k;
            sBreathe += (breathe - sBreathe) * k;
            sSweep += (sweep - sSweep) * k;
            // switching effects fades the old one out, then the new one in
            if (fx != curFx) {
                sFx -= sFx * Math.min(1f, k * 1.6f);
                if (sFx < 0.02f) {
                    curFx = fx;
                    sFx = 0f;
                }
            } else sFx += ((fx == 0 ? 0f : fxAmount) - sFx) * k;
            fxTime += dt * fxSpeed; // integrated, so changing the speed never jumps the pattern
            boolean glowOn = glow || sGlowStr > 0.01f;
            sFrost += (frost - sFrost) * k;
            sRefr += (refraction - sRefr) * k;
            sPix += (pixelate - sPix) * k;
            sPost += (posterize - sPost) * k;
            sGlitch += (glitch - sGlitch) * k;
            sDiss += (dissolve - sDiss) * k;
            sWob += (wobble - sWob) * k;
            sFres += (fresnel - sFres) * k;
            // seasonal look: the old look fades out, then the new one fades in (no popping), every parameter glides
            float kS = 1f - (float) Math.exp(-dt * 6f);
            if (season != curSeason) {
                sSeason -= sSeason * Math.min(1f, kS * 1.8f);
                if (sSeason < 0.03f) {
                    curSeason = season;
                    sSeason = 0f;
                }
            } else sSeason += ((season == 0 ? 0f : seasonAmount) - sSeason) * kS;
            sSGlow += (seasonGlow - sSGlow) * kS;
            sSSpark += (seasonSparkle - sSSpark) * kS;
            sSEdge += (seasonEdge - sSEdge) * kS;
            sSPulse += (seasonPulse - sSPulse) * kS;
            sSBright += (seasonBright - sSBright) * kS;
            seasonTime += dt * seasonSpeed;
            u1i("uSeason", curSeason);
            u1f("uSeasonAmt", sSeason);
            u1f("uSeasonTime", seasonTime);
            u1f("uSeasonScale", seasonScale);
            u1f("uSGlow", sSGlow);
            u1f("uSSpark", sSSpark);
            u1f("uSEdge", sSEdge);
            u1f("uSPulse", sSPulse);
            u1f("uSBright", sSBright);
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uSeasonCol"), ((seasonColor >> 16) & 255) / 255f, ((seasonColor >> 8) & 255) / 255f, (seasonColor & 255) / 255f);
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uSeasonAcc"), ((seasonAccent >> 16) & 255) / 255f, ((seasonAccent >> 8) & 255) / 255f, (seasonAccent & 255) / 255f);
            u1i("uScene", 1);
            u1i("uBlur", 2);
            u1i("uGlowTex", 3);
            u1f("uFrost", needScene ? sFrost : 0f);
            u1f("uRefr", needScene ? sRefr : 0f);
            u1f("uPix", sPix < 1.2f ? 0f : sPix * (h / 1080f));
            u1f("uPost", sPost < 1.5f ? 0f : sPost);
            u1f("uGlitch", sGlitch);
            u1f("uDiss", sDiss);
            u1f("uWob", sWob);
            u1f("uFres", sFres);
            u1i("uGrade", grade);
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uFrostTint"), ((frostTint >> 16) & 255) / 255f, ((frostTint >> 8) & 255) / 255f, (frostTint & 255) / 255f);
            u1f("uOpacity", sOp);
            u1f("uTintAmt", sTint);
            u1f("uGlowSize", sGlowSize * (h / 1080f) * 5f); // halo radius in pixels
            u1f("uGlowStr", sGlowStr);
            u1i("uFx", curFx);
            u1i("uFxChroma", fxChroma ? 1 : 0);
            u1f("uFxAmt", sFx);
            u1f("uFxTime", fxTime);
            u1f("uFxScale", fxScale);
            u1f("uAberr", sAb);
            u1f("uBreathe", sBreathe);
            u1f("uSweep", sSweep);
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uFxCol"), ((fxColor >> 16) & 255) / 255f, ((fxColor >> 8) & 255) / 255f, (fxColor & 255) / 255f);
            // outline radius and halo radius in texels, scaled to the resolution and the Glow Size setting
            float px = h / 1080f;
            float sizeK = Math.max(0.6f, glowSize / 4f);
            float ringR = (1f + ringWidth) * px * sizeK;
            u1f("uRingR", ringR);
            u1f("uHaloR", ringR + 4f); // Crypto: the halo ring sits exactly 4 texels outside the rim ring
            u1f("uSat", sSat);
            u1f("uTime", (System.nanoTime() - START) / 1e9f);
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uTint"), ((tint >> 16) & 255) / 255f, ((tint >> 8) & 255) / 255f, (tint & 255) / 255f);
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uGlowCol"), ((glowColor >> 16) & 255) / 255f, ((glowColor >> 8) & 255) / 255f, (glowColor & 255) / 255f);
            u1i("uChromaTint", chromaTint ? 1 : 0);
            u1i("uChromaGlow", chromaGlow ? 1 : 0);
            u1i("uGlow", glowOn ? 1 : 0);
            u1i("uHide", hideHand ? 1 : 0);
            u1i("uScan", scanlines ? 1 : 0);
            u1i("uRim", rim ? 1 : 0);
            u1i("uPulse", pulse ? 1 : 0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("hand shader composite failed - disabled", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }

    private static void u1f(String n, float v) {
        GL20.glUniform1f(GL20.glGetUniformLocation(prog, n), v);
    }

    private static void u1i(String n, int v) {
        GL20.glUniform1i(GL20.glGetUniformLocation(prog, n), v);
    }
}

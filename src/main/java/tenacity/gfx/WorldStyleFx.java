package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;
import tenacity.Tenacity;
import voided.util.render.Render3D;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * World Style: a full screen pass over the finished world (before the hand and our overlays) that restyles every block.
 * uMode picks the look (1 Frost ... 69 Thunderstorm, 70 None). On top of any look a screen-space reflection can be
 * laid over upward facing surfaces so the ground looks polished / wet.
 */
public final class WorldStyleFx {
    /** 0 off, otherwise 1 + index into WorldStyle's style list. Written by the World Style module. */
    public static int mode;
    public static float intensity = 1f, edges = 1f, grid = 1f, scan = 0.5f, glints = 1f, particles = 1f, wind = 1f, pixel = 4f;
    public static boolean reflection;
    public static float reflStrength = 0.55f, roughness = 0.3f, reflFade = 1f;
    public static int color = 0xFF00E5FF, color2 = 0xFFFF2BD6;
    public static boolean chroma;
    /** Rain on the camera lens, 0 (dry) .. 1 (covered). Driven by the World Style module from the view pitch. */
    public static float lens;

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAG0 = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uScene, uBlur, uDepth;
            uniform mat4 uInv, uVP;
            uniform vec3 uCamFrac, uCamCell, uCol, uCol2;
            uniform vec2 uTexel;
            uniform float uLens, uTime, uInt, uEdge, uGrid, uScan, uGlint, uPart, uRefl, uRough, uWind, uPix;
            uniform int uMode, uChroma, uReflOn;

            vec3 chroma(float t) { return 0.5 + 0.5 * cos(6.2831 * (t + vec3(0.0, 0.33, 0.67))); }
            // sine-free hashes: stable for large world coordinates
            float h21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
            vec2 hh2(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }
            float hash(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }
            float vn(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(h21(i), h21(i + vec2(1.0, 0.0)), f.x), mix(h21(i + vec2(0.0, 1.0)), h21(i + vec2(1.0, 1.0)), f.x), f.y);
            }
            float fbm(vec2 p) {
                float s = 0.0, a = 0.5;
                for (int i = 0; i < 4; i++) { s += a * vn(p); p = p * 2.03 + 17.1; a *= 0.5; }
                return s;
            }
            float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
            vec3 satur(vec3 c, float s) { return mix(vec3(luma(c)), c, s); }
            vec3 aces(vec3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }
            vec3 worldAt(vec2 uv, float d) {
                vec4 w = uInv * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
                return w.xyz / (abs(w.w) < 1e-7 ? 1e-7 : w.w);
            }
            float bayer4(vec2 p) {
                const float M[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
                ivec2 q = ivec2(mod(p, 4.0));
                return (M[q.x + q.y * 4] + 0.5) / 16.0;
            }
            // anti aliased block grid lines on the face plane
            float gridL(vec3 w, vec3 n, float wd) {
                vec3 fw = fwidth(w) * 1.2;
                vec3 g = 1.0 - smoothstep(vec3(0.0), fw + wd, min(fract(w), 1.0 - fract(w)));
                vec3 an = abs(n);
                return an.y > 0.5 ? max(g.x, g.z) : an.x > 0.5 ? max(g.y, g.z) : max(g.x, g.y);
            }
            // Kuwahara: mean of the least varying quadrant (n x n taps, stp pixels apart)
            vec3 kuwa(vec2 uv, float stp, int n) {
                vec3 best = vec3(0.0);
                float bv = 1e9;
                float cnt = float(n * n);
                for (int q = 0; q < 4; q++) {
                    vec2 sg = vec2((q == 1 || q == 3) ? -1.0 : 1.0, q >= 2 ? -1.0 : 1.0);
                    vec3 m = vec3(0.0), s = vec3(0.0);
                    for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                        vec3 c = texture(uScene, uv + vec2(float(i), float(j)) * sg * uTexel * stp).rgb;
                        m += c; s += c * c;
                    }
                    m /= cnt;
                    s = s / cnt - m * m;
                    float v = s.r + s.g + s.b;
                    if (v < bv) { bv = v; best = m; }
                }
                return best;
            }
            // luminance sobel magnitude
            float sob(vec2 uv, float r) {
                vec2 o = uTexel * r;
                float tl = luma(texture(uScene, uv + vec2(-o.x, o.y)).rgb), tc = luma(texture(uScene, uv + vec2(0.0, o.y)).rgb), tr = luma(texture(uScene, uv + o).rgb);
                float ml = luma(texture(uScene, uv - vec2(o.x, 0.0)).rgb), mr = luma(texture(uScene, uv + vec2(o.x, 0.0)).rgb);
                float bl = luma(texture(uScene, uv - o).rgb), bc = luma(texture(uScene, uv - vec2(0.0, o.y)).rgb), br = luma(texture(uScene, uv + vec2(o.x, -o.y)).rgb);
                float gx = (tr + 2.0 * mr + br) - (tl + 2.0 * ml + bl);
                float gy = (tl + 2.0 * tc + tr) - (bl + 2.0 * bc + br);
                return length(vec2(gx, gy));
            }
            // geometry ink: silhouettes (depth jumps) and creases (plane breaks) sampled r pixels out
            float inkAt(vec2 uv, vec3 Pc, float dc, float r) {
                float e = 0.0;
                for (int i = 0; i < 4; i++) {
                    vec2 o = (i == 0 ? vec2(1.0, 0.0) : i == 1 ? vec2(0.0, 1.0) : i == 2 ? vec2(0.7071, 0.7071) : vec2(0.7071, -0.7071)) * uTexel * r;
                    float da = texture(uDepth, uv + o).r, db = texture(uDepth, uv - o).r;
                    vec3 Pa = worldAt(uv + o, da), Pb = worldAt(uv - o, db);
                    float la = da >= 0.99999 ? dc * 4.0 : length(Pa);
                    float lb = db >= 0.99999 ? dc * 4.0 : length(Pb);
                    float de = min(abs(la + lb - 2.0 * dc), max(abs(la - dc), abs(lb - dc))) / max(dc, 0.5);
                    // creases over a wider baseline so depth quantisation noise can't fake them
                    vec2 o3 = o * 3.0;
                    float dA = texture(uDepth, uv + o3).r, dB = texture(uDepth, uv - o3).r;
                    vec3 PA = worldAt(uv + o3, dA), PB = worldAt(uv - o3, dB);
                    float cr = (dA >= 0.99999 || dB >= 0.99999) ? 0.0 : length(PA + PB - 2.0 * Pc) / (length(PA - PB) + 1e-3);
                    e = max(e, max(smoothstep(0.02, 0.07, de), smoothstep(0.18, 0.32, cr) * (1.0 - smoothstep(12.0, 32.0, dc))));
                }
                e = (e >= 0.0 && e <= 1.0) ? e : 0.0;
                return e * (1.0 - smoothstep(70.0, 170.0, dc));
            }
            // voronoi: (nearest, second nearest, cell hash)
            vec3 voro(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                float d1 = 8.0, d2 = 8.0;
                vec2 id = vec2(0.0);
                for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                    vec2 g = vec2(float(x), float(y));
                    vec2 r = g + hh2(i + g) * 0.85 + 0.075 - f;
                    float dd = dot(r, r);
                    if (dd < d1) { d2 = d1; d1 = dd; id = i + g; } else if (dd < d2) d2 = dd;
                }
                return vec3(sqrt(d1), sqrt(d2), h21(id));
            }
            // ---------- molten flow: domain-warped fbm advected along a flow with two crossfaded phases
            float wfbm(vec2 p, float t) {
                vec2 q = vec2(fbm(p + vec2(0.0, t * 0.05)), fbm(p + vec2(5.2, 1.3) - t * 0.04));
                return fbm(p + q * 1.7);
            }
            float flowN(vec2 p, vec2 flow, float spd) {
                float ph0 = fract(uTime * spd), ph1 = fract(uTime * spd + 0.5);
                float wgt = abs(ph0 * 2.0 - 1.0);
                float n0 = wfbm(p - flow * ph0, uTime);
                float n1 = wfbm(p - flow * ph1 + vec2(3.7, 1.9), uTime);
                float n = mix(n0, n1, wgt);
                // undo the contrast loss of the crossfade
                float k = 1.0 / sqrt(wgt * wgt + (1.0 - wgt) * (1.0 - wgt));
                return clamp((n - 0.47) * k + 0.47, 0.0, 1.0);
            }
            vec3 lavaPal(float n, float seed) {
                float h = smoothstep(0.3, 0.72, n);
                float pulse = 0.88 + 0.12 * sin(uTime * 1.1 + n * 8.0 + seed);
                vec3 c = mix(vec3(0.08, 0.025, 0.015), vec3(0.75, 0.1, 0.01), smoothstep(0.0, 0.35, h));
                c = mix(c, vec3(1.0, 0.42, 0.04), smoothstep(0.3, 0.65, h));
                c = mix(c, vec3(1.0, 0.86, 0.45), smoothstep(0.7, 1.0, h));
                return c * pulse * (1.0 + 0.5 * smoothstep(0.6, 1.0, h));
            }
            // ---------- 3D rain: walk the (wind sheared) xz cell grid along the view ray; every cell owns one world-locked drop.
            // Drops are thin, mostly transparent streaks (the caller refracts the background through them). Streak length = fall
            // speed x exposure, width never drops below the pixel footprint (energy kept) so far rain becomes fine haze, and near
            // drops get a depth-of-field circle of confusion so they turn into big faint out-of-focus smears. Density is modulated
            // by world-locked gust sheets drifting with the wind; cell centres, speeds and phases are jittered by separate hashes.
            vec2 gWind = vec2(0.14, 0.06);
            float gShape = 0.0, gMoteId = 0.0, pa0 = 0.001;
            float rainL(vec3 D, float maxT, float pa, float cs, float dens, float seed, float rad, float tmin, float R, float bri) {
                vec3 O = uCamCell + uCamFrac;
                vec2 o = O.xz - gWind * O.y;
                vec2 dd = D.xz - gWind * D.y;
                dd = vec2(abs(dd.x) < 1e-4 ? 1e-4 : dd.x, abs(dd.y) < 1e-4 ? 1e-4 : dd.y);
                float d2 = dot(dd, dd);
                vec2 cell = floor(o / cs);
                vec2 st = sign(dd);
                vec2 tD = abs(cs / dd);
                vec2 tM = ((cell + max(st, 0.0)) * cs - o) / dd;
                float acc = 0.0;
                float lim = min(maxT, R);
                float sl = sqrt(1.0 + dot(gWind, gWind));
                // gusts: drifting world-locked sheets of heavier / lighter rain (sampled once per ray, mid-way into the layer)
                vec2 gp = o + dd * min(lim, R * 0.5) - gWind * uTime * 25.0;
                float gust = vn(gp * 0.05 + seed) * 1.3 + vn(gp * 0.19) * 0.4 - 0.25;
                float dg = dens * gust;
                for (int i = 0; i < 64; i++) {
                    vec2 r = hh2(cell * 1.013 + seed);
                    if (r.x < dg) {
                        vec2 r2 = hh2(cell * 0.917 + seed + 31.7);
                        float r3 = h21(cell * 1.37 + seed + 7.3);
                        vec2 L = (cell + 0.06 + 0.88 * r2) * cs;
                        float t = dot(L - o, dd) / d2;
                        if (t > tmin * 0.5 && t < lim) {
                            float dp = length(o + dd * t - L) / sl;
                            float coc = 0.016 * max(1.0 - t / 3.5, 0.0);               // near drops: out of focus
                            float w = max(max(rad, pa * t * 0.8), coc);
                            // quadratic falloff: the old linear rad/w exactly cancelled the growing cell count along long
                            // rays, so the far field stayed as bright as the near field no matter the distance. Squaring
                            // restores the decay (near drops have w == rad, so they render identically).
                            float dim = rad / w;
                            float line = exp(-dp * dp / (w * w) * 2.2) * dim * dim;
                            float Y = O.y + D.y * t;
                            float spd = 11.0 + r3 * 8.0 + r2.y * 3.0;
                            float per = 4.0 + r.y * 5.0;
                            float s = fract((Y + uTime * spd) / per + r.y * 7.0 + r2.x * 3.0);
                            float len = spd * 0.028 + coc * 3.0;                        // exposure ~1/36 s
                            float u = s * per / len;
                            float seg = smoothstep(0.0, 0.08, u) * (1.0 - smoothstep(0.55, 1.0, u));
                            float fade = smoothstep(tmin * 0.5, tmin, t) * (1.0 - smoothstep(R * 0.4, R, t));
                            acc += line * seg * fade * (0.55 + 0.45 * r3);
                        }
                    }
                    if (min(tM.x, tM.y) > lim) break;
                    if (tM.x < tM.y) { tM.x += tD.x; cell.x += st.x; } else { tM.y += tD.y; cell.y += st.y; }
                }
                return acc * bri;
            }
            // fine far rain haze: vertical streak texture in view-direction space, only where the ray is long enough to hold rain
            float rainHaze(vec3 D, float maxT) {
                vec2 h = normalize(D.xz + 1e-5);
                float y = D.y * 6.0 / sqrt(max(1.0 - D.y * D.y, 0.05)) + uTime * 6.5;
                float wx = (h.x * 260.0 + h.y * 37.0) + D.y * 60.0 * uWind;
                float wz = (h.y * 260.0 - h.x * 23.0) + D.y * 60.0 * uWind;
                float a = vn(vec2(wx, y)) * abs(h.y) + vn(vec2(wz, y + 3.1)) * abs(h.x);
                float a2 = vn(vec2(wx * 1.9 + 5.0, y * 1.4 + uTime * 2.0)) * abs(h.y) + vn(vec2(wz * 1.9, y * 1.4 + uTime * 2.0 + 9.0)) * abs(h.x);
                // soft ramps (no hard threshold) and a fade once the streak frequency nears the pixel pitch: no moire, no dots
                float aaF = 1.0 - smoothstep(0.35, 0.9, pa0 * 260.0);
                return (smoothstep(0.35, 1.0, a) * 0.6 + smoothstep(0.4, 1.0, a2) * 0.4) * mix(0.55, 1.0, aaF);
            }
            // distance-weighted rain visibility: full close up, then a long smooth exponential-ish fade into mist. Never a
            // hard cutoff; sky pixels (rays that leave the world) use the far value so the horizon keeps its curtain.
            float rainVis(float dist, bool isSky) {
                float d = isSky ? 220.0 : dist;
                return mix(1.0, 0.0, smoothstep(40.0, 260.0, d)) * 0.9 + 0.1 * exp(-d * 0.01);
            }
            // layered rain: fine mid/far field, sharp near drops, a few huge out-of-focus drops right at the lens + far haze
            float rainFx(vec3 D, float maxT, float pa, float dens, float seed) {
                float a = rainL(D, maxT, pa, 0.37, 0.55 * dens, seed, 0.0028, 0.6, 8.0, 1.0);
                // the far layer and haze used to stack into a solid wall past a few chunks; thinned so distance reads as
                // weather instead of whiteout. Near rain and lens drops are untouched.
                a += rainL(D, maxT, pa, 0.83, 0.45 * dens, seed + 11.0, 0.004, 3.5, 36.0, 0.9);
                a += rainL(D, maxT, pa, 1.3, 0.35 * dens, seed + 23.0, 0.003, 0.25, 1.6, 0.8);
                // far layer: big cells, long reach, its own slow fade-in/out so the transition from the mid layer is seamless
                a += rainL(D, maxT, pa, 2.6, 0.5 * dens, seed + 37.0, 0.006, 20.0, 150.0, 0.55);
                float far = 1.0 - exp(-max(min(maxT, 400.0) - 12.0, 0.0) * 0.012);
                a += rainHaze(D, maxT) * far * 0.12 * dens;
                return a;
            }
            // distant rain curtains: soft veils that drift with the wind (direction space, wrapped on the horizontal circle)
            float rainSheets(vec3 D) {
                vec2 h = normalize(D.xz + 1e-5);
                vec2 q = h * 2.3 + vec2(0.0, 0.0);
                float t = uTime * 0.035 * (0.5 + uWind);
                float b = fbm(q * 1.0 + vec2(t, -t * 0.6) + D.y * vec2(0.3, 0.9));
                float b2 = vn(q * 6.0 + vec2(-t * 3.0, t * 2.0) + D.y * 3.0);
                return smoothstep(0.38, 0.75, b * 0.85 + b2 * 0.25) * (1.0 - smoothstep(-0.05, 0.5, D.y)) * smoothstep(-0.6, -0.1, D.y + 0.45);
            }
            // composite rain coverage: the streak refracts (flipped, slightly offset) background, catches a faint highlight
            vec3 rainMix(vec3 c, float rn, vec3 tint, float amt) {
                float a = clamp(rn * amt, 0.0, 1.0);
                vec2 g = vec2(dFdx(rn), dFdy(rn));
                vec2 ruv = clamp(vUv + vec2(-g.x * 0.03, 0.018 + g.y * 0.01), 0.001, 0.999);
                vec3 bg = texture(uBlur, ruv).rgb;
                float l0 = luma(texture(uBlur, vUv).rgb);
                vec3 bgN = bg * (luma(c) + 0.06) / (l0 + 0.06);                // refracted background, in the graded tone of c
                vec3 refr = max(bgN, c) * 1.18 + tint * 0.22 + 0.025;
                return mix(c, refr, clamp(a * 0.75, 0.0, 0.75));
            }
            // soft round motes (snow, embers, petals, ash, bubbles); speed > 0 falls, < 0 rises
            float motes(vec3 D, float maxT, float R, float pa, float cs, float per, float speed, float drift, float size, float dens, float seed, vec2 wind) {
                vec3 O = uCamCell + uCamFrac;
                vec2 o = O.xz - wind * uTime;
                vec2 dd = D.xz;
                dd = vec2(abs(dd.x) < 1e-4 ? 1e-4 : dd.x, abs(dd.y) < 1e-4 ? 1e-4 : dd.y);
                float d2 = dot(dd, dd);
                vec2 cell = floor(o / cs);
                vec2 st = sign(dd);
                vec2 tD = abs(cs / dd);
                vec2 tM = ((cell + max(st, 0.0)) * cs - o) / dd;
                float acc = 0.0;
                float lim = min(maxT, R);
                for (int i = 0; i < 48; i++) {
                    vec2 r = hh2(cell + seed);
                    if (r.x < dens) {
                        vec2 L = (cell + 0.25 + 0.5 * hh2(cell + seed + 17.0)) * cs;
                        float t0 = dot(L - o, dd) / d2;
                        if (t0 > -1.0 && t0 < lim + 1.0) {
                            float Y0 = O.y + D.y * t0;
                            float u = (Y0 + speed * uTime) / per + r.y;
                            float k = floor(u);
                            float yk = (k + 0.5 - r.y) * per - speed * uTime;
                            float ph = h21(vec2(k * 1.31 + cell.x, cell.y * 7.0 + seed));
                            vec2 Lk = L + drift * cs * 0.22 * vec2(sin(uTime * 0.9 + ph * 40.0 + yk * 0.7), cos(uTime * 0.7 + ph * 30.0 + yk * 0.5));
                            vec3 F = vec3(Lk.x - o.x, yk - O.y, Lk.y - o.y);
                            float tt = dot(F, D);
                            if (tt > 0.25 && tt < lim) {
                                vec3 e3 = F - D * tt;
                                float dp = length(e3);
                                if (gShape > 0.5) {
                                    // tumbling leaf: rotating, flipping ellipse in the view plane
                                    vec3 ux = normalize(cross(D, vec3(0.0, 1.0, 0.0)) + vec3(1e-4, 0.0, 0.0));
                                    vec3 uy = cross(ux, D);
                                    vec2 q = vec2(dot(e3, ux), dot(e3, uy));
                                    float an2 = uTime * (0.8 + ph * 1.6) + ph * 40.0;
                                    q = mat2(cos(an2), -sin(an2), sin(an2), cos(an2)) * q;
                                    q.x /= max(abs(cos(uTime * (1.3 + ph) + ph * 9.0)), 0.3);
                                    q.y *= 2.1;
                                    dp = length(q);
                                }
                                float rad = size * (0.6 + 0.8 * ph);
                                float w = max(rad, pa * tt * 1.3);
                                float fade = smoothstep(0.25, 0.9, tt) * (1.0 - smoothstep(R * 0.45, R, tt));
                                float ct = (1.0 - smoothstep(w * (gShape > 0.5 ? 0.75 : 0.3), w, dp)) * (rad / w) * fade * (gShape > 0.5 ? 1.0 : 0.75 + 0.25 * sin(uTime * 3.0 + ph * 20.0));
                                if (ct > acc * 0.5) gMoteId = ph;
                                acc += ct;
                            }
                        }
                    }
                    if (min(tM.x, tM.y) > lim) break;
                    if (tM.x < tM.y) { tM.x += tD.x; cell.x += st.x; } else { tM.y += tD.y; cell.y += st.y; }
                }
                return acc;
            }
            // world-locked rain impacts on upward faces: x = ring/splash brightness, yz = surface slope (perturbs the reflection).
            // Four rotated, jittered layers of impacts; every impact launches a damped capillary wave packet (2-3 concentric
            // crests) that expands and decays, plus a tiny splash crown with bouncing droplets the instant the drop lands.
            // pud scales the waves (standing water) vs. hard ground (splash only).
            vec3 ripples2(vec2 xz, float pud) {
                vec3 acc = vec3(0.0);
                for (int l = 0; l < 4; l++) {
                    float fl = float(l);
                    float an = fl * 1.7 + 0.4;
                    mat2 rm = mat2(cos(an), -sin(an), sin(an), cos(an));
                    vec2 rq = rm * (xz * (1.25 + fl * 0.35)) + vec2(fl * 17.3, fl * 9.1);
                    vec2 rid = floor(rq);
                    float rate = 0.6 + 0.6 * h21(rid + 2.7 + fl);
                    float tt = uTime * rate + h21(rid * 1.3 + fl * 9.0);
                    float ph = fract(tt), cyc = floor(tt);
                    vec2 cen = 0.5 + (hh2(rid + 1.3 + cyc * 0.173 + fl) - 0.5) * 0.4;
                    vec2 dv = fract(rq) - cen;
                    float rr = length(dv);
                    float on = step(h21(rid + 5.3 + fl + cyc * 0.31), 0.6);
                    float R0 = ph * 0.34;
                    float x = rr - R0;
                    float k = 46.0 - ph * 18.0;                                    // wavelength stretches as it spreads
                    float env = exp(-x * x / (0.0016 + ph * 0.004)) * smoothstep(0.0, 0.04, -x + 0.04) ;
                    float dec = on * (1.0 - ph) * (1.0 - ph) * (1.0 - ph * 0.5) * smoothstep(0.5, 0.36, rr);
                    float aa = 1.0 - smoothstep(0.5, 1.6, fwidth(rr) * k);        // waves below the pixel footprint vanish
                    float wv = sin(x * k) * env * dec * aa;
                    float sl = cos(x * k) * k * env * dec * aa;
                    acc.x += max(wv, 0.0) * (0.25 + 0.75 * pud);
                    vec2 nd = dv / max(rr, 1e-3);
                    acc.yz += nd * sl * 0.006 * (0.25 + 0.75 * pud);
                    // splash crown + droplets thrown out and falling back (hard surfaces mostly)
                    float sp = 1.0 - smoothstep(0.0, 0.12, ph);
                    float crown = exp(-pow((rr - ph * 0.5) / 0.012, 2.0)) * sp;
                    float da = atan(dv.y, dv.x) * 7.0 / 6.2831;
                    float drops = exp(-pow((rr - ph * 1.0) / 0.01, 2.0)) * smoothstep(0.35, 0.0, abs(fract(da + h21(rid + cyc)) - 0.5) * 2.0) * (1.0 - smoothstep(0.0, 0.2, ph));
                    acc.x += on * (crown * 0.9 + drops * 0.8 + exp(-rr * rr / 0.0006) * sp * 1.2) * (1.0 - 0.5 * pud) * aa;
                }
                return acc;
            }
            vec2 toUv(vec3 Q) { vec4 cp = uVP * vec4(Q, 1.0); return clamp(cp.xy / max(cp.w, 1e-4) * 0.5 + 0.5, 0.001, 0.999); }
            // face-planar delta (as in fq) back to a world vector
            vec3 fq2w(vec2 dl, vec3 an) { return an.y > 0.5 ? vec3(dl.x, 0.0, dl.y) : an.x > 0.5 ? vec3(0.0, dl.y, dl.x) : vec3(dl.x, dl.y, 0.0); }
            // voronoi: (offset to nearest centre, border distance, cell hash)
            vec4 voroC(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                float d1 = 8.0;
                vec2 mr = vec2(0.0), mg = vec2(0.0);
                for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
                    vec2 g = vec2(float(x), float(y));
                    vec2 r = g + hh2(i + g) * 0.8 + 0.1 - f;
                    float dd = dot(r, r);
                    if (dd < d1) { d1 = dd; mr = r; mg = g; }
                }
                float bd = 8.0;
                for (int y = -2; y <= 2; y++) for (int x = -2; x <= 2; x++) {
                    vec2 g = mg + vec2(float(x), float(y));
                    vec2 r = g + hh2(i + g) * 0.8 + 0.1 - f;
                    if (dot(mr - r, mr - r) > 1e-5) bd = min(bd, dot(0.5 * (mr + r), normalize(r - mr)));
                }
                return vec4(mr, bd, h21(i + mg));
            }
            // painted cumulus over a sky colour (self shadowed towards the sun)
            vec3 cumulus(vec3 dir, vec3 s, vec3 sunD, vec3 lit, vec3 shade, float cov) {
                vec2 cu = dir.xz / max(dir.y + 0.12, 0.06) * 0.55 + vec2(uTime * 0.008, 0.0);
                float f = fbm(cu) * 0.7 + fbm(cu * 2.3 + 4.0) * 0.3;
                float dens = smoothstep(0.5 - cov, 0.72 - cov, f);
                float sh = smoothstep(0.42 - cov, 0.8 - cov, fbm((cu + sunD.xz * 0.12) * 1.0) * 0.7 + fbm((cu + sunD.xz * 0.12) * 2.3 + 4.0) * 0.3);
                vec3 cc = mix(lit, shade, sh * 0.85);
                cc += lit * 0.25 * smoothstep(0.0, 0.25, dens) * (1.0 - smoothstep(0.25, 1.0, dens));   // silver lining
                return mix(s, cc, dens * smoothstep(0.0, 0.12, dir.y + 0.02));
            }
            const int PALH[36] = int[36](0x4a4a52, 0x7a7a82, 0xa8a8b0, 0xd4d4da, 0xbe4a2f, 0xd77643, 0xead4aa, 0xe4a672, 0xb86f50, 0x733e39, 0x3e2731, 0xa22633, 0xe43b44, 0xf77622, 0xfeae34,
                0xfee761, 0x63c74d, 0x3e8948, 0x265c42, 0x193c3e, 0x124e89, 0x0099db, 0x2ce8f5, 0xffffff, 0xc0cbdc, 0x8b9bb4, 0x5a6988, 0x3a4466,
                0x262b44, 0x181425, 0xff0044, 0x68386c, 0xb55088, 0xf6757a, 0xe8b796, 0xc28569);
            vec3 hexc(int h) { return vec3(float((h >> 16) & 255), float((h >> 8) & 255), float(h & 255)) / 255.0; }
            vec3 palNear(vec3 c, float th) {
                vec3 b1 = vec3(0.0), b2 = vec3(0.0);
                float d1 = 1e9, d2 = 1e9;
                for (int i = 0; i < 36; i++) {
                    vec3 p = hexc(PALH[i]);
                    vec3 df = (c - p) * vec3(0.85, 1.0, 0.7);
                    float dd = sqrt(dot(df, df));
                    if (dd < d1) { d2 = d1; b2 = b1; d1 = dd; b1 = p; } else if (dd < d2) { d2 = dd; b2 = p; }
                }
                // ordered dither between the two closest palette entries, only when they are close in brightness
                float t = d1 / max(d1 + d2, 1e-5);
                return (th < t * 0.9 && length(b1 - b2) < 0.3) ? b2 : b1;
            }
            vec3 yiq(vec3 c) { return vec3(dot(c, vec3(0.299, 0.587, 0.114)), dot(c, vec3(0.596, -0.274, -0.322)), dot(c, vec3(0.211, -0.523, 0.312))); }
            vec3 rgbq(vec3 y) { return vec3(y.x + 0.956 * y.y + 0.621 * y.z, y.x - 0.272 * y.y - 0.647 * y.z, y.x - 1.106 * y.y + 1.703 * y.z); }
            void main() {
                vec3 scene = texture(uScene, vUv).rgb;
                float d = texture(uDepth, vUv).r;
                bool sky = d >= 0.99999;
                vec3 P = worldAt(vUv, d);
                float dist = length(P);
                // normal from depth with 3px one-sided differences (the shorter side wins at edges): stays stable on
                // far grazing ground where the depth buffer quantises row by row
                vec2 ox = vec2(uTexel.x * 3.0, 0.0), oy = vec2(0.0, uTexel.y * 3.0);
                vec3 Pr = worldAt(vUv + ox, texture(uDepth, vUv + ox).r) - P, Pl = P - worldAt(vUv - ox, texture(uDepth, vUv - ox).r);
                vec3 Pu = worldAt(vUv + oy, texture(uDepth, vUv + oy).r) - P, Pd = P - worldAt(vUv - oy, texture(uDepth, vUv - oy).r);
                vec3 tx = dot(Pr, Pr) < dot(Pl, Pl) ? Pr : Pl;
                vec3 ty = dot(Pu, Pu) < dot(Pd, Pd) ? Pu : Pd;
                vec3 cn = cross(tx, ty);
                vec3 N = dot(cn, cn) > 1e-12 ? normalize(cn) : normalize(cross(dFdx(P), dFdy(P)));
                if (dot(N, P) > 0.0) N = -N;
                vec3 rd = normalize(worldAt(vUv, 0.5));
                float pa = max(length(dFdx(rd)), length(dFdy(rd)));
                pa0 = pa;
                vec3 V = sky ? rd : P / max(dist, 1e-4);
                vec3 dir = V;
                float maxT = sky ? 1e4 : dist;
                float e = 0.0;
                if (!sky) {
                    for (int i = 0; i < 4; i++) {
                        vec2 o = vec2(i == 0 ? 1.0 : i == 1 ? -1.0 : 0.0, i == 2 ? 1.0 : i == 3 ? -1.0 : 0.0) * uTexel * 1.5;
                        float dn = texture(uDepth, vUv + o).r;
                        float ln = dn >= 0.99999 ? dist * 4.0 : length(worldAt(vUv + o, dn));
                        e = max(e, abs(ln - dist) / max(dist, 0.5));
                    }
                }
                float edge = smoothstep(0.02, 0.09, e) * (1.0 - smoothstep(70.0, 180.0, dist));
                vec3 wp = P + uCamFrac;
                vec3 W = wp + uCamCell;                                        // world position (xz wrapped every 4096 blocks)
                float skyM = sky ? 0.0 : 1.0;
                float top = smoothstep(0.6, 0.9, N.y) * skyM;
                float facing = clamp(dot(N, -V), 0.0, 1.0);
                float rim = pow(1.0 - facing, 3.0) * skyM;
                float gline = gridL(wp, N, 0.012) * skyM;
                float gsoft = gridL(wp, N, 0.09) * skyM;
                vec3 an = abs(N);
                vec2 fq = an.y > 0.5 ? W.xz : an.x > 0.5 ? W.zy : W.xy;      // face-planar world coords (y second on walls)
                vec2 su = gl_FragCoord.xy * uTexel.y;
                float grain = hash(vec3(gl_FragCoord.xy, fract(uTime * 0.37) * 100.0)) - 0.5;
                float vig = 1.0 - smoothstep(0.35, 0.95, length((vUv - 0.5) * vec2(1.2, 1.0)));
                float skyK = clamp(luma(scene) * 1.8 + 0.12, 0.2, 1.0);
                vec3 c1 = uChroma == 1 ? chroma(uTime * 0.05) : uCol;
                vec3 c2 = uChroma == 1 ? chroma(uTime * 0.05 + 0.33) : uCol2;
                float mx0 = max(max(scene.r, scene.g), scene.b), mn0 = min(min(scene.r, scene.g), scene.b);
                // vanilla lava pixels (bright orange): get replaced by smooth flowing molten rock in the lava looks
                float lavaPx = skyM * smoothstep(0.55, 0.8, scene.r) * smoothstep(0.25, 0.45, scene.r - scene.b) * smoothstep(0.45, 0.2, scene.b / max(scene.r, 0.01))
                             * smoothstep(0.08, 0.25, scene.r - scene.g);

                // ---------------- wet ground + screen space reflection on upward faces
                gWind = vec2(0.14, 0.06) * uWind;
                bool wet = uMode == 28 || uMode == 38 || uMode == 41 || uMode == 69;
                float rStr = uReflOn == 1 ? uRefl : 0.0;
                vec3 rip = vec3(0.0);
                float pud = 0.0;
                if (wet && !sky) {
                    float upW = smoothstep(0.6, 0.9, N.y);
                    pud = smoothstep(0.44, 0.6, fbm(W.xz * 0.2 + 3.1)) * upW;
                    rip = ripples2(W.xz, pud) * upW * (1.0 - smoothstep(3.0, 14.0, dist)) * min(uPart, 1.5);
                    rip.yz *= 1.0 - smoothstep(3.0, 12.0, dist);
                    // soaked: darker, slightly richer albedo; standing water darker still
                    scene = satur(scene, 1.0 + 0.2 * upW) * mix(1.0, mix(0.62, 0.42, pud), upW);
                    // walls: soaked darker, with thin rivulets running down (world-locked columns, each with its own speed)
                    // only real block faces (they lie on whole/half block planes), not entities
                    float wpl = an.x > an.z ? W.x : W.z;
                    float onBlk = 1.0 - smoothstep(0.015, 0.04, abs(fract(wpl * 2.0 + 0.5) - 0.5) * 0.5);
                    float wall = (1.0 - upW) * smoothstep(0.5, 0.2, abs(N.y)) * (1.0 - smoothstep(8.0, 28.0, dist)) * onBlk;
                    float colx = fq.x * 9.0;
                    float cid = floor(colx);
                    float rh = h21(vec2(cid, 3.7));
                    float cw = abs(fract(colx) - 0.5 - (rh - 0.5) * 0.5);
                    float flow = vn(vec2(cid * 3.1, fq.y * (1.2 + rh) + uTime * (0.6 + rh * 1.2)));
                    float riv = step(0.55, rh) * (1.0 - smoothstep(0.03, 0.09 + fwidth(colx), cw)) * smoothstep(0.45, 0.7, flow) * wall;
                    scene *= mix(1.0, 0.8, (1.0 - upW) * skyM) * (1.0 - riv * 0.25);
                    float bead = smoothstep(0.86, 0.95, flow) * riv;
                    scene += texture(uBlur, vUv + vec2(0.0, 0.02)).rgb * (riv * 0.12 + bead * 0.35);
                }
                if ((rStr > 0.0 || wet) && !sky) {
                    float up = smoothstep(0.72, 0.95, N.y) * (1.0 - smoothstep(90.0, 170.0, dist));
                    if (up > 0.001) {
                        float rgh = uReflOn == 1 ? uRough : 0.3;
                        if (wet) rgh = mix(min(rgh, 0.3) + 0.08, 0.0, pud);
                        float str = wet ? max(rStr, mix(0.55, 1.0, pud)) : rStr;
                        vec2 micro = wet ? (vec2(vn(W.xz * 2.3 + uTime * 0.2), vn(W.xz * 2.3 - uTime * 0.2 + 5.0)) - 0.5) * 0.05 * (1.0 - pud) : vec2(0.0);
                        vec3 n = normalize(vec3(-rip.y - micro.x, 1.0, -rip.z - micro.y));
                        vec3 R = reflect(V, n);
                        // start a little above the surface along the normal (grows with distance: depth quantisation) so the
                        // ray never re-hits the floor it left; steep looks down use a bigger lift because the floor depth is coarser
                        float steep = smoothstep(0.55, 0.95, -V.y);
                        vec3 O = P + vec3(0.0, 0.03 + dist * 0.0015 + steep * 0.04, 0.0);
                        float ign = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
                        float stp = 0.04 + dist * 0.005;
                        stp *= 1.0 - steep * 0.35;                                  // finer steps when looking down
                        float t = stp * (0.35 + ign);
                        float pt = 0.0, thick = 0.4;
                        vec2 lastUv = vUv;
                        bool hit = false, lastSky = false;
                        for (int i = 0; i < 72; i++) {
                            vec3 Q = O + R * t;
                            vec4 cp = uVP * vec4(Q, 1.0);
                            if (cp.w <= 0.01) break;
                            vec2 suv = cp.xy / cp.w * 0.5 + 0.5;
                            if (suv.x < 0.0 || suv.x > 1.0 || suv.y < 0.0 || suv.y > 1.0) break;
                            lastUv = suv;
                            float sd = texture(uDepth, suv).r;
                            lastSky = sd >= 0.99999;
                            if (!lastSky) {
                                float diff = length(Q) - length(worldAt(suv, sd));
                                thick = max(0.3, stp * 2.2) + t * 0.015;
                                // rays leave an upward face going up: anything at or below the start height is the floor itself
                                if (diff > 0.0 && diff < thick && worldAt(suv, sd).y > O.y + 0.04) { hit = true; break; }
                            }
                            pt = t;
                            t += stp;
                            stp *= 1.075;
                        }
                        vec2 huv = lastUv;
                        float conf = 0.0, hT = t;
                        if (hit) {
                            float lo = pt, hi = t, fd = 0.0;
                            for (int k = 0; k < 8; k++) {
                                float mid = 0.5 * (lo + hi);
                                vec3 Q = O + R * mid;
                                vec4 cp = uVP * vec4(Q, 1.0);
                                vec2 suv = cp.xy / cp.w * 0.5 + 0.5;
                                float sd = texture(uDepth, suv).r;
                                float diff = sd < 0.99999 ? length(Q) - length(worldAt(suv, sd)) : -1.0;
                                if (diff > 0.0) { hi = mid; fd = diff; } else lo = mid;
                            }
                            hT = hi;
                            vec4 cp = uVP * vec4(O + R * hi, 1.0);
                            huv = cp.xy / cp.w * 0.5 + 0.5;
                            // thickness test after refinement: a ray that slipped behind an object never converges -> reject
                            conf = (1.0 - smoothstep(thick * 0.5, thick, fd)) * (1.0 - smoothstep(45.0, 120.0, hi)) * smoothstep(0.02, 0.15, hi);
                        } else if (R.y > 0.0) {
                            // no hit: mirror the sky straight along the reflected direction (continuous in R, so no step banding);
                            // when that direction is off screen fall back to the sky along the top rows (dimmer, only an estimate)
                            vec4 ci = uVP * vec4(O + R * 4000.0, 1.0);
                            vec2 iuv = ci.w > 0.01 ? ci.xy / ci.w * 0.5 + 0.5 : vec2(vUv.x, 2.0);
                            vec2 cuv = clamp(iuv, 0.002, 0.998);
                            float off = length(iuv - cuv);
                            if (off > 0.0) cuv = vec2(cuv.x, min(cuv.y, 0.975));
                            huv = cuv;
                            // sample a 3-tap vertical sky coverage (soft 0..1) instead of one binary depth test: no pixel pops
                            float skyCov = (texture(uDepth, cuv).r >= 0.99999 ? 1.0 : 0.0) * 0.5
                                         + (texture(uDepth, cuv + vec2(0.0, -0.02)).r >= 0.99999 ? 1.0 : 0.0) * 0.25
                                         + (texture(uDepth, cuv + vec2(0.0, -0.045)).r >= 0.99999 ? 1.0 : 0.0) * 0.25;
                            conf = skyCov * mix(1.0, 0.5, smoothstep(0.0, 0.25, off));
                        }
                        vec2 eb = smoothstep(vec2(0.0), vec2(0.07), huv) * smoothstep(vec2(1.0), vec2(0.93), huv);
                        if (hit) conf *= eb.x * eb.y;
                        conf *= smoothstep(-0.95, -0.6, dot(R, V));   // rays bouncing straight back at the camera have nothing on screen
                        // steep downward views mirror whatever is behind the camera (off screen): ease the reflection out with
                        // pitch so it never pops, but keep a floor so the wet sheen survives
                        conf *= 1.0 - smoothstep(0.62, 0.97, -V.y) * 0.85;
                        // cone blur grows with roughness and with how far the reflected ray travelled
                        float rad = clamp((rgh * rgh * 0.5 + rgh * 0.12) * hT / (hT + dist * 0.5 + 1.0), 0.0, 0.07) + rgh * uTexel.y * 2.0;
                        vec3 rc = texture(uScene, huv).rgb;
                        if (rad > uTexel.y * 0.75) {
                            vec3 acc = rc;
                            float wsum = 1.0;
                            float a0 = ign * 6.2831;
                            vec2 asp = vec2(uTexel.x / uTexel.y, 1.0 + (wet ? 1.6 : 0.4));   // wet streets smear lights vertically
                            for (int k = 0; k < 12; k++) {
                                float fk = float(k) + 0.5;
                                float a = a0 + fk * 2.39996;
                                vec2 o = vec2(cos(a), sin(a)) * sqrt(fk / 12.0) * rad * asp;
                                float wk = 1.0 - fk / 14.0;
                                acc += mix(texture(uScene, huv + o).rgb, texture(uBlur, huv + o).rgb, smoothstep(0.015, 0.05, rad)) * wk;
                                wsum += wk;
                            }
                            rc = acc / wsum;
                        }
                        if (wet) {
                            // wet asphalt: bright light sources smear into long vertical streaks towards the viewer
                            vec3 sm = vec3(0.0);
                            for (int k = 0; k < 6; k++) {
                                float fk = (float(k) + ign) / 6.0;
                                vec3 sb = texture(uBlur, clamp(huv + vec2(0.0, (fk - 0.35) * 0.12 * (1.0 - pud * 0.6)), 0.001, 0.999)).rgb;
                                sm += max(sb - 0.55, 0.0) * (1.0 - fk * 0.6);
                            }
                            rc += sm * 0.45;
                        }
                        float F0 = wet ? 0.02 : 0.04;
                        float fres = F0 + (1.0 - F0) * pow(1.0 - clamp(dot(-V, n), 0.0, 1.0), 5.0);
                        float w = str * up * conf * mix(wet ? 0.3 : 0.45, 1.0, fres) * (1.0 - edge * 0.5);
                        scene = mix(scene, rc, clamp(w, 0.0, 0.95));
                    }
                }

                vec3 bl = texture(uBlur, vUv).rgb;
                vec3 bloom = max(bl - 0.55, 0.0) * 1.5;
                float lum = luma(scene);
                vec3 outc = scene;

                if (uMode == 1) {
                    // ---------------- frost (unchanged)
                    vec2 jit = (vec2(hash(vec3(gl_FragCoord.xy, 1.0)), hash(vec3(gl_FragCoord.yx, 7.0))) - 0.5) * uTexel * 6.0;
                    vec3 blurred = texture(uBlur, vUv + jit).rgb;
                    vec3 tint = vec3(0.953, 0.961, 0.973);
                    vec3 f = blurred * 0.82 + tint * 0.30;
                    f += (hash(vec3(gl_FragCoord.xy, uTime)) - 0.5) * 0.035;
                    float sheen = smoothstep(0.0, 1.0, 0.5 + 0.5 * sin((vUv.x * 2.0 + vUv.y * 5.0) * 3.0 - uTime * 0.8));
                    f += vec3(0.06) * sheen;
                    f += tint * edge * 0.55 * uEdge;
                    outc = mix(scene, f, 0.76);
                } else if (uMode == 2) {
                    // ---------------- cyber: dark wireframe city
                    float fog = sky ? 1.0 : smoothstep(10.0, 110.0, dist);
                    vec3 col = mix(scene * vec3(0.35, 0.38, 0.5), vec3(lum) * 0.25, 0.5) * 0.7;
                    float ink = inkAt(vUv, P, dist, 1.5) * skyM;
                    if (!sky) {
                        float pulse = 0.7 + 0.3 * sin(uTime * 2.0 - dist * 0.25);
                        col += c1 * (gline * 0.9 + gsoft * 0.16) * uGrid * pulse * (1.0 - fog);
                        col += mix(c1, c2, 0.5 + 0.5 * N.y) * max(edge, ink) * 1.4 * uEdge * (1.0 - fog * 0.7);
                        float ring = 1.0 - smoothstep(0.0, 1.2, abs(dist - mod(uTime * 18.0, 140.0)));
                        col += c2 * ring * 0.6;
                        col += c1 * bloom * 0.5;
                    } else {
                        float h = clamp(dir.y, -0.2, 1.0);
                        col = mix(c2 * 0.55, vec3(0.02, 0.0, 0.06), smoothstep(-0.05, 0.6, h));
                        col += c1 * pow(max(0.0, 1.0 - abs(h) * 6.0), 4.0) * 0.4;
                    }
                    col = mix(col, vec3(0.02, 0.01, 0.06) + c2 * 0.08, sky ? 0.0 : fog * 0.85);
                    col *= 1.0 - uScan * 0.18 * (0.5 + 0.5 * sin(gl_FragCoord.y * 1.6 + uTime * 4.0));
                    outc = col * mix(0.75, 1.0, vig);
                } else if (uMode == 3) { // night: moon-lit from one side, misty valleys, stars, fireflies
                    float fog = sky ? 0.0 : 1.0 - exp(-dist * 0.025);
                    vec3 Lm = normalize(vec3(0.4, 0.45, -0.8));
                    float moon = mix(0.45, 1.15, smoothstep(-0.2, 0.6, dot(N, Lm)));
                    vec3 c = satur(scene, 0.4) * vec3(0.42, 0.52, 0.85) * (0.5 + 0.6 * lum) * moon;
                    c += max(scene - 0.72, 0.0) * vec3(1.0, 0.75, 0.45) * 2.0 + bloom * vec3(1.0, 0.8, 0.55) * 0.6;
                    c += vec3(0.65, 0.8, 1.0) * (edge * 0.18 * uEdge + rim * 0.08) * (1.0 - fog);
                    float mist = smoothstep(0.35, 0.75, fbm(W.xz * 0.06 + vec2(uTime * 0.02, 0.0))) * smoothstep(8.0, 40.0, dist) * skyM;
                    c = mix(c, vec3(0.02, 0.035, 0.08), fog * 0.85);
                    c = mix(c, vec3(0.16, 0.2, 0.3), mist * 0.35);
                    if (sky) {
                        c = mix(vec3(0.06, 0.08, 0.17), vec3(0.0, 0.01, 0.035), smoothstep(-0.05, 0.7, dir.y));
                        vec3 sg = dir * 260.0;
                        float st = hash(floor(sg));
                        float star = step(0.996, st) * smoothstep(0.5, 0.1, length(fract(sg) - 0.5)) * (0.6 + 0.4 * sin(uTime * 3.0 + st * 80.0));
                        c += vec3(0.9, 0.95, 1.0) * star * smoothstep(-0.05, 0.15, dir.y);
                    }
                    float ff = motes(dir, maxT, 30.0, pa, 3.0, 4.0, 0.15, 3.0, 0.03, 0.35, 41.0, vec2(0.05, 0.02));
                    c += vec3(1.0, 0.9, 0.35) * ff * 1.6 * uPart * (0.5 + 0.5 * sin(uTime * 2.3 + dir.x * 40.0));
                    outc = c * mix(0.75, 1.0, vig);
                } else if (uMode == 4) { // vapor: synthwave, dithered neon gradient, floor grid, striped sun
                    float fog = sky ? 0.0 : smoothstep(20.0, 120.0, dist);
                    float lq = lum + (bayer4(floor(gl_FragCoord.xy / 2.0)) - 0.5) * 0.18;
                    lq = floor(lq * 6.0 + 0.5) / 6.0;
                    vec3 c = mix(vec3(0.32, 0.06, 0.45), vec3(0.35, 1.0, 0.95), smoothstep(0.05, 0.95, lq)) * (0.45 + lq) * 0.85 + scene * 0.15;
                    c += vec3(1.0, 0.35, 0.85) * (gline * 0.55 + gsoft * 0.12) * uGrid * (1.0 - fog);
                    c += vec3(1.0, 0.4, 0.9) * max(edge, inkAt(vUv, P, dist, 1.5) * skyM) * 0.6 * uEdge + bloom * vec3(1.0, 0.5, 0.9) * 0.5;
                    c = mix(c, vec3(0.55, 0.2, 0.6), fog * 0.6);
                    if (sky) {
                        c = mix(vec3(1.0, 0.5, 0.55), vec3(0.18, 0.04, 0.32), smoothstep(-0.05, 0.55, dir.y));
            """;
    private static final String FRAG1 = """
                        vec3 sunD = normalize(vec3(0.0, 0.14, 1.0));
                        float sd2 = length(dir - sunD);
                        float sun = 1.0 - smoothstep(0.165, 0.175, sd2);
                        float cut = dir.y < sunD.y ? smoothstep(-0.25, 0.25, sin(dir.y * 220.0 + uTime * 2.0)) : 1.0;
                        vec3 sunC = mix(vec3(1.0, 0.2, 0.6), vec3(1.0, 0.88, 0.3), smoothstep(sunD.y - 0.17, sunD.y + 0.17, dir.y));
                        c = mix(c, sunC, sun * cut);
                        c += vec3(1.0, 0.4, 0.6) * exp(-sd2 * 6.0) * 0.25;
                    }
                    float ca = 0.0025;
                    c.r = mix(c.r, c.r + (luma(texture(uScene, vUv + vec2(ca, 0.0)).rgb) - lum) * 0.6, skyM);
                    outc = c * mix(0.8, 1.0, vig) * (0.94 + 0.06 * sin(gl_FragCoord.y * 1.5));
                } else if (uMode == 5) { // toon: flat colour cells, banded directional light, thick ink
                    vec3 base = kuwa(vUv, 1.4, 3);
                    float b0 = luma(base);
                    vec3 hue = base / max(b0, 0.03);
                    vec3 Ld = normalize(vec3(0.45, 0.8, 0.35));
                    float ndl = sky ? 1.0 : dot(N, Ld) * 0.5 + 0.5;
                    float lit = clamp(b0 * 1.15 * (0.5 + 0.7 * ndl), 0.0, 1.0) * 3.0;
                    float fwl = fwidth(lit) + 0.06;
                    float bq = (floor(lit) + smoothstep(0.5 - fwl, 0.5 + fwl, fract(lit))) / 3.0;
                    vec3 c = satur(clamp(hue * (0.08 + bq * 0.72), 0.0, 1.2), 1.35);
                    float spec = smoothstep(0.9, 0.93, dot(reflect(V, N), Ld)) * skyM;
                    c += vec3(1.0) * spec * 0.25 + vec3(1.0, 0.95, 0.85) * smoothstep(0.55, 0.65, rim) * 0.25;
                    float ink = max(inkAt(vUv, P, dist, 2.5) * skyM, smoothstep(0.35, 0.6, sob(vUv, 1.5)) * 0.6);
                    c *= 1.0 - clamp(ink * uEdge, 0.0, 1.0) * 0.92;
                    if (sky) c = floor(satur(scene, 1.25) * 6.0 + 0.5) / 6.0;
                    outc = c;
                } else if (uMode == 6) { // noir: hard key light, venetian blind shadows, ink, grain
                    vec3 Ld = normalize(vec3(-0.6, 0.55, 0.45));
                    float key = smoothstep(-0.05, 0.12, dot(N, Ld));
                    float bp = dot(W, vec3(0.35, 1.3, 0.2));
                    float bf = abs(fract(bp) - 0.5);
                    float fwb = fwidth(bp) + 0.02;
                    float blinds = smoothstep(0.17 - fwb, 0.17 + fwb, bf);
                    float light = sky ? 1.0 : mix(0.22, 1.25, key * mix(0.3, 1.0, blinds));
                    float l = smoothstep(0.06, 0.8, lum * light);
                    vec3 c = vec3(l * l * (3.0 - 2.0 * l));
                    c += vec3(1.0) * luma(bloom) * 0.6;
                    c *= 1.0 - inkAt(vUv, P, dist, 1.5) * skyM * 0.85 * uEdge;
                    c = mix(c, vec3(0.12), (sky ? 0.0 : 1.0 - exp(-dist * 0.02)) * 0.6);
                    c += grain * 0.08;
                    c *= 1.0 - smoothstep(0.45, 0.95, length(vUv - 0.5)) * 0.65;
                    outc = c;
                } else if (uMode == 7) { // thermal camera: low-res sensor, heat from light + facing, white hot outlines
                    vec2 px = uTexel * 3.0;
                    vec2 cuv = (floor(vUv / px) + 0.5) * px;
                    vec3 sc = mix(texture(uScene, cuv).rgb, texture(uBlur, cuv).rgb, 0.5);
                    float t = clamp(luma(sc) * 1.1 + facing * 0.15 * skyM - (sky ? 0.25 : 0.0) + max(luma(bloom), 0.0) * 0.6, 0.0, 1.0);
                    t += edge * 0.15 + (h21(floor(gl_FragCoord.xy / 3.0) + floor(uTime * 20.0)) - 0.5) * 0.04;
                    t = clamp(t, 0.0, 1.0);
                    vec3 c = t < 0.25 ? mix(vec3(0.02, 0.0, 0.2), vec3(0.35, 0.0, 0.6), t / 0.25)
                           : t < 0.5 ? mix(vec3(0.35, 0.0, 0.6), vec3(0.9, 0.1, 0.35), (t - 0.25) / 0.25)
                           : t < 0.75 ? mix(vec3(0.9, 0.1, 0.35), vec3(1.0, 0.6, 0.0), (t - 0.5) / 0.25)
                           : mix(vec3(1.0, 0.6, 0.0), vec3(1.0, 1.0, 0.85), (t - 0.75) / 0.25);
                    c += vec3(1.0, 0.95, 0.8) * smoothstep(0.25, 0.5, sob(cuv, 2.0)) * 0.25 * uEdge;
                    c *= 0.93 + 0.07 * sin(gl_FragCoord.y * 2.1);
                    outc = c;
                } else if (uMode == 8) { // dream: depth of field around the crosshair, glow, wavy air, floating bokeh
                    vec2 wv = vec2(sin(vUv.y * 9.0 + uTime * 0.8), cos(vUv.x * 7.0 + uTime * 0.6)) * uTexel * 3.0;
                    float fdp = texture(uDepth, vec2(0.5)).r;
                    float focus = fdp >= 0.99999 ? 60.0 : length(worldAt(vec2(0.5), fdp));
                    float coc = clamp(abs((sky ? 200.0 : dist) - focus) / max(focus, 2.0) * 1.4, 0.0, 1.0);
                    vec3 sc = texture(uScene, vUv + wv).rgb;
                    vec3 c = mix(sc, texture(uBlur, vUv + wv).rgb * 1.08, smoothstep(0.1, 0.8, coc));
                    c = c * 0.8 + bl * 0.3 + max(bl - 0.5, 0.0) * 0.9;
                    c = mix(c, vec3(1.0, 0.86, 0.95), 0.1);
                    c = mix(vec3(dot(c, vec3(0.333))), c, 0.8);
                    float bk = motes(dir, maxT, 22.0, pa, 2.2, 3.0, 0.08, 2.0, 0.09, 0.4, 61.0, vec2(0.0));
                    c += chroma(dir.x * 0.6 + dir.y + uTime * 0.03) * bk * 0.45 * uPart;
                    outc = mix(vec3(1.0, 0.92, 0.97), c, mix(0.65, 1.0, vig));
                } else if (uMode == 9) { // matrix: world-locked falling glyph columns on every surface
                    vec3 g = vec3(0.1, 1.0, 0.35);
                    vec3 c = g * lum * 0.35 + g * max(edge, inkAt(vUv, P, dist, 1.5) * skyM) * 0.9 * uEdge + g * luma(bloom) * 0.4;
                    vec2 q = fq * 3.0;
                    vec2 ci = floor(q), cf = fract(q);
                    float colSp = 0.6 + h21(vec2(ci.x, 3.1)) * 1.6;
                    float f = fract(-q.y * 0.035 - uTime * colSp * 0.12 + h21(vec2(ci.x, 7.7)));
                    vec2 sub = floor(cf * vec2(4.0, 6.0));
                    float bit = step(0.45, h21(ci * 13.1 + sub * 1.7 + floor(uTime * 2.0 + h21(ci) * 20.0) * 0.37));
                    vec2 m2 = smoothstep(vec2(0.08), vec2(0.16), cf) * smoothstep(vec2(0.92), vec2(0.84), cf);
                    float detail = (1.0 - smoothstep(8.0, 30.0, dist)) * skyM;
                    float glyph = bit * m2.x * m2.y;
                    float tr = pow(f, 4.0);
                    c += g * mix(tr * 0.25, glyph * (tr * 1.1 + 0.08), detail);
                    c += vec3(0.8, 1.0, 0.85) * smoothstep(0.975, 1.0, f) * glyph * detail * 0.6;
                    if (sky) {
                        vec2 cell = floor(gl_FragCoord.xy / vec2(10.0, 14.0));
                        float sp = 3.0 + h21(vec2(cell.x, 1.0)) * 6.0;
                        float head = fract(-uTime * sp * 0.05 + h21(vec2(cell.x, 3.0)));
                        float trail = smoothstep(0.25, 0.0, fract(fract(cell.y * 0.02) - head));
                        vec2 inC = fract(gl_FragCoord.xy / vec2(10.0, 14.0));
                        float gl2 = step(0.45, h21(cell + floor(uTime * 8.0) * 3.1)) * step(0.15, inC.x) * step(inC.x, 0.85) * step(0.1, inC.y) * step(inC.y, 0.9);
                        c = g * trail * gl2 * 0.35;
                    }
                    outc = c * mix(0.7, 1.0, vig);
                } else if (uMode == 10) { // sunset: golden hour grade, long haze, god rays
                    float fog = sky ? 0.0 : 1.0 - exp(-dist * 0.012);
                    vec3 sunD = normalize(vec3(0.8, 0.08, 0.3));
                    float side = dot(N, sunD);
                    vec3 c = scene * vec3(1.18, 0.95, 0.72) * (sky ? 1.0 : mix(0.7, 1.25, smoothstep(-0.3, 0.6, side)));
                    c += bloom * vec3(1.0, 0.7, 0.4) * 0.7;
                    c = mix(c, vec3(1.0, 0.6, 0.36), fog * 0.65);
                    c += vec3(1.0, 0.75, 0.45) * (edge * 0.15 * uEdge + rim * 0.25 * smoothstep(0.0, 0.5, dot(V, sunD)));
                    if (sky) {
                        c = mix(vec3(1.0, 0.52, 0.28), vec3(0.3, 0.22, 0.5), smoothstep(-0.05, 0.6, dir.y));
                        float sd2 = max(dot(dir, sunD), 0.0);
                        c += vec3(1.0, 0.75, 0.4) * (smoothstep(0.9993, 0.9996, sd2) * 1.5 + pow(sd2, 12.0) * 0.5);
                    }
                    vec4 sp = uVP * vec4(sunD * 400.0, 1.0);
                    if (sp.w > 0.0) {
                        vec2 suv = sp.xy / sp.w * 0.5 + 0.5;
                        float ray = 0.0;
                        float jit = h21(gl_FragCoord.xy);
                        for (int i = 0; i < 20; i++) {
                            vec2 tuv = mix(vUv, suv, (float(i) + jit) / 20.0);
                            ray += texture(uDepth, tuv).r >= 0.99999 ? 1.0 : 0.0;
                        }
                        ray /= 20.0;
                        float toward = pow(max(dot(dir, sunD), 0.0), 3.0);
                        c += vec3(1.0, 0.7, 0.4) * ray * toward * 0.6;
                    }
                    outc = aces(c * 1.1) * mix(0.8, 1.0, vig);
                } else if (uMode == 11) { // underwater: wobble, caustics, deep fog, rising bubbles
                    vec2 wob = vec2(sin(vUv.y * 30.0 + uTime * 2.0), cos(vUv.x * 25.0 + uTime * 1.7)) * uTexel * 4.0;
                    vec3 sc = texture(uScene, vUv + wob).rgb;
                    float fog = sky ? 1.0 : smoothstep(4.0, 60.0, dist);
                    vec3 c = sc * vec3(0.45, 0.85, 0.95);
                    vec2 q = W.xz * 1.3 + uTime * 0.25;
                    float ca = pow(abs(sin(q.x + sin(q.y * 1.3)) * sin(q.y + sin(q.x * 1.1))), 6.0);
                    c += vec3(0.6, 1.0, 1.0) * ca * 0.4 * (top + 0.3 * skyM) * (1.0 - fog);
                    c += vec3(0.4, 0.9, 1.0) * smoothstep(0.5, 0.8, fbm(vec2(vUv.x * 5.0 + vUv.y, uTime * 0.1))) * vUv.y * 0.12;
                    c = mix(c, vec3(0.02, 0.22, 0.32), fog * 0.85);
                    float bb = motes(dir, maxT, 18.0, pa, 1.6, 3.0, -1.4, 0.7, 0.03, 0.35, 23.0, vec2(0.0));
                    c += vec3(0.7, 0.95, 1.0) * bb * 0.6 * uPart;
                    outc = c * mix(0.7, 1.0, vig);
                } else if (uMode == 12 || uMode == 30) {
                    // ---------------- inferno / lava world: smoothly flowing molten rock (flowmap advected warped fbm)
                    bool lw = uMode == 30;
                    vec2 hz = vec2(sin(vUv.y * 70.0 + uTime * 4.0), cos(vUv.x * 50.0 + uTime * 3.3)) * uTexel * 1.2;
                    vec3 sc = texture(uScene, vUv + hz).rgb;
                    float far = smoothstep(40.0, 120.0, dist);
                    bool flat0 = an.y > 0.5;
                    vec2 lp = flat0 ? fq * 0.3 : vec2(fq.x * 0.6, fq.y * 0.22);
                    vec2 fdir = flat0 ? normalize(vec2(1.0, 0.45) + (vec2(vn(lp * 0.2), vn(lp * 0.2 + 9.1)) - 0.5) * 1.4) * 1.1 : vec2(0.0, -1.2);
                    float n = mix(flowN(lp, fdir, 0.12), 0.5, far);
                    float plate = mix(flowN(lp * 0.55 + 11.0, fdir * 0.4, 0.06), 0.5, far);
                    vec3 molten = lavaPal(n, 0.0);
                    vec3 basalt = satur(sc, 0.2) * vec3(0.4, 0.3, 0.27) * (lw ? 0.55 : 0.75);
                    float fwp = fwidth(plate) + 0.004;
                    float crust = flat0 ? smoothstep(0.5 - fwp, 0.53 + fwp, plate) : 1.0 - smoothstep(0.62 - fwidth(n), 0.7, n);
                    float glowB = flat0 ? (1.0 - smoothstep(0.0, 0.05 + fwp, abs(plate - 0.515))) : 0.0;
                    if (!lw) { crust = max(crust, flat0 ? smoothstep(0.42, 0.47, plate) : 1.0 - smoothstep(0.7, 0.78, n)); }
                    vec3 c = mix(molten, basalt + molten * 0.05, crust) * skyM;
                    c += vec3(1.0, 0.45, 0.06) * glowB * (lw ? 0.9 : 0.6) * (1.0 - far);
                    c += vec3(1.0, 0.35, 0.05) * max(edge, inkAt(vUv, P, dist, 1.5) * skyM) * 0.45 * uEdge * skyM;
                    c += vec3(0.9, 0.25, 0.05) * max(-N.y, 0.0) * 0.3 * skyM;
                    c += bloom * vec3(1.0, 0.5, 0.15);
                    // real lava blocks flow smoothly too
                    vec2 lq = W.xz * 0.45;
                    vec3 realL = lavaPal(flowN(lq, vec2(0.8, 0.4), 0.1) * 0.7 + 0.35, 1.0) * 1.1;
                    c = mix(c, realL, lavaPx);
                    c = mix(c, lw ? vec3(0.35, 0.06, 0.02) : vec3(0.3, 0.03, 0.0), (sky ? 0.0 : 1.0 - exp(-dist * 0.03)) * 0.7);
                    if (sky) {
                        float sm = fbm(dir.xz / max(dir.y + 0.3, 0.1) * 1.5 + uTime * 0.03);
                        c = mix(vec3(0.6, 0.12, 0.03), vec3(0.06, 0.02, 0.02), smoothstep(-0.1, 0.5, dir.y)) * (0.6 + 0.6 * sm);
                    }
                    float em = motes(dir, maxT, 24.0, pa, 1.8, 2.5, -1.1, 1.4, 0.025, 0.4, 11.0, vec2(0.2, 0.1));
                    c += vec3(1.0, 0.55, 0.15) * em * uPart * 2.0;
                    outc = c * mix(0.7, 1.0, vig);
                } else if (uMode == 13) { // toxic: oozing slime on the ground, acid haze, rising bubbles
                    float fog = sky ? 0.6 : smoothstep(6.0, 70.0, dist);
                    vec3 c = scene * vec3(0.55, 1.0, 0.4);
                    float far = smoothstep(30.0, 90.0, dist);
                    float n = mix(flowN(fq * 0.4, an.y > 0.5 ? vec2(0.6, 0.3) : vec2(0.0, -0.8), 0.08), 0.4, far);
                    float slime = smoothstep(0.52, 0.58, n) * skyM;
                    vec3 sl = mix(vec3(0.25, 0.75, 0.05), vec3(0.75, 1.0, 0.3), smoothstep(0.58, 0.8, n)) * (0.8 + 0.25 * sin(uTime * 2.0 + n * 10.0));
                    c = mix(c, sl, slime * (an.y > 0.5 ? 0.85 : 0.6));
                    c += vec3(0.4, 1.0, 0.1) * edge * (0.5 + 0.5 * sin(uTime * 4.0 + dist)) * uEdge;
                    c += vec3(0.5, 1.0, 0.2) * bloom * 0.6;
                    c = mix(c, vec3(0.25, 0.45, 0.05), fog * 0.6);
                    float bb = motes(dir, maxT, 20.0, pa, 1.8, 3.0, -0.8, 1.0, 0.04, 0.35, 13.0, vec2(0.0));
                    c += vec3(0.6, 1.0, 0.3) * bb * 0.8 * uPart;
                    outc = c * mix(0.75, 1.0, vig);
                } else if (uMode == 14) { // blueprint: white technical drawing on blue paper
                    vec3 c = vec3(0.06, 0.22, 0.55);
                    vec2 g = fract(gl_FragCoord.xy / 24.0);
                    c += vec3(0.07) * (step(g.x, 0.04) + step(g.y, 0.04));
                    c += vec3(0.04) * (step(fract(gl_FragCoord.x / 6.0), 0.15) + step(fract(gl_FragCoord.y / 6.0), 0.15)) * 0.5;
                    float nf = an.y > 0.5 ? 0.06 : an.x > 0.5 ? 0.03 : 0.0;
                    c += vec3(nf) * skyM;
                    float ink = inkAt(vUv, P, dist, 1.2) * skyM;
                    c += vec3(0.95) * clamp(max(ink, edge) * 1.3 * uEdge, 0.0, 1.0);
                    c += vec3(0.4) * gline * (1.0 - smoothstep(10.0, 50.0, dist));
                    c += vec3(0.25) * smoothstep(0.3, 0.6, sob(vUv, 1.0)) * (1.0 - smoothstep(15.0, 40.0, dist)) * skyM;
                    float hb = abs(fract((gl_FragCoord.x - gl_FragCoord.y) / 8.0) - 0.5);
                    c += vec3(0.12) * (1.0 - smoothstep(0.05, 0.12, hb)) * (1.0 - smoothstep(0.15, 0.35, lum)) * skyM;
                    outc = c;
                } else if (uMode == 15) { // sketch: wobbly graphite hatching on paper, sketchy double outlines
                    vec3 paper = vec3(0.96, 0.95, 0.92) - vn(gl_FragCoord.xy * 0.6) * 0.04;
                    vec2 g = gl_FragCoord.xy + (vec2(vn(gl_FragCoord.xy * 0.03), vn(gl_FragCoord.xy * 0.03 + 3.3)) - 0.5) * 8.0;
                    float l = luma(mix(scene, bl, 0.35)) * (sky ? 1.4 : 1.0);
                    float a1 = (g.x + g.y) * 0.7071, a2 = (g.x - g.y) * 0.7071;
                    float h1 = 1.0 - smoothstep(0.6, 1.6, abs(fract(a1 / 6.0) - 0.5) * 6.0);
                    float h2 = 1.0 - smoothstep(0.6, 1.6, abs(fract(a2 / 6.0) - 0.5) * 6.0);
                    float h3 = 1.0 - smoothstep(0.5, 1.4, abs(fract(g.x / 4.0) - 0.5) * 4.0);
                    float pr1 = 0.6 + 0.4 * vn(vec2(a2 * 0.04, floor(a1 / 6.0)));
                    float pr2 = 0.6 + 0.4 * vn(vec2(a1 * 0.04, floor(a2 / 6.0)));
                    float hatch = h1 * pr1 * (1.0 - smoothstep(0.45, 0.62, l)) + h2 * pr2 * (1.0 - smoothstep(0.28, 0.4, l)) + h3 * (1.0 - smoothstep(0.1, 0.2, l));
                    vec2 j1 = (vec2(vn(gl_FragCoord.xy * 0.05), vn(gl_FragCoord.xy * 0.05 + 7.0)) - 0.5) * uTexel * 3.0;
                    float ol = max(inkAt(vUv, P, dist, 1.3), inkAt(vUv, P, dist, 2.6) * 0.5) * skyM;
                    ol = max(ol, max(smoothstep(0.45, 0.8, sob(vUv + j1, 1.0)), smoothstep(0.45, 0.8, sob(vUv - j1 * 1.5, 1.0)) * 0.6) * 0.5);
                    vec3 graph = vec3(0.22, 0.22, 0.26);
                    vec3 c = mix(paper, graph, clamp(hatch * 0.55, 0.0, 0.7));
                    c = mix(c, graph * 0.7, clamp(ol * uEdge, 0.0, 0.9));
                    outc = c;
                } else if (uMode == 16) { // pastel: chalk strokes on tinted paper, soft coloured outlines
                    vec3 base = kuwa(vUv, 1.3, 3);
                    vec3 pc = satur(base, 0.8) * 0.6 + 0.38;
                    pc *= vec3(1.0, 0.93, 1.03) + 0.06 * sin(vec3(0.0, 2.0, 4.0) + W.x * 0.1);
                    float ang = 0.6 + (fbm(gl_FragCoord.xy * 0.004) - 0.5) * 2.0;
                    vec2 rp = mat2(cos(ang), -sin(ang), sin(ang), cos(ang)) * gl_FragCoord.xy;
                    float chalk = vn(rp * vec2(0.03, 0.55)) * 0.6 + vn(gl_FragCoord.xy * 0.7) * 0.4;
                    vec3 paper = vec3(0.97, 0.94, 0.95);
                    float cover = smoothstep(0.15, 0.55, chalk + (1.0 - luma(pc)) * 0.4 + 0.2);
                    vec3 c = mix(paper, pc, cover);
                    float ol = max(inkAt(vUv, P, dist, 1.8) * skyM, edge);
                    c = mix(c, satur(base, 1.6) * 0.75, clamp(ol * 0.55 * uEdge, 0.0, 1.0));
                    outc = c + bloom * 0.25;
                } else if (uMode == 17) { // hologram: translucent blue projection, world scan sweep, flicker, tearing
                    vec3 h = vec3(0.35, 0.85, 1.0);
                    float tear = step(0.96, h21(vec2(floor(gl_FragCoord.y / 6.0), floor(uTime * 12.0)))) * (h21(vec2(floor(uTime * 12.0), 2.0)) - 0.5) * 0.03;
                    vec3 sc = texture(uScene, vUv + vec2(tear, 0.0)).rgb;
                    float band = 0.6 + 0.4 * sin(gl_FragCoord.y * 0.8 - uTime * 10.0);
                    float flick = 0.9 + 0.1 * sin(uTime * 37.0);
                    float sweep = 1.0 - smoothstep(0.0, 0.6, abs(fract(W.y * 0.08 - uTime * 0.25) - 0.5) * 12.5);
                    float ink = max(edge, inkAt(vUv, P, dist, 1.3) * skyM);
                    vec3 c = h * (luma(sc) * 0.5 + ink * 1.2 * uEdge + gline * 0.3 * (1.0 - smoothstep(8.0, 40.0, dist)) + rim * 0.5) * band * flick;
                    c += vec3(0.7, 1.0, 1.0) * sweep * 0.5 * skyM;
                    c.r += luma(texture(uScene, vUv + vec2(0.004 + tear, 0.0)).rgb) * 0.12;
                    outc = sky ? vec3(0.0, 0.03, 0.06) : c;
                } else if (uMode == 18) { // retro 8-bit: chunky pixels, bayer dithered 64 colour palette, dark outlines
                    vec2 px = uTexel * 5.0;
                    vec2 cid = floor(vUv / px);
                    vec2 cuv = (cid + 0.5) * px;
                    vec3 c = satur(texture(uScene, cuv).rgb, 1.25);
                    float bq = bayer4(cid) - 0.5;
                    c = floor(c * 3.0 + 0.5 + bq * 0.9) / 3.0;
                    float cd = texture(uDepth, cuv).r;
                    float ol = cd >= 0.99999 ? 0.0 : inkAt(cuv, worldAt(cuv, cd), length(worldAt(cuv, cd)), 5.0);
                    c *= 1.0 - step(0.5, ol) * 0.7 * min(uEdge, 1.0);
                    outc = c;
                } else if (uMode == 19) { // gameboy: four greens, dithered, visible LCD pixel grid
                    vec2 px = uTexel * 3.0;
                    vec2 cid = floor(vUv / px);
                    float l = luma(texture(uScene, (cid + 0.5) * px).rgb) * 1.1;
                    l = clamp(l - edge * 0.35 * uEdge + (bayer4(cid) - 0.5) * 0.22, 0.0, 0.999);
                    vec3 c = l < 0.25 ? vec3(0.06, 0.22, 0.06) : l < 0.5 ? vec3(0.19, 0.38, 0.19) : l < 0.75 ? vec3(0.55, 0.67, 0.06) : vec3(0.61, 0.74, 0.06);
                    vec2 lc = fract(vUv / px);
                    c *= 0.85 + 0.15 * smoothstep(0.0, 0.2, min(lc.x, lc.y));
                    outc = c;
                } else if (uMode == 20) { // sakura: pink light, blossom carpet on the ground, 3D falling petals
                    vec3 c = scene * vec3(1.08, 0.9, 0.97) + vec3(0.1, 0.02, 0.05) + bloom * vec3(1.0, 0.8, 0.9) * 0.4;
                    vec3 vr = voro(W.xz * 4.0);
                    float pet = smoothstep(0.32, 0.22, vr.x) * step(0.55, vr.z) * top * (1.0 - smoothstep(10.0, 30.0, dist));
                    c = mix(c, mix(vec3(1.0, 0.72, 0.85), vec3(1.0, 0.88, 0.93), vr.z), pet * 0.8);
                    c += vec3(1.0, 0.8, 0.9) * max(edge, inkAt(vUv, P, dist, 1.3) * skyM) * 0.2 * uEdge;
                    float pf = motes(dir, maxT, 26.0, pa, 1.4, 2.0, 0.9, 2.2, 0.05, 0.5, 2.0, vec2(0.5, 0.2));
                    outc = mix(c, vec3(1.0, 0.68, 0.84), clamp(pf, 0.0, 1.0) * 0.9 * uPart);
                } else if (uMode == 21) { // infrared: glowing white foliage (wood effect), dark sky, halation, grain
                    vec3 c = vec3(clamp(scene.g * 1.6 + scene.r * 0.3 - scene.b * 0.4, 0.0, 1.0));
                    float hal = clamp(bl.g * 1.6 + bl.r * 0.3 - bl.b * 0.4, 0.0, 1.0);
                    c += vec3(1.0, 0.85, 0.9) * max(hal - 0.4, 0.0) * 0.8;
                    c = mix(c, vec3(1.0, 0.88, 0.95), 0.1);
                    c += grain * 0.07;
                    outc = sky ? vec3(0.06, 0.04, 0.09) + vec3(0.5, 0.3, 0.4) * pow(max(1.0 - dir.y, 0.0), 6.0) * 0.4 : c;
                } else if (uMode == 22) { // golden: polished gilded metal with environment reflections
                    vec3 gold = vec3(1.0, 0.76, 0.3);
                    vec3 R = reflect(V, N);
                    vec3 env = mix(vec3(0.25, 0.15, 0.05), vec3(1.0, 0.9, 0.65), smoothstep(-0.3, 0.4, R.y));
                    env += vec3(1.0, 0.95, 0.8) * exp(-abs(R.y - 0.1) * 18.0) * 0.6;
                    float spec = pow(max(dot(R, normalize(vec3(0.4, 0.8, 0.3))), 0.0), 60.0);
                    vec3 c = gold * (0.2 + lum * 0.85) * mix(vec3(1.0), env, 0.55) + vec3(1.0, 0.95, 0.8) * (spec * 0.8 + edge * 0.4 * uEdge + rim * 0.35) + bloom * gold * 0.6;
                    c *= 1.0 - inkAt(vUv, P, dist, 1.3) * skyM * 0.4;
                    outc = sky ? mix(vec3(1.0, 0.85, 0.5), vec3(0.6, 0.4, 0.15), clamp(dir.y, 0.0, 1.0)) : c;
                } else if (uMode == 23) { // void: pitch black world with glowing white outlines and drifting dust
                    vec3 c = vec3(0.01) + vec3(1.0) * max(edge, inkAt(vUv, P, dist, 1.2) * skyM) * 1.2 * uEdge;
                    c += vec3(0.25) * gline * (1.0 - smoothstep(6.0, 50.0, dist));
                    c += vec3(0.8) * motes(dir, maxT, 24.0, pa, 2.0, 3.0, 0.1, 1.5, 0.02, 0.4, 3.0, vec2(0.0)) * uPart;
                    outc = c;
                } else if (uMode == 24) { // aurora: dancing curtains overhead, their glow on the ground
                    vec3 c = scene * vec3(0.4, 0.5, 0.75);
                    float a1 = smoothstep(0.2, 0.0, abs(dir.y - 0.35 - 0.08 * sin(dir.x * 6.0 + uTime * 0.6)));
                    float a2 = smoothstep(0.15, 0.0, abs(dir.y - 0.5 - 0.06 * sin(dir.z * 5.0 - uTime * 0.5)));
                    float cur = 0.6 + 0.4 * vn(vec2(dir.x * 30.0 + uTime * 0.3, dir.z * 30.0));
                    if (sky) {
                        c = vec3(0.01, 0.02, 0.06) + (vec3(0.2, 1.0, 0.6) * a1 * 0.7 + vec3(0.6, 0.3, 1.0) * a2 * 0.5) * cur;
                    } else {
                        float gw = 0.5 + 0.5 * sin(W.x * 0.08 + W.z * 0.05 + uTime * 0.5);
                        c += mix(vec3(0.2, 0.9, 0.55), vec3(0.55, 0.3, 1.0), gw) * (top * 0.12 + rim * 0.1) + vec3(0.6, 0.9, 1.0) * edge * 0.2 * uEdge;
                    }
                    outc = c * mix(0.75, 1.0, vig);
                } else if (uMode == 25) { // anime: two-tone cel shading with cool shadows, ink, rim light, crisp clouds
                    vec3 Ld = normalize(vec3(0.45, 0.8, 0.35));
                    vec3 base = satur(kuwa(vUv, 1.0, 2), 1.35);
                    float lit = sky ? 1.0 : lum * (0.6 + 0.6 * clamp(dot(N, Ld) * 0.5 + 0.5, 0.0, 1.0));
                    float fwl = fwidth(lit) + 0.01;
                    float sh = smoothstep(0.24 - fwl, 0.24 + fwl, lit);
                    vec3 c = base * mix(0.68, 1.1, sh);
                    c = mix(c, c * vec3(0.78, 0.84, 1.15), (1.0 - sh) * 0.7);
                    c += vec3(1.0, 0.9, 0.75) * smoothstep(0.45, 0.6, rim) * 0.35 + bloom * 0.35;
                    c *= 1.0 - inkAt(vUv, P, dist, 1.4) * skyM * 0.75 * uEdge;
                    if (sky) {
                        float h = clamp(dir.y, 0.0, 1.0);
                        c = mix(vec3(0.62, 0.84, 1.0), vec3(0.14, 0.4, 0.92), smoothstep(0.0, 0.7, h));
                        vec2 cu = dir.xz / max(dir.y, 0.08) * 1.2 + uTime * 0.02;
                        float f = fbm(cu);
                        float cl = smoothstep(0.5, 0.53, f);
                        c = mix(c, mix(vec3(0.8, 0.85, 0.98), vec3(1.0), smoothstep(0.56, 0.68, f)), cl * smoothstep(0.0, 0.2, h));
                        c = mix(c * skyK, scene, 0.15);
                    }
                    outc = c;
                } else if (uMode == 26) { // oil painting: wide kuwahara, impasto strokes along the image flow, canvas weave
                    vec3 k = kuwa(vUv, 1.8, 5);
                    vec2 o = uTexel * 3.0;
                    float gx = luma(texture(uBlur, vUv + vec2(o.x, 0.0)).rgb) - luma(texture(uBlur, vUv - vec2(o.x, 0.0)).rgb);
                    float gy = luma(texture(uBlur, vUv + vec2(0.0, o.y)).rgb) - luma(texture(uBlur, vUv - vec2(0.0, o.y)).rgb);
                    float a = length(vec2(gx, gy)) > 0.01 ? atan(gy, gx) + 1.5708 : fbm(gl_FragCoord.xy * 0.004) * 6.2831;
                    a = mix(a, fbm(gl_FragCoord.xy * 0.004) * 6.2831, 0.55);
                    vec2 bp = mat2(cos(a), -sin(a), sin(a), cos(a)) * gl_FragCoord.xy;
                    float br = vn(bp * vec2(0.02, 0.2)) * 0.75 + vn(bp * vec2(0.05, 0.42)) * 0.25;
                    float imp = dFdx(br) - dFdy(br);
                    vec3 c = satur(k, 1.3) * (0.9 + 0.2 * br) + clamp(imp, -0.08, 0.08) * 0.8;
                    c *= 1.0 - max(edge, inkAt(vUv, P, dist, 1.5) * skyM) * 0.3 * uEdge;
                    float weave = (sin(gl_FragCoord.x * 1.9) * sin(gl_FragCoord.y * 1.9)) * 0.025;
                    c += weave;
                    outc = c * mix(0.85, 1.0, vig);
                } else if (uMode == 27) { // watercolor: flat washes, pigment pooling at wash edges, granulation, paper
                    vec2 wob = (vec2(fbm(gl_FragCoord.xy * 0.02), fbm(gl_FragCoord.xy * 0.02 + 5.2)) - 0.5) * uTexel * 14.0;
                    vec3 k = kuwa(vUv + wob, 1.5, 3);
                    vec3 bc = texture(uBlur, vUv + wob).rgb;
                    vec3 c = satur(mix(k, bc, 0.3), 1.2);
                    float pool = clamp(length(k - bc) * 2.8, 0.0, 1.0);
                    c *= 1.0 - pool * 0.3 * uEdge;
                    float gran = fbm(gl_FragCoord.xy * 0.18);
                    c *= mix(1.0, 0.75 + 0.5 * gran, 0.5 * (1.0 - luma(c)));
                    c = 1.0 - (1.0 - c) * 0.82;
                    c *= 0.93 + 0.12 * fbm(gl_FragCoord.xy * 0.05);
                    c *= 1.0 - inkAt(vUv, P, dist, 1.3) * skyM * 0.35 * uEdge;
                    vec3 paper = vec3(0.97, 0.95, 0.9) * (0.96 + 0.04 * vn(gl_FragCoord.xy * 0.9));
                    c *= paper;
                    if (sky) c = mix(c, paper, 0.35);
                    outc = mix(c, paper, smoothstep(0.55, 1.0, length(vUv - 0.5) * 1.3 + (fbm(gl_FragCoord.xy * 0.01) - 0.5) * 0.3));
                } else if (uMode == 28) { // neon noir: rainy night, only neon keeps colour, wet streets, 3D rain
                    float neon = clamp(smoothstep(0.35, 0.7, lum) * smoothstep(0.15, 0.4, mx0 - mn0) + smoothstep(0.75, 0.95, lum), 0.0, 1.0);
                    vec3 c = mix(vec3(lum) * vec3(0.5, 0.58, 0.78) * 0.6, satur(scene, 1.6) * 1.1, neon);
                    c += bloom * satur(bl, 1.5) * 0.9;
                    c += mix(c1, c2, 0.5 + 0.5 * N.y) * max(edge, inkAt(vUv, P, dist, 1.3) * skyM) * 0.3 * uEdge;
                    c += mix(vec3(0.55, 0.62, 0.8), satur(bl, 1.4) * 1.5, 0.5) * rip.x * 0.16;
                    float mist = (sky ? 0.0 : 1.0 - exp(-dist * 0.03)) * (0.75 + 0.25 * fbm(W.xz * 0.05 + vec2(uTime * 0.06, uTime * 0.02)));
                    c = mix(c, vec3(0.03, 0.04, 0.08) + c2 * 0.03, mist * 0.75);
                    if (sky) c = mix(c2 * 0.12 + vec3(0.04, 0.04, 0.08), vec3(0.01, 0.01, 0.03), smoothstep(-0.05, 0.5, dir.y));
                    c += (vec3(0.1, 0.12, 0.18) + bl * 0.5) * rainSheets(dir) * (sky ? 1.0 : smoothstep(10.0, 60.0, dist)) * 0.4 * min(uPart, 1.5);
                    float rn = rainFx(dir, maxT, pa, 1.0, 3.0);
                    c = rainMix(c, rn * uPart * rainVis(dist, sky),vec3(0.3, 0.36, 0.5) + satur(bl, 1.3) * 0.8, 1.6);
                    outc = (c + grain * 0.03) * mix(0.6, 1.0, vig);
                } else if (uMode == 29) { // ice age: snow drifts, frosted walls, sparkling tops, light snowfall
                    vec3 c = mix(vec3(lum), scene, 0.4) * vec3(0.78, 0.92, 1.1) + vec3(0.05, 0.07, 0.1);
                    float sn = top * smoothstep(0.35, 0.6, vn(W.xz * 3.0) * 0.5 + N.y * 0.6);
                    c = mix(c, vec3(0.92, 0.96, 1.0) * (0.72 + 0.35 * lum), sn * 0.9);
                    float fr = (1.0 - top) * skyM * smoothstep(0.55, 0.75, fbm(fq * 2.5) + (1.0 - fract(fq.y)) * 0.25) * (1.0 - smoothstep(15.0, 45.0, dist));
                    c = mix(c, vec3(0.85, 0.93, 1.0), fr * 0.55);
                    vec2 gq = W.xz * 14.0;
                    float gh = h21(floor(gq));
                    float tw = step(0.985, gh) * smoothstep(0.35, 0.0, length(fract(gq) - 0.5)) * (0.5 + 0.5 * sin(uTime * 4.0 + gh * 90.0));
                    c += vec3(0.85, 0.95, 1.0) * tw * top * uGlint * (1.0 - smoothstep(4.0, 22.0, dist));
                    c += vec3(0.55, 0.9, 1.0) * (max(edge, inkAt(vUv, P, dist, 1.3) * skyM) * 0.5 * uEdge + rim * 0.15) + bloom * vec3(0.7, 0.9, 1.0) * 0.5;
                    c = mix(c, vec3(0.78, 0.88, 0.96), (sky ? 0.0 : 1.0 - exp(-dist * 0.02)) * 0.75);
                    if (sky) c = mix(vec3(0.86, 0.93, 1.0), vec3(0.45, 0.65, 0.86), smoothstep(0.0, 0.6, dir.y)) * skyK;
                    float fl = motes(dir, maxT, 22.0, pa, 1.6, 2.2, 0.8, 1.2, 0.03, 0.5, 29.0, vec2(0.3, 0.1));
                    c = mix(c, vec3(1.0), clamp(fl, 0.0, 1.0) * 0.8 * uPart);
                    outc = c * mix(0.85, 1.0, vig);
                } else if (uMode == 31) { // moonlight: silver key light, drifting ground mist, glowing moon
                    vec3 Lm = normalize(vec3(0.4, 0.45, -0.8));
                    float key = smoothstep(-0.1, 0.5, dot(N, Lm));
                    vec3 c = satur(scene, 0.3) * vec3(0.45, 0.55, 0.85) * mix(0.45, 1.05, key);
                    c += vec3(0.75, 0.85, 1.0) * key * skyM * 0.05;
                    c += max(scene - 0.75, 0.0) * vec3(1.0, 0.7, 0.4) * 1.5 + bloom * vec3(1.0, 0.8, 0.6) * 0.5;
                    c += vec3(0.75, 0.82, 1.0) * (rim * 0.15 + edge * 0.12 * uEdge);
                    c = mix(c, vec3(0.05, 0.07, 0.14), (sky ? 0.0 : 1.0 - exp(-dist * 0.02)) * 0.8);
                    float mist = fbm(W.xz * 0.12 + vec2(uTime * 0.05, uTime * 0.02)) * (1.0 - smoothstep(0.0, 6.0, abs(W.y - (uCamCell.y - 1.5))));
                    c = mix(c, vec3(0.45, 0.5, 0.65), smoothstep(0.4, 0.75, mist) * 0.4 * skyM);
                    if (sky) {
                        c = mix(vec3(0.1, 0.13, 0.24), vec3(0.01, 0.02, 0.06), smoothstep(-0.05, 0.7, dir.y));
                        float md = dot(dir, Lm);
                        float disc = smoothstep(0.9988, 0.9992, md);
                        c = mix(c, vec3(0.92, 0.94, 1.0) * (0.85 + 0.15 * vn(dir.xy * 900.0)), disc);
                        c += vec3(0.5, 0.6, 0.9) * (pow(max(md, 0.0), 300.0) * 0.5 + pow(max(md, 0.0), 20.0) * 0.12);
                        vec3 sg = dir * 260.0;
                        float st = hash(floor(sg));
                        c += vec3(0.8) * step(0.997, st) * smoothstep(0.5, 0.1, length(fract(sg) - 0.5)) * smoothstep(0.0, 0.2, dir.y);
                    }
                    outc = c * mix(0.7, 1.0, vig);
                } else if (uMode == 32) { // storybook: painted flat shapes, soft brown pen lines, paper, puffy clouds
                    vec3 k = kuwa(vUv, 1.4, 3);
                    vec3 c = satur(k, 1.2) * 0.86 + vec3(0.09, 0.07, 0.035);
                    c *= vec3(1.06, 1.0, 0.9);
                    c += bl * 0.1 + bloom * vec3(1.0, 0.85, 0.6) * 0.6;
                    c = mix(c, vec3(0.3, 0.2, 0.12), clamp(inkAt(vUv, P, dist, 1.6) * skyM * 0.6 * uEdge, 0.0, 1.0));
                    c += vec3(1.0, 0.9, 0.7) * rim * 0.12;
                    c = mix(c, vec3(0.86, 0.9, 0.92), (sky ? 0.0 : 1.0 - exp(-dist * 0.012)) * 0.55);
                    if (sky) {
                        vec3 s = mix(vec3(0.98, 0.93, 0.82), vec3(0.38, 0.62, 0.9), smoothstep(-0.02, 0.6, dir.y));
                        vec2 cu = dir.xz / max(dir.y, 0.06) * 0.9 + vec2(uTime * 0.01, 0.0);
                        float f = fbm(cu);
                        vec3 cc = mix(vec3(1.0, 0.98, 0.95), vec3(0.78, 0.8, 0.9), smoothstep(0.5, 0.8, fbm(cu + 0.15)));
                        s = mix(s, cc, smoothstep(0.45, 0.75, f) * smoothstep(0.0, 0.15, dir.y));
                        c = mix(s * skyK, scene, 0.15);
                    }
                    c *= 0.95 + 0.05 * vn(gl_FragCoord.xy * 0.7) + (fbm(gl_FragCoord.xy * 0.03) - 0.5) * 0.06;
                    outc = c * mix(0.85, 1.0, vig);
                } else if (uMode == 33) { // comic: flat posterised colour, CMYK halftone shading, thick ink
                    vec3 k = kuwa(vUv, 1.3, 3);
                    float kl = luma(k);
                    float bq = mix(0.35, 1.0, smoothstep(0.2, 0.26, kl)) * mix(0.88, 1.12, smoothstep(0.56, 0.62, kl));
                    vec3 c = clamp(satur(k, 1.7) / max(kl, 0.05) * 0.55, 0.0, 1.2) * bq;
                    vec2 hp = mat2(0.7071, -0.7071, 0.7071, 0.7071) * gl_FragCoord.xy / 7.0;
                    float rad = sqrt(clamp(1.0 - kl * 1.6, 0.0, 1.0)) * 0.6;
                    float hd = length(fract(hp) - 0.5);
                    float fwh = fwidth(hd);
                    float dotm = 1.0 - smoothstep(rad - fwh, rad + fwh, hd);
                    c *= 1.0 - dotm * 0.5;
                    float ink = max(inkAt(vUv, P, dist, 2.5) * skyM, smoothstep(0.4, 0.7, sob(vUv, 1.5)));
                    c *= 1.0 - clamp(ink * uEdge, 0.0, 1.0) * 0.95;
                    if (sky) {
                        c = mix(vec3(0.55, 0.82, 1.0), vec3(0.25, 0.55, 0.95), clamp(dir.y, 0.0, 1.0)) * skyK;
                        c *= 1.0 - (1.0 - smoothstep(0.15, 0.25, hd)) * 0.12;
                    }
                    outc = c;
                } else if (uMode == 34) { // glitch: row tearing, datamosh blocks, rgb split
                    float tk = floor(uTime * 10.0);
                    float burst = step(0.7, h21(vec2(floor(uTime * 2.0), 9.1)));
                    vec2 bid = floor(vUv * vec2(6.0, 28.0));
                    float shift = (h21(bid.yy + tk) > 0.82 ? (h21(vec2(bid.y, tk + 3.0)) - 0.5) * 0.12 : 0.0) * (0.3 + burst);
                    vec2 uv2 = vUv + vec2(shift, 0.0);
                    vec2 px = uTexel * 16.0;
                    uv2 = mix(uv2, (floor(uv2 / px) + 0.5) * px, step(0.93 - burst * 0.1, h21(bid + tk * 0.37)));
                    float ca = 0.003 + burst * 0.008 + abs(shift) * 0.2;
                    vec3 c = vec3(texture(uScene, uv2 + vec2(ca, 0.0)).r, texture(uScene, uv2).g, texture(uScene, uv2 - vec2(ca, 0.0)).b);
                    c = mix(c, 1.0 - c, step(0.985, h21(bid * 1.7 + tk)) * burst);
                    c += (h21(vec2(gl_FragCoord.y, tk)) - 0.5) * 0.08 * step(0.9, h21(vec2(floor(gl_FragCoord.y / 3.0), tk)));
                    c *= 0.92 + 0.08 * sin(gl_FragCoord.y * 1.5);
                    c += vec3(0.2, 1.0, 0.9) * edge * 0.3 * uEdge;
                    outc = c;
                } else if (uMode == 35) { // crystal: world-locked faceted gem surfaces, refraction, prismatic seams, sparkles
                    vec3 vr = voro(fq * 1.4);
                    float bord = vr.y - vr.x;
                    float fwb = fwidth(bord) + 0.002;
                    float nr = (1.0 - smoothstep(14.0, 40.0, dist)) * skyM;
                    float seam = (1.0 - smoothstep(0.0, 0.035 + fwb, bord)) * nr;
                    vec2 refr = (hh2(vec2(vr.z * 91.0, 3.0)) - 0.5) * 0.03 * nr;
                    vec3 c = vec3(texture(uScene, vUv + refr * 1.15).r, texture(uScene, vUv + refr).g, texture(uScene, vUv + refr * 0.85).b);
                    c *= mix(1.0, 0.75 + 0.5 * vr.z, nr);
                    vec3 ir = mix(vec3(0.65, 0.85, 1.0), chroma(vr.z + facing * 0.8 + uTime * 0.04), 0.45);
                    c = c * 0.85 + (ir * (0.08 + rim * 0.4) + vec3(0.04, 0.06, 0.1)) * skyM;
                    c += mix(vec3(1.0), chroma(vr.z * 3.0 + uTime * 0.1), 0.5) * seam * 0.55;
                    c += chroma(dot(W, vec3(0.1)) + uTime * 0.1) * edge * 0.8 * uEdge;
                    vec2 q2 = fq * 10.0;
                    vec2 f2 = fract(q2) - 0.5;
                    float hs = h21(floor(q2));
                    float sp = step(0.985, hs) * (0.5 + 0.5 * sin(uTime * 5.0 + hs * 70.0))
                             * (smoothstep(0.08, 0.0, abs(f2.x)) * smoothstep(0.4, 0.0, abs(f2.y)) + smoothstep(0.08, 0.0, abs(f2.y)) * smoothstep(0.4, 0.0, abs(f2.x)));
                    c += vec3(1.0) * sp * uGlint * skyM * (1.0 - smoothstep(4.0, 20.0, dist));
                    c += bloom * (0.5 + 0.4 * chroma(vUv.x + uTime * 0.05));
                    if (sky) c = c * vec3(0.92, 0.96, 1.08) + ir * 0.06;
                    outc = c;
                } else if (uMode == 36) { // deep ocean: light absorption by depth, god rays, 3D marine snow
                    vec2 wob = vec2(sin(vUv.y * 24.0 + uTime * 1.5), cos(vUv.x * 20.0 + uTime * 1.2)) * uTexel * 2.5;
                    vec3 sc = texture(uScene, vUv + wob).rgb;
                    float dd = sky ? 200.0 : dist;
                    vec3 absorb = exp(-dd * vec3(0.11, 0.035, 0.022));
                    vec3 c = sc * absorb * 1.2 + vec3(0.0, 0.12, 0.2) * (1.0 - exp(-dd * 0.04));
                    vec2 q = W.xz * 1.1 + uTime * 0.2;
                    float ca = pow(abs(sin(q.x + sin(q.y * 1.3)) * sin(q.y + sin(q.x * 1.1))), 6.0);
                    c += vec3(0.4, 0.85, 0.9) * ca * 0.3 * top * absorb.g;
                    float ry = fbm(vec2(vUv.x * 5.0 + vUv.y * 1.2 + uTime * 0.05, uTime * 0.1));
                    c += vec3(0.3, 0.7, 0.8) * smoothstep(0.45, 0.8, ry) * smoothstep(0.1, 1.0, vUv.y) * 0.25;
                    if (sky) c = mix(vec3(0.0, 0.08, 0.15), vec3(0.2, 0.6, 0.7) * (0.8 + 0.4 * fbm(dir.xz / max(dir.y, 0.1) * 3.0 + uTime * 0.2)), smoothstep(0.0, 0.8, dir.y));
                    float ms = motes(dir, maxT, 16.0, pa, 1.0, 1.6, 0.12, 1.2, 0.015, 0.6, 5.0, vec2(0.1, 0.05));
                    c += vec3(0.6, 0.8, 0.85) * ms * uPart * 0.7;
                    outc = c * mix(0.55, 1.0, vig);
                } else if (uMode == 37) { // snowfall: snow piling on tops, cold haze, real 3D flakes
                    vec3 c = scene * vec3(0.93, 0.97, 1.05) + 0.02;
                    float pile = top * smoothstep(0.3, 0.55, vn(W.xz * 2.0) * 0.4 + N.y * 0.55 + fract(W.y) * 0.1);
                    c = mix(c, vec3(0.95, 0.97, 1.0) * (0.78 + 0.28 * lum), pile * 0.85);
                    float lip = (1.0 - top) * skyM * smoothstep(0.82, 0.97, fract(W.y)) * (1.0 - smoothstep(10.0, 40.0, dist)) * smoothstep(0.4, 0.55, vn(fq * vec2(4.0, 1.0)));
                    c = mix(c, vec3(0.95, 0.97, 1.0) * 0.85, lip * 0.75);
                    c = mix(c, vec3(0.82, 0.86, 0.9), (sky ? 0.0 : 1.0 - exp(-dist * 0.02)) * 0.6);
                    c += bloom * 0.3;
                    float fl = motes(dir, maxT, 30.0, pa, 1.0, 1.5, 1.4, 1.2, 0.035, 0.85, 1.0, vec2(0.35, 0.12));
                    outc = mix(c, vec3(1.0), clamp(fl, 0.0, 1.0) * 0.92 * uPart) * mix(0.85, 1.0, vig);
                } else if (uMode == 38) { // rainy: soaked grey world, puddles with ripples and splashes, layered 3D rain, mist
                    vec3 c = satur(scene, 0.72) * vec3(0.8, 0.84, 0.92) * 0.9;
                    c += vec3(0.78, 0.82, 0.9) * rip.x * 0.16;
                    // atmospheric perspective: contrast and colour wash out into a cool grey-blue haze with distance
                    float mist = (sky ? 0.0 : 1.0 - exp(-dist * 0.03)) * (0.75 + 0.25 * fbm(W.xz * 0.05 + vec2(uTime * 0.07, uTime * 0.03)));
                    vec3 fogc = vec3(0.42, 0.46, 0.52) * mix(1.0, skyK, 0.6);
                    c = mix(c, mix(vec3(luma(c)), c, 0.6), mist);
                    c = mix(c, fogc, mist * 0.85);
                    if (sky) {
                        float cl = fbm(dir.xz / max(dir.y + 0.1, 0.08) * 1.3 + vec2(uTime * 0.025, 0.0));
                        c = mix(vec3(0.46, 0.49, 0.54), vec3(0.26, 0.28, 0.32), clamp(dir.y * 1.5, 0.0, 1.0)) * (0.85 + 0.3 * cl) * skyK;
                    }
                    // rain fades smoothly into the mist with distance (no cutoff line): fine streaks far away, curtain on the horizon
                    float rainRange = rainVis(dist, sky);
                    float cur = rainSheets(dir) * (sky ? 1.0 : smoothstep(10.0, 60.0, dist)) * min(uPart, 1.5) * rainRange;
                    c = mix(c, fogc * 1.18, cur * 0.35);
                    // bright rain highlight on the mist so streaks read against both dark and bright backgrounds
                    float rn = rainFx(dir, maxT, pa, 1.0, 0.0);
                    c += fogc * 0.12 * clamp(rn, 0.0, 1.0) * rainRange;
                    c = rainMix(c, rn * uPart * rainRange, vec3(0.75, 0.8, 0.88) * mix(1.0, skyK, 0.5), 1.6);
                    c = mix(vec3(luma(c)), c, 0.8) * vec3(0.96, 0.99, 1.04);
                    outc = c * mix(0.78, 1.0, vig);
                } else if (uMode == 39) { // silent hill: dense drifting fog, 3D falling ash, grain
                    float fog = sky ? 1.0 : 1.0 - exp(-pow(dist * 0.075, 1.4));
                    vec3 fogc = vec3(0.63, 0.63, 0.61) + (fbm(su * 2.0 + vec2(uTime * 0.03, 0.0)) - 0.5) * 0.08;
                    vec3 c = mix(satur(scene, 0.3) * vec3(0.9, 0.88, 0.85) * 0.9, fogc, fog);
                    c *= 1.0 - inkAt(vUv, P, dist, 1.2) * skyM * 0.3 * (1.0 - fog);
                    float ash = motes(dir, maxT, 20.0, pa, 1.1, 1.8, 0.5, 1.6, 0.03, 0.7, 4.0, vec2(0.15, 0.05));
                    c = mix(c, vec3(0.8, 0.79, 0.77), clamp(ash, 0.0, 1.0) * 0.75 * uPart);
                    outc = (c + grain * 0.06) * mix(0.7, 1.0, vig);
                } else if (uMode == 40) { // western: sepia film, harsh sun, dust in the air, scratches, flicker
                    vec3 Ld = normalize(vec3(0.3, 0.7, 0.5));
                    float key = sky ? 1.0 : mix(0.6, 1.2, smoothstep(-0.1, 0.4, dot(N, Ld)));
                    float l = smoothstep(0.05, 0.95, lum * key);
                    l = l * l * (3.0 - 2.0 * l);
                    vec3 c = mix(vec3(1.0, 0.82, 0.6) * l * 1.1 + vec3(0.08, 0.04, 0.0), scene * vec3(1.1, 0.9, 0.7), 0.2);
                    c *= 1.0 - inkAt(vUv, P, dist, 1.3) * skyM * 0.35 * uEdge;
                    c = mix(c, vec3(0.92, 0.75, 0.5), (sky ? 0.0 : 1.0 - exp(-dist * 0.015)) * 0.5);
                    if (sky) c = mix(vec3(1.0, 0.88, 0.65), vec3(0.85, 0.7, 0.5), clamp(dir.y, 0.0, 1.0)) * skyK;
                    float dust = motes(dir, maxT, 18.0, pa, 1.4, 2.0, 0.05, 2.0, 0.02, 0.5, 9.0, vec2(0.8, 0.1));
                    c += vec3(1.0, 0.9, 0.7) * dust * 0.4 * uPart;
                    float ft = floor(uTime * 12.0);
                    float scr = (1.0 - smoothstep(0.0, 1.5, abs(gl_FragCoord.x - h21(vec2(ft, 1.0)) / uTexel.x))) * step(0.6, h21(vec2(ft, 2.0)));
                    c = mix(c, vec3(0.95, 0.9, 0.8), scr * 0.5);
                    c *= 1.0 - 0.6 * step(0.9985, hash(vec3(floor(gl_FragCoord.xy / 2.0), ft)));
                    c *= 0.96 + 0.04 * h21(vec2(floor(uTime * 18.0), 5.0));
                    outc = (c + grain * 0.09) * mix(0.45, 1.0, vig);
                } else if (uMode == 41) { // cyberpunk: teal/magenta split tone, neon ink, wet streets, 3D rain
                    vec3 c = satur(scene, 1.35);
                    c = mix(c * vec3(0.55, 0.75, 0.85), c * vec3(1.15, 0.95, 1.05), smoothstep(0.2, 0.7, lum));
                    c = mix(c, c * 0.6 + vec3(0.0, 0.12, 0.15), (1.0 - lum) * 0.35);
                    c += c2 * bloom + bloom * 0.5;
                    c += mix(c1, c2, vUv.y) * max(edge, inkAt(vUv, P, dist, 1.3) * skyM) * 0.75 * uEdge * (1.0 - smoothstep(30.0, 120.0, dist));
                    c += mix(mix(c1, c2, 0.5), satur(bl, 1.4) * 1.5, 0.5) * rip.x * 0.16;
                    float mist = (sky ? 0.0 : 1.0 - exp(-dist * 0.02)) * (0.75 + 0.25 * fbm(W.xz * 0.05 + vec2(uTime * 0.06, 0.0)));
                    c = mix(c, c2 * 0.35 + vec3(0.05, 0.0, 0.1), mist * 0.65);
                    if (sky) c = mix(c2 * 0.45 + c1 * 0.1, vec3(0.03, 0.0, 0.07), smoothstep(-0.05, 0.5, dir.y));
                    c += (mix(c1, c2, 0.5) * 0.2 + bl * 0.4) * rainSheets(dir) * (sky ? 1.0 : smoothstep(10.0, 60.0, dist)) * 0.4 * min(uPart, 1.5);
                    float rn = rainFx(dir, maxT, pa, 1.0, 2.0);
                    c = rainMix(c, rn * uPart * rainVis(dist, sky),mix(c1, c2, 0.5) * 0.3 + satur(bl, 1.3) * 0.7, 1.6);
                    c *= 1.0 - uScan * 0.1 * (0.5 + 0.5 * sin(gl_FragCoord.y * 1.6));
                    outc = c * mix(0.65, 1.0, vig);
                } else if (uMode == 42) { // chrome: liquid metal, environment reflections, sharp speculars
                    vec3 R = reflect(V, N);
                    vec3 env = mix(vec3(0.12, 0.12, 0.14), vec3(0.55, 0.6, 0.68), smoothstep(-0.4, 0.0, R.y));
                    env = mix(env, vec3(0.95, 0.97, 1.0), smoothstep(0.0, 0.5, R.y));
                    env += vec3(1.0) * exp(-abs(R.y) * 25.0) * 0.5;
                    vec3 warp = texture(uBlur, vUv + N.xz * 0.04).rgb;
                    vec3 c = mix(env, env * 0.4 + warp * 0.9, 0.35) * (0.55 + 0.6 * lum);
                    c = mix(vec3(luma(c)), c, 0.4);
                    float spec = pow(max(dot(R, normalize(vec3(0.4, 0.85, 0.3))), 0.0), 80.0);
                    c += vec3(1.0) * spec * 1.2 + vec3(0.9, 0.95, 1.0) * rim * 0.35 + edge * 0.6 * uEdge;
                    outc = sky ? satur(scene, 0.2) * 1.05 : c;
                } else if (uMode == 43) { // prism: world-locked rainbow light, spectral dispersion, white edges
                    float hue = fract(dot(W, vec3(0.03, 0.02, 0.04)) + uTime * 0.05 + dist * 0.01);
                    vec2 dsp = (vUv - 0.5) * 0.012;
                    vec3 sc = vec3(texture(uScene, vUv + dsp).r, scene.g, texture(uScene, vUv - dsp).b);
                    vec3 c = mix(sc, chroma(hue) * (0.35 + lum * 0.9), 0.55) + chroma(hue + 0.5) * max(edge, inkAt(vUv, P, dist, 1.3) * skyM) * 0.7 * uEdge + bloom * 0.5;
                    float band = smoothstep(0.4, 0.5, abs(fract(dot(W, vec3(0.25, 0.1, 0.18)) - uTime * 0.1) - 0.5)) * skyM * (1.0 - smoothstep(10.0, 40.0, dist));
                    c += chroma(hue + 0.25) * band * 0.15;
                    if (sky) c = mix(scene, chroma(dir.x * 0.5 + dir.z * 0.3 + uTime * 0.03) * 0.5 + 0.45, 0.5) * skyK;
                    outc = c;
                } else if (uMode == 44) { // shadow realm: violet darkness, black fog, glowing outlines, rising spirits
                    float fog = sky ? 1.0 : 1.0 - exp(-dist * 0.045);
                    vec3 fogc = vec3(0.05, 0.0, 0.09) + vec3(0.12, 0.0, 0.18) * fbm(su * 1.5 + uTime * 0.05);
                    vec3 c = satur(scene, 0.2) * vec3(0.5, 0.38, 0.7) * 0.65;
                    c += vec3(0.65, 0.25, 1.0) * max(edge, inkAt(vUv, P, dist, 1.4) * skyM) * 0.7 * uEdge * (0.75 + 0.25 * sin(uTime * 2.0 + dist * 0.3)) * (1.0 - fog);
                    c += vec3(0.5, 0.1, 0.8) * rim * 0.2 * (1.0 - fog);
                    c = mix(c, fogc, fog * 0.9);
                    if (sky) {
                        float mm = dot(dir, normalize(vec3(-0.3, 0.4, 0.85)));
                        c += vec3(0.9, 0.1, 0.15) * (smoothstep(0.9985, 0.999, mm) + pow(max(mm, 0.0), 60.0) * 0.3);
                    }
                    c += vec3(0.6, 0.3, 1.0) * motes(dir, maxT, 22.0, pa, 1.8, 3.0, -0.5, 1.5, 0.03, 0.45, 7.0, vec2(0.0)) * 1.2 * uPart;
                    outc = c * mix(0.35, 1.0, vig);
                } else if (uMode == 45) { // ghibli summer: painted shapes, lush greens, lilac shadows, towering cumulus, drifting seeds
                    vec3 Ld = normalize(vec3(0.5, 0.75, 0.3));
                    vec3 k = kuwa(vUv, 1.2, 3);
                    float kl = luma(k);
                    float ndl = sky ? 1.0 : clamp(dot(N, Ld) * 0.5 + 0.5, 0.0, 1.0);
                    float lit = kl * (0.5 + 0.75 * ndl);
                    float fwl = fwidth(lit) + 0.02;
                    float sh = smoothstep(0.26 - fwl, 0.26 + fwl, lit);
                    float green = smoothstep(0.01, 0.1, k.g - max(k.r, k.b));
                    vec3 base = satur(k, 1.3);
                    base = mix(base, base * vec3(0.8, 1.12, 0.62) + vec3(0.02, 0.05, 0.0), green * 0.65);
                    vec3 c = base * mix(vec3(0.6, 0.6, 0.86), vec3(1.14, 1.07, 0.94), sh);
                    c += vec3(1.0, 0.95, 0.8) * smoothstep(0.5, 0.7, rim) * 0.2 * sh;
                    c = mix(c, vec3(0.18, 0.24, 0.16), clamp(inkAt(vUv, P, dist, 1.3) * skyM * 0.45 * uEdge, 0.0, 1.0));
                    c += bloom * vec3(1.0, 0.95, 0.8) * 0.5;
                    float haze = sky ? 0.0 : 1.0 - exp(-dist * 0.011);
                    c = mix(c, vec3(0.7, 0.84, 0.98) * mix(1.0, skyK, 0.5), haze * 0.6);
                    if (sky) {
                        vec3 s = mix(vec3(0.8, 0.91, 1.0), vec3(0.18, 0.46, 0.92), smoothstep(-0.02, 0.55, dir.y));
                        c = cumulus(dir, s, Ld, vec3(1.0, 0.99, 0.96), vec3(0.62, 0.68, 0.86), 0.0) * mix(1.0, skyK, 0.6);
                        c += vec3(1.0, 0.9, 0.7) * pow(max(dot(dir, Ld), 0.0), 40.0) * 0.4;
            """;
    private static final String FRAG2 = """
                    }
                    float seeds = motes(dir, maxT, 24.0, pa, 2.0, 3.0, 0.12, 2.5, 0.022, 0.35, 45.0, vec2(0.3, 0.1) * uWind);
                    c += vec3(1.0, 0.98, 0.9) * seeds * 0.7 * uPart;
                    c *= 0.97 + 0.03 * vn(gl_FragCoord.xy * 0.6);
                    outc = c * mix(0.88, 1.0, vig);
                } else if (uMode == 46) { // cinematic: teal & orange grade, filmic tonemap, anamorphic bloom, lens fringe, grain
                    vec2 cc0 = vUv - 0.5;
                    vec2 ca = cc0 * length(cc0) * 0.012;
                    vec3 sc = vec3(texture(uScene, vUv + ca).r, scene.g, texture(uScene, vUv - ca).b);
                    vec3 strk = vec3(0.0);
                    for (int i = -6; i <= 6; i++) strk += max(texture(uBlur, vUv + vec2(float(i) * 0.02, 0.0)).rgb - 0.6, 0.0) * (1.0 - abs(float(i)) / 7.0);
                    vec3 x = sc * 0.95 + max(bl - 0.5, 0.0) * 0.7 + vec3(0.45, 0.65, 1.0) * luma(strk) * 0.18;
                    x = mix(x, vec3(0.45, 0.55, 0.62) * skyK, (sky ? 0.0 : 1.0 - exp(-dist * 0.008)) * 0.2);
                    vec3 c = aces(x * 1.15);
                    float l = luma(c);
                    c = mix(c, c * vec3(0.7, 0.98, 1.08) + vec3(0.0, 0.025, 0.045), (1.0 - smoothstep(0.05, 0.55, l)) * 0.85);
                    c = mix(c, c * vec3(1.1, 0.98, 0.84), smoothstep(0.45, 1.0, l) * 0.6);
                    c = mix(c, c * vec3(1.15, 0.92, 0.75), smoothstep(0.08, 0.25, mx0 - mn0) * smoothstep(0.1, 0.0, abs(scene.r - scene.g * 1.25 - 0.05)) * 0.5);
                    c = satur(c, 1.12);
                    c = mix(c, c * c * (3.0 - 2.0 * c), 0.55);
                    c += grain * 0.035 * (1.0 - l * 0.5);
                    outc = c * mix(0.7, 1.0, vig);
                } else if (uMode == 47) { // dreamy haze: orton glow, lifted lavender blacks, soft pink fog, gentle sparkles
                    vec3 o = 1.0 - (1.0 - scene) * (1.0 - bl * bl * 1.3);
                    vec3 c = mix(scene, o, 0.7);
                    c = c * vec3(1.03, 0.95, 1.06) * 0.85 + vec3(0.1, 0.07, 0.13);
                    c += vec3(1.0, 0.65, 0.75) * max(luma(bl) - 0.55, 0.0) * 0.9;
                    float fog = sky ? 0.0 : 1.0 - exp(-dist * 0.022);
                    c = mix(c, vec3(0.98, 0.86, 0.93) * mix(1.0, skyK, 0.5), fog * (0.55 + 0.25 * fbm(W.xz * 0.04 + uTime * 0.02)));
                    if (sky) {
                        c = mix(vec3(1.0, 0.86, 0.9), vec3(0.62, 0.7, 1.0), smoothstep(-0.05, 0.6, dir.y)) * mix(1.0, skyK, 0.6);
                        c = cumulus(dir, c, normalize(vec3(0.3, 0.4, 0.8)), vec3(1.0, 0.95, 0.98), vec3(0.85, 0.75, 0.9), -0.05);
                    }
                    float sp = motes(dir, maxT, 18.0, pa, 1.8, 2.6, 0.06, 2.0, 0.04, 0.35, 47.0, vec2(0.0));
                    c += chroma(gMoteId + uTime * 0.05) * 0.4 * sp * uPart + sp * 0.3 * uPart;
                    outc = mix(vec3(1.0, 0.93, 0.97), c, mix(0.7, 1.0, vig));
                } else if (uMode == 48) { // autumn: foliage turns red / orange / gold, leaf carpets, low warm sun, tumbling 3D leaves
                    vec3 Ld = normalize(vec3(-0.7, 0.4, 0.45));
                    float green = smoothstep(0.0, 0.08, scene.g - max(scene.r, scene.b) * 0.95) * skyM;
                    float pick = vn(W.xz * 0.13 + W.y * 0.05);
                    vec3 leafC = mix(vec3(0.92, 0.3, 0.07), vec3(1.0, 0.7, 0.12), smoothstep(0.3, 0.65, pick));
                    leafC = mix(leafC, vec3(0.62, 0.1, 0.06), smoothstep(0.72, 0.88, pick));
                    float key = sky ? 1.0 : mix(0.72, 1.18, smoothstep(-0.2, 0.5, dot(N, Ld)));
                    vec3 c = scene * vec3(1.1, 0.97, 0.8) * key;
                    c = mix(c, leafC * (0.2 + luma(scene) * 1.5) * key, green * 0.88);
                    vec4 vr = voroC(W.xz * 3.2);
                    float la = vr.w * 40.0;
                    vec2 lq = mat2(cos(la), -sin(la), sin(la), cos(la)) * vr.xy;
                    float ld = length(vec2(abs(lq.x) + 0.16, lq.y)) - 0.25;
                    float fwL = fwidth(ld) + 0.004;
                    float clus = smoothstep(0.35, 0.65, fbm(W.xz * 0.3));
                    float leaf = (1.0 - smoothstep(-fwL, fwL, ld)) * step(0.75 - clus * 0.6, vr.w) * top * (1.0 - smoothstep(10.0, 30.0, dist));
                    leaf *= 1.0 - 0.35 * (1.0 - smoothstep(0.0, 0.008 + fwL, abs(lq.x)));
                    vec3 lc = mix(vec3(0.85, 0.25, 0.05), vec3(0.98, 0.65, 0.1), fract(vr.w * 7.0));
                    lc = mix(lc, vec3(0.55, 0.12, 0.05), step(0.85, fract(vr.w * 13.0)));
                    c = mix(c, lc * (0.55 + 0.6 * lum) * (0.85 + 0.3 * smoothstep(-0.1, 0.1, lq.x)), clamp(leaf, 0.0, 1.0) * 0.92);
                    c += bloom * vec3(1.0, 0.7, 0.35) * 0.6 + vec3(1.0, 0.75, 0.4) * rim * 0.12;
                    c *= 1.0 - inkAt(vUv, P, dist, 1.3) * skyM * 0.25 * uEdge;
                    c = mix(c, vec3(1.0, 0.75, 0.5) * mix(1.0, skyK, 0.6), (sky ? 0.0 : 1.0 - exp(-dist * 0.012)) * 0.5);
                    if (sky) {
                        vec3 s = mix(vec3(1.0, 0.75, 0.5), vec3(0.38, 0.55, 0.85), smoothstep(-0.05, 0.6, dir.y));
                        c = cumulus(dir, s, Ld, vec3(1.0, 0.9, 0.78), vec3(0.75, 0.6, 0.62), -0.08) * mix(1.0, skyK, 0.6);
                        c += vec3(1.0, 0.7, 0.4) * pow(max(dot(dir, Ld), 0.0), 16.0) * 0.4;
                    }
                    gShape = 1.0;
                    float lf = motes(dir, maxT, 24.0, pa, 1.7, 2.8, 0.75, 2.6, 0.075, 0.42, 48.0, vec2(0.45, 0.15) * uWind);
                    gShape = 0.0;
                    vec3 fc = mix(vec3(0.9, 0.28, 0.06), vec3(1.0, 0.72, 0.15), fract(gMoteId * 5.0));
                    c = mix(c, fc * mix(1.0, skyK, 0.4), clamp(lf, 0.0, 1.0) * 0.95 * min(uPart, 1.0));
                    outc = c * mix(0.82, 1.0, vig);
                } else if (uMode == 49) { // spring bloom: fresh greens, flower dotted meadows, pollen, airy light
                    float green = smoothstep(0.0, 0.08, scene.g - max(scene.r, scene.b) * 0.95) * skyM;
                    vec3 c = satur(scene, 1.18) * vec3(1.02, 1.05, 0.98) + 0.03;
                    c = mix(c, c * vec3(0.82, 1.16, 0.7) + vec3(0.02, 0.04, 0.0), green * 0.55);
                    vec4 vr = voroC(W.xz * 4.5);
                    float rr = length(vr.xy);
                    float ang = atan(vr.y, vr.x);
                    float petal = 0.15 * (0.62 + 0.38 * abs(cos(ang * 2.5 + vr.w * 6.0)));
                    float fwF = fwidth(rr) + 0.006;
                    float fl = (1.0 - smoothstep(petal - fwF, petal + fwF, rr)) * step(0.62, vr.w) * top * (1.0 - smoothstep(8.0, 26.0, dist));
                    vec3 fcol = vr.w < 0.8 ? vec3(1.0, 0.97, 0.98) : vr.w < 0.88 ? vec3(1.0, 0.72, 0.84) : vr.w < 0.94 ? vec3(0.78, 0.68, 1.0) : vec3(1.0, 0.86, 0.3);
                    c = mix(c, fcol * (0.65 + 0.45 * lum), fl);
                    c = mix(c, vec3(1.0, 0.82, 0.2), fl * (1.0 - smoothstep(0.03, 0.045 + fwF, rr)));
                    c += bloom * vec3(1.0, 1.0, 0.9) * 0.6 + vec3(1.0, 0.98, 0.9) * rim * 0.1;
                    c *= 1.0 - inkAt(vUv, P, dist, 1.3) * skyM * 0.2 * uEdge;
                    c = mix(c, vec3(0.86, 0.94, 1.0) * mix(1.0, skyK, 0.5), (sky ? 0.0 : 1.0 - exp(-dist * 0.012)) * 0.45);
                    if (sky) {
                        vec3 s = mix(vec3(0.85, 0.94, 1.0), vec3(0.35, 0.62, 0.98), smoothstep(-0.02, 0.6, dir.y));
                        c = cumulus(dir, s, normalize(vec3(0.4, 0.7, 0.3)), vec3(1.0), vec3(0.75, 0.8, 0.92), -0.12) * mix(1.0, skyK, 0.6);
                    }
                    float po = motes(dir, maxT, 20.0, pa, 1.6, 2.4, 0.05, 2.2, 0.016, 0.45, 49.0, vec2(0.15, 0.05) * uWind);
                    c += vec3(1.0, 0.9, 0.5) * po * 0.8 * uPart;
                    outc = c * mix(0.9, 1.0, vig);
                } else if (uMode == 50) { // bioluminescent night: dark world, glowing moss, pulsing outlines, rising spores
                    float t1 = uTime;
                    vec3 bio = mix(vec3(0.1, 1.0, 0.75), vec3(0.3, 0.45, 1.0), vn(W.xz * 0.08 + t1 * 0.04));
                    vec3 c = satur(scene, 0.5) * vec3(0.16, 0.28, 0.45) * 0.65;
                    float green = smoothstep(0.0, 0.08, scene.g - max(scene.r, scene.b) * 0.95) * skyM;
                    float ink = max(edge, inkAt(vUv, P, dist, 1.3) * skyM);
                    float puls = 0.65 + 0.35 * sin(t1 * 1.4 + dot(W.xz, vec2(0.25, 0.18)));
                    float nearF = 1.0 - smoothstep(18.0, 70.0, dist);
                    c += bio * ink * 0.75 * uEdge * puls * nearF;
                    float moss = smoothstep(0.5, 0.72, fbm(fq * 0.7)) * skyM;
                    vec4 vr = voroC(fq * 3.0);
                    float dots = (1.0 - smoothstep(0.03, 0.09 + fwidth(length(vr.xy)), length(vr.xy))) * step(0.55, vr.w);
                    c += bio * (moss * 0.3 + dots * moss * 1.5 * (0.45 + 0.55 * sin(t1 * 2.0 + vr.w * 30.0))) * nearF;
                    c += bio * green * 0.22 * puls;
                    c += max(scene - 0.7, 0.0) * vec3(0.6, 1.0, 0.9) * 1.5;
                    float fog = sky ? 0.0 : 1.0 - exp(-dist * 0.035);
                    c = mix(c, vec3(0.0, 0.04, 0.07), fog * 0.85);
                    if (sky) {
                        c = mix(vec3(0.0, 0.08, 0.12), vec3(0.0, 0.0, 0.03), smoothstep(-0.05, 0.6, dir.y));
                        vec3 sg = dir * 240.0;
                        float st = hash(floor(sg));
                        c += vec3(0.7, 1.0, 0.95) * step(0.996, st) * smoothstep(0.5, 0.1, length(fract(sg) - 0.5)) * (0.5 + 0.5 * sin(t1 * 2.0 + st * 60.0)) * smoothstep(0.0, 0.2, dir.y);
                    }
                    float sp = motes(dir, maxT, 22.0, pa, 1.5, 2.5, -0.35, 1.6, 0.028, 0.5, 50.0, vec2(0.05, 0.02));
                    c += mix(bio, vec3(0.8, 1.0, 0.6), fract(gMoteId * 3.0)) * sp * 1.5 * uPart;
                    outc = c * mix(0.6, 1.0, vig);
                } else if (uMode == 51) { // synthwave grid: black neon world, glowing magenta/cyan grid, striped retro sun, starfield
                    float fog = sky ? 0.0 : smoothstep(15.0, 150.0, dist);
                    vec3 c = vec3(0.02, 0.0, 0.05) + satur(scene, 0.3) * 0.07;
                    vec3 gc = mix(vec3(0.15, 0.85, 1.0), vec3(1.0, 0.18, 0.75), smoothstep(0.5, 0.9, N.y));
                    vec3 fwq = fwidth(wp);
                    vec3 gd = min(fract(wp), 1.0 - fract(wp)) / max(fwq, vec3(1e-4));
                    float gm = an.y > 0.5 ? min(gd.x, gd.z) : an.x > 0.5 ? min(gd.y, gd.z) : min(gd.x, gd.y);
                    vec3 gw = min(fract(wp), 1.0 - fract(wp));
                    float gwm = an.y > 0.5 ? min(gw.x, gw.z) : an.x > 0.5 ? min(gw.y, gw.z) : min(gw.x, gw.y);
                    float core = 1.0 - smoothstep(0.5, 1.5, gm);
                    float halo = exp(-gwm * 14.0) * 0.5;
                    c += gc * (core * 1.1 + halo) * (1.0 - fog) * uGrid * skyM;
                    c += vec3(0.4, 0.95, 1.0) * max(edge, inkAt(vUv, P, dist, 1.4) * skyM) * 0.9 * uEdge * (1.0 - fog);
                    c = mix(c, vec3(0.35, 0.05, 0.4), fog * 0.75);
                    if (sky) {
                        float h = dir.y;
                        c = mix(vec3(1.0, 0.35, 0.35), vec3(0.45, 0.06, 0.45), smoothstep(-0.02, 0.18, h));
                        c = mix(c, vec3(0.03, 0.0, 0.09), smoothstep(0.15, 0.6, h));
                        vec3 sunD = normalize(vec3(0.0, 0.16, -1.0));
                        float sd2 = length(dir - sunD);
                        float yy = (dir.y - sunD.y) / 0.2;
                        float fws = fwidth(yy * 9.0) + 0.02;
                        float cut = yy < 0.0 ? smoothstep(-fws, fws, fract(yy * 9.0 + uTime * 0.25) - mix(0.0, 0.65, clamp(-yy, 0.0, 1.0))) : 1.0;
                        float sun = (1.0 - smoothstep(0.195, 0.2 + pa, sd2)) * cut;
                        vec3 sunC = mix(vec3(1.0, 0.15, 0.55), vec3(1.0, 0.9, 0.3), smoothstep(-0.8, 0.9, yy));
                        c = mix(c, sunC, sun);
                        c += vec3(1.0, 0.3, 0.6) * exp(-sd2 * 5.0) * 0.3;
                        vec3 sg = dir * 200.0;
                        float st = hash(floor(sg));
                        c += vec3(1.0) * step(0.995, st) * smoothstep(0.5, 0.1, length(fract(sg) - 0.5)) * smoothstep(0.15, 0.4, h);
                        c += vec3(1.0, 0.4, 0.8) * exp(-abs(h) * 60.0) * 0.6;
                    }
                    c *= 1.0 - uScan * 0.12 * (0.5 + 0.5 * sin(gl_FragCoord.y * 1.6));
                    outc = c * mix(0.75, 1.0, vig);
                } else if (uMode == 52) { // vaporwave mall: pastel gradient world, checker marble floors, chrome pink sky, soft vhs fringe
                    float l = luma(scene);
                    vec3 pal = mix(vec3(0.98, 0.58, 0.8), vec3(0.42, 0.9, 0.92), smoothstep(0.15, 0.85, l + 0.18 * sin(W.y * 0.25 + W.x * 0.05)));
                    vec3 c = mix(scene, pal * (0.5 + 0.65 * l), 0.72);
                    float s = sin(3.14159 * W.x) * sin(3.14159 * W.z);
                    float fs = fwidth(s) + 1e-3;
                    float chk = smoothstep(-fs, fs, s);
                    float vein = (1.0 - smoothstep(0.0, 0.03 + fwidth(fbm(W.xz * 1.3)), abs(fbm(W.xz * 1.3) - 0.5))) * 0.25;
                    vec3 tile = mix(vec3(0.96, 0.94, 0.97), vec3(0.98, 0.55, 0.76), chk) - vein;
                    float nearT = 1.0 - smoothstep(25.0, 70.0, dist);
                    c = mix(c, tile * (0.65 + 0.45 * l), top * 0.8 * nearT);
                    c += vec3(1.0, 0.95, 1.0) * pow(max(dot(reflect(V, N), normalize(vec3(0.3, 0.8, 0.2))), 0.0), 50.0) * 0.35 * top;
                    c += vec3(0.5, 1.0, 0.95) * max(edge, inkAt(vUv, P, dist, 1.3) * skyM) * 0.35 * uEdge + bloom * vec3(1.0, 0.7, 0.9) * 0.5;
                    c = mix(c, vec3(0.95, 0.7, 0.88), (sky ? 0.0 : 1.0 - exp(-dist * 0.015)) * 0.5);
                    if (sky) {
                        c = mix(vec3(1.0, 0.72, 0.82), vec3(0.45, 0.82, 0.95), smoothstep(-0.05, 0.6, dir.y));
                        vec2 gq = dir.xz / max(dir.y, 0.05) * 3.0;
                        float gl2 = max(1.0 - smoothstep(0.0, fwidth(gq.x) * 1.5, abs(fract(gq.x) - 0.5) - 0.48), 1.0 - smoothstep(0.0, fwidth(gq.y) * 1.5, abs(fract(gq.y) - 0.5) - 0.48));
                        c += vec3(1.0, 1.0, 1.0) * gl2 * 0.12 * smoothstep(0.05, 0.3, dir.y);
                        vec3 sunD = normalize(vec3(0.5, 0.25, -0.8));
                        c = mix(c, mix(vec3(1.0, 0.5, 0.7), vec3(1.0, 0.95, 0.6), smoothstep(-0.1, 0.1, dir.y - sunD.y)), 1.0 - smoothstep(0.12, 0.125 + pa, length(dir - sunD)));
                    }
                    float cav = 0.002;
                    c.r = mix(c.r, c.r + (luma(texture(uScene, vUv + vec2(cav, 0.0)).rgb) - l) * 0.3, skyM);
                    outc = c * mix(0.85, 1.0, vig);
                } else if (uMode == 53) { // pixel art: chunky pixels, curated 32 colour palette, ordered dither, clean dark outlines
                    float ps = max(uPix, 2.0);
                    vec2 px = uTexel * ps;
                    vec2 cid = floor(vUv / px);
                    vec2 cuv = (cid + 0.5) * px;
                    vec3 s0 = (texture(uScene, cuv).rgb * 2.0 + texture(uScene, cuv + px * 0.25).rgb + texture(uScene, cuv - px * 0.25).rgb) * 0.25;
                    s0 = satur(s0, 1.25) * 1.05;
                    vec3 c = palNear(s0, bayer4(cid));
                    float cd = texture(uDepth, cuv).r;
                    float ol = cd >= 0.99999 ? 0.0 : inkAt(cuv, worldAt(cuv, cd), length(worldAt(cuv, cd)), ps);
                    c = mix(c, hexc(0x181425), step(0.5, ol) * 0.85 * min(uEdge, 1.0));
                    outc = c;
                } else if (uMode == 54) { // stained glass: world-locked glass cells coloured by the scene behind, lead came, backlight
                    float cs2 = 1.7;
                    vec4 vc = voroC(fq * cs2);
                    vec2 cen = fq + vc.xy / cs2;
                    vec2 cu = sky ? vUv : toUv(P + fq2w(cen - fq, an));
                    vec3 g = mix(texture(uScene, cu).rgb, texture(uBlur, cu).rgb, 0.5);
                    float gl0 = luma(g);
                    vec3 hueG = satur(g, 2.2) / max(max(max(g.r, g.g), g.b), 0.05);
                    float pk = fract(vc.w * 7.13);
                    vec3 cath = pk < 0.2 ? vec3(0.78, 0.08, 0.14) : pk < 0.4 ? vec3(0.1, 0.28, 0.85) : pk < 0.58 ? vec3(0.98, 0.62, 0.1)
                              : pk < 0.74 ? vec3(0.1, 0.62, 0.32) : pk < 0.86 ? vec3(0.5, 0.18, 0.72) : vec3(0.9, 0.88, 0.75);
                    vec3 glass = mix(clamp(hueG, 0.0, 1.0), cath, 0.6 * (1.0 - smoothstep(0.25, 0.6, max(max(hueG.r, hueG.g), hueG.b) - min(min(hueG.r, hueG.g), hueG.b)))) * (0.35 + 0.9 * gl0);
                    glass *= 0.82 + 0.3 * fbm(fq * 5.0 + vc.w * 17.0);
                    glass *= 1.1 - length(vc.xy) * 0.45;
                    float fwv = fwidth(vc.z) + 1e-3;
                    float lead = 1.0 - smoothstep(0.022, 0.022 + fwv * 1.5, vc.z);
                    float nearG = (1.0 - smoothstep(25.0, 60.0, dist)) * skyM;
                    float ink = max(inkAt(vUv, P, dist, 2.0) * skyM, gridL(wp, N, 0.02) * nearG * 0.0);
                    lead = max(lead * nearG, ink);
                    vec3 c = mix(satur(scene, 1.5), glass, nearG);
                    if (sky) {
                        vec4 sv = voroC(dir.xz / max(dir.y + 1.1, 0.2) * 9.0);
                        vec3 tint = 0.6 + 0.4 * chroma(sv.w);
                        c = scene * tint * 1.1;
                        lead = 1.0 - smoothstep(0.03, 0.03 + fwidth(sv.z) * 1.5, sv.z);
                    }
                    c += max(bl - 0.5, 0.0) * 0.8;
                    c = mix(c, vec3(0.05, 0.045, 0.04) + vec3(0.25) * pow(max(dot(reflect(V, N), normalize(vec3(0.3, 0.8, 0.2))), 0.0), 8.0) * 0.4, clamp(lead * uEdge, 0.0, 1.0));
                    outc = c * mix(0.75, 1.0, vig);
                } else if (uMode == 55) { // mosaic: world-locked ceramic tiles with grout, each tile one colour, bevel and glaze glints
                    float tsz = 4.0;
                    vec2 q = fq * tsz;
                    vec2 id = floor(q), f = fract(q);
                    vec2 jit = (hh2(id + 3.7) - 0.5) * 0.08;
                    vec2 cen = (id + 0.5 + jit) / tsz;
                    vec2 cu = sky ? vUv : toUv(P + fq2w(cen - fq, an));
                    vec3 tc = texture(uScene, cu).rgb * 0.6 + texture(uBlur, cu).rgb * 0.4;
                    tc = satur(tc, 1.3) * (0.9 + 0.2 * h21(id + 1.1));
                    tc = floor(tc * 6.0 + 0.5) / 6.0 * 0.85 + tc * 0.15;
                    vec2 e2 = min(f - 0.04 - jit, 0.96 - jit - f);
                    float ed = min(e2.x, e2.y);
                    float fwT = fwidth(q.x) + fwidth(q.y);
                    float tileM = smoothstep(0.02, 0.02 + fwT, ed);
                    float bev = clamp(ed * 6.0, 0.0, 1.0);
                    vec3 tl = tc * (0.82 + 0.18 * bev) + vec3(1.0) * (1.0 - bev) * 0.06 * step(0.5, f.x + f.y - 0.6);
                    float glint = pow(max(dot(reflect(V, N), normalize(vec3(0.3, 0.8, 0.2))), 0.0), 30.0) * h21(id + 9.0);
                    tl += glint * 0.35;
                    vec3 grout = vec3(0.78, 0.76, 0.72) * (0.6 + 0.4 * lum);
                    float nearT = (1.0 - smoothstep(20.0, 55.0, dist)) * skyM * (1.0 - smoothstep(0.25, 0.6, fwT));
                    vec3 c = mix(satur(scene, 1.25), mix(grout, tl, tileM), nearT);
                    if (sky) c = scene;
                    c *= 1.0 - inkAt(vUv, P, dist, 1.3) * skyM * 0.3 * uEdge;
                    outc = c * mix(0.85, 1.0, vig);
                } else if (uMode == 56) { // neon wireframe: pure black, glowing triangulated wireframe whose hue flows with height
                    float fog = sky ? 1.0 : smoothstep(10.0, 90.0, dist);
                    vec3 hc = chroma(W.y * 0.025 + uTime * 0.05 + (W.x + W.z) * 0.004);
                    vec2 fqf = fract(fq);
                    vec2 fwq = fwidth(fq) + 1e-4;
                    vec2 ed = min(fqf, 1.0 - fqf) / fwq;
                    float dg = abs(fqf.x - fqf.y) / (0.7071 * (fwq.x + fwq.y));
                    float lineD = min(min(ed.x, ed.y), dg);
                    float ew = min(min(min(fqf.x, 1.0 - fqf.x), min(fqf.y, 1.0 - fqf.y)), abs(fqf.x - fqf.y) * 0.7071);
                    float core = (1.0 - smoothstep(0.4, 1.4, lineD)) * skyM;
                    float halo = exp(-ew * 20.0) * 0.4 * skyM;
                    vec3 c = vec3(0.0, 0.0, 0.015);
                    c += hc * (core + halo * uGrid) * (1.0 - fog);
                    c += mix(hc, vec3(1.0), 0.4) * max(edge, inkAt(vUv, P, dist, 1.3) * skyM) * 1.1 * uEdge * (1.0 - fog * 0.8);
                    c += hc * fog * skyM * 0.05;
                    if (sky) {
                        c = vec3(0.0, 0.0, 0.02) + chroma(uTime * 0.05) * exp(-abs(dir.y) * 25.0) * 0.35;
                        vec3 sg = dir * 220.0;
                        float st = hash(floor(sg));
                        c += vec3(0.8) * step(0.997, st) * smoothstep(0.5, 0.1, length(fract(sg) - 0.5)) * smoothstep(0.05, 0.3, dir.y);
                    }
                    outc = c;
                } else if (uMode == 57) { // x-ray ghost: see-through blue-white, glowing fresnel shells and edges, scan sweep
                    float fr = pow(1.0 - facing, 2.2) * skyM * (1.0 - smoothstep(30.0, 100.0, dist));
                    float ink = max(edge, inkAt(vUv, P, dist, 1.2) * skyM);
                    vec3 c = vec3(0.01, 0.02, 0.05);
                    c += vec3(0.5, 0.78, 1.0) * fr * 0.85;
                    c += vec3(0.75, 0.9, 1.0) * ink * 1.0 * uEdge;
                    c += vec3(0.25, 0.5, 0.9) * (1.0 - lum) * 0.12 * skyM * (1.0 - smoothstep(10.0, 60.0, dist));
                    c += vec3(0.4, 0.7, 1.0) * gline * 0.12 * (1.0 - smoothstep(4.0, 25.0, dist));
                    c += vec3(0.6, 0.85, 1.0) * max(luma(bl) - 0.5, 0.0) * 0.8;
                    float sweep = exp(-abs(fract(dist * 0.02 - uTime * 0.25) - 0.5) * 60.0) * skyM;
                    c += vec3(0.5, 0.85, 1.0) * sweep * 0.35;
                    c += grain * 0.04;
                    if (sky) c = vec3(0.01, 0.02, 0.06) + vec3(0.1, 0.2, 0.4) * pow(1.0 - abs(dir.y), 8.0) * 0.4;
                    outc = c * mix(0.6, 1.0, vig);
                } else if (uMode == 58) { // desert heat: bleached warm light, rising heat shimmer, horizon mirage pools, drifting dust
                    float hk = sky ? smoothstep(0.25, 0.0, dir.y) : smoothstep(8.0, 60.0, dist);
                    vec2 hd = (vec2(vn(vec2(vUv.x * 50.0, vUv.y * 70.0 - uTime * 3.5)), vn(vec2(vUv.x * 50.0 + 7.0, vUv.y * 70.0 - uTime * 3.0))) - 0.5) * uTexel * 7.0 * hk;
                    vec3 sc = texture(uScene, vUv + hd).rgb;
                    vec3 c = sc * vec3(1.15, 1.0, 0.78);
                    c = mix(c, vec3(luma(c)) * vec3(1.12, 0.96, 0.74), 0.3) * 1.06 + 0.04;
                    vec3 Ld = normalize(vec3(0.2, 0.9, 0.3));
                    c *= sky ? 1.0 : mix(0.8, 1.15, smoothstep(-0.2, 0.6, dot(N, Ld)));
                    // mirage: far grazing ground mirrors what is above the horizon
                    float hy = toUv(normalize(vec3(V.x, 0.0, V.z)) * 2000.0).y;
                    float mir = top * smoothstep(30.0, 80.0, dist) * (1.0 - smoothstep(0.0, 0.07, -V.y));
                    vec2 muv = vec2(vUv.x + hd.x * 3.0, clamp(2.0 * hy - vUv.y + (vn(vec2(vUv.x * 30.0, uTime * 2.0)) - 0.5) * 0.004, 0.0, 1.0));
                    c = mix(c, texture(uBlur, muv).rgb * vec3(1.0, 1.0, 1.05), mir * 0.75);
                    c = mix(c, vec3(0.98, 0.84, 0.62) * mix(1.0, skyK, 0.5), (sky ? 0.0 : 1.0 - exp(-dist * 0.013)) * 0.6);
                    if (sky) {
                        c = mix(vec3(1.0, 0.94, 0.82), vec3(0.45, 0.66, 0.9), smoothstep(-0.02, 0.6, dir.y)) * mix(1.0, skyK, 0.6);
                        float sdt = max(dot(dir, Ld), 0.0);
                        c += vec3(1.0, 0.95, 0.85) * (smoothstep(0.9985, 0.9992, sdt) + pow(sdt, 30.0) * 0.5 + pow(sdt, 4.0) * 0.15);
                    }
                    c += bloom * vec3(1.0, 0.9, 0.7) * 0.5;
                    float du = motes(dir, maxT, 20.0, pa, 1.5, 2.2, 0.03, 2.5, 0.018, 0.4, 58.0, vec2(1.0, 0.3) * uWind);
                    c += vec3(1.0, 0.9, 0.7) * du * 0.45 * uPart;
                    outc = c * mix(0.85, 1.0, vig);
                } else if (uMode == 59) { // arctic blizzard: wind driven 3D snow in layers, whiteout gusts, drifts, frosted lens edges
                    vec3 c = mix(vec3(lum), scene, 0.3) * vec3(0.85, 0.93, 1.05) + 0.04;
                    float sn = top * smoothstep(0.35, 0.6, vn(W.xz * 2.5) * 0.45 + N.y * 0.6);
                    c = mix(c, vec3(0.93, 0.96, 1.0) * (0.75 + 0.3 * lum), sn * 0.9);
                    float side = (1.0 - top) * skyM * smoothstep(0.55, 0.7, fbm(fq * vec2(3.0, 1.0)) + max(dot(N.xz, normalize(gWind + 1e-4)), 0.0) * 0.3);
                    c = mix(c, vec3(0.9, 0.94, 1.0), side * 0.6 * (1.0 - smoothstep(10.0, 40.0, dist)));
                    vec2 wd = normalize(gWind + vec2(1e-4));
                    float gust = fbm(vec2(dot(W.xz, wd) * 0.07 - uTime * 1.4 * uWind, W.y * 0.25 + dot(W.xz, vec2(-wd.y, wd.x)) * 0.05));
                    float fog = sky ? 0.0 : 1.0 - exp(-dist * (0.035 + 0.035 * gust));
                    vec3 fogc = vec3(0.82, 0.87, 0.93) * mix(1.0, skyK, 0.6);
                    c = mix(c, fogc, fog * 0.92);
                    if (sky) c = mix(vec3(0.85, 0.89, 0.94), vec3(0.62, 0.7, 0.8), clamp(dir.y * 1.5, 0.0, 1.0)) * mix(1.0, skyK, 0.6) * (0.92 + 0.12 * gust);
                    vec2 wv = vec2(3.2, 1.1) * uWind;
                    float s1 = motes(dir, maxT, 7.0, pa, 0.8, 1.2, 2.2, 0.8, 0.03, 0.6, 59.0, wv);
                    float s2 = motes(dir, maxT, 22.0, pa, 1.2, 1.4, 1.8, 0.6, 0.03, 0.8, 159.0, wv * 0.9);
                    float s3 = motes(dir, maxT, 2.5, pa, 1.0, 1.6, 2.4, 0.5, 0.09, 0.25, 259.0, wv * 1.1);
                    c = mix(c, vec3(1.0), clamp((s1 + s2) * 0.9 + s3 * 0.35, 0.0, 1.0) * min(uPart, 1.2));
                    float fe = smoothstep(0.55, 0.95, length((vUv - 0.5) * vec2(1.3, 1.0)) * 1.25 + (fbm(gl_FragCoord.xy * 0.012) - 0.5) * 0.35);
                    c = mix(c, mix(bl, vec3(0.92, 0.96, 1.0), 0.6), fe * 0.7);
                    outc = c;
                } else if (uMode == 60) { // volcanic ash: choking brown haze, glowing fissures, ash fall and rising embers, red underglow
                    vec3 c = satur(scene, 0.35) * vec3(0.6, 0.53, 0.48) * 0.8;
                    c += vec3(1.0, 0.3, 0.05) * max(-N.y, 0.0) * 0.25 * skyM;
                    vec3 vr = voro(W.xz * 1.1);
                    float fwc = fwidth(vr.y - vr.x) + 0.004;
                    float crack = (1.0 - smoothstep(0.0, 0.05 + fwc, vr.y - vr.x)) * top * smoothstep(0.45, 0.65, fbm(W.xz * 0.08)) * (1.0 - smoothstep(15.0, 50.0, dist));
                    c += lavaPal(0.75 + 0.15 * sin(uTime * 1.3 + vr.z * 20.0), vr.z) * crack * 0.8;
                    vec3 realL = lavaPal(flowN(W.xz * 0.45, vec2(0.8, 0.4), 0.1) * 0.7 + 0.35, 1.0) * 1.1;
                    c = mix(c, realL, lavaPx);
                    c += bloom * vec3(1.0, 0.45, 0.12) * 0.9;
                    c *= 1.0 - inkAt(vUv, P, dist, 1.3) * skyM * 0.3 * uEdge;
                    float fog = sky ? 0.0 : 1.0 - exp(-dist * 0.038);
                    vec3 fogc = mix(vec3(0.24, 0.18, 0.15), vec3(0.5, 0.18, 0.06), exp(-abs(dir.y) * 6.0) * 0.8);
                    c = mix(c, fogc * (0.85 + 0.25 * fbm(W.xz * 0.05 + uTime * 0.03)), fog * 0.9);
                    if (sky) {
                        float sm = fbm(dir.xz / max(dir.y + 0.25, 0.08) * 1.3 + vec2(uTime * 0.03, uTime * 0.01));
                        c = mix(vec3(0.6, 0.18, 0.05), vec3(0.12, 0.09, 0.08), smoothstep(-0.05, 0.35, dir.y)) * (0.6 + 0.7 * sm);
                    }
                    float ash = motes(dir, maxT, 22.0, pa, 1.1, 1.8, 0.45, 1.6, 0.026, 0.7, 60.0, vec2(0.25, 0.08) * uWind);
                    c = mix(c, vec3(0.42, 0.4, 0.38), clamp(ash, 0.0, 1.0) * 0.8 * min(uPart, 1.0));
                    float em = motes(dir, maxT, 24.0, pa, 2.0, 2.5, -1.0, 1.4, 0.02, 0.3, 160.0, vec2(0.2, 0.1));
                    c += vec3(1.0, 0.5, 0.12) * em * 2.0 * uPart;
                    outc = (c + grain * 0.04) * mix(0.6, 1.0, vig);
                } else if (uMode == 61) { // candyland: candy palette, icing with drips over block tops, sprinkles, glossy shine, cotton candy sky
                    vec3 k = kuwa(vUv, 1.2, 3);
                    vec3 c = satur(k, 1.7);
                    c = mix(c, c * vec3(1.1, 0.82, 1.05) + vec3(0.14, 0.06, 0.12), 0.5);
                    c = c * 0.72 + vec3(0.26, 0.2, 0.24);
                    vec3 Wi = W - N * 0.02;
                    float iceSel = h21(floor(Wi.xz / 3.0) + floor(Wi.y) * 7.31);
                    vec3 icing = iceSel < 0.45 ? vec3(1.0, 0.97, 0.98) : iceSel < 0.7 ? vec3(1.0, 0.72, 0.85) : vec3(0.7, 0.92, 1.0);
                    float dripL = 0.12 + 0.3 * vn(vec2(fq.x * 4.0, floor(Wi.y) * 3.1)) * vn(vec2(fq.x * 1.3, 5.0));
                    float dy = 1.0 - fract(Wi.y);
                    float fwI = fwidth(dy) + 0.01;
                    float ice = top + (1.0 - top) * skyM * (1.0 - smoothstep(dripL - fwI, dripL + fwI, dy)) * (1.0 - smoothstep(18.0, 40.0, dist));
                    ice = clamp(ice, 0.0, 1.0) * skyM;
                    c = mix(c, icing * (0.75 + 0.3 * lum), ice * 0.9);
                    vec2 sq = W.xz * 7.0;
                    vec2 sid = floor(sq);
                    float sa = h21(sid) * 6.2831;
                    vec2 sf = mat2(cos(sa), -sin(sa), sin(sa), cos(sa)) * (fract(sq) - 0.5 - (hh2(sid) - 0.5) * 0.4);
                    float spr = length(vec2(max(abs(sf.x) - 0.13, 0.0), sf.y)) - 0.045;
                    float fwS = fwidth(spr) + 1e-3;
                    float sprM = (1.0 - smoothstep(-fwS, fwS, spr)) * step(0.55, h21(sid + 4.2)) * top * (1.0 - smoothstep(6.0, 18.0, dist));
                    c = mix(c, 0.35 + 0.65 * chroma(h21(sid + 8.0)), sprM);
                    float spec = pow(max(dot(reflect(V, N), normalize(vec3(0.3, 0.85, 0.2))), 0.0), 40.0);
                    c += vec3(1.0) * spec * 0.4 * skyM + vec3(1.0, 0.9, 0.95) * rim * 0.2;
                    c = mix(c, vec3(0.55, 0.25, 0.4), clamp(inkAt(vUv, P, dist, 1.4) * skyM * 0.5 * uEdge, 0.0, 1.0));
                    c += bloom * vec3(1.0, 0.8, 0.9) * 0.5;
                    c = mix(c, vec3(1.0, 0.85, 0.93) * mix(1.0, skyK, 0.5), (sky ? 0.0 : 1.0 - exp(-dist * 0.014)) * 0.5);
                    if (sky) {
                        vec3 s = mix(vec3(1.0, 0.78, 0.88), vec3(0.55, 0.78, 1.0), smoothstep(-0.05, 0.55, dir.y));
                        c = cumulus(dir, s, normalize(vec3(0.3, 0.6, 0.4)), vec3(1.0, 0.9, 0.96), vec3(0.95, 0.6, 0.8), -0.02) * mix(1.0, skyK, 0.6);
                    }
                    outc = c * mix(0.88, 1.0, vig);
                } else if (uMode == 62) { // sumi-e ink: rice paper, layered ink washes fading with distance, dry brush strokes, red sun
                    vec3 paper = vec3(0.94, 0.91, 0.84) * (0.96 + 0.04 * vn(gl_FragCoord.xy * 0.8)) - vn(gl_FragCoord.xy * vec2(0.02, 0.3)) * 0.02;
                    float l = luma(mix(scene, bl, 0.45));
                    float dens = smoothstep(0.8, 0.12, l) * (sky ? 0.0 : exp(-dist * 0.022));
                    float wash = dens + (fbm(gl_FragCoord.xy * 0.012) - 0.5) * 0.3 * dens;
                    float wq = wash * 3.0;
                    float fwW = fwidth(wq) + 0.15;
                    wq = (floor(wq) + smoothstep(0.5 - fwW, 0.5 + fwW, fract(wq))) / 3.0;
                    float ink = inkAt(vUv, P, dist, 1.4 + 1.6 * vn(gl_FragCoord.xy * 0.015)) * skyM;
                    ink *= smoothstep(0.2, 0.55, vn(gl_FragCoord.xy * vec2(0.06, 0.45)) + 0.25) * exp(-dist * 0.01);
                    vec3 c = mix(paper, vec3(0.07, 0.07, 0.09), clamp(wq * 0.72 + ink * 0.95 * uEdge, 0.0, 1.0));
                    float mist = smoothstep(0.3, 0.7, fbm(W.xz * 0.05 + vec2(uTime * 0.02, 0.0))) * smoothstep(6.0, 30.0, dist) * skyM;
                    c = mix(c, paper, mist * 0.5);
                    if (sky) {
                        vec3 sunD = normalize(vec3(0.6, 0.25, -0.75));
                        float sdd = length(dir - sunD);
                        float rim2 = 0.09 + (vn(vec2(atan(dir.y - sunD.y, dir.x - sunD.x) * 6.0, 1.0)) - 0.5) * 0.008;
                        c = mix(paper, vec3(0.78, 0.16, 0.12), (1.0 - smoothstep(rim2 - pa, rim2 + pa * 2.0, sdd)) * 0.85);
                    }
                    outc = c;
                } else if (uMode == 63) { // retro vhs: tracking band, line jitter, chroma bleed, head switching noise, tape speckle
                    float ln = gl_FragCoord.y;
                    float bandY = fract(-uTime * 0.06);
                    float by = vUv.y - bandY;
                    float band = exp(-by * by * 700.0);
                    float jit = (h21(vec2(floor(ln / 2.0), floor(uTime * 30.0))) - 0.5) * uTexel.x * 1.5 + band * (vn(vec2(ln * 0.25, uTime * 25.0)) - 0.5) * 0.025;
                    float hs = 1.0 - smoothstep(0.0, 0.03, vUv.y);
                    jit += hs * (vn(vec2(ln * 0.4, uTime * 12.0)) - 0.2) * 0.05;
                    vec2 uv = vUv + vec2(jit, 0.0);
                    vec3 a = (texture(uScene, uv).rgb * 2.0 + texture(uScene, uv - vec2(uTexel.x, 0.0)).rgb + texture(uScene, uv + vec2(uTexel.x, 0.0)).rgb) * 0.25;
                    vec3 ch = vec3(0.0);
                    for (int i = 0; i < 6; i++) ch += texture(uScene, uv - vec2(float(i) * uTexel.x * 3.0 + 0.003, 0.0)).rgb;
                    ch /= 6.0;
                    vec3 yq = vec3(yiq(a).x, yiq(ch).yz * 0.85);
                    yq.x = yq.x * 0.9 + 0.05;
                    vec3 c = rgbq(yq);
                    c = mix(c, c * vec3(1.05, 0.98, 0.92), 0.5);
                    c += band * (hash(vec3(floor(gl_FragCoord.xy / vec2(3.0, 1.0)), floor(uTime * 30.0))) - 0.5) * 0.35;
                    c += vec3(0.9) * step(0.9975, hash(vec3(floor(gl_FragCoord.xy / vec2(5.0, 1.0)), floor(uTime * 24.0))));
                    c = mix(c, vec3(h21(vec2(ln, floor(uTime * 30.0)))), hs * 0.5);
                    c *= 0.94 + 0.06 * sin(ln * 1.7);
                    c += grain * 0.05;
                    outc = c * mix(0.75, 1.0, vig);
                } else if (uMode == 64) { // film noir 1940s: silver b&w, glamour diffusion, drifting smoke, gate weave, dust and scratches
                    float fr = floor(uTime * 24.0);
                    vec2 weave = (vec2(h21(vec2(fr, 1.0)), h21(vec2(fr, 2.0))) - 0.5) * uTexel * 1.5;
                    vec3 sc = texture(uScene, vUv + weave).rgb;
                    vec3 sb = texture(uBlur, vUv + weave).rgb;
                    float l = luma(sc);
                    l = mix(l, max(l, luma(sb)), 0.4) + max(luma(sb) - 0.5, 0.0) * 0.7;
                    vec3 Ld = normalize(vec3(-0.5, 0.7, 0.4));
                    l *= sky ? 1.0 : mix(0.55, 1.15, smoothstep(-0.1, 0.4, dot(N, Ld)));
                    l = smoothstep(0.03, 0.88, l);
                    l = pow(l, 1.12);
                    vec3 c = vec3(l) * vec3(1.0, 0.985, 0.95);
                    c *= 1.0 - inkAt(vUv, P, dist, 1.3) * skyM * 0.35 * uEdge;
                    float sm = fbm(su * 2.2 + vec2(uTime * 0.04, uTime * 0.015));
                    c = mix(c, vec3(0.72), smoothstep(0.45, 0.85, sm) * 0.2 * (sky ? 0.3 : smoothstep(2.0, 20.0, dist)));
                    c = mix(c, vec3(0.32), (sky ? 0.0 : 1.0 - exp(-dist * 0.018)) * 0.45);
                    c *= 0.95 + 0.05 * h21(vec2(fr, 5.0));
                    float scx = h21(vec2(floor(uTime * 3.0), 7.0)) / uTexel.x;
                    c = mix(c, vec3(0.9), (1.0 - smoothstep(0.0, 1.2, abs(gl_FragCoord.x - scx))) * step(0.5, h21(vec2(fr, 9.0))) * 0.35);
                    float spk = hash(vec3(floor(gl_FragCoord.xy / 3.0), fr));
                    c *= 1.0 - 0.7 * step(0.9992, spk);
                    c += 0.6 * step(0.9994, hash(vec3(floor(gl_FragCoord.xy / 2.0), fr + 0.5)));
                    c += grain * 0.1 * (1.0 - l * 0.4);
                    outc = c * (1.0 - smoothstep(0.4, 0.95, length((vUv - 0.5) * vec2(1.25, 1.0))) * 0.75);
                } else if (uMode == 65) { // polaroid: faded instant film, cyan shadows, creamy highlights, light leaks, soft corners
                    vec3 c = scene * 0.84 + vec3(0.085, 0.075, 0.1);
                    float l = luma(c);
                    c = mix(c, c * vec3(0.84, 1.02, 1.06) + vec3(0.0, 0.02, 0.035), (1.0 - smoothstep(0.0, 0.5, l)) * 0.65);
                    c *= mix(vec3(1.0), vec3(1.08, 1.03, 0.86), smoothstep(0.35, 1.0, l));
                    c = satur(c, 0.85);
                    c += max(bl - 0.5, 0.0) * vec3(1.0, 0.9, 0.75) * 0.5;
                    float rr = length((vUv - 0.5) * vec2(1.3, 1.0));
                    c = mix(c, satur(bl, 0.85) * 0.9 + 0.06, smoothstep(0.35, 0.8, rr) * 0.45);
                    float lk = exp(-length((vUv - vec2(-0.05, 1.05 - 0.05 * sin(uTime * 0.2))) * vec2(1.6, 1.0)) * 3.2);
                    float lk2 = exp(-length((vUv - vec2(1.08, 0.25)) * vec2(1.0, 1.4)) * 4.5);
                    c += vec3(1.0, 0.45, 0.15) * lk * 0.5 + vec3(1.0, 0.25, 0.3) * lk2 * 0.25;
                    c += grain * 0.035;
                    outc = c * mix(0.8, 1.0, vig);
                } else if (uMode == 66) { // low poly: world-locked triangle facets, each flat coloured and flat lit, banded sky
                    vec3 Ld = normalize(vec3(0.45, 0.8, 0.35));
                    float ts = dist < 28.0 ? 0.5 : 2.0;
                    vec2 q = fq / ts;
                    vec2 id = floor(q), f = fract(q);
                    bool flp = h21(id + 0.7) > 0.5;
                    vec2 g = flp ? vec2(1.0 - f.x, f.y) : f;
                    float tri = g.x > g.y ? 1.0 : 0.0;
                    vec2 cen = tri > 0.5 ? vec2(0.6667, 0.3333) : vec2(0.3333, 0.6667);
                    if (flp) cen.x = 1.0 - cen.x;
                    vec2 cq = (id + cen) * ts;
                    vec2 cu = sky ? vUv : toUv(P + fq2w(cq - fq, an));
                    vec3 col = mix(texture(uScene, cu).rgb, texture(uBlur, cu).rgb, 0.45);
                    vec3 tn = normalize(N + (vec3(h21(id + tri * 7.0 + 1.3), h21(id + 3.1 + tri), h21(id + tri * 2.0 + 9.7)) - 0.5) * 0.7);
                    float ndl = dot(tn, Ld) * 0.5 + 0.5;
                    vec3 c = satur(col, 1.2) * (0.72 + 0.5 * ndl);
                    float ew = min(min(g.x, 1.0 - g.y), abs(g.x - g.y) * 0.7071);
                    ew = min(ew, min(1.0 - g.x, g.y));
                    float fwE = fwidth(q.x) + fwidth(q.y);
                    c *= 1.0 - (1.0 - smoothstep(0.0, fwE * 1.2, ew)) * 0.12 * skyM;
                    c = mix(c, vec3(0.75, 0.85, 0.95) * mix(1.0, skyK, 0.5), (sky ? 0.0 : 1.0 - exp(-dist * 0.012)) * 0.4);
                    if (sky) {
                        float hb = clamp(dir.y, 0.0, 1.0) * 7.0;
                        float fwB = fwidth(hb) + 0.02;
                        hb = (floor(hb) + smoothstep(0.5 - fwB, 0.5 + fwB, fract(hb))) / 7.0;
                        c = mix(vec3(0.98, 0.85, 0.7), vec3(0.3, 0.52, 0.85), smoothstep(0.0, 0.7, hb)) * mix(1.0, skyK, 0.6);
                    }
                    outc = c * mix(0.88, 1.0, vig);
                } else if (uMode == 67) { // cartoon: punchy flat colours, three tone cel light, thick boiling ink lines, outlined clouds
                    vec3 Ld = normalize(vec3(0.45, 0.8, 0.35));
                    vec3 k = kuwa(vUv, 1.5, 3);
                    float kl = luma(k);
                    vec3 hue = satur(k, 1.7) / max(kl, 0.04);
                    float lit = sky ? 1.0 : kl * (0.55 + 0.65 * clamp(dot(N, Ld) * 0.5 + 0.5, 0.0, 1.0));
                    float fwl = fwidth(lit) + 0.015;
                    float b1 = smoothstep(0.22 - fwl, 0.22 + fwl, lit), b2 = smoothstep(0.5 - fwl, 0.5 + fwl, lit);
                    vec3 c = clamp(hue * mix(mix(0.22, 0.5, b1), 0.72, b2), 0.0, 1.2);
                    c += vec3(1.0) * smoothstep(0.93, 0.95, dot(reflect(V, N), Ld)) * 0.5 * skyM;
                    float fr = floor(uTime * 8.0);
                    vec2 jo = (vec2(vn(gl_FragCoord.xy * 0.04 + fr * 7.1), vn(gl_FragCoord.xy * 0.04 + fr * 3.3 + 9.0)) - 0.5) * uTexel * 3.0;
                    float ink = max(inkAt(vUv + jo, P, dist, 3.0) * skyM, smoothstep(0.4, 0.7, sob(vUv + jo, 2.0)) * 0.8);
                    c *= 1.0 - clamp(ink * uEdge, 0.0, 1.0) * 0.95;
                    if (sky) {
                        vec3 s = mix(vec3(0.55, 0.85, 1.0), vec3(0.15, 0.55, 1.0), smoothstep(0.0, 0.6, dir.y)) * skyK;
                        vec2 cu = dir.xz / max(dir.y + 0.1, 0.06) * 0.8 + vec2(uTime * 0.01, 0.0);
                        float f = fbm(cu);
                        float fwc = fwidth(f) + 0.004;
                        float cl = smoothstep(0.55 - fwc, 0.55 + fwc, f);
                        float olc = 1.0 - smoothstep(0.0, fwc * 2.5, abs(f - 0.55));
                        c = mix(s, vec3(1.0) * mix(1.0, skyK, 0.5), cl * smoothstep(0.0, 0.1, dir.y));
                        c *= 1.0 - olc * 0.85 * smoothstep(0.0, 0.1, dir.y);
                    }
                    outc = c;
                } else if (uMode == 68) { // underworld: crimson dark, river of souls on the ground, ghost-green outlines, veined sky
                    float fog = sky ? 1.0 : 1.0 - exp(-dist * 0.04);
                    vec3 c = satur(scene, 0.25) * vec3(0.55, 0.32, 0.3) * 0.6;
                    c += vec3(1.0, 0.25, 0.05) * max(-N.y, 0.0) * 0.35 * skyM;
                    float ink = max(edge, inkAt(vUv, P, dist, 1.3) * skyM);
                    c += vec3(0.3, 1.0, 0.82) * ink * 0.55 * uEdge * (0.6 + 0.4 * sin(uTime * 1.3 + W.y * 0.5)) * (1.0 - fog);
                    float sn = flowN(W.xz * 0.3, vec2(1.0, 0.3), 0.07);
                    float wisp = smoothstep(0.58, 0.8, sn) * top * (1.0 - smoothstep(10.0, 45.0, dist));
                    c += vec3(0.25, 0.9, 0.8) * wisp * 0.35;
                    c = mix(c, lavaPal(flowN(W.xz * 0.45, vec2(0.8, 0.4), 0.1) * 0.7 + 0.35, 1.0) * 1.1, lavaPx);
                    c += bloom * vec3(1.0, 0.35, 0.15) * 0.7;
                    vec3 fogc = mix(vec3(0.2, 0.02, 0.02), vec3(0.02, 0.0, 0.01), smoothstep(-0.2, 0.4, dir.y));
                    c = mix(c, fogc, fog * 0.9);
                    if (sky) {
                        vec2 sq = dir.xz / max(dir.y + 0.4, 0.1) * 2.0 + uTime * 0.015;
                        float vn2 = abs(fbm(sq) - 0.5);
                        c += vec3(0.9, 0.08, 0.05) * (1.0 - smoothstep(0.0, 0.025 + fwidth(vn2), vn2)) * 0.5 * smoothstep(-0.1, 0.3, dir.y);
                    }
                    float so = motes(dir, maxT, 22.0, pa, 1.9, 3.0, -0.6, 1.8, 0.04, 0.4, 68.0, vec2(0.0));
                    c += vec3(0.4, 1.0, 0.85) * so * 0.9 * uPart;
                    float em = motes(dir, maxT, 20.0, pa, 2.0, 2.5, -1.2, 1.2, 0.018, 0.3, 168.0, vec2(0.1, 0.05));
                    c += vec3(1.0, 0.45, 0.1) * em * 1.6 * uPart;
                    outc = c * mix(0.4, 1.0, vig);
                } else if (uMode == 69) { // thunderstorm: dark soaked world, rolling clouds, lightning bolts that light the whole scene
                    float per = 6.5;
                    float k = floor(uTime / per);
                    float tt = uTime - k * per;
                    float hk = h21(vec2(k, 4.2));
                    float t0 = 1.0 + hk * 3.5;
                    // return strokes: a main flash and 2-3 re-strikes, then the flicker dies away
                    float tf = tt - t0;
                    float fl = step(0.0, tf) * step(0.2, hk) * (exp(-tf * 7.0) + 0.8 * exp(-abs(tf - 0.12) * 40.0) * step(0.4, hk)
                             + 0.6 * exp(-abs(tf - 0.3) * 30.0) + 0.35 * exp(-abs(tf - 0.55) * 22.0) * step(0.6, hk));
                    float ba = hk * 6.2831;
                    vec2 bd = vec2(cos(ba), sin(ba));
                    vec3 Lb = normalize(vec3(bd.x, 0.9, bd.y));
                    float sheet = 0.25 * step(0.5, h21(vec2(k, 9.1))) * exp(-abs(tt - 0.7 - hk) * 5.0);   // distant cloud-to-cloud glow
                    vec3 c = satur(scene, 0.5) * vec3(0.6, 0.65, 0.78) * 0.58;
                    c += vec3(0.6, 0.66, 0.78) * rip.x * 0.14;
                    // flash light comes from the bolt's side: faces turned to it light up, the rest stays in shadow
                    float lit = 0.15 + 0.85 * max(dot(N, Lb), 0.0);
                    c += (vec3(0.75, 0.82, 1.0) * 0.2 + satur(scene, 0.3) * 1.3) * fl * skyM * lit * exp(-dist * 0.01);
                    float mist = (sky ? 0.0 : 1.0 - exp(-dist * 0.03)) * (0.75 + 0.25 * fbm(W.xz * 0.05 + vec2(uTime * 0.08, 0.0)));
                    c = mix(c, vec3(0.13, 0.15, 0.2) + fl * vec3(0.35, 0.38, 0.45), mist * 0.8);
                    if (sky) {
                        vec2 cu = dir.xz / max(dir.y + 0.15, 0.08) * 1.1 + vec2(uTime * 0.05, uTime * 0.01);
                        float f = fbm(cu) * 0.7 + fbm(cu * 2.7) * 0.3;
                        c = mix(vec3(0.08, 0.09, 0.12), vec3(0.3, 0.32, 0.38), f) + fl * vec3(0.55, 0.6, 0.75) * (0.4 + f);
                        c += vec3(0.35, 0.38, 0.5) * sheet * f;
                        vec2 dh = normalize(dir.xz + 1e-5);
                        float lat = dh.x * bd.y - dh.y * bd.x;
                        float jag = (fbm(vec2(dir.y * 9.0, k * 3.0)) - 0.5) * 0.14 + (vn(vec2(dir.y * 70.0, k)) - 0.5) * 0.02;
                        float dl = abs(lat - jag);
                        float bolt = ((1.0 - smoothstep(0.0, 0.0025 + pa * 1.5, dl)) + exp(-dl * 70.0) * 0.35) * step(0.0, dot(dh, bd)) * smoothstep(0.55, 0.3, dir.y) * smoothstep(-0.02, 0.05, dir.y);
                        c += vec3(0.85, 0.9, 1.0) * bolt * clamp(fl * 1.5, 0.0, 1.0);
                    }
                    float rainRange = rainVis(dist, sky);
                    c = mix(c, vec3(0.24, 0.27, 0.33) + fl * 0.35, rainSheets(dir) * (sky ? 1.0 : smoothstep(10.0, 60.0, dist)) * 0.4 * min(uPart, 1.5) * rainRange);
                    float rn = rainFx(dir, maxT, pa, 1.35, 7.0);
                    c = rainMix(c, rn * uPart * rainRange, vec3(0.5, 0.55, 0.65) + fl * 0.9, 1.6);
                    outc = c * mix(0.62, 1.0, vig);
                }
                // uMode 70 = None: reflection only
                if (wet && uLens > 0.002) {
                    // rain on the lens: out-of-focus water drops that refract (and flip) the view; big ones slide down
                    vec2 off = vec2(0.0);
                    float m = 0.0, hl = 0.0, rimA = 0.0;
                    for (int l = 0; l < 3; l++) {
                        float fl = float(l);
                        float sc = 4.0 + fl * 3.5;
                        vec2 q = su * sc + vec2(fl * 7.7, fl * 3.1);
                        float col = floor(q.x);
                        float slide = l == 0 ? 1.0 : l == 1 ? 0.35 : 0.0;
                        float sp = (0.15 + 0.5 * h21(vec2(col, fl + 2.0))) * slide;
                        q.y += uTime * sp * sc * 0.12 + h21(vec2(col, fl)) * 10.0;
                        vec2 id = floor(q);
                        vec2 hj = hh2(id + fl * 13.0);
                        if (h21(id + fl * 5.0 + 0.7) < uLens * (0.2 + 0.12 * fl)) {
                            vec2 dv = fract(q) - 0.5 - (hj - 0.5) * 0.45;
                            float r = 0.06 + 0.1 * hj.x * hj.x + 0.05 * (1.0 - fl * 0.4);
                            dv.y *= mix(1.0, 0.75, slide * step(0.15, sp));           // sliding drops stretch along their path
                            dv.x *= 1.0 + 0.15 * sin(hj.y * 30.0 + dv.y * 12.0);       // not perfectly round
                            float dl = length(dv) / r;
                            float mk = 1.0 - smoothstep(0.82, 1.0, dl);
                            float zz = sqrt(max(1.0 - dl * dl, 0.0));
                            off -= dv / r * zz * mk * (0.025 + 0.02 * hj.x) * (1.0 - fl * 0.25);
                            hl += smoothstep(0.22, 0.0, length(dv / r - vec2(-0.28, 0.4))) * mk;
                            rimA = max(rimA, smoothstep(0.6, 0.92, dl) * mk);
                            m = max(m, mk);
                            // trail left behind a sliding drop
                            float tr = slide * step(0.15, sp) * (1.0 - smoothstep(0.02, 0.05, abs(fract(q.x) - 0.5 - (hj.x - 0.5) * 0.45))) * smoothstep(0.0, 0.4, fract(q.y) - 0.5) * 0.35;
                            m = max(m, tr * 0.6);
                        }
                    }
                    vec2 luv = clamp(vUv + off * vec2(uTexel.y / uTexel.x, 1.0) * 1.0 - off * 0.0, 0.001, 0.999);
                    vec3 dropC = texture(uScene, luv).rgb;
                    vec3 o0 = clamp(outc, 0.0, 1.0);
                    dropC = dropC * (luma(o0) + 0.04) / (luma(scene) + 0.04);
                    dropC = mix(vec3(luma(dropC)), dropC, 0.85) * 1.06;
                    outc = mix(o0, dropC, m * 0.8) * (1.0 - rimA * 0.35) + vec3(0.85, 0.9, 1.0) * hl * 0.35;
                }
                fragColor = vec4(mix(scene, clamp(outc, 0.0, 1.0), uInt), 1.0);
            }
            """;
    /** Split in parts: a single constant would exceed the class file string limit. */
    private static final String FRAG = String.join("", FRAG0, FRAG1, FRAG2);

    private static final GlState GL = new GlState();
    private static final KawaseChain CHAIN = new KawaseChain();
    private static int prog, vao, fbo, readFbo, tmpTex, tmpW, tmpH;
    private static boolean failed;
    private static final long START = System.nanoTime();

    private WorldStyleFx() {
    }

    public static void draw() {
        if (mode == 0 || failed) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        var fb = mc.getFramebuffer();
        if (!(fb.getColorAttachment() instanceof GlTexture main) || main.isClosed()) return;
        if (!(fb.getDepthAttachment() instanceof GlTexture depth) || depth.isClosed()) return;
        int w = main.getWidth(0), h = main.getHeight(0);
        GL.save();
        try {
            if (prog == 0) {
                prog = Glass.program(VERT, FRAG);
                if (prog == 0 || !CHAIN.init()) {
                    failed = true;
                    return;
                }
                vao = GL30.glGenVertexArrays();
                fbo = GL30.glGenFramebuffers();
                readFbo = GL30.glGenFramebuffers();
            }
            if (tmpTex == 0 || tmpW != w || tmpH != h) {
                if (tmpTex != 0) GL11.glDeleteTextures(tmpTex);
                tmpTex = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tmpTex);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
                tmpW = w;
                tmpH = h;
            }
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColorMask(true, true, true, true);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            for (int u = 0; u < 4; u++) GL33.glBindSampler(u, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            // copy the scene and blur it (bloom, frost, rough reflections and several styles use the blur)
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.getGlId(), 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tmpTex, 0);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            int blur = CHAIN.blur(tmpTex, w, h, 3, 1.3f);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.getGlId(), 0);
            GL11.glViewport(0, 0, w, h);
            GL30.glBindVertexArray(vao);
            GL20.glUseProgram(prog);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth.getGlId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL14.GL_TEXTURE_COMPARE_MODE, GL11.GL_NONE);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, blur);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tmpTex);
            u1i("uScene", 0);
            u1i("uBlur", 1);
            u1i("uDepth", 2);
            Matrix4f vp = new Matrix4f(Render3D.lastProjMat).mul(Render3D.lastModMat).mul(Render3D.lastWorldSpaceMatrix);
            Matrix4f inv = new Matrix4f(vp).invert();
            try (MemoryStack st = MemoryStack.stackPush()) {
                FloatBuffer m = st.mallocFloat(16);
                inv.get(m);
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(prog, "uInv"), false, m);
                vp.get(m);
                GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(prog, "uVP"), false, m);
            }
            Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uCamFrac"), (float) (cam.x - Math.floor(cam.x)), (float) (cam.y - Math.floor(cam.y)), (float) (cam.z - Math.floor(cam.z)));
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uCamCell"), (float) Math.floorMod((long) Math.floor(cam.x), 4096L), (float) Math.floor(cam.y), (float) Math.floorMod((long) Math.floor(cam.z), 4096L));
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uCol"), ((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f);
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uCol2"), ((color2 >> 16) & 255) / 255f, ((color2 >> 8) & 255) / 255f, (color2 & 255) / 255f);
            GL20.glUniform2f(GL20.glGetUniformLocation(prog, "uTexel"), 1f / w, 1f / h);
            // wrap time so the sin() hashes keep their precision in long sessions
            u1f("uTime", ((System.nanoTime() - START) / 1e9f) % 3600f);
            u1f("uInt", intensity);
            u1f("uEdge", edges);
            u1f("uGrid", grid);
            u1f("uScan", scan);
            u1f("uGlint", glints);
            u1f("uPart", particles);
            u1f("uRefl", reflStrength * reflFade);
            u1f("uRough", roughness);
            u1f("uWind", wind);
            u1f("uPix", pixel);
            u1f("uLens", lens);
            u1i("uReflOn", reflection ? 1 : 0);
            u1i("uMode", mode);
            u1i("uChroma", chroma ? 1 : 0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("world style failed - disabled", t);
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

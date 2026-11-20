package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.GlTexture;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import tenacity.Tenacity;

/**
 * "Sky Visuals": a separate procedural sky pass drawn like Shader Sky (where the depth buffer is empty, before our 3D
 * visuals) - themed gradient, sun / moon discs with halos, fluffy lit clouds, hot-air balloons, stars with constellation
 * lines, shooting stars, aurora curtains and a horizon fog tint over the terrain. Everything is a function of the
 * world-space view ray, so it stays fixed in the sky.
 */
public final class SkyVisualsFx {
    /** Settings written by the module every tick (colours are linear-ish 0..1 rgb). */
    public static boolean on;
    public static final float[] zenith = {0.22f, 0.45f, 0.85f}, horizon = {0.68f, 0.82f, 0.96f}, glow = {1f, 0.9f, 0.7f}, sunColor = {1f, 0.95f, 0.85f}, tint = {1f, 1f, 1f};
    public static final float[] sunDir = {0.5f, 0.6f, -0.3f}, moonDir = {-0.5f, 0.4f, 0.3f};
    public static float night, dusk, tintAmount, aurora;
    public static float cloudSpeed = 1f, cloudCover = 0.5f, balloons = 5f, balloonSpeed = 1f, shooting = 1f, halo = 1f, horizonGlow = 1f, fog = 0.4f, speed = 1f;
    public static boolean clouds = true, showBalloons = true, showShooting = true, sunMoon = true, stars = true, constellations = true;
    public static int segCount;
    public static final float[] segA = new float[MAX_SEGS() * 3], segB = new float[MAX_SEGS() * 3];

    public static int MAX_SEGS() {
        return 48;
    }

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
            uniform sampler2D uDepth;
            uniform mat4 uInvViewProj;
            uniform float uTime;
            uniform vec3 uZenith, uHorizon, uGlow, uSunCol, uTint, uSun, uMoon;
            uniform float uNight, uDusk, uTintAmt, uAurora;
            uniform float uCloudSpeed, uCover, uBalloons, uBalloonSpeed, uShoot, uHalo, uHGlow, uFog;
            uniform float uClouds, uShowBalloons, uShowShoot, uSunMoon, uStars, uConst;
            uniform int uSegCount;
            uniform vec3 uSegA[48];
            uniform vec3 uSegB[48];

            const float PI = 3.14159265;

            float h13(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yzx + 33.33); return fract((p.x + p.y) * p.z); }
            vec3 h33(vec3 p) { p = fract(p * vec3(0.1031, 0.1030, 0.0973)); p += dot(p, p.yxz + 33.33); return fract((p.xxy + p.yxx) * p.zyx); }
            float noise(vec3 p) {
                vec3 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(h13(i), h13(i + vec3(1, 0, 0)), f.x), mix(h13(i + vec3(0, 1, 0)), h13(i + vec3(1, 1, 0)), f.x), f.y),
                           mix(mix(h13(i + vec3(0, 0, 1)), h13(i + vec3(1, 0, 1)), f.x), mix(h13(i + vec3(0, 1, 1)), h13(i + vec3(1, 1, 1)), f.x), f.y), f.z);
            }
            float fbm(vec3 p) {
                float v = 0.0, a = 0.5;
                for (int i = 0; i < 5; i++) { v += a * noise(p); p = p * 2.03 + vec3(1.7, 9.2, 3.1); a *= 0.5; }
                return v;
            }
            void basis(vec3 n, out vec3 tx, out vec3 ty) {
                vec3 up = abs(n.y) < 0.97 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                tx = normalize(cross(up, n));
                ty = cross(n, tx);
            }
            vec2 local2(vec3 d, vec3 n, float r) {
                vec3 tx, ty; basis(n, tx, ty);
                return vec2(dot(d, tx), dot(d, ty)) / max(dot(d, n), 1e-3) / r;
            }

            /* ------------------------------------------------------------ gradient + atmosphere */
            vec3 baseSky(vec3 d) {
                float y = d.y;
                float h = pow(1.0 - clamp(y, 0.0, 1.0), 3.0);
                vec3 c = mix(uZenith, uHorizon, h);
                vec3 sh = normalize(vec3(uSun.x, 0.0, uSun.z) + 1e-4), dh = normalize(vec3(d.x, 0.0, d.z) + 1e-4);
                float toward = 0.5 + 0.5 * dot(sh, dh);
                float band = exp(-abs(y) * 7.0) * uHGlow;
                c += uGlow * band * (0.12 + 0.55 * uDusk) * pow(toward, 2.0);
                float sd = max(dot(d, uSun), 0.0);
                c += uGlow * pow(sd, 8.0) * (0.15 + 0.45 * uDusk) * (1.0 - uNight);
                // below the horizon: a soft darker haze
                c = mix(c, uHorizon * 0.55 + uZenith * 0.1, smoothstep(0.0, -0.35, y));
                return c;
            }

            /* ------------------------------------------------------------ stars + constellations */
            vec3 starLayer(vec3 d, float n, float dens, float t) {
                vec3 g = d * n; vec3 c = floor(g); vec3 h = h33(c);
                if (h.x > dens) return vec3(0.0);
                vec3 p = c + 0.25 + 0.5 * h.yzx;
                float r = length(g - p);
                float sz = max(0.06 + 0.1 * h.y * h.y, n * 0.0009);
                float b = exp(-r * r / (sz * sz)) * (0.35 + 1.4 * pow(h.z, 3.0));
                b *= 0.7 + 0.3 * sin(t * (0.8 + 2.5 * h.y) + h.x * 60.0);
                return mix(vec3(0.7, 0.8, 1.0), vec3(1.0, 0.88, 0.7), h.z) * b;
            }
            vec3 starField(vec3 d, float t) {
                return starLayer(d, 300.0, 0.32, t) * 0.8 + starLayer(d, 150.0, 0.12, t * 0.7) * 1.2 + starLayer(d, 70.0, 0.04, t * 0.5) * 1.7;
            }
            vec3 constellation(vec3 d, float t) {
                vec3 c = vec3(0.0);
                for (int i = 0; i < 48; i++) {
                    if (i >= uSegCount) break;
                    vec3 a = uSegA[i], b = uSegB[i];
                    vec3 n = normalize(cross(a, b));
                    float dist = abs(dot(d, n));
                    float inArc = step(0.0, dot(cross(a, d), n)) * step(0.0, dot(cross(d, b), n)) * step(0.0, dot(d, a + b));
                    float w = fwidth(dot(d, n)) * 1.2 + 1e-5;
                    c += vec3(0.75, 0.85, 1.0) * (1.0 - smoothstep(0.0, w, dist)) * inArc * 0.32;
                    float ea = 1.0 - dot(d, a), eb = 1.0 - dot(d, b);
                    c += vec3(1.0, 0.97, 0.9) * (exp(-ea * 9e5) + exp(-eb * 9e5)) * (0.8 + 0.2 * sin(t * 2.0 + float(i)));
                    c += vec3(0.6, 0.75, 1.0) * (exp(-ea * 9e4) + exp(-eb * 9e4)) * 0.15;
                }
                return c;
            }

            /* ------------------------------------------------------------ shooting stars */
            vec3 shootingStars(vec3 d, float t) {
                vec3 c = vec3(0.0);
                for (int i = 0; i < 3; i++) {
                    float per = 9.0 / max(uShoot, 0.05);
                    float tt = t / per + float(i) * 0.371;
                    float id = floor(tt), f = fract(tt);
                    if (f > 0.16) continue;
                    float p = f / 0.16;
                    vec3 h = h33(vec3(id, float(i) * 3.7, 7.1));
                    float el = 0.35 + 0.5 * h.y, az = h.x * 6.2831;
                    vec3 cen = normalize(vec3(cos(az) * cos(el), sin(el), sin(az) * cos(el)));
                    // wide gate: the old 0.8 cone cut the meteor off mid-fall once its head travelled away from the
                    // fixed centre, so it blinked out instead of fading. The sin(p*PI) fade already ends it smoothly.
                    if (dot(d, cen) < 0.5) continue;
                    vec2 q = local2(d, cen, 1.0);
                    float ang = -0.6 - h.z * 1.9;
                    vec2 v = vec2(cos(ang), sin(ang));
                    vec2 head = v * (p * 0.55 - 0.27);
                    vec2 tail = head - v * 0.16;
                    vec2 pa = q - tail, ba = head - tail;
                    float k = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                    float dd = length(pa - ba * k);
                    float wd = fwidth(q.x) * 1.4;
                    float fade = sin(p * PI);
                    c += vec3(0.85, 0.92, 1.0) * (1.0 - smoothstep(0.0, wd, dd)) * k * k * fade * 1.4;
                    c += vec3(0.6, 0.75, 1.0) * exp(-dd * dd / (wd * wd * 16.0)) * k * k * fade * 0.25;
                }
                return c;
            }

            /* ------------------------------------------------------------ sun + moon */
            vec3 sunMoon(vec3 d, inout vec3 col) {
                float sd = dot(d, uSun);
                float ang = acos(clamp(sd, -1.0, 1.0));
                float aa = fwidth(ang) + 1e-5;
                float sunVis = smoothstep(-0.12, 0.02, uSun.y);
                vec3 add = vec3(0.0);
                add += uSunCol * (exp(-ang * 14.0) * 0.45 + exp(-ang * 4.0) * 0.12) * uHalo * sunVis;
                float disc = 1.0 - smoothstep(0.034 - aa, 0.034 + aa, ang);
                vec3 sunDisc = mix(uSunCol, vec3(1.0), 0.55) * (1.0 + 0.25 * (1.0 - ang / 0.034));
                col = mix(col, sunDisc * 1.15, disc * sunVis);

                float md = dot(d, uMoon);
                float ma = acos(clamp(md, -1.0, 1.0));
                float mvis = smoothstep(-0.08, 0.05, uMoon.y) * (0.35 + 0.65 * uNight);
                add += vec3(0.75, 0.82, 1.0) * (exp(-ma * 18.0) * 0.28 + exp(-ma * 5.0) * 0.06) * uHalo * mvis;
                if (md > 0.99) {
                    vec2 q = local2(d, uMoon, 0.024);
                    float r = length(q);
                    float aq = fwidth(r) + 1e-4;
                    float m = 1.0 - smoothstep(1.0 - aq, 1.0 + aq, r);
                    float shadow = smoothstep(1.0 - aq * 2.0, 1.0 + aq * 2.0, length(q - vec2(0.55, 0.18)));
                    float crater = fbm(vec3(q * 2.6, 3.0));
                    vec3 mc = vec3(0.93, 0.94, 0.98) * (0.78 + 0.3 * crater) * mix(0.10, 1.0, shadow);
                    col = mix(col, mc + col * (1.0 - shadow) * 0.4, m * mvis);
                }
                return add;
            }

            /* ------------------------------------------------------------ clouds */
            vec4 cloudLayer(vec3 d, float t) {
                if (d.y <= 0.0) return vec4(0.0);
                vec2 uv = d.xz / (d.y + 0.12) * 0.55 + vec2(t * 0.012, t * 0.004) * uCloudSpeed;
                vec3 p = vec3(uv, t * 0.004 * uCloudSpeed);
                float n = fbm(p);
                float thr = 1.0 - uCover * 0.62 - 0.12;
                float dens = smoothstep(thr, thr + 0.22, n);
                if (dens <= 0.001) return vec4(0.0);
                vec2 sl = normalize(uSun.xz + 1e-4) * 0.09;
                float n2 = fbm(p + vec3(sl, 0.0));
                float lit = clamp(0.55 + (n - n2) * 4.0, 0.0, 1.0);
                vec3 dayLit = mix(vec3(1.0), uGlow, 0.25 * uDusk + 0.1);
                vec3 litCol = mix(dayLit, uGlow * 1.1 + vec3(0.12), uDusk * 0.6);
                litCol = mix(litCol, uHorizon * 1.6 + vec3(0.06), uNight * 0.85);
                vec3 shadeCol = mix(uZenith, uHorizon, 0.55) * mix(0.82, 0.55, uNight);
                vec3 c = mix(shadeCol, litCol, lit);
                float sd = max(dot(d, uSun), 0.0);
                c += uGlow * pow(sd, 10.0) * (1.0 - dens) * 0.9 * (1.0 - uNight);
                float fade = smoothstep(0.0, 0.18, d.y);
                return vec4(c, dens * fade * 0.94);
            }

            /* ------------------------------------------------------------ aurora */
            vec3 auroraCurtain(vec3 d, float t) {
                if (d.y <= 0.0) return vec3(0.0);
                vec3 c = vec3(0.0);
                for (int i = 0; i < 3; i++) {
                    float hgt = 0.25 + float(i) * 0.12;
                    vec2 uv = d.xz / (d.y + hgt) * 0.6;
                    float wave = uv.y + sin(uv.x * 0.9 + t * 0.12 + float(i)) * 0.5 + (fbm(vec3(uv * 0.7, t * 0.03)) - 0.5) * 1.2;
                    float band = exp(-pow((wave - 1.2 - float(i) * 0.4) * 2.2, 2.0));
                    float streak = 0.55 + 0.45 * noise(vec3(uv.x * 9.0, t * 0.2, float(i)));
                    vec3 col = mix(vec3(0.15, 1.0, 0.55), vec3(0.6, 0.3, 1.0), clamp(d.y * 1.6 + float(i) * 0.2, 0.0, 1.0));
                    c += col * band * streak * 0.5 * smoothstep(0.02, 0.25, d.y);
                }
                return c;
            }

            /* ------------------------------------------------------------ hot-air balloons */
            vec3 pastel(float h) { return 0.62 + 0.38 * cos(6.2831 * (h + vec3(0.0, 0.33, 0.67))); }
            vec4 balloon(vec3 d, float t, int i) {
                vec3 h = h33(vec3(float(i) * 1.73, 3.1, 9.2));
                float az = h.x * 6.2831 + t * uBalloonSpeed * 0.006 * (0.6 + h.y);
                float el = 0.05 + 0.28 * h.y + 0.012 * sin(t * 0.25 + float(i) * 2.0);
                vec3 cen = normalize(vec3(cos(az) * cos(el), sin(el), sin(az) * cos(el)));
                float r = 0.016 + 0.024 * h.z;
                if (dot(d, cen) < 1.0 - 40.0 * r * r) return vec4(0.0);
                vec2 q = local2(d, cen, r);
                float px = fwidth(q.x) * 1.0 + 1e-4;
                vec3 colA = pastel(h.x + h.z * 0.5), colB = mix(vec3(1.0), pastel(h.y), 0.25);
                vec3 tx, ty; basis(cen, tx, ty);
                vec2 L = normalize(vec2(dot(uSun, tx), dot(uSun, ty)) + 1e-4);
                vec4 o = vec4(0.0);
                // envelope: sphere top tapering into the mouth
                float y = q.y;
                float w = y > -0.35 ? sqrt(max(0.0, 1.0 - (y - 0.1) * (y - 0.1))) : mix(0.22, 0.894, (y + 1.0) / 0.65);
                float inside = (1.0 - smoothstep(-px, px, abs(q.x) - w)) * (1.0 - smoothstep(-px, px, y - 1.1)) * (1.0 - smoothstep(-px, px, -1.0 - y));
                if (inside > 0.0) {
                    float u = clamp(q.x / max(w, 0.02), -1.0, 1.0);
                    float g = asin(u) / PI + 0.5;
                    float stripe = step(0.5, fract(g * 3.0));
                    vec3 c = mix(colA, colB, stripe);
                    float nz = sqrt(max(0.0, 1.0 - u * u));
                    float shade = clamp(0.55 + 0.45 * (u * L.x * 0.8 + (y - 0.1) * L.y * 0.5 + nz * 0.45), 0.25, 1.15);
                    float dayK = mix(1.0, 0.35, uNight);
                    c *= shade * dayK;
                    c += uGlow * pow(max(0.0, -u * L.x), 6.0) * 0.18 * uDusk;
                    c += vec3(1.0) * pow(max(0.0, 1.0 - length(q - vec2(-0.35 * L.x - 0.2, 0.55)) / 0.35), 2.0) * 0.18 * dayK;
                    o = vec4(c, inside);
                }
                // ropes
                for (int s = -1; s <= 1; s += 2) {
                    vec2 a = vec2(0.22 * float(s), -1.0), b = vec2(0.14 * float(s), -1.22);
                    vec2 pa = q - a, ba = b - a;
                    float k = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
                    float rope = 1.0 - smoothstep(px * 0.3, px * 1.3, length(pa - ba * k));
                    o = mix(o, vec4(vec3(0.25, 0.2, 0.17) * mix(1.0, 0.4, uNight), 1.0), rope * 0.8);
                }
                // basket
                vec2 bq = abs(q - vec2(0.0, -1.33)) - vec2(0.15, 0.11);
                float bd = max(bq.x, bq.y);
                float basket = 1.0 - smoothstep(-px, px, bd);
                vec3 bc = vec3(0.52, 0.34, 0.2) * (0.75 + 0.25 * step(0.5, fract((q.y + 1.33) * 18.0))) * mix(1.0, 0.35, uNight);
                o = mix(o, vec4(bc, 1.0), basket);
                // burner glow
                float flick = 0.7 + 0.3 * sin(t * 13.0 + float(i) * 5.0) * sin(t * 7.3 + float(i));
                vec3 burn = vec3(1.0, 0.6, 0.25) * exp(-dot(q - vec2(0.0, -1.0), q - vec2(0.0, -1.0)) * 25.0) * flick * (0.25 + 0.9 * uNight);
                o.rgb += burn * max(o.a, 0.3);
                o.a = max(o.a, length(burn) * 0.5);
                // aerial perspective: far / small balloons fade into the haze
                o.rgb = mix(o.rgb, mix(uHorizon, uZenith, 0.3), (1.0 - h.z) * 0.32);
                return o;
            }

            float dither() { return (h13(vec3(gl_FragCoord.xy, fract(uTime) * 61.0)) - 0.5) / 255.0; }

            void main() {
                float depth = texture(uDepth, vUv).r;
                vec4 far = uInvViewProj * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
                vec4 near = uInvViewProj * vec4(vUv * 2.0 - 1.0, -1.0, 1.0);
                vec3 d = normalize(far.xyz / far.w - near.xyz / near.w);
                float t = uTime;
                if (depth < 0.99999) {
                    if (uFog <= 0.001) discard;
                    vec4 wp = uInvViewProj * vec4(vUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                    float dist = length(wp.xyz / wp.w);
                    float f = uFog * smoothstep(20.0, 280.0, dist) * 0.7;
                    vec3 fc = mix(uHorizon, uGlow, 0.25 * uDusk) * mix(1.0, 0.6, uNight);
                    fc = mix(fc, fc * uTint * 1.4, uTintAmt);
                    fragColor = vec4(fc + dither(), f);
                    return;
                }
                vec3 col = baseSky(d);
                float above = smoothstep(-0.05, 0.12, d.y);
                if (uStars > 0.5) col += starField(d, t) * uNight * above;
                if (uConst > 0.5) col += constellation(d, t) * uNight * above;
                if (uAurora > 0.0) col += auroraCurtain(d, t) * uAurora;
                if (uShowShoot > 0.5) col += shootingStars(d, t) * smoothstep(0.25, 0.7, uNight);
                vec3 halo = vec3(0.0);
                if (uSunMoon > 0.5) halo = sunMoon(d, col);
                col += halo;
                if (uClouds > 0.5) {
                    vec4 cl = cloudLayer(d, t);
                    col = mix(col, cl.rgb + halo * 0.4, cl.a);
                }
                if (uShowBalloons > 0.5) {
                    int n = int(uBalloons + 0.5);
                    for (int i = 0; i < 16; i++) {
                        if (i >= n) break;
                        vec4 b = balloon(d, t, i);
                        col = mix(col, b.rgb, clamp(b.a, 0.0, 1.0));
                    }
                }
                col = mix(col, col * uTint * 1.5, uTintAmt);
                // gentle filmic roll-off so glows never clip harshly
                col = col / (1.0 + max(col - 0.85, 0.0) * 0.6);
                fragColor = vec4(col + dither(), 1.0);
            }
            """;

    private static final GlState GL = new GlState();
    private static int prog, vao, fbo;
    private static boolean failed;
    private static final long START = System.nanoTime();
    private static double animTime, lastNs;

    private SkyVisualsFx() {
    }

    private static boolean setup() {
        if (failed) return false;
        if (prog == 0) {
            prog = Glass.program(VERT, FRAG);
            if (prog == 0) {
                failed = true;
                Tenacity.LOG.error("sky visuals shader failed to compile");
                return false;
            }
            vao = GL30.glGenVertexArrays();
            fbo = GL30.glGenFramebuffers();
        }
        return true;
    }

    private static void u1(String n, float v) {
        GL20.glUniform1f(GL20.glGetUniformLocation(prog, n), v);
    }

    private static void u3(String n, float[] v) {
        GL20.glUniform3f(GL20.glGetUniformLocation(prog, n), v[0], v[1], v[2]);
    }

    public static void draw() {
        if (!on || !setup() || !tenacity.util.Projection.ready()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.getFramebuffer().getColorAttachment() instanceof GlTexture color) || color.isClosed()) return;
        if (!(mc.getFramebuffer().getDepthAttachment() instanceof GlTexture depth) || depth.isClosed()) return;
        long ns = System.nanoTime();
        if (lastNs != 0) animTime += Math.min(0.25, (ns - lastNs) / 1e9) * speed;
        lastNs = ns;
        GL.save();
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL33.glBindSampler(0, 0);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color.getGlId(), 0);
            GL11.glViewport(0, 0, color.getWidth(0), color.getHeight(0));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth.getGlId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, 0x884C /* COMPARE_MODE */, GL11.GL_NONE);
            GL20.glUseProgram(prog);
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uDepth"), 0);
            Matrix4f inv = tenacity.util.Projection.inverseViewProj();
            float[] m = new float[16];
            inv.get(m);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(prog, "uInvViewProj"), false, m);
            u1("uTime", (float) (animTime % 100000.0));
            u3("uZenith", zenith);
            u3("uHorizon", horizon);
            u3("uGlow", glow);
            u3("uSunCol", sunColor);
            u3("uTint", tint);
            u3("uSun", sunDir);
            u3("uMoon", moonDir);
            u1("uNight", night);
            u1("uDusk", dusk);
            u1("uTintAmt", tintAmount);
            u1("uAurora", aurora);
            u1("uCloudSpeed", cloudSpeed);
            u1("uCover", cloudCover);
            u1("uBalloons", balloons);
            u1("uBalloonSpeed", balloonSpeed);
            u1("uShoot", shooting);
            u1("uHalo", halo);
            u1("uHGlow", horizonGlow);
            u1("uFog", fog);
            u1("uClouds", clouds ? 1 : 0);
            u1("uShowBalloons", showBalloons ? 1 : 0);
            u1("uShowShoot", showShooting ? 1 : 0);
            u1("uSunMoon", sunMoon ? 1 : 0);
            u1("uStars", stars ? 1 : 0);
            u1("uConst", constellations ? 1 : 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uSegCount"), constellations ? segCount : 0);
            GL20.glUniform3fv(GL20.glGetUniformLocation(prog, "uSegA"), segA);
            GL20.glUniform3fv(GL20.glGetUniformLocation(prog, "uSegB"), segB);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("sky visuals failed - disabled", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }
}

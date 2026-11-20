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
 * "Music Visualiser" sky pass: additive, direction-based (fixed in the sky) neon spectrum waves, horizon equalizer arc,
 * beat pulse rings, music-driven aurora, spectrum ribbon and beat particles. Drawn after the sky passes, only where
 * the depth buffer is empty, fed by a 48-band uniform array plus level / bass / beat uniforms from the module.
 */
public final class MusicVisFx {
    public static final int BANDS = 48;
    public static boolean on;
    public static final float[] bands = new float[BANDS];
    public static final float[] primary = {0.75f, 0.3f, 1f}, secondary = {1f, 0.3f, 0.7f};
    /** Ages (seconds) of the last 4 beat rings; large = inactive. */
    public static final float[] ringAge = {99, 99, 99, 99};
    public static float level, bass, treble, beat, activity, time;
    public static float height = 1f, speed = 1f, thickness = 1f, glow = 1f, skyHeight = 0.18f, opacity = 1f;
    public static int waveCount = 4, palette; // 0 two-colour, 1 rainbow
    public static boolean gradient = true, waves = true, eq, rings, aurora, ribbon, particles = true;

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
            uniform float uTime, uLevel, uBass, uTreble, uBeat, uAct;
            uniform float uBands[48];
            uniform float uRing[4];
            uniform vec3 uCol1, uCol2;
            uniform float uHeight, uSpeed, uThick, uGlow, uSkyH, uOpacity, uWaveCount, uPalette, uGradient;
            uniform float uWaves, uEq, uRings, uAurora, uRibbon, uParticles;

            const float PI = 3.14159265, TAU = 6.2831853;

            float h11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
            float h12(vec2 p) { vec3 q = fract(vec3(p.xyx) * 0.1031); q += dot(q, q.yzx + 33.33); return fract((q.x + q.y) * q.z); }
            float vnoise(vec2 p) { vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
                return mix(mix(h12(i), h12(i + vec2(1, 0)), f.x), mix(h12(i + vec2(0, 1)), h12(i + vec2(1, 1)), f.x), f.y); }

            /* spectrum lookup, x in 0..1, linear between bands */
            float band(float x) {
                float f = clamp(x, 0.0, 1.0) * 47.0;
                int i = int(floor(f));
                int j = min(i + 1, 47);
                return mix(uBands[i], uBands[j], fract(f));
            }
            vec3 hue(float h) { return 0.5 + 0.5 * cos(TAU * (h + vec3(0.0, 0.33, 0.67))); }
            vec3 pal(float x) {
                if (uPalette > 0.5) return hue(x + uTime * 0.03) * 1.1;
                return uGradient > 0.5 ? mix(uCol1, uCol2, smoothstep(0.0, 1.0, x)) : uCol1;
            }

            void main() {
                float depth = texture(uDepth, vUv).r;
                if (depth < 0.99999) discard;
                vec4 far = uInvViewProj * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
                vec4 near = uInvViewProj * vec4(vUv * 2.0 - 1.0, -1.0, 1.0);
                vec3 d = normalize(far.xyz / far.w - near.xyz / near.w);
                float t = uTime;
                float az = atan(d.z, d.x);           // -PI..PI around the horizon
                float u = az / TAU + 0.5;            // 0..1
                float sx = abs(fract(u * 2.0) * 2.0 - 1.0); // mirrored 0..1..0 twice round -> seamless spectrum
                float el = asin(clamp(d.y, -1.0, 1.0)); // elevation (radians)
                float px = max(fwidth(el), 1e-4);
                vec3 col = vec3(0.0);
                float act = uAct;
                float pulse = 1.0 + uBeat * 0.6 * act;

                /* ---------------- neon waves */
                if (uWaves > 0.5) {
                    int n = int(uWaveCount + 0.5);
                    for (int i = 0; i < 8; i++) {
                        if (i >= n) break;
                        float fi = float(i), k = fi / max(float(n - 1), 1.0);
                        float sp = (0.35 + 0.18 * fi) * uSpeed * (mod(fi, 2.0) < 0.5 ? 1.0 : -1.0);
                        float freq = 3.0 + fi * 2.0;
                        float bx = fract(sx * 0.85 + k * 0.15);
                        float b = band(bx) * act;
                        float amp = (0.025 + 0.16 * b + 0.05 * uBass * act) * uHeight * pulse;
                        float y = uSkyH + 0.06 * fi * uHeight
                                + amp * sin(az * freq + t * sp + fi * 1.7)
                                + amp * 0.45 * sin(az * (freq * 2.3) - t * sp * 1.6 + fi)
                                + 0.012 * uHeight * (vnoise(vec2(az * 4.0 + t * 0.4, fi * 3.1)) - 0.5);
                        float dist = abs(el - y);
                        float w = 0.0025 * uThick * (1.0 + 1.5 * b);
                        float core = exp(-dist * dist / (w * w + px * px));
                        float halo = exp(-dist / (w * 7.0 + px)) * 0.35 * uGlow;
                        vec3 c = pal(uPalette > 0.5 ? u + k * 0.3 : (uGradient > 0.5 ? sx * 0.6 + k * 0.4 : k));
                        col += c * (core * 1.4 + halo) * (0.45 + 0.9 * b + 0.4 * uBeat * act);
                    }
                }

                /* ---------------- spectrum ribbon: filled translucent band between two spectrum curves */
                if (uRibbon > 0.5) {
                    float b = band(sx) * act;
                    float y0 = uSkyH + 0.25 * uHeight + 0.02 * sin(az * 2.0 + t * 0.3 * uSpeed);
                    float top = y0 + (0.01 + 0.22 * b) * uHeight * pulse, bot = y0 - (0.01 + 0.1 * b) * uHeight;
                    float inside = smoothstep(bot - px, bot + px, el) * (1.0 - smoothstep(top - px, top + px, el));
                    float edge = exp(-abs(el - top) / (0.003 * uThick + px)) + exp(-abs(el - bot) / (0.003 * uThick + px)) * 0.5;
                    float shimmer = 0.6 + 0.4 * sin(el * 300.0 - t * 4.0 * uSpeed);
                    vec3 c = pal(sx);
                    col += c * (inside * 0.22 * shimmer + edge * 0.9) * (0.4 + b);
                    col += c * exp(-max(el - top, 0.0) / (0.03 * uGlow + 1e-3)) * step(bot, el) * 0.12 * uGlow * b;
                }

                /* ---------------- equalizer bars arched along the horizon */
                if (uEq > 0.5) {
                    float bars = 96.0;
                    float gi = floor(u * bars), gf = fract(u * bars);
                    float gx = abs(fract((gi + 0.5) / bars * 2.0) * 2.0 - 1.0);
                    float b = band(gx) * act;
                    float base = uSkyH - 0.06;
                    float hgt = (0.008 + 0.3 * b * b + 0.1 * b) * uHeight * pulse;
                    float pxu = fwidth(u * bars) + 1e-4;
                    float xm = smoothstep(0.12 - pxu, 0.12 + pxu, gf) * (1.0 - smoothstep(0.88 - pxu, 0.88 + pxu, gf));
                    float ym = smoothstep(base - px, base + px, el) * (1.0 - smoothstep(base + hgt - px, base + hgt + px, el));
                    float rel = clamp((el - base) / max(hgt, 1e-3), 0.0, 1.0);
                    vec3 c = pal(uPalette > 0.5 ? gi / bars : rel * 0.8 + gx * 0.2);
                    col += c * xm * ym * (0.35 + 0.8 * rel) * 0.9;
                    float cap = exp(-abs(el - base - hgt) / (0.004 + px)) * xm;
                    col += c * cap * (0.6 + uGlow * 0.6);
                    // reflection-ish glow under the arc
                    col += c * exp(-abs(el - base) / (0.02 * uGlow + 1e-3)) * b * 0.15 * xm;
                }

                /* ---------------- aurora curtains driven by the music */
                if (uAurora > 0.5 && d.y > 0.0) {
                    vec3 ac = vec3(0.0);
                    for (int i = 0; i < 3; i++) {
                        float fi = float(i);
                        float hgt = 0.25 + fi * 0.12;
                        vec2 uv = d.xz / (d.y + hgt) * 0.6;
                        float wave = uv.y + sin(uv.x * 0.9 + t * 0.15 * uSpeed + fi) * (0.5 + 0.6 * uBass * act) + (vnoise(uv * 0.9 + t * 0.05) - 0.5) * 1.2;
                        float bw = 2.2 / (0.7 + 0.8 * uLevel * act);
                        float bnd = exp(-pow((wave - 1.2 - fi * 0.4) * bw, 2.0));
                        float bx = fract(uv.x * 0.08 + 0.5);
                        float streak = 0.4 + 0.6 * vnoise(vec2(uv.x * 9.0, t * 0.3 + fi)) * (0.5 + band(bx) * act);
                        ac += pal(clamp(d.y * 1.4 + fi * 0.2, 0.0, 1.0)) * bnd * streak;
                    }
                    col += ac * (0.25 + 0.9 * uLevel * act + 0.4 * uBeat * act) * smoothstep(0.02, 0.25, d.y) * 0.6;
                }

                /* ---------------- pulse rings expanding from the zenith on each beat */
                if (uRings > 0.5) {
                    float zen = acos(clamp(d.y, -1.0, 1.0)); // angle from straight up
                    for (int i = 0; i < 4; i++) {
                        float a = uRing[i];
                        if (a > 3.0) continue;
                        float r = a * 0.55 * uSpeed + 0.05;
                        float fade = exp(-a * 1.3) * act;
                        float dd = abs(zen - r);
                        float w = 0.004 * uThick + px;
                        col += pal(fract(r * 0.6 + float(i) * 0.25)) * (exp(-dd * dd / (w * w)) * 1.2 + exp(-dd / (w * 8.0)) * 0.3 * uGlow) * fade;
                    }
                }

                /* ---------------- particles: drifting sparks near the waves, flaring + bursting on beats */
                if (uParticles > 0.5) {
                    vec2 g = vec2(u * 220.0, el * 70.0 - t * 0.6 * uSpeed);
                    vec2 c = floor(g), f = fract(g);
                    float r = h12(c);
                    if (r > 0.86) {
                        vec2 p = vec2(h12(c + 7.1), h12(c + 3.3)) * 0.6 + 0.2;
                        float dd = length((f - p) * vec2(1.0, 0.7));
                        float sz = 0.06 + 0.1 * h12(c + 1.9);
                        float zone = exp(-pow((el - uSkyH - 0.1 * uHeight) / (0.25 * uHeight + 0.05), 2.0));
                        float tw = 0.5 + 0.5 * sin(t * (2.0 + 4.0 * r) + r * 40.0);
                        float b = band(abs(fract(c.x / 220.0 * 2.0) * 2.0 - 1.0)) * act;
                        col += pal(r) * exp(-dd * dd / (sz * sz)) * zone * tw * (0.15 + 1.2 * b + 1.4 * uBeat * act);
                    }
                    // beat burst: sparks flung outward from the zenith rings
                    float a0 = uRing[0];
                    if (a0 < 1.6) {
                        float zen = acos(clamp(d.y, -1.0, 1.0));
                        for (int i = 0; i < 24; i++) {
                            float fi = float(i);
                            float ang = (fi + h11(fi + floor(uTime))) / 24.0 * TAU;
                            float rr = 0.05 + a0 * (0.4 + 0.5 * h11(fi * 3.1)) * uSpeed;
                            vec3 sp = vec3(sin(rr) * cos(ang), cos(rr), sin(rr) * sin(ang));
                            float dd = 1.0 - dot(d, sp);
                            col += pal(fi / 24.0) * exp(-dd * 2.2e4) * exp(-a0 * 2.2) * 2.0 * act;
                        }
                    }
                }

                col *= uOpacity * (0.6 + 0.4 * uGlow);
                col = col / (1.0 + col * 0.35); // soft roll-off, no harsh clipping
                col += (h12(gl_FragCoord.xy + fract(t) * 91.0) - 0.5) / 255.0;
                fragColor = vec4(max(col, 0.0), 1.0);
            }
            """;

    private static final GlState GL = new GlState();
    private static int prog, vao, fbo;
    private static boolean failed;

    private MusicVisFx() {
    }

    private static boolean setup() {
        if (failed) return false;
        if (prog == 0) {
            prog = Glass.program(VERT, FRAG);
            if (prog == 0) {
                failed = true;
                Tenacity.LOG.error("music visualiser shader failed to compile");
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
        GL.save();
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE); // additive glow
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
            u1("uTime", time % 10000f);
            u1("uLevel", level);
            u1("uBass", bass);
            u1("uTreble", treble);
            u1("uBeat", beat);
            u1("uAct", activity);
            GL20.glUniform1fv(GL20.glGetUniformLocation(prog, "uBands"), bands);
            GL20.glUniform1fv(GL20.glGetUniformLocation(prog, "uRing"), ringAge);
            u3("uCol1", primary);
            u3("uCol2", secondary);
            u1("uHeight", height);
            u1("uSpeed", speed);
            u1("uThick", thickness);
            u1("uGlow", glow);
            u1("uSkyH", skyHeight);
            u1("uOpacity", opacity);
            u1("uWaveCount", waveCount);
            u1("uPalette", palette);
            u1("uGradient", gradient ? 1 : 0);
            u1("uWaves", waves ? 1 : 0);
            u1("uEq", eq ? 1 : 0);
            u1("uRings", rings ? 1 : 0);
            u1("uAurora", aurora ? 1 : 0);
            u1("uRibbon", ribbon ? 1 : 0);
            u1("uParticles", particles ? 1 : 0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("music visualiser failed - disabled", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }
}

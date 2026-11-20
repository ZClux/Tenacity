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

import java.nio.ByteBuffer;

/**
 * Full screen world effects drawn straight into Minecraft's main framebuffer:
 * <ul>
 *   <li>Shader Sky: an animated sky painted where the depth buffer is still empty (behind all terrain), before the hand.</li>
 *   <li>Motion Blur: the previous frame blended into the current one.</li>
 * </ul>
 */
public final class WorldFx {
    /** Settings written by the modules. */
    public static int sky = -1; // -1 off, otherwise the sky mode index
    public static float skySpeed = 1f, skyScale = 1.1f, skyStrength = 1f, skyStars = 0.75f;
    public static boolean skyVanilla, skyCustom;
    /** Sky colours, the same pair the reference client exposes (theme or custom). */
    public static int skyPrimary = 0xFFFFFFFF, skySecondary = 0xFFBFBFBF;
    public static float motionBlur; // 0 off .. 0.9
    /** Seasonal Sky options (sky modes 100+). */
    public static float seasonClouds = 1f, seasonMoon = 1f, seasonParticles = 1f, seasonGlow = 1f, seasonAurora = 1f, seasonFog = 1f, seasonBright = 1f;

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /**
     * Ported from the reference sky shader: every mode is a self contained directional function evaluated on the view ray,
     * so the sky stays fixed to the world and looks the same at any FOV.
     */
    private static final String SKY = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uDepth;
            uniform mat4 uInvViewProj;
            uniform float uTime;
            uniform int uMode;
            uniform vec3 uPrimary;
            uniform vec3 uSecondary;
            uniform vec3 uAccent;
            uniform float uSpeed;
            uniform float uScale;
            uniform float uStrength;
            uniform float uStars;
            uniform float uVanilla;

            float hash(vec3 p) {
                p = fract(p * vec3(123.34, 456.21, 321.79));
                p += dot(p, p.yzx + 45.32);
                return fract((p.x + p.y) * p.z);
            }

            float noise(vec3 p) {
                vec3 i = floor(p);
                vec3 f = fract(p);
                vec3 u = f * f * (3.0 - 2.0 * f);
                float n000 = hash(i + vec3(0.0, 0.0, 0.0));
                float n100 = hash(i + vec3(1.0, 0.0, 0.0));
                float n010 = hash(i + vec3(0.0, 1.0, 0.0));
                float n110 = hash(i + vec3(1.0, 1.0, 0.0));
                float n001 = hash(i + vec3(0.0, 0.0, 1.0));
                float n101 = hash(i + vec3(1.0, 0.0, 1.0));
                float n011 = hash(i + vec3(0.0, 1.0, 1.0));
                float n111 = hash(i + vec3(1.0, 1.0, 1.0));
                float nx00 = mix(n000, n100, u.x);
                float nx10 = mix(n010, n110, u.x);
                float nx01 = mix(n001, n101, u.x);
                float nx11 = mix(n011, n111, u.x);
                return mix(mix(nx00, nx10, u.y), mix(nx01, nx11, u.y), u.z);
            }

            float fbm(vec3 p) {
                float value = 0.0;
                float amp = 0.5;
                for (int i = 0; i < 5; i++) {
                    value += noise(p) * amp;
                    p = mat3(1.53, 0.72, 0.21,
                            -0.64, 1.39, 0.57,
                             0.31,-0.48, 1.71) * p;
                    amp *= 0.5;
                }
                return value;
            }

            vec2 domeUv(vec3 dir) {
                dir = normalize(dir);
                float k = 1.0 / (1.0 + max(dir.y, -0.35));
                return dir.xz * k * 0.55 + 0.5;
            }

            float hash2(vec2 p) {
                p = fract(p * vec2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            float noise2(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                float a = hash2(i);
                float b = hash2(i + vec2(1.0, 0.0));
                float c = hash2(i + vec2(0.0, 1.0));
                float d = hash2(i + vec2(1.0, 1.0));
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }

            float fbm2(vec2 p) {
                float value = 0.0;
                float amp = 0.5;
                for (int i = 0; i < 6; i++) {
                    value += noise2(p) * amp;
                    p = mat2(1.6, 1.2, -1.2, 1.6) * p;
                    amp *= 0.5;
                }
                return value;
            }

            float starField(vec3 dir, float amount, float time) {
                vec3 grid = dir * 130.0;
                vec3 cell = floor(grid);
                vec3 local = fract(grid) - 0.5;
                float h = hash(cell);
                float sparkle = smoothstep(0.997 - amount * 0.045, 1.0, h);
                float shape = smoothstep(0.18, 0.0, length(local));
                return sparkle * shape * (0.65 + 0.35 * sin(time * 2.0 + h * 12.0));
            }

            vec3 aurora(vec3 dir, float time, float scale, float intensity) {
                vec3 p = dir * scale * 2.6;
                float flow = fbm(p + vec3(time * 0.08, -time * 0.03, time * 0.04));
                float bands = 0.0;
                for (int i = 0; i < 4; i++) {
                    float fi = float(i);
                    float center = -0.15 + fi * 0.18 + sin((dir.x + dir.z) * (2.2 + fi) + time * (0.35 + fi * 0.08)) * 0.10;
                    bands += smoothstep(0.18, 0.0, abs(dir.y - center - flow * 0.16));
                }
                vec3 base = mix(uPrimary * 0.16, uSecondary * 0.32, clamp(dir.y * 0.5 + 0.5, 0.0, 1.0));
                vec3 glowCol = mix(uPrimary, uSecondary, flow);
                return base + glowCol * bands * 0.55 * intensity + uAccent * pow(bands, 3.0) * 0.24;
            }

            vec3 night(vec3 dir, float time, float scale, float intensity) {
                vec2 uv = domeUv(dir) * scale;
                vec2 drift = vec2(time * 0.0062379, cos(time * 0.0962379)) * (sin(time * 0.0041839) + 1.1);
                float n1 = fbm2(uv * 2.0 + drift);
                float n2 = fbm2(uv * 6.0 + drift * 1.15 + 12.523);
                float cloud = pow(max(0.8 - n1, 0.0), 2.0);
                float core = pow(max((0.9 - n2) * cloud, 0.0), 1.1);
                vec2 starCell = floor(uv * 420.0);
                float r1 = hash2(starCell);
                float r2 = hash2(starCell.yx + 17.0);
                float twinkle = sin((time + 10.0) * r1 * 2.7) * 0.8 + 0.5;
                float star = r1 * pow(r2, 20.0) * twinkle * 0.9;
                float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 base = mix(uPrimary * 0.08, uSecondary * 0.22, h);
                return base + (uPrimary * cloud * 0.55 + uSecondary * core * 0.95 + uAccent * star) * intensity;
            }

            vec3 snow(vec3 dir, float time, float scale, float intensity) {
                vec2 uv = domeUv(dir) * 2.0 - 1.0;
                float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 base = mix(uPrimary * 0.10, uSecondary * 0.18, h);
                float c = smoothstep(1.0, 0.3, clamp(uv.y * 0.3 + 0.8, 0.0, 0.75));
                for (int i = 0; i < 7; i++) {
                    float fi = float(i);
                    float sc = (6.0 + fi * 2.5) * scale;
                    vec2 p = uv;
                    p += time / sc;
                    p.y += time * 2.0 / sc;
                    p.x += sin(p.y + time * 0.5) / sc;
                    p *= sc;
                    vec2 cell = floor(p);
                    vec2 local = fract(p);
                    vec2 flake = 0.5 + 0.35 * sin(11.0 * fract(sin((cell + sc) * mat2(7.0, 3.0, 6.0, 5.0)) * 5.0)) - local;
                    c += smoothstep(0.035, 0.0, length(flake)) * (0.45 + fi * 0.08);
                }
                return base + mix(uPrimary, uAccent, 0.75) * c * intensity;
            }

            vec3 cloudy(vec3 dir, float time, float scale, float intensity) {
                vec2 p = domeUv(dir) * scale * 1.1;
                float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
                float q = fbm2(p * 0.5);
                float r = 0.0;
                vec2 uv = p - q + time * 0.03;
                float weight = 0.8;
                for (int i = 0; i < 8; i++) {
                    r += abs(weight * noise2(uv));
                    uv = mat2(1.6, 1.2, -1.2, 1.6) * uv + time * 0.03;
                    weight *= 0.7;
                }
                float f = 0.0;
                uv = p - q + time * 0.03;
                weight = 0.2;
                for (int i = 0; i < 8; i++) {
                    f += weight * noise2(uv);
                    uv = mat2(1.6, 1.2, -1.2, 1.6) * uv + time * 0.03;
                    weight *= 0.6;
                }
                float clouds = clamp(0.2 + 8.0 * f * r + r * 0.25, 0.0, 1.0);
                vec3 base = mix(uPrimary * 0.30, uSecondary * 0.50, h);
                vec3 cloud = vec3(1.1, 1.1, 0.9) * mix(uAccent, uSecondary, 0.18);
                return mix(base, cloud, clouds * intensity);
            }

            vec3 starMode(vec3 dir, float time, float scale, float intensity) {
                vec2 uv = (domeUv(dir) - 0.5) * vec2(2.0, 2.0) * scale;
                uv.x += sin(0.2 + uv.y * 0.8) * 0.5;
                uv.x *= 50.0;
                float dx = fract(uv.x);
                uv.x = floor(uv.x);
                uv.y *= 0.15;
                float o = sin(uv.x * 215.4);
                float s = cos(uv.x * 33.1) * 0.3 + 0.7;
                float trail = mix(95.0, 35.0, s);
                float yv = fract(uv.y + time * 0.4 * s + o) * trail;
                yv = 1.0 / max(yv, 0.001);
                yv = smoothstep(0.0, 1.0, yv * yv);
                yv = sin(yv * 3.14159) * (s * 5.0);
                float d2 = sin(dx * 3.14159);
                yv *= d2 * d2;
                float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 base = mix(uPrimary * 0.05, uSecondary * 0.18, h);
                return base + mix(uPrimary, uSecondary, s) * sqrt(max(yv, 0.0)) * intensity;
            }

            vec3 glow(vec3 dir, float time, float scale, float intensity) {
                vec2 uv = (domeUv(dir) - 0.5) * 2.0 * scale;
                for (float i = 1.0; i < 7.0; i++) {
                    uv.x += 0.35 / i * sin(i * 1.7 * uv.y - time * 0.7);
                    uv.y += 0.35 / i * sin(i * 2.3 * uv.x + time * 0.5);
                }
                float rib = 0.09 / max(abs(cos(uv.y * 2.0 + uv.x - time * 0.6)), 0.045);
                float core = smoothstep(0.5, 2.4, rib);
                float halo = smoothstep(0.15, 0.9, rib) - core;
                float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 base = mix(uPrimary * 0.02, uSecondary * 0.06, h);
                vec3 ramp = mix(uSecondary, uPrimary, clamp(rib * 0.5, 0.0, 1.0));
                ramp = mix(ramp, uAccent, core * 0.85);
                return base + (ramp * (rib * 0.4 + halo * 0.35) + uAccent * core * 0.9) * intensity;
            }

            vec3 nebula(vec3 dir, float time, float scale, float intensity) {
                vec2 uv = domeUv(dir) * scale;
                vec2 warp = vec2(fbm2(uv * 1.2 + vec2(time * 0.008, 0.0)),
                                 fbm2(uv * 1.2 + vec2(5.2, 1.3) - vec2(0.0, time * 0.006)));
                float base = fbm2(uv * 1.6 + warp * 1.4 + vec2(0.0, time * 0.004));
                float detail = fbm2(uv * 4.2 + warp * 2.2 - vec2(time * 0.006, 0.0));
                float dens = clamp(base * 1.35 - 0.28 + (detail - 0.5) * 0.55, 0.0, 1.0);
                float lane = (1.0 - smoothstep(0.0, 0.45, abs(detail - 0.5) * 2.0 * dens)) * step(0.02, dens);
                float rim = clamp(dens * (1.0 - dens) * 4.0, 0.0, 1.0);
                float core = pow(clamp(dens * detail * 1.9 - 0.35, 0.0, 1.0), 2.2);
                float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 bg = mix(uPrimary * 0.015, uSecondary * 0.05, h);
                vec3 gas = mix(uSecondary, uPrimary, clamp(dens * 1.3, 0.0, 1.0));
                vec3 col = bg + gas * dens * 0.75 * (1.0 - lane * 0.75);
                col += mix(uPrimary, uAccent, 0.6) * rim * dens * 0.5;
                col += mix(vec3(1.0), uAccent, 0.35) * core * 1.5;
                return col * intensity;
            }

            vec3 storm(vec3 dir, float time, float scale, float intensity) {
                vec2 p = domeUv(dir) * scale * 1.2;
                float q = fbm2(p * 0.6);
                vec2 uv = p - q + vec2(time * 0.05, 0.0);
                float r = 0.0;
                float weight = 0.8;
                for (int i = 0; i < 6; i++) {
                    r += abs(weight * noise2(uv));
                    uv = mat2(1.6, 1.2, -1.2, 1.6) * uv + vec2(time * 0.05, 0.0);
                    weight *= 0.7;
                }
                float veins = pow(clamp(1.0 - r * 0.9, 0.0, 1.0), 3.0);
                float edge = smoothstep(0.0, 0.35, veins) - smoothstep(0.35, 1.0, veins);
                float flash = pow(fbm2(p * 0.4 + vec2(0.0, -time * 0.3)), 5.0);
                float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 base = mix(uPrimary * 0.10, uSecondary * 0.22, h);
                vec3 bolt = mix(uPrimary, uAccent, clamp(veins * 1.5, 0.0, 1.0));
                return base + (bolt * veins * 2.2 + uSecondary * edge * 0.7 + uAccent * flash) * intensity;
            }

            vec3 tide(vec3 dir, float time, float scale, float intensity) {
                vec2 p = domeUv(dir) * scale;
                float q = fbm2(p * 0.5 + vec2(time * 0.02, 0.0));
                vec2 uv = vec2(p.x - q * 1.5 + time * 0.04, p.y * 2.2);
                float f = 0.0;
                float weight = 0.5;
                for (int i = 0; i < 6; i++) {
                    f += weight * noise2(uv);
                    uv = mat2(1.6, 1.2, -1.2, 1.6) * uv;
                    weight *= 0.6;
                }
                float bands = smoothstep(0.32, 0.78, f);
                float crest = smoothstep(0.55, 0.72, f) * (1.0 - smoothstep(0.72, 0.9, f));
                float h = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 base = mix(uSecondary * 0.10, uPrimary * 0.04, h);
                vec3 col = mix(uSecondary * 0.7, mix(uPrimary, uAccent, 0.55), bands);
                return base + col * (0.2 + bands * 0.9) * intensity + uAccent * crest * 0.55 * intensity;
            }

            float hash3(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }

            float noise3(vec3 p) {
                vec3 i = floor(p), f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x), mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y),
                           mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x), mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y), f.z);
            }

            float fbm3(vec3 p) {
                float v = 0.0, a = 0.5;
                for (int i = 0; i < 5; i++) { v += a * noise3(p); p *= 2.03; a *= 0.5; }
                return v;
            }

            vec3 sunset(vec3 d, float t) {
                float up = d.y;
                vec3 sun = normalize(vec3(0.0, 0.08, -1.0));
                vec3 col = mix(vec3(1.0, 0.45, 0.25), vec3(0.18, 0.12, 0.38), smoothstep(-0.05, 0.6, up));
                col = mix(col, vec3(0.05, 0.04, 0.12), smoothstep(0.5, 1.0, up));
                float sd = max(dot(d, sun), 0.0);
                col += vec3(1.0, 0.6, 0.3) * pow(sd, 12.0) * 0.6 + vec3(1.0, 0.85, 0.6) * smoothstep(0.9985, 0.9995, sd);
                float cl = fbm3(vec3(d.xz / max(up + 0.2, 0.05) * 0.8 + vec2(t * 0.01, 0.0), 1.0));
                col = mix(col, vec3(1.0, 0.55, 0.45) * (0.6 + 0.6 * pow(sd, 3.0)), smoothstep(0.55, 0.85, cl) * smoothstep(0.0, 0.3, up) * 0.6);
                return col;
            }
            """ + SkyShaders.FUNCS + SeasonSky.funcs() + """
            void main() {
                float depth = texture(uDepth, vUv).r;
                if (depth < 0.99999) discard;
                vec4 far = uInvViewProj * vec4(vUv * 2.0 - 1.0, 1.0, 1.0);
                vec4 near = uInvViewProj * vec4(vUv * 2.0 - 1.0, -1.0, 1.0);
                vec3 dir = normalize(far.xyz / far.w - near.xyz / near.w);

                float time = uTime * max(uSpeed, 0.01);
                float scale = max(uScale, 0.01);
                float intensity = clamp(uStrength, 0.02, 1.0);
                vec3 color;
                if (uMode >= 100) {
                    color = seasonSky(uMode, dir, time, scale) * uSBright;
                    color = customTint(rolloff(color)) + dither();
                    fragColor = vec4(color, intensity * mix(1.0, 0.72, clamp(uVanilla, 0.0, 1.0)));
                    return;
                }
                if (uMode >= 10) {
                    color = customTint(rolloff(premiumSky(uMode, dir, time, scale))) + dither();
                    fragColor = vec4(color, intensity * mix(1.0, 0.72, clamp(uVanilla, 0.0, 1.0)));
                    return;
                }
                if (uMode == 0) color = aurora(dir, time, scale, intensity);
                else if (uMode == 1) color = night(dir, time, scale, intensity);
                else if (uMode == 2) color = snow(dir, time, scale, intensity);
                else if (uMode == 3) color = cloudy(dir, time, scale, intensity);
                else if (uMode == 4) color = starMode(dir, time, scale, intensity);
                else if (uMode == 5) color = glow(dir, time, scale, intensity);
                else if (uMode == 6) color = nebula(dir, time, scale, intensity);
                else if (uMode == 7) color = storm(dir, time, scale, intensity);
                else if (uMode == 8) color = tide(dir, time, scale, intensity);
                else color = sunset(dir, time);

                color += uAccent * starField(dir, uStars, time) * smoothstep(-0.15, 0.65, dir.y);
                color *= 0.72 + smoothstep(-0.65, 0.85, dir.y) * 0.34;
                color += dither();

                float opacity = uMode == 9 ? 1.0 : intensity;
                fragColor = vec4(color, opacity * mix(1.0, 0.72, clamp(uVanilla, 0.0, 1.0)));
            }
            """;

    private static final String BLEND = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uCur;
            uniform sampler2D uPrev;
            uniform float uAmount;
            void main() {
                vec3 c = texture(uCur, vUv).rgb, p = texture(uPrev, vUv).rgb;
                fragColor = vec4(mix(c, p, uAmount), 1.0);
            }
            """;

    private static final GlState GL = new GlState();
    private static int progSky, progBlend, vao, fboMain, fboTmp, fboPrev, texTmp, texPrev, w, h;
    private static boolean failed, hasPrev;
    private static final long START = System.nanoTime();

    private WorldFx() {
    }

    private static boolean setup() {
        if (failed) return false;
        if (progSky == 0) {
            progSky = Glass.program(VERT, SKY);
            progBlend = Glass.program(VERT, BLEND);
            if (progSky == 0 || progBlend == 0) {
                failed = true;
                return false;
            }
            vao = GL30.glGenVertexArrays();
            fboMain = GL30.glGenFramebuffers();
            fboTmp = GL30.glGenFramebuffers();
            fboPrev = GL30.glGenFramebuffers();
        }
        return true;
    }

    private static void baseState() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColorMask(true, true, true, true);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL33.glBindSampler(0, 0);
        GL33.glBindSampler(1, 0);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static void setColor(int program, String name, int argb) {
        GL20.glUniform3f(GL20.glGetUniformLocation(program, name),
                ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f);
    }

    /** Before the hand is drawn (depth still holds the world). */
    public static void drawSky() {
        if (sky < 0 || !setup() || !tenacity.util.Projection.ready()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.getFramebuffer().getColorAttachment() instanceof GlTexture color) || color.isClosed()) return;
        if (!(mc.getFramebuffer().getDepthAttachment() instanceof GlTexture depth) || depth.isClosed()) return;
        GL.save();
        try {
            baseState();
            // the shader writes alpha so "vanilla sky" can blend the procedural sky over the real one
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboMain);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color.getGlId(), 0);
            GL11.glViewport(0, 0, color.getWidth(0), color.getHeight(0));
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth.getGlId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, 0x884C /* COMPARE_MODE */, GL11.GL_NONE);
            GL20.glUseProgram(progSky);
            GL20.glUniform1i(GL20.glGetUniformLocation(progSky, "uDepth"), 0);
            Matrix4f inv = tenacity.util.Projection.inverseViewProj();
            float[] m = new float[16];
            inv.get(m);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(progSky, "uInvViewProj"), false, m);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uTime"), (System.nanoTime() - START) / 1e9f);
            GL20.glUniform1i(GL20.glGetUniformLocation(progSky, "uMode"), sky >= 100 ? sky : Math.max(0, Math.min(SkyShaders.COUNT - 1, sky)));
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uCustom"), skyCustom ? 1f : 0f);
            setColor(progSky, "uPrimary", skyPrimary);
            setColor(progSky, "uSecondary", skySecondary);
            setColor(progSky, "uAccent", 0xFFFFFFFF);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uSpeed"), skySpeed);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uScale"), skyScale);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uStrength"), skyStrength);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uStars"), skyStars);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uVanilla"), skyVanilla ? 1f : 0f);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uSClouds"), seasonClouds);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uSMoon"), seasonMoon);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uSPart"), seasonParticles);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uSGlow"), seasonGlow);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uSAurora"), seasonAurora);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uSFog"), seasonFog);
            GL20.glUniform1f(GL20.glGetUniformLocation(progSky, "uSBright"), seasonBright);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("shader sky failed - disabled", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }

    private static int texture(int w, int h) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        return t;
    }

    /** After the world and hand, before the gui. */
    public static void motionBlur() {
        if (motionBlur <= 0.01f) {
            hasPrev = false;
            return;
        }
        if (!setup()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.getFramebuffer().getColorAttachment() instanceof GlTexture color) || color.isClosed()) return;
        int cw = color.getWidth(0), ch = color.getHeight(0);
        GL.save();
        try {
            if (cw != w || ch != h || texTmp == 0) {
                if (texTmp != 0) GL11.glDeleteTextures(new int[]{texTmp, texPrev});
                texTmp = texture(cw, ch);
                texPrev = texture(cw, ch);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboTmp);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texTmp, 0);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboPrev);
                GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texPrev, 0);
                w = cw;
                h = ch;
                hasPrev = false;
            }
            baseState();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboMain);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color.getGlId(), 0);
            if (hasPrev) {
                // current -> tmp, then mix(tmp, prev) -> main
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboMain);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fboTmp);
                GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboMain);
                GL11.glViewport(0, 0, w, h);
                GL20.glUseProgram(progBlend);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texPrev);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, texTmp);
                GL20.glUniform1i(GL20.glGetUniformLocation(progBlend, "uCur"), 0);
                GL20.glUniform1i(GL20.glGetUniformLocation(progBlend, "uPrev"), 1);
                GL20.glUniform1f(GL20.glGetUniformLocation(progBlend, "uAmount"), motionBlur);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }
            // the result becomes the previous frame
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboMain);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fboPrev);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            hasPrev = true;
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("motion blur failed - disabled", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }
}

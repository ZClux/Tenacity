package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.GlTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import tenacity.Tenacity;

/**
 * Animated black background for the menus: slow domain warped noise gives soft grey gradients that flow like smoke, with a
 * light that follows the pointer, a vignette and fine grain. Drawn straight into Minecraft's main framebuffer right before
 * the gui pass, so the vanilla widgets (the player model) are drawn on top of it.
 */
public final class MenuShader {
    private static final String VERT = """
            #version 330 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 330 core
            out vec4 fragColor;
            uniform vec2 uRes;
            uniform float uTime;
            uniform vec2 uMouse;

            float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
            float noise(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
            }
            float fbm(vec2 p) {
                float v = 0.0, a = 0.5;
                mat2 r = mat2(0.8, 0.6, -0.6, 0.8);
                for (int i = 0; i < 5; i++) { v += a * noise(p); p = r * p * 2.02; a *= 0.5; }
                return v;
            }

            void main() {
                vec2 fc = gl_FragCoord.xy;
                vec2 uv = fc / uRes;
                vec2 p = (fc - 0.5 * uRes) / uRes.y;
                float t = uTime;
                vec3 col = vec3(0.004, 0.005, 0.008);

                // deep, slow fog
                vec2 q = vec2(fbm(p * 1.2 + vec2(t * 0.02, -t * 0.015)), fbm(p * 1.2 + vec2(5.2, 1.3 + t * 0.02)));
                float fog = fbm(p * 1.0 + 2.4 * q);
                col += vec3(0.06, 0.065, 0.08) * smoothstep(0.3, 0.95, fog);

                // silk ribbons of light flowing across the screen
                for (int i = 0; i < 5; i++) {
                    float fi = float(i);
                    float speed = 0.10 + fi * 0.025;
                    float wave = sin(p.x * (1.6 + fi * 0.35) + t * speed * 3.0 + fi * 1.7) * (0.16 + fi * 0.02)
                               + sin(p.x * (3.1 + fi * 0.5) - t * speed * 2.0 + fi) * 0.05
                               + (fbm(vec2(p.x * 0.8 + t * 0.05, fi * 3.1)) - 0.5) * 0.35;
                    float y = wave + (fi - 2.0) * 0.11 - 0.05;
                    float d = abs(p.y - y);
                    float thick = 0.010 + 0.035 * fbm(vec2(p.x * 1.5 - t * 0.08, fi * 7.0));
                    float glow = exp(-d * d / (thick * thick * 6.0));
                    float core = exp(-d * d / (thick * thick * 0.25));
                    float fade = smoothstep(-0.95, -0.2, p.x) * smoothstep(0.95, 0.25, p.x * 0.6 + 0.2);
                    float shimmer = 0.6 + 0.4 * sin(p.x * 9.0 - t * (0.8 + fi * 0.2) + fi * 2.0);
                    col += vec3(0.55, 0.58, 0.66) * glow * 0.16 * fade * shimmer;
                    col += vec3(0.95) * core * 0.10 * fade * shimmer;
                }

                // drifting dust
                vec2 g = uv * vec2(uRes.x / uRes.y, 1.0) * 26.0 + vec2(0.0, -t * 0.25);
                vec2 cell = floor(g), f = fract(g) - 0.5;
                float h = hash(cell);
                vec2 off = vec2(hash(cell + 3.1), hash(cell + 7.7)) - 0.5;
                float dd = length(f - off * 0.7);
                float tw = 0.5 + 0.5 * sin(t * (1.0 + h * 2.0) + h * 30.0);
                col += vec3(0.9) * smoothstep(0.06, 0.0, dd) * step(0.86, h) * tw * 0.35;

                // light following the pointer
                vec2 m = (uMouse - 0.5 * uRes) / uRes.y;
                col += vec3(0.06, 0.065, 0.08) * exp(-length(p - m) * 2.2);

                vec2 v = uv - 0.5;
                col *= 1.0 - 0.9 * dot(v, v) * 2.0;
                col += (hash(fc + fract(t) * 100.0) - 0.5) * 0.012;
                fragColor = vec4(max(col, 0.0), 1.0);
            }
            """;

    private static final GlState GL = new GlState();
    private static int prog, vao, fbo;
    private static boolean failed;
    private static final long START = System.nanoTime();

    private MenuShader() {
    }

    public static void draw() {
        if (failed) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.getFramebuffer().getColorAttachment() instanceof GlTexture color) || color.isClosed()) return;
        int w = color.getWidth(0), h = color.getHeight(0);
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
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color.getGlId(), 0);
            GL11.glViewport(0, 0, w, h);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColorMask(true, true, true, true);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL20.glUseProgram(prog);
            GL20.glUniform2f(GL20.glGetUniformLocation(prog, "uRes"), w, h);
            GL20.glUniform1f(GL20.glGetUniformLocation(prog, "uTime"), (System.nanoTime() - START) / 1e9f);
            double sx = w / (double) Math.max(1, mc.getWindow().getWidth());
            GL20.glUniform2f(GL20.glGetUniformLocation(prog, "uMouse"), (float) (mc.mouse.getX() * sx), (float) (h - mc.mouse.getY() * sx));
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("menu background shader failed", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }
}

package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.GlTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import tenacity.Tenacity;
import tenacity.modules.RenderFlags;

import java.nio.ByteBuffer;

/**
 * Frosted glass players (Player Transparency). The chosen players are put in the entity outline pass, whose buffer then holds
 * their silhouettes. Inside a silhouette the scene behind is replaced by a frosted version of itself: blurred, brightened with
 * a cool white, a little grain, a soft rim of light along the edge and a slow sheen - while the see-through body still shows.
 */
public final class FrostFx {
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
            uniform sampler2D uMask;
            uniform sampler2D uScene;
            uniform sampler2D uBlur;
            uniform vec2 uTexel;
            uniform vec3 uTint;
            uniform float uFrost, uTime;
            uniform int uMode;
            float vn(vec2 p) { vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
                float a = fract(sin(dot(i, vec2(127.1, 311.7))) * 43758.5453), b = fract(sin(dot(i + vec2(1, 0), vec2(127.1, 311.7))) * 43758.5453);
                float c = fract(sin(dot(i + vec2(0, 1), vec2(127.1, 311.7))) * 43758.5453), d = fract(sin(dot(i + vec2(1, 1), vec2(127.1, 311.7))) * 43758.5453);
                return mix(mix(a, b, f.x), mix(c, d, f.x), f.y); }
            float fbm(vec2 p) { float v = 0.0, w = 0.5; for (int i = 0; i < 5; i++) { v += w * vn(p); p = p * 2.02 + 5.3; w *= 0.5; } return v; }

            float cov(vec2 uv) { vec4 c = texture(uMask, uv); return max(c.a, max(c.r, max(c.g, c.b))); }
            float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

            void main() {
                float inside = cov(vUv);
                if (inside < 0.5) discard;
                // distance to the edge of the silhouette (in px, capped) for the rim light
                float mn = 1.0;
                for (int r = 1; r <= 3; r++)
                    for (int i = 0; i < 12; i++) {
                        float an = float(i) * 0.5235988;
                        mn = min(mn, cov(vUv + vec2(cos(an), sin(an)) * float(r) * 2.0 * uTexel));
                    }
                float rim = 1.0 - mn;
                vec3 scene = texture(uScene, vUv).rgb;
                // frosted glass: the scene behind seen through a rough surface (offset blur sample = refraction)
                vec2 jitter = (vec2(hash(gl_FragCoord.xy), hash(gl_FragCoord.yx + 7.0)) - 0.5) * uTexel * 6.0;
                vec3 blurred = texture(uBlur, vUv + jitter).rgb;
                if (uMode == 1) { // smoke: churning grey wisps rising through the body, dark core, soft glowing edge
                    vec2 p = vUv * vec2(uTexel.y / uTexel.x, 1.0) * 7.0;
                    vec2 q = vec2(fbm(p + vec2(0.0, -uTime * 0.35)), fbm(p + vec2(5.2, 1.3) - uTime * 0.25));
                    float n = fbm(p + q * 2.2 + vec2(0.0, -uTime * 0.6));
                    float lum = dot(blurred, vec3(0.299, 0.587, 0.114));
                    vec3 deep = vec3(lum * 0.30) + 0.03;
                    vec3 wisp = mix(vec3(0.42, 0.44, 0.48), uTint * 0.9, 0.35);
                    vec3 smoke = mix(deep, wisp, smoothstep(0.30, 0.80, n));
                    smoke += vec3(0.9, 0.93, 1.0) * pow(smoothstep(0.55, 0.95, n), 3.0) * 0.35;   // lit curls
                    smoke += wisp * rim * (0.35 + 0.65 * n) * 0.8;                              // wispy edge
                    smoke += (hash(gl_FragCoord.xy + uTime) - 0.5) * 0.02;
                    float k = uFrost * (0.72 + 0.28 * n) * (1.0 - rim * (1.0 - n) * 0.6);
                    fragColor = vec4(clamp(mix(scene, smoke, k), 0.0, 1.0), 1.0);
                    return;
                }
                vec3 frost = blurred * 0.82 + uTint * 0.30;
                frost += (hash(gl_FragCoord.xy + uTime) - 0.5) * 0.035;                 // grain
                float sheen = smoothstep(0.0, 1.0, 0.5 + 0.5 * sin((vUv.x * 2.0 + vUv.y * 5.0) * 3.0 - uTime * 0.8));
                frost += vec3(0.06) * sheen;
                frost += uTint * rim * 0.55;                                              // light along the edge
                vec3 col = mix(scene, frost, uFrost);
                fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
            """;

    private static final GlState GL = new GlState();
    private static final KawaseChain CHAIN = new KawaseChain();
    private static int prog, vao, fbo, readFbo, tmpTex, tmpW, tmpH;
    private static boolean failed;
    private static final long START = System.nanoTime();

    private FrostFx() {
    }

    public static boolean active() {
        return !failed && RenderFlags.frost && RenderFlags.playerAlpha < 1f;
    }

    public static void draw() {
        if (!active()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.worldRenderer == null) return;
        var outline = ((tenacity.mixin.WorldRendererAccessor) mc.worldRenderer).tenacity$outline();
        if (outline == null) {
            mc.worldRenderer.loadEntityOutlinePostProcessor();
            return;
        }
        if (!(outline.getColorAttachment() instanceof GlTexture mask) || mask.isClosed()) return;
        if (!(mc.getFramebuffer().getColorAttachment() instanceof GlTexture main) || main.isClosed()) return;
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
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColorMask(true, true, true, true);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL33.glBindSampler(0, 0);
            GL33.glBindSampler(1, 0);
            GL33.glBindSampler(2, 0);
            // copy the scene (our own texture: linear filtered, no mipmaps) and blur it
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.getGlId(), 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tmpTex, 0);
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            int blur = CHAIN.blur(tmpTex, w, h, 4, 1.4f);

            // frost the silhouettes onto the scene
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.getGlId(), 0);
            GL11.glViewport(0, 0, w, h);
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GL20.glUseProgram(prog);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, blur);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tmpTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mask.getGlId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uMask"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uScene"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uBlur"), 2);
            GL20.glUniform2f(GL20.glGetUniformLocation(prog, "uTexel"), 1f / w, 1f / h);
            int t = RenderFlags.alphaTint;
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uTint"), ((t >> 16) & 255) / 255f, ((t >> 8) & 255) / 255f, (t & 255) / 255f);
            GL20.glUniform1f(GL20.glGetUniformLocation(prog, "uFrost"), 1f - RenderFlags.playerAlpha * 0.6f);
            GL20.glUniform1f(GL20.glGetUniformLocation(prog, "uTime"), (System.nanoTime() - START) / 1e9f);
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uMode"), RenderFlags.smoke ? 1 : 0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable e) {
            failed = true;
            Tenacity.LOG.error("frost players failed - disabled", e);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }
}

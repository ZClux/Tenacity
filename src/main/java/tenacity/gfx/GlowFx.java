package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.GlTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import tenacity.Tenacity;
import tenacity.modules.RenderFlags;

import java.nio.ByteBuffer;

/**
 * Shader ESP glow. Vanilla's outline post effect is switched off while glow is on (see WorldOutlineMixin), so the entity
 * outline framebuffer holds the plain silhouettes of every glowing entity. That mask is blurred with a dual Kawase chain and
 * combined onto the scene: a soft halo outside, a crisp rim on the edge, an optional fill with an inner glow, a moving
 * shimmer and a pulse.
 */
public final class GlowFx {
    private static final int LEVELS = 4;

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String DOWN = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uTex;
            uniform vec2 uHalf;
            void main() {
                vec4 s = texture(uTex, vUv) * 4.0;
                s += texture(uTex, vUv - uHalf);
                s += texture(uTex, vUv + uHalf);
                s += texture(uTex, vUv + vec2(uHalf.x, -uHalf.y));
                s += texture(uTex, vUv - vec2(uHalf.x, -uHalf.y));
                fragColor = s * 0.125;
            }
            """;

    private static final String UP = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uTex;
            uniform vec2 uHalf;
            void main() {
                vec2 o = uHalf;
                vec4 s = texture(uTex, vUv + vec2(-o.x * 2.0, 0.0));
                s += texture(uTex, vUv + vec2(-o.x, o.y)) * 2.0;
                s += texture(uTex, vUv + vec2(0.0, o.y * 2.0));
                s += texture(uTex, vUv + vec2(o.x, o.y)) * 2.0;
                s += texture(uTex, vUv + vec2(o.x * 2.0, 0.0));
                s += texture(uTex, vUv + vec2(o.x, -o.y)) * 2.0;
                s += texture(uTex, vUv + vec2(0.0, -o.y * 2.0));
                s += texture(uTex, vUv + vec2(-o.x, -o.y)) * 2.0;
                fragColor = s / 12.0;
            }
            """;

    private static final String COMBINE = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uMask;
            uniform sampler2D uGlow;
            uniform vec2 uTexel;
            uniform float uStrength, uFalloff, uFill, uInner, uRimW, uRim, uShimmer, uPulse, uTime, uSat;
            uniform vec3 uColor;
            uniform int uUseColor, uChroma;

            float cov(vec4 c) { return max(c.a, max(c.r, max(c.g, c.b))); }

            void main() {
                vec4 m = texture(uMask, vUv);
                vec4 g = texture(uGlow, vUv);
                float sharp = cov(m);
                float blur = clamp(cov(g) * 1.6, 0.0, 1.0);
                bool inside = sharp >= 0.5;
                if (!inside && blur < 0.002) discard;
                vec3 tint = inside ? m.rgb / max(sharp, 1e-3) : g.rgb / max(cov(g), 1e-3);
                tint = clamp(tint, 0.0, 1.0);
                if (uChroma == 1) tint = 0.5 + 0.5 * cos(6.2831 * (vUv.y * 0.8 + vUv.x * 0.3 - uTime * 0.25 + vec3(0.0, 0.33, 0.67)));
                else if (uUseColor == 1) tint = uColor;
                { // Crypto: deepen the colour keeping its hue so the additive glow does not wash it to white
                    float hi = max(tint.r, max(tint.g, tint.b)), lo = min(tint.r, min(tint.g, tint.b));
                    float sat = (hi - lo) / max(hi, 0.0001);
                    if (sat > 0.01) { float k = min(1.0 / sat, 1.0 + (uSat - 1.0) * 3.0); tint = clamp(vec3(hi) - (vec3(hi) - tint) * k, 0.0, 1.0); }
                }
                // eased, slower pulse: ph*abs(ph) flattens the peaks and the zero crossings so it breathes instead of flickering
                float ph = sin(uTime * 1.9);
                float pulse = 1.0 + uPulse * (0.5 * ph + 0.5 * ph * abs(ph));

                // blur * 0.55 keeps the halo tight against the silhouette instead of leaving a gap
                // three stacked falloff bands, the same way skeleton ESP stacks widening strokes, so the glow ramps out
                // smoothly instead of dropping off in one curve
                float halo = inside ? 0.0 : (blur * 0.55
                        + pow(blur, 2.2) * 0.30
                        + pow(blur, uFalloff) * 0.35
                        + pow(blur, 4.0) * 0.15) * uStrength;
                // the body itself always glows a little; Fill only adds on top of that
                float fill = inside ? uFill + uInner * pow(clamp(1.0 - blur, 0.0, 1.0), 1.5) : 0.0;
                float rim = 0.0;
                int w = int(uRimW + 0.5);
                if (uRim > 0.0 && w > 0 && !inside) {
                    float best = 1e6;
                    for (int y = -w; y <= w; y++)
                        for (int x = -w; x <= w; x++)
                            if (cov(texture(uMask, vUv + vec2(x, y) * uTexel)) >= 0.5) best = min(best, length(vec2(x, y)));
                    if (best <= float(w)) rim = (1.0 - best / (float(w) + 1.0)) * uRim;
                }
                vec3 rgb = tint * (halo + fill) * pulse + tint * rim;
                if (uShimmer > 0.0) {
                    float sweep = 1.0 - smoothstep(0.0, 0.08, abs(vUv.y - fract(uTime * 0.35) * 1.3 + 0.15));
                    rgb += vec3(sweep * uShimmer * max(halo + fill, rim));
                }
                float peak = max(rgb.r, max(rgb.g, rgb.b));
                if (peak < 0.002) discard;
                if (peak > 1.0) rgb /= peak;
                fragColor = vec4(rgb, 1.0);
            }
            """;

    private static final GlState GL = new GlState();
    private static int progDown, progUp, progCombine, vao, mainFbo;
    private static final int[] tex = new int[LEVELS], fbo = new int[LEVELS], lw = new int[LEVELS], lh = new int[LEVELS];
    private static int texW, texH;
    private static boolean failed;
    private static final long START = System.nanoTime();

    private GlowFx() {
    }

    /** True when the vanilla outline post effect should be skipped (the raw silhouettes are used here instead). */
    public static boolean active() {
        return !failed && RenderFlags.glowAny();
    }

    private static int texture(int w, int h) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        return t;
    }

    private static void ensure(int w, int h) {
        if (texW == w && texH == h && tex[0] != 0) return;
        for (int i = 0; i < LEVELS; i++) {
            if (tex[i] != 0) GL11.glDeleteTextures(tex[i]);
            if (fbo[i] != 0) GL30.glDeleteFramebuffers(fbo[i]);
        }
        int cw = w, ch = h;
        for (int i = 0; i < LEVELS; i++) {
            cw = Math.max(1, cw / 2);
            ch = Math.max(1, ch / 2);
            lw[i] = cw;
            lh[i] = ch;
            tex[i] = texture(cw, ch);
            fbo[i] = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex[i], 0);
        }
        texW = w;
        texH = h;
    }

    public static void draw() {
        if (!active()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.worldRenderer == null) return;
        var outline = ((tenacity.mixin.WorldRendererAccessor) mc.worldRenderer).tenacity$outline();
        if (outline == null || !(outline.getColorAttachment() instanceof GlTexture src) || src.isClosed()) return;
        if (!(mc.getFramebuffer().getColorAttachment() instanceof GlTexture main) || main.isClosed()) return;
        int w = main.getWidth(0), h = main.getHeight(0);
        GL.save();
        try {
            if (progCombine == 0) {
                progDown = Glass.program(VERT, DOWN);
                progUp = Glass.program(VERT, UP);
                progCombine = Glass.program(VERT, COMBINE);
                if (progDown == 0 || progUp == 0 || progCombine == 0) {
                    failed = true;
                    return;
                }
                vao = GL30.glGenVertexArrays();
                mainFbo = GL30.glGenFramebuffers();
            }
            ensure(src.getWidth(0), src.getHeight(0));
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

            // blur chain: wider glow = more levels / bigger offset
            int use = Math.max(2, Math.min(LEVELS, Math.round(1.5f + RenderFlags.glowSize * 1.3f)));
            float off = 1.0f + RenderFlags.glowSize * 0.4f;
            GL20.glUseProgram(progDown);
            GL20.glUniform1i(GL20.glGetUniformLocation(progDown, "uTex"), 0);
            int srcTex = src.getGlId(), sw = src.getWidth(0), sh = src.getHeight(0);
            for (int i = 0; i < use; i++) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[i]);
                GL11.glViewport(0, 0, lw[i], lh[i]);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, srcTex);
                GL20.glUniform2f(GL20.glGetUniformLocation(progDown, "uHalf"), off * 0.5f / sw, off * 0.5f / sh);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                srcTex = tex[i];
                sw = lw[i];
                sh = lh[i];
            }
            GL20.glUseProgram(progUp);
            GL20.glUniform1i(GL20.glGetUniformLocation(progUp, "uTex"), 0);
            for (int i = use - 2; i >= 0; i--) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[i]);
                GL11.glViewport(0, 0, lw[i], lh[i]);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[i + 1]);
                GL20.glUniform2f(GL20.glGetUniformLocation(progUp, "uHalf"), off * 0.5f / lw[i + 1], off * 0.5f / lh[i + 1]);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }

            // combine onto the scene, additive
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, mainFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, main.getGlId(), 0);
            GL11.glViewport(0, 0, w, h);
            GL11.glColorMask(true, true, true, false);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL20.glUseProgram(progCombine);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[0]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, src.getGlId());
            int p = progCombine;
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "uMask"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "uGlow"), 1);
            GL20.glUniform2f(GL20.glGetUniformLocation(p, "uTexel"), 1f / src.getWidth(0), 1f / src.getHeight(0));
            GL20.glUniform1f(GL20.glGetUniformLocation(p, "uStrength"), RenderFlags.glowStrength);
            GL20.glUniform1f(GL20.glGetUniformLocation(p, "uFalloff"), 1.1f);
            GL20.glUniform1f(GL20.glGetUniformLocation(p, "uFill"), RenderFlags.glowFill);
            GL20.glUniform1f(GL20.glGetUniformLocation(p, "uInner"), RenderFlags.glowFill > 0 ? 0.6f : 0f);
            GL20.glUniform1f(GL20.glGetUniformLocation(p, "uSat"), 1.15f);
            int gc = RenderFlags.glowColor;
            GL20.glUniform3f(GL20.glGetUniformLocation(p, "uColor"), ((gc >> 16) & 255) / 255f, ((gc >> 8) & 255) / 255f, (gc & 255) / 255f);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "uUseColor"), gc != 0 ? 1 : 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "uChroma"), RenderFlags.glowChroma ? 1 : 0);
            // the rim grows with Glow Size too, not just the halo
            GL20.glUniform1f(GL20.glGetUniformLocation(p, "uRimW"),
                    RenderFlags.glowRimWidth * h / 1080f * (0.7f + RenderFlags.glowSize * 0.6f));
            GL20.glUniform1f(GL20.glGetUniformLocation(p, "uRim"), RenderFlags.glowRimWidth > 0 ? 1f : 0f);
            GL20.glUniform1f(GL20.glGetUniformLocation(p, "uShimmer"), RenderFlags.glowShimmer ? 0.6f : 0f);
            GL20.glUniform1f(GL20.glGetUniformLocation(p, "uPulse"), RenderFlags.glowPulse ? 0.25f : 0f);
            GL20.glUniform1f(GL20.glGetUniformLocation(p, "uTime"), (System.nanoTime() - START) / 1e9f);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("glow ESP failed - disabled", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }
}

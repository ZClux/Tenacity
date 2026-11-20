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
import tenacity.Tenacity;
import tenacity.util.Projection;

import java.nio.ByteBuffer;

/**
 * Smooth motion blur.
 * <ul>
 *   <li>Velocity: per pixel camera velocity from depth reprojection (current inverse view-proj -> previous view-proj),
 *   normalised to a fixed 1/60 s shutter so the blur length does not depend on the frame rate, sampled with 16 jittered
 *   taps along the motion vector (no stepping, no ghost copies). The hand is masked out.</li>
 *   <li>Accumulate: exponential frame blend into a 16 bit float history, weight {@code a^(dt*60)} so it decays at the
 *   same speed at any fps and never leaves 8 bit banding trails.</li>
 * </ul>
 */
public final class MotionBlurFx {
    /** 0 off .. 1. */
    public static float strength;
    /** 0 Velocity, 1 Accumulate, 2 Both. */
    public static int mode;

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String COPY = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uDepth;
            void main() { fragColor = vec4(texture(uDepth, vUv).r, 0.0, 0.0, 1.0); }
            """;

    private static final String BLUR = """
            #version 330 core
            in vec2 vUv;
            layout(location = 0) out vec4 outMain;
            layout(location = 1) out vec4 outHist;
            uniform sampler2D uCur;
            uniform sampler2D uWorldDepth;
            uniform sampler2D uHandDepth;
            uniform sampler2D uHist;
            uniform mat4 uInvVP;
            uniform mat4 uPrevVP;
            uniform vec3 uCamDelta;
            uniform float uScale;
            uniform float uAccum;
            uniform float uHasHist;
            uniform float uHandMask;

            bool hand(vec2 uv) { return uHandMask > 0.5 && texture(uHandDepth, uv).r < 0.99999; }

            void main() {
                vec3 col = texture(uCur, vUv).rgb;
                bool isHand = hand(vUv);
                if (uScale > 0.0 && !isHand) {
                    float d = texture(uWorldDepth, vUv).r;
                    vec4 wp = uInvVP * vec4(vUv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
                    wp /= wp.w;
                    vec3 rel = wp.xyz + (d >= 0.99999 ? vec3(0.0) : uCamDelta);
                    vec4 pc = uPrevVP * vec4(rel, 1.0);
                    if (pc.w > 1e-4) {
                        vec2 prevUv = pc.xy / pc.w * 0.5 + 0.5;
                        vec2 vel = (vUv - prevUv) * uScale;
                        float len = length(vel);
                        if (len > 0.06) vel *= 0.06 / len;
                        if (length(vel * textureSize(uCur, 0)) > 0.75) {
                            // interleaved gradient noise jitter hides the tap pattern
                            float j = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5;
                            vec3 acc = col;
                            float wsum = 1.0;
                            const int N = 16;
                            for (int i = 0; i < N; i++) {
                                float t = (float(i) + 0.5 + j) / float(N) - 0.5;
                                vec2 uv = vUv + vel * t;
                                if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0 || hand(uv)) continue;
                                float w = 1.0 - abs(t) * 0.8; // soft tent: centre heavier, no hard edges
                                acc += texture(uCur, uv).rgb * w;
                                wsum += w;
                            }
                            col = acc / wsum;
                        }
                    }
                }
                vec3 res = col;
                if (uAccum > 0.0 && uHasHist > 0.5 && !isHand) res = mix(col, texture(uHist, vUv).rgb, uAccum);
                outMain = vec4(res, 1.0);
                outHist = vec4(res, 1.0);
            }
            """;

    private static final GlState GL = new GlState();
    private static int progCopy, progBlur, vao, fboDepth, fboOut, fboRead, texDepth, texCur, w, h;
    private static final int[] hist = new int[2];
    private static int histIdx;
    private static boolean failed, hasHist, hasPrev, snapThisFrame;
    private static final Matrix4f prevVP = new Matrix4f();
    private static Vec3d prevCam = Vec3d.ZERO;
    private static long lastNs;

    private MotionBlurFx() {
    }

    private static boolean setup() {
        if (failed) return false;
        if (progBlur == 0) {
            progCopy = Glass.program(VERT, COPY);
            progBlur = Glass.program(VERT, BLUR);
            if (progCopy == 0 || progBlur == 0) {
                failed = true;
                return false;
            }
            vao = GL30.glGenVertexArrays();
            fboDepth = GL30.glGenFramebuffers();
            fboOut = GL30.glGenFramebuffers();
            fboRead = GL30.glGenFramebuffers();
        }
        return true;
    }

    private static int tex(int iw, int ih, int internal, int format, int type) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, iw, ih, 0, format, type, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
        return t;
    }

    private static void resize(int cw, int ch) {
        if (cw == w && ch == h && texCur != 0) return;
        if (texCur != 0) GL11.glDeleteTextures(new int[]{texCur, texDepth, hist[0], hist[1]});
        texCur = tex(cw, ch, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
        texDepth = tex(cw, ch, GL30.GL_R32F, GL11.GL_RED, GL11.GL_FLOAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        hist[0] = tex(cw, ch, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT);
        hist[1] = tex(cw, ch, GL30.GL_RGBA16F, GL11.GL_RGBA, GL11.GL_FLOAT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboDepth);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texDepth, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboRead);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texCur, 0);
        w = cw;
        h = ch;
        hasHist = false;
    }

    private static void baseState() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColorMask(true, true, true, true);
        for (int i = 0; i < 4; i++) GL33.glBindSampler(i, 0);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static void bind(int unit, int tex) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
    }

    /** Before the depth buffer is cleared for the hand: keep a copy of the world depth for reprojection. */
    public static void captureDepth() {
        if (strength <= 0.01f || mode == 1 || !setup()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.getFramebuffer().getColorAttachment() instanceof GlTexture color) || color.isClosed()) return;
        if (!(mc.getFramebuffer().getDepthAttachment() instanceof GlTexture depth) || depth.isClosed()) return;
        GL.save();
        try {
            resize(color.getWidth(0), color.getHeight(0));
            baseState();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboDepth);
            GL11.glViewport(0, 0, w, h);
            bind(0, depth.getGlId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, 0x884C /* COMPARE_MODE */, GL11.GL_NONE);
            GL20.glUseProgram(progCopy);
            GL20.glUniform1i(GL20.glGetUniformLocation(progCopy, "uDepth"), 0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            snapThisFrame = true;
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("motion blur depth failed - disabled", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }

    /** After the world and the hand, before the gui. */
    public static void apply() {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 1 / 60f : Math.max(1 / 1000f, Math.min(0.1f, (now - lastNs) / 1e9f));
        lastNs = now;
        boolean snapped = snapThisFrame;
        snapThisFrame = false;
        Matrix4f vp = Projection.viewProj();
        Vec3d cam = Projection.camera();
        if (strength <= 0.01f || !setup() || !Projection.ready()) {
            hasHist = false;
            hasPrev = false;
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.getFramebuffer().getColorAttachment() instanceof GlTexture color) || color.isClosed()) return;
        if (!(mc.getFramebuffer().getDepthAttachment() instanceof GlTexture depth) || depth.isClosed()) return;
        GL.save();
        try {
            resize(color.getWidth(0), color.getHeight(0));
            baseState();
            // current frame -> texCur
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboOut);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color.getGlId(), 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, hist[1 - histIdx], 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboOut);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fboRead);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});
            GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            boolean velocity = mode != 1;
            boolean jump = !hasPrev || cam.squaredDistanceTo(prevCam) > 16;
            float scale = velocity && !jump ? strength * 1.6f * Math.max(0.25f, Math.min(4f, (1 / 60f) / dt)) : 0f;
            float a = mode == 0 ? 0f : (mode == 1 ? 0.9f : 0.55f) * strength;
            float accum = a <= 0 ? 0f : (float) Math.pow(a, dt * 60f);
            if (jump) accum = 0;

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboOut);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
            GL11.glViewport(0, 0, w, h);
            GL20.glUseProgram(progBlur);
            bind(0, texCur);
            bind(1, snapped ? texDepth : depth.getGlId());
            if (!snapped) GL11.glTexParameteri(GL11.GL_TEXTURE_2D, 0x884C, GL11.GL_NONE);
            bind(2, depth.getGlId());
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, 0x884C, GL11.GL_NONE);
            bind(3, hist[histIdx]);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(GL20.glGetUniformLocation(progBlur, "uCur"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(progBlur, "uWorldDepth"), 1);
            GL20.glUniform1i(GL20.glGetUniformLocation(progBlur, "uHandDepth"), 2);
            GL20.glUniform1i(GL20.glGetUniformLocation(progBlur, "uHist"), 3);
            float[] m = new float[16];
            new Matrix4f(vp).invert().get(m);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(progBlur, "uInvVP"), false, m);
            prevVP.get(m);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(progBlur, "uPrevVP"), false, m);
            GL20.glUniform3f(GL20.glGetUniformLocation(progBlur, "uCamDelta"),
                    (float) (cam.x - prevCam.x), (float) (cam.y - prevCam.y), (float) (cam.z - prevCam.z));
            GL20.glUniform1f(GL20.glGetUniformLocation(progBlur, "uScale"), scale);
            GL20.glUniform1f(GL20.glGetUniformLocation(progBlur, "uAccum"), accum);
            GL20.glUniform1f(GL20.glGetUniformLocation(progBlur, "uHasHist"), hasHist ? 1f : 0f);
            GL20.glUniform1f(GL20.glGetUniformLocation(progBlur, "uHandMask"), snapped ? 1f : 0f);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0});
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GL11.GL_TEXTURE_2D, 0, 0);
            histIdx = 1 - histIdx;
            hasHist = true;
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("motion blur failed - disabled", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
        prevVP.set(vp);
        prevCam = cam;
        hasPrev = true;
    }
}

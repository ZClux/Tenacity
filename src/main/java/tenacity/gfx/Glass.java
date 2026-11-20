package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import tenacity.Tenacity;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Real liquid glass. Every frame the finished scene is copied into a full resolution texture and run through a dual Kawase
 * blur chain. Glass panels are then drawn with a signed distance field fragment shader that
 * <ul>
 *   <li>refracts the sharp scene through a curved bezel (lens profile) with per channel dispersion,</li>
 *   <li>shows the blurred scene in the body of the panel (frosted), slightly saturated,</li>
 *   <li>adds a directional specular rim, an inner bevel glow, a slow light sheen and a soft drop shadow.</li>
 * </ul>
 * Panels are queued with {@link #panel} and drawn in {@link #flush}, which {@link Nvg#layer()} / {@link Nvg#end()} call so
 * NanoVG content ends up on top of the glass of the same layer.
 */
public final class Glass {
    private static final int MAX_LEVELS = 5;

    private static final String VERT = """
            #version 330 core
            uniform vec4 uQuad;
            uniform vec2 uRes;
            out vec2 vUv;
            void main() {
                vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
                vUv = c;
                vec2 p = uQuad.xy + c * uQuad.zw;
                gl_Position = vec4(p / uRes * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAG_DOWN = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uTex;
            uniform vec2 uHalfPixel;
            uniform float uOffset;
            void main() {
                vec2 o = uHalfPixel * uOffset;
                vec4 sum = texture(uTex, vUv) * 4.0;
                sum += texture(uTex, vUv - o);
                sum += texture(uTex, vUv + o);
                sum += texture(uTex, vUv + vec2(o.x, -o.y));
                sum += texture(uTex, vUv - vec2(o.x, -o.y));
                fragColor = vec4((sum / 8.0).rgb, 1.0);
            }
            """;

    private static final String FRAG_UP = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uTex;
            uniform vec2 uHalfPixel;
            uniform float uOffset;
            void main() {
                vec2 o = uHalfPixel * uOffset;
                vec4 sum = texture(uTex, vUv + vec2(-o.x * 2.0, 0.0));
                sum += texture(uTex, vUv + vec2(-o.x, o.y)) * 2.0;
                sum += texture(uTex, vUv + vec2(0.0, o.y * 2.0));
                sum += texture(uTex, vUv + vec2(o.x, o.y)) * 2.0;
                sum += texture(uTex, vUv + vec2(o.x * 2.0, 0.0));
                sum += texture(uTex, vUv + vec2(o.x, -o.y)) * 2.0;
                sum += texture(uTex, vUv + vec2(0.0, -o.y * 2.0));
                sum += texture(uTex, vUv + vec2(-o.x, -o.y)) * 2.0;
                fragColor = vec4((sum / 12.0).rgb, 1.0);
            }
            """;

    /** Full screen blurred scene with a fade amount and darkening (the menu backdrop). */
    private static final String FRAG_BACKDROP = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uBlur;
            uniform float uAmount;
            uniform float uDim;
            uniform float uLight;
            void main() {
                vec3 c = texture(uBlur, vUv).rgb;
                float l = dot(c, vec3(0.299, 0.587, 0.114));
                c = mix(vec3(l), c, 0.85);
                c = uLight > 0.5 ? mix(c, vec3(0.80, 0.81, 0.84), uDim) : c * (1.0 - uDim);
                fragColor = vec4(c, uAmount);
            }
            """;

    /**
     * Glass for an arbitrary shape given as a coverage mask (used for text). The bevel normal comes from the gradient of
     * the mask sampled on two rings, which makes letters look like thick rounded glass.
     */
    private static final String FRAG_MASK = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uBlur;
            uniform sampler2D uSharp;
            uniform sampler2D uMask;
            uniform vec2 uRes;
            uniform float uBevel;
            uniform float uRefract;
            uniform float uAlpha;
            uniform float uTime;

            float m(vec2 p) { return texture(uMask, p / uRes).a; }

            // soft height field from the mip chain of the mask (a smooth blur), so strokes become rounded domes
            float height(vec2 p) {
                vec2 uv = p / uRes;
                float L = log2(max(uBevel * 0.9, 1.0));
                float h = 0.0, w = 0.0;
                for (int i = 0; i < 4; i++) {
                    vec2 o = (vec2(i & 1, i >> 1) - 0.5) * exp2(L) * 0.5 / uRes;
                    h += textureLod(uMask, uv + o, L).a * 0.6 + textureLod(uMask, uv + o, L - 1.0).a * 0.4;
                    w += 1.0;
                }
                return h / w;
            }

            void main() {
                vec2 fp = gl_FragCoord.xy;
                float c = m(fp);
                if (c < 0.004) discard;
                float e = max(1.0, uBevel * 0.35);
                float hC = height(fp);
                vec2 grad = vec2(height(fp + vec2(e, 0.0)) - height(fp - vec2(e, 0.0)),
                                 height(fp + vec2(0.0, e)) - height(fp - vec2(0.0, e))) / (2.0 * e);
                vec3 n = normalize(vec3(-grad * uBevel * 2.2, 1.0));
                float fres = pow(1.0 - n.z, 1.6);

                // refraction through the dome, with dispersion
                vec2 off = n.xy * uRefract;
                vec3 body = texture(uBlur, (fp + off * 0.7) / uRes).rgb;
                vec3 bend;
                bend.r = texture(uSharp, (fp + off * 1.00) / uRes).r;
                bend.g = texture(uSharp, (fp + off * 1.12) / uRes).g;
                bend.b = texture(uSharp, (fp + off * 1.24) / uRes).b;
                vec3 col = mix(body, bend, 0.45 + 0.35 * fres);
                col = col * 1.15 + 0.035;

                // studio environment: bright overhead, dark floor, reflected more at grazing angles
                float up = n.y * 0.5 + 0.5;
                vec3 env = mix(vec3(0.05, 0.055, 0.065), vec3(0.95, 0.97, 1.0), smoothstep(0.25, 0.85, up));
                col = mix(col, env, 0.25 + 0.55 * fres);

                // key light + soft fill speculars
                vec3 L = normalize(vec3(-0.45, 0.75, 0.55));
                vec3 R = reflect(-L, n);
                float spec = pow(max(R.z, 0.0), 60.0) * 1.6 + pow(max(R.z, 0.0), 9.0) * 0.22;
                vec3 L2 = normalize(vec3(0.6, -0.5, 0.6));
                spec += pow(max(reflect(-L2, n).z, 0.0), 30.0) * 0.35;
                col += vec3(spec);

                // a moving sheen across the face
                float sweep = fract(uTime * 0.12) * 3.0 - 1.0;
                float band = exp(-pow((fp.x / uRes.x - sweep) * 7.0 - (fp.y / uRes.y) * 2.0, 2.0));
                col += band * 0.18 * (1.0 - fres);

                // crisp silhouette: a dark line just outside the bevel and a thin bright line inside it
                float edge = 1.0 - smoothstep(0.08, 0.55, hC);
                col *= 1.0 - edge * 0.45;
                col += smoothstep(0.35, 0.5, hC) * (1.0 - smoothstep(0.5, 0.65, hC)) * 0.18;

                fragColor = vec4(clamp(col, 0.0, 1.0), c * uAlpha);
            }
            """;

    private static final String FRAG_PANEL = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uBlur;
            uniform sampler2D uSharp;
            uniform vec4 uRect;      // x y w h in framebuffer pixels (origin bottom left)
            uniform float uR;        // corner radius
            uniform vec4 uR4;        // per corner radius (tl, tr, br, bl), used when x >= 0
            uniform vec2 uRes;
            uniform vec4 uTint;      // rgb + strength
            uniform float uAlpha;
            uniform float uBezel;    // width of the curved rim
            uniform float uRefract;  // maximum refraction offset in pixels
            uniform float uTime;
            uniform float uLight;    // 0 dark glass, 1 light glass
            uniform float uShadow;   // drop shadow size, 0 = none

            float cornerR(vec2 p) {
                if (uR4.x < 0.0) return uR;
                return p.x < 0.0 ? (p.y > 0.0 ? uR4.x : uR4.w) : (p.y > 0.0 ? uR4.y : uR4.z);
            }

            float sdBox(vec2 p, vec2 b, float r) {
                r = min(cornerR(p), min(b.x, b.y));
                vec2 q = abs(p) - b + r;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
            }

            void main() {
                vec2 fp = gl_FragCoord.xy;
                vec2 hs = uRect.zw * 0.5;
                vec2 p = fp - (uRect.xy + hs);
                float sd = sdBox(p, hs, uR);

                if (sd >= 0.5) {
                    if (uShadow < 0.5) discard;
                    float sds = sdBox(p + vec2(0.0, uShadow * 0.30), hs, uR);
                    float a = sds < 0.0 ? 1.0 : exp(-(sds * sds) / (0.20 * uShadow * uShadow));
                    fragColor = vec4(0.0, 0.0, 0.0, a * 0.38 * uAlpha);
                    return;
                }

                float cov = clamp(0.5 - sd, 0.0, 1.0);
                float depth = max(-sd, 0.0);
                vec2 e = vec2(1.0, 0.0);
                vec2 n = vec2(sdBox(p + e.xy, hs, uR) - sdBox(p - e.xy, hs, uR),
                              sdBox(p + e.yx, hs, uR) - sdBox(p - e.yx, hs, uR));
                n = n / max(length(n), 1e-5);

                float t = clamp(depth / uBezel, 0.0, 1.0);
                float lens = pow(1.0 - t, 2.4);                 // 1 at the rim .. 0 at the inner end of the bevel
                vec2 off = -n * lens * uRefract;                // image is pulled from inside the glass towards the rim

                vec3 body = texture(uBlur, (fp + off * 0.5) / uRes).rgb;
                // thin panels bend the blurred scene only: refracting the sharp scene through a short bevel
                // stretches hard edges behind them (hotbar, text) into visible streaks
                float sharpK = 1.0;
                vec3 bez;
                bez.r = mix(texture(uBlur, (fp + off * 1.00) / uRes).r, texture(uSharp, (fp + off * 1.00) / uRes).r, sharpK);
                bez.g = mix(texture(uBlur, (fp + off * 1.07) / uRes).g, texture(uSharp, (fp + off * 1.07) / uRes).g, sharpK);
                bez.b = mix(texture(uBlur, (fp + off * 1.14) / uRes).b, texture(uSharp, (fp + off * 1.14) / uRes).b, sharpK);
                vec3 col = mix(body, mix(body, bez, 0.62), lens);

                float lum = dot(col, vec3(0.299, 0.587, 0.114));
                col = mix(vec3(lum), col, 1.22);

                vec3 tint = uTint.rgb;
                col = mix(col, tint, uTint.a);
                col += (p.y / max(hs.y, 1.0)) * 0.035;          // light from above

                vec2 L = normalize(vec2(-0.55, 0.83));
                float nd = dot(n, L);
                float rim = exp(-depth / 1.35);
                float spec = rim * (pow(max(nd, 0.0), 2.0) * 0.80 + pow(max(-nd, 0.0), 3.0) * 0.30 + 0.12);
                float bevel = pow(1.0 - t, 3.0) * 0.09 * (0.55 + 0.45 * nd);
                float sheen = 0.020 * sin(dot(p, vec2(0.010, 0.006)) * 6.2831 - uTime * 0.5) * (0.4 + 0.6 * lens);
                float k = mix(1.0, 0.55, uLight);
                col += vec3((spec + bevel) * k + sheen);

                fragColor = vec4(clamp(col, 0.0, 1.0), cov * uAlpha);
            }
            """;

    private static final class Item {
        int kind; // 0 = panel, 1 = backdrop
        float x, y, w, h, r, alpha, tintR, tintG, tintB, tintA, refract, bezel, shadow, dim, light;
        float tl = -1, tr, br, bl;
    }

    private static final List<Item> QUEUE = new ArrayList<>();
    private static final GlState GL = new GlState();

    private static boolean failed, ready, frameReady;
    private static int width, height, levels;
    private static int vao;
    private static int progDown, progUp, progBackdrop, progPanel, progMask;
    private static int maskTex, maskFbo, maskW, maskH;
    private static int sharpTex, sharpFbo, readFbo;
    private static final int[] downTex = new int[MAX_LEVELS], downFbo = new int[MAX_LEVELS];
    private static final int[] upTex = new int[MAX_LEVELS], upFbo = new int[MAX_LEVELS];
    private static final int[] lw = new int[MAX_LEVELS], lh = new int[MAX_LEVELS];
    private static int blurTex;

    /** Blur quality 1..5 (number of downsample levels); changed from the Interface module. */
    public static int blurLevels = 2;
    public static float time;

    private Glass() {
    }

    /** Marks the glass textures stale (called on frames where nothing was captured). */
    public static void skip() {
        frameReady = false;
        QUEUE.clear();
    }

    public static boolean active() {
        return ready && frameReady && !failed;
    }

    /* ------------------------------------------------------------------ queue */

    /**
     * Queues a liquid glass panel.
     *
     * @param tint   argb tint multiplied over the glass (alpha = amount of tint)
     * @param refract refraction strength in logical units (0 = flat frosted glass)
     */
    public static void panel(float x, float y, float w, float h, float r, float alpha, int tint, float refract, float shadow, boolean light) {
        if (!active() || w < 1 || h < 1 || alpha <= 0.002f) return;
        // Callers queue in the NanoVG transform, the flush happens in frame pixels. Bake the current extra scale (the
        // larger world overlay space) right here so a queued panel can never drift away from the text drawn beside it.
        float k = Nvg.extraScale();
        x *= k; y *= k; w *= k; h *= k; r *= k; refract *= k; shadow *= k;
        Item i = new Item();
        i.x = x; i.y = y; i.w = w; i.h = h; i.r = r; i.alpha = alpha;
        i.tintR = ((tint >> 16) & 255) / 255f; i.tintG = ((tint >> 8) & 255) / 255f; i.tintB = (tint & 255) / 255f;
        i.tintA = ((tint >>> 24) & 255) / 255f;
        float small = Math.min(w, h);
        // thin panels (pills, search box, rows) get a narrow bevel and weaker bending; a full height bevel smears the whole pill
        i.bezel = Math.min(small * 0.28f, 16f);
        i.refract = refract * Math.min(1f, small / 90f);
        i.shadow = shadow; i.light = light ? 1f : 0f;
        QUEUE.add(i);
    }

    /** Glass panel with its own radius per corner (top-left, top-right, bottom-right, bottom-left). */
    public static void panelCorners(float x, float y, float w, float h, float tl, float tr, float br, float bl, float alpha, int tint, float refract, boolean light) {
        int before = QUEUE.size();
        panel(x, y, w, h, Math.max(Math.max(tl, tr), Math.max(br, bl)), alpha, tint, refract, 0, light);
        if (QUEUE.size() > before) {
            Item i = QUEUE.get(QUEUE.size() - 1);
            float k = Nvg.extraScale();
            i.tl = tl * k;
            i.tr = tr * k;
            i.br = br * k;
            i.bl = bl * k;
        }
    }

    /** Queues the full screen blurred backdrop (amount 0..1 = blur fade, dim 0..1 = darkening). */
    public static void backdrop(float amount, float dim, boolean light) {
        if (!active() || amount <= 0.002f) return;
        Item i = new Item();
        i.kind = 1; i.alpha = amount; i.dim = dim; i.light = light ? 1f : 0f;
        QUEUE.add(i);
    }

    /* ------------------------------------------------------------------ capture */

    /** Copies the scene and builds the blur chain. Call before {@link Nvg#begin} each frame that uses glass. */
    public static void capture(int fbW, int fbH) {
        frameReady = false;
        if (failed || fbW <= 8 || fbH <= 8) return;
        GL.save();
        try {
            if (!ready || fbW != width || fbH != height) {
                destroy();
                if (!create(fbW, fbH)) {
                    failed = true;
                    return;
                }
            }
            run(fbW, fbH);
            frameReady = true;
            time = (System.nanoTime() / 1_000_000L % 1_000_000L) / 1000f;
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("Liquid glass capture failed - glass disabled", t);
            try { destroy(); } catch (Throwable ignored) { }
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }

    private static final java.util.Map<Long, Integer> LOCS = new java.util.HashMap<>();

    private static int loc(int prog, String name) {
        long key = ((long) prog << 32) ^ name.hashCode();
        Integer v = LOCS.get(key);
        if (v == null) {
            v = GL20.glGetUniformLocation(prog, name);
            LOCS.put(key, v);
        }
        return v;
    }

    private static void baseState() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColorMask(true, true, true, true);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL33.glBindSampler(0, 0);
        GL33.glBindSampler(1, 0);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private static void quad(int prog, float x, float y, float w, float h, float resX, float resY) {
        GL20.glUniform4f(loc(prog, "uQuad"), x, y, w, h);
        GL20.glUniform2f(loc(prog, "uRes"), resX, resY);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void run(int fbW, int fbH) {
        baseState();
        MinecraftClient mc = MinecraftClient.getInstance();
        int srcW = fbW, srcH = fbH;
        var source = mc.getFramebuffer().getColorAttachment();
        if (mc.world != null && source instanceof net.minecraft.client.texture.GlTexture color && !color.isClosed()) {
            if (readFbo == 0) readFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color.getGlId(), 0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Minecraft color attachment unavailable for glass capture");
            srcW = color.getWidth(0);
            srcH = color.getHeight(0);
        } else {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        }
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sharpFbo);
        GL30.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, fbW, fbH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

        int use = Math.max(1, Math.min(levels, blurLevels));
        float offset = 1.35f;

        GL20.glUseProgram(progDown);
        GL20.glUniform1i(loc(progDown, "uTex"), 0);
        int locHalf = loc(progDown, "uHalfPixel");
        int locOff = loc(progDown, "uOffset");
        int srcTex = sharpTex, srcLw = fbW, srcLh = fbH;
        for (int i = 0; i < use; i++) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, downFbo[i]);
            GL11.glViewport(0, 0, lw[i], lh[i]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, srcTex);
            GL20.glUniform2f(locHalf, 0.5f / srcLw, 0.5f / srcLh);
            GL20.glUniform1f(locOff, offset);
            quad(progDown, 0, 0, 1, 1, 1, 1);
            srcTex = downTex[i]; srcLw = lw[i]; srcLh = lh[i];
        }

        GL20.glUseProgram(progUp);
        GL20.glUniform1i(loc(progUp, "uTex"), 0);
        locHalf = loc(progUp, "uHalfPixel");
        locOff = loc(progUp, "uOffset");
        for (int i = use - 2; i >= 0; i--) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, upFbo[i]);
            GL11.glViewport(0, 0, lw[i], lh[i]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, srcTex);
            GL20.glUniform2f(locHalf, 0.5f / srcLw, 0.5f / srcLh);
            GL20.glUniform1f(locOff, offset);
            quad(progUp, 0, 0, 1, 1, 1, 1);
            srcTex = upTex[i]; srcLw = lw[i]; srcLh = lh[i];
        }
        blurTex = srcTex;
    }

    /* ------------------------------------------------------------------ draw */

    /** Draws everything queued since the last flush. Called by {@link Nvg}. */
    static void flush(int fbW, int fbH, float pxScale, float logicalH) {
        if (QUEUE.isEmpty()) return;
        if (!active()) {
            QUEUE.clear();
            return;
        }
        GL.save();
        try {
            baseState();
            GL11.glViewport(0, 0, fbW, fbH);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, downTex[0] != 0 ? downTex[0] : sharpTex); // lightly blurred: sharp edges smear into streaks along the rim
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, blurTex);

            for (Item i : QUEUE) {
                if (i.kind == 1) {
                    GL20.glUseProgram(progBackdrop);
                    GL20.glUniform1i(loc(progBackdrop, "uBlur"), 0);
                    GL20.glUniform1f(loc(progBackdrop, "uAmount"), i.alpha);
                    GL20.glUniform1f(loc(progBackdrop, "uDim"), i.dim);
                    GL20.glUniform1f(loc(progBackdrop, "uLight"), i.light);
                    quad(progBackdrop, 0, 0, fbW, fbH, fbW, fbH);
                    continue;
                }
                float px = i.x * pxScale, pw = i.w * pxScale, ph = i.h * pxScale;
                float py = fbH - (i.y + i.h) * pxScale;
                float pad = i.shadow * pxScale * 1.8f + 2f;
                GL20.glUseProgram(progPanel);
                GL20.glUniform1i(loc(progPanel, "uBlur"), 0);
                GL20.glUniform1i(loc(progPanel, "uSharp"), 1);
                GL20.glUniform4f(loc(progPanel, "uRect"), px, py, pw, ph);
                GL20.glUniform1f(loc(progPanel, "uR"), Math.min(i.r * pxScale, Math.min(pw, ph) * 0.5f));
                GL20.glUniform4f(loc(progPanel, "uR4"), i.tl < 0 ? -1f : i.tl * pxScale, i.tr * pxScale, i.br * pxScale, i.bl * pxScale);
                GL20.glUniform4f(loc(progPanel, "uTint"), i.tintR, i.tintG, i.tintB, i.tintA);
                GL20.glUniform1f(loc(progPanel, "uAlpha"), i.alpha);
                GL20.glUniform1f(loc(progPanel, "uBezel"), i.bezel * pxScale);
                GL20.glUniform1f(loc(progPanel, "uRefract"), i.refract * pxScale);
                GL20.glUniform1f(loc(progPanel, "uTime"), time);
                GL20.glUniform1f(loc(progPanel, "uLight"), i.light);
                GL20.glUniform1f(loc(progPanel, "uShadow"), i.shadow * pxScale);
                quad(progPanel, px - pad, py - pad, pw + pad * 2f, ph + pad * 2f, fbW, fbH);
            }
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("Liquid glass draw failed - glass disabled", t);
        } finally {
            QUEUE.clear();
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }

    /* ------------------------------------------------------------------ resources */

    private static boolean create(int fbW, int fbH) {
        width = fbW;
        height = fbH;
        if (progPanel == 0) {
            progDown = program(VERT, FRAG_DOWN);
            progUp = program(VERT, FRAG_UP);
            progBackdrop = program(VERT, FRAG_BACKDROP);
            progPanel = program(VERT, FRAG_PANEL);
            progMask = program(VERT, FRAG_MASK);
            if (progDown == 0 || progUp == 0 || progBackdrop == 0 || progPanel == 0 || progMask == 0) return false;
        }
        if (vao == 0) vao = GL30.glGenVertexArrays();

        sharpTex = texture(fbW, fbH);
        sharpFbo = fbo(sharpTex);

        levels = 0;
        int w = fbW, h = fbH;
        for (int i = 0; i < MAX_LEVELS; i++) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            if (i > 0 && (w < 4 || h < 4)) break;
            lw[i] = w;
            lh[i] = h;
            downTex[i] = texture(w, h);
            downFbo[i] = fbo(downTex[i]);
            upTex[i] = texture(w, h);
            upFbo[i] = fbo(upTex[i]);
            levels++;
        }
        ready = true;
        return true;
    }

    private static int texture(int w, int h) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, 0x8370);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, 0x8370);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_MAX_LEVEL, 0);
        return t;
    }

    private static final int GL12_BASE_LEVEL = 0x813C, GL12_MAX_LEVEL = 0x813D;

    private static int fbo(int tex) {
        int f = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, f);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("glass framebuffer incomplete");
        return f;
    }

    /** Renders a coverage mask with the given NanoVG drawing (white = glass). Called by {@link Nvg#glassText}. */
    static void renderMask(int fbW, int fbH, Runnable draw) {
        if (maskTex == 0 || maskW != fbW || maskH != fbH) {
            if (maskTex != 0) GL11.glDeleteTextures(maskTex);
            if (maskFbo != 0) GL30.glDeleteFramebuffers(maskFbo);
            maskTex = texture(fbW, fbH);
            maskFbo = fbo(maskTex);
            maskW = fbW;
            maskH = fbH;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, maskFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_MAX_LEVEL, 0);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        try {
            draw.run();
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            // mip chain = progressively blurred mask, sampled by the bevel shader
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12_MAX_LEVEL, 10);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        }
    }

    /** Draws the last rendered mask as liquid glass. bevel / refract in framebuffer pixels. */
    static void drawMask(int fbW, int fbH, float bevel, float refract, float alpha) {
        if (!active() || maskTex == 0) return;
        GL.save();
        try {
            baseState();
            GL11.glViewport(0, 0, fbW, fbH);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, maskTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sharpTex);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, blurTex);
            GL20.glUseProgram(progMask);
            GL20.glUniform1i(loc(progMask, "uBlur"), 0);
            GL20.glUniform1i(loc(progMask, "uSharp"), 1);
            GL20.glUniform1i(loc(progMask, "uMask"), 2);
            GL20.glUniform1f(loc(progMask, "uBevel"), bevel);
            GL20.glUniform1f(loc(progMask, "uRefract"), refract);
            GL20.glUniform1f(loc(progMask, "uAlpha"), alpha);
            GL20.glUniform1f(loc(progMask, "uTime"), time);
            quad(progMask, 0, 0, fbW, fbH, fbW, fbH);
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("glass text failed - glass disabled", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }

    private static void destroy() {
        if (sharpTex != 0) GL11.glDeleteTextures(sharpTex);
        if (sharpFbo != 0) GL30.glDeleteFramebuffers(sharpFbo);
        sharpTex = sharpFbo = 0;
        for (int i = 0; i < MAX_LEVELS; i++) {
            if (downTex[i] != 0) GL11.glDeleteTextures(downTex[i]);
            if (upTex[i] != 0) GL11.glDeleteTextures(upTex[i]);
            if (downFbo[i] != 0) GL30.glDeleteFramebuffers(downFbo[i]);
            if (upFbo[i] != 0) GL30.glDeleteFramebuffers(upFbo[i]);
            downTex[i] = upTex[i] = downFbo[i] = upFbo[i] = 0;
        }
        ready = false;
        levels = 0;
    }

    static int program(String vs, String fs) {
        int v = shader(GL20.GL_VERTEX_SHADER, vs), f = shader(GL20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == 0) {
            Tenacity.LOG.error("glass shader link failed: {}", GL20.glGetProgramInfoLog(p));
            GL20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int shader(int type, String src) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, src);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == 0) {
            Tenacity.LOG.error("glass shader compile failed: {}", GL20.glGetShaderInfoLog(s));
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}

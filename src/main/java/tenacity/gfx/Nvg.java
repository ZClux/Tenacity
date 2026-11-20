package tenacity.gfx;

import net.minecraft.client.util.Window;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryUtil;
import tenacity.Tenacity;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.nanovg.NanoVGGL3.*;

/** Thin immediate-mode wrapper around LWJGL NanoVG (GL3 backend). Font: Inter. */
public final class Nvg {
    public static final int LEFT = NVG_ALIGN_LEFT | NVG_ALIGN_MIDDLE;
    public static final int RIGHT = NVG_ALIGN_RIGHT | NVG_ALIGN_MIDDLE;
    public static final int CENTER = NVG_ALIGN_CENTER | NVG_ALIGN_MIDDLE;

    public static long ctx;
    public static int REG, MED, SEMI;
    /** Logical size of the current frame (window units / ui scale). */
    public static float w, h;
    /** Framebuffer pixels per logical unit. */
    public static float pxScale = 1f;

    private static final NVGColor C1 = NVGColor.create();
    private static final NVGColor C2 = NVGColor.create();
    private static final NVGPaint PAINT = NVGPaint.create();
    private static final GlState GL = new GlState();
    private static final ByteBuffer[] FONT_DATA = new ByteBuffer[3];
    private static final Map<String, Float> WIDTHS = new HashMap<>();
    private static boolean failed;
    private static boolean inFrame;
    private static float winW, winH, uiScale = 1f;
    private static int fbW, fbH;
    private static final float[] ASTACK = new float[64];
    private static int asp;
    private static float curAlpha = 1f;

    private Nvg() {
    }

    public static boolean init() {
        if (ctx != 0) return true;
        if (failed) return false;
        try {
            ctx = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
            if (ctx == 0) throw new IllegalStateException("nvgCreate returned 0");
            REG = font("inter-regular", "Inter-Regular.ttf", 0);
            MED = font("inter-medium", "Inter-Medium.ttf", 1);
            SEMI = font("inter-semibold", "Inter-SemiBold.ttf", 2);
            Tenacity.LOG.info("NanoVG initialised (Inter)");
            return true;
        } catch (Throwable t) {
            failed = true;
            ctx = 0;
            Tenacity.LOG.error("NanoVG failed to initialise - overlay disabled", t);
            return false;
        }
    }

    private static int font(String name, String file, int slot) throws Exception {
        try (InputStream in = Nvg.class.getResourceAsStream("/assets/tenacity/fonts/" + file)) {
            if (in == null) throw new IllegalStateException("missing font " + file);
            byte[] bytes = in.readAllBytes();
            ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
            buf.put(bytes).flip();
            FONT_DATA[slot] = buf;
            int id = nvgCreateFontMem(ctx, name, buf, false);
            if (id < 0) throw new IllegalStateException("nvgCreateFontMem failed for " + file);
            return id;
        }
    }

    /* ------------------------------------------------------------------ frame */

    public static boolean inFrame() {
        return inFrame;
    }

    public static void begin(Window win, float scale) {
        GL.save();
        fbW = win.getFramebufferWidth();
        fbH = win.getFramebufferHeight();
        GL.prepare(fbW, fbH);
        winW = win.getWidth();
        winH = win.getHeight();
        uiScale = scale;
        extra = 1f;
        layers = 0;
        pxScale = fbW / winW * scale;
        w = winW / scale;
        h = winH / scale;
        beginFrame();
        inFrame = true;
        asp = 0;
        curAlpha = 1f;
        if (WIDTHS.size() > 6000) WIDTHS.clear();
    }

    private static void beginFrame() {
        nvgBeginFrame(ctx, winW, winH, fbW / winW);
        if (uiScale != 1f) nvgScale(ctx, uiScale, uiScale);
        if (extra != 1f) nvgScale(ctx, extra, extra);
    }

    /** Scale applied on top of the frame scale; re-applied after every {@link #layer()} flush. */
    private static float extra = 1f;
    private static int layers;
    private static final float[] LSCALE = new float[8];

    /**
     * Draws the next block in a larger coordinate space (frame scale * k) without moving anything that is already
     * projected: w, h and pxScale move with the transform, so world overlays keep their exact pixels.
     */
    /** Extra transform on top of the frame scale (world overlays draw in a larger space than the ui). */
    public static float extraScale() {
        return extra;
    }

    public static void pushScale(float k) {
        if (!inFrame || layers >= LSCALE.length) return;
        LSCALE[layers++] = k;
        if (k == 1f) return;
        // pxScale deliberately stays on the frame scale: liquid glass is queued in coordinate space and flushed later,
        // so Theme.gs bakes this scale into the queue instead (see Overlay.render)
        extra *= k;
        w /= k;
        h /= k;
        nvgScale(ctx, k, k);
    }

    public static void popScale() {
        if (!inFrame || layers <= 0) return;
        float k = LSCALE[--layers];
        if (k == 1f) return;
        extra /= k;
        w *= k;
        h *= k;
        nvgScale(ctx, 1f / k, 1f / k);
    }

    /** Flushes queued glass and the NanoVG content so far; everything drawn next lands on top of it. */
    public static void layer() {
        if (!inFrame) return;
        Glass.flush(fbW, fbH, pxScale, h);
        nvgEndFrame(ctx);
        GL.prepare(fbW, fbH);
        beginFrame();
        asp = 0;
        curAlpha = 1f;
    }

    /**
     * Liquid glass text: the text is rendered into a mask and drawn through the glass shader right away, on top of
     * everything drawn so far in this frame. Logical coordinates, centred on (x, y).
     */
    public static void glassText(String s, float x, float y, float size, int font, float alpha) {
        if (!inFrame || !Glass.active() || alpha <= 0.003f) {
            text(s, x, y, size, font, CENTER, alpha(0xFFFFFFFF, alpha));
            return;
        }
        Glass.flush(fbW, fbH, pxScale, h);
        nvgEndFrame(ctx);
        GL.prepare(fbW, fbH);
        Glass.renderMask(fbW, fbH, () -> {
            nvgBeginFrame(ctx, winW, winH, fbW / winW);
            if (uiScale != 1f) nvgScale(ctx, uiScale, uiScale);
            text(s, x, y, size, font, CENTER, 0xFFFFFFFF);
            nvgEndFrame(ctx);
        });
        Glass.drawMask(fbW, fbH, size * 0.085f * pxScale, size * 0.07f * pxScale, alpha);
        GL.prepare(fbW, fbH);
        beginFrame();
        asp = 0;
        curAlpha = 1f;
    }

    public static void end() {
        Glass.flush(fbW, fbH, pxScale, h);
        nvgEndFrame(ctx);
        inFrame = false;
        GL.restore();
    }

    /* ------------------------------------------------------------------ colors */

    public static int rgba(int r, int g, int b, int a) {
        return (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
    }

    public static int alpha(int argb, float mul) {
        int a = Math.round(((argb >>> 24) & 255) * Math.max(0f, Math.min(1f, mul)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (int) (((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        int ar = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int ag = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int ab = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return rgba(ar, ag, ab, aa);
    }

    private static NVGColor col(NVGColor c, int argb) {
        c.r(((argb >> 16) & 255) / 255f);
        c.g(((argb >> 8) & 255) / 255f);
        c.b((argb & 255) / 255f);
        c.a(((argb >>> 24) & 255) / 255f);
        return c;
    }

    /* ------------------------------------------------------------------ shapes */

    public static void rect(float x, float y, float w, float h, int color) {
        nvgBeginPath(ctx);
        nvgRect(ctx, x, y, w, h);
        nvgFillColor(ctx, col(C1, color));
        nvgFill(ctx);
    }

    public static void rrect(float x, float y, float w, float h, float r, int color) {
        if (w <= 0 || h <= 0) return;
        nvgBeginPath(ctx);
        nvgRoundedRect(ctx, x, y, w, h, Math.min(r, Math.min(w, h) / 2f));
        nvgFillColor(ctx, col(C1, color));
        nvgFill(ctx);
    }

    public static void rrect(float x, float y, float w, float h, float tl, float tr, float br, float bl, int color) {
        if (w <= 0 || h <= 0) return;
        nvgBeginPath(ctx);
        nvgRoundedRectVarying(ctx, x, y, w, h, tl, tr, br, bl);
        nvgFillColor(ctx, col(C1, color));
        nvgFill(ctx);
    }

    public static void rrectStrokeVarying(float x, float y, float w, float h, float tl, float tr, float br, float bl, float width, int color) {
        if (w <= 0 || h <= 0) return;
        nvgBeginPath(ctx);
        nvgRoundedRectVarying(ctx, x + width / 2f, y + width / 2f, w - width, h - width, tl, tr, br, bl);
        nvgStrokeWidth(ctx, width);
        nvgStrokeColor(ctx, col(C1, color));
        nvgStroke(ctx);
    }

    public static void rrectGradient(float x, float y, float w, float h, float r, float x1, float y1, float x2, float y2, int c1, int c2) {
        if (w <= 0 || h <= 0) return;
        nvgBeginPath(ctx);
        nvgRoundedRect(ctx, x, y, w, h, Math.min(r, Math.min(w, h) / 2f));
        nvgLinearGradient(ctx, x1, y1, x2, y2, col(C1, c1), col(C2, c2), PAINT);
        nvgFillPaint(ctx, PAINT);
        nvgFill(ctx);
    }

    public static void rrectStroke(float x, float y, float w, float h, float r, float width, int color) {
        if (w <= 0 || h <= 0) return;
        nvgBeginPath(ctx);
        nvgRoundedRect(ctx, x + width / 2f, y + width / 2f, w - width, h - width, Math.max(0, Math.min(r, Math.min(w, h) / 2f) - width / 2f));
        nvgStrokeWidth(ctx, width);
        nvgStrokeColor(ctx, col(C1, color));
        nvgStroke(ctx);
    }

    public static void rrectStrokeGradient(float x, float y, float w, float h, float r, float width, float x1, float y1, float x2, float y2, int c1, int c2) {
        if (w <= 0 || h <= 0) return;
        nvgBeginPath(ctx);
        nvgRoundedRect(ctx, x + width / 2f, y + width / 2f, w - width, h - width, Math.max(0, Math.min(r, Math.min(w, h) / 2f) - width / 2f));
        nvgLinearGradient(ctx, x1, y1, x2, y2, col(C1, c1), col(C2, c2), PAINT);
        nvgStrokeWidth(ctx, width);
        nvgStrokePaint(ctx, PAINT);
        nvgStroke(ctx);
    }

    public static void circle(float cx, float cy, float r, int color) {
        nvgBeginPath(ctx);
        nvgCircle(ctx, cx, cy, r);
        nvgFillColor(ctx, col(C1, color));
        nvgFill(ctx);
    }

    /** Additive blending (light adds up like a real glow) or back to normal. */
    public static void additive(boolean on) {
        if (on) nvgGlobalCompositeBlendFunc(ctx, NVG_ONE, NVG_ONE);
        else nvgGlobalCompositeOperation(ctx, NVG_SOURCE_OVER);
    }

    /**
     * Glow around a line in the style of Crypto's GlowLine: a gaussian halo made of stacked additive strokes, widest and
     * faintest outside, brightest at the core. Draws only the halo; the crisp core is drawn by the caller.
     */
    public static void glowStroke(float x1, float y1, float x2, float y2, float core, float glow, int color, float strength) {
        if (glow <= core) return;
        additive(true);
        int steps = 7;
        for (int i = steps; i >= 1; i--) {
            float f = i / (float) steps;
            float wdt = core + (glow - core) * f;
            float a = (float) Math.exp(-4.0 * f * f) * 0.16f * strength;
            line(x1, y1, x2, y2, wdt, alpha(color, a));
        }
        additive(false);
    }

    /** Filled circle with a radial gradient from the centre colour to the edge colour. */
    public static void glowDisc(float cx, float cy, float r, int inner, int outer) {
        nvgBeginPath(ctx);
        nvgCircle(ctx, cx, cy, r);
        nvgRadialGradient(ctx, cx, cy, 0, r, col(C1, inner), col(C2, outer), PAINT);
        nvgFillPaint(ctx, PAINT);
        nvgFill(ctx);
    }

    public static void lineGradient(float x1, float y1, float x2, float y2, float width, int c1, int c2) {
        nvgBeginPath(ctx);
        nvgMoveTo(ctx, x1, y1);
        nvgLineTo(ctx, x2, y2);
        nvgLineCap(ctx, NVG_ROUND);
        nvgStrokeWidth(ctx, width);
        nvgLinearGradient(ctx, x1, y1, x2, y2, col(C1, c1), col(C2, c2), PAINT);
        nvgStrokePaint(ctx, PAINT);
        nvgStroke(ctx);
    }

    public static void circleStroke(float cx, float cy, float r, float width, int color) {
        nvgBeginPath(ctx);
        nvgCircle(ctx, cx, cy, r);
        nvgStrokeWidth(ctx, width);
        nvgStrokeColor(ctx, col(C1, color));
        nvgStroke(ctx);
    }

    public static void line(float x1, float y1, float x2, float y2, float width, int color) {
        nvgBeginPath(ctx);
        nvgMoveTo(ctx, x1, y1);
        nvgLineTo(ctx, x2, y2);
        nvgLineCap(ctx, NVG_ROUND);
        nvgStrokeWidth(ctx, width);
        nvgStrokeColor(ctx, col(C1, color));
        nvgStroke(ctx);
    }

    /* path builder for icons */
    public static void beginPath() { nvgBeginPath(ctx); }
    public static void moveTo(float x, float y) { nvgMoveTo(ctx, x, y); }
    public static void lineTo(float x, float y) { nvgLineTo(ctx, x, y); }
    public static void bezierTo(float c1x, float c1y, float c2x, float c2y, float x, float y) { nvgBezierTo(ctx, c1x, c1y, c2x, c2y, x, y); }
    public static void quadTo(float cx, float cy, float x, float y) { nvgQuadTo(ctx, cx, cy, x, y); }
    public static void closePath() { nvgClosePath(ctx); }
    public static void circlePath(float cx, float cy, float r) { nvgCircle(ctx, cx, cy, r); }
    public static void rrectPath(float x, float y, float w, float h, float r) { nvgRoundedRect(ctx, x, y, w, h, r); }
    public static void arcPath(float cx, float cy, float r, float a0, float a1) { nvgArc(ctx, cx, cy, r, a0, a1, NVG_CW); }

    public static void fillPath(int color) {
        nvgFillColor(ctx, col(C1, color));
        nvgFill(ctx);
    }

    public static void fillPathGradient(float x1, float y1, float x2, float y2, int c1, int c2) {
        nvgLinearGradient(ctx, x1, y1, x2, y2, col(C1, c1), col(C2, c2), PAINT);
        nvgFillPaint(ctx, PAINT);
        nvgFill(ctx);
    }

    public static void strokePath(float width, int color) {
        nvgLineCap(ctx, NVG_ROUND);
        nvgLineJoin(ctx, NVG_ROUND);
        nvgStrokeWidth(ctx, width);
        nvgStrokeColor(ctx, col(C1, color));
        nvgStroke(ctx);
    }

    /** Draws a NanoVG image as a rounded rectangle. */
    public static void image(int img, float x, float y, float w, float h, float radius, float alpha) {
        if (img <= 0) return;
        nvgImagePattern(ctx, x, y, w, h, 0f, img, alpha, PAINT);
        nvgBeginPath(ctx);
        nvgRoundedRect(ctx, x, y, w, h, Math.min(radius, Math.min(w, h) / 2f));
        nvgFillPaint(ctx, PAINT);
        nvgFill(ctx);
    }

    /** Fills a rounded rect with a sub-rectangle (u, v, uw, vh in texels) of an image. */
    public static void imageRegion(int img, float texW, float texH, float u, float v, float uw, float vh, float x, float y, float w, float h, float radius, float alpha) {
        if (img <= 0) return;
        float sx = w / uw, sy = h / vh;
        nvgImagePattern(ctx, x - u * sx, y - v * sy, texW * sx, texH * sy, 0f, img, alpha, PAINT);
        nvgBeginPath(ctx);
        nvgRoundedRect(ctx, x, y, w, h, Math.min(radius, Math.min(w, h) / 2f));
        nvgFillPaint(ctx, PAINT);
        nvgFill(ctx);
    }

    /** Decodes an encoded image (png...) into a NanoVG image, 0 on failure. */
    public static int imageMem(byte[] data) {
        if (data == null || data.length == 0) return 0;
        ByteBuffer buf = MemoryUtil.memAlloc(data.length);
        try {
            buf.put(data).flip();
            return Math.max(0, nvgCreateImageMem(ctx, NVG_IMAGE_GENERATE_MIPMAPS, buf));
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    public static int rgbaImage(int w, int h, ByteBuffer rgba) {
        return Math.max(0, nvgCreateImageRGBA(ctx, w, h, NVG_IMAGE_GENERATE_MIPMAPS, rgba));
    }

    public static int rgbaImageNearest(int w, int h, ByteBuffer rgba) {
        return Math.max(0, nvgCreateImageRGBA(ctx, w, h, NVG_IMAGE_NEAREST, rgba));
    }

    public static void deleteImage(int img) {
        if (img > 0 && ctx != 0) nvgDeleteImage(ctx, img);
    }

    /* ------------------------------------------------------------------ clipping / alpha */

    public static void save() {
        nvgSave(ctx);
        if (asp < ASTACK.length) ASTACK[asp++] = curAlpha;
    }

    public static void restore() {
        nvgRestore(ctx);
        if (asp > 0) curAlpha = ASTACK[--asp];
    }

    public static void clip(float x, float y, float w, float h) {
        nvgIntersectScissor(ctx, x, y, Math.max(0, w), Math.max(0, h));
    }

    /** Multiplies the current layer alpha (nests correctly inside other fades). */
    public static void fade(float a) {
        curAlpha = Math.max(0f, Math.min(1f, curAlpha * Math.max(0f, Math.min(1f, a))));
        nvgGlobalAlpha(ctx, curAlpha);
    }

    public static void translate(float x, float y) { nvgTranslate(ctx, x, y); }
    public static void scale(float x, float y) { nvgScale(ctx, x, y); }
    public static void rotate(float rad) { nvgRotate(ctx, rad); }

    /* ------------------------------------------------------------------ text */

    public static void text(String s, float x, float y, float size, int font, int align, int color) {
        if (s == null || s.isEmpty()) return;
        s = tenacity.modules.NameCore.nvg(s);
        nvgFontFaceId(ctx, font);
        nvgFontSize(ctx, size);
        nvgTextAlign(ctx, align);
        nvgFillColor(ctx, col(C1, color));
        nvgText(ctx, x, y, s);
    }

    /** Text with a gaussian blur (soft shadows and glows). */
    public static void textBlur(String s, float x, float y, float size, int font, int align, int color, float blur) {
        nvgFontBlur(ctx, blur);
        text(s, x, y, size, font, align, color);
        nvgFontBlur(ctx, 0);
    }

    public static float textWidth(String s, float size, int font) {
        if (s == null || s.isEmpty()) return 0;
        s = tenacity.modules.NameCore.nvg(s);
        String key = font + ":" + size + ":" + s;
        Float cached = WIDTHS.get(key);
        if (cached != null) return cached;
        nvgFontFaceId(ctx, font);
        nvgFontSize(ctx, size);
        float width = nvgTextBounds(ctx, 0, 0, s, (FloatBuffer) null);
        WIDTHS.put(key, width);
        return width;
    }

    public static String fit(String s, float size, int font, float max) {
        if (textWidth(s, size, font) <= max) return s;
        String t = s;
        while (t.length() > 1 && textWidth(t + "...", size, font) > max) t = t.substring(0, t.length() - 1);
        return t + "...";
    }
}

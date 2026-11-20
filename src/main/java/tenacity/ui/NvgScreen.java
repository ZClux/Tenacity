package tenacity.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import tenacity.gfx.Icons;
import tenacity.gfx.Nvg;

import static org.lwjgl.nanovg.NanoVG.*;

/**
 * Screen drawn entirely by NanoVG from {@link Overlay}. Also carries the small kit the menu screens share: glass buttons with
 * hover physics, text fields, a momentum scroller, line icons and tracked display text.
 */
public abstract class NvgScreen extends Screen {
    protected float open;
    /** Left mouse button state; {@link #released} is true for the frame the button went up. */
    protected static boolean down, released;
    private static final NVGColor CA = NVGColor.create(), CB = NVGColor.create();
    private static final NVGPaint PAINT = NVGPaint.create();

    protected NvgScreen(String title) {
        super(Text.literal(title));
    }

    public abstract void draw(float dt);

    /** Call at the very end of draw(). */
    protected static void frameEnd() {
        released = false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
    }

    @Override
    protected void applyBlur(DrawContext context) {
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0) {
            Widgets.click = true;
            down = true;
        }
        if (click.button() == 1) Widgets.rclick = true;
        super.mouseClicked(click, doubled);
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (click.button() == 0) {
            down = false;
            released = true;
        }
        super.mouseReleased(click);
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double dx, double dy) {
        super.mouseDragged(click, dx, dy);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double h, double v) {
        Widgets.scroll += (float) v;
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        Widgets.keys.add(input.key());
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (input.isValidChar()) Widgets.typed.appendCodePoint(input.codepoint());
        return true;
    }

    /* ================================================================== kit */

    protected static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** Eased 0..1 progress of a stage that starts at {@code start} seconds and lasts {@code dur}. */
    protected static float stage(float t, float start, float dur) {
        return Anim.easeOut(clamp01((t - start) / dur));
    }

    protected static boolean ctrl() {
        long h = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
    }

    private static NVGColor c(NVGColor o, int argb) {
        return o.r(((argb >> 16) & 255) / 255f).g(((argb >> 8) & 255) / 255f).b((argb & 255) / 255f).a(((argb >>> 24) & 255) / 255f);
    }

    /** Rounded rect filled with a radial gradient (light spots that follow the pointer). */
    protected static void rrectRadial(float x, float y, float w, float h, float r, float cx, float cy, float rad, int inner, int outer) {
        long ctx = Nvg.ctx;
        nvgBeginPath(ctx);
        nvgRoundedRect(ctx, x, y, w, h, r);
        nvgRadialGradient(ctx, cx, cy, 0, rad, c(CA, inner), c(CB, outer), PAINT);
        nvgFillPaint(ctx, PAINT);
        nvgFill(ctx);
    }

    /** Soft ellipse (contact shadows / floor light). */
    protected static void ellipseGlow(float cx, float cy, float rx, float ry, int inner) {
        Nvg.save();
        Nvg.translate(cx, cy);
        Nvg.scale(1f, ry / rx);
        Nvg.glowDisc(0, 0, rx, inner, inner & 0x00FFFFFF);
        Nvg.restore();
    }

    /** Text with letter spacing (tracking). Alignment accounts for the spacing. */
    protected static void tracked(String s, float x, float y, float size, int font, int align, int color, float spacing) {
        if (s == null || s.isEmpty()) return;
        float w = trackedWidth(s, size, font, spacing);
        if ((align & NVG_ALIGN_CENTER) != 0) x -= w / 2f;
        else if ((align & NVG_ALIGN_RIGHT) != 0) x -= w;
        Nvg.save();
        nvgTextLetterSpacing(Nvg.ctx, spacing);
        Nvg.text(s, x, y, size, font, Nvg.LEFT, color);
        Nvg.restore();
    }

    protected static float trackedWidth(String s, float size, int font, float spacing) {
        return Nvg.textWidth(s, size, font) + spacing * Math.max(0, s.length() - 1);
    }

    /** Thin hairline that fades out at both ends. */
    protected static void hairline(float x, float y, float w, float a) {
        Nvg.lineGradient(x, y, x + w / 2f, y, 1f, Nvg.alpha(0xFFFFFFFF, 0f), Nvg.alpha(0xFFFFFFFF, a));
        Nvg.lineGradient(x + w / 2f, y, x + w, y, 1f, Nvg.alpha(0xFFFFFFFF, a), Nvg.alpha(0xFFFFFFFF, 0f));
    }

    /** Line icons: own set plus everything in {@link Icons}. Centered on (cx, cy). */
    protected static void icon(String id, float cx, float cy, float s, int col) {
        float k = s / 24f, sw = 1.8f * Math.max(0.8f, k);
        float x = cx - s / 2f, y = cy - s / 2f;
        switch (id) {
            case "plus" -> {
                Nvg.line(cx - 7 * k, cy, cx + 7 * k, cy, sw, col);
                Nvg.line(cx, cy - 7 * k, cx, cy + 7 * k, sw, col);
            }
            case "close" -> {
                Nvg.line(cx - 6 * k, cy - 6 * k, cx + 6 * k, cy + 6 * k, sw, col);
                Nvg.line(cx + 6 * k, cy - 6 * k, cx - 6 * k, cy + 6 * k, sw, col);
            }
            case "back" -> {
                Nvg.line(cx + 2.5f * k, cy - 6 * k, cx - 3.5f * k, cy, sw, col);
                Nvg.line(cx - 3.5f * k, cy, cx + 2.5f * k, cy + 6 * k, sw, col);
            }
            case "next" -> {
                Nvg.line(cx - 2.5f * k, cy - 6 * k, cx + 3.5f * k, cy, sw, col);
                Nvg.line(cx + 3.5f * k, cy, cx - 2.5f * k, cy + 6 * k, sw, col);
            }
            case "arrow" -> {
                Nvg.line(cx - 6 * k, cy, cx + 6 * k, cy, sw, col);
                Nvg.line(cx + 1.5f * k, cy - 4.5f * k, cx + 6 * k, cy, sw, col);
                Nvg.line(cx + 1.5f * k, cy + 4.5f * k, cx + 6 * k, cy, sw, col);
            }
            case "trash" -> {
                Nvg.line(x + 4 * k, y + 6.5f * k, x + 20 * k, y + 6.5f * k, sw, col);
                Nvg.line(x + 9.5f * k, y + 4 * k, x + 14.5f * k, y + 4 * k, sw, col);
                Nvg.beginPath();
                Nvg.moveTo(x + 6 * k, y + 6.5f * k);
                Nvg.lineTo(x + 7 * k, y + 20 * k);
                Nvg.lineTo(x + 17 * k, y + 20 * k);
                Nvg.lineTo(x + 18 * k, y + 6.5f * k);
                Nvg.strokePath(sw, col);
                Nvg.line(x + 10.3f * k, y + 10 * k, x + 10.3f * k, y + 16.5f * k, sw * 0.85f, col);
                Nvg.line(x + 13.7f * k, y + 10 * k, x + 13.7f * k, y + 16.5f * k, sw * 0.85f, col);
            }
            case "edit" -> {
                Nvg.beginPath();
                Nvg.moveTo(x + 15.5f * k, y + 4.5f * k);
                Nvg.lineTo(x + 19.5f * k, y + 8.5f * k);
                Nvg.lineTo(x + 8.5f * k, y + 19.5f * k);
                Nvg.lineTo(x + 4.5f * k, y + 19.5f * k);
                Nvg.lineTo(x + 4.5f * k, y + 15.5f * k);
                Nvg.closePath();
                Nvg.strokePath(sw, col);
                Nvg.line(x + 13 * k, y + 7 * k, x + 17 * k, y + 11 * k, sw * 0.85f, col);
            }
            case "refresh" -> {
                Nvg.beginPath();
                Nvg.arcPath(cx, cy, 7 * k, (float) Math.toRadians(-60), (float) Math.toRadians(230));
                Nvg.strokePath(sw, col);
                float ax = cx + 7 * k * (float) Math.cos(Math.toRadians(-60)), ay = cy + 7 * k * (float) Math.sin(Math.toRadians(-60));
                Nvg.line(ax, ay, ax - 4.5f * k, ay - 0.6f * k, sw, col);
                Nvg.line(ax, ay, ax + 0.6f * k, ay + 4.5f * k, sw, col);
            }
            case "direct" -> {
                Nvg.beginPath();
                Nvg.moveTo(x + 13 * k, y + 4.5f * k);
                Nvg.lineTo(x + 19.5f * k, y + 4.5f * k);
                Nvg.lineTo(x + 19.5f * k, y + 19.5f * k);
                Nvg.lineTo(x + 13 * k, y + 19.5f * k);
                Nvg.strokePath(sw, col);
                Nvg.line(x + 4 * k, cy, x + 14 * k, cy, sw, col);
                Nvg.line(x + 10 * k, cy - 4 * k, x + 14 * k, cy, sw, col);
                Nvg.line(x + 10 * k, cy + 4 * k, x + 14 * k, cy, sw, col);
            }
            case "people" -> {
                Nvg.circleStroke(cx, y + 8.5f * k, 3.6f * k, sw, col);
                Nvg.beginPath();
                Nvg.arcPath(cx, y + 21 * k, 7 * k, (float) Math.PI, (float) (Math.PI * 2));
                Nvg.strokePath(sw, col);
            }
            case "cube" -> {
                Nvg.beginPath();
                Nvg.moveTo(cx, y + 3.5f * k);
                Nvg.lineTo(x + 19.5f * k, y + 8 * k);
                Nvg.lineTo(x + 19.5f * k, y + 16.5f * k);
                Nvg.lineTo(cx, y + 21 * k);
                Nvg.lineTo(x + 4.5f * k, y + 16.5f * k);
                Nvg.lineTo(x + 4.5f * k, y + 8 * k);
                Nvg.closePath();
                Nvg.strokePath(sw, col);
                Nvg.beginPath();
                Nvg.moveTo(x + 4.5f * k, y + 8 * k);
                Nvg.lineTo(cx, y + 12.5f * k);
                Nvg.lineTo(x + 19.5f * k, y + 8 * k);
                Nvg.moveTo(cx, y + 12.5f * k);
                Nvg.lineTo(cx, y + 21 * k);
                Nvg.strokePath(sw, col);
            }
            default -> Icons.draw(id, x, y, s, col);
        }
    }

    /** Spinner arc. */
    protected static void spinner(float cx, float cy, float r, float w, float a) {
        float t = (System.nanoTime() % 100_000_000_000L) / 1e9f * 5.2f;
        Nvg.circleStroke(cx, cy, r, w, Nvg.alpha(0xFFFFFFFF, 0.12f * a));
        Nvg.beginPath();
        float sweep = 1.2f + 0.9f * (float) Math.sin(t * 0.6f);
        Nvg.arcPath(cx, cy, r, t, t + sweep);
        Nvg.strokePath(w, Nvg.alpha(0xFFFFFFFF, 0.95f * a));
    }

    /**
     * Glass button with hover physics: it grows a touch, a light follows the pointer across its surface, and it dips when
     * pressed. style 0 = glass, 1 = solid white (primary), 2 = quiet (no glass, hover plate only - for buttons on glass).
     * Returns true on click (the click is consumed).
     */
    protected static boolean button(String id, String label, String icn, float x, float y, float w, float h, int style, boolean enabled, float a) {
        if (a <= 0.003f) return false;
        boolean hov = enabled && Widgets.in(x, y, w, h);
        float t = Widgets.anim(id, hov ? 1f : 0f, 14f);
        boolean clicked = hov && Widgets.click;
        if (clicked) {
            Widgets.click = false;
            Widgets.anim(id + "#p", 1f, 1000f);
        }
        float p = Widgets.anim(id + "#p", 0f, 9f);
        float s = style == 2 ? 1f - 0.04f * p : 1f + 0.03f * t - 0.05f * p;
        float lift = style == 2 ? 0 : t * 1.5f;
        float cx = x + w / 2f, cy = y + h / 2f;
        float sw = w * s, sh = h * s, sx = cx - sw / 2f, sy = cy - sh / 2f - lift;
        float r = Math.min(sh / 2f, 15f);
        float da = enabled ? a : a * 0.4f;
        // light that follows the pointer, clamped to the button
        float lx = Math.max(sx, Math.min(sx + sw, Widgets.mx)), ly = Math.max(sy, Math.min(sy + sh, Widgets.my));
        int fg;
        if (style == 1 && !enabled) style = 2;
        if (style == 1) {
            Nvg.rrect(sx + 2, sy + 5, sw - 4, sh, r, Nvg.alpha(0xFF000000, 0.3f * da));
            for (int i = 3; i >= 1; i--)
                Nvg.rrect(sx - i * 3 * t, sy - i * 3 * t, sw + i * 6 * t, sh + i * 6 * t, r + i * 3 * t, Nvg.alpha(0xFFFFFFFF, 0.03f * t * da));
            Nvg.rrectGradient(sx, sy, sw, sh, r, sx, sy, sx, sy + sh, Nvg.alpha(0xFFFFFFFF, da), Nvg.alpha(0xFFD9DCE2, da));
            rrectRadial(sx, sy, sw, sh, r, lx, ly, sw * 0.5f, Nvg.alpha(0xFFFFFFFF, 0.9f * t * da), Nvg.alpha(0xFFFFFFFF, 0f));
            fg = Nvg.alpha(0xFF0A0B0E, da);
        } else {
            if (style == 0) Widgets.glass(sx, sy, sw, sh, r, da);
            Nvg.rrect(sx, sy, sw, sh, r, Nvg.alpha(0xFFFFFFFF, (style == 2 ? 0.07f : 0.05f) * t * da));
            rrectRadial(sx, sy, sw, sh, r, lx, ly, Math.max(sw, sh) * 0.55f, Nvg.alpha(0xFFFFFFFF, 0.13f * t * da), Nvg.alpha(0xFFFFFFFF, 0f));
            if (t > 0.01f && style == 0)
                Nvg.rrectStrokeGradient(sx, sy, sw, sh, r, 1f, lx - sw * 0.3f, ly - sh, lx + sw * 0.3f, ly + sh, Nvg.alpha(0xFFFFFFFF, 0.45f * t * da), Nvg.alpha(0xFFFFFFFF, 0.04f * t * da));
            fg = Nvg.alpha(0xFFFFFFFF, da * (0.8f + 0.2f * t));
        }
        Nvg.save();
        Nvg.translate(cx, cy - lift);
        Nvg.scale(s, s);
        float fs = Math.min(13.5f, h * 0.34f);
        float tw = label == null ? 0 : Nvg.textWidth(label, fs, style == 1 ? Nvg.SEMI : Nvg.MED);
        float iw = icn == null ? 0 : (label == null ? Math.min(20f, h * 0.45f) : fs * 1.25f);
        float gap = icn != null && label != null && !label.isEmpty() ? 8 : 0;
        float x0 = -(tw + iw + gap) / 2f;
        if (icn != null) icon(icn, x0 + iw / 2f, 0, iw, fg);
        if (label != null) Nvg.text(label, x0 + iw + gap, 0.5f, fs, style == 1 ? Nvg.SEMI : Nvg.MED, Nvg.LEFT, fg);
        Nvg.restore();
        return clicked && enabled;
    }

    /** Single line text input. */
    protected static final class Field {
        public String text = "";
        public boolean focus;
        public final String id;

        public Field(String id) {
            this.id = id;
        }

        /** Applies this frame's keyboard input when focused. Returns true if Enter was pressed. */
        public boolean input() {
            if (!focus) return false;
            boolean enter = false;
            for (int k : Widgets.keys) {
                if (k == GLFW.GLFW_KEY_BACKSPACE && !text.isEmpty()) {
                    if (ctrl()) {
                        String s = text.stripTrailing();
                        int i = Math.max(s.lastIndexOf(' '), Math.max(s.lastIndexOf('.'), s.lastIndexOf(':')));
                        text = i < 0 || i == s.length() - 1 ? s.substring(0, Math.max(0, i)) : s.substring(0, i + 1);
                    } else text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
                } else if (k == GLFW.GLFW_KEY_V && ctrl()) {
                    String clip = MinecraftClient.getInstance().keyboard.getClipboard();
                    if (clip != null) text += clip.replace("\n", "").replace("\r", "").trim();
                } else if (k == GLFW.GLFW_KEY_A && ctrl()) text = "";
                else if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) enter = true;
            }
            if (!Widgets.typed.isEmpty()) text += Widgets.typed;
            if (text.length() > 256) text = text.substring(0, 256);
            return enter;
        }

        /** Draws the field; clicking it focuses it (returns true when it was clicked). */
        public boolean draw(float x, float y, float w, float h, String placeholder, String icn, float a) {
            boolean hov = Widgets.in(x, y, w, h);
            boolean clicked = hov && Widgets.click;
            if (clicked) {
                Widgets.click = false;
                focus = true;
            }
            float f = Widgets.anim(id + "#f", focus ? 1f : 0f, 14f);
            float hv = Widgets.anim(id + "#h", hov ? 1f : 0f, 14f);
            float r = Math.min(h / 2f, 12f);
            Nvg.rrect(x, y, w, h, r, Nvg.alpha(0xFF000000, (0.22f + 0.08f * f) * a));
            Nvg.rrect(x, y, w, h, r, Nvg.alpha(0xFFFFFFFF, (0.035f + 0.03f * hv + 0.02f * f) * a));
            Nvg.rrectStroke(x + 0.5f, y + 0.5f, w - 1, h - 1, r, 1f, Nvg.alpha(0xFFFFFFFF, (0.09f + 0.06f * hv + 0.4f * f) * a));
            float tx = x + 14;
            if (icn != null) {
                icon(icn, x + 14 + 8, y + h / 2f, 16, Nvg.alpha(0xFFFFFFFF, (0.45f + 0.4f * f) * a));
                tx += 26;
            }
            float fs = 13.5f, maxW = x + w - 14 - tx;
            Nvg.save();
            Nvg.clip(tx - 2, y, maxW + 4, h);
            float tw = Nvg.textWidth(text, fs, Nvg.MED);
            float off = Math.max(0, tw - maxW + 4);
            if (text.isEmpty()) Nvg.text(placeholder, tx + (focus ? 6 : 0), y + h / 2f + 0.5f, fs, Nvg.REG, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.32f * a));
            else Nvg.text(text, tx - off, y + h / 2f + 0.5f, fs, Nvg.MED, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, a));
            if (focus) {
                float blink = 0.5f + 0.5f * (float) Math.cos(System.nanoTime() / 1e9 * Math.PI * 1.6);
                Nvg.rrect(tx - off + tw + 1.5f, y + h / 2f - 8, 1.5f, 16, 0.75f, Nvg.alpha(0xFFFFFFFF, blink * a));
            }
            Nvg.restore();
            return clicked;
        }
    }

    /** Vertical scroller: wheel, drag with momentum and rubber band edges. */
    protected static final class Scroller {
        public float pos, target, vel;
        private boolean armed, dragging;
        private float startY, startTarget, lastY;
        /** True for the frame a press inside the area was released without dragging. */
        public boolean tap;

        public void update(float x, float y, float w, float h, float content, boolean enabled) {
            float max = Math.max(0, content - h);
            float dt = Math.max(0.001f, Anim.dt);
            tap = false;
            boolean in = enabled && Widgets.in(x, y, w, h);
            if (in && Widgets.scroll != 0) {
                target = Math.max(0, Math.min(max, target - Widgets.scroll * 64));
                vel = 0;
            }
            if (in && Widgets.click) {
                armed = true;
                dragging = false;
                startY = lastY = Widgets.my;
                startTarget = target;
                vel = 0;
            }
            if (armed && down) {
                if (!dragging && Math.abs(Widgets.my - startY) > 5) dragging = true;
                if (dragging) {
                    float raw = startTarget - (Widgets.my - startY);
                    if (raw < 0) raw = raw * 0.35f;
                    else if (raw > max) raw = max + (raw - max) * 0.35f;
                    target = raw;
                    float v = (lastY - Widgets.my) / dt;
                    vel = vel * 0.6f + v * 0.4f;
                    pos = target;
                }
                lastY = Widgets.my;
            }
            if (armed && !down) {
                if (!dragging) tap = true;
                armed = false;
                dragging = false;
            }
            if (!dragging) {
                if (Math.abs(vel) > 4f) {
                    target += vel * dt;
                    vel *= (float) Math.exp(-4.2f * dt);
                    if (target < 0 || target > max) vel *= (float) Math.exp(-20f * dt);
                } else vel = 0;
                float clamped = Math.max(0, Math.min(max, target));
                target = Anim.approach(target, clamped, 14f);
                pos = Anim.approach(pos, target, 15f);
            }
        }

        public boolean dragging() {
            return dragging;
        }
    }

    /** Header shared by the list screens: glass back button, title and subtitle. Returns true when back was clicked. */
    protected static boolean header(String title, String sub, float pad, float e) {
        float y = 32 - (1 - e) * 12;
        boolean back = button("hdr-back", null, "back", pad, y, 44, 44, 0, true, e);
        Nvg.save();
        Nvg.fade(e);
        tracked(title, pad + 60, y + 15, 25, Nvg.SEMI, Nvg.LEFT, 0xFFFFFFFF, -0.4f);
        if (sub != null) Nvg.text(sub, pad + 61, y + 37, 12, Nvg.REG, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.5f));
        Nvg.restore();
        return back;
    }

    /** Soft fade of list content near the top/bottom edges of a clipped viewport. */
    protected static float edgeFade(float ry, float rh, float ly, float lh) {
        float top = clamp01((ry + rh - ly) / 40f), bot = clamp01((ly + lh - ry) / 40f);
        return Anim.smooth(Math.min(top, bot));
    }
}

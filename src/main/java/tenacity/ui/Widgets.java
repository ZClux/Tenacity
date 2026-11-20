package tenacity.ui;

import net.minecraft.client.MinecraftClient;
import tenacity.gfx.Nvg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immediate mode widgets shared by the custom screens. Input is filled by {@link NvgScreen}. */
public final class Widgets {
    public static float mx, my, scroll;
    public static boolean click, rclick;
    public static final List<Integer> keys = new ArrayList<>();
    public static final StringBuilder typed = new StringBuilder();
    private static final Map<String, Float> A = new HashMap<>();

    private Widgets() {
    }

    public static void begin(float scale) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mx = (float) mc.mouse.getX() / scale;
        my = (float) mc.mouse.getY() / scale;
    }

    public static void end() {
        click = rclick = false;
        scroll = 0;
        keys.clear();
        typed.setLength(0);
    }

    public static boolean in(float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    public static float anim(String id, float target, float speed) {
        float v = Anim.approach(A.getOrDefault(id, 0f), target, speed);
        A.put(id, v);
        return v;
    }

    /** Dark liquid glass surface (falls back to a translucent plate). */
    public static void glass(float x, float y, float w, float h, float r, float a) {
        if (a <= 0.003f) return;
        if (tenacity.gfx.Glass.active()) tenacity.gfx.Glass.panel(x, y, w, h, r, a, 0x7A0B0E14, 14f, 0, false);
        else Nvg.rrect(x, y, w, h, r, Nvg.alpha(0xD00E121A, a));
        Nvg.rrectStrokeGradient(x, y, w, h, r, 1f, x, y, x + w * 0.4f, y + h, Nvg.alpha(0xFFFFFFFF, 0.28f * a), Nvg.alpha(0xFFFFFFFF, 0.05f * a));
    }

    public static void card(float x, float y, float w, float h, float r, float a) {
        glass(x, y, w, h, r, a);
    }

    /** Glass pill button; returns true when clicked. */
    public static boolean button(String id, String label, float x, float y, float w, float h, boolean primary, float a) {
        return button(id, label, null, x, y, w, h, primary, a, false);
    }

    public static boolean button(String id, String label, String icon, float x, float y, float w, float h, boolean primary, float a, boolean left) {
        boolean hov = in(x, y, w, h);
        float t = anim(id, hov ? 1f : 0f, 16f);
        float g = t * 3f;
        float r = Math.min(h / 2f, 16f);
        float bx = x - g, by = y - g / 2, bw = w + g * 2, bh = h + g;
        if (primary) Nvg.rrect(bx, by, bw, bh, r, Nvg.alpha(Nvg.mix(0xFFE9EEF5, 0xFFFFFFFF, t), a));
        else {
            glass(bx, by, bw, bh, r, a);
            if (t > 0.01f) Nvg.rrect(bx, by, bw, bh, r, Nvg.alpha(0xFFFFFFFF, 0.08f * t * a));
        }
        int tc = Nvg.alpha(primary ? 0xFF0B0E14 : 0xFFFFFFFF, a);
        if (left) {
            float tx = x + 18;
            if (icon != null) {
                tenacity.gfx.Icons.draw(icon, tx, y + h / 2f - 9, 18, tc);
                tx += 30;
            }
            Nvg.text(label, tx + t * 3f, y + h / 2f + 0.5f, 14.5f, primary ? Nvg.SEMI : Nvg.MED, Nvg.LEFT, tc);
            Nvg.save();
            Nvg.translate(x + w - 22 + t * 3f, y + h / 2f);
            Nvg.rotate((float) (-Math.PI / 2));
            tenacity.gfx.Icons.draw("chevron", -7, -7, 14, Nvg.alpha(tc, 0.35f + 0.65f * t));
            Nvg.restore();
        } else Nvg.text(label, x + w / 2, y + h / 2 + 0.5f, 14, primary ? Nvg.SEMI : Nvg.MED, Nvg.CENTER, tc);
        return hov && click;
    }

    public static void title(String t, String sub, float a) {
        Nvg.save();
        Nvg.fade(a);
        Nvg.text(t, Nvg.w / 2f, 58, 34, Nvg.SEMI, Nvg.CENTER, 0xFFFFFFFF);
        if (sub != null) Nvg.text(sub, Nvg.w / 2f, 88, 13, Nvg.MED, Nvg.CENTER, Nvg.alpha(0xFFFFFFFF, 0.65f));
        Nvg.restore();
    }
}

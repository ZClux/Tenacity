package tenacity.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import tenacity.core.Module;
import tenacity.core.ModuleManager;
import tenacity.gfx.Nvg;
import tenacity.ui.Anim;
import tenacity.ui.TenacityScreen;
import tenacity.ui.Theme;

/** Lays out, animates and draws the HUD widgets. Drag them in chat, scroll over them to resize. */
public final class Hud {
    private static final float SNAP = 7f, MARGIN = 0f;
    private static HudElement dragging;
    private static float dragDx, dragDy;
    /** Active snap guides (x, y; NaN = none) and their fade. */
    private static float guideX = Float.NaN, guideY = Float.NaN, guideA;
    private static float lastGX, lastGY;

    private Hud() {
    }

    public static boolean editing() {
        var s = MinecraftClient.getInstance().currentScreen;
        return s instanceof TenacityScreen || s instanceof ChatScreen;
    }

    public static void render() {
        // a minimised / still loading window has no real size; laying out now would pile every widget into a corner
        if (Nvg.w < 320 || Nvg.h < 200) return;
        boolean edit = MinecraftClient.getInstance().currentScreen instanceof ChatScreen;
        for (Module m : ModuleManager.all()) {
            if (!(m instanceof HudElement e)) continue;
            e.shown = Anim.approach(e.shown, e.enabled() ? 1f : 0f, 10f);
            if (e.shown < 0.01f) continue;
            if (e.x < 0 || e.y < 0) {
                e.defaults(Nvg.w, Nvg.h);
                e.refW = Nvg.w;
                e.refH = Nvg.h;
            }
            e.scaleAnim = Anim.approach(e.scaleAnim, e.scale, 14f);
            e.measure();
            if (e.w <= 0 || e.h <= 0) continue;
            // the window changed size since this widget was placed: keep it against the same edge
            if (e.refW > 0 && (Math.abs(e.refW - Nvg.w) > 0.5f || Math.abs(e.refH - Nvg.h) > 0.5f)) {
                float left = tenacity.ui.Anchor.remap(e.left(), e.sw(), e.refW, Nvg.w);
                e.x = e.anchorRight ? left + e.sw() : left;
                e.y = tenacity.ui.Anchor.remap(e.y, e.sh(), e.refH, Nvg.h);
                e.dx = Float.NaN;
            }
            e.refW = Nvg.w;
            e.refH = Nvg.h;
            if (dragging != e) clamp(e);
            float a = Anim.easeOut(e.shown);
            float tx = e.left(), ty = e.y;
            if (Float.isNaN(e.dx)) { e.dx = tx; e.dy = ty; }
            float sp = dragging == e ? 40f : 18f;
            e.dx = Anim.approach(e.dx, tx, sp);
            e.dy = Anim.approach(e.dy, ty, sp);
            float s = e.scaleAnim * (0.94f + 0.06f * a);
            float x = e.dx, y = e.dy;
            Nvg.save();
            Nvg.translate(x, y);
            Nvg.scale(s, s);
            Theme.ox = x * Nvg.pxScale / Nvg.pxScale;
            Theme.oy = y;
            Theme.gs = s;
            try {
                e.draw(0, 0, a);
            } catch (Throwable t) {
                tenacity.Tenacity.LOG.error("hud {} failed", e.name, t);
                e.setEnabled(false);
            } finally {
                Theme.ox = Theme.oy = 0;
                Theme.gs = 1f;
                Nvg.restore();
            }
            if (edit) {
                float hl = e == dragging ? 0.55f : 0.18f;
                Nvg.rrectStroke(x - 4, y - 4, e.sw() + 8, e.sh() + 8, 14, 1.2f, Nvg.alpha(Theme.text(), hl * a));
                if (e == dragging || e.scale != 1f)
                    Nvg.text(Math.round(e.scale * 100) + "%", x + e.sw(), y - 12, 10, Nvg.SEMI, Nvg.RIGHT, Nvg.alpha(Theme.text(), 0.7f));
            }
        }
        // snap guides
        boolean show = dragging != null && (!Float.isNaN(guideX) || !Float.isNaN(guideY));
        guideA = Anim.approach(guideA, show ? 1f : 0f, 18f);
        if (!Float.isNaN(guideX)) lastGX = guideX;
        if (!Float.isNaN(guideY)) lastGY = guideY;
        if (guideA > 0.01f) {
            int c = Nvg.alpha(Theme.text(), 0.75f * guideA);
            if (!Float.isNaN(guideX) || guideA < 0.99f) if (lastGX > 0) Nvg.line(lastGX, 0, lastGX, Nvg.h, 1f, Float.isNaN(guideX) ? Nvg.alpha(c, 0.4f) : c);
            if (!Float.isNaN(guideY) || guideA < 0.99f) if (lastGY > 0) Nvg.line(0, lastGY, Nvg.w, lastGY, 1f, Float.isNaN(guideY) ? Nvg.alpha(c, 0.4f) : c);
        }
    }

    private static void clamp(HudElement e) {
        float left = Math.max(MARGIN, Math.min(Nvg.w - e.sw() - MARGIN, e.left()));
        e.x = e.anchorRight ? left + e.sw() : left;
        e.y = Math.max(MARGIN, Math.min(Nvg.h - e.sh() - MARGIN, e.y));
    }

    /** Topmost enabled widget under the point, or null. */
    public static HudElement at(float mx, float my) {
        HudElement hit = null;
        for (Module m : ModuleManager.all()) {
            if (!(m instanceof HudElement e) || !e.enabled() || e.w <= 0) continue;
            float x = e.left();
            if (mx >= x && mx <= x + e.sw() && my >= e.y && my <= e.y + e.sh()) hit = e;
        }
        return hit;
    }

    /** Scroll over a widget to resize it. Returns true when a widget was resized. */
    public static boolean scroll(float mx, float my, double amount) {
        HudElement e = at(mx, my);
        if (e == null) return false;
        float right = e.left() + e.sw();
        e.scale = Math.max(0.5f, Math.min(2.5f, Math.round((e.scale + (float) amount * 0.1f) * 10f) / 10f));
        if (e.anchorRight) e.x = right;
        return true;
    }

    public static void startDrag(HudElement e, float mx, float my) {
        dragging = e;
        dragDx = mx - e.left();
        dragDy = my - e.y;
    }

    public static boolean dragging() {
        return dragging != null;
    }

    public static void drag(float mx, float my) {
        if (dragging == null) return;
        float w = dragging.sw(), h = dragging.sh();
        float left = mx - dragDx, top = my - dragDy;
        guideX = Float.NaN;
        guideY = Float.NaN;
        // snap to screen edges, centre lines and the edges of the other widgets
        java.util.List<float[]> xs = new java.util.ArrayList<>(), ys = new java.util.ArrayList<>();
        xs.add(new float[]{MARGIN, MARGIN});
        xs.add(new float[]{Nvg.w - MARGIN, Nvg.w - MARGIN});
        xs.add(new float[]{Nvg.w / 2f, Nvg.w / 2f});
        ys.add(new float[]{MARGIN, MARGIN});
        ys.add(new float[]{Nvg.h - MARGIN, Nvg.h - MARGIN});
        ys.add(new float[]{Nvg.h / 2f, Nvg.h / 2f});
        for (Module m : ModuleManager.all()) {
            if (!(m instanceof HudElement o) || o == dragging || !o.enabled() || o.w <= 0) continue;
            float ox = o.left();
            xs.add(new float[]{ox, ox});
            xs.add(new float[]{ox + o.sw(), ox + o.sw()});
            ys.add(new float[]{o.y, o.y});
            ys.add(new float[]{o.y + o.sh(), o.y + o.sh()});
            ys.add(new float[]{o.y + o.sh() + 6, o.y + o.sh() + 6}); // stack below with a gap
        }
        float bestX = SNAP, bestY = SNAP;
        float nl = left, nt = top;
        for (float[] g : xs) {
            float[] cands = {left, left + w, left + w / 2f};
            float[] offs = {0, w, w / 2f};
            for (int i = 0; i < 3; i++) {
                float d = Math.abs(cands[i] - g[0]);
                if (d < bestX) { bestX = d; nl = g[0] - offs[i]; guideX = g[1]; }
            }
        }
        for (float[] g : ys) {
            float[] cands = {top, top + h, top + h / 2f};
            float[] offs = {0, h, h / 2f};
            for (int i = 0; i < 3; i++) {
                float d = Math.abs(cands[i] - g[0]);
                if (d < bestY) { bestY = d; nt = g[0] - offs[i]; guideY = g[1]; }
            }
        }
        dragging.x = dragging.anchorRight ? nl + w : nl;
        dragging.y = nt;
        clamp(dragging);
    }

    public static void endDrag() {
        dragging = null;
        guideX = guideY = Float.NaN;
    }
}

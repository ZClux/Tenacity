package tenacity.ui;

import tenacity.gfx.Glass;
import tenacity.gfx.Nvg;
import tenacity.modules.Interface;

/** Black and white theme. Everything is derived from one foreground color. */
public final class Theme {
    /** Scale of the coordinate space panels are drawn in (the click gui shrinks on small windows). */
    /** Fixed liquid glass look (not user adjustable). */
    public static final float REFRACTION = 30f, GLASS_TINT = 27f;
    public static final int BLUR_LEVELS = 2;

    public static float gs = 1f;
    /** Set while hud() draws (its radius is already scaled). */
    private static boolean inHud;
    /** Screen offset of the current coordinate space (scaled hud widgets). */
    public static float ox, oy;

    private Theme() {
    }

    public static boolean dark() {
        return true;
    }

    /** Foreground color (white on dark glass, near black on light glass). */
    public static int text() {
        return dark() ? 0xFFFFFFFF : 0xFF0B0B0D;
    }

    /** Color used on top of a filled foreground-colored surface. */
    public static int onFill() {
        return dark() ? 0xFF0B0B0D : 0xFFFFFFFF;
    }

    public static int dim() {
        return Nvg.alpha(text(), 0.62f);
    }

    public static int faint() {
        return Nvg.alpha(text(), 0.36f);
    }

    public static int line() {
        return Nvg.alpha(text(), 0.10f);
    }

    public static int hover() {
        return Nvg.alpha(text(), 0.07f);
    }

    /** Per-panel tint override (0 = none), used by the Christmas theme to alternate red and green panels. */
    public static int tintOverride, rimOverride;

    /** Per theme: tint A, tint B (panels alternate), rim A, rim B, accent. */
    private static final int[][] PAL = {
            {0, 0, 0, 0, 0},
            {0x4A0810, 0x0A3C1E, 0xFFFF7A7A, 0xFF6BF0A0, 0xFF3BE07A},
            {0x3A1606, 0x1E0838, 0xFFFF9A2E, 0xFFB57BFF, 0xFFFF8A1E}};

    public static int tint(int i) {
        return PAL[Interface.themeId()][i % 2];
    }

    public static int rim(int i) {
        return PAL[Interface.themeId()][2 + i % 2];
    }

    /** Highlight color: white by default, the theme accent otherwise. */
    public static int accent() {
        int id = Interface.themeId();
        return id == 0 ? text() : PAL[id][4];
    }

    public static int glassTint() {
        if (Interface.themeId() > 0) {
            int v = tintOverride != 0 ? tintOverride : PAL[Interface.themeId()][0];
            return (Math.round(0.52f * 255) << 24) | (v & 0xFFFFFF);
        }
        float a = GLASS_TINT / 100f;
        if (!dark()) a = Math.min(0.92f, a + 0.22f);
        int v = dark() ? 0x0A0A0D : 0xFFFFFF;
        return (Math.round(a * 255) << 24) | v;
    }

    /** Corner radius multiplier from Interface > Roundness (100% = the original look). */
    public static float round() {
        return Interface.I == null ? 0.6f : Interface.I.roundness.f() / 100f;
    }

    public static boolean glassOn() {
        return Glass.active(); // liquid glass is always on
    }

    public static float refraction() {
        return REFRACTION;
    }

    /** Flat HUD surface: translucent plate with a bright left edge (deliberately unlike the glass menu). */
    public static void hud(float x, float y, float w, float h, float r, float a, float unused) {
        if (a <= 0.003f) return;
        if (r < Math.min(w, h) / 2f - 0.5f) r *= round(); // round widgets (the radar) stay round
        inHud = true;
        try {
            panel(x, y, w, h, Math.min(r * 1.4f, h / 2f), a, 0);
        } finally {
            inHud = false;
        }
    }

    /** HUD glass with a radius per corner. */
    public static void hudCorners(float x, float y, float w, float h, float tl, float tr, float br, float bl, float a) {
        if (a <= 0.003f) return;
        float k = round();
        tl *= k;
        tr *= k;
        br *= k;
        bl *= k;
        if (glassOn()) Glass.panelCorners(ox + x * gs, oy + y * gs, w * gs, h * gs, tl * gs, tr * gs, br * gs, bl * gs, a, glassTint(), refraction() * gs * 0.6f, !dark());
        else Nvg.rrect(x, y, w, h, tl, tr, br, bl, Nvg.alpha(0xFF121216, 0.92f * a));
        Nvg.rrectStrokeVarying(x, y, w, h, tl, tr, br, bl, 1f, Nvg.alpha(text(), 0.16f * a));
    }

    /**
     * A liquid glass panel (or a translucent fallback when glass is unavailable) with a hairline rim. The glass is queued and
     * drawn under the NanoVG content of the current layer.
     */
    public static void panel(float x, float y, float w, float h, float r, float alpha, float shadow) {
        if (alpha <= 0.003f) return;
        if (r < Math.min(w, h) / 2f - 0.5f && !inHud) r *= Math.max(0.15f, round() * 1.4f);
        shadow = 0f;
        if (glassOn()) {
            Glass.panel(ox + x * gs, oy + y * gs, w * gs, h * gs, r * gs, alpha, glassTint(), refraction() * gs, shadow * gs, !dark());
        } else {
            if (shadow > 0) Nvg.rrect(x - 1, y + 2, w + 2, h + 2, r + 1, Nvg.alpha(0xFF000000, 0.18f * alpha));
            int fill = dark() ? 0xFF121216 : 0xFFF4F4F7;
            Nvg.rrect(x, y, w, h, r, Nvg.alpha(fill, 0.92f * alpha));
        }
        if (Interface.themeId() > 0 && tintOverride != 0) {
            int rim = rimOverride;
            Nvg.rrectStrokeGradient(x, y, w, h, r, 1.4f, x, y, x + w * 0.35f, y + h, Nvg.alpha(rim, 0.7f * alpha), Nvg.alpha(0xFFFFFFFF, 0.14f * alpha));
            return;
        }
        Nvg.rrectStrokeGradient(x, y, w, h, r, 1f, x, y, x + w * 0.35f, y + h,
                dark() ? Nvg.alpha(text(), 0.22f * alpha) : Nvg.alpha(0xFFFFFFFF, 0.95f * alpha),
                dark() ? Nvg.alpha(text(), 0.04f * alpha) : Nvg.alpha(0xFF000000, 0.10f * alpha));
    }
}

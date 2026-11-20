package tenacity.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.PlayerSkinWidget;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import tenacity.gfx.Nvg;

import static org.lwjgl.nanovg.NanoVG.*;

/**
 * Main menu: flowing black background, a crisp wide-tracked wordmark with a travelling light sweep, a column of glass
 * buttons with hover physics, your player model on the right, an account chip and a quiet footer. Everything enters in a
 * staggered, eased sequence and drifts with a little parallax against the pointer.
 */
public final class TenacityMenu extends NvgScreen {
    private static final String WORD = "TENACITY";
    private static final NVGColor C1 = NVGColor.create(), C2 = NVGColor.create();
    private static final NVGPaint P = NVGPaint.create();
    private PlayerSkinWidget skin;
    private int skinX, skinY;
    private long openedAt;
    private float px, py;

    public TenacityMenu() {
        super("Tenacity");
    }

    @Override
    protected void init() {
        MinecraftClient mc = MinecraftClient.getInstance();
        int ph = (int) (height * 0.54f), pw = (int) (ph * 0.55f);
        java.util.function.Supplier<net.minecraft.entity.player.SkinTextures> own = mc.getSkinProvider().supplySkinTextures(mc.getGameProfile(), false);
        // Skin Protect: the menu model shows the protected skin too (re-evaluated every frame, so toggling is live)
        java.util.UUID selfId = mc.getGameProfile().id();
        java.util.function.Supplier<net.minecraft.entity.player.SkinTextures> shown = () -> {
            var sp = tenacity.modules.SkinProtect.I;
            return sp != null && sp.enabled() ? sp.apply(selfId, own.get()) : own.get();
        };
        skin = new PlayerSkinWidget(pw, ph, mc.getLoadedEntityModels(), shown);
        skinX = (int) (width * 0.7f - pw / 2f);
        skinY = (int) (height * 0.94f - ph);
        skin.setPosition(skinX, skinY);
        addDrawableChild(skin);
        if (openedAt == 0) openedAt = System.nanoTime();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // the player drifts against the pointer a little, like the rest of the menu
        if (skin != null) skin.setPosition(skinX + Math.round(-px * 6), skinY + Math.round(-py * 3));
        for (var d : children()) if (d instanceof net.minecraft.client.gui.Drawable dr) dr.render(context, mouseX, mouseY, delta);
    }

    private static NVGColor col(NVGColor o, int argb) {
        return o.r(((argb >> 16) & 255) / 255f).g(((argb >> 8) & 255) / 255f).b((argb & 255) / 255f).a(((argb >>> 24) & 255) / 255f);
    }

    /** Draws one glyph run filled with a linear gradient paint. */
    private static void paintText(String s, float x, float y, float size, float gx1, float gy1, float gx2, float gy2, int c1, int c2) {
        long ctx = Nvg.ctx;
        nvgFontFaceId(ctx, Nvg.SEMI);
        nvgFontSize(ctx, size);
        nvgTextLetterSpacing(ctx, 0);
        nvgTextAlign(ctx, NVG_ALIGN_LEFT | NVG_ALIGN_BASELINE);
        nvgLinearGradient(ctx, gx1, gy1, gx2, gy2, col(C1, c1), col(C2, c2), P);
        nvgFillPaint(ctx, P);
        nvgText(ctx, x, y, s);
    }

    /**
     * The wordmark: wide tracked Inter set in a cool silver gradient, letters rising in one by one, a soft bloom behind, and a
     * band of light that sweeps across every few seconds.
     */
    private float wordmark(float x, float base, float size, float t) {
        float sp = size * 0.2f;
        float total = trackedWidth(WORD, size, Nvg.SEMI, sp);
        float cap = size * 0.73f; // cap height of Inter
        float top = base - cap;

        // bloom: a wide, very soft halo that breathes slowly
        float breathe = 0.85f + 0.15f * (float) Math.sin(System.nanoTime() % 1_000_000_000_000L / 1e9 * 0.9);
        float bloom = stage(t, 0.5f, 1.4f);
        Nvg.save();
        nvgTextLetterSpacing(Nvg.ctx, sp);
        Nvg.textBlur(WORD, x, base - cap / 2f, size, Nvg.SEMI, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.16f * bloom * breathe), size * 0.5f);
        Nvg.textBlur(WORD, x, base - cap / 2f, size, Nvg.SEMI, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.10f * bloom), size * 0.12f);
        Nvg.restore();

        float sweepPeriod = 6.5f, sweepDur = 2.2f;
        float st = (t - 1.0f) % sweepPeriod;
        float sweepK = t > 1.0f && st < sweepDur ? Anim.smooth(st / sweepDur) : -1f;
        float band = size * 2.2f;
        float sx = x - band + (total + band * 2) * sweepK;

        float cx = x;
        for (int i = 0; i < WORD.length(); i++) {
            String ch = WORD.substring(i, i + 1);
            float cw = Nvg.textWidth(ch, size, Nvg.SEMI);
            float k = stage(t, 0.12f + i * 0.055f, 0.9f);
            if (k > 0.002f) {
                float dy = (1 - k) * size * 0.35f;
                Nvg.save();
                Nvg.fade(k);
                // body: bright silver at the cap line falling to a cooler grey at the baseline
                paintText(ch, cx, base + dy, size, 0, top + dy, 0, base + dy, 0xFFE9EBEF, 0xFF8A909B);
                if (sweepK >= 0) {
                    // the light band: a soft bloom plus a bright core, both only inside the band
                    float fall = (float) Math.sin(Math.PI * sweepK);
                    Nvg.save();
                    Nvg.clip(sx - band / 2f, top - size, band, cap + size * 2);
                    Nvg.textBlur(ch, cx, base + dy - cap / 2f, size, Nvg.SEMI, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.35f * fall), size * 0.18f);
                    Nvg.restore();
                    Nvg.save();
                    Nvg.clip(sx - band / 2f, top - 20, band / 2f, cap + 40);
                    paintText(ch, cx, base + dy, size, sx - band / 2f, 0, sx, 0, 0x00FFFFFF, 0xFFFFFFFF);
                    Nvg.restore();
                    Nvg.save();
                    Nvg.clip(sx, top - 20, band / 2f, cap + 40);
                    paintText(ch, cx, base + dy, size, sx, 0, sx + band / 2f, 0, 0xFFFFFFFF, 0x00FFFFFF);
                    Nvg.restore();
                }
                Nvg.restore();
            }
            cx += cw + sp;
        }
        return total;
    }

    @Override
    public void draw(float dt) {
        MinecraftClient mc = MinecraftClient.getInstance();
        float t = (System.nanoTime() - openedAt) / 1e9f;
        open = clamp01(t / 1.2f);
        float W = Nvg.w, H = Nvg.h;
        // smoothed pointer for parallax
        float nx = Widgets.mx / Math.max(1, W) * 2 - 1, ny = Widgets.my / Math.max(1, H) * 2 - 1;
        px = Anim.approach(px, nx, 3f);
        py = Anim.approach(py, ny, 3f);

        float lx = Math.max(56, W * 0.085f);
        float colW = Math.min(340, W * 0.34f);
        float size = Math.max(40, Math.min(colW * 0.2f, H * 0.11f));
        float blockH = size * 0.73f + 78 + 144 + 48;
        float top = H * 0.5f - blockH / 2f - 10;

        // ---- wordmark + tagline (deepest parallax layer)
        float wx = lx + px * 7, wy = top + size * 0.73f + py * 4;
        float total = wordmark(wx, wy, size, t);
        float tg = stage(t, 0.75f, 1.0f);
        hairline(wx - 10, wy + 22, (total + 20) * (0.35f + 0.65f * tg), 0.38f * tg);
        Nvg.save();
        Nvg.fade(tg);
        float ty = wy + 42 + (1 - tg) * 6;
        tracked("LIQUID GLASS CLIENT", wx + 1, ty, 10.5f, Nvg.MED, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.55f), 4.2f);
        float vw = trackedWidth("LIQUID GLASS CLIENT", 10.5f, Nvg.MED, 4.2f);
        String ver = "v" + tenacity.Tenacity.VERSION;
        float pw = Nvg.textWidth(ver, 10, Nvg.MED) + 16;
        Nvg.rrectStroke(wx + vw + 14, ty - 9.5f, pw, 19, 9.5f, 1f, Nvg.alpha(0xFFFFFFFF, 0.22f));
        Nvg.text(ver, wx + vw + 14 + pw / 2f, ty + 0.5f, 10, Nvg.MED, Nvg.CENTER, Nvg.alpha(0xFFFFFFFF, 0.6f));
        Nvg.restore();

        // ---- buttons
        float bx0 = lx + px * 4, by = wy + 78 + py * 2;
        String[][] big = {{"Singleplayer", "Your worlds", "player"}, {"Multiplayer", "Servers and friends", "globe"}};
        for (int i = 0; i < 2; i++) {
            float k = stage(t, 0.42f + i * 0.08f, 0.8f);
            float y = by + i * 72 + (1 - k) * 18;
            if (bigButton("mb" + i, big[i][0], big[i][1], big[i][2], bx0 - (1 - k) * 24, y, colW, 62, k) && t > 0.5f) {
                if (i == 0) mc.setScreen(new WorldsScreen(this));
                else mc.setScreen(new ServersScreen(this));
            }
        }
        float hy = by + 144;
        float half = (colW - 10) / 2f;
        float k2 = stage(t, 0.58f, 0.8f), k3 = stage(t, 0.64f, 0.8f);
        if (button("mopt", "Options", "gear", bx0 - (1 - k2) * 24, hy + (1 - k2) * 18, half, 48, 0, true, k2) && t > 0.5f)
            mc.setScreen(new OptionsScreen(this, mc.options));
        if (button("mquit", "Quit", "power", bx0 + half + 10 - (1 - k3) * 24, hy + (1 - k3) * 18, half, 48, 0, true, k3) && t > 0.5f)
            mc.scheduleStop();

        // ---- player stage: a soft floor light under the model
        float pk = stage(t, 0.3f, 1.4f);
        float fx = W * 0.7f - px * 6, fy = H * 0.94f - py * 3;
        ellipseGlow(fx, fy + 2, Math.min(W * 0.13f, 170), 16, Nvg.alpha(0xFFFFFFFF, 0.10f * pk));
        ellipseGlow(fx, fy + 1, Math.min(W * 0.06f, 70), 7, Nvg.alpha(0xFF000000, 0.55f * pk));

        // ---- account chip (top right)
        float ck = stage(t, 0.8f, 0.9f);
        String name = tenacity.modules.NameCore.nvg(mc.getSession().getUsername());
        float cw = Math.max(150, Nvg.textWidth(name, 13, Nvg.SEMI) + 72);
        float cx = W - 28 - cw + (1 - ck) * 20, cy = 26;
        boolean ch = Widgets.in(cx, cy, cw, 46);
        float chv = Widgets.anim("chip", ch ? 1f : 0f, 12f);
        Widgets.glass(cx, cy, cw, 46, 23, ck);
        if (chv > 0.01f) rrectRadial(cx, cy, cw, 46, 23, Widgets.mx, Widgets.my, cw * 0.6f, Nvg.alpha(0xFFFFFFFF, 0.1f * chv * ck), 0);
        Nvg.save();
        Nvg.fade(ck);
        Nvg.circle(cx + 23, cy + 23, 15, 0xFFFFFFFF);
        Nvg.text(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(), cx + 23, cy + 23.5f, 14, Nvg.SEMI, Nvg.CENTER, 0xFF0A0B0E);
        Nvg.text(name, cx + 46, cy + 17, 13, Nvg.SEMI, Nvg.LEFT, 0xFFFFFFFF);
        Nvg.circle(cx + 49, cy + 31.5f, 2.6f, Nvg.alpha(0xFFFFFFFF, 0.8f));
        Nvg.text("Signed in", cx + 56, cy + 31.5f, 10.5f, Nvg.REG, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.5f));
        Nvg.restore();

        // ---- footer
        float fk = stage(t, 1.0f, 1.0f);
        Nvg.save();
        Nvg.fade(fk);
        tracked("TENACITY " + tenacity.Tenacity.VERSION.toUpperCase() + "   /   MINECRAFT 1.21.11", lx, H - 30, 9.5f, Nvg.MED, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.36f), 1.6f);
        tracked("DRAG TO ROTATE", fx, H - 16, 9f, Nvg.MED, Nvg.CENTER, Nvg.alpha(0xFFFFFFFF, 0.22f), 2.2f);
        tracked("NOT AN OFFICIAL MINECRAFT PRODUCT", W - 28, H - 30, 9.5f, Nvg.MED, Nvg.RIGHT, Nvg.alpha(0xFFFFFFFF, 0.24f), 1.6f);
        Nvg.restore();
        frameEnd();
    }

    /** Tall glass button with an icon disc, a title and a caption; the arrow slides out on hover. */
    private static boolean bigButton(String id, String title, String caption, String icn, float x, float y, float w, float h, float a) {
        if (a <= 0.003f) return false;
        boolean hov = Widgets.in(x, y, w, h);
        float t = Widgets.anim(id, hov ? 1f : 0f, 13f);
        boolean clicked = hov && Widgets.click;
        if (clicked) {
            Widgets.click = false;
            Widgets.anim(id + "#p", 1f, 1000f);
        }
        float p = Widgets.anim(id + "#p", 0f, 9f);
        float s = 1f + 0.025f * t - 0.04f * p;
        float cx = x + w / 2f, cy = y + h / 2f;
        float sw = w * s, sh = h * s, sx = cx - sw / 2f, sy = cy - sh / 2f - t * 2f;
        float r = 18;
        float lx = Math.max(sx, Math.min(sx + sw, Widgets.mx)), ly = Math.max(sy, Math.min(sy + sh, Widgets.my));
        Widgets.glass(sx, sy, sw, sh, r, a);
        Nvg.rrect(sx, sy, sw, sh, r, Nvg.alpha(0xFFFFFFFF, 0.045f * t * a));
        rrectRadial(sx, sy, sw, sh, r, lx, ly, sw * 0.55f, Nvg.alpha(0xFFFFFFFF, 0.14f * t * a), 0);
        if (t > 0.01f)
            Nvg.rrectStrokeGradient(sx, sy, sw, sh, r, 1.1f, lx - sw * 0.25f, ly - sh, lx + sw * 0.25f, ly + sh, Nvg.alpha(0xFFFFFFFF, 0.55f * t * a), Nvg.alpha(0xFFFFFFFF, 0.03f * t * a));
        Nvg.save();
        Nvg.translate(cx, cy - t * 2f);
        Nvg.scale(s, s);
        Nvg.translate(-w / 2f, -h / 2f);
        Nvg.fade(a);
        // icon disc turns white on hover, the glyph inverts
        float dcx = 31, dcy = h / 2f;
        Nvg.circle(dcx, dcy, 17, Nvg.mix(Nvg.alpha(0xFFFFFFFF, 0.08f), 0xFFFFFFFF, t));
        Nvg.circleStroke(dcx, dcy, 17, 1f, Nvg.alpha(0xFFFFFFFF, 0.16f * (1 - t)));
        icon(icn, dcx, dcy, 17, Nvg.mix(0xFFFFFFFF, 0xFF0A0B0E, t));
        Nvg.text(title, 60 + t * 2, h / 2f - 7.5f, 15, Nvg.SEMI, Nvg.LEFT, 0xFFFFFFFF);
        Nvg.text(caption, 60 + t * 2, h / 2f + 10f, 11, Nvg.REG, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.45f + 0.15f * t));
        icon("arrow", w - 26 + t * 4, h / 2f, 16, Nvg.alpha(0xFFFFFFFF, 0.25f + 0.75f * t));
        Nvg.restore();
        return clicked;
    }
}

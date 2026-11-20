package tenacity.modules;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.screen.ChatScreen;
import org.lwjgl.glfw.GLFW;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.Icons;
import tenacity.gfx.Nvg;
import tenacity.gfx.SpotifyArt;
import tenacity.spotify.Lyrics;
import tenacity.spotify.NowPlaying;
import tenacity.spotify.SpotifyPoller;
import tenacity.spotify.SyncedLyrics;
import tenacity.ui.Anim;
import tenacity.ui.Theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Music widget + synced lyrics, built on the existing local Spotify integration (SpotifyPoller reads the Windows media
 * session, LyricsService fetches synced LRC lyrics). Floating liquid-glass card plus an optional big lyric line above the
 * hotbar.
 */
public final class SkyLyrics extends Module {
    private final Setting.Bool widget = bool("Widget", false);
    private final Setting.Bool lyrics = bool("Lyrics", true);
    private final Setting.Bool hudLyrics = bool("Lyrics in HUD", false);
    private final Setting.Mode position = mode("Position", "Bottom Right", "Top Left", "Top Right", "Bottom Left", "Bottom Right", "Custom");
    private final Setting.Num height = num("Sky Height", 28, 5, 60, 1);
    private final Setting.Num follow = num("Follow Smoothness", 60, 0, 100, 5);
    private final Setting.Bool songInfo = bool("Song In Sky", true);
    private final Setting.Num scale = num("Scale", 1, 0.6, 1.8, 0.05);
    private final Setting.Color textColor = color("Text Colour", 0xFFF4F7FF);
    private final Setting.Num bg = num("Background Opacity", 100, 0, 100, 5);
    private final Setting.Bool art = bool("Album Art", true);
    private final Setting.Bool titleArtist = bool("Show Title / Artist", true);
    private final Setting.Num animSpeed = num("Lyrics Animation Speed", 1, 0.3, 3, 0.05);
    private final Setting.Mode weight = mode("Font Weight", "Medium", "Regular", "Medium", "Semibold");
    private final Setting.Bool controls = bool("Floating Controls", true);
    private final Setting.Num ctrlHeight = num("Controls Height", 0.55, 0.1, 2, 0.05);
    private final Setting.Num ctrlSize = num("Button Size", 0.3, 0.12, 0.7, 0.02);
    private final Setting.Num ctrlSpacing = num("Button Spacing", 0.1, 0, 0.5, 0.02);
    private final Setting.Num ctrlBg = num("Controls Background Opacity", 60, 0, 100, 5);
    private final Setting.Color ctrlColor = color("Icon Colour", 0xFFF4F7FF);
    private final Setting.Bool ctrlHover = bool("Hover Animation", true);
    private final Setting.Num ctrlGlow = num("Glow Intensity", 50, 0, 100, 5);

    private static SkyLyrics INSTANCE;
    private float ctrlVis;
    private final float[] hoverA = new float[3], pressA = new float[3];
    private final float[] btnX = new float[3], btnY = new float[3];
    private float btnR;
    private int hovered = -1;
    private boolean prevMouseDown, holdConsumed;
    private float fpYaw = Float.NaN;
    private boolean optimisticPlaying;
    private long optimisticUntil;

    private final SpotifyArt albumArt = new SpotifyArt();
    private float vis, scroll, cardH = -1, hudSwitch = 1, activeA;
    private int lastIdx = Integer.MIN_VALUE, hudIdx = Integer.MIN_VALUE, hudPrevIdx = Integer.MIN_VALUE;
    private float customX = -1, customY = -1;
    private boolean dragging;
    private float dragDX, dragDY;
    private float lastX, lastY, lastW, lastH;

    private static final boolean DEMO = System.getenv("TENACITY_LYRICS_DEMO") != null;
    private static final long DEMO_START = System.currentTimeMillis() - 41_000;

    public SkyLyrics() {
        super("Sky Lyrics", "Spotify music widget with smooth synced lyrics", Category.RENDER);
        INSTANCE = this;
    }

    /* ------------------------------------------------------------------ floating music controls */

    /** Called from doAttack: if the crosshair is on a music button, press it and swallow the click. */
    public static boolean consumeClick() {
        SkyLyrics s = INSTANCE;
        if (s == null || !s.controlsLive() || s.mc.currentScreen != null || s.hovered < 0) return false;
        s.press(s.hovered);
        s.holdConsumed = true;
        return true;
    }

    /** True while the left button that pressed a music button is still held (suppresses block breaking). */
    public static boolean holdingClick() {
        SkyLyrics s = INSTANCE;
        if (s == null || !s.holdConsumed) return false;
        if (!s.mc.options.attackKey.isPressed()) {
            s.holdConsumed = false;
            return false;
        }
        return true;
    }

    private boolean controlsLive() {
        return enabled() && controls.on() && ctrlVis > 0.35f;
    }

    private void press(int i) {
        pressA[i] = 1f;
        SpotifyPoller.Control c = i == 0 ? SpotifyPoller.Control.PREVIOUS : i == 1 ? SpotifyPoller.Control.PLAY_PAUSE : SpotifyPoller.Control.NEXT;
        if (i == 1) {
            optimisticPlaying = !shownPlaying(view().playing);
            optimisticUntil = System.currentTimeMillis() + 1500;
        }
        if (!DEMO) SpotifyPoller.get().control(c);
    }

    private boolean shownPlaying(boolean real) {
        return System.currentTimeMillis() < optimisticUntil ? optimisticPlaying : real;
    }

    private void drawControls(View v, float td, float a) {
        boolean want = controls.on() && v.active && mc.player != null && !mc.options.hudHidden;
        ctrlVis = Anim.approach(ctrlVis, want ? 1f : 0f, 7f);
        if (ctrlVis <= 0.01f || mc.player == null) {
            hovered = -1;
            return;
        }
        var cam = mc.gameRenderer.getCamera();
        net.minecraft.util.math.Vec3d anchor;
        // sit right above the sky lyrics: same lazily-following sky direction, a little higher, a few blocks out
        if (Float.isNaN(skyYaw)) skyYaw = cam.getYaw();
        double sy = Math.toRadians(skyYaw), sp = Math.toRadians(-(height.f() + 12f + ctrlHeight.f() * 4f));
        net.minecraft.util.math.Vec3d sdir = new net.minecraft.util.math.Vec3d(-Math.sin(sy) * Math.cos(sp), -Math.sin(sp), Math.cos(sy) * Math.cos(sp));
        anchor = cam.getCameraPos().add(sdir.multiply(6));
        float[] o = new float[2], o2 = new float[2];
        double ry = Math.toRadians(cam.getYaw());
        net.minecraft.util.math.Vec3d right = new net.minecraft.util.math.Vec3d(-Math.cos(ry), 0, -Math.sin(ry));
        if (!tenacity.util.Projection.point(anchor, o) || !tenacity.util.Projection.point(anchor.add(right), o2)) {
            hovered = -1;
            return;
        }
        float ppm = (float) Math.hypot(o2[0] - o[0], o2[1] - o[1]); // pixels per block at the anchor
        float appear = Anim.easeOut(ctrlVis);
        float alpha = a * Anim.smooth(ctrlVis);
        float r = ctrlSize.f() * 0.5f * ppm * (0.7f + 0.3f * appear);
        if (r < 3 || r > Nvg.h) {
            hovered = -1;
            return;
        }
        float gap = ctrlSpacing.f() * ppm;
        btnR = r;
        float cx = o[0], cy = o[1] - (1f - appear) * r * 0.6f;

        // pointer: crosshair while the cursor is locked, else the free mouse cursor
        boolean free = mc.currentScreen != null;
        float mx, my;
        if (free) {
            mx = (float) (mc.mouse.getX() * Nvg.w / mc.getWindow().getWidth());
            my = (float) (mc.mouse.getY() * Nvg.h / mc.getWindow().getHeight());
        } else {
            mx = Nvg.w / 2f;
            my = Nvg.h / 2f;
        }
        int newHover = -1;
        for (int i = 0; i < 3; i++) {
            btnX[i] = cx + (i - 1) * (2 * r + gap);
            btnY[i] = cy;
            float hr = r * (i == 1 ? 1.12f : 1f);
            if (alpha > 0.35f && Math.hypot(mx - btnX[i], my - btnY[i]) <= hr) newHover = i;
        }
        hovered = newHover;
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (free && down && !prevMouseDown && hovered >= 0 && controlsLive()) press(hovered);
        prevMouseDown = down;

        boolean playing = shownPlaying(v.playing);
        int ic = ctrlColor.rgb();
        float glow = ctrlGlow.f() / 100f;
        for (int i = 0; i < 3; i++) {
            hoverA[i] = Anim.approach(hoverA[i], hovered == i ? 1f : 0f, 12f);
            pressA[i] = Math.max(0f, pressA[i] - Anim.dt * 3.5f);
            float h = Anim.smooth(hoverA[i]);
            float p = Anim.easeOut(pressA[i]);
            float stagger = Anim.smooth(Math.max(0f, Math.min(1f, ctrlVis * 1.6f - Math.abs(i - 1) * 0.3f)));
            float br = r * (i == 1 ? 1.12f : 1f) * (0.6f + 0.4f * stagger);
            if (ctrlHover.on()) br *= 1f + 0.12f * h - 0.1f * p * (1f - p) * 4f;
            float ba = alpha * stagger;
            if (ba <= 0.01f) continue;
            float x = btnX[i], y = btnY[i];
            if (glow > 0.01f)
                Nvg.glowDisc(x, y, br * (1.5f + 0.4f * h), withA(ic, (0.16f + 0.24f * h + 0.3f * p) * glow * ba), withA(ic, 0f));
            float bgA = ctrlBg.f() / 100f;
            if (bgA > 0.01f) Theme.hud(x - br, y - br, br * 2, br * 2, br, ba * bgA, 0);
            Nvg.circle(x, y, br, withA(ic, (0.05f + 0.12f * h + 0.2f * p) * ba));
            Nvg.circleStroke(x, y, br - 0.5f, 1f, withA(ic, (0.22f + 0.3f * h) * ba));
            drawIcon(i, playing, x, y, br * 0.42f, withA(ic, (0.88f + 0.12f * h) * ba));
        }
    }

    /** Vector transport icons (no glyphs). */
    private static void drawIcon(int i, boolean playing, float x, float y, float s, int c) {
        if (i == 1) {
            if (playing) { // pause
                float bw = s * 0.42f, bh = s * 1.5f;
                Nvg.rrect(x - s * 0.55f, y - bh / 2, bw, bh, bw * 0.35f, c);
                Nvg.rrect(x + s * 0.55f - bw, y - bh / 2, bw, bh, bw * 0.35f, c);
            } else { // play (optically centred)
                Nvg.beginPath();
                Nvg.moveTo(x - s * 0.5f, y - s * 0.8f);
                Nvg.lineTo(x + s * 0.85f, y);
                Nvg.lineTo(x - s * 0.5f, y + s * 0.8f);
                Nvg.closePath();
                Nvg.fillPath(c);
            }
            return;
        }
        float d = i == 0 ? -1 : 1; // direction
        float th = s * 0.62f;
        for (int k = 0; k < 2; k++) { // two chevron triangles
            float bx = x + d * (k == 0 ? -s * 0.55f : s * 0.15f);
            Nvg.beginPath();
            Nvg.moveTo(bx - d * th * 0.5f, y - th);
            Nvg.lineTo(bx + d * th * 0.7f, y);
            Nvg.lineTo(bx - d * th * 0.5f, y + th);
            Nvg.closePath();
            Nvg.fillPath(c);
        }
        float barX = x + d * s * 0.95f - s * 0.12f;
        Nvg.rrect(barX, y - th, s * 0.24f, th * 2, s * 0.1f, c);
    }

    @Override
    public boolean lingers() {
        return vis > 0.01f;
    }

    @Override
    protected void onDisable() {
        dragging = false;
    }

    @Override
    public void saveExtra(JsonObject o) {
        o.addProperty("lx", customX);
        o.addProperty("ly", customY);
    }

    @Override
    public void loadExtra(JsonObject o) {
        if (o.has("lx")) customX = o.get("lx").getAsFloat();
        if (o.has("ly")) customY = o.get("ly").getAsFloat();
    }

    /* ------------------------------------------------------------------ data */

    /** A frame's worth of state (real Spotify or the dev demo). */
    private record View(boolean active, boolean playing, String title, String artist, long pos, long dur, NowPlaying np,
                        List<String> lines, long[] times, String status, String idle) {
        int index() {
            if (times == null) return -1;
            int lo = 0, hi = times.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (times[mid] <= pos) lo = mid + 1;
                else hi = mid;
            }
            return lo - 1;
        }
    }

    private View view() {
        if (DEMO) {
            String[] l = {"Floating up above the clouds", "Balloons drifting through the gold", "Every star a quiet sound",
                    "Hold the sky and don't let go", "", "We were dreaming out loud", "Painted pastel on the night"};
            long[] t = new long[l.length];
            for (int i = 0; i < l.length; i++) t[i] = 30_000 + i * 4_200L;
            long pos = (System.currentTimeMillis() - DEMO_START) % 90_000;
            return new View(true, true, "Cotton Candy Skies", "Tenacity Sound", pos, 214_000, null, List.of(l), t, null, null);
        }
        NowPlaying now = SpotifyPoller.get().nowPlaying();
        if (!now.active() || !now.hasTrack()) {
            String idle = switch (now.status()) {
                case NOT_RUNNING -> "Spotify is not open";
                case UNSUPPORTED -> "Spotify: Windows only";
                default -> "Nothing playing";
            };
            return new View(false, false, "", "", 0, 0, now, List.of(), null, null, idle);
        }
        long pos = now.positionAt(System.currentTimeMillis());
        List<String> lines = new ArrayList<>();
        long[] times = null;
        String status = null;
        if (lyrics.on() || hudLyrics.on()) {
            Lyrics ly = SpotifyPoller.get().lyricsFor(now);
            if (ly == null) status = now.lyricsEligible() ? "Looking for lyrics..." : null;
            else switch (ly.status()) {
                case SYNCED -> {
                    SyncedLyrics s = ly.synced();
                    times = new long[s.size()];
                    for (int i = 0; i < s.size(); i++) {
                        lines.add(s.line(i).text());
                        times[i] = s.line(i).timeMs();
                    }
                }
                case PLAIN -> status = "Lyrics aren't synced for this song";
                case INSTRUMENTAL -> status = "Instrumental";
                case LOADING -> status = "Loading lyrics...";
                default -> status = "No lyrics found";
            }
        }
        return new View(true, now.playing(), now.title(), now.artist(), pos, now.durationMs(), now, lines, times, status, null);
    }

    private int font() {
        return switch (weight.index) {
            case 0 -> Nvg.REG;
            case 2 -> Nvg.SEMI;
            default -> Nvg.MED;
        };
    }

    private static int withA(int argb, float a) {
        return Nvg.alpha(argb | 0xFF000000, a);
    }

    /* ------------------------------------------------------------------ render */

    @Override
    public void render2d(float td) {
        float dt = Anim.dt;
        vis = Anim.approach(vis, enabled() ? 1f : 0f, 6f);
        if (vis <= 0.01f) return;
        float ease = Anim.smooth(vis);
        View v = view();
        activeA = Anim.approach(activeA, v.active ? 1f : 0f, 5f);

        int idx = v.index();
        if (lastIdx == Integer.MIN_VALUE) scroll = idx;
        lastIdx = idx;
        float k = 1f - (float) Math.exp(-dt * 7f * animSpeed.f());
        scroll += (idx - scroll) * k;
        if (Math.abs(idx - scroll) > 6) scroll = idx;

        if (v.active) drawSky(v, idx, ease * activeA);
        drawControls(v, td, ease);
        if (widget.on()) drawWidget(v, idx, ease);
        if (hudLyrics.on() && v.active && v.times != null) drawHudLyric(v, idx, ease);
        else hudIdx = Integer.MIN_VALUE;
    }

    private float skyYaw = Float.NaN, skyPrev;
    private int skyIdx = Integer.MIN_VALUE, skyPrevIdx = -1;
    private float skySwitch = 1;

    /** Lyrics written on the sky: anchored to a sky direction that lazily glides after where you look. */
    private void drawSky(View v, int idx, float a) {
        if (mc.options.hudHidden || a <= 0.01f) return;
        var cam = mc.gameRenderer.getCamera();
        float yaw = cam.getYaw();
        if (Float.isNaN(skyYaw)) skyYaw = yaw;
        float rate = 0.6f + (100f - follow.f()) * 0.08f;
        skyYaw += net.minecraft.util.math.MathHelper.wrapDegrees(yaw - skyYaw) * (1f - (float) Math.exp(-Anim.dt * rate));
        double ry = Math.toRadians(skyYaw), rp = Math.toRadians(-height.f());
        net.minecraft.util.math.Vec3d dir = new net.minecraft.util.math.Vec3d(-Math.sin(ry) * Math.cos(rp), -Math.sin(rp), Math.cos(ry) * Math.cos(rp));
        float[] o = new float[2];
        if (!tenacity.util.Projection.point(cam.getCameraPos().add(dir.multiply(200)), o)) return;
        // fade when it drifts toward the screen edge (it is "behind" your view while catching up)
        float edge = Math.max(Math.abs(o[0] / Nvg.w - 0.5f), Math.abs(o[1] / Nvg.h - 0.5f)) * 2f;
        float va = a * (1f - Anim.smooth(Math.max(0f, (edge - 0.6f) / 0.4f)));
        if (va <= 0.01f) return;
        float x = o[0], y = o[1];
        float s = scale.f() * Nvg.h / 1080f * 1.6f;
        int tc = textColor.rgb();
        int f = font() == Nvg.REG ? Nvg.MED : Nvg.SEMI;
        if (idx != skyIdx) {
            skyPrevIdx = skyIdx;
            skyIdx = idx;
            skySwitch = 0;
        }
        skySwitch = Math.min(1f, skySwitch + Anim.dt * 1.8f * animSpeed.f());
        float e = Anim.easeOut(skySwitch);
        float size = 30 * s;
        if (songInfo.on()) {
            String head = v.title + (v.artist == null || v.artist.isEmpty() ? "" : "  ·  " + v.artist);
            Nvg.textBlur(head, x, y - size * 1.5f, 11 * s, Nvg.MED, Nvg.CENTER, withA(tc, 0.25f * va), 6);
            Nvg.text(head, x, y - size * 1.5f, 11 * s, Nvg.MED, Nvg.CENTER, withA(tc, 0.55f * va));
        }
        if (!lyrics.on() || v.times == null || v.lines.isEmpty()) return;
        if (skyPrevIdx >= 0 && skyPrevIdx < v.lines.size() && e < 1f) {
            String t = v.lines.get(skyPrevIdx);
            float pa = (1f - e) * va;
            float yy = y - size * 0.9f * e;
            Nvg.textBlur(t, x, yy, size * (1f - 0.15f * e), f, Nvg.CENTER, withA(tc, 0.35f * pa), 10 + 14 * e);
            Nvg.text(t, x, yy, size * (1f - 0.15f * e), f, Nvg.CENTER, withA(tc, 0.8f * pa * (1f - e)));
        }
        if (idx >= 0 && idx < v.lines.size()) {
            String t = v.lines.get(idx).isEmpty() ? "• • •" : v.lines.get(idx);
            float yy = y + size * 0.9f * (1f - e);
            float sz = size * (0.9f + 0.1f * e);
            Nvg.textBlur(t, x, yy, sz, f, Nvg.CENTER, withA(tc, 0.55f * va * e), 16);
            Nvg.textBlur(t, x, yy, sz, f, Nvg.CENTER, withA(tc, 0.4f * va * e), 5);
            Nvg.text(t, x, yy, sz, f, Nvg.CENTER, withA(tc, va * e));
        }
        if (idx + 1 < v.lines.size()) { // the next line waits faintly below
            String t = v.lines.get(idx + 1);
            Nvg.text(t, x, y + size * 1.25f, size * 0.62f, f, Nvg.CENTER, withA(tc, 0.22f * va * e));
        }
    }

    private void drawWidget(View v, int idx, float a) {
        float s = scale.f();
        int tc = textColor.rgb();
        float pad = 12 * s;
        boolean showArt = art.on();
        boolean lyr = lyrics.on() && v.active;
        float w = 300 * s;
        float headH = v.active ? (showArt || titleArtist.on() ? 64 * s : 0) : 34 * s;
        float progH = v.active ? 26 * s : 0;
        float lyrH = lyr ? (v.times != null ? 72 * s : 26 * s) : 0;
        float targetH = v.active ? pad + headH + progH + lyrH + pad * 0.5f : 40 * s;
        float targetW = v.active ? w : 40 * s + Nvg.textWidth(v.active ? "" : "Spotify inactive", 12 * s, Nvg.SEMI) + 24 * s;
        if (cardH < 0) cardH = targetH;
        cardH = Anim.approach(cardH, targetH, 9f);
        lastW = lastW <= 0 ? targetW : Anim.approach(lastW, targetW, 9f);
        float h = cardH, cw = lastW;

        // position: preset corners or the dragged spot
        float m = 10;
        float x, y;
        switch (position.get()) {
            case "Top Left" -> { x = m; y = m + 8; }
            case "Bottom Left" -> { x = m; y = Nvg.h - h - 60; }
            case "Bottom Right" -> { x = Nvg.w - cw - m; y = Nvg.h - h - 60; }
            case "Custom" -> {
                if (customX < 0) { customX = Nvg.w - cw - m; customY = m + 40; }
                x = customX;
                y = customY;
            }
            default -> { x = Nvg.w - cw - m; y = m + 40; }
        }
        handleDrag(x, y, cw, h);
        if (position.is("Custom")) { x = customX; y = customY; }
        x = Math.max(2, Math.min(Nvg.w - cw - 2, x));
        y = Math.max(2, Math.min(Nvg.h - h - 2, y));
        lastX = x;
        lastY = y;

        // slide in from the side it lives on
        float slide = (1f - a) * 24 * (x > Nvg.w / 2 ? 1 : -1);
        x += slide;
        float bgA = bg.f() / 100f;
        if (bgA > 0.01f) Theme.hud(x, y, cw, h, 18 * s, a * bgA, 14);
        if (dragging) Nvg.rrectStroke(x, y, cw, h, 18 * s, 1.2f, withA(tc, 0.45f * a));

        if (!v.active) {
            float ia = a * (1f - activeA * 0.0f);
            Icons.draw("spotify", x + 12 * s, y + h / 2 - 8 * s, 16 * s, withA(tc, 0.9f * ia));
            Nvg.text("Spotify inactive", x + 36 * s, y + h / 2, 12 * s, Nvg.SEMI, Nvg.LEFT, withA(tc, 0.92f * ia));
            albumArt.clear();
            return;
        }
        Nvg.save();
        Nvg.clip(x, y, cw, h);
        float cx = x + pad, cy = y + pad;
        float as = 64 * s;
        float tx = cx;
        if (showArt) {
            boolean has = v.np != null && albumArt.update(v.np.art());
            if (has) albumArt.draw(cx, cy, as, 12 * s, a);
            else {
                Nvg.rrectGradient(cx, cy, as, as, 12 * s, cx, cy, cx + as, cy + as, withA(0xFFFFFFFF, 0.22f * a), withA(0xFFFFFFFF, 0.06f * a));
                Icons.draw("spotify", cx + as / 2 - 12 * s, cy + as / 2 - 12 * s, 24 * s, withA(tc, 0.7f * a));
            }
            Nvg.rrectStroke(cx, cy, as, as, 12 * s, 1f, withA(0xFFFFFFFF, 0.18f * a));
            tx = cx + as + 12 * s;
        }
        float maxW = x + cw - pad - tx - 22 * s;
        if (titleArtist.on()) {
            Nvg.text(Nvg.fit(v.title, 14 * s, Nvg.SEMI, maxW), tx, cy + 18 * s, 14 * s, Nvg.SEMI, Nvg.LEFT, withA(tc, a));
            Nvg.text(Nvg.fit(v.artist, 11.5f * s, Nvg.REG, maxW), tx, cy + 36 * s, 11.5f * s, Nvg.REG, Nvg.LEFT, withA(tc, 0.62f * a));
        }
        // play / pause status
        float px = x + cw - pad - 10 * s, py = cy + 18 * s;
        float t = System.nanoTime() / 1e9f;
        if (v.playing) {
            for (int i = 0; i < 3; i++) {
                float f = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * (4.1f + i * 1.7f) + i * 2f));
                float bh = 13 * s * f;
                Nvg.rrect(px - 6 * s + i * 5.5f * s, py + 6 * s - bh, 3.4f * s, bh, 1.7f * s, withA(tc, 0.9f * a));
            }
        } else {
            Nvg.rrect(px - 5 * s, py - 6 * s, 3.6f * s, 12 * s, 1.5f * s, withA(tc, 0.75f * a));
            Nvg.rrect(px + 1.5f * s, py - 6 * s, 3.6f * s, 12 * s, 1.5f * s, withA(tc, 0.75f * a));
        }
        if (!v.playing) Nvg.text("Paused", tx, cy + 54 * s, 9.5f * s, Nvg.MED, Nvg.LEFT, withA(tc, 0.45f * a));
        else Nvg.text("Now playing", tx, cy + 54 * s, 9.5f * s, Nvg.MED, Nvg.LEFT, withA(tc, 0.45f * a));

        // progress
        float by = cy + headH + 8 * s;
        if (v.dur > 0) {
            float f = Math.max(0f, Math.min(1f, v.pos / (float) v.dur));
            float bx = cx, bw = cw - pad * 2;
            Nvg.rrect(bx, by - 1.5f * s, bw, 3f * s, 1.5f * s, withA(tc, 0.16f * a));
            Nvg.rrectGradient(bx, by - 1.5f * s, Math.max(3f * s, bw * f), 3f * s, 1.5f * s, bx, by, bx + bw * f, by, withA(tc, 0.55f * a), withA(tc, a));
            Nvg.glowDisc(bx + bw * f, by, 7 * s, withA(tc, 0.35f * a), withA(tc, 0f));
            Nvg.circle(bx + bw * f, by, 3.4f * s, withA(tc, a));
            Nvg.text(time(v.pos), bx, by + 10 * s, 9 * s, Nvg.MED, Nvg.LEFT, withA(tc, 0.45f * a));
            Nvg.text(time(v.dur), bx + bw, by + 10 * s, 9 * s, Nvg.MED, Nvg.RIGHT, withA(tc, 0.45f * a));
        }

        // lyrics
        if (lyrics.on()) {
            float ly = y + pad + headH + progH;
            float lh = h - (ly - y) - pad * 0.5f;
            if (lh > 4) {
                Nvg.save();
                Nvg.clip(x, ly, cw, lh);
                float mid = ly + lh / 2;
                if (v.times != null && !v.lines.isEmpty()) {
                    int f = font();
                    float lineH = 21 * s;
                    for (int i = Math.max(0, idx - 3); i <= Math.min(v.lines.size() - 1, idx + 3); i++) {
                        float rel = i - scroll;
                        float yy = mid + rel * lineH;
                        float dist = Math.abs(rel);
                        float la = Math.max(0f, 1f - dist * 0.62f);
                        if (la <= 0.01f) continue;
                        boolean curLine = i == idx;
                        float size = (11.5f + 2.5f * Math.max(0f, 1f - dist)) * s;
                        String text = v.lines.get(i).isEmpty() ? "\u2022 \u2022 \u2022" : v.lines.get(i);
                        text = Nvg.fit(text, size, f, cw - pad * 2);
                        if (curLine) Nvg.textBlur(text, x + cw / 2, yy, size, f, Nvg.CENTER, withA(tc, 0.35f * a * la), 6 * s);
                        Nvg.text(text, x + cw / 2, yy, size, f, Nvg.CENTER, withA(tc, a * (curLine ? la : la * 0.55f)));
                    }
                    if (idx < 0) Nvg.text("\u2022 \u2022 \u2022", x + cw / 2, mid - (-1 - scroll) * 0, 12 * s, f, Nvg.CENTER, withA(tc, 0.4f * a * Math.min(1f, -scroll)));
                } else if (v.status != null) {
                    Nvg.text(v.status, x + cw / 2, mid, 10.5f * s, Nvg.MED, Nvg.CENTER, withA(tc, 0.5f * a));
                }
                Nvg.restore();
            }
        }
        Nvg.restore();
    }

    private void drawHudLyric(View v, int idx, float a) {
        if (mc.options.hudHidden) return;
        if (idx != hudIdx) {
            hudPrevIdx = hudIdx;
            hudIdx = idx;
            hudSwitch = 0;
        }
        hudSwitch = Math.min(1f, hudSwitch + Anim.dt * 2.6f * animSpeed.f());
        float e = Anim.easeOut(hudSwitch);
        float s = scale.f();
        int tc = textColor.rgb();
        int f = font() == Nvg.REG ? Nvg.MED : Nvg.SEMI;
        float size = 19 * s;
        float gui = (float) (mc.getWindow().getScaleFactor() * Nvg.h / mc.getWindow().getFramebufferHeight());
        boolean bars = mc.interactionManager != null && mc.interactionManager.hasStatusBars();
        float cy = Nvg.h - (bars ? 52 : 32) * gui - 10 * s;
        // the outgoing line slides up and fades, the new one rises in
        if (hudPrevIdx >= 0 && hudPrevIdx < v.lines.size() && e < 1f) {
            String t = v.lines.get(hudPrevIdx);
            if (!t.isEmpty()) {
                float pa = (1f - e) * a;
                Nvg.textBlur(t, Nvg.w / 2, cy - 14 * e, size * (1f - 0.08f * e), f, Nvg.CENTER, withA(tc, 0.3f * pa), 8);
                Nvg.text(t, Nvg.w / 2, cy - 14 * e, size * (1f - 0.08f * e), f, Nvg.CENTER, withA(tc, 0.85f * pa));
            }
        }
        if (idx >= 0 && idx < v.lines.size()) {
            String t = v.lines.get(idx);
            if (!t.isEmpty()) {
                float na = e * a;
                float yy = cy + 14 * (1f - e);
                Nvg.textBlur(t, Nvg.w / 2, yy, size, f, Nvg.CENTER, withA(tc, 0.45f * na), 10);
                Nvg.textBlur(t, Nvg.w / 2, yy, size, f, Nvg.CENTER, withA(0xFF000000, 0.35f * na), 3);
                Nvg.text(t, Nvg.w / 2, yy, size, f, Nvg.CENTER, withA(tc, na));
            }
        }
    }

    /** Drag the card while the chat is open (switches Position to Custom). */
    private void handleDrag(float x, float y, float w, float h) {
        if (!(mc.currentScreen instanceof ChatScreen)) {
            dragging = false;
            return;
        }
        float mx = (float) (mc.mouse.getX() * Nvg.w / mc.getWindow().getWidth());
        float my = (float) (mc.mouse.getY() * Nvg.h / mc.getWindow().getHeight());
        boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!down) {
            dragging = false;
            return;
        }
        if (!dragging && mx >= x && mx <= x + w && my >= y && my <= y + h) {
            dragging = true;
            dragDX = mx - x;
            dragDY = my - y;
            position.index = 4;
        }
        if (dragging) {
            customX = Math.max(2, Math.min(Nvg.w - w - 2, mx - dragDX));
            customY = Math.max(2, Math.min(Nvg.h - h - 2, my - dragDY));
        }
    }

    private static String time(long ms) {
        long sec = Math.max(0, ms / 1000);
        return sec / 60 + ":" + String.format("%02d", sec % 60);
    }
}

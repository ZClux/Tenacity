package tenacity.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.world.level.storage.LevelSummary;
import org.lwjgl.glfw.GLFW;
import tenacity.gfx.Nvg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Singleplayer: the same glass card language as the server list, with world thumbnails, search and a dock. */
public final class WorldsScreen extends NvgScreen {
    private static final float CARD_H = 84, GAP = 10;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("d MMM yyyy, HH:mm");
    private final Screen parent;
    private volatile List<LevelSummary> worlds = new ArrayList<>();
    private volatile boolean loaded;
    private int selected = -1, lastTapIndex = -1;
    private long lastTap, openedAt;
    private final Scroller scroller = new Scroller();
    private final Field search = new Field("wld-search");
    private final Map<String, Integer> thumbs = new HashMap<>();
    private LevelSummary starting;
    private long startAt;
    private float startK;

    public WorldsScreen(Screen parent) {
        super("Singleplayer");
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (openedAt == 0) openedAt = System.nanoTime();
        starting = null;
        startK = 0;
        var storage = client.getLevelStorage();
        try {
            storage.loadSummaries(storage.getLevelList()).thenAccept(list -> {
                List<LevelSummary> l = new ArrayList<>(list);
                l.sort((a, b) -> Long.compare(b.getLastPlayed(), a.getLastPlayed()));
                worlds = l;
                loaded = true;
            });
        } catch (Exception e) {
            loaded = true;
        }
    }

    @Override
    public void removed() {
        for (int img : thumbs.values()) Nvg.deleteImage(img);
        thumbs.clear();
    }

    private void play(LevelSummary s) {
        if (starting != null) return;
        starting = s;
        startAt = System.nanoTime();
    }

    private int thumb(LevelSummary s) {
        Integer c = thumbs.get(s.getName());
        if (c != null) return c;
        int img = 0;
        try {
            Path p = s.getIconPath();
            if (p != null && Files.isRegularFile(p)) img = Nvg.imageMem(Files.readAllBytes(p));
        } catch (Exception ignored) {
        }
        thumbs.put(s.getName(), img);
        return img;
    }

    private static String title(LevelSummary s) {
        return s.getDisplayName().isEmpty() ? s.getName() : s.getDisplayName();
    }

    @Override
    public void draw(float dt) {
        MinecraftClient mc = client;
        float t = (System.nanoTime() - openedAt) / 1e9f;
        open = clamp01(t / 0.9f);
        float e = stage(t, 0f, 0.7f);
        float W = Nvg.w, H = Nvg.h;
        float pad = Math.max(32, W * 0.045f);
        List<LevelSummary> all = worlds;
        List<Integer> rows = new ArrayList<>();
        String q = search.text.trim().toLowerCase();
        for (int i = 0; i < all.size(); i++)
            if (q.isEmpty() || title(all.get(i)).toLowerCase().contains(q) || all.get(i).getName().toLowerCase().contains(q)) rows.add(i);
        LevelSummary sel = selected >= 0 && selected < all.size() ? all.get(selected) : null;

        if (starting == null) {
            for (int k : Widgets.keys) {
                if (k == GLFW.GLFW_KEY_ESCAPE) {
                    if (!search.text.isEmpty()) search.text = "";
                    else mc.setScreen(parent);
                    Widgets.keys.clear();
                    Widgets.typed.setLength(0);
                    break;
                }
                int at = rows.indexOf(selected);
                if (k == GLFW.GLFW_KEY_DOWN && !rows.isEmpty()) selected = rows.get(Math.min(rows.size() - 1, at + 1));
                else if (k == GLFW.GLFW_KEY_UP && !rows.isEmpty()) selected = rows.get(Math.max(0, at < 0 ? 0 : at - 1));
                else if ((k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) && sel != null) play(sel);
            }
            search.focus = true;
            search.input();
        }

        String sub = !loaded ? "Loading worlds" : all.size() + (all.size() == 1 ? " world" : " worlds");
        if (header("Singleplayer", sub, pad, e)) mc.setScreen(parent);
        float sw = Math.min(300, W * 0.3f), sx = W - pad - sw + (1 - e) * 16, sy = 34 - (1 - e) * 12;
        Widgets.glass(sx, sy, sw, 40, 20, e);
        Nvg.save();
        Nvg.fade(e);
        search.draw(sx, sy, sw, 40, "Search worlds", "search", 1f);
        if (!search.text.isEmpty() && button("wld-clear", null, "close", sx + sw - 34, sy + 6, 28, 28, 2, true, 1f)) search.text = "";
        Nvg.restore();

        float lw = Math.min(900, W - pad * 2), lx = W / 2f - lw / 2f;
        float ly = 100, dockH = 60, lh = H - ly - dockH - 44;
        float content = rows.size() * (CARD_H + GAP) - GAP + 16;
        scroller.update(lx, ly, lw, lh, content, starting == null);
        float le = stage(t, 0.12f, 0.8f);

        if (!loaded) spinner(W / 2f, ly + lh * 0.4f, 16, 2f, le);
        else if (all.isEmpty()) empty(W / 2f, ly + lh * 0.42f, le, "No worlds yet", "Create your first world to get started.");
        else if (rows.isEmpty()) empty(W / 2f, ly + lh * 0.42f, le, "No matches", "Nothing matches \"" + search.text + "\".");

        Nvg.save();
        Nvg.clip(lx - 20, ly, lw + 40, lh);
        LevelSummary playHit = null;
        int playIdx = -1;
        for (int n = 0; n < rows.size(); n++) {
            int i = rows.get(n);
            LevelSummary s = all.get(i);
            float rk = stage(t, 0.16f + n * 0.05f, 0.75f);
            float ry = ly + 8 + n * (CARD_H + GAP) - scroller.pos + (1 - rk) * 26;
            if (ry + CARD_H < ly - 4 || ry > ly + lh + 4) continue;
            float a = rk * edgeFade(ry, CARD_H, ly, lh);
            boolean hov = starting == null && !scroller.dragging() && Widgets.in(lx, ry, lw, CARD_H) && Widgets.in(lx, ly, lw, lh);
            boolean over = card(s, i, lx, ry, lw, CARD_H, hov, a);
            if (hov && scroller.tap) {
                if (over) {
                    playHit = s;
                    playIdx = i;
                } else {
                    long now = System.currentTimeMillis();
                    if (lastTapIndex == i && selected == i && now - lastTap < 380) play(s);
                    selected = i;
                    lastTap = now;
                    lastTapIndex = i;
                }
            }
        }
        Nvg.restore();
        if (playHit != null) {
            selected = playIdx;
            play(playHit);
        }
        if (content > lh) {
            float max = Math.max(1, content - lh);
            float th = Math.max(36, lh * lh / content), tyy = ly + (lh - th) * clamp01(scroller.pos / max);
            Nvg.rrect(lx + lw + 12, tyy, 3, th, 1.5f, Nvg.alpha(0xFFFFFFFF, 0.3f * le));
        }

        // dock
        float da = stage(t, 0.25f, 0.8f);
        String[] labels = {"Play", "Create New World", "Back"};
        String[] icns = {"play", "plus", "back"};
        float[] ws = new float[3];
        float total = 0;
        for (int i = 0; i < 3; i++) {
            ws[i] = Nvg.textWidth(labels[i], 13.5f, Nvg.MED) + 17 + 8 + (i == 0 ? 52 : 32);
            total += ws[i];
        }
        float inner = 8, gap = 4;
        total += gap * 2 + inner * 2;
        float dx = W / 2f - total / 2f, dy = H - dockH - 22 + (1 - da) * 24;
        Widgets.glass(dx, dy, total, dockH, dockH / 2f, da);
        float bx = dx + inner;
        for (int i = 0; i < 3; i++) {
            boolean en = starting == null && (i != 0 || sel != null);
            if (button("wld-d" + i, labels[i], icns[i], bx, dy + inner, ws[i], dockH - inner * 2, i == 0 ? 1 : 2, en, da)) {
                switch (i) {
                    case 0 -> play(sel);
                    case 1 -> CreateWorldScreen.show(mc, () -> mc.setScreen(this));
                    default -> mc.setScreen(parent);
                }
            }
            bx += ws[i] + gap;
        }

        if (starting != null) {
            startK = Anim.approach(startK, 1f, 9f);
            Nvg.layer();
            float k = Anim.easeOut(startK);
            Nvg.rect(0, 0, W, H, Nvg.alpha(0xFF000000, 0.6f * k));
            float cx = W / 2f, cy = H / 2f - 10 + (1 - k) * 14;
            Nvg.save();
            Nvg.fade(k);
            Nvg.glowDisc(cx, cy - 20, 90, Nvg.alpha(0xFFFFFFFF, 0.08f), 0);
            icon("cube", cx, cy - 20, 24, 0xFFFFFFFF);
            spinner(cx, cy - 20, 38, 2f, 1f);
            Nvg.text("Loading world", cx, cy + 42, 18, Nvg.SEMI, Nvg.CENTER, 0xFFFFFFFF);
            Nvg.text(title(starting), cx, cy + 64, 12, Nvg.REG, Nvg.CENTER, Nvg.alpha(0xFFFFFFFF, 0.5f));
            Nvg.restore();
            if ((System.nanoTime() - startAt) / 1e9f > 0.5f) {
                LevelSummary s = starting;
                starting = null;
                client.createIntegratedServerLoader().start(s.getName(), () -> client.setScreen(this));
            }
        }
        frameEnd();
    }

    private void empty(float cx, float cy, float a, String title, String sub) {
        Nvg.save();
        Nvg.fade(a);
        float bob = (float) Math.sin(System.nanoTime() % 100_000_000_000L / 1e9 * 1.4) * 3f;
        Nvg.glowDisc(cx, cy - 30 + bob, 70, Nvg.alpha(0xFFFFFFFF, 0.07f), 0);
        Widgets.glass(cx - 34, cy - 64 + bob, 68, 68, 34, a);
        icon("cube", cx, cy - 30 + bob, 26, Nvg.alpha(0xFFFFFFFF, 0.9f));
        Nvg.text(title, cx, cy + 26, 18, Nvg.SEMI, Nvg.CENTER, 0xFFFFFFFF);
        Nvg.text(sub, cx, cy + 50, 12.5f, Nvg.REG, Nvg.CENTER, Nvg.alpha(0xFFFFFFFF, 0.5f));
        Nvg.restore();
    }

    private boolean card(LevelSummary s, int i, float x, float y, float w, float h, boolean hov, float a) {
        if (a <= 0.003f) return false;
        String key = "wld-c" + s.getName();
        float hv = Widgets.anim(key, hov ? 1f : 0f, 14f);
        float sl = Widgets.anim(key + "s", i == selected ? 1f : 0f, 12f);
        float sc = 1f + 0.008f * hv;
        float cw = w * sc, ch = h * sc, cx = x + w / 2f - cw / 2f, cy = y + h / 2f - ch / 2f - hv * 2f;
        float r = 20;
        Widgets.glass(cx, cy, cw, ch, r, a);
        Nvg.save();
        Nvg.fade(a);
        Nvg.rrect(cx, cy, cw, ch, r, Nvg.alpha(0xFFFFFFFF, 0.03f * hv + 0.045f * sl));
        if (hv > 0.01f) rrectRadial(cx, cy, cw, ch, r, Widgets.mx, Widgets.my, cw * 0.4f, Nvg.alpha(0xFFFFFFFF, 0.08f * hv), 0);
        if (sl > 0.01f) {
            Nvg.rrectStrokeGradient(cx, cy, cw, ch, r, 1.3f, cx, cy, cx + cw * 0.6f, cy + ch, Nvg.alpha(0xFFFFFFFF, 0.85f * sl), Nvg.alpha(0xFFFFFFFF, 0.2f * sl));
            Nvg.rrect(cx - 1, cy + ch / 2f - 14 * sl, 3, 28 * sl, 1.5f, Nvg.alpha(0xFFFFFFFF, sl));
        }
        // thumbnail
        float is = 56, ix = cx + 14, iy = cy + ch / 2f - is / 2f;
        int img = thumb(s);
        if (img > 0) {
            Nvg.rrect(ix, iy + 3, is, is, 13, Nvg.alpha(0xFF000000, 0.35f));
            Nvg.image(img, ix, iy, is, is, 13, 1f);
            Nvg.rrectStroke(ix, iy, is, is, 13, 1f, Nvg.alpha(0xFFFFFFFF, 0.12f));
        } else {
            Nvg.rrectGradient(ix, iy, is, is, 13, ix, iy, ix + is, iy + is, Nvg.alpha(0xFFFFFFFF, 0.16f), Nvg.alpha(0xFFFFFFFF, 0.04f));
            Nvg.rrectStroke(ix, iy, is, is, 13, 1f, Nvg.alpha(0xFFFFFFFF, 0.14f));
            icon("cube", ix + is / 2f, iy + is / 2f, 22, Nvg.alpha(0xFFFFFFFF, 0.8f));
        }
        // tags on the right
        float rx = cx + cw - 20 - 44 * hv;
        String mode = s.isHardcore() ? "Hardcore" : s.getGameMode().getTranslatableName().getString();
        List<String> tags = new ArrayList<>();
        tags.add(mode);
        if (s.hasCheats()) tags.add("Cheats");
        float tagX = rx;
        for (String tag : tags) {
            float tw = Nvg.textWidth(tag, 10.5f, Nvg.MED) + 18;
            tagX -= tw;
            Nvg.rrect(tagX, cy + 16, tw, 22, 11, Nvg.alpha(0xFFFFFFFF, 0.07f));
            Nvg.rrectStroke(tagX, cy + 16, tw, 22, 11, 1f, Nvg.alpha(0xFFFFFFFF, 0.14f));
            Nvg.text(tag, tagX + tw / 2f, cy + 27.5f, 10.5f, Nvg.MED, Nvg.CENTER, Nvg.alpha(0xFFFFFFFF, 0.85f));
            tagX -= 6;
        }
        String ver = s.getVersion().getString();
        Nvg.text(ver, rx, cy + 56, 11, Nvg.REG, Nvg.RIGHT, Nvg.alpha(0xFFFFFFFF, 0.4f));
        float textRight = Math.min(tagX, rx - Nvg.textWidth(ver, 11, Nvg.REG)) - 16;

        float tx = ix + is + 16, maxW = textRight - tx;
        Nvg.text(Nvg.fit(title(s), 15.5f, Nvg.SEMI, maxW), tx, cy + 25, 15.5f, Nvg.SEMI, Nvg.LEFT, 0xFFFFFFFF);
        String when = s.getLastPlayed() > 0 ? "Played " + FMT.format(new Date(s.getLastPlayed())) : "Never played";
        Nvg.text(Nvg.fit(when, 12, Nvg.REG, maxW), tx, cy + 45, 12, Nvg.REG, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.55f));
        Nvg.text(Nvg.fit(s.getName(), 10.5f, Nvg.MED, maxW), tx, cy + ch - 16, 10.5f, Nvg.MED, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.28f));

        boolean overPlay = false;
        if (hv > 0.01f) {
            float bx = cx + cw - 14 - 40 * hv, by = cy + ch / 2f - 20;
            overPlay = Widgets.in(bx, by, 40, 40);
            float ph = Widgets.anim(key + "p", overPlay ? 1f : 0f, 16f);
            float pr = 18 + 2 * ph;
            Nvg.circle(bx + 20, by + 20, pr, Nvg.alpha(0xFFFFFFFF, hv * (0.9f + 0.1f * ph)));
            if (ph > 0.01f) Nvg.glowDisc(bx + 20, by + 20, pr + 14, Nvg.alpha(0xFFFFFFFF, 0.25f * ph), 0);
            icon("play", bx + 21, by + 20, 15, Nvg.alpha(0xFF0A0B0E, hv));
        }
        Nvg.restore();
        return overPlay;
    }
}

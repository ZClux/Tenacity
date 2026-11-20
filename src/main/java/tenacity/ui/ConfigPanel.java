package tenacity.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import tenacity.core.Profiles;
import tenacity.gfx.Icons;
import tenacity.gfx.Nvg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Config manager opened from the click gui (button next to the search bar): local profiles and cloud configs.
 * Immediate mode, drawn inside the click gui's scaled space; input is handed over by {@link ClickGui}.
 */
public final class ConfigPanel {
    /** Panel size, shared with {@link ChatPanel} so both windows match exactly. */
    public static final float W = 660, H = 470;

    /** Top-left of the centered, anchored panel for an open animation value e (0..1 eased). */
    public static float panelX(float gw) {
        return gw / 2f - W / 2f;
    }

    public static float panelY(float gh, float e) {
        return gh / 2f - H / 2f + (1 - e) * 22 - 10;
    }

    public static boolean open;
    private static float anim, tabAnim;
    private static int tab; // 0 local, 1 cloud

    /* input for this frame */
    private static float mx, my, scroll;
    private static boolean pressed;
    private static final Map<String, Float> A = new HashMap<>();

    /* text fields */
    private static String focus = "";
    private static final Map<String, String> FIELDS = new HashMap<>();

    /* local */
    private static List<Profiles.Local> locals;
    private static boolean justOpened, pWas;
    private static float localScroll, localScrollT;
    private static String confirm = "";
    private static long confirmAt;

    /* cloud */
    private static final List<Profiles.Cloud> clouds = new ArrayList<>();
    private static volatile boolean loading, busy;
    private static volatile String cloudError;
    private static boolean more, cloudLoaded;
    private static int page, total, requestGen;
    private static String sort = "new", lastQuery = "";
    private static long queryChanged;
    private static float cloudScroll, cloudScrollT;
    private static boolean uploadOpen;
    private static float uploadAnim;
    private static int curIcon = -1;
    private static String curIconFor;

    private ConfigPanel() {
    }

    public static void toggle() {
        open = !open;
        if (open) {
            locals = null;
            focus = "";
            justOpened = true;
            openedAt = System.currentTimeMillis();
        }
    }

    private static long openedAt;

    /** Dev harness: open on a given tab. */
    public static void openTab(int t) {
        open = true;
        tab = t;
        tabAnim = t;
        locals = null;
    }

    public static boolean visible() {
        return open || anim > 0.01f;
    }

    private static boolean in(float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float a(String id, float target, float speed) {
        float v = Anim.approach(A.getOrDefault(id, 0f), target, speed);
        A.put(id, v);
        return v;
    }

    private static void toast(String title, String body) {
        Toasts.push("gear", title, body);
    }

    private static boolean armed(String id) {
        return confirm.equals(id) && System.currentTimeMillis() - confirmAt < 3500;
    }

    private static void arm(String id) {
        confirm = id;
        confirmAt = System.currentTimeMillis();
    }

    /* ------------------------------------------------------------------ frame */

    /** Draws the panel; returns nothing - input passed here was taken away from the click gui. */
    public static void draw(float gw, float gh, float pmx, float pmy, boolean ppressed, float pscroll, List<Integer> keys, CharSequence typed, float gs) {
        anim = Anim.approach(anim, open ? 1f : 0f, open ? 8f : 11f);
        if (anim < 0.01f && !open) {
            releaseImages();
            return;
        }
        mx = pmx;
        my = pmy;
        pressed = ppressed && open;
        boolean edge = pressed && !pWas;
        pWas = pressed;
        scroll = open ? pscroll : 0;

        if (open) handleKeys(keys, typed);
        if (locals == null) locals = Profiles.list();
        if (tab == 1 && !cloudLoaded && !loading) refresh(true);
        // keep the cloud list fresh: reload when the panel opens and every 20 s while you look at it
        if (open && tab == 1 && !loading && page == 0 && System.currentTimeMillis() - lastRefresh > (justOpened ? 0 : 20000)) refresh(true);

        float e = Anim.easeOut(anim);
        float x = gw / 2f - W / 2f, y = gh / 2f - H / 2f + (1 - e) * 22 - 10;

        Nvg.layer();
        Nvg.save();
        Nvg.scale(gs, gs);
        // dim the gui behind
        Nvg.rect(0, 0, gw, gh, Nvg.alpha(0xFF000000, 0.28f * e));
        Nvg.restore();
        Nvg.layer();
        Nvg.save();
        Nvg.scale(gs, gs);
        Theme.panel(x, y, W, H, 26, e, 30);
        Nvg.save();
        Nvg.fade(e);

        // click outside closes, but only on a fresh press that started after the panel was already open: the press
        // that opened the panel lands outside of it and must not close it again in the same frame
        if (edge && !in(x, y, W, H)) {
            // ignore the click that opened it (and anything in the first moments after), whatever frame it lands in
            if (justOpened || System.currentTimeMillis() - openedAt < 350) justOpened = false;
            else {
                open = false;
                pressed = false;
            }
        }

        // header
        drawIcon(tab == 0 ? "folder" : "cloud", x + 22, y + 20, 22, Theme.text());
        Nvg.text("Configs", x + 54, y + 31, 19, Nvg.SEMI, Nvg.LEFT, Theme.text());
        Nvg.text(tab == 0 ? "Profiles saved on this computer" : (cloudLoaded && !loading && cloudError == null ? "Shared by the community  -  " + total + (total == 1 ? " config" : " configs") : "Shared by the community"), x + 136, y + 32, 12, Nvg.REG, Nvg.LEFT, Theme.faint());

        // segmented tabs
        tabAnim = Anim.approach(tabAnim, tab, 14f);
        float tw = 176, tx = x + W - tw - 62, ty = y + 16;
        Nvg.rrect(tx, ty, tw, 30, 15, Nvg.alpha(Theme.text(), 0.08f));
        Nvg.rrect(tx + 3 + (tw / 2f - 3) * Anim.smooth(tabAnim), ty + 3, tw / 2f - 3, 24, 12, Theme.text());
        for (int i = 0; i < 2; i++) {
            float sx = tx + i * tw / 2f;
            float sel = 1f - Math.min(1f, Math.abs(tabAnim - i));
            int col = Nvg.mix(Theme.dim(), Theme.onFill(), sel);
            drawIcon(i == 0 ? "folder" : "cloud", sx + 18, ty + 7, 16, col);
            Nvg.text(i == 0 ? "Local" : "Cloud", sx + 40, ty + 15.5f, 12.5f, Nvg.SEMI, Nvg.LEFT, col);
            if (pressed && in(sx, ty, tw / 2f, 30) && tab != i) {
                tab = i;
                focus = "";
                pressed = false;
            }
        }
        // close
        float cx = x + W - 44, cy = y + 16;
        float ch = a("close", in(cx, cy, 30, 30) ? 1f : 0f, 16f);
        Nvg.rrect(cx, cy, 30, 30, 15, Nvg.alpha(Theme.text(), 0.06f + 0.1f * ch));
        Nvg.line(cx + 10.5f, cy + 10.5f, cx + 19.5f, cy + 19.5f, 1.8f, Theme.text());
        Nvg.line(cx + 19.5f, cy + 10.5f, cx + 10.5f, cy + 19.5f, 1.8f, Theme.text());
        if (pressed && in(cx, cy, 30, 30)) {
            open = false;
            pressed = false;
        }
        Nvg.rect(x + 20, y + 60, W - 40, 1, Nvg.alpha(Theme.text(), 0.08f));

        float bx = x + 20, by = y + 74, bw = W - 40, bh = H - 94;
        // slide between tabs
        float off = tabAnim * (bw + 40);
        Nvg.save();
        Nvg.clip(x + 2, by - 4, W - 4, bh + 16);
        if (tabAnim < 0.99f) {
            Nvg.save();
            Nvg.translate(-off, 0);
            boolean was = pressed;
            if (tab != 0) pressed = false;
            drawLocal(bx, by, bw, bh, tab == 0);
            pressed = was;
            Nvg.restore();
        }
        if (tabAnim > 0.01f) {
            Nvg.save();
            Nvg.translate(bw + 40 - off, 0);
            boolean was = pressed;
            if (tab != 1) pressed = false;
            drawCloud(bx, by, bw, bh, tab == 1);
            pressed = was;
            Nvg.restore();
        }
        Nvg.restore();

        Nvg.restore();
        Nvg.restore();
    }

    private static void handleKeys(List<Integer> keys, CharSequence typed) {
        boolean ctrl = GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(MinecraftClient.getInstance().getWindow().getHandle(), GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        for (int k : keys) {
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                if (!focus.isEmpty()) focus = "";
                else if (uploadOpen) uploadOpen = false;
                else open = false;
            } else if (!focus.isEmpty()) {
                String v = FIELDS.getOrDefault(focus, "");
                if (k == GLFW.GLFW_KEY_BACKSPACE && !v.isEmpty()) FIELDS.put(focus, ctrl ? "" : v.substring(0, v.length() - 1));
                else if (k == GLFW.GLFW_KEY_V && ctrl) FIELDS.put(focus, v + MinecraftClient.getInstance().keyboard.getClipboard().replaceAll("[\\r\\n\\t]", " "));
                else if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) submit(focus);
                else if (k == GLFW.GLFW_KEY_TAB && focus.equals("upName")) focus = "upDesc";
            }
        }
        if (!focus.isEmpty() && typed.length() > 0) FIELDS.put(focus, FIELDS.getOrDefault(focus, "") + typed);
        if (!focus.isEmpty()) {
            int max = focus.equals("upDesc") ? 160 : 32;
            String v = FIELDS.getOrDefault(focus, "");
            if (v.length() > max) FIELDS.put(focus, v.substring(0, max));
        }
    }

    private static void submit(String field) {
        switch (field) {
            case "saveName" -> saveLocal();
            case "upName", "upDesc" -> upload();
            case "search" -> refresh(true);
            default -> {
            }
        }
    }

    /* ------------------------------------------------------------------ widgets */

    private static boolean button(String id, String label, String icon, float x, float y, float w, float h, int style, boolean enabled) {
        boolean hov = enabled && in(x, y, w, h);
        float t = a("b" + id, hov ? 1f : 0f, 16f);
        float p = a("p" + id, 0f, 10f);
        float g = t * 1.5f - p * 2f;
        float bx = x - g, by = y - g / 2f, bw = w + g * 2, bh = h + g;
        float r = Math.min(bh / 2f, 12f);
        int fg;
        if (style == 1) {
            Nvg.rrect(bx, by, bw, bh, r, Nvg.alpha(Theme.text(), enabled ? 0.92f + 0.08f * t : 0.35f));
            fg = Theme.onFill();
        } else if (style == 2) {
            Nvg.rrect(bx, by, bw, bh, r, Nvg.alpha(0xFFE5484D, 0.75f + 0.25f * t));
            fg = 0xFFFFFFFF;
        } else {
            Nvg.rrect(bx, by, bw, bh, r, Nvg.alpha(Theme.text(), (0.09f + 0.09f * t) * (enabled ? 1f : 0.5f)));
            fg = enabled ? Theme.text() : Theme.faint();
        }
        float tw = Nvg.textWidth(label, 12, Nvg.SEMI) + (icon != null ? 20 : 0);
        float sx = x + w / 2f - tw / 2f;
        if (icon != null) {
            drawIcon(icon, sx, y + h / 2f - 7.5f, 15, fg);
            sx += 20;
        }
        Nvg.text(label, sx, y + h / 2f + 0.5f, 12, Nvg.SEMI, Nvg.LEFT, fg);
        if (hov && pressed) {
            A.put("p" + id, 1f);
            pressed = false;
            return true;
        }
        return false;
    }

    private static void field(String id, String hint, float x, float y, float w, float h, String icon) {
        boolean f = focus.equals(id);
        boolean hov = in(x, y, w, h);
        float t = a("f" + id, f ? 1f : hov ? 0.4f : 0f, 14f);
        Nvg.rrect(x, y, w, h, Math.min(12, h / 2f), Nvg.alpha(Theme.text(), 0.07f + 0.05f * t));
        Nvg.rrectStroke(x, y, w, h, Math.min(12, h / 2f), 1f, Nvg.alpha(Theme.text(), 0.06f + 0.3f * t));
        float tx = x + 13;
        if (icon != null) {
            Icons.draw(icon, x + 11, y + h / 2f - 8, 16, f ? Theme.text() : Theme.dim());
            tx += 22;
        }
        String v = FIELDS.getOrDefault(id, "");
        Nvg.save();
        Nvg.clip(x + 4, y, w - 8, h);
        if (v.isEmpty()) Nvg.text(hint, tx, y + h / 2f + 0.5f, 12.5f, Nvg.REG, Nvg.LEFT, Theme.faint());
        else {
            float vw = Nvg.textWidth(v, 12.5f, Nvg.MED);
            float shift = Math.max(0, vw - (w - (tx - x) - 16));
            Nvg.text(v, tx - shift, y + h / 2f + 0.5f, 12.5f, Nvg.MED, Nvg.LEFT, Theme.text());
            tx += vw - shift;
        }
        if (f) {
            float blink = (System.currentTimeMillis() / 500 % 2 == 0) ? 1f : 0.2f;
            Nvg.rrect(tx + 2, y + h / 2f - 7.5f, 1.6f, 15, 0.8f, Nvg.alpha(Theme.text(), blink));
        }
        Nvg.restore();
        if (pressed && hov) {
            focus = id;
            pressed = false;
        }
    }

    private static void spinner(float cx, float cy, float r, float w) {
        float t = (System.nanoTime() % 100_000_000_000L) / 1e9f * 5.2f;
        Nvg.circleStroke(cx, cy, r, w, Nvg.alpha(Theme.text(), 0.12f));
        Nvg.beginPath();
        float sweep = 1.2f + 0.9f * (float) Math.sin(t * 0.6f);
        Nvg.arcPath(cx, cy, r, t, t + sweep);
        Nvg.strokePath(w, Nvg.alpha(Theme.text(), 0.95f));
    }

    private static float scrollbar(float x, float y, float h, float content, float sc) {
        if (content <= h) return 0;
        float th = Math.max(30, h * h / content);
        float ty = y + (h - th) * (sc / (content - h));
        Nvg.rrect(x, ty, 3, th, 1.5f, Nvg.alpha(Theme.text(), 0.22f));
        return 0;
    }

    private static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 60) return "just now";
        if (s < 3600) return s / 60 + "m ago";
        if (s < 86400) return s / 3600 + "h ago";
        if (s < 86400 * 30) return s / 86400 + "d ago";
        if (s < 86400 * 365) return s / (86400 * 30) + "mo ago";
        return s / (86400 * 365) + "y ago";
    }

    /* ------------------------------------------------------------------ local tab */

    private static void saveLocal() {
        String n = Profiles.clean(FIELDS.getOrDefault("saveName", ""));
        if (n.isEmpty()) {
            focus = "saveName";
            toast("Name required", "Type a name for the config");
            return;
        }
        boolean over = Profiles.exists(n);
        if (Profiles.save(n)) {
            toast(over ? "Config updated" : "Config saved", n);
            FIELDS.put("saveName", "");
            focus = "";
            locals = null;
        } else toast("Save failed", n);
    }

    private static void drawLocal(float x, float y, float w, float h, boolean active) {
        // save row
        float bw = 92;
        field("saveName", "Name your current setup...", x, y, w - bw * 2 - 20, 38, "pin");
        if (button("save", "Save", "check", x + w - bw * 2 - 10, y, bw, 38, 1, true)) saveLocal();
        if (button("import", "Import", "download", x + w - bw, y, bw, 38, 0, true)) {
            String n = Profiles.importClipboard();
            if (n != null) {
                toast("Config imported", n);
                locals = null;
            } else toast("Import failed", "Clipboard does not contain a Tenacity config");
        }

        float ly = y + 52, lh = h - 52;
        List<Profiles.Local> list = locals == null ? List.of() : locals;
        float rowH = 54, content = list.size() * (rowH + 6);
        if (active && scroll != 0 && in(x, ly, w, lh)) localScrollT -= scroll * 40;
        localScrollT = Math.max(0, Math.min(Math.max(0, content - lh), localScrollT));
        localScroll = Anim.approach(localScroll, localScrollT, 16f);

        if (list.isEmpty()) {
            drawIcon("folder", x + w / 2f - 20, ly + lh / 2f - 46, 40, Theme.faint());
            Nvg.text("No saved configs yet", x + w / 2f, ly + lh / 2f + 8, 14, Nvg.SEMI, Nvg.CENTER, Theme.dim());
            Nvg.text("Name your setup above and press Save", x + w / 2f, ly + lh / 2f + 28, 12, Nvg.REG, Nvg.CENTER, Theme.faint());
            return;
        }
        Nvg.save();
        Nvg.clip(x - 4, ly, w + 8, lh);
        float yy = ly - localScroll;
        String renaming = FIELDS.getOrDefault("renaming", "");
        for (int i = 0; i < list.size(); i++) {
            Profiles.Local c = list.get(i);
            float ry = yy + i * (rowH + 6);
            if (ry + rowH < ly || ry > ly + lh) continue;
            boolean visible = my >= ly && my <= ly + lh;
            boolean hov = visible && in(x, ry, w, rowH);
            float ht = a("row" + c.name(), hov ? 1f : 0f, 14f);
            float appear = a("rowIn" + c.name(), 1f, 9f);
            Nvg.save();
            Nvg.fade(appear);
            Nvg.rrect(x, ry, w, rowH, 14, Nvg.alpha(Theme.text(), 0.05f + 0.05f * ht));
            // letter tile
            Nvg.rrect(x + 10, ry + 9, 36, 36, 10, Nvg.alpha(Theme.text(), 0.12f));
            Nvg.text(c.name().substring(0, 1).toUpperCase(Locale.ROOT), x + 28, ry + 27.5f, 16, Nvg.SEMI, Nvg.CENTER, Theme.text());
            boolean pressedBefore = pressed;
            if (!visible) pressed = false;
            float bx = x + w - 10;
            float btnW = 68;
            if (renaming.equals(c.name())) {
                field("rename", "New name", x + 56, ry + 11, 220, 32, null);
                bx -= btnW;
                if (button("rnok" + c.name(), "Rename", null, bx, ry + 13, btnW, 28, 1, true)) {
                    if (Profiles.rename(c.name(), FIELDS.getOrDefault("rename", ""))) {
                        toast("Config renamed", Profiles.clean(FIELDS.get("rename")));
                        locals = null;
                    } else toast("Rename failed", "Name empty or already taken");
                    FIELDS.remove("renaming");
                    focus = "";
                }
                bx -= btnW + 6;
                if (button("rncancel" + c.name(), "Cancel", null, bx, ry + 13, btnW, 28, 0, true)) {
                    FIELDS.remove("renaming");
                    focus = "";
                }
            } else {
                Nvg.text(Nvg.fit(c.name(), 14, Nvg.SEMI, w - 380), x + 58, ry + 21, 14, Nvg.SEMI, Nvg.LEFT, Theme.text());
                Nvg.text("Saved " + ago(c.modified()) + "  -  " + Math.max(1, c.size() / 1024) + " KB", x + 58, ry + 38, 11, Nvg.REG, Nvg.LEFT, Theme.faint());
                boolean del = armed("del" + c.name());
                bx -= btnW;
                if (button("del" + c.name(), del ? "Sure?" : "Delete", null, bx, ry + 13, btnW, 28, del ? 2 : 0, true)) {
                    if (del) {
                        Profiles.delete(c.name());
                        toast("Config deleted", c.name());
                        locals = null;
                        confirm = "";
                    } else arm("del" + c.name());
                }
                bx -= btnW + 6;
                if (button("ren" + c.name(), "Rename", null, bx, ry + 13, btnW, 28, 0, true)) {
                    FIELDS.put("renaming", c.name());
                    FIELDS.put("rename", c.name());
                    focus = "rename";
                }
                bx -= btnW + 6;
                if (button("exp" + c.name(), "Export", null, bx, ry + 13, btnW, 28, 0, true)) {
                    toast(Profiles.exportClipboard(c.name()) ? "Copied to clipboard" : "Export failed", c.name());
                }
                bx -= btnW + 6;
                if (button("load" + c.name(), "Load", null, bx, ry + 13, btnW, 28, 1, true)) {
                    toast(Profiles.load(c.name()) ? "Config loaded" : "Load failed", c.name());
                }
            }
            if (!visible) pressed = pressedBefore;
            Nvg.restore();
        }
        Nvg.restore();
        scrollbar(x + w + 6, ly, lh, content, localScroll);
    }

    /* ------------------------------------------------------------------ cloud tab */

    private static long lastRefresh;

    private static void refresh(boolean reset) {
        lastRefresh = System.currentTimeMillis();
        if (reset) {
            page = 0;
            cloudScrollT = 0;
        }
        int gen = ++requestGen;
        loading = true;
        cloudError = null;
        cloudLoaded = true;
        lastQuery = FIELDS.getOrDefault("search", "");
        final int pg = page;
        Profiles.browse(sort, lastQuery, pg).whenComplete((res, err) -> MinecraftClient.getInstance().execute(() -> {
            if (gen != requestGen) return;
            loading = false;
            if (err != null) {
                cloudError = rootMessage(err);
                return;
            }
            if (pg == 0) {
                releaseImages();
                clouds.clear();
            }
            clouds.addAll(res.items());
            more = res.more();
            total = res.total();
        }));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        if (t instanceof java.net.ConnectException || t instanceof java.net.http.HttpConnectTimeoutException) return "Could not reach the config server";
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    private static void releaseImages() {
        for (Profiles.Cloud c : clouds) {
            if (c.img > 0) Nvg.deleteImage(c.img);
            c.img = -1;
        }
        if (curIcon > 0) Nvg.deleteImage(curIcon);
        curIcon = -1;
        curIconFor = null;
    }

    private static void upload() {
        if (busy) return;
        String n = Profiles.clean(FIELDS.getOrDefault("upName", ""));
        if (n.isEmpty()) {
            focus = "upName";
            toast("Name required", "Give your config a name");
            return;
        }
        busy = true;
        focus = "";
        Profiles.upload(n, FIELDS.getOrDefault("upDesc", "").trim()).whenComplete((id, err) -> MinecraftClient.getInstance().execute(() -> {
            busy = false;
            if (err != null) {
                toast("Upload failed", rootMessage(err));
                return;
            }
            toast("Config uploaded", n + "  -  id " + id);
            FIELDS.put("upName", "");
            FIELDS.put("upDesc", "");
            uploadOpen = false;
            sort = "new";
            refresh(true);
        }));
    }

    private static void loadCloud(Profiles.Cloud c) {
        if (busy) return;
        busy = true;
        Profiles.download(c.id).whenComplete((cfg, err) -> MinecraftClient.getInstance().execute(() -> {
            busy = false;
            if (err != null) {
                toast("Download failed", rootMessage(err));
                return;
            }
            c.downloads++;
            JsonObject root = cfg;
            if (Profiles.apply(root)) {
                // keep a local copy so it can be loaded again offline
                String base = Profiles.clean(c.name + " by " + c.author);
                if (!base.isEmpty() && !Profiles.exists(base)) {
                    root.addProperty("name", base);
                    Profiles.save(base);
                }
                locals = null;
                toast("Config loaded", c.name + " by " + c.author);
            } else toast("Load failed", "Config is not compatible");
        }));
    }

    private static void icon(Profiles.Cloud c, float x, float y, float s) {
        if (c.img == -1) c.img = c.icon == null ? 0 : Nvg.imageMem(c.icon);
        serverTile(c.img, c.server, x, y, s);
    }

    private static void serverTile(int img, String server, float x, float y, float s) {
        if (img > 0) {
            Nvg.image(img, x, y, s, s, s * 0.22f, 1f);
            Nvg.rrectStroke(x, y, s, s, s * 0.22f, 1f, Nvg.alpha(Theme.text(), 0.12f));
            return;
        }
        boolean sp = server == null || server.isEmpty() || server.equals("Singleplayer") || server.equals("Menu");
        int hue = sp ? 0xFF6B7280 : java.awt.Color.HSBtoRGB((server.hashCode() & 0xFFFF) / 65535f, 0.45f, 0.75f);
        Nvg.rrectGradient(x, y, s, s, s * 0.22f, x, y, x + s, y + s, hue, Nvg.mix(hue, 0xFF000000, 0.45f));
        if (sp) Icons.draw("base", x + s * 0.2f, y + s * 0.2f, s * 0.6f, 0xFFFFFFFF);
        else Nvg.text(server.substring(0, 1).toUpperCase(Locale.ROOT), x + s / 2f, y + s / 2f + 1, s * 0.46f, Nvg.SEMI, Nvg.CENTER, 0xFFFFFFFF);
    }

    private static void drawCloud(float x, float y, float w, float h, boolean active) {
        // search changes refresh after a short pause
        String q = FIELDS.getOrDefault("search", "");
        if (!q.equals(lastQuery)) {
            if (queryChanged == 0) queryChanged = System.currentTimeMillis();
            if (System.currentTimeMillis() - queryChanged > 450) {
                queryChanged = 0;
                refresh(true);
            }
        } else queryChanged = 0;

        float sortW = 132, upW = 110;
        field("search", "Search configs, authors, servers...", x, y, w - sortW - upW - 20, 38, "search");
        // sort segmented
        float sx = x + w - sortW - upW - 10;
        float st = a("sort", sort.equals("top") ? 1f : 0f, 14f);
        Nvg.rrect(sx, y, sortW, 38, 12, Nvg.alpha(Theme.text(), 0.08f));
        Nvg.rrect(sx + 3 + (sortW / 2f - 3) * Anim.smooth(st), y + 3, sortW / 2f - 3, 32, 10, Nvg.alpha(Theme.text(), 0.16f));
        for (int i = 0; i < 2; i++) {
            String s = i == 0 ? "new" : "top";
            Nvg.text(i == 0 ? "Newest" : "Top", sx + sortW / 4f + i * sortW / 2f, y + 19.5f, 12, Nvg.SEMI, Nvg.CENTER, sort.equals(s) ? Theme.text() : Theme.dim());
            if (pressed && in(sx + i * sortW / 2f, y, sortW / 2f, 38) && !sort.equals(s)) {
                sort = s;
                pressed = false;
                refresh(true);
            }
        }
        if (button("upload", uploadOpen ? "Cancel" : "Upload", uploadOpen ? null : "upload", x + w - upW, y, upW, 38, uploadOpen ? 0 : 1, true)) {
            uploadOpen = !uploadOpen;
            if (uploadOpen) focus = "upName";
        }

        // upload form slides down
        uploadAnim = Anim.approach(uploadAnim, uploadOpen ? 1f : 0f, 12f);
        float formH = 132 * Anim.smooth(uploadAnim);
        float ly = y + 50;
        if (formH > 1) {
            Nvg.save();
            Nvg.clip(x - 2, ly, w + 4, formH);
            Nvg.fade(uploadAnim);
            Nvg.rrect(x, ly, w, 122, 16, Nvg.alpha(Theme.text(), 0.06f));
            String server = Profiles.currentServer();
            if (curIconFor == null || !curIconFor.equals(server)) {
                if (curIcon > 0) Nvg.deleteImage(curIcon);
                byte[] fav = Profiles.currentIcon();
                curIcon = fav == null ? 0 : Nvg.imageMem(fav);
                curIconFor = server;
            }
            serverTile(curIcon, server, x + 14, ly + 14, 44);
            Nvg.text("Uploading from", x + 70, ly + 28, 11, Nvg.REG, Nvg.LEFT, Theme.faint());
            Nvg.text(Nvg.fit(server, 13, Nvg.SEMI, 150), x + 70, ly + 45, 13, Nvg.SEMI, Nvg.LEFT, Theme.text());
            String author = MinecraftClient.getInstance().getSession().getUsername();
            Nvg.text("as " + author, x + 14, ly + 82, 11, Nvg.REG, Nvg.LEFT, Theme.faint());
            float fx = x + 230, fw = w - 230 - 14;
            field("upName", "Config name", fx, ly + 12, fw - 130, 34, null);
            field("upDesc", "Short description (optional)", fx, ly + 54, fw, 34, null);
            if (busy && uploadOpen) {
                spinner(fx + fw - 60, ly + 29, 8, 2f);
            } else if (button("publish", "Publish", "cloud", fx + fw - 120, ly + 12, 120, 34, 1, true)) upload();
            Nvg.text("Shares your modules, settings and keybinds. The menu layout stays local to you.", fx, ly + 106, 10.5f, Nvg.REG, Nvg.LEFT, Theme.faint());
            Nvg.restore();
            ly += formH;
        }

        float lh = y + h - ly;
        int cols = 2;
        float gap = 10, cw = (w - gap) / cols, chh = 104;
        int rows = (clouds.size() + cols - 1) / cols;
        float content = rows * (chh + gap) + (more ? 50 : 0);
        if (active && scroll != 0 && in(x, ly, w, lh)) cloudScrollT -= scroll * 44;
        cloudScrollT = Math.max(0, Math.min(Math.max(0, content - lh), cloudScrollT));
        cloudScroll = Anim.approach(cloudScroll, cloudScrollT, 16f);

        if (clouds.isEmpty()) {
            if (loading) {
                spinner(x + w / 2f, ly + lh / 2f - 10, 16, 2.4f);
                Nvg.text("Loading configs...", x + w / 2f, ly + lh / 2f + 24, 12, Nvg.MED, Nvg.CENTER, Theme.dim());
            } else if (cloudError != null) {
                drawIcon("cloud", x + w / 2f - 20, ly + lh / 2f - 52, 40, Theme.faint());
                Nvg.text("Could not load configs", x + w / 2f, ly + lh / 2f + 2, 14, Nvg.SEMI, Nvg.CENTER, Theme.dim());
                Nvg.text(Nvg.fit(cloudError, 12, Nvg.REG, w - 60), x + w / 2f, ly + lh / 2f + 22, 12, Nvg.REG, Nvg.CENTER, Theme.faint());
                if (button("retry", "Retry", null, x + w / 2f - 50, ly + lh / 2f + 40, 100, 30, 0, true)) refresh(true);
            } else {
                drawIcon("cloud", x + w / 2f - 20, ly + lh / 2f - 46, 40, Theme.faint());
                Nvg.text(lastQuery.isEmpty() ? "No configs shared yet" : "Nothing matches \"" + lastQuery + "\"", x + w / 2f, ly + lh / 2f + 8, 14, Nvg.SEMI, Nvg.CENTER, Theme.dim());
                Nvg.text("Be the first - press Upload", x + w / 2f, ly + lh / 2f + 28, 12, Nvg.REG, Nvg.CENTER, Theme.faint());
            }
            return;
        }

        Nvg.save();
        Nvg.clip(x - 4, ly, w + 8, lh);
        boolean visible = my >= ly && my <= ly + lh;
        boolean pressedBefore = pressed;
        if (!visible) pressed = false;
        float top = ly + 2 - cloudScroll;
        for (int i = 0; i < clouds.size(); i++) {
            Profiles.Cloud c = clouds.get(i);
            float cx = x + (i % cols) * (cw + gap), cy = top + (i / cols) * (chh + gap);
            if (cy + chh < ly || cy > ly + lh) continue;
            boolean hov = visible && in(cx, cy, cw, chh);
            float ht = a("cc" + c.id, hov ? 1f : 0f, 14f);
            float appear = Anim.easeOut(a("ci" + c.id, 1f, 6f));
            Nvg.save();
            Nvg.fade(appear);
            Nvg.translate(0, (1 - appear) * 10);
            Nvg.rrect(cx, cy, cw, chh, 16, Nvg.alpha(Theme.text(), 0.05f + 0.05f * ht));
            Nvg.rrectStroke(cx, cy, cw, chh, 16, 1f, Nvg.alpha(Theme.text(), 0.05f + 0.07f * ht));
            icon(c, cx + 12, cy + 12, 48);
            float tx = cx + 72, tmax = cw - 72 - 12;
            Nvg.text(Nvg.fit(c.name, 14, Nvg.SEMI, tmax - 40), tx, cy + 22, 14, Nvg.SEMI, Nvg.LEFT, Theme.text());
            Nvg.text(Nvg.fit("by " + c.author, 11.5f, Nvg.MED, tmax), tx, cy + 40, 11.5f, Nvg.MED, Nvg.LEFT, Theme.dim());
            Icons.draw("globe", tx - 1, cy + 49, 12, Theme.faint());
            Nvg.text(Nvg.fit(c.server, 11, Nvg.REG, tmax - 16), tx + 15, cy + 55.5f, 11, Nvg.REG, Nvg.LEFT, Theme.faint());
            if (!c.description.isEmpty()) Nvg.text(Nvg.fit(c.description, 11, Nvg.REG, cw - 130), cx + 12, cy + 84, 11, Nvg.REG, Nvg.LEFT, Theme.dim());
            else Nvg.text(ago(c.created) + (c.version.isEmpty() ? "" : "  -  v" + c.version), cx + 12, cy + 84, 11, Nvg.REG, Nvg.LEFT, Theme.faint());
            // downloads chip
            String dl = String.valueOf(c.downloads);
            float dw = Nvg.textWidth(dl, 10.5f, Nvg.SEMI) + 26;
            Nvg.rrect(cx + cw - dw - 12, cy + 12, dw, 20, 10, Nvg.alpha(Theme.text(), 0.1f));
            drawIcon("download", cx + cw - dw - 6, cy + 16, 12, Theme.dim());
            Nvg.text(dl, cx + cw - 18, cy + 22.5f, 10.5f, Nvg.SEMI, Nvg.RIGHT, Theme.text());
            if (!c.description.isEmpty()) Nvg.text(ago(c.created), cx + cw - 12, cy + 42, 10.5f, Nvg.REG, Nvg.RIGHT, Theme.faint());
            boolean sure = armed("cl" + c.id);
            if (busy && sure) spinner(cx + cw - 50, cy + 82, 8, 2f);
            else if (button("cl" + c.id, sure ? "Apply?" : "Load", sure ? "check" : "download", cx + cw - 96, cy + 68, 84, 28, 1, !busy)) {
                if (sure) loadCloud(c);
                else arm("cl" + c.id);
            }
            Nvg.restore();
        }
        if (more) {
            float my2 = top + rows * (chh + gap);
            if (loading) spinner(x + w / 2f, my2 + 18, 9, 2f);
            else if (button("more", "Load more", null, x + w / 2f - 70, my2 + 4, 140, 30, 0, true)) {
                page++;
                refresh(false);
            }
        } else if (loading) spinner(x + w - 14, y - 34, 7, 2f);
        if (!visible) pressed = pressedBefore;
        Nvg.restore();
        scrollbar(x + w + 6, ly, lh, content, cloudScroll);
    }

    /* ------------------------------------------------------------------ icons */

    /** Icons used only here (24 unit grid like {@link Icons}). */
    public static void drawIcon(String id, float x, float y, float size, int c) {
        float sw = 1.9f;
        switch (id) {
            case "folder" -> {
                Nvg.save();
                Nvg.translate(x, y);
                Nvg.scale(size / 24f, size / 24f);
                Nvg.beginPath();
                Nvg.moveTo(3, 7); Nvg.lineTo(3, 18); Nvg.quadTo(3, 20, 5, 20); Nvg.lineTo(19, 20); Nvg.quadTo(21, 20, 21, 18);
                Nvg.lineTo(21, 9.5f); Nvg.quadTo(21, 7.5f, 19, 7.5f); Nvg.lineTo(11.5f, 7.5f); Nvg.lineTo(9.5f, 5); Nvg.lineTo(5, 5); Nvg.quadTo(3, 5, 3, 7);
                Nvg.closePath();
                Nvg.strokePath(sw, c);
                Nvg.line(3.5f, 11, 20.5f, 11, sw * 0.8f, Nvg.alpha(c, 0.6f));
                Nvg.restore();
            }
            case "cloud" -> {
                Nvg.save();
                Nvg.translate(x, y);
                Nvg.scale(size / 24f, size / 24f);
                Nvg.beginPath();
                Nvg.moveTo(7, 18.5f);
                Nvg.bezierTo(3.6f, 18.5f, 2.4f, 14.2f, 5.2f, 12.4f);
                Nvg.bezierTo(5f, 8.4f, 9.4f, 6.2f, 12.4f, 8.4f);
                Nvg.bezierTo(14f, 4.6f, 20f, 5.6f, 19.6f, 10.6f);
                Nvg.bezierTo(22.8f, 11.4f, 22.4f, 18.5f, 18.2f, 18.5f);
                Nvg.closePath();
                Nvg.strokePath(sw, c);
                Nvg.restore();
            }
            case "upload", "download" -> {
                Nvg.save();
                Nvg.translate(x, y);
                Nvg.scale(size / 24f, size / 24f);
                boolean up = id.equals("upload");
                Nvg.line(12, 4, 12, 15.5f, sw + 0.2f, c);
                Nvg.beginPath();
                if (up) {
                    Nvg.moveTo(7, 8.6f); Nvg.lineTo(12, 3.8f); Nvg.lineTo(17, 8.6f);
                } else {
                    Nvg.moveTo(7, 10.8f); Nvg.lineTo(12, 15.6f); Nvg.lineTo(17, 10.8f);
                }
                Nvg.strokePath(sw + 0.2f, c);
                Nvg.beginPath();
                Nvg.moveTo(4.5f, 15); Nvg.lineTo(4.5f, 19); Nvg.lineTo(19.5f, 19); Nvg.lineTo(19.5f, 15);
                Nvg.strokePath(sw + 0.2f, c);
                Nvg.restore();
            }
            default -> Icons.draw(id, x, y, size, c);
        }
    }
}

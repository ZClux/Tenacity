package tenacity.ui;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import tenacity.core.Category;
import tenacity.core.Chat;
import tenacity.core.Module;
import tenacity.core.ModuleManager;
import tenacity.core.Setting;
import tenacity.gfx.Glass;
import tenacity.gfx.Icons;
import tenacity.gfx.Nvg;
import tenacity.hud.Hud;
import tenacity.hud.HudElement;
import tenacity.modules.Interface;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The dropdown click gui: one liquid glass panel per category, modules expand into their settings with a smooth slide.
 * Immediate mode - input arrives through the static fields and is consumed while drawing.
 */
public final class ClickGui {
    private static final float PW = 208, HEADER = 46, ROW = 32, PAD = 8, BOX_PW = 440;
    /** Width of the panel being drawn (wider in the Box style). */
    private static float pw = PW;
    private static boolean boxMode;
    private static Category boxCat;

    private static final class Panel {
        final Category cat;
        float x = -1, y = -1;
        boolean open = true;
        float openAnim = 1f, scroll, scrollTarget, h, contentH;
        float openIn;

        Panel(Category c) {
            cat = c;
        }
    }

    private static final List<Panel> PANELS = new ArrayList<>();
    private static final JsonObject SAVED = new JsonObject();

    /* input, filled by TenacityScreen */
    public static float mx, my;
    public static boolean down, pressed, released, rpressed, mpressed;
    public static float scroll;
    public static final List<Integer> keys = new ArrayList<>();
    public static final StringBuilder typed = new StringBuilder();

    /* state */
    public static float openT;
    public static boolean closing;
    public static String search = "";
    private static float searchAnim;
    private static Panel dragPanel;
    private static float dragX, dragY;
    private static boolean dragMoved;
    private static Panel hoverPanel;
    private static Setting.Num dragNum;
    private static Setting.Color dragColor;
    private static boolean dragHue;
    private static float dragNumX, dragNumW;
    private static Setting.Key listening;
    private static Setting.Text editing;
    private static HudElement hudTarget;
    private static String tooltip;
    private static Setting.Mode ddHover, ddHoverPrev;
    private static float tooltipAnim;
    private static float guiScale = 1f;
    private static float lastGw, lastGh;

    private ClickGui() {
    }

    public static void init() {
        if (!PANELS.isEmpty()) return;
        for (Category c : Category.values()) PANELS.add(new Panel(c));
        for (Panel p : PANELS) {
            if (SAVED.has(p.cat.name()) && SAVED.get(p.cat.name()).isJsonObject()) {
                JsonObject o = SAVED.getAsJsonObject(p.cat.name());
                p.x = o.get("x").getAsFloat();
                p.y = o.get("y").getAsFloat();
                p.open = o.get("open").getAsBoolean();
                p.openAnim = p.open ? 1f : 0f;
            }
        }
    }

    public static void save(JsonObject out) {
        for (Panel p : PANELS) {
            if (p.x < 0) continue;
            JsonObject o = new JsonObject();
            o.addProperty("x", p.x);
            o.addProperty("y", p.y);
            o.addProperty("open", p.open);
            out.add(p.cat.name(), o);
        }
        if (PANELS.isEmpty()) for (var e : SAVED.entrySet()) out.add(e.getKey(), e.getValue());
        else {
            out.addProperty("refW", lastGw);
            out.addProperty("refH", lastGh);
        }
    }

    public static void load(JsonObject in) {
        for (var e : in.entrySet()) SAVED.add(e.getKey(), e.getValue());
        if (in.has("refW")) {
            lastGw = in.get("refW").getAsFloat();
            lastGh = in.get("refH").getAsFloat();
        }
    }

    public static void onOpen() {
        init();
        openT = 0f;
        closing = false;
        search = "";
        ConfigPanel.open = false;
        if (ChatPanel.open) ChatPanel.toggle();
        resetInput();
    }

    public static void requestClose() {
        closing = true;
        listening = null;
    }

    public static void resetInput() {
        down = pressed = released = rpressed = mpressed = false;
        scroll = 0;
        keys.clear();
        typed.setLength(0);
        dragPanel = null;
        dragNum = null;
        dragColor = null;
        hudTarget = null;
        Hud.endDrag();
        if (listening != null) listening.listening = false;
        listening = null;
        if (editing != null) editing.editing = false;
        editing = null;
    }

    public static boolean draggingSlider() {
        return dragNum != null && down;
    }

    public static float scale() {
        return guiScale;
    }

    /* ------------------------------------------------------------------ frame */

    /** Blurred backdrop - must be queued before any other glass of the frame. */
    public static void backdrop() {
        Glass.backdrop(Anim.smooth(openT), (Theme.dark() ? 0.38f : 0.45f) * Anim.smooth(openT), !Theme.dark());
    }

    private static boolean in(float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    public static void draw(float dt) {
        init();
        openT = Anim.approach(openT, closing ? 0f : 1f, 6.5f);
        if (closing && openT < 0.02f) {
            MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(null));
        }
        float W = Nvg.w, H = Nvg.h;
        if (W < 320 || H < 200) return; // window not laid out yet
        guiScale = Math.min(1f, W / (7 * PW + 6 * 14 + 48));
        guiScale = Math.max(0.55f, guiScale);
        float g = guiScale;
        Theme.gs = g;
        float gw = W / g, gh = H / g;
        boxMode = Interface.I != null && Interface.I.guiStyle.get().equals("Box");
        pw = boxMode ? BOX_PW : PW;
        float lmx = mx, lmy = my;
        mx = lmx / g;
        my = lmy / g;

        // the config panel takes all input while it is open
        boolean cfg = ConfigPanel.visible();
        boolean chat = ChatPanel.visible();
        final boolean chatOwns = ChatPanel.open, cfgOwns = ConfigPanel.open && !ChatPanel.open;
        float cmx = mx, cmy = my, cscroll = scroll;
        boolean cpressed = pressed;
        List<Integer> ckeys = new ArrayList<>(keys);
        String ctyped = typed.toString();
        // the two panels never use input at the same time: whichever is open gets it
        if (cfg) {
            if (ConfigPanel.open) {
                pressed = rpressed = mpressed = false;
                scroll = 0;
                keys.clear();
                typed.setLength(0);
            }
            mx = my = -10000;
        }
        if (chat) {
            // the chat panel takes all input while it is open
            if (ChatPanel.open) {
                pressed = rpressed = mpressed = false;
                scroll = 0;
                keys.clear();
                typed.setLength(0);
            }
            mx = my = -10000;
        }

        // keyboard
        handleKeys();

        Nvg.save();
        Nvg.scale(g, g);

        // keep the panels where they were (relative to the screen) when the ui scale or the window size changes
        if (lastGw > 0 && (Math.abs(lastGw - gw) > 0.5f || Math.abs(lastGh - gh) > 0.5f))
            for (Panel p : PANELS) if (p.x >= 0) {
                p.x = Anchor.remap(p.x, PW, lastGw, gw);
                p.y = Anchor.remap(p.y, Math.max(HEADER, p.h), lastGh, gh);
            }
        lastGw = gw;
        lastGh = gh;

        // default layout
        float total = 7 * PW + 6 * 14;
        float x0 = (gw - total) / 2f;
        for (int i = 0; i < PANELS.size(); i++) {
            Panel p = PANELS.get(i);
            if (p.x >= 0) {
                p.x = Math.max(4 - PW + 60, Math.min(gw - 60, p.x));
                p.y = Math.max(0, Math.min(gh - HEADER, p.y));
            }
            if (p.x < 0) {
                p.x = x0 + i * (PW + 14);
                p.y = Math.max(86, gh * 0.13f);
            }
        }

        if (boxMode) {
            if (boxCat == null) boxCat = PANELS.get(0).cat;
            for (Panel p : PANELS) if (p.cat == boxCat && searchHidden(p)) {
                for (Panel q : PANELS) if (!searchHidden(q)) {
                    boxCat = q.cat;
                    break;
                }
                break;
            }
        }

        drawBrand(gw);
        if (Interface.themeId() > 0) drawThemeFx(gw, gh);

        // which panel owns the pointer (topmost first)
        hoverPanel = null;
        boolean overSearch = in(gw / 2f - 360, gh - 70, 720, 42);
        for (int i = PANELS.size() - 1; !boxMode && i >= 0; i--) {
            Panel p = PANELS.get(i);
            if (hidden(p)) continue;
            if (in(p.x, p.y, pw, Math.max(HEADER, p.h))) {
                hoverPanel = p;
                break;
            }
        }
        if (boxMode) {
            boxLayout(gw, gh);
            hoverPanel = drawerAnim > 0.3f && in(bX + bW - drawerW(), bY, drawerW(), bH) ? BOXP : null;
        }

        // hud widgets can be dragged while the menu is open
        if (pressed && hoverPanel == null && !overSearch && dragPanel == null) {
            HudElement e = null;
            if (e != null) {
                hudTarget = e;
                Hud.startDrag(e, mx * g, my * g);
            }
        }
        if (hudTarget != null) {
            if (down) Hud.drag(mx * g, my * g);
            else {
                hudTarget = null;
                Hud.endDrag();
            }
        }

        tooltip = null;
        ddHoverPrev = ddHover;
        ddHover = null;
        // panel dragging
        if (dragPanel != null) {
            if (down) {
                if (Math.abs(mx - dragX) + Math.abs(my - dragY) > 3) dragMoved = true;
                if (dragMoved) {
                    dragPanel.x += mx - dragX;
                    dragPanel.y += my - dragY;
                    dragX = mx;
                    dragY = my;
                }
            } else {
                if (!dragMoved) dragPanel.open = !dragPanel.open;
                dragPanel = null;
            }
        }

        Panel front = null;
        if (boxMode) {
            drawBoxGui(gw, gh, Anim.easeOut(Math.max(0f, Math.min(1f, openT / 0.65f))));
            Nvg.layer();
            Nvg.save();
            Nvg.scale(g, g);
        }
        for (int i = 0; !boxMode && i < PANELS.size(); i++) {
            Panel p = PANELS.get(i);
            if (hidden(p)) continue;
            float e = Anim.easeOut(Math.max(0f, Math.min(1f, (openT - i * 0.07f) / 0.65f)));
            drawPanel(p, i, e, gh, dt);
            Nvg.layer();
            Nvg.save();
            Nvg.scale(g, g);
            if (pressed && hoverPanel == p) front = p;
        }
        if (front != null) {
            PANELS.remove(front);
            PANELS.add(front);
        }

        drawSearch(gw, gh, overSearch);
        drawTooltip(gw, gh, dt);
        if (cfg || ConfigPanel.open) {
            Nvg.restore();
            ConfigPanel.draw(gw, gh, cmx, cmy, cpressed && !chatOwns, cscroll, chatOwns ? List.of() : ckeys, chatOwns ? "" : ctyped, guiScale);
            Nvg.save();
            Nvg.scale(g, g);
        }
        if (chat || ChatPanel.open) {
            Nvg.restore();
            ChatPanel.draw(gw, gh, cmx, cmy, cpressed && !cfgOwns, cscroll, cfgOwns ? List.of() : ckeys, cfgOwns ? "" : ctyped, guiScale);
            Nvg.save();
            Nvg.scale(g, g);
        }

        Nvg.restore();
        Theme.gs = 1f;
        if (ConfigPanel.visible() || ChatPanel.visible() || !Chat.MENTIONS.isEmpty()) {
            Nvg.layer();
            Toasts.render(dt);
        }
        mx = lmx;
        my = lmy;

        if (released) {
            dragNum = null;
            dragColor = null;
        }
        pressed = released = rpressed = mpressed = false;
        scroll = 0;
        keys.clear();
        typed.setLength(0);
    }

    private static boolean hidden(Panel p) {
        return searchHidden(p) || (boxMode && p.cat != boxCat);
    }

    private static boolean searchHidden(Panel p) {
        if (search.isEmpty()) return false;
        for (Module m : ModuleManager.of(p.cat)) if (matches(m)) return false;
        return true;
    }

    private static boolean matches(Module m) {
        if (search.isEmpty()) return true;
        // ignore spaces so "freecam" finds "Freecam" and "storageesp" finds "Storage ESP"
        return m.name.toLowerCase(Locale.ROOT).replace(" ", "").contains(search.toLowerCase(Locale.ROOT).replace(" ", ""));
    }

    private static void handleKeys() {
        if (editing != null) {
            for (int k : keys) {
                if (k == GLFW.GLFW_KEY_ESCAPE || k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
                    editing.editing = false;
                    editing = null;
                    break;
                } else if (k == GLFW.GLFW_KEY_BACKSPACE && !editing.value.isEmpty()) editing.set(editing.value.substring(0, editing.value.length() - 1));
            }
            if (editing != null && typed.length() > 0) editing.set(editing.value + typed);
            keys.clear();
            typed.setLength(0);
            return;
        }
        for (int k : keys) {
            if (listening != null) {
                if (k == GLFW.GLFW_KEY_ESCAPE) listening.code = listening.code;
                else if (k == GLFW.GLFW_KEY_BACKSPACE || k == GLFW.GLFW_KEY_DELETE) listening.code = -1;
                else listening.code = k;
                listening.listening = false;
                listening = null;
                continue;
            }
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                if (!search.isEmpty()) search = "";
                else requestClose();
            } else if (k == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) search = search.substring(0, search.length() - 1);
            else if (k == tenacity.modules.Interface.I.menuKey.code) requestClose();
        }
        if (listening == null && typed.length() > 0) search += typed;
        if (search.length() > 28) search = search.substring(0, 28);
    }

    /* ------------------------------------------------------------------ brand + search */

    /** Per-panel decoration of the active theme. */
    private static void drawThemePanel(float x, float y, int index) {
        int id = Interface.themeId();
        long tm = System.nanoTime() / 1_000_000L;
        switch (id) {
            case 1 -> drawGarland(x, y, index, true, new int[]{0xFFFF4040, 0xFF4BE880, 0xFFFFE27A}, 0xFF1F7A3A, 0xFF6FD08A);
            case 2 -> {
                drawGarland(x, y, index, false, new int[]{0xFFFF8A1E, 0xFFB57BFF, 0xFF7CFF5A}, 0xFF2A1840, 0xFF7A5AB0);
                // cobweb in the top-left corner
                int wc = Nvg.alpha(0xFFFFFFFF, 0.30f);
                float ox = x + 14, oy = y + 14;
                for (int k = 0; k < 5; k++) {
                    double a = k * Math.PI / 8;
                    Nvg.line(ox, oy, ox + (float) Math.cos(a) * 34, oy + (float) Math.sin(a) * 34, 0.8f, wc);
                }
                for (int r = 1; r <= 3; r++) {
                    float rr = r * 10f;
                    for (int k = 0; k < 4; k++) {
                        double a0 = k * Math.PI / 8, a1 = (k + 1) * Math.PI / 8, am = (a0 + a1) / 2;
                        Nvg.beginPath();
                        Nvg.moveTo(ox + (float) Math.cos(a0) * rr, oy + (float) Math.sin(a0) * rr);
                        Nvg.quadTo(ox + (float) Math.cos(am) * rr * 0.86f, oy + (float) Math.sin(am) * rr * 0.86f,
                                ox + (float) Math.cos(a1) * rr, oy + (float) Math.sin(a1) * rr);
                        Nvg.strokePath(0.8f, wc);
                    }
                }
            }
            default -> { }
        }
    }

    /** String lights: a sagging wire with proper capped bulbs that glow and chase. */
    private static void drawGarland(float x, float y, int index, boolean snow, int[] cols, int rope, int ropeHi) {
        long tm = System.nanoTime() / 1_000_000L;
        float x0 = x + 30, x1 = x + pw - 30, top = y + 3f, sag = 7f;
        int swags = Math.max(1, Math.round((x1 - x0) / 120f));
        float sw = (x1 - x0) / swags;
        for (int i = 0; i < swags; i++) {
            float ax = x0 + i * sw, bx = ax + sw, mx = (ax + bx) / 2f;
            Nvg.beginPath();
            Nvg.moveTo(ax, top);
            Nvg.quadTo(mx, top + sag * 2f, bx, top);
            Nvg.strokePath(1.6f, 0xFF16201A);
            Nvg.circle(ax, top, 2.2f, 0xFF2A2A2A);
            int bulbs = 4;
            for (int k = 1; k <= bulbs; k++) {
                float t = k / (bulbs + 1f), u = 1f - t;
                float px = u * u * ax + 2 * u * t * mx + t * t * bx;
                float py = u * u * top + 2 * u * t * (top + sag * 2f) + t * t * top;
                float chase = 0.5f + 0.5f * (float) Math.sin(tm / 260.0 - (i * bulbs + k) * 0.9 + index);
                int bc = cols[(i * bulbs + k) % cols.length];
                float len = 10f;
                Nvg.rect(px - 1.3f, py, 2.6f, 2.6f, 0xFF3A3F3A);
                Nvg.glowDisc(px, py + 2.6f + len / 2f, 15f, Nvg.alpha(bc, 0.38f * chase), Nvg.alpha(bc, 0f));
                Nvg.rrect(px - 3.4f, py + 2.6f, 6.8f, len, 3.4f, Nvg.mix(Nvg.alpha(bc, 0.45f), bc, chase));
                Nvg.rrect(px - 2f, py + 4f, 1.6f, 4f, 0.8f, Nvg.alpha(0xFFFFFFFF, 0.55f + 0.3f * chase));
            }
        }
    }

    private static void drawThemeFx(float gw, float gh) {
        int id = Interface.themeId();
        if (id == 1) {
            drawSnow(gw, gh);
            return;
        }
        double t = System.nanoTime() / 1e9;
        float oa = Math.min(1f, openT);
        Nvg.glowDisc(gw * 0.82f, 110, 150, Nvg.alpha(0xFFFFE9B0, 0.20f * oa), Nvg.alpha(0xFFFFE9B0, 0f));
        Nvg.circle(gw * 0.82f, 110, 30, Nvg.alpha(0xFFFFF1C8, 0.55f * oa));
        for (int i = 0; i < 5; i++) {
            float fx = (float) ((t * (10 + i * 4) + i * 400) % (gw + 500)) - 250;
            float fy = gh - 60 - (i % 3) * 40 + (float) Math.sin(t * 0.5 + i) * 14;
            Nvg.glowDisc(fx, fy, 190, Nvg.alpha(i % 2 == 0 ? 0xFF7A3BC0 : 0xFFFF7A1E, 0.12f * oa), Nvg.alpha(0xFF7A3BC0, 0f));
        }
        for (int i = 0; i < 55; i++) {
            float seed = i * 9.173f + 62;
            float fx = (float) (Math.abs(Math.sin(seed) * 43758.5453) % 1.0);
            float fs = (float) (Math.abs(Math.sin(seed * 1.7f + 3) * 24634.6345) % 1.0);
            float fc = (float) (Math.abs(Math.sin(seed * 2.9f + 1) * 9183.3412) % 1.0);
            float prog = (float) ((t * (16 + fs * 34) + fs * 1000) % (gh + 60)) - 30;
            float y = gh - prog, x = fx * gw + (float) Math.sin(t * (0.5 + fs * 0.7) + seed) * 12, sz = 2f + fs * 4.5f;
            float fl = 0.6f + 0.4f * (float) Math.sin(t * 5 + seed);
            Nvg.circle(x, y, sz * 2.6f, Nvg.alpha(0xFFFF8A1E, 0.07f * oa * fl));
            Nvg.circle(x, y, sz * 0.7f, Nvg.alpha(fc < 0.8f ? 0xFFFFA03C : 0xFFFFE08A, (0.45f + 0.5f * fs) * oa * fl));
        }
        {
            for (int i = 0; i < 4; i++) {
                float bx = (float) ((t * (46 + i * 9) + i * 520) % (gw + 240)) - 120;
                float by = 80 + i * 78 + (float) Math.sin(t * 1.3 + i * 2) * 26;
                float f = (float) Math.sin(t * 11 + i * 3);
                int bc = Nvg.alpha(0xFF0E0618, 0.92f * oa);
                for (int side = -1; side <= 1; side += 2) {
                    Nvg.beginPath();
                    Nvg.moveTo(bx, by);
                    Nvg.quadTo(bx + side * 12, by - 14 - 8 * f, bx + side * 27, by - 4 + 6 * f);
                    Nvg.quadTo(bx + side * 20, by + 1, bx + side * 15, by + 3);
                    Nvg.quadTo(bx + side * 9, by + 5, bx + side * 4, by + 2);
                    Nvg.closePath();
                    Nvg.fillPath(bc);
                }
                Nvg.circle(bx, by + 1, 4.2f, bc);
                Nvg.circle(bx - 1.6f, by, 0.9f, Nvg.alpha(0xFFFF8A1E, oa));
                Nvg.circle(bx + 1.6f, by, 0.9f, Nvg.alpha(0xFFFF8A1E, oa));
            }
        }
    }

    private static void drawXmasScene(float gw, float gh, double t, float oa) {
        Nvg.glowDisc(gw * (0.25f + 0.08f * (float) Math.sin(t * 0.25)), 40, 360, Nvg.alpha(0xFF3BE07A, 0.13f * oa), Nvg.alpha(0xFF3BE07A, 0f));
        Nvg.glowDisc(gw * (0.75f + 0.08f * (float) Math.cos(t * 0.2)), 30, 380, Nvg.alpha(0xFFFF4A4A, 0.11f * oa), Nvg.alpha(0xFFFF4A4A, 0f));
        for (int i = 0; i < 40; i++) {
            float sx = (float) (Math.abs(Math.sin(i * 91.7) * 43758.5453) % 1.0) * gw;
            float sy = (float) (Math.abs(Math.sin(i * 51.3 + 2) * 24634.6345) % 1.0) * gh * 0.45f;
            float tw = 0.4f + 0.6f * (float) Math.abs(Math.sin(t * (0.8 + i % 5 * 0.3) + i));
            Nvg.circle(sx, sy, 1.2f, Nvg.alpha(0xFFFFFFFF, 0.5f * tw * oa));
        }
        float[] tx = {0.04f, 0.13f, 0.27f, 0.74f, 0.88f, 0.97f};
        float[] th = {210, 150, 120, 130, 190, 140};
        for (int n = 0; n < tx.length; n++) {
            float cx = tx[n] * gw, h = th[n], base = gh + 4, w = h * 0.34f;
            Nvg.rect(cx - 4, base - 18, 8, 18, Nvg.alpha(0xFF3B2412, 0.9f * oa));
            for (int k = 0; k < 4; k++) {
                float yt = base - h + k * h * 0.22f;
                float hw = w * (0.38f + k * 0.2f), tierB = base - 14 - (3 - k) * h * 0.17f;
                Nvg.beginPath();
                Nvg.moveTo(cx, yt);
                Nvg.lineTo(cx + hw, tierB);
                Nvg.lineTo(cx - hw, tierB);
                Nvg.closePath();
                Nvg.fillPathGradient(cx, yt, cx, tierB, Nvg.alpha(0xFF1E6B3C, 0.92f * oa), Nvg.alpha(0xFF0B3A20, 0.92f * oa));
                Nvg.line(cx - hw * 0.18f, yt + (tierB - yt) * 0.18f, cx - hw * 0.92f, tierB - 1, 2.2f, Nvg.alpha(0xFFFFFFFF, 0.55f * oa));
                for (int j = 0; j < 3 + k; j++) {
                    float u = (j + 0.5f) / (3 + k);
                    float lx = cx - hw * 0.8f + u * hw * 1.6f, ly = tierB - 4 - (float) Math.abs(Math.sin(j * 2.1 + k)) * (tierB - yt) * 0.12f;
                    float tw = 0.5f + 0.5f * (float) Math.sin(t * 2.4 + j * 1.9 + k * 2.7 + n * 3.1);
                    int bc = (j + k + n) % 3 == 0 ? 0xFFFF4040 : (j + k + n) % 3 == 1 ? 0xFFFFE27A : 0xFF5BE8FF;
                    Nvg.circle(lx, ly, 7f, Nvg.alpha(bc, 0.16f * tw * oa));
                    Nvg.circle(lx, ly, 2.2f, Nvg.alpha(bc, (0.5f + 0.5f * tw) * oa));
                }
            }
            float st = 0.6f + 0.4f * (float) Math.sin(t * 3 + n);
            Nvg.glowDisc(cx, base - h, 26, Nvg.alpha(0xFFFFE27A, 0.5f * st * oa), Nvg.alpha(0xFFFFE27A, 0f));
            Nvg.circle(cx, base - h, 3.5f, Nvg.alpha(0xFFFFF4B0, oa));
        }
        Nvg.beginPath();
        Nvg.moveTo(0, gh);
        for (float x = 0; x <= gw + 8; x += 8) Nvg.lineTo(x, gh - 22 + (float) Math.sin(x * 0.006 + 1) * 9 + (float) Math.sin(x * 0.02) * 3);
        Nvg.lineTo(gw + 8, gh);
        Nvg.closePath();
        Nvg.fillPathGradient(0, gh - 36, 0, gh, Nvg.alpha(0xFFFFFFFF, 0.95f * oa), Nvg.alpha(0xFFBFD8F0, 0.95f * oa));
    }

    /** Icicles hanging from the bottom edge of the Box panel. */
    private static void drawIcicles(float x, float y, float w) {
        for (float sx = 36; sx < w - 36; sx += 17) {
            float hs = (float) (Math.abs(Math.sin(sx * 7.31) * 43758.5453) % 1.0);
            float len = 8 + hs * 20, hw = 3.2f + hs * 2.2f;
            Nvg.beginPath();
            Nvg.moveTo(x + sx - hw, y - 1);
            Nvg.lineTo(x + sx + hw, y - 1);
            Nvg.lineTo(x + sx, y + len);
            Nvg.closePath();
            Nvg.fillPathGradient(x + sx, y, x + sx, y + len, Nvg.alpha(0xFFFFFFFF, 0.85f), Nvg.alpha(0xFF9FD8FF, 0.35f));
        }
    }

    private static void drawSnow(float gw, float gh) {
        double t = System.nanoTime() / 1e9;
        float oa = Math.min(1f, openT);
        drawXmasScene(gw, gh, t, oa);
        // frosty ground glow
        Nvg.rrectGradient(0, gh - 150, gw, 150, 0, 0, gh - 150, 0, gh, Nvg.alpha(0xFFFFFFFF, 0f), Nvg.alpha(0xFFDDEEFF, 0.16f * oa));
        for (int i = 0; i < 70; i++) {
            float seed = i * 12.9898f;
            float fx = (float) (Math.abs(Math.sin(seed) * 43758.5453) % 1.0);
            float fs = (float) (Math.abs(Math.sin(seed * 1.7f + 3) * 24634.6345) % 1.0);
            float sz = 3f + fs * 4.5f;
            float speed = 14 + fs * 26;
            float y = (float) ((t * speed + fs * 1000) % (gh + 30)) - 15;
            float x = fx * gw + (float) Math.sin(t * (0.5 + fs * 0.5) + seed) * 18;
            float rot = (float) (t * (0.3 + fs) + seed);
            int c = Nvg.alpha(0xFFFFFFFF, (0.35f + fs * 0.5f) * oa);
            for (int a = 0; a < 3; a++) {
                float an = rot + a * (float) Math.PI / 3f;
                float dx = (float) Math.cos(an) * sz, dy = (float) Math.sin(an) * sz;
                Nvg.line(x - dx, y - dy, x + dx, y + dy, 1f, c);
            }
            Nvg.circle(x, y, 1.1f, c);
        }
        for (int i = 0; i < 110; i++) {
            float seed = i * 7.31f + 100;
            float fx = (float) (Math.abs(Math.sin(seed) * 43758.5453) % 1.0);
            float fs = (float) (Math.abs(Math.sin(seed * 1.3f + 3) * 24634.6345) % 1.0);
            float sz = 1.1f + fs * 2.3f;
            float y = (float) ((t * (24 + fs * 46) + fs * 900) % (gh + 20)) - 10;
            float x = fx * gw + (float) Math.sin(t * (0.6 + fs) + seed) * 14;
            Nvg.circle(x, y, sz * 2.2f, Nvg.alpha(0xFFFFFFFF, 0.05f * oa));
            Nvg.circle(x, y, sz, Nvg.alpha(0xFFFFFFFF, (0.3f + fs * 0.55f) * oa));
        }
    }

    /* ---------------- Box style ---------------- */
    private static final Panel BOXP = new Panel(Category.COMBAT);
    private static Module boxModule, lastBoxModule;
    private static float drawerAnim, gridScroll, gridTarget, drawScroll, drawTarget;
    private static float bX, bY, bW, bH;
    private static final float SB_W = 178, DRAWER_W = 312;
    private static float sbSel, sbTarget = -1;
    private static final float[] sbHov = new float[16];
    private static long catStart = System.nanoTime();
    private static String lastSearch = "";

    /** Dev screenshots: open a module's settings in the Box style. */
    public static void devBox(String module) {
        for (Module m : ModuleManager.all()) if (m.name.equals(module)) {
            boxModule = m;
            boxCat = m.category;
            drawerAnim = 1f;
        }
    }

    private static void boxLayout(float gw, float gh) {
        bW = Math.min(gw - 60, 980);
        bH = Math.max(300, Math.min(gh - 190, 620));
        bX = (gw - bW) / 2f;
        bY = Math.max(64, (gh - 70 - bH) / 2f + 4);
        drawerAnim = Anim.approach(drawerAnim, boxModule != null ? 1f : 0f, 12f);
    }

    private static float drawerW() {
        return DRAWER_W * Anim.smooth(drawerAnim);
    }

    private static int selFill() {
        return Interface.themeId() > 0 ? Nvg.alpha(Theme.accent(), 0.30f) : Theme.text();
    }

    private static int selText() {
        return Interface.themeId() > 0 ? Theme.text() : Theme.onFill();
    }

    private static void drawBoxGui(float gw, float gh, float e) {
        if (boxCat == null) boxCat = Category.COMBAT;
        Theme.tintOverride = Theme.tint(0);
        Theme.rimOverride = Theme.rim(0);
        Theme.panel(bX, bY, bW, bH, 28, e, 0);
        Theme.tintOverride = 0;
        Nvg.save();
        Nvg.fade(e);
        if (Interface.themeId() > 0) {
            pw = bW;
            drawThemePanel(bX, bY, 0);
            if (Interface.christmas()) drawIcicles(bX, bY + bH, bW);
            pw = BOX_PW;
        }

        // sidebar
        Nvg.rrect(bX + 8, bY + 8, SB_W - 8, bH - 16, 22, Nvg.alpha(0xFF000000, Theme.dark() ? 0.22f : 0.06f));
        Nvg.rrectStroke(bX + 8, bY + 8, SB_W - 8, bH - 16, 22, 1f, Nvg.alpha(Theme.text(), 0.07f));
        Nvg.circle(bX + 34, bY + 40, 13, Nvg.alpha(Theme.accent(), 0.20f));
        Nvg.circleStroke(bX + 34, bY + 40, 13, 1.2f, Nvg.alpha(Theme.accent(), 0.7f));
        Nvg.text("T", bX + 34, bY + 41, 14, Nvg.SEMI, Nvg.CENTER, Theme.text());
        Nvg.text("Tenacity", bX + 56, bY + 37, 15.5f, Nvg.SEMI, Nvg.LEFT, Theme.text());
        Nvg.text("Client", bX + 56, bY + 53, 10, Nvg.MED, Nvg.LEFT, Theme.faint());
        if (!search.equals(lastSearch)) {
            lastSearch = search;
            catStart = System.nanoTime();
        }
        float catT = (System.nanoTime() - catStart) / 1e9f;
        if (sbTarget >= 0) {
            if (sbSel <= 0) sbSel = sbTarget;
            sbSel = Anim.approach(sbSel, sbTarget, 13f);
            Nvg.rrect(bX + 12, sbSel, SB_W - 24, 40, 14, selFill());
            Nvg.rrect(bX + 12, sbSel + 11, 3, 18, 1.5f, Nvg.alpha(Theme.accent(), 0.9f));
        }
        float cy = bY + 82;
        for (Category c : Category.values()) {
            List<Module> all = ModuleManager.of(c);
            int shown = 0, on = 0;
            for (Module m : all) {
                if (matches(m)) shown++;
                if (!m.locked() && m.enabled()) on++;
            }
            if (!search.isEmpty() && shown == 0) continue;
            float ix = bX + 12, iw = SB_W - 24, ih = 40;
            boolean sel = c == boxCat, hov = in(ix, cy, iw, ih);
            int oi = c.ordinal();
            sbHov[oi] = Anim.approach(sbHov[oi], hov ? 1f : 0f, 16f);
            if (sel) sbTarget = cy;
            else if (sbHov[oi] > 0.01f) Nvg.rrect(ix + sbHov[oi] * 3, cy, iw - sbHov[oi] * 3, ih, 14, Nvg.alpha(Theme.text(), 0.09f * sbHov[oi]));
            int col = sel ? selText() : Nvg.mix(Theme.dim(), Theme.text(), sbHov[oi]);
            if (!sel) ix += sbHov[oi] * 3;
            Icons.draw(c.icon, ix + 13, cy + ih / 2f - 9, 18, col);
            Nvg.text(c.label, ix + 42, cy + ih / 2f + 0.5f, 13, sel ? Nvg.SEMI : Nvg.MED, Nvg.LEFT, col);
            String cnt = String.valueOf(on);
            float cw = Math.max(20, Nvg.textWidth(cnt, 10, Nvg.SEMI) + 12);
            if (on > 0) {
                Nvg.rrect(ix + iw - cw - 10, cy + ih / 2f - 8, cw, 16, 8, sel ? Nvg.alpha(selText(), 0.18f) : Nvg.alpha(Theme.accent(), 0.22f));
                Nvg.text(cnt, ix + iw - cw / 2f - 10, cy + ih / 2f + 0.5f, 10, Nvg.SEMI, Nvg.CENTER, col);
            }
            if (hov && pressed) {
                if (boxCat != c) {
                    boxCat = c;
                    boxModule = null;
                    gridTarget = 0;
                    gridScroll = 0;
                    catStart = System.nanoTime();
                }
                pressed = false;
            }
            cy += ih + 4;
        }
        Nvg.rect(bX + SB_W, bY + 22, 1, bH - 44, Theme.line());

        // module grid
        float dw = drawerW();
        float gx = bX + SB_W + 20, gwid = bW - SB_W - 40 - dw;
        List<Module> mods = new ArrayList<>();
        for (Module m : ModuleManager.of(boxCat)) if (matches(m)) mods.add(m);
        float hp = Anim.easeOut(Math.min(1f, catT / 0.3f));
        Nvg.save();
        Nvg.fade(hp);
        Nvg.text(boxCat.label, gx + (1 - hp) * 14, bY + 40, 22, Nvg.SEMI, Nvg.LEFT, Theme.text());
        {
            int onN = 0;
            for (Module m : mods) if (!m.locked() && m.enabled()) onN++;
            String cn = mods.size() + (mods.size() == 1 ? " module" : " modules") + "  •  " + onN + " enabled";
            Nvg.text(cn, gx + (1 - hp) * 14, bY + 60, 11, Nvg.MED, Nvg.LEFT, Theme.dim());
        }
        Nvg.restore();
        float ct = bY + 80, cb = bY + bH - 16, viewH = cb - ct;
        int cols = Math.max(1, (int) ((gwid + 10) / 196));
        float cardW = (gwid - (cols - 1) * 10) / cols, cardH = 88;
        int rows = (mods.size() + cols - 1) / cols;
        float contentH = rows * (cardH + 10);
        float maxScroll = Math.max(0, contentH - viewH);
        boolean overGrid = in(gx - 4, ct, gwid + 8, viewH);
        if (overGrid && scroll != 0) {
            gridTarget = Math.max(0, Math.min(maxScroll, gridTarget - scroll * 46));
            scroll = 0;
        }
        gridTarget = Math.max(0, Math.min(maxScroll, gridTarget));
        gridScroll = Anim.approach(gridScroll, gridTarget, 16f);
        if (mods.isEmpty()) Nvg.text("No modules found", gx + gwid / 2f, ct + 60, 13, Nvg.MED, Nvg.CENTER, Theme.faint());
        Nvg.save();
        Nvg.clip(gx - 6, ct, gwid + 12, viewH);
        for (int i = 0; i < mods.size(); i++) {
            Module m = mods.get(i);
            float cp = Anim.easeOut(Math.max(0f, Math.min(1f, (catT - i * 0.035f) / 0.4f)));
            float cx = gx + (i % cols) * (cardW + 10), cyy = ct + (i / cols) * (cardH + 10) - gridScroll + (1 - cp) * 20;
            if (cyy + cardH < ct - 4 || cyy > cb + 4) continue;
            Nvg.save();
            Nvg.fade(cp);
            boolean hover = overGrid && in(cx, cyy, cardW, cardH) && dragNum == null;
            m.hoverAnim = Anim.approach(m.hoverAnim, hover ? 1f : 0f, 16f);
            m.enabledAnim = Anim.approach(m.enabledAnim, m.locked() ? 0f : m.enabled() ? 1f : 0f, 14f);
            m.pressAnim = Anim.approach(m.pressAnim, 0f, 9f);
            float ea = m.enabledAnim, sh = m.pressAnim * 2.5f;
            float hv = m.hoverAnim;
            float rx = cx + sh - hv * 1.5f, ry = cyy + sh - hv * 2f, rw = cardW - sh * 2 + hv * 3f, rh = cardH - sh * 2 + hv * 3f;
            if (hv > 0.01f) Nvg.rrect(rx - 3, ry + 3, rw + 6, rh + 4, 17, Nvg.alpha(0xFF000000, 0.16f * hv));
            Nvg.rrect(rx, ry, rw, rh, 16, Nvg.alpha(Theme.text(), 0.085f + 0.07f * hv));
            Nvg.rrectStroke(rx, ry, rw, rh, 16, 1f, Nvg.alpha(Theme.text(), 0.08f + 0.10f * hv));
            if (hv > 0.01f) Nvg.rrectGradient(rx, ry, rw, rh, 14, rx, ry, rx, ry + rh, Nvg.alpha(Theme.text(), 0.11f * hv), Nvg.alpha(Theme.text(), 0f));
            if (ea > 0.01f) {
                Nvg.rrectGradient(rx, ry, rw, rh, 16, rx, ry, rx + rw, ry + rh, Nvg.alpha(Theme.accent(), 0.34f * ea), Nvg.alpha(Theme.accent(), 0.08f * ea));
                Nvg.rrectStroke(rx, ry, rw, rh, 14, 1.2f, Nvg.alpha(Theme.accent(), 0.55f * ea));
                float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 4.5e8 + i);
                Nvg.rrectStroke(rx - 1.5f, ry - 1.5f, rw + 3, rh + 3, 15.5f, 3f, Nvg.alpha(Theme.accent(), 0.10f * ea * pulse));
                Nvg.lineGradient(rx + 16, ry + 1.2f, rx + rw - 16, ry + 1.2f, 1.6f, Nvg.alpha(Theme.accent(), 0f), Nvg.alpha(Theme.accent(), 0.8f * ea));
            }
            if (boxModule == m) Nvg.rrectStroke(rx - 1, ry - 1, rw + 2, rh + 2, 15, 1.5f, Nvg.alpha(Theme.text(), 0.6f));
            int tc = Nvg.mix(Theme.dim(), Theme.text(), Math.max(ea, m.hoverAnim * 0.8f));
            Nvg.rrect(rx + 14, ry + 14, 38, 38, 12, Nvg.mix(Nvg.alpha(Theme.text(), 0.10f), Nvg.alpha(Theme.accent(), 0.40f), ea));
            Icons.draw(boxCat.icon, rx + 14 + 9, ry + 14 + 9, 20, Nvg.mix(Theme.dim(), Theme.text(), Math.max(ea, hv)));
            Nvg.text(Nvg.fit(m.name, 14f, Nvg.SEMI, rw - 62 - 56), rx + 62, ry + 27, 14f, Nvg.SEMI, Nvg.LEFT, tc);
            Nvg.text(Nvg.fit(m.description, 10.5f, Nvg.MED, rw - 76), rx + 62, ry + 46, 10.5f, Nvg.MED, Nvg.LEFT, Theme.dim());
            if (!m.locked()) {
                float tw = 28, th = 15, tx = rx + rw - tw - 12, ty = ry + 14.5f - th / 2f + 0.5f;
                Nvg.rrect(tx, ty, tw, th, th / 2, Nvg.mix(Nvg.alpha(Theme.text(), 0.16f), Theme.accent(), ea));
                Nvg.circle(tx + 7.5f + (tw - 15) * ea, ty + th / 2f, 5.2f, Nvg.mix(Theme.text(), Theme.dark() ? 0xFF0B0B0D : 0xFFFFFFFF, ea));
            }
            boolean gearHov = hover && in(rx + rw - 34, ry + rh - 33, 28, 26);
            if (gearHov) Nvg.rrect(rx + rw - 34, ry + rh - 33, 28, 24, 8, Nvg.alpha(Theme.text(), 0.12f));
            Icons.draw("gear", rx + rw - 27, ry + rh - 30, 14, Nvg.alpha(Theme.text(), gearHov || boxModule == m ? 0.95f : 0.45f));
            if (m.bind.code != -1 && !m.locked()) {
                String k = m.bind.display();
                float kw = Nvg.textWidth(k, 9, Nvg.SEMI) + 10;
                Nvg.rrect(rx + 62, ry + rh - 26, kw, 14, 5, Nvg.alpha(tc, 0.14f));
                Nvg.text(k, rx + 62 + kw / 2, ry + rh - 18.5f, 9, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(tc, 0.85f));
            }
            if (hover && pressed) {
                m.pressAnim = 1f;
                if (gearHov || m.locked()) boxModule = boxModule == m ? null : m;
                else m.toggle();
                pressed = false;
            }
            if (hover && rpressed) {
                boxModule = boxModule == m ? null : m;
                rpressed = false;
            }
            Nvg.restore();
        }
        Nvg.restore();
        if (maxScroll > 0) {
            float th = Math.max(20, viewH * viewH / contentH);
            Nvg.rrect(gx + gwid + 4, ct + (viewH - th) * (gridScroll / maxScroll), 2.5f, th, 1.2f, Nvg.alpha(Theme.text(), 0.25f));
        }

        // settings drawer
        if (boxModule != lastBoxModule) {
            drawTarget = 0;
            drawScroll = 0;
            lastBoxModule = boxModule;
        }
        Module dm = boxModule != null ? boxModule : lastBoxModule;
        if (dw > 1f && dm != null) {
            float dx = bX + bW - dw;
            Nvg.save();
            Nvg.clip(dx, bY, dw + 2, bH);
            Nvg.fade(Math.min(1f, drawerAnim * 1.3f));
            Nvg.rect(dx, bY + 22, 1, bH - 44, Theme.line());
            float ix = dx + 18;
            Nvg.text(Nvg.fit(dm.name, 16, Nvg.SEMI, DRAWER_W - 120), ix, bY + 38, 16, Nvg.SEMI, Nvg.LEFT, Theme.text());
            Nvg.text(Nvg.fit(dm.description, 10.5f, Nvg.MED, DRAWER_W - 40), ix, bY + 56, 10.5f, Nvg.MED, Nvg.LEFT, Theme.faint());
            float xx = dx + DRAWER_W - 40, xy = bY + 24;
            boolean xh = in(xx, xy, 22, 22);
            if (xh) Nvg.rrect(xx, xy, 22, 22, 8, Nvg.alpha(Theme.text(), 0.12f));
            int xc = Nvg.alpha(Theme.text(), xh ? 0.95f : 0.55f);
            Nvg.line(xx + 7, xy + 7, xx + 15, xy + 15, 1.5f, xc);
            Nvg.line(xx + 15, xy + 7, xx + 7, xy + 15, 1.5f, xc);
            if (xh && pressed) {
                boxModule = null;
                pressed = false;
            }
            if (!dm.locked()) {
                float ea = dm.enabledAnim, tw = 30, th = 16, tx = xx - tw - 10, ty = bY + 27;
                boolean th_ = in(tx - 4, ty - 4, tw + 8, th + 8);
                Nvg.rrect(tx, ty, tw, th, th / 2, Nvg.mix(Nvg.alpha(Theme.text(), 0.16f), Theme.accent(), ea));
                Nvg.circle(tx + 8 + (tw - 16) * ea, ty + th / 2f, 5.6f, Nvg.mix(Theme.text(), Theme.dark() ? 0xFF0B0B0D : 0xFFFFFFFF, ea));
                if (th_ && pressed) {
                    dm.toggle();
                    pressed = false;
                }
            }
            float st = bY + 78, sb = bY + bH - 16, vh = sb - st;
            float dMax = Math.max(0, settingsHeight(dm) + 4 - vh);
            if (hoverPanel == BOXP && scroll != 0 && !(ddHoverPrev != null && ddHoverPrev.modes.length > DD_ROWS)) {
                drawTarget = Math.max(0, Math.min(dMax, drawTarget - scroll * 34));
                scroll = 0;
            }
            drawTarget = Math.max(0, Math.min(dMax, drawTarget));
            drawScroll = Anim.approach(drawScroll, drawTarget, 16f);
            Nvg.save();
            Nvg.clip(dx + 4, st, DRAWER_W - 8, vh);
            float yy = st + 2 - drawScroll;
            for (Setting s : dm.settings) {
                if (!s.visible()) continue;
                yy += drawSetting(BOXP, s, dx + 14, yy, DRAWER_W - 28, st, vh);
            }
            drawBind(BOXP, dm, dx + 14, yy, DRAWER_W - 28, st, vh);
            Nvg.restore();
            if (dMax > 0) {
                float th2 = Math.max(20, vh * vh / (dMax + vh));
                Nvg.rrect(dx + DRAWER_W - 8, st + (vh - th2) * (drawScroll / dMax), 2.5f, th2, 1.2f, Nvg.alpha(Theme.text(), 0.25f));
            }
            Nvg.restore();
        }
        Nvg.restore();
    }

    private static void drawBrand(float gw) {
        float e = Anim.easeOut(openT);
        Nvg.save();
        Nvg.fade(e);
        float y = Math.max(40, 52) - (1 - e) * 14;
        Nvg.text("Tenacity", gw / 2f, y, 30, Nvg.SEMI, Nvg.CENTER, Theme.text());
        Nvg.restore();
    }

    private static void drawSearch(float gw, float gh, boolean hover) {
        float e = Anim.easeOut(Math.max(0f, (openT - 0.2f) / 0.8f));
        searchAnim = Anim.approach(searchAnim, search.isEmpty() ? 0f : 1f, 12f);
        float w = 300 + searchAnim * 60, h = 40;
        float cw = 112, gap = 10;
        float x = gw / 2f - (w + gap + cw) / 2f + (cw + gap) / 2f, y = gh - 66 + (1 - e) * 24;
        Nvg.layer();
        Nvg.save();
        Nvg.scale(guiScale, guiScale);
        Theme.panel(x, y, w, h, 20, e, 20);
        Nvg.save();
        Nvg.fade(e);
        Icons.draw("search", x + 16, y + 11, 18, search.isEmpty() ? Theme.dim() : Theme.text());
        if (search.isEmpty()) {
            Nvg.text("Type to search modules", x + 44, y + 20.5f, 13, Nvg.REG, Nvg.LEFT, Theme.faint());
        } else {
            Nvg.text(search, x + 44, y + 20.5f, 13.5f, Nvg.MED, Nvg.LEFT, Theme.text());
            float tw = Nvg.textWidth(search, 13.5f, Nvg.MED);
            float blink = (System.currentTimeMillis() / 500 % 2 == 0) ? 1f : 0.2f;
            Nvg.rrect(x + 46 + tw, y + 12, 1.6f, 16, 0.8f, Nvg.alpha(Theme.text(), blink));
        }
        Nvg.restore();
        Nvg.restore();

        // configs button, right next to the search bar
        float bx = x + w + gap;
        boolean hov = in(bx, y, cw, h);
        configHover = Anim.approach(configHover, hov || ConfigPanel.open ? 1f : 0f, 14f);
        Nvg.layer();
        Nvg.save();
        Nvg.scale(guiScale, guiScale);
        float grow = configHover * 2f;
        Theme.panel(bx - grow, y - grow / 2f, cw + grow * 2, h + grow, 20, e, 20);
        Nvg.save();
        Nvg.fade(e);
        if (configHover > 0.01f) Nvg.rrect(bx - grow, y - grow / 2f, cw + grow * 2, h + grow, 20, Nvg.alpha(Theme.text(), 0.08f * configHover));
        ConfigPanel.drawIcon("folder", bx + 16, y + 11, 18, Nvg.mix(Theme.dim(), Theme.text(), configHover));
        Nvg.text("Configs", bx + 42, y + 20.5f, 13, Nvg.SEMI, Nvg.LEFT, Nvg.mix(Theme.dim(), Theme.text(), configHover));
        Nvg.restore();
        Nvg.restore();
        if (hov && tooltip == null) tooltip = "Save, load and share configs";
        if (hov && pressed && !ConfigPanel.open) {
            pressed = false;
            ConfigPanel.toggle();
        }

        // chat button, on the left of the search bar
        float kx = x - gap - cw;
        boolean khov = in(kx, y, cw, h);
        chatHover = Anim.approach(chatHover, khov || ChatPanel.open ? 1f : 0f, 14f);
        Nvg.layer();
        Nvg.save();
        Nvg.scale(guiScale, guiScale);
        float kg = chatHover * 2f;
        Theme.panel(kx - kg, y - kg / 2f, cw + kg * 2, h + kg, 20, e, 20);
        Nvg.save();
        Nvg.fade(e);
        if (chatHover > 0.01f) Nvg.rrect(kx - kg, y - kg / 2f, cw + kg * 2, h + kg, 20, Nvg.alpha(Theme.text(), 0.08f * chatHover));
        int kc = Nvg.mix(Theme.dim(), Theme.text(), chatHover);
        ChatPanel.drawBubble(kx + 16, y + 11, 18, kc);
        Nvg.text("Chat", kx + 42, y + 20.5f, 13, Nvg.SEMI, Nvg.LEFT, kc);
        int ur = Chat.unread;
        unreadAnim = Anim.approach(unreadAnim, ur > 0 && !ChatPanel.open ? 1f : 0f, 12f);
        if (unreadAnim > 0.01f) {
            String ut = ur > 9 ? "9+" : String.valueOf(ur);
            float bw = Math.max(16, Nvg.textWidth(ut, 9.5f, Nvg.SEMI) + 9), bxx = kx + cw - bw - 8, byy = y + h / 2f - 8;
            float sc = Anim.easeOutBack(unreadAnim);
            Nvg.save();
            Nvg.translate(bxx + bw / 2f, byy + 8);
            Nvg.scale(sc, sc);
            Nvg.rrect(-bw / 2f, -8, bw, 16, 8, 0xFFEF4444);
            Nvg.text(ut, 0, 0.5f, 9.5f, Nvg.SEMI, Nvg.CENTER, 0xFFFFFFFF);
            Nvg.restore();
        }
        Nvg.restore();
        Nvg.restore();
        if (khov && tooltip == null) tooltip = "Global chat with other Tenacity users";
        if (khov && pressed && !ChatPanel.open) {
            pressed = false;
            ChatPanel.toggle();
        }
    }

    private static float chatHover, unreadAnim;

    private static float configHover;

    private static void drawTooltip(float gw, float gh, float dt) {
        tooltipAnim = Anim.approach(tooltipAnim, tooltip == null ? 0f : 1f, 14f);
        if (tooltip == null || tooltipAnim < 0.02f) return;
        float tw = Nvg.textWidth(tooltip, 11.5f, Nvg.MED);
        float w = tw + 22, h = 26;
        float x = Math.min(gw - w - 8, mx + 16), y = Math.min(gh - h - 8, my + 18);
        Nvg.layer();
        Nvg.save();
        Nvg.scale(guiScale, guiScale);
        Theme.panel(x, y, w, h, 13, tooltipAnim, 10);
        Nvg.save();
        Nvg.fade(tooltipAnim);
        Nvg.text(tooltip, x + 11, y + 13.5f, 11.5f, Nvg.MED, Nvg.LEFT, Theme.text());
        Nvg.restore();
        Nvg.restore();
    }

    /* ------------------------------------------------------------------ panel */

    private static final float OPT_H = 22, BOX_W = 168, BOX_ROW = 24;
    private static final int DD_ROWS = 8;

    /** How a mode setting is shown: the Interface option, with Box falling back to Dropdown for long lists. */
    private static String selStyle(Setting.Mode m) {
        String st = tenacity.modules.Interface.I == null ? "Dropdown" : tenacity.modules.Interface.I.selector.get();
        if (m.modes.length < 2) return "Cycle";
        if (st.equals("Box") && m.modes.length > 12) return "Dropdown";
        return st;
    }

    private static float[] boxChipW(Setting.Mode m) {
        float[] w = new float[m.modes.length];
        for (int i = 0; i < w.length; i++) w[i] = Math.min(BOX_W, Nvg.textWidth(m.modes[i], 11, Nvg.SEMI) + 18);
        return w;
    }

    private static int boxRows(Setting.Mode m) {
        float[] cw = boxChipW(m);
        int rows = 1;
        float x = 0;
        for (float c : cw) {
            if (x > 0 && x + c > BOX_W) {
                rows++;
                x = 0;
            }
            x += c + 5;
        }
        return rows;
    }

    private static float ddListH(Setting.Mode m) {
        return Math.min(m.modes.length, DD_ROWS) * OPT_H + 8;
    }

    private static float settingH(Setting s) {
        if (s instanceof Setting.Mode m) {
            String st = selStyle(m);
            if (st.equals("Box")) return 28 + boxRows(m) * BOX_ROW + 4;
            if (st.equals("Dropdown")) return 28 + Anim.smooth(m.openAnim) * ddListH(m);
            return 28;
        }
        if (s instanceof Setting.Num) return 42;

        if (s instanceof Setting.Color) return 62;
        return 28;
    }

    private static float settingsHeight(Module m) {
        float h = 0;
        for (Setting s : m.settings) if (s.visible()) h += settingH(s);
        if (!m.locked()) h += 28; // bind row
        return h + 8;
    }

    private static void drawPanel(Panel p, int index, float e, float gh, float dt) {
        List<Module> mods = new ArrayList<>();
        for (Module m : ModuleManager.of(p.cat)) if (matches(m)) mods.add(m);

        // content height
        float content = 4;
        for (Module m : mods) {
            m.expandAnim = Anim.approach(m.expandAnim, m.expanded ? 1f : 0f, 13f);
            content += ROW + (settingsHeight(m) - 4) * Anim.smooth(m.expandAnim);
        }
        content += 4;
        float maxH = gh - p.y - 84;
        float viewport = Math.min(content, Math.max(80, maxH - HEADER));
        p.contentH = content;
        p.openAnim = Anim.approach(p.openAnim, p.open || boxMode ? 1f : 0f, 13f);
        float full = HEADER + viewport * Anim.smooth(p.openAnim);
        p.h = full;

        float oy = (1f - e) * 26f;
        float x = p.x, y = p.y + oy;

        // scrolling
        float maxScroll = Math.max(0, content - viewport);
        if (hoverPanel == p && scroll != 0 && p.open && !(ddHoverPrev != null && ddHoverPrev.modes.length > DD_ROWS)) p.scrollTarget = Math.max(0, Math.min(maxScroll, p.scrollTarget - scroll * 34));
        p.scrollTarget = Math.max(0, Math.min(maxScroll, p.scrollTarget));
        p.scroll = Anim.approach(p.scroll, p.scrollTarget, 16f);

        Theme.tintOverride = Theme.tint(index);
        Theme.rimOverride = Theme.rim(index);
        Theme.panel(x, y, pw, full, 22, e, 24);
        Theme.tintOverride = 0;

        Nvg.save();
        Nvg.fade(e);

        // header
        boolean hoverHeader = !boxMode && hoverPanel == p && in(p.x, p.y, pw, HEADER) && dragPanel == null;
        if (hoverHeader && tooltip == null) tooltip = p.open ? "Drag to move  -  click to collapse" : "Click to expand";
        Icons.draw(p.cat.icon, x + 15, y + 14, 18, Theme.text());
        Nvg.text(p.cat.label, x + 46, y + 23.5f, 14.5f, Nvg.SEMI, Nvg.LEFT, Theme.text());
        Nvg.save();
        Nvg.translate(x + pw - 22, y + 23);
        Nvg.rotate((float) (Math.PI * (1f - Anim.smooth(p.openAnim))) * -1f + (float) Math.PI);
        Icons.draw("chevron", -8, -8, 16, Theme.dim());
        Nvg.restore();

        if (Interface.themeId() > 0) drawThemePanel(x, y, index);
        Nvg.rect(x + 14, y + HEADER - 1, pw - 28, 1, Nvg.alpha(Theme.text(), 0.08f * Anim.smooth(p.openAnim)));
        if (pressed && hoverHeader) {
            dragPanel = p;
            dragX = mx;
            dragY = my;
            dragMoved = false;
        }
        if (rpressed && hoverHeader) p.open = !p.open;

        // body
        if (p.openAnim > 0.01f) {
            Nvg.save();
            Nvg.clip(x, y + HEADER, pw, viewport * Anim.smooth(p.openAnim));
            float yy = y + HEADER + 2 - p.scroll;
            for (Module m : mods) yy += drawModule(p, m, x, yy, y + HEADER, viewport * Anim.smooth(p.openAnim));
            Nvg.restore();
        }
        Nvg.restore();
    }

    /** Draws one module row (plus its settings) and returns the height it used. */
    private static float drawModule(Panel p, Module m, float x, float y, float viewTop, float viewH) {
        float exp = Anim.smooth(m.expandAnim);
        float rx = x + PAD, rw = pw - PAD * 2, rh = ROW - 6;
        float ry = y + 1;
        boolean visible = ry + ROW > viewTop && ry < viewTop + viewH;
        boolean hover = visible && hoverPanel == p && in(rx, ry, rw, rh) && ry >= viewTop - 2 && ry + rh <= viewTop + viewH + 2 + rh;
        m.hoverAnim = Anim.approach(m.hoverAnim, hover ? 1f : 0f, 16f);
        float target = m.locked() ? (m.expanded ? 1f : 0f) : (m.enabled() ? 1f : 0f);
        m.enabledAnim = Anim.approach(m.enabledAnim, target, 14f);

        if (visible) {
            m.pressAnim = Anim.approach(m.pressAnim, 0f, 9f);
            float ea = m.enabledAnim;
            float in = m.pressAnim * 3f;
            rx += in; rw -= in * 2; ry += in * 0.5f; rh -= in;
            if (m.hoverAnim > 0.01f) Nvg.rrect(rx, ry, rw, rh, 11, Nvg.alpha(Theme.text(), 0.09f * m.hoverAnim));
            if (ea > 0.01f && !m.locked()) {
                Nvg.rrect(rx, ry, rw, rh, 11, Interface.themeId() > 0 ? Nvg.alpha(Theme.accent(), 0.30f * ea) : Theme.dark() ? Nvg.alpha(Theme.text(), 0.13f * ea) : Nvg.alpha(0xFF0B0B0D, 0.92f * ea));
            }
            int tc = m.locked() ? Theme.text() : Theme.dark() ? Nvg.mix(Theme.dim(), Theme.text(), Math.max(ea, m.hoverAnim * 0.7f))
                    : Nvg.mix(Nvg.mix(Theme.dim(), Theme.text(), m.hoverAnim * 0.7f), 0xFFFFFFFF, ea);
            Nvg.text(m.name, rx + 12, ry + rh / 2f + 0.5f, 13, ea > 0.5f ? Nvg.SEMI : Nvg.MED, Nvg.LEFT, tc);
            // right side: bind chip or settings chevron
            if (m.bind.code != -1 && !m.locked()) {
                String k = m.bind.display();
                float kw = Nvg.textWidth(k, 9.5f, Nvg.SEMI) + 10;
                Nvg.rrect(rx + rw - 30 - kw, ry + rh / 2f - 7, kw, 14, 5, Nvg.alpha(tc, 0.14f));
                Nvg.text(k, rx + rw - 30 - kw / 2, ry + rh / 2f + 0.5f, 9.5f, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(tc, 0.85f));
            }
            Nvg.save();
            Nvg.translate(rx + rw - 15, ry + rh / 2f);
            Nvg.rotate((float) Math.PI * exp);
            Icons.draw(m.locked() ? "gear" : "chevron", -7, -7, 14, Nvg.alpha(tc, 0.7f));
            Nvg.restore();

            if (hover && tooltip == null && !m.description.isEmpty()) tooltip = m.description;
            if (hover && pressed) {
                m.pressAnim = 1f;
                if (m.locked()) m.expanded = !m.expanded;
                else m.toggle();
            }
            if (hover && rpressed) m.expanded = !m.expanded;
        }

        float used = ROW;
        float sh = settingsHeight(m) * exp;
        if (exp > 0.005f) {
            float sy = y + ROW - 4;
            Nvg.save();
            Nvg.clip(rx, sy, rw, sh);
            Nvg.rrect(rx, sy, rw, settingsHeight(m) - 4, 11, Nvg.alpha(Theme.text(), 0.05f));
            float cy = sy + 4;
            Nvg.save();
            Nvg.fade(exp);
            for (Setting s : m.settings) {
                if (!s.visible()) continue;
                cy += drawSetting(p, s, rx + 4, cy, rw - 8, viewTop, viewH);
            }
            cy += drawBind(p, m, rx + 4, cy, rw - 8, viewTop, viewH);
            Nvg.restore();
            Nvg.restore();
            used += sh - 4;
        }
        return used;
    }

    private static boolean canClick(Panel p, float x, float y, float w, float h, float viewTop, float viewH) {
        return hoverPanel == p && in(x, y, w, h) && y + h > viewTop && y < viewTop + viewH && dragNum == null;
    }

    private static float drawSetting(Panel p, Setting s, float x, float y, float w, float viewTop, float viewH) {
        float h = settingH(s);
        if (s instanceof Setting.Bool b) {
            boolean hov = canClick(p, x, y, w, h, viewTop, viewH);
            b.anim = Anim.approach(b.anim, b.value ? 1f : 0f, 15f);
            Nvg.text(b.name, x + 8, y + h / 2f + 0.5f, 12, Nvg.REG, Nvg.LEFT, hov ? Theme.text() : Theme.dim());
            float tw = 30, th = 16, tx = x + w - tw - 6, ty = y + h / 2f - th / 2f;
            Nvg.rrect(tx, ty, tw, th, th / 2, Nvg.mix(Nvg.alpha(Theme.text(), 0.16f), Theme.text(), b.anim));
            Nvg.circle(tx + 8 + (tw - 16) * b.anim, ty + th / 2f, 5.6f, Nvg.mix(Theme.text(), Theme.onFill(), b.anim));
            if (hov && pressed) b.toggle();
        } else if (s instanceof Setting.Num n) {
            Nvg.text(n.name, x + 8, y + 12, 12, Nvg.REG, Nvg.LEFT, Theme.dim());
            Nvg.text(n.display(), x + w - 8, y + 12, 11.5f, Nvg.SEMI, Nvg.RIGHT, Theme.text());
            float bx = x + 8, bw = w - 16, by = y + 29;
            boolean hov = canClick(p, bx - 4, by - 9, bw + 8, 18, viewTop, viewH);
            if (hov && pressed) {
                dragNum = n;
                dragNumX = bx;
                dragNumW = bw;
            }
            if (dragNum == n && down) n.set(n.min + (n.max - n.min) * Math.max(0, Math.min(1, (mx - dragNumX) / dragNumW)));
            n.anim = Anim.approach(n.anim, n.fraction(), 18f);
            Nvg.rrect(bx, by - 2, bw, 4, 2, Nvg.alpha(Theme.text(), 0.16f));
            Nvg.rrect(bx, by - 2, Math.max(4, bw * n.anim), 4, 2, Theme.text());
            float kr = dragNum == n || hov ? 6.5f : 5.5f;
            Nvg.circle(bx + bw * n.anim, by, kr + 1.2f, Nvg.alpha(0xFF000000, 0.25f));
            Nvg.circle(bx + bw * n.anim, by, kr, Theme.text());
        } else if (s instanceof Setting.Mode md) {
            modeRow(p, md, x, y, w, viewTop, viewH);
        } else if (s instanceof Setting.Color c) {
            Nvg.text(c.name, x + 8, y + 12, 12, Nvg.REG, Nvg.LEFT, Theme.dim());
            Nvg.rrect(x + w - 46, y + 5, 38, 13, 6.5f, c.rgb());
            Nvg.rrectStroke(x + w - 46, y + 5, 38, 13, 6.5f, 1f, Nvg.alpha(Theme.text(), 0.28f));
            float bx = x + 8, bw = w - 16, hy = y + 32, sy = y + 47;
            boolean hueHover = canClick(p, bx - 4, hy - 8, bw + 8, 16, viewTop, viewH);
            boolean satHover = canClick(p, bx - 4, sy - 8, bw + 8, 16, viewTop, viewH);
            if (pressed) {
                if (hueHover) {
                    dragColor = c;
                    dragHue = true;
                } else if (satHover) {
                    dragColor = c;
                    dragHue = false;
                }
            }
            if (dragColor == c && down) {
                float t = Math.max(0f, Math.min(1f, (mx - bx) / bw));
                if (dragHue) c.setHsv(t, c.sat());
                else c.setHsv(c.hue(), t);
            }
            int satColor = java.awt.Color.HSBtoRGB(c.hue(), 1f, 1f);
            for (int i = 0; i < 28; i++) {
                float t0 = i / 28f;
                Nvg.rect(bx + bw * t0, hy - 1.5f, bw / 28f + 0.6f, 3f, java.awt.Color.HSBtoRGB(t0, 1f, 1f));
                Nvg.rect(bx + bw * t0, sy - 1.5f, bw / 28f + 0.6f, 3f, Nvg.mix(0xFFFFFFFF, satColor, t0));
            }
            Nvg.circle(bx + bw * c.hue(), hy, 5.5f, Nvg.alpha(0xFF000000, 0.3f));
            Nvg.circle(bx + bw * c.hue(), hy, 4.5f, 0xFFFFFFFF);
            Nvg.circle(bx + bw * c.sat(), sy, 5.5f, Nvg.alpha(0xFF000000, 0.3f));
            Nvg.circle(bx + bw * c.sat(), sy, 4.5f, 0xFFFFFFFF);
        } else if (s instanceof Setting.Key k) {
            keyRow(p, k, x, y, w, h, viewTop, viewH);
        } else if (s instanceof Setting.Text t) {
            textRow(p, t, x, y, w, h, viewTop, viewH);
        }
        return h;
    }

    private static void modeRow(Panel p, Setting.Mode md, float x, float y, float w, float viewTop, float viewH) {
        String st = selStyle(md);
        float rowH = 28;
        boolean hov = canClick(p, x, y, w, rowH, viewTop, viewH);
        float nameW = Math.min(Nvg.textWidth(md.name, 12, Nvg.REG), w * 0.5f);
        float maxChip = Math.max(40, w - nameW - 28);
        Nvg.text(Nvg.fit(md.name, 12, Nvg.REG, nameW), x + 8, y + rowH / 2f + 0.5f, 12, Nvg.REG, Nvg.LEFT, hov ? Theme.text() : Theme.dim());
        if (st.equals("Cycle")) {
            md.open = false;
            md.openAnim = 0f;
            String v = md.get();
            float vw = Math.min(maxChip, Nvg.textWidth(v, 11, Nvg.SEMI) + 16);
            Nvg.rrect(x + w - vw - 6, y + rowH / 2f - 9, vw, 18, 9, Nvg.alpha(Theme.text(), hov ? 0.18f : 0.11f));
            Nvg.text(Nvg.fit(v, 11, Nvg.SEMI, vw - 12), x + w - 6 - vw / 2, y + rowH / 2f + 0.5f, 11, Nvg.SEMI, Nvg.CENTER, Theme.text());
            if (hov && pressed) md.next(1);
            if (hov && rpressed) md.next(-1);
            return;
        }
        if (st.equals("Box")) {
            md.open = false;
            md.openAnim = 0f;
            float[] cw = boxChipW(md);
            float cx = 0, cy = y + rowH - 2;
            for (int i = 0; i < md.modes.length; i++) {
                if (cx > 0 && cx + cw[i] > BOX_W) {
                    cx = 0;
                    cy += BOX_ROW;
                }
                float bx = x + 8 + cx, by = cy, bw = cw[i], bh = 20;
                boolean sel = i == md.index;
                boolean ch = canClick(p, bx, by, bw, bh, viewTop, viewH);
                if (sel) Nvg.rrect(bx, by, bw, bh, 10, Theme.text());
                else Nvg.rrect(bx, by, bw, bh, 10, Nvg.alpha(Theme.text(), ch ? 0.18f : 0.09f));
                Nvg.text(Nvg.fit(md.modes[i], 11, Nvg.SEMI, bw - 10), bx + bw / 2, by + bh / 2f + 0.5f, 11, Nvg.SEMI, Nvg.CENTER,
                        sel ? Theme.onFill() : ch ? Theme.text() : Theme.dim());
                if (ch && pressed) md.index = i;
                cx += bw + 5;
            }
            return;
        }
        // dropdown
        md.openAnim = Anim.approach(md.openAnim, md.open ? 1f : 0f, 15f);
        String v = md.get();
        float vw = Math.min(maxChip, Nvg.textWidth(v, 11, Nvg.SEMI) + 30);
        float chx = x + w - vw - 6, chy = y + rowH / 2f - 9;
        boolean chHov = canClick(p, chx, chy, vw, 18, viewTop, viewH);
        Nvg.rrect(chx, chy, vw, 18, 9, Nvg.alpha(Theme.text(), chHov || md.open ? 0.2f : 0.11f));
        Nvg.text(Nvg.fit(v, 11, Nvg.SEMI, vw - 26), chx + 9, y + rowH / 2f + 0.5f, 11, Nvg.SEMI, Nvg.LEFT, Theme.text());
        Nvg.save();
        Nvg.translate(chx + vw - 11, y + rowH / 2f);
        Nvg.rotate((float) Math.PI * Anim.smooth(md.openAnim));
        Icons.draw("chevron", -5, -5, 10, Nvg.alpha(Theme.text(), 0.8f));
        Nvg.restore();
        if (chHov && pressed) {
            md.open = !md.open;
            if (md.open) md.listScroll = Math.max(0, Math.min(md.modes.length * OPT_H - DD_ROWS * OPT_H, (md.index - 2) * OPT_H));
        }
        if (chHov && rpressed) md.next(-1);
        float ea = Anim.smooth(md.openAnim);
        if (ea < 0.01f) return;
        float lh = ddListH(md) * ea, lx = x + 8, ly = y + rowH - 2, lw = w - 16;
        boolean inList = md.open && hoverPanel == p && in(lx, ly, lw, lh);
        Nvg.save();
        Nvg.clip(lx, ly, lw, lh);
        Nvg.rrect(lx, ly, lw, ddListH(md) - 4, 10, Nvg.alpha(Theme.text(), 0.09f));
        float max = Math.max(0, md.modes.length * OPT_H - DD_ROWS * OPT_H);
        if (inList && scroll != 0 && max > 0) {
            md.listScroll = Math.max(0, Math.min(max, md.listScroll - scroll * OPT_H));
            scroll = 0;
        }
        if (inList) ddHover = md;
        for (int i = 0; i < md.modes.length; i++) {
            float oy = ly + 2 + i * OPT_H - md.listScroll;
            if (oy + OPT_H < ly || oy > ly + lh) continue;
            boolean sel = i == md.index;
            boolean oh = inList && my >= oy && my < oy + OPT_H && y + rowH > viewTop && y < viewTop + viewH;
            if (sel) Nvg.rrect(lx + 3, oy + 1, lw - 6, OPT_H - 2, 8, Theme.text());
            else if (oh) Nvg.rrect(lx + 3, oy + 1, lw - 6, OPT_H - 2, 8, Nvg.alpha(Theme.text(), 0.14f));
            Nvg.text(Nvg.fit(md.modes[i], 11, Nvg.MED, lw - 22), lx + 11, oy + OPT_H / 2f + 0.5f, 11, sel ? Nvg.SEMI : Nvg.MED, Nvg.LEFT,
                    sel ? Theme.onFill() : oh ? Theme.text() : Theme.dim());
            if (oh && pressed) {
                md.index = i;
                md.open = false;
            }
        }
        if (max > 0) {
            float th = Math.max(14, (lh - 4) * (DD_ROWS * OPT_H) / (md.modes.length * OPT_H));
            float ty = ly + 2 + (lh - 4 - th) * (md.listScroll / max);
            Nvg.rrect(lx + lw - 4, ty, 2.5f, th, 1.2f, Nvg.alpha(Theme.text(), 0.3f));
        }
        Nvg.restore();
        // a click anywhere else closes it
        if (pressed && md.open && !chHov && !inList) md.open = false;
    }

    private static void textRow(Panel p, Setting.Text t, float x, float y, float w, float h, float viewTop, float viewH) {
        boolean hov = canClick(p, x, y, w, h, viewTop, viewH);
        boolean ed = t.editing;
        Nvg.text(t.name, x + 8, y + h / 2f + 0.5f, 12, Nvg.REG, Nvg.LEFT, hov || ed ? Theme.text() : Theme.dim());
        float fw = Math.max(70, w * 0.55f), fx = x + w - fw - 6;
        Nvg.rrect(fx, y + h / 2f - 9, fw, 18, 9, ed ? Nvg.alpha(Theme.text(), 0.22f) : Nvg.alpha(Theme.text(), hov ? 0.18f : 0.11f));
        String shown = Nvg.fit(t.value, 11, Nvg.SEMI, fw - 16);
        boolean caret = ed && (System.currentTimeMillis() / 450 % 2 == 0);
        if (ed) {
            shown = t.value;
            while (shown.length() > 1 && Nvg.textWidth(shown, 11, Nvg.SEMI) > fw - 22) shown = shown.substring(1);
        }
        Nvg.text(shown + (caret ? "|" : ""), fx + 8, y + h / 2f + 0.5f, 11, Nvg.SEMI, Nvg.LEFT, Theme.text());
        if (pressed) {
            if (hov) {
                if (editing != null && editing != t) editing.editing = false;
                t.editing = true;
                editing = t;
            } else if (ed) {
                t.editing = false;
                editing = null;
            }
        }
    }

    private static void keyRow(Panel p, Setting.Key k, float x, float y, float w, float h, float viewTop, float viewH) {
        boolean hov = canClick(p, x, y, w, h, viewTop, viewH);
        Nvg.text(k.name, x + 8, y + h / 2f + 0.5f, 12, Nvg.REG, Nvg.LEFT, hov ? Theme.text() : Theme.dim());
        String v = k.display();
        float vw = Math.max(34, Nvg.textWidth(v, 11, Nvg.SEMI) + 16);
        boolean lst = k.listening;
        Nvg.rrect(x + w - vw - 6, y + h / 2f - 9, vw, 18, 9, lst ? Theme.text() : Nvg.alpha(Theme.text(), hov ? 0.18f : 0.11f));
        Nvg.text(v, x + w - 6 - vw / 2, y + h / 2f + 0.5f, 11, Nvg.SEMI, Nvg.CENTER, lst ? Theme.onFill() : Theme.text());
        if (hov && pressed) {
            if (listening != null) listening.listening = false;
            k.listening = true;
            listening = k;
        } else if (hov && rpressed) k.code = -1;
        else if (lst && mpressed) k.listening = false;
    }

    private static float drawBind(Panel p, Module m, float x, float y, float w, float viewTop, float viewH) {
        if (m.locked()) return 0;
        float h = 28;
        keyRow(p, m.bind, x, y, w, h, viewTop, viewH);
        return h;
    }

    /** Returns true while a key bind is being captured (the screen then forwards every key). */
    public static boolean capturing() {
        return listening != null;
    }

    /** Mouse buttons other than left / right bind while capturing. */
    public static boolean captureMouse(int button) {
        if (listening == null || button < 2) return false;
        listening.code = -100 - button;
        listening.listening = false;
        listening = null;
        return true;
    }
}

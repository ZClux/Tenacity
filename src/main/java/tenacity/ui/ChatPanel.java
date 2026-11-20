package tenacity.ui;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import tenacity.core.Chat;
import tenacity.gfx.Nvg;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global chat panel (Discord style) opened from the click gui. Same size and anchoring as {@link ConfigPanel}.
 * Immediate mode, drawn inside the click gui's scaled space, input handed over by {@link ClickGui}.
 */
public final class ChatPanel {
    private static final float W = ConfigPanel.W, H = ConfigPanel.H;
    private static final int MAX_LEN = 200;
    private static final long GROUP_MS = 3 * 60_000L;

    public static boolean open;
    private static float anim;

    private static float mx, my, scroll;
    private static boolean pressed, pWas, justOpened;
    private static long openedAt;
    private static final Map<String, Float> A = new HashMap<>();

    private static String input = "";
    private static boolean focused = true;
    private static float listScroll, listScrollT, vel;
    private static boolean stick = true;
    private static long lastSend;
    private static boolean settingsOpen;
    private static float settingsAnim;

    /* ------------------------------------------------------------------ avatars */

    /** One avatar per key (uuid, or lowercase name when there is no real uuid). Bytes arrive on a worker thread. */
    private static final class Head {
        volatile byte[] bytes;
        volatile boolean loading;
        volatile long retryAt;
        int fails;
        int img;
        float fade;
    }

    private static final Map<String, Head> HEADS = new ConcurrentHashMap<>();
    private static final AtomicInteger HEAD_THREADS = new AtomicInteger();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private ChatPanel() {
    }

    public static void toggle() {
        open = !open;
        if (open) {
            input = "";
            focused = true;
            justOpened = true;
            openedAt = System.currentTimeMillis();
            stick = true;
            settingsOpen = false;
            Chat.ensure();
        }
        Chat.panelOpen = open;
        if (open) Chat.unread = 0;
    }

    public static void openDev() {
        open = true;
        Chat.panelOpen = true;
        Chat.unread = 0;
        stick = true;
    }

    public static boolean visible() {
        return open || anim > 0.01f;
    }

    private static void close() {
        open = false;
        Chat.panelOpen = false;
        pressed = false;
    }

    private static boolean in(float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private static float a(String id, float target, float speed) {
        float v = Anim.approach(A.getOrDefault(id, 0f), target, speed);
        A.put(id, v);
        return v;
    }

    /* ------------------------------------------------------------------ frame */

    public static void draw(float gw, float gh, float pmx, float pmy, boolean ppressed, float pscroll, List<Integer> keys, CharSequence typed, float gs) {
        anim = Anim.approach(anim, open ? 1f : 0f, open ? 8f : 11f);
        if (anim < 0.01f && !open) {
            releaseHeads();
            return;
        }
        mx = pmx;
        my = pmy;
        pressed = ppressed && open;
        boolean edge = pressed && !pWas;
        pWas = pressed;
        scroll = open ? pscroll : 0;
        if (open) {
            Chat.unread = 0;
            handleKeys(keys, typed);
        }

        float e = Anim.easeOut(anim);
        float x = ConfigPanel.panelX(gw), y = ConfigPanel.panelY(gh, e);

        Nvg.layer();
        Nvg.save();
        Nvg.scale(gs, gs);
        Nvg.rect(0, 0, gw, gh, Nvg.alpha(0xFF000000, 0.28f * e));
        Nvg.restore();
        Nvg.layer();
        Nvg.save();
        Nvg.scale(gs, gs);
        Theme.panel(x, y, W, H, 26, e, 30);
        Nvg.save();
        Nvg.fade(e);

        if (edge && !in(x, y, W, H)) {
            if (justOpened || System.currentTimeMillis() - openedAt < 350) justOpened = false;
            else close();
        }

        // a press while the settings popover is open is consumed by it first
        float popX = x + W - 252, popY = y + 54, popW = 232, popH = 98;
        boolean popHit = settingsOpen && settingsAnim > 0.5f && in(popX, popY, popW, popH);
        boolean gearHit = in(x + W - 82, y + 16, 30, 30);
        if (settingsOpen && pressed && !popHit && !gearHit) settingsOpen = false; // click elsewhere closes it, click still goes through

        float bx = x + 20, bw = W - 40;
        float inputH = 42;
        float ly = y + 62, lh = H - 62 - inputH - 34;
        boolean wasPressed = pressed;
        if (popHit) pressed = false; // body widgets must not react to presses meant for the popover
        drawList(bx, ly, bw, lh);
        drawInput(bx, y + H - inputH - 16, bw, inputH);
        pressed = wasPressed;
        drawHeader(x, y);
        drawSettings(popX, popY, popW, popH);

        Nvg.restore();
        Nvg.restore();
    }

    private static void drawHeader(float x, float y) {
        // channel strip: "# global" + status + online
        Nvg.text("#", x + 24, y + 30, 22, Nvg.MED, Nvg.LEFT, Theme.faint());
        Nvg.text("global", x + 42, y + 30.5f, 18, Nvg.SEMI, Nvg.LEFT, Theme.text());
        float tw = Nvg.textWidth("global", 18, Nvg.SEMI);

        Chat.State st = Chat.state;
        int dot = switch (st) {
            case ONLINE -> 0xFF4ADE80;
            case POLLING, CONNECTING -> 0xFFFBBF24;
            default -> 0xFFEF4444;
        };
        float pulse = (float) (0.5 + 0.5 * Math.sin(System.nanoTime() / 4e8));
        float sx = x + 42 + tw + 22;
        Nvg.rect(sx - 12, y + 22, 1, 16, Nvg.alpha(Theme.text(), 0.12f));
        if (st == Chat.State.CONNECTING) Nvg.circle(sx + 3, y + 30, 4.5f + 2.5f * pulse, Nvg.alpha(dot, 0.25f));
        else if (st == Chat.State.ONLINE) Nvg.circle(sx + 3, y + 30, 4.5f + 1.5f * pulse, Nvg.alpha(dot, 0.2f));
        Nvg.circle(sx + 3, y + 30, 3.6f, dot);
        String label = switch (st) {
            case ONLINE -> Chat.online + " online";
            case POLLING -> "Limited  -  " + Chat.online + " online";
            case CONNECTING -> "Connecting...";
            default -> "Offline";
        };
        Nvg.text(label, sx + 14, y + 30.5f, 12, Nvg.MED, Nvg.LEFT, Theme.dim());

        // settings gear
        float gx = x + W - 82, gy = y + 16;
        boolean gh = in(gx, gy, 30, 30);
        float gt = a("gear", gh || settingsOpen ? 1f : 0f, 16f);
        Nvg.rrect(gx, gy, 30, 30, 15, Nvg.alpha(Theme.text(), 0.06f + 0.1f * gt));
        drawGear(gx + 15, gy + 15, 8.2f, Theme.text(), a("gearRot", settingsOpen ? 1f : 0f, 7f) * 1.05f);
        if (pressed && gh) {
            settingsOpen = !settingsOpen;
            pressed = false;
        }

        // close
        float cx = x + W - 46, cy = y + 16;
        float ch = a("close", in(cx, cy, 30, 30) ? 1f : 0f, 16f);
        Nvg.rrect(cx, cy, 30, 30, 15, Nvg.alpha(Theme.text(), 0.06f + 0.1f * ch));
        Nvg.line(cx + 10.5f, cy + 10.5f, cx + 19.5f, cy + 19.5f, 1.8f, Theme.text());
        Nvg.line(cx + 19.5f, cy + 10.5f, cx + 10.5f, cy + 19.5f, 1.8f, Theme.text());
        if (pressed && in(cx, cy, 30, 30)) close();
        Nvg.rect(x + 20, y + 60, W - 40, 1, Nvg.alpha(Theme.text(), 0.08f));
    }

    private static void drawGear(float cx, float cy, float r, int c, float rot) {
        Nvg.save();
        Nvg.translate(cx, cy);
        Nvg.rotate(rot);
        Nvg.circleStroke(0, 0, r * 0.62f, 1.8f, c);
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4;
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            Nvg.line(ca * r * 0.78f, sa * r * 0.78f, ca * r * 1.12f, sa * r * 1.12f, 2.4f, c);
        }
        Nvg.circleStroke(0, 0, r * 0.22f, 1.5f, Nvg.alpha(c, 0.8f));
        Nvg.restore();
    }

    /** Small popover under the gear with the "Hide my name" toggle. */
    private static void drawSettings(float x, float y, float w, float h) {
        settingsAnim = Anim.approach(settingsAnim, settingsOpen ? 1f : 0f, 14f);
        if (settingsAnim < 0.01f) return;
        float t = Anim.easeOut(settingsAnim);
        Nvg.save();
        Nvg.fade(t);
        Nvg.translate(0, (1 - t) * -8);
        Theme.hud(x, y, w, h, 16, 1f, 0);
        Nvg.text("Chat settings", x + 16, y + 20, 11, Nvg.SEMI, Nvg.LEFT, Theme.faint());
        boolean on = Chat.hideName();
        Nvg.text("Hide my name", x + 16, y + 46, 13, Nvg.SEMI, Nvg.LEFT, Theme.text());
        Nvg.text("Send as Anonymous", x + 16, y + 64, 10.5f, Nvg.REG, Nvg.LEFT, Theme.faint());
        Nvg.text("with a neutral avatar", x + 16, y + 78, 10.5f, Nvg.REG, Nvg.LEFT, Theme.faint());
        float tx = x + w - 56, ty = y + 36, tw = 40, th = 22;
        boolean hov = in(tx - 6, ty - 6, tw + 12, th + 12) && settingsOpen;
        float k = a("hideKnob", on ? 1f : 0f, 14f);
        float hv = a("hideHov", hov ? 1f : 0f, 14f);
        Nvg.rrect(tx, ty, tw, th, th / 2f, Nvg.mix(Nvg.alpha(Theme.text(), 0.16f + 0.06f * hv), Theme.text(), k));
        float kx = tx + 3 + (tw - th) * Anim.smooth(k);
        Nvg.circle(kx + (th - 6) / 2f, ty + th / 2f, (th - 6) / 2f + 0.5f * hv, Nvg.mix(Theme.text(), Theme.onFill(), k));
        if (hov && pressed) {
            Chat.setHideName(!on);
            pressed = false;
        }
        Nvg.restore();
    }

    /* ------------------------------------------------------------------ keys + input */

    private static void handleKeys(List<Integer> keys, CharSequence typed) {
        long win = MinecraftClient.getInstance().getWindow().getHandle();
        boolean ctrl = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;
        for (int k : keys) {
            if (k == GLFW.GLFW_KEY_ESCAPE) {
                if (settingsOpen) settingsOpen = false;
                else {
                    open = false;
                    Chat.panelOpen = false;
                }
            } else if (focused) {
                if (k == GLFW.GLFW_KEY_BACKSPACE && !input.isEmpty()) input = ctrl ? "" : input.substring(0, input.length() - 1);
                else if (k == GLFW.GLFW_KEY_V && ctrl) input += MinecraftClient.getInstance().keyboard.getClipboard().replaceAll("[\\r\\n\\t]", " ");
                else if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) submit();
            }
        }
        if (focused && typed.length() > 0) input += typed;
        if (input.length() > MAX_LEN) input = input.substring(0, MAX_LEN);
    }

    private static void submit() {
        String t = input.trim();
        if (t.isEmpty()) return;
        if (System.currentTimeMillis() - lastSend < 350) return;
        if (Chat.send(t)) {
            input = "";
            lastSend = System.currentTimeMillis();
            stick = true;
        }
    }

    private static void drawInput(float x, float y, float w, float h) {
        boolean hov = in(x, y, w, h);
        float t = a("in", focused ? 1f : hov ? 0.4f : 0f, 14f);
        float sendW = 44;
        float fw = w - sendW - 8;
        float r = 14;
        Nvg.rrect(x, y, fw, h, r, Nvg.alpha(Theme.text(), 0.07f + 0.05f * t));
        Nvg.rrectStroke(x, y, fw, h, r, 1f, Nvg.alpha(Theme.text(), 0.06f + 0.3f * t));
        float tx = x + 16;
        Nvg.save();
        Nvg.clip(x + 4, y, fw - 62, h);
        if (input.isEmpty()) Nvg.text("Message #global", tx, y + h / 2f + 0.5f, 13, Nvg.REG, Nvg.LEFT, Theme.faint());
        else {
            float vw = Nvg.textWidth(input, 13, Nvg.MED);
            float shift = Math.max(0, vw - (fw - 80));
            Nvg.text(input, tx - shift, y + h / 2f + 0.5f, 13, Nvg.MED, Nvg.LEFT, Theme.text());
            tx += vw - shift;
        }
        if (focused) {
            float blink = (System.currentTimeMillis() / 500 % 2 == 0) ? 1f : 0.2f;
            Nvg.rrect(tx + 2, y + h / 2f - 8, 1.6f, 16, 0.8f, Nvg.alpha(Theme.text(), blink));
        }
        Nvg.restore();
        if (!input.isEmpty()) {
            int left = MAX_LEN - input.length();
            Nvg.text(String.valueOf(left), x + fw - 12, y + h / 2f + 0.5f, 10.5f, Nvg.MED, Nvg.RIGHT, left < 20 ? 0xFFFBBF24 : Theme.faint());
        }
        if (pressed && hov && in(x, y, fw, h)) {
            focused = true;
            pressed = false;
        }
        boolean ok = !input.trim().isEmpty();
        boolean sh = ok && in(x + w - sendW, y, sendW, h);
        float st = a("send", sh ? 1f : 0f, 16f);
        float okT = a("sendok", ok ? 1f : 0f, 12f);
        float p = a("sendp", 0f, 10f);
        float g = st * 1.5f - p * 2f;
        Nvg.rrect(x + w - sendW - g, y - g / 2f, sendW + g * 2, h + g, r, Nvg.alpha(Theme.text(), 0.12f + (0.8f + 0.08f * st) * okT));
        drawSendIcon(x + w - sendW + 12, y + h / 2f - 10, 20, Nvg.mix(Theme.faint(), Theme.onFill(), okT));
        if (sh && pressed) {
            A.put("sendp", 1f);
            pressed = false;
            submit();
        }
    }

    private static void drawSendIcon(float x, float y, float size, int c) {
        Nvg.save();
        Nvg.translate(x, y);
        Nvg.scale(size / 24f, size / 24f);
        Nvg.beginPath();
        Nvg.moveTo(4, 12); Nvg.lineTo(20, 4); Nvg.lineTo(14.5f, 20); Nvg.lineTo(11.5f, 13.2f); Nvg.closePath();
        Nvg.strokePath(2f, c);
        Nvg.line(11.5f, 13.2f, 20, 4, 2f, c);
        Nvg.restore();
    }

    /* ------------------------------------------------------------------ list */

    private static final float AV = 38, TEXT_X = 52, LINE = 17.5f, HEAD_H = 22, PAD = 4;

    /** Wraps and measures one message. h is the full row height including the header when this row starts a group. */
    private static void layout(Chat.Msg m, boolean header, float textW) {
        if (m.system) {
            if (m.lines == null || m.wrapW != textW) {
                m.wrapW = textW;
                m.lines = wrap(m.text, textW - 40, 12f);
            }
            m.h = 12 + m.lines.size() * 16;
            return;
        }
        if (m.lines == null || m.wrapW != textW) {
            m.wrapW = textW;
            m.lines = wrap(m.text, textW, 13.5f);
        }
        m.h = (header ? HEAD_H + 6 : 0) + m.lines.size() * LINE + PAD + (header ? 4 : 0);
    }

    private static List<String> wrap(String s, float maxW, float size) {
        List<String> out = new ArrayList<>();
        for (String para : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ", -1)) {
                String test = line.length() == 0 ? word : line + " " + word;
                if (Nvg.textWidth(test, size, Nvg.REG) <= maxW) {
                    line.setLength(0);
                    line.append(test);
                    continue;
                }
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (Nvg.textWidth(rest, size, Nvg.REG) > maxW && rest.length() > 1) {
                    int n = rest.length();
                    while (n > 1 && Nvg.textWidth(rest.substring(0, n), size, Nvg.REG) > maxW) n--;
                    out.add(rest.substring(0, n));
                    rest = rest.substring(n);
                }
                line.append(rest);
            }
            out.add(line.toString());
        }
        return out;
    }

    private static boolean startsGroup(Chat.Msg prev, Chat.Msg m) {
        if (prev == null || prev.system || m.system) return true;
        if (!prev.user.equalsIgnoreCase(m.user) || prev.mine != m.mine) return true;
        return m.ts - prev.ts > GROUP_MS;
    }

    private static void drawList(float x, float y, float w, float h) {
        float textW = w - TEXT_X - 22;
        List<Chat.Msg> msgs = Chat.snapshot();
        boolean[] hdr = new boolean[msgs.size()];
        float total = 10;
        Chat.Msg prev = null;
        for (int i = 0; i < msgs.size(); i++) {
            Chat.Msg m = msgs.get(i);
            hdr[i] = startsGroup(prev, m);
            layout(m, hdr[i], textW);
            total += m.h + (hdr[i] && i > 0 ? 8 : 0);
            prev = m;
        }
        float maxScroll = Math.max(0, total - h);

        if (open && scroll != 0 && in(x, y, w, h) && !(settingsOpen && settingsAnim > 0.5f)) {
            vel -= scroll * 520f;
            stick = false;
        }
        listScrollT += vel * Anim.dt;
        vel *= (float) Math.exp(-9f * Anim.dt);
        if (Math.abs(vel) < 1f) vel = 0;
        listScrollT = Math.max(0, Math.min(maxScroll, listScrollT));
        if (listScrollT >= maxScroll - 2) stick = true;
        if (stick) listScrollT = maxScroll;
        listScroll = Anim.approach(listScroll, listScrollT, stick ? 14f : 22f);

        Nvg.save();
        Nvg.clip(x - 6, y, w + 12, h);

        if (msgs.isEmpty()) {
            Chat.State st = Chat.state;
            ChatPanel.drawBubble(x + w / 2f - 22, y + h / 2f - 54, 44, Theme.faint());
            if (st == Chat.State.CONNECTING) {
                spinner(x + w / 2f, y + h / 2f + 8, 10, 2.2f);
                Nvg.text("Connecting to the chat...", x + w / 2f, y + h / 2f + 38, 12, Nvg.MED, Nvg.CENTER, Theme.dim());
            } else {
                Nvg.text("Welcome to #global", x + w / 2f, y + h / 2f + 8, 14, Nvg.SEMI, Nvg.CENTER, Theme.dim());
                String sub = (st == Chat.State.OFFLINE || st == Chat.State.POLLING) && !Chat.lastError.isEmpty() ? Chat.lastError : "This is the start of the conversation. Say hello!";
                Nvg.text(sub, x + w / 2f, y + h / 2f + 28, 12, Nvg.REG, Nvg.CENTER, Theme.faint());
            }
            Nvg.restore();
            return;
        }

        float yy = y + 4 - listScroll + (total < h ? h - total : 0);
        long now = System.currentTimeMillis();
        boolean listHover = my >= y && my <= y + h && !(settingsOpen && settingsAnim > 0.5f);
        for (int i = 0; i < msgs.size(); i++) {
            Chat.Msg m = msgs.get(i);
            if (hdr[i] && i > 0) yy += 8;
            float ry = yy;
            yy += m.h;
            if (ry + m.h < y - 4 || ry > y + h + 4) continue;
            m.anim = Anim.approach(m.anim, 1f, 9f);
            float ap = Anim.easeOut(m.anim);
            Nvg.save();
            Nvg.fade(ap);
            Nvg.translate((1 - ap) * 10, (1 - ap) * 14);

            if (m.system) {
                float lw = 0;
                for (String l : m.lines) lw = Math.max(lw, Nvg.textWidth(l, 12, Nvg.MED));
                float bw = lw + 28, bh = m.lines.size() * 16 + 6;
                Nvg.rrect(x + w / 2f - bw / 2f, ry + 3, bw, bh, 10, Nvg.alpha(0xFFFBBF24, 0.10f));
                Nvg.rrectStroke(x + w / 2f - bw / 2f, ry + 3, bw, bh, 10, 1f, Nvg.alpha(0xFFFBBF24, 0.22f));
                for (int k = 0; k < m.lines.size(); k++) {
                    Nvg.text(m.lines.get(k), x + w / 2f, ry + 3 + 11 + k * 16, 12, Nvg.MED, Nvg.CENTER, 0xFFFBBF24);
                }
                Nvg.restore();
                continue;
            }

            boolean mention = mentions(m);
            float rowTop = ry - (hdr[i] ? 2 : 0), rowH = m.h - (hdr[i] ? 0 : 0);
            boolean hov = listHover && in(x, rowTop, w, rowH);
            float ht = a("m" + m.id + ":" + i, hov ? 1f : 0f, 16f);
            if (mention) {
                Nvg.rrect(x, rowTop, w, rowH, 10, Nvg.alpha(0xFFFBBF24, 0.10f + 0.04f * ht));
                Nvg.rrect(x, rowTop + 3, 2.5f, rowH - 6, 1.2f, 0xFFFBBF24);
            } else if (ht > 0.01f) {
                Nvg.rrect(x, rowTop, w, rowH, 10, Nvg.alpha(Theme.text(), 0.055f * ht));
            }

            float ty = ry;
            if (hdr[i]) {
                head(m, x + 8, ry + 2, AV);
                String name = Chat.display(m);
                int nc = nameColor(m, name);
                Nvg.text(name, x + TEXT_X, ry + 11, 14, Nvg.SEMI, Nvg.LEFT, nc);
                float nw = Nvg.textWidth(name, 14, Nvg.SEMI);
                float bx = x + TEXT_X + nw + 8;
                if (m.verified) {
                    tickBadge(bx + 6, ry + 10.5f);
                    bx += 18;
                }
                Nvg.text(time(m.ts, now), bx, ry + 11.5f, 10.5f, Nvg.REG, Nvg.LEFT, Theme.faint());
                ty = ry + HEAD_H + 2;
            } else {
                // compact row: time shows in the gutter on hover
                if (ht > 0.01f) Nvg.text(hm(m.ts), x + 8 + AV / 2f, ry + 9, 9.5f, Nvg.MED, Nvg.CENTER, Nvg.alpha(Theme.faint(), ht));
            }
            int tc = Nvg.alpha(Theme.text(), 0.92f);
            for (int k = 0; k < m.lines.size(); k++) {
                drawLine(m.lines.get(k), x + TEXT_X, ty + 8 + k * LINE, tc, mention);
            }
            Nvg.restore();
        }
        Nvg.restore();

        // slim scrollbar with eased fade
        float sbT = a("sb", total > h && (listHover || Math.abs(vel) > 1f) ? 1f : 0f, 8f);
        if (total > h && sbT > 0.01f) {
            float th = Math.max(30, h * h / total);
            float ty = y + (h - th) * (listScroll / Math.max(1, maxScroll));
            Nvg.rrect(x + w + 3, ty, 3, th, 1.5f, Nvg.alpha(Theme.text(), 0.08f + 0.2f * sbT));
        }
        float jt = a("jump", !stick && maxScroll - listScroll > 40 ? 1f : 0f, 12f);
        if (jt > 0.02f) {
            float jw = 124, jx = x + w / 2f - jw / 2f, jy = y + h - 34 + (1 - jt) * 12;
            Nvg.save();
            Nvg.fade(jt);
            boolean jh = in(jx, jy, jw, 28);
            Nvg.rrect(jx, jy, jw, 28, 14, Nvg.alpha(Theme.text(), jh ? 0.95f : 0.88f));
            Nvg.text("Jump to present", jx + jw / 2f, jy + 14.5f, 11.5f, Nvg.SEMI, Nvg.CENTER, Theme.onFill());
            Nvg.restore();
            if (jh && pressed && jt > 0.5f) {
                stick = true;
                vel = 0;
                pressed = false;
            }
        }
    }

    /** Draws a line of message text; @mentions of me get a highlighted pill. */
    private static void drawLine(String line, float x, float y, int color, boolean mention) {
        String real = Chat.realName();
        if (!mention || real.isEmpty()) {
            Nvg.text(line, x, y, 13.5f, Nvg.REG, Nvg.LEFT, color);
            return;
        }
        String low = line.toLowerCase(Locale.ROOT);
        String[] tags = {"@" + real.toLowerCase(Locale.ROOT), "@" + Chat.alias(real).toLowerCase(Locale.ROOT)};
        int pos = 0;
        float cx = x;
        while (pos < line.length()) {
            int hit = -1, hl = 0;
            for (String t : tags) {
                int i = low.indexOf(t, pos);
                if (i >= 0 && (hit < 0 || i < hit)) {
                    hit = i;
                    hl = t.length();
                }
            }
            if (hit < 0) {
                Nvg.text(line.substring(pos), cx, y, 13.5f, Nvg.REG, Nvg.LEFT, color);
                break;
            }
            if (hit > pos) {
                String pre = line.substring(pos, hit);
                Nvg.text(pre, cx, y, 13.5f, Nvg.REG, Nvg.LEFT, color);
                cx += Nvg.textWidth(pre, 13.5f, Nvg.REG);
            }
            String tag = line.substring(hit, hit + hl);
            float tw = Nvg.textWidth(tag, 13.5f, Nvg.SEMI);
            Nvg.rrect(cx - 2, y - 9, tw + 4, 18, 5, Nvg.alpha(0xFFFBBF24, 0.28f));
            Nvg.text(tag, cx, y, 13.5f, Nvg.SEMI, Nvg.LEFT, 0xFFFFE08A);
            cx += tw + 0.5f;
            pos = hit + hl;
        }
    }

    /** Stable per-user color: hashed hue, tuned to read on both glass shades. */
    private static int nameColor(Chat.Msg m, String name) {
        if (name.equals(Chat.ANON_NAME)) return Theme.dim();
        float hue = (name.toLowerCase(Locale.ROOT).hashCode() & 0xFFFF) / 65535f;
        int base = java.awt.Color.HSBtoRGB(hue, 0.50f, Theme.dark() ? 0.98f : 0.62f) | 0xFF000000;
        return m.verified || m.mine ? base : Nvg.mix(base, Theme.dim(), 0.45f);
    }

    private static void tickBadge(float cx, float cy) {
        Nvg.circle(cx, cy, 6.2f, Nvg.alpha(0xFF60A5FA, 0.9f));
        Nvg.beginPath();
        Nvg.moveTo(cx - 2.8f, cy + 0.2f);
        Nvg.lineTo(cx - 0.8f, cy + 2.2f);
        Nvg.lineTo(cx + 3f, cy - 2f);
        Nvg.strokePath(1.5f, 0xFFFFFFFF);
    }

    private static boolean mentions(Chat.Msg m) {
        if (m.mine || m.system) return false;
        String real = Chat.realName();
        if (real.isEmpty()) return false;
        String low = m.text.toLowerCase(Locale.ROOT);
        return low.contains("@" + real.toLowerCase(Locale.ROOT)) || low.contains("@" + Chat.alias(real).toLowerCase(Locale.ROOT));
    }

    private static String hm(long ts) {
        java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault());
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    private static String time(long ts, long now) {
        java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault());
        java.time.LocalDate today = java.time.LocalDate.now();
        String hm = String.format("%02d:%02d", t.getHour(), t.getMinute());
        if (t.toLocalDate().equals(today)) return "Today at " + hm;
        if (t.toLocalDate().equals(today.minusDays(1))) return "Yesterday at " + hm;
        return t.getMonthValue() + "/" + t.getDayOfMonth() + "/" + (t.getYear() % 100) + " " + hm;
    }

    /* ------------------------------------------------------------------ avatars */

    /**
     * Key for the avatar: real uuid when there is one, otherwise the lowercase name (cracked/offline accounts send a
     * zero uuid but the name often still resolves to a skin). Anonymous senders have no key (neutral avatar).
     */
    private static String headKey(Chat.Msg m) {
        String name = Chat.display(m);
        if (name.equals(Chat.ANON_NAME) || (m.mine && Chat.hideName())) return null;
        String u = m.uuid == null ? "" : m.uuid.replace("-", "").toLowerCase(Locale.ROOT);
        if (u.length() == 32 && !u.chars().allMatch(c -> c == '0')) return u;
        String n = m.user == null ? "" : m.user;
        return n.matches("[A-Za-z0-9_]{3,16}") ? "n:" + n.toLowerCase(Locale.ROOT) : null;
    }

    private static void head(Chat.Msg m, float x, float y, float s) {
        String name = Chat.display(m);
        String key = headKey(m);
        // Name Protect hides the real skin of my own messages too
        if (m.mine && key != null && tenacityProtect()) key = null;
        float r = s / 2f, cx = x + r, cy = y + r;
        if (key != null) {
            Head hd = HEADS.computeIfAbsent(key, k -> new Head());
            if (hd.img <= 0) {
                byte[] b = hd.bytes;
                if (b != null && b.length > 32) {
                    hd.img = Nvg.imageMem(b);
                    hd.bytes = null;
                    if (hd.img <= 0) fail(hd);
                } else if (b != null) {
                    fail(hd);
                } else if (!hd.loading && System.currentTimeMillis() >= hd.retryAt) {
                    fetchHead(key, hd);
                }
            }
            if (hd.img > 0) {
                hd.fade = Anim.approach(hd.fade, 1f, 9f);
                drawFallback(name, cx, cy, r, 1f - hd.fade);
                Nvg.save();
                Nvg.fade(hd.fade);
                Nvg.image(hd.img, x, y, s, s, r, 1f);
                Nvg.restore();
                Nvg.circleStroke(cx, cy, r - 0.5f, 1f, Nvg.alpha(Theme.text(), 0.14f));
                return;
            }
        }
        drawFallback(name, cx, cy, r, 1f);
    }

    /** Colored circle with the first letter; neutral grey silhouette for anonymous. */
    private static void drawFallback(String name, float cx, float cy, float r, float alpha) {
        if (alpha <= 0.01f) return;
        Nvg.save();
        Nvg.fade(alpha);
        if (name.equals(Chat.ANON_NAME)) {
            Nvg.circle(cx, cy, r, Nvg.alpha(Theme.text(), 0.16f));
            Nvg.circle(cx, cy - r * 0.18f, r * 0.30f, Nvg.alpha(Theme.text(), 0.55f));
            Nvg.save();
            Nvg.clip(cx - r, cy - r, r * 2, r * 2);
            Nvg.rrect(cx - r * 0.58f, cy + r * 0.22f, r * 1.16f, r * 1.0f, r * 0.5f, Nvg.alpha(Theme.text(), 0.55f));
            Nvg.restore();
        } else {
            int hue = java.awt.Color.HSBtoRGB((name.toLowerCase(Locale.ROOT).hashCode() & 0xFFFF) / 65535f, 0.42f, 0.74f) | 0xFF000000;
            Nvg.rrectGradient(cx - r, cy - r, r * 2, r * 2, r, cx - r, cy - r, cx + r, cy + r, hue, Nvg.mix(hue, 0xFF000000, 0.45f));
            Nvg.text(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), cx, cy + 1, r * 0.95f, Nvg.SEMI, Nvg.CENTER, 0xFFFFFFFF);
        }
        Nvg.restore();
    }

    private static void fail(Head hd) {
        hd.bytes = null;
        hd.fails++;
        // back off: 4s, 8s, 16s ... capped at 2 minutes, then keep retrying
        hd.retryAt = System.currentTimeMillis() + Math.min(120_000L, 2000L << Math.min(6, hd.fails));
    }

    private static boolean tenacityProtect() {
        var p = tenacity.modules.NameProtect.I;
        return p != null && p.enabled();
    }

    private static void fetchHead(String key, Head hd) {
        // cap concurrent downloads so a long history does not stampede the skin services
        if (HEAD_THREADS.get() >= 4) return;
        hd.loading = true;
        HEAD_THREADS.incrementAndGet();
        Thread t = new Thread(() -> {
            byte[] out = new byte[0];
            try {
                String id = key.startsWith("n:") ? key.substring(2) : key;
                String[] urls = {
                        "https://mc-heads.net/avatar/" + id + "/64",
                        "https://minotar.net/helm/" + id + "/64.png",
                        "https://crafatar.com/avatars/" + id + "?size=64&overlay",
                };
                for (String url : urls) {
                    try {
                        HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).header("user-agent", "Tenacity").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                        byte[] b = r.body();
                        // only accept real PNG data
                        if (r.statusCode() == 200 && b.length > 64 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
                            out = b;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } finally {
                hd.bytes = out;
                hd.loading = false;
                HEAD_THREADS.decrementAndGet();
            }
        }, "Tenacity-ChatHead");
        t.setDaemon(true);
        t.start();
    }

    private static void releaseHeads() {
        for (Head h : HEADS.values()) {
            if (h.img > 0) Nvg.deleteImage(h.img);
            h.img = 0;
            h.fade = 0;
        }
    }

    /* ------------------------------------------------------------------ bits */

    private static void spinner(float cx, float cy, float r, float w) {
        float t = (System.nanoTime() % 100_000_000_000L) / 1e9f * 5.2f;
        Nvg.circleStroke(cx, cy, r, w, Nvg.alpha(Theme.text(), 0.12f));
        Nvg.beginPath();
        float sweep = 1.2f + 0.9f * (float) Math.sin(t * 0.6f);
        Nvg.arcPath(cx, cy, r, t, t + sweep);
        Nvg.strokePath(w, Nvg.alpha(Theme.text(), 0.95f));
    }

    /** Chat bubble icon on the 24 unit grid (also used by the click gui button). */
    public static void drawBubble(float x, float y, float size, int c) {
        Nvg.save();
        Nvg.translate(x, y);
        Nvg.scale(size / 24f, size / 24f);
        Nvg.beginPath();
        Nvg.moveTo(7, 4.5f);
        Nvg.lineTo(17, 4.5f);
        Nvg.quadTo(21, 4.5f, 21, 8.5f);
        Nvg.lineTo(21, 12.5f);
        Nvg.quadTo(21, 16.5f, 17, 16.5f);
        Nvg.lineTo(11.5f, 16.5f);
        Nvg.lineTo(7, 20.2f);
        Nvg.lineTo(7, 16.5f);
        Nvg.quadTo(3, 16.5f, 3, 12.5f);
        Nvg.lineTo(3, 8.5f);
        Nvg.quadTo(3, 4.5f, 7, 4.5f);
        Nvg.closePath();
        Nvg.strokePath(1.9f, c);
        Nvg.circle(8.2f, 10.5f, 1.1f, c);
        Nvg.circle(12, 10.5f, 1.1f, c);
        Nvg.circle(15.8f, 10.5f, 1.1f, c);
        Nvg.restore();
    }
}

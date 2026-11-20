package tenacity.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import org.lwjgl.glfw.GLFW;
import tenacity.gfx.Nvg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Multiplayer: glass server cards (favicon, coloured MOTD, players, ping), a type-to-filter search, a floating glass dock
 * with every action, modal sheets for add / edit / direct connect, drag scrolling with momentum and a connecting transition.
 */
public final class ServersScreen extends NvgScreen {
    private static final ExecutorService PING_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "Tenacity-Ping");
        t.setDaemon(true);
        return t;
    });
    private static final float CARD_H = 84, GAP = 10;

    private final Screen parent;
    private ServerList list;
    private final MultiplayerServerListPinger pinger = new MultiplayerServerListPinger();
    private final Map<ServerInfo, Object[]> icons = new HashMap<>();
    private final Map<ServerInfo, Float> appear = new HashMap<>();
    private final Scroller scroller = new Scroller();
    private final Field search = new Field("srv-search");
    private final Field fName = new Field("srv-name"), fAddr = new Field("srv-addr");
    private int selected = -1;
    private long lastTap, openedAt, deleteArm, refreshAt;
    private int lastTapIndex = -1;

    /** 0 = none, 1 = add, 2 = edit, 3 = direct connect. */
    private int modal, modalShown;
    private float modalK;
    private ServerInfo editing;

    private ServerInfo joining;
    private long joinAt;
    private float joinK;

    public ServersScreen(Screen parent) {
        super("Multiplayer");
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (openedAt == 0) openedAt = System.nanoTime();
        joining = null;
        joinK = 0;
        list = new ServerList(client);
        list.loadFile();
        refresh();
    }

    /* ------------------------------------------------------------------ data */

    private void refresh() {
        refreshAt = System.nanoTime();
        for (int i = 0; i < list.size(); i++) ping(list.get(i));
    }

    /** Pinging resolves DNS and opens sockets, which blocks; never do it on the render thread. */
    private void ping(ServerInfo s) {
        s.ping = -2;
        s.label = Text.empty();
        s.playerCountLabel = Text.empty();
        s.setStatus(ServerInfo.Status.PINGING);
        boolean nativeT = client.options.shouldUseNativeTransport();
        ServerList owner = list;
        MinecraftClient mc = client;
        PING_POOL.submit(() -> {
            try {
                pinger.add(s, () -> mc.execute(owner::saveFile), () -> { }, net.minecraft.network.NetworkingBackend.remote(nativeT));
            } catch (Exception ex) {
                s.ping = -1;
                s.setStatus(ServerInfo.Status.UNREACHABLE);
                s.label = Text.literal("Can't resolve hostname");
            }
        });
    }

    private void join(ServerInfo s) {
        if (joining != null) return;
        joining = s;
        joinAt = System.nanoTime();
    }

    private void connectNow(ServerInfo s) {
        ConnectScreen.connect(this, client, ServerAddress.parse(s.address), s, false, null);
    }

    @Override
    public void removed() {
        pinger.cancel();
        for (Object[] c : icons.values()) Nvg.deleteImage((Integer) c[1]);
        icons.clear();
    }

    private ServerInfo sel() {
        return selected >= 0 && selected < list.size() ? list.get(selected) : null;
    }

    private void openModal(int m) {
        modal = m;
        modalShown = m;
        search.focus = false;
        fName.focus = m != 3;
        fAddr.focus = m == 3;
        if (m == 1) {
            fName.text = "";
            fAddr.text = "";
            editing = null;
        } else if (m == 2) {
            editing = sel();
            if (editing == null) {
                modal = 0;
                return;
            }
            fName.text = editing.name;
            fAddr.text = editing.address;
        } else if (m == 3) fAddr.text = client.options.lastServer == null ? "" : client.options.lastServer;
    }

    private void submit() {
        String addr = fAddr.text.trim();
        if (!ServerAddress.isValid(addr)) return;
        if (modal == 3) {
            client.options.lastServer = addr;
            client.options.write();
            modal = 0;
            join(new ServerInfo(fName.text.isBlank() ? "Direct connection" : fName.text, addr, ServerInfo.ServerType.OTHER));
            return;
        }
        String name = fName.text.isBlank() ? addr : fName.text.trim();
        if (modal == 2 && editing != null) {
            editing.name = name;
            boolean changed = !editing.address.equals(addr);
            editing.address = addr;
            list.saveFile();
            if (changed) {
                editing.setFavicon(null);
                ping(editing);
            }
        } else {
            ServerInfo s = new ServerInfo(name, addr, ServerInfo.ServerType.OTHER);
            list.add(s, false);
            list.saveFile();
            selected = list.size() - 1;
            ping(s);
        }
        modal = 0;
    }

    private void delete() {
        ServerInfo s = sel();
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (now - deleteArm > 2500) {
            deleteArm = now;
            return;
        }
        deleteArm = 0;
        list.remove(s);
        list.saveFile();
        appear.remove(s);
        selected = Math.min(selected, list.size() - 1);
    }

    private String motdPlain(ServerInfo s) {
        return s.label == null ? "" : s.label.getString();
    }

    private List<Integer> filtered() {
        List<Integer> out = new ArrayList<>();
        String q = search.text.trim().toLowerCase();
        for (int i = 0; i < list.size(); i++) {
            ServerInfo s = list.get(i);
            if (q.isEmpty() || s.name.toLowerCase().contains(q) || s.address.toLowerCase().contains(q) || motdPlain(s).toLowerCase().contains(q)) out.add(i);
        }
        return out;
    }

    /* ------------------------------------------------------------------ frame */

    @Override
    public void draw(float dt) {
        MinecraftClient mc = client;
        pinger.tick();
        float t = (System.nanoTime() - openedAt) / 1e9f;
        open = clamp01(t / 0.9f);
        float e = stage(t, 0f, 0.7f);
        float W = Nvg.w, H = Nvg.h;
        float pad = Math.max(32, W * 0.045f);
        List<Integer> rows = filtered();

        // ---------------- keyboard
        boolean modalOpen = modal != 0;
        if (modalOpen) {
            Field f = fName.focus ? fName : fAddr;
            boolean enter = f.input();
            for (int k : Widgets.keys) {
                if (k == GLFW.GLFW_KEY_ESCAPE) modal = 0;
                else if (k == GLFW.GLFW_KEY_TAB && modal != 3) {
                    boolean n = fName.focus;
                    fName.focus = !n;
                    fAddr.focus = n;
                }
            }
            if (enter) submit();
            Widgets.keys.clear();
            Widgets.typed.setLength(0);
        } else if (joining == null) {
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
                else if ((k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) && sel() != null) join(sel());
                else if (k == GLFW.GLFW_KEY_DELETE && sel() != null) delete();
                else if (k == GLFW.GLFW_KEY_F5) refresh();
                else if (k == GLFW.GLFW_KEY_F && ctrl()) search.focus = true;
            }
            // type anywhere to filter
            search.focus = true;
            search.input();
        }

        // ---------------- header + search
        int online = 0;
        for (int i = 0; i < list.size(); i++) if (list.get(i).ping >= 0) online++;
        String sub = list.size() == 0 ? "No saved servers" : list.size() + (list.size() == 1 ? " server" : " servers") + "   \u00B7   " + online + " online";
        if (header("Multiplayer", sub, pad, e) && !modalOpen) mc.setScreen(parent);
        float sw = Math.min(300, W * 0.3f), sx = W - pad - sw + (1 - e) * 16, sy = 34 - (1 - e) * 12;
        Widgets.glass(sx, sy, sw, 40, 20, e);
        Nvg.save();
        Nvg.fade(e);
        boolean was = search.focus;
        search.focus = !modalOpen && joining == null;
        search.draw(sx, sy, sw, 40, "Search servers", "search", 1f);
        search.focus = was;
        if (!search.text.isEmpty() && button("srv-clear", null, "close", sx + sw - 34, sy + 6, 28, 28, 2, true, 1f)) search.text = "";
        Nvg.restore();

        // ---------------- list
        float lw = Math.min(900, W - pad * 2), lx = W / 2f - lw / 2f;
        float ly = 100, dockH = 60, lh = H - ly - dockH - 44;
        float content = rows.size() * (CARD_H + GAP) - GAP + 16;
        scroller.update(lx, ly, lw, lh, content, !modalOpen && joining == null);
        float le = stage(t, 0.12f, 0.8f);

        if (list.size() == 0) emptyState(W / 2f, ly + lh * 0.42f, le, "No servers yet", "Add a server or connect directly with an address.", true);
        else if (rows.isEmpty()) emptyState(W / 2f, ly + lh * 0.42f, le, "No matches", "Nothing matches \"" + search.text + "\".", false);

        Nvg.save();
        Nvg.clip(lx - 20, ly, lw + 40, lh);
        ServerInfo playHit = null;
        int playIdx = -1;
        for (int n = 0; n < rows.size(); n++) {
            int i = rows.get(n);
            ServerInfo s = list.get(i);
            float rk = stage(t, 0.16f + n * 0.05f, 0.75f);
            float ap = appear.getOrDefault(s, 0f);
            ap = Anim.approach(ap, 1f, 7f);
            appear.put(s, ap);
            float ry = ly + 8 + n * (CARD_H + GAP) - scroller.pos + (1 - rk) * 26;
            if (ry + CARD_H < ly - 4 || ry > ly + lh + 4) continue;
            float a = rk * Anim.easeOut(ap) * edgeFade(ry, CARD_H, ly, lh);
            boolean hov = !modalOpen && joining == null && !scroller.dragging() && Widgets.in(lx, ry, lw, CARD_H) && Widgets.in(lx, ly, lw, lh);
            boolean hitPlay = card(s, i, lx, ry, lw, CARD_H, hov, a);
            if (hov && scroller.tap) {
                if (hitPlay) {
                    playHit = s;
                    playIdx = i;
                }
                else {
                    long now = System.currentTimeMillis();
                    if (lastTapIndex == i && selected == i && now - lastTap < 380) join(s);
                    selected = i;
                    lastTap = now;
                    lastTapIndex = i;
                    deleteArm = 0;
                }
            }
        }
        Nvg.restore();
        if (playHit != null) {
            selected = playIdx;
            join(playHit);
        }
        // scrollbar
        float max = Math.max(1, content - lh);
        if (content > lh) {
            float th = Math.max(36, lh * lh / content), tyy = ly + (lh - th) * clamp01(scroller.pos / max);
            float sb = Widgets.anim("srv-sb", Widgets.in(lx + lw - 30, ly, 60, lh) || scroller.dragging() || Math.abs(scroller.vel) > 10 ? 1f : 0.35f, 8f);
            Nvg.rrect(lx + lw + 12, tyy, 3, th, 1.5f, Nvg.alpha(0xFFFFFFFF, 0.35f * sb * le));
        }

        // ---------------- dock
        dock(W, H - dockH - 22, dockH, stage(t, 0.25f, 0.8f), modalOpen);

        // ---------------- modal + connecting
        modalK = Anim.approach(modalK, modal != 0 ? 1f : 0f, modal != 0 ? 11f : 14f);
        if (modalK > 0.003f) {
            Nvg.layer();
            drawModal(W, H, modalK);
        }
        if (joining != null) {
            joinK = Anim.approach(joinK, 1f, 9f);
            Nvg.layer();
            connecting(W, H, joinK);
            if ((System.nanoTime() - joinAt) / 1e9f > 0.55f) {
                ServerInfo s = joining;
                connectNow(s);
            }
        }
        frameEnd();
    }

    private void emptyState(float cx, float cy, float a, String title, String sub, boolean actions) {
        Nvg.save();
        Nvg.fade(a);
        float bob = (float) Math.sin(System.nanoTime() % 100_000_000_000L / 1e9 * 1.4) * 3f;
        Nvg.glowDisc(cx, cy - 30 + bob, 70, Nvg.alpha(0xFFFFFFFF, 0.07f), 0);
        Widgets.glass(cx - 34, cy - 64 + bob, 68, 68, 34, a);
        icon(actions ? "globe" : "search", cx, cy - 30 + bob, 26, Nvg.alpha(0xFFFFFFFF, 0.9f));
        Nvg.text(title, cx, cy + 26, 18, Nvg.SEMI, Nvg.CENTER, 0xFFFFFFFF);
        Nvg.text(sub, cx, cy + 50, 12.5f, Nvg.REG, Nvg.CENTER, Nvg.alpha(0xFFFFFFFF, 0.5f));
        Nvg.restore();
        if (actions && modal == 0) {
            if (button("srv-e-add", "Add Server", "plus", cx - 158, cy + 76, 150, 42, 1, true, a)) openModal(1);
            if (button("srv-e-dir", "Direct Connect", "direct", cx + 8, cy + 76, 150, 42, 0, true, a)) openModal(3);
        }
    }

    /** One server card. Returns true when the pointer is over its play button. */
    private boolean card(ServerInfo s, int i, float x, float y, float w, float h, boolean hov, float a) {
        if (a <= 0.003f) return false;
        String key = "srv-c" + System.identityHashCode(s);
        float hv = Widgets.anim(key, hov ? 1f : 0f, 14f);
        float sl = Widgets.anim(key + "s", i == selected ? 1f : 0f, 12f);
        float jn = joining == s ? joinK : 0f;
        float lift = hv * 2f;
        float sc = 1f + 0.008f * hv + 0.01f * jn;
        float cw = w * sc, ch = h * sc, cx = x + w / 2f - cw / 2f, cy = y + h / 2f - ch / 2f - lift;
        float r = 20;
        Widgets.glass(cx, cy, cw, ch, r, a);
        Nvg.save();
        Nvg.fade(a);
        Nvg.rrect(cx, cy, cw, ch, r, Nvg.alpha(0xFFFFFFFF, 0.03f * hv + 0.045f * sl));
        if (hv > 0.01f)
            rrectRadial(cx, cy, cw, ch, r, Widgets.mx, Widgets.my, cw * 0.4f, Nvg.alpha(0xFFFFFFFF, 0.08f * hv), 0);
        if (sl > 0.01f) {
            Nvg.rrectStrokeGradient(cx, cy, cw, ch, r, 1.3f, cx, cy, cx + cw * 0.6f, cy + ch, Nvg.alpha(0xFFFFFFFF, 0.85f * sl), Nvg.alpha(0xFFFFFFFF, 0.2f * sl));
            Nvg.rrect(cx - 1, cy + ch / 2f - 14 * sl, 3, 28 * sl, 1.5f, Nvg.alpha(0xFFFFFFFF, sl));
        }

        // favicon
        float is = 56, ix = cx + 14, iy = cy + ch / 2f - is / 2f;
        int img = favicon(s);
        if (img > 0) {
            Nvg.rrect(ix, iy + 3, is, is, 13, Nvg.alpha(0xFF000000, 0.35f));
            Nvg.imageRegion(img, 64, 64, 0, 0, 64, 64, ix, iy, is, is, 13, 1f);
            Nvg.rrectStroke(ix, iy, is, is, 13, 1f, Nvg.alpha(0xFFFFFFFF, 0.12f));
        } else {
            Nvg.rrectGradient(ix, iy, is, is, 13, ix, iy, ix + is, iy + is, Nvg.alpha(0xFFFFFFFF, 0.16f), Nvg.alpha(0xFFFFFFFF, 0.04f));
            Nvg.rrectStroke(ix, iy, is, is, 13, 1f, Nvg.alpha(0xFFFFFFFF, 0.14f));
            Nvg.text(s.name.isEmpty() ? "?" : s.name.substring(0, 1).toUpperCase(), ix + is / 2f, iy + is / 2f + 1, 22, Nvg.SEMI, Nvg.CENTER, 0xFFFFFFFF);
        }

        // right column: ping + players
        float rx = cx + cw - 20;
        float playW = 40 * hv;
        rx -= playW;
        boolean pinging = s.ping == -2 || s.getStatus() == ServerInfo.Status.PINGING || s.getStatus() == ServerInfo.Status.INITIAL;
        boolean offline = !pinging && s.ping < 0;
        boolean incompatible = s.getStatus() == ServerInfo.Status.INCOMPATIBLE;
        bars(rx - 18, cy + 21, s.ping, pinging, offline);
        String ps = pinging ? "Pinging" : offline ? "Offline" : s.ping + " ms";
        Nvg.text(ps, rx - 26, cy + 27, 11.5f, Nvg.MED, Nvg.RIGHT, Nvg.alpha(0xFFFFFFFF, offline ? 0.4f : 0.7f));
        float textRight = rx - 26 - Nvg.textWidth(ps, 11.5f, Nvg.MED);
        if (!pinging && !offline) {
            String pc = s.players != null ? s.players.online() + " / " + s.players.max() : (s.playerCountLabel == null ? "" : s.playerCountLabel.getString());
            float pw = 74;
            Nvg.text(pc, rx, cy + 50, 12.5f, Nvg.SEMI, Nvg.RIGHT, 0xFFFFFFFF);
            float pcw = Nvg.textWidth(pc, 12.5f, Nvg.SEMI);
            icon("people", rx - pcw - 12, cy + 50, 13, Nvg.alpha(0xFFFFFFFF, 0.55f));
            float fill = s.players != null && s.players.max() > 0 ? clamp01(s.players.online() / (float) s.players.max()) : 0f;
            Nvg.rrect(rx - pw, cy + 62, pw, 3, 1.5f, Nvg.alpha(0xFFFFFFFF, 0.12f));
            Nvg.rrect(rx - pw, cy + 62, Math.max(3, pw * fill), 3, 1.5f, Nvg.alpha(0xFFFFFFFF, 0.75f));
            textRight = Math.min(textRight, rx - Math.max(pw, pcw + 22));
            if (incompatible) {
                String v = s.version == null ? "Incompatible" : s.version.getString();
                Nvg.text(Nvg.fit(v, 10.5f, Nvg.MED, 120), rx - pcw - 26, cy + 50, 10.5f, Nvg.MED, Nvg.RIGHT, Nvg.alpha(0xFFFFFFFF, 0.45f));
            }
        } else if (pinging) {
            float sh = (float) ((System.nanoTime() % 1_400_000_000L) / 1.4e9);
            Nvg.rrect(rx - 74, cy + 50, 74, 6, 3, Nvg.alpha(0xFFFFFFFF, 0.06f));
            Nvg.save();
            Nvg.clip(rx - 74, cy + 50, 74, 6);
            Nvg.rrectGradient(rx - 74 - 40 + 114 * sh, cy + 50, 40, 6, 3, rx - 74 - 40 + 114 * sh, 0, rx - 74 + 114 * sh, 0, Nvg.alpha(0xFFFFFFFF, 0f), Nvg.alpha(0xFFFFFFFF, 0.35f));
            Nvg.restore();
        }

        // name, motd, address
        float tx = ix + is + 16, maxW = textRight - tx - 16;
        Nvg.text(Nvg.fit(s.name, 15.5f, Nvg.SEMI, maxW), tx, cy + 22, 15.5f, Nvg.SEMI, Nvg.LEFT, 0xFFFFFFFF);
        if (pinging) Nvg.text("Contacting server...", tx, cy + 43, 12, Nvg.REG, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.35f));
        else motd(s.label, tx, cy + 43, maxW, offline);
        Nvg.text(Nvg.fit(s.address, 10.5f, Nvg.REG, maxW), tx, cy + ch - 15, 10.5f, Nvg.MED, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.28f));

        // play button slides in on hover
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

    /** MOTD with its own colours (lightly softened so it sits on the black glass), up to two lines. */
    private void motd(Text label, float x, float y, float maxW, boolean offline) {
        if (label == null) return;
        if (offline) {
            Nvg.text(Nvg.fit(label.getString().replace('\n', ' '), 12, Nvg.REG, maxW), x, y, 12, Nvg.REG, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.4f));
            return;
        }
        List<List<Object[]>> lines = new ArrayList<>();
        lines.add(new ArrayList<>());
        label.visit((Style style, String str) -> {
            String[] parts = str.split("\n", -1);
            for (int p = 0; p < parts.length; p++) {
                if (p > 0) lines.add(new ArrayList<>());
                if (!parts[p].isEmpty()) lines.get(lines.size() - 1).add(new Object[]{parts[p], style});
            }
            return Optional.empty();
        }, Style.EMPTY);
        float fs = 11.5f, lh = 14;
        boolean single = lines.size() < 2 || lines.get(1).isEmpty();
        float yy = single ? y : y - 3;
        for (int l = 0; l < Math.min(2, lines.size()); l++) {
            float cx = x;
            for (Object[] seg : lines.get(l)) {
                String str = (String) seg[0];
                Style st = (Style) seg[1];
                if (l == 0 && cx == x) str = str.stripLeading();
                int font = st.isBold() ? Nvg.SEMI : Nvg.REG;
                TextColor tc = st.getColor();
                int rgb = tc == null ? 0xC8CBD2 : tc.getRgb();
                int c = Nvg.mix(0xFF000000 | rgb, 0xFFFFFFFF, 0.15f);
                float left = x + maxW - cx;
                if (left <= 8) break;
                String fitted = Nvg.textWidth(str, fs, font) > left ? Nvg.fit(str, fs, font, left) : str;
                Nvg.text(fitted, cx, yy, fs, font, Nvg.LEFT, Nvg.alpha(c, 0.85f));
                cx += Nvg.textWidth(fitted, fs, font);
            }
            yy += lh;
            if (single) break;
        }
    }

    private static void bars(float x, float y, long ping, boolean pinging, boolean offline) {
        int n = ping <= 0 ? 0 : ping < 80 ? 4 : ping < 150 ? 3 : ping < 300 ? 2 : 1;
        double ph = (System.nanoTime() % 1_000_000_000_000L) / 1e9;
        for (int i = 0; i < 4; i++) {
            float h = 4 + i * 3;
            float a = i < n ? 0.95f : 0.16f;
            if (pinging) a = 0.16f + 0.6f * (float) Math.max(0, Math.sin(ph * 5 - i * 0.8));
            Nvg.rrect(x + i * 4.5f, y + 12 - h, 3, h, 1.2f, Nvg.alpha(0xFFFFFFFF, a));
        }
        if (offline) {
            Nvg.line(x + 11, y - 2, x + 16, y + 3, 1.4f, Nvg.alpha(0xFFFFFFFF, 0.7f));
            Nvg.line(x + 16, y - 2, x + 11, y + 3, 1.4f, Nvg.alpha(0xFFFFFFFF, 0.7f));
        }
    }

    /** Floating glass dock with all actions. */
    private void dock(float W, float y, float h, float a, boolean modalOpen) {
        ServerInfo s = sel();
        boolean can = s != null && !modalOpen && joining == null;
        boolean armed = System.currentTimeMillis() - deleteArm < 2500 && s != null;
        String[] labels = {"Join", "Direct", "Add", "Edit", armed ? "Confirm" : "Delete", "Refresh"};
        String[] icns = {"play", "direct", "plus", "edit", "trash", "refresh"};
        float[] ws = new float[labels.length];
        float total = 0;
        for (int i = 0; i < labels.length; i++) {
            ws[i] = Nvg.textWidth(labels[i], 13.5f, Nvg.MED) + 13.5f * 1.25f + 8 + (i == 0 ? 44 : 30);
            total += ws[i];
        }
        float gap = 4, inner = 8;
        total += gap * (labels.length - 1) + inner * 2 + 10;
        float x = W / 2f - total / 2f, yy = y + (1 - a) * 24;
        Widgets.glass(x, yy, total, h, h / 2f, a);
        float bx = x + inner, bh = h - inner * 2;
        for (int i = 0; i < labels.length; i++) {
            boolean en = !modalOpen && joining == null && switch (i) {
                case 0, 3, 4 -> can;
                default -> true;
            };
            int style = i == 0 ? 1 : (i == 4 && armed ? 1 : 2);
            if (button("srv-d" + i, labels[i], icns[i], bx, yy + inner, ws[i], bh, style, en, a)) {
                switch (i) {
                    case 0 -> join(s);
                    case 1 -> openModal(3);
                    case 2 -> openModal(1);
                    case 3 -> openModal(2);
                    case 4 -> delete();
                    default -> {
                        list.loadFile();
                        selected = Math.min(selected, list.size() - 1);
                        refresh();
                    }
                }
            }
            bx += ws[i] + gap;
            if (i == 0) {
                Nvg.line(bx + 3, yy + 16, bx + 3, yy + h - 16, 1f, Nvg.alpha(0xFFFFFFFF, 0.12f * a));
                bx += 10;
            }
        }
    }

    private void drawModal(float W, float H, float k) {
        float e = Anim.easeOut(k);
        Nvg.rect(0, 0, W, H, Nvg.alpha(0xFF000000, 0.5f * e));
        int m = modal != 0 ? modal : modalShown;
        boolean direct = m == 3;
        float mw = Math.min(460, W - 60), mh = direct ? 236 : 304;
        float s = 0.94f + 0.06f * e;
        float mx = W / 2f - mw * s / 2f, my = H / 2f - mh * s / 2f + (1 - e) * 20;
        Widgets.glass(mx, my, mw * s, mh * s, 26, e);
        Nvg.save();
        Nvg.translate(mx, my);
        Nvg.scale(s, s);
        Nvg.fade(e);
        // the modal's own coordinate space starts at 0,0; widgets need screen coordinates so offset the pointer
        float omx = Widgets.mx, omy = Widgets.my;
        Widgets.mx = (omx - mx) / s;
        Widgets.my = (omy - my) / s;
        String title = m == 1 ? "Add server" : m == 2 ? "Edit server" : "Direct connect";
        String sub = m == 1 ? "Save a server to your list." : m == 2 ? "Change the name or address." : "Join a server without saving it.";
        Nvg.circle(46, 48, 20, Nvg.alpha(0xFFFFFFFF, 0.1f));
        icon(m == 1 ? "plus" : m == 2 ? "edit" : "direct", 46, 48, 18, 0xFFFFFFFF);
        Nvg.text(title, 78, 40, 18, Nvg.SEMI, Nvg.LEFT, 0xFFFFFFFF);
        Nvg.text(sub, 78, 60, 12, Nvg.REG, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.5f));
        float fy = 96;
        if (!direct) {
            Nvg.text("NAME", 28, fy, 9.5f, Nvg.SEMI, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.45f));
            if (fName.draw(26, fy + 10, mw - 52, 42, "Minecraft Server", null, 1f)) fAddr.focus = false;
            fy += 70;
        }
        Nvg.text("ADDRESS", 28, fy, 9.5f, Nvg.SEMI, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.45f));
        if (fAddr.draw(26, fy + 10, mw - 52, 42, "play.example.net", "globe", 1f)) fName.focus = false;
        boolean valid = ServerAddress.isValid(fAddr.text.trim());
        if (!fAddr.text.isBlank() && !valid) Nvg.text("That address doesn't look right", mw - 28, fy, 10.5f, Nvg.MED, Nvg.RIGHT, Nvg.alpha(0xFFFFFFFF, 0.6f));
        float by = mh - 66;
        boolean act = modal != 0;
        if (button("srv-m-cancel", "Cancel", null, mw - 26 - 252, by, 120, 42, 2, act, 1f)) modal = 0;
        if (button("srv-m-ok", m == 3 ? "Connect" : m == 2 ? "Save" : "Add Server", m == 3 ? "arrow" : null, mw - 26 - 124, by, 124, 42, 1, act && valid, 1f)) submit();
        Widgets.mx = omx;
        Widgets.my = omy;
        Nvg.restore();
        if (act && Widgets.click && !Widgets.in(mx, my, mw * s, mh * s)) modal = 0;
        Widgets.click = false;
    }

    private void connecting(float W, float H, float k) {
        float e = Anim.easeOut(k);
        Nvg.rect(0, 0, W, H, Nvg.alpha(0xFF000000, 0.6f * e));
        float cx = W / 2f, cy = H / 2f - 10 + (1 - e) * 14;
        Nvg.save();
        Nvg.fade(e);
        Nvg.glowDisc(cx, cy - 20, 90, Nvg.alpha(0xFFFFFFFF, 0.08f), 0);
        int img = joining == null ? 0 : favicon(joining);
        if (img > 0) Nvg.imageRegion(img, 64, 64, 0, 0, 64, 64, cx - 22, cy - 42, 44, 44, 11, 1f);
        else icon("globe", cx, cy - 20, 26, 0xFFFFFFFF);
        spinner(cx, cy - 20, 38, 2f, 1f);
        Nvg.text("Connecting", cx, cy + 42, 18, Nvg.SEMI, Nvg.CENTER, 0xFFFFFFFF);
        if (joining != null) Nvg.text(joining.name + "  \u00B7  " + joining.address, cx, cy + 64, 12, Nvg.REG, Nvg.CENTER, Nvg.alpha(0xFFFFFFFF, 0.5f));
        Nvg.restore();
    }

    /** Server favicon as a NanoVG image, re-decoded when the server sends a new one. */
    private int favicon(ServerInfo s) {
        byte[] fav = s.getFavicon();
        Object[] c = icons.get(s);
        if (c != null && c[0] == fav) return (Integer) c[1];
        if (c != null) Nvg.deleteImage((Integer) c[1]);
        int img = fav == null ? 0 : Nvg.imageMem(fav);
        icons.put(s, new Object[]{fav, img});
        return img;
    }
}

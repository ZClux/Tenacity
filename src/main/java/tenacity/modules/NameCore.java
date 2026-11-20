package tenacity.modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared text rewriting core for Name Protect and Name Scrambler.
 *
 * <p>Everything funnels through {@link #string(String)} / {@link #ordered(OrderedText)}: the mixins on
 * TextRenderer (Minecraft text) and Nvg (the client's own UI) call these before layout, so widths and glyphs
 * always agree. Strings that cannot contain a candidate name are rejected with a cheap scan, results are cached
 * per input string and the whole cache is dropped whenever the configuration or the animation frame changes.
 */
public final class NameCore {
    private NameCore() {
    }

    /** Set while we are building replacement text so the hooks never recurse. */
    static boolean busy;

    /* ------------------------------------------------------------------ candidates */

    /** One name that may be rewritten, with the action to apply to it. */
    record Target(String name, String lower, boolean self, boolean friend) {
    }

    /** Extra literal replacements (own UUID in F3, server address). */
    private static String[] litFrom = new String[0];
    private static String[] litTo = new String[0];

    private static Target[] targets = new Target[0];
    private static String[] lowerKeys = new String[0];
    private static long targetsAt;
    private static String targetsSig = "";
    private static int configRev;
    private static long frame = -1;
    private static boolean active;

    private static final int CACHE_MAX = 1024;
    private static final Map<String, String> STR = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > CACHE_MAX;
        }
    };
    private static final Map<String, Boolean> MISS = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > CACHE_MAX * 2;
        }
    };
    /** Filled by the server list mixin so the multiplayer screen can be censored too. */
    public static final List<net.minecraft.client.network.ServerInfo> SERVER_LIST = new ArrayList<>();
    private static final Map<String, Integer> PLAYER_NUMBERS = new HashMap<>();

    private static NameProtect protect() {
        return NameProtect.I;
    }

    private static NameScrambler scrambler() {
        return NameScrambler.I;
    }

    /** True when at least one of the two modules wants to rewrite text. */
    public static boolean anyActive() {
        NameProtect p = NameProtect.I;
        NameScrambler s = NameScrambler.I;
        return (p != null && p.enabled()) || (s != null && s.enabled());
    }

    /** Called when any setting that changes output is edited. */
    public static void invalidate() {
        configRev++;
    }

    private static String lastAimed = "";
    /** True while a chat line is being laid out (Chat Only scrambler scope). */
    public static boolean inChat;
    private static int lastRev = -1;
    private static int lastSettingsHash;

    /** Refreshes candidate names and drops caches when something relevant changed. */
    private static void refresh() {
        MinecraftClient mc = MinecraftClient.getInstance();
        NameProtect p = protect();
        NameScrambler s = scrambler();
        boolean pOn = p != null && p.enabled();
        boolean sOn = s != null && s.enabled();
        active = pOn || sOn;
        if (!active) {
            if (!STR.isEmpty()) {
                STR.clear();
                MISS.clear();
            }
            return;
        }
        long now = System.currentTimeMillis();
        int hash = (pOn ? p.hash() : 0) * 31 + (sOn ? s.hash() : 0);
        long fr = sOn ? now / s.periodMs() : 0;
        boolean dirty = false;
        if (hash != lastSettingsHash || configRev != lastRev) {
            lastSettingsHash = hash;
            lastRev = configRev;
            dirty = true;
        }
        if (fr != frame) {
            frame = fr;
            dirty = true;
        }
        if (sOn && s.reveal.value) {
            net.minecraft.util.hit.HitResult hr = mc.crosshairTarget;
            String aimed = hr instanceof EntityHitResult eh && eh.getEntity() instanceof net.minecraft.entity.player.PlayerEntity pe ? pe.getGameProfile().name() : "";
            if (!aimed.equals(lastAimed)) {
                lastAimed = aimed;
                dirty = true;
            }
        }
        if (now - targetsAt > 500 || dirty) {
            if (now - targetsAt > 500) {
                targetsAt = now;
                rebuildTargets(mc, pOn ? p : null, sOn ? s : null);
            }
        }
        if (dirty) {
            STR.clear();
            MISS.clear();
        }
    }

    private static void rebuildTargets(MinecraftClient mc, NameProtect p, NameScrambler s) {
        List<Target> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String self = mc.getSession() == null ? "" : mc.getSession().getUsername();
        boolean everyoneP = p != null && p.mode.is("Everyone");
        boolean friendsP = p != null && p.mode.is("Friends");
        boolean othersS = s != null && !s.scope.is("Self");
        boolean selfS = s != null && (s.scope.is("Self") || s.scope.is("Everyone") || s.scope.is("Chat Only"));
        boolean everyoneS = s != null && (s.scope.is("Everyone") || s.scope.is("Others") || s.scope.is("Chat Only"));
        if (!self.isEmpty() && (p != null || selfS)) add(list, seen, self, true, false);
        if (mc.getNetworkHandler() != null && (everyoneP || friendsP || everyoneS || othersS)) {
            for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
                String n = e.getProfile().name();
                if (n == null || n.isEmpty() || n.equals(self)) continue;
                boolean friend = friendsP && p.isFriend(n);
                if (everyoneP || friend || everyoneS) add(list, seen, n, false, friend);
            }
        }
        // players that are rendered but not in the tab list (npcs, vanished, test dummies)
        if (mc.world != null && (everyoneP || friendsP || everyoneS || othersS)) {
            for (net.minecraft.entity.player.PlayerEntity pl : mc.world.getPlayers()) {
                String n = pl.getGameProfile().name();
                if (n == null || n.isEmpty() || n.equals(self)) continue;
                boolean friend = friendsP && p.isFriend(n);
                if (everyoneP || friend || everyoneS) add(list, seen, n, false, friend);
            }
        }
        if (p != null && friendsP) for (String f : p.friends()) add(list, seen, f, false, true);
        // longest first so "Notch2" wins over "Notch"
        list.sort((a, b) -> b.name.length() - a.name.length());
        String sig = list.toString();
        if (!sig.equals(targetsSig)) {
            targetsSig = sig;
            STR.clear();
            MISS.clear();
        }
        List<String> from = new ArrayList<>(), to = new ArrayList<>();
        if (p != null && p.debugScreen.on() && mc.getSession() != null && mc.getSession().getUuidOrNull() != null) {
            java.util.UUID u = mc.getSession().getUuidOrNull();
            from.add(u.toString());
            to.add("00000000-0000-0000-0000-000000000000");
            from.add(u.toString().replace("-", ""));
            to.add("00000000000000000000000000000000");
        }
        if (p != null && p.hideIp.on()) {
            if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
                from.add(mc.getCurrentServerEntry().address);
                to.add("hidden.server");
            }
            if (mc.getNetworkHandler() != null && mc.getNetworkHandler().getConnection() != null
                    && mc.getNetworkHandler().getConnection().getAddress() != null) {
                String a = mc.getNetworkHandler().getConnection().getAddress().toString();
                from.add(a);
                to.add("hidden.server");
                int slash = a.indexOf('/');
                if (slash >= 0 && slash + 1 < a.length()) {
                    from.add(a.substring(slash + 1));
                    to.add("hidden");
                }
            }
            for (var si : SERVER_LIST) {
                if (si != null && si.address != null && !si.address.isEmpty()) {
                    from.add(si.address);
                    to.add("hidden.server");
                }
            }
        }
        litFrom = from.toArray(new String[0]);
        litTo = to.toArray(new String[0]);
        targets = list.toArray(new Target[0]);
        lowerKeys = new String[targets.length];
        for (int i = 0; i < targets.length; i++) lowerKeys[i] = targets[i].lower;
    }

    private static void add(List<Target> list, java.util.Set<String> seen, String n, boolean self, boolean friend) {
        if (n.length() < 2 || !seen.add(n.toLowerCase(Locale.ROOT))) return;
        list.add(new Target(n, n.toLowerCase(Locale.ROOT), self, friend));
    }

    /* ------------------------------------------------------------------ public entry points */

    /** Rewrites a legacy string (may contain section formatting codes). */
    public static String string(String s) {
        if (s == null || s.length() < 2 || busy) return s;
        refresh();
        if (!active || (targets.length == 0 && litFrom.length == 0)) return s;
        String key = inChat ? "chat:" + s : s;
        String hit = STR.get(key);
        if (hit != null) return hit;
        if (MISS.containsKey(key)) return s;
        String out = rewrite(s);
        if (out.equals(s)) MISS.put(key, Boolean.TRUE);
        else STR.put(key, out);
        return out;
    }

    /**
     * Rewrites text drawn by the client's own NanoVG UI. NanoVG has no formatting codes, so the obfuscated style is
     * reduced to plain glyphs here.
     */
    public static String nvg(String s) {
        if (!anyActive()) return s;
        String r = string(s);
        if (r != s && r.indexOf('\u00a7') >= 0) r = r.replace("\u00a7k", "").replace("\u00a7r", "");
        return r;
    }

    /** Rewrites an ordered text by flattening it into (codepoint, style) runs. */
    public static OrderedText ordered(OrderedText t) {
        if (t == null || busy) return t;
        refresh();
        if (!active || (targets.length == 0 && litFrom.length == 0)) return t;
        // flatten
        StringBuilder sb = new StringBuilder(48);
        List<Style> styles = new ArrayList<>(48);
        t.accept((i, st, cp) -> {
            sb.appendCodePoint(cp);
            int units = Character.charCount(cp);
            for (int k = 0; k < units; k++) styles.add(st);
            return true;
        });
        String flat = sb.toString();
        if (flat.length() < 2) return t;
        String mkey = inChat ? "chat:" + flat : flat;
        if (MISS.containsKey(mkey)) return t;
        // literal pass (uuid / server address): same-length-agnostic, the style of the first char is reused
        for (int li = 0; li < litFrom.length; li++) {
            int at = flat.indexOf(litFrom[li]);
            while (at >= 0) {
                Style st = styles.get(at);
                String to = litTo[li];
                flat = flat.substring(0, at) + to + flat.substring(at + litFrom[li].length());
                for (int k = 0; k < litFrom[li].length(); k++) styles.remove(at);
                for (int k = 0; k < to.length(); k++) styles.add(at, st);
                at = flat.indexOf(litFrom[li], at + to.length());
            }
        }
        List<int[]> spans = findSpans(flat);
        if (spans.isEmpty() && flat.equals(sb.toString())) {
            MISS.put(mkey, Boolean.TRUE);
            return t;
        }
        // build output
        StringBuilder out = new StringBuilder(flat.length() + 8);
        List<Style> outStyles = new ArrayList<>(flat.length() + 8);
        int pos = 0;
        for (int[] sp : spans) {
            for (int k = pos; k < sp[0]; k++) {
                out.append(flat.charAt(k));
                outStyles.add(styles.get(k));
            }
            String repl = replacement(targets[sp[2]], flat.substring(sp[0], sp[1]));
            Style st = styles.get(sp[0]);
            for (int k = 0; k < repl.length(); k++) {
                out.append(repl.charAt(k));
                outStyles.add(st);
            }
            pos = sp[1];
        }
        for (int k = pos; k < flat.length(); k++) {
            out.append(flat.charAt(k));
            outStyles.add(styles.get(k));
        }
        String result = out.toString();
        List<Style> rs = outStyles;
        return visitor -> {
            for (int i = 0; i < result.length(); ) {
                int cp = result.codePointAt(i);
                if (!visitor.accept(i, rs.get(i), cp)) return false;
                i += Character.charCount(cp);
            }
            return true;
        };
    }

    /** Rewrites a Text through its ordered form. */
    public static OrderedText ordered(net.minecraft.text.Text t) {
        return ordered(t.asOrderedText());
    }

    /* ------------------------------------------------------------------ matching */

    private static String literals(String s) {
        for (int i = 0; i < litFrom.length; i++) if (s.contains(litFrom[i])) s = s.replace(litFrom[i], litTo[i]);
        return s;
    }

    /** The section codes in effect at {@code upTo} (colour plus modifiers since the last reset). */
    private static String activeFormat(String s, int upTo) {
        StringBuilder f = new StringBuilder();
        for (int i = 0; i + 1 < upTo; i++) {
            if (s.charAt(i) != '§') continue;
            char c = Character.toLowerCase(s.charAt(i + 1));
            if (c == 'r') f.setLength(0);
            else if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                f.setLength(0);
                f.append('§').append(c);
            } else if (c == 'k' || c == 'l' || c == 'm' || c == 'n' || c == 'o') f.append('§').append(c);
            i++;
        }
        return f.toString();
    }

    private static String rewrite(String s) {
        s = literals(s);
        List<int[]> spans = findSpans(s);
        if (spans.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length() + 8);
        int pos = 0;
        for (int[] sp : spans) {
            out.append(s, pos, sp[0]);
            String repl = replacement(targets[sp[2]], s.substring(sp[0], sp[1]));
            out.append(repl);
            // a replacement that ends with a reset (obfuscated style) must restore the formatting active before it
            if (repl.endsWith("§r")) out.append(activeFormat(s, sp[0]));
            pos = sp[1];
        }
        out.append(s, pos, s.length());
        return out.toString();
    }

    private static boolean wordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    /** Finds non overlapping {start, end, targetIndex} spans, left to right, longest candidate first. */
    private static List<int[]> findSpans(String s) {
        List<int[]> spans = new ArrayList<>(2);
        int n = s.length();
        NameProtect p = protect();
        boolean ci = p == null || !p.enabled() || p.caseInsensitive.on();
        // the scrambler is always case insensitive
        String hay = ci ? s.toLowerCase(Locale.ROOT) : s;
        if (hay.length() != n) hay = s; // exotic case folding changed the length, fall back
        int i = 0;
        outer:
        while (i < n) {
            char c = hay.charAt(i);
            if (i > 0 && wordChar(s.charAt(i - 1))) {
                i++;
                continue;
            }
            for (int ti = 0; ti < targets.length; ti++) {
                String key = ci ? targets[ti].lower : targets[ti].name;
                int len = key.length();
                if (len > n - i || key.charAt(0) != c) continue;
                if (!hay.startsWith(key, i)) continue;
                int end = i + len;
                if (end < n && wordChar(s.charAt(end))) continue;
                spans.add(new int[]{i, end, ti});
                i = end;
                continue outer;
            }
            i++;
        }
        return spans;
    }

    /* ------------------------------------------------------------------ replacement */

    private static String replacement(Target t, String original) {
        NameProtect p = protect();
        NameScrambler s = scrambler();
        boolean pOn = p != null && p.enabled();
        boolean sOn = s != null && s.enabled();
        // Protect wins for names it covers
        if (pOn && p.covers(t)) return p.replacementFor(t.name, t.self);
        if (sOn && s.covers(t) && (!s.scope.is("Chat Only") || inChat)) return s.scramble(t.name, original);
        return original;
    }

    /** Stable "Player N" number per name. */
    static int playerNumber(String name) {
        String k = name.toLowerCase(Locale.ROOT);
        return PLAYER_NUMBERS.computeIfAbsent(k, x -> PLAYER_NUMBERS.size() + 1);
    }

    /** True while the crosshair is on the player with this name (reveal on aim). */
    static boolean aimedAt(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        HitResult hr = mc.crosshairTarget;
        if (hr instanceof EntityHitResult eh) {
            Entity e = eh.getEntity();
            return e instanceof net.minecraft.entity.player.PlayerEntity pe && pe.getGameProfile().name().equalsIgnoreCase(name);
        }
        return false;
    }

}

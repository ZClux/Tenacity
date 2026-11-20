package tenacity.modules.donut;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import tenacity.gfx.Nvg;
import tenacity.ui.Widgets;
import tenacity.util.Projection;
import voided.util.render.Render3D;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Frosted chunk cards: Tenacity's take on Krypton's ChunkCardRenderer. Every marked chunk gets a soft frost plate on a
 * horizontal plane with a rounded hairline rim, a breathing halo, a travelling comet and ripples. Cards fade and scale in
 * and out with exponential smoothing (nothing snaps), and the 2D pass adds liquid glass labels, tracers and a found list.
 *
 * Usage per frame (renderWorld): {@link #begin()}, {@link #put} every live find, {@link #drawWorld}. Then in render2d:
 * {@link #drawOverlay}. Cards that are no longer put fade out and are dropped.
 */
public final class ChunkCards {
    public static final int FROST = 0xF3F5F8;

    private static final class Card {
        int cx, cz;
        String title = "", detail = "";
        double ax, ay, az;
        long born;
        float a, hover;
        int dg; // diagonal neighbours: 1 NE, 2 SE, 4 SW, 8 NW
        int nb; // neighbouring live cards: 1 north (-z), 2 south (+z), 4 west (-x), 8 east (+x)
        boolean live;
        float phase;
        List<String> reasons = List.of();
        float chance = -1;
        /** Pulse style: 0..1 heat (frost white to amber) and the ping rate. */
        float heat;
        /** Optional extra hover content: sparkline samples (0..1) and labelled bars (label, 0..1, value text). */
        float[] spark;
        List<Bar> bars = List.of();
        String gaugeLabel;
    }

    /** A labelled bar of the hover panel (per-reason breakdown). */
    public record Bar(String label, float fraction, String value) {
    }

    private final Map<Long, Card> cards = new LinkedHashMap<>();
    private long lastNs;
    private float dt = 0.016f;
    private float panelA, panelH, panelRows;
    private double planeY = 64;
    private double limit = 512;

    public void begin() {
        for (Card c : cards.values()) c.live = false;
    }

    /** Marks a chunk this frame. (x, y, z) is the best estimate of the find, used for the list and distance. */
    public void put(long key, int cx, int cz, String title, String detail, double x, double y, double z, long born) {
        Card c = cards.get(key);
        if (c == null) {
            c = new Card();
            c.phase = (float) ((key * 0x9E3779B97F4A7C15L >>> 40) % 1000) / 1000f;
            cards.put(key, c);
        }
        c.cx = cx;
        c.cz = cz;
        c.title = title;
        c.detail = detail == null ? "" : detail;
        c.ax = x;
        c.ay = y;
        c.az = z;
        c.born = born;
        c.live = true;
    }

    /** Why this chunk was flagged and how likely it is a base (0..1), shown when you aim at the card. */
    public void info(long key, List<String> reasons, float chance) {
        Card c = cards.get(key);
        if (c == null) return;
        c.reasons = reasons;
        c.chance = chance;
    }

    /** Pulse cards: heat 0..1, a sparkline of recent activity (0..1 samples), breakdown bars and the gauge label. */
    public void pulse(long key, float heat, float[] spark, List<Bar> bars, String gaugeLabel) {
        Card c = cards.get(key);
        if (c == null) return;
        c.heat = Math.max(0f, Math.min(1f, heat));
        c.spark = spark;
        c.bars = bars == null ? List.of() : bars;
        c.gaugeLabel = gaugeLabel;
    }

    private long hovered = Long.MIN_VALUE;

    public void clear() {
        cards.clear();
        panelA = panelH = panelRows = 0;
    }

    /** Cards by chunk position. The modules key their cards with their own (ChunkPos) packing, so neighbours are looked up
     *  by the cards' real cx / cz - looking them up by key never matched, which is why plates did not join. */
    private final java.util.Map<Long, Card> byPos = new java.util.HashMap<>();

    private void indexCards() {
        byPos.clear();
        for (Card c : cards.values()) byPos.put(((long) c.cx << 32) ^ (c.cz & 0xFFFFFFFFL), c);
    }

    private Card at(int cx, int cz) {
        return byPos.get(((long) cx << 32) ^ (cz & 0xFFFFFFFFL));
    }

    private boolean alive(int cx, int cz) {
        Card o = at(cx, cz);
        return o != null && o.a > 0.35f;
    }

    private static long key(Card c) {
        return ((long) c.cx << 32) ^ (c.cz & 0xFFFFFFFFL);
    }

    /** The card under the crosshair: where your view ray crosses the card plane, or the block you are aiming at. */
    private long pick(MinecraftClient mc, double planeY) {
        var cam = mc.gameRenderer.getCamera();
        Vec3d o = cam.getCameraPos(), d = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
        java.util.List<int[]> chunks = new ArrayList<>(2);
        if (Math.abs(d.y) > 1e-4) {
            double t = (planeY - o.y) / d.y;
            if (t > 0 && t < 2048 && o.y > planeY + 1) {
                Vec3d h = o.add(d.multiply(t));
                chunks.add(new int[]{(int) Math.floor(h.x) >> 4, (int) Math.floor(h.z) >> 4});
            }
        }
        int mx = (int) Math.floor(o.x) >> 4, mz = (int) Math.floor(o.z) >> 4;
        chunks.removeIf(ch -> ch[0] == mx && ch[1] == mz);
        for (int[] ch : chunks)
            for (Card c : cards.values()) if (c.live && c.cx == ch[0] && c.cz == ch[1]) return key(c);
        return Long.MIN_VALUE;
    }

    private static float approach(float cur, float target, float speed, float dt) {
        float v = cur + (target - cur) * (1f - (float) Math.exp(-speed * dt));
        return Math.abs(v - target) < 0.001f ? target : v;
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static int argb(int rgb, float a) {
        int al = Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
        return (al << 24) | (rgb & 0xFFFFFF);
    }

    /* ------------------------------------------------------------------ 3D */

    public void drawWorld(double planeY, double limit, boolean animations, int tint, float opacity) {
        this.planeY = planeY;
        this.limit = limit;
        long now = System.nanoTime();
        dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        long ms = System.currentTimeMillis();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        double px = mc.player.getX(), pz = mc.player.getZ();
        hovered = pick(mc, planeY);
        indexCards();

        Iterator<Card> it = cards.values().iterator();
        while (it.hasNext()) {
            Card c = it.next();
            c.a = approach(c.a, c.live ? 1f : 0f, c.live ? 5.5f : 7f, dt);
            c.hover = approach(c.hover, c.live && hovered == key(c) ? 1f : 0f, 9f, dt);
            if (!c.live && c.a <= 0.001f) {
                it.remove();
                continue;
            }
            double ccx = (c.cx << 4) + 8.0, ccz = (c.cz << 4) + 8.0;
            if (Math.abs(ccx - px) > limit || Math.abs(ccz - pz) > limit) continue;
            c.nb = 0;
            c.dg = 0;
            if (connect) {
                if (alive(c.cx + 1, c.cz - 1)) c.dg |= 1;
                if (alive(c.cx + 1, c.cz + 1)) c.dg |= 2;
                if (alive(c.cx - 1, c.cz + 1)) c.dg |= 4;
                if (alive(c.cx - 1, c.cz - 1)) c.dg |= 8;
                if (alive(c.cx, c.cz - 1)) c.nb |= 1;
                if (alive(c.cx, c.cz + 1)) c.nb |= 2;
                if (alive(c.cx - 1, c.cz)) c.nb |= 4;
                if (alive(c.cx + 1, c.cz)) c.nb |= 8;
            }
            card(c, ccx, ccz, planeY, ms, animations, tint & 0xFFFFFF, opacity);
        }
    }

    /** 0 Frost, 1 Liquid Glass, 2 Flat. */
    public int style;
    /** Spread mode: touching chunks merge into one connected plate (rounded only on the outside corners). */
    public boolean connect;
    /** false = no aim-at hover card */
    public boolean hoverCard = true;

    private void card(Card c, double x, double z, double y, long ms, boolean anim, int tint, float op) {
        float e = smooth(c.a);
        float hv = smooth(c.hover);
        float t = ms / 1000f + c.phase * 10f;
        float pop = c.a < 1f ? (float) (1 + 2.2 * Math.pow(c.a - 1, 3) + 1.2 * Math.pow(c.a - 1, 2)) : 1f;
        double half = 7.6 * (0.7 + 0.3 * pop) + hv * 0.35;
        float breath = anim ? 0.85f + 0.15f * (float) Math.sin(t * 1.6f) : 1f;
        double r = Math.min(3.2, half * 0.4);
        float k = e * op * (1f + hv * 0.6f);
        List<Vec3d> edge = roundRect(x, y, z, half, r);

        if (connect) {
            connected(c, x, y, z, t, e * op, e, breath, style == 3 ? (Nvg.mix(tint, 0xFFB347, Math.min(1f, c.heat * 1.1f)) & 0xFFFFFF) : tint, anim);
            return;
        }

        if (style == 3) {
            // pulse = the frost plate (same fill, same corner radius on every layer, so every corner joins up), tinted from
            // frost white to amber with the heat, with a heat-driven breathing bloom and a single soft ring ping
            tint = Nvg.mix(tint, 0xFFB347, Math.min(1f, c.heat * 1.1f)) & 0xFFFFFF;
            float ping = ((t * (0.25f + 0.6f * c.heat)) % 1f + 1f) % 1f;
            double pr = half * (0.25 + 0.85 * ping);
            double prr = Math.min(3.2, pr * 0.4);
            float pa = (1f - ping) * (1f - ping) * smooth(Math.min(1f, ping * 5f));
            ring(roundRect(x, y + 0.03, z, pr, prr), roundRect(x, y + 0.03, z, pr + 0.8, prr + 0.8), argb(tint, 0.5f * pa * k), argb(tint, 0f));
            ring(roundRect(x, y + 0.03, z, pr - 0.8, Math.max(0.2, prr - 0.8)), roundRect(x, y + 0.03, z, pr, prr), argb(tint, 0f), argb(tint, 0.5f * pa * k));
            k *= 0.8f + 0.5f * c.heat * breath;
        }

        if (style == 2) {
            // ---------------- flat: solid rounded tile, soft shadow, slightly lighter rim band (no outline)
            ring(roundRect(x, y - 0.004, z, half, r), roundRect(x, y - 0.004, z, half + 1.1, r + 1.1), argb(0, 0.22f * k), argb(0, 0f));
            fan(x, y, z, edge, argb(tint, 0.30f * k), argb(tint, 0.34f * k));
            ring(roundRect(x, y + 0.006, z, half - 0.9, Math.max(0.2, r - 0.9)), edge, argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0.16f * k));
            if (anim) glint(edge, roundRect(x, y + 0.01, z, half - 0.6, Math.max(0.2, r - 0.6)), t, k * 0.6f);
            return;
        }

        if (style == 1) {
            // ---------------- liquid glass: clear body, thick bright glass edge, a specular highlight that sweeps
            // around with a moving light, a soft outer bloom (no outline)
            fan(x, y, z, edge, argb(tint, 0.025f * k), argb(tint, 0.06f * k));
            List<Vec3d> in1 = roundRect(x, y + 0.004, z, half - 1.7, Math.max(0.2, r - 1.7));
            ring(in1, roundRect(x, y + 0.004, z, half, r), argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0.30f * k));
            double la = anim ? t * 0.5 : 0.8;
            ringLit(roundRect(x, y + 0.008, z, half - 0.7, Math.max(0.2, r - 0.7)), roundRect(x, y + 0.008, z, half, r), x, z, la, 0.75f * k);
            ring(roundRect(x, y, z, half, r), roundRect(x, y, z, half + 1.6 * breath, r + 1.6 * breath), argb(0xFFFFFF, 0.16f * k), argb(tint, 0f));
            if (anim) glint(roundRect(x, y + 0.012, z, half, r), roundRect(x, y + 0.012, z, half - 0.8, Math.max(0.2, r - 0.8)), t, k);
            return;
        }

        // ---------------- frost: milky centre glow into a bright frosted edge, soft bloom, gliding light (no outline)
        fan(x, y, z, edge, argb(0xFFFFFF, 0.13f * k * breath), argb(tint, 0.20f * k));
        ring(roundRect(x, y + 0.004, z, half - 1.4, Math.max(0.2, r - 1.4)), roundRect(x, y + 0.004, z, half, r), argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0.22f * k));
        ring(roundRect(x, y, z, half, r), roundRect(x, y, z, half + 1.4 * breath, r + 1.4 * breath), argb(tint, 0.24f * k), argb(tint, 0f));
        if (!anim) return;
        glint(roundRect(x, y + 0.01, z, half, r), roundRect(x, y + 0.01, z, half - 0.9, Math.max(0.2, r - 0.9)), t, k);
        long age = ms - c.born;
        if (age >= 0 && age < 1800) {
            float p = age / 1800f;
            double rr = half * (0.15 + 1.15 * p);
            ring(roundRect(x, y + 0.02, z, rr, Math.min(3.2, rr * 0.4)), roundRect(x, y + 0.02, z, rr + 0.5, Math.min(3.2, rr * 0.4) + 0.5),
                    argb(0xFFFFFF, (1f - p) * (1f - p) * 0.5f * op), argb(0xFFFFFF, 0f));
        }
        float pp = ((t * 0.22f) % 1f + 1f) % 1f;
        double pr = half * (0.2 + 0.8 * pp);
        float pa = (float) Math.sin(pp * Math.PI);
        ring(roundRect(x, y + 0.02, z, pr, Math.min(3.2, pr * 0.4)), roundRect(x, y + 0.02, z, pr + 0.35, Math.min(3.2, pr * 0.4) + 0.35),
                argb(tint, pa * 0.25f * k), argb(tint, 0f));
    }

    /* ------------------------------------------------------------------ connected plates (Spread mode) */

    private static final int PER = 11;

    /** Rounded-square outline with a separate corner radius per corner (0 = square), 11 points per corner, closed. The inset
     *  (positive = inward, negative = outward) is NOT applied to sides that touch a neighbouring plate (bits in {@code nb}), so
     *  bands and glows stop exactly at a junction instead of poking into the neighbour and doubling up there. */
    private static List<Vec3d> shape(double x, double y, double z, double half, double[] rr, double inset, int nb, int dg) {
        List<Vec3d> out = new ArrayList<>(PER * 4 + 1);
        double[][] corners = {{1, 1, 0}, {-1, 1, Math.PI / 2}, {-1, -1, Math.PI}, {1, -1, Math.PI * 1.5}};
        for (int c = 0; c < 4; c++) {
            double sx = corners[c][0], sz = corners[c][1];
            boolean sharedX = (nb & (sx > 0 ? 8 : 4)) != 0, sharedZ = (nb & (sz > 0 ? 2 : 1)) != 0;
            double hx = half - (sharedX ? 0 : inset), hz = half - (sharedZ ? 0 : inset);
            // concave junction (an L): the outward band of the side running away from the neighbour is trimmed by its own width,
            // so it butts against the neighbour's band instead of overlapping it in a double bright square
            int dgBit = c == 0 ? 2 : c == 1 ? 4 : c == 2 ? 8 : 1;
            if (sharedX && !sharedZ && (dg & dgBit) != 0 && inset < 0) hx = half + inset;
            double r = rr[c] > 0 ? Math.max(0, Math.min(rr[c] - inset, Math.min(hx, hz))) : 0;
            for (int i = 0; i < PER; i++) {
                double ang = corners[c][2] + i * (Math.PI / 2) / (PER - 1);
                out.add(new Vec3d(x + sx * (hx - r) + Math.cos(ang) * r, y, z + sz * (hz - r) + Math.sin(ang) * r));
            }
        }
        out.add(out.get(0));
        return out;
    }

    /** True when segment i of a {@link #shape} lies on a side that touches a neighbouring chunk. */
    private static boolean shared(int i, int nb) {
        if (i % PER != PER - 1) return false;
        int bit = switch (i / PER) {
            case 0 -> 2; // south (+z)
            case 1 -> 4; // west (-x)
            case 2 -> 1; // north (-z)
            default -> 8; // east (+x)
        };
        return (nb & bit) != 0;
    }

    private static void ringMasked(List<Vec3d> a, List<Vec3d> b, int ca, int cb, int nb) {
        if ((ca >>> 24) == 0 && (cb >>> 24) == 0) return;
        for (int i = 0; i + 1 < a.size(); i++) {
            if (shared(i, nb)) continue;
            Render3D.drawGradientQuad(a.get(i), a.get(i + 1), b.get(i + 1), b.get(i), ca, ca, cb, cb, false);
        }
    }

    private void connected(Card c, double x, double z0, double z, float t, float k, float e, float breath, int tint, boolean anim) {
        // note: parameter order is (card, x, y, z, ...) - z0 is the plane height
        double y = z0;
        double half = 8.0; // exactly the chunk, so neighbouring plates share their edge with no gap while fading in
        double r = 2.5;    // Crypto's plate corner radius
        int nb = c.nb;
        double[] rr = {
                (nb & (2 | 8)) == 0 ? r : 0, // +x +z corner
                (nb & (2 | 4)) == 0 ? r : 0, // -x +z
                (nb & (1 | 4)) == 0 ? r : 0, // -x -z
                (nb & (1 | 8)) == 0 ? r : 0  // +x -z
        };
        List<Vec3d> edge = shape(x, y, z, half, rr, 0, nb, c.dg);
        // inner glow at an L-shaped concave corner: the two inner bands only touch at the vertex, so fill the quadrant between them
        // with a soft corner glow (bright at the vertex, clear toward the inside)
        float fillA = style == 2 ? 0.16f : style == 1 ? 0.30f : 0.22f;
        double fw = style == 2 ? 0.9 : style == 1 ? 1.7 : 1.4;
        double[][] cv = {{1, 1, 2}, {-1, 1, 4}, {-1, -1, 8}, {1, -1, 1}};
        for (double[] q : cv) {
            boolean shX = (nb & (q[0] > 0 ? 8 : 4)) != 0, shZ = (nb & (q[1] > 0 ? 2 : 1)) != 0;
            if (shX && !shZ && (c.dg & (int) q[2]) != 0) {
                double vx = x + q[0] * half, vz = z + q[1] * half;
                int cc = argb(0xFFFFFF, fillA * k), c0z = argb(0xFFFFFF, 0f);
                Render3D.drawGradientQuad(new Vec3d(vx, y + 0.004, vz), new Vec3d(vx + q[0] * fw, y + 0.004, vz), new Vec3d(vx + q[0] * fw, y + 0.004, vz - q[1] * fw),
                        new Vec3d(vx, y + 0.004, vz - q[1] * fw), cc, c0z, c0z, c0z, false);
            }
        }
        // plate edge (Crypto's rounded plate has side walls): a soft wall dropping from the rim on every outer side
        {
            List<Vec3d> low = shape(x, y - 0.18, z, half, rr, 0, nb, c.dg);
            int wallA = style == 2 ? 70 : 46;
            for (int i = 0; i + 1 < edge.size(); i++) {
                if (shared(i, nb)) continue;
                Render3D.drawGradientQuad(edge.get(i), edge.get(i + 1), low.get(i + 1), low.get(i), argb(0xFFFFFF, wallA / 255f * k), argb(0xFFFFFF, wallA / 255f * k),
                        argb(tint, 0f), argb(tint, 0f), false);
            }
        }
        if (style == 2) { // flat
            ringMasked(edge, shape(x, y - 0.004, z, half, rr, -1.1, nb, c.dg), argb(0, 0.22f * k), argb(0, 0f), nb);
            fan(x, y, z, edge, argb(tint, 0.32f * k), argb(tint, 0.32f * k));
            ringMasked(shape(x, y + 0.006, z, half, rr, 0.9, nb, c.dg), edge, argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0.16f * k), nb);
            return;
        }
        if (style == 1) { // liquid glass
            fan(x, y, z, edge, argb(tint, 0.05f * k), argb(tint, 0.05f * k));
            ringMasked(shape(x, y + 0.004, z, half, rr, 1.7, nb, c.dg), edge, argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0.30f * k), nb);
            double la = anim ? t * 0.5 : 0.8;
            double lx = Math.cos(la), lz = Math.sin(la);
            List<Vec3d> inner = shape(x, y + 0.008, z, half, rr, 0.7, nb, c.dg);
            for (int i = 0; i + 1 < edge.size(); i++) {
                if (shared(i, nb)) continue;
                float f0 = lit(edge.get(i), x, z, lx, lz), f1 = lit(edge.get(i + 1), x, z, lx, lz);
                Render3D.drawGradientQuad(inner.get(i), inner.get(i + 1), edge.get(i + 1), edge.get(i), argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0f),
                        argb(0xFFFFFF, f1 * 0.75f * k), argb(0xFFFFFF, f0 * 0.75f * k), false);
            }
            ringMasked(edge, shape(x, y, z, half, rr, -1.6 * breath, nb, c.dg), argb(0xFFFFFF, 0.16f * k), argb(tint, 0f), nb);
            return;
        }
        // frost
        fan(x, y, z, edge, argb(tint, 0.17f * k), argb(tint, 0.17f * k));
        ringMasked(shape(x, y + 0.004, z, half, rr, 1.4, nb, c.dg), edge, argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0.22f * k), nb);
        ringMasked(edge, shape(x, y, z, half, rr, -1.4 * breath, nb, c.dg), argb(tint, 0.24f * k), argb(tint, 0f), nb);
        if (anim) {
            // a light drifting along the whole plate's outer rim
            int n = edge.size() - 1;
            float head = ((t * 0.18f + c.phase) % 1f + 1f) % 1f * n;
            int len = Math.max(6, n / 4);
            List<Vec3d> in2 = shape(x, y + 0.01, z, half, rr, 0.9, nb, c.dg);
            for (int j = 0; j < len; j++) {
                int i = ((int) head - j + n * 4) % n;
                if (shared(i, nb)) continue;
                float f0 = (float) Math.sin(Math.PI * (j / (float) len)), f1 = (float) Math.sin(Math.PI * ((j + 1) / (float) len));
                int i1 = (i + 1) % n;
                Render3D.drawGradientQuad(in2.get(i), in2.get(i1), edge.get(i1), edge.get(i), argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0f),
                        argb(0xFFFFFF, f0 * 0.7f * k), argb(0xFFFFFF, f1 * 0.7f * k), false);
            }
        }
    }

    /** Filled rounded shape, colour {@code cc} in the middle to {@code ce} at the rim. */
    private static void fan(double x, double y, double z, List<Vec3d> pts, int cc, int ce) {
        if ((cc >>> 24) == 0 && (ce >>> 24) == 0) return;
        Vec3d o = new Vec3d(x, y, z);
        for (int i = 0; i + 1 < pts.size(); i++) Render3D.drawGradientQuad(o, o, pts.get(i + 1), pts.get(i), cc, cc, ce, ce, false);
    }

    /** Band between two rounded outlines with the same point count, colour {@code ca} on {@code a} to {@code cb} on {@code b}. */
    private static void ring(List<Vec3d> a, List<Vec3d> b, int ca, int cb) {
        if ((ca >>> 24) == 0 && (cb >>> 24) == 0) return;
        for (int i = 0; i + 1 < a.size(); i++) Render3D.drawGradientQuad(a.get(i), a.get(i + 1), b.get(i + 1), b.get(i), ca, ca, cb, cb, false);
    }

    /** Glass rim lit from one side: brightness follows how much each part of the edge faces a light that circles slowly. */
    private static void ringLit(List<Vec3d> a, List<Vec3d> b, double cx, double cz, double lightAng, float k) {
        double lx = Math.cos(lightAng), lz = Math.sin(lightAng);
        for (int i = 0; i + 1 < a.size(); i++) {
            float f0 = lit(b.get(i), cx, cz, lx, lz), f1 = lit(b.get(i + 1), cx, cz, lx, lz);
            Render3D.drawGradientQuad(a.get(i), a.get(i + 1), b.get(i + 1), b.get(i), argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0f),
                    argb(0xFFFFFF, f1 * k), argb(0xFFFFFF, f0 * k), false);
        }
    }

    private static float lit(Vec3d p, double cx, double cz, double lx, double lz) {
        double dx = p.x - cx, dz = p.z - cz, l = Math.sqrt(dx * dx + dz * dz) + 1e-6;
        double d = (dx * lx + dz * lz) / l; // facing the light: 1, away: -1
        double f = Math.max(0, d), g = Math.max(0, -d);
        return (float) (Math.pow(f, 6) + Math.pow(g, 10) * 0.35); // main glint plus a faint opposite caustic
    }

    /** A soft light gliding along the rim (a band segment that fades at both ends). */
    private static void glint(List<Vec3d> outer, List<Vec3d> inner, float t, float k) {
        int n = outer.size() - 1;
        float head = ((t * 0.18f) % 1f + 1f) % 1f * n;
        int len = Math.max(6, n / 4);
        for (int j = 0; j < len; j++) {
            int i = ((int) head - j + n * 4) % n;
            float f0 = (float) Math.sin(Math.PI * (j / (float) len)), f1 = (float) Math.sin(Math.PI * ((j + 1) / (float) len));
            int i1 = (i + 1) % n;
            Render3D.drawGradientQuad(inner.get(i), inner.get(i1), outer.get(i1), outer.get(i),
                    argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0f), argb(0xFFFFFF, f0 * 0.7f * k), argb(0xFFFFFF, f1 * 0.7f * k), false);
        }
    }

    private static void plate(double x, double y, double z, double h, int color) {
        if ((color >>> 24) == 0) return;
        Render3D.drawQuad(new Vec3d(x - h, y, z - h), new Vec3d(x + h, y, z - h), new Vec3d(x + h, y, z + h), new Vec3d(x - h, y, z + h), color, false);
    }

    private static void polyline(List<Vec3d> pts, int color, float width) {
        if ((color >>> 24) == 0) return;
        for (int i = 1; i < pts.size(); i++) Render3D.drawLine(pts.get(i - 1), pts.get(i), color, width, false);
    }

    /** Closed rounded square on a horizontal plane (first point repeated at the end). */
    private static List<Vec3d> roundRect(double x, double y, double z, double half, double r) {
        List<Vec3d> out = new ArrayList<>(30);
        double in = half - r;
        double[][] corners = {{in, in, 0}, {-in, in, Math.PI / 2}, {-in, -in, Math.PI}, {in, -in, Math.PI * 1.5}};
        for (double[] c : corners) {
            for (int i = 0; i <= 10; i++) {
                double a = c[2] + i * (Math.PI / 2) / 10;
                out.add(new Vec3d(x + c[0] + Math.cos(a) * r, y, z + c[1] + Math.sin(a) * r));
            }
        }
        out.add(out.get(0));
        return out;
    }

    /* ------------------------------------------------------------------ 2D */

    private final float[] p = new float[2];

    public void drawOverlay(String header, boolean tracers, boolean labels, boolean list, int tint, float opacity) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        tint &= 0xFFFFFF;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        List<Card> visible = new ArrayList<>();
        for (Card c : cards.values()) {
            double ccx = (c.cx << 4) + 8.0, ccz = (c.cz << 4) + 8.0;
            if (Math.abs(ccx - px) > limit || Math.abs(ccz - pz) > limit) continue;
            visible.add(c);
        }

        indexCards();
        // Spread / connected plates get ONE label per plate, in the middle of it; single cards sit on their own centre
        java.util.Map<Card, double[]> spots = new java.util.LinkedHashMap<>();
        if (connect) {
            java.util.Set<Card> seen = new java.util.HashSet<>();
            for (Card c : visible) {
                if (seen.contains(c)) continue;
                List<Card> group = new ArrayList<>();
                java.util.ArrayDeque<Card> q = new java.util.ArrayDeque<>();
                q.add(c);
                seen.add(c);
                while (!q.isEmpty()) {
                    Card n = q.poll();
                    group.add(n);
                    int[][] d = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
                    for (int[] dd : d) {
                        Card o = at(n.cx + dd[0], n.cz + dd[1]);
                        if (o != null && visible.contains(o) && seen.add(o)) q.add(o);
                    }
                }
                double mx = 0, mz = 0;
                for (Card g : group) {
                    mx += (g.cx << 4) + 8.0;
                    mz += (g.cz << 4) + 8.0;
                }
                mx /= group.size();
                mz /= group.size();
                Card rep = group.get(0);
                double best = Double.MAX_VALUE;
                for (Card g : group) {
                    double dd = sq(((g.cx << 4) + 8.0) - mx) + sq(((g.cz << 4) + 8.0) - mz);
                    if (g.hover > 0.5f) { rep = g; break; }
                    if (dd < best) { best = dd; rep = g; }
                }
                spots.put(rep, new double[]{mx, mz, group.size()});
            }
        } else for (Card c : visible) spots.put(c, new double[]{(c.cx << 4) + 8.0, (c.cz << 4) + 8.0, 1});

        for (var en : spots.entrySet()) {
            Card c = en.getKey();
            double[] sp = en.getValue();
            float e = smooth(c.a);
            if (e <= 0.01f) continue;
            Vec3d on = new Vec3d(sp[0], planeY + 0.05, sp[1]);
            Vec3d at = new Vec3d(sp[0], planeY + 0.6, sp[1]);
            float[] base = new float[2];
            if (!Projection.point(at, p) || !Projection.point(on, base)) continue;
            float sx = p[0], sy = p[1];
            if (tracers) {
                Nvg.lineGradient(Nvg.w / 2f, Nvg.h, base[0], base[1], 1.3f, argb(tint, 0f), argb(tint, 0.65f * e * opacity));
                Nvg.glowDisc(base[0], base[1], 6f * e, argb(0xFFFFFF, 0.5f * e * opacity), argb(tint, 0f));
            }
            if (labels) {
                double dist = Math.sqrt(sq(sp[0] - px) + sq(sp[1] - pz));
                String s = c.title + (sp[2] > 1 ? " x" + (int) sp[2] : "") + "  " + fmtDist(dist);
                float k = 0.82f + 0.18f * e;
                float fs = 8.5f * k;
                float w = (Nvg.textWidth(s, fs, Nvg.SEMI) + 32f * k);
                float h = 17f * k;
                float x = sx - w / 2f, y = sy - 16f * k - h / 2f - (1f - e) * 6f;
                // stem joining the label to the middle of the plate, then the glass pill on top of it (same glass as the hover card)
                Nvg.lineGradient(sx, y + h, base[0], base[1], 1.2f, argb(0xFFFFFF, 0.55f * e * opacity), argb(0xFFFFFF, 0.1f * e * opacity));
                Nvg.circle(base[0], base[1], 2.2f * e, argb(0xFFFFFF, 0.85f * e * opacity));
                tenacity.ui.Theme.hud(x, y, w, h, h / 2f, e * opacity, 0);
                Nvg.circle(x + 8f * k, y + h / 2f, 2.2f * k, argb(tint, 0.95f * e));
                Nvg.text(s, x + 16f * k, y + h / 2f, fs, Nvg.SEMI, Nvg.LEFT, argb(0xFFFFFF, 0.94f * e));
            }
        }

        if (hoverCard) for (Card c : visible) inspect(c, tint, opacity);

        if (!list) return;
        visible.removeIf(c -> !c.live);
        visible.sort((a, b) -> Double.compare(sq(a.ax - px) + sq(a.az - pz), sq(b.ax - px) + sq(b.az - pz)));
        int rows = Math.min(6, visible.size());
        panelA = approach(panelA, rows > 0 ? 1f : 0f, 6f, dt);
        panelRows = approach(panelRows, rows, 9f, dt);
        if (panelA <= 0.005f) return;
        float e = smooth(panelA);
        float pw = 196f;
        float targetH = 30f + Math.max(1f, panelRows) * 15f;
        panelH = panelH <= 0 ? targetH : approach(panelH, targetH, 10f, dt);
        float x = Nvg.w - pw - 12f + (1f - e) * 28f;
        float y = Nvg.h * 0.3f;
        Widgets.glass(x, y, pw, panelH, 10f, e * opacity);
        Nvg.circle(x + 12f, y + 13f, 2.6f, argb(tint, e));
        Nvg.glowDisc(x + 12f, y + 13f, 7f, argb(tint, 0.35f * e), argb(tint, 0f));
        Nvg.text(header, x + 20f, y + 13.5f, 9.5f, Nvg.SEMI, Nvg.LEFT, argb(0xFFFFFF, 0.95f * e));
        Nvg.text(visible.size() + " found", x + pw - 10f, y + 13.5f, 8f, Nvg.MED, Nvg.RIGHT, argb(tint, 0.75f * e));
        Nvg.line(x + 10f, y + 24f, x + pw - 10f, y + 24f, 1f, argb(0xFFFFFF, 0.08f * e));
        Nvg.save();
        Nvg.clip(x, y + 25f, pw, Math.max(0f, panelH - 27f));
        for (int i = 0; i < rows; i++) {
            Card c = visible.get(i);
            float re = e * smooth(c.a);
            float ry = y + 34f + i * 15f;
            String coords = (int) Math.floor(c.ax) + ", " + (int) Math.floor(c.ay) + ", " + (int) Math.floor(c.az);
            double dist = Math.sqrt(sq(c.ax - px) + sq(c.ay - py) + sq(c.az - pz));
            Nvg.text(Nvg.fit(c.title, 8.5f, Nvg.MED, 82f), x + 12f, ry, 8.5f, Nvg.MED, Nvg.LEFT, argb(0xFFFFFF, 0.9f * re));
            Nvg.text(coords + "  " + fmtDist(dist), x + pw - 10f, ry, 7.5f, Nvg.REG, Nvg.RIGHT, argb(tint, 0.62f * re));
        }
        Nvg.restore();
    }

    private float lagX, lagY, lastYaw = Float.NaN, lastPitch;

    /** Liquid glass card to the right of the crosshair for the aimed-at chunk: why it got flagged and the base chance. */
    private void inspect(Card c, int tint, float opacity) {
        float h = smooth(c.hover) * smooth(c.a);
        if (h <= 0.01f) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        // floats like a 3D card: it lags a little behind your camera turns and springs back
        var cam = mc.gameRenderer.getCamera();
        if (Float.isNaN(lastYaw)) { lastYaw = cam.getYaw(); lastPitch = cam.getPitch(); }
        float dy = net.minecraft.util.math.MathHelper.wrapDegrees(cam.getYaw() - lastYaw), dp = cam.getPitch() - lastPitch;
        lastYaw = cam.getYaw();
        lastPitch = cam.getPitch();
        lagX = approach(lagX - dy * 2.2f, 0, 7f, dt);
        lagY = approach(lagY - dp * 2.2f, 0, 7f, dt);
        lagX = Math.max(-60, Math.min(60, lagX));
        lagY = Math.max(-40, Math.min(40, lagY));

        List<String> rs = c.reasons.isEmpty() ? List.of(c.detail.isEmpty() ? c.title : c.detail.replace("+", ", ")) : c.reasons;
        float chance = c.chance < 0 ? 0.5f : c.chance;
        float pw = 224f * (0.9f + 0.1f * h), rowH = 14f;
        float extraH = 0f;
        if (c.spark != null) extraH += 40f;
        if (!c.bars.isEmpty()) extraH += 6f + c.bars.size() * 13f;
        float ph = (64f + extraH + rs.size() * rowH) * (0.9f + 0.1f * h);
        float x = Nvg.w / 2f + 34f + (1 - h) * 24f + lagX, y = Nvg.h / 2f - ph / 2f + lagY;
        tenacity.ui.Theme.hud(x, y, pw, ph, 16f, h * opacity, 0);
        // little connector from the crosshair
        Nvg.lineGradient(Nvg.w / 2f + 8f, Nvg.h / 2f, x, y + ph / 2f, 1.2f, argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0.6f * h));
        Nvg.circle(x, y + ph / 2f, 2.2f, argb(0xFFFFFF, 0.9f * h));

        float ix = x + 14f, iw = pw - 28f;
        Nvg.text(c.title, ix, y + 18f, 11f, Nvg.SEMI, Nvg.LEFT, argb(0xFFFFFF, 0.97f * h));
        Nvg.text("chunk " + c.cx + ", " + c.cz, x + pw - 14f, y + 18f, 8f, Nvg.MED, Nvg.RIGHT, argb(0xFFFFFF, 0.5f * h));
        String pct = Math.round(chance * 100 * h) + "%";
        Nvg.text(c.gaugeLabel == null ? "Base chance" : c.gaugeLabel, ix, y + 36f, 8.5f, Nvg.MED, Nvg.LEFT, argb(0xFFFFFF, 0.7f * h));
        Nvg.text(pct, x + pw - 14f, y + 36f, 10f, Nvg.SEMI, Nvg.RIGHT, argb(0xFFFFFF, h));
        float shown = chance * h;
        Nvg.rrect(ix, y + 44f, iw, 6f, 3f, argb(0xFFFFFF, 0.10f * h));
        Nvg.rrectGradient(ix, y + 44f, Math.max(6f, iw * shown), 6f, 3f, ix, 0, ix + iw, 0, argb(tint, 0.85f * h), argb(0xFFFFFF, h));
        Nvg.glowDisc(ix + iw * shown, y + 47f, 7f, argb(0xFFFFFF, 0.45f * h), argb(0xFFFFFF, 0f));
        Nvg.line(ix, y + 57f, x + pw - 14f, y + 57f, 1f, argb(0xFFFFFF, 0.08f * h));
        float oy = 0f;
        if (c.spark != null) {
            // activity sparkline (one sample per 2 seconds of the last minute, newest on the right)
            float sy = y + 64f, sh = 26f;
            Nvg.text("last 60s", ix, sy + 2f, 7.5f, Nvg.MED, Nvg.LEFT, argb(0xFFFFFF, 0.5f * h));
            int n = c.spark.length;
            float bw = iw / n;
            for (int i = 0; i < n; i++) {
                float v = Math.max(0.04f, c.spark[i]) * smooth(Math.min(1f, c.hover * 1.5f));
                float bh = (sh - 8f) * v;
                int col = Nvg.mix(0xF3F5F8, 0xFFB347, Math.min(1f, c.spark[i] * 1.2f));
                Nvg.rrect(ix + i * bw + 0.5f, sy + sh - bh, Math.max(1f, bw - 1f), bh, 1f, argb(col, (0.35f + 0.55f * c.spark[i]) * h));
            }
            Nvg.line(ix, sy + sh + 1f, ix + iw, sy + sh + 1f, 1f, argb(0xFFFFFF, 0.1f * h));
            oy += 40f;
        }
        if (!c.bars.isEmpty()) {
            for (int i = 0; i < c.bars.size(); i++) {
                Bar b = c.bars.get(i);
                float by = y + 66f + oy + i * 13f;
                Nvg.text(Nvg.fit(b.label(), 7.5f, Nvg.MED, 62f), ix, by, 7.5f, Nvg.MED, Nvg.LEFT, argb(0xFFFFFF, 0.72f * h));
                float bx = ix + 66f, bwid = iw - 66f - 34f;
                Nvg.rrect(bx, by - 2.5f, bwid, 5f, 2.5f, argb(0xFFFFFF, 0.09f * h));
                float fw = Math.max(3f, bwid * b.fraction() * smooth(Math.min(1f, c.hover * 1.3f)));
                Nvg.rrectGradient(bx, by - 2.5f, fw, 5f, 2.5f, bx, 0, bx + bwid, 0, argb(tint, 0.8f * h), argb(0xFFB347, 0.95f * h));
                Nvg.text(b.value(), ix + iw, by, 7.5f, Nvg.MED, Nvg.RIGHT, argb(0xFFFFFF, 0.6f * h));
            }
            oy += 6f + c.bars.size() * 13f;
        }
        for (int i = 0; i < rs.size(); i++) {
            float ry = y + 68f + oy + i * rowH;
            float ra = h * smooth(Math.min(1f, c.hover * 1.4f - i * 0.08f)); // rows cascade in
            Nvg.circle(ix + 2f, ry, 1.7f, argb(tint, 0.9f * ra));
            Nvg.text(Nvg.fit(rs.get(i), 8.5f, Nvg.MED, iw - 10f), ix + 9f, ry, 8.5f, Nvg.MED, Nvg.LEFT, argb(0xFFFFFF, 0.86f * ra));
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String fmtDist(double d) {
        return d >= 1000 ? String.format(Locale.ROOT, "%.1fk", d / 1000) : (int) d + "m";
    }
}

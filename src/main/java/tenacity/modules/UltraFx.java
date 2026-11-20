package tenacity.modules;

import net.minecraft.util.math.Vec3d;
import tenacity.modules.MoreVisuals.VFx;
import voided.util.render.GlowBatch;
import voided.util.render.Render3D;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Additive (light-adding) drawing helpers on top of {@link GlowBatch}: soft camera facing sprites, soft glow lines and
 * ribbons with fully transparent rims so everything blooms instead of having hard edges. Call {@link VFx#frame()} first.
 */
final class UltraFx {
    private UltraFx() {
    }

    static final int WHITE = 0xFFFFFFFF, FROST = 0xFFEFF4FA;

    static void q(boolean d, Vec3d a, Vec3d b, Vec3d c, Vec3d e, int ca, int cb, int cc, int ce) {
        if (((ca | cb | cc | ce) >>> 24) == 0) return;
        GlowBatch.quad(d, a.x, a.y, a.z, ca, b.x, b.y, b.z, cb, c.x, c.y, c.z, cc, e.x, e.y, e.z, ce);
    }

    static int a(int c, float al) {
        return VFx.a(c, al);
    }

    static int mix(int x, int y, float t) {
        return VFx.mix(x, y, t);
    }

    /** Camera facing soft additive disc: colour at the centre fading to nothing. */
    static void sprite(Vec3d p, double r, int col, float al, boolean d) {
        al *= VFx.near(p);
        if (al <= 0.003f || r <= 0) return;
        int c = a(col, al), e = col & 0xFFFFFF;
        int seg = 14;
        Vec3d prev = p.add(VFx.right.multiply(r));
        for (int i = 1; i <= seg; i++) {
            double an = i * Math.PI * 2 / seg;
            Vec3d n = p.add(VFx.right.multiply(Math.cos(an) * r)).add(VFx.up.multiply(Math.sin(an) * r));
            q(d, p, p, n, prev, c, c, e, e);
            prev = n;
        }
    }

    /** Point light: wide faint halo, body glow and a whitened hot core. */
    static void glow(Vec3d p, double r, int col, float al, boolean d) {
        sprite(p, r * 4.2, col, al * 0.10f, d);
        sprite(p, r * 2.0, col, al * 0.30f, d);
        sprite(p, r * 0.8, mix(col, WHITE, 0.65f), al * 0.9f, d);
    }

    /** Camera facing ring band (bright at radius r, soft inside / outside). */
    static void rim(Vec3d p, double r, double w, int col, float al, boolean d) {
        if (al <= 0.003f) return;
        int c = a(col, al), e = col & 0xFFFFFF, seg = 40;
        for (int i = 0; i < seg; i++) {
            double t0 = i * Math.PI * 2 / seg, t1 = (i + 1) * Math.PI * 2 / seg;
            Vec3d d0 = VFx.right.multiply(Math.cos(t0)).add(VFx.up.multiply(Math.sin(t0)));
            Vec3d d1 = VFx.right.multiply(Math.cos(t1)).add(VFx.up.multiply(Math.sin(t1)));
            Vec3d m0 = p.add(d0.multiply(r)), m1 = p.add(d1.multiply(r));
            q(d, p.add(d0.multiply(Math.max(0, r - w * 2.5))), m0, m1, p.add(d1.multiply(Math.max(0, r - w * 2.5))), e, c, c, e);
            q(d, m0, p.add(d0.multiply(r + w)), p.add(d1.multiply(r + w)), m1, c, e, e, c);
        }
    }

    /** Soft additive band between two points facing the camera. */
    static void line(Vec3d a, Vec3d b, double w, int ca, int cb, boolean d) {
        Vec3d t = b.subtract(a);
        Vec3d s = t.crossProduct(a.add(b).multiply(0.5).subtract(VFx.cam));
        if (s.lengthSquared() < 1e-12) return;
        s = s.normalize().multiply(w);
        float na = VFx.near(a), nb = VFx.near(b);
        ca = VFx.am(ca, na);
        cb = VFx.am(cb, nb);
        int ea = ca & 0xFFFFFF, eb = cb & 0xFFFFFF;
        q(d, a.subtract(s), a, b, b.subtract(s), ea, ca, cb, eb);
        q(d, a, a.add(s), b.add(s), b, ca, ea, eb, cb);
    }

    static void glowLine(Vec3d a, Vec3d b, double w, int col, float al, boolean d) {
        line(a, b, w * 5, a(col, al * 0.10f), a(col, al * 0.10f), d);
        line(a, b, w * 2.2, a(col, al * 0.32f), a(col, al * 0.32f), d);
        int hot = mix(col, WHITE, 0.6f);
        line(a, b, w * 0.8, a(hot, al), a(hot, al), d);
    }

    /** Additive camera facing ribbon through points with per-point half widths and colours (colours carry alpha). */
    static void ribbon(List<Vec3d> p, float[] w, int[] c, boolean d) {
        int n = p.size();
        if (n < 2) return;
        Vec3d[] s = new Vec3d[n];
        Vec3d prev = VFx.right;
        for (int i = 0; i < n; i++) {
            Vec3d t = p.get(Math.min(i + 1, n - 1)).subtract(p.get(Math.max(i - 1, 0)));
            Vec3d side = t.crossProduct(p.get(i).subtract(VFx.cam));
            side = side.lengthSquared() < 1e-14 ? prev : side.normalize();
            if (side.dotProduct(prev) < 0 && i > 0) side = side.multiply(-1);
            s[i] = side;
            prev = side;
        }
        for (int i = 0; i < n - 1; i++) {
            Vec3d a = p.get(i), b = p.get(i + 1);
            int ca = VFx.am(c[i], VFx.near(a)), cb = VFx.am(c[i + 1], VFx.near(b));
            if ((ca >>> 24) < 1 && (cb >>> 24) < 1) continue;
            Vec3d sa = s[i].multiply(w[i]), sb = s[i + 1].multiply(w[i + 1]);
            int ea = ca & 0xFFFFFF, eb = cb & 0xFFFFFF;
            q(d, a.subtract(sa), a, b, b.subtract(sb), ea, ca, cb, eb);
            q(d, a, a.add(sa), b.add(sb), b, ca, ea, eb, cb);
        }
    }

    /** Ribbon plus two wider faint layers and a hot core. */
    static void glowRibbon(List<Vec3d> p, float[] w, int[] c, float strength, boolean d) {
        int n = p.size();
        float[] lw = new float[n];
        int[] lc = new int[n];
        float[][] layers = {{4.5f, 0.09f}, {2.2f, 0.28f}, {1f, 0.75f}, {0.4f, 1f}};
        for (int l = 0; l < layers.length; l++) {
            for (int i = 0; i < n; i++) {
                lw[i] = w[i] * layers[l][0];
                int col = l == 3 ? mix(c[i], WHITE, 0.6f) : c[i];
                lc[i] = VFx.am(col | 0xFF000000, ((c[i] >>> 24) / 255f) * layers[l][1] * (l < 2 ? strength : 1));
            }
            ribbon(p, lw, lc, d);
        }
    }

    /** Jagged bolt from a to b by midpoint displacement. */
    static List<Vec3d> bolt(Vec3d a, Vec3d b, double jag, int depth, Random r) {
        List<Vec3d> pts = new ArrayList<>();
        pts.add(a);
        pts.add(b);
        for (int k = 0; k < depth; k++) {
            List<Vec3d> nx = new ArrayList<>();
            for (int i = 0; i < pts.size() - 1; i++) {
                Vec3d p = pts.get(i), e = pts.get(i + 1);
                double len = p.distanceTo(e);
                nx.add(p);
                nx.add(p.add(e).multiply(0.5).add((r.nextDouble() - 0.5) * len * jag, (r.nextDouble() - 0.5) * len * jag, (r.nextDouble() - 0.5) * len * jag));
            }
            nx.add(pts.get(pts.size() - 1));
            pts = nx;
        }
        return pts;
    }

    /** Translucent (normal blend) double sided quad. */
    static void glass(Vec3d a, Vec3d b, Vec3d c, Vec3d e, int ca, int cb, int cc, int ce, boolean d) {
        Render3D.drawGradientQuad(a, b, c, e, ca, cb, cc, ce, d);
        Render3D.drawGradientQuad(e, c, b, a, ce, cc, cb, ca, d);
    }

    /** Orthonormal pair perpendicular to n. */
    static Vec3d[] basis(Vec3d n) {
        Vec3d u = n.crossProduct(Math.abs(n.y) < 0.95 ? VFx.UP : VFx.X).normalize();
        return new Vec3d[]{u, n.crossProduct(u).normalize()};
    }
}

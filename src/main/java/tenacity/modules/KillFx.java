package tenacity.modules;

import net.minecraft.util.math.Vec3d;
import tenacity.modules.MoreVisuals.VFx;
import voided.util.render.Render3D;

import java.util.ArrayList;
import java.util.List;

/**
 * Kill effect renderers. Every effect is a pure function of its age (seconds) and a seed, so it is perfectly smooth at
 * any frame rate and needs no per tick simulation: all motion is analytic (drag, gravity, bounces, spirals).
 */
final class KillFx {
    static final String[] NAMES = {"Lightning", "Lightning Strike", "Soul", "Shatter", "Ink Splash", "Firework", "Black Hole",
            "Angel Wings", "Disintegrate", "Ice Freeze", "Confetti", "Tornado"};

    /** A running effect: where, how big, which one, when. */
    record Kill(Vec3d pos, double h, double w, float yaw, String type, double born, int seed, int color) {
    }

    private KillFx() {
    }

    static float life(String type) {
        return switch (type) {
            case "Lightning Strike" -> 1.6f;
            case "Soul" -> 3.0f;
            case "Shatter" -> 3.2f;
            case "Ink Splash" -> 3.5f;
            case "Firework" -> 2.8f;
            case "Black Hole" -> 2.6f;
            case "Angel Wings" -> 3.2f;
            case "Disintegrate" -> 2.8f;
            case "Ice Freeze" -> 3.4f;
            case "Confetti" -> 3.6f;
            case "Tornado" -> 3.0f;
            default -> 0f;
        };
    }

    static void render(Kill k, float age) {
        switch (k.type) {
            case "Lightning Strike" -> lightning(k, age);
            case "Soul" -> soul(k, age);
            case "Shatter" -> shatter(k, age, 0, 0xFFF3F5F8);
            case "Ink Splash" -> ink(k, age);
            case "Firework" -> firework(k, age);
            case "Black Hole" -> blackHole(k, age);
            case "Angel Wings" -> wings(k, age);
            case "Disintegrate" -> disintegrate(k, age);
            case "Ice Freeze" -> ice(k, age);
            case "Confetti" -> confetti(k, age);
            case "Tornado" -> tornado(k, age);
            default -> {
            }
        }
    }

    /** Normal-alpha (not additive) filled disc, for dark shapes; soft edge from stacked rings of falling alpha. */
    private static void darkDisc(Vec3d c, Vec3d u, Vec3d v, double r, int seg, int col, float alpha) {
        float[] rs = {1f, 0.86f, 0.7f};
        float[] as = {0.3f, 0.45f, 1f};
        for (int l = 0; l < 3; l++) {
            int cc = VFx.a(col, alpha * as[l]);
            if ((cc >>> 24) < 2) continue;
            double rr = r * rs[l];
            Vec3d prev = c.add(u.multiply(rr));
            for (int i = 1; i <= seg; i++) {
                double an = i * Math.PI * 2 / seg;
                Vec3d q = c.add(u.multiply(Math.cos(an) * rr)).add(v.multiply(Math.sin(an) * rr));
                Render3D.drawQuad(c, prev, q, q, cc, true);
                prev = q;
            }
        }
    }

    private static float h(int s) {
        return VFx.hash(s);
    }

    private static float fadeOut(float age, float start, float len) {
        return 1 - VFx.smooth((age - start) / len);
    }

    /* ------------------------------------------------------------------ lightning strike */

    private static void lightning(Kill k, float age) {
        Vec3d ground = k.pos;
        Vec3d top = ground.add((h(k.seed) - 0.5) * 6, 28, (h(k.seed + 1) - 0.5) * 6);
        // three strokes: main + two re-strikes, each re-rolled
        for (int stroke = 0; stroke < 3; stroke++) {
            float t0 = stroke * 0.16f, a = age - t0;
            if (a < 0) continue;
            float al = (float) Math.exp(-a * 7) * VFx.smooth(a / 0.015f);
            if (al < 0.02f) continue;
            int s = k.seed * 17 + stroke * 101;
            List<Vec3d> bolt = jag(top, ground.add(0, 0.05, 0), 22, 1.4, s);
            boltDraw(bolt, al, 0.11, k.color);
            // forks
            for (int f = 0; f < 4; f++) {
                int idx = 3 + (int) (h(s + f * 3) * (bolt.size() - 8));
                Vec3d from = bolt.get(idx);
                Vec3d dir = new Vec3d(h(s + f * 3 + 1) - 0.5, -0.6 - h(s + f) * 0.4, h(s + f * 3 + 2) - 0.5).normalize();
                List<Vec3d> fork = jag(from, from.add(dir.multiply(3 + 4 * h(s + f + 9))), 8, 0.6, s + f * 7);
                boltDraw(fork, al * 0.6f, 0.05, k.color);
            }
        }
        // flash at the impact and a scorched glowing ring
        float fl = (float) Math.exp(-age * 5) * VFx.smooth(age / 0.02f);
        VFx.sprite(ground.add(0, 1, 0), 3.5, VFx.a(0xFFFFFFFF, 0.35f * fl), true);
        VFx.disc(ground.add(0, 0.03, 0), VFx.X, VFx.Z, 1.6, 40, VFx.a(0xFFFFFFFF, 0.5f * fl), VFx.a(k.color, 0), true);
        float rk = VFx.outCubic(age / 0.8f);
        VFx.ring(ground.add(0, 0.03, 0), VFx.X, VFx.Z, 0.3 + 2.4 * rk, 0.12, 64, t -> k.color, 0.55f * (1 - rk), 0, true);
        for (int i = 0; i < 14; i++) {
            int s = k.seed + 300 + i;
            double an = h(s) * Math.PI * 2, v = 2 + 3 * h(s + 1);
            double d = v / 3 * (1 - Math.exp(-3 * age)), y = (2 + 3 * h(s + 2)) * age - 7 * age * age;
            if (y < 0) continue;
            VFx.spark(ground.add(Math.cos(an) * d, y + 0.05, Math.sin(an) * d), 0.02, k.color, fadeOut(age, 0.3f, 0.6f), true);
        }
    }

    private static List<Vec3d> jag(Vec3d a, Vec3d b, int segs, double amp, int seed) {
        List<Vec3d> out = new ArrayList<>();
        Vec3d d = b.subtract(a);
        Vec3d s1 = d.crossProduct(VFx.UP);
        if (s1.lengthSquared() < 1e-6) s1 = VFx.X;
        s1 = s1.normalize();
        Vec3d s2 = s1.crossProduct(d).normalize();
        for (int i = 0; i <= segs; i++) {
            double t = i / (double) segs;
            double env = Math.sin(Math.PI * t) * amp;
            Vec3d p = a.add(d.multiply(t));
            if (i > 0 && i < segs) p = p.add(s1.multiply((h(seed + i * 2) - 0.5) * 2 * env)).add(s2.multiply((h(seed + i * 2 + 1) - 0.5) * 2 * env));
            out.add(p);
        }
        return out;
    }

    private static void boltDraw(List<Vec3d> b, float al, double width, int col) {
        int n = b.size();
        float[] w = new float[n], wc = new float[n];
        int[] c = new int[n], cc = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = (float) width * 2.4f;
            c[i] = VFx.a(col, al * 0.35f);
            wc[i] = (float) width * 0.45f;
            cc[i] = VFx.a(0xFFFFFFFF, al);
        }
        VFx.glowRibbon(b, w, c, VFx.CAMERA, null, 4, 1.2f, 1.2f, false);
        VFx.ribbon(b, wc, cc, VFx.CAMERA, null, 0.6f, false);
    }

    /* ------------------------------------------------------------------ soul */

    private static void soul(Kill k, float age) {
        float L = 3f;
        float rise = (float) (Math.pow(age / L, 1.4) * 4.5);
        float al = VFx.smooth(age / 0.35f) * fadeOut(age, L * 0.55f, L * 0.45f);
        Vec3d body = k.pos.add(Math.sin(age * 2.1) * 0.15, k.h * 0.6 + rise, Math.cos(age * 1.7) * 0.15);
        // ghost: a soft glowing core with a tapering tail that sways
        VFx.spark(body, 0.12, k.color, al, true);
        VFx.sprite(body, 0.8, VFx.a(0xFFFFFFFF, 0.1f * al), true);
        List<Vec3d> tail = new ArrayList<>();
        int n = 16;
        float[] w = new float[n];
        int[] c = new int[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) (n - 1); // 0 head .. 1 tip
            tail.add(body.add(Math.sin(age * 4 - t * 5) * 0.18 * t, -t * 0.9, Math.cos(age * 3 - t * 4) * 0.12 * t));
            w[i] = 0.22f * (1 - t) + 0.01f;
            c[i] = VFx.a(VFx.mix(0xFFFFFFFF, k.color, t), al * (1 - t) * 0.7f);
        }
        VFx.glowRibbon(tail, w, c, VFx.CAMERA, null, 3, 0.8f, 0.8f, true);
        VFx.ribbon(tail, w, c, VFx.CAMERA, null, 0.3f, true);
        // wisps spiralling around it
        for (int s = 0; s < 4; s++) {
            List<Vec3d> wp = new ArrayList<>();
            int m = 14;
            float[] ww = new float[m];
            int[] wc = new int[m];
            for (int i = 0; i < m; i++) {
                float t = i / (float) (m - 1);
                double a = age * 3 + s * Math.PI / 2 - t * 2.2;
                double r = 0.35 + 0.25 * t;
                wp.add(body.add(Math.cos(a) * r, -t * 1.2 - 0.1, Math.sin(a) * r));
                ww[i] = 0.035f * (1 - t);
                wc[i] = VFx.a(k.color, al * (1 - t) * 0.8f);
            }
            VFx.ribbon(wp, ww, wc, VFx.CAMERA, null, 0.3f, true);
        }
        // fading silhouette ring on the ground
        float g = fadeOut(age, 0.2f, 1.2f);
        VFx.ring(k.pos.add(0, 0.03, 0), VFx.X, VFx.Z, 0.5 + age * 0.4, 0.12, 48, t -> k.color, 0.4f * g, 0, true);
    }

    /* ------------------------------------------------------------------ shatter (glass shards with physics) */

    private static void shatter(Kill k, float age, float delay, int tint) {
        float a = age - delay;
        if (a < 0) return;
        double ground = k.pos.y + 0.02;
        int count = 46;
        for (int i = 0; i < count; i++) {
            int s = k.seed * 7 + i * 13;
            double sy = h(s) * k.h, sx = (h(s + 1) - 0.5) * k.w, sz = (h(s + 2) - 0.5) * k.w;
            Vec3d p0 = k.pos.add(sx, sy, sz);
            Vec3d out = new Vec3d(sx, 0, sz);
            out = out.lengthSquared() < 1e-6 ? new Vec3d(h(s + 3) - 0.5, 0, h(s + 4) - 0.5) : out;
            out = out.normalize().multiply(1.2 + 2.2 * h(s + 5));
            double vy = 1.5 + 3 * h(s + 6);
            double[] y = bounce(p0.y, vy, ground, a);
            double drag = 1.6;
            double d = (1 - Math.exp(-drag * a)) / drag;
            Vec3d p = new Vec3d(p0.x + out.x * d, y[0], p0.z + out.z * d);
            // tumbling triangle
            double rot = a * (6 + 8 * h(s + 7)) * Math.max(0.15, 1 - y[1] * 0.85);
            double sz2 = 0.07 + 0.12 * h(s + 8);
            Vec3d ax = new Vec3d(Math.cos(rot), Math.sin(rot * 0.7), Math.sin(rot)).normalize();
            Vec3d ay = ax.crossProduct(new Vec3d(h(s + 9) - 0.5, 1, h(s + 10) - 0.5)).normalize();
            if (y[1] > 0.5) { // resting: lie flat
                ax = new Vec3d(Math.cos(h(s) * 6.28), 0, Math.sin(h(s) * 6.28));
                ay = new Vec3d(-ax.z, 0, ax.x);
                p = new Vec3d(p.x, ground + 0.01, p.z);
            }
            float al = VFx.smooth(a / 0.03f) * fadeOut(a, 2.0f, 1.1f);
            Vec3d v1 = p.add(ax.multiply(sz2)), v2 = p.add(ay.multiply(sz2 * 0.9)), v3 = p.subtract(ax.multiply(sz2 * 0.6)).subtract(ay.multiply(sz2 * 0.5));
            // glass: face glint depends on how it faces the camera
            Vec3d nrm = ax.crossProduct(ay);
            float glint = (float) Math.pow(Math.abs(nrm.normalize().dotProduct(VFx.fwd)), 6);
            int face = VFx.a(VFx.mix(tint, 0xFFFFFFFF, glint), al * (0.28f + 0.6f * glint));
            Render3D.drawGradientQuad(v1, v2, v3, v3, face, face, VFx.am(face, 0.5f), VFx.am(face, 0.5f), true);
            int edge = VFx.a(0xFFFFFFFF, al * 0.85f);
            Render3D.drawLine(v1, v2, edge, 1.2f, true);
            Render3D.drawLine(v2, v3, VFx.am(edge, 0.5f), 1f, true);
            if (glint > 0.6f) VFx.spark(v1, 0.02, 0xFFFFFFFF, al * glint, true);
        }
        float fl = (float) Math.exp(-a * 8);
        VFx.sprite(k.pos.add(0, k.h * 0.5, 0), 1.6, VFx.a(0xFFFFFFFF, 0.3f * fl), true);
    }

    /** Height under gravity with one damped bounce; returns {y, restedFraction}. */
    private static double[] bounce(double y0, double vy, double ground, double t) {
        double g = 14;
        // first impact time: y0 + vy t - g t^2 / 2 = ground
        double disc = vy * vy + 2 * g * (y0 - ground);
        double t1 = (vy + Math.sqrt(Math.max(0, disc))) / g;
        if (t < t1) return new double[]{y0 + vy * t - g * t * t / 2, 0};
        double v2 = Math.sqrt(Math.max(0, disc)) * 0.3, tt = t - t1, t2 = 2 * v2 / g;
        if (tt < t2) return new double[]{ground + v2 * tt - g * tt * tt / 2, 0.2};
        return new double[]{ground, 1};
    }

    /* ------------------------------------------------------------------ ink splash */

    private static void ink(Kill k, float age) {
        int ink = 0xFF6E0F1C, inkHi = 0xFFB02035;
        Vec3d g = k.pos.add(0, 0.025, 0);
        float al = fadeOut(age, 2.2f, 1.3f);
        // main splat grows with a little overshoot, made of overlapping lobes
        float grow = VFx.backOut(Math.min(1, age / 0.35f));
        for (int i = 0; i < 9; i++) {
            int s = k.seed + i * 5;
            double an = h(s) * Math.PI * 2, d = i == 0 ? 0 : 0.35 + 0.45 * h(s + 1);
            double r = (i == 0 ? 0.7 : 0.18 + 0.22 * h(s + 2)) * grow;
            Vec3d c = g.add(Math.cos(an) * d * grow, i * 0.0005, Math.sin(an) * d * grow);
            darkDisc(c, VFx.X, VFx.Z, r, 24, ink, 0.9f * al);
        }
        // glossy highlight
        VFx.disc(g.add(-0.15, 0.002, -0.12), VFx.X.multiply(1.2), VFx.Z, 0.12 * grow, 16, VFx.a(inkHi, 0.5f * al), VFx.a(inkHi, 0), true);
        // droplets: ballistic arcs that land as small splats
        for (int i = 0; i < 22; i++) {
            int s = k.seed * 3 + i * 11;
            double an = h(s) * Math.PI * 2, v = 1.5 + 2.5 * h(s + 1), vy = 2 + 3 * h(s + 2);
            Vec3d p0 = k.pos.add(0, k.h * 0.55, 0);
            double tl = (vy + Math.sqrt(vy * vy + 2 * 14 * (p0.y - g.y))) / 14;
            double t = Math.min(age, tl);
            Vec3d p = p0.add(Math.cos(an) * v * t, vy * t - 7 * t * t, Math.sin(an) * v * t);
            double r = 0.035 + 0.04 * h(s + 3);
            if (age < tl) darkDisc(p, VFx.right, VFx.up, r * 1.3, 10, ink, 1f);
            else {
                float sp = VFx.outCubic((float) ((age - tl) / 0.2));
                darkDisc(new Vec3d(p.x, g.y + 0.001 * (i + 2), p.z), VFx.X, VFx.Z, r * (1 + 1.6 * sp), 12, ink, 0.9f * al);
            }
        }
    }

    /* ------------------------------------------------------------------ firework */

    private static void firework(Kill k, float age) {
        float launch = 0.55f;
        Vec3d base = k.pos.add(0, k.h * 0.5, 0);
        double peakH = 5.5;
        if (age < launch) {
            float t = VFx.outCubic(age / launch);
            Vec3d head = base.add(0, peakH * t, 0);
            List<Vec3d> streak = new ArrayList<>();
            int n = 10;
            float[] w = new float[n];
            int[] c = new int[n];
            for (int i = 0; i < n; i++) {
                float q = i / (float) (n - 1);
                streak.add(base.add(Math.sin(q * 9 + age * 20) * 0.03, peakH * Math.max(0, t - (1 - q) * 0.35), 0));
                w[i] = 0.05f * q;
                c[i] = VFx.a(0xFFFFFFFF, q * 0.8f);
            }
            VFx.ribbon(streak, w, c, VFx.CAMERA, null, 0.3f, true);
            VFx.spark(head, 0.05, k.color, 1, true);
            return;
        }
        float a = age - launch;
        Vec3d c = base.add(0, peakH, 0);
        float fl = (float) Math.exp(-a * 6);
        VFx.sprite(c, 3, VFx.a(0xFFFFFFFF, 0.3f * fl), true);
        int n = 70;
        for (int i = 0; i < n; i++) {
            int s = k.seed * 5 + i * 7;
            // fibonacci sphere directions so the burst is even
            double yy = 1 - 2 * (i + 0.5) / n, rr = Math.sqrt(1 - yy * yy), th = i * 2.39996;
            Vec3d dir = new Vec3d(Math.cos(th) * rr, yy, Math.sin(th) * rr);
            double v = 5.5 + h(s) * 1.5, drag = 2.2;
            float hue = (i % 3) / 3f * 0.18f + h(k.seed) ;
            int col = i % 4 == 0 ? 0xFFFFFFFF : VFx.hue(hue, 0.55f);
            float al = fadeOut(a, 0.9f + h(s + 1) * 0.6f, 0.7f);
            float twinkle = a > 1.0f ? (float) (0.5 + 0.5 * Math.sin(a * 30 + i)) : 1;
            // trail: a few points behind the spark
            List<Vec3d> tr = new ArrayList<>();
            int m = 6;
            float[] w = new float[m];
            int[] cc = new int[m];
            for (int j = 0; j < m; j++) {
                double tt = Math.max(0, a - (m - 1 - j) * 0.04);
                double d = v / drag * (1 - Math.exp(-drag * tt));
                tr.add(c.add(dir.multiply(d)).add(0, -1.4 * tt * tt, 0));
                w[j] = 0.03f * j / (m - 1);
                cc[j] = VFx.a(col, al * j / (m - 1) * 0.6f);
            }
            VFx.ribbon(tr, w, cc, VFx.CAMERA, null, 0.3f, true);
            VFx.spark(tr.get(m - 1), 0.025, col, al * twinkle, true);
        }
    }

    /* ------------------------------------------------------------------ black hole */

    private static void blackHole(Kill k, float age) {
        // hovers over the body and swallows it: the victim is pulled into the void
        Vec3d c = k.pos.add(0, k.h + 0.9, 0);
        float L = 2.6f;
        float open = VFx.backOut(Math.min(1, age / 0.5f));
        float close = age > L - 0.55f ? 1 - VFx.smooth((age - (L - 0.55f)) / 0.35f) : 1;
        float s = open * close;
        // accretion disc: tilted bright rings spinning fast
        Vec3d u = new Vec3d(1, 0.25, 0).normalize(), v = new Vec3d(0, 0.15, 1).normalize();
        for (int r = 0; r < 4; r++) {
            double rad = (0.45 + r * 0.2) * s;
            int rr = r;
            VFx.ring(c, u, v, rad, 0.06 + r * 0.03, 72, t -> VFx.mix(0xFFFFFFFF, k.color, rr / 4f + t * 0.3f), (0.7f - r * 0.13f) * s, (float) (age * (6 - r)), true);
        }
        // the void: a camera facing dark disc with a bright photon ring
        darkDisc(c, VFx.right, VFx.up, 0.46 * s, 32, 0xFF000000, s);
        VFx.sprite(c, 1.1 * s, VFx.a(k.color, 0.18f * s), true);
        VFx.ring(c, VFx.right, VFx.up, 0.44 * s, 0.035, 64, t -> 0xFFFFFFFF, 0.9f * s, 0, true);
        // matter spiralling in
        for (int i = 0; i < 40; i++) {
            int sd = k.seed + i * 19;
            float ph = (float) ((age * (0.5 + h(sd) * 0.4) + h(sd + 1)) % 1.0);
            double r = (2.4 + h(sd + 2)) * (1 - ph) * (1 - ph) + 0.4;
            double an = h(sd + 3) * Math.PI * 2 + ph * 9;
            Vec3d p = c.add(u.multiply(Math.cos(an) * r)).add(v.multiply(Math.sin(an) * r)).add(0, (h(sd + 4) - 0.5) * 0.4 * (1 - ph), 0);
            VFx.spark(p, 0.02, k.color, VFx.smooth(ph / 0.2f) * (1 - ph) * s * 1.4f, true);
        }
        // implosion flash
        float fl = age > L - 0.25f ? (float) Math.exp(-(age - (L - 0.25f)) * 14) : 0;
        if (fl > 0.01f) VFx.sprite(c, 2.2, VFx.a(0xFFFFFFFF, 0.6f * fl), true);
    }

    /* ------------------------------------------------------------------ angel wings */

    private static void wings(Kill k, float age) {
        float L = 3.2f;
        float rise = (float) Math.pow(Math.max(0, age - 0.4f) / (L - 0.4f), 1.6) * 5;
        float al = VFx.smooth(age / 0.4f) * fadeOut(age, L * 0.6f, L * 0.4f);
        Vec3d c = k.pos.add(0, k.h * 0.75 + rise, 0);
        double yaw = Math.toRadians(k.yaw);
        Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw)), side = new Vec3d(-fwd.z, 0, fwd.x);
        float unfold = VFx.backOut(Math.min(1, age / 0.6f));
        float flap = (float) Math.sin(age * 5) * 0.25f;
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            for (int f = 0; f < 7; f++) {
                float t = f / 6f;
                double spread = (0.25 + t * 1.2) * unfold;
                double lift = (0.55 - t * 0.85 + flap * (0.4 + t)) * unfold;
                Vec3d root = c.add(side.multiply(sgn * 0.12)).subtract(fwd.multiply(0.1));
                Vec3d tip = root.add(side.multiply(sgn * spread)).add(0, lift, 0).subtract(fwd.multiply(0.15 * t));
                List<Vec3d> feather = List.of(root, root.lerp(tip, 0.5).add(0, 0.08 * (1 - t), 0), tip);
                float len = 1 - t * 0.25f;
                float[] w = {0.02f, 0.09f * len, 0.012f};
                int col = VFx.mix(0xFFFFFFFF, k.color, t * 0.5f);
                int[] cs = {VFx.a(col, al * 0.8f), VFx.a(col, al * 0.75f), VFx.a(0xFFFFFFFF, al * 0.4f)};
                VFx.glowRibbon(feather, w, cs, VFx.CAMERA, null, 2, 1.2f, 0.5f, true);
                VFx.ribbon(feather, w, cs, VFx.CAMERA, null, 0.4f, true);
            }
        }
        // halo
        VFx.ring(c.add(0, 0.55, 0), VFx.X, VFx.Z, 0.28, 0.035, 48, t -> 0xFFFFE9A8, 0.9f * al, 0, true);
        VFx.spark(c, 0.06, 0xFFFFFFFF, al * 0.7f, true);
        // a beam of light from above
        float beam = al * 0.25f;
        VFx.ribbon(List.of(c.add(0, 9, 0), c.add(0, -k.h * 0.7 - rise, 0)), new float[]{0.9f, 0.6f}, new int[]{VFx.a(0xFFFFFFFF, 0), VFx.a(0xFFFFFFFF, beam)}, VFx.CAMERA, null, 0, true);
        for (int i = 0; i < 6; i++) {
            int s = k.seed + i * 9;
            float ph = (float) ((age * 0.6 + h(s)) % 1);
            VFx.spark(c.add((h(s + 1) - 0.5) * 1.6, -1 + ph * 2.5, (h(s + 2) - 0.5) * 1.6), 0.02, 0xFFFFFFFF, al * (1 - ph), true);
        }
    }

    /* ------------------------------------------------------------------ pixel disintegrate */

    private static void disintegrate(Kill k, float age) {
        int cols = 7, rows = 16;
        double cell = k.h / rows;
        double yaw = Math.toRadians(k.yaw);
        Vec3d side = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                for (int layer = 0; layer < 2; layer++) {
                    int s = k.seed + (r * cols + c) * 3 + layer * 997;
                    // body silhouette: narrow head, wide torso, two legs
                    double fy = r / (double) rows;
                    double halfW = fy > 0.75 ? 0.32 : fy > 0.38 ? 0.5 : 0.42;
                    double fx = (c + 0.5) / cols - 0.5;
                    if (Math.abs(fx) > halfW * 0.9) continue;
                    if (fy < 0.38 && Math.abs(fx) < 0.05) continue;
                    // top dissolves first, sweeping down
                    float delay = (float) ((1 - fy) * 1.1 + h(s) * 0.25);
                    float a = age - delay;
                    Vec3d home = k.pos.add(side.multiply(fx * k.w * 1.3)).add(0, (r + 0.5) * cell, 0)
                            .add(new Vec3d(-side.z, 0, side.x).multiply((layer - 0.5) * k.w * 0.4));
                    float al;
                    Vec3d p;
                    if (a < 0) {
                        al = 0.55f * VFx.smooth(age / 0.15f);
                        p = home;
                    } else {
                        double drift = 1 - Math.exp(-a * 1.5);
                        p = home.add((h(s + 1) - 0.3) * 1.4 * drift, a * (0.6 + h(s + 2) * 0.8) + a * a * 0.4, (h(s + 3) - 0.5) * 1.4 * drift);
                        al = 0.75f * (1 - VFx.smooth(a / (0.9f + h(s + 4) * 0.5f)));
                    }
                    if (al < 0.01f) continue;
                    double q = cell * 0.42 * (a < 0 ? 1 : Math.max(0.2, 1 - a * 0.6));
                    int col = VFx.mix(k.color, 0xFFFFFFFF, h(s + 5) * 0.6f + (a > 0 ? 0.3f : 0));
                    Vec3d R = VFx.right.multiply(q), U = VFx.up.multiply(q);
                    int cc = VFx.a(col, al);
                    Render3D.drawQuad(p.subtract(R).subtract(U), p.add(R).subtract(U), p.add(R).add(U), p.subtract(R).add(U), cc, true);
                }
            }
        }
        // a bright scan line sweeping down the body
        double scan = k.h * (1 - Math.min(1, age / 1.25));
        float sa = age < 1.35f ? 1 : 0;
        VFx.ring(k.pos.add(0, scan, 0), VFx.X, VFx.Z, k.w * 0.85, 0.06, 40, t -> 0xFFFFFFFF, 0.8f * sa, 0, true);
    }

    /* ------------------------------------------------------------------ ice freeze + crack */

    private static void ice(Kill k, float age) {
        float freeze = VFx.outCubic(Math.min(1, age / 0.9f));
        float shatterAt = 1.8f;
        int frost = 0xFFCFE8FF;
        if (age < shatterAt + 0.05f) {
            double topY = k.h * 1.05 * freeze;
            double r = k.w * 0.75;
            // hexagonal crystal prism growing from the feet up
            int sides = 6;
            for (int i = 0; i < sides; i++) {
                double a0 = i * Math.PI * 2 / sides + 0.3, a1 = (i + 1) * Math.PI * 2 / sides + 0.3;
                Vec3d b0 = k.pos.add(Math.cos(a0) * r, 0, Math.sin(a0) * r), b1 = k.pos.add(Math.cos(a1) * r, 0, Math.sin(a1) * r);
                Vec3d t0 = b0.add(0, topY, 0), t1 = b1.add(0, topY, 0);
                Vec3d n = new Vec3d(Math.cos((a0 + a1) / 2), 0, Math.sin((a0 + a1) / 2));
                float glint = (float) Math.pow(Math.max(0, -n.dotProduct(VFx.fwd)), 3);
                int cb = VFx.a(frost, 0.28f + 0.2f * glint), ct = VFx.a(0xFFFFFFFF, 0.18f + 0.45f * glint);
                Render3D.drawGradientQuad(b0, b1, t1, t0, cb, cb, ct, ct, true);
                Render3D.drawLine(b0, t0, VFx.a(0xFFFFFFFF, 0.7f), 1.2f, true);
                Render3D.drawLine(t0, t1, VFx.a(0xFFFFFFFF, 0.8f), 1.2f, true);
            }
            // cracks spreading over the surface before it breaks
            float crack = VFx.smooth((age - 1.0f) / 0.75f);
            if (crack > 0) {
                for (int c = 0; c < 9; c++) {
                    int s = k.seed + c * 23;
                    double an = h(s) * Math.PI * 2, y = k.h * (0.2 + 0.7 * h(s + 1));
                    Vec3d p = k.pos.add(Math.cos(an) * r * 1.01, y, Math.sin(an) * r * 1.01);
                    for (int seg = 0; seg < 4 && seg < crack * 4; seg++) {
                        double dan = (h(s + seg * 2 + 2) - 0.5) * 0.9, dy = (h(s + seg * 2 + 3) - 0.5) * 0.45;
                        an += dan / r * 0.3;
                        Vec3d q = k.pos.add(Math.cos(an) * r * 1.01, Math.max(0.05, Math.min(topY, p.y - k.pos.y + dy)), Math.sin(an) * r * 1.01);
                        Render3D.drawLine(p, q, VFx.a(0xFFFFFFFF, 0.95f), 1.6f, true);
                        p = q;
                    }
                }
            }
            // frost creeping over the ground
            VFx.disc(k.pos.add(0, 0.025, 0), VFx.X, VFx.Z, (k.w + 0.9) * freeze, 36, VFx.a(frost, 0.4f), VFx.a(frost, 0), true);
            for (int i = 0; i < 10; i++) {
                float tw = (float) Math.pow(0.5 + 0.5 * Math.sin(age * 6 + i * 2.3), 8);
                int s = k.seed + i * 3;
                VFx.spark(k.pos.add(Math.cos(h(s) * 6.28) * r, h(s + 1) * topY, Math.sin(h(s) * 6.28) * r), 0.02, 0xFFFFFFFF, tw, true);
            }
        } else {
            VFx.disc(k.pos.add(0, 0.025, 0), VFx.X, VFx.Z, k.w + 0.9, 36, VFx.a(frost, 0.4f * fadeOut(age, shatterAt, 1.4f)), VFx.a(frost, 0), true);
        }
        shatter(k, age, shatterAt, frost);
    }

    /* ------------------------------------------------------------------ confetti */

    private static void confetti(Kill k, float age) {
        int[] pal = {0xFFFF5A8C, 0xFFFFD25A, 0xFF5AE1FF, 0xFF9B7BFF, 0xFF7CFFA8, 0xFFFFFFFF};
        Vec3d o = k.pos.add(0, k.h * 0.8, 0);
        for (int i = 0; i < 90; i++) {
            int s = k.seed + i * 17;
            double an = h(s) * Math.PI * 2, el = 0.5 + h(s + 1) * 0.9;
            double v = 4 + 3.5 * h(s + 2), drag = 2.6, term = 0.9;
            // strong drag + terminal velocity flutter: shoots out, then floats down
            double d = v * Math.cos(el) / drag * (1 - Math.exp(-drag * age));
            double vy0 = v * Math.sin(el);
            double y = (vy0 + term / drag) / drag * (1 - Math.exp(-drag * age)) - term * age;
            double fl = Math.sin(age * (5 + 4 * h(s + 3)) + i) * 0.15 * Math.min(1, age);
            Vec3d p = o.add(Math.cos(an) * d + fl, y, Math.sin(an) * d - fl);
            if (p.y < k.pos.y + 0.02) p = new Vec3d(p.x, k.pos.y + 0.02, p.z);
            float al = VFx.smooth(age / 0.05f) * fadeOut(age, 2.6f, 1f);
            double rot = age * (8 + 10 * h(s + 4));
            double flip = Math.cos(age * (6 + 6 * h(s + 5)));
            Vec3d ax = new Vec3d(Math.cos(rot), 0, Math.sin(rot)).multiply(0.06);
            Vec3d ay = new Vec3d(-Math.sin(rot) * flip, Math.sqrt(Math.max(0, 1 - flip * flip)), Math.cos(rot) * flip).multiply(0.035);
            int col = pal[i % pal.length];
            int c = VFx.a(VFx.mix(col, 0xFFFFFFFF, (float) Math.abs(flip) * 0.35f), al);
            Render3D.drawQuad(p.subtract(ax).subtract(ay), p.add(ax).subtract(ay), p.add(ax).add(ay), p.subtract(ax).add(ay), c, true);
        }
        float pop = (float) Math.exp(-age * 7);
        VFx.sprite(o, 1.4, VFx.a(0xFFFFFFFF, 0.35f * pop), true);
    }

    /* ------------------------------------------------------------------ tornado */

    private static void tornado(Kill k, float age) {
        float L = 3f;
        float al = VFx.smooth(age / 0.4f) * fadeOut(age, L * 0.6f, L * 0.4f);
        double H = 3.6 * VFx.outCubic(Math.min(1, age / 0.8f));
        // funnel bands: spiralling ribbons whose radius widens with height
        for (int b = 0; b < 5; b++) {
            List<Vec3d> pts = new ArrayList<>();
            int n = 40;
            float[] w = new float[n];
            int[] c = new int[n];
            for (int i = 0; i < n; i++) {
                float t = i / (float) (n - 1);
                double y = t * H, r = 0.15 + t * t * 1.4;
                double an = age * 7 + b * Math.PI * 2 / 5 + t * 6;
                pts.add(k.pos.add(Math.cos(an) * r, y, Math.sin(an) * r));
                w[i] = 0.03f + 0.07f * t;
                c[i] = VFx.a(VFx.mix(0xFFFFFFFF, k.color, t), al * (float) Math.sin(Math.PI * t) * 0.55f);
            }
            VFx.ribbon(pts, w, c, VFx.CAMERA, null, 0.25f, true);
        }
        // debris swirling up
        for (int i = 0; i < 36; i++) {
            int s = k.seed + i * 13;
            float ph = (float) ((age * (0.35 + 0.3 * h(s)) + h(s + 1)) % 1);
            double y = ph * H, r = 0.2 + ph * ph * 1.5 + h(s + 2) * 0.2;
            double an = age * (6 + 3 * h(s + 3)) + h(s + 4) * 6.28;
            VFx.spark(k.pos.add(Math.cos(an) * r, y, Math.sin(an) * r), 0.022, k.color, al * (float) Math.sin(Math.PI * ph), true);
        }
        VFx.disc(k.pos.add(0, 0.03, 0), VFx.X, VFx.Z, 1.2, 32, VFx.a(k.color, 0.25f * al), VFx.a(k.color, 0), true);
        VFx.ring(k.pos.add(0, 0.03, 0), VFx.X, VFx.Z, 0.9 + 0.2 * Math.sin(age * 4), 0.1, 48, t -> 0xFFFFFFFF, 0.4f * al, (float) age * 3, true);
    }
}

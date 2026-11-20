package tenacity.modules;

import net.minecraft.block.BlockState;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.Nvg;
import tenacity.util.Projection;
import voided.util.render.Render3D;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static tenacity.modules.PremiumVisuals.*;

/**
 * Christmas visuals. Same premium base as {@link PremiumVisuals}: eased fade in / out (lingers), tick-delta
 * interpolated body-anchored frames, lit 3D geometry (diffuse + specular + rim) and soft additive 2D glow.
 */
final class ChristmasVisuals {
    private ChristmasVisuals() {
    }

    static List<Module> create() {
        return List.of(new SantaHat(), new Snowfall(), new XmasLights(), new CandyCaneTrail(), new GiftBurst(), new SnowGlobe(),
                new Antlers(), new JingleSparkles(), new NorthernStar(), new FrostyBreath(), new OrnamentOrbit(), new Mistletoe(),
                new GingerbreadSteps(), new HollyWreath(), new FestiveKill());
    }

    /* ================================================================== shared 3D / 2D helpers */

    static final class G {
        private G() {
        }

        static final Vec3d LIGHT = new Vec3d(0.35, 0.88, 0.32).normalize();

        interface Surf {
            Vec3d at(double u, double v);
        }

        interface Shade {
            int at(double u, double v, Vec3d p, Vec3d n);
        }

        /** Local frame: origin and (scaled) axes. */
        record F(Vec3d o, Vec3d x, Vec3d y, Vec3d z) {
            Vec3d p(double a, double b, double c) {
                return o.add(x.multiply(a)).add(y.multiply(b)).add(z.multiply(c));
            }

            Vec3d d(double a, double b, double c) {
                return x.multiply(a).add(y.multiply(b)).add(z.multiply(c));
            }

            static F head(BodyAnchor ba) {
                double s = ba.scale;
                return new F(ba.neck(), ba.hRight.multiply(s), ba.hUp.multiply(s), ba.hFwd.multiply(s));
            }

            static F body(BodyAnchor ba) {
                double s = ba.scale;
                return new F(ba.origin, ba.right.multiply(s), ba.up.multiply(s), ba.fwd.multiply(s));
            }

            static F world(Vec3d o, double s) {
                return new F(o, new Vec3d(s, 0, 0), new Vec3d(0, s, 0), new Vec3d(0, 0, s));
            }

            /** Upright frame facing {@code yaw}. */
            static F yaw(Vec3d o, double yawRad, double s) {
                Vec3d z = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));
                Vec3d x = new Vec3d(-z.z, 0, z.x);
                return new F(o, x.multiply(s), new Vec3d(0, s, 0), z.multiply(s));
            }
        }

        static int clamp255(double v) {
            return v < 0 ? 0 : v > 255 ? 255 : (int) v;
        }

        static int scale(int c, double k) {
            return c & 0xFF000000 | clamp255(((c >> 16) & 255) * k) << 16 | clamp255(((c >> 8) & 255) * k) << 8 | clamp255((c & 255) * k);
        }

        /** Lambert + Blinn specular + rim. Colour alpha is kept. */
        static int lit(int c, Vec3d p, Vec3d n, float amb, float gloss) {
            Vec3d v = Projection.camera().subtract(p);
            double vl = v.length();
            v = vl < 1e-6 ? LIGHT : v.multiply(1 / vl);
            double d = Math.max(0, n.dotProduct(LIGHT));
            double rim = Math.pow(1 - Math.abs(n.dotProduct(v)), 3) * 0.22;
            double k = amb + (1 - amb) * d + rim;
            double sp = 0;
            if (gloss > 0) {
                Vec3d h = LIGHT.add(v).normalize();
                sp = Math.pow(Math.max(0, n.dotProduct(h)), 32) * gloss * 255;
            }
            return c & 0xFF000000 | clamp255(((c >> 16) & 255) * k + sp) << 16 | clamp255(((c >> 8) & 255) * k + sp) << 8 | clamp255((c & 255) * k + sp);
        }

        /** As {@link #lit} but the normal is flipped toward the camera (thin two sided surfaces). */
        static int lit2(int c, Vec3d p, Vec3d n, float amb, float gloss) {
            if (n.dotProduct(Projection.camera().subtract(p)) < 0) n = n.multiply(-1);
            return lit(c, p, n, amb, gloss);
        }

        static Vec3d[][] build(Surf s, int nu, int nv) {
            Vec3d[][] P = new Vec3d[nu + 1][nv + 1];
            for (int i = 0; i <= nu; i++) for (int j = 0; j <= nv; j++) P[i][j] = s.at(i == nu ? 0 : i / (double) nu, j / (double) nv);
            return P;
        }

        /** Draws a grid that is closed in u (P[nu] == P[0]); normals point away from the ring centres. */
        static void grid(Vec3d[][] P, Shade sh, boolean depth) {
            int nu = P.length - 1, nv = P[0].length - 1;
            Vec3d[] ctr = new Vec3d[nv + 1];
            Vec3d gc = Vec3d.ZERO;
            for (int j = 0; j <= nv; j++) {
                double x = 0, y = 0, z = 0;
                for (int i = 0; i < nu; i++) {
                    x += P[i][j].x;
                    y += P[i][j].y;
                    z += P[i][j].z;
                }
                ctr[j] = new Vec3d(x / nu, y / nu, z / nu);
                gc = gc.add(ctr[j]);
            }
            gc = gc.multiply(1.0 / (nv + 1));
            int[][] C = new int[nu + 1][nv + 1];
            for (int i = 0; i <= nu; i++)
                for (int j = 0; j <= nv; j++) {
                    Vec3d du = P[(i + 1) % nu][j].subtract(P[(i - 1 + nu) % nu][j]);
                    Vec3d dv = P[i][Math.min(j + 1, nv)].subtract(P[i][Math.max(j - 1, 0)]);
                    Vec3d out = P[i][j].subtract(ctr[j]);
                    if (out.lengthSquared() < 1e-12) out = P[i][j].subtract(gc);
                    Vec3d n = du.crossProduct(dv);
                    if (n.lengthSquared() < 1e-16) n = out;
                    if (n.dotProduct(out) < 0) n = n.multiply(-1);
                    double l = n.length();
                    n = l < 1e-12 ? new Vec3d(0, 1, 0) : n.multiply(1 / l);
                    C[i][j] = sh.at(i / (double) nu, j / (double) nv, P[i][j], n);
                }
            for (int i = 0; i < nu; i++)
                for (int j = 0; j < nv; j++)
                    quad(P[i][j], P[i + 1][j], P[i + 1][j + 1], P[i][j + 1], C[i][j], C[i + 1][j], C[i + 1][j + 1], C[i][j + 1], depth);
        }

        static void surface(Surf s, int nu, int nv, Shade sh, boolean depth) {
            grid(build(s, nu, nv), sh, depth);
        }

        /** Ellipsoid in frame f (v: bottom 0 -> top 1). */
        static Surf ellip(F f, Vec3d c, double rx, double ry, double rz) {
            return (u, v) -> {
                double th = u * TAU, ph = (v - 0.5) * Math.PI;
                return c.add(f.d(Math.cos(ph) * Math.cos(th) * rx, Math.sin(ph) * ry, Math.cos(ph) * Math.sin(th) * rz));
            };
        }

        static void ball(Vec3d c, double r, int seg, int col, float amb, float gloss, float al, boolean depth) {
            F f = F.world(Vec3d.ZERO, 1);
            surface(ellip(f, c, r, r, r), seg, Math.max(4, seg / 2), (u, v, p, n) -> a(lit(col, p, n, amb, gloss), al), depth);
        }

        static void torus(F f, Vec3d c, double R, double r, int nu, int nv, Shade sh, boolean depth) {
            torus(f, c, R, r, nu, nv, null, sh, depth);
        }

        interface Rad {
            double at(double u, double v);
        }

        static void torus(F f, Vec3d c, double R, double r, int nu, int nv, Rad mod, Shade sh, boolean depth) {
            // u around the main ring, v around the tube; the grid is closed in v so build it with v as "u"
            Vec3d[][] P = new Vec3d[nv + 1][nu + 1];
            for (int j = 0; j <= nu; j++) {
                double U = (j == nu ? 0 : j / (double) nu) * TAU;
                for (int i = 0; i <= nv; i++) {
                    double V = (i == nv ? 0 : i / (double) nv) * TAU;
                    double rr = r * (mod == null ? 1 : mod.at(j / (double) nu, i / (double) nv));
                    P[i][j] = c.add(f.d(Math.cos(U) * (R + rr * Math.cos(V)), rr * Math.sin(V), Math.sin(U) * (R + rr * Math.cos(V))));
                }
            }
            // the ring centre of each tube cross section is its own centre (normals point out of the tube)
            int[][] C = new int[nv + 1][nu + 1];
            for (int j = 0; j <= nu; j++) {
                double U = (j == nu ? 0 : j / (double) nu) * TAU;
                Vec3d cc = c.add(f.d(Math.cos(U) * R, 0, Math.sin(U) * R));
                for (int i = 0; i <= nv; i++) {
                    Vec3d n = P[i][j].subtract(cc);
                    double l = n.length();
                    n = l < 1e-9 ? f.y.normalize() : n.multiply(1 / l);
                    C[i][j] = sh.at(j / (double) nu, i / (double) nv, P[i][j], n);
                }
            }
            for (int i = 0; i < nv; i++)
                for (int j = 0; j < nu; j++)
                    quad(P[i][j], P[i + 1][j], P[i + 1][j + 1], P[i][j + 1], C[i][j], C[i + 1][j], C[i + 1][j + 1], C[i][j + 1], depth);
        }

        /** Tube along a centre line with radii, ring frames parallel transported (no twisting). */
        static void tube(Vec3d[] c, double[] r, int nu, Vec3d ref, Rad mod, Shade sh, boolean depth) {
            int nv = c.length - 1;
            if (nv < 1) return;
            Vec3d[][] P = new Vec3d[nu + 1][nv + 1];
            Vec3d A = null;
            for (int j = 0; j <= nv; j++) {
                Vec3d T = c[Math.min(j + 1, nv)].subtract(c[Math.max(j - 1, 0)]);
                if (T.lengthSquared() < 1e-14) T = ref.crossProduct(new Vec3d(1, 0, 0));
                T = T.normalize();
                if (A == null) {
                    A = ref.subtract(T.multiply(ref.dotProduct(T)));
                    if (A.lengthSquared() < 1e-10) A = T.crossProduct(new Vec3d(0, 1, 0));
                    if (A.lengthSquared() < 1e-10) A = T.crossProduct(new Vec3d(1, 0, 0));
                } else A = A.subtract(T.multiply(A.dotProduct(T)));
                A = A.normalize();
                Vec3d B = T.crossProduct(A);
                for (int i = 0; i <= nu; i++) {
                    double th = (i == nu ? 0 : i / (double) nu) * TAU;
                    double rr = r[j] * (mod == null ? 1 : mod.at(i / (double) nu, j / (double) nv));
                    P[i][j] = c[j].add(A.multiply(Math.cos(th) * rr)).add(B.multiply(Math.sin(th) * rr));
                }
            }
            grid(P, sh, depth);
        }

        static Vec3d bez(Vec3d a, Vec3d b, Vec3d c, Vec3d d, double t) {
            double u = 1 - t;
            return a.multiply(u * u * u).add(b.multiply(3 * u * u * t)).add(c.multiply(3 * u * t * t)).add(d.multiply(t * t * t));
        }

        /**
         * A leaf: raised midrib, curled edges, optional holly spikes. dir / side are unit vectors in the leaf plane.
         */
        static void leaf(Vec3d base, Vec3d dir, Vec3d side, double len, double w, int spikes, int cMid, int cEdge, float al, boolean depth) {
            Vec3d nrm = dir.crossProduct(side).normalize();
            int n = spikes > 0 ? spikes * 4 : 8;
            Vec3d[] mid = new Vec3d[n + 1], L = new Vec3d[n + 1], R = new Vec3d[n + 1];
            for (int k = 0; k <= n; k++) {
                double s = k / (double) n;
                double ww = Math.sin(Math.PI * Math.pow(s, 0.8)) * w;
                if (spikes > 0) {
                    double ph = s * spikes;
                    double f = ph - Math.floor(ph);
                    ww *= 0.72 + 0.42 * Math.pow(1 - Math.abs(f - 0.5) * 2, 3);
                }
                Vec3d m = base.add(dir.multiply(len * s));
                mid[k] = m.add(nrm.multiply(w * 0.22 * Math.sin(Math.PI * s)));
                L[k] = m.add(side.multiply(ww)).add(nrm.multiply(-w * 0.06));
                R[k] = m.add(side.multiply(-ww)).add(nrm.multiply(-w * 0.06));
            }
            Vec3d nl = nrm.add(side.multiply(0.4)).normalize(), nr = nrm.add(side.multiply(-0.4)).normalize();
            for (int k = 0; k < n; k++) {
                int m0 = a(lit2(cMid, mid[k], nrm, 0.45f, 0.7f), al), m1 = a(lit2(cMid, mid[k + 1], nrm, 0.45f, 0.7f), al);
                int l0 = a(lit2(cEdge, L[k], nl, 0.4f, 0.4f), al), l1 = a(lit2(cEdge, L[k + 1], nl, 0.4f, 0.4f), al);
                int r0 = a(lit2(cEdge, R[k], nr, 0.4f, 0.4f), al), r1 = a(lit2(cEdge, R[k + 1], nr, 0.4f, 0.4f), al);
                quad(mid[k], mid[k + 1], L[k + 1], L[k], m0, m1, l1, l0, depth);
                quad(mid[k], mid[k + 1], R[k + 1], R[k], m0, m1, r1, r0, depth);
            }
        }

        /** A flat polygon fan (ground decals). */
        static void fan(Vec3d c, Vec3d[] ring, int inner, int outer, boolean depth) {
            for (int i = 0; i < ring.length; i++) tri3(c, ring[i], ring[(i + 1) % ring.length], inner, outer, outer, depth);
        }

        /* ---- 2D */

        static float fadeNear(Vec3d p) {
            return smooth((float) ((p.distanceTo(Projection.camera()) - 0.35) / 0.9));
        }

        static void soft(float x, float y, float r, int col, float al) {
            if (al <= 0.003f || r <= 0.2f) return;
            Nvg.glowDisc(x, y, r, a(col, al), a(col, 0));
        }

        /** Six armed ice crystal (lines with side branches) and a soft core. */
        static void crystal(float x, float y, float r, float rot, int col, float al) {
            if (al <= 0.004f || r < 0.6f) return;
            float w = Math.max(0.8f, r * 0.13f);
            for (int k = 0; k < 6; k++) {
                double an = rot + k * Math.PI / 3;
                float cx = (float) Math.cos(an), cy = (float) Math.sin(an);
                float ex = x + cx * r, ey = y + cy * r;
                Nvg.lineGradient(x, y, ex, ey, w, a(col, al), a(col, al * 0.55f));
                if (r > 3.5f) for (float f : new float[]{0.5f, 0.75f}) {
                    float bx = x + cx * r * f, by = y + cy * r * f, bl = r * (0.9f - f) * 0.9f;
                    for (int sgn = -1; sgn <= 1; sgn += 2) {
                        double ba = an + sgn * 0.75;
                        Nvg.line(bx, by, bx + (float) Math.cos(ba) * bl, by + (float) Math.sin(ba) * bl, w * 0.8f, a(col, al * 0.7f));
                    }
                }
            }
            soft(x, y, r * 1.6f, col, al * 0.18f);
        }

        static int pick(int[] cols, int i) {
            return cols[Math.floorMod(i, cols.length)];
        }

        static float noise(double x) {
            return (float) (Math.sin(x) * 0.5 + Math.sin(x * 2.13 + 1.7) * 0.3 + Math.sin(x * 4.37 + 0.3) * 0.2);
        }

        static double frac(double v) {
            return v - Math.floor(v);
        }
    }

    /** Your kills: entities you hit that die within 4 s. Each consumer keeps the last sequence number it handled. */
    static final class KillWatch {
        record Kill(Vec3d pos, double h, double w, double t, int seq) {
        }

        static final List<Kill> events = new ArrayList<>();
        private static final Map<Integer, Long> recent = new HashMap<>();
        private static long seenHit, devAt;
        static int seq;

        private KillWatch() {
        }

        static void poll() {
            var mc = MC();
            if (mc.world == null) return;
            long now = System.currentTimeMillis();
            if (VisualModules.lastHit != seenHit) {
                seenHit = VisualModules.lastHit;
                if (VisualModules.lastTarget != null) recent.put(VisualModules.lastTarget.getId(), now);
                if (seenHit > now + 1000 && System.getenv("TENACITY_FESTKILL") != null) devAt = now + 1500;
            }
            for (Iterator<Map.Entry<Integer, Long>> it = recent.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                Entity e = mc.world.getEntityById(en.getKey());
                if (e == null || now - en.getValue() > 4000) {
                    it.remove();
                    continue;
                }
                boolean dead = e instanceof LivingEntity le ? (le.isDead() || le.getHealth() <= 0) : e.isRemoved();
                if (dead) {
                    it.remove();
                    fire(e);
                }
            }
            if (devAt != 0 && now > devAt && VisualModules.lastTarget != null) {
                devAt = now + 3200;
                fire(VisualModules.lastTarget);
            }
        }

        static void fire(Entity e) {
            events.add(new Kill(e.getEntityPos(), e.getHeight(), e.getWidth(), System.nanoTime() / 1e9, ++seq));
            if (events.size() > 16) events.remove(0);
        }
    }

    static List<PlayerEntity> near(PV m, Setting.Bool others, double range) {
        List<PlayerEntity> l = new ArrayList<>();
        var mc = MC();
        if (thirdPerson()) l.add(mc.player);
        if (others != null && others.on())
            for (AbstractClientPlayerEntity p : mc.world.getPlayers())
                if (p != mc.player && p.isAlive() && !p.isInvisible() && p.squaredDistanceTo(mc.player) < range * range) l.add(p);
        return l;
    }

    /* ================================================================== 1. santa hat */

    static final class SantaHat extends PV {
        private final Setting.Num size = num("Size", 1, 0.6, 1.6, 0.05);
        private final Setting.Num droop = num("Droop", 1, 0, 2, 0.05);
        private final Setting.Bool glow = bool("Pom Glow", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = mode("Color", "Classic", "Classic", "Green", "Blue", "Pink", "Gold", "Black");
        private final Map<Integer, double[]> sway = new HashMap<>();
        private final List<Vec3d> poms = new ArrayList<>();

        SantaHat() {
            super("Santa Hat", "A velvet Santa hat with a fluffy fur rim and pom-pom that sways as you move");
        }

        private int base() {
            return switch (color.get()) {
                case "Green" -> 0xFF17803C;
                case "Blue" -> 0xFF2350C0;
                case "Pink" -> 0xFFE0508A;
                case "Gold" -> 0xFFD2A21E;
                case "Black" -> 0xFF26222C;
                default -> 0xFFC8102E;
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            poms.clear();
            for (PlayerEntity p : targets(others)) hat(p, td, t, dt, al);
        }

        private void hat(PlayerEntity p, float td, double t, float dt, float al) {
            BodyAnchor ba = BodyAnchor.of(p, td);
            G.F f = G.F.head(ba);
            double[] sw = sway.computeIfAbsent(p.getId(), k -> new double[2]);
            double tx = MathHelper.clamp(-ba.vel.dotProduct(ba.hRight) * 3.2, -0.9, 0.9), tz = MathHelper.clamp(-ba.vel.dotProduct(ba.hFwd) * 3.2, -0.9, 0.9);
            float k = ease(dt, 4.5f);
            sw[0] += (tx - sw[0]) * k;
            sw[1] += (tz - sw[1]) * k;
            double s = size.f() * (0.35 + 0.65 * outBack(al)), dr = droop.f();
            double by = BodyAnchor.HEAD - 0.075, sx = sw[0] + 0.06 * Math.sin(t * 1.1 + p.getId()), sz = sw[1];
            int nv = 18;
            Vec3d[] c = new Vec3d[nv + 1];
            double[] r = new double[nv + 1];
            for (int j = 0; j <= nv; j++) {
                double v = j / (double) nv, v2 = v * v;
                c[j] = f.p(sx * 0.32 * v2 * s, by + (0.47 * v - dr * 0.27 * v2 * v) * s, (-dr * 0.30 * v2 + sz * 0.30 * v2) * s + 0.02 * Math.sin(t * 1.4) * v2);
                r[j] = (0.292 * Math.pow(1 - v, 1.15) + 0.022) * s * ba.scale;
            }
            int col = base();
            G.tube(c, r, 22, f.z, (u, v) -> 1 + 0.025 * Math.sin(u * TAU * 3 + v * 9), (u, v, pt, n) -> {
                int cc = G.scale(col, 1 - 0.18 * v);
                return a(G.lit(cc, pt, n, 0.42f, 0.16f), al);
            }, true);
            // fur rim
            G.torus(f, f.p(0, by, 0).subtract(f.o).add(f.o), 0.302 * s, 0.078 * s, 30, 12,
                    (u, v) -> 1 + 0.14 * Math.sin(u * TAU * 29 + Math.sin(v * TAU * 3) * 2) * Math.sin(v * TAU * 5 + u * TAU * 13),
                    (u, v, pt, n) -> a(G.lit(G.scale(0xFFF7F5F0, 0.94 + 0.06 * Math.sin(u * 211 + v * 37)), pt, n, 0.62f, 0.05f), al), true);
            // pom-pom
            Vec3d tip = c[nv].add(c[nv].subtract(c[nv - 1]).normalize().multiply(0.045 * s * ba.scale));
            poms.add(tip);
            double pr = 0.088 * s;
            G.surface((u, v) -> {
                double th = u * TAU, ph = (v - 0.5) * Math.PI;
                double fz = 1 + 0.13 * Math.sin(th * 7 + v * 11) * Math.sin(v * 17 + th * 3);
                return tip.add(f.d(Math.cos(ph) * Math.cos(th) * pr * fz, Math.sin(ph) * pr * fz, Math.cos(ph) * Math.sin(th) * pr * fz));
            }, 18, 10, (u, v, pt, n) -> a(G.lit(0xFFFAF8F4, pt, n, 0.66f, 0.04f), al), true);
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            if (!glow.on()) return;
            float[] s = new float[2];
            for (Vec3d p : poms) {
                if (!Projection.point(p, s)) continue;
                float k = ppb(p) * size.f();
                G.soft(s[0], s[1], k * 0.2f, 0xFFFFFFFF, 0.28f * al);
                Nvg.additive(true);
                G.soft(s[0], s[1], k * 0.35f, 0xFFFFF4E8, 0.07f * al);
                Nvg.additive(false);
            }
        }

        @Override
        void onFadedOut() {
            sway.clear();
        }
    }

    /* ================================================================== 2. snowfall */

    static final class Snowfall extends PV {
        private final Setting.Num density = num("Density", 280, 40, 700, 10);
        private final Setting.Num radius = num("Radius", 10, 4, 24, 1);
        private final Setting.Num speed = num("Fall Speed", 1, 0.3, 2.5, 0.05);
        private final Setting.Num size = num("Size", 1, 0.4, 2.5, 0.05);
        private final Setting.Num wind = num("Wind", 0.3, 0, 2, 0.05);
        private final Setting.Bool settle = bool("Settle & Melt", true);
        private final Setting.Bool crystals = bool("Crystal Flakes", true);

        private static final class Flake {
            double x, y, z, landed = -1;
            float sz, ph, spd, rot, melt;
        }

        private final List<Flake> flakes = new ArrayList<>();
        private final Random rng = new Random();

        Snowfall() {
            super("Snowfall", "Soft 3D snow drifts down around you, settles on blocks and slowly melts");
        }

        private Flake spawn(Vec3d c, double y) {
            Flake f = new Flake();
            double R = radius.f(), an = rng.nextDouble() * TAU, rr = Math.sqrt(rng.nextDouble()) * R;
            f.x = c.x + Math.cos(an) * rr;
            f.z = c.z + Math.sin(an) * rr;
            f.y = y;
            f.sz = 0.55f + rng.nextFloat() * rng.nextFloat() * 1.3f;
            f.ph = rng.nextFloat();
            f.spd = 0.55f + rng.nextFloat() * 0.6f + f.sz * 0.1f;
            f.rot = rng.nextFloat() * 6;
            f.melt = 2.2f + rng.nextFloat() * 2.5f;
            return f;
        }

        @Override
        void world(float td, double t, float dt, float al) {
            var w = mc.world;
            Vec3d c = mc.player.getLerpedPos(td);
            int want = density.i();
            if (flakes.isEmpty()) for (int i = 0; i < want; i++) flakes.add(spawn(c, c.y - 3 + rng.nextDouble() * 12));
            for (int i = 0; i < 12 && flakes.size() < want && enabled(); i++) flakes.add(spawn(c, c.y + 7 + rng.nextDouble() * 3));
            double R = radius.f();
            BlockPos.Mutable bp = new BlockPos.Mutable();
            for (Iterator<Flake> it = flakes.iterator(); it.hasNext(); ) {
                Flake f = it.next();
                double dx = f.x - c.x, dz = f.z - c.z;
                if (dx * dx + dz * dz > R * R * 2.1 || f.y < c.y - 14 || flakes.size() > want * 1.2) {
                    it.remove();
                    continue;
                }
                if (f.landed >= 0) {
                    if (t - f.landed > f.melt) it.remove();
                    continue;
                }
                f.y -= f.spd * speed.f() * dt;
                f.x += (Math.sin(t * 0.8 + f.ph * 9) * 0.28 + wind.f() * 0.7) * dt;
                f.z += (Math.cos(t * 0.65 + f.ph * 7) * 0.22 + wind.f() * 0.25) * dt;
                f.rot += dt * (f.ph - 0.5f) * 2.4f;
                bp.set(MathHelper.floor(f.x), MathHelper.floor(f.y), MathHelper.floor(f.z));
                BlockState st = w.getBlockState(bp);
                if (st.isAir()) continue;
                if (!st.getFluidState().isEmpty()) {
                    f.landed = t;
                    f.melt = 0.35f;
                    continue;
                }
                VoxelShape sh = st.getCollisionShape(w, bp);
                if (sh.isEmpty()) continue;
                double top = bp.getY() + sh.getMax(Direction.Axis.Y);
                if (f.y <= top) {
                    if (!settle.on()) {
                        it.remove();
                        continue;
                    }
                    f.y = top + 0.012;
                    f.landed = t;
                }
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            Vec3d cam = Projection.camera();
            double R = radius.f();
            float base = ppb(cam.add(Vec3d.fromPolar(mc.gameRenderer.getCamera().getPitch(), mc.gameRenderer.getCamera().getYaw())));
            if (base <= 0) return;
            for (Flake f : flakes) {
                Vec3d p = new Vec3d(f.x, f.y, f.z);
                if (!Projection.point(p, s)) continue;
                double d = p.distanceTo(cam);
                if (d > R * 1.7) continue;
                float fade = smooth((float) ((d - 0.3) / 0.8)) * (1 - smooth((float) ((d - R * 1.1) / (R * 0.5))));
                float m = f.landed < 0 ? 1 : 1 - (float) ((t - f.landed) / f.melt);
                if (m <= 0) continue;
                float rp = (float) (base / d) * 0.03f * f.sz * size.f() * (float) Math.sqrt(m);
                float aa = fade * al * (f.landed < 0 ? 1 : m);
                if (rp < 0.3f || aa < 0.01f) continue;
                if (crystals.on() && rp > 2.4f && f.landed < 0) G.crystal(s[0], s[1], rp * 1.6f, f.rot, 0xFFF4FAFF, aa * 0.85f);
                else {
                    G.soft(s[0], s[1], rp * 1.7f, 0xFFF2F8FF, 0.85f * aa);
                    G.soft(s[0], s[1], rp * 0.6f, 0xFFFFFFFF, 0.9f * aa);
                }
            }
        }

        @Override
        void onFadedOut() {
            flakes.clear();
        }
    }

    /* ================================================================== 3. christmas lights */

    static final class XmasLights extends PV {
        private final Setting.Mode where = mode("Wrap", "Around You", "Around You", "Looked Block", "Both");
        private final Setting.Mode style = mode("Bulbs", "Classic", "Classic", "Warm White", "Candy", "Icy", "Rainbow");
        private final Setting.Mode anim = mode("Animation", "Twinkle", "Twinkle", "Chase", "Steady", "Fade");
        private final Setting.Num bulbs = num("Bulbs", 22, 8, 48, 1);
        private final Setting.Num speed = num("Speed", 1, 0.2, 3, 0.1);
        private final Setting.Num glow = num("Glow", 1, 0, 2, 0.05);
        private final List<double[]> lamps = new ArrayList<>(); // x y z color intensity back
        private Vec3d bMin, bMax;
        private float boxVis;

        XmasLights() {
            super("Christmas Lights", "A string of twinkling bulbs wrapped around you or the block you look at");
        }

        private int bulbCol(int i, double t) {
            return switch (style.get()) {
                case "Warm White" -> 0xFFFFD9A0;
                case "Candy" -> i % 2 == 0 ? 0xFFFF2A3C : 0xFFFFF4F4;
                case "Icy" -> G.pick(new int[]{0xFF8FD8FF, 0xFFFFFFFF, 0xFF5E9BFF}, i);
                case "Rainbow" -> 0xFF000000 | java.awt.Color.HSBtoRGB((float) G.frac(i * 0.13 + t * 0.05), 0.75f, 1f);
                default -> G.pick(new int[]{0xFFFF3030, 0xFF2CE860, 0xFF3B7CFF, 0xFFFFC628, 0xFFFF7A1E}, i);
            };
        }

        private float inten(int i, int n, double t) {
            double sp = speed.f();
            return switch (anim.get()) {
                case "Chase" -> (float) (0.25 + 0.75 * Math.pow(0.5 + 0.5 * Math.sin(i * 0.9 - t * 4 * sp), 3));
                case "Steady" -> 1f;
                case "Fade" -> (float) (0.35 + 0.65 * (0.5 + 0.5 * Math.sin(t * 1.6 * sp + (i % 3) * TAU / 3)));
                default -> {
                    float h = hash(i * 7 + 3);
                    double tw = 0.5 + 0.5 * Math.sin(t * (1.3 + h * 2.2) * sp + h * 40);
                    yield (float) (0.3 + 0.7 * Math.pow(tw, 2.2));
                }
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            lamps.clear();
            String w = where.get();
            if (!w.equals("Looked Block") && thirdPerson()) aroundYou(td, t, al);
            boolean wantBox = !w.equals("Around You") && mc.crosshairTarget instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK;
            if (wantBox) {
                BlockPos pos = ((BlockHitResult) mc.crosshairTarget).getBlockPos();
                VoxelShape sh = mc.world.getBlockState(pos).getOutlineShape(mc.world, pos);
                if (!sh.isEmpty()) {
                    Box b = sh.getBoundingBox().offset(pos).expand(0.03);
                    Vec3d mn = new Vec3d(b.minX, b.minY, b.minZ), mx = new Vec3d(b.maxX, b.maxY, b.maxZ);
                    if (bMin == null || boxVis < 0.02f) {
                        bMin = mn;
                        bMax = mx;
                    }
                    float k = ease(dt, 14);
                    bMin = bMin.add(mn.subtract(bMin).multiply(k));
                    bMax = bMax.add(mx.subtract(bMax).multiply(k));
                } else wantBox = false;
            }
            boxVis += ((wantBox ? 1 : 0) - boxVis) * ease(dt, 8);
            if (bMin != null && boxVis > 0.01f) aroundBox(t, al * smooth(boxVis));
        }

        private void wire(Vec3d a0, Vec3d a1, double sag, float al) {
            int n = 5;
            Vec3d prev = a0;
            for (int k = 1; k <= n; k++) {
                double f = k / (double) n;
                Vec3d p = a0.lerp(a1, f).add(0, -sag * Math.sin(Math.PI * f), 0);
                Render3D.drawLineGradient(prev, p, a(0xFF1E3A24, 0.95f * al), a(0xFF1E3A24, 0.95f * al), 1.6f, true);
                prev = p;
            }
        }

        private void bulb(Vec3d hang, int i, int n, double t, float al, double back) {
            int col = bulbCol(i, t);
            float in = inten(i, n, t);
            Vec3d c = hang.add(0, -0.035, 0);
            G.F f = G.F.world(Vec3d.ZERO, 1);
            G.surface(G.ellip(f, c, 0.022, 0.036, 0.022), 10, 6, (u, v, p, nn) -> a(Nvg.mix(G.lit(col, p, nn, 0.75f, 0.9f), 0xFFFFFFFF, 0.35f * in), (0.75f + 0.25f * in) * al), true);
            G.ball(hang.add(0, -0.004, 0), 0.012, 6, 0xFF1A3320, 0.5f, 0.3f, al, true);
            lamps.add(new double[]{c.x, c.y, c.z, col, in, back});
        }

        private void aroundYou(float td, double t, float al) {
            BodyAnchor ba = BodyAnchor.of(mc.player, td);
            int n = bulbs.i();
            double turns = 2.6, rot = t * 0.35 * speed.f();
            Vec3d cam = Projection.camera(), cc = ba.center();
            Vec3d prev = null;
            float grow = outCubic(al);
            for (int i = 0; i < n; i++) {
                double s = (i + 0.5) / n;
                if (s > grow + 0.02) break;
                double an = s * turns * TAU + rot;
                double rad = 0.46 + 0.03 * Math.sin(an * 2 + t);
                Vec3d p = ba.at(Math.cos(an) * rad, 0.12 + s * 1.62, Math.sin(an) * rad);
                if (prev != null) wire(prev, p, 0.05, al);
                double back = p.subtract(cc).normalize().dotProduct(cam.subtract(cc).normalize());
                bulb(p, i, n, t, al, back);
                prev = p;
            }
        }

        private void aroundBox(double t, float al) {
            double[] ys = {bMax.y, bMin.y};
            int idx = 0;
            for (double y : ys) {
                Vec3d[] cs = {new Vec3d(bMin.x, y, bMin.z), new Vec3d(bMax.x, y, bMin.z), new Vec3d(bMax.x, y, bMax.z), new Vec3d(bMin.x, y, bMax.z)};
                for (int e = 0; e < 4; e++) {
                    Vec3d a0 = cs[e], a1 = cs[(e + 1) % 4];
                    int m = Math.max(2, (int) Math.round(a0.distanceTo(a1) / 0.2));
                    Vec3d prev = a0;
                    for (int k = 1; k <= m; k++) {
                        Vec3d p = a0.lerp(a1, k / (double) m);
                        wire(prev, p, 0.035, al);
                        if (k < m) bulb(p, idx++, 64, t, al, 1);
                        prev = p;
                    }
                }
            }
            for (int e = 0; e < 4; e++) {
                double x = e == 0 || e == 3 ? bMin.x : bMax.x, z = e < 2 ? bMin.z : bMax.z;
                wire(new Vec3d(x, bMax.y, z), new Vec3d(x, bMin.y, z), 0, al);
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            float g = glow.f();
            if (g <= 0) return;
            Nvg.additive(true);
            for (double[] l : lamps) {
                Vec3d p = new Vec3d(l[0], l[1], l[2]);
                if (!Projection.point(p, s)) continue;
                float k = ppb(p);
                int col = (int) (long) l[3];
                float in = (float) l[4] * g * al * (l[5] < -0.15 ? 0.25f : 1f) * G.fadeNear(p);
                G.soft(s[0], s[1], k * 0.32f, col, 0.16f * in);
                G.soft(s[0], s[1], k * 0.11f, col, 0.45f * in);
                G.soft(s[0], s[1], k * 0.035f, 0xFFFFFFFF, 0.8f * in);
            }
            Nvg.additive(false);
        }

        @Override
        void onFadedOut() {
            bMin = bMax = null;
            boxVis = 0;
        }
    }

    /* ================================================================== 4. candy cane trail */

    static final class CandyCaneTrail extends PV {
        private final Setting.Num life = num("Length", 1.4, 0.4, 4, 0.1);
        private final Setting.Num width = num("Thickness", 1, 0.4, 2.5, 0.05);
        private final Setting.Num stripes = num("Stripes", 1, 0.3, 3, 0.05);
        private final Setting.Bool mint = bool("Mint Sparkles", true);
        private final Setting.Bool others = bool("Other Players", false);
        private final Setting.Mode colors = mode("Colors", "Classic", "Classic", "Mint", "Green", "Rainbow");
        private final Map<Integer, Deque<double[]>> trails = new HashMap<>();
        private final List<Vec3d> sparks = new ArrayList<>();

        CandyCaneTrail() {
            super("Candy Cane Trail", "A glossy twisted red and white candy cane ribbon follows your path");
        }

        private int stripe(double v) {
            return switch (colors.get()) {
                case "Mint" -> 0xFF22C48A;
                case "Green" -> 0xFF1A9A44;
                case "Rainbow" -> 0xFF000000 | java.awt.Color.HSBtoRGB((float) G.frac(v * 0.15), 0.8f, 1f);
                default -> 0xFFE3122E;
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            sparks.clear();
            List<PlayerEntity> ps = new ArrayList<>();
            ps.add(mc.player);
            if (others.on()) for (AbstractClientPlayerEntity p : mc.world.getPlayers()) if (p != mc.player && p.isAlive()) ps.add(p);
            double L = life.f();
            for (PlayerEntity p : ps) {
                Deque<double[]> d = trails.computeIfAbsent(p.getId(), k -> new ArrayDeque<>());
                BodyAnchor ba = BodyAnchor.of(p, td);
                Vec3d now = ba.at(0, 0.55, -0.1);
                double[] last = d.peekLast();
                if (enabled() && (last == null || now.squaredDistanceTo(last[0], last[1], last[2]) > 0.06 * 0.06))
                    d.addLast(new double[]{now.x, now.y, now.z, t, last == null ? 0 : last[4] + now.distanceTo(new Vec3d(last[0], last[1], last[2]))});
                while (!d.isEmpty() && (t - d.peekFirst()[3] > L || d.size() > 140)) d.pollFirst();
                if (d.size() < 3) continue;
                double[][] pts = d.toArray(new double[0][]);
                int n = pts.length;
                Vec3d[] c = new Vec3d[n + 1];
                double[] r = new double[n + 1], arc = new double[n + 1];
                for (int i = 0; i < n; i++) {
                    c[i] = new Vec3d(pts[i][0], pts[i][1], pts[i][2]);
                    float age = (float) ((t - pts[i][3]) / L);
                    r[i] = 0.05 * width.f() * Math.sqrt(Math.max(0, 1 - age)) * Math.min(1, (n - i) / 4.0 + 0.2) * (0.3 + 0.7 * al);
                    arc[i] = pts[i][4];
                }
                c[n] = c[n - 1].lerp(now, 1);
                r[n] = r[n - 1] * 0.5;
                arc[n] = arc[n - 1] + c[n].distanceTo(c[n - 1]);
                double freq = 2.2 * stripes.f();
                G.tube(c, r, 12, new Vec3d(0, 1, 0), null, (u, v, pt, nn) -> {
                    double ar = arc[(int) Math.round(v * n)];
                    double sv = G.frac(ar * freq + u);
                    float m = smooth((float) ((Math.abs(sv - 0.5) - 0.18) / 0.06));
                    int col = Nvg.mix(stripe(ar), 0xFFFFF8F6, m);
                    return a(G.lit(col, pt, nn, 0.5f, 0.85f), al * 0.97f);
                }, true);
                if (mint.on()) for (int i = 0; i < n; i += 6) sparks.add(c[i].add(0, 0.05 * Math.sin(t * 3 + i), 0));
            }
            trails.entrySet().removeIf(en -> mc.world.getEntityById(en.getKey()) == null);
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            if (!mint.on()) return;
            float[] s = new float[2];
            Nvg.additive(true);
            int i = 0;
            for (Vec3d p : sparks) {
                i++;
                if (!Projection.point(p, s)) continue;
                float tw = (float) Math.pow(0.5 + 0.5 * Math.sin(t * 5 + i * 1.7), 3);
                spark(s[0], s[1], ppb(p) * 0.02f, 0xFFB8FFE0, al * tw * 0.7f * G.fadeNear(p));
            }
            Nvg.additive(false);
        }

        @Override
        void onFadedOut() {
            trails.clear();
        }
    }

    /* ================================================================== 5. gift burst */

    static final class GiftBurst extends PV {
        private final Setting.Num gifts = num("Presents", 3, 1, 6, 1);
        private final Setting.Num confetti = num("Confetti", 45, 0, 120, 5);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num duration = num("Duration", 1.6, 0.8, 3, 0.1);
        private final List<double[]> boxes = new ArrayList<>(); // ox oy oz vx vy vz born seed floor
        private final List<double[]> bits = new ArrayList<>();
        private final List<double[]> flashes = new ArrayList<>();
        private long seen;
        private final Random rng = new Random();
        private static final int[][] WRAP = {{0xFFD0202E, 0xFFFFD34A}, {0xFF1E8E48, 0xFFF4F0E8}, {0xFF2F5BD6, 0xFFF4F0E8}, {0xFFF4F0E8, 0xFFD0202E},
                {0xFF8C3BD8, 0xFFFFD34A}, {0xFFFFC23A, 0xFFD0202E}};
        private static final int[] CONF = {0xFFFF3B4E, 0xFFFFD34A, 0xFF34E07A, 0xFF4E8BFF, 0xFFFFFFFF, 0xFFFF7AD0};

        GiftBurst() {
            super("Gift Burst", "Presents with ribbons and bows pop out of whatever you hit, with confetti");
        }

        @Override
        protected void onEnable() {
            seen = VisualModules.lastHit;
        }

        private void burst(Entity e, double t) {
            Vec3d o = e.getEntityPos().add(0, e.getHeight() * 0.6, 0);
            double floor = e.getEntityPos().y;
            for (int i = 0; i < gifts.i() && boxes.size() < 24; i++) {
                double an = rng.nextDouble() * TAU, sp = 1.2 + rng.nextDouble() * 1.4;
                boxes.add(new double[]{o.x, o.y, o.z, Math.cos(an) * sp, 2.8 + rng.nextDouble() * 1.6, Math.sin(an) * sp, t, rng.nextInt(1 << 20), floor});
            }
            for (int i = 0; i < confetti.i() && bits.size() < 320; i++) {
                double an = rng.nextDouble() * TAU, el = rng.nextDouble() * 1.2 - 0.1, sp = 2 + rng.nextDouble() * 3.5;
                bits.add(new double[]{o.x, o.y, o.z, Math.cos(an) * Math.cos(el) * sp, Math.sin(el) * sp + 1.5, Math.sin(an) * Math.cos(el) * sp, t, rng.nextInt(1 << 20), floor});
            }
            flashes.add(new double[]{o.x, o.y, o.z, t});
        }

        private static Vec3d motion(double[] b, double age, double drag, double grav) {
            double f = (1 - Math.exp(-drag * age)) / drag;
            double y = b[1] + b[4] * f - grav * age * age * 0.5;
            if (y < b[8] + 0.02) y = b[8] + 0.02;
            return new Vec3d(b[0] + b[3] * f, y, b[2] + b[5] * f);
        }

        @Override
        void world(float td, double t, float dt, float al) {
            if (VisualModules.lastHit != seen) {
                seen = VisualModules.lastHit;
                if (enabled() && VisualModules.lastTarget != null) burst(VisualModules.lastTarget, t);
            }
            double D = duration.f();
            boxes.removeIf(b -> t - b[6] > D);
            bits.removeIf(b -> t - b[6] > D * 1.3);
            flashes.removeIf(b -> t - b[3] > 0.5);
            for (double[] b : boxes) {
                double age = t - b[6];
                float p = (float) (age / D);
                float sc = (float) (size.f() * outBack(p * 4) * (1 - smooth((p - 0.8f) / 0.2f)));
                if (sc <= 0.01) continue;
                Vec3d c = motion(b, age, 1.6, 7.5);
                int seed = (int) b[7];
                Quaternionf q = new Quaternionf().rotateAxis((float) (age * (3 + hash(seed) * 4) + hash(seed + 1) * 6), new Vector3f(hash(seed + 2) - 0.5f, 1, hash(seed + 3) - 0.5f).normalize());
                gift(c, q, 0.12 * sc, WRAP[seed % WRAP.length], al);
            }
            for (double[] b : bits) {
                double age = t - b[6];
                float p = (float) (age / (D * 1.3));
                Vec3d c = motion(b, age, 2.6, 3.2);
                int seed = (int) b[7];
                c = c.add(Math.sin(age * 5 + seed) * 0.08 * age, 0, Math.cos(age * 4.3 + seed) * 0.08 * age);
                Quaternionf q = new Quaternionf().rotateAxis((float) (age * (6 + hash(seed) * 8)), new Vector3f(hash(seed + 5) - 0.5f, hash(seed + 6) - 0.5f, hash(seed + 7) - 0.5f).normalize());
                Vector3f ax = q.transform(new Vector3f(0.022f, 0, 0)), az = q.transform(new Vector3f(0, 0, 0.012f));
                Vec3d X = new Vec3d(ax.x, ax.y, ax.z), Z = new Vec3d(az.x, az.y, az.z);
                Vec3d n = X.crossProduct(Z).normalize();
                int col = G.lit2(CONF[seed % CONF.length], c, n, 0.55f, 0.9f);
                int cc = a(col, al * (1 - smooth((p - 0.7f) / 0.3f)));
                quad(c.subtract(X).subtract(Z), c.add(X).subtract(Z), c.add(X).add(Z), c.subtract(X).add(Z), cc, cc, cc, cc, true);
            }
        }

        /** A wrapped present with a ribbon cross and a bow. */
        private void gift(Vec3d c, Quaternionf q, double h, int[] pal, float al) {
            Vector3f vx = q.transform(new Vector3f(1, 0, 0)), vy = q.transform(new Vector3f(0, 1, 0)), vz = q.transform(new Vector3f(0, 0, 1));
            Vec3d X = new Vec3d(vx.x, vx.y, vx.z), Y = new Vec3d(vy.x, vy.y, vy.z), Z = new Vec3d(vz.x, vz.y, vz.z);
            Vec3d[] ns = {X, X.multiply(-1), Y, Y.multiply(-1), Z, Z.multiply(-1)};
            double rw = 0.18;
            for (Vec3d n : ns) {
                Vec3d u = Math.abs(n.dotProduct(Y)) > 0.5 ? X : Y, v = n.crossProduct(u);
                Vec3d fc = c.add(n.multiply(h));
                int wc = a(G.lit(pal[0], fc, n, 0.45f, 0.35f), al), rc = a(G.lit(pal[1], fc, n, 0.5f, 0.9f), al);
                quad(fc.add(u.multiply(h)).add(v.multiply(h)), fc.subtract(u.multiply(h)).add(v.multiply(h)), fc.subtract(u.multiply(h)).subtract(v.multiply(h)), fc.add(u.multiply(h)).subtract(v.multiply(h)), wc, wc, wc, wc, true);
                Vec3d rf = c.add(n.multiply(h * 1.006));
                boolean top = Math.abs(n.dotProduct(Y)) > 0.5;
                // band running along u through the centre, and for top/bottom a second one along v
                quad(rf.add(u.multiply(h)).add(v.multiply(h * rw)), rf.subtract(u.multiply(h)).add(v.multiply(h * rw)), rf.subtract(u.multiply(h)).subtract(v.multiply(h * rw)), rf.add(u.multiply(h)).subtract(v.multiply(h * rw)), rc, rc, rc, rc, true);
                if (top || Math.abs(n.dotProduct(Z)) > 0.5)
                    quad(rf.add(v.multiply(h)).add(u.multiply(h * rw)), rf.subtract(v.multiply(h)).add(u.multiply(h * rw)), rf.subtract(v.multiply(h)).subtract(u.multiply(h * rw)), rf.add(v.multiply(h)).subtract(u.multiply(h * rw)), rc, rc, rc, rc, true);
            }
            // bow: two flattened loops and a knot
            Vec3d topC = c.add(Y.multiply(h * 1.05));
            G.F f = new G.F(Vec3d.ZERO, X, Y, Z);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                Vec3d lc = topC.add(X.multiply(sgn * h * 0.42)).add(Y.multiply(h * 0.22));
                G.F lf = new G.F(Vec3d.ZERO, X.multiply(Math.cos(0.5)).add(Y.multiply(sgn * Math.sin(0.5))), Y.multiply(Math.cos(0.5)).add(X.multiply(-sgn * Math.sin(0.5))), Z);
                G.surface(G.ellip(lf, lc, h * 0.42, h * 0.22, h * 0.13), 10, 6, (u, v, p, n) -> a(G.lit(pal[1], p, n, 0.5f, 0.9f), al), true);
            }
            G.surface(G.ellip(f, topC.add(Y.multiply(h * 0.1)), h * 0.16, h * 0.14, h * 0.16), 8, 5, (u, v, p, n) -> a(G.lit(pal[1], p, n, 0.5f, 0.9f), al), true);
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            Nvg.additive(true);
            for (double[] f : flashes) {
                Vec3d p = new Vec3d(f[0], f[1], f[2]);
                if (!Projection.point(p, s)) continue;
                float pr = (float) ((t - f[3]) / 0.5);
                float k = ppb(p) * size.f();
                G.soft(s[0], s[1], k * (0.4f + 0.9f * outCubic(pr)), 0xFFFFE6A0, 0.35f * (1 - pr) * al);
                G.soft(s[0], s[1], k * 0.25f, 0xFFFFFFFF, 0.5f * (1 - pr) * (1 - pr) * al);
            }
            Nvg.additive(false);
        }

        @Override
        void onFadedOut() {
            boxes.clear();
            bits.clear();
            flashes.clear();
        }
    }

    /* ================================================================== 6. snow globe */

    static final class SnowGlobe extends PV {
        private final Setting.Num radius = num("Radius", 1.55, 1.2, 2.6, 0.05);
        private final Setting.Num snow = num("Snow", 160, 30, 400, 10);
        private final Setting.Num swirl = num("Swirl", 1, 0, 3, 0.05);
        private final Setting.Mode tint = mode("Glass", "Clear", "Clear", "Ice", "Rose", "Gold");
        private final Setting.Mode base = mode("Base", "Wood & Gold", "Wood & Gold", "Silver", "Red", "None");
        private final Setting.Bool others = bool("Other Players", false);
        private final List<Vec3d> globes = new ArrayList<>();
        private static final double BASE_H = 0.2;

        SnowGlobe() {
            super("Snow Globe", "You stand inside a glass snow globe with swirling snow");
        }

        private int glass() {
            return switch (tint.get()) {
                case "Ice" -> 0xFF9AD6FF;
                case "Rose" -> 0xFFFFB3CF;
                case "Gold" -> 0xFFFFE09A;
                default -> 0xFFE6F2FF;
            };
        }

        private double R(float al) {
            return radius.f() * (0.6 + 0.4 * outBack(al));
        }

        @Override
        void world(float td, double t, float dt, float al) {
            globes.clear();
            for (PlayerEntity p : targets(others)) {
                Vec3d o = p.getLerpedPos(td);
                globes.add(o);
                globe(o, R(al), al);
            }
        }

        private void globe(Vec3d o, double R, float al) {
            double cy = BASE_H + R * 0.2;
            Vec3d c = o.add(0, cy, 0);
            double cut = -(cy - BASE_H) / R; // sin(phi) at the base plane
            double ph0 = Math.asin(Math.max(-1, cut));
            int gc = glass();
            G.F f = G.F.world(Vec3d.ZERO, 1);
            Vec3d cam = Projection.camera();
            G.surface((u, v) -> {
                double th = u * TAU, ph = ph0 + (Math.PI / 2 - ph0) * v;
                return c.add(Math.cos(ph) * Math.cos(th) * R, Math.sin(ph) * R, Math.cos(ph) * Math.sin(th) * R);
            }, 40, 18, (u, v, p, n) -> {
                Vec3d vd = cam.subtract(p).normalize();
                float fr = (float) Math.pow(1 - Math.abs(n.dotProduct(vd)), 2.4);
                Vec3d h = G.LIGHT.add(vd).normalize();
                float sp = (float) Math.pow(Math.max(0, Math.abs(n.dotProduct(h))), 60);
                int col = Nvg.mix(gc, 0xFFFFFFFF, Math.min(1, fr * 0.6f + sp));
                return a(col, (0.035f + 0.42f * fr + 0.6f * sp) * al);
            }, false);
            String b = base.get();
            if (b.equals("None")) return;
            int wood = switch (b) {
                case "Silver" -> 0xFFB8C0CC;
                case "Red" -> 0xFF9A1422;
                default -> 0xFF5A3420;
            };
            int trim = b.equals("Silver") ? 0xFFE8EEF6 : 0xFFE0B040;
            double rb = Math.cos(ph0) * R;
            // pedestal: flared cylinder
            G.surface((u, v) -> {
                double th = u * TAU, rr = rb * (1.06 + 0.08 * (1 - v) * (1 - v));
                return o.add(Math.cos(th) * rr, BASE_H * v, Math.sin(th) * rr);
            }, 40, 4, (u, v, p, n) -> a(G.lit(Math.abs(v - 0.55) < 0.17 ? trim : wood, p, n, 0.4f, Math.abs(v - 0.55) < 0.17 ? 0.9f : 0.3f), al), true);
            // snow layer on top of the base
            Vec3d top = o.add(0, BASE_H + 0.01, 0);
            Vec3d[] ring = new Vec3d[40];
            for (int i = 0; i < 40; i++) ring[i] = top.add(Math.cos(i * TAU / 40) * rb, 0, Math.sin(i * TAU / 40) * rb);
            G.fan(top, ring, a(0xFFE2ECF8, 0.35f * al), a(0xFFF4F8FF, 0.8f * al), true);
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            Vec3d cam = Projection.camera();
            for (Vec3d o : globes) {
                double R = R(al), cy = BASE_H + R * 0.2;
                Vec3d c = o.add(0, cy, 0);
                int n = snow.i();
                float sw = swirl.f();
                for (int i = 0; i < n; i++) {
                    float h1 = hash(i * 4 + 1), h2 = hash(i * 4 + 2), h3 = hash(i * 4 + 3), h4 = hash(i * 4 + 4);
                    double rr = R * 0.9 * Math.sqrt(h1);
                    double an = h2 * TAU + t * sw * (0.25 + 0.9 * (1 - rr / R)) * (h4 > 0.15 ? 1 : -1);
                    double ymax = cy + Math.sqrt(Math.max(0, R * R * 0.85 - rr * rr)), ymin = BASE_H + 0.03;
                    double cyc = G.frac(h3 - t * (0.05 + 0.04 * h4) * (0.4 + sw * 0.6));
                    double y = ymin + (ymax - ymin) * cyc + Math.sin(t * 1.3 + i) * 0.05 * sw;
                    Vec3d p = o.add(Math.cos(an) * rr, y, Math.sin(an) * rr);
                    if (!Projection.point(p, s)) continue;
                    double d = p.distanceTo(cam);
                    float k = ppb(p);
                    float edge = smooth((float) (cyc / 0.08)) * smooth((float) ((1 - cyc) / 0.15));
                    float aa = al * edge * G.fadeNear(p) * (float) (0.6 + 0.4 * Math.sin(t * 3 + i));
                    float rp = k * (0.014f + 0.016f * h4);
                    G.soft(s[0], s[1], rp * 1.8f, 0xFFFFFFFF, 0.75f * aa);
                    if (d > 30) break;
                }
                // glass specular highlight and a soft caustic on the base
                Vec3d vd = cam.subtract(c).normalize();
                Vec3d hp = c.add(G.LIGHT.add(vd).normalize().multiply(R));
                if (Projection.point(hp, s)) {
                    float k = ppb(hp);
                    Nvg.additive(true);
                    G.soft(s[0], s[1], k * 0.35f, 0xFFFFFFFF, 0.22f * al);
                    G.soft(s[0], s[1], k * 0.09f, 0xFFFFFFFF, 0.55f * al);
                    Nvg.additive(false);
                }
            }
        }
    }

    /* ================================================================== 7. reindeer antlers */

    static final class Antlers extends PV {
        private final Setting.Num size = num("Size", 1, 0.6, 1.6, 0.05);
        private final Setting.Mode style = mode("Style", "Velvet", "Velvet", "Gold", "Ice", "Chocolate");
        private final Setting.Bool nose = bool("Red Nose", true);
        private final Setting.Bool ears = bool("Ears", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final List<Vec3d> noses = new ArrayList<>();
        private final List<Float> noseFace = new ArrayList<>();

        Antlers() {
            super("Reindeer Antlers", "Branching reindeer antlers, little ears and a glowing red nose");
        }

        private int[] cols() {
            return switch (style.get()) {
                case "Gold" -> new int[]{0xFFC08A1E, 0xFFFFE08A};
                case "Ice" -> new int[]{0xFF7FBDF0, 0xFFF0FAFF};
                case "Chocolate" -> new int[]{0xFF4A2A18, 0xFF8A5A38};
                default -> new int[]{0xFF6E4428, 0xFFC9A276};
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            noses.clear();
            noseFace.clear();
            for (PlayerEntity p : targets(others)) {
                BodyAnchor ba = BodyAnchor.of(p, td);
                G.F f = G.F.head(ba);
                double s = size.f() * outBack(al);
                if (s < 0.02) continue;
                double H = BodyAnchor.HEAD;
                int[] c = cols();
                float gloss = style.is("Gold") || style.is("Ice") ? 0.9f : 0.25f;
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    Vec3d b0 = f.p(sgn * 0.17, H - 0.05, -0.02);
                    Vec3d P1 = f.p(sgn * (0.17 + 0.12 * s), H + 0.06 * s, -0.04 * s), P2 = f.p(sgn * (0.17 + 0.30 * s), H + 0.24 * s, -0.12 * s),
                            P3 = f.p(sgn * (0.17 + 0.24 * s), H + 0.44 * s, 0.0);
                    branch(b0, P1, P2, P3, 0.034 * s, 0.011 * s, 18, f, c, gloss, al);
                    double[][] tines = {{0.32, sgn * 0.02, 0.14, 0.11}, {0.58, sgn * 0.09, 0.12, -0.02}, {0.78, -sgn * 0.03, 0.10, 0.08}};
                    for (double[] tn : tines) {
                        Vec3d st = G.bez(b0, P1, P2, P3, tn[0]);
                        Vec3d en = st.add(f.d(tn[1] * s, tn[2] * s, tn[3] * s));
                        Vec3d m1 = st.add(f.d(tn[1] * 0.2 * s, tn[2] * 0.5 * s, tn[3] * 0.1 * s)), m2 = st.add(f.d(tn[1] * 0.8 * s, tn[2] * 0.85 * s, tn[3] * 0.7 * s));
                        branch(st, m1, m2, en, 0.022 * s, 0.007 * s, 9, f, c, gloss, al);
                    }
                    if (ears.on()) {
                        G.F ef = new G.F(Vec3d.ZERO, f.x, f.y.multiply(Math.cos(0.9)).add(f.x.multiply(sgn * Math.sin(0.9))), f.z);
                        G.surface(G.ellip(ef, f.p(sgn * 0.27, H - 0.12, -0.02), 0.03, 0.085 * Math.min(1.3, s), 0.05), 10, 6,
                                (u, v, pt, n) -> a(G.lit(Nvg.mix(c[0], 0xFFE8B8A0, (float) Math.max(0, v - 0.2) * 0.4f), pt, n, 0.45f, 0.1f), al), true);
                    }
                }
                if (nose.on()) {
                    Vec3d np = f.p(0, 0.15, 0.255);
                    G.ball(np, 0.045 * ba.scale, 12, 0xFFFF2020, 0.75f, 1f, al, true);
                    double face = ba.hFwd.dotProduct(Projection.camera().subtract(np).normalize());
                    noses.add(np);
                    noseFace.add((float) smooth((float) (face * 3 + 0.4)));
                }
            }
        }

        private void branch(Vec3d a0, Vec3d a1, Vec3d a2, Vec3d a3, double r0, double r1, int n, G.F f, int[] c, float gloss, float al) {
            Vec3d[] cc = new Vec3d[n + 1];
            double[] rr = new double[n + 1];
            for (int j = 0; j <= n; j++) {
                double v = j / (double) n;
                cc[j] = G.bez(a0, a1, a2, a3, v);
                rr[j] = (r0 + (r1 - r0) * Math.pow(v, 0.8)) * (j == n ? 0.35 : 1);
            }
            G.tube(cc, rr, 10, f.z, (u, v) -> 1 + 0.07 * Math.sin(v * 37 + u * 9), (u, v, p, nn) -> a(G.lit(Nvg.mix(c[0], c[1], (float) Math.pow(v, 1.6)), p, nn, 0.42f, gloss), al), true);
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            Nvg.additive(true);
            for (int i = 0; i < noses.size(); i++) {
                Vec3d p = noses.get(i);
                if (!Projection.point(p, s)) continue;
                float k = ppb(p), pulse = (float) (0.75 + 0.25 * Math.sin(t * 3.2)) * noseFace.get(i);
                G.soft(s[0], s[1], k * 0.45f, 0xFFFF2A2A, 0.22f * al * pulse);
                G.soft(s[0], s[1], k * 0.14f, 0xFFFF5040, 0.5f * al * pulse);
                G.soft(s[0], s[1], k * 0.035f, 0xFFFFE0D0, 0.7f * al * noseFace.get(i));
            }
            Nvg.additive(false);
        }
    }

    /* ================================================================== 8. jingle sparkles */

    static final class JingleSparkles extends PV {
        private final Setting.Num rate = num("Rate", 1, 0.2, 3, 0.05);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Bool bells = bool("Bells", true);
        private final Setting.Bool sound = bool("Jingle Sound", true);
        private final Setting.Num volume = num("Volume", 0.35, 0.05, 1, 0.05);
        private final Setting.Mode color = mode("Color", "Gold", "Gold", "Silver", "Rose Gold");
        private final List<double[]> belts = new ArrayList<>(); // ox oy oz vx vy vz born seed
        private final List<double[]> sparks = new ArrayList<>();
        private double acc, bellAcc, lastSound;
        private final Random rng = new Random();
        private static final float[] NOTES = {0.84f, 0.94f, 1.06f, 1.26f, 1.41f, 1.68f};

        JingleSparkles() {
            super("Jingle Sparkles", "Golden sleigh bells and sparkles jingle off you as you move");
        }

        private int gold() {
            return switch (color.get()) {
                case "Silver" -> 0xFFD8DEE8;
                case "Rose Gold" -> 0xFFF0A890;
                default -> 0xFFF2BE3A;
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            BodyAnchor ba = BodyAnchor.of(mc.player, td);
            double sp = ba.speed * 20;
            if (enabled()) {
                acc += dt * rate.f() * (6 + sp * 9);
                while (acc > 1 && sparks.size() < 220) {
                    acc -= 1;
                    Vec3d o = ba.at((rng.nextDouble() - 0.5) * 0.8, 0.2 + rng.nextDouble() * 1.5, (rng.nextDouble() - 0.5) * 0.8);
                    sparks.add(new double[]{o.x, o.y, o.z, (rng.nextDouble() - 0.5) * 0.6, 0.3 + rng.nextDouble() * 0.6, (rng.nextDouble() - 0.5) * 0.6, t, rng.nextInt(1 << 20)});
                }
                acc = Math.min(acc, 2);
                bellAcc += dt * rate.f() * (sp > 1.5 ? 1.3 : 0.25);
                if (bellAcc > 1 && bells.on() && belts.size() < 14) {
                    bellAcc = 0;
                    Vec3d o = ba.at((rng.nextDouble() - 0.5) * 0.7, 0.6 + rng.nextDouble() * 0.9, (rng.nextDouble() - 0.5) * 0.7);
                    belts.add(new double[]{o.x, o.y, o.z, (rng.nextDouble() - 0.5) * 0.8, 0.9 + rng.nextDouble() * 0.5, (rng.nextDouble() - 0.5) * 0.8, t, rng.nextInt(1 << 20)});
                    if (sound.on() && t - lastSound > 0.22) {
                        lastSound = t;
                        float pitch = NOTES[rng.nextInt(NOTES.length)];
                        mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), pitch, volume.f() * 0.6f));
                        mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), pitch * 2, volume.f() * 0.35f));
                    }
                }
            }
            sparks.removeIf(s -> t - s[6] > 1.4);
            belts.removeIf(s -> t - s[6] > 1.9);
            int gc = gold();
            for (double[] b : belts) {
                double age = t - b[6];
                float p = (float) (age / 1.9);
                double f = (1 - Math.exp(-1.8 * age)) / 1.8;
                Vec3d c = new Vec3d(b[0] + b[3] * f, b[1] + b[4] * f - 0.55 * age * age, b[2] + b[5] * f);
                double sc = size.f() * outBack(p * 5) * (1 - smooth((p - 0.75f) / 0.25f));
                if (sc < 0.01) continue;
                bell(c, age, (int) b[7], sc, gc, al);
            }
        }

        private void bell(Vec3d c, double age, int seed, double sc, int col, float al) {
            double sw = Math.sin(age * 9 + seed) * 0.45 * Math.exp(-age * 0.6);
            double yaw = hash(seed) * TAU + age * 0.8;
            Vec3d X = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw)), Z = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
            Vec3d Y = new Vec3d(0, Math.cos(sw), 0).add(X.multiply(Math.sin(sw)));
            X = X.multiply(Math.cos(sw)).add(new Vec3d(0, -Math.sin(sw), 0));
            G.F f = new G.F(c, X.multiply(sc), Y.multiply(sc), Z.multiply(sc));
            // sleigh bell: a sphere with a slot and a loop
            G.surface(G.ellip(f, c, 0.06, 0.06, 0.06), 14, 8, (u, v, p, n) -> {
                boolean slot = Math.abs(v - 0.32) < 0.035 || (v < 0.32 && Math.abs(G.frac(u * 2) - 0.5) < 0.04);
                return a(slot ? 0xFF2A1A08 : G.lit(col, p, n, 0.4f, 1.1f), al);
            }, true);
            G.torus(new G.F(Vec3d.ZERO, f.x, f.z, f.y), f.p(0, 0.075, 0), 0.022 * sc, 0.007 * sc, 10, 5, (u, v, p, n) -> a(G.lit(col, p, n, 0.4f, 1f), al), true);
            G.ball(f.p(0, -0.035, 0.045), 0.012 * sc, 6, 0xFF2A1A08, 0.6f, 0, al, true);
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            int gc = gold();
            Nvg.additive(true);
            for (double[] b : sparks) {
                double age = t - b[6];
                float p = (float) (age / 1.4);
                double f = (1 - Math.exp(-2 * age)) / 2;
                Vec3d c = new Vec3d(b[0] + b[3] * f, b[1] + b[4] * f - 0.25 * age * age, b[2] + b[5] * f);
                if (!Projection.point(c, s)) continue;
                int seed = (int) b[7];
                float tw = (float) Math.pow(0.5 + 0.5 * Math.sin(age * (9 + hash(seed) * 8) + seed), 2);
                float aa = al * (1 - p) * smooth(p * 8) * (0.35f + 0.65f * tw) * G.fadeNear(c);
                float k = ppb(c) * size.f();
                spark(s[0], s[1], k * 0.016f, Nvg.mix(gc, 0xFFFFFFFF, hash(seed + 1) * 0.5f), aa);
                if (hash(seed + 2) > 0.7f) {
                    float L = k * 0.07f * tw;
                    Nvg.lineGradient(s[0] - L, s[1], s[0] + L, s[1], 1, a(gc, 0), a(0xFFFFFFFF, 0));
                    Nvg.lineGradient(s[0] - L, s[1], s[0], s[1], 1.2f, a(gc, 0), a(0xFFFFF4D0, 0.7f * aa));
                    Nvg.lineGradient(s[0], s[1], s[0] + L, s[1], 1.2f, a(0xFFFFF4D0, 0.7f * aa), a(gc, 0));
                    Nvg.lineGradient(s[0], s[1] - L, s[0], s[1], 1.2f, a(gc, 0), a(0xFFFFF4D0, 0.7f * aa));
                    Nvg.lineGradient(s[0], s[1], s[0], s[1] + L, 1.2f, a(0xFFFFF4D0, 0.7f * aa), a(gc, 0));
                }
            }
            Nvg.additive(false);
        }

        @Override
        void onFadedOut() {
            sparks.clear();
            belts.clear();
        }
    }

    /* ================================================================== 9. northern star */

    static final class NorthernStar extends PV {
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num height = num("Height", 0.55, 0.25, 1.5, 0.05);
        private final Setting.Num rays = num("Rays", 12, 0, 24, 1);
        private final Setting.Num spin = num("Spin", 0.5, 0, 2, 0.05);
        private final Setting.Mode color = mode("Color", "Gold", "Gold", "Silver", "Ice", "Rose");
        private final Setting.Bool others = bool("Other Players", false);
        private final List<Vec3d> stars = new ArrayList<>();

        NorthernStar() {
            super("Northern Star", "A glowing eight-pointed star shines above your head with soft rays");
        }

        private int col() {
            return switch (color.get()) {
                case "Silver" -> 0xFFE4ECF6;
                case "Ice" -> 0xFF8ED4FF;
                case "Rose" -> 0xFFFFA0C0;
                default -> 0xFFFFC840;
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            stars.clear();
            for (PlayerEntity p : targets(others)) {
                BodyAnchor ba = BodyAnchor.of(p, td);
                Vec3d c = ba.headTop(height.f() + 0.05 * Math.sin(t * 1.5));
                stars.add(c);
                double s = 0.22 * size.f() * outBack(al) * ba.scale;
                if (s < 0.005) continue;
                double yaw = t * spin.f();
                Vec3d X = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw)), Y = new Vec3d(0, 1, 0), N = X.crossProduct(Y);
                int pts = 16, sc = col();
                Vec3d[] ring = new Vec3d[pts];
                for (int i = 0; i < pts; i++) {
                    double an = i * TAU / pts + Math.PI / 2;
                    double r = i % 4 == 0 ? 1 : i % 2 == 0 ? 0.62 : 0.26;
                    ring[i] = c.add(X.multiply(Math.cos(an) * r * s)).add(Y.multiply(Math.sin(an) * r * s));
                }
                for (int side = -1; side <= 1; side += 2) {
                    Vec3d apex = c.add(N.multiply(side * s * 0.22));
                    for (int i = 0; i < pts; i++) {
                        Vec3d a0 = ring[i], a1 = ring[(i + 1) % pts];
                        Vec3d n = a1.subtract(apex).crossProduct(a0.subtract(apex)).normalize();
                        if (n.dotProduct(N.multiply(side)) < 0) n = n.multiply(-1);
                        int fc = a(G.lit(sc, apex, n, 0.6f, 1.2f), al), ec = a(G.lit(G.scale(sc, 0.85), a0, n, 0.55f, 1f), al);
                        tri3(apex, a0, a1, a(Nvg.mix(fc, 0xFFFFFFFF, 0.35f), al), ec, ec, true);
                    }
                }
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            int sc = col();
            for (Vec3d c : stars) {
                if (!Projection.point(c, s)) continue;
                float k = ppb(c) * size.f();
                float pulse = (float) (0.85 + 0.15 * Math.sin(t * 2.3));
                Nvg.additive(true);
                G.soft(s[0], s[1], k * 1.3f, sc, 0.08f * al * pulse);
                G.soft(s[0], s[1], k * 0.55f, sc, 0.2f * al * pulse);
                G.soft(s[0], s[1], k * 0.16f, 0xFFFFFFFF, 0.6f * al);
                int n = rays.i();
                for (int i = 0; i < n; i++) {
                    double an = i * TAU / n + t * 0.15 * spin.f();
                    float h = hash(i * 5 + 1);
                    float L = k * (0.4f + 0.45f * h) * (float) (0.7 + 0.3 * Math.sin(t * (0.8 + h) + i * 1.7)) * outCubic(al);
                    float ex = s[0] + (float) Math.cos(an) * L, ey = s[1] + (float) Math.sin(an) * L;
                    Nvg.lineGradient(s[0], s[1], ex, ey, Math.max(1.5f, k * 0.035f), a(sc, 0.32f * al), a(sc, 0));
                    Nvg.lineGradient(s[0], s[1], ex, ey, Math.max(4, k * 0.12f), a(sc, 0.06f * al), a(sc, 0));
                }
                // lens flare cross
                float F = k * 0.9f * pulse;
                Nvg.lineGradient(s[0] - F, s[1], s[0], s[1], 1.5f, a(sc, 0), a(0xFFFFFFFF, 0.5f * al));
                Nvg.lineGradient(s[0], s[1], s[0] + F, s[1], 1.5f, a(0xFFFFFFFF, 0.5f * al), a(sc, 0));
                Nvg.lineGradient(s[0], s[1] - F * 0.6f, s[0], s[1], 1.5f, a(sc, 0), a(0xFFFFFFFF, 0.4f * al));
                Nvg.lineGradient(s[0], s[1], s[0], s[1] + F * 0.6f, 1.5f, a(0xFFFFFFFF, 0.4f * al), a(sc, 0));
                // falling stardust
                for (int i = 0; i < 14; i++) {
                    float h1 = hash(i * 3 + 7), h2 = hash(i * 3 + 8);
                    double cyc = G.frac(t * 0.35 + h1);
                    Vec3d p = c.add(Math.cos(h2 * TAU + t * 0.3) * 0.18 * cyc, -cyc * 0.9, Math.sin(h2 * TAU + t * 0.3) * 0.18 * cyc);
                    float[] q = new float[2];
                    if (Projection.point(p, q)) spark(q[0], q[1], ppb(p) * 0.012f, sc, al * (float) (1 - cyc) * 0.8f);
                }
                Nvg.additive(false);
            }
        }
    }

    /* ================================================================== 10. frosty breath */

    static final class FrostyBreath extends PV {
        private final Setting.Num interval = num("Interval", 2.8, 1, 6, 0.1);
        private final Setting.Num amount = num("Amount", 1, 0.3, 2.5, 0.05);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Bool firstPerson = bool("First Person", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final List<double[]> puffs = new ArrayList<>(); // ox oy oz vx vy vz born seed
        private final Map<Integer, double[]> acc = new HashMap<>();
        private final Random rng = new Random();
        private static final double LIFE = 1.6;

        FrostyBreath() {
            super("Frosty Breath", "Soft puffs of cold breath vapour drift from your mouth");
        }

        @Override
        void world(float td, double t, float dt, float al) {
            List<PlayerEntity> ps = targets(others);
            boolean fp = !thirdPerson() && firstPerson.on();
            if (fp) ps.add(mc.player);
            if (enabled()) for (PlayerEntity p : ps) {
                double[] st = acc.computeIfAbsent(p.getId(), k -> new double[]{hash(p.getId()) * 3, 0});
                double per = interval.f() * (0.9 + 0.2 * hash(p.getId() + 9));
                double ph = G.frac((t + st[0]) / per) * per;
                if (ph > 0.75) continue;
                st[1] += dt * 22 * amount.f() * Math.sin(Math.PI * ph / 0.75);
                Vec3d mouth, fwd;
                Vec3d vel;
                if (p == mc.player && fp) {
                    var cam = mc.gameRenderer.getCamera();
                    fwd = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
                    mouth = cam.getCameraPos().add(fwd.multiply(0.32)).add(0, -0.2, 0);
                    vel = mc.player.getVelocity();
                } else {
                    BodyAnchor ba = BodyAnchor.of(p, td);
                    mouth = ba.head(0, 0.1, 0.27);
                    fwd = ba.hFwd;
                    vel = ba.vel;
                }
                while (st[1] > 1 && puffs.size() < 220) {
                    st[1] -= 1;
                    Vec3d v = fwd.multiply(0.7 + rng.nextDouble() * 0.4).add((rng.nextDouble() - 0.5) * 0.18, 0.05 + (rng.nextDouble() - 0.5) * 0.12, (rng.nextDouble() - 0.5) * 0.18).add(vel.multiply(14));
                    puffs.add(new double[]{mouth.x, mouth.y, mouth.z, v.x, v.y, v.z, t, rng.nextInt(1 << 20)});
                }
                st[1] = Math.min(st[1], 2);
            }
            puffs.removeIf(b -> t - b[6] > LIFE);
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            for (double[] b : puffs) {
                double age = t - b[6];
                float p = (float) (age / LIFE);
                double f = (1 - Math.exp(-2.6 * age)) / 2.6;
                int seed = (int) b[7];
                Vec3d c = new Vec3d(b[0] + b[3] * f + Math.sin(age * 2 + seed) * 0.04 * age, b[1] + b[4] * f + 0.09 * age * age, b[2] + b[5] * f + Math.cos(age * 1.7 + seed) * 0.04 * age);
                if (!Projection.point(c, s)) continue;
                float k = ppb(c) * size.f();
                float r = k * (0.035f + 0.2f * outCubic(p)) * (0.7f + 0.6f * hash(seed));
                float aa = al * (float) Math.pow(1 - p, 1.6) * smooth(p * 6) * G.fadeNear(c);
                G.soft(s[0], s[1], r * 1.6f, 0xFFE4EEF8, 0.10f * aa);
                G.soft(s[0], s[1], r, 0xFFF2F7FF, 0.16f * aa);
            }
        }

        @Override
        void onFadedOut() {
            puffs.clear();
            acc.clear();
        }
    }

    /* ================================================================== 11. ornament orbit */

    static final class OrnamentOrbit extends PV {
        private final Setting.Num count = num("Count", 5, 2, 10, 1);
        private final Setting.Num radius = num("Radius", 0.9, 0.5, 1.8, 0.05);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num speed = num("Speed", 0.8, 0, 3, 0.05);
        private final Setting.Num wave = num("Wave", 1, 0, 2, 0.05);
        private final Setting.Mode pal = mode("Colors", "Classic", "Classic", "Frost", "Candy", "Gold", "Rainbow");
        private final Setting.Bool firstPerson = bool("First Person", false);
        private final List<double[]> balls = new ArrayList<>();

        OrnamentOrbit() {
            super("Ornament Orbit", "Glossy glass Christmas baubles orbit you with reflections and glow");
        }

        private int col(int i) {
            return switch (pal.get()) {
                case "Frost" -> G.pick(new int[]{0xFF5FB6F0, 0xFFE2ECF6, 0xFF3A6FD8}, i);
                case "Candy" -> G.pick(new int[]{0xFFE8182E, 0xFFF4F0F0, 0xFFFF6AA0}, i);
                case "Gold" -> G.pick(new int[]{0xFFE6AC2A, 0xFFF4D890, 0xFFB8781A}, i);
                case "Rainbow" -> 0xFF000000 | java.awt.Color.HSBtoRGB(i / (float) count.i(), 0.75f, 0.95f);
                default -> G.pick(new int[]{0xFFC8102E, 0xFFE6AC2A, 0xFF13803C, 0xFF1F4FC0}, i);
            };
        }

        private Vec3d pos(BodyAnchor ba, int i, int n, double t, float al) {
            double th = t * speed.f() + i * TAU / n;
            double R = radius.f() * (0.4 + 0.6 * outCubic(al));
            double y = 0.95 + Math.sin(th * 2 + t * 0.6) * 0.22 * wave.f() + 0.04 * Math.sin(t * 1.7 + i);
            return ba.at(Math.cos(th) * R, y, Math.sin(th) * R);
        }

        @Override
        void world(float td, double t, float dt, float al) {
            balls.clear();
            if (!thirdPerson() && !firstPerson.on()) return;
            BodyAnchor ba = BodyAnchor.of(mc.player, td);
            int n = count.i();
            G.F f = G.F.body(ba);
            for (int i = 0; i < n; i++) {
                Vec3d c = pos(ba, i, n, t, al);
                double r = 0.085 * size.f() * ba.scale;
                int col = col(i);
                int ii = i;
                G.surface(G.ellip(f, c, r / ba.scale, r / ba.scale, r / ba.scale), 18, 10, (u, v, p, nn) -> {
                    int cc = col;
                    if (pal.is("Classic") && Math.abs(v - 0.5) < 0.07) cc = ii % 2 == 0 ? 0xFFF4F0E8 : 0xFFE6AC2A;
                    if (Math.abs(v - 0.5) < 0.07 && !pal.is("Classic")) cc = G.scale(cc, 1.25);
                    return a(G.lit(cc, p, nn, 0.32f, 1.25f), al);
                }, true);
                Vec3d up = ba.up;
                Vec3d capB = c.add(up.multiply(r * 0.9)), capT = c.add(up.multiply(r * 1.18));
                G.tube(new Vec3d[]{capB, capB.lerp(capT, 0.5), capT}, new double[]{r * 0.28, r * 0.28, r * 0.24}, 10, ba.right, null,
                        (u, v, p, nn) -> a(G.lit(0xFFD8B050, p, nn, 0.45f, 1f), al), true);
                G.torus(new G.F(Vec3d.ZERO, ba.right, ba.fwd, ba.up), capT.add(up.multiply(r * 0.12)), r * 0.13, r * 0.035, 8, 4,
                        (u, v, p, nn) -> a(G.lit(0xFFD8B050, p, nn, 0.45f, 1f), al), true);
                balls.add(new double[]{c.x, c.y, c.z, col, r});
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            Vec3d cam = Projection.camera();
            Nvg.additive(true);
            for (double[] b : balls) {
                Vec3d c = new Vec3d(b[0], b[1], b[2]);
                double r = b[4];
                int col = (int) (long) b[3];
                Vec3d vd = cam.subtract(c).normalize();
                if (Projection.point(c, s)) {
                    float k = ppb(c);
                    G.soft(s[0], s[1], k * (float) r * 3.2f, col, 0.12f * al);
                }
                // window reflection: a highlight toward the light, a faint secondary on the opposite rim
                Vec3d h = c.add(G.LIGHT.add(vd).normalize().multiply(r * 0.98));
                if (Projection.point(h, s)) {
                    float k = ppb(h) * (float) r;
                    G.soft(s[0], s[1], k * 0.55f, 0xFFFFFFFF, 0.35f * al);
                    G.soft(s[0], s[1], k * 0.18f, 0xFFFFFFFF, 0.9f * al);
                }
                Vec3d h2 = c.add(G.LIGHT.multiply(-1).add(vd.multiply(1.6)).normalize().multiply(r * 0.95));
                if (Projection.point(h2, s)) G.soft(s[0], s[1], ppb(h2) * (float) r * 0.4f, Nvg.mix(col, 0xFFFFFFFF, 0.5f), 0.25f * al);
            }
            Nvg.additive(false);
        }
    }

    /* ================================================================== 12. mistletoe */

    static final class Mistletoe extends PV {
        private final Setting.Num range = num("Range", 16, 4, 48, 1);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Bool self = bool("Self", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Bool hearts = bool("Hearts When Close", true);
        private final List<Vec3d> berries = new ArrayList<>();
        private final List<Vec3d> heartsAt = new ArrayList<>();

        Mistletoe() {
            super("Mistletoe", "A sprig of mistletoe with a red bow hangs above nearby players");
        }

        @Override
        void world(float td, double t, float dt, float al) {
            berries.clear();
            heartsAt.clear();
            List<PlayerEntity> ps = near(this, others, range.f());
            if (!self.on()) ps.remove(mc.player);
            for (PlayerEntity p : ps) {
                Vec3d top = p.getLerpedPos(td).add(0, p.getHeight() + 0.55 + 0.04 * Math.sin(t * 1.2 + p.getId()), 0);
                double s = size.f() * outBack(al);
                if (s < 0.02) continue;
                double sw = Math.sin(t * 1.1 + p.getId()) * 0.12;
                G.F f = G.F.yaw(top, t * 0.25 + p.getId(), s);
                // ribbon string up
                Render3D.drawLineGradient(top, top.add(0, 0.6 * s, 0), a(0xFFD01830, 0.9f * al), a(0xFFD01830, 0), 2f, true);
                // bow
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    G.F lf = new G.F(Vec3d.ZERO, f.x.multiply(Math.cos(0.4)).add(f.y.multiply(sgn * Math.sin(0.4))), f.y, f.z);
                    G.surface(G.ellip(lf, top.add(f.d(sgn * 0.06, 0.02, 0)), 0.06, 0.035, 0.022), 10, 6, (u, v, pp, n) -> a(G.lit(0xFFD01830, pp, n, 0.45f, 0.8f), al), true);
                    Vec3d tl = top.add(f.d(sgn * 0.01, -0.01, 0.005)), tr = tl.add(f.d(sgn * 0.05, -0.12, 0));
                    int rc = a(G.lit2(0xFFC01428, tl, f.z.normalize(), 0.5f, 0.6f), al);
                    quad(tl, tl.add(f.d(sgn * 0.025, 0, 0)), tr.add(f.d(sgn * 0.02, 0, 0)), tr, rc, rc, rc, rc, true);
                }
                G.ball(top, 0.022 * s, 8, 0xFFB01024, 0.5f, 0.8f, al, true);
                // leaves: pairs radiating down and out
                for (int i = 0; i < 7; i++) {
                    double an = i * TAU / 7 + 0.3, dn = 0.55 + 0.25 * hash(i + 3);
                    Vec3d dir = f.d(Math.cos(an) * 0.8, -dn - sw * Math.cos(an), Math.sin(an) * 0.8).normalize();
                    Vec3d side = new Vec3d(-Math.sin(an), 0, Math.cos(an));
                    side = side.subtract(dir.multiply(side.dotProduct(dir))).normalize();
                    Vec3d base = top.add(f.d(0, -0.03, 0));
                    G.leaf(base, dir, side, 0.2 * s, 0.045 * s, 0, 0xFF5C9A3A, 0xFF2C6A28, al, true);
                }
                for (int i = 0; i < 6; i++) {
                    double an = i * TAU / 6 + 0.8;
                    Vec3d bpos = top.add(f.d(Math.cos(an) * 0.05, -0.07 - 0.03 * hash(i), Math.sin(an) * 0.05));
                    G.ball(bpos, 0.018 * s, 8, 0xFFF4F2EA, 0.7f, 1f, al, true);
                    berries.add(bpos);
                }
                if (hearts.on() && p != mc.player && p.squaredDistanceTo(mc.player) < 9) heartsAt.add(top);
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            Nvg.additive(true);
            for (Vec3d b : berries) if (Projection.point(b, s)) G.soft(s[0], s[1], ppb(b) * 0.06f, 0xFFFFFFF0, 0.25f * al);
            Nvg.additive(false);
            for (Vec3d h : heartsAt) {
                for (int i = 0; i < 5; i++) {
                    double cyc = G.frac(t * 0.4 + i / 5.0);
                    Vec3d p = h.add(Math.sin(i * 2.1 + t) * 0.25, -0.2 + cyc * 0.7, Math.cos(i * 1.7 + t) * 0.25);
                    if (!Projection.point(p, s)) continue;
                    float k = ppb(p) * 0.07f * (float) (0.6 + 0.4 * Math.sin(Math.PI * cyc));
                    float aa = al * (float) Math.sin(Math.PI * cyc);
                    heart(s[0], s[1], k, a(0xFFFF5A8C, 0.85f * aa));
                    Nvg.additive(true);
                    G.soft(s[0], s[1], k * 2, 0xFFFF5A8C, 0.25f * aa);
                    Nvg.additive(false);
                }
            }
        }

        static void heart(float x, float y, float r, int col) {
            Nvg.beginPath();
            Nvg.moveTo(x, y + r * 0.9f);
            Nvg.bezierTo(x - r * 1.6f, y - r * 0.1f, x - r * 0.8f, y - r * 1.3f, x, y - r * 0.45f);
            Nvg.bezierTo(x + r * 0.8f, y - r * 1.3f, x + r * 1.6f, y - r * 0.1f, x, y + r * 0.9f);
            Nvg.closePath();
            Nvg.fillPath(col);
        }
    }

    /* ================================================================== 13. gingerbread footprints */

    static final class GingerbreadSteps extends PV {
        private final Setting.Num life = num("Duration", 6, 2, 15, 0.5);
        private final Setting.Num size = num("Size", 1, 0.6, 1.6, 0.05);
        private final Setting.Mode shape = mode("Shape", "Boots", "Boots", "Gingerbread Men", "Mixed");
        private final Setting.Bool others = bool("Other Players", true);
        private final List<double[]> prints = new ArrayList<>(); // x y z yaw born side kind
        private final Map<Integer, double[]> st = new HashMap<>(); // lastX lastZ side

        GingerbreadSteps() {
            super("Gingerbread Steps", "You leave iced gingerbread cookie footprints behind you");
        }

        @Override
        void world(float td, double t, float dt, float al) {
            List<PlayerEntity> ps = new ArrayList<>();
            ps.add(mc.player);
            if (others.on()) for (AbstractClientPlayerEntity p : mc.world.getPlayers()) if (p != mc.player && p.isAlive() && p.squaredDistanceTo(mc.player) < 64 * 64) ps.add(p);
            if (enabled()) for (PlayerEntity p : ps) {
                double[] s = st.computeIfAbsent(p.getId(), k -> new double[]{p.getX(), p.getZ(), 1});
                double dx = p.getX() - s[0], dz = p.getZ() - s[1], d = Math.hypot(dx, dz);
                if (d > 4) {
                    s[0] = p.getX();
                    s[1] = p.getZ();
                } else if (d > 0.62 * size.f() && p.isOnGround()) {
                    double yaw = Math.atan2(-dx, dz);
                    s[2] = -s[2];
                    Vec3d r = new Vec3d(-Math.cos(yaw), 0, -Math.sin(yaw));
                    Vec3d at = p.getEntityPos().add(r.multiply(s[2] * 0.13 * size.f()));
                    int kind = shape.is("Boots") ? 0 : shape.is("Gingerbread Men") ? 1 : (int) (hash(prints.size() + (int) (t * 10)) * 2);
                    prints.add(new double[]{at.x, p.getY() + 0.012, at.z, yaw, t, s[2], kind});
                    if (prints.size() > 90) prints.remove(0);
                    s[0] = p.getX();
                    s[1] = p.getZ();
                }
            }
            double L = life.f();
            prints.removeIf(b -> t - b[4] > L);
            for (double[] b : prints) {
                double age = t - b[4];
                float aa = al * (1 - smooth((float) ((age - (L - 1.5)) / 1.5)));
                float pop = outBack((float) (age / 0.3));
                G.F f = G.F.yaw(new Vec3d(b[0], b[1], b[2]), b[3], size.f() * pop);
                if (b[6] == 0) boot(f, b[5], aa);
                else man(f, aa, (int) (b[4] * 100));
            }
        }

        private static Vec3d[] ellipse(G.F f, double cx, double cz, double rx, double rz, int n) {
            Vec3d[] r = new Vec3d[n];
            for (int i = 0; i < n; i++) r[i] = f.p(cx + Math.cos(i * TAU / n) * rx, 0, cz + Math.sin(i * TAU / n) * rz);
            return r;
        }

        private static void icing(Vec3d[] ring, double lift, float al) {
            for (int i = 0; i < ring.length; i++)
                Render3D.drawLineGradient(ring[i].add(0, lift, 0), ring[(i + 1) % ring.length].add(0, lift, 0), a(0xFFFFFDF8, 0.95f * al), a(0xFFFFFDF8, 0.95f * al), 2.2f, true);
        }

        private static final int DOUGH = 0xFFB86A30, DOUGH_DARK = 0xFF7E4218;

        private void boot(G.F f, double side, float al) {
            Vec3d[] sole = ellipse(f, side * 0.01, 0.06, 0.07, 0.1, 22), heel = ellipse(f, 0, -0.11, 0.055, 0.06, 16);
            G.fan(f.p(side * 0.01, 0.004, 0.06), sole, a(DOUGH, al), a(DOUGH_DARK, al), true);
            G.fan(f.p(0, 0.004, -0.11), heel, a(DOUGH, al), a(DOUGH_DARK, al), true);
            Vec3d[] si = ellipse(f, side * 0.01, 0.06, 0.05, 0.078, 22), hi = ellipse(f, 0, -0.11, 0.036, 0.04, 16);
            icing(si, 0.006, al);
            icing(hi, 0.006, al);
            // icing zigzag across the sole
            Vec3d prev = null;
            for (int k = 0; k <= 6; k++) {
                Vec3d p = f.p(side * 0.01 - 0.035 + k * 0.0117, 0.01, 0.06 + (k % 2 == 0 ? 0.025 : -0.025));
                if (prev != null) Render3D.drawLineGradient(prev, p, a(0xFFFF4A6A, 0.9f * al), a(0xFFFF4A6A, 0.9f * al), 2f, true);
                prev = p;
            }
        }

        private void man(G.F f, float al, int seed) {
            double s = 1.15;
            Vec3d[] head = ellipse(f, 0, 0.12 * s, 0.045 * s, 0.045 * s, 16);
            Vec3d[] body = ellipse(f, 0, 0.02 * s, 0.05 * s, 0.07 * s, 18);
            G.fan(f.p(0, 0.004, 0.12 * s), head, a(DOUGH, al), a(DOUGH_DARK, al), true);
            G.fan(f.p(0, 0.004, 0.02 * s), body, a(DOUGH, al), a(DOUGH_DARK, al), true);
            double[][] limbs = {{-0.06, 0.06, -0.09, 0.03}, {0.06, 0.06, 0.09, 0.03}, {-0.03, -0.04, -0.055, -0.12}, {0.03, -0.04, 0.055, -0.12}};
            for (double[] l : limbs) {
                Vec3d a0 = f.p(l[0] * s, 0.005, l[1] * s), a1 = f.p(l[2] * s, 0.005, l[3] * s);
                Vec3d d = a1.subtract(a0).normalize(), n = new Vec3d(-d.z, 0, d.x).multiply(0.024 * s * f.x.length());
                int c = a(DOUGH, al), ce = a(DOUGH_DARK, al);
                quad(a0.add(n), a1.add(n), a1.subtract(n), a0.subtract(n), ce, ce, c, c, true);
                Render3D.drawLineGradient(a0.lerp(a1, 0.55).add(n.multiply(0.6)).add(0, 0.004, 0), a0.lerp(a1, 0.55).subtract(n.multiply(0.6)).add(0, 0.004, 0), a(0xFFFFFDF8, 0.95f * al), a(0xFFFFFDF8, 0.95f * al), 2f, true);
            }
            icing(ellipse(f, 0, 0.12 * s, 0.032 * s, 0.032 * s, 14), 0.007, al);
            int[] btn = {0xFFFF3A50, 0xFF34D070, 0xFFFFFFFF};
            for (int i = 0; i < 3; i++)
                G.ball(f.p(0, 0.012, (0.05 - i * 0.03) * s), 0.009 * f.x.length(), 6, btn[(i + seed) % 3], 0.6f, 0.8f, al, true);
        }

        @Override
        void onFadedOut() {
            prints.clear();
            st.clear();
        }
    }

    /* ================================================================== 14. holly wreath halo */

    static final class HollyWreath extends PV {
        private final Setting.Num size = num("Size", 1, 0.6, 1.6, 0.05);
        private final Setting.Num height = num("Height", 0.22, 0.05, 0.6, 0.01);
        private final Setting.Num spin = num("Spin", 0.2, 0, 1.5, 0.05);
        private final Setting.Num leaves = num("Leaves", 16, 8, 26, 1);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Bool bow = bool("Bow", true);
        private final Setting.Bool others = bool("Other Players", false);
        private final List<Vec3d> berries = new ArrayList<>();
        private final List<double[]> halos = new ArrayList<>();

        HollyWreath() {
            super("Holly Wreath", "A floating halo wreath of glossy holly leaves and red berries");
        }

        @Override
        void world(float td, double t, float dt, float al) {
            berries.clear();
            halos.clear();
            for (PlayerEntity p : targets(others)) {
                BodyAnchor ba = BodyAnchor.of(p, td);
                G.F hf = G.F.head(ba);
                double s = size.f() * outBack(al);
                if (s < 0.02) continue;
                Vec3d c = hf.p(0, BodyAnchor.HEAD + height.f() + 0.025 * Math.sin(t * 1.4), 0);
                double R = 0.27 * s;
                int n = leaves.i();
                double rot = t * spin.f();
                Vec3d X = ba.hRight, Y = ba.hUp, Z = ba.hFwd;
                for (int i = 0; i < n; i++) {
                    double an = i * TAU / n + rot;
                    Vec3d radial = X.multiply(Math.cos(an)).add(Z.multiply(Math.sin(an)));
                    Vec3d tang = X.multiply(-Math.sin(an)).add(Z.multiply(Math.cos(an)));
                    double tilt = (i % 2 == 0 ? 0.55 : -0.45);
                    Vec3d dir = tang.multiply(Math.cos(tilt)).add(radial.multiply(Math.sin(tilt))).normalize();
                    Vec3d side = Y.multiply(0.5).add(radial.multiply(i % 2 == 0 ? -0.5 : 0.5)).normalize();
                    side = side.subtract(dir.multiply(side.dotProduct(dir))).normalize();
                    Vec3d base = c.add(radial.multiply(R)).subtract(dir.multiply(0.07 * s));
                    G.leaf(base, dir, side, 0.16 * s, 0.045 * s, 3, i % 3 == 0 ? 0xFF3E9A48 : 0xFF2E8040, 0xFF145A26, al, true);
                    if (i % 2 == 0) for (int b = 0; b < 3; b++) {
                        double ba2 = an + 0.05 * (b - 1);
                        Vec3d bp = c.add(X.multiply(Math.cos(ba2) * R).add(Z.multiply(Math.sin(ba2) * R))).add(Y.multiply(0.03 * s + (b == 1 ? 0.025 * s : 0)));
                        G.ball(bp, 0.022 * s * ba.scale, 8, 0xFFD8142A, 0.45f, 1.2f, al, true);
                        berries.add(bp);
                    }
                }
                if (bow.on()) {
                    Vec3d front = c.add(Z.multiply(R + 0.03 * s));
                    for (int sgn = -1; sgn <= 1; sgn += 2) {
                        G.F lf = new G.F(Vec3d.ZERO, X.multiply(Math.cos(0.35)).add(Y.multiply(sgn * Math.sin(0.35))), Y, Z);
                        G.surface(G.ellip(lf, front.add(X.multiply(sgn * 0.06 * s)), 0.06 * s, 0.035 * s, 0.022 * s), 10, 6, (u, v, pp, nn) -> a(G.lit(0xFFC81428, pp, nn, 0.45f, 0.9f), al), true);
                        Vec3d tl = front.add(X.multiply(sgn * 0.01 * s)), tr = tl.add(X.multiply(sgn * 0.05 * s)).add(Y.multiply(-0.13 * s));
                        int rc = a(G.lit2(0xFFB01024, tl, Z, 0.5f, 0.6f), al);
                        quad(tl, tl.add(X.multiply(sgn * 0.028 * s)), tr.add(X.multiply(sgn * 0.022 * s)), tr, rc, rc, rc, rc, true);
                    }
                    G.ball(front, 0.024 * s, 8, 0xFFB01024, 0.5f, 0.9f, al, true);
                }
                halos.add(new double[]{c.x, c.y, c.z, R, X.x, X.y, X.z, Z.x, Z.y, Z.z});
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            Nvg.additive(true);
            for (Vec3d b : berries) if (Projection.point(b, s)) G.soft(s[0], s[1], ppb(b) * 0.07f, 0xFFFF3040, 0.22f * al);
            if (glow.on()) for (double[] h : halos) {
                Vec3d c = new Vec3d(h[0], h[1], h[2]), X = new Vec3d(h[4], h[5], h[6]), Z = new Vec3d(h[7], h[8], h[9]);
                for (int i = 0; i < 28; i++) {
                    double an = i * TAU / 28 + t * 0.6;
                    Vec3d p = c.add(X.multiply(Math.cos(an) * h[3])).add(Z.multiply(Math.sin(an) * h[3]));
                    if (!Projection.point(p, s)) continue;
                    float tw = (float) (0.5 + 0.5 * Math.sin(t * 2 + i * 1.3));
                    G.soft(s[0], s[1], ppb(p) * 0.16f, 0xFFFFD27A, 0.08f * al);
                    if (i % 4 == 0) spark(s[0], s[1], ppb(p) * 0.012f, 0xFFFFE0A0, al * tw * 0.8f);
                }
            }
            Nvg.additive(false);
        }
    }

    /* ================================================================== 15. festive kill (snow burst) */

    static final class FestiveKill extends PV {
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num flakes = num("Flakes", 36, 10, 80, 2);
        private final Setting.Num duration = num("Duration", 2.6, 1, 5, 0.1);
        private final Setting.Mode color = mode("Color", "Ice", "Ice", "White", "Festive");
        private final List<KillWatch.Kill> run = new ArrayList<>();
        private int seq;

        FestiveKill() {
            super("Festive Kill Effect", "Your kills explode into a burst of snowflakes with a frost ring");
        }

        @Override
        protected void onEnable() {
            seq = KillWatch.seq;
        }

        private int col(int i) {
            return switch (color.get()) {
                case "White" -> 0xFFFFFFFF;
                case "Festive" -> G.pick(new int[]{0xFFFF4A5A, 0xFF4AE07A, 0xFFFFFFFF, 0xFFFFD050}, i);
                default -> i % 3 == 0 ? 0xFFFFFFFF : 0xFF9AD8FF;
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            KillWatch.poll();
            for (KillWatch.Kill k : KillWatch.events) if (k.seq() > seq) {
                seq = k.seq();
                if (enabled()) run.add(k);
            }
            double D = duration.f();
            run.removeIf(k -> t - k.t() > D);
            for (KillWatch.Kill k : run) {
                float p = (float) ((t - k.t()) / D);
                double R = (0.4 + 2.4 * outCubic(Math.min(1, p * 1.8f))) * size.f();
                float f = (1 - p) * (1 - p) * al;
                Vec3d c = k.pos().add(0, 0.03, 0);
                int seg = 48;
                for (int i = 0; i < seg; i++) {
                    double t0 = i * TAU / seg, t1 = (i + 1) * TAU / seg;
                    double r0 = R * 0.78, r1 = R;
                    Vec3d a0 = c.add(Math.cos(t0) * r0, 0, Math.sin(t0) * r0), a1 = c.add(Math.cos(t1) * r0, 0, Math.sin(t1) * r0);
                    Vec3d b0 = c.add(Math.cos(t0) * r1, 0, Math.sin(t0) * r1), b1 = c.add(Math.cos(t1) * r1, 0, Math.sin(t1) * r1);
                    quad(a0, a1, b1, b0, a(0xFFCDEBFF, 0), a(0xFFCDEBFF, 0), a(0xFFF4FBFF, 0.55f * f), a(0xFFF4FBFF, 0.55f * f), true);
                }
                disc(c, R * 0.8, 40, a(0xFFE6F4FF, 0.22f * f), true);
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            double D = duration.f();
            for (KillWatch.Kill k : run) {
                double age = t - k.t();
                float p = (float) (age / D);
                Vec3d c = k.pos().add(0, k.h() * 0.55, 0);
                if (Projection.point(c, s)) {
                    float kk = ppb(c) * size.f();
                    float fl = (float) Math.exp(-age * 6);
                    Nvg.additive(true);
                    G.soft(s[0], s[1], kk * 1.6f, 0xFFBFE6FF, 0.5f * fl * al);
                    G.soft(s[0], s[1], kk * 0.5f, 0xFFFFFFFF, 0.8f * fl * al);
                    Nvg.additive(false);
                    float gp = outCubic(Math.min(1, p * 2.5f));
                    G.crystal(s[0], s[1], kk * (0.3f + 0.9f * gp), (float) (age * 0.6), 0xFFE8F6FF, al * (1 - p) * 0.6f);
                }
                int n = flakes.i();
                for (int i = 0; i < n; i++) {
                    float h1 = hash(k.seq() * 131 + i * 3), h2 = hash(k.seq() * 131 + i * 3 + 1), h3 = hash(k.seq() * 131 + i * 3 + 2);
                    double an = h1 * TAU, el = (h2 - 0.3) * 1.3, sp = (1.5 + 2.6 * h3) * size.f();
                    double f = (1 - Math.exp(-3 * age)) / 3;
                    Vec3d q = c.add(Math.cos(an) * Math.cos(el) * sp * f + Math.sin(age * 2 + i) * 0.1 * age, Math.sin(el) * sp * f - 0.25 * age * age, Math.sin(an) * Math.cos(el) * sp * f);
                    if (!Projection.point(q, s)) continue;
                    float kk = ppb(q) * size.f();
                    float aa = al * (1 - smooth((p - 0.6f) / 0.4f)) * G.fadeNear(q);
                    if (i % 3 == 0) G.crystal(s[0], s[1], kk * (0.06f + 0.05f * h2), (float) (age * (h1 - 0.5) * 4), col(i), aa);
                    else {
                        G.soft(s[0], s[1], kk * 0.05f, col(i), 0.8f * aa);
                        G.soft(s[0], s[1], kk * 0.015f, 0xFFFFFFFF, aa);
                    }
                }
            }
        }

        @Override
        void onFadedOut() {
            run.clear();
        }
    }
}

package tenacity.modules;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.Nvg;
import tenacity.modules.ChristmasVisuals.G;
import tenacity.modules.ChristmasVisuals.KillWatch;
import tenacity.util.Projection;
import voided.util.render.Render3D;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static tenacity.modules.PremiumVisuals.*;

/** Halloween visuals, built on the same premium base and helpers as {@link ChristmasVisuals}. */
final class HalloweenVisuals {
    private HalloweenVisuals() {
    }

    static List<Module> create() {
        return List.of(new PumpkinHead(), new Ghosts(), new BatSwarm(), new WitchHat(), new SpiderWeb(), new SpookyFog(), new FloatingCandles(),
                new SoulFlames(), new SkullKill(), new HauntedTrail());
    }

    /** Teardrop candle flame (2D, unit = r), additive. */
    static void flame(float x, float y, float r, float sway, float al, int outer, int inner) {
        if (al <= 0.004f || r < 0.4f) return;
        Nvg.additive(true);
        G.soft(x, y - r * 0.6f, r * 4.2f, outer, 0.10f * al);
        G.soft(x, y - r * 0.6f, r * 1.8f, outer, 0.22f * al);
        float tx = x + sway * r, ty = y - r * 2.6f;
        Nvg.beginPath();
        Nvg.moveTo(tx, ty);
        Nvg.bezierTo(x + r * 0.35f + sway * r * 0.5f, y - r * 1.4f, x + r * 1.0f, y - r * 0.5f, x, y + r * 0.55f);
        Nvg.bezierTo(x - r * 1.0f, y - r * 0.5f, x - r * 0.35f + sway * r * 0.5f, y - r * 1.4f, tx, ty);
        Nvg.closePath();
        Nvg.fillPathGradient(x, y + r * 0.5f, tx, ty, a(inner, 0.95f * al), a(outer, 0.15f * al));
        G.soft(x, y - r * 0.15f, r * 0.6f, 0xFFFFFFFF, 0.75f * al);
        Nvg.additive(false);
    }

    /* ================================================================== 1. pumpkin head */

    static final class PumpkinHead extends PV {
        private final Setting.Num size = num("Size", 1, 0.85, 1.4, 0.05);
        private final Setting.Num flicker = num("Flicker", 1, 0, 2, 0.05);
        private final Setting.Mode face = mode("Face", "Classic", "Classic", "Scary", "Happy");
        private final Setting.Mode color = mode("Color", "Orange", "Orange", "White", "Green", "Purple");
        private final Setting.Bool others = bool("Other Players", true);
        private final List<Vec3d[]> glows = new ArrayList<>();

        PumpkinHead() {
            super("Pumpkin Head", "A carved jack-o'-lantern head lit by a flickering candle inside");
        }

        private int skin() {
            return switch (color.get()) {
                case "White" -> 0xFFE8E2D4;
                case "Green" -> 0xFF5E8A2E;
                case "Purple" -> 0xFF5A2E80;
                default -> 0xFFE8761A;
            };
        }

        private double rib(double th) {
            return 0.93 + 0.07 * Math.pow(Math.abs(Math.sin(th * 4)), 0.6);
        }

        /** Point on the pumpkin at angle th (0 = right, PI/2 = front) and latitude ph. */
        private Vec3d at(G.F f, Vec3d c, double rx, double ry, double th, double ph, double lift) {
            double rb = rib(th);
            double cp = Math.cos(ph), sp = Math.sin(ph);
            double dimple = 1 - 0.12 * Math.pow(Math.abs(sp), 6);
            double x = cp * Math.cos(th) * rx * rb, z = cp * Math.sin(th) * rx * rb, y = sp * ry * dimple;
            Vec3d p = c.add(f.d(x, y, z));
            if (lift != 0) p = p.add(f.d(cp * Math.cos(th), sp * 0.6, cp * Math.sin(th)).normalize().multiply(lift));
            return p;
        }

        private float fl(double t, int id) {
            float k = flicker.f();
            return (float) (0.82 + k * (0.1 * Math.sin(t * 11 + id) + 0.06 * Math.sin(t * 23.7 + id * 2) + 0.05 * G.noise(t * 7 + id)));
        }

        @Override
        void world(float td, double t, float dt, float al) {
            glows.clear();
            for (PlayerEntity p : targets(others)) {
                BodyAnchor ba = BodyAnchor.of(p, td);
                G.F f = G.F.head(ba);
                double s = size.f() * (0.4 + 0.6 * outBack(al));
                double rx = 0.42 * s, ry = 0.34 * s;
                Vec3d c = f.p(0, 0.25, 0);
                int sk = skin();
                G.surface((u, v) -> at(f, c, rx, ry, u * TAU, (v - 0.5) * Math.PI, 0), 48, 20, (u, v, pt, n) -> {
                    double groove = 1 - Math.pow(Math.abs(Math.sin(u * TAU * 4)), 0.6);
                    int cc = G.scale(sk, 1 - 0.28 * groove - 0.12 * Math.pow(Math.abs(v - 0.5) * 2, 3));
                    return a(G.lit(cc, pt, n, 0.38f, 0.35f), al);
                }, true);
                // stem
                Vec3d[] sc = new Vec3d[7];
                double[] sr = new double[7];
                for (int j = 0; j < 7; j++) {
                    double v = j / 6.0;
                    sc[j] = c.add(f.d(0.03 * v * v, ry * 0.86 + 0.13 * s * v, -0.04 * v * v * s));
                    sr[j] = (0.05 - 0.018 * v) * s * ba.scale;
                }
                G.tube(sc, sr, 10, f.z(), (u, v) -> 1 + 0.12 * Math.sin(u * TAU * 5), (u, v, pt, n) -> a(G.lit(Nvg.mix(0xFF3E5A1C, 0xFF7A6A34, (float) v), pt, n, 0.4f, 0.2f), al), true);
                // curly vine
                Vec3d[] vc = new Vec3d[16];
                double[] vr = new double[16];
                for (int j = 0; j < 16; j++) {
                    double v = j / 15.0, an = v * TAU * 1.6;
                    vc[j] = c.add(f.d(0.04 + 0.12 * v * s + 0.05 * Math.cos(an) * s, ry * 0.9 + 0.05 * Math.sin(an) * s + 0.02, 0.05 * Math.sin(an) * s * 0.5));
                    vr[j] = 0.008 * s * ba.scale * (1 - v * 0.6);
                }
                G.tube(vc, vr, 6, f.z(), null, (u, v, pt, n) -> a(G.lit(0xFF4E7A22, pt, n, 0.45f, 0.2f), al), true);
                // carved face: glowing polygons on the surface
                float fk = fl(t, p.getId());
                int in = Nvg.mix(0xFFFF9A20, 0xFFFFF0A0, 0.6f * fk), out = 0xFFFF7A10;
                List<Vec3d> gl = new ArrayList<>();
                for (double[][] poly : faceShapes()) {
                    Vec3d[] pts = new Vec3d[poly.length];
                    double cx = 0, cy = 0;
                    for (int i = 0; i < poly.length; i++) {
                        pts[i] = face(f, c, rx, ry, poly[i][0], poly[i][1], 0.004);
                        cx += poly[i][0];
                        cy += poly[i][1];
                    }
                    Vec3d mid = face(f, c, rx, ry, cx / poly.length, cy / poly.length, 0.004);
                    G.fan(mid, pts, a(in, al), a(out, al), true);
                    gl.add(mid);
                }
                double face = ba.hFwd.dotProduct(Projection.camera().subtract(c).normalize());
                gl.add(new Vec3d(smooth((float) (face * 2.5 + 0.35)), 0, 0));
                glows.add(gl.toArray(new Vec3d[0]));
            }
        }

        private Vec3d face(G.F f, Vec3d c, double rx, double ry, double fx, double fy, double lift) {
            return at(f, c, rx, ry, Math.PI / 2 - fx * 1.7, fy * 1.25, lift);
        }

        /** Face polygons in (fx right, fy up) coordinates, each convex enough for a centre fan. */
        private List<double[][]> faceShapes() {
            List<double[][]> l = new ArrayList<>();
            String fc = face.get();
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                if (fc.equals("Happy")) l.add(new double[][]{{sgn * 0.32, 0.30}, {sgn * 0.20, 0.14}, {sgn * 0.44, 0.14}});
                else if (fc.equals("Scary")) l.add(new double[][]{{sgn * 0.08, 0.16}, {sgn * 0.42, 0.30}, {sgn * 0.36, 0.08}});
                else l.add(new double[][]{{sgn * 0.30, 0.34}, {sgn * 0.14, 0.10}, {sgn * 0.46, 0.10}});
            }
            l.add(new double[][]{{0, 0.06}, {-0.07, -0.05}, {0.07, -0.05}});
            // mouth: a jagged band split into slices (each a convex quad)
            int n = 9;
            double w = 0.5;
            for (int i = 0; i < n; i++) {
                double x0 = -w + 2 * w * i / n, x1 = -w + 2 * w * (i + 1) / n;
                l.add(new double[][]{{x0, mTop(x0, fc)}, {x1, mTop(x1, fc)}, {x1, mBot(x1, fc)}, {x0, mBot(x0, fc)}});
            }
            return l;
        }

        private static double mTop(double x, String fc) {
            double curve = fc.equals("Happy") ? 0.22 * x * x : fc.equals("Scary") ? 0.05 : 0.14 * x * x;
            double tooth = fc.equals("Scary") ? 0.07 * Math.max(0, Math.cos(x * 22)) : (Math.abs(x - 0.17) < 0.06 || Math.abs(x + 0.17) < 0.06) ? -0.07 : 0;
            return -0.13 + curve - tooth;
        }

        private static double mBot(double x, String fc) {
            double curve = fc.equals("Happy") ? 0.42 * x * x : 0.30 * x * x;
            double tooth = fc.equals("Scary") ? 0.07 * Math.max(0, Math.cos(x * 22 + 1.4)) : Math.abs(x) < 0.06 ? 0.07 : 0;
            double half = 1 - Math.pow(Math.abs(x) / 0.5, 6);
            return -0.13 + curve - (0.18 * half) + tooth;
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            Nvg.additive(true);
            int id = 0;
            for (Vec3d[] g : glows) {
                float fk = fl(t, id++) * (float) g[g.length - 1].x;
                for (int i = 0; i < g.length - 1; i++) {
                    Vec3d p = g[i];
                    if (!Projection.point(p, s)) continue;
                    float k = ppb(p) * size.f();
                    G.soft(s[0], s[1], k * (i < 3 ? 0.13f : 0.09f), 0xFFFFB040, 0.22f * al * fk);
                }
                if (g.length > 3) {
                    Vec3d m = g[2];
                    if (Projection.point(m, s)) {
                        float k = ppb(m) * size.f();
                        G.soft(s[0], s[1], k * 0.9f, 0xFFFF8A20, 0.10f * al * fk);
                    }
                }
            }
            Nvg.additive(false);
        }
    }

    /* ================================================================== 2. ghost companions */

    static final class Ghosts extends PV {
        private final Setting.Num count = num("Ghosts", 3, 1, 5, 1);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num follow = num("Follow Speed", 1, 0.3, 3, 0.1);
        private final Setting.Mode color = mode("Color", "White", "White", "Spectral", "Lilac", "Ecto");
        private final Vec3d[] pos = new Vec3d[5];
        private final List<Deque<double[]>> trails = new ArrayList<>();

        Ghosts() {
            super("Ghost Companions", "Cute wispy ghosts float around you leaving misty trails");
            for (int i = 0; i < 5; i++) trails.add(new ArrayDeque<>());
        }

        private int col() {
            return switch (color.get()) {
                case "Spectral" -> 0xFF9AF0FF;
                case "Lilac" -> 0xFFD8B8FF;
                case "Ecto" -> 0xFFB8FFB0;
                default -> 0xFFF4F8FF;
            };
        }

        private Vec3d target(int i, int n, float td, double t) {
            Vec3d o = mc.player.getLerpedPos(td);
            double an = t * 0.45 + i * TAU / n;
            double R = 1.3 + 0.25 * Math.sin(t * 0.7 + i * 2);
            return o.add(Math.cos(an) * R, 1.5 + 0.3 * Math.sin(t * 1.1 + i * 1.3), Math.sin(an) * R);
        }

        @Override
        void world(float td, double t, float dt, float al) {
            int n = count.i();
            for (int i = 0; i < n; i++) {
                Vec3d tg = target(i, n, td, t);
                if (pos[i] == null || pos[i].squaredDistanceTo(tg) > 200) pos[i] = tg;
                pos[i] = pos[i].add(tg.subtract(pos[i]).multiply(ease(dt, (1.6f + i * 0.35f) * follow.f())));
                Deque<double[]> tr = trails.get(i);
                double[] last = tr.peekLast();
                if (last == null || pos[i].squaredDistanceTo(last[0], last[1], last[2]) > 0.0016) tr.addLast(new double[]{pos[i].x, pos[i].y, pos[i].z, t});
                while (!tr.isEmpty() && (t - tr.peekFirst()[3] > 0.9 || tr.size() > 60)) tr.pollFirst();
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2], s2 = new float[2];
            int c = col(), n = count.i();
            for (int i = 0; i < n; i++) {
                if (pos[i] == null) continue;
                // misty trail
                Nvg.additive(true);
                for (double[] q : trails.get(i)) {
                    Vec3d p = new Vec3d(q[0], q[1] - 0.12 * size.f(), q[2]);
                    if (!Projection.point(p, s)) continue;
                    float age = (float) ((t - q[3]) / 0.9);
                    G.soft(s[0], s[1], ppb(p) * size.f() * 0.16f * (1 - age * 0.5f), c, 0.05f * (1 - age) * al);
                }
                Nvg.additive(false);
                Vec3d p = pos[i];
                if (!Projection.point(p, s)) continue;
                float r = ppb(p) * 0.17f * size.f() * (0.3f + 0.7f * outBack(al));
                if (r < 1) continue;
                float tilt = 0;
                Deque<double[]> tr = trails.get(i);
                if (tr.size() > 3) {
                    double[] old = tr.peekFirst();
                    if (Projection.point(new Vec3d(old[0], old[1], old[2]), s2)) tilt = MathHelper.clamp((s[0] - s2[0]) / (r * 8), -0.5f, 0.5f);
                }
                ghost(s[0], s[1], r, tilt, t + i * 1.7, c, al * G.fadeNear(p));
            }
        }

        static void ghost(float x, float y, float r, float tilt, double t, int c, float al) {
            Nvg.additive(true);
            G.soft(x, y + r * 0.3f, r * 3.2f, c, 0.12f * al);
            Nvg.additive(false);
            Nvg.save();
            Nvg.translate(x, y);
            Nvg.rotate(tilt);
            Nvg.beginPath();
            Nvg.moveTo(-1 * r, 0);
            Nvg.bezierTo(-1 * r, -1.35f * r, 1 * r, -1.35f * r, 1 * r, 0);
            float sw = (float) Math.sin(t * 2.4) * 0.12f * r;
            Nvg.bezierTo(1.05f * r, 0.6f * r, 0.95f * r + sw, 1.1f * r, 0.9f * r + sw * 1.6f, 1.55f * r);
            int k = 4;
            for (int i = 0; i < k; i++) {
                float x0 = (0.9f - 1.8f * i / k) * r + sw * 1.6f, x1 = (0.9f - 1.8f * (i + 1) / k) * r + sw * 1.6f;
                float wave = (float) Math.sin(t * 5 + i * 1.7) * 0.12f * r;
                Nvg.quadTo((x0 + x1) / 2, 1.15f * r + wave, x1, 1.55f * r - wave * 0.5f);
            }
            Nvg.bezierTo(-0.95f * r + sw, 1.1f * r, -1.05f * r, 0.6f * r, -1 * r, 0);
            Nvg.closePath();
            Nvg.fillPathGradient(0, -1 * r, 0, 1.6f * r, a(Nvg.mix(c, 0xFFFFFFFF, 0.5f), 0.92f * al), a(c, 0.05f * al));
            // eyes + mouth
            float blink = (float) (G.frac(t * 0.23) < 0.03 ? 0.15 : 1);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                Nvg.save();
                Nvg.translate(sgn * 0.36f * r, -0.25f * r);
                Nvg.scale(1, blink);
                Nvg.beginPath();
                Nvg.circlePath(0, 0, 0.15f * r);
                Nvg.fillPath(a(0xFF1A1428, 0.9f * al));
                Nvg.restore();
            }
            Nvg.beginPath();
            Nvg.circlePath(0, 0.12f * r, (0.09f + 0.03f * (float) Math.sin(t * 3)) * r);
            Nvg.fillPath(a(0xFF1A1428, 0.75f * al));
            Nvg.restore();
        }

        @Override
        void onFadedOut() {
            java.util.Arrays.fill(pos, null);
            for (Deque<double[]> d : trails) d.clear();
        }
    }

    /* ================================================================== 3. bat swarm */

    static final class BatSwarm extends PV {
        private final Setting.Num count = num("Bats", 12, 3, 40, 1);
        private final Setting.Num radius = num("Radius", 1.6, 0.8, 4, 0.1);
        private final Setting.Num speed = num("Speed", 1, 0.3, 3, 0.05);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Bool eyes = bool("Glowing Eyes", true);
        private final Setting.Mode color = mode("Rim Glow", "Purple", "Purple", "Red", "Orange", "None");

        BatSwarm() {
            super("Bat Swarm", "A swarm of flapping bats circles around you");
        }

        private Vec3d at(Vec3d o, int i, double t) {
            float h1 = hash(i * 5 + 1), h2 = hash(i * 5 + 2), h3 = hash(i * 5 + 3);
            double dir = h3 > 0.25 ? 1 : -1;
            double an = h1 * TAU + t * speed.f() * (0.7 + 0.7 * h2) * dir;
            double R = radius.f() * (0.7 + 0.6 * h2) + 0.25 * Math.sin(t * 1.3 + i);
            double y = 1.3 + (h3 - 0.5) * 1.4 + 0.3 * Math.sin(an * 2 + t * 0.9);
            return o.add(Math.cos(an) * R, y, Math.sin(an) * R);
        }

        private int rim() {
            return switch (color.get()) {
                case "Red" -> 0xFFFF3040;
                case "Orange" -> 0xFFFF8A20;
                case "None" -> 0;
                default -> 0xFFA050FF;
            };
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            Vec3d o = mc.player.getLerpedPos(td);
            float[] s = new float[2], s2 = new float[2];
            int n = count.i(), rc = rim();
            float grow = outCubic(al);
            for (int i = 0; i < n; i++) {
                if (i >= n * grow + 0.5f) break;
                Vec3d p = at(o, i, t);
                if (!Projection.point(p, s)) continue;
                float r = ppb(p) * 0.16f * size.f() * (0.8f + 0.4f * hash(i * 9));
                if (r < 0.8f) continue;
                float bank = 0;
                if (Projection.point(at(o, i, t + 0.06), s2)) bank = MathHelper.clamp((s2[1] - s[1]) / (r * 2), -0.6f, 0.6f) * Math.signum(s2[0] - s[0]);
                float flap = (float) Math.sin(t * (13 + hash(i) * 5) * speed.f() + i * 2.1);
                bat(s[0], s[1], r, flap, bank, rc, eyes.on(), al * G.fadeNear(p));
            }
        }

        static void bat(float x, float y, float r, float flap, float bank, int rim, boolean eyes, float al) {
            if (rim != 0) {
                Nvg.additive(true);
                G.soft(x, y, r * 2.2f, rim, 0.10f * al);
                Nvg.additive(false);
            }
            Nvg.save();
            Nvg.translate(x, y);
            Nvg.rotate(bank);
            Nvg.scale(r, r);
            float tip = -0.55f * flap, mid = -0.25f * flap;
            int body = a(0xFF15101C, 0.95f * al);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                Nvg.beginPath();
                Nvg.moveTo(0, -0.12f);
                Nvg.quadTo(sgn * 0.45f, -0.3f + mid, sgn * 1.15f, tip - 0.08f);
                // scalloped trailing edge back to the body
                Nvg.quadTo(sgn * 0.98f, tip + 0.12f, sgn * 0.84f, tip * 0.7f + 0.22f);
                Nvg.quadTo(sgn * 0.66f, mid + 0.14f, sgn * 0.52f, mid * 0.6f + 0.3f);
                Nvg.quadTo(sgn * 0.34f, 0.16f, sgn * 0.18f, 0.24f);
                Nvg.lineTo(0, 0.12f);
                Nvg.closePath();
                Nvg.fillPath(body);
                // finger bones
                for (int k = 0; k < 2; k++) {
                    float fx = sgn * (0.84f - k * 0.32f), fy = k == 0 ? tip * 0.7f + 0.22f : mid * 0.6f + 0.3f;
                    Nvg.line(sgn * 0.2f, -0.1f, fx, fy - 0.04f, 0.035f, a(0xFF2A2036, 0.9f * al));
                }
            }
            Nvg.beginPath();
            Nvg.moveTo(-0.11f, -0.2f);
            Nvg.lineTo(-0.15f, -0.42f);
            Nvg.lineTo(-0.04f, -0.27f);
            Nvg.lineTo(0.04f, -0.27f);
            Nvg.lineTo(0.15f, -0.42f);
            Nvg.lineTo(0.11f, -0.2f);
            Nvg.closePath();
            Nvg.fillPath(body);
            Nvg.beginPath();
            Nvg.circlePath(0, 0.0f, 0.17f);
            Nvg.fillPath(body);
            Nvg.restore();
            if (eyes) {
                Nvg.additive(true);
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    float ex = x + (float) (Math.cos(bank) * sgn * 0.065f * r + Math.sin(bank) * 0.12f * r), ey = y + (float) (Math.sin(bank) * sgn * 0.065f * r - Math.cos(bank) * 0.12f * r);
                    G.soft(ex, ey, r * 0.12f, 0xFFFF4020, 0.9f * al);
                    G.soft(ex, ey, r * 0.04f, 0xFFFFE0A0, al);
                }
                Nvg.additive(false);
            }
        }
    }

    /* ================================================================== 4. witch hat */

    static final class WitchHat extends PV {
        private final Setting.Num size = num("Size", 1, 0.6, 1.6, 0.05);
        private final Setting.Num bend = num("Bend", 1, 0, 2, 0.05);
        private final Setting.Mode color = mode("Color", "Classic", "Classic", "Midnight", "Emerald", "Plum");
        private final Setting.Bool dust = bool("Magic Dust", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final List<Vec3d> tips = new ArrayList<>();

        WitchHat() {
            super("Witch Hat", "A crooked, wrinkled witch hat with a wavy brim, band and buckle");
        }

        private int[] cols() {
            return switch (color.get()) {
                case "Midnight" -> new int[]{0xFF141C3A, 0xFFB8C4DC, 0xFFE0E8F4};
                case "Emerald" -> new int[]{0xFF103420, 0xFFFF8A20, 0xFFFFD050};
                case "Plum" -> new int[]{0xFF3A1240, 0xFF30E080, 0xFFE0C050};
                default -> new int[]{0xFF1C1424, 0xFF7A2AC0, 0xFFE8C048};
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            tips.clear();
            for (PlayerEntity p : targets(others)) {
                BodyAnchor ba = BodyAnchor.of(p, td);
                G.F f = G.F.head(ba);
                double s = size.f() * (0.35 + 0.65 * outBack(al)), bd = bend.f();
                int[] c = cols();
                double by = BodyAnchor.HEAD - 0.05;
                // brim
                int nu = 48, nv = 6;
                double r0 = 0.26 * s, r1 = 0.62 * s;
                Vec3d[][] P = new Vec3d[nu + 1][nv + 1];
                for (int i = 0; i <= nu; i++)
                    for (int j = 0; j <= nv; j++) {
                        double th = (i == nu ? 0 : i / (double) nu) * TAU, v = j / (double) nv, r = r0 + (r1 - r0) * v;
                        double y = by + 0.03 * Math.sin(th * 3 + t * 0.8) * v * v * s + 0.06 * v * v * v * s - 0.01 * v * s;
                        P[i][j] = f.p(Math.cos(th) * r, y, Math.sin(th) * r);
                    }
                for (int i = 0; i < nu; i++)
                    for (int j = 0; j < nv; j++) {
                        Vec3d n = P[i + 1][j].subtract(P[i][j]).crossProduct(P[i][j + 1].subtract(P[i][j]));
                        n = n.lengthSquared() < 1e-14 ? ba.hUp : n.normalize();
                        int c0 = a(G.lit2(G.scale(c[0], 1 + 0.25 * j / nv), P[i][j], n, 0.45f, 0.25f), al);
                        int c1 = a(G.lit2(G.scale(c[0], 1 + 0.25 * (j + 1) / nv), P[i][j + 1], n, 0.45f, 0.25f), al);
                        quad(P[i][j], P[i + 1][j], P[i + 1][j + 1], P[i][j + 1], c0, c0, c1, c1, true);
                    }
                // cone
                int cv = 22;
                Vec3d[] cc = new Vec3d[cv + 1];
                double[] rr = new double[cv + 1];
                for (int j = 0; j <= cv; j++) {
                    double v = j / (double) cv;
                    double kink = Math.max(0, v - 0.55);
                    cc[j] = f.p(0.10 * bd * kink * kink * 4 * s, by + (0.66 * v - 0.12 * bd * kink * kink * 3) * s, (-0.16 * bd * Math.pow(v, 2.5) - 0.18 * bd * kink * kink * 2) * s);
                    rr[j] = (0.25 * Math.pow(1 - v, 1.05) + 0.008) * s * ba.scale;
                }
                G.tube(cc, rr, 24, f.z(), (u, v) -> 1 + 0.05 * Math.sin(v * TAU * 3 + u * 2) * v, (u, v, pt, n) -> {
                    boolean band = v > 0.03 && v < 0.15;
                    int base = band ? c[1] : c[0];
                    return a(G.lit(base, pt, n, band ? 0.5f : 0.42f, band ? 0.5f : 0.22f), al);
                }, true);
                // buckle on the band front
                Vec3d fc = cc[2].add(ba.hFwd.multiply(rr[2] * 1.03));
                Vec3d bu = ba.hRight.multiply(0.06 * s), bv = cc[3].subtract(cc[1]).normalize().multiply(0.045 * s);
                int gc = a(G.lit(c[2], fc, ba.hFwd, 0.55f, 1.2f), al);
                Vec3d[] sq = {fc.subtract(bu).subtract(bv), fc.add(bu).subtract(bv), fc.add(bu).add(bv), fc.subtract(bu).add(bv)};
                for (int k = 0; k < 4; k++) {
                    Vec3d a0 = sq[k], a1 = sq[(k + 1) % 4];
                    Vec3d in0 = a0.lerp(fc, 0.3), in1 = a1.lerp(fc, 0.3);
                    quad(a0, a1, in1, in0, gc, gc, gc, gc, true);
                }
                tips.add(cc[cv]);
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            if (!dust.on()) return;
            float[] s = new float[2];
            int c = cols()[2];
            Nvg.additive(true);
            for (Vec3d tip : tips)
                for (int i = 0; i < 12; i++) {
                    float h1 = hash(i * 3 + 11), h2 = hash(i * 3 + 12);
                    double cyc = G.frac(t * 0.4 + h1);
                    Vec3d p = tip.add(Math.cos(h2 * TAU + t) * 0.15 * cyc, -cyc * 0.6 + 0.02, Math.sin(h2 * TAU + t) * 0.15 * cyc);
                    if (!Projection.point(p, s)) continue;
                    float tw = (float) Math.pow(0.5 + 0.5 * Math.sin(t * 6 + i * 2), 2);
                    spark(s[0], s[1], ppb(p) * 0.013f, Nvg.mix(c, cols()[1], h2), al * (float) (1 - cyc) * (0.4f + 0.6f * tw));
                }
            Nvg.additive(false);
        }
    }

    /* ================================================================== 5. spider web */

    static final class SpiderWeb extends PV {
        private final Setting.Num size = num("Size", 1, 0.4, 2, 0.05);
        private final Setting.Mode corners = mode("Corners", "Top", "Top", "All", "Top Left", "Top Right");
        private final Setting.Bool spider = bool("Spider", true);
        private final Setting.Bool cross = bool("Crosshair Web", false);
        private final Setting.Num opacity = num("Opacity", 0.55, 0.1, 1, 0.05);

        SpiderWeb() {
            super("Spider Web", "Delicate dewy spider webs in the screen corners with a dangling spider");
        }

        @Override
        float fadeRate() {
            return 3.5f;
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float W = Nvg.w, H = Nvg.h;
            float L = Math.min(W, H) * 0.3f * size.f();
            String c = corners.get();
            boolean tl = !c.equals("Top Right"), tr = !c.equals("Top Left"), bot = c.equals("All");
            if (tl) web(0, 0, 1, 1, L, t, al, 1);
            if (tr) web(W, 0, -1, 1, L * 0.85f, t, al, 2);
            if (bot) {
                web(0, H, 1, -1, L * 0.7f, t, al, 3);
                web(W, H, -1, -1, L * 0.8f, t, al, 4);
            }
            if (cross.on()) web2(W / 2, H / 2, 22 * size.f(), t, al * 0.6f);
            if (spider.on()) {
                float x = W * (tr ? 0.86f : 0.14f);
                float len = H * 0.2f * size.f() * (float) (0.65 + 0.35 * Math.sin(t * 0.55)) * outCubic(al);
                float sway = (float) Math.sin(t * 0.9) * 6;
                Nvg.line(x, 0, x + sway, len, 1, a(0xFFFFFFFF, 0.45f * al * opacity.f()));
                spider(x + sway, len + 8 * size.f(), 9 * size.f(), t, al);
            }
        }

        private void web(float cx, float cy, int dx, int dy, float L, double t, float al, int seed) {
            int rad = 7, rings = 9;
            float op = opacity.f() * al;
            float[][] px = new float[rad][rings + 1], py = new float[rad][rings + 1];
            float grow = outCubic(al);
            for (int i = 0; i < rad; i++) {
                double an = Math.toRadians(4 + i * 82.0 / (rad - 1)) + 0.03 * Math.sin(t * 0.7 + i + seed);
                float len = L * (0.75f + 0.35f * hash(seed * 17 + i)) * grow;
                for (int j = 0; j <= rings; j++) {
                    float f = j / (float) rings;
                    px[i][j] = cx + dx * (float) Math.cos(an) * len * f;
                    py[i][j] = cy + dy * (float) Math.sin(an) * len * f;
                }
                Nvg.lineGradient(cx, cy, px[i][rings], py[i][rings], 1.1f, a(0xFFFFFFFF, 0.6f * op), a(0xFFFFFFFF, 0.25f * op));
            }
            for (int j = 2; j <= rings; j++) {
                Nvg.beginPath();
                Nvg.moveTo(px[0][j], py[0][j]);
                for (int i = 1; i < rad; i++) {
                    float mx = (px[i - 1][j] + px[i][j]) / 2, my = (py[i - 1][j] + py[i][j]) / 2;
                    mx += (cx - mx) * 0.12f;
                    my += (cy - my) * 0.12f;
                    Nvg.quadTo(mx, my, px[i][j], py[i][j]);
                }
                Nvg.strokePath(0.9f, a(0xFFFFFFFF, op * (0.25f + 0.25f * j / rings)));
            }
            Nvg.additive(true);
            for (int i = 0; i < rad; i++)
                for (int j = 3; j <= rings; j += 2) {
                    if (hash(seed * 101 + i * 13 + j) < 0.6f) continue;
                    float tw = (float) Math.pow(0.5 + 0.5 * Math.sin(t * 1.4 + i * 3 + j), 6);
                    spark(px[i][j], py[i][j], 1.3f, 0xFFE8F4FF, op * (0.35f + 0.65f * tw));
                }
            Nvg.additive(false);
        }

        private void web2(float cx, float cy, float r, double t, float al) {
            for (int i = 0; i < 8; i++) {
                double an = i * TAU / 8;
                Nvg.line(cx + (float) Math.cos(an) * r * 0.3f, cy + (float) Math.sin(an) * r * 0.3f, cx + (float) Math.cos(an) * r, cy + (float) Math.sin(an) * r, 0.8f, a(0xFFFFFFFF, 0.5f * al));
            }
            for (int j = 1; j <= 3; j++) {
                float rr = r * (0.3f + 0.7f * j / 3f);
                Nvg.beginPath();
                for (int i = 0; i <= 8; i++) {
                    double an = i * TAU / 8;
                    float x = cx + (float) Math.cos(an) * rr, y = cy + (float) Math.sin(an) * rr;
                    if (i == 0) Nvg.moveTo(x, y);
                    else {
                        double am = (i - 0.5) * TAU / 8;
                        Nvg.quadTo(cx + (float) Math.cos(am) * rr * 0.86f, cy + (float) Math.sin(am) * rr * 0.86f, x, y);
                    }
                }
                Nvg.strokePath(0.8f, a(0xFFFFFFFF, 0.4f * al));
            }
        }

        private void spider(float x, float y, float r, double t, float al) {
            int c = a(0xFF120E16, 0.95f * al);
            for (int sgn = -1; sgn <= 1; sgn += 2)
                for (int k = 0; k < 4; k++) {
                    float wig = (float) Math.sin(t * 6 + k * 1.3 + sgn) * 0.12f;
                    float ang = (float) (-0.9 + k * 0.6 + wig);
                    float kx = x + sgn * (float) Math.cos(ang) * r * 1.1f, ky = y + (float) Math.sin(ang) * r * 0.9f - r * 0.5f;
                    float ex = x + sgn * (float) Math.cos(ang) * r * 2.0f, ey = y + (float) Math.sin(ang) * r * 1.2f + r * 0.5f;
                    Nvg.line(x, y, kx, ky, r * 0.16f, c);
                    Nvg.line(kx, ky, ex, ey, r * 0.13f, c);
                }
            Nvg.beginPath();
            Nvg.circlePath(x, y + r * 0.55f, r * 0.75f);
            Nvg.fillPath(c);
            Nvg.beginPath();
            Nvg.circlePath(x, y - r * 0.2f, r * 0.45f);
            Nvg.fillPath(c);
            Nvg.glowDisc(x - r * 0.25f, y + r * 0.25f, r * 0.35f, a(0xFF6A5A80, 0.5f * al), a(0xFF6A5A80, 0));
            Nvg.additive(true);
            G.soft(x - r * 0.16f, y - r * 0.25f, r * 0.14f, 0xFFFF3030, al);
            G.soft(x + r * 0.16f, y - r * 0.25f, r * 0.14f, 0xFFFF3030, al);
            Nvg.additive(false);
        }
    }

    /* ================================================================== 6. spooky fog */

    static final class SpookyFog extends PV {
        private final Setting.Num radius = num("Radius", 7, 3, 16, 0.5);
        private final Setting.Num density = num("Density", 1, 0.3, 2.5, 0.05);
        private final Setting.Num height = num("Height", 1, 0.4, 2.5, 0.05);
        private final Setting.Num drift = num("Drift", 1, 0, 3, 0.05);
        private final Setting.Mode color = mode("Color", "Ghostly", "Ghostly", "Toxic", "Violet", "Blood Moon");

        SpookyFog() {
            super("Spooky Fog", "Thick rolling ground fog creeps around your feet");
        }

        @Override
        float fadeRate() {
            return 2.5f;
        }

        private int[] cols() {
            return switch (color.get()) {
                case "Toxic" -> new int[]{0xFF7AD860, 0xFF2E7A40};
                case "Violet" -> new int[]{0xFFB088E8, 0xFF4E2E80};
                case "Blood Moon" -> new int[]{0xFFE87070, 0xFF6A1A24};
                default -> new int[]{0xFFD8E2EA, 0xFF7A8A9A};
            };
        }

        private double ground(int x, int z, int py) {
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int dy = 1; dy >= -4; dy--) {
                m.set(x, py + dy - 1, z);
                if (!mc.world.getBlockState(m).getCollisionShape(mc.world, m).isEmpty()) {
                    m.set(x, py + dy, z);
                    if (mc.world.getBlockState(m).getCollisionShape(mc.world, m).isEmpty()) return py + dy;
                }
            }
            return Double.NaN;
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            Vec3d o = mc.player.getLerpedPos(td);
            Vec3d cam = Projection.camera();
            double R = radius.f(), cell = 1.6;
            int py = MathHelper.floor(o.y + 0.2);
            int cx0 = MathHelper.floor((o.x - R) / cell), cx1 = MathHelper.floor((o.x + R) / cell);
            int cz0 = MathHelper.floor((o.z - R) / cell), cz1 = MathHelper.floor((o.z + R) / cell);
            int[] c = cols();
            float[] s = new float[2];
            double dr = drift.f();
            for (int layer = 0; layer < 2; layer++)
                for (int gx = cx0; gx <= cx1; gx++)
                    for (int gz = cz0; gz <= cz1; gz++) {
                        int id = gx * 73856093 ^ gz * 19349663 ^ layer * 83492791;
                        float h1 = hash(id), h2 = hash(id + 1), h3 = hash(id + 2);
                        double x = (gx + h1) * cell + Math.sin(t * 0.13 * dr + h2 * 9) * 0.9 + t * 0.12 * dr, z = (gz + h2) * cell + Math.cos(t * 0.11 * dr + h1 * 7) * 0.9;
                        double ddx = x - o.x, ddz = z - o.z, d2 = Math.sqrt(ddx * ddx + ddz * ddz);
                        if (d2 > R) continue;
                        double g = ground(MathHelper.floor(x), MathHelper.floor(z), py);
                        if (Double.isNaN(g)) continue;
                        double y = g + (0.12 + layer * 0.35 + h3 * 0.15) * height.f() + 0.05 * Math.sin(t * 0.5 + h1 * 20);
                        Vec3d p = new Vec3d(x, y, z);
                        if (!Projection.point(p, s)) continue;
                        float k = ppb(p);
                        float edge = 1 - smooth((float) ((d2 - R * 0.55) / (R * 0.45)));
                        float near = smooth((float) ((p.distanceTo(cam) - 0.6) / 2.0));
                        float breathe = (float) (0.75 + 0.25 * Math.sin(t * 0.4 + h3 * 30));
                        float aa = al * edge * near * breathe * density.f() * (layer == 0 ? 0.15f : 0.09f);
                        if (aa < 0.003f) continue;
                        int col = Nvg.mix(c[0], c[1], h3 * 0.6f + layer * 0.3f);
                        G.soft(s[0], s[1], k * (float) cell * (1.1f + 0.5f * h3) * (layer == 0 ? 1 : 1.3f), col, aa);
                    }
        }
    }

    /* ================================================================== 7. floating candles */

    static final class FloatingCandles extends PV {
        private final Setting.Num count = num("Candles", 6, 2, 12, 1);
        private final Setting.Num radius = num("Radius", 1.1, 0.6, 2.5, 0.05);
        private final Setting.Num height = num("Height", 2.2, 1.4, 3.5, 0.05);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Mode wax = mode("Wax", "Ivory", "Ivory", "Black", "Purple", "Bone");
        private final Setting.Mode fire = mode("Flame", "Warm", "Warm", "Soul", "Ghost Green", "Violet");
        private final List<Vec3d> flames = new ArrayList<>();

        FloatingCandles() {
            super("Floating Candles", "Enchanted wax candles hover in a slow ring above you");
        }

        private int waxCol() {
            return switch (wax.get()) {
                case "Black" -> 0xFF2A2430;
                case "Purple" -> 0xFF6A3A90;
                case "Bone" -> 0xFFD8CCAE;
                default -> 0xFFF2EAD8;
            };
        }

        private int[] fireCols() {
            return switch (fire.get()) {
                case "Soul" -> new int[]{0xFF40C8FF, 0xFFD0F4FF};
                case "Ghost Green" -> new int[]{0xFF40FF80, 0xFFE0FFE0};
                case "Violet" -> new int[]{0xFFB050FF, 0xFFF0D8FF};
                default -> new int[]{0xFFFF8A20, 0xFFFFF0B0};
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            flames.clear();
            if (!thirdPerson() && height.f() < 1.8) return;
            Vec3d o = mc.player.getLerpedPos(td);
            int n = count.i();
            int wc = waxCol();
            for (int i = 0; i < n; i++) {
                double an = t * 0.18 + i * TAU / n;
                double R = radius.f() * (0.5 + 0.5 * outCubic(al));
                float h = hash(i * 7 + 2);
                Vec3d b = o.add(Math.cos(an) * R, height.f() + (h - 0.5) * 0.35 + 0.08 * Math.sin(t * 1.2 + i * 1.9), Math.sin(an) * R);
                double s = size.f() * outBack(al);
                if (s < 0.02) continue;
                double hh = (0.22 + 0.12 * h) * s, rr = 0.035 * s;
                Vec3d top = b.add(0, hh, 0);
                G.tube(new Vec3d[]{b, b.lerp(top, 0.33), b.lerp(top, 0.66), top}, new double[]{rr, rr, rr, rr * 0.97}, 14, new Vec3d(1, 0, 0),
                        (u, v) -> 1 + 0.035 * Math.sin(u * TAU * 3 + h * 9),
                        (u, v, p, nn) -> a(G.lit(G.scale(wc, 0.92 + 0.12 * v), p, nn, 0.5f, 0.35f), al), true);
                Vec3d[] ring = new Vec3d[14];
                for (int k = 0; k < 14; k++) ring[k] = top.add(Math.cos(k * TAU / 14) * rr, -0.004 * Math.sin(k * 3.0), Math.sin(k * TAU / 14) * rr);
                G.fan(top.add(0, -0.006, 0), ring, a(G.scale(wc, 1.08), al), a(wc, al), true);
                // drips
                for (int k = 0; k < 3; k++) {
                    double da = hash(i * 13 + k) * TAU;
                    double len = (0.03 + 0.06 * hash(i * 13 + k + 5)) * s;
                    Vec3d dc = top.add(Math.cos(da) * rr * 1.02, -len * 0.5, Math.sin(da) * rr * 1.02);
                    G.surface(G.ellip(G.F.world(Vec3d.ZERO, 1), dc, rr * 0.25, len * 0.55, rr * 0.25), 8, 5, (u, v, p, nn) -> a(G.lit(wc, p, nn, 0.5f, 0.5f), al), true);
                }
                Render3D.drawLineGradient(top, top.add(0, 0.03 * s, 0), a(0xFF201810, al), a(0xFF201810, al), 1.6f, true);
                flames.add(top.add(0, 0.03 * s, 0));
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            int[] fc = fireCols();
            int i = 0;
            for (Vec3d p : flames) {
                i++;
                if (!Projection.point(p, s)) continue;
                float k = ppb(p) * size.f();
                float fl = (float) (0.9 + 0.1 * Math.sin(t * 13 + i * 2) + 0.06 * G.noise(t * 6 + i));
                flame(s[0], s[1], k * 0.022f * fl, (float) (Math.sin(t * 3 + i) * 0.25 + G.noise(t * 4 + i * 3) * 0.2), al * G.fadeNear(p), fc[0], fc[1]);
            }
        }
    }

    /* ================================================================== 8. soul flames aura */

    static final class SoulFlames extends PV {
        private final Setting.Num count = num("Flames", 120, 20, 240, 5);
        private final Setting.Num height = num("Height", 1, 0.4, 2, 0.05);
        private final Setting.Num radius = num("Radius", 0.55, 0.3, 1.2, 0.05);
        private final Setting.Num speed = num("Speed", 1, 0.3, 3, 0.05);
        private final Setting.Mode color = mode("Color", "Soul", "Soul", "Ghostfire", "Hellfire", "Violet");
        private final Setting.Bool ring = bool("Ground Glow", true);
        private final Setting.Bool others = bool("Other Players", false);

        SoulFlames() {
            super("Soul Flames", "Blue soul fire licks up around your body");
        }

        private int[] cols() {
            return switch (color.get()) {
                case "Ghostfire" -> new int[]{0xFFE0FFE8, 0xFF40FF90, 0xFF0A6A40};
                case "Hellfire" -> new int[]{0xFFFFF0B0, 0xFFFF6A10, 0xFF8A1010};
                case "Violet" -> new int[]{0xFFF4E0FF, 0xFFB050FF, 0xFF3A1070};
                default -> new int[]{0xFFE0FCFF, 0xFF30D8F0, 0xFF1A40C0};
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            if (!ring.on()) return;
            int[] c = cols();
            for (PlayerEntity p : targets(others)) {
                Vec3d o = p.getLerpedPos(td).add(0, 0.03, 0);
                float pulse = (float) (0.8 + 0.2 * Math.sin(t * 3));
                disc(o, radius.f() * 1.7, 40, a(c[1], 0.22f * al * pulse), true);
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            int[] c = cols();
            Nvg.additive(true);
            for (PlayerEntity pl : targets(others)) {
                BodyAnchor ba = BodyAnchor.of(pl, td);
                int n = count.i();
                double H = 1.9 * height.f();
                for (int i = 0; i < n; i++) {
                    float h1 = hash(i * 4 + 1), h2 = hash(i * 4 + 2), h3 = hash(i * 4 + 3);
                    double rate = (0.7 + 0.6 * h2) * speed.f();
                    double ph = G.frac(t * rate + h1);
                    double an = h3 * TAU + Math.sin(t * 0.8 + i) * 0.2;
                    double r = radius.f() * (1 - 0.55 * ph) * (0.8 + 0.3 * h2);
                    double y = ph * H * (0.6 + 0.4 * h1);
                    double wob = Math.sin(t * 5 * speed.f() + i * 1.3) * 0.05 * ph;
                    Vec3d p = ba.at(Math.cos(an) * r + wob, y, Math.sin(an) * r);
                    if (!Projection.point(p, s)) continue;
                    float k = ppb(p);
                    float life = (float) Math.sin(Math.PI * Math.pow(ph, 0.6));
                    float rr = k * (0.15f * (1 - (float) ph) + 0.03f);
                    int col = ph < 0.35 ? Nvg.mix(c[0], c[1], (float) (ph / 0.35)) : Nvg.mix(c[1], c[2], (float) ((ph - 0.35) / 0.65));
                    float aa = al * life * G.fadeNear(p);
                    G.soft(s[0], s[1], rr * 2.2f, col, 0.07f * aa);
                    G.soft(s[0], s[1], rr, col, 0.26f * aa);
                    G.soft(s[0], s[1] - rr * 0.6f, rr * 0.7f, col, 0.18f * aa);
                    if (ph < 0.4) G.soft(s[0], s[1], rr * 0.35f, 0xFFFFFFFF, 0.35f * aa);
                }
                // embers
                for (int i = 0; i < 16; i++) {
                    float h1 = hash(i * 9 + 50), h2 = hash(i * 9 + 51);
                    double ph = G.frac(t * 0.35 * speed.f() + h1);
                    Vec3d p = ba.at(Math.cos(h2 * TAU + ph * 3) * radius.f() * 1.1, ph * H * 1.3, Math.sin(h2 * TAU + ph * 3) * radius.f() * 1.1);
                    if (Projection.point(p, s)) spark(s[0], s[1], ppb(p) * 0.01f, c[1], al * (float) Math.sin(Math.PI * ph));
                }
            }
            Nvg.additive(false);
        }
    }

    /* ================================================================== 9. skull kill effect */

    static final class SkullKill extends PV {
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num duration = num("Duration", 2.4, 1, 5, 0.1);
        private final Setting.Mode color = mode("Glow", "Ecto Green", "Ecto Green", "Soul Blue", "Pumpkin", "Blood");
        private final List<KillWatch.Kill> run = new ArrayList<>();
        private int seq;

        SkullKill() {
            super("Skull Kill Effect", "A grinning glowing skull rises from your kills in a burst of ghostly smoke");
        }

        @Override
        protected void onEnable() {
            seq = KillWatch.seq;
        }

        private int glow() {
            return switch (color.get()) {
                case "Soul Blue" -> 0xFF40D0FF;
                case "Pumpkin" -> 0xFFFF8A20;
                case "Blood" -> 0xFFFF2030;
                default -> 0xFF50FF80;
            };
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            KillWatch.poll();
            for (KillWatch.Kill k : KillWatch.events) if (k.seq() > seq) {
                seq = k.seq();
                if (enabled()) run.add(k);
            }
            double D = duration.f();
            run.removeIf(k -> t - k.t() > D);
            float[] s = new float[2];
            int g = glow();
            for (KillWatch.Kill k : run) {
                double age = t - k.t();
                float p = (float) (age / D);
                // smoke burst
                Nvg.additive(true);
                for (int i = 0; i < 26; i++) {
                    float h1 = hash(k.seq() * 77 + i), h2 = hash(k.seq() * 77 + i + 40);
                    double an = h1 * TAU, f = (1 - Math.exp(-2.5 * age)) / 2.5;
                    Vec3d q = k.pos().add(Math.cos(an) * f * (1.2 + h2) * size.f(), k.h() * 0.4 + f * 0.6 * h2 + age * 0.3, Math.sin(an) * f * (1.2 + h2) * size.f());
                    if (!Projection.point(q, s)) continue;
                    float kk = ppb(q) * size.f();
                    G.soft(s[0], s[1], kk * (0.2f + 0.35f * p), Nvg.mix(g, 0xFF6A3A90, h2), 0.14f * (1 - p) * al);
                }
                Nvg.additive(false);
                Vec3d c = k.pos().add(0, k.h() * 0.7 + 1.4 * outCubic(Math.min(1, p * 1.3f)), 0);
                if (!Projection.point(c, s)) continue;
                float kk = ppb(c) * size.f() * 0.32f * outBack(Math.min(1, p * 4));
                float fade = 1 - smooth((p - 0.7f) / 0.3f);
                skull(s[0], s[1], kk, (float) Math.sin(age * 3) * 0.1f, (float) Math.abs(Math.sin(age * 16)) * (p < 0.6f ? 1 : 0), g, al * fade);
            }
        }

        static void skull(float x, float y, float r, float tilt, float jaw, int glow, float al) {
            if (r < 1 || al < 0.01f) return;
            Nvg.additive(true);
            G.soft(x, y, r * 2.6f, glow, 0.18f * al);
            Nvg.additive(false);
            Nvg.save();
            Nvg.translate(x, y);
            Nvg.rotate(tilt);
            Nvg.scale(r, r);
            int bone = a(0xFFEDE6D6, 0.96f * al), shadow = a(0xFFA89E8A, 0.96f * al), dark = a(0xFF140C10, 0.95f * al);
            // jaw (moves)
            float jy = 0.12f * jaw;
            Nvg.beginPath();
            Nvg.rrectPath(-0.42f, 0.42f + jy, 0.84f, 0.42f, 0.18f);
            Nvg.fillPathGradient(0, 0.42f, 0, 0.86f, bone, shadow);
            // cranium
            Nvg.beginPath();
            Nvg.moveTo(-0.62f, 0.15f);
            Nvg.bezierTo(-0.8f, -1.05f, 0.8f, -1.05f, 0.62f, 0.15f);
            Nvg.bezierTo(0.6f, 0.38f, 0.48f, 0.55f, 0.36f, 0.56f);
            Nvg.lineTo(-0.36f, 0.56f);
            Nvg.bezierTo(-0.48f, 0.55f, -0.6f, 0.38f, -0.62f, 0.15f);
            Nvg.closePath();
            Nvg.fillPathGradient(-0.3f, -0.7f, 0.3f, 0.6f, bone, shadow);
            // sockets
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                Nvg.beginPath();
                Nvg.moveTo(sgn * 0.08f, 0.0f);
                Nvg.bezierTo(sgn * 0.08f, -0.3f, sgn * 0.5f, -0.3f, sgn * 0.46f, 0.02f);
                Nvg.bezierTo(sgn * 0.44f, 0.2f, sgn * 0.1f, 0.22f, sgn * 0.08f, 0.0f);
                Nvg.closePath();
                Nvg.fillPath(dark);
            }
            // nose
            Nvg.beginPath();
            Nvg.moveTo(0, 0.18f);
            Nvg.lineTo(-0.08f, 0.36f);
            Nvg.lineTo(0.08f, 0.36f);
            Nvg.closePath();
            Nvg.fillPath(dark);
            // teeth
            for (int i = -3; i <= 3; i++) {
                float tx = i * 0.1f;
                Nvg.line(tx, 0.46f, tx, 0.62f, 0.025f, a(0xFF5A5046, 0.9f * al));
                Nvg.line(tx, 0.62f + jy, tx, 0.74f + jy, 0.025f, a(0xFF5A5046, 0.9f * al));
            }
            Nvg.line(-0.36f, 0.56f, 0.36f, 0.56f, 0.03f, a(0xFF5A5046, 0.7f * al));
            Nvg.restore();
            Nvg.additive(true);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                float ex = x + (float) (Math.cos(tilt) * sgn * 0.27f * r - Math.sin(tilt) * 0.0f * r), ey = y + (float) (Math.sin(tilt) * sgn * 0.27f * r);
                G.soft(ex, ey, r * 0.3f, glow, 0.6f * al);
                G.soft(ex, ey, r * 0.08f, 0xFFFFFFFF, 0.9f * al);
            }
            Nvg.additive(false);
        }

        @Override
        void onFadedOut() {
            run.clear();
        }
    }

    /* ================================================================== 10. haunted trail */

    static final class HauntedTrail extends PV {
        private final Setting.Num life = num("Length", 1.8, 0.5, 4, 0.1);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Bool drips = bool("Drips", true);
        private final Setting.Mode color = mode("Color", "Purple Green", "Purple Green", "Ecto", "Phantom", "Blood");
        private final Setting.Bool others = bool("Other Players", false);
        private final Map<Integer, Deque<double[]>> trails = new HashMap<>();
        private final List<double[]> drops = new ArrayList<>(); // x y z born floor
        private double dripAcc;

        HauntedTrail() {
            super("Haunted Trail", "A wobbling trail of purple-green ectoplasm with dripping goo");
        }

        private int[] cols() {
            return switch (color.get()) {
                case "Ecto" -> new int[]{0xFF60FF70, 0xFFB0FF60};
                case "Phantom" -> new int[]{0xFF8AD0FF, 0xFFE8E8FF};
                case "Blood" -> new int[]{0xFFE0102A, 0xFF600814};
                default -> new int[]{0xFF56FF8A, 0xFFA040FF};
            };
        }

        @Override
        void world(float td, double t, float dt, float al) {
            List<PlayerEntity> ps = new ArrayList<>();
            ps.add(mc.player);
            if (others.on()) for (AbstractClientPlayerEntity p : mc.world.getPlayers()) if (p != mc.player && p.isAlive()) ps.add(p);
            for (PlayerEntity p : ps) {
                Deque<double[]> d = trails.computeIfAbsent(p.getId(), k -> new ArrayDeque<>());
                Vec3d now = p.getLerpedPos(td).add(0, 0.45, 0);
                double[] last = d.peekLast();
                if (enabled() && (last == null || now.squaredDistanceTo(last[0], last[1], last[2]) > 0.01)) {
                    d.addLast(new double[]{now.x, now.y, now.z, t, p.getY()});
                    if (drips.on()) {
                        dripAcc += 0.18;
                        if (dripAcc > 1 && drops.size() < 40) {
                            dripAcc = 0;
                            drops.add(new double[]{now.x, now.y - 0.1, now.z, t, p.getY()});
                        }
                    }
                }
                while (!d.isEmpty() && (t - d.peekFirst()[3] > life.f() || d.size() > 120)) d.pollFirst();
            }
            drops.removeIf(b -> t - b[3] > 1.6);
            int[] c = cols();
            for (double[] b : drops) {
                double age = t - b[3];
                double y = b[1] - 4.9 * age * age;
                if (y <= b[4] + 0.01) {
                    double ta = Math.sqrt((b[1] - b[4]) / 4.9);
                    float sp = (float) Math.max(0, age - ta);
                    float f = 1 - sp / 1.2f;
                    if (f > 0) disc(new Vec3d(b[0], b[4] + 0.015, b[2]), 0.05 + 0.18 * outCubic(sp * 3) * size.f(), 20, a(c[0], 0.45f * f * al), true);
                }
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            int[] c = cols();
            float L = life.f();
            Nvg.additive(true);
            for (Deque<double[]> d : trails.values()) {
                int i = 0;
                for (double[] q : d) {
                    i++;
                    float age = (float) ((t - q[3]) / L);
                    double wob = Math.sin(t * 3 + i * 0.5) * 0.06;
                    Vec3d p = new Vec3d(q[0] + wob, q[1] + Math.cos(t * 2.3 + i * 0.7) * 0.05 + age * 0.35, q[2] - wob);
                    if (!Projection.point(p, s)) continue;
                    float k = ppb(p) * size.f();
                    int col = Nvg.mix(c[0], c[1], age);
                    float aa = al * (1 - age) * G.fadeNear(p);
                    G.soft(s[0], s[1], k * (0.22f + 0.2f * age), col, 0.07f * aa);
                    G.soft(s[0], s[1], k * 0.08f * (1 - age * 0.5f), col, 0.22f * aa);
                    if (i % 7 == 0) spark(s[0], s[1], k * 0.012f, c[0], aa * 0.8f);
                }
            }
            Nvg.additive(false);
            for (double[] b : drops) {
                double age = t - b[3];
                double y = b[1] - 4.9 * age * age;
                if (y <= b[4] + 0.01) continue;
                Vec3d p = new Vec3d(b[0], y, b[2]);
                if (!Projection.point(p, s)) continue;
                float k = ppb(p) * size.f() * 0.03f;
                float st = (float) Math.min(2.2, 1 + age * 3);
                Nvg.save();
                Nvg.translate(s[0], s[1]);
                Nvg.scale(1, st);
                Nvg.beginPath();
                Nvg.circlePath(0, 0, k);
                Nvg.fillPath(a(c[0], 0.85f * al));
                Nvg.restore();
                Nvg.additive(true);
                G.soft(s[0], s[1], k * 3, c[0], 0.25f * al);
                Nvg.additive(false);
            }
        }

        @Override
        void onFadedOut() {
            trails.clear();
            drops.clear();
        }
    }
}

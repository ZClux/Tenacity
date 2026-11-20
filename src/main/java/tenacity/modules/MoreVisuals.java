package tenacity.modules;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.Nvg;
import tenacity.util.PacketHook;
import voided.util.render.Render3D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** Extra 3D / screen visuals: auras, halos, wings, hit effects, ambient particles and screen overlays. */
final class MoreVisuals {
    private MoreVisuals() {
    }

    static List<Module> create() {
        return List.of(new Aura(), new Halo(), new Wings(), new SlashTrails(), new HitRipple(), new SoulBurst(), new AmbientParticles(),
                new OrbitOrbs(), new TotemPop(), new Footprints(), new SpeedLines(), new HitVignette(), new LowHpFrost(), new CritSparks(),
                new GroundRings(), new SpiritAura(), new ShieldBubble(), new HoloRing(), new CapeGlow(), new LightPillar(), new LandImpact());
    }

    /* ================================================================== shared base */

    abstract static class Fx extends Module {
        private long lastNs = System.nanoTime();
        protected float dt = 0.016f;
        private long seenHit;

        Fx(String name, String desc) {
            super(name, desc, Category.RENDER);
        }

        protected float td() {
            return mc.getRenderTickCounter().getTickProgress(true);
        }

        /** Call once at the top of renderWorld / render2d: frame delta + camera basis. */
        protected void frame() {
            long n = System.nanoTime();
            dt = (float) MathHelper.clamp((n - lastNs) / 1e9, 0.0001, 0.1);
            lastNs = n;
            VFx.frame();
        }

        /** The entity you just hit, once per hit. */
        protected Entity newHit() {
            if (VisualModules.lastHit != seenHit) {
                seenHit = VisualModules.lastHit;
                return VisualModules.lastTarget;
            }
            return null;
        }

        @Override
        protected void onEnable() {
            seenHit = VisualModules.lastHit;
            lastNs = System.nanoTime();
        }

        /** Players to decorate; yourself only when the camera can see you (or when {@code self} says so). */
        protected List<PlayerEntity> players(boolean others, boolean selfInFirstPerson) {
            List<PlayerEntity> out = new ArrayList<>();
            boolean fp = mc.options.getPerspective().isFirstPerson();
            if (selfInFirstPerson || !fp) out.add(mc.player);
            if (others) for (AbstractClientPlayerEntity p : mc.world.getPlayers()) if (p != mc.player && p.isAlive() && !p.isInvisible()) out.add(p);
            return out;
        }

        static int pal(Setting.Mode m, float t) {
            return VisualModules.palette(m, t);
        }
    }

    /* ================================================================== drawing helpers */

    static final class VFx {
        static final Vec3d UP = new Vec3d(0, 1, 0), X = new Vec3d(1, 0, 0), Z = new Vec3d(0, 0, 1);
        static final int CAMERA = 0, FLAT = 1, AXIS = 2;
        static final int FROST = 0xFFF3F5F8;
        private static final long T0 = System.nanoTime();
        static Vec3d cam = Vec3d.ZERO, right = X, up = UP, fwd = Z;

        interface Col {
            int at(float t);
        }

        static double now() {
            return (System.nanoTime() - T0) / 1e9;
        }

        static void frame() {
            var c = mc().gameRenderer.getCamera();
            cam = c.getCameraPos();
            fwd = Vec3d.fromPolar(c.getPitch(), c.getYaw());
            Vec3d r = fwd.crossProduct(UP);
            right = r.lengthSquared() < 1e-6 ? X : r.normalize();
            up = right.crossProduct(fwd).normalize();
        }

        static net.minecraft.client.MinecraftClient mc() {
            return net.minecraft.client.MinecraftClient.getInstance();
        }

        /** Fades effects that pass right in front of the camera instead of filling the screen. */
        static float near(Vec3d p) {
            return smooth((float) ((p.distanceTo(cam) - 0.5) / 1.2));
        }

        static int a(int c, float alpha) {
            return Math.round(255 * Math.max(0, Math.min(1, alpha))) << 24 | (c & 0xFFFFFF);
        }

        /** Multiply the existing alpha. */
        static int am(int c, float m) {
            int al = Math.round((c >>> 24) * Math.max(0, Math.min(1, m)));
            return al << 24 | (c & 0xFFFFFF);
        }

        static int mix(int x, int y, float t) {
            t = Math.max(0, Math.min(1, t));
            int r = Math.round(((x >> 16) & 255) + (((y >> 16) & 255) - ((x >> 16) & 255)) * t);
            int g = Math.round(((x >> 8) & 255) + (((y >> 8) & 255) - ((x >> 8) & 255)) * t);
            int b = Math.round((x & 255) + ((y & 255) - (x & 255)) * t);
            return 0xFF000000 | r << 16 | g << 8 | b;
        }

        static int hue(float h, float s) {
            return 0xFF000000 | java.awt.Color.HSBtoRGB(((h % 1) + 1) % 1, s, 1f);
        }

        static float smooth(float t) {
            t = Math.max(0, Math.min(1, t));
            return t * t * (3 - 2 * t);
        }

        static float outCubic(float t) {
            t = Math.max(0, Math.min(1, t));
            float u = 1 - t;
            return 1 - u * u * u;
        }

        static float backOut(float t) {
            t = Math.max(0, Math.min(1, t));
            float c1 = 1.70158f, c3 = c1 + 1;
            return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
        }

        /** 0 -> 1 -> 0 envelope with eased in / out edges. */
        static float env(float age, float in, float life) {
            if (age < 0 || age > life) return 0;
            return smooth(age / in) * smooth((life - age) / (life * 0.55f));
        }

        static float hash(int i) {
            int x = i * 0x27d4eb2d;
            x ^= x >>> 15;
            x *= 0x85ebca6b;
            x ^= x >>> 13;
            return (x & 0xFFFFFF) / (float) 0xFFFFFF;
        }

        /** Uniform Catmull-Rom through {@code c}; {@code s} scalars are interpolated linearly along with it. */
        static List<Vec3d> catmull(List<Vec3d> c, int sub, float[][] s, List<float[]> sOut) {
            List<Vec3d> out = new ArrayList<>();
            int n = c.size();
            if (n < 2) {
                out.addAll(c);
                if (sOut != null) for (int i = 0; i < n; i++) sOut.add(col(s, i));
                return out;
            }
            for (int i = 0; i < n - 1; i++) {
                Vec3d p0 = c.get(Math.max(0, i - 1)), p1 = c.get(i), p2 = c.get(i + 1), p3 = c.get(Math.min(n - 1, i + 2));
                for (int k = 0; k < sub; k++) {
                    double t = k / (double) sub, t2 = t * t, t3 = t2 * t;
                    double x = 0.5 * (2 * p1.x + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3);
                    double y = 0.5 * (2 * p1.y + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3);
                    double z = 0.5 * (2 * p1.z + (-p0.z + p2.z) * t + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2 + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3);
                    out.add(new Vec3d(x, y, z));
                    if (sOut != null) {
                        float[] a = col(s, i), b = col(s, i + 1), r = new float[a.length];
                        for (int j = 0; j < a.length; j++) r[j] = (float) (a[j] + (b[j] - a[j]) * t);
                        sOut.add(r);
                    }
                }
            }
            out.add(c.get(n - 1));
            if (sOut != null) sOut.add(col(s, n - 1));
            return out;
        }

        private static float[] col(float[][] s, int i) {
            if (s == null) return new float[0];
            float[] r = new float[s.length];
            for (int j = 0; j < s.length; j++) r[j] = s[j][i];
            return r;
        }

        /**
         * A soft ribbon through {@code p}: full colour across the inner {@code core} fraction of the width, fading to
         * transparent at the edges. Widths are half widths in blocks; colours carry their own alpha.
         */
        static void ribbon(List<Vec3d> p, float[] w, int[] c, int mode, Vec3d axis, float core, boolean depth) {
            int n = p.size();
            if (n < 2) return;
            Vec3d[] s = new Vec3d[n];
            Vec3d prev = null;
            for (int i = 0; i < n; i++) {
                Vec3d t = p.get(Math.min(i + 1, n - 1)).subtract(p.get(Math.max(i - 1, 0)));
                Vec3d side = null;
                if (t.lengthSquared() > 1e-12) {
                    side = switch (mode) {
                        case FLAT -> t.crossProduct(UP);
                        case AXIS -> axis.subtract(t.multiply(axis.dotProduct(t) / t.lengthSquared()));
                        default -> t.crossProduct(p.get(i).subtract(cam));
                    };
                }
                if (side == null || side.lengthSquared() < 1e-14) side = prev != null ? prev : (mode == AXIS && axis != null ? axis : right);
                side = side.normalize();
                if (prev != null && side.dotProduct(prev) < 0) side = side.multiply(-1);
                s[i] = side;
                prev = side;
            }
            for (int i = 0; i < n - 1; i++) {
                if ((c[i] >>> 24) < 2 && (c[i + 1] >>> 24) < 2) continue;
                Vec3d a = p.get(i), b = p.get(i + 1);
                Vec3d sa = s[i].multiply(w[i]), sb = s[i + 1].multiply(w[i + 1]);
                int ca = am(c[i], near(a)), cb = am(c[i + 1], near(b)), ea = ca & 0xFFFFFF, eb = cb & 0xFFFFFF;
                if (core <= 0) {
                    Render3D.drawGradientQuad(a.subtract(sa), a, b, b.subtract(sb), ea, ca, cb, eb, depth);
                    Render3D.drawGradientQuad(a, a.add(sa), b.add(sb), b, ca, ea, eb, cb, depth);
                } else {
                    Vec3d ia = sa.multiply(core), ib = sb.multiply(core);
                    Render3D.drawGradientQuad(a.subtract(ia), a.add(ia), b.add(ib), b.subtract(ib), ca, ca, cb, cb, depth);
                    Render3D.drawGradientQuad(a.subtract(sa), a.subtract(ia), b.subtract(ib), b.subtract(sb), ea, ca, cb, eb, depth);
                    Render3D.drawGradientQuad(a.add(ia), a.add(sa), b.add(sb), b.add(ib), ca, ea, eb, cb, depth);
                }
            }
        }

        /** Stacked, widening, fading ribbons: a soft gaussian-ish glow under a ribbon. */
        static void glowRibbon(List<Vec3d> p, float[] w, int[] c, int mode, Vec3d axis, int layers, float spread, float strength, boolean depth) {
            int n = p.size();
            float[] lw = new float[n];
            int[] lc = new int[n];
            for (int l = layers; l >= 1; l--) {
                float f = l / (float) (layers + 1);
                float al = (1 - f) * (1 - f) * 0.45f * strength;
                for (int i = 0; i < n; i++) {
                    lw[i] = w[i] * (1 + l * spread);
                    lc[i] = am(c[i], al);
                }
                ribbon(p, lw, lc, mode, axis, 0, depth);
            }
        }

        /** Camera facing soft disc (fully transparent rim). {@code c} carries alpha. */
        static void sprite(Vec3d p, double r, int c, boolean depth) {
            c = am(c, near(p));
            if ((c >>> 24) < 2) return;
            int e = c & 0xFFFFFF, seg = 10;
            Vec3d prev = p.add(right.multiply(r));
            for (int i = 1; i <= seg; i++) {
                double an = i * Math.PI * 2 / seg;
                Vec3d q = p.add(right.multiply(Math.cos(an) * r)).add(up.multiply(Math.sin(an) * r));
                Render3D.drawGradientQuad(p, p, q, prev, c, c, e, e, depth);
                prev = q;
            }
        }

        /** A glowing point light: wide faint halo, medium glow and a whitened hot core. */
        static void spark(Vec3d p, double r, int col, float alpha, boolean depth) {
            if (alpha <= 0.01f) return;
            sprite(p, r * 4, a(col, alpha * 0.12f), depth);
            sprite(p, r * 1.8, a(col, alpha * 0.4f), depth);
            sprite(p, r * 0.7, a(mix(col, 0xFFFFFFFF, 0.7f), alpha), depth);
        }

        /** Soft band around a circle in the plane (u, v): transparent -> colour at r -> transparent. */
        static void ring(Vec3d c, Vec3d u, Vec3d v, double r, double w, int seg, Col col, float alpha, float rot, boolean depth) {
            if (alpha <= 0.004f) return;
            for (int i = 0; i < seg; i++) {
                double t0 = i * Math.PI * 2 / seg + rot, t1 = (i + 1) * Math.PI * 2 / seg + rot;
                Vec3d d0 = u.multiply(Math.cos(t0)).add(v.multiply(Math.sin(t0))), d1 = u.multiply(Math.cos(t1)).add(v.multiply(Math.sin(t1)));
                int c0 = a(col.at(i / (float) seg), alpha), c1 = a(col.at((i + 1) / (float) seg), alpha);
                int e0 = c0 & 0xFFFFFF, e1 = c1 & 0xFFFFFF;
                double ri = Math.max(0, r - w), ro = r + w;
                Vec3d m0 = c.add(d0.multiply(r)), m1 = c.add(d1.multiply(r));
                Render3D.drawGradientQuad(c.add(d0.multiply(ri)), m0, m1, c.add(d1.multiply(ri)), e0, c0, c1, e1, depth);
                Render3D.drawGradientQuad(m0, c.add(d0.multiply(ro)), c.add(d1.multiply(ro)), m1, c0, e0, e1, c1, depth);
            }
        }

        /** Partial arc of {@link #ring} from angle a0 to a1 with tapered ends. */
        static void arc(Vec3d c, Vec3d u, Vec3d v, double r, double w, double a0, double a1, int seg, Col col, float alpha, boolean depth) {
            List<Vec3d> pts = new ArrayList<>();
            float[] ws = new float[seg + 1];
            int[] cs = new int[seg + 1];
            for (int i = 0; i <= seg; i++) {
                float t = i / (float) seg;
                double an = a0 + (a1 - a0) * t;
                pts.add(c.add(u.multiply(Math.cos(an) * r)).add(v.multiply(Math.sin(an) * r)));
                float taper = (float) Math.pow(Math.sin(Math.PI * t), 0.35);
                ws[i] = (float) w * taper;
                cs[i] = a(col.at(t), alpha * taper);
            }
            Vec3d n = u.crossProduct(v);
            ribbonRadial(pts, c, ws, cs, depth);
        }

        /** Ribbon whose width points away from {@code centre} (for flat arcs). */
        static void ribbonRadial(List<Vec3d> pts, Vec3d centre, float[] w, int[] c, boolean depth) {
            for (int i = 0; i < pts.size() - 1; i++) {
                Vec3d a = pts.get(i), b = pts.get(i + 1);
                Vec3d sa = a.subtract(centre).normalize().multiply(w[i]), sb = b.subtract(centre).normalize().multiply(w[i + 1]);
                int ca = c[i], cb = c[i + 1], ea = ca & 0xFFFFFF, eb = cb & 0xFFFFFF;
                Render3D.drawGradientQuad(a.subtract(sa), a, b, b.subtract(sb), ea, ca, cb, eb, depth);
                Render3D.drawGradientQuad(a, a.add(sa), b.add(sb), b, ca, ea, eb, cb, depth);
            }
        }

        /** Filled soft disc in the plane (u, v) (u / v may be scaled for ellipses). */
        static void disc(Vec3d c, Vec3d u, Vec3d v, double r, int seg, int cin, int cout, boolean depth) {
            Vec3d prev = c.add(u.multiply(r));
            for (int i = 1; i <= seg; i++) {
                double an = i * Math.PI * 2 / seg;
                Vec3d q = c.add(u.multiply(Math.cos(an) * r)).add(v.multiply(Math.sin(an) * r));
                Render3D.drawGradientQuad(c, c, q, prev, cin, cin, cout, cout, depth);
                prev = q;
            }
        }

        static Vec3d bodyForward(LivingEntity e, float td) {
            double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(td, e.lastBodyYaw, e.bodyYaw));
            return new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        }
    }

    /* ================================================================== trail renderer (shared by Trails / Breadcrumbs) */

    /**
     * Draws a smoothed, tapering trail. {@code pts} oldest -> newest; {@code fades} 0..1 per point (age fade).
     * Every style has its own geometry and animation (not just a colour):
     * Ribbon (twisting lit band), Glow Line (thin core + bloom + travelling pulses), Frost (ice shards + glints),
     * Rainbow (scrolling hue chevrons), Comet (hot head, fading tail, shed sparkles), Helix (double spiral strands
     * with beads), Dots (evenly spaced glowing beads), Lightning (flickering jagged bolt with forks).
     */
    static void trail(List<Vec3d> pts, float[] fades, String style, Setting.Mode color, float width, float glow, int mode, boolean depth, int seed) {
        if (pts.size() < 2) return;
        List<float[]> sc = new ArrayList<>();
        List<Vec3d> p = VFx.catmull(pts, 4, new float[][]{fades}, sc);
        int n = p.size();
        double time = VFx.now();
        float[] len = new float[n];
        for (int i = 1; i < n; i++) len[i] = len[i - 1] + (float) p.get(i).distanceTo(p.get(i - 1));
        float total = Math.max(1e-4f, len[n - 1]);
        // per point: tangent, side (the ribbon's width direction for this mode) and normal
        Vec3d[] tan = new Vec3d[n], side = new Vec3d[n], nrm = new Vec3d[n];
        float[] al = new float[n], tp = new float[n], u = new float[n];
        Vec3d prevSide = null;
        for (int i = 0; i < n; i++) {
            Vec3d t = p.get(Math.min(i + 1, n - 1)).subtract(p.get(Math.max(i - 1, 0)));
            t = t.lengthSquared() < 1e-12 ? (i > 0 ? tan[i - 1] : VFx.X) : t.normalize();
            tan[i] = t;
            Vec3d s = mode == VFx.FLAT ? t.crossProduct(VFx.UP) : t.crossProduct(p.get(i).subtract(VFx.cam));
            if (s.lengthSquared() < 1e-10) s = prevSide != null ? prevSide : VFx.right;
            s = s.normalize();
            if (prevSide != null && s.dotProduct(prevSide) < 0) s = s.multiply(-1);
            side[i] = s;
            prevSide = s;
            nrm[i] = s.crossProduct(t).normalize();
            float k = (float) i / (n - 1), f = sc.get(i)[0];
            tp[i] = (float) Math.pow(k, 0.65) * (0.25f + 0.75f * f);
            al[i] = (float) Math.pow(k, 1.1) * f;
            u[i] = len[i] / total;
        }
        switch (style) {
            case "Glow Line" -> {
                float[] w = new float[n], wc = new float[n];
                int[] c = new int[n], core = new int[n];
                for (int i = 0; i < n; i++) {
                    int col = Fx.pal(color, 1 - u[i]);
                    // bright pulses travelling from the tail to the head
                    float pulse = (float) Math.pow(0.5 + 0.5 * Math.sin(len[i] * 3.2 - time * 9), 8);
                    w[i] = width * tp[i] * 0.55f;
                    c[i] = VFx.a(col, al[i] * (0.4f + 0.5f * pulse));
                    wc[i] = width * tp[i] * (0.09f + 0.06f * pulse);
                    core[i] = VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.75f), al[i]);
                }
                VFx.glowRibbon(p, w, c, mode, null, 6, 1.1f * Math.max(0.3f, glow), 1.6f, depth);
                VFx.ribbon(p, wc, core, mode, null, 0.5f, depth);
            }
            case "Frost" -> {
                float[] w = new float[n];
                int[] c = new int[n];
                for (int i = 0; i < n; i++) {
                    w[i] = width * tp[i] * 0.22f;
                    c[i] = VFx.a(VFx.mix(VFx.FROST, Fx.pal(color, 1 - u[i]), 0.25f), al[i] * 0.7f);
                }
                if (glow > 0) VFx.glowRibbon(p, w, c, mode, null, 3, 1.4f * glow, 0.6f, depth);
                VFx.ribbon(p, w, c, mode, null, 0.4f, depth);
                // ice shards: thin crystalline blades jutting out of the path at alternating angles
                float step = Math.max(0.12f, width * 1.3f);
                int idx = 0;
                for (float d = total % step; d < total; d += step, idx++) {
                    int i = at(len, d);
                    if (al[i] < 0.02f) continue;
                    int h = seed * 131 + Math.round(d / step);
                    float grow = VFx.backOut(Math.min(1, al[i] * 2.2f));
                    double ang = (VFx.hash(h) - 0.5) * 1.6 + (idx % 2 == 0 ? 0.5 : -0.5);
                    Vec3d dir = side[i].multiply(Math.sin(ang)).add(nrm[i].multiply(Math.cos(ang))).add(tan[i].multiply(-0.35)).normalize();
                    if (mode == VFx.FLAT) dir = new Vec3d(dir.x, Math.abs(dir.y) + 0.6, dir.z).normalize();
                    double L = width * (1.4 + VFx.hash(h + 1) * 1.6) * grow * tp[i];
                    Vec3d base = p.get(i), tip = base.add(dir.multiply(L));
                    Vec3d bw = tan[i].multiply(width * 0.16 * grow);
                    int cb = VFx.a(VFx.mix(VFx.FROST, 0xFFB8DCFF, 0.35f), al[i] * 0.55f), ct = VFx.a(0xFFFFFFFF, al[i] * 0.95f);
                    Render3D.drawGradientQuad(base.subtract(bw), base.add(bw), tip, tip, cb, cb, ct, ct, depth);
                    Render3D.drawGradientQuad(base.add(bw), base.subtract(bw), tip, tip, cb, cb, ct, ct, depth);
                    float tw = (float) Math.pow(0.5 + 0.5 * Math.sin(time * 4 + VFx.hash(h + 2) * 30), 10);
                    if (tw > 0.05f) VFx.spark(tip, width * 0.14, 0xFFFFFFFF, tw * al[i], depth);
                }
            }
            case "Rainbow" -> {
                // scrolling chevrons, each a V pointing the way of travel, cycling through the hues
                float step = Math.max(0.1f, width * 1.6f);
                float off = (float) ((time * 1.6) % step);
                for (float d = off; d < total; d += step) {
                    int i = at(len, d);
                    if (al[i] < 0.02f) continue;
                    float k = d / total;
                    int col = VFx.hue((float) (k * 1.2 - time * 0.35), 0.6f);
                    double W = width * tp[i] * 1.15, back = width * 0.8 * tp[i], th = width * 0.32 * tp[i];
                    Vec3d c0 = p.get(i), s = side[i], t = tan[i];
                    Vec3d lA = c0.add(s.multiply(W)).subtract(t.multiply(back)), rA = c0.subtract(s.multiply(W)).subtract(t.multiply(back));
                    Vec3d tip0 = c0, tip1 = c0.subtract(t.multiply(th * 1.6));
                    Vec3d lB = lA.subtract(t.multiply(th)), rB = rA.subtract(t.multiply(th));
                    int ci = VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.25f), al[i] * 0.95f), co = VFx.a(col, al[i] * 0.45f);
                    Render3D.drawGradientQuad(tip0, lA, lB, tip1, ci, co, co, ci, depth);
                    Render3D.drawGradientQuad(tip1, rB, rA, tip0, ci, co, co, ci, depth);
                    if (glow > 0) VFx.sprite(c0.subtract(t.multiply(back * 0.5)), W * 1.2, VFx.a(col, al[i] * 0.12f * glow), depth);
                }
            }
            case "Comet" -> {
                float[] w = new float[n];
                int[] c = new int[n];
                for (int i = 0; i < n; i++) {
                    float k = (float) i / (n - 1);
                    w[i] = width * (float) Math.pow(k, 2.2) * 1.1f * (0.3f + 0.7f * sc.get(i)[0]);
                    c[i] = VFx.a(VFx.mix(Fx.pal(color, 1 - k), 0xFFFFFFFF, k * k * 0.6f), (float) Math.pow(k, 2) * sc.get(i)[0] * 0.75f);
                }
                if (glow > 0) VFx.glowRibbon(p, w, c, mode, null, 5, 0.9f * glow, 1.3f, depth);
                VFx.ribbon(p, w, c, mode, null, 0.2f, depth);
                Vec3d head = p.get(n - 1);
                float flick = 0.85f + 0.15f * (float) Math.sin(time * 23);
                VFx.spark(head, width * 1.1 * flick, Fx.pal(color, 0), 1, depth);
                VFx.sprite(head, width * 6, VFx.a(Fx.pal(color, 0), 0.08f * Math.max(0.3f, glow)), depth);
                for (int i = 0; i < pts.size() - 1; i++) {
                    float f = fades[i], age = 1 - f;
                    for (int j = 0; j < 3; j++) {
                        int h = seed * 31 + i * 7 + j;
                        double ax = (VFx.hash(h) - 0.5) * 2, ay = VFx.hash(h + 1) - 0.25, az = (VFx.hash(h + 2) - 0.5) * 2;
                        Vec3d q = pts.get(i).add(ax * age * 0.7, ay * age * 0.6 - age * age * 0.3, az * age * 0.7);
                        float tw = 0.5f + 0.5f * (float) Math.sin(time * 14 + h);
                        VFx.spark(q, width * 0.13 * (0.6 + tw * 0.6), Fx.pal(color, VFx.hash(h + 3)), f * f * 0.9f, depth);
                    }
                }
            }
            case "Helix" -> {
                // two thin strands winding around the path, with beads where they cross
                double R = width * 1.3;
                for (int strand = 0; strand < 2; strand++) {
                    List<Vec3d> sp = new ArrayList<>();
                    float[] w = new float[n];
                    int[] c = new int[n];
                    for (int i = 0; i < n; i++) {
                        double th = len[i] * 4.5 - time * 4 + strand * Math.PI;
                        double r = R * tp[i];
                        Vec3d off = side[i].multiply(Math.cos(th) * r).add(nrm[i].multiply(Math.sin(th) * r));
                        sp.add(p.get(i).add(off));
                        float front = 0.55f + 0.45f * (float) Math.sin(th); // depth shading: the near half is brighter
                        w[i] = width * 0.16f * tp[i];
                        c[i] = VFx.a(VFx.mix(Fx.pal(color, (1 - u[i] + strand * 0.5f) % 1f), 0xFFFFFFFF, 0.3f * front), al[i] * (0.45f + 0.5f * front));
                    }
                    if (glow > 0) VFx.glowRibbon(sp, w, c, VFx.CAMERA, null, 3, 1.8f * glow, 0.9f, depth);
                    VFx.ribbon(sp, w, c, VFx.CAMERA, null, 0.45f, depth);
                }
                float step = (float) (Math.PI / 4.5);
                for (float d = (float) ((time * 4 / 4.5) % step); d < total; d += step) {
                    int i = at(len, d);
                    if (al[i] > 0.03f) VFx.spark(p.get(i), width * 0.22 * tp[i], Fx.pal(color, 1 - u[i]), al[i] * 0.8f, depth);
                }
            }
            case "Dots" -> {
                float step = Math.max(0.08f, width * 2.2f);
                float wave = (float) (time * 2.5);
                for (float d = total % step; d < total; d += step) {
                    int i = at(len, d);
                    if (al[i] < 0.02f) continue;
                    float pulse = 0.75f + 0.35f * (float) Math.sin(d * 4 - wave * 3);
                    float pop = VFx.backOut(Math.min(1, al[i] * 3));
                    Vec3d q = p.get(i);
                    int col = Fx.pal(color, 1 - u[i]);
                    if (glow > 0) VFx.sprite(q, width * 1.6 * pop * glow, VFx.a(col, al[i] * 0.12f), depth);
                    VFx.sprite(q, width * 0.75 * pop * pulse, VFx.a(col, al[i] * 0.55f), depth);
                    VFx.sprite(q, width * 0.32 * pop * pulse, VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.8f), al[i]), depth);
                }
            }
            case "Lightning" -> {
                // a jagged bolt re-rolled ~18 times a second, plus short forks
                int frameSeed = (int) (time * 18);
                List<Vec3d> bolt = new ArrayList<>();
                float[] w = new float[n];
                int[] c = new int[n], core = new int[n];
                for (int i = 0; i < n; i++) {
                    int h = seed * 977 + i / 3 * 13 + frameSeed * 7919;
                    float j = i % 3 == 0 && i > 0 && i < n - 1 ? 1 : 0.35f;
                    double amp = width * 1.2 * tp[i] * j;
                    Vec3d off = side[i].multiply((VFx.hash(h) - 0.5) * 2 * amp).add(nrm[i].multiply((VFx.hash(h + 1) - 0.5) * 2 * amp));
                    bolt.add(p.get(i).add(off));
                    int col = Fx.pal(color, 1 - u[i]);
                    w[i] = width * 0.45f * tp[i];
                    c[i] = VFx.a(col, al[i] * 0.45f);
                    core[i] = VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.85f), al[i]);
                }
                float flash = 0.75f + 0.25f * VFx.hash(frameSeed);
                if (glow > 0) VFx.glowRibbon(bolt, w, c, mode, null, 4, 1.2f * glow, 1.3f * flash, depth);
                float[] wc = new float[n];
                for (int i = 0; i < n; i++) wc[i] = width * 0.08f * tp[i] + 0.004f;
                VFx.ribbon(bolt, wc, core, mode, null, 0.6f, depth);
                for (int i = 6; i < n - 3; i += 9) {
                    int h = seed * 53 + i * 11 + frameSeed * 31;
                    if (VFx.hash(h) < 0.45f || al[i] < 0.05f) continue;
                    Vec3d a0 = bolt.get(i);
                    Vec3d dir = side[i].multiply(VFx.hash(h + 1) - 0.5).add(nrm[i].multiply(VFx.hash(h + 2) - 0.5)).normalize().add(tan[i].multiply(-0.4));
                    Vec3d mid = a0.add(dir.multiply(width * 1.5)).add(side[i].multiply((VFx.hash(h + 3) - 0.5) * width));
                    Vec3d end = mid.add(dir.multiply(width * 1.4));
                    int cc = VFx.a(0xFFFFFFFF, al[i] * 0.8f), ce = cc & 0xFFFFFF;
                    Render3D.drawLineGradient(a0, mid, cc, VFx.a(0xFFFFFFFF, al[i] * 0.45f), 1.5f, depth);
                    Render3D.drawLineGradient(mid, end, VFx.a(0xFFFFFFFF, al[i] * 0.45f), ce, 1.2f, depth);
                }
            }
            default -> { // Ribbon: a flat band that slowly twists around its path, lit by how it faces the camera
                for (int i = 0; i < n - 1; i++) {
                    if (al[i] < 0.01f && al[i + 1] < 0.01f) continue;
                    Vec3d a = p.get(i), b = p.get(i + 1);
                    Vec3d sa = twist(side[i], nrm[i], len[i], time, mode).multiply(width * tp[i]);
                    Vec3d sb = twist(side[i + 1], nrm[i + 1], len[i + 1], time, mode).multiply(width * tp[i + 1]);
                    float la = lit(sa, a), lb = lit(sb, b);
                    int col0 = Fx.pal(color, 1 - u[i]), col1 = Fx.pal(color, 1 - u[i + 1]);
                    int ca = VFx.am(VFx.a(VFx.mix(col0, 0xFFFFFFFF, la * 0.5f), al[i] * (0.35f + 0.5f * la)), VFx.near(a));
                    int cb = VFx.am(VFx.a(VFx.mix(col1, 0xFFFFFFFF, lb * 0.5f), al[i + 1] * (0.35f + 0.5f * lb)), VFx.near(b));
                    int ea = VFx.am(VFx.a(0xFFFFFFFF, al[i] * 0.9f), VFx.near(a)), eb = VFx.am(VFx.a(0xFFFFFFFF, al[i + 1] * 0.9f), VFx.near(b));
                    Render3D.drawGradientQuad(a.subtract(sa), a, b, b.subtract(sb), ea, ca, cb, eb, depth);
                    Render3D.drawGradientQuad(a, a.add(sa), b.add(sb), b, ca, ea, eb, cb, depth);
                }
                if (glow > 0) {
                    float[] w = new float[n];
                    int[] c = new int[n];
                    for (int i = 0; i < n; i++) {
                        w[i] = width * tp[i] * 0.6f;
                        c[i] = VFx.a(Fx.pal(color, 1 - u[i]), al[i] * 0.35f);
                    }
                    VFx.glowRibbon(p, w, c, mode, null, 3, 0.9f * glow, 0.8f, depth);
                }
            }
        }
    }

    /** Index of the first point at arc length >= d. */
    private static int at(float[] len, float d) {
        int lo = 0, hi = len.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (len[mid] < d) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static Vec3d twist(Vec3d s, Vec3d nrm, float len, double time, int mode) {
        // ground trails only rock a little so the band never dives into the floor
        double th = mode == VFx.FLAT ? 0.55 * Math.sin(len * 1.4 - time * 1.2) : len * 1.4 - time * 1.2;
        return s.multiply(Math.cos(th)).add(nrm.multiply(Math.sin(th)));
    }

    /** 0..1: how face-on a band with width direction {@code s} at {@code p} is to the camera. */
    private static float lit(Vec3d s, Vec3d p) {
        Vec3d v = VFx.cam.subtract(p);
        if (v.lengthSquared() < 1e-8 || s.lengthSquared() < 1e-12) return 0.5f;
        float d = (float) Math.abs(s.normalize().dotProduct(v.normalize()));
        return 1 - d;
    }

    /* ================================================================== 1. aura */

    static final class Aura extends Fx {
        private final Setting.Num count = num("Particles", 14, 4, 40, 1);
        private final Setting.Num radius = num("Radius", 0.8, 0.4, 2, 0.05);
        private final Setting.Num speed = num("Speed", 1, 0.2, 4, 0.1);
        private final Setting.Bool groundRing = bool("Ground Ring", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = VisualModules.colors(this);

        Aura() {
            super("Aura", "Glowing motes spiralling around players with comet tails");
        }

        private Vec3d at(BodyAnchor b, float h, double r, double t, double ph) {
            double ang = t * 1.6 + ph * Math.PI * 2;
            double y = h * (0.5 + 0.42 * Math.sin(t * 1.1 + ph * Math.PI * 4));
            return b.at(Math.cos(ang) * r, y, Math.sin(ang) * r);
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now() * speed.f();
            for (PlayerEntity p : players(others.on(), true)) {
                BodyAnchor b = BodyAnchor.of(p, td);
                float h = (float) (b.length() / b.scale);
                double r = radius.f();
                int n = count.i();
                boolean self = p == mc.player && mc.options.getPerspective().isFirstPerson();
                if (!self) for (int i = 0; i < n; i++) {
                    double ph = i / (double) n;
                    int c = pal(color, (float) ph);
                    List<Vec3d> tail = new ArrayList<>();
                    int L = 12;
                    float[] w = new float[L];
                    int[] cs = new int[L];
                    for (int j = L - 1; j >= 0; j--) {
                        float k = 1 - j / (float) (L - 1);
                        tail.add(at(b, h, r, T - j * 0.03, ph));
                        w[L - 1 - j] = 0.035f * k;
                        cs[L - 1 - j] = VFx.a(c, 0.75f * k * k);
                    }
                    VFx.glowRibbon(tail, w, cs, VFx.CAMERA, null, 2, 1.5f, 0.8f, true);
                    VFx.ribbon(tail, w, cs, VFx.CAMERA, null, 0.3f, true);
                    VFx.spark(at(b, h, r, T, ph), 0.028, c, 1, true);
                }
                float gv = 1 - b.lying;
                if (groundRing.on() && gv > 0.02f) {
                    Vec3d g = p.getLerpedPos(td).add(0, 0.02, 0);
                    float rot = (float) (T * 0.5);
                    VFx.ring(g, VFx.X, VFx.Z, r, 0.16, 72, t -> pal(color, (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2))), 0.25f * gv, rot, true);
                    VFx.ring(g, VFx.X, VFx.Z, r, 0.018, 72, t -> pal(color, (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2))), 0.9f * gv, rot, true);
                }
            }
        }
    }

    /* ================================================================== 2. halo */

    static final class Halo extends Fx {
        private final Setting.Num size = num("Size", 0.3, 0.15, 0.6, 0.01);
        private final Setting.Num height = num("Height", 0.28, 0.05, 0.6, 0.01);
        private final Setting.Num glow = num("Glow", 1, 0, 2.5, 0.1);
        private final Setting.Num spin = num("Spin", 1, 0, 4, 0.1);
        private final Setting.Bool sparkles = bool("Sparkles", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = VisualModules.colors(this);

        Halo() {
            super("Halo", "An angelic glowing ring floating above heads");
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            for (PlayerEntity p : players(others.on(), false)) {
                double bob = 0.035 * Math.sin(T * 2 + p.getId());
                BodyAnchor b = BodyAnchor.of(p, td);
                Vec3d c = b.headTop(height.f() - 0.06 + bob);
                // ring plane follows the head
                Vec3d rt = b.hRight, v = b.hFwd;
                double r = size.f();
                float rot = (float) (T * spin.f());
                VFx.Col col = t -> pal(color, (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 2)));
                for (int l = 3; l >= 1; l--) VFx.ring(c, rt, v, r, 0.02 + 0.045 * l * glow.f(), 72, col, 0.16f / l * Math.min(1, glow.f() + 0.2f), rot, true);
                VFx.ring(c, rt, v, r, 0.03, 72, col, 0.85f, rot, true);
                VFx.ring(c, rt, v, r, 0.012, 72, t -> VFx.mix(col.at(t), 0xFFFFFFFF, 0.65f), 0.95f, rot, true);
                if (sparkles.on()) for (int i = 0; i < 3; i++) {
                    double an = rot * 1.7 + i * Math.PI * 2 / 3;
                    Vec3d q = c.add(rt.multiply(Math.cos(an) * r)).add(v.multiply(Math.sin(an) * r));
                    float tw = (float) (0.6 + 0.4 * Math.sin(T * 6 + i * 2));
                    VFx.spark(q, 0.025, 0xFFFFFFFF, tw, true);
                }
            }
        }
    }

    /* ================================================================== 3. wings */

    static final class Wings extends Fx {
        private final Setting.Num size = num("Size", 0.95, 0.4, 1.6, 0.05);
        private final Setting.Num flap = num("Flap Speed", 1, 0, 3, 0.1);
        private final Setting.Num feathers = num("Feathers", 12, 6, 20, 1);
        private final Setting.Num opacity = num("Opacity", 0.8, 0.2, 1, 0.05);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Bool fold = bool("Fold When Idle", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = VisualModules.colors(this);
        /** id -> {spread 0..1, flap phase, flap amplitude}. */
        private final Map<Integer, float[]> st = new HashMap<>();

        Wings() {
            super("Wings", "Smooth feathered light wings that fold at rest and beat when you move or fly");
        }

        @Override
        protected void onDisable() {
            st.clear();
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            Set<Integer> seen = new HashSet<>();
            for (PlayerEntity p : players(others.on(), false)) {
                seen.add(p.getId());
                BodyAnchor b = BodyAnchor.of(p, td);
                float[] s = st.computeIfAbsent(p.getId(), k -> new float[]{0.2f, VFx.hash(k) * 6.28f, 0.2f, 0.45f});
                boolean fly = b.flying(), air = !p.isOnGround() && !p.isTouchingWater();
                boolean moving = b.speed > 0.04;
                float wantSpread = fly || air ? 1 : moving ? 0.8f : fold.on() ? 0.08f : 0.55f;
                float freq = fly ? 1.5f : air ? 2.6f : moving ? (float) (1.0 + b.speed * 3) : 0.45f;
                float wantAmp = b.gliding ? 0.35f : air ? 1 : moving ? 0.55f : 0.18f;
                // everything eases (spread, amplitude AND flap rate), so jumping / landing never jolts the beat
                float k = 1 - (float) Math.exp(-3.5f * dt);
                s[0] += (wantSpread - s[0]) * k;
                s[2] += (wantAmp - s[2]) * k;
                s[3] += (freq - s[3]) * (1 - (float) Math.exp(-2.5f * dt));
                s[1] += dt * s[3] * flap.f() * (float) Math.PI * 2;
                if (s[1] > 1000) s[1] -= (float) (Math.PI * 2 * 100);
                // eased beat: phase-warped sine -> quick downstroke, slower recovery, smooth turnarounds
                double ph = s[1] + 0.35 * Math.sin(s[1]);
                float beat = (float) Math.sin(ph) * s[2];
                drawWing(b, -1, VFx.smooth(s[0]), beat);
                drawWing(b, 1, VFx.smooth(s[0]), beat);
            }
            st.keySet().retainAll(seen);
        }

        private static Vec3d lerpN(Vec3d a, Vec3d b, double t) {
            Vec3d v = a.multiply(1 - t).add(b.multiply(t));
            return v.lengthSquared() < 1e-9 ? b : v.normalize();
        }

        /**
         * A bird-like wing: an arched arm bone reaching out from the shoulder blades with feathers hanging off it -
         * short coverts near the body, long primaries at the tip. Folded, the arm tucks behind the shoulder and all
         * feathers lie down along the back.
         */
        private void drawWing(BodyAnchor b, int sgn, float spread, float beat) {
            Vec3d side = b.right.multiply(sgn), back = b.backDir(), up = b.up;
            double sc = b.scale;
            Vec3d root = b.at(0.07 * sgn, BodyAnchor.NECK - 0.2 - (b.sneaking ? 0.25 : 0), -0.2);
            double L = size.f() * sc;
            int F = feathers.i();
            float op = opacity.f();
            double t0 = VFx.now();
            double e = Math.toRadians(18 + 38 * beat);
            Vec3d armSpread = side.multiply(Math.cos(e)).add(up.multiply(Math.sin(e))).add(back.multiply(0.35)).normalize();
            Vec3d armFold = side.multiply(0.3).add(up.multiply(0.3)).add(back.multiply(0.6)).normalize();
            Vec3d arm = lerpN(armFold, armSpread, spread);
            double armLen = L * (0.35 + 0.6 * spread);
            Vec3d foldF = up.multiply(-0.92).add(back.multiply(0.3)).add(side.multiply(0.1)).normalize();
            Vec3d innerF = up.multiply(-0.85).add(back.multiply(0.3)).add(side.multiply(0.2)).normalize();
            Vec3d outerF = arm.multiply(0.92).add(up.multiply(-0.3 - 0.25 * beat)).normalize();
            // feather planes: their width runs along the arm
            for (int j = 0; j < F; j++) {
                double u = (j + 0.5) / F;
                Vec3d base = root.add(arm.multiply(armLen * u)).add(up.multiply(0.1 * L * Math.sin(Math.PI * u) * spread));
                Vec3d d = lerpN(foldF, lerpN(innerF, outerF, Math.pow(u, 1.4)), spread);
                Vec3d wAxis = arm.subtract(d.multiply(arm.dotProduct(d)));
                wAxis = wAxis.lengthSquared() < 1e-6 ? side : wAxis.normalize();
                Vec3d nrm = d.crossProduct(wAxis).normalize();
                double len = L * (0.42 + 0.55 * Math.pow(u, 1.15)) * (u > 0.85 ? 1 - (u - 0.85) * 1.2 : 1) * (0.85 + 0.15 * spread);
                int N = 18;
                List<Vec3d> pts = new ArrayList<>(N);
                float[] w = new float[N], wc = new float[N];
                int[] c = new int[N], core = new int[N];
                // air drag: tips trail against the beat, and curl softly backwards
                double lag = -0.18 * beat * spread;
                for (int i = 0; i < N; i++) {
                    double t = i / (double) (N - 1);
                    double curl = t * t;
                    pts.add(base.add(d.multiply(len * t)).add(nrm.multiply(len * curl * lag)).add(back.multiply(len * curl * 0.1)));
                    float leaf = (float) Math.pow(Math.sin(Math.PI * Math.min(1, Math.pow(t, 0.75) * 1.02 + 0.04)), 0.55);
                    w[i] = (float) (armLen / F * 1.9 * leaf + 0.016 * L * leaf);
                    wc[i] = w[i] * 0.22f;
                    int col = pal(color, (float) ((u * 0.55 + t * 0.45 + t0 * 0.05) % 1));
                    float al = (0.3f + 0.7f * VFx.smooth((float) t * 1.8f)) * (float) (1 - 0.65 * Math.pow(t, 5));
                    c[i] = VFx.a(col, 0.5f * op * al);
                    core[i] = VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.65f), 0.85f * op * al);
                }
                if (glow.on()) VFx.glowRibbon(pts, w, c, VFx.AXIS, wAxis, 2, 0.8f, 0.55f, true);
                VFx.ribbon(pts, w, c, VFx.AXIS, wAxis, 0.55f, true);
                VFx.ribbon(pts, wc, core, VFx.AXIS, wAxis, 0.3f, true);
            }
            // leading edge: the arm bone with a bright core
            int N = 16;
            List<Vec3d> armPts = new ArrayList<>();
            float[] aw = new float[N];
            int[] ac = new int[N];
            for (int i = 0; i < N; i++) {
                double t = i / (double) (N - 1);
                armPts.add(root.add(arm.multiply(armLen * t)).add(up.multiply(0.1 * L * Math.sin(Math.PI * t) * spread)));
                aw[i] = (float) (0.026 * L * (1 - 0.55 * t));
                ac[i] = VFx.a(VFx.mix(pal(color, (float) t * 0.3f), 0xFFFFFFFF, 0.55f), 0.85f * op * (float) Math.sin(Math.PI * Math.min(1, t * 0.9 + 0.1)));
            }
            if (glow.on()) VFx.glowRibbon(armPts, aw, ac, VFx.CAMERA, null, 2, 1.6f, 0.8f, true);
            VFx.ribbon(armPts, aw, ac, VFx.CAMERA, null, 0.45f, true);
        }
    }

    /* ================================================================== 4. slash trails */

    static final class SlashTrails extends Fx {
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num thickness = num("Thickness", 1, 0.4, 2.5, 0.1);
        private final Setting.Num duration = num("Duration", 0.55, 0.25, 1.5, 0.05);
        private final Setting.Mode color = VisualModules.colors(this);
        private final List<Object[]> slashes = new ArrayList<>(); // centre, U, V, f, born, flip
        private final Random rnd = new Random();
        private int n;

        SlashTrails() {
            super("Slash Trails", "A glowing crescent sword slash across whatever you hit");
        }

        @Override
        protected void onDisable() {
            slashes.clear();
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            Entity e = newHit();
            double now = VFx.now();
            if (e != null) {
                Vec3d c = e.getBoundingBox().getCenter();
                Vec3d f = c.subtract(mc.player.getEyePos()).normalize();
                Vec3d u = f.crossProduct(VFx.UP);
                u = u.lengthSquared() < 1e-6 ? VFx.X : u.normalize();
                Vec3d v = u.crossProduct(f).normalize();
                double roll = (rnd.nextDouble() - 0.5) * 1.6;
                Vec3d U = u.multiply(Math.cos(roll)).add(v.multiply(Math.sin(roll))), V = u.multiply(-Math.sin(roll)).add(v.multiply(Math.cos(roll)));
                double R = Math.max(0.6, e.getWidth()) * 1.05 * size.f();
                slashes.add(new Object[]{c.subtract(f.multiply(0.3)), U, V, R, now, (n++ & 1) == 0});
            }
            float life = duration.f();
            slashes.removeIf(s -> now - (double) s[4] > life);
            for (Object[] s : slashes) {
                float age = (float) (now - (double) s[4]);
                float sweep = VFx.outCubic(age / (life * 0.3f));
                float fade = 1 - VFx.smooth((age - life * 0.25f) / (life * 0.75f));
                Vec3d c = (Vec3d) s[0], U = (Vec3d) s[1], V = (Vec3d) s[2];
                double R = (double) s[3] * (1 + 0.12 * age / life);
                double a0 = (boolean) s[5] ? -1.25 : Math.PI + 1.25, a1 = (boolean) s[5] ? 1.25 : Math.PI - 1.25;
                double head = a0 + (a1 - a0) * sweep;
                int N = 28;
                List<Vec3d> pts = new ArrayList<>();
                float[] w = new float[N + 1], wc = new float[N + 1];
                int[] c1 = new int[N + 1], c2 = new int[N + 1];
                for (int i = 0; i <= N; i++) {
                    float t = i / (float) N;
                    double an = a0 + (head - a0) * t;
                    double rr = R * (0.92 + 0.08 * t);
                    pts.add(c.add(U.multiply(Math.cos(an) * rr)).add(V.multiply(Math.sin(an) * rr)));
                    float cres = (float) Math.pow(Math.sin(Math.PI * Math.min(1, t * 0.92 + 0.04)), 0.8) * (0.3f + 0.7f * t);
                    w[i] = 0.11f * thickness.f() * cres;
                    wc[i] = w[i] * 0.35f;
                    int col = pal(color, 1 - t);
                    c1[i] = VFx.a(col, 0.7f * fade * (0.3f + 0.7f * t));
                    c2[i] = VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.75f), fade * t);
                }
                VFx.glowRibbon(pts, w, c1, VFx.AXIS, U.multiply(Math.cos(head)).add(V.multiply(Math.sin(head))), 3, 1.1f, 1f, false);
                ribbonRadial(pts, c, w, c1);
                ribbonRadial(pts, c, wc, c2);
                VFx.spark(pts.get(N), 0.05 * thickness.f(), pal(color, 0), fade * (1 - sweep * 0.6f), false);
            }
        }

        private static void ribbonRadial(List<Vec3d> pts, Vec3d c, float[] w, int[] col) {
            VFx.ribbonRadial(pts, c, w, col, false);
        }
    }

    /* ================================================================== 5. hit ripple */

    static final class HitRipple extends Fx {
        private final Setting.Num size = num("Size", 1.6, 0.6, 4, 0.1);
        private final Setting.Num duration = num("Duration", 0.9, 0.3, 2, 0.05);
        private final Setting.Bool shock = bool("Shockwave Ring", true);
        private final Setting.Mode color = VisualModules.colors(this);
        private final List<Object[]> ripples = new ArrayList<>(); // entity, feet, born

        HitRipple() {
            super("Hit Ripple", "Shockwave rings ripple out from the target you hit");
        }

        @Override
        protected void onDisable() {
            ripples.clear();
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            Entity e = newHit();
            double now = VFx.now();
            float td = td();
            if (e != null) ripples.add(new Object[]{e, e.getLerpedPos(td), now});
            float life = duration.f();
            ripples.removeIf(r -> now - (double) r[2] > life);
            for (Object[] r : ripples) {
                Entity en = (Entity) r[0];
                Vec3d feet = en.isAlive() ? en.getLerpedPos(td) : (Vec3d) r[1];
                float age = (float) (now - (double) r[2]);
                for (int j = 0; j < 3; j++) {
                    float k = (age - j * 0.08f) / life;
                    if (k <= 0 || k >= 1) continue;
                    float e2 = VFx.outCubic(k);
                    float al = (1 - k) * (1 - k);
                    double rr = 0.2 + size.f() * e2 * (1 - j * 0.15);
                    VFx.Col col = t -> pal(color, (float) (0.5 + 0.5 * Math.sin(t * Math.PI * 4)));
                    VFx.ring(feet.add(0, 0.03 + j * 0.002, 0), VFx.X, VFx.Z, rr, 0.18 * (1 - k) + 0.02, 64, col, 0.35f * al, 0, true);
                    VFx.ring(feet.add(0, 0.031 + j * 0.002, 0), VFx.X, VFx.Z, rr, 0.02, 64, col, 0.9f * al, 0, true);
                }
                if (shock.on()) {
                    float k = age / (life * 0.7f);
                    if (k < 1) {
                        Vec3d mid = feet.add(0, en.getHeight() * 0.55, 0);
                        double rr = 0.3 + size.f() * 0.6 * VFx.outCubic(k);
                        float al = (1 - k) * (1 - k);
                        VFx.ring(mid, VFx.right, VFx.up, rr, 0.12 * (1 - k) + 0.015, 64, t -> pal(color, t), 0.4f * al, 0, false);
                        VFx.ring(mid, VFx.right, VFx.up, rr, 0.015, 64, t -> VFx.mix(pal(color, t), 0xFFFFFFFF, 0.6f), 0.85f * al, 0, false);
                    }
                }
            }
        }
    }

    /* ================================================================== 6. soul burst (kill effect) */

    static final class SoulBurst extends Fx {
        private final Setting.Num duration = num("Duration", 2.2, 1, 5, 0.1);
        private final Setting.Num souls = num("Souls", 22, 6, 50, 1);
        private final Setting.Num pillar = num("Pillar Height", 7, 0, 20, 0.5);
        private final Setting.Bool playersOnly = bool("Players Only", false);
        private final Setting.Mode color = VisualModules.colors(this);
        private final List<Object[]> bursts = new ArrayList<>(); // pos, born, seed
        private final Set<Integer> dead = new HashSet<>();

        SoulBurst() {
            super("Soul Burst", "Kills release a light pillar and spiralling souls");
        }

        @Override
        protected void onDisable() {
            bursts.clear();
            dead.clear();
        }

        @Override
        public void tick() {
            Set<Integer> still = new HashSet<>();
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof LivingEntity le) || e == mc.player) continue;
                if (playersOnly.on() && !(e instanceof PlayerEntity)) continue;
                if (le.isDead() || le.getHealth() <= 0) {
                    still.add(e.getId());
                    if (dead.add(e.getId()) && e.squaredDistanceTo(mc.player) < 64 * 64)
                        bursts.add(new Object[]{e.getEntityPos(), VFx.now(), e.getId()});
                }
            }
            dead.retainAll(still);
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            double now = VFx.now();
            float life = duration.f();
            bursts.removeIf(b -> now - (double) b[1] > life);
            for (Object[] b : bursts) {
                Vec3d c = (Vec3d) b[0];
                int seed = (int) b[2];
                float age = (float) (now - (double) b[1]), k = age / life;
                float env = VFx.env(age, 0.12f, life);
                if (pillar.f() > 0) {
                    List<Vec3d> pts = new ArrayList<>();
                    int N = 12;
                    float[] w = new float[N];
                    int[] cs = new int[N], cc = new int[N];
                    float[] wc = new float[N];
                    double H = pillar.f() * VFx.outCubic(age / 0.35f);
                    for (int i = 0; i < N; i++) {
                        float t = i / (float) (N - 1);
                        pts.add(c.add(0, H * t, 0));
                        w[i] = 0.32f * (1 - t * 0.5f) * (0.6f + 0.4f * (1 - k));
                        wc[i] = w[i] * 0.25f;
                        cs[i] = VFx.a(pal(color, t), 0.45f * env * (1 - t));
                        cc[i] = VFx.a(0xFFFFFFFF, 0.8f * env * (1 - t));
                    }
                    VFx.glowRibbon(pts, w, cs, VFx.CAMERA, null, 3, 1.2f, 1, true);
                    VFx.ribbon(pts, wc, cc, VFx.CAMERA, null, 0.3f, true);
                }
                double rr = 0.3 + 2.2 * VFx.outCubic(age / (life * 0.5f));
                VFx.ring(c.add(0, 0.03, 0), VFx.X, VFx.Z, rr, 0.12, 64, t -> pal(color, t), 0.4f * (1 - VFx.smooth(age / (life * 0.5f))), 0, true);
                int n = souls.i();
                for (int i = 0; i < n; i++) {
                    float delay = VFx.hash(seed + i * 3) * 0.35f;
                    float a = age - delay;
                    if (a <= 0) continue;
                    double ang0 = i * 2.39996, rise = 0.9 + VFx.hash(seed + i * 3 + 1) * 1.4;
                    List<Vec3d> tail = new ArrayList<>();
                    int L = 8;
                    float[] w = new float[L];
                    int[] cs = new int[L];
                    float al = VFx.env(a, 0.15f, life - delay);
                    int col = pal(color, VFx.hash(seed + i));
                    for (int j = L - 1; j >= 0; j--) {
                        double aa = Math.max(0, a - j * 0.04);
                        double r = 0.25 + aa * 0.45;
                        double an = ang0 + aa * 3.2;
                        tail.add(c.add(Math.cos(an) * r, 0.3 + aa * rise, Math.sin(an) * r));
                        float q = 1 - j / (float) (L - 1);
                        w[L - 1 - j] = 0.04f * q;
                        cs[L - 1 - j] = VFx.a(col, 0.7f * q * al);
                    }
                    VFx.ribbon(tail, w, cs, VFx.CAMERA, null, 0.3f, true);
                    VFx.spark(tail.get(L - 1), 0.03, col, al, true);
                }
            }
        }
    }

    /* ================================================================== 7. ambient particles */

    static final class AmbientParticles extends Fx {
        private final Setting.Mode type = mode("Type", "Snow", "Snow", "Ash", "Embers", "Stars");
        private final Setting.Num count = num("Count", 160, 20, 400, 10);
        private final Setting.Num radius = num("Radius", 12, 4, 24, 1);
        private final Setting.Num size = num("Size", 1, 0.4, 2.5, 0.1);
        private final Setting.Num speed = num("Speed", 1, 0.2, 3, 0.1);
        private final Setting.Mode color = VisualModules.colors(this);
        private final List<double[]> ps = new ArrayList<>(); // x y z vx vy vz seed
        private final Random r = new Random();
        private String lastType = "";

        AmbientParticles() {
            super("Ambient Particles", "Snow, ash, embers or twinkling stars drifting around you");
        }

        @Override
        protected void onDisable() {
            ps.clear();
        }

        private double[] spawn(Vec3d me, boolean anywhere) {
            double R = radius.f();
            double y = anywhere ? me.y - 3 + r.nextDouble() * 11 : (type.is("Embers") ? me.y - 3 : me.y + 8);
            return new double[]{me.x + (r.nextDouble() * 2 - 1) * R, y, me.z + (r.nextDouble() * 2 - 1) * R, 0, 0, 0, r.nextDouble() * 1000};
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            Vec3d me = mc.player.getLerpedPos(td());
            if (!type.get().equals(lastType)) {
                ps.clear();
                lastType = type.get();
            }
            while (ps.size() < count.i()) ps.add(spawn(me, true));
            while (ps.size() > count.i()) ps.remove(ps.size() - 1);
            double T = VFx.now(), R = radius.f();
            float sp = speed.f();
            for (int i = 0; i < ps.size(); i++) {
                double[] p = ps.get(i);
                double s = p[6];
                switch (type.get()) {
                    case "Snow" -> {
                        p[0] += Math.sin(T * 0.7 + s) * 0.35 * dt * sp;
                        p[2] += Math.cos(T * 0.5 + s * 1.3) * 0.35 * dt * sp;
                        p[1] -= (0.6 + (s % 1) * 0.5) * dt * sp;
                    }
                    case "Ash" -> {
                        p[0] += (Math.sin(T * 0.4 + s) * 0.5 + 0.2) * dt * sp;
                        p[2] += Math.cos(T * 0.3 + s) * 0.4 * dt * sp;
                        p[1] -= (0.25 + (s % 1) * 0.2) * dt * sp;
                    }
                    case "Embers" -> {
                        p[0] += Math.sin(T * 1.3 + s) * 0.4 * dt * sp;
                        p[2] += Math.cos(T * 1.1 + s) * 0.4 * dt * sp;
                        p[1] += (0.5 + (s % 1) * 0.6) * dt * sp;
                    }
                    default -> {
                        p[1] += Math.sin(T * 0.3 + s) * 0.05 * dt;
                    }
                }
                double dx = p[0] - me.x, dz = p[2] - me.z;
                if (Math.abs(dx) > R) p[0] -= Math.signum(dx) * R * 2;
                if (Math.abs(dz) > R) p[2] -= Math.signum(dz) * R * 2;
                if (p[1] < me.y - 4) p[1] += 12;
                if (p[1] > me.y + 8) p[1] -= 12;
                Vec3d at = new Vec3d(p[0], p[1], p[2]);
                double hd = Math.sqrt(dx * dx + dz * dz);
                float edge = VFx.smooth((float) ((R - hd) / (R * 0.25)));
                float near = VFx.smooth((float) ((at.distanceTo(VFx.cam) - 0.6) / 1.2));
                float al = edge * near;
                if (al < 0.02f) continue;
                switch (type.get()) {
                    case "Snow" -> VFx.spark(at, 0.022 * size.f(), VFx.FROST, 0.85f * al, true);
                    case "Ash" -> VFx.sprite(at, 0.035 * size.f(), VFx.a(0xFF5A5C62, 0.8f * al), true);
                    case "Embers" -> {
                        float fl = (float) (0.55 + 0.45 * Math.sin(T * 9 + s * 3));
                        VFx.spark(at, 0.018 * size.f(), VFx.mix(0xFFFF7A2E, 0xFFFFD27A, (float) (s % 1)), al * fl, true);
                    }
                    default -> {
                        float tw = (float) Math.pow(0.5 + 0.5 * Math.sin(T * 1.6 * sp + s * 5), 3);
                        VFx.spark(at, 0.02 * size.f(), pal(color, (float) (s % 1)), al * (0.2f + 0.8f * tw), true);
                    }
                }
            }
        }
    }

    /* ================================================================== 8. orbit orbs */

    static final class OrbitOrbs extends Fx {
        private final Setting.Num count = num("Orbs", 3, 1, 8, 1);
        private final Setting.Num radius = num("Radius", 1.1, 0.5, 2.5, 0.05);
        private final Setting.Num speed = num("Speed", 1, 0.2, 4, 0.1);
        private final Setting.Num trailLen = num("Trail", 0.9, 0.1, 2.5, 0.05);
        private final Setting.Num size = num("Size", 1, 0.4, 2.5, 0.1);
        private final Setting.Bool others = bool("Other Players", false);
        private final Setting.Mode color = VisualModules.colors(this);

        OrbitOrbs() {
            super("Orbit Orbs", "Glowing orbs on tilted orbits around you, leaving comet trails");
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now() * speed.f();
            for (PlayerEntity p : players(others.on(), true)) {
                BodyAnchor b = BodyAnchor.of(p, td);
                Vec3d c = b.center();
                int n = count.i();
                for (int i = 0; i < n; i++) {
                    double phi = i * 2.39996 + 0.4, inc = Math.toRadians(20 + 25 * VFx.hash(i * 13 + 5));
                    Vec3d U = b.local(Math.cos(phi), 0, Math.sin(phi));
                    Vec3d V = b.local(-Math.sin(phi) * Math.cos(inc), Math.sin(inc), Math.cos(phi) * Math.cos(inc));
                    double w = 1.8 * (1 + 0.12 * i);
                    double a = T * w + i * Math.PI * 2 / n;
                    double R = radius.f();
                    int col = pal(color, i / (float) Math.max(1, n - 1));
                    int L = 22;
                    List<Vec3d> pts = new ArrayList<>();
                    float[] ws = new float[L];
                    int[] cs = new int[L], cc = new int[L];
                    float[] wc = new float[L];
                    for (int j = L - 1; j >= 0; j--) {
                        double aa = a - j / (double) (L - 1) * trailLen.f() * Math.PI;
                        pts.add(c.add(U.multiply(Math.cos(aa) * R)).add(V.multiply(Math.sin(aa) * R)));
                        float k = 1 - j / (float) (L - 1);
                        ws[L - 1 - j] = 0.05f * size.f() * (float) Math.pow(k, 0.8);
                        wc[L - 1 - j] = ws[L - 1 - j] * 0.35f;
                        cs[L - 1 - j] = VFx.a(col, 0.6f * k * k);
                        cc[L - 1 - j] = VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.6f), 0.9f * k * k);
                    }
                    VFx.glowRibbon(pts, ws, cs, VFx.CAMERA, null, 3, 1.2f, 1, true);
                    VFx.ribbon(pts, wc, cc, VFx.CAMERA, null, 0.3f, true);
                    VFx.spark(pts.get(L - 1), 0.05 * size.f(), col, 1, true);
                }
            }
        }
    }

    /* ================================================================== 9. totem pop */

    static final class TotemPop extends Fx implements PacketHook.Receiver {
        private final Setting.Num duration = num("Duration", 1.6, 0.6, 4, 0.1);
        private final Setting.Num size = num("Size", 1.4, 0.6, 3, 0.1);
        private final Setting.Num sparks = num("Sparks", 40, 10, 100, 2);
        private final Setting.Bool totemColors = bool("Totem Colors", true);
        private final Setting.Mode color = VisualModules.colors(this);
        private final List<Object[]> pops = new ArrayList<>(); // entity, pos, born, seed

        TotemPop() {
            super("Totem Pop", "A golden shell and spark burst when someone pops a totem");
        }

        @Override
        protected void onDisable() {
            pops.clear();
        }

        @Override
        public void onPacket(Packet<?> packet) {
            if (packet instanceof EntityStatusS2CPacket s && s.getStatus() == 35) {
                mc.execute(() -> {
                    if (mc.world == null) return;
                    Entity e = s.getEntity(mc.world);
                    if (e != null) pops.add(new Object[]{e, e.getEntityPos(), VFx.now(), e.getId() * 97 + pops.size()});
                });
            }
        }

        private int col(float t) {
            return totemColors.on() ? VFx.mix(0xFFFFD45A, 0xFF8CF2A8, t) : pal(color, t);
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double now = VFx.now();
            float life = duration.f();
            pops.removeIf(p -> now - (double) p[2] > life);
            for (Object[] p : pops) {
                Entity e = (Entity) p[0];
                Vec3d base = e.isAlive() && !e.isRemoved() ? e.getLerpedPos(td) : (Vec3d) p[1];
                Vec3d c = base.add(0, e.getHeight() * 0.55, 0);
                float age = (float) (now - (double) p[2]), k = age / life;
                int seed = (int) p[3];
                float shell = VFx.outCubic(age / (life * 0.45f));
                float al = (float) Math.pow(1 - k, 1.5);
                double R = size.f() * (0.4 + 0.6 * shell);
                for (int j = 1; j < 6; j++) {
                    double lat = -Math.PI / 2 + j * Math.PI / 6;
                    Vec3d cc = c.add(0, Math.sin(lat) * R, 0);
                    double rr = Math.cos(lat) * R;
                    VFx.ring(cc, VFx.X, VFx.Z, rr, 0.05, 48, this::col, 0.35f * al, (float) (age * 2), true);
                }
                VFx.ring(c, VFx.right, VFx.up, R * 1.05, 0.14, 64, this::col, 0.3f * al, 0, true);
                VFx.ring(c, VFx.right, VFx.up, R * 1.05, 0.02, 64, t -> VFx.mix(col(t), 0xFFFFFFFF, 0.5f), 0.9f * al, 0, true);
                int n = sparks.i();
                for (int i = 0; i < n; i++) {
                    double y = 1 - 2 * (i + 0.5) / n, rr = Math.sqrt(1 - y * y), th = i * 2.39996 + seed;
                    Vec3d d = new Vec3d(Math.cos(th) * rr, y * 0.8 + 0.35, Math.sin(th) * rr);
                    double spd = (2.2 + VFx.hash(seed + i) * 2.5) * size.f();
                    double tt = age * (1 - 0.35 * k);
                    Vec3d at = c.add(d.multiply(spd * tt)).add(0, -2.5 * tt * tt, 0);
                    Vec3d vel = d.multiply(spd).add(0, -5 * tt, 0);
                    int cl = col(VFx.hash(seed + i * 5));
                    Render3D.drawLineGradient(at.subtract(vel.multiply(0.05)), at, VFx.a(cl, 0), VFx.a(cl, al), 2f, true);
                    VFx.spark(at, 0.025, cl, al, true);
                }
                if (age < 0.25f) VFx.spark(c, 0.35 * size.f(), col(0), 1 - age / 0.25f, false);
            }
        }
    }

    /* ================================================================== 10. footprints */

    static final class Footprints extends Fx {
        private final Setting.Mode style = mode("Style", "Shoe", "Shoe", "Paw");
        private final Setting.Num life = num("Fade Time", 4, 1, 15, 0.5);
        private final Setting.Num stride = num("Stride", 0.8, 0.4, 1.6, 0.05);
        private final Setting.Bool others = bool("Other Players", false);
        private final Setting.Mode color = VisualModules.colors(this);
        private final List<double[]> prints = new ArrayList<>(); // x y z yaw side born
        private final Map<Integer, double[]> walk = new HashMap<>(); // lastX lastZ dist side

        Footprints() {
            super("Footprints", "Glowing footprints left on the ground where players walk");
        }

        @Override
        protected void onDisable() {
            prints.clear();
            walk.clear();
        }

        @Override
        public void tick() {
            for (PlayerEntity p : players(others.on(), true)) {
                double[] s = walk.computeIfAbsent(p.getId(), k -> new double[]{p.getX(), p.getZ(), 0, 1});
                double dx = p.getX() - s[0], dz = p.getZ() - s[1];
                double d = Math.sqrt(dx * dx + dz * dz);
                s[0] = p.getX();
                s[1] = p.getZ();
                if (d > 3) continue;
                s[2] += d;
                if (s[2] >= stride.f() && p.isOnGround() && d > 0.01) {
                    s[2] = 0;
                    s[3] = -s[3];
                    double yaw = Math.atan2(dz, dx);
                    double ox = -Math.sin(yaw) * 0.14 * s[3], oz = Math.cos(yaw) * 0.14 * s[3];
                    prints.add(new double[]{p.getX() + ox, p.getY() + 0.015, p.getZ() + oz, yaw, s[3], VFx.now()});
                }
            }
            while (prints.size() > 160) prints.remove(0);
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            double now = VFx.now();
            float L = life.f();
            prints.removeIf(p -> now - p[5] > L);
            int idx = 0;
            for (double[] p : prints) {
                float age = (float) (now - p[5]);
                float pop = VFx.backOut(age / 0.25f);
                float fade = 1 - VFx.smooth((age - L * 0.5f) / (L * 0.5f));
                Vec3d c = new Vec3d(p[0], p[1], p[2]);
                Vec3d f = new Vec3d(Math.cos(p[3]), 0, Math.sin(p[3])), s = new Vec3d(-f.z, 0, f.x);
                int col = pal(color, (idx++ * 0.09f) % 1f);
                double sc = pop;
                if (style.is("Paw")) {
                    paw(c, f, s, sc, col, fade, (float) p[4]);
                    continue;
                }
                // halo
                VFx.disc(c.add(f.multiply(0.02)), s.multiply(0.22 * sc), f.multiply(0.32 * sc), 1, 20, VFx.a(col, 0.18f * fade), VFx.a(col, 0), true);
                // sole + heel + toes
                VFx.disc(c.add(f.multiply(0.07 * sc)), s.multiply(0.075 * sc), f.multiply(0.13 * sc), 1, 18, VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.4f), 0.6f * fade), VFx.a(col, 0.25f * fade), true);
                VFx.disc(c.add(f.multiply(-0.12 * sc)), s.multiply(0.062 * sc), f.multiply(0.07 * sc), 1, 16, VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.4f), 0.55f * fade), VFx.a(col, 0.2f * fade), true);
                for (int t = 0; t < 4; t++) {
                    double lat = (t - 1.5) * 0.04 + p[4] * 0.01;
                    VFx.disc(c.add(f.multiply((0.23 - Math.abs(t - 1.5) * 0.015) * sc)).add(s.multiply(lat * sc)), s.multiply(0.018 * sc), f.multiply(0.022 * sc), 1, 10,
                            VFx.a(0xFFFFFFFF, 0.6f * fade), VFx.a(col, 0.1f * fade), true);
                }
            }
        }

        /** A cute paw: soft halo, a three-lobed main pad and four toe beans fanned out in front, splayed a little outward per side. */
        private static void paw(Vec3d c, Vec3d f, Vec3d s, double sc, int col, float fade, float sideSign) {
            // turn the whole print a touch outward, like a real gait
            double tilt = 0.18 * sideSign;
            Vec3d F = f.multiply(Math.cos(tilt)).add(s.multiply(Math.sin(tilt))), S = new Vec3d(-F.z, 0, F.x);
            int hot = VFx.mix(col, 0xFFFFFFFF, 0.55f);
            VFx.disc(c.add(F.multiply(0.03 * sc)), S.multiply(0.26 * sc), F.multiply(0.28 * sc), 1, 24, VFx.a(col, 0.16f * fade), VFx.a(col, 0), true);
            // main pad: a wide bean made of three overlapping lobes (two at the back, one in front)
            Vec3d pad = c.add(F.multiply(-0.04 * sc));
            for (int k = -1; k <= 1; k += 2)
                VFx.disc(pad.add(S.multiply(0.045 * k * sc)).add(F.multiply(-0.015 * sc)), S.multiply(0.06 * sc), F.multiply(0.055 * sc), 1, 18,
                        VFx.a(hot, 0.62f * fade), VFx.a(col, 0.22f * fade), true);
            VFx.disc(pad.add(F.multiply(0.03 * sc)), S.multiply(0.085 * sc), F.multiply(0.055 * sc), 1, 20, VFx.a(hot, 0.66f * fade), VFx.a(col, 0.2f * fade), true);
            // four toe beans on an arc
            for (int t = 0; t < 4; t++) {
                double an = Math.toRadians(-54 + t * 36);
                double r = (t == 0 || t == 3) ? 0.135 : 0.155;
                Vec3d bean = pad.add(F.multiply(Math.cos(an) * r * sc + 0.02 * sc)).add(S.multiply(Math.sin(an) * r * sc * 1.05));
                Vec3d bf = F.multiply(Math.cos(an)).add(S.multiply(Math.sin(an))), bs = new Vec3d(-bf.z, 0, bf.x);
                VFx.disc(bean, bs.multiply(0.03 * sc), bf.multiply(0.038 * sc), 1, 14, VFx.a(0xFFFFFFFF, 0.7f * fade), VFx.a(col, 0.18f * fade), true);
            }
        }
    }

    /* ================================================================== 11. speed lines (screen) */

    static final class SpeedLines extends Fx {
        private final Setting.Num amount = num("Lines", 70, 20, 160, 5);
        private final Setting.Num strength = num("Strength", 1, 0.2, 2, 0.1);
        private final Setting.Num threshold = num("Min Speed", 5.2, 2, 12, 0.2);
        private final Setting.Bool frost = bool("Frost White", true);
        private final Setting.Mode color = VisualModules.colors(this);
        private float inten;
        private Vec3d lastPos;

        SpeedLines() {
            super("Speed Lines", "Anime style speed streaks at the screen edges when moving fast");
        }

        @Override
        public void render2d(float td) {
            frame();
            Vec3d pos = mc.player.getLerpedPos(td);
            double spd = 0;
            if (lastPos != null && dt > 0) spd = Math.hypot(pos.x - lastPos.x, pos.z - lastPos.z) / dt;
            lastPos = pos;
            float target = (float) MathHelper.clamp((spd - threshold.f()) / 5, 0, 1);
            if (mc.player.isGliding()) target = Math.max(target, (float) MathHelper.clamp(spd / 30, 0, 1));
            inten += (target - inten) * (1 - (float) Math.exp(-(target > inten ? 6 : 3) * dt));
            if (inten < 0.01f) return;
            float W = Nvg.w, H = Nvg.h, cx = W / 2, cy = H / 2, diag = (float) Math.hypot(W, H) / 2;
            double T = VFx.now();
            Nvg.additive(true);
            for (int i = 0; i < amount.i(); i++) {
                float ang = VFx.hash(i * 7 + 1) * (float) Math.PI * 2;
                float sp = 0.7f + VFx.hash(i * 7 + 2) * 1.2f;
                float ph = (float) ((T * sp * 1.4 + VFx.hash(i * 7 + 3)) % 1);
                float len = (0.12f + 0.25f * VFx.hash(i * 7 + 4)) * diag;
                float ro = diag * (1.05f - ph * 0.45f), ri = ro - len * (0.6f + 0.4f * inten);
                float al = (float) Math.sin(Math.PI * ph) * inten * strength.f() * 0.5f;
                float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
                int col = frost.on() ? VFx.FROST : pal(color, VFx.hash(i));
                Nvg.lineGradient(cx + ca * ro, cy + sa * ro, cx + ca * ri, cy + sa * ri, 1f + 1.6f * VFx.hash(i * 7 + 5), Nvg.alpha(col, Math.min(1, al)), Nvg.alpha(col, 0));
            }
            Nvg.additive(false);
        }
    }

    /* ================================================================== 12. hit vignette */

    static final class HitVignette extends Fx {
        private final Setting.Mode tone = mode("Tone", "Red", "Red", "Frost", "Palette");
        private final Setting.Num size = num("Size", 0.22, 0.08, 0.45, 0.01);
        private final Setting.Num strength = num("Strength", 0.7, 0.2, 1, 0.05);
        private final Setting.Num duration = num("Duration", 0.7, 0.2, 2, 0.05);
        private final Setting.Mode color = VisualModules.colors(this);
        private int lastHurt;
        private double born = -10;

        HitVignette() {
            super("Hit Vignette", "The screen edges pulse softly when you take damage");
        }

        @Override
        public void render2d(float td) {
            frame();
            if (mc.player.hurtTime > lastHurt) born = VFx.now();
            lastHurt = mc.player.hurtTime;
            float age = (float) (VFx.now() - born);
            float k = VFx.smooth(age / 0.07f) * (float) Math.exp(-age * 4.5 / duration.f());
            if (age > duration.f() * 1.6f || k < 0.01f) return;
            int col = switch (tone.get()) {
                case "Frost" -> VFx.FROST;
                case "Palette" -> pal(color, 0);
                default -> 0xFFE8384F;
            };
            edges(Nvg.w, Nvg.h, size.f(), Nvg.alpha(col, k * strength.f()));
        }

        static void edges(float W, float H, float s, int c) {
            int z = c & 0xFFFFFF;
            float sx = W * s, sy = H * s;
            Nvg.rrectGradient(0, 0, W, sy, 0, 0, 0, 0, sy, c, z);
            Nvg.rrectGradient(0, H - sy, W, sy, 0, 0, H, 0, H - sy, c, z);
            Nvg.rrectGradient(0, 0, sx, H, 0, 0, 0, sx, 0, c, z);
            Nvg.rrectGradient(W - sx, 0, sx, H, 0, W, 0, W - sx, 0, c, z);
        }
    }

    /* ================================================================== 13. low hp frost */

    static final class LowHpFrost extends Fx {
        private final Setting.Num threshold = num("Health", 8, 2, 16, 1);
        private final Setting.Num strength = num("Strength", 0.85, 0.2, 1, 0.05);
        private final Setting.Bool heartbeat = bool("Heartbeat", true);
        private final Setting.Bool crystals = bool("Crystals", true);
        private final List<float[]> lines = new ArrayList<>(); // x1 y1 x2 y2 (0..1) depth order
        private float k;

        LowHpFrost() {
            super("Low HP Frost", "Frost creeps in from the screen edges when your health is low");
            Random r = new Random(7);
            for (int i = 0; i < 34; i++) {
                int edge = i % 4;
                float t = r.nextFloat();
                float x = edge == 0 ? t : edge == 1 ? t : edge == 2 ? 0 : 1, y = edge == 0 ? 0 : edge == 1 ? 1 : t;
                float ang = (float) Math.atan2(0.5 - y, 0.5 - x) + (r.nextFloat() - 0.5f) * 0.9f;
                branch(r, x, y, ang, 0.09f + r.nextFloat() * 0.07f, 0);
            }
        }

        private void branch(Random r, float x, float y, float ang, float len, int depth) {
            float x2 = x + (float) Math.cos(ang) * len, y2 = y + (float) Math.sin(ang) * len * 1.6f;
            lines.add(new float[]{x, y, x2, y2, depth});
            if (depth >= 3) return;
            branch(r, x2, y2, ang + (r.nextFloat() - 0.5f) * 0.4f, len * 0.7f, depth + 1);
            if (r.nextFloat() < 0.8f) branch(r, x + (x2 - x) * 0.5f, y + (y2 - y) * 0.5f, ang + 0.7f + r.nextFloat() * 0.3f, len * 0.45f, depth + 1);
            if (r.nextFloat() < 0.8f) branch(r, x + (x2 - x) * 0.6f, y + (y2 - y) * 0.6f, ang - 0.7f - r.nextFloat() * 0.3f, len * 0.4f, depth + 1);
        }

        @Override
        public void render2d(float td) {
            frame();
            float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            float target = MathHelper.clamp((threshold.f() - hp) / threshold.f() + (hp < threshold.f() ? 0.25f : 0), 0, 1);
            k += (target - k) * (1 - (float) Math.exp(-2.5 * dt));
            if (k < 0.01f) return;
            double T = VFx.now();
            float beat = heartbeat.on() ? (float) Math.pow(Math.max(0, Math.sin(T * Math.PI * (1.1 + k))), 14) : 0;
            float W = Nvg.w, H = Nvg.h, s = strength.f();
            HitVignette.edges(W, H, 0.16f + 0.14f * k, Nvg.alpha(VFx.FROST, (0.35f + 0.25f * beat) * k * s));
            HitVignette.edges(W, H, 0.06f + 0.05f * k, Nvg.alpha(0xFFFFFFFF, 0.3f * k * s));
            if (crystals.on()) {
                for (float[] l : lines) {
                    float grow = MathHelper.clamp(k * 4 - l[4] * 0.7f, 0, 1);
                    if (grow <= 0) continue;
                    float x1 = l[0] * W, y1 = l[1] * H, x2 = (l[0] + (l[2] - l[0]) * grow) * W, y2 = (l[1] + (l[3] - l[1]) * grow) * H;
                    float al = (0.55f - l[4] * 0.1f) * k * s;
                    Nvg.line(x1, y1, x2, y2, 4f - l[4], Nvg.alpha(VFx.FROST, al * 0.25f));
                    Nvg.line(x1, y1, x2, y2, 1.4f - l[4] * 0.25f, Nvg.alpha(0xFFFFFFFF, al));
                }
            }
        }
    }

    /* ================================================================== 14. crit sparks */

    static final class CritSparks extends Fx {
        private final Setting.Num count = num("Sparks", 22, 6, 60, 1);
        private final Setting.Num power = num("Power", 1, 0.4, 2.5, 0.1);
        private final Setting.Bool every = bool("Every Hit", false);
        private final Setting.Mode color = VisualModules.colors(this);
        private final List<double[]> sparks = new ArrayList<>(); // x y z vx vy vz born life seed
        private final List<double[]> flashes = new ArrayList<>();
        private final Random r = new Random();

        CritSparks() {
            super("Crit Sparks", "Critical hits burst into streaking sparks");
        }

        @Override
        protected void onDisable() {
            sparks.clear();
            flashes.clear();
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            Entity e = newHit();
            double now = VFx.now();
            if (e != null) {
                boolean crit = mc.player.fallDistance > 0 && !mc.player.isOnGround() && !mc.player.isClimbing() && !mc.player.isTouchingWater();
                if (crit || every.on()) {
                    Vec3d c = e.getBoundingBox().getCenter().add(0, e.getHeight() * 0.15, 0);
                    Vec3d away = c.subtract(mc.player.getEyePos()).normalize();
                    for (int i = 0; i < count.i(); i++) {
                        Vec3d d = new Vec3d(r.nextGaussian(), r.nextGaussian() * 0.6 + 0.6, r.nextGaussian()).normalize().add(away.multiply(-0.5)).normalize();
                        double sp = (3 + r.nextDouble() * 5) * power.f();
                        sparks.add(new double[]{c.x, c.y, c.z, d.x * sp, d.y * sp, d.z * sp, now, 0.4 + r.nextDouble() * 0.5, r.nextDouble()});
                    }
                    flashes.add(new double[]{c.x, c.y, c.z, now});
                }
            }
            sparks.removeIf(s -> now - s[6] > s[7]);
            flashes.removeIf(f -> now - f[3] > 0.2);
            for (double[] s : sparks) {
                double drag = Math.exp(-3.5 * dt);
                s[3] *= drag;
                s[5] *= drag;
                s[4] = s[4] * drag - 14 * dt;
                s[0] += s[3] * dt;
                s[1] += s[4] * dt;
                s[2] += s[5] * dt;
                float k = (float) ((now - s[6]) / s[7]);
                float al = 1 - k * k;
                Vec3d at = new Vec3d(s[0], s[1], s[2]);
                Vec3d tail = at.subtract(s[3] * 0.045, s[4] * 0.045, s[5] * 0.045);
                int col = VFx.mix(pal(color, (float) s[8]), 0xFFFFFFFF, 0.3f);
                Render3D.drawLineGradient(tail, at, VFx.a(col, 0), VFx.a(col, al), 2.4f, true);
                VFx.spark(at, 0.018, col, al, true);
            }
            for (double[] f : flashes) {
                float k = (float) ((now - f[3]) / 0.2);
                VFx.spark(new Vec3d(f[0], f[1], f[2]), 0.2 * (0.6 + k), pal(color, 0), 1 - k, false);
            }
        }
    }

    /* ================================================================== 15. ground rings */

    static final class GroundRings extends Fx {
        private final Setting.Num radius = num("Radius", 1.1, 0.4, 3, 0.05);
        private final Setting.Num speed = num("Speed", 1, 0.2, 3, 0.1);
        private final Setting.Num rings = num("Rings", 3, 1, 6, 1);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = VisualModules.colors(this);

        GroundRings() {
            super("Ground Rings", "Rings ripple outward continuously under players' feet");
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now() * speed.f();
            for (PlayerEntity p : players(others.on(), true)) {
                Vec3d c = p.getLerpedPos(td).add(0, 0.025, 0);
                double R = radius.f();
                int n = rings.i();
                VFx.disc(c, VFx.X, VFx.Z, R * 0.55, 32, VFx.a(pal(color, 0), 0.14f), VFx.a(pal(color, 0), 0), true);
                for (int j = 0; j < n; j++) {
                    float k = (float) ((T * 0.5 + j / (double) n) % 1);
                    float al = (float) Math.pow(Math.sin(Math.PI * k), 1.5);
                    VFx.Col col = t -> pal(color, (float) (0.5 + 0.5 * Math.sin((t + k) * Math.PI * 2)));
                    VFx.ring(c, VFx.X, VFx.Z, R * (0.15 + 0.85 * k), 0.07, 64, col, 0.3f * al, 0, true);
                    VFx.ring(c, VFx.X, VFx.Z, R * (0.15 + 0.85 * k), 0.012, 64, col, 0.85f * al, 0, true);
                }
                VFx.arc(c, VFx.X, VFx.Z, R, 0.025, T, T + 2.2, 36, t -> pal(color, t), 0.8f, true);
                VFx.arc(c, VFx.X, VFx.Z, R, 0.025, T + Math.PI, T + Math.PI + 2.2, 36, t -> pal(color, t), 0.8f, true);
            }
        }
    }

    /* ================================================================== 16. spirit aura */

    static final class SpiritAura extends Fx {
        private final Setting.Num count = num("Motes", 26, 8, 70, 1);
        private final Setting.Num radius = num("Radius", 0.6, 0.3, 1.5, 0.05);
        private final Setting.Num speed = num("Speed", 0.6, 0.1, 2, 0.05);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = VisualModules.colors(this);

        SpiritAura() {
            super("Spirit Aura", "Enchanted motes rise and swirl around players like magic");
        }

        private Vec3d at(BodyAnchor b, float h, double R, int i, int n, double T) {
            double ph = ((T + i / (double) n * 3.7 + VFx.hash(i)) % 1 + 1) % 1;
            double ang = i * 2.39996 + T * 2.2 + ph * 2;
            double r = R * (1 - ph * 0.45);
            return b.at(Math.cos(ang) * r, ph * h * 1.15, Math.sin(ang) * r);
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now() * speed.f();
            for (PlayerEntity p : players(others.on(), false)) {
                BodyAnchor b = BodyAnchor.of(p, td);
                float h = (float) (b.length() / b.scale);
                int n = count.i();
                for (int i = 0; i < n; i++) {
                    double ph = ((T + i / (double) n * 3.7 + VFx.hash(i)) % 1 + 1) % 1;
                    float al = (float) Math.pow(Math.sin(Math.PI * ph), 1.2);
                    int col = pal(color, VFx.hash(i * 3));
                    if (ph > 0.06) {
                        List<Vec3d> tail = new ArrayList<>();
                        int L = 6;
                        float[] w = new float[L];
                        int[] cs = new int[L];
                        for (int j = L - 1; j >= 0; j--) {
                            tail.add(at(b, h, radius.f(), i, n, T - j * 0.012));
                            float k = 1 - j / (float) (L - 1);
                            w[L - 1 - j] = 0.02f * k;
                            cs[L - 1 - j] = VFx.a(col, 0.6f * k * al);
                        }
                        VFx.ribbon(tail, w, cs, VFx.CAMERA, null, 0.3f, true);
                    }
                    VFx.spark(at(b, h, radius.f(), i, n, T), 0.022, col, al, true);
                }
            }
        }
    }

    /* ================================================================== 17. shield bubble */

    static final class ShieldBubble extends Fx {
        private final Setting.Num radius = num("Radius", 1.3, 0.8, 2.5, 0.05);
        private final Setting.Num opacity = num("Opacity", 0.6, 0.1, 1, 0.05);
        private final Setting.Num spin = num("Spin", 0.3, 0, 2, 0.05);
        private final Setting.Bool others = bool("Other Players", false);
        private final Setting.Mode color = VisualModules.colors(this);
        private float flash;
        private int lastHurt;

        ShieldBubble() {
            super("Shield Bubble", "A fresnel-lit energy sphere that flares when you get hit");
        }

        private float fres(Vec3d c, Vec3d p) {
            Vec3d n = p.subtract(c).normalize(), v = p.subtract(VFx.cam).normalize();
            float f = 1 - (float) Math.abs(n.dotProduct(v));
            return 0.12f + 0.88f * f * f;
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            if (mc.player.hurtTime > lastHurt) flash = 1;
            lastHurt = mc.player.hurtTime;
            flash *= (float) Math.exp(-4 * dt);
            double T = VFx.now();
            for (PlayerEntity p : players(others.on(), false)) {
                BodyAnchor ba = BodyAnchor.of(p, td);
                Vec3d c = ba.at(0, ba.length() / ba.scale * 0.5, 0);
                double R = radius.f() * (1 + 0.04 * flash);
                float op = opacity.f() * (p == mc.player ? 1 + flash : 1);
                int LAT = 10, LON = 20;
                double rot = T * spin.f();
                Vec3d[][] g = new Vec3d[LAT + 1][LON + 1];
                for (int i = 0; i <= LAT; i++) {
                    double la = -Math.PI / 2 + i * Math.PI / LAT;
                    for (int j = 0; j <= LON; j++) {
                        double lo = j * Math.PI * 2 / LON + rot;
                        g[i][j] = c.add(Math.cos(la) * Math.cos(lo) * R, Math.sin(la) * R, Math.cos(la) * Math.sin(lo) * R);
                    }
                }
                for (int i = 0; i < LAT; i++)
                    for (int j = 0; j < LON; j++) {
                        Vec3d a = g[i][j], b = g[i][j + 1], d = g[i + 1][j + 1], e = g[i + 1][j];
                        float u = j / (float) LON, v = i / (float) LAT;
                        int ca = VFx.a(pal(color, (float) (0.5 + 0.5 * Math.sin((u + T * 0.1) * Math.PI * 2))), 0.1f * op * fres(c, a));
                        int cb = VFx.a(pal(color, (float) (0.5 + 0.5 * Math.sin((u + 1f / LON + T * 0.1) * Math.PI * 2))), 0.1f * op * fres(c, b));
                        int cd = VFx.am(cb, fres(c, d) / Math.max(0.01f, fres(c, b))), ce = VFx.am(ca, fres(c, e) / Math.max(0.01f, fres(c, a)));
                        Render3D.drawGradientQuad(a, b, d, e, ca, cb, cd, ce, true);
                        float la = 0.55f * op;
                        if (i > 0) Render3D.drawLineGradient(a, b, VFx.a(ca | 0xFF000000, la * fres(c, a)), VFx.a(cb | 0xFF000000, la * fres(c, b)), 1.2f, true);
                        if (j % 2 == 0) Render3D.drawLineGradient(a, e, VFx.a(ca | 0xFF000000, la * fres(c, a)), VFx.a(ce | 0xFF000000, la * fres(c, e)), 1.2f, true);
                    }
                // scanning band of light
                double sy = Math.sin(T * 1.2) * R * 0.9;
                double rr = Math.sqrt(Math.max(0, R * R - sy * sy));
                VFx.ring(c.add(0, sy, 0), VFx.X, VFx.Z, rr, 0.08, 64, t -> pal(color, t), 0.35f * op, 0, true);
            }
        }
    }

    /* ================================================================== 18. holo ring */

    static final class HoloRing extends Fx {
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num spin = num("Spin", 1, 0, 4, 0.1);
        private final Setting.Bool allPlayers = bool("All Players", false);
        private final Setting.Bool scan = bool("Scanline", true);
        private final Setting.Mode color = VisualModules.colors(this);
        private final Map<Integer, Float> vis = new HashMap<>();

        HoloRing() {
            super("Holo Ring", "Holographic HUD rings with ticks and scanlines around your target");
        }

        @Override
        protected void onDisable() {
            vis.clear();
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            Set<Integer> want = new HashSet<>();
            Entity t = VisualModules.lastTarget;
            if (t instanceof LivingEntity && t.isAlive() && System.currentTimeMillis() - VisualModules.lastHit < 3000) want.add(t.getId());
            if (allPlayers.on()) for (PlayerEntity p : players(true, false)) if (p != mc.player) want.add(p.getId());
            for (Integer id : want) vis.putIfAbsent(id, 0f);
            Iterator<Map.Entry<Integer, Float>> it = vis.entrySet().iterator();
            while (it.hasNext()) {
                var en = it.next();
                Entity e = mc.world.getEntityById(en.getKey());
                float goal = want.contains(en.getKey()) && e != null ? 1 : 0;
                float v = en.getValue() + (goal - en.getValue()) * (1 - (float) Math.exp(-8 * dt));
                en.setValue(v);
                if (e == null || (goal == 0 && v < 0.01f)) {
                    it.remove();
                    continue;
                }
                draw(e, td, T, v);
            }
        }

        private void draw(Entity e, float td, double T, float v) {
            Vec3d b = e.getLerpedPos(td);
            float h = e.getHeight();
            double R = (Math.max(0.5, e.getWidth()) * 0.9 + 0.3) * size.f() * (0.7 + 0.3 * VFx.outCubic(v));
            VFx.Col col = t -> pal(color, t);
            Vec3d mid = e instanceof LivingEntity le ? BodyAnchor.of(le, td).center() : b.add(0, h * 0.5, 0);
            double rot = T * spin.f();
            for (int k = 0; k < 3; k++) {
                double a0 = rot + k * Math.PI * 2 / 3;
                VFx.arc(mid, VFx.X, VFx.Z, R, 0.03, a0, a0 + 1.5, 32, col, 0.85f * v, false);
                VFx.arc(mid, VFx.X, VFx.Z, R, 0.12, a0, a0 + 1.5, 32, col, 0.18f * v, false);
            }
            for (int k = 0; k < 2; k++) {
                double a0 = -rot * 1.4 + k * Math.PI;
                VFx.arc(b.add(0, 0.05, 0), VFx.X, VFx.Z, R * 0.8, 0.02, a0, a0 + 2.4, 32, col, 0.7f * v, false);
                VFx.arc(b.add(0, h + 0.1, 0), VFx.X, VFx.Z, R * 0.7, 0.02, -a0, -a0 + 2.0, 32, col, 0.6f * v, false);
            }
            for (int i = 0; i < 48; i++) {
                double an = i * Math.PI * 2 / 48 - rot * 0.5;
                double len = i % 4 == 0 ? 0.12 : 0.05;
                Vec3d d = new Vec3d(Math.cos(an), 0, Math.sin(an));
                Vec3d p0 = mid.add(d.multiply(R * 1.12)), p1 = p0.add(0, len, 0);
                int c = pal(color, i / 48f);
                Render3D.drawLineGradient(p0.add(0, -len / 2, 0), p1.add(0, -len / 2, 0), VFx.a(c, 0.15f * v), VFx.a(c, 0.75f * v), 1.4f, false);
            }
            if (scan.on()) {
                double sy = (0.5 + 0.5 * Math.sin(T * 2.2)) * h;
                VFx.ring(b.add(0, sy, 0), VFx.X, VFx.Z, R * 0.95, 0.09, 64, col, 0.35f * v, 0, false);
                VFx.ring(b.add(0, sy, 0), VFx.X, VFx.Z, R * 0.95, 0.01, 64, t -> 0xFFFFFFFF, 0.6f * v, 0, false);
            }
        }
    }

    /* ================================================================== 19. cape glow */

    static final class CapeGlow extends Fx {
        private final Setting.Num length = num("Length", 1.0, 0.5, 1.8, 0.05);
        private final Setting.Num width = num("Width", 0.32, 0.15, 0.6, 0.01);
        private final Setting.Num flow = num("Flow", 1, 0, 3, 0.1);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = VisualModules.colors(this);
        private final Map<Integer, Vec3d[]> chains = new HashMap<>();

        CapeGlow() {
            super("Cape Glow", "A flowing cloth-physics cape of light on players' backs");
        }

        @Override
        protected void onDisable() {
            chains.clear();
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            Set<Integer> seen = new HashSet<>();
            for (PlayerEntity p : players(others.on(), false)) {
                seen.add(p.getId());
                BodyAnchor ba = BodyAnchor.of(p, td);
                Vec3d back = ba.backDir(), rt = ba.right;
                Vec3d anchor = ba.back(0.97, 0.01);
                int N = 9;
                double seg = length.f() / (N - 1);
                Vec3d[] ch = chains.get(p.getId());
                if (ch == null || ch[0].squaredDistanceTo(anchor) > 9) {
                    ch = new Vec3d[N];
                    for (int i = 0; i < N; i++) ch[i] = anchor.add(0, -seg * i, 0).add(back.multiply(0.02 * i));
                    chains.put(p.getId(), ch);
                }
                ch[0] = anchor;
                for (int i = 1; i < N; i++) {
                    float k = i / (float) (N - 1);
                    Vec3d wind = back.multiply((0.6 + 0.4 * Math.sin(T * 2.3 * flow.f() + i * 0.7)) * 0.9 * flow.f() * k).add(rt.multiply(Math.sin(T * 1.7 + i) * 0.15 * k));
                    // gravity plus the airstream of your own motion (streams behind you when flying)
                    Vec3d target = ch[i].add(0, -2.2 * dt, 0).add(wind.multiply(dt)).add(ba.vel.multiply(-14 * dt * k));
                    Vec3d d = target.subtract(ch[i - 1]);
                    double len = d.length();
                    Vec3d np = len < 1e-6 ? ch[i - 1].add(0, -seg, 0) : ch[i - 1].add(d.multiply(seg / len));
                    // keep the cloth out of the body
                    double behind = np.subtract(anchor).dotProduct(back);
                    double min = 0.02 + 0.06 * k;
                    if (behind < min) np = np.add(back.multiply(min - behind));
                    ch[i] = ch[i].add(np.subtract(ch[i]).multiply(1 - Math.exp(-28 * dt)));
                }
                List<Vec3d> pts = VFx.catmull(java.util.Arrays.asList(ch), 3, null, null);
                int n = pts.size();
                float[] w = new float[n];
                int[] c = new int[n], hl = new int[n];
                float[] wh = new float[n];
                for (int i = 0; i < n; i++) {
                    float k = i / (float) (n - 1);
                    w[i] = width.f() * (1 + 0.15f * k);
                    float shimmer = (float) Math.pow(0.5 + 0.5 * Math.sin(k * 9 - T * 4 * Math.max(0.2, flow.f())), 4);
                    int col = pal(color, (float) ((k * 0.7 + T * 0.1) % 1));
                    c[i] = VFx.a(VFx.mix(col, 0xFFFFFFFF, shimmer * 0.4f), 0.5f * (1 - k * 0.55f));
                    wh[i] = w[i] * 0.85f;
                    hl[i] = VFx.a(0xFFFFFFFF, 0.22f * shimmer * (1 - k));
                }
                VFx.glowRibbon(pts, w, c, VFx.AXIS, rt, 2, 0.35f, 0.9f, true);
                VFx.ribbon(pts, w, c, VFx.AXIS, rt, 0.8f, true);
                VFx.ribbon(pts, wh, hl, VFx.AXIS, rt, 0.5f, true);
            }
            chains.keySet().retainAll(seen);
        }
    }

    /* ================================================================== 20. light pillar */

    static final class LightPillar extends Fx {
        private final Setting.Num height = num("Height", 40, 5, 120, 5);
        private final Setting.Num width = num("Width", 0.45, 0.1, 1.5, 0.05);
        private final Setting.Num minDist = num("Min Distance", 8, 0, 40, 1);
        private final Setting.Mode color = VisualModules.colors(this);

        LightPillar() {
            super("Light Pillar", "Beacon-like beams of light mark distant players");
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            for (PlayerEntity p : players(true, false)) {
                if (p == mc.player) continue;
                Vec3d b = p.getLerpedPos(td);
                double d = b.distanceTo(VFx.cam);
                float vis = VFx.smooth((float) ((d - minDist.f()) / 6));
                if (vis < 0.01f) continue;
                int N = 14;
                List<Vec3d> pts = new ArrayList<>();
                float[] w = new float[N], wc = new float[N];
                int[] c = new int[N], cc = new int[N];
                float pulse = (float) (0.85 + 0.15 * Math.sin(T * 3 + p.getId()));
                float scale = (float) Math.max(1, d / 30); // stay readable far away
                for (int i = 0; i < N; i++) {
                    float t = i / (float) (N - 1);
                    pts.add(b.add(0, height.f() * t * t, 0));
                    w[i] = width.f() * scale * pulse * (1 - 0.4f * t);
                    wc[i] = w[i] * 0.22f;
                    int col = pal(color, t);
                    c[i] = VFx.a(col, 0.4f * vis * (1 - t));
                    cc[i] = VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.7f), 0.85f * vis * (1 - t));
                }
                VFx.glowRibbon(pts, w, c, VFx.CAMERA, null, 3, 1.3f, 1, true);
                VFx.ribbon(pts, wc, cc, VFx.CAMERA, null, 0.3f, true);
                VFx.ring(b.add(0, 0.03, 0), VFx.X, VFx.Z, 0.6 * scale, 0.1 * scale, 48, t -> pal(color, t), 0.45f * vis, (float) T, true);
            }
        }
    }

    /* ================================================================== 21. land impact */

    static final class LandImpact extends Fx {
        private final Setting.Num minFall = num("Min Fall", 1.5, 0.5, 6, 0.1);
        private final Setting.Num size = num("Size", 1, 0.4, 2.5, 0.1);
        private final Setting.Mode color = VisualModules.colors(this);
        private final List<double[]> hits = new ArrayList<>(); // x y z born strength
        private boolean wasGround = true;
        private double maxFall;

        LandImpact() {
            super("Land Impact", "A shockwave and dust burst when you land from a fall");
        }

        @Override
        protected void onDisable() {
            hits.clear();
        }

        @Override
        public void tick() {
            boolean g = mc.player.isOnGround();
            if (!g) maxFall = Math.max(maxFall, mc.player.fallDistance);
            if (g && !wasGround) {
                if (maxFall >= minFall.f())
                    hits.add(new double[]{mc.player.getX(), mc.player.getY() + 0.03, mc.player.getZ(), VFx.now(), Math.min(1, maxFall / 8)});
                maxFall = 0;
            }
            wasGround = g;
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            double now = VFx.now();
            final float LIFE = 1.35f;
            hits.removeIf(h -> now - h[3] > LIFE);
            for (double[] h : hits) {
                float age = (float) (now - h[3]), k = age / LIFE;
                float s = (float) h[4] * size.f();
                Vec3d c = new Vec3d(h[0], h[1], h[2]);
                int base = pal(color, 0);
                int frost = VFx.mix(VFx.FROST, base, 0.35f);
                double Rmax = 1.1 + 3.4 * s;

                // 1. ground flash: a quick bright disc that eases out in ~0.25 s
                float fl = VFx.smooth(age / 0.04f) * (1 - VFx.smooth(age / 0.28f));
                if (fl > 0.01f) {
                    VFx.disc(c, VFx.X, VFx.Z, 0.5 + s * 1.4, 36, VFx.a(0xFFFFFFFF, 0.55f * fl), VFx.a(frost, 0), true);
                    VFx.sprite(c.add(0, 0.15, 0), 0.6 + s, VFx.a(0xFFFFFFFF, 0.25f * fl), true);
                }

                // 2. main shockwave: soft trailing gradient band + bright crest, eased expansion
                float e = VFx.outCubic(Math.min(1, k * 1.25f));
                float al = (float) Math.pow(1 - Math.min(1, k * 1.1f), 1.6);
                double R = 0.2 + (Rmax - 0.2) * e;
                double band = (0.35 + 0.9 * s) * (1 - 0.6 * e);
                shock(c, R, band, frost, 0.42f * al);
                VFx.ring(c, VFx.X, VFx.Z, R, 0.025 + 0.02 * s, 96, t -> VFx.mix(base, 0xFFFFFFFF, 0.7f), 0.95f * al, 0, true);
                // 3. a slower, fainter echo ring
                float k2 = Math.max(0, k - 0.12f) / 0.88f;
                if (k2 > 0) {
                    double R2 = 0.2 + (Rmax * 0.62 - 0.2) * VFx.outCubic(k2);
                    float a2 = (float) Math.pow(1 - k2, 2) * 0.5f;
                    VFx.ring(c, VFx.X, VFx.Z, R2, 0.06 + 0.05 * s, 72, t -> frost, a2 * 0.5f, 0, true);
                }

                // 4. dust puffs bursting outward with drag, rising a little then settling, growing and fading
                int count = 14 + Math.round(22 * Math.min(1, s));
                for (int i = 0; i < count; i++) {
                    int hs = (int) (h[3] * 1000) * 31 + i * 17;
                    double an = i * Math.PI * 2 / count + (VFx.hash(hs) - 0.5) * 0.5;
                    double v0 = (2.2 + 3.2 * VFx.hash(hs + 1)) * (0.5 + s), drag = 4.2;
                    double dist = 0.2 + v0 / drag * (1 - Math.exp(-drag * age));
                    double up = (0.25 + 0.5 * VFx.hash(hs + 2)) * (0.4 + s) * Math.sin(Math.PI * Math.min(1, age / 0.9)) * Math.exp(-age * 1.2);
                    Vec3d at = c.add(Math.cos(an) * dist, 0.06 + up, Math.sin(an) * dist);
                    float life = 0.75f + 0.5f * VFx.hash(hs + 3);
                    float pa = VFx.smooth(age / 0.06f) * (1 - VFx.smooth(age / life));
                    if (pa < 0.01f) continue;
                    double r = (0.07 + 0.1 * VFx.hash(hs + 4)) * (0.6 + s) * (0.6 + 1.6 * VFx.outCubic(age / life));
                    VFx.sprite(at, r * 1.8, VFx.a(frost, 0.16f * pa), true);
                    VFx.sprite(at, r, VFx.a(VFx.mix(frost, 0xFFFFFFFF, 0.4f), 0.32f * pa), true);
                }
                // 5. a few hot sparks kicked up on hard landings
                if (s > 0.35f) {
                    for (int i = 0; i < 10; i++) {
                        int hs = (int) (h[3] * 1000) * 7 + i * 29;
                        double an = VFx.hash(hs) * Math.PI * 2, v = 2 + 3 * VFx.hash(hs + 1), vy = 2 + 2.5 * VFx.hash(hs + 2);
                        double dist = v / 3 * (1 - Math.exp(-3 * age));
                        double y = vy * age - 6 * age * age;
                        if (y < -0.02) continue;
                        float pa = 1 - VFx.smooth(age / 0.7f);
                        VFx.spark(c.add(Math.cos(an) * dist, y + 0.05, Math.sin(an) * dist), 0.018, base, pa, true);
                    }
                }
            }
        }

        /** Flat ground band from r - w (transparent) up to r (alpha), then a short fade out past r. */
        private static void shock(Vec3d c, double r, double w, int col, float alpha) {
            if (alpha <= 0.004f) return;
            int seg = 96;
            double ri = Math.max(0, r - w), ro = r + w * 0.18;
            int ci = VFx.a(col, alpha), e = col & 0xFFFFFF;
            for (int i = 0; i < seg; i++) {
                double t0 = i * Math.PI * 2 / seg, t1 = (i + 1) * Math.PI * 2 / seg;
                double x0 = Math.cos(t0), z0 = Math.sin(t0), x1 = Math.cos(t1), z1 = Math.sin(t1);
                Vec3d a0 = c.add(x0 * ri, 0, z0 * ri), a1 = c.add(x1 * ri, 0, z1 * ri);
                Vec3d m0 = c.add(x0 * r, 0, z0 * r), m1 = c.add(x1 * r, 0, z1 * r);
                Vec3d o0 = c.add(x0 * ro, 0, z0 * ro), o1 = c.add(x1 * ro, 0, z1 * ro);
                Render3D.drawGradientQuad(a0, m0, m1, a1, e, ci, ci, e, true);
                Render3D.drawGradientQuad(m0, o0, o1, m1, ci, e, e, ci, true);
            }
        }
    }
}

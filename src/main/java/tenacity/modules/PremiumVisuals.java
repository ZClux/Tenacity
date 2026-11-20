package tenacity.modules;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import tenacity.core.Category;
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

/**
 * Premium visuals: time based eased animations, tick-delta interpolated anchors, soft additive multi-layer glow and
 * smooth fade in / fade out when toggled (modules keep rendering while {@link PV#lingers()}).
 */
final class PremiumVisuals {
    private PremiumVisuals() {
    }

    static List<Module> create() {
        return List.of(new GalaxyRing(), new IceCrown(), new HexWings(), new HoloGrid(), new Lanterns(), new SilkRibbons(),
                new ManaAura(), new StarDust(), new Sakura(), new GhostTrail(), new RuneOrbit(), new CrosshairHalo(),
                new Bokeh(), new Shockwave(), new CinematicBars());
    }

    /* ------------------------------------------------------------------ shared helpers */

    static final double TAU = Math.PI * 2;
    static final int WHITE = 0xFFFFFFFF;

    static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    static float smooth(float v) {
        v = clamp01(v);
        return v * v * (3 - 2 * v);
    }

    static float outCubic(float v) {
        v = clamp01(v);
        float u = 1 - v;
        return 1 - u * u * u;
    }

    static float outBack(float v) {
        v = clamp01(v);
        float c1 = 1.70158f, c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(v - 1, 3) + c1 * (float) Math.pow(v - 1, 2);
    }

    /** Exponential smoothing factor for a frame of length dt (frame rate independent). */
    static float ease(float dt, float rate) {
        return 1 - (float) Math.exp(-dt * rate);
    }

    static float hash(int i) {
        double s = Math.sin(i * 12.9898 + 78.233) * 43758.5453;
        return (float) (s - Math.floor(s));
    }

    static int a(int c, float alpha) {
        return Math.round(255 * clamp01(alpha)) << 24 | (c & 0xFFFFFF);
    }

    static int tri(int x, int y, int z, float t) {
        t = clamp01(t);
        return t < 0.5f ? Nvg.mix(x, y, t * 2) : Nvg.mix(y, z, (t - 0.5f) * 2);
    }

    static int pal(Setting.Mode m, float t) {
        t = clamp01(t);
        return switch (m.get()) {
            case "Aurora" -> tri(0xFF3CF5C0, 0xFF4FA8FF, 0xFFB46CFF, t);
            case "Sakura" -> Nvg.mix(0xFFFF8DBE, 0xFFFFEEF5, t);
            case "Warm" -> Nvg.mix(0xFFFFC270, 0xFFFF6A3D, t);
            case "Gold" -> Nvg.mix(0xFFFFC94A, 0xFFFFF3D2, t);
            case "Ocean" -> Nvg.mix(0xFF2E7BFF, 0xFF6EF7FF, t);
            default -> VisualModules.palette(m, t);
        };
    }

    static Setting.Mode cols(Module m, String def) {
        return m.mode("Color", def, "White", "Purple", "Ice", "Rose", "Chroma", "Aurora", "Sakura", "Warm", "Gold", "Ocean");
    }

    static boolean thirdPerson() {
        return !MC().options.getPerspective().isFirstPerson();
    }

    static net.minecraft.client.MinecraftClient MC() {
        return net.minecraft.client.MinecraftClient.getInstance();
    }

    static float bodyYaw(PlayerEntity p, float td) {
        return MathHelper.lerpAngleDegrees(td, p.lastBodyYaw, p.bodyYaw);
    }

    static Vec3d fwd(float yawDeg) {
        double y = Math.toRadians(yawDeg);
        return new Vec3d(-Math.sin(y), 0, Math.cos(y));
    }

    static Vec3d right(float yawDeg) {
        double y = Math.toRadians(yawDeg);
        return new Vec3d(-Math.cos(y), 0, -Math.sin(y));
    }

    /** Pixels per block at a world point (perspective scale for 2D sprites). */
    static float ppb(Vec3d p) {
        return VisualModules.pixels(p, 1);
    }

    /** Small soft additive light: wide halo, coloured body, hot core. Caller enables additive blending. */
    static void spark(float x, float y, float px, int col, float al) {
        if (al <= 0.003f || px <= 0.05f) return;
        Nvg.glowDisc(x, y, px * 3.4f, a(col, 0.20f * al), a(col, 0));
        Nvg.glowDisc(x, y, px * 1.5f, a(col, 0.60f * al), a(col, 0));
        Nvg.glowDisc(x, y, px * 0.6f, a(Nvg.mix(col, WHITE, 0.7f), al), a(WHITE, 0));
    }

    /** Double sided gradient quad (the no-depth pipeline culls back faces). */
    static void quad(Vec3d p1, Vec3d p2, Vec3d p3, Vec3d p4, int c1, int c2, int c3, int c4, boolean depth) {
        Render3D.drawGradientQuad(p1, p2, p3, p4, c1, c2, c3, c4, depth);
        Render3D.drawGradientQuad(p4, p3, p2, p1, c4, c3, c2, c1, depth);
    }

    static void tri3(Vec3d p0, Vec3d p1, Vec3d p2, int c0, int c1, int c2, boolean depth) {
        quad(p0, p1, p2, p2, c0, c1, c2, c2, depth);
    }

    /** Soft flat disc (centre colour fading to nothing at the rim). */
    static void disc(Vec3d c, double r, int seg, int inner, boolean depth) {
        int out = inner & 0xFFFFFF;
        for (int i = 0; i < seg; i++) {
            double t0 = i * TAU / seg, t1 = (i + 1) * TAU / seg;
            tri3(c, c.add(Math.cos(t0) * r, 0, Math.sin(t0) * r), c.add(Math.cos(t1) * r, 0, Math.sin(t1) * r), inner, out, out, depth);
        }
    }

    /** Base: fade in/out on toggle, per-pass dt, world/hud entry points with the eased visibility. */
    abstract static class PV extends Module {
        float vis;
        private long lastFade, lastW, lastH;

        PV(String n, String d) {
            super(n, d, Category.RENDER);
        }

        @Override
        public boolean lingers() {
            return vis > 0.004f;
        }

        float fadeRate() {
            return 6.5f;
        }

        private float fade() {
            long now = System.nanoTime();
            float dt = lastFade == 0 ? 0.016f : Math.min(0.1f, (now - lastFade) / 1e9f);
            lastFade = now;
            vis += ((enabled() ? 1 : 0) - vis) * ease(dt, fadeRate());
            if (!enabled() && vis < 0.004f) {
                vis = 0;
                onFadedOut();
            }
            return smooth(vis);
        }

        void onFadedOut() {
        }

        private static float step(long[] last, int i) {
            long now = System.nanoTime();
            long l = last[i];
            last[i] = now;
            return l == 0 ? 0.016f : Math.min(0.1f, (now - l) / 1e9f);
        }

        private final long[] clocks = new long[2];

        @Override
        public final void renderWorld(voided.events.impl.WorldRenderEvent event) {
            if (!inWorld()) return;
            float al = fade();
            float dt = step(clocks, 0);
            if (al <= 0.002f) return;
            world(mc.getRenderTickCounter().getTickProgress(true), System.nanoTime() / 1e9, dt, al);
        }

        @Override
        public final void render2d(float td) {
            if (!inWorld()) return;
            float al = fade();
            float dt = step(clocks, 1);
            if (al <= 0.002f) return;
            hud(mc.getRenderTickCounter().getTickProgress(true), System.nanoTime() / 1e9, dt, al);
        }

        void world(float td, double t, float dt, float al) {
        }

        void hud(float td, double t, float dt, float al) {
        }

        List<PlayerEntity> targets(Setting.Bool others) {
            List<PlayerEntity> l = new ArrayList<>();
            if (thirdPerson()) l.add(mc.player);
            if (others != null && others.on())
                for (AbstractClientPlayerEntity p : mc.world.getPlayers()) if (p != mc.player && p.isAlive() && !p.isInvisible()) l.add(p);
            return l;
        }
    }

    /* ------------------------------------------------------------------ galaxy ring */

    static final class GalaxyRing extends PV {
        private final Setting.Num count = num("Stars", 240, 60, 600, 10);
        private final Setting.Num size = num("Size", 1.2, 0.5, 2.5, 0.05);
        private final Setting.Num arms = num("Arms", 3, 2, 6, 1);
        private final Setting.Num speed = num("Speed", 1, 0, 3, 0.1);
        private final Setting.Bool nebula = bool("Nebula", true);
        private final Setting.Mode color = cols(this, "Aurora");

        GalaxyRing() {
            super("Galaxy Ring", "A slowly spinning spiral galaxy of glowing stars under your feet");
        }

        private Vec3d centre(float td) {
            return mc.player.getLerpedPos(td).add(0, 0.04, 0);
        }

        private float radius(float al) {
            return size.f() * (0.55f + 0.45f * outCubic(al));
        }

        @Override
        void world(float td, double t, float dt, float al) {
            if (!nebula.on()) return;
            Vec3d c = centre(td);
            float R = radius(al);
            int seg = 72, n = arms.i();
            double spin = t * speed.f() * 0.5;
            // nebula: a soft centre glow and arm-shaped haze bands
            disc(c, R * 0.45, 48, a(pal(color, 0.1f), 0.30f * al), true);
            for (int ring = 0; ring < 3; ring++) {
                double r0 = R * (0.15 + ring * 0.28), r1 = r0 + R * 0.30;
                for (int i = 0; i < seg; i++) {
                    double t0 = i * TAU / seg, t1 = (i + 1) * TAU / seg;
                    float k0 = (float) Math.pow(0.5 + 0.5 * Math.cos(n * (t0 - spin * (1.6 - ring * 0.4)) - r0 * 2.6 * n / R), 3);
                    float k1 = (float) Math.pow(0.5 + 0.5 * Math.cos(n * (t1 - spin * (1.6 - ring * 0.4)) - r0 * 2.6 * n / R), 3);
                    float fall = 0.16f * (1 - ring * 0.25f) * al;
                    int c0 = pal(color, (float) (r0 / R)), c1 = pal(color, (float) (r1 / R));
                    Vec3d a0 = c.add(Math.cos(t0) * r0, 0, Math.sin(t0) * r0), a1 = c.add(Math.cos(t1) * r0, 0, Math.sin(t1) * r0);
                    Vec3d b0 = c.add(Math.cos(t0) * r1, 0, Math.sin(t0) * r1), b1 = c.add(Math.cos(t1) * r1, 0, Math.sin(t1) * r1);
                    quad(a0, a1, b1, b0, a(c0, fall * k0), a(c0, fall * k1), a(c1, fall * k1 * 0.4f), a(c1, fall * k0 * 0.4f), true);
                }
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            Vec3d c = centre(td);
            float k = ppb(c);
            if (k <= 0) return;
            float R = radius(al);
            int n = arms.i(), cnt = count.i();
            double spin = t * speed.f();
            float[] s = new float[2];
            Nvg.additive(true);
            for (int i = 0; i < cnt; i++) {
                float h1 = hash(i * 3 + 1), h2 = hash(i * 3 + 2), h3 = hash(i * 3 + 3);
                float rr = (float) Math.pow(h1, 0.75);
                if (rr * cnt / (float) cnt > al * 1.4f) continue; // grows outward when enabling
                double ang = (i % n) * TAU / n + rr * 2.8 + (h2 - 0.5) * 0.7 * (1.15 - rr) + spin * 0.45 / (rr + 0.3);
                double r = R * (0.08 + 0.92 * rr);
                Vec3d q = c.add(Math.cos(ang) * r, Math.sin(t * 1.2 + h3 * 30) * 0.02 + 0.01, Math.sin(ang) * r);
                if (!Projection.point(q, s)) continue;
                float tw = (float) (0.55 + 0.45 * Math.sin(t * (2 + h2 * 3) + h3 * 40));
                float edge = 1 - smooth((rr - 0.7f) / 0.3f) * 0.85f;
                spark(s[0], s[1], k * 0.012f * (0.5f + h3 * 1.1f), pal(color, rr), al * tw * edge);
            }
            if (Projection.point(c, s)) spark(s[0], s[1], k * 0.06f, pal(color, 0), al * 0.8f);
            Nvg.additive(false);
        }
    }

    /* ------------------------------------------------------------------ ice crown */

    static final class IceCrown extends PV {
        private final Setting.Num spikes = num("Spikes", 10, 5, 16, 1);
        private final Setting.Num size = num("Size", 1, 0.6, 1.6, 0.05);
        private final Setting.Num height = num("Height", 1, 0.5, 2, 0.05);
        private final Setting.Num spin = num("Spin", 0.3, 0, 2, 0.05);
        private final Setting.Bool others = bool("Other Players", false);
        private final Setting.Mode color = cols(this, "Ice");

        IceCrown() {
            super("Ice Crown", "A crown of glassy ice crystals floating above your head");
        }

        private BodyAnchor ba;

        private Vec3d top(PlayerEntity p, float td, double t) {
            ba = BodyAnchor.of(p, td);
            return ba.headTop(0.1 + 0.035 * Math.sin(t * 1.6));
        }

        /** Point on the crown in the head's frame (follows head tilt, elytra and swim poses). */
        private Vec3d at(Vec3d c, double ang, double r, double y) {
            return c.add(ba.hRight.multiply(Math.cos(ang) * r)).add(ba.hFwd.multiply(Math.sin(ang) * r)).add(ba.hUp.multiply(y));
        }

        private double tall(int i, double t, float pop) {
            return (i % 2 == 0 ? 1 : 0.58) * height.f() * 0.30 * size.f() * pop * (0.92 + 0.08 * Math.sin(t * 2.2 + i));
        }

        @Override
        void world(float td, double t, float dt, float al) {
            float pop = outBack(al);
            for (PlayerEntity p : targets(others)) {
                Vec3d c = top(p, td, t);
                double R = 0.27 * size.f() * Math.max(0.05, pop), rot = t * spin.f();
                int n = spikes.i();
                double w = TAU / n * 0.46, bev = 0.035 * size.f();
                // band + its soft outward glow
                int seg = 64;
                for (int i = 0; i < seg; i++) {
                    double t0 = i * TAU / seg + rot, t1 = (i + 1) * TAU / seg + rot;
                    int ca = pal(color, (float) (0.5 + 0.5 * Math.sin(t0 * 2 + t))), cb = pal(color, (float) (0.5 + 0.5 * Math.sin(t1 * 2 + t)));
                    quad(at(c, t0, R, -0.035), at(c, t1, R, -0.035), at(c, t1, R, 0.01), at(c, t0, R, 0.01), a(ca, 0.55f * al), a(cb, 0.55f * al), a(cb, 0.25f * al), a(ca, 0.25f * al), false);
                    quad(at(c, t0, R, -0.035), at(c, t1, R, -0.035), at(c, t1, R + 0.16, -0.06), at(c, t0, R + 0.16, -0.06), a(ca, 0.22f * al), a(cb, 0.22f * al), a(cb, 0), a(ca, 0), false);
                    Render3D.drawLineGradient(at(c, t0, R, -0.035), at(c, t1, R, -0.035), a(Nvg.mix(ca, WHITE, 0.5f), 0.8f * al), a(Nvg.mix(cb, WHITE, 0.5f), 0.8f * al), 1.4f, false);
                }
                for (int i = 0; i < n; i++) {
                    double th = i * TAU / n + rot, h = tall(i, t, pop);
                    int col = pal(color, i / (float) n);
                    int base = a(col, 0.42f * al), side = a(Nvg.mix(col, WHITE, 0.35f), 0.30f * al), tip = a(WHITE, 0.92f * al);
                    Vec3d L = at(c, th - w, R, 0), Rr = at(c, th + w, R, 0), Mo = at(c, th, R + bev, 0), Mi = at(c, th, R - bev, 0), T = at(c, th, R * 1.07, h);
                    float shine = (float) Math.pow(0.5 + 0.5 * Math.cos(th - t * 1.3), 6);
                    int lit = a(Nvg.mix(col, WHITE, 0.4f + 0.5f * shine), (0.35f + 0.4f * shine) * al);
                    tri3(L, Mo, T, base, lit, tip, false);
                    tri3(Mo, Rr, T, lit, side, tip, false);
                    tri3(L, Mi, T, a(col, 0.25f * al), a(col, 0.18f * al), a(WHITE, 0.5f * al), false);
                    tri3(Mi, Rr, T, a(col, 0.18f * al), a(col, 0.25f * al), a(WHITE, 0.5f * al), false);
                    Render3D.drawLineGradient(L, T, a(col, 0.7f * al), a(WHITE, 0.95f * al), 1.2f, false);
                    Render3D.drawLineGradient(Rr, T, a(col, 0.7f * al), a(WHITE, 0.95f * al), 1.2f, false);
                    Render3D.drawLineGradient(Mo, T, a(WHITE, 0.35f * al), a(WHITE, 0.9f * al), 1.0f, false);
                }
            }
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float pop = outBack(al);
            float[] s = new float[2];
            Nvg.additive(true);
            for (PlayerEntity p : targets(others)) {
                Vec3d c = top(p, td, t);
                double R = 0.27 * size.f() * Math.max(0.05, pop), rot = t * spin.f();
                float k = ppb(c);
                int n = spikes.i();
                for (int i = 0; i < n; i += 2) {
                    double th = i * TAU / n + rot;
                    Vec3d T = at(c, th, R * 1.07, tall(i, t, pop));
                    if (!Projection.point(T, s)) continue;
                    float tw = (float) Math.pow(0.5 + 0.5 * Math.sin(t * 2.4 + i * 1.7), 4);
                    spark(s[0], s[1], k * 0.018f, pal(color, 0.2f), al * (0.35f + 0.65f * tw));
                    float L = k * 0.09f * tw;
                    Nvg.line(s[0] - L, s[1], s[0] + L, s[1], 1f, a(WHITE, 0.7f * tw * al));
                    Nvg.line(s[0], s[1] - L, s[0], s[1] + L, 1f, a(WHITE, 0.7f * tw * al));
                }
            }
            Nvg.additive(false);
        }
    }

    /* ------------------------------------------------------------------ hex wings */

    static final class HexWings extends PV {
        private final Setting.Num size = num("Size", 1, 0.5, 1.8, 0.05);
        private final Setting.Num flap = num("Flap Speed", 1, 0, 3, 0.1);
        private final Setting.Num spread = num("Spread", 28, 5, 60, 1);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Bool others = bool("Other Players", false);
        private final Setting.Mode color = cols(this, "Aurora");
        private static final List<float[]> CELLS = new ArrayList<>(); // x y (hex units), dist 0..1

        static {
            double s3 = Math.sqrt(3);
            for (int q = 0; q <= 8; q++)
                for (int r = -8; r <= 8; r++) {
                    double x = 1.5 * q + 0.9, y = s3 * (r + q / 2.0);
                    double top = 3.6 + 1.2 * x - 0.1 * x * x, bot = -3.4 + 0.45 * x;
                    if (x > 12.2 || y > top || y < bot) continue;
                    CELLS.add(new float[]{(float) x, (float) y, (float) Math.min(1, x / 12.2)});
                }
        }

        HexWings() {
            super("Hex Wings", "Wings built from glowing hexagonal light panels that ripple and flap");
        }

        @Override
        void world(float td, double t, float dt, float al) {
            for (PlayerEntity p : targets(others)) wings(p, td, t, al);
        }

        private void wings(PlayerEntity p, float td, double t, float al) {
            BodyAnchor ba = BodyAnchor.of(p, td);
            Vec3d f = ba.fwd, rgt = ba.right, up = ba.up;
            Vec3d origin = ba.back(0.86, 0.04);
            double hs = 0.078 * size.f();
            double fl = Math.sin(t * flap.f() * 2.2);
            double phi = Math.toRadians(spread.f() + 16 * fl) + (1 - al) * 1.2; // folded while fading
            Vec3d upv = up.multiply(0.96).subtract(f.multiply(0.28)).normalize();
            for (int side = -1; side <= 1; side += 2) {
                Vec3d d = rgt.multiply(side * Math.cos(phi)).subtract(f.multiply(Math.sin(phi)));
                for (float[] cell : CELLS) {
                    float dist = cell[2];
                    float cv = smooth(al * 1.8f - dist * 0.8f);
                    if (cv <= 0.01f) continue;
                    float wave = (float) (0.5 + 0.5 * Math.sin(t * 2.6 - dist * 7 + fl * 0.6));
                    int col = pal(color, (float) (dist * 0.85 + 0.15 * Math.sin(t * 0.7 + cell[1] * 0.3)));
                    Vec3d[] cor = new Vec3d[6], glo = new Vec3d[6];
                    Vec3d cen = map(origin, d, upv, f, cell[0] * hs, cell[1] * hs, size.f());
                    for (int k = 0; k < 6; k++) {
                        double an = k * Math.PI / 3;
                        cor[k] = map(origin, d, upv, f, (cell[0] + Math.cos(an) * 0.86) * hs, (cell[1] + Math.sin(an) * 0.86) * hs, size.f());
                        glo[k] = map(origin, d, upv, f, (cell[0] + Math.cos(an) * 1.32) * hs, (cell[1] + Math.sin(an) * 1.32) * hs, size.f());
                    }
                    int cc = a(Nvg.mix(col, WHITE, 0.25f * wave), (0.10f + 0.30f * wave) * cv * al);
                    int ce = a(col, (0.30f + 0.25f * wave) * cv * al);
                    for (int k = 0; k < 6; k++) {
                        int k2 = (k + 1) % 6;
                        tri3(cen, cor[k], cor[k2], cc, ce, ce, false);
                        if (glow.on()) quad(cor[k], cor[k2], glo[k2], glo[k], a(col, 0.16f * wave * cv * al), a(col, 0.16f * wave * cv * al), a(col, 0), a(col, 0), false);
                        Render3D.drawLine(cor[k], cor[k2], a(Nvg.mix(col, WHITE, 0.3f), (0.45f + 0.5f * wave) * cv * al), 1.2f, false);
                    }
                }
            }
        }

        /** Wing local (u outward, v up) to world, with a backward sweep that grows toward the tip. */
        private static Vec3d map(Vec3d o, Vec3d d, Vec3d upv, Vec3d f, double u, double v, double sz) {
            double sweep = u * u * 0.22 / sz;
            return o.add(d.multiply(u)).add(upv.multiply(v + u * 0.62)).subtract(f.multiply(sweep));
        }
    }

    /* ------------------------------------------------------------------ holo grid */

    static final class HoloGrid extends PV {
        private final Setting.Num radius = num("Radius", 4.5, 2, 9, 0.5);
        private final Setting.Num cell = num("Cell Size", 0.5, 0.25, 1, 0.05);
        private final Setting.Num scan = num("Scan Speed", 1, 0, 3, 0.1);
        private final Setting.Bool depth = bool("Depth Test", true);
        private final Setting.Mode color = cols(this, "Ocean");
        private double gy = Double.NaN;

        HoloGrid() {
            super("Holo Grid", "A holographic floor grid around you with a soft scanning pulse");
        }

        @Override
        void world(float td, double t, float dt, float al) {
            Vec3d c = mc.player.getLerpedPos(td);
            double target = mc.player.isOnGround() || Double.isNaN(gy) ? c.y : Math.min(gy, c.y);
            gy = Double.isNaN(gy) || Math.abs(gy - target) > 3 ? target : gy + (target - gy) * ease(dt, 10);
            double y = gy + 0.015, R = radius.f() * (0.4 + 0.6 * outCubic(al)), cs = cell.f();
            boolean dp = depth.on();
            double period = 2.6 / Math.max(0.05, scan.f());
            double pr = ((t % period) / period) * R * 1.25;
            float pf = 1 - (float) (pr / (R * 1.25));
            // soft base glow + the travelling pulse band
            disc(new Vec3d(c.x, y - 0.002, c.z), R * 0.9, 64, a(pal(color, 0.2f), 0.10f * al), dp);
            int seg = 80;
            for (int i = 0; i < seg; i++) {
                double t0 = i * TAU / seg, t1 = (i + 1) * TAU / seg;
                int col = pal(color, 0.5f);
                for (int s = -1; s <= 1; s += 2) {
                    double r0 = pr, r1 = pr + s * 0.35;
                    quad(new Vec3d(c.x + Math.cos(t0) * r0, y, c.z + Math.sin(t0) * r0), new Vec3d(c.x + Math.cos(t1) * r0, y, c.z + Math.sin(t1) * r0),
                            new Vec3d(c.x + Math.cos(t1) * r1, y, c.z + Math.sin(t1) * r1), new Vec3d(c.x + Math.cos(t0) * r1, y, c.z + Math.sin(t0) * r1),
                            a(col, 0.22f * pf * al), a(col, 0.22f * pf * al), a(col, 0), a(col, 0), dp);
                }
            }
            for (int axis = 0; axis < 2; axis++) {
                double cMain = axis == 0 ? c.x : c.z, cOther = axis == 0 ? c.z : c.x;
                for (double g = Math.ceil((cMain - R) / cs) * cs; g <= cMain + R; g += cs) {
                    double off = g - cMain;
                    double half = Math.sqrt(Math.max(0, R * R - off * off));
                    if (half < 0.05) continue;
                    double step = cs / 2;
                    Vec3d prev = null;
                    int pc = 0;
                    for (double o = -half; o <= half + 1e-6; o += Math.min(step, half * 2 / 4)) {
                        double oo = Math.min(o, half);
                        double dist = Math.sqrt(off * off + oo * oo);
                        float fall = (float) Math.pow(Math.max(0, 1 - (dist / R) * (dist / R)), 2);
                        float boost = (float) Math.exp(-Math.pow((dist - pr) / 0.4, 2)) * pf;
                        int col = Nvg.mix(pal(color, (float) (dist / R)), WHITE, boost * 0.6f);
                        int cc = a(col, (0.20f * fall + 0.75f * boost * (float) Math.sqrt(fall)) * al);
                        Vec3d pt = axis == 0 ? new Vec3d(g, y, cOther + oo) : new Vec3d(cOther + oo, y, g);
                        if (prev != null) Render3D.drawLineGradient(prev, pt, pc, cc, 1.3f, dp);
                        prev = pt;
                        pc = cc;
                    }
                }
            }
        }

        @Override
        void onFadedOut() {
            gy = Double.NaN;
        }
    }

    /* ------------------------------------------------------------------ lanterns */

    static final class Lanterns extends PV {
        private final Setting.Num count = num("Count", 2, 1, 4, 1);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num follow = num("Follow Speed", 1, 0.3, 3, 0.1);
        private final Setting.Mode color = cols(this, "Warm");
        private final Vec3d[] pos = new Vec3d[4], vel = new Vec3d[4];

        Lanterns() {
            super("Lanterns", "Floating paper lanterns that lazily follow you around");
        }

        private Vec3d target(int i, float td, double t) {
            float by = bodyYaw(mc.player, td);
            Vec3d f = fwd(by), r = right(by);
            int side = i % 2 == 0 ? 1 : -1;
            double out = 0.85 + 0.35 * (i / 2), back = 0.45 + 0.4 * (i / 2);
            return mc.player.getLerpedPos(td).add(r.multiply(side * out)).subtract(f.multiply(back))
                    .add(0, 1.85 + 0.18 * Math.sin(t * 1.3 + i * 1.9) + 0.1 * i, 0).add(f.multiply(0.12 * Math.sin(t * 0.7 + i)));
        }

        @Override
        void world(float td, double t, float dt, float al) {
            for (int i = 0; i < count.i(); i++) {
                Vec3d tg = target(i, td, t);
                if (pos[i] == null || pos[i].squaredDistanceTo(tg) > 100) {
                    pos[i] = tg;
                    vel[i] = Vec3d.ZERO;
                }
                Vec3d np = pos[i].add(tg.subtract(pos[i]).multiply(ease(dt, (2.2f + i * 0.6f) * follow.f())));
                Vec3d v = dt > 0 ? np.subtract(pos[i]).multiply(1 / dt) : Vec3d.ZERO;
                vel[i] = vel[i].add(v.subtract(vel[i]).multiply(ease(dt, 4)));
                pos[i] = np;
                lantern(pos[i], vel[i], t, i, al);
            }
        }

        private void lantern(Vec3d c, Vec3d v, double t, int i, float al) {
            float s = size.f() * (0.3f + 0.7f * outBack(al));
            Vec3d axis = new Vec3d(-v.x * 0.12, 1, -v.z * 0.12).normalize();
            Vec3d u = axis.crossProduct(new Vec3d(0, 0, 1));
            if (u.lengthSquared() < 1e-6) u = new Vec3d(1, 0, 0);
            u = u.normalize();
            Vec3d w = axis.crossProduct(u).normalize();
            double[] hy = {-0.13, -0.09, -0.03, 0.03, 0.09, 0.13};
            double[] hr = {0.035, 0.075, 0.098, 0.098, 0.075, 0.035};
            int seg = 16;
            float flick = (float) (0.85 + 0.1 * Math.sin(t * 9 + i * 3) + 0.05 * Math.sin(t * 23 + i));
            int col = pal(color, 0.25f);
            for (int ring = 0; ring < hy.length - 1; ring++) {
                float mid0 = 1 - Math.abs((float) hy[ring]) / 0.13f, mid1 = 1 - Math.abs((float) hy[ring + 1]) / 0.13f;
                int c0 = a(Nvg.mix(col, 0xFFFFF4D8, 0.55f * mid0 * flick), (0.55f + 0.35f * mid0) * al), c1 = a(Nvg.mix(col, 0xFFFFF4D8, 0.55f * mid1 * flick), (0.55f + 0.35f * mid1) * al);
                for (int k = 0; k < seg; k++) {
                    double a0 = k * TAU / seg, a1 = (k + 1) * TAU / seg;
                    float rib0 = k % 2 == 0 ? 0.85f : 1f;
                    Vec3d p00 = pt(c, axis, u, w, hy[ring] * s, hr[ring] * s, a0), p01 = pt(c, axis, u, w, hy[ring] * s, hr[ring] * s, a1);
                    Vec3d p10 = pt(c, axis, u, w, hy[ring + 1] * s, hr[ring + 1] * s, a0), p11 = pt(c, axis, u, w, hy[ring + 1] * s, hr[ring + 1] * s, a1);
                    quad(p00, p01, p11, p10, Nvg.alpha(c0, rib0), c0, c1, Nvg.alpha(c1, rib0), false);
                }
            }
            // dark caps + tassel
            Vec3d topC = c.add(axis.multiply(0.15 * s)), botC = c.subtract(axis.multiply(0.15 * s));
            for (int k = 0; k < seg; k++) {
                double a0 = k * TAU / seg, a1 = (k + 1) * TAU / seg;
                tri3(topC, pt(c, axis, u, w, 0.13 * s, 0.04 * s, a0), pt(c, axis, u, w, 0.13 * s, 0.04 * s, a1), a(0xFF2A1A12, 0.9f * al), a(0xFF3A2416, 0.9f * al), a(0xFF3A2416, 0.9f * al), false);
                tri3(botC, pt(c, axis, u, w, -0.13 * s, 0.04 * s, a0), pt(c, axis, u, w, -0.13 * s, 0.04 * s, a1), a(0xFF2A1A12, 0.9f * al), a(0xFF3A2416, 0.9f * al), a(0xFF3A2416, 0.9f * al), false);
            }
            Vec3d tassel = botC.subtract(axis.multiply(0.1 * s)).subtract(v.multiply(0.03));
            Render3D.drawLineGradient(botC, tassel, a(0xFFE04030, 0.9f * al), a(0xFFE04030, 0), 2f, false);
        }

        private static Vec3d pt(Vec3d c, Vec3d axis, Vec3d u, Vec3d w, double h, double r, double an) {
            return c.add(axis.multiply(h)).add(u.multiply(Math.cos(an) * r)).add(w.multiply(Math.sin(an) * r));
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float[] s = new float[2];
            for (int i = 0; i < count.i(); i++) {
                if (pos[i] == null || !Projection.point(pos[i], s)) continue;
                float flick = (float) (0.8 + 0.12 * Math.sin(t * 9 + i * 3) + 0.08 * Math.sin(t * 23 + i));
                float k = ppb(pos[i]) * size.f();
                int col = pal(color, 0.3f);
                Nvg.additive(true);
                Nvg.glowDisc(s[0], s[1], k * 1.1f, a(col, 0.10f * al * flick), a(col, 0));
                Nvg.glowDisc(s[0], s[1], k * 0.45f, a(col, 0.22f * al * flick), a(col, 0));
                Nvg.glowDisc(s[0], s[1], k * 0.16f, a(0xFFFFF0D0, 0.55f * al * flick), a(col, 0));
                Nvg.additive(false);
            }
        }

        @Override
        void onFadedOut() {
            java.util.Arrays.fill(pos, null);
        }
    }

    /* ------------------------------------------------------------------ silk ribbons */

    static final class SilkRibbons extends PV {
        private final Setting.Num count = num("Ribbons", 3, 1, 6, 1);
        private final Setting.Num length = num("Length", 1, 0.4, 2, 0.05);
        private final Setting.Num width = num("Width", 0.13, 0.04, 0.35, 0.01);
        private final Setting.Num radius = num("Radius", 0.85, 0.5, 1.6, 0.05);
        private final Setting.Num speed = num("Speed", 1, 0.2, 3, 0.1);
        private final Setting.Bool firstPerson = bool("First Person", false);
        private final Setting.Mode color = cols(this, "Sakura");

        SilkRibbons() {
            super("Silk Ribbons", "Flowing silk ribbons that dance in orbits around you");
        }

        @Override
        void world(float td, double t, float dt, float al) {
            if (!thirdPerson() && !firstPerson.on()) return;
            BodyAnchor ba = BodyAnchor.of(mc.player, td);
            Vec3d c = ba.origin;
            double h = ba.length();
            int n = count.i(), M = 56;
            double R = radius.f() * (0.5 + 0.5 * outCubic(al));
            for (int r = 0; r < n; r++) {
                double ph = r * TAU / n;
                Vec3d[] top = new Vec3d[M + 1], bot = new Vec3d[M + 1], gt = new Vec3d[M + 1], gb = new Vec3d[M + 1];
                float[] fa = new float[M + 1];
                int[] cc = new int[M + 1];
                for (int j = 0; j <= M; j++) {
                    float s = j / (float) M;
                    double th = t * speed.f() * 1.3 + ph - s * length.f() * 2.4;
                    double rad = R * (1 + 0.14 * Math.sin(th * 2 + ph + t * 0.4));
                    double y = h * (0.2 + 0.6 * (0.5 + 0.5 * Math.sin(th * 1.25 + ph * 2 + t * 0.35)));
                    Vec3d out = ba.right.multiply(Math.cos(th)).add(ba.fwd.multiply(Math.sin(th)));
                    Vec3d P = c.add(out.multiply(rad)).add(ba.up.multiply(y));
                    double tw = s * 2.6 + t * 0.9 + ph;
                    Vec3d nrm = ba.up.multiply(Math.cos(tw)).add(out.multiply(Math.sin(tw) * 0.8)).normalize();
                    double wd = width.f() * Math.pow(Math.sin(Math.PI * Math.min(1, s * 1.15 + 0.06)), 0.6) * (1 - s * 0.35);
                    top[j] = P.add(nrm.multiply(wd / 2));
                    bot[j] = P.subtract(nrm.multiply(wd / 2));
                    gt[j] = P.add(nrm.multiply(wd * 1.6));
                    gb[j] = P.subtract(nrm.multiply(wd * 1.6));
                    fa[j] = (float) Math.pow(1 - s, 1.3) * al;
                    float shim = (float) Math.pow(0.5 + 0.5 * Math.sin(s * 14 - t * 4 + ph), 3);
                    cc[j] = Nvg.mix(pal(color, s), WHITE, 0.45f * shim);
                }
                for (int j = 0; j < M; j++) {
                    int c0 = cc[j], c1 = cc[j + 1];
                    float f0 = fa[j], f1 = fa[j + 1];
                    quad(top[j], top[j + 1], gt[j + 1], gt[j], a(c0, 0.18f * f0), a(c1, 0.18f * f1), a(c1, 0), a(c0, 0), false);
                    quad(bot[j], bot[j + 1], gb[j + 1], gb[j], a(c0, 0.18f * f0), a(c1, 0.18f * f1), a(c1, 0), a(c0, 0), false);
                    quad(top[j], top[j + 1], bot[j + 1], bot[j], a(c0, 0.62f * f0), a(c1, 0.62f * f1), a(c1, 0.45f * f1), a(c0, 0.45f * f0), false);
                    Render3D.drawLineGradient(top[j], top[j + 1], a(Nvg.mix(c0, WHITE, 0.5f), 0.7f * f0), a(Nvg.mix(c1, WHITE, 0.5f), 0.7f * f1), 1.1f, false);
                }
            }
        }
    }

    /* ------------------------------------------------------------------ mana aura */

    static final class ManaAura extends PV {
        private final Setting.Num radius = num("Radius", 0.6, 0.3, 1.2, 0.05);
        private final Setting.Num height = num("Height", 1.3, 0.4, 2.5, 0.05);
        private final Setting.Num sparks = num("Sparks", 40, 0, 120, 5);
        private final Setting.Num speed = num("Speed", 1, 0.2, 3, 0.1);
        private final Setting.Mode color = cols(this, "Aurora");

        ManaAura() {
            super("Mana Aura", "A flowing mana sheath around your body with rising sparks");
        }

        @Override
        void world(float td, double t, float dt, float al) {
            Vec3d c = mc.player.getLerpedPos(td).add(0, 0.02, 0);
            double R = radius.f() * (0.6 + 0.4 * outCubic(al)), H = height.f() * outCubic(al);
            disc(c, R * 1.7, 56, a(pal(color, 0.2f), 0.17f * al), true);
            int seg = 56;
            double sp = t * speed.f();
            for (int i = 0; i < seg; i++) {
                double t0 = i * TAU / seg, t1 = (i + 1) * TAU / seg;
                float s0 = streak(t0, sp), s1 = streak(t1, sp);
                int c0 = pal(color, (float) (0.5 + 0.5 * Math.sin(t0 + sp * 0.5))), c1 = pal(color, (float) (0.5 + 0.5 * Math.sin(t1 + sp * 0.5)));
                Vec3d b0 = c.add(Math.cos(t0) * R, 0, Math.sin(t0) * R), b1 = c.add(Math.cos(t1) * R, 0, Math.sin(t1) * R);
                if (thirdPerson()) {
                    Vec3d u0 = c.add(Math.cos(t0) * R * 0.8, H * (0.6 + 0.4 * s0), Math.sin(t0) * R * 0.8), u1 = c.add(Math.cos(t1) * R * 0.8, H * (0.6 + 0.4 * s1), Math.sin(t1) * R * 0.8);
                    quad(b0, b1, u1, u0, a(c0, (0.12f + 0.30f * s0) * al), a(c1, (0.12f + 0.30f * s1) * al), a(c1, 0), a(c0, 0), true);
                }
                Render3D.drawLineGradient(b0, b1, a(Nvg.mix(c0, WHITE, 0.4f), 0.85f * al), a(Nvg.mix(c1, WHITE, 0.4f), 0.85f * al), 1.6f, true);
                Vec3d o0 = c.add(Math.cos(t0) * R * 1.25, 0, Math.sin(t0) * R * 1.25), o1 = c.add(Math.cos(t1) * R * 1.25, 0, Math.sin(t1) * R * 1.25);
                quad(b0, b1, o1, o0, a(c0, 0.35f * al), a(c1, 0.35f * al), a(c1, 0), a(c0, 0), true);
            }
        }

        private static float streak(double an, double t) {
            return (float) Math.pow(clamp01((float) (0.5 + 0.5 * Math.sin(an * 5 + t * 2) * Math.sin(an * 3 - t * 1.3 + 1))), 1.5);
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            Vec3d c = mc.player.getLerpedPos(td);
            float k = ppb(c.add(0, 0.6, 0));
            if (k <= 0) return;
            double R = radius.f(), H = height.f() * 1.4;
            float[] s = new float[2];
            Nvg.additive(true);
            for (int i = 0; i < sparks.i(); i++) {
                double P = (1.3 + hash(i) * 1.3) / speed.f();
                double u = t / P + hash(i + 77);
                int cyc = (int) Math.floor(u);
                float age = (float) (u - cyc);
                int seed = i * 131 + cyc * 7919;
                double ang = hash(seed) * TAU + age * 1.4;
                double rad = R * (0.35 + 0.85 * hash(seed + 1)) * (1 - age * 0.3);
                Vec3d q = c.add(Math.cos(ang) * rad, age * H, Math.sin(ang) * rad);
                if (!Projection.point(q, s)) continue;
                float f = smooth(age / 0.15f) * (float) Math.pow(1 - age, 1.3);
                spark(s[0], s[1], k * 0.016f * (1 - age * 0.5f), pal(color, hash(seed + 2)), f * al);
            }
            Nvg.additive(false);
        }
    }

    /* ------------------------------------------------------------------ star dust */

    static final class StarDust extends PV {
        private final Setting.Num density = num("Density", 1, 0.2, 3, 0.1);
        private final Setting.Num life = num("Life", 1.6, 0.5, 4, 0.1);
        private final Setting.Num size = num("Size", 1, 0.4, 2.5, 0.05);
        private final Setting.Mode color = cols(this, "Gold");
        private final List<double[]> parts = new ArrayList<>(); // x y z vx vy vz age seed
        private final Random rnd = new Random();
        private Vec3d last;
        private double acc;

        StarDust() {
            super("Star Dust", "Twinkling star dust sparkles kicked up as you walk");
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            Vec3d p = mc.player.getLerpedPos(td);
            if (enabled() && last != null) {
                double mv = Math.hypot(p.x - last.x, p.z - last.z);
                if (mv < 2) acc += mv * 9 * density.f();
                Vec3d dir = mv > 1e-4 ? new Vec3d(p.x - last.x, 0, p.z - last.z).multiply(1 / mv) : Vec3d.ZERO;
                while (acc >= 1 && parts.size() < 700) {
                    acc -= 1;
                    double an = rnd.nextDouble() * TAU, rr = Math.sqrt(rnd.nextDouble()) * 0.35;
                    parts.add(new double[]{p.x + Math.cos(an) * rr - dir.x * 0.25, p.y + 0.05 + rnd.nextDouble() * 0.25, p.z + Math.sin(an) * rr - dir.z * 0.25,
                            -dir.x * 0.4 + (rnd.nextDouble() - 0.5) * 0.5, 0.35 + rnd.nextDouble() * 0.45, -dir.z * 0.4 + (rnd.nextDouble() - 0.5) * 0.5, 0, rnd.nextDouble() * 100});
                }
            }
            last = p;
            float L = life.f();
            float[] s = new float[2];
            float k = ppb(p);
            Nvg.additive(true);
            for (Iterator<double[]> it = parts.iterator(); it.hasNext(); ) {
                double[] q = it.next();
                q[6] += dt;
                if (q[6] > L) {
                    it.remove();
                    continue;
                }
                double damp = Math.exp(-dt * 1.6);
                q[3] *= damp;
                q[5] *= damp;
                q[4] = q[4] * Math.exp(-dt * 1.2) - dt * 0.05;
                q[0] += q[3] * dt;
                q[1] += q[4] * dt;
                q[2] += q[5] * dt;
                if (!Projection.point(new Vec3d(q[0], q[1], q[2]), s)) continue;
                float age = (float) (q[6] / L);
                float f = smooth(age / 0.08f) * (float) Math.pow(1 - age, 1.4) * al;
                float tw = (float) (0.5 + 0.5 * Math.sin(t * 7 + q[7]));
                int col = pal(color, (float) (q[7] % 1));
                float px = k * 0.012f * size.f();
                spark(s[0], s[1], px, col, f * (0.6f + 0.4f * tw));
                float len = px * (3 + 4 * tw), rot = (float) (t * 0.8 + q[7]);
                float cx = (float) Math.cos(rot) * len, cy = (float) Math.sin(rot) * len;
                Nvg.line(s[0] - cx, s[1] - cy, s[0] + cx, s[1] + cy, Math.max(0.6f, px * 0.35f), a(Nvg.mix(col, WHITE, 0.6f), 0.75f * f * tw));
                Nvg.line(s[0] + cy, s[1] - cx, s[0] - cy, s[1] + cx, Math.max(0.6f, px * 0.35f), a(Nvg.mix(col, WHITE, 0.6f), 0.75f * f * tw));
            }
            Nvg.additive(false);
        }

        @Override
        void onFadedOut() {
            parts.clear();
            last = null;
        }
    }

    /* ------------------------------------------------------------------ sakura storm */

    static final class Sakura extends PV {
        private final Setting.Num count = num("Petals", 80, 10, 250, 5);
        private final Setting.Num radius = num("Radius", 7, 3, 14, 0.5);
        private final Setting.Num wind = num("Wind", 1, 0, 3, 0.1);
        private final Setting.Num size = num("Size", 1, 0.4, 2.5, 0.05);
        private final Setting.Mode color = m("Color", "Sakura", "Sakura", "White", "Rose", "Autumn", "Gold");
        private final List<double[]> petals = new ArrayList<>(); // x y z seed age
        private final Random rnd = new Random();

        private Setting.Mode m(String n, String d, String... o) {
            return mode(n, d, o);
        }

        Sakura() {
            super("Sakura", "Cherry blossom petals tumbling softly through the air around you");
        }

        private int petalCol(float t) {
            return switch (color.get()) {
                case "Autumn" -> tri(0xFFFFB347, 0xFFFF6A2A, 0xFFC8321E, t);
                case "White" -> Nvg.mix(0xFFFFFFFF, 0xFFE6ECF5, t);
                case "Rose" -> Nvg.mix(0xFFFF4F7E, 0xFFFFA0BA, t);
                case "Gold" -> Nvg.mix(0xFFFFC94A, 0xFFFFF3D2, t);
                default -> Nvg.mix(0xFFFF8FBF, 0xFFFFF0F6, t);
            };
        }

        private void spawn(double[] q, Vec3d c, boolean anyHeight) {
            double an = rnd.nextDouble() * TAU, rr = Math.sqrt(rnd.nextDouble()) * radius.f();
            q[0] = c.x + Math.cos(an) * rr;
            q[1] = c.y + (anyHeight ? -1 + rnd.nextDouble() * 7 : 4 + rnd.nextDouble() * 3);
            q[2] = c.z + Math.sin(an) * rr;
            q[3] = rnd.nextDouble() * 1000;
            q[4] = 0;
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            Vec3d c = mc.player.getLerpedPos(td);
            while (petals.size() < count.i()) {
                double[] q = new double[5];
                spawn(q, c, true);
                petals.add(q);
            }
            while (petals.size() > count.i()) petals.remove(petals.size() - 1);
            double wx = Math.cos(t * 0.13) * 0.7 * wind.f(), wz = Math.sin(t * 0.11 + 1) * 0.7 * wind.f();
            float R = radius.f();
            float[] s = new float[2];
            for (double[] q : petals) {
                double sd = q[3];
                q[4] += dt;
                double fall = 0.35 + (sd % 1) * 0.3;
                q[0] += (wx + Math.sin(t * 1.6 + sd) * 0.45) * dt;
                q[1] -= fall * dt;
                q[2] += (wz + Math.cos(t * 1.3 + sd * 1.7) * 0.45) * dt;
                double dx = q[0] - c.x, dz = q[2] - c.z, hd = Math.sqrt(dx * dx + dz * dz);
                if (q[1] < c.y - 2.5 || hd > R * 1.25) {
                    spawn(q, c, false);
                    if (hd > R * 1.25) q[1] = c.y - 1 + rnd.nextDouble() * 6;
                    continue;
                }
                Vec3d p = new Vec3d(q[0], q[1], q[2]);
                if (!Projection.point(p, s)) continue;
                float px = VisualModules.pixels(p, 0.075 * size.f());
                if (px < 0.4f) continue;
                float edge = 1 - smooth((float) (hd / (R * 1.25) - 0.6f) / 0.4f);
                float f = al * smooth((float) q[4] / 0.8f) * edge * smooth((float) (q[1] - (c.y - 2.5)) / 1.2f);
                if (f <= 0.01f) continue;
                float rot = (float) (t * (0.6 + (sd % 0.7)) + sd);
                float flip = (float) Math.cos(t * (1.5 + (sd % 1.3)) + sd * 3);
                petal(s[0], s[1], px, rot, 0.25f + 0.75f * Math.abs(flip), petalCol((float) ((sd * 0.37) % 1)), f, flip < 0);
            }
        }

        private static final float[] SHAPE = {0, -1, 0.75f, -0.6f, 0.62f, 0.7f, 0.2f, 1, 0, 0.8f, -0.2f, 1, -0.62f, 0.7f, -0.75f, -0.6f, 0, -1};

        private static void petal(float x, float y, float px, float rot, float sx, int col, float al, boolean back) {
            float cs = (float) Math.cos(rot), sn = (float) Math.sin(rot);
            float[] o = new float[SHAPE.length];
            for (int i = 0; i < SHAPE.length; i += 2) {
                float lx = SHAPE[i] * sx * px * 0.7f, ly = SHAPE[i + 1] * px;
                o[i] = x + lx * cs - ly * sn;
                o[i + 1] = y + lx * sn + ly * cs;
            }
            Nvg.additive(true);
            Nvg.glowDisc(x, y, px * 2.2f, a(col, 0.10f * al), a(col, 0));
            Nvg.additive(false);
            Nvg.beginPath();
            Nvg.moveTo(o[0], o[1]);
            Nvg.bezierTo(o[2], o[3], o[4], o[5], o[6], o[7]);
            Nvg.lineTo(o[8], o[9]);
            Nvg.lineTo(o[10], o[11]);
            Nvg.bezierTo(o[12], o[13], o[14], o[15], o[16], o[17]);
            Nvg.closePath();
            int deep = Nvg.mix(col, 0xFFE0507E, back ? 0.35f : 0.2f), light = Nvg.mix(col, WHITE, back ? 0.3f : 0.6f);
            Nvg.fillPathGradient(o[0], o[1], o[8], o[9], a(deep, 0.9f * al), a(light, 0.85f * al));
        }

        @Override
        void onFadedOut() {
            petals.clear();
        }
    }

    /* ------------------------------------------------------------------ ghost trail */

    static final class GhostTrail extends PV {
        private final Setting.Num ghosts = num("Ghosts", 7, 2, 16, 1);
        private final Setting.Num interval = num("Spacing", 0.07, 0.03, 0.2, 0.01);
        private final Setting.Num glow = num("Glow", 1, 0, 2, 0.1);
        private final Setting.Bool others = bool("Other Players", false);
        private final Setting.Mode color = cols(this, "Ice");
        private final Map<Integer, Deque<double[]>> snaps = new HashMap<>(); // x y z yaw time sneak stride
        private final Map<Integer, double[]> walk = new HashMap<>(); // last x z, stride distance, last snap time

        GhostTrail() {
            super("Ghost Trail", "Fading glowing after-image silhouettes trail behind moving players");
        }

        private List<PlayerEntity> who() {
            List<PlayerEntity> l = new ArrayList<>();
            l.add(mc.player);
            if (others.on()) for (AbstractClientPlayerEntity p : mc.world.getPlayers()) if (p != mc.player && p.isAlive()) l.add(p);
            return l;
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            double life = ghosts.i() * interval.f();
            List<Integer> alive = new ArrayList<>();
            for (PlayerEntity p : who()) {
                alive.add(p.getId());
                Vec3d pos = p.getLerpedPos(td);
                double[] w = walk.computeIfAbsent(p.getId(), k -> new double[]{pos.x, pos.z, 0, 0});
                double mv = Math.hypot(pos.x - w[0], pos.z - w[1]);
                if (mv < 3) w[2] += mv;
                w[0] = pos.x;
                w[1] = pos.z;
                Deque<double[]> d = snaps.computeIfAbsent(p.getId(), k -> new ArrayDeque<>());
                double[] lastS = d.peekLast();
                boolean moved = lastS == null || Math.hypot(pos.x - lastS[0], pos.z - lastS[2]) + Math.abs(pos.y - lastS[1]) > 0.12;
                if (enabled() && t - w[3] >= interval.f() && moved) {
                    w[3] = t;
                    d.addLast(new double[]{pos.x, pos.y, pos.z, bodyYaw(p, td), t, p.isInSneakingPose() ? 1 : 0, w[2]});
                }
                while (!d.isEmpty() && t - d.peekFirst()[4] > life) d.removeFirst();
                boolean self = p == mc.player;
                for (double[] g : d) {
                    float age = (float) ((t - g[4]) / life);
                    if (self && !thirdPerson() && t - g[4] < 0.25) continue;
                    float f = (float) Math.pow(1 - age, 1.5) * smooth((float) (t - g[4]) / 0.08f) * al * 0.75f;
                    ghost(g, f, pal(color, age));
                }
            }
            snaps.keySet().retainAll(alive);
            walk.keySet().retainAll(alive);
        }

        private void ghost(double[] g, float f, int col) {
            if (f <= 0.01f) return;
            Vec3d base = new Vec3d(g[0], g[1], g[2]);
            float yaw = (float) g[3];
            Vec3d r = right(yaw);
            boolean sn = g[5] > 0;
            double sc = sn ? 0.86 : 1;
            double sw = Math.sin(g[6] * 2.4) * 0.28;
            Vec3d fw = fwd(yaw);
            float[] head = new float[2], neck = new float[2], hip = new float[2], sl = new float[2], sr = new float[2], hl = new float[2], hr = new float[2],
                    pl = new float[2], pr = new float[2], fl = new float[2], fr = new float[2];
            if (!Projection.point(base.add(0, 1.6 * sc, 0), head) || !Projection.point(base.add(0, 1.38 * sc, 0), neck) || !Projection.point(base.add(0, 0.78 * sc, 0), hip)) return;
            Projection.point(base.add(0, 1.34 * sc, 0).add(r.multiply(0.24)), sl);
            Projection.point(base.add(0, 1.34 * sc, 0).add(r.multiply(-0.24)), sr);
            Projection.point(base.add(0, 0.78 * sc, 0).add(r.multiply(0.3)).add(fw.multiply(-sw * 0.8)), hl);
            Projection.point(base.add(0, 0.78 * sc, 0).add(r.multiply(-0.3)).add(fw.multiply(sw * 0.8)), hr);
            Projection.point(base.add(0, 0.74 * sc, 0).add(r.multiply(0.11)), pl);
            Projection.point(base.add(0, 0.74 * sc, 0).add(r.multiply(-0.11)), pr);
            Projection.point(base.add(0, 0.04, 0).add(r.multiply(0.12)).add(fw.multiply(sw)), fl);
            Projection.point(base.add(0, 0.04, 0).add(r.multiply(-0.12)).add(fw.multiply(-sw)), fr);
            float k = ppb(base.add(0, 0.9, 0));
            float gl = glow.f();
            Nvg.additive(true);
            for (int layer = 0; layer < 3; layer++) {
                float wm = layer == 0 ? 2.6f * gl + 1 : layer == 1 ? 1.5f : 1f;
                float am = layer == 0 ? 0.10f : layer == 1 ? 0.22f : 0.38f;
                int c = layer == 2 ? Nvg.mix(col, WHITE, 0.25f) : col;
                int cc = a(c, am * f);
                if (layer == 0 && gl <= 0) continue;
                Nvg.glowDisc(head[0], head[1], k * 0.2f * wm, cc, a(c, am * f * 0.6f));
                Nvg.line(neck[0], neck[1], hip[0], hip[1], k * 0.36f * wm, cc);
                Nvg.line(sl[0], sl[1], hl[0], hl[1], k * 0.13f * wm, cc);
                Nvg.line(sr[0], sr[1], hr[0], hr[1], k * 0.13f * wm, cc);
                Nvg.line(pl[0], pl[1], fl[0], fl[1], k * 0.16f * wm, cc);
                Nvg.line(pr[0], pr[1], fr[0], fr[1], k * 0.16f * wm, cc);
            }
            Nvg.additive(false);
        }

        @Override
        void onFadedOut() {
            snaps.clear();
            walk.clear();
        }
    }

    /* ------------------------------------------------------------------ rune orbit */

    static final class RuneOrbit extends PV {
        private final Setting.Num count = num("Runes", 6, 3, 12, 1);
        private final Setting.Num radius = num("Radius", 1.05, 0.6, 2, 0.05);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num speed = num("Speed", 1, 0, 3, 0.1);
        private final Setting.Num tilt = num("Tilt", 14, 0, 40, 1);
        private final Setting.Bool path = bool("Orbit Path", true);
        private final Setting.Bool firstPerson = bool("First Person", false);
        private final Setting.Mode color = cols(this, "Purple");
        private static final float[][] RUNES = {
                {-0.4f, 1, -0.4f, -1, -0.4f, 0.2f, 0.5f, 0.75f, -0.4f, -0.25f, 0.5f, 0.3f},
                {-0.4f, 1, -0.4f, -1, -0.4f, 1, 0.4f, 0.5f, 0.4f, 0.5f, -0.4f, 0, -0.4f, 0, 0.45f, -1},
                {0, 1, 0.5f, 0.35f, 0.5f, 0.35f, 0, -0.3f, 0, -0.3f, -0.5f, 0.35f, -0.5f, 0.35f, 0, 1, 0, -0.3f, -0.5f, -1, 0, -0.3f, 0.5f, -1},
                {0, -1, 0, 1, 0, 0.2f, -0.55f, 0.9f, 0, 0.2f, 0.55f, 0.9f},
                {-0.45f, -1, -0.45f, 1, 0.45f, -1, 0.45f, 1, -0.45f, 1, 0.45f, 0.2f, 0.45f, 1, -0.45f, 0.2f},
                {-0.3f, 1, -0.3f, -1, -0.3f, 0.55f, 0.4f, 0, 0.4f, 0, -0.3f, -0.55f},
                {-0.5f, 1, -0.5f, -1, 0.5f, 1, 0.5f, -1, -0.5f, 1, 0.5f, -1, 0.5f, 1, -0.5f, -1},
                {-0.35f, 1, -0.35f, -1, -0.35f, 1, 0.4f, 0.5f, -0.35f, 0.45f, 0.4f, -0.05f},
        };

        RuneOrbit() {
            super("Rune Orbit", "Glowing ancient runes orbiting you on a tilted ring");
        }

        private Vec3d orbit(Vec3d c, double th, double R) {
            double tl = Math.toRadians(tilt.f());
            return c.add(Math.cos(th) * R, Math.sin(th) * Math.sin(tl) * R, Math.sin(th) * R * Math.cos(tl));
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            if (!thirdPerson() && !firstPerson.on()) return;
            Vec3d c = BodyAnchor.of(mc.player, td).center();
            double R = radius.f() * (0.7 + 0.3 * outCubic(al));
            Vec3d cam = Projection.camera();
            double dc = cam.distanceTo(c);
            float[] s = new float[2], s2 = new float[2];
            int n = count.i();
            Nvg.additive(true);
            if (path.on()) {
                int seg = 96;
                for (int i = 0; i < seg; i++) {
                    Vec3d p0 = orbit(c, i * TAU / seg, R), p1 = orbit(c, (i + 1) * TAU / seg, R);
                    if (!Projection.point(p0, s) || !Projection.point(p1, s2)) continue;
                    float fr = clamp01((float) (0.5 + (dc - cam.distanceTo(p0)) / (2 * R)));
                    int col = pal(color, i / (float) seg);
                    Nvg.line(s[0], s[1], s2[0], s2[1], 4f, a(col, 0.05f * al * (0.3f + 0.7f * fr)));
                    Nvg.line(s[0], s[1], s2[0], s2[1], 1.1f, a(col, 0.28f * al * (0.3f + 0.7f * fr)));
                }
            }
            for (int i = 0; i < n; i++) {
                float rv = smooth(al * 1.6f - i / (float) n * 0.6f);
                if (rv <= 0.01f) continue;
                double th = t * speed.f() * 0.55 + i * TAU / n;
                Vec3d p = orbit(c, th, R).add(0, 0.06 * Math.sin(t * 1.5 + i), 0);
                if (cam.distanceTo(p) < 0.5 || !Projection.point(p, s)) continue;
                float fr = clamp01((float) (0.5 + (dc - cam.distanceTo(p)) / (2 * R)));
                float px = VisualModules.pixels(p, 0.16 * size.f()) * (0.85f + 0.15f * fr) * (0.6f + 0.4f * outBack(rv));
                float aa = rv * (0.35f + 0.65f * fr) * (0.85f + 0.15f * (float) Math.sin(t * 3 + i * 2));
                int col = pal(color, i / (float) n);
                Nvg.glowDisc(s[0], s[1], px * 2.4f, a(col, 0.16f * aa), a(col, 0));
                float[] g = RUNES[i % RUNES.length];
                float rot = (float) (0.18 * Math.sin(t * 0.9 + i));
                float cs = (float) Math.cos(rot), sn = (float) Math.sin(rot);
                for (int layer = 0; layer < 3; layer++) {
                    float w = layer == 0 ? px * 0.55f : layer == 1 ? px * 0.24f : Math.max(1f, px * 0.09f);
                    int lc = layer == 2 ? a(Nvg.mix(col, WHITE, 0.75f), 0.95f * aa) : a(col, (layer == 0 ? 0.13f : 0.40f) * aa);
                    for (int j = 0; j + 3 < g.length; j += 4) {
                        float x1 = g[j] * px * 0.62f, y1 = -g[j + 1] * px, x2 = g[j + 2] * px * 0.62f, y2 = -g[j + 3] * px;
                        Nvg.line(s[0] + x1 * cs - y1 * sn, s[1] + x1 * sn + y1 * cs, s[0] + x2 * cs - y2 * sn, s[1] + x2 * sn + y2 * cs, w, lc);
                    }
                }
            }
            Nvg.additive(false);
        }
    }

    /* ------------------------------------------------------------------ crosshair halo */

    static final class CrosshairHalo extends PV {
        private final Setting.Num radius = num("Radius", 7, 3, 16, 0.5);
        private final Setting.Num thickness = num("Thickness", 1.5, 0.6, 4, 0.1);
        private final Setting.Bool dynamic = bool("Dynamic", true);
        private final Setting.Bool cooldown = bool("Cooldown Arc", true);
        private final Setting.Bool dot = bool("Dot", true);
        private final Setting.Mode color = cols(this, "White");
        private float spread, aim, cd = 1, cdVis;

        CrosshairHalo() {
            super("Crosshair Halo", "A smooth reactive crosshair ring with spread, target tint and a cooldown arc");
        }

        private static void arc(float cx, float cy, float r, float a0, float a1, float w, int c) {
            int n = Math.max(2, (int) Math.ceil(Math.abs(a1 - a0) / 0.06f));
            Nvg.beginPath();
            for (int i = 0; i <= n; i++) {
                float an = a0 + (a1 - a0) * i / n;
                float x = cx + (float) Math.cos(an) * r, y = cy + (float) Math.sin(an) * r;
                if (i == 0) Nvg.moveTo(x, y);
                else Nvg.lineTo(x, y);
            }
            Nvg.strokePath(w, c);
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float cx = Nvg.w / 2f, cy = Nvg.h / 2f;
            Vec3d v = mc.player.getVelocity();
            float sp = dynamic.on() ? Math.min(1, (float) Math.hypot(v.x, v.z) * 20 / 7f) + (mc.player.isOnGround() ? 0 : 0.35f) : 0;
            spread += (sp - spread) * ease(dt, 9);
            aim += ((mc.targetedEntity != null ? 1 : 0) - aim) * ease(dt, 12);
            float cdT = mc.player.getAttackCooldownProgress(0);
            cd += (cdT - cd) * ease(dt, 22);
            cdVis += ((cdT < 0.98f ? 1 : 0) - cdVis) * ease(dt, cdT < 0.98f ? 18 : 5);
            float hp = (System.currentTimeMillis() - VisualModules.lastHit) / 380f;
            float pulse = hp >= 0 && hp < 1 ? (1 - hp) * (1 - hp) : 0;
            float r = radius.f() * (1 + spread * 0.6f + pulse * 0.4f) * (0.5f + 0.5f * outBack(al));
            int base = pal(color, 0.15f);
            int col = Nvg.mix(base, 0xFFFF5470, aim * 0.85f);
            float th = thickness.f();
            float rot = (float) (t * 0.5) + spread * 0.8f;
            float gap = 0.26f + 0.18f * spread;
            Nvg.additive(true);
            Nvg.glowDisc(cx, cy, r * 2.2f, a(col, 0.05f * al), a(col, 0));
            for (int k = 0; k < 4; k++) {
                float a0 = rot + k * (float) Math.PI / 2 + gap, a1 = rot + (k + 1) * (float) Math.PI / 2 - gap;
                arc(cx, cy, r, a0, a1, th * 5, a(col, 0.07f * al));
                arc(cx, cy, r, a0, a1, th * 2.6f, a(col, 0.18f * al));
            }
            Nvg.additive(false);
            for (int k = 0; k < 4; k++) {
                float a0 = rot + k * (float) Math.PI / 2 + gap, a1 = rot + (k + 1) * (float) Math.PI / 2 - gap;
                arc(cx, cy, r, a0, a1, th, a(Nvg.mix(col, WHITE, 0.3f), 0.95f * al));
            }
            if (cooldown.on() && cdVis > 0.01f) {
                float cr = r + 4 + th;
                float st = (float) (-Math.PI / 2);
                arc(cx, cy, cr, 0, (float) TAU - 0.001f, th * 0.8f, a(0xFF000000, 0.25f * cdVis * al));
                Nvg.additive(true);
                arc(cx, cy, cr, st, st + (float) TAU * Math.max(0.001f, cd), th * 3.5f, a(col, 0.12f * cdVis * al));
                Nvg.additive(false);
                arc(cx, cy, cr, st, st + (float) TAU * Math.max(0.001f, cd), th * 0.9f, a(Nvg.mix(col, WHITE, 0.4f), 0.9f * cdVis * al));
            }
            if (pulse > 0) {
                Nvg.additive(true);
                Nvg.circleStroke(cx, cy, r + (1 - pulse) * 10, th * 2 * pulse + 0.5f, a(col, 0.5f * pulse * al));
                Nvg.additive(false);
            }
            if (dot.on()) {
                Nvg.additive(true);
                Nvg.glowDisc(cx, cy, 3.2f, a(col, 0.5f * al), a(col, 0));
                Nvg.additive(false);
                Nvg.circle(cx, cy, 0.9f, a(WHITE, al));
            }
        }
    }

    /* ------------------------------------------------------------------ bokeh */

    static final class Bokeh extends PV {
        private final Setting.Num count = num("Orbs", 16, 4, 40, 1);
        private final Setting.Num size = num("Size", 1, 0.4, 2.5, 0.05);
        private final Setting.Num intensity = num("Intensity", 1, 0.2, 2.5, 0.05);
        private final Setting.Bool parallax = bool("Parallax", true);
        private final Setting.Bool clearCenter = bool("Clear Center", true);
        private final Setting.Mode color = cols(this, "Aurora");
        private final List<float[]> orbs = new ArrayList<>(); // x y depth size hue phase vx vy
        private final Random rnd = new Random(7);
        private float lastYaw = Float.NaN, lastPitch, px, py;

        Bokeh() {
            super("Screen Bokeh", "Soft out-of-focus light orbs drifting across the screen with parallax");
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            while (orbs.size() < count.i())
                orbs.add(new float[]{rnd.nextFloat(), rnd.nextFloat(), 0.25f + rnd.nextFloat() * 0.75f, 0.6f + rnd.nextFloat() * 0.8f, rnd.nextFloat(), rnd.nextFloat() * 100,
                        (rnd.nextFloat() - 0.5f) * 0.012f, (rnd.nextFloat() - 0.5f) * 0.008f - 0.004f});
            while (orbs.size() > count.i()) orbs.remove(orbs.size() - 1);
            float yaw = mc.player.getYaw(td), pitch = mc.player.getPitch(td);
            float dy = 0, dp = 0;
            if (!Float.isNaN(lastYaw)) {
                dy = MathHelper.wrapDegrees(yaw - lastYaw);
                dp = pitch - lastPitch;
            }
            lastYaw = yaw;
            lastPitch = pitch;
            // eased parallax velocity so camera flicks glide instead of snapping
            px += ((parallax.on() ? dy : 0) - px) * ease(dt, 14);
            py += ((parallax.on() ? dp : 0) - py) * ease(dt, 14);
            float W = Nvg.w, H = Nvg.h, diag = (float) Math.hypot(W, H) / 2;
            Nvg.additive(true);
            for (float[] o : orbs) {
                o[0] += o[6] * dt - px / 90f * o[2] * 0.35f;
                o[1] += o[7] * dt - py / 90f * o[2] * 0.35f;
                o[0] = ((o[0] + 0.2f) % 1.4f + 1.4f) % 1.4f - 0.2f;
                o[1] = ((o[1] + 0.2f) % 1.4f + 1.4f) % 1.4f - 0.2f;
                float x = o[0] * W, y = o[1] * H;
                float R = size.f() * (14 + 46 * o[2]) * o[3] * (H / 480f);
                float cw = clearCenter.on() ? smooth(((float) Math.hypot(x - W / 2, y - H / 2) / diag - 0.15f) / 0.45f) : 1;
                float breath = (float) (0.6 + 0.4 * Math.sin(t * 0.45 + o[5]));
                float aa = intensity.f() * 0.2f * breath * cw * al * (1.15f - o[2] * 0.4f);
                if (aa <= 0.003f) continue;
                int col = pal(color, (float) ((o[4] + t * 0.02) % 1));
                Nvg.glowDisc(x, y, R * 1.35f, a(col, aa * 0.45f), a(col, 0));
                Nvg.circle(x, y, R * 0.9f, a(col, aa * 0.35f));
                Nvg.circleStroke(x, y, R * 0.88f, Math.max(1, R * 0.06f), a(Nvg.mix(col, WHITE, 0.3f), aa * 0.55f));
            }
            Nvg.additive(false);
        }
    }

    /* ------------------------------------------------------------------ hit shockwave */

    static final class Shockwave extends PV {
        private final Setting.Num size = num("Size", 1, 0.4, 2.5, 0.05);
        private final Setting.Num duration = num("Duration", 0.6, 0.2, 1.5, 0.05);
        private final Setting.Bool chroma = bool("Chromatic", true);
        private final Setting.Bool flash = bool("Flash", true);
        private final Setting.Mode color = cols(this, "White");
        private final List<Object[]> waves = new ArrayList<>(); // Entity, start sec, last pos
        private long seen;

        Shockwave() {
            super("Hit Shockwave", "A refractive chromatic shockwave ring bursts from whatever you hit");
        }

        @Override
        protected void onEnable() {
            seen = VisualModules.lastHit;
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            if (VisualModules.lastHit != seen) {
                seen = VisualModules.lastHit;
                Entity e = VisualModules.lastTarget;
                if (enabled() && e != null) waves.add(new Object[]{e, t, e.getLerpedPos(td).add(0, e.getHeight() * 0.55, 0)});
            }
            float D = duration.f();
            float[] s = new float[2];
            for (Iterator<Object[]> it = waves.iterator(); it.hasNext(); ) {
                Object[] w = it.next();
                float p = (float) ((t - (double) w[1]) / D);
                if (p >= 1) {
                    it.remove();
                    continue;
                }
                Entity e = (Entity) w[0];
                if (!e.isRemoved()) w[2] = e.getLerpedPos(td).add(0, e.getHeight() * 0.55, 0);
                Vec3d c = (Vec3d) w[2];
                if (!Projection.point(c, s)) continue;
                float ez = outCubic(p);
                float R = VisualModules.pixels(c, (0.25 + 1.9 * ez) * size.f());
                float f = (1 - p) * (1 - p) * al;
                float th = Math.max(1, R * 0.10f * (1 - ez) + 1.2f);
                int col = pal(color, p);
                Nvg.additive(true);
                Nvg.circleStroke(s[0], s[1], R, th * 6, a(col, 0.05f * f));
                Nvg.circleStroke(s[0], s[1], R, th * 3, a(col, 0.12f * f));
                if (chroma.on()) {
                    float off = 1.5f + 4 * ez;
                    Nvg.circleStroke(s[0], s[1], R - off, th, a(0xFFFF3050, 0.55f * f));
                    Nvg.circleStroke(s[0], s[1], R, th, a(0xFF40FF80, 0.55f * f));
                    Nvg.circleStroke(s[0], s[1], R + off, th, a(0xFF4070FF, 0.6f * f));
                } else Nvg.circleStroke(s[0], s[1], R, th, a(col, 0.85f * f));
                Nvg.circleStroke(s[0], s[1], R * 0.72f, th * 0.6f, a(col, 0.18f * f));
                if (flash.on()) {
                    float fl = (float) Math.pow(1 - p, 4);
                    Nvg.glowDisc(s[0], s[1], R * 0.6f, a(WHITE, 0.35f * fl * al), a(col, 0));
                }
                Nvg.additive(false);
            }
        }

        @Override
        void onFadedOut() {
            waves.clear();
        }
    }

    /* ------------------------------------------------------------------ cinematic bars */

    static final class CinematicBars extends PV {
        private final Setting.Num ratio = num("Aspect", 2.35, 1.85, 3, 0.05);
        private final Setting.Num softness = num("Softness", 1, 0, 3, 0.1);
        private final Setting.Bool accent = bool("Accent Line", true);
        private final Setting.Mode color = cols(this, "White");

        CinematicBars() {
            super("Cinematic Bars", "Smoothly sliding letterbox bars with soft edges for a film look");
        }

        @Override
        float fadeRate() {
            return 4.5f;
        }

        @Override
        void hud(float td, double t, float dt, float al) {
            float W = Nvg.w, H = Nvg.h;
            float bar = Math.max(0, (H - W / ratio.f()) / 2) * al;
            if (bar < 0.3f) return;
            float soft = 22 * softness.f() * al;
            Nvg.rect(0, 0, W, bar, 0xFF000000);
            Nvg.rect(0, H - bar, W, bar, 0xFF000000);
            if (soft > 0.5f) {
                Nvg.rrectGradient(0, bar, W, soft, 0, 0, bar, 0, bar + soft, a(0xFF000000, 0.55f), a(0xFF000000, 0));
                Nvg.rrectGradient(0, H - bar - soft, W, soft, 0, 0, H - bar, 0, H - bar - soft, a(0xFF000000, 0.55f), a(0xFF000000, 0));
            }
            if (accent.on()) {
                int col = pal(color, (float) (0.5 + 0.5 * Math.sin(t * 0.4)));
                Nvg.additive(true);
                for (float y : new float[]{bar, H - bar}) {
                    Nvg.lineGradient(0, y, W / 2, y, 1, a(col, 0), a(col, 0.45f * al));
                    Nvg.lineGradient(W / 2, y, W, y, 1, a(col, 0.45f * al), a(col, 0));
                    Nvg.lineGradient(W * 0.2f, y, W / 2, y, 5, a(col, 0), a(col, 0.08f * al));
                    Nvg.lineGradient(W / 2, y, W * 0.8f, y, 5, a(col, 0.08f * al), a(col, 0));
                }
                Nvg.additive(false);
            }
        }
    }
}

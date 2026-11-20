package tenacity.modules;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.modules.MoreVisuals.VFx;
import tenacity.modules.PremiumVisuals.PV;
import voided.util.render.Render3D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static tenacity.modules.UltraFx.*;

/**
 * Ultra visuals: ten more premium effects. Body attached effects go through {@link BodyAnchor} (tick-delta interpolated,
 * follows gliding / swimming / sneaking poses); all motion is frame-time based and eased, glow is soft additive light
 * and every module fades in / out on toggle ({@link PV#lingers()}).
 */
final class UltraVisuals {
    private UltraVisuals() {
    }

    static List<Module> create() {
        return List.of(new BlackHole(), new PhoenixWings(), new KitsuneTails(), new FloatingKatana(), new LightningAura(),
                new CometHalo(), new PetalVortex(), new Starfall(), new WaterRipples(), new GlitchAura());
    }

    static final double TAU = Math.PI * 2;

    static float sm(float v) {
        return PremiumVisuals.smooth(v);
    }

    static float h(int i) {
        return VFx.hash(i);
    }

    static int pal(Setting.Mode m, float t) {
        return PremiumVisuals.pal(m, t);
    }

    static Setting.Mode cols(Module m, String def) {
        return PremiumVisuals.cols(m, def);
    }

    /** Base for body attached effects: frame setup, every target's anchor. */
    abstract static class Body extends PV {
        final Setting.Bool others = bool("Other Players", false);

        Body(String n, String d) {
            super(n, d);
        }

        boolean includeSelfFirstPerson() {
            return false;
        }

        @Override
        void world(float td, double t, float dt, float al) {
            VFx.frame();
            List<PlayerEntity> l = targets(others);
            if (includeSelfFirstPerson() && !l.contains(mc.player)) l.add(0, mc.player);
            for (PlayerEntity p : l) body(BodyAnchor.of(p, td), p, t, dt, al);
        }

        abstract void body(BodyAnchor b, PlayerEntity p, double t, float dt, float al);
    }

    /* ================================================================== 1. black hole companion */

    static final class BlackHole extends Body {
        private final Setting.Num size = num("Size", 1, 0.4, 2, 0.05);
        private final Setting.Num speed = num("Speed", 1, 0, 3, 0.05);
        private final Setting.Mode color = cols(this, "Warm");
        private final Map<Integer, Vec3d> pos = new HashMap<>();

        BlackHole() {
            super("Black Hole", "A tiny black hole companion with a glowing accretion disk floats by your shoulder");
        }

        @Override
        void onFadedOut() {
            pos.clear();
        }

        @Override
        void body(BodyAnchor b, PlayerEntity p, double t, float dt, float al) {
            Vec3d target = b.at(0.85, 1.75 + 0.08 * Math.sin(t * 1.3), -0.35);
            Vec3d cur = pos.get(p.getId());
            cur = cur == null || cur.squaredDistanceTo(target) > 64 ? target : cur.lerp(target, PremiumVisuals.ease(dt, 7));
            pos.put(p.getId(), cur);
            double s = size.f() * b.scale * (0.6 + 0.4 * PremiumVisuals.outBack(al));
            double R = 0.42 * s, spin = t * speed.f();
            // disk plane: tilted toward the camera a little, wobbling slowly
            Vec3d n = new Vec3d(Math.sin(t * 0.4) * 0.35, 1, Math.cos(t * 0.33) * 0.35).normalize();
            Vec3d[] uv = basis(n);
            // wide warm glow
            sprite(cur, R * 2.6, pal(color, 0.4f), al * 0.18f, true);
            // spiral arms falling inward (angular speed rises as the radius shrinks)
            int arms = 6;
            for (int k = 0; k < arms; k++) {
                List<Vec3d> pts = new ArrayList<>();
                int segs = 34;
                float[] w = new float[segs + 1];
                int[] cs = new int[segs + 1];
                for (int i = 0; i <= segs; i++) {
                    float f = i / (float) segs; // 0 outer -> 1 inner
                    double r = R * (1.0 - 0.72 * f);
                    double an = k * TAU / arms + spin * 1.2 + f * 3.2 + spin * f * 1.5;
                    pts.add(cur.add(uv[0].multiply(Math.cos(an) * r)).add(uv[1].multiply(Math.sin(an) * r)));
                    w[i] = (float) (0.035 * s * (0.4 + f));
                    cs[i] = a(pal(color, 1 - f), al * 0.55f * sm(f * 4) * (0.5f + 0.5f * f));
                }
                glowRibbon(pts, w, cs, 0.8f, true);
            }
            // infalling sparks
            for (int i = 0; i < 14; i++) {
                float f = (float) ((t * 0.35 * speed.f() + h(i)) % 1);
                double r = R * 1.25 * (1 - f * 0.78);
                double an = h(i + 50) * TAU + spin * 2 + f * 6;
                Vec3d q = cur.add(uv[0].multiply(Math.cos(an) * r)).add(uv[1].multiply(Math.sin(an) * r));
                glow(q, 0.012 * s, pal(color, f), al * sm(f * 5) * sm((1 - f) * 5), true);
            }
            // photon ring + event horizon (the dark disc is normal blended on top of the light)
            rim(cur, R * 0.30, 0.03 * s, pal(color, 0.1f), al * 0.9f, true);
            rim(cur, R * 0.34, 0.06 * s, 0xFFFFFFFF, al * 0.25f, true);
            VFx.sprite(cur, R * 0.27, VFx.a(0xFF000000, al), true);
            VFx.sprite(cur, R * 0.30, VFx.a(0xFF000000, al * 0.6f), true);
        }
    }

    /* ================================================================== 2. phoenix wings */

    static final class PhoenixWings extends Body {
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num flap = num("Flap Speed", 1, 0, 3, 0.05);
        private final Setting.Num embers = num("Embers", 24, 0, 60, 1);
        private final Setting.Mode color = mode("Color", "Fire", "Fire", "Azure", "Violet", "Frost", "Emerald");
        private final Map<Integer, Float> spread = new HashMap<>();

        PhoenixWings() {
            super("Phoenix Wings", "Fiery feathered wings that flap softly and shed embers");
        }

        private int col(float t) {
            t = PremiumVisuals.clamp01(t);
            return switch (color.get()) {
                case "Azure" -> PremiumVisuals.tri(0xFFFFFFFF, 0xFF6EE8FF, 0xFF2D6BFF, t);
                case "Violet" -> PremiumVisuals.tri(0xFFFFFFFF, 0xFFD38CFF, 0xFF7A2DFF, t);
                case "Frost" -> PremiumVisuals.tri(0xFFFFFFFF, 0xFFE6F2FF, 0xFF9CC8F0, t);
                case "Emerald" -> PremiumVisuals.tri(0xFFFFFFFF, 0xFF7DFFB0, 0xFF12B26A, t);
                default -> PremiumVisuals.tri(0xFFFFF6D8, 0xFFFFB13A, 0xFFFF3B1F, t);
            };
        }

        @Override
        void body(BodyAnchor b, PlayerEntity p, double t, float dt, float al) {
            float target = b.flying() ? 1 : (float) Math.min(1, b.speed * 3);
            float sp = spread.getOrDefault(p.getId(), 0f);
            sp += (target - sp) * PremiumVisuals.ease(dt, 3);
            spread.put(p.getId(), sp);
            double s = size.f() * b.scale * (0.5 + 0.5 * PremiumVisuals.outCubic(al));
            double beat = Math.sin(t * (2.2 + sp * 1.8) * flap.f());
            for (int side = -1; side <= 1; side += 2) {
                Vec3d root = b.back(0.8, 0.02).add(b.right.multiply(side * 0.12 * b.scale));
                int F = 12;
                List<Vec3d> prev = null;
                for (int i = 0; i < F; i++) {
                    float fi = i / (float) (F - 1);
                    // feather fan from drooping (outer-low) to raised (upper)
                    double th = Math.toRadians(-35 + 105 * fi + beat * 14 * (0.4 + fi * 0.6) + sp * 8);
                    double fold = 0.55 - 0.35 * sp - beat * 0.08; // swept back when folded
                    Vec3d dir = b.right.multiply(side * Math.cos(th)).add(b.up.multiply(Math.sin(th))).add(b.backDir().multiply(fold)).normalize();
                    double L = s * (0.55 + 0.75 * Math.sin(Math.PI * (0.25 + fi * 0.6))) * (1 - 0.15 * (1 - fi));
                    int segs = 12;
                    List<Vec3d> pts = new ArrayList<>();
                    float[] w = new float[segs + 1];
                    int[] cs = new int[segs + 1];
                    for (int j = 0; j <= segs; j++) {
                        float f = j / (float) segs;
                        double wav = Math.sin(t * 5 + i * 0.7 - f * 4) * 0.05 * f * s;
                        Vec3d q = root.add(dir.multiply(L * f)).add(b.up.multiply(-0.18 * s * f * f + wav)).add(b.backDir().multiply(0.12 * s * f * f));
                        pts.add(q);
                        w[j] = (float) (s * 0.10 * Math.sin(Math.PI * Math.min(1, f * 1.15 + 0.05)) * (0.6 + 0.4 * fi));
                        float flick = 0.85f + 0.15f * (float) Math.sin(t * 9 + i * 1.7 + f * 6);
                        cs[j] = a(col(0.25f + 0.75f * f), al * 0.38f * flick * sm((1 - f) * 6 + 0.15f));
                    }
                    glowRibbon(pts, w, cs, 0.7f, true);
                    // soft membrane between neighbouring feathers
                    if (prev != null) for (int j = 0; j < segs; j++) {
                        float f0 = j / (float) segs, f1 = (j + 1) / (float) segs;
                        int m0 = a(col(0.3f + 0.7f * f0), al * 0.10f * (1 - f0 * 0.6f)), m1 = a(col(0.3f + 0.7f * f1), al * 0.10f * (1 - f1 * 0.6f));
                        q(true, prev.get(j), pts.get(j), pts.get(j + 1), prev.get(j + 1), m0, m0, m1, m1);
                    }
                    prev = pts;
                    // feather tip spark
                    glow(pts.get(segs - 1), 0.02 * s, col(0.8f), al * 0.4f, true);
                }
                // embers rising from the wing
                for (int k = 0; k < embers.i(); k++) {
                    int id = k * 7 + (side > 0 ? 1 : 0) + p.getId() * 131;
                    float life = (float) ((t * 0.5 + h(id)) % 1);
                    double th = Math.toRadians(-20 + 100 * h(id + 3));
                    Vec3d dir = b.right.multiply(side * Math.cos(th)).add(b.up.multiply(Math.sin(th))).add(b.backDir().multiply(0.4)).normalize();
                    Vec3d q = root.add(dir.multiply(s * (0.3 + 0.9 * h(id + 9)))).add(0, life * 0.7 * s, 0)
                            .add(Math.sin(t * 2 + id) * 0.08 * life, 0, Math.cos(t * 1.7 + id) * 0.08 * life);
                    glow(q, 0.010 * s, col(0.4f + life * 0.6f), al * sm(life * 6) * sm((1 - life) * 2), true);
                }
            }
        }
    }

    /* ================================================================== 3. kitsune tails */

    static final class KitsuneTails extends Body {
        private final Setting.Num count = num("Tails", 9, 1, 9, 1);
        private final Setting.Num length = num("Length", 1, 0.5, 2, 0.05);
        private final Setting.Num sway = num("Sway", 1, 0, 2.5, 0.05);
        private final Setting.Mode color = cols(this, "Purple");
        private final Map<Integer, Float> drag = new HashMap<>();

        KitsuneTails() {
            super("Kitsune Tails", "Flowing spirit-fire fox tails that sway and trail behind you");
        }

        @Override
        void body(BodyAnchor b, PlayerEntity p, double t, float dt, float al) {
            float sp = drag.getOrDefault(p.getId(), 0f);
            sp += ((float) Math.min(1, b.speed * 3.5) - sp) * PremiumVisuals.ease(dt, 4);
            drag.put(p.getId(), sp);
            int n = count.i();
            double L = length.f() * b.scale * (0.4 + 0.6 * PremiumVisuals.outCubic(al));
            Vec3d base = b.at(0, 0.78, -0.16);
            for (int i = 0; i < n; i++) {
                float fi = n == 1 ? 0.5f : i / (float) (n - 1);
                double phi = Math.toRadians((fi - 0.5) * (n == 1 ? 0 : 125) * (1 - 0.45 * sp));
                Vec3d dir = b.backDir().multiply(Math.cos(phi)).add(b.right.multiply(Math.sin(phi)));
                double lift = 1.45 - 0.75 * sp - 0.25 * Math.abs(fi - 0.5);
                int segs = 22;
                List<Vec3d> pts = new ArrayList<>();
                float[] w = new float[segs + 1];
                int[] cs = new int[segs + 1];
                for (int j = 0; j <= segs; j++) {
                    float f = j / (float) segs;
                    double ph = t * 1.8 + i * 0.9 - f * 2.6;
                    Vec3d side = dir.crossProduct(b.up).normalize();
                    Vec3d q = base.add(dir.multiply(L * (0.25 + 0.85 * f)))
                            .add(b.up.multiply(L * (lift * f * f * 1.1 - 0.12 * f)))
                            .add(side.multiply(Math.sin(ph) * 0.16 * f * sway.f() * L))
                            .add(b.up.multiply(Math.sin(ph * 0.8 + 1.3) * 0.08 * f * sway.f() * L));
                    pts.add(q);
                    // fluffy body: thin at the root, full in the middle, soft round tip
                    w[j] = (float) (L * 0.15 * Math.pow(Math.sin(Math.PI * Math.min(1, 0.12 + f * 0.95)), 0.6));
                    cs[j] = a(VFx.mix(pal(color, 0.5f + 0.5f * f), 0xFFFFFFFF, f > 0.82f ? (f - 0.82f) * 4 : 0), al * (0.14f + 0.22f * f));
                }
                glowRibbon(pts, w, cs, 0.6f, true);
                glow(pts.get(segs - 1), 0.04 * L, 0xFFFFFFFF, al * 0.45f, true);
                // drifting fox-fire wisps from the tip
                for (int k = 0; k < 3; k++) {
                    float life = (float) ((t * 0.7 + h(i * 13 + k)) % 1);
                    Vec3d q = pts.get(segs).add(0, life * 0.35, 0).add(Math.sin(t + k + i) * 0.05, 0, Math.cos(t * 1.3 + k) * 0.05);
                    glow(q, 0.012 * L, pal(color, 0.8f), al * sm(life * 5) * sm((1 - life) * 2) * 0.8f, true);
                }
            }
        }
    }

    /* ================================================================== 4. floating katana */

    static final class FloatingKatana extends Body {
        private final Setting.Mode mode = mode("Mode", "Hover", "Hover", "Orbit", "Back");
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num speed = num("Speed", 1, 0, 3, 0.05);
        private final Setting.Mode color = cols(this, "Ice");

        FloatingKatana() {
            super("Floating Katana", "A spectral katana with a glowing edge hovers beside you");
        }

        @Override
        void body(BodyAnchor b, PlayerEntity p, double t, float dt, float al) {
            double s = size.f() * b.scale;
            double sp = speed.f();
            Vec3d hilt, dir, flat;
            switch (mode.get()) {
                case "Orbit" -> {
                    double an = t * 1.1 * sp;
                    Vec3d out = b.right.multiply(Math.cos(an)).add(b.fwd.multiply(Math.sin(an)));
                    hilt = b.center().add(out.multiply(0.95 * b.scale)).add(b.up.multiply(0.15 + 0.1 * Math.sin(t * 1.5)));
                    Vec3d tang = b.right.multiply(-Math.sin(an)).add(b.fwd.multiply(Math.cos(an)));
                    dir = tang.multiply(-1).add(b.up.multiply(0.25)).normalize();
                    flat = b.up.crossProduct(dir).normalize();
                }
                case "Back" -> {
                    hilt = b.back(0.95, 0.12).add(b.right.multiply(0.25 * b.scale)).add(b.up.multiply(0.15 * b.scale));
                    dir = b.right.multiply(-0.55).add(b.up.multiply(-1)).normalize();
                    flat = b.backDir();
                }
                default -> {
                    double bob = Math.sin(t * 1.4 * sp) * 0.07;
                    hilt = b.at(0.7, 1.05 + bob, -0.15);
                    double tilt = Math.sin(t * 0.9 * sp) * 0.12;
                    dir = b.up.multiply(1).add(b.right.multiply(0.18 + tilt)).add(b.backDir().multiply(0.15)).normalize();
                    flat = b.fwd.crossProduct(dir).normalize().crossProduct(dir).normalize();
                }
            }
            hilt = hilt.subtract(dir.multiply(0.05 * s * (1 - PremiumVisuals.outBack(al))));
            Vec3d edgeDir = dir.crossProduct(flat).normalize();
            double blade = 0.95 * s, w = 0.045 * s;
            int segs = 16;
            int glowC = pal(color, 0.3f);
            List<Vec3d> spine = new ArrayList<>(), edge = new ArrayList<>(), mid = new ArrayList<>();
            for (int i = 0; i <= segs; i++) {
                float f = i / (float) segs;
                double curve = 0.06 * s * f * f; // sori: gentle curvature
                double wid = w * (f < 0.88 ? 1 - 0.15 * f : (1 - f) / 0.12 * 0.87);
                Vec3d c = hilt.add(dir.multiply(blade * f)).add(edgeDir.multiply(-curve));
                spine.add(c.subtract(edgeDir.multiply(wid * 0.45)));
                edge.add(c.add(edgeDir.multiply(wid * 0.55)));
                mid.add(c);
            }
            // steel: dark spine -> bright frosted edge (normal blend)
            for (int i = 0; i < segs; i++) {
                int sa = VFx.a(0xFF9AA3AE, 0.92f * al), ea = VFx.a(0xFFF4F8FF, 0.95f * al);
                glass(spine.get(i), mid.get(i), mid.get(i + 1), spine.get(i + 1), sa, VFx.a(0xFFD8DEE6, 0.95f * al), VFx.a(0xFFD8DEE6, 0.95f * al), sa, true);
                glass(mid.get(i), edge.get(i), edge.get(i + 1), mid.get(i + 1), VFx.a(0xFFD8DEE6, 0.95f * al), ea, ea, VFx.a(0xFFD8DEE6, 0.95f * al), true);
            }
            // glowing hamon edge
            float[] gw = new float[segs + 1];
            int[] gc = new int[segs + 1];
            for (int i = 0; i <= segs; i++) {
                gw[i] = (float) (0.012 * s);
                gc[i] = a(glowC, al * (0.5f + 0.3f * (float) Math.sin(t * 3 - i * 0.5)));
            }
            glowRibbon(edge, gw, gc, 1f, true);
            // light sweep along the blade
            float sw = (float) ((t * 0.45 * sp) % 1.4);
            if (sw < 1) glow(mid.get(Math.min(segs, Math.round(sw * segs))), 0.03 * s, 0xFFFFFFFF, al * (float) Math.sin(Math.PI * sw), true);
            // tsuba (guard) and handle with wrap diamonds
            Vec3d gc0 = hilt;
            Vec3d[] rim = new Vec3d[12];
            for (int i = 0; i < 12; i++) {
                double an = i * TAU / 12;
                rim[i] = gc0.add(flat.multiply(Math.cos(an) * 0.05 * s)).add(edgeDir.multiply(Math.sin(an) * 0.075 * s));
            }
            EvenMoreVisuals.fan(gc0, rim, VFx.a(0xFF2A2C33, al), VFx.a(0xFF15161A, al), true);
            EvenMoreVisuals.fan(gc0, new Vec3d[]{rim[0], rim[11], rim[10], rim[9], rim[8], rim[7], rim[6], rim[5], rim[4], rim[3], rim[2], rim[1]}, VFx.a(0xFF2A2C33, al), VFx.a(0xFF15161A, al), true);
            for (int i = 0; i < 12; i++) line(rim[i], rim[(i + 1) % 12], 0.004 * s, a(glowC, al * 0.6f), a(glowC, al * 0.6f), true);
            Vec3d hEnd = hilt.subtract(dir.multiply(0.3 * s));
            double hw = 0.026 * s;
            glass(hilt.add(edgeDir.multiply(hw)), hilt.subtract(edgeDir.multiply(hw)), hEnd.subtract(edgeDir.multiply(hw)), hEnd.add(edgeDir.multiply(hw)),
                    VFx.a(0xFF111216, al), VFx.a(0xFF111216, al), VFx.a(0xFF1C1D22, al), VFx.a(0xFF1C1D22, al), true);
            for (int k = 1; k <= 5; k++) {
                Vec3d c = hilt.subtract(dir.multiply(0.3 * s * k / 6.0)).add(flat.multiply(0.002));
                double d = 0.022 * s;
                glass(c.add(dir.multiply(d)), c.add(edgeDir.multiply(d * 0.9)), c.subtract(dir.multiply(d)), c.subtract(edgeDir.multiply(d * 0.9)),
                        VFx.a(0xFFE8ECF2, al), VFx.a(0xFFE8ECF2, al), VFx.a(0xFFE8ECF2, al), VFx.a(0xFFE8ECF2, al), true);
            }
            glow(hEnd, 0.015 * s, glowC, al * 0.7f, true);
            sprite(mid.get(segs / 2), blade * 0.7, glowC, al * 0.06f, true);
        }
    }

    /* ================================================================== 5. lightning aura */

    static final class LightningAura extends Body {
        private final Setting.Num bolts = num("Bolts", 4, 1, 10, 1);
        private final Setting.Num rate = num("Rate", 1, 0.3, 3, 0.05);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Mode color = cols(this, "Ice");

        LightningAura() {
            super("Lightning Aura", "Crackling lightning arcs dance over your body");
        }

        @Override
        void body(BodyAnchor b, PlayerEntity p, double t, float dt, float al) {
            double s = size.f() * b.scale;
            double slotT = t * 9 * rate.f();
            long slot = (long) Math.floor(slotT);
            float frac = (float) (slotT - slot);
            sprite(b.center(), 1.1 * s, pal(color, 0.5f), al * 0.07f, true);
            for (int age = 0; age < 3; age++) {
                float fa = sm(1 - (age + frac) / 3f);
                if (fa <= 0.01f) continue;
                Random r = new Random((slot - age) * 7919 + p.getId() * 31L);
                for (int k = 0; k < bolts.i(); k++) {
                    double a0 = r.nextDouble() * TAU, y0 = 0.1 + r.nextDouble() * 1.7, a1 = a0 + (r.nextDouble() - 0.5) * 1.6;
                    double y1 = Math.max(0, Math.min(1.9, y0 + (r.nextDouble() - 0.5) * 1.4));
                    double r0 = 0.38 + r.nextDouble() * 0.15, r1 = 0.38 + r.nextDouble() * 0.2;
                    Vec3d p0 = b.at(Math.cos(a0) * r0 * size.f(), y0, Math.sin(a0) * r0 * size.f()), p1 = b.at(Math.cos(a1) * r1 * size.f(), y1, Math.sin(a1) * r1 * size.f());
                    List<Vec3d> pts = bolt(p0, p1, 0.45, 4, r);
                    float[] w = new float[pts.size()];
                    int[] cs = new int[pts.size()];
                    for (int j = 0; j < pts.size(); j++) {
                        w[j] = (float) (0.010 * s);
                        cs[j] = a(pal(color, j / (float) pts.size()), al * fa * 0.9f);
                    }
                    glowRibbon(pts, w, cs, 1.2f, true);
                    if (age == 0) {
                        glow(p0, 0.02 * s, pal(color, 0), al * (1 - frac), true);
                        // a short branch
                        int at = pts.size() / 2;
                        Vec3d bp = pts.get(at), bd = pts.get(at).subtract(b.center()).normalize().multiply(0.25 * s);
                        List<Vec3d> br = bolt(bp, bp.add(bd).add(0, (r.nextDouble() - 0.5) * 0.3, 0), 0.5, 3, r);
                        float[] bw = new float[br.size()];
                        int[] bc = new int[br.size()];
                        for (int j = 0; j < br.size(); j++) {
                            bw[j] = (float) (0.006 * s);
                            bc[j] = a(pal(color, 0.6f), al * 0.7f * (1 - j / (float) br.size()));
                        }
                        glowRibbon(br, bw, bc, 1f, true);
                    }
                }
            }
        }
    }

    /* ================================================================== 6. comet halo */

    static final class CometHalo extends Body {
        private final Setting.Num comets = num("Comets", 5, 1, 12, 1);
        private final Setting.Num radius = num("Radius", 0.45, 0.25, 1, 0.01);
        private final Setting.Num speed = num("Speed", 1, 0, 3, 0.05);
        private final Setting.Num tail = num("Tail", 1, 0.3, 2, 0.05);
        private final Setting.Mode color = cols(this, "Aurora");

        CometHalo() {
            super("Comet Halo", "Glowing comets race around a halo above your head");
        }

        @Override
        void body(BodyAnchor b, PlayerEntity p, double t, float dt, float al) {
            Vec3d c = b.headTop(0.22);
            double R = radius.f() * b.scale * (0.6 + 0.4 * PremiumVisuals.outBack(al));
            Vec3d u = b.hRight, v = b.hFwd;
            VFx.ring(c, u, v, R, 0.012 * b.scale, 64, x -> 0xFFFFFFFF, al * 0.45f, 0, true);
            int n = comets.i();
            for (int k = 0; k < n; k++) {
                double sp = (1.4 + 0.5 * h(k)) * speed.f() * (k % 2 == 0 ? 1 : -1);
                double an = t * sp + k * TAU / n;
                double rr = R * (0.88 + 0.24 * h(k + 7));
                double wob = 0.05 * b.scale * Math.sin(t * 2 + k);
                int segs = 22;
                List<Vec3d> pts = new ArrayList<>();
                float[] w = new float[segs + 1];
                int[] cs = new int[segs + 1];
                int col = pal(color, k / (float) Math.max(1, n - 1));
                for (int i = 0; i <= segs; i++) {
                    float f = i / (float) segs; // 0 tail end -> 1 head
                    double a2 = an - Math.signum(sp) * (1 - f) * 1.4 * tail.f();
                    pts.add(c.add(u.multiply(Math.cos(a2) * rr)).add(v.multiply(Math.sin(a2) * rr)).add(b.hUp.multiply(wob * f)));
                    w[i] = (float) (0.03 * b.scale * f);
                    cs[i] = a(col, al * f * f);
                }
                glowRibbon(pts, w, cs, 0.9f, true);
                glow(pts.get(segs), 0.022 * b.scale, col, al, true);
            }
        }
    }

    /* ================================================================== 7. petal vortex */

    static final class PetalVortex extends Body {
        private final Setting.Num count = num("Petals", 40, 10, 120, 1);
        private final Setting.Num speed = num("Speed", 1, 0.2, 3, 0.05);
        private final Setting.Num width = num("Width", 1, 0.5, 2, 0.05);
        private final Setting.Mode color = cols(this, "Sakura");

        PetalVortex() {
            super("Petal Vortex", "Soft petals spiral up around you in a gentle vortex");
        }

        @Override
        void body(BodyAnchor b, PlayerEntity p, double t, float dt, float al) {
            int n = count.i();
            double sc = b.scale;
            for (int i = 0; i < n; i++) {
                float f = (float) ((t * 0.18 * speed.f() + h(i)) % 1);
                double y = f * 2.3;
                double r = (0.5 + 0.35 * f + 0.08 * Math.sin(t + i)) * width.f() * (0.6 + 0.4 * al);
                double an = h(i + 99) * TAU + t * speed.f() * (1.1 + 0.4 * h(i + 3)) + f * 3;
                Vec3d c = b.at(Math.cos(an) * r, y, Math.sin(an) * r);
                float fa = al * sm(f * 6) * sm((1 - f) * 4);
                if (fa < 0.01f) continue;
                // tumbling petal: two triangles forming a teardrop, rotated about a wandering axis
                double ta = t * (2 + h(i + 5) * 2) + i;
                Vec3d ax = new Vec3d(Math.cos(ta), Math.sin(ta * 0.7) * 0.6, Math.sin(ta)).normalize();
                Vec3d[] uv = basis(ax);
                double L = 0.11 * sc, W = 0.07 * sc;
                Vec3d tip = c.add(uv[0].multiply(L)), tail = c.subtract(uv[0].multiply(L * 0.6));
                Vec3d s1 = c.add(uv[1].multiply(W)), s2 = c.subtract(uv[1].multiply(W));
                int col = pal(color, h(i + 11) * 0.7f), core = VFx.mix(col, 0xFFFFFFFF, 0.6f);
                glass(tail, s1, tip, s2, VFx.a(core, 0.9f * fa), VFx.a(col, 0.85f * fa), VFx.a(col, 0.6f * fa), VFx.a(col, 0.85f * fa), true);
                sprite(c, 0.09 * sc, col, fa * 0.18f, true);
            }
            // vortex swirl hint at the feet
            VFx.ring(b.at(0, 0.03, 0), VFx.X, VFx.Z, 0.65 * width.f(), 0.04, 48, x -> pal(color, x), al * 0.25f, (float) t, true);
        }
    }

    /* ================================================================== 8. starfall */

    static final class Starfall extends PV {
        private final Setting.Num count = num("Stars", 14, 3, 40, 1);
        private final Setting.Num radius = num("Radius", 6, 2, 14, 0.5);
        private final Setting.Num speed = num("Speed", 1, 0.3, 3, 0.05);
        private final Setting.Mode color = cols(this, "White");
        private final Map<Integer, Vec3d> anchor = new HashMap<>();
        private final Map<Integer, Long> anchorCyc = new HashMap<>();

        Starfall() {
            super("Starfall", "Shooting stars fall around you and flash softly where they land");
        }

        @Override
        void world(float td, double t, float dt, float al) {
            VFx.frame();
            Vec3d o = mc.player.getLerpedPos(td);
            double R = radius.f();
            for (int i = 0; i < count.i(); i++) {
                double per = 2.4 + h(i) * 1.6;
                double tt = t * speed.f() / per + h(i + 17);
                long cyc = (long) Math.floor(tt);
                float f = (float) (tt - cyc);
                int seed = (int) (cyc * 131 + i * 7);
                double an = h(seed) * TAU, rr = R * (0.35 + 0.65 * Math.sqrt(h(seed + 1)));
                // landing point eases after you instead of snapping: the old per-frame 3-block quantize teleported
                // every meteor each time you crossed a cell border. It snaps only on cycle rollover, when alpha is 0.
                Vec3d target = new Vec3d(Math.floor(o.x / 3) * 3 + Math.cos(an) * rr, o.y, Math.floor(o.z / 3) * 3 + Math.sin(an) * rr);
                Long seen = anchorCyc.get(i);
                Vec3d land;
                if (seen == null || seen != cyc) {
                    anchor.put(i, target);
                    anchorCyc.put(i, cyc);
                    land = target;
                } else {
                    Vec3d a = anchor.get(i);
                    land = a == null ? target : a.lerp(target, Math.min(1f, dt * 3f));
                    anchor.put(i, land);
                }
                Vec3d slant = new Vec3d(Math.cos(an + 1.3) * 1.6, 7, Math.sin(an + 1.3) * 1.6);
                int col = pal(color, h(seed + 2));
                float fall = 0.72f;
                if (f < fall) {
                    float k = f / fall;
                    float e = k * k; // accelerating
                    Vec3d head = land.add(slant.multiply(1 - e));
                    int segs = 12;
                    List<Vec3d> pts = new ArrayList<>();
                    float[] w = new float[segs + 1];
                    int[] cs = new int[segs + 1];
                    double tl = Math.min(1 - e, 0.08 + 0.25 * k);
                    for (int j = 0; j <= segs; j++) {
                        float g = j / (float) segs;
                        pts.add(head.add(slant.multiply(tl * (1 - g))));
                        w[j] = 0.03f * g;
                        cs[j] = a(col, al * g * g * sm(k * 8));
                    }
                    glowRibbon(pts, w, cs, 1f, true);
                    glow(head, 0.035, col, al * sm(k * 8), true);
                } else {
                    float k = (f - fall) / (1 - fall);
                    float e = PremiumVisuals.outCubic(k);
                    Vec3d g = land.add(0, 0.04, 0);
                    VFx.ring(g, VFx.X, VFx.Z, 0.15 + 0.9 * e, 0.06 * (1 - e) + 0.01, 40, x -> col, al * (1 - k) * 0.8f, 0, true);
                    glow(g.add(0, 0.1, 0), 0.08 * (1 - k * 0.5), col, al * (1 - k) * (1 - k), true);
                    for (int s = 0; s < 5; s++) {
                        double sa = s * TAU / 5 + seed;
                        Vec3d q = g.add(Math.cos(sa) * e * 0.6, Math.sin(Math.PI * k) * 0.35, Math.sin(sa) * e * 0.6);
                        glow(q, 0.012, col, al * (1 - k), true);
                    }
                }
            }
        }
    }

    /* ================================================================== 9. water ripples */

    static final class WaterRipples extends Body {
        private final Setting.Num stride = num("Step Distance", 0.6, 0.2, 2, 0.05);
        private final Setting.Num size = num("Size", 1, 0.4, 2.5, 0.05);
        private final Setting.Num life = num("Duration", 1.6, 0.5, 4, 0.1);
        private final Setting.Bool idle = bool("Idle Ripples", true);
        private final Setting.Mode color = cols(this, "White");
        private final Map<Integer, Vec3d> lastStep = new HashMap<>();
        private final Map<Integer, Double> lastIdle = new HashMap<>();
        private final List<double[]> rips = new ArrayList<>(); // x y z start strength

        WaterRipples() {
            super("Water Ripples", "Every step sends soft water ripples across the ground");
        }

        @Override
        boolean includeSelfFirstPerson() {
            return true;
        }

        @Override
        void onFadedOut() {
            rips.clear();
            lastStep.clear();
        }

        @Override
        void world(float td, double t, float dt, float al) {
            super.world(td, t, dt, al);
            Iterator<double[]> it = rips.iterator();
            while (it.hasNext()) {
                double[] r = it.next();
                float k = (float) ((t - r[3]) / life.f());
                if (k >= 1) {
                    it.remove();
                    continue;
                }
                Vec3d c = new Vec3d(r[0], r[1] + 0.02, r[2]);
                for (int ring = 0; ring < 3; ring++) {
                    float kk = k - ring * 0.12f;
                    if (kk <= 0) continue;
                    float e = PremiumVisuals.outCubic(kk / (1 - ring * 0.12f));
                    double R = (0.15 + 1.05 * e) * size.f() * r[4];
                    float fa = al * (1 - e) * sm(kk * 10) * (ring == 0 ? 0.9f : 0.55f);
                    VFx.ring(c, VFx.X, VFx.Z, R, 0.035 * (1 - e * 0.5) * size.f(), 56, x -> pal(color, 0.3f), fa, 0, true);
                }
                // soft caustic shimmer in the middle
                VFx.disc(c, VFx.X, VFx.Z, 0.5 * size.f() * r[4] * (0.5 + k), 28, VFx.a(pal(color, 0.6f), 0.10f * al * (1 - k)), pal(color, 0.6f) & 0xFFFFFF, true);
            }
        }

        @Override
        void body(BodyAnchor b, PlayerEntity p, double t, float dt, float al) {
            if (!enabled()) return;
            Vec3d pos = p.getEntityPos();
            Vec3d last = lastStep.get(p.getId());
            if (!p.isOnGround()) return;
            if (last == null) {
                lastStep.put(p.getId(), pos);
                return;
            }
            if (last.distanceTo(pos) > stride.f() || Math.abs(last.y - pos.y) > 0.6) {
                rips.add(new double[]{pos.x, pos.y, pos.z, t, Math.abs(last.y - pos.y) > 0.6 ? 1.4 : 1});
                lastStep.put(p.getId(), pos);
                lastIdle.put(p.getId(), t);
            } else if (idle.on() && t - lastIdle.getOrDefault(p.getId(), 0.0) > 1.8) {
                rips.add(new double[]{pos.x, pos.y, pos.z, t, 0.7});
                lastIdle.put(p.getId(), t);
            }
            if (rips.size() > 80) rips.remove(0);
        }
    }

    /* ================================================================== 10. glitch aura */

    static final class GlitchAura extends Body {
        private final Setting.Num amount = num("Amount", 1, 0.3, 3, 0.05);
        private final Setting.Num rate = num("Rate", 1, 0.3, 3, 0.05);
        private final Setting.Num split = num("RGB Split", 1, 0, 3, 0.05);
        private final Setting.Mode style = mode("Palette", "RGB", "RGB", "Mono", "Cyber", "Vapor");

        GlitchAura() {
            super("Glitch Aura", "Digital pixel glitches, RGB split and scan bars flicker around you");
        }

        private int[] trio() {
            return switch (style.get()) {
                case "Mono" -> new int[]{0xFFFFFFFF, 0xFFB8BEC8, 0xFF6E7480};
                case "Cyber" -> new int[]{0xFF00F0FF, 0xFFFF2BD6, 0xFFFFF04A};
                case "Vapor" -> new int[]{0xFFFF71CE, 0xFF01CDFE, 0xFFB967FF};
                default -> new int[]{0xFFFF2A3C, 0xFF2AFF7A, 0xFF3A6BFF};
            };
        }

        private void px(Vec3d c, double hw, double hh, int col, float al) {
            if (al <= 0.01f) return;
            Vec3d x = VFx.right.multiply(hw), y = VFx.up.multiply(hh);
            int k = a(col, al * VFx.near(c));
            q(true, c.subtract(x).subtract(y), c.add(x).subtract(y), c.add(x).add(y), c.subtract(x).add(y), k, k, k, k);
        }

        @Override
        void body(BodyAnchor b, PlayerEntity p, double t, float dt, float al) {
            int[] cs = trio();
            double sc = b.scale;
            double st = t * 14 * rate.f();
            long slot = (long) Math.floor(st);
            float frac = (float) (st - slot);
            int n = (int) (22 * amount.f());
            for (int age = 0; age < 2; age++) {
                float fa = age == 0 ? 1 : 1 - sm(frac * 1.5f);
                Random r = new Random((slot - age) * 104729 + p.getId());
                for (int i = 0; i < n; i++) {
                    if (r.nextFloat() > 0.55f) continue;
                    double an = r.nextDouble() * TAU, rr = 0.32 + r.nextDouble() * 0.28, y = r.nextDouble() * 1.9;
                    Vec3d c = b.at(Math.cos(an) * rr, y, Math.sin(an) * rr);
                    double hw = (0.02 + r.nextDouble() * 0.07) * sc, hh = (0.02 + r.nextDouble() * 0.04) * sc;
                    double off = 0.025 * split.f() * sc * (0.5 + r.nextDouble());
                    float k = al * fa * (0.5f + 0.5f * r.nextFloat());
                    px(c.subtract(VFx.right.multiply(off)), hw, hh, cs[0], k * 0.7f);
                    px(c.add(VFx.right.multiply(off)), hw, hh, cs[2], k * 0.7f);
                    px(c, hw * 0.8, hh * 0.8, cs[1], k * 0.55f);
                    px(c, hw * 0.5, hh * 0.4, 0xFFFFFFFF, k * 0.35f);
                }
                // horizontal tear bars
                if (r.nextFloat() < 0.5f * amount.f()) {
                    double y = r.nextDouble() * 1.9;
                    Vec3d c = b.at(0, y, 0).add(VFx.right.multiply((r.nextDouble() - 0.5) * 0.3 * sc));
                    double hw = (0.35 + r.nextDouble() * 0.4) * sc, hh = (0.006 + r.nextDouble() * 0.014) * sc;
                    px(c.subtract(VFx.right.multiply(0.03 * split.f())), hw, hh, cs[0], al * fa * 0.55f);
                    px(c.add(VFx.right.multiply(0.03 * split.f())), hw, hh, cs[2], al * fa * 0.55f);
                    px(c, hw, hh * 0.5, 0xFFFFFFFF, al * fa * 0.5f);
                }
            }
            // faint scanline shell
            for (int i = 0; i < 6; i++) {
                double y = ((t * 0.35 * rate.f() + i / 6.0) % 1) * 1.95;
                VFx.ring(b.at(0, y, 0), b.right, b.fwd, 0.5 * sc, 0.008 * sc, 40, x -> cs[1], al * 0.18f, 0, true);
            }
        }
    }
}

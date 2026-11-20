package tenacity.modules;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.Nvg;
import tenacity.modules.MoreVisuals.Fx;
import tenacity.modules.MoreVisuals.VFx;
import tenacity.util.Projection;
import voided.util.render.Render3D;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Even more premium player visuals. Everything attached to a body goes through {@link BodyAnchor} so it stays on the
 * rendered model (elytra gliding, swimming, crawling, sneaking) and uses tick-delta interpolated poses.
 */
final class EvenMoreVisuals {
    private EvenMoreVisuals() {
    }

    static List<Module> create() {
        return List.of(new Crown(), new DevilHorns(), new NameRing(), new EnergyShield(), new Afterimages(), new Butterflies(), new PetOrb(),
                new Constellation(), new HitHearts(), new DamageNumbers(), new RainbowRoad(), new SakuraPetals(), new ElectricArcs(),
                new SwordGlint(), new JetpackFlames(), new JumpBubbles(), new DragonTail());
    }

    /* ================================================================== shared */

    static Setting.Mode colors(Module m, String def) {
        return m.mode("Color", def, "Gold", "Crimson", "Sakura", "Rainbow", "White", "Purple", "Ice", "Rose", "Chroma");
    }

    static int epal(Setting.Mode m, float t) {
        t = Math.max(0, Math.min(1, t));
        return switch (m.get()) {
            case "Gold" -> VFx.mix(0xFFFFB627, 0xFFFFF2C4, t);
            case "Crimson" -> VFx.mix(0xFFE0102A, 0xFFFF8A4C, t);
            case "Sakura" -> VFx.mix(0xFFFF8FB8, 0xFFFFE6EF, t);
            case "Rainbow" -> VFx.hue((float) (t * 0.8 + VFx.now() * 0.15), 0.6f);
            default -> VisualModules.palette(m, t);
        };
    }

    /** Fan-filled polygon (centre colour -> rim colour). */
    static void fan(Vec3d c, Vec3d[] rim, int cin, int cout, boolean depth) {
        for (int i = 0; i < rim.length; i++) {
            Vec3d a = rim[i], b = rim[(i + 1) % rim.length];
            Render3D.drawGradientQuad(c, c, b, a, cin, cin, cout, cout, depth);
        }
    }

    /** Solid translucent box from a centre and three half-extent vectors. */
    static void box(Vec3d c, Vec3d x, Vec3d y, Vec3d z, int col, int edge, boolean depth) {
        Vec3d[] v = new Vec3d[8];
        for (int i = 0; i < 8; i++) v[i] = c.add(x.multiply((i & 1) == 0 ? -1 : 1)).add(y.multiply((i & 2) == 0 ? -1 : 1)).add(z.multiply((i & 4) == 0 ? -1 : 1));
        int[][] f = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
        for (int[] q : f) Render3D.drawGradientQuad(v[q[0]], v[q[1]], v[q[2]], v[q[3]], col, col, col, col, depth);
        if ((edge >>> 24) > 2) {
            int[][] e = {{0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
            for (int[] q : e) Render3D.drawLineGradient(v[q[0]], v[q[1]], edge, edge, 1.2f, depth);
        }
    }

    /** Jagged bolt by midpoint displacement, in whatever space a / b are given. */
    static List<Vec3d> bolt(Vec3d a, Vec3d b, Random r, double disp, int levels) {
        List<Vec3d> pts = new ArrayList<>(List.of(a, b));
        for (int l = 0; l < levels; l++) {
            List<Vec3d> nx = new ArrayList<>();
            for (int i = 0; i < pts.size() - 1; i++) {
                Vec3d p = pts.get(i), q = pts.get(i + 1);
                nx.add(p);
                nx.add(p.add(q).multiply(0.5).add((r.nextDouble() - 0.5) * disp, (r.nextDouble() - 0.5) * disp, (r.nextDouble() - 0.5) * disp));
            }
            nx.add(pts.get(pts.size() - 1));
            pts = nx;
            disp *= 0.55;
        }
        return pts;
    }

    static void glowLine(List<Vec3d> pts, float width, int col, float alpha, boolean depth) {
        int n = pts.size();
        float[] w = new float[n], wc = new float[n];
        int[] c = new int[n], cc = new int[n];
        for (int i = 0; i < n; i++) {
            float k = (float) Math.sin(Math.PI * Math.min(1, (i + 0.5) / n * 1.0 + 0.02));
            w[i] = width * (0.4f + 0.6f * k);
            wc[i] = w[i] * 0.3f;
            c[i] = VFx.a(col, 0.55f * alpha * k);
            cc[i] = VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.75f), alpha * k);
        }
        VFx.glowRibbon(pts, w, c, VFx.CAMERA, null, 3, 1.4f, 1, depth);
        VFx.ribbon(pts, wc, cc, VFx.CAMERA, null, 0.4f, depth);
    }

    /* ================================================================== 1. crown */

    static final class Crown extends Fx {
        private final Setting.Num size = num("Size", 1, 0.5, 1.8, 0.05);
        private final Setting.Num spikes = num("Spikes", 7, 4, 12, 1);
        private final Setting.Num float_ = num("Float", 0.08, 0, 0.5, 0.01);
        private final Setting.Bool gems = bool("Gems", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = colors(this, "Gold");

        Crown() {
            super("Crown", "A glowing royal crown with sparkling gems sitting on players' heads");
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            for (PlayerEntity p : players(others.on(), false)) {
                BodyAnchor b = BodyAnchor.of(p, td);
                double s = size.f() * b.scale;
                Vec3d base = b.headTop(float_.f() - 0.06 + 0.012 * Math.sin(T * 2 + p.getId()));
                Vec3d u = b.hRight, v = b.hFwd, up = b.hUp;
                double r = 0.25 * s, band = 0.09 * s, spikeH = 0.17 * s;
                int n = spikes.i(), seg = n * 8;
                double rot = T * 0.25;
                // band: two rings joined by a vertical gradient wall
                for (int i = 0; i < seg; i++) {
                    double a0 = i * Math.PI * 2 / seg + rot, a1 = (i + 1) * Math.PI * 2 / seg + rot;
                    Vec3d d0 = u.multiply(Math.cos(a0)).add(v.multiply(Math.sin(a0))), d1 = u.multiply(Math.cos(a1)).add(v.multiply(Math.sin(a1)));
                    Vec3d b0 = base.add(d0.multiply(r)), b1 = base.add(d1.multiply(r));
                    Vec3d t0 = b0.add(up.multiply(band)), t1 = b1.add(up.multiply(band));
                    float light = (float) (0.75 + 0.25 * Math.cos(a0 - T * 1.3));
                    int cb = VFx.a(epal(color, 0.1f), 0.85f * light), ct = VFx.a(epal(color, 0.6f), 0.95f * light);
                    Render3D.drawGradientQuad(b0, b1, t1, t0, cb, cb, ct, ct, true);
                    // spikes: triangle from the band top to a point, every 8 segments
                    int k = i % 8;
                    if (k < 7) {
                        double f0 = k / 7.0, f1 = (k + 1) / 7.0;
                        double h0 = spikeH * Math.pow(Math.sin(Math.PI * f0), 1.6), h1 = spikeH * Math.pow(Math.sin(Math.PI * f1), 1.6);
                        Vec3d s0 = t0.add(up.multiply(h0)), s1 = t1.add(up.multiply(h1));
                        int cs0 = VFx.a(epal(color, (float) (0.6 + 0.4 * h0 / spikeH)), 0.9f * light), cs1 = VFx.a(epal(color, (float) (0.6 + 0.4 * h1 / spikeH)), 0.9f * light);
                        Render3D.drawGradientQuad(t0, t1, s1, s0, ct, ct, cs1, cs0, true);
                    }
                }
                // rim lines and glow
                VFx.Col col = t -> epal(color, 0.7f);
                VFx.ring(base, u, v, r, 0.01, seg, col, 0.9f, (float) rot, true);
                VFx.ring(base.add(up.multiply(band)), u, v, r, 0.01, seg, t -> 0xFFFFFFFF, 0.7f, (float) rot, true);
                VFx.ring(base.add(up.multiply(band * 0.5)), u, v, r, 0.12 * s, seg, col, 0.12f, (float) rot, true);
                for (int i = 0; i < n; i++) {
                    double a = (i + 0.5) * Math.PI * 2 / n + rot;
                    Vec3d d = u.multiply(Math.cos(a)).add(v.multiply(Math.sin(a)));
                    Vec3d tip = base.add(d.multiply(r)).add(up.multiply(band + spikeH * 1.02));
                    VFx.spark(tip, 0.016 * s, epal(color, 1), (float) (0.6 + 0.4 * Math.sin(T * 4 + i * 1.7)), true);
                    if (gems.on()) {
                        int gem = VFx.hue(i / (float) n + (float) T * 0.05f, 0.75f);
                        VFx.spark(base.add(d.multiply(r * 1.02)).add(up.multiply(band * 0.5)), 0.02 * s, gem, 0.95f, true);
                    }
                }
            }
        }
    }

    /* ================================================================== 2. devil horns */

    static final class DevilHorns extends Fx {
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num curl = num("Curl", 1, 0, 2, 0.05);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = colors(this, "Crimson");

        DevilHorns() {
            super("Devil Horns", "Curved glowing horns that follow your head");
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            for (PlayerEntity p : players(others.on(), false)) {
                BodyAnchor b = BodyAnchor.of(p, td);
                double s = size.f() * b.scale;
                for (int sg = -1; sg <= 1; sg += 2) {
                    Vec3d root = b.head(0.16 * sg, BodyAnchor.HEAD - 0.04, 0.06);
                    int N = 18;
                    List<Vec3d> pts = new ArrayList<>();
                    float[] w = new float[N];
                    int[] c = new int[N], hl = new int[N];
                    float[] wh = new float[N];
                    for (int i = 0; i < N; i++) {
                        double t = i / (double) (N - 1);
                        double L = 0.38 * s;
                        // out, up, then curling back and inwards at the tip
                        double out = Math.sin(t * 1.4) * 0.45 * L;
                        double upv = (t * 0.9 - 0.25 * t * t * curl.f()) * L;
                        double backv = -t * t * 0.55 * L * curl.f();
                        Vec3d q = root.add(b.hRight.multiply(out * sg)).add(b.hUp.multiply(upv)).add(b.hFwd.multiply(backv + 0.12 * L * t));
                        pts.add(q);
                        w[i] = (float) (0.055 * s * Math.pow(1 - t, 0.9) + 0.002);
                        wh[i] = w[i] * 0.3f;
                        float shade = (float) (0.35 + 0.65 * t);
                        c[i] = VFx.a(epal(color, (float) t), 0.95f);
                        c[i] = VFx.a(VFx.mix(0xFF2A0508, epal(color, (float) t), shade), 0.95f);
                        hl[i] = VFx.a(VFx.mix(epal(color, 1), 0xFFFFFFFF, 0.5f), (float) (0.6 * (0.5 + 0.5 * Math.sin(t * 6 - T * 3))));
                    }
                    if (glow.on()) VFx.glowRibbon(pts, w, java.util.Arrays.stream(c).map(x -> VFx.am(x, 0.5f)).toArray(), VFx.CAMERA, null, 3, 1.3f, 0.8f, true);
                    VFx.ribbon(pts, w, c, VFx.CAMERA, null, 0.85f, true);
                    VFx.ribbon(pts, wh, hl, VFx.CAMERA, null, 0.3f, true);
                    VFx.spark(pts.get(N - 1), 0.012 * s, epal(color, 1), (float) (0.6 + 0.4 * Math.sin(T * 5 + sg)), true);
                }
            }
        }
    }

    /* ================================================================== 3. floating name ring */

    static final class NameRing extends Fx {
        private final Setting.Num radius = num("Radius", 0.55, 0.3, 1.2, 0.05);
        private final Setting.Num height = num("Height", 0.45, 0.1, 1, 0.05);
        private final Setting.Num spin = num("Spin", 0.5, 0, 2, 0.05);
        private final Setting.Num textSize = num("Text Size", 1, 0.5, 2, 0.05);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = colors(this, "White");

        NameRing() {
            super("Floating Name Ring", "Players' names orbit their heads as a rotating ring of letters");
        }

        @Override
        public void render2d(float td) {
            frame();
            double T = VFx.now();
            float[] o = new float[2];
            for (PlayerEntity p : players(others.on(), false)) {
                BodyAnchor b = BodyAnchor.of(p, td);
                Vec3d c = b.headTop(height.f() + 0.03 * Math.sin(T * 1.6 + p.getId()));
                String label = p.getName().getString().toUpperCase() + "  \u2022  ";
                String s = label + label;
                int n = s.length();
                double R = radius.f() * b.scale * Math.max(1, n / 26.0);
                double rot = T * spin.f();
                for (int i = 0; i < n; i++) {
                    char ch = s.charAt(i);
                    if (ch == ' ') continue;
                    double a = -i * Math.PI * 2 / n + rot;
                    Vec3d d = b.hRight.multiply(Math.cos(a)).add(b.hFwd.multiply(Math.sin(a)));
                    Vec3d q = c.add(d.multiply(R));
                    if (!Projection.point(q, o)) continue;
                    double dist = q.distanceTo(VFx.cam);
                    // letters on the far side of the ring fade back
                    float front = (float) (0.5 - 0.5 * d.dotProduct(q.subtract(VFx.cam).normalize()));
                    float al = (0.25f + 0.75f * front) * VFx.near(q);
                    float sz = (float) MathHelper.clamp(95 * textSize.f() / Math.max(0.6, dist), 8, 48) * (0.85f + 0.15f * front);
                    int col = epal(color, i / (float) n);
                    Nvg.textBlur(String.valueOf(ch), o[0], o[1], sz, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(col, 0.45f * al), 6);
                    Nvg.text(String.valueOf(ch), o[0], o[1], sz, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(col, al));
                }
            }
        }
    }

    /* ================================================================== 4. energy shield on hit */

    static final class EnergyShield extends Fx {
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num duration = num("Duration", 0.8, 0.3, 2, 0.05);
        private final Setting.Bool onSelf = bool("When You Get Hit", true);
        private final Setting.Mode color = colors(this, "Ice");
        private final List<Object[]> hits = new ArrayList<>();
        private int lastHurt;

        EnergyShield() {
            super("Energy Shield", "A hexagonal energy shield flares up where a hit lands");
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double now = VFx.now();
            Entity e = newHit();
            if (e instanceof LivingEntity) hits.add(new Object[]{e, mc.player.getEyePos(), now});
            if (onSelf.on() && mc.player.hurtTime > lastHurt) {
                Entity src = mc.player.getAttacker();
                Vec3d from = src != null ? src.getEyePos() : mc.player.getEyePos().add(mc.player.getRotationVec(1).multiply(2));
                hits.add(new Object[]{mc.player, from, now});
            }
            lastHurt = mc.player.hurtTime;
            hits.removeIf(h -> now - (double) h[2] > duration.f() || ((Entity) h[0]).isRemoved());
            for (Object[] h : hits) {
                LivingEntity le = (LivingEntity) h[0];
                if (le == mc.player && mc.options.getPerspective().isFirstPerson()) continue;
                draw(BodyAnchor.of(le, td), (Vec3d) h[1], (float) ((now - (double) h[2]) / duration.f()));
            }
        }

        private Vec3d map(Vec3d c, Vec3d f, Vec3d sx, Vec3d sy, double R, double x, double y) {
            return c.add(f.multiply(R).add(sx.multiply(x)).add(sy.multiply(y)).normalize().multiply(R));
        }

        private void draw(BodyAnchor b, Vec3d from, float k) {
            Vec3d c = b.center();
            Vec3d f = from.subtract(c);
            f = f.lengthSquared() < 1e-6 ? b.fwd : f.normalize();
            Vec3d sx = f.crossProduct(VFx.UP);
            sx = sx.lengthSquared() < 1e-6 ? VFx.X : sx.normalize();
            Vec3d sy = sx.crossProduct(f).normalize();
            double R = 0.85 * size.f() * b.scale * (0.92 + 0.08 * VFx.outCubic(k * 3));
            double hs = 0.11 * size.f() * b.scale;
            float fade = VFx.smooth(k * 8) * VFx.smooth((1 - k) * 2.2f);
            double wave = k * 1.4;
            int rings = 5;
            for (int q = -rings; q <= rings; q++)
                for (int r = -rings; r <= rings; r++) {
                    int s = -q - r;
                    if (Math.abs(s) > rings) continue;
                    double x = hs * Math.sqrt(3) * (q + r / 2.0), y = hs * 1.5 * r;
                    double dn = Math.hypot(x, y) / (hs * rings * 1.6);
                    if (dn > 1) continue;
                    float ripple = (float) Math.exp(-Math.pow((dn - wave) * 7, 2));
                    float base = (float) (1 - dn) * 0.35f;
                    float al = (base + ripple) * fade;
                    if (al < 0.02f) continue;
                    int col = epal(color, (float) dn);
                    Vec3d cen = map(c, f, sx, sy, R, x, y);
                    Vec3d[] rim = new Vec3d[6];
                    for (int i = 0; i < 6; i++) {
                        double an = Math.PI / 6 + i * Math.PI / 3;
                        rim[i] = map(c, f, sx, sy, R, x + Math.cos(an) * hs * 0.92, y + Math.sin(an) * hs * 0.92);
                    }
                    fan(cen, rim, VFx.a(col, 0.06f * al), VFx.a(col, 0.22f * al), true);
                    for (int i = 0; i < 6; i++) Render3D.drawLineGradient(rim[i], rim[(i + 1) % 6], VFx.a(VFx.mix(col, 0xFFFFFFFF, ripple * 0.6f), 0.8f * al), VFx.a(VFx.mix(col, 0xFFFFFFFF, ripple * 0.6f), 0.8f * al), 1.3f, true);
                }
            VFx.spark(map(c, f, sx, sy, R, 0, 0), 0.06 * b.scale, epal(color, 0), fade * (1 - k), true);
        }
    }

    /* ================================================================== 5. afterimages */

    static final class Afterimages extends Fx {
        private final Setting.Num count = num("Copies", 6, 2, 14, 1);
        private final Setting.Num spacing = num("Spacing", 0.06, 0.03, 0.2, 0.01);
        private final Setting.Num opacity = num("Opacity", 0.35, 0.1, 0.8, 0.05);
        private final Setting.Bool always = bool("Always (not only sprint)", false);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = colors(this, "Ice");
        /** id -> snapshots {anchor, swing, time}. */
        private final Map<Integer, ArrayDeque<Object[]>> snaps = new HashMap<>();
        private final Map<Integer, Double> lastSnap = new HashMap<>();

        Afterimages() {
            super("Afterimages", "Fading holographic ghost copies trail behind you while sprinting");
        }

        @Override
        protected void onDisable() {
            snaps.clear();
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double now = VFx.now();
            double life = count.i() * spacing.f();
            Set<Integer> seen = new HashSet<>();
            List<PlayerEntity> list = new ArrayList<>(players(others.on(), true));
            for (PlayerEntity p : list) {
                seen.add(p.getId());
                ArrayDeque<Object[]> q = snaps.computeIfAbsent(p.getId(), k -> new ArrayDeque<>());
                boolean on = (always.on() || p.isSprinting() || p.isGliding()) && p.getVelocity().horizontalLength() > 0.05;
                if (on && now - lastSnap.getOrDefault(p.getId(), 0.0) >= spacing.f()) {
                    lastSnap.put(p.getId(), now);
                    float swing = (float) (Math.sin(now * 13) * 0.75);
                    q.addLast(new Object[]{BodyAnchor.of(p, td), swing, now});
                }
                while (!q.isEmpty() && now - (double) q.peekFirst()[2] > life) q.pollFirst();
                for (Object[] s : q) {
                    float age = (float) ((now - (double) s[2]) / life);
                    float al = opacity.f() * (1 - age) * (1 - age) * VFx.smooth(age * 6);
                    if (al < 0.01f) continue;
                    ghost((BodyAnchor) s[0], (float) s[1], epal(color, age), al);
                }
            }
            snaps.keySet().retainAll(seen);
        }

        private void ghost(BodyAnchor b, float swing, int col, float al) {
            double sc = b.scale;
            int fill = VFx.a(col, 0.35f * al), edge = VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.4f), 0.8f * al);
            Vec3d R = b.right, U = b.up, F = b.fwd;
            box(b.at(0, 1.03, 0), R.multiply(0.25 * sc), U.multiply(0.35 * sc), F.multiply(0.125 * sc), fill, edge, true);
            box(b.head(0, 0.24, 0), b.hRight.multiply(0.24 * sc), b.hUp.multiply(0.24 * sc), b.hFwd.multiply(0.24 * sc), fill, edge, true);
            for (int s = -1; s <= 1; s += 2) {
                double sw = swing * s;
                Vec3d legDir = U.multiply(-Math.cos(sw)).add(F.multiply(Math.sin(sw)));
                Vec3d hip = b.at(0.12 * s, 0.7, 0);
                box(hip.add(legDir.multiply(0.35 * sc)), R.multiply(0.12 * sc), legDir.multiply(0.35 * sc), legDir.crossProduct(R).multiply(0.12 * sc), fill, edge, true);
                Vec3d armDir = U.multiply(-Math.cos(-sw * 0.8)).add(F.multiply(Math.sin(-sw * 0.8)));
                Vec3d sh = b.at(0.36 * s, 1.33, 0);
                box(sh.add(armDir.multiply(0.33 * sc)), R.multiply(0.11 * sc), armDir.multiply(0.33 * sc), armDir.crossProduct(R).multiply(0.11 * sc), fill, edge, true);
            }
        }
    }

    /* ================================================================== 6. butterflies */

    static final class Butterflies extends Fx {
        private final Setting.Num count = num("Butterflies", 3, 1, 8, 1);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num range = num("Range", 1.1, 0.6, 2.5, 0.05);
        private final Setting.Bool others = bool("Other Players", false);
        private final Setting.Mode color = colors(this, "Rainbow");

        Butterflies() {
            super("Butterflies", "Glowing butterflies flutter around you");
        }

        /** Smooth wandering path around the body (local coordinates). */
        private Vec3d path(int i, double t, double R) {
            double a = t * (0.55 + 0.2 * VFx.hash(i * 7)) + i * 2.1;
            double r = R * (0.8 + 0.25 * Math.sin(t * 0.7 + i));
            double y = 1.0 + 0.55 * Math.sin(t * 0.9 + i * 1.3) + 0.2 * Math.sin(t * 2.3 + i);
            return new Vec3d(Math.cos(a) * r, y, Math.sin(a) * r);
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            for (PlayerEntity p : players(others.on(), true)) {
                BodyAnchor b = BodyAnchor.of(p, td);
                for (int i = 0; i < count.i(); i++) {
                    double t = T + i * 3.3;
                    Vec3d lp = path(i, t, range.f()), lq = path(i, t + 0.05, range.f());
                    Vec3d pos = b.at(lp.x, lp.y, lp.z);
                    if (p == mc.player && mc.options.getPerspective().isFirstPerson() && pos.distanceTo(VFx.cam) < 0.6) continue;
                    Vec3d head = b.at(lq.x, lq.y, lq.z).subtract(pos);
                    head = head.lengthSquared() < 1e-8 ? b.fwd : head.normalize();
                    Vec3d side = head.crossProduct(VFx.UP);
                    side = side.lengthSquared() < 1e-6 ? VFx.X : side.normalize();
                    Vec3d up = side.crossProduct(head).normalize();
                    double s = 0.13 * size.f();
                    // wings beat fast, eased through the top / bottom
                    double flap = Math.toRadians(10 + 65 * VFx.smooth((float) (0.5 + 0.5 * Math.sin(T * 17 + i * 2))));
                    int c1 = epal(color, VFx.hash(i * 31)), c2 = epal(color, (VFx.hash(i * 31) + 0.35f) % 1);
                    for (int sg = -1; sg <= 1; sg += 2) {
                        Vec3d out = side.multiply(sg * Math.cos(flap)).add(up.multiply(Math.sin(flap)));
                        wing(pos.add(head.multiply(s * 0.15)), head.multiply(s * 0.55), out.multiply(s * 0.95), c1, c2);
                        wing(pos.add(head.multiply(-s * 0.15)), head.multiply(-s * 0.35), out.multiply(s * 0.65), c2, c1);
                    }
                    Render3D.drawLineGradient(pos.add(head.multiply(-s * 0.4)), pos.add(head.multiply(s * 0.4)), 0xE0FFFFFF, 0xE0FFFFFF, 2f, true);
                    VFx.spark(pos, 0.03 * size.f(), c1, 0.7f, true);
                    // a short sparkle trail
                    for (int k = 1; k <= 5; k++) {
                        Vec3d lt = path(i, t - k * 0.08, range.f());
                        VFx.spark(b.at(lt.x, lt.y, lt.z), 0.01 * size.f(), c2, 0.5f * (1 - k / 6f), true);
                    }
                }
            }
        }

        private void wing(Vec3d root, Vec3d along, Vec3d out, int cin, int cout) {
            int seg = 14;
            Vec3d[] rim = new Vec3d[seg];
            for (int i = 0; i < seg; i++) {
                double a = i * Math.PI / (seg - 1);
                // rounded lobe from the body outwards
                rim[i] = root.add(along.multiply(Math.cos(a) * 0.6 + 0.2)).add(out.multiply(Math.sin(a) * (0.9 + 0.1 * Math.cos(a))));
            }
            Vec3d c = root.add(out.multiply(0.45)).add(along.multiply(0.2));
            for (int i = 0; i < seg - 1; i++)
                Render3D.drawGradientQuad(c, c, rim[i + 1], rim[i], VFx.a(VFx.mix(cin, 0xFFFFFFFF, 0.3f), 0.75f), VFx.a(VFx.mix(cin, 0xFFFFFFFF, 0.3f), 0.75f), VFx.a(cout, 0.9f), VFx.a(cout, 0.9f), true);
            Render3D.drawGradientQuad(c, c, rim[0], root, VFx.a(cin, 0.75f), VFx.a(cin, 0.75f), VFx.a(cout, 0.9f), VFx.a(cin, 0.6f), true);
            Render3D.drawGradientQuad(c, c, root, rim[seg - 1], VFx.a(cin, 0.75f), VFx.a(cin, 0.75f), VFx.a(cin, 0.6f), VFx.a(cout, 0.9f), true);
        }
    }

    /* ================================================================== 7. pet orb */

    static final class PetOrb extends Fx {
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Num follow = num("Follow Speed", 4, 1, 10, 0.5);
        private final Setting.Mode color = colors(this, "Ice");
        private Vec3d pos;
        private double nextBlink;
        private Vec3d lookSm = VFx.Z;

        PetOrb() {
            super("Pet Orb", "A cute floating orb companion with eyes that follows you and looks around");
        }

        @Override
        protected void onEnable() {
            super.onEnable();
            pos = null;
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            BodyAnchor b = BodyAnchor.of(mc.player, td);
            Vec3d target = b.at(-0.6, 1.85 + 0.08 * Math.sin(T * 1.8), -0.25);
            if (pos == null || pos.squaredDistanceTo(target) > 100) pos = target;
            pos = pos.add(target.subtract(pos).multiply(1 - Math.exp(-follow.f() * dt)));
            double R = 0.16 * size.f();
            int col = epal(color, 0.2f);
            // look at your target when fighting, else where you look
            Entity t = VisualModules.lastTarget;
            Vec3d want = t != null && t.isAlive() && System.currentTimeMillis() - VisualModules.lastHit < 4000
                    ? t.getBoundingBox().getCenter().subtract(pos).normalize() : mc.player.getRotationVec(td);
            lookSm = lookSm.add(want.subtract(lookSm).multiply(1 - Math.exp(-6 * dt))).normalize();
            // body: glow layers, rim light, hot core
            VFx.sprite(pos, R * 3.2, VFx.a(col, 0.10f), true);
            VFx.sprite(pos, R * 1.6, VFx.a(col, 0.35f), true);
            VFx.sprite(pos, R * 1.05, VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.55f), 0.95f), true);
            VFx.ring(pos, VFx.right, VFx.up, R * 1.02, 0.02 * size.f(), 40, x -> col, 0.8f, 0, true);
            // eyes on the side of the orb facing the look direction (only when that side faces the camera a bit)
            if (T > nextBlink + 0.14) nextBlink = T + 2 + VFx.hash((int) T) * 3;
            float open = T > nextBlink ? (float) Math.abs(Math.cos((T - nextBlink) / 0.14 * Math.PI)) : 1;
            Vec3d face = pos.add(lookSm.multiply(R * 0.45)).add(VFx.cam.subtract(pos).normalize().multiply(R * 0.5));
            Vec3d sx = VFx.right, sy = VFx.up;
            for (int s = -1; s <= 1; s += 2) {
                Vec3d e = face.add(sx.multiply(s * R * 0.36)).add(sy.multiply(R * 0.1));
                VFx.disc(e, sx, sy.multiply(Math.max(0.12, open)), R * 0.17, 16, 0xFF15171C, 0xF015171C, true);
                Vec3d hl = e.add(sx.multiply(R * 0.05)).add(sy.multiply(R * 0.06 * open));
                VFx.disc(hl, sx, sy.multiply(Math.max(0.12, open)), R * 0.055, 10, 0xFFFFFFFF, 0xC0FFFFFF, true);
            }
            // a few orbiting motes
            for (int i = 0; i < 3; i++) {
                double a = T * 2.4 + i * Math.PI * 2 / 3;
                VFx.spark(pos.add(Math.cos(a) * R * 1.9, Math.sin(T * 1.7 + i) * R * 0.6, Math.sin(a) * R * 1.9), 0.012 * size.f(), col, 0.8f, true);
            }
        }
    }

    /* ================================================================== 8. constellation */

    static final class Constellation extends Fx {
        private final Setting.Num range = num("Range", 16, 4, 48, 1);
        private final Setting.Num links = num("Links", 2, 1, 4, 1);
        private final Setting.Mode color = colors(this, "Purple");

        Constellation() {
            super("Constellation", "Starlit lines connect nearby players like a constellation");
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            List<Vec3d> stars = new ArrayList<>();
            List<PlayerEntity> ps = new ArrayList<>();
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (!p.isAlive() || p.isInvisible() || p.distanceTo(mc.player) > range.f()) continue;
                if (p == mc.player && mc.options.getPerspective().isFirstPerson()) continue;
                ps.add(p);
                stars.add(BodyAnchor.of(p, td).headTop(0.35));
            }
            int n = stars.size();
            Set<Long> done = new HashSet<>();
            for (int i = 0; i < n; i++) {
                Integer[] idx = new Integer[n];
                for (int j = 0; j < n; j++) idx[j] = j;
                final int fi = i;
                java.util.Arrays.sort(idx, (x, y) -> Double.compare(stars.get(fi).squaredDistanceTo(stars.get(x)), stars.get(fi).squaredDistanceTo(stars.get(y))));
                for (int k = 1; k <= Math.min(links.i(), n - 1); k++) {
                    int j = idx[k];
                    long key = Math.min(i, j) * 100000L + Math.max(i, j);
                    if (!done.add(key)) continue;
                    Vec3d a = stars.get(i), c = stars.get(j);
                    int seg = 24;
                    List<Vec3d> pts = new ArrayList<>();
                    float[] w = new float[seg + 1];
                    int[] cs = new int[seg + 1];
                    for (int s = 0; s <= seg; s++) {
                        double t = s / (double) seg;
                        pts.add(a.lerp(c, t).add(0, Math.sin(Math.PI * t) * 0.25, 0));
                        float pulse = (float) Math.pow(0.5 + 0.5 * Math.sin(t * 8 - T * 3 + i), 6);
                        w[s] = 0.012f + 0.02f * pulse;
                        cs[s] = VFx.a(VFx.mix(epal(color, (float) t), 0xFFFFFFFF, pulse * 0.6f), 0.35f + 0.55f * pulse);
                    }
                    VFx.glowRibbon(pts, w, cs, VFx.CAMERA, null, 2, 2f, 0.6f, true);
                    VFx.ribbon(pts, w, cs, VFx.CAMERA, null, 0.3f, true);
                }
            }
            for (int i = 0; i < n; i++) {
                Vec3d s = stars.get(i);
                float tw = (float) (0.7 + 0.3 * Math.sin(T * 3 + i * 2));
                VFx.spark(s, 0.04, epal(color, 0.3f), tw, true);
                // four point star flare
                double L = 0.18 * tw;
                Render3D.drawLineGradient(s.add(VFx.up.multiply(L)), s, 0x00FFFFFF, VFx.a(0xFFFFFFFF, 0.8f * tw), 1.5f, true);
                Render3D.drawLineGradient(s, s.add(VFx.up.multiply(-L)), VFx.a(0xFFFFFFFF, 0.8f * tw), 0x00FFFFFF, 1.5f, true);
                Render3D.drawLineGradient(s.add(VFx.right.multiply(L)), s, 0x00FFFFFF, VFx.a(0xFFFFFFFF, 0.8f * tw), 1.5f, true);
                Render3D.drawLineGradient(s, s.add(VFx.right.multiply(-L)), VFx.a(0xFFFFFFFF, 0.8f * tw), 0x00FFFFFF, 1.5f, true);
            }
        }
    }

    /* ================================================================== 9. hit hearts */

    static final class HitHearts extends Fx {
        private final Setting.Num count = num("Hearts", 6, 2, 16, 1);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Mode color = colors(this, "Sakura");
        /** {pos, vel, born, size, seed}. */
        private final List<Object[]> hearts = new ArrayList<>();
        private final Random rng = new Random();

        HitHearts() {
            super("Hit Hearts", "Soft glowing hearts pop out of whatever you hit");
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double now = VFx.now();
            Entity e = newHit();
            if (e != null) {
                Vec3d c = e instanceof LivingEntity le ? BodyAnchor.of(le, td).center() : e.getBoundingBox().getCenter();
                for (int i = 0; i < count.i(); i++) {
                    double a = rng.nextDouble() * Math.PI * 2;
                    hearts.add(new Object[]{c.add(Math.cos(a) * 0.2, rng.nextDouble() * 0.3, Math.sin(a) * 0.2),
                            new Vec3d(Math.cos(a) * 1.1, 1.6 + rng.nextDouble() * 1.4, Math.sin(a) * 1.1), now, 0.7 + rng.nextDouble() * 0.6, rng.nextInt(1000)});
                }
            }
            hearts.removeIf(h -> now - (double) h[2] > 1.4);
            for (Object[] h : hearts) {
                float age = (float) (now - (double) h[2]);
                Vec3d v = (Vec3d) h[1];
                // drag + gentle float
                double damp = Math.exp(-3 * dt);
                v = new Vec3d(v.x * damp, v.y * damp + 0.6 * dt, v.z * damp);
                h[1] = v;
                h[0] = ((Vec3d) h[0]).add(v.multiply(dt));
                float pop = VFx.backOut(age / 0.3f);
                float al = VFx.smooth((1.4f - age) / 0.6f);
                double s = 0.09 * size.f() * (double) h[3] * pop;
                double wob = Math.sin(age * 6 + (int) h[4]) * 0.25;
                heart((Vec3d) h[0], s, wob, epal(color, VFx.hash((int) h[4])), al);
            }
        }

        static void heart(Vec3d c, double s, double rot, int col, float al) {
            int seg = 28;
            Vec3d[] rim = new Vec3d[seg];
            Vec3d rx = VFx.right.multiply(Math.cos(rot)).add(VFx.up.multiply(Math.sin(rot)));
            Vec3d ry = VFx.up.multiply(Math.cos(rot)).subtract(VFx.right.multiply(Math.sin(rot)));
            for (int i = 0; i < seg; i++) {
                double t = i * Math.PI * 2 / seg;
                double x = 16 * Math.pow(Math.sin(t), 3) / 16.0;
                double y = (13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)) / 16.0;
                rim[i] = c.add(rx.multiply(x * s)).add(ry.multiply(y * s));
            }
            Vec3d mid = c.add(ry.multiply(0.15 * s));
            VFx.sprite(mid, s * 2.2, VFx.a(col, 0.2f * al), true);
            fan(mid, rim, VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.45f), 0.95f * al), VFx.a(col, 0.9f * al), true);
            VFx.spark(c.add(rx.multiply(-0.4 * s)).add(ry.multiply(0.45 * s)), s * 0.12, 0xFFFFFFFF, 0.7f * al, true);
        }
    }

    /* ================================================================== 10. damage numbers */

    static final class DamageNumbers extends Fx {
        private final Setting.Num size = num("Size", 1, 0.5, 2.5, 0.05);
        private final Setting.Num range = num("Range", 24, 4, 64, 1);
        private final Setting.Bool self = bool("Show Your Damage", false);
        private final Setting.Mode color = colors(this, "Gold");
        private final Map<Integer, Float> hp = new HashMap<>();
        /** {entity id, world pos, amount, born, seed}. */
        private final List<Object[]> pops = new ArrayList<>();

        DamageNumbers() {
            super("3D Damage Pops", "Damage pops out of hit entities as bouncing 3D numbers");
        }

        @Override
        protected void onDisable() {
            hp.clear();
            pops.clear();
        }

        @Override
        public void tick() {
            if (mc.world == null) return;
            Set<Integer> seen = new HashSet<>();
            double now = VFx.now();
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof LivingEntity le) || le.distanceTo(mc.player) > range.f()) continue;
                if (le == mc.player && !self.on()) continue;
                seen.add(le.getId());
                float h = le.getHealth();
                Float old = hp.put(le.getId(), h);
                if (old != null && h < old - 0.01f) {
                    Vec3d at = le.getBoundingBox().getCenter().add(0, le.getHeight() * 0.45, 0);
                    pops.add(new Object[]{le.getId(), at, old - h, now, pops.size() * 37 + le.getId()});
                }
            }
            hp.keySet().retainAll(seen);
        }

        @Override
        public void render2d(float td) {
            frame();
            double now = VFx.now();
            pops.removeIf(p -> now - (double) p[3] > 1.3);
            float[] o = new float[2];
            for (Object[] p : pops) {
                float age = (float) (now - (double) p[3]);
                int seed = (int) p[4];
                double side = (VFx.hash(seed) - 0.5) * 1.2;
                // arc: up and to the side, falling back a little
                Vec3d base = (Vec3d) p[1];
                Vec3d q = base.add(VFx.right.multiply(side * VFx.outCubic(age / 1.3f) * 0.8)).add(0, 0.9 * age - 0.45 * age * age, 0);
                if (!Projection.point(q, o)) continue;
                double dist = Math.max(1, q.distanceTo(VFx.cam));
                float pop = VFx.backOut(age / 0.25f);
                float al = VFx.smooth((1.3f - age) / 0.45f);
                float amt = (float) p[2];
                boolean big = amt >= 6;
                float sz = (float) MathHelper.clamp(150 / dist, 10, 64) * size.f() * pop * (big ? 1.25f : 1);
                String s = amt >= 10 ? String.valueOf(Math.round(amt)) : String.format(java.util.Locale.ROOT, "%.1f", amt);
                int col = epal(color, big ? 0 : 0.6f);
                Nvg.textBlur(s, o[0] + 1.5f, o[1] + 2f, sz, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(0xFF000000, 0.55f * al), 5);
                Nvg.textBlur(s, o[0], o[1], sz, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(col, 0.6f * al), 10);
                Nvg.text(s, o[0], o[1], sz, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(VFx.mix(col, 0xFFFFFFFF, 0.25f), al));
            }
        }
    }

    /* ================================================================== 11. rainbow road */

    static final class RainbowRoad extends Fx {
        private final Setting.Num width = num("Width", 0.35, 0.1, 1, 0.05);
        private final Setting.Num length = num("Duration", 2.5, 0.5, 8, 0.1);
        private final Setting.Bool others = bool("Other Players", false);
        private final Setting.Mode color = colors(this, "Rainbow");
        private final Map<Integer, ArrayDeque<Object[]>> roads = new HashMap<>();

        RainbowRoad() {
            super("Rainbow Road", "A shimmering rainbow road unrolls under your feet");
        }

        @Override
        protected void onDisable() {
            roads.clear();
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double now = VFx.now();
            Set<Integer> seen = new HashSet<>();
            for (PlayerEntity p : players(others.on(), true)) {
                seen.add(p.getId());
                ArrayDeque<Object[]> q = roads.computeIfAbsent(p.getId(), k -> new ArrayDeque<>());
                Vec3d feet = p.getLerpedPos(td).add(0, 0.03, 0);
                if (q.isEmpty() || ((Vec3d) q.peekLast()[0]).distanceTo(feet) > 0.18) q.addLast(new Object[]{feet, now});
                while (!q.isEmpty() && now - (double) q.peekFirst()[1] > length.f()) q.pollFirst();
                List<Vec3d> seg = new ArrayList<>();
                List<Float> ages = new ArrayList<>();
                Vec3d prev = null;
                for (Object[] o : q) {
                    Vec3d v = (Vec3d) o[0];
                    if (prev != null && Math.abs(v.y - prev.y) > 0.8) {
                        draw(seg, ages, now);
                        seg = new ArrayList<>();
                        ages = new ArrayList<>();
                    }
                    seg.add(v);
                    ages.add((float) ((now - (double) o[1]) / length.f()));
                    prev = v;
                }
                if (!seg.isEmpty() && seg.get(seg.size() - 1).distanceTo(feet) > 0.01) {
                    seg.add(feet);
                    ages.add(0f);
                }
                draw(seg, ages, now);
            }
            roads.keySet().retainAll(seen);
        }

        private void draw(List<Vec3d> raw, List<Float> ages, double now) {
            if (raw.size() < 2) return;
            float[][] sc = {new float[ages.size()]};
            for (int i = 0; i < ages.size(); i++) sc[0][i] = ages.get(i);
            List<float[]> so = new ArrayList<>();
            List<Vec3d> p = VFx.catmull(raw, 3, sc, so);
            int n = p.size();
            float[] w = new float[n], we = new float[n];
            int[] c = new int[n], e = new int[n];
            float len = 0;
            for (int i = 0; i < n; i++) {
                if (i > 0) len += (float) p.get(i).distanceTo(p.get(i - 1));
                float age = so.get(i)[0];
                float al = (1 - age) * VFx.smooth((1 - age) * 3) * VFx.smooth(i / 3f);
                w[i] = width.f() * (0.6f + 0.4f * (1 - age));
                we[i] = w[i] * 1.15f;
                int col = color.get().equals("Rainbow") ? VFx.hue((float) (len * 0.35 - now * 0.4), 0.55f) : epal(color, (float) ((len * 0.3 - now * 0.3) % 1 + 1) % 1);
                c[i] = VFx.a(col, 0.55f * al);
                e[i] = VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.5f), 0.25f * al);
            }
            VFx.ribbon(p, we, e, VFx.FLAT, null, 0, true);
            VFx.ribbon(p, w, c, VFx.FLAT, null, 0.75f, true);
        }
    }

    /* ================================================================== 12. sakura petals */

    static final class SakuraPetals extends Fx {
        private final Setting.Num count = num("Petals", 26, 6, 80, 1);
        private final Setting.Num radius = num("Radius", 1.6, 0.6, 4, 0.1);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Mode color = colors(this, "Sakura");
        /** {pos, vel, born, life, seed}. */
        private final List<Object[]> petals = new ArrayList<>();
        private final Random rng = new Random();

        SakuraPetals() {
            super("Sakura Petals", "Cherry blossom petals swirl around you and drift to the ground");
        }

        @Override
        protected void onDisable() {
            petals.clear();
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double now = VFx.now();
            Vec3d me = mc.player.getLerpedPos(td);
            while (petals.size() < count.i()) {
                double a = rng.nextDouble() * Math.PI * 2, r = radius.f() * Math.sqrt(rng.nextDouble());
                petals.add(new Object[]{me.add(Math.cos(a) * r, 1.5 + rng.nextDouble() * 1.6, Math.sin(a) * r), Vec3d.ZERO, now, 3.5 + rng.nextDouble() * 3, rng.nextInt(100000)});
            }
            Iterator<Object[]> it = petals.iterator();
            while (it.hasNext()) {
                Object[] p = it.next();
                float age = (float) (now - (double) p[2]), life = (float) (double) p[3];
                Vec3d pos = (Vec3d) p[0];
                if (age > life || pos.distanceTo(me) > radius.f() * 3 + 4) {
                    it.remove();
                    continue;
                }
                int seed = (int) p[4];
                // swirl around you + sway + slow fall
                Vec3d rel = pos.subtract(me);
                Vec3d swirl = new Vec3d(-rel.z, 0, rel.x).normalize().multiply(0.45);
                double sway = Math.sin(age * 2.2 + seed) * 0.35;
                Vec3d vel = swirl.add(Math.cos(seed) * sway, -0.32 - 0.1 * Math.sin(age * 3 + seed), Math.sin(seed) * sway);
                pos = pos.add(vel.multiply(dt));
                p[0] = pos;
                float al = VFx.smooth(age / 0.6f) * VFx.smooth((life - age) / 0.8f);
                petal(pos, age, seed, al);
            }
        }

        private void petal(Vec3d c, float age, int seed, float al) {
            double s = 0.075 * size.f() * (0.7 + 0.6 * VFx.hash(seed));
            // tumbling orientation
            double a = age * (1.5 + VFx.hash(seed + 1) * 2) + seed, b = age * (1.1 + VFx.hash(seed + 2)) + seed * 0.3;
            Vec3d u = new Vec3d(Math.cos(a), Math.sin(b) * 0.6, Math.sin(a)).normalize();
            Vec3d v = u.crossProduct(new Vec3d(Math.sin(b), Math.cos(b), 0.3)).normalize();
            int seg = 12;
            Vec3d[] rim = new Vec3d[seg];
            for (int i = 0; i < seg; i++) {
                double t = i * Math.PI * 2 / seg;
                // teardrop with a notch at the tip
                double r = (1 - 0.35 * Math.cos(t)) * (1 - 0.18 * Math.exp(-Math.pow((t - Math.PI) * 3, 2)));
                rim[i] = c.add(u.multiply(Math.cos(t) * r * s)).add(v.multiply(Math.sin(t) * r * s * 0.62));
            }
            int col = epal(color, VFx.hash(seed) * 0.6f);
            fan(c.add(u.multiply(-0.25 * s)), rim, VFx.a(VFx.mix(col, 0xFFFFFFFF, 0.55f), 0.95f * al), VFx.a(col, 0.85f * al), true);
        }
    }

    /* ================================================================== 13. electric arcs */

    static final class ElectricArcs extends Fx {
        private final Setting.Num arcs = num("Arcs", 4, 1, 10, 1);
        private final Setting.Num rate = num("Flicker Rate", 18, 5, 40, 1);
        private final Setting.Bool always = bool("Always (not only sprint)", false);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = colors(this, "Ice");
        /** id -> {last refresh time, list of local-space bolts}. */
        private final Map<Integer, Object[]> st = new HashMap<>();
        private final Random rng = new Random();

        ElectricArcs() {
            super("Electric Arcs", "Crackling electric arcs jump across your body and to the ground while sprinting");
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double now = VFx.now();
            Set<Integer> seen = new HashSet<>();
            for (PlayerEntity p : players(others.on(), false)) {
                if (!always.on() && !p.isSprinting()) continue;
                seen.add(p.getId());
                BodyAnchor b = BodyAnchor.of(p, td);
                Object[] s = st.get(p.getId());
                if (s == null || now - (double) s[0] > 1.0 / rate.f()) {
                    List<List<Vec3d>> bolts = new ArrayList<>();
                    for (int i = 0; i < arcs.i(); i++) {
                        Vec3d a = new Vec3d((rng.nextDouble() - 0.5) * 0.6, 0.3 + rng.nextDouble() * 1.3, (rng.nextDouble() - 0.5) * 0.35);
                        Vec3d e;
                        if (rng.nextFloat() < 0.4) {
                            double an = rng.nextDouble() * Math.PI * 2;
                            e = new Vec3d(Math.cos(an) * 0.7, 0.02, Math.sin(an) * 0.7);
                        } else e = new Vec3d((rng.nextDouble() - 0.5) * 0.9, 0.2 + rng.nextDouble() * 1.5, (rng.nextDouble() - 0.5) * 0.5);
                        bolts.add(bolt(a, e, rng, 0.28, 4));
                    }
                    s = new Object[]{now, bolts};
                    st.put(p.getId(), s);
                }
                @SuppressWarnings("unchecked") List<List<Vec3d>> bolts = (List<List<Vec3d>>) s[1];
                float fl = (float) (0.6 + 0.4 * Math.sin(now * 90 + p.getId()));
                int i = 0;
                for (List<Vec3d> bl : bolts) {
                    List<Vec3d> w = new ArrayList<>(bl.size());
                    for (Vec3d l : bl) w.add(b.at(l.x, l.y, l.z));
                    glowLine(w, 0.018f, epal(color, VFx.hash(i++)), fl, true);
                    VFx.spark(w.get(w.size() - 1), 0.02, epal(color, 0), fl * 0.8f, true);
                }
            }
            st.keySet().retainAll(seen);
        }
    }

    /* ================================================================== 14. sword glint */

    static final class SwordGlint extends Fx {
        private final Setting.Num interval = num("Interval", 2.2, 0.6, 6, 0.1);
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = colors(this, "White");

        SwordGlint() {
            super("Sword Glint", "A sharp star glint sweeps along held blades every few seconds");
        }

        static boolean blade(ItemStack s) {
            if (s.isEmpty()) return false;
            String id = Registries.ITEM.getId(s.getItem()).getPath();
            return id.endsWith("_sword") || id.endsWith("_axe") || id.equals("trident") || id.equals("mace") || id.endsWith("_spear");
        }

        private float phase(double T, int seed) {
            double k = ((T + VFx.hash(seed) * 3) % interval.f()) / 0.55;
            return (float) k;
        }

        static void star(Vec3d c, double L, int col, float al, double rot) {
            VFx.spark(c, L * 0.12, col, al, false);
            for (int i = 0; i < 4; i++) {
                double a = rot + i * Math.PI / 2;
                double l = L * (i % 2 == 0 ? 1 : 0.55);
                Vec3d d = VFx.right.multiply(Math.cos(a)).add(VFx.up.multiply(Math.sin(a)));
                Render3D.drawLineGradient(c, c.add(d.multiply(l)), VFx.a(0xFFFFFFFF, al), VFx.a(col, 0), 2f, false);
            }
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            for (PlayerEntity p : players(others.on(), false)) {
                if (!blade(p.getMainHandStack())) continue;
                BodyAnchor b = BodyAnchor.of(p, td);
                float k = phase(T, p.getId());
                if (k > 1) continue;
                // right hand (arm hanging, slightly forward) and the blade pointing forward / up
                Vec3d hand = b.at(0.37, 0.72, 0.12);
                Vec3d dir = b.fwd.multiply(0.75).add(b.up.multiply(0.6)).normalize();
                Vec3d q = hand.add(dir.multiply((0.15 + 0.75 * VFx.smooth(k)) * b.scale));
                float al = (float) Math.sin(Math.PI * k);
                star(q, 0.22 * size.f() * (0.6 + 0.4 * al), epal(color, 0.2f), al, T * 2);
            }
        }

        @Override
        public void render2d(float td) {
            // first person: the glint sweeps across the view model in the bottom right
            if (!mc.options.getPerspective().isFirstPerson() || !blade(mc.player.getMainHandStack())) return;
            float k = phase(VFx.now(), mc.player.getId());
            if (k > 1) return;
            float W = Nvg.w, H = Nvg.h;
            float e = VFx.smooth(k);
            float x = W * (0.70f - 0.12f * e), y = H * (0.86f - 0.24f * e);
            float al = (float) Math.sin(Math.PI * k);
            float L = 46 * size.f() * (0.6f + 0.4f * al);
            int col = epal(color, 0.2f);
            Nvg.glowDisc(x, y, L * 0.6f, Nvg.alpha(col, 0.5f * al), 0);
            Nvg.lineGradient(x - L, y, x, y, 2, 0, Nvg.alpha(0xFFFFFFFF, al));
            Nvg.lineGradient(x, y, x + L, y, 2, Nvg.alpha(0xFFFFFFFF, al), 0);
            Nvg.lineGradient(x, y - L * 0.6f, x, y, 2, 0, Nvg.alpha(0xFFFFFFFF, al));
            Nvg.lineGradient(x, y, x, y + L * 0.6f, 2, Nvg.alpha(0xFFFFFFFF, al), 0);
            Nvg.glowDisc(x, y, 5, Nvg.alpha(0xFFFFFFFF, al), 0);
        }
    }

    /* ================================================================== 15. jetpack flames */

    static final class JetpackFlames extends Fx {
        private final Setting.Num length = num("Flame Length", 1, 0.4, 2.5, 0.05);
        private final Setting.Bool always = bool("Always", false);
        private final Setting.Bool pack = bool("Show Jetpack", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = mode("Flame", "Fire", "Fire", "Blue", "Purple", "Green");
        /** {pos, vel, born}. */
        private final List<Object[]> sparks = new ArrayList<>();
        private final Map<Integer, Float> thrust = new HashMap<>();
        private final Random rng = new Random();

        JetpackFlames() {
            super("Jetpack Flames", "Roaring jetpack flames on your back while flying with an elytra");
        }

        private int[] ramp() {
            return switch (color.get()) {
                case "Blue" -> new int[]{0xFFFFFFFF, 0xFF8FE3FF, 0xFF2D7BFF, 0xFF1A2AFF};
                case "Purple" -> new int[]{0xFFFFFFFF, 0xFFE3A6FF, 0xFFA64DFF, 0xFF5A1AFF};
                case "Green" -> new int[]{0xFFFFFFFF, 0xFFC8FFAE, 0xFF4DFF6A, 0xFF138A3A};
                default -> new int[]{0xFFFFFFFF, 0xFFFFE27A, 0xFFFF8A1F, 0xFFE0261A};
            };
        }

        private int ramp(float t) {
            int[] r = ramp();
            t = Math.max(0, Math.min(0.999f, t)) * (r.length - 1);
            int i = (int) t;
            return VFx.mix(r[i], r[i + 1], t - i);
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            float td = td();
            double T = VFx.now();
            Set<Integer> seen = new HashSet<>();
            for (PlayerEntity p : players(others.on(), false)) {
                BodyAnchor b = BodyAnchor.of(p, td);
                seen.add(p.getId());
                float want = always.on() || b.flying() ? 1 : 0;
                float th = thrust.getOrDefault(p.getId(), 0f);
                th += (want - th) * (1 - (float) Math.exp(-6 * dt));
                thrust.put(p.getId(), th);
                if (th < 0.01f && !pack.on()) continue;
                Vec3d down = b.up.multiply(-1);
                for (int s = -1; s <= 1; s += 2) {
                    Vec3d top = b.at(0.13 * s, 1.3, -0.3), noz = b.at(0.13 * s, 0.86, -0.3);
                    if (pack.on() && (b.flying() || always.on() || th > 0.01f)) {
                        box(top.lerp(noz, 0.5), b.right.multiply(0.075 * b.scale), b.up.multiply(0.22 * b.scale), b.fwd.multiply(0.075 * b.scale), 0xC02A2E36, 0x90C8CDD6, true);
                        VFx.ring(noz, b.right, b.fwd, 0.06 * b.scale, 0.012, 20, x -> ramp(0.3f), 0.8f * th, 0, true);
                    }
                    if (th < 0.01f) continue;
                    int N = 16;
                    double L = 0.75 * length.f() * th * (0.85 + 0.15 * Math.sin(T * 40 + s * 2) + 0.05 * Math.sin(T * 67));
                    List<Vec3d> pts = new ArrayList<>();
                    float[] w = new float[N], wc = new float[N];
                    int[] c = new int[N], cc = new int[N];
                    // the flame bends with your motion (trails behind)
                    Vec3d drift = b.vel.multiply(-1.4);
                    for (int i = 0; i < N; i++) {
                        double t = i / (double) (N - 1);
                        double wob = Math.sin(T * 30 + t * 9 + s) * 0.02 * t;
                        pts.add(noz.add(down.multiply(L * t)).add(drift.multiply(t * t)).add(b.right.multiply(wob)));
                        float shape = (float) (Math.pow(Math.sin(Math.PI * Math.min(1, 0.12 + t * 0.95)), 0.8));
                        w[i] = (float) (0.075 * b.scale * shape * (1 - 0.6 * t));
                        wc[i] = w[i] * 0.45f;
                        c[i] = VFx.a(ramp((float) (0.25 + t)), 0.8f * (float) (1 - t));
                        cc[i] = VFx.a(ramp((float) (t * 0.8)), 0.95f * (float) Math.pow(1 - t, 1.6));
                    }
                    VFx.glowRibbon(pts, w, c, VFx.CAMERA, null, 3, 1.4f, th, true);
                    VFx.ribbon(pts, w, c, VFx.CAMERA, null, 0.5f, true);
                    VFx.ribbon(pts, wc, cc, VFx.CAMERA, null, 0.4f, true);
                    VFx.sprite(noz, 0.2 * b.scale, VFx.a(ramp(0.4f), 0.35f * th), true);
                    if (rng.nextFloat() < 0.6f * th) sparks.add(new Object[]{noz, down.multiply(3 + rng.nextDouble() * 3).add(b.vel.multiply(10))
                            .add((rng.nextDouble() - 0.5) * 1.2, (rng.nextDouble() - 0.5) * 1.2, (rng.nextDouble() - 0.5) * 1.2), T});
                }
            }
            thrust.keySet().retainAll(seen);
            sparks.removeIf(sp -> T - (double) sp[2] > 0.5);
            for (Object[] sp : sparks) {
                Vec3d v = ((Vec3d) sp[1]).multiply(Math.exp(-3 * dt)).add(0, -4 * dt, 0);
                sp[1] = v;
                sp[0] = ((Vec3d) sp[0]).add(v.multiply(dt));
                float age = (float) ((T - (double) sp[2]) / 0.5);
                VFx.spark((Vec3d) sp[0], 0.012, ramp(0.2f + age), 1 - age, true);
            }
        }
    }

    /* ================================================================== 16. jump bubbles */

    static final class JumpBubbles extends Fx {
        private final Setting.Num size = num("Size", 1, 0.5, 2, 0.05);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = colors(this, "Ice");
        private final Map<Integer, Boolean> ground = new HashMap<>();
        /** {pos, born, seed}. */
        private final List<Object[]> jumps = new ArrayList<>();

        JumpBubbles() {
            super("Jump Bubbles", "A ring of bubbles bursts out under your feet when you jump");
        }

        @Override
        public void tick() {
            if (mc.world == null) return;
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p != mc.player && !others.on()) continue;
                boolean g = p.isOnGround();
                Boolean was = ground.put(p.getId(), g);
                if (was != null && was && !g && p.getVelocity().y > 0.05) jumps.add(new Object[]{p.getEntityPos(), VFx.now(), p.getId() * 31 + jumps.size()});
            }
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            frame();
            double now = VFx.now();
            jumps.removeIf(j -> now - (double) j[1] > 1.1);
            for (Object[] j : jumps) {
                float age = (float) (now - (double) j[1]);
                Vec3d c = ((Vec3d) j[0]).add(0, 0.04, 0);
                int seed = (int) j[2];
                float k = VFx.outCubic(age / 0.6f);
                float al = VFx.smooth((1.1f - age) / 0.5f);
                double R = (0.25 + 0.75 * k) * size.f();
                VFx.ring(c, VFx.X, VFx.Z, R, 0.07 * (1 - k * 0.5), 64, t -> epal(color, t), 0.4f * al, 0, true);
                VFx.ring(c, VFx.X, VFx.Z, R, 0.012, 64, t -> 0xFFFFFFFF, 0.7f * al, 0, true);
                int n = 12;
                for (int i = 0; i < n; i++) {
                    double a = i * Math.PI * 2 / n + VFx.hash(seed + i) * 0.4;
                    double rise = age * (0.5 + VFx.hash(seed + i * 3) * 0.7);
                    Vec3d bp = c.add(Math.cos(a) * R * (0.9 + 0.2 * VFx.hash(i)), rise + 0.05, Math.sin(a) * R * (0.9 + 0.2 * VFx.hash(i)));
                    double br = (0.035 + 0.035 * VFx.hash(seed * 7 + i)) * size.f() * VFx.backOut(age / 0.3f);
                    // bubbles: soft fill, bright rim, highlight; popping near the end
                    float pa = al * (age > 0.8f + 0.25f * VFx.hash(i) ? 0 : 1);
                    if (pa < 0.01f) continue;
                    int col = epal(color, VFx.hash(seed + i));
                    VFx.sprite(bp, br, VFx.a(col, 0.18f * pa), true);
                    VFx.ring(bp, VFx.right, VFx.up, br, br * 0.18, 20, t -> col, 0.75f * pa, 0, true);
                    VFx.sprite(bp.add(VFx.right.multiply(-br * 0.35)).add(VFx.up.multiply(br * 0.35)), br * 0.25, VFx.a(0xFFFFFFFF, 0.8f * pa), true);
                }
            }
        }
    }

    /* ================================================================== 17. dragon tail */

    static final class DragonTail extends Fx {
        private final Setting.Num length = num("Length", 1.3, 0.5, 2.5, 0.05);
        private final Setting.Num thickness = num("Thickness", 1, 0.5, 2, 0.05);
        private final Setting.Bool spikes = bool("Spikes", true);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = colors(this, "Crimson");
        private final Map<Integer, Vec3d[]> chains = new HashMap<>();

        DragonTail() {
            super("Dragon Tail", "A swaying, spiked dragon tail with physics");
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
                BodyAnchor b = BodyAnchor.of(p, td);
                Vec3d back = b.backDir();
                Vec3d root = b.at(0, 0.78, -0.14);
                int N = 14;
                double seg = length.f() * b.scale / (N - 1);
                Vec3d[] ch = chains.get(p.getId());
                if (ch == null || ch[0].squaredDistanceTo(root) > 9) {
                    ch = new Vec3d[N];
                    for (int i = 0; i < N; i++) ch[i] = root.add(back.multiply(seg * i)).add(0, -seg * i * 0.4, 0);
                    chains.put(p.getId(), ch);
                }
                ch[0] = root;
                for (int i = 1; i < N; i++) {
                    float k = i / (float) (N - 1);
                    // rest pose: out behind and curving down, swaying side to side
                    // rest pose: out behind the hips and hanging toward the feet (trails behind you when gliding)
                    Vec3d rest = ch[i - 1].add(back.multiply(seg * (0.75 - 0.45 * k))).add(b.up.multiply(-seg * (0.55 + 0.35 * k)))
                            .add(b.right.multiply(Math.sin(T * 2.2 - i * 0.45) * seg * 0.55 * k));
                    Vec3d target = ch[i].lerp(rest, 1 - Math.exp(-(10 - 6 * k) * dt)).add(0, -0.8 * dt * k, 0);
                    Vec3d d = target.subtract(ch[i - 1]);
                    double len = d.length();
                    ch[i] = len < 1e-6 ? rest : ch[i - 1].add(d.multiply(seg / len));
                    double behind = ch[i].subtract(root).dotProduct(back);
                    if (behind < 0.05 * k) ch[i] = ch[i].add(back.multiply(0.05 * k - behind));
                }
                List<Vec3d> pts = VFx.catmull(java.util.Arrays.asList(ch), 3, null, null);
                int n = pts.size();
                float[] w = new float[n], wh = new float[n];
                int[] c = new int[n], hl = new int[n];
                for (int i = 0; i < n; i++) {
                    float k = i / (float) (n - 1);
                    w[i] = (float) (0.1 * thickness.f() * b.scale * Math.pow(1 - k, 0.8) + 0.004);
                    wh[i] = w[i] * 0.35f;
                    float scale = (float) Math.pow(0.5 + 0.5 * Math.sin(k * 40 - T * 2), 3);
                    c[i] = VFx.a(VFx.mix(VFx.mix(0xFF1B0507, epal(color, k), 0.75f), 0xFFFFFFFF, scale * 0.12f), 0.95f);
                    hl[i] = VFx.a(VFx.mix(epal(color, 1), 0xFFFFFFFF, 0.4f), 0.45f * (1 - k));
                }
                VFx.glowRibbon(pts, w, java.util.Arrays.stream(c).map(x -> VFx.am(x, 0.35f)).toArray(), VFx.CAMERA, null, 2, 1.2f, 0.7f, true);
                VFx.ribbon(pts, w, c, VFx.CAMERA, null, 0.8f, true);
                VFx.ribbon(pts, wh, hl, VFx.CAMERA, null, 0.3f, true);
                if (spikes.on()) for (int i = 2; i < n - 2; i += 3) {
                    float k = i / (float) (n - 1);
                    Vec3d a = pts.get(i), t = pts.get(i + 1).subtract(pts.get(i - 1)).normalize();
                    Vec3d upv = b.up.subtract(t.multiply(b.up.dotProduct(t)));
                    if (upv.lengthSquared() < 1e-6) continue;
                    upv = upv.normalize();
                    double h = 0.09 * thickness.f() * b.scale * (1 - k) + 0.02;
                    Vec3d base0 = a.subtract(t.multiply(h * 0.5)).add(upv.multiply(w[i] * 0.6)), base1 = a.add(t.multiply(h * 0.5)).add(upv.multiply(w[i] * 0.6));
                    Vec3d tip = a.add(t.multiply(-h * 0.4)).add(upv.multiply(w[i] * 0.6 + h));
                    int cb = VFx.a(epal(color, k), 0.95f), ct = VFx.a(VFx.mix(epal(color, 1), 0xFFFFFFFF, 0.5f), 0.95f);
                    Render3D.drawGradientQuad(base0, base1, tip, tip, cb, cb, ct, ct, true);
                }
                VFx.spark(pts.get(n - 1), 0.02 * b.scale, epal(color, 1), 0.7f, true);
            }
            chains.keySet().retainAll(seen);
        }
    }
}

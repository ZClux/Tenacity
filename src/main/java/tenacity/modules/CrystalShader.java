package tenacity.modules;

import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tenacity.core.Setting;
import tenacity.gfx.Nvg;
import tenacity.modules.MoreVisuals.VFx;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static tenacity.modules.PremiumVisuals.clamp01;
import static tenacity.modules.PremiumVisuals.outBack;
import static tenacity.modules.PremiumVisuals.smooth;
import static tenacity.modules.PremiumVisuals.tri;
import static tenacity.modules.UltraFx.*;

/**
 * Crystal Shader: replaces the vanilla End Crystal with smooth, time based, tick-delta interpolated custom crystals
 * (glass, neon, hologram, plasma core, diamond, orb) with soft additive glow, a custom beam and a sleek base.
 * The vanilla renderer is cancelled / trimmed by {@code EndCrystalRendererMixin}.
 */
public final class CrystalShader extends PremiumVisuals.PV {
    private static CrystalShader INSTANCE;
    /** Dev harness: give every crystal a different style (by spawn order). */
    public static boolean devAllStyles;

    private static final String[] STYLES = {"Glass", "Neon", "Hologram", "Plasma Core", "Diamond", "Orb"};
    private final Setting.Mode style = mode("Style", "Glass", STYLES);
    private final Setting.Mode color = mode("Color", "Frost", "Frost", "Mono", "Ice", "Purple", "Rose", "Aurora", "Gold", "Ocean", "Chroma", "Custom");
    private final Setting.Color custom = color("Custom Color", 0xFF8FD8FF);
    private final Setting.Bool replace = bool("Replace Vanilla", true);
    private final Setting.Num scale = num("Scale", 1, 0.4, 2.5, 0.05);
    private final Setting.Num spin = num("Spin Speed", 1, 0, 4, 0.05);
    private final Setting.Num bobH = num("Bob Height", 0.25, 0, 1, 0.01);
    private final Setting.Num bobS = num("Bob Speed", 1, 0, 4, 0.05);
    private final Setting.Num glowAmt = num("Glow", 1, 0, 2.5, 0.05);
    private final Setting.Bool pulse = bool("Pulse", true);
    private final Setting.Mode beam = mode("Beam", "Custom", "Custom", "Vanilla", "Off");
    private final Setting.Bool hideBase = bool("Hide Base", false);
    private final Setting.Bool walls = bool("Through Walls", false);
    private final Setting.Num range = num("Range", 96, 16, 256, 8);

    public CrystalShader() {
        super("Crystal Shader", "Smooth custom End Crystals: glass, neon, hologram, plasma, diamond and orb styles");
        INSTANCE = this;
        custom.showIf(() -> color.is("Custom"));
    }

    private static boolean on() {
        return INSTANCE != null && INSTANCE.enabled() && INSTANCE.inWorld();
    }

    /** Cancel the whole vanilla crystal render (beam included). */
    public static boolean hideVanilla() {
        return on() && INSTANCE.replace.on();
    }

    public static boolean hideBase() {
        return on() && INSTANCE.hideBase.on();
    }

    public static boolean hideBeam() {
        return on() && !INSTANCE.beam.is("Vanilla");
    }

    private int col(float t) {
        t = clamp01(t);
        return switch (color.get()) {
            case "Frost" -> Nvg.mix(0xFFFFFFFF, 0xFFB9DDFF, t);
            case "Mono" -> Nvg.mix(0xFFFFFFFF, 0xFF8A8F98, t);
            case "Ice" -> Nvg.mix(0xFFBFF4FF, 0xFF4FA8FF, t);
            case "Purple" -> Nvg.mix(0xFFE3C2FF, 0xFF8A4DFF, t);
            case "Rose" -> Nvg.mix(0xFFFFD3E2, 0xFFFF4F8B, t);
            case "Aurora" -> tri(0xFF3CF5C0, 0xFF4FA8FF, 0xFFB46CFF, t);
            case "Gold" -> Nvg.mix(0xFFFFF3D2, 0xFFFFB23A, t);
            case "Ocean" -> Nvg.mix(0xFF6EF7FF, 0xFF2E6BFF, t);
            case "Chroma" -> VFx.hue((float) (t * 0.5 + VFx.now() * 0.12), 0.55f);
            default -> Nvg.mix(0xFFFFFFFF, custom.rgb() | 0xFF000000, 0.35f + 0.65f * t);
        };
    }

    /* ------------------------------------------------------------------ frame */

    @Override
    void world(float td, double t, float dt, float al) {
        VFx.frame();
        boolean d = !walls.on();
        double r2 = range.f() * range.f();
        List<EndCrystalEntity> list = new ArrayList<>();
        for (Entity e : mc.world.getEntities()) if (e instanceof EndCrystalEntity c && c.squaredDistanceTo(mc.player) < r2) list.add(c);
        list.sort((x, y) -> Integer.compare(y.getId(), x.getId()));
        int k = 0;
        for (EndCrystalEntity c : list) {
            float born = smooth((c.age + td) / 14f);
            int st = devAllStyles ? k % STYLES.length : style.index;
            draw(c, td, t, al * born, st, d, outBack(clamp01((c.age + td) / 14f)));
            k++;
        }
    }

    private void draw(EndCrystalEntity c, float td, double t, float al, int st, boolean d, float pop) {
        Vec3d pos = c.getLerpedPos(td);
        double ph = (c.getId() * 0.6180339) % 1 * Math.PI * 2;
        double s = scale.f() * pop;
        float pl = pulse.on() ? (float) (0.5 + 0.5 * Math.sin(t * 2.4 + ph)) : 0.5f;
        double bob = bobH.f() * Math.sin(t * 1.6 * bobS.f() + ph);
        Vec3d cen = pos.add(0, 1.15 + bob, 0);
        double ang = t * 1.1 * spin.f() + ph; // continuous, frame time based: no tick stepping

        // base
        if (replace.on() && !hideBase.on() && c.shouldShowBottom()) base(pos, t, al, d);
        // beam
        BlockPos bt = c.getBeamTarget();
        if (bt != null && beam.is("Custom")) beam(cen, Vec3d.ofCenter(bt), t, al, d);
        if (!replace.on()) return;

        // ambient halo, shared by every style
        float g = glowAmt.f();
        int c0 = col(0), c1 = col(1);
        sprite(cen, 1.5 * s, c1, al * 0.06f * g * (0.8f + 0.4f * pl), d);
        sprite(cen, 0.8 * s, c0, al * 0.07f * g, d);
        // floor light pool
        VFx.disc(pos.add(0, 0.03, 0), VFx.X, VFx.Z, 1.1 * s, 28, a(c1, 0.10f * al * g), a(c1, 0), true);

        switch (STYLES[st]) {
            case "Glass" -> glassStyle(cen, s, ang, t, al, pl, d);
            case "Neon" -> neon(cen, s, ang, t, al, pl, d);
            case "Hologram" -> holo(pos, cen, s, ang, t, al, pl, d, c.getId());
            case "Plasma Core" -> plasma(cen, s, ang, t, al, pl, d, c.getId());
            case "Diamond" -> diamond(cen, s, ang, t, al, pl, d);
            default -> orb(cen, s, ang, t, al, pl, d);
        }
    }

    /* ------------------------------------------------------------------ geometry */

    /** Vanilla-like tumbling frame: spin about Y then a 60 degree tilt about the (1,0,1) diagonal. */
    private static Quaternionf frame(double ang, double extra) {
        float k = (float) Math.sin(Math.PI / 4);
        return new Quaternionf().rotateY((float) ang).rotateAxis((float) Math.toRadians(60), k, 0, k).rotateY((float) extra);
    }

    private static Vec3d tf(Quaternionf q, double x, double y, double z) {
        Vector3f v = q.transform(new Vector3f((float) x, (float) y, (float) z));
        return new Vec3d(v.x, v.y, v.z);
    }

    private static final int[][] FACES = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
    private static final int[][] EDGES = {{0, 1}, {1, 3}, {3, 2}, {2, 0}, {4, 5}, {5, 7}, {7, 6}, {6, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    private static Vec3d[] cube(Vec3d c, Quaternionf q, double h) {
        Vec3d[] v = new Vec3d[8];
        for (int i = 0; i < 8; i++) v[i] = c.add(tf(q, (i & 1) == 0 ? -h : h, (i & 2) == 0 ? -h : h, (i & 4) == 0 ? -h : h));
        return v;
    }

    /** 0 facing the camera, 1 grazing. */
    private static float fresnel(Vec3d centre, Vec3d n) {
        Vec3d v = centre.subtract(VFx.cam).normalize();
        return 1 - (float) Math.abs(v.dotProduct(n));
    }

    private void glassCube(Vec3d c, Vec3d[] v, float alpha, int tint, float edge, boolean d) {
        for (int[] f : FACES) {
            Vec3d fc = v[f[0]].add(v[f[1]]).add(v[f[2]]).add(v[f[3]]).multiply(0.25);
            Vec3d n = fc.subtract(c).normalize();
            float fr = fresnel(fc, n);
            float base = alpha * (0.05f + 0.32f * fr * fr);
            int[] cs = new int[4];
            for (int i = 0; i < 4; i++) {
                // liquid sheen: brighter toward the top of the crystal, frost white at grazing angles
                float hi = (float) clamp01((float) ((v[f[i]].y - c.y) * 2.2 + 0.5));
                cs[i] = a(mix(tint, FROST, 0.4f + 0.5f * fr), base * (0.65f + 0.6f * hi));
            }
            glass(v[f[0]], v[f[1]], v[f[2]], v[f[3]], cs[0], cs[1], cs[2], cs[3], d);
        }
        if (edge > 0) for (int[] e : EDGES) {
            Vec3d mid = v[e[0]].add(v[e[1]]).multiply(0.5);
            float fr = fresnel(mid, mid.subtract(c).normalize());
            int ec = mix(tint, WHITE, 0.6f);
            line(v[e[0]], v[e[1]], 0.035, a(ec, edge * (0.25f + 0.5f * fr)), a(ec, edge * (0.25f + 0.5f * fr)), d);
            line(v[e[0]], v[e[1]], 0.010, a(WHITE, edge * 0.9f), a(WHITE, edge * 0.9f), d);
        }
    }

    private void glassStyle(Vec3d cen, double s, double ang, double t, float al, float pl, boolean d) {
        float g = glowAmt.f();
        Quaternionf q1 = frame(ang, 0), q2 = frame(ang * 1.35 + 0.8, 0.6), q3 = frame(-ang * 1.9, 1.2);
        Vec3d[] o = cube(cen, q1, 0.42 * s), m = cube(cen, q2, 0.31 * s), in = cube(cen, q3, 0.17 * s);
        // inner core: coloured light
        for (int[] f : FACES) q(d, in[f[0]], in[f[1]], in[f[2]], in[f[3]], a(col(1), 0.30f * al), a(col(0.6f), 0.40f * al), a(col(1), 0.30f * al), a(col(0.3f), 0.45f * al));
        glow(cen, 0.12 * s, col(0.7f), al * g * (0.6f + 0.4f * pl), d);
        glassCube(cen, m, al * 0.9f, col(0.5f), al * 0.6f, d);
        glassCube(cen, o, al, col(0.2f), al * 0.85f, d);
        // travelling glint on the outer edges
        for (int k = 0; k < 2; k++) {
            int[] e = EDGES[(int) ((t * 1.3 + k * 6) % 12)];
            float u = (float) ((t * 1.3 + k * 6) % 1);
            Vec3d p = o[e[0]].add(o[e[1]].subtract(o[e[0]]).multiply(smooth(u)));
            glow(p, 0.03 * s, WHITE, al * (float) Math.sin(Math.PI * u), d);
        }
    }

    private void neon(Vec3d cen, double s, double ang, double t, float al, float pl, boolean d) {
        float g = glowAmt.f();
        double[] hs = {0.44, 0.32, 0.19};
        for (int k = 0; k < 3; k++) {
            Quaternionf q = frame(ang * (1 + k * 0.45) * (k == 1 ? -1 : 1), k * 0.7);
            Vec3d[] v = cube(cen, q, hs[k] * s);
            int c = col(k / 2f);
            for (int[] e : EDGES) glowLine(v[e[0]], v[e[1]], 0.016 * s * (1 + 0.3 * g), c, al * (0.5f + 0.2f * pl) * (k == 2 ? 0.9f : 0.7f), d);
            for (Vec3d p : v) glow(p, 0.018 * s, c, al * 0.8f, d);
        }
        glow(cen, 0.1 * s, col(1), al * g * (0.7f + 0.5f * pl), d);
    }

    private void holo(Vec3d pos, Vec3d cen, double s, double ang, double t, float al, float pl, boolean d, int id) {
        float flick = 0.85f + 0.15f * (float) Math.sin(t * 37 + id) * (float) Math.sin(t * 13.3);
        al *= flick;
        Quaternionf q = frame(ang, 0);
        double h = 0.42 * s;
        Vec3d[] v = cube(cen, q, h);
        int c = col(0.6f);
        // scanlines across every face, scrolling
        int lines = 9;
        for (int[] f : FACES) {
            Vec3d o = v[f[0]], ea = v[f[1]].subtract(o), eb = v[f[3]].subtract(o);
            for (int i = 0; i < lines; i++) {
                double u = ((i + t * 0.9) % lines) / lines;
                float fa = 0.35f + 0.65f * (float) Math.pow(Math.sin(Math.PI * u), 2);
                Vec3d p0 = o.add(eb.multiply(u)), p1 = p0.add(ea);
                line(p0, p1, 0.012 * s, a(c, 0.55f * al * fa), a(c, 0.55f * al * fa), d);
            }
            glass(v[f[0]], v[f[1]], v[f[2]], v[f[3]], a(c, 0.05f * al), a(c, 0.05f * al), a(c, 0.05f * al), a(c, 0.05f * al), d);
        }
        for (int[] e : EDGES) glowLine(v[e[0]], v[e[1]], 0.012 * s, c, al * 0.8f, d);
        // inner wire core
        Vec3d[] in = cube(cen, frame(-ang * 1.6, 1), 0.16 * s);
        for (int[] e : EDGES) line(in[e[0]], in[e[1]], 0.02 * s, a(WHITE, 0.7f * al), a(WHITE, 0.7f * al), d);
        // projector cone from the base
        Vec3d src = pos.add(0, 0.28, 0);
        int seg = 24;
        double rr = 0.5 * s;
        for (int i = 0; i < seg; i++) {
            double a0 = i * Math.PI * 2 / seg + t * 0.4, a1 = (i + 1) * Math.PI * 2 / seg + t * 0.4;
            Vec3d r0 = cen.add(Math.cos(a0) * rr, -h * 0.6, Math.sin(a0) * rr), r1 = cen.add(Math.cos(a1) * rr, -h * 0.6, Math.sin(a1) * rr);
            q(d, src, src, r1, r0, a(c, 0.16f * al), a(c, 0.16f * al), a(c, 0), a(c, 0));
        }
        VFx.ring(src, VFx.X, VFx.Z, 0.38 * s, 0.03 * s, 48, x -> col(x), al * 0.8f, (float) t, d);
        VFx.ring(src, VFx.X, VFx.Z, 0.24 * s * (1 + 0.6 * ((t * 0.7) % 1)), 0.02 * s, 48, x -> col(x), al * 0.6f * (1 - (float) ((t * 0.7) % 1)), 0, d);
    }

    private void plasma(Vec3d cen, double s, double ang, double t, float al, float pl, boolean d, int id) {
        float g = glowAmt.f();
        double cr = 0.16 * s * (0.9 + 0.2 * pl);
        // turbulent core: several drifting blobs
        for (int i = 0; i < 6; i++) {
            double a1 = t * (1.3 + i * 0.37) + i, a2 = t * (0.9 + i * 0.21) + i * 2;
            Vec3d o = new Vec3d(Math.cos(a1) * Math.sin(a2), Math.cos(a2), Math.sin(a1) * Math.sin(a2)).multiply(cr * 0.45);
            sprite(cen.add(o), cr * 1.2, col(i / 5f), al * 0.2f, d);
        }
        glow(cen, cr * 0.6, col(0.8f), al * g * (0.5f + 0.3f * pl), d);
        sprite(cen, cr * 0.5, WHITE, al * 0.6f, d);
        // three orbit rings with electrons
        for (int k = 0; k < 3; k++) {
            Quaternionf q = new Quaternionf().rotateY((float) (ang * 0.5 + k * 2.1)).rotateX((float) (Math.toRadians(65) + k * 0.4)).rotateZ((float) (k * 1.1));
            Vec3d u = tf(q, 1, 0, 0), v = tf(q, 0, 0, 1);
            double R = (0.36 + k * 0.05) * s;
            int c = col(k / 2f);
            List<Vec3d> pts = new ArrayList<>();
            int n = 56;
            float[] w = new float[n + 1];
            int[] cs = new int[n + 1];
            double rot = t * (1.6 + k * 0.5) * (k == 1 ? -1 : 1);
            for (int i = 0; i <= n; i++) {
                double an = i * Math.PI * 2 / n;
                pts.add(cen.add(u.multiply(Math.cos(an) * R)).add(v.multiply(Math.sin(an) * R)));
                // bright comet section chasing the electron
                double dd = ((an - rot) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2);
                float tail = (float) Math.exp(-dd * 1.2);
                w[i] = (float) (0.012 * s);
                cs[i] = a(c, al * (0.2f + 0.8f * tail));
            }
            glowRibbon(pts, w, cs, g, d);
            Vec3d e = cen.add(u.multiply(Math.cos(rot) * R)).add(v.multiply(Math.sin(rot) * R));
            glow(e, 0.03 * s, c, al, d);
        }
        // occasional arcs from the core to the orbit shell
        Random r = new Random(id * 31L + (long) (t * 12));
        for (int i = 0; i < 2; i++) {
            Vec3d dir = new Vec3d(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()).normalize();
            List<Vec3d> b = bolt(cen.add(dir.multiply(cr * 0.5)), cen.add(dir.multiply(0.4 * s)), 0.5, 3, r);
            float[] w = new float[b.size()];
            int[] cs = new int[b.size()];
            for (int j = 0; j < b.size(); j++) {
                w[j] = (float) (0.008 * s);
                cs[j] = a(col(0.3f), al * 0.8f * (1 - j / (float) b.size()));
            }
            glowRibbon(b, w, cs, g, d);
        }
    }

    private void diamond(Vec3d cen, double s, double ang, double t, float al, float pl, boolean d) {
        float g = glowAmt.f();
        Quaternionf q = new Quaternionf().rotateY((float) ang).rotateX((float) (Math.sin(t * 0.7) * 0.15));
        double w = 0.34 * s, top = 0.30 * s, bot = 0.62 * s;
        int sides = 8;
        Vec3d T = cen.add(tf(q, 0, top, 0)), B = cen.add(tf(q, 0, -bot, 0));
        Vec3d[] girdle = new Vec3d[sides], table = new Vec3d[sides];
        for (int i = 0; i < sides; i++) {
            double an = i * Math.PI * 2 / sides;
            girdle[i] = cen.add(tf(q, Math.cos(an) * w, 0, Math.sin(an) * w));
            table[i] = cen.add(tf(q, Math.cos(an + Math.PI / sides) * w * 0.55, top, Math.sin(an + Math.PI / sides) * w * 0.55));
        }
        Vec3d light = new Vec3d(0.4, 1, 0.3).normalize();
        for (int i = 0; i < sides; i++) {
            int j = (i + 1) % sides;
            // pavilion (lower) facet
            facet(cen, girdle[i], girdle[j], B, light, al, 0.7f, d);
            // crown: kite between girdle edge and table edge
            facet(cen, girdle[i], girdle[j], table[i], light, al, 0.2f, d);
            facet(cen, table[i], girdle[j], table[j], light, al, 0.1f, d);
            glass(T, table[i], table[j], table[j], a(FROST, 0.10f * al), a(FROST, 0.18f * al), a(FROST, 0.18f * al), a(FROST, 0.18f * al), d);
            int ec = mix(col(0.4f), WHITE, 0.5f);
            line(girdle[i], girdle[j], 0.012 * s, a(ec, 0.85f * al), a(ec, 0.85f * al), d);
            line(girdle[i], B, 0.010 * s, a(ec, 0.55f * al), a(col(1), 0.2f * al), d);
            line(girdle[i], table[i], 0.010 * s, a(ec, 0.6f * al), a(ec, 0.6f * al), d);
            line(table[i], table[j], 0.010 * s, a(WHITE, 0.7f * al), a(WHITE, 0.7f * al), d);
        }
        glow(cen.add(0, -0.08 * s, 0), 0.12 * s, col(0.9f), al * g * (0.5f + 0.4f * pl), d);
        // twinkles hopping around the girdle
        for (int k = 0; k < 3; k++) {
            double ph = t * 0.8 + k / 3.0;
            int idx = (int) Math.floor(ph * sides * 0.37 + k * 3) % sides;
            float tw = (float) Math.pow(Math.sin(Math.PI * (ph % 1)), 6);
            Vec3d p = (k == 0 ? table : girdle)[idx];
            glow(p, 0.025 * s, WHITE, al * tw, d);
            Vec3d hx = VFx.right.multiply(0.18 * s * tw), hy = VFx.up.multiply(0.18 * s * tw);
            line(p.subtract(hx), p.add(hx), 0.006 * s, a(WHITE, 0), a(WHITE, 0), d);
            line(p.subtract(hx), p, 0.006 * s, a(WHITE, 0), a(WHITE, tw * al), d);
            line(p, p.add(hx), 0.006 * s, a(WHITE, tw * al), a(WHITE, 0), d);
            line(p.subtract(hy), p, 0.006 * s, a(WHITE, 0), a(WHITE, tw * al), d);
            line(p, p.add(hy), 0.006 * s, a(WHITE, tw * al), a(WHITE, 0), d);
        }
    }

    private void facet(Vec3d c, Vec3d p0, Vec3d p1, Vec3d p2, Vec3d light, float al, float tintAmt, boolean d) {
        Vec3d n = p1.subtract(p0).crossProduct(p2.subtract(p0)).normalize();
        Vec3d fc = p0.add(p1).add(p2).multiply(1 / 3.0);
        if (n.dotProduct(fc.subtract(c)) < 0) n = n.multiply(-1);
        float lam = (float) Math.max(0, n.dotProduct(light));
        float fr = fresnel(fc, n);
        int base = mix(col(0.5f + 0.5f * tintAmt), FROST, 0.3f + 0.5f * lam);
        float aa = al * (0.10f + 0.25f * fr + 0.15f * lam);
        glass(p0, p1, p2, p2, a(base, aa), a(base, aa * 0.8f), a(mix(base, col(1), 0.5f), aa * 1.2f), a(base, aa), d);
    }

    private void orb(Vec3d cen, double s, double ang, double t, float al, float pl, boolean d) {
        float g = glowAmt.f();
        double R = 0.36 * s * (0.96 + 0.08 * pl);
        // body: stacked soft light, fresnel rim, hot core
        sprite(cen, R * 1.05, col(0.9f), al * 0.20f, d);
        sprite(cen, R * 0.7, col(0.5f), al * 0.16f, d);
        rim(cen, R, 0.035 * s, mix(col(0.2f), WHITE, 0.4f), al * 0.75f, d);
        rim(cen, R * 1.08, 0.08 * s, col(0.6f), al * 0.18f * g, d);
        glow(cen, 0.06 * s, col(0.3f), al * g * (0.45f + 0.3f * pl), d);
        // inner swirl: two arcs rotating in the sphere
        for (int k = 0; k < 3; k++) {
            Quaternionf q = new Quaternionf().rotateY((float) (ang * (0.8 + k * 0.3) + k * 2.09)).rotateX((float) (0.9 + k * 0.5));
            Vec3d u = tf(q, 1, 0, 0), v = tf(q, 0, 0, 1);
            double r = R * (0.55 + k * 0.12);
            List<Vec3d> pts = new ArrayList<>();
            int n = 30;
            float[] w = new float[n + 1];
            int[] cs = new int[n + 1];
            for (int i = 0; i <= n; i++) {
                float f = i / (float) n;
                double an = f * Math.PI * 1.3;
                pts.add(cen.add(u.multiply(Math.cos(an) * r)).add(v.multiply(Math.sin(an) * r)));
                float taper = (float) Math.sin(Math.PI * f);
                w[i] = (float) (0.014 * s * taper);
                cs[i] = a(col(k / 2f), al * 0.7f * taper);
            }
            glowRibbon(pts, w, cs, g, d);
        }
        // specular highlight (top left, camera space)
        Vec3d hl = cen.add(VFx.up.multiply(R * 0.45)).subtract(VFx.right.multiply(R * 0.4)).subtract(VFx.fwd.multiply(R * 0.3));
        sprite(hl, R * 0.28, WHITE, al * 0.35f, d);
        sprite(hl, R * 0.1, WHITE, al * 0.9f, d);
        // little satellites
        for (int k = 0; k < 3; k++) {
            double an = t * 1.4 + k * Math.PI * 2 / 3;
            Vec3d p = cen.add(Math.cos(an) * R * 1.6, Math.sin(an * 1.3 + k) * R * 0.5, Math.sin(an) * R * 1.6);
            glow(p, 0.02 * s, col(k / 2f), al, d);
        }
    }

    private void base(Vec3d pos, double t, float al, boolean d) {
        Vec3d c = pos.add(0, 0.125, 0);
        EvenMoreVisuals.box(c, new Vec3d(0.5, 0, 0), new Vec3d(0, 0.125, 0), new Vec3d(0, 0, 0.5), a(0xFF0B0C10, 0.88f * al), a(0xFFFFFFFF, 0.35f * al), d);
        Vec3d top = pos.add(0, 0.255, 0);
        VFx.ring(top, VFx.X, VFx.Z, 0.34, 0.025, 48, x -> col(x), al * 0.9f, (float) (t * 0.6), d);
        VFx.arc(top, VFx.X, VFx.Z, 0.42, 0.02, t * 1.5, t * 1.5 + 1.6, 24, x -> WHITE, al * 0.8f, d);
        VFx.arc(top, VFx.X, VFx.Z, 0.42, 0.02, t * 1.5 + Math.PI, t * 1.5 + Math.PI + 1.6, 24, x -> WHITE, al * 0.8f, d);
    }

    private void beam(Vec3d from, Vec3d to, double t, float al, boolean d) {
        int c = col(0.6f);
        Vec3d dir = to.subtract(from);
        double len = dir.length();
        if (len < 0.1) return;
        glowLine(from, to, 0.03, c, al * 0.75f, d);
        // helix strand + travelling pulses
        Vec3d n = dir.normalize();
        Vec3d[] uv = basis(n);
        int seg = (int) Math.min(240, len * 6);
        List<Vec3d> pts = new ArrayList<>();
        float[] w = new float[seg + 1];
        int[] cs = new int[seg + 1];
        for (int i = 0; i <= seg; i++) {
            double f = i / (double) seg, an = f * len * 2.5 - t * 4;
            pts.add(from.add(dir.multiply(f)).add(uv[0].multiply(Math.cos(an) * 0.09)).add(uv[1].multiply(Math.sin(an) * 0.09)));
            w[i] = 0.012f;
            cs[i] = a(mix(c, WHITE, 0.3f), al * 0.55f);
        }
        ribbon(pts, w, cs, d);
        for (int k = 0; k < 4; k++) {
            double f = ((t * 0.5 + k / 4.0) % 1);
            glow(from.add(dir.multiply(f)), 0.04, c, al * (float) Math.sin(Math.PI * f), d);
        }
    }

}

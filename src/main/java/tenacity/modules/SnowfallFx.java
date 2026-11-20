package tenacity.modules;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import tenacity.core.Setting;
import tenacity.gfx.Nvg;
import tenacity.util.Projection;
import voided.util.render.Render3D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static tenacity.modules.PremiumVisuals.*;

/**
 * Snowfall FX: layered, world anchored, depth tested snowflakes (soft discs, plates and six armed dendrites with
 * flutter / tumble and gusty wind), purely visual snow accumulation on exposed top faces (smoothed heightfield with
 * rounded edges, gradual growth + melt), footprints, glinting ice crystals, storm fog and a cold tint.
 * Never touches the world or sends packets.
 */
final class SnowfallFx extends PV {
    private final Setting.Bool snowing = bool("Snowfall", true);
    private final Setting.Mode preset = mode("Preset", "Heavy", "Light", "Heavy", "Blizzard");
    private final Setting.Num density = num("Density", 1, 0.2, 2.5, 0.05);
    private final Setting.Num flakeSize = num("Flake Size", 1, 0.4, 2.5, 0.05);
    private final Setting.Num fallSpeed = num("Fall Speed", 1, 0.3, 2.5, 0.05);
    private final Setting.Num windStr = num("Wind Strength", 1, 0, 3, 0.05);
    private final Setting.Num windDir = num("Wind Direction", 45, 0, 359, 1);
    private final Setting.Bool gusts = bool("Gusts", true);
    private final Setting.Bool accum = bool("Accumulation", true);
    private final Setting.Num accSpeed = num("Accumulation Speed", 1, 0.1, 10, 0.1);
    private final Setting.Num maxDepth = num("Max Depth", 0.25, 0.03, 0.8, 0.01);
    private final Setting.Bool melting = bool("Melting", true);
    private final Setting.Num meltSpeed = num("Melt Speed", 1, 0.1, 10, 0.1);
    private final Setting.Bool footprints = bool("Footprints", true);
    private final Setting.Bool fog = bool("Storm Fog", true);
    private final Setting.Bool sparkles = bool("Ice Sparkles", true);
    private final Setting.Bool cold = bool("Cold Atmosphere", true);
    private final Setting.Num radius = num("Radius", 16, 6, 32, 1);
    private final Setting.Bool perf = bool("Performance Mode", false);

    /** Dev fast-forward multiplier for accumulation (harness only). */
    private static final float FF = parseFF();

    private static float parseFF() {
        try {
            String s = System.getenv("TENACITY_SNOWFF");
            return s == null ? 1 : Float.parseFloat(s);
        } catch (Exception e) {
            return 1;
        }
    }

    // ------------------------------------------------------------------ state

    private static final int MAXF = 6000;
    private final double[] fx = new double[MAXF], fy = new double[MAXF], fz = new double[MAXF];
    private final float[] fs = new float[MAXF], fv = new float[MAXF], fph = new float[MAXF], frot = new float[MAXF], fspin = new float[MAXF], fage = new float[MAXF];
    private final byte[] flay = new byte[MAXF], ftype = new byte[MAXF];
    private int count;
    private final Random rng = new Random();

    /** Smoothed live parameters (preset transitions). */
    private float intensity, pDensity = 1, pWind = 0.4f, pFog, pSpeed = 1, gust, snowState;

    private static final class Col {
        int x, z;
        double surf;            // world y of the top face
        float x0, x1, z0, z1;   // extents of the top face inside the block
        boolean full, valid;
        float depth, cap, light = 1;
        double checked;
    }

    private final Map<Long, Col> cols = new HashMap<>();
    private int scanIdx;

    private static final class Print {
        double x, z;
        float yaw, side;
        double t;
    }

    private final List<Print> prints = new ArrayList<>();
    private final Map<Integer, double[]> lastStep = new HashMap<>();

    SnowfallFx() {
        super("Snowfall FX", "Realistic layered snowfall with wind, visual snow accumulation, footprints, sparkles and storm fog");
    }

    @Override
    float fadeRate() {
        return enabled() ? 2.5f : 0.6f;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private float presetDensity() {
        return preset.is("Light") ? 0.45f : preset.is("Blizzard") ? 1.6f : 1f;
    }

    private float presetWind() {
        return preset.is("Light") ? 0.25f : preset.is("Blizzard") ? 2.4f : 0.6f;
    }

    private float presetFog() {
        return preset.is("Light") ? 0.12f : preset.is("Blizzard") ? 1f : 0.4f;
    }

    private float presetSpeed() {
        return preset.is("Blizzard") ? 1.35f : 1f;
    }

    private static float vnoise(double x, double z) {
        int ix = MathHelper.floor(x), iz = MathHelper.floor(z);
        double fxp = x - ix, fzp = z - iz;
        fxp = fxp * fxp * (3 - 2 * fxp);
        fzp = fzp * fzp * (3 - 2 * fzp);
        float a = h2(ix, iz), b = h2(ix + 1, iz), c = h2(ix, iz + 1), d = h2(ix + 1, iz + 1);
        return (float) MathHelper.lerp(fzp, MathHelper.lerp(fxp, a, b), MathHelper.lerp(fxp, c, d));
    }

    private static float h2(int x, int z) {
        return hash(x * 73856093 ^ z * 19349663);
    }

    private int topY(int x, int z) {
        return mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z);
    }

    private float dayLight() {
        double tod = (mc.world.getTimeOfDay() % 24000) / 24000.0;
        float d = (float) (0.5 + 0.5 * Math.cos((tod - 0.25) * TAU));
        return 0.18f + 0.82f * smooth(d * 1.6f - 0.2f);
    }

    // ------------------------------------------------------------------ simulation

    private Vec3d wind(double t) {
        double an = Math.toRadians(windDir.f());
        double g = 1;
        if (gusts.on()) g = 0.65 + 0.9 * Math.pow(vnoise(t * 0.35, 3.7), 2) * 1.6 + 0.15 * Math.sin(t * 1.7);
        gust = (float) g;
        double s = pWind * windStr.f() * g;
        return new Vec3d(Math.cos(an) * s, 0, Math.sin(an) * s);
    }

    private void spawn(int i, Vec3d cam, boolean top, Vec3d w) {
        float r = rng.nextFloat();
        byte layer = (byte) (r < 0.07f ? 0 : r < 0.55f ? 1 : 2); // 0 near / 1 mid / 2 far
        double R = layer == 0 ? 4 : layer == 1 ? radius.f() * 0.45 : radius.f() * (perf.on() ? 1.0 : 1.3);
        double an = rng.nextDouble() * TAU, rr = layer == 0 ? 0.8 + rng.nextDouble() * (R - 0.8) : Math.sqrt(rng.nextDouble()) * R;
        double H = layer == 0 ? 5 : 14;
        double x = cam.x + Math.cos(an) * rr, z = cam.z + Math.sin(an) * rr;
        double y = top ? cam.y + H * (0.6 + rng.nextDouble() * 0.4) : cam.y - H * 0.6 + rng.nextDouble() * H * 1.6;
        if (top) { // upwind offset so wind drift doesn't empty the windward side
            x -= w.x * 2.5;
            z -= w.z * 2.5;
        }
        fx[i] = x;
        fy[i] = y;
        fz[i] = z;
        flay[i] = layer;
        float sz = layer == 0 ? 0.035f + rng.nextFloat() * 0.035f : layer == 1 ? 0.03f + rng.nextFloat() * rng.nextFloat() * 0.045f : 0.018f + rng.nextFloat() * 0.016f;
        fs[i] = sz;
        float tr = rng.nextFloat();
        ftype[i] = (byte) (layer == 2 ? 0 : tr < 0.55f ? 0 : tr < 0.8f ? 1 : 2); // 0 disc, 1 dendrite, 2 plate
        fv[i] = 0.7f + sz * 8 + rng.nextFloat() * 0.35f; // bigger aggregates fall a bit faster
        fph[i] = rng.nextFloat() * 100;
        frot[i] = rng.nextFloat() * 6.28f;
        fspin[i] = (rng.nextFloat() - 0.5f) * 3.2f;
        fage[i] = 0;
    }

    private void simulate(Vec3d cam, double t, float dt, Vec3d w) {
        float k = ease(dt, 1.2f);
        snowState += ((snowing.on() && enabled() ? 1 : 0) - snowState) * ease(dt, 0.7f);
        pDensity += (presetDensity() - pDensity) * k;
        pWind += (presetWind() - pWind) * k;
        pFog += (presetFog() - pFog) * k;
        pSpeed += (presetSpeed() - pSpeed) * k;
        intensity = snowState * pDensity * density.f();
        int want = Math.min(MAXF, (int) (2600 * intensity * (perf.on() ? 0.45f : 1f)));
        if (count == 0 && want > 0) {
            for (int i = 0; i < want; i++) spawn(i, cam, false, w);
            count = want;
        }
        while (count < want) spawn(count++, cam, true, w);
        if (count > want) count = Math.max(want, count - (int) Math.max(1, count * dt * 1.5f));
        float spd = fallSpeed.f() * pSpeed;
        double fR = radius.f() * (perf.on() ? 1.0 : 1.3), nR = 4;
        for (int i = 0; i < count; i++) {
            fage[i] += dt;
            float ph = fph[i];
            double flutterX = Math.sin(t * 1.9 + ph) * 0.35 + Math.sin(t * 4.3 + ph * 2.1) * 0.08;
            double flutterZ = Math.cos(t * 1.6 + ph * 1.3) * 0.35;
            float lm = flay[i] == 2 ? 1.05f : 1f;
            fy[i] -= (fv[i] * spd * lm + Math.sin(t * 2.6 + ph) * 0.12) * dt;
            fx[i] += (w.x * (1.1 - fs[i] * 6) + flutterX) * dt;
            fz[i] += (w.z * (1.1 - fs[i] * 6) + flutterZ) * dt;
            frot[i] += fspin[i] * dt * (1 + gust * 0.5f);
            double R = flay[i] == 0 ? nR : flay[i] == 1 ? radius.f() * 0.45 : fR;
            double dx = fx[i] - cam.x, dz = fz[i] - cam.z;
            // wrap horizontally (world anchored, density preserved while walking)
            if (dx > R) fx[i] -= 2 * R; else if (dx < -R) fx[i] += 2 * R;
            if (dz > R) fz[i] -= 2 * R; else if (dz < -R) fz[i] += 2 * R;
            double H = flay[i] == 0 ? 5 : 14;
            boolean dead = fy[i] < cam.y - H || fy[i] > cam.y + H * 1.3;
            if (!dead) {
                int ty = topY(MathHelper.floor(fx[i]), MathHelper.floor(fz[i]));
                if (fy[i] < ty) dead = fage[i] > 0.05f || true; // under cover / hit the surface
            }
            if (dead) spawn(i, cam, true, w);
        }
    }

    private void updateColumns(Vec3d pp, double t, float dt) {
        var world = mc.world;
        int R = Math.min(radius.i(), perf.on() ? 12 : 26);
        int px = MathHelper.floor(pp.x), pz = MathHelper.floor(pp.z);
        int side = 2 * R + 1, total = side * side;
        int budget = perf.on() ? 250 : 600;
        BlockPos.Mutable bp = new BlockPos.Mutable();
        for (int n = 0; n < budget; n++) {
            int idx = scanIdx++ % total;
            int x = px - R + idx % side, z = pz - R + idx / side;
            if ((x - px) * (x - px) + (z - pz) * (z - pz) > R * R) continue;
            long kk = key(x, z);
            Col c = cols.get(kk);
            if (c != null && t - c.checked < 1.5) continue;
            if (c == null) {
                c = new Col();
                c.x = x;
                c.z = z;
                c.cap = 0.75f + 0.5f * vnoise(x * 0.31, z * 0.31);
                cols.put(kk, c);
            }
            c.checked = t;
            int ty = topY(x, z);
            bp.set(x, ty - 1, z);
            BlockState st = world.getBlockState(bp);
            double oldSurf = c.surf;
            c.valid = false;
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            if (st.isOf(Blocks.LAVA) || st.isOf(Blocks.FIRE) || st.isOf(Blocks.SOUL_FIRE) || st.isOf(Blocks.MAGMA_BLOCK)
                    || st.isIn(BlockTags.CAMPFIRES) || st.isOf(Blocks.ICE) && false) continue;
            VoxelShape sh = st.getOutlineShape(world, bp);
            if (sh.isEmpty()) continue;
            Box b = sh.getBoundingBox();
            c.surf = bp.getY() + b.maxY;
            c.x0 = (float) b.minX;
            c.x1 = (float) b.maxX;
            c.z0 = (float) b.minZ;
            c.z1 = (float) b.maxZ;
            c.full = b.minX < 0.01 && b.maxX > 0.99 && b.minZ < 0.01 && b.maxZ > 0.99;
            if (Math.abs(oldSurf - c.surf) > 0.01 && c.depth > 0) c.depth = 0; // block changed under the snow
            bp.set(x, ty, z);
            int sky = world.getLightLevel(LightType.SKY, bp), blk = world.getLightLevel(LightType.BLOCK, bp);
            if (blk >= 12) continue; // hot / bright light source melts it
            c.light = Math.max(sky / 15f * dayLight(), blk / 15f * 0.85f);
            c.valid = true;
        }
        float grow = accum.on() ? 0.012f * accSpeed.f() * FF * intensity : 0;
        boolean meltNow = melting.on() && (intensity < 0.05f || !accum.on());
        float melt = 0.02f * meltSpeed.f() * FF;
        float max = maxDepth.f();
        for (Iterator<Col> it = cols.values().iterator(); it.hasNext(); ) {
            Col c = it.next();
            double dx = c.x - pp.x, dz = c.z - pp.z;
            boolean far = dx * dx + dz * dz > (R + 3) * (R + 3);
            if (!c.valid || far && c.depth <= 0) {
                if (far || c.depth <= 0) {
                    if (far || !c.valid && c.depth <= 0) it.remove();
                    continue;
                }
            }
            if (!c.valid) {
                c.depth = Math.max(0, c.depth - 0.2f * dt);
                continue;
            }
            float cap = max * c.cap * (c.full ? 1 : 0.6f);
            if (grow > 0 && c.depth < cap) c.depth = Math.min(cap, c.depth + grow * dt * (0.6f + 0.8f * hash(c.x * 31 + c.z * 7)) * (1 - c.depth / cap * 0.6f));
            if (meltNow) c.depth = Math.max(0, c.depth - melt * dt * (0.7f + 0.6f * hash(c.x * 13 + c.z * 5)));
        }
    }

    private void updatePrints(double t) {
        if (!footprints.on()) {
            prints.clear();
            return;
        }
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (!p.isOnGround() || p.squaredDistanceTo(mc.player) > 30 * 30) continue;
            double[] l = lastStep.computeIfAbsent(p.getId(), k -> new double[]{p.getX(), p.getZ(), 1});
            double dx = p.getX() - l[0], dz = p.getZ() - l[1];
            if (dx * dx + dz * dz < 0.55 * 0.55) continue;
            Col c = cols.get(key(MathHelper.floor(p.getX()), MathHelper.floor(p.getZ())));
            l[0] = p.getX();
            l[1] = p.getZ();
            l[2] = -l[2];
            if (c == null || c.depth < 0.012f) continue;
            Print pr = new Print();
            float yaw = (float) Math.atan2(dz, dx);
            pr.yaw = yaw;
            pr.side = (float) l[2];
            pr.x = p.getX() - Math.sin(yaw) * 0.14 * l[2];
            pr.z = p.getZ() + Math.cos(yaw) * 0.14 * l[2];
            pr.t = t;
            prints.add(pr);
        }
        if (prints.size() > 400) prints.subList(0, prints.size() - 400).clear();
        double life = 60 / (1 + intensity * 2);
        prints.removeIf(p -> t - p.t > life);
    }

    // ------------------------------------------------------------------ rendering

    private Vec3d camR = Vec3d.ZERO, camU = Vec3d.ZERO, camF = Vec3d.ZERO;

    private static void face(Vec3d a, Vec3d b, Vec3d c, Vec3d d, int ca, int cb, int cc, int cd) {
        Render3D.drawGradientQuad(a, b, c, d, ca, cb, cc, cd, true);
    }

    private static void face2(Vec3d a, Vec3d b, Vec3d c, Vec3d d, int ca, int cb, int cc, int cd) {
        Render3D.drawGradientQuad(a, b, c, d, ca, cb, cc, cd, true);
        Render3D.drawGradientQuad(d, c, b, a, cd, cc, cb, ca, true);
    }

    private static int shade(float l, float al) {
        int v = MathHelper.clamp((int) (255 * l), 0, 255);
        int b = MathHelper.clamp((int) (255 * Math.min(1, l * 1.06f + 0.03f)), 0, 255);
        int g = MathHelper.clamp((int) (255 * Math.min(1, l * 1.02f)), 0, 255);
        return Math.round(255 * clamp01(al)) << 24 | v << 16 | g << 8 | b;
    }

    private void drawFlakes(Vec3d cam, float al, float light) {
        double near2 = 2.4 * 2.4;
        for (int i = 0; i < count; i++) {
            double dx = fx[i] - cam.x, dy = fy[i] - cam.y, dz = fz[i] - cam.z;
            double fwd = dx * camF.x + dy * camF.y + dz * camF.z;
            if (fwd < 0.15) continue;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < near2 && flay[i] == 0) continue; // drawn as bokeh in hud
            double d = Math.sqrt(d2);
            // cheap frustum check (fov ~ 110 deg diag)
            if (fwd < d * 0.45) continue;
            float edge = 1 - smooth((float) ((Math.sqrt(dx * dx + dz * dz) - radius.f() * (flay[i] == 2 ? 1.0 : 0.35)) / (radius.f() * 0.35)));
            float a = al * edge * smooth((float) (d - 0.4)) * Math.min(1, fage[i] * 2);
            if (a < 0.02f) continue;
            float sz = fs[i] * flakeSize.f() * (flay[i] == 2 ? (float) Math.max(1, d * 0.04) : 1); // keep far flakes sub-pixel-safe
            Vec3d c = new Vec3d(fx[i], fy[i], fz[i]);
            float rot = frot[i];
            float tumble = (float) Math.abs(Math.cos(fph[i] + fage[i] * fspin[i] * 0.7));
            float sq = 0.35f + 0.65f * tumble;
            double cr = Math.cos(rot), sr = Math.sin(rot);
            Vec3d ax = camR.multiply(cr).add(camU.multiply(sr)), ay = camR.multiply(-sr).add(camU.multiply(cr)).multiply(sq);
            int core = shade(light, a * 0.95f), rim = shade(light, 0);
            if (ftype[i] == 0 || perf.on() || d > 9) {
                // soft disc: 3 fan quads (hexagon) with transparent rim
                if (flay[i] == 2 || perf.on()) {
                    Vec3d p1 = c.add(ax.multiply(sz)), p2 = c.add(ay.multiply(sz)), p3 = c.subtract(ax.multiply(sz)), p4 = c.subtract(ay.multiply(sz));
                    int cc = shade(light, a * 0.75f);
                    face2(p1, p2, p3, p4, cc, cc, cc, cc);
                    continue;
                }
                Vec3d[] r = new Vec3d[7];
                for (int k = 0; k <= 6; k++) {
                    double an = k * TAU / 6;
                    r[k] = c.add(ax.multiply(Math.cos(an) * sz * 1.4)).add(ay.multiply(Math.sin(an) * sz * 1.4));
                }
                for (int k = 0; k < 6; k += 2) face2(c, r[k], r[k + 1], r[k + 2], core, rim, rim, rim);
            } else if (ftype[i] == 1) {
                // dendrite: 3 crossed arms + side branches
                int arm = shade(light, a * 0.9f), tip = shade(light, a * 0.45f);
                double w = sz * 0.16;
                for (int k = 0; k < 3; k++) {
                    double an = k * Math.PI / 3;
                    Vec3d dir = ax.multiply(Math.cos(an)).add(ay.multiply(Math.sin(an)));
                    Vec3d n = ax.multiply(-Math.sin(an)).add(ay.multiply(Math.cos(an))).multiply(w);
                    Vec3d e1 = c.add(dir.multiply(sz * 1.5)), e2 = c.subtract(dir.multiply(sz * 1.5));
                    face2(e2.add(n), e1.add(n), e1.subtract(n), e2.subtract(n), tip, tip, tip, tip);
                    face2(c.add(n), e1.add(n), e1.subtract(n), c.subtract(n), arm, tip, tip, arm);
                    if (d < 6) for (int s = -1; s <= 1; s += 2) {
                        Vec3d base = c.add(dir.multiply(sz * 0.8 * s));
                        for (int b = -1; b <= 1; b += 2) {
                            Vec3d bd = dir.multiply(0.6 * s).add(n.normalize().multiply(0.8 * b)).multiply(sz * 0.55);
                            Vec3d nn = n.multiply(0.7);
                            face2(base.add(nn), base.add(bd).add(nn), base.add(bd).subtract(nn), base.subtract(nn), tip, tip, tip, tip);
                        }
                    }
                }
                Vec3d s1 = ax.multiply(sz * 0.35), s2 = ay.multiply(sz * 0.35);
                face2(c.add(s1), c.add(s2), c.subtract(s1), c.subtract(s2), core, core, core, core);
            } else {
                // hexagonal plate: solid hexagon with brighter rim
                Vec3d[] r = new Vec3d[7];
                for (int k = 0; k <= 6; k++) {
                    double an = k * TAU / 6;
                    r[k] = c.add(ax.multiply(Math.cos(an) * sz)).add(ay.multiply(Math.sin(an) * sz));
                }
                int mid = shade(light * 0.92f, a * 0.55f), edgeC = shade(light, a * 0.95f);
                for (int k = 0; k < 6; k += 2) face2(c, r[k], r[k + 1], r[k + 2], mid, edgeC, edgeC, edgeC);
            }
        }
    }

    /** Continuous per-vertex snow shade (world-space noise, slightly brighter on raised parts). */
    private static int vs(Vec3d p, float L, float alpha) {
        float n = 0.93f + 0.07f * vnoise(p.x * 1.3, p.z * 1.3);
        double fr = p.y - Math.floor(p.y);
        return shade(L * n * (0.97f + (float) Math.min(0.05, fr * 0.2)), alpha);
    }

    private float colDepth(int x, int z, double surf, float al) {
        Col c = cols.get(key(x, z));
        if (c == null || !c.full || Math.abs(c.surf - surf) > 0.01) return -1;
        return c.depth * al;
    }

    private void drawSnow(Vec3d cam, float al, double t) {
        float R = Math.min(radius.f(), perf.on() ? 12 : 26);
        for (Col c : cols.values()) {
            float d = c.depth * al;
            if (d < 0.002f) continue;
            double cx = c.x + 0.5 - cam.x, cy = c.surf - cam.y, cz = c.z + 0.5 - cam.z;
            double dist = Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (dist > 2 && (cx * camF.x + cy * camF.y + cz * camF.z) < -1.0) continue;
            if (cy > 0.05 && false) continue;
            float edgeFade = 1 - smooth((float) ((Math.sqrt(cx * cx + cz * cz) - R + 3) / 3));
            if (edgeFade <= 0) continue;
            float op = clamp01(d / 0.035f) * edgeFade;
            float alpha = 0.35f + 0.62f * op;
            double y0 = c.surf + 0.0015;
            if (!c.full) {
                // small cap on fences / posts / thin shapes with rounded bevel
                double ins = Math.min(0.04, (c.x1 - c.x0) * 0.15);
                double x0 = c.x + c.x0, x1 = c.x + c.x1, z0 = c.z + c.z0, z1 = c.z + c.z1, yt = y0 + d;
                int top = shade(c.light * 0.98f, alpha), sd = shade(c.light * 0.8f, alpha);
                face2(new Vec3d(x0 + ins, yt, z0 + ins), new Vec3d(x1 - ins, yt, z0 + ins), new Vec3d(x1 - ins, yt, z1 - ins), new Vec3d(x0 + ins, yt, z1 - ins), top, top, top, top);
                double ym = y0 + d * 0.55;
                face2(new Vec3d(x0, ym, z0), new Vec3d(x1, ym, z0), new Vec3d(x1 - ins, yt, z0 + ins), new Vec3d(x0 + ins, yt, z0 + ins), sd, sd, top, top);
                face2(new Vec3d(x1, ym, z1), new Vec3d(x0, ym, z1), new Vec3d(x0 + ins, yt, z1 - ins), new Vec3d(x1 - ins, yt, z1 - ins), sd, sd, top, top);
                face2(new Vec3d(x0, ym, z1), new Vec3d(x0, ym, z0), new Vec3d(x0 + ins, yt, z0 + ins), new Vec3d(x0 + ins, yt, z1 - ins), sd, sd, top, top);
                face2(new Vec3d(x1, ym, z0), new Vec3d(x1, ym, z1), new Vec3d(x1 - ins, yt, z1 - ins), new Vec3d(x1 - ins, yt, z0 + ins), sd, sd, top, top);
                continue;
            }
            // corner heights averaged with matching neighbours -> soft rounded edges where snow ends
            float[] ch = new float[4];
            int[][] cn = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int k = 0; k < 4; k++) {
                int ox = cn[k][0], oz = cn[k][1];
                float sum = 0;
                int edges = 0;
                for (int a = -1; a <= 0; a++)
                    for (int b = -1; b <= 0; b++) {
                        float nd = colDepth(c.x + ox + a, c.z + oz + b, c.surf, al);
                        if (nd < 0) edges++;
                        else sum += nd;
                    }
                float avg = sum / 4f;
                float n = 0.82f + 0.36f * vnoise((c.x + ox) * 0.9, (c.z + oz) * 0.9);
                ch[k] = (edges > 0 ? avg * 0.75f : avg) * n;
            }
            float center = d * (0.9f + 0.25f * vnoise(c.x * 1.7 + 0.5, c.z * 1.7 + 0.5));
            double X = c.x, Z = c.z;
            Vec3d p00 = new Vec3d(X, y0 + ch[0], Z), p10 = new Vec3d(X + 1, y0 + ch[1], Z), p11 = new Vec3d(X + 1, y0 + ch[2], Z + 1), p01 = new Vec3d(X, y0 + ch[3], Z + 1);
            float L = c.light;
            if (perf.on() || dist > 18) {
                face2(p00, p10, p11, p01, vs(p00, L, alpha), vs(p10, L, alpha), vs(p11, L, alpha), vs(p01, L, alpha));
            } else {
                Vec3d m = new Vec3d(X + 0.5, y0 + center, Z + 0.5);
                Vec3d e0 = new Vec3d(X + 0.5, y0 + (ch[0] + ch[1]) * 0.5 * 1.04, Z), e1 = new Vec3d(X + 1, y0 + (ch[1] + ch[2]) * 0.5 * 1.04, Z + 0.5);
                Vec3d e2 = new Vec3d(X + 0.5, y0 + (ch[2] + ch[3]) * 0.5 * 1.04, Z + 1), e3 = new Vec3d(X, y0 + (ch[3] + ch[0]) * 0.5 * 1.04, Z + 0.5);
                int cm = vs(m, L, alpha), c00 = vs(p00, L, alpha), c10 = vs(p10, L, alpha), c11 = vs(p11, L, alpha), c01 = vs(p01, L, alpha);
                int ce0 = vs(e0, L, alpha), ce1 = vs(e1, L, alpha), ce2 = vs(e2, L, alpha), ce3 = vs(e3, L, alpha);
                face2(p00, e0, m, e3, c00, ce0, cm, ce3);
                face2(e0, p10, e1, m, ce0, c10, ce1, cm);
                face2(m, e1, p11, e2, cm, ce1, c11, ce2);
                face2(e3, m, e2, p01, ce3, cm, ce2, c01);
            }
            // skirts on edges facing a lower / missing neighbour
            int sd = shade(L * 0.78f, alpha);
            int sb = shade(L * 0.7f, alpha * 0.9f);
            if (colDepth(c.x, c.z - 1, c.surf, al) < 0) face2(new Vec3d(X, y0, Z), new Vec3d(X + 1, y0, Z), p10, p00, sb, sb, sd, sd);
            if (colDepth(c.x + 1, c.z, c.surf, al) < 0) face2(new Vec3d(X + 1, y0, Z), new Vec3d(X + 1, y0, Z + 1), p11, p10, sb, sb, sd, sd);
            if (colDepth(c.x, c.z + 1, c.surf, al) < 0) face2(new Vec3d(X + 1, y0, Z + 1), new Vec3d(X, y0, Z + 1), p01, p11, sb, sb, sd, sd);
            if (colDepth(c.x - 1, c.z, c.surf, al) < 0) face2(new Vec3d(X, y0, Z + 1), new Vec3d(X, y0, Z), p00, p01, sb, sb, sd, sd);
        }
        // footprints: shadowed ovals pressed into the snow, refilling over time
        if (footprints.on()) {
            double life = 60 / (1 + intensity * 2);
            for (Print p : prints) {
                Col c = cols.get(key(MathHelper.floor(p.x), MathHelper.floor(p.z)));
                if (c == null || c.depth * al < 0.01f) continue;
                float f = 1 - (float) ((t - p.t) / life);
                float a = clamp01(f * 1.5f) * Math.min(1, c.depth * al / 0.05f) * 0.75f;
                if (a < 0.02f) continue;
                double y = c.surf + 0.0015 + c.depth * al * (0.95 + 0.25 * vnoise(c.x * 1.7 + 0.5, c.z * 1.7 + 0.5)) * 0.92 + 0.004;
                double cs = Math.cos(p.yaw), sn = Math.sin(p.yaw);
                int in = shade(c.light * 0.62f, a), out = shade(c.light * 0.8f, 0);
                Vec3d ctr = new Vec3d(p.x, y, p.z);
                Vec3d[] r = new Vec3d[9];
                for (int k = 0; k <= 8; k++) {
                    double an = k * TAU / 8, lx = Math.cos(an) * 0.15, lz = Math.sin(an) * 0.075;
                    r[k] = new Vec3d(p.x + lx * cs - lz * sn, y, p.z + lx * sn + lz * cs);
                }
                for (int k = 0; k < 8; k += 2) face2(ctr, r[k], r[k + 1], r[k + 2], in, out, out, out);
            }
        }
    }

    @Override
    void world(float td, double t, float dt, float al) {
        if (mc.world == null || mc.player == null) return;
        var camE = mc.gameRenderer.getCamera();
        Vec3d cam = Projection.camera();
        camF = Vec3d.fromPolar(camE.getPitch(), camE.getYaw());
        camR = camF.crossProduct(new Vec3d(0, 1, 0)).normalize();
        if (camR.lengthSquared() < 1e-4) camR = new Vec3d(1, 0, 0);
        camU = camR.crossProduct(camF).normalize();
        Vec3d w = wind(t);
        simulate(cam, t, dt, w);
        updateColumns(mc.player.getLerpedPos(td), t, dt);
        updatePrints(t);
        BlockPos cp = BlockPos.ofFloored(cam);
        float light = Math.max(mc.world.getLightLevel(LightType.SKY, cp) / 15f * dayLight(), mc.world.getLightLevel(LightType.BLOCK, cp) / 15f * 0.8f);
        light = Math.max(0.15f, light);
        lastLight = light;
        drawSnow(cam, al, t);
        drawFlakes(cam, al, light);
    }

    private float lastLight = 1;

    @Override
    void hud(float td, double t, float dt, float al) {
        if (mc.world == null) return;
        float W = Nvg.w, H = Nvg.h;
        Vec3d cam = Projection.camera();
        float L = lastLight;
        BlockPos cp = BlockPos.ofFloored(cam);
        boolean exposed = cam.y >= topY(cp.getX(), cp.getZ()) - 0.5;
        float expo = exposed ? 1 : 0.35f;
        // storm fog: veil thicker toward the horizon + slight overall haze
        if (fog.on()) {
            float f = pFog * snowState * al * expo * (0.75f + 0.25f * gust);
            int fc = shade(L * 0.95f, 1) & 0xFFFFFF;
            Nvg.rect(0, 0, W, H, a(fc, 0.10f * f));
            float hy = H * (0.5f + 0.25f * (float) Math.sin(Math.toRadians(mc.gameRenderer.getCamera().getPitch())));
            Nvg.rrectGradient(0, hy - H * 0.35f, W, H * 0.35f, 0, 0, hy - H * 0.35f, 0, hy, a(fc, 0), a(fc, 0.30f * f));
            Nvg.rrectGradient(0, hy, W, H * 0.3f, 0, 0, hy, 0, hy + H * 0.3f, a(fc, 0.30f * f), a(fc, 0));
        }
        if (cold.on()) {
            Nvg.rect(0, 0, W, H, a(0x6FA8FF, 0.07f * al));
            Nvg.rrectGradient(0, 0, W, H * 0.3f, 0, 0, 0, 0, H * 0.3f, a(0x8FC4FF, 0.06f * al), a(0x8FC4FF, 0));
        }
        Nvg.additive(true);
        float[] s = new float[2];
        // near layer: big soft out of focus flakes
        for (int i = 0; i < count; i++) {
            if (flay[i] != 0) continue;
            double dx = fx[i] - cam.x, dy = fy[i] - cam.y, dz = fz[i] - cam.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > 2.4 * 2.4 || d2 < 0.04) continue;
            Vec3d p = new Vec3d(fx[i], fy[i], fz[i]);
            if (!Projection.point(p, s)) continue;
            float d = (float) Math.sqrt(d2);
            float base = ppb(p);
            if (base <= 0) continue;
            float r = base * fs[i] * flakeSize.f() * 2.2f;
            float blur = 1 + (2.4f - d) * 0.9f;
            float aa = al * Math.min(1, fage[i] * 2) * smooth((d - 0.25f) / 0.6f) * 0.32f / blur;
            Nvg.glowDisc(s[0], s[1], r * blur, a(shade(L, 1), aa), a(shade(L, 1), 0));
        }
        // ice sparkles: per column glints that flash with view angle
        if (sparkles.on() && !perf.on()) {
            Vec3d look = camF;
            for (Col c : cols.values()) {
                float d = c.depth * al;
                if (d < 0.015f) continue;
                double dx = c.x + 0.5 - cam.x, dz = c.z + 0.5 - cam.z;
                if (dx * dx + dz * dz > 14 * 14) continue;
                for (int g = 0; g < 3; g++) {
                    int hi = c.x * 92837111 ^ c.z * 689287499 ^ g * 283923481;
                    double px = c.x + 0.1 + hash(hi) * 0.8, pz = c.z + 0.1 + hash(hi + 1) * 0.8;
                    Vec3d p = new Vec3d(px, c.surf + d * 0.95 + 0.004, pz);
                    Vec3d v = p.subtract(cam);
                    double dist = v.length();
                    double facet = hash(hi + 2) * 40 + (v.x / dist) * 26 + (v.z / dist) * 21 + v.y / dist * 14;
                    float glint = (float) Math.pow(Math.max(0, Math.sin(facet + t * 0.6)), 60);
                    if (glint < 0.05f || !Projection.point(p, s)) continue;
                    float b = glint * al * c.light * (1 - (float) dist / 14f);
                    float px2 = Math.max(0.9f, ppb(p) * 0.012f);
                    Nvg.glowDisc(s[0], s[1], px2 * 3.5f, a(0xDDEEFF, 0.45f * b), a(0xDDEEFF, 0));
                    Nvg.glowDisc(s[0], s[1], px2, a(WHITE, b), a(WHITE, 0));
                    if (b > 0.5f) {
                        Nvg.line(s[0] - px2 * 4, s[1], s[0] + px2 * 4, s[1], 0.7f, a(WHITE, b * 0.5f));
                        Nvg.line(s[0], s[1] - px2 * 4, s[0], s[1] + px2 * 4, 0.7f, a(WHITE, b * 0.5f));
                    }
                }
            }
        }
        Nvg.additive(false);
    }

    @Override
    void onFadedOut() {
        count = 0;
        cols.clear();
        prints.clear();
        lastStep.clear();
        snowState = 0;
    }
}

package tenacity.modules;

import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.Nvg;
import tenacity.gfx.WorldFx;
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

/** More render visuals: Crypto style jump circles / target ESP / storage ESP, world shaders and effects. */
final class VisualModules {
    private VisualModules() {
    }

    static List<Module> create() {
        return List.of(new CryptoJumpWaves(), new CryptoTargetEsp(), new StorageEsp(), new Arrows(), new ChinaHat(), new StepTiles(),
                new Fireflies(), new Trails(), new HitMarker(), new Particles(), new ShaderSky(), new MotionBlur());
    }

    /* ------------------------------------------------------------------ shared */

    static final net.minecraft.client.MinecraftClient MC = net.minecraft.client.MinecraftClient.getInstance();
    /** Last entity you hit and when (Target ESP / Hit Marker). */
    static Entity lastTarget;
    static long lastHit;

    static {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient()) {
                lastTarget = entity;
                lastHit = System.currentTimeMillis();
                HitMarker.hits.add(new long[]{lastHit, 0});
                if (RenderModules.KillEffects.I != null) RenderModules.KillEffects.I.hit(entity);
            }
            return ActionResult.PASS;
        });
    }

    static int palette(Setting.Mode m, float t) {
        return switch (m.get()) {
            case "Purple" -> Nvg.mix(0xFFAA8CFF, 0xFFFFFFFF, t);
            case "Ice" -> Nvg.mix(0xFF6EC8FF, 0xFFFFFFFF, t);
            case "Rose" -> Nvg.mix(0xFFFF5A8C, 0xFFFFC8DC, t);
            case "Chroma" -> 0xFF000000 | java.awt.Color.HSBtoRGB(((System.currentTimeMillis() % 4000) / 4000f + t * 0.3f) % 1f, 0.6f, 1f);
            default -> Nvg.mix(0xFFFFFFFF, 0xFF9AA0AA, t); // White
        };
    }

    static Setting.Mode colors(Module m) {
        return m.mode("Color", "White", "White", "Purple", "Ice", "Rose", "Chroma");
    }

    /** A world-space ring (flat, at y) drawn as a band of segments with a colour gradient around it. */
    static void ring(Vec3d c, double r0, double r1, int seg, float rot, Setting.Mode col, float alpha, boolean additive) {
        float[] a = new float[2], b = new float[2], d = new float[2], e = new float[2];
        if (additive) Nvg.additive(true);
        for (int i = 0; i < seg; i++) {
            double t0 = i * Math.PI * 2 / seg + rot, t1 = (i + 1) * Math.PI * 2 / seg + rot;
            Vec3d p0 = c.add(Math.cos(t0) * r1, 0, Math.sin(t0) * r1), p1 = c.add(Math.cos(t1) * r1, 0, Math.sin(t1) * r1);
            Vec3d q0 = c.add(Math.cos(t0) * r0, 0, Math.sin(t0) * r0), q1 = c.add(Math.cos(t1) * r0, 0, Math.sin(t1) * r0);
            if (!Projection.point(p0, a) || !Projection.point(p1, b) || !Projection.point(q1, d) || !Projection.point(q0, e)) continue;
            float k = (float) (0.5 + 0.5 * Math.sin(i * Math.PI * 2 / seg));
            Nvg.beginPath();
            Nvg.moveTo(a[0], a[1]);
            Nvg.lineTo(b[0], b[1]);
            Nvg.lineTo(d[0], d[1]);
            Nvg.lineTo(e[0], e[1]);
            Nvg.closePath();
            Nvg.fillPath(Nvg.alpha(palette(col, k), alpha));
        }
        if (additive) Nvg.additive(false);
    }

    /**
     * Flat world-space band with the reference ring's mirrored two tone gradient (c1 -> c2 -> c1 around the circle),
     * drawn segment by segment so it rotates smoothly under the feet.
     */
    static void gradientBand(Vec3d c, double r0, double r1, int seg, float rot, int c1, int c2, float alpha, boolean additive) {
        if (alpha <= 0.004f) return;
        float[] a = new float[2], b = new float[2], d = new float[2], e = new float[2];
        if (additive) Nvg.additive(true);
        for (int i = 0; i < seg; i++) {
            double t0 = i * Math.PI * 2 / seg + rot, t1 = (i + 1) * Math.PI * 2 / seg + rot;
            Vec3d p0 = c.add(Math.cos(t0) * r1, 0, Math.sin(t0) * r1), p1 = c.add(Math.cos(t1) * r1, 0, Math.sin(t1) * r1);
            Vec3d q0 = c.add(Math.cos(t0) * r0, 0, Math.sin(t0) * r0), q1 = c.add(Math.cos(t1) * r0, 0, Math.sin(t1) * r0);
            if (!Projection.point(p0, a) || !Projection.point(p1, b) || !Projection.point(q1, d) || !Projection.point(q0, e)) continue;
            float t = (i + 0.5f) / seg;
            float k = t <= 0.5f ? t * 2f : (1f - t) * 2f;
            Nvg.beginPath();
            Nvg.moveTo(a[0], a[1]);
            Nvg.lineTo(b[0], b[1]);
            Nvg.lineTo(d[0], d[1]);
            Nvg.lineTo(e[0], e[1]);
            Nvg.closePath();
            Nvg.fillPath(Nvg.alpha(Nvg.mix(c1, c2, k), alpha));
        }
        if (additive) Nvg.additive(false);
    }

    static float pixels(Vec3d at, double r) {
        float[] a = new float[2], b = new float[2];
        if (!Projection.point(at, a) || !Projection.point(at.add(0, r, 0), b)) return 0;
        return (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    static void glowDot(Vec3d p, double r, int color, float alpha) {
        float[] a = new float[2];
        if (!Projection.point(p, a)) return;
        float px = pixels(p, r);
        if (px < 0.5f) return;
        bloom(a[0], a[1], px, color, alpha);
    }

    /** Real bloom look: a wide faint halo, a mid glow, a coloured body and a hot white core, all additive. */
    static void bloom(float x, float y, float px, int color, float alpha) {
        Nvg.additive(true);
        Nvg.glowDisc(x, y, px * 9f, Nvg.alpha(color, 0.10f * alpha), Nvg.alpha(color, 0));
        Nvg.glowDisc(x, y, px * 4.5f, Nvg.alpha(color, 0.22f * alpha), Nvg.alpha(color, 0));
        Nvg.glowDisc(x, y, px * 2f, Nvg.alpha(color, 0.55f * alpha), Nvg.alpha(color, 0));
        Nvg.glowDisc(x, y, px, Nvg.alpha(0xFFFFFFFF, alpha), Nvg.alpha(color, 0.5f * alpha));
        Nvg.glowDisc(x, y, px * 0.45f, Nvg.alpha(0xFFFFFFFF, alpha), Nvg.alpha(0xFFFFFFFF, 0.6f * alpha));
        Nvg.additive(false);
    }

    /* ------------------------------------------------------------------ jump circles (Crypto) */

    /* ------------------------------------------------------------------ target esp (Crypto style) */

    /* ------------------------------------------------------------------ storage esp (Crypto colours) */

    static final class StorageEsp extends Module {
        private final Setting.Num range = num("Range", 8, 2, 16, 1);
        private final Setting.Bool fill = bool("Fill", true);
        private final Setting.Bool outline = bool("Outline", true);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Bool tracers = bool("Tracers", true);
        private final Setting.Bool spawnerLabels = bool("Spawner Labels", true);
        private final List<Object[]> found = new ArrayList<>(); // BlockPos, color, label
        private static final Map<Block, Integer> COLORS = new HashMap<>();
        private int clock;

        static {
            COLORS.put(Blocks.CHEST, 0xFFD28C3C);
            COLORS.put(Blocks.TRAPPED_CHEST, 0xFFD25A3C);
            COLORS.put(Blocks.ENDER_CHEST, 0xFF8C50DC);
            COLORS.put(Blocks.BARREL, 0xFFC88264);
            COLORS.put(Blocks.SPAWNER, 0xFFA096B4);
            COLORS.put(Blocks.FURNACE, 0xFF969696);
            COLORS.put(Blocks.BLAST_FURNACE, 0xFF969696);
            COLORS.put(Blocks.SMOKER, 0xFF969696);
            COLORS.put(Blocks.HOPPER, 0xFF787878);
            COLORS.put(Blocks.ENCHANTING_TABLE, 0xFF6464F0);
            COLORS.put(Blocks.DISPENSER, 0xFF64B4A0);
            COLORS.put(Blocks.DROPPER, 0xFF64B4A0);
            COLORS.put(Blocks.BREWING_STAND, 0xFF64B4A0);
            COLORS.put(Blocks.CRAFTER, 0xFF64B4A0);
        }

        StorageEsp() {
            super("Storage ESP", "Coloured boxes on chests, barrels, shulkers, spawners and more", Category.RENDER);
        }

        @Override
        public void tick() {
            if (clock++ % 20 != 0) return;
            found.clear();
            int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4, r = range.i();
            for (int cx = pcx - r; cx <= pcx + r; cx++)
                for (int cz = pcz - r; cz <= pcz + r; cz++) {
                    WorldChunk ch = mc.world.getChunkManager().getWorldChunk(cx, cz);
                    if (ch == null) continue;
                    for (BlockEntity be : ch.getBlockEntities().values()) {
                        Block b = be.getCachedState().getBlock();
                        Integer col = b instanceof ShulkerBoxBlock ? Integer.valueOf(0xFFA03CB4) : COLORS.get(b);
                        if (col == null) continue;
                        String label = null;
                        if (be instanceof MobSpawnerBlockEntity sp) {
                            try {
                                Entity e = sp.getLogic().getRenderedEntity(mc.world, be.getPos());
                                label = e != null ? e.getType().getName().getString() : "Empty";
                            } catch (Exception ex) {
                                label = "Unknown";
                            }
                        }
                        Box box = new Box(be.getPos()).contract(0.04);
                        var st = be.getCachedState();
                        // double chest: one merged box and one tracer, owned by the RIGHT half only
                        if (st.contains(net.minecraft.block.ChestBlock.CHEST_TYPE)
                                && st.get(net.minecraft.block.ChestBlock.CHEST_TYPE) != net.minecraft.block.enums.ChestType.SINGLE) {
                            if (st.get(net.minecraft.block.ChestBlock.CHEST_TYPE) == net.minecraft.block.enums.ChestType.LEFT) continue;
                            BlockPos other = be.getPos().offset(net.minecraft.block.ChestBlock.getFacing(st));
                            box = box.union(new Box(other).contract(0.04));
                        }
                        found.add(new Object[]{be.getPos(), col, label, box});
                    }
                }
        }

        @Override
        public void render2d(float td) {
            float[] o = new float[2], q = new float[2];
            for (Object[] f : found) {
                BlockPos p = (BlockPos) f[0];
                int col = (int) f[1];
                Box b = (Box) f[3];
                // Crypto's BlockEsp tracer: from a touch in front of the camera (the crosshair) to the block centre, in
                // the storage block's own colour
                if (tracers.on()) {
                    var cam = mc.gameRenderer.getCamera();
                    Vec3d from = Projection.camera().add(Vec3d.fromPolar(cam.getPitch(), cam.getYaw()).multiply(0.55));
                    if (Projection.segment(from, b.getCenter(), o, q))
                        RenderModules.glowLine(o[0], o[1], q[0], q[1], 1.2f, Nvg.alpha(col, 0.9f), glow.on() ? 0.5f : 0f);
                }
                if (glow.on()) {
                    float[] r = Projection.box(b);
                    if (r != null) {
                        Nvg.additive(true);
                        float pad = Math.max(4, (r[2] - r[0]) * 0.25f);
                        Nvg.glowDisc((r[0] + r[2]) / 2, (r[1] + r[3]) / 2, Math.max(r[2] - r[0], r[3] - r[1]) / 2 + pad, Nvg.alpha(col, 0.18f), Nvg.alpha(col, 0));
                        Nvg.additive(false);
                    }
                }
                if (fill.on()) boxFaces(b, Nvg.alpha(col, 0.16f));
                if (outline.on()) RenderModules.box3d(b, 1.4f, Nvg.alpha(col, 0.95f));
                if (spawnerLabels.on() && f[2] != null) {
                    // bob gently above the cage, ease out with distance instead of popping
                    double bob = 0.06 * Math.sin(System.nanoTime() / 1e9 * 1.6 + p.getX() * 0.7 + p.getZ() * 0.3);
                    Vec3d at = Vec3d.ofCenter(p).add(0, 0.95 + bob, 0);
                    double dist = at.distanceTo(Projection.camera());
                    float far = range.f() * 16;
                    float fade = (float) MathHelper.clamp(1 - (dist - far * 0.55) / (far * 0.45), 0, 1);
                    fade = fade * fade * (3 - 2 * fade);
                    float k = (float) MathHelper.clamp(1.15 - dist / 90, 0.75, 1.1);
                    if (fade > 0.02f && Projection.point(at, o)) spawnerLabel((String) f[2], o[0], o[1], fade, k);
                }
            }
        }
    }

    /** Liquid glass pill for spawners: glowing frost dot, mob name and a dim "Spawner" tag. */
    static void spawnerLabel(String mob, float cx, float cy, float a, float k) {
        float size = 10.5f * k, small = 8.5f * k;
        String tag = "Spawner";
        float tw = Nvg.textWidth(mob, size, Nvg.MED), sw = Nvg.textWidth(tag, small, Nvg.MED);
        float dot = 5.5f * k, pad = 8 * k, gap = 5 * k;
        float w = pad + dot + gap + tw + gap + sw + pad, h = 19 * k;
        float x = cx - w / 2, y = cy - h / 2;
        tenacity.ui.Theme.hud(x, y, w, h, h / 2, a, 0);
        float dx = x + pad + dot / 2;
        double pulse = 0.75 + 0.25 * Math.sin(System.nanoTime() / 1e9 * 3);
        Nvg.additive(true);
        Nvg.glowDisc(dx, cy, dot * 1.9f, Nvg.alpha(0xFFF3F5F8, (float) (0.5 * pulse) * a), Nvg.alpha(0xFFF3F5F8, 0));
        Nvg.additive(false);
        Nvg.circle(dx, cy, dot / 2, Nvg.alpha(0xFFFFFFFF, a));
        float tx = dx + dot / 2 + gap;
        Nvg.text(mob, tx, cy + 0.5f, size, Nvg.MED, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, a));
        Nvg.text(tag, tx + tw + gap, cy + 0.5f, small, Nvg.MED, Nvg.LEFT, Nvg.alpha(0xFFFFFFFF, 0.55f * a));
    }

    /** The six faces of a box, projected and filled. */
    static void boxFaces(Box b, int color) {
        Vec3d[] c = new Vec3d[8];
        for (int i = 0; i < 8; i++) c[i] = new Vec3d((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
        int[][] faces = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        float[] a = new float[2];
        for (int[] f : faces) {
            Nvg.beginPath();
            boolean ok = true;
            for (int k = 0; k < 4 && ok; k++) {
                if (!Projection.point(c[f[k]], a)) ok = false;
                else if (k == 0) Nvg.moveTo(a[0], a[1]);
                else Nvg.lineTo(a[0], a[1]);
            }
            if (!ok) continue;
            Nvg.closePath();
            Nvg.fillPath(color);
        }
    }

    /* ------------------------------------------------------------------ arrows */

    static final class Arrows extends Module {
        private final Setting.Num radius = num("Distance", 70, 30, 220, 5);
        private final Setting.Num size = num("Size", 10, 6, 20, 1);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Bool gradient = bool("Gradient", true);
        private final Setting.Bool onlyOffscreen = bool("Only Off Screen", false);
        private final Setting.Mode color = colors(this);
        private final Map<Integer, Float> anim = new HashMap<>();
        private float ax0 = -1, ay0 = -1;

        Arrows() {
            super("Arrows", "Arrows around the crosshair pointing at players", Category.RENDER);
        }

        @Override
        public void render2d(float td) {
            // anchor on your player, not the crosshair: matches in third person and while freecamming
            // first person: around the crosshair (your own body projects behind the camera); third person / freecam: on your player
            float[] pl = new float[2];
            float cx = Nvg.w / 2f, cy = Nvg.h / 2f;
            boolean detached = !mc.options.getPerspective().isFirstPerson() || CryptoFreeCam.on() || tenacity.gfx.FreecamFx.freecam;
            if (detached && Projection.point(mc.player.getLerpedPos(td).add(0, mc.player.getHeight() * 0.5, 0), pl)
                    && pl[0] > 0 && pl[0] < Nvg.w && pl[1] > 0 && pl[1] < Nvg.h) {
                ax0 = ax0 < 0 ? pl[0] : ax0 + (pl[0] - ax0) * 0.35f; // glide, no jitter
                ay0 = ay0 < 0 ? pl[1] : ay0 + (pl[1] - ay0) * 0.35f;
            } else {
                ax0 = ax0 < 0 ? cx : ax0 + (cx - ax0) * 0.35f;
                ay0 = ay0 < 0 ? cy : ay0 + (cy - ay0) * 0.35f;
            }
            cx = ax0;
            cy = ay0;
            float[] o = new float[2];
            Map<Integer, Float> next = new HashMap<>();
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player || !p.isAlive()) continue;
                Vec3d target = p.getLerpedPos(td).add(0, p.getHeight() / 2, 0);
                boolean onScreen = Projection.point(target, o) && o[0] > 0 && o[0] < Nvg.w && o[1] > 0 && o[1] < Nvg.h;
                float a = tenacity.ui.Anim.approach(anim.getOrDefault(p.getId(), 0f), onlyOffscreen.on() && onScreen ? 0f : 1f, 10f);
                next.put(p.getId(), a);
                if (a < 0.02f) continue;
                // angle in the horizontal plane relative to where you look
                Vec3d d = target.subtract(mc.player.getLerpedPos(td));
                double yaw = Math.toRadians(MathHelper.lerpAngleDegrees(td, mc.player.lastYaw, mc.player.getYaw()));
                double ang = Math.atan2(d.z, d.x) - yaw - Math.PI; // straight ahead = up on screen
                float dist = (float) d.length();
                float r = radius.f() + (1 - a) * 20;
                float ax = cx + (float) Math.cos(ang) * r, ay = cy + (float) Math.sin(ang) * r;
                float s = size.f() * (0.8f + 0.4f * (float) MathHelper.clamp(1 - dist / 60.0, 0, 1));
                int c1 = palette(color, 0f), c2 = palette(color, 1f);
                Nvg.save();
                Nvg.translate(ax, ay);
                Nvg.rotate((float) ang);
                if (glow.on()) {
                    Nvg.additive(true);
                    Nvg.glowDisc(0, 0, s * 2.2f, Nvg.alpha(c1, 0.35f * a), Nvg.alpha(c1, 0));
                    Nvg.additive(false);
                }
                Nvg.beginPath();
                Nvg.moveTo(s, 0);
                Nvg.lineTo(-s * 0.8f, -s * 0.75f);
                Nvg.lineTo(-s * 0.35f, 0);
                Nvg.lineTo(-s * 0.8f, s * 0.75f);
                Nvg.closePath();
                if (gradient.on()) Nvg.fillPathGradient(s, 0, -s, 0, Nvg.alpha(c1, a), Nvg.alpha(c2, 0.55f * a));
                else Nvg.fillPath(Nvg.alpha(c1, a));
                Nvg.restore();
            }
            anim.clear();
            anim.putAll(next);
        }
    }

    /* ------------------------------------------------------------------ china hat */

    static final class ChinaHat extends Module {
        private final Setting.Bool fill = bool("Fill", true);
        private final Setting.Bool outline = bool("Outline", true);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Num glowSize = num("Glow Size", 1, 0.3, 2.5, 0.1).showIf(glow::on);
        private final Setting.Bool shine = bool("Shine", true);
        private final Setting.Bool others = bool("Other Players", false);
        private final Setting.Num height = num("Height", 0.3, 0.1, 0.8, 0.05);
        private final Setting.Num size = num("Size", 0.65, 0.3, 1.2, 0.05);
        private final Setting.Num opacity = num("Opacity", 45, 10, 90, 5);
        private final Setting.Num spin = num("Gradient Speed", 0.5, 0, 3, 0.1);
        private final Setting.Mode color = colors(this);

        ChinaHat() {
            super("China Hat", "A smooth gradient glass cone hat with a soft glow", Category.RENDER);
        }

        private static int a(int c, float alpha) {
            return Math.round(255 * Math.max(0, Math.min(1, alpha))) << 24 | (c & 0xFFFFFF);
        }

        private static int mixc(int x, int y, float t) {
            int r = Math.round(((x >> 16) & 255) + (((y >> 16) & 255) - ((x >> 16) & 255)) * t);
            int g = Math.round(((x >> 8) & 255) + (((y >> 8) & 255) - ((x >> 8) & 255)) * t);
            int b = Math.round((x & 255) + ((y & 255) - (x & 255)) * t);
            return 0xFF000000 | r << 16 | g << 8 | b;
        }

        private void hat(PlayerEntity p, float td, double time) {
            BodyAnchor ba = BodyAnchor.of(p, td);
            Vec3d base = ba.headTop(0.04);
            Vec3d hu = ba.hUp, hr = ba.hRight, hf = ba.hFwd;
            float yaw = 0;
            int seg = 72;
            double r = size.f(), hh = height.f();
            Vec3d tip = base.add(hu.multiply(hh));
            float op = opacity.f() / 100f;
            // colours: a seamless loop around the rim (sin of the angle), drifting over time
            int[] cols = new int[seg + 1];
            for (int i = 0; i <= seg; i++) {
                float t = (float) (0.5 + 0.5 * Math.sin(i * Math.PI * 2 / seg + time * spin.f() * 2));
                cols[i] = palette(color, t);
            }
            Vec3d[] rim = new Vec3d[seg + 1];
            for (int i = 0; i <= seg; i++) {
                double an = i * Math.PI * 2 / seg + yaw;
                rim[i] = base.add(hr.multiply(Math.cos(an) * r)).add(hf.multiply(Math.sin(an) * r));
            }
            if (glow.on()) {
                // one soft halo, not copies: a gradient band from the brim outwards (fades to nothing), plus a faint
                // glow skirt hanging under the brim and a glow along the cone's surface toward the tip
                double gw = 0.32 * glowSize.f();
                for (int i = 0; i < seg; i++) {
                    Vec3d d0 = rim[i].subtract(base).normalize(), d1 = rim[i + 1].subtract(base).normalize();
                    Vec3d o0 = rim[i].add(d0.multiply(gw)).add(hu.multiply(-gw * 0.15)), o1 = rim[i + 1].add(d1.multiply(gw)).add(hu.multiply(-gw * 0.15));
                    int c0 = a(cols[i], 0.42f * op), c1 = a(cols[i + 1], 0.42f * op);
                    Render3D.drawGradientQuad(rim[i], rim[i + 1], o1, o0, c0, c1, cols[i + 1] & 0xFFFFFF, cols[i] & 0xFFFFFF, false);
                    Vec3d s0 = rim[i].add(hu.multiply(-gw * 0.7)), s1 = rim[i + 1].add(hu.multiply(-gw * 0.7));
                    Render3D.drawGradientQuad(rim[i], rim[i + 1], s1, s0, a(cols[i], 0.22f * op), a(cols[i + 1], 0.22f * op), cols[i + 1] & 0xFFFFFF, cols[i] & 0xFFFFFF, false);
                }
            }
            if (fill.on()) for (int i = 0; i < seg; i++) {
                // light from above: faces turned to the sun-ish direction are brighter, tip whitens like glass
                double mid = (i + 0.5) * Math.PI * 2 / seg + yaw;
                float light = (float) (0.75 + 0.25 * Math.cos(mid - time * 0.7));
                int c0 = a(mixc(cols[i], 0xFFFFFFFF, 0.1f * light), op * light), c1 = a(mixc(cols[i + 1], 0xFFFFFFFF, 0.1f * light), op * light);
                int ct = a(mixc(mixc(cols[i], cols[i + 1], 0.5f), 0xFFFFFFFF, 0.55f), op * 0.9f);
                Render3D.drawGradientQuad(tip, tip, rim[i + 1], rim[i], ct, ct, c1, c0, false);
            }
            if (shine.on()) {
                // a highlight streak sliding around the cone
                double sa = time * 1.4 % (Math.PI * 2);
                for (int k = -3; k <= 3; k++) {
                    double an = sa + k * 0.06 + yaw;
                    float f = 1 - Math.abs(k) / 4f;
                    Vec3d rp = base.add(hr.multiply(Math.cos(an) * r * 0.98)).add(hf.multiply(Math.sin(an) * r * 0.98)).add(hu.multiply(0.002));
                    Render3D.drawLineGradient(tip.add(hu.multiply(-0.01)), rp, a(0xFFFFFFFF, 0.55f * f), a(0xFFFFFFFF, 0), 1.6f, false);
                }
            }
            if (outline.on()) for (int i = 0; i < seg; i++) {
                Render3D.drawLineGradient(rim[i], rim[i + 1], a(cols[i], 0.95f), a(cols[i + 1], 0.95f), 1.8f, false);
            }
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            float td = mc.getRenderTickCounter().getTickProgress(true);
            double time = System.nanoTime() / 1e9;
            if (!mc.options.getPerspective().isFirstPerson()) hat(mc.player, td, time);
            if (others.on()) for (AbstractClientPlayerEntity p : mc.world.getPlayers()) if (p != mc.player && p.isAlive()) hat(p, td, time);
        }
    }

    /* ------------------------------------------------------------------ step tiles (SoberClient) */

    static final class StepTiles extends Module {
        private final Setting.Mode style = mode("Style", "Glow", "Glow", "Frost", "Ripple", "Neon");
        private final Setting.Num life = num("Fade Time", 1.6, 0.5, 5, 0.1);
        private final Setting.Bool smooth = bool("Smooth Pop", true);
        private final Setting.Num glowSize = num("Glow Size", 1, 0.2, 2.5, 0.1);
        private final Setting.Num maxTiles = num("Max Tiles", 60, 10, 200, 5);
        private final Setting.Mode color = colors(this);
        private final Deque<Object[]> tiles = new ArrayDeque<>(); // BlockPos, born, top y
        private BlockPos last;

        StepTiles() {
            super("Step Tiles", "The blocks you walk on light up with a smooth glow and fade", Category.RENDER);
        }

        @Override
        protected void onDisable() {
            tiles.clear();
            last = null;
        }

        @Override
        public void tick() {
            if (!mc.player.isOnGround()) return;
            BlockPos below = BlockPos.ofFloored(mc.player.getX(), mc.player.getY() - 0.2, mc.player.getZ());
            var state = mc.world.getBlockState(below);
            if (below.equals(last) || state.isAir()) return;
            var shape = state.getOutlineShape(mc.world, below);
            double top = shape.isEmpty() ? 1 : shape.getMax(net.minecraft.util.math.Direction.Axis.Y);
            last = below;
            tiles.addLast(new Object[]{below, System.currentTimeMillis(), below.getY() + top + 0.004});
            while (tiles.size() > maxTiles.i()) tiles.removeFirst();
        }

        private static int a(int c, float alpha) {
            return Math.round(255 * Math.max(0, Math.min(1, alpha))) << 24 | (c & 0xFFFFFF);
        }

        private static float backOut(float t) {
            float c1 = 1.70158f, c3 = c1 + 1;
            return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
        }

        private static void quad(double cx, double y, double cz, double half, int c, int edge) {
            Vec3d p0 = new Vec3d(cx - half, y, cz - half), p1 = new Vec3d(cx + half, y, cz - half), p2 = new Vec3d(cx + half, y, cz + half), p3 = new Vec3d(cx - half, y, cz + half);
            Render3D.drawGradientQuad(p0, p1, p2, p3, c, c, c, c, true);
            if (edge != 0) {
                Render3D.drawLine(p0, p1, edge, 1.4f, true);
                Render3D.drawLine(p1, p2, edge, 1.4f, true);
                Render3D.drawLine(p2, p3, edge, 1.4f, true);
                Render3D.drawLine(p3, p0, edge, 1.4f, true);
            }
        }

        /** Soft square ring between two half sizes (inner colour -> outer colour), for halos and ripples. */
        private static void ring(double cx, double y, double cz, double in, double out, int cin, int cout) {
            double[][] s = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int i = 0; i < 4; i++) {
                double[] a0 = s[i], a1 = s[(i + 1) % 4];
                Render3D.drawGradientQuad(new Vec3d(cx + a0[0] * in, y, cz + a0[1] * in), new Vec3d(cx + a1[0] * in, y, cz + a1[1] * in),
                        new Vec3d(cx + a1[0] * out, y, cz + a1[1] * out), new Vec3d(cx + a0[0] * out, y, cz + a0[1] * out), cin, cin, cout, cout, true);
            }
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            long now = System.currentTimeMillis();
            float lifeMs = life.f() * 1000;
            while (!tiles.isEmpty() && now - (long) tiles.peekFirst()[1] > lifeMs) tiles.removeFirst();
            int idx = 0;
            for (Object[] t : tiles) {
                BlockPos p = (BlockPos) t[0];
                float age = (now - (long) t[1]) / lifeMs;
                float f = 1 - age;
                float fade = f * f * (3 - 2 * f); // smoothstep fade-out
                float pop = smooth.on() ? backOut(Math.min(1, age * 6)) : 1;
                double cx = p.getX() + 0.5, cz = p.getZ() + 0.5, y = (double) t[2];
                int col = palette(color, (idx++ * 0.07f + now / 4000f) % 1f);
                double half = 0.5 * Math.max(0.05, pop);
                switch (style.get()) {
                    case "Frost" -> {
                        quad(cx, y, cz, half, a(0xFFFFFFFF, 0.16f * fade), a(0xFFF5F6F8, 0.75f * fade));
                        quad(cx, y + 0.001, cz, half * 0.8, a(col, 0.12f * fade), 0);
                        ring(cx, y, cz, half, half + 0.18 * glowSize.f(), a(0xFFF3F5F8, 0.18f * fade), a(0xFFF3F5F8, 0));
                    }
                    case "Ripple" -> {
                        quad(cx, y, cz, half, a(col, 0.18f * fade), a(col, 0.7f * fade));
                        double rr = 0.5 + age * 0.9 * glowSize.f();
                        ring(cx, y + 0.001, cz, rr - 0.06, rr, a(col, 0), a(col, 0.45f * fade));
                        ring(cx, y + 0.001, cz, rr, rr + 0.06, a(col, 0.45f * fade), a(col, 0));
                    }
                    case "Neon" -> {
                        quad(cx, y, cz, half, a(col, 0.08f * fade), a(col, fade));
                        ring(cx, y, cz, half, half + 0.12 * glowSize.f(), a(col, 0.5f * fade), a(col, 0));
                        ring(cx, y, cz, half - 0.1 * glowSize.f(), half, a(col, 0), a(col, 0.4f * fade));
                    }
                    default -> {
                        quad(cx, y, cz, half, a(col, 0.3f * fade), a(col, 0.85f * fade));
                        ring(cx, y, cz, half, half + 0.25 * glowSize.f(), a(col, 0.28f * fade), a(col, 0));
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ fireflies */

    static final class Fireflies extends Module {
        private final Setting.Num count = num("Count", 40, 10, 120, 5);
        private final Setting.Mode color = colors(this);
        private final List<double[]> flies = new ArrayList<>(); // x y z phase
        private final Random r = new Random();

        Fireflies() {
            super("Fireflies", "Glowing fireflies drifting around you", Category.RENDER);
        }

        @Override
        protected void onDisable() {
            flies.clear();
        }

        @Override
        public void tick() {
            Vec3d me = mc.player.getEntityPos();
            flies.removeIf(f -> me.squaredDistanceTo(f[0], f[1], f[2]) > 18 * 18);
            while (flies.size() < count.i())
                flies.add(new double[]{me.x + (r.nextDouble() - 0.5) * 24, me.y + r.nextDouble() * 5, me.z + (r.nextDouble() - 0.5) * 24, r.nextDouble() * 100});
        }

        @Override
        public void render2d(float td) {
            double t = System.nanoTime() / 1e9;
            for (double[] f : flies) {
                double ph = f[3];
                Vec3d p = new Vec3d(f[0] + Math.sin(t * 0.4 + ph) * 1.2, f[1] + Math.sin(t * 0.7 + ph * 1.3) * 0.6, f[2] + Math.cos(t * 0.35 + ph) * 1.2);
                float blink = (float) (0.35 + 0.65 * Math.pow(0.5 + 0.5 * Math.sin(t * 1.8 + ph * 7), 2));
                glowDot(p, 0.045, palette(color, (float) ((ph % 1 + 1) % 1)), blink);
            }
        }
    }

    /* ------------------------------------------------------------------ trails */

    static final class Trails extends Module {
        private final Setting.Mode style = mode("Style", "Ribbon", "Ribbon", "Glow Line", "Frost", "Rainbow", "Comet", "Helix", "Dots", "Lightning");
        private final Setting.Num life = num("Length", 1.2, 0.3, 4, 0.1);
        private final Setting.Num width = num("Width", 0.14, 0.04, 0.5, 0.01);
        private final Setting.Num glow = num("Glow", 1, 0, 2.5, 0.1);
        private final Setting.Num height = num("Height", 0.45, 0, 1, 0.05);
        private final Setting.Bool others = bool("Other Players", true);
        private final Setting.Mode color = colors(this);
        private final Map<Integer, Deque<double[]>> trails = new HashMap<>(); // x y z time

        Trails() {
            super("Trails", "A smooth glowing ribbon that follows moving players", Category.RENDER);
        }

        @Override
        protected void onDisable() {
            trails.clear();
        }

        @Override
        public void tick() {
            List<Integer> alive = new ArrayList<>();
            double now = MoreVisuals.VFx.now();
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (p != mc.player && !others.on()) continue;
                alive.add(p.getId());
                Deque<double[]> d = trails.computeIfAbsent(p.getId(), k -> new ArrayDeque<>());
                double x = p.getX(), y = p.getY() + p.getHeight() * height.f(), z = p.getZ();
                double[] l = d.peekLast();
                if (l != null && (x - l[0]) * (x - l[0]) + (y - l[1]) * (y - l[1]) + (z - l[2]) * (z - l[2]) > 16) d.clear(); // teleport
                if (l == null || (x - l[0]) * (x - l[0]) + (y - l[1]) * (y - l[1]) + (z - l[2]) * (z - l[2]) > 0.0025) d.addLast(new double[]{x, y, z, now});
                while (d.size() > 200) d.removeFirst();
            }
            trails.keySet().retainAll(alive);
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            MoreVisuals.VFx.frame();
            float td = mc.getRenderTickCounter().getTickProgress(true);
            double now = MoreVisuals.VFx.now();
            float L = life.f();
            for (var en : trails.entrySet()) {
                Entity e = mc.world.getEntityById(en.getKey());
                if (e == null) continue;
                if (e == mc.player && mc.options.getPerspective().isFirstPerson()) continue;
                Deque<double[]> d = en.getValue();
                while (!d.isEmpty() && now - d.peekFirst()[3] > L) d.removeFirst();
                if (d.isEmpty()) continue;
                Vec3d head = e.getLerpedPos(td).add(0, e.getHeight() * height.f(), 0);
                List<Vec3d> pts = new ArrayList<>();
                float[] fades = new float[d.size() + 1];
                int i = 0;
                for (double[] s : d) {
                    pts.add(new Vec3d(s[0], s[1], s[2]));
                    fades[i++] = MoreVisuals.VFx.smooth((float) (1 - (now - s[3]) / L));
                }
                if (head.squaredDistanceTo(pts.get(pts.size() - 1)) > 1e-6) {
                    pts.add(head);
                    fades[i++] = 1;
                } else fades[i - 1] = 1;
                if (pts.size() < 2) continue;
                MoreVisuals.trail(pts, java.util.Arrays.copyOf(fades, pts.size()), style.get(), color, width.f(), glow.f(), MoreVisuals.VFx.CAMERA, true, en.getKey());
            }
        }
    }

    /* ------------------------------------------------------------------ hit marker */

    static final class HitMarker extends Module {
        static final List<long[]> hits = new ArrayList<>();
        private final Setting.Bool sound = bool("Sound", false);

        HitMarker() {
            super("Hit Marker", "A crosshair X that pops when you hit something", Category.RENDER);
        }

        @Override
        public void render2d(float td) {
            long now = System.currentTimeMillis();
            hits.removeIf(h -> now - h[0] > 400);
            if (hits.isEmpty()) return;
            long[] h = hits.get(hits.size() - 1);
            if (h[1] == 0 && sound.on()) {
                mc.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), 0.6f, 1.8f);
                h[1] = 1;
            }
            float t = (now - h[0]) / 400f;
            float g = 4 + 8 * (1 - (1 - t) * (1 - t)), l = 7;
            float cx = Nvg.w / 2f, cy = Nvg.h / 2f;
            int c = Nvg.alpha(0xFFFFFFFF, 1 - t);
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sy = -1; sy <= 1; sy += 2) {
                    Nvg.line(cx + sx * g, cy + sy * g, cx + sx * (g + l), cy + sy * (g + l), 3.5f, Nvg.alpha(0xFF000000, 0.4f * (1 - t)));
                    Nvg.line(cx + sx * g, cy + sy * g, cx + sx * (g + l), cy + sy * (g + l), 1.8f, c);
                }
        }
    }

    /* ------------------------------------------------------------------ particles (Crypto style) */

    static final class Particles extends Module {
        private final Setting.Mode shape = mode("Shape", "Star", "Star", "Heart", "Rhombus", "Cube", "Circle", "Triangle", "Snowflake", "Random");
        private final Setting.Bool onHit = bool("On Hit", true);
        private final Setting.Bool walking = bool("Walking", false);
        private final Setting.Num amount = num("Amount", 24, 5, 60, 1);
        private final Setting.Num spread = num("Spread", 1, 0.3, 3, 0.1);
        private final Setting.Num speed = num("Speed", 1.2, 0.2, 3, 0.1);
        private final Setting.Num life = num("Lifetime", 2, 0.5, 6, 0.1);
        private final Setting.Num size = num("Size", 0.6, 0.1, 1.5, 0.05);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Mode color = colors(this);
        private final List<double[]> parts = new ArrayList<>(); // x y z vx vy vz born shape rot spin hue
        private final Random r = new Random();
        private long lastSeen;
        private float walkAcc;

        Particles() {
            super("Particles", "Glowing shapes that burst out when you hit", Category.RENDER);
        }

        @Override
        protected void onDisable() {
            parts.clear();
        }

        private void burst(Vec3d at, int n) {
            String[] shapes = {"Star", "Heart", "Rhombus", "Cube", "Circle", "Triangle", "Snowflake"};
            for (int i = 0; i < n; i++) {
                double yaw = r.nextDouble() * Math.PI * 2, up = 0.15 + r.nextDouble() * 0.35;
                double sp = (0.08 + r.nextDouble() * 0.12) * speed.value * spread.value;
                int sh = shape.is("Random") ? r.nextInt(shapes.length) : java.util.Arrays.asList(shapes).indexOf(shape.get());
                parts.add(new double[]{at.x, at.y, at.z, Math.cos(yaw) * sp, up * speed.value * 0.6, Math.sin(yaw) * sp,
                        System.currentTimeMillis(), sh, r.nextDouble() * 6.28, (r.nextDouble() - 0.5) * 0.4, r.nextDouble()});
            }
            while (parts.size() > 600) parts.remove(0);
        }

        @Override
        public void tick() {
            if (onHit.on() && lastHit != lastSeen && lastTarget != null) {
                lastSeen = lastHit;
                burst(lastTarget.getEntityPos().add(0, lastTarget.getHeight() * 0.6, 0), amount.i());
            }
            if (walking.on() && mc.player.isOnGround() && mc.player.getVelocity().horizontalLengthSquared() > 0.002) {
                walkAcc += 0.6f;
                while (walkAcc >= 1) {
                    walkAcc--;
                    burst(mc.player.getEntityPos().add(0, 0.1, 0), 1);
                }
            }
            long now = System.currentTimeMillis();
            for (Iterator<double[]> it = parts.iterator(); it.hasNext(); ) {
                double[] p = it.next();
                if (now - p[6] > life.f() * 1000) {
                    it.remove();
                    continue;
                }
                p[4] -= 0.012; // gravity
                double nx = p[0] + p[3], ny = p[1] + p[4], nz = p[2] + p[5];
                BlockPos bp = BlockPos.ofFloored(nx, ny, nz);
                if (!mc.world.getBlockState(bp).getCollisionShape(mc.world, bp).isEmpty()) {
                    // bounce off whatever we hit
                    if (!mc.world.getBlockState(BlockPos.ofFloored(p[0], ny, p[2])).getCollisionShape(mc.world, bp).isEmpty()) p[4] = -p[4] * 0.45;
                    else {
                        p[3] = -p[3] * 0.6;
                        p[5] = -p[5] * 0.6;
                    }
                    p[3] *= 0.8;
                    p[5] *= 0.8;
                } else {
                    p[0] = nx;
                    p[1] = ny;
                    p[2] = nz;
                }
                p[3] *= 0.98;
                p[5] *= 0.98;
                p[8] += p[9];
            }
        }

        @Override
        public void render2d(float td) {
            long now = System.currentTimeMillis();
            float[] a = new float[2];
            for (double[] p : parts) {
                Vec3d pos = new Vec3d(p[0] + p[3] * td, p[1] + p[4] * td, p[2] + p[5] * td);
                if (!Projection.point(pos, a)) continue;
                float age = (now - (long) p[6]) / (life.f() * 1000f);
                float alpha = age < 0.1f ? age / 0.1f : 1f - Math.max(0, (age - 0.6f) / 0.4f);
                float px = pixels(pos, 0.12 * size.f());
                if (px < 0.6f) continue;
                int col = palette(color, (float) p[10]);
                if (glow.on()) {
                    Nvg.additive(true);
                    Nvg.glowDisc(a[0], a[1], px * 7f, Nvg.alpha(col, 0.10f * alpha), Nvg.alpha(col, 0));
                    Nvg.glowDisc(a[0], a[1], px * 3.6f, Nvg.alpha(col, 0.32f * alpha), Nvg.alpha(col, 0));
                    Nvg.glowDisc(a[0], a[1], px * 1.6f, Nvg.alpha(col, 0.5f * alpha), Nvg.alpha(col, 0));
                    Nvg.additive(false);
                }
                Nvg.save();
                Nvg.translate(a[0], a[1]);
                Nvg.rotate((float) p[8]);
                shape((int) p[7], px, Nvg.alpha(Nvg.mix(col, 0xFFFFFFFF, 0.35f), alpha));
                Nvg.restore();
            }
        }

        private static void shape(int s, float r, int c) {
            Nvg.beginPath();
            switch (s) {
                case 0 -> { // star
                    for (int i = 0; i < 10; i++) {
                        double an = i * Math.PI / 5 - Math.PI / 2;
                        float rr = i % 2 == 0 ? r : r * 0.45f;
                        if (i == 0) Nvg.moveTo((float) Math.cos(an) * rr, (float) Math.sin(an) * rr);
                        else Nvg.lineTo((float) Math.cos(an) * rr, (float) Math.sin(an) * rr);
                    }
                    Nvg.closePath();
                    Nvg.fillPath(c);
                }
                case 1 -> { // heart
                    Nvg.moveTo(0, r * 0.9f);
                    Nvg.bezierTo(-r * 1.3f, -r * 0.1f, -r * 0.6f, -r * 1.1f, 0, -r * 0.35f);
                    Nvg.bezierTo(r * 0.6f, -r * 1.1f, r * 1.3f, -r * 0.1f, 0, r * 0.9f);
                    Nvg.closePath();
                    Nvg.fillPath(c);
                }
                case 2 -> { // rhombus
                    Nvg.moveTo(0, -r); Nvg.lineTo(r * 0.62f, 0); Nvg.lineTo(0, r); Nvg.lineTo(-r * 0.62f, 0);
                    Nvg.closePath();
                    Nvg.fillPath(c);
                }
                case 3 -> { // cube outline
                    Nvg.rrectPath(-r * 0.7f, -r * 0.7f, r * 1.4f, r * 1.4f, r * 0.15f);
                    Nvg.strokePath(Math.max(1f, r * 0.25f), c);
                }
                case 4 -> {
                    Nvg.circlePath(0, 0, r * 0.6f);
                    Nvg.fillPath(c);
                }
                case 5 -> { // triangle
                    Nvg.moveTo(0, -r); Nvg.lineTo(r * 0.87f, r * 0.5f); Nvg.lineTo(-r * 0.87f, r * 0.5f);
                    Nvg.closePath();
                    Nvg.fillPath(c);
                }
                default -> { // snowflake
                    for (int i = 0; i < 3; i++) {
                        double an = i * Math.PI / 3;
                        float dx = (float) Math.cos(an) * r, dy = (float) Math.sin(an) * r;
                        Nvg.line(-dx, -dy, dx, dy, Math.max(1f, r * 0.18f), c);
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ world shaders */

    static final class ShaderSky extends Module {
        private final Setting.Mode sky = mode("Mode", "Aurora", "Aurora", "Night", "Snow", "Clouds", "Star", "Glow", "Nebula", "Storm", "Tide", "Sunset",
                "Galaxy Spiral", "Milky Way", "Volumetric Clouds", "Golden Hour", "Blue Hour", "Cotton Candy", "Northern Lights",
                "Black Hole", "Planet Rise", "Eclipse", "Meteor Shower", "Synthwave", "Vaporwave", "Warp", "Nebula Storm",
                "Deep Space", "Cherry Blossom", "Thunderstorm", "Moonlit Clouds", "Rainbow", "Lava", "Underwater", "Matrix", "Cyber Grid");
        private final Setting.Mode colorMode = mode("Color Mode", "Theme", "Theme", "Custom");
        private final Setting.Color color1 = color("Color 1", 0xFFFFFFFF).showIf(() -> colorMode.is("Custom"));
        private final Setting.Color color2 = color("Color 2", 0xFFFFFFFF).showIf(() -> colorMode.is("Custom"));
        private final Setting.Num speed = num("Speed", 1, 0.1, 3, 0.05);
        private final Setting.Num scale = num("Scale", 1.1, 0.45, 3, 0.05);
        private final Setting.Num strength = num("Strength", 100, 10, 100, 5);
        private final Setting.Num stars = num("Stars", 75, 0, 100, 5);
        private final Setting.Bool vanilla = bool("Vanilla Sky", false);

        ShaderSky() {
            super("Shader Sky", "Procedural skies you can colour", Category.RENDER);
        }

        @Override
        public String suffix() {
            return sky.get();
        }

        @Override
        public void tick() {
            WorldFx.sky = sky.index;
            // Theme is the reference client's default: white primary, 75% secondary
            WorldFx.skyPrimary = colorMode.is("Theme") ? 0xFFFFFFFF : color1.rgb();
            WorldFx.skySecondary = colorMode.is("Theme") ? 0xFFBFBFBF : color2.rgb();
            WorldFx.skySpeed = speed.f();
            WorldFx.skyScale = scale.f();
            WorldFx.skyStrength = strength.f() / 100f;
            WorldFx.skyStars = stars.f() / 100f;
            WorldFx.skyVanilla = vanilla.on();
            WorldFx.skyCustom = colorMode.is("Custom");
        }

        @Override
        protected void onDisable() {
            WorldFx.sky = -1;
        }
    }

    static final class MotionBlur extends Module {
        private final Setting.Mode type = mode("Mode", "Velocity", "Velocity", "Accumulate", "Both");
        private final Setting.Num amount = num("Strength", 50, 5, 100, 5);

        MotionBlur() {
            super("Motion Blur", "Smooth camera motion blur (depth reprojection, frame-rate independent)", Category.RENDER);
        }

        @Override
        public void tick() {
            tenacity.gfx.MotionBlurFx.strength = amount.f() / 100f;
            tenacity.gfx.MotionBlurFx.mode = type.index;
        }

        @Override
        protected void onEnable() {
            tick();
        }

        @Override
        protected void onDisable() {
            tenacity.gfx.MotionBlurFx.strength = 0;
        }
    }
}

package tenacity.modules;

import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
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
import java.util.Locale;
import java.util.Map;

/** Render tab: 2D overlays drawn with NanoVG plus vanilla tweaks done through mixins (see {@link RenderFlags}). */
final class RenderModules {
    private RenderModules() {
    }

    static List<Module> create() {
        return List.of(new Esp(), new SkeletonEsp(), new GlowEsp(), new PlayerTransparency(), new Tracers(), new Nametags(),
                new ItemEsp(), new BlockHighlight(), new Breadcrumbs(),
                new DamageNumbers(), new Compass(),
                new Crosshair(), new DeathWaypoint(), new KillEffects(), new Fullbright(),
                new NoShading(), new NoHurtCam(), new NoFire(), new NoWeather(), new TimeChanger(), new CustomFov(), new AspectRatio(),
                new Zoom(), new NoBob(), new NoNausea(), new HandShaders(), new ViewModel(), new SwingAnimations());
    }

    /* ------------------------------------------------------------------ helpers */

    private static final int WHITE = 0xFFFFFFFF, SHADOW = 0x99000000;

    static Box boxOf(Entity e, float td) {
        return e.getDimensions(e.getPose()).getBoxAt(e.getLerpedPos(td));
    }

    static boolean valid(Entity e, boolean players, boolean mobs) {
        if (e == mc().player || !(e instanceof LivingEntity le) || !le.isAlive() || e instanceof ArmorStandEntity) return false;
        return e instanceof PlayerEntity ? players : mobs && e instanceof MobEntity;
    }

    static net.minecraft.client.MinecraftClient mc() {
        return net.minecraft.client.MinecraftClient.getInstance();
    }

    /** A line with a soft glow: wide faint passes under a crisp core. Glow 0 is just the core line, no dark underlay. */
    static void glowLine(float x1, float y1, float x2, float y2, float w, int color, float glow) {
        if (glow > 0) {
            Nvg.line(x1, y1, x2, y2, w + 12 * glow, Nvg.alpha(color, 0.04f));
            Nvg.line(x1, y1, x2, y2, w + 7 * glow, Nvg.alpha(color, 0.08f));
            Nvg.line(x1, y1, x2, y2, w + 3.5f * glow, Nvg.alpha(color, 0.16f));
        }
        Nvg.line(x1, y1, x2, y2, w, color);
    }

    static void glowWorldLine(Vec3d a, Vec3d b, float w, int color, float glow) {
        float[] p = new float[2], q = new float[2];
        if (Projection.segment(a, b, p, q)) glowLine(p[0], p[1], q[0], q[1], w, color, glow);
    }

    /** Small dark pill with centred text. */
    static void label(String s, float cx, float cy, float size, int color) {
        float tw = Nvg.textWidth(s, size, Nvg.MED);
        Nvg.rrect(cx - tw / 2 - 6, cy - size * 0.75f, tw + 12, size * 1.5f, size * 0.75f, 0xB0000000);
        Nvg.text(s, cx, cy + 0.5f, size, Nvg.MED, Nvg.CENTER, color);
    }

    /** Small liquid glass pill with centred text. */
    static void glassLabel(String s, float cx, float cy, float size, int color) {
        float tw = Nvg.textWidth(s, size, Nvg.MED);
        float w = tw + 14, h = size * 1.7f;
        tenacity.ui.Theme.hud(cx - w / 2, cy - h / 2, w, h, h / 2, 1f, 0);
        Nvg.text(s, cx, cy + 0.5f, size, Nvg.MED, Nvg.CENTER, color);
    }

    /** Projected polyline through world points, clipped at the camera near plane instead of breaking. */
    static void worldLine(List<Vec3d> pts, float width, int color, boolean closed) {
        float[] a = new float[2], b = new float[2];
        int n = pts.size();
        for (int i = 0; i < (closed ? n : n - 1); i++) {
            if (Projection.segment(pts.get(i), pts.get((i + 1) % n), a, b)) Nvg.line(a[0], a[1], b[0], b[1], width, color);
        }
    }

    static List<Vec3d> circle(Vec3d c, double r, int seg) {
        List<Vec3d> out = new ArrayList<>(seg);
        for (int i = 0; i < seg; i++) {
            double a = i * Math.PI * 2 / seg;
            out.add(c.add(Math.cos(a) * r, 0, Math.sin(a) * r));
        }
        return out;
    }

    /** The 12 edges of a box, projected. */
    static void box3d(Box b, float width, int color) {
        Vec3d[] c = new Vec3d[8];
        for (int i = 0; i < 8; i++)
            c[i] = new Vec3d((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
        int[][] e = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] p : e) worldLine(List.of(c[p[0]], c[p[1]]), width, color, false);
    }

    /* ------------------------------------------------------------------ ESP */

    static final class Esp extends Module {
        private final Setting.Mode style = mode("Style", "Corners", "Corners", "Box", "3D");
        private final Setting.Bool players = bool("Players", true);
        private final Setting.Bool mobs = bool("Mobs", false);
        private final Setting.Bool health = bool("Health Bar", true);
        private final Setting.Num range = num("Range", 96, 16, 256, 8);

        Esp() {
            super("ESP", "Boxes and health bars around entities", Category.RENDER);
        }

        @Override
        public String suffix() {
            return style.get();
        }

        @Override
        public void render2d(float td) {
            for (Entity e : mc.world.getEntities()) {
                if (!valid(e, players.on(), mobs.on()) || mc.player.distanceTo(e) > range.value) continue;
                LivingEntity le = (LivingEntity) e;
                if (style.is("3D")) {
                    Box b = boxOf(e, td);
                    box3d(b, 3f, SHADOW);
                    box3d(b, 1.3f, WHITE);
                    continue;
                }
                float[] r = Projection.box(boxOf(e, td));
                if (r == null) continue;
                float x = r[0], y = r[1], w = r[2] - r[0], h = r[3] - r[1];
                if (w < 2 || h < 2) continue;
                if (style.is("Box")) {
                    Nvg.rrectStroke(x - 1, y - 1, w + 2, h + 2, 2, 3.4f, SHADOW);
                    Nvg.rrectStroke(x, y, w, h, 1.5f, 1.4f, WHITE);
                } else {
                    corners(x, y, w, h, SHADOW, 3.4f);
                    corners(x, y, w, h, WHITE, 1.4f);
                }
                if (health.on()) {
                    float f = MathHelper.clamp(le.getHealth() / Math.max(1f, le.getMaxHealth()), 0f, 1f);
                    Nvg.rrect(x - 6, y - 1, 3.5f, h + 2, 1.7f, SHADOW);
                    Nvg.rrect(x - 5.5f, y + h * (1 - f), 2.5f, h * f, 1.2f, Nvg.mix(0xFFFF5A5A, 0xFF7CF29A, f));
                }
            }
        }

        private static void corners(float x, float y, float w, float h, int c, float sw) {
            float l = Math.min(w, h) * 0.28f;
            Nvg.line(x, y, x + l, y, sw, c); Nvg.line(x, y, x, y + l, sw, c);
            Nvg.line(x + w, y, x + w - l, y, sw, c); Nvg.line(x + w, y, x + w, y + l, sw, c);
            Nvg.line(x, y + h, x + l, y + h, sw, c); Nvg.line(x, y + h, x, y + h - l, sw, c);
            Nvg.line(x + w, y + h, x + w - l, y + h, sw, c); Nvg.line(x + w, y + h, x + w, y + h - l, sw, c);
        }
    }

    /** Stick figure that follows the player's body rotation, head turn and walking animation. */
    /**
     * Skeleton ESP: thirteen bones posed from the same inputs the vanilla player model uses (body yaw, walk cycle, attack swing,
     * sneaking, babies), with elbows, knees and a head, drawn as glowing lines. Flashes red when the player is hit.
     */
    static final class SkeletonEsp extends Module {
        private final Setting.Mode style = mode("Style", "X-Ray", "X-Ray", "Bones", "X-Ray + Bones");
        private final Setting.Mode colorMode = mode("Color", "Purple", "Purple", "White", "Red", "Cyan", "Chroma");
        private final Setting.Bool distColor = bool("Distance Color", false);
        private final Setting.Bool xray = bool("X-Ray Body", true).showIf(() -> style.get().contains("Bones"));
        private final Setting.Num width = num("Thickness", 2, 0.8, 5, 0.2);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Num glowAmt = num("Glow Size", 1, 0.3, 2.5, 0.1).showIf(() -> glow.on());
        private final Setting.Num glowStrength = num("Glow Strength", 1, 0.2, 2, 0.1).showIf(() -> glow.on());
        private final Setting.Bool self = bool("Self (3rd person)", false);
        private final Setting.Num range = num("Range", 96, 8, 256, 8);

        SkeletonEsp() {
            super("Skeleton ESP", "Glowing X-Ray skeletons inside players", Category.RENDER);
        }

        private boolean shows(PlayerEntity p) {
            if (!p.isAlive() || p.age < 3) return false;
            if (p == mc.player) return self.on() && !mc.options.getPerspective().isFirstPerson();
            return mc.player.distanceTo(p) <= range.value;
        }

        @Override
        public void tick() {
            boolean x = style.get().startsWith("X-Ray");
            tenacity.gfx.XrayFeature.enabled = x;
            tenacity.gfx.XrayFeature.self = self.on();
            tenacity.gfx.XrayFeature.rangeSq = range.value * range.value;
            tenacity.gfx.XrayRenderer.enabled = x;
            tenacity.gfx.XrayRenderer.color = color() & 0xFFFFFF;
            tenacity.gfx.XrayRenderer.glow = glow.on() ? glowAmt.f() : 0f;
            tenacity.gfx.XrayRenderer.glowStrength = glowStrength.f();
        }

        @Override
        protected void onDisable() {
            tenacity.gfx.XrayFeature.enabled = false;
            tenacity.gfx.XrayRenderer.enabled = false;
            tenacity.gfx.XrayFeature.CAPTURES.clear();
        }

        /* ---------------- SoberClient-style bones (model pixels, hand computed from the player's animation inputs) */

        private static final double PIXEL = 0.05859375;
        private static final int[][] BONES = {{0, 1}, {2, 3}, {2, 4}, {3, 5}, {6, 7}, {6, 8}, {7, 9}, {1, 10}};
        private final Vec3d[] jp = new Vec3d[12];

        private void bonesPose(PlayerEntity p, float td) {
            float bodyYaw = MathHelper.lerpAngleDegrees(td, p.lastBodyYaw, p.bodyYaw);
            float headYaw = MathHelper.lerpAngleDegrees(td, p.lastHeadYaw, p.headYaw);
            float pitch = p.getPitch(td);
            double yaw = Math.toRadians(bodyYaw);
            double rx = -Math.cos(yaw), rz = -Math.sin(yaw), fx = -Math.sin(yaw), fz = Math.cos(yaw);
            Vec3d o = p.getLerpedPos(td);
            float progress = p.limbAnimator.getAnimationProgress(td), amp = Math.min(1f, p.limbAnimator.getAmplitude(td));
            float rightLeg = MathHelper.cos(progress * 0.6662f) * 1.4f * amp;
            float leftLeg = MathHelper.cos(progress * 0.6662f + (float) Math.PI) * 1.4f * amp;
            float rightArm = MathHelper.cos(progress * 0.6662f + (float) Math.PI) * amp;
            float leftArm = MathHelper.cos(progress * 0.6662f) * amp;
            float swing = p.getHandSwingProgress(td);
            if (swing > 0) rightArm -= (float) (Math.sin(swing * Math.PI) * 1.3);
            boolean sneak = p.isInSneakingPose();
            double lean = sneak ? 0.5 : 0, hipY = sneak ? 9.6 : 12, hipZ = sneak ? -3.2 : 0;
            if (sneak) {
                rightArm += 0.4f;
                leftArm += 0.4f;
            }
            double tilt = (p.isGliding() || p.isSwimming()) ? Math.toRadians(90 + pitch) : 0;
            double[][] q = new double[12][];
            q[0] = new double[]{0, hipY, hipZ};
            q[1] = new double[]{0, hipY + 12 * Math.cos(lean), hipZ + 12 * Math.sin(lean)};
            q[2] = new double[]{5, hipY + 10 * Math.cos(lean), hipZ + 10 * Math.sin(lean)};
            q[3] = new double[]{-5, q[2][1], q[2][2]};
            q[4] = new double[]{6, q[2][1] - 10 * Math.cos(rightArm), q[2][2] - 10 * Math.sin(rightArm)};
            q[5] = new double[]{-6, q[3][1] - 10 * Math.cos(leftArm), q[3][2] - 10 * Math.sin(leftArm)};
            q[6] = new double[]{1.9, hipY, hipZ};
            q[7] = new double[]{-1.9, hipY, hipZ};
            q[8] = new double[]{1.9, hipY - 12 * Math.cos(rightLeg), hipZ - 12 * Math.sin(rightLeg)};
            q[9] = new double[]{-1.9, hipY - 12 * Math.cos(leftLeg), hipZ - 12 * Math.sin(leftLeg)};
            if (sneak) {
                q[8][1] = Math.max(0, q[8][1] - 2.4);
                q[9][1] = Math.max(0, q[9][1] - 2.4);
            }
            double turn = Math.toRadians(MathHelper.wrapDegrees(headYaw - bodyYaw)), hp = Math.toRadians(pitch);
            q[10] = new double[]{q[1][0], q[1][1] + 4.4, q[1][2]};
            q[11] = new double[]{q[10][0] + 5.5 * Math.sin(turn) * Math.cos(hp), q[10][1] - 5.5 * Math.sin(hp), q[10][2] + 5.5 * Math.cos(turn) * Math.cos(hp)};
            for (int i = 0; i < 12; i++) {
                double x = q[i][0], y = q[i][1], z = q[i][2];
                if (tilt != 0) { // rotate about the X axis through (y = 6, z = 0)
                    double yy = y - 6, c = Math.cos(tilt), s = Math.sin(tilt);
                    double ny = yy * c - z * s, nz = yy * s + z * c;
                    y = ny + 6;
                    z = nz;
                }
                jp[i] = o.add((rx * x + fx * z) * PIXEL, y * PIXEL, (rz * x + fz * z) * PIXEL);
            }
        }

        /** Size on screen (logical px) of {@code r} blocks at {@code at}. */
        private static float px(Vec3d at, double r) {
            float[] a = new float[2], b = new float[2];
            if (!Projection.point(at, a) || !Projection.point(at.add(0, r, 0), b)) return 0;
            return (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
        }

        private static void disc(float x, float y, float r, int inner, int outer) {
            if (r < 0.5f) return;
            Nvg.additive(true);
            Nvg.glowDisc(x, y, r, inner, outer);
            Nvg.additive(false);
        }

        private void drawBones(PlayerEntity p, float td) {
            bonesPose(p, td);
            double dist = mc.player.distanceTo(p);
            int c = distColor.on() ? Nvg.mix(0xFFFF465A, color(), (float) Math.min(1, dist / 48)) : color();
            if (p.hurtTime > 0) c = Nvg.mix(c, 0xFFFF3B3B, p.hurtTime / 10f);
            int soft = Nvg.mix(c, 0xFFFFFFFF, 0.55f);
            float w = width.f();
            float[] a = new float[2], b = new float[2];
            // optional x-ray body (thick translucent limbs)
            if (xray.on()) {
                int body = Nvg.alpha(c, 48 / 255f);
                Vec3d mid = jp[0].add(jp[1]).multiply(0.5);
                float spine = MathHelper.clamp(px(mid, 0.46875), 3, 90), limb = MathHelper.clamp(px(mid, 0.234375), 2, 60);
                if (Projection.point(jp[0], a) && Projection.point(jp[1], b)) Nvg.line(a[0], a[1], b[0], b[1], spine, body);
                for (int[] l : new int[][]{{2, 4}, {3, 5}, {6, 8}, {7, 9}})
                    if (Projection.point(jp[l[0]], a) && Projection.point(jp[l[1]], b)) Nvg.line(a[0], a[1], b[0], b[1], limb, body);
                if (Projection.point(jp[10], a)) Nvg.circle(a[0], a[1], px(jp[10], 0.3046875), body);
            }
            // glow pass
            if (glow.on()) {
                float halo = MathHelper.clamp(px(jp[1], 0.16 * glowAmt.f()), 2.5f, 14f * glowAmt.f()) * 2f + w;
                for (int[] bn : BONES)
                    if (Projection.point(jp[bn[0]], a) && Projection.point(jp[bn[1]], b)) Nvg.glowStroke(a[0], a[1], b[0], b[1], w, halo, c, glowStrength.f());
            }
            // core pass: soft at the start, full colour at the end
            for (int[] bn : BONES)
                if (Projection.point(jp[bn[0]], a) && Projection.point(jp[bn[1]], b)) Nvg.lineGradient(a[0], a[1], b[0], b[1], w, soft, c);
            // head: glow, ring, nose
            if (Projection.point(jp[10], a)) {
                float hr = px(jp[10], 0.26953125);
                if (glow.on()) disc(a[0], a[1], hr * 2.1f, Nvg.alpha(c, 70 / 255f), Nvg.alpha(c, 0));
                Nvg.circleStroke(a[0], a[1], hr, w, soft);
                if (Projection.point(jp[11], b)) Nvg.lineGradient(a[0], a[1], b[0], b[1], Math.max(1, w - 0.5f), soft, Nvg.alpha(soft, 0));
            }
            // joints
            for (int i = 2; i <= 9; i++) {
                if (!Projection.point(jp[i], a)) continue;
                if (glow.on()) disc(a[0], a[1], px(jp[i], 0.1875), Nvg.alpha(c, 110 / 255f), Nvg.alpha(c, 0));
                float dr = Math.max(1.4f, px(jp[i], 0.064453125));
                Nvg.circle(a[0], a[1], dr + 0.8f, Nvg.alpha(soft, 200 / 255f));
                Nvg.circle(a[0], a[1], dr, 0xFFFFFFFF);
            }
        }

        private int color() {
            return switch (colorMode.get()) {
                case "Purple" -> 0xFFAA8CFF;
                case "Red" -> 0xFFFF5A6E;
                case "Cyan" -> 0xFF6EE7F9;
                case "Chroma" -> 0xFF000000 | java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 6000) / 6000f, 0.55f, 1f);
                default -> 0xFFFFFFFF;
            };
        }

        @Override
        public void render2d(float td) {
            if (style.is("3D Model")) return;
            boolean b = style.get().contains("Bones");
            if (b) for (AbstractClientPlayerEntity p : mc.world.getPlayers()) if (shows(p)) drawBones(p, td);
        }
    }

    static final class GlowEsp extends Module {
        private final Setting.Bool players = bool("Players", true);
        private final Setting.Bool mobs = bool("Mobs", false);
        private final Setting.Bool items = bool("Items", false);
        private final Setting.Mode color = mode("Color", "White", "Entity", "White", "Purple", "Ice", "Rose", "Mint", "Gold", "Chroma");
        private final Setting.Bool halo = bool("Halo", true);
        private final Setting.Num size = num("Glow Size", 1.4, 0.3, 3, 0.1).showIf(() -> halo.on());
        private final Setting.Num strength = num("Glow Strength", 1.4, 0.2, 3, 0.1).showIf(() -> halo.on());
        private final Setting.Num rim = num("Outline Width", 2, 0, 4, 1);
        private final Setting.Bool fill = bool("Fill", true);
        private final Setting.Num fillAlpha = num("Fill Opacity", 45, 5, 80, 5).showIf(() -> fill.on());
        private final Setting.Bool shimmer = bool("Shimmer", false);
        private final Setting.Bool pulse = bool("Pulse", false);

        GlowEsp() {
            super("Shader ESP", "Clean glowing outlines around entities, through walls", Category.RENDER);
        }

        @Override
        public void tick() {
            RenderFlags.glowPlayers = players.on();
            RenderFlags.glowMobs = mobs.on();
            RenderFlags.glowItems = items.on();
            RenderFlags.glowSize = size.f();
            RenderFlags.glowStrength = halo.on() ? strength.f() : 0f; // outline only unless Halo is on
            RenderFlags.glowRimWidth = rim.f();
            RenderFlags.glowFill = fill.on() ? fillAlpha.f() / 100f : 0f;
            RenderFlags.glowShimmer = shimmer.on();
            RenderFlags.glowPulse = pulse.on();
            RenderFlags.glowChroma = color.is("Chroma");
            RenderFlags.glowColor = switch (color.get()) {
                case "White" -> 0xFFFFFFFF;
                case "Purple" -> 0xFF9B50FF;
                case "Ice" -> 0xFF8FD8FF;
                case "Rose" -> 0xFFFF6FA8;
                case "Mint" -> 0xFF7CFFC4;
                case "Gold" -> 0xFFFFC24A;
                default -> 0;
            };
        }

        @Override
        protected void onDisable() {
            RenderFlags.glowPlayers = RenderFlags.glowMobs = RenderFlags.glowItems = false;
        }
    }

    /** Makes players (and/or yourself in third person) see-through. */
    static final class PlayerTransparency extends Module {
        private final Setting.Num opacity = num("Opacity", 40, 5, 95, 5);
        private final Setting.Bool self = bool("Self", true);
        private final Setting.Bool others = bool("Others", true);
        private final Setting.Mode look = mode("Look", "Frost", "Frost", "Ice", "Clear", "Smoke");

        PlayerTransparency() {
            super("Player Transparency", "Frosted glass players and armor (yourself and/or others)", Category.RENDER);
        }

        @Override
        public String suffix() {
            return opacity.i() + "%";
        }

        @Override
        public void tick() {
            RenderFlags.playerAlpha = opacity.f() / 100f;
            RenderFlags.alphaSelf = self.on();
            RenderFlags.alphaOthers = others.on();
            RenderFlags.alphaTint = switch (look.get()) {
                case "Ice" -> 0xB4DCFF;
                case "Clear" -> 0xFFFFFF;
                case "Smoke" -> 0xB8C0CC;
                default -> 0xF3F5F8; // frosted white with a cool tint
            };
            RenderFlags.frost = !look.is("Clear");
            RenderFlags.smoke = look.is("Smoke");
        }

        @Override
        protected void onDisable() {
            RenderFlags.playerAlpha = 1f;
            RenderFlags.frost = false;
        }
    }

    static final class Tracers extends Module {
        private final Setting.Mode origin = mode("Origin", "Crosshair", "Crosshair", "Eyes", "Feet");
        private final Setting.Mode aim = mode("Aim At", "Body", "Body", "Feet", "Head");
        private final Setting.Mode style = mode("Style", "Line", "Line", "Gradient", "Dotted", "Arrow");
        private final Setting.Num range = num("Range", 96, 8, 256, 8);
        private final Setting.Num width = num("Width", 1.8, 0.5, 5, 0.1);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Bool fade = bool("Distance Fade", true);
        private final Setting.Mode color = mode("Color", "Theme", "Theme", "White", "Distance", "Chroma");

        Tracers() {
            super("Tracers", "Lines to players, smooth even behind you", Category.RENDER);
        }

        @Override
        public String suffix() {
            return style.get();
        }

        /** The world point the line starts on: a touch in front of the camera like Crypto, your eyes or your feet. */
        private Vec3d start(float td) {
            if (origin.is("Eyes")) return mc.player.getLerpedPos(td).add(0, mc.player.getStandingEyeHeight() - 0.15, 0);
            if (origin.is("Feet")) return mc.player.getLerpedPos(td).add(0, 0.05, 0);
            // crosshair: the camera's own look direction, so the start is glued to the crosshair while you turn
            var cam = mc.gameRenderer.getCamera();
            Vec3d fwd = Vec3d.fromPolar(cam.getPitch(), cam.getYaw());
            return Projection.camera().add(fwd.multiply(0.55));
        }

        private Vec3d end(AbstractClientPlayerEntity p, float td) {
            Vec3d pos = p.getLerpedPos(td);
            return switch (aim.get()) {
                case "Feet" -> pos.add(0, 0.05, 0);
                case "Head" -> pos.add(0, p.getHeight() * 0.92, 0);
                default -> pos.add(0, p.getHeight() * 0.5, 0);
            };
        }

        private int baseColor(AbstractClientPlayerEntity p, double d) {
            return switch (color.get()) {
                case "Distance" -> Nvg.mix(0xFFFF465A, 0xFF7CF29A, (float) Math.min(1, d / 48));
                case "Chroma" -> 0xFF000000 | java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 4000) / 4000f + p.getId() * 0.13f, 0.6f, 1f);
                default -> VisualModules.palette(color, 0f);
            };
        }

        @Override
        public void render2d(float td) {
            float[] o = new float[2], o2 = new float[2];
            Vec3d from = start(td);
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player || !p.isAlive()) continue;
                double d = mc.player.distanceTo(p);
                if (d > range.value) continue;
                if (!Projection.segment(from, end(p, td), o, o2)) continue;
                float sx = o[0], sy = o[1], ex = o2[0], ey = o2[1];
                float a = fade.on() ? (float) Math.max(0.25, 1.0 - d / range.value) : 1f;
                int c = baseColor(p, d);
                switch (style.get()) {
                    case "Gradient" -> {
                        // clear at the start, solid at the target, like Crypto
                        if (glow.on()) Nvg.lineGradient(sx, sy, ex, ey, width.f() * 2.4f, Nvg.alpha(c, 0.04f * a), Nvg.alpha(c, 0.22f * a));
                        Nvg.lineGradient(sx, sy, ex, ey, width.f(), Nvg.alpha(c, 0.06f * a), Nvg.alpha(c, a));
                    }
                    case "Dotted" -> {
                        float dx = ex - sx, dy = ey - sy, len = (float) Math.hypot(dx, dy);
                        if (len < 4) break;
                        dx /= len;
                        dy /= len;
                        float step = 9 + width.f() * 3, dash = step * 0.55f;
                        float phase = (System.currentTimeMillis() % 1400L) / 1400f * step;
                        for (float t = phase; t < len; t += step) {
                            float e = Math.min(len, t + dash);
                            float k = 0.35f + 0.65f * (t / len);
                            float x1 = sx + dx * t, y1 = sy + dy * t, x2 = sx + dx * e, y2 = sy + dy * e;
                            if (glow.on()) Nvg.glowStroke(x1, y1, x2, y2, width.f(), width.f() + 8, c, a * k);
                            Nvg.line(x1, y1, x2, y2, width.f(), Nvg.alpha(c, a * k));
                        }
                    }
                    case "Arrow" -> {
                        float dx = ex - sx, dy = ey - sy, len = (float) Math.hypot(dx, dy);
                        if (len < 12) break;
                        dx /= len;
                        dy /= len;
                        float head = Math.min(len * 0.5f, VisualModules.pixels(end(p, td), 0.9));
                        if (head < 5) head = Math.min(len * 0.5f, 14);
                        float nx = ex - dx * head, ny = ey - dy * head, wx = -dy * head * 0.45f, wy = dx * head * 0.45f;
                        if (glow.on()) Nvg.lineGradient(sx, sy, nx, ny, width.f() * 2.4f, Nvg.alpha(c, 0.04f * a), Nvg.alpha(c, 0.2f * a));
                        Nvg.lineGradient(sx, sy, nx, ny, width.f(), Nvg.alpha(c, 0.15f * a), Nvg.alpha(c, a));
                        Nvg.beginPath();
                        Nvg.moveTo(nx + wx, ny + wy);
                        Nvg.lineTo(ex, ey);
                        Nvg.lineTo(nx - wx, ny - wy);
                        Nvg.closePath();
                        Nvg.fillPath(Nvg.alpha(c, 0.55f * a));
                        Nvg.line(nx + wx, ny + wy, ex, ey, width.f() * 0.8f, Nvg.alpha(c, a));
                        Nvg.line(nx - wx, ny - wy, ex, ey, width.f() * 0.8f, Nvg.alpha(c, a));
                    }
                    default -> {
                        // glow off means exactly one clean line: no halo, no dark outline
                        if (glow.on()) Nvg.glowStroke(sx, sy, ex, ey, width.f(), width.f() + 10, c, a);
                        Nvg.line(sx, sy, ex, ey, width.f(), Nvg.alpha(c, a));
                    }
                }
            }
        }
    }

    static final class Nametags extends Module {
        private final Setting.Bool hp = bool("Health", true);
        private final Setting.Bool dist = bool("Distance", true);
        private final Setting.Bool head = bool("Head", true);
        private final Setting.Bool items = bool("Held Item", true);
        private final Setting.Num scale = num("Scale", 1, 0.5, 2, 0.05);

        Nametags() {
            super("Nametags", "Liquid glass name plates with head, health and distance", Category.RENDER);
        }

        @Override
        public void render2d(float td) {
            float[] o = new float[2];
            List<Object[]> tags = new ArrayList<>();
            for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player || !p.isAlive()) continue;
                if (!Projection.point(p.getLerpedPos(td).add(0, p.getHeight() + 0.5, 0), o)) continue;
                tags.add(new Object[]{p, o[0], o[1], mc.player.distanceTo(p)});
            }
            tags.sort((x, y) -> Float.compare((float) y[3], (float) x[3])); // far ones first, near ones on top
            for (Object[] t : tags) {
                AbstractClientPlayerEntity p = (AbstractClientPlayerEntity) t[0];
                float cx = (float) t[1], by = (float) t[2], d = (float) t[3];
                float k = (float) MathHelper.clamp(1.15 - d * 0.012, 0.6, 1.1) * scale.f();
                float s = 13 * k, h = 26 * k, pad = 8 * k;
                String name = p.getName().getString();
                float hpVal = p.getHealth() + p.getAbsorptionAmount();
                String hpS = hp.on() ? String.format(Locale.ROOT, "%.0f", hpVal) : "";
                String dS = dist.on() ? (int) d + "m" : "";
                float hs = head.on() ? h - 8 * k : 0;
                float w = pad + (hs > 0 ? hs + 6 * k : 0) + Nvg.textWidth(name, s, Nvg.SEMI)
                        + (hpS.isEmpty() ? 0 : 8 * k + Nvg.textWidth(hpS, s * 0.9f, Nvg.SEMI)) + (dS.isEmpty() ? 0 : 8 * k + Nvg.textWidth(dS, s * 0.8f, Nvg.REG)) + pad;
                float x = cx - w / 2, y = by - h;
                tenacity.ui.Theme.hud(x, y, w, h, h / 2, 1f, 0);
                float tx = x + pad;
                if (hs > 0) {
                    if (!tenacity.gfx.Skins.head(p.getSkin(), tx - 2 * k, y + 4 * k, hs, 4 * k)) Nvg.rrect(tx - 2 * k, y + 4 * k, hs, hs, 4 * k, 0x40FFFFFF);
                    tx += hs + 4 * k;
                }
                Nvg.text(name, tx, y + h / 2 + 0.5f, s, Nvg.SEMI, Nvg.LEFT, WHITE);
                tx += Nvg.textWidth(name, s, Nvg.SEMI) + 8 * k;
                if (!hpS.isEmpty()) {
                    float f = MathHelper.clamp(p.getHealth() / Math.max(1f, p.getMaxHealth()), 0, 1);
                    Nvg.text(hpS, tx, y + h / 2 + 0.5f, s * 0.9f, Nvg.SEMI, Nvg.LEFT, Nvg.mix(0xFFFF5A5A, 0xFF7CF29A, f));
                    tx += Nvg.textWidth(hpS, s * 0.9f, Nvg.SEMI) + 8 * k;
                }
                if (!dS.isEmpty()) Nvg.text(dS, tx, y + h / 2 + 0.5f, s * 0.8f, Nvg.REG, Nvg.LEFT, 0xA0FFFFFF);
                // health bar under the plate
                if (hp.on()) {
                    float f = MathHelper.clamp(p.getHealth() / Math.max(1f, p.getMaxHealth()), 0, 1);
                    float bw = w - h, bx = x + h / 2;
                    Nvg.rrect(bx, y + h + 2 * k, bw, 2.5f * k, 1.25f * k, 0x50000000);
                    Nvg.rrect(bx, y + h + 2 * k, bw * f, 2.5f * k, 1.25f * k, Nvg.mix(0xFFFF5A5A, 0xFF7CF29A, f));
                }
                if (items.on() && !p.getMainHandStack().isEmpty())
                    glassLabel(p.getMainHandStack().getName().getString(), cx, y - 10 * k, 10.5f * k, 0xE6FFFFFF);
            }
        }
    }

    static final class ItemEsp extends Module {
        ItemEsp() {
            super("Item ESP", "Names and counts of dropped items", Category.RENDER);
        }

        @Override
        public void render2d(float td) {
            float[] o = new float[2];
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof ItemEntity item) || mc.player.distanceTo(e) > 48) continue;
                if (!Projection.point(e.getLerpedPos(td).add(0, 0.6, 0), o)) continue;
                ItemStack st = item.getStack();
                glassLabel(st.getName().getString() + (st.getCount() > 1 ? " x" + st.getCount() : ""), o[0], o[1], 10.5f, 0xF2FFFFFF);
            }
        }
    }

    static final class BlockHighlight extends Module {
        private final Setting.Mode style = mode("Style", "Frost", "Frost", "Outline", "Glow", "Corners", "Liquid");
        /** Sober's Block Overlay palette plus frosty tones. */
        private final Setting.Mode color = mode("Color", "Frost", "Frost", "Ice", "Mint", "White", "Chroma", "Galaxy", "Palm", "Opal", "Molten", "Pulse");
        private final Setting.Bool smooth = bool("Smooth", true);
        private final Setting.Num smoothSpeed = num("Smooth Speed", 14, 4, 30, 1).showIf(smooth::on);
        private final Setting.Bool fade = bool("Fade In/Out", true);
        private final Setting.Num lineWidth = num("Outline Width", 1.6, 0.5, 4, 0.1);
        private final Setting.Bool fill = bool("Fill", true);
        private final Setting.Num fillOpacity = num("Fill Opacity", 18, 0, 60, 2).showIf(fill::on);
        private final Setting.Bool gradient = bool("Fill Gradient", true).showIf(fill::on);
        private final Setting.Bool glow = bool("Glow", true);
        private final Setting.Num glowSize = num("Glow Size", 1, 0.3, 3, 0.1).showIf(glow::on);
        private final Setting.Num glowStrength = num("Glow Strength", 60, 10, 150, 5).showIf(glow::on);
        private final Setting.Num pulse = num("Pulse", 40, 0, 100, 5);
        private final Setting.Num pulseSpeed = num("Pulse Speed", 1, 0.2, 4, 0.1).showIf(() -> pulse.value > 0);
        private final Setting.Bool sheen = bool("Sheen", true);
        private final Setting.Bool walls = bool("Through Walls", false);

        private Box anim, last;
        private float vis, pulseS;
        private long lastFrame = System.nanoTime();

        BlockHighlight() {
            super("Block Highlight", "Smooth frosted / glowing highlight on the block you look at", Category.RENDER);
        }

        private int color(float seconds) {
            return switch (color.get()) {
                case "Chroma" -> 0xFF000000 | java.awt.Color.HSBtoRGB(seconds / 6f % 1, 0.6f, 1f);
                case "Pulse" -> 0xFF000000 | java.awt.Color.HSBtoRGB(seconds * 0.12f % 1, 0.75f, 1f);
                case "Frost" -> 0xFFF3F5F8;
                case "Ice" -> 0xFF9FD8FF;
                case "Mint" -> 0xFF8CFFC8;
                case "Galaxy" -> 0xFF9B50E8;
                case "Palm" -> 0xFF5FF2E4;
                case "Opal" -> 0xFFF2B8FF;
                case "Molten" -> 0xFFFF7A1F;
                default -> WHITE;
            };
        }

        private static int a(int c, float alpha) {
            return Math.round(255 * Math.max(0, Math.min(1, alpha))) << 24 | (c & 0xFFFFFF);
        }

        private static double lerp(double a, double b, double k) {
            return a + (b - a) * k;
        }

        @Override
        public void tick() {
            RenderFlags.blockHighlight = true;
        }

        @Override
        protected void onDisable() {
            anim = last = null;
            vis = 0;
            RenderFlags.blockHighlight = false;
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            long now = System.nanoTime();
            float dt = (float) MathHelper.clamp((now - lastFrame) / 1e9, 0.0005, 0.05);
            lastFrame = now;
            Box target = null;
            if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                net.minecraft.util.shape.VoxelShape shape = mc.world.getBlockState(hit.getBlockPos()).getOutlineShape(mc.world, hit.getBlockPos());
                if (!shape.isEmpty()) target = shape.getBoundingBox().offset(hit.getBlockPos()).expand(0.004);
            }
            // fade the whole highlight in / out instead of popping
            float fadeK = 1 - (float) Math.exp(-(fade.on() ? 12 : 1000) * dt);
            vis += ((target != null ? 1 : 0) - vis) * fadeK;
            if (target != null) last = target;
            if (last == null || vis < 0.01f) {
                if (target == null) anim = null;
                return;
            }
            // smooth block switch: the box glides (and resizes) to the next block
            Box goal = last;
            if (anim == null || !smooth.on() || goal.getCenter().squaredDistanceTo(anim.getCenter()) > 36) anim = goal;
            else {
                double k = 1 - Math.exp(-smoothSpeed.f() * dt);
                anim = new Box(lerp(anim.minX, goal.minX, k), lerp(anim.minY, goal.minY, k), lerp(anim.minZ, goal.minZ, k),
                        lerp(anim.maxX, goal.maxX, k), lerp(anim.maxY, goal.maxY, k), lerp(anim.maxZ, goal.maxZ, k));
            }
            float seconds = (System.currentTimeMillis() % 3600000L) / 1000f;
            // pulse: a smooth breathing between 0 and 1, eased so it never ticks
            float wave = 0.5f + 0.5f * (float) Math.sin(seconds * Math.PI * pulseSpeed.f());
            pulseS += (wave * pulse.f() / 100f - pulseS) * (1 - (float) Math.exp(-20 * dt));
            int c = color(seconds);
            boolean depth = !walls.on();
            Box b = anim;
            float v = vis;

            if (glow.on() || style.is("Glow")) {
                int layers = 7;
                float gs = glowSize.f() * (style.is("Glow") ? 1.6f : 1f) * (1 + pulseS * 0.6f);
                float str = glowStrength.f() / 100f * (style.is("Glow") ? 1.4f : 1f) * (0.75f + pulseS * 0.5f);
                for (int i = 1; i <= layers; i++) {
                    float t = i / (float) (layers + 1);
                    double e = 0.022 * i * gs;
                    float fall = (1 - t) * (1 - t);
                    Box g = b.expand(e);
                    faces(g, a(c, 0.07f * str * fall * v), a(c, 0.035f * str * fall * v), false);
                    edges(g, a(c, 0.35f * str * fall * v), 1.5f + i * 1.2f * gs, false);
                }
            }
            if (fill.on() || style.is("Frost") || style.is("Liquid")) {
                float fa = (fill.on() ? fillOpacity.f() / 100f : 0.12f) * (1 + pulseS * 0.35f) * v;
                if (style.is("Frost")) {
                    // frosted glass: milky cool white faces, brighter at the bottom, plus a soft inner rim
                    int frost = blend(c, 0xFFFFFFFF, 0.55f);
                    faces(b, a(frost, fa * 1.25f), a(frost, fa * (gradient.on() ? 0.45f : 1.25f)), depth);
                    faces(b.contract(0.04), a(0xFFFFFFFF, fa * 0.35f), a(0xFFFFFFFF, fa * 0.1f), depth);
                } else if (style.is("Liquid")) {
                    // liquid glass: clear body with a bright rolling caustic band
                    faces(b, a(c, fa * 0.6f), a(c, fa * 0.25f), depth);
                } else faces(b, a(c, fa), a(c, fa * (gradient.on() ? 0.3f : 1f)), depth);
            }
            if (sheen.on() || style.is("Liquid")) {
                // a soft light band sweeping up the sides
                double h = b.maxY - b.minY;
                double y = b.minY + ((seconds * 0.45f) % 1.6f - 0.3f) * h;
                double band = h * 0.22;
                double y0 = Math.max(b.minY, y - band), y1 = Math.min(b.maxY, y + band);
                if (y1 > y0) {
                    int hi = a(0xFFFFFFFF, (style.is("Liquid") ? 0.32f : 0.18f) * v), lo = a(0xFFFFFFFF, 0);
                    double ym = MathHelper.clamp(y, y0, y1);
                    sideBand(b, y0, ym, lo, hi, depth);
                    sideBand(b, ym, y1, hi, lo, depth);
                }
            }
            float lw = lineWidth.f() * (1 + pulseS * 0.25f);
            if (style.is("Corners")) corners(b, a(c, v), lw + 0.6f, depth);
            else if (!style.is("Glow")) {
                if (!style.is("Frost") && !style.is("Liquid")) edges(b, a(0xFF000000, 0.35f * v), lw + 1.6f, depth);
                edges(b, a(style.is("Frost") ? blend(c, 0xFFFFFFFF, 0.4f) : c, (style.is("Frost") ? 0.85f : 1f) * v), lw, depth);
            } else edges(b, a(blend(c, 0xFFFFFFFF, 0.5f), 0.9f * v), lw * 0.8f, depth);
        }

        private static int blend(int x, int y, float t) {
            int r = (int) lerp((x >> 16) & 255, (y >> 16) & 255, t), g = (int) lerp((x >> 8) & 255, (y >> 8) & 255, t), bb = (int) lerp(x & 255, y & 255, t);
            return 0xFF000000 | r << 16 | g << 8 | bb;
        }

        /** Six faces, colour {@code bottom} at the low edge fading to {@code top}. */
        private static void faces(Box b, int bottom, int top, boolean depth) {
            Vec3d p000 = new Vec3d(b.minX, b.minY, b.minZ), p100 = new Vec3d(b.maxX, b.minY, b.minZ), p101 = new Vec3d(b.maxX, b.minY, b.maxZ), p001 = new Vec3d(b.minX, b.minY, b.maxZ);
            Vec3d p010 = new Vec3d(b.minX, b.maxY, b.minZ), p110 = new Vec3d(b.maxX, b.maxY, b.minZ), p111 = new Vec3d(b.maxX, b.maxY, b.maxZ), p011 = new Vec3d(b.minX, b.maxY, b.maxZ);
            Render3D.drawGradientQuad(p000, p100, p101, p001, bottom, bottom, bottom, bottom, depth);
            Render3D.drawGradientQuad(p010, p011, p111, p110, top, top, top, top, depth);
            Render3D.drawGradientQuad(p000, p010, p110, p100, bottom, top, top, bottom, depth);
            Render3D.drawGradientQuad(p100, p110, p111, p101, bottom, top, top, bottom, depth);
            Render3D.drawGradientQuad(p101, p111, p011, p001, bottom, top, top, bottom, depth);
            Render3D.drawGradientQuad(p001, p011, p010, p000, bottom, top, top, bottom, depth);
        }

        private static void sideBand(Box b, double y0, double y1, int c0, int c1, boolean depth) {
            double e = 0.002;
            Vec3d a0 = new Vec3d(b.minX - e, y0, b.minZ - e), b0 = new Vec3d(b.maxX + e, y0, b.minZ - e), c0v = new Vec3d(b.maxX + e, y0, b.maxZ + e), d0 = new Vec3d(b.minX - e, y0, b.maxZ + e);
            Vec3d a1 = new Vec3d(b.minX - e, y1, b.minZ - e), b1 = new Vec3d(b.maxX + e, y1, b.minZ - e), c1v = new Vec3d(b.maxX + e, y1, b.maxZ + e), d1 = new Vec3d(b.minX - e, y1, b.maxZ + e);
            Render3D.drawGradientQuad(a0, a1, b1, b0, c0, c1, c1, c0, depth);
            Render3D.drawGradientQuad(b0, b1, c1v, c0v, c0, c1, c1, c0, depth);
            Render3D.drawGradientQuad(c0v, c1v, d1, d0, c0, c1, c1, c0, depth);
            Render3D.drawGradientQuad(d0, d1, a1, a0, c0, c1, c1, c0, depth);
        }

        private static void edges(Box b, int c, float w, boolean depth) {
            Render3D.drawBox(b, c, w, true, false, depth);
        }

        private static void corners(Box b, int c, float w, boolean depth) {
            double lx = (b.maxX - b.minX) * 0.28, ly = (b.maxY - b.minY) * 0.28, lz = (b.maxZ - b.minZ) * 0.28;
            for (int i = 0; i < 8; i++) {
                double x = (i & 1) == 0 ? b.minX : b.maxX, y = (i & 2) == 0 ? b.minY : b.maxY, z = (i & 4) == 0 ? b.minZ : b.maxZ;
                double sx = (i & 1) == 0 ? lx : -lx, sy = (i & 2) == 0 ? ly : -ly, sz = (i & 4) == 0 ? lz : -lz;
                Vec3d p = new Vec3d(x, y, z);
                Render3D.drawLine(p, p.add(sx, 0, 0), c, w, depth);
                Render3D.drawLine(p, p.add(0, sy, 0), c, w, depth);
                Render3D.drawLine(p, p.add(0, 0, sz), c, w, depth);
            }
        }
    }

    static final class Breadcrumbs extends Module {
        private final Setting.Mode style = mode("Style", "Ribbon", "Ribbon", "Glow Line", "Frost", "Rainbow", "Comet", "Helix", "Dots", "Lightning");
        private final Setting.Num life = num("Length", 30, 5, 300, 5);
        private final Setting.Num width = num("Width", 0.12, 0.03, 0.4, 0.01);
        private final Setting.Num glow = num("Glow", 1, 0, 2.5, 0.1);
        private final Setting.Bool walls = bool("Through Walls", false);
        private final Setting.Mode color = VisualModules.colors(this);
        private final Deque<double[]> points = new ArrayDeque<>(); // x y z time

        Breadcrumbs() {
            super("Breadcrumbs", "A smooth glowing ribbon on the ground where you walked", Category.RENDER);
        }

        @Override
        protected void onDisable() {
            points.clear();
        }

        @Override
        public void tick() {
            double x = mc.player.getX(), y = mc.player.getY() + 0.04, z = mc.player.getZ();
            double[] l = points.peekLast();
            if (l != null && (x - l[0]) * (x - l[0]) + (y - l[1]) * (y - l[1]) + (z - l[2]) * (z - l[2]) > 64) points.clear();
            if (l == null || (x - l[0]) * (x - l[0]) + (y - l[1]) * (y - l[1]) + (z - l[2]) * (z - l[2]) > 0.04)
                points.addLast(new double[]{x, y, z, MoreVisuals.VFx.now()});
            while (points.size() > 1500) points.removeFirst();
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            MoreVisuals.VFx.frame();
            double now = MoreVisuals.VFx.now();
            float L = life.f();
            while (!points.isEmpty() && now - points.peekFirst()[3] > L) points.removeFirst();
            if (points.isEmpty()) return;
            List<Vec3d> pts = new ArrayList<>();
            float[] fades = new float[points.size() + 1];
            int i = 0;
            for (double[] s : points) {
                pts.add(new Vec3d(s[0], s[1], s[2]));
                // long trails: only the last 25% of the lifetime fades out
                fades[i++] = MoreVisuals.VFx.smooth((float) ((1 - (now - s[3]) / L) * 4));
            }
            Vec3d head = mc.player.getLerpedPos(mc.getRenderTickCounter().getTickProgress(true)).add(0, 0.04, 0);
            if (head.squaredDistanceTo(pts.get(pts.size() - 1)) > 1e-6) {
                pts.add(head);
                fades[i++] = 1;
            }
            if (pts.size() < 2) return;
            MoreVisuals.trail(pts, java.util.Arrays.copyOf(fades, pts.size()), style.get(), color, width.f(), glow.f(), MoreVisuals.VFx.FLAT, !walls.on(), 7);
        }
    }

    static final class DamageNumbers extends Module {
        private final Map<Integer, Float> last = new HashMap<>();
        private final List<Object[]> pops = new ArrayList<>();

        DamageNumbers() {
            super("Damage Numbers", "Floating numbers when entities take damage", Category.RENDER);
        }

        @Override
        public void tick() {
            Map<Integer, Float> now = new HashMap<>();
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof LivingEntity le && e != mc.player && mc.player.distanceTo(e) <= 32) {
                    float hp = le.getHealth();
                    Float was = last.get(e.getId());
                    if (was != null && hp < was - 0.05f) {
                        float dmg = was - hp;
                        pops.add(new Object[]{
                                e.getEntityPos().add((Math.random() - 0.5) * 0.6, e.getHeight() * 0.85, (Math.random() - 0.5) * 0.6),
                                dmg, e.getId(), System.currentTimeMillis()
                        });
                    }
                    now.put(e.getId(), hp);
                }
            }
            last.clear();
            last.putAll(now);
        }

        @Override
        public void render2d(float td) {
            float[] o = new float[2];
            long now = System.currentTimeMillis();
            Iterator<Object[]> it = pops.iterator();
            while (it.hasNext()) {
                Object[] p = it.next();
                float t = (now - (long) p[3]) / 1100f;
                if (t >= 1) {
                    it.remove();
                    continue;
                }
                if (!Projection.point(((Vec3d) p[0]).add(0, t * 0.8, 0), o)) continue;
                float dmg = (float) p[1];
                int seed = (int) p[2];
                // quick overshoot on spawn, then settle while it floats up
                float pop = t < 0.12f ? 1 + (float) Math.sin(t / 0.12 * Math.PI) * 0.35f : 1f;
                float size = (13 + MathHelper.clamp(dmg, 1, 30) * 0.65f) * pop * (1 + 0.25f * (1 - t));
                int c = colorFor(dmg, seed);
                String s = dmg < 10 ? String.format(Locale.ROOT, "-%.1f", dmg) : "-" + (int) Math.round(dmg);
                Nvg.text(s, o[0] + 1.2f, o[1] + 1.2f, size, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(SHADOW, 0.75f * (1 - t)));
                Nvg.text(s, o[0], o[1], size, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(c, 1 - t));
            }
        }

        /** Soft amber for small hits, hot red for big ones, and a shimmer for huge crits. */
        private int colorFor(float dmg, int seed) {
            float heat = MathHelper.clamp(dmg / 14f, 0f, 1f);
            if (heat > 0.75f)
                return 0xFF000000 | java.awt.Color.HSBtoRGB((System.currentTimeMillis() % 1200) / 1200f + seed * 0.37f, 0.85f, 1f);
            return Nvg.mix(0xFFFFE08A, 0xFFFF4040, heat);
        }
    }

    static final class Compass extends Module {
        private static final String[] CARDINALS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

        Compass() {
            super("Compass", "Heading strip at the top of the screen", Category.RENDER);
        }

        @Override
        public void render2d(float td) {
            float w = 360, x = Nvg.w / 2f - w / 2, y = 10, h = 30;
            tenacity.ui.Theme.hud(x, y, w, h, h / 2, 1f, 0); // liquid glass strip
            float yaw = MathHelper.wrapDegrees(mc.player.getYaw(td) + 180);
            Nvg.save();
            Nvg.clip(x + 10, y, w - 20, h);
            for (int deg = -180; deg <= 540; deg += 15) {
                float dx = MathHelper.wrapDegrees(deg - yaw) * 3;
                if (Math.abs(dx) > w / 2) continue;
                float px = Nvg.w / 2f + dx;
                if (deg % 45 == 0) {
                    String s = CARDINALS[Math.floorMod(deg / 45, 8)];
                    Nvg.text(s, px, y + h / 2f + 0.5f, s.length() == 1 ? 13 : 11, Nvg.SEMI, Nvg.CENTER, s.equals("N") ? WHITE : 0xC0FFFFFF);
                } else {
                    Nvg.line(px, y + 11, px, y + h - 11, 1, 0x50FFFFFF);
                }
            }
            Nvg.restore();
            Nvg.rrect(Nvg.w / 2f - 1, y + h - 4, 2, 6, 1, WHITE);
        }
    }

    static final class Crosshair extends Module {
        private final Setting.Mode style = mode("Style", "Cross", "Cross", "Dot", "Circle");
        private final Setting.Num gap = num("Gap", 3, 0, 10, 0.5);
        private final Setting.Bool dynamic = bool("Dynamic", true);

        Crosshair() {
            super("Crosshair", "Replaces the vanilla crosshair", Category.RENDER);
        }

        @Override
        public void tick() {
            RenderFlags.customCrosshair = true;
        }

        @Override
        protected void onDisable() {
            RenderFlags.customCrosshair = false;
        }

        @Override
        public void render2d(float td) {
            if (!mc.options.getPerspective().isFirstPerson() && !CryptoFreeCam.on() && !tenacity.gfx.FreecamFx.freecam) return;
            float cx = Nvg.w / 2f, cy = Nvg.h / 2f;
            float g = gap.f() + (dynamic.on() ? (float) mc.player.getVelocity().horizontalLength() * 18 : 0);
            switch (style.get()) {
                case "Dot" -> {
                    Nvg.circle(cx, cy, 3, SHADOW);
                    Nvg.circle(cx, cy, 2, WHITE);
                }
                case "Circle" -> {
                    Nvg.circleStroke(cx, cy, 5 + g, 3, SHADOW);
                    Nvg.circleStroke(cx, cy, 5 + g, 1.4f, WHITE);
                }
                default -> {
                    for (int i = 0; i < 2; i++) {
                        float sw = i == 0 ? 3.4f : 1.6f;
                        int c = i == 0 ? SHADOW : WHITE;
                        Nvg.line(cx - g - 6, cy, cx - g, cy, sw, c);
                        Nvg.line(cx + g, cy, cx + g + 6, cy, sw, c);
                        Nvg.line(cx, cy - g - 6, cx, cy - g, sw, c);
                        Nvg.line(cx, cy + g, cx, cy + g + 6, sw, c);
                    }
                }
            }
            float charge = mc.player.getAttackCooldownProgress(0f);
            if (charge < 1) {
                Nvg.rrect(cx - 12, cy + 14, 24, 2.5f, 1.25f, 0x50FFFFFF);
                Nvg.rrect(cx - 12, cy + 14, 24 * charge, 2.5f, 1.25f, WHITE);
            }
        }
    }

    static final class DeathWaypoint extends Module {
        private Vec3d death;
        private boolean wasDead;

        DeathWaypoint() {
            super("Death Waypoint", "Marks the spot where you last died", Category.RENDER);
        }

        @Override
        public void tick() {
            boolean dead = mc.player.isDead() || mc.player.getHealth() <= 0;
            if (dead && !wasDead) death = mc.player.getEntityPos();
            wasDead = dead;
        }

        @Override
        public void render2d(float td) {
            if (death == null) return;
            float[] o = new float[2];
            if (Projection.point(death.add(0, 1, 0), o))
                label("Death  " + (int) mc.player.getEntityPos().distanceTo(death) + "m", o[0], o[1], 12, WHITE);
        }
    }

    static final class KillEffects extends Module implements tenacity.util.PacketHook.Receiver {
        static KillEffects I;
        private final Setting.Mode effect = mode("Effect", "Lightning", "Lightning", "Lightning Strike", "Soul", "Shatter", "Ink Splash", "Firework",
                "Black Hole", "Angel Wings", "Disintegrate", "Ice Freeze", "Confetti", "Tornado", "Random");
        private final Setting.Mode color = VisualModules.colors(this);
        private final Setting.Bool sound = bool("Sound", true);
        private final Setting.Bool cutscene = bool("Kill Cutscene", false);
        private final Setting.Mode cutShot = mode("Cutscene", "Orbit", "Orbit", "Dolly Zoom", "Slow Push-In", "Crane Up", "Spiral Out", "Side Pan",
                "Low Angle Hero", "Matrix 360", "Drop Down", "Whip Pan", "Random");
        private final Setting.Mode titleCard = mode("Title Card", "Eliminated", "Eliminated", "None");
        private final Setting.Num cutLength = num("Cutscene Length", 2.6, 1.5, 5, 0.1);
        private final Setting.Bool playersOnly = bool("Cutscene Players Only", true);
        private final Setting.Bool letterbox = bool("Letterbox", true);
        private final Setting.Bool cancelKey = bool("Cancel On Key", true);
        private final Setting.Bool cancelDamage = bool("Cancel On Damage", true);
        /** Entities you hit recently: id -> hit time (ms). A death within the window counts as your kill. */
        private final Map<Integer, Long> recent = new HashMap<>();
        private final List<KillFx.Kill> running = new ArrayList<>();
        private String victim = "";
        private int lastHurt;
        private boolean[] keysAtStart = new boolean[0];
        private final java.util.Random rng = new java.util.Random();

        KillEffects() {
            super("Kill Effects", "Cinematic effects (and an optional cutscene) when you kill something", Category.RENDER);
            I = this;
        }

        void hit(Entity e) {
            if (enabled()) recent.put(e.getId(), System.currentTimeMillis());
        }

        @Override
        protected void onDisable() {
            recent.clear();
            running.clear();
            tenacity.gfx.CutsceneCam.stop();
        }

        @Override
        public void onPacket(net.minecraft.network.packet.Packet<?> packet) {
            if (packet instanceof net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket s && s.getStatus() == 3) {
                mc.execute(() -> {
                    if (mc.world == null) return;
                    Entity e = s.getEntity(mc.world);
                    if (e != null && recent.containsKey(e.getId())) kill(e);
                });
            }
        }

        @Override
        public void tick() {
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<Integer, Long>> it = recent.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                Entity e = mc.world.getEntityById(en.getKey());
                if (e == null || now - en.getValue() > 4000 || mc.player.distanceTo(e) > 48) {
                    it.remove();
                    continue;
                }
                boolean dead = e instanceof LivingEntity le ? (!le.isAlive() || le.getHealth() <= 0 || le.isDead())
                        : e.isRemoved() && e.getRemovalReason() == Entity.RemovalReason.KILLED;
                if (dead) {
                    it.remove();
                    kill(e);
                }
            }
            // cutscene cancel: damage, or a key that was not already held when it started
            if (tenacity.gfx.CutsceneCam.active) {
                if (cancelDamage.on() && mc.player.hurtTime > lastHurt) tenacity.gfx.CutsceneCam.cancel();
                if (cancelKey.on()) {
                    boolean[] k = keys();
                    for (int i = 0; i < k.length; i++) {
                        if (k[i] && !keysAtStart[i]) tenacity.gfx.CutsceneCam.cancel();
                        if (!k[i]) keysAtStart[i] = false; // released: a later press counts
                    }
                }
            }
            lastHurt = mc.player.hurtTime;
        }

        private boolean[] keys() {
            var o = mc.options;
            return new boolean[]{o.forwardKey.isPressed(), o.backKey.isPressed(), o.leftKey.isPressed(), o.rightKey.isPressed(), o.jumpKey.isPressed(),
                    o.sneakKey.isPressed(), o.attackKey.isPressed(), o.useKey.isPressed(), o.inventoryKey.isPressed(), o.dropKey.isPressed()};
        }

        /** Starts the effect (and cutscene) for {@code e}. Public to the package for the dev harness. */
        void kill(Entity e) {
            recent.remove(e.getId());
            String type = effect.get();
            if (type.equals("Random")) type = KillFx.NAMES[rng.nextInt(KillFx.NAMES.length)];
            Vec3d pos = e.getEntityPos();
            if (type.equals("Lightning")) {
                LightningEntity bolt = new LightningEntity(EntityType.LIGHTNING_BOLT, mc.world);
                bolt.refreshPositionAfterTeleport(pos);
                mc.world.addEntity(bolt);
                if (sound.on()) mc.player.playSound(net.minecraft.sound.SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1f);
            } else {
                running.add(new KillFx.Kill(pos, e.getHeight(), Math.max(0.4, e.getWidth()), e.getYaw(), type, MoreVisuals.VFx.now(),
                        e.getId() * 7919 + (int) (System.nanoTime() & 0xFFFF), VisualModules.palette(color, 0)));
                if (sound.on()) mc.player.playSound(switch (type) {
                    case "Lightning Strike" -> net.minecraft.sound.SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER;
                    case "Shatter", "Ice Freeze" -> net.minecraft.sound.SoundEvents.BLOCK_GLASS_BREAK;
                    case "Firework" -> net.minecraft.sound.SoundEvents.ENTITY_FIREWORK_ROCKET_LARGE_BLAST;
                    case "Confetti" -> net.minecraft.sound.SoundEvents.ENTITY_FIREWORK_ROCKET_TWINKLE;
                    case "Soul", "Angel Wings" -> net.minecraft.sound.SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
                    case "Black Hole", "Disintegrate" -> net.minecraft.sound.SoundEvents.BLOCK_BEACON_DEACTIVATE;
                    default -> net.minecraft.sound.SoundEvents.ENTITY_PLAYER_ATTACK_CRIT;
                }, type.equals("Lightning Strike") ? 0.5f : 0.8f, 1f);
            }
            if (cutscene.on() && (!playersOnly.on() || e instanceof PlayerEntity) && !tenacity.gfx.FreecamFx.freecam) {
                keysAtStart = keys();
                lastHurt = mc.player.hurtTime;
                tenacity.gfx.CutsceneCam.start(pos, e.getHeight(), cutLength.f(), cutShot.get());
            }
        }

        @Override
        public void renderWorld(voided.events.impl.WorldRenderEvent event) {
            MoreVisuals.VFx.frame();
            double now = MoreVisuals.VFx.now();
            running.removeIf(k -> now - k.born() > KillFx.life(k.type()));
            for (KillFx.Kill k : running) KillFx.render(k, (float) (now - k.born()));
        }

        @Override
        public void render2d(float td) {
            tenacity.gfx.CutsceneCam.frame();
            if (!tenacity.gfx.CutsceneCam.active) return;
            float w = tenacity.gfx.CutsceneCam.overlay();
            if (w < 0.005f) return;
            float W = Nvg.w, H = Nvg.h;
            // vignette
            MoreVisuals.HitVignette.edges(W, H, 0.28f, Nvg.alpha(0xFF000000, 0.55f * w));
            // letterbox bars sliding in
            if (letterbox.on()) {
                float bh = H * 0.105f * w;
                Nvg.rect(0, 0, W, bh, 0xFF000000);
                Nvg.rect(0, H - bh, W, bh, 0xFF000000);
                Nvg.rect(0, bh, W, 1, Nvg.alpha(0xFFFFFFFF, 0.08f * w));
                Nvg.rect(0, H - bh - 1, W, 1, Nvg.alpha(0xFFFFFFFF, 0.08f * w));
            }
            // glass title card: fades and rises in after the camera settles, out with the glide back
            float t = tenacity.gfx.CutsceneCam.time();
            float card = MoreVisuals.VFx.smooth((t - 0.35f) / 0.5f) * MoreVisuals.VFx.smooth((w - 0.35f) / 0.65f);
            if (card < 0.01f || titleCard.get().equals("None")) return;
            float rise = (1 - MoreVisuals.VFx.outCubic(Math.min(1, (t - 0.35f) / 0.6f))) * 14;
            // clean glass pill with a letter-spaced ELIMINATED (no victim name)
            String lab = "E L I M I N A T E D";
            float ts = 17;
            float tw = Nvg.textWidth(lab, ts, Nvg.SEMI);
            float cw = tw + 64, chh = 46;
            float cx = W / 2 - cw / 2, cy = H * 0.72f - chh / 2 + rise;
            Nvg.glowDisc(W / 2, cy + chh / 2, cw * 0.65f, Nvg.alpha(0xFF000000, 0.35f * card), 0);
            Nvg.rrectGradient(cx, cy, cw, chh, chh / 2, cx, cy, cx, cy + chh, Nvg.alpha(0xFF2A2D33, 0.6f * card), Nvg.alpha(0xFF0C0D10, 0.72f * card));
            Nvg.rrectGradient(cx + 1, cy + 1, cw - 2, chh * 0.48f, chh / 2 - 1, cx, cy, cx, cy + chh * 0.48f, Nvg.alpha(0xFFFFFFFF, 0.10f * card), 0);
            Nvg.rrectStroke(cx, cy, cw, chh, chh / 2, 1, Nvg.alpha(0xFFF3F5F8, 0.28f * card));
            float ty = cy + chh / 2;
            Nvg.textBlur(lab, W / 2, ty, ts, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(0xFFFFFFFF, 0.3f * card), 8);
            Nvg.text(lab, W / 2, ty, ts, Nvg.SEMI, Nvg.CENTER, Nvg.alpha(0xFFF3F5F8, card));
            float lw = 56 * card, lx = cw / 2 + 10;
            Nvg.lineGradient(W / 2 - lw - lx, ty, W / 2 - lx, ty, 1, 0, Nvg.alpha(0xFFF3F5F8, 0.5f * card));
            Nvg.lineGradient(W / 2 + lx, ty, W / 2 + lx + lw, ty, 1, Nvg.alpha(0xFFF3F5F8, 0.5f * card), 0);
        }
    }

    static final class Fullbright extends Module {
        Fullbright() {
            super("Fullbright", "See everything in the dark", Category.RENDER);
        }

        @Override
        public void tick() {
            StatusEffectInstance eff = mc.player.getStatusEffect(StatusEffects.NIGHT_VISION);
            if (eff == null || eff.getDuration() < 400)
                mc.player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 100000, 0, false, false, false));
        }

        @Override
        protected void onDisable() {
            if (mc.player != null) mc.player.removeStatusEffect(StatusEffects.NIGHT_VISION);
        }
    }

    static final class NoShading extends Module {
        NoShading() {
            super("No Shading", "Removes the darker shade on block sides", Category.RENDER);
        }

        @Override
        protected void onEnable() {
            RenderFlags.noShading = true;
            if (mc.worldRenderer != null) mc.worldRenderer.reload();
        }

        @Override
        protected void onDisable() {
            RenderFlags.noShading = false;
            if (mc.worldRenderer != null) mc.worldRenderer.reload();
        }
    }

    static final class NoHurtCam extends Module {
        NoHurtCam() {
            super("No Hurt Cam", "No camera shake when you take damage", Category.RENDER);
        }

        @Override
        protected void onEnable() {
            RenderFlags.noHurtCam = true;
        }

        @Override
        protected void onDisable() {
            RenderFlags.noHurtCam = false;
        }
    }

    static final class NoFire extends Module {
        NoFire() {
            super("No Fire", "Removes the fire overlay", Category.RENDER);
        }

        @Override
        protected void onEnable() {
            RenderFlags.noFire = true;
        }

        @Override
        protected void onDisable() {
            RenderFlags.noFire = false;
        }
    }

    static final class NoWeather extends Module {
        NoWeather() {
            super("No Weather", "Hides rain, snow and thunder", Category.RENDER);
            setEnabled(true);
        }

        @Override
        public void tick() {
            // config restore does not call onEnable, so keep the flag in sync every tick
            RenderFlags.noWeather = enabled();
        }

        @Override
        protected void onEnable() {
            RenderFlags.noWeather = true;
        }

        @Override
        protected void onDisable() {
            RenderFlags.noWeather = false;
        }
    }

    static final class TimeChanger extends Module {
        private final Setting.Num time = num("Time", 6000, 0, 24000, 500);

        TimeChanger() {
            super("Time Changer", "Client side time of day", Category.RENDER);
        }

        @Override
        public void tick() {
            RenderFlags.time = time.i();
        }

        @Override
        protected void onDisable() {
            RenderFlags.time = -1L;
        }
    }

    static final class CustomFov extends Module {
        private final Setting.Num fov = num("FOV", 100, 30, 140, 1);

        CustomFov() {
            super("Custom FOV", "Sets your field of view, up to 140, smoothly", Category.RENDER);
        }

        @Override
        public String suffix() {
            return fov.i() + "";
        }

        @Override
        public void tick() {
            RenderFlags.customFov = fov.f();
        }

        @Override
        protected void onDisable() {
            RenderFlags.customFov = 0;
        }
    }

    static final class AspectRatio extends Module {
        private final Setting.Mode ratio = mode("Ratio", "4:3", "4:3", "5:4", "16:10", "16:9", "21:9", "1:1", "Custom");
        private final Setting.Num custom = num("Custom Ratio", 1.33, 0.5, 3, 0.01).showIf(() -> ratio.is("Custom"));

        AspectRatio() {
            super("Aspect Ratio", "Stretched resolution look (4:3, 16:10, ...), smoothly", Category.RENDER);
        }

        @Override
        public String suffix() {
            return ratio.get();
        }

        @Override
        public void tick() {
            RenderFlags.aspectTarget = switch (ratio.get()) {
                case "5:4" -> 1.25f;
                case "16:10" -> 1.6f;
                case "16:9" -> 16f / 9f;
                case "21:9" -> 21f / 9f;
                case "1:1" -> 1f;
                case "Custom" -> custom.f();
                default -> 4f / 3f;
            };
        }

        @Override
        protected void onDisable() {
            RenderFlags.aspectTarget = 0;
        }
    }

    static final class Zoom extends Module {
        static float target = 1, current = 1;
        private static long last = System.nanoTime();
        static Zoom INSTANCE;
        private final Setting.Num amount = num("Zoom", 4, 1.5, 12, 0.5);
        private final Setting.Mode modeS = mode("Mode", "Hold", "Hold", "Toggle");
        private final Setting.Bool smooth = bool("Smooth", true);
        private final Setting.Num smoothness = num("Smoothness", 5, 1, 10, 1);
        private final Setting.Bool scroll = bool("Scroll Adjust", true);
        private final Setting.Bool cinematic = bool("Cinematic Camera", false);
        private Double oldSens;
        private Boolean oldCine;
        private float extra = 1;

        Zoom() {
            super("Zoom", "Smoothly zooms in (bind it to a key: hold or toggle, scroll to adjust)", Category.RENDER);
            INSTANCE = this;
            smoothness.showIf(smooth::on);
        }

        @Override
        public String suffix() {
            return amount.display() + "x";
        }

        /** Called for every fov query; eases the zoom in log space so in and out feel equally even. */
        static float apply(float fov) {
            long now = System.nanoTime();
            float dt = Math.min(0.1f, (now - last) / 1e9f);
            last = now;
            float rate = INSTANCE == null ? 13f : INSTANCE.smooth.on() ? 22f - INSTANCE.smoothness.f() * 1.8f : 1000f;
            double lc = Math.log(current), lt = Math.log(target);
            lc += (lt - lc) * (1 - Math.exp(-rate * dt));
            current = (float) Math.exp(lc);
            if (Math.abs(current - target) < 0.0005f) current = target;
            if (INSTANCE != null && INSTANCE.oldSens != null && mc.options != null) // sensitivity follows the zoom as it glides
                mc.options.getMouseSensitivity().setValue(INSTANCE.oldSens / Math.max(1.0, Math.sqrt(current)));
            return fov / current;
        }

        static boolean onScroll(double v) {
            if (INSTANCE == null || !INSTANCE.enabled() || !INSTANCE.scroll.on() || v == 0) return false;
            INSTANCE.extra = Math.max(0.25f, Math.min(4f, INSTANCE.extra * (v > 0 ? 1.15f : 1 / 1.15f)));
            target = Math.max(1.1f, Math.min(50f, INSTANCE.amount.f() * INSTANCE.extra));
            return true;
        }

        @Override
        protected void onEnable() {
            extra = 1;
            target = amount.f();
            if (oldSens == null) oldSens = mc.options.getMouseSensitivity().getValue();
            if (cinematic.on()) {
                oldCine = mc.options.smoothCameraEnabled;
                mc.options.smoothCameraEnabled = true;
            }
        }

        @Override
        public void tick() {
            if (modeS.is("Hold") && bind.code != -1 && bind.code > -100 && !net.minecraft.client.util.InputUtil.isKeyPressed(mc.getWindow(), bind.code)) {
                setEnabled(false);
                return;
            }
            target = Math.max(1.1f, Math.min(50f, amount.f() * extra));
            if (oldSens == null) oldSens = mc.options.getMouseSensitivity().getValue();
        }

        @Override
        protected void onDisable() {
            target = 1;
            if (oldSens != null && mc.options != null) mc.options.getMouseSensitivity().setValue(oldSens);
            oldSens = null;
            if (oldCine != null && mc.options != null) mc.options.smoothCameraEnabled = oldCine;
            oldCine = null;
        }
    }

    static final class NoBob extends Module {
        private Boolean old;

        NoBob() {
            super("No Bob", "Turns off view bobbing", Category.RENDER);
        }

        @Override
        public void tick() {
            if (old == null) old = mc.options.getBobView().getValue();
            mc.options.getBobView().setValue(false);
        }

        @Override
        protected void onDisable() {
            if (old != null) mc.options.getBobView().setValue(old);
            old = null;
        }
    }

    static final class NoNausea extends Module {
        private Double old;

        NoNausea() {
            super("No Nausea", "Removes the nausea and portal wobble", Category.RENDER);
        }

        @Override
        public void tick() {
            if (old == null) old = mc.options.getDistortionEffectScale().getValue();
            mc.options.getDistortionEffectScale().setValue(0.0);
        }

        @Override
        protected void onDisable() {
            if (old != null) mc.options.getDistortionEffectScale().setValue(old);
            old = null;
        }
    }

    static final class HandShaders extends Module {
        private final Setting.Mode preset = mode("Preset", "Custom", "Custom", "Ghost", "Neon", "Outline", "Chroma", "Frost", "Shadow", "Hologram", "Pulse", "Rim", "Glass", "Thermal", "Night Vision", "Retro", "Glitch", "Dissolve", "Water");
        private final Setting.Num opacity = num("Opacity", 70, 0, 100, 5);
        private final Setting.Mode tint = mode("Tint", "Off", "Off", "White", "Black", "Chroma");
        private final Setting.Num tintAmount = num("Tint Amount", 50, 0, 100, 5);
        private final Setting.Bool glow = bool("Glow Outline", true);
        private final Setting.Mode glowColor = mode("Glow Color", "White", "White", "Item", "Black", "Chroma");
        private final Setting.Num outline = num("Outline Width", 1, 0, 3, 1);
        private final Setting.Bool shimmerS = bool("Shimmer", false);
        private final Setting.Num glowSize = num("Glow Size", 4, 1, 12, 0.5);
        private final Setting.Num glowStrength = num("Glow Strength", 80, 10, 150, 5);
        private final Setting.Num saturation = num("Saturation", 100, 0, 200, 5);
        private final Setting.Bool rimLight = bool("Rim Light", false);
        private final Setting.Bool scan = bool("Scanlines", false);
        private final Setting.Bool pulseS = bool("Pulse Glow", false);
        private final Setting.Mode effect = mode("Effect", "None", "None", "Caustic", "Plasma", "Lightning", "Aurora", "Galaxy", "Chrome", "Holo Foil");
        private final Setting.Mode effectColor = mode("Effect Color", "Purple", "White", "Purple", "Ice", "Rose", "Gold", "Chroma");
        private final Setting.Num effectAmount = num("Effect Amount", 80, 0, 100, 5);
        private final Setting.Num effectSpeed = num("Effect Speed", 1, 0.1, 3, 0.1);
        private final Setting.Num effectScale = num("Effect Scale", 1, 0.3, 3, 0.1);
        private final Setting.Num aberr = num("Aberration", 0, 0, 100, 5);
        private final Setting.Num breatheS = num("Breathe", 0, 0, 100, 5);
        private final Setting.Num sweepS = num("Light Sweep", 0, 0, 100, 5);
        private final Setting.Num frostS = num("Frost", 0, 0, 100, 5);
        private final Setting.Mode frostTintS = mode("Frost Tint", "Frost", "Frost", "Ice", "Clear", "Smoke", "Rose", "Mint");
        private final Setting.Num refrS = num("Refraction", 0, 0, 100, 5);
        private final Setting.Num fresnelS = num("Edge Light", 0, 0, 100, 5);
        private final Setting.Mode gradeS = mode("Color Grade", "None", "None", "Thermal", "Invert", "Sepia", "Night Vision", "Duotone", "Mono");
        private final Setting.Num pixS = num("Pixelate", 0, 0, 12, 1);
        private final Setting.Num postS = num("Posterize", 0, 0, 12, 1);
        private final Setting.Num glitchS = num("Glitch", 0, 0, 100, 5);
        private final Setting.Num dissolveS = num("Dissolve", 0, 0, 100, 5);
        private final Setting.Num wobbleS = num("Wobble", 0, 0, 100, 5);

        HandShaders() {
            super("Hand Shaders", "Transparent, tinted or glowing first person hand", Category.RENDER);
            for (Setting s : new Setting[]{opacity, tint, tintAmount, glow, glowColor, glowSize, glowStrength, saturation, rimLight, scan, pulseS})
                s.showIf(() -> preset.is("Custom"));
            for (Setting s : new Setting[]{effectColor, effectAmount, effectSpeed, effectScale}) s.showIf(() -> !effect.is("None"));
            frostTintS.showIf(() -> frostS.value > 0);
        }

        @Override
        public String suffix() {
            return preset.get();
        }

        @Override
        public void tick() {
            float op = opacity.f() / 100f, ta = tintAmount.f() / 100f, gs = glowSize.f(), gst = glowStrength.f() / 100f, sat = saturation.f() / 100f;
            int tc = tint.is("Black") ? 0xFF000000 : WHITE, gc = glowColor.is("Black") ? 0xFF000000 : WHITE;
            boolean ct = tint.is("Chroma"), cg = glowColor.is("Chroma"), g = glow.on();
            boolean hide = false, rim = rimLight.on(), sc = scan.on(), pu = pulseS.on();
            if (tint.is("Off")) ta = 0;
            switch (preset.get()) {
                case "Ghost" -> {
                    op = 0.35f;
                    ta = 0.4f;
                    tc = WHITE;
                    sat = 0.2f;
                    g = true;
                    gc = WHITE;
                    gs = 3;
                    gst = 0.45f;
                    ct = cg = false;
                }
                case "Neon" -> {
                    op = 0.9f;
                    ta = 0.25f;
                    ct = true;
                    g = true;
                    cg = true;
                    gs = 6;
                    gst = 1.3f;
                    sat = 1.3f;
                }
                case "Outline" -> {
                    hide = true;
                    g = true;
                    gc = WHITE;
                    cg = false;
                    gs = 2.5f;
                    gst = 1.5f;
                }
                case "Chroma" -> {
                    op = 1;
                    ta = 0.65f;
                    ct = true;
                    g = false;
                    sat = 1.2f;
                }
                case "Frost" -> {
                    op = 0.55f;
                    ta = 0.55f;
                    tc = 0xFFF3F5F8;
                    ct = false;
                    sat = 0.4f;
                    g = true;
                    gc = WHITE;
                    cg = false;
                    gs = 5;
                    gst = 0.7f;
                }
                case "Shadow" -> {
                    op = 0.85f;
                    ta = 0.85f;
                    tc = 0xFF000000;
                    ct = false;
                    g = true;
                    gc = WHITE;
                    cg = false;
                    gs = 2;
                    gst = 0.9f;
                }
                case "Hologram" -> {
                    op = 0.6f;
                    ta = 0.7f;
                    tc = 0xFFCFF3FF;
                    ct = false;
                    sat = 0.3f;
                    g = true;
                    gc = 0xFFCFF3FF;
                    cg = false;
                    gs = 3;
                    gst = 0.8f;
                    rim = true;
                    sc = true;
                }
                case "Pulse" -> {
                    op = 0.85f;
                    ta = 0.2f;
                    ct = false;
                    tc = WHITE;
                    g = true;
                    gc = WHITE;
                    cg = false;
                    gs = 7;
                    gst = 1.3f;
                    pu = true;
                }
                case "Rim" -> {
                    op = 0.95f;
                    ta = 0.8f;
                    tc = 0xFF000000;
                    ct = false;
                    rim = true;
                    g = false;
                    gc = WHITE;
                    cg = false;
                    gs = 3;
                }
            }
            if (!preset.is("Custom") && !preset.is("Hologram") && !preset.is("Rim")) rim = false;
            if (!preset.is("Custom") && !preset.is("Hologram")) sc = false;
            if (!preset.is("Custom") && !preset.is("Pulse")) pu = false;
            tenacity.gfx.HandFx.rim = rim;
            tenacity.gfx.HandFx.itemGlow = preset.is("Custom") && glowColor.is("Item");
            tenacity.gfx.HandFx.ringWidth = outline.i();
            tenacity.gfx.HandFx.shimmer = shimmerS.on();
            tenacity.gfx.HandFx.scanlines = sc;
            tenacity.gfx.HandFx.pulse = pu;
            tenacity.gfx.HandFx.opacity = op;
            tenacity.gfx.HandFx.tintAmount = ta;
            tenacity.gfx.HandFx.tint = tc;
            tenacity.gfx.HandFx.chromaTint = ct;
            tenacity.gfx.HandFx.glow = g;
            tenacity.gfx.HandFx.glowColor = gc;
            tenacity.gfx.HandFx.chromaGlow = cg;
            tenacity.gfx.HandFx.glowSize = gs;
            tenacity.gfx.HandFx.glowStrength = gst;
            tenacity.gfx.HandFx.saturation = sat;
            tenacity.gfx.HandFx.hideHand = hide;
            tenacity.gfx.HandFx.fx = java.util.List.of("None", "Caustic", "Plasma", "Lightning", "Aurora", "Galaxy", "Chrome", "Holo Foil").indexOf(effect.get());
            tenacity.gfx.HandFx.fxChroma = effectColor.is("Chroma");
            tenacity.gfx.HandFx.fxColor = switch (effectColor.get()) {
                case "White" -> 0xFFFFFFFF;
                case "Ice" -> 0xFF8FD8FF;
                case "Rose" -> 0xFFFF6FA8;
                case "Gold" -> 0xFFFFC24A;
                default -> 0xFF9A5CFF;
            };
            tenacity.gfx.HandFx.fxAmount = effectAmount.f() / 100f;
            tenacity.gfx.HandFx.fxSpeed = effectSpeed.f();
            tenacity.gfx.HandFx.fxScale = effectScale.f();
            tenacity.gfx.HandFx.aberration = aberr.f() / 100f;
            tenacity.gfx.HandFx.breathe = breatheS.f() / 100f * 0.6f;
            tenacity.gfx.HandFx.sweep = sweepS.f() / 100f;
            float fr = frostS.f() / 100f;
            if (preset.is("Frost")) fr = Math.max(fr, 0.85f);
            if (preset.is("Glass")) fr = Math.max(fr, 0.25f);
            tenacity.gfx.HandFx.frost = fr;
            tenacity.gfx.HandFx.refraction = preset.is("Glass") ? Math.max(0.8f, refrS.f() / 100f) : refrS.f() / 100f;
            tenacity.gfx.HandFx.frostTint = switch (frostTintS.get()) {
                case "Ice" -> 0xFFB4DCFF;
                case "Clear" -> 0xFFFFFFFF;
                case "Smoke" -> 0xFF8A9099;
                case "Rose" -> 0xFFFFC6DC;
                case "Mint" -> 0xFFC4FFE4;
                default -> 0xFFF3F5F8;
            };
            tenacity.gfx.HandFx.fresnel = preset.is("Glass") || preset.is("Frost") ? Math.max(0.5f, fresnelS.f() / 100f) : fresnelS.f() / 100f;
            tenacity.gfx.HandFx.grade = java.util.List.of("None", "Thermal", "Invert", "Sepia", "Night Vision", "Duotone", "Mono").indexOf(gradeS.get());
            if (preset.is("Thermal")) tenacity.gfx.HandFx.grade = 1;
            if (preset.is("Night Vision")) tenacity.gfx.HandFx.grade = 4;
            tenacity.gfx.HandFx.pixelate = preset.is("Retro") ? 6 : pixS.f();
            tenacity.gfx.HandFx.posterize = preset.is("Retro") ? 5 : postS.f();
            tenacity.gfx.HandFx.glitch = preset.is("Glitch") ? 0.8f : glitchS.f() / 100f;
            tenacity.gfx.HandFx.dissolve = preset.is("Dissolve") ? 0.6f : dissolveS.f() / 100f;
            tenacity.gfx.HandFx.wobble = preset.is("Water") ? 0.7f : wobbleS.f() / 100f;
            tenacity.gfx.HandFx.enabled = true;
        }

        @Override
        protected void onDisable() {
            tenacity.gfx.HandFx.enabled = false;
        }
    }

    static final class ViewModel extends Module {
        private final Setting.Mode hand = mode("Edit Hand", "Right", "Right", "Left");
        private final Setting.Bool link = bool("Same For Both", false);
        private final Setting.Num[][] v = new Setting.Num[2][];
        private final Setting.Bool noEquip = bool("No Equip Animation", false);

        ViewModel() {
            super("View Model", "Size, position and rotation of each hand", Category.RENDER);
            String[] parts = {"Scale", "X", "Y", "Z", "Rotate X", "Rotate Y", "Rotate Z"};
            for (int i = 0; i < 2; i++) {
                final int which = i;
                v[i] = new Setting.Num[]{
                        num((i == 0 ? "Right " : "Left ") + parts[0], 1, 0.3, 2, 0.05),
                        num((i == 0 ? "Right " : "Left ") + parts[1], 0, -1, 1, 0.02),
                        num((i == 0 ? "Right " : "Left ") + parts[2], 0, -1, 1, 0.02),
                        num((i == 0 ? "Right " : "Left ") + parts[3], 0, -1, 1, 0.02),
                        num((i == 0 ? "Right " : "Left ") + parts[4], 0, -180, 180, 5),
                        num((i == 0 ? "Right " : "Left ") + parts[5], 0, -180, 180, 5),
                        num((i == 0 ? "Right " : "Left ") + parts[6], 0, -180, 180, 5)
                };
                for (Setting.Num n : v[i])
                    n.showIf(() -> hand.is(which == 0 ? "Right" : "Left") && (!link.on() || which != 1));
            }
        }

        @Override
        public void tick() {
            tenacity.gfx.ViewModelFx.viewModel = true;
            for (int i = 0; i < 2; i++) {
                Setting.Num[] set = v[link.on() ? 0 : i];
                tenacity.gfx.ViewModelFx.scale[i] = set[0].f();
                tenacity.gfx.ViewModelFx.x[i] = set[1].f();
                tenacity.gfx.ViewModelFx.y[i] = set[2].f();
                tenacity.gfx.ViewModelFx.z[i] = set[3].f();
                tenacity.gfx.ViewModelFx.rotX[i] = set[4].f();
                tenacity.gfx.ViewModelFx.rotY[i] = set[5].f();
                tenacity.gfx.ViewModelFx.rotZ[i] = set[6].f();
            }
            tenacity.gfx.ViewModelFx.noEquip = noEquip.on();
        }

        @Override
        protected void onDisable() {
            tenacity.gfx.ViewModelFx.viewModel = false;
            tenacity.gfx.ViewModelFx.noEquip = false;
        }
    }

    static final class SwingAnimations extends Module {
        private final Setting.Mode mode = mode("Animation", "Smooth", "Smooth", "Exhibition", "Sigma", "Stab", "Push", "Punch", "Chop", "Slash",
                "Smash", "Swing Down", "Spin", "Twirl", "Helicopter", "Rotate", "Flick", "Flux", "Swank", "Wave", "Glide", "Dash", "Jello",
                "Bounce", "Zoom", "Tap", "Low", "Classic", "Vanilla", "Custom");
        private final Setting.Num strength = num("Strength", 1, 0.2, 2, 0.05);
        private final Setting.Num speed = num("Speed", 1, 0.5, 3, 0.05);
        private final Setting.Mode curve = mode("Curve", "Smooth", "Smooth", "Sine", "Linear", "Pulse", "Snap").showIf(() -> mode.is("Custom"));
        private final Setting.Num mx = num("Move X", 0, -0.6, 0.6, 0.02).showIf(() -> mode.is("Custom"));
        private final Setting.Num my = num("Move Y", 0, -0.6, 0.6, 0.02).showIf(() -> mode.is("Custom"));
        private final Setting.Num mz = num("Move Z", -0.2, -0.6, 0.6, 0.02).showIf(() -> mode.is("Custom"));
        private final Setting.Num rx = num("Rotate X", -60, -360, 360, 5).showIf(() -> mode.is("Custom"));
        private final Setting.Num ry = num("Rotate Y", 0, -360, 360, 5).showIf(() -> mode.is("Custom"));
        private final Setting.Num rz = num("Rotate Z", -20, -360, 360, 5).showIf(() -> mode.is("Custom"));
        private final Setting.Bool pivot = bool("Pivot On Item", true).showIf(() -> mode.is("Custom"));

        SwingAnimations() {
            super("Swing Animations", "Custom first person swing animations", Category.RENDER);
        }

        @Override
        public String suffix() {
            return mode.get();
        }

        @Override
        public void tick() {
            tenacity.gfx.ViewModelFx.swing = true;
            tenacity.gfx.ViewModelFx.mode = mode.get();
            tenacity.gfx.ViewModelFx.strength = strength.f();
            tenacity.gfx.ViewModelFx.swingSpeed = speed.f();
            tenacity.gfx.ViewModelFx.cCurve = curve.get();
            tenacity.gfx.ViewModelFx.cMoveX = mx.f();
            tenacity.gfx.ViewModelFx.cMoveY = my.f();
            tenacity.gfx.ViewModelFx.cMoveZ = mz.f();
            tenacity.gfx.ViewModelFx.cRotX = rx.f();
            tenacity.gfx.ViewModelFx.cRotY = ry.f();
            tenacity.gfx.ViewModelFx.cRotZ = rz.f();
            tenacity.gfx.ViewModelFx.cPivot = pivot.on();
        }

        @Override
        protected void onDisable() {
            tenacity.gfx.ViewModelFx.swing = false;
            tenacity.gfx.ViewModelFx.swingSpeed = 1f;
        }
    }
}


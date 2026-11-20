package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import tenacity.modules.Knifes;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The CS2 knives of Cake Client in first person: the original meshes and skeletal clips (idle, deploy, inspect, slashes) with the player's own
 * Minecraft arms moved onto the knife's grip, and the support hand brought in for the clips that use it. When one of these knives is the
 * Weapons style and the main hand holds something the Weapons module replaces, {@link #render} draws all of it in place of the vanilla hand.
 * Playback, the arm retargeting and the camera framing follow Cake Client (MIT, Copyright (c) 2026 Cake Client contributors); the meshes,
 * textures and clips are Counter-Strike 2 exports (see assets/crypto/knives/CREDITS.txt).
 */
public final class CsKnives {

    public static final String[] IDS = {"karambit", "butterfly", "bayonet", "bowie", "classic", "m9", "flip", "gut", "huntsman", "falchion", "shadow",
            "navaja", "stiletto", "ursus", "talon", "skeleton", "nomad", "paracord", "survival", "kukri", "default_ct", "default_t"};
    public static final String[] LABELS = {"Karambit", "Butterfly", "Bayonet", "Bowie", "Classic", "M9 Bayonet", "Flip", "Gut", "Huntsman", "Falchion",
            "Shadow Daggers", "Navaja", "Stiletto", "Ursus", "Talon", "Skeleton", "Nomad", "Paracord", "Survival", "Kukri", "CT Knife", "T Knife"};

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private CsKnives() {
    }

    public static int indexOf(String label) {
        for (int i = 0; i < LABELS.length; i++) if (LABELS[i].equals(label)) return i;
        return -1;
    }

    /** A loaded knife: its asset, texture and the frame it is mounted in on the body (grip at the origin, tip along +y). */
    private static final class Loaded {
        final int index;
        final CsKnifeAsset asset;
        final Identifier texture;
        final Quaternionf mountRotation = new Quaternionf();
        final Vector3f grip = new Vector3f();
        float reach = 0.3f;

        Loaded(int index, CsKnifeAsset asset) {
            this.index = index;
            this.asset = asset;
            this.texture = Identifier.of("tenacity", "knives/" + IDS[index] + ".png");
            // the idle pose: the grip is where the main hand is, the tip is the point of the knife farthest from it
            Matrix4f hand = asset.clips[0].handMatrix(0);
            hand.getTranslation(grip);
            Vector3f tip = new Vector3f(), best = new Vector3f(), p = new Vector3f();
            float far = -1f;
            for (int i = 0; i < asset.parts.size(); i++) {
                Matrix4f pose = asset.clips[0].matrix(i, 0);
                float[] d = asset.parts.get(i).data();
                for (int v = 0; v < asset.parts.get(i).count(); v += 3) {
                    pose.transformPosition(d[v * 8], d[v * 8 + 1], d[v * 8 + 2], p);
                    float dist = p.distanceSquared(grip);
                    if (dist > far) {
                        far = dist;
                        best.set(p);
                    }
                }
            }
            tip.set(best).sub(grip);
            reach = Math.max(0.1f, tip.length());
            mountRotation.rotationTo(tip.normalize(), new Vector3f(0f, 1f, 0f));
        }
    }

    private static final Map<Integer, Loaded> cache = new LinkedHashMap<>();
    private static boolean failed;

    private static Loaded load(int index) {
        if (index < 0 || index >= IDS.length) return null;
        Loaded l = cache.get(index);
        if (l != null) return l;
        try (InputStream in = CsKnives.class.getResourceAsStream("/assets/tenacity/knives/" + IDS[index] + ".ckm")) {
            byte[] bytes;
            if (in != null) {
                bytes = in.readAllBytes();
            } else {
                var res = mc.getResourceManager().getResource(Identifier.of("tenacity", "knives/" + IDS[index] + ".ckm"));
                if (res.isEmpty()) throw new java.io.FileNotFoundException(IDS[index]);
                try (InputStream stream = res.get().getInputStream()) {
                    bytes = stream.readAllBytes();
                }
            }
            l = new Loaded(index, CsKnifeAsset.parse(bytes));
            cache.put(index, l);
            while (cache.size() > 3) cache.remove(cache.keySet().iterator().next());
            return l;
        } catch (Exception e) {
            if (!failed) tenacity.Tenacity.LOG.error("CS knife {} could not be loaded", IDS[index], e);
            failed = true;
            return null;
        }
    }

    /** Length of the knife from the grip to the tip in blocks (for the body mounts), 0.3 until it is loaded. */
    public static float reach(String label) {
        Loaded l = load(indexOf(label));
        return l == null ? 0.3f : l.reach;
    }

    // ------------------------------------------------------------------ playback (Cake's KnifePlayback)

    private static final class Playback {
        int mode, next = 3, queued = -1;
        long started, serial;

        void play(int action, long now) {
            mode = action;
            started = now;
            queued = -1;
            serial++;
        }

        double elapsed(long now) {
            return Math.max(0, (now - started) / 1e9);
        }

        double progress(long now, double[] d) {
            return mode == 0 ? 0 : Math.clamp(elapsed(now) / Math.max(.01, d[mode]), 0, 1);
        }

        void attack(boolean heavy, long now, double[] d) {
            int action = heavy ? 5 : next;
            if (!heavy) next = next == 3 ? 4 : 3;
            play(action, now);
        }

        void advance(long now, double[] d) {
            if (mode != 0 && progress(now, d) >= 1) {
                if (queued >= 0) {
                    int action = queued;
                    play(action, now);
                } else {
                    mode = 0;
                    started = now;
                    serial++;
                }
            }
        }

        double seconds(long now, double[] d, double speed) {
            return mode == 0 ? (elapsed(now) % d[0]) * speed : Math.min(d[mode], elapsed(now)) * speed;
        }
    }

    private static Loaded current;
    private static Playback playback = new Playback();
    private static Matrix4f[] pose, previousPose;
    private static Matrix4f arm = new Matrix4f(), previousArm = new Matrix4f(), leftArm = new Matrix4f(), previousLeft = new Matrix4f();
    private static long transition, lastSerial = -1;
    private static boolean wasEligible, supportHand, lastSwinging;
    private static int lastSwingTicks, inspectVariant, drawVariant;

    private static double speed() {
        return 1.0;
    }

    /** The Karambit and the Butterfly have long slash clips that made the hit feel late: they play faster. */
    private static double speed(int clip) {
        return clip >= 3 && clip <= 5 && current != null && current.index <= 1 ? 1.5 : 1.0;
    }

    private static double[] durations(CsKnifeAsset a) {
        double[] d = new double[a.clips.length];
        for (int i = 0; i < d.length; i++) d[i] = a.clips[i].duration / speed(i);
        return d;
    }

    /** The Weapons module has a CS knife selected and replaces what the main hand holds. */
    public static boolean eligible(ItemStack stack) {
        Knifes k = Knifes.I;
        if (k == null || !k.enabled() || mc.player == null || mc.world == null) return false;
        if (!k.replaces(stack)) return false;
        return !mc.player.isSpectator() && !mc.player.isSleeping() && mc.options.getPerspective().isFirstPerson();
    }

    private static float ambient(int light) {
        float sky = net.minecraft.client.render.LightmapTextureManager.getSkyLightCoordinates(light) / 15f;
        float block = net.minecraft.client.render.LightmapTextureManager.getBlockLightCoordinates(light) / 15f;
        float day = mc.world == null ? 1f : 1f - mc.world.getAmbientDarkness() / 15f;
        return 0.34f + 0.66f * net.minecraft.util.math.MathHelper.clamp(Math.max(sky * day, block), 0f, 1f);
    }

    /** Plays the knife's own inspect clip (the three inspects take turns). False when no CS knife is in the hand. */
    public static boolean inspect() {
        if (mc.player == null || !eligible(mc.player.getMainHandStack())) return false;
        Loaded l = load(indexOf(Knifes.I.knife.get()));
        if (l == null) return false;
        if (current != l) reset(l);
        int[] variants = {1, l.asset.find("inspect2"), l.asset.find("inspect3")};
        int count = 1;
        while (count < variants.length && variants[count] >= 0) count++;
        playback.play(variants[Math.floorMod(inspectVariant++, count)], System.nanoTime());
        return true;
    }

    private static void reset(Loaded l) {
        current = l;
        playback = new Playback();
        pose = previousPose = null;
        arm = new Matrix4f();
        previousArm = new Matrix4f();
        leftArm = new Matrix4f();
        previousLeft = new Matrix4f();
        lastSerial = -1;
        wasEligible = false;
    }

    /** Cake's camera framing: where the knife sits in the view, which depends on the model. */
    private static Matrix4f base(Arm mainArm) {
        float side = mainArm == Arm.LEFT ? -1f : 1f;
        Knifes w = Knifes.I;
        float scale = 0.85f + 0.3f * (w == null ? 1f : w.size.f() - 0.4f) / 1.2f;
        int i = current.index;
        float y = i == 3 ? -0.055f : i == 1 ? -0.085f : -0.045f, z = i == 3 ? -0.16f : i == 0 ? -0.10f : -0.13f;
        float x = w == null ? 0f : w.x.f() * 0.3f;
        float dy = w == null ? 0f : w.y.f() * 0.3f;
        float dz = w == null ? 0f : -w.z.f() * 0.3f;
        return new Matrix4f().translation(0.015f * side + x * side, y + dy, z + dz).scale(side * scale, scale, scale);
    }

    private static Matrix4f blend(Matrix4f a, Matrix4f b, float p) {
        Vector3f pos = a.getTranslation(new Vector3f()).lerp(b.getTranslation(new Vector3f()), p), scale = a.getScale(new Vector3f()).lerp(b.getScale(new Vector3f()), p);
        Quaternionf q = a.getUnnormalizedRotation(new Quaternionf()).slerp(b.getUnnormalizedRotation(new Quaternionf()), p);
        return new Matrix4f().translationRotateScale(pos, q, scale);
    }

    /** Steps the playback and the poses to this frame; returns the matrix of the main hand. */
    private static Matrix4f prepare(Arm mainArm) {
        CsKnifeAsset asset = current.asset;
        long now = System.nanoTime();
        if (!wasEligible) {
            wasEligible = true;
            int other = asset.find("deploy2");
            playback.play(other >= 0 && (drawVariant++ & 1) == 1 ? other : 2, now);
        }
        double[] d = durations(asset);
        playback.advance(now, d);
        int mode = playback.mode;
        if (lastSerial != playback.serial) {
            previousPose = pose;
            previousArm = new Matrix4f(arm);
            previousLeft = new Matrix4f(leftArm);
            transition = now;
            lastSerial = playback.serial;
        }
        CsKnifeAsset.Clip clip = asset.clips[mode];
        double t = playback.seconds(now, d, speed(mode));
        float mix = (float) Math.clamp((now - transition) / 80_000_000., 0, 1);
        mix = mix * mix * (3 - 2 * mix);
        supportHand = clip.hasLeftArm() && (clip.name.startsWith("inspect") || clip.name.startsWith("deploy"));
        leftArm = clip.leftArmMatrix(t);
        if (previousPose != null && mix < 1) leftArm = blend(previousLeft, leftArm, mix);
        arm = clip.handMatrix(t);
        if (previousPose != null && mix < 1) arm = blend(previousArm, arm, mix);
        pose = new Matrix4f[asset.parts.size()];
        for (int i = 0; i < pose.length; i++) {
            pose[i] = clip.matrix(i, t);
            if (previousPose != null && mix < 1) pose[i] = blend(previousPose[i], pose[i], mix);
        }
        return base(mainArm).mul(arm);
    }

    // ------------------------------------------------------------------ the arms

    /** The fist anchor of a Minecraft arm after the vanilla first person renderer's roll of 0.1 radians (Cake's NativeHandGrip). */
    private static Vector3f anchor(float pivotX, float pivotY, float pivotZ, float minX, float maxX, float maxY, float minZ, float maxZ, boolean left) {
        float x = (minX + maxX) * .5f, y = maxY - 1, z = (minZ + maxZ) * .5f, a = left ? -.1f : .1f, c = (float) Math.cos(a), s = (float) Math.sin(a);
        return new Vector3f((pivotX + c * x - s * y) / 16, (pivotY + s * x + c * y) / 16, (pivotZ + z) / 16);
    }

    /** Scales a Minecraft arm to the fist of the knife and puts its grip on the matrix's origin. */

    public static float[] TUNE = {0, 0, 0};
    static { String t = System.getenv("TENACITY_KTUNE"); if (t != null) { String[] p = t.split(","); for (int i = 0; i < 3; i++) TUNE[i] = Float.parseFloat(p[i]); } }

    private static Matrix4f nativeArm(AbstractClientPlayerEntity player, Matrix4f matrix, boolean left) {
        PlayerEntityRenderer renderer = mc.getEntityRenderDispatcher().getPlayerRenderer(player);
        net.minecraft.client.render.entity.model.PlayerEntityModel model = (net.minecraft.client.render.entity.model.PlayerEntityModel) renderer.getModel();
        var part = left ? model.leftArm : model.rightArm;
        var t = part.getDefaultTransform();
        boolean slim = String.valueOf(player.getSkin().model()).toLowerCase().contains("slim");
        float minX = left ? -1f : slim ? -2f : -3f, maxX = left ? (slim ? 2f : 3f) : 1f;
        Vector3f g = anchor(t.x(), t.y(), t.z(), minX, maxX, 10f, -2f, 2f, left);
        return new Matrix4f(matrix).translate(TUNE[0] + (!left && current != null && current.index == 1 ? -0.05f : 0f), TUNE[1], TUNE[2] + (0f)).scale(.32f, .58f, .32f).translate(-g.x, -g.y, -g.z);
    }

    private static void drawArm(AbstractClientPlayerEntity player, MatrixStack m, OrderedRenderCommandQueue queue, int light, Matrix4f pose, boolean left) {
        PlayerEntityRenderer renderer = mc.getEntityRenderDispatcher().getPlayerRenderer(player);
        Identifier skin = player.getSkin().body().texturePath();
        m.push();
        m.multiplyPositionMatrix(nativeArm(player, pose, left));
        if (left) renderer.renderLeftArm(m, queue, light, skin, player.isModelPartVisible(PlayerModelPart.LEFT_SLEEVE));
        else renderer.renderRightArm(m, queue, light, skin, player.isModelPartVisible(PlayerModelPart.RIGHT_SLEEVE));
        m.pop();
    }

    // ------------------------------------------------------------------ drawing

    private static final Vector3f LIGHT = new Vector3f(-0.35f, 0.62f, 0.70f).normalize();

    /** Draws one part's triangles into a textured consumer: the matrix takes part space to the layer's space, the colour carries the baked light. */
    private static void emit(net.minecraft.client.render.VertexConsumer vc, Matrix4f m, Matrix4f lightFrame, float[] d, int count, float bright) {
        Vector3f n = new Vector3f();
        for (int v = 0; v < count; v += 3) {
            for (int k = 0; k < 4; k++) {
                int i = (v + Math.min(k, 2)) * 8;
                lightFrame.transformDirection(d[i + 5], d[i + 6], d[i + 7], n);
                float diff = Math.max(0f, n.dot(LIGHT));
                int c = Math.min(255, (int) (255f * (0.50f + 0.62f * diff) * bright));
                vc.vertex(m, d[i], d[i + 1], d[i + 2]).texture(d[i + 3], d[i + 4]).color(0xFF000000 | (c << 16) | (c << 8) | c);
            }
        }
    }

    private static void submit(MatrixStack stack, OrderedRenderCommandQueue queue, Loaded l, Matrix4f[] poses, Matrix4f base, float bright) {
        RenderLayer layer = KnifePipeline.layer(l.texture);
        boolean offhandBusy = mc.player != null && !mc.player.getOffHandStack().isEmpty();
        for (int part = 0; part < poses.length; part++) {
            if (l.index == 10 && part == 0 && offhandBusy) continue;
            final Matrix4f frame = new Matrix4f(base).mul(poses[part]);
            final CsKnifeAsset.Part p = l.asset.parts.get(part);
            queue.submitCustom(stack, layer, (entry, vc) -> emit(vc, new Matrix4f(entry.getPositionMatrix()).mul(frame), frame, p.data(), p.count(), bright));
        }
    }

    /**
     * Draws the knife in place of the main hand in first person (and cancels the vanilla hand when it returns true): the knife parts, the arm
     * that holds it and, in the clips that use it, the other arm.
     */
    public static boolean render(AbstractClientPlayerEntity player, Hand hand, ItemStack item, MatrixStack matrices, OrderedRenderCommandQueue queue, int light) {
        if (hand != Hand.MAIN_HAND || !eligible(item)) {
            if (hand == Hand.MAIN_HAND) wasEligible = false;
            return false;
        }
        Knifes w = Knifes.I;
        Loaded l = load(indexOf(w.knife.get()));
        if (l == null) return false;
        if (current != l) reset(l);

        // a swing starts a slash: it counts as a new swing when the swing counter went back
        boolean swinging = player.handSwinging;
        if (swinging && (!lastSwinging || player.handSwingTicks < lastSwingTicks)) playback.attack(false, System.nanoTime(), durations(l.asset));
        lastSwinging = swinging;
        lastSwingTicks = player.handSwingTicks;

        final Arm mainArm = player.getMainArm();
        final Matrix4f armPose = prepare(mainArm);
        final float bright = 0.62f + 0.38f * ambient(light);
        matrices.push();
        try {
            ViewModelFx.applyViewModel(matrices, mainArm == Arm.RIGHT ? 1 : -1);
            if (w.arms.on()) {
                drawArm(player, matrices, queue, light, armPose, mainArm == Arm.LEFT);
                // the other arm: in the clips that use it (draw, inspect), or all the time when asked (it rests where the clips put a free hand)
                boolean showLeft = switch (w.leftHand.get()) {
                    case "Always" -> true;
                    case "Never" -> false;
                    default -> supportHand;
                };
                if (showLeft && player.getOffHandStack().isEmpty()) {
                    drawArm(player, matrices, queue, light, base(mainArm).mul(leftArm), mainArm != Arm.LEFT);
                }
            }
            submit(matrices, queue, l, pose, base(mainArm), bright);
        } finally {
            matrices.pop();
        }
        return true;
    }

    /** The knife at rest on the body: {@code placed} takes the weapon frame (grip at the origin, tip along +y, one block per unit) to the buffer's space. */
    public static void submitMounted(MatrixStack stack, OrderedRenderCommandQueue queue, String label, Matrix4f placed, float bright) {
        Loaded l = load(indexOf(label));
        if (l == null) return;
        RenderLayer layer = KnifePipeline.layer(l.texture);
        for (int part = 0; part < l.asset.parts.size(); part++) {
            final Matrix4f frame = new Matrix4f().rotate(l.mountRotation).translate(-l.grip.x, -l.grip.y, -l.grip.z).mul(l.asset.clips[0].matrix(part, 0));
            final CsKnifeAsset.Part p = l.asset.parts.get(part);
            queue.submitCustom(stack, layer, (entry, vc) -> emit(vc, new Matrix4f(placed).mul(frame), frame, p.data(), p.count(), bright));
        }
    }
}

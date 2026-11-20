package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Shared state for Free Cam and Free Look, written by the modules and read by the camera / look / hand mixins.
 * Ported from the reference client: instant camera movement, tick interpolated rotation and a real freeze.
 */
public final class FreecamFx {
    public static boolean freecam, freelook;
    public static boolean showPlayer = true, freeze, ogFreecam;
    public static float speed = 2f;
    /** Free look orbit distance: target and eased value. */
    public static float camDistance = 4f, camDist = 4f;
    public static boolean collide;

    /** Free cam camera position, per tick plus the previous tick for interpolation. */
    public static double x, y, z, px, py, pz;
    /** Free cam rotation, per tick plus the previous tick for interpolation. */
    public static float yaw, pitch, prevYaw, prevPitch;
    /** Crypto's Smoothing: the camera accelerates and decelerates instead of jumping straight to full speed. */
    private static Vec3d velocity = Vec3d.ZERO;

    /** Free look orbit angle around the player. */
    public static float lookYaw, lookPitch;

    private static Perspective saved = null;
    private static boolean savedCulling = true, lastOg;

    private FreecamFx() {
    }

    /** Rotation for the current frame, eased between the last two ticks so the camera does not step. */
    public static float yawAt(float tickProgress) {
        return MathHelper.lerpAngleDegrees(tickProgress, prevYaw, yaw);
    }

    public static float pitchAt(float tickProgress) {
        return MathHelper.lerp(tickProgress, prevPitch, pitch);
    }

    public static void rotateFree(double dx, double dy) {
        prevYaw = yaw;
        prevPitch = pitch;
        yaw = MathHelper.wrapDegrees(yaw + (float) dx * 0.15f);
        pitch = MathHelper.clamp(pitch + (float) dy * 0.15f, -90f, 90f);
    }

    public static void rotateLook(double dx, double dy) {
        lookYaw = MathHelper.wrapDegrees(lookYaw + (float) dx * 0.15f);
        lookPitch = MathHelper.clamp(lookPitch + (float) dy * 0.15f, -90f, 90f);
    }

    private static void applyCulling(MinecraftClient client) {
        client.chunkCullingEnabled = ogFreecam && savedCulling;
        if (client.worldRenderer != null) client.worldRenderer.scheduleTerrainUpdate();
        lastOg = ogFreecam;
    }

    public static void start(boolean look) {
        var client = MinecraftClient.getInstance();
        var p = client.player;
        if (look) {
            freelook = true;
            if (p != null) {
                lookYaw = p.getYaw();
                lookPitch = p.getPitch();
            }
            camDist = 0.35f; // start at the head and glide out
            closing = false;
            lastNs = 0;
        } else {
            freecam = true;
            var cam = client.gameRenderer.getCamera();
            if (cam != null) {
                x = px = cam.getCameraPos().x;
                y = py = cam.getCameraPos().y;
                z = pz = cam.getCameraPos().z;
            } else if (p != null) {
                x = px = p.getX();
                y = py = p.getEyeY();
                z = pz = p.getZ();
            }
            if (p != null) {
                yaw = prevYaw = p.getYaw();
                pitch = prevPitch = p.getPitch();
            }
        }
        saved = client.options.getPerspective();
        savedCulling = client.chunkCullingEnabled;
        // the detached body is only visible in third person, exactly like the reference
        if ((look || showPlayer) && saved == Perspective.FIRST_PERSON) client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        applyCulling(client);
    }

    /** Freelook smoothing (0 = instant, 100 = slowest) and the exit animation state. */
    public static float smoothness = 60f;
    public static boolean closing;
    private static long lastNs;

    /** Smoothly zooms the freelook camera to its distance (log-space ease like Zoom); when closing, back to the head. */
    public static float frameDist() {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        float goal = closing ? 0.35f : camDistance;
        float rate = 4f + (100f - smoothness) * 0.16f;
        double lc = Math.log(Math.max(0.05f, camDist)), lt = Math.log(Math.max(0.05f, goal));
        lc += (lt - lc) * (1 - Math.exp(-rate * dt));
        camDist = (float) Math.exp(lc);
        if (closing && camDist < 0.45f) {
            closing = false;
            stop();
        }
        return camDist;
    }

    public static void stop() {
        freecam = false;
        freelook = false;
        var client = MinecraftClient.getInstance();
        client.options.setPerspective(saved == null ? Perspective.FIRST_PERSON : saved);
        saved = null;
        ogFreecam = false;
        lastOg = false;
        client.chunkCullingEnabled = savedCulling;
        if (client.worldRenderer != null) client.worldRenderer.scheduleTerrainUpdate();
    }

    /** One movement tick for the free cam: read keys and advance the camera by a fixed step (no acceleration). */
    public static void tickCam() {
        var client = MinecraftClient.getInstance();
        var p = client.player;
        if (p == null) return;
        // the body is held still by FreecamInputMixin at input time (module ticks run after the player has moved)
        if (freeze) p.setVelocity(0, 0, 0);

        if (ogFreecam != lastOg) applyCulling(client);

        var o = client.options;
        float f = (o.forwardKey.isPressed() ? 1 : 0) - (o.backKey.isPressed() ? 1 : 0);
        float s = (o.rightKey.isPressed() ? 1 : 0) - (o.leftKey.isPressed() ? 1 : 0);
        float u = (o.jumpKey.isPressed() ? 1 : 0) - (o.sneakKey.isPressed() ? 1 : 0);
        double step = speed * 0.2; // blocks per tick
        px = x;
        py = y;
        pz = z;
        // Fly on the camera's real basis vectors instead of hand written trig: forward is always exactly where the
        // camera looks, right is up x forward. No yaw convention left to get backwards.
        Vec3d fwd = Vec3d.fromPolar(pitch, yaw);
        Vec3d right = new Vec3d(0, 1, 0).crossProduct(fwd);
        // Crypto applies the movement straight to the camera position (its Smoothing setting is declared but never
        // read), so there is no acceleration curve to match here
        Vec3d move = fwd.multiply(f).add(right.multiply(s)).multiply(step);
        x += move.x;
        y += move.y + u * step;
        z += move.z;
    }
}

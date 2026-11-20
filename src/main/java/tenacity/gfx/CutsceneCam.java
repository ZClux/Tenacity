package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Kill cutscene camera: a detached camera that glides out of your eyes into one of several cinematic shots around the
 * death spot and glides back. Read by CameraUpdateMixin (pose) and ZoomMixin (fov, for the dolly zoom); the player
 * keeps full control of their body the whole time (input is never blocked).
 */
public final class CutsceneCam {
    public static final String[] SHOTS = {"Orbit", "Dolly Zoom", "Slow Push-In", "Crane Up", "Spiral Out", "Side Pan", "Low Angle Hero",
            "Matrix 360", "Drop Down", "Whip Pan"};

    public static boolean active;
    /** Orbit centre (the death spot, feet) and the victim's height. */
    private static Vec3d spot = Vec3d.ZERO;
    private static double height = 1.8;
    private static float startAngle, side = 1;
    private static String shot = "Orbit";
    private static long startNs;
    private static float duration = 2.5f;
    /** Seconds into the cutscene when the exit started (glide back), or -1. */
    private static float exitAt = -1;
    private static final float GLIDE_IN = 0.55f, GLIDE_OUT = 0.6f;
    private static Perspective saved;
    private static boolean switched;
    /** Smoothed collision pull-in (fraction of the wanted camera distance). */
    private static double clip = 1;
    /** Fov tangent multiplier for the dolly zoom (1 = untouched). */
    private static double fovTan = 1;
    /** The camera pose last frame (x y z yaw pitch). */
    public static double cx, cy, cz;
    public static float cyaw, cpitch;

    private CutsceneCam() {
    }

    public static void start(Vec3d deathSpot, double victimHeight, float seconds) {
        start(deathSpot, victimHeight, seconds, "Orbit");
    }

    public static void start(Vec3d deathSpot, double victimHeight, float seconds, String mode) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        spot = deathSpot;
        height = Math.max(0.6, victimHeight);
        duration = Math.max(1f, seconds);
        Vec3d from = mc.player.getEyePos().subtract(deathSpot);
        startAngle = (float) Math.atan2(from.z, from.x);
        side = mc.player.getRandom().nextBoolean() ? 1 : -1;
        shot = mode == null ? "Orbit" : mode;
        if (shot.equals("Random")) shot = SHOTS[mc.player.getRandom().nextInt(SHOTS.length)];
        startNs = System.nanoTime();
        exitAt = -1;
        clip = 1;
        fovTan = 1;
        if (!active) {
            saved = null;
            switched = false;
        }
        active = true;
    }

    public static String shot() {
        return shot;
    }

    public static float time() {
        return (System.nanoTime() - startNs) / 1e9f;
    }

    /** Begin the glide back early (damage / key press). */
    public static void cancel() {
        if (!active || exiting()) return;
        exitAt = Math.max(time(), 0f);
    }

    public static boolean exiting() {
        return exitAt >= 0 || time() > duration - GLIDE_OUT;
    }

    private static float easeInOut(float t) {
        t = MathHelper.clamp(t, 0, 1);
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    private static float sine(float t) {
        t = MathHelper.clamp(t, 0, 1);
        return (float) (0.5 - 0.5 * Math.cos(Math.PI * t));
    }

    private static float outCubic(float t) {
        t = MathHelper.clamp(t, 0, 1);
        float u = 1 - t;
        return 1 - u * u * u;
    }

    private static float outQuint(float t) {
        t = MathHelper.clamp(t, 0, 1);
        float u = 1 - t;
        return 1 - u * u * u * u * u;
    }

    private static float backOut(float t, float s) {
        t = MathHelper.clamp(t, 0, 1) - 1;
        return 1 + (s + 1) * t * t * t + s * t * t;
    }

    /** "Slow motion" progress: fast at the start, lingering through the middle, drifting out. */
    private static float slowMo(float k) {
        double a = Math.atan((-0.35) * 5), b = Math.atan((1 - 0.35) * 5);
        return (float) ((Math.atan((k - 0.35) * 5) - a) / (b - a));
    }

    /** 0 = your normal camera, 1 = fully on the cinematic camera. */
    public static float weight() {
        if (!active) return 0;
        float t = time();
        float in = easeInOut(t / GLIDE_IN);
        float outStart = exitAt >= 0 ? Math.min(exitAt, duration - GLIDE_OUT) : duration - GLIDE_OUT;
        float outLen = exitAt >= 0 ? Math.min(GLIDE_OUT, 0.45f) : GLIDE_OUT;
        float out = 1 - easeInOut((t - outStart) / outLen);
        if (exitAt >= 0 && t >= exitAt) {
            float w0 = easeInOut(exitAt / GLIDE_IN);
            return w0 * (1 - easeInOut((t - exitAt) / outLen));
        }
        return Math.min(in, out);
    }

    /** Overall cinematic progress for overlays (letterbox etc.): eased 0..1..0. */
    public static float overlay() {
        return weight();
    }

    public static boolean finished() {
        if (!active) return true;
        float t = time();
        return exitAt >= 0 ? t > exitAt + 0.5f : t > duration;
    }

    /** Called every frame by the module (render thread): perspective handling and end of the cutscene. */
    public static void frame() {
        var mc = MinecraftClient.getInstance();
        if (!active) return;
        if (finished() || mc.player == null) {
            stop();
            return;
        }
        float w = weight();
        if (w > 0.12f && !switched) {
            saved = mc.options.getPerspective();
            if (saved == Perspective.FIRST_PERSON) mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
            switched = true;
        } else if (w < 0.08f && switched && exiting()) {
            if (saved != null) mc.options.setPerspective(saved);
            switched = false;
        }
    }

    public static void stop() {
        var mc = MinecraftClient.getInstance();
        if (switched && saved != null) mc.options.setPerspective(saved);
        switched = false;
        saved = null;
        active = false;
        exitAt = -1;
        fovTan = 1;
    }

    /** Applied to the game fov (degrees) by ZoomMixin: the dolly zoom's counter zoom, blended by the cutscene weight. */
    public static float fov(float base) {
        if (!active || fovTan == 1) return base;
        double w = weight();
        double t = Math.tan(Math.toRadians(base) / 2) * (1 + (fovTan - 1) * w);
        return (float) Math.toDegrees(2 * Math.atan(t));
    }

    private static Vec3d ring(double ang, double r, double y) {
        return spot.add(Math.cos(ang) * r, y, Math.sin(ang) * r);
    }

    /** Computes the camera pose for this frame into cx..cpitch. */
    public static void pose(Entity focused, float tickProgress) {
        float t = time();
        float w = weight();
        float k = MathHelper.clamp(t / duration, 0, 1);
        double h = height;
        double a0 = startAngle;
        Vec3d look = spot.add(0, h * 0.55, 0);
        Vec3d cam;
        float yawOff = 0;
        fovTan = 1;
        switch (shot) {
            case "Dolly Zoom" -> {
                // vertigo: the camera pulls back while the lens zooms in, the subject stays the same size
                float e = sine(k);
                double r0 = 2.0, r = r0 + 3.8 * e;
                cam = ring(a0 + side * 0.25, r, h * 0.6);
                look = spot.add(0, h * 0.6, 0);
                fovTan = r0 / r;
            }
            case "Slow Push-In" -> {
                float e = sine(k);
                cam = ring(a0 + side * (0.35 - 0.2 * e), 4.6 - 2.5 * e, h * 0.45 + 0.15 * e);
                look = spot.add(0, h * (0.5 + 0.15 * e), 0);
            }
            case "Crane Up" -> {
                // starts low beside the body, rises and tips down to look onto the spot
                float e = easeInOut(k);
                cam = ring(a0 + side * 0.4 * e, 3.2 - 1.0 * e, 0.4 + 5.2 * e);
                look = spot.add(0, h * (0.5 - 0.35 * e), 0);
            }
            case "Spiral Out" -> {
                float e = sine(k);
                cam = ring(a0 + side * Math.toRadians(250) * e, 1.7 + 3.6 * e, h * 0.5 + 2.6 * e);
                look = spot.add(0, h * 0.5 - 0.2 * e, 0);
            }
            case "Side Pan" -> {
                // a dolly track perpendicular to the view: slides past while tracking the spot
                float e = sine(k);
                double perp = a0 + Math.PI / 2;
                double off = side * (-2.6 + 5.2 * e);
                Vec3d base = ring(a0, 3.4, h * 0.6);
                cam = base.add(Math.cos(perp) * off, 0, Math.sin(perp) * off);
            }
            case "Low Angle Hero" -> {
                float e = sine(k);
                cam = ring(a0 + side * (0.2 + 0.5 * e), 2.4 - 0.3 * e, 0.22);
                look = spot.add(0, h * 0.85, 0);
            }
            case "Matrix 360" -> {
                // a full revolution: snaps around fast, slows to a crawl in the middle, finishes the turn
                float p = slowMo(k);
                cam = ring(a0 + side * Math.PI * 2 * p, 2.7, h * 0.62);
                look = spot.add(0, h * 0.55, 0);
            }
            case "Drop Down" -> {
                // starts high above looking straight down, drops in and levels out
                float e = outQuint(k * 1.15f);
                cam = ring(a0 + side * 0.3, 0.35 + 2.75 * e, h * 0.6 + 7.5 * (1 - e));
                look = spot.add(0, h * 0.5, 0);
            }
            case "Whip Pan" -> {
                // the lens whips onto the subject with a slight overshoot, then a slow push holds on it
                float wp = backOut(k / 0.28f, 1.2f);
                yawOff = side * 105 * (1 - wp);
                float e = sine(k);
                cam = ring(a0 + side * 0.15, 3.4 - 0.9 * e, h * 0.6);
            }
            default -> {
                // Orbit
                float prog = slowMo(k);
                double ang = a0 + side * Math.toRadians(110) * prog;
                double r = 3.4 - 0.8 * easeInOut(k);
                cam = spot.add(Math.cos(ang) * r, h * 0.75 + 0.5 - 0.25 * k, Math.sin(ang) * r);
            }
        }
        // keep the camera out of walls / floors / ceilings: pull it toward the subject, in fast, out slowly
        var mc = MinecraftClient.getInstance();
        Vec3d d = cam.subtract(look);
        double len = d.length();
        double want = 1;
        if (mc.world != null && focused != null && len > 1e-4) {
            HitResult hr = mc.world.raycast(new RaycastContext(look, cam, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, focused));
            if (hr.getType() != HitResult.Type.MISS) want = Math.max(0.5, hr.getPos().distanceTo(look) - 0.3) / len;
        }
        want = Math.min(1, want);
        clip = want < clip ? want : clip + (want - clip) * 0.08;
        cam = look.add(d.multiply(clip));
        Vec3d to = look.subtract(cam);
        float oyaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90) + yawOff;
        float opitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        opitch = MathHelper.clamp(opitch, -89.9f, 89.9f);
        Vec3d eye = focused == null ? cam : focused.getCameraPosVec(tickProgress);
        float pyaw = focused == null ? oyaw : focused.getYaw(tickProgress), ppitch = focused == null ? opitch : focused.getPitch(tickProgress);
        Vec3d p = eye.lerp(cam, w).add(0, Math.sin(Math.PI * w) * 0.4 * (1 - w), 0);
        cx = p.x;
        cy = p.y;
        cz = p.z;
        cyaw = MathHelper.lerpAngleDegrees(w, pyaw, oyaw);
        cpitch = MathHelper.lerp(w, ppitch, opitch);
    }
}

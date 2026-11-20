package tenacity.modules;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The rendered body pose of a living entity for this frame, matching vanilla PlayerEntityRenderer.setupTransforms
 * (1.21.11): rotate Y by (180 - bodyYaw); when gliding rotate X by glideProgress * (-90 - pitch) and Y by the flying
 * rotation; when leaning (swimming / crawling) rotate X by lerp(leaning, 0, touchingWater ? -90 - pitch : -90) and,
 * when swimming, translate (0, -1, 0.3). The sneaking position offset (-2/16 * scale) is applied too.
 * <p>
 * Local coordinates: x = the body's right, y = feet -> head, z = the body's front (all in blocks, before scale).
 * Everything uses tick-delta interpolated values.
 */
public final class BodyAnchor {
    /** Model is rendered at 15/16 scale: head top ~1.875, neck ~1.41 above the feet. */
    public static final double NECK = 1.5 * 0.9375, HEAD = 0.5 * 0.9375;

    public final LivingEntity e;
    public final float td;
    /** World pivot (feet), world unit axes of the body. */
    public final Vec3d origin, right, up, fwd;
    /** World unit axes of the head (body + head yaw / pitch relative to it). */
    public final Vec3d hRight, hUp, hFwd;
    public final double scale;
    public final boolean sneaking, gliding, swimming;
    /** 0 = upright, 1 = lying along the look / velocity (gliding, swimming, crawling). */
    public final float lying;
    /** Interpolated per-tick velocity and horizontal speed (blocks / tick). */
    public final Vec3d vel;
    public final double speed;
    private final double neckY;

    private BodyAnchor(LivingEntity e, float td) {
        this.e = e;
        this.td = td;
        scale = e.getScale();
        sneaking = e.isInSneakingPose();
        gliding = e.isGliding();
        swimming = e.isSwimming();
        float bodyYaw = MathHelper.lerpAngleDegrees(td, e.lastBodyYaw, e.bodyYaw);
        float headYaw = MathHelper.lerpAngleDegrees(td, e.lastHeadYaw, e.headYaw);
        float pitch = e.getLerpedPitch(td);
        float leaning = e.getLeaningPitch(td);
        Vec3d last = new Vec3d(e.lastX, e.lastY, e.lastZ);
        Vec3d pos = e.getLerpedPos(td);
        vel = e.getEntityPos().subtract(last);
        speed = vel.horizontalLength();

        Quaternionf q = new Quaternionf().rotateY((float) Math.toRadians(180 - bodyYaw));
        Vec3d off = Vec3d.ZERO;
        float lie = 0;
        if (gliding) {
            float gt = e.getGlidingTicks() + td;
            float prog = MathHelper.clamp(gt * gt / 100f, 0, 1);
            if (!e.isUsingRiptide()) q.rotateX((float) Math.toRadians(prog * (-90 - pitch)));
            Vec3d look = e.getRotationVec(td), v = e.getVelocity();
            if (v.horizontalLengthSquared() > 1e-5 && look.horizontalLengthSquared() > 1e-5) {
                double d = v.getHorizontal().normalize().dotProduct(look.getHorizontal().normalize());
                double c = v.x * look.z - v.z * look.x;
                q.rotateY((float) (Math.signum(c) * Math.acos(Math.min(1, Math.abs(d)))));
            }
            lie = prog;
        } else if (leaning > 0) {
            float g = e.isTouchingWater() ? -90 - pitch : -90;
            q.rotateX((float) Math.toRadians(MathHelper.lerp(leaning, 0, g)));
            if (swimming) off = vec(q, 0, -1, 0.3f);
            lie = leaning;
        }
        lying = lie;
        Vec3d o = pos.add(off);
        if (sneaking) o = o.add(0, -2.0 / 16 * scale, 0);
        origin = o;
        right = vec(q, 1, 0, 0);
        up = vec(q, 0, 1, 0);
        fwd = vec(q, 0, 0, -1);
        neckY = NECK - (sneaking ? 0.25 : 0);

        // head: vanilla biped head yaw / pitch relative to the body; gliding / swimming look ~45 deg "up" in the model
        float relYaw = MathHelper.wrapDegrees(headYaw - bodyYaw);
        float hp = lie > 0 ? MathHelper.lerp(lie, pitch, -45) : pitch;
        double a = Math.toRadians(relYaw), p = Math.toRadians(hp);
        // local head axes (x right, y up, z front): pitch about x, then yaw about y toward the right
        double[] lu = rotYaw(0, Math.cos(p), Math.sin(p), a);   // looking down tilts the head's up forward
        double[] lf = rotYaw(0, -Math.sin(p), Math.cos(p), a);
        double[] lr = rotYaw(1, 0, 0, a);
        hUp = local(lu[0], lu[1], lu[2]);
        hFwd = local(lf[0], lf[1], lf[2]);
        hRight = local(lr[0], lr[1], lr[2]);
    }

    /** Yaw around local y; positive turns the front (+z) toward the right (+x). */
    private static double[] rotYaw(double x, double y, double z, double a) {
        return new double[]{x * Math.cos(a) + z * Math.sin(a), y, -x * Math.sin(a) + z * Math.cos(a)};
    }

    private static Vec3d vec(Quaternionf q, float x, float y, float z) {
        Vector3f v = q.transform(new Vector3f(x, y, z));
        return new Vec3d(v.x, v.y, v.z);
    }

    public static BodyAnchor of(LivingEntity e, float td) {
        return new BodyAnchor(e, td);
    }

    /** Direction expressed in body-local axes, to world (unscaled). */
    public Vec3d local(double x, double y, double z) {
        return right.multiply(x).add(up.multiply(y)).add(fwd.multiply(z));
    }

    /** A point in body-local coordinates (x right, y up from the feet, z front), scaled with the entity. */
    public Vec3d at(double x, double y, double z) {
        return origin.add(local(x * scale, y * scale, z * scale));
    }

    /** The neck pivot (head rotation centre). */
    public Vec3d neck() {
        return at(0, neckY, 0);
    }

    /** A point in head-local coordinates (x right, y up from the neck, z face direction), scaled. */
    public Vec3d head(double x, double y, double z) {
        return neck().add(hRight.multiply(x * scale)).add(hUp.multiply(y * scale)).add(hFwd.multiply(z * scale));
    }

    /** Top of the head plus {@code above} blocks along the head's up axis. */
    public Vec3d headTop(double above) {
        return head(0, HEAD + above, 0);
    }

    /** Body centre (chest / waist) - the centre of auras, bubbles, orbits. */
    public Vec3d center() {
        return at(0, (neckY) * 0.62, 0);
    }

    /** A point on the upper back at fraction {@code f} (0 = feet, 1 = neck) of the body, {@code out} behind it. */
    public Vec3d back(double f, double out) {
        return at(0, neckY * f, -0.16 - out);
    }

    /** The length of the body axis feet -> head top (scaled). */
    public double length() {
        return (neckY + HEAD) * scale;
    }

    /** Backward unit vector of the body. */
    public Vec3d backDir() {
        return fwd.multiply(-1);
    }

    /** True when the entity is a player rendered in first person by the local camera. */
    public boolean firstPersonSelf() {
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        return e == mc.player && mc.options.getPerspective().isFirstPerson();
    }

    /** Whether this player is flying with an elytra or otherwise moving fast (spreads wings etc.). */
    public boolean flying() {
        return gliding || (e instanceof PlayerEntity p && p.getAbilities().flying);
    }
}

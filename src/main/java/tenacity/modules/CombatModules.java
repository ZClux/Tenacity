package tenacity.modules;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;

import java.util.List;

/** Combat tab. */
final class CombatModules {
    private CombatModules() {
    }

    static List<Module> create() {
        return List.of(new tenacity.modules.TriggerBot(), new AimAssist());
    }

    /**
     * Frame-rate independent aim assist. It aims at the point of the target's hitbox closest to the crosshair, eases towards it with a
     * speed cap (degrees per second), adds slow human drift, snaps every step to the mouse sensitivity grid (like a real mouse) and stops
     * once the crosshair is on the hitbox. It only runs while you are holding the attack key by default.
     */
    static final class AimAssist extends Module {
        private final Setting.Num range = num("Range", 4.5, 2, 8, 0.25);
        private final Setting.Num fov = num("FOV", 90, 10, 360, 5);
        private final Setting.Num speed = num("Speed", 55, 5, 100, 1);
                private final Setting.Bool horizontal = bool("Horizontal", true);
        private final Setting.Num hSpeed = num("Horizontal Speed (deg/s)", 180, 20, 600, 10).showIf(() -> horizontal.on());
        private final Setting.Bool vertical = bool("Vertical", true);
        private final Setting.Num vSpeed = num("Vertical Speed (deg/s)", 90, 20, 400, 10).showIf(() -> vertical.on());
        private final Setting.Num smooth = num("Smoothness", 50, 0, 100, 1);
        private final Setting.Bool clickOnly = bool("Only While Clicking", true);
        private final Setting.Bool weaponOnly = bool("Weapons Only", false);
        private final Setting.Bool sticky = bool("Sticky Target", true);
        private final Setting.Bool lineOfSight = bool("Line Of Sight", true);
        private final Setting.Bool teams = bool("Ignore Teammates", true);
        private final Setting.Bool gcd = bool("Mouse Grid Snap", true);
        private final Setting.Bool human = bool("Humanize", true);

        private int lockId = -1;
        private long last = System.nanoTime();
        private float noiseYaw, noisePitch, yawVel, pitchVel, remYaw, remPitch;

        AimAssist() {
            super("Aim Assist", "Eases your aim onto nearby players", Category.COMBAT);
        }

        @Override
        protected void onDisable() {
            lockId = -1;
            yawVel = pitchVel = remYaw = remPitch = 0f;
        }

        private boolean weapon() {
            var s = mc.player.getMainHandStack();
            return s.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) || s.isIn(net.minecraft.registry.tag.ItemTags.AXES)
                    || s.isIn(net.minecraft.registry.tag.ItemTags.SPEARS) || s.getItem() == Items.MACE || s.getItem() == Items.TRIDENT;
        }

        /** Closest point of the box to where the player is already looking, kept inside the body. */
        private Vec3d aimPoint(LivingEntity t, Vec3d eye, Vec3d look) {
            var box = t.getBoundingBox().contract(0.08, 0.1, 0.08);
            Vec3d far = eye.add(look.multiply(eye.distanceTo(t.getEyePos()) + 0.5));
            double x = MathHelper.clamp(far.x, box.minX, box.maxX);
            double y = MathHelper.clamp(far.y, box.minY + box.getLengthY() * 0.25, box.maxY - box.getLengthY() * 0.1);
            double z = MathHelper.clamp(far.z, box.minZ, box.maxZ);
            return new Vec3d(x, y, z);
        }

        @Override
        public void render2d(float tickDelta) {
            long now = System.nanoTime();
            float dt = MathHelper.clamp((now - last) / 1.0e9f, 0.0005f, 0.1f);
            last = now;
            var p = mc.player;
            if (p == null || mc.world == null || mc.currentScreen != null || !p.isAlive() || p.isSpectator()) return;
            if (clickOnly.on() && !mc.options.attackKey.isPressed()) return;
            if (weaponOnly.on() && !weapon()) return;
            Vec3d eye = p.getEyePos();
            Vec3d look = p.getRotationVec(1.0f);
            LivingEntity best = null;
            double bestAngle = fov.value / 2.0;
            Entity locked = lockId == -1 ? null : mc.world.getEntityById(lockId);
            if (sticky.on() && locked instanceof LivingEntity le && le.isAlive() && p.distanceTo(le) <= range.value + 1.0) {
                best = le;
            } else {
                lockId = -1;
                for (AbstractClientPlayerEntity t : mc.world.getPlayers()) {
                    if (t == p || !t.isAlive() || t.isSpectator() || t.isInvisible()) continue;
                    if (teams.on() && t.isTeammate(p)) continue;
                    if (p.distanceTo(t) > range.value) continue;
                    if (lineOfSight.on() && !p.canSee(t)) continue;
                    Vec3d to = t.getBoundingBox().getCenter().subtract(eye);
                    double ang = Math.toDegrees(Math.acos(MathHelper.clamp(to.normalize().dotProduct(look), -1.0, 1.0)));
                    if (ang < bestAngle) {
                        bestAngle = ang;
                        best = t;
                    }
                }
            }
            if (best == null) {
                yawVel = pitchVel = 0f;
                return;
            }
            if (lineOfSight.on() && !p.canSee(best)) {
                lockId = -1;
                return;
            }
            lockId = best.getId();
            // already on the hitbox: leave the aim alone, that is what makes it feel like your own aim
            if (mc.crosshairTarget instanceof EntityHitResult hr && hr.getEntity() == best) {
                yawVel *= 0.6f;
                pitchVel *= 0.6f;
                return;
            }

            Vec3d aim = aimPoint(best, eye, look).add(best.getVelocity().multiply(0.6, 0.0, 0.6).multiply(dt * 4));
            double dx = aim.x - eye.x, dy = aim.y - eye.y, dz = aim.z - eye.z;
            float wantYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            float ey = MathHelper.wrapDegrees(wantYaw - p.getYaw());
            float ep = wantPitch - p.getPitch();

            if (human.on()) {
                // slow drifting offset so the path is never a perfect pull
                noiseYaw += (float) ((Math.random() - 0.5) * 0.35) - noiseYaw * 1.5f * dt;
                noisePitch += (float) ((Math.random() - 0.5) * 0.2) - noisePitch * 1.5f * dt;
                ey += MathHelper.clamp(noiseYaw, -0.8f, 0.8f);
                ep += MathHelper.clamp(noisePitch, -0.5f, 0.5f);
            }
            // spring smoothing: the turn speed eases towards the wanted speed, so it accelerates and settles without any snap
            float accel = 1.0f - (float) Math.exp(-(6.0 + smooth.value * 0.5) * dt);
            float wantYawVel = horizontal.on() ? MathHelper.clamp(ey * (float) (speed.value * 0.12), -(float) hSpeed.value, (float) hSpeed.value) : 0f;
            float wantPitchVel = vertical.on() ? MathHelper.clamp(ep * (float) (speed.value * 0.12), -(float) vSpeed.value, (float) vSpeed.value) : 0f;
            yawVel += (wantYawVel - yawVel) * accel;
            pitchVel += (wantPitchVel - pitchVel) * accel;
            float dyaw = yawVel * dt;
            float dpitch = pitchVel * dt;
            if (gcd.on()) {
                // snap to the mouse grid but carry the remainder, so slow movement stays smooth instead of stepping
                double f = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
                double step = f * f * f * 8.0 * 0.15;
                remYaw += dyaw;
                remPitch += dpitch;
                dyaw = (float) (Math.floor(remYaw / step) * step);
                dpitch = (float) (Math.floor(remPitch / step) * step);
                remYaw -= dyaw;
                remPitch -= dpitch;
            }
            if (dyaw == 0f && dpitch == 0f) return;
            p.setYaw(p.getYaw() + dyaw);
            p.setPitch(MathHelper.clamp(p.getPitch() + dpitch, -90f, 90f));
        }
    }
}

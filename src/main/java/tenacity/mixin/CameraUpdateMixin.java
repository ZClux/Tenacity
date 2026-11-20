package tenacity.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.gfx.FreecamFx;

/** Free cam places the camera wherever it wants; free look orbits the player with its own angle and distance. */
@Mixin(Camera.class)
public abstract class CameraUpdateMixin {
    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void moveBy(float x, float y, float z);

    @Shadow
    private float clipToSpace(float dist) {
        throw new AssertionError();
    }

    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setPos(DDD)V", shift = At.Shift.AFTER), cancellable = true)
    private void tenacity$detach(World world, Entity focused, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        if (tenacity.gfx.CutsceneCam.active && !FreecamFx.freecam) {
            tenacity.gfx.CutsceneCam.pose(focused, tickProgress);
            setPos(tenacity.gfx.CutsceneCam.cx, tenacity.gfx.CutsceneCam.cy, tenacity.gfx.CutsceneCam.cz);
            setRotation(tenacity.gfx.CutsceneCam.cyaw, tenacity.gfx.CutsceneCam.cpitch);
            ci.cancel();
        } else if (FreecamFx.freecam) {
            setPos(MathHelper.lerp(tickProgress, FreecamFx.px, FreecamFx.x), MathHelper.lerp(tickProgress, FreecamFx.py, FreecamFx.y),
                    MathHelper.lerp(tickProgress, FreecamFx.pz, FreecamFx.z));
            setRotation(FreecamFx.yawAt(tickProgress), FreecamFx.pitchAt(tickProgress));
            ci.cancel();
        } else if (FreecamFx.freelook && focused instanceof ClientPlayerEntity) {
            float yaw = FreecamFx.lookYaw, pitch = FreecamFx.lookPitch;
            if (inverseView) {
                yaw += 180f;
                pitch = -pitch;
            }
            setRotation(yaw, pitch);
            float d = FreecamFx.frameDist(); // eased per frame, like Zoom: glides out on enter and back in on exit
            moveBy(FreecamFx.collide ? -clipToSpace(d) : -d, 0f, 0f);
            ci.cancel();
        }
    }
}

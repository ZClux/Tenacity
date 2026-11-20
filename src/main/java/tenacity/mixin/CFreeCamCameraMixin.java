package tenacity.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.CryptoFreeCam;

/** Crypto's VPLFreeCamCameraMixin: the camera sits at the interpolated free camera position and rotation. */
@Mixin(value = Camera.class, priority = 500)
public abstract class CFreeCamCameraMixin {
    @Shadow
    private Vec3d pos;

    @Shadow
    @Final
    private BlockPos.Mutable blockPos;

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Inject(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setPos(DDD)V", shift = At.Shift.AFTER), cancellable = true)
    private void tenacity$freeCamUpdate(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        CryptoFreeCam fc = CryptoFreeCam.INSTANCE;
        if (fc != null && fc.enabled() && fc.pos != null && fc.prevPos != null) {
            setRotation(fc.getInterpolatedYaw(tickProgress), fc.getInterpolatedPitch(tickProgress));
            Vec3d p = fc.interpolatedPos(tickProgress);
            this.pos = p;
            blockPos.set(p.x, p.y, p.z);
            ci.cancel();
        }
    }
}

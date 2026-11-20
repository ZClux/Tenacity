package tenacity.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.gfx.FreecamFx;

/** While free cam / free look is on the mouse steers the detached camera instead of the player. */
@Mixin(Entity.class)
public abstract class LookDirectionMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void tenacity$steerCamera(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        if (FreecamFx.freecam) {
            FreecamFx.rotateFree(cursorDeltaX, cursorDeltaY);
            ci.cancel();
        } else if (FreecamFx.freelook) {
            FreecamFx.rotateLook(cursorDeltaX, cursorDeltaY);
            ci.cancel();
        }
    }
}

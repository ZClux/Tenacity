package tenacity.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.gfx.ViewModelFx;

/** Scales how long every entity's swing takes, which makes the animation genuinely faster or slower. */
@Mixin(LivingEntity.class)
public abstract class SwingSpeedMixin {
    @Inject(method = "getHandSwingDuration", at = @At("RETURN"), cancellable = true)
    private void tenacity$swingSpeed(CallbackInfoReturnable<Integer> cir) {
        float s = ViewModelFx.swingSpeed;
        if (s != 1f && cir.getReturnValue() > 1) cir.setReturnValue(Math.max(1, Math.round(cir.getReturnValue() / s)));
    }
}

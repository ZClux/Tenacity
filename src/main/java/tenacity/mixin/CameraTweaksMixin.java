package tenacity.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.RenderFlags;

@Mixin(GameRenderer.class)
public abstract class CameraTweaksMixin {
    @Inject(method = "tiltViewWhenHurt", at = @At("HEAD"), cancellable = true)
    private void tenacity$noHurtCam(MatrixStack matrices, float tickDelta, CallbackInfo ci) {
        if (RenderFlags.noHurtCam) ci.cancel();
    }
}

package tenacity.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.RenderFlags;

@Mixin(GameRenderer.class)
public abstract class ZoomMixin {
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void tenacity$zoom(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        cir.setReturnValue(tenacity.gfx.CutsceneCam.fov(RenderFlags.zoom(cir.getReturnValue())));
    }
}

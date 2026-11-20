package tenacity.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.gfx.ItemAtlas;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /** The shader sky goes in while the depth buffer still holds the world (right before it is cleared for the hand). */
    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void tenacity$sky(RenderTickCounter counter, CallbackInfo ci) {
        tenacity.gfx.MotionBlurFx.captureDepth();
        tenacity.gfx.XrayRenderer.draw(tenacity.gfx.XrayFeature.CAPTURES);
        tenacity.gfx.XrayFeature.CAPTURES.clear(); // refilled by the next frame's entity pass
    }

    /** Renders the items the inventory HUD needs into an offscreen atlas before the gui pass. */
    @Inject(method = "renderWorld", at = @At("TAIL"))
    private void tenacity$items(RenderTickCounter counter, CallbackInfo ci) {
        tenacity.gfx.MotionBlurFx.apply();
        ItemAtlas.render();
    }
}

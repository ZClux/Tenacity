package tenacity.mixin;

import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.RenderFlags;

/** Block Highlight replaces the vanilla black block outline. */
@Mixin(WorldRenderer.class)
public abstract class BlockOutlineMixin {
    @Inject(method = "renderTargetBlockOutline", at = @At("HEAD"), cancellable = true)
    private void tenacity$noOutline(CallbackInfo ci) {
        if (RenderFlags.blockHighlight) ci.cancel();
    }
}

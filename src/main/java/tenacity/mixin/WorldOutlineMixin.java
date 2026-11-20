package tenacity.mixin;

import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.gl.ShaderLoader;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.gfx.GlowFx;

import java.util.Set;

/** While Glow ESP is on, the outline buffer keeps the raw silhouettes; GlowFx draws the glow instead of vanilla. */
@Mixin(WorldRenderer.class)
public abstract class WorldOutlineMixin {
    @Inject(method = "drawEntityOutlinesFramebuffer", at = @At("HEAD"), cancellable = true)
    private void tenacity$noVanillaOutline(CallbackInfo ci) {
        if (!GlowFx.active() && !tenacity.gfx.FrostFx.active()) return;
        ci.cancel(); // drawn at the end of render() instead, like Crypto, so it sits exactly on this frame's models
    }

    /** Crypto's VSHWorldRendererMixin: publish the ESP effects once the world (and this frame's outline mask) is done. */
    @Inject(method = "render", at = @At("RETURN"))
    private void tenacity$effects(CallbackInfo ci) {
        if (tenacity.gfx.FrostFx.active()) tenacity.gfx.FrostFx.draw();
        if (GlowFx.active()) GlowFx.draw();
    }
}

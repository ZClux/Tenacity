package tenacity.mixin;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EndCrystalEntityRenderer;
import net.minecraft.client.render.entity.state.EndCrystalEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.CrystalShader;

/** Crystal Shader: hides the vanilla End Crystal (or just its base / beam) so the custom one can be drawn. */
@Mixin(EndCrystalEntityRenderer.class)
public class EndCrystalRendererMixin {
    @Inject(method = "render(Lnet/minecraft/client/render/entity/state/EndCrystalEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void tenacity$crystalShader(EndCrystalEntityRenderState state, MatrixStack matrices, OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
        if (CrystalShader.hideVanilla()) {
            ci.cancel();
            return;
        }
        if (CrystalShader.hideBase()) state.baseVisible = false;
        if (CrystalShader.hideBeam()) state.beamOffset = null;
    }
}

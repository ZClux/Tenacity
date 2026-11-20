package tenacity.mixin;

import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.CryptoFreeCam;

/** Crypto's VPLFreeCamOverlayMixin: no in-wall overlay while the camera is detached. */
@Mixin(InGameOverlayRenderer.class)
public class CFreeCamOverlayMixin {
    @Inject(method = "renderInWallOverlay", at = @At("HEAD"), cancellable = true)
    private static void tenacity$freecamNoWall(Sprite sprite, MatrixStack matrices, VertexConsumerProvider vertexConsumers, CallbackInfo ci) {
        if (CryptoFreeCam.on()) ci.cancel();
    }
}

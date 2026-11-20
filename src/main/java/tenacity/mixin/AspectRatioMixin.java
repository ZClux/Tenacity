package tenacity.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.RenderFlags;

/** Aspect Ratio: squeezes the world projection horizontally so the game looks stretched like a 4:3 (etc.) res. */
@Mixin(GameRenderer.class)
public abstract class AspectRatioMixin {
    @Inject(method = "getBasicProjectionMatrix", at = @At("RETURN"))
    private void tenacity$aspect(float fov, CallbackInfoReturnable<Matrix4f> cir) {
        float target = RenderFlags.aspect();
        if (target <= 0) return;
        var w = MinecraftClient.getInstance().getWindow();
        float real = (float) w.getFramebufferWidth() / Math.max(1, w.getFramebufferHeight());
        // vanilla divides by the real aspect; dividing by the target instead stretches the picture to fill the screen
        cir.getReturnValue().m00(cir.getReturnValue().m00() * real / target);
    }
}

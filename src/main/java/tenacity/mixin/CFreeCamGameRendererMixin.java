package tenacity.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.CryptoFreeCam;

/** Crypto's VPLFreeCamGameRendererMixin: the hand is drawn while the camera is detached (unless OG Freecam). */
@Mixin(value = GameRenderer.class, priority = 1500)
public abstract class CFreeCamGameRendererMixin {
    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    protected abstract void renderHand(float tickProgress, boolean sleeping, Matrix4f positionMatrix);

    @Inject(method = "renderWorld", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = {"ldc=hand"}))
    private void tenacity$freeCamHand(RenderTickCounter tickCounter, CallbackInfo ci, @Local(ordinal = 0) Matrix4f projection, @Local(ordinal = 0) float tickDelta) {
        if (client.world == null || client.player == null) return;
        if (CryptoFreeCam.on() && !CryptoFreeCam.INSTANCE.isOgFreecam()) renderHand(tickDelta, false, projection);
    }
}

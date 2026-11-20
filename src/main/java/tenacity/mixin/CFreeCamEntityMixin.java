package tenacity.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.CryptoFreeCam;

/** Crypto's VPLFreeCamEntityMixin: your own body stays visible while the camera is detached. */
@Mixin(Entity.class)
public abstract class CFreeCamEntityMixin {
    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
    private void tenacity$freecamSeeOwnBody(PlayerEntity viewer, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && (Object) this == mc.player && viewer == mc.player && CryptoFreeCam.on() && CryptoFreeCam.INSTANCE.isShowPlayerModel())
            cir.setReturnValue(false);
    }
}

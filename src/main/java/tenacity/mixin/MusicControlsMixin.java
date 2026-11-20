package tenacity.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.SkyLyrics;

/** Lets Sky Lyrics' floating music buttons swallow the left click that pressed them (no attack / block breaking). */
@Mixin(MinecraftClient.class)
public abstract class MusicControlsMixin {
    @Inject(method = "doAttack", at = @At("HEAD"), cancellable = true)
    private void tenacity$musicClick(CallbackInfoReturnable<Boolean> cir) {
        if (SkyLyrics.consumeClick()) cir.setReturnValue(false);
    }

    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void tenacity$musicHold(boolean breaking, CallbackInfo ci) {
        if (breaking && SkyLyrics.holdingClick()) ci.cancel();
    }
}

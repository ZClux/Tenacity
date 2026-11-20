package tenacity.mixin;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.ui.Overlay;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    /** Runs after the frame was blitted to the window and right before buffers are swapped. */
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/Window;swapBuffers(Lnet/minecraft/client/util/tracy/TracyFrameCapturer;)V"))
    private void tenacity$overlay(boolean tick, CallbackInfo ci) {
        Overlay.render();
    }
}

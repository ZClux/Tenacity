package tenacity.mixin;

import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.CreamyKeys;

/** Feeds raw mouse button / scroll events to Creamy Keys. */
@Mixin(Mouse.class)
public abstract class MouseSoundMixin {
    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void tenacity$clickSound(long window, MouseInput input, int action, CallbackInfo ci) {
        CreamyKeys.onMouseButton(window, input.button(), action);
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"))
    private void tenacity$scrollSound(long window, double h, double v, CallbackInfo ci) {
        CreamyKeys.onMouseScroll(window, h, v);
    }
}

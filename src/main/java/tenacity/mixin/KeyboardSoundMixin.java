package tenacity.mixin;

import net.minecraft.client.Keyboard;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.CreamyKeys;

/** Feeds raw key / char events to Creamy Keys. */
@Mixin(Keyboard.class)
public abstract class KeyboardSoundMixin {
    @Inject(method = "onKey", at = @At("HEAD"))
    private void tenacity$keySound(long window, int action, KeyInput input, CallbackInfo ci) {
        CreamyKeys.onKey(window, action, input.key());
    }

    @Inject(method = "onChar", at = @At("HEAD"))
    private void tenacity$charSound(long window, CharInput input, CallbackInfo ci) {
        CreamyKeys.onChar(window, input.codepoint());
    }
}

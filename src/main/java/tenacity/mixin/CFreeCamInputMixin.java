package tenacity.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import tenacity.modules.CryptoFreeCam;

/** Crypto's EvInputEventHook (FreeCam part): the movement keys drive the camera and the player gets no input. */
@Mixin(KeyboardInput.class)
public class CFreeCamInputMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "NEW", target = "(ZZZZZZZ)Lnet/minecraft/util/PlayerInput;"))
    private PlayerInput tenacity$input(PlayerInput original) {
        return CryptoFreeCam.on() ? CryptoFreeCam.INSTANCE.onInput(original) : original;
    }
}

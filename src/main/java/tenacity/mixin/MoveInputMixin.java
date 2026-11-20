package tenacity.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import tenacity.modules.CryptoMovement;

/** Post-processes the freshly built player input for Inventory Move and Move Fix. */
@Mixin(KeyboardInput.class)
public abstract class MoveInputMixin {
    @ModifyExpressionValue(method = "tick", at = @At(value = "NEW", target = "(ZZZZZZZ)Lnet/minecraft/util/PlayerInput;"))
    private PlayerInput tenacity$moveInput(PlayerInput original) {
        return CryptoMovement.adjustInput(original);
    }
}

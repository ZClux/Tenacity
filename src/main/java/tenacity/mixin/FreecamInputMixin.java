package tenacity.mixin;

import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.gfx.FreecamFx;

/**
 * While the free cam is detached the body must stand still. Module ticks run at END_CLIENT_TICK, after the player has
 * already moved, so the input has to be cleared here, right after the keys were read.
 */
@Mixin(KeyboardInput.class)
public abstract class FreecamInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void tenacity$freecamInput(CallbackInfo ci) {
        if (FreecamFx.freecam) ((KeyboardInput) (Object) this).playerInput = PlayerInput.DEFAULT;
    }
}

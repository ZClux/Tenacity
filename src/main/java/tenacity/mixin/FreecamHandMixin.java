package tenacity.mixin;

import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import tenacity.gfx.FreecamFx;

/** The first person hand keeps rendering while the free cam camera is detached. */
@Mixin(GameRenderer.class)
public abstract class FreecamHandMixin {
    @Redirect(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/Perspective;isFirstPerson()Z"))
    private boolean tenacity$freecamHands(Perspective perspective) {
        if (tenacity.modules.CryptoFreeCam.on()) return !tenacity.modules.CryptoFreeCam.INSTANCE.isOgFreecam();
        return FreecamFx.freecam || perspective.isFirstPerson();
    }
}

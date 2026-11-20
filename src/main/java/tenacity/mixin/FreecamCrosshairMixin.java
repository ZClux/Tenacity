package tenacity.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.option.Perspective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import tenacity.gfx.FreecamFx;

/** The crosshair stays on screen while the free cam is detached from the body. */
@Mixin(InGameHud.class)
public abstract class FreecamCrosshairMixin {
    @Redirect(method = "renderCrosshair", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/option/Perspective;isFirstPerson()Z"))
    private boolean tenacity$crosshair(Perspective perspective) {
        return tenacity.modules.CryptoFreeCam.on() || FreecamFx.freecam || perspective.isFirstPerson();
    }
}

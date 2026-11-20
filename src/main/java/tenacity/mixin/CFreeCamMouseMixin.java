package tenacity.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.CryptoFreeCam;

/** Crypto's mouse hooks (FreeCam part): the mouse turns the free camera, the wheel changes its speed. */
@Mixin(Mouse.class)
public abstract class CFreeCamMouseMixin {
    @WrapWithCondition(method = "updateMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private boolean tenacity$mouseRotation(ClientPlayerEntity instance, double dx, double dy) {
        if (!CryptoFreeCam.on()) return true;
        CryptoFreeCam.INSTANCE.onMouse(dx, dy);
        return false;
    }

    @Inject(method = "onMouseScroll", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;getInventory()Lnet/minecraft/entity/player/PlayerInventory;"), cancellable = true)
    private void tenacity$scroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (tenacity.modules.RenderFlags.zoomScroll(vertical)) {
            ci.cancel();
            return;
        }
        if (!CryptoFreeCam.on()) return;
        CryptoFreeCam.INSTANCE.onScroll(vertical);
        ci.cancel();
    }
}

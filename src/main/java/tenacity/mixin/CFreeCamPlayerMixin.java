package tenacity.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.CryptoFreeCam;

/** Crypto's FreeCam Freeze: the player does not move and sends no movement packets. */
@Mixin(ClientPlayerEntity.class)
public abstract class CFreeCamPlayerMixin {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3d tenacity$freeze(Vec3d movement) {
        return CryptoFreeCam.on() && CryptoFreeCam.INSTANCE.isFrozen() ? Vec3d.ZERO : movement;
    }

    @Inject(method = "sendMovementPackets", at = @At("HEAD"), cancellable = true)
    private void tenacity$noPackets(CallbackInfo ci) {
        if (CryptoFreeCam.on() && CryptoFreeCam.INSTANCE.isFrozen()) ci.cancel();
    }
}

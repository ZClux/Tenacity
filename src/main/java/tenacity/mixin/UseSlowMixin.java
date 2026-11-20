package tenacity.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import tenacity.modules.CryptoMovement;

/** Lets No Slow remove the item-use slowdown. */
@Mixin(ClientPlayerEntity.class)
public abstract class UseSlowMixin {
    @Redirect(method = "applyMovementSpeedFactors", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/math/Vec2f;multiply(F)Lnet/minecraft/util/math/Vec2f;", ordinal = 1))
    private Vec2f tenacity$noSlow(Vec2f vec, float multiplier) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (CryptoMovement.skipUseSlowdown(self)) return vec.multiply(1.0F);
        return vec.multiply(multiplier);
    }
}

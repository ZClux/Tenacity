package tenacity.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.SkinProtect;

/** Entity skin lookup, used by the player renderer (world, hand, third person) and by the client's HUD heads. */
@Mixin(AbstractClientPlayerEntity.class)
public abstract class SkinProtectMixin {
    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void tenacity$skin(CallbackInfoReturnable<SkinTextures> cir) {
        SkinProtect sp = SkinProtect.I;
        if (sp == null || !sp.enabled()) return;
        cir.setReturnValue(sp.apply(((AbstractClientPlayerEntity) (Object) this).getUuid(), cir.getReturnValue()));
    }

    @Inject(method = "hasExtraEars", at = @At("RETURN"), cancellable = true)
    private void tenacity$ears(CallbackInfoReturnable<Boolean> cir) {
        SkinProtect sp = SkinProtect.I;
        if (sp != null && cir.getReturnValueZ() && sp.hidesEars(((AbstractClientPlayerEntity) (Object) this).getUuid())) cir.setReturnValue(false);
    }
}

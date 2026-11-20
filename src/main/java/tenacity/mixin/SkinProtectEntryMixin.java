package tenacity.mixin;

import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.SkinProtect;

/** Tab list heads, spectator menu and every other consumer of the player list skin. */
@Mixin(PlayerListEntry.class)
public abstract class SkinProtectEntryMixin {
    @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true)
    private void tenacity$skin(CallbackInfoReturnable<SkinTextures> cir) {
        SkinProtect sp = SkinProtect.I;
        if (sp == null || !sp.enabled()) return;
        cir.setReturnValue(sp.apply(((PlayerListEntry) (Object) this).getProfile().id(), cir.getReturnValue()));
    }
}

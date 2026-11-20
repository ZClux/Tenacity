package tenacity.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.RenderFlags;

@Mixin(InGameHud.class)
public abstract class HotbarMixin {
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void tenacity$hotbar(DrawContext context, RenderTickCounter counter, CallbackInfo ci) {
        if (RenderFlags.customHotbar) ci.cancel();
    }

    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
    private void tenacity$status(DrawContext context, CallbackInfo ci) {
        if (RenderFlags.customStatus) ci.cancel();
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "getCurrentBarType", at = @At("RETURN"), cancellable = true)
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void tenacity$noXpBar(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable cir) {
        Object v = cir.getReturnValue();
        if (RenderFlags.customStatus && v instanceof Enum e && e.name().equals("EXPERIENCE"))
            cir.setReturnValue(Enum.valueOf(e.getDeclaringClass(), "EMPTY"));
    }

    @com.llamalad7.mixinextras.injector.v2.WrapWithCondition(method = "renderMainHud", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/bar/Bar;drawExperienceLevel(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/font/TextRenderer;I)V"))
    private boolean tenacity$noXpLevel(DrawContext context, net.minecraft.client.font.TextRenderer tr, int level) {
        return !RenderFlags.customStatus;
    }
}

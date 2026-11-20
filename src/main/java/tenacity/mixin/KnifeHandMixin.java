package tenacity.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.gfx.CsKnives;

/** The Knifes module draws the knife and the arms in place of the vanilla first person hand. */
@Mixin(HeldItemRenderer.class)
public abstract class KnifeHandMixin {
    @Inject(method = "renderFirstPersonItem", at = @At("HEAD"), cancellable = true)
    private void tenacity$knife(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress, ItemStack item,
                                float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        if (CsKnives.eligible(item) && CsKnives.render(player, hand, item, matrices, queue, light)) ci.cancel();
    }
}

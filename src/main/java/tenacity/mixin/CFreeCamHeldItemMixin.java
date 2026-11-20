package tenacity.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import tenacity.modules.CryptoFreeCam;

/** Crypto's VPLFreeCamHeldItemMixin: the held item uses the real player pitch, not the detached camera pitch. */
@Mixin(HeldItemRenderer.class)
public abstract class CFreeCamHeldItemMixin {
    @WrapOperation(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderFirstPersonItem(Lnet/minecraft/client/network/AbstractClientPlayerEntity;FFLnet/minecraft/util/Hand;FLnet/minecraft/item/ItemStack;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V"))
    private void tenacity$freecamItemPitch(HeldItemRenderer instance, AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, Operation<Void> original) {
        float renderPitch = CryptoFreeCam.on() ? player.getPitch() : pitch;
        original.call(instance, player, tickDelta, renderPitch, hand, swingProgress, item, equipProgress, matrices, queue, light);
    }
}

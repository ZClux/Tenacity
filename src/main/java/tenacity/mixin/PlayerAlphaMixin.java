package tenacity.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.RenderFlags;

/**
 * Player Transparency: vanilla already has a translucent path for invisible players that you are allowed to see (the 15%
 * "ghost"). Players picked by the module are sent down that path and the ghost alpha is replaced with the chosen one.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class PlayerAlphaMixin {
    @Unique
    private static boolean tenacity$translucent;

    @Inject(method = "isVisible", at = @At("HEAD"), cancellable = true)
    private void tenacity$visible(LivingEntityRenderState state, CallbackInfoReturnable<Boolean> cir) {
        tenacity$translucent = false;
        if (RenderFlags.playerAlpha >= 1f || !(state instanceof PlayerEntityRenderState ps) || state.invisible) return;
        var mc = MinecraftClient.getInstance();
        boolean self = mc.player != null && ps.id == mc.player.getId();
        if (self ? RenderFlags.alphaSelf : RenderFlags.alphaOthers) {
            tenacity$translucent = true;
            cir.setReturnValue(false);
        }
    }

    @ModifyConstant(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
            constant = @Constant(intValue = 654311423))
    private int tenacity$alpha(int ghost) {
        if (!tenacity$translucent) return ghost;
        return RenderFlags.glassColor(0xFFFFFFFF); // frosted: transparency plus the glass tint
    }
}

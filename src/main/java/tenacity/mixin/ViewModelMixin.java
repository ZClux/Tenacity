package tenacity.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.gfx.ViewModelFx;

@Mixin(HeldItemRenderer.class)
public abstract class ViewModelMixin {
    @Inject(method = "renderFirstPersonItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;push()V", ordinal = 0, shift = At.Shift.AFTER))
    private void tenacity$viewModel(AbstractClientPlayerEntity player, float tickProgress, float pitch, Hand hand, float swingProgress, ItemStack item,
                                    float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        Arm arm = hand == Hand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
        ViewModelFx.applyViewModel(matrices, arm == Arm.RIGHT ? 1 : -1);
    }

    @Inject(method = "swingArm", at = @At("HEAD"), cancellable = true)
    private void tenacity$swing(float swingProgress, MatrixStack matrices, int armX, Arm arm, CallbackInfo ci) {
        if (!ViewModelFx.swing) return;
        ViewModelFx.applySwing(matrices, armX, swingProgress);
        ci.cancel();
    }

    @Inject(method = "applyEquipOffset", at = @At("HEAD"), cancellable = true)
    private void tenacity$equip(MatrixStack matrices, Arm arm, float equipProgress, CallbackInfo ci) {
        if (!ViewModelFx.noEquip) return;
        int i = arm == Arm.RIGHT ? 1 : -1;
        matrices.translate(i * 0.56f, -0.52f, -0.72f);
        ci.cancel();
    }
}

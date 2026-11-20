package tenacity.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.RenderFlags;

/** Auto Tool picks the tool right before the block starts breaking, so the hit is made with the right tool from the first tick. */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class AutoToolMixin {
    @Inject(method = "attackBlock", at = @At("HEAD"))
    private void tenacity$toolAttack(BlockPos pos, Direction dir, CallbackInfoReturnable<Boolean> cir) {
        RenderFlags.autoTool(pos);
    }

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"))
    private void tenacity$toolProgress(BlockPos pos, Direction dir, CallbackInfoReturnable<Boolean> cir) {
        RenderFlags.autoTool(pos);
    }
}

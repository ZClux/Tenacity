package tenacity.mixin;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import tenacity.modules.RenderFlags;

@Mixin(ClientWorld.class)
public abstract class ClientWorldMixin {
    /** No Shading: every block face gets full brightness instead of the directional shade. */
    @Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
    private void tenacity$noShading(Direction direction, boolean shaded, CallbackInfoReturnable<Float> cir) {
        if (RenderFlags.noShading) cir.setReturnValue(1f);
    }
}

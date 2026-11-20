package tenacity.mixin;

import net.minecraft.client.render.chunk.ChunkOcclusionDataBuilder;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.modules.CryptoFreeCam;

/** Crypto's chunk occlusion hook: while the free camera flies (not OG mode) blocks never close chunk visibility. */
@Mixin(ChunkOcclusionDataBuilder.class)
public abstract class CFreeCamOcclusionMixin {
    @Inject(method = "markClosed", at = @At("HEAD"), cancellable = true)
    private void tenacity$markClosed(BlockPos pos, CallbackInfo ci) {
        if (CryptoFreeCam.on() && !CryptoFreeCam.INSTANCE.isOgFreecam()) ci.cancel();
    }
}

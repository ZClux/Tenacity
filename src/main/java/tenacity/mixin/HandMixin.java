package tenacity.mixin;

import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.command.RenderDispatcher;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.gfx.HandFx;

/** Hand shaders: everything queued by renderHand is drawn into the hand buffer and composited by {@link HandFx}. */
@Mixin(GameRenderer.class)
public abstract class HandMixin {
    @Shadow @Final private RenderDispatcher entityRenderDispatcher;
    @Shadow @Final private BufferBuilderStorage buffers;

    // after renderHand flushed what the world queued before it, so only the hand ends up in our buffer
    @Inject(method = "renderHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;draw()V", ordinal = 0, shift = At.Shift.AFTER))
    private void tenacity$handBegin(float tickProgress, boolean sleeping, Matrix4f positionMatrix, CallbackInfo ci) {
        HandFx.begin();
    }

    @Inject(method = "renderHand", at = @At("TAIL"))
    private void tenacity$handEnd(float tickProgress, boolean sleeping, Matrix4f positionMatrix, CallbackInfo ci) {
        if (!HandFx.active()) return;
        entityRenderDispatcher.render();
        buffers.getEntityVertexConsumers().draw();
        HandFx.end();
    }
}

package tenacity.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.memory.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.util.Projection;

/** Captures the exact view and projection matrices of the frame (including fov effects, bobbing and hurt tilt). */
@Mixin(WorldRenderer.class)
public abstract class WorldMatricesMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void tenacity$matrices(ObjectAllocator allocator, RenderTickCounter counter, boolean outline, Camera camera, Matrix4f position,
                                   Matrix4f projection, Matrix4f cullProjection, GpuBufferSlice fog, Vector4f fogColor, boolean sky, CallbackInfo ci) {
        Projection.capture(position, projection, camera.getCameraPos());
    }
}

package tenacity.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import voided.events.impl.WorldRenderEvent;
import voided.util.render.Render3D;

/** Fires {@link WorldRenderEvent} in the world pass (before the hand is drawn) and flushes the queued 3D shapes. */
@Mixin(GameRenderer.class)
public abstract class WorldRenderHookMixin {
    @Shadow
    @Final
    private MinecraftClient client;

    @Shadow
    @Final
    private Camera camera;

    @Shadow
    protected abstract void bobView(MatrixStack matrices, float tickDelta);

    @Shadow
    protected abstract void tiltViewWhenHurt(MatrixStack matrices, float tickDelta);

    @Shadow
    public abstract float getFov(Camera camera, float tickDelta, boolean changingFov);

    @Inject(method = "renderWorld", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = {"ldc=hand"}))
    private void tenacity$hookWorldRender(RenderTickCounter tickCounter, CallbackInfo ci, @Local(ordinal = 0) Matrix4f projection, @Local(ordinal = 1) Matrix4f view, @Local(ordinal = 0) float tickDelta, @Local MatrixStack matrixStack) {
        if (client.world == null || client.player == null) return;

        MatrixStack worldSpaceStack = new MatrixStack();
        worldSpaceStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        worldSpaceStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0F));

        Render3D.lastProjMat.set(client.gameRenderer.getBasicProjectionMatrix(getFov(camera, tickDelta, true)));
        Render3D.lastModMat.set(RenderSystem.getModelViewMatrix());
        Render3D.lastWorldSpaceMatrix.set(worldSpaceStack.peek().getPositionMatrix());

        Render3D.setLastWorldSpaceEntry(matrixStack.peek());
        Render3D.setLastTickDelta(tickDelta);
        Render3D.setLastCameraPos(camera.getCameraPos());
        Render3D.setLastCameraRotation(new Quaternionf(camera.getRotation()));
        tenacity.gfx.WorldFx.drawSky(); // sky before our 3D visuals, so it never paints over wings / halos in the sky
        tenacity.gfx.SkyVisualsFx.draw(); // Sky Visuals pass (same place, drawn over Shader Sky when both are on)
        tenacity.gfx.MusicVisFx.draw(); // Music Visualiser: additive over the sky, sky pixels only
        tenacity.gfx.WorldStyleFx.draw(); // restyle the finished world before our own 3D overlays

        Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.pushMatrix().mul(view);

        MatrixStack tenacityMatrices = new MatrixStack();
        tenacityMatrices.push();
        tiltViewWhenHurt(tenacityMatrices, camera.getLastTickProgress());
        if (client.options.getBobView().getValue()) {
            bobView(tenacityMatrices, camera.getLastTickProgress());
        }
        modelViewStack.mul(tenacityMatrices.peek().getPositionMatrix().invert(new Matrix4f()));
        tenacityMatrices.pop();

        WorldRenderEvent event = new WorldRenderEvent(matrixStack, tickDelta);
        tenacity.core.ModuleManager.renderWorld(event);
        Render3D.onWorldRender(event);

        modelViewStack.popMatrix();
    }
}

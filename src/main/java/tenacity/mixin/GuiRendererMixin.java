package tenacity.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.gfx.ItemAtlas;

/** The vanilla gui renderer picks the main framebuffer explicitly; while the item atlas renders it must use ours. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    /** The menu background shader goes into the main framebuffer before the gui, so widgets draw on top of it. */
    @Inject(method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("HEAD"))
    private void tenacity$menuBackground(com.mojang.blaze3d.buffers.GpuBufferSlice fog, CallbackInfo ci) {
        if (ItemAtlas.activeTarget() == null && MinecraftClient.getInstance().currentScreen instanceof tenacity.ui.NvgScreen)
            tenacity.gfx.MenuShader.draw();
    }

    @Redirect(method = "renderPreparedDraws", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;getFramebuffer()Lnet/minecraft/client/gl/Framebuffer;"))
    private Framebuffer tenacity$itemTarget(MinecraftClient client) {
        Framebuffer target = ItemAtlas.activeTarget();
        return target != null ? target : client.getFramebuffer();
    }

    @Redirect(method = "renderPreparedDraws", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/GameRenderer;renderBlur()V"))
    private void tenacity$itemBlur(GameRenderer renderer) {
        if (ItemAtlas.activeTarget() == null) renderer.renderBlur();
    }
}

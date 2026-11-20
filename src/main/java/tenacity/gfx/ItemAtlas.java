package tenacity.gfx;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.ItemGuiElementRenderState;
import net.minecraft.client.render.item.KeyedItemRenderState;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import org.joml.Matrix3x2f;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.nanovg.NanoVGGL3;
import tenacity.Tenacity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real item models for NanoVG. The vanilla gui item renderer draws every requested stack into an offscreen framebuffer
 * once per frame; that framebuffer is then used as a NanoVG image and cut into 16x16 cells.
 */
public final class ItemAtlas {
    private record Entry(ItemStack stack, int index) {
    }

    private static final Map<String, Entry> ITEMS = new LinkedHashMap<>();
    private static final GuiRenderState STATE = new GuiRenderState();
    private static SimpleFramebuffer target;
    private static GuiRenderer renderer;
    private static boolean drawing, failed;
    private static int image, columns;
    private static long context;

    private ItemAtlas() {
    }

    public static net.minecraft.client.gl.Framebuffer activeTarget() {
        return drawing ? target : null;
    }

    /** Requests the stack for the next frame and draws it if it is already in the atlas. */
    public static void draw(ItemStack stack, float x, float y, float side) {
        if (stack == null || stack.isEmpty() || failed) return;
        String key = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()) + ":" + stack.getComponents().hashCode();
        Entry entry = ITEMS.get(key);
        if (entry == null) {
            if (ITEMS.size() >= 64) ITEMS.clear();
            entry = new Entry(stack.copy(), ITEMS.size());
            ITEMS.put(key, entry);
        }
        if (target == null || image == 0 || columns == 0) return;
        var w = MinecraftClient.getInstance().getWindow();
        float scale = (float) w.getFramebufferWidth() / w.getScaledWidth();
        float u = (2 + (entry.index % columns) * 20) * scale, v = (2 + (entry.index / columns) * 20) * scale;
        Nvg.imageRegion(image, target.textureWidth, target.textureHeight, u, v, 16 * scale, 16 * scale, x, y, side, side, 0, 1);
    }

    public static void render() {
        if (failed) return;
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || ITEMS.isEmpty()) return;
        try {
            var w = mc.getWindow();
            if (target == null || target.textureWidth != w.getFramebufferWidth() || target.textureHeight != w.getFramebufferHeight()) {
                closeGpu();
                target = new SimpleFramebuffer("Tenacity item atlas", w.getFramebufferWidth(), w.getFramebufferHeight(), true);
            }
            if (renderer == null)
                renderer = new GuiRenderer(STATE, mc.getBufferBuilders().getEntityVertexConsumers(), mc.gameRenderer.getEntityRenderCommandQueue(), mc.gameRenderer.getEntityRenderDispatcher(), List.of());
            columns = Math.max(1, (w.getScaledWidth() - 4) / 20);
            for (var entry : ITEMS.values()) {
                KeyedItemRenderState state = new KeyedItemRenderState();
                mc.getItemModelManager().clearAndUpdate(state, entry.stack, ItemDisplayContext.GUI, mc.world, mc.player, entry.index);
                if (!state.isEmpty())
                    STATE.addItem(new ItemGuiElementRenderState(entry.stack.getItem().getName().toString(), new Matrix3x2f(), state, 2 + (entry.index % columns) * 20, 2 + (entry.index / columns) * 20, null));
            }
            var oldColor = RenderSystem.outputColorTextureOverride;
            var oldDepth = RenderSystem.outputDepthTextureOverride;
            var oldProjection = RenderSystem.getProjectionMatrixBuffer();
            var oldType = RenderSystem.getProjectionType();
            try {
                RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorAttachment(), 0, target.getDepthAttachment(), 1);
                RenderSystem.outputColorTextureOverride = target.getColorAttachmentView();
                RenderSystem.outputDepthTextureOverride = target.getDepthAttachmentView();
                drawing = true;
                renderer.render(RenderSystem.getShaderFog());
                renderer.incrementFrame();
            } finally {
                drawing = false;
                RenderSystem.outputColorTextureOverride = oldColor;
                RenderSystem.outputDepthTextureOverride = oldDepth;
                RenderSystem.setProjectionMatrix(oldProjection, oldType);
            }
            if (Nvg.ctx != 0 && (image == 0 || context != Nvg.ctx) && target.getColorAttachment() instanceof GlTexture texture) {
                image = NanoVGGL3.nvglCreateImageFromHandle(Nvg.ctx, texture.getGlId(), target.textureWidth, target.textureHeight, NanoVGGL3.NVG_IMAGE_NODELETE | NanoVG.NVG_IMAGE_FLIPY);
                context = Nvg.ctx;
            }
        } catch (Throwable t) {
            failed = true;
            drawing = false;
            Tenacity.LOG.error("item atlas failed - inventory icons disabled", t);
        }
    }

    private static void closeGpu() {
        if (image != 0 && context == Nvg.ctx && Nvg.ctx != 0) NanoVG.nvgDeleteImage(Nvg.ctx, image);
        image = 0;
        if (target != null) target.delete();
        target = null;
    }

    public static void close() {
        closeGpu();
        if (renderer != null) renderer.close();
        renderer = null;
        ITEMS.clear();
        columns = 0;
    }
}

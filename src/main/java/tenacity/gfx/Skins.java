package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;
import com.mojang.blaze3d.textures.GpuTexture;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** Draws the face of a Minecraft skin (with the hat layer) inside a NanoVG frame. */
public final class Skins {
    private static final Map<String, Integer> IMAGES = new HashMap<>();
    private Skins() {
    }

    /** Returns false if the skin texture isn't available (caller should draw a placeholder). */
    public static boolean head(SkinTextures skin, float x, float y, float size, float radius) {
        if (skin == null || skin.body() == null) return false;
        try {
            Identifier id = skin.body().texturePath();
            AbstractTexture t = MinecraftClient.getInstance().getTextureManager().getTexture(id);
            if (t == null) return false;
            GpuTexture g = t.getGlTexture();
            if (!(g instanceof GlTexture gl) || gl.isClosed()) return false;
            int w = g.getWidth(0), h = g.getHeight(0);
            String key = id + ":" + gl.getGlId() + ":" + w + ":" + h;
            Integer cached = IMAGES.get(key);
            int img;
            if (cached != null) img = cached;
            else {
                img = copy(gl.getGlId(), w, h);
                if (img > 0) IMAGES.put(key, img);
            }
            if (img <= 0) return false;
            float sc = w / 64f; // hd skins keep the same layout, just scaled
            Nvg.imageRegion(img, w, h, 8 * sc, 8 * sc, 8 * sc, 8 * sc, x, y, size, size, radius, 1f);
            Nvg.imageRegion(img, w, h, 40 * sc, 8 * sc, 8 * sc, 8 * sc, x, y, size, size, radius, 1f);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Own the NanoVG texture instead of changing/wrapping Minecraft's live texture state. */
    private static int copy(int texture, int width, int height) {
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int align = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int row = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            return Nvg.rgbaImageNearest(width, height, pixels);
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, align);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, row);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            MemoryUtil.memFree(pixels);
        }
    }
}

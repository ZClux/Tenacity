package tenacity.mixin;

import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WorldRenderer.class)
public interface WorldRendererAccessor {
    /** The persistent outline target (getEntityOutlinesFramebuffer only works while the frame graph runs). */
    @Accessor("entityOutlineFramebuffer")
    Framebuffer tenacity$outline();
}

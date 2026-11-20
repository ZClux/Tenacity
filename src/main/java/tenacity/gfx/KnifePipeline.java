package tenacity.gfx;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.util.function.Function;

/** Textured, opaque, depth tested layer of the knife meshes; the light is baked into the vertex colours. */
final class KnifePipeline {
    private KnifePipeline() {
    }

    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.POSITION_TEX_COLOR_SNIPPET)
                    .withLocation(Identifier.of("tenacity", "knife_textured"))
                    .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
                    .withCull(false)
                    .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
                    .withDepthWrite(true)
                    .withSampler("Sampler0")
                    .build());

    private static final Function<Identifier, RenderLayer> LAYERS = Util.memoize(texture -> RenderLayer.of("knife_textured",
            RenderSetup.builder(PIPELINE).texture("Sampler0", texture).expectedBufferSize(1 << 20).build()));

    static RenderLayer layer(Identifier texture) {
        return LAYERS.apply(texture);
    }
}

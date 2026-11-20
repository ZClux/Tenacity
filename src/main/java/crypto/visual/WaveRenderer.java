package crypto.visual;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import com.mojang.blaze3d.vertex.VertexFormat.DrawMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.*;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.*;

/** Original shockwave's ping-pong composition: never sample a render target while writing it. */
public final class WaveRenderer {
    public record Ring(float x,float y,float z,float radius,float width,float strength,int color,float alpha){}
    private static final RenderPipeline PIPE=RenderPipelines.register(RenderPipeline.builder()
        .withLocation(Identifier.of("crypto","jump_wave")).withVertexShader(Identifier.of("crypto","core/hand_fullscreen"))
        .withFragmentShader(Identifier.of("crypto","core/jump_wave")).withVertexFormat(VertexFormats.EMPTY,DrawMode.TRIANGLES)
        .withUniform("WaveData",UniformType.UNIFORM_BUFFER).withSampler("Sampler0").withSampler("Sampler1")
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false).withCull(false).build());
    private final GpuTexture[] textures=new GpuTexture[2];private final GpuTextureView[] views=new GpuTextureView[2];
    private GpuBuffer uniform,dummy;private ByteBuffer bytes;private int width,height;
    private void init(int w,int h){if(textures[0]!=null&&width==w&&height==h)return;close();width=w;height=h;
        for(int i=0;i<2;i++){textures[i]=RenderSystem.getDevice().createTexture(()->"crypto:jump_wave",13|GpuTexture.USAGE_COPY_SRC,TextureFormat.RGBA8,w,h,1,1);views[i]=RenderSystem.getDevice().createTextureView(textures[i]);}
        bytes=MemoryUtil.memAlloc(112);uniform=RenderSystem.getDevice().createBuffer(()->"crypto:wave_data",136,112);
        ByteBuffer v=MemoryUtil.memCalloc(4);try{dummy=RenderSystem.getDevice().createBuffer(()->"crypto:wave_vertex",32,v);}finally{MemoryUtil.memFree(v);}
    }
    public void render(Matrix4f inverse,List<Ring> rings){if(rings.isEmpty())return;var fb=MinecraftClient.getInstance().getFramebuffer();if(fb.getDepthAttachmentView()==null)return;init(fb.textureWidth,fb.textureHeight);
        GpuTextureView source=fb.getColorAttachmentView();int index=0;
        for(Ring ring:rings){bytes.clear();inverse.get(0,bytes);bytes.position(64);bytes.putFloat(ring.x).putFloat(ring.y).putFloat(ring.z).putFloat(ring.radius);
            bytes.putFloat(ring.width).putFloat(ring.strength).putFloat(0).putFloat(0);
            bytes.putFloat((ring.color>>16&255)/255f).putFloat((ring.color>>8&255)/255f).putFloat((ring.color&255)/255f).putFloat((ring.color>>>24)/255f*ring.alpha).flip();
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(uniform.slice(),bytes);
            try(var pass=RenderSystem.getDevice().createCommandEncoder().createRenderPass(()->"crypto:jump_shockwave",views[index],OptionalInt.empty())){pass.setPipeline(PIPE);pass.setVertexBuffer(0,dummy);pass.setUniform("WaveData",uniform);pass.bindTexture("Sampler0",source,RenderSystem.getSamplerCache().get(FilterMode.LINEAR));pass.bindTexture("Sampler1",fb.getDepthAttachmentView(),RenderSystem.getSamplerCache().get(FilterMode.NEAREST));pass.draw(0,6);}
            source=views[index];index^=1;
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(textures[index^1],fb.getColorAttachment(),0,0,0,0,0,width,height);
    }
    public void close(){for(int i=0;i<2;i++){if(views[i]!=null)views[i].close();if(textures[i]!=null)textures[i].close();views[i]=null;textures[i]=null;}if(uniform!=null)uniform.close();if(dummy!=null)dummy.close();uniform=dummy=null;if(bytes!=null)MemoryUtil.memFree(bytes);bytes=null;}
}

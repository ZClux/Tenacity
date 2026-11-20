package tenacity.gfx;

import org.lwjgl.opengl.*;

/**
 * Snapshot of the raw OpenGL state NanoVG touches. Blaze3D caches GL state, so anything we change
 * has to be put back exactly or vanilla rendering corrupts on the next frame.
 */
final class GlState {
    private int program, vao, arrayBuf, uboGeneric, uboIdx0, drawFbo, readFbo;
    private long uboStart, uboSize;
    private int activeTex, tex2d, sampler0;
    private boolean blend, cull, depth, scissor, stencil;
    private final int[] viewport = new int[4];
    private final int[] scissorBox = new int[4];
    private int srcRgb, dstRgb, srcA, dstA, eqRgb, eqA;
    private int cullFace, frontFace;
    private final boolean[] colorMask = new boolean[4];
    private boolean depthMask;
    private int depthFunc;
    private int stencilMask, stencilFunc, stencilRef, stencilFuncMask, stFail, stDepthFail, stPass;
    private int unpackAlign, unpackRow, unpackSkipPx, unpackSkipRows;
    private boolean framebufferSrgb, multisample;
    private final int[] unitTex = new int[4], unitSampler = new int[4];

    void save() {
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        arrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        uboGeneric = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING);
        uboIdx0 = GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, 0);
        uboStart = GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_START, 0);
        uboSize = GL32.glGetInteger64i(GL31.GL_UNIFORM_BUFFER_SIZE, 0);
        drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        activeTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        tex2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        sampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        for (int u = 1; u < 4; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            unitTex[u] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            unitSampler[u] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        GL13.glActiveTexture(activeTex);

        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST);
        framebufferSrgb = GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB);
        multisample = GL11.glIsEnabled(GL13.GL_MULTISAMPLE);

        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);

        srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        eqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
        eqA = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);

        cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);

        byte[] cm = new byte[4];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, bb);
        for (int i = 0; i < 4; i++) colorMask[i] = bb.get(i) != 0;
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);

        stencilMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        stencilFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        stencilRef = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        stencilFuncMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
        stFail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
        stDepthFail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
        stPass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);

        unpackAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        unpackRow = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        unpackSkipPx = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        unpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
    }

    /** Put GL into a known state for NanoVG on the default framebuffer. */
    void prepare(int fbW, int fbH) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL33.glBindSampler(0, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
    }

    void restore() {
        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuf);
        if (uboIdx0 != 0) GL30.glBindBufferRange(GL31.GL_UNIFORM_BUFFER, 0, uboIdx0, uboStart, uboSize);
        else GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 0, 0);
        GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uboGeneric);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex2d);
        GL33.glBindSampler(0, sampler0);
        for (int u = 1; u < 4; u++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + u);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, unitTex[u]);
            GL33.glBindSampler(u, unitSampler[u]);
        }
        GL13.glActiveTexture(activeTex);

        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);

        set(GL11.GL_BLEND, blend);
        set(GL11.GL_CULL_FACE, cull);
        set(GL11.GL_DEPTH_TEST, depth);
        set(GL11.GL_SCISSOR_TEST, scissor);
        set(GL11.GL_STENCIL_TEST, stencil);
        set(GL30.GL_FRAMEBUFFER_SRGB, framebufferSrgb);
        set(GL13.GL_MULTISAMPLE, multisample);

        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);

        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcA, dstA);
        GL20.glBlendEquationSeparate(eqRgb, eqA);
        GL11.glCullFace(cullFace);
        GL11.glFrontFace(frontFace);
        GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        GL11.glDepthMask(depthMask);
        GL11.glDepthFunc(depthFunc);

        GL11.glStencilMask(stencilMask);
        GL11.glStencilFunc(stencilFunc, stencilRef, stencilFuncMask);
        GL11.glStencilOp(stFail, stDepthFail, stPass);

        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlign);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, unpackRow);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, unpackSkipPx);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, unpackSkipRows);
    }

    private static void set(int cap, boolean on) {
        if (on) GL11.glEnable(cap);
        else GL11.glDisable(cap);
    }
}

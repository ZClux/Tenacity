package tenacity.gfx;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

/** A reusable dual Kawase blur chain (half resolution and down). Caller handles GL state save / restore. */
final class KawaseChain {
    private static final int MAX = 5;
    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String DOWN = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uTex;
            uniform vec2 uHalf;
            void main() {
                vec4 s = texture(uTex, vUv) * 4.0;
                s += texture(uTex, vUv - uHalf);
                s += texture(uTex, vUv + uHalf);
                s += texture(uTex, vUv + vec2(uHalf.x, -uHalf.y));
                s += texture(uTex, vUv - vec2(uHalf.x, -uHalf.y));
                fragColor = s * 0.125;
            }
            """;
    private static final String UP = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uTex;
            uniform vec2 uHalf;
            void main() {
                vec2 o = uHalf;
                vec4 s = texture(uTex, vUv + vec2(-o.x * 2.0, 0.0));
                s += texture(uTex, vUv + vec2(-o.x, o.y)) * 2.0;
                s += texture(uTex, vUv + vec2(0.0, o.y * 2.0));
                s += texture(uTex, vUv + vec2(o.x, o.y)) * 2.0;
                s += texture(uTex, vUv + vec2(o.x * 2.0, 0.0));
                s += texture(uTex, vUv + vec2(o.x, -o.y)) * 2.0;
                s += texture(uTex, vUv + vec2(0.0, -o.y * 2.0));
                s += texture(uTex, vUv + vec2(-o.x, -o.y)) * 2.0;
                fragColor = s / 12.0;
            }
            """;

    private int progDown, progUp, vao;
    private final int[] tex = new int[MAX], fbo = new int[MAX], lw = new int[MAX], lh = new int[MAX];
    private int w, h;

    boolean init() {
        if (progDown != 0) return true;
        progDown = Glass.program(VERT, DOWN);
        progUp = Glass.program(VERT, UP);
        vao = GL30.glGenVertexArrays();
        return progDown != 0 && progUp != 0;
    }

    private void ensure(int sw, int sh) {
        if (sw == w && sh == h && tex[0] != 0) return;
        for (int i = 0; i < MAX; i++) {
            if (tex[i] != 0) GL11.glDeleteTextures(tex[i]);
            if (fbo[i] != 0) GL30.glDeleteFramebuffers(fbo[i]);
        }
        int cw = sw, ch = sh;
        for (int i = 0; i < MAX; i++) {
            cw = Math.max(1, cw / 2);
            ch = Math.max(1, ch / 2);
            lw[i] = cw;
            lh[i] = ch;
            tex[i] = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[i]);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, cw, ch, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
            fbo[i] = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[i]);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex[i], 0);
        }
        w = sw;
        h = sh;
    }

    /** Blurs {@code src}; returns the blurred texture (half resolution). Blend must be off and a VAO is bound here. */
    int blur(int src, int sw, int sh, int levels, float offset) {
        ensure(sw, sh);
        levels = Math.max(1, Math.min(MAX, levels));
        GL30.glBindVertexArray(vao);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUseProgram(progDown);
        GL20.glUniform1i(GL20.glGetUniformLocation(progDown, "uTex"), 0);
        int s = src, cw = sw, ch = sh;
        for (int i = 0; i < levels; i++) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[i]);
            GL11.glViewport(0, 0, lw[i], lh[i]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, s);
            GL20.glUniform2f(GL20.glGetUniformLocation(progDown, "uHalf"), offset * 0.5f / cw, offset * 0.5f / ch);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            s = tex[i];
            cw = lw[i];
            ch = lh[i];
        }
        GL20.glUseProgram(progUp);
        GL20.glUniform1i(GL20.glGetUniformLocation(progUp, "uTex"), 0);
        for (int i = levels - 2; i >= 0; i--) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo[i]);
            GL11.glViewport(0, 0, lw[i], lh[i]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[i + 1]);
            GL20.glUniform2f(GL20.glGetUniformLocation(progUp, "uHalf"), offset * 0.5f / lw[i + 1], offset * 0.5f / lh[i + 1]);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        }
        return tex[0];
    }
}

package tenacity.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.GlTexture;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import tenacity.Tenacity;

import java.nio.FloatBuffer;
import java.util.Map;

/**
 * GPU X-Ray skeletons. The bone mesh of every captured player is transformed by its model part matrices on the CPU, uploaded
 * once per frame and drawn into Minecraft's main framebuffer with its own depth buffer (bones hide each other correctly but
 * are visible through walls): first soft additive glow shells, then the bones with per pixel fresnel shading, a rim light and
 * a little specular.
 */
public final class XrayRenderer {
    /** Settings written by Skeleton ESP. */
    public static boolean enabled;
    public static int color = 0xAA8CFF;
    public static float glow = 1f, glowStrength = 1f;

    private static final String VERT = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in float aKind;
            uniform mat4 uViewProj;
            uniform float uGrow;
            out vec3 vPos;
            out vec3 vNormal;
            out float vKind;
            void main() {
                vec3 p = aPos + aNormal * uGrow * aKind;
                vPos = p;
                vNormal = aNormal;
                vKind = aKind;
                gl_Position = uViewProj * vec4(p, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 330 core
            in vec3 vPos;
            in vec3 vNormal;
            in float vKind;
            out vec4 fragColor;
            uniform vec3 uBase;
            uniform int uPass;          // 0 = glow shell, 1 = bones
            uniform float uShellAlpha;
            uniform float uTime;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 v = normalize(-vPos);
                float facing = abs(dot(n, v));
                if (uPass == 0) {
                    float soft = pow(facing, 1.5);   // shells fade towards their silhouette
                    fragColor = vec4(uBase * uShellAlpha * soft, 1.0);
                    return;
                }
                vec3 core = mix(uBase, vec3(1.0), 0.9);
                vec3 edge = mix(uBase, vec3(1.0), 0.35);
                vec3 dark = vec3(0.10, 0.086, 0.15);
                vec3 col;
                if (vKind < 0.5) { // dark parts (eye sockets, gaps)
                    col = mix(dark, mix(dark, uBase, 0.3), facing * 0.6);
                } else {
                    col = mix(edge, core, pow(facing, 0.75));
                    float rim = pow(1.0 - facing, 3.0);
                    col += uBase * rim * 0.9;                                   // coloured rim light
                    vec3 L = normalize(vec3(-0.4, 0.8, 0.45));
                    col += vec3(pow(max(dot(reflect(-L, n), v), 0.0), 24.0) * 0.35); // specular
                    col *= 0.92 + 0.08 * sin(uTime * 2.0 + vPos.y * 6.0);        // faint pulse
                }
                fragColor = vec4(col, 1.0);
            }
            """;

    private static final String COMP_VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /** Same glow as Hand Shaders: gaussian blur of the bone mask, the halo is blurred minus sharp. */
    private static final String COMP_FRAG = """
            #version 330 core
            in vec2 vUv;
            out vec4 fragColor;
            uniform sampler2D uBones;
            uniform vec2 uTexel;
            uniform float uRadius, uStrength;
            uniform vec3 uBase;
            void main() {
                vec4 b = texture(uBones, vUv);
                float a = b.a;
                float blurred = 0.0, wsum = 0.0;
                if (uStrength > 0.0) {
                    for (int r = 1; r <= 4; r++) {
                        float fr = float(r) / 4.0;
                        float w = exp(-fr * fr * 2.2);
                        for (int i = 0; i < 20; i++) {
                            float an = float(i) * 0.31415927 + fr * 1.3;
                            blurred += texture(uBones, vUv + vec2(cos(an), sin(an)) * uRadius * fr * uTexel).a * w;
                            wsum += w;
                        }
                    }
                    blurred = (blurred + a * 2.0) / (wsum + 2.0);
                }
                float g = clamp(max(0.0, blurred - a) * 3.0 * uStrength, 0.0, 1.0);
                vec3 rgb = b.rgb + uBase * g * (1.0 - a);
                fragColor = vec4(rgb, a + g * (1.0 - a));
            }
            """;

    private static final GlState GL = new GlState();
    private static int compProg, boneTex, boneFbo, boneW, boneH;
    private static int prog, vao, vbo, fbo, depthRb, depthW, depthH;
    private static boolean failed;
    private static BoneMesh[] meshes;
    private static FloatBuffer buf;
    private static final long START = System.nanoTime();

    private XrayRenderer() {
    }

    private static boolean setup() {
        if (failed) return false;
        if (prog != 0) return true;
        prog = Glass.program(VERT, FRAG);
        if (prog == 0) {
            failed = true;
            return false;
        }
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        fbo = GL30.glGenFramebuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 28, 0);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 28, 12);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, 28, 24);
        return true;
    }

    /** Builds the vertex data of all captured players (two triangles per quad). Returns the vertex count. */
    private static int build(Map<Integer, Matrix4f[]> captures) {
        if (meshes == null) meshes = BoneMesh.skeleton();
        int quads = 0;
        for (Matrix4f[] parts : captures.values())
            for (int i = 0; i < meshes.length; i++) if (parts[i] != null) quads += meshes[i].quads;
        int floats = quads * 6 * 7;
        if (buf == null || buf.capacity() < floats) {
            if (buf != null) MemoryUtil.memFree(buf);
            buf = MemoryUtil.memAllocFloat(Math.max(floats, 4096));
        }
        buf.clear();
        int[] tri = {0, 1, 2, 0, 2, 3};
        float[] pos = new float[12], nor = new float[12];
        for (Matrix4f[] parts : captures.values()) {
            Matrix4f root = parts[1] != null ? parts[1] : parts[0];
            if (root == null || root.m30() * root.m30() + root.m31() * root.m31() + root.m32() * root.m32() > 9216f) continue;
            for (int ix = 0; ix < meshes.length; ix++) {
                Matrix4f m = parts[ix];
                if (m == null) continue;
                BoneMesh mesh = meshes[ix];
                float sc = (float) Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
                for (int k = 0; k < mesh.quads; k++) {
                    for (int v = 0; v < 4; v++) {
                        int o = k * 12 + v * 3;
                        float x = mesh.positions[o], y = mesh.positions[o + 1], z = mesh.positions[o + 2];
                        pos[v * 3] = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
                        pos[v * 3 + 1] = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
                        pos[v * 3 + 2] = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
                        float nx = mesh.normals[o], ny = mesh.normals[o + 1], nz = mesh.normals[o + 2];
                        float tx = m.m00() * nx + m.m10() * ny + m.m20() * nz, ty = m.m01() * nx + m.m11() * ny + m.m21() * nz, tz = m.m02() * nx + m.m12() * ny + m.m22() * nz;
                        float l = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
                        if (l < 1e-6f) l = 1f;
                        // the normal carries the part scale so the shell growth follows the model size
                        nor[v * 3] = tx / l * sc;
                        nor[v * 3 + 1] = ty / l * sc;
                        nor[v * 3 + 2] = tz / l * sc;
                    }
                    float kind = mesh.kinds[k] == 1 ? 0f : 1f; // 1 = solid bone (grows into shells), 0 = dark part
                    for (int t : tri) buf.put(pos, t * 3, 3).put(nor, t * 3, 3).put(kind);
                }
            }
        }
        buf.flip();
        return buf.remaining() / 7;
    }

    /** Draws all captured skeletons into the main framebuffer. Call before the hand is rendered. */
    public static void draw(Map<Integer, Matrix4f[]> captures) {
        if (!enabled || captures.isEmpty() || !setup() || !tenacity.util.Projection.ready()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!(mc.getFramebuffer().getColorAttachment() instanceof GlTexture target) || target.isClosed()) return;
        int w = target.getWidth(0), h = target.getHeight(0);
        GL.save();
        try {
            int count = build(captures);
            if (count == 0) return;
            if (depthRb == 0 || depthW != w || depthH != h) {
                if (depthRb != 0) GL30.glDeleteRenderbuffers(depthRb);
                depthRb = GL30.glGenRenderbuffers();
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRb);
                GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, w, h);
                depthW = w;
                depthH = h;
            }
            if (compProg == 0) compProg = Glass.program(COMP_VERT, COMP_FRAG);
            if (boneTex == 0 || boneW != w || boneH != h) {
                if (boneTex != 0) GL11.glDeleteTextures(boneTex);
                boneTex = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, boneTex);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL13.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL13.GL_CLAMP_TO_EDGE);
                if (boneFbo == 0) boneFbo = GL30.glGenFramebuffers();
                boneW = w;
                boneH = h;
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, boneFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, boneTex, 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depthRb);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            GL11.glViewport(0, 0, w, h);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STREAM_DRAW);
            GL20.glUseProgram(prog);
            float[] m = new float[16];
            tenacity.util.Projection.viewProj().get(m);
            GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(prog, "uViewProj"), false, m);
            int c = XrayRenderer.color;
            GL20.glUniform3f(GL20.glGetUniformLocation(prog, "uBase"), ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
            GL20.glUniform1f(GL20.glGetUniformLocation(prog, "uTime"), (System.nanoTime() - START) / 1e9f);

            // bones: opaque with depth among themselves
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL20.glUniform1i(GL20.glGetUniformLocation(prog, "uPass"), 1);
            GL20.glUniform1f(GL20.glGetUniformLocation(prog, "uGrow"), 0f);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count);

            // composite the bones with their glow onto the scene
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target.getGlId(), 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, 0);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            org.lwjgl.opengl.GL33.glBindSampler(0, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, boneTex);
            GL20.glUseProgram(compProg);
            GL20.glUniform1i(GL20.glGetUniformLocation(compProg, "uBones"), 0);
            GL20.glUniform2f(GL20.glGetUniformLocation(compProg, "uTexel"), 1f / w, 1f / h);
            GL20.glUniform1f(GL20.glGetUniformLocation(compProg, "uRadius"), 18f * glow * h / 1080f);
            GL20.glUniform1f(GL20.glGetUniformLocation(compProg, "uStrength"), glow > 0 ? glowStrength : 0f);
            GL20.glUniform3f(GL20.glGetUniformLocation(compProg, "uBase"), ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } catch (Throwable t) {
            failed = true;
            Tenacity.LOG.error("x-ray renderer failed - disabled", t);
        } finally {
            try { GL.restore(); } catch (Throwable ignored) { }
        }
    }
}

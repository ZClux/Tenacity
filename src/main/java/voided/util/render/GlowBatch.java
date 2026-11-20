package voided.util.render;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import voided.util.render.сliemtpipeline.ClientPipelines;

/**
 * Additive glow quads queued by {@link GlowLine} during the frame and drawn by {@link Render3D} with the other world geometry. The
 * vertices are kept in flat arrays (no objects per vertex) because a glow ribbon is dozens of quads per line.
 */
public final class GlowBatch {

    private static final class Buf {
        double[] pos = new double[3 * 4096];
        int[] col = new int[4096];
        int n;

        void add(double x, double y, double z, int color) {
            if (n == col.length) {
                pos = java.util.Arrays.copyOf(pos, pos.length * 2);
                col = java.util.Arrays.copyOf(col, col.length * 2);
            }
            int i = n * 3;
            pos[i] = x;
            pos[i + 1] = y;
            pos[i + 2] = z;
            col[n++] = color;
        }
    }

    private static final Buf FLAT = new Buf();
    private static final Buf DEPTH = new Buf();

    private GlowBatch() {
    }

    /** Four vertices of one quad; {@code depth} makes blocks in front hide it. */
    public static void quad(boolean depth, double x1, double y1, double z1, int c1, double x2, double y2, double z2, int c2,
                            double x3, double y3, double z3, int c3, double x4, double y4, double z4, int c4) {
        Buf b = depth ? DEPTH : FLAT;
        b.add(x1, y1, z1, c1);
        b.add(x2, y2, z2, c2);
        b.add(x3, y3, z3, c3);
        b.add(x4, y4, z4, c4);
    }

    static void flush(MatrixStack matrices, VertexConsumerProvider.Immediate immediate, Vec3d camera) {
        if (FLAT.n == 0 && DEPTH.n == 0) return;
        if (FLAT.n > 0) {
            write(matrices, immediate.getBuffer(ClientPipelines.ESP_GLOW_QUADS), FLAT, camera);
            immediate.draw();
        }
        if (DEPTH.n > 0) {
            write(matrices, immediate.getBuffer(ClientPipelines.WORLD_PARTICLES_QUADS), DEPTH, camera);
            immediate.draw();
        }
    }

    private static void write(MatrixStack matrices, VertexConsumer buffer, Buf b, Vec3d camera) {
        MatrixStack.Entry entry = matrices.peek();
        for (int i = 0; i < b.n; i++) {
            int p = i * 3;
            buffer.vertex(entry, (float) (b.pos[p] - camera.x), (float) (b.pos[p + 1] - camera.y), (float) (b.pos[p + 2] - camera.z)).color(b.col[i]);
        }
        b.n = 0;
    }
}

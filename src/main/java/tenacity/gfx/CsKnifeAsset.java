package tenacity.gfx;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A knife of the CKM format (Cake Client's converted CS2 exports): the meshes, one rigid part per bone, and the clips (idle, inspect, deploy,
 * slashes, stab ...) as one 4x4 matrix per part and frame, followed by the matrices of the two hands. Parsing follows Cake Client's
 * CsKnifeAsset (MIT, Copyright (c) 2026 Cake Client contributors); the vertices are kept as plain float arrays (x, y, z, u, v, nx, ny, nz).
 */
final class CsKnifeAsset {

    record Part(int joint, int count, float[] data) {
    }

    static final class Clip {
        final float duration, holdStart, holdEnd;
        final int frames;
        final String name;
        private final float[] data;
        private final int stride, parts, handFloats;
        private final boolean fullHand;

        Clip(String name, float duration, int frames, int parts, float[] data, int handFloats, float holdStart, float holdEnd) {
            this.name = name;
            this.duration = duration;
            this.frames = frames;
            this.parts = parts;
            this.data = data;
            this.handFloats = handFloats;
            this.holdStart = holdStart;
            this.holdEnd = holdEnd;
            this.fullHand = handFloats >= 16;
            this.stride = parts * 16 + handFloats;
        }

        /** The matrix of a part (or of a hand: index {@code parts} is the main hand, {@code parts + 1} the support hand) at a time. */
        Matrix4f matrix(int part, double seconds) {
            double frame = Math.clamp(seconds / duration, 0, 1) * (frames - 1);
            int a = (int) frame, b = Math.min(frames - 1, a + 1);
            float t = (float) (frame - a);
            Matrix4f first = new Matrix4f().set(Arrays.copyOfRange(data, a * stride + part * 16, a * stride + part * 16 + 16));
            if (a == b || t == 0) return first;
            Matrix4f second = new Matrix4f().set(Arrays.copyOfRange(data, b * stride + part * 16, b * stride + part * 16 + 16));
            // positions blend linearly and rotations by slerp, so a moving part stays rigid
            Vector3f position = first.getTranslation(new Vector3f()).lerp(second.getTranslation(new Vector3f()), t);
            Quaternionf rotation = first.getNormalizedRotation(new Quaternionf()).slerp(second.getNormalizedRotation(new Quaternionf()), t);
            return new Matrix4f().translationRotate(position.x, position.y, position.z, rotation.x, rotation.y, rotation.z, rotation.w);
        }

        Matrix4f handMatrix(double seconds) {
            return fullHand ? matrix(parts, seconds) : new Matrix4f().translation(handPosition(seconds));
        }

        boolean hasLeftArm() {
            return handFloats == 32;
        }

        Matrix4f leftArmMatrix(double seconds) {
            return hasLeftArm() ? matrix(parts + 1, seconds) : new Matrix4f();
        }

        private Vector3f handPosition(double seconds) {
            double frame = Math.clamp(seconds / duration, 0, 1) * (frames - 1);
            int a = (int) frame, b = Math.min(frames - 1, a + 1), x = a * stride + parts * 16, y = b * stride + parts * 16;
            return new Vector3f(data[x], data[x + 1], data[x + 2]).lerp(new Vector3f(data[y], data[y + 1], data[y + 2]), (float) (frame - a));
        }
    }

    final List<Part> parts;
    final Clip[] clips;

    private CsKnifeAsset(List<Part> parts, Clip[] clips) {
        this.parts = List.copyOf(parts);
        this.clips = clips;
    }

    int find(String name) {
        for (int i = 0; i < clips.length; i++) if (clips[i].name.equals(name)) return i;
        return -1;
    }

    static CsKnifeAsset parse(byte[] bytes) throws IOException {
        try {
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int magic = b.getInt();
            boolean v7 = magic == 0x374d4b43, v6 = magic == 0x364d4b43 || v7, full = magic == 0x354d4b43 || v6;
            int hands = v6 ? 32 : full ? 16 : 3;
            if (magic != 0x344d4b43 && !full) throw new IOException("Invalid knife mesh");
            int n = b.getInt();
            if (n < 1 || n > 32) throw new IOException("Invalid mesh parts");
            List<Part> parts = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int joint = b.getInt(), count = b.getInt();
                if (count < 3 || count > 1_000_000) throw new IOException("Invalid vertices");
                // 28 bytes per vertex: position, uv, an rgba colour, a signed byte normal and a pad byte
                float[] data = new float[count * 8];
                for (int v = 0; v < count; v++) {
                    int o = v * 8;
                    for (int k = 0; k < 5; k++) data[o + k] = b.getFloat();
                    b.getInt();
                    for (int k = 0; k < 3; k++) data[o + 5 + k] = b.get() / 127f;
                    b.get();
                }
                parts.add(new Part(joint, count, data));
            }
            Clip[] clips = new Clip[b.getInt()];
            for (int i = 0; i < clips.length; i++) {
                String label;
                if (v6) {
                    int length = Short.toUnsignedInt(b.getShort());
                    byte[] raw = new byte[length];
                    b.get(raw);
                    label = new String(raw, StandardCharsets.UTF_8);
                } else {
                    label = new String[]{"idle", "inspect", "deploy", "slash1", "slash2", "stab"}[Math.min(i, 5)];
                }
                float d = b.getFloat(), holdStart = v7 ? b.getFloat() : -1f, holdEnd = v7 ? b.getFloat() : -1f;
                int frames = b.getInt();
                if (d <= 0 || frames < 1 || frames > 2000) throw new IOException("Invalid clip");
                float[] data = new float[frames * (n * 16 + hands)];
                for (int k = 0; k < data.length; k++) data[k] = b.getFloat();
                clips[i] = new Clip(label, d, frames, n, data, hands, holdStart, holdEnd);
            }
            if (b.hasRemaining()) throw new IOException("Unexpected knife data");
            return new CsKnifeAsset(parts, clips);
        } catch (BufferUnderflowException e) {
            throw new IOException("Truncated knife asset", e);
        }
    }
}

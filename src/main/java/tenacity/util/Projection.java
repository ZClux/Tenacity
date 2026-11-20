package tenacity.util;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import tenacity.gfx.Nvg;

/** World to screen projection in logical NanoVG coordinates, using the matrices the world was actually rendered with. */
public final class Projection {
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static final Vector4f V = new Vector4f();
    private static Vec3d cam = Vec3d.ZERO;
    private static boolean ready;

    private Projection() {
    }

    public static void capture(Matrix4f view, Matrix4f proj, Vec3d cameraPos) {
        VIEW_PROJ.set(proj).mul(view);
        cam = cameraPos;
        ready = true;
    }

    public static Matrix4f viewProj() {
        return new Matrix4f(VIEW_PROJ);
    }

    public static boolean ready() {
        return ready;
    }

    /** Inverse of the camera-relative view projection (for reconstructing view rays in shaders). */
    public static Matrix4f inverseViewProj() {
        return new Matrix4f(VIEW_PROJ).invert();
    }

    public static Vec3d camera() {
        return cam;
    }

    /** Projects a point; returns false when it is behind the camera. out = {x, y}. */
    public static boolean point(Vec3d p, float[] out) {
        if (!ready) return false;
        V.set((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), 1f);
        VIEW_PROJ.transform(V);
        if (V.w <= 0.05f) return false;
        float nx = V.x / V.w, ny = V.y / V.w;
        out[0] = (nx * 0.5f + 0.5f) * Nvg.w;
        out[1] = (0.5f - ny * 0.5f) * Nvg.h;
        return true;
    }

    /**
     * Projects a world segment with the camera near plane clipped, so lines stay smooth even when one end is behind you:
     * the line keeps pointing exactly where the hidden end is instead of popping. Clip space is a linear transform of
     * world space, so interpolating there is exact.
     */
    public static boolean segment(Vec3d a, Vec3d b, float[] oa, float[] ob) {
        if (!ready) return false;
        V.set((float) (a.x - cam.x), (float) (a.y - cam.y), (float) (a.z - cam.z), 1f);
        VIEW_PROJ.transform(V);
        float ax = V.x, ay = V.y, aw = V.w;
        V.set((float) (b.x - cam.x), (float) (b.y - cam.y), (float) (b.z - cam.z), 1f);
        VIEW_PROJ.transform(V);
        float bx = V.x, by = V.y, bw = V.w;
        final float EPS = 0.05f;
        if (aw <= EPS && bw <= EPS) return false;
        if (aw < EPS) {
            float t = (EPS - aw) / (bw - aw);
            ax += (bx - ax) * t;
            ay += (by - ay) * t;
            aw = EPS;
        } else if (bw < EPS) {
            float t = (EPS - bw) / (aw - bw);
            bx += (ax - bx) * t;
            by += (ay - by) * t;
            bw = EPS;
        }
        oa[0] = (ax / aw * 0.5f + 0.5f) * Nvg.w;
        oa[1] = (0.5f - ay / aw * 0.5f) * Nvg.h;
        ob[0] = (bx / bw * 0.5f + 0.5f) * Nvg.w;
        ob[1] = (0.5f - by / bw * 0.5f) * Nvg.h;
        return true;
    }

    /**
     * Like {@link #point} but never fails: a point behind the camera (or off screen) is pushed out to the screen edge in its
     * direction. Returns true when the point really is on screen.
     */
    public static boolean pointOrEdge(Vec3d p, float[] out) {
        if (!ready) return false;
        V.set((float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z), 1f);
        VIEW_PROJ.transform(V);
        float x = V.x, y = V.y, w = V.w;
        if (w > 0.05f) {
            float nx = x / w, ny = y / w;
            if (Math.abs(nx) <= 1f && Math.abs(ny) <= 1f) {
                out[0] = (nx * 0.5f + 0.5f) * Nvg.w;
                out[1] = (0.5f - ny * 0.5f) * Nvg.h;
                return true;
            }
        } else {
            x = -x; // behind: the direction flips
            y = -y;
        }
        float len = (float) Math.hypot(x, y);
        if (len < 1e-5f) {
            x = 0;
            y = -1;
            len = 1;
        }
        float dx = x / len, dy = -y / len; // screen y points down
        float hw = Nvg.w / 2f, hh = Nvg.h / 2f;
        float k = Math.min(hw / Math.max(Math.abs(dx), 1e-4f), hh / Math.max(Math.abs(dy), 1e-4f));
        out[0] = hw + dx * k;
        out[1] = hh + dy * k;
        return false;
    }

    /** Screen rectangle {minX, minY, maxX, maxY} of a world box, or null when it is not visible. */
    public static float[] box(Box b) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float[] o = new float[2];
        int seen = 0;
        for (int i = 0; i < 8; i++) {
            Vec3d corner = new Vec3d((i & 1) == 0 ? b.minX : b.maxX, (i & 2) == 0 ? b.minY : b.maxY, (i & 4) == 0 ? b.minZ : b.maxZ);
            if (!point(corner, o)) continue;
            seen++;
            minX = Math.min(minX, o[0]);
            minY = Math.min(minY, o[1]);
            maxX = Math.max(maxX, o[0]);
            maxY = Math.max(maxY, o[1]);
        }
        if (seen < 8) return null; // partly behind the camera: the rectangle would be wrong
        if (maxX < -50 || maxY < -50 || minX > Nvg.w + 50 || minY > Nvg.h + 50) return null;
        return new float[]{minX, minY, maxX, maxY};
    }
}

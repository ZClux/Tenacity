package tenacity.ui;

/** Animation helpers. */
public final class Anim {
    private Anim() {
    }

    /** Frame time in seconds, set by the overlay each frame. */
    public static float dt = 0.016f;

    /** Exponential approach, framerate independent. speed ~ 10 is snappy, ~ 5 is lazy. */
    public static float approach(float cur, float target, float speed) {
        float k = 1f - (float) Math.exp(-speed * dt);
        float v = cur + (target - cur) * k;
        return Math.abs(v - target) < 0.0005f ? target : v;
    }

    public static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    public static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    public static float easeOutBack(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float c1 = 1.4f, c3 = c1 + 1f;
        return 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
    }
}

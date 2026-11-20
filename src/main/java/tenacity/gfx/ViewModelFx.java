package tenacity.gfx;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

/** First person view model offsets and swing animations (settings written by the View Model / Swing Animations modules). */
public final class ViewModelFx {
    /* view model */
    public static boolean viewModel;
    /** Per hand: index 0 = right hand, 1 = left hand. */
    public static final float[] x = new float[2], y = new float[2], z = new float[2], rotX = new float[2], rotY = new float[2], rotZ = new float[2];
    public static final float[] scale = {1f, 1f};
    public static boolean noEquip;

    /* swing */
    public static boolean swing;
    public static String mode = "Vanilla";
    public static float strength = 1f;
    public static float swingSpeed = 1f;
    /* custom swing */
    public static float cMoveX, cMoveY, cMoveZ, cRotX, cRotY, cRotZ;
    public static String cCurve = "Smooth";
    public static boolean cPivot = true;

    private ViewModelFx() {
    }

    private static void rx(MatrixStack m, float d) {
        m.multiply(RotationAxis.POSITIVE_X.rotationDegrees(d));
    }

    private static void ry(MatrixStack m, float d) {
        m.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(d));
    }

    private static void rz(MatrixStack m, float d) {
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(d));
    }

    /** Applied once per hand right after the hand's matrix push. i = 1 right hand, -1 left hand. */
    public static void applyViewModel(MatrixStack m, int i) {
        if (!viewModel) return;
        int h = i > 0 ? 0 : 1;
        m.translate(i * x[h], y[h], z[h]);
        ry(m, i * rotY[h]);
        rx(m, rotX[h]);
        rz(m, i * rotZ[h]);
        m.scale(scale[h], scale[h], scale[h]);
    }

    /** Replaces vanilla's swing (translation + rotation). Progress 0..1. */
    public static void applySwing(MatrixStack m, int i, float p) {
        float pi = (float) Math.PI;
        float s = (float) Math.sin(p * pi) * strength;
        float sq = (float) Math.sin(Math.sqrt(p) * pi) * strength;
        float s2 = (float) Math.sin(p * p * pi) * strength;
        float tw = (float) Math.sin(Math.sqrt(p) * pi * 2) * strength;
        switch (mode) {
            case "Smooth" -> {
                m.translate(i * -0.1f * sq, 0.05f * tw, -0.15f * s);
                ry(m, i * (45 - 15 * s2)); rz(m, i * -15 * sq); rx(m, -50 * sq); ry(m, i * -45);
            }
            case "Stab" -> {
                m.translate(0, 0.05f * s, -0.45f * sq);
                rx(m, -15 * sq);
            }
            case "Push" -> {
                m.translate(i * -0.05f * s, -0.08f * s, -0.25f * sq);
                rx(m, -35 * s); rz(m, i * -10 * s);
            }
            case "Chop" -> {
                m.translate(0, 0.1f * s, -0.1f * s);
                ry(m, i * 45); rx(m, -110 * sq + 20 * s2); ry(m, i * -45);
            }
            case "Slash" -> {
                m.translate(i * -0.1f * s, 0, 0);
                ry(m, i * (45 + 50 * s)); rz(m, i * -40 * sq); rx(m, -30 * sq); ry(m, i * -45);
            }
            case "Spin" -> {
                // spin the item around its own grip while lifting it slightly
                float e = p < 0.5f ? 4 * p * p * p : 1 - (float) Math.pow(-2 * p + 2, 3) / 2;
                m.translate(0, 0.1f * s, -0.08f * s);
                m.translate(0, 0.2f, 0);
                rz(m, i * -360 * e * Math.min(1f, strength));
                m.translate(0, -0.2f, 0);
            }
            case "Exhibition" -> {
                m.translate(0, 0.12f * sq, 0);
                ry(m, i * 45); rz(m, i * -18 * sq); rx(m, -72 * sq); ry(m, i * -45);
            }
            case "Sigma" -> {
                m.translate(i * -0.06f * sq, 0.06f * sq, -0.06f * s);
                ry(m, i * 45); rz(m, i * -32 * sq); rx(m, -48 * sq); ry(m, i * -10);
            }
            case "Rotate" -> {
                ry(m, i * -90 * sq);
                rx(m, -25 * s);
            }
            case "Flux" -> {
                m.translate(i * 0.08f * s, 0.1f * sq, -0.1f * s);
                ry(m, i * 30); rx(m, -80 * sq); rz(m, i * 20 * s); ry(m, i * -30);
            }
            case "Zoom" -> {
                float k = 1f + 0.35f * s;
                m.translate(0, 0, -0.25f * s);
                m.scale(k, k, k);
            }
            case "Punch" -> {
                m.translate(i * -0.15f * s, 0.1f * s, -0.55f * s);
                rx(m, -10 * s);
            }
            case "Swing Down" -> {
                m.translate(0, 0.15f * sq, 0);
                rx(m, 70 * sq - 20 * s2);
            }
            case "Helicopter" -> {
                ry(m, i * 720 * p * Math.min(1f, strength));
            }
            case "Flick" -> {
                float t = (float) Math.sin(Math.pow(p, 0.35) * Math.PI) * strength;
                ry(m, i * 45); rz(m, i * -50 * t); rx(m, -25 * t); ry(m, i * -45);
            }
            case "Jello" -> {
                float wob = (float) Math.sin(p * Math.PI * 4) * (1 - p) * strength;
                rz(m, i * 18 * wob); rx(m, -20 * sq); m.scale(1 + 0.08f * wob, 1 - 0.08f * wob, 1f);
            }
            case "Dash" -> {
                m.translate(i * -0.35f * sq, 0.05f * s, -0.15f * s);
                ry(m, i * 35 * sq);
            }
            case "Bounce" -> {
                float b = (float) Math.abs(Math.sin(p * Math.PI * 2)) * (1 - p * 0.5f) * strength;
                m.translate(0, 0.18f * b, 0);
                rx(m, -15 * b);
            }
            case "Twirl" -> {
                ry(m, i * 45); rz(m, i * -180 * sq); ry(m, i * -45);
            }
            case "Smash" -> {
                m.translate(0, 0.25f * s - 0.35f * s2, -0.1f * s);
                rx(m, -120 * sq + 90 * s2);
            }
            case "Glide" -> {
                m.translate(i * -0.25f * s, -0.05f * s, -0.2f * s);
                rz(m, i * 25 * s); ry(m, i * 20 * s);
            }
            case "Swank" -> {
                m.translate(i * -0.1f * sq, 0, 0);
                ry(m, i * 45); rz(m, i * -55 * sq); rx(m, -45 * sq); ry(m, i * -45);
            }
            case "Wave" -> {
                m.translate(0, 0.08f * s, 0);
                rz(m, i * 35 * s); ry(m, i * 25 * sq);
            }
            case "Tap" -> {
                float t = (float) Math.abs(Math.sin(p * pi * 2)) * strength;
                m.translate(0, 0, -0.1f * t);
                rx(m, -25 * t);
            }
            case "Low" -> {
                m.translate(0, -0.22f * s, -0.05f * s);
                rx(m, -70 * sq);
            }
            case "Classic" -> {
                m.translate(0, 0.06f, 0);
                ry(m, i * 45); rz(m, i * -20 * sq); rx(m, -60 * sq); ry(m, i * -45);
            }
            case "Custom" -> {
                float f = switch (cCurve) {
                    case "Sine" -> s;
                    case "Linear" -> (1 - Math.abs(2 * p - 1)) * strength;
                    case "Pulse" -> (float) Math.abs(Math.sin(p * pi * 2)) * strength;
                    case "Snap" -> (float) Math.sin(Math.pow(p, 0.3) * pi) * strength;
                    default -> sq;
                };
                m.translate(i * cMoveX * f, cMoveY * f, cMoveZ * f);
                if (cPivot) ry(m, i * 45);
                ry(m, i * cRotY * f); rz(m, i * cRotZ * f); rx(m, cRotX * f);
                if (cPivot) ry(m, i * -45);
            }
            default -> { // vanilla, scaled by strength
                m.translate(i * -0.4f * sq, 0.2f * tw, -0.2f * s);
                ry(m, i * (45 + s2 * -20)); rz(m, i * sq * -20); rx(m, sq * -80); ry(m, i * -45);
            }
        }
    }
}

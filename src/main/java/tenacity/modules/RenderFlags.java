package tenacity.modules;

/** Switches read by the render mixins (written by the render modules). */
public final class RenderFlags {
    public static volatile boolean glowPlayers, glowMobs, glowItems;
    public static volatile boolean noShading, noHurtCam, noFire, noWeather, customCrosshair;
    /** -1 = real time, otherwise the forced time of day. */
    public static volatile long time = -1;
    /** Player Transparency: alpha (1 = off) and who it applies to. */
    public static volatile float playerAlpha = 1f;
    public static volatile boolean alphaSelf, alphaOthers, frost, smoke;
    /** Frosted tint (rgb) multiplied into the see-through players and their armor. */
    public static volatile int alphaTint = 0xF3F5F8;

    /** A colour with the transparency alpha and the frosted tint applied. */
    public static int glassColor(int argb) {
        int a = Math.round(Math.max(0.02f, Math.min(1f, playerAlpha)) * ((argb >>> 24) & 255));
        int r = ((argb >> 16) & 255) * ((alphaTint >> 16) & 255) / 255, g = ((argb >> 8) & 255) * ((alphaTint >> 8) & 255) / 255,
                b = (argb & 255) * (alphaTint & 255) / 255;
        return a << 24 | r << 16 | g << 8 | b;
    }
    /** Glow ESP halo. */
    public static volatile float glowStrength = 1f, glowSize = 1f, glowFill = 0f, glowRimWidth = 2f;
    public static volatile boolean glowShimmer, glowPulse, glowChroma;
    /** Shader ESP colour override (0 = the entity's own outline colour). */
    public static volatile int glowColor;

    public static volatile boolean customHotbar, blockHighlight;
    public static volatile boolean customStatus;

    public static boolean zoomScroll(double v) {
        return RenderModules.Zoom.onScroll(v);
    }

    /** Custom FOV (0 = off) and Aspect Ratio target (0 = off), both eased so changes glide. */
    public static volatile float customFov, aspectTarget;
    private static float fovS = -1, aspectS = -1;
    private static long fovNs, aspectNs;

    /** Smoothed aspect ratio for the world projection, 0 = leave vanilla alone. */
    public static float aspect() {
        if (aspectTarget <= 0 && aspectS < 0) return 0;
        var w = net.minecraft.client.MinecraftClient.getInstance().getWindow();
        float real = (float) w.getFramebufferWidth() / Math.max(1, w.getFramebufferHeight());
        float goal = aspectTarget > 0 ? aspectTarget : real;
        long now = System.nanoTime();
        float dt = aspectNs == 0 ? 1 : Math.min(0.1f, (now - aspectNs) / 1e9f);
        aspectNs = now;
        if (aspectS < 0) aspectS = real;
        aspectS += (goal - aspectS) * (1 - (float) Math.exp(-10 * dt));
        if (aspectTarget <= 0 && Math.abs(aspectS - real) < 0.002f) {
            aspectS = -1;
            return 0;
        }
        return aspectS;
    }

    public static float zoom(float fov) {
        // Custom FOV: scale the final fov (keeps sprint / speed fov changes) to the chosen value, past vanilla's 110 cap
        float base = net.minecraft.client.MinecraftClient.getInstance().options.getFov().getValue();
        float goal = customFov > 0 ? customFov : base;
        long now = System.nanoTime();
        float dt = fovNs == 0 ? 1 : Math.min(0.1f, (now - fovNs) / 1e9f);
        fovNs = now;
        if (fovS < 0) fovS = goal;
        fovS += (goal - fovS) * (1 - (float) Math.exp(-12 * dt));
        if (Math.abs(fovS - base) > 0.01f) fov = fov * fovS / Math.max(1f, base);
        return RenderModules.Zoom.apply(fov);
    }

    public static void autoTool(net.minecraft.util.math.BlockPos pos) {
        PlayerModules.autoTool(pos);
    }

    public static boolean glowAny() {
        return glowPlayers || glowMobs || glowItems;
    }

    private RenderFlags() {
    }
}

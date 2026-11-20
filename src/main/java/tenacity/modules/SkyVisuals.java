package tenacity.modules;

import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.SkyVisualsFx;

/** Procedural sky: themes, sun / moon, clouds, hot-air balloons, stars + constellations, shooting stars, horizon glow. */
public final class SkyVisuals extends Module {
    private static final String[] THEMES = {"Day", "Sunset", "Night", "Pastel Dream", "Cotton Candy", "Aurora", "Golden Hour", "Ocean", "Monochrome"};
    /** zenith, horizon, glow per theme. */
    private static final float[][][] PAL = {
            {{0.20f, 0.42f, 0.84f}, {0.66f, 0.81f, 0.96f}, {1.00f, 0.92f, 0.75f}},
            {{0.15f, 0.16f, 0.40f}, {1.00f, 0.54f, 0.32f}, {1.00f, 0.46f, 0.22f}},
            {{0.008f, 0.012f, 0.045f}, {0.05f, 0.07f, 0.16f}, {0.25f, 0.30f, 0.55f}},
            {{0.52f, 0.58f, 0.94f}, {1.00f, 0.82f, 0.88f}, {1.00f, 0.86f, 0.95f}},
            {{0.42f, 0.58f, 1.00f}, {1.00f, 0.66f, 0.84f}, {1.00f, 0.74f, 0.90f}},
            {{0.008f, 0.03f, 0.07f}, {0.04f, 0.14f, 0.17f}, {0.25f, 0.85f, 0.60f}},
            {{0.30f, 0.42f, 0.72f}, {1.00f, 0.76f, 0.44f}, {1.00f, 0.70f, 0.35f}},
            {{0.02f, 0.25f, 0.55f}, {0.40f, 0.85f, 0.92f}, {0.85f, 1.00f, 0.95f}},
            {{0.03f, 0.03f, 0.04f}, {0.52f, 0.53f, 0.57f}, {1.00f, 1.00f, 1.00f}},
    };
    /** Sun elevation (degrees) each theme implies when the time source is "Theme". */
    private static final float[] ELEV = {48, 3, -32, 26, 14, -26, 8, 40, 22};

    private final Setting.Mode preset = mode("Preset", "Custom", "Custom", "Day", "Sunset", "Night");
    private final Setting.Mode theme = mode("Theme", "Day", THEMES);
    private final Setting.Mode time = mode("Time Source", "Theme", "Theme", "Real Time", "Game Time");
    private final Setting.Color tint = color("Sky Colour", 0xFFFFFFFF);
    private final Setting.Num tintAmt = num("Tint Amount", 0, 0, 100, 5);
    private final Setting.Num anim = num("Animation Speed", 1, 0, 4, 0.05);
    private final Setting.Bool clouds = bool("Clouds", true);
    private final Setting.Num cloudSpeed = num("Cloud Speed", 1, 0, 5, 0.1).showIf(clouds::on);
    private final Setting.Num cover = num("Cloud Cover", 50, 0, 100, 5).showIf(clouds::on);
    private final Setting.Bool balloons = bool("Balloons", true);
    private final Setting.Num density = num("Balloon Density", 6, 1, 16, 1).showIf(balloons::on);
    private final Setting.Num bSpeed = num("Balloon Speed", 1, 0, 5, 0.1).showIf(balloons::on);
    private final Setting.Bool sunMoon = bool("Sun & Moon", true);
    private final Setting.Num halo = num("Halo", 100, 0, 200, 5).showIf(sunMoon::on);
    private final Setting.Bool stars = bool("Stars", true);
    private final Setting.Bool constellations = bool("Constellations", true);
    private final Setting.Bool shooting = bool("Shooting Stars", true);
    private final Setting.Num shootRate = num("Shooting Rate", 1, 0.2, 5, 0.1).showIf(shooting::on);
    private final Setting.Num hGlow = num("Horizon Glow", 100, 0, 200, 5);
    private final Setting.Num fog = num("Fog Tint", 35, 0, 100, 5);

    private int lastPreset = -1;
    /** Smoothed values so switching themes / presets eases instead of snapping. */
    private final float[] cur = new float[9 + 3 + 2];
    private boolean seeded;

    public SkyVisuals() {
        super("Sky Visuals", "Procedural sky with clouds, balloons, sun, moon and constellations", Category.RENDER);
        buildConstellations();
    }

    @Override
    public String suffix() {
        return theme.get();
    }

    @Override
    protected void onEnable() {
        seeded = false;
        SkyVisualsFx.on = true;
    }

    @Override
    protected void onDisable() {
        SkyVisualsFx.on = false;
    }

    private void applyPreset() {
        if (preset.index == lastPreset) return;
        lastPreset = preset.index;
        switch (preset.get()) {
            case "Day" -> { theme.index = 0; time.index = 0; clouds.value = true; balloons.value = true; cover.value = 45; }
            case "Sunset" -> { theme.index = 1; time.index = 0; clouds.value = true; balloons.value = true; cover.value = 55; }
            case "Night" -> { theme.index = 2; time.index = 0; clouds.value = true; cover.value = 25; stars.value = true; constellations.value = true; shooting.value = true; }
            default -> { }
        }
    }

    @Override
    public void tick() {
        applyPreset();
    }

    /** Called every frame through render2d so the easing is frame-rate smooth. */
    @Override
    public void render2d(float td) {
        int th = Math.max(0, Math.min(THEMES.length - 1, theme.index));
        float elev;
        if (time.is("Real Time")) {
            java.time.LocalTime lt = java.time.LocalTime.now();
            float hours = lt.getHour() + lt.getMinute() / 60f + lt.getSecond() / 3600f;
            elev = (float) Math.toDegrees(Math.asin(Math.sin((hours - 6f) / 24f * Math.PI * 2) * Math.sin(Math.toRadians(62))));
        } else if (time.is("Game Time") && mc.world != null) {
            float hours = (mc.world.getTimeOfDay() % 24000L) / 1000f + 6f;
            elev = (float) Math.toDegrees(Math.asin(Math.sin((hours - 6f) / 24f * Math.PI * 2) * Math.sin(Math.toRadians(62))));
        } else elev = ELEV[th];
        boolean morning = !time.is("Theme") && isMorning();

        float day = smooth(-4f, 14f, elev);
        float dusk = (float) Math.exp(-(elev - 3f) * (elev - 3f) / 90f);
        float night = 1f - smooth(-12f, 1f, elev);
        float[] z = new float[3], h = new float[3], g = new float[3];
        for (int i = 0; i < 3; i++) {
            if (time.is("Theme")) {
                z[i] = PAL[th][0][i];
                h[i] = PAL[th][1][i];
                g[i] = PAL[th][2][i];
            } else {
                // the theme is the daytime look; night and sunset palettes take over as the sun moves
                z[i] = lerp(PAL[2][0][i], PAL[th][0][i], day);
                h[i] = lerp(PAL[2][1][i], PAL[th][1][i], day);
                g[i] = lerp(PAL[2][2][i], PAL[th][2][i], day);
                z[i] = lerp(z[i], PAL[1][0][i], dusk * 0.6f);
                h[i] = lerp(h[i], PAL[1][1][i], dusk * 0.75f);
                g[i] = lerp(g[i], PAL[1][2][i], dusk * 0.8f);
            }
        }
        if (time.is("Theme") && th == 5) night = 1f;
        float aur = th == 5 ? 1f : 0f;

        // ease everything
        float k = seeded ? 1f - (float) Math.exp(-tenacity.ui.Anim.dt * 2.5f) : 1f;
        seeded = true;
        float[] target = {z[0], z[1], z[2], h[0], h[1], h[2], g[0], g[1], g[2], night, dusk, aur, elev, 0};
        for (int i = 0; i < 13; i++) cur[i] += (target[i] - cur[i]) * k;

        System.arraycopy(cur, 0, SkyVisualsFx.zenith, 0, 3);
        System.arraycopy(cur, 3, SkyVisualsFx.horizon, 0, 3);
        System.arraycopy(cur, 6, SkyVisualsFx.glow, 0, 3);
        SkyVisualsFx.night = cur[9];
        SkyVisualsFx.dusk = cur[10];
        SkyVisualsFx.aurora = cur[11];
        float e = (float) Math.toRadians(cur[12]);
        // sun travels east (+x) -> west (-x); in theme mode it sits in the west like late afternoon
        float az = morning ? 0.35f : (float) Math.PI - 0.35f;
        float ce = (float) Math.cos(e);
        set(SkyVisualsFx.sunDir, (float) Math.cos(az) * ce, (float) Math.sin(e), (float) Math.sin(az) * ce * -1f);
        float me = (float) Math.toRadians(Math.max(-cur[12], 18f + 22f * cur[9]));
        float maz = az + (float) Math.PI * 0.85f;
        set(SkyVisualsFx.moonDir, (float) Math.cos(maz) * (float) Math.cos(me), (float) Math.sin(me), (float) Math.sin(maz) * (float) Math.cos(me) * -1f);
        float warm = 1f - smooth(2f, 25f, cur[12]);
        set(SkyVisualsFx.sunColor, 1f, lerp(0.95f, 0.62f, warm), lerp(0.86f, 0.36f, warm));

        int c = tint.rgb();
        set(SkyVisualsFx.tint, ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
        SkyVisualsFx.tintAmount = tintAmt.f() / 100f;
        SkyVisualsFx.speed = anim.f();
        SkyVisualsFx.clouds = clouds.on();
        SkyVisualsFx.cloudSpeed = cloudSpeed.f();
        SkyVisualsFx.cloudCover = cover.f() / 100f;
        SkyVisualsFx.showBalloons = balloons.on();
        SkyVisualsFx.balloons = density.f();
        SkyVisualsFx.balloonSpeed = bSpeed.f();
        SkyVisualsFx.sunMoon = sunMoon.on();
        SkyVisualsFx.halo = halo.f() / 100f;
        SkyVisualsFx.stars = stars.on();
        SkyVisualsFx.constellations = constellations.on();
        SkyVisualsFx.showShooting = shooting.on();
        SkyVisualsFx.shooting = shootRate.f();
        SkyVisualsFx.horizonGlow = hGlow.f() / 100f;
        SkyVisualsFx.fog = fog.f() / 100f;
        SkyVisualsFx.on = true;
    }

    private boolean isMorning() {
        if (time.is("Real Time")) return java.time.LocalTime.now().getHour() < 12;
        return mc.world != null && (mc.world.getTimeOfDay() % 24000L) < 6000L;
    }

    private static void set(float[] a, float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        if (a == SkyVisualsFx.sunColor || a == SkyVisualsFx.tint) l = 1f;
        a[0] = x / l;
        a[1] = y / l;
        a[2] = z / l;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    /* ------------------------------------------------------------------ constellations (azimuth°, altitude°) */

    private static final float[][][] FIGURES = {
            // Big Dipper
            {{20, 55}, {28, 58}, {35, 57}, {41, 54}, {44, 47}, {54, 46}, {55, 53}, {41, 54}},
            // Orion (belt + shoulders + feet)
            {{200, 38}, {206, 30}, {209, 29}, {212, 28}, {216, 37}, {200, 38}, {-1, -1}, {206, 30}, {201, 20}, {-1, -1}, {212, 28}, {218, 19}},
            // Cassiopeia
            {{110, 62}, {116, 67}, {122, 63}, {128, 68}, {135, 64}},
            // Cygnus (cross)
            {{290, 70}, {292, 60}, {294, 50}, {-1, -1}, {282, 60}, {292, 60}, {302, 61}},
            // Leo
            {{150, 35}, {155, 41}, {162, 43}, {168, 40}, {172, 33}, {160, 30}, {150, 35}},
            // Lyra
            {{330, 46}, {334, 42}, {336, 37}, {330, 36}, {329, 41}, {334, 42}},
    };

    private static void buildConstellations() {
        int n = 0;
        for (float[][] f : FIGURES) {
            for (int i = 0; i + 1 < f.length && n < SkyVisualsFx.MAX_SEGS(); i++) {
                if (f[i][0] < 0 || f[i + 1][0] < 0) continue;
                dir(f[i], SkyVisualsFx.segA, n * 3);
                dir(f[i + 1], SkyVisualsFx.segB, n * 3);
                n++;
            }
        }
        SkyVisualsFx.segCount = n;
    }

    private static void dir(float[] azAlt, float[] out, int o) {
        double az = Math.toRadians(azAlt[0]), al = Math.toRadians(azAlt[1]);
        out[o] = (float) (Math.cos(al) * Math.cos(az));
        out[o + 1] = (float) Math.sin(al);
        out[o + 2] = (float) (Math.cos(al) * Math.sin(az));
    }
}

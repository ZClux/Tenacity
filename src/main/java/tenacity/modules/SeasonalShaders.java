package tenacity.modules;

import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.HandFx;
import tenacity.gfx.WorldFx;

import java.util.List;

/**
 * Seasonal Hand (Christmas + Halloween looks for the first person hand) and Seasonal Sky (Christmas + Halloween shader skies).
 * Both drive the existing pipelines (HandFx / WorldFx) through additive state, so Hand Shaders and Shader Sky are untouched.
 */
public final class SeasonalShaders {
    private SeasonalShaders() {
    }

    public static List<Module> create() {
        return List.of(new SeasonalHand(), new SeasonalSky());
    }

    private static final String[] XMAS_HAND = {"Candy Cane", "Frosted Pine", "Ornament Glass", "Snowglobe", "Tinsel Sparkle", "Aurora Frost",
            "Gingerbread", "Holly Berry", "Northern Lights", "Gift Wrap", "Ice Crystal", "Warm Fireplace"};
    private static final String[] HAL_HAND = {"Pumpkin Glow", "Haunted Mist", "Witch Brew", "Jack-o-Lantern Flame", "Ghost Wisp", "Cursed Purple",
            "Blood Moon", "Spider Web", "Candy Corn", "Zombie Rot", "Skeleton Bone", "Spooky Lightning"};
    private static final String[] XMAS_SKY = {"Snowy Aurora Night", "Starry Snowfall", "Santa Sleigh Moon", "Red Green Dusk", "Frosted Glass Dome",
            "Candy Sky", "Silent Night", "Northern Lights"};
    private static final String[] HAL_SKY = {"Blood Moon", "Haunted Bats", "Orange Thunderstorm", "Graveyard Fog", "Giant Pumpkin Moon",
            "Witch Night", "Black Eclipse", "Green Mist"};

    /** Default colour / accent per look so each one opens looking right (user can override with Custom colours). */
    private static final int[][] HAND_COLORS = {
            {0xFFE01020, 0xFFFFFFFF}, {0xFF1E7A3C, 0xFFFFFFFF}, {0xFFD01830, 0xFFFFD27A}, {0xFFFFFFFF, 0xFFFFFFFF}, {0xFFE8C45A, 0xFFE0304A}, {0xFF39FFB0, 0xFF7C9CFF},
            {0xFF8B4A1C, 0xFFFFFFFF}, {0xFF1C7A30, 0xFFE0182A}, {0xFF22FF99, 0xFFB050FF}, {0xFFD01830, 0xFFFFD27A}, {0xFFFFFFFF, 0xFFFFFFFF}, {0xFFFF7A1E, 0xFFFFD27A},
            {0xFFFF7A10, 0xFFFFB347}, {0xFFB8C8C4, 0xFF9CC8B8}, {0xFF55E030, 0xFFB6FF80}, {0xFFFF6A00, 0xFFFFD060}, {0xFFEAF4F0, 0xFFA8FFE8}, {0xFF8A2BE2, 0xFFD070FF},
            {0xFFB0101A, 0xFFFF5040}, {0xFFCCD2E0, 0xFFFFFFFF}, {0xFFFFA010, 0xFFFFE070}, {0xFF6E8A3A, 0xFFB6E060}, {0xFFEDE4CC, 0xFFD8F0E0}, {0xFF7A4DFF, 0xFFE0D0FF},
    };

    /* ------------------------------------------------------------------ hand */

    static final class SeasonalHand extends Module {
        private final Setting.Mode theme = mode("Theme", "Christmas", "Christmas", "Halloween");
        private final Setting.Mode xmas = mode("Christmas Look", XMAS_HAND[0], XMAS_HAND).showIf(() -> theme.is("Christmas"));
        private final Setting.Mode hal = mode("Halloween Look", HAL_HAND[0], HAL_HAND).showIf(() -> theme.is("Halloween"));
        private final Setting.Mode colorMode = mode("Color Mode", "Preset", "Preset", "Custom");
        private final Setting.Color color = color("Color", 0xFFE01020).showIf(() -> colorMode.is("Custom"));
        private final Setting.Color accent = color("Accent", 0xFFFFFFFF).showIf(() -> colorMode.is("Custom"));
        private final Setting.Num intensity = num("Intensity", 100, 0, 100, 5);
        private final Setting.Num speed = num("Speed", 1, 0.1, 3, 0.05);
        private final Setting.Num scale = num("Pattern Scale", 1, 0.4, 2.5, 0.05);
        private final Setting.Num brightness = num("Brightness", 100, 50, 160, 5);
        private final Setting.Bool animate = bool("Animation", true);
        private final Setting.Num glow = num("Glow", 50, 0, 150, 5);
        private final Setting.Num sparkle = num("Sparkle / Particles", 50, 0, 150, 5);
        private final Setting.Num edge = num("Edge Strength", 50, 0, 150, 5);
        private final Setting.Num pulse = num("Pulse", 0, 0, 100, 5);
        private final Setting.Bool outline = bool("Glow Outline", true);
        private final Setting.Num outlineSize = num("Outline Size", 4, 1, 12, 0.5).showIf(outline::on);
        private final Setting.Num outlineStr = num("Outline Strength", 70, 10, 150, 5).showIf(outline::on);
        private final Setting.Num opacity = num("Hand Opacity", 100, 20, 100, 5);
        private final Setting.Num sat = num("Saturation", 100, 0, 200, 5);
        private final Setting.Num sweep = num("Light Sweep", 0, 0, 100, 5);
        private final Setting.Num fresnel = num("Rim Light", 0, 0, 100, 5);

        private float animClock;
        private long lastNs;

        SeasonalHand() {
            super("Seasonal Hand", "Christmas and Halloween first person hand looks", Category.RENDER);
        }

        @Override
        public String suffix() {
            return theme.is("Christmas") ? xmas.get() : hal.get();
        }

        private int look() {
            return theme.is("Christmas") ? 1 + xmas.index : 13 + hal.index;
        }

        @Override
        public void tick() {
            int look = look();
            HandFx.season = look;
            int[] c = HAND_COLORS[look - 1];
            HandFx.seasonColor = colorMode.is("Custom") ? color.rgb() : c[0];
            HandFx.seasonAccent = colorMode.is("Custom") ? accent.rgb() : c[1];
            HandFx.seasonAmount = intensity.f() / 100f;
            HandFx.seasonSpeed = animate.on() ? speed.f() : 0f;
            HandFx.seasonScale = scale.f();
            HandFx.seasonBright = brightness.f() / 100f;
            HandFx.seasonGlow = glow.f() / 100f;
            HandFx.seasonSparkle = sparkle.f() / 100f;
            HandFx.seasonEdge = edge.f() / 100f;
            HandFx.seasonPulse = pulse.f() / 100f;
            HandFx.opacity = opacity.f() / 100f;
            HandFx.saturation = sat.f() / 100f;
            HandFx.sweep = sweep.f() / 100f;
            HandFx.fresnel = fresnel.f() / 100f;
            HandFx.glow = outline.on();
            HandFx.glowSize = outlineSize.f();
            HandFx.glowStrength = outlineStr.f() / 100f;
            HandFx.glowColor = colorMode.is("Custom") ? accent.rgb() : c[1] | 0xFF000000;
            HandFx.chromaGlow = false;
            HandFx.itemGlow = false;
            HandFx.hideHand = false;
            HandFx.enabled = true;
        }

        @Override
        protected void onDisable() {
            HandFx.season = 0;
            HandFx.enabled = false;
            HandFx.glow = false;
            HandFx.opacity = 1f;
            HandFx.saturation = 1f;
            HandFx.sweep = 0f;
            HandFx.fresnel = 0f;
        }
    }

    /* ------------------------------------------------------------------ sky */

    static final class SeasonalSky extends Module {
        private final Setting.Mode theme = mode("Theme", "Christmas", "Christmas", "Halloween");
        private final Setting.Mode xmas = mode("Christmas Sky", XMAS_SKY[0], XMAS_SKY).showIf(() -> theme.is("Christmas"));
        private final Setting.Mode hal = mode("Halloween Sky", HAL_SKY[0], HAL_SKY).showIf(() -> theme.is("Halloween"));
        private final Setting.Num intensity = num("Intensity", 100, 10, 100, 5);
        private final Setting.Num brightness = num("Brightness", 100, 40, 180, 5);
        private final Setting.Num speed = num("Speed", 1, 0.1, 3, 0.05);
        private final Setting.Num scale = num("Scale", 1.1, 0.45, 3, 0.05);
        private final Setting.Num stars = num("Stars", 75, 0, 100, 5);
        private final Setting.Num clouds = num("Clouds", 100, 0, 200, 5);
        private final Setting.Num moon = num("Moon Size", 100, 40, 220, 5);
        private final Setting.Num particles = num("Particles", 100, 0, 200, 5);
        private final Setting.Num aurora = num("Aurora", 100, 0, 200, 5).showIf(() -> theme.is("Christmas"));
        private final Setting.Num fog = num("Fog", 100, 0, 200, 5).showIf(() -> theme.is("Halloween"));
        private final Setting.Num glowS = num("Glow", 100, 0, 200, 5);
        private final Setting.Bool vanilla = bool("Vanilla Sky", false);
        private final Setting.Mode colorMode = mode("Color Mode", "Theme", "Theme", "Custom");
        private final Setting.Color color1 = color("Color 1", 0xFFFFFFFF).showIf(() -> colorMode.is("Custom"));
        private final Setting.Color color2 = color("Color 2", 0xFFFFFFFF).showIf(() -> colorMode.is("Custom"));

        /** Eased copies so every slider glides (and mode changes crossfade by dipping the strength). */
        private float cCl = 1f, cMoon = 1f, cPart = 1f, cAur = 1f, cFog = 1f, cGlow = 1f, cBright = 1f, cStr = 1f;
        private int shown = -1;
        private long lastNs;

        SeasonalSky() {
            super("Seasonal Sky", "Christmas and Halloween shader skies", Category.RENDER);
        }

        @Override
        public String suffix() {
            return theme.is("Christmas") ? xmas.get() : hal.get();
        }

        private int mode() {
            return theme.is("Christmas") ? 100 + xmas.index : 108 + hal.index;
        }

        @Override
        public void tick() {
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 1f : Math.min(0.1f, (now - lastNs) / 1e9f);
            lastNs = now;
            float k = 1f - (float) Math.exp(-dt * 6f);
            int want = mode();
            if (shown < 0) shown = want;
            float strTarget = intensity.f() / 100f;
            if (want != shown) {
                // dip out, swap the mode while it is invisible, fade back in
                cStr += (0.02f - cStr) * Math.min(1f, k * 1.8f);
                if (cStr < 0.06f) shown = want;
            } else cStr += (strTarget - cStr) * k;
            cCl += (clouds.f() / 100f - cCl) * k;
            cMoon += (moon.f() / 100f - cMoon) * k;
            cPart += (particles.f() / 100f - cPart) * k;
            cAur += (aurora.f() / 100f - cAur) * k;
            cFog += (fog.f() / 100f - cFog) * k;
            cGlow += (glowS.f() / 100f - cGlow) * k;
            cBright += (brightness.f() / 100f - cBright) * k;

            WorldFx.sky = shown;
            WorldFx.skyPrimary = colorMode.is("Theme") ? 0xFFFFFFFF : color1.rgb();
            WorldFx.skySecondary = colorMode.is("Theme") ? 0xFFBFBFBF : color2.rgb();
            WorldFx.skyCustom = colorMode.is("Custom");
            WorldFx.skySpeed = speed.f();
            WorldFx.skyScale = scale.f();
            WorldFx.skyStrength = Math.max(0.02f, cStr);
            WorldFx.skyStars = stars.f() / 100f;
            WorldFx.skyVanilla = vanilla.on();
            WorldFx.seasonClouds = cCl;
            WorldFx.seasonMoon = cMoon;
            WorldFx.seasonParticles = cPart;
            WorldFx.seasonAurora = cAur;
            WorldFx.seasonFog = cFog;
            WorldFx.seasonGlow = cGlow;
            WorldFx.seasonBright = cBright;
        }

        @Override
        protected void onEnable() {
            shown = -1;
            cStr = 0.02f;
            lastNs = 0;
        }

        @Override
        protected void onDisable() {
            WorldFx.sky = -1;
            WorldFx.seasonClouds = WorldFx.seasonMoon = WorldFx.seasonParticles = WorldFx.seasonAurora = WorldFx.seasonFog = WorldFx.seasonGlow = WorldFx.seasonBright = 1f;
        }
    }
}

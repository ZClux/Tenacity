package tenacity.modules;

import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.WorldStyleFx;

import java.util.List;

/** Restyles the whole world (69 looks) and can add smooth screen-space reflections on the ground. */
final class WorldStyle extends Module {
    /** Order must match uMode in WorldStyleFx (index + 1). */
    private static final List<String> STYLES = List.of(
            "Frost", "Cyber", "Night", "Vapor", "Toon", "Noir", "Thermal", "Dream", "Matrix", "Sunset",
            "Underwater", "Inferno", "Toxic", "Blueprint", "Sketch", "Pastel", "Hologram", "Retro", "Gameboy", "Sakura",
            "Infrared", "Golden", "Void", "Aurora",
            "Anime", "Oil Painting", "Watercolor", "Neon Noir", "Ice Age", "Lava World", "Moonlight", "Storybook", "Comic", "Glitch",
            "Crystal", "Deep Ocean", "Snowfall", "Rainy", "Silent Hill", "Western", "Cyberpunk", "Chrome", "Prism", "Shadow Realm",
            "Ghibli Summer", "Cinematic", "Dreamy Haze", "Autumn", "Spring Bloom", "Bioluminescent", "Synthwave Grid", "Vaporwave Mall",
            "Pixel Art", "Stained Glass", "Mosaic", "Neon Wireframe", "X-Ray Ghost", "Desert Heat", "Arctic Blizzard", "Volcanic Ash",
            "Candyland", "Sumi-e Ink", "Retro VHS", "Film Noir", "Polaroid", "Low Poly", "Cartoon", "Underworld", "Thunderstorm",
            "None");
    private static final List<String> PARTICLE_STYLES = List.of("Snowfall", "Rainy", "Neon Noir", "Cyberpunk", "Lava World", "Deep Ocean",
            "Silent Hill", "Shadow Realm", "Ghibli Summer", "Dreamy Haze", "Autumn", "Spring Bloom", "Bioluminescent", "Desert Heat",
            "Arctic Blizzard", "Volcanic Ash", "Underworld", "Thunderstorm");
    private static final List<String> WIND_STYLES = List.of("Rainy", "Neon Noir", "Cyberpunk", "Thunderstorm", "Arctic Blizzard", "Autumn",
            "Ghibli Summer", "Spring Bloom", "Desert Heat", "Volcanic Ash");

    private final Setting.Mode style = mode("Style", "Frost", STYLES.toArray(new String[0]));
    private final Setting.Num intensity = num("Intensity", 100, 10, 100, 5);
    private final Setting.Num edges = num("Edges", 100, 0, 200, 5).showIf(() -> !style.is("None"));
    private final Setting.Mode color = mode("Cyber Color", "Cyan", "Cyan", "Magenta", "Lime", "Purple", "Red", "Orange", "White", "Chroma").showIf(this::colored);
    private final Setting.Mode color2 = mode("Accent Color", "Magenta", "Magenta", "Cyan", "Lime", "Purple", "Red", "Orange", "White").showIf(() -> colored() && !color.is("Chroma"));
    private final Setting.Num grid = num("Block Grid", 100, 0, 200, 5).showIf(() -> style.is("Cyber") || style.is("Vapor") || style.is("Synthwave Grid") || style.is("Neon Wireframe"));
    private final Setting.Num scan = num("Scanlines", 50, 0, 100, 5).showIf(() -> style.is("Cyber") || style.is("Cyberpunk") || style.is("Synthwave Grid"));
    private final Setting.Num particles = num("Particles", 100, 0, 200, 5).showIf(() -> PARTICLE_STYLES.contains(style.get()));
    private final Setting.Num wind = num("Wind", 100, 0, 300, 10).showIf(() -> WIND_STYLES.contains(style.get()));
    private final Setting.Num pixel = num("Pixel Size", 4, 2, 10, 1).showIf(() -> style.is("Pixel Art"));
    private final Setting.Num sparkles = num("Sparkles", 100, 0, 200, 5).showIf(() -> style.is("Ice Age") || style.is("Crystal"));
    private final Setting.Bool reflection = bool("Reflection", false);
    private final Setting.Num reflStrength = num("Reflection Strength", 55, 5, 100, 5).showIf(reflection::on);
    private final Setting.Num roughness = num("Roughness", 30, 0, 100, 5).showIf(reflection::on);
    private float fade, lensWet;
    private long lastNs;

    WorldStyle() {
        super("World Style", "Restyles the whole world (Frost, Cyber, Anime, Oil Painting, Neon Noir, ...) with optional ground reflections", Category.RENDER);
    }

    private boolean rainy() {
        return style.is("Rainy") || style.is("Neon Noir") || style.is("Cyberpunk") || style.is("Thunderstorm");
    }

    private boolean colored() {
        return style.is("Cyber") || style.is("Neon Noir") || style.is("Cyberpunk");
    }

    private static int col(String c) {
        return switch (c) {
            case "Magenta" -> 0xFFFF2BD6;
            case "Lime" -> 0xFF7CFF3A;
            case "Purple" -> 0xFF9B50FF;
            case "Red" -> 0xFFFF3050;
            case "Orange" -> 0xFFFF8A1F;
            case "White" -> 0xFFFFFFFF;
            default -> 0xFF00E5FF;
        };
    }

    @Override
    public String suffix() {
        return reflection.on() ? style.get() + " + Refl" : style.get();
    }

    private String lastStyle;

    @Override
    public void tick() {
        if (lastStyle != null && !lastStyle.equals(style.get())) fade = 0; // switching styles cross-fades in
        lastStyle = style.get();
        WorldStyleFx.mode = 1 + STYLES.indexOf(style.get());
        WorldStyleFx.edges = edges.f() / 100f;
        WorldStyleFx.grid = grid.f() / 100f;
        WorldStyleFx.scan = scan.f() / 100f;
        WorldStyleFx.particles = particles.f() / 100f;
        WorldStyleFx.glints = sparkles.f() / 100f;
        WorldStyleFx.wind = wind.f() / 100f;
        WorldStyleFx.pixel = pixel.f();
        WorldStyleFx.reflection = reflection.on();
        WorldStyleFx.reflStrength = reflStrength.f() / 100f;
        WorldStyleFx.roughness = roughness.f() / 100f;
        WorldStyleFx.chroma = color.is("Chroma");
        WorldStyleFx.color = col(color.get());
        WorldStyleFx.color2 = col(color2.get());
    }

    @Override
    public void renderWorld(voided.events.impl.WorldRenderEvent event) {
        // fade in smoothly when turned on
        fade = Math.min(1f, fade + 0.04f);
        float s = fade * fade * (3 - 2 * fade);
        WorldStyleFx.intensity = intensity.f() / 100f * s;
        WorldStyleFx.reflFade = s;
        // lens drops collect while looking up into the rain and dry off / run away when looking down
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0f : Math.min(0.1f, (now - lastNs) / 1e9f);
        lastNs = now;
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        float pitch = mc.player != null ? mc.player.getPitch() : 0f;   // negative = looking up
        float up = Math.max(0f, Math.min(1f, (-pitch + 5f) / 50f));
        float target = 0f; // lens drops removed
        float down = Math.max(0f, Math.min(1f, (pitch - 5f) / 40f));
        if (dt == 0f) lensWet = target;
        if (target > lensWet) lensWet = Math.min(target, lensWet + dt * (0.08f + 0.4f * up));
        else lensWet = Math.max(target, lensWet - dt * (0.06f + 0.35f * down));
        WorldStyleFx.lens = lensWet * s;
    }

    @Override
    protected void onDisable() {
        WorldStyleFx.mode = 0;
        fade = 0;
    }
}

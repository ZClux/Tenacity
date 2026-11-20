package tenacity.modules;

import tenacity.audio.AudioAnalyzer;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.MusicVisFx;
import tenacity.spotify.NowPlaying;
import tenacity.spotify.SpotifyPoller;

/**
 * Audio-reactive sky visualiser. Real-time analysis of the PC's output (WASAPI loopback FFT -> endpoint meter ->
 * Stereo Mix -> simulated) on a daemon thread; Spotify state gates it (decays to flat when paused) and resets the
 * adaptive gain on song change. Rendering is one additive GLSL sky pass ({@link MusicVisFx}).
 */
public final class MusicVisualiser extends Module {
    private static final String[] STYLES = {"All", "Neon Waves", "Equalizer", "Aurora", "Pulse Rings", "Spectrum Ribbon"};

    private final Setting.Mode style = mode("Style", "Neon Waves", STYLES);
    private final Setting.Mode preset = mode("Colour Preset", "Purple/Pink", "Purple/Pink", "Blue/Cyan", "Rainbow", "Custom");
    private final Setting.Color col1 = color("Primary Colour", 0xFFB44CFF).showIf(() -> preset.is("Custom"));
    private final Setting.Color col2 = color("Secondary Colour", 0xFFFF4FB4).showIf(() -> preset.is("Custom"));
    private final Setting.Bool gradient = bool("Gradient", true);
    private final Setting.Num waveHeight = num("Wave Height", 1, 0.2, 3, 0.05);
    private final Setting.Num waveSpeed = num("Wave Speed", 1, 0, 4, 0.05);
    private final Setting.Num thickness = num("Wave Thickness", 1, 0.3, 4, 0.1);
    private final Setting.Num waveCount = num("Wave Count", 4, 1, 8, 1);
    private final Setting.Num sensitivity = num("Beat Sensitivity", 1, 0.3, 3, 0.05);
    private final Setting.Num glow = num("Glow Intensity", 1, 0, 3, 0.05);
    private final Setting.Bool particles = bool("Particles", true);
    private final Setting.Bool equalizer = bool("Equalizer", false);
    private final Setting.Bool rings = bool("Pulse Rings", true);
    private final Setting.Bool aurora = bool("Aurora", false);
    private final Setting.Num skyHeight = num("Sky Height", 18, -5, 60, 1);
    private final Setting.Num opacity = num("Opacity", 100, 0, 100, 5);
    private final Setting.Bool spotifyOnly = bool("Spotify Only", true);
    private final Setting.Mode source = mode("Audio Source", "Auto", AudioAnalyzer.SOURCES);
    /** Read-only status line: a single-entry mode whose label is rewritten every tick. */
    private final Setting.Mode status = mode("Status", "Starting", "Starting");

    private final float[] disp = new float[MusicVisFx.BANDS];
    private long lastBeatCount = -1, lastNs;
    private NowPlaying lastTrack;
    private float act;
    private String srcLabel = "Starting";

    public MusicVisualiser() {
        super("Music Visualiser", "Audio-reactive neon waves, equalizer, aurora and beat rings in the sky", Category.RENDER);
    }

    @Override
    public String suffix() {
        return style.get() + " · " + srcLabel;
    }

    @Override
    protected void onEnable() {
        lastNs = 0;
        lastBeatCount = -1;
        java.util.Arrays.fill(MusicVisFx.ringAge, 99f);
        AudioAnalyzer.get();
    }

    @Override
    protected void onDisable() {
        MusicVisFx.on = false;
    }

    @Override
    public void tick() {
        AudioAnalyzer a = AudioAnalyzer.get();
        a.setSource(source.index);
        a.setSensitivity(sensitivity.f());
        String s = a.status();
        status.modes[0] = s.length() > 42 ? s.substring(0, 42) + "..." : s;
        status.index = 0;
        srcLabel = a.snapshot().source();
    }

    @Override
    public void render2d(float td) {
        long ns = System.nanoTime();
        float dt = lastNs == 0 ? 0f : Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        AudioAnalyzer.Snapshot snap = AudioAnalyzer.get().snapshot();

        // Spotify gating + song-change reset
        NowPlaying now = SpotifyPoller.get().nowPlaying();
        boolean spotifyPlaying = now.playing();
        if (now.hasTrack() && (lastTrack == null || !now.sameTrack(lastTrack))) {
            if (lastTrack != null) AudioAnalyzer.get().reset();
            lastTrack = now;
        }
        boolean target;
        if (!snap.live()) target = spotifyPlaying; // simulated pulse only while Spotify really plays
        else target = !spotifyOnly.on() || spotifyPlaying;
        act += ((target ? 1f : 0f) - act) * (1f - (float) Math.exp(-dt * (target ? 4f : 1.6f)));

        // interpolate the ~94 Hz analysis frames to the frame rate
        float k = 1f - (float) Math.exp(-dt * 30f);
        float[] b = snap.bands();
        for (int i = 0; i < disp.length && i < b.length; i++) disp[i] += (b[i] - disp[i]) * k;
        System.arraycopy(disp, 0, MusicVisFx.bands, 0, disp.length);
        MusicVisFx.level += (snap.level() - MusicVisFx.level) * k;
        MusicVisFx.bass += (snap.bass() - MusicVisFx.bass) * k;
        MusicVisFx.treble += (snap.treble() - MusicVisFx.treble) * k;
        MusicVisFx.beat = Math.max(snap.beat(), MusicVisFx.beat * (float) Math.exp(-dt * 7f));

        // beat rings
        for (int i = 0; i < 4; i++) MusicVisFx.ringAge[i] += dt;
        if (lastBeatCount >= 0 && snap.beatCount() != lastBeatCount && act > 0.3f) {
            System.arraycopy(MusicVisFx.ringAge, 0, MusicVisFx.ringAge, 1, 3);
            MusicVisFx.ringAge[0] = 0f;
        }
        lastBeatCount = snap.beatCount();

        MusicVisFx.time += dt * (0.6f + 0.6f * MusicVisFx.level * act);
        MusicVisFx.activity = act;

        // colours
        switch (preset.get()) {
            case "Purple/Pink" -> { rgb(MusicVisFx.primary, 0xB44CFF); rgb(MusicVisFx.secondary, 0xFF4FB4); }
            case "Blue/Cyan" -> { rgb(MusicVisFx.primary, 0x3D6BFF); rgb(MusicVisFx.secondary, 0x2EF2FF); }
            default -> { rgb(MusicVisFx.primary, col1.rgb()); rgb(MusicVisFx.secondary, col2.rgb()); }
        }
        MusicVisFx.palette = preset.is("Rainbow") ? 1 : 0;
        MusicVisFx.gradient = gradient.on();

        String st = style.get();
        boolean all = st.equals("All");
        MusicVisFx.waves = all || st.equals("Neon Waves");
        MusicVisFx.eq = all || st.equals("Equalizer") || equalizer.on();
        MusicVisFx.aurora = all || st.equals("Aurora") || aurora.on();
        MusicVisFx.rings = all || st.equals("Pulse Rings") || rings.on();
        MusicVisFx.ribbon = all || st.equals("Spectrum Ribbon");
        MusicVisFx.particles = particles.on();
        MusicVisFx.height = waveHeight.f();
        MusicVisFx.speed = waveSpeed.f();
        MusicVisFx.thickness = thickness.f();
        MusicVisFx.waveCount = waveCount.i();
        MusicVisFx.glow = glow.f();
        MusicVisFx.skyHeight = (float) Math.toRadians(skyHeight.f());
        MusicVisFx.opacity = opacity.f() / 100f;
        MusicVisFx.on = MusicVisFx.opacity > 0f;
    }

    private static void rgb(float[] out, int c) {
        out[0] = ((c >> 16) & 255) / 255f;
        out[1] = ((c >> 8) & 255) / 255f;
        out[2] = (c & 255) / 255f;
    }
}

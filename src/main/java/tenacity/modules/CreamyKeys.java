package tenacity.modules;

import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import tenacity.Tenacity;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Creamy mechanical keyboard sounds. Real recorded switch packs (kbsim + Mechvibes, MIT - see assets/tenacity/keys/CREDITS.txt)
 * are loaded from the jar as mono WAV clips and played straight through OpenAL from a small source pool. The old procedural
 * synth profiles are kept as extra "Synth" profiles and as a fallback if a pack fails to load.
 */
public final class CreamyKeys extends Module {
    static CreamyKeys I;

    /** Recorded packs: resource folder id + display name. Order = profile order. */
    static final String[][] PACKS = {
            {"nkcream", "NK Cream"}, {"oreo", "Everglide Oreo"}, {"holypanda", "Holy Panda"}, {"alpaca", "Alpaca"},
            {"topre", "Topre"}, {"turquoise", "Turquoise Tealios"}, {"boxnavy", "Box Navy"}, {"mxblue", "MX Blue"},
            {"mxbrown", "MX Brown"}, {"mxblack", "MX Black"}, {"mxblackpbt", "MX Black PBT"}, {"bluealps", "Blue Alps"},
            {"buckling", "Buckling Spring"}, {"blackink", "Black Ink"}, {"redink", "Red Ink"},
    };
    static final String[] SYNTH_NAMES = {"Creamy", "Thocky", "Marble", "Cream Linear", "Clicky Blue", "Topre", "Bubble", "Typewriter", "Soft Pop", "Deep Thock"};
    static final String[] NAMES = new String[PACKS.length + SYNTH_NAMES.length];

    static {
        for (int i = 0; i < PACKS.length; i++) NAMES[i] = PACKS[i][1];
        for (int i = 0; i < SYNTH_NAMES.length; i++) NAMES[PACKS.length + i] = "Synth " + SYNTH_NAMES[i];
    }

    private final Setting.Mode profile = mode("Profile", "NK Cream", NAMES);
    private final Setting.Num volume = num("Volume", 70, 0, 100, 1);
    private final Setting.Num variance = num("Pitch Variance", 3, 0, 15, 0.5);
    private final Setting.Bool keyUp = bool("Key-up Sounds", true);
    private final Setting.Bool gameKeys = bool("Game Keys", true);
    private final Setting.Bool movement = bool("Movement Keys", true).showIf(gameKeys::on);
    private final Setting.Bool typingOnly = bool("Only While Typing", false);

    /** Recorded mouse packs (Kenney CC0 - see assets/tenacity/mice/CREDITS.txt). */
    static final String[][] MICE = {{"gaming", "Gaming Mouse"}, {"clicky", "Clicky Optical"}, {"office", "Office Mouse"},
            {"silent", "Silent Click"}, {"creamy", "Creamy"}};
    static final String[] MICE_NAMES = {"Gaming Mouse", "Clicky Optical", "Office Mouse", "Silent Click", "Creamy"};

    private final Setting.Bool mouseSounds = bool("Mouse Sounds", true);
    private final Setting.Mode mouseProfile = mode("Mouse Profile", "Gaming Mouse", MICE_NAMES).showIf(mouseSounds::on);
    private final Setting.Num mouseVolume = num("Mouse Volume", 60, 0, 100, 1).showIf(mouseSounds::on);
    private final Setting.Bool scrollSounds = bool("Scroll Sounds", true).showIf(mouseSounds::on);
    private final Setting.Bool mouseRelease = bool("Release Sounds", true).showIf(mouseSounds::on);
    private final Setting.Bool mouseMenusOnly = bool("Only In Menus", false).showIf(mouseSounds::on);

    public CreamyKeys() {
        super("Creamy Keys", "Real recorded mechanical keyboard sounds on every key press", Category.RENDER);
        I = this;
        Synth.start();
        Packs.start();
    }

    @Override
    public String suffix() {
        return profile.get();
    }

    /* ------------------------------------------------------------------ hooks (from KeyboardSoundMixin) */

    static final int KEY = 0, SPACE = 1, ENTER = 2, BACK = 3, UP = 4;

    public static void onKey(long window, int action, int key) {
        CreamyKeys m = I;
        if (m == null || !m.enabled() || window != mc.getWindow().getHandle() || key == GLFW.GLFW_KEY_UNKNOWN) return;
        boolean back = key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE;
        if (action == GLFW.GLFW_REPEAT && !back) return;
        Screen s = mc.currentScreen;
        if (m.typingOnly.on() && (s == null || !typing(s))) return;
        if (s == null) {
            if (!m.gameKeys.on()) return;
            if (!m.movement.on() && isMovement(key)) return;
        }
        if (action == GLFW.GLFW_RELEASE) {
            if (m.keyUp.on()) m.play(UP);
            return;
        }
        m.play(switch (key) {
            case GLFW.GLFW_KEY_SPACE -> SPACE;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> ENTER;
            case GLFW.GLFW_KEY_BACKSPACE, GLFW.GLFW_KEY_DELETE -> BACK;
            default -> KEY;
        });
    }

    static final int M_LDOWN = 0, M_LUP = 1, M_RDOWN = 2, M_RUP = 3, M_MID = 4, M_SCROLL = 5;
    private static long lastScroll;
    private static int scrollBurst;

    public static void onMouseButton(long window, int button, int action) {
        CreamyKeys m = I;
        if (m == null || !m.enabled() || !m.mouseSounds.on() || window != mc.getWindow().getHandle()) return;
        if (m.mouseMenusOnly.on() && mc.currentScreen == null) return;
        boolean press = action == GLFW.GLFW_PRESS;
        if (!press && (action != GLFW.GLFW_RELEASE || !m.mouseRelease.on())) return;
        int kind = switch (button) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> press ? M_LDOWN : M_LUP;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> press ? M_RDOWN : M_RUP;
            default -> press ? M_MID : -1; // middle + side buttons: one click on press
        };
        if (kind >= 0) m.playMouse(kind, 1f);
    }

    public static void onMouseScroll(long window, double h, double v) {
        CreamyKeys m = I;
        if (m == null || !m.enabled() || !m.mouseSounds.on() || !m.scrollSounds.on() || window != mc.getWindow().getHandle()) return;
        if (m.mouseMenusOnly.on() && mc.currentScreen == null) return;
        if (h == 0 && v == 0) return;
        long now = System.nanoTime();
        long dt = now - lastScroll;
        // smooth rate limit: at most one notch every ~28 ms; fast spins get progressively quieter instead of machine-gunning
        if (dt < 28_000_000L) return;
        scrollBurst = dt < 90_000_000L ? Math.min(scrollBurst + 1, 6) : 0;
        lastScroll = now;
        m.playMouse(M_SCROLL, 1f - scrollBurst * 0.07f);
    }

    /** Typed characters are already covered by their key press; kept for the mixin hook. */
    public static void onChar(long window, int codepoint) {
    }

    private static boolean typing(Screen s) {
        return s instanceof ChatScreen || s.getFocused() instanceof TextFieldWidget || s instanceof net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen
                || s instanceof net.minecraft.client.gui.screen.ingame.BookEditScreen;
    }

    private static boolean isMovement(int key) {
        var o = mc.options;
        var in = new net.minecraft.client.input.KeyInput(key, 0, 0);
        return o.forwardKey.matchesKey(in) || o.backKey.matchesKey(in) || o.leftKey.matchesKey(in) || o.rightKey.matchesKey(in)
                || o.jumpKey.matchesKey(in) || o.sneakKey.matchesKey(in) || o.sprintKey.matchesKey(in);
    }

    /* ------------------------------------------------------------------ sample sets */

    /** One profile's clips: press variants + optional specials (null = use a press variant). */
    record Set(short[][] keys, short[] space, short[] enter, short[] back, short[] up, int rate) {
        short[] pick(int kind) {
            short[] s = switch (kind) {
                case SPACE -> space;
                case ENTER -> enter;
                case BACK -> back;
                case UP -> up;
                default -> null;
            };
            if (s == null && kind == UP) return null;
            return s != null ? s : keys[RND.nextInt(keys.length)];
        }
    }

    static final class Packs {
        static volatile Set[] sets; // index = pack index, entry null if it failed

        static void start() {
            Thread t = new Thread(() -> {
                Set[] out = new Set[PACKS.length];
                int ok = 0;
                for (int i = 0; i < PACKS.length; i++) {
                    try {
                        out[i] = load(PACKS[i][0]);
                        if (out[i] != null) ok++;
                    } catch (Throwable e) {
                        Tenacity.LOG.warn("creamy keys: failed to load pack {}", PACKS[i][0], e);
                    }
                }
                sets = out;
                Tenacity.LOG.info("Creamy Keys: loaded {}/{} recorded keyboard packs", ok, PACKS.length);
                short[][][][] mo = new short[MICE.length][][][];
                int mok = 0;
                for (int i = 0; i < MICE.length; i++) {
                    try {
                        mo[i] = loadMouse(MICE[i][0]);
                        if (mo[i] != null) mok++;
                    } catch (Throwable e) {
                        Tenacity.LOG.warn("creamy keys: failed to load mouse pack {}", MICE[i][0], e);
                    }
                }
                mice = mo;
                Tenacity.LOG.info("Creamy Keys: loaded {}/{} recorded mouse packs", mok, MICE.length);
            }, "Tenacity-KeyPacks");
            t.setDaemon(true);
            t.start();
        }

        static volatile short[][][][] mice; // [pack][kind][variant] (44.1 kHz), entry null if it failed
        static final String[] MOUSE_FILES = {"ldown", "lup", "rdown", "rup", "mid", "scroll"};

        static short[][][] loadMouse(String id) throws Exception {
            String base = "/assets/tenacity/mice/" + id + "/";
            short[][][] out = new short[MOUSE_FILES.length][][];
            int[] rate = {44100};
            for (int k = 0; k < MOUSE_FILES.length; k++) {
                List<short[]> l = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    short[] s = wav(base + MOUSE_FILES[k] + i + ".wav", rate);
                    if (s == null) break;
                    l.add(s);
                }
                out[k] = l.toArray(new short[0][]);
            }
            return out[M_LDOWN].length == 0 ? null : out;
        }

        static Set load(String id) throws Exception {
            String base = "/assets/tenacity/keys/" + id + "/";
            List<short[]> keys = new ArrayList<>();
            int[] rate = {44100};
            for (int i = 0; i < 16; i++) {
                short[] s = wav(base + "key" + i + ".wav", rate);
                if (s == null) break;
                keys.add(s);
            }
            if (keys.isEmpty()) return null;
            return new Set(keys.toArray(new short[0][]), wav(base + "space.wav", rate), wav(base + "enter.wav", rate),
                    wav(base + "back.wav", rate), wav(base + "up.wav", rate), rate[0]);
        }

        /** Minimal RIFF/WAVE reader for mono 16-bit PCM. Returns null if the resource is missing. */
        static short[] wav(String path, int[] rate) throws Exception {
            byte[] data;
            try (InputStream in = CreamyKeys.class.getResourceAsStream(path)) {
                if (in == null) return null;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                in.transferTo(bo);
                data = bo.toByteArray();
            }
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) throw new IllegalStateException("not a wav: " + path);
            int p = 12, channels = 1, bits = 16;
            while (p + 8 <= data.length) {
                int id = b.getInt(p), len = b.getInt(p + 4);
                if (id == 0x20746d66) { // "fmt "
                    channels = b.getShort(p + 10);
                    rate[0] = b.getInt(p + 12);
                    bits = b.getShort(p + 22);
                } else if (id == 0x61746164) { // "data"
                    if (channels != 1 || bits != 16) throw new IllegalStateException("need mono 16-bit: " + path);
                    int n = Math.min(len, data.length - p - 8) / 2;
                    short[] s = new short[n];
                    for (int i = 0; i < n; i++) s[i] = b.getShort(p + 8 + i * 2);
                    return s;
                }
                p += 8 + len + (len & 1);
            }
            throw new IllegalStateException("no data chunk: " + path);
        }
    }

    /* ------------------------------------------------------------------ playback */

    private static final Random RND = new Random();
    private static final int POOL = 16;
    private static int[] sources;
    private static final java.util.Map<short[], Integer> BUFS = new java.util.IdentityHashMap<>();
    private static long ctx;
    private static int next;
    private static Set[] synthSets;

    private Set current() {
        int p = Math.max(0, Math.min(NAMES.length - 1, profile.index));
        Set[] packs = Packs.sets;
        if (p < PACKS.length && packs != null && packs[p] != null) return packs[p];
        if (!Synth.ready()) return null;
        if (synthSets == null) {
            Set[] ss = new Set[SYNTH_NAMES.length];
            for (int i = 0; i < ss.length; i++) {
                short[][] pc = Synth.pcm[i];
                short[][] keys = new short[Synth.KEYS][];
                System.arraycopy(pc, Synth.KEY0, keys, 0, Synth.KEYS);
                ss[i] = new Set(keys, pc[Synth.SPACE], pc[Synth.ENTER], pc[Synth.BACK], pc[Synth.UP], Synth.RATE);
            }
            synthSets = ss;
        }
        // synth profile, or the matching-ish synth fallback (Creamy) when a recorded pack is unavailable
        return synthSets[p >= PACKS.length ? p - PACKS.length : 0];
    }

    private void play(int kind) {
        Set set = current();
        if (set == null) return;
        float g = volume.f() / 100f * (0.88f + RND.nextFloat() * 0.12f);
        emit(set.pick(kind), set.rate(), g * g * (kind == UP ? 0.8f : 1.2f), variance.f() / 100f);
    }

    private void playMouse(int kind, float gainMul) {
        short[] pcm = null;
        int rate = 44100;
        short[][][][] mo = Packs.mice;
        int p = Math.max(0, Math.min(MICE.length - 1, mouseProfile.index));
        if (mo != null && mo[p] != null) {
            short[][] v = mo[p][kind];
            if (v.length == 0 && (kind == M_RDOWN || kind == M_MID)) v = mo[p][M_LDOWN];
            if (v.length == 0 && kind == M_RUP) v = mo[p][M_LUP];
            if (v.length > 0) pcm = v[RND.nextInt(v.length)];
        } else if (Synth.ready()) {
            // fallback: short synthesized clicks (Clicky Blue for presses, key-up tick for releases/scroll)
            short[][] pc = Synth.pcm[4];
            pcm = switch (kind) {
                case M_LUP, M_RUP, M_SCROLL -> pc[Synth.UP];
                default -> pc[Synth.KEY0 + RND.nextInt(Synth.KEYS)];
            };
            rate = Synth.RATE;
        }
        if (pcm == null) return;
        float g = mouseVolume.f() / 100f * (0.86f + RND.nextFloat() * 0.14f);
        float k = switch (kind) {
            case M_LUP, M_RUP -> 0.85f;
            case M_SCROLL -> 0.75f;
            default -> 1.15f;
        };
        emit(pcm, rate, g * g * k * Math.max(0.3f, gainMul), kind == M_SCROLL ? 0.05f : 0.03f);
    }

    private static void emit(short[] pcm, int rate, float gain, float var) {
        try {
            if (pcm == null || pcm.length == 0) return;
            long cur = ALC10.alcGetCurrentContext();
            if (cur == 0) return;
            if (cur != ctx || sources == null || !AL10.alIsSource(sources[0])) reset(cur);
            Integer buf = BUFS.get(pcm);
            if (buf == null) {
                buf = AL10.alGenBuffers();
                ShortBuffer sb = BufferUtils.createShortBuffer(pcm.length);
                sb.put(pcm).flip();
                AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, sb, rate);
                BUFS.put(pcm, buf);
            }
            int src = sources[next++ % POOL];
            AL10.alSourceStop(src);
            AL10.alSourcei(src, AL10.AL_BUFFER, buf);
            AL10.alSourcef(src, AL10.AL_PITCH, 1f + (RND.nextFloat() * 2f - 1f) * var);
            AL10.alSourcef(src, AL10.AL_GAIN, gain);
            AL10.alSourcePlay(src);
        } catch (Throwable t) {
            Tenacity.LOG.debug("creamy keys playback", t);
        }
    }

    /** (Re)creates the source pool for a new OpenAL context; buffers are uploaded lazily per clip. */
    private static void reset(long cur) {
        ctx = cur;
        BUFS.clear();
        sources = new int[POOL];
        for (int i = 0; i < POOL; i++) {
            int s = AL10.alGenSources();
            AL10.alSourcei(s, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(s, AL10.AL_POSITION, 0, 0, 0);
            AL10.alSourcef(s, AL10.AL_ROLLOFF_FACTOR, 0f);
            sources[i] = s;
        }
    }

    /* ------------------------------------------------------------------ synthesis (fallback / "Synth" profiles) */

    static final class Synth {
        static final int RATE = 44100;
        static final int KEYS = 5, KEY0 = 0, SPACE = 5, ENTER = 6, BACK = 7, UP = 8, COUNT = 9;
        static volatile short[][][] pcm;

        /**
         * clickAmp/clickFc/clickQ/clickDecay: filtered noise transient; body*: decaying partials; sweep: pitch drop of the
         * body (bubble/pop); lp: final low pass; dbl: delay of a second click (clicky switches) in ms; len seconds.
         */
        record P(float clickAmp, float clickFc, float clickQ, float clickDecay, float[] bodyF, float[] bodyA, float bodyDecay,
                 float sweep, float lp, float dbl, float len, float bottom) {
        }

        static final P[] PROFILES = {
                // Creamy: soft muted mid click with a warm rounded body
                new P(0.55f, 1900, 1.2f, 0.006f, new float[]{430, 860, 1290}, new float[]{1f, 0.35f, 0.12f}, 0.030f, 0.10f, 3800, 0, 0.16f, 0.5f),
                // Thocky: deep low body, little click
                new P(0.35f, 1200, 1.0f, 0.007f, new float[]{230, 470, 720}, new float[]{1f, 0.45f, 0.15f}, 0.045f, 0.18f, 2400, 0, 0.20f, 0.7f),
                // Marble: bright glassy clack with ringing high partials
                new P(0.9f, 3600, 2.5f, 0.004f, new float[]{1650, 2780, 4100}, new float[]{0.8f, 0.6f, 0.3f}, 0.022f, 0.0f, 9000, 0, 0.14f, 0.3f),
                // Cream Linear: smooth poppy mid
                new P(0.45f, 2300, 1.4f, 0.005f, new float[]{620, 1250}, new float[]{1f, 0.3f}, 0.026f, 0.12f, 4800, 0, 0.14f, 0.45f),
                // Clicky Blue: sharp double click
                new P(1.0f, 4300, 3.0f, 0.0025f, new float[]{2900, 900}, new float[]{0.5f, 0.35f}, 0.012f, 0.0f, 11000, 14, 0.12f, 0.35f),
                // Topre: rubbery dome thud
                new P(0.25f, 900, 0.9f, 0.009f, new float[]{360, 540}, new float[]{1f, 0.4f}, 0.040f, 0.30f, 2000, 0, 0.18f, 0.55f),
                // Bubble: rising pop like a bubble bursting
                new P(0.2f, 2600, 1.5f, 0.003f, new float[]{780, 1560}, new float[]{1f, 0.2f}, 0.035f, -0.55f, 5200, 0, 0.14f, 0.2f),
                // Typewriter: metallic strike + bell ring
                new P(1.0f, 3100, 2.2f, 0.006f, new float[]{1180, 2350, 3520, 4700}, new float[]{0.55f, 0.45f, 0.3f, 0.2f}, 0.070f, 0.0f, 10000, 22, 0.30f, 0.6f),
                // Soft Pop: tiny gentle pop
                new P(0.18f, 1600, 1.0f, 0.004f, new float[]{940}, new float[]{1f}, 0.018f, 0.45f, 3600, 0, 0.10f, 0.2f),
                // Deep Thock: very low, hollow
                new P(0.3f, 800, 0.9f, 0.010f, new float[]{165, 330, 505}, new float[]{1f, 0.5f, 0.2f}, 0.060f, 0.22f, 1700, 0, 0.24f, 0.8f),
        };

        static void start() {
            Thread t = new Thread(() -> {
                try {
                    short[][][] out = new short[PROFILES.length][COUNT][];
                    Random r = new Random(1337);
                    for (int p = 0; p < PROFILES.length; p++) {
                        P pr = PROFILES[p];
                        for (int k = 0; k < KEYS; k++) out[p][k] = render(pr, 0.94f + r.nextFloat() * 0.12f, 1f, 1f, false, r);
                        out[p][SPACE] = render(pr, 0.78f, 1.25f, 1.1f, true, r);
                        out[p][ENTER] = render(pr, 0.86f, 1.2f, 1.15f, true, r);
                        out[p][BACK] = render(pr, 1.07f, 0.95f, 1f, false, r);
                        out[p][UP] = renderUp(pr, r);
                    }
                    pcm = out;
                } catch (Throwable e) {
                    Tenacity.LOG.error("creamy keys synth failed", e);
                }
            }, "Tenacity-KeySynth");
            t.setDaemon(true);
            t.start();
        }

        static boolean ready() {
            return pcm != null;
        }

        /** Band pass biquad (constant 0 dB peak gain). */
        static final class BP {
            final float b0, b2, a1, a2;
            float x1, x2, y1, y2;

            BP(float fc, float q) {
                double w = 2 * Math.PI * Math.min(fc, RATE * 0.45) / RATE, al = Math.sin(w) / (2 * q), a0 = 1 + al;
                b0 = (float) (al / a0);
                b2 = (float) (-al / a0);
                a1 = (float) (-2 * Math.cos(w) / a0);
                a2 = (float) ((1 - al) / a0);
            }

            float f(float x) {
                float y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1;
                x1 = x;
                y2 = y1;
                y1 = y;
                return y;
            }
        }

        static short[] render(P p, float pitch, float lenMul, float gain, boolean rattle, Random r) {
            int n = (int) (p.len * lenMul * RATE);
            float[] s = new float[n];
            BP click = new BP(p.clickFc * pitch, p.clickQ), click2 = new BP(p.clickFc * pitch * 1.18f, p.clickQ);
            BP low = new BP(140 * pitch, 0.8f);
            double[] ph = new double[p.bodyF.length];
            float lpA = (float) (1 - Math.exp(-2 * Math.PI * p.lp / RATE)), lp = 0;
            int dbl = (int) (p.dbl * 0.001f * RATE);
            for (int i = 0; i < n; i++) {
                float t = i / (float) RATE;
                float atk = Math.min(1f, t / 0.0006f);
                float nz = r.nextFloat() * 2f - 1f;
                float v = click.f(nz) * p.clickAmp * 3f * (float) Math.exp(-t / p.clickDecay);
                if (dbl > 0 && i >= dbl) v += click2.f(r.nextFloat() * 2f - 1f) * p.clickAmp * 2.4f * (float) Math.exp(-(t - p.dbl * 0.001f) / (p.clickDecay * 0.9f));
                // bottom-out: a low thump from the plate/case
                v += low.f(nz) * p.bottom * 2.2f * (float) Math.exp(-t / 0.012f);
                float sw = 1f + p.sweep * (float) Math.exp(-t / 0.010f);
                for (int k = 0; k < ph.length; k++) {
                    ph[k] += 2 * Math.PI * p.bodyF[k] * pitch * sw / RATE;
                    v += (float) Math.sin(ph[k]) * p.bodyA[k] * 0.5f * (float) Math.exp(-t / (p.bodyDecay * lenMul / (1 + k * 0.6f)));
                }
                if (rattle) {
                    float rt = t - 0.009f;
                    if (rt > 0) v += (r.nextFloat() * 2f - 1f) * 0.12f * (float) Math.exp(-rt / 0.006f);
                }
                lp += (v - lp) * lpA;
                s[i] = lp * atk * gain;
            }
            return finish(s, 0.8f * gain / 1.15f);
        }

        /** The softer, higher key-up (stem returning) sound. */
        static short[] renderUp(P p, Random r) {
            int n = (int) (0.07f * RATE);
            float[] s = new float[n];
            BP click = new BP(p.clickFc * 1.25f, p.clickQ * 1.2f);
            double ph = 0;
            float f = p.bodyF[0] * 1.35f;
            for (int i = 0; i < n; i++) {
                float t = i / (float) RATE;
                float v = click.f(r.nextFloat() * 2f - 1f) * (0.4f + p.clickAmp) * 1.6f * (float) Math.exp(-t / 0.003f);
                ph += 2 * Math.PI * f / RATE;
                v += (float) Math.sin(ph) * 0.35f * (float) Math.exp(-t / 0.012f);
                s[i] = v * Math.min(1f, t / 0.0006f);
            }
            return finish(s, 0.32f);
        }

        static short[] finish(float[] s, float peak) {
            float m = 1e-6f;
            for (float v : s) m = Math.max(m, Math.abs(v));
            short[] o = new short[s.length];
            int fade = Math.min(s.length / 4, RATE / 200);
            for (int i = 0; i < s.length; i++) {
                float v = s[i] / m * peak;
                if (i > s.length - fade) v *= (s.length - i) / (float) fade;
                o[i] = (short) Math.max(-32767, Math.min(32767, v * 32767));
            }
            return o;
        }
    }
}

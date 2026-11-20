package tenacity.modules;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plays real recordings of real gaming mice (press and release in one take, see assets/tenacity/clicks/CREDITS.txt). Each click gets a
 * tiny random pitch and level change so repeated hits never sound identical.
 */
final class MouseClickSound {
    static final String[] TYPES = {"Logitech G203", "Logitech Superlight", "Razer DeathAdder V2 Pro", "Glorious Model O", "Bloody V8",
            "Rapture Venom", "IntelliMouse Optical", "Trust GXT 152"};
    private static final String[] FILES = {"logitech_g203", "logitech_superlight", "razer_deathadder_v2_pro", "glorious_model_o", "bloody_v8",
            "rapture_venom", "intellimouse", "trust_gxt_152"};
    private static final Random RNG = new Random();
    private static final ConcurrentHashMap<String, byte[]> RAW = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tenacity-click");
        t.setDaemon(true);
        return t;
    });

    private MouseClickSound() {
    }

    private static byte[] load(String type) {
        int idx = 0;
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(type)) idx = i;
        return RAW.computeIfAbsent(FILES[idx], f -> {
            try (InputStream in = MouseClickSound.class.getResourceAsStream("/assets/tenacity/clicks/" + f + ".wav")) {
                if (in == null) return new byte[0];
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                in.transferTo(bo);
                byte[] d = bo.toByteArray();
                // 44-byte canonical header written by the converter, mono 16-bit little endian
                byte[] pcm = new byte[Math.max(0, d.length - 44)];
                System.arraycopy(d, 44, pcm, 0, pcm.length);
                return pcm;
            } catch (Throwable t) {
                return new byte[0];
            }
        });
    }

    /** Loads the recording ahead of the first click, so the first one is not late. */
    static void warm(String type) {
        POOL.execute(() -> load(type));
    }

    static void play(String type, float volume) {
        if (volume <= 0.001f) return;
        POOL.execute(() -> {
            try {
                byte[] src = load(type);
                if (src.length < 4) return;
                float pitch = 0.98f + RNG.nextFloat() * 0.04f;
                float gain = volume * (0.9f + RNG.nextFloat() * 0.15f);
                int in = src.length / 2;
                int n = (int) (in / pitch);
                byte[] pcm = new byte[n * 2];
                for (int i = 0; i < n; i++) {
                    float pos = i * pitch;
                    int a = Math.min((int) pos, in - 1);
                    int b = Math.min(a + 1, in - 1);
                    float f = pos - a;
                    short sa = (short) ((src[2 * a] & 0xff) | (src[2 * a + 1] << 8));
                    short sb = (short) ((src[2 * b] & 0xff) | (src[2 * b + 1] << 8));
                    int s = (int) Math.max(-32767, Math.min(32767, (sa * (1 - f) + sb * f) * gain));
                    pcm[2 * i] = (byte) s;
                    pcm[2 * i + 1] = (byte) (s >> 8);
                }
                Clip clip = AudioSystem.getClip();
                clip.addLineListener(e -> {
                    if (e.getType() == LineEvent.Type.STOP) clip.close();
                });
                clip.open(new AudioFormat(44100f, 16, 1, true, false), pcm, 0, pcm.length);
                clip.start();
            } catch (Throwable ignored) {
            }
        });
    }
}

package tenacity.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * Real-time audio analysis of what the PC is playing, on a background daemon thread. Sources, in Auto order:
 * WASAPI loopback (true PCM -> 2048-pt Hann FFT -> log bands), WASAPI endpoint peak meter (envelope -> pseudo bands),
 * a javax.sound "Stereo Mix" line (PCM -> FFT), and finally a simulated pulse. The render thread reads the latest
 * immutable {@link Snapshot} through a volatile field (lock-free).
 */
public final class AudioAnalyzer {
    public static final int BANDS = 48;
    public static final String[] SOURCES = {"Auto", "Loopback", "Meter", "Fallback"};

    /** Immutable analysis frame. bands are 0..1 (attack/release smoothed), beat is 1 on onset decaying to 0. */
    public record Snapshot(float[] bands, float level, float bass, float mid, float treble, float beat, long beatCount, String source, boolean live, long nanos) {
    }

    private static final Snapshot EMPTY = new Snapshot(new float[BANDS], 0, 0, 0, 0, 0, 0, "Starting", false, 0);
    private static volatile AudioAnalyzer instance;

    private volatile Snapshot snapshot = EMPTY;
    private volatile int wantedSource; // index in SOURCES
    private volatile float sensitivity = 1f;
    private volatile boolean resetRequested;
    private volatile long lastUsedNs = System.nanoTime();
    private volatile String status = "Starting";
    private Thread thread;

    // ---- analysis state (analysis thread only)
    private static final int N = 2048, HOP = 512;
    private final float[] ring = new float[N];
    private int ringPos, sinceHop;
    private final float[] re = new float[N], im = new float[N], hann = new float[N];
    private final int[] bandLo = new int[BANDS], bandHi = new int[BANDS];
    private int bandRate;
    private final float[] smooth = new float[BANDS], prevLog = new float[BANDS], bandPeak = new float[BANDS];
    private final float[] fluxHist = new float[64];
    private int fluxPos;
    private float beat, levelS, bassS, midS, trebS, agc = 1e-3f, envAvg, envSlow;
    private long beatCount, lastBeatNs;
    private float rmsAgc;

    private AudioAnalyzer() {
        for (int i = 0; i < N; i++) hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
    }

    public static AudioAnalyzer get() {
        AudioAnalyzer a = instance;
        if (a == null) {
            synchronized (AudioAnalyzer.class) {
                if (instance == null) instance = new AudioAnalyzer();
                a = instance;
            }
        }
        a.lastUsedNs = System.nanoTime();
        a.ensureStarted();
        return a;
    }

    public Snapshot snapshot() {
        lastUsedNs = System.nanoTime();
        return snapshot;
    }

    public String status() {
        return status;
    }

    /** 0 Auto, 1 Loopback, 2 Meter, 3 Fallback (Stereo Mix, then simulated). Changing it restarts the source. */
    public void setSource(int idx) {
        wantedSource = idx;
    }

    public void setSensitivity(float s) {
        sensitivity = s;
    }

    /** Song changed: forget the adaptive gain / beat history so the new track is judged on its own. */
    public void reset() {
        resetRequested = true;
    }

    private synchronized void ensureStarted() {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(this::run, "Tenacity-AudioAnalyzer");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        thread.start();
    }

    /** Idle (no module reading) for this long -> the thread releases the device and exits. */
    private boolean idle() {
        return System.nanoTime() - lastUsedNs > 5_000_000_000L;
    }

    private void run() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) Com.init();
        try {
            while (!idle()) {
                int src = wantedSource;
                boolean ok = false;
                if (windows && (src == 0 || src == 1)) ok = runLoopback(src);
                if (!ok && windows && (src == 0 || src == 2)) ok = runMeter(src);
                if (!ok && (src == 0 || src == 3)) ok = runStereoMix(src);
                if (!ok) runSimulated(src);
            }
        } finally {
            if (windows) Com.uninit();
            snapshot = EMPTY;
        }
    }

    private boolean keepRunning(int src) {
        return wantedSource == src && !idle();
    }

    // ------------------------------------------------------------------ sources

    private boolean runLoopback(int src) {
        WasapiLoopback lb = new WasapiLoopback();
        try {
            lb.open();
        } catch (Throwable t) {
            lb.close();
            status = "Loopback failed: " + t.getMessage();
            return false;
        }
        String name = "Loopback " + lb.sampleRate / 1000 + "kHz";
        status = name;
        setupBands(lb.sampleRate);
        long lastData = System.nanoTime();
        long opened = lastData;
        try {
            while (keepRunning(src)) {
                int n = lb.drain(this::push);
                long now = System.nanoTime();
                if (n > 0) lastData = now;
                else if (now - lastData > 12_000_000L) {
                    // engine idle (nothing playing): loopback delivers no packets, feed silence to keep decaying
                    for (int i = 0; i < HOP; i++) push(0f);
                    lastData = now;
                }
                // hop-sized pushes publish via analyseFrame; the source label is attached there
                currentSource = name;
                // re-acquire the default device every 30 s in case the user switched outputs
                if (now - opened > 30_000_000_000L && n == 0) break;
                sleepMs(4);
            }
            return true;
        } catch (Throwable t) {
            status = "Loopback lost: " + t.getMessage();
            sleepMs(300);
            return true; // retry loop picks it up again
        } finally {
            lb.close();
        }
    }

    private boolean runMeter(int src) {
        WasapiLoopback.Meter m = new WasapiLoopback.Meter();
        try {
            m.open();
        } catch (Throwable t) {
            m.close();
            status = "Meter failed: " + t.getMessage();
            return false;
        }
        status = "Meter (peak envelope)";
        currentSource = "Meter";
        try {
            long next = System.nanoTime();
            while (keepRunning(src)) {
                analyseEnvelope(m.read(), 1f / 200f);
                next += 5_000_000L;
                long wait = next - System.nanoTime();
                if (wait > 0) sleepNs(wait);
                else next = System.nanoTime();
            }
            return true;
        } catch (Throwable t) {
            status = "Meter lost: " + t.getMessage();
            return true;
        } finally {
            m.close();
        }
    }

    private boolean runStereoMix(int src) {
        TargetDataLine line = null;
        try {
            AudioFormat fmt = new AudioFormat(44100f, 16, 2, true, false);
            for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
                String n = mi.getName().toLowerCase();
                if (!(n.contains("stereo mix") || n.contains("what u hear") || n.contains("loopback") || n.contains("monitor"))) continue;
                Mixer mixer = AudioSystem.getMixer(mi);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
                if (!mixer.isLineSupported(info)) continue;
                line = (TargetDataLine) mixer.getLine(info);
                line.open(fmt, 44100 / 10 * 4);
                line.start();
                status = "Stereo Mix (" + mi.getName() + ")";
                break;
            }
            if (line == null) {
                if (src == 3 || !status.startsWith("Loopback") && !status.startsWith("Meter")) status = "No Stereo Mix device";
                return false;
            }
            currentSource = "Stereo Mix";
            setupBands(44100);
            byte[] buf = new byte[HOP * 4];
            while (keepRunning(src)) {
                int r = line.read(buf, 0, buf.length);
                for (int i = 0; i + 3 < r; i += 4) {
                    short l = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8)), rr = (short) ((buf[i + 2] & 0xFF) | (buf[i + 3] << 8));
                    push((l + rr) / 65536f);
                }
            }
            return true;
        } catch (Throwable t) {
            status = "Stereo Mix failed: " + t.getMessage();
            return false;
        } finally {
            if (line != null) line.close();
        }
    }

    /** Last resort: a steady 120 BPM pulse; the module only shows it while Spotify reports playback. */
    private void runSimulated(int src) {
        String why = status;
        currentSource = "Simulated";
        status = "Simulated (no capture: " + why + ")";
        long t0 = System.nanoTime();
        while (keepRunning(src) && System.nanoTime() - t0 < 15_000_000_000L) {
            double t = (System.nanoTime() - t0) / 1e9;
            double ph = (t * 2.0) % 1.0;
            float env = (float) (0.25 + 0.6 * Math.exp(-ph * 6.0) + 0.1 * Math.sin(t * 0.7));
            analyseEnvelope(env, 0.01f);
            sleepMs(10);
        }
    }

    private volatile String currentSource = "Starting";

    // ------------------------------------------------------------------ FFT path

    private void setupBands(int rate) {
        bandRate = rate;
        double fLo = 32, fHi = Math.min(16000, rate / 2.0 - 100);
        int prevHi = 1;
        for (int b = 0; b < BANDS; b++) {
            double a = fLo * Math.pow(fHi / fLo, b / (double) BANDS), z = fLo * Math.pow(fHi / fLo, (b + 1) / (double) BANDS);
            int lo = Math.max(prevHi, (int) Math.floor(a * N / rate));
            int hi = Math.max(lo + 1, (int) Math.ceil(z * N / rate));
            bandLo[b] = lo;
            bandHi[b] = Math.min(hi, N / 2);
            prevHi = Math.max(1, (int) Math.floor(z * N / rate));
        }
    }

    private void push(float v) {
        ring[ringPos] = v;
        ringPos = (ringPos + 1) & (N - 1);
        if (++sinceHop >= HOP) {
            sinceHop = 0;
            analyseFrame((float) HOP / bandRate);
        }
    }

    private void analyseFrame(float dt) {
        checkReset();
        double sq = 0;
        for (int i = 0; i < N; i++) {
            float s = ring[(ringPos + i) & (N - 1)];
            re[i] = s * hann[i];
            im[i] = 0f;
            if (i >= N - HOP) sq += s * s;
        }
        float rms = (float) Math.sqrt(sq / HOP);
        Fft.fft(re, im);
        float[] raw = new float[BANDS];
        float frameMax = 0f;
        for (int b = 0; b < BANDS; b++) {
            float m = 0f;
            for (int k = bandLo[b]; k < bandHi[b]; k++) m = Math.max(m, re[k] * re[k] + im[k] * im[k]);
            // tilt: +3 dB/oct-ish so treble isn't buried under bass
            float mag = (float) Math.sqrt(m) * (1f + b * 0.06f);
            raw[b] = mag;
            frameMax = Math.max(frameMax, mag);
        }
        // AGC: fast rise, slow (~6 s) fall, floored so silence stays silent
        agc = Math.max(frameMax, agc * (float) Math.exp(-dt / 6.0));
        float norm = Math.max(agc, 20f);
        float flux = 0f;
        for (int b = 0; b < BANDS; b++) {
            // log scale over ~48 dB
            float db = (float) (20 * Math.log10(raw[b] / norm + 1e-6));
            float v = clamp01((db + 48f) / 48f);
            float lg = (float) Math.log1p(raw[b] * 20f / norm);
            float diff = lg - prevLog[b];
            if (diff > 0 && b < BANDS * 3 / 4) flux += diff * (b < 10 ? 1.6f : 1f);
            prevLog[b] = lg;
            float k = v > smooth[b] ? 1f - (float) Math.exp(-dt / 0.012) : 1f - (float) Math.exp(-dt / 0.16);
            smooth[b] += (v - smooth[b]) * k;
        }
        publishWithFlux(flux, rms, dt);
    }

    // ------------------------------------------------------------------ envelope path (meter / simulated)

    private void analyseEnvelope(float peak, float dt) {
        checkReset();
        agc = Math.max(peak, agc * (float) Math.exp(-dt / 6.0));
        float norm = Math.max(agc, 0.05f);
        float e = clamp01(peak / norm);
        envAvg += (e - envAvg) * (1f - (float) Math.exp(-dt / 0.05));
        envSlow += (e - envSlow) * (1f - (float) Math.exp(-dt / 0.6));
        float trans = Math.max(0f, e - envSlow); // fast energy above the slow average ~ low/percussive
        float sustain = envSlow;
        float flutter = Math.abs(e - envAvg); // fast wiggle ~ high-frequency content
        for (int b = 0; b < BANDS; b++) {
            float x = b / (float) (BANDS - 1);
            float v = trans * 1.6f * (1f - x) * (1f - x) + sustain * (0.75f - 0.35f * x) + flutter * 2.2f * x;
            v *= 0.85f + 0.15f * (float) Math.sin(b * 1.7f + bandPeak[0] * 3f);
            v = clamp01(v);
            float k = v > smooth[b] ? 1f - (float) Math.exp(-dt / 0.015) : 1f - (float) Math.exp(-dt / 0.18);
            smooth[b] += (v - smooth[b]) * k;
        }
        bandPeak[0] += dt;
        float flux = Math.max(0f, e - prevLog[0]) * 4f;
        prevLog[0] = e;
        publishWithFlux(flux, peak * 0.7f, dt);
    }

    // ------------------------------------------------------------------ onset + publish

    private void publishWithFlux(float flux, float rms, float dt) {
        // adaptive threshold: mean + k * std of the last ~0.7 s of flux
        float mean = 0f, var = 0f;
        for (float f : fluxHist) mean += f;
        mean /= fluxHist.length;
        for (float f : fluxHist) var += (f - mean) * (f - mean);
        float std = (float) Math.sqrt(var / fluxHist.length);
        fluxHist[fluxPos] = flux;
        fluxPos = (fluxPos + 1) % fluxHist.length;
        float k = 1.9f / Math.max(0.2f, sensitivity);
        long now = System.nanoTime();
        if (flux > mean + k * std + 0.05f && flux > 0.15f && now - lastBeatNs > 180_000_000L && levelS > 0.02f) {
            beat = 1f;
            beatCount++;
            lastBeatNs = now;
        } else beat *= (float) Math.exp(-dt / 0.14);

        rmsAgc = Math.max(rms, rmsAgc * (float) Math.exp(-dt / 8.0));
        float level = clamp01(rms / Math.max(rmsAgc, 0.03f));
        float bass = 0f, mid = 0f, treb = 0f;
        for (int b = 0; b < 10; b++) bass += smooth[b];
        for (int b = 10; b < 30; b++) mid += smooth[b];
        for (int b = 30; b < BANDS; b++) treb += smooth[b];
        float a = 1f - (float) Math.exp(-dt / 0.03), r = 1f - (float) Math.exp(-dt / 0.2);
        levelS += (level - levelS) * (level > levelS ? a : r);
        bass /= 10f;
        mid /= 20f;
        treb /= BANDS - 30;
        bassS += (bass - bassS) * (bass > bassS ? a : r);
        midS += (mid - midS) * (mid > midS ? a : r);
        trebS += (treb - trebS) * (treb > trebS ? a : r);
        boolean live = !"Simulated".equals(currentSource);
        snapshot = new Snapshot(smooth.clone(), levelS, bassS, midS, trebS, beat, beatCount, currentSource, live, now);
    }

    private void checkReset() {
        if (!resetRequested) return;
        resetRequested = false;
        agc = 1e-3f;
        rmsAgc = 0f;
        java.util.Arrays.fill(fluxHist, 0f);
        java.util.Arrays.fill(prevLog, 0f);
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void sleepNs(long ns) {
        try {
            Thread.sleep(ns / 1_000_000L, (int) (ns % 1_000_000L));
        } catch (InterruptedException ignored) {
        }
    }
}

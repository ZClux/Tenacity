package tenacity.audio;

/**
 * Standalone check: {@code java -cp <classes>;<jna>;<jna-platform> tenacity.audio.AudioCaptureTest [source] [seconds]}.
 * source 0 Auto, 1 Loopback, 2 Meter, 3 Fallback. Prints the source/status, level, bass and beat count 5x a second
 * and a verdict on whether any non-zero signal was observed. Also probes a raw loopback + meter directly.
 */
public final class AudioCaptureTest {
    public static void main(String[] args) throws Exception {
        int src = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int secs = args.length > 1 ? Integer.parseInt(args[1]) : 8;

        // raw probe, independent of the analyzer
        Thread probe = new Thread(() -> {
            Com.init();
            try {
                WasapiLoopback lb = new WasapiLoopback();
                lb.open();
                System.out.println("[probe] loopback open: " + lb.sampleRate + " Hz, " + lb.channels + " ch, " + lb.bits + " bit");
                float[] peak = {0};
                long[] count = {0};
                long end = System.nanoTime() + 1_500_000_000L;
                while (System.nanoTime() < end) {
                    lb.drain(v -> { peak[0] = Math.max(peak[0], Math.abs(v)); count[0]++; });
                    Thread.sleep(5);
                }
                lb.close();
                System.out.println("[probe] loopback 1.5 s: frames=" + count[0] + " peak=" + peak[0]);
            } catch (Throwable t) {
                System.out.println("[probe] loopback FAILED: " + t);
            }
            try {
                WasapiLoopback.Meter m = new WasapiLoopback.Meter();
                m.open();
                float mx = 0;
                for (int i = 0; i < 100; i++) { mx = Math.max(mx, m.read()); Thread.sleep(5); }
                m.close();
                System.out.println("[probe] endpoint meter 0.5 s peak=" + mx);
            } catch (Throwable t) {
                System.out.println("[probe] meter FAILED: " + t);
            }
            Com.uninit();
        });
        probe.start();
        probe.join();

        AudioAnalyzer a = AudioAnalyzer.get();
        a.setSource(src);
        float maxLevel = 0, maxBand = 0;
        long beats0 = -1, beats = 0;
        for (int i = 0; i < secs * 5; i++) {
            Thread.sleep(200);
            AudioAnalyzer.Snapshot s = a.snapshot();
            float mb = 0;
            for (float b : s.bands()) mb = Math.max(mb, b);
            maxLevel = Math.max(maxLevel, s.level());
            maxBand = Math.max(maxBand, mb);
            if (beats0 < 0) beats0 = s.beatCount();
            beats = s.beatCount();
            StringBuilder bar = new StringBuilder();
            for (int b = 0; b < AudioAnalyzer.BANDS; b += 3) bar.append(" .:-=+*#%@".charAt(Math.min(9, (int) (s.bands()[b] * 9.99f))));
            System.out.printf("%-34s level=%.3f bass=%.3f beat=%.2f beats=%d [%s]%n", a.status(), s.level(), s.bass(), s.beat(), s.beatCount(), bar);
        }
        System.out.printf("RESULT source=%s maxLevel=%.4f maxBand=%.4f beats=%d -> %s%n", a.snapshot().source(), maxLevel, maxBand, beats - Math.max(0, beats0),
                maxLevel > 0.01f || maxBand > 0.05f ? "SIGNAL DETECTED" : "SILENT (no audio playing, or capture not delivering)");
        System.exit(0);
    }
}

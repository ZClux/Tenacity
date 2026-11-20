package tenacity.audio;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

/**
 * WASAPI shared-mode loopback capture of the default render endpoint (what the speakers play), via raw COM through
 * JNA. Must be opened, read and closed on the same (COM-initialised) thread.
 */
final class WasapiLoopback {
    private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
    private static final int WAVE_FORMAT_PCM = 1, WAVE_FORMAT_IEEE_FLOAT = 3, WAVE_FORMAT_EXTENSIBLE = 0xFFFE;

    private Pointer device, client, capture;
    int sampleRate, channels, bits;
    private boolean isFloat;

    private final PointerByReference data = new PointerByReference();
    private final IntByReference frames = new IntByReference(), flags = new IntByReference(), packet = new IntByReference();
    private final LongByReference pos = new LongByReference(), qpc = new LongByReference();

    void open() {
        device = Com.defaultRenderDevice();
        client = Com.activate(device, Com.IID_IAudioClient);
        PointerByReference fmtRef = new PointerByReference();
        Com.check(Com.call(client, 8, fmtRef), "GetMixFormat");
        Pointer fmt = fmtRef.getValue();
        try {
            int tag = fmt.getShort(0) & 0xFFFF;
            channels = fmt.getShort(2) & 0xFFFF;
            sampleRate = fmt.getInt(4);
            bits = fmt.getShort(14) & 0xFFFF;
            if (tag == WAVE_FORMAT_EXTENSIBLE) {
                // SubFormat GUID Data1 at offset 24: 3 = IEEE float, 1 = PCM
                isFloat = fmt.getInt(24) == WAVE_FORMAT_IEEE_FLOAT;
            } else isFloat = tag == WAVE_FORMAT_IEEE_FLOAT;
            if (!isFloat && !(tag == WAVE_FORMAT_PCM || tag == WAVE_FORMAT_EXTENSIBLE)) throw new IllegalStateException("unsupported mix format tag " + tag);
            if (!isFloat && bits != 16 && bits != 24 && bits != 32) throw new IllegalStateException("unsupported bit depth " + bits);
            // 200 ms shared buffer, loopback flag; uses the engine's mix format directly
            Com.check(Com.call(client, 3, 0, AUDCLNT_STREAMFLAGS_LOOPBACK, 2_000_000L, 0L, fmt, Pointer.NULL), "IAudioClient::Initialize(loopback)");
        } finally {
            Com.taskFree(fmt);
        }
        PointerByReference cap = new PointerByReference();
        Com.check(Com.call(client, 14, Com.guid(Com.IID_IAudioCaptureClient), cap), "GetService(IAudioCaptureClient)");
        capture = cap.getValue();
        Com.check(Com.call(client, 10), "IAudioClient::Start");
    }

    /**
     * Drains all available packets into {@code sink} as mono floats. Returns frames read (0 when the engine is idle,
     * which is how loopback reports silence). Throws on device loss so the caller can reopen.
     */
    int drain(Sink sink) {
        int total = 0;
        while (true) {
            Com.check(Com.call(capture, 5, packet), "GetNextPacketSize");
            if (packet.getValue() == 0) return total;
            int hr = Com.call(capture, 3, data, frames, flags, pos, qpc);
            Com.check(hr, "GetBuffer");
            int n = frames.getValue();
            if (hr == 0x08890001 /* AUDCLNT_S_BUFFER_EMPTY */ || n == 0) {
                Com.call(capture, 4, 0);
                return total;
            }
            boolean silent = (flags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
            Pointer p = data.getValue();
            int bytesPer = bits / 8, block = bytesPer * channels;
            for (int f = 0; f < n; f++) {
                float acc = 0f;
                if (!silent) {
                    long base = (long) f * block;
                    for (int c = 0; c < channels; c++) {
                        long o = base + (long) c * bytesPer;
                        float v;
                        if (isFloat) v = p.getFloat(o);
                        else if (bits == 16) v = p.getShort(o) / 32768f;
                        else if (bits == 24) v = (((p.getByte(o + 2) << 16) | ((p.getByte(o + 1) & 0xFF) << 8) | (p.getByte(o) & 0xFF))) / 8388608f;
                        else v = p.getInt(o) / 2147483648f;
                        acc += v;
                    }
                    acc /= channels;
                }
                sink.sample(acc);
            }
            Com.check(Com.call(capture, 4, n), "ReleaseBuffer");
            total += n;
        }
    }

    void close() {
        if (client != null) {
            try {
                Com.call(client, 11);
            } catch (Throwable ignored) {
            }
        }
        Com.release(capture);
        Com.release(client);
        Com.release(device);
        capture = client = device = null;
    }

    interface Sink {
        void sample(float v);
    }

    /** Endpoint peak meter (IAudioMeterInformation) - the fallback when loopback can't be initialised. */
    static final class Meter {
        private Pointer device, meter;
        private final com.sun.jna.ptr.FloatByReference peak = new com.sun.jna.ptr.FloatByReference();

        void open() {
            device = Com.defaultRenderDevice();
            meter = Com.activate(device, Com.IID_IAudioMeterInformation);
            read();
        }

        float read() {
            Com.check(Com.call(meter, 3, peak), "GetPeakValue");
            return peak.getValue();
        }

        void close() {
            Com.release(meter);
            Com.release(device);
            meter = device = null;
        }
    }
}

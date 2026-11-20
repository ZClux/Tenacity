package tenacity.audio;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.ptr.PointerByReference;

/** Minimal raw COM helpers (vtable calls through JNA) for the Core Audio interfaces. */
final class Com {
    static final String CLSID_MMDeviceEnumerator = "{BCDE0395-E52F-467C-8E3D-C4579291692E}";
    static final String IID_IMMDeviceEnumerator = "{A95664D2-9614-4F35-A746-DE8DB63617E6}";
    static final String IID_IAudioClient = "{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}";
    static final String IID_IAudioCaptureClient = "{C8ADBD64-E71E-48A0-A4DE-185C395CD317}";
    static final String IID_IAudioMeterInformation = "{C02216F6-8C67-4B5B-9D00-D008E73E0064}";
    static final int CLSCTX_ALL = 0x17;

    private static NativeLibrary ole32;

    private Com() {
    }

    static Pointer guid(String s) {
        Guid.GUID g = new Guid.GUID(s);
        g.write();
        return g.getPointer();
    }

    private static NativeLibrary ole() {
        if (ole32 == null) ole32 = NativeLibrary.getInstance("ole32");
        return ole32;
    }

    /** CoInitializeEx(MTA) for the calling thread; S_FALSE / RPC_E_CHANGED_MODE are fine. */
    static void init() {
        ole().getFunction("CoInitializeEx").invokeInt(new Object[]{Pointer.NULL, 0});
    }

    static void uninit() {
        try {
            ole().getFunction("CoUninitialize").invokeVoid(new Object[0]);
        } catch (Throwable ignored) {
        }
    }

    static void taskFree(Pointer p) {
        if (p != null) ole().getFunction("CoTaskMemFree").invokeVoid(new Object[]{p});
    }

    static int call(Pointer self, int slot, Object... args) {
        Pointer vt = self.getPointer(0);
        Function f = Function.getFunction(vt.getPointer((long) slot * Native.POINTER_SIZE), Function.ALT_CONVENTION);
        Object[] full = new Object[args.length + 1];
        full[0] = self;
        System.arraycopy(args, 0, full, 1, args.length);
        return f.invokeInt(full);
    }

    static void release(Pointer p) {
        if (p != null) {
            try {
                call(p, 2);
            } catch (Throwable ignored) {
            }
        }
    }

    static void check(int hr, String what) {
        if (hr < 0) throw new IllegalStateException(what + " failed: 0x" + Integer.toHexString(hr));
    }

    /** Default render (speakers) endpoint, eConsole role. */
    static Pointer defaultRenderDevice() {
        PointerByReference en = new PointerByReference();
        check(ole().getFunction("CoCreateInstance").invokeInt(new Object[]{guid(CLSID_MMDeviceEnumerator), Pointer.NULL, CLSCTX_ALL, guid(IID_IMMDeviceEnumerator), en}), "CoCreateInstance(MMDeviceEnumerator)");
        Pointer enumerator = en.getValue();
        try {
            PointerByReference dev = new PointerByReference();
            check(call(enumerator, 4, 0, 0, dev), "GetDefaultAudioEndpoint");
            return dev.getValue();
        } finally {
            release(enumerator);
        }
    }

    static Pointer activate(Pointer device, String iid) {
        PointerByReference out = new PointerByReference();
        check(call(device, 3, guid(iid), CLSCTX_ALL, Pointer.NULL, out), "IMMDevice::Activate");
        return out.getValue();
    }
}

package tenacity.audio;

/** In-place iterative radix-2 complex FFT (length must be a power of two). Twiddles cached per size. */
final class Fft {
    private static int cachedN;
    private static float[] cos, sin;
    private static int[] rev;

    private Fft() {
    }

    private static void prepare(int n) {
        if (n == cachedN) return;
        int bits = Integer.numberOfTrailingZeros(n);
        rev = new int[n];
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        cos = new float[n / 2];
        sin = new float[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cos[i] = (float) Math.cos(-2 * Math.PI * i / n);
            sin[i] = (float) Math.sin(-2 * Math.PI * i / n);
        }
        cachedN = n;
    }

    static void fft(float[] re, float[] im) {
        int n = re.length;
        prepare(n);
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1, step = n / size;
            for (int s = 0; s < n; s += size) {
                for (int k = 0; k < half; k++) {
                    float wr = cos[k * step], wi = sin[k * step];
                    int a = s + k, b = a + half;
                    float xr = re[b] * wr - im[b] * wi, xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
    }
}

package tenacity.modules;

import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;

import java.util.Locale;

/** Animated scrambled glyphs instead of names (hacker decode effect). */
public final class NameScrambler extends Module {
    public static NameScrambler I;

    final Setting.Mode scope = mode("Scope", "Everyone", "Self", "Others", "Everyone", "Chat Only");
    final Setting.Mode style = mode("Style", "Random Glyphs", "Random Glyphs", "Shuffle", "Obfuscated");
    final Setting.Mode charset = mode("Character Set", "Letters", "Letters", "Symbols", "Katakana", "Binary", "Mixed");
    final Setting.Num speed = num("Scramble Speed", 12, 1, 60, 1);
    final Setting.Bool reveal = bool("Reveal On Aim", true);

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String SYMBOLS = "#$%&*+-/<=>?@^~|!:;\u00a7\u00b1\u00d7\u00f7\u2022\u00b7";
    private static final String KATAKANA = "\u30a2\u30a4\u30a6\u30a8\u30aa\u30ab\u30ad\u30af\u30b1\u30b3\u30b5\u30b7\u30b9\u30bb\u30bd\u30bf\u30c1\u30c4\u30c6\u30c8\u30ca\u30cb\u30cc\u30cd\u30ce\u30cf\u30d2\u30d5\u30d8\u30db\u30de\u30df\u30e0\u30e1\u30e2\u30e4\u30e6\u30e8\u30e9\u30ea\u30eb\u30ec\u30ed\u30ef\u30f2\u30f3";
    private static final String BINARY = "01";

    public NameScrambler() {
        super("Name Scrambler", "Scrambles names into constantly changing glyphs", Category.PLAYER);
        I = this;
    }

    @Override
    protected void onEnable() {
        NameCore.invalidate();
    }

    @Override
    protected void onDisable() {
        NameCore.invalidate();
    }

    @Override
    public String suffix() {
        return scope.get();
    }

    int hash() {
        return ((scope.index * 31 + style.index) * 31 + charset.index) * 31 + (reveal.value ? 1 : 0);
    }

    /** Cache bucket length, the scramble changes once per bucket. */
    long periodMs() {
        return Math.max(16L, (long) (1000.0 / Math.max(1, speed.value)));
    }

    boolean covers(NameCore.Target t) {
        return switch (scope.get()) {
            case "Self" -> t.self();
            case "Others" -> !t.self();
            default -> true;
        };
    }

    private String alphabet() {
        return switch (charset.get()) {
            case "Symbols" -> SYMBOLS;
            case "Katakana" -> KATAKANA;
            case "Binary" -> BINARY;
            case "Mixed" -> LETTERS + SYMBOLS + KATAKANA;
            default -> LETTERS;
        };
    }

    /** Deterministic for (name, frame) so repeated draws within one frame agree. */
    String scramble(String name, String original) {
        if (reveal.value && NameCore.aimedAt(name)) return original;
        long frame = System.currentTimeMillis() / periodMs();
        long seed = name.toLowerCase(Locale.ROOT).hashCode() * 0x9E3779B97F4A7C15L + frame * 0xBF58476D1CE4E5B9L;
        int n = original.length();
        char[] out = new char[n];
        String mode = style.get();
        if (mode.equals("Shuffle")) {
            System.arraycopy(original.toCharArray(), 0, out, 0, n);
            for (int i = n - 1; i > 0; i--) {
                seed = next(seed);
                int j = (int) ((seed >>> 33) % (i + 1));
                char c = out[i];
                out[i] = out[j];
                out[j] = c;
            }
            return new String(out);
        }
        String a = alphabet();
        if (mode.equals("Obfuscated")) {
            // vanilla's own animated obfuscation keeps the glyph widths, NanoVG just shows the letters
            for (int i = 0; i < n; i++) {
                seed = next(seed);
                out[i] = LETTERS.charAt((int) ((seed >>> 33) % LETTERS.length()));
            }
            return "\u00a7k" + new String(out) + "\u00a7r";
        }
        for (int i = 0; i < n; i++) {
            seed = next(seed);
            out[i] = a.charAt((int) ((seed >>> 33) % a.length()));
        }
        return new String(out);
    }

    private static long next(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x * 0x2545F4914F6CDD1DL;
    }
}

package tenacity.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

import java.util.function.BooleanSupplier;

/** A module option. Concrete kinds: {@link Bool}, {@link Num}, {@link Mode}, {@link Key}. */
public abstract class Setting {
    public final String name;
    private BooleanSupplier visible = () -> true;
    /** Animation state used by the click gui. */
    public float anim;

    protected Setting(String name) {
        this.name = name;
    }

    @SuppressWarnings("unchecked")
    public <T extends Setting> T showIf(BooleanSupplier s) {
        this.visible = s;
        return (T) this;
    }

    public boolean visible() {
        return visible.getAsBoolean();
    }

    abstract JsonElement save();

    abstract void load(JsonElement e);

    public static final class Bool extends Setting {
        public boolean value;

        public Bool(String name, boolean def) {
            super(name);
            this.value = def;
            this.anim = def ? 1f : 0f;
        }

        public boolean on() {
            return value;
        }

        public void toggle() {
            value = !value;
        }

        JsonElement save() {
            return new JsonPrimitive(value);
        }

        void load(JsonElement e) {
            value = e.getAsBoolean();
            anim = value ? 1f : 0f;
        }
    }

    public static final class Num extends Setting {
        public double value;
        public final double min, max, step;

        public Num(String name, double def, double min, double max, double step) {
            super(name);
            this.value = def;
            this.min = min;
            this.max = max;
            this.step = step;
        }

        public float f() {
            return (float) value;
        }

        public int i() {
            return (int) Math.round(value);
        }

        public void set(double v) {
            v = Math.max(min, Math.min(max, v));
            if (step > 0) v = Math.round((v - min) / step) * step + min;
            value = Math.max(min, Math.min(max, v));
        }

        public float fraction() {
            return (float) ((value - min) / (max - min));
        }

        public String display() {
            if (step >= 1) return Integer.toString((int) Math.round(value));
            return String.format(java.util.Locale.ROOT, step >= 0.1 ? "%.1f" : "%.2f", value);
        }

        JsonElement save() {
            return new JsonPrimitive(value);
        }

        void load(JsonElement e) {
            set(e.getAsDouble());
        }
    }

    /** ARGB colour, edited with hue and saturation bars in the click gui (alpha stays opaque). */
    public static final class Color extends Setting {
        public int value;

        public Color(String name, int def) {
            super(name);
            this.value = 0xFF000000 | (def & 0xFFFFFF);
        }

        public int rgb() {
            return value;
        }

        public void set(int argb) {
            value = 0xFF000000 | (argb & 0xFFFFFF);
        }

        public float hue() {
            float[] hsv = new float[3];
            java.awt.Color.RGBtoHSB((value >> 16) & 255, (value >> 8) & 255, value & 255, hsv);
            return hsv[0];
        }

        public float sat() {
            float[] hsv = new float[3];
            java.awt.Color.RGBtoHSB((value >> 16) & 255, (value >> 8) & 255, value & 255, hsv);
            return hsv[1];
        }

        public void setHsv(float h, float s) {
            set(java.awt.Color.HSBtoRGB((h % 1f + 1f) % 1f, Math.max(0f, Math.min(1f, s)), 1f));
        }

        JsonElement save() {
            return new JsonPrimitive(String.format(java.util.Locale.ROOT, "%08X", value));
        }

        void load(JsonElement e) {
            try {
                set((int) Long.parseLong(e.getAsString(), 16));
            } catch (Exception ignored) {
            }
        }
    }

    public static final class Mode extends Setting {
        public final String[] modes;
        public int index;
        /** Click GUI state of the dropdown. */
        public boolean open;
        public float openAnim, listScroll;

        public Mode(String name, String def, String... modes) {
            super(name);
            this.modes = modes;
            for (int i = 0; i < modes.length; i++) if (modes[i].equals(def)) index = i;
        }

        public String get() {
            return modes[index];
        }

        public boolean is(String m) {
            return modes[index].equals(m);
        }

        public void next(int dir) {
            index = (index + dir + modes.length) % modes.length;
        }

        JsonElement save() {
            return new JsonPrimitive(get());
        }

        void load(JsonElement e) {
            String s = e.getAsString();
            for (int i = 0; i < modes.length; i++) if (modes[i].equals(s)) index = i;
        }
    }

    /** Free text setting, edited inline in the click gui. */
    public static final class Text extends Setting {
        public String value;
        public final int maxLen;
        public boolean editing;
        /** Bumped on every change so consumers can invalidate caches cheaply. */
        public int rev;

        public Text(String name, String def, int maxLen) {
            super(name);
            this.value = def;
            this.maxLen = maxLen;
        }

        public String get() {
            return value;
        }

        public void set(String v) {
            if (v == null) v = "";
            if (v.length() > maxLen) v = v.substring(0, maxLen);
            if (!v.equals(value)) rev++;
            value = v;
        }

        JsonElement save() {
            return new JsonPrimitive(value);
        }

        void load(JsonElement e) {
            set(e.getAsString());
        }
    }

    /** Key bind setting; {@code -1} = unbound. Mouse buttons are stored as {@code -100 - button}. */
    public static final class Key extends Setting {
        public int code = -1;
        public boolean listening;

        public Key(String name, int def) {
            super(name);
            this.code = def;
        }

        public String display() {
            if (listening) return "...";
            if (code == -1) return "None";
            if (code <= -100) return "Mouse " + (-100 - code + 1);
            String n = org.lwjgl.glfw.GLFW.glfwGetKeyName(code, 0);
            if (n != null) return n.toUpperCase(java.util.Locale.ROOT);
            return switch (code) {
                case 32 -> "Space";
                case 340 -> "L-Shift";
                case 344 -> "R-Shift";
                case 341 -> "L-Ctrl";
                case 345 -> "R-Ctrl";
                case 342 -> "L-Alt";
                case 346 -> "R-Alt";
                case 258 -> "Tab";
                case 257 -> "Enter";
                case 259 -> "Backspace";
                case 280 -> "Caps";
                case 290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301 -> "F" + (code - 289);
                case 262 -> "Right";
                case 263 -> "Left";
                case 264 -> "Down";
                case 265 -> "Up";
                default -> "Key " + code;
            };
        }

        JsonElement save() {
            return new JsonPrimitive(code);
        }

        void load(JsonElement e) {
            code = e.getAsInt();
        }
    }
}

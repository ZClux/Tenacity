package tenacity.core;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    protected static final MinecraftClient mc = MinecraftClient.getInstance();

    public final String name;
    public final String description;
    public Category category;
    public final List<Setting> settings = new ArrayList<>();
    public final Setting.Key bind = new Setting.Key("Bind", -1);
    private boolean enabled;

    /* gui animation state */
    public float pressAnim, enabledAnim, expandAnim, hoverAnim;
    public boolean expanded;
    /** Smoothed 0..1 for the module list. */
    public float listAnim;

    protected Module(String name, String description, Category category) {
        this.name = name;
        this.description = description;
        this.category = category;
    }

    public <T extends Setting> T add(T s) {
        settings.add(s);
        return s;
    }

    public Setting.Bool bool(String n, boolean def) {
        return add(new Setting.Bool(n, def));
    }

    public Setting.Num num(String n, double def, double min, double max, double step) {
        return add(new Setting.Num(n, def, min, max, step));
    }

    public Setting.Mode mode(String n, String def, String... modes) {
        return add(new Setting.Mode(n, def, modes));
    }

    public Setting.Text text(String n, String def, int maxLen) {
        return add(new Setting.Text(n, def, maxLen));
    }

    public Setting.Color color(String n, int def) {
        return add(new Setting.Color(n, def));
    }

    public boolean enabled() {
        return enabled;
    }

    /** Modules that cannot be switched off (interface settings). */
    protected boolean locked;
    /** Set while the config loads so nothing is announced. */
    public static boolean quiet;

    public boolean locked() {
        return locked;
    }

    public void setEnabled(boolean on) {
        if (on == enabled || (locked && !on)) return;
        enabled = on;
        try {
            if (on) onEnable();
            else onDisable();
            if (!quiet && !locked && category != Category.HUD && tenacity.modules.Interface.I != null && tenacity.modules.Interface.I.notify.on())
                tenacity.ui.Toasts.push(category.icon, name + (on ? " enabled" : " disabled"), category.label);
        } catch (Throwable t) {
            tenacity.Tenacity.LOG.error("module {} failed", name, t);
        }
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    /** Silent enable used when loading the config. */
    void restore(boolean on) {
        enabled = on;
        enabledAnim = on ? 1f : 0f;
        listAnim = on ? 1f : 0f;
    }

    /** Shown next to the name in the module list. */
    public String suffix() {
        return "";
    }

    public boolean showInList() {
        return category != Category.HUD;
    }

    /** Extra persistent state (hud position...). */
    public void saveExtra(com.google.gson.JsonObject o) {
    }

    public void loadExtra(com.google.gson.JsonObject o) {
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    /** Start of the client tick, before the game handles input; only while enabled and in a world. */
    public void preTick() {
    }

    /** Client tick, only while enabled and in a world. */
    public void tick() {
    }

    /** In the world pass (before the hand), with the camera relative matrix stack (ported Crypto renderers). */
    public void renderWorld(voided.events.impl.WorldRenderEvent event) {
    }

    /** Per frame, inside the NanoVG frame (logical coordinates), only while enabled and in a world. */
    public void render2d(float tickDelta) {
    }

    /** True while a disabled module is still fading out and should keep receiving render calls. */
    public boolean lingers() {
        return false;
    }

    protected boolean inWorld() {
        return mc.player != null && mc.world != null;
    }
}

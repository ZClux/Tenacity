package tenacity.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import tenacity.Tenacity;
import tenacity.ui.ClickGui;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists modules, settings, hud positions and gui panel layout in config/tenacity.json. */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("tenacity.json");

    private Config() {
    }

    private static String key(Module m) {
        return m.category.name() + ":" + m.name + ":" + ModuleManager.all().subList(0, ModuleManager.all().indexOf(m)).stream().filter(x -> x.category == m.category && x.name.equals(m.name)).count();
    }

    /** Module states, binds, settings and extra state (hud positions). */
    public static JsonObject snapshotModules() {
        JsonObject mods = new JsonObject();
        for (Module m : ModuleManager.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("enabled", m.enabled());
            o.addProperty("bind", m.bind.code);
            JsonObject s = new JsonObject();
            for (Setting st : m.settings) s.add(st.name, st.save());
            o.add("settings", s);
            m.saveExtra(o);
            mods.add(key(m), o);
        }
        return mods;
    }

    public static void save() {
        try {
            JsonObject root = new JsonObject();
            root.add("modules", snapshotModules());
            JsonObject gui = new JsonObject();
            ClickGui.save(gui);
            root.add("gui", gui);
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
        } catch (Throwable t) {
            Tenacity.LOG.warn("could not save config", t);
        }
    }

    /**
     * Applies a module snapshot. With {@code full} every module is switched to the stored state (and modules missing
     * from the snapshot are switched off) - used by config profiles; the startup load only switches modules on.
     */
    public static void applyModules(JsonObject mods, boolean full) {
        boolean wasQuiet = Module.quiet;
        Module.quiet = true;
        try {
            for (Module m : ModuleManager.all()) {
                String key = key(m);
                if (!mods.has(key) || !mods.get(key).isJsonObject()) {
                    if (full && !m.locked()) m.setEnabled(false);
                    continue;
                }
                JsonObject o = mods.getAsJsonObject(key);
                try {
                    if (o.has("bind")) m.bind.code = o.get("bind").getAsInt();
                    if (o.has("settings")) {
                        JsonObject s = o.getAsJsonObject("settings");
                        for (Setting st : m.settings) {
                            JsonElement e = s.get(st.name);
                            if (e != null) st.load(e);
                        }
                    }
                    m.loadExtra(o);
                    boolean on = o.has("enabled") && o.get("enabled").getAsBoolean();
                    if (full) {
                        if (!m.locked()) m.setEnabled(on);
                    } else if (on) {
                        m.restore(true);
                        m.setEnabled(false); // force the real enable path so onEnable runs
                        m.setEnabled(true);
                    } else if (m.enabled() && !m.locked()) {
                        m.setEnabled(false); // saved as off: modules that start on by default (HUD widgets) stay off after a restart
                    }
                } catch (Throwable t) {
                    Tenacity.LOG.warn("bad config entry for {}", m.name, t);
                }
            }
        } finally {
            Module.quiet = wasQuiet;
        }
    }

    public static void load() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
            applyModules(root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject(), false);
            if (root.has("gui")) ClickGui.load(root.getAsJsonObject("gui"));
        } catch (Throwable t) {
            Tenacity.LOG.warn("could not read config", t);
        }
    }
}

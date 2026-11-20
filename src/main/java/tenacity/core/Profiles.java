package tenacity.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import tenacity.Tenacity;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** Named config profiles (config/tenacity/configs/&lt;name&gt;.json) and the cloud config client. */
public final class Profiles {
    /** Cloud endpoint - override with -Dtenacity.cloud=https://... */
    public static final String DEFAULT_ENDPOINT = "https://tenacity-configs.voided222333.workers.dev";
    public static final String ENDPOINT = System.getProperty("tenacity.cloud", DEFAULT_ENDPOINT).replaceAll("/+$", "");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("tenacity").resolve("configs");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final int MAX_ICON = 16 * 1024;

    private Profiles() {
    }

    /* ------------------------------------------------------------------ local */

    public record Local(String name, long modified, long size) {
    }

    public static String clean(String name) {
        String s = name == null ? "" : name.replaceAll("[^A-Za-z0-9 _.\\-]", "").trim();
        while (s.startsWith(".")) s = s.substring(1);
        return s.length() > 32 ? s.substring(0, 32).trim() : s;
    }

    private static Path file(String name) {
        return DIR.resolve(clean(name) + ".json");
    }

    public static List<Local> list() {
        List<Local> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) return out;
        try (Stream<Path> s = Files.list(DIR)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    String n = p.getFileName().toString();
                    out.add(new Local(n.substring(0, n.length() - 5), Files.getLastModifiedTime(p).toMillis(), Files.size(p)));
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            Tenacity.LOG.warn("could not list configs", e);
        }
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    public static boolean exists(String name) {
        return Files.exists(file(name));
    }

    /** The current setup as a portable profile object. */
    public static JsonObject snapshot(String name) {
        JsonObject root = new JsonObject();
        root.addProperty("tenacity", Tenacity.VERSION);
        root.addProperty("name", clean(name));
        root.addProperty("created", System.currentTimeMillis());
        root.add("modules", Config.snapshotModules());
        return root;
    }

    public static boolean save(String name) {
        name = clean(name);
        if (name.isEmpty()) return false;
        try {
            Files.createDirectories(DIR);
            Files.writeString(file(name), GSON.toJson(snapshot(name)), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            Tenacity.LOG.warn("could not save config {}", name, e);
            return false;
        }
    }

    public static JsonObject read(String name) {
        try {
            return JsonParser.parseString(Files.readString(file(name), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            Tenacity.LOG.warn("could not read config {}", name, e);
            return null;
        }
    }

    /** Applies a profile object (must run on the render thread) and persists it as the active autosave. */
    public static boolean apply(JsonObject root) {
        if (root == null || !root.has("modules") || !root.get("modules").isJsonObject()) return false;
        Config.applyModules(root.getAsJsonObject("modules"), true);
        Config.save();
        return true;
    }

    public static boolean load(String name) {
        return apply(read(name));
    }

    public static boolean delete(String name) {
        try {
            return Files.deleteIfExists(file(name));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean rename(String from, String to) {
        to = clean(to);
        if (to.isEmpty() || exists(to)) return false;
        try {
            JsonObject o = read(from);
            if (o == null) return false;
            o.addProperty("name", to);
            Files.writeString(file(to), GSON.toJson(o), StandardCharsets.UTF_8);
            Files.deleteIfExists(file(from));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean exportClipboard(String name) {
        JsonObject o = read(name);
        if (o == null) return false;
        MinecraftClient.getInstance().keyboard.setClipboard(new Gson().toJson(o));
        return true;
    }

    /** Imports clipboard json as a new local config, returns its name or null. */
    public static String importClipboard() {
        try {
            String clip = MinecraftClient.getInstance().keyboard.getClipboard();
            JsonObject o = JsonParser.parseString(clip.trim()).getAsJsonObject();
            if (!o.has("modules") || !o.get("modules").isJsonObject()) return null;
            String base = clean(o.has("name") ? o.get("name").getAsString() : "");
            if (base.isEmpty()) base = "Imported";
            String n = base;
            for (int i = 2; exists(n); i++) n = clean(base + " " + i);
            Files.createDirectories(DIR);
            o.addProperty("name", n);
            Files.writeString(file(n), GSON.toJson(o), StandardCharsets.UTF_8);
            return n;
        } catch (Exception e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ cloud */

    public static final class Cloud {
        public String id, name, author, description, server, version;
        public int downloads, likes;
        public long created;
        public byte[] icon;
        /** NanoVG image handle, created lazily on the render thread. -1 = not yet, 0 = none. */
        public int img = -1;
    }

    public record Page(List<Cloud> items, boolean more, int total) {
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static Cloud parse(JsonObject o) {
        Cloud c = new Cloud();
        c.id = str(o, "id");
        c.name = str(o, "name");
        c.author = str(o, "author");
        c.description = str(o, "description");
        c.server = str(o, "server");
        c.version = str(o, "version");
        c.downloads = o.has("downloads") ? o.get("downloads").getAsInt() : 0;
        c.likes = o.has("likes") ? o.get("likes").getAsInt() : 0;
        c.created = o.has("created") ? o.get("created").getAsLong() : 0;
        String ic = str(o, "icon");
        if (!ic.isEmpty()) {
            try {
                c.icon = Base64.getDecoder().decode(ic);
            } catch (Exception ignored) {
            }
        }
        return c;
    }

    private static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(ENDPOINT + path)).timeout(Duration.ofSeconds(15))
                .header("user-agent", "Tenacity/" + Tenacity.VERSION).header("content-type", "application/json");
    }

    private static JsonObject body(HttpResponse<String> r) {
        JsonObject o;
        try {
            o = JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("Bad response (" + r.statusCode() + ")");
        }
        if (r.statusCode() >= 400) throw new RuntimeException(o.has("error") ? o.get("error").getAsString() : "HTTP " + r.statusCode());
        return o;
    }

    public static CompletableFuture<Page> browse(String sort, String query, int page) {
        String q = "/configs?sort=" + sort + "&page=" + page + (query == null || query.isBlank() ? "" : "&q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        return HTTP.sendAsync(req(q).GET().build(), HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            JsonObject o = body(r);
            List<Cloud> items = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("items")) items.add(parse(e.getAsJsonObject()));
            return new Page(items, o.has("more") && o.get("more").getAsBoolean(), o.has("total") ? o.get("total").getAsInt() : items.size());
        });
    }

    /** Fetches the full config and bumps its download counter. */
    public static CompletableFuture<JsonObject> download(String id) {
        HTTP.sendAsync(req("/configs/" + id + "/download").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        return HTTP.sendAsync(req("/configs/" + id).GET().build(), HttpResponse.BodyHandlers.ofString()).thenApply(r -> {
            JsonObject o = body(r);
            JsonElement cfg = o.get("config");
            if (cfg == null || !cfg.isJsonObject()) throw new RuntimeException("Config is empty");
            return cfg.getAsJsonObject();
        });
    }

    public static CompletableFuture<Integer> like(String id) {
        return HTTP.sendAsync(req("/configs/" + id + "/like").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> body(r).get("likes").getAsInt());
    }

    /** "play.example.net" / "Singleplayer" / "Menu" for the session the upload comes from. */
    public static String currentServer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ServerInfo s = mc.getCurrentServerEntry();
        if (s != null) return s.address.toLowerCase(Locale.ROOT);
        return mc.isInSingleplayer() ? "Singleplayer" : "Menu";
    }

    public static byte[] currentIcon() {
        ServerInfo s = MinecraftClient.getInstance().getCurrentServerEntry();
        byte[] f = s == null ? null : s.getFavicon();
        return f != null && f.length <= MAX_ICON ? f : null;
    }

    /** Uploads the current setup (snapshot taken on the calling thread). Returns the new id. */
    public static CompletableFuture<String> upload(String name, String description) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("description", description);
        o.addProperty("author", MinecraftClient.getInstance().getSession().getUsername());
        o.addProperty("server", currentServer());
        byte[] icon = currentIcon();
        if (icon != null) o.addProperty("icon", Base64.getEncoder().encodeToString(icon));
        o.addProperty("version", Tenacity.VERSION);
        o.add("config", snapshot(name));
        return HTTP.sendAsync(req("/configs").POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(o))).build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> body(r).get("id").getAsString());
    }
}

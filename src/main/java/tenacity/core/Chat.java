package tenacity.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import tenacity.Tenacity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global Tenacity chat client. Everything network related runs on background threads; the render thread only reads
 * the (copy on write) message list and the volatile state flags.
 *
 * Identity: the server hands out a random challenge on connect. We call Mojang's joinServer with that challenge as
 * the server id and ask the worker to verify it through sessionserver hasJoined. If that fails (offline / cracked
 * account) we connect anyway and are shown as unverified.
 */
public final class Chat {
    public static final String DEFAULT_ENDPOINT = "https://tenacity-chat.voided222333.workers.dev";
    public static final String ENDPOINT = System.getProperty("tenacity.chat", DEFAULT_ENDPOINT).replaceAll("/+$", "");

    public static final class Msg {
        public long id, ts;
        public String user = "", uuid = "", text = "";
        public boolean verified, system, mine;
        /** Render state owned by the panel. */
        public float anim;
        public float h = -1, wrapW = -1;
        public List<String> lines;
    }

    public enum State { CONNECTING, ONLINE, POLLING, OFFLINE }

    public static final List<Msg> MESSAGES = new CopyOnWriteArrayList<>();
    public static volatile State state = State.OFFLINE;
    public static volatile int online;
    public static volatile boolean verified;
    public static volatile String lastError = "";
    public static volatile String myName = "";
    /** Unread count while the panel is closed. */
    public static volatile int unread;
    public static volatile boolean panelOpen;
    /** Queue of mention toasts for the render thread: {from, text}. */
    public static final List<String[]> MENTIONS = new CopyOnWriteArrayList<>();

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final AtomicInteger TID = new AtomicInteger();
    private static final ScheduledExecutorService EX = Executors.newScheduledThreadPool(2, (ThreadFactory) r -> {
        Thread t = new Thread(r, "Tenacity-Chat-" + TID.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private static volatile WebSocket ws;
    private static volatile boolean started, demo;
    private static volatile int gen;
    private static volatile long lastId;
    private static volatile long lastPing;
    private static int failures;
    private static final StringBuilder FRAME = new StringBuilder();

    private Chat() {
    }

    /* ------------------------------------------------------------------ identity */

    public static String realName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.getSession() == null ? "" : mc.getSession().getUsername();
    }

    private static UUID realUuid() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.getSession() == null ? null : mc.getSession().getUuidOrNull();
    }

    private static String uuidHex() {
        UUID u = realUuid();
        return u == null ? "0".repeat(32) : u.toString().replace("-", "");
    }

    private static boolean protectOn() {
        var p = tenacity.modules.NameProtect.I;
        return p != null && p.enabled();
    }

    /** Stable alias for a name: User + 4 hex of a hash of it (3-16 chars, [A-Za-z0-9_]). */
    public static String alias(String name) {
        int h = name.toLowerCase(Locale.ROOT).hashCode() * 0x9E3779B1;
        return "User" + String.format("%05x", (h >>> 8) & 0xFFFFF);
    }

    /* ------------------------------------------------------------------ settings (hide my name) */

    public static final String ANON_NAME = "Anonymous";
    private static final java.nio.file.Path SETTINGS = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("tenacity-chat.json");
    private static volatile boolean hideName, settingsLoaded;

    private static void loadSettings() {
        if (settingsLoaded) return;
        settingsLoaded = true;
        try {
            if (java.nio.file.Files.exists(SETTINGS)) {
                JsonObject o = JsonParser.parseString(java.nio.file.Files.readString(SETTINGS, StandardCharsets.UTF_8)).getAsJsonObject();
                hideName = o.has("hideName") && o.get("hideName").getAsBoolean();
            }
        } catch (Throwable t) {
            Tenacity.LOG.debug("chat settings", t);
        }
    }

    public static boolean hideName() {
        loadSettings();
        return hideName;
    }

    /** Toggles anonymity, persists it and reconnects so the server shows the new identity. */
    public static void setHideName(boolean v) {
        loadSettings();
        if (hideName == v) return;
        hideName = v;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("hideName", v);
            java.nio.file.Files.createDirectories(SETTINGS.getParent());
            java.nio.file.Files.writeString(SETTINGS, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Tenacity.LOG.warn("could not save chat settings", t);
        }
        if (started && !demo) {
            WebSocket w = ws;
            gen++;
            ws = null;
            if (w != null) try { w.abort(); } catch (Throwable ignored) { }
            failures = 0;
            MESSAGES.clear();
            lastId = 0;
            connect();
        }
    }

    /** Name used when talking to the server (hashed alias while Name Protect is on, and then never verified). */
    private static String sendName() {
        if (hideName()) return ANON_NAME;
        String real = realName();
        return protectOn() ? alias(real) : real;
    }

    /** Name shown in the client UI. */
    public static String display(Msg m) {
        if (m.mine && hideName()) return ANON_NAME;
        if (protectOn() && (m.mine || m.user.equalsIgnoreCase(realName()))) return alias(realName());
        return m.user;
    }

    /** Texts I sent while anonymous (with the time), so the echoed "Anonymous" message can be recognised as mine. */
    private static final java.util.concurrent.ConcurrentLinkedDeque<Object[]> SENT_ANON = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public static boolean isMine(String user) {
        String r = realName();
        return !r.isEmpty() && (user.equalsIgnoreCase(r) || user.equalsIgnoreCase(alias(r)));
    }

    private static boolean isMineAnon(Msg m) {
        if (!m.user.equals(ANON_NAME)) return false;
        long now = System.currentTimeMillis();
        SENT_ANON.removeIf(e -> now - (Long) e[1] > 60_000L);
        for (Object[] e : SENT_ANON) {
            if (((String) e[0]).equals(m.text) || ((String) e[0]).replaceAll("\\s+", " ").trim().equals(m.text)) {
                SENT_ANON.remove(e);
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ lifecycle */

    /** Starts the connection the first time the panel is used. Safe to call every frame. */
    public static void ensure() {
        if (started || demo) return;
        started = true;
        connect();
    }

    /** Dev harness: fills the list with fake messages without touching the network. */
    public static void demo() {
        if (demo) return;
        demo = true;
        started = true;
        gen++;
        state = State.ONLINE;
        online = 7;
        verified = true;
        myName = realName();
        long now = System.currentTimeMillis();
        String me = realName().isEmpty() ? "Player" : realName();
        String[][] rows = {
                {"Kryptic_", "00000000000000000000000000000001", "1", "welcome to the tenacity chat everyone", "-540"},
                {"Shatter", "069a79f444e94726a5befca90e38aaf5", "1", "anyone down for some crystal pvp on donut?", "-420"},
                {"xX_Nova_Xx", "853c80ef3c3749fdaa49938b674adae6", "0", "this is an unverified account so the check is missing", "-300"},
                {"Lumen", "61699b2ed3274a019f1e0ea8c3f06bc5", "1", "new config just dropped, check the cloud tab https://tenacity.dev/c/lumen", "-200"},
                {me, uuidHex(), "1", "nice, loading it now", "-120"},
                {"Kryptic_", "00000000000000000000000000000001", "1", "@" + me + " the long messages wrap onto several lines so you can read all of the text without it getting cut off at the edge of the panel", "-45"},
                {"Shatter", "069a79f444e94726a5befca90e38aaf5", "1", "gg", "-10"},
        };
        for (String[] r : rows) {
            Msg m = new Msg();
            m.id = MESSAGES.size() + 1;
            m.user = r[0];
            m.uuid = r[1];
            m.verified = r[2].equals("1");
            m.text = r[3];
            m.ts = now + Long.parseLong(r[4]) * 1000L;
            m.mine = isMine(m.user);
            m.anim = 1f;
            MESSAGES.add(m);
        }
    }

    private static String wsUrl() {
        return ENDPOINT.replaceFirst("^http", "ws") + "/ws";
    }

    private static void connect() {
        final int g = ++gen;
        state = State.CONNECTING;
        lastError = "";
        FRAME.setLength(0);
        try {
            HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).header("user-agent", "Tenacity/" + Tenacity.VERSION)
                    .buildAsync(URI.create(wsUrl()), new Listener(g)).whenComplete((sock, err) -> {
                        if (err != null && g == gen) fail(g, "Could not reach the chat server");
                    });
        } catch (Throwable t) {
            fail(g, "Could not reach the chat server");
        }
    }

    private static void fail(int g, String why) {
        if (g != gen) return;
        ws = null;
        online = 0;
        lastError = why;
        failures++;
        // after a couple of failed websocket attempts fall back to http polling while still retrying the socket
        state = failures >= 2 ? State.POLLING : State.OFFLINE;
        long delay = Math.min(60, (long) Math.pow(2, Math.min(6, failures))) * 1000L;
        EX.schedule(() -> {
            if (g == gen && !demo) connect();
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (failures >= 2) poll(g);
    }

    /* ------------------------------------------------------------------ http fallback */

    private static void poll(int g) {
        EX.schedule(() -> {
            if (g != gen || state != State.POLLING) return;
            HttpRequest r = HttpRequest.newBuilder(URI.create(ENDPOINT + "/history?since=" + lastId)).timeout(Duration.ofSeconds(8))
                    .header("user-agent", "Tenacity/" + Tenacity.VERSION).GET().build();
            HTTP.sendAsync(r, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
                if (g != gen || state != State.POLLING) return;
                try {
                    if (err == null && res.statusCode() == 200) {
                        JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                        online = o.has("online") ? o.get("online").getAsInt() : online;
                        JsonArray a = o.getAsJsonArray("messages");
                        for (JsonElement e : a) addMsg(parse(e.getAsJsonObject()), true);
                        lastError = "";
                    } else lastError = "Chat server unreachable";
                } catch (Throwable ignored) {
                }
                poll(g);
            });
        }, 3, java.util.concurrent.TimeUnit.SECONDS);
    }

    /* ------------------------------------------------------------------ websocket */

    private static final class Listener implements WebSocket.Listener {
        private final int g;

        Listener(int g) {
            this.g = g;
        }

        @Override
        public void onOpen(WebSocket w) {
            if (g != gen) {
                w.abort();
                return;
            }
            ws = w;
            w.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            if (g != gen) return null;
            FRAME.append(data);
            if (last) {
                String s = FRAME.toString();
                FRAME.setLength(0);
                try {
                    handle(w, JsonParser.parseString(s).getAsJsonObject());
                } catch (Throwable t) {
                    Tenacity.LOG.debug("chat frame", t);
                }
            }
            w.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
            if (g == gen) fail(g, code == 4003 ? "You are banned from chat" : "Disconnected");
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) {
            if (g == gen) fail(g, "Connection lost");
        }
    }

    private static void handle(WebSocket w, JsonObject o) {
        String t = o.has("t") ? o.get("t").getAsString() : "";
        switch (t) {
            case "hello" -> {
                String challenge = o.get("challenge").getAsString();
                final int g = gen;
                // joinServer is a blocking https call: never do it on the websocket thread
                EX.execute(() -> authenticate(g, w, challenge));
            }
            case "ready" -> {
                failures = 0;
                state = State.ONLINE;
                verified = o.has("verified") && o.get("verified").getAsBoolean();
                myName = o.has("user") ? o.get("user").getAsString() : myName;
                online = o.has("online") ? o.get("online").getAsInt() : online;
                lastError = "";
                MESSAGES.clear();
                lastId = 0;
                if (o.has("history")) for (JsonElement e : o.getAsJsonArray("history")) addMsg(parse(e.getAsJsonObject()), false);
                lastPing = System.currentTimeMillis();
                keepAlive(gen);
            }
            case "msg" -> addMsg(parse(o.getAsJsonObject("m")), true);
            case "online" -> online = o.get("n").getAsInt();
            case "del" -> {
                long id = o.get("id").getAsLong();
                MESSAGES.removeIf(m -> m.id == id);
            }
            case "err" -> {
                String msg = o.has("msg") ? o.get("msg").getAsString() : "Error";
                String code = o.has("code") ? o.get("code").getAsString() : "";
                sysMsg(code.equals("slow") ? "Slow down - you are sending too fast" : msg);
            }
            default -> {
            }
        }
    }

    private static void authenticate(int g, WebSocket w, String challenge) {
        boolean joined = false;
        String name = sendName();
        // a protected alias is not a real account name so it can never be verified
        final boolean anon = hideName();
        if (!protectOn() && !anon) {
            try {
                MinecraftClient mc = MinecraftClient.getInstance();
                var session = mc.getSession();
                UUID u = session.getUuidOrNull();
                String token = session.getAccessToken();
                if (u != null && token != null && !token.isEmpty() && !token.equals("0") && !token.equalsIgnoreCase("fml")) {
                    mc.getApiServices().sessionService().joinServer(u, token, challenge);
                    joined = true;
                }
            } catch (Throwable t) {
                Tenacity.LOG.debug("chat: joinServer failed, continuing unverified: {}", t.toString());
            }
        }
        if (g != gen) return;
        JsonObject a = new JsonObject();
        a.addProperty("t", "auth");
        a.addProperty("user", name);
        a.addProperty("uuid", protectOn() || anon ? "0".repeat(32) : uuidHex());
        a.addProperty("verify", joined);
        if (anon) a.addProperty("anon", true);
        w.sendText(GSON.toJson(a), true);
    }

    private static void keepAlive(int g) {
        EX.schedule(() -> {
            if (g != gen || state != State.ONLINE) return;
            WebSocket w = ws;
            if (w != null) w.sendText("{\"t\":\"ping\"}", true);
            keepAlive(g);
        }, 25, java.util.concurrent.TimeUnit.SECONDS);
    }

    /* ------------------------------------------------------------------ messages */

    private static Msg parse(JsonObject o) {
        Msg m = new Msg();
        m.id = o.get("id").getAsLong();
        m.ts = o.get("ts").getAsLong();
        m.user = o.get("user").getAsString();
        m.uuid = o.has("uuid") ? o.get("uuid").getAsString() : "";
        m.text = o.get("text").getAsString();
        m.verified = o.has("v") && o.get("v").getAsInt() == 1;
        m.mine = isMine(m.user) || isMineAnon(m);
        return m;
    }

    private static void addMsg(Msg m, boolean live) {
        if (m.id <= lastId && m.id != 0) return;
        for (Msg e : MESSAGES) if (!e.system && e.id == m.id) return;
        lastId = Math.max(lastId, m.id);
        if (!live) m.anim = 1f;
        MESSAGES.add(m);
        while (MESSAGES.size() > 300) MESSAGES.remove(0);
        if (live && !m.mine) {
            if (!panelOpen) unread++;
            String me = realName();
            String alias = alias(me);
            String low = m.text.toLowerCase(Locale.ROOT);
            if ((!me.isEmpty() && low.contains("@" + me.toLowerCase(Locale.ROOT))) || low.contains("@" + alias.toLowerCase(Locale.ROOT)))
                MENTIONS.add(new String[]{m.user, m.text});
        }
    }

    private static void sysMsg(String text) {
        Msg m = new Msg();
        m.system = true;
        m.text = text;
        m.ts = System.currentTimeMillis();
        MESSAGES.add(m);
    }

    /** Sends a message (never blocks). Returns false if it cannot be sent right now. */
    public static boolean send(String text) {
        text = text.trim();
        if (text.isEmpty()) return false;
        if (demo) {
            Msg m = new Msg();
            m.id = MESSAGES.size() + 1;
            m.user = hideName() ? ANON_NAME : realName().isEmpty() ? "Player" : realName();
            m.uuid = hideName() ? "0".repeat(32) : uuidHex();
            m.verified = !hideName();
            m.mine = true;
            m.text = text;
            m.ts = System.currentTimeMillis();
            MESSAGES.add(m);
            return true;
        }
        if (hideName()) SENT_ANON.add(new Object[]{text, System.currentTimeMillis()});
        WebSocket w = ws;
        if (state == State.ONLINE && w != null) {
            JsonObject o = new JsonObject();
            o.addProperty("t", "send");
            o.addProperty("text", text);
            w.sendText(GSON.toJson(o), true);
            return true;
        }
        if (state == State.POLLING) {
            postHttp(text);
            return true;
        }
        sysMsg("Not connected - reconnecting...");
        return false;
    }

    /** http fallback: ask for a challenge, join the session with it, then post. */
    private static void postHttp(String text) {
        final int g = gen;
        EX.execute(() -> {
            try {
                HttpResponse<String> c = HTTP.send(HttpRequest.newBuilder(URI.create(ENDPOINT + "/challenge")).timeout(Duration.ofSeconds(8)).GET().build(), HttpResponse.BodyHandlers.ofString());
                String challenge = JsonParser.parseString(c.body()).getAsJsonObject().get("challenge").getAsString();
                boolean joined = false;
                final boolean anon = hideName();
                if (!protectOn() && !anon) {
                    try {
                        MinecraftClient mc = MinecraftClient.getInstance();
                        var s = mc.getSession();
                        mc.getApiServices().sessionService().joinServer(s.getUuidOrNull(), s.getAccessToken(), challenge);
                        joined = true;
                    } catch (Throwable ignored) {
                    }
                }
                JsonObject o = new JsonObject();
                o.addProperty("user", sendName());
                o.addProperty("uuid", protectOn() || anon ? "0".repeat(32) : uuidHex());
                o.addProperty("text", text);
                o.addProperty("challenge", challenge);
                o.addProperty("verify", joined);
                if (anon) o.addProperty("anon", true);
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(ENDPOINT + "/send")).timeout(Duration.ofSeconds(10))
                        .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(o), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() >= 400) {
                    JsonObject e = JsonParser.parseString(r.body()).getAsJsonObject();
                    sysMsg(e.has("error") ? e.get("error").getAsString() : "Could not send");
                }
            } catch (Throwable t) {
                sysMsg("Could not send the message");
            }
        });
    }

    public static List<Msg> snapshot() {
        return new ArrayList<>(MESSAGES);
    }
}

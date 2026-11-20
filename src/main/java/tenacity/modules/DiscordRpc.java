package tenacity.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Discord Rich Presence over Discord's local IPC pipe (\\.\pipe\discord-ipc-N), no library needed. Shows the client name,
 * the Minecraft version, what you are doing (server / singleplayer / menu) and a button to the Discord server.
 */
public final class DiscordRpc {
    private static final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
    private static final String APP_ID = "1571593259044700332";
    /** Discord server invite for the "Join Discord" button; empty = no button. */
    static final String INVITE = "";

    private static RandomAccessFile pipe;
    private static Thread worker;
    private static volatile boolean running;
    private static long started;
    private static String lastState = "";

    private DiscordRpc() {
    }

    /** Always on: started once when the client loads, keeps reconnecting if Discord is closed / restarted. */
    public static void start() {
        if (running) return;
        started = System.currentTimeMillis() / 1000;
        running = true;
        worker = new Thread(DiscordRpc::loop, "Tenacity Discord RPC");
        worker.setDaemon(true);
        worker.start();
    }


    private static String details() {
        return "Tenacity Client · Minecraft " + net.minecraft.SharedConstants.getGameVersion().name();
    }

    private static String state() {
        if (mc.world == null) return "In the menus";
        if (mc.isInSingleplayer()) return "Singleplayer";
        var e = mc.getCurrentServerEntry();
        if (e == null) return "Multiplayer";
        return "Playing on " + e.address;
    }

    private static void loop() {
        while (running) {
            try {
                if (pipe == null) {
                    connect();
                    lastState = "";
                }
                String st = details() + "|" + state();
                if (!st.equals(lastState)) {
                    send(1, activity());
                    lastState = st;
                }
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                close(); // Discord not running / restarted: retry later
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private static void connect() throws Exception {
        Exception last = null;
        for (int i = 0; i < 10; i++) {
            try {
                pipe = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
                break;
            } catch (Exception e) {
                last = e;
            }
        }
        if (pipe == null) throw last;
        JsonObject hs = new JsonObject();
        hs.addProperty("v", 1);
        hs.addProperty("client_id", APP_ID);
        send(0, hs);
        read(); // READY
    }

    private static JsonObject activity() {
        JsonObject act = new JsonObject();
        act.addProperty("details", details());
        act.addProperty("state", state());
        {
            JsonObject ts = new JsonObject();
            ts.addProperty("start", started);
            act.add("timestamps", ts);
        }
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", "logo");
        assets.addProperty("large_text", "Tenacity Client");
        act.add("assets", assets);
        if (!INVITE.isEmpty()) {
            JsonArray buttons = new JsonArray();
            JsonObject b = new JsonObject();
            b.addProperty("label", "Join the Discord");
            b.addProperty("url", INVITE);
            buttons.add(b);
            act.add("buttons", buttons);
        }
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        args.add("activity", act);
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", UUID.randomUUID().toString());
        return cmd;
    }

    private static synchronized void send(int op, JsonObject payload) throws Exception {
        byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(op).putInt(data.length).put(data);
        pipe.write(buf.array());
    }

    private static void read() throws Exception {
        byte[] head = new byte[8];
        pipe.readFully(head);
        int len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
        pipe.readFully(new byte[len]);
    }

    private static void close() {
        try {
            if (pipe != null) pipe.close();
        } catch (Exception ignored) {
        }
        pipe = null;
    }
}

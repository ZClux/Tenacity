package tenacity.modules;

import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BeaconBlockEntity;
import net.minecraft.block.entity.BedBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.Icons;
import tenacity.gfx.Nvg;
import tenacity.ui.Theme;
import tenacity.ui.Toasts;
import tenacity.util.Projection;
import tenacity.util.StaffScan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Base finding and Donut tabs. Everything here reads block entities of the chunks the server already sent. */
public final class WorldModules {
    private WorldModules() {
    }

    static List<Module> baseFinding() {
        return List.of(new BaseFinder(), new StorageEsp());
    }

    static List<Module> donut() {
        return List.of(new SpawnerEsp(), new StaffAlert(), new AntiAfk());
    }

    /* ------------------------------------------------------------------ scanning */

    enum Kind {
        CHEST("Chest", 3, 0xFFFFC857), ENDER("Ender Chest", 1, 0xFFB388FF), BARREL("Barrel", 3, 0xFFD9A066),
        SHULKER("Shulker", 6, 0xFFFF7AD9), FURNACE("Furnace", 2, 0xFFB8C0CC), HOPPER("Hopper", 2, 0xFF9AA5B1),
        DISPENSER("Dispenser", 2, 0xFF9AA5B1), BED("Bed", 2, 0xFFFF6B6B), BEACON("Beacon", 10, 0xFF6EE7F9),
        BREWING("Brewing Stand", 2, 0xFFE9E58A), SIGN("Sign", 1, 0xFFE8DCC0), BANNER("Banner", 1, 0xFFE8DCC0),
        SPAWNER("Spawner", 0, 0xFF5EEAD4);

        final String label;
        final int weight;
        final int color;

        Kind(String label, int weight, int color) {
            this.label = label;
            this.weight = weight;
            this.color = color;
        }

        boolean storage() {
            return this == CHEST || this == ENDER || this == BARREL || this == SHULKER || this == HOPPER || this == DISPENSER || this == FURNACE;
        }
    }

    record Found(BlockPos pos, Kind kind) {
    }

    static Kind kindOf(BlockEntity be) {
        if (be instanceof ChestBlockEntity) return Kind.CHEST;
        if (be instanceof EnderChestBlockEntity) return Kind.ENDER;
        if (be instanceof BarrelBlockEntity) return Kind.BARREL;
        if (be instanceof ShulkerBoxBlockEntity) return Kind.SHULKER;
        if (be instanceof AbstractFurnaceBlockEntity) return Kind.FURNACE;
        if (be instanceof HopperBlockEntity) return Kind.HOPPER;
        if (be instanceof DispenserBlockEntity) return Kind.DISPENSER;
        if (be instanceof BedBlockEntity) return Kind.BED;
        if (be instanceof BeaconBlockEntity) return Kind.BEACON;
        if (be instanceof BrewingStandBlockEntity) return Kind.BREWING;
        if (be instanceof SignBlockEntity) return Kind.SIGN;
        if (be instanceof BannerBlockEntity) return Kind.BANNER;
        if (be instanceof MobSpawnerBlockEntity) return Kind.SPAWNER;
        return null;
    }

    static List<Found> scan(int radiusChunks) {
        List<Found> out = new ArrayList<>();
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return out;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        for (int cx = pcx - radiusChunks; cx <= pcx + radiusChunks; cx++) {
            for (int cz = pcz - radiusChunks; cz <= pcz + radiusChunks; cz++) {
                WorldChunk ch = mc.world.getChunkManager().getWorldChunk(cx, cz);
                if (ch == null) continue;
                for (BlockEntity be : ch.getBlockEntities().values()) {
                    Kind k = kindOf(be);
                    if (k != null) out.add(new Found(be.getPos(), k));
                }
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------ base finder */

    public record Base(double x, double y, double z, int score, int storage, int beds, int total, boolean underground) {
        public double distance() {
            var p = net.minecraft.client.MinecraftClient.getInstance().player;
            return p == null ? 0 : Math.sqrt((p.getX() - x) * (p.getX() - x) + (p.getZ() - z) * (p.getZ() - z));
        }
    }

    /** Clusters player placed block entities (storage, beds, furnaces...) and points at the busiest spots. */
    public static final class BaseFinder extends Module {
        public static BaseFinder I;
        private static volatile List<Base> results = List.of();

        private final Setting.Num range = num("Range", 12, 3, 24, 1);
        private final Setting.Num minScore = num("Min Score", 10, 3, 60, 1);
        private final Setting.Bool underground = bool("Underground Only", false);
        private final Setting.Bool markers = bool("Markers", true);
        private final Setting.Bool alerts = bool("Alerts", true);
        private int clock;
        private final Set<Long> announced = new HashSet<>();

        BaseFinder() {
            super("Base Finder", "Finds bases by clustering chests, barrels, beds and furnaces", Category.BASE);
            I = this;
        }

        public static List<Base> results() {
            return I != null && I.enabled() ? results : List.of();
        }

        @Override
        public String suffix() {
            return results.isEmpty() ? "" : Integer.toString(results.size());
        }

        @Override
        protected void onDisable() {
            results = List.of();
            announced.clear();
        }

        @Override
        public void tick() {
            if (clock++ % 30 != 0) return;
            Map<Long, double[]> clusters = new HashMap<>(); // sx, sy, sz, n, score, storage, beds
            for (Found f : scan(range.i())) {
                if (f.kind() == Kind.SPAWNER || f.kind().weight == 0) continue;
                BlockPos p = f.pos();
                long key = ((long) Math.floorDiv(p.getX(), 28) << 32) ^ (Math.floorDiv(p.getZ(), 28) & 0xffffffffL);
                double[] c = clusters.computeIfAbsent(key, k -> new double[7]);
                c[0] += p.getX() + 0.5;
                c[1] += p.getY();
                c[2] += p.getZ() + 0.5;
                c[3]++;
                c[4] += f.kind().weight;
                if (f.kind().storage()) c[5]++;
                if (f.kind() == Kind.BED) c[6]++;
            }
            List<Base> list = new ArrayList<>();
            for (var e : clusters.entrySet()) {
                double[] c = e.getValue();
                if (c[4] < minScore.value) continue;
                double y = c[1] / c[3];
                if (underground.on() && y > 50) continue;
                list.add(new Base(c[0] / c[3], y, c[2] / c[3], (int) c[4], (int) c[5], (int) c[6], (int) c[3], y < 50));
                if (alerts.on() && announced.add(e.getKey()))
                    Toasts.push("base", "Base found", (int) (c[0] / c[3]) + ", " + (int) y + ", " + (int) (c[2] / c[3]) + "  -  score " + (int) c[4]);
            }
            list.sort(Comparator.comparingInt(Base::score).reversed());
            results = list.size() > 10 ? List.copyOf(list.subList(0, 10)) : List.copyOf(list);
        }

        @Override
        public void render2d(float td) {
            if (!markers.on()) return;
            float[] o = new float[2];
            int i = 0;
            for (Base b : results) {
                if (i++ >= 6) break;
                Vec3d p = new Vec3d(b.x(), b.y() + 2, b.z());
                String label = (int) b.distance() + "m  |  " + b.score() + " pts";
                boolean on = Projection.point(p, o) && o[0] > 30 && o[0] < Nvg.w - 30 && o[1] > 30 && o[1] < Nvg.h - 30;
                if (!on) continue;
                float tw = Nvg.textWidth(label, 11.5f, Nvg.MED);
                float w = tw + 38, h = 26;
                float x = o[0] - w / 2f, y = o[1] - h / 2f;
                Theme.panel(x, y, w, h, 13, 1f, 10);
                Icons.draw("base", x + 8, y + h / 2f - 7, 14, Theme.text());
                Nvg.text(label, x + 27, y + h / 2f + 0.5f, 11.5f, Nvg.MED, Nvg.LEFT, Theme.text());
            }
        }
    }

    /* ------------------------------------------------------------------ storage esp */

    static final class StorageEsp extends Module {
        private final Setting.Num range = num("Range", 10, 2, 24, 1);
        private final Setting.Bool chests = bool("Chests", true);
        private final Setting.Bool barrels = bool("Barrels", true);
        private final Setting.Bool shulkers = bool("Shulkers", true);
        private final Setting.Bool other = bool("Furnaces & Hoppers", false);
        private final Setting.Bool tag = bool("Labels", true);
        private List<Found> found = List.of();
        private int clock;

        StorageEsp() {
            super("Container Scan", "Base-finding scan for chests, barrels and shulker boxes", Category.BASE);
        }

        @Override
        public void tick() {
            if (clock++ % 20 != 0) return;
            List<Found> out = new ArrayList<>();
            for (Found f : scan(range.i())) {
                boolean ok = switch (f.kind()) {
                    case CHEST, ENDER -> chests.on();
                    case BARREL -> barrels.on();
                    case SHULKER -> shulkers.on();
                    case FURNACE, HOPPER, DISPENSER -> other.on();
                    default -> false;
                };
                if (ok) out.add(f);
            }
            found = out;
        }

        @Override
        public void render2d(float td) {
            for (Found f : found) {
                float[] r = Projection.box(new Box(f.pos()));
                if (r == null) continue;
                float w = r[2] - r[0], h = r[3] - r[1];
                if (w < 2 || h < 2 || w > Nvg.w) continue;
                Nvg.rrectStroke(r[0] - 1, r[1] - 1, w + 2, h + 2, 3, 3.4f, 0x99000000);
                Nvg.rrect(r[0], r[1], w, h, 2, Nvg.alpha(f.kind().color, 0.16f));
                Nvg.rrectStroke(r[0], r[1], w, h, 2, 1.4f, f.kind().color);
                if (tag.on() && w > 10) {
                    String n = f.kind().label;
                    float tw = Nvg.textWidth(n, 10.5f, Nvg.MED);
                    Nvg.rrect(r[0] + w / 2 - tw / 2 - 5, r[1] - 17, tw + 10, 14, 7, 0xB0000000);
                    Nvg.text(n, r[0] + w / 2, r[1] - 9.5f, 10.5f, Nvg.MED, Nvg.CENTER, 0xFFFFFFFF);
                }
            }
        }
    }

    /* ------------------------------------------------------------------ donut */

    static final class SpawnerEsp extends Module {
        private final Setting.Num range = num("Range", 12, 2, 24, 1);
        private final Setting.Bool alert = bool("Alert", true);
        private List<Found> found = List.of();
        private final Set<BlockPos> told = new HashSet<>();
        private int clock;

        SpawnerEsp() {
            super("Spawner ESP", "Highlights spawners and tells you when one shows up", Category.DONUT);
        }

        @Override
        protected void onDisable() {
            told.clear();
            found = List.of();
        }

        @Override
        public void tick() {
            if (clock++ % 20 != 0) return;
            List<Found> out = new ArrayList<>();
            for (Found f : scan(range.i())) if (f.kind() == Kind.SPAWNER) out.add(f);
            found = out;
            if (alert.on()) for (Found f : out) if (told.add(f.pos()))
                Toasts.push("donut", "Spawner found", f.pos().getX() + ", " + f.pos().getY() + ", " + f.pos().getZ());
        }

        @Override
        public void render2d(float td) {
            for (Found f : found) {
                float[] r = Projection.box(new Box(f.pos()));
                if (r == null) continue;
                float w = r[2] - r[0], h = r[3] - r[1];
                if (w < 2 || h < 2 || w > Nvg.w) continue;
                Nvg.rrectStroke(r[0] - 1, r[1] - 1, w + 2, h + 2, 3, 3.4f, 0x99000000);
                Nvg.rrect(r[0], r[1], w, h, 2, Nvg.alpha(Kind.SPAWNER.color, 0.2f));
                Nvg.rrectStroke(r[0], r[1], w, h, 2, 1.6f, Kind.SPAWNER.color);
                float tw = Nvg.textWidth("Spawner", 10.5f, Nvg.MED);
                Nvg.rrect(r[0] + w / 2 - tw / 2 - 5, r[1] - 17, tw + 10, 14, 7, 0xB0000000);
                Nvg.text("Spawner", r[0] + w / 2, r[1] - 9.5f, 10.5f, Nvg.MED, Nvg.CENTER, 0xFFFFFFFF);
            }
        }
    }

    /** Warns when staff or a developer shows up in the player list. */
    static final class StaffAlert extends Module {
        private final Setting.Bool chat = bool("Chat Message", true);
        private final Setting.Bool sound = bool("Sound", true);
        private final Set<String> seen = new HashSet<>();

        StaffAlert() {
            super("Staff Alert", "Warns you when staff or devs are online", Category.DONUT);
        }

        @Override
        protected void onDisable() {
            seen.clear();
        }

        @Override
        public void tick() {
            Set<String> now = new HashSet<>();
            for (StaffScan.Entry e : StaffScan.current()) {
                now.add(e.name());
                if (!seen.add(e.name())) continue;
                Toasts.push("staff", e.tag() + " online", e.name() + "  -  " + e.rank());
                if (chat.on()) mc.player.sendMessage(Text.literal("[Tenacity] " + e.tag() + " " + e.name() + " (" + e.rank() + ") is online"), false);
                if (sound.on()) mc.player.playSound(net.minecraft.sound.SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1f, 1.2f);
            }
            seen.retainAll(now);
        }
    }

    /** Keeps you from getting kicked for idling. */
    static final class AntiAfk extends Module {
        private final Setting.Num interval = num("Interval", 25, 5, 120, 1);
        private final Setting.Bool jump = bool("Jump", true);
        private final Setting.Bool look = bool("Look Around", true);
        private int clock;
        private int jumpHold;

        AntiAfk() {
            super("Anti AFK", "Moves a little now and then so you are not kicked", Category.DONUT);
        }

        @Override
        public void tick() {
            if (jumpHold > 0 && --jumpHold == 0) mc.options.jumpKey.setPressed(false);
            if (++clock < interval.value * 20) return;
            clock = 0;
            if (look.on()) {
                mc.player.setYaw(mc.player.getYaw() + (float) (Math.random() * 40 - 20));
                mc.player.setPitch(Math.max(-60, Math.min(60, mc.player.getPitch() + (float) (Math.random() * 10 - 5))));
            }
            if (jump.on() && mc.currentScreen == null) {
                mc.options.jumpKey.setPressed(true);
                jumpHold = 3;
            }
        }

        @Override
        protected void onDisable() {
            mc.options.jumpKey.setPressed(false);
        }
    }
}

package tenacity.hud;

import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import tenacity.Tenacity;
import tenacity.core.Module;
import tenacity.core.ModuleManager;
import tenacity.core.Setting;
import tenacity.gfx.Icons;
import tenacity.gfx.ItemAtlas;
import tenacity.gfx.Nvg;
import tenacity.gfx.SpotifyArt;
import tenacity.gfx.Skins;
import net.minecraft.util.math.Vec3d;
import tenacity.modules.WorldModules;
import tenacity.spotify.NowPlaying;
import tenacity.spotify.SpotifyPoller;
import tenacity.ui.Anim;
import tenacity.ui.Theme;
import tenacity.util.StaffScan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** All heads up display widgets. */
public final class Huds {
    private Huds() {
    }

    public static List<Module> create() {
        return List.of(new Watermark(), new ModuleList(), new Coordinates(), new StaffList(), new SpotifyHud(),
                new Keybinds(), new InventoryHud(), new ArmorHud(), new RadarHud(), new HotbarHud());
    }

    /* ------------------------------------------------------------------ helpers */

    /** Standard widget header: icon, title and an optional right aligned count. */
    private static void header(float x, float y, float w, String icon, String title, String right) {
        Nvg.rect(x + 12, y + 31, w - 24, 1, Theme.line());
        Icons.draw(icon, x + 12, y + 10, 15, Theme.text());
        Nvg.text(title, x + 33, y + 17.5f, 12.5f, Nvg.SEMI, Nvg.LEFT, Theme.text());
        if (right != null && !right.isEmpty()) {
            float tw = Nvg.textWidth(right, 10.5f, Nvg.SEMI) + 12;
            Nvg.rrect(x + w - tw - 10, y + 10, tw, 15, 7.5f, Theme.hover());
            Nvg.text(right, x + w - 10 - tw / 2f, y + 17.8f, 10.5f, Nvg.SEMI, Nvg.CENTER, Theme.dim());
        }
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /* ------------------------------------------------------------------ watermark */

    static final class Watermark extends HudElement {
        private final Setting.Mode style = mode("Style", "Pill", "Pill", "Card", "Minimal");
        private final Setting.Mode text = mode("Text", "Tenacity", "Tenacity", "Tenacity Client", "T");
        private final Setting.Mode rank = mode("Rank", "Developer", "Developer", "Admin", "Member");
        private final Setting.Bool showRank = bool("Show Rank", true);
        private final Setting.Bool fps = bool("FPS", true);
        private final Setting.Bool ram = bool("RAM", true);
        private final Setting.Bool cpu = bool("CPU", true);
        private final Setting.Bool clock = bool("Time", true);
        private final Setting.Bool ping = bool("Ping", false);
        private final List<String[]> stats = new ArrayList<>();
        private String name = "Tenacity";

        Watermark() {
            super("Watermark", "Logo, rank, fps, memory and time");
        }

        @Override
        public void defaults(float sw, float sh) {
            x = 12;
            y = 12;
        }

        private static final com.sun.management.OperatingSystemMXBean OS =
                java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
        private long cpuAt;
        private int cpuPct;

        private void collect() {
            stats.clear();
            if (fps.on()) stats.add(new String[]{"gauge", mc.getCurrentFps() + " fps"});
            if (cpu.on()) {
                long now = System.currentTimeMillis();
                if (now - cpuAt > 1000 && OS != null) {
                    cpuAt = now;
                    double l = OS.getProcessCpuLoad();
                    if (l >= 0) cpuPct = (int) Math.round(l * 100);
                }
                stats.add(new String[]{"chip", cpuPct + "%"});
            }
            if (ram.on()) {
                Runtime rt = Runtime.getRuntime();
                stats.add(new String[]{"memory", (rt.totalMemory() - rt.freeMemory()) / 1048576L + " MB"});
            }
            if (ping.on() && mc.player != null && mc.getNetworkHandler() != null) {
                var e = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
                if (e != null) stats.add(new String[]{"signal", e.getLatency() + " ms"});
            }
            if (clock.on()) stats.add(new String[]{"clock", java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))});
            name = text.get();
        }

        private String rankIcon() {
            return switch (rank.get()) {
                case "Developer" -> "code";
                case "Admin" -> "crown";
                default -> "player";
            };
        }

        /** Width of one icon + text item. */
        private static float itemW(String t, float size, int font) {
            return size * 1.15f + 5 + Nvg.textWidth(t, size, font);
        }

        private float statsW(float size) {
            float w = 0;
            for (String[] st : stats) w += itemW(st[1], size, Nvg.MED) + 14;
            return w;
        }

        private float rankW(float size) {
            return showRank.on() ? itemW(rank.get(), size, Nvg.SEMI) : 0;
        }

        @Override
        public void measure() {
            collect();
            boolean logoOnly = name.equals("T");
            switch (style.get()) {
                case "Card" -> {
                    float top = Nvg.textWidth(logoOnly ? "Tenacity" : name, 18, Nvg.SEMI) + (showRank.on() ? 12 + rankW(11) : 0);
                    w = 62 + Math.max(top, statsW(11) - 14) + 18;
                    h = 56;
                }
                case "Minimal" -> {
                    w = Nvg.textWidth(name, 20, Nvg.SEMI) + 14 + (showRank.on() ? rankW(12) + 14 : 0) + statsW(12);
                    h = 26;
                }
                default -> {
                    w = 12 + 22 + (logoOnly ? 0 : 9 + Nvg.textWidth(name, 15, Nvg.SEMI)) + (showRank.on() ? 14 + rankW(12.5f) : 0)
                            + (stats.isEmpty() ? 0 : statsW(12.5f)) + 14;
                    h = 36;
                }
            }
        }

        /** The "T" tile. */
        private static void logo(float x, float y, float s) {
            Nvg.text("T", x + s / 2, y + s / 2 + s * 0.04f, s * 0.95f, Nvg.SEMI, Nvg.CENTER, Theme.text());
        }

        /** Icon followed by text; returns the width used. */
        private static float item(String icon, String t, float x, float cy, float size, int font, int iconCol, int textCol) {
            float is = size * 1.15f;
            Icons.draw(icon, x, cy - is / 2, is, iconCol);
            Nvg.text(t, x + is + 5, cy + 0.5f, size, font, Nvg.LEFT, textCol);
            return itemW(t, size, font);
        }

        private float rankItem(float x, float cy, float size) {
            return item(rankIcon(), rank.get(), x, cy, size, Nvg.SEMI, Theme.text(), Theme.text());
        }

        private void statsRow(float x, float cy, float size) {
            for (String[] st : stats) x += item(st[0], st[1], x, cy, size, Nvg.MED, Theme.faint(), Theme.dim()) + 14;
        }

        @Override
        public void draw(float x, float y, float a) {
            boolean logoOnly = name.equals("T");
            switch (style.get()) {
                case "Card" -> {
                    Theme.hud(x, y, w, h, 16, a, 0);
                    logo(x + 10, y + 10, 36);
                    String title = logoOnly ? "Tenacity" : name;
                    Nvg.text(title, x + 56, y + 20, 18, Nvg.SEMI, Nvg.LEFT, Theme.text());
                    if (showRank.on()) rankItem(x + 56 + Nvg.textWidth(title, 18, Nvg.SEMI) + 12, y + 20.5f, 11);
                    statsRow(x + 56, y + 41, 11);
                }
                case "Minimal" -> {
                    Nvg.text(name, x + 1, y + h / 2 + 1, 20, Nvg.SEMI, Nvg.LEFT, Nvg.alpha(0xFF000000, 0.35f));
                    Nvg.text(name, x, y + h / 2, 20, Nvg.SEMI, Nvg.LEFT, Theme.text());
                    float cx = x + Nvg.textWidth(name, 20, Nvg.SEMI) + 14;
                    if (showRank.on()) cx += rankItem(cx, y + h / 2 + 1, 12) + 14;
                    statsRow(cx, y + h / 2 + 1, 12);
                }
                default -> {
                    Theme.hud(x, y, w, h, 18, a, 0);
                    float cx = x + 7, cy = y + h / 2;
                    logo(cx, cy - 11, 22);
                    cx += 22;
                    if (!logoOnly) {
                        Nvg.text(name, cx + 9, cy + 0.5f, 15, Nvg.SEMI, Nvg.LEFT, Theme.text());
                        cx += 9 + Nvg.textWidth(name, 15, Nvg.SEMI);
                    }
                    if (showRank.on()) {
                        Nvg.rect(cx + 7, cy - 7, 1, 14, Theme.line());
                        cx += 14 + rankItem(cx + 14, cy, 12.5f);
                    }
                    if (!stats.isEmpty()) {
                        Nvg.rect(cx + 7, cy - 7, 1, 14, Theme.line());
                        statsRow(cx + 14, cy, 12.5f);
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ armor */

    static final class ArmorHud extends HudElement {
        private final Setting.Bool percent = bool("Durability %", true);
        private final ItemStack[] items = new ItemStack[4];
        private static final net.minecraft.entity.EquipmentSlot[] SLOTS = {net.minecraft.entity.EquipmentSlot.HEAD,
                net.minecraft.entity.EquipmentSlot.CHEST, net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET};

        ArmorHud() {
            super("Armor", "The armor you are wearing with its durability");
        }

        @Override
        public void defaults(float sw, float sh) {
            x = sw / 2 - 80;
            y = sh - 122;
        }

        @Override
        public void measure() {
            for (int i = 0; i < 4; i++) items[i] = mc.player == null ? ItemStack.EMPTY : mc.player.getEquippedStack(SLOTS[i]);
            w = 4 * 38 + 10;
            h = percent.on() ? 58 : 46;
        }

        @Override
        public void draw(float x, float y, float a) {
            Theme.hud(x, y, w, h, 18, a, 0);
            for (int i = 0; i < 4; i++) {
                ItemStack st = items[i];
                float ix = x + 6 + i * 38;
                if (st.isEmpty()) {
                    Nvg.rrectStroke(ix + 5, y + 9, 28, 28, 8, 1f, Nvg.alpha(Theme.text(), 0.12f));
                    continue;
                }
                ItemAtlas.draw(st, ix + 7, y + 11, 24);
                if (!st.isDamageable()) continue;
                float left = 1f - (float) st.getDamage() / Math.max(1, st.getMaxDamage());
                int col = left < 0.25f ? 0xFFFF5A5A : left < 0.5f ? 0xFFFFC857 : Theme.text();
                Nvg.rrect(ix + 7, y + 39, 24, 2.2f, 1.1f, Nvg.alpha(Theme.text(), 0.14f));
                Nvg.rrect(ix + 7, y + 39, 24 * left, 2.2f, 1.1f, col);
                if (percent.on()) Nvg.text(Math.round(left * 100) + "%", ix + 19, y + 50, 9.5f, Nvg.SEMI, Nvg.CENTER, Theme.dim());
            }
        }
    }

    /* ------------------------------------------------------------------ hotbar */

    /** Replaces the vanilla hotbar: glass bar with a sliding selection, item counts, durability and the off hand. */
    static final class HotbarHud extends HudElement {
        private final Setting.Bool offhand = bool("Off Hand", true);
        private final Setting.Bool numbers = bool("Slot Numbers", false);
        /** Custom glass health, hunger, armor and air instead of the vanilla pixel bars. */
        private final Setting.Bool status = bool("Status Bars", true);
        private float sel = -1;

        HotbarHud() {
            super("Hotbar", "A clean liquid glass hotbar with health, hunger and armor");
        }

        @Override
        protected void onEnable() {
            tenacity.modules.RenderFlags.customHotbar = true;
            tenacity.modules.RenderFlags.customStatus = status.on();
        }

        @Override
        protected void onDisable() {
            tenacity.modules.RenderFlags.customHotbar = false;
            tenacity.modules.RenderFlags.customStatus = false;
        }

        @Override
        public void defaults(float sw, float sh) {
            x = sw / 2 - (9 * 36 + 12) / 2f;
            y = sh - 78;
        }

        @Override
        public void measure() {
            if (enabled()) tenacity.modules.RenderFlags.customHotbar = true; // only while on, so vanilla comes back on disable
            w = 9 * 36 + 12;
            h = 46 + (status.on() ? 36 : 0);
        }

        private static void slot(ItemStack st, float sx, float sy) {
            if (st.isEmpty()) return;
            ItemAtlas.draw(st, sx + 7, sy + 7, 22);
            if (st.getCount() > 1) {
                String n = Integer.toString(st.getCount());
                Nvg.text(n, sx + 33.5f, sy + 29.5f, 10, Nvg.SEMI, Nvg.RIGHT, 0xA0000000);
                Nvg.text(n, sx + 33, sy + 29, 10, Nvg.SEMI, Nvg.RIGHT, 0xFFFFFFFF);
            }
            if (st.isDamageable() && st.getDamage() > 0) {
                float left = 1f - (float) st.getDamage() / Math.max(1, st.getMaxDamage());
                Nvg.rrect(sx + 7, sy + 31.5f, 22, 2, 1, 0x60000000);
                Nvg.rrect(sx + 7, sy + 31.5f, 22 * left, 2, 1, left < 0.25f ? 0xFFFF5A5A : left < 0.5f ? 0xFFFFC857 : 0xFFFFFFFF);
            }
        }

        /** Vector heart so it fits the glass look. */
        private static void heartShape(float cx, float cy, float s) {
            float k = s * 0.5f;
            Nvg.beginPath();
            Nvg.moveTo(cx, cy + s * 0.44f);
            Nvg.bezierTo(cx - k * 1.12f, cy + s * 0.06f, cx - k * 1.02f, cy - s * 0.52f, cx - k * 0.4f, cy - s * 0.52f);
            Nvg.bezierTo(cx - k * 0.12f, cy - s * 0.52f, cx, cy - s * 0.3f, cx, cy - s * 0.12f);
            Nvg.bezierTo(cx, cy - s * 0.3f, cx + k * 0.12f, cy - s * 0.52f, cx + k * 0.4f, cy - s * 0.52f);
            Nvg.bezierTo(cx + k * 1.02f, cy - s * 0.52f, cx + k * 1.12f, cy + s * 0.06f, cx, cy + s * 0.44f);
            Nvg.closePath();
        }

        @Override
        public void draw(float x, float y, float a) {
            if (mc.player == null) return;
            float top = 0;
            if (status.on()) {
                top = 36;
                drawStatus(x, y, a);
            }
            Theme.hud(x, y + top, w, 46, 18, a, 0);
            int cur = mc.player.getInventory().getSelectedSlot();
            if (sel < 0) sel = cur;
            sel = Anim.approach(sel, cur, 18f);
            float bx = x + 6;
            Nvg.rrect(bx + sel * 36 + 1, y + top + 5, 34, 36, 12, Nvg.alpha(Theme.text(), 0.16f));
            Nvg.rrectStroke(bx + sel * 36 + 1, y + top + 5, 34, 36, 12, 1.2f, Nvg.alpha(Theme.text(), 0.55f));
            for (int i = 0; i < 9; i++) {
                float sx = bx + i * 36, sy = y + top + 5;
                if (numbers.on()) Nvg.text(Integer.toString(i + 1), sx + 6, sy + 7, 8, Nvg.MED, Nvg.LEFT, Theme.faint());
                slot(mc.player.getInventory().getStack(i), sx, sy);
            }
            ItemStack off = mc.player.getOffHandStack();
            if (offhand.on() && !off.isEmpty()) {
                float ox = x - 50;
                Theme.hud(ox, y + top, 46, 46, 18, a, 0);
                slot(off, ox + 5, y + top + 5);
            }
        }

        private float hpS = -1, foodS = -1, armorS = 0, airS = 1, absS = 0, hurtFlash = 0, lastHp = -1;

        /** One frosted glass pill: icon, smooth bar, value. */
        private void pill(float x, float y, float pw, float a, float frac, float extra, float sub, int fill0, int fill1, String value, int icon) {
            float ph = 26;
            Theme.hud(x, y, pw, ph, 13, a, 0);
            // icon
            float ix = x + 14, iy = y + ph / 2;
            int ic = Nvg.alpha(0xFFFFFFFF, 0.95f * a);
            if (icon == 0) {
                heartShape(ix, iy, 12);
                Nvg.fillPathGradient(ix, iy - 6, ix, iy + 6, ic, Nvg.alpha(0xFFDDE2E8, a));
            } else {
                Nvg.line(ix - 3.4f, iy + 2.8f, ix + 0.4f, iy - 0.6f, 2.2f, Nvg.alpha(0xFFE9ECEF, a));
                Nvg.circle(ix - 3.9f, iy + 3.2f, 1.5f, Nvg.alpha(0xFFE9ECEF, a));
                Nvg.circle(ix - 1.6f, iy + 4.4f, 1.5f, Nvg.alpha(0xFFE9ECEF, a));
                Nvg.circle(ix + 1.2f, iy - 1.4f, 4.4f, ic);
            }
            // bar
            float bx = x + 26, bw = pw - 26 - 34, by = y + ph / 2 - 3, bh = 6;
            Nvg.rrect(bx, by, bw, bh, 3, Nvg.alpha(0xFFFFFFFF, 0.10f * a));
            if (frac > 0.002f) {
                Nvg.rrectGradient(bx, by, Math.max(bh, bw * frac), bh, 3, bx, by, bx + bw, by, Nvg.alpha(fill0, a), Nvg.alpha(fill1, a));
                // frost sheen on the bar
                Nvg.rrect(bx + 1, by + 0.8f, Math.max(0, bw * frac - 2), 1.6f, 0.8f, Nvg.alpha(0xFFFFFFFF, 0.35f * a));
            }
            if (extra > 0.002f) // absorption: a golden-white tail after the health
                Nvg.rrect(bx + bw * Math.min(1, frac), by, Math.min(bw * extra, bw * (1 - Math.min(1, frac))), bh, 3, Nvg.alpha(0xFFFFE9A8, 0.9f * a));
            if (sub > 0.002f) { // thin secondary line under the bar (armor / air)
                Nvg.rrect(bx, by + bh + 2.5f, bw, 1.6f, 0.8f, Nvg.alpha(0xFFFFFFFF, 0.07f * a));
                Nvg.rrect(bx, by + bh + 2.5f, bw * sub, 1.6f, 0.8f, Nvg.alpha(0xFFD9DEE4, 0.85f * a));
            }
            Nvg.text(value, x + pw - 10, y + ph / 2 + 0.5f, 11, Nvg.SEMI, Nvg.RIGHT, Nvg.alpha(0xFFFFFFFF, a));
        }

        /** Frosted white glass health (with armor + absorption) and hunger (with air) pills above the hotbar. */
        private void drawStatus(float x, float y, float a) {
            var p = mc.player;
            float max = Math.max(1f, p.getMaxHealth()), hp = p.getHealth();
            if (hpS < 0) hpS = hp;
            if (foodS < 0) foodS = p.getHungerManager().getFoodLevel();
            if (lastHp >= 0 && hp < lastHp - 0.01f) hurtFlash = 1;
            lastHp = hp;
            hpS = Anim.approach(hpS, hp, 10f);
            foodS = Anim.approach(foodS, p.getHungerManager().getFoodLevel(), 10f);
            armorS = Anim.approach(armorS, p.getArmor() / 20f, 10f);
            absS = Anim.approach(absS, p.getAbsorptionAmount() / max, 10f);
            airS = Anim.approach(airS, Math.max(0, p.getAir()) / (float) Math.max(1, p.getMaxAir()), 10f);
            hurtFlash = Anim.approach(hurtFlash, 0, 4f);
            float gap = 8, pw = (w - gap) / 2f;
            boolean low = hp / max < 0.3f;
            float pulse = low ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 140.0) : 0;
            // frost white, warming to a soft red when hurt / low
            int h0 = mixc(0xFFFFFFFF, 0xFFFF6F7F, Math.max(hurtFlash, pulse * 0.8f));
            int h1 = mixc(0xFFE4E8ED, 0xFFFF4D63, Math.max(hurtFlash, pulse * 0.8f));
            String hv = (hp % 1 == 0 ? Integer.toString((int) hp) : String.format(java.util.Locale.ROOT, "%.1f", hp));
            pill(x, y, pw, a, hpS / max, absS, armorS, h0, h1, hv, 0);
            boolean underwater = p.getAir() < p.getMaxAir();
            pill(x + pw + gap, y, pw, a, foodS / 20f, 0, underwater ? airS : 0, 0xFFFFFFFF, 0xFFE4E8ED, Integer.toString(p.getHungerManager().getFoodLevel()), 1);
        }

        private static int mixc(int x, int y, float t) {
            t = Math.max(0, Math.min(1, t));
            int r = Math.round(((x >> 16) & 255) + (((y >> 16) & 255) - ((x >> 16) & 255)) * t);
            int g = Math.round(((x >> 8) & 255) + (((y >> 8) & 255) - ((x >> 8) & 255)) * t);
            int b = Math.round((x & 255) + ((y & 255) - (x & 255)) * t);
            return 0xFF000000 | r << 16 | g << 8 | b;
        }

        /** Little chestplate. */
        private static void armorIcon(float cx, float cy, float s, int c) {
            float k = s * 0.5f;
            Nvg.beginPath();
            Nvg.moveTo(cx - k, cy - k * 0.7f);
            Nvg.lineTo(cx - k * 0.3f, cy - k * 0.7f);
            Nvg.quadTo(cx, cy - k * 0.28f, cx + k * 0.3f, cy - k * 0.7f);
            Nvg.lineTo(cx + k, cy - k * 0.7f);
            Nvg.lineTo(cx + k * 0.85f, cy + k);
            Nvg.quadTo(cx, cy + k * 1.25f, cx - k * 0.85f, cy + k);
            Nvg.closePath();
            Nvg.fillPath(c);
        }
    }

    /* ------------------------------------------------------------------ radar */

    /** Round liquid glass radar showing the heads of nearby players, rotated with your view. */
    static final class RadarHud extends HudElement {
        private final Setting.Num size = num("Size", 150, 90, 260, 10);
        private final Setting.Num range = num("Range", 48, 16, 160, 8);
        private final Setting.Bool mobs = bool("Mobs", false);
        private final Setting.Bool names = bool("Names", false);

        RadarHud() {
            super("Radar", "Round radar with the heads of nearby players");
        }

        @Override
        public void defaults(float sw, float sh) {
            x = sw - 170;
            y = 130;
        }

        @Override
        public void measure() {
            w = h = size.f();
        }

        @Override
        public void draw(float x, float y, float a) {
            float s = w, r = s / 2f, cx = x + r, cy = y + r;
            Theme.hud(x, y, s, s, r, a, 0);
            // rings and cross
            Nvg.circleStroke(cx, cy, r * 0.66f, 1f, Nvg.alpha(Theme.text(), 0.10f));
            Nvg.circleStroke(cx, cy, r * 0.33f, 1f, Nvg.alpha(Theme.text(), 0.08f));
            Nvg.line(cx, y + 10, cx, y + s - 10, 1f, Nvg.alpha(Theme.text(), 0.07f));
            Nvg.line(x + 10, cy, x + s - 10, cy, 1f, Nvg.alpha(Theme.text(), 0.07f));
            if (mc.player == null) return;
            float td = mc.getRenderTickCounter().getTickProgress(false);
            double yaw = Math.toRadians(mc.player.getYaw(td));
            String[] dirs = {"N", "E", "S", "W"};
            double[][] vec = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
            for (int i = 0; i < 4; i++) {
                double rx = vec[i][0] * Math.cos(yaw) + vec[i][1] * Math.sin(yaw), rz = vec[i][1] * Math.cos(yaw) - vec[i][0] * Math.sin(yaw);
                float lx = cx - (float) rx * (r - 11), ly = cy - (float) rz * (r - 11);
                Nvg.text(dirs[i], lx, ly + 0.5f, i == 0 ? 11 : 9.5f, Nvg.SEMI, Nvg.CENTER, i == 0 ? Theme.text() : Theme.faint());
            }
            Vec3d me = mc.player.getLerpedPos(td);
            float k = (r - 14) / range.f();
            Nvg.save();
            Nvg.clip(x, y, s, s);
            for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
                if (e == mc.player || !(e instanceof net.minecraft.entity.LivingEntity) || !e.isAlive()) continue;
                boolean player = e instanceof net.minecraft.client.network.AbstractClientPlayerEntity;
                if (!player && !mobs.on()) continue;
                Vec3d d = e.getLerpedPos(td).subtract(me);
                double rx = d.x * Math.cos(yaw) + d.z * Math.sin(yaw), rz = d.z * Math.cos(yaw) - d.x * Math.sin(yaw);
                float px = cx - (float) rx * k, py = cy - (float) rz * k;
                float dist = (float) Math.hypot(px - cx, py - cy);
                if (dist > r - 10) { // pin to the rim
                    px = cx + (px - cx) * (r - 10) / dist;
                    py = cy + (py - cy) * (r - 10) / dist;
                }
                if (player) {
                    var ap = (net.minecraft.client.network.AbstractClientPlayerEntity) e;
                    float hs = 14;
                    Nvg.rrect(px - hs / 2 - 1.5f, py - hs / 2 - 1.5f, hs + 3, hs + 3, 4.5f, Nvg.alpha(0xFF000000, 0.45f));
                    if (!Skins.head(ap.getSkin(), px - hs / 2, py - hs / 2, hs, 3)) Nvg.rrect(px - hs / 2, py - hs / 2, hs, hs, 3, Theme.text());
                    if (names.on()) Nvg.text(ap.getName().getString(), px, py + hs / 2 + 7, 8.5f, Nvg.MED, Nvg.CENTER, Theme.text());
                } else Nvg.circle(px, py, 2.4f, Nvg.alpha(Theme.text(), 0.6f));
            }
            Nvg.restore();
            // you
            Nvg.beginPath();
            Nvg.moveTo(cx, cy - 7); Nvg.lineTo(cx + 5, cy + 5.5f); Nvg.lineTo(cx, cy + 3); Nvg.lineTo(cx - 5, cy + 5.5f); Nvg.closePath();
            Nvg.fillPath(Theme.text());
        }
    }

    /* ------------------------------------------------------------------ module list */

    /**
     * Enabled modules as a stepped stack hugging the screen edge it sits on. Every row is its own glass piece, but the rows join
     * into one shape: corners are only rounded where they are exposed, the side against the screen edge stays square and a
     * hairline separates the rows.
     */
    static final class ModuleList extends HudElement {
        private final Setting.Bool suffix = bool("Suffixes", true);
        private final Setting.Mode sort = mode("Sort", "Length", "Length", "A-Z");
        private final List<Module> rows = new ArrayList<>();
        private final List<Float> widths = new ArrayList<>();
        private static final float ROW = 22f, PAD = 9f, R = 6f;

        ModuleList() {
            super("Module List", "The enabled modules, stacked against the screen edge");
            anchorRight = true;
        }

        @Override
        public void defaults(float sw, float sh) {
            x = sw;
            y = 0;
        }

        private float textLen(Module m) {
            float tw = Nvg.textWidth(m.name, 12.5f, Nvg.MED);
            String sx = suffix.on() ? m.suffix() : "";
            if (!sx.isEmpty()) tw += 6 + Nvg.textWidth(sx, 11f, Nvg.REG);
            return tw;
        }

        @Override
        public void measure() {
            rows.clear();
            widths.clear();
            for (Module m : ModuleManager.all()) {
                m.listAnim = Anim.approach(m.listAnim, m.enabled() ? 1f : 0f, 14f);
                if (m.showInList() && m.listAnim > 0.01f) rows.add(m);
            }
            if (sort.is("A-Z")) rows.sort(Comparator.comparing((Module m) -> m.name));
            else rows.sort(Comparator.comparingDouble((Module m) -> -textLen(m)));
            float maxW = 0, total = 0, anchor = -100;
            for (Module m : rows) {
                float rw = textLen(m) + PAD * 2;
                if (Math.abs(anchor - rw) < 3) rw = Math.max(anchor, rw);
                else anchor = rw;
                widths.add(rw);
                maxW = Math.max(maxW, rw);
                total += ROW * Anim.easeOut(m.listAnim);
            }
            w = Math.max(rows.isEmpty() ? 110 : 0, maxW);
            h = Math.max(rows.isEmpty() ? ROW : 0, total);
        }

        @Override
        public void draw(float x, float y, float a) {
            if (rows.isEmpty()) {
                if (Hud.editing()) {
                    Theme.hud(x, y, w, h, R, a, 0);
                    Nvg.text("Module List", x + w / 2, y + h / 2 + 0.5f, 11.5f, Nvg.MED, Nvg.CENTER, Theme.dim());
                }
                return;
            }
            // which side of the screen the stack hugs
            boolean right = Theme.ox + (x + w / 2f) * Theme.gs > Nvg.w / 2f;
            float cy = y;
            int n = rows.size();
            for (int i = 0; i < n; i++) {
                Module m = rows.get(i);
                float rw = widths.get(i);
                float e = Anim.easeOut(m.listAnim);
                float rowH = ROW * e;
                float slide = (1 - e) * (rw + 8);
                float rx = right ? x + (w - rw) + slide : x - slide;
                float above = i == 0 ? -1 : widths.get(i - 1), below = i == n - 1 ? -1 : widths.get(i + 1);
                float rt = above < rw - 0.5f ? Math.min(R, rw - Math.max(above, 0)) : 0;
                float rb = below < rw - 0.5f ? Math.min(R, rw - Math.max(below, 0)) : 0;
                float tl = right ? rt : 0, tr = right ? 0 : rt, br = right ? 0 : rb, bl = right ? rb : 0;
                Theme.hudCorners(rx, cy, rw, rowH, tl, tr, br, bl, a * e);
                Nvg.save();
                Nvg.fade(a * e);
                if (i < n - 1) {
                    float lw = Math.min(rw, below);
                    Nvg.rect(right ? rx + rw - lw : rx, cy + rowH - 0.5f, lw, 1, Nvg.alpha(Theme.text(), 0.07f));
                }
                float tlen = textLen(m);
                float tx = right ? rx + rw - PAD - tlen : rx + PAD, ty = cy + rowH / 2f + 0.5f;
                Nvg.text(m.name, tx, ty, 12.5f, Nvg.MED, Nvg.LEFT, Theme.text());
                String sx = suffix.on() ? m.suffix() : "";
                if (!sx.isEmpty()) Nvg.text(sx, tx + Nvg.textWidth(m.name, 12.5f, Nvg.MED) + 6, ty, 11f, Nvg.REG, Nvg.LEFT, Theme.dim());
                Nvg.restore();
                cy += rowH;
            }
        }
    }

    /* ------------------------------------------------------------------ coordinates */

    static final class Coordinates extends HudElement {
        private final Setting.Bool other = bool("Other Dimension", true);
        private final Setting.Bool dir = bool("Direction", true);

        Coordinates() {
            super("Coordinates", "Your position, the matching nether / overworld position and heading");
        }

        @Override
        public void defaults(float sw, float sh) {
            x = 12;
            y = sh - 90;
        }

        @Override
        public void measure() {
            w = 226;
            h = (other.on() || dir.on()) ? 78 : 52;
        }

        private void cell(String label, String value, float x, float y, float cw) {
            Nvg.text(label, x + cw / 2, y + 11, 9, Nvg.SEMI, Nvg.CENTER, Theme.faint());
            Nvg.text(value, x + cw / 2, y + 26, 14, Nvg.SEMI, Nvg.CENTER, Theme.text());
        }

        @Override
        public void draw(float x, float y, float a) {
            Theme.hud(x, y, w, h, 14, a, 0);
            int px = 0, py = 0, pz = 0;
            if (mc.player != null) {
                px = (int) Math.floor(mc.player.getX());
                py = (int) Math.floor(mc.player.getY());
                pz = (int) Math.floor(mc.player.getZ());
            }
            float cw = (w - 16 - 12) / 3f;
            cell("X", Integer.toString(px), x + 8, y + 8, cw);
            Nvg.rect(x + 8 + cw + 2.5f, y + 14, 1, 24, Theme.line());
            Nvg.rect(x + 8 + (cw + 6) * 2 - 3.5f, y + 14, 1, 24, Theme.line());
            cell("Y", Integer.toString(py), x + 8 + cw + 6, y + 8, cw);
            cell("Z", Integer.toString(pz), x + 8 + (cw + 6) * 2, y + 8, cw);
            if (h < 60 || mc.player == null) return;
            String line = "";
            if (other.on() && mc.world != null) {
                var key = mc.world.getRegistryKey();
                if (key == World.OVERWORLD) line = "Nether  " + Math.floorDiv(px, 8) + ", " + Math.floorDiv(pz, 8);
                else if (key == World.NETHER) line = "Overworld  " + px * 8 + ", " + pz * 8;
                else line = "The End";
            }
            Nvg.rect(x + 12, y + 49, w - 24, 1, Theme.line());
            Icons.draw("pin", x + 10, y + 53, 13, Theme.dim());
            Nvg.text(line, x + 28, y + 60, 11, Nvg.MED, Nvg.LEFT, Theme.dim());
            if (dir.on()) {
                Direction f = mc.player.getHorizontalFacing();
                String nm = f.asString();
                String d = nm.substring(0, 1).toUpperCase(Locale.ROOT) + nm.substring(1);
                String ax = switch (f) {
                    case NORTH -> "-Z";
                    case SOUTH -> "+Z";
                    case EAST -> "+X";
                    default -> "-X";
                };
                Nvg.text(ax, x + w - 12, y + 60, 11, Nvg.SEMI, Nvg.RIGHT, Theme.dim());
                Nvg.text(d, x + w - 18 - Nvg.textWidth(ax, 11, Nvg.SEMI), y + 60, 11, Nvg.MED, Nvg.RIGHT, Theme.text());
            }
        }
    }

    /* ------------------------------------------------------------------ staff list */

    static final class StaffList extends HudElement {
        private final Setting.Bool ranks = bool("Show Rank", true);
        private final Setting.Bool heads = bool("Heads", true);
        private final Setting.Bool empty = bool("Show When Empty", true);
        private List<StaffScan.Entry> list = List.of();

        StaffList() {
            super("Staff List", "Staff and developers currently online");
        }

        @Override
        public void defaults(float sw, float sh) {
            x = 12;
            y = 56;
        }

        @Override
        public void measure() {
            list = StaffScan.current();
            if (list.isEmpty() && !empty.on() && !Hud.editing()) {
                w = h = 0;
                return;
            }
            float width = 170;
            for (StaffScan.Entry e : list) {
                float rw = (heads.on() ? 30 : 12) + Nvg.textWidth(e.name(), 12f, Nvg.MED) + 14 + (ranks.on() ? 18 + Nvg.textWidth(e.rank(), 11f, Nvg.MED) : 0) + 12;
                width = Math.max(width, rw);
            }
            w = width;
            h = 36 + (list.isEmpty() ? 24 : list.size() * 24 + 4);
        }

        @Override
        public void draw(float x, float y, float a) {
            if (w <= 0) return;
            Theme.hud(x, y, w, h, 14, a, 0);
            header(x, y, w, "staff", "Staff", Integer.toString(list.size()));
            float yy = y + 38;
            if (list.isEmpty()) {
                Nvg.text("No staff online", x + 14, yy + 10, 11.5f, Nvg.REG, Nvg.LEFT, Theme.faint());
                return;
            }
            for (StaffScan.Entry e : list) {
                float tx = x + 12;
                if (heads.on()) {
                    var pe = mc.getNetworkHandler() == null ? null : mc.getNetworkHandler().getPlayerListEntry(e.name());
                    if (pe == null || !Skins.head(pe.getSkinTextures(), tx, yy + 2, 16, 4)) Nvg.rrect(tx, yy + 2, 16, 16, 4, Theme.hover());
                    tx += 24;
                }
                Nvg.text(e.name(), tx, yy + 10.5f, 12f, Nvg.MED, Nvg.LEFT, Theme.text());
                if (ranks.on()) {
                    float rw = Nvg.textWidth(e.rank(), 11f, Nvg.MED);
                    Nvg.text(e.rank(), x + w - 12, yy + 10.5f, 11f, Nvg.MED, Nvg.RIGHT, e.dev() ? Theme.text() : Theme.dim());
                    Icons.draw(e.dev() ? "code" : "staff", x + w - 12 - rw - 17, yy + 3.5f, 13, e.dev() ? Theme.text() : Theme.faint());
                }
                yy += 24;
            }
        }
    }

    /* ------------------------------------------------------------------ spotify */

    static final class SpotifyHud extends HudElement {
        private final Setting.Bool art = bool("Album Art", true);
        private final Setting.Bool progress = bool("Progress", true);
        private final SpotifyArt albumArt = new SpotifyArt();
        private NowPlaying now = NowPlaying.WAITING;
        private boolean card;
        private String idle = "";

        SpotifyHud() {
            super("Spotify", "Album art, title, artist and progress of the song playing on Spotify");
        }

        @Override
        public void defaults(float sw, float sh) {
            x = sw - 280;
            y = sh - 96;
        }

        @Override
        protected void onDisable() {
            albumArt.clear();
        }

        @Override
        public void measure() {
            now = SpotifyPoller.get().nowPlaying();
            card = now.active() && now.hasTrack() && now.source() == NowPlaying.Source.SESSION;
            if (card) {
                w = 268;
                h = progress.on() ? 80 : 62;
            } else {
                albumArt.clear();
                idle = switch (now.status()) {
                    case NOT_RUNNING -> "Spotify is not open";
                    case UNSUPPORTED -> "Spotify: Windows only";
                    case PAUSED, PLAYING -> now.hasTrack() ? now.title() + (now.artist().isEmpty() ? "" : "  -  " + now.artist()) : "Not playing";
                    default -> "Nothing playing";
                };
                idle = Nvg.fit(idle, 12, Nvg.MED, 260);
                w = 52 + Nvg.textWidth(idle, 12, Nvg.MED);
                h = 34;
            }
        }

        @Override
        public void draw(float x, float y, float a) {
            if (!card) {
                Theme.hud(x, y, w, h, 17, a, 12);
                Icons.draw("spotify", x + 12, y + 9, 16, Theme.text());
                Nvg.text(idle, x + 36, y + 17.5f, 12, Nvg.MED, Nvg.LEFT, Theme.dim());
                return;
            }
            Theme.hud(x, y, w, h, 18, a, 14);
            boolean hasArt = art.on() && albumArt.update(now.art());
            float s = 50;
            if (hasArt) albumArt.draw(x + 10, y + 10, s, 11, 1f);
            else {
                Nvg.rrect(x + 10, y + 10, s, s, 11, Theme.hover());
                Icons.draw("spotify", x + 10 + s / 2 - 10, y + 10 + s / 2 - 10, 20, Theme.dim());
            }
            float tx = x + 10 + (art.on() ? s + 12 : 2);
            float maxW = x + w - 36 - tx;
            Nvg.text(Nvg.fit(now.title(), 13, Nvg.SEMI, maxW), tx, y + 24, 13, Nvg.SEMI, Nvg.LEFT, Theme.text());
            Nvg.text(Nvg.fit(now.artist(), 11, Nvg.REG, maxW), tx, y + 41, 11, Nvg.REG, Nvg.LEFT, Theme.dim());
            // equaliser bars
            float t = System.nanoTime() / 1e9f;
            for (int i = 0; i < 3; i++) {
                float f = now.playing() ? 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * (4.1f + i * 1.7f) + i * 2f)) : 0.25f;
                float bh = 14 * f;
                Nvg.rrect(x + w - 26 + i * 5.5f, y + 24 - bh, 3.4f, bh, 1.7f, Theme.text());
            }
            if (progress.on() && now.hasTimeline()) {
                long pos = now.positionAt(System.currentTimeMillis());
                float f = Math.max(0f, Math.min(1f, pos / (float) now.durationMs()));
                float bx = x + 12, bw = w - 24, by = y + 62;
                Nvg.rrect(bx, by - 1.6f, bw, 3.2f, 1.6f, Theme.line());
                Nvg.rrect(bx, by - 1.6f, Math.max(3.2f, bw * f), 3.2f, 1.6f, Theme.text());
                Nvg.circle(bx + bw * f, by, 3.6f, Theme.text());
                Nvg.text(time(pos), bx, by + 9.5f, 9.5f, Nvg.MED, Nvg.LEFT, Theme.faint());
                Nvg.text(time(now.durationMs()), bx + bw, by + 9.5f, 9.5f, Nvg.MED, Nvg.RIGHT, Theme.faint());
            }
        }
    }

    /* ------------------------------------------------------------------ keybinds */

    static final class Keybinds extends HudElement {
        private final List<Module> bound = new ArrayList<>();

        Keybinds() {
            super("Keybinds", "Every module that has a key");
        }

        @Override
        public void defaults(float sw, float sh) {
            x = sw - 190;
            y = sh / 2 - 90;
        }

        @Override
        public void measure() {
            bound.clear();
            for (Module m : ModuleManager.all()) if (m.bind.code != -1 && !m.locked()) bound.add(m);
            float width = 150;
            for (Module m : bound) width = Math.max(width, 28 + Nvg.textWidth(m.name, 12f, Nvg.MED) + Nvg.textWidth(m.bind.display(), 10.5f, Nvg.SEMI) + 30);
            w = width;
            h = 36 + (bound.isEmpty() ? 22 : bound.size() * 24 + 2);
        }

        @Override
        public void draw(float x, float y, float a) {
            Theme.hud(x, y, w, h, 14, a, 12);
            header(x, y, w, "keyboard", "Keybinds", Integer.toString(bound.size()));
            float yy = y + 36;
            if (bound.isEmpty()) {
                Nvg.text("No binds", x + 14, yy + 9, 11, Nvg.REG, Nvg.LEFT, Theme.faint());
                return;
            }
            for (Module m : bound) {
                boolean on = m.enabled();
                Nvg.rrect(x + 6, yy, w - 12, 22, 8, on ? Nvg.alpha(Theme.text(), 0.9f) : Theme.hover());
                Nvg.text(m.name, x + 14, yy + 11.4f, 12f, Nvg.MED, Nvg.LEFT, on ? Theme.onFill() : Theme.text());
                String k = m.bind.display();
                float kw = Nvg.textWidth(k, 10.5f, Nvg.SEMI) + 12;
                Nvg.rrect(x + w - 12 - kw, yy + 4, kw, 14, 5, on ? Nvg.alpha(Theme.onFill(), 0.16f) : Theme.hover());
                Nvg.text(k, x + w - 12 - kw / 2, yy + 11.4f, 10.5f, Nvg.SEMI, Nvg.CENTER, on ? Theme.onFill() : Theme.dim());
                yy += 24;
            }
        }
    }

    /* ------------------------------------------------------------------ inventory */

    static final class InventoryHud extends HudElement {

        InventoryHud() {
            super("Inventory", "Your inventory with item counts and durability");
        }

        @Override
        public void defaults(float sw, float sh) {
            x = sw - 262;
            y = sh / 2 + 70;
        }

        @Override
        public void measure() {
            w = 9 * 26 + 16;
            h = 36 + 3 * 26 + 10;
            if (mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?> && !Hud.editing()) w = h = 0;
        }

        @Override
        public void draw(float x, float y, float a) {
            if (w <= 0) return;
            Theme.hud(x, y, w, h, 14, a, 12);
            int used = 0;
            if (mc.player != null) for (int i = 9; i < 36; i++) if (!mc.player.getInventory().getStack(i).isEmpty()) used++;
            header(x, y, w, "inventory", "Inventory", used + " / 27");
            if (mc.player == null) return;
            int rows = 3;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < 9; c++) {
                    int idx = r < 3 ? 9 + r * 9 + c : c;
                    ItemStack st = mc.player.getInventory().getStack(idx);
                    float xx = x + 8 + c * 26, yy = y + 36 + r * 26 + (r == 3 ? 4 : 0);
                    Nvg.rrect(xx, yy, 24, 24, 6, st.isEmpty() ? Nvg.alpha(Theme.text(), 0.04f) : Nvg.alpha(Theme.text(), 0.09f));
                    if (st.isEmpty()) continue;
                    ItemAtlas.draw(st, xx + 3, yy + 3, 18);
                    if (st.getCount() > 1) {
                        String n = Integer.toString(st.getCount());
                        Nvg.text(n, xx + 23, yy + 19, 9.5f, Nvg.SEMI, Nvg.RIGHT, 0xFF000000);
                        Nvg.text(n, xx + 22.4f, yy + 18.4f, 9.5f, Nvg.SEMI, Nvg.RIGHT, 0xFFFFFFFF);
                    }
                    if (st.isDamageable() && st.getDamage() > 0) {
                        float left = 1f - (float) st.getDamage() / Math.max(1, st.getMaxDamage());
                        Nvg.rrect(xx + 4, yy + 21, 16, 1.8f, 0.9f, Nvg.alpha(0xFF000000, 0.5f));
                        Nvg.rrect(xx + 4, yy + 21, 16 * left, 1.8f, 0.9f, left < 0.25f ? 0xFFFF5A5A : Theme.text());
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ base list */

    static final class BaseList extends HudElement {
        private List<WorldModules.Base> list = List.of();
        private boolean active;

        BaseList() {
            super("Base List", "Bases found by the Base Finder");
        }

        @Override
        public void defaults(float sw, float sh) {
            x = 12;
            y = sh / 2 - 60;
        }

        @Override
        public void measure() {
            list = WorldModules.BaseFinder.results();
            active = WorldModules.BaseFinder.I != null && WorldModules.BaseFinder.I.enabled();
            if (!active && !Hud.editing()) {
                w = h = 0;
                return;
            }
            w = 214;
            int n = Math.min(list.size(), 6);
            h = 36 + (n == 0 ? 22 : n * 34 + 2);
        }

        @Override
        public void draw(float x, float y, float a) {
            if (w <= 0) return;
            Theme.hud(x, y, w, h, 14, a, 12);
            header(x, y, w, "base", "Bases", Integer.toString(list.size()));
            float yy = y + 36;
            if (list.isEmpty()) {
                Nvg.text(active ? "Scanning loaded chunks..." : "Enable Base Finder", x + 14, yy + 9, 11, Nvg.REG, Nvg.LEFT, Theme.faint());
                return;
            }
            int i = 0;
            for (WorldModules.Base b : list) {
                if (i++ >= 6) break;
                Nvg.rrect(x + 6, yy, w - 12, 32, 9, Theme.hover());
                Nvg.text((int) b.x() + ", " + (int) b.y() + ", " + (int) b.z(), x + 14, yy + 11.5f, 12f, Nvg.SEMI, Nvg.LEFT, Theme.text());
                Nvg.text(b.storage() + " storage  " + b.beds() + " beds" + (b.underground() ? "  underground" : ""), x + 14, yy + 24, 10f, Nvg.REG, Nvg.LEFT, Theme.dim());
                String d = (int) b.distance() + "m";
                Nvg.text(d, x + w - 14, yy + 11.5f, 11f, Nvg.MED, Nvg.RIGHT, Theme.text());
                Nvg.text(b.score() + " pts", x + w - 14, yy + 24, 10f, Nvg.REG, Nvg.RIGHT, Theme.dim());
                yy += 34;
            }
        }
    }
}

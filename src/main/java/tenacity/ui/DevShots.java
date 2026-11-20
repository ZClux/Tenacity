package tenacity.ui;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import tenacity.core.Module;
import tenacity.core.ModuleManager;
import tenacity.Tenacity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

/** Developer aid (-Dtenacity.shots=<dir>): opens the menu, takes screenshots of the gui and the hud and quits. */
public final class DevShots {
    private static int stage, frames;
    /** Optional dev scenario from the TENACITY_DEV env var, e.g. "kill:Shatter", "trail:Helix", "land", "blur". */
    private static final String DEV = System.getenv("TENACITY_DEV") == null ? "" : System.getenv("TENACITY_DEV");
    private static final int DEV_FRAME = System.getenv("TENACITY_DEV_FRAME") == null ? 90 : Integer.parseInt(System.getenv("TENACITY_DEV_FRAME"));
    private static net.minecraft.entity.Entity devDummy;

    private static void devFx(Module m) {
        String arg = DEV.contains(":") ? DEV.substring(DEV.indexOf(':') + 1) : "";
        if (m.name.equals("Kill Effects") && DEV.startsWith("kill")) {
            m.setEnabled(true);
            tenacity.modules.DevHooks.setMode(m, "Effect", arg);
            for (var st : m.settings) if (st.name.equals("Kill Cutscene") || st.name.equals("Cutscene Players Only")) ((tenacity.core.Setting.Bool) st).value = st.name.equals("Kill Cutscene") && System.getenv("TENACITY_CUT") != null;
            for (var st : m.settings) if (st.name.equals("Cancel On Key")) ((tenacity.core.Setting.Bool) st).value = false;
            if (System.getenv("TENACITY_SHOT") != null) tenacity.modules.DevHooks.setMode(m, "Cutscene", System.getenv("TENACITY_SHOT"));
        }
        if ((m.name.equals("Breadcrumbs")) && DEV.startsWith("trail")) {
            m.setEnabled(true);
            tenacity.modules.DevHooks.setMode(m, "Style", arg);
            for (Module t : ModuleManager.all()) if (t.name.equals("Trails")) tenacity.modules.DevHooks.setMode(t, "Style", arg);
        }
        if (m.name.equals("Motion Blur") && DEV.startsWith("blur")) m.setEnabled(true);
        if ((m.name.equals("Land Impact") || m.name.equals("Footprints")) && DEV.startsWith("land")) {
            m.setEnabled(true);
            tenacity.modules.DevHooks.setMode(m, "Style", "Paw");
            for (var st : m.settings) {
                if (st.name.equals("Min Fall")) ((tenacity.core.Setting.Num) st).value = 0.5;
                if (st.name.equals("Other Players")) ((tenacity.core.Setting.Bool) st).value = true;
            }
        }
    }

    private static boolean skyDone;
    private static BufferedImage skySheet;

    /** TENACITY_DEV=sky[:from-to]: cycles Shader Sky modes and writes a contact sheet sky_sheet.png. */
    private static boolean skyStep(MinecraftClient mc, String dir) {
        boolean season = DEV.startsWith("seasky");
        String arg = DEV.contains(":") ? DEV.substring(DEV.indexOf(':') + 1) : (season ? "100-115" : "10-33");
        int from = Integer.parseInt(arg.split("-")[0]), to = Integer.parseInt(arg.contains("-") ? arg.split("-")[1] : arg.split("-")[0]);
        int dw = season ? 90 : 16;
        int idx = from + frames / dw, n = to - from + 1;
        Module sky = null;
        for (Module m : ModuleManager.all()) if (m.name.equals(season ? "Seasonal Sky" : "Shader Sky")) sky = m;
        if (sky == null) { skyDone = true; return false; }
        if (idx > to) {
            try { ImageIO.write(skySheet, "png", new File(dir, season ? "seasky_sheet.png" : "sky_sheet.png")); } catch (Exception e) { Tenacity.LOG.error("sheet", e); }
            skyDone = true;
            frames = DEV_FRAME - 1;
            return true;
        }
        if (season) {
            sky.setEnabled(true);
            boolean xm = idx < 108;
            for (var st : sky.settings) {
                if (st.name.equals("Theme")) ((tenacity.core.Setting.Mode) st).index = xm ? 0 : 1;
                if (st.name.equals("Christmas Sky") && xm) ((tenacity.core.Setting.Mode) st).index = idx - 100;
                if (st.name.equals("Halloween Sky") && !xm) ((tenacity.core.Setting.Mode) st).index = idx - 108;
            }
        } else
        for (var st : sky.settings) if (st.name.equals("Mode")) ((tenacity.core.Setting.Mode) st).index = idx;
        if (frames % dw == dw - 2) {
            BufferedImage img = grab(mc);
            int cols = Math.min(n, 4), rows = (n + 3) / 4, tw = 640, th = tw * img.getHeight() / img.getWidth();
            if (skySheet == null) skySheet = new BufferedImage(tw * cols, th * rows, BufferedImage.TYPE_INT_RGB);
            int k = idx - from;
            var g = skySheet.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, (k % 4) * tw, (k / 4) * th, tw, th, null);
            g.setColor(java.awt.Color.YELLOW);
            g.drawString(String.valueOf(idx), (k % 4) * tw + 6, (k / 4) * th + 16);
            g.dispose();
            if (n == 1 || System.getenv("TENACITY_SKYEACH") != null) { try { ImageIO.write(img, "png", new File(dir, (season ? "seasky_" : "sky_") + idx + ".png")); } catch (Exception ignored) { } }
        }
        return true;
    }

    private DevShots() {
    }

    public static boolean enabled() {
        return System.getProperty("tenacity.shots") != null;
    }

    public static void afterFrame(MinecraftClient mc) {
        if (!enabled()) return;
        frames++;
        String dir = System.getProperty("tenacity.shots");
        switch (stage) {
            case 0 -> {
                if (mc.player != null && mc.world != null && frames > 400) {
                    for (Module m : ModuleManager.all()) {
                        switch (m.name) {
                            case "Aspect Ratio", "Custom FOV" -> m.setEnabled(true);
                            case "Trails", "Halo", "Wings", "Orbit Orbs", "Ground Rings", "Spirit Aura", "Hit Ripple", "Ambient Particles" -> m.setEnabled(true);
                            case "World Style" -> { m.setEnabled(true); for (var st : m.settings) { if (st.name.equals("Style")) ((tenacity.core.Setting.Mode) st).index = System.getenv("TENACITY_WS") != null ? Integer.parseInt(System.getenv("TENACITY_WS")) : 40; if (st.name.equals("Reflection")) ((tenacity.core.Setting.Bool) st).value = true; } }
                            case "China Hat" -> { m.setEnabled(true); for (var st : m.settings) if (st.name.equals("Other Players")) ((tenacity.core.Setting.Bool) st).value = true; }
                            case "Kill Effects", "Motion Blur", "Land Impact", "Footprints", "Breadcrumbs" -> devFx(m);
                            case "Galaxy Ring", "Ice Crown", "Hex Wings", "Holo Grid", "Lanterns", "Silk Ribbons", "Mana Aura", "Star Dust", "Sakura", "Ghost Trail", "Rune Orbit", "Crosshair Halo", "Screen Bokeh", "Hit Shockwave", "Cinematic Bars" -> { String pv = System.getenv("TENACITY_PV"); if (pv != null && (pv.equals("all") || pv.contains(m.name))) { m.setEnabled(true); if (pv.contains("3P")) mc.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK); } }
                            case "Shader Sky" -> { if (DEV.startsWith("sky")) m.setEnabled(true); }
                            case "Sus Chunk Finder" -> { if (DEV.startsWith("sus")) { tenacity.modules.donut.SusChunkFinder.devFake = true; for (var st : m.settings) { if (st.name.equals("Follow My Height")) ((tenacity.core.Setting.Bool) st).value = false; if (st.name.equals("Height")) ((tenacity.core.Setting.Num) st).value = Math.floor(mc.player.getY()); if (st.name.equals("Style") && System.getenv("TENACITY_SUSSTYLE") != null) ((tenacity.core.Setting.Mode) st).index = Integer.parseInt(System.getenv("TENACITY_SUSSTYLE")); } m.setEnabled(!DEV.contains("motion")); } }
                            case "Santa Hat", "Snowfall", "Christmas Lights", "Candy Cane Trail", "Gift Burst", "Snow Globe", "Reindeer Antlers", "Jingle Sparkles", "Northern Star", "Frosty Breath", "Ornament Orbit", "Mistletoe", "Gingerbread Steps", "Holly Wreath", "Festive Kill Effect", "Pumpkin Head", "Ghost Companions", "Bat Swarm", "Witch Hat", "Spider Web", "Spooky Fog", "Floating Candles", "Soul Flames", "Skull Kill Effect", "Haunted Trail" -> { String fv = System.getenv("TENACITY_FEST"); if (fv != null && java.util.Arrays.asList(fv.split(",")).contains(m.name)) m.setEnabled(true); }
                            case "Sky Visuals", "Sky Lyrics", "Creamy Keys" -> { String sv = System.getenv("TENACITY_SKYVIS"); if (sv != null) { m.setEnabled(true); for (var st : m.settings) if (st.name.equals("Theme") || st.name.equals("Position")) ((tenacity.core.Setting.Mode) st).index = st.name.equals("Theme") ? Integer.parseInt(sv) : 3; } }
                            case "Music Visualiser" -> { String mv = System.getenv("TENACITY_MUSICVIS"); if (mv != null) { m.setEnabled(true); for (var st : m.settings) { if (st.name.equals("Style")) ((tenacity.core.Setting.Mode) st).index = Integer.parseInt(mv); if (st.name.equals("Spotify Only")) ((tenacity.core.Setting.Bool) st).value = false; } } }
                            case "Knifes" -> { String kn = System.getenv("TENACITY_KNIFE"); if (kn != null) { m.setEnabled(true); for (var st : m.settings) { if (st.name.equals("Knife")) ((tenacity.core.Setting.Mode) st).index = Integer.parseInt(kn); if (st.name.equals("Replace")) ((tenacity.core.Setting.Mode) st).index = 1; } } }
                            case "Snowfall FX" -> { String sf = System.getenv("TENACITY_SNOWFX"); if (sf != null) { m.setEnabled(true); for (var st : m.settings) if (st.name.equals("Preset")) ((tenacity.core.Setting.Mode) st).index = Integer.parseInt(sf); } }
                            case "Trigger Bot" -> { m.expanded = true; if ("1".equals(System.getenv("TENACITY_STYLE"))) tenacity.ui.ClickGui.devBox("Trigger Bot"); }
                            case "Interface" -> { if (System.getenv("TENACITY_STYLE") != null) tenacity.modules.Interface.I.guiStyle.index = Integer.parseInt(System.getenv("TENACITY_STYLE")); if (System.getenv("TENACITY_THEME") != null) tenacity.modules.Interface.I.theme.index = Integer.parseInt(System.getenv("TENACITY_THEME")); m.expanded = System.getenv("TENACITY_SEL") != null; if (System.getenv("TENACITY_SEL") != null) { tenacity.modules.Interface.I.selector.index = Integer.parseInt(System.getenv("TENACITY_SEL")); tenacity.modules.Interface.I.selector.open = System.getenv("TENACITY_SEL").equals("0"); } }
                            case "Aim Assist" -> m.expanded = false;
                            default -> {
                            }
                        }
                    }
                    if (DEV.startsWith("vis:")) {
                        var want = java.util.Arrays.asList(DEV.substring(4).split(","));
                        var noisy = java.util.List.of("Trails", "Halo", "Wings", "Orbit Orbs", "Ground Rings", "Spirit Aura", "Hit Ripple", "Ambient Particles", "China Hat", "World Style");
                        for (Module vm : ModuleManager.all()) {
                            if (want.contains(vm.name)) {
                                vm.setEnabled(true);
                                for (var st : vm.settings) if (st.name.equals("Other Players")) ((tenacity.core.Setting.Bool) st).value = true;
                            } else if (noisy.contains(vm.name)) vm.setEnabled(false);
                        }
                    }
                    if (System.getenv("TENACITY_CRYSTAL") != null) crystals(mc, System.getenv("TENACITY_CRYSTAL"));
                    if (System.getenv("TENACITY_3P") != null) mc.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
                    for (Module hm : ModuleManager.all()) if (hm.name.equals("Hand Shaders")) ((tenacity.core.Setting.Mode) hm.settings.get(0)).index = 0;
                    for (Module hm : ModuleManager.all()) if (hm.name.equals("Hand Shaders"))
                        for (var st : hm.settings) { if (st.name.equals("Glow Color")) ((tenacity.core.Setting.Mode) st).index = 3; if (st.name.equals("Frost")) ((tenacity.core.Setting.Num) st).value = 90; if (st.name.equals("Edge Light")) ((tenacity.core.Setting.Num) st).value = 60; if (st.name.equals("Light Sweep")) ((tenacity.core.Setting.Num) st).value = 60; }
                    for (Module pm : ModuleManager.all()) if (pm.name.equals("Player Transparency")) for (var st : pm.settings) if (st.name.equals("Look")) ((tenacity.core.Setting.Mode) st).index = 3;
                    for (Module pm : ModuleManager.all()) if (pm.name.equals("Shader ESP")) for (var st : pm.settings) if (st.name.equals("Color")) ((tenacity.core.Setting.Mode) st).index = 2;
                    var cm = ModuleManager.of(tenacity.core.Category.COMBAT);
                    if (cm.size() > 0 && System.getenv("TENACITY_SEL") == null) cm.get(0).expanded = true;
                    if (cm.size() > 1) cm.get(1).setEnabled(true);
                    if (cm.size() > 3) cm.get(3).bind.code = 82;
                    for (int d = 0; d < 3; d++) {
                        var dummy = new net.minecraft.client.network.OtherClientPlayerEntity(mc.world,
                                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Dummy" + d));
                        dummy.refreshPositionAndAngles(mc.player.getX() + 3 + d * 1.5, mc.player.getY(), mc.player.getZ() - 4 - d * 2, 160 + d * 20, 0);
                        dummy.setId(-1000 - d);
                        dummy.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_CHESTPLATE));
                        dummy.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_HELMET));
                        mc.world.addEntity(dummy);
                        if (d == 0) {
                            devDummy = dummy;
                            tenacity.modules.DevHooks.hit(dummy);
                        }
                    }
                    String nm = System.getenv("TENACITY_NAMES");
                    if (nm != null) {
                        // "protect" (self), "everyone", "scramble" (scope Others), "skin" (everyone), "all" = protect everyone + skin
                        for (Module m : ModuleManager.all()) {
                            if (m.name.equals("Name Protect") && (nm.contains("protect") || nm.contains("everyone") || nm.equals("all"))) {
                                m.setEnabled(true);
                                if (nm.contains("everyone") || nm.equals("all")) for (var st : m.settings) if (st.name.equals("Mode")) ((tenacity.core.Setting.Mode) st).index = 1;
                            }
                            if (m.name.equals("Name Scrambler") && nm.contains("scramble")) {
                                m.setEnabled(true);
                                for (var st : m.settings) if (st.name.equals("Scope")) ((tenacity.core.Setting.Mode) st).index = 2;
                            }
                            if (m.name.equals("Skin Protect") && (nm.contains("skin") || nm.equals("all"))) {
                                m.setEnabled(true);
                                for (var st : m.settings) if (st.name.equals("Target")) ((tenacity.core.Setting.Mode) st).index = 1;
                            }
                        }
                    }
                    mc.player.setPitch(45);
                    mc.player.setYaw(-148);
                    if (DEV.startsWith("sky") || DEV.startsWith("seasky")) {
                        mc.player.setPitch(-14);
                        mc.options.getCloudRenderMode().setValue(net.minecraft.client.option.CloudRenderMode.OFF);
                        for (Module wm : ModuleManager.all()) if (wm.name.equals("World Style") || (DEV.startsWith("seasky") && (wm.name.equals("Aspect Ratio") || wm.name.equals("Custom FOV")))) wm.setEnabled(false);
                    }
                    mc.setScreen(new TenacityScreen());
                    stage = 1;
                    frames = 0;
                }
            }
            case 1 -> {
                String cfgTab = System.getenv("TENACITY_CFGTAB");
                if (frames == 120) shot(mc, dir, "gui.png");
                if (cfgTab != null && frames == 122) ConfigPanel.openTab(Integer.parseInt(cfgTab));
                if (cfgTab != null && frames == 300) shot(mc, dir, "configs.png");
                boolean chatDemo = System.getenv("TENACITY_CHATDEMO") != null;
                if (chatDemo && frames == 124) { if (System.getenv("TENACITY_CHATLIVE") == null) tenacity.core.Chat.demo(); else tenacity.core.Chat.ensure(); ChatPanel.openDev(); }
                if (chatDemo && frames == 200 && System.getenv("TENACITY_CHATLIVE") != null) tenacity.core.Chat.send("hello from the tenacity harness");
                if (chatDemo && frames == 330) shot(mc, dir, "chat.png");
                if (frames == (cfgTab != null ? 302 : chatDemo ? 332 : 120)) {
                    mc.setScreen(null);
                    stage = 2;
                    frames = 0;
                }
            }
            case 2 -> {
                if (DEV.startsWith("seasky") && mc.player != null) { mc.player.setPitch(System.getenv("TENACITY_SKYPITCH") != null ? Float.parseFloat(System.getenv("TENACITY_SKYPITCH")) : -14); mc.player.setYaw(-148); mc.player.setVelocity(0, 0, 0); }
                if ((DEV.startsWith("sky") || DEV.startsWith("seasky")) && !skyDone && skyStep(mc, dir)) return;
                if (DEV.startsWith("sus") && mc.player != null) {
                    // stand at the middle of the chunk, 20 blocks up looking down at the first fake chunk (offset 0,-1 so it sits right under the crosshair)
                    double bx = ((mc.player.getBlockX() >> 4) << 4) + 8.0, bz = ((mc.player.getBlockZ() >> 4) << 4) + 8.0;
                    if (frames == 1) { mc.player.refreshPositionAndAngles(bx, mc.player.getY() + 20, bz, 0, 0); mc.player.setVelocity(0, 0, 0); }
                    mc.player.setNoGravity(true);
                    mc.player.setVelocity(0, 0, 0);
                    double tx = bx, tz = bz - 16.0, ddx = tx - mc.player.getX(), ddz = tz - mc.player.getZ(), dd = Math.hypot(ddx, ddz);
                    double eye = mc.player.getEyeY() - (Math.floor(mc.player.getY() - 20));
                    mc.player.setYaw((float) Math.toDegrees(Math.atan2(-ddx, ddz)));
                    mc.player.setPitch((float) Math.toDegrees(Math.atan2(eye, dd)));
                }
                if (frames == 30 && mc.player != null && !DEV.startsWith("sus")) mc.player.jump();
                if (System.getenv("TENACITY_CRYSTAL") != null && mc.player != null) { mc.player.setPitch(System.getenv("TENACITY_PITCH") != null ? Float.parseFloat(System.getenv("TENACITY_PITCH")) : 14); mc.player.setYaw(-148); }
                if (System.getenv("TENACITY_WSPITCH") != null && mc.player != null) mc.player.setPitch(Float.parseFloat(System.getenv("TENACITY_WSPITCH")));
                if (frames == 40 && DEV.startsWith("kill") && devDummy != null) tenacity.modules.DevHooks.kill(devDummy);
                if ((DEV.contains("glide") || DEV.contains("stand")) && mc.player != null) mc.player.setPitch(DEV.contains("glide") ? 4 : 18);
                if ((DEV.contains("glide") || DEV.contains("stand")) && mc.player != null) for (int d = 0; d < 3; d++) {
                    if (!(mc.world.getEntityById(-1000 - d) instanceof net.minecraft.entity.player.PlayerEntity gp)) continue;
                    if (DEV.contains("glide")) tenacity.modules.DevHooks.glide(gp);
                    gp.refreshPositionAndAngles(mc.player.getX() + 1.0 + d * 1.6, mc.player.getY() + (DEV.contains("glide") ? 1.0 : 0), mc.player.getZ() - 3.2 + d * 0.9, 160 + d * 20, d * 25 - 10);
                    gp.setBodyYaw(160 + d * 20);
                    gp.setHeadYaw(160 + d * 20);
                }
                if ((DEV.startsWith("trail") || DEV.startsWith("land")) && devDummy != null) {
                    devDummy.setOnGround(true);
                    double a = frames * 0.06;
                    devDummy.refreshPositionAndAngles(mc.player.getX() + 3 + Math.cos(a) * 2.5, mc.player.getY(), mc.player.getZ() - 5 + Math.sin(a) * 2.5, 0, 0);
                }
                if (frames == DEV_FRAME) {
                    shot(mc, dir, "hud.png");
                    mc.setScreen(new TenacityMenu());
                    stage = 4;
                    frames = 0;
                }
            }
            case 3 -> mc.scheduleStop();
            case 4 -> {
                if (frames == 150) {
                    shot(mc, dir, "menu.png");
                    mc.setScreen(new ServersScreen(new TenacityMenu()));
                    stage = 5;
                    frames = 0;
                }
            }
            case 5 -> {
                if (frames == 120) {
                    shot(mc, dir, "servers.png");
                    stage = 3;
                }
            }
            default -> {
            }
        }
    }

    /** TENACITY_CRYSTAL=all|<style index>: spawns client side End Crystals in front of the camera, Crystal Shader on. */
    private static void crystals(MinecraftClient mc, String arg) {
        for (Module m : ModuleManager.all()) if (m.name.equals("Crystal Shader")) {
            m.setEnabled(true);
            if (arg.equals("all")) tenacity.modules.CrystalShader.devAllStyles = true;
            else if (arg.matches("[0-9]+")) ((tenacity.core.Setting.Mode) m.settings.get(0)).index = Integer.parseInt(arg);
            else m.setEnabled(false);
        }
        mc.player.setYaw(-148);
        double yaw = Math.toRadians(-148), fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        int n = arg.equals("all") ? 6 : 3;
        for (int i = 0; i < n; i++) {
            double side = (i - (n - 1) / 2.0) * 1.6, dist = 4.2 + Math.abs(i - (n - 1) / 2.0) * 0.5;
            var c = new net.minecraft.entity.decoration.EndCrystalEntity(mc.world, mc.player.getX() + fx * dist + rx * side, mc.player.getY(), mc.player.getZ() + fz * dist + rz * side);
            c.setId(-2000 - i);
            if (i == n - 1) c.setBeamTarget(c.getBlockPos().up(6).add(2, 0, 0));
            mc.world.addEntity(c);
        }
    }

    private static void shot(MinecraftClient mc, String dir, String name) {
        BufferedImage img = grab(mc);
        try {
            new File(dir).mkdirs();
            ImageIO.write(img, "png", new File(dir, name));
            Tenacity.LOG.info("saved {}", name);
        } catch (Exception e) {
            Tenacity.LOG.error("shot failed", e);
        }
    }

    private static BufferedImage grab(MinecraftClient mc) {
        int w = mc.getWindow().getFramebufferWidth(), h = mc.getWindow().getFramebufferHeight();
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        int prev = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prev);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = ((h - 1 - y) * w + x) * 4;
                img.setRGB(x, y, (buf.get(i) & 255) << 16 | (buf.get(i + 1) & 255) << 8 | (buf.get(i + 2) & 255));
            }
        }
        return img;
    }
}

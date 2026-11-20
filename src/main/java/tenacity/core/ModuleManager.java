package tenacity.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import tenacity.Tenacity;
import tenacity.modules.Interface;
import tenacity.modules.Modules;
import tenacity.ui.TenacityScreen;
import tenacity.util.StaffScan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ModuleManager {
    private static final List<Module> MODULES = new ArrayList<>();
    private static final Map<Module, Boolean> HELD = new HashMap<>();
    private static boolean menuHeld;

    private ModuleManager() {
    }

    public static void init() {
        MODULES.addAll(Modules.create());
        for (Module m : MODULES) {
            String n = m.name;
            if (n.equals("Interface") || n.equals("Watermark") || n.equals("Module List") || n.equals("Coordinates") || n.equals("Staff List")) m.restore(true);
        }
        Tenacity.LOG.info("{} modules registered", MODULES.size());
    }

    public static List<Module> all() {
        return MODULES;
    }

    public static List<Module> of(Category c) {
        List<Module> out = new ArrayList<>();
        for (Module m : MODULES) if (m.category == c) out.add(m);
        return out;
    }

    private static boolean down(MinecraftClient mc, int code) {
        if (code == -1) return false;
        if (code <= -100) return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), -100 - code) == GLFW.GLFW_PRESS;
        return InputUtil.isKeyPressed(mc.getWindow(), code);
    }

    public static void tick(MinecraftClient mc) {
        if (mc.getWindow() == null) return;
        if (mc.currentScreen != null && mc.currentScreen.getClass() == net.minecraft.client.gui.screen.TitleScreen.class) mc.setScreen(new tenacity.ui.TenacityMenu());
        boolean menu = mc.currentScreen == null && down(mc, Interface.I.menuKey.code);
        if (menu && !menuHeld) mc.setScreen(new TenacityScreen());
        menuHeld = menu;

        if (mc.currentScreen == null) {
            for (Module m : MODULES) {
                if (m.bind.code == -1 || m.locked()) continue;
                boolean d = down(mc, m.bind.code);
                boolean was = HELD.getOrDefault(m, false);
                if (d && !was) m.toggle();
                HELD.put(m, d);
            }
        }

        if (mc.player == null || mc.world == null) return;
        StaffScan.tick(mc);
        for (Module m : MODULES) {
            if (!m.enabled()) continue;
            try {
                m.tick();
            } catch (Throwable t) {
                Tenacity.LOG.error("module {} crashed in tick - disabling", m.name, t);
                m.setEnabled(false);
            }
        }
    }

    public static void preTick(net.minecraft.client.MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        for (Module m : MODULES) {
            if (!m.enabled()) continue;
            try {
                m.preTick();
            } catch (Throwable t) {
                Tenacity.LOG.error("module {} crashed in preTick - disabling", m.name, t);
                m.setEnabled(false);
            }
        }
    }

    public static void renderWorld(voided.events.impl.WorldRenderEvent event) {
        for (Module m : MODULES) {
            if (!m.enabled() && !m.lingers()) continue;
            try {
                m.renderWorld(event);
            } catch (Throwable t) {
                Tenacity.LOG.error("module {} crashed in world render - disabling", m.name, t);
                m.setEnabled(false);
            }
        }
    }

    /** Draws the 2D parts of the modules (ESP, markers) inside a NanoVG frame. */
    public static void render2d(float td) {
        for (Module m : MODULES) {
            if (!m.enabled() && !m.lingers()) continue;
            try {
                m.render2d(td);
            } catch (Throwable t) {
                Tenacity.LOG.error("module {} crashed in render - disabling", m.name, t);
                m.setEnabled(false);
            }
        }
    }
}

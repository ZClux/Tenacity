package tenacity.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.Window;
import tenacity.Tenacity;
import tenacity.core.ModuleManager;
import tenacity.gfx.Glass;
import tenacity.gfx.Nvg;
import tenacity.hud.Hud;
import tenacity.modules.Interface;

/** Draws the ESP layer, the HUD, notifications and the click gui every frame, right before buffers are swapped. */
public final class Overlay {
    private static long last = System.nanoTime();
    private static boolean errored;

    private Overlay() {
    }

    private static boolean wasDown, wasRight;
    private static float appliedScale = 1f, shownScale = 1f;
    /** The hud draws 30% larger than the click gui - the world overlays always use this scale. */
    public static final float HUD_ZOOM = 1.3f;

    public static float hudScale() {
        return shownScale * HUD_ZOOM;
    }

    /** The hud widgets can be moved while the chat is open. */
    private static void chatDrag(MinecraftClient mc, float hs) {
        boolean down = org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), 0) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        float mx = (float) mc.mouse.getX() / hs, my = (float) mc.mouse.getY() / hs;
        if (down && !wasDown) {
            var e = Hud.at(mx, my);
            if (e != null) Hud.startDrag(e, mx, my);
        }
        if (down) Hud.drag(mx, my);
        else Hud.endDrag();
        wasDown = down;
        boolean right = org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), 1) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (right && !wasRight) {
            var e = Hud.at(mx, my);
            if (e != null) {
                e.setEnabled(false);
                Toasts.push("hud", e.name + " removed", "Turn it back on in the HUD tab");
            }
        }
        wasRight = right;
    }

    public static void render() {
        if (errored) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Window win = mc.getWindow();
        if (win == null || win.getFramebufferWidth() <= 0 || win.getFramebufferHeight() <= 0) return;
        long now = System.nanoTime();
        float dt = Math.max(0.0005f, Math.min(0.1f, (now - last) / 1e9f));
        last = now;

        Screen screen = mc.currentScreen;
        boolean gui = screen instanceof TenacityScreen;
        boolean menu = screen instanceof NvgScreen;
        boolean world = mc.player != null && mc.world != null;
        boolean hud = world && !mc.options.hudHidden && (screen == null || gui || screen instanceof ChatScreen || screen instanceof HandledScreen);
        if (!gui && !hud && !menu) {
            Glass.skip();
            DevShots.afterFrame(mc);
            return;
        }
        if (!Nvg.init() || Interface.I == null) return;

        Anim.dt = dt;
        try {
            if (gui || hud || menu) Glass.capture(win.getFramebufferWidth(), win.getFramebufferHeight());
            else Glass.skip();

            if (!ClickGui.draggingSlider()) appliedScale = Interface.I.uiScale();
            shownScale = Anim.approach(shownScale, appliedScale, 12f);
            float scale = shownScale;
            float hs = gui || menu ? scale : scale * HUD_ZOOM;
            Nvg.begin(win, hs);
            try {
                if (gui) {
                    float sc = scale;
                    ClickGui.mx = (float) mc.mouse.getX() / sc;
                    ClickGui.my = (float) mc.mouse.getY() / sc;
                    ClickGui.backdrop();
                }
                if (world && (screen == null || gui)) {
                    // the world overlays always render in hud scale, so opening the click gui cannot move them
                    // Glass bakes Nvg.extraScale() when it is queued, so the plates follow this transform by
                    // themselves: no Theme.gs bookkeeping to get out of step with the text.
                    if (gui) Nvg.pushScale(HUD_ZOOM);
                    try {
                        ModuleManager.render2d(mc.getRenderTickCounter().getTickProgress(false));
                    } finally {
                        if (gui) Nvg.popScale();
                    }
                }
                if (screen instanceof ChatScreen) chatDrag(mc, hs);
                if (hud && !gui) {
                    Hud.render();
                    Toasts.render(dt);
                }
                if (menu) {
                    Widgets.begin(scale);
                    ((NvgScreen) screen).draw(dt);
                    Widgets.end();
                }
                if (gui) {
                    Nvg.layer();
                    ClickGui.draw(dt);
                }
            } finally {
                Nvg.end();
            }
            DevShots.afterFrame(mc);
        } catch (Throwable t) {
            errored = true;
            Tenacity.LOG.error("Overlay render failed - overlay disabled until restart", t);
        }
    }
}

package tenacity.modules;

import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.ItemTags;
import org.lwjgl.glfw.GLFW;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.CsKnives;

/** First person CS2 knives with their own idle, deploy, slash and inspect animations. */
public final class Knifes extends Module {
    public static Knifes I;

    public final Setting.Mode knife = mode("Knife", "Karambit", CsKnives.LABELS);
    public final Setting.Mode replace = mode("Replace", "Swords", "Swords", "Always", "Empty hand");
    public final Setting.Bool arms = bool("Arms", true);
    public final Setting.Mode leftHand = mode("Left Hand", "Inspect", "Always", "Inspect", "Never");
    public final Setting.Num size = num("Size", 1.0, 0.4, 1.6, 0.05);
    public final Setting.Num x = num("Hand X", 0, -2, 2, 0.05);
    public final Setting.Num y = num("Hand Y", 0, -2, 2, 0.05);
    public final Setting.Num z = num("Hand Z", 0, -2, 2, 0.05);
    public final Setting.Key inspectKey = add(new Setting.Key("Inspect Key", GLFW.GLFW_KEY_G));

    private boolean down;

    public Knifes() {
        super("Knifes", "CS2 knives in your hand with real draw, slash and inspect animations", Category.RENDER);
        I = this;
    }

    @Override
    public String suffix() {
        return knife.get();
    }

    public boolean replaces(ItemStack stack) {
        return switch (replace.get()) {
            case "Swords" -> stack.isIn(ItemTags.SWORDS);
            case "Empty hand" -> stack.isEmpty();
            default -> true;
        };
    }

    @Override
    public void tick() {
        int code = inspectKey.code;
        boolean pressed = code != -1 && mc.currentScreen == null && mc.getWindow() != null
                && (code <= -100 ? GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), -100 - code) == GLFW.GLFW_PRESS
                : InputUtil.isKeyPressed(mc.getWindow(), code));
        if (pressed && !down) CsKnives.inspect();
        down = pressed;
    }
}

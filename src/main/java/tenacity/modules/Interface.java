package tenacity.modules;

import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.gfx.Glass;
import tenacity.ui.Theme;

/** Global look of the client: glass quality and the menu key. */
public final class Interface extends Module {
    public static Interface I;

    public final Setting.Num scale = num("UI Scale", 100, 70, 150, 5);
    public final Setting.Num roundness = num("Roundness", 60, 0, 100, 5);
    public final Setting.Mode guiStyle = mode("GUI Style", "Panels", "Panels", "Box");
    public final Setting.Mode theme = mode("Theme", "Default", "Default", "Christmas", "Halloween");
    public final Setting.Mode selector = mode("Mode Selector", "Dropdown", "Dropdown", "Box", "Cycle");
    public final Setting.Bool notify = bool("Notifications", true);
    public final Setting.Key menuKey = add(new Setting.Key("Menu Key", 344));

    public Interface() {
        super("Interface", "Theme, liquid glass and menu settings", Category.HUD);
        locked = true;
        I = this;
    }

    @Override
    public boolean showInList() {
        return false;
    }

    @Override
    public void tick() {
        Glass.blurLevels = Theme.BLUR_LEVELS;
    }

    /** 0 = default, 1 Christmas, 2 Halloween. */
    public static int themeId() {
        return I == null ? 0 : I.theme.index;
    }

    public static boolean christmas() {
        return I != null && I.theme.get().equals("Christmas");
    }

    public float uiScale() {
        return scale.f() / 100f;
    }
}

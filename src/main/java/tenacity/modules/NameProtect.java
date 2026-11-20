package tenacity.modules;

import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Replaces usernames in every rendered text (Minecraft text and the client's own UI). */
public final class NameProtect extends Module {
    public static NameProtect I;

    final Setting.Text replacement = text("Replacement", "Protected", 24);
    final Setting.Mode mode = mode("Mode", "Self", "Self", "Everyone", "Friends");
    final Setting.Text friendList = text("Friends", "", 120);
    final Setting.Bool caseInsensitive = bool("Case Insensitive", true);
    public final Setting.Bool debugScreen = bool("Protect F3 UUID", true);
    public final Setting.Bool hideIp = bool("Hide Server IP", false);

    private String friendSig = "";
    private String[] friendCache = new String[0];

    public NameProtect() {
        super("Name Protect", "Replaces your name (or everyone's) in all text, nametags and the client UI", Category.PLAYER);
        I = this;
        friendList.showIf(() -> mode.is("Friends"));
    }

    @Override
    protected void onEnable() {
        NameCore.invalidate();
    }

    @Override
    protected void onDisable() {
        NameCore.invalidate();
    }

    @Override
    public String suffix() {
        return mode.get();
    }

    int hash() {
        int h = replacement.value.hashCode();
        h = h * 31 + mode.index;
        h = h * 31 + (caseInsensitive.value ? 1 : 0);
        h = h * 31 + friendList.value.hashCode();
        return h;
    }

    String[] friends() {
        String v = friendList.value;
        if (!v.equals(friendSig)) {
            friendSig = v;
            List<String> out = new ArrayList<>();
            for (String s : v.split("[,; ]+")) if (!s.isBlank()) out.add(s.trim());
            friendCache = out.toArray(new String[0]);
        }
        return friendCache;
    }

    boolean isFriend(String name) {
        for (String f : friends()) if (f.equalsIgnoreCase(name)) return true;
        return false;
    }

    boolean covers(NameCore.Target t) {
        if (t.self()) return true;
        return switch (mode.get()) {
            case "Everyone" -> true;
            case "Friends" -> t.friend();
            default -> false;
        };
    }

    String replacementFor(String name, boolean self) {
        if (self || !mode.is("Everyone")) return replacement.value.isEmpty() ? "Protected" : replacement.value;
        return "Player " + NameCore.playerNumber(name.toLowerCase(Locale.ROOT));
    }
}

package tenacity.modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Replaces player skins client side so nobody (including screenshots) can identify you. The same substitution is used
 * by the entity renderer, the tab list and the client's own UI (menu model, heads in the HUD).
 */
public final class SkinProtect extends Module {
    public static SkinProtect I;

    /** Order of the vanilla default skins, matches {@code DefaultSkinHelper.SKINS} (slim 0-8, wide 9-17). */
    private static final String[] DEFAULTS = {"Alex", "Ari", "Efe", "Kai", "Makena", "Noor", "Steve", "Sunny", "Zuri"};

    final Setting.Mode target = mode("Target", "Self", "Self", "Everyone", "Others");
    final Setting.Mode skin = mode("Skin", "Steve", "Steve", "Alex", "Ari", "Efe", "Kai", "Makena", "Noor", "Sunny", "Zuri", "Random Per Player");
    final Setting.Bool hideCape = bool("Hide Cape", true);
    final Setting.Bool hideElytra = bool("Hide Elytra Skin", true);
    final Setting.Bool hideEars = bool("Hide Ears", true);

    private final Map<UUID, SkinTextures> cache = new HashMap<>();
    private int lastHash = -1;

    public SkinProtect() {
        super("Skin Protect", "Swaps skins for a default one so you cannot be identified", Category.PLAYER);
        I = this;
    }

    @Override
    public String suffix() {
        return skin.get();
    }

    private void checkCache() {
        int h = (target.index * 31 + skin.index) * 31 + (hideCape.value ? 1 : 0) + (hideElytra.value ? 2 : 0);
        if (h != lastHash) {
            lastHash = h;
            cache.clear();
        }
    }

    /** True if the player with this uuid has their skin replaced. */
    public boolean covers(UUID id) {
        if (!enabled() || id == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        UUID self = mc.getSession() == null ? null : mc.getSession().getUuidOrNull();
        boolean isSelf = id.equals(self) || (mc.player != null && id.equals(mc.player.getUuid()));
        return switch (target.get()) {
            case "Self" -> isSelf;
            case "Others" -> !isSelf;
            default -> true;
        };
    }

    public boolean hidesEars(UUID id) {
        return covers(id) && hideEars.on();
    }

    /** Returns the protected skin for the player, or the original when this player is not covered. */
    public SkinTextures apply(UUID id, SkinTextures original) {
        if (original == null || !covers(id)) return original;
        checkCache();
        SkinTextures hit = cache.get(id);
        if (hit != null) return hit;
        SkinTextures base = pick(id);
        // the default textures carry no cape/elytra, but keep the model type of the chosen skin
        SkinTextures out = SkinTextures.create(base.body(), hideCape.on() ? null : original.cape(), hideElytra.on() ? null : original.elytra(), base.model());
        cache.put(id, out);
        return out;
    }

    /** Wraps a skin supplier (menu model, async skin loads) so it is substituted lazily. */
    public Supplier<SkinTextures> wrap(UUID id, Supplier<SkinTextures> original) {
        return () -> apply(id, original.get());
    }

    private SkinTextures pick(UUID id) {
        if (skin.is("Random Per Player")) return DefaultSkinHelper.getSkinTextures(new UUID(id.getLeastSignificantBits() * 31, id.getMostSignificantBits() * 17));
        int idx = 6;
        for (int i = 0; i < DEFAULTS.length; i++) if (DEFAULTS[i].equals(skin.get())) idx = i;
        // Alex (0) and the other slim ones are listed first, Steve is the wide variant of index 6 (wide = +9)
        boolean wide = skin.is("Steve") || skin.is("Ari") || skin.is("Efe") || skin.is("Kai") || skin.is("Makena") || skin.is("Noor") || skin.is("Sunny") || skin.is("Zuri");
        return byIndex(wide ? idx + 9 : idx);
    }

    private static SkinTextures byIndex(int index) {
        // DefaultSkinHelper only exposes the table through UUID hashing, so find a uuid that lands on the wanted slot
        UUID u = new UUID(0L, index);
        for (long probe = 0; probe < 4096; probe++) {
            UUID c = new UUID(0L, probe);
            if (Math.floorMod(c.hashCode(), 18) == index) return DefaultSkinHelper.getSkinTextures(c);
        }
        return DefaultSkinHelper.getSkinTextures(u);
    }

    /** Model type of a default skin, used by the UI. */
    public static PlayerSkinType modelOf(SkinTextures t) {
        return t.model();
    }
}

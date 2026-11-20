package tenacity.modules;

/** Lets the developer screenshot harness fake a hit. */
public final class DevHooks {
    private DevHooks() {
    }

    public static void hit(net.minecraft.entity.Entity e) {
        VisualModules.lastTarget = e;
        VisualModules.lastHit = System.currentTimeMillis() + 60_000;
    }

    public static void kill(net.minecraft.entity.Entity e) {
        if (RenderModules.KillEffects.I != null) RenderModules.KillEffects.I.kill(e);
    }

    /** Dev only: keeps a (dummy) player in the elytra gliding pose, fully rotated. */
    public static void glide(net.minecraft.entity.player.PlayerEntity p) {
        p.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, new net.minecraft.item.ItemStack(net.minecraft.item.Items.ELYTRA));
        p.startGliding();
        p.setPose(net.minecraft.entity.EntityPose.GLIDING);
        try {
            var f = net.minecraft.entity.LivingEntity.class.getDeclaredField("glidingTicks");
            f.setAccessible(true);
            if (f.getInt(p) < 20) f.setInt(p, 20);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    public static void setMode(tenacity.core.Module m, String setting, String value) {
        for (var st : m.settings) if (st.name.equals(setting) && st instanceof tenacity.core.Setting.Mode md)
            for (int i = 0; i < md.modes.length; i++) if (md.modes[i].equals(value)) md.index = i;
    }
}

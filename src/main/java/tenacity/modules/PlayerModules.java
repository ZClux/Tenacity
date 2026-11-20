package tenacity.modules;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;

import java.util.List;

/** Player tab. */
final class PlayerModules {
    private PlayerModules() {
    }

    static List<Module> create() {
        return List.of(new AutoRespawn(), new AutoEat(), new AutoTool());
    }

    static final class AutoRespawn extends Module {
        AutoRespawn() {
            super("Auto Respawn", "Respawns immediately after dying", Category.PLAYER);
        }

        @Override
        public void tick() {
            if (mc.currentScreen instanceof DeathScreen) mc.player.requestRespawn();
        }
    }

    /** Eats food from the hotbar when hungry. */
    static final class AutoEat extends Module {
        private final Setting.Num hunger = num("Eat Below", 14, 1, 19, 1);
        private int previous = -1;
        private boolean eating;

        AutoEat() {
            super("Auto Eat", "Eats food from your hotbar when hungry", Category.PLAYER);
        }

        @Override
        public void tick() {
            if (mc.currentScreen != null) {
                stop();
                return;
            }
            int food = mc.player.getHungerManager().getFoodLevel();
            if (food > hunger.i() || (eating && food >= 20)) {
                stop();
                return;
            }
            if (!eating) {
                int slot = findFood();
                if (slot < 0) return;
                previous = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(slot);
                eating = true;
            }
            mc.options.useKey.setPressed(true);
        }

        private int findFood() {
            for (int i = 0; i < 9; i++) {
                ItemStack s = mc.player.getInventory().getStack(i);
                if (!s.isEmpty() && s.contains(DataComponentTypes.FOOD)) return i;
            }
            return -1;
        }

        private void stop() {
            if (!eating) return;
            eating = false;
            mc.options.useKey.setPressed(false);
            if (previous >= 0 && mc.player != null) mc.player.getInventory().setSelectedSlot(previous);
            previous = -1;
        }

        @Override
        protected void onDisable() {
            stop();
        }
    }

    /** Picks the fastest hotbar tool for the block you are breaking. */
    static AutoTool autoToolInstance;

    /** Called right before a block starts / continues breaking: swap to the hotbar slot that mines it fastest. */
    static void autoTool(BlockPos pos) {
        AutoTool t = autoToolInstance;
        if (t == null || !t.enabled() || mc().player == null || mc().world == null || mc().player.isCreative()) return;
        t.select(pos);
    }

    private static net.minecraft.client.MinecraftClient mc() {
        return net.minecraft.client.MinecraftClient.getInstance();
    }

    static final class AutoTool extends Module {
        private int previous = -1;

        /** Tries every hotbar slot with the game's own breaking-speed maths (enchantments, haste, fatigue included). */
        void select(BlockPos pos) {
            var inv = mc.player.getInventory();
            BlockState state = mc.world.getBlockState(pos);
            if (state.isAir()) return;
            int orig = inv.getSelectedSlot(), best = orig;
            float bestSpeed = -1f;
            for (int i = 0; i < 9; i++) {
                inv.setSelectedSlot(i);
                float sp = mc.player.getBlockBreakingSpeed(state);
                // prefer the current slot on ties and prefer real tools over an empty hand
                if (sp > bestSpeed + 1e-3f || (i == orig && sp >= bestSpeed - 1e-3f)) {
                    bestSpeed = sp;
                    best = i;
                }
            }
            inv.setSelectedSlot(best);
            if (best != orig && previous < 0) previous = orig;
        }

        AutoTool() {
            super("Auto Tool", "Selects the best tool while mining", Category.PLAYER);
            autoToolInstance = this;
        }

        @Override
        public void tick() {
            boolean mining = mc.currentScreen == null && mc.options.attackKey.isPressed()
                    && mc.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
            if (!mining) {
                if (previous >= 0) {
                    mc.player.getInventory().setSelectedSlot(previous);
                    previous = -1;
                }
                return;
            }
            // the swap itself happens in AutoToolMixin right before the block breaks; this tick only puts the old slot back
        }
    }
}

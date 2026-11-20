package tenacity.modules;

import net.minecraft.entity.attribute.EntityAttributes;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;

import java.util.List;

/** Movement tab. */
final class MovementModules {
    private MovementModules() {
    }

    static net.minecraft.util.PlayerInput snapTap(net.minecraft.util.PlayerInput in) {
        return SnapTap.apply(in);
    }

    static List<Module> create() {
        return List.of(new Sprint(), new SnapTap(), new Step(), new AutoJump(), new AutoWalk(), new CryptoFreeCam(), new FreeLook());
    }

    static final class Sprint extends Module {
        private final Setting.Bool omni = bool("Omni Directional", false);

        Sprint() {
            super("Sprint", "Always sprint while moving", Category.MOVEMENT);
        }

        private boolean moving() {
            return mc.options.forwardKey.isPressed() || (omni.on() && (mc.options.backKey.isPressed() || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed()));
        }

        @Override
        public void preTick() {
            if (mc.player == null) return;
            // held before the game reads input, so vanilla sprint logic starts the sprint itself
            if (moving() && !TriggerBot.blocksSprint() && !mc.player.isSneaking()) mc.options.sprintKey.setPressed(true);
        }

        @Override
        public void tick() {
            if (mc.player != null && moving() && !TriggerBot.blocksSprint() && !mc.player.isSneaking() && mc.player.getHungerManager().getFoodLevel() > 6)
                mc.player.setSprinting(true);
        }

        @Override
        public void onDisable() {
            mc.options.sprintKey.setPressed(false);
        }
    }

    /** SOCD cleaning: when opposite keys are held together, the one pressed last wins instead of cancelling out. */
    static final class SnapTap extends Module {
        static SnapTap instance;
        private boolean pl, pr, pf, pb;
        private int lastH, lastV; // -1 / +1 = the key last pressed on that axis

        SnapTap() {
            super("Snap Tap", "Last pressed key wins when you hold opposite movement keys", Category.MOVEMENT);
            instance = this;
        }

        @Override
        public void onDisable() {
            pl = pr = pf = pb = false;
            lastH = lastV = 0;
        }

        static net.minecraft.util.PlayerInput apply(net.minecraft.util.PlayerInput in) {
            SnapTap s = instance;
            if (s == null || !s.enabled()) return in;
            boolean l = in.left(), r = in.right(), f = in.forward(), b = in.backward();
            if (l && !s.pl) s.lastH = -1;
            if (r && !s.pr) s.lastH = 1;
            if (f && !s.pf) s.lastV = 1;
            if (b && !s.pb) s.lastV = -1;
            s.pl = l; s.pr = r; s.pf = f; s.pb = b;
            if (l && r) { l = s.lastH == -1; r = s.lastH == 1; }
            if (f && b) { f = s.lastV == 1; b = s.lastV == -1; }
            return new net.minecraft.util.PlayerInput(f, b, l, r, in.jump(), in.sneak(), in.sprint());
        }
    }

    static final class Step extends Module {
        private final Setting.Num height = num("Height", 1.0, 0.6, 2.0, 0.1);

        Step() {
            super("Step", "Walk up blocks without jumping", Category.MOVEMENT);
        }

        @Override
        public String suffix() {
            return height.display();
        }

        @Override
        public void tick() {
            var attr = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
            if (attr != null && Math.abs(attr.getBaseValue() - height.value) > 1e-6) attr.setBaseValue(height.value);
        }

        @Override
        protected void onDisable() {
            if (mc.player == null) return;
            var attr = mc.player.getAttributeInstance(EntityAttributes.STEP_HEIGHT);
            if (attr != null) attr.setBaseValue(0.6);
        }
    }

    static final class AutoJump extends Module {
        AutoJump() {
            super("Auto Jump", "Jumps for you while you walk", Category.MOVEMENT);
        }

        private boolean pressing;

        @Override
        public void tick() {
            boolean go = mc.player.isOnGround() && mc.options.forwardKey.isPressed() && mc.currentScreen == null;
            if (go) {
                mc.options.jumpKey.setPressed(true);
                pressing = true;
            } else if (pressing) {
                mc.options.jumpKey.setPressed(false);
                pressing = false;
            }
        }

        @Override
        protected void onDisable() {
            mc.options.jumpKey.setPressed(false);
        }
    }

    static final class AutoWalk extends Module {
        AutoWalk() {
            super("Auto Walk", "Holds the forward key for you", Category.MOVEMENT);
        }

        @Override
        public void tick() {
            if (mc.currentScreen == null) mc.options.forwardKey.setPressed(true);
        }

        @Override
        protected void onDisable() {
            mc.options.forwardKey.setPressed(false);
        }
    }

    static final class FreeLook extends Module {
        private final Setting.Num distance = num("Distance", 4, 1, 30, 0.5);
        private final Setting.Bool smooth = bool("Smooth Zoom", true);
        private final Setting.Num smoothness = num("Smoothness", 60, 0, 100, 5).showIf(() -> smooth.on());
        private final Setting.Bool collide = bool("Camera Collision", false);

        FreeLook() {
            super("Freelook", "Look around in third person without turning your character", Category.RENDER);
        }

        @Override
        public void tick() {
            if (!tenacity.gfx.FreecamFx.freelook) tenacity.gfx.FreecamFx.start(true);
            tenacity.gfx.FreecamFx.closing = false;
            tenacity.gfx.FreecamFx.camDistance = distance.f();
            tenacity.gfx.FreecamFx.collide = collide.on();
            tenacity.gfx.FreecamFx.smoothness = smooth.on() ? smoothness.f() : -500f;
        }

        @Override
        protected void onEnable() {
            tenacity.gfx.FreecamFx.camDistance = distance.f();
            tenacity.gfx.FreecamFx.collide = collide.on();
            tenacity.gfx.FreecamFx.start(true);
        }

        @Override
        protected void onDisable() {
            // glide back into the head, then leave third person (instant when Smooth Zoom is off)
            if (smooth.on() && tenacity.gfx.FreecamFx.freelook) tenacity.gfx.FreecamFx.closing = true;
            else tenacity.gfx.FreecamFx.stop();
        }
    }
}

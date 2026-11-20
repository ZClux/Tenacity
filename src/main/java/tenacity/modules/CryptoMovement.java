package tenacity.modules;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.AbstractCommandBlockScreen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import net.minecraft.client.gui.screen.ingame.StructureBlockScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.mixin.JumpCooldownAccessor;

import java.util.List;

/** Extra movement modules: Speed, Fly, Jesus, Inventory Move, No Slow, No Jump Delay, Water Speed, Jump Reset, Move Fix, Elytra Motion. */
public final class CryptoMovement {
    private CryptoMovement() {
    }

    private static InventoryMove inventoryMove;
    private static NoSlow noSlow;
    private static MoveFix moveFix;

    public static List<Module> create() {
        inventoryMove = new InventoryMove();
        noSlow = new NoSlow();
        moveFix = new MoveFix();
        return List.of(new Speed(), new Fly(), new Jesus(), inventoryMove, noSlow, new NoJumpDelay(), new WaterSpeed(), new JumpReset(), moveFix, new ElytraMotion());
    }

    /* ---------------------------------------------------------------- hooks used by the mixins */

    /** True when the item-use slowdown should be left out this tick. */
    public static boolean skipUseSlowdown(ClientPlayerEntity player) {
        NoSlow n = noSlow;
        if (n == null || !n.enabled() || !player.isUsingItem() || player.hasVehicle()) return false;
        return n.skip(player);
    }

    /** Called with every freshly built player input. */
    public static PlayerInput adjustInput(PlayerInput in) {
        if (mcPlayer() == null) return in;
        InventoryMove im = inventoryMove;
        if (im != null && im.enabled() && im.canMoveInScreen()) in = im.readKeys(in);
        MoveFix mf = moveFix;
        if (mf != null && mf.enabled()) in = mf.correct(in);
        return MovementModules.snapTap(in);
    }

    private static ClientPlayerEntity mcPlayer() {
        return net.minecraft.client.MinecraftClient.getInstance().player;
    }

    private static boolean moving(ClientPlayerEntity p) {
        return p.input != null && (p.input.getMovementInput().lengthSquared() > 0);
    }

    /** Horizontal velocity along the movement keys, relative to the player yaw. */
    private static void setHorizontal(ClientPlayerEntity p, double speed) {
        float f = p.input.getMovementInput().y;
        float s = p.input.getMovementInput().x;
        if (f == 0 && s == 0) {
            p.setVelocity(0, p.getVelocity().y, 0);
            return;
        }
        float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        double len = Math.sqrt(f * f + s * s);
        f /= (float) len;
        s /= (float) len;
        double x = -MathHelper.sin(yaw) * f + MathHelper.cos(yaw) * -s;
        double z = MathHelper.cos(yaw) * f + MathHelper.sin(yaw) * -s;
        p.setVelocity(x * speed, p.getVelocity().y, z * speed);
    }

    /* ---------------------------------------------------------------- modules */

    static final class Speed extends Module {
        private final Setting.Mode mode = mode("Mode", "Strafe", "Strafe", "Vanilla", "Collision");
        private final Setting.Num speed = num("Speed", 1.5, 1.0, 5.0, 0.1).showIf(() -> mode.is("Vanilla"));
        private final Setting.Bool onlyGround = bool("Only On Ground", false).showIf(() -> mode.is("Vanilla"));
        private final Setting.Bool autoJump = bool("Auto Jump", false).showIf(() -> mode.is("Strafe"));

        Speed() {
            super("Speed", "Moves faster on the ground and in the air", Category.MOVEMENT);
        }

        @Override
        public String suffix() {
            return mode.get();
        }

        @Override
        public void tick() {
            ClientPlayerEntity p = mc.player;
            if (p.isGliding() || p.isSneaking() || p.isTouchingWater() || p.isInLava() || p.getAbilities().flying || p.hasVehicle()) return;
            switch (mode.get()) {
                case "Vanilla" -> {
                    if (!moving(p) || (onlyGround.on() && !p.isOnGround())) return;
                    setHorizontal(p, speed.value / 3.0);
                }
                case "Strafe" -> {
                    if (!moving(p)) return;
                    if (autoJump.on() && p.isOnGround()) p.jump();
                    double base = 0.2873 * (1.0 + 0.2 * (p.hasStatusEffect(StatusEffects.SPEED) ? p.getStatusEffect(StatusEffects.SPEED).getAmplifier() + 1 : 0));
                    double current = Math.hypot(p.getVelocity().x, p.getVelocity().z);
                    setHorizontal(p, Math.max(base * (p.isOnGround() ? 1.0 : 1.04), current * (p.isOnGround() ? 1.0 : 0.99)));
                }
                case "Collision" -> {
                    if (!moving(p)) return;
                    int hits = 0;
                    for (var e : mc.world.getOtherEntities(p, p.getBoundingBox().expand(0.5))) {
                        if (e instanceof net.minecraft.entity.LivingEntity || e instanceof net.minecraft.entity.vehicle.BoatEntity) {
                            if (!(e instanceof net.minecraft.entity.decoration.ArmorStandEntity)) hits++;
                        }
                    }
                    if (hits == 0) return;
                    float yaw = p.getYaw() * MathHelper.RADIANS_PER_DEGREE;
                    double push = 0.07 * hits;
                    p.addVelocity(-MathHelper.sin(yaw) * push, 0, MathHelper.cos(yaw) * push);
                }
                default -> {
                }
            }
        }
    }

    static final class Fly extends Module {
        private final Setting.Mode mode = mode("Mode", "Normal", "Normal", "Creative");
        private final Setting.Num horizontal = num("Horizontal Speed", 1.5, 0.5, 10.0, 0.1);
        private final Setting.Num vertical = num("Vertical Speed", 1.5, 0.0, 10.0, 0.1);
        private boolean hadFlying, couldFly;

        Fly() {
            super("Fly", "Lets you fly freely", Category.MOVEMENT);
        }

        @Override
        public String suffix() {
            return mode.get();
        }

        @Override
        protected void onEnable() {
            if (mc.player == null) return;
            hadFlying = mc.player.getAbilities().flying;
            couldFly = mc.player.getAbilities().allowFlying;
        }

        @Override
        public void tick() {
            ClientPlayerEntity p = mc.player;
            if (mode.is("Creative")) {
                p.getAbilities().allowFlying = true;
                p.getAbilities().flying = true;
                p.getAbilities().setFlySpeed(horizontal.f() / 30f);
                return;
            }
            if (p.isGliding()) return;
            double y = 0;
            if (mc.options.sneakKey.isPressed()) y = -vertical.value / 3.0;
            else if (mc.options.jumpKey.isPressed()) y = vertical.value / 3.0;
            setHorizontal(p, horizontal.value / 3.0);
            p.setVelocity(p.getVelocity().x, y, p.getVelocity().z);
            p.fallDistance = 0;
        }

        @Override
        protected void onDisable() {
            if (mc.player == null) return;
            var a = mc.player.getAbilities();
            if (mode.is("Creative")) {
                a.setFlySpeed(0.05f);
                if (!mc.player.isCreative() && !mc.player.isSpectator()) {
                    a.flying = false;
                    a.allowFlying = false;
                } else {
                    a.flying = hadFlying;
                    a.allowFlying = couldFly || mc.player.isCreative() || mc.player.isSpectator();
                }
            }
        }
    }

    static final class Jesus extends Module {
        private final Setting.Mode mode = mode("Mode", "Solid", "Solid", "Dolphin");
        private final Setting.Num speed = num("Speed", 0.3, 0.05, 0.6, 0.01).showIf(() -> mode.is("Dolphin"));
        private final Setting.Bool lava = bool("Lava", true);
        private int tickCounter;

        Jesus() {
            super("Jesus", "Walk on top of water", Category.MOVEMENT);
        }

        @Override
        public String suffix() {
            return mode.get();
        }

        private boolean fluid(ClientPlayerEntity p) {
            return p.isTouchingWater() || (lava.on() && p.isInLava());
        }

        @Override
        public void tick() {
            ClientPlayerEntity p = mc.player;
            if (p.isSneaking() || p.hasVehicle()) return;
            if (mode.is("Solid")) {
                if (fluid(p)) {
                    Vec3d v = p.getVelocity();
                    p.setVelocity(v.x, mc.options.jumpKey.isPressed() ? 0.14 : 0.06, v.z);
                    p.fallDistance = 0;
                }
            } else if (fluid(p) || p.isInFluid()) {
                tickCounter = (tickCounter + 1) % 3;
                if (moving(p)) {
                    setHorizontal(p, speed.value);
                    double y = tickCounter == 0 ? 0.05 : tickCounter == 2 ? -0.05 : 0;
                    if (mc.options.jumpKey.isPressed()) y = 0.2;
                    p.setVelocity(p.getVelocity().x, y, p.getVelocity().z);
                } else {
                    p.setVelocity(0, mc.options.jumpKey.isPressed() ? 0.2 : 0, 0);
                }
            } else {
                tickCounter = 0;
            }
        }
    }

    static final class InventoryMove extends Module {
        private final Setting.Bool jump = bool("Jump", true);
        private final Setting.Bool sneak = bool("Sneak", true);
        private final Setting.Bool sprint = bool("Sprint", true);

        InventoryMove() {
            super("Inventory Move", "Keep walking while an inventory screen is open", Category.MOVEMENT);
        }

        boolean canMoveInScreen() {
            var s = mc.currentScreen;
            return s != null && !(s instanceof ChatScreen) && !(s instanceof SignEditScreen) && !(s instanceof AnvilScreen)
                    && !(s instanceof AbstractCommandBlockScreen) && !(s instanceof StructureBlockScreen)
                    && !s.getClass().getName().startsWith("tenacity.");
        }

        private boolean down(net.minecraft.client.option.KeyBinding k) {
            return InputUtil.isKeyPressed(mc.getWindow(), InputUtil.fromTranslationKey(k.getBoundKeyTranslationKey()).getCode());
        }

        PlayerInput readKeys(PlayerInput in) {
            boolean f = down(mc.options.forwardKey), b = down(mc.options.backKey), l = down(mc.options.leftKey), r = down(mc.options.rightKey);
            boolean j = jump.on() && down(mc.options.jumpKey);
            boolean sn = sneak.on() && down(mc.options.sneakKey);
            boolean sp = sprint.on() && down(mc.options.sprintKey);
            return new PlayerInput(f, b, l, r, j, sn, sp || in.sprint());
        }
    }

    static final class NoSlow extends Module {
        private final Setting.Mode mode = mode("Mode", "Vanilla", "Vanilla", "Ticks");
        private final Setting.Num delay = num("Delay Ticks", 2, 0, 10, 1).showIf(() -> mode.is("Ticks"));
        private int useTicks;

        NoSlow() {
            super("No Slow", "No slowdown while using items", Category.MOVEMENT);
        }

        @Override
        public String suffix() {
            return mode.get();
        }

        @Override
        public void tick() {
            if (mc.player.isUsingItem()) useTicks++;
            else useTicks = 0;
        }

        boolean skip(ClientPlayerEntity p) {
            if (mode.is("Vanilla")) return true;
            if (useTicks >= delay.i()) {
                useTicks = 0;
                return true;
            }
            return false;
        }
    }

    static final class NoJumpDelay extends Module {
        NoJumpDelay() {
            super("No Jump Delay", "Removes the delay between jumps", Category.MOVEMENT);
        }

        @Override
        public void tick() {
            ((JumpCooldownAccessor) mc.player).tenacity$setJumpingCooldown(0);
        }
    }

    static final class WaterSpeed extends Module {
        private final Setting.Num boost = num("Boost", 1.3, 1.0, 2.0, 0.05);
        private final Setting.Bool jumpOut = bool("Hop Out", false);
        private final Setting.Bool ice = bool("Ice Boost", true);
        private final Setting.Num iceSpeed = num("Ice Speed", 1.5, 1.0, 3.0, 0.1).showIf(() -> ice.on());

        WaterSpeed() {
            super("Water Speed", "Swim faster", Category.MOVEMENT);
        }

        @Override
        public void tick() {
            ClientPlayerEntity p = mc.player;
            if (!p.isTouchingWater() && !p.isSwimming()) return;
            if (jumpOut.on() && p.isSwimming() && p.isOnGround()) {
                p.jump();
                p.setVelocity(p.getVelocity().x, 0.1, p.getVelocity().z);
            }
            if (!p.isSwimming() && !p.isTouchingWater()) return;
            Vec3d v = p.getVelocity();
            double m = boost.value;
            if (ice.on() && headUnderIce()) m *= iceSpeed.value;
            double h = Math.hypot(v.x, v.z);
            double f = 1.0 + (m - 1.0) * 0.25;
            if (h > 0.01 && h < 0.6) p.setVelocity(v.x * f, v.y, v.z * f);
            if (mc.options.jumpKey.isPressed() && p.isSwimming()) {
                float pitch = p.getPitch();
                float up = pitch >= 0 ? MathHelper.clamp(pitch / 45f, 1f, 2f) : 0.5f;
                p.setVelocity(p.getVelocity().x, Math.max(p.getVelocity().y, 0.1 * up), p.getVelocity().z);
            }
        }

        private boolean headUnderIce() {
            BlockPos head = mc.player.getBlockPos().up(1);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 1; dy++) {
                        Block b = mc.world.getBlockState(head.add(dx, dy, dz)).getBlock();
                        if (b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE || b == Blocks.FROSTED_ICE) return true;
                    }
                }
            }
            return false;
        }
    }

    static final class JumpReset extends Module {
        private final Setting.Num chance = num("Chance", 70, 0, 100, 1);
        private final Setting.Bool liquid = bool("Liquid Check", true);
        private final Setting.Bool web = bool("Web Check", true);
        private int lastHurt;

        JumpReset() {
            super("Jump Reset", "Jumps the moment you take knockback", Category.MOVEMENT);
        }

        @Override
        public void tick() {
            ClientPlayerEntity p = mc.player;
            int hurt = p.hurtTime;
            boolean fresh = hurt > 0 && hurt > lastHurt - 1 && lastHurt == 0;
            lastHurt = hurt;
            if (!fresh || !p.isOnGround()) return;
            if (Math.random() * 100.0 > chance.value) return;
            if (liquid.on() && (p.isTouchingWater() || p.isInLava())) return;
            if (web.on() && mc.world.getBlockState(p.getBlockPos()).isOf(Blocks.COBWEB)) return;
            p.jump();
        }

        @Override
        protected void onDisable() {
            lastHurt = 0;
        }
    }

    static final class MoveFix extends Module {
        private final Setting.Mode mode = mode("Mode", "Snap", "Snap", "Diagonal");
        private final Setting.Num step = num("Angle Step", 45, 15, 90, 15);

        MoveFix() {
            super("Move Fix", "Snaps your walking direction to clean angles", Category.MOVEMENT);
        }

        @Override
        public String suffix() {
            return mode.get();
        }

        private static float mult(boolean pos, boolean neg) {
            if (pos == neg) return 0f;
            return pos ? 1f : -1f;
        }

        /** Remaps the pressed keys so the walk direction is the closest key combination to the camera heading, snapped to the angle step. */
        PlayerInput correct(PlayerInput in) {
            float fw = mult(in.forward(), in.backward());
            float sd = mult(in.left(), in.right());
            if (fw == 0 && sd == 0) return in;
            if (mode.is("Diagonal") && (fw == 0 || sd == 0)) return in;

            double intended = Math.toDegrees(Math.atan2(-sd, fw));
            double snapped = Math.round(intended / step.value) * step.value;
            float bestF = fw, bestS = sd;
            double best = Double.MAX_VALUE;
            for (int f = -1; f <= 1; f++) {
                for (int s = -1; s <= 1; s++) {
                    if (f == 0 && s == 0) continue;
                    double ang = Math.toDegrees(Math.atan2(-s, f));
                    double diff = Math.abs(MathHelper.wrapDegrees((float) (snapped - ang)));
                    if (diff < best) {
                        best = diff;
                        bestF = f;
                        bestS = s;
                    }
                }
            }
            return new PlayerInput(bestF > 0, bestF < 0, bestS > 0, bestS < 0, in.jump(), in.sneak(), in.sprint());
        }
    }

    static final class ElytraMotion extends Module {
        private final Setting.Bool hover = bool("Hover Near Players", true);
        private final Setting.Num range = num("Range", 6, 2, 16, 0.5).showIf(() -> hover.on());
        private final Setting.Bool boost = bool("Control Speed", false);
        private final Setting.Num speed = num("Speed", 1.2, 0.5, 3.0, 0.1).showIf(() -> boost.on());

        ElytraMotion() {
            super("Elytra Motion", "Extra control while gliding", Category.MOVEMENT);
        }

        @Override
        public void tick() {
            ClientPlayerEntity p = mc.player;
            if (!p.isGliding()) return;
            if (hover.on()) {
                for (var other : mc.world.getPlayers()) {
                    if (other == p || other.isSpectator()) continue;
                    if (p.distanceTo(other) < range.value) {
                        p.setVelocity(0, 0.02, 0);
                        return;
                    }
                }
            }
            if (boost.on() && mc.options.forwardKey.isPressed()) {
                Vec3d look = p.getRotationVector();
                p.setVelocity(look.multiply(speed.value));
            }
        }
    }
}

package tenacity.modules;

import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.RaycastContext;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Advanced combat modules: crystals, totems and respawn anchors. */
public final class CryptoCombat {
    private CryptoCombat() {
    }

    public static List<Module> create() {
        return List.of(new AutoCrystal(), new AutoTotem(), new AnchorMacro());
    }

    /* ------------------------------------------------------------------ shared helpers */

    private static final net.minecraft.client.MinecraftClient MC = net.minecraft.client.MinecraftClient.getInstance();

    /** Sends a rotation to the server only, the client camera is not touched. */
    static void silentLook(float yaw, float pitch) {
        if (MC.getNetworkHandler() == null || MC.player == null) return;
        MC.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, MathHelper.clamp(pitch, -90f, 90f),
                MC.player.isOnGround(), MC.player.horizontalCollision));
    }

    static float[] angles(Vec3d from, Vec3d to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new float[]{yaw, pitch};
    }

    static Vec3d lookVec(float yaw, float pitch) {
        float p = pitch * ((float) Math.PI / 180f), y = -yaw * ((float) Math.PI / 180f);
        float cy = MathHelper.cos(y), sy = MathHelper.sin(y), cp = MathHelper.cos(p), sp = MathHelper.sin(p);
        return new Vec3d(sy * cp, -sp, cy * cp);
    }

    /** Ray from the eye along a rotation, against blocks only. */
    static BlockHitResult rayFromRotation(float yaw, float pitch) {
        Vec3d eye = MC.player.getEyePos();
        Vec3d end = eye.add(lookVec(yaw, pitch).multiply(MC.player.getBlockInteractionRange()));
        return MC.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, MC.player));
    }

    static int findHotbar(Item item) {
        for (int i = 0; i < 9; i++) {
            var s = MC.player.getInventory().getStack(i);
            if (!s.isEmpty() && s.getItem() == item) return i;
        }
        return -1;
    }

    static boolean keyDown(int code) {
        if (code == -1) return false;
        try {
            long h = MC.getWindow().getHandle();
            if (code <= -100) return org.lwjgl.glfw.GLFW.glfwGetMouseButton(h, -100 - code) == 1;
            return InputUtil.isKeyPressed(MC.getWindow(), code);
        } catch (Throwable t) {
            return false;
        }
    }

    static Vec3d closest(Vec3d from, Box box) {
        return new Vec3d(MathHelper.clamp(from.x, box.minX, box.maxX),
                MathHelper.clamp(from.y, box.minY, box.maxY),
                MathHelper.clamp(from.z, box.minZ, box.maxZ));
    }

    /** Fraction of the victim box visible from the explosion centre (vanilla style sampling). */
    static float seenPercent(Vec3d center, LivingEntity victim) {
        Box box = victim.getBoundingBox();
        double sx = 1.0 / ((box.maxX - box.minX) * 2.0 + 1.0);
        double sy = 1.0 / ((box.maxY - box.minY) * 2.0 + 1.0);
        double sz = 1.0 / ((box.maxZ - box.minZ) * 2.0 + 1.0);
        if (sx < 0 || sy < 0 || sz < 0) return 0f;
        double ox = (1.0 - Math.floor(1.0 / sx) * sx) / 2.0;
        double oz = (1.0 - Math.floor(1.0 / sz) * sz) / 2.0;
        int hit = 0, total = 0;
        for (double x = 0; x <= 1.0; x += sx) {
            for (double y = 0; y <= 1.0; y += sy) {
                for (double z = 0; z <= 1.0; z += sz) {
                    Vec3d p = new Vec3d(MathHelper.lerp(x, box.minX, box.maxX) + ox,
                            MathHelper.lerp(y, box.minY, box.maxY),
                            MathHelper.lerp(z, box.minZ, box.maxZ) + oz);
                    BlockHitResult r = MC.world.raycast(new RaycastContext(p, center, RaycastContext.ShapeType.COLLIDER,
                            RaycastContext.FluidHandling.NONE, victim));
                    if (r.getType() == HitResult.Type.MISS) hit++;
                    total++;
                }
            }
        }
        return total == 0 ? 0f : (float) hit / total;
    }

    /** Vanilla explosion damage (power 6 crystal) at {@code center}: exposure, difficulty, armor. */
    static float crystalDamage(LivingEntity victim, Vec3d center) {
        double dist = Math.sqrt(victim.squaredDistanceTo(center)) / 12.0;
        if (dist > 1.0) return 0f;
        double e = (1.0 - dist) * seenPercent(center, victim);
        float raw = (float) ((e * e + e) / 2.0 * 7.0 * 12.0 + 1.0);
        if (victim instanceof PlayerEntity) {
            Difficulty d = MC.world.getDifficulty();
            if (d == Difficulty.PEACEFUL) return 0f;
            if (d == Difficulty.EASY) raw = Math.min(raw / 2.0f + 1.0f, raw);
            else if (d == Difficulty.HARD) raw = raw * 3.0f / 2.0f;
        }
        float armor = (float) victim.getArmor();
        float toughness = (float) victim.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        float effective = MathHelper.clamp(armor - raw / (2.0f + toughness / 4.0f), armor * 0.2f, 20.0f);
        return raw * (1.0f - effective / 25.0f);
    }

    /* ------------------------------------------------------------------ Auto Crystal */

    /** Places and detonates end crystals where they hurt enemies most and us least. */
    static final class AutoCrystal extends Module {
        private static final double TARGET_RANGE = 12.0;
        private static final double BREAK_RANGE = 4.5;
        private static final double[][] FACE_SAMPLES = {
                {0.0, 0.0}, {0.25, 0.25}, {-0.25, 0.25}, {0.25, -0.25}, {-0.25, -0.25},
                {0.4, 0.0}, {-0.4, 0.0}, {0.0, 0.4}, {0.0, -0.4}
        };

        private final Setting.Mode activation = mode("Activation", "Always", "Always", "Key");
        private final Setting.Key activationKey = add(new Setting.Key("Activation Key", -1)).showIf(() -> activation.is("Key"));
        private final Setting.Num placeDelay = num("Place Delay", 1, 0, 20, 1);
        private final Setting.Num breakDelay = num("Break Delay", 1, 0, 20, 1);
        private final Setting.Num minDamage = num("Min Damage", 4, 1, 20, 0.5);
        private final Setting.Num maxSelf = num("Max Self Damage", 8, 1, 20, 0.5);
        private final Setting.Num failChance = num("Fail Chance", 0, 0, 100, 1);
        private final Setting.Bool placeBases = bool("Place Obsidian", false);
        private final Setting.Bool stopOnKill = bool("Stop On Kill", false);

        private int placeCd, breakCd;
        private int previousSlot = -1;
        private int restoreTicks;
        private long tickNo;
        private long lastPlaceTick = -10, lastBreakTick = -10, swapTick = -1;
        private BlockPos lastBase;

        AutoCrystal() {
            super("Auto Crystal", "Places and detonates end crystals around enemies", Category.COMBAT);
        }

        @Override
        protected void onDisable() {
            if (previousSlot >= 0 && previousSlot <= 8 && mc.player != null) mc.player.getInventory().setSelectedSlot(previousSlot);
            previousSlot = -1;
        }

        @Override
        protected void onEnable() {
            placeCd = breakCd = 0;
            previousSlot = -1;
        }

        @Override
        public void tick() {
            if (mc.interactionManager == null) return;
            tickNo++;
            if (placeCd > 0) placeCd--;
            if (breakCd > 0) breakCd--;
            if (restoreSlot()) return;
            if (mc.currentScreen != null || mc.player.isUsingItem()) return;
            if (activation.is("Key") && !keyDown(activationKey.code)) return;
            if (stopOnKill.on() && deadBodyNearby()) return;

            EndCrystalEntity crystal = chooseBreak();
            if (crystal != null && breakCd <= 0 && tickNo > lastPlaceTick + 1) {
                if (fail()) return;
                if (breakCrystal(crystal)) {
                    breakCd = (int) placeDelayOf(breakDelay);
                    return;
                }
            }
            if (placeCd > 0 || tickNo <= lastBreakTick + 1 || fail()) return;
            if (placeBest()) placeCd = (int) placeDelayOf(placeDelay);
        }

        private double placeDelayOf(Setting.Num n) {
            return n.value <= 0 ? 0 : Math.max(0, Math.round(n.value + ThreadLocalRandom.current().nextInt(0, 2)));
        }

        private boolean fail() {
            return Math.random() * 100.0 < failChance.value;
        }

        private boolean restoreSlot() {
            if (previousSlot < 0) return false;
            if (previousSlot > 8 || mc.player.getInventory().getSelectedSlot() == previousSlot) {
                previousSlot = -1;
                return false;
            }
            if (tickNo > swapTick) {
                mc.player.getInventory().setSelectedSlot(previousSlot);
                previousSlot = -1;
                restoreTicks = 0;
                return true;
            }
            if (++restoreTicks > 12) previousSlot = -1;
            return true;
        }

        private boolean hold(int slot) {
            int cur = mc.player.getInventory().getSelectedSlot();
            if (cur == slot) return true;
            if (previousSlot < 0) previousSlot = cur;
            swapTick = tickNo;
            mc.player.getInventory().setSelectedSlot(slot);
            return true;
        }

        private boolean selfSafe(float dmg) {
            if (dmg > maxSelf.value) return false;
            return mc.player.getHealth() + mc.player.getAbsorptionAmount() - dmg > 3.0f;
        }

        private boolean validTarget(Entity e) {
            return e instanceof PlayerEntity p && p != mc.player && p.isAlive() && !p.isSpectator();
        }

        private float bestEnemyDamage(Vec3d c) {
            float best = 0f;
            for (var p : mc.world.getPlayers()) {
                if (!validTarget(p) || p.squaredDistanceTo(c) > TARGET_RANGE * TARGET_RANGE) continue;
                best = Math.max(best, crystalDamage(p, c));
            }
            return best;
        }

        private boolean deadBodyNearby() {
            for (var p : mc.world.getPlayers()) {
                if (p != mc.player && mc.player.distanceTo(p) < 6 && p.isDead()) return true;
            }
            return false;
        }

        private EndCrystalEntity chooseBreak() {
            Vec3d eye = mc.player.getEyePos();
            EndCrystalEntity best = null;
            float bestScore = -Float.MAX_VALUE;
            for (Entity en : mc.world.getOtherEntities(mc.player, mc.player.getBoundingBox().expand(BREAK_RANGE + 1.0))) {
                if (!(en instanceof EndCrystalEntity c) || !c.isAlive() || c.isRemoved() || c.age < 1) continue;
                if (eye.squaredDistanceTo(closest(eye, c.getBoundingBox())) > BREAK_RANGE * BREAK_RANGE) continue;
                Vec3d center = c.getEntityPos();
                float self = crystalDamage(mc.player, center);
                if (!selfSafe(self)) continue;
                float enemy = bestEnemyDamage(center);
                if (enemy < minDamage.value && self <= 1.5f) continue;
                float score = enemy - self * 1.5f;
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
            return best;
        }

        private boolean breakCrystal(EndCrystalEntity crystal) {
            Vec3d eye = mc.player.getEyePos();
            Box box = crystal.getBoundingBox();
            Vec3d aim = closest(eye, box).lerp(box.getCenter(), 0.5);
            if (box.raycast(eye, eye.add(aim.subtract(eye).multiply(2.0))).isEmpty()) {
                aim = box.getCenter();
                if (box.raycast(eye, eye.add(aim.subtract(eye).multiply(2.0))).isEmpty()) return false;
            }
            BlockHitResult wall = mc.world.raycast(new RaycastContext(eye, box.getCenter(), RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, mc.player));
            if (wall.getType() == HitResult.Type.BLOCK
                    && eye.squaredDistanceTo(wall.getPos()) < eye.squaredDistanceTo(box.getCenter()) - 1.0) return false;
            float[] rot = angles(eye, aim);
            silentLook(rot[0], rot[1]);
            mc.interactionManager.attackEntity(mc.player, crystal);
            mc.player.swingHand(Hand.MAIN_HAND);
            lastBreakTick = tickNo;
            return true;
        }

        private boolean placeBest() {
            int slot = findHotbar(Items.END_CRYSTAL);
            boolean off = mc.player.getOffHandStack().isOf(Items.END_CRYSTAL);
            if (slot == -1 && !off) return false;
            Object[] spot = chooseSpot();
            if (spot == null) return placeBases.on() && placeObsidianBase();
            Hand hand = Hand.MAIN_HAND;
            if (off && !mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) hand = Hand.OFF_HAND;
            else if (!hold(slot)) return false;
            return click((BlockHitResult) spot[1], hand, (BlockPos) spot[0]);
        }

        private Object[] chooseSpot() {
            Vec3d eye = mc.player.getEyePos();
            double reach = mc.player.getBlockInteractionRange();
            int r = (int) Math.ceil(reach);
            BlockPos origin = mc.player.getBlockPos();
            Object[] best = null;
            float bestScore = -Float.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        BlockPos base = origin.add(dx, dy, dz);
                        var st = mc.world.getBlockState(base);
                        if (!st.isOf(Blocks.OBSIDIAN) && !st.isOf(Blocks.BEDROCK)) continue;
                        if (!crystalFits(base)) continue;
                        if (base.equals(lastBase) && tickNo <= lastPlaceTick + 2) continue;
                        Vec3d center = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
                        if (eye.squaredDistanceTo(center) > (reach + 1.0) * (reach + 1.0)) continue;
                        float self = crystalDamage(mc.player, center);
                        if (!selfSafe(self)) continue;
                        float enemy = bestEnemyDamage(center);
                        if (enemy < minDamage.value) continue;
                        float score = enemy - self * 1.5f;
                        if (score <= bestScore) continue;
                        BlockHitResult hit = faceHit(eye, base, reach);
                        if (hit == null) continue;
                        bestScore = score;
                        best = new Object[]{base, hit};
                    }
                }
            }
            return best;
        }

        private boolean crystalFits(BlockPos base) {
            BlockPos above = base.up();
            if (!mc.world.isAir(above) || !mc.world.isAir(above.up())) return false;
            Box column = new Box(above.getX(), above.getY(), above.getZ(), above.getX() + 1.0, above.getY() + 2.0, above.getZ() + 1.0);
            return mc.world.getOtherEntities(null, column).stream().noneMatch(e -> !e.isRemoved() && !e.isSpectator());
        }

        private BlockHitResult faceHit(Vec3d eye, BlockPos pos, double reach) {
            BlockHitResult best = null;
            double bestSq = Double.MAX_VALUE;
            for (Direction side : Direction.values()) {
                BlockPos nb = pos.offset(side);
                if (!mc.world.getBlockState(nb).isReplaceable() && !mc.world.isAir(nb)) continue;
                Vec3d fc = Vec3d.ofCenter(pos).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
                for (double[] off : FACE_SAMPLES) {
                    Vec3d point = switch (side.getAxis()) {
                        case X -> fc.add(0.0, off[0], off[1]);
                        case Y -> fc.add(off[0], 0.0, off[1]);
                        case Z -> fc.add(off[0], off[1], 0.0);
                    };
                    double dsq = eye.squaredDistanceTo(point);
                    if (dsq > reach * reach || dsq >= bestSq) continue;
                    Vec3d end = point.add(side.getOffsetX() * 0.01, side.getOffsetY() * 0.01, side.getOffsetZ() * 0.01);
                    BlockHitResult ray = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                            RaycastContext.FluidHandling.NONE, mc.player));
                    if (ray.getType() != HitResult.Type.BLOCK || !ray.getBlockPos().equals(pos) || ray.getSide() != side) continue;
                    best = new BlockHitResult(point, side, pos, false);
                    bestSq = dsq;
                    break;
                }
            }
            return best;
        }

        private boolean click(BlockHitResult hit, Hand hand, BlockPos base) {
            Vec3d eye = mc.player.getEyePos();
            float[] rot = angles(eye, hit.getPos());
            BlockHitResult check = rayFromRotation(rot[0], rot[1]);
            if (check == null || check.getType() != HitResult.Type.BLOCK
                    || !check.getBlockPos().equals(hit.getBlockPos()) || check.getSide() != hit.getSide()) return false;
            silentLook(rot[0], rot[1]);
            ActionResult res = mc.interactionManager.interactBlock(mc.player, hand, new BlockHitResult(check.getPos(), check.getSide(), check.getBlockPos(), false));
            if (res instanceof ActionResult.Success s && s.swingSource() == ActionResult.SwingSource.CLIENT) mc.player.swingHand(hand);
            lastPlaceTick = tickNo;
            lastBase = base;
            return true;
        }

        private boolean placeObsidianBase() {
            int slot = findHotbar(Items.OBSIDIAN);
            if (slot == -1) return false;
            LivingEntity target = null;
            double bd = TARGET_RANGE * TARGET_RANGE;
            for (var p : mc.world.getPlayers()) {
                if (!validTarget(p)) continue;
                double d = mc.player.squaredDistanceTo(p);
                if (d < bd) {
                    bd = d;
                    target = p;
                }
            }
            if (target == null) return false;
            Vec3d eye = mc.player.getEyePos();
            double reach = mc.player.getBlockInteractionRange();
            BlockPos feet = target.getBlockPos();
            BlockPos bestBase = null;
            float bestScore = 0f;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos base = feet.add(dx, -1, dz);
                    if (!mc.world.getBlockState(base).isReplaceable() && !mc.world.isAir(base)) continue;
                    if (!crystalFits(base) || eye.squaredDistanceTo(Vec3d.ofCenter(base)) > reach * reach) continue;
                    Vec3d center = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
                    float self = crystalDamage(mc.player, center);
                    if (!selfSafe(self)) continue;
                    float score = crystalDamage(target, center) - self * 1.5f;
                    if (score > bestScore && supported(base)) {
                        bestScore = score;
                        bestBase = base;
                    }
                }
            }
            if (bestBase == null || bestScore < minDamage.value) return false;
            for (Direction side : Direction.values()) {
                BlockPos nb = bestBase.offset(side);
                if (mc.world.getBlockState(nb).isReplaceable() || mc.world.isAir(nb)) continue;
                BlockHitResult h = faceHit(eye, nb, reach);
                if (h == null || h.getSide() != side.getOpposite()) continue;
                if (!hold(slot)) return false;
                return click(h, Hand.MAIN_HAND, bestBase);
            }
            return false;
        }

        private boolean supported(BlockPos pos) {
            for (Direction d : Direction.values()) {
                BlockPos nb = pos.offset(d);
                if (!mc.world.getBlockState(nb).isReplaceable() && !mc.world.isAir(nb)) return true;
            }
            return false;
        }
    }

    /* ------------------------------------------------------------------ Auto Totem */

    /** Keeps a totem in the off hand (and optionally a hotbar slot) with human-like delays. */
    static final class AutoTotem extends Module {
        private final Setting.Mode mode = mode("Mode", "Open Inventory", "Open Inventory", "Silent Open", "Dont Open");
        private final Setting.Num minDelay = num("Min Delay", 50, 0, 500, 25);
        private final Setting.Num maxDelay = num("Max Delay", 150, 0, 500, 25);
        private final Setting.Bool randomSlot = bool("Random Slot", false);
        private final Setting.Bool autoClose = bool("Auto Close", true);
        private final Setting.Bool refillHotbar = bool("Refill Hotbar", false);
        private final Setting.Num refillSlot = num("Refill Slot", 9, 1, 9, 1).showIf(refillHotbar::on);

        private enum Stage { IDLE, WAIT_OPEN, WAIT_CLICK, WAIT_CLOSE }

        private long actionAt, closeAt, stageAt;
        private boolean waitingClose, waitingOpen;
        private Stage stage = Stage.IDLE;
        private int pendingSource = -1, pendingButton = -1;
        private int tickCounter, openedTick;

        AutoTotem() {
            super("Auto Totem", "Keeps a totem of undying in your off hand", Category.COMBAT);
        }

        private long rnd() {
            long lo = (long) Math.min(minDelay.value, maxDelay.value), hi = (long) Math.max(minDelay.value, maxDelay.value);
            return lo >= hi ? lo : ThreadLocalRandom.current().nextLong(lo, hi + 1);
        }

        @Override
        protected void onEnable() {
            actionAt = System.currentTimeMillis() + rnd();
            reset();
        }

        @Override
        protected void onDisable() {
            reset();
        }

        private void reset() {
            waitingClose = false;
            waitingOpen = false;
            stage = Stage.IDLE;
            pendingSource = pendingButton = -1;
        }

        private boolean invOpen() {
            return mc.currentScreen instanceof InventoryScreen;
        }

        @Override
        public void tick() {
            tickCounter++;
            if (mc.interactionManager == null) return;
            if (mc.currentScreen != null && !invOpen()) return;

            if (!mode.is("Silent Open") && stage != Stage.IDLE) reset();
            if (mode.is("Silent Open") && stage != Stage.IDLE) {
                silentStep();
                return;
            }
            if (waitingClose) pendingClose();

            if (!mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
                refill(-1);
                return;
            }
            if (refillHotbar.on()) {
                int target = refillSlot.i() - 1;
                if (target >= 0 && target <= 8 && !mc.player.getInventory().getStack(target).isOf(Items.TOTEM_OF_UNDYING)) refill(target);
            }
        }

        private void refill(int hotbarTarget) {
            int source = findSource(hotbarTarget);
            if (source == -1) {
                waitingOpen = false;
                return;
            }
            int button = hotbarTarget < 0 ? 40 : hotbarTarget;
            if (mode.is("Open Inventory") && !invOpen()) {
                if (!waitingOpen) {
                    waitingOpen = true;
                    actionAt = System.currentTimeMillis() + rnd();
                    return;
                }
                if (System.currentTimeMillis() < actionAt) return;
                waitingOpen = false;
                mc.setScreen(new InventoryScreen(mc.player));
                return;
            }
            if (System.currentTimeMillis() < actionAt) return;
            if (mode.is("Silent Open")) {
                pendingSource = source;
                pendingButton = button;
                stage = Stage.WAIT_OPEN;
                stageAt = System.currentTimeMillis() + rnd();
                return;
            }
            if (!invOpen()) return;
            swap(source, button, true);
        }

        private void swap(int source, int button, boolean close) {
            mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, source, button, SlotActionType.SWAP, mc.player);
            actionAt = System.currentTimeMillis() + rnd();
            if (close && autoClose.on()) {
                long w = rnd();
                if (w <= 0) closeNow();
                else {
                    closeAt = System.currentTimeMillis() + w;
                    waitingClose = true;
                }
            }
        }

        private void pendingClose() {
            if (!autoClose.on() || (mode.is("Open Inventory") && !invOpen())) {
                waitingClose = false;
                return;
            }
            if (System.currentTimeMillis() >= closeAt) closeNow();
        }

        private void closeNow() {
            waitingClose = false;
            if (invOpen()) mc.player.closeHandledScreen();
        }

        private void silentStep() {
            long now = System.currentTimeMillis();
            var conn = mc.getNetworkHandler();
            if (conn == null) {
                reset();
                return;
            }
            switch (stage) {
                case WAIT_OPEN -> {
                    if (now < stageAt) return;
                    conn.sendPacket(new net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket(mc.player,
                            net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket.Mode.OPEN_INVENTORY));
                    stage = Stage.WAIT_CLICK;
                    openedTick = tickCounter;
                }
                case WAIT_CLICK -> {
                    if (tickCounter - openedTick < 1) return;
                    mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, pendingSource, pendingButton, SlotActionType.SWAP, mc.player);
                    actionAt = now + rnd();
                    if (!autoClose.on()) {
                        conn.sendPacket(new net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
                        reset();
                        return;
                    }
                    stage = Stage.WAIT_CLOSE;
                    stageAt = now + rnd();
                }
                case WAIT_CLOSE -> {
                    if (now < stageAt) return;
                    conn.sendPacket(new net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket(mc.player.currentScreenHandler.syncId));
                    reset();
                }
                default -> reset();
            }
        }

        private int findSource(int excludedHotbar) {
            java.util.ArrayList<Integer> found = new java.util.ArrayList<>();
            for (int i = 9; i < 36; i++) {
                if (mc.player.getInventory().getStack(i).isOf(Items.TOTEM_OF_UNDYING)) found.add(i);
            }
            if (found.isEmpty()) return -1;
            return randomSlot.on() ? found.get(ThreadLocalRandom.current().nextInt(found.size())) : found.get(0);
        }
    }

    /* ------------------------------------------------------------------ Anchor Macro */

    /** Hold the key and aim at a block: places an anchor, charges it with glowstone and explodes it. */
    static final class AnchorMacro extends Module {
        private final Setting.Key key = add(new Setting.Key("Activation Key", org.lwjgl.glfw.GLFW.GLFW_KEY_X));
        private final Setting.Num minDelay = num("Min Delay", 0, 0, 500, 5);
        private final Setting.Num maxDelay = num("Max Delay", 30, 0, 500, 5);
        private final Setting.Bool fastSwap = bool("Fast Swap", true);
        private final Setting.Num restoreDelay = num("Restore Delay", 2, 1, 20, 1);

        private enum Step { NONE, SWAPPED, USED }

        private long nextAt;
        private boolean keyPressed, active, placed, pendingRestore;
        private int originalSlot = -1, restoreLeft;

        AnchorMacro() {
            super("Anchor Macro", "Places, charges and detonates respawn anchors while the key is held", Category.COMBAT);
        }

        @Override
        protected void onEnable() {
            keyPressed = active = placed = pendingRestore = false;
            originalSlot = -1;
        }

        @Override
        protected void onDisable() {
            stop();
            if (mc.player != null && originalSlot != -1) mc.player.getInventory().setSelectedSlot(originalSlot);
            originalSlot = -1;
            pendingRestore = false;
        }

        private long delay() {
            long lo = (long) Math.min(minDelay.value, maxDelay.value), hi = (long) Math.max(minDelay.value, maxDelay.value);
            return lo >= hi ? lo : ThreadLocalRandom.current().nextLong(lo, hi + 1);
        }

        @Override
        public void tick() {
            if (mc.interactionManager == null || mc.currentScreen != null) return;
            boolean down = keyDown(key.code);
            if (down && !keyPressed) {
                active = true;
                if (originalSlot == -1) originalSlot = mc.player.getInventory().getSelectedSlot();
                placed = false;
                nextAt = 0;
            } else if (!down && keyPressed) {
                stop();
            } else if (!down) {
                placed = false;
            }
            keyPressed = down;

            if (pendingRestore) {
                if (restoreLeft > 0) restoreLeft--;
                else {
                    restore();
                    pendingRestore = false;
                }
                return;
            }
            if (!active || System.currentTimeMillis() < nextAt) return;
            Step s = process();
            if (s == Step.NONE) return;
            nextAt = System.currentTimeMillis() + (s == Step.SWAPPED ? 0L : delay());
        }

        private void stop() {
            if (!active) return;
            active = false;
            if (originalSlot != -1 && mc.player != null && mc.player.getInventory().getSelectedSlot() != originalSlot) {
                pendingRestore = true;
                restoreLeft = 0;
            } else {
                originalSlot = -1;
            }
        }

        private void restore() {
            if (originalSlot == -1 || mc.player == null) return;
            mc.player.getInventory().setSelectedSlot(originalSlot);
            if (!active) originalSlot = -1;
        }

        private Step process() {
            if (!(mc.crosshairTarget instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) return Step.NONE;
            BlockPos target = bh.getBlockPos();
            var state = mc.world.getBlockState(target);
            if (state.isAir()) return Step.NONE;

            if (state.getBlock() == Blocks.RESPAWN_ANCHOR) {
                if (state.get(RespawnAnchorBlock.CHARGES) > 0) {
                    int slot = findHotbar(Items.TOTEM_OF_UNDYING);
                    if (slot == -1) {
                        for (int i = 0; i < 9; i++) {
                            var st = mc.player.getInventory().getStack(i);
                            if (!st.isEmpty() && st.isIn(ItemTags.SWORDS)) {
                                slot = i;
                                break;
                            }
                        }
                    }
                    Step st = act(slot, target, true);
                    if (st == Step.USED) {
                        placed = true;
                        if (originalSlot != -1) {
                            pendingRestore = true;
                            restoreLeft = restoreDelay.i();
                        }
                    }
                    return st;
                }
                Step st = act(findHotbar(Items.GLOWSTONE), target, true);
                if (st == Step.USED) placed = true;
                return st;
            }
            BlockPos place = target.offset(bh.getSide());
            if (!placed && validAnchorPos(place)) {
                Step st = act(findHotbar(Items.RESPAWN_ANCHOR), target, false);
                if (st == Step.USED) placed = true;
                return st;
            }
            return Step.NONE;
        }

        private Step act(int slot, BlockPos clicked, boolean onAnchor) {
            if (slot == -1) return Step.NONE;
            boolean needSwap = mc.player.getInventory().getSelectedSlot() != slot;
            if (needSwap) {
                if (originalSlot == -1) originalSlot = mc.player.getInventory().getSelectedSlot();
                mc.player.getInventory().setSelectedSlot(slot);
                if (!fastSwap.on()) return Step.SWAPPED;
            }
            BlockHitResult hit = faceHit(clicked, onAnchor);
            if (hit == null) return needSwap ? Step.SWAPPED : Step.NONE;
            Vec3d eye = mc.player.getEyePos();
            float[] rot = angles(eye, hit.getPos());
            BlockHitResult check = rayFromRotation(rot[0], rot[1]);
            if (check == null || check.getType() != HitResult.Type.BLOCK
                    || !check.getBlockPos().equals(hit.getBlockPos()) || check.getSide() != hit.getSide()) {
                return needSwap ? Step.SWAPPED : Step.NONE;
            }
            silentLook(rot[0], rot[1]);
            ActionResult res = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(check.getPos(), check.getSide(), check.getBlockPos(), false));
            if (res instanceof ActionResult.Success s && s.swingSource() == ActionResult.SwingSource.CLIENT) mc.player.swingHand(Hand.MAIN_HAND);
            return Step.USED;
        }

        private BlockHitResult faceHit(BlockPos pos, boolean onAnchor) {
            Vec3d eye = mc.player.getEyePos();
            double reach = mc.player.getBlockInteractionRange();
            if (onAnchor) {
                // Aim at the anchor, trying the centre of each exposed face.
                Vec3d c = Vec3d.ofCenter(pos);
                Vec3d[] pts = new Vec3d[7];
                pts[0] = c;
                int n = 1;
                for (Direction d : Direction.values()) pts[n++] = c.add(d.getOffsetX() * 0.45, d.getOffsetY() * 0.45, d.getOffsetZ() * 0.45);
                for (Vec3d p : pts) {
                    BlockHitResult ray = mc.world.raycast(new RaycastContext(eye, eye.add(p.subtract(eye).normalize().multiply(reach)),
                            RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
                    if (ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(pos)) return ray;
                }
                return null;
            }
            if (!(mc.crosshairTarget instanceof BlockHitResult cur) || !cur.getBlockPos().equals(pos)) return null;
            Direction side = cur.getSide();
            Vec3d fc = Vec3d.ofCenter(pos).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
            double[][] samples = {{0, 0}, {0.2, 0.2}, {-0.2, 0.2}, {0.2, -0.2}, {-0.2, -0.2}};
            for (double[] s : samples) {
                Vec3d point = switch (side.getAxis()) {
                    case X -> fc.add(0.0, s[0], s[1]);
                    case Y -> fc.add(s[0], 0.0, s[1]);
                    case Z -> fc.add(s[0], s[1], 0.0);
                };
                Vec3d end = point.add(side.getOffsetX() * 0.01, side.getOffsetY() * 0.01, side.getOffsetZ() * 0.01);
                BlockHitResult ray = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                        RaycastContext.FluidHandling.NONE, mc.player));
                if (ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(pos) && ray.getSide() == side) {
                    return new BlockHitResult(point, side, pos, false);
                }
            }
            return null;
        }

        private boolean validAnchorPos(BlockPos pos) {
            if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos)) > mc.player.getBlockInteractionRange()) return false;
            var st = mc.world.getBlockState(pos);
            if (!st.isAir() && !st.isReplaceable()) return false;
            Box box = new Box(pos);
            if (!mc.world.getOtherEntities(null, box).stream().noneMatch(e -> !e.isRemoved() && !e.isSpectator())) return false;
            BlockPos pp = mc.player.getBlockPos();
            return !pos.equals(pp) && !pos.equals(pp.up());
        }
    }
}

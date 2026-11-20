package tenacity.modules;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Attacks the moment the crosshair is on a valid target in reach, with the weapon charged.
 *
 * <p>It runs at the very start of the client tick (before the game handles input), so the attack goes out in front of this tick's movement
 * packet exactly where a real click does, and never between ticks. It uses its own ray against the real hitboxes (box plus targeting
 * margin, nearest first, blocked by walls), measured from the eye and kept a hair under the real reach, so it cannot hit what a normal
 * click could not hit.
 *
 * <p>Crits: it waits for the fall of a jump instead of hitting on the way up, and can drop sprint in the air so the falling hit is a real
 * crit. Stick To Target keeps it on the first target it hit and ignores everyone else until that target dies or leaves.
 */
public final class TriggerBot extends Module {
    private static TriggerBot instance;

    private final Setting.Num reach = num("Reach", 3.0, 2.0, 3.0, 0.05);
    private final Setting.Num margin = num("Reach Margin", 0.02, 0.0, 0.1, 0.01);
    private final Setting.Bool players = bool("Target Players", true);
    private final Setting.Bool mobs = bool("Target Mobs", true);
    private final Setting.Bool animals = bool("Target Animals", false);
    private final Setting.Bool teams = bool("Ignore Teammates", true);
    private final Setting.Bool invisible = bool("Target Invisible", true);
    private final Setting.Bool weaponOnly = bool("Weapons Only", true);
    private final Setting.Num chargeMin = num("Charge Min %", 84, 30, 100, 1);
    private final Setting.Num chargeMax = num("Charge Max %", 94, 30, 100, 1);
    private final Setting.Mode crit = mode("Crits", "Prioritize", "Off", "Prioritize", "Only");
    private final Setting.Num critWait = num("Crit Wait (ticks)", 5, 1, 16, 1).showIf(() -> crit.is("Prioritize"));
    private final Setting.Bool sprintReset = bool("Sprint Reset Crit", true).showIf(() -> !crit.is("Off"));
    private final Setting.Bool hurtCheck = bool("Hurt Time Check", true);
    private final Setting.Bool stick = bool("Stick To Target", true);
    private final Setting.Num stickRange = num("Stick Range", 6, 3, 12, 0.5).showIf(() -> stick.on());
    private final Setting.Bool skipBlocking = bool("Skip Blocking", true);
    private final Setting.Bool unshield = bool("Unshield", true);
    private final Setting.Bool smash = bool("Mace Smash", true);
    private final Setting.Bool requireClick = bool("Only While Attack Held", false);
    private final Setting.Bool pauseUse = bool("Pause While Using", true);
    private final Setting.Num reaction = num("Reaction (ms)", 0, 0, 300, 5);
    private final Setting.Num hitDelay = num("Hit Delay (ms)", 0, 0, 300, 5);
    private final Setting.Num miss = num("Skip Chance %", 0, 0, 20, 1);
    private final Setting.Bool clickSound = bool("Mouse Click Sound", true);
    private final Setting.Mode soundType = mode("Mouse Profile", "Logitech G203", MouseClickSound.TYPES).showIf(() -> clickSound.on());
    private final Setting.Num soundVol = num("Click Volume %", 60, 5, 100, 5).showIf(() -> clickSound.on());

    private final Map<Integer, Long> hitAt = new HashMap<>();
    private long ticks;
    private int lockId = -1;
    private int seenId = -1;
    private long seenAt;
    private long lastHit;
    private int lastAttackAge = -1;
    private int airTicks;
    private int cooldownGate;
    private boolean sprintBlocked;
    private boolean unblocked;
    private float rolledCharge = 0.94f;
    private long rolledDelay;

    private record Pick(LivingEntity entity, Vec3d point) {
    }

    public TriggerBot() {
        super("Trigger Bot", "Hits what you aim at with perfect timing", Category.COMBAT);
        instance = this;
    }

    /** True while a mid-air sprint reset for a crit is pending, so the sprint module must not turn sprint back on. */
    public static boolean blocksSprint() {
        return instance != null && instance.enabled() && instance.sprintBlocked;
    }

    @Override
    public String suffix() {
        return crit.is("Off") ? "Charge" : "Crits";
    }

    @Override
    protected void onEnable() {
        MouseClickSound.warm(soundType.get());
    }

    @Override
    protected void onDisable() {
        lockId = -1;
        seenId = -1;
        airTicks = 0;
        reblock();
        sprintBlocked = false;
        hitAt.clear();
    }

    private static boolean isWeapon(ItemStack s) {
        return s.isIn(ItemTags.SWORDS) || s.isIn(ItemTags.AXES) || s.isIn(ItemTags.SPEARS) || s.isOf(Items.MACE) || s.isOf(Items.TRIDENT);
    }

    /** Mid-air states in which no crit can happen on this jump, so waiting for one would only delay the hit. */
    private boolean critImpossibleAir() {
        var p = mc.player;
        return p.isTouchingWater() || p.isInLava() || p.isClimbing() || p.hasVehicle() || p.isGliding() || p.getAbilities().flying
                || p.hasStatusEffect(StatusEffects.BLINDNESS) || p.hasStatusEffect(StatusEffects.LEVITATION)
                || p.hasStatusEffect(StatusEffects.SLOW_FALLING) || mc.world.getBlockState(p.getBlockPos()).isOf(net.minecraft.block.Blocks.COBWEB);
    }

    private boolean critImpossible() {
        return critImpossibleAir() || (mc.player.isSprinting() && !sprintReset.on());
    }

    private boolean critReady() {
        var p = mc.player;
        return !p.isOnGround() && !p.isSprinting() && p.fallDistance > 0.0f && p.getVelocity().y < 0.0 && !critImpossibleAir();
    }

    @Override
    public void preTick() {
        if (!inWorld() || mc.interactionManager == null) return;
        var p = mc.player;
        ticks++;
        airTicks = p.isOnGround() ? 0 : airTicks + 1;
        if (cooldownGate > 0) cooldownGate--;

        // the lock ends when the target dies, disappears or gets further away than the stick range
        if (lockId != -1) {
            Entity l = mc.world.getEntityById(lockId);
            if (!(l instanceof LivingEntity le) || !le.isAlive() || le.isRemoved() || le.getHealth() <= 0f || p.distanceTo(le) > stickRange.value) lockId = -1;
        }
        if (!stick.on()) lockId = -1;

        if (mc.currentScreen != null || p.isSpectator() || !p.isAlive()) {
            sprintBlocked = false;
            return;
        }
        if (weaponOnly.on() && !isWeapon(p.getMainHandStack())) {
            sprintBlocked = false;
            seenId = -1;
            return;
        }

        Pick pick = pick();
        updateSprintReset(pick);
        if (pick == null) {
            seenId = -1;
            return;
        }
        LivingEntity t = pick.entity();
        if (!allowed(t)) return;

        if (p.isUsingItem()) {
            // a raised shield is dropped for the hit and put back right after; anything else (eating, bows) is never interrupted
            if (unshield.on() && !unblocked && p.getActiveItem().contains(DataComponentTypes.BLOCKS_ATTACKS) && physicalDown(mc.options.useKey)) {
                mc.options.useKey.setPressed(false);
                unblocked = true;
            } else if (pauseUse.on() && !unblocked) {
                return;
            }
            return;
        }
        if (requireClick.on() && !physicalDown(mc.options.attackKey)) return;
        if (lastAttackAge == p.age || cooldownGate > 0) return;
        if (skipBlocking.on() && t.isBlocking() && !p.getMainHandStack().isIn(ItemTags.AXES)) return;
        if (hurtCheck.on() && hurt(t)) return;

        // the weapon's own range (spears) has the last word
        AttackRangeComponent wr = p.getMainHandStack().get(DataComponentTypes.ATTACK_RANGE);
        if (wr != null && !wr.isWithinRange(p, pick.point())) return;

        // reaction: a fresh target is only hit after the configured delay (0 = the same tick)
        long now = System.currentTimeMillis();
        if (t.getId() != seenId) {
            seenId = t.getId();
            seenAt = now;
            rolledDelay = (long) (reaction.value * (0.8 + ThreadLocalRandom.current().nextDouble() * 0.4));
        }
        if (now - seenAt < rolledDelay) return;
        if (now - lastHit < hitDelay.value) return;

        // charge exactly like the server counts it; a crit needs the strong swing, so it never goes under 0.9
        boolean wantCrit = !crit.is("Off") && !p.isOnGround() && !critImpossible();
        float need = rolledCharge;
        if (wantCrit) need = Math.max(need, 0.92f);
        if (p.getAttackCooldownProgress(0.5f) < need) return;

        // mace: only a real smash (falling more than 1.5 blocks) is worth a swing
        if (smash.on() && p.getMainHandStack().isOf(Items.MACE) && p.fallDistance < 1.5f && !p.isOnGround()) return;

        // crits: in the air wait for the fall instead of hitting on the way up (Only never hits without a crit)
        if (!crit.is("Off") && !p.isOnGround() && !critImpossible() && !critReady()) {
            if (crit.is("Only") || airTicks <= critWait.value) return;
        }
        if (crit.is("Only") && p.isOnGround()) return;

        if (miss.value > 0 && ThreadLocalRandom.current().nextDouble() * 100.0 < miss.value) {
            cooldownGate = 1;
            return;
        }
        attack(pick);
    }

    private void attack(Pick pick) {
        var p = mc.player;
        LivingEntity t = pick.entity();
        mc.crosshairTarget = new EntityHitResult(t, pick.point());
        mc.interactionManager.attackEntity(p, t);
        p.swingHand(Hand.MAIN_HAND);
        lastAttackAge = p.age;
        lastHit = System.currentTimeMillis();
        hitAt.put(t.getId(), ticks);
        if (stick.on() && lockId == -1) lockId = t.getId();
        sprintBlocked = false;
        rolledCharge = (float) (chargeMin.value + ThreadLocalRandom.current().nextDouble() * Math.max(0, chargeMax.value - chargeMin.value)) / 100f;
        reblock();
        if (clickSound.on()) MouseClickSound.play(soundType.get(), (float) soundVol.value / 100f);
    }

    /** The target under the crosshair, with a ray of our own against the server's hitboxes; walls and nearer entities block it. */
    private Pick pick() {
        var p = mc.player;
        ItemStack held = p.getMainHandStack();
        AttackRangeComponent wr = held.get(DataComponentTypes.ATTACK_RANGE);
        double limit = wr != null ? wr.getEffectiveMaxRange(p) : p.getEntityInteractionRange();
        double range = Math.min(reach.value, limit) - margin.value;
        Vec3d eye = p.getEyePos();
        Vec3d dir = p.getRotationVec(1.0f);
        Vec3d end = eye.add(dir.multiply(range));
        Box search = p.getBoundingBox().stretch(dir.multiply(range)).expand(1.0);

        LivingEntity best = null;
        Vec3d bestPoint = null;
        double bestDist = range * range;
        for (Entity e : mc.world.getOtherEntities(p, search, x -> x instanceof LivingEntity && !x.isSpectator() && x.canHit())) {
            if (lockId != -1 && e.getId() != lockId) continue; // stuck on one target: everyone else is ignored
            Box box = e.getBoundingBox().expand(e.getTargetingMargin());
            Vec3d point;
            if (box.contains(eye)) {
                point = eye;
            } else {
                Optional<Vec3d> hit = box.raycast(eye, end);
                if (hit.isEmpty()) continue;
                point = hit.get();
            }
            double d = eye.squaredDistanceTo(point);
            if (d <= bestDist) {
                bestDist = d;
                best = (LivingEntity) e;
                bestPoint = point;
            }
        }
        if (best == null) return null;
        HitResult block = mc.world.raycast(new RaycastContext(eye, bestPoint, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
        if (block != null && block.getType() == HitResult.Type.BLOCK && block.getPos().squaredDistanceTo(eye) < bestDist - 1.0e-3) return null;
        return new Pick(best, bestPoint);
    }

    /**
     * The server never crits a sprinting player, so a sprint-jump into a target would wait the whole jump. Drop sprint once airborne with a
     * target in reach: the stop-sprint goes out with this tick's movement packet, so the hit on the way down is a real crit.
     */
    private void updateSprintReset(Pick pick) {
        var p = mc.player;
        boolean want = sprintReset.on() && !crit.is("Off") && pick != null && allowed(pick.entity()) && !p.isOnGround() && !critImpossibleAir();
        sprintBlocked = want;
        if (want && p.isSprinting()) p.setSprinting(false);
    }

    /** The target is still inside its damage immunity: the hurt animation reaches us half a ping late and our hit half a ping later. */
    private boolean hurt(LivingEntity t) {
        long mine = 0;
        Long last = hitAt.get(t.getId());
        if (last != null) {
            long el = ticks - last;
            if (el >= 10) hitAt.remove(t.getId());
            else mine = 10 - el;
        }
        int pingTicks = 0;
        if (mc.getNetworkHandler() != null) {
            var entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
            if (entry != null) pingTicks = entry.getLatency() / 50;
        }
        long vanilla = Math.max(t.hurtTime - pingTicks, 0);
        return Math.max(mine, vanilla) > 0;
    }

    private boolean allowed(LivingEntity e) {
        if (e == mc.player || e == mc.getCameraEntity() || !e.isAlive() || e.isSpectator() || e.isInvulnerable()) return false;
        if (!invisible.on() && e.isInvisible()) return false;
        if (e instanceof PlayerEntity pl) {
            if (!players.on() || pl.isCreative()) return false;
            return !(teams.on() && pl.isTeammate(mc.player));
        }
        if (!(e instanceof MobEntity)) return false; // armor stands and the like
        if (e instanceof Monster) return mobs.on();
        if (!animals.on()) return false;
        return !(e instanceof TameableEntity pet && pet.isTamed() && pet.isOwner(mc.player));
    }

    /** Whether the physical key or mouse button behind a binding is held (the binding's own state may have been cleared by us). */
    private boolean physicalDown(net.minecraft.client.option.KeyBinding kb) {
        var key = net.minecraft.client.util.InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey());
        long w = mc.getWindow().getHandle();
        return key.getCategory() == net.minecraft.client.util.InputUtil.Type.MOUSE
                ? GLFW.glfwGetMouseButton(w, key.getCode()) == GLFW.GLFW_PRESS
                : GLFW.glfwGetKey(w, key.getCode()) == GLFW.GLFW_PRESS;
    }

    /** Puts the shield back up after the hit, but only if the use key is still held. */
    private void reblock() {
        if (!unblocked) return;
        unblocked = false;
        if (mc.player != null && physicalDown(mc.options.useKey)) mc.options.useKey.setPressed(true);
    }
}

package tenacity.modules;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.util.BlockUpdateHook;
import tenacity.util.PacketHook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** DonutSMP utilities: relog, selling, homes, tpa spam, vanish detection and spawner protection. */
public final class CryptoDonut {
    private CryptoDonut() {
    }

    public static List<Module> create() {
        return List.of(new AutoRelog(), new AutoSell(), new AutoSellAxe(), new HomeMacro(), new AutoTpahere(),
                new AntiVanish(), new SpawnerProtect());
    }

    private static void command(String cmd) {
        var handler = MinecraftClient.getInstance().getNetworkHandler();
        if (handler != null) handler.sendChatCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
    }

    private static void info(String msg) {
        var p = MinecraftClient.getInstance().player;
        if (p != null) p.sendMessage(Text.literal("\u00a77[\u00a76Donut\u00a77] \u00a7f" + msg), false);
    }

    /* ------------------------------------------------------------------------------------------------ */

    /** Reconnects when you fall into the void. */
    public static final class AutoRelog extends Module {
        private static final double TRIGGER_Y = -5.0;
        private static final double REARM_Y = 10.0;
        private boolean armed = true;

        public AutoRelog() {
            super("Auto Relog", "Reconnects to the server when you fall to Y -5 and rearms at Y 10", Category.DONUT);
        }

        @Override
        protected void onEnable() {
            armed = true;
        }

        @Override
        protected void onDisable() {
            armed = true;
        }

        @Override
        public void tick() {
            if (mc.player == null || mc.world == null || mc.currentScreen instanceof LevelLoadingScreen) return;
            double y = mc.player.getY();
            if (!armed && y >= REARM_Y) armed = true;
            else if (armed && y <= TRIGGER_Y) relog();
        }

        private void relog() {
            if (mc.isInSingleplayer()) return;
            ServerInfo server = mc.getCurrentServerEntry();
            if (server == null || server.isRealm()) return;
            armed = false;
            // disconnect on a frame boundary, not in the middle of the entity tick loop
            CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
                if (mc.world != null) mc.disconnect(ClientWorld.QUITTING_MULTIPLAYER_TEXT);
                CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
                    if (mc.world == null)
                        ConnectScreen.connect(new MultiplayerScreen(null), mc, ServerAddress.parse(server.address), server, false, null);
                }));
            }));
        }
    }

    /* ------------------------------------------------------------------------------------------------ */

    /** Sells an item through /sell or posts /order requests. */
    public static final class AutoSell extends Module {
        private final Setting.Mode mode = mode("Mode", "Sell", "Sell", "Order");
        private final Setting.Text item = text("Item", "sea_pickle", 48);
        private final Setting.Text who = text("Who To Sell", "", 16).showIf(() -> mode.is("Order"));
        private final Setting.Num delay = num("Delay", 2, 0, 20, 1);
        private final Setting.Bool quick = bool("Quick Deliver", true);
        private int delayTicks;

        public AutoSell() {
            super("Auto Sell", "Automatically sells the chosen item through the sell menu or order requests", Category.DONUT);
        }

        @Override
        protected void onEnable() {
            delayTicks = 0;
        }

        @Override
        public void tick() {
            if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
            if (delayTicks > 0) {
                delayTicks--;
                return;
            }
            var handler = mc.player.currentScreenHandler;
            GenericContainerScreenHandler container = handler instanceof GenericContainerScreenHandler g ? g : null;

            if (mode.is("Sell")) {
                if (container != null && container.getRows() == 4) {
                    sellMenu(handler);
                } else if (container != null && container.getRows() == 3) {
                    mc.interactionManager.clickSlot(handler.syncId, 15, 1, SlotActionType.QUICK_MOVE, mc.player);
                    delayTicks = 10;
                } else if (container != null && container.getRows() == 6) {
                    orderMenu(container);
                } else {
                    command("sell");
                    delayTicks = 20;
                }
            } else {
                String name = formattedName();
                if (name.equals("Air")) {
                    delayTicks = 20;
                    return;
                }
                String target = who.get().trim();
                if (!target.isEmpty()) name = name + " " + target;
                command("order " + name);
                delayTicks = 20;
            }
        }

        private void sellMenu(net.minecraft.screen.ScreenHandler handler) {
            if (!hasEmptySlot()) {
                mc.player.closeHandledScreen();
                delayTicks = 10;
                return;
            }
            Item target = resolveItem();
            int syncId = handler.syncId;
            List<Integer> slots = new ArrayList<>();
            ItemStack reference = ItemStack.EMPTY;
            int size = handler.slots.size();
            for (int slot = Math.max(0, size - 36); slot < size; slot++) {
                ItemStack st = handler.getSlot(slot).getStack();
                if (st.isEmpty() || st.getItem() != target) continue;
                if (reference.isEmpty()) {
                    reference = st;
                    slots.add(slot);
                } else if (ItemStack.areItemsAndComponentsEqual(reference, st)) {
                    slots.add(slot);
                }
            }
            if (slots.isEmpty()) {
                mc.player.closeHandledScreen();
                delayTicks = 10;
                return;
            }
            if (quick.on() && slots.size() > 1) {
                int first = slots.get(0);
                mc.interactionManager.clickSlot(syncId, first, 0, SlotActionType.PICKUP, mc.player);
                for (int i = 1; i < slots.size(); i++)
                    mc.interactionManager.clickSlot(syncId, slots.get(i), 0, SlotActionType.QUICK_MOVE, mc.player);
                mc.interactionManager.clickSlot(syncId, first, 0, SlotActionType.PICKUP, mc.player);
                mc.interactionManager.clickSlot(syncId, first, 0, SlotActionType.QUICK_MOVE, mc.player);
            } else {
                for (int s : slots) {
                    mc.interactionManager.clickSlot(syncId, s, 1, SlotActionType.QUICK_MOVE, mc.player);
                    if (delay.i() != 0) {
                        delayTicks = delay.i();
                        return;
                    }
                }
            }
            delayTicks = 5;
        }

        private void orderMenu(GenericContainerScreenHandler container) {
            int syncId = container.syncId;
            ItemStack sort = container.getSlot(47).getStack();
            if (sort.isEmpty()) {
                delayTicks = 2;
                mc.player.closeHandledScreen();
                return;
            }
            Item.TooltipContext ctx = Item.TooltipContext.create(mc.world);
            for (Text line : sort.getTooltip(ctx, mc.player, TooltipType.BASIC)) {
                String s = line.toString();
                if (s.contains("Most Money Per Item") && (line.getStyle().toString().contains("white") || s.contains("white"))) {
                    mc.interactionManager.clickSlot(syncId, 47, 1, SlotActionType.QUICK_MOVE, mc.player);
                    delayTicks = 5;
                    return;
                }
            }
            Item target = resolveItem();
            for (int slot = 0; slot < 44; slot++) {
                if (container.getSlot(slot).getStack().isOf(target)) {
                    mc.interactionManager.clickSlot(syncId, slot, 1, SlotActionType.QUICK_MOVE, mc.player);
                    delayTicks = 10;
                    return;
                }
            }
            delayTicks = 40;
            mc.player.closeHandledScreen();
        }

        private boolean hasEmptySlot() {
            for (int i = 0; i < 36; i++) if (mc.player.getInventory().getStack(i).isEmpty()) return true;
            return false;
        }

        private String formattedName() {
            Item it = resolveItem();
            return it == Items.BONE ? "Bones" : it.getName().getString();
        }

        private Item resolveItem() {
            String text = item.get().trim();
            if (text.isEmpty()) return Items.SEA_PICKLE;
            String id = text.toLowerCase(Locale.ROOT).replace(' ', '_');
            if (!id.contains(":")) id = "minecraft:" + id;
            Identifier ident = Identifier.tryParse(id);
            if (ident != null && Registries.ITEM.containsId(ident)) return Registries.ITEM.get(ident);
            for (Item it : Registries.ITEM)
                if (it != Items.AIR && it.getName().getString().equalsIgnoreCase(text)) return it;
            return Items.SEA_PICKLE;
        }

        @Override
        public String suffix() {
            return mode.get();
        }
    }

    /* ------------------------------------------------------------------------------------------------ */

    /** Holds attack with a netherite axe from the hotbar (for axe based sell farms). */
    public static final class AutoSellAxe extends Module {
        public AutoSellAxe() {
            super("Auto Sell Axe", "Switches to a netherite axe in the hotbar and holds left click", Category.DONUT);
        }

        @Override
        public void tick() {
            if (mc.player == null || mc.world == null || mc.currentScreen != null || mc.options == null) {
                release();
                return;
            }
            if (!mc.player.getMainHandStack().isOf(Items.NETHERITE_AXE)) {
                int slot = -1;
                for (int i = 0; i < 9; i++) {
                    if (mc.player.getInventory().getStack(i).isOf(Items.NETHERITE_AXE)) {
                        slot = i;
                        break;
                    }
                }
                if (slot < 0) {
                    mc.options.attackKey.setPressed(false);
                    return;
                }
                mc.player.getInventory().setSelectedSlot(slot);
                if (mc.player.networkHandler != null) mc.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
            }
            mc.options.attackKey.setPressed(true);
        }

        @Override
        protected void onDisable() {
            release();
        }

        private void release() {
            if (mc.options != null) mc.options.attackKey.setPressed(false);
        }
    }

    /* ------------------------------------------------------------------------------------------------ */

    /** Runs /delhome then /sethome for a slot, then switches itself off. */
    public static final class HomeMacro extends Module {
        private final Setting.Num slot = num("Home Slot", 1, 1, 5, 1);

        public HomeMacro() {
            super("Home Macro", "Runs /delhome and /sethome for the selected home slot", Category.DONUT);
        }

        @Override
        protected void onEnable() {
            if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
                mc.execute(() -> setEnabled(false));
                return;
            }
            int n = slot.i();
            command("delhome " + n);
            CompletableFuture.delayedExecutor(1000, TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
                if (mc.player != null && mc.getNetworkHandler() != null) command("sethome " + n);
                setEnabled(false);
            }));
        }
    }

    /* ------------------------------------------------------------------------------------------------ */

    /** Repeats /tpahere or /tpa for a player. */
    public static final class AutoTpahere extends Module {
        private final Setting.Text player = text("Player Name", "", 16);
        private final Setting.Mode mode = mode("Mode", "Tpahere", "Tpahere", "Tpa");
        private final Setting.Num cooldown = num("Cooldown (s)", 15.5, 1, 60, 0.5);
        private int ticks;

        public AutoTpahere() {
            super("Auto Tpahere", "Repeatedly sends /tpahere or /tpa to a chosen player", Category.DONUT);
        }

        @Override
        protected void onEnable() {
            ticks = 0;
            if (player.get().trim().isEmpty()) {
                info("\u00a7cNo player name set.");
                mc.execute(() -> setEnabled(false));
                return;
            }
            send();
        }

        @Override
        public void tick() {
            if (mc.player == null || mc.world == null) return;
            if (++ticks >= (int) (cooldown.value * 20)) {
                ticks = 0;
                send();
            }
        }

        private void send() {
            String name = player.get().trim();
            if (name.isEmpty()) return;
            command((mode.is("Tpahere") ? "tpahere " : "tpa ") + name);
        }

        @Override
        public String suffix() {
            return mode.get();
        }
    }

    /* ------------------------------------------------------------------------------------------------ */

    /** Flags doors, chests and combat effects that happen with no visible player nearby (a vanished player). */
    public static final class AntiVanish extends Module implements PacketHook.Receiver {
        private final Setting.Num range = num("Range", 64, 16, 128, 4);
        private final Setting.Bool sounds = bool("Sounds", true);
        private final Setting.Bool particles = bool("Particles", true);
        private final Setting.Num cooldown = num("Alert Cooldown (s)", 5, 1, 30, 1);
        private long lastAlert;

        public AntiVanish() {
            super("Anti Vanish", "Warns about interaction sounds and effects with no visible player nearby", Category.DONUT);
        }

        @Override
        public void onPacket(Packet<?> packet) {
            if (packet instanceof PlaySoundS2CPacket p && sounds.on()) {
                String id = p.getSound().getKey().map(k -> k.getValue().toString()).orElse(null);
                if (id != null && suspiciousSound(id)) check("sound " + path(id), p.getX(), p.getY(), p.getZ());
            } else if (packet instanceof ParticleS2CPacket p && particles.on()) {
                String id = Registries.PARTICLE_TYPE.getId(p.getParameters().getType()).toString();
                if (suspiciousParticle(id)) check("particle " + path(id), p.getX(), p.getY(), p.getZ());
            }
        }

        private void check(String what, double x, double y, double z) {
            mc.execute(() -> {
                if (mc.player == null || mc.world == null) return;
                Vec3d at = new Vec3d(x, y, z);
                double r = range.value;
                if (mc.player.getEntityPos().squaredDistanceTo(at) > r * r) return;
                for (PlayerEntity pl : mc.world.getPlayers()) {
                    if (pl == mc.player) continue;
                    if (pl.squaredDistanceTo(at) < 36.0) return;
                }
                long now = System.currentTimeMillis();
                if (now - lastAlert < (long) (cooldown.value * 1000)) return;
                lastAlert = now;
                info("\u00a7eHidden player? \u00a77" + what + " at \u00a7f" + (int) x + " " + (int) y + " " + (int) z);
            });
        }

        private static String path(String id) {
            int i = id.indexOf(':');
            return (i >= 0 && i + 1 < id.length() ? id.substring(i + 1) : id).toLowerCase(Locale.ROOT);
        }

        private static boolean suspiciousSound(String id) {
            String p = path(id);
            return p.contains("chest.open") || p.contains("chest.close") || p.contains("chest.locked")
                    || p.contains("barrel.open") || p.contains("barrel.close")
                    || p.contains("shulker_box.open") || p.contains("shulker_box.close")
                    || p.contains("door.open") || p.contains("door.close")
                    || p.contains("fence_gate.open") || p.contains("fence_gate.close")
                    || p.contains("button.click") || p.contains("lever.click");
        }

        private static boolean suspiciousParticle(String id) {
            String p = path(id);
            return p.contains("crit") || p.contains("enchanted_hit") || p.contains("damage_indicator")
                    || p.contains("smoke") || p.equals("block");
        }
    }

    /* ------------------------------------------------------------------------------------------------ */

    /**
     * When stone or deepslate near you is broken (someone digging in), mines every spawner in range, stores them in
     * a nearby ender chest and disconnects.
     */
    public static final class SpawnerProtect extends Module implements BlockUpdateHook.Listener {
        private enum State {
            IDLE, GOING_TO_SPAWNERS, MINING_SPAWNER, GOING_TO_CHEST, OPENING_CHEST, DEPOSITING_ITEMS, DISCONNECTING,
            POSITION_DISPLACED, AWAITING_RECALIBRATION
        }

        private final Setting.Num breakDelay = num("Break Delay", 2, 0, 20, 1);
        private final Setting.Num rotationSpeed = num("Rotation Speed", 22.5, 1, 90, 0.5);
        private final Setting.Num depositDelay = num("Deposit Delay", 0, 0, 20, 1);

        private State state = State.AWAITING_RECALIBRATION;
        private final Set<BlockPos> spawners = new HashSet<>();
        private BlockPos targetSpawner, targetChest;
        private Vec3d basePosition;
        private boolean positionCalibrated, confirmingEmpty, miningStarted, attackPressed;
        private int ticks, emptyScanCount, chestOpenTicks, miningDelayTicks, depositDelayTicks, lastDepositSlot = -1;
        private int depositedCount, minedSpawnerCount;
        private double yawJitter, pitchJitter;

        public SpawnerProtect() {
            super("Spawner Protect", "Mines your spawners into an ender chest and disconnects when someone digs in", Category.DONUT);
        }

        @Override
        protected void onEnable() {
            resetState();
        }

        @Override
        protected void onDisable() {
            releaseInputs();
            spawners.clear();
        }

        @Override
        public String suffix() {
            return state == State.IDLE || state == State.AWAITING_RECALIBRATION ? "" : state.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }

        @Override
        public void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
            if (mc.player == null || mc.world == null || state != State.IDLE) return;
            if (newState.isAir() && oldState != null && (oldState.isOf(Blocks.STONE) || oldState.isOf(Blocks.DEEPSLATE))) {
                state = State.GOING_TO_SPAWNERS;
                setSneaking(true);
            }
        }

        @Override
        public void tick() {
            if (mc.player == null || mc.world == null) return;
            ticks++;
            Vec3d pos = mc.player.getEntityPos();
            scanSpawners();
            if (state == State.AWAITING_RECALIBRATION) {
                if (ticks >= 10) {
                    basePosition = pos;
                    positionCalibrated = true;
                    state = State.IDLE;
                }
                return;
            }
            if (!positionCalibrated || basePosition == null) {
                state = State.AWAITING_RECALIBRATION;
                ticks = 0;
                return;
            }
            double d = pos.distanceTo(basePosition);
            if (d > 5.0) {
                if (state == State.IDLE) state = State.POSITION_DISPLACED;
            } else if (state == State.POSITION_DISPLACED) {
                state = State.IDLE;
            }
            switch (state) {
                case GOING_TO_SPAWNERS, MINING_SPAWNER -> mineSpawner();
                case GOING_TO_CHEST -> walkToChest();
                case OPENING_CHEST -> openChest();
                case DEPOSITING_ITEMS -> depositSpawners();
                case DISCONNECTING -> disconnect();
                case POSITION_DISPLACED -> releaseInputs();
                default -> {
                }
            }
        }

        private void scanSpawners() {
            if (ticks % 20 != 0) return;
            spawners.clear();
            BlockPos c = mc.player.getBlockPos();
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int x = -50; x <= 50; x++)
                for (int y = -50; y <= 50; y++)
                    for (int z = -50; z <= 50; z++) {
                        m.set(c.getX() + x, c.getY() + y, c.getZ() + z);
                        if (mc.world.getBlockState(m).isOf(Blocks.SPAWNER)) spawners.add(m.toImmutable());
                    }
        }

        private BlockPos nearestSpawner() {
            BlockPos best = null;
            double bd = Double.MAX_VALUE;
            Vec3d p = mc.player.getEntityPos();
            for (BlockPos b : spawners) {
                double d = b.getSquaredDistance(p);
                if (d < bd) {
                    bd = d;
                    best = b;
                }
            }
            return best;
        }

        private void mineSpawner() {
            if (!miningStarted && targetSpawner == null) {
                targetSpawner = nearestSpawner();
                if (targetSpawner == null && !confirmingEmpty) {
                    confirmingEmpty = true;
                    emptyScanCount = 0;
                } else if (targetSpawner != null) {
                    randomizeAim();
                    state = State.GOING_TO_SPAWNERS;
                }
            } else if (targetSpawner != null) {
                double d = mc.player.getEntityPos().distanceTo(Vec3d.ofCenter(targetSpawner));
                if (d > 4.5 && state == State.GOING_TO_SPAWNERS) {
                    walkToward(targetSpawner);
                } else {
                    if (state == State.GOING_TO_SPAWNERS) {
                        state = State.MINING_SPAWNER;
                        miningDelayTicks = 0;
                        stopWalking();
                    }
                    if (state == State.MINING_SPAWNER && !miningStarted) {
                        rotateToward(targetSpawner);
                        if (miningDelayTicks < breakDelay.i()) {
                            miningDelayTicks++;
                        } else {
                            stopMining();
                            miningStarted = true;
                            mc.interactionManager.attackBlock(targetSpawner, Direction.UP);
                        }
                    }
                }
            }
            if (state == State.MINING_SPAWNER && miningStarted && targetSpawner != null) {
                rotateToward(targetSpawner);
                startMining(targetSpawner);
                if (mc.world.getBlockState(targetSpawner).isAir()) {
                    minedSpawnerCount++;
                    randomizeAim();
                    stopMining();
                    miningStarted = false;
                    targetSpawner = null;
                    state = State.GOING_TO_SPAWNERS;
                }
            }
            if (confirmingEmpty) {
                BlockPos again = nearestSpawner();
                if (again != null) {
                    confirmingEmpty = false;
                    targetSpawner = again;
                    state = State.GOING_TO_SPAWNERS;
                } else if (++emptyScanCount >= 5) {
                    stopMining();
                    miningStarted = false;
                    setSneaking(false);
                    randomizeAim();
                    state = State.GOING_TO_CHEST;
                }
            }
        }

        private void walkToChest() {
            if (targetChest == null) {
                targetChest = nearestEnderChest();
                if (targetChest == null) {
                    state = State.DISCONNECTING;
                    return;
                }
            }
            walkToward(targetChest);
            if (mc.player.getBlockPos().getSquaredDistance(targetChest) <= 9.0) {
                state = State.OPENING_CHEST;
                chestOpenTicks = 0;
                stopWalking();
            }
        }

        private BlockPos nearestEnderChest() {
            BlockPos c = mc.player.getBlockPos();
            Vec3d p = mc.player.getEntityPos();
            BlockPos best = null;
            double bd = Double.MAX_VALUE;
            for (int x = -16; x <= 16; x++)
                for (int y = -8; y <= 8; y++)
                    for (int z = -16; z <= 16; z++) {
                        BlockPos b = c.add(x, y, z);
                        if (!mc.world.getBlockState(b).isOf(Blocks.ENDER_CHEST)) continue;
                        double d = b.getSquaredDistance(p);
                        if (d < bd) {
                            bd = d;
                            best = b.toImmutable();
                        }
                    }
            return best;
        }

        private void openChest() {
            if (targetChest == null) {
                state = State.GOING_TO_CHEST;
                return;
            }
            rotateToward(targetChest);
            if (isAimingAt(targetChest)) {
                if (chestOpenTicks % 5 == 0)
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(targetChest), Direction.UP, targetChest, false));
                chestOpenTicks++;
            }
            if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
                state = State.DEPOSITING_ITEMS;
                lastDepositSlot = -1;
                depositDelayTicks = 0;
            }
            if (chestOpenTicks > 200) state = State.DISCONNECTING;
        }

        private void depositSpawners() {
            if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler h)) {
                state = State.OPENING_CHEST;
                chestOpenTicks = 0;
                return;
            }
            if (!hasSpawners()) {
                mc.player.closeHandledScreen();
                state = State.DISCONNECTING;
                return;
            }
            if (depositDelayTicks > 0) {
                depositDelayTicks--;
                return;
            }
            int total = h.slots.size();
            int first = total - 36;
            int start = Math.max(lastDepositSlot + 1, first);
            for (int l = 0; l < 36; l++) {
                int idx = first + (start - first + l) % 36;
                ItemStack st = h.getSlot(idx).getStack();
                if (st.isOf(Items.SPAWNER)) {
                    depositedCount += st.getCount();
                    mc.interactionManager.clickSlot(h.syncId, idx, 0, SlotActionType.QUICK_MOVE, mc.player);
                    lastDepositSlot = idx;
                    depositDelayTicks = depositDelay.i();
                    return;
                }
            }
            if (lastDepositSlot >= first) lastDepositSlot = first - 1;
            depositDelayTicks = depositDelay.i();
        }

        private boolean hasSpawners() {
            for (int i = 0; i < 36; i++) if (mc.player.getInventory().getStack(i).isOf(Items.SPAWNER)) return true;
            return false;
        }

        private void disconnect() {
            releaseInputs();
            int n = depositedCount > 0 ? depositedCount : minedSpawnerCount;
            if (mc.getNetworkHandler() != null)
                mc.getNetworkHandler().getConnection().disconnect(Text.literal(n + " Spawners Protected!"));
            resetState();
            mc.execute(() -> setEnabled(false));
        }

        private void resetState() {
            state = State.AWAITING_RECALIBRATION;
            ticks = 0;
            lastDepositSlot = -1;
            setSneaking(false);
            targetSpawner = null;
            targetChest = null;
            emptyScanCount = 0;
            confirmingEmpty = false;
            miningStarted = false;
            chestOpenTicks = 0;
            miningDelayTicks = 0;
            depositDelayTicks = 0;
            attackPressed = false;
            depositedCount = 0;
            minedSpawnerCount = 0;
            yawJitter = 0;
            pitchJitter = 0;
            positionCalibrated = false;
            basePosition = null;
            spawners.clear();
            releaseInputs();
        }

        private void randomizeAim() {
            yawJitter = (Math.random() * 2.0 - 1.0) * 1.5;
            pitchJitter = (Math.random() * 2.0 - 1.0) * 1.5;
        }

        private boolean isAimingAt(BlockPos b) {
            Vec3d d = Vec3d.ofCenter(b).subtract(mc.player.getEyePos()).normalize();
            double yaw = Math.toDegrees(Math.atan2(-d.x, d.z)) + yawJitter;
            double pitch = Math.toDegrees(-Math.asin(d.y)) + pitchJitter;
            float dy = MathHelper.wrapDegrees((float) (yaw - mc.player.getYaw()));
            float dp = (float) (pitch - mc.player.getPitch());
            return Math.abs(dy) < 1.0 && Math.abs(dp) < 1.0;
        }

        private void rotateToward(BlockPos b) {
            Vec3d d = Vec3d.ofCenter(b).subtract(mc.player.getEyePos()).normalize();
            double yaw = Math.toDegrees(Math.atan2(-d.x, d.z)) + yawJitter;
            double pitch = Math.toDegrees(-Math.asin(d.y)) + pitchJitter;
            float cy = MathHelper.wrapDegrees(mc.player.getYaw());
            float cp = mc.player.getPitch();
            float dy = MathHelper.wrapDegrees((float) (yaw - cy));
            float dp = (float) (pitch - cp);
            float sp = rotationSpeed.f();
            mc.player.setYaw(cy + MathHelper.clamp(dy, -sp, sp));
            mc.player.setPitch(cp + MathHelper.clamp(dp, -sp, sp));
        }

        private void walkToward(BlockPos b) {
            Vec3d d = Vec3d.ofCenter(b).subtract(mc.player.getEntityPos()).normalize();
            mc.player.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
            mc.options.forwardKey.setPressed(true);
        }

        private void startMining(BlockPos b) {
            if (attackPressed) return;
            mc.options.attackKey.setPressed(true);
            attackPressed = true;
            mc.interactionManager.updateBlockBreakingProgress(b, Direction.UP);
        }

        private void stopMining() {
            if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
            if (attackPressed && mc.options != null) {
                mc.options.attackKey.setPressed(false);
                attackPressed = false;
            }
        }

        private void setSneaking(boolean on) {
            if (mc.options != null) mc.options.sneakKey.setPressed(on);
        }

        private void stopWalking() {
            if (mc.options == null) return;
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
        }

        private void releaseInputs() {
            if (mc.options == null) return;
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            mc.options.attackKey.setPressed(false);
        }
    }
}

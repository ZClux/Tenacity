package tenacity.modules.donut;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PropaguleBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.Fluids;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.ChunkLightProvider;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import voided.util.render.Render3D;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;
import tenacity.util.BlockUpdateHook;
import tenacity.util.PacketHook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sus Chunk Finder. Two detections ("Detection" setting): <b>Crypto</b> (default, a port of Crypto's SusChunkFinder: per chunk
 * not-grown counts spread as heat over the simulation distance, render-time candidate filter, Smart Adjustment) and
 * <b>Sober</b> (the pipeline described below). Both feed the same {@code Sus} verdict, Mode, Keep Time latch and ChunkCards.
 *
 * Sober pipeline: detection is a port of Sober's SusChunk (with Crypto's extras), the verdict and the visuals are ours.
 *
 * <h3>Idea</h3>
 * On DonutSMP (brand "DonutFolia") chunks that no player has been near are never ticked, so natural growth stays
 * "owed": kelp that is not fully grown under water, bamboo that has not reached its height, cocoa that is not ripe,
 * cave vines, vines, amethyst buds, grass that should have spread, snow and ice that should have formed, eyeblossoms in
 * the wrong state... A chunk with growth that has clearly happened was ticked, so a player (or a loaded base) was within
 * simulation distance. Such a chunk "clears" every chunk within {@code simDistance}. A hole of un-ticked chunks that is
 * enclosed by cleared ones is where somebody stood while nobody was near it: very likely a base.
 *
 * <h3>Pipeline (Sober)</h3>
 * chunk packet -> background scan -> Record(clears, radius, hole, weight). {@code clearCoverage} counts how many clearing
 * chunks reach a chunk, {@code support} spreads the growth weight over {@code 2*simDist+2}. A chunk is flagged when it
 * was scanned, is loaded, has no coverage, passes the hole filter, {@code support >= sensitivity} and at least three of
 * its four sides have cleared chunks within reach ({@code coveredSides}).
 *
 * <h3>Crypto extras</h3>
 * Bee nests with full honey, rotated deepslate buried in stone and amethyst touching stone (Folia) are extra evidence
 * that is added to a chunk's score. Smart Adjustment thins huge candidate clusters (straight lines, peak chunks).
 *
 * <h3>Chance model (not a toy formula)</h3>
 * A logistic score {@code z = b0 + sum(w_i * f_i)} is mapped through {@code p = 1 / (1 + e^-z)} and clamped to 1..99%.
 * It is a hand-calibrated naive-Bayes style log-odds sum: every feature adds the log likelihood ratio
 * {@code ln P(f | base) / P(f | wilderness)} that a flagged chunk with this feature is a base. The weights are listed
 * next to the feature below and every contribution is shown as a reason line in the hover panel. Features:
 * covered sides (0..4), how deep in the hole the chunk is (distance to the nearest clearing chunk), support margin over
 * the sensitivity, cluster size and shape (bases are 1..4 chunks, big uniform regions are unvisited wilderness),
 * hole filter hits (player dug shafts and tunnels), bee nest / rotated deepslate / amethyst contacts, being near the
 * world spawn (spawn chunks are always loaded) and sitting on the loaded edge (half known neighbours).
 */
public final class SusChunkFinder extends Module implements PacketHook.Receiver, BlockUpdateHook.Listener {
    private static final String DONUT_BRAND = "DonutFolia";

    /* evidence kinds, same order as Sober's */
    private static final int KELP = 0, CAVE_VINES = 1, WEEPING_VINES = 2, BAMBOO = 3, SUGAR_CANE = 4, CACTUS = 5, COCOA = 6, BERRY = 7,
            NETHER_WART = 8, CROP = 9, STEM = 10, AMETHYST = 11, VINE = 12, LEAVES = 13, SOIL = 14, PROPAGULE = 15, EYEBLOSSOM = 16,
            SNOW = 17, ICE = 18, BEE_NEST = 19, DEEPSLATE = 20;
    private static final int KINDS = 21;
    private static final String[] KIND_NAMES = {"Kelp", "Cave Vines", "Weeping Vines", "Bamboo", "Sugar Cane", "Cactus", "Cocoa", "Sweet Berries",
            "Nether Wart", "Crops", "Stems", "Amethyst", "Vines", "Leaves", "Grass & Dirt", "Mangrove Propagules", "Eyeblossoms", "Snow", "Ice",
            "Bee Nests", "Rotated Deepslate"};

    private static final int GROWTH_LIGHT = 9, MELT_LIGHT = 10, BAMBOO_LIMIT = 16, KELP_MAX_AGE = 25;
    private static final int EYEBLOSSOM_OPENS = 12600, EYEBLOSSOM_CLOSES = 23401, EYEBLOSSOM_SETTLE = 4000, EYEBLOSSOM_MIN = 3;
    private static final int DEEPSLATE_MIN_Y = -8, DEEPSLATE_MAX_Y = 64, DEEPSLATE_SOLID_SIDES = 5;
    private static final int FOLIA_AMETHYST_CONTACTS_MIN = 5;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final Direction[] DIRECTIONS = Direction.values();

    /* detection */
    private final Setting.Num simDistance = num("Simulation Distance", 4, 2, 16, 1);
    private final Setting.Num soberSupport = num("Support Needed", 40, 1, 500, 1).showIf(() -> sober());
    private final Setting.Num cryptoSensitivity = num("Sensitivity", 2, 1, 20, 1).showIf(() -> !sober());
    private final Setting.Bool holeFilter = bool("Hole Filter", false).showIf(() -> sober());
    private final Setting.Num holeDistance = num("Hole Distance", 2, 0, 8, 1).showIf(() -> sober() && holeFilter.on());
    private final Setting.Bool customEvidence = bool("Custom Flags", false).showIf(() -> false); // removed: every kind always counts
    private final Setting.Bool[] evidence = new Setting.Bool[KINDS];
    private final Setting.Bool cKelp = bool("Kelp", true).showIf(() -> !sober());
    private final Setting.Bool cCaveVines = bool("Cave Vines", true).showIf(() -> !sober());
    private final Setting.Bool cVines = bool("Vines", true).showIf(() -> !sober());
    private final Setting.Bool cCocoa = bool("Cocoa", true).showIf(() -> !sober());
    private final Setting.Bool cAmethyst = bool("Amethyst", true).showIf(() -> !sober());
    private final Setting.Bool cBamboo = bool("Bamboo", true).showIf(() -> !sober());
    private final Setting.Bool beeNests = bool("Bee Nest", true);
    private final Setting.Bool deepslate = bool("Rotated Deepslate", true);
    private final Setting.Bool smart = bool("Smart Thinning", true);
    private final Setting.Bool growArea = bool("Grow Area", true);

    /* verdict */
    private final Setting.Mode mode = mode("Mode", "Spread", "Spread", "Single Chunk", "Auto");
    private final Setting.Num minChance = num("Min Chance", 0, 0, 99, 1);
    private final Setting.Bool notify = bool("Notify", true);

    /* look */
    private final Setting.Bool followHeight = bool("Follow My Height", false);
    private final Setting.Num height = num("Height", 63, -64, 320, 1).showIf(() -> !followHeight.on());
    private final Setting.Num renderDistance = num("Render Distance", 512, 16, 2048, 1);
    private final Setting.Bool animations = bool("Animations", true);
    private final Setting.Bool tracers = bool("Tracers", true);
    private final Setting.Bool labels = bool("Labels", true);
    private final Setting.Num keepTime = num("Keep Time", 900, 0, 3600, 30);
    private final boolean list = false; // found list removed
    private final Setting.Mode cardStyle = mode("Style", "Frost", "Frost", "Liquid Glass", "Flat");
    private final Setting.Color tint = color("Tint", ChunkCards.FROST);
    private final Setting.Num opacity = num("Opacity", 1, 0.1, 1, 0.05);

    private record Record(boolean clears, int radius, boolean hole, int weight, int[] kinds) {
    }

    private record Source(boolean clears, boolean hole, int weight, int[] kinds) {
    }

    /** One chunk of the verdict. */
    private static final class Sus {
        int cx, cz;
        double support, margin, score;
        int sides, depth, clusterSize, extra;
        boolean hole, nearSpawn, edge, shaped, crypto;
        int[] cryptoKinds = new int[7];
        int intelEnt, intelBe, intelEv, intelY = Integer.MAX_VALUE;
        double chance;
        List<String> reasons = List.of();
        long born;
        int[] kinds = new int[KINDS];
    }

    private final Object lock = new Object();
    private final Long2ObjectOpenHashMap<Record> records = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap clearCoverage = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap support = new Long2IntOpenHashMap();
    private final LongOpenHashSet scanned = new LongOpenHashSet();
    private final LongArrayList distant = new LongArrayList();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final Set<Long> kelpChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> seenKelp = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Boolean> packetQueue = new ConcurrentHashMap<>();
    private volatile List<Sus> flagged = List.of();
    private final java.util.Map<Long, Long> bornAt = new java.util.HashMap<>();
    private final java.util.Set<Long> announced = new java.util.HashSet<>();
    private volatile int generation;
    private volatile boolean dirty;
    private volatile boolean[] counted = new boolean[KINDS];
    private ExecutorService executor;
    private RegistryKey<World> worldKey;
    private int tickCounter;
    private final ChunkCards cards = new ChunkCards();
    private final ChunkCards beeCards = new ChunkCards();

    /** Dev aid: fake detections for screenshots (TENACITY_DEV=sus). */
    public static boolean devFake;

    public SusChunkFinder() {
        super("Sus Chunk Finder", "Marks chunks no player has been near: areas with overdue natural growth rule themselves out", Category.BASE);
        for (int i = 0; i < 19; i++) evidence[i] = bool("Sober " + KIND_NAMES[i], true).showIf(() -> false); // unique names: "Kelp", "Amethyst"... collided with the Crypto toggles in the saved config
        evidence[BEE_NEST] = beeNests;
        evidence[DEEPSLATE] = deepslate;
        refreshEvidence();
    }

    private boolean sober() {
        return false; // one detection only
    }

    private void refreshEvidence() {
        boolean custom = false;
        boolean[] next = new boolean[KINDS];
        for (int i = 0; i < KINDS; i++) next[i] = i >= 19 ? evidence[i].on() : !custom || evidence[i].on();
        counted = next;
    }

    /* ------------------------------------------------------------------ lifecycle */

    @Override
    protected void onEnable() {
        resetState();
        executor = Executors.newSingleThreadExecutor(task -> {
            Thread t = new Thread(task, "Tenacity SusChunks");
            t.setDaemon(true);
            return t;
        });
        worldKey = mc.world == null ? null : mc.world.getRegistryKey();
        dirty = false;
        rescanLoadedChunks();
    }

    @Override
    protected void onDisable() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        resetState();
        worldKey = null;
        cards.clear();
        beeCards.clear();
    }

    private void resetState() {
        latched.clear();
        latchedAt.clear();
        synchronized (lock) {
            generation++;
            clearCoverage.clear();
            support.clear();
            records.clear();
            scanned.clear();
            pending.clear();
            kelpChunks.clear();
            seenKelp.clear();
        }
        packetQueue.clear();
        cryptoClear();
        lightClear();
        flagged = List.of();
        bornAt.clear();
        announced.clear();
        tickCounter = 0;
    }

    @Override
    public String suffix() {
        int n = flagged.size();
        return n == 0 ? "" : Integer.toString(n);
    }

    private void rescanLoadedChunks() {
        if (!enabled() || mc.player == null || mc.world == null) return;
        resetState();
        int radius = mc.options.getClampedViewDistance() + (sober() ? 3 : 2);
        ChunkPos c = mc.player.getChunkPos();
        for (int x = c.x - radius; x <= c.x + radius; x++)
            for (int z = c.z - radius; z <= c.z + radius; z++)
                if (mc.world.isChunkLoaded(x, z)) scheduleScan(ChunkPos.toLong(x, z));
    }

    private void scheduleScan(long key) {
        ExecutorService worker = executor;
        if (worker != null && !worker.isShutdown() && pending.add(key)) {
            int expected = generation;
            try {
                worker.submit(() -> runScan(key, expected));
            } catch (Exception e) {
                pending.remove(key);
            }
        }
    }

    private void runScan(long key, int expected) {
        pending.remove(key);
        if (expected != generation || !enabled()) return;
        ClientWorld level = mc.world;
        if (level == null) return;
        int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
        if (!level.isChunkLoaded(cx, cz)) return;
        WorldChunk chunk = level.getChunkManager().getWorldChunk(cx, cz);
        if (chunk == null) return;
        if (sober()) rescan(level, key, chunk, expected);
        else cryptoAnalyze(level, key, chunk, expected);
    }

    private void rescan(ClientWorld level, long key, WorldChunk chunk, int expected) {
        Record old = removeRecord(key);
        Source fresh;
        try {
            fresh = scanChunk(level, key, chunk);
        } catch (Throwable t) {
            return;
        }
        synchronized (lock) {
            if (expected != generation) return;
            int[] kinds = fresh.kinds();
            if (old != null) for (int i = 0; i < KINDS; i++) kinds[i] = Math.max(kinds[i], old.kinds()[i]);
            Record merged = new Record(fresh.clears() || old != null && old.clears(), simDistance.i(),
                    fresh.hole() || old != null && old.hole(), Math.max(fresh.weight(), old == null ? 0 : old.weight()), kinds);
            scanned.add(key);
            records.put(key, merged);
            if (merged.clears()) {
                int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
                int reach = 2 * merged.radius() + 2;
                for (int dx = -merged.radius(); dx <= merged.radius(); dx++)
                    for (int dz = -merged.radius(); dz <= merged.radius(); dz++) clearCoverage.addTo(ChunkPos.toLong(cx + dx, cz + dz), 1);
                for (int dx = -reach; dx <= reach; dx++)
                    for (int dz = -reach; dz <= reach; dz++) support.addTo(ChunkPos.toLong(cx + dx, cz + dz), merged.weight());
            }
        }
    }

    private Record removeRecord(long key) {
        synchronized (lock) {
            Record old = records.remove(key);
            scanned.remove(key);
            if (old != null && old.clears()) {
                int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
                int reach = 2 * old.radius() + 2;
                for (int dx = -old.radius(); dx <= old.radius(); dx++)
                    for (int dz = -old.radius(); dz <= old.radius(); dz++) subtract(clearCoverage, ChunkPos.toLong(cx + dx, cz + dz), 1);
                for (int dx = -reach; dx <= reach; dx++)
                    for (int dz = -reach; dz <= reach; dz++) subtract(support, ChunkPos.toLong(cx + dx, cz + dz), old.weight());
            }
            return old;
        }
    }

    private static void subtract(Long2IntOpenHashMap map, long key, int amount) {
        if (!map.containsKey(key)) return;
        int v = map.get(key);
        if (v <= amount) map.remove(key);
        else map.put(key, v - amount);
    }

    /* ------------------------------------------------------------------ scan */

    private Source scanChunk(ClientWorld level, long key, WorldChunk chunk) {
        boolean donut = donutServer();
        boolean[] counts = counted;
        int[] kinds = new int[KINDS];
        int count = 0;
        if (donut && counts[KELP] && kelpChunks.contains(key)) {
            count++;
            kinds[KELP]++;
        }
        Reader blocks = new Reader(level);
        ChunkPos pos = chunk.getPos();
        int minX = pos.getStartX(), minZ = pos.getStartZ();
        Boolean bloomsOpen = eyeblossomsOpen(level);
        int lateBlooms = 0, settledBlooms = 0;
        ChunkSection[] sections = chunk.getSectionArray();
        int amethystContacts = donut && counts[AMETHYST] ? amethystStoneContacts(level, chunk) : 0;

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty() || !section.hasAny(st -> kindOf(st, counts) >= 0)) continue;
            int baseY = chunk.getBottomY() + (i << 4);
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        int kind = kindOf(state, counts);
                        if (kind < 0) continue;
                        if (kind == PROPAGULE) {
                            if (state.get(PropaguleBlock.AGE) < 4) {
                                count++;
                                kinds[kind]++;
                            }
                        } else if (kind == EYEBLOSSOM) {
                            if (bloomsOpen != null) {
                                if (state.isOf(Blocks.OPEN_EYEBLOSSOM) == bloomsOpen) settledBlooms++;
                                else lateBlooms++;
                            }
                        } else if (kind == BEE_NEST) {
                            if (state.contains(Properties.HONEY_LEVEL) && state.get(Properties.HONEY_LEVEL) == 5) {
                                count += 2;
                                kinds[kind]++;
                            }
                        } else if (kind == DEEPSLATE) {
                            int by = baseY + y;
                            if (by >= DEEPSLATE_MIN_Y && by <= DEEPSLATE_MAX_Y && rotatedDeepslateBuried(blocks, minX + x, by, minZ + z)) {
                                count++;
                                kinds[kind]++;
                            }
                        } else if (owed(blocks, minX + x, baseY + y, minZ + z, state, kind, donut)) {
                            count++;
                            kinds[kind]++;
                        }
                    }
        }
        if (settledBlooms == 0 && lateBlooms >= EYEBLOSSOM_MIN) {
            count += lateBlooms;
            kinds[EYEBLOSSOM] += lateBlooms;
        }
        if (amethystContacts >= FOLIA_AMETHYST_CONTACTS_MIN) {
            count++;
            kinds[AMETHYST]++;
        }
        if ((counts[SNOW] || counts[ICE]) && chunk.hasHeightmap(Heightmap.Type.MOTION_BLOCKING)) {
            Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING);
            int sea = level.getSeaLevel();
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int y = heightmap.get(x, z);
                    boolean snow = counts[SNOW] && (!donut || !blocks.state(minX + x, y - 1, minZ + z).isOf(Blocks.DEEPSLATE)) && snowOwed(level, blocks, minX + x, y, minZ + z, sea);
                    boolean ice = !snow && counts[ICE] && iceOwed(level, blocks, minX + x, y, minZ + z, sea);
                    if (snow) { count++; kinds[SNOW]++; }
                    else if (ice) { count++; kinds[ICE]++; }
                }
        }
        boolean hole = holeFilter.on() && hasHole(level, pos);
        return new Source(count > 0, hole, count, kinds);
    }

    private static int kindOf(BlockState state, boolean[] counts) {
        int kind = classify(state);
        return kind >= 0 && counts[kind] ? kind : -1;
    }

    private static int classify(BlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.KELP) return KELP;
        if (b == Blocks.CAVE_VINES) return CAVE_VINES;
        if (b == Blocks.WEEPING_VINES) return WEEPING_VINES;
        if (b == Blocks.BAMBOO) return BAMBOO;
        if (b == Blocks.SUGAR_CANE) return SUGAR_CANE;
        if (b == Blocks.CACTUS) return CACTUS;
        if (b == Blocks.COCOA) return COCOA;
        if (b == Blocks.SWEET_BERRY_BUSH) return BERRY;
        if (b == Blocks.NETHER_WART) return NETHER_WART;
        if (b == Blocks.PUMPKIN_STEM || b == Blocks.MELON_STEM) return STEM;
        if (b == Blocks.VINE) return VINE;
        if (b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_CLUSTER || isBud(state)) return AMETHYST;
        if (b == Blocks.MANGROVE_PROPAGULE) return state.get(PropaguleBlock.HANGING) ? PROPAGULE : -1;
        if (b == Blocks.OPEN_EYEBLOSSOM || b == Blocks.CLOSED_EYEBLOSSOM) return EYEBLOSSOM;
        if (b == Blocks.BEE_NEST) return BEE_NEST;
        if (b == Blocks.DEEPSLATE && state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y) return DEEPSLATE;
        if (b instanceof CropBlock) return CROP;
        if (b instanceof LeavesBlock) return LEAVES;
        return isGrass(state) || state.isOf(Blocks.DIRT) ? SOIL : -1;
    }

    /** Overworld eyeblossoms are settled 4000 ticks after dusk / dawn; null when the time says nothing. */
    private static Boolean eyeblossomsOpen(ClientWorld level) {
        if (level.getRegistryKey() != World.OVERWORLD || level.getDimension().hasFixedTime()) return null;
        int time = Math.floorMod(level.getTimeOfDay(), 24000);
        boolean open = time >= EYEBLOSSOM_OPENS && time < EYEBLOSSOM_CLOSES;
        int since = open ? time - EYEBLOSSOM_OPENS : Math.floorMod(time - EYEBLOSSOM_CLOSES, 24000);
        return since >= EYEBLOSSOM_SETTLE ? open : null;
    }

    private static boolean owed(Reader blocks, int x, int y, int z, BlockState state, int kind, boolean donut) {
        if (kind == SOIL) return soilOwed(blocks, x, y, z, state);
        if (donut) {
            // Folia keeps kelp growing only in loaded water and ignores cave vines; only exposed amethyst buds count
            if (kind == KELP) return blocks.state(x, y + 1, z).isOf(Blocks.WATER) && column(blocks, x, y, z, Blocks.KELP_PLANT, 3) < 3;
            if (kind == AMETHYST) return isBud(state);
            if (kind == CAVE_VINES) return false;
        }
        return growthOwed(blocks, x, y, z, state);
    }

    private static boolean growthOwed(Reader blocks, int x, int y, int z, BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.KELP) return state.get(Properties.AGE_25) < KELP_MAX_AGE && blocks.state(x, y + 1, z).isOf(Blocks.WATER);
        if (block == Blocks.CAVE_VINES || block == Blocks.WEEPING_VINES) return state.get(Properties.AGE_25) < KELP_MAX_AGE && blocks.state(x, y - 1, z).isAir();
        if (block == Blocks.BAMBOO)
            return state.get(Properties.STAGE) == 0 && blocks.state(x, y + 1, z).isAir() && bambooBelow(blocks, x, y, z) + 1 < BAMBOO_LIMIT && blocks.light(x, y + 1, z) >= GROWTH_LIGHT;
        if (block == Blocks.SUGAR_CANE) return blocks.state(x, y + 1, z).isAir() && column(blocks, x, y, z, Blocks.SUGAR_CANE, 3) < 3;
        if (block == Blocks.CACTUS)
            return blocks.state(x, y + 1, z).isAir() && (state.get(Properties.AGE_15) < 15 || column(blocks, x, y, z, Blocks.CACTUS, 3) < 3);
        if (block == Blocks.COCOA) return state.get(Properties.AGE_2) < 2;
        if (block == Blocks.NETHER_WART) return state.get(Properties.AGE_3) < 3;
        if (block == Blocks.SWEET_BERRY_BUSH) return state.get(Properties.AGE_3) < 3 && blocks.light(x, y + 1, z) >= GROWTH_LIGHT;
        if (block == Blocks.PUMPKIN_STEM || block == Blocks.MELON_STEM) return state.get(Properties.AGE_7) < 7 && blocks.light(x, y, z) >= GROWTH_LIGHT;
        if (block instanceof CropBlock crop) return !crop.isMature(state) && blocks.light(x, y, z) >= GROWTH_LIGHT;
        if (block == Blocks.VINE)
            return blocks.state(x, y - 1, z).isAir() && (state.get(Properties.NORTH) || state.get(Properties.EAST) || state.get(Properties.SOUTH) || state.get(Properties.WEST));
        if (block instanceof LeavesBlock)
            return state.contains(Properties.DISTANCE_1_7) && state.contains(Properties.PERSISTENT) && state.get(Properties.DISTANCE_1_7) == 7 && !state.get(Properties.PERSISTENT);
        return amethystOwed(blocks, x, y, z, state);
    }

    /** Budding amethyst with a free face should have grown a bud; a bud whose parent is gone is a stale one. */
    private static boolean amethystOwed(Reader blocks, int x, int y, int z, BlockState state) {
        if (state.isOf(Blocks.BUDDING_AMETHYST)) {
            for (Direction d : DIRECTIONS) {
                BlockState near = blocks.state(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ());
                if (near.isAir()) return true;
                if (near.isOf(Blocks.WATER) && near.getFluidState().getLevel() == 8) return true;
            }
            return false;
        }
        if (isBud(state) && state.contains(Properties.FACING)) {
            Direction f = state.get(Properties.FACING);
            return blocks.state(x - f.getOffsetX(), y - f.getOffsetY(), z - f.getOffsetZ()).isOf(Blocks.BUDDING_AMETHYST);
        }
        return false;
    }

    private static int column(Reader blocks, int x, int y, int z, Block target, int limit) {
        int len = 1;
        while (len < limit && blocks.state(x, y - len, z).isOf(target)) len++;
        return len;
    }

    private static int bambooBelow(Reader blocks, int x, int y, int z) {
        int below = 0;
        while (below < BAMBOO_LIMIT && blocks.state(x, y - below - 1, z).isOf(Blocks.BAMBOO)) below++;
        return below;
    }

    /** Crypto: rotated (non Y axis) deepslate that is buried in solid blocks is placed by a player, worldgen never rotates it. */
    private static boolean rotatedDeepslateBuried(Reader blocks, int x, int y, int z) {
        int solid = 0;
        for (Direction d : DIRECTIONS) if (!blocks.state(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ()).isAir()) solid++;
        return solid >= DEEPSLATE_SOLID_SIDES;
    }

    /** Crypto (Folia): stone that touches amethyst blocks, counted over the 3x3 chunks around. */
    private int amethystStoneContacts(ClientWorld level, WorldChunk chunk) {
        Set<Long> amethystSections = new java.util.HashSet<>();
        ChunkPos center = chunk.getPos();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int cx = center.x + dx, cz = center.z + dz;
                if (!level.isChunkLoaded(cx, cz)) continue;
                WorldChunk near = level.getChunkManager().getWorldChunk(cx, cz);
                if (near == null) continue;
                ChunkSection[] secs = near.getSectionArray();
                int bottom = near.getBottomY() >> 4;
                for (int i = 0; i < secs.length; i++)
                    if (secs[i] != null && !secs[i].isEmpty() && secs[i].hasAny(SusChunkFinder::isAmethystBlock)) amethystSections.add(ChunkSectionPos.asLong(cx, bottom + i, cz));
            }
        if (amethystSections.isEmpty()) return 0;
        Set<Long> contacts = new java.util.HashSet<>();
        ChunkSection[] secs = chunk.getSectionArray();
        int bottom = chunk.getBottomY() >> 4;
        BlockPos.Mutable n = new BlockPos.Mutable();
        for (int i = 0; i < secs.length; i++) {
            ChunkSection s = secs[i];
            int sy = bottom + i;
            if (s == null || s.isEmpty() || !s.hasAny(st -> st.isOf(Blocks.STONE))) continue;
            if (!touches(center.x, sy, center.z, amethystSections)) continue;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        if (!s.getBlockState(x, y, z).isOf(Blocks.STONE)) continue;
                        int bx = center.getStartX() + x, by = (sy << 4) + y, bz = center.getStartZ() + z;
                        for (Direction d : DIRECTIONS) {
                            n.set(bx + d.getOffsetX(), by + d.getOffsetY(), bz + d.getOffsetZ());
                            if (isAmethystBlock(level.getBlockState(n))) {
                                contacts.add(BlockPos.asLong(bx, by, bz));
                                break;
                            }
                        }
                    }
        }
        return contacts.size();
    }

    private static boolean touches(int x, int y, int z, Set<Long> secs) {
        if (secs.contains(ChunkSectionPos.asLong(x, y, z))) return true;
        for (Direction d : DIRECTIONS) if (secs.contains(ChunkSectionPos.asLong(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ()))) return true;
        return false;
    }

    private static boolean isAmethystBlock(BlockState s) {
        return s.isOf(Blocks.AMETHYST_BLOCK) || s.isOf(Blocks.BUDDING_AMETHYST) || s.isOf(Blocks.AMETHYST_CLUSTER) || isBud(s);
    }

    private static boolean soilOwed(Reader blocks, int x, int y, int z, BlockState state) {
        BlockState upper = blocks.state(x, y + 1, z);
        if (isGrass(state)) return smothers(upper);
        if (state.isOf(Blocks.DIRT) && !smothers(upper) && !upper.getFluidState().isIn(FluidTags.WATER)) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    for (int ny = y - 1; ny <= y + 3; ny++)
                        if ((dx != 0 || dz != 0 || ny != y) && isGrass(blocks.state(x + dx, ny, z + dz)) && !smothers(blocks.state(x + dx, ny + 1, z + dz))
                                && blocks.light(x + dx, ny + 1, z + dz) >= GROWTH_LIGHT) return true;
        }
        return false;
    }

    private static boolean smothers(BlockState upper) {
        if (upper.isOf(Blocks.SNOW) && upper.get(SnowBlock.LAYERS) == 1) return false;
        if (upper.getFluidState().getLevel() == 8) return true;
        return ChunkLightProvider.getRealisticOpacity(Blocks.GRASS_BLOCK.getDefaultState(), upper, Direction.UP, upper.getOpacity()) >= 15;
    }

    private static boolean snowOwed(ClientWorld level, Reader blocks, int x, int y, int z, int sea) {
        if (level.isOutOfHeightLimit(y) || !blocks.state(x, y, z).isAir()) return false;
        if (!holdsSnow(level, blocks.state(x, y - 1, z), new BlockPos(x, y - 1, z))) return false;
        BlockPos pos = new BlockPos(x, y, z);
        if (level.getBiome(pos).value().getPrecipitation(pos, sea) != Biome.Precipitation.SNOW) return false;
        return calmLight(level, blocks, x, y, z);
    }

    private static boolean holdsSnow(ClientWorld level, BlockState below, BlockPos pos) {
        if (below.isIn(BlockTags.SNOW_LAYER_CANNOT_SURVIVE_ON)) return false;
        if (below.isIn(BlockTags.SNOW_LAYER_CAN_SURVIVE_ON)) return true;
        if (below.isOf(Blocks.SNOW) && below.get(SnowBlock.LAYERS) == 8) return true;
        return Block.isFaceFullSquare(below.getCollisionShape(level, pos), Direction.UP);
    }

    private static boolean iceOwed(ClientWorld level, Reader blocks, int x, int y, int z, int sea) {
        int water = y - 1;
        if (level.isOutOfHeightLimit(water)) return false;
        BlockState state = blocks.state(x, water, z);
        if (!(state.getBlock() instanceof FluidBlock) || state.getFluidState().getFluid() != Fluids.WATER) return false;
        if (!level.isChunkLoaded(x - 1 >> 4, z >> 4) || !level.isChunkLoaded(x + 1 >> 4, z >> 4) || !level.isChunkLoaded(x >> 4, z - 1 >> 4) || !level.isChunkLoaded(x >> 4, z + 1 >> 4)) return false;
        if (isWater(blocks, x - 1, water, z) && isWater(blocks, x + 1, water, z) && isWater(blocks, x, water, z - 1) && isWater(blocks, x, water, z + 1)) return false;
        if (level.getBiome(new BlockPos(x, y, z)).value().doesNotSnow(new BlockPos(x, water, z), sea)) return false;
        return calmLight(level, blocks, x, water, z);
    }

    private static boolean isWater(Reader blocks, int x, int y, int z) {
        return blocks.state(x, y, z).getFluidState().isIn(FluidTags.WATER);
    }

    /** No strong light source within 5 blocks (those melt snow and ice) and every chunk around is loaded. */
    private static boolean calmLight(ClientWorld level, Reader blocks, int x, int y, int z) {
        int r = 5;
        for (int cx = x - r >> 4; cx <= x + r >> 4; cx++)
            for (int cz = z - r >> 4; cz <= z + r >> 4; cz++) if (!level.isChunkLoaded(cx, cz)) return false;
        for (int dx = -r; dx <= r; dx++) {
            int restY = r - Math.abs(dx);
            for (int dy = -restY; dy <= restY; dy++) {
                int restZ = restY - Math.abs(dy);
                for (int dz = -restZ; dz <= restZ; dz++) {
                    int em = blocks.state(x + dx, y + dy, z + dz).getLuminance();
                    if (em != 0 && em - Math.abs(dx) - Math.abs(dy) - Math.abs(dz) >= MELT_LIGHT) return false;
                }
            }
        }
        return true;
    }

    /* ------------------------------------------------------------------ hole filter (player dug shafts and tunnels) */

    private static boolean hasHole(ClientWorld level, ChunkPos pos) {
        Reader blocks = new Reader(level);
        int bottom = level.getBottomY(), top = bottom + level.getHeight();
        int minX = pos.getStartX(), minZ = pos.getStartZ();
        for (int x = minX; x < minX + 16; x++)
            for (int z = minZ; z < minZ + 16; z++) {
                int single = 0, alongX = 0, alongZ = 0;
                for (int y = bottom; y < top; y++) {
                    if (singleShaft(level, blocks, x, y, z)) single++;
                    else {
                        if (shaftEnds(level, blocks, x, y, z, single)) return true;
                        single = 0;
                    }
                    if (wideShaft(level, blocks, x, y, z, 1, 0)) alongX++;
                    else {
                        if (shaftEnds(level, blocks, x, y, z, alongX)) return true;
                        alongX = 0;
                    }
                    if (wideShaft(level, blocks, x, y, z, 0, 1)) alongZ++;
                    else {
                        if (shaftEnds(level, blocks, x, y, z, alongZ)) return true;
                        alongZ = 0;
                    }
                }
            }
        return false;
    }

    private static boolean singleShaft(ClientWorld l, Reader b, int x, int y, int z) {
        return open(l, b, x, y, z) && !open(l, b, x - 1, y, z) && !open(l, b, x + 1, y, z) && !open(l, b, x, y, z - 1) && !open(l, b, x, y, z + 1);
    }

    private static boolean wideShaft(ClientWorld l, Reader b, int x, int y, int z, int sx, int sz) {
        for (int i = 0; i < 3; i++) if (!open(l, b, x + sx * i, y, z + sz * i)) return false;
        if (open(l, b, x - sx, y, z - sz) || open(l, b, x + sx * 3, y, z + sz * 3)) return false;
        for (int i = 0; i < 3; i++) {
            int wx = x + sx * i, wz = z + sz * i;
            if (open(l, b, wx - sz, y, wz - sx) || open(l, b, wx + sz, y, wz + sx)) return false;
        }
        return true;
    }

    private static boolean shaftEnds(ClientWorld l, Reader b, int x, int y, int z, int run) {
        return run >= 2 && open(l, b, x, y, z) && !open(l, b, x, y - run - 1, z);
    }

    private static boolean open(ClientWorld l, Reader b, int x, int y, int z) {
        return b.state(x, y, z).getCollisionShape(l, BlockPos.ORIGIN).isEmpty();
    }

    private static boolean isBud(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isGrass(BlockState s) {
        return s.isOf(Blocks.GRASS_BLOCK) || s.isOf(Blocks.MYCELIUM);
    }

    private static boolean donutServer() {
        var h = MINECRAFT().getNetworkHandler();
        if (h == null) return false;
        String brand = h.getBrand();
        return brand != null && brand.contains(DONUT_BRAND);
    }

    private static net.minecraft.client.MinecraftClient MINECRAFT() {
        return net.minecraft.client.MinecraftClient.getInstance();
    }

    /* ------------------------------------------------------------------ events */

    @Override
    public void onPacket(Packet<?> packet) {
        if (packet instanceof ChunkDataS2CPacket c) {
            long key = ChunkPos.toLong(c.getChunkX(), c.getChunkZ());
            // the chunk is applied on the client thread right after; scan it a tick later
            mc.execute(() -> mc.execute(() -> scheduleScan(key)));
        } else if (packet instanceof GameJoinS2CPacket || packet instanceof PlayerRespawnS2CPacket) {
            mc.execute(() -> {
                resetState();
                worldKey = null;
            });
        }
    }

    @Override
    public void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
        amethystUpdate(pos, newState);
        if (!sober() || mc.world == null || !counted[KELP] || !newState.isOf(Blocks.KELP) || !donutServer()) return;
        int age = newState.get(Properties.AGE_25);
        if (seenKelp.add(pos.asLong()) && age > 0 && age < KELP_MAX_AGE && mc.world.getBlockState(pos.up()).isOf(Blocks.WATER)) {
            long key = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
            if (kelpChunks.add(key)) scheduleScan(key);
        }
    }

    @Override
    public void tick() {
        if (mc.world == null || mc.player == null) return;
        RegistryKey<World> dim = mc.world.getRegistryKey();
        if (dim != worldKey) {
            resetState();
            worldKey = dim;
        }
        if (dirty) {
            dirty = false;
            rescanLoadedChunks();
        }
        refreshSettingsState();
        if (!sober() && !devFake) cryptoSweepTick();
        lightTick();
        probeTick();
        pruneDistant();
        if (++tickCounter >= 20) {
            tickCounter = 0;
            rebuild();
        }
    }

    private int lastCMask = -1;
    private int lastSim = -1, lastSens = -1, lastHoleDist = -1, lastMask;
    private boolean lastHole, lastCustom;

    /** Settings that change what a scan produces trigger a rescan, the ones that only change the verdict just a rebuild. */
    private void refreshSettingsState() {
        refreshEvidence();
        int mask = 0;
        for (int i = 0; i < KINDS; i++) if (counted[i]) mask |= 1 << i;
        int cmask = (cKelp.on() ? 1 : 0) | (cCaveVines.on() ? 2 : 0) | (cVines.on() ? 4 : 0) | (cCocoa.on() ? 8 : 0) | (cAmethyst.on() ? 16 : 0)
                | (cBamboo.on() ? 32 : 0) | (beeNests.on() ? 64 : 0) | (deepslate.on() ? 128 : 0) | (sober() ? 256 : 0);
        if (cmask != lastCMask) {
            if (lastCMask >= 0) dirty = true;
            lastCMask = cmask;
        }
        boolean rescan = (lastSim >= 0 && (simDistance.i() != lastSim || (sober() && (mask != lastMask || (holeFilter.on() && !lastHole)))));
        boolean verdict = lastSim >= 0 && ((sober() ? soberSupport.i() : cryptoSensitivity.i()) != lastSens || holeDistance.i() != lastHoleDist || holeFilter.on() != lastHole);
        lastSim = simDistance.i();
        lastSens = sober() ? soberSupport.i() : cryptoSensitivity.i();
        lastHoleDist = holeDistance.i();
        lastHole = holeFilter.on();
        lastMask = mask;
        if (rescan) dirty = true;
        else if (verdict) tickCounter = 20;
    }

    private void pruneDistant() {
        if (!sober()) {
            cryptoPrune();
            return;
        }
        int reach = mc.options.getClampedViewDistance() + 2 * simDistance.i() + 2;
        int px = mc.player.getBlockX() >> 4, pz = mc.player.getBlockZ() >> 4;
        synchronized (lock) {
            distant.clear();
            LongIterator it = records.keySet().iterator();
            while (it.hasNext()) {
                long key = it.nextLong();
                if (Math.abs(ChunkPos.getPackedX(key) - px) > reach || Math.abs(ChunkPos.getPackedZ(key) - pz) > reach) distant.add(key);
            }
            for (int i = 0; i < distant.size(); i++) removeRecord(distant.getLong(i));
        }
        kelpChunks.removeIf(k -> Math.abs(ChunkPos.getPackedX(k) - px) > reach || Math.abs(ChunkPos.getPackedZ(k) - pz) > reach);
        seenKelp.removeIf(p -> Math.abs((BlockPos.unpackLongX(p) >> 4) - px) > reach || Math.abs((BlockPos.unpackLongZ(p) >> 4) - pz) > reach);
    }

    /* ------------------------------------------------------------------ verdict */

    private void rebuild() {
        if (devFake) {
            flagged = fake();
            return;
        }
        ClientWorld level = mc.world;
        if (level == null) return;
        if (!sober()) {
            rebuildCrypto(level);
            return;
        }
        boolean holes = holeFilter.on();
        int threshold = soberSupport.i(), holeReach = holeDistance.i(), sim = simDistance.i(), reach = 2 * sim + 2;
        long now = System.currentTimeMillis();

        // 1. Sober's flagging rule
        java.util.Map<Long, Sus> cand = new java.util.HashMap<>();
        synchronized (lock) {
            LongIterator keys = scanned.iterator();
            while (keys.hasNext()) {
                long key = keys.nextLong();
                int cx = ChunkPos.getPackedX(key), cz = ChunkPos.getPackedZ(key);
                if (!level.isChunkLoaded(cx, cz) || clearCoverage.containsKey(key)) continue;
                if (holes && !holeNearby(cx, cz, holeReach)) continue;
                int sup = support.get(key);
                int sides = coveredSides(cx, cz, reach);
                if (sup < threshold || sides < 3) continue;
                Sus s = new Sus();
                s.cx = cx;
                s.cz = cz;
                s.support = sup;
                s.margin = sup / (double) threshold;
                s.sides = sides;
                s.hole = holeNearby(cx, cz, 1);
                Record r = records.get(key);
                if (r != null) System.arraycopy(r.kinds(), 0, s.kinds, 0, KINDS);
                cand.put(key, s);
            }
        }
        if (cand.isEmpty()) {
            finish(new ArrayList<>(), now);
            return;
        }
        // evidence of the surrounding chunks (bee nests / deepslate / amethyst of neighbours count for the chunk too)
        for (Sus s : cand.values()) {
            synchronized (lock) {
                for (int dx = -1; dx <= 1; dx++)
                    for (int dz = -1; dz <= 1; dz++) {
                        Record r = records.get(ChunkPos.toLong(s.cx + dx, s.cz + dz));
                        if (r == null) continue;
                        s.extra += r.kinds()[BEE_NEST] + r.kinds()[DEEPSLATE];
                        if (dx != 0 || dz != 0) s.kinds[BEE_NEST] = Math.max(s.kinds[BEE_NEST], r.kinds()[BEE_NEST]);
                        if (dx != 0 || dz != 0) s.kinds[DEEPSLATE] = Math.max(s.kinds[DEEPSLATE], r.kinds()[DEEPSLATE]);
                    }
            }
            s.depth = depthIntoHole(s.cx, s.cz, reach);
            s.nearSpawn = nearSpawn(s.cx, s.cz);
            s.edge = loadedNeighbours(level, s.cx, s.cz) < 6;
        }

        // 2. clusters (8-connected), cluster size / shape
        List<List<Sus>> clusters = cluster(new ArrayList<>(cand.values()));
        List<Sus> out = new ArrayList<>();
        boolean forceSpread = mode.is("Spread");
        boolean forceSingle = mode.is("Single Chunk");
        for (List<Sus> group : clusters) {
            int size = group.size();
            boolean line = isLine(group);
            for (Sus s : group) {
                s.clusterSize = size;
                s.shaped = line;
                score(s);
            }
            // Smart Adjustment (Crypto): thin big clusters down to a line or their peak chunks
            if (smart.on() && size >= 10) {
                List<Sus> kept = new ArrayList<>();
                if (line) kept.addAll(group);
                else {
                    double max = 0;
                    for (Sus s : group) max = Math.max(max, s.support);
                    double f = size >= 40 ? 0.7 : size >= 20 ? 0.55 : 0.4;
                    for (Sus s : group) if (s.support >= max * f) kept.add(s);
                }
                group = kept;
            }
            boolean single = forceSingle || (!forceSpread && size <= 4) || (!forceSpread && !line && size > 4);
            if (mode.is("Auto")) single = !line && size <= 6;
            if (single) {
                Sus best = group.get(0);
                for (Sus s : group) if (s.score > best.score) best = s;
                out.add(best);
            } else out.addAll(group);
        }

        finish(out, now);
    }

    /** Chunks stay marked once found: walking up to one makes the server tick it and its growth "clears" it, which used to
     *  make the mark vanish exactly when you got close. A latched chunk stays for the Keep Time unless it is re-flagged. */
    private final java.util.Map<Long, Sus> latched = new java.util.HashMap<>();
    private final java.util.Map<Long, Long> latchedAt = new java.util.HashMap<>();

    private void finish(List<Sus> out, long now) {
        java.util.Set<Long> fresh = new java.util.HashSet<>();
        for (Sus s : out) {
            long k = ChunkPos.toLong(s.cx, s.cz);
            fresh.add(k);
            latched.put(k, s);
            latchedAt.put(k, now);
        }
        long keepMs = (long) keepTime.value * 1000L;
        java.util.Iterator<java.util.Map.Entry<Long, Sus>> it = latched.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            long age = now - latchedAt.getOrDefault(en.getKey(), 0L);
            if (keepMs <= 0 || age > keepMs) {
                it.remove();
                latchedAt.remove(en.getKey());
            } else if (!fresh.contains(en.getKey())) out.add(en.getValue());
        }
        // 3. chance filter, born times, notifications
        List<Sus> shown = new ArrayList<>();
        for (Sus s : out) {
            if (s.chance * 100 < minChance.value) continue;
            long k = ChunkPos.toLong(s.cx, s.cz);
            s.born = bornAt.computeIfAbsent(k, kk -> now);
            shown.add(s);
        }
        if (notify.on()) {
            for (Sus s : shown) {
                long k = ChunkPos.toLong(s.cx, s.cz);
                if (announced.add(k)) tenacity.ui.Toasts.push(category.icon, "Sus chunk " + s.cx + ", " + s.cz, Math.round(s.chance * 100) + "% base chance");
            }
        }
        bornAt.keySet().retainAll(shown.stream().map(s -> ChunkPos.toLong(s.cx, s.cz)).collect(java.util.stream.Collectors.toSet()));
        announced.retainAll(bornAt.keySet());
        flagged = shown;
    }

    private boolean holeNearby(int cx, int cz, int distance) {
        for (int dx = -distance; dx <= distance; dx++)
            for (int dz = -distance; dz <= distance; dz++) {
                Record r = records.get(ChunkPos.toLong(cx + dx, cz + dz));
                if (r != null && r.hole()) return true;
            }
        return false;
    }

    private int coveredSides(int cx, int cz, int reach) {
        int sides = 0;
        for (int[] s : SIDES) if (stripCleared(cx, cz, s[0], s[1], reach)) sides++;
        return sides;
    }

    private boolean stripCleared(int cx, int cz, int dx, int dz, int reach) {
        for (int depth = 1; depth <= reach; depth++)
            for (int lat = -reach; lat <= reach; lat++)
                if (clearCoverage.containsKey(ChunkPos.toLong(cx + dx * depth + dz * lat, cz + dz * depth + dx * lat))) return true;
        return false;
    }

    /** Chebyshev distance (in chunks) to the nearest chunk that was cleared by growth: 1 is the border of the hole. */
    private int depthIntoHole(int cx, int cz, int reach) {
        synchronized (lock) {
            for (int d = 1; d <= reach + 2; d++)
                for (int dx = -d; dx <= d; dx++)
                    for (int dz = -d; dz <= d; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                        if (clearCoverage.containsKey(ChunkPos.toLong(cx + dx, cz + dz))) return d;
                    }
        }
        return reach + 2;
    }

    private boolean nearSpawn(int cx, int cz) {
        if (mc.world == null) return false;
        var sp = mc.world.getSpawnPoint().getPos();
        return Math.abs((sp.getX() >> 4) - cx) <= 6 && Math.abs((sp.getZ() >> 4) - cz) <= 6;
    }

    private int loadedNeighbours(ClientWorld level, int cx, int cz) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) if ((dx != 0 || dz != 0) && level.isChunkLoaded(cx + dx, cz + dz)) n++;
        return n;
    }

    private static List<List<Sus>> cluster(List<Sus> all) {
        java.util.Map<Long, Integer> idx = new java.util.HashMap<>();
        for (int i = 0; i < all.size(); i++) idx.put(ChunkPos.toLong(all.get(i).cx, all.get(i).cz), i);
        int[] parent = new int[all.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int i = 0; i < all.size(); i++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Integer o = idx.get(ChunkPos.toLong(all.get(i).cx + dx, all.get(i).cz + dz));
                    if (o == null) continue;
                    int a = find(parent, i), b = find(parent, o);
                    if (a != b) parent[a] = b;
                }
        java.util.Map<Integer, List<Sus>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(all.get(i));
        return new ArrayList<>(groups.values());
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    /** A thin straight band of chunks (Crypto's dominantStraightLine): it follows a boundary, not a base. */
    private static boolean isLine(List<Sus> g) {
        if (g.size() < 3) return false;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        double mx = 0, mz = 0;
        for (Sus s : g) {
            minX = Math.min(minX, s.cx);
            maxX = Math.max(maxX, s.cx);
            minZ = Math.min(minZ, s.cz);
            maxZ = Math.max(maxZ, s.cz);
            mx += s.cx;
            mz += s.cz;
        }
        int spanX = maxX - minX + 1, spanZ = maxZ - minZ + 1;
        if (Math.max(spanX, spanZ) >= 8 && Math.min(spanX, spanZ) <= 2) return true;
        mx /= g.size();
        mz /= g.size();
        double xx = 0, zz = 0, xz = 0;
        for (Sus s : g) {
            double dx = s.cx - mx, dz = s.cz - mz;
            xx += dx * dx;
            zz += dz * dz;
            xz += dx * dz;
        }
        xx /= g.size();
        zz /= g.size();
        xz /= g.size();
        double a = 0.5 * Math.atan2(2 * xz, xx - zz), ax = Math.cos(a), az = Math.sin(a);
        double major = ax * ax * xx + 2 * ax * az * xz + az * az * zz, minor = Math.max(0, xx + zz - major);
        return major >= 1.0 && minor / major <= 0.2;
    }

    /**
     * Logistic chance. z = B0 + sum of weighted features (log likelihood ratios, hand calibrated against flagged chunks
     * of confirmed bases vs. unvisited wilderness), p = 1 / (1 + e^-z), clamped to 1..99%. Each line is also the reason
     * shown on the card, with its contribution in log-odds.
     */
    private void score(Sus s) {
        List<String> why = new ArrayList<>();
        double z = B0; // prior: most flagged chunks are wilderness, bases are rare

        double w = switch (s.sides) {
            case 4 -> 1.5;
            case 3 -> 0.6;
            default -> -0.8;
        };
        z += w;
        why.add(fmt(w, s.sides == 4 ? "Grown chunks on all 4 sides" : s.sides + " sides touch grown chunks"));

        if (s.depth >= 2) {
            w = Math.min(1.0, 0.3 * (s.depth - 1));
            z += w;
            why.add(fmt(w, s.depth + " chunks deep in the hole"));
        }

        w = Math.max(-0.5, Math.min(1.2, 1.2 * Math.log(Math.max(0.1, s.margin)) / Math.log(4)));
        z += w;
        why.add(fmt(w, String.format(Locale.ROOT, "Support %d (%.1fx sensitivity)", (int) s.support, s.margin)));

        if (s.clusterSize <= 4) {
            w = s.clusterSize == 1 ? 0.5 : 0.35;
            z += w;
            why.add(fmt(w, s.clusterSize == 1 ? "Single chunk, base sized" : s.clusterSize + " chunk cluster, base sized"));
        } else {
            w = -Math.min(2.4, 0.3 * (s.clusterSize - 4) + 0.3);
            z += w;
            why.add(fmt(w, s.clusterSize + " chunk region, likely wilderness"));
        }
        if (s.shaped) {
            z -= 1.1;
            why.add(fmt(-1.1, "Thin straight band (a boundary)"));
        }
        if (s.hole) {
            z += 1.4;
            why.add(fmt(1.4, "Dug shaft or tunnel found"));
        }
        int bees = s.kinds[BEE_NEST], deep = s.kinds[DEEPSLATE], ame = s.kinds[AMETHYST];
        if (bees > 0) {
            w = Math.min(1.1, 0.5 + 0.2 * bees);
            z += w;
            why.add(fmt(w, bees + " full bee nest" + (bees == 1 ? "" : "s") + " (farm)"));
        }
        if (deep > 0) {
            w = Math.min(1.4, 0.7 + 0.1 * deep);
            z += w;
            why.add(fmt(w, deep + " buried rotated deepslate"));
        }
        if (ame > 0) {
            z += 0.4;
            why.add(fmt(0.4, "Amethyst touching stone"));
        }
        if (s.nearSpawn) {
            z -= 1.8;
            why.add(fmt(-1.8, "Near world spawn"));
        }
        if (s.edge) {
            z -= 0.7;
            why.add(fmt(-0.7, "On the loaded edge"));
        }
        s.score = z;
        s.chance = Math.max(0.01, Math.min(0.99, 1.0 / (1.0 + Math.exp(-z))));
        why.add(String.format(Locale.ROOT, "Log-odds %+.1f (prior %+.1f)", z, B0));
        s.reasons = why;
    }

    /** Log-odds of a flagged chunk being a base before any feature is known. */
    private static final double B0 = -2.2;

    /** Contribution first so it survives the card truncation: "+1.5  Grown chunks on all 4 sides". */
    private static String fmt(double w, String text) {
        return String.format(Locale.ROOT, "%+.1f  %s", w, text);
    }

    /* ------------------------------------------------------------------ Crypto detection (port of Crypto's SusChunkFinder) */

    /** Crypto's lenient constants. */
    private static final int MIN_LOADED_NEIGHBORS = 2;
    private static final int HEAVY_OVERLAP_MIN = 10;
    private static final int C_FOLIA_AMETHYST_EXPOSED_MIN = 3;
    private static final int C_DEEPSLATE_MIN_SOLID_SIDES = 5;
    private static final int C_DEEPSLATE_MIN_Y = -8, C_DEEPSLATE_MAX_Y = 64;
    private static final double LINE_MAX_VARIANCE_RATIO = 0.2, LINE_MAX_DISTANCE = 1.25, LINE_MIN_FRACTION = 0.45;
    private static final int RENDER_RANGE = 64;

    private record ScanResult(int totalNotGrown, boolean anyGrown, Set<BlockPos> deepslate, int fullBeeNestCount, int[] notGrown) {
    }

    private final ConcurrentHashMap<ChunkPos, Integer> chunkHeatmap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Integer> trackedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Integer> chunkAppliedRing = new ConcurrentHashMap<>();
    private final Set<ChunkPos> loadedChunkPositions = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> suspiciousDeepslateBlocks = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ChunkPos, Set<BlockPos>> chunkDeepslateMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Integer> chunkGrowthCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, int[]> chunkKindCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean> chunkHasFullyGrown = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Integer> fullBeeNestCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Integer> fullBeeNestHeatmap = new ConcurrentHashMap<>();
    private volatile List<ChunkPos> beeNestChunks = List.of();
    private long lastSweep;
    private ChunkPos lastSweepCenter;

    private void cryptoClear() {
        chunkHeatmap.clear();
        trackedChunks.clear();
        chunkAppliedRing.clear();
        loadedChunkPositions.clear();
        suspiciousDeepslateBlocks.clear();
        chunkDeepslateMap.clear();
        chunkGrowthCounts.clear();
        chunkKindCounts.clear();
        chunkHasFullyGrown.clear();
        fullBeeNestCounts.clear();
        fullBeeNestHeatmap.clear();
        beeNestChunks = List.of();
        lastSweep = System.currentTimeMillis();
        lastSweepCenter = null;
    }

    /** Crypto sweeps the view distance when the player changes chunk and every 5 s (the packets alone miss re-ticked chunks). */
    private void cryptoSweepTick() {
        ChunkPos center = mc.player.getChunkPos();
        long now = System.currentTimeMillis();
        if (lastSweepCenter == null || !lastSweepCenter.equals(center) || now - lastSweep >= 5000L) {
            lastSweepCenter = center;
            lastSweep = now;
            int r = Math.max(2, Math.min(32, mc.options.getViewDistance().getValue())) + 2;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    if (mc.world.isChunkLoaded(center.x + dx, center.z + dz)) scheduleScan(ChunkPos.toLong(center.x + dx, center.z + dz));
        }
    }

    private void cryptoPrune() {
        if (mc.world == null) return;
        suspiciousDeepslateBlocks.removeIf(p -> !mc.world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4));
        loadedChunkPositions.removeIf(p -> !mc.world.isChunkLoaded(p.x, p.z));
    }

    private void cryptoAnalyze(ClientWorld level, long key, WorldChunk chunk, int expected) {
        ChunkPos pos = chunk.getPos();
        ScanResult result;
        try {
            result = cryptoScan(level, pos, chunk);
        } catch (Throwable t) {
            return;
        }
        int sim = simDistance.i();
        mc.execute(() -> {
            if (!enabled() || mc.world != level || expected != generation) return;
            loadedChunkPositions.add(pos);
            updateChunkData(pos, result, sim);
        });
    }

    /** Crypto's updateChunkData: remove the previous contribution of this chunk (same ring it was applied with), add the new one. */
    private void updateChunkData(ChunkPos pos, ScanResult result, int simDist) {
        int appliedRing = chunkAppliedRing.getOrDefault(pos, simDist);
        int previousGrowth = chunkGrowthCounts.getOrDefault(pos, 0);
        boolean previousFullyGrown = Boolean.TRUE.equals(chunkHasFullyGrown.get(pos));
        Set<BlockPos> previousDeepslate = chunkDeepslateMap.remove(pos);

        if (previousGrowth > 0)
            for (int dx = -appliedRing; dx <= appliedRing; dx++)
                for (int dz = -appliedRing; dz <= appliedRing; dz++)
                    chunkHeatmap.compute(new ChunkPos(pos.x + dx, pos.z + dz), (k, v) -> {
                        int n = (v == null ? 0 : v) - previousGrowth;
                        return n <= 0 ? null : n;
                    });
        if (previousFullyGrown)
            for (int dx = -appliedRing; dx <= appliedRing; dx++)
                for (int dz = -appliedRing; dz <= appliedRing; dz++)
                    trackedChunks.compute(new ChunkPos(pos.x + dx, pos.z + dz), (k, v) -> v == null || v - 1 <= 0 ? null : v - 1);
        if (previousDeepslate != null && !previousDeepslate.isEmpty()) suspiciousDeepslateBlocks.removeAll(previousDeepslate);

        chunkGrowthCounts.put(pos, result.totalNotGrown());
        chunkKindCounts.put(pos, result.notGrown());
        chunkHasFullyGrown.put(pos, result.anyGrown());
        chunkAppliedRing.put(pos, simDist);

        if (result.anyGrown())
            for (int dx = -simDist; dx <= simDist; dx++)
                for (int dz = -simDist; dz <= simDist; dz++) trackedChunks.merge(new ChunkPos(pos.x + dx, pos.z + dz), 1, Integer::sum);
        if (result.totalNotGrown() > 0)
            for (int dx = -simDist; dx <= simDist; dx++)
                for (int dz = -simDist; dz <= simDist; dz++) chunkHeatmap.merge(new ChunkPos(pos.x + dx, pos.z + dz), result.totalNotGrown(), Integer::sum);
        if (!result.deepslate().isEmpty()) {
            chunkDeepslateMap.put(pos, result.deepslate());
            suspiciousDeepslateBlocks.addAll(result.deepslate());
        }

        // the previous bee nest contribution is not subtracted by Crypto either (it merges again on every scan); we do
        // subtract it, otherwise the periodic sweep would inflate the counts forever
        Integer oldBees = fullBeeNestCounts.put(pos, result.fullBeeNestCount());
        if (oldBees != null && oldBees > 0)
            for (int dx = -6; dx <= 6; dx++)
                for (int dz = -6; dz <= 6; dz++)
                    fullBeeNestHeatmap.compute(new ChunkPos(pos.x + dx, pos.z + dz), (k, v) -> {
                        int n = (v == null ? 0 : v) - oldBees;
                        return n <= 0 ? null : n;
                    });
        if (result.fullBeeNestCount() > 0)
            for (int dx = -6; dx <= 6; dx++)
                for (int dz = -6; dz <= 6; dz++)
                    fullBeeNestHeatmap.merge(new ChunkPos(pos.x + dx, pos.z + dz), result.fullBeeNestCount(), Integer::sum);
    }

    private static final int C_KELP = 0, C_CAVE = 1, C_VINE = 2, C_COCOA = 3, C_AMETHYST = 4, C_BAMBOO = 5, C_BEE = 6;

    /** Crypto's scanChunkBlocks, line for line (grown / not grown semantics are Crypto's, including its odd inversions). */
    private ScanResult cryptoScan(ClientWorld level, ChunkPos chunkPos, WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY() >> 4;
        int startX = chunkPos.getStartX(), startZ = chunkPos.getStartZ();
        Reader blocks = new Reader(level);
        boolean kelp = cKelp.on(), cave = cCaveVines.on(), vines = cVines.on(), cocoa = cCocoa.on(), amethyst = cAmethyst.on(),
                bamboo = cBamboo.on(), bee = beeNests.on(), rotated = deepslate.on();

        int[] grown = new int[7], notGrown = new int[7];
        int fullBeeNestCount = 0;
        boolean folia = amethyst && donutServer();
        int foliaExposed = 0;
        Set<BlockPos> deepslatePos = ConcurrentHashMap.newKeySet();

        for (int si = 0; si < sections.length; si++) {
            ChunkSection section = sections[si];
            if (section == null || section.isEmpty()) continue;
            int sectionMinY = (bottomY + si) << 4;
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block b = state.getBlock();
                        if (state.isAir()) continue;
                        int bx = startX + x, by = sectionMinY + y, bz = startZ + z;
                        if (folia && isAmethystBlock(state)) {
                            boolean open = false;
                            for (Direction d : DIRECTIONS)
                                if (blocks.state(bx + d.getOffsetX(), by + d.getOffsetY(), bz + d.getOffsetZ()).isAir()) { open = true; break; }
                            if (!open) foliaExposed++; // buried amethyst only (no air touching it)
                        }

                        if (kelp && b == Blocks.KELP && state.contains(Properties.AGE_25)) {
                            boolean waterAbove = blocks.state(bx, by + 1, bz).isOf(Blocks.WATER);
                            if (state.get(Properties.AGE_25) != 25 && waterAbove) grown[C_KELP]++;
                            else notGrown[C_KELP]++;
                        } else if (cave && b == Blocks.CAVE_VINES && state.contains(Properties.AGE_25)) {
                            boolean airBelow = blocks.state(bx, by - 1, bz).isAir();
                            if (state.get(Properties.AGE_25) != 25 && airBelow) grown[C_CAVE]++;
                            else notGrown[C_CAVE]++;
                        } else if (vines && b == Blocks.VINE) {
                            BlockState below = blocks.state(bx, by - 1, bz);
                            if (!below.isOf(Blocks.VINE)) {
                                if (!below.isAir()) notGrown[C_VINE]++;
                                else if (state.get(Properties.NORTH) || state.get(Properties.EAST) || state.get(Properties.SOUTH) || state.get(Properties.WEST)) grown[C_VINE]++;
                                else notGrown[C_VINE]++;
                            }
                        } else if (cocoa && b == Blocks.COCOA && state.contains(Properties.AGE_2)) {
                            if (state.get(Properties.AGE_2) != 2) grown[C_COCOA]++;
                            else notGrown[C_COCOA]++;
                        } else if (amethyst && !folia && (b == Blocks.AMETHYST_CLUSTER || isBud(state))) {
                            if (b == Blocks.AMETHYST_CLUSTER) notGrown[C_AMETHYST]++;
                            else if (state.contains(Properties.FACING)) {
                                Direction f = state.get(Properties.FACING);
                                if (blocks.state(bx - f.getOffsetX(), by - f.getOffsetY(), bz - f.getOffsetZ()).isOf(Blocks.BUDDING_AMETHYST)) grown[C_AMETHYST]++;
                                else notGrown[C_AMETHYST]++;
                            }
                        } else if (bamboo && b == Blocks.BAMBOO && state.contains(Properties.STAGE)) {
                            BlockState above = blocks.state(bx, by + 1, bz);
                            if (!above.isOf(Blocks.BAMBOO)) {
                                if (state.get(Properties.STAGE) == 1) notGrown[C_BAMBOO]++;
                                else if (above.isAir()) grown[C_BAMBOO]++;
                            }
                        } else if (bee && b == Blocks.BEE_NEST && state.contains(Properties.HONEY_LEVEL)) {
                            if (state.get(Properties.HONEY_LEVEL) == 5) {
                                notGrown[C_BEE]++;
                                fullBeeNestCount++;
                            } else grown[C_BEE]++;
                        } else if (rotated && b == Blocks.DEEPSLATE && state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y
                                && by >= C_DEEPSLATE_MIN_Y && by <= C_DEEPSLATE_MAX_Y) {
                            int solid = 0;
                            for (Direction d : DIRECTIONS) if (!blocks.state(bx + d.getOffsetX(), by + d.getOffsetY(), bz + d.getOffsetZ()).isAir()) solid++;
                            if (solid >= C_DEEPSLATE_MIN_SOLID_SIDES) deepslatePos.add(new BlockPos(bx, by, bz));
                        }
                    }
        }
        if (folia && foliaExposed >= C_FOLIA_AMETHYST_EXPOSED_MIN) notGrown[C_AMETHYST]++;

        int totalGrown = 0, totalNotGrown = 0;
        for (int i = 0; i < 7; i++) {
            totalGrown += grown[i];
            totalNotGrown += notGrown[i];
        }
        return new ScanResult(totalNotGrown, totalGrown > 0, deepslatePos, fullBeeNestCount, notGrown);
    }

    /** Crypto's render-time pass: heat threshold, loaded, not tracked (unless Smart), range, neighbour count, then Smart Adjustment. */
    private void rebuildCrypto(ClientWorld level) {
        long now = System.currentTimeMillis();
        int minSens = cryptoSensitivity.i();
        boolean smartOn = smart.on();
        ChunkPos pc = mc.player.getChunkPos();

        // bee nest chunks (Crypto's orange marks): at least one full nest within 6 chunks
        List<ChunkPos> bees = new ArrayList<>();
        for (var e : fullBeeNestHeatmap.entrySet()) {
            ChunkPos p = e.getKey();
            if (e.getValue() < 1 || !level.isChunkLoaded(p.x, p.z)) continue;
            if (Math.abs(p.x - pc.x) > RENDER_RANGE || Math.abs(p.z - pc.z) > RENDER_RANGE) continue;
            bees.add(p);
        }
        beeNestChunks = bees;

        List<Map.Entry<ChunkPos, Integer>> candidates = new ArrayList<>();
        // area size is always the sensitivity-2 area; a higher sensitivity only keeps the blobs that contain a chunk reaching it
        int base = growArea.on() ? Math.min(minSens, 2) : minSens;
        for (var entry : chunkHeatmap.entrySet()) {
            ChunkPos pos = entry.getKey();
            if (entry.getValue() < base) continue;
            if (!level.isChunkLoaded(pos.x, pos.z)) continue;
            if (!smartOn && trackedChunks.containsKey(pos)) continue;
            if (Math.abs(pos.x - pc.x) > RENDER_RANGE || Math.abs(pos.z - pc.z) > RENDER_RANGE) continue;
            int neighbours = 0;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if ((dx != 0 || dz != 0) && loadedChunkPositions.contains(new ChunkPos(pos.x + dx, pos.z + dz))) neighbours++;
            if (neighbours < MIN_LOADED_NEIGHBORS) continue;
            candidates.add(entry);
        }

        if (growArea.on() && minSens > base && !candidates.isEmpty()) {
            Map<Long, Integer> byKey = new HashMap<>();
            for (var e : candidates) byKey.put(e.getKey().toLong(), e.getValue());
            Set<Long> keep = new java.util.HashSet<>(), done = new java.util.HashSet<>();
            for (var e : candidates) {
                long k0 = e.getKey().toLong();
                if (!done.add(k0)) continue;
                List<Long> comp = new ArrayList<>(List.of(k0));
                boolean strong = e.getValue() >= minSens;
                for (int qi = 0; qi < comp.size(); qi++) {
                    ChunkPos c = new ChunkPos(comp.get(qi));
                    for (int ax = -1; ax <= 1; ax++)
                        for (int az = -1; az <= 1; az++) {
                            long nk = ChunkPos.toLong(c.x + ax, c.z + az);
                            Integer h = byKey.get(nk);
                            if (h != null && done.add(nk)) {
                                comp.add(nk);
                                if (h >= minSens) strong = true;
                            }
                        }
                }
                if (strong) keep.addAll(comp);
            }
            candidates.removeIf(e -> !keep.contains(e.getKey().toLong()));
            // a higher sensitivity must not look smaller: the surviving blobs grow by two chunks over any chunk that has some heat
            Set<Long> have = new java.util.HashSet<>(keep);
            List<Map.Entry<ChunkPos, Integer>> extra = new ArrayList<>();
            for (long k : keep) {
                ChunkPos c = new ChunkPos(k);
                for (int ax = -2; ax <= 2; ax++)
                    for (int az = -2; az <= 2; az++) {
                        ChunkPos q = new ChunkPos(c.x + ax, c.z + az);
                        Integer h = chunkHeatmap.get(q);
                        if (h == null || !have.add(q.toLong()) || !level.isChunkLoaded(q.x, q.z) || trackedChunks.containsKey(q)) continue;
                        if (Math.abs(q.x - pc.x) > RENDER_RANGE || Math.abs(q.z - pc.z) > RENDER_RANGE) continue;
                        extra.add(new java.util.AbstractMap.SimpleEntry<>(q, h));
                    }
            }
            candidates.addAll(extra);
        }

        // hidden light pockets: a chunk with one is a candidate even when the growth heat (or the tracked filter) says nothing
        Map<Long, Integer> lightHeat = new HashMap<>();
        if (lightActive()) {
            for (var e : lightHeatmap.entrySet()) {
                ChunkPos pos = e.getKey();
                if (e.getValue() < LIGHT_HEAT_MIN || !level.isChunkLoaded(pos.x, pos.z)) continue;
                if (Math.abs(pos.x - pc.x) > RENDER_RANGE || Math.abs(pos.z - pc.z) > RENDER_RANGE) continue;
                lightHeat.put(pos.toLong(), e.getValue());
            }
            java.util.Set<Long> have = new java.util.HashSet<>();
            for (var e : candidates) have.add(e.getKey().toLong());
            for (var e : lightHeat.entrySet()) if (!have.contains(e.getKey())) {
                ChunkPos p = new ChunkPos(e.getKey());
                candidates.add(new java.util.AbstractMap.SimpleEntry<>(p, chunkHeatmap.getOrDefault(p, 0)));
            }
        }

        List<ChunkPos> toDraw = new ArrayList<>();
        List<List<Integer>> groups = new ArrayList<>();
        boolean[] lineGroup;
        if (smartOn && candidates.size() > 1) {
            groups = clusterCandidates(candidates);
            lineGroup = new boolean[groups.size()];
            for (int g = 0; g < groups.size(); g++) {
                List<Integer> group = groups.get(g);
                if (group.size() < HEAVY_OVERLAP_MIN) {
                    for (int i : group) if (!trackedChunks.containsKey(candidates.get(i).getKey())) toDraw.add(candidates.get(i).getKey());
                    continue;
                }
                // Smart Adjustment, safe version: a big blob is thinned to its strong chunks only. The line (if any), every chunk within
                // 60% of the peak heat and every chunk with hidden-evidence stay, so a real flag is never dropped and the group never empties.
                int max = 0;
                for (int i : group) max = Math.max(max, candidates.get(i).getValue());
                java.util.Set<Integer> keep = new java.util.HashSet<>(dominantStraightLine(group, candidates));
                if (!keep.isEmpty()) lineGroup[g] = true;
                // the bigger the blob, the harder it is thinned: the fringe of a huge blob is spill, not a base
                double frac = group.size() >= 40 ? 0.7 : group.size() >= 20 ? 0.55 : 0.4;
                Map<Long, Integer> gh = new HashMap<>();
                for (int i : group) gh.put(candidates.get(i).getKey().toLong(), candidates.get(i).getValue());
                for (int i : group) {
                    ChunkPos p = candidates.get(i).getKey();
                    int h = candidates.get(i).getValue();
                    boolean peak = true;
                    for (int dx = -1; dx <= 1 && peak; dx++)
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) continue;
                            Integer o = gh.get(ChunkPos.toLong(p.x + dx, p.z + dz));
                            if (o != null && o > h) { peak = false; break; }
                        }
                    if (peak && h >= Math.max(1, (int) Math.ceil(max * 0.5)) || h >= Math.max(1, (int) Math.ceil(max * frac)) || lightHeat.containsKey(p.toLong())) keep.add(i);
                }
                // drop lone stragglers: a kept chunk with no kept neighbour that is not a peak or line member
                java.util.Set<Long> keptPos = new java.util.HashSet<>();
                for (int i : keep) keptPos.add(candidates.get(i).getKey().toLong());
                final int peakHeat = max;
                if (keep.size() > 3) keep.removeIf(i -> {
                    ChunkPos p = candidates.get(i).getKey();
                    if (lightHeat.containsKey(p.toLong()) || candidates.get(i).getValue() >= peakHeat) return false;
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dz = -1; dz <= 1; dz++) if ((dx != 0 || dz != 0) && keptPos.contains(ChunkPos.toLong(p.x + dx, p.z + dz))) return false;
                    return true;
                });
                for (int i : keep) {
                    ChunkPos p = candidates.get(i).getKey();
                    if (!trackedChunks.containsKey(p) || lightHeat.containsKey(p.toLong())) toDraw.add(p);
                }
            }
        } else {
            for (var e : candidates) if (!trackedChunks.containsKey(e.getKey()) || lightHeat.containsKey(e.getKey().toLong())) toDraw.add(e.getKey());
        }

        // Verdict objects: cluster of the drawn chunks (8-connected) for size / shape, then the shared score and Mode logic
        Map<Long, Integer> heat = new HashMap<>();
        for (var e : candidates) heat.put(e.getKey().toLong(), e.getValue());
        List<Sus> all = new ArrayList<>();
        for (ChunkPos p : toDraw) {
            Sus s = new Sus();
            s.cx = p.x;
            s.cz = p.z;
            s.support = heat.getOrDefault(p.toLong(), 0);
            s.margin = Math.max(s.support, 0.1) / (double) minSens;
            s.crypto = true;
            s.sides = 0;
            s.depth = ringDepth(p);
            s.nearSpawn = nearSpawn(p.x, p.z);
            s.edge = loadedNeighbours(level, p.x, p.z) < 6;
            // evidence: what the 3x3 around the chunk is made of
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    ChunkPos q = new ChunkPos(p.x + dx, p.z + dz);
                    int[] k = chunkKindCounts.get(q);
                    if (k != null) for (int i = 0; i < 7; i++) s.cryptoKinds[i] += k[i];
                    Set<BlockPos> ds = chunkDeepslateMap.get(q);
                    if (ds != null) s.kinds[DEEPSLATE] += ds.size();
                    Integer bn = fullBeeNestCounts.get(q);
                    if (bn != null) s.kinds[BEE_NEST] += bn;
                }
            lightEvidence(s);
            all.add(s);
        }
        List<Sus> out = new ArrayList<>();
        boolean forceSpread = mode.is("Spread"), forceSingle = mode.is("Single Chunk");
        for (List<Sus> group : cluster(all)) {
            int size = group.size();
            boolean line = isLine(group);
            for (Sus s : group) {
                s.clusterSize = size;
                s.shaped = line;
                scoreCrypto(s);
            }
            boolean single = forceSingle;
            if (mode.is("Auto")) single = !line && size <= 6;
            if (single) {
                Sus best = group.get(0);
                for (Sus s : group) if (s.score > best.score) best = s;
                out.add(best);
            } else out.addAll(group);
        }
        finish(out, now);
    }

    /** Distance in chunks to the nearest chunk the server ticked (tracked): how deep inside the un-simulated area a chunk is. */
    private int ringDepth(ChunkPos p) {
        int max = simDistance.i() + 6;
        for (int d = 1; d <= max; d++)
            for (int dx = -d; dx <= d; dx++)
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    if (trackedChunks.containsKey(new ChunkPos(p.x + dx, p.z + dz))) return d;
                }
        return max;
    }

    private static final String[] C_NAMES = {"Kelp", "Cave vines", "Vines", "Cocoa", "Amethyst", "Bamboo", "Bee nests"};

    /** Same logistic model as {@link #score}, fed with Crypto's evidence: heat vs sensitivity, ring depth, cluster shape, extras. */
    private void scoreCrypto(Sus s) {
        List<String> why = new ArrayList<>();
        // Crypto's own filter (heat >= sensitivity, loaded neighbours, not ticked) has already removed most of the world, so a chunk
        // that reaches here starts from a much higher base than a raw Sober candidate
        double z = B0 + 1.5;
        double w;
        boolean growthHeat = s.support >= cryptoSensitivity.i();
        if (growthHeat) {
            w = Math.max(-0.5, Math.min(1.6, 1.2 * Math.log(Math.max(0.1, s.margin)) / Math.log(3)));
            z += w;
            why.add(fmt(w, String.format(Locale.ROOT, "Heat %d vs sensitivity %d", (int) s.support, cryptoSensitivity.i())));
        } else if (s.intelEnt + s.intelBe + s.intelEv > 0) {
            z -= 0.3; // no growth heat: the buried activity has to carry the chunk alone
        }
        int intel = s.intelEv;
        if (intel > 0) {
            w = Math.min(3.2, 1.6 + 0.8 * Math.log(1 + intel));
            z += w;
            StringBuilder sb = new StringBuilder("Geode in chunk palette (" + s.intelEv + ", Y " + (s.intelY == Integer.MAX_VALUE ? 0 : s.intelY) + ")");
            why.add(fmt(w, sb.toString()));
        }

        int total = 0, top = 0;
        for (int i = 0; i < 7; i++) {
            total += s.cryptoKinds[i];
            if (s.cryptoKinds[i] > s.cryptoKinds[top]) top = i;
        }
        if (total > 0) {
            w = Math.min(0.8, 0.15 * Math.log(1 + total) / Math.log(2));
            z += w;
            String what = switch (top) {
                case C_KELP -> "Kelp not grown";
                case C_CAVE -> "Cave vines not grown";
                case C_VINE -> "Vines not grown";
                case C_COCOA -> "Cocoa not grown";
                case C_AMETHYST -> "Amethyst not grown";
                case C_BAMBOO -> "Bamboo not grown";
                default -> "Full bee nests";
            };
            why.add(fmt(w, what + " (" + total + " blocks around)"));
        }
        if (s.depth >= 2) {
            w = Math.min(1.0, 0.25 * (s.depth - 1));
            z += w;
            why.add(fmt(w, s.depth + " chunks from the nearest ticked chunk"));
        } else {
            z -= 0.4;
            why.add(fmt(-0.4, "Borders a ticked chunk"));
        }
        if (s.clusterSize <= 4) {
            w = s.clusterSize == 1 ? 0.5 : 0.35;
            z += w;
            why.add(fmt(w, s.clusterSize == 1 ? "Single chunk, base sized" : s.clusterSize + " chunk cluster, base sized"));
        } else if (s.shaped) {
            z -= 1.1;
            why.add(fmt(-1.1, s.clusterSize + " chunk straight band (a boundary)"));
        } else {
            w = -Math.min(2.4, 0.2 * (s.clusterSize - 4) + 0.3);
            z += w;
            why.add(fmt(w, s.clusterSize + " chunk blob, likely wilderness"));
        }
        int bees = s.kinds[BEE_NEST], deep = s.kinds[DEEPSLATE];
        if (bees > 0) {
            w = Math.min(1.1, 0.5 + 0.2 * bees);
            z += w;
            why.add(fmt(w, bees + " full bee nest" + (bees == 1 ? "" : "s") + " (farm)"));
        }
        if (deep > 0) {
            w = Math.min(1.4, 0.7 + 0.1 * deep);
            z += w;
            why.add(fmt(w, deep + " buried rotated deepslate"));
        }
        if (s.nearSpawn) {
            z -= 1.8;
            why.add(fmt(-1.8, "Near world spawn"));
        }
        if (s.edge) {
            z -= 0.7;
            why.add(fmt(-0.7, "On the loaded edge"));
        }
        s.score = z;
        s.chance = Math.max(0.01, Math.min(0.99, 1.0 / (1.0 + Math.exp(-z))));
        why.add(String.format(Locale.ROOT, "Log-odds %+.1f (prior %+.1f)", z, B0));
        s.reasons = why;
    }

    /** Crypto's Smart Adjustment helpers. */
    private static List<Integer> dominantStraightLine(List<Integer> group, List<Map.Entry<ChunkPos, Integer>> candidates) {
        if (group.size() < 3) return List.of();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        double meanX = 0, meanZ = 0;
        for (int i : group) {
            ChunkPos pos = candidates.get(i).getKey();
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
            minZ = Math.min(minZ, pos.z);
            maxZ = Math.max(maxZ, pos.z);
            meanX += pos.x;
            meanZ += pos.z;
        }
        int spanX = maxX - minX + 1, spanZ = maxZ - minZ + 1;
        if (Math.max(spanX, spanZ) >= 8 && Math.min(spanX, spanZ) <= 2) return group;
        meanX /= group.size();
        meanZ /= group.size();
        double xx = 0, zz = 0, xz = 0;
        for (int i : group) {
            ChunkPos pos = candidates.get(i).getKey();
            double dx = pos.x - meanX, dz = pos.z - meanZ;
            xx += dx * dx;
            zz += dz * dz;
            xz += dx * dz;
        }
        xx /= group.size();
        zz /= group.size();
        xz /= group.size();
        double angle = 0.5 * Math.atan2(2 * xz, xx - zz), ax = Math.cos(angle), az = Math.sin(angle);
        double major = ax * ax * xx + 2 * ax * az * xz + az * az * zz, minor = Math.max(0, xx + zz - major);
        if (major < 1.0 || minor / major > LINE_MAX_VARIANCE_RATIO) return List.of();
        List<Integer> line = new ArrayList<>();
        for (int i : group) {
            ChunkPos pos = candidates.get(i).getKey();
            double dx = pos.x - meanX, dz = pos.z - meanZ;
            if (Math.abs(-az * dx + ax * dz) <= LINE_MAX_DISTANCE) line.add(i);
        }
        int required = Math.max(3, (int) Math.ceil(group.size() * LINE_MIN_FRACTION));
        return line.size() >= required ? line : List.of();
    }

    private static List<List<Integer>> clusterCandidates(List<Map.Entry<ChunkPos, Integer>> candidates) {
        int count = candidates.size();
        Map<Long, Integer> index = new HashMap<>(count * 2);
        for (int i = 0; i < count; i++) index.put(candidates.get(i).getKey().toLong(), i);
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) parent[i] = i;
        for (int i = 0; i < count; i++) {
            ChunkPos pos = candidates.get(i).getKey();
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Integer other = index.get(ChunkPos.toLong(pos.x + dx, pos.z + dz));
                    if (other == null) continue;
                    int a = find(parent, i), b = find(parent, other);
                    if (a != b) parent[a] = b;
                }
        }
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < count; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        return new ArrayList<>(groups.values());
    }

    /** Crypto's cyan boxes on rotated deepslate buried in stone. */
    private void drawDeepslate() {
        if (suspiciousDeepslateBlocks.isEmpty() || mc.world == null) return;
        int cyan = 0x50 << 24 | 0x00FFFF;
        int n = 0;
        for (BlockPos p : suspiciousDeepslateBlocks) {
            if (++n > 4000) break;
            double x1 = p.getX(), y1 = p.getY(), z1 = p.getZ(), x2 = x1 + 1, y2 = y1 + 1, z2 = z1 + 1;
            Render3D.drawQuad(new Vec3d(x1, y1, z1), new Vec3d(x2, y1, z1), new Vec3d(x2, y1, z2), new Vec3d(x1, y1, z2), cyan, false);
            Render3D.drawQuad(new Vec3d(x1, y1, z1), new Vec3d(x1, y2, z1), new Vec3d(x2, y2, z1), new Vec3d(x2, y1, z1), cyan, false);
            Render3D.drawQuad(new Vec3d(x2, y1, z1), new Vec3d(x2, y2, z1), new Vec3d(x2, y2, z2), new Vec3d(x2, y1, z2), cyan, false);
            Render3D.drawQuad(new Vec3d(x1, y1, z2), new Vec3d(x2, y1, z2), new Vec3d(x2, y2, z2), new Vec3d(x1, y2, z2), cyan, false);
            Render3D.drawQuad(new Vec3d(x1, y1, z1), new Vec3d(x1, y1, z2), new Vec3d(x1, y2, z2), new Vec3d(x1, y2, z1), cyan, false);
            Render3D.drawQuad(new Vec3d(x1, y2, z1), new Vec3d(x1, y2, z2), new Vec3d(x2, y2, z2), new Vec3d(x2, y2, z1), cyan, false);
        }
    }

    /* ------------------------------------------------------------------ dev fakes */

    private List<Sus> fake() {
        List<Sus> out = new ArrayList<>();
        if (mc.player == null) return out;
        int px = mc.player.getBlockX() >> 4, pz = mc.player.getBlockZ() >> 4;
        int[][] at = {{0, -1}, {-3, -3}, {5, 1}, {-2, 4}, {1, -1}, {0, 0}, {-1, 0}, {-3, -2}, {-4, -3}};
        for (int i = 0; i < at.length; i++) {
            Sus s = new Sus();
            s.cx = px + at[i][0];
            s.cz = pz + at[i][1];
            s.sides = i == 0 ? 4 : 3;
            s.depth = 2 + i;
            s.support = 60 + i * 40;
            s.margin = s.support / 40.0;
            s.clusterSize = i == 2 ? 6 : 1 + i;
            s.hole = i == 0;
            s.kinds[BEE_NEST] = i == 1 ? 2 : 0;
            s.kinds[DEEPSLATE] = i == 0 ? 3 : 0;
            s.born = System.currentTimeMillis() - 2000;
            fakeLight(s, i);
            if (i == 0) {
                s.crypto = true;
                s.cryptoKinds[C_AMETHYST] = 4;
                scoreCrypto(s);
            } else score(s);
            out.add(s);
        }
        return out;
    }

    /* ------------------------------------------------------------------ render */

    @Override
    public void renderWorld(voided.events.impl.WorldRenderEvent event) {
        if (!inWorld()) return;
        List<Sus> list = flagged;
        long now = System.currentTimeMillis();
        double y = followHeight.on() ? Math.floor(mc.player.getY()) + 0.05 : height.value;
        if (!sober() && !devFake) drawDeepslate();
        cards.begin();
        for (Sus s : list) {
            long k = ChunkPos.toLong(s.cx, s.cz);
            String title = mode.is("Single Chunk") || s.clusterSize <= 1 ? "Sus chunk" : "Sus chunk x" + s.clusterSize;
            cards.put(k, s.cx, s.cz, title, Math.round(s.chance * 100) + "%", (s.cx << 4) + 8.0, y, (s.cz << 4) + 8.0, s.born == 0 ? now : s.born);
            cards.info(k, s.reasons, (float) s.chance);
        }
        if (!sober() && !devFake && beeNests.on()) {
            // Crypto's orange bee nest chunks as amber plates under the main ones
            beeCards.begin();
            for (ChunkPos p : beeNestChunks) {
                long k = p.toLong();
                if (list.stream().anyMatch(s -> s.cx == p.x && s.cz == p.z)) continue;
                beeCards.put(k, p.x, p.z, "Bee nests", "", (p.x << 4) + 8.0, y - 0.02, (p.z << 4) + 8.0, now);
            }
            beeCards.style = cardStyle.is("Liquid Glass") ? 1 : cardStyle.is("Flat") ? 2 : 0;
            beeCards.connect = true;
            beeCards.drawWorld(y - 0.02, renderDistance.value, animations.on(), 0xFFA500, opacity.f() * 0.55f);
        }
        cards.style = cardStyle.is("Liquid Glass") ? 1 : cardStyle.is("Flat") ? 2 : 0;
        cards.connect = true; // touching chunks always fuse into one rounded plate, like Crypto's
        cards.drawWorld(y, renderDistance.value, animations.on(), tint.rgb(), opacity.f());
    }

    @Override
    public void render2d(float tickDelta) {
        cards.drawOverlay(name, tracers.on(), labels.on(), list, tint.rgb(), opacity.f());
    }

    /* ------------------------------------------------------------------ buried activity (DonutSMP only) */

    /**
     * DonutSMP's anti-xray hides every block under the surface, but a budding amethyst that grows a bud has to tell the clients
     * about the new block, and the anti-xray reveals the blocks around a changed one. So amethyst, buds, clusters, budding amethyst
     * and calcite arriving as block updates, with no way for us to have seen them, mean a geode growing right there. Nothing is
     * read from light or chunk data; it only listens to the update stream.
     */
    static final class HiddenLight {
        private HiddenLight() {
        }

        /** True for donutsmp.net and its subdomains (never gated on the brand alone). */
        static boolean onDonut() {
            var e = net.minecraft.client.MinecraftClient.getInstance().getCurrentServerEntry();
            if (e == null || e.address == null) return false;
            String a = e.address.trim().toLowerCase(Locale.ROOT);
            int colon = a.lastIndexOf(':');
            if (colon > 0 && a.indexOf(':') == colon) a = a.substring(0, colon);
            while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
            final String d = "donutsmp.net";
            return a.equals(d) || (a.endsWith(d) && a.length() > d.length() && a.charAt(a.length() - d.length() - 1) == '.');
        }

        static boolean allowed() {
            return devFake || onDonut();
        }
    }

    private static final int LIGHT_HEAT_MIN = 4, DEPTH_MARGIN = 5;

    /** Latched evidence of one chunk. */
    private static final class Intel {
        volatile int ent, be, ev, y = Integer.MAX_VALUE; // ev = amethyst updates

        int heat() {
            return Math.min(24, ev * 8);
        }
    }

    private final Setting.Bool probe = bool("Block Probe", false).showIf(() -> HiddenLight.allowed());
    private final java.util.ArrayDeque<Long> probeQueue = new java.util.ArrayDeque<>();
    private final Set<Long> probeChunks = new java.util.HashSet<>();
    private int probeScan;

    /**
     * Out-of-reach dig packets: the server answers every one with the real state of that position (it "corrects" the client), and the
     * anti-xray does not rewrite single block updates. The answers come back through the normal update stream, so the amethyst
     * watcher sees them. A coarse lattice (every 4th block, Y -58..30) of the chunks around you is walked slowly, nearest first.
     */
    private void probeTick() {
        if (!probe.on() || !lightActive() || devFake || mc.world == null || mc.player == null || mc.getNetworkHandler() == null) return;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        if (++probeScan % 100 == 1) {
            List<int[]> fresh = new ArrayList<>();
            for (int dx = -6; dx <= 6; dx++)
                for (int dz = -6; dz <= 6; dz++) {
                    int cx = pcx + dx, cz = pcz + dz;
                    if (probeChunks.contains(ChunkPos.toLong(cx, cz)) || mc.world.getChunkManager().getWorldChunk(cx, cz) == null) continue;
                    fresh.add(new int[]{cx, cz, dx * dx + dz * dz});
                }
            fresh.sort(java.util.Comparator.comparingInt(a -> a[2]));
            for (int[] c : fresh) {
                probeChunks.add(ChunkPos.toLong(c[0], c[1]));
                for (int y = -58; y <= 30; y += 4)
                    for (int x = 2; x < 16; x += 4)
                        for (int z = 2; z < 16; z += 4) probeQueue.add(BlockPos.asLong((c[0] << 4) + x, y, (c[1] << 4) + z));
            }
        }
        var eye = mc.player.getEyePos();
        int sent = 0, looked = 0;
        while (sent < 8 && looked++ < 80 && (!probeHot.isEmpty() || !probeQueue.isEmpty())) {
            boolean hot = !probeHot.isEmpty();
            BlockPos bp = BlockPos.fromLong(hot ? probeHot.poll() : probeQueue.poll());
            if (probed.contains(bp.asLong())) continue;
            if (eye.squaredDistanceTo(bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5) < 100) {
                probeQueue.add(bp.asLong()); // too close: it would be a real dig, try again later when we are elsewhere
                continue;
            }
            if (bp.getY() > mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, bp.getX(), bp.getZ()) - 2) continue;
            probed.add(bp.asLong());
            sent++;
            mc.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket(
                    net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, bp, net.minecraft.util.math.Direction.UP, 0));
        }
    }

    /** A geode block answered: map the whole geode around it with a dense local lattice, first in line. */
    private void probeRefine(BlockPos hit) {
        if (!probe.on()) return;
        long cell = BlockPos.asLong(hit.getX() >> 3, hit.getY() >> 3, hit.getZ() >> 3);
        if (!refined.add(cell) || refined.size() > 400) return;
        for (int dy = -8; dy <= 8; dy += 2)
            for (int dx = -8; dx <= 8; dx += 2)
                for (int dz = -8; dz <= 8; dz += 2) {
                    BlockPos q = hit.add(dx, dy, dz);
                    if (q.getY() < -60 || probed.contains(q.asLong())) continue;
                    probeHot.add(q.asLong());
                }
    }

    private final java.util.ArrayDeque<Long> probeHot = new java.util.ArrayDeque<>();
    private final Set<Long> probed = new java.util.HashSet<>();
    private final Set<Long> refined = new java.util.HashSet<>();

    private final ConcurrentHashMap<ChunkPos, Intel> intelRaw = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Integer> lightHeatmap = new ConcurrentHashMap<>();
    private int lightTicks;

    private boolean lightActive() {
        return HiddenLight.allowed() && !sober();
    }

    private void lightClear() {
        intelRaw.clear();
        lightHeatmap.clear();
        palScanned.clear();
        probeQueue.clear();
        probeHot.clear();
        probed.clear();
        refined.clear();
        probeChunks.clear();
        lightTicks = 0;
    }

    private Intel intelAt(int cx, int cz) {
        return intelRaw.computeIfAbsent(new ChunkPos(cx, cz), k -> new Intel());
    }

    /** True when the client sees solid stone at a spot well under the surface: whatever happens there is hidden from us. */
    private boolean buriedAt(ClientWorld w, BlockPos p) {
        if (!w.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4) || w.isOutOfHeightLimit(p.getY())) return false;
        if (p.getY() > w.getTopY(Heightmap.Type.WORLD_SURFACE, p.getX(), p.getZ()) - DEPTH_MARGIN) return false;
        return w.getBlockState(p).isSolidBlock(w, p);
    }

    /** Main thread, from the block update stream: amethyst family blocks appearing under the surface. */
    private void amethystUpdate(BlockPos pos, BlockState now) {
        if (devFake || !lightActive() || mc.world == null) return;
        var b = now.getBlock();
        boolean amy = b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.LARGE_AMETHYST_BUD
                || b == Blocks.AMETHYST_CLUSTER || b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_BLOCK || b == Blocks.CALCITE || b == Blocks.SMOOTH_BASALT;
        if (!amy) return;
        if (pos.getY() > mc.world.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ()) - 2) return; // not a surface one
        Intel in = intelAt(pos.getX() >> 4, pos.getZ() >> 4);
        int add = 1;
        if (add == 0 && in.ev == 0) return;
        if (in.ev < 12) in.ev += add;
        probeRefine(pos);
        in.y = Math.min(in.y, pos.getY());
        lightDirty = true;
    }

    private int palTick;
    private final Set<Long> palScanned = new java.util.HashSet<>();

    /**
     * Palette read: the chunk packet lists the block types of every 16x16x16 section in its palette. A server that swaps the hidden
     * blocks for stone often forgets to shrink that list, so a geode section still names amethyst / budding amethyst / calcite /
     * smooth basalt even though no such block is in the data. Only the palette is asked, never a block position.
     */
    private void paletteSweep() {
        ClientWorld w = mc.world;
        if (w == null || mc.player == null) return;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        int r = Math.min(mc.options.getViewDistance().getValue(), 32), budget = 60;
        for (int dx = -r; dx <= r && budget > 0; dx++)
            for (int dz = -r; dz <= r && budget > 0; dz++) {
                long key = ChunkPos.toLong(pcx + dx, pcz + dz);
                if (palScanned.contains(key)) continue;
                var c = w.getChunkManager().getWorldChunk(pcx + dx, pcz + dz);
                if (c == null) continue;
                palScanned.add(key);
                budget--;
                var secs = c.getSectionArray();
                int hits = 0, minY = Integer.MAX_VALUE, kinds = 0;
                for (int i = 0; i < secs.length; i++) {
                    var sec = secs[i];
                    if (sec == null) continue;
                    boolean amy = sec.hasAny(st -> { var b = st.getBlock();
                        return b == Blocks.AMETHYST_BLOCK || b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_CLUSTER
                                || b == Blocks.LARGE_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.SMALL_AMETHYST_BUD; });
                    boolean cal = sec.hasAny(st -> st.getBlock() == Blocks.CALCITE);
                    boolean bas = sec.hasAny(st -> st.getBlock() == Blocks.SMOOTH_BASALT);
                    if (!(amy || (cal && bas))) continue;
                    hits++;
                    kinds += (amy ? 2 : 0) + (cal && bas ? 1 : 0);
                    minY = Math.min(minY, (c.sectionIndexToCoord(i) << 4) + 8);
                }
                if (hits == 0 || hits > 3) continue; // every section naming it = a global palette, tells nothing
                Intel in = intelAt(pcx + dx, pcz + dz);
                in.ev = Math.max(in.ev, Math.min(12, kinds + hits));
                in.y = Math.min(in.y, minY);
                lightDirty = true;
            }
    }

    private boolean lightDirty;

    /** Main thread: rebuild the heat map when new updates came in. */
    private void lightTick() {
        if (devFake || mc.world == null || mc.player == null) return;
        if (!lightActive()) {
            if (!lightHeatmap.isEmpty() || !intelRaw.isEmpty()) lightClear();
            return;
        }
        if (!lightDirty) return;
        lightDirty = false;
        lightHeatmap.clear();
        for (var e : intelRaw.entrySet()) {
            int h = e.getValue().heat();
            if (h <= 0) continue;
            ChunkPos p = e.getKey();
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    int add = (dx == 0 && dz == 0) ? h : h / 2;
                    if (add > 0) lightHeatmap.merge(new ChunkPos(p.x + dx, p.z + dz), add, Math::max);
                }
        }
    }

    /** Fill the buried evidence of a verdict chunk from its own chunk and the ring around it. */
    private void lightEvidence(Sus s) {
        if (!lightActive()) return;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                Intel in = intelRaw.get(new ChunkPos(s.cx + dx, s.cz + dz));
                if (in == null) continue;
                s.intelEnt += in.ent;
                s.intelBe += in.be;
                s.intelEv += in.ev;
                if (in.y != Integer.MAX_VALUE) s.intelY = s.intelY == Integer.MAX_VALUE ? in.y : Math.min(s.intelY, in.y);
            }
    }

    /** Dev screenshot helper: fake buried evidence on the first fake chunk. */
    private static void fakeLight(Sus s, int i) {
        if (i == 0) {
            s.intelEnt = 3;
            s.intelBe = 2;
            s.intelEv = 4;
            s.intelY = 12;
        }
    }

    /** Block and light reads straight from the loaded chunks (the scan runs off-thread). */
    private static final class Reader {
        private final ClientWorld level;
        private WorldChunk chunk;

        Reader(ClientWorld level) {
            this.level = level;
        }

        BlockState state(int x, int y, int z) {
            if (level.isOutOfHeightLimit(y)) return Blocks.VOID_AIR.getDefaultState();
            WorldChunk at = chunkAt(x >> 4, z >> 4);
            if (at == null) return Blocks.VOID_AIR.getDefaultState();
            ChunkSection s = at.getSectionArray()[at.getSectionIndex(y)];
            return s == null ? Blocks.VOID_AIR.getDefaultState() : s.getBlockState(x & 15, y & 15, z & 15);
        }

        private boolean emptySection(int x, int y, int z) {
            WorldChunk at = chunkAt(x >> 4, z >> 4);
            if (at == null) return true;
            ChunkSection s = at.getSectionArray()[at.getSectionIndex(y)];
            return s == null || s.isEmpty();
        }

        private WorldChunk chunkAt(int cx, int cz) {
            if (chunk != null && chunk.getPos().x == cx && chunk.getPos().z == cz) return chunk;
            WorldChunk loaded = level.getChunkManager().getWorldChunk(cx, cz);
            if (loaded != null) chunk = loaded;
            return loaded;
        }

        int light(int x, int y, int z) {
            int v = Math.max(raw(LightType.BLOCK, x, y, z), raw(LightType.SKY, x, y, z));
            if (v < 15 && level.getDimension().hasSkyLight()) return openSky(x, y, z) ? 15 : v;
            return v;
        }

        private int raw(LightType type, int x, int y, int z) {
            ChunkNibbleArray data = level.getLightingProvider().get(type).getLightSection(ChunkSectionPos.from(x >> 4, y >> 4, z >> 4));
            return data != null && !data.isArrayUninitialized() ? data.get(x & 15, y & 15, z & 15) : 0;
        }

        private boolean openSky(int x, int y, int z) {
            int top = level.getTopYInclusive();
            for (int ny = y + 1; ny <= top; ny++) {
                if (emptySection(x, ny, z)) ny |= 15;
                else if (state(x, ny, z).getOpacity() > 0) return false;
            }
            return true;
        }
    }
}

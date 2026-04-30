import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

final class VoxelWorld implements StructureTemplates.Target {
    private static final int SAVE_FORMAT_VERSION = 4;
    private static final int CONTAINER_SAVE_MAGIC = 0x544D4354;
    private static final int CONTAINER_SAVE_VERSION = 3;
    private static final int PLAYER_INVENTORY_SAVE_MAGIC = 0x544D5049;
    private static final int PLAYER_INVENTORY_SAVE_VERSION = 1;
    private static final double ZOMBIE_GROWL_DISTANCE_SQUARED = 11.0 * 11.0;
    private static final double COLLISION_EPSILON = 1.0e-7;
    private static final int ZOMBIE_TARGET_COUNT = 18;
    private static final int MAX_ASYNC_COLUMN_SUBMISSIONS_PER_TICK = Math.max(16, GameConfig.CHUNK_GENERATION_THREADS * 4);
    private static final int MAX_PENDING_COLUMN_TASKS = Math.max(96, GameConfig.CHUNK_GENERATION_THREADS * 24);

    static final class ChunkColumn {
        final int chunkX;
        final int chunkZ;
        final Chunk[] sections = new Chunk[GameConfig.SECTION_COUNT];
        final short[] surfaceHeights = new short[Chunk.SIZE * Chunk.SIZE];
        ChunkGenerationStatus status = ChunkGenerationStatus.EMPTY;
        boolean dirty;
        boolean generated;
        boolean naturalTerrain;

        ChunkColumn(int chunkX, int chunkZ) {
            this(chunkX, chunkZ, true);
        }

        ChunkColumn(int chunkX, int chunkZ, boolean allocateSections) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            if (allocateSections) {
                for (int chunkY = 0; chunkY < sections.length; chunkY++) {
                    sections[chunkY] = new Chunk(chunkX, chunkY, chunkZ);
                }
            }
        }

        Chunk section(int chunkY) {
            if (!GameConfig.isChunkCoordinateInside(chunkX, chunkY, chunkZ)) {
                return null;
            }
            return sections[chunkY];
        }

        int getSurfaceHeightLocal(int localX, int localZ) {
            return surfaceHeights[indexSurface(localX, localZ)];
        }

        void setSurfaceHeightLocal(int localX, int localZ, int height) {
            surfaceHeights[indexSurface(localX, localZ)] = (short) height;
        }

        private static int indexSurface(int localX, int localZ) {
            return localZ * Chunk.SIZE + localX;
        }
    }

    private static final class PendingBlockChange {
        final int x;
        final int y;
        final int z;
        final byte block;
        final int distance;

        PendingBlockChange(int x, int y, int z, byte block, int distance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.distance = distance;
        }
    }

    private static final class CachedFluidFlow {
        final double x;
        final double y;
        final double z;

        CachedFluidFlow(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private long seed;
    private Path worldDirectory;
    private Path saveDirectory;
    private RegionStorage regionStorage;
    private final ConcurrentHashMap<Long, ChunkColumn> loadedColumns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Future<ChunkColumn>> pendingColumns = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor generationExecutor = createGenerationExecutor();
    private final int[] permutation = new int[512];
    private final ArrayList<Zombie> zombies = new ArrayList<>();
    private final HashSet<Long> populatedVillageCells = new HashSet<>();
    private final ArrayList<DroppedItem> droppedItems = new ArrayList<>();
    private final ArrayList<FallingBlock> fallingBlocks = new ArrayList<>();
    private final HashMap<Long, ContainerInventory> chestContainers = new HashMap<>();
    private final HashMap<Long, FurnaceBlockEntity> furnaces = new HashMap<>();
    private final HashSet<Long> activeWaterCells = new HashSet<>();
    private final HashSet<Long> activeLavaCells = new HashSet<>();
    private final HashSet<Long> activeSandCells = new HashSet<>();
    private final HashMap<Long, Double> leafDecayTimers = new HashMap<>();
    private final HashSet<Long> fallingBlockSources = new HashSet<>();
    private final HashSet<Long> dirtyBlockColumns = new HashSet<>();
    private final ColumnUpdateList simulationDirtyColumns = new ColumnUpdateList(64);
    private final MutableVec2 zombieSteering = new MutableVec2();
    private final MutableVec2 zombieSeparation = new MutableVec2();
    private final MutableVec3 sampledFluidFlow = new MutableVec3();
    private final MutableVec3 blockFluidFlow = new MutableVec3();
    private final HashMap<Long, CachedFluidFlow> waterFlowCache = new HashMap<>();
    private Random worldRandom;
    private WorldGenerator worldGenerator;
    private double simulationAccumulator;
    private double zombieSpawnCooldown;
    private double worldTime = 0.30;
    private long worldTickCounter;
    private int renderDistanceChunks = GameConfig.CHUNK_RENDER_DISTANCE;
    private int streamingWarmupFrames;
    private int lastStreamingChunkX = Integer.MIN_VALUE;
    private int lastStreamingChunkZ = Integer.MIN_VALUE;
    private long nextColumnTaskSequence;

    private static ThreadPoolExecutor createGenerationExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            GameConfig.CHUNK_GENERATION_THREADS,
            GameConfig.CHUNK_GENERATION_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>()
        );
        return executor;
    }

    private static final class PrioritizedColumnTask extends FutureTask<ChunkColumn> implements Comparable<PrioritizedColumnTask> {
        final int priority;
        final long sequence;

        PrioritizedColumnTask(Callable<ChunkColumn> callable, int priority, long sequence) {
            super(callable);
            this.priority = priority;
            this.sequence = sequence;
        }

        @Override
        public int compareTo(PrioritizedColumnTask other) {
            int priorityCompare = Integer.compare(priority, other.priority);
            return priorityCompare != 0 ? priorityCompare : Long.compare(sequence, other.sequence);
        }
    }

    VoxelWorld(long seed) {
        configureWorld(RuntimePaths.resolve(GameConfig.SAVE_ROOT_DIRECTORY, "World 1"), seed);
    }

    long getSeed() {
        return seed;
    }

    void configureWorld(Path worldDirectory, long seed) {
        if (this.worldDirectory != null && !this.worldDirectory.equals(worldDirectory)) {
            flushLoadedColumns();
            for (Future<ChunkColumn> future : pendingColumns.values()) {
                future.cancel(true);
            }
            pendingColumns.clear();
            loadedColumns.clear();
            activeWaterCells.clear();
            activeLavaCells.clear();
            activeSandCells.clear();
            leafDecayTimers.clear();
            fallingBlockSources.clear();
            zombies.clear();
            droppedItems.clear();
            fallingBlocks.clear();
            saveContainers();
            chestContainers.clear();
            furnaces.clear();
        }
        this.seed = seed;
        this.worldDirectory = worldDirectory;
        this.saveDirectory = worldDirectory.resolve(GameConfig.SAVE_CHUNKS_DIRECTORY);
        this.regionStorage = new RegionStorage(worldDirectory);
        this.worldRandom = new Random(seed ^ 0x7A21AF13D54E3B21L);
        this.worldGenerator = new WorldGenerator(seed);
        this.worldTime = 0.30;
        this.streamingWarmupFrames = 0;
        this.lastStreamingChunkX = Integer.MIN_VALUE;
        this.lastStreamingChunkZ = Integer.MIN_VALUE;
        waterFlowCache.clear();
        leafDecayTimers.clear();
        loadContainers();
    }

    List<Zombie> getZombies() {
        return zombies;
    }

    List<DroppedItem> getDroppedItems() {
        return droppedItems;
    }

    List<FallingBlock> getFallingBlocks() {
        return fallingBlocks;
    }

    void spawnZombieAt(double x, double y, double z) {
        double spawnY = Math.max(GameConfig.WORLD_MIN_Y + 1.0, Math.min(GameConfig.WORLD_MAX_Y - GameConfig.ZOMBIE_HEIGHT, y));
        zombies.add(new Zombie(x, spawnY, z, x, z, worldRandom));
    }

    void spawnMobAt(MobKind kind, double x, double y, double z) {
        double spawnY = Math.max(GameConfig.WORLD_MIN_Y + 1.0, Math.min(GameConfig.WORLD_MAX_Y - GameConfig.ZOMBIE_HEIGHT, y));
        zombies.add(new Zombie(kind, x, spawnY, z, x, z, worldRandom));
    }

    void setRenderDistanceChunks(int renderDistanceChunks) {
        this.renderDistanceChunks = clamp(renderDistanceChunks, GameConfig.MIN_RENDER_DISTANCE, GameConfig.MAX_RENDER_DISTANCE_CHUNKS);
    }

    Chunk getChunk(int chunkX, int chunkY, int chunkZ) {
        ChunkColumn column = loadedColumns.get(columnKey(chunkX, chunkZ));
        if (column == null) {
            return null;
        }
        return column.section(chunkY);
    }

    boolean isChunkEmpty(int chunkX, int chunkY, int chunkZ) {
        Chunk chunk = getChunk(chunkX, chunkY, chunkZ);
        return chunk == null || chunk.isEmpty();
    }

    boolean isChunkLoaded(int chunkX, int chunkZ) {
        return loadedColumns.containsKey(columnKey(chunkX, chunkZ));
    }

    void fillLoadedChunksSnapshot(List<Chunk> chunks) {
        chunks.clear();
        if (chunks instanceof ArrayList) {
            ((ArrayList<Chunk>) chunks).ensureCapacity(loadedColumns.size() * GameConfig.SECTION_COUNT);
        }
        for (ChunkColumn column : loadedColumns.values()) {
            for (Chunk chunk : column.sections) {
                chunks.add(chunk);
            }
        }
    }

    void initializeNoise() {
        int[] values = new int[256];
        for (int i = 0; i < values.length; i++) {
            values[i] = i;
        }

        Random random = new Random(seed);
        for (int i = values.length - 1; i > 0; i--) {
            int swapIndex = random.nextInt(i + 1);
            int temporary = values[i];
            values[i] = values[swapIndex];
            values[swapIndex] = temporary;
        }

        for (int i = 0; i < permutation.length; i++) {
            permutation[i] = values[i & 255];
        }
        worldGenerator = new WorldGenerator(seed);
    }

    void saveAllLoadedColumns() {
        flushLoadedColumns();
        saveContainers();
    }

    void discardLoadedWorld() {
        for (Future<ChunkColumn> future : pendingColumns.values()) {
            future.cancel(true);
        }
        pendingColumns.clear();
        loadedColumns.clear();
        activeWaterCells.clear();
        activeLavaCells.clear();
        activeSandCells.clear();
        leafDecayTimers.clear();
        fallingBlockSources.clear();
        dirtyBlockColumns.clear();
        simulationDirtyColumns.clear();
        waterFlowCache.clear();
        zombies.clear();
        populatedVillageCells.clear();
        droppedItems.clear();
        fallingBlocks.clear();
        saveContainers();
        chestContainers.clear();
        furnaces.clear();
        worldDirectory = null;
        saveDirectory = null;
        regionStorage = null;
    }

    void generateWorld() {
        flushLoadedColumns();
        loadedColumns.clear();
        pendingColumns.clear();
        activeWaterCells.clear();
        activeLavaCells.clear();
        activeSandCells.clear();
        leafDecayTimers.clear();
        fallingBlockSources.clear();
        waterFlowCache.clear();
        dirtyBlockColumns.clear();
        simulationDirtyColumns.clear();
        zombies.clear();
        populatedVillageCells.clear();
        droppedItems.clear();
        fallingBlocks.clear();
        chestContainers.clear();
        furnaces.clear();
        simulationAccumulator = 0.0;
        zombieSpawnCooldown = 20.0;
        worldTickCounter = 0L;
        worldTime = 0.30;
        streamingWarmupFrames = 0;
        lastStreamingChunkX = Integer.MIN_VALUE;
        lastStreamingChunkZ = Integer.MIN_VALUE;

        try {
            Files.createDirectories(worldDirectory.resolve(GameConfig.SAVE_REGION_DIRECTORY));
        } catch (IOException exception) {
            if (GameConfig.ENABLE_DEBUG_LOGS) {
                System.out.println("VoxelWorld: failed to create save directory: " + exception.getMessage());
            }
        }

        ensureColumnsAround(0, 0, GameConfig.INITIAL_CHUNK_SYNC_RADIUS, true);
    }

    void prepareForPlayer(PlayerState player) {
        if (player == null) {
            return;
        }
        int playerChunkX = worldToChunk((int) Math.floor(player.x));
        int playerChunkZ = worldToChunk((int) Math.floor(player.z));
        if (playerChunkX != lastStreamingChunkX || playerChunkZ != lastStreamingChunkZ) {
            lastStreamingChunkX = playerChunkX;
            lastStreamingChunkZ = playerChunkZ;
            streamingWarmupFrames = Math.min(streamingWarmupFrames, 80);
        }
        streamingWarmupFrames = Math.min(600, streamingWarmupFrames + 1);
        drainGeneratedColumns();
        int streamingRadius = getStreamingRadius(player);
        ensureColumnsAround(playerChunkX, playerChunkZ, streamingRadius, false);
        unloadFarColumns(playerChunkX, playerChunkZ, streamingRadius + 2);
        cancelFarPendingColumns(playerChunkX, playerChunkZ, streamingRadius + 4);
    }

    ColumnUpdateList updateWorldTicks(PlayerState player, double deltaTime) {
        simulationDirtyColumns.clear();
        dirtyBlockColumns.clear();
        drainGeneratedColumns();

        updateFallingBlocks(deltaTime);
        tickLeafDecay(deltaTime);
        updateFurnaces(deltaTime);

        simulationAccumulator += deltaTime;
        while (simulationAccumulator >= GameConfig.WORLD_TICK_INTERVAL) {
            simulationAccumulator -= GameConfig.WORLD_TICK_INTERVAL;
            tickDynamicBlocks(player);
            worldTickCounter++;
        }

        return simulationDirtyColumns;
    }

    double getWorldTime() {
        return worldTime;
    }

    void setWorldTime(double worldTime) {
        this.worldTime = normalizeWorldTime(worldTime);
    }

    void advanceWorldTime(double deltaTime) {
        setWorldTime(worldTime + deltaTime / GameConfig.DAY_LENGTH_SECONDS);
    }

    void placePlayerAtSpawn(PlayerState player) {
        ensureColumnsAround(0, 0, 1, true);
        int bestX = 0;
        int bestZ = 0;
        int bestY = Integer.MIN_VALUE;

        for (int radius = 0; radius <= 64; radius += 4) {
            boolean found = false;
            for (int offsetX = -radius; offsetX <= radius && !found; offsetX += 4) {
                for (int offsetZ = -radius; offsetZ <= radius; offsetZ += 4) {
                    if (Math.abs(offsetX) != radius && Math.abs(offsetZ) != radius) {
                        continue;
                    }
                    int x = offsetX;
                    int z = offsetZ;
                    ChunkColumn spawnColumn = ensureColumnGeneratedSync(worldToChunk(x), worldToChunk(z));
                    if (spawnColumn == null || !spawnColumn.generated) {
                        continue;
                    }
                    int surfaceY = findSpawnGroundY(x, z);
                    byte ground = getBlock(x, surfaceY, z);
                    if (surfaceY <= GameConfig.WORLD_MIN_Y
                        || !isSpawnGroundBlock(ground)
                        || getBlock(x, surfaceY + 1, z) != GameConfig.AIR
                        || getBlock(x, surfaceY + 2, z) != GameConfig.AIR) {
                        continue;
                    }
                    bestX = x;
                    bestY = surfaceY;
                    bestZ = z;
                    found = true;
                    break;
                }
            }
            if (found) {
                break;
            }
        }

        if (bestY == Integer.MIN_VALUE) {
            bestX = 0;
            bestZ = 0;
            bestY = GameConfig.SEA_LEVEL;
            ensureColumnGeneratedSync(worldToChunk(bestX), worldToChunk(bestZ));
            createSpawnIsland(bestX, bestY, bestZ);
        }

        player.x = bestX + 0.5;
        player.z = bestZ + 0.5;
        player.y = getStandingY(player.x, player.z, bestY + 1.0);
    }

    private int findSpawnGroundY(int x, int z) {
        for (int y = GameConfig.WORLD_MAX_Y; y >= GameConfig.WORLD_MIN_Y + 1; y--) {
            byte block = getBlock(x, y, z);
            if (GameConfig.isLiquidBlock(block)) {
                continue;
            }
            if (isSpawnGroundBlock(block)) {
                return y;
            }
        }
        return GameConfig.WORLD_MIN_Y;
    }

    private boolean isSpawnGroundBlock(byte block) {
        return block == GameConfig.GRASS
            || block == GameConfig.SAND
            || block == GameConfig.STONE
            || block == GameConfig.DEEPSLATE
            || block == GameConfig.COBBLESTONE;
    }

    private void createSpawnIsland(int centerX, int surfaceY, int centerZ) {
        for (int offsetX = -4; offsetX < 4; offsetX++) {
            for (int offsetZ = -4; offsetZ < 4; offsetZ++) {
                int x = centerX + offsetX;
                int z = centerZ + offsetZ;
                ensureColumnGeneratedSync(worldToChunk(x), worldToChunk(z));
                setBlockState(x, surfaceY - 2, z, GameConfig.COBBLESTONE, -1);
                setBlockState(x, surfaceY - 1, z, GameConfig.DIRT, -1);
                setBlockState(x, surfaceY, z, GameConfig.SAND, -1);
                setBlockState(x, surfaceY + 1, z, GameConfig.AIR, -1);
                setBlockState(x, surfaceY + 2, z, GameConfig.AIR, -1);
                refreshDynamicCellsAround(x, surfaceY, z);
                refreshSurfaceHeight(x, z);
            }
        }
    }

    boolean loadPlayerState(PlayerState player) {
        return loadPlayerState(player, null);
    }

    boolean loadPlayerState(PlayerState player, PlayerInventory inventory) {
        if (player == null || worldDirectory == null) {
            return false;
        }
        Path playerPath = worldDirectory.resolve(GameConfig.SAVE_PLAYER_FILE);
        if (!Files.isRegularFile(playerPath)) {
            return false;
        }

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(playerPath)))) {
            double savedX = input.readDouble();
            double savedY = input.readDouble();
            double savedZ = input.readDouble();
            double savedYaw = input.readDouble();
            double savedPitch = input.readDouble();
            if (!Double.isFinite(savedX) || !Double.isFinite(savedY) || !Double.isFinite(savedZ)
                || !Double.isFinite(savedYaw) || !Double.isFinite(savedPitch)) {
                return false;
            }
            player.x = savedX;
            player.y = savedY;
            player.z = savedZ;
            player.yaw = savedYaw;
            player.pitch = savedPitch;
            if (input.available() >= 2) {
                int savedMode = input.readUnsignedByte();
                boolean savedFlightEnabled = input.readBoolean();
                player.creativeMode = savedMode == 1;
                player.spectatorMode = savedMode == 2;
                player.flightEnabled = savedFlightEnabled;
            } else {
                player.creativeMode = false;
                player.spectatorMode = false;
                player.flightEnabled = false;
            }
            if (input.available() >= 41) {
                player.health = clamp(input.readDouble(), 0.0, GameConfig.MAX_HEALTH);
                player.hunger = clamp(input.readDouble(), 0.0, GameConfig.MAX_HUNGER);
            } else {
                if (input.available() >= 4) {
                    player.health = clamp(input.readInt(), 0.0, GameConfig.MAX_HEALTH);
                }
                if (input.available() >= 4) {
                    player.hunger = clamp(input.readInt(), 0.0, GameConfig.MAX_HUNGER);
                }
            }
            if (input.available() >= 25) {
                player.hasCustomSpawn = input.readBoolean();
                player.spawnX = input.readDouble();
                player.spawnY = input.readDouble();
                player.spawnZ = input.readDouble();
            }
            if (inventory != null && input.available() >= 8) {
                int magic = input.readInt();
                int version = input.readInt();
                if (magic == PLAYER_INVENTORY_SAVE_MAGIC && version >= 1 && version <= PLAYER_INVENTORY_SAVE_VERSION) {
                    readPlayerInventory(input, inventory);
                }
            }
            return true;
        } catch (IOException exception) {
            if (GameConfig.ENABLE_DEBUG_LOGS) {
                System.out.println("VoxelWorld: failed to load player state: " + exception.getMessage());
            }
            return false;
        }
    }

    void savePlayerState(PlayerState player) {
        savePlayerState(player, null);
    }

    void savePlayerState(PlayerState player, PlayerInventory inventory) {
        if (player == null || worldDirectory == null) {
            return;
        }

        try {
            Files.createDirectories(worldDirectory);
            Path playerPath = worldDirectory.resolve(GameConfig.SAVE_PLAYER_FILE);
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(playerPath)))) {
                output.writeDouble(player.x);
                output.writeDouble(player.y);
                output.writeDouble(player.z);
                output.writeDouble(player.yaw);
                output.writeDouble(player.pitch);
                output.writeByte(player.spectatorMode ? 2 : (player.creativeMode ? 1 : 0));
                output.writeBoolean(player.flightEnabled);
                output.writeDouble(clamp(player.health, 0.0, GameConfig.MAX_HEALTH));
                output.writeDouble(clamp(player.hunger, 0.0, GameConfig.MAX_HUNGER));
                output.writeBoolean(player.hasCustomSpawn);
                output.writeDouble(player.spawnX);
                output.writeDouble(player.spawnY);
                output.writeDouble(player.spawnZ);
                if (inventory != null) {
                    output.writeInt(PLAYER_INVENTORY_SAVE_MAGIC);
                    output.writeInt(PLAYER_INVENTORY_SAVE_VERSION);
                    writePlayerInventory(output, inventory);
                }
            }
        } catch (IOException exception) {
            if (GameConfig.ENABLE_DEBUG_LOGS) {
                System.out.println("VoxelWorld: failed to save player state: " + exception.getMessage());
            }
        }
    }

    private void loadContainers() {
        chestContainers.clear();
        furnaces.clear();
        if (worldDirectory == null) {
            return;
        }
        Path path = worldDirectory.resolve("containers.dat");
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int header = input.readInt();
            int version = header == CONTAINER_SAVE_MAGIC ? input.readInt() : header;
            if (version < 1 || version > CONTAINER_SAVE_VERSION) {
                return;
            }
            int chestCount = input.readInt();
            for (int i = 0; i < chestCount; i++) {
                long key = input.readLong();
                ContainerInventory container = new ContainerInventory(27);
                for (ItemStack stack : container.slots) {
                    readStack(input, stack, version);
                }
                chestContainers.put(key, container);
            }
            int furnaceCount = input.readInt();
            for (int i = 0; i < furnaceCount; i++) {
                long key = input.readLong();
                FurnaceBlockEntity furnace = new FurnaceBlockEntity();
                readStack(input, furnace.input, version);
                readStack(input, furnace.fuel, version);
                readStack(input, furnace.output, version);
                furnace.burnRemaining = input.readDouble();
                furnace.burnTotal = input.readDouble();
                furnace.cookProgress = input.readDouble();
                furnace.cookTotal = input.readDouble();
                furnaces.put(key, furnace);
            }
        } catch (IOException ignored) {
            chestContainers.clear();
            furnaces.clear();
        }
    }

    private void saveContainers() {
        if (worldDirectory == null) {
            return;
        }
        try {
            Files.createDirectories(worldDirectory);
            Path path = worldDirectory.resolve("containers.dat");
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
                output.writeInt(CONTAINER_SAVE_MAGIC);
                output.writeInt(CONTAINER_SAVE_VERSION);
                ArrayList<Map.Entry<Long, ContainerInventory>> nonEmptyChests = new ArrayList<>();
                for (Map.Entry<Long, ContainerInventory> entry : chestContainers.entrySet()) {
                    if (!isContainerEmpty(entry.getValue())) {
                        nonEmptyChests.add(entry);
                    }
                }
                ArrayList<Map.Entry<Long, FurnaceBlockEntity>> nonEmptyFurnaces = new ArrayList<>();
                for (Map.Entry<Long, FurnaceBlockEntity> entry : furnaces.entrySet()) {
                    if (!isFurnaceEmpty(entry.getValue())) {
                        nonEmptyFurnaces.add(entry);
                    }
                }
                output.writeInt(nonEmptyChests.size());
                for (Map.Entry<Long, ContainerInventory> entry : nonEmptyChests) {
                    output.writeLong(entry.getKey());
                    for (ItemStack stack : entry.getValue().slots) {
                        writeStack(output, stack);
                    }
                }
                output.writeInt(nonEmptyFurnaces.size());
                for (Map.Entry<Long, FurnaceBlockEntity> entry : nonEmptyFurnaces) {
                    FurnaceBlockEntity furnace = entry.getValue();
                    output.writeLong(entry.getKey());
                    writeStack(output, furnace.input);
                    writeStack(output, furnace.fuel);
                    writeStack(output, furnace.output);
                    output.writeDouble(furnace.burnRemaining);
                    output.writeDouble(furnace.burnTotal);
                    output.writeDouble(furnace.cookProgress);
                    output.writeDouble(furnace.cookTotal);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void writeStack(DataOutputStream output, ItemStack stack) throws IOException {
        output.writeByte(stack == null || stack.isEmpty() ? GameConfig.AIR : stack.itemId);
        output.writeInt(stack == null || stack.isEmpty() ? 0 : stack.count);
        output.writeInt(stack == null || stack.isEmpty() ? 0 : stack.durabilityDamage);
    }

    private boolean isContainerEmpty(ContainerInventory container) {
        if (container == null) {
            return true;
        }
        for (ItemStack stack : container.slots) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private boolean isFurnaceEmpty(FurnaceBlockEntity furnace) {
        return furnace == null
            || (furnace.input.isEmpty()
            && furnace.fuel.isEmpty()
            && furnace.output.isEmpty()
            && furnace.burnRemaining <= 0.0
            && furnace.cookProgress <= 0.0);
    }

    private void readStack(DataInputStream input, ItemStack stack, int version) throws IOException {
        byte item = input.readByte();
        int count = input.readInt();
        stack.set(item, count);
        if (version >= 3) {
            int damage = input.readInt();
            if (InventoryItems.isDurableItem(item)) {
                stack.durabilityDamage = Math.max(0, Math.min(damage, InventoryItems.maxDurability(item) - 1));
            } else {
                stack.durabilityDamage = 0;
            }
        }
    }

    void updateZombies(PlayerState player, double deltaTime) {
        if (player == null) {
            return;
        }

        zombieSpawnCooldown -= deltaTime;
        populateVillageResidentsNear(player);
        maybeSpawnZombieNearPlayer(player);

        for (int i = zombies.size() - 1; i >= 0; i--) {
            Zombie zombie = zombies.get(i);
            double despawnDistance = zombie.kind == MobKind.VILLAGER ? 220.0 : 96.0;
            if (zombie.health <= 0) {
                dropMobLoot(zombie);
                zombies.remove(i);
                continue;
            }
            if (distanceSquared(zombie.x, zombie.z, player.x, player.z) > despawnDistance * despawnDistance) {
                zombies.remove(i);
                continue;
            }

            boolean inWater = intersectsFluid(
                zombie.x, zombie.y, zombie.z,
                GameConfig.ZOMBIE_RADIUS, GameConfig.ZOMBIE_HEIGHT, GameConfig.WATER
            );
            boolean inLava = intersectsFluid(
                zombie.x, zombie.y, zombie.z,
                GameConfig.ZOMBIE_RADIUS, GameConfig.ZOMBIE_HEIGHT, GameConfig.LAVA
            );
            if (inWater != zombie.wasInWater) {
                zombie.splashQueued = true;
            }
            zombie.wasInWater = inWater;

            if (inLava) {
                zombie.fireTimer = Math.max(zombie.fireTimer, 3.0);
                zombie.fireDamageTimer -= deltaTime;
                if (zombie.fireDamageTimer <= 0.0) {
                    zombie.health -= GameConfig.LAVA_DAMAGE;
                    zombie.fireDamageTimer = GameConfig.LAVA_DAMAGE_INTERVAL;
                }
            } else if (isSunBurningMob(zombie, inWater)) {
                zombie.fireTimer = Math.max(zombie.fireTimer, 1.2);
                zombie.fireDamageTimer -= deltaTime;
                if (zombie.fireDamageTimer <= 0.0) {
                    zombie.health -= GameConfig.FIRE_DAMAGE;
                    zombie.fireDamageTimer = GameConfig.FIRE_DAMAGE_INTERVAL;
                }
            } else {
                zombie.fireTimer = Math.max(0.0, zombie.fireTimer - deltaTime);
            }
            zombie.hurtCooldown = Math.max(0.0, zombie.hurtCooldown - deltaTime);
            zombie.fleeTimer = Math.max(0.0, zombie.fleeTimer - deltaTime);
            zombie.loveTimer = Math.max(0.0, zombie.loveTimer - deltaTime);
            zombie.breedCooldown = Math.max(0.0, zombie.breedCooldown - deltaTime);

            updateZombieAi(zombie, player, deltaTime);
            updateZombieMovement(zombie, inWater, deltaTime);
            if (isHostileMob(zombie)) {
                maybeQueueZombieGrowl(zombie, player, deltaTime);
                tryZombieAttack(zombie, player);
            } else {
                maybeQueuePassiveMobSound(zombie, deltaTime);
            }
        }
    }

    private boolean isHostileMob(Zombie mob) {
        return mob.kind == MobKind.ZOMBIE || mob.kind == MobKind.SKELETON;
    }

    private boolean isPassiveMob(Zombie mob) {
        return mob != null && (mob.kind == MobKind.PIG || mob.kind == MobKind.SHEEP || mob.kind == MobKind.COW);
    }

    private void dropMobLoot(Zombie mob) {
        if (mob == null || mob.kind == MobKind.VILLAGER) {
            return;
        }
        switch (mob.kind) {
            case PIG:
                spawnDroppedItem(InventoryItems.RAW_PORK, 1 + worldRandom.nextInt(2), mob.x, mob.y + 0.45, mob.z);
                break;
            case COW:
                spawnDroppedItem(InventoryItems.RAW_BEEF, 1 + worldRandom.nextInt(2), mob.x, mob.y + 0.45, mob.z);
                if (worldRandom.nextDouble() < 0.55) {
                    spawnDroppedItem(InventoryItems.LEATHER, 1, mob.x, mob.y + 0.45, mob.z);
                }
                break;
            case SHEEP:
                spawnDroppedItem(InventoryItems.WOOL, 1, mob.x, mob.y + 0.45, mob.z);
                if (worldRandom.nextDouble() < 0.65) {
                    spawnDroppedItem(InventoryItems.RAW_MUTTON, 1, mob.x, mob.y + 0.45, mob.z);
                }
                break;
            case SKELETON:
                spawnDroppedItem(InventoryItems.BONE, 1 + worldRandom.nextInt(2), mob.x, mob.y + 0.45, mob.z);
                break;
            case ZOMBIE:
                if (worldRandom.nextDouble() < 0.75) {
                    spawnDroppedItem(InventoryItems.ROTTEN_FLESH, 1, mob.x, mob.y + 0.45, mob.z);
                }
                break;
            default:
                break;
        }
    }

    private void populateVillageResidentsNear(PlayerState player) {
        if (player == null || zombies.size() > 96) {
            return;
        }
        int villageCellSize = getVillageCellSizeChunks();
        int playerChunkX = worldToChunk((int) Math.floor(player.x));
        int playerChunkZ = worldToChunk((int) Math.floor(player.z));
        int playerCellX = Math.floorDiv(playerChunkX, villageCellSize);
        int playerCellZ = Math.floorDiv(playerChunkZ, villageCellSize);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int cellX = playerCellX + dx;
                int cellZ = playerCellZ + dz;
                long key = (((long) cellX) << 32) ^ (cellZ & 0xFFFFFFFFL);
                if (populatedVillageCells.contains(key)) {
                    continue;
                }
                int[] center = getVillageCenterForCell(cellX, cellZ);
                if (center == null) {
                    continue;
                }
                if (distanceSquared(player.x, player.z, center[0], center[2]) > 180.0 * 180.0) {
                    continue;
                }
                int spawned = spawnVillageResidents(center[0], center[1], center[2], mix64(seed ^ key));
                if (spawned > 0) {
                    populatedVillageCells.add(key);
                }
            }
        }
    }

    private int spawnVillageResidents(int centerX, int centerY, int centerZ, long villageSeed) {
        int count = 2 + (int) Math.round(randomUnit(villageSeed ^ 0xA11CE5L) * 3.0);
        int spawned = 0;
        int[][] offsets = {
            {0, 0}, {-8, -6}, {9, -5}, {-10, 8}, {8, 9}, {14, 2}, {-14, -1}
        };
        for (int attempt = 0; attempt < offsets.length && spawned < count; attempt++) {
            int x = centerX + offsets[attempt][0] + (int) Math.round((randomUnit(villageSeed ^ (attempt * 31L)) - 0.5) * 4.0);
            int z = centerZ + offsets[attempt][1] + (int) Math.round((randomUnit(villageSeed ^ (attempt * 57L)) - 0.5) * 4.0);
            ensureColumnLoadedSync(worldToChunk(x), worldToChunk(z));
            int surfaceY = getSurfaceHeight(x, z);
            if (Math.abs(surfaceY - centerY) > 6
                || GameConfig.isLiquidBlock(getBlock(x, surfaceY, z))
                || getBlock(x, surfaceY + 1, z) != GameConfig.AIR
                || getBlock(x, surfaceY + 2, z) != GameConfig.AIR
                || collides(x + 0.5, surfaceY + 1.01, z + 0.5, GameConfig.ZOMBIE_RADIUS, GameConfig.ZOMBIE_HEIGHT)) {
                continue;
            }
            zombies.add(new Zombie(MobKind.VILLAGER, x + 0.5, surfaceY + 1.01, z + 0.5, centerX + 0.5, centerZ + 0.5, new Random(villageSeed ^ attempt)));
            spawned++;
        }
        return spawned;
    }

    private double randomUnit(long bits) {
        long mantissa = (bits >>> 11) & ((1L << 53) - 1L);
        return mantissa / (double) (1L << 53);
    }

    private boolean isSunBurningMob(Zombie mob, boolean inWater) {
        return isHostileMob(mob)
            && isDaytime()
            && !inWater
            && isExposedToSky((int) Math.floor(mob.x), (int) Math.floor(mob.y + mob.height()), (int) Math.floor(mob.z));
    }

    private boolean isDaytime() {
        return worldTime > 0.18 && worldTime < 0.78;
    }

    private boolean isExposedToSky(int x, int y, int z) {
        for (int checkY = Math.max(y, GameConfig.WORLD_MIN_Y); checkY <= GameConfig.WORLD_MAX_Y; checkY++) {
            byte block = getBlock(x, checkY, z);
            if (Blocks.isOpaque(block) && block != GameConfig.OAK_LEAVES && block != GameConfig.PINE_LEAVES) {
                return false;
            }
        }
        return true;
    }

    boolean breakBlock(RayHit hit) {
        if (hit == null) {
            return false;
        }
        byte targetBlock = getBlock(hit.x, hit.y, hit.z);
        if (GameConfig.isLiquidBlock(targetBlock) || targetBlock == GameConfig.BEDROCK || targetBlock == GameConfig.AIR) {
            return false;
        }

        if (targetBlock == GameConfig.OAK_DOOR) {
            removeDoorAt(hit.x, hit.y, hit.z);
        } else if (targetBlock == GameConfig.RED_BED) {
            removeBedAt(hit.x, hit.y, hit.z);
        } else {
            if (targetBlock == GameConfig.CHEST) {
                dropChestContents(hit.x, hit.y, hit.z);
            } else if (targetBlock == GameConfig.FURNACE) {
                dropFurnaceContents(hit.x, hit.y, hit.z);
            }
            setBlockState(hit.x, hit.y, hit.z, GameConfig.AIR, -1);
        }
        if (targetBlock == GameConfig.OAK_LOG || targetBlock == GameConfig.PINE_LOG) {
            scheduleUnsupportedLeavesAround(hit.x, hit.y, hit.z);
        }
        updatePlantSupportAt(hit.x, hit.y + 1, hit.z);
        updateSnowSupportAt(hit.x, hit.y + 1, hit.z);
        updateDoorSupportAt(hit.x, hit.y + 1, hit.z);
        refreshDynamicCellsAround(hit.x, hit.y, hit.z);
        markDirtyColumn(hit.x, hit.z);
        refreshSurfaceHeight(hit.x, hit.z);
        return true;
    }

    private void scheduleUnsupportedLeavesAround(int x, int y, int z) {
        int radius = 6;
        for (int leafY = y - radius; leafY <= y + radius; leafY++) {
            for (int leafZ = z - radius; leafZ <= z + radius; leafZ++) {
                for (int leafX = x - radius; leafX <= x + radius; leafX++) {
                    byte block = getBlock(leafX, leafY, leafZ);
                    if (!isLeafBlock(block) || hasNearbyLog(leafX, leafY, leafZ, 4)) {
                        continue;
                    }
                    double distance = Math.sqrt(square(leafX - x) + square(leafY - y) + square(leafZ - z));
                    leafDecayTimers.put(packBlock(leafX, leafY, leafZ), 4.0 + distance * 0.9 + worldRandom.nextDouble() * 8.0);
                }
            }
        }
    }

    private void writePlayerInventory(DataOutputStream output, PlayerInventory inventory) throws IOException {
        for (int i = 0; i < PlayerInventory.HOTBAR_SIZE; i++) {
            writeStack(output, inventory.getHotbarStack(i));
        }
        for (int i = 0; i < PlayerInventory.STORAGE_SIZE; i++) {
            writeStack(output, inventory.getStorageStack(i));
        }
        for (int i = 0; i < PlayerInventory.ARMOR_SIZE; i++) {
            writeStack(output, inventory.getArmorStack(i));
        }
        writeStack(output, inventory.getOffhandStack());
    }

    private void readPlayerInventory(DataInputStream input, PlayerInventory inventory) throws IOException {
        inventory.clearAll();
        for (int i = 0; i < PlayerInventory.HOTBAR_SIZE; i++) {
            readStack(input, inventory.getHotbarStack(i), CONTAINER_SAVE_VERSION);
        }
        for (int i = 0; i < PlayerInventory.STORAGE_SIZE; i++) {
            readStack(input, inventory.getStorageStack(i), CONTAINER_SAVE_VERSION);
        }
        for (int i = 0; i < PlayerInventory.ARMOR_SIZE; i++) {
            readStack(input, inventory.getArmorStack(i), CONTAINER_SAVE_VERSION);
        }
        readStack(input, inventory.getOffhandStack(), CONTAINER_SAVE_VERSION);
    }

    private void tickLeafDecay(double deltaTime) {
        if (leafDecayTimers.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Long, Double>> iterator = leafDecayTimers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Double> entry = iterator.next();
            long blockKey = entry.getKey();
            int x = unpackBlockX(blockKey);
            int y = unpackBlockY(blockKey);
            int z = unpackBlockZ(blockKey);
            if (!isLeafBlock(getBlock(x, y, z)) || hasNearbyLog(x, y, z, 4)) {
                iterator.remove();
                continue;
            }
            double remaining = entry.getValue() - deltaTime;
            if (remaining > 0.0) {
                entry.setValue(remaining);
                continue;
            }
            setBlockState(x, y, z, GameConfig.AIR, -1);
            updatePlantSupportAt(x, y + 1, z);
            refreshDynamicCellsAround(x, y, z);
            markDirtyColumn(x, z);
            refreshSurfaceHeight(x, z);
            iterator.remove();
        }
    }

    private int square(int value) {
        return value * value;
    }

    private boolean hasNearbyLog(int x, int y, int z, int radius) {
        for (int logY = y - radius; logY <= y + radius; logY++) {
            for (int logZ = z - radius; logZ <= z + radius; logZ++) {
                for (int logX = x - radius; logX <= x + radius; logX++) {
                    if (isLogBlock(getBlock(logX, logY, logZ))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isLeafBlock(byte block) {
        return block == GameConfig.OAK_LEAVES || block == GameConfig.PINE_LEAVES;
    }

    private boolean isLogBlock(byte block) {
        return block == GameConfig.OAK_LOG || block == GameConfig.PINE_LOG;
    }

    boolean interactBlock(RayHit hit, PlayerState player, boolean shiftDown) {
        if (hit == null) {
            return false;
        }
        byte block = getBlock(hit.x, hit.y, hit.z);
        if (block == GameConfig.OAK_DOOR) {
            toggleDoorAt(hit.x, hit.y, hit.z);
            return true;
        }
        if (block == GameConfig.RED_BED) {
            BlockState bed = getBlockState(hit.x, hit.y, hit.z);
            int facing = Blocks.bedFacing(bed);
            int footX = Blocks.isBedHead(bed) ? hit.x - facingDx(facing) : hit.x;
            int footZ = Blocks.isBedHead(bed) ? hit.z - facingDz(facing) : hit.z;
            player.hasCustomSpawn = true;
            player.spawnX = footX + 0.5;
            player.spawnY = hit.y + 1.01;
            player.spawnZ = footZ + 0.5;
            if (!isDaytime()) {
                setWorldTime(0.30);
            }
            return true;
        }
        if (block == GameConfig.STRUCTURE_BLOCK) {
            return interactStructureBlock(hit, player, shiftDown);
        }
        return false;
    }

    ContainerInventory chestContainerAt(int x, int y, int z) {
        long key = packBlock(x, y, z);
        ContainerInventory container = chestContainers.get(key);
        if (container == null) {
            container = new ContainerInventory(27);
            fillGeneratedChestLoot(container, x, y, z);
            chestContainers.put(key, container);
        }
        return container;
    }

    FurnaceBlockEntity furnaceAt(int x, int y, int z) {
        long key = packBlock(x, y, z);
        FurnaceBlockEntity furnace = furnaces.get(key);
        if (furnace == null) {
            furnace = new FurnaceBlockEntity();
            furnaces.put(key, furnace);
        }
        return furnace;
    }

    private void dropChestContents(int x, int y, int z) {
        ContainerInventory container = chestContainerAt(x, y, z);
        for (ItemStack stack : container.slots) {
            if (!stack.isEmpty()) {
                spawnDroppedItem(stack.itemId, stack.count, stack.durabilityDamage, x + 0.5, y + 0.65, z + 0.5);
                stack.clear();
            }
        }
        chestContainers.remove(packBlock(x, y, z));
    }

    private void dropFurnaceContents(int x, int y, int z) {
        FurnaceBlockEntity furnace = furnaces.remove(packBlock(x, y, z));
        if (furnace == null) {
            return;
        }
        dropStack(furnace.input, x, y, z);
        dropStack(furnace.fuel, x, y, z);
        dropStack(furnace.output, x, y, z);
    }

    private void dropStack(ItemStack stack, int x, int y, int z) {
        if (stack != null && !stack.isEmpty()) {
            spawnDroppedItem(stack.itemId, stack.count, stack.durabilityDamage, x + 0.5, y + 0.65, z + 0.5);
            stack.clear();
        }
    }

    private boolean interactStructureBlock(RayHit hit, PlayerState player, boolean shiftDown) {
        int x = hit.x;
        int y = hit.y;
        int z = hit.z;
        BlockState state = getBlockState(x, y, z);
        int templateIndex = state.data & 0xFF;
        int rotation = (state.data >>> 8) & 3;
        boolean topClick = hit.previousY > y;
        if (topClick) {
            String template = StructureTemplates.nameAt(templateIndex);
            int placeX = x + 2;
            int placeY = Math.max(GameConfig.WORLD_MIN_Y + 1, y);
            int placeZ = z;
            StructureTemplates.place(this, template, placeX, placeY, placeZ, rotation);
        } else if (shiftDown) {
            rotation = (rotation + 1) & 3;
        } else {
            templateIndex = (templateIndex + 1) % StructureTemplates.NAMES.size();
        }
        setBlockState(x, y, z, new BlockState(Blocks.typeFromLegacyId(GameConfig.STRUCTURE_BLOCK), templateIndex | (rotation << 8)));
        markDirtyColumn(x, z);
        refreshDynamicCellsAround(x, y, z);
        return true;
    }

    String placeStructureTemplate(String name, int originX, int originY, int originZ, int rotation) {
        String template = StructureTemplates.normalize(name);
        if (!StructureTemplates.exists(template)) {
            return "Unknown structure '" + name + "'. Available: " + StructureTemplates.listNames();
        }
        if (!isInside(originX, originY, originZ)) {
            return "Cannot place structure outside world.";
        }
        StructureTemplates.place(this, template, originX, originY, originZ, rotation);
        return "Placed structure " + template + " rotation=" + (((rotation % 4) + 4) & 3) + ".";
    }

    String structureTemplateList() {
        return StructureTemplates.listNames();
    }

    void spawnDroppedItem(byte itemId, int count, double x, double y, double z) {
        spawnDroppedItem(itemId, count, 0, x, y, z);
    }

    void spawnDroppedItem(byte itemId, int count, int durabilityDamage, double x, double y, double z) {
        if (!InventoryItems.isCollectible(itemId) || count <= 0) {
            return;
        }
        if (droppedItems.size() >= 256) {
            return;
        }
        DroppedItem droppedItem = new DroppedItem(itemId, count, x, y, z);
        droppedItem.durabilityDamage = InventoryItems.isDurableItem(itemId)
            ? Math.max(0, Math.min(durabilityDamage, InventoryItems.maxDurability(itemId) - 1))
            : 0;
        droppedItem.velocityX = (worldRandom.nextDouble() - 0.5) * 1.2;
        droppedItem.velocityZ = (worldRandom.nextDouble() - 0.5) * 1.2;
        droppedItem.verticalVelocity = 1.8 + worldRandom.nextDouble() * 1.0;
        droppedItem.spinDegrees = worldRandom.nextDouble() * 360.0;
        droppedItems.add(droppedItem);
    }

    void spawnThrownItem(byte itemId, int count, double x, double y, double z, double velocityX, double velocityY, double velocityZ) {
        spawnThrownItem(itemId, count, 0, x, y, z, velocityX, velocityY, velocityZ);
    }

    void spawnThrownItem(byte itemId, int count, int durabilityDamage, double x, double y, double z, double velocityX, double velocityY, double velocityZ) {
        if (!InventoryItems.isCollectible(itemId) || count <= 0 || droppedItems.size() >= 256) {
            return;
        }
        DroppedItem droppedItem = new DroppedItem(itemId, count, x, y, z);
        droppedItem.durabilityDamage = InventoryItems.isDurableItem(itemId)
            ? Math.max(0, Math.min(durabilityDamage, InventoryItems.maxDurability(itemId) - 1))
            : 0;
        droppedItem.velocityX = velocityX;
        droppedItem.velocityZ = velocityZ;
        droppedItem.verticalVelocity = velocityY;
        droppedItem.spinDegrees = worldRandom.nextDouble() * 360.0;
        droppedItems.add(droppedItem);
    }

    void spawnChestLootAt(int blockX, int blockY, int blockZ) {
        ContainerInventory container = new ContainerInventory(27);
        fillGeneratedChestLoot(container, blockX, blockY, blockZ);
        for (ItemStack stack : container.slots) {
            if (!stack.isEmpty()) {
                spawnDroppedItem(stack.itemId, stack.count, blockX + 0.5, blockY + 0.8, blockZ + 0.5);
            }
        }
    }

    private void fillGeneratedChestLoot(ContainerInventory container, int blockX, int blockY, int blockZ) {
        long lootSeed = seed ^ mix64((((long) blockX) << 38) ^ (((long) blockZ) << 12) ^ blockY);
        Random lootRandom = new Random(lootSeed);
        byte[] commonLoot = {InventoryItems.POTATO, InventoryItems.CARROT, InventoryItems.WHEAT_SEEDS, InventoryItems.BREAD};
        byte[] mineLoot = {
            InventoryItems.COAL_ITEM, InventoryItems.COAL_ITEM, InventoryItems.COAL_ITEM,
            GameConfig.IRON_ORE, GameConfig.IRON_ORE, InventoryItems.IRON_INGOT,
            InventoryItems.BREAD, GameConfig.RAIL, GameConfig.RAIL,
            InventoryItems.DIAMOND_ITEM
        };
        byte[] loot = blockY < GameConfig.SEA_LEVEL - 12 ? mineLoot : commonLoot;
        int rolls = 2 + lootRandom.nextInt(3);
        for (int roll = 0; roll < rolls; roll++) {
            byte item = loot[lootRandom.nextInt(loot.length)];
            int count = item == InventoryItems.DIAMOND_ITEM ? 1 : 1 + lootRandom.nextInt(item == GameConfig.RAIL ? 10 : 4);
            int slot = lootRandom.nextInt(container.slots.length);
            ItemStack stack = container.slots[slot];
            if (stack.isEmpty()) {
                stack.set(item, count);
            } else if (stack.itemId == item) {
                stack.count = Math.min(InventoryItems.maxStackSize(item), stack.count + count);
            }
        }
    }

    private long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    void updateDroppedItems(PlayerState player, PlayerInventory inventory, double deltaTime) {
        for (int i = droppedItems.size() - 1; i >= 0; i--) {
            DroppedItem droppedItem = droppedItems.get(i);
            droppedItem.ageSeconds += deltaTime;
            droppedItem.pickupDelaySeconds = Math.max(0.0, droppedItem.pickupDelaySeconds - deltaTime);
            droppedItem.spinDegrees = (droppedItem.spinDegrees + deltaTime * 180.0) % 360.0;
            if (droppedItem.ageSeconds >= GameConfig.DROPPED_ITEM_DESPAWN_SECONDS || droppedItem.count <= 0) {
                droppedItems.remove(i);
                continue;
            }

            updateDroppedItemPhysics(droppedItem, deltaTime);
            if (player == null || inventory == null || droppedItem.pickupDelaySeconds > 0.0) {
                continue;
            }

            double dx = droppedItem.x - player.x;
            double dy = (droppedItem.y + droppedItem.height() * 0.5) - (player.y + GameConfig.PLAYER_HEIGHT * 0.5);
            double dz = droppedItem.z - player.z;
            double pickupDistance = GameConfig.DROPPED_ITEM_PICKUP_RADIUS;
            if (dx * dx + dy * dy + dz * dz <= pickupDistance * pickupDistance
                && inventory.addItem(droppedItem.itemId, droppedItem.count, droppedItem.durabilityDamage)) {
                droppedItems.remove(i);
            }
        }
    }

    boolean placeBlock(RayHit hit, byte block, PlayerState player) {
        if (hit == null) {
            return false;
        }

        byte placedBlock = GameConfig.placedBlockForItem(block);
        int placeX = hit.previousX;
        int placeY = hit.previousY;
        int placeZ = hit.previousZ;
        if (GameConfig.isLiquidBlock(placedBlock)
            && GameConfig.fluidItemForBlock(getBlock(hit.x, hit.y, hit.z)) == GameConfig.fluidItemForBlock(placedBlock)) {
            placeX = hit.x;
            placeY = hit.y;
            placeZ = hit.z;
        }

        if (!isInside(placeX, placeY, placeZ)) {
            return false;
        }

        if (placedBlock == GameConfig.OAK_DOOR) {
            return placeDoor(placeX, placeY, placeZ, player);
        }
        if (placedBlock == GameConfig.RED_BED) {
            return placeBed(placeX, placeY, placeZ, player);
        }

        byte targetBlock = getBlock(placeX, placeY, placeZ);
        boolean canReplaceTarget = Blocks.isReplaceable(targetBlock)
            || GameConfig.isLiquidBlock(targetBlock);
        if (!canReplaceTarget) {
            return false;
        }

        boolean solidPlacement = isSolidBlock(placedBlock);
        if (solidPlacement && blockIntersectsPlayerHitbox(placeX, placeY, placeZ, player)) {
            return false;
        }

        if (GameConfig.isLiquidBlock(placedBlock)) {
            setBlockState(placeX, placeY, placeZ, placedBlock, 0);
        } else {
            setBlockState(placeX, placeY, placeZ, placedBlock, -1);
        }
        if (placedBlock == GameConfig.CHEST) {
            chestContainers.put(packBlock(placeX, placeY, placeZ), new ContainerInventory(27));
        } else if (placedBlock == GameConfig.FURNACE) {
            furnaces.put(packBlock(placeX, placeY, placeZ), new FurnaceBlockEntity());
        }
        updatePlantSupportAt(placeX, placeY + 1, placeZ);
        updateDoorSupportAt(placeX, placeY + 1, placeZ);
        refreshDynamicCellsAround(placeX, placeY, placeZ);
        markDirtyColumn(placeX, placeZ);
        refreshSurfaceHeight(placeX, placeZ);
        return true;
    }

    byte scoopLiquid(RayHit hit) {
        if (hit == null || !isInside(hit.x, hit.y, hit.z)) {
            return GameConfig.AIR;
        }
        byte block = getBlock(hit.x, hit.y, hit.z);
        byte fluid = GameConfig.fluidItemForBlock(block);
        if (fluid == GameConfig.WATER) {
            removeLiquidAt(hit.x, hit.y, hit.z);
            return InventoryItems.ITEM_WATER_BUCKET;
        }
        if (fluid == GameConfig.LAVA) {
            removeLiquidAt(hit.x, hit.y, hit.z);
            return InventoryItems.ITEM_LAVA_BUCKET;
        }
        return GameConfig.AIR;
    }

    private void removeLiquidAt(int x, int y, int z) {
        setBlockState(x, y, z, GameConfig.AIR, -1);
        refreshDynamicCellsAround(x, y, z);
        markDirtyColumn(x, z);
        refreshSurfaceHeight(x, z);
    }

    private boolean placeDoor(int x, int y, int z, PlayerState player) {
        if (!isInside(x, y, z) || !isInside(x, y + 1, z) || y <= GameConfig.WORLD_MIN_Y) {
            return false;
        }
        if (!isReplaceableForPlacement(getBlock(x, y, z)) || !isReplaceableForPlacement(getBlock(x, y + 1, z))) {
            return false;
        }
        if (!isSolidBlock(getBlock(x, y - 1, z))) {
            return false;
        }
        if (blockIntersectsPlayerHitbox(x, y, z, player) || blockIntersectsPlayerHitbox(x, y + 1, z, player)) {
            return false;
        }
        int facing = player == null ? 2 : facingFromYaw(player.yaw);
        setBlockState(x, y, z, Blocks.doorState(false, false, facing));
        setBlockState(x, y + 1, z, Blocks.doorState(false, true, facing));
        refreshDynamicCellsAround(x, y, z);
        refreshDynamicCellsAround(x, y + 1, z);
        markDirtyColumn(x, z);
        refreshSurfaceHeight(x, z);
        return true;
    }

    private boolean placeBed(int x, int y, int z, PlayerState player) {
        if (!isInside(x, y, z) || y <= GameConfig.WORLD_MIN_Y) {
            return false;
        }
        int facing = player == null ? 1 : facingFromYaw(player.yaw);
        int headX = x + facingDx(facing);
        int headZ = z + facingDz(facing);
        if (!isInside(headX, y, headZ)) {
            return false;
        }
        if (!isReplaceableForPlacement(getBlock(x, y, z)) || !isReplaceableForPlacement(getBlock(headX, y, headZ))) {
            return false;
        }
        if (!isSolidBlock(getBlock(x, y - 1, z)) || !isSolidBlock(getBlock(headX, y - 1, headZ))) {
            return false;
        }
        if (blockIntersectsPlayerHitbox(x, y, z, player) || blockIntersectsPlayerHitbox(headX, y, headZ, player)) {
            return false;
        }
        setBlockState(x, y, z, Blocks.bedState(false, facing));
        setBlockState(headX, y, headZ, Blocks.bedState(true, facing));
        refreshDynamicCellsAround(x, y, z);
        refreshDynamicCellsAround(headX, y, headZ);
        markDirtyColumn(x, z);
        markDirtyColumn(headX, headZ);
        refreshSurfaceHeight(x, z);
        refreshSurfaceHeight(headX, headZ);
        return true;
    }

    private void removeBedAt(int x, int y, int z) {
        BlockState state = getBlockState(x, y, z);
        if (state.type.numericId != (GameConfig.RED_BED & 0xFF)) {
            return;
        }
        int facing = Blocks.bedFacing(state);
        int footX = Blocks.isBedHead(state) ? x - facingDx(facing) : x;
        int footZ = Blocks.isBedHead(state) ? z - facingDz(facing) : z;
        int headX = footX + facingDx(facing);
        int headZ = footZ + facingDz(facing);
        if (getBlock(footX, y, footZ) == GameConfig.RED_BED) {
            setBlockState(footX, y, footZ, GameConfig.AIR, -1);
            refreshDynamicCellsAround(footX, y, footZ);
            markDirtyColumn(footX, footZ);
            refreshSurfaceHeight(footX, footZ);
        }
        if (getBlock(headX, y, headZ) == GameConfig.RED_BED) {
            setBlockState(headX, y, headZ, GameConfig.AIR, -1);
            refreshDynamicCellsAround(headX, y, headZ);
            markDirtyColumn(headX, headZ);
            refreshSurfaceHeight(headX, headZ);
        }
    }

    private int facingDx(int facing) {
        return facing == 1 ? 1 : (facing == 3 ? -1 : 0);
    }

    private int facingDz(int facing) {
        return facing == 2 ? 1 : (facing == 0 ? -1 : 0);
    }

    private boolean isReplaceableForPlacement(byte block) {
        return Blocks.isReplaceable(block) || GameConfig.isLiquidBlock(block);
    }

    private int facingFromYaw(double yaw) {
        double normalized = yaw % (Math.PI * 2.0);
        if (normalized < 0.0) {
            normalized += Math.PI * 2.0;
        }
        int quadrant = (int) Math.floor((normalized + Math.PI * 0.25) / (Math.PI * 0.5)) & 3;
        switch (quadrant) {
            case 0:
                return 1;
            case 1:
                return 2;
            case 2:
                return 3;
            default:
                return 0;
        }
    }

    private void toggleDoorAt(int x, int y, int z) {
        BlockState state = getBlockState(x, y, z);
        if (state.type.numericId != (GameConfig.OAK_DOOR & 0xFF)) {
            return;
        }
        int lowerY = doorLowerY(x, y, z, state);
        BlockState lower = getBlockState(x, lowerY, z);
        if (lower.type.numericId != (GameConfig.OAK_DOOR & 0xFF)) {
            return;
        }
        int facing = Blocks.doorFacing(lower);
        boolean open = !Blocks.isDoorOpen(lower);
        setBlockState(x, lowerY, z, Blocks.doorState(open, false, facing));
        if (getBlock(x, lowerY + 1, z) == GameConfig.OAK_DOOR) {
            setBlockState(x, lowerY + 1, z, Blocks.doorState(open, true, facing));
        }
        refreshDynamicCellsAround(x, lowerY, z);
        refreshDynamicCellsAround(x, lowerY + 1, z);
        markDirtyColumn(x, z);
    }

    private void removeDoorAt(int x, int y, int z) {
        BlockState state = getBlockState(x, y, z);
        int lowerY = doorLowerY(x, y, z, state);
        if (getBlock(x, lowerY, z) == GameConfig.OAK_DOOR) {
            setBlockState(x, lowerY, z, GameConfig.AIR, -1);
        }
        if (getBlock(x, lowerY + 1, z) == GameConfig.OAK_DOOR) {
            setBlockState(x, lowerY + 1, z, GameConfig.AIR, -1);
        }
    }

    private void updateDoorSupportAt(int x, int y, int z) {
        if (!isInside(x, y, z) || getBlock(x, y, z) != GameConfig.OAK_DOOR) {
            return;
        }
        BlockState state = getBlockState(x, y, z);
        int lowerY = doorLowerY(x, y, z, state);
        if (getBlock(x, lowerY, z) != GameConfig.OAK_DOOR
            || getBlock(x, lowerY + 1, z) != GameConfig.OAK_DOOR
            || !isSolidBlock(getBlock(x, lowerY - 1, z))) {
            removeDoorAt(x, y, z);
            markDirtyColumn(x, z);
            refreshSurfaceHeight(x, z);
        }
    }

    private int doorLowerY(int x, int y, int z, BlockState state) {
        if (Blocks.isDoorUpper(state)) {
            return y - 1;
        }
        if (getBlock(x, y - 1, z) == GameConfig.OAK_DOOR && getBlock(x, y + 1, z) != GameConfig.OAK_DOOR) {
            return y - 1;
        }
        return y;
    }

    RayHit raycastBlock(PlayerState player) {
        double originX = player.x;
        double originY = player.y + player.eyeHeight();
        double originZ = player.z;

        double horizontalLength = Math.cos(player.pitch);
        double dirX = Math.cos(player.yaw) * horizontalLength;
        double dirY = Math.sin(player.pitch);
        double dirZ = Math.sin(player.yaw) * horizontalLength;

        int blockX = (int) Math.floor(originX);
        int blockY = (int) Math.floor(originY);
        int blockZ = (int) Math.floor(originZ);
        int previousX = blockX;
        int previousY = blockY;
        int previousZ = blockZ;

        int stepX = dirX > 0.0 ? 1 : (dirX < 0.0 ? -1 : 0);
        int stepY = dirY > 0.0 ? 1 : (dirY < 0.0 ? -1 : 0);
        int stepZ = dirZ > 0.0 ? 1 : (dirZ < 0.0 ? -1 : 0);

        double nextBoundaryX = stepX > 0 ? blockX + 1.0 : blockX;
        double nextBoundaryY = stepY > 0 ? blockY + 1.0 : blockY;
        double nextBoundaryZ = stepZ > 0 ? blockZ + 1.0 : blockZ;

        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (nextBoundaryX - originX) / dirX;
        double tMaxY = stepY == 0 ? Double.POSITIVE_INFINITY : (nextBoundaryY - originY) / dirY;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (nextBoundaryZ - originZ) / dirZ;

        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirX);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirY);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dirZ);

        double maxDistance = GameConfig.REACH_DISTANCE;
        double traveled = 0.0;

        while (traveled <= maxDistance) {
            if (isInside(blockX, blockY, blockZ)) {
                byte block = getBlock(blockX, blockY, blockZ);
                if (block != GameConfig.AIR && !GameConfig.isLiquidBlock(block)) {
                    double[] bounds = selectionBounds(block, getBlockState(blockX, blockY, blockZ));
                    if (rayIntersectsAabb(originX, originY, originZ, dirX, dirY, dirZ,
                        blockX + bounds[0], blockY + bounds[1], blockZ + bounds[2],
                        blockX + bounds[3], blockY + bounds[4], blockZ + bounds[5],
                        maxDistance)) {
                        return new RayHit(blockX, blockY, blockZ, previousX, previousY, previousZ);
                    }
                }
            }

            previousX = blockX;
            previousY = blockY;
            previousZ = blockZ;

            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                blockX += stepX;
                traveled = tMaxX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                blockY += stepY;
                traveled = tMaxY;
                tMaxY += tDeltaY;
            } else {
                blockZ += stepZ;
                traveled = tMaxZ;
                tMaxZ += tDeltaZ;
            }
        }

        return null;
    }

    private double[] selectionBounds(byte block, BlockState state) {
        switch (block) {
            case GameConfig.SNOW_LAYER:
                return new double[]{0.0, 0.0, 0.0, 1.0, 0.125, 1.0};
            case GameConfig.FARMLAND:
                return new double[]{0.0, 0.0, 0.0, 1.0, 0.9375, 1.0};
            case GameConfig.WHEAT_CROP:
            case GameConfig.TALL_GRASS:
            case GameConfig.RED_FLOWER:
            case GameConfig.YELLOW_FLOWER:
            case GameConfig.SEAGRASS:
                return new double[]{0.18, 0.0, 0.18, 0.82, 0.86, 0.82};
            case GameConfig.RAIL:
                return new double[]{0.0, 0.0, 0.0, 1.0, 0.075, 1.0};
            case GameConfig.TORCH:
                return new double[]{0.40, 0.0, 0.40, 0.60, 0.82, 0.60};
            case GameConfig.OAK_FENCE:
                return new double[]{0.25, 0.0, 0.25, 0.75, 1.0, 0.75};
            case GameConfig.RED_BED:
                return new double[]{0.0, 0.0, 0.0, 1.0, 0.56, 1.0};
            case GameConfig.OAK_DOOR:
                return doorSelectionBounds(state);
            default:
                return new double[]{0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
        }
    }

    private double[] doorSelectionBounds(BlockState state) {
        int facing = Blocks.doorFacing(state);
        boolean open = Blocks.isDoorOpen(state);
        double t = 0.16;
        int visualFacing = open ? ((facing + 1) & 3) : facing;
        switch (visualFacing) {
            case 0:
                return new double[]{0.0, 0.0, 0.0, 1.0, 1.0, t};
            case 1:
                return new double[]{1.0 - t, 0.0, 0.0, 1.0, 1.0, 1.0};
            case 2:
                return new double[]{0.0, 0.0, 1.0 - t, 1.0, 1.0, 1.0};
            default:
                return new double[]{0.0, 0.0, 0.0, t, 1.0, 1.0};
        }
    }

    private boolean rayIntersectsAabb(double ox, double oy, double oz, double dx, double dy, double dz,
                                      double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ,
                                      double maxDistance) {
        double tMin = 0.0;
        double tMax = maxDistance;
        if (Math.abs(dx) < 1.0e-9) {
            if (ox < minX || ox > maxX) return false;
        } else {
            double inv = 1.0 / dx;
            double t1 = (minX - ox) * inv;
            double t2 = (maxX - ox) * inv;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        if (Math.abs(dy) < 1.0e-9) {
            if (oy < minY || oy > maxY) return false;
        } else {
            double inv = 1.0 / dy;
            double t1 = (minY - oy) * inv;
            double t2 = (maxY - oy) * inv;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        if (Math.abs(dz) < 1.0e-9) {
            if (oz < minZ || oz > maxZ) return false;
        } else {
            double inv = 1.0 / dz;
            double t1 = (minZ - oz) * inv;
            double t2 = (maxZ - oz) * inv;
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        return tMax >= tMin && tMax >= 0.0;
    }

    boolean attackMobInReach(PlayerState player, int damage, double knockback) {
        if (player == null || damage <= 0) {
            return false;
        }
        double originX = player.x;
        double originY = player.y + player.eyeHeight();
        double originZ = player.z;
        double horizontalLength = Math.cos(player.pitch);
        double dirX = Math.cos(player.yaw) * horizontalLength;
        double dirY = Math.sin(player.pitch);
        double dirZ = Math.sin(player.yaw) * horizontalLength;
        double reach = 3.4;
        Zombie best = null;
        double bestProjection = Double.POSITIVE_INFINITY;
        for (Zombie mob : zombies) {
            if (mob.health <= 0) {
                continue;
            }
            double centerX = mob.x;
            double centerY = mob.y + GameConfig.ZOMBIE_HEIGHT * 0.52;
            double centerZ = mob.z;
            double toX = centerX - originX;
            double toY = centerY - originY;
            double toZ = centerZ - originZ;
            double projection = toX * dirX + toY * dirY + toZ * dirZ;
            if (projection < 0.0 || projection > reach || projection >= bestProjection) {
                continue;
            }
            double closestX = originX + dirX * projection;
            double closestY = originY + dirY * projection;
            double closestZ = originZ + dirZ * projection;
            double dx = centerX - closestX;
            double dy = centerY - closestY;
            double dz = centerZ - closestZ;
            double hitRadius = mob.kind == MobKind.VILLAGER ? 0.72 : 0.62;
            if (dx * dx + dy * dy + dz * dz <= hitRadius * hitRadius) {
                best = mob;
                bestProjection = projection;
            }
        }
        if (best == null) {
            return false;
        }
        if (best.hurtCooldown > 0.0) {
            return true;
        }
        best.health -= damage;
        best.fireTimer = 0.0;
        best.growlQueued = true;
        best.hurtCooldown = 0.28;
        if (isPassiveMob(best)) {
            best.fleeTimer = 4.0 + best.random.nextDouble() * 2.0;
        }
        double appliedKnockback = knockback * (isPassiveMob(best) ? 1.35 : 1.12);
        best.velocityX += dirX * appliedKnockback;
        best.velocityZ += dirZ * appliedKnockback;
        best.verticalVelocity = Math.max(best.verticalVelocity, 0.20 + appliedKnockback * 0.18);
        return true;
    }

    boolean feedPassiveMobInReach(PlayerState player, byte itemId) {
        if (player == null || !isBreedingFood(itemId)) {
            return false;
        }
        Zombie fed = findFeedTarget(player, itemId);
        if (fed == null) {
            return false;
        }
        fed.loveTimer = 8.0;
        fed.breedCooldown = Math.max(fed.breedCooldown, 1.0);
        fed.growlQueued = true;
        Zombie mate = findMateFor(fed);
        if (mate != null) {
            spawnMobAt(fed.kind, (fed.x + mate.x) * 0.5, Math.max(fed.y, mate.y), (fed.z + mate.z) * 0.5);
            fed.loveTimer = 0.0;
            mate.loveTimer = 0.0;
            fed.breedCooldown = 35.0;
            mate.breedCooldown = 35.0;
        }
        return true;
    }

    private Zombie findFeedTarget(PlayerState player, byte itemId) {
        Zombie best = null;
        double bestDistance = 3.2 * 3.2;
        for (Zombie mob : zombies) {
            if (!canFeedMob(mob, itemId)) {
                continue;
            }
            double dx = mob.x - player.x;
            double dy = (mob.y + 0.7) - (player.y + player.eyeHeight());
            double dz = mob.z - player.z;
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDistance) {
                bestDistance = dist;
                best = mob;
            }
        }
        return best;
    }

    private Zombie findMateFor(Zombie fed) {
        for (Zombie other : zombies) {
            if (other == fed || other.kind != fed.kind || other.health <= 0 || other.breedCooldown > 0.0 || other.loveTimer <= 0.0) {
                continue;
            }
            if (distanceSquared(fed.x, fed.z, other.x, other.z) <= 6.0 * 6.0 && Math.abs(fed.y - other.y) <= 2.0) {
                return other;
            }
        }
        return null;
    }

    private boolean canFeedMob(Zombie mob, byte itemId) {
        return mob != null
            && mob.health > 0
            && mob.breedCooldown <= 0.0
            && ((itemId == GameConfig.WHEAT_CROP && (mob.kind == MobKind.COW || mob.kind == MobKind.SHEEP))
                || (itemId == InventoryItems.CARROT && mob.kind == MobKind.PIG));
    }

    private boolean isBreedingFood(byte itemId) {
        return itemId == GameConfig.WHEAT_CROP || itemId == InventoryItems.CARROT;
    }

    boolean collides(double x, double y, double z, double radius, double height) {
        int minX = (int) Math.floor(x - radius + COLLISION_EPSILON);
        int maxX = (int) Math.floor(x + radius - COLLISION_EPSILON);
        int minY = (int) Math.floor(y + COLLISION_EPSILON);
        int maxY = (int) Math.floor(y + height - COLLISION_EPSILON);
        int minZ = (int) Math.floor(z - radius + COLLISION_EPSILON);
        int maxZ = (int) Math.floor(z + radius - COLLISION_EPSILON);

        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockY = minY; blockY <= maxY; blockY++) {
                for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                    if (isCollisionSolid(getBlockState(blockX, blockY, blockZ))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean touchesBlock(double x, double y, double z, double radius, double height, byte targetBlock) {
        int minX = (int) Math.floor(x - radius + COLLISION_EPSILON);
        int maxX = (int) Math.floor(x + radius - COLLISION_EPSILON);
        int minY = (int) Math.floor(y + COLLISION_EPSILON);
        int maxY = (int) Math.floor(y + height - COLLISION_EPSILON);
        int minZ = (int) Math.floor(z - radius + COLLISION_EPSILON);
        int maxZ = (int) Math.floor(z + radius - COLLISION_EPSILON);

        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockY = minY; blockY <= maxY; blockY++) {
                for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                    if (getBlock(blockX, blockY, blockZ) == targetBlock) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean intersectsFluid(double x, double y, double z, double radius, double height, byte fluidItem) {
        int minX = (int) Math.floor(x - radius + COLLISION_EPSILON);
        int maxX = (int) Math.floor(x + radius - COLLISION_EPSILON);
        int minY = (int) Math.floor(y + COLLISION_EPSILON);
        int maxY = (int) Math.floor(y + height - COLLISION_EPSILON);
        int minZ = (int) Math.floor(z - radius + COLLISION_EPSILON);
        int maxZ = (int) Math.floor(z + radius - COLLISION_EPSILON);

        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockY = minY; blockY <= maxY; blockY++) {
                for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                    byte block = getBlock(blockX, blockY, blockZ);
                    if (GameConfig.fluidItemForBlock(block) != fluidItem) {
                        continue;
                    }
                    double fluidTop = blockY + getFluidSurfaceHeight(blockX, blockY, blockZ);
                    if (fluidTop > y + COLLISION_EPSILON) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    boolean isPointInsideFluid(double x, double y, double z, byte fluidItem) {
        int blockX = (int) Math.floor(x);
        int blockY = (int) Math.floor(y);
        int blockZ = (int) Math.floor(z);
        byte block = getBlock(blockX, blockY, blockZ);
        if (GameConfig.fluidItemForBlock(block) != fluidItem) {
            return false;
        }
        return y < blockY + getFluidSurfaceHeight(blockX, blockY, blockZ) - 1.0e-4;
    }

    boolean canStandOnFluid(double x, double y, double z, double radius, byte fluidBlock) {
        int minX = (int) Math.floor(x - radius + COLLISION_EPSILON);
        int maxX = (int) Math.floor(x + radius - COLLISION_EPSILON);
        int minZ = (int) Math.floor(z - radius + COLLISION_EPSILON);
        int maxZ = (int) Math.floor(z + radius - COLLISION_EPSILON);
        int blockY = (int) Math.floor(y - 0.08);

        boolean foundFlowingFluid = false;
        for (int blockX = minX; blockX <= maxX; blockX++) {
            for (int blockZ = minZ; blockZ <= maxZ; blockZ++) {
                byte block = getBlock(blockX, blockY, blockZ);
                if (block == fluidBlock) {
                    foundFlowingFluid = true;
                } else if (isSolidBlock(block)) {
                    return false;
                }
            }
        }
        return foundFlowingFluid;
    }

    boolean hasNearbyWater(double x, double y, double z, int radius) {
        int centerX = (int) Math.floor(x);
        int centerY = (int) Math.floor(y);
        int centerZ = (int) Math.floor(z);
        for (int blockX = centerX - radius; blockX <= centerX + radius; blockX++) {
            for (int blockY = Math.max(GameConfig.WORLD_MIN_Y, centerY - 1); blockY <= Math.min(GameConfig.WORLD_MAX_Y, centerY + 1); blockY++) {
                for (int blockZ = centerZ - radius; blockZ <= centerZ + radius; blockZ++) {
                    if (GameConfig.isWaterBlock(getBlock(blockX, blockY, blockZ))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    double getFluidSurfaceHeight(int x, int y, int z) {
        byte block = getBlock(x, y, z);
        if (!GameConfig.isLiquidBlock(block)) {
            return 0.0;
        }
        if (GameConfig.isFluidSourceBlock(block)) {
            return 1.0;
        }
        byte above = getBlock(x, y + 1, z);
        if (GameConfig.fluidItemForBlock(above) == GameConfig.fluidItemForBlock(block)) {
            return 1.0;
        }
        int distance = Math.max(0, getFluidDistance(x, y, z));
        int maxDistance = GameConfig.fluidSpreadDistance(block);
        double step = GameConfig.isWaterBlock(block) ? 0.11 : 0.15;
        double minimum = GameConfig.isWaterBlock(block) ? 0.16 : 0.24;
        double maximum = GameConfig.isWaterBlock(block) ? 0.98 : 0.96;
        return Math.max(minimum, Math.min(maximum, maximum - Math.min(distance, maxDistance) * step));
    }

    void sampleFluidFlow(double x, double y, double z, double radius, double height, byte fluidItem, MutableVec3 output) {
        double minX = x - radius;
        double maxX = x + radius;
        double minY = y;
        double maxY = y + height;
        double minZ = z - radius;
        double maxZ = z + radius;

        int startX = (int) Math.floor(minX + COLLISION_EPSILON);
        int endX = (int) Math.floor(maxX - COLLISION_EPSILON);
        int startY = (int) Math.floor(minY + COLLISION_EPSILON);
        int endY = (int) Math.floor(maxY - COLLISION_EPSILON);
        int startZ = (int) Math.floor(minZ + COLLISION_EPSILON);
        int endZ = (int) Math.floor(maxZ - COLLISION_EPSILON);

        double flowX = 0.0;
        double flowY = 0.0;
        double flowZ = 0.0;
        int samples = 0;

        for (int blockX = startX; blockX <= endX; blockX++) {
            for (int blockY = startY; blockY <= endY; blockY++) {
                for (int blockZ = startZ; blockZ <= endZ; blockZ++) {
                    byte block = getBlock(blockX, blockY, blockZ);
                    if (GameConfig.fluidItemForBlock(block) != fluidItem) {
                        continue;
                    }
                    double fluidTop = blockY + getFluidSurfaceHeight(blockX, blockY, blockZ);
                    if (fluidTop <= minY + COLLISION_EPSILON) {
                        continue;
                    }

                    getFluidFlowVector(blockX, blockY, blockZ, blockFluidFlow);
                    flowX += blockFluidFlow.x;
                    flowY += blockFluidFlow.y;
                    flowZ += blockFluidFlow.z;
                    samples++;
                }
            }
        }

        if (samples == 0) {
            output.set(0.0, 0.0, 0.0);
            return;
        }
        output.set(flowX / samples, flowY / samples, flowZ / samples);
    }

    double getStandingY(double x, double z, double fallbackY) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int topSolid = getSurfaceHeight(blockX, blockZ);
        int standY = topSolid + 1;
        if (standY >= GameConfig.WORLD_MAX_Y) {
            return fallbackY;
        }
        if (isSolidBlock(getBlock(blockX, standY, blockZ)) || isSolidBlock(getBlock(blockX, standY + 1, blockZ))) {
            return fallbackY;
        }
        return standY + 0.01;
    }

    boolean isInside(int x, int y, int z) {
        return GameConfig.isWorldCoordinateInside(x, y, z);
    }

    byte getBlock(int x, int y, int z) {
        Chunk chunk = getChunkForBlock(x, y, z);
        if (chunk == null) {
            return GameConfig.AIR;
        }
        return chunk.getBlockLocal(localBlockCoordinate(x), GameConfig.localYForWorldY(y), localBlockCoordinate(z));
    }

    BlockState getBlockState(int x, int y, int z) {
        Chunk chunk = getChunkForBlock(x, y, z);
        if (chunk == null) {
            return Blocks.stateFromLegacyId(GameConfig.AIR);
        }
        return chunk.getBlockStateLocal(localBlockCoordinate(x), GameConfig.localYForWorldY(y), localBlockCoordinate(z));
    }

    public BlockState getTemplateBlock(int x, int y, int z) {
        return getBlockState(x, y, z);
    }

    public void setTemplateBlock(int x, int y, int z, BlockState state) {
        setBlockState(x, y, z, state);
        markDirtyColumn(x, z);
        refreshSurfaceHeight(x, z);
        refreshDynamicCellsAround(x, y, z);
    }

    int getSurfaceHeight(int x, int z) {
        ChunkColumn column = loadedColumns.get(columnKey(worldToChunk(x), worldToChunk(z)));
        if (column == null) {
            return worldGenerator != null ? worldGenerator.estimateSurfaceHeight(x, z) : calculateTerrainHeight(x, z);
        }
        return column.getSurfaceHeightLocal(localBlockCoordinate(x), localBlockCoordinate(z));
    }

    float getAmbientShade(int x, int y, int z) {
        int surfaceY = getSurfaceHeight(x, z);
        int depth = surfaceY - y;
        float shade = 1.0f;
        if (depth > 0) {
            shade -= Math.min(0.50f, depth * 0.030f);
        }
        if (y < 8) {
            shade -= 0.04f;
        }
        if (isLiquidBlock(getBlock(x, y, z))) {
            shade = Math.max(shade, 0.55f);
        }
        if (depth > 2) {
            float torchShade = nearbyTorchShade(x, y, z);
            if (torchShade > shade) {
                shade = torchShade;
            }
        }
        return Math.max(0.42f, Math.min(1.0f, shade));
    }

    private float nearbyTorchShade(int x, int y, int z) {
        int radius = 2;
        int bestDistanceSquared = Integer.MAX_VALUE;
        for (int offsetY = -radius; offsetY <= radius; offsetY++) {
            int checkY = y + offsetY;
            if (!GameConfig.isWorldYInside(checkY)) {
                continue;
            }
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                    int distanceSquared = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
                    if (distanceSquared >= bestDistanceSquared || distanceSquared > radius * radius) {
                        continue;
                    }
                    if (getBlock(x + offsetX, checkY, z + offsetZ) == GameConfig.TORCH) {
                        bestDistanceSquared = distanceSquared;
                    }
                }
            }
        }
        if (bestDistanceSquared == Integer.MAX_VALUE) {
            return 0.0f;
        }
        return 0.56f + (1.0f - bestDistanceSquared / (float) (radius * radius)) * 0.38f;
    }

    float getColdSurfaceTint(int x, int y, int z) {
        if (getBlock(x, y + 1, z) == GameConfig.SNOW_LAYER
            || getBlock(x, y, z) == GameConfig.SNOW_LAYER
            || getBlock(x, y + 2, z) == GameConfig.SNOW_LAYER) {
            return 1.0f;
        }

        double temperature = biomeTemperature(x, z);
        double altitudeCold = y > GameConfig.SEA_LEVEL + 18 ? Math.min(0.35, (y - GameConfig.SEA_LEVEL - 18) / 72.0) : 0.0;
        double cold = Math.max(0.0, (-temperature - 0.08) * 2.0 + altitudeCold);
        return (float) Math.max(0.0, Math.min(1.0, cold));
    }

    float getTaigaSurfaceTint(int x, int z) {
        return getBiomeName(x, z).contains("Taiga") ? 1.0f : 0.0f;
    }

    String getBiomeName(int x, int z) {
        return worldGenerator != null ? worldGenerator.debugBiomeName(x, z) : "Unknown";
    }

    int getGeneratedSurfaceHeight(int x, int z) {
        return worldGenerator != null ? worldGenerator.debugSurfaceHeight(x, z) : calculateTerrainHeight(x, z);
    }

    int getVillageCellSizeChunks() {
        if (worldGenerator == null) {
            worldGenerator = new WorldGenerator(seed);
        }
        return worldGenerator.debugVillageCellSizeChunks();
    }

    int[] getVillageCenterForCell(int cellX, int cellZ) {
        if (worldGenerator == null) {
            worldGenerator = new WorldGenerator(seed);
        }
        return worldGenerator.debugVillageCenterForCell(cellX, cellZ);
    }

    int getActualSurfaceHeight(int x, int z) {
        ChunkColumn column = loadedColumns.get(columnKey(worldToChunk(x), worldToChunk(z)));
        if (column == null) {
            return getGeneratedSurfaceHeight(x, z);
        }
        return column.getSurfaceHeightLocal(localBlockCoordinate(x), localBlockCoordinate(z));
    }

    String getTerrainDebugInfo(int x, int z) {
        return worldGenerator != null ? worldGenerator.debugTerrainInfo(x, z) : "terrain=unknown";
    }

    String getDensityDebugInfo(int x, int y, int z) {
        return worldGenerator != null ? worldGenerator.debugDensityInfo(x, y, z) : "density=unknown";
    }

    ChunkGenerationStatus getChunkStatus(int chunkX, int chunkZ) {
        ChunkColumn column = loadedColumns.get(columnKey(chunkX, chunkZ));
        return column == null ? ChunkGenerationStatus.EMPTY : column.status;
    }

    String getRegionFileName(int chunkX, int chunkZ) {
        return RegionStorage.regionFileNameForChunk(chunkX, chunkZ);
    }

    boolean isFaceVisible(byte sourceBlock, int x, int y, int z) {
        if (!isInside(x, y, z)) {
            return true;
        }

        byte neighbor = getBlock(x, y, z);
        if (neighbor == GameConfig.AIR) {
            return true;
        }
        if (isLiquidBlock(sourceBlock)) {
            return GameConfig.fluidItemForBlock(neighbor) != GameConfig.fluidItemForBlock(sourceBlock);
        }
        if (sourceBlock == GameConfig.SNOW_LAYER) {
            return neighbor != GameConfig.SNOW_LAYER
                && (isTransparentBlock(neighbor) || !isSolidBlock(neighbor));
        }
        if (sourceBlock == GameConfig.OAK_LEAVES || sourceBlock == GameConfig.PINE_LEAVES) {
            return neighbor != sourceBlock
                && neighbor != GameConfig.OAK_LOG
                && neighbor != GameConfig.PINE_LOG;
        }
        return isTransparentBlock(neighbor) || !isSolidBlock(neighbor);
    }

    void cleanup() {
        flushLoadedColumns();
        saveContainers();
        for (Future<ChunkColumn> future : pendingColumns.values()) {
            future.cancel(true);
        }
        pendingColumns.clear();
        generationExecutor.shutdownNow();
        try {
            generationExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    boolean isTransparentBlock(byte block) {
        return block != GameConfig.AIR && !Blocks.isOpaque(block);
    }

    boolean isSolidBlock(byte block) {
        return Blocks.isSolid(block);
    }

    private boolean isCollisionSolid(BlockState state) {
        if (state == null || state.type == null) {
            return false;
        }
        if (state.type.numericId == (GameConfig.OAK_DOOR & 0xFF)) {
            return !Blocks.isDoorOpen(state);
        }
        return state.type.isSolid();
    }

    boolean isLiquidBlock(byte block) {
        return Blocks.isLiquid(block);
    }

    boolean isCrossPlant(byte block) {
        return block == GameConfig.TALL_GRASS
            || block == GameConfig.SEAGRASS
            || block == GameConfig.RED_FLOWER
            || block == GameConfig.YELLOW_FLOWER
            || block == GameConfig.WHEAT_CROP
            || block == GameConfig.TORCH;
    }

    boolean isPlantBlock(byte block) {
        return Blocks.isPlant(block);
    }

    boolean isCubeBlock(byte block) {
        return block != GameConfig.AIR && !isCrossPlant(block);
    }

    int getFluidDistance(int x, int y, int z) {
        Chunk chunk = getChunkForBlock(x, y, z);
        if (chunk == null) {
            return -1;
        }
        return chunk.getFluidDistanceLocal(localBlockCoordinate(x), GameConfig.localYForWorldY(y), localBlockCoordinate(z));
    }

    private void tickDynamicBlocks(PlayerState player) {
        int playerChunkX = player == null ? 0 : worldToChunk((int) Math.floor(player.x));
        int playerChunkZ = player == null ? 0 : worldToChunk((int) Math.floor(player.z));

        HashSet<Long> reactionCandidates = new HashSet<>();
        tickFluidSet(activeWaterCells, GameConfig.WATER, playerChunkX, playerChunkZ, reactionCandidates);
        if (worldTickCounter % GameConfig.LAVA_FLOW_STEP_INTERVAL == 0) {
            tickFluidSet(activeLavaCells, GameConfig.LAVA, playerChunkX, playerChunkZ, reactionCandidates);
        }
        resolveFluidReactions(reactionCandidates);
        tickSand(playerChunkX, playerChunkZ);
        if ((worldTickCounter & 31L) == 0L) {
            tickGrassSpread(playerChunkX, playerChunkZ);
        }
    }

    private void tickGrassSpread(int playerChunkX, int playerChunkZ) {
        for (int attempt = 0; attempt < 36; attempt++) {
            int chunkX = playerChunkX + worldRandom.nextInt(9) - 4;
            int chunkZ = playerChunkZ + worldRandom.nextInt(9) - 4;
            ChunkColumn column = loadedColumns.get(columnKey(chunkX, chunkZ));
            if (column == null) {
                continue;
            }
            int x = chunkX * GameConfig.CHUNK_SIZE + worldRandom.nextInt(GameConfig.CHUNK_SIZE);
            int z = chunkZ * GameConfig.CHUNK_SIZE + worldRandom.nextInt(GameConfig.CHUNK_SIZE);
            int y = column.getSurfaceHeightLocal(localBlockCoordinate(x), localBlockCoordinate(z));
            if (getBlock(x, y, z) != GameConfig.DIRT || getBlock(x, y + 1, z) != GameConfig.AIR) {
                continue;
            }
            if (!hasAdjacentGrass(x, y, z)) {
                continue;
            }
            setBlockState(x, y, z, GameConfig.GRASS, -1);
            markDirtyColumn(x, z);
        }
    }

    private boolean hasAdjacentGrass(int x, int y, int z) {
        return getBlock(x + 1, y, z) == GameConfig.GRASS
            || getBlock(x - 1, y, z) == GameConfig.GRASS
            || getBlock(x, y, z + 1) == GameConfig.GRASS
            || getBlock(x, y, z - 1) == GameConfig.GRASS
            || getBlock(x + 1, y + 1, z) == GameConfig.GRASS
            || getBlock(x - 1, y + 1, z) == GameConfig.GRASS
            || getBlock(x, y + 1, z + 1) == GameConfig.GRASS
            || getBlock(x, y + 1, z - 1) == GameConfig.GRASS;
    }

    private void tickFluidSet(HashSet<Long> activeCells, byte fluidItem, int playerChunkX, int playerChunkZ, HashSet<Long> reactionCandidates) {
        if (activeCells.isEmpty()) {
            return;
        }

        ArrayList<Long> snapshot = new ArrayList<>(activeCells);
        snapshot.sort(Long::compare);
        HashMap<Long, PendingBlockChange> pendingChanges = new HashMap<>();
        for (long blockKey : snapshot) {
            int x = unpackBlockX(blockKey);
            int y = unpackBlockY(blockKey);
            int z = unpackBlockZ(blockKey);
            byte block = getBlock(x, y, z);
            if (GameConfig.fluidItemForBlock(block) != fluidItem) {
                activeCells.remove(blockKey);
                continue;
            }
            if (!isChunkWithinFluidSimulationDistance(worldToChunk(x), worldToChunk(z), playerChunkX, playerChunkZ)) {
                continue;
            }
            simulateFluidCell(x, y, z, fluidItem, pendingChanges);
        }

        if (pendingChanges.isEmpty()) {
            return;
        }

        ArrayList<PendingBlockChange> orderedChanges = new ArrayList<>(pendingChanges.values());
        orderedChanges.sort((left, right) -> {
            if (left.y != right.y) {
                return Integer.compare(left.y, right.y);
            }
            if (left.x != right.x) {
                return Integer.compare(left.x, right.x);
            }
            return Integer.compare(left.z, right.z);
        });
        for (PendingBlockChange change : orderedChanges) {
            byte current = getBlock(change.x, change.y, change.z);
            if (change.block == GameConfig.AIR) {
                if (GameConfig.fluidItemForBlock(current) != fluidItem) {
                    continue;
                }
            } else if (!isReplaceableForFluid(current) && GameConfig.fluidItemForBlock(current) != fluidItem) {
                continue;
            }

            setBlockState(change.x, change.y, change.z, change.block, change.distance);
            refreshDynamicCellsAround(change.x, change.y, change.z);
            markDirtyColumn(change.x, change.z);
            reactionCandidates.add(packBlock(change.x, change.y, change.z));
        }
    }

    private void simulateFluidCell(int x, int y, int z, byte fluidItem, HashMap<Long, PendingBlockChange> pendingChanges) {
        byte block = getBlock(x, y, z);
        byte sourceBlock = GameConfig.sourceBlockForFluid(fluidItem);
        byte flowingBlock = GameConfig.flowingBlockForFluid(fluidItem);
        int maxDistance = GameConfig.fluidSpreadDistance(fluidItem);
        boolean hasFluidAbove = GameConfig.fluidItemForBlock(getBlock(x, y + 1, z)) == fluidItem;

        if (fluidItem == GameConfig.WATER && countCurrentAdjacentWaterSources(x, y, z) >= 2) {
            scheduleBlockChange(pendingChanges, x, y, z, sourceBlock, 0);
        } else if (block != sourceBlock) {
            int desiredDistance = hasFluidAbove ? 0 : findDesiredFluidDistance(x, y, z, fluidItem);
            if (desiredDistance < 0 || desiredDistance > maxDistance) {
                if (hasAdjacentFluidConnection(x, y, z, fluidItem)) {
                    return;
                }
                scheduleBlockChange(pendingChanges, x, y, z, GameConfig.AIR, -1);
                return;
            }
            scheduleBlockChange(pendingChanges, x, y, z, flowingBlock, desiredDistance);
        }

        if (y > GameConfig.WORLD_MIN_Y && canIntroduceFluidNow(x, y - 1, z, fluidItem, 0)) {
            scheduleBlockChange(pendingChanges, x, y - 1, z, flowingBlock, 0);
        }

        int currentDistance = currentHorizontalFluidDistance(x, y, z, fluidItem);
        int nextDistance = block == sourceBlock || hasFluidAbove ? 1 : currentDistance + 1;
        if (nextDistance > maxDistance) {
            return;
        }

        tryScheduleHorizontalFlow(pendingChanges, x + 1, y, z, fluidItem, nextDistance);
        tryScheduleHorizontalFlow(pendingChanges, x - 1, y, z, fluidItem, nextDistance);
        tryScheduleHorizontalFlow(pendingChanges, x, y, z + 1, fluidItem, nextDistance);
        tryScheduleHorizontalFlow(pendingChanges, x, y, z - 1, fluidItem, nextDistance);
    }

    private void tryScheduleHorizontalFlow(HashMap<Long, PendingBlockChange> pendingChanges, int x, int y, int z, byte fluidItem, int distance) {
        if (!isInside(x, y, z) || !canIntroduceFluidNow(x, y, z, fluidItem, distance)) {
            return;
        }
        scheduleBlockChange(pendingChanges, x, y, z, GameConfig.flowingBlockForFluid(fluidItem), distance);
    }

    private void tickSand(int playerChunkX, int playerChunkZ) {
        if (activeSandCells.isEmpty()) {
            return;
        }

        ArrayList<Long> snapshot = new ArrayList<>(activeSandCells);
        for (long blockKey : snapshot) {
            int x = unpackBlockX(blockKey);
            int y = unpackBlockY(blockKey);
            int z = unpackBlockZ(blockKey);
            byte block = getBlock(x, y, z);
            if (!isFallingTerrainBlock(block)) {
                activeSandCells.remove(blockKey);
                continue;
            }
            if (!isChunkWithinFluidSimulationDistance(worldToChunk(x), worldToChunk(z), playerChunkX, playerChunkZ)) {
                continue;
            }
            byte below = getBlock(x, y - 1, z);
            if (!isReplaceableForFallingBlock(below)) {
                refreshDynamicCellsAround(x, y, z);
                continue;
            }

            spawnFallingBlock(block, x, y, z);
        }
    }

    private void spawnFallingBlock(byte block, int x, int y, int z) {
        long blockKey = packBlock(x, y, z);
        if (fallingBlockSources.contains(blockKey) || getBlock(x, y, z) != block) {
            return;
        }

        fallingBlockSources.add(blockKey);
        activeSandCells.remove(blockKey);
        setBlockState(x, y, z, GameConfig.AIR, -1);
        updatePlantSupportAt(x, y + 1, z);
        refreshDynamicCellsAround(x, y, z);
        markDirtyColumn(x, z);
        refreshSurfaceHeight(x, z);
        fallingBlocks.add(new FallingBlock(block, x, y, z));
    }

    private void updateFallingBlocks(double deltaTime) {
        if (fallingBlocks.isEmpty()) {
            return;
        }

        for (int i = fallingBlocks.size() - 1; i >= 0; i--) {
            FallingBlock fallingBlock = fallingBlocks.get(i);
            fallingBlock.verticalVelocity = Math.max(
                fallingBlock.verticalVelocity - GameConfig.GRAVITY * deltaTime,
                -GameConfig.TERMINAL_VELOCITY
            );
            double previousY = fallingBlock.y;
            fallingBlock.y += fallingBlock.verticalVelocity * deltaTime;

            int blockX = (int) Math.floor(fallingBlock.x);
            int blockZ = (int) Math.floor(fallingBlock.z);
            if (!isChunkLoaded(worldToChunk(blockX), worldToChunk(blockZ))) {
                continue;
            }

            int landingY = findFallingBlockLandingY(blockX, (int) Math.floor(previousY), (int) Math.floor(fallingBlock.y), blockZ);
            if (fallingBlock.y <= landingY + COLLISION_EPSILON || landingY >= GameConfig.WORLD_MAX_Y) {
                bakeFallingBlock(fallingBlock, blockX, landingY, blockZ);
                fallingBlocks.remove(i);
            }
        }
    }

    private int findFallingBlockLandingY(int x, int previousY, int currentY, int z) {
        int startY = clamp(Math.max(previousY, currentY), GameConfig.WORLD_MIN_Y + 1, GameConfig.WORLD_MAX_Y);
        int endY = clamp(Math.min(previousY, currentY), GameConfig.WORLD_MIN_Y, GameConfig.WORLD_MAX_Y - 1);
        for (int y = startY - 1; y >= endY; y--) {
            if (!isReplaceableForFallingBlock(getBlock(x, y, z))) {
                return Math.min(y + 1, GameConfig.WORLD_MAX_Y);
            }
        }
        return findFallingBlockLandingY(x, currentY, z);
    }

    private int findFallingBlockLandingY(int x, int currentY, int z) {
        int startY = clamp(currentY, GameConfig.WORLD_MIN_Y + 1, GameConfig.WORLD_MAX_Y);
        for (int y = startY - 1; y >= GameConfig.WORLD_MIN_Y; y--) {
            if (!isReplaceableForFallingBlock(getBlock(x, y, z))) {
                return Math.min(y + 1, GameConfig.WORLD_MAX_Y);
            }
        }
        return GameConfig.WORLD_MIN_Y;
    }

    private void bakeFallingBlock(FallingBlock fallingBlock, int x, int y, int z) {
        fallingBlockSources.remove(packBlock(fallingBlock.blockX, fallingBlock.blockY, fallingBlock.blockZ));

        int placeY = clamp(y, GameConfig.WORLD_MIN_Y, GameConfig.WORLD_MAX_Y);
        while (placeY < GameConfig.WORLD_MAX_Y && !isReplaceableForFallingBlock(getBlock(x, placeY, z))) {
            placeY++;
        }

        setBlockState(x, placeY, z, fallingBlock.blockId, -1);
        updatePlantSupportAt(x, placeY + 1, z);
        refreshDynamicCellsAround(x, placeY, z);
        markDirtyColumn(x, z);
        refreshSurfaceHeight(x, z);
    }

    private void updateFurnaces(double deltaTime) {
        if (furnaces.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Long, FurnaceBlockEntity>> iterator = furnaces.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, FurnaceBlockEntity> entry = iterator.next();
            long key = entry.getKey();
            int x = unpackBlockX(key);
            int y = unpackBlockY(key);
            int z = unpackBlockZ(key);
            if (getBlock(x, y, z) != GameConfig.FURNACE) {
                iterator.remove();
                continue;
            }
            if (!isChunkLoaded(worldToChunk(x), worldToChunk(z))) {
                continue;
            }
            entry.getValue().tick(deltaTime);
        }
    }

    private void resolveFluidReactions(HashSet<Long> reactionCandidates) {
        for (long blockKey : reactionCandidates) {
            int x = unpackBlockX(blockKey);
            int y = unpackBlockY(blockKey);
            int z = unpackBlockZ(blockKey);
            resolveFluidReactionAt(x, y, z);
            resolveFluidReactionAt(x + 1, y, z);
            resolveFluidReactionAt(x - 1, y, z);
            resolveFluidReactionAt(x, y + 1, z);
            resolveFluidReactionAt(x, y - 1, z);
            resolveFluidReactionAt(x, y, z + 1);
            resolveFluidReactionAt(x, y, z - 1);
        }
    }

    private void resolveFluidReactionAt(int x, int y, int z) {
        byte lavaBlock = getBlock(x, y, z);
        if (!GameConfig.isLavaBlock(lavaBlock)) {
            return;
        }

        byte waterBlock = GameConfig.AIR;
        if (GameConfig.isWaterBlock(getBlock(x + 1, y, z))) {
            waterBlock = getBlock(x + 1, y, z);
        } else if (GameConfig.isWaterBlock(getBlock(x - 1, y, z))) {
            waterBlock = getBlock(x - 1, y, z);
        } else if (GameConfig.isWaterBlock(getBlock(x, y + 1, z))) {
            waterBlock = getBlock(x, y + 1, z);
        } else if (GameConfig.isWaterBlock(getBlock(x, y - 1, z))) {
            waterBlock = getBlock(x, y - 1, z);
        } else if (GameConfig.isWaterBlock(getBlock(x, y, z + 1))) {
            waterBlock = getBlock(x, y, z + 1);
        } else if (GameConfig.isWaterBlock(getBlock(x, y, z - 1))) {
            waterBlock = getBlock(x, y, z - 1);
        }

        if (waterBlock == GameConfig.AIR) {
            return;
        }

        setBlockState(x, y, z, getFluidReactionBlock(waterBlock, lavaBlock), -1);
        refreshDynamicCellsAround(x, y, z);
        markDirtyColumn(x, z);
        refreshSurfaceHeight(x, z);
    }

    private void scheduleBlockChange(HashMap<Long, PendingBlockChange> pendingChanges, int x, int y, int z, byte block, int distance) {
        if (!isInside(x, y, z)) {
            return;
        }
        byte currentBlock = getBlock(x, y, z);
        byte currentFluid = GameConfig.fluidItemForBlock(currentBlock);
        byte changeFluid = GameConfig.fluidItemForBlock(block);
        if (currentFluid != GameConfig.AIR && currentBlock == GameConfig.sourceBlockForFluid(currentFluid)) {
            if (block == GameConfig.AIR || block == GameConfig.flowingBlockForFluid(currentFluid)) {
                return;
            }
        }

        long key = packBlock(x, y, z);
        PendingBlockChange existing = pendingChanges.get(key);
        if (existing != null) {
            byte existingFluid = GameConfig.fluidItemForBlock(existing.block);
            if (existingFluid != GameConfig.AIR && existing.block == GameConfig.sourceBlockForFluid(existingFluid)
                && block != existing.block) {
                return;
            }
            if (changeFluid != GameConfig.AIR && block == GameConfig.sourceBlockForFluid(changeFluid)) {
                pendingChanges.put(key, new PendingBlockChange(x, y, z, block, distance));
                return;
            }
        }
        pendingChanges.put(key, new PendingBlockChange(x, y, z, block, distance));
    }

    private int findDesiredFluidDistance(int x, int y, int z, byte fluidItem) {
        if (GameConfig.fluidItemForBlock(getBlock(x, y + 1, z)) == fluidItem) {
            return 0;
        }

        int bestDistance = Integer.MAX_VALUE;
        bestDistance = findBestNeighborDistance(bestDistance, x + 1, y, z, fluidItem);
        bestDistance = findBestNeighborDistance(bestDistance, x - 1, y, z, fluidItem);
        bestDistance = findBestNeighborDistance(bestDistance, x, y, z + 1, fluidItem);
        bestDistance = findBestNeighborDistance(bestDistance, x, y, z - 1, fluidItem);
        return bestDistance == Integer.MAX_VALUE ? -1 : bestDistance;
    }

    private boolean hasAdjacentFluidConnection(int x, int y, int z, byte fluidItem) {
        return GameConfig.fluidItemForBlock(getBlock(x, y + 1, z)) == fluidItem
            || GameConfig.fluidItemForBlock(getBlock(x + 1, y, z)) == fluidItem
            || GameConfig.fluidItemForBlock(getBlock(x - 1, y, z)) == fluidItem
            || GameConfig.fluidItemForBlock(getBlock(x, y, z + 1)) == fluidItem
            || GameConfig.fluidItemForBlock(getBlock(x, y, z - 1)) == fluidItem;
    }

    private int findBestNeighborDistance(int currentBest, int x, int y, int z, byte fluidItem) {
        int distance = currentHorizontalFluidDistance(x, y, z, fluidItem);
        if (distance < 0) {
            return currentBest;
        }
        return Math.min(currentBest, distance + 1);
    }

    private int countCurrentAdjacentWaterSources(int x, int y, int z) {
        int neighboringSources = 0;
        if (getBlock(x + 1, y, z) == GameConfig.WATER_SOURCE) {
            neighboringSources++;
        }
        if (getBlock(x - 1, y, z) == GameConfig.WATER_SOURCE) {
            neighboringSources++;
        }
        if (getBlock(x, y, z + 1) == GameConfig.WATER_SOURCE) {
            neighboringSources++;
        }
        if (getBlock(x, y, z - 1) == GameConfig.WATER_SOURCE) {
            neighboringSources++;
        }
        return neighboringSources;
    }

    private boolean canIntroduceFluidNow(int x, int y, int z, byte fluidItem, int desiredDistance) {
        byte currentBlock = getBlock(x, y, z);
        if (currentBlock == GameConfig.sourceBlockForFluid(fluidItem)) {
            return false;
        }
        if (!isReplaceableForFluid(currentBlock) && GameConfig.fluidItemForBlock(currentBlock) != fluidItem) {
            return false;
        }
        if (desiredDistance <= 0) {
            return true;
        }
        return hasCurrentHorizontalFluidSupport(x, y, z, fluidItem, desiredDistance);
    }

    private boolean hasCurrentHorizontalFluidSupport(int x, int y, int z, byte fluidItem, int desiredDistance) {
        return hasNeighborDistance(x + 1, y, z, fluidItem, desiredDistance)
            || hasNeighborDistance(x - 1, y, z, fluidItem, desiredDistance)
            || hasNeighborDistance(x, y, z + 1, fluidItem, desiredDistance)
            || hasNeighborDistance(x, y, z - 1, fluidItem, desiredDistance);
    }

    private boolean hasNeighborDistance(int x, int y, int z, byte fluidItem, int desiredDistance) {
        int neighborDistance = currentHorizontalFluidDistance(x, y, z, fluidItem);
        return neighborDistance >= 0 && neighborDistance + 1 == desiredDistance;
    }

    private int currentHorizontalFluidDistance(int x, int y, int z, byte fluidItem) {
        byte block = getBlock(x, y, z);
        if (GameConfig.fluidItemForBlock(block) != fluidItem) {
            return -1;
        }
        if (GameConfig.isFluidSourceBlock(block)) {
            return 0;
        }
        if (GameConfig.fluidItemForBlock(getBlock(x, y + 1, z)) == fluidItem) {
            return 0;
        }
        int distance = getFluidDistance(x, y, z);
        return distance < 0 ? -1 : distance;
    }

    private void getFluidFlowVector(int x, int y, int z, MutableVec3 output) {
        byte block = getBlock(x, y, z);
        if (!GameConfig.isWaterBlock(block)) {
            output.set(0.0, 0.0, 0.0);
            return;
        }

        long cacheKey = packBlock(x, y, z);
        CachedFluidFlow cachedFlow = waterFlowCache.get(cacheKey);
        if (cachedFlow != null) {
            output.set(cachedFlow.x, cachedFlow.y, cachedFlow.z);
            return;
        }

        int currentDistance = Math.max(0, getFluidDistance(x, y, z));
        double currentHeight = getFluidSurfaceHeight(x, y, z);
        double flowX = 0.0;
        double flowY = 0.0;
        double flowZ = 0.0;

        flowX = accumulateFlow(flowX, currentDistance, currentHeight, x + 1, y, z, 1, 0);
        flowX = accumulateFlow(flowX, currentDistance, currentHeight, x - 1, y, z, -1, 0);
        flowZ = accumulateFlow(flowZ, currentDistance, currentHeight, x, y, z + 1, 1, 0, true);
        flowZ = accumulateFlow(flowZ, currentDistance, currentHeight, x, y, z - 1, -1, 0, true);

        if (isWaterFallEdge(x, y, z + 1)) {
            flowZ += 1.8;
            flowY -= 0.8;
        }
        if (isWaterFallEdge(x, y, z - 1)) {
            flowZ -= 1.8;
            flowY -= 0.8;
        }
        if (isWaterFallEdge(x + 1, y, z)) {
            flowX += 1.8;
            flowY -= 0.8;
        }
        if (isWaterFallEdge(x - 1, y, z)) {
            flowX -= 1.8;
            flowY -= 0.8;
        }
        if (y > GameConfig.WORLD_MIN_Y && canFluidOccupyBlock(getBlock(x, y - 1, z), GameConfig.WATER)) {
            flowY -= 1.2;
        }

        double magnitude = Math.sqrt(flowX * flowX + flowY * flowY + flowZ * flowZ);
        if (magnitude < 1.0e-8) {
            waterFlowCache.put(cacheKey, new CachedFluidFlow(0.0, 0.0, 0.0));
            output.set(0.0, 0.0, 0.0);
            return;
        }
        double normalizedX = flowX / magnitude;
        double normalizedY = flowY / magnitude;
        double normalizedZ = flowZ / magnitude;
        waterFlowCache.put(cacheKey, new CachedFluidFlow(normalizedX, normalizedY, normalizedZ));
        output.set(normalizedX, normalizedY, normalizedZ);
    }

    private double accumulateFlow(double current, int currentDistance, double currentHeight,
                                  int neighborX, int neighborY, int neighborZ,
                                  int direction, int unused) {
        return accumulateFlow(current, currentDistance, currentHeight, neighborX, neighborY, neighborZ, direction, unused, false);
    }

    private double accumulateFlow(double current, int currentDistance, double currentHeight,
                                  int neighborX, int neighborY, int neighborZ,
                                  int direction, int unused, boolean zAxis) {
        byte neighbor = getBlock(neighborX, neighborY, neighborZ);
        if (GameConfig.isWaterBlock(neighbor)) {
            int neighborDistance = Math.max(0, getFluidDistance(neighborX, neighborY, neighborZ));
            double neighborHeight = getFluidSurfaceHeight(neighborX, neighborY, neighborZ);
            double weight = Math.max(0.0, neighborDistance - currentDistance)
                + Math.max(0.0, currentHeight - neighborHeight) * 3.0;
            if (weight > 0.0) {
                current += direction * weight;
            }
        } else if (isReplaceableForFluid(neighbor)) {
            current += direction * 0.25;
        }
        return current;
    }

    private boolean isWaterFallEdge(int x, int y, int z) {
        if (!isInside(x, y, z) || y <= GameConfig.WORLD_MIN_Y) {
            return false;
        }
        byte sameLevel = getBlock(x, y, z);
        if (!canFluidOccupyBlock(sameLevel, GameConfig.WATER) && !GameConfig.isWaterBlock(sameLevel)) {
            return false;
        }
        byte below = getBlock(x, y - 1, z);
        return canFluidOccupyBlock(below, GameConfig.WATER) && !GameConfig.isWaterBlock(below);
    }

    private boolean canFluidOccupyBlock(byte block, byte fluidItem) {
        return isReplaceableForFluid(block) || GameConfig.fluidItemForBlock(block) == fluidItem;
    }

    private void updateZombieAi(Zombie zombie, PlayerState player, double deltaTime) {
        if (zombie.kind == MobKind.VILLAGER) {
            updateVillagerAi(zombie, deltaTime);
            return;
        }
        zombie.wanderTime -= deltaTime;
        if (zombie.wanderTime <= 0.0) {
            if (zombie.resting) {
                zombie.resting = false;
                chooseZombieDirection(zombie, true);
                zombie.wanderTime = 1.4 + zombie.random.nextDouble() * 1.8;
            } else {
                zombie.resting = true;
                zombie.wanderTime = 0.8 + zombie.random.nextDouble() * 1.2;
            }
        }

        double targetVelocityX = 0.0;
        double targetVelocityZ = 0.0;
        boolean pursuingPlayer = isHostileMob(zombie) && canPursuePlayer(zombie, player);
        if (isPassiveMob(zombie) && zombie.fleeTimer > 0.0 && player != null) {
            double deltaX = zombie.x - player.x;
            double deltaZ = zombie.z - player.z;
            double horizontalDistanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (horizontalDistanceSquared > 1.0e-8) {
                double horizontalDistance = Math.sqrt(horizontalDistanceSquared);
                double fleeSpeed = GameConfig.ZOMBIE_WANDER_SPEED * 1.65;
                targetVelocityX = deltaX / horizontalDistance * fleeSpeed;
                targetVelocityZ = deltaZ / horizontalDistance * fleeSpeed;
                zombie.targetBodyYaw = Math.atan2(deltaZ, deltaX);
                zombie.resting = false;
            }
        } else if (pursuingPlayer) {
            zombie.resting = false;
            double deltaX = player.x - zombie.x;
            double deltaZ = player.z - zombie.z;
            double horizontalDistanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (horizontalDistanceSquared > 1.0e-8) {
                double horizontalDistance = Math.sqrt(horizontalDistanceSquared);
                targetVelocityX = deltaX / horizontalDistance * GameConfig.ZOMBIE_CHASE_SPEED;
                targetVelocityZ = deltaZ / horizontalDistance * GameConfig.ZOMBIE_CHASE_SPEED;
                steerZombieAroundObstacle(zombie, targetVelocityX, targetVelocityZ, zombieSteering);
                targetVelocityX = zombieSteering.x;
                targetVelocityZ = zombieSteering.z;
                zombie.targetBodyYaw = Math.atan2(deltaZ, deltaX);
            }
        } else if (!zombie.resting) {
            double wanderSpeed = isHostileMob(zombie) ? GameConfig.ZOMBIE_WANDER_SPEED : GameConfig.ZOMBIE_WANDER_SPEED * 0.62;
            targetVelocityX = directionMoveX(zombie.directionIndex) * wanderSpeed;
            targetVelocityZ = directionMoveZ(zombie.directionIndex) * wanderSpeed;
            zombie.targetBodyYaw = angleForDirectionIndex(zombie.directionIndex);
        } else {
            zombie.targetBodyYaw = zombie.bodyYaw;
        }

        calculateZombieSeparation(zombie, zombieSeparation);
        targetVelocityX += zombieSeparation.x;
        targetVelocityZ += zombieSeparation.z;

        double targetSpeedSquared = targetVelocityX * targetVelocityX + targetVelocityZ * targetVelocityZ;
        double maxSpeed = isHostileMob(zombie)
            ? GameConfig.ZOMBIE_CHASE_SPEED
            : GameConfig.ZOMBIE_WANDER_SPEED * (zombie.fleeTimer > 0.0 ? 1.75 : 0.72);
        if (targetSpeedSquared > maxSpeed * maxSpeed) {
            double scale = maxSpeed / Math.sqrt(targetSpeedSquared);
            targetVelocityX *= scale;
            targetVelocityZ *= scale;
        }

        double blend = Math.min(1.0, deltaTime * 8.0);
        zombie.velocityX += (targetVelocityX - zombie.velocityX) * blend;
        zombie.velocityZ += (targetVelocityZ - zombie.velocityZ) * blend;
    }

    private void updateVillagerAi(Zombie villager, double deltaTime) {
        villager.wanderTime -= deltaTime;
        double homeDistance = Math.sqrt(distanceSquared(villager.x, villager.z, villager.homeX, villager.homeZ));
        boolean night = !isDaytime();
        if (homeDistance > 24.0) {
            double dx = villager.homeX - villager.x;
            double dz = villager.homeZ - villager.z;
            double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
            villager.velocityX += (dx / len * 0.85 - villager.velocityX) * Math.min(1.0, deltaTime * 5.0);
            villager.velocityZ += (dz / len * 0.85 - villager.velocityZ) * Math.min(1.0, deltaTime * 5.0);
            villager.targetBodyYaw = Math.atan2(dz, dx);
            return;
        }
        if (villager.wanderTime <= 0.0) {
            villager.resting = villager.random.nextDouble() < (night ? 0.78 : 0.34);
            chooseZombieDirection(villager, true);
            villager.wanderTime = night
                ? 2.4 + villager.random.nextDouble() * 3.2
                : 1.2 + villager.random.nextDouble() * 2.4;
        }
        double targetVelocityX = 0.0;
        double targetVelocityZ = 0.0;
        if (!villager.resting) {
            double speed = night ? 0.26 : 0.56;
            targetVelocityX = directionMoveX(villager.directionIndex) * speed;
            targetVelocityZ = directionMoveZ(villager.directionIndex) * speed;
            villager.targetBodyYaw = angleForDirectionIndex(villager.directionIndex);
        }
        calculateZombieSeparation(villager, zombieSeparation);
        targetVelocityX += zombieSeparation.x * 0.5;
        targetVelocityZ += zombieSeparation.z * 0.5;
        double blend = Math.min(1.0, deltaTime * 5.0);
        villager.velocityX += (targetVelocityX - villager.velocityX) * blend;
        villager.velocityZ += (targetVelocityZ - villager.velocityZ) * blend;
    }

    private void updateZombieMovement(Zombie zombie, boolean inWater, double deltaTime) {
        if (inWater) {
            sampleFluidFlow(zombie.x, zombie.y, zombie.z, GameConfig.ZOMBIE_RADIUS, GameConfig.ZOMBIE_HEIGHT, GameConfig.WATER, sampledFluidFlow);
            zombie.velocityX += sampledFluidFlow.x * GameConfig.WATER_ENTITY_FLOW_PUSH * deltaTime;
            zombie.velocityZ += sampledFluidFlow.z * GameConfig.WATER_ENTITY_FLOW_PUSH * deltaTime;
            zombie.verticalVelocity += sampledFluidFlow.y * GameConfig.WATER_VERTICAL_FLOW_PUSH * deltaTime;
            zombie.velocityX *= 0.82;
            zombie.velocityZ *= 0.82;
            zombie.verticalVelocity -= GameConfig.WATER_SINK_ACCELERATION * deltaTime;
            double tickScale = deltaTime / GameConfig.PHYSICS_TICK_SECONDS;
            zombie.verticalVelocity *= Math.pow(GameConfig.WATER_DRAG_PER_TICK, tickScale);
        }

        double moveX = zombie.velocityX * deltaTime;
        double moveZ = zombie.velocityZ * deltaTime;
        boolean movedHorizontally = moveZombieHorizontal(zombie, moveX, moveZ, deltaTime);
        if (!movedHorizontally && (Math.abs(moveX) > 1.0e-8 || Math.abs(moveZ) > 1.0e-8)) {
            zombie.velocityX *= 0.25;
            zombie.velocityZ *= 0.25;
            redirectZombieAfterCollision(zombie);
        }

        if (Double.isNaN(zombie.stepTargetY)) {
            if (!inWater) {
                zombie.verticalVelocity = Math.max(zombie.verticalVelocity - GameConfig.GRAVITY * deltaTime, -GameConfig.TERMINAL_VELOCITY);
            }
            moveZombieVertical(zombie, zombie.verticalVelocity * deltaTime);
            zombie.isGrounded = isZombieStandingOnGround(zombie);
        }
        zombie.bodyYaw = turnTowardAngle(zombie.bodyYaw, zombie.targetBodyYaw, GameConfig.ZOMBIE_TURN_SPEED * deltaTime);

        double horizontalSpeed = Math.sqrt(zombie.velocityX * zombie.velocityX + zombie.velocityZ * zombie.velocityZ);
        if (movedHorizontally && horizontalSpeed > 0.02) {
            zombie.walkCycle += horizontalSpeed * deltaTime * (zombie.isGrounded ? 8.0 : 5.0);
        } else {
            zombie.walkCycle += (0.0 - zombie.walkCycle) * Math.min(1.0, deltaTime * 7.0);
        }
    }

    private boolean moveZombieHorizontal(Zombie zombie, double moveX, double moveZ, double deltaTime) {
        if (Math.abs(moveX) < 1e-8 && Math.abs(moveZ) < 1e-8) {
            return false;
        }

        double nextX = zombie.x + moveX;
        double nextZ = zombie.z + moveZ;
        if (canZombieOccupy(nextX, zombie.y, nextZ)) {
            zombie.x = nextX;
            zombie.z = nextZ;
            zombie.stepTargetY = Double.NaN;
            return true;
        }
        if ((zombie.isGrounded || !Double.isNaN(zombie.stepTargetY)) && canZombieStepUp(zombie, moveX, moveZ)) {
            if (Double.isNaN(zombie.stepTargetY) || zombie.stepTargetY < zombie.y + 0.5) {
                zombie.stepTargetY = zombie.y + 1.0;
            }
            double raisedY = Math.min(zombie.stepTargetY, zombie.y + GameConfig.ZOMBIE_STEP_SPEED * deltaTime);
            if (canZombieOccupy(zombie.x, raisedY, zombie.z)) {
                zombie.y = raisedY;
            }
            zombie.verticalVelocity = 0.0;
            zombie.isGrounded = false;
            if (zombie.y + 1.0e-4 >= zombie.stepTargetY && canZombieOccupy(nextX, zombie.y, nextZ)) {
                zombie.x = nextX;
                zombie.z = nextZ;
                zombie.stepTargetY = Double.NaN;
            }
            return true;
        }

        zombie.stepTargetY = Double.NaN;
        return false;
    }

    private void moveZombieVertical(Zombie zombie, double moveY) {
        if (Math.abs(moveY) < 1e-8) {
            return;
        }

        double nextY = zombie.y + moveY;
        if (!collides(zombie.x, nextY, zombie.z, GameConfig.ZOMBIE_RADIUS, GameConfig.ZOMBIE_HEIGHT)) {
            zombie.y = nextY;
            return;
        }

        if (moveY > 0.0) {
            int guard = 0;
            while (collides(zombie.x, nextY, zombie.z, GameConfig.ZOMBIE_RADIUS, GameConfig.ZOMBIE_HEIGHT) && guard++ < 256) {
                nextY -= 0.01;
            }
        } else {
            int guard = 0;
            while (collides(zombie.x, nextY, zombie.z, GameConfig.ZOMBIE_RADIUS, GameConfig.ZOMBIE_HEIGHT) && guard++ < 256) {
                nextY += 0.01;
            }
            zombie.isGrounded = true;
        }

        zombie.y = nextY;
        zombie.verticalVelocity = 0.0;
    }

    private boolean canZombieStepUp(Zombie zombie, double moveX, double moveZ) {
        if (Math.abs(moveX) < 1e-8 && Math.abs(moveZ) < 1e-8) {
            return false;
        }

        double nextX = zombie.x + moveX;
        double nextZ = zombie.z + moveZ;
        if (!collides(nextX, zombie.y, nextZ, GameConfig.ZOMBIE_RADIUS, GameConfig.ZOMBIE_HEIGHT)) {
            return false;
        }
        return canZombieOccupy(nextX, zombie.y + 1.0, nextZ);
    }

    private boolean isZombieStandingOnGround(Zombie zombie) {
        return collides(zombie.x, zombie.y - 0.05, zombie.z, GameConfig.ZOMBIE_RADIUS, GameConfig.ZOMBIE_HEIGHT);
    }

    private boolean canZombieOccupy(double x, double y, double z) {
        int chunkX = worldToChunk((int) Math.floor(x));
        int chunkZ = worldToChunk((int) Math.floor(z));
        if (!isChunkLoaded(chunkX, chunkZ)) {
            return false;
        }
        return !collides(x, y, z, GameConfig.ZOMBIE_RADIUS, GameConfig.ZOMBIE_HEIGHT);
    }

    private void chooseZombieDirection(Zombie zombie, boolean preferHomeAxis) {
        int directionIndex;
        if (preferHomeAxis && distanceSquared(zombie.x, zombie.z, zombie.homeX, zombie.homeZ) > 4.0) {
            double deltaX = zombie.homeX - zombie.x;
            double deltaZ = zombie.homeZ - zombie.z;
            if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
                directionIndex = deltaX >= 0.0 ? 0 : 2;
            } else {
                directionIndex = deltaZ >= 0.0 ? 1 : 3;
            }
        } else {
            directionIndex = zombie.random.nextInt(4);
        }

        zombie.directionIndex = directionIndex;
        zombie.wanderDirection = angleForDirectionIndex(directionIndex);
        zombie.targetBodyYaw = zombie.wanderDirection;
    }

    private void redirectZombieAfterCollision(Zombie zombie) {
        if (tryRedirectZombie(zombie, (zombie.directionIndex + 2) & 3)
            || tryRedirectZombie(zombie, (zombie.directionIndex + 1) & 3)
            || tryRedirectZombie(zombie, (zombie.directionIndex + 3) & 3)) {
            return;
        }

        zombie.resting = true;
        zombie.wanderTime = 0.8 + zombie.random.nextDouble() * 1.2;
        zombie.targetBodyYaw = angleForDirectionIndex(zombie.random.nextInt(4));
    }

    private boolean tryRedirectZombie(Zombie zombie, int candidate) {
        double moveX = directionMoveX(candidate) * 0.25;
        double moveZ = directionMoveZ(candidate) * 0.25;
        boolean canStep = zombie.isGrounded && canZombieStepUp(zombie, moveX, moveZ);
        if (!canZombieOccupy(zombie.x + moveX, zombie.y, zombie.z + moveZ) && !canStep) {
            return false;
        }

        zombie.directionIndex = candidate;
        zombie.wanderDirection = angleForDirectionIndex(candidate);
        zombie.targetBodyYaw = zombie.wanderDirection;
        zombie.resting = false;
        zombie.wanderTime = 1.5 + zombie.random.nextDouble() * 1.5;
        return true;
    }

    private double directionMoveX(int directionIndex) {
        switch (directionIndex & 3) {
            case 0:
                return 1.0;
            case 2:
                return -1.0;
            default:
                return 0.0;
        }
    }

    private double directionMoveZ(int directionIndex) {
        switch (directionIndex & 3) {
            case 1:
                return 1.0;
            case 3:
                return -1.0;
            default:
                return 0.0;
        }
    }

    private double angleForDirectionIndex(int directionIndex) {
        return (directionIndex & 3) * Math.PI * 0.5;
    }

    private double turnTowardAngle(double current, double target, double maxStep) {
        double delta = wrapAngle(target - current);
        if (delta > maxStep) {
            delta = maxStep;
        } else if (delta < -maxStep) {
            delta = -maxStep;
        }
        return wrapAngle(current + delta);
    }

    private double wrapAngle(double angle) {
        while (angle <= -Math.PI) {
            angle += Math.PI * 2.0;
        }
        while (angle > Math.PI) {
            angle -= Math.PI * 2.0;
        }
        return angle;
    }

    private void steerZombieAroundObstacle(Zombie zombie, double velocityX, double velocityZ, MutableVec2 output) {
        double speedSquared = velocityX * velocityX + velocityZ * velocityZ;
        if (speedSquared < 1.0e-8) {
            output.set(0.0, 0.0);
            return;
        }

        double speed = Math.sqrt(speedSquared);
        double probeScale = 0.35 / speed;
        double probeX = velocityX * probeScale;
        double probeZ = velocityZ * probeScale;
        if (canZombieOccupy(zombie.x + probeX, zombie.y, zombie.z + probeZ)
            || (zombie.isGrounded && canZombieStepUp(zombie, probeX, probeZ))) {
            output.set(velocityX, velocityZ);
            return;
        }

        double signX = Math.signum(velocityX);
        double signZ = Math.signum(velocityZ);
        if (Math.abs(velocityX) >= Math.abs(velocityZ)) {
            if (signX != 0.0 && canZombieOccupy(zombie.x + signX * 0.35, zombie.y, zombie.z)) {
                output.set(signX * speed, 0.0);
                return;
            }
            if (signZ != 0.0 && canZombieOccupy(zombie.x, zombie.y, zombie.z + signZ * 0.35)) {
                output.set(0.0, signZ * speed);
                return;
            }
        } else {
            if (signZ != 0.0 && canZombieOccupy(zombie.x, zombie.y, zombie.z + signZ * 0.35)) {
                output.set(0.0, signZ * speed);
                return;
            }
            if (signX != 0.0 && canZombieOccupy(zombie.x + signX * 0.35, zombie.y, zombie.z)) {
                output.set(signX * speed, 0.0);
                return;
            }
        }

        double leftX = -velocityZ / speed * speed;
        double leftZ = velocityX / speed * speed;
        if (canZombieOccupy(zombie.x + leftX / speed * 0.35, zombie.y, zombie.z + leftZ / speed * 0.35)) {
            output.set(leftX, leftZ);
            return;
        }
        if (canZombieOccupy(zombie.x - leftX / speed * 0.35, zombie.y, zombie.z - leftZ / speed * 0.35)) {
            output.set(-leftX, -leftZ);
            return;
        }
        output.set(0.0, 0.0);
    }

    private void calculateZombieSeparation(Zombie zombie, MutableVec2 output) {
        double separationX = 0.0;
        double separationZ = 0.0;
        double separationDistanceSquared = GameConfig.ZOMBIE_SEPARATION_DISTANCE * GameConfig.ZOMBIE_SEPARATION_DISTANCE;
        for (Zombie other : zombies) {
            if (other == zombie) {
                continue;
            }
            double deltaX = zombie.x - other.x;
            double deltaZ = zombie.z - other.z;
            double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
            if (distanceSquared < 1.0e-6 || distanceSquared > separationDistanceSquared) {
                continue;
            }
            double distance = Math.sqrt(distanceSquared);
            double strength = (GameConfig.ZOMBIE_SEPARATION_DISTANCE - distance) / GameConfig.ZOMBIE_SEPARATION_DISTANCE;
            separationX += deltaX / distance * strength * 0.85;
            separationZ += deltaZ / distance * strength * 0.85;
        }
        output.set(separationX, separationZ);
    }

    private boolean canPursuePlayer(Zombie zombie, PlayerState player) {
        if (player == null || player.creativeMode || player.spectatorMode || player.health <= 0) {
            return false;
        }
        if (Math.abs(player.y - zombie.y) > GameConfig.ZOMBIE_AGGRO_Y_LIMIT) {
            return false;
        }
        return distanceSquared(zombie.x, zombie.z, player.x, player.z) <= GameConfig.ZOMBIE_VIEW_DISTANCE * GameConfig.ZOMBIE_VIEW_DISTANCE;
    }

    private void maybeQueueZombieGrowl(Zombie zombie, PlayerState player, double deltaTime) {
        if (!canPursuePlayer(zombie, player) || zombie.growlCooldown > 0.0) {
            zombie.growlCooldown = Math.max(0.0, zombie.growlCooldown - deltaTime);
            return;
        }
        double distanceToPlayerSquared = distanceSquared(zombie.x, zombie.z, player.x, player.z);
        if (distanceToPlayerSquared < ZOMBIE_GROWL_DISTANCE_SQUARED
            && zombie.random.nextDouble() < deltaTime * 0.45) {
            zombie.growlCooldown = 4.0 + zombie.random.nextDouble() * 5.0;
            zombie.growlQueued = true;
        }
    }

    private void maybeQueuePassiveMobSound(Zombie zombie, double deltaTime) {
        zombie.growlCooldown = Math.max(0.0, zombie.growlCooldown - deltaTime);
        if (zombie.growlCooldown > 0.0) {
            return;
        }
        if (zombie.random.nextDouble() < deltaTime * 0.10) {
            zombie.growlCooldown = 5.0 + zombie.random.nextDouble() * 7.0;
            zombie.growlQueued = true;
        }
    }

    private void tryZombieAttack(Zombie zombie, PlayerState player) {
        zombie.attackCooldown = Math.max(0.0, zombie.attackCooldown - GameConfig.PHYSICS_TICK_SECONDS);
        if (!canPursuePlayer(zombie, player) || zombie.attackCooldown > 0.0 || player.health <= 0) {
            return;
        }
        double distanceToPlayerSquared = distanceSquared(zombie.x, zombie.z, player.x, player.z);
        if (distanceToPlayerSquared <= GameConfig.ZOMBIE_ATTACK_RANGE * GameConfig.ZOMBIE_ATTACK_RANGE
            && Math.abs((zombie.y + 0.9) - (player.y + 0.9)) <= 1.35) {
            applyPlayerDamage(player, GameConfig.ZOMBIE_ATTACK_DAMAGE);
            zombie.attackCooldown = GameConfig.ZOMBIE_ATTACK_COOLDOWN;
            zombie.growlQueued = true;
        }
    }

    private void applyPlayerDamage(PlayerState player, double amount) {
        int armor = Math.max(0, Math.min(20, player.armorProtection));
        double protectedDamage = Math.ceil(amount * (1.0 - armor * 0.04) * 2.0) / 2.0;
        player.health = Math.max(0.0, player.health - Math.max(0.5, protectedDamage));
    }

    private void maybeSpawnZombieNearPlayer(PlayerState player) {
        if (player == null || player.creativeMode || player.spectatorMode || zombies.size() >= ZOMBIE_TARGET_COUNT || zombieSpawnCooldown > 0.0) {
            return;
        }

        for (int attempt = 0; attempt < 6; attempt++) {
            int offsetX = worldRandom.nextInt(36) - 18;
            int offsetZ = worldRandom.nextInt(36) - 18;
            if (Math.abs(offsetX) < 8 && Math.abs(offsetZ) < 8) {
                continue;
            }
            int blockX = (int) Math.floor(player.x) + offsetX;
            int blockZ = (int) Math.floor(player.z) + offsetZ;
            ensureColumnLoadedSync(worldToChunk(blockX), worldToChunk(blockZ));
            int surfaceY = getSurfaceHeight(blockX, blockZ);
            if (surfaceY <= GameConfig.WORLD_MIN_Y || surfaceY + 2 > GameConfig.WORLD_MAX_Y) {
                continue;
            }
            if (getBlock(blockX, surfaceY + 1, blockZ) != GameConfig.AIR || getBlock(blockX, surfaceY + 2, blockZ) != GameConfig.AIR) {
                continue;
            }
            if (GameConfig.isLiquidBlock(getBlock(blockX, surfaceY, blockZ))) {
                continue;
            }
            boolean day = isDaytime();
            if (day && !isExposedToSky(blockX, surfaceY + 2, blockZ) && worldRandom.nextDouble() < 0.85) {
                continue;
            }
            if (!day && isExposedToSky(blockX, surfaceY + 2, blockZ) && worldRandom.nextDouble() < 0.45) {
                continue;
            }
            MobKind kind = chooseSpawnMobKind(day, blockX, surfaceY, blockZ);
            double zombieX = blockX + 0.5;
            double zombieY = surfaceY + 1.01;
            double zombieZ = blockZ + 0.5;
            zombies.add(new Zombie(kind, zombieX, zombieY, zombieZ, zombieX, zombieZ, worldRandom));
            zombieSpawnCooldown = day ? 2.0 + worldRandom.nextDouble() * 2.4 : 1.5 + worldRandom.nextDouble() * 1.5;
            return;
        }

        zombieSpawnCooldown = 0.8;
    }

    private MobKind chooseSpawnMobKind(boolean day, int blockX, int surfaceY, int blockZ) {
        if (!day) {
            return worldRandom.nextBoolean() ? MobKind.ZOMBIE : MobKind.SKELETON;
        }
        double roll = worldRandom.nextDouble();
        if (roll < 0.34) {
            return MobKind.PIG;
        }
        if (roll < 0.67) {
            return MobKind.SHEEP;
        }
        return MobKind.COW;
    }

    private boolean blockIntersectsPlayerHitbox(int blockX, int blockY, int blockZ, PlayerState player) {
        if (player == null || player.spectatorMode) {
            return false;
        }
        double blockMinX = blockX;
        double blockMaxX = blockX + 1.0;
        double blockMinY = blockY;
        double blockMaxY = blockY + 1.0;
        double blockMinZ = blockZ;
        double blockMaxZ = blockZ + 1.0;

        double playerMinX = player.x - GameConfig.PLAYER_RADIUS;
        double playerMaxX = player.x + GameConfig.PLAYER_RADIUS;
        double playerMinY = player.y;
        double playerMaxY = player.y + player.height();
        double playerMinZ = player.z - GameConfig.PLAYER_RADIUS;
        double playerMaxZ = player.z + GameConfig.PLAYER_RADIUS;

        return playerMaxX > blockMinX && playerMinX < blockMaxX
            && playerMaxY > blockMinY && playerMinY < blockMaxY
            && playerMaxZ > blockMinZ && playerMinZ < blockMaxZ;
    }

    private void ensureColumnsAround(int centerChunkX, int centerChunkZ, int chunkRadius, boolean synchronousOnly) {
        int syncRadius = synchronousOnly ? chunkRadius : 1;
        int submittedAsync = 0;
        for (int radius = 0; radius <= chunkRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                submittedAsync = ensureColumnAtRadius(centerChunkX + dx, centerChunkZ - radius, radius, syncRadius, synchronousOnly, submittedAsync);
                if (radius != 0) {
                    submittedAsync = ensureColumnAtRadius(centerChunkX + dx, centerChunkZ + radius, radius, syncRadius, synchronousOnly, submittedAsync);
                }
            }
            for (int dz = -radius + 1; dz <= radius - 1; dz++) {
                submittedAsync = ensureColumnAtRadius(centerChunkX - radius, centerChunkZ + dz, radius, syncRadius, synchronousOnly, submittedAsync);
                if (radius != 0) {
                    submittedAsync = ensureColumnAtRadius(centerChunkX + radius, centerChunkZ + dz, radius, syncRadius, synchronousOnly, submittedAsync);
                }
            }
        }
    }

    private int ensureColumnAtRadius(int chunkX, int chunkZ, int radius, int syncRadius, boolean synchronousOnly, int submittedAsync) {
        if (radius <= syncRadius || synchronousOnly) {
            ensureColumnLoadedSync(chunkX, chunkZ);
        } else if (submittedAsync < MAX_ASYNC_COLUMN_SUBMISSIONS_PER_TICK) {
            if (submitColumnTask(chunkX, chunkZ)) {
                submittedAsync++;
            }
        }
        return submittedAsync;
    }

    private void unloadFarColumns(int playerChunkX, int playerChunkZ, int unloadRadius) {
        Iterator<Map.Entry<Long, ChunkColumn>> iterator = loadedColumns.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, ChunkColumn> entry = iterator.next();
            ChunkColumn column = entry.getValue();
            int horizontalDistance = Math.max(Math.abs(column.chunkX - playerChunkX), Math.abs(column.chunkZ - playerChunkZ));
            if (horizontalDistance <= unloadRadius) {
                continue;
            }
            saveColumnIfDirty(column);
            removeColumnDynamicBlocks(column);
            iterator.remove();
        }
    }

    private void drainGeneratedColumns() {
        for (Map.Entry<Long, Future<ChunkColumn>> entry : pendingColumns.entrySet()) {
            Future<ChunkColumn> future = entry.getValue();
            if (!future.isDone()) {
                continue;
            }
            try {
                ChunkColumn column = future.get();
                integrateColumn(column);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException exception) {
                if (GameConfig.ENABLE_DEBUG_LOGS) {
                    System.out.println("VoxelWorld: column task failed: " + exception.getMessage());
                }
            }
            pendingColumns.remove(entry.getKey(), future);
        }
    }

    private void ensureColumnLoadedSync(int chunkX, int chunkZ) {
        long key = columnKey(chunkX, chunkZ);
        ChunkColumn loaded = loadedColumns.get(key);
        if (loaded != null) {
            return;
        }

        Future<ChunkColumn> future = pendingColumns.get(key);
        if (future != null) {
            try {
                integrateColumn(future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException exception) {
                if (GameConfig.ENABLE_DEBUG_LOGS) {
                    System.out.println("VoxelWorld: failed to wait for chunk column: " + exception.getMessage());
                }
            }
            pendingColumns.remove(key, future);
            return;
        }

        integrateColumn(loadOrGenerateColumn(chunkX, chunkZ));
    }

    private ChunkColumn ensureColumnGeneratedSync(int chunkX, int chunkZ) {
        ensureColumnLoadedSync(chunkX, chunkZ);
        ChunkColumn column = loadedColumns.get(columnKey(chunkX, chunkZ));
        return column != null && column.generated ? column : null;
    }

    private boolean submitColumnTask(int chunkX, int chunkZ) {
        long key = columnKey(chunkX, chunkZ);
        if (loadedColumns.containsKey(key) || pendingColumns.containsKey(key)) {
            return false;
        }
        int priority = chunkPriority(chunkX, chunkZ);
        if (pendingColumns.size() >= MAX_PENDING_COLUMN_TASKS && priority > 3) {
            return false;
        }
        PrioritizedColumnTask task = new PrioritizedColumnTask(
            () -> loadOrGenerateColumn(chunkX, chunkZ),
            priority,
            nextColumnTaskSequence++
        );
        Future<ChunkColumn> previous = pendingColumns.putIfAbsent(key, task);
        if (previous != null) {
            return false;
        }
        generationExecutor.execute(task);
        return true;
    }

    private int chunkPriority(int chunkX, int chunkZ) {
        if (lastStreamingChunkX == Integer.MIN_VALUE || lastStreamingChunkZ == Integer.MIN_VALUE) {
            return 0;
        }
        int dx = Math.abs(chunkX - lastStreamingChunkX);
        int dz = Math.abs(chunkZ - lastStreamingChunkZ);
        return Math.max(dx, dz) * 256 + dx * dx + dz * dz;
    }

    private void cancelFarPendingColumns(int playerChunkX, int playerChunkZ, int keepRadius) {
        for (Map.Entry<Long, Future<ChunkColumn>> entry : pendingColumns.entrySet()) {
            int chunkX = unpackColumnX(entry.getKey());
            int chunkZ = unpackColumnZ(entry.getKey());
            int horizontalDistance = Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));
            if (horizontalDistance <= keepRadius) {
                continue;
            }
            Future<ChunkColumn> future = entry.getValue();
            if (pendingColumns.remove(entry.getKey(), future)) {
                future.cancel(true);
            }
        }
        generationExecutor.purge();
    }

    private void integrateColumn(ChunkColumn column) {
        if (column == null) {
            return;
        }
        column.generated = true;
        ChunkColumn previous = loadedColumns.putIfAbsent(columnKey(column.chunkX, column.chunkZ), column);
        if (previous == null) {
            registerColumnDynamicBlocks(column);
        }
    }

    private ChunkColumn loadOrGenerateColumn(int chunkX, int chunkZ) {
        if (regionStorage != null) {
            ChunkColumn loaded = regionStorage.loadColumn(chunkX, chunkZ);
            if (loaded != null) {
                return loaded;
            }
        }
        if (saveDirectory != null) {
            Path chunkPath = chunkFilePath(chunkX, chunkZ);
            if (Files.isRegularFile(chunkPath)) {
                ChunkColumn loaded = loadColumnFromDisk(chunkPath, chunkX, chunkZ);
                if (loaded != null) {
                    return loaded;
                }
            }
        }
        ChunkColumn generated = generateColumn(chunkX, chunkZ);
        return generated;
    }

    private ChunkColumn loadColumnFromDisk(Path chunkPath, int chunkX, int chunkZ) {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(chunkPath)))) {
            int version = input.readInt();
            if (version != SAVE_FORMAT_VERSION) {
                return null;
            }
            int storedChunkX = input.readInt();
            int storedChunkZ = input.readInt();
            if (storedChunkX != chunkX || storedChunkZ != chunkZ) {
                return null;
            }

            ChunkColumn column = new ChunkColumn(chunkX, chunkZ);
            int sectionMask = input.readInt();
            for (int chunkY = 0; chunkY < GameConfig.SECTION_COUNT; chunkY++) {
                if ((sectionMask & (1 << chunkY)) == 0) {
                    continue;
                }
                Chunk chunk = column.section(chunkY);
                int index = 0;
                while (index < Chunk.VOLUME) {
                    int runLength = input.readUnsignedShort();
                    byte block = input.readByte();
                    int distance = input.readByte();
                    for (int run = 0; run < runLength && index < Chunk.VOLUME; run++, index++) {
                        chunk.setSerializedCell(index, block, distance);
                    }
                }
            }
            recalculateColumnSurfaceHeights(column);
            column.status = ChunkGenerationStatus.FULL;
            column.dirty = false;
            return column;
        } catch (IOException exception) {
            if (GameConfig.ENABLE_DEBUG_LOGS) {
                System.out.println("VoxelWorld: failed to load chunk " + chunkPath + ": " + exception.getMessage());
            }
            return null;
        }
    }

    private ChunkColumn generateColumn(int chunkX, int chunkZ) {
        if (worldGenerator == null) {
            worldGenerator = new WorldGenerator(seed);
        }
        GeneratedChunkColumn generated = worldGenerator.generateChunk(chunkX, chunkZ);
        ChunkColumn column = new ChunkColumn(chunkX, chunkZ, false);

        Chunk[] generatedSections = generated.sections();
        for (int chunkY = 0; chunkY < GameConfig.SECTION_COUNT; chunkY++) {
            column.sections[chunkY] = generatedSections[chunkY];
        }
        column.status = generated.status();

        for (int localX = 0; localX < GameConfig.CHUNK_SIZE; localX++) {
            for (int localZ = 0; localZ < GameConfig.CHUNK_SIZE; localZ++) {
                column.setSurfaceHeightLocal(localX, localZ, generated.getSurfaceHeight(localX, localZ));
            }
        }
        column.dirty = false;
        column.naturalTerrain = true;
        return column;
    }

    private void recalculateColumnSurfaceHeights(ChunkColumn column) {
        int startX = column.chunkX * GameConfig.CHUNK_SIZE;
        int startZ = column.chunkZ * GameConfig.CHUNK_SIZE;
        for (int localX = 0; localX < GameConfig.CHUNK_SIZE; localX++) {
            for (int localZ = 0; localZ < GameConfig.CHUNK_SIZE; localZ++) {
                int worldX = startX + localX;
                int worldZ = startZ + localZ;
                column.setSurfaceHeightLocal(localX, localZ, findTopSolidBlock(worldX, worldZ));
            }
        }
    }

    private void registerColumnDynamicBlocks(ChunkColumn column) {
        int startX = column.chunkX * GameConfig.CHUNK_SIZE;
        int startZ = column.chunkZ * GameConfig.CHUNK_SIZE;
        for (int chunkY = 0; chunkY < GameConfig.SECTION_COUNT; chunkY++) {
            Chunk chunk = column.section(chunkY);
            int startY = GameConfig.sectionYForIndex(chunkY);
            for (int localY = 0; localY < GameConfig.CHUNK_SIZE; localY++) {
                for (int localZ = 0; localZ < GameConfig.CHUNK_SIZE; localZ++) {
                    for (int localX = 0; localX < GameConfig.CHUNK_SIZE; localX++) {
                        int worldX = startX + localX;
                        int worldY = startY + localY;
                        int worldZ = startZ + localZ;
                        if (column.naturalTerrain
                            && isFallingTerrainBlock(chunk.getBlockLocal(localX, localY, localZ))
                            && isStableGeneratedSand(worldX, worldY, worldZ)) {
                            continue;
                        }
                        updateActiveStateAt(worldX, worldY, worldZ);
                    }
                }
            }
        }
    }

    private void removeColumnDynamicBlocks(ChunkColumn column) {
        int startX = column.chunkX * GameConfig.CHUNK_SIZE;
        int startZ = column.chunkZ * GameConfig.CHUNK_SIZE;
        for (int chunkY = 0; chunkY < GameConfig.SECTION_COUNT; chunkY++) {
            int startY = GameConfig.sectionYForIndex(chunkY);
            for (int localY = 0; localY < GameConfig.CHUNK_SIZE; localY++) {
                for (int localZ = 0; localZ < GameConfig.CHUNK_SIZE; localZ++) {
                    for (int localX = 0; localX < GameConfig.CHUNK_SIZE; localX++) {
                        long blockKey = packBlock(startX + localX, startY + localY, startZ + localZ);
                        activeWaterCells.remove(blockKey);
                        activeLavaCells.remove(blockKey);
                        activeSandCells.remove(blockKey);
                        waterFlowCache.remove(blockKey);
                    }
                }
            }
        }
    }

    private void updateActiveStateAt(int x, int y, int z) {
        if (!isInside(x, y, z)) {
            return;
        }

        long blockKey = packBlock(x, y, z);
        byte block = getBlock(x, y, z);
        if (GameConfig.isWaterBlock(block) && isFluidActiveCandidate(x, y, z, GameConfig.WATER)) {
            activeWaterCells.add(blockKey);
        } else {
            activeWaterCells.remove(blockKey);
        }

        if (GameConfig.isLavaBlock(block) && isFluidActiveCandidate(x, y, z, GameConfig.LAVA)) {
            activeLavaCells.add(blockKey);
        } else {
            activeLavaCells.remove(blockKey);
        }

        if (isFallingTerrainBlock(block) && isSandActiveCandidate(x, y, z)) {
            activeSandCells.add(blockKey);
        } else {
            activeSandCells.remove(blockKey);
        }
    }

    private boolean isFallingTerrainBlock(byte block) {
        return Blocks.isGravityAffected(block);
    }

    private boolean isFluidActiveCandidate(int x, int y, int z, byte fluidItem) {
        byte block = getBlock(x, y, z);
        if (GameConfig.fluidItemForBlock(block) != fluidItem) {
            return false;
        }
        if (GameConfig.isFluidSourceBlock(block) && getFluidDistance(x, y, z) == GameConfig.NATURAL_FLUID_DISTANCE) {
            return y > GameConfig.WORLD_MIN_Y && canFluidOccupyBlock(getBlock(x, y - 1, z), fluidItem);
        }
        if (GameConfig.isFluidFlowingBlock(block)) {
            return true;
        }
        if (fluidItem == GameConfig.WATER && isStableNaturalWaterSource(x, y, z)) {
            return hasFluidExpansionOpportunity(x, y, z);
        }
        if (y > GameConfig.WORLD_MIN_Y && canFluidOccupyBlock(getBlock(x, y - 1, z), fluidItem)) {
            return true;
        }
        return canFluidOccupyBlock(getBlock(x + 1, y, z), fluidItem)
            || canFluidOccupyBlock(getBlock(x - 1, y, z), fluidItem)
            || canFluidOccupyBlock(getBlock(x, y, z + 1), fluidItem)
            || canFluidOccupyBlock(getBlock(x, y, z - 1), fluidItem)
            || (fluidItem == GameConfig.WATER && countCurrentAdjacentWaterSources(x, y, z) >= 1);
    }

    private boolean hasFluidExpansionOpportunity(int x, int y, int z) {
        return (y > GameConfig.WORLD_MIN_Y && isReplaceableForFluid(getBlock(x, y - 1, z)))
            || isReplaceableForFluid(getBlock(x + 1, y, z))
            || isReplaceableForFluid(getBlock(x - 1, y, z))
            || isReplaceableForFluid(getBlock(x, y, z + 1))
            || isReplaceableForFluid(getBlock(x, y, z - 1));
    }

    private boolean isStableNaturalWaterSource(int x, int y, int z) {
        if (getBlock(x, y, z) != GameConfig.WATER_SOURCE || y > GameConfig.SEA_LEVEL) {
            return false;
        }
        if (getFluidDistance(x, y, z) == GameConfig.NATURAL_FLUID_DISTANCE) {
            return true;
        }
        byte below = getBlock(x, y - 1, z);
        if (isReplaceableForFluid(below) || GameConfig.isLavaBlock(below)) {
            return false;
        }
        return GameConfig.isWaterBlock(below)
            || GameConfig.isWaterBlock(getBlock(x, y + 1, z))
            || countCurrentAdjacentWaterSources(x, y, z) >= 1;
    }

    private boolean isSandActiveCandidate(int x, int y, int z) {
        return y > GameConfig.WORLD_MIN_Y && isReplaceableForFallingBlock(getBlock(x, y - 1, z));
    }

    private boolean isStableGeneratedSand(int x, int y, int z) {
        return y <= GameConfig.SEA_LEVEL
            && (GameConfig.isWaterBlock(getBlock(x, y + 1, z))
                || GameConfig.isWaterBlock(getBlock(x, y + 2, z)));
    }

    private void refreshDynamicCellsAround(int x, int y, int z) {
        invalidateFluidFlowCacheAround(x, y, z);
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    updateActiveStateAt(x + offsetX, y + offsetY, z + offsetZ);
                }
            }
        }
    }

    private void setBlockState(int x, int y, int z, byte block, int fluidDistance) {
        ChunkColumn column = ensureLoadedColumnForBlock(x, z);
        if (column == null) {
            return;
        }
        Chunk chunk = column.section(GameConfig.sectionIndexForY(y));
        if (chunk == null) {
            return;
        }

        int localX = localBlockCoordinate(x);
        int localY = GameConfig.localYForWorldY(y);
        int localZ = localBlockCoordinate(z);
        byte previous = chunk.getBlockLocal(localX, localY, localZ);
        int previousDistance = chunk.getFluidDistanceLocal(localX, localY, localZ);
        if (previous == block && (!GameConfig.isLiquidBlock(block) || previousDistance == fluidDistance)) {
            return;
        }

        chunk.setBlockLocal(localX, localY, localZ, block);
        if (GameConfig.isLiquidBlock(block)) {
            chunk.setFluidDistanceLocal(localX, localY, localZ, Math.max(0, fluidDistance));
        }
        column.dirty = true;
        invalidateFluidFlowCacheAround(x, y, z);
    }

    void setBlockState(int x, int y, int z, BlockState state) {
        BlockState normalized = state == null ? Blocks.stateFromLegacyId(GameConfig.AIR) : state;
        int fluidDistance = normalized.type.isLiquid() ? 0 : -1;
        ChunkColumn column = ensureLoadedColumnForBlock(x, z);
        if (column == null) {
            return;
        }
        Chunk chunk = column.section(GameConfig.sectionIndexForY(y));
        if (chunk == null) {
            return;
        }
        int localX = localBlockCoordinate(x);
        int localY = GameConfig.localYForWorldY(y);
        int localZ = localBlockCoordinate(z);
        BlockState previous = chunk.getBlockStateLocal(localX, localY, localZ);
        if (previous.type.numericId == normalized.type.numericId && previous.data == normalized.data) {
            return;
        }
        chunk.setBlockStateLocal(localX, localY, localZ, normalized);
        if (normalized.type.isLiquid()) {
            chunk.setFluidDistanceLocal(localX, localY, localZ, Math.max(0, fluidDistance));
        }
        column.dirty = true;
        invalidateFluidFlowCacheAround(x, y, z);
    }

    private void invalidateFluidFlowCacheAround(int x, int y, int z) {
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetY = -2; offsetY <= 2; offsetY++) {
                for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                    waterFlowCache.remove(packBlock(x + offsetX, y + offsetY, z + offsetZ));
                }
            }
        }
    }

    private ChunkColumn ensureLoadedColumnForBlock(int x, int z) {
        int chunkX = worldToChunk(x);
        int chunkZ = worldToChunk(z);
        ensureColumnLoadedSync(chunkX, chunkZ);
        return loadedColumns.get(columnKey(chunkX, chunkZ));
    }

    private void updatePlantSupportAt(int x, int y, int z) {
        if (!isInside(x, y, z)) {
            return;
        }
        byte block = getBlock(x, y, z);
        if (!isPlantBlock(block) || canPlantStay(x, y, z)) {
            return;
        }
        setBlockState(x, y, z, GameConfig.AIR, -1);
        refreshDynamicCellsAround(x, y, z);
    }

    private void updateSnowSupportAt(int x, int y, int z) {
        if (!isInside(x, y, z) || getBlock(x, y, z) != GameConfig.SNOW_LAYER || canSnowStay(x, y, z)) {
            return;
        }
        setBlockState(x, y, z, GameConfig.AIR, -1);
        refreshDynamicCellsAround(x, y, z);
        markDirtyColumn(x, z);
    }

    private boolean canSnowStay(int x, int y, int z) {
        if (!isInside(x, y, z) || y <= GameConfig.WORLD_MIN_Y) {
            return false;
        }
        byte supportBlock = getBlock(x, y - 1, z);
        return supportBlock == GameConfig.GRASS
            || supportBlock == GameConfig.DIRT
            || supportBlock == GameConfig.COBBLESTONE
            || supportBlock == GameConfig.STONE
            || supportBlock == GameConfig.DEEPSLATE
            || supportBlock == GameConfig.SAND
            || supportBlock == GameConfig.GRAVEL;
    }

    private boolean canPlantStay(int x, int y, int z) {
        if (!isInside(x, y, z) || y <= GameConfig.WORLD_MIN_Y) {
            return false;
        }
        byte supportBlock = getBlock(x, y - 1, z);
        return Blocks.isSolid(supportBlock)
            && (supportBlock == GameConfig.GRASS
                || supportBlock == GameConfig.DIRT
                || supportBlock == GameConfig.SAND
                || supportBlock == GameConfig.FARMLAND
                || supportBlock == InventoryItems.OAK_PLANKS
                || supportBlock == GameConfig.COBBLESTONE
                || supportBlock == GameConfig.STONE
                || supportBlock == GameConfig.DEEPSLATE
                || supportBlock == GameConfig.GRAVEL);
    }

    private void refreshSurfaceHeight(int x, int z) {
        ChunkColumn column = loadedColumns.get(columnKey(worldToChunk(x), worldToChunk(z)));
        if (column == null) {
            return;
        }
        column.setSurfaceHeightLocal(localBlockCoordinate(x), localBlockCoordinate(z), findTopSolidBlock(x, z));
    }

    private int findTopSolidBlock(int x, int z) {
        for (int y = GameConfig.WORLD_MAX_Y; y >= GameConfig.WORLD_MIN_Y; y--) {
            if (blocksSky(getBlock(x, y, z))) {
                return y;
            }
        }
        return GameConfig.WORLD_MIN_Y;
    }

    private boolean blocksSky(byte block) {
        return Blocks.isSolid(block) && block != GameConfig.OAK_LEAVES && block != GameConfig.PINE_LEAVES;
    }

    private boolean isReplaceableForFluid(byte block) {
        return Blocks.isReplaceable(block);
    }

    private boolean isReplaceableForFallingBlock(byte block) {
        return Blocks.isReplaceable(block) || Blocks.isLiquid(block);
    }

    private byte getFluidReactionBlock(byte waterBlock, byte lavaBlock) {
        boolean waterSource = waterBlock == GameConfig.WATER_SOURCE;
        boolean lavaSource = lavaBlock == GameConfig.LAVA_SOURCE;
        if (waterSource && lavaSource) {
            return GameConfig.OBSIDIAN;
        }
        if (waterSource) {
            return GameConfig.COBBLESTONE;
        }
        if (lavaSource) {
            return GameConfig.STONE;
        }
        return GameConfig.COBBLESTONE;
    }

    private boolean isChunkWithinFluidSimulationDistance(int chunkX, int chunkZ, int playerChunkX, int playerChunkZ) {
        return Math.abs(chunkX - playerChunkX) <= GameConfig.FLUID_SIMULATION_CHUNK_DISTANCE
            && Math.abs(chunkZ - playerChunkZ) <= GameConfig.FLUID_SIMULATION_CHUNK_DISTANCE;
    }

    private int getStreamingRadius(PlayerState player) {
        int renderRadius = player != null && player.spectatorMode
            ? Math.max(renderDistanceChunks, GameConfig.SPECTATOR_CHUNK_RENDER_DISTANCE)
            : renderDistanceChunks;
        return Math.max(renderRadius + GameConfig.RENDER_CHUNK_UNLOAD_PADDING + 2, GameConfig.ACTIVE_SIMULATION_CHUNK_DISTANCE + 2);
    }

    private void markDirtyColumn(int x, int z) {
        long dirtyKey = (((long) x) << 32) ^ (((long) z) & 0xFFFFFFFFL);
        if (dirtyBlockColumns.add(dirtyKey)) {
            simulationDirtyColumns.add(x, z);
        }
    }

    private void updateDroppedItemPhysics(DroppedItem droppedItem, double deltaTime) {
        boolean inWater = intersectsFluid(
            droppedItem.x, droppedItem.y, droppedItem.z,
            droppedItem.radius(), droppedItem.height(), GameConfig.WATER
        );

        if (inWater) {
            sampleFluidFlow(
                droppedItem.x, droppedItem.y, droppedItem.z,
                droppedItem.radius(), droppedItem.height(), GameConfig.WATER, sampledFluidFlow
            );
            droppedItem.velocityX += sampledFluidFlow.x * GameConfig.WATER_ENTITY_FLOW_PUSH * deltaTime;
            droppedItem.velocityZ += sampledFluidFlow.z * GameConfig.WATER_ENTITY_FLOW_PUSH * deltaTime;
            droppedItem.verticalVelocity += sampledFluidFlow.y * GameConfig.WATER_VERTICAL_FLOW_PUSH * deltaTime;
            droppedItem.verticalVelocity -= GameConfig.DROPPED_ITEM_GRAVITY * 0.25 * deltaTime;
        } else {
            droppedItem.verticalVelocity -= GameConfig.DROPPED_ITEM_GRAVITY * deltaTime;
        }

        droppedItem.verticalVelocity = Math.max(-GameConfig.TERMINAL_VELOCITY * 0.5, droppedItem.verticalVelocity);
        moveDroppedItemHorizontal(droppedItem, droppedItem.velocityX * deltaTime, droppedItem.velocityZ * deltaTime);
        moveDroppedItemVertical(droppedItem, droppedItem.verticalVelocity * deltaTime);

        double drag = inWater ? GameConfig.DROPPED_ITEM_WATER_DRAG : GameConfig.DROPPED_ITEM_DRAG;
        droppedItem.velocityX *= drag;
        droppedItem.velocityZ *= drag;
        if (droppedItem.isGrounded) {
            droppedItem.velocityX *= 0.72;
            droppedItem.velocityZ *= 0.72;
        }
    }

    private void moveDroppedItemHorizontal(DroppedItem droppedItem, double moveX, double moveZ) {
        if (Math.abs(moveX) > 1.0e-5) {
            double nextX = droppedItem.x + moveX;
            if (!collides(nextX, droppedItem.y, droppedItem.z, droppedItem.radius(), droppedItem.height())) {
                droppedItem.x = nextX;
            } else {
                droppedItem.velocityX = 0.0;
            }
        }
        if (Math.abs(moveZ) > 1.0e-5) {
            double nextZ = droppedItem.z + moveZ;
            if (!collides(droppedItem.x, droppedItem.y, nextZ, droppedItem.radius(), droppedItem.height())) {
                droppedItem.z = nextZ;
            } else {
                droppedItem.velocityZ = 0.0;
            }
        }
    }

    private void moveDroppedItemVertical(DroppedItem droppedItem, double moveY) {
        if (Math.abs(moveY) < 1.0e-5) {
            return;
        }

        double nextY = droppedItem.y + moveY;
        if (!collides(droppedItem.x, nextY, droppedItem.z, droppedItem.radius(), droppedItem.height())) {
            droppedItem.y = nextY;
            droppedItem.isGrounded = false;
            return;
        }

        if (moveY < 0.0) {
            droppedItem.isGrounded = true;
            droppedItem.verticalVelocity = -droppedItem.verticalVelocity * GameConfig.DROPPED_ITEM_BOUNCE;
            if (Math.abs(droppedItem.verticalVelocity) < 0.18) {
                droppedItem.verticalVelocity = 0.0;
            }
        } else {
            droppedItem.verticalVelocity = 0.0;
        }
    }

    private void saveColumnIfDirty(ChunkColumn column) {
        if (column == null || !column.dirty) {
            return;
        }

        if (regionStorage != null) {
            try {
                regionStorage.saveColumn(column);
                column.dirty = false;
                return;
            } catch (IOException exception) {
                if (GameConfig.ENABLE_DEBUG_LOGS) {
                    System.out.println("VoxelWorld: failed to save region column " + column.chunkX + "," + column.chunkZ + ": " + exception.getMessage());
                }
            }
        }

        if (saveDirectory == null) {
            return;
        }

        try {
            Files.createDirectories(saveDirectory);
            Path chunkPath = chunkFilePath(column.chunkX, column.chunkZ);
            int sectionMask = 0;
            for (int chunkY = 0; chunkY < GameConfig.SECTION_COUNT; chunkY++) {
                Chunk chunk = column.section(chunkY);
                if (chunk != null && !chunk.isEmpty()) {
                    sectionMask |= 1 << chunkY;
                }
            }
            if (sectionMask == 0) {
                Files.deleteIfExists(chunkPath);
                column.dirty = false;
                return;
            }
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(chunkPath)))) {
                output.writeInt(SAVE_FORMAT_VERSION);
                output.writeInt(column.chunkX);
                output.writeInt(column.chunkZ);
                output.writeInt(sectionMask);
                for (int chunkY = 0; chunkY < GameConfig.SECTION_COUNT; chunkY++) {
                    Chunk chunk = column.section(chunkY);
                    if (chunk == null || chunk.isEmpty()) {
                        continue;
                    }
                    int index = 0;
                    while (index < Chunk.VOLUME) {
                        byte block = chunk.getBlockAtIndex(index);
                        int distance = chunk.getFluidDistanceAtIndex(index);
                        int runLength = 1;
                        while (index + runLength < Chunk.VOLUME && runLength < 0xFFFF
                            && chunk.getBlockAtIndex(index + runLength) == block
                            && chunk.getFluidDistanceAtIndex(index + runLength) == distance) {
                            runLength++;
                        }
                        output.writeShort(runLength);
                        output.writeByte(block);
                        output.writeByte(distance);
                        index += runLength;
                    }
                }
            }
            column.dirty = false;
        } catch (IOException exception) {
            if (GameConfig.ENABLE_DEBUG_LOGS) {
                System.out.println("VoxelWorld: failed to save column " + column.chunkX + "," + column.chunkZ + ": " + exception.getMessage());
            }
        }
    }

    private void flushLoadedColumns() {
        drainGeneratedColumns();
        for (ChunkColumn column : loadedColumns.values()) {
            saveColumnIfDirty(column);
        }
    }

    private Path chunkFilePath(int chunkX, int chunkZ) {
        return saveDirectory.resolve("c." + chunkX + "." + chunkZ + ".bin");
    }

    private Chunk getChunkForBlock(int x, int y, int z) {
        if (!isInside(x, y, z)) {
            return null;
        }
        ChunkColumn column = loadedColumns.get(columnKey(worldToChunk(x), worldToChunk(z)));
        if (column == null) {
            return null;
        }
        return column.section(GameConfig.sectionIndexForY(y));
    }

    private double biomeTemperature(int x, int z) {
        return octaveNoise((x - 380.0) * 0.0036, (z + 120.0) * 0.0036, 3, 0.55);
    }

    private double biomeHumidity(int x, int z) {
        return octaveNoise((x + 540.0) * 0.0042, (z - 260.0) * 0.0042, 3, 0.58);
    }

    private int calculateTerrainHeight(int x, int z) {
        double continent = octaveNoise(x * 0.0035, z * 0.0035, 4, 0.53);
        double mountainMask = Math.max(0.0, octaveNoise((x + 460.0) * 0.0056, (z - 700.0) * 0.0056, 3, 0.56));
        double broadHills = octaveNoise(x * 0.012, z * 0.012, 4, 0.52);
        double detail = octaveNoise((x + 200.0) * 0.042, (z - 300.0) * 0.042, 3, 0.48);
        double ridges = Math.abs(octaveNoise((x - 600.0) * 0.020, (z + 450.0) * 0.020, 3, 0.58));
        double canyonField = octaveNoise((x + 900.0) * 0.0072, (z - 410.0) * 0.0072, 2, 0.60);
        double temperature = biomeTemperature(x, z);
        double humidity = biomeHumidity(x, z);
        double mountainBoost = ridges * ridges * (8.0 + mountainMask * 22.0);
        double biomeLift = temperature < -0.10 ? 4.5 : (humidity > 0.40 ? -2.0 : 0.0);
        double baseHeight = GameConfig.SEA_LEVEL
            + continent * 18.0
            + broadHills * 10.0
            + detail * 4.0
            + mountainBoost
            + biomeLift;
        if (canyonField > 0.68) {
            baseHeight -= 12.0 + (canyonField - 0.68) * 34.0;
        }
        return clamp((int) Math.round(baseHeight), GameConfig.WORLD_MIN_Y + 8, GameConfig.WORLD_MAX_Y - 9);
    }

    private int worldToChunk(int coordinate) {
        return Math.floorDiv(coordinate, GameConfig.CHUNK_SIZE);
    }

    private int localBlockCoordinate(int coordinate) {
        return Math.floorMod(coordinate, GameConfig.CHUNK_SIZE);
    }

    private long columnKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (((long) chunkZ) & 0xFFFFFFFFL);
    }

    private int unpackColumnX(long columnKey) {
        return (int) (columnKey >> 32);
    }

    private int unpackColumnZ(long columnKey) {
        return (int) columnKey;
    }

    private long packBlock(int x, int y, int z) {
        long packedX = ((long) x) & 0x3FFFFFFL;
        long packedZ = ((long) z) & 0x3FFFFFFL;
        long packedY = (long) (y - GameConfig.WORLD_MIN_Y) & 0xFFFL;
        return (packedX << 38) | (packedZ << 12) | packedY;
    }

    private int unpackBlockX(long blockKey) {
        int value = (int) ((blockKey >>> 38) & 0x3FFFFFFL);
        return value >= 0x2000000 ? value - 0x4000000 : value;
    }

    private int unpackBlockY(long blockKey) {
        return (int) (blockKey & 0xFFFL) + GameConfig.WORLD_MIN_Y;
    }

    private int unpackBlockZ(long blockKey) {
        int value = (int) ((blockKey >>> 12) & 0x3FFFFFFL);
        return value >= 0x2000000 ? value - 0x4000000 : value;
    }

    private double distanceSquared(double x1, double z1, double x2, double z2) {
        double deltaX = x1 - x2;
        double deltaZ = z1 - z2;
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double normalizeWorldTime(double value) {
        double normalized = value % 1.0;
        return normalized < 0.0 ? normalized + 1.0 : normalized;
    }

    private double octaveNoise(double x, double z, int octaves, double persistence) {
        double total = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double amplitudeSum = 0.0;

        for (int octave = 0; octave < octaves; octave++) {
            total += perlin(x * frequency, z * frequency) * amplitude;
            amplitudeSum += amplitude;
            amplitude *= persistence;
            frequency *= 2.0;
        }

        return total / amplitudeSum;
    }

    private double perlin(double x, double y) {
        int baseX = (int) Math.floor(x) & 255;
        int baseY = (int) Math.floor(y) & 255;

        double localX = x - Math.floor(x);
        double localY = y - Math.floor(y);

        double fadeX = fade(localX);
        double fadeY = fade(localY);

        int aa = permutation[permutation[baseX] + baseY];
        int ab = permutation[permutation[baseX] + baseY + 1];
        int ba = permutation[permutation[baseX + 1] + baseY];
        int bb = permutation[permutation[baseX + 1] + baseY + 1];

        double blendTop = lerp(grad(aa, localX, localY), grad(ba, localX - 1.0, localY), fadeX);
        double blendBottom = lerp(grad(ab, localX, localY - 1.0), grad(bb, localX - 1.0, localY - 1.0), fadeX);
        return lerp(blendTop, blendBottom, fadeY);
    }

    private double fade(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private double lerp(double a, double b, double t) {
        return a + t * (b - a);
    }

    private double grad(int hash, double x, double y) {
        switch (hash & 7) {
            case 0:
                return x + y;
            case 1:
                return -x + y;
            case 2:
                return x - y;
            case 3:
                return -x - y;
            case 4:
                return x;
            case 5:
                return -x;
            case 6:
                return y;
            default:
                return -y;
        }
    }

}

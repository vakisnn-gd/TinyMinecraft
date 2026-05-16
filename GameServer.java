import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class GameServer implements MultiplayerManager.Listener {
    private static final double AUTOSAVE_SECONDS = 30.0;

    private final ServerProperties properties;
    private final PlayerState simulationPlayer = new PlayerState();

    private VoxelWorld world;
    private MultiplayerManager multiplayer;
    private Thread tickThread;
    private volatile boolean running;
    private volatile boolean stopped = true;
    private long startMillis;
    private double autosaveTimer;

    GameServer(ServerProperties properties) {
        this.properties = properties;
    }

    boolean start() {
        try {
            ResolvedWorld resolved = resolveWorld();
            world = new VoxelWorld(resolved.seed);
            world.setRenderDistanceChunks(properties.viewDistance);
            world.configureWorld(resolved.directory, resolved.seed, resolved.terrainPreset);
            world.initializeNoise();
            world.generateWorld();
            world.placePlayerAtSpawn(simulationPlayer);
            world.prepareForPlayer(simulationPlayer);
            world.primeStreamingAround(simulationPlayer);

            multiplayer = new MultiplayerManager(null, this);
            if (!multiplayer.startDedicatedHost(world, properties.port, properties.maxPlayers, properties.allowPvp)) {
                return false;
            }
            running = true;
            stopped = false;
            startMillis = System.currentTimeMillis();
            tickThread = new Thread(this::runTickLoop, "TinyCraft dedicated tick");
            tickThread.setDaemon(false);
            tickThread.start();
            System.out.println("TinyCraft Snapshot 8 dedicated server");
            System.out.println("World: " + resolved.directory);
            System.out.println("Seed: " + Long.toUnsignedString(resolved.seed, 16));
            System.out.println("MOTD: " + properties.motd);
            System.out.println("Type 'help' for commands.");
            return true;
        } catch (IOException exception) {
            System.out.println("Server start failed: " + exception.getMessage());
            return false;
        }
    }

    boolean isRunning() {
        return running;
    }

    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        running = false;
        if (tickThread != null && Thread.currentThread() != tickThread) {
            try {
                tickThread.join(2000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            tickThread = null;
        }
        boolean saved = false;
        if (multiplayer != null) {
            multiplayer.broadcastServerMessage("Server stopping.");
            save();
            saved = true;
            multiplayer.stop();
            multiplayer = null;
        }
        if (!saved) {
            save();
        }
        if (world != null) {
            world.cleanup();
            world = null;
        }
        System.out.println("Server stopped.");
    }

    void handleConsoleCommand(String raw) {
        String line = raw == null ? "" : raw.trim();
        if (line.isEmpty()) {
            return;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if ("help".equals(lower)) {
            System.out.println("Commands: help, status, list, say <message>, kick <player> [reason], save, stop");
        } else if ("status".equals(lower)) {
            long uptimeSeconds = Math.max(0L, (System.currentTimeMillis() - startMillis) / 1000L);
            System.out.println("Status: running, uptime=" + uptimeSeconds + "s, players="
                + multiplayer.connectedPlayerCount() + "/" + properties.maxPlayers);
        } else if ("list".equals(lower)) {
            System.out.println("Players: " + multiplayer.connectedPlayerNames());
        } else if (lower.startsWith("say ")) {
            String message = line.substring(4).trim();
            if (!message.isEmpty()) {
                multiplayer.broadcastServerMessage(message);
                System.out.println("[Server] " + message);
            }
        } else if (lower.startsWith("kick ")) {
            String[] parts = line.split("\\s+", 3);
            if (parts.length >= 2) {
                String reason = parts.length >= 3 ? parts[2] : "Kicked by server.";
                multiplayer.kickByName(parts[1], reason);
            } else {
                System.out.println("Usage: kick <player> [reason]");
            }
        } else if ("save".equals(lower)) {
            save();
        } else if ("stop".equals(lower)) {
            stop();
        } else {
            System.out.println("Unknown command. Type 'help'.");
        }
    }

    @Override
    public void onMultiplayerStatus(String status) {
        System.out.println(status);
    }

    @Override
    public void onMultiplayerChat(String message) {
        System.out.println(message);
    }

    @Override
    public void onClientWelcome(long seed, TerrainPreset terrainPreset, double x, double y, double z, double worldTime) {
    }

    @Override
    public void onClientChunkColumn(int chunkX, int chunkZ) {
    }

    @Override
    public void onClientBlockUpdate(int x, int y, int z) {
    }

    @Override
    public void onClientDisconnected(String reason) {
    }

    @Override
    public void onClientHealth(double health) {
    }

    @Override
    public void onClientInventoryAdd(byte itemId, int count, int durabilityDamage) {
    }

    private void runTickLoop() {
        long lastNs = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            double delta = Math.min(0.2, (now - lastNs) / 1_000_000_000.0);
            lastNs = now;
            tick(delta);
            long sleepMillis = Math.max(1L, (long) (GameConfig.GAME_TICK_SECONDS * 1000.0));
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void tick(double delta) {
        if (world == null || multiplayer == null) {
            return;
        }
        PlayerState activePlayer = multiplayer == null ? null : multiplayer.firstConnectedPlayer();
        PlayerState serverPlayer = activePlayer == null ? simulationPlayer : activePlayer;
        world.setRenderDistanceChunks(properties.viewDistance);
        world.advanceWorldTime(delta);
        world.prepareForPlayer(serverPlayer);
        world.updateDroppedItems(serverPlayer, null, delta);
        world.updateMobs(serverPlayer, delta);
        ColumnUpdateList dirtyFromTicks = world.updateWorldTicks(serverPlayer, delta);
        if (multiplayer != null) {
            multiplayer.drainEvents();
            multiplayer.tickDedicated(world, delta);
            multiplayer.broadcastBlockUpdates(world, dirtyFromTicks);
            multiplayer.broadcastBlockUpdates(world, world.drainNetworkDirtyBlocks());
        }
        autosaveTimer += delta;
        if (autosaveTimer >= AUTOSAVE_SECONDS) {
            autosaveTimer = 0.0;
            save();
        }
    }

    private void save() {
        if (world == null) {
            return;
        }
        if (multiplayer != null) {
            multiplayer.saveConnectedPlayers(world);
        }
        world.saveAllLoadedColumns();
        System.out.println("Saved world.");
    }

    private ResolvedWorld resolveWorld() throws IOException {
        Path directory = properties.worldDirectory();
        Files.createDirectories(directory);
        WorldMetadata metadata = readWorldMetadataIfPresent(directory);
        if (metadata != null) {
            return new ResolvedWorld(directory, metadata.seed, metadata.terrainPreset);
        }

        TerrainPreset terrainPreset = properties.terrainPreset();
        Long explicitSeed = properties.explicitSeed();
        long seed = explicitSeed == null ? properties.randomSeed() : explicitSeed.longValue();
        writeWorldMetadata(directory, seed, 0, 2, terrainPreset);
        return new ResolvedWorld(directory, seed, terrainPreset);
    }

    private WorldMetadata readWorldMetadataIfPresent(Path directory) throws IOException {
        Path levelPath = directory.resolve(GameConfig.SAVE_LEVEL_FILE);
        if (Files.isRegularFile(levelPath)) {
            String json = readUtf8(levelPath);
            long seed = parseStoredSeed(jsonValue(json, "seed"));
            TerrainPreset terrainPreset = TerrainPreset.fromMetadata(jsonValue(json, "worldType"));
            if (terrainPreset == TerrainPreset.LEGACY) {
                terrainPreset = properties.terrainPreset();
            }
            return new WorldMetadata(seed, terrainPreset);
        }

        Path metadataPath = directory.resolve(GameConfig.SAVE_METADATA_FILE);
        if (!Files.isRegularFile(metadataPath)) {
            return null;
        }
        String[] lines = readUtf8(metadataPath).split("\\R");
        long seed = lines.length == 0 ? 0L : parseStoredSeed(lines[0]);
        TerrainPreset terrainPreset = properties.terrainPreset();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("worldType=")) {
                terrainPreset = TerrainPreset.fromMetadata(line.substring(10));
            } else if (line.startsWith("terrainPreset=")) {
                terrainPreset = TerrainPreset.fromMetadata(line.substring(14));
            }
        }
        if (terrainPreset == TerrainPreset.LEGACY) {
            terrainPreset = properties.terrainPreset();
        }
        return new WorldMetadata(seed, terrainPreset);
    }

    private void writeWorldMetadata(Path directory, long seed, int gameMode, int difficulty, TerrainPreset terrainPreset) throws IOException {
        Files.createDirectories(directory);
        Files.createDirectories(directory.resolve(GameConfig.SAVE_REGION_DIRECTORY));
        TerrainPreset storedPreset = terrainPreset == null ? TerrainPreset.DEFAULT : terrainPreset;
        String seedHex = Long.toUnsignedString(seed, 16);
        String levelJson = "{"
            + System.lineSeparator() + "  \"version\": 1,"
            + System.lineSeparator() + "  \"seed\": \"" + jsonEscape(seedHex) + "\","
            + System.lineSeparator() + "  \"gameMode\": " + gameMode + ","
            + System.lineSeparator() + "  \"difficulty\": " + difficulty + ","
            + System.lineSeparator() + "  \"worldType\": \"" + jsonEscape(storedPreset.metadataId()) + "\","
            + System.lineSeparator() + "  \"minY\": " + GameConfig.WORLD_MIN_Y + ","
            + System.lineSeparator() + "  \"height\": " + GameConfig.WORLD_HEIGHT + ","
            + System.lineSeparator() + "  \"seaLevel\": " + GameConfig.SEA_LEVEL + ","
            + System.lineSeparator() + "  \"createdWith\": \"TinyCraft server mcrx-1\""
            + System.lineSeparator() + "}"
            + System.lineSeparator();
        Files.write(directory.resolve(GameConfig.SAVE_LEVEL_FILE), levelJson.getBytes(StandardCharsets.UTF_8));

        String metadata = seedHex
            + System.lineSeparator() + "mode=" + gameMode
            + System.lineSeparator() + "difficulty=" + difficulty
            + System.lineSeparator() + "worldType=" + storedPreset.metadataId()
            + System.lineSeparator();
        Files.write(directory.resolve(GameConfig.SAVE_METADATA_FILE), metadata.getBytes(StandardCharsets.UTF_8));
    }

    private String readUtf8(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private long parseStoredSeed(String seedText) {
        if (seedText == null || seedText.trim().isEmpty()) {
            return 0L;
        }
        String trimmed = seedText.trim();
        try {
            return Long.parseUnsignedLong(trimmed, 16);
        } catch (NumberFormatException ignored) {
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ignoredAgain) {
                return ServerProperties.parseSeed(trimmed);
            }
        }
    }

    private String jsonValue(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', keyIndex + marker.length());
        if (colonIndex < 0) {
            return null;
        }
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= json.length()) {
            return null;
        }
        if (json.charAt(valueStart) == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            return valueEnd < 0 ? null : json.substring(valueStart + 1, valueEnd);
        }
        int valueEnd = valueStart;
        while (valueEnd < json.length() && ",}\r\n".indexOf(json.charAt(valueEnd)) < 0) {
            valueEnd++;
        }
        return json.substring(valueStart, valueEnd).trim();
    }

    private String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class WorldMetadata {
        final long seed;
        final TerrainPreset terrainPreset;

        WorldMetadata(long seed, TerrainPreset terrainPreset) {
            this.seed = seed;
            this.terrainPreset = terrainPreset == null ? TerrainPreset.DEFAULT : terrainPreset;
        }
    }

    private static final class ResolvedWorld {
        final Path directory;
        final long seed;
        final TerrainPreset terrainPreset;

        ResolvedWorld(Path directory, long seed, TerrainPreset terrainPreset) {
            this.directory = directory;
            this.seed = seed;
            this.terrainPreset = terrainPreset;
        }
    }
}

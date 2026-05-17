import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

final class GameServer implements MultiplayerManager.Listener, MultiplayerManager.ServerCommandDelegate {
    private static final double AUTOSAVE_SECONDS = 30.0;

    private final ServerProperties properties;
    private final ServerAccessList accessList = new ServerAccessList();
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
            accessList.loadOrCreate();
            Files.createDirectories(RuntimePaths.resolve("backups"));
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
            multiplayer.setServerCommandDelegate(this);
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
        String result = executeServerCommand(null, "Console", raw, true);
        if (result == null) {
            System.out.println("Unknown command. Type 'help'.");
        } else if (!result.trim().isEmpty()) {
            System.out.println(result);
        }
    }

    @Override
    public String joinRejectionReason(UUID uuid, String name) {
        if (accessList.isBanned(uuid, name)) {
            return "You are banned from this server.";
        }
        if (properties.whitelist && !accessList.isWhitelisted(uuid, name) && !accessList.isOperator(uuid, name)) {
            return "You are not whitelisted on this server.";
        }
        return null;
    }

    @Override
    public String executeServerCommand(UUID senderUuid, String senderName, String commandLine) {
        return executeServerCommand(senderUuid, senderName, commandLine, false);
    }

    private String executeServerCommand(UUID senderUuid, String senderName, String commandLine, boolean console) {
        String line = commandLine == null ? "" : commandLine.trim();
        if (line.startsWith("/")) {
            line = line.substring(1).trim();
        }
        if (line.isEmpty()) {
            return "";
        }
        String[] parts = line.split("\\s+", 4);
        String command = parts[0].toLowerCase(Locale.ROOT);
        if (!isDedicatedServerCommand(command)) {
            return null;
        }
        if (senderUuid != null && !accessList.isOperator(senderUuid, senderName)) {
            return "You do not have permission to use /" + command + ".";
        }
        try {
            if ("help".equals(command)) {
                return "Commands: help, status, list, say <message>, kick <player> [reason], save, save-all, stop, op, deop, whitelist, ban, pardon, tp, gamemode";
            }
            if ("status".equals(command)) {
                long uptimeSeconds = Math.max(0L, (System.currentTimeMillis() - startMillis) / 1000L);
                return "Status: running, uptime=" + uptimeSeconds + "s, players="
                    + multiplayer.connectedPlayerCount() + "/" + properties.maxPlayers
                    + ", whitelist=" + properties.whitelist;
            }
            if ("list".equals(command)) {
                return "Players: " + multiplayer.connectedPlayerNames();
            }
            if ("say".equals(command)) {
                if (parts.length < 2 || line.length() <= 4) {
                    return "Usage: say <message>";
                }
                String message = line.substring(4).trim();
                multiplayer.broadcastServerMessage(message);
                System.out.println("[Server] " + message);
                return console ? "" : "[Server] " + message;
            }
            if ("kick".equals(command)) {
                if (parts.length < 2) {
                    return "Usage: kick <player> [reason]";
                }
                String[] kickParts = line.split("\\s+", 3);
                String reason = kickParts.length >= 3 ? kickParts[2] : "Kicked by server.";
                multiplayer.kickByName(kickParts[1], reason);
                return "Kicked " + kickParts[1] + ".";
            }
            if ("save".equals(command) || "save-all".equals(command)) {
                save();
                return console ? "" : "Saved world.";
            }
            if ("stop".equals(command)) {
                if (!console) {
                    multiplayer.broadcastServerMessage("Server stop requested by " + safeSenderName(senderName) + ".");
                }
                stop();
                return console ? "" : "Stopping server.";
            }
            if ("op".equals(command)) {
                if (parts.length < 2) {
                    return "Usage: op <player|uuid>";
                }
                ResolvedPlayer target = resolvePlayer(parts[1]);
                accessList.addOperator(parts[1], target.uuid, target.name);
                return "Opped " + target.display(parts[1]) + ".";
            }
            if ("deop".equals(command)) {
                if (parts.length < 2) {
                    return "Usage: deop <player|uuid>";
                }
                ResolvedPlayer target = resolvePlayer(parts[1]);
                accessList.removeOperator(parts[1], target.uuid, target.name);
                return "Deopped " + target.display(parts[1]) + ".";
            }
            if ("ban".equals(command)) {
                if (parts.length < 2) {
                    return "Usage: ban <player|uuid> [reason]";
                }
                ResolvedPlayer target = resolvePlayer(parts[1]);
                accessList.addBan(parts[1], target.uuid, target.name);
                String reason = line.split("\\s+", 3).length >= 3 ? line.split("\\s+", 3)[2] : "Banned by server.";
                multiplayer.kickByName(parts[1], reason);
                return "Banned " + target.display(parts[1]) + ".";
            }
            if ("pardon".equals(command)) {
                if (parts.length < 2) {
                    return "Usage: pardon <player|uuid>";
                }
                ResolvedPlayer target = resolvePlayer(parts[1]);
                accessList.removeBan(parts[1], target.uuid, target.name);
                return "Pardoned " + target.display(parts[1]) + ".";
            }
            if ("whitelist".equals(command)) {
                return handleWhitelistCommand(parts);
            }
            if ("tp".equals(command)) {
                if (parts.length < 4) {
                    return "Usage: tp <player> <x> <y> <z>";
                }
                double x = Double.parseDouble(parts[2]);
                String[] tail = parts[3].split("\\s+");
                if (tail.length < 2) {
                    return "Usage: tp <player> <x> <y> <z>";
                }
                double y = Double.parseDouble(tail[0]);
                double z = Double.parseDouble(tail[1]);
                return multiplayer.teleportPlayerByName(parts[1], x, y, z)
                    ? "Teleported " + parts[1] + "."
                    : "Player not found: " + parts[1];
            }
            if ("gamemode".equals(command)) {
                if (parts.length < 3) {
                    return "Usage: gamemode <survival|creative|spectator> <player>";
                }
                String mode = parts[1].toLowerCase(Locale.ROOT);
                if (!"survival".equals(mode) && !"creative".equals(mode) && !"spectator".equals(mode)
                    && !"0".equals(mode) && !"1".equals(mode) && !"3".equals(mode)) {
                    return "Unknown gamemode: " + parts[1];
                }
                return multiplayer.setPlayerGameModeByName(parts[2], mode)
                    ? "Set " + parts[2] + " to " + mode + "."
                    : "Player not found: " + parts[2];
            }
            return null;
        } catch (IOException exception) {
            return "Command failed: " + exception.getMessage();
        } catch (NumberFormatException exception) {
            return "Command failed: expected a number.";
        }
    }

    private String handleWhitelistCommand(String[] parts) throws IOException {
        if (parts.length < 2) {
            return "Whitelist is " + (properties.whitelist ? "on" : "off") + ". Entries: " + accessList.describeWhitelist();
        }
        String action = parts[1].toLowerCase(Locale.ROOT);
        if ("on".equals(action)) {
            properties.whitelist = true;
            properties.save();
            return "Whitelist enabled.";
        }
        if ("off".equals(action)) {
            properties.whitelist = false;
            properties.save();
            return "Whitelist disabled.";
        }
        if ("list".equals(action)) {
            return "Whitelist: " + accessList.describeWhitelist();
        }
        if (parts.length < 3) {
            return "Usage: whitelist <on|off|list|add|remove> [player|uuid]";
        }
        ResolvedPlayer target = resolvePlayer(parts[2]);
        if ("add".equals(action)) {
            accessList.addWhitelist(parts[2], target.uuid, target.name);
            return "Whitelisted " + target.display(parts[2]) + ".";
        }
        if ("remove".equals(action)) {
            accessList.removeWhitelist(parts[2], target.uuid, target.name);
            return "Removed " + target.display(parts[2]) + " from whitelist.";
        }
        return "Usage: whitelist <on|off|list|add|remove> [player|uuid]";
    }

    private boolean isDedicatedServerCommand(String command) {
        return "help".equals(command)
            || "status".equals(command)
            || "list".equals(command)
            || "say".equals(command)
            || "kick".equals(command)
            || "save".equals(command)
            || "save-all".equals(command)
            || "stop".equals(command)
            || "op".equals(command)
            || "deop".equals(command)
            || "whitelist".equals(command)
            || "ban".equals(command)
            || "pardon".equals(command)
            || "tp".equals(command)
            || "gamemode".equals(command);
    }

    private ResolvedPlayer resolvePlayer(String token) {
        UUID uuid = multiplayer == null ? null : multiplayer.connectedPlayerUuid(token);
        String name = multiplayer == null ? null : multiplayer.connectedPlayerDisplayName(token);
        if (uuid == null) {
            try {
                uuid = UUID.fromString(token);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (name == null && uuid == null) {
            name = token;
        }
        return new ResolvedPlayer(uuid, name);
    }

    private String safeSenderName(String senderName) {
        return senderName == null || senderName.trim().isEmpty() ? "Console" : senderName.trim();
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

    @Override
    public void onClientServerPlayerState(double x, double y, double z, double yaw, double pitch, boolean creativeMode, boolean spectatorMode, double health) {
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

    private static final class ResolvedPlayer {
        final UUID uuid;
        final String name;

        ResolvedPlayer(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        String display(String fallback) {
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
            if (uuid != null) {
                return uuid.toString();
            }
            return fallback;
        }
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

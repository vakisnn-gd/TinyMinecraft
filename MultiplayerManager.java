import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

final class MultiplayerManager {
    interface Listener {
        void onMultiplayerStatus(String status);
        void onMultiplayerChat(String message);
        void onClientWelcome(long seed, TerrainPreset terrainPreset, double x, double y, double z, double worldTime);
        void onClientChunkColumn(int chunkX, int chunkZ);
        void onClientBlockUpdate(int x, int y, int z);
        void onClientDisconnected(String reason);
        void onClientHealth(double health);
        void onClientInventoryAdd(byte itemId, int count, int durabilityDamage);
        void onClientServerPlayerState(double x, double y, double z, double yaw, double pitch, boolean creativeMode, boolean spectatorMode, double health);
    }

    interface ServerCommandDelegate {
        String joinRejectionReason(UUID uuid, String name);
        String executeServerCommand(UUID senderUuid, String senderName, String commandLine);
    }

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final double PLAYER_SEND_INTERVAL = 0.05;
    private static final double HOST_BROADCAST_INTERVAL = 0.08;
    private static final double ENTITY_SNAPSHOT_INTERVAL = 0.08;
    private static final double PLAYER_LIST_INTERVAL = 1.0;
    private static final double PING_INTERVAL = 1.0;
    private static final double PING_TIMEOUT_SECONDS = 5.0;
    private static final double CLIENT_CHUNK_REQUEST_INTERVAL = 0.15;
    private static final int CLIENT_CHUNK_REQUESTS_PER_TICK = 4;
    private static final int INITIAL_CHUNK_SYNC_RADIUS = 3;
    private static final int INITIAL_CHUNK_SEND_BUDGET = 49;

    private final LocalProfile profile;
    private final Listener listener;
    private final ConcurrentLinkedQueue<Runnable> mainThreadEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BlockAction> pendingBlockActions = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChunkRequest> pendingChunkRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<UUID, ServerClient> serverClients = new ConcurrentHashMap<>();
    private final HashSet<Long> requestedClientColumns = new HashSet<>();
    private final ArrayDeque<Long> clientColumnQueue = new ArrayDeque<>();
    private final GameClientSession clientSession = new GameClientSession();
    private final ArrayList<PlayerListEntry> playerListSnapshot = new ArrayList<>();

    private volatile boolean hostRunning;
    private volatile boolean clientRunning;
    private volatile VoxelWorld activeWorld;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Connection clientConnection;
    private PlayerState activeHostPlayer;
    private ServerCommandDelegate serverCommandDelegate;
    private int maxPlayers = 8;
    private boolean allowPvp = true;
    private int dedicatedChunkLogBudget = 64;
    private String status = "Offline";
    private double clientPlayerSendTimer;
    private double hostBroadcastTimer;
    private double entitySnapshotTimer;
    private double playerListTimer;
    private double pingTimer;
    private double clientChunkRequestTimer;
    private long pendingClientPingTime = -1L;
    private long lastClientPongMillis;
    private volatile boolean clientDisconnectEventQueued;

    MultiplayerManager(LocalProfile profile, Listener listener) {
        this.profile = profile;
        this.listener = listener;
    }

    void setServerCommandDelegate(ServerCommandDelegate serverCommandDelegate) {
        this.serverCommandDelegate = serverCommandDelegate;
    }

    boolean isHosting() {
        return hostRunning;
    }

    boolean isClient() {
        return clientRunning;
    }

    boolean isMultiplayerActive() {
        return hostRunning || clientRunning;
    }

    String status() {
        return status;
    }

    void drainEvents() {
        Runnable event;
        while ((event = mainThreadEvents.poll()) != null) {
            event.run();
        }
    }

    boolean startHost(final VoxelWorld world, final PlayerState hostPlayer, int port) {
        stop();
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress(port));
        } catch (IOException exception) {
            setStatus("Host failed: " + exception.getMessage());
            return false;
        }
        activeWorld = world;
        activeHostPlayer = hostPlayer;
        maxPlayers = 8;
        allowPvp = true;
        playerListSnapshot.clear();
        hostRunning = true;
        setStatus("Hosting on port " + port);
        acceptThread = new Thread(() -> acceptLoop(world, hostPlayer), "TinyCraft LAN host");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return true;
    }

    boolean startDedicatedHost(final VoxelWorld world, int port, int maxPlayers, boolean allowPvp) {
        stop();
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
        } catch (IOException exception) {
            setStatus("Dedicated host failed: " + exception.getMessage());
            return false;
        }
        activeWorld = world;
        activeHostPlayer = null;
        this.maxPlayers = Math.max(1, maxPlayers);
        this.allowPvp = allowPvp;
        dedicatedChunkLogBudget = 64;
        playerListSnapshot.clear();
        hostRunning = true;
        setStatus("Listening on 0.0.0.0:" + port);
        acceptThread = new Thread(() -> acceptLoop(world, null), "TinyCraft dedicated listener");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return true;
    }

    boolean connect(final String host, final int port) {
        stop();
        clientRunning = true;
        clientDisconnectEventQueued = false;
        requestedClientColumns.clear();
        clientColumnQueue.clear();
        playerListSnapshot.clear();
        clientSession.replacePlayerList(playerListSnapshot);
        pendingClientPingTime = -1L;
        lastClientPongMillis = 0L;
        setStatus("Connecting to " + host + ":" + port);
        Thread thread = new Thread(() -> clientConnectLoop(host, port), "TinyCraft LAN client");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    void stop() {
        boolean hadNetworkState = hostRunning
            || clientRunning
            || clientConnection != null
            || serverSocket != null
            || !serverClients.isEmpty();
        hostRunning = false;
        clientRunning = false;
        closeQuietly(clientConnection);
        clientConnection = null;
        for (ServerClient client : serverClients.values()) {
            closeQuietly(client.connection);
        }
        serverClients.clear();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        pendingBlockActions.clear();
        pendingChunkRequests.clear();
        requestedClientColumns.clear();
        clientColumnQueue.clear();
        playerListSnapshot.clear();
        clientSession.replacePlayerList(playerListSnapshot);
        if (hadNetworkState || !"Offline".equals(status)) {
            setStatus("Offline");
        }
    }

    void tickHost(VoxelWorld world, PlayerState hostPlayer, byte hostHeldItem, double deltaTime) {
        if (!hostRunning) {
            return;
        }
        activeWorld = world;
        activeHostPlayer = hostPlayer;
        processHostRequests(world);
        processRemoteClientItemPickups(world);
        hostBroadcastTimer += deltaTime;
        entitySnapshotTimer += deltaTime;
        playerListTimer += deltaTime;
        pingTimer += deltaTime;
        if (hostBroadcastTimer >= HOST_BROADCAST_INTERVAL) {
            hostBroadcastTimer = 0.0;
            broadcastPlayerState(profile.uuid, profile.name, hostPlayer, hostHeldItem);
            broadcastWorldTime(world.getWorldTime());
            for (ServerClient client : serverClients.values()) {
                broadcastPlayerState(client.uuid, client.name, client.player, client.heldItem);
            }
        }
        if (entitySnapshotTimer >= ENTITY_SNAPSHOT_INTERVAL) {
            entitySnapshotTimer = 0.0;
            broadcastMobSnapshot(world);
            broadcastDroppedItemSnapshot(world);
        }
        if (pingTimer >= PING_INTERVAL) {
            pingTimer = 0.0;
            pingServerClients();
        }
        if (playerListTimer >= PLAYER_LIST_INTERVAL) {
            playerListTimer = 0.0;
            broadcastPlayerList(hostPlayer, hostHeldItem);
        }
    }

    void tickDedicated(VoxelWorld world, double deltaTime) {
        if (!hostRunning) {
            return;
        }
        activeWorld = world;
        processHostRequests(world);
        processRemoteClientItemPickups(world);
        hostBroadcastTimer += deltaTime;
        entitySnapshotTimer += deltaTime;
        playerListTimer += deltaTime;
        pingTimer += deltaTime;
        if (hostBroadcastTimer >= HOST_BROADCAST_INTERVAL) {
            hostBroadcastTimer = 0.0;
            broadcastWorldTime(world.getWorldTime());
            for (ServerClient client : serverClients.values()) {
                broadcastPlayerState(client.uuid, client.name, client.player, client.heldItem);
            }
        }
        if (entitySnapshotTimer >= ENTITY_SNAPSHOT_INTERVAL) {
            entitySnapshotTimer = 0.0;
            broadcastMobSnapshot(world);
            broadcastDroppedItemSnapshot(world);
        }
        if (pingTimer >= PING_INTERVAL) {
            pingTimer = 0.0;
            pingServerClients();
        }
        if (playerListTimer >= PLAYER_LIST_INTERVAL) {
            playerListTimer = 0.0;
            broadcastPlayerList(null, GameConfig.AIR);
        }
    }

    void tickClient(VoxelWorld world, PlayerState player, byte heldItem, int renderDistanceChunks, double deltaTime) {
        if (!clientRunning || clientConnection == null) {
            return;
        }
        activeWorld = world;
        clientPlayerSendTimer += deltaTime;
        clientChunkRequestTimer += deltaTime;
        pingTimer += deltaTime;
        if (clientPlayerSendTimer >= PLAYER_SEND_INTERVAL) {
            clientPlayerSendTimer = 0.0;
            sendPlayerState(clientConnection, profile.uuid, profile.name, player, heldItem);
        }
        if (clientChunkRequestTimer >= CLIENT_CHUNK_REQUEST_INTERVAL) {
            clientChunkRequestTimer = 0.0;
            queueClientChunkRequests(player, renderDistanceChunks);
            for (int i = 0; i < CLIENT_CHUNK_REQUESTS_PER_TICK && !clientColumnQueue.isEmpty(); i++) {
                long key = clientColumnQueue.removeFirst();
                sendChunkRequest(clientConnection, unpackColumnX(key), unpackColumnZ(key));
            }
        }
        if (pingTimer >= PING_INTERVAL) {
            pingTimer = 0.0;
            sendClientPing();
        }
        if (lastClientPongMillis > 0L && System.currentTimeMillis() - lastClientPongMillis > (long) (PING_TIMEOUT_SECONDS * 1000.0)) {
            clientSession.setPingMs(-1);
        }
    }

    void requestInitialClientChunks(double x, double z) {
        if (!clientRunning || clientConnection == null) {
            return;
        }
        int chunkX = Math.floorDiv((int) Math.floor(x), GameConfig.CHUNK_SIZE);
        int chunkZ = Math.floorDiv((int) Math.floor(z), GameConfig.CHUNK_SIZE);
        queueClientChunkRequests(chunkX, chunkZ, INITIAL_CHUNK_SYNC_RADIUS);
        int sent = 0;
        while (sent < INITIAL_CHUNK_SEND_BUDGET && !clientColumnQueue.isEmpty()) {
            long key = clientColumnQueue.removeFirst();
            sendChunkRequest(clientConnection, unpackColumnX(key), unpackColumnZ(key));
            sent++;
        }
    }

    void sendChat(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        if (hostRunning) {
            String senderName = profile == null ? "Server" : profile.name;
            String formatted = "<" + senderName + "> " + message;
            emitChat(formatted);
            broadcastChat(formatted);
        } else if (clientRunning && clientConnection != null) {
            send(clientConnection, MultiplayerProtocol.CHAT, output -> output.writeUTF(message));
        }
    }

    void sendCommand(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return;
        }
        if (hostRunning) {
            handleServerCommand(profile == null ? null : profile.uuid, profile == null ? "Server" : profile.name, commandLine);
        } else if (clientRunning && clientConnection != null) {
            send(clientConnection, MultiplayerProtocol.COMMAND, output -> output.writeUTF(commandLine));
        }
    }

    List<PlayerListEntry> playerListSnapshot() {
        ArrayList<PlayerListEntry> copy = new ArrayList<>(playerListSnapshot);
        final UUID localUuid = profile == null ? null : profile.uuid;
        Collections.sort(copy, new Comparator<PlayerListEntry>() {
            @Override
            public int compare(PlayerListEntry a, PlayerListEntry b) {
                boolean aLocal = localUuid != null && localUuid.equals(a.uuid);
                boolean bLocal = localUuid != null && localUuid.equals(b.uuid);
                if (aLocal != bLocal) {
                    return aLocal ? -1 : 1;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return Collections.unmodifiableList(copy);
    }

    int pingMs() {
        return clientSession.pingMs();
    }

    String playerListText() {
        List<PlayerListEntry> entries = playerListSnapshot();
        if (entries.isEmpty()) {
            return "Players: (none)";
        }
        StringBuilder builder = new StringBuilder("Players: ");
        for (int i = 0; i < entries.size(); i++) {
            PlayerListEntry entry = entries.get(i);
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(entry.name).append(" (").append(formatPing(entry.pingMs)).append(")");
        }
        return builder.toString();
    }

    String pingText() {
        return "Ping: " + formatPing(clientSession.pingMs());
    }

    private String formatPing(int pingMs) {
        return pingMs < 0 ? "? ms" : pingMs + " ms";
    }

    private void handleServerCommand(UUID senderUuid, String senderName, String commandLine) {
        String raw = commandLine == null ? "" : commandLine.trim();
        if (raw.startsWith("/")) {
            raw = raw.substring(1).trim();
        }
        if (raw.isEmpty()) {
            return;
        }
        String[] parts = raw.split("\\s+", 3);
        String command = parts[0].toLowerCase(Locale.ROOT);
        if ("list".equals(command)) {
            sendCommandFeedback(senderUuid, playerListText());
        } else if ("ping".equals(command)) {
            sendCommandFeedback(senderUuid, "Ping: " + pingFor(senderUuid));
        } else if ("msg".equals(command)) {
            if (parts.length < 3) {
                sendCommandFeedback(senderUuid, "Usage: /msg <player> <message>");
            } else {
                sendPrivateMessage(senderUuid, senderName, parts[1], parts[2]);
            }
        } else if ("kick".equals(command)) {
            if (runDelegatedServerCommand(senderUuid, senderName, raw)) {
                return;
            }
            if (profile != null && (senderUuid == null || !profile.uuid.equals(senderUuid))) {
                sendCommandFeedback(senderUuid, "Only the host can use /kick.");
                return;
            }
            if (parts.length < 2) {
                sendCommandFeedback(senderUuid, "Usage: /kick <player> [reason]");
            } else {
                String[] kickParts = raw.split("\\s+", 3);
                kickPlayer(kickParts[1], kickParts.length >= 3 ? kickParts[2] : "Kicked by host.");
            }
        } else {
            if (!runDelegatedServerCommand(senderUuid, senderName, raw)) {
                sendCommandFeedback(senderUuid, "Unknown multiplayer command: /" + parts[0]);
            }
        }
    }

    private boolean runDelegatedServerCommand(UUID senderUuid, String senderName, String raw) {
        if (serverCommandDelegate == null) {
            return false;
        }
        String result = serverCommandDelegate.executeServerCommand(senderUuid, senderName, raw);
        if (result == null) {
            return false;
        }
        if (!result.trim().isEmpty()) {
            for (String line : result.split("\\R")) {
                sendCommandFeedback(senderUuid, line);
            }
        }
        return true;
    }

    private String pingFor(UUID uuid) {
        if (uuid != null && profile != null && profile.uuid.equals(uuid)) {
            return "0 ms";
        }
        ServerClient client = uuid == null ? null : serverClients.get(uuid);
        return client == null ? "? ms" : formatPing(client.pingMs);
    }

    private void sendPrivateMessage(UUID senderUuid, String senderName, String targetName, String message) {
        ServerClient target = findClientByName(targetName);
        if (target == null) {
            sendCommandFeedback(senderUuid, "Player not found: " + targetName);
            return;
        }
        String from = senderName == null || senderName.trim().isEmpty() ? "Server" : senderName;
        String formatted = "[PM] <" + from + "> " + message;
        send(target.connection, MultiplayerProtocol.CHAT, output -> output.writeUTF(formatted));
        sendCommandFeedback(senderUuid, "[PM to " + target.name + "] " + message);
    }

    private void kickPlayer(String targetName, String reason) {
        ServerClient target = findClientByName(targetName);
        if (target == null) {
            emitChat("Player not found: " + targetName);
            return;
        }
        sendDisconnect(target.connection, reason == null || reason.trim().isEmpty() ? "Kicked." : reason);
        closeQuietly(target.connection);
    }

    private ServerClient findClientByName(String name) {
        if (name == null) {
            return null;
        }
        for (ServerClient client : serverClients.values()) {
            if (client.name.equalsIgnoreCase(name)) {
                return client;
            }
        }
        return null;
    }

    private void sendCommandFeedback(UUID targetUuid, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        if (targetUuid == null || (profile != null && profile.uuid.equals(targetUuid))) {
            emitChat(message);
            return;
        }
        ServerClient client = serverClients.get(targetUuid);
        if (client != null) {
            send(client.connection, MultiplayerProtocol.CHAT, output -> output.writeUTF(message));
        }
    }

    void sendBlockBreak(int x, int y, int z, byte heldItem) {
        if (clientRunning && clientConnection != null) {
            send(clientConnection, MultiplayerProtocol.BLOCK_ACTION, output -> {
                output.writeByte(MultiplayerProtocol.BLOCK_BREAK);
                output.writeInt(x);
                output.writeInt(y);
                output.writeInt(z);
                output.writeInt(x);
                output.writeInt(y);
                output.writeInt(z);
                output.writeByte(heldItem);
            });
        }
    }

    void sendBlockPlace(RayHit hit, byte heldItem) {
        if (hit == null || !clientRunning || clientConnection == null) {
            return;
        }
        send(clientConnection, MultiplayerProtocol.BLOCK_ACTION, output -> {
            output.writeByte(MultiplayerProtocol.BLOCK_PLACE);
            output.writeInt(hit.x);
            output.writeInt(hit.y);
            output.writeInt(hit.z);
            output.writeInt(hit.previousX);
            output.writeInt(hit.previousY);
            output.writeInt(hit.previousZ);
            output.writeByte(heldItem);
        });
    }

    void sendPlayerAttack(UUID targetUuid, int damage) {
        if (targetUuid == null || damage <= 0) {
            return;
        }
        if (clientRunning && clientConnection != null) {
            send(clientConnection, MultiplayerProtocol.PLAYER_ATTACK, output -> {
                MultiplayerProtocol.writeUuid(output, targetUuid);
                output.writeInt(damage);
            });
        } else if (hostRunning) {
            if (profile != null) {
                applyPlayerAttack(profile.uuid, targetUuid, damage);
            }
        }
    }

    void sendMobAttack(int damage, double knockback) {
        if (damage <= 0) {
            return;
        }
        if (clientRunning && clientConnection != null) {
            send(clientConnection, MultiplayerProtocol.MOB_ATTACK, output -> {
                output.writeInt(damage);
                output.writeDouble(knockback);
            });
        }
    }

    void broadcastServerMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        String formatted = "[Server] " + message.trim();
        emitChat(formatted);
        broadcastChat(formatted);
    }

    int connectedPlayerCount() {
        return serverClients.size();
    }

    String connectedPlayerNames() {
        StringBuilder builder = new StringBuilder();
        for (ServerClient client : serverClients.values()) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(client.name);
        }
        return builder.length() == 0 ? "(none)" : builder.toString();
    }

    UUID connectedPlayerUuid(String targetName) {
        ServerClient client = findClientByName(targetName);
        return client == null ? null : client.uuid;
    }

    String connectedPlayerDisplayName(String targetName) {
        ServerClient client = findClientByName(targetName);
        return client == null ? null : client.name;
    }

    boolean teleportPlayerByName(String targetName, double x, double y, double z) {
        ServerClient client = findClientByName(targetName);
        if (client == null || !client.connection.open) {
            return false;
        }
        client.player.setPosition(x, y, z);
        client.player.capturePreviousPosition();
        sendServerPlayerState(client);
        broadcastPlayerState(client.uuid, client.name, client.player, client.heldItem);
        return true;
    }

    boolean setPlayerGameModeByName(String targetName, String mode) {
        ServerClient client = findClientByName(targetName);
        if (client == null || !client.connection.open) {
            return false;
        }
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        client.player.creativeMode = "creative".equals(normalized) || "1".equals(normalized);
        client.player.spectatorMode = "spectator".equals(normalized) || "3".equals(normalized);
        sendServerPlayerState(client);
        broadcastPlayerState(client.uuid, client.name, client.player, client.heldItem);
        return true;
    }

    private void sendServerPlayerState(ServerClient client) {
        if (client == null || !client.connection.open) {
            return;
        }
        send(client.connection, MultiplayerProtocol.SERVER_PLAYER_STATE, output -> {
            output.writeDouble(client.player.x);
            output.writeDouble(client.player.y);
            output.writeDouble(client.player.z);
            output.writeDouble(client.player.yaw);
            output.writeDouble(client.player.pitch);
            output.writeBoolean(client.player.creativeMode);
            output.writeBoolean(client.player.spectatorMode);
            output.writeDouble(client.player.health);
        });
    }

    void saveConnectedPlayers(VoxelWorld world) {
        if (world == null) {
            return;
        }
        for (ServerClient client : serverClients.values()) {
            world.saveNetworkPlayerState(client.uuid, client.player);
        }
    }

    void kickByName(String targetName, String reason) {
        kickPlayer(targetName, reason);
    }

    PlayerState firstConnectedPlayer() {
        for (ServerClient client : serverClients.values()) {
            return client.player;
        }
        return activeHostPlayer;
    }

    void broadcastBlockNeighborhood(VoxelWorld world, int x, int y, int z) {
        if (!hostRunning) {
            return;
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    broadcastBlockState(world, x + dx, y + dy, z + dz);
                }
            }
        }
    }

    void broadcastBlockUpdates(VoxelWorld world, ColumnUpdateList blocks) {
        if (!hostRunning || world == null || blocks == null) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            broadcastBlockState(world, blocks.xAt(i), blocks.yAt(i), blocks.zAt(i));
        }
    }

    private void acceptLoop(VoxelWorld world, PlayerState hostPlayer) {
        while (hostRunning && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket socket = serverSocket.accept();
                Connection connection = new Connection(socket);
                Thread thread = new Thread(() -> handleServerClient(connection, world, hostPlayer), "TinyCraft LAN peer");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException exception) {
                if (hostRunning) {
                    setStatus("Host accept failed: " + exception.getMessage());
                }
            }
        }
    }

    private void handleServerClient(Connection connection, VoxelWorld world, PlayerState hostPlayer) {
        UUID uuid = null;
        try {
            MultiplayerProtocol.Packet hello = MultiplayerProtocol.readPacket(connection.input);
            if (hello == null || hello.type != MultiplayerProtocol.HELLO) {
                throw new IOException("missing HELLO");
            }
            DataInputStream input = hello.input;
            int magic = input.readInt();
            int version = input.readInt();
            uuid = MultiplayerProtocol.readUuid(input);
            String name = LocalProfile.sanitizeName(input.readUTF(), "Player");
            if (magic != MultiplayerProtocol.MAGIC || version != MultiplayerProtocol.VERSION) {
                sendDisconnect(connection, "Incompatible multiplayer protocol.");
                return;
            }
            if (serverCommandDelegate != null) {
                String rejection = serverCommandDelegate.joinRejectionReason(uuid, name);
                if (rejection != null && !rejection.trim().isEmpty()) {
                    sendDisconnect(connection, rejection);
                    return;
                }
            }
            if ((profile != null && profile.uuid.equals(uuid)) || serverClients.containsKey(uuid)) {
                sendDisconnect(connection, "Duplicate player uuid.");
                return;
            }
            if (serverClients.size() >= maxPlayers) {
                sendDisconnect(connection, "Server is full.");
                return;
            }
            ServerClient client = new ServerClient(uuid, name, connection);
            if (world.loadNetworkPlayerState(uuid, client.player)) {
                client.player.capturePreviousPosition();
            } else if (hostPlayer != null) {
                client.player.setPosition(hostPlayer.x + 1.5, hostPlayer.y, hostPlayer.z + 1.5);
                client.player.y = world.safeStandingYAt(client.player.x, client.player.z);
            } else {
                world.placePlayerAtSpawn(client.player);
            }
            serverClients.put(uuid, client);
            if (profile == null) {
                System.out.println("Join: " + client.name + " " + client.uuid);
            }
            mainThreadEvents.add(() -> world.updateRemotePlayer(
                client.uuid,
                client.name,
                client.player.x,
                client.player.y,
                client.player.z,
                client.player.yaw,
                client.player.pitch,
                client.heldItem,
                client.player.sneaking,
                client.player.spectatorMode
            ));
            send(connection, MultiplayerProtocol.WELCOME, output -> {
                output.writeLong(world.getSeed());
                output.writeUTF(world.getTerrainPreset().metadataId());
                output.writeDouble(client.player.x);
                output.writeDouble(client.player.y);
                output.writeDouble(client.player.z);
                output.writeDouble(world.getWorldTime());
            });
            emitChat(name + " joined the world.");
            broadcastChat(name + " joined the world.");
            mainThreadEvents.add(() -> sendInitialChunkBurst(world, client));

            while (hostRunning && connection.open) {
                MultiplayerProtocol.Packet packet = MultiplayerProtocol.readPacket(connection.input);
                if (packet == null) {
                    break;
                }
                handleServerPacket(client, packet);
            }
        } catch (IOException exception) {
            if (hostRunning) {
                setStatus("Client disconnected: " + exception.getMessage());
                if (profile == null) {
                    System.out.println("Client error: " + exception.getMessage());
                }
            }
        } finally {
            closeQuietly(connection);
            if (uuid != null) {
                ServerClient removed = serverClients.remove(uuid);
                if (removed != null) {
                    world.saveNetworkPlayerState(removed.uuid, removed.player);
                    mainThreadEvents.add(() -> world.removeRemotePlayer(removed.uuid));
                    broadcastDespawn(uuid);
                    emitChat(removed.name + " left the world.");
                    if (profile == null) {
                        System.out.println("Leave: " + removed.name + " " + removed.uuid);
                    }
                }
            }
        }
    }

    private void handleServerPacket(ServerClient client, MultiplayerProtocol.Packet packet) throws IOException {
        DataInputStream input = packet.input;
        if (packet.type == MultiplayerProtocol.PLAYER_STATE) {
            readPlayerStateInto(client, input);
        } else if (packet.type == MultiplayerProtocol.CHAT) {
            String message = input.readUTF();
            String formatted = "<" + client.name + "> " + message;
            emitChat(formatted);
            broadcastChat(formatted);
        } else if (packet.type == MultiplayerProtocol.CHUNK_REQUEST) {
            pendingChunkRequests.add(new ChunkRequest(client, input.readInt(), input.readInt()));
        } else if (packet.type == MultiplayerProtocol.BLOCK_ACTION) {
            pendingBlockActions.add(BlockAction.read(client, input));
        } else if (packet.type == MultiplayerProtocol.PLAYER_ATTACK) {
            UUID targetUuid = MultiplayerProtocol.readUuid(input);
            int damage = input.readInt();
            applyPlayerAttack(client.uuid, targetUuid, damage);
        } else if (packet.type == MultiplayerProtocol.MOB_ATTACK) {
            int damage = input.readInt();
            double knockback = input.readDouble();
            applyMobAttack(client, damage, knockback);
        } else if (packet.type == MultiplayerProtocol.PING) {
            long timestamp = input.readLong();
            send(client.connection, MultiplayerProtocol.PONG, output -> output.writeLong(timestamp));
        } else if (packet.type == MultiplayerProtocol.PONG) {
            long timestamp = input.readLong();
            if (client.pendingPingTime == timestamp) {
                client.pingMs = (int) Math.max(0L, Math.min(9999L, System.currentTimeMillis() - timestamp));
                client.pendingPingTime = -1L;
            }
        } else if (packet.type == MultiplayerProtocol.COMMAND) {
            handleServerCommand(client.uuid, client.name, input.readUTF());
        } else if (packet.type == MultiplayerProtocol.DISCONNECT) {
            closeQuietly(client.connection);
        }
    }

    private void readPlayerStateInto(ServerClient client, DataInputStream input) throws IOException {
        UUID packetUuid = MultiplayerProtocol.readUuid(input);
        String packetName = LocalProfile.sanitizeName(input.readUTF(), client.name);
        if (!client.uuid.equals(packetUuid)) {
            throw new IOException("player uuid mismatch");
        }
        client.name = packetName;
        client.player.capturePreviousPosition();
        client.player.x = input.readDouble();
        client.player.y = input.readDouble();
        client.player.z = input.readDouble();
        client.player.yaw = input.readDouble();
        client.player.pitch = input.readDouble();
        client.heldItem = input.readByte();
        client.player.sneaking = input.readBoolean();
        client.player.spectatorMode = input.readBoolean();
        double packetHealth = input.readDouble();
        long now = System.currentTimeMillis();
        boolean allowHealthIncrease = client.player.health <= 0.0
            || now - client.lastServerDamageMillis > 1500L;
        if (packetHealth <= client.player.health || allowHealthIncrease) {
            client.player.health = packetHealth;
        }
        mainThreadEvents.add(() -> client.worldUpdate(packetName));
    }

    private void processHostRequests(VoxelWorld world) {
        ChunkRequest chunkRequest;
        int chunks = 0;
        while (chunks < 4 && (chunkRequest = pendingChunkRequests.poll()) != null) {
            final ChunkRequest request = chunkRequest;
            ServerClient client = request.client;
            if (client.connection.open) {
                if (profile == null && dedicatedChunkLogBudget > 0) {
                    dedicatedChunkLogBudget--;
                    System.out.println("Chunk request: " + client.name + " " + request.chunkX + "," + request.chunkZ);
                    if (dedicatedChunkLogBudget == 0) {
                        System.out.println("Chunk request logging muted after initial burst.");
                    }
                }
                send(client.connection, MultiplayerProtocol.CHUNK_DATA, output -> world.writeNetworkColumn(request.chunkX, request.chunkZ, output));
            }
            chunks++;
        }

        BlockAction action;
        while ((action = pendingBlockActions.poll()) != null) {
            if (!action.client.connection.open) {
                continue;
            }
            boolean changed = false;
            if (action.kind == MultiplayerProtocol.BLOCK_BREAK) {
                byte targetBlock = world.getBlock(action.x, action.y, action.z);
                changed = world.breakBlock(new RayHit(action.x, action.y, action.z, action.x, action.y, action.z));
                if (changed) {
                    byte droppedItem = droppedItemForBrokenBlock(targetBlock);
                    if (droppedItem != GameConfig.AIR && canHarvestBlock(targetBlock, action.heldItem)) {
                        world.spawnDroppedItem(droppedItem, 1, action.x + 0.5, action.y + 0.2, action.z + 0.5);
                    }
                }
            } else if (action.kind == MultiplayerProtocol.BLOCK_PLACE) {
                PlayerState actor = action.client.player;
                changed = world.placeBlock(new RayHit(action.x, action.y, action.z, action.previousX, action.previousY, action.previousZ), action.heldItem, actor);
            }
            if (changed) {
                if (profile == null) {
                    System.out.println("Block action: " + action.client.name + " kind=" + action.kind
                        + " at " + action.x + "," + action.y + "," + action.z);
                }
                broadcastBlockNeighborhood(world, action.x, action.y, action.z);
                broadcastBlockNeighborhood(world, action.previousX, action.previousY, action.previousZ);
            }
        }
    }

    private void clientConnectLoop(String host, int port) {
        String disconnectReason = "Connection Lost";
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            Connection connection = new Connection(socket);
            clientConnection = connection;
            send(connection, MultiplayerProtocol.HELLO, output -> {
                output.writeInt(MultiplayerProtocol.MAGIC);
                output.writeInt(MultiplayerProtocol.VERSION);
                MultiplayerProtocol.writeUuid(output, profile.uuid);
                output.writeUTF(profile.name);
            });
            while (clientRunning && connection.open) {
                MultiplayerProtocol.Packet packet = MultiplayerProtocol.readPacket(connection.input);
                if (packet == null) {
                    disconnectReason = "Connection Lost";
                    break;
                }
                handleClientPacket(packet);
            }
        } catch (IOException exception) {
            if (clientRunning) {
                disconnectReason = exception.getMessage() == null ? "Connection Lost" : "Connection Lost: " + exception.getMessage();
                setStatus(disconnectReason);
                queueClientDisconnected(disconnectReason);
            }
        } finally {
            if (clientRunning) {
                queueClientDisconnected(disconnectReason);
            }
            clientRunning = false;
            closeQuietly(clientConnection);
            clientConnection = null;
            setStatus("Offline");
        }
    }

    private void handleClientPacket(MultiplayerProtocol.Packet packet) throws IOException {
        DataInputStream input = packet.input;
        if (packet.type == MultiplayerProtocol.WELCOME) {
            long seed = input.readLong();
            TerrainPreset preset = TerrainPreset.fromMetadata(input.readUTF());
            double x = input.readDouble();
            double y = input.readDouble();
            double z = input.readDouble();
            double worldTime = input.readDouble();
            mainThreadEvents.add(() -> listener.onClientWelcome(seed, preset, x, y, z, worldTime));
            setStatus("Connected");
        } else if (packet.type == MultiplayerProtocol.PLAYER_STATE) {
            UUID uuid = MultiplayerProtocol.readUuid(input);
            String name = input.readUTF();
            double x = input.readDouble();
            double y = input.readDouble();
            double z = input.readDouble();
            double yaw = input.readDouble();
            double pitch = input.readDouble();
            byte heldItem = input.readByte();
            boolean sneaking = input.readBoolean();
            boolean spectator = input.readBoolean();
            double health = input.readDouble();
            if (!profile.uuid.equals(uuid)) {
                mainThreadEvents.add(() -> {
                    VoxelWorld world = activeWorld;
                    if (world != null) {
                        world.updateRemotePlayer(uuid, name, x, y, z, yaw, pitch, heldItem, sneaking, spectator);
                        RemotePlayerState remote = null;
                        for (RemotePlayerState candidate : world.getRemotePlayers()) {
                            if (candidate.uuid.equals(uuid)) {
                                remote = candidate;
                                break;
                            }
                        }
                        if (remote != null) {
                            remote.health = health;
                        }
                    }
                });
            }
        } else if (packet.type == MultiplayerProtocol.PLAYER_DESPAWN) {
            UUID uuid = MultiplayerProtocol.readUuid(input);
            mainThreadEvents.add(() -> {
                VoxelWorld world = activeWorld;
                if (world != null) {
                    world.removeRemotePlayer(uuid);
                }
            });
        } else if (packet.type == MultiplayerProtocol.CHAT) {
            String message = input.readUTF();
            emitChat(message);
        } else if (packet.type == MultiplayerProtocol.CHUNK_DATA) {
            byte[] remaining = readRemaining(input);
            mainThreadEvents.add(() -> {
                try {
                    VoxelWorld world = activeWorld;
                    if (world != null) {
                        long key = world.readNetworkColumn(new DataInputStream(new ByteArrayInputStream(remaining)));
                        listener.onClientChunkColumn(unpackColumnX(key), unpackColumnZ(key));
                    }
                } catch (IOException exception) {
                    setStatus("Chunk sync failed: " + exception.getMessage());
                }
            });
        } else if (packet.type == MultiplayerProtocol.BLOCK_UPDATE) {
            int x = input.readInt();
            int y = input.readInt();
            int z = input.readInt();
            String id = input.readUTF();
            int distance = input.readByte();
            mainThreadEvents.add(() -> {
                VoxelWorld world = activeWorld;
                if (world != null) {
                    world.applyNetworkBlockState(x, y, z, Blocks.stateFromNamespacedId(id), distance);
                    listener.onClientBlockUpdate(x, y, z);
                }
            });
        } else if (packet.type == MultiplayerProtocol.WORLD_TIME) {
            double worldTime = input.readDouble();
            mainThreadEvents.add(() -> {
                VoxelWorld world = activeWorld;
                if (world != null) {
                    world.setWorldTime(worldTime);
                }
            });
        } else if (packet.type == MultiplayerProtocol.MOB_SNAPSHOT) {
            byte[] remaining = readRemaining(input);
            mainThreadEvents.add(() -> applyMobSnapshot(remaining));
        } else if (packet.type == MultiplayerProtocol.DROPPED_ITEM_SNAPSHOT) {
            byte[] remaining = readRemaining(input);
            mainThreadEvents.add(() -> applyDroppedItemSnapshot(remaining));
        } else if (packet.type == MultiplayerProtocol.DISCONNECT) {
            String reason = input.readUTF();
            setStatus("Disconnected: " + reason);
            queueClientDisconnected(reason == null || reason.trim().isEmpty() ? "Connection Lost" : reason);
            clientRunning = false;
        } else if (packet.type == MultiplayerProtocol.PLAYER_HEALTH) {
            double health = input.readDouble();
            mainThreadEvents.add(() -> listener.onClientHealth(health));
        } else if (packet.type == MultiplayerProtocol.INVENTORY_ADD) {
            byte itemId = input.readByte();
            int count = input.readInt();
            int durabilityDamage = input.readInt();
            mainThreadEvents.add(() -> listener.onClientInventoryAdd(itemId, count, durabilityDamage));
        } else if (packet.type == MultiplayerProtocol.SERVER_PLAYER_STATE) {
            double x = input.readDouble();
            double y = input.readDouble();
            double z = input.readDouble();
            double yaw = input.readDouble();
            double pitch = input.readDouble();
            boolean creativeMode = input.readBoolean();
            boolean spectatorMode = input.readBoolean();
            double health = input.readDouble();
            mainThreadEvents.add(() -> listener.onClientServerPlayerState(x, y, z, yaw, pitch, creativeMode, spectatorMode, health));
        } else if (packet.type == MultiplayerProtocol.PING) {
            long timestamp = input.readLong();
            send(clientConnection, MultiplayerProtocol.PONG, output -> output.writeLong(timestamp));
        } else if (packet.type == MultiplayerProtocol.PONG) {
            long timestamp = input.readLong();
            if (pendingClientPingTime == timestamp) {
                int ping = (int) Math.max(0L, Math.min(9999L, System.currentTimeMillis() - timestamp));
                pendingClientPingTime = -1L;
                lastClientPongMillis = System.currentTimeMillis();
                clientSession.setPingMs(ping);
            }
        } else if (packet.type == MultiplayerProtocol.PLAYER_LIST) {
            int count = input.readInt();
            ArrayList<PlayerListEntry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                UUID uuid = MultiplayerProtocol.readUuid(input);
                String name = input.readUTF();
                int pingMs = input.readInt();
                double health = input.readDouble();
                String gameMode = input.readUTF();
                boolean connected = input.readBoolean();
                if (profile != null && profile.uuid.equals(uuid) && clientSession.pingMs() >= 0) {
                    pingMs = clientSession.pingMs();
                }
                entries.add(new PlayerListEntry(uuid, name, pingMs, health, gameMode, connected));
            }
            mainThreadEvents.add(() -> {
                playerListSnapshot.clear();
                playerListSnapshot.addAll(entries);
                clientSession.replacePlayerList(entries);
            });
        }
    }

    private void queueClientDisconnected(String reason) {
        if (clientDisconnectEventQueued) {
            return;
        }
        clientDisconnectEventQueued = true;
        String message = reason == null || reason.trim().isEmpty() ? "Connection Lost" : reason;
        mainThreadEvents.add(() -> listener.onClientDisconnected(message));
    }

    private void queueClientChunkRequests(PlayerState player, int radius) {
        int playerChunkX = Math.floorDiv((int) Math.floor(player.x), GameConfig.CHUNK_SIZE);
        int playerChunkZ = Math.floorDiv((int) Math.floor(player.z), GameConfig.CHUNK_SIZE);
        queueClientChunkRequests(playerChunkX, playerChunkZ, radius);
    }

    private void queueClientChunkRequests(int playerChunkX, int playerChunkZ, int radius) {
        int requestRadius = Math.max(2, Math.min(radius, 8));
        for (int dz = -requestRadius; dz <= requestRadius; dz++) {
            for (int dx = -requestRadius; dx <= requestRadius; dx++) {
                long key = columnKey(playerChunkX + dx, playerChunkZ + dz);
                if (requestedClientColumns.add(key)) {
                    clientColumnQueue.addLast(key);
                }
            }
        }
    }

    private void sendInitialChunkBurst(VoxelWorld world, ServerClient client) {
        if (world == null || client == null || !client.connection.open) {
            return;
        }
        int centerX = Math.floorDiv((int) Math.floor(client.player.x), GameConfig.CHUNK_SIZE);
        int centerZ = Math.floorDiv((int) Math.floor(client.player.z), GameConfig.CHUNK_SIZE);
        int sent = 0;
        for (int dz = -INITIAL_CHUNK_SYNC_RADIUS; dz <= INITIAL_CHUNK_SYNC_RADIUS && sent < INITIAL_CHUNK_SEND_BUDGET; dz++) {
            for (int dx = -INITIAL_CHUNK_SYNC_RADIUS; dx <= INITIAL_CHUNK_SYNC_RADIUS && sent < INITIAL_CHUNK_SEND_BUDGET; dx++) {
                int chunkX = centerX + dx;
                int chunkZ = centerZ + dz;
                send(client.connection, MultiplayerProtocol.CHUNK_DATA, output -> world.writeNetworkColumn(chunkX, chunkZ, output));
                sent++;
            }
        }
    }

    private void sendPlayerState(Connection connection, UUID uuid, String name, PlayerState player, byte heldItem) {
        send(connection, MultiplayerProtocol.PLAYER_STATE, output -> {
            MultiplayerProtocol.writeUuid(output, uuid);
            output.writeUTF(name);
            writePlayerFields(output, player, heldItem);
        });
    }

    private void sendClientPing() {
        if (clientConnection == null || !clientConnection.open || pendingClientPingTime >= 0L) {
            return;
        }
        pendingClientPingTime = System.currentTimeMillis();
        send(clientConnection, MultiplayerProtocol.PING, output -> output.writeLong(pendingClientPingTime));
    }

    private void pingServerClients() {
        long now = System.currentTimeMillis();
        for (ServerClient client : serverClients.values()) {
            if (client.pendingPingTime >= 0L && now - client.pendingPingTime > (long) (PING_TIMEOUT_SECONDS * 1000.0)) {
                client.pingMs = -1;
                client.pendingPingTime = -1L;
            }
            if (client.pendingPingTime < 0L && client.connection.open) {
                client.pendingPingTime = now;
                send(client.connection, MultiplayerProtocol.PING, output -> output.writeLong(now));
            }
        }
    }

    private void broadcastPlayerList(PlayerState hostPlayer, byte hostHeldItem) {
        ArrayList<PlayerListEntry> entries = buildPlayerList(hostPlayer, hostHeldItem);
        playerListSnapshot.clear();
        playerListSnapshot.addAll(entries);
        for (ServerClient client : serverClients.values()) {
            send(client.connection, MultiplayerProtocol.PLAYER_LIST, output -> writePlayerList(output, entries));
        }
    }

    private ArrayList<PlayerListEntry> buildPlayerList(PlayerState hostPlayer, byte hostHeldItem) {
        ArrayList<PlayerListEntry> entries = new ArrayList<>();
        if (profile != null && hostPlayer != null) {
            entries.add(new PlayerListEntry(profile.uuid, profile.name, 0, hostPlayer.health, gameModeName(hostPlayer), true));
        }
        for (ServerClient client : serverClients.values()) {
            entries.add(new PlayerListEntry(client.uuid, client.name, client.pingMs, client.player.health, gameModeName(client.player), client.connection.open));
        }
        Collections.sort(entries, new Comparator<PlayerListEntry>() {
            @Override
            public int compare(PlayerListEntry a, PlayerListEntry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return entries;
    }

    private void writePlayerList(DataOutputStream output, List<PlayerListEntry> entries) throws IOException {
        output.writeInt(entries.size());
        for (PlayerListEntry entry : entries) {
            MultiplayerProtocol.writeUuid(output, entry.uuid);
            output.writeUTF(entry.name);
            output.writeInt(entry.pingMs);
            output.writeDouble(entry.health);
            output.writeUTF(entry.gameMode);
            output.writeBoolean(entry.connected);
        }
    }

    private String gameModeName(PlayerState player) {
        if (player == null) {
            return "survival";
        }
        if (player.spectatorMode) {
            return "spectator";
        }
        if (player.creativeMode) {
            return "creative";
        }
        return "survival";
    }

    private void broadcastPlayerState(UUID uuid, String name, PlayerState player, byte heldItem) {
        for (ServerClient client : serverClients.values()) {
            send(client.connection, MultiplayerProtocol.PLAYER_STATE, output -> {
                MultiplayerProtocol.writeUuid(output, uuid);
                output.writeUTF(name);
                writePlayerFields(output, player, heldItem);
            });
        }
    }

    private void writePlayerFields(DataOutputStream output, PlayerState player, byte heldItem) throws IOException {
        output.writeDouble(player.x);
        output.writeDouble(player.y);
        output.writeDouble(player.z);
        output.writeDouble(player.yaw);
        output.writeDouble(player.pitch);
        output.writeByte(heldItem);
        output.writeBoolean(player.sneaking);
        output.writeBoolean(player.spectatorMode);
        output.writeDouble(player.health);
    }

    private void applyPlayerAttack(UUID attackerUuid, UUID targetUuid, int damage) {
        if (!hostRunning || attackerUuid == null || targetUuid == null || damage <= 0) {
            return;
        }
        PlayerState attacker = profile != null && profile.uuid.equals(attackerUuid)
            ? activeHostPlayer
            : serverClients.containsKey(attackerUuid) ? serverClients.get(attackerUuid).player : null;
        PlayerState target = profile != null && profile.uuid.equals(targetUuid)
            ? activeHostPlayer
            : serverClients.containsKey(targetUuid) ? serverClients.get(targetUuid).player : null;
        if (!allowPvp && serverClients.containsKey(targetUuid)) {
            return;
        }
        if (attacker == null || target == null || target.spectatorMode || target.health <= 0.0) {
            return;
        }
        double dx = target.x - attacker.x;
        double dy = (target.y + target.height() * 0.5) - (attacker.y + attacker.eyeHeight());
        double dz = target.z - attacker.z;
        if (dx * dx + dy * dy + dz * dz > 3.8 * 3.8) {
            return;
        }
        double protectedDamage = Math.max(0.5, damage);
        target.health = Math.max(0.0, target.health - protectedDamage);
        if (profile == null || !profile.uuid.equals(targetUuid)) {
            ServerClient targetClient = serverClients.get(targetUuid);
            if (targetClient != null) {
                targetClient.lastServerDamageMillis = System.currentTimeMillis();
                send(targetClient.connection, MultiplayerProtocol.PLAYER_HEALTH, output -> output.writeDouble(target.health));
            }
        }
        if (profile != null && profile.uuid.equals(targetUuid)) {
            broadcastPlayerState(targetUuid, profile.name, target, GameConfig.AIR);
        } else {
            ServerClient targetClient = serverClients.get(targetUuid);
            if (targetClient != null) {
                broadcastPlayerState(targetUuid, targetClient.name, target, targetClient.heldItem);
            }
        }
    }

    private void applyMobAttack(ServerClient client, int damage, double knockback) {
        VoxelWorld world = activeWorld;
        if (!hostRunning || world == null || client == null || !client.connection.open || damage <= 0) {
            return;
        }
        if (world.attackMobInReach(client.player, damage, knockback)) {
            broadcastMobSnapshot(world);
        }
    }

    private void broadcastChat(String message) {
        for (ServerClient client : serverClients.values()) {
            send(client.connection, MultiplayerProtocol.CHAT, output -> output.writeUTF(message));
        }
    }

    private void broadcastDespawn(UUID uuid) {
        for (ServerClient client : serverClients.values()) {
            send(client.connection, MultiplayerProtocol.PLAYER_DESPAWN, output -> MultiplayerProtocol.writeUuid(output, uuid));
        }
    }

    private void broadcastWorldTime(double worldTime) {
        for (ServerClient client : serverClients.values()) {
            send(client.connection, MultiplayerProtocol.WORLD_TIME, output -> output.writeDouble(worldTime));
        }
    }

    private void broadcastBlockState(VoxelWorld world, int x, int y, int z) {
        if (!world.isInside(x, y, z)) {
            return;
        }
        BlockState state = world.getBlockState(x, y, z);
        int distance = world.getFluidDistance(x, y, z);
        for (ServerClient client : serverClients.values()) {
            send(client.connection, MultiplayerProtocol.BLOCK_UPDATE, output -> {
                output.writeInt(x);
                output.writeInt(y);
                output.writeInt(z);
                output.writeUTF(Blocks.serializedId(state));
                output.writeByte(distance);
            });
        }
    }

    private void broadcastMobSnapshot(VoxelWorld world) {
        List<MobEntity> mobs = world.getMobs();
        for (ServerClient client : serverClients.values()) {
            send(client.connection, MultiplayerProtocol.MOB_SNAPSHOT, output -> {
                output.writeInt(Math.min(mobs.size(), 128));
                for (int i = 0; i < mobs.size() && i < 128; i++) {
                    MobEntity mob = mobs.get(i);
                    output.writeByte(mob.kind.ordinal());
                    output.writeDouble(mob.x);
                    output.writeDouble(mob.y);
                    output.writeDouble(mob.z);
                    output.writeDouble(mob.bodyYaw);
                    output.writeDouble(mob.health);
                    output.writeDouble(mob.babyAge);
                }
            });
        }
    }

    private void broadcastDroppedItemSnapshot(VoxelWorld world) {
        List<DroppedItem> items = world.getDroppedItems();
        for (ServerClient client : serverClients.values()) {
            send(client.connection, MultiplayerProtocol.DROPPED_ITEM_SNAPSHOT, output -> {
                output.writeInt(Math.min(items.size(), 128));
                for (int i = 0; i < items.size() && i < 128; i++) {
                    DroppedItem item = items.get(i);
                    output.writeByte(item.itemId);
                    output.writeInt(item.count);
                    output.writeInt(item.durabilityDamage);
                    output.writeDouble(item.x);
                    output.writeDouble(item.y);
                    output.writeDouble(item.z);
                }
            });
        }
    }

    private void processRemoteClientItemPickups(VoxelWorld world) {
        if (world == null || serverClients.isEmpty()) {
            return;
        }
        List<DroppedItem> items = world.getDroppedItems();
        double pickupDistance = GameConfig.DROPPED_ITEM_PICKUP_RADIUS;
        double pickupDistanceSquared = pickupDistance * pickupDistance;
        for (int i = items.size() - 1; i >= 0; i--) {
            DroppedItem item = items.get(i);
            if (item.pickupDelaySeconds > 0.0 || item.count <= 0) {
                continue;
            }
            for (ServerClient client : serverClients.values()) {
                if (!client.connection.open || client.player.health <= 0.0) {
                    continue;
                }
                double dx = item.x - client.player.x;
                double dy = (item.y + item.height() * 0.5) - (client.player.y + GameConfig.PLAYER_HEIGHT * 0.5);
                double dz = item.z - client.player.z;
                if (dx * dx + dy * dy + dz * dz <= pickupDistanceSquared) {
                    final byte itemId = item.itemId;
                    final int count = item.count;
                    final int durabilityDamage = item.durabilityDamage;
                    send(client.connection, MultiplayerProtocol.INVENTORY_ADD, output -> {
                        output.writeByte(itemId);
                        output.writeInt(count);
                        output.writeInt(durabilityDamage);
                    });
                    items.remove(i);
                    break;
                }
            }
        }
    }

    private byte droppedItemForBrokenBlock(byte block) {
        if (!InventoryItems.isCollectible(block)
            || block == GameConfig.OAK_LEAVES
            || block == GameConfig.PINE_LEAVES
            || block == GameConfig.BIRCH_LEAVES
            || GameConfig.isLiquidBlock(block)) {
            return GameConfig.AIR;
        }
        if (block == GameConfig.COAL_ORE || block == GameConfig.DEEPSLATE_COAL_ORE) {
            return InventoryItems.COAL_ITEM;
        }
        if (block == GameConfig.DIAMOND_ORE || block == GameConfig.DEEPSLATE_DIAMOND_ORE) {
            return InventoryItems.DIAMOND_ITEM;
        }
        if (block == GameConfig.STONE) {
            return GameConfig.COBBLESTONE;
        }
        return block;
    }

    private boolean canHarvestBlock(byte block, byte heldItem) {
        if (block == GameConfig.OBSIDIAN) {
            return pickaxeTier(heldItem) >= 4;
        }
        if (block == GameConfig.DIAMOND_ORE || block == GameConfig.DEEPSLATE_DIAMOND_ORE) {
            return pickaxeTier(heldItem) >= 3;
        }
        if (block == GameConfig.IRON_ORE || block == GameConfig.DEEPSLATE_IRON_ORE) {
            return pickaxeTier(heldItem) >= 2;
        }
        if (isStoneHarvestBlock(block)) {
            return pickaxeTier(heldItem) >= 1;
        }
        return true;
    }

    private boolean isStoneHarvestBlock(byte block) {
        return block == GameConfig.STONE
            || block == GameConfig.COBBLESTONE
            || block == GameConfig.DEEPSLATE
            || block == GameConfig.COAL_ORE
            || block == GameConfig.DEEPSLATE_COAL_ORE
            || block == GameConfig.IRON_ORE
            || block == GameConfig.DEEPSLATE_IRON_ORE
            || block == GameConfig.DIAMOND_ORE
            || block == GameConfig.DEEPSLATE_DIAMOND_ORE
            || block == GameConfig.OBSIDIAN;
    }

    private int pickaxeTier(byte itemId) {
        switch (itemId) {
            case InventoryItems.WOODEN_PICKAXE:
                return 1;
            case InventoryItems.STONE_PICKAXE:
                return 2;
            case InventoryItems.IRON_PICKAXE:
                return 3;
            case InventoryItems.DIAMOND_PICKAXE:
                return 4;
            case InventoryItems.NETHERITE_PICKAXE:
                return 5;
            default:
                return 0;
        }
    }

    private void applyMobSnapshot(byte[] payload) {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            VoxelWorld world = activeWorld;
            if (world == null) {
                return;
            }
            List<MobEntity> mobs = world.getMobs();
            ArrayList<MobEntity> previousMobs = new ArrayList<>(mobs);
            boolean[] reused = new boolean[previousMobs.size()];
            mobs.clear();
            int count = input.readInt();
            for (int i = 0; i < count; i++) {
                int ordinal = input.readUnsignedByte();
                MobKind[] kinds = MobKind.values();
                MobKind kind = ordinal >= 0 && ordinal < kinds.length ? kinds[ordinal] : MobKind.ZOMBIE;
                double x = input.readDouble();
                double y = input.readDouble();
                double z = input.readDouble();
                double bodyYaw = input.readDouble();
                MobEntity mob = reuseMob(previousMobs, reused, kind, x, y, z);
                mob.capturePreviousPosition();
                mob.x = x;
                mob.y = y;
                mob.z = z;
                mob.bodyYaw = bodyYaw;
                mob.targetBodyYaw = mob.bodyYaw;
                mob.health = input.readDouble();
                mob.babyAge = input.readDouble();
                mobs.add(mob);
            }
        } catch (IOException exception) {
            setStatus("Mob sync failed: " + exception.getMessage());
        }
    }

    private MobEntity reuseMob(List<MobEntity> previousMobs, boolean[] reused, MobKind kind, double x, double y, double z) {
        int bestIndex = -1;
        double bestDistanceSquared = 16.0;
        for (int i = 0; i < previousMobs.size(); i++) {
            if (reused[i]) {
                continue;
            }
            MobEntity candidate = previousMobs.get(i);
            if (candidate.kind != kind) {
                continue;
            }
            double dx = candidate.x - x;
            double dy = candidate.y - y;
            double dz = candidate.z - z;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared < bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                bestIndex = i;
            }
        }
        if (bestIndex >= 0) {
            reused[bestIndex] = true;
            return previousMobs.get(bestIndex);
        }
        return new MobEntity(kind, x, y, z, 0.0, 0.0, new java.util.Random(1L));
    }

    private void applyDroppedItemSnapshot(byte[] payload) {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            VoxelWorld world = activeWorld;
            if (world == null) {
                return;
            }
            List<DroppedItem> items = world.getDroppedItems();
            items.clear();
            int count = input.readInt();
            for (int i = 0; i < count; i++) {
                byte itemId = input.readByte();
                int itemCount = input.readInt();
                int durabilityDamage = input.readInt();
                DroppedItem item = new DroppedItem(itemId, itemCount, input.readDouble(), input.readDouble(), input.readDouble());
                item.durabilityDamage = durabilityDamage;
                items.add(item);
            }
        } catch (IOException exception) {
            setStatus("Item sync failed: " + exception.getMessage());
        }
    }

    private void sendChunkRequest(Connection connection, int chunkX, int chunkZ) {
        send(connection, MultiplayerProtocol.CHUNK_REQUEST, output -> {
            output.writeInt(chunkX);
            output.writeInt(chunkZ);
        });
    }

    private void sendDisconnect(Connection connection, String reason) {
        send(connection, MultiplayerProtocol.DISCONNECT, output -> output.writeUTF(reason));
    }

    private void send(Connection connection, byte type, MultiplayerProtocol.PacketWriter writer) {
        if (connection == null || !connection.open) {
            return;
        }
        synchronized (connection.output) {
            try {
                MultiplayerProtocol.writePacket(connection.output, type, writer);
            } catch (IOException exception) {
                closeQuietly(connection);
            }
        }
    }

    private void emitChat(String message) {
        if (listener != null) {
            mainThreadEvents.add(() -> listener.onMultiplayerChat(message));
        }
    }

    private void setStatus(String nextStatus) {
        status = nextStatus == null ? "Offline" : nextStatus;
        if (listener != null) {
            final String deliveredStatus = status;
            mainThreadEvents.add(() -> listener.onMultiplayerStatus(deliveredStatus));
        }
    }

    private byte[] readRemaining(DataInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (input.available() > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, input.available()));
            if (read <= 0) {
                break;
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void closeQuietly(Connection connection) {
        if (connection != null) {
            connection.open = false;
            try {
                connection.socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private long columnKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private int unpackColumnX(long key) {
        return (int) (key >> 32);
    }

    private int unpackColumnZ(long key) {
        return (int) key;
    }

    private static final class Connection {
        final Socket socket;
        final DataInputStream input;
        final DataOutputStream output;
        volatile boolean open = true;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.socket.setTcpNoDelay(true);
            this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }
    }

    private final class ServerClient {
        final UUID uuid;
        String name;
        final Connection connection;
        final PlayerState player = new PlayerState();
        byte heldItem = GameConfig.AIR;
        int pingMs = -1;
        long pendingPingTime = -1L;
        long lastServerDamageMillis = -1L;

        ServerClient(UUID uuid, String name, Connection connection) {
            this.uuid = uuid;
            this.name = name;
            this.connection = connection;
        }

        void worldUpdate(String displayName) {
            VoxelWorld world = activeWorld;
            if (world != null) {
                world.updateRemotePlayer(uuid, displayName, player.x, player.y, player.z, player.yaw, player.pitch, heldItem, player.sneaking, player.spectatorMode);
            }
        }
    }

    private static final class BlockAction {
        final ServerClient client;
        final byte kind;
        final int x;
        final int y;
        final int z;
        final int previousX;
        final int previousY;
        final int previousZ;
        final byte heldItem;

        BlockAction(ServerClient client, byte kind, int x, int y, int z, int previousX, int previousY, int previousZ, byte heldItem) {
            this.client = client;
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.previousX = previousX;
            this.previousY = previousY;
            this.previousZ = previousZ;
            this.heldItem = heldItem;
        }

        static BlockAction read(ServerClient client, DataInputStream input) throws IOException {
            return new BlockAction(
                client,
                input.readByte(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readInt(),
                input.readByte()
            );
        }
    }

    private static final class ChunkRequest {
        final ServerClient client;
        final int chunkX;
        final int chunkZ;

        ChunkRequest(ServerClient client, int chunkX, int chunkZ) {
            this.client = client;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }
}

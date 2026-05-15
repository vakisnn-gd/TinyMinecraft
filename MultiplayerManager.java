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
import java.util.HashSet;
import java.util.List;
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
    }

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final double PLAYER_SEND_INTERVAL = 0.05;
    private static final double HOST_BROADCAST_INTERVAL = 0.08;
    private static final double ENTITY_SNAPSHOT_INTERVAL = 0.35;
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

    private volatile boolean hostRunning;
    private volatile boolean clientRunning;
    private volatile VoxelWorld activeWorld;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Connection clientConnection;
    private PlayerState activeHostPlayer;
    private String status = "Offline";
    private double clientPlayerSendTimer;
    private double hostBroadcastTimer;
    private double entitySnapshotTimer;
    private double clientChunkRequestTimer;
    private volatile boolean clientDisconnectEventQueued;

    MultiplayerManager(LocalProfile profile, Listener listener) {
        this.profile = profile;
        this.listener = listener;
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
        hostRunning = true;
        setStatus("Hosting on port " + port);
        acceptThread = new Thread(() -> acceptLoop(world, hostPlayer), "TinyCraft LAN host");
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
        setStatus("Connecting to " + host + ":" + port);
        Thread thread = new Thread(() -> clientConnectLoop(host, port), "TinyCraft LAN client");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    void stop() {
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
        setStatus("Offline");
    }

    void tickHost(VoxelWorld world, PlayerState hostPlayer, byte hostHeldItem, double deltaTime) {
        if (!hostRunning) {
            return;
        }
        activeWorld = world;
        activeHostPlayer = hostPlayer;
        processHostRequests(world);
        hostBroadcastTimer += deltaTime;
        entitySnapshotTimer += deltaTime;
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
    }

    void tickClient(VoxelWorld world, PlayerState player, byte heldItem, int renderDistanceChunks, double deltaTime) {
        if (!clientRunning || clientConnection == null) {
            return;
        }
        activeWorld = world;
        clientPlayerSendTimer += deltaTime;
        clientChunkRequestTimer += deltaTime;
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
            String formatted = "<" + profile.name + "> " + message;
            emitChat(formatted);
            broadcastChat(formatted);
        } else if (clientRunning && clientConnection != null) {
            send(clientConnection, MultiplayerProtocol.CHAT, output -> output.writeUTF(message));
        }
    }

    void sendBlockBreak(int x, int y, int z) {
        if (clientRunning && clientConnection != null) {
            send(clientConnection, MultiplayerProtocol.BLOCK_ACTION, output -> {
                output.writeByte(MultiplayerProtocol.BLOCK_BREAK);
                output.writeInt(x);
                output.writeInt(y);
                output.writeInt(z);
                output.writeInt(x);
                output.writeInt(y);
                output.writeInt(z);
                output.writeByte(GameConfig.AIR);
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
            applyPlayerAttack(profile.uuid, targetUuid, damage);
        }
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
            if (profile.uuid.equals(uuid) || serverClients.containsKey(uuid)) {
                sendDisconnect(connection, "Duplicate player uuid.");
                return;
            }
            ServerClient client = new ServerClient(uuid, name, connection);
            client.player.setPosition(hostPlayer.x + 1.5, hostPlayer.y, hostPlayer.z + 1.5);
            client.player.y = world.safeStandingYAt(client.player.x, client.player.z);
            serverClients.put(uuid, client);
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
            }
        } finally {
            closeQuietly(connection);
            if (uuid != null) {
                ServerClient removed = serverClients.remove(uuid);
                if (removed != null) {
                    mainThreadEvents.add(() -> world.removeRemotePlayer(removed.uuid));
                    broadcastDespawn(uuid);
                    emitChat(removed.name + " left the world.");
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
        client.player.capturePreviousPosition();
        client.player.x = input.readDouble();
        client.player.y = input.readDouble();
        client.player.z = input.readDouble();
        client.player.yaw = input.readDouble();
        client.player.pitch = input.readDouble();
        client.heldItem = input.readByte();
        client.player.sneaking = input.readBoolean();
        client.player.spectatorMode = input.readBoolean();
        client.player.health = input.readDouble();
        mainThreadEvents.add(() -> client.worldUpdate(packetName));
    }

    private void processHostRequests(VoxelWorld world) {
        ChunkRequest chunkRequest;
        int chunks = 0;
        while (chunks < 4 && (chunkRequest = pendingChunkRequests.poll()) != null) {
            final ChunkRequest request = chunkRequest;
            ServerClient client = request.client;
            if (client.connection.open) {
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
                changed = world.breakBlock(new RayHit(action.x, action.y, action.z, action.x, action.y, action.z));
            } else if (action.kind == MultiplayerProtocol.BLOCK_PLACE) {
                PlayerState actor = action.client.player;
                changed = world.placeBlock(new RayHit(action.x, action.y, action.z, action.previousX, action.previousY, action.previousZ), action.heldItem, actor);
            }
            if (changed) {
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
        PlayerState attacker = profile.uuid.equals(attackerUuid)
            ? activeHostPlayer
            : serverClients.containsKey(attackerUuid) ? serverClients.get(attackerUuid).player : null;
        PlayerState target = profile.uuid.equals(targetUuid)
            ? activeHostPlayer
            : serverClients.containsKey(targetUuid) ? serverClients.get(targetUuid).player : null;
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
        if (!profile.uuid.equals(targetUuid)) {
            ServerClient targetClient = serverClients.get(targetUuid);
            if (targetClient != null) {
                send(targetClient.connection, MultiplayerProtocol.PLAYER_HEALTH, output -> output.writeDouble(target.health));
            }
        }
        if (profile.uuid.equals(targetUuid)) {
            broadcastPlayerState(targetUuid, profile.name, target, GameConfig.AIR);
        } else {
            ServerClient targetClient = serverClients.get(targetUuid);
            if (targetClient != null) {
                broadcastPlayerState(targetUuid, targetClient.name, target, targetClient.heldItem);
            }
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

    private void applyMobSnapshot(byte[] payload) {
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            VoxelWorld world = activeWorld;
            if (world == null) {
                return;
            }
            List<MobEntity> mobs = world.getMobs();
            mobs.clear();
            int count = input.readInt();
            for (int i = 0; i < count; i++) {
                int ordinal = input.readUnsignedByte();
                MobKind[] kinds = MobKind.values();
                MobKind kind = ordinal >= 0 && ordinal < kinds.length ? kinds[ordinal] : MobKind.ZOMBIE;
                MobEntity mob = new MobEntity(kind, input.readDouble(), input.readDouble(), input.readDouble(), 0.0, 0.0, new java.util.Random(1L));
                mob.bodyYaw = input.readDouble();
                mob.health = input.readDouble();
                mob.babyAge = input.readDouble();
                mobs.add(mob);
            }
        } catch (IOException exception) {
            setStatus("Mob sync failed: " + exception.getMessage());
        }
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
        mainThreadEvents.add(() -> listener.onMultiplayerChat(message));
    }

    private void setStatus(String nextStatus) {
        status = nextStatus == null ? "Offline" : nextStatus;
        mainThreadEvents.add(() -> listener.onMultiplayerStatus(status));
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
        final String name;
        final Connection connection;
        final PlayerState player = new PlayerState();
        byte heldItem = GameConfig.AIR;

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

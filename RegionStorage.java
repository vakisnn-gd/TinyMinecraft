import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class RegionStorage {
    private static final byte[] MAGIC = {'M', 'C', 'R', 'X'};
    private static final int VERSION = 1;
    private static final int PAYLOAD_STATUS_MARKER = 0x53544154;
    private static final int HEADER_SIZE = MAGIC.length + Integer.BYTES
        + GameConfig.REGION_CHUNK_COUNT * (Long.BYTES + Integer.BYTES);

    private final Path regionDirectory;

    RegionStorage(Path worldDirectory) {
        this.regionDirectory = worldDirectory.resolve(GameConfig.SAVE_REGION_DIRECTORY);
    }

    synchronized VoxelWorld.ChunkColumn loadColumn(int chunkX, int chunkZ) {
        Path regionPath = regionFilePath(chunkX, chunkZ);
        if (!Files.isRegularFile(regionPath)) {
            return null;
        }

        try {
            byte[][] payloads = readRegionPayloads(regionPath);
            byte[] payload = payloads[localChunkIndex(chunkX, chunkZ)];
            return payload == null ? null : decodeColumnPayload(payload, chunkX, chunkZ);
        } catch (IOException | RuntimeException exception) {
            if (GameConfig.ENABLE_DEBUG_LOGS) {
                System.out.println("RegionStorage: failed to load column " + chunkX + "," + chunkZ + ": " + exception.getMessage());
            }
            return null;
        }
    }

    synchronized void saveColumn(VoxelWorld.ChunkColumn column) throws IOException {
        if (column == null) {
            return;
        }

        Files.createDirectories(regionDirectory);
        Path regionPath = regionFilePath(column.chunkX, column.chunkZ);
        byte[][] payloads = readRegionPayloads(regionPath);
        payloads[localChunkIndex(column.chunkX, column.chunkZ)] = encodeColumnPayload(column);
        writeRegionPayloads(regionPath, payloads);
    }

    synchronized boolean hasColumn(int chunkX, int chunkZ) {
        Path regionPath = regionFilePath(chunkX, chunkZ);
        if (!Files.isRegularFile(regionPath)) {
            return false;
        }
        try {
            byte[][] payloads = readRegionPayloads(regionPath);
            return payloads[localChunkIndex(chunkX, chunkZ)] != null;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    static boolean validateCoordinatesForDebug() {
        return regionCoordinate(-1) == -1 && localChunkCoordinate(-1) == 31
            && regionCoordinate(0) == 0 && localChunkCoordinate(0) == 0
            && regionCoordinate(31) == 0 && localChunkCoordinate(31) == 31
            && regionCoordinate(32) == 1 && localChunkCoordinate(32) == 0;
    }

    static int regionCoordinate(int chunkCoordinate) {
        return Math.floorDiv(chunkCoordinate, GameConfig.REGION_SIZE_CHUNKS);
    }

    static int localChunkCoordinate(int chunkCoordinate) {
        return Math.floorMod(chunkCoordinate, GameConfig.REGION_SIZE_CHUNKS);
    }

    static int localChunkIndex(int chunkX, int chunkZ) {
        int localChunkX = localChunkCoordinate(chunkX);
        int localChunkZ = localChunkCoordinate(chunkZ);
        return localChunkZ * GameConfig.REGION_SIZE_CHUNKS + localChunkX;
    }

    static String regionFileNameForChunk(int chunkX, int chunkZ) {
        return "r." + regionCoordinate(chunkX) + "." + regionCoordinate(chunkZ) + GameConfig.REGION_FILE_EXTENSION;
    }

    private Path regionFilePath(int chunkX, int chunkZ) {
        return regionDirectory.resolve(regionFileNameForChunk(chunkX, chunkZ));
    }

    private byte[][] readRegionPayloads(Path regionPath) throws IOException {
        byte[][] payloads = new byte[GameConfig.REGION_CHUNK_COUNT][];
        if (!Files.isRegularFile(regionPath)) {
            return payloads;
        }

        byte[] fileBytes = Files.readAllBytes(regionPath);
        if (fileBytes.length < HEADER_SIZE) {
            return payloads;
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(fileBytes))) {
            byte[] magic = new byte[MAGIC.length];
            input.readFully(magic);
            if (!Arrays.equals(MAGIC, magic) || input.readInt() != VERSION) {
                return payloads;
            }

            long[] offsets = new long[GameConfig.REGION_CHUNK_COUNT];
            int[] lengths = new int[GameConfig.REGION_CHUNK_COUNT];
            for (int i = 0; i < GameConfig.REGION_CHUNK_COUNT; i++) {
                offsets[i] = input.readLong();
                lengths[i] = input.readInt();
            }

            for (int i = 0; i < GameConfig.REGION_CHUNK_COUNT; i++) {
                long offset = offsets[i];
                int length = lengths[i];
                if (offset <= 0 || length <= 0 || offset > Integer.MAX_VALUE) {
                    continue;
                }
                long end = offset + length;
                if (end > fileBytes.length || end > Integer.MAX_VALUE) {
                    continue;
                }
                payloads[i] = Arrays.copyOfRange(fileBytes, (int) offset, (int) end);
            }
        }
        return payloads;
    }

    private void writeRegionPayloads(Path regionPath, byte[][] payloads) throws IOException {
        long[] offsets = new long[GameConfig.REGION_CHUNK_COUNT];
        int[] lengths = new int[GameConfig.REGION_CHUNK_COUNT];
        long offset = HEADER_SIZE;
        for (int i = 0; i < GameConfig.REGION_CHUNK_COUNT; i++) {
            byte[] payload = payloads[i];
            if (payload == null || payload.length == 0) {
                continue;
            }
            offsets[i] = offset;
            lengths[i] = payload.length;
            offset += payload.length;
        }

        Path tempPath = regionPath.resolveSibling(regionPath.getFileName().toString() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(tempPath))) {
            output.write(MAGIC);
            output.writeInt(VERSION);
            for (int i = 0; i < GameConfig.REGION_CHUNK_COUNT; i++) {
                output.writeLong(offsets[i]);
                output.writeInt(lengths[i]);
            }
            for (byte[] payload : payloads) {
                if (payload != null && payload.length > 0) {
                    output.write(payload);
                }
            }
        }

        try {
            Files.move(tempPath, regionPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            Files.move(tempPath, regionPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private byte[] encodeColumnPayload(VoxelWorld.ChunkColumn column) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
            output.writeInt(column.chunkX);
            output.writeInt(column.chunkZ);
            output.writeInt(PAYLOAD_STATUS_MARKER);
            output.writeInt(column.status.ordinal());
            output.writeInt(GameConfig.SECTION_COUNT);
            for (int chunkY = 0; chunkY < GameConfig.SECTION_COUNT; chunkY++) {
                writeSection(output, column.section(chunkY));
            }
            for (int localZ = 0; localZ < Chunk.SIZE; localZ++) {
                for (int localX = 0; localX < Chunk.SIZE; localX++) {
                    output.writeInt(column.getSurfaceHeightLocal(localX, localZ));
                }
            }
        }
        return bytes.toByteArray();
    }

    private void writeSection(DataOutputStream output, Chunk chunk) throws IOException {
        boolean present = chunk != null && !chunk.isEmpty();
        output.writeBoolean(present);
        if (!present) {
            return;
        }

        ArrayList<BlockState> palette = new ArrayList<>();
        HashMap<Integer, Integer> paletteIndexes = new HashMap<>();
        int[] indices = new int[Chunk.VOLUME];
        for (int index = 0; index < Chunk.VOLUME; index++) {
            BlockState state = chunk.getBlockStateAtIndex(index);
            int stateKey = (state.type.numericId << 16) ^ (state.data & 0xFFFF);
            Integer paletteIndex = paletteIndexes.get(stateKey);
            if (paletteIndex == null) {
                paletteIndex = palette.size();
                paletteIndexes.put(stateKey, paletteIndex);
                palette.add(state);
            }
            indices[index] = paletteIndex;
        }

        output.writeInt(palette.size());
        for (BlockState state : palette) {
            output.writeUTF(Blocks.serializedId(state));
        }
        for (int index = 0; index < Chunk.VOLUME; index++) {
            output.writeInt(indices[index]);
        }
        for (int index = 0; index < Chunk.VOLUME; index++) {
            output.writeByte(chunk.getFluidDistanceAtIndex(index));
        }
    }

    private VoxelWorld.ChunkColumn decodeColumnPayload(byte[] payload, int expectedChunkX, int expectedChunkZ) throws IOException {
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            if (chunkX != expectedChunkX || chunkZ != expectedChunkZ) {
                return null;
            }

            VoxelWorld.ChunkColumn column = new VoxelWorld.ChunkColumn(chunkX, chunkZ);
            ChunkGenerationStatus status = ChunkGenerationStatus.FULL;
            int markerOrSectionCount = input.readInt();
            int sectionCount = markerOrSectionCount;
            if (markerOrSectionCount == PAYLOAD_STATUS_MARKER) {
                status = ChunkGenerationStatus.fromOrdinal(input.readInt());
                sectionCount = input.readInt();
            }
            if (sectionCount < 0 || sectionCount > 256) {
                throw new IOException("Invalid section count: " + sectionCount);
            }
            for (int chunkY = 0; chunkY < sectionCount; chunkY++) {
                readSection(input, chunkY < GameConfig.SECTION_COUNT ? column.section(chunkY) : null);
            }
            for (int localZ = 0; localZ < Chunk.SIZE; localZ++) {
                for (int localX = 0; localX < Chunk.SIZE; localX++) {
                    column.setSurfaceHeightLocal(localX, localZ, input.readInt());
                }
            }
            column.status = status;
            column.dirty = false;
            return column;
        }
    }

    private void readSection(DataInputStream input, Chunk chunk) throws IOException {
        boolean present = input.readBoolean();
        if (!present) {
            return;
        }

        int paletteSize = input.readInt();
        if (paletteSize <= 0 || paletteSize > Chunk.VOLUME) {
            throw new IOException("Invalid section palette size: " + paletteSize);
        }

        BlockState[] palette = new BlockState[paletteSize];
        for (int paletteIndex = 0; paletteIndex < paletteSize; paletteIndex++) {
            palette[paletteIndex] = Blocks.stateFromNamespacedId(input.readUTF());
            if (palette[paletteIndex] == null) {
                palette[paletteIndex] = Blocks.stateFromLegacyId(GameConfig.AIR);
            }
        }

        int[] indices = new int[Chunk.VOLUME];
        for (int index = 0; index < Chunk.VOLUME; index++) {
            indices[index] = input.readInt();
        }
        for (int index = 0; index < Chunk.VOLUME; index++) {
            int distance = input.readByte();
            if (chunk == null) {
                continue;
            }
            int paletteIndex = indices[index];
            BlockState state = paletteIndex >= 0 && paletteIndex < palette.length
                ? palette[paletteIndex]
                : Blocks.stateFromLegacyId(GameConfig.AIR);
            chunk.setSerializedCellState(index, state, distance);
        }
    }
}

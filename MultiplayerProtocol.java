import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.UUID;

final class MultiplayerProtocol {
    static final int MAGIC = 0x54434D50; // TCMP
    static final int VERSION = 3;
    static final int DEFAULT_PORT = 25566;
    static final int MAX_PACKET_BYTES = 4 * 1024 * 1024;

    static final byte HELLO = 1;
    static final byte WELCOME = 2;
    static final byte PLAYER_SPAWN = 3;
    static final byte PLAYER_STATE = 4;
    static final byte PLAYER_DESPAWN = 5;
    static final byte CHAT = 6;
    static final byte CHUNK_REQUEST = 7;
    static final byte CHUNK_DATA = 8;
    static final byte BLOCK_ACTION = 9;
    static final byte BLOCK_UPDATE = 10;
    static final byte WORLD_TIME = 11;
    static final byte MOB_SNAPSHOT = 12;
    static final byte DROPPED_ITEM_SNAPSHOT = 13;
    static final byte DISCONNECT = 14;
    static final byte PLAYER_ATTACK = 15;
    static final byte PLAYER_HEALTH = 16;
    static final byte PING = 17;
    static final byte PONG = 18;
    static final byte PLAYER_LIST = 19;
    static final byte COMMAND = 20;
    static final byte INVENTORY_ADD = 21;
    static final byte MOB_ATTACK = 22;

    static final byte BLOCK_BREAK = 1;
    static final byte BLOCK_PLACE = 2;

    private MultiplayerProtocol() {
    }

    static void writePacket(DataOutputStream output, byte type, PacketWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream packet = new DataOutputStream(bytes);
        packet.writeByte(type);
        if (writer != null) {
            writer.write(packet);
        }
        packet.flush();
        byte[] payload = bytes.toByteArray();
        output.writeInt(payload.length);
        output.write(payload);
        output.flush();
    }

    static Packet readPacket(DataInputStream input) throws IOException {
        int length;
        try {
            length = input.readInt();
        } catch (EOFException eof) {
            return null;
        }
        if (length <= 0 || length > MAX_PACKET_BYTES) {
            throw new IOException("invalid packet length: " + length);
        }
        byte[] payload = new byte[length];
        input.readFully(payload);
        DataInputStream packetInput = new DataInputStream(new ByteArrayInputStream(payload));
        byte type = packetInput.readByte();
        return new Packet(type, packetInput);
    }

    static void writeUuid(DataOutputStream output, UUID uuid) throws IOException {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    interface PacketWriter {
        void write(DataOutputStream output) throws IOException;
    }

    static final class Packet {
        final byte type;
        final DataInputStream input;

        Packet(byte type, DataInputStream input) {
            this.type = type;
            this.input = input;
        }
    }
}

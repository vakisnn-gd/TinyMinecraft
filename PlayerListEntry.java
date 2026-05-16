import java.util.UUID;

final class PlayerListEntry {
    final UUID uuid;
    final String name;
    final int pingMs;
    final double health;
    final String gameMode;
    final boolean connected;

    PlayerListEntry(UUID uuid, String name, int pingMs, double health, String gameMode, boolean connected) {
        this.uuid = uuid;
        this.name = name == null || name.trim().isEmpty() ? "Player" : name;
        this.pingMs = pingMs;
        this.health = health;
        this.gameMode = gameMode == null || gameMode.trim().isEmpty() ? "survival" : gameMode;
        this.connected = connected;
    }
}

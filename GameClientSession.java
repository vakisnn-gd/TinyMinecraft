import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class GameClientSession {
    private final ArrayList<PlayerListEntry> playerList = new ArrayList<>();
    private int pingMs = -1;
    private String status = "Offline";

    void setStatus(String status) {
        this.status = status == null ? "Offline" : status;
    }

    String status() {
        return status;
    }

    void setPingMs(int pingMs) {
        this.pingMs = pingMs;
    }

    int pingMs() {
        return pingMs;
    }

    void replacePlayerList(List<PlayerListEntry> entries) {
        playerList.clear();
        if (entries != null) {
            playerList.addAll(entries);
        }
    }

    List<PlayerListEntry> playerList() {
        return Collections.unmodifiableList(playerList);
    }
}

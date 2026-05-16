import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class TinyCraftServer {
    private TinyCraftServer() {
    }

    public static void main(String[] args) throws IOException {
        ServerProperties properties = ServerProperties.loadOrCreate();
        properties.applyArgs(args);
        properties.printEffective();

        GameServer server = new GameServer(properties);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "TinyCraft server shutdown"));
        if (!server.start()) {
            System.exit(1);
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while (server.isRunning() && (line = reader.readLine()) != null) {
                server.handleConsoleCommand(line);
            }
        } finally {
            server.stop();
        }
    }
}

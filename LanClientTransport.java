import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

final class LanClientTransport {
    NetworkConnection connect(String host, int port, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMillis);
        return new NetworkConnection(socket);
    }
}

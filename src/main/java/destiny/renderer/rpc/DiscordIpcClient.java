package destiny.renderer.rpc;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

public class DiscordIpcClient {
    private final String clientId;
    private FileChannel pipeChannel;
    private SocketChannel unixChannel;
    
    public DiscordIpcClient(String clientId) {
        this.clientId = clientId;
    }
    
    public synchronized void connect() {
        if (isConnected()) return;
        
        String os = System.getProperty("os.name").toLowerCase();
        for (int i = 0; i < 10; i++) {
            try {
                if (os.contains("win")) {
                    RandomAccessFile raf = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
                    pipeChannel = raf.getChannel();
                } else {
                    String[] roots = {
                        System.getenv("XDG_RUNTIME_DIR"),
                        "/tmp",
                        System.getenv("TMPDIR"),
                        System.getenv("TMP"),
                        System.getenv("TEMP")
                    };
                    boolean connected = false;
                    for (String root : roots) {
                        if (root == null || root.isEmpty()) continue;
                        Path path = Paths.get(root, "discord-ipc-" + i);
                        if (path.toFile().exists()) {
                            unixChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
                            unixChannel.connect(UnixDomainSocketAddress.of(path));
                            connected = true;
                            break;
                        }
                    }
                    if (!connected) continue;
                }
                
                // Handshake
                String handshake = "{\"v\":1,\"client_id\":\"" + clientId + "\"}";
                sendFrame(0, handshake);
                return;
            } catch (Exception e) {
                disconnect();
            }
        }
    }
    
    public synchronized void disconnect() {
        try {
            if (pipeChannel != null) pipeChannel.close();
        } catch (IOException ignored) {}
        pipeChannel = null;
        
        try {
            if (unixChannel != null) unixChannel.close();
        } catch (IOException ignored) {}
        unixChannel = null;
    }
    
    public synchronized boolean isConnected() {
        return pipeChannel != null || unixChannel != null;
    }
    
    public synchronized void clearActivity() {
        if (!isConnected()) return;
        String payload = "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + ProcessHandle.current().pid() + "},\"nonce\":\"1\"}";
        sendFrame(1, payload);
    }

    public synchronized void sendActivity(String jsonPayload) {
        if (!isConnected()) return;
        
        String payload = "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + ProcessHandle.current().pid() + ",\"activity\":" + jsonPayload + "},\"nonce\":\"1\"}";
        sendFrame(1, payload);
    }
    
    private void sendFrame(int opcode, String payload) {
        try {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(8 + data.length);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(opcode);
            buffer.putInt(data.length);
            buffer.put(data);
            buffer.flip();
            
            if (pipeChannel != null) {
                while (buffer.hasRemaining()) pipeChannel.write(buffer);
            } else if (unixChannel != null) {
                while (buffer.hasRemaining()) unixChannel.write(buffer);
            }
        } catch (Exception e) {
            disconnect();
        }
    }
}

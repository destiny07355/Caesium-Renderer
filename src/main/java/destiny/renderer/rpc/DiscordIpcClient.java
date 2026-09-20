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
import java.util.concurrent.atomic.AtomicLong;

public class DiscordIpcClient {
    private final String clientId;
    private FileChannel pipeChannel;
    private SocketChannel unixChannel;
    private final AtomicLong nonce = new AtomicLong();
    
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
        return (pipeChannel != null && pipeChannel.isOpen())
            || (unixChannel != null && unixChannel.isOpen() && unixChannel.isConnected());
    }
    
    public synchronized void clearActivity() {
        if (!isConnected()) return;
        String payload = commandPayload(null, ProcessHandle.current().pid(), nonce.incrementAndGet());
        sendFrame(1, payload);
    }

    public synchronized void sendActivity(String jsonPayload) {
        if (!isConnected()) return;
        
        String payload = commandPayload(jsonPayload, ProcessHandle.current().pid(), nonce.incrementAndGet());
        sendFrame(1, payload);
    }

    static String commandPayload(String activity, long pid, long nonce) {
        String activityField = activity == null ? "" : ",\"activity\":" + activity;
        return "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + pid + activityField
            + "},\"nonce\":\"" + nonce + "\"}";
    }

    static ByteBuffer encodeFrame(int opcode, String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(opcode).putInt(data.length).put(data).flip();
        return buffer;
    }
    
    private void sendFrame(int opcode, String payload) {
        try {
            ByteBuffer buffer = encodeFrame(opcode, payload);
            
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

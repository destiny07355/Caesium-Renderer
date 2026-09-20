package destiny.renderer.rpc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class DiscordRpcProtocolTest {
    public static void main(String[] args) {
        String unicode = "Server \\\"A\\\" — 世界\nline";
        String escaped = DiscordPresenceManager.escapeJson(unicode);
        require(escaped.equals("Server \\\\\\\"A\\\\\\\" — 世界\\nline"), "JSON escaping");

        String activity = "{\"state\":\"Testing\"}";
        String command = DiscordIpcClient.commandPayload(activity, 42, 7);
        require(command.equals("{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":42,\"activity\":{\"state\":\"Testing\"}},\"nonce\":\"7\"}"), "activity command");

        ByteBuffer frame = DiscordIpcClient.encodeFrame(1, command).order(ByteOrder.LITTLE_ENDIAN);
        require(frame.getInt() == 1, "frame opcode");
        int size = frame.getInt();
        require(size == command.getBytes(StandardCharsets.UTF_8).length, "UTF-8 frame length");
        byte[] body = new byte[size];
        frame.get(body);
        require(command.equals(new String(body, StandardCharsets.UTF_8)), "frame body");
        require(!frame.hasRemaining(), "frame has no trailing bytes");

        String clear = DiscordIpcClient.commandPayload(null, 42, 8);
        require(!clear.contains("activity"), "clear command omits activity");
        System.out.println("PASS  Discord RPC framing, payloads, and escaping");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

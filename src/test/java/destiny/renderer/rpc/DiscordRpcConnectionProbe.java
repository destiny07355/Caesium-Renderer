package destiny.renderer.rpc;

public final class DiscordRpcConnectionProbe {
    public static void main(String[] args) {
        DiscordIpcClient client = new DiscordIpcClient("1343648172900000000");
        try {
            client.connect();
            if (!client.isConnected()) {
                System.out.println("SKIP  Discord desktop IPC endpoint is not available");
                return;
            }
            client.sendActivity("{\"details\":\"Caesium RPC self-test\",\"state\":\"Validating local IPC\"}");
            client.clearActivity();
            System.out.println("PASS  Discord desktop IPC endpoint opened and activity frames were written");
        } finally {
            client.disconnect();
        }
    }
}

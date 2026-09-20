package destiny.renderer.rpc;

import destiny.renderer.config.RendererConfig;
import destiny.renderer.DestinyRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import java.util.logging.Logger;

public final class DiscordPresenceManager {
    private static final Logger LOGGER = Logger.getLogger("Caesium/DiscordRPC");
    private static DiscordIpcClient client;
    private static Thread daemonThread;
    private static volatile boolean running;
    private static volatile boolean desiredEnabled;
    private static volatile String pendingActivity;

    private DiscordPresenceManager() {}
    
    public static synchronized void start() {
        RendererConfig cfg = RendererConfig.get();
        if (running || cfg == null || !cfg.enableDiscordRpc) return;
        desiredEnabled = true;
        running = true;
        
        client = new DiscordIpcClient("1343648172900000000");
        
        daemonThread = new Thread(() -> {
            while (running) {
                try {
                    if (!RendererConfig.get().enableDiscordRpc) break;
                    requestSnapshot();
                    if (!client.isConnected()) client.connect();
                    String activity = pendingActivity;
                    if (client.isConnected() && activity != null) client.sendActivity(activity);
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    LOGGER.fine("Discord RPC unavailable: " + e.getMessage());
                }
            }
            if (client != null) {
                if (client.isConnected()) {
                    client.clearActivity();
                }
                client.disconnect();
            }
            synchronized (DiscordPresenceManager.class) {
                running = false;
                daemonThread = null;
                client = null;
            }
            if (desiredEnabled && RendererConfig.get().enableDiscordRpc) start();
        }, "Caesium-DiscordRPC");
        daemonThread.setDaemon(true);
        daemonThread.start();
    }
    
    public static synchronized void stop() {
        desiredEnabled = false;
        running = false;
        if (daemonThread != null) {
            daemonThread.interrupt();
        }
        pendingActivity = null;
    }

    public static synchronized void setEnabled(boolean enabled) {
        RendererConfig.get().enableDiscordRpc = enabled;
        desiredEnabled = enabled;
        if (enabled) start(); else stop();
    }

    private static void requestSnapshot() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) mc.execute(() -> pendingActivity = buildActivity(mc, RendererConfig.get()));
    }

    static String buildActivity(MinecraftClient mc, RendererConfig cfg) {
        if (mc == null || cfg == null) return null;
        
        String details = "Playing Minecraft using Caesium";
        String state = "Main Menu";
        
        if (mc.world != null) {
            String dimension = "Overworld";
            if (mc.world.getRegistryKey() == net.minecraft.world.World.NETHER) {
                dimension = "Nether";
            } else if (mc.world.getRegistryKey() == net.minecraft.world.World.END) {
                dimension = "The End";
            } else {
                String path = mc.world.getRegistryKey().getValue().getPath();
                dimension = path.isEmpty() ? "Unknown" : Character.toUpperCase(path.charAt(0)) + path.substring(1);
            }
            
            String fpsStr = cfg.rpcShowFps ? (" (" + mc.getCurrentFps() + " FPS)") : "";
            
            if (mc.isInSingleplayer()) {
                state = "Singleplayer - " + dimension + fpsStr;
            } else {
                ServerInfo server = mc.getCurrentServerEntry();
                if (cfg.rpcShowServer && server != null && server.address != null) {
                    state = server.address + " - " + dimension + fpsStr;
                } else {
                    state = "Multiplayer - " + dimension + fpsStr;
                }
            }
        }
        
        var backend = DestinyRenderer.getActiveBackend();
        String backendName = backend != null && backend.name().toLowerCase().contains("vulkan") ? "vulkan" : "opengl";
        String smallText = backend == null ? "Vanilla terrain + Caesium optimizations" : backend.name();
        
        String json = "{" +
            "\"details\":\"" + escapeJson(details) + "\"," +
            "\"state\":\"" + escapeJson(state) + "\"," +
            "\"assets\":{" +
                "\"large_image\":\"caesium_logo\"," +
                "\"large_text\":\"Caesium Rendering Engine v" + escapeJson(DestinyRenderer.getVersion()) + "\"," +
                "\"small_image\":\"" + backendName + "\"," +
                "\"small_text\":\"" + escapeJson(smallText) + "\"" +
            "}" +
        "}";
        
        return json;
    }

    static String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}


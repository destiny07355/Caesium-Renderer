package destiny.renderer.rpc;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

public class DiscordPresenceManager {
    private static DiscordIpcClient client;
    private static Thread daemonThread;
    private static volatile boolean running = false;
    
    public static synchronized void start() {
        if (running) return;
        running = true;
        
        client = new DiscordIpcClient("1343648172900000000");
        
        daemonThread = new Thread(() -> {
            while (running) {
                try {
                    RendererConfig cfg = RendererConfig.get();
                    if (cfg != null && cfg.enableDiscordRpc) {
                        if (!client.isConnected()) {
                            client.connect();
                        }
                        if (client.isConnected()) {
                            update();
                        }
                    } else {
                        if (client.isConnected()) {
                            client.clearActivity();
                            client.disconnect();
                        }
                    }
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // silently handle
                }
            }
            if (client != null) {
                if (client.isConnected()) {
                    client.clearActivity();
                }
                client.disconnect();
            }
        }, "Caesium-DiscordRPC");
        daemonThread.setDaemon(true);
        daemonThread.start();
    }
    
    public static synchronized void stop() {
        running = false;
        if (daemonThread != null) {
            daemonThread.interrupt();
        }
    }
    
    public static void update() {
        MinecraftClient mc = MinecraftClient.getInstance();
        RendererConfig cfg = RendererConfig.get();
        if (mc == null || cfg == null || !client.isConnected()) return;
        
        String details = "Playing Minecraft using Caesium";
        String state = "Main Menu";
        
        if (mc.world != null) {
            String dimension = "Overworld";
            if (mc.world.getRegistryKey() == net.minecraft.world.World.NETHER) {
                dimension = "Nether";
            } else if (mc.world.getRegistryKey() == net.minecraft.world.World.END) {
                dimension = "The End";
            } else {
                dimension = mc.world.getRegistryKey().getValue().getPath();
                dimension = dimension.substring(0, 1).toUpperCase() + dimension.substring(1);
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
        
        String backendName = "opengl";
        if (cfg.renderingBackend != null && cfg.renderingBackend.toLowerCase().contains("vulkan")) {
            backendName = "vulkan";
        }
        String smallText = backendName.equals("vulkan") ? "Vulkan MDI Backend" : "OpenGL 3.3 Backend";
        
        String json = "{" +
            "\"details\":\"" + details + "\"," +
            "\"state\":\"" + state + "\"," +
            "\"assets\":{" +
                "\"large_image\":\"caesium_logo\"," +
                "\"large_text\":\"Caesium Rendering Engine v1.15.0\"," +
                "\"small_image\":\"" + backendName + "\"," +
                "\"small_text\":\"" + smallText + "\"" +
            "}" +
        "}";
        
        client.sendActivity(json);
    }
}


package destiny.renderer.hardware;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Enumerates Vulkan adapter names for the settings screen without starting the renderer. */
public final class VulkanDeviceDiscovery {
    private VulkanDeviceDiscovery() {}

    public static List<String> deviceNames() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                    .pApplicationName(stack.UTF8("Caesium"))
                    .applicationVersion(1)
                    .pEngineName(stack.UTF8("Caesium"))
                    .engineVersion(1)
                    .apiVersion(VK10.VK_API_VERSION_1_0);
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                    .pApplicationInfo(app);
            PointerBuffer instanceHandle = stack.mallocPointer(1);
            if (VK10.vkCreateInstance(createInfo, null, instanceHandle) != VK10.VK_SUCCESS) {
                return List.of();
            }
            VkInstance instance = new VkInstance(instanceHandle.get(0), createInfo);
            try {
                var count = stack.mallocInt(1);
                if (VK10.vkEnumeratePhysicalDevices(instance, count, null) != VK10.VK_SUCCESS
                        || count.get(0) == 0) {
                    return List.of();
                }
                PointerBuffer devices = stack.mallocPointer(count.get(0));
                if (VK10.vkEnumeratePhysicalDevices(instance, count, devices) != VK10.VK_SUCCESS) {
                    return List.of();
                }
                LinkedHashSet<String> names = new LinkedHashSet<>();
                for (int i = 0; i < count.get(0); i++) {
                    VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                    VK10.vkGetPhysicalDeviceProperties(new VkPhysicalDevice(devices.get(i), instance), props);
                    String name = props.deviceNameString();
                    if (name != null && !name.isBlank()) names.add(name);
                }
                return List.copyOf(new ArrayList<>(names));
            } finally {
                VK10.vkDestroyInstance(instance, null);
            }
        } catch (Throwable ignored) {
            return List.of();
        }
    }
}

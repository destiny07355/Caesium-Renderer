package caesium.engine.backend.vulkan;

import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.GpuBuffer;
import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.backend.GpuImage;
import caesium.engine.backend.GpuMemoryAllocator;
import caesium.engine.backend.GpuPipeline;
import caesium.engine.backend.GpuQueue;
import caesium.engine.backend.GpuSync;
import caesium.engine.backend.GpuTimer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memAllocLong;
import static org.lwjgl.system.MemoryUtil.memAllocPointer;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_GRAPHICS_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * The Vulkan backend (Month 2, primary). Owns the instance, physical device selection
 * (discrete first, then integrated), the logical device with graphics + transfer queues,
 * a command pool with per-frame command buffers and fences, and a render target.
 *
 * <p>Two targets exist behind the {@link RenderTarget} seam: an offscreen color target for
 * headless correctness tests and a GLFW window swapchain for the real present path. The
 * encoder draws into whichever target is active; when a window is attached via
 * {@link #attachWindow(long)} the same engine quad is rendered into a swapchain image and
 * presented with acquire/render semaphore sync around the per-frame fence (fifo pacing,
 * mailbox fallback, ARCHITECTURE.md §10.2).
 *
 * <p>Zero Minecraft imports — like every {@code caesium.engine.*} type.
 */
public final class VulkanBackend implements GpuBackend {

    static final int FRAMES_IN_FLIGHT = 2;
    static final int TARGET_WIDTH = 128;
    static final int TARGET_HEIGHT = 128;

    private final Queue queue = new Queue();

    /**
     * Physical-device preference passed from {@link caesium.engine.backend.BackendSelector}
     * (which reads the user's option): {@code AUTO} (score discrete first, then integrated),
     * {@code DISCRETE}, {@code INTEGRATED}, or a device-name substring to pin a specific
     * card. The chosen device must still expose a graphics queue, or AUTO scoring applies.
     */
    private final String devicePreference;

    private VkInstance instance;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private int graphicsFamily = -1;
    private int transferFamily = -1;
    private long commandPool;
    private final long[] commandBuffers = new long[FRAMES_IN_FLIGHT];
    private final long[] fences = new long[FRAMES_IN_FLIGHT];

    /** Frame slot selected by {@link #beginFrame(int)}; consumed by the next encoder. */
    private int currentFrameIndex;

    private OffscreenTarget offscreen;
    private SwapchainTarget window;

    public VulkanBackend() {
        this("AUTO");
    }

    public VulkanBackend(String devicePreference) {
        this.devicePreference = devicePreference == null || devicePreference.isBlank()
                ? "AUTO" : devicePreference.trim();
    }

    @Override
    public BackendType type() {
        return BackendType.VULKAN;
    }

    @Override
    public String name() {
        return "Caesium Vulkan backend";
    }

    /**
     * True when a Vulkan instance can be created on this driver. Used by
     * {@link caesium.engine.backend.BackendSelector} so a machine without Vulkan support
     * never attempts it — the choice falls back to OpenGL instead.
     *
     * <p>LWJGL 3.3.3 loads the system Vulkan library as part of {@code VK}'s class
     * initialisation, so a non-null {@code getFunctionProvider()} is the availability
     * signal. Calling {@code VK.create()} a second time would throw "already created",
     * which is exactly the false-negative we must avoid.
     */
    public static boolean isSupported() {
        try {
            return org.lwjgl.vulkan.VK.getFunctionProvider() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void initialize() {
        if (!isSupported()) {
            throw new IllegalStateException("Caesium: Vulkan is not available on this driver");
        }
        createInstance();
        pickPhysicalDevice();
        createDevice();
        createCommandPoolAndBuffers();
        offscreen = new OffscreenTarget(this, device, TARGET_WIDTH, TARGET_HEIGHT);
        offscreen.initialize();
    }

    /**
     * Attaches a GLFW window to this backend. The next encoder will render into the
     * window's swapchain instead of the offscreen target, and each frame is presented
     * with acquire/render semaphore sync (fifo pacing, mailbox fallback). Re-attaching
     * (e.g. after a window resize) tears down the old swapchain first.
     */
    public void attachWindow(long glfwWindow) {
        if (window != null) {
            window.destroy();
            window = null;
        }
        window = new SwapchainTarget(this, device, glfwWindow);
        window.initialize();
    }

    /** Detaches the window and resumes offscreen rendering. Safe to call when none attached. */
    public void detachWindow() {
        if (window != null) {
            window.destroy();
            window = null;
        }
    }

    /** The current render target: the swapchain when a window is attached, else offscreen. */
    RenderTarget activeTarget() {
        return window != null ? window : offscreen;
    }

    @Override
    public void shutdown() {
        if (device == null) {
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        if (window != null) {
            window.destroy();
            window = null;
        }
        if (offscreen != null) {
            offscreen.destroy();
            offscreen = null;
        }
        if (commandPool != 0L) {
            VK10.vkDestroyCommandPool(device, commandPool, null);
            commandPool = 0L;
        }
        if (fences != null && device != null) {
            for (long fence : fences) {
                if (fence != VK10.VK_NULL_HANDLE) VK10.vkDestroyFence(device, fence, null);
            }
        }
        if (device != null) {
            VK10.vkDestroyDevice(device, null);
            device = null;
        }
        if (instance != null) {
            VK10.vkDestroyInstance(instance, null);
            instance = null;
        }
    }

    private void createInstance() {
        try (MemoryStack stack = stackPush()) {
            VkInstanceCreateInfo info = VkInstanceCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            PointerBuffer required = GLFWVulkan.glfwGetRequiredInstanceExtensions();
            if (required != null) {
                info.ppEnabledExtensionNames(required);
            }
            PointerBuffer pInstance = memAllocPointer(1);
            int err = VK10.vkCreateInstance(info, null, pInstance);
            if (err != VK_SUCCESS) {
                memFree(pInstance);
                throw new IllegalStateException("Caesium: vkCreateInstance failed: " + err);
            }
            instance = new VkInstance(pInstance.get(0), info);
            memFree(pInstance);
        }
    }

    private void pickPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            int err = VK10.vkEnumeratePhysicalDevices(instance, pCount, null);
            if (err != VK_SUCCESS || pCount.get(0) == 0) {
                throw new IllegalStateException("Caesium: no Vulkan physical devices");
            }
            int count = pCount.get(0);
            PointerBuffer devices = memAllocPointer(count);
            long best = 0L;
            try {
                err = VK10.vkEnumeratePhysicalDevices(instance, pCount, devices);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkEnumeratePhysicalDevices failed: " + err);
                }
                int bestScore = -1;
                String pinned = isPinnedName() ? devicePreference.toLowerCase() : null;
                for (int i = 0; i < count; i++) {
                    long handle = devices.get(i);
                    VkPhysicalDevice pd = new VkPhysicalDevice(handle, instance);
                    int score = pinned != null
                            ? pinScore(pd, pinned)
                            : preferenceScore(pd);
                    if (score > bestScore) {
                        bestScore = score;
                        best = handle;
                    }
                }
                if (best == 0L) {
                    // No device matched the preference; fall back to AUTO scoring so the engine
                    // never fails to start because of a bad device choice.
                    best = 0L;
                    bestScore = -1;
                    for (int i = 0; i < count; i++) {
                        long handle = devices.get(i);
                        int score = scoreDevice(new VkPhysicalDevice(handle, instance));
                        if (score > bestScore) {
                            bestScore = score;
                            best = handle;
                        }
                    }
                }
                if (best == 0L) {
                    throw new RuntimeException("[Caesium] No suitable Vulkan physical device found. Check GPU drivers.");
                }
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(devices);
            }
            physicalDevice = new VkPhysicalDevice(best, instance);
        }
    }

    private boolean isPinnedName() {
        return !"AUTO".equals(devicePreference)
                && !"DISCRETE".equals(devicePreference)
                && !"INTEGRATED".equals(devicePreference);
    }

    /** Score by the user's AUTO / DISCRETE / INTEGRATED preference. */
    private int preferenceScore(VkPhysicalDevice pd) {
        int type = deviceType(pd);
        return switch (devicePreference) {
            case "DISCRETE" -> type == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 1000 : -1000;
            case "INTEGRATED" -> type == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 1000 : -1000;
            default -> type == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 1000
                    : (type == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 500 : 0);
        };
    }

    /** Score by device-name substring pin; only the named card is eligible. */
    private int pinScore(VkPhysicalDevice pd, String pin) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.callocStack(stack);
            VK10.vkGetPhysicalDeviceProperties(pd, props);
            String name = props.deviceNameString();
            return name.toLowerCase().contains(pin) ? 2000 : -2000;
        }
    }

    private int deviceType(VkPhysicalDevice pd) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.callocStack(stack);
            VK10.vkGetPhysicalDeviceProperties(pd, props);
            return props.deviceType();
        }
    }

    /** Baseline capability score used by AUTO selection (discrete first, then integrated). */
    private static int scoreDevice(VkPhysicalDevice pd) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.callocStack(stack);
            VK10.vkGetPhysicalDeviceProperties(pd, props);
            return switch (props.deviceType()) {
                case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 1000;
                case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 500;
                default -> 0;
            };
        }
    }

    private void createDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pCount = stack.mallocInt(1);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pCount, null);
            int count = pCount.get(0);
            VkQueueFamilyProperties.Buffer props =
                    VkQueueFamilyProperties.callocStack(count, stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, pCount, props);

            int graphics = -1;
            int transfer = -1;
            for (int i = 0; i < count; i++) {
                int flags = props.get(i).queueFlags();
                if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0 && graphics < 0) {
                    graphics = i;
                }
                if ((flags & VK_QUEUE_TRANSFER_BIT) != 0 && transfer < 0 && graphics != i) {
                    transfer = i;
                }
            }
            if (graphics < 0) {
                throw new IllegalStateException("Caesium: no Vulkan graphics queue family");
            }
            graphicsFamily = graphics;
            transferFamily = transfer >= 0 ? transfer : graphics;

            VkDeviceQueueCreateInfo.Buffer queueInfos =
                    VkDeviceQueueCreateInfo.callocStack(2, stack);
            FloatBuffer priorities = stack.mallocFloat(1);
            priorities.put(0, 1.0f);
            queueInfos.get(0)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(graphicsFamily)
                    .pQueuePriorities(priorities);
            int queueCount = 1;
            if (transferFamily != graphicsFamily) {
                FloatBuffer priorities2 = stack.mallocFloat(1);
                priorities2.put(0, 1.0f);
                queueInfos.get(1)
                        .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                        .queueFamilyIndex(transferFamily)
                        .pQueuePriorities(priorities2);
                queueCount = 2;
            }

            VkDeviceCreateInfo info = VkDeviceCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(queueInfos.limit(queueCount));
            PointerBuffer swapchainExt = stack.mallocPointer(1);
            ByteBuffer swapchainName = stack.ASCII("VK_KHR_swapchain");
            swapchainExt.put(memAddress(swapchainName));
            swapchainExt.flip();
            info.ppEnabledExtensionNames(swapchainExt);

            PointerBuffer pDevice = memAllocPointer(1);
            int err = VK10.vkCreateDevice(physicalDevice, info, null, pDevice);
            if (err != VK_SUCCESS) {
                memFree(pDevice);
                throw new IllegalStateException("Caesium: vkCreateDevice failed: " + err);
            }
            device = new VkDevice(pDevice.get(0), physicalDevice, info);
            memFree(pDevice);
        }
    }

    private void createCommandPoolAndBuffers() {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(graphicsFamily);
            LongBuffer pPool = stack.mallocLong(1);
            int err = VK10.vkCreateCommandPool(device, poolInfo, null, pPool);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateCommandPool failed: " + err);
            }
            commandPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPool)
                    .level(VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(FRAMES_IN_FLIGHT);
            PointerBuffer pBuffers = memAllocPointer(FRAMES_IN_FLIGHT);
            err = VK10.vkAllocateCommandBuffers(device, allocInfo, pBuffers);
            if (err != VK_SUCCESS) {
                memFree(pBuffers);
                throw new IllegalStateException("Caesium: vkAllocateCommandBuffers failed: " + err);
            }
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                commandBuffers[i] = pBuffers.get(i);
            }
            memFree(pBuffers);
        }

        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                .flags(VK10.VK_FENCE_CREATE_SIGNALED_BIT);
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            LongBuffer pFence = memAllocLong(1);
            int err = VK10.vkCreateFence(device, fenceInfo, null, pFence);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateFence failed: " + err);
            }
            fences[i] = pFence.get(0);
            memFree(pFence);
        }
        fenceInfo.free();
    }

    @Override
    public GpuQueue graphicsQueue() {
        return queue;
    }

    @Override
    public GpuQueue transferQueue() {
        return queue;
    }

    @Override
    public GpuMemoryAllocator memory() {
        return new Allocator();
    }

    @Override
    public GpuBuffer createBuffer(GpuBuffer.Usage usage, int size) {
        return new Buffer(usage, size);
    }

    @Override
    public GpuImage createImage(GpuImage.Format format, int width, int height) {
        return new Image(format, width, height);
    }

    @Override
    public GpuPipeline createPipeline() {
        return activeTarget().pipeline();
    }

    @Override
    public GpuPipeline createPipeline(GpuCommandEncoder.VertexLayout layout) {
        return activeTarget().pipeline(layout);
    }

    @Override
    public int viewportWidth() {
        return activeTarget().width();
    }

    @Override
    public int viewportHeight() {
        return activeTarget().height();
    }

    @Override
    public GpuTimer createTimer() {
        return new Timer();
    }

    @Override
    public void beginFrame(int frameIndex) {
        currentFrameIndex = frameIndex;
        if (window != null) {
            // Acquire first: sets the swapchain image this frame will render into. On
            // OUT_OF_DATE the window needs a resize-driven swapchain rebuild (later increment).
            if (window.acquire(frameIndex) < 0) {
                return;
            }
        }
        if (fences[frameIndex] != 0L) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer pFence = stack.mallocLong(1).put(0, fences[frameIndex]);
                VK10.vkWaitForFences(device, pFence, true, Long.MAX_VALUE);
                VK10.vkResetFences(device, pFence);
            }
            VK10.vkResetCommandBuffer(new VkCommandBuffer(commandBuffers[frameIndex], device), 0);
        }
    }

    @Override
    public void endFrame(int frameIndex) {
        if (window != null) {
            window.present(frameIndex);
        }
    }

    /**
     * Reads back the RGBA value of one pixel of the active render target after the queue
     * has drained. Used by the headless correctness tests to prove the quad reached the
     * GPU. Coordinates are in image space (origin bottom-left).
     */
    public int readBackPixel(int x, int y) {
        VK10.vkDeviceWaitIdle(device);
        return activeTarget() == window
                ? window.readBackPixel(x, y)
                : offscreen.readBackPixel(x, y);
    }

    /**
     * Marks the next presented frame for pixel capture (swapchain path). Only meaningful
     * when a window is attached; no-op otherwise.
     */
    public void requestCapture() {
        if (window != null) {
            window.requestCapture();
        }
    }

    // -------------------------------------------------------------------------
    // Backend-internal helpers (package-visible for OffscreenTarget/SwapchainTarget)
    // -------------------------------------------------------------------------

    VkDevice device() {
        return device;
    }

    VkInstance instance() {
        return instance;
    }

    int graphicsFamilyIndex() {
        return graphicsFamily;
    }

    VkPhysicalDevice physicalDevice() {
        return physicalDevice;
    }

    VkQueue graphicsVkQueue() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pQueue = stack.mallocPointer(1);
            VK10.vkGetDeviceQueue(device, graphicsFamily, 0, pQueue);
            return new VkQueue(pQueue.get(0), device);
        }
    }

    int findMemoryType(int typeBits, int propertyFlags) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties props =
                    VkPhysicalDeviceMemoryProperties.callocStack(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, props);
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0
                        && (props.memoryTypes(i).propertyFlags() & propertyFlags) == propertyFlags) {
                    return i;
                }
            }
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0) {
                    return i;
                }
            }
            return -1;
        }
    }

    static int bufferUsageFlags(GpuBuffer.Usage usage) {
        return switch (usage) {
            case VERTEX -> VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            case INDEX -> VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            case UNIFORM -> VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            case STAGING -> VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
            case INDIRECT -> VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            case STORAGE -> VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        };
    }

    // -------------------------------------------------------------------------
    // Encoder / queue
    // -------------------------------------------------------------------------

    private final class Queue implements GpuQueue {
        @Override
        public String name() {
            return "graphics";
        }

        @Override
        public GpuCommandEncoder createEncoder() {
            return new Encoder();
        }

        @Override
        public void submit(GpuCommandEncoder encoder) {
            Encoder e = (Encoder) encoder;
            if (window != null) {
                window.submitFrame(e.commandBuffer(), fences[e.frameIndex()], e.frameIndex());
                return;
            }
            VkQueue vkQueue = graphicsVkQueue();
            try (MemoryStack stack = stackPush()) {
                PointerBuffer pCmd = stack.mallocPointer(1).put(0, e.commandBuffer());
                VkSubmitInfo submit = VkSubmitInfo.callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(pCmd);
                int err = VK10.vkQueueSubmit(vkQueue, submit, fences[e.frameIndex()]);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkQueueSubmit failed: " + err);
                }
            }
        }

        @Override
        public void waitIdle() {
            VK10.vkQueueWaitIdle(graphicsVkQueue());
        }
    }

    private final class Encoder implements GpuCommandEncoder {
        private int frameIndex;
        private long cmdBuffer;
        private boolean began;

        @Override
        public void begin() {
            // The engine creates the encoder before beginFrame(idx), so the slot is only
            // known now (currentFrameIndex was set by beginFrame right after creation).
            frameIndex = currentFrameIndex % FRAMES_IN_FLIGHT;
            cmdBuffer = commandBuffers[frameIndex];
            try (MemoryStack stack = stackPush()) {
                VkCommandBufferBeginInfo info = VkCommandBufferBeginInfo.callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                        .flags(VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                int err = VK10.vkBeginCommandBuffer(new VkCommandBuffer(cmdBuffer, device), info);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkBeginCommandBuffer failed: " + err);
                }
                // Open the active render pass; every engine draw lands in the target.
                activeTarget().beginRenderPass(cmdBuffer);
            }
            began = true;
        }

        long commandBuffer() {
            return cmdBuffer;
        }

        int frameIndex() {
            return frameIndex;
        }

        @Override
        public void bindPipeline(GpuPipeline pipeline) {
            VK10.vkCmdBindPipeline(new VkCommandBuffer(cmdBuffer, device),
                    VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
            RenderTarget t = activeTarget();
            try (MemoryStack stack = stackPush()) {
                VK10.vkCmdBindDescriptorSets(new VkCommandBuffer(cmdBuffer, device),
                        VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, t.pipelineLayout(),
                        0, stack.longs(t.descriptorSet()), null);
            }
        }

        @Override
        public void bindUniformBuffer(GpuBuffer buffer) {
            RenderTarget t = activeTarget();
            VulkanUniforms.writeSetToBuffer(device, t.descriptorSet(),
                    ((Buffer) buffer).handle(), ((Buffer) buffer).size());
        }

        @Override
        public void writeBuffer(GpuBuffer buffer, int offset, ByteBuffer data) {
            // All engine buffers are host-visible; the memory is written directly, so no
            // copy command can ever be recorded inside the render pass (which Vulkan forbids).
            ((Buffer) buffer).write(offset, data);
        }

        @Override
        public void bindVertexBuffer(GpuBuffer buffer, VertexLayout layout) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer pBuffer = stack.mallocLong(1).put(0, ((Buffer) buffer).handle());
                LongBuffer offsets = stack.mallocLong(1).put(0, 0L);
                VK10.vkCmdBindVertexBuffers(new VkCommandBuffer(cmdBuffer, device), 0, pBuffer, offsets);
            }
        }

        @Override
        public void bindIndexBuffer(GpuBuffer buffer) {
            VK10.vkCmdBindIndexBuffer(new VkCommandBuffer(cmdBuffer, device),
                    ((Buffer) buffer).handle(), 0L, VK10.VK_INDEX_TYPE_UINT32);
        }

        @Override
        public void draw(int vertexCount, int instanceCount) {
            VK10.vkCmdDraw(new VkCommandBuffer(cmdBuffer, device), vertexCount, instanceCount, 0, 0);
        }

        @Override
        public void drawIndexed(int indexCount, int instanceCount) {
            VK10.vkCmdDrawIndexed(new VkCommandBuffer(cmdBuffer, device),
                    indexCount, instanceCount, 0, 0, 0);
        }

        @Override
        public void copyBuffer(GpuBuffer src, int srcOffset, GpuBuffer dst, int dstOffset, int size) {
            try (MemoryStack stack = stackPush()) {
                org.lwjgl.vulkan.VkBufferCopy.Buffer copy =
                        org.lwjgl.vulkan.VkBufferCopy.callocStack(1, stack);
                copy.get(0).srcOffset(srcOffset).dstOffset(dstOffset).size(size);
                VK10.vkCmdCopyBuffer(new VkCommandBuffer(cmdBuffer, device),
                        ((Buffer) src).handle(), ((Buffer) dst).handle(), copy);
            }
        }

        @Override
        public void writeTimestamp(GpuTimer timer, boolean end) {
            if (timer instanceof Timer t) {
                if (end) {
                    t.recordEnd(cmdBuffer);
                } else {
                    t.recordStart(cmdBuffer);
                }
            }
        }

        @Override
        public void end() {
            if (began) {
                activeTarget().endRenderPass(cmdBuffer);
                VK10.vkEndCommandBuffer(new VkCommandBuffer(cmdBuffer, device));
                began = false;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Timer (timestamp query pool for GPU-side region timing)
    // -------------------------------------------------------------------------

    /**
     * Measures GPU time of a command region via two timestamp queries (top-of-pipe and
     * bottom-of-pipe). The pool lives on the graphics queue family and the elapsed time is
     * {@code (end - start) * timestampPeriod}. Returns 0 when the device reports no
     * timestamp support (no-op timer), so the engine never pays for an unavailable feature.
     */
    private final class Timer implements GpuTimer {
        private static final int QUERY_COUNT = 2;

        private long pool;
        private final float timestampPeriod;

        Timer() {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc();
            VK10.vkGetPhysicalDeviceProperties(physicalDevice, props);
            timestampPeriod = props.limits().timestampPeriod();
            boolean hasTimestamp = props.limits().timestampComputeAndGraphics();
            props.free();
            if (timestampPeriod > 0.0f && hasTimestamp) {
                createQueryPool();
            }
        }

        private void createQueryPool() {
            try (MemoryStack stack = stackPush()) {
                org.lwjgl.vulkan.VkQueryPoolCreateInfo info =
                        org.lwjgl.vulkan.VkQueryPoolCreateInfo.callocStack(stack)
                                .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                                .queryType(org.lwjgl.vulkan.VK10.VK_QUERY_TYPE_TIMESTAMP)
                                .queryCount(QUERY_COUNT);
                LongBuffer pPool = stack.mallocLong(1);
                int err = VK10.vkCreateQueryPool(device, info, null, pPool);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkCreateQueryPool failed: " + err);
                }
                pool = pPool.get(0);
            }
        }

        void recordStart(long cmdBuffer) {
            if (pool != 0L) {
                VK10.vkCmdWriteTimestamp(new VkCommandBuffer(cmdBuffer, device),
                        org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool, 0);
            }
        }

        void recordEnd(long cmdBuffer) {
            if (pool != 0L) {
                VK10.vkCmdWriteTimestamp(new VkCommandBuffer(cmdBuffer, device),
                        org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, 1);
            }
        }

        @Override
        public long elapsedNanos() {
            if (pool == 0L) {
                return 0L;
            }
            try (MemoryStack stack = stackPush()) {
                LongBuffer results = stack.mallocLong(QUERY_COUNT);
                int err = VK10.vkGetQueryPoolResults(device, pool, 0, QUERY_COUNT,
                        results, Long.BYTES, org.lwjgl.vulkan.VK10.VK_QUERY_RESULT_64_BIT);
                if (err != VK_SUCCESS) {
                    return 0L;
                }
                long start = results.get(0);
                long end = results.get(1);
                return end > start
                        ? (long) ((end - start) * (double) timestampPeriod)
                        : 0L;
            }
        }

        @Override
        public void destroy() {
            if (pool != 0L) {
                VK10.vkDestroyQueryPool(device, pool, null);
                pool = 0L;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Buffer (host-visible; written directly by the engine)
    // -------------------------------------------------------------------------

    private final class Buffer implements GpuBuffer {
        private final Usage usage;
        private final int size;
        private long handle;
        private long memory;
        private boolean destroyed;

        Buffer(Usage usage, int size) {
            this.usage = usage;
            this.size = size;
            handle = createBufferHandle(usage, size);
            memory = allocateAndBindBufferMemory(handle);
        }

        private long createBufferHandle(Usage usage, int size) {
            try (MemoryStack stack = stackPush()) {
                org.lwjgl.vulkan.VkBufferCreateInfo info =
                        org.lwjgl.vulkan.VkBufferCreateInfo.callocStack(stack)
                                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                                .size(size)
                                .usage(bufferUsageFlags(usage))
                                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
                LongBuffer pBuffer = stack.mallocLong(1);
                int err = VK10.vkCreateBuffer(device, info, null, pBuffer);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkCreateBuffer failed: " + err);
                }
                return pBuffer.get(0);
            }
        }

        private long allocateAndBindBufferMemory(long bufferHandle) {
            try (MemoryStack stack = stackPush()) {
                VkMemoryRequirements req = VkMemoryRequirements.callocStack(stack);
                VK10.vkGetBufferMemoryRequirements(device, bufferHandle, req);
                int typeIndex = findMemoryType(req.memoryTypeBits(),
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(req.size())
                        .memoryTypeIndex(typeIndex);
                LongBuffer pMem = stack.mallocLong(1);
                int err = VK10.vkAllocateMemory(device, alloc, null, pMem);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkAllocateMemory failed: " + err);
                }
                long mem = pMem.get(0);
                err = VK10.vkBindBufferMemory(device, bufferHandle, mem, 0L);
                if (err != VK_SUCCESS) {
                    VK10.vkFreeMemory(device, mem, null);
                    throw new IllegalStateException("Caesium: vkBindBufferMemory failed: " + err);
                }
                return mem;
            }
        }

        void write(int offset, ByteBuffer data) {
            try (MemoryStack stack = stackPush()) {
                PointerBuffer pp = stack.mallocPointer(1);
                VK10.vkMapMemory(device, memory, offset, data.remaining(), 0, pp);
                long mapped = pp.get(0);
                if (mapped != 0L) {
                    org.lwjgl.system.MemoryUtil.memCopy(
                            org.lwjgl.system.MemoryUtil.memAddress(data), mapped, data.remaining());
                }
                VK10.vkUnmapMemory(device, memory);
            }
        }

        @Override
        public Usage usage() {
            return usage;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public long handle() {
            return handle;
        }

        @Override
        public void destroy() {
            if (!destroyed) {
                VK10.vkDestroyBuffer(device, handle, null);
                VK10.vkFreeMemory(device, memory, null);
                destroyed = true;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Image
    // -------------------------------------------------------------------------

    private final class Image implements GpuImage {
        private final Format format;
        private final int width;
        private final int height;
        private long handle;
        private long memory;
        private boolean destroyed;

        Image(Format format, int width, int height) {
            this.format = format;
            this.width = width;
            this.height = height;
            try (MemoryStack stack = stackPush()) {
                VkImageCreateInfo info = VkImageCreateInfo.callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .imageType(VK10.VK_IMAGE_TYPE_2D)
                        .format(vkFormat(format))
                        .extent(it -> it.width(width).height(height).depth(1))
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                                | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
                LongBuffer pImage = stack.mallocLong(1);
                int err = VK10.vkCreateImage(device, info, null, pImage);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkCreateImage failed: " + err);
                }
                handle = pImage.get(0);
            }
            memory = allocateImageMemory(handle);
        }

        private long allocateImageMemory(long imageHandle) {
            try (MemoryStack stack = stackPush()) {
                VkMemoryRequirements req = VkMemoryRequirements.callocStack(stack);
                VK10.vkGetImageMemoryRequirements(device, imageHandle, req);
                int typeIndex = findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(req.size())
                        .memoryTypeIndex(typeIndex);
                LongBuffer pMem = stack.mallocLong(1);
                int err = VK10.vkAllocateMemory(device, alloc, null, pMem);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkAllocateMemory (image) failed: " + err);
                }
                long mem = pMem.get(0);
                err = VK10.vkBindImageMemory(device, imageHandle, mem, 0L);
                if (err != VK_SUCCESS) {
                    VK10.vkFreeMemory(device, mem, null);
                    throw new IllegalStateException("Caesium: vkBindImageMemory failed: " + err);
                }
                return mem;
            }
        }

        @Override
        public Format format() {
            return format;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public long handle() {
            return handle;
        }

        @Override
        public void destroy() {
            if (!destroyed) {
                VK10.vkDestroyImage(device, handle, null);
                VK10.vkFreeMemory(device, memory, null);
                destroyed = true;
            }
        }
    }

    private static int vkFormat(GpuImage.Format f) {
        return switch (f) {
            case RGBA8 -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case RGBA16F -> VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
            case DEPTH24 -> VK10.VK_FORMAT_D24_UNORM_S8_UINT;
            case DEPTH32F -> VK10.VK_FORMAT_D32_SFLOAT;
        };
    }

    // -------------------------------------------------------------------------
    // Allocator
    // -------------------------------------------------------------------------

    private final class Allocator implements GpuMemoryAllocator {
        private long bytes;
        private int count;

        @Override
        public GpuBuffer allocate(GpuBuffer.Usage usage, int size) {
            Buffer buffer = new Buffer(usage, size);
            bytes += size;
            count++;
            return buffer;
        }

        @Override
        public void free(GpuBuffer buffer) {
            if (buffer instanceof Buffer b && !b.destroyed) {
                bytes -= b.size();
                count--;
                b.destroy();
            }
        }

        @Override
        public long bytesAllocated() {
            return bytes;
        }

        @Override
        public int activeAllocations() {
            return count;
        }
    }

    // -------------------------------------------------------------------------
    // Sync (fence-backed; semaphores arrive with the swapchain increment)
    // -------------------------------------------------------------------------

    private static final class Sync implements GpuSync {
        private final VulkanBackend backend;
        private long fence;

        Sync(VulkanBackend backend) {
            this.backend = backend;
        }

        @Override
        public void signal() {
        }

        @Override
        public void waitFor() {
            if (fence != 0L) {
                try (MemoryStack stack = stackPush()) {
                    LongBuffer pFence = stack.mallocLong(1).put(0, fence);
                    VK10.vkWaitForFences(backend.device, pFence, true, Long.MAX_VALUE);
                }
            }
        }

        @Override
        public void reset() {
            if (fence != 0L) {
                try (MemoryStack stack = stackPush()) {
                    LongBuffer pFence = stack.mallocLong(1).put(0, fence);
                    VK10.vkResetFences(backend.device, pFence);
                }
            }
        }
    }
}
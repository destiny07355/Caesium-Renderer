package caesium.engine.backend.vulkan;

import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.backend.GpuPipeline;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAllocLong;
import static org.lwjgl.system.MemoryUtil.memAllocPointer;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memGetByte;
import static org.lwjgl.vulkan.KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_PRESENT_MODE_MAILBOX_KHR;
import static org.lwjgl.vulkan.KHRSurface.VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfacePresentModesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_SUBOPTIMAL_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkAcquireNextImageKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkQueuePresentKHR;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_CONTENTS_INLINE;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_EXTERNAL;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * The window target (Month 2 swapchain increment): a GLFW window surface, the swapchain
 * built on it, per-image framebuffers, the acquire/render semaphores and the present
 * submission that paces frames (FIFO first — stable pacing per ARCHITECTURE.md §10.2,
 * mailbox as a fallback). The encoder renders the engine's quad into the current swapchain
 * image and the backend presents it, giving the first real "render into a window" path.
 *
 * <p>Zero Minecraft imports — like every {@code caesium.engine.*} type.
 */
final class SwapchainTarget implements RenderTarget {

    private static final long UINT64_MAX = 0xFFFFFFFFFFFFFFFFL;

    private final VulkanBackend backend;
    private final VkDevice device;
    private final long window;

    private long surface;
    private long swapchain;
    private int format;
    private int presentMode;
    private int width;
    private int height;

    private long[] imageHandles;
    private long[] imageViews;
    private long[] framebuffers;
    private long renderPass;
    private long pipelineLayout;
    private long pipeline;
    private long terrainPipeline;
    private long descriptorPool;
    private long descriptorSet;
    private long setLayout;
    private long identityUbo;
    private long identityUboMemory;
    private long[] acquireSemaphores;
    private long[] renderSemaphores;

    private int currentImageIndex;
    private int framesInFlight;

    // Readback (headless correctness check of the presented pixels).
    private long readbackBuffer;
    private long readbackBufferMemory;
    private long readbackPool;
    private long readbackCmd;
    private long readbackFence;
    private boolean captureNext;

    private GpuPipeline pipelineWrapper;

    SwapchainTarget(VulkanBackend backend, VkDevice device, long window) {
        this.backend = backend;
        this.device = device;
        this.window = window;
    }

    void initialize() {
        framesInFlight = VulkanBackend.FRAMES_IN_FLIGHT;
        createSurface();
        pickFormatAndPresentMode();
        createSwapchain();
        createImageViews();
        createRenderPass();
        createFramebuffers();
        createPipeline();
        createDescriptorResources();
        createSyncObjects();
        createReadbackResources();
        pipelineWrapper = new Pipeline(pipeline);
    }

    private void createSurface() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.mallocLong(1);
            int err = org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface(
                    backend.instance(), window, null, pSurface);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: glfwCreateWindowSurface failed: " + err);
            }
            surface = pSurface.get(0);
        }
    }

    private void pickFormatAndPresentMode() {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.callocStack(stack);
            int err = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                    backend.physicalDevice(), surface, caps);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed: " + err);
            }
            int minImageCount = Math.max(caps.minImageCount(), framesInFlight + 1);
            if (caps.maxImageCount() > 0) {
                minImageCount = Math.min(minImageCount, caps.maxImageCount());
            }
            width = caps.currentExtent().width() == 0xFFFFFFFF
                    ? 128 : caps.currentExtent().width();
            height = caps.currentExtent().height() == 0xFFFFFFFF
                    ? 128 : caps.currentExtent().height();

            // Prefer a linear UNORM surface format so pixel readback is byte-exact; fall
            // back to whatever the surface offers (usually B8G8R8A8_SRGB).
            IntBuffer pCount = stack.mallocInt(1);
            err = vkGetPhysicalDeviceSurfaceFormatsKHR(backend.physicalDevice(), surface, pCount, null);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkGetPhysicalDeviceSurfaceFormatsKHR (count) failed: " + err);
            }
            int fmtCount = pCount.get(0);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.callocStack(fmtCount, stack);
            err = vkGetPhysicalDeviceSurfaceFormatsKHR(backend.physicalDevice(), surface, pCount, formats);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkGetPhysicalDeviceSurfaceFormatsKHR failed: " + err);
            }
            int chosen = formats.get(0).format();
            for (int i = 0; i < fmtCount; i++) {
                int f = formats.get(i).format();
                if (f == VK10.VK_FORMAT_B8G8R8A8_UNORM || f == VK10.VK_FORMAT_R8G8B8A8_UNORM) {
                    chosen = f;
                    break;
                }
            }
            format = chosen;

            IntBuffer pModes = stack.mallocInt(1);
            err = vkGetPhysicalDeviceSurfacePresentModesKHR(backend.physicalDevice(), surface, pModes, null);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkGetPhysicalDeviceSurfacePresentModesKHR (count) failed: " + err);
            }
            int modeCount = pModes.get(0);
            IntBuffer modes = stack.mallocInt(modeCount);
            err = vkGetPhysicalDeviceSurfacePresentModesKHR(backend.physicalDevice(), surface, pModes, modes);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkGetPhysicalDeviceSurfacePresentModesKHR failed: " + err);
            }
            presentMode = VK_PRESENT_MODE_FIFO_KHR; // guaranteed everywhere
            for (int i = 0; i < modeCount; i++) {
                if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                    presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
                    break;
                }
            }
        }
    }

    private void createSwapchain() {
        try (MemoryStack stack = stackPush()) {
            VkSwapchainCreateInfoKHR info = VkSwapchainCreateInfoKHR.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                    .surface(surface)
                    .minImageCount(Math.max(2, framesInFlight + 1))
                    .imageFormat(format)
                    .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                    .imageExtent(VkExtent2D.callocStack(stack).set(width, height))
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(presentMode)
                    .clipped(true);
            LongBuffer pSwapchain = stack.mallocLong(1);
            int err = vkCreateSwapchainKHR(device, info, null, pSwapchain);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateSwapchainKHR failed: " + err);
            }
            swapchain = pSwapchain.get(0);

            IntBuffer pCount = stack.mallocInt(1);
            err = vkGetSwapchainImagesKHR(device, swapchain, pCount, null);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkGetSwapchainImagesKHR (count) failed: " + err);
            }
            int count = pCount.get(0);
            LongBuffer pImages = stack.mallocLong(count);
            err = vkGetSwapchainImagesKHR(device, swapchain, pCount, pImages);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkGetSwapchainImagesKHR failed: " + err);
            }
            imageHandles = new long[count];
            for (int i = 0; i < count; i++) {
                imageHandles[i] = pImages.get(i);
            }
        }
    }

    private void createImageViews() {
        imageViews = new long[imageHandles.length];
        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < imageHandles.length; i++) {
                VkImageViewCreateInfo info = VkImageViewCreateInfo.callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(imageHandles[i])
                        .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                        .format(format)
                        .subresourceRange(it -> it
                                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0)
                                .levelCount(1)
                                .baseArrayLayer(0)
                                .layerCount(1));
                LongBuffer pView = stack.mallocLong(1);
                int err = VK10.vkCreateImageView(device, info, null, pView);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkCreateImageView (swapchain) failed: " + err);
                }
                imageViews[i] = pView.get(0);
            }
        }
    }

    private void createRenderPass() {
        try (MemoryStack stack = stackPush()) {
            VkAttachmentDescription.Buffer attachments =
                    VkAttachmentDescription.callocStack(1, stack);
            attachments.get(0)
                    .format(format)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.callocStack(1, stack);
            colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.callocStack(1, stack);
            subpass.get(0)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);

            VkSubpassDependency.Buffer dependency = VkSubpassDependency.callocStack(1, stack);
            dependency.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(attachments)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);

            LongBuffer pRenderPass = stack.mallocLong(1);
            int err = VK10.vkCreateRenderPass(device, info, null, pRenderPass);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateRenderPass (swapchain) failed: " + err);
            }
            renderPass = pRenderPass.get(0);
        }
    }

    private void createFramebuffers() {
        framebuffers = new long[imageViews.length];
        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < imageViews.length; i++) {
                LongBuffer pAttachments = stack.mallocLong(1).put(0, imageViews[i]);
                VkFramebufferCreateInfo info = VkFramebufferCreateInfo.callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                        .renderPass(renderPass)
                        .pAttachments(pAttachments)
                        .width(width)
                        .height(height)
                        .layers(1);
                LongBuffer pFramebuffer = stack.mallocLong(1);
                int err = VK10.vkCreateFramebuffer(device, info, null, pFramebuffer);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkCreateFramebuffer (swapchain) failed: " + err);
                }
                framebuffers[i] = pFramebuffer.get(0);
            }
        }
    }

    private void createPipeline() {
        long[] outLayout = new long[1];
        long[] outSetLayout = new long[1];
        pipeline = VulkanPipelineFactory.create(device, renderPass, width, height,
                outLayout, outSetLayout);
        pipelineLayout = outLayout[0];
        setLayout = outSetLayout[0];
        pipelineWrapper = new Pipeline(pipeline);
    }

    private void createDescriptorResources() {
        descriptorPool = VulkanUniforms.createDescriptorPool(device);
        descriptorSet = VulkanUniforms.allocateDescriptorSet(device, descriptorPool, setLayout);
        long[] ubo = VulkanUniforms.createIdentityUbo(device, backend);
        identityUbo = ubo[0];
        identityUboMemory = ubo[1];
        VulkanUniforms.writeSetToBuffer(device, descriptorSet, identityUbo, VulkanUniforms.UNIFORM_BYTES);
    }

    private void createSyncObjects() {
        acquireSemaphores = new long[framesInFlight];
        renderSemaphores = new long[framesInFlight];
        VkSemaphoreCreateInfo info = VkSemaphoreCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < framesInFlight; i++) {
                LongBuffer pAcquire = stack.mallocLong(1);
                int err = VK10.vkCreateSemaphore(device, info, null, pAcquire);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkCreateSemaphore (acquire) failed: " + err);
                }
                acquireSemaphores[i] = pAcquire.get(0);

                LongBuffer pRender = stack.mallocLong(1);
                err = VK10.vkCreateSemaphore(device, info, null, pRender);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: vkCreateSemaphore (render) failed: " + err);
                }
                renderSemaphores[i] = pRender.get(0);
            }
        }
        info.free();
    }

    private void createReadbackResources() {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size((long) width * height * 4)
                    .usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            int err = VK10.vkCreateBuffer(device, info, null, pBuffer);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateBuffer (swapchain readback) failed: " + err);
            }
            readbackBuffer = pBuffer.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.callocStack(stack);
            VK10.vkGetBufferMemoryRequirements(device, readbackBuffer, req);
            int typeIndex = backend.findMemoryType(req.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(typeIndex);
            LongBuffer pMem = stack.mallocLong(1);
            err = VK10.vkAllocateMemory(device, alloc, null, pMem);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkAllocateMemory (swapchain readback) failed: " + err);
            }
            readbackBufferMemory = pMem.get(0);
            VK10.vkBindBufferMemory(device, readbackBuffer, readbackBufferMemory, 0L);
        }

        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(backend.graphicsFamilyIndex());
            LongBuffer pPool = stack.mallocLong(1);
            int err = VK10.vkCreateCommandPool(device, poolInfo, null, pPool);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateCommandPool (swapchain readback) failed: " + err);
            }
            readbackPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(readbackPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            err = VK10.vkAllocateCommandBuffers(device, allocInfo, pCmd);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkAllocateCommandBuffers (swapchain readback) failed: " + err);
            }
            readbackCmd = pCmd.get(0);
        }

        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
        LongBuffer pFence = memAllocLong(1);
        int err = VK10.vkCreateFence(device, fenceInfo, null, pFence);
        if (err != VK_SUCCESS) {
            throw new IllegalStateException("Caesium: vkCreateFence (swapchain readback) failed: " + err);
        }
        readbackFence = pFence.get(0);
        memFree(pFence);
        fenceInfo.free();
    }

    @Override
    public GpuPipeline pipeline() {
        return pipelineWrapper;
    }

    @Override
    public GpuPipeline pipeline(GpuCommandEncoder.VertexLayout layout) {
        if (layout == GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F) {
            if (terrainPipeline == 0L) {
                terrainPipeline = VulkanPipelineFactory.createForLayout(device, renderPass,
                        pipelineLayout, setLayout, width, height,
                        GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F);
                if (terrainPipeline == 0L) {
                    throw new IllegalStateException("Caesium: terrain pipeline creation failed");
                }
            }
            return new Pipeline(terrainPipeline);
        }
        return pipeline();
    }

    @Override
    public long pipelineLayout() {
        return pipelineLayout;
    }

    @Override
    public long descriptorSet() {
        return descriptorSet;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    int imageCount() {
        return imageHandles.length;
    }

    int format() {
        return format;
    }

    /** True when the channel memory layout is B-G-R-A rather than R-G-B-A. */
    private static boolean isBgr(int fmt) {
        return fmt == VK10.VK_FORMAT_B8G8R8A8_UNORM
                || fmt == VK10.VK_FORMAT_B8G8R8A8_SRGB
                || fmt == VK10.VK_FORMAT_B8G8R8_UNORM
                || fmt == VK10.VK_FORMAT_B8G8R8_SRGB;
    }

    int currentImageIndex() {
        return currentImageIndex;
    }

    long acquireSemaphore(int frameIndex) {
        return acquireSemaphores[frameIndex];
    }

    long renderSemaphore(int frameIndex) {
        return renderSemaphores[frameIndex];
    }

    /** Waits for the next presentable image on the frame's acquire semaphore. */
    int acquire(int frameIndex) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pIndex = stack.mallocInt(1);
            int err = vkAcquireNextImageKHR(device, swapchain, UINT64_MAX,
                    acquireSemaphores[frameIndex], 0L, pIndex);
            if (err == VK_ERROR_OUT_OF_DATE_KHR) {
                return -1;
            }
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkAcquireNextImageKHR failed: " + err);
            }
            currentImageIndex = pIndex.get(0);
            return currentImageIndex;
        }
    }

    @Override
    public void beginRenderPass(long cmdBuffer) {
        try (MemoryStack stack = stackPush()) {
            VkClearValue.Buffer clear = VkClearValue.callocStack(1, stack);
            clear.get(0).color().float32(0, 1.0f).float32(1, 0.0f)
                    .float32(2, 0.0f).float32(3, 1.0f);
            VkRenderPassBeginInfo info = VkRenderPassBeginInfo.callocStack(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffers[currentImageIndex])
                    .renderArea(it -> it
                            .offset(o -> o.x(0).y(0))
                            .extent(e -> e.width(width).height(height)))
                    .pClearValues(clear);
            VK10.vkCmdBeginRenderPass(new VkCommandBuffer(cmdBuffer, device), info,
                    VK_SUBPASS_CONTENTS_INLINE);
        }
    }

    @Override
    public void endRenderPass(long cmdBuffer) {
        VK10.vkCmdEndRenderPass(new VkCommandBuffer(cmdBuffer, device));
    }

    /** Marks the next frame for pixel capture before present (used by the headless test). */
    void requestCapture() {
        captureNext = true;
    }

    /**
     * Copies the just-rendered swapchain image into the readback buffer, before present.
     * Must be called after the frame's submit and before {@code present()}. The image is
     * left in PRESENT_SRC so present can still submit it.
     */
    void capture(long cmdBuffer, long fence) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.callocStack(1, stack);
            barrier.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(imageHandles[currentImageIndex])
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    .subresourceRange(it -> it
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1));
            VK10.vkCmdPipelineBarrier(new VkCommandBuffer(cmdBuffer, device),
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.callocStack(1, stack);
            region.get(0)
                    .bufferOffset(0L)
                    .bufferRowLength(0)
                    .bufferImageHeight(0)
                    .imageSubresource(it -> it
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0)
                            .baseArrayLayer(0)
                            .layerCount(1))
                    .imageOffset(o -> o.x(0).y(0).z(0))
                    .imageExtent(e -> e.width(width).height(height).depth(1));
            VK10.vkCmdCopyImageToBuffer(new VkCommandBuffer(cmdBuffer, device),
                    imageHandles[currentImageIndex], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    readbackBuffer, region);

            barrier.get(0)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                    .srcAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    .dstAccessMask(0);
            VK10.vkCmdPipelineBarrier(new VkCommandBuffer(cmdBuffer, device),
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, 0, null, null, barrier);
        }
    }

    /** Submits the frame's command buffer with acquire/render semaphore sync. */
    void submitFrame(long cmdBuffer, long fence, int frameIndex) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pWait = stack.mallocLong(1).put(0, acquireSemaphores[frameIndex]);
            IntBuffer waitStage = stack.mallocInt(1).put(0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            LongBuffer pSignal = stack.mallocLong(1).put(0, renderSemaphores[frameIndex]);
            PointerBuffer pCmd = stack.mallocPointer(1).put(0, cmdBuffer);
            VkSubmitInfo submit = VkSubmitInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pWaitSemaphores(pWait)
                    .pWaitDstStageMask(waitStage)
                    .pCommandBuffers(pCmd)
                    .pSignalSemaphores(pSignal);
            int err = VK10.vkQueueSubmit(backend.graphicsVkQueue(), submit, fence);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkQueueSubmit (swapchain) failed: " + err);
            }
        }
    }

    /** Presents the current image, waiting on the render semaphore. */
    void present(int frameIndex) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pWait = stack.mallocLong(1).put(0, renderSemaphores[frameIndex]);
            LongBuffer pSwapchain = stack.mallocLong(1).put(0, swapchain);
            IntBuffer pIndex = stack.mallocInt(1).put(0, currentImageIndex);
            VkPresentInfoKHR present = VkPresentInfoKHR.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                    .pWaitSemaphores(pWait)
                    .pSwapchains(pSwapchain)
                    .pImageIndices(pIndex);
            int err = vkQueuePresentKHR(backend.graphicsVkQueue(), present);
            if (err == VK_ERROR_OUT_OF_DATE_KHR || err == VK_SUBOPTIMAL_KHR) {
                return; // resize path arrives with graph recompilation (ARCHITECTURE.md §10.2)
            }
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkQueuePresentKHR failed: " + err);
            }
        }
    }

    int readBackPixel(int x, int y) {
        if (captureNext) {
            VK10.vkDeviceWaitIdle(device);
            try (MemoryStack stack = stackPush()) {
                VkCommandBuffer cmd = new VkCommandBuffer(readbackCmd, device);
                VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                VK10.vkBeginCommandBuffer(cmd, begin);
                capture(readbackCmd, readbackFence);
                VK10.vkEndCommandBuffer(cmd);

                PointerBuffer pCmd = stack.mallocPointer(1).put(0, readbackCmd);
                VkSubmitInfo submit = VkSubmitInfo.callocStack(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(pCmd);
                int err = VK10.vkQueueSubmit(backend.graphicsVkQueue(), submit, readbackFence);
                if (err != VK_SUCCESS) {
                    throw new IllegalStateException("Caesium: readback submit failed: " + err);
                }
                LongBuffer pFence = stack.mallocLong(1).put(0, readbackFence);
                VK10.vkWaitForFences(device, pFence, true, Long.MAX_VALUE);
                VK10.vkResetFences(device, pFence);
            }
            captureNext = false;
        }

        try (MemoryStack stack = stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            VK10.vkMapMemory(device, readbackBufferMemory, 0L, (long) width * height * 4, 0, pp);
            long mapped = pp.get(0);
            if (mapped == 0L) {
                return 0xFFFF00FF;
            }
            int offset = (y * width + x) * 4;
            int b0 = memGetByte(mapped + offset) & 0xff;
            int b1 = memGetByte(mapped + offset + 1) & 0xff;
            int b2 = memGetByte(mapped + offset + 2) & 0xff;
            int a = memGetByte(mapped + offset + 3) & 0xff;
            VK10.vkUnmapMemory(device, readbackBufferMemory);
            // Swapchain surfaces are commonly B8G8R8A8; interpret by the chosen format so
            // the returned ARGB always has the true red in the high byte regardless of layout.
            if (isBgr(format)) {
                return (b2 << 24) | (b1 << 16) | (b0 << 8) | a;
            }
            return (b0 << 24) | (b1 << 16) | (b2 << 8) | a;
        }
    }

    void destroy() {
        if (device == null) {
            return;
        }
        if (readbackFence != 0L) {
            VK10.vkDestroyFence(device, readbackFence, null);
            readbackFence = 0L;
        }
        if (readbackPool != 0L) {
            VK10.vkDestroyCommandPool(device, readbackPool, null);
            readbackPool = 0L;
        }
        if (readbackBuffer != 0L) {
            VK10.vkDestroyBuffer(device, readbackBuffer, null);
            VK10.vkFreeMemory(device, readbackBufferMemory, null);
            readbackBuffer = 0L;
        }
        for (long sem : acquireSemaphores != null ? acquireSemaphores : new long[0]) {
            if (sem != 0L) {
                VK10.vkDestroySemaphore(device, sem, null);
            }
        }
        for (long sem : renderSemaphores != null ? renderSemaphores : new long[0]) {
            if (sem != 0L) {
                VK10.vkDestroySemaphore(device, sem, null);
            }
        }
        if (pipeline != 0L) {
            VK10.vkDestroyPipeline(device, pipeline, null);
            pipeline = 0L;
        }
        if (terrainPipeline != 0L) {
            VK10.vkDestroyPipeline(device, terrainPipeline, null);
            terrainPipeline = 0L;
        }
        if (pipelineLayout != 0L) {
            VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            pipelineLayout = 0L;
        }
        VulkanUniforms.destroyBuffer(device, identityUbo, identityUboMemory);
        identityUbo = 0L;
        identityUboMemory = 0L;
        VulkanUniforms.destroyPool(device, descriptorPool);
        descriptorPool = 0L;
        descriptorSet = 0L;
        if (setLayout != 0L) {
            VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
            setLayout = 0L;
        }
        for (long fb : framebuffers != null ? framebuffers : new long[0]) {
            if (fb != 0L) {
                VK10.vkDestroyFramebuffer(device, fb, null);
            }
        }
        for (long view : imageViews != null ? imageViews : new long[0]) {
            if (view != 0L) {
                VK10.vkDestroyImageView(device, view, null);
            }
        }
        if (renderPass != 0L) {
            VK10.vkDestroyRenderPass(device, renderPass, null);
            renderPass = 0L;
        }
        if (swapchain != 0L) {
            vkDestroySwapchainKHR(device, swapchain, null);
            swapchain = 0L;
        }
        if (surface != 0L) {
            vkDestroySurfaceKHR(backend.instance(), surface, null);
            surface = 0L;
        }
    }

    private static final class Pipeline implements GpuPipeline {
        private final long handle;

        Pipeline(long handle) {
            this.handle = handle;
        }

        @Override
        public long handle() {
            return handle;
        }

        @Override
        public void destroy() {
            // Owned and destroyed by SwapchainTarget.destroy().
        }
    }
}
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
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memAllocLong;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.system.MemoryUtil.memGetByte;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_LEVEL_PRIMARY;
import static org.lwjgl.vulkan.VK10.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SUBMIT_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_CONTENTS_INLINE;
import static org.lwjgl.vulkan.VK10.VK_SUBPASS_EXTERNAL;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;

/**
 * The offscreen color target the engine renders into at this milestone: an RGBA8 image,
 * a matching framebuffer, and the graphics pipeline built from the shader-slot GLSL
 * compiled to SPIR-V by {@link VulkanShaderCompiler}. Exposes {@link #beginRenderPass} /
 * {@link #endRenderPass} (wrapped by the backend's encoder) and {@link #readBackPixel}
 * for the headless correctness test (ARCHITECTURE.md §10.2).
 *
 * <p>Zero Minecraft imports — like every {@code caesium.engine.*} type.
 */
final class OffscreenTarget implements RenderTarget {

    private final VulkanBackend backend;
    private final VkDevice device;
    private final int width;
    private final int height;

    private long renderPass;
    private long colorImage;
    private long colorImageMemory;
    private long colorImageView;
    private long framebuffer;
    private long pipelineLayout;
    private long pipeline;
    private long terrainPipeline;
    private long descriptorPool;
    private long descriptorSet;
    private long setLayout;
    private long identityUbo;
    private long identityUboMemory;

    private long readbackBuffer;
    private long readbackBufferMemory;
    private long readbackPool;
    private long readbackCmd;
    private long readbackFence;

    private GpuPipeline pipelineWrapper;

    OffscreenTarget(VulkanBackend backend, VkDevice device, int width, int height) {
        this.backend = backend;
        this.device = device;
        this.width = width;
        this.height = height;
    }

    void initialize() {
        createRenderPass();
        createColorImage();
        createFramebuffer();
        createPipeline();
        createDescriptorResources();
        createReadbackResources();
        pipelineWrapper = new Pipeline(pipeline);
    }

    private void createRenderPass() {
        try (MemoryStack stack = stackPush()) {
            VkAttachmentDescription.Buffer attachments =
                    VkAttachmentDescription.callocStack(1, stack);
            attachments.get(0)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.callocStack(1, stack);
            colorRef.get(0)
                    .attachment(0)
                    .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

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
                throw new IllegalStateException("Caesium: vkCreateRenderPass failed: " + err);
            }
            renderPass = pRenderPass.get(0);
        }
    }

    private void createColorImage() {
        try (MemoryStack stack = stackPush()) {
            org.lwjgl.vulkan.VkImageCreateInfo info =
                    org.lwjgl.vulkan.VkImageCreateInfo.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                            .imageType(VK_IMAGE_TYPE_2D)
                            .format(VK_FORMAT_R8G8B8A8_UNORM)
                            .extent(it -> it.width(width).height(height).depth(1))
                            .mipLevels(1)
                            .arrayLayers(1)
                            .samples(VK_SAMPLE_COUNT_1_BIT)
                            .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                            .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            LongBuffer pImage = stack.mallocLong(1);
            int err = VK10.vkCreateImage(device, info, null, pImage);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateImage (target) failed: " + err);
            }
            colorImage = pImage.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.callocStack(stack);
            VK10.vkGetImageMemoryRequirements(device, colorImage, req);
            int typeIndex = backend.findMemoryType(req.memoryTypeBits(), 0);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(typeIndex);
            LongBuffer pMem = stack.mallocLong(1);
            err = VK10.vkAllocateMemory(device, alloc, null, pMem);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkAllocateMemory (target) failed: " + err);
            }
            colorImageMemory = pMem.get(0);
            VK10.vkBindImageMemory(device, colorImage, colorImageMemory, 0L);
        }

        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(colorImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM)
                    .subresourceRange(it -> it
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1));
            LongBuffer pView = stack.mallocLong(1);
            int err = VK10.vkCreateImageView(device, info, null, pView);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateImageView (target) failed: " + err);
            }
            colorImageView = pView.get(0);
        }
    }

    private void createFramebuffer() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pAttachments = stack.mallocLong(1).put(0, colorImageView);
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
                throw new IllegalStateException("Caesium: vkCreateFramebuffer failed: " + err);
            }
            framebuffer = pFramebuffer.get(0);
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
                throw new IllegalStateException("Caesium: vkCreateBuffer (readback) failed: " + err);
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
                throw new IllegalStateException("Caesium: vkAllocateMemory (readback) failed: " + err);
            }
            readbackBufferMemory = pMem.get(0);
            VK10.vkBindBufferMemory(device, readbackBuffer, readbackBufferMemory, 0L);
        }

        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK10.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(0);
            LongBuffer pPool = stack.mallocLong(1);
            int err = VK10.vkCreateCommandPool(device, poolInfo, null, pPool);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateCommandPool (readback) failed: " + err);
            }
            readbackPool = pPool.get(0);

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(readbackPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            org.lwjgl.PointerBuffer pCmd = stack.mallocPointer(1);
            err = VK10.vkAllocateCommandBuffers(device, allocInfo, pCmd);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkAllocateCommandBuffers (readback) failed: " + err);
            }
            readbackCmd = pCmd.get(0);
        }

        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
        LongBuffer pFence = memAllocLong(1);
        int err = VK10.vkCreateFence(device, fenceInfo, null, pFence);
        if (err != VK_SUCCESS) {
            throw new IllegalStateException("Caesium: vkCreateFence (readback) failed: " + err);
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
    public void beginRenderPass(long cmdBuffer) {
        try (MemoryStack stack = stackPush()) {
            VkClearValue.Buffer clear = VkClearValue.callocStack(1, stack);
            clear.get(0).color().float32(0, 1.0f).float32(1, 0.0f)
                    .float32(2, 0.0f).float32(3, 1.0f);
            VkRenderPassBeginInfo info = VkRenderPassBeginInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffer)
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

    /**
     * Copies the target into the host-visible readback buffer and returns one RGBA pixel.
     * Caller must have made the GPU idle. Rows are tightly packed (RGBA8), so byte offset
     * is {@code (y * width + x) * 4}. Returns packed ARGB int.
     */
    int readBackPixel(int x, int y) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer cmd = new VkCommandBuffer(readbackCmd, device);
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            VK10.vkBeginCommandBuffer(cmd, begin);

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.callocStack(1, stack);
            barrier.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(colorImage)
                    .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT)
                    .subresourceRange(it -> it
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1));
            VK10.vkCmdPipelineBarrier(cmd,
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
            VK10.vkCmdCopyImageToBuffer(cmd, colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    readbackBuffer, region);
            VK10.vkEndCommandBuffer(cmd);

            org.lwjgl.PointerBuffer pCmd = stack.mallocPointer(1).put(0, readbackCmd);
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

        try (MemoryStack stack = stackPush()) {
            org.lwjgl.PointerBuffer pp = stack.mallocPointer(1);
            VK10.vkMapMemory(device, readbackBufferMemory, 0L, (long) width * height * 4, 0, pp);
            long mapped = pp.get(0);
            if (mapped == 0L) {
                return 0xFFFF00FF; // magenta sentinel: readback unmapped
            }
            int offset = (y * width + x) * 4;
            int r = memGetByte(mapped + offset) & 0xff;
            int g = memGetByte(mapped + offset + 1) & 0xff;
            int b = memGetByte(mapped + offset + 2) & 0xff;
            int a = memGetByte(mapped + offset + 3) & 0xff;
            VK10.vkUnmapMemory(device, readbackBufferMemory);
            return (r << 24) | (g << 16) | (b << 8) | a;
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
        if (framebuffer != 0L) {
            VK10.vkDestroyFramebuffer(device, framebuffer, null);
            framebuffer = 0L;
        }
        if (colorImageView != 0L) {
            VK10.vkDestroyImageView(device, colorImageView, null);
            colorImageView = 0L;
        }
        if (colorImage != 0L) {
            VK10.vkDestroyImage(device, colorImage, null);
            VK10.vkFreeMemory(device, colorImageMemory, null);
            colorImage = 0L;
        }
        if (renderPass != 0L) {
            VK10.vkDestroyRenderPass(device, renderPass, null);
            renderPass = 0L;
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
            // Owned and destroyed by OffscreenTarget.destroy().
        }
    }
}
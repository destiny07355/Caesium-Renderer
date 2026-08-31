package caesium.engine.backend.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * Shared descriptor resources for the engine's single UBO binding (set 0 / binding 0).
 * Every {@link RenderTarget} owns one descriptor pool, one descriptor set, and a
 * 16-byte identity UBO (a white tint) so the pipeline draws correctly before the engine
 * binds real per-frame data. The encoder updates the same set to point at the engine's
 * buffer each frame — no per-frame allocation (ARCHITECTURE.md §16).
 */
final class VulkanUniforms {

    static final int UNIFORM_BYTES = 80; // mat4 uMVP (64) + vec4 uTint (16)

    private VulkanUniforms() {
    }

    /** Creates the descriptor pool: one UBO descriptor, one set. Returns the pool. */
    static long createDescriptorPool(VkDevice device) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.callocStack(1, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            VkDescriptorPoolCreateInfo info = VkDescriptorPoolCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                    .maxSets(1)
                    .pPoolSizes(sizes);
            LongBuffer pPool = stack.mallocLong(1);
            int err = VK10.vkCreateDescriptorPool(device, info, null, pPool);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateDescriptorPool failed: " + err);
            }
            return pPool.get(0);
        }
    }

    /** Allocates one descriptor set from {@code pool} against the pipeline's set layout. */
    static long allocateDescriptorSet(VkDevice device, long pool, long setLayout) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pLayouts = stack.mallocLong(1).put(0, setLayout);
            VkDescriptorSetAllocateInfo info = VkDescriptorSetAllocateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(pool)
                    .pSetLayouts(pLayouts);
            LongBuffer pSet = stack.mallocLong(1);
            int err = VK10.vkAllocateDescriptorSets(device, info, pSet);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkAllocateDescriptorSets failed: " + err);
            }
            return pSet.get(0);
        }
    }

    /** Creates an 80-byte host-visible UBO filled with identity MVP + white tint. */
    static long[] createIdentityUbo(VkDevice device, VulkanBackend backend) {
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(UNIFORM_BYTES)
                    .usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            int err = VK10.vkCreateBuffer(device, info, null, pBuffer);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateBuffer (identity UBO) failed: " + err);
            }
            long buffer = pBuffer.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.callocStack(stack);
            VK10.vkGetBufferMemoryRequirements(device, buffer, req);
            int typeIndex = backend.findMemoryType(req.memoryTypeBits(),
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.callocStack(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(req.size())
                    .memoryTypeIndex(typeIndex);
            LongBuffer pMem = stack.mallocLong(1);
            err = VK10.vkAllocateMemory(device, alloc, null, pMem);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkAllocateMemory (identity UBO) failed: " + err);
            }
            long memory = pMem.get(0);
            VK10.vkBindBufferMemory(device, buffer, memory, 0L);

            try (MemoryStack s2 = stackPush()) {
                PointerBuffer pp = s2.mallocPointer(1);
                VK10.vkMapMemory(device, memory, 0L, UNIFORM_BYTES, 0, pp);
                long mapped = pp.get(0);
                if (mapped != 0L) {
                    for (int i = 0; i < 16; i++) {
                        float v = (i % 5 == 0) ? 1.0f : 0.0f; // identity mat4, column-major
                        org.lwjgl.system.MemoryUtil.memPutFloat(mapped + 4L * i, v);
                    }
                    for (int i = 0; i < 4; i++) {
                        org.lwjgl.system.MemoryUtil.memPutFloat(mapped + 64L + 4L * i, 1.0f);
                    }
                }
                VK10.vkUnmapMemory(device, memory);
            }
            return new long[]{buffer, memory};
        }
    }

    /** Points the descriptor set at {@code buffer} (set 0 / binding 0, UBO). */
    static void writeSetToBuffer(VkDevice device, long set, long buffer, long size) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.callocStack(1, stack);
            bufferInfo.get(0).buffer(buffer).offset(0L).range(size);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.callocStack(1, stack);
            writes.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set)
                    .dstBinding(0)
                    .dstArrayElement(0)
                    .descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(bufferInfo);
            VK10.vkUpdateDescriptorSets(device, writes, null);
        }
    }

    /** Frees the pool (and its sets). */
    static void destroyPool(VkDevice device, long pool) {
        if (pool != 0L) {
            VK10.vkDestroyDescriptorPool(device, pool, null);
        }
    }

    static void destroyBuffer(VkDevice device, long buffer, long memory) {
        if (buffer != 0L) {
            VK10.vkDestroyBuffer(device, buffer, null);
            VK10.vkFreeMemory(device, memory, null);
        }
    }
}
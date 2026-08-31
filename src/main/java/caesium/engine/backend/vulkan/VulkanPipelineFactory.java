package caesium.engine.backend.vulkan;

import caesium.engine.backend.GpuCommandEncoder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
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
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkViewport;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;

/**
 * Builds the single graphics pipeline the engine uses at this milestone (the shader-slot
 * POS_COLOR quad pipeline) against a given render pass. Both the offscreen correctness
 * target and the window swapchain create their own pipeline through this helper — the
 * pipeline's render pass must match the attachment format it renders into, and the two
 * targets use different formats. Shader sources are the same GLSL the OpenGL backend
 * consumes (ARCHITECTURE.md §12), compiled to SPIR-V by {@link VulkanShaderCompiler}.
 */
final class VulkanPipelineFactory {

    private static final String VERT_SRC = """
            #version 450
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec4 aColor;
            layout(std140, set = 0, binding = 0) uniform Uniforms {
                mat4 uMVP;
                vec4 uTint;
            };
            layout(location = 0) out vec4 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
            }
            """;

    /** 3D terrain vertex shader: vec3 position + vec4 color, transformed by the camera MVP. */
    private static final String TERRAIN_VERT_SRC = """
            #version 450
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec4 aColor;
            layout(std140, set = 0, binding = 0) uniform Uniforms {
                mat4 uMVP;
                vec4 uTint;
            };
            layout(location = 0) out vec4 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
            """;

    private static final String FRAG_SRC = """
            #version 450
            layout(std140, set = 0, binding = 0) uniform Uniforms {
                mat4 uMVP;
                vec4 uTint;
            };
            layout(location = 0) in vec4 vColor;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = vColor * uTint;
            }
            """;

    private VulkanPipelineFactory() {
    }

    private static String vertexShader(GpuCommandEncoder.VertexLayout layout) {
        return layout == GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F
                ? TERRAIN_VERT_SRC : VERT_SRC;
    }

    private static int cullMode(GpuCommandEncoder.VertexLayout layout) {
        // Back-face culling for solid terrain; the debug quad keeps both faces visible.
        return layout == GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F
                ? org.lwjgl.vulkan.VK10.VK_CULL_MODE_BACK_BIT
                : VK_CULL_MODE_NONE;
    }

    /**
     * @return the pipeline handle; the caller owns the pipeline layout (returned in
     * {@code outLayout}) and the descriptor set layout (returned in {@code outSetLayout}).
     */
    static long create(VkDevice device, long renderPass, int width, int height,
                       long[] outLayout, long[] outSetLayout) {
        return create(device, renderPass, width, height, outLayout, outSetLayout,
                GpuCommandEncoder.VertexLayout.POS_COLOR_2F_4F);
    }

    /** Layout-aware variant of {@link #create(VkDevice, long, int, int, long[], long[])}. */
    static long create(VkDevice device, long renderPass, int width, int height,
                       long[] outLayout, long[] outSetLayout,
                       GpuCommandEncoder.VertexLayout vertexLayout) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer setBindings =
                    VkDescriptorSetLayoutBinding.callocStack(1, stack);
            setBindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                    .pImmutableSamplers(null);
            VkDescriptorSetLayoutCreateInfo setLayoutInfo =
                    VkDescriptorSetLayoutCreateInfo.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                            .pBindings(setBindings);
            LongBuffer pSetLayout = stack.mallocLong(1);
            int err = VK10.vkCreateDescriptorSetLayout(device, setLayoutInfo, null, pSetLayout);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateDescriptorSetLayout failed: " + err);
            }
            long setLayout = pSetLayout.get(0);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pSetLayout);
            LongBuffer pLayout = stack.mallocLong(1);
            err = VK10.vkCreatePipelineLayout(device, layoutInfo, null, pLayout);
            if (err != VK_SUCCESS) {
                VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
                throw new IllegalStateException("Caesium: vkCreatePipelineLayout failed: " + err);
            }
            long pipelineLayout = pLayout.get(0);

            long pipeline = buildPipeline(device, renderPass, pipelineLayout, setLayout,
                    width, height, vertexLayout);
            if (pipeline == 0L) {
                VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
                VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
                throw new IllegalStateException("Caesium: vkCreateGraphicsPipelines failed");
            }

            outLayout[0] = pipelineLayout;
            outSetLayout[0] = setLayout;
            return pipeline;
        }
    }

    /**
     * Builds an additional graphics pipeline for a target that already owns a pipeline
     * layout and descriptor set layout (the UBO binding is shared across pipelines).
     * Returns 0 on failure so callers can report it against their own context.
     */
    static long createForLayout(VkDevice device, long renderPass, long pipelineLayout,
                                long setLayout, int width, int height,
                                GpuCommandEncoder.VertexLayout vertexLayout) {
        return buildPipeline(device, renderPass, pipelineLayout, setLayout,
                width, height, vertexLayout);
    }

    private static long buildPipeline(VkDevice device, long renderPass, long pipelineLayout,
                                      long setLayout, int width, int height,
                                      GpuCommandEncoder.VertexLayout vertexLayout) {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer vertSpv = VulkanShaderCompiler.compile(
                    "caesium.vert", vertexShader(vertexLayout), VulkanShaderCompiler.VERTEX);
            ByteBuffer fragSpv = VulkanShaderCompiler.compile(
                    "caesium.frag", FRAG_SRC, VulkanShaderCompiler.FRAGMENT);

            VkShaderModuleCreateInfo vertModuleInfo = VkShaderModuleCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(vertSpv);
            VkShaderModuleCreateInfo fragModuleInfo = VkShaderModuleCreateInfo.callocStack(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(fragSpv);
            LongBuffer pVert = stack.mallocLong(1);
            LongBuffer pFrag = stack.mallocLong(1);
            int err = VK10.vkCreateShaderModule(device, vertModuleInfo, null, pVert);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateShaderModule (vert) failed: " + err);
            }
            err = VK10.vkCreateShaderModule(device, fragModuleInfo, null, pFrag);
            if (err != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateShaderModule (frag) failed: " + err);
            }
            long vertModule = pVert.get(0);
            long fragModule = pFrag.get(0);

            VkPipelineShaderStageCreateInfo.Buffer stages =
                    VkPipelineShaderStageCreateInfo.callocStack(2, stack);
            stages.get(0)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertModule)
                    .pName(stack.ASCII("main"));
            stages.get(1)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragModule)
                    .pName(stack.ASCII("main"));

            // Vertex input depends on the layout: 2F_4F (stride 24) or 3F_4F (stride 28).
            VkVertexInputBindingDescription.Buffer bindings =
                    VkVertexInputBindingDescription.callocStack(1, stack);
            VkVertexInputAttributeDescription.Buffer attributes;
            if (vertexLayout == GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F) {
                bindings.get(0).binding(0).stride(28).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                attributes = VkVertexInputAttributeDescription.callocStack(2, stack);
                attributes.get(0)
                        .location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attributes.get(1)
                        .location(1).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(12);
            } else {
                bindings.get(0).binding(0).stride(24).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                attributes = VkVertexInputAttributeDescription.callocStack(2, stack);
                attributes.get(0)
                        .location(0).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
                attributes.get(1)
                        .location(1).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(8);
            }
            VkPipelineVertexInputStateCreateInfo vertexInput =
                    VkPipelineVertexInputStateCreateInfo.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                            .pVertexBindingDescriptions(bindings)
                            .pVertexAttributeDescriptions(attributes);

            VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                    VkPipelineInputAssemblyStateCreateInfo.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                            .primitiveRestartEnable(false);

            VkViewport.Buffer viewports = VkViewport.callocStack(1, stack);
            viewports.get(0).x(0).y(0).width(width).height(height).minDepth(0).maxDepth(1);
            VkRect2D.Buffer scissors = VkRect2D.callocStack(1, stack);
            scissors.get(0)
                    .offset(VkOffset2D.callocStack(stack).set(0, 0))
                    .extent(VkExtent2D.callocStack(stack).set(width, height));
            VkPipelineViewportStateCreateInfo viewportState =
                    VkPipelineViewportStateCreateInfo.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                            .pViewports(viewports)
                            .pScissors(scissors);

            VkPipelineRasterizationStateCreateInfo raster =
                    VkPipelineRasterizationStateCreateInfo.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                            .depthClampEnable(false)
                            .rasterizerDiscardEnable(false)
                            .polygonMode(VK_POLYGON_MODE_FILL)
                            .cullMode(cullMode(vertexLayout))
                            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                            .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample =
                    VkPipelineMultisampleStateCreateInfo.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                            .sampleShadingEnable(false);

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.callocStack(1, stack);
            blendAttachment.get(0)
                    .blendEnable(false)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT
                            | VK_COLOR_COMPONENT_G_BIT
                            | VK_COLOR_COMPONENT_B_BIT
                            | VK_COLOR_COMPONENT_A_BIT);
            VkPipelineColorBlendStateCreateInfo blend =
                    VkPipelineColorBlendStateCreateInfo.callocStack(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                            .logicOpEnable(false)
                            .pAttachments(blendAttachment);

            VkGraphicsPipelineCreateInfo.Buffer create =
                    VkGraphicsPipelineCreateInfo.callocStack(1, stack);
            create.get(0)
                    .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisample)
                    .pColorBlendState(blend)
                    .layout(pipelineLayout)
                    .renderPass(renderPass)
                    .subpass(0);

            LongBuffer pPipeline = stack.mallocLong(1);
            int pipelineErr = VK10.vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, create, null, pPipeline);
            if (pipelineErr != VK_SUCCESS) {
                throw new IllegalStateException("Caesium: vkCreateGraphicsPipelines failed: " + pipelineErr);
            }

            VK10.vkDestroyShaderModule(device, vertModule, null);
            VK10.vkDestroyShaderModule(device, fragModule, null);

            return pPipeline.get(0);
        }
    }
}
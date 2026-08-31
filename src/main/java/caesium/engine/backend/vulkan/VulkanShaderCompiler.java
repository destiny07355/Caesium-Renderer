package caesium.engine.backend.vulkan;

import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

/**
 * Compiles GLSL to SPIR-V at runtime via LWJGL's shaderc bindings. Both backends share one
 * logical GLSL source (ARCHITECTURE.md §12); the Vulkan backend needs SPIR-V modules, so
 * the shader slot compiles them here. The OpenGL backend consumes the same GLSL directly.
 *
 * <p>Thread-safety: shaderc's compiler handle is used for one compile at a time here; the
 * caller owns synchronization.
 */
final class VulkanShaderCompiler {

    private VulkanShaderCompiler() {
    }

    /** Compiles a single GLSL stage to a SPIR-V binary. */
    static ByteBuffer compile(String name, String source, int stageKind) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("Caesium: shaderc failed to initialize");
        }
        long result;
        try {
            result = Shaderc.shaderc_compile_into_spv(compiler, source, stageKind,
                    name, "main", 0L);
        } finally {
            Shaderc.shaderc_compiler_release(compiler);
        }
        try {
            int status = Shaderc.shaderc_result_get_compilation_status(result);
            if (status != Shaderc.shaderc_compilation_status_success) {
                String message = Shaderc.shaderc_result_get_error_message(result);
                throw new IllegalStateException(
                    "Caesium: failed to compile " + name + " to SPIR-V (" + status + "): " + message);
            }
            ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
            ByteBuffer copy = org.lwjgl.BufferUtils.createByteBuffer(spv.remaining());
            copy.put(spv);
            copy.flip();
            return copy;
        } finally {
            Shaderc.shaderc_result_release(result);
        }
    }

    static final int VERTEX = Shaderc.shaderc_glsl_vertex_shader;
    static final int FRAGMENT = Shaderc.shaderc_glsl_fragment_shader;
}
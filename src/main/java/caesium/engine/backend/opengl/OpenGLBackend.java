package caesium.engine.backend.opengl;

import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.GpuBuffer;
import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.backend.GpuImage;
import caesium.engine.backend.GpuMemoryAllocator;
import caesium.engine.backend.GpuPipeline;
import caesium.engine.backend.GpuQueue;
import caesium.engine.backend.GpuSync;
import caesium.engine.backend.GpuTimer;
import org.lwjgl.opengl.GL33;

/**
 * The OpenGL reference backend. It renders into the caller's current GL context (created
 * by the game/GLFW — this backend never opens a window) and executes commands
 * immediately, since OpenGL has no deferred command buffers. Slow but correct: it is the
 * pixel reference for the Vulkan backend and the compatibility fallback for drivers where
 * Vulkan is broken (ARCHITECTURE.md §10.3).
 *
 * <p>Requires OpenGL 3.3 core (matches the Intel UHD 630 contract) and a current GL
 * context at {@link #initialize()}. Mesh binding (VAO setup) belongs to the terrain
 * subsystem; this skeleton wires programs, buffers, textures, sync and the draw calls.
 */
public final class OpenGLBackend implements GpuBackend {

    private static final String TEST_VERT = """
            #version 330 core
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec4 aColor;
            layout(std140) uniform Uniforms {
                mat4 uMVP;
                vec4 uTint;
            };
            out vec4 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
            }
            """;

    private static final String TEST_FRAG = """
            #version 330 core
            layout(std140) uniform Uniforms {
                mat4 uMVP;
                vec4 uTint;
            };
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vColor * uTint;
            }
            """;

    /** 3D terrain geometry: vec3 position + vec4 color, transformed by the camera MVP. */
    private static final String TERRAIN_VERT = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec4 aColor;
            layout(std140) uniform Uniforms {
                mat4 uMVP;
                vec4 uTint;
            };
            out vec4 vColor;
            void main() {
                vColor = aColor;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
            """;

    private static final String TERRAIN_FRAG = TEST_FRAG;

    private static final String BAKED_TERRAIN_VERT = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUv;
            layout(location = 2) in vec4 aColor;
            layout(location = 3) in uint aLight;
            layout(location = 4) in vec4 aNormal;
            layout(std140) uniform Uniforms { mat4 uMVP; vec4 uTint; };
            out vec4 vColor;
            out vec2 vUv;
            out vec2 vLightUv;
            void main() {
                float blockLight = float(aLight & 15u);
                float skyLight = float((aLight >> 4u) & 15u);
                vColor = aColor;
                vUv = aUv;
                vLightUv = (vec2(blockLight, skyLight) * 16.0 + 8.0) / 256.0;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
            """;

    private static final String BAKED_TERRAIN_FRAG = """
            #version 330 core
            layout(std140) uniform Uniforms { mat4 uMVP; vec4 uTint; };
            uniform sampler2D uAtlas;
            uniform sampler2D uLightmap;
            in vec4 vColor;
            in vec2 vUv;
            in vec2 vLightUv;
            out vec4 fragColor;
            void main() {
                vec4 color = texture(uAtlas, vUv) * vColor * texture(uLightmap, vLightUv) * uTint;
                if (color.a < 0.1) discard;
                fragColor = color;
            }
            """;

    private final Queue queue = new Queue();
    private final Allocator allocator = new Allocator();
    private GpuPipeline defaultPipeline;
    private GpuPipeline terrainPipeline;
    private GpuPipeline bakedTerrainPipeline;
    private int frameIndex = -1;
    private int cachedViewportW = 854;
    private int cachedViewportH = 480;

    @Override
    public BackendType type() {
        return BackendType.OPENGL;
    }

    @Override
    public String name() {
        return "Caesium OpenGL reference backend";
    }

    private static boolean hasAttribBinding = false;
    private static boolean attribBindingChecked = false;

    @Override
    public void initialize() {
        if (!attribBindingChecked) {
            try {
                org.lwjgl.opengl.GLCapabilities caps = org.lwjgl.opengl.GL.getCapabilities();
                hasAttribBinding = caps.GL_ARB_vertex_attrib_binding || caps.OpenGL43;
            } catch (Throwable ignored) {
                hasAttribBinding = false;
            }
            attribBindingChecked = true;
        }
        // A VAO must be bound in core profile before any draw; create + bind one now so
        // draw calls from the skeleton never hit the "no VAO bound" error.
        int vao = GL33.glGenVertexArrays();
        GL33.glBindVertexArray(vao);
        defaultPipeline = new Pipeline(new OpenGLShaderProgram("caesium-test", TEST_VERT, TEST_FRAG));
    }

    @Override
    public void shutdown() {
        if (defaultPipeline != null) {
            defaultPipeline.destroy();
            defaultPipeline = null;
        }
        if (terrainPipeline != null) {
            terrainPipeline.destroy();
            terrainPipeline = null;
        }
        if (bakedTerrainPipeline != null) {
            bakedTerrainPipeline.destroy();
            bakedTerrainPipeline = null;
        }
    }

    /**
     * Controls vertical synchronization swap interval (0 = unconstrained/vsync off, 1 = vsync on).
     */
    public void setSwapInterval(int interval) {
        try {
            org.lwjgl.glfw.GLFW.glfwSwapInterval(interval);
        } catch (Throwable ignored) {}
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
        return allocator;
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
        if (defaultPipeline == null) {
            defaultPipeline = new Pipeline(new OpenGLShaderProgram("caesium-test", TEST_VERT, TEST_FRAG));
        }
        return defaultPipeline;
    }

    @Override
    public GpuPipeline createPipeline(GpuCommandEncoder.VertexLayout layout) {
        if (layout == GpuCommandEncoder.VertexLayout.TERRAIN_BAKED) {
            if (bakedTerrainPipeline == null) {
                bakedTerrainPipeline = new Pipeline(new OpenGLShaderProgram(
                        "caesium-baked-terrain", BAKED_TERRAIN_VERT, BAKED_TERRAIN_FRAG), layout);
            }
            return bakedTerrainPipeline;
        }
        if (layout == GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F) {
            if (terrainPipeline == null) {
                terrainPipeline = new Pipeline(
                        new OpenGLShaderProgram("caesium-terrain", TERRAIN_VERT, TERRAIN_FRAG), layout);
            }
            return terrainPipeline;
        }
        return createPipeline();
    }

    @Override
    public GpuTimer createTimer() {
        return new Timer();
    }

    @Override
    public int viewportWidth() {
        return cachedViewportW;
    }

    @Override
    public int viewportHeight() {
        return cachedViewportH;
    }

    public void setViewportSize(int w, int h) {
        cachedViewportW = w;
        cachedViewportH = h;
        GL33.glViewport(0, 0, w, h);
    }

    @Override
    public void beginFrame(int frameIndex) {
        this.frameIndex = frameIndex;
    }

    @Override
    public void endFrame(int frameIndex) {
        // Minecraft owns the present/swap; nothing to flush here at the skeleton stage.
    }

    /** Binds Minecraft's current terrain framebuffer while preserving every GL state we touch. */
    public ExternalTerrainState beginExternalTerrainPass(int framebuffer, int width, int height,
                                                          int atlasTexture, int lightmapTexture,
                                                          int atlasSampler, int lightmapSampler,
                                                          boolean translucent) {
        ExternalTerrainState state = ExternalTerrainState.capture();
        bindExternalTerrainPass(framebuffer, width, height, atlasTexture, lightmapTexture,
                atlasSampler, lightmapSampler, translucent);
        return state;
    }

    public void bindExternalTerrainPass(int framebuffer, int width, int height,
                                        int atlasTexture, int lightmapTexture,
                                        int atlasSampler, int lightmapSampler,
                                        boolean translucent) {
        GL33.glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL33.glViewport(0, 0, width, height);
        GL33.glEnable(GL33.GL_DEPTH_TEST);
        GL33.glDepthFunc(GL33.GL_LEQUAL);
        GL33.glDepthMask(!translucent);
        GL33.glEnable(GL33.GL_CULL_FACE);
        if (translucent) {
            GL33.glEnable(GL33.GL_BLEND);
            GL33.glBlendFuncSeparate(GL33.GL_SRC_ALPHA, GL33.GL_ONE_MINUS_SRC_ALPHA,
                    GL33.GL_ONE, GL33.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            GL33.glDisable(GL33.GL_BLEND);
        }
        GL33.glActiveTexture(GL33.GL_TEXTURE0);
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, atlasTexture);
        GL33.glBindSampler(0, atlasSampler);
        GL33.glActiveTexture(GL33.GL_TEXTURE2);
        GL33.glBindTexture(GL33.GL_TEXTURE_2D, lightmapTexture);
        GL33.glBindSampler(2, lightmapSampler);
    }

    public void endExternalTerrainPass(ExternalTerrainState state) {
        if (state != null) state.restore();
    }

    public int currentFrameIndex() {
        return frameIndex;
    }

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
            // OpenGL executes immediately; nothing to defer.
        }

        /**
         * Blocks until all submitted GPU commands complete via glFinish().
         * <p>WARNING: Must NEVER be called in hot-path rendering. Reserved strictly for
         * context destruction, device shutdown, or test harness verification.
         */
        @Override
        public void waitIdle() {
            GL33.glFinish();
        }
    }

    private static final class Encoder implements GpuCommandEncoder {
        private int boundProgram = 0;
        private int boundVao = 0;
        private int boundUbo = 0;
        private int boundVbo = 0;
        private VertexLayout boundLayout = null;

        @Override
        public void begin() {
            boundProgram = 0;
            boundVao = 0;
            boundUbo = 0;
            boundVbo = 0;
            boundLayout = null;
        }

        @Override
        public void bindPipeline(GpuPipeline pipeline) {
            if (pipeline instanceof Pipeline p) {
                int prog = p.program().program();
                if (boundProgram != prog) {
                    GL33.glUseProgram(prog);
                    boundProgram = prog;
                    int atlas = GL33.glGetUniformLocation(prog, "uAtlas");
                    if (atlas >= 0) GL33.glUniform1i(atlas, 0);
                    int lightmap = GL33.glGetUniformLocation(prog, "uLightmap");
                    if (lightmap >= 0) GL33.glUniform1i(lightmap, 2);
                }
                int vao = p.vao();
                if (boundVao != vao) {
                    GL33.glBindVertexArray(vao);
                    boundVao = vao;
                    boundVbo = 0;
                    boundLayout = null;
                }
                int ubo = p.defaultUbo();
                if (boundUbo != ubo) {
                    GL33.glBindBufferBase(GL33.GL_UNIFORM_BUFFER, 0, ubo);
                    boundUbo = ubo;
                }
            }
        }

        @Override
        public void writeBuffer(GpuBuffer buffer, int offset, java.nio.ByteBuffer data) {
            Buffer b = (Buffer) buffer;
            if (b.mapped != null) {
                java.nio.ByteBuffer destination = b.mapped.duplicate();
                destination.position(offset);
                destination.limit(offset + data.remaining());
                destination.put(data.duplicate());
                return;
            }
            int target = Buffer.bufferTarget(b.usage());
            GL33.glBindBuffer(target, b.id());
            GL33.glBufferSubData(target, (long) offset, data);
            GL33.glBindBuffer(target, 0);
        }

        @Override
        public void bindVertexBuffer(GpuBuffer buffer, VertexLayout layout) {
            Buffer b = (Buffer) buffer;
            int bufId = b.id();
            if (boundVbo == bufId && boundLayout == layout) {
                return; // Already bound with matching layout on active VAO
            }
            if (hasAttribBinding) {
                int stride = switch (layout) {
                    case POS_COLOR_2F_4F -> 24;
                    case POS_COLOR_3F_4F -> 28;
                    case TERRAIN_BAKED -> 32;
                };
                org.lwjgl.opengl.GL43.glBindVertexBuffer(0, bufId, 0L, stride);
            } else {
                GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, bufId);
                if (layout == VertexLayout.POS_COLOR_2F_4F) {
                    // Positions vec2 + colors vec4, tightly packed (stride 24 bytes).
                    GL33.glVertexAttribPointer(0, 2, GL33.GL_FLOAT, false, 24, 0L);
                    GL33.glVertexAttribPointer(1, 4, GL33.GL_FLOAT, false, 24, 8L);
                } else if (layout == VertexLayout.POS_COLOR_3F_4F) {
                    // Positions vec3 + colors vec4, tightly packed (stride 28 bytes).
                    GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false, 28, 0L);
                    GL33.glVertexAttribPointer(1, 4, GL33.GL_FLOAT, false, 28, 12L);
                } else if (layout == VertexLayout.TERRAIN_BAKED) {
                    GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false, 32, 0L);
                    GL33.glVertexAttribPointer(1, 2, GL33.GL_FLOAT, false, 32, 12L);
                    GL33.glVertexAttribPointer(2, 4, GL33.GL_UNSIGNED_BYTE, true, 32, 20L);
                    GL33.glVertexAttribIPointer(3, 1, GL33.GL_UNSIGNED_INT, 32, 24L);
                    GL33.glVertexAttribPointer(4, 4, GL33.GL_BYTE, true, 32, 28L);
                }
            }
            boundVbo = bufId;
            boundLayout = layout;
        }

        @Override
        public void draw(int vertexCount, int instanceCount) {
            GL33.glDrawArraysInstanced(GL33.GL_TRIANGLES, 0, vertexCount, instanceCount);
        }

        @Override
        public void drawIndexed(int indexCount, int instanceCount) {
            GL33.glDrawElementsInstanced(GL33.GL_TRIANGLES, indexCount,
                    GL33.GL_UNSIGNED_INT, 0L, instanceCount);
        }

        @Override
        public void drawIndexedIndirect(GpuBuffer commands, int offset, int drawCount, int stride) {
            if (!org.lwjgl.opengl.GL.getCapabilities().GL_ARB_multi_draw_indirect) {
                throw new UnsupportedOperationException("Multi-draw indirect is unavailable");
            }
            Buffer buffer = (Buffer) commands;
            org.lwjgl.opengl.GL43.glBindBuffer(org.lwjgl.opengl.GL43.GL_DRAW_INDIRECT_BUFFER, buffer.id());
            org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect(
                    GL33.GL_TRIANGLES, GL33.GL_UNSIGNED_INT, (long) offset, drawCount, stride);
            org.lwjgl.opengl.GL43.glBindBuffer(org.lwjgl.opengl.GL43.GL_DRAW_INDIRECT_BUFFER, 0);
        }

        @Override
        public void copyBuffer(GpuBuffer src, int srcOffset, GpuBuffer dst, int dstOffset, int size) {
            Buffer s = (Buffer) src;
            Buffer d = (Buffer) dst;
            GL33.glBindBuffer(GL33.GL_COPY_READ_BUFFER, s.id);
            GL33.glBindBuffer(GL33.GL_COPY_WRITE_BUFFER, d.id);
            GL33.glCopyBufferSubData(GL33.GL_COPY_READ_BUFFER, GL33.GL_COPY_WRITE_BUFFER,
                    srcOffset, dstOffset, size);
            GL33.glBindBuffer(GL33.GL_COPY_READ_BUFFER, 0);
            GL33.glBindBuffer(GL33.GL_COPY_WRITE_BUFFER, 0);
        }

        @Override
        public void bindUniformBuffer(GpuBuffer buffer) {
            Buffer b = (Buffer) buffer;
            GL33.glBindBufferBase(GL33.GL_UNIFORM_BUFFER, 0, b.id);
        }

        @Override
        public void bindIndexBuffer(GpuBuffer buffer) {
            Buffer b = (Buffer) buffer;
            GL33.glBindBuffer(GL33.GL_ELEMENT_ARRAY_BUFFER, b.id);
        }

        @Override
        public void writeTimestamp(GpuTimer timer, boolean end) {
            if (timer instanceof Timer t) {
                if (end) {
                    t.recordEnd();
                } else {
                    t.recordStart();
                }
            }
        }

        @Override
        public void end() {
        }
    }

    public record ExternalTerrainState(int framebuffer, int[] viewport, int program, int vao,
                                       int arrayBuffer, int elementBuffer, int uniformBuffer,
                                       int activeTexture, int texture0, int texture2,
                                       int sampler0, int sampler2,
                                       boolean blend, boolean depthTest, boolean cull,
                                       int depthFunc, boolean depthMask,
                                       int blendSrcRgb, int blendDstRgb, int blendSrcAlpha,
                                       int blendDstAlpha) {
        public static ExternalTerrainState capture() {
            int[] viewport = new int[4];
            GL33.glGetIntegerv(GL33.GL_VIEWPORT, viewport);

            int fbo = destiny.renderer.render.GlStateTracker.getCurrentFramebuffer();
            if (fbo < 0) fbo = GL33.glGetInteger(GL33.GL_DRAW_FRAMEBUFFER_BINDING);

            int prog = destiny.renderer.render.GlStateTracker.getCurrentProgram();
            if (prog < 0) prog = GL33.glGetInteger(GL33.GL_CURRENT_PROGRAM);

            int vao = destiny.renderer.render.GlStateTracker.getCurrentVao();
            if (vao < 0) vao = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING);

            int vbo = destiny.renderer.render.GlStateTracker.getCurrentArrayBuffer();
            if (vbo < 0) vbo = GL33.glGetInteger(GL33.GL_ARRAY_BUFFER_BINDING);

            int ebo = destiny.renderer.render.GlStateTracker.getCurrentElementArrayBuffer();
            if (ebo < 0) ebo = GL33.glGetInteger(GL33.GL_ELEMENT_ARRAY_BUFFER_BINDING);

            int active = destiny.renderer.render.GlStateTracker.getCurrentActiveTexture();
            if (active < 0) active = GL33.glGetInteger(GL33.GL_ACTIVE_TEXTURE);

            GL33.glActiveTexture(GL33.GL_TEXTURE0);
            int texture0 = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_2D);
            GL33.glActiveTexture(GL33.GL_TEXTURE2);
            int texture2 = GL33.glGetInteger(GL33.GL_TEXTURE_BINDING_2D);
            GL33.glActiveTexture(active);

            int blendVal = destiny.renderer.render.GlStateTracker.getBlendState();
            boolean blend = blendVal >= 0 ? (blendVal == 1) : GL33.glIsEnabled(GL33.GL_BLEND);

            int depthVal = destiny.renderer.render.GlStateTracker.getDepthTestState();
            boolean depthTest = depthVal >= 0 ? (depthVal == 1) : GL33.glIsEnabled(GL33.GL_DEPTH_TEST);

            int cullVal = destiny.renderer.render.GlStateTracker.getCullFaceState();
            boolean cull = cullVal >= 0 ? (cullVal == 1) : GL33.glIsEnabled(GL33.GL_CULL_FACE);

            return new ExternalTerrainState(
                    fbo, viewport, prog, vao, vbo, ebo,
                    org.lwjgl.opengl.GL30.glGetIntegeri(GL33.GL_UNIFORM_BUFFER_BINDING, 0), active, texture0, texture2,
                    org.lwjgl.opengl.GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 0),
                    org.lwjgl.opengl.GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, 2),
                    blend, depthTest, cull, GL33.glGetInteger(GL33.GL_DEPTH_FUNC),
                    GL33.glGetBoolean(GL33.GL_DEPTH_WRITEMASK),
                    GL33.glGetInteger(GL33.GL_BLEND_SRC_RGB), GL33.glGetInteger(GL33.GL_BLEND_DST_RGB),
                    GL33.glGetInteger(GL33.GL_BLEND_SRC_ALPHA), GL33.glGetInteger(GL33.GL_BLEND_DST_ALPHA));
        }

        void restore() {
            GL33.glBindFramebuffer(GL33.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL33.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL33.glUseProgram(program);
            GL33.glBindVertexArray(vao);
            GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, arrayBuffer);
            GL33.glBindBuffer(GL33.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
            GL33.glBindBufferBase(GL33.GL_UNIFORM_BUFFER, 0, uniformBuffer);
            setEnabled(GL33.GL_BLEND, blend);
            setEnabled(GL33.GL_DEPTH_TEST, depthTest);
            setEnabled(GL33.GL_CULL_FACE, cull);
            GL33.glDepthFunc(depthFunc);
            GL33.glDepthMask(depthMask);
            GL33.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            GL33.glActiveTexture(GL33.GL_TEXTURE0);
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, texture0);
            GL33.glBindSampler(0, sampler0);
            GL33.glActiveTexture(GL33.GL_TEXTURE2);
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, texture2);
            GL33.glBindSampler(2, sampler2);
            GL33.glActiveTexture(activeTexture);
        }

        private static void setEnabled(int capability, boolean enabled) {
            if (enabled) GL33.glEnable(capability); else GL33.glDisable(capability);
        }
    }

    private static final class Buffer implements GpuBuffer {
        private final Usage usage;
        private final int size;
        private final int id;
        private final java.nio.ByteBuffer mapped;
        private boolean destroyed;

        Buffer(Usage usage, int size) {
            this.usage = usage;
            this.size = size;
            this.id = GL33.glGenBuffers();
            int target = bufferTarget(usage);
            GL33.glBindBuffer(target, id);
            java.nio.ByteBuffer persistent = null;
            boolean canMap = usage != Usage.INDIRECT && usage != Usage.STORAGE
                    && org.lwjgl.opengl.GL.getCapabilities().GL_ARB_buffer_storage;
            if (canMap) {
                int mapFlags = org.lwjgl.opengl.GL44.GL_MAP_WRITE_BIT
                        | org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT
                        | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
                org.lwjgl.opengl.GL44.glBufferStorage(target, size, mapFlags);
                persistent = org.lwjgl.opengl.GL30.glMapBufferRange(target, 0, size, mapFlags);
                if (persistent == null) {
                    GL33.glBindBuffer(target, 0);
                    GL33.glDeleteBuffers(id);
                    throw new IllegalStateException("Failed to map persistent OpenGL buffer");
                }
            } else {
                GL33.glBufferData(target, size,
                        usage == Usage.UNIFORM ? GL33.GL_DYNAMIC_DRAW : GL33.GL_STREAM_DRAW);
            }
            this.mapped = persistent;
            GL33.glBindBuffer(target, 0);
        }

        private static int bufferTarget(Usage usage) {
            return switch (usage) {
                case VERTEX -> GL33.GL_ARRAY_BUFFER;
                case INDEX -> GL33.GL_ELEMENT_ARRAY_BUFFER;
                case UNIFORM -> GL33.GL_UNIFORM_BUFFER;
                case STAGING -> GL33.GL_COPY_READ_BUFFER;
                // GL_DRAW_INDIRECT_BUFFER / GL_SHADER_STORAGE_BUFFER are GL 4.x; the
                // reference backend is GL 3.3, so indirect/storage data rides in array
                // buffers until those features land.
                case INDIRECT -> org.lwjgl.opengl.GL43.GL_DRAW_INDIRECT_BUFFER;
                case STORAGE -> GL33.GL_ARRAY_BUFFER;
            };
        }

        int id() {
            return id;
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
            return id;
        }

        @Override
        public void destroy() {
            if (!destroyed) {
                if (mapped != null) {
                    int target = bufferTarget(usage);
                    GL33.glBindBuffer(target, id);
                    org.lwjgl.opengl.GL15.glUnmapBuffer(target);
                    GL33.glBindBuffer(target, 0);
                }
                GL33.glDeleteBuffers(id);
                destroyed = true;
            }
        }
    }

    private static final class Image implements GpuImage {
        private final Format format;
        private final int width;
        private final int height;
        private final int id;
        private boolean destroyed;

        Image(Format format, int width, int height) {
            this.format = format;
            this.width = width;
            this.height = height;
            this.id = GL33.glGenTextures();
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, id);
            GL33.glTexImage2D(GL33.GL_TEXTURE_2D, 0, internalFormat(format), width, height,
                    0, pixelFormat(format), pixelType(format), (long) 0);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_NEAREST);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, 0);
        }

        private static int internalFormat(Format format) {
            return switch (format) {
                case RGBA8 -> GL33.GL_RGBA8;
                case RGBA16F -> GL33.GL_RGBA16F;
                case DEPTH24 -> GL33.GL_DEPTH_COMPONENT24;
                case DEPTH32F -> GL33.GL_DEPTH_COMPONENT32F;
            };
        }

        private static int pixelFormat(Format format) {
            return switch (format) {
                case RGBA8, RGBA16F -> GL33.GL_RGBA;
                case DEPTH24, DEPTH32F -> GL33.GL_DEPTH_COMPONENT;
            };
        }

        private static int pixelType(Format format) {
            return switch (format) {
                case RGBA8 -> GL33.GL_UNSIGNED_BYTE;
                case RGBA16F -> GL33.GL_HALF_FLOAT;
                case DEPTH24 -> GL33.GL_UNSIGNED_INT;
                case DEPTH32F -> GL33.GL_FLOAT;
            };
        }

        int id() {
            return id;
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
            return id;
        }

        @Override
        public void destroy() {
            if (!destroyed) {
                GL33.glDeleteTextures(id);
                destroyed = true;
            }
        }
    }

    private static final class Pipeline implements GpuPipeline {
        private final OpenGLShaderProgram program;
        private final int vao;
        private final int defaultUbo;
        private boolean destroyed;

        Pipeline(OpenGLShaderProgram program) {
            this(program, GpuCommandEncoder.VertexLayout.POS_COLOR_2F_4F);
        }

        Pipeline(OpenGLShaderProgram program, GpuCommandEncoder.VertexLayout layout) {
            this.program = program;
            this.vao = GL33.glGenVertexArrays();
            GL33.glBindVertexArray(vao);
            // Enable attribute arrays on this VAO once at creation,
            // avoiding repetitive glEnableVertexAttribArray calls in hot-path rendering.
            GL33.glEnableVertexAttribArray(0);
            GL33.glEnableVertexAttribArray(1);
            if (layout == GpuCommandEncoder.VertexLayout.TERRAIN_BAKED) {
                GL33.glEnableVertexAttribArray(2);
                GL33.glEnableVertexAttribArray(3);
                GL33.glEnableVertexAttribArray(4);
            }
            boolean hasAttribBinding = org.lwjgl.opengl.GL.getCapabilities().GL_ARB_vertex_attrib_binding
                    || org.lwjgl.opengl.GL.getCapabilities().OpenGL43;
            if (hasAttribBinding) {
                if (layout == GpuCommandEncoder.VertexLayout.POS_COLOR_2F_4F) {
                    org.lwjgl.opengl.GL43.glVertexAttribFormat(0, 2, GL33.GL_FLOAT, false, 0);
                    org.lwjgl.opengl.GL43.glVertexAttribBinding(0, 0);
                    org.lwjgl.opengl.GL43.glVertexAttribFormat(1, 4, GL33.GL_FLOAT, false, 8);
                    org.lwjgl.opengl.GL43.glVertexAttribBinding(1, 0);
                } else if (layout == GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F) {
                    org.lwjgl.opengl.GL43.glVertexAttribFormat(0, 3, GL33.GL_FLOAT, false, 0);
                    org.lwjgl.opengl.GL43.glVertexAttribBinding(0, 0);
                    org.lwjgl.opengl.GL43.glVertexAttribFormat(1, 4, GL33.GL_FLOAT, false, 12);
                    org.lwjgl.opengl.GL43.glVertexAttribBinding(1, 0);
                } else if (layout == GpuCommandEncoder.VertexLayout.TERRAIN_BAKED) {
                    org.lwjgl.opengl.GL43.glVertexAttribFormat(0, 3, GL33.GL_FLOAT, false, 0);
                    org.lwjgl.opengl.GL43.glVertexAttribBinding(0, 0);
                    org.lwjgl.opengl.GL43.glVertexAttribFormat(1, 2, GL33.GL_FLOAT, false, 12);
                    org.lwjgl.opengl.GL43.glVertexAttribBinding(1, 0);
                    org.lwjgl.opengl.GL43.glVertexAttribFormat(2, 4, GL33.GL_UNSIGNED_BYTE, true, 20);
                    org.lwjgl.opengl.GL43.glVertexAttribBinding(2, 0);
                    org.lwjgl.opengl.GL43.glVertexAttribIFormat(3, 1, GL33.GL_UNSIGNED_INT, 24);
                    org.lwjgl.opengl.GL43.glVertexAttribBinding(3, 0);
                    org.lwjgl.opengl.GL43.glVertexAttribFormat(4, 4, GL33.GL_BYTE, true, 28);
                    org.lwjgl.opengl.GL43.glVertexAttribBinding(4, 0);
                }
            }
            GL33.glBindVertexArray(0);

            // Default identity UBO (identity MVP + white tint) so the pipeline draws correctly
            // before the engine binds real per-frame camera data (ARCHITECTURE.md §16).
            this.defaultUbo = GL33.glGenBuffers();
            GL33.glBindBuffer(GL33.GL_UNIFORM_BUFFER, defaultUbo);
            java.nio.ByteBuffer identity = java.nio.ByteBuffer.allocateDirect(80)
                    .order(java.nio.ByteOrder.nativeOrder());
            for (int i = 0; i < 16; i++) {
                identity.putFloat(i % 5 == 0 ? 1.0f : 0.0f); // column-major identity
            }
            identity.putFloat(1.0f).putFloat(1.0f).putFloat(1.0f).putFloat(1.0f); // white tint
            identity.flip();
            GL33.glBufferData(GL33.GL_UNIFORM_BUFFER, identity, GL33.GL_STATIC_DRAW);
            GL33.glBindBuffer(GL33.GL_UNIFORM_BUFFER, 0);
            int blockIndex = GL33.glGetUniformBlockIndex(program.program(), "Uniforms");
            if (blockIndex != GL33.GL_INVALID_INDEX) {
                GL33.glUniformBlockBinding(program.program(), blockIndex, 0);
            }
        }

        OpenGLShaderProgram program() {
            return program;
        }

        int vao() {
            return vao;
        }

        int defaultUbo() {
            return defaultUbo;
        }

        @Override
        public long handle() {
            return program.program();
        }

        @Override
        public void destroy() {
            if (!destroyed) {
                GL33.glDeleteBuffers(defaultUbo);
                GL33.glDeleteVertexArrays(vao);
                program.close();
                destroyed = true;
            }
        }
    }

    private static final class Timer implements GpuTimer {
        private final int[] query = new int[2];
        private boolean begun;

        Timer() {
            GL33.glGenQueries(query);
        }

        void recordStart() {
            GL33.glQueryCounter(query[0], GL33.GL_TIMESTAMP);
        }

        void recordEnd() {
            GL33.glQueryCounter(query[1], GL33.GL_TIMESTAMP);
        }

        @Override
        public long elapsedNanos() {
            long endNs = read(query[1]);
            long startNs = read(query[0]);
            return endNs > startNs ? endNs - startNs : 0L;
        }

        @Override
        public long tryElapsedNanos() {
            if (GL33.glGetQueryObjecti(query[1], GL33.GL_QUERY_RESULT_AVAILABLE) == 0) return 0L;
            return elapsedNanos();
        }

        private final long[] result = new long[1];

        private long read(int id) {
            GL33.glGetQueryObjecti64v(id, GL33.GL_QUERY_RESULT, result);
            return result[0];
        }

        @Override
        public void destroy() {
            GL33.glDeleteQueries(query);
        }
    }

    private static final class Sync implements GpuSync {
        private long fence;

        @Override
        public void signal() {
            fence = GL33.glFenceSync(GL33.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }

        @Override
        public void waitFor() {
            if (fence == 0L) return;
            // Non-blocking poll first, tiny bounded micro-wait if not yet signaled
            int result = GL33.glClientWaitSync(fence, GL33.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
            if (result == GL33.GL_TIMEOUT_EXPIRED) {
                GL33.glClientWaitSync(fence, GL33.GL_SYNC_FLUSH_COMMANDS_BIT, 50_000L); // at most 50µs
            }
            GL33.glDeleteSync(fence);
            fence = 0L;
        }

        @Override
        public void reset() {
            if (fence != 0) {
                GL33.glDeleteSync(fence);
                fence = 0;
            }
        }
    }

    private static final class Allocator implements GpuMemoryAllocator {
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
}

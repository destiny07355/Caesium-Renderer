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

    private final Queue queue = new Queue();
    private final Allocator allocator = new Allocator();
    private GpuPipeline defaultPipeline;
    private GpuPipeline terrainPipeline;
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

    @Override
    public void initialize() {
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
        if (layout == GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F) {
            if (terrainPipeline == null) {
                terrainPipeline = new Pipeline(
                        new OpenGLShaderProgram("caesium-terrain", TERRAIN_VERT, TERRAIN_FRAG));
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

        @Override
        public void waitIdle() {
            GL33.glFinish();
        }
    }

    private static final class Encoder implements GpuCommandEncoder {
        @Override
        public void begin() {
        }

        @Override
        public void bindPipeline(GpuPipeline pipeline) {
            if (pipeline instanceof Pipeline p) {
                GL33.glUseProgram(p.program().program());
                GL33.glBindVertexArray(p.vao());
                GL33.glBindBufferBase(GL33.GL_UNIFORM_BUFFER, 0, p.defaultUbo());
            }
        }

        @Override
        public void writeBuffer(GpuBuffer buffer, int offset, java.nio.ByteBuffer data) {
            Buffer b = (Buffer) buffer;
            int target = Buffer.bufferTarget(b.usage());
            GL33.glBindBuffer(target, b.id());
            GL33.glBufferSubData(target, (long) offset, data);
            GL33.glBindBuffer(target, 0);
        }

        @Override
        public void bindVertexBuffer(GpuBuffer buffer, VertexLayout layout) {
            Buffer b = (Buffer) buffer;
            GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, b.id());
            if (layout == VertexLayout.POS_COLOR_2F_4F) {
                // Positions vec2 + colors vec4, tightly packed (stride 24 bytes).
                GL33.glVertexAttribPointer(0, 2, GL33.GL_FLOAT, false, 24, 0L);
                GL33.glEnableVertexAttribArray(0);
                GL33.glVertexAttribPointer(1, 4, GL33.GL_FLOAT, false, 24, 8L);
                GL33.glEnableVertexAttribArray(1);
            } else if (layout == VertexLayout.POS_COLOR_3F_4F) {
                // Positions vec3 + colors vec4, tightly packed (stride 28 bytes).
                GL33.glVertexAttribPointer(0, 3, GL33.GL_FLOAT, false, 28, 0L);
                GL33.glEnableVertexAttribArray(0);
                GL33.glVertexAttribPointer(1, 4, GL33.GL_FLOAT, false, 28, 12L);
                GL33.glEnableVertexAttribArray(1);
            }
            GL33.glBindBuffer(GL33.GL_ARRAY_BUFFER, 0);
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

    private static final class Buffer implements GpuBuffer {
        private final Usage usage;
        private final int size;
        private final int id;
        private boolean destroyed;

        Buffer(Usage usage, int size) {
            this.usage = usage;
            this.size = size;
            this.id = GL33.glGenBuffers();
            int target = bufferTarget(usage);
            GL33.glBindBuffer(target, id);
            GL33.glBufferData(target, size, GL33.GL_STATIC_DRAW);
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
                case INDIRECT, STORAGE -> GL33.GL_ARRAY_BUFFER;
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
                    0, pixelFormat(format), GL33.GL_UNSIGNED_BYTE, (long) 0);
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
            this.program = program;
            this.vao = GL33.glGenVertexArrays();
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
            long timeoutNs = 100_000_000L; // 100ms
            int result;
            do {
                result = GL33.glClientWaitSync(fence, GL33.GL_SYNC_FLUSH_COMMANDS_BIT, timeoutNs);
            } while (result == GL33.GL_TIMEOUT_EXPIRED);
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
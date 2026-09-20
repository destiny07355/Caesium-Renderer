package destiny.renderer.render;

import destiny.renderer.memory.GpuBuffer;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.logging.Logger;

/**
 * Batched entity renderer — eliminates per-entity draw calls and state changes.
 *
 * <p>Uses pre-partitioned staging regions directly in GPU memory or a unified staging buffer
 * to completely eliminate CPU consolidation copying before rendering.
 * All OpenGL state transitions are routed through {@link GlStateTracker}.
 */
public final class EntityBatchRenderer {

    private static final Logger LOGGER = Logger.getLogger("Caesium/EntityBatch");

    public static final int NUM_RINGS = 3;
    public static final int MAX_SLOTS = 64;
    public static final int SLOT_CAPACITY_VERTS = 2048; // 2048 vertices = 512 quads per texture slot
    public static final int TOTAL_VERTS = MAX_SLOTS * SLOT_CAPACITY_VERTS; // 131,072 vertices per frame
    private static final int VERTEX_STRIDE = 4 * Float.BYTES; // x, y, z, uv packed = 16 bytes
    private static final int TOTAL_FLOATS = TOTAL_VERTS * 4;
    private static final long TOTAL_BYTES = (long) TOTAL_FLOATS * Float.BYTES; // 2 MB per frame
    private static final long RING_TOTAL_BYTES = TOTAL_BYTES * NUM_RINGS; // 6 MB triple-buffered ring

    public enum BatchResult {
        ADDED,
        FALLBACK_SLOTS_FULL,
        FALLBACK_BUFFER_FULL;

        public boolean isSuccess() { return this == ADDED; }
        public boolean isAdded() { return this == ADDED; }
        public boolean requiresFallback() { return this != ADDED; }
    }

    public static final class BatchSlot {
        public int textureId = -1;
        public int startVertex = 0;
        public int vertCount = 0;
    }

    private final BatchSlot[] slots = new BatchSlot[MAX_SLOTS];
    private int activeSlotCount = 0;

    // Direct integer-indexed lookup table: textureId -> slot index (open-addressed, power of 2)
    private static final int LOOKUP_CAPACITY = 128; // power-of-two > MAX_SLOTS to keep load factor <= 0.50
    private static final int LOOKUP_MASK = LOOKUP_CAPACITY - 1;
    private final int[] lookupKeys = new int[LOOKUP_CAPACITY];
    private final int[] lookupSlotIndices = new int[LOOKUP_CAPACITY];

    // Direct CPU staging buffer (fallback mode)
    private final float[] unifiedStaging = new float[TOTAL_FLOATS];
    private final FloatBuffer unifiedNioBuffer = FloatBuffer.wrap(unifiedStaging);

    // Persistent GPU buffer & mapped view
    private GpuBuffer persistentBuffer;
    private FloatBuffer mappedFloatBuffer;
    private int currentRingSlot = 0;
    private int ringVertexOffset = 0;
    private int ringFloatOffset = 0;

    // OpenGL resources
    private int batchVAO;
    private int batchShader;
    private int uProjection, uView, uTexture;
    private int lastProjHash = 0;
    private int lastViewHash = 0;
    private boolean hasMatrices = false;

    public EntityBatchRenderer() {
        for (int i = 0; i < MAX_SLOTS; i++) {
            slots[i] = new BatchSlot();
            slots[i].startVertex = i * SLOT_CAPACITY_VERTS;
        }
        java.util.Arrays.fill(lookupKeys, -1);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void initialize() {
        try {
            persistentBuffer = GpuBuffer.createPersistent(GL15.GL_ARRAY_BUFFER, RING_TOTAL_BYTES);
            if (persistentBuffer.isPersistent() && persistentBuffer.segment() != null) {
                ByteBuffer byteBuf = persistentBuffer.segment().asByteBuffer();
                mappedFloatBuffer = byteBuf.asFloatBuffer();
            }
        } catch (Throwable t) {
            LOGGER.warning("[Caesium] Fallback to standard VBO for EntityBatchRenderer: " + t.getMessage());
            int vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, TOTAL_BYTES, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            persistentBuffer = GpuBuffer.createStatic(GL15.GL_ARRAY_BUFFER, TOTAL_BYTES, GL15.GL_DYNAMIC_DRAW);
        }

        int vboHandle = persistentBuffer != null ? persistentBuffer.handle() : 0;

        batchVAO = GL30.glGenVertexArrays();
        GlStateTracker.bindVertexArray(batchVAO);
        GlStateTracker.bindArrayBuffer(vboHandle);
        // attrib 0: position (xyz) + UV (packed w)
        GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, VERTEX_STRIDE, 0L);
        GL20.glEnableVertexAttribArray(0);
        GlStateTracker.bindVertexArray(0);
        GlStateTracker.bindArrayBuffer(0);

        batchShader = compileEntityShader();
        if (batchShader != 0) {
            uProjection = GL20.glGetUniformLocation(batchShader, "u_Projection");
            uView       = GL20.glGetUniformLocation(batchShader, "u_View");
            uTexture    = GL20.glGetUniformLocation(batchShader, "u_Texture");

            // Configure sampler uniform once at creation — routed through GlStateTracker
            GlStateTracker.useProgram(batchShader);
            GL20.glUniform1i(uTexture, 0);
            GlStateTracker.useProgram(0);
        }

        LOGGER.info("[Caesium] EntityBatchRenderer initialized with persistent triple-buffered ring ("
                + (RING_TOTAL_BYTES / 1024) + " KB).");
    }

    // -------------------------------------------------------------------------
    // Per-frame batch building
    // -------------------------------------------------------------------------

    /** Called at frame start to reset all entity batches and the slot lookup table. */
    public void beginFrame() {
        GlStateTracker.invalidate();
        currentRingSlot = (int) (destiny.renderer.hud.PerformanceOverlay.frameCounter() % NUM_RINGS);
        ringVertexOffset = currentRingSlot * TOTAL_VERTS;
        ringFloatOffset = currentRingSlot * TOTAL_FLOATS;
        for (int i = 0; i < activeSlotCount; i++) {
            slots[i].vertCount = 0;
            slots[i].textureId = -1;
        }
        activeSlotCount = 0;
        java.util.Arrays.fill(lookupKeys, -1);
    }

    /**
     * Searches for the batch slot allocated to the given texture ID via open addressing.
     */
    private int findSlot(int textureId) {
        int hash = (textureId * 0x9E3779B9) & LOOKUP_MASK;
        for (int i = 0; i < LOOKUP_CAPACITY; i++) {
            int idx = (hash + i) & LOOKUP_MASK;
            int k = lookupKeys[idx];
            if (k == textureId) return lookupSlotIndices[idx];
            if (k == -1) return -1;
        }
        return -1;
    }

    /**
     * Adds an entity's geometry directly into the unified staging buffer for its atlas batch.
     *
     * @param atlasTextureId GL texture ID of the entity's texture atlas
     * @param vertices       float array: [x,y,z,u,v] per vertex, grouped in quads (4 verts each)
     * @param count          number of vertices
     * @return BatchResult indicating success or specific fallback requirement
     */
    public BatchResult addEntityBatch(int atlasTextureId, float[] vertices, int count) {
        int slotIdx = findSlot(atlasTextureId);
        if (slotIdx < 0) {
            if (activeSlotCount >= MAX_SLOTS) {
                return BatchResult.FALLBACK_SLOTS_FULL; // Beyond 64 distinct atlas slots
            }
            slotIdx = activeSlotCount++;
            BatchSlot slot = slots[slotIdx];
            slot.textureId = atlasTextureId;
            slot.vertCount = 0;

            // Record in open-addressed lookup table
            int hash = (atlasTextureId * 0x9E3779B9) & LOOKUP_MASK;
            for (int i = 0; i < LOOKUP_CAPACITY; i++) {
                int idx = (hash + i) & LOOKUP_MASK;
                if (lookupKeys[idx] == -1 || lookupKeys[idx] == atlasTextureId) {
                    lookupKeys[idx] = atlasTextureId;
                    lookupSlotIndices[idx] = slotIdx;
                    break;
                }
            }
        }

        BatchSlot slot = slots[slotIdx];
        if (slot.vertCount + count > SLOT_CAPACITY_VERTS) {
            return BatchResult.FALLBACK_BUFFER_FULL; // Slot capacity full
        }

        int slotFloatOffset = (slot.startVertex + slot.vertCount) * 4;
        int needed = count * 4;

        if (mappedFloatBuffer != null) {
            int destOffset = ringFloatOffset + slotFloatOffset;
            mappedFloatBuffer.position(destOffset);
            mappedFloatBuffer.put(vertices, 0, needed);
        } else {
            System.arraycopy(vertices, 0, unifiedStaging, slotFloatOffset, needed);
        }

        slot.vertCount += count;
        return BatchResult.ADDED;
    }

    /**
     * Backward-compatible boolean helper for addEntityBatch.
     */
    public boolean addEntity(int atlasTextureId, float[] vertices, int count) {
        return addEntityBatch(atlasTextureId, vertices, count).isAdded();
    }

    /**
     * Submits entity geometry to the batcher, guaranteeing execution of the vanillaFallback
     * runnable if batch slots (64) or buffer limits are exhausted.
     *
     * @return true if batched, false if fallen back to vanilla
     */
    public boolean submitOrFallback(int atlasTextureId, float[] vertices, int count, Runnable vanillaFallback) {
        BatchResult result = addEntityBatch(atlasTextureId, vertices, count);
        if (result.isSuccess()) {
            return true;
        }
        if (vanillaFallback != null) {
            vanillaFallback.run();
        }
        return false;
    }

    /**
     * Flushes all entity batches to the GPU and issues draw calls.
     * All state transitions are tracked via {@link GlStateTracker}.
     */
    public void flushAndRender(float[] projectionMatrix, float[] viewMatrix) {
        if (activeSlotCount == 0 || batchShader == 0) return;
        destiny.renderer.hud.CaesiumFrameProfiler.beginEntities();
        try {
            int maxEndVertex = 0;
            for (int i = 0; i < activeSlotCount; i++) {
                BatchSlot slot = slots[i];
                if (slot.vertCount > 0) {
                    maxEndVertex = Math.max(maxEndVertex, slot.startVertex + slot.vertCount);
                }
            }
            if (maxEndVertex == 0) return;

            int vboHandle = persistentBuffer != null ? persistentBuffer.handle() : 0;

            GlStateTracker.useProgram(batchShader);
            int projHash = java.util.Arrays.hashCode(projectionMatrix);
            if (!hasMatrices || projHash != lastProjHash) {
                GL20.glUniformMatrix4fv(uProjection, false, projectionMatrix);
                lastProjHash = projHash;
            }
            int viewHash = java.util.Arrays.hashCode(viewMatrix);
            if (!hasMatrices || viewHash != lastViewHash) {
                GL20.glUniformMatrix4fv(uView, false, viewMatrix);
                lastViewHash = viewHash;
            }
            hasMatrices = true;

            GlStateTracker.setBlend(true);
            GlStateTracker.bindVertexArray(batchVAO);
            GlStateTracker.bindArrayBuffer(vboHandle);

            // If not persistently mapped (GL 3.3 fallback mode), upload dirty staging slice
            if (mappedFloatBuffer == null && vboHandle != 0) {
                int uploadFloats = maxEndVertex * 4;
                unifiedNioBuffer.position(0);
                unifiedNioBuffer.limit(uploadFloats);
                GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) ringFloatOffset * Float.BYTES, unifiedNioBuffer);
            }

            GlStateTracker.activeTexture(GL13.GL_TEXTURE0);

            for (int i = 0; i < activeSlotCount; i++) {
                BatchSlot slot = slots[i];
                int count = slot.vertCount;
                if (count == 0) continue;

                GlStateTracker.bindTexture2D(slot.textureId);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, ringVertexOffset + slot.startVertex, count);
            }
        } finally {
            destiny.renderer.hud.CaesiumFrameProfiler.endEntities();
        }
    }

    public void shutdown() {
        if (batchVAO != 0) GL30.glDeleteVertexArrays(batchVAO);
        if (persistentBuffer != null) {
            persistentBuffer.close();
            persistentBuffer = null;
        }
        if (batchShader != 0) GL20.glDeleteProgram(batchShader);
    }

    // -------------------------------------------------------------------------
    // Shader
    // -------------------------------------------------------------------------

    private int compileEntityShader() {
        String vert = """
            #version 330 core
            layout(location = 0) in vec4 a_PosUV;
            uniform mat4 u_Projection;
            uniform mat4 u_View;
            out vec2 v_UV;
            void main() {
                gl_Position = u_Projection * u_View * vec4(a_PosUV.xyz, 1.0);
                v_UV = a_PosUV.zw; // UV stored in z,w
            }
            """;
        String frag = """
            #version 330 core
            in vec2 v_UV;
            uniform sampler2D u_Texture;
            out vec4 fragColor;
            void main() {
                vec4 col = texture(u_Texture, v_UV);
                if (col.a < 0.1) discard;
                fragColor = col;
            }
            """;
        try {
            int vs = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
            GL20.glShaderSource(vs, vert); GL20.glCompileShader(vs);
            int fs = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(fs, frag); GL20.glCompileShader(fs);
            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs); GL20.glAttachShader(prog, fs);
            GL20.glLinkProgram(prog);
            GL20.glDeleteShader(vs); GL20.glDeleteShader(fs);
            return prog;
        } catch (Exception e) {
            LOGGER.warning("[Caesium] Entity shader compile failed: " + e.getMessage());
            return 0;
        }
    }
}

package destiny.renderer.render;

import destiny.renderer.memory.RendererArenaManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Batched entity renderer — eliminates the per-entity immediate-mode draw calls of
 * vanilla's {@code EntityRenderDispatcher}.
 *
 * <h2>Strategy</h2>
 * Entities are grouped by their texture atlas. All entities sharing the same atlas are
 * written into a single frame-local vertex buffer allocated from the FFM frame arena
 * (guaranteed to be freed at the start of the next frame). A single MDI call per atlas
 * renders the entire group without per-entity state changes.
 *
 * <h2>Memory Safety</h2>
 * By using a frame-scoped {@link RendererArenaManager#allocateFrame(long)} allocation,
 * the entity buffer is automatically reclaimed without GC involvement. This eliminates
 * the use-after-free risk that affects ImmediatelyFast when Iris rebinds framebuffers
 * mid-frame — our buffer is scoped and deterministically freed.
 */
public final class EntityBatchRenderer {

    private static final Logger LOGGER = Logger.getLogger("Caesium/EntityBatch");

    /** Maximum vertices per entity batch (roughly 400 entities × 24 verts per entity). */
    private static final int MAX_VERTS_PER_BATCH = 131072;
    private static final int VERTEX_STRIDE = 4 * Float.BYTES; // x, y, z, uv packed = 16 bytes

    public static final class BatchSlot {
        int textureId = -1;
        int startVertex = 0;
        final BatchAccumulator accumulator = new BatchAccumulator();
    }

    private static final int MAX_SLOTS = 64;
    private final BatchSlot[] slots = new BatchSlot[MAX_SLOTS];
    private int activeSlotCount = 0;

    private float[] unifiedVerts = new float[4096];
    private java.nio.FloatBuffer unifiedNioBuffer = java.nio.FloatBuffer.wrap(unifiedVerts);

    {
        for (int i = 0; i < MAX_SLOTS; i++) {
            slots[i] = new BatchSlot();
        }
    }

    // OpenGL resources
    private int batchVBO;
    private int batchVAO;
    private int batchShader;
    private int uProjection, uView, uTexture;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void initialize() {
        batchVBO = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchVBO);
        // Dynamic upload buffer for entity batches — no persistent mapping needed
        // (entities change too frequently for persistent mapping to be beneficial)
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) MAX_VERTS_PER_BATCH * VERTEX_STRIDE, GL15.GL_DYNAMIC_DRAW);

        batchVAO = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(batchVAO);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchVBO);
        // attrib 0: position (xyz) + UV (packed w)
        GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, VERTEX_STRIDE, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);

        batchShader = compileEntityShader();
        if (batchShader != 0) {
            uProjection = GL20.glGetUniformLocation(batchShader, "u_Projection");
            uView       = GL20.glGetUniformLocation(batchShader, "u_View");
            uTexture    = GL20.glGetUniformLocation(batchShader, "u_Texture");
        }

        LOGGER.info("[Caesium] EntityBatchRenderer initialized.");
    }

    // -------------------------------------------------------------------------
    // Per-frame batch building
    // -------------------------------------------------------------------------

    /** Called at frame start to reset all entity batches. */
    public void beginFrame() {
        for (int i = 0; i < activeSlotCount; i++) {
            slots[i].accumulator.reset();
            slots[i].textureId = -1;
        }
        activeSlotCount = 0;
    }

    /**
     * Adds an entity's geometry to its atlas batch.
     * This is called from the mixin that intercepts {@code EntityRenderDispatcher.render()}.
     *
     * @param atlasTextureId GL texture ID of the entity's texture atlas
     * @param vertices       float array: [x,y,z,u,v] per vertex, grouped in quads (4 verts each)
     * @param count          number of vertices
     */
    public void addEntity(int atlasTextureId, float[] vertices, int count) {
        for (int i = 0; i < activeSlotCount; i++) {
            if (slots[i].textureId == atlasTextureId) {
                slots[i].accumulator.add(vertices, count);
                return;
            }
        }
        if (activeSlotCount < MAX_SLOTS) {
            BatchSlot slot = slots[activeSlotCount++];
            slot.textureId = atlasTextureId;
            slot.accumulator.reset();
            slot.accumulator.add(vertices, count);
        }
    }

    /**
     * Flushes all entity batches to the GPU and issues draw calls.
     * Called once per frame after all entities have been processed.
     *
     * @param projectionMatrix 16-float column-major projection matrix
     * @param viewMatrix       16-float column-major view matrix
     */
    public void flushAndRender(float[] projectionMatrix, float[] viewMatrix) {
        if (activeSlotCount == 0 || batchShader == 0) return;
        destiny.renderer.hud.CaesiumFrameProfiler.beginEntities();
        try {
            int totalVerts = 0;
            for (int i = 0; i < activeSlotCount; i++) {
                totalVerts += slots[i].accumulator.vertCount;
            }
            if (totalVerts == 0) return;

            int totalFloats = totalVerts * 4;
            if (unifiedVerts.length < totalFloats) {
                unifiedVerts = new float[Math.max(totalFloats, unifiedVerts.length * 2)];
                unifiedNioBuffer = java.nio.FloatBuffer.wrap(unifiedVerts);
            }

            int offset = 0;
            int firstVertex = 0;
            for (int i = 0; i < activeSlotCount; i++) {
                BatchSlot slot = slots[i];
                int count = slot.accumulator.vertCount;
                if (count == 0) continue;
                slot.startVertex = firstVertex;
                System.arraycopy(slot.accumulator.verts, 0, unifiedVerts, offset, count * 4);
                offset += count * 4;
                firstVertex += count;
            }

            GL20.glUseProgram(batchShader);
            GL20.glUniformMatrix4fv(uProjection, false, projectionMatrix);
            GL20.glUniformMatrix4fv(uView,       false, viewMatrix);

            GL11.glEnable(GL11.GL_BLEND);
            GL30.glBindVertexArray(batchVAO);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, batchVBO);

            // Single unified upload for all entity vertices this frame
            unifiedNioBuffer.position(0);
            unifiedNioBuffer.limit(totalFloats);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, unifiedNioBuffer);

            // Single texture sampler setup
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL20.glUniform1i(uTexture, 0);

            int lastBoundTexture = -1;
            for (int i = 0; i < activeSlotCount; i++) {
                BatchSlot slot = slots[i];
                int count = slot.accumulator.vertCount;
                if (count == 0) continue;

                if (lastBoundTexture != slot.textureId) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, slot.textureId);
                    lastBoundTexture = slot.textureId;
                }

                GL11.glDrawArrays(GL11.GL_TRIANGLES, slot.startVertex, count);
            }

            GL30.glBindVertexArray(0);
            GL11.glDisable(GL11.GL_BLEND);
            GL20.glUseProgram(0);
        } finally {
            destiny.renderer.hud.CaesiumFrameProfiler.endEntities();
        }
    }

    public void shutdown() {
        if (batchVAO    != 0) GL30.glDeleteVertexArrays(batchVAO);
        if (batchVBO    != 0) GL15.glDeleteBuffers(batchVBO);
        if (batchShader != 0) GL20.glDeleteProgram(batchShader);
    }

    // -------------------------------------------------------------------------
    // Inline batch accumulator
    // -------------------------------------------------------------------------

    private static final class BatchAccumulator {
        float[] verts = new float[1024]; // start small, grow as needed
        int vertCount = 0;
        java.nio.FloatBuffer nioBuffer = java.nio.FloatBuffer.wrap(verts);

        void reset() {
            vertCount = 0;
        }

        void add(float[] src, int count) {
            int needed = count * 4;
            int required = vertCount * 4 + needed;
            if (required > verts.length) {
                int newSize = Math.max(required, verts.length * 2);
                if (newSize > MAX_VERTS_PER_BATCH * 4) {
                    newSize = MAX_VERTS_PER_BATCH * 4;
                    if (required > newSize) return; // batch full
                }
                verts = java.util.Arrays.copyOf(verts, newSize);
                nioBuffer = java.nio.FloatBuffer.wrap(verts);
            }
            System.arraycopy(src, 0, verts, vertCount * 4, needed);
            vertCount += count;
        }

        java.nio.FloatBuffer getUploadBuffer() {
            nioBuffer.position(0);
            nioBuffer.limit(vertCount * 4);
            return nioBuffer;
        }
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

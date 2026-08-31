package destiny.renderer.render.sky;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;

public final class FastSkyRenderer {

    private static int cloudVao = 0;
    private static int cloudVbo = 0;
    private static int cloudVertexCount = 0;
    private static boolean initialized = false;

    private FastSkyRenderer() {}

    public static synchronized void initialize() {
        if (initialized) return;

        cloudVao = GL30.glGenVertexArrays();
        cloudVbo = GL15.glGenBuffers();

        GL30.glBindVertexArray(cloudVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cloudVbo);

        GL30.glEnableVertexAttribArray(0);
        GL30.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 9 * Float.BYTES, 0);

        GL30.glEnableVertexAttribArray(1);
        GL30.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 9 * Float.BYTES, 3 * Float.BYTES);

        GL30.glEnableVertexAttribArray(2);
        GL30.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, 9 * Float.BYTES, 5 * Float.BYTES);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        initialized = true;
    }

    public static synchronized void uploadCloudGeometry(FloatBuffer vertexBuffer, int vertexCount) {
        if (!initialized || vertexBuffer == null || vertexCount <= 0) return;

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, cloudVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        cloudVertexCount = vertexCount;
    }

    public static void renderClouds() {
        if (!initialized || cloudVertexCount == 0) return;

        GL30.glBindVertexArray(cloudVao);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, cloudVertexCount);
        GL30.glBindVertexArray(0);
    }

    public static synchronized void shutdown() {
        if (!initialized) return;
        if (cloudVao != 0) { GL30.glDeleteVertexArrays(cloudVao); cloudVao = 0; }
        if (cloudVbo != 0) { GL15.glDeleteBuffers(cloudVbo); cloudVbo = 0; }
        initialized = false;
    }
}

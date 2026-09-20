package destiny.renderer.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * High-performance shadow state tracker for OpenGL pipeline state.
 *
 * <p>Avoids redundant driver round-trips for shader programs, VAOs, VBOs,
 * 2D texture bindings, active texture units, and blend state.
 */
public final class GlStateTracker {

    private static int currentProgram = -1;
    private static int currentVao = -1;
    private static int currentArrayBuffer = -1;
    private static int currentTexture2D = -1;
    private static int currentActiveTexture = -1;
    private static int blendState = -1; // -1 unknown, 0 disabled, 1 enabled

    private GlStateTracker() {}

    public static void useProgram(int program) {
        if (currentProgram != program) {
            GL20.glUseProgram(program);
            currentProgram = program;
        }
    }

    public static void bindVertexArray(int vao) {
        if (currentVao != vao) {
            GL30.glBindVertexArray(vao);
            currentVao = vao;
        }
    }

    public static void bindArrayBuffer(int vbo) {
        if (currentArrayBuffer != vbo) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            currentArrayBuffer = vbo;
        }
    }

    public static void activeTexture(int unit) {
        if (currentActiveTexture != unit) {
            GL13.glActiveTexture(unit);
            currentActiveTexture = unit;
        }
    }

    public static void bindTexture2D(int texture) {
        if (currentTexture2D != texture) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            currentTexture2D = texture;
        }
    }

    public static void setBlend(boolean enabled) {
        int state = enabled ? 1 : 0;
        if (blendState != state) {
            if (enabled) {
                GL11.glEnable(GL11.GL_BLEND);
            } else {
                GL11.glDisable(GL11.GL_BLEND);
            }
            blendState = state;
        }
    }

    private static int depthTestState = -1;
    private static int cullFaceState = -1;
    private static int scissorTestState = -1;
    private static int currentFramebuffer = -1;
    private static int currentElementArrayBuffer = -1;
    private static final int[] currentSamplers = new int[16];
    static {
        java.util.Arrays.fill(currentSamplers, -1);
    }
    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger("Caesium/GL");

    public static void bindFramebuffer(int target, int fbo) {
        if (target == GL30.GL_FRAMEBUFFER) {
            if (currentFramebuffer != fbo) {
                GL30.glBindFramebuffer(target, fbo);
                currentFramebuffer = fbo;
            }
        } else {
            GL30.glBindFramebuffer(target, fbo);
        }
    }

    public static void bindElementArrayBuffer(int ebo) {
        if (currentElementArrayBuffer != ebo) {
            GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            currentElementArrayBuffer = ebo;
        }
    }

    public static void bindSampler(int unit, int sampler) {
        if (unit >= 0 && unit < currentSamplers.length) {
            if (currentSamplers[unit] != sampler) {
                org.lwjgl.opengl.GL33.glBindSampler(unit, sampler);
                currentSamplers[unit] = sampler;
            }
        } else {
            org.lwjgl.opengl.GL33.glBindSampler(unit, sampler);
        }
    }

    public static void setScissorTest(boolean enabled) {
        int state = enabled ? 1 : 0;
        if (scissorTestState != state) {
            if (enabled) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            scissorTestState = state;
        }
    }

    public static void setDepthTest(boolean enabled) {
        int state = enabled ? 1 : 0;
        if (depthTestState != state) {
            if (enabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
            else GL11.glDisable(GL11.GL_DEPTH_TEST);
            depthTestState = state;
        }
    }

    public static void setCullFace(boolean enabled) {
        int state = enabled ? 1 : 0;
        if (cullFaceState != state) {
            if (enabled) GL11.glEnable(GL11.GL_CULL_FACE);
            else GL11.glDisable(GL11.GL_CULL_FACE);
            cullFaceState = state;
        }
    }

    public static void checkError(String callsite) {
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) {
            LOGGER.warning("[Caesium/GL Error] " + callsite + " -> code 0x" + Integer.toHexString(err));
        }
    }

    /**
     * Resets all cached state to unknown. Called at frame start and after
     * unmanaged/vanilla rendering passes.
     */
    public static void invalidate() {
        currentProgram = -1;
        currentVao = -1;
        currentArrayBuffer = -1;
        currentElementArrayBuffer = -1;
        currentFramebuffer = -1;
        currentTexture2D = -1;
        currentActiveTexture = -1;
        blendState = -1;
        depthTestState = -1;
        cullFaceState = -1;
        scissorTestState = -1;
        java.util.Arrays.fill(currentSamplers, -1);
    }

    public static int getCurrentProgram() { return currentProgram; }
    public static int getCurrentVao() { return currentVao; }
    public static int getCurrentArrayBuffer() { return currentArrayBuffer; }
    public static int getCurrentElementArrayBuffer() { return currentElementArrayBuffer; }
    public static int getCurrentFramebuffer() { return currentFramebuffer; }
    public static int getCurrentActiveTexture() { return currentActiveTexture; }
    public static int getBlendState() { return blendState; }
    public static int getDepthTestState() { return depthTestState; }
    public static int getCullFaceState() { return cullFaceState; }
}

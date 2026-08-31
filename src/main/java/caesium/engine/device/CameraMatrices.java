package caesium.engine.device;

import caesium.engine.world.RenderWorld;

/**
 * Pure-math builder for the per-frame MVP the terrain pass uploads through the engine's
 * shared {@code Uniforms} block ({@code mat4 uMVP}). Zero Minecraft imports — it consumes
 * the engine-neutral {@link RenderWorld.Camera} record (position + Minecraft-style
 * pitch/yaw in degrees: yaw 0 faces +Z, pitch positive looks down) and returns a
 * column-major float[16] in the memory layout GLSL/GL expect (ARCHITECTURE.md §16).
 *
 * <p>Vulkan's clip space differs from OpenGL's (Y inverted, Z in [0,1]); the caller passes
 * the backend type so the same camera produces the correct MVP for each API.
 */
public final class CameraMatrices {

    private static final float NEAR = 0.1f;
    private static final float FAR = 1000.0f;

    private CameraMatrices() {
    }

    /**
     * @param camera     engine camera snapshot
     * @param aspect     viewport width / height
     * @param vulkanClip true when the target backend is Vulkan (Y-flip + Z remap)
     * @return column-major MVP as 16 floats (identity model: world-space vertices)
     */
    public static float[] mvp(RenderWorld.Camera camera, float aspect, boolean vulkanClip) {
        float[] proj = perspective(radians(camera.fovDeg()), aspect, NEAR, FAR);
        float[] view = lookAt(camera.x(), camera.y(), camera.z(), camera.pitch(), camera.yaw());
        float[] pv = mul(proj, view);
        return vulkanClip ? vulkanClipCorrection(pv) : pv;
    }

    /** Minecraft camera → view matrix: yaw 0 faces +Z, positive pitch looks down. */
    private static float[] lookAt(float ex, float ey, float ez, float pitchDeg, float yawDeg) {
        float cp = (float) Math.cos(radians(pitchDeg));
        float sp = (float) Math.sin(radians(pitchDeg));
        float cy = (float) Math.cos(radians(yawDeg));
        float sy = (float) Math.sin(radians(yawDeg));
        // forward from pitch/yaw (Minecraft convention)
        float fx = -sy * cp;
        float fy = -sp;
        float fz = cy * cp;

        // right = normalize(cross(forward, worldUp)); at yaw 0 this is -X, matching the
        // standard gluLookAt construction so the view basis stays right-handed.
        float rx = -fz;
        float ry = 0.0f;
        float rz = fx;
        float rl = invSqrt(rx * rx + rz * rz);
        rx *= rl;
        rz *= rl;

        // up = cross(right, forward)
        float ux = ry * fz - rz * fy;
        float uy = rz * fx - rx * fz;
        float uz = rx * fy - ry * fx;

        // Column-major view matrix
        return new float[]{
                rx, ux, -fx, 0,
                ry, uy, -fy, 0,
                rz, uz, -fz, 0,
                -(rx * ex + ry * ey + rz * ez),
                -(ux * ex + uy * ey + uz * ez),
                (fx * ex + fy * ey + fz * ez),
                1
        };
    }

    private static float[] perspective(float fovRad, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(fovRad * 0.5));
        float inv = 1.0f / (near - far);
        return new float[]{
                f / aspect, 0, 0, 0,
                0, f, 0, 0,
                0, 0, (far + near) * inv, -1,
                0, 0, 2 * far * near * inv, 0
        };
    }

    /** Maps OpenGL clip (-1..1 Z, Y up) to Vulkan clip (0..1 Z, Y down). */
    private static float[] vulkanClipCorrection(float[] mvp) {
        // correction = diag(1, -1, 0.5, 1) then translate z by 0.5; apply left-multiply.
        return new float[]{
                mvp[0], -mvp[1], 0.5f * mvp[2], mvp[3],
                mvp[4], -mvp[5], 0.5f * mvp[6], mvp[7],
                mvp[8], -mvp[9], 0.5f * mvp[10], mvp[11],
                mvp[12], -mvp[13], 0.5f * mvp[14] + 0.5f * mvp[15], mvp[15]
        };
    }

    /** Column-major matrix multiply {@code a * b}. */
    private static float[] mul(float[] a, float[] b) {
        float[] r = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float s = 0;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + row] * b[col * 4 + k];
                }
                r[col * 4 + row] = s;
            }
        }
        return r;
    }

    private static float radians(float deg) {
        return (float) (deg * Math.PI / 180.0);
    }

    private static float invSqrt(float x) {
        return 1.0f / (float) Math.sqrt(x);
    }
}
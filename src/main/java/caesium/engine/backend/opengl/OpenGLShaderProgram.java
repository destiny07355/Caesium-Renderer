package caesium.engine.backend.opengl;

import org.lwjgl.opengl.GL33;

/**
 * Compiles and links an OpenGL shader program from GLSL sources. Owns the GL program
 * handle and closes it on release. Used by the OpenGL reference backend only; the engine
 * itself talks to {@code GpuPipeline}.
 */
public final class OpenGLShaderProgram implements AutoCloseable {

    private final int program;
    private final String name;

    public OpenGLShaderProgram(String name, String vertexSource, String fragmentSource) {
        this.name = name;
        int vertex = compile(GL33.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GL33.GL_FRAGMENT_SHADER, fragmentSource);
        try {
            int prog = GL33.glCreateProgram();
            GL33.glAttachShader(prog, vertex);
            GL33.glAttachShader(prog, fragment);
            GL33.glLinkProgram(prog);
            if (GL33.glGetProgrami(prog, GL33.GL_LINK_STATUS) == GL33.GL_FALSE) {
                String log = GL33.glGetProgramInfoLog(prog);
                GL33.glDeleteProgram(prog);
                throw new IllegalStateException("Caesium: failed to link shader '" + name + "': " + log);
            }
            this.program = prog;
        } finally {
            GL33.glDeleteShader(vertex);
            GL33.glDeleteShader(fragment);
        }
    }

    public int program() {
        return program;
    }

    public int uniformLocation(String uniform) {
        return GL33.glGetUniformLocation(program, uniform);
    }

    private static int compile(int type, String source) {
        int shader = GL33.glCreateShader(type);
        GL33.glShaderSource(shader, source);
        GL33.glCompileShader(shader);
        if (GL33.glGetShaderi(shader, GL33.GL_COMPILE_STATUS) == GL33.GL_FALSE) {
            String log = GL33.glGetShaderInfoLog(shader);
            GL33.glDeleteShader(shader);
            throw new IllegalStateException("Caesium: failed to compile shader: " + log);
        }
        return shader;
    }

    @Override
    public void close() {
        GL33.glDeleteProgram(program);
    }
}
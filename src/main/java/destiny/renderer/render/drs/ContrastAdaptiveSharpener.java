package destiny.renderer.render.drs;

public final class ContrastAdaptiveSharpener {

    public static final String CAS_FRAGMENT_SHADER = """
        #version 330 core
        
        uniform sampler2D u_Texture;
        uniform vec2 u_TexelSize;
        uniform float u_Sharpness;
        
        in vec2 v_TexCoord;
        out vec4 FragColor;
        
        void main() {
            vec3 a = texture(u_Texture, v_TexCoord + vec2(0.0, -u_TexelSize.y)).rgb;
            vec3 b = texture(u_Texture, v_TexCoord + vec2(-u_TexelSize.x, 0.0)).rgb;
            vec3 e = texture(u_Texture, v_TexCoord).rgb;
            vec3 d = texture(u_Texture, v_TexCoord + vec2(u_TexelSize.x, 0.0)).rgb;
            vec3 c = texture(u_Texture, v_TexCoord + vec2(0.0, u_TexelSize.y)).rgb;
            
            vec3 minRGB = min(min(min(a, b), min(c, d)), e);
            vec3 maxRGB = max(max(max(a, b), max(c, d)), e);
            
            vec3 ampRGB = clamp(min(minRGB, 2.0 - maxRGB) / max(maxRGB, 0.0001), 0.0, 1.0);
            vec3 wRGB = sqrt(ampRGB) * (-0.125 * u_Sharpness);
            
            vec3 filterResult = (a * wRGB + b * wRGB + c * wRGB + d * wRGB + e) / (4.0 * wRGB + 1.0);
            FragColor = vec4(clamp(filterResult, 0.0, 1.0), 1.0);
        }
        """;

    private ContrastAdaptiveSharpener() {}
}

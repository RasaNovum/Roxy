package net.rasanovum.roxy.shader;

public final class RoxyModelTintShader {
    private static final String AO_MARKER = "bool hasAO = isShaded;";
    private static final String AO_PATCH = "bool hasAO = isShaded || (model.flagsA & 32u) != 0u;";

    private RoxyModelTintShader() {}

    public static String patch(String path, String source) {
        if (path == null || source == null) return source;
        if (path.equals("/assets/voxy/shaders/lod/quad_util.glsl")) {
            if (source.contains(AO_PATCH)) return source;
            int index = source.indexOf(AO_MARKER);
            if (index < 0 || source.indexOf(AO_MARKER, index + AO_MARKER.length()) >= 0) {
                throw new IllegalStateException("Unsupported Voxy ambient occlusion shader");
            }
            return source.replace(AO_MARKER, AO_PATCH);
        }
        if (!path.equals("/assets/voxy/shaders/lod/gl46/quads.frag")) return source;
        source = replace(source, "vec4 tintTest = textureLod(blockModelAtlas, texturePos, 0);", "texturePos");
        return replace(source, "vec4 tintTest = texture(blockModelAtlas, texPos, -2);", "texPos");
    }

    private static String replace(String source, String marker, String uv) {
        String replacement = """
                #ifdef TRANSLUCENT
                """ + marker + """

                #else
                ivec2 roxyAtlasSize = textureSize(blockModelAtlas, 0);
                float tintFlag = texelFetch(blockModelAtlas, clamp(ivec2(
                """ + uv + """
                 * vec2(roxyAtlasSize)), ivec2(0), roxyAtlasSize - ivec2(1)), 0).a;
                vec4 tintTest = tintFlag > (254.5 / 255.0) ? vec4(1.0) : vec4(1.0, 0.0, 0.0, 1.0);
                #endif
                """;
        if (source.contains(replacement)) return source;
        int index = source.indexOf(marker);
        if (index < 0 || source.indexOf(marker, index + marker.length()) >= 0) {
            throw new IllegalStateException("Unsupported Voxy partial-tint shader");
        }
        return source.replace(marker, replacement);
    }
}

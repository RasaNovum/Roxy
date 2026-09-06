package net.rasanovum.roxy.compat;

public final class RoxyModelTintShader {
    private RoxyModelTintShader() {}

    public static String patch(String path, String source) {
        if (!path.equals("/assets/voxy/shaders/lod/gl46/quads.frag")) return source;
        source = replace(source, "vec4 tintTest = textureLod(blockModelAtlas, texturePos, 0);", "texturePos");
        return replace(source, "vec4 tintTest = texture(blockModelAtlas, texPos, -2);", "texPos");
    }

    private static String replace(String source, String marker, String uv) {
        int index = source.indexOf(marker);
        if (index < 0 || source.indexOf(marker, index + marker.length()) >= 0)
            throw new IllegalStateException("Unsupported Voxy partial-tint shader");
        return source.replace(marker, """
                #ifdef TRANSLUCENT
                """ + marker + """

                #else
                float tintFlag = texelFetch(blockModelAtlas, clamp(ivec2(
                """ + uv + """
                 * vec2(textureSize(blockModelAtlas, 0))), ivec2(0), textureSize(blockModelAtlas, 0) - ivec2(1)), 0).a;
                vec4 tintTest = tintFlag > (254.5 / 255.0) ? vec4(1.0) : vec4(1.0, 0.0, 0.0, 1.0);
                #endif
                """);
    }
}

package net.rasanovum.roxy.shader;

public final class RoxyLodFadeShader {
    private static final String STENCIL_PATH = "/assets/voxy/shaders/post/setup_stencil_depth.frag";
    private static final String FINAL_BLIT_PATH = "/assets/voxy/shaders/post/blit_texture_depth_cutout.frag";
    private static final String STENCIL_MARKER = "// ROXY_LOD_FADE_STENCIL";
    private static final String FINAL_MARKER = "// ROXY_LOD_FADE_FINAL";
    private static final String STENCIL_UNIFORMS = "layout(location = 6) uniform int roxyFadeEnabled;\n"
            + "layout(location = 7) uniform float roxyFadeStart;\n"
            + "layout(location = 8) uniform mat4 roxyFadeInvVanillaMvp;\n";
    private static final String FINAL_UNIFORMS = "layout(binding = 6) uniform sampler2D roxyFadeRealDepth;\n"
            + "layout(location = 6) uniform int roxyFadeEnabled;\n"
            + "layout(location = 7) uniform float roxyFadeStart;\n"
            + "layout(location = 8) uniform float roxyFadeEnd;\n"
            + "layout(location = 9) uniform mat4 roxyFadeInvVanillaMvp;\n";

    private RoxyLodFadeShader() {
    }

    public static String patch(String path, String source) {
        if (path == null || source == null) return source;
        if (path.equals(STENCIL_PATH)) return patchStencilDepth(source);
        if (path.equals(FINAL_BLIT_PATH)) return patchFinalBlit(source);
        return source;
    }

    public static String patchStencilDepth(String source) {
        if (source.contains(STENCIL_MARKER)) return source;
        source = replaceOnce(source,
                "layout(location = 1) uniform vec2 scaleFactor;\n",
                "layout(location = 1) uniform vec2 scaleFactor;\n" + STENCIL_MARKER + "\n" + STENCIL_UNIFORMS,
                "Unsupported Voxy setup-stencil shader uniforms");
        return replaceOnce(source,
                "void main() {\n    gl_FragDepth = NEAR;\n    if (texture(depthTex, UV*scaleFactor).r==FAR) {",
                "void main() {\n"
                        + "    // ROXY_LOD_FADE_STENCIL\n"
                        + "    float roxyFadeVanillaDepth = texture(depthTex, UV*scaleFactor).r;\n"
                        + "    if (roxyFadeEnabled != 0 && roxyFadeVanillaDepth > 0.0 && roxyFadeVanillaDepth < 1.0) {\n"
                        + "        vec2 roxyFadeUv = UV*scaleFactor;\n"
                        + "        vec4 roxyFadeClip = vec4(roxyFadeUv*2.0-1.0, roxyFadeVanillaDepth*2.0-1.0, 1.0);\n"
                        + "        vec4 roxyFadeCamera = roxyFadeInvVanillaMvp * roxyFadeClip;\n"
                        + "        float roxyFadeDistance = length(roxyFadeCamera.xz/roxyFadeCamera.w);\n"
                        + "        if (roxyFadeDistance > roxyFadeStart) discard;\n"
                        + "    }\n"
                        + "    gl_FragDepth = NEAR;\n"
                        + "    if (roxyFadeVanillaDepth==FAR) {",
                "Unsupported Voxy setup-stencil main body");
    }

    public static String patchFinalBlit(String source) {
        if (source.contains(FINAL_MARKER)) return source;
        source = replaceOnce(source,
                "layout(binding = 3) uniform sampler2D colourTex;\n",
                "layout(binding = 3) uniform sampler2D colourTex;\n"
                        + "    " + FINAL_MARKER + "\n"
                        + "    " + FINAL_UNIFORMS.replace("\n", "\n    ").stripTrailing() + "\n",
                "Unsupported Voxy final-blit colour declarations");
        source = replaceOnce(source,
                "    if (depth == 0.0f || depth == 1.0f) {\n        discard;\n    }\n",
                "    if (depth == 0.0f || depth == 1.0f) {\n        discard;\n    }\n"
                        + "    #ifdef EMIT_COLOUR\n"
                        + "    // ROXY_LOD_FADE_FINAL\n"
                        + "    float roxyFadeOpacity = 1.0;\n"
                        + "    float roxyFadeVanillaDepth = 1.0;\n"
                        + "    bool roxyFadeHasVanillaDepth = false;\n"
                        + "    if (roxyFadeEnabled != 0) {\n"
                        + "        roxyFadeVanillaDepth = texture(roxyFadeRealDepth, UV.xy).r;\n"
                        + "        roxyFadeHasVanillaDepth = roxyFadeVanillaDepth > 0.0 && roxyFadeVanillaDepth < 1.0;\n"
                        + "        if (roxyFadeHasVanillaDepth) {\n"
                        + "            vec4 roxyFadeClip = vec4(UV.xy*2.0-1.0, roxyFadeVanillaDepth*2.0-1.0, 1.0);\n"
                        + "            vec4 roxyFadeCamera = roxyFadeInvVanillaMvp * roxyFadeClip;\n"
                        + "            float roxyFadeDistance = length(roxyFadeCamera.xz/roxyFadeCamera.w);\n"
                        + "            if (roxyFadeDistance <= roxyFadeStart) discard;\n"
                        + "            roxyFadeOpacity = smoothstep(roxyFadeStart, roxyFadeEnd, roxyFadeDistance);\n"
                        + "        }\n"
                        + "    }\n"
                        + "    #endif\n",
                "Unsupported Voxy final-blit main body");
        return replaceOnce(source,
                "    #endif\n    #else\n    colour = vec4(0);",
                "    #endif\n"
                        + "    if (roxyFadeEnabled != 0) {\n"
                        + "        colour.a *= roxyFadeOpacity;\n"
                        + "        if (roxyFadeHasVanillaDepth) gl_FragDepth = roxyFadeVanillaDepth;\n"
                        + "    }\n"
                        + "    #else\n    colour = vec4(0);",
                "Unsupported Voxy final-blit fog/color boundary");
    }

    private static String replaceOnce(String source, String anchor, String replacement, String error) {
        int first = source.indexOf(anchor);
        if (first < 0 || source.indexOf(anchor, first + anchor.length()) >= 0) {
            throw new IllegalStateException(error);
        }
        return source.substring(0, first) + replacement + source.substring(first + anchor.length());
    }
}

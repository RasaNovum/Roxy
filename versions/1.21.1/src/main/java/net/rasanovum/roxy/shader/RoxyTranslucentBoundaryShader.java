package net.rasanovum.roxy.shader;

/** Adds a trans-only native-section mask to Voxy's exact GL46 quad shaders. */
public final class RoxyTranslucentBoundaryShader {
    private static final String VERTEX_PATH = "/assets/voxy/shaders/lod/gl46/quads3.vert";
    private static final String FRAGMENT_PATH = "/assets/voxy/shaders/lod/gl46/quads.frag";
    private static final String VERTEX_MARKER = "// ROXY_NATIVE_TRANSLUCENT_POSITION";
    private static final String FRAGMENT_MARKER = "// ROXY_NATIVE_TRANSLUCENT_MASK";
    private static final String FRAGMENT_TEST_MARKER = "// ROXY_NATIVE_TRANSLUCENT_TEST";

    private static final String VERTEX_OUTPUT = "#ifdef TRANSLUCENT\n"
            + "// ROXY_NATIVE_TRANSLUCENT_POSITION\n"
            + "layout(location = 8) out vec3 roxyTranslucentPosition;\n"
            + "#endif\n";

    private static final String VERTEX_POSITION = "#ifdef TRANSLUCENT\n"
            + "    // ROXY_NATIVE_TRANSLUCENT_POSITION\n"
            + "    vec2 roxyTranslucentCorner = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;\n"
            + "    roxyTranslucentPosition = quad.basePoint + swizzelDataAxis(quad.axis,\n"
            + "            vec3(quad.quadSizeAddin*roxyTranslucentCorner, 0.0));\n"
            + "#endif\n";

    private static final String FRAGMENT_INPUT = "#ifdef TRANSLUCENT\n"
            + "// ROXY_NATIVE_TRANSLUCENT_POSITION\n"
            + "layout(location = 8) in vec3 roxyTranslucentPosition;\n"
            + "#endif\n";

    private static final String FRAGMENT_UNIFORMS = "#ifdef TRANSLUCENT\n"
            + "// ROXY_NATIVE_TRANSLUCENT_MASK\n"
            + "layout(binding = 4) uniform usampler3D roxyNativeTranslucentMask;\n"
            + "uniform int roxyNativeTranslucentEnabled;\n"
            + "uniform ivec3 roxyNativeTranslucentOrigin;\n"
            + "uniform ivec3 roxyNativeTranslucentSize;\n"
            + "#endif\n";

    private static final String FRAGMENT_HELPER = "#ifdef TRANSLUCENT\n"
            + "bool roxyInNativeTranslucentSection() {\n"
            + "    if (roxyNativeTranslucentEnabled == 0) return false;\n"
            + "    uint roxyFace = getFace();\n"
            + "    vec3 roxyOutwardNormal = vec3(0.0);\n"
            + "    roxyOutwardNormal[int(roxyFace >> 1u)] = (roxyFace & 1u) != 0u ? 1.0 : -1.0;\n"
            + "    ivec3 roxyWorldSection = ivec3(floor((roxyTranslucentPosition\n"
            + "            - roxyOutwardNormal*0.002)/16.0)) + baseSectionPos*2;\n"
            + "    ivec3 roxyMaskCoord = roxyWorldSection - roxyNativeTranslucentOrigin;\n"
            + "    if (any(lessThan(roxyMaskCoord, ivec3(0)))\n"
            + "            || any(greaterThanEqual(roxyMaskCoord, roxyNativeTranslucentSize))) return false;\n"
            + "    return texelFetch(roxyNativeTranslucentMask, roxyMaskCoord, 0).r != 0u;\n"
            + "}\n"
            + "#endif\n";

    private static final String FRAGMENT_TEST = "#ifdef TRANSLUCENT\n"
            + "    // ROXY_NATIVE_TRANSLUCENT_TEST\n"
            + "    #ifndef PATCHED_SHADER\n"
            + "    if (roxyInNativeTranslucentSection()) discard;\n"
            + "    #endif\n"
            + "#endif\n";

    private RoxyTranslucentBoundaryShader() {
    }

    public static String patch(String path, String source) {
        if (path == null || source == null) return source;
        if (path.equals(VERTEX_PATH)) return patchVertex(source);
        if (path.equals(FRAGMENT_PATH)) return patchFragment(source);
        return source;
    }

    private static String patchVertex(String source) {
        if (source.contains(VERTEX_MARKER)) return source;
        source = replaceOnce(source,
                "#endif\n\n#ifdef USE_NV_JANK\n",
                "#endif\n\n" + VERTEX_OUTPUT + "\n#ifdef USE_NV_JANK\n",
                "Unsupported Voxy quad vertex output declarations");
        return replaceOnce(source,
                "    gl_Position =\n",
                VERTEX_POSITION + "\n    gl_Position =\n",
                "Unsupported Voxy quad vertex position anchor");
    }

    private static String patchFragment(String source) {
        if (source.contains(FRAGMENT_TEST_MARKER)) return source;
        if (source.contains(FRAGMENT_MARKER) || source.contains(VERTEX_MARKER)) {
            throw new IllegalStateException("Incomplete Voxy translucent-boundary shader patch");
        }
        source = replaceOnce(source,
                "#endif\n\n#ifdef DEBUG_RENDER\nlayout(location = 7) in flat uint quadDebug;\n#endif\n",
                "#endif\n\n" + FRAGMENT_INPUT
                        + "\n#ifdef DEBUG_RENDER\nlayout(location = 7) in flat uint quadDebug;\n#endif\n",
                "Unsupported Voxy quad fragment input declarations");
        source = replaceOnce(source,
                "#import <voxy:lod/gl46/bindings.glsl>\n#import <voxy:lod/lighting.glsl>\n",
                "#import <voxy:lod/gl46/bindings.glsl>\n" + FRAGMENT_UNIFORMS
                        + "#import <voxy:lod/lighting.glsl>\n",
                "Unsupported Voxy quad fragment mask declarations");
        source = replaceOnce(source,
                "uint getFace() {\n    return (interData.x>>4)&7u;\n}\n",
                "uint getFace() {\n    return (interData.x>>4)&7u;\n}\n\n" + FRAGMENT_HELPER,
                "Unsupported Voxy quad face helper");
        return replaceOnce(source,
                "    #ifndef PATCHED_SHADER\n    colour = computeColour(texPos, colour);\n",
                FRAGMENT_TEST + "\n    #ifndef PATCHED_SHADER\n    colour = computeColour(texPos, colour);\n",
                "Unsupported Voxy quad fragment emission boundary");
    }

    private static String replaceOnce(String source, String anchor, String replacement, String error) {
        int first = source.indexOf(anchor);
        if (first < 0 || source.indexOf(anchor, first + anchor.length()) >= 0) {
            throw new IllegalStateException(error);
        }
        return source.substring(0, first) + replacement + source.substring(first + anchor.length());
    }
}

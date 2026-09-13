package net.rasanovum.roxy.shader;

public final class RoxyIceFaceCullingShader {
    private static final String QUAD_UTIL_PATH = "/assets/voxy/shaders/lod/quad_util.glsl";
    private static final String QUADS_FRAGMENT_PATH = "/assets/voxy/shaders/lod/gl46/quads.frag";
    private static final String MODEL_FLAG_ANCHOR = "    uint flags = 0;\n";
    private static final String MODEL_FLAG_PATCH = "    flags |= uint((model.flagsA & 0x10u) != 0u) << 7;\n";
    private static final String FRAGMENT_ANCHOR =
            "        colour = textureGrad(blockModelAtlas, texPos, dx, dy);\n    }// else {";
    private static final String FRAGMENT_PATCH = """

        if ((interData.x & 0x80u) != 0u
                && (getFace() & 1u) != uint(gl_FrontFacing != ((getFace() >> 1u) != 0u))) {
            discard;
            return;
        }
        // else {""";

    private RoxyIceFaceCullingShader() {
    }

    public static String patch(String path, String source) {
        if (path == null || source == null) return source;
        if (path.equals(QUAD_UTIL_PATH)) return patchModelFlags(normalizeLineEndings(source));
        if (path.equals(QUADS_FRAGMENT_PATH)) return patchFragment(normalizeLineEndings(source));
        return source;
    }

    private static String normalizeLineEndings(String source) {
        return source.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String patchModelFlags(String source) {
        if (source.contains(MODEL_FLAG_PATCH)) return source;
        return replaceOnce(source, MODEL_FLAG_ANCHOR, MODEL_FLAG_ANCHOR + MODEL_FLAG_PATCH,
                "Unsupported Voxy quad flag layout");
    }

    private static String patchFragment(String source) {
        if (source.contains(FRAGMENT_PATCH)) return source;
        return replaceOnce(source, FRAGMENT_ANCHOR,
                "        colour = textureGrad(blockModelAtlas, texPos, dx, dy);\n    }"
                        + FRAGMENT_PATCH,
                "Unsupported Voxy quad fragment layout");
    }

    private static String replaceOnce(String source, String marker, String replacement, String error) {
        int first = source.indexOf(marker);
        if (first < 0 || source.indexOf(marker, first + marker.length()) >= 0) {
            throw new IllegalStateException(error);
        }
        return source.substring(0, first) + replacement + source.substring(first + marker.length());
    }
}

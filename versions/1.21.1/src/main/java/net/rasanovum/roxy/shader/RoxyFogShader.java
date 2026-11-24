package net.rasanovum.roxy.shader;

public final class RoxyFogShader {
    private static final String MARKER =
            "float fogLerp = clamp(fma(length(point.xyz),endParams.x,endParams.y),0,endParams.z);";
    private static final String PATCH = """
            float fogDistance = endParams.w > 1.5 ? max(length(point.xz), abs(point.y)) : length(point.xyz);
            float fogLerp = clamp(fma(fogDistance,endParams.x,endParams.y),0.0,endParams.z);
            if (endParams.w > 0.5) fogLerp = smoothstep(0.0, 1.0, fogLerp);
            """;

    private RoxyFogShader() {}

    public static String patch(String path, String source) {
        if (path == null || source == null) return source;
        if (!path.equals("/assets/voxy/shaders/post/blit_texture_depth_cutout.frag")) return source;
        if (source.contains(PATCH)) return source;
        int index = source.indexOf(MARKER);
        if (index < 0 || source.indexOf(MARKER, index + MARKER.length()) >= 0) {
            throw new IllegalStateException("Unsupported Voxy environmental fog shader");
        }
        return source.replace(MARKER, PATCH);
    }
}

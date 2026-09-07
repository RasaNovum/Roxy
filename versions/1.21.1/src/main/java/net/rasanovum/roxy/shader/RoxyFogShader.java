package net.rasanovum.roxy.shader;

public final class RoxyFogShader {
    private RoxyFogShader() {}

    public static String patch(String path, String source) {
        if (!path.equals("/assets/voxy/shaders/post/blit_texture_depth_cutout.frag")) return source;
        String marker = "float fogLerp = clamp(fma(length(point.xyz),endParams.x,endParams.y),0,endParams.z);";
        int index = source.indexOf(marker);
        if (index < 0 || source.indexOf(marker, index + marker.length()) >= 0)
            throw new IllegalStateException("Unsupported Voxy environmental fog shader");
        return source.replace(marker, """
                float fogDistance = endParams.w > 1.5 ? max(length(point.xz), abs(point.y)) : length(point.xyz);
                float fogLerp = clamp(fma(fogDistance,endParams.x,endParams.y),0,endParams.z);
                if (endParams.w > 0.5) fogLerp = smoothstep(0.0, 1.0, fogLerp);
                """);
    }
}

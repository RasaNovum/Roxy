package net.rasanovum.roxy.shader;

public final class RoxyTerrainFogShader {
    private RoxyTerrainFogShader() {}

    public static String patch(String path, String source) {
        if (path.equals("/assets/voxy/shaders/lod/gl46/quads3.vert")) return patchVertex(source);
        if (path.equals("/assets/voxy/shaders/lod/gl46/quads.frag")) return patchFragment(source);
        if (path.equals("/assets/voxy/shaders/post/ssao.comp")) return patchSsao(source);
        if (path.equals("/assets/voxy/shaders/post/blit_texture_depth_cutout.frag")) return patchBlit(source);
        return source;
    }

    private static String patchVertex(String source) {
        source = replaceOnce(source, "layout(location = 0) out flat uvec4 interData;", """
                layout(location = 0) out flat uvec4 interData;
                #ifdef TRANSLUCENT
                layout(location = 6) out vec3 roxyFogPoint;
                #endif""");
        return replaceOnce(source, "    interData = quad.attributeData;", """
                    interData = quad.attributeData;
                    #ifdef TRANSLUCENT
                    vec2 roxyFogMask = vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale;
                    roxyFogPoint = quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin*roxyFogMask, 0)) - cameraSubPos;
                    #endif""");
    }

    private static String patchFragment(String source) {
        source = replaceOnce(source, "layout(location = 0) in flat uvec4 interData;", """
                layout(location = 0) in flat uvec4 interData;
                #ifdef TRANSLUCENT
                layout(location = 6) in vec3 roxyFogPoint;
                layout(location = 40) uniform vec4 roxyTerrainFogEnd;
                layout(location = 41) uniform vec4 roxyTerrainFogColour;
                #endif""");
        return replaceOnce(source, "    colour = computeColour(texPos, colour);\n    outColour = colour;", """
                    colour = computeColour(texPos, colour);
                    #ifdef TRANSLUCENT
                    if (roxyTerrainFogColour.a > 0.0) {
                        float roxyFogDistance = roxyTerrainFogEnd.w > 1.5 ? max(length(roxyFogPoint.xz), abs(roxyFogPoint.y)) : length(roxyFogPoint);
                        float roxyFogLerp = clamp(fma(roxyFogDistance, roxyTerrainFogEnd.x, roxyTerrainFogEnd.y), 0.0, roxyTerrainFogEnd.z);
                        if (roxyTerrainFogEnd.w > 0.5) roxyFogLerp = smoothstep(0.0, 1.0, roxyFogLerp);
                        colour.rgb = mix(colour.rgb, roxyTerrainFogColour.rgb, roxyFogLerp*roxyTerrainFogColour.a);
                    }
                    #endif
                    outColour = colour;""");
    }

    private static String patchSsao(String source) {
        source = replaceOnce(source, "layout(binding = 2) uniform sampler2D depthTex;", """
                layout(binding = 2) uniform sampler2D depthTex;
                layout(location = 40) uniform vec4 roxyTerrainFogEnd;
                layout(location = 41) uniform vec4 roxyTerrainFogColour;""");
        return replaceOnce(source,
                "                ocolour.rgb *= computeAO(vec3(point, depth), worldNormal);\n            }", """
                            ocolour.rgb *= computeAO(vec3(point, depth), worldNormal);
                        }
                        if (roxyTerrainFogColour.a > 0.0) {
                #ifdef BETTER_SSAO
                            vec3 roxyFogPoint = transpose(mat3(MV)) * rev3d(invProj, vec3(point, depth));
                #else
                            vec3 roxyFogPoint = rev3d(invMVP, vec3(point, depth));
                #endif
                            float roxyFogDistance = roxyTerrainFogEnd.w > 1.5 ? max(length(roxyFogPoint.xz), abs(roxyFogPoint.y)) : length(roxyFogPoint);
                            float roxyFogLerp = clamp(fma(roxyFogDistance, roxyTerrainFogEnd.x, roxyTerrainFogEnd.y), 0.0, roxyTerrainFogEnd.z);
                            if (roxyTerrainFogEnd.w > 0.5) roxyFogLerp = smoothstep(0.0, 1.0, roxyFogLerp);
                            ocolour.rgb = mix(ocolour.rgb, roxyTerrainFogColour.rgb, roxyFogLerp*roxyTerrainFogColour.a);
                        }""");
    }

    private static String patchBlit(String source) {
        source = replaceOnce(source, "#ifdef EMIT_COLOUR\nlayout(binding = 3) uniform sampler2D colourTex;", """
                #ifdef EMIT_COLOUR
                layout(binding = 3) uniform sampler2D colourTex;
                layout(location = 40) uniform float roxyTerrainFogActive;""");
        return replaceOnce(source, "    if (colour.a == 0.0) {\n        discard;\n    }", """
                    if (colour.a == 0.0) {
                        discard;
                    }
                    if (roxyTerrainFogActive > 0.5 && colour.a < 1.0) colour.rgb /= colour.a;""");
    }

    private static String replaceOnce(String source, String marker, String replacement) {
        int index = source.indexOf(marker);
        if (index < 0 || source.indexOf(marker, index + marker.length()) >= 0) {
            throw new IllegalStateException("Unsupported Voxy terrain shader: " + marker.lines().findFirst().orElse(marker));
        }
        return source.substring(0, index) + replacement + source.substring(index + marker.length());
    }
}

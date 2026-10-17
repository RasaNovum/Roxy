package net.rasanovum.roxy.shader;

public final class RoxyIrisFadeShader {
    private static final String FRAGMENT = "/assets/voxy/shaders/lod/gl46/quads.frag";
    private static final String VERTEX = "/assets/voxy/shaders/lod/gl46/quads3.vert";
    private static final String MARKER = "// ROXY_IRIS_LOD_FADE";
    private static final String DECLARATION_MARKER = "// ROXY_IRIS_LOD_FADE_UNIFORMS";
    private static final String VERTEX_MARKER = "// ROXY_IRIS_FADE_WORLD_POSITION";
    private static final String UNIFORMS = """
            layout(binding = 3) uniform sampler2D roxyIrisFadeRealDepth;
            #ifdef TRANSLUCENT
            layout(binding = 5) uniform sampler2D roxyIrisFadeOpaqueDepth;
            uniform int roxyIrisFadeTranslucentEnabled;
            #endif
            uniform sampler2D roxyIrisFadeFullDepth;
            uniform int roxyIrisFadeDepthPass;
            uniform int roxyIrisFadeEnabled;
            uniform float roxyIrisFadeStart;
            uniform float roxyIrisFadeEnd;
            uniform mat4 roxyIrisFadeInvVanillaMvp;
            uniform vec2 roxyIrisFadeViewport;
            layout(location = 9) in vec3 roxyIrisFadePosition;

            float roxyIrisFadeThreshold(vec3 position, ivec3 base) {
                ivec3 cell = ivec3(floor((position + vec3((base & ivec3(127)) << 5)) * 4.0));
                uvec3 key = uvec3(cell & ivec3(16383));
                uint hash = key.x * 1664525u ^ key.y * 1013904223u ^ key.z * 2246822519u;
                hash ^= hash >> 16u;
                hash *= 2146121005u;
                hash ^= hash >> 15u;
                return float(hash & 16777215u) / 16777216.0;
            }
            """;
    private static final String FADE = """
                // ROXY_IRIS_LOD_FADE
                if (roxyIrisFadeDepthPass != 0) return;
                #ifdef TRANSLUCENT
                if (roxyIrisFadeTranslucentEnabled == 0) {
                    if (roxyInNativeTranslucentSection()) discard;
                } else
                #endif
                if (roxyIrisFadeEnabled != 0 && roxyIrisFadeEnd > roxyIrisFadeStart
                        && roxyIrisFadeViewport.x > 0.0 && roxyIrisFadeViewport.y > 0.0) {
                    vec2 roxyUv = gl_FragCoord.xy / roxyIrisFadeViewport;
                    float roxyFrontDepth = texture(roxyIrisFadeFullDepth, roxyUv).r;
                    // A discarded front surface reveals real terrain, rather than a deeper LOD face.
                    if (roxyFrontDepth > 0.0 && roxyFrontDepth < 1.0
                            && abs(gl_FragCoord.z - roxyFrontDepth) > 0.0000002) discard;
                    float roxyRealDepth = texture(roxyIrisFadeRealDepth, roxyUv).r;
                    #ifdef TRANSLUCENT
                    bool roxyNativeTranslucent = roxyInNativeTranslucentSection();
                    float roxySurfaceDistance = length((roxyIrisFadePosition - cameraSubPos).xz);
                    if (roxyNativeTranslucent && roxySurfaceDistance <= roxyIrisFadeStart) discard;
                    if (roxyRealDepth > 0.0 && roxyRealDepth < 1.0) {
                        float roxySelectedOpaque = texture(roxyIrisFadeOpaqueDepth, roxyUv).r;
                        if (roxySelectedOpaque <= 0.0 || roxySelectedOpaque >= 1.0) discard;
                    } else if (roxyNativeTranslucent) {
                        float roxyOpacity = smoothstep(roxyIrisFadeStart, roxyIrisFadeEnd, roxySurfaceDistance);
                        if (roxyIrisFadeThreshold(roxyIrisFadePosition, baseSectionPos) >= roxyOpacity) discard;
                    }
                    #else
                    if (roxyRealDepth > 0.0 && roxyRealDepth < 1.0) {
                        vec4 roxyPosition = roxyIrisFadeInvVanillaMvp
                                * vec4(roxyUv * 2.0 - 1.0, roxyRealDepth * 2.0 - 1.0, 1.0);
                        if (abs(roxyPosition.w) > 1e-6) {
                            float roxyDistance = length(roxyPosition.xz / roxyPosition.w);
                            if (roxyDistance <= roxyIrisFadeStart) discard;
                            float roxyOpacity = smoothstep(roxyIrisFadeStart, roxyIrisFadeEnd, roxyDistance);
                            if (roxyIrisFadeThreshold(roxyIrisFadePosition, baseSectionPos) >= roxyOpacity) discard;
                        }
                    }
                    #endif
                }
            """;

    private RoxyIrisFadeShader() {}

    public static String patch(String path, String source) {
        if (path == null || source == null) return source;
        if (VERTEX.equals(path)) return patchVertex(source);
        if (!FRAGMENT.equals(path) || !source.contains("#ifdef PATCHED_SHADER")) return source;
        if (source.contains(MARKER)) return source;
        source = replaceOnce(source, "#else\n\n//Bind the model buffer and import the model system as we need it\n",
                "#else\n\n" + DECLARATION_MARKER + "\n" + UNIFORMS
                        + "\n//Bind the model buffer and import the model system as we need it\n");
        return replaceOnce(source,
                "    voxy_emitFragment(VoxyFragmentParameters(colour, tile, texPos, face, modelId, getLightmapUv(interData.y), tint, model.customId));\n",
                FADE + "    voxy_emitFragment(VoxyFragmentParameters(colour, tile, texPos, face, modelId, getLightmapUv(interData.y), tint, model.customId));\n");
    }

    private static String patchVertex(String source) {
        if (source.contains(VERTEX_MARKER)) return source;
        source = replaceOnce(source, "#endif\n\n#ifdef USE_NV_JANK\n",
                "#endif\n\n#ifdef PATCHED_SHADER\n" + VERTEX_MARKER
                        + "\nlayout(location = 9) out vec3 roxyIrisFadePosition;\n#endif\n\n#ifdef USE_NV_JANK\n");
        return replaceOnce(source, "    uint cornerId = gl_VertexID&3;\n\n    gl_Position =\n",
                "    uint cornerId = gl_VertexID&3;\n\n#ifdef PATCHED_SHADER\n"
                        + "    roxyIrisFadePosition = quad.basePoint + swizzelDataAxis(quad.axis,\n"
                        + "            vec3(quad.quadSizeAddin*vec2((cornerId>>1)&1u, cornerId&1u)*quad.lodScale, 0.0));\n"
                        + "#endif\n\n    gl_Position =\n");
    }

    private static String replaceOnce(String source, String anchor, String replacement) {
        int first = source.indexOf(anchor);
        if (first < 0 || source.indexOf(anchor, first + anchor.length()) >= 0) {
            throw new IllegalStateException("Unsupported Voxy Iris fade shader anchor: " + anchor.strip());
        }
        return source.substring(0, first) + replacement + source.substring(first + anchor.length());
    }
}
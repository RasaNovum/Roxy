package net.rasanovum.roxy.shader;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RoxyRenderDistanceShader {
    private static final String VERTEX_MARKER = "ROXY_RENDER_DISTANCE_BLEND_VERTEX";
    private static final String FRAGMENT_MARKER = "ROXY_RENDER_DISTANCE_BLEND_FRAGMENT";
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");

    private RoxyRenderDistanceShader() {}

    public static String patchSodiumVertex(String source) {
        return patchVertex(source);
    }

    public static String patchSodiumFragment(String source) {
        return patchFragment(source, false);
    }

    public static String patchIrisVertex(String source) {
        return patchVertex(source);
    }

    public static String patchIrisFragment(String source) {
        return patchFragment(source, true);
    }

    private static String patchVertex(String source) {
        if (source == null || source.contains(VERTEX_MARKER)) return source;
        int main = mainStart(source);
        String initialization = "_vert_init();";
        if (main < 0
                || count(source, initialization) != 1
                || !source.contains("_draw_id")
                || !source.contains("_vert_position")
                || !source.contains("u_RegionOffset")
                || !source.contains("_get_draw_translation")) return source;
        String declarations = """
                // ROXY_RENDER_DISTANCE_BLEND_VERTEX
                uniform float roxy_SectionBlend[256];
                out float roxy_BlendDistance;
                flat out float roxy_SectionBlendValue;

                """;
        source = source.substring(0, main) + declarations + source.substring(main);
        return source.replace(
                initialization,
                initialization + "\n    roxy_BlendDistance = length(("
                        + "_vert_position + u_RegionOffset + _get_draw_translation(_draw_id)).xz);"
                        + "\n    roxy_SectionBlendValue = roxy_SectionBlend[int(_draw_id)];"
        );
    }

    private static String patchFragment(String source, boolean iris) {
        if (source == null || source.contains(FRAGMENT_MARKER)) return source;
        Matcher main = MAIN.matcher(maskComments(source));
        if (!main.find()) return source;
        int mainStart = main.start();
        if (main.find()) return source;

        int name = source.indexOf("main", mainStart);
        String renamed = source.substring(0, name) + "roxy_original_main" + source.substring(name + 4);
        String condition = (iris ? "iris_currentAlphaTest <= 0.0 && " : "")
                + "roxy_BlendEnabled != 0";
        String declaration = iris && !source.contains("iris_currentAlphaTest")
                ? "uniform float iris_currentAlphaTest;\n"
                : "";
        return renamed + """

                // ROXY_RENDER_DISTANCE_BLEND_FRAGMENT
                in float roxy_BlendDistance;
                flat in float roxy_SectionBlendValue;
                uniform float roxy_BlendStart;
                uniform float roxy_BlendEnd;
                uniform int roxy_BlendEnabled;
                """ + declaration + """
                float roxy_BlendDither(vec2 position) {
                    return fract(52.9829189 * fract(dot(floor(position), vec2(0.06711056, 0.00583715))));
                }

                void main() {
                    roxy_original_main();
                    #ifndef USE_FRAGMENT_DISCARD
                    if (%s
                            && roxy_SectionBlendValue > 0.5
                            && roxy_BlendDither(gl_FragCoord.xy) < smoothstep(
                                    roxy_BlendStart, roxy_BlendEnd, roxy_BlendDistance)) {
                        discard;
                    }
                    #endif
                }
                """.formatted(condition);
    }

    private static int mainStart(String source) {
        Matcher main = MAIN.matcher(maskComments(source));
        if (!main.find()) return -1;
        int result = main.start();
        return main.find() ? -1 : result;
    }

    private static int count(String source, String marker) {
        int result = 0;
        for (int index = 0; (index = source.indexOf(marker, index)) >= 0; index += marker.length()) result++;
        return result;
    }

    private static String maskComments(String source) {
        StringBuilder result = new StringBuilder(source);
        boolean line = false;
        boolean block = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (line) {
                if (current == '\n' || current == '\r') line = false;
                else result.setCharAt(index, ' ');
            } else if (block) {
                result.setCharAt(index, current == '\n' || current == '\r' ? current : ' ');
                if (current == '*' && next == '/') {
                    result.setCharAt(index + 1, ' ');
                    block = false;
                    index++;
                }
            } else if (current == '/' && next == '/') {
                result.setCharAt(index, ' ');
                result.setCharAt(index + 1, ' ');
                line = true;
                index++;
            } else if (current == '/' && next == '*') {
                result.setCharAt(index, ' ');
                result.setCharAt(index + 1, ' ');
                block = true;
                index++;
            }
        }
        return result.toString();
    }
}

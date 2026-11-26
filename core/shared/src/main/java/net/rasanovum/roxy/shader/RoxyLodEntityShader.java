package net.rasanovum.roxy.shader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import net.rasanovum.roxy.core.RoxyPlatform;
import org.slf4j.LoggerFactory;

public final class RoxyLodEntityShader {
    private static final String MARKER = "roxy_lod_entity_occlusion";
    private static final Pattern MAIN = Pattern.compile("(?m)^[ \\t]*void\\s+(?:main|_clrwl_shader_main)\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private static final Pattern VERSION = Pattern.compile("(?m)^[ \\t]*#\\s*version\\s+(\\d+)");
    private static final Pattern PREPROCESSOR_HEADER = Pattern.compile("(?m)^[ \\t]*#\\s*(?:version|extension)\\b[^\\n]*(?:\\n|$)");
    private static final AtomicBoolean IRIS_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean COLORWHEEL_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean LOW_VERSION_LOGGED = new AtomicBoolean();
    private static final Set<String> IRIS_ROLES = Set.of(
            "entities_alpha", "entities_solid", "entities_solid_diffuse", "entities_solid_bright",
            "entities_cutout", "entities_cutout_diffuse", "entities_translucent",
            "block_entity", "block_entity_bright", "block_entity_diffuse", "be_translucent",
            "moving_block", "gbuffers_entities", "gbuffers_entities_translucent",
            "gbuffers_block", "gbuffers_block_translucent"
    );
    private static final String DECLARATIONS = "uniform sampler2D " + RoxyLodEntityOcclusion.DEPTH_SAMPLER + ";\n"
            + "uniform usampler2D " + RoxyLodEntityOcclusion.STENCIL_SAMPLER + ";\n"
            + "uniform int " + RoxyLodEntityOcclusion.ENABLED_UNIFORM + ";\n"
            + "uniform mat4 " + RoxyLodEntityOcclusion.TRANSFORM_UNIFORM + ";\n"
            + "uniform vec4 " + RoxyLodEntityOcclusion.VIEWPORT_UNIFORM + ";\n"
            + "uniform int " + RoxyLodEntityOcclusion.REVERSE_Z_UNIFORM + ";\n"
            + "uniform int " + RoxyLodEntityOcclusion.ZERO_ONE_UNIFORM + ";\n"
            + "void roxy_lod_entity_occlusion() {\n"
            + "    if (" + RoxyLodEntityOcclusion.ENABLED_UNIFORM + " == 0) return;\n"
            + "    vec2 roxyUv = (gl_FragCoord.xy - " + RoxyLodEntityOcclusion.VIEWPORT_UNIFORM
            + ".xy) / " + RoxyLodEntityOcclusion.VIEWPORT_UNIFORM + ".zw;\n"
            + "    if (any(lessThan(roxyUv, vec2(0.0))) || any(greaterThanEqual(roxyUv, vec2(1.0)))) return;\n"
            + "    ivec2 roxySize = textureSize(" + RoxyLodEntityOcclusion.DEPTH_SAMPLER + ", 0);\n"
            + "    ivec2 roxyTexel = clamp(ivec2(roxyUv * vec2(roxySize)), ivec2(0), roxySize - ivec2(1));\n"
            + "    uint roxyStencil = texelFetch(" + RoxyLodEntityOcclusion.STENCIL_SAMPLER + ", roxyTexel, 0).r;\n"
            + "    if ((roxyStencil & 1u) == 0u) return;\n"
            + "    float roxyLodDepth = texelFetch(" + RoxyLodEntityOcclusion.DEPTH_SAMPLER + ", roxyTexel, 0).r;\n"
            + "    if (" + RoxyLodEntityOcclusion.REVERSE_Z_UNIFORM + " != 0 && roxyLodDepth <= 0.000001) return;\n"
            + "    if (" + RoxyLodEntityOcclusion.REVERSE_Z_UNIFORM + " == 0 && roxyLodDepth >= 0.999999) return;\n"
            + "    float roxyLodNdcDepth = " + RoxyLodEntityOcclusion.ZERO_ONE_UNIFORM + " != 0 ? roxyLodDepth : roxyLodDepth * 2.0 - 1.0;\n"
            + "    vec4 roxyNdc = vec4(roxyUv * 2.0 - 1.0, roxyLodNdcDepth, 1.0);\n"
            + "    vec4 roxyClip = " + RoxyLodEntityOcclusion.TRANSFORM_UNIFORM + " * roxyNdc;\n"
            + "    if (roxyClip.w <= 0.000001) return;\n"
            + "    float roxyMainDepth = roxyClip.z / roxyClip.w * 0.5 + 0.5;\n"
            + "    if (roxyMainDepth < 0.0 || roxyMainDepth > 1.0) return;\n"
            + "    bool roxyBehind = gl_FragCoord.z > roxyMainDepth + 5.9604645e-8;\n"
            + "    if (roxyBehind) discard;\n"
            + "}\n";

    private RoxyLodEntityShader() {
    }

    public static boolean isIrisRole(String name) {
        if (!createPresent() || name == null) {
            return false;
        }
        String role = name.toLowerCase(Locale.ROOT);
        return IRIS_ROLES.contains(role);
    }

    public static boolean isColorwheelRole(Object id) {
        if (!createPresent()) {
            return false;
        }
        String role = id == null ? "" : id.toString().toLowerCase(Locale.ROOT);
        return role.equals("gbuffers")
                || role.equals("gbuffers_translucent")
                || role.equals("gbuffers_damagedblock")
                || role.equals("clrwl_gbuffers")
                || role.equals("clrwl_gbuffers_translucent")
                || role.equals("clrwl_gbuffers_damagedblock");
    }

    public static String patchIris(String name, String source) {
        if (!isIrisRole(name)) return source;
        String result = patch(source);
        if (!Objects.equals(result, source) && IRIS_LOGGED.compareAndSet(false, true)) {
            LoggerFactory.getLogger("Roxy").info("Create LOD occlusion patched Iris shader {}", name);
        }
        return result;
    }

    public static String patchColorwheel(String name, String source) {
        if (!isColorwheelRole(name)) return source;
        String result = patch(source);
        if (!Objects.equals(result, source) && COLORWHEEL_LOGGED.compareAndSet(false, true)) {
            LoggerFactory.getLogger("Roxy").info("Create LOD occlusion patched Colorwheel shader {}", name);
        }
        return result;
    }

    public static Object patchColorwheelResult(Object result, Object programId) {
        if (result == null || !createPresent() || (programId != null && !isColorwheelRole(programId))) {
            return result;
        }
        try {
            Object fragment = accessor(result, "fragment");
            if (fragment == null) {
                return result;
            }
            Object source = accessor(fragment, "code");
            if (!(source instanceof String original)) {
                return result;
            }
            String patched = programId == null
                    ? patch(original)
                    : patchColorwheel(programId.toString(), original);
            if (Objects.equals(patched, original)) {
                return result;
            }
            Object patchedFragment = rebuild(fragment, patched, "code");
            return rebuild(result, patchedFragment, "fragment");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return result;
        }
    }

    private static boolean createPresent() {
        return RoxyPlatform.isModLoaded("create");
    }

    static String patch(String source) {
        if (source == null || source.contains(MARKER)) {
            return source;
        }
        String masked = maskComments(source);
        int glslVersion = parseGlslVersion(masked);
        if (glslVersion < 130) {
            if (LOW_VERSION_LOGGED.compareAndSet(false, true)) {
                LoggerFactory.getLogger("Roxy").warn(
                        "Skipping Create LOD entity occlusion shader patch for GLSL version {}",
                        glslVersion
                );
            }
            return source;
        }

        Matcher mainMatcher = MAIN.matcher(masked);
        List<int[]> mainRanges = new ArrayList<>();
        while (mainMatcher.find()) {
            mainRanges.add(new int[]{mainMatcher.start(), mainMatcher.end()});
        }
        if (mainRanges.isEmpty()) {
            return source;
        }

        int insertAt = findDeclarationInsertionIndex(masked, mainRanges.get(0)[0]);

        StringBuilder output = new StringBuilder(source.length() + DECLARATIONS.length() + mainRanges.size() * 34);
        int cursor = 0;
        boolean declarationsInserted = false;
        for (int[] range : mainRanges) {
            if (!declarationsInserted && insertAt <= range[0]) {
                output.append(source, cursor, insertAt);
                if (insertAt > 0 && source.charAt(insertAt - 1) != '\n') output.append('\n');
                output.append(DECLARATIONS);
                cursor = insertAt;
                declarationsInserted = true;
            }
            output.append(source, cursor, range[1]);
            output.append("\n    roxy_lod_entity_occlusion();");
            cursor = range[1];
        }
        output.append(source, cursor, source.length());
        return output.toString();
    }

    private static int parseGlslVersion(String masked) {
        Matcher matcher = VERSION.matcher(masked);
        if (!matcher.find()) return 110;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 110;
        }
    }

    private static int findDeclarationInsertionIndex(String masked, int fallback) {
        int conditionalDepth = 0;
        int insertAt = -1;
        int lineStart = 0;
        int length = masked.length();
        while (lineStart < length && lineStart < fallback) {
            int lineEnd = masked.indexOf('\n', lineStart);
            int nextStart = lineEnd < 0 ? length : lineEnd + 1;
            String line = masked.substring(lineStart, lineEnd < 0 ? length : lineEnd).strip();
            if (line.startsWith("#")) {
                String directive = line.substring(1).stripLeading();
                if (directive.startsWith("if")) {
                    conditionalDepth++;
                } else if (directive.startsWith("endif")) {
                    if (conditionalDepth > 0) conditionalDepth--;
                } else if (conditionalDepth == 0 && PREPROCESSOR_HEADER.matcher(masked.substring(lineStart, nextStart)).matches()) {
                    insertAt = nextStart;
                }
            }
            lineStart = nextStart;
        }
        return insertAt >= 0 ? insertAt : fallback;
    }

    private static String maskComments(String source) {
        char[] chars = source.toCharArray();
        int length = chars.length;
        int i = 0;
        while (i < length - 1) {
            if (chars[i] == '/' && chars[i + 1] == '/') {
                chars[i] = ' ';
                chars[i + 1] = ' ';
                i += 2;
                while (i < length && chars[i] != '\n') {
                    if (chars[i] != '\r') chars[i] = ' ';
                    i++;
                }
            } else if (chars[i] == '/' && chars[i + 1] == '*') {
                chars[i] = ' ';
                chars[i + 1] = ' ';
                i += 2;
                while (i < length) {
                    if (i < length - 1 && chars[i] == '*' && chars[i + 1] == '/') {
                        chars[i] = ' ';
                        chars[i + 1] = ' ';
                        i += 2;
                        break;
                    }
                    if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
                    i++;
                }
            } else {
                i++;
            }
        }
        return new String(chars);
    }

    private static Object accessor(Object value, String name) throws ReflectiveOperationException {
        Method method = value.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(value);
    }

    private static Object rebuild(Object record, Object replacement, String replacementName)
            throws ReflectiveOperationException {
        Class<?> type = record.getClass();
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            return record;
        }
        Object[] arguments = new Object[components.length];
        Class<?>[] argumentTypes = new Class<?>[components.length];
        boolean matched = false;
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            Method componentAccessor = component.getAccessor();
            componentAccessor.setAccessible(true);
            arguments[i] = componentAccessor.invoke(record);
            argumentTypes[i] = component.getType();
            if (component.getName().equals(replacementName)) {
                arguments[i] = replacement;
                matched = true;
            }
        }
        if (!matched) {
            return record;
        }
        Constructor<?> constructor = type.getDeclaredConstructor(argumentTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }
}

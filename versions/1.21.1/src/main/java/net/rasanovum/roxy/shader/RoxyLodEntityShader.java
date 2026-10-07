package net.rasanovum.roxy.shader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.rasanovum.roxy.compat.RoxyLodEntityOcclusion;
import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.LoggerFactory;

public final class RoxyLodEntityShader {
    private static final String MARKER = "roxy_lod_entity_occlusion";
    private static final Pattern MAIN = Pattern.compile("(?m)(^[ \\t]*void\\s+(?:main|_clrwl_shader_main)\\s*\\(\\s*\\)\\s*\\{)");
    private static boolean irisLogged, colorwheelLogged;
    private static final Set<String> IRIS_ROLES = Set.of(
            "entities_alpha", "entities_solid", "entities_solid_diffuse", "entities_solid_bright",
            "entities_cutout", "entities_cutout_diffuse", "entities_translucent",
            "block_entity", "block_entity_bright", "block_entity_diffuse", "be_translucent",
            "moving_block", "gbuffers_entities", "gbuffers_entities_translucent",
            "gbuffers_block", "gbuffers_block_translucent"
    );

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
        if (!irisLogged && !java.util.Objects.equals(result, source)) {
            irisLogged = true;
            LoggerFactory.getLogger("Roxy").info("Create LOD occlusion patched Iris shader {}", name);
        }
        return result;
    }

    public static String patchColorwheel(String name, String source) {
        if (!isColorwheelRole(name)) return source;
        String result = patch(source);
        if (!colorwheelLogged && !java.util.Objects.equals(result, source)) {
            colorwheelLogged = true;
            LoggerFactory.getLogger("Roxy").info("Create LOD occlusion patched Colorwheel shader {}", name);
        }
        return result;
    }

    public static Object patchColorwheelResult(Object result, Object programId) {
        if (result == null || (programId != null && !isColorwheelRole(programId))) {
            return result;
        }
        try {
            Object fragment = accessor(result, "fragment");
            Object source = accessor(fragment, "code");
            if (!(source instanceof String)) {
                return result;
            }
            Object patchedFragment = rebuild(fragment, programId == null
                    ? patch((String) source)
                    : patchColorwheel(programId.toString(), (String) source));
            return rebuild(result, patchedFragment, "fragment");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return result;
        }
    }

    public static Object patchColorwheelResult(Object result) {
        return patchColorwheelResult(result, null);
    }

    private static boolean createPresent() {
        try {
            LoadingModList mods = LoadingModList.get();
            return mods != null && mods.getModFileById("create") != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static String patch(String source) {
        if (source == null || source.contains(MARKER)) {
            return source;
        }
        String declarations = "uniform sampler2D " + RoxyLodEntityOcclusion.DEPTH_SAMPLER + ";\n"
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
                + "    vec4 roxyNdc = vec4(roxyUv * 2.0 - 1.0, gl_FragCoord.z * 2.0 - 1.0, 1.0);\n"
                + "    vec4 roxyClip = " + RoxyLodEntityOcclusion.TRANSFORM_UNIFORM + " * roxyNdc;\n"
                + "    if (roxyClip.w <= 0.000001) return;\n"
                + "    float roxyDepth = roxyClip.z / roxyClip.w;\n"
                + "    if (" + RoxyLodEntityOcclusion.ZERO_ONE_UNIFORM + " == 0) roxyDepth = roxyDepth * 0.5 + 0.5;\n"
                + "    if (roxyDepth < 0.0 || roxyDepth > 1.0) return;\n"
                + "    ivec2 roxyTexel = ivec2(roxyUv * vec2(textureSize(" + RoxyLodEntityOcclusion.DEPTH_SAMPLER + ", 0)));\n"
                + "    uint roxyStencil = texelFetch(" + RoxyLodEntityOcclusion.STENCIL_SAMPLER + ", roxyTexel, 0).r;\n"
                + "    if ((roxyStencil & 1u) == 0u) return;\n"
                + "    float roxyLodDepth = texelFetch(" + RoxyLodEntityOcclusion.DEPTH_SAMPLER + ", roxyTexel, 0).r;\n"
                + "    if (" + RoxyLodEntityOcclusion.REVERSE_Z_UNIFORM + " != 0 && roxyLodDepth <= 0.000001) return;\n"
                + "    if (" + RoxyLodEntityOcclusion.REVERSE_Z_UNIFORM + " == 0 && roxyLodDepth >= 0.999999) return;\n"
                + "    bool roxyBehind = " + RoxyLodEntityOcclusion.REVERSE_Z_UNIFORM + " != 0"
                + " ? roxyDepth < roxyLodDepth - 0.000001 : roxyDepth > roxyLodDepth + 0.000001;\n"
                + "    if (roxyBehind) discard;\n"
                + "}\n";
        Matcher matcher = MAIN.matcher(source);
        if (!matcher.find()) {
            return source;
        }
        String withDeclarations = source.substring(0, matcher.start()) + declarations + source.substring(matcher.start());
        Matcher mainWithDeclarations = MAIN.matcher(withDeclarations);
        if (!mainWithDeclarations.find()) {
            return source;
        }
        return mainWithDeclarations.replaceFirst(Matcher.quoteReplacement(
                mainWithDeclarations.group(1) + "\n    roxy_lod_entity_occlusion();"));
    }

    private static Object accessor(Object value, String name) throws ReflectiveOperationException {
        Method method = value.getClass().getMethod(name);
        return method.invoke(value);
    }

    private static Object rebuild(Object record, Object replacement) throws ReflectiveOperationException {
        return rebuild(record, replacement, null);
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
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            arguments[i] = component.getAccessor().invoke(record);
            argumentTypes[i] = component.getType();
            if (replacementName == null || component.getName().equals(replacementName)
                    || (replacementName == null && i == 0)) {
                if (replacementName == null && i != 0) {
                    continue;
                }
                arguments[i] = replacement;
            }
        }
        Constructor<?> constructor = type.getDeclaredConstructor(argumentTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }
}

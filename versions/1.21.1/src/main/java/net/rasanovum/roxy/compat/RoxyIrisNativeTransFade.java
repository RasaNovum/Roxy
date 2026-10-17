package net.rasanovum.roxy.compat;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

public final class RoxyIrisNativeTransFade {
    public static final String ENABLED = "roxyIrisNativeTransFadeEnabled";
    public static final String START = "roxyIrisNativeTransFadeStart";
    public static final String END = "roxyIrisNativeTransFadeEnd";
    public static final String INVERSE_NATIVE_MVP = "roxyIrisNativeTransFadeInverseNativeMvp";
    public static final String INVERSE_NATIVE_PROJECTION = "roxyIrisNativeTransFadeInverseNativeProjection";
    public static final String INVERSE_LOD_PROJECTION = "roxyIrisNativeTransFadeInverseLodProjection";
    public static final String VIEWPORT = "roxyIrisNativeTransFadeViewport";
    public static final String OPAQUE_DEPTH = "roxyIrisNativeTransFadeOpaqueDepth";
    public static final String TRANSLUCENT_DEPTH = "roxyIrisNativeTransFadeTranslucentDepth";
    private static volatile Frame frame;
    private static volatile boolean waterProgramPatched;
    private static volatile boolean samplersRegistered;
    private static final Set<String> DIAGNOSTICS = ConcurrentHashMap.newKeySet();

    private RoxyIrisNativeTransFade() {
    }

    public static void beginFrame() {
        frame = null;
    }

    public static void reset() {
        frame = null;
        waterProgramPatched = false;
        samplersRegistered = false;
    }

    public static void invalidateFrame() {
        frame = null;
    }

    public static void publish(boolean enabled, float start, float end,
                               float[] inverseNativeMvp, float[] inverseLodProjection,
                               int opaqueDepthId, int translucentDepthId, int width, int height) {
        publish(enabled, start, end, inverseNativeMvp, inverseNativeMvp, inverseLodProjection,
                opaqueDepthId, translucentDepthId, width, height);
    }

    public static void publish(boolean enabled, float start, float end,
                               float[] inverseNativeMvp, float[] inverseNativeProjection,
                               float[] inverseLodProjection,
                               int opaqueDepthId, int translucentDepthId, int width, int height) {
        if (!enabled || end <= start || inverseNativeMvp == null || inverseNativeMvp.length != 16
                || inverseNativeProjection == null || inverseNativeProjection.length != 16
                || inverseLodProjection == null || inverseLodProjection.length != 16
                || opaqueDepthId == 0 || translucentDepthId == 0 || width <= 0 || height <= 0) {
            frame = null;
            logOnce("publish-invalid", "Publish rejected: enabled=" + enabled + " range=" + start + ".." + end
                    + " depths=" + opaqueDepthId + "/" + translucentDepthId + " size=" + width + "x" + height);
            return;
        }
        float[] nativeMatrix = inverseNativeMvp.clone();
        float[] nativeProjectionMatrix = inverseNativeProjection.clone();
        float[] lodMatrix = inverseLodProjection.clone();
        for (float value : nativeMatrix) {
            if (!Float.isFinite(value)) {
                frame = null;
                return;
            }
        }
        for (float value : nativeProjectionMatrix) {
            if (!Float.isFinite(value)) {
                frame = null;
                return;
            }
        }
        for (float value : lodMatrix) {
            if (!Float.isFinite(value)) {
                frame = null;
                return;
            }
        }
        frame = new Frame(start, end, nativeMatrix, nativeProjectionMatrix, lodMatrix,
                opaqueDepthId, translucentDepthId, width, height);
        logOnce("publish", "Published frame range=" + start + ".." + end + " depths=" + opaqueDepthId + "/"
                + translucentDepthId + " size=" + width + "x" + height + " helperLoader=" + loaderId());
    }

    public static void publish(boolean enabled, float start, float end,
                               Matrix4fc inverseNativeMvp, Matrix4fc inverseLodProjection,
                               int opaqueDepthId, int translucentDepthId, int width, int height) {
        if (inverseNativeMvp == null || inverseLodProjection == null) {
            frame = null;
            return;
        }
        publish(enabled, start, end, new Matrix4f(inverseNativeMvp).get(new float[16]),
                new Matrix4f(inverseLodProjection).get(new float[16]), opaqueDepthId, translucentDepthId, width, height);
    }

    public static void publish(boolean enabled, float start, float end,
                               Matrix4fc inverseNativeMvp, Matrix4fc inverseNativeProjection,
                               Matrix4fc inverseLodProjection,
                               int opaqueDepthId, int translucentDepthId, int width, int height) {
        if (inverseNativeMvp == null || inverseNativeProjection == null || inverseLodProjection == null) {
            frame = null;
            return;
        }
        publish(enabled, start, end, new Matrix4f(inverseNativeMvp).get(new float[16]),
                new Matrix4f(inverseNativeProjection).get(new float[16]),
                new Matrix4f(inverseLodProjection).get(new float[16]),
                opaqueDepthId, translucentDepthId, width, height);
    }

    public static boolean canFadeNativeTranslucency() {
        boolean active = waterProgramPatched && samplersRegistered && frame != null;
        if (active) {
            Frame current = frame;
            logOnce("native-fade-active", "Native water fade active for frame range=" + current.start + ".."
                    + current.end + " depthIds=" + current.opaqueDepthId + "/" + current.translucentDepthId
                    + " size=" + current.width + "x" + current.height + " helperLoader=" + loaderId());
        }
        return active;
    }

    public static String patchLateNativeFragment(String programId, String stage, String source) {
        if (!("Water".equals(programId) || "translucent".equals(programId))
                || !"FRAGMENT".equals(stage) || source == null) return source;
        logOnce("late-source-entry", "Late shader hook entered program=" + programId + " stage=" + stage
                + " sourceLength=" + source.length() + " helperLoader=" + loaderId());
        String marker = "// ROXY_LATE_NATIVE_TRANS_FADE";
        if (source.contains(marker)) {
            waterProgramPatched = true;
            return source;
        }
        java.util.regex.Matcher main = java.util.regex.Pattern
                .compile("(?m)^[ \\t]*void\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{").matcher(source);
        if (!main.find()) {
            waterProgramPatched = false;
            logOnce("late-source-main-missing", "Late native water source has no main() function.");
            return source;
        }
        int mainStart = main.start();
        int mainEnd = main.end();
        if (main.find()) {
            waterProgramPatched = false;
            logOnce("late-source-main-ambiguous", "Late native water source has multiple main() functions.");
            return source;
        }
        String renamed = source.substring(0, mainStart) + "void roxyNativeOriginalMain() {"
                + source.substring(mainEnd);
        String patched = renamed + "\n\n" + lateWrapperUniforms() + "\n" + lateWrapperMain(marker);
        waterProgramPatched = true;
        logOnce("late-source-patched", "Patched the post-transform Iris water fragment main wrapper.");
        return patched;
    }

    public static void applyNativeUniforms() {
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program == 0) return;
        int enabledLocation = GL20C.glGetUniformLocation(program, ENABLED);
        if (enabledLocation < 0) return;
        Frame current = frame;
        boolean enabled = canFadeNativeTranslucency() && current != null;
        GL20C.glUniform1i(enabledLocation, enabled ? 1 : 0);
        int startLocation = GL20C.glGetUniformLocation(program, START);
        int endLocation = GL20C.glGetUniformLocation(program, END);
        int nativeMatrixLocation = GL20C.glGetUniformLocation(program, INVERSE_NATIVE_MVP);
        int nativeProjectionLocation = GL20C.glGetUniformLocation(program, INVERSE_NATIVE_PROJECTION);
        int lodMatrixLocation = GL20C.glGetUniformLocation(program, INVERSE_LOD_PROJECTION);
        int viewportLocation = GL20C.glGetUniformLocation(program, VIEWPORT);
        if (!enabled) return;
        if (startLocation >= 0) GL20C.glUniform1f(startLocation, current.start);
        if (endLocation >= 0) GL20C.glUniform1f(endLocation, current.end);
        if (nativeMatrixLocation >= 0) GL20C.glUniformMatrix4fv(nativeMatrixLocation, false, current.inverseNativeMvp);
        if (nativeProjectionLocation >= 0) GL20C.glUniformMatrix4fv(nativeProjectionLocation, false, current.inverseNativeProjection);
        if (lodMatrixLocation >= 0) GL20C.glUniformMatrix4fv(lodMatrixLocation, false, current.inverseLodProjection);
        if (viewportLocation >= 0) GL20C.glUniform2f(viewportLocation, current.width, current.height);
        logOnce("native-bound", "Applied the native translucent fade to bound chunk shader " + program);
    }

    public static void registerSamplers(Object holder) {
        if (holder == null) return;
        boolean opaqueDeclared = declaresSampler(holder, OPAQUE_DEPTH);
        boolean translucentDeclared = declaresSampler(holder, TRANSLUCENT_DEPTH);
        if (!opaqueDeclared || !translucentDeclared) {
            logOnce("sampler-not-declared", "Iris sampler hook saw " + holder.getClass().getName() + " holderLoader="
                    + loaderId(holder.getClass().getClassLoader()) + " but declarations were "
                    + opaqueDeclared + "/" + translucentDeclared);
            return;
        }
        try {
            ClassLoader loader = holder.getClass().getClassLoader();
            Class<?> textureType = Class.forName("net.irisshaders.iris.gl.texture.TextureType", false, loader);
            Object texture2d = textureType.getField("TEXTURE_2D").get(null);
            Class<?> samplerType = Class.forName("net.irisshaders.iris.gl.sampler.GlSampler", false, loader);
            Object nearest = samplerType.getField("NEAREST").get(null);
            boolean opaqueAdded = RoxyIrisCompat.addDynamicSampler(holder, texture2d,
                    (IntSupplier) () -> textureId(false), RoxyIrisCompat.samplerSupplier(nearest),
                    new String[]{OPAQUE_DEPTH});
            boolean translucentAdded = RoxyIrisCompat.addDynamicSampler(holder, texture2d,
                    (IntSupplier) () -> textureId(true), RoxyIrisCompat.samplerSupplier(nearest),
                    new String[]{TRANSLUCENT_DEPTH});
            samplersRegistered = opaqueAdded && translucentAdded;
            logOnce("sampler-result", "Dynamic depth samplers registered opaque/translucent="
                    + opaqueAdded + "/" + translucentAdded + " holderLoader="
                    + loaderId(holder.getClass().getClassLoader()));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            logOnce("sampler-failed", "Could not register Iris depth samplers: " + ignored);
        }
    }

    private static void logOnce(String key, String message) {
        if (Boolean.getBoolean("roxy.fade.debug") && DIAGNOSTICS.add(key)) {
            System.err.println("[RoxyIrisNativeTransFade] " + message);
        }
    }

    private static String loaderId() {
        return loaderId(RoxyIrisNativeTransFade.class.getClassLoader());
    }

    private static String loaderId(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static boolean declaresSampler(Object holder, String name) {
        try {
            for (Method method : holder.getClass().getMethods()) {
                if (method.getParameterCount() == 1 && method.getName().equals("hasSampler")) {
                    return Boolean.TRUE.equals(method.invoke(holder, name));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return false;
    }

    private static int textureId(boolean translucent) {
        Frame current = frame;
        if (current == null) return 0;
        return translucent ? current.translucentDepthId : current.opaqueDepthId;
    }

    private static String lateWrapperUniforms() {
        return "// ROXY_LATE_NATIVE_TRANS_FADE_UNIFORMS\n"
            + "uniform int " + ENABLED + ";\n"
            + "uniform float " + START + ";\n"
            + "uniform float " + END + ";\n"
            + "uniform mat4 " + INVERSE_NATIVE_MVP + ";\n"
            + "uniform mat4 " + INVERSE_NATIVE_PROJECTION + ";\n"
            + "uniform mat4 " + INVERSE_LOD_PROJECTION + ";\n"
            + "uniform vec2 " + VIEWPORT + ";\n"
            + "uniform sampler2D " + OPAQUE_DEPTH + ";\n"
            + "uniform sampler2D " + TRANSLUCENT_DEPTH + ";\n";
    }

    private static String lateWrapperMain(String marker) {
        return "void main() {\n"
                + "    // " + marker + "\n"
                + "    if (" + ENABLED + " != 0 && " + END + " > " + START + ") {\n"
                + "        vec2 roxyNativeUv = gl_FragCoord.xy / " + VIEWPORT + ";\n"
                + "        float roxyOpaqueDepth = texture(" + OPAQUE_DEPTH + ", roxyNativeUv).r;\n"
                + "        float roxyTransDepth = texture(" + TRANSLUCENT_DEPTH + ", roxyNativeUv).r;\n"
                + "        bool roxyHasOpaqueLod = roxyOpaqueDepth > 0.0 && roxyOpaqueDepth < 1.0;\n"
                + "        bool roxyHasTransLod = roxyTransDepth > 0.0 && roxyTransDepth < 1.0;\n"
                + "        if (roxyHasOpaqueLod || roxyHasTransLod) {\n"
                + "            vec4 roxyNative = " + INVERSE_NATIVE_MVP
                + " * vec4(roxyNativeUv * 2.0 - 1.0, gl_FragCoord.z * 2.0 - 1.0, 1.0);\n"
                + "            vec4 roxyNativeView = " + INVERSE_NATIVE_PROJECTION
                + " * vec4(roxyNativeUv * 2.0 - 1.0, gl_FragCoord.z * 2.0 - 1.0, 1.0);\n"
                + "            if (abs(roxyNative.w) > 1.0e-6 && abs(roxyNativeView.w) > 1.0e-6) {\n"
                + "                vec3 roxyNativePosition = roxyNative.xyz / roxyNative.w;\n"
                + "                float roxyNativeViewDistance = length(roxyNativeView.xyz / roxyNativeView.w);\n"
                + "                float roxyOpaqueDistance = -1.0;\n"
                + "                if (roxyHasOpaqueLod) {\n"
                + "                    vec4 roxyOpaque = " + INVERSE_LOD_PROJECTION
                + " * vec4(roxyNativeUv * 2.0 - 1.0, roxyOpaqueDepth * 2.0 - 1.0, 1.0);\n"
                + "                    if (abs(roxyOpaque.w) > 1.0e-6) {\n"
                + "                        roxyOpaqueDistance = length(roxyOpaque.xyz / roxyOpaque.w);\n"
                + "                        if (roxyNativeViewDistance > roxyOpaqueDistance + 0.05) discard;\n"
                + "                    }\n"
                + "                }\n"
                + "                if (roxyHasTransLod) {\n"
                + "                    vec4 roxyTrans = " + INVERSE_LOD_PROJECTION
                + " * vec4(roxyNativeUv * 2.0 - 1.0, roxyTransDepth * 2.0 - 1.0, 1.0);\n"
                + "                    if (abs(roxyTrans.w) > 1.0e-6) {\n"
                + "                        float roxyTransDistance = length(roxyTrans.xyz / roxyTrans.w);\n"
                + "                        bool roxyDistinctTrans = !roxyHasOpaqueLod;\n"
                + "                        if (roxyHasOpaqueLod && roxyOpaqueDistance >= 0.0)\n"
                + "                            roxyDistinctTrans = abs(roxyTransDistance - roxyOpaqueDistance) > 0.02;\n"
                + "                        bool roxyNativeForeground = roxyNativeViewDistance + 4.0 < roxyTransDistance;\n"
                + "                        if (roxyDistinctTrans && !roxyNativeForeground\n"
                + "                                && length(roxyNativePosition.xz) > " + START + ") discard;\n"
                + "                    }\n"
                + "                }\n"
                + "            }\n"
                + "        }\n"
                + "    }\n"
                + "    roxyNativeOriginalMain();\n"
                + "}\n";
    }

    private record Frame(float start, float end, float[] inverseNativeMvp, float[] inverseNativeProjection,
                         float[] inverseLodProjection,
                         int opaqueDepthId, int translucentDepthId, int width, int height) {
    }
}

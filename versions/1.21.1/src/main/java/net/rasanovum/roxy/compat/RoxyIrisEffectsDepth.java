package net.rasanovum.roxy.compat;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL33C;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

public final class RoxyIrisEffectsDepth {
    public static final String ENABLED = "roxyIrisEffectsDepthEnabled";
    public static final String FULL_OPAQUE = "roxyIrisEffectsFullOpaqueDepth";
    public static final String FULL_TRANSLUCENT = "roxyIrisEffectsFullTransDepth";
    public static final String REAL_OPAQUE = "roxyIrisEffectsRealOpaqueDepth";
    public static final String VIEWPORT = "roxyIrisEffectsDepthViewport";

    private static final String MARKER = "// ROXY_IRIS_EFFECTS_DEPTH_BRIDGE";
    private static final String OPAQUE_SELECTED = "vxDepthTexOpaque";
    private static final String TRANSLUCENT_SELECTED = "vxDepthTexTrans";
    private static final String NATIVE_SELECTED = "depthtex0";
    private static final String[] SELECTED_SAMPLERS = {OPAQUE_SELECTED, TRANSLUCENT_SELECTED, NATIVE_SELECTED};
    private static volatile Frame frame;
    private static volatile Object samplerPipeline;
    private static volatile ClassLoader irisManagerLoader;
    private static volatile Method irisGetPipelineManager;
    private static volatile Method managerGetPipelineNullable;
    private static final RoxyIrisNativeDepthMerge NATIVE_DEPTH_MERGE = new RoxyIrisNativeDepthMerge();
    private static volatile boolean nativeDepthMerged;

    private RoxyIrisEffectsDepth() {
    }

    public static void beginFrame() {
        frame = null;
        nativeDepthMerged = false;
    }

    public static void reset() {
        samplerPipeline = null;
        releaseDepthResources();
    }

    public static void releaseDepthResources() {
        frame = null;
        nativeDepthMerged = false;
        NATIVE_DEPTH_MERGE.free();
    }

    public static void activatePipeline(ClassLoader loader) {
        if (loader == null) {
            samplerPipeline = null;
            return;
        }
        try {
            Method getManager;
            Method getPipeline;
            synchronized (RoxyIrisEffectsDepth.class) {
                if (irisManagerLoader != loader || irisGetPipelineManager == null || managerGetPipelineNullable == null) {
                    Class<?> iris = Class.forName("net.irisshaders.iris.Iris", false, loader);
                    Class<?> manager = Class.forName("net.irisshaders.iris.pipeline.PipelineManager", false, loader);
                    irisGetPipelineManager = iris.getMethod("getPipelineManager");
                    managerGetPipelineNullable = manager.getMethod("getPipelineNullable");
                    irisManagerLoader = loader;
                }
                getManager = irisGetPipelineManager;
                getPipeline = managerGetPipelineNullable;
            }
            Object manager = getManager.invoke(null);
            samplerPipeline = manager == null ? null : getPipeline.invoke(manager);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            samplerPipeline = null;
        }
    }

    public static void activateIris(Object voxyPipeline, boolean translucent) {
        activatePipeline(voxyPipeline == null ? null : voxyPipeline.getClass().getClassLoader());
    }

    public static void publish(boolean enabled, int realDepth, int fullOpaque, int fullTrans,
                               int width, int height) {
        if (!enabled || realDepth == 0 || fullOpaque == 0 || fullTrans == 0 || width < 1 || height < 1) {
            frame = null;
            return;
        }
        frame = new Frame(realDepth, fullOpaque, fullTrans, width, height);
    }

    public static String patchFragmentSource(String source) {
        if (source == null || source.contains(MARKER)) return source;

        List<Replacement> replacements = new ArrayList<>();
        Set<Wrapper> wrappers = new HashSet<>();
        Map<String, Set<Integer>> samplerParameters = findSamplerParameters(source);
        List<Call> calls = findCalls(source);
        boolean usesVoxySampler = false;
        boolean usesNativeSampler = false;

        for (Call call : calls) {
            if (!call.declaration && samplerParameters.containsKey(call.name)) {
                for (int index : samplerParameters.get(call.name)) {
                    if (index >= call.arguments.size()) continue;
                    Span argument = call.arguments.get(index);
                    String sampler = source.substring(argument.start, argument.end).trim();
                    String fullSampler = fullSampler(sampler);
                    if (fullSampler != null) {
                        replacements.add(new Replacement(argument.start, argument.end,
                                conditionalSamplerArgument(sampler, fullSampler)));
                        if (sampler.equals(NATIVE_SELECTED)) usesNativeSampler = true;
                        else usesVoxySampler = true;
                    }
                }
            }

            if (call.declaration || !isSamplingFunction(call.name) || call.arguments.size() < 2) continue;
            Span firstArgument = call.arguments.get(0);
            String sampler = source.substring(firstArgument.start, firstArgument.end).trim();
            int samplerIndex = selectedSamplerIndex(sampler);
            if (samplerIndex < 0) continue;
            if (samplerIndex == 2) usesNativeSampler = true;
            else usesVoxySampler = true;
            Wrapper wrapper = wrapperFor(call.name, call.arguments.size(), samplerIndex);
            if (wrapper == null) continue;
            wrappers.add(wrapper);
            String suffix = source.substring(firstArgument.end, call.end - 1);
            replacements.add(new Replacement(call.start, call.nameEnd, wrapper.name));
            replacements.add(new Replacement(firstArgument.start, call.end - 1,
                    source.substring(firstArgument.end + 1, call.end - 1)));
        }

        if (replacements.isEmpty() || !usesVoxySampler) return source;
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        StringBuilder patched = new StringBuilder(source);
        int previousStart = Integer.MAX_VALUE;
        for (Replacement replacement : replacements) {
            if (replacement.end > previousStart) continue;
            patched.replace(replacement.start, replacement.end, replacement.text);
            previousStart = replacement.start;
        }

        String declarations = buildWrappers(wrappers, usesNativeSampler,
                usesVoxySamplerDeclarationsAreGuarded(source));
        String result = patched.toString();
        int insertion = insertionAfterDepthDeclarations(result);
        if (insertion < 0) return source;
        return result.substring(0, insertion) + declarations + result.substring(insertion);
    }

    public static void registerSamplers(Object pipeline, Object holder) {
        if (pipeline == null || holder == null) return;
        samplerPipeline = pipeline;
        try {
            ClassLoader loader = holder.getClass().getClassLoader();
            Class<?> textureType = Class.forName("net.irisshaders.iris.gl.texture.TextureType", false, loader);
            Object texture2d = textureType.getField("TEXTURE_2D").get(null);
            Class<?> samplerType = Class.forName("net.irisshaders.iris.gl.sampler.GlSampler", false, loader);
            Object nearest = samplerType.getField("NEAREST").get(null);
            Object owner = pipeline;
            RoxyIrisCompat.addDynamicSampler(holder, texture2d,
                    fullOpaqueSupplier(owner),
                    RoxyIrisCompat.samplerSupplier(nearest), new String[]{FULL_OPAQUE});
            RoxyIrisCompat.addDynamicSampler(holder, texture2d,
                    fullTransSupplier(owner),
                    RoxyIrisCompat.samplerSupplier(nearest), new String[]{FULL_TRANSLUCENT});
            RoxyIrisCompat.addDynamicSampler(holder, texture2d,
                    realDepthSupplier(owner),
                    RoxyIrisCompat.samplerSupplier(nearest), new String[]{REAL_OPAQUE});
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Iris is optional, and an unavailable API leaves the GLSL bridge disabled.
        }
    }

    public static void applyUniforms() {
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program == 0) return;
        int enabledLocation = GL20C.glGetUniformLocation(program, ENABLED);
        if (enabledLocation < 0) return;

        Frame current = frame;
        int[] viewport = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
        boolean active = current != null && viewport[2] > 0 && viewport[3] > 0;
        GL20C.glUniform1i(enabledLocation, active ? 1 : 0);
        if (!active) return;

        int viewportLocation = GL20C.glGetUniformLocation(program, VIEWPORT);
        if (viewportLocation >= 0) {
            GL20C.glUniform4f(viewportLocation, viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    public static void mergeCurrentNativeDepth() {
        Frame current = frame;
        if (current == null || nativeDepthMerged) return;
        nativeDepthMerged = true;
        int nativeDepth = vanillaDepthTexture(samplerPipeline);
        if (nativeDepth == 0 || nativeDepth == current.realDepth) return;
        try {
            NATIVE_DEPTH_MERGE.merge(current.realDepth, nativeDepth, current.width, current.height);
        } catch (RuntimeException | LinkageError failure) {
            if (Boolean.getBoolean("roxy.fade.debug")) {
                System.err.println("[RoxyIrisEffectsDepth] Native depth snapshot merge failed: " + failure);
            }
        }
    }

    public static void bindVoxyUniforms() {
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        if (program == 0) return;

        int maxUnits = GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        if (maxUnits < 4) return;
        int opaqueUnit = maxUnits - 2;
        int transUnit = maxUnits - 3;
        int realUnit = maxUnits - 4;
        int previousActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        bindDepthSampler(program, FULL_OPAQUE, opaqueUnit, fullOpaqueTexture());
        bindDepthSampler(program, FULL_TRANSLUCENT, transUnit, fullTransTexture());
        bindDepthSampler(program, REAL_OPAQUE, realUnit, realDepthTexture());
        GL13C.glActiveTexture(previousActiveTexture);

        int enabledLocation = GL20C.glGetUniformLocation(program, ENABLED);
        Frame current = frame;
        GL20C.glUniform1i(enabledLocation, current == null ? 0 : 1);
        int viewportLocation = GL20C.glGetUniformLocation(program, VIEWPORT);
        if (viewportLocation >= 0) {
            int[] viewport = new int[4];
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            GL20C.glUniform4f(viewportLocation, viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    private static void bindDepthSampler(int program, String uniform, int unit, int texture) {
        int location = GL20C.glGetUniformLocation(program, uniform);
        if (location < 0) return;
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL33C.glBindSampler(unit, 0);
        GL20C.glUniform1i(location, unit);
    }

    private static int fullOpaqueTexture() {
        return fullOpaqueTexture(samplerPipeline);
    }

    private static int fullOpaqueTexture(Object owner) {
        Frame current = frame;
        return current == null ? selectedVoxyDepth(owner, false) : current.fullOpaque;
    }

    private static IntSupplier fullOpaqueSupplier(Object owner) {
        return () -> fullOpaqueTexture(owner);
    }

    private static int fullTransTexture() {
        return fullTransTexture(samplerPipeline);
    }

    private static int fullTransTexture(Object owner) {
        Frame current = frame;
        return current == null ? selectedVoxyDepth(owner, true) : current.fullTrans;
    }

    private static IntSupplier fullTransSupplier(Object owner) {
        return () -> fullTransTexture(owner);
    }

    private static int realDepthTexture() {
        return realDepthTexture(samplerPipeline);
    }

    private static int realDepthTexture(Object owner) {
        Frame current = frame;
        return current == null ? vanillaDepthTexture(owner) : current.realDepth;
    }

    private static IntSupplier realDepthSupplier(Object owner) {
        return () -> realDepthTexture(owner);
    }

    private static int selectedVoxyDepth(Object pipeline, boolean translucent) {
        if (pipeline == null) return 0;
        try {
            Object data = pipeline.getClass().getMethod("voxy$getPipelineData").invoke(pipeline);
            if (data == null) return 0;
            Object voxyPipeline = data.getClass().getField("thePipeline").get(data);
            if (voxyPipeline == null) return 0;
            Field framebufferField = voxyPipeline.getClass().getField(translucent ? "fbTranslucent" : "fb");
            Object framebuffer = framebufferField.get(voxyPipeline);
            Method getDepth = framebuffer.getClass().getMethod("getDepthTex");
            Object depth = getDepth.invoke(framebuffer);
            if (depth == null) return 0;
            return depth.getClass().getField("id").getInt(depth);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    private static int vanillaDepthTexture(Object pipeline) {
        if (pipeline == null) return 0;
        try {
            Object targets;
            try {
                targets = pipeline.getClass().getMethod("getRenderTargets").invoke(pipeline);
            } catch (NoSuchMethodException ignored) {
                Field targetsField = pipeline.getClass().getDeclaredField("renderTargets");
                if (!targetsField.trySetAccessible()) return 0;
                targets = targetsField.get(pipeline);
            }
            if (targets == null) return 0;
            return ((Number) targets.getClass().getMethod("getDepthTexture").invoke(targets)).intValue();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    private static int selectedSamplerIndex(String sampler) {
        for (int index = 0; index < SELECTED_SAMPLERS.length; index++) {
            if (SELECTED_SAMPLERS[index].equals(sampler)) return index;
        }
        return -1;
    }

    private static String fullSampler(String sampler) {
        return switch (sampler) {
            case OPAQUE_SELECTED -> FULL_OPAQUE;
            case TRANSLUCENT_SELECTED -> FULL_TRANSLUCENT;
            case NATIVE_SELECTED -> REAL_OPAQUE;
            default -> null;
        };
    }

    private static String conditionalSamplerArgument(String selected, String full) {
        return "\n#ifdef VOXY\n" + full + "\n#else\n" + selected + "\n#endif\n";
    }

    private static boolean isSamplingFunction(String name) {
        return name.equals("texture") || name.equals("texture2D") || name.equals("textureLod")
                || name.equals("texture2DLod") || name.equals("texelFetch");
    }

    private static Wrapper wrapperFor(String function, int count, int samplerIndex) {
        String sampler = switch (samplerIndex) {
            case 0 -> "Opaque";
            case 1 -> "Trans";
            case 2 -> "Native";
            default -> throw new IllegalArgumentException("Unexpected sampler index " + samplerIndex);
        };
        String method;
        if (function.equals("texelFetch")) {
            if (count != 3) return null;
            method = "Fetch";
        } else if (function.equals("textureLod") || function.equals("texture2DLod")) {
            if (count != 3) return null;
            method = function.equals("textureLod") ? "TextureLod" : "Texture2DLod";
        } else {
            if (count == 2) method = function.equals("texture") ? "Texture" : "Texture2D";
            else if (count == 3) method = function.equals("texture") ? "TextureBias" : "Texture2DBias";
            else return null;
        }
        return new Wrapper(function, sampler, method, "roxyEffects" + sampler + method);
    }

    private static String buildWrappers(Set<Wrapper> wrappers, boolean usesNativeSampler, boolean guardVoxy) {
        StringBuilder source = new StringBuilder("\n").append(MARKER).append('\n')
                .append("uniform int ").append(ENABLED).append(";\n")
                .append("uniform vec4 ").append(VIEWPORT).append(";\n");
        if (usesNativeSampler) source.append("uniform sampler2D ").append(REAL_OPAQUE).append(";\n");
        boolean usesVoxyWrapper = wrappers.stream().anyMatch(wrapper -> !wrapper.sampler.equals("Native"));
        if (usesVoxyWrapper) {
            if (guardVoxy) source.append("#ifdef VOXY\n");
            source.append("uniform sampler2D ").append(FULL_OPAQUE).append(";\n")
                    .append("uniform sampler2D ").append(FULL_TRANSLUCENT).append(";\n");
        }
        if (!wrappers.isEmpty()) {
            source.append("bool roxyEffectsSameUv(sampler2D selectedDepth, vec2 uv) {\n")
                    .append("    vec2 currentTexel = floor((gl_FragCoord.xy - ").append(VIEWPORT).append(".xy) / ").append(VIEWPORT).append(".zw * vec2(textureSize(selectedDepth, 0)));\n")
                    .append("    return all(equal(floor(uv * vec2(textureSize(selectedDepth, 0))), currentTexel));\n")
                    .append("}\n")
                    .append("bool roxyEffectsSameTexel(sampler2D selectedDepth, ivec2 coord) {\n")
                    .append("    ivec2 currentTexel = ivec2(floor((gl_FragCoord.xy - ").append(VIEWPORT).append(".xy) / ").append(VIEWPORT).append(".zw * vec2(textureSize(selectedDepth, 0))));\n")
                    .append("    return all(equal(coord, currentTexel));\n")
                    .append("}\n");
        }
        for (Wrapper wrapper : wrappers) {
            if (wrapper.sampler.equals("Native")) appendWrapper(source, wrapper);
        }
        if (usesVoxyWrapper) {
            for (Wrapper wrapper : wrappers) {
                if (!wrapper.sampler.equals("Native")) appendWrapper(source, wrapper);
            }
            if (guardVoxy) source.append("#endif\n");
        }
        source.append('\n');
        return source.toString();
    }

    private static void appendWrapper(StringBuilder source, Wrapper wrapper) {
        String selected = SELECTED_SAMPLERS[samplerIndex(wrapper.sampler)];
        String full = switch (wrapper.sampler) {
            case "Opaque" -> FULL_OPAQUE;
            case "Trans" -> FULL_TRANSLUCENT;
            case "Native" -> REAL_OPAQUE;
            default -> throw new IllegalStateException("Unknown sampler " + wrapper.sampler);
        };
        String query = wrapper.method;
        if (query.equals("Fetch")) {
            source.append("vec4 ").append(wrapper.name).append("(ivec2 coord, int lod) {\n")
                    .append("    if (").append(ENABLED).append(" == 0 || roxyEffectsSameTexel(").append(selected).append(", coord)) return texelFetch(")
                    .append(selected).append(", coord, lod);\n")
                    .append("    return texelFetch(").append(full).append(", coord, lod);\n")
                    .append("}\n");
        } else if (query.equals("TextureLod") || query.equals("Texture2DLod")) {
            String builtin = query.equals("TextureLod") ? "textureLod" : "texture2DLod";
            source.append("vec4 ").append(wrapper.name).append("(vec2 uv, float lod) {\n")
                    .append("    if (").append(ENABLED).append(" == 0 || roxyEffectsSameUv(").append(selected).append(", uv)) return ")
                    .append(builtin).append('(').append(selected).append(", uv, lod);\n")
                    .append("    return ").append(builtin).append('(').append(full).append(", uv, lod);\n")
                    .append("}\n");
        } else if (query.equals("TextureBias") || query.equals("Texture2DBias")) {
            String builtin = query.equals("TextureBias") ? "texture" : "texture2D";
            source.append("vec4 ").append(wrapper.name).append("(vec2 uv, float bias) {\n")
                    .append("    if (").append(ENABLED).append(" == 0 || roxyEffectsSameUv(").append(selected).append(", uv)) return ")
                    .append(builtin).append('(').append(selected).append(", uv, bias);\n")
                    .append("    return ").append(builtin).append('(').append(full).append(", uv, bias);\n")
                    .append("}\n");
        } else {
            String builtin = query.equals("Texture") ? "texture" : "texture2D";
            source.append("vec4 ").append(wrapper.name).append("(vec2 uv) {\n")
                    .append("    if (").append(ENABLED).append(" == 0 || roxyEffectsSameUv(").append(selected).append(", uv)) return ")
                    .append(builtin).append('(').append(selected).append(", uv);\n")
                    .append("    return ").append(builtin).append('(').append(full).append(", uv);\n")
                    .append("}\n");
        }
    }

    private static int samplerIndex(String shortName) {
        return switch (shortName) {
            case "Opaque" -> 0;
            case "Trans" -> 1;
            case "Native" -> 2;
            default -> -1;
        };
    }

    private static List<Call> findCalls(String source) {
        List<Token> tokens = tokenize(source);
        Map<Integer, Integer> matchingParen = matchParentheses(tokens);
        List<Call> calls = new ArrayList<>();
        for (int index = 0; index + 1 < tokens.size(); index++) {
            Token name = tokens.get(index);
            if (!name.identifier || !tokens.get(index + 1).text.equals("(")) continue;
            Integer closeIndex = matchingParen.get(index + 1);
            if (closeIndex == null) continue;
            Token close = tokens.get(closeIndex);
            List<Span> arguments = splitArguments(source, tokens, index + 1, closeIndex);
            boolean declaration = hasSamplerTypeArgument(source, arguments);
            calls.add(new Call(name.text, name.start, name.end, close.end, arguments, declaration));
        }
        return calls;
    }

    private static Map<String, Set<Integer>> findSamplerParameters(String source) {
        List<Token> tokens = tokenize(source);
        Map<Integer, Integer> matchingParen = matchParentheses(tokens);
        Map<String, Set<Integer>> result = new HashMap<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!tokens.get(index).text.equals("sampler2D")) continue;
            int openParen = -1;
            for (int before = index - 1; before >= 0; before--) {
                if (tokens.get(before).text.equals(")")) break;
                if (tokens.get(before).text.equals("(")) {
                    openParen = before;
                    break;
                }
            }
            if (openParen < 1) continue;
            Integer closeParen = matchingParen.get(openParen);
            if (closeParen == null) continue;
            int nameIndex = openParen - 1;
            if (!tokens.get(nameIndex).identifier) continue;
            List<Span> args = splitArguments(source, tokens, openParen, closeParen);
            for (int argIndex = 0; argIndex < args.size(); argIndex++) {
                if (containsSampler2D(source, args.get(argIndex))) {
                    result.computeIfAbsent(tokens.get(nameIndex).text, ignored -> new HashSet<>()).add(argIndex);
                }
            }
            index = closeParen;
        }
        return result;
    }

    private static boolean hasSamplerTypeArgument(String source, List<Span> arguments) {
        for (Span argument : arguments) if (containsSampler2D(source, argument)) return true;
        return false;
    }

    private static boolean containsSampler2D(String source, Span span) {
        String text = source.substring(span.start, span.end);
        return text.matches("(?s).*\\bsampler2D\\b.*");
    }

    private static List<Span> splitArguments(String source, List<Token> tokens, int openParen, int closeParen) {
        List<Span> arguments = new ArrayList<>();
        int depthParen = 0;
        int depthBracket = 0;
        int depthBrace = 0;
        int start = tokens.get(openParen).end;
        for (int index = openParen + 1; index < closeParen; index++) {
            Token token = tokens.get(index);
            switch (token.text) {
                case "(" -> depthParen++;
                case ")" -> depthParen--;
                case "[" -> depthBracket++;
                case "]" -> depthBracket--;
                case "{" -> depthBrace++;
                case "}" -> depthBrace--;
                case "," -> {
                    if (depthParen == 0 && depthBracket == 0 && depthBrace == 0) {
                        arguments.add(trimSpan(source, start, token.start));
                        start = token.end;
                    }
                }
            }
        }
        int end = tokens.get(closeParen).start;
        if (start <= end && !source.substring(start, end).isBlank()) arguments.add(trimSpan(source, start, end));
        return arguments;
    }

    private static Span trimSpan(String source, int start, int end) {
        while (start < end && Character.isWhitespace(source.charAt(start))) start++;
        while (end > start && Character.isWhitespace(source.charAt(end - 1))) end--;
        return new Span(start, end);
    }

    private static Map<Integer, Integer> matchParentheses(List<Token> tokens) {
        Map<Integer, Integer> matches = new HashMap<>();
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).text.equals("(")) stack.push(index);
            else if (tokens.get(index).text.equals(")") && !stack.isEmpty()) {
                int open = stack.pop();
                matches.put(open, index);
            }
        }
        return matches;
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (Character.isWhitespace(c)) {
                index++;
                continue;
            }
            if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                index = skipLineComment(source, index + 2);
                continue;
            }
            if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                index = skipBlockComment(source, index + 2);
                continue;
            }
            if (c == '"') {
                index = skipString(source, index + 1);
                continue;
            }
            int start = index;
            if (Character.isLetter(c) || c == '_') {
                index++;
                while (index < source.length()) {
                    char next = source.charAt(index);
                    if (!Character.isLetterOrDigit(next) && next != '_') break;
                    index++;
                }
                tokens.add(new Token(source.substring(start, index), start, index, true));
            } else {
                index++;
                tokens.add(new Token(source.substring(start, index), start, index, false));
            }
        }
        return tokens;
    }

    private static int skipLineComment(String source, int index) {
        while (index < source.length() && source.charAt(index) != '\n') index++;
        return index;
    }

    private static int skipBlockComment(String source, int index) {
        int end = source.indexOf("*/", index);
        return end < 0 ? source.length() : end + 2;
    }

    private static int skipString(String source, int index) {
        while (index < source.length()) {
            char c = source.charAt(index++);
            if (c == '\\' && index < source.length()) index++;
            else if (c == '"') break;
        }
        return index;
    }

    private static int insertionAfterDepthDeclarations(String source) {
        String[] lines = source.split("(?<=\\n)", -1);
        ArrayDeque<Conditional> conditionals = new ArrayDeque<>();
        int offset = 0;
        int insertion = -1;
        for (String line : lines) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("#if")) {
                conditionals.push(new Conditional(offset));
            } else if (trimmed.startsWith("#endif") && !conditionals.isEmpty()) {
                Conditional closed = conditionals.pop();
                closed.end = offset + line.length();
                if (closed.containsTarget) insertion = Math.max(insertion, closed.end);
                if (!conditionals.isEmpty() && closed.containsTarget) conditionals.peek().containsTarget = true;
            } else if (isDepthSamplerDeclaration(trimmed)) {
                if (conditionals.isEmpty()) insertion = Math.max(insertion, offset + line.length());
                else conditionals.peekLast().containsTarget = true;
            }
            offset += line.length();
        }
        if (insertion < 0) return -1;
        while (insertion < source.length() && source.charAt(insertion) == '\r') insertion++;
        if (insertion < source.length() && source.charAt(insertion) == '\n') insertion++;
        return insertion;
    }

    private static boolean isDepthSamplerDeclaration(String line) {
        return line.matches("(?s).*\\buniform\\s+sampler\\w*\\s+"
                + "(?:vxDepthTexOpaque|vxDepthTexTrans|depthtex0)\\b.*");
    }

    private static boolean usesVoxySamplerDeclarationsAreGuarded(String source) {
        ArrayDeque<Boolean> voxyGuards = new ArrayDeque<>();
        boolean foundDeclaration = false;
        boolean everyDeclarationGuarded = true;
        for (String line : source.split("(?<=\\n)")) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("#if")) {
                voxyGuards.push(trimmed.matches("#if(?:def)?\\s+.*\\bVOXY\\b.*"));
            } else if (trimmed.startsWith("#endif") && !voxyGuards.isEmpty()) {
                voxyGuards.pop();
            } else if (trimmed.matches("(?s).*\\buniform\\s+sampler\\w*\\s+vxDepthTex(?:Opaque|Trans)\\b.*")) {
                foundDeclaration = true;
                if (!voxyGuards.contains(Boolean.TRUE)) everyDeclarationGuarded = false;
            }
        }
        return foundDeclaration && everyDeclarationGuarded;
    }

    private record Frame(int realDepth, int fullOpaque, int fullTrans, int width, int height) {
    }

    private record Token(String text, int start, int end, boolean identifier) {
    }

    private record Span(int start, int end) {
    }

    private record Call(String name, int start, int nameEnd, int end, List<Span> arguments, boolean declaration) {
    }

    private record Replacement(int start, int end, String text) {
    }

    private record Wrapper(String function, String sampler, String method, String name) {
    }

    private static final class Conditional {
        private final int start;
        private int end;
        private boolean containsTarget;

        private Conditional(int start) {
            this.start = start;
        }
    }
}

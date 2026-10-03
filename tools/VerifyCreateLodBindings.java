import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipFile;

/** Read-only artifact and API fixture for the optional Create/LOD shader bindings. */
public final class VerifyCreateLodBindings {
    private static final String IRIS = "G:/Modrinth/profiles/Shenanigans/mods/iris-neoforge-1.8.14-beta.1+mc1.21.1.jar";
    private static final String COLORWHEEL = "G:/Modrinth/profiles/Shenanigans/mods/colorwheel-neoforge-1.3.0-beta3+mc1.21.1.jar";
    private static final String CREATE = "G:/Modrinth/profiles/Shenanigans/mods/create-1.21.1-6.0.10.jar";
    private static final String SHADER_HELPER = "net.rasanovum.roxy.shader.RoxyLodEntityShader";

    private VerifyCreateLodBindings() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
        Path iris = Path.of(args.length > 1 ? args[1] : IRIS);
        Path colorwheel = Path.of(args.length > 2 ? args[2] : COLORWHEEL);
        Path create = Path.of(args.length > 3 ? args[3] : CREATE);
        Path classes = root.resolve(args.length > 4 ? args[4] : "versions/1.21.1/build/classes/java/main");
        require(Files.isRegularFile(iris), "Iris artifact missing: " + iris);
        require(Files.isRegularFile(colorwheel), "Colorwheel artifact missing: " + colorwheel);
        require(Files.isRegularFile(create), "Create artifact missing: " + create);

        verifyIrisApi(iris);
        verifyColorwheelApi(colorwheel);
        verifyMixinSources(root, iris, colorwheel);
        verifyShaderRoles(iris, classes, create);
        verifyColorwheelRecordPatch(classes, iris, colorwheel, create);
        System.out.println("PASS: pinned Iris/Colorwheel targets, mixin descriptors, optional Create gating, shader roles, and Colorwheel record preservation");
    }

    private static void verifyIrisApi(Path jar) throws IOException {
        byte[] creator = entry(jar, "net/irisshaders/iris/pipeline/programs/ShaderCreator.class");
        MethodShape create = method(creator, "create");
        require(create != null && create.descriptor.contains("ProgramSource") && create.descriptor.endsWith("ExtendedShader;"),
                "Iris ShaderCreator.create signature changed: " + (create == null ? "missing" : create.descriptor));
        byte[] extended = entry(jar, "net/irisshaders/iris/pipeline/programs/ExtendedShader.class");
        require(method(extended, "apply", "()V") != null, "Iris ExtendedShader.apply()V missing");
        require(method(extended, "<init>") != null, "Iris ExtendedShader constructor missing");
        require(hasInvoke(extended, "<init>", "java/util/function/BiConsumer", "accept"),
                "Iris ExtendedShader constructor no longer invokes the sampler creator");
        byte[] pipeline = entry(jar, "net/irisshaders/iris/pipeline/IrisRenderingPipeline.class");
        require(method(pipeline, "addGbufferOrShadowSamplers") != null,
                "Iris sampler hook missing");
        byte[] key = entry(jar, "net/irisshaders/iris/pipeline/programs/ShaderKey.class");
        Set<String> names = enumNames(key);
        for (String expected : List.of("ENTITIES_SOLID", "ENTITIES_TRANSLUCENT", "BLOCK_ENTITY", "BE_TRANSLUCENT")) {
            require(names.contains(expected), "Pinned Iris ShaderKey missing " + expected);
        }
        require(names.contains("HAND_CUTOUT") && names.contains("SHADOW_ENTITIES_CUTOUT")
                        && names.contains("TERRAIN_SOLID"), "Pinned Iris exclusion roles missing");
    }

    private static void verifyColorwheelApi(Path jar) throws IOException {
        byte[] program = entry(jar, "dev/djefrey/colorwheel/compile/ClrwlProgram.class");
        require(method(program, "postLink") != null, "Colorwheel ClrwlProgram.postLink missing");
        require(method(program, "bind", "()V") != null, "Colorwheel ClrwlProgram.bind()V missing");
        require(hasInvoke(program, "postLink", "net/irisshaders/iris/pipeline/IrisRenderingPipeline", "addGbufferOrShadowSamplers"),
                "Colorwheel ClrwlProgram no longer delegates to Iris sampler setup");
        byte[] sources = entry(jar, "dev/djefrey/colorwheel/compile/ClrwlProgramSources.class");
        require(method(sources, "getGbuffersSources") != null, "Colorwheel getGbuffersSources missing");
        byte[] output = entry(jar, "dev/djefrey/colorwheel/compile/transform/ClrwlTransformOutput.class");
        require(hasRecordComponents(output, Set.of("code", "outputs")), "Colorwheel transform output record changed");
        byte[] patched = entry(jar, "dev/djefrey/colorwheel/compile/ClrwlProgramSources$PatchedGbuffersSources.class");
        require(hasRecordComponents(patched, Set.of("vertex", "geometry", "fragment", "extensions", "drawBuffers")),
                "Colorwheel patched G-buffer record changed");
    }

    private static void verifyMixinSources(Path root, Path iris, Path colorwheel) throws IOException {
        Path source = root.resolve("versions/1.21.1/src/main/java/net/rasanovum/roxy/mixin");
        String shader = text(source.resolve("RoxyLodIrisShaderMixin.java"));
        require(shader.contains("ShaderCreator") && shader.contains("method = \"create\"")
                        && shader.contains("Map;get") && shader.toLowerCase(Locale.ROOT).contains("fragment"),
                "Iris source patch mixin is not guarded at the fragment lookup");
        String extended = text(source.resolve("RoxyLodIrisExtendedShaderMixin.java"));
        require(extended.contains("ExtendedShader") && extended.contains("<init>")
                        && extended.contains("BiConsumer;accept"), "ExtendedShader sampler target changed");
        String cwProgram = text(source.resolve("RoxyLodColorwheelProgramMixin.java"));
        require(cwProgram.contains("ClrwlProgram") && cwProgram.contains("postLink")
                        && cwProgram.contains("ProgramSamplers$Builder;build"), "Colorwheel program hook changed");
        String cwSources = text(source.resolve("RoxyLodColorwheelSourcesMixin.java"));
        require(cwSources.contains("ClrwlProgramSources") && cwSources.contains("getGbuffersSources")
                        && cwSources.contains("cancellable = true") && cwSources.contains("@Coerce"),
                "Colorwheel cancellable source injection must preserve coerced return/fixture records");
        String frame = text(source.resolve("RoxyLodEntityFrameMixin.java"));
        require(frame.contains("LevelRenderer") && frame.contains("renderLevel") && frame.contains("beginFrame"),
                "LOD frame hook missing");
        String config = text(root.resolve("versions/1.21.1/src/main/resources/roxy-voxy-shader.json"));
        for (String name : List.of("RoxyLodEntityFrameMixin", "RoxyLodIrisShaderMixin",
                "RoxyLodIrisExtendedShaderMixin", "RoxyLodColorwheelSourcesMixin", "RoxyLodColorwheelProgramMixin")) {
            require(config.contains(name), "Mixin config does not register " + name);
        }
        require(method(entry(iris, "net/irisshaders/iris/pipeline/programs/ShaderCreator.class"), "create") != null,
                "Iris target disappeared while checking mixin source");
        require(method(entry(colorwheel, "dev/djefrey/colorwheel/compile/ClrwlProgram.class"), "postLink") != null,
                "Colorwheel target disappeared while checking mixin source");
    }

    private static void verifyShaderRoles(Path iris, Path classes, Path create) throws Exception {
        Path marker = Files.createTempDirectory("roxy-create-marker-");
        writeMarker(marker);
        try (URLClassLoader loader = childLoader(marker, classes, create)) {
            Class<?> helper = Class.forName(SHADER_HELPER, true, loader);
            Method irisRole = helper.getMethod("isIrisRole", String.class);
            Set<String> names = enumNames(entry(iris, "net/irisshaders/iris/pipeline/programs/ShaderKey.class"));
            Set<String> accepted = Set.of("entities_alpha", "entities_solid", "entities_solid_diffuse", "entities_solid_bright",
                    "entities_cutout", "entities_cutout_diffuse", "entities_translucent", "block_entity",
                    "block_entity_bright", "block_entity_diffuse", "be_translucent", "moving_block");
            for (String name : names) {
                boolean expected = accepted.contains(name.toLowerCase(Locale.ROOT));
                boolean actual = (Boolean) irisRole.invoke(null, name.toLowerCase(Locale.ROOT));
                require(actual == expected, "Iris role mismatch for ShaderKey#getName " + name + ": " + actual);
            }
            for (String excluded : List.of("hand_cutout", "shadow_entities_cutout", "terrain_solid", "composite", "final")) {
                require(!(Boolean) irisRole.invoke(null, excluded), "Excluded Iris role was accepted: " + excluded);
            }
            Method colorRole = helper.getMethod("isColorwheelRole", Object.class);
            for (String acceptedName : List.of("GBUFFERS", "GBUFFERS_TRANSLUCENT", "GBUFFERS_DAMAGEDBLOCK")) {
                require((Boolean) colorRole.invoke(null, acceptedName), "Colorwheel role was rejected: " + acceptedName);
            }
            for (String excluded : List.of("SHADOW", "SHADOW_TRANSLUCENT", "TERRAIN", "GLINT", "LIGHTNING", "ADDITIVE")) {
                require(!(Boolean) colorRole.invoke(null, excluded), "Excluded Colorwheel role accepted: " + excluded);
            }
            String renamedEntry = (String) helper.getMethod("patchColorwheel", String.class, String.class).invoke(
                    null, "GBUFFERS", "#version 330 core\nvoid _clrwl_shader_main() { }\n");
            require(renamedEntry.contains("roxy_lod_entity_occlusion()")
                            && renamedEntry.contains("void _clrwl_shader_main"),
                    "Colorwheel renamed fragment entry point was not patched");
        }
        try (URLClassLoader absent = childLoader(classes)) {
            Class<?> helper = Class.forName(SHADER_HELPER, true, absent);
            require(!(Boolean) helper.getMethod("isIrisRole", String.class).invoke(null, "entities_solid"),
                    "Create marker absence did not disable Iris patching");
        }
    }

    private static void verifyColorwheelRecordPatch(Path classes, Path iris, Path colorwheel, Path create) throws Exception {
        Path marker = Files.createTempDirectory("roxy-create-record-marker-");
        writeMarker(marker);
        try (URLClassLoader loader = childLoader(marker, classes, iris, colorwheel, create)) {
            Class<?> helper = Class.forName(SHADER_HELPER, true, loader);
            Class<?> outputType = Class.forName("dev.djefrey.colorwheel.compile.transform.ClrwlTransformOutput", true, loader);
            Constructor<?> outputCtor = outputType.getDeclaredConstructor(String.class, java.util.Map.class);
            Object output = outputCtor.newInstance("#version 330 core\nvoid _clrwl_shader_main() { }\n", java.util.Map.of());
            Class<?> sourcesType = Class.forName("dev.djefrey.colorwheel.compile.ClrwlProgramSources$PatchedGbuffersSources", true, loader);
            Constructor<?> sourceCtor = sourcesType.getDeclaredConstructors()[0];
            Object record = sourceCtor.newInstance("vertex", Optional.empty(), output, new java.util.EnumMap(
                    Class.forName("dev.djefrey.colorwheel.gl.ClrwlShaderType", true, loader)), new int[]{0, 6});
            Object patched = helper.getMethod("patchColorwheelResult", Object.class, Object.class)
                    .invoke(null, record, "GBUFFERS");
            Object fragment = patched.getClass().getMethod("fragment").invoke(patched);
            String code = (String) fragment.getClass().getMethod("code").invoke(fragment);
            require(code.contains("roxy_lod_entity_occlusion") && code.contains("_clrwl_shader_main"),
                    "Colorwheel fragment record was not patched");
            require("vertex".equals(patched.getClass().getMethod("vertex").invoke(patched)),
                    "Colorwheel record vertex changed");
            require(patched.getClass().getMethod("drawBuffers").invoke(patched) != null,
                    "Colorwheel record draw buffers were lost");
        }
    }

    private static URLClassLoader childLoader(Path... paths) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (Path path : paths) if (Files.exists(path)) urls.add(path.toUri().toURL());
        ClassLoader parent = VerifyCreateLodBindings.class.getClassLoader();
        return new URLClassLoader(urls.toArray(URL[]::new), parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("net.rasanovum.roxy.") || name.equals("com.simibubi.create.Create")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) try { loaded = findClass(name); } catch (ClassNotFoundException ignored) { }
                        if (loaded != null) { if (resolve) resolveClass(loaded); return loaded; }
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private static void writeMarker(Path root) throws IOException {
        Path output = root.resolve("com/simibubi/create/Create.class");
        Files.createDirectories(output.getParent());
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/simibubi/create/Create", null, "java/lang/Object", null);
        writer.visitEnd();
        Files.write(output, writer.toByteArray());
    }

    private static byte[] entry(Path jar, String name) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var item = zip.getEntry(name);
            require(item != null, "Missing artifact entry " + name + " in " + jar);
            return zip.getInputStream(item).readAllBytes();
        }
    }

    private static String text(Path path) throws IOException {
        require(Files.isRegularFile(path), "Missing source fixture " + path);
        return Files.readString(path);
    }

    private static Set<String> enumNames(byte[] bytes) {
        Set<String> result = new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if ((access & Opcodes.ACC_ENUM) != 0) result.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    private static MethodShape method(byte[] bytes, String name) { return method(bytes, name, null); }
    private static MethodShape method(byte[] bytes, String name, String descriptor) {
        List<MethodShape> result = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String methodName, String desc, String sig, String[] exceptions) {
                if (methodName.equals(name) && (descriptor == null || descriptor.equals(desc))) result.add(new MethodShape(methodName, desc));
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result.isEmpty() ? null : result.get(0);
    }

    private static boolean hasInvoke(byte[] bytes, String methodName, String owner, String methodNameNeedle) {
        final boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                if (!name.equals(methodName)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String callOwner, String callName, String callDesc, boolean itf) {
                        if (callOwner.equals(owner) && callName.equals(methodNameNeedle)) found[0] = true;
                    }
                };
            }
        }, 0);
        return found[0];
    }

    private static boolean hasRecordComponents(byte[] bytes, Set<String> expected) {
        Set<String> components = new HashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public org.objectweb.asm.RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
                components.add(name);
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return components.equals(expected);
    }

    private record MethodShape(String name, String descriptor) { }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

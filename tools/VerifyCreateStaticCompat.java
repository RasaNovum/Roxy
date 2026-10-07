import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Locale;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/** Verifies the exact Create assets/API boundary used by the static belt adapter. */
public final class VerifyCreateStaticCompat {
    private static Constructor<?> quadConstructor;

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        Path jar = args.length == 0
                ? Path.of("G:/Modrinth/profiles/Shenanigans/mods/create-1.21.1-6.0.10.jar")
                : Path.of(args[0]);
        require(Files.isRegularFile(jar), "Create jar missing: " + jar);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            require(zip.getEntry("com/simibubi/create/content/kinetics/belt/BeltRenderer.class") != null,
                    "BeltRenderer API missing");
            require(zip.getEntry("com/simibubi/create/AllPartialModels.class") != null,
                    "AllPartialModels API missing");
            String particle = read(zip, "assets/create/models/block/belt/particle.json");
            require(particle.replaceAll("\\s+", "").contains("\"elements\":[]"),
                    "Create belt particle model is no longer empty");
            for (String model : new String[]{"start", "middle", "end", "diagonal_start", "diagonal_middle", "diagonal_end"}) {
                require(zip.getEntry("assets/create/models/block/belt/" + model + ".json") != null,
                        "Missing static belt model: " + model);
            }
            require(read(zip, "assets/create/blockstates/belt.json").contains("create:block/belt/particle"),
                    "Belt blockstate no longer demonstrates the missing static path");
        }
        boolean partialApi = false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var nested = zip.getEntry("META-INF/jarjar/flywheel-neoforge-1.21.1-1.0.6.jar");
            if (nested != null) try (ZipInputStream nestedZip = new ZipInputStream(zip.getInputStream(nested))) {
                for (var entry = nestedZip.getNextEntry(); entry != null; entry = nestedZip.getNextEntry()) {
                    if (entry.getName().equals("dev/engine_room/flywheel/lib/model/baked/PartialModel.class")) {
                        partialApi = true;
                        break;
                    }
                }
            }
        }
        require(partialApi, "Flywheel PartialModel API missing from Create's embedded backend");

        Class<?> bridge = Class.forName("net.rasanovum.roxy.compat.RoxyCreateStaticModelCompat");
        Method rotate = bridge.getDeclaredMethod("rotate", float.class, float.class, float.class,
                float.class, float.class, float.class);
        rotate.setAccessible(true);
        float[] rotated = (float[]) rotate.invoke(null, 1f, .5f, .5f, 0f, 90f, 0f);
        require(Math.abs(rotated[0] - .5f) < .0001f && Math.abs(rotated[2] - 0f) < .0001f,
                "Y rotation did not transform model coordinates around block center");
        verifyRuntimeFake(bridge);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ClassNode renderer = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(
                    "com/simibubi/create/content/kinetics/belt/BeltRenderer.class")).readAllBytes())
                    .accept(renderer, ClassReader.SKIP_CODE);
            require(renderer.methods.stream().anyMatch(method -> method.name.equals("getBeltPartial")
                    && method.desc.equals("(ZZZZ)Ldev/engine_room/flywheel/lib/model/baked/PartialModel;")),
                    "Create getBeltPartial descriptor changed");
        }
        System.out.println("PASS: exact Create belt assets, PartialModel/BeltRenderer APIs and static orientation transform");
    }

    private static void verifyRuntimeFake(Class<?> bridge) throws Exception {
        Class<?> bakedModel = Class.forName("net.minecraft.client.resources.model.BakedModel");
        Class<?> direction = Class.forName("net.minecraft.core.Direction");
        Class<?> randomSource = Class.forName("net.minecraft.util.RandomSource");
        Method getQuads = bakedModel.getMethod("getQuads", Class.forName("net.minecraft.world.level.block.state.BlockState"),
                direction, randomSource);
        quadConstructor = BakedQuad.class.getConstructor(int[].class, int.class, Direction.class,
                TextureAtlasSprite.class, boolean.class, boolean.class);
        Method fakePartialFor = FakeApi.class.getMethod("partialFor", boolean.class, boolean.class, boolean.class, boolean.class);
        Method fakePartialGet = FakeApi.class.getMethod("partialGet");
        Method fakeQuads = FakeApi.class.getMethod("getQuads", Object.class, Object.class, Object.class);
        Class<?> access = Class.forName(bridge.getName() + "$Access");
        Constructor<?> accessConstructor = access.getDeclaredConstructors()[0];
        accessConstructor.setAccessible(true);
        Direction[] directionValues = Direction.values();
        Object[] directions = java.util.Arrays.copyOf(directionValues, directionValues.length + 1, Object[].class);
        directions[directions.length - 1] = null;
        Object fakeAccess = accessConstructor.newInstance(fakePartialFor, fakePartialGet, fakeQuads,
                quadConstructor, directions);
        setStatic(bridge, "checked", true);
        setStatic(bridge, "installed", true);
        Object previousAccess = getStatic(bridge, "access");
        setStatic(bridge, "access", fakeAccess);
        try {
            Object delegate = Proxy.newProxyInstance(bakedModel.getClassLoader(), new Class<?>[]{bakedModel},
                    (proxy, method, args) -> method.getName().equals("getQuads") ? List.of() : defaultValue(method.getReturnType()));
            Object wrapped = bridge.getMethod("wrap", Object.class, Object.class).invoke(null, new FakeState(), delegate);
            require(wrapped != delegate, "fake Create belt was not wrapped");
            Object random = randomSource.getMethod("create", long.class).invoke(null, 42L);
            Method invoke = java.lang.reflect.InvocationHandler.class.getMethod("invoke", Object.class, Method.class, Object[].class);
            Object handler = Proxy.getInvocationHandler(wrapped);
            @SuppressWarnings("unchecked") List<Object> nullSide = (List<Object>) invoke.invoke(handler, wrapped, getQuads,
                    new Object[]{null, null, random});
            @SuppressWarnings("unchecked") List<Object> nominal = (List<Object>) invoke.invoke(handler, wrapped, getQuads,
                    new Object[]{null, Direction.NORTH, random});
            require(nullSide.size() == 2, "static belt partials were not emitted once through the null side");
            require(nominal.isEmpty(), "static belt partials were duplicated into nominal face calls");
            require(FakeApi.sourceVertices[0] == 0, "source BakedQuad vertices were mutated");
            System.out.println("PASS: runtime BakedQuad fixture nullSide=" + nullSide.size()
                    + " nominal=" + nominal.size() + " sourceImmutable=true");
            setStatic(bridge, "installed", false);
            require(bridge.getMethod("wrap", Object.class, Object.class).invoke(null, new FakeState(), delegate) == delegate,
                    "absent Create did not preserve the delegate model");
        } finally {
            setStatic(bridge, "installed", true);
            setStatic(bridge, "access", previousAccess);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static Object getStatic(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    public static final class FakeProperty {
        private final String name;
        FakeProperty(String name) { this.name = name; }
        public String getName() { return name; }
    }

    public static final class FakeState {
        public Object getBlock() { return new com.simibubi.create.content.kinetics.belt.BeltBlock(); }
        public Map<FakeProperty, String> getValues() {
            return Map.of(new FakeProperty("part"), "middle", new FakeProperty("slope"), "horizontal",
                    new FakeProperty("facing"), "north", new FakeProperty("casing"), "false");
        }
    }

    public static final class FakeApi {
        private static int[] sourceVertices;
        public static Object partialFor(boolean diagonal, boolean start, boolean end, boolean bottom) { return new Object(); }
        public static Object partialGet() { return new Object(); }
        public static List<BakedQuad> getQuads(Object state, Object direction, Object random) throws Exception {
            if (direction != null) return List.of();
            sourceVertices = new int[32];
            sourceVertices[0] = Float.floatToRawIntBits(0f);
            sourceVertices[1] = Float.floatToRawIntBits(0f);
            sourceVertices[2] = Float.floatToRawIntBits(0f);
            return List.of((BakedQuad) quadConstructor.newInstance(sourceVertices.clone(), -1, Direction.UP, null, true, true));
        }
    }

    private static String read(ZipFile zip, String path) throws Exception {
        var entry = zip.getEntry(path);
        require(entry != null, "Missing Create resource: " + path);
        try (var stream = zip.getInputStream(entry)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

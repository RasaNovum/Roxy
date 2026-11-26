package net.rasanovum.roxy.compat;

import net.rasanovum.roxy.core.RoxyPlatform;
import net.rasanovum.roxy.util.RoxyClassLoaders;
import net.rasanovum.roxy.util.RoxyReflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RoxyCreateStaticModelCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String CREATE = "com.simibubi.create";
    private static final int MAX_MODELS = 512;
    private static final Map<Key, Object> WRAPPERS = new LinkedHashMap<>(32, .75f, true);
    private static final Map<Object, Map<Object, List<?>>> GEOMETRY = new LinkedHashMap<>(32, .75f, true);
    private static volatile StateAccess stateAccess;
    private static volatile boolean stateAccessFailed;
    private static volatile Access access;
    private static volatile boolean integrationFailureLogged;

    private RoxyCreateStaticModelCompat() {
    }

    public static synchronized void clear() {
        WRAPPERS.clear();
        GEOMETRY.clear();
    }

    public static Object wrap(Object state, Object model) {
        if (state == null || model == null || !RoxyPlatform.isModLoaded("create")) return model;
        StateAccess base = resolveStateAccess(state.getClass().getClassLoader());
        if (base == null || !isBelt(state, base)) return model;
        synchronized (RoxyCreateStaticModelCompat.class) {
            Key key = new Key(model, stateKey(state, base));
            Object cached = WRAPPERS.get(key);
            if (cached != null) return cached;
            if (!base.bakedModel.isInterface() || !base.bakedModel.isInstance(model)) return model;
            Object wrapper = Proxy.newProxyInstance(
                    base.bakedModel.getClassLoader(),
                    new Class<?>[]{base.bakedModel},
                    new Handler(state, model)
            );
            WRAPPERS.put(key, wrapper);
            while (WRAPPERS.size() > MAX_MODELS) {
                WRAPPERS.remove(WRAPPERS.keySet().iterator().next());
            }
            return wrapper;
        }
    }

    private static StateAccess resolveStateAccess(ClassLoader hint) {
        StateAccess current = stateAccess;
        if (current != null || stateAccessFailed) return current;
        synchronized (RoxyCreateStaticModelCompat.class) {
            current = stateAccess;
            if (current != null || stateAccessFailed) return current;
            try {
                ClassLoader loader = RoxyClassLoaders.findGameLoader(hint);
                Class<?> blockStateBase = RoxyClassLoaders.loadMinecraftClass(
                        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase", loader);
                Class<?> stateHolder = RoxyClassLoaders.loadMinecraftClass(
                        "net.minecraft.world.level.block.state.StateHolder", loader);
                Class<?> property = RoxyClassLoaders.loadMinecraftClass(
                        "net.minecraft.world.level.block.state.properties.Property", loader);
                Class<?> bakedModel = RoxyClassLoaders.loadMinecraftClass(
                        "net.minecraft.client.resources.model.BakedModel", loader);
                Class<?> beltBlock = Class.forName(CREATE + ".content.kinetics.belt.BeltBlock", false, loader);
                current = new StateAccess(
                        RoxyReflection.findMethod(blockStateBase, "getBlock"),
                        RoxyReflection.findMethod(stateHolder, "getValues"),
                        RoxyReflection.findMethod(property, "getName"),
                        bakedModel,
                        beltBlock
                );
                stateAccess = current;
                return current;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                stateAccessFailed = true;
                return null;
            }
        }
    }

    private static boolean isBelt(Object state, StateAccess base) {
        try {
            Object block = base.getBlock.invoke(state);
            return block != null && base.beltBlock.isInstance(block);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static String stateKey(Object state, StateAccess base) {
        try {
            Object values = base.getValues.invoke(state);
            StringBuilder key = new StringBuilder(state.getClass().getName());
            for (Object entryObject : ((Map<?, ?>) values).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) entryObject;
                key.append('|').append(entry.getKey()).append('=').append(entry.getValue());
            }
            return key.toString();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return state.toString();
        }
    }

    private static Access access(ClassLoader loader) throws ReflectiveOperationException {
        Access current = access;
        if (current != null) return current;
        synchronized (RoxyCreateStaticModelCompat.class) {
            current = access;
            if (current != null) return current;
            ClassLoader effective = RoxyClassLoaders.findGameLoader(loader);
            Class<?> beltRenderer = Class.forName(CREATE + ".content.kinetics.belt.BeltRenderer", false, effective);
            Class<?> partial = resolvePartialModelClass(effective);
            Method partialFor = beltRenderer.getMethod("getBeltPartial", boolean.class, boolean.class, boolean.class, boolean.class);
            Method partialGet = partial.getMethod("get");
            Class<?> blockState = RoxyClassLoaders.loadMinecraftClass("net.minecraft.world.level.block.state.BlockState", effective);
            Class<?> direction = RoxyClassLoaders.loadMinecraftClass("net.minecraft.core.Direction", effective);
            Class<?> random = RoxyClassLoaders.loadMinecraftClass("net.minecraft.util.RandomSource", effective);
            Class<?> bakedQuad = RoxyClassLoaders.loadMinecraftClass("net.minecraft.client.renderer.block.model.BakedQuad", effective);
            Class<?> bakedModel = RoxyClassLoaders.loadMinecraftClass("net.minecraft.client.resources.model.BakedModel", effective);
            Method getQuads = RoxyReflection.findMethod(bakedModel, "getQuads", blockState, direction, random);
            QuadCtor quadCtor = findQuadConstructor(bakedQuad, effective);
            Object[] enumDirections = direction.getEnumConstants();
            Object[] directions = Arrays.copyOf(enumDirections, enumDirections.length + 1);
            directions[directions.length - 1] = null;
            Method stepX = RoxyReflection.findMethod(direction, "getStepX");
            Method stepY = RoxyReflection.findMethod(direction, "getStepY");
            Method stepZ = RoxyReflection.findMethod(direction, "getStepZ");
            float[][] directionSteps = new float[enumDirections.length][3];
            Map<Object, float[]> directionStepMap = new IdentityHashMap<>(enumDirections.length);
            for (int i = 0; i < enumDirections.length; i++) {
                float[] step = new float[]{
                        ((Number) stepX.invoke(enumDirections[i])).floatValue(),
                        ((Number) stepY.invoke(enumDirections[i])).floatValue(),
                        ((Number) stepZ.invoke(enumDirections[i])).floatValue()
                };
                directionSteps[i] = step;
                directionStepMap.put(enumDirections[i], step);
            }
            Method quadAo = quadCtor.sixArgs ? RoxyReflection.tryFindMethod(bakedQuad, "hasAmbientOcclusion") : null;
            current = new Access(
                    partialFor,
                    partialGet,
                    getQuads,
                    getQuads.getName(),
                    quadCtor,
                    directions,
                    enumDirections,
                    directionSteps,
                    directionStepMap,
                    RoxyReflection.findMethod(bakedQuad, "getVertices"),
                    RoxyReflection.findMethod(bakedQuad, "getTintIndex"),
                    RoxyReflection.findMethod(bakedQuad, "getDirection"),
                    RoxyReflection.findMethod(bakedQuad, "getSprite"),
                    RoxyReflection.findMethod(bakedQuad, "isShade"),
                    quadAo
            );
            access = current;
            return current;
        }
    }

    private static Class<?> resolvePartialModelClass(ClassLoader loader) throws ClassNotFoundException {
        try {
            return Class.forName("dev.engine_room.flywheel.lib.model.baked.PartialModel", false, loader);
        } catch (ClassNotFoundException ignored) {
            return Class.forName("com.jozufozu.flywheel.core.PartialModel", false, loader);
        }
    }

    private static QuadCtor findQuadConstructor(Class<?> quad, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> direction = RoxyClassLoaders.loadMinecraftClass("net.minecraft.core.Direction", loader);
        Class<?> sprite = RoxyClassLoaders.loadMinecraftClass("net.minecraft.client.renderer.texture.TextureAtlasSprite", loader);
        Constructor<?> fiveArg = null;
        for (Constructor<?> constructor : quad.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 6 && types[0] == int[].class && types[1] == int.class
                    && types[2] == direction && types[3] == sprite
                    && types[4] == boolean.class && types[5] == boolean.class) {
                return new QuadCtor(constructor, true);
            }
            if (types.length == 5 && types[0] == int[].class && types[1] == int.class
                    && types[2] == direction && types[3] == sprite
                    && types[4] == boolean.class) {
                fiveArg = constructor;
            }
        }
        if (fiveArg != null) return new QuadCtor(fiveArg, false);
        throw new NoSuchMethodException("BakedQuad constructor");
    }

    private static List<?> staticQuads(Object state, Object random) {
        try {
            StateAccess base = resolveStateAccess(state.getClass().getClassLoader());
            if (base == null) return null;
            Access api = access(state.getClass().getClassLoader());
            Map<String, String> values = values(state, base);
            String part = values.get("part");
            String slope = values.get("slope");
            String facing = values.get("facing");
            if (part == null || slope == null || facing == null) return List.of();
            boolean diagonal = "upward".equals(slope) || "downward".equals(slope);
            boolean start = "start".equals(part);
            boolean end = "end".equals(part);
            List<Object> result = new ArrayList<>();
            boolean loaded = addPartial(result, api, state, random, diagonal, start, end, false, facing, slope);
            if (!diagonal) {
                loaded &= addPartial(result, api, state, random, diagonal, start, end, true, facing, slope);
            }
            if (!loaded && result.isEmpty()) return null;
            return Collections.unmodifiableList(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!integrationFailureLogged) {
                integrationFailureLogged = true;
                LOGGER.warn("Create static LOD geometry is unavailable; preserving the native Voxy model", failure);
            }
            return null;
        }
    }

    private static boolean addPartial(List<Object> result, Access api, Object state, Object random,
                                      boolean diagonal, boolean start, boolean end, boolean bottom,
                                      String facing, String slope) throws ReflectiveOperationException {
        Object partial = api.partialFor.invoke(null, diagonal, start, end, bottom);
        if (partial == null) return false;
        Object baked = api.partialGet.invoke(partial);
        if (baked == null) return false;
        for (Object direction : api.directions) {
            List<?> quads = (List<?>) api.getQuads.invoke(baked, state, direction, random);
            if (quads == null) continue;
            for (Object quad : quads) result.add(transform(quad, facing, slope, api));
        }
        return true;
    }

    private static Object transform(Object quad, String facing, String slope, Access api) throws ReflectiveOperationException {
        int[] original = (int[]) api.quadVertices.invoke(quad);
        int[] copy = original.clone();
        float x = ("upward".equals(slope) || "downward".equals(slope)) ? 90 : 0;
        if ("downward".equals(slope)) x += 180;
        if ("sideways".equals(slope)) x += 90;
        float y = switch (facing) {
            case "south" -> 0;
            case "west" -> 90;
            case "north" -> 180;
            default -> 270;
        };
        if (("upward".equals(slope) || "downward".equals(slope)) ^ ("east".equals(facing) || "west".equals(facing))) {
            if (!"downward".equals(slope)) y += 180;
        }
        if ("sideways".equals(slope) && ("north".equals(facing) || "south".equals(facing))) y += 180;
        if ("vertical".equals(slope) && ("east".equals(facing) || "west".equals(facing))) y += 90;
        float z = ("sideways".equals(slope) || ("vertical".equals(slope) && ("east".equals(facing) || "west".equals(facing)))) ? 90 : 0;
        int stride = copy.length / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * stride;
            float[] p = rotate(Float.intBitsToFloat(copy[offset]), Float.intBitsToFloat(copy[offset + 1]),
                    Float.intBitsToFloat(copy[offset + 2]), x, y, z);
            copy[offset] = Float.floatToRawIntBits(p[0]);
            copy[offset + 1] = Float.floatToRawIntBits(p[1]);
            copy[offset + 2] = Float.floatToRawIntBits(p[2]);
            if (stride >= 8) {
                int packed = copy[offset + stride - 1];
                float[] normal = rotateVector((byte) packed / 127f, (byte) (packed >> 8) / 127f,
                        (byte) (packed >> 16) / 127f, x, y, z);
                int nx = Math.max(-127, Math.min(127, Math.round(normal[0] * 127f)));
                int ny = Math.max(-127, Math.min(127, Math.round(normal[1] * 127f)));
                int nz = Math.max(-127, Math.min(127, Math.round(normal[2] * 127f)));
                copy[offset + stride - 1] = (packed & 0xFF000000) | (nx & 0xFF)
                        | ((ny & 0xFF) << 8) | ((nz & 0xFF) << 16);
            }
        }
        int tint = (int) api.quadTintIndex.invoke(quad);
        Object direction = rotateDirection(api.quadDirection.invoke(quad), x, y, z, api);
        Object sprite = api.quadSprite.invoke(quad);
        boolean shade = (Boolean) api.quadShade.invoke(quad);
        if (api.quadCtor.sixArgs) {
            boolean ao = api.quadAo == null || Boolean.TRUE.equals(api.quadAo.invoke(quad));
            return api.quadCtor.constructor.newInstance(copy, tint, direction, sprite, shade, ao);
        }
        return api.quadCtor.constructor.newInstance(copy, tint, direction, sprite, shade);
    }

    private static Object rotateDirection(Object direction, float xDegrees, float yDegrees, float zDegrees, Access api) {
        if (direction == null) return null;
        float[] dirStep = api.directionStepMap.get(direction);
        if (dirStep == null) return direction;
        float[] rotated = rotateVector(dirStep[0], dirStep[1], dirStep[2], xDegrees, yDegrees, zDegrees);
        Object best = direction;
        float score = -Float.MAX_VALUE;
        for (int i = 0; i < api.enumDirections.length; i++) {
            float[] step = api.directionSteps[i];
            float dot = rotated[0] * step[0] + rotated[1] * step[1] + rotated[2] * step[2];
            if (dot > score) {
                score = dot;
                best = api.enumDirections[i];
            }
        }
        return best;
    }

    private static float[] rotate(float x, float y, float z, float xDegrees, float yDegrees, float zDegrees) {
        float[] result = rotateVector(x - .5f, y - .5f, z - .5f, xDegrees, yDegrees, zDegrees);
        return new float[]{result[0] + .5f, result[1] + .5f, result[2] + .5f};
    }

    private static float[] rotateVector(float x, float y, float z, float xDegrees, float yDegrees, float zDegrees) {
        double xr = Math.toRadians(xDegrees), yr = Math.toRadians(yDegrees), zr = Math.toRadians(zDegrees);
        float cy = (float) Math.cos(xr), sy = (float) Math.sin(xr);
        float ny = y * cy - z * sy, nz = y * sy + z * cy;
        float cx = (float) Math.cos(yr), sx = (float) Math.sin(yr);
        float nx = x * cx + nz * sx;
        nz = -x * sx + nz * cx;
        float cz = (float) Math.cos(zr), sz = (float) Math.sin(zr);
        return new float[]{nx * cz - ny * sz, nx * sz + ny * cz, nz};
    }

    private static Map<String, String> values(Object state, StateAccess base) throws ReflectiveOperationException {
        Map<String, String> values = new LinkedHashMap<>();
        Map<?, ?> map = (Map<?, ?>) base.getValues.invoke(state);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = (String) base.propertyName.invoke(entry.getKey());
            values.put(name, entry.getValue().toString().toLowerCase(Locale.ROOT));
        }
        return values;
    }

    private record Key(Object model, String state) {
    }

    private record StateAccess(
            Method getBlock,
            Method getValues,
            Method propertyName,
            Class<?> bakedModel,
            Class<?> beltBlock
    ) {
    }

    private record QuadCtor(Constructor<?> constructor, boolean sixArgs) {
    }

    private record Access(
            Method partialFor,
            Method partialGet,
            Method getQuads,
            String getQuadsName,
            QuadCtor quadCtor,
            Object[] directions,
            Object[] enumDirections,
            float[][] directionSteps,
            Map<Object, float[]> directionStepMap,
            Method quadVertices,
            Method quadTintIndex,
            Method quadDirection,
            Method quadSprite,
            Method quadShade,
            Method quadAo
    ) {
    }

    private static final class Handler implements InvocationHandler {
        private final Object state;
        private final Object delegate;

        private Handler(Object state, Object delegate) {
            this.state = state;
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try {
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause() != null ? exception.getCause() : exception;
            }
            if (args == null || (args.length != 3 && args.length != 5) || args[1] != null || !(result instanceof List<?> base)) {
                return result;
            }
            String methodName = method.getName();
            Access current = access;
            boolean isGetQuads = "getQuads".equals(methodName)
                    || (current != null && current.getQuadsName.equals(methodName))
                    || "method_4707".equals(methodName);
            if (!isGetQuads) return result;
            List<?> extra = geometry(state, args[2]);
            if (extra.isEmpty()) return result;
            List<Object> merged = new ArrayList<>(base.size() + extra.size());
            merged.addAll(base);
            merged.addAll(extra);
            return merged;
        }
    }

    private static List<?> geometry(Object state, Object random) {
        synchronized (RoxyCreateStaticModelCompat.class) {
            Object randomKey = random == null ? Void.class : random.getClass();
            Map<Object, List<?>> byRandom = GEOMETRY.computeIfAbsent(state, ignored -> new IdentityHashMap<>());
            List<?> cached = byRandom.get(randomKey);
            if (cached != null) return cached;
            List<?> result = staticQuads(state, random);
            if (result == null) return List.of();
            byRandom.put(randomKey, result);
            while (GEOMETRY.size() > MAX_MODELS) GEOMETRY.remove(GEOMETRY.keySet().iterator().next());
            return result;
        }
    }
}

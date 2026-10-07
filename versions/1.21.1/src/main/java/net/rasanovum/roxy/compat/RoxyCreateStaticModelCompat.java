package net.rasanovum.roxy.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import net.neoforged.fml.loading.LoadingModList;
import java.util.logging.Logger;

public final class RoxyCreateStaticModelCompat {
    private static final Logger LOGGER = Logger.getLogger("Roxy");
    private static final String CREATE = "com.simibubi.create";
    private static final int MAX_MODELS = 512;
    private static final Map<Key, Object> WRAPPERS = new LinkedHashMap<>(32, .75f, true);
    private static final Map<Object, Map<Object, List<?>>> GEOMETRY = new LinkedHashMap<>(32, .75f, true);
    private static volatile boolean checked;
    private static volatile boolean installed;
    private static volatile Object lastModel;
    private static volatile Access access;
    private static volatile boolean integrationFailureLogged;

    private RoxyCreateStaticModelCompat() {
    }

    public static Object wrap(Object state, Object model) {
        if (state == null || model == null || !isCreateLoaded() || !isBelt(state)) return model;
        synchronized (RoxyCreateStaticModelCompat.class) {
            if (model != lastModel) {
                WRAPPERS.clear();
                GEOMETRY.clear();
                lastModel = model;
            }
            Key key = new Key(model, stateKey(state));
            Object cached = WRAPPERS.get(key);
            if (cached != null) return cached;
            Class<?> bakedModel = findBakedModel(model.getClass());
            if (bakedModel == null || !bakedModel.isInterface()) return model;
            Object wrapper = Proxy.newProxyInstance(bakedModel.getClassLoader(), new Class<?>[]{bakedModel},
                    new Handler(state, model));
            WRAPPERS.put(key, wrapper);
            while (WRAPPERS.size() > MAX_MODELS) {
                WRAPPERS.remove(WRAPPERS.keySet().iterator().next());
            }
            return wrapper;
        }
    }

    private static boolean isCreateLoaded() {
        if (!checked) {
            synchronized (RoxyCreateStaticModelCompat.class) {
                if (!checked) {
                    try {
                        LoadingModList mods = LoadingModList.get();
                        installed = mods != null && mods.getModFileById("create") != null;
                    } catch (RuntimeException | LinkageError ignored) {
                        installed = false;
                    }
                    checked = true;
                }
            }
        }
        return installed;
    }

    private static boolean isBelt(Object state) {
        try {
            return state.getClass().getMethod("getBlock").invoke(state).getClass().getName()
                    .equals(CREATE + ".content.kinetics.belt.BeltBlock");
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static String stateKey(Object state) {
        try {
            Object values = state.getClass().getMethod("getValues").invoke(state);
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

    private static Class<?> findBakedModel(Class<?> modelClass) {
        for (Class<?> type : modelClass.getInterfaces()) {
            if (type.getName().equals("net.minecraft.client.resources.model.BakedModel")) return type;
        }
        for (Class<?> type = modelClass; type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                if (iface.getName().equals("net.minecraft.client.resources.model.BakedModel")) return iface;
            }
        }
        return null;
    }

    private static Access access(ClassLoader loader) throws ReflectiveOperationException {
        Access current = access;
        if (current != null) return current;
        synchronized (RoxyCreateStaticModelCompat.class) {
            current = access;
            if (current != null) return current;
            Class<?> beltRenderer = Class.forName(CREATE + ".content.kinetics.belt.BeltRenderer", false, loader);
            Class<?> partial = Class.forName("dev.engine_room.flywheel.lib.model.baked.PartialModel", false, loader);
            Method partialFor = beltRenderer.getMethod("getBeltPartial", boolean.class, boolean.class, boolean.class, boolean.class);
            Method partialGet = partial.getMethod("get");
            Class<?> blockState = Class.forName("net.minecraft.world.level.block.state.BlockState", false, loader);
            Class<?> direction = Class.forName("net.minecraft.core.Direction", false, loader);
            Class<?> random = Class.forName("net.minecraft.util.RandomSource", false, loader);
            Class<?> bakedQuad = Class.forName("net.minecraft.client.renderer.block.model.BakedQuad", false, loader);
            Class<?> bakedModel = Class.forName("net.minecraft.client.resources.model.BakedModel", false, loader);
            Method getQuads = bakedModel.getMethod("getQuads", blockState, direction, random);
            Constructor<?> quadConstructor = findQuadConstructor(bakedQuad, blockState.getClass().getClassLoader());
            Object[] directions = Class.forName("net.minecraft.core.Direction", false, loader).getEnumConstants();
            directions = Arrays.copyOf(directions, directions.length + 1);
            directions[directions.length - 1] = null;
            current = new Access(partialFor, partialGet, getQuads, quadConstructor, directions);
            access = current;
            return current;
        }
    }

    private static Constructor<?> findQuadConstructor(Class<?> quad, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> direction = Class.forName("net.minecraft.core.Direction", false, loader);
        Class<?> sprite = Class.forName("net.minecraft.client.renderer.texture.TextureAtlasSprite", false, loader);
        for (Constructor<?> constructor : quad.getConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            if (types.length == 6 && types[0] == int[].class && types[1] == int.class
                    && types[2] == direction && types[3] == sprite
                    && types[4] == boolean.class && types[5] == boolean.class) return constructor;
        }
        throw new NoSuchMethodException("BakedQuad six-argument constructor");
    }

    private static List<?> staticQuads(Object state, Object random) {
        try {
            Access api = access(state.getClass().getClassLoader());
            Map<String, String> values = values(state);
            String part = values.get("part");
            String slope = values.get("slope");
            String facing = values.get("facing");
            if (part == null || slope == null || facing == null) return List.of();
            boolean diagonal = "upward".equals(slope) || "downward".equals(slope);
            boolean start = "start".equals(part);
            boolean end = "end".equals(part);
            List<Object> result = new ArrayList<>();
            addPartial(result, api, state, random, diagonal, start, end, false, facing, slope);
            if (!diagonal) addPartial(result, api, state, random, diagonal, start, end, true, facing, slope);
            return Collections.unmodifiableList(result);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (!integrationFailureLogged) {
                integrationFailureLogged = true;
                LOGGER.log(Level.WARNING, "Create static LOD geometry is unavailable; preserving the native Voxy model", failure);
            }
            return List.of();
        }
    }

    private static void addPartial(List<Object> result, Access api, Object state, Object random,
                                   boolean diagonal, boolean start, boolean end, boolean bottom,
                                   String facing, String slope) throws ReflectiveOperationException {
        Object partial = api.partialFor.invoke(null, diagonal, start, end, bottom);
        Object baked = api.partialGet.invoke(partial);
        if (baked == null) return;
        for (Object direction : api.directions) {
            List<?> quads = (List<?>) api.getQuads.invoke(baked, state, direction, random);
            for (Object quad : quads) result.add(transform(quad, facing, slope, api));
        }
    }

    private static Object transform(Object quad, String facing, String slope, Access api) throws ReflectiveOperationException {
        Method vertices = quad.getClass().getMethod("getVertices");
        int[] original = (int[]) vertices.invoke(quad);
        int[] copy = original.clone();
        float x = ("upward".equals(slope) || "downward".equals(slope)) ? 90 : 0;
        if ("downward".equals(slope)) x += 180;
        if ("sideways".equals(slope)) x += 90;
        if ("vertical".equals(slope) && ("east".equals(facing) || "west".equals(facing))) x += 0;
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
        int tint = (int) quad.getClass().getMethod("getTintIndex").invoke(quad);
        Object direction = quad.getClass().getMethod("getDirection").invoke(quad);
        direction = rotateDirection(direction, x, y, z);
        Object sprite = quad.getClass().getMethod("getSprite").invoke(quad);
        boolean shade = (Boolean) quad.getClass().getMethod("isShade").invoke(quad);
        boolean ao = (Boolean) quad.getClass().getMethod("hasAmbientOcclusion").invoke(quad);
        return api.quadConstructor.newInstance(copy, tint, direction, sprite, shade, ao);
    }

    private static Object rotateDirection(Object direction, float xDegrees, float yDegrees, float zDegrees)
            throws ReflectiveOperationException {
        if (direction == null) return null;
        Class<?> type = direction.getClass();
        Method stepX = type.getMethod("getStepX");
        Method stepY = type.getMethod("getStepY");
        Method stepZ = type.getMethod("getStepZ");
        float[] rotated = rotateVector(((Number) stepX.invoke(direction)).floatValue(),
                ((Number) stepY.invoke(direction)).floatValue(),
                ((Number) stepZ.invoke(direction)).floatValue(), xDegrees, yDegrees, zDegrees);
        Object best = direction;
        float score = -Float.MAX_VALUE;
        for (Object candidate : type.getEnumConstants()) {
            float dot = rotated[0] * ((Number) stepX.invoke(candidate)).floatValue()
                    + rotated[1] * ((Number) stepY.invoke(candidate)).floatValue()
                    + rotated[2] * ((Number) stepZ.invoke(candidate)).floatValue();
            if (dot > score) { score = dot; best = candidate; }
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
        float nx = x * cx + nz * sx; nz = -x * sx + nz * cx;
        float cz = (float) Math.cos(zr), sz = (float) Math.sin(zr);
        return new float[]{nx * cz - ny * sz, nx * sz + ny * cz, nz};
    }

    private static Map<String, String> values(Object state) throws ReflectiveOperationException {
        Map<String, String> values = new LinkedHashMap<>();
        Map<?, ?> map = (Map<?, ?>) state.getClass().getMethod("getValues").invoke(state);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = (String) entry.getKey().getClass().getMethod("getName").invoke(entry.getKey());
            values.put(name, entry.getValue().toString().toLowerCase(java.util.Locale.ROOT));
        }
        return values;
    }

    private record Key(Object model, String state) {
    }

    private record Access(Method partialFor, Method partialGet, Method getQuads,
                          Constructor<?> quadConstructor, Object[] directions) {
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
            Object result = method.invoke(delegate, args);
            if (!"getQuads".equals(method.getName()) || args == null || args.length != 3 || !(result instanceof List<?> base)) {
                return result;
            }
            Object random = args[2];
            List<?> extra = geometry(state, random);
            if (extra.isEmpty()) return result;
            Object direction = args[1];
            List<Object> merged = new ArrayList<>(base.size() + extra.size());
            merged.addAll(base);
            if (direction == null) merged.addAll(extra);
            return merged;
        }
    }

    private static List<?> geometry(Object state, Object random) {
        synchronized (RoxyCreateStaticModelCompat.class) {
            Map<Object, List<?>> byRandom = GEOMETRY.computeIfAbsent(state, ignored -> new IdentityHashMap<>());
            List<?> cached = byRandom.get(random.getClass());
            if (cached != null) return cached;
            List<?> result = staticQuads(state, random);
            byRandom.put(random.getClass(), result);
            while (GEOMETRY.size() > MAX_MODELS) GEOMETRY.remove(GEOMETRY.keySet().iterator().next());
            return result;
        }
    }
}

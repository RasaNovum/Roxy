package net.rasanovum.roxy.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RoxyModelTintBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final Map<Object, TintPlan> PLANS = new ConcurrentHashMap<>();

    private RoxyModelTintBridge() {
    }

    public static void beginBlock(Object state) {
        PLANS.put(state, new TintPlan());
    }

    public static int metadata(Object state, Object quad, int layerMetadata) {
        try {
            int index = (int) quad.getClass().getMethod("getTintIndex").invoke(quad);
            if (index < 0) return layerMetadata;
            TintPlan plan = PLANS.computeIfAbsent(state, ignored -> new TintPlan());
            synchronized (plan) {
                Tint tint = plan.tints.get(index);
                if (tint == null) {
                    tint = inspectTint(state, index);
                    plan.tints.put(index, tint);
                }
                if (tint.biomeDependent) {
                    if (plan.biomeIndex < 0) plan.biomeIndex = index;
                    if (plan.biomeIndex != index && !plan.warned) {
                        plan.warned = true;
                        LOGGER.warn("Voxy supports one biome tint per model: {} uses indices {} and {}",
                                state, plan.biomeIndex, index);
                    }
                    return layerMetadata | 4;
                }
                return layerMetadata | 8 | (tint.colour & 0xFFFFFF) << 4;
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to preserve block model tint indices", exception);
        }
    }

    public static Object wrapProvider(Object provider) {
        if (provider == null) return null;
        try {
            ClassLoader loader = provider.getClass().getClassLoader();
            Class<?> type = Class.forName("net.minecraft.client.color.block.BlockColor", false, loader);
            return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getName().equals("getColor") && args != null && args.length == 4) {
                    TintPlan plan = PLANS.get(args[0]);
                    if (plan != null && plan.biomeIndex >= 0) args[3] = plan.biomeIndex;
                }
                return method.invoke(provider, args);
            });
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to select the model's biome tint index", exception);
        }
    }

    public static int tintSample(int abgr, int metadata) {
        if ((metadata & 8) == 0) return abgr;
        int rgb = metadata >>> 4;
        int red = (abgr & 255) * ((rgb >>> 16) & 255) / 255;
        int green = ((abgr >>> 8) & 255) * ((rgb >>> 8) & 255) / 255;
        int blue = ((abgr >>> 16) & 255) * (rgb & 255) / 255;
        return (abgr & 0xFF000000) | red | green << 8 | blue << 16;
    }

    private static Tint inspectTint(Object state, int index) throws ReflectiveOperationException {
        ClassLoader loader = state.getClass().getClassLoader();
        Class<?> stateType = Class.forName("net.minecraft.world.level.block.state.BlockState", false, loader);
        Class<?> levelType = Class.forName("net.minecraft.world.level.BlockAndTintGetter", false, loader);
        Class<?> posType = Class.forName("net.minecraft.core.BlockPos", false, loader);
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
        Object colours = minecraft.getMethod("getBlockColors").invoke(minecraft.getMethod("getInstance").invoke(null));
        Class<?> probeType = Class.forName("me.cortex.voxy.client.core.model.ModelFactory$3", false, loader);
        Constructor<?> constructor = probeType.getDeclaredConstructor(boolean[].class, stateType);
        constructor.setAccessible(true);
        boolean[] dependent = {false};
        Object probe = constructor.newInstance(dependent, state);
        Method getColor = colours.getClass().getMethod("getColor", stateType, levelType, posType, int.class);
        int colour = (int) getColor.invoke(colours, state, probe, posType.getField("ZERO").get(null), index);
        return new Tint(colour, dependent[0]);
    }

    private static final class TintPlan {
        private final Map<Integer, Tint> tints = new HashMap<>();
        private volatile int biomeIndex = -1;
        private boolean warned;
    }

    private record Tint(int colour, boolean biomeDependent) {
    }
}

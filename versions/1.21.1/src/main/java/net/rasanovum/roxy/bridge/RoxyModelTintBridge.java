package net.rasanovum.roxy.bridge;

import net.rasanovum.roxy.compat.RoxyCompatConfig;
import net.rasanovum.roxy.tfc.TfcVoxyBridge;
import net.rasanovum.roxy.util.RoxyClassLoaders;
import net.rasanovum.roxy.util.RoxyReflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RoxyModelTintBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final Map<Object, TintPlan> PLANS = new ConcurrentHashMap<>();
    private static volatile boolean tintFailureLogged;
    private static volatile boolean providerFailureLogged;
    private static final ClassValue<Method> TINT_INDEX = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                return RoxyReflection.findMethod(type, "getTintIndex");
            } catch (NoSuchMethodException exception) {
                throw new IllegalStateException("Unable to resolve BakedQuad.getTintIndex", exception);
            }
        }
    };
    private static final ClassValue<InspectAccess> INSPECT_ACCESS = new ClassValue<>() {
        @Override
        protected InspectAccess computeValue(Class<?> stateClass) {
            try {
                ClassLoader loader = RoxyClassLoaders.findGameLoader(stateClass.getClassLoader());
                Class<?> stateType = Class.forName("net.minecraft.world.level.block.state.BlockState", false, loader);
                Class<?> levelType = Class.forName("net.minecraft.world.level.BlockAndTintGetter", false, loader);
                Class<?> posType = Class.forName("net.minecraft.core.BlockPos", false, loader);
                Class<?> minecraftType = Class.forName("net.minecraft.client.Minecraft", false, loader);
                Class<?> blockColorType = Class.forName("net.minecraft.client.color.block.BlockColor", false, loader);
                Class<?> blockColorsType = Class.forName("net.minecraft.client.color.block.BlockColors", false, loader);
                Class<?> fluidStateType = Class.forName("net.minecraft.world.level.material.FluidState", false, loader);
                Class<?> fluidsType = Class.forName("net.minecraft.world.level.material.Fluids", false, loader);
                Object emptyFluid = fluidsType.getField("EMPTY").get(null);
                Object emptyFluidState = emptyFluid.getClass().getMethod("defaultFluidState").invoke(emptyFluid);
                return new InspectAccess(
                        loader,
                        levelType,
                        blockColorType,
                        minecraftType.getMethod("getInstance"),
                        minecraftType.getMethod("getBlockColors"),
                        blockColorsType.getMethod("getColor", stateType, levelType, posType, int.class),
                        posType.getField("ZERO").get(null),
                        fluidStateType,
                        emptyFluidState
                );
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Unable to resolve Minecraft tint inspection API", exception);
            }
        }
    };

    private RoxyModelTintBridge() {
    }

    public static void clear() {
        PLANS.clear();
    }

    public static void beginBlock(Object state) {
        if (state == null) return;
        PLANS.put(state, new TintPlan());
    }

    public static int metadata(Object state, Object quad, int layerMetadata) {
        if (state == null || quad == null) return layerMetadata;
        try {
            int index = (int) TINT_INDEX.get(quad.getClass()).invoke(quad);
            if (index < 0) return layerMetadata;
            if (RoxyCompatConfig.tfcEnabled() && TfcVoxyBridge.isBakingVariant()) {
                return TfcVoxyBridge.tintMetadata(layerMetadata, index);
            }
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
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("Unable to preserve block model tint indices", cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to preserve block model tint indices", exception);
        }
    }

    public static Object wrapProvider(Object provider) {
        if (RoxyCompatConfig.tfcEnabled() && TfcVoxyBridge.isBakingVariant()) return null;
        if (provider == null) return null;
        try {
            ClassLoader loader = RoxyClassLoaders.findLoaderFor(
                    "net.minecraft.client.color.block.BlockColor",
                    provider.getClass().getClassLoader()
            );
            Class<?> type = Class.forName("net.minecraft.client.color.block.BlockColor", false, loader);
            return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
                boolean isGetColor = method.getName().equals("getColor") && args != null && args.length == 4;
                if (isGetColor && args[0] != null) {
                    TintPlan plan = PLANS.get(args[0]);
                    if (plan != null && plan.biomeIndex >= 0) args[3] = plan.biomeIndex;
                }
                try {
                    return method.invoke(provider, args);
                } catch (InvocationTargetException exception) {
                    Throwable target = exception.getTargetException();
                    if (isGetColor && target instanceof RuntimeException && isVoxySyntheticGetter(args[1])) {
                        if (!providerFailureLogged) {
                            providerFailureLogged = true;
                            LOGGER.warn("Unable to evaluate block color provider {} for {} on Voxy's synthetic level; falling back to untinted",
                                    provider.getClass().getName(), args[0], target);
                        }
                        return -1;
                    }
                    throw target;
                }
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
        InspectAccess access = INSPECT_ACCESS.get(state.getClass());
        Object minecraft = access.minecraftInstance.invoke(null);
        Object colours = access.getBlockColors.invoke(minecraft);
        boolean[] dependent = {false};
        Object probe = Proxy.newProxyInstance(
                access.loader,
                new Class<?>[]{access.levelType},
                (proxy, method, args) -> handleProbeCall(method, proxy, args, state, dependent, access)
        );
        try {
            int colour = (int) access.getColor.invoke(colours, state, probe, access.blockPosZero, index);
            return new Tint(colour, dependent[0]);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getTargetException();
            if (cause instanceof Error error) throw error;
            if (!tintFailureLogged) {
                tintFailureLogged = true;
                LOGGER.warn("Unable to inspect tint color for {} (index {}); falling back to untinted", state, index, cause);
            }
            return new Tint(-1, dependent[0]);
        } catch (RuntimeException exception) {
            if (!tintFailureLogged) {
                tintFailureLogged = true;
                LOGGER.warn("Unable to inspect tint color for {} (index {}); falling back to untinted", state, index, exception);
            }
            return new Tint(-1, dependent[0]);
        }
    }

    private static boolean isVoxySyntheticGetter(Object getter) {
        return getter != null && getter.getClass().getName().startsWith("me.cortex.voxy.");
    }

    private static Object handleProbeCall(
            Method method,
            Object proxy,
            Object[] args,
            Object state,
            boolean[] dependent,
            InspectAccess access
    ) {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "toString" -> "RoxyTintProbe";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> args != null && args.length == 1 && proxy == args[0];
                default -> null;
            };
        }
        String name = method.getName();
        if (name.equals("getBlockTint")) {
            dependent[0] = true;
            return 0xFFFFFF;
        }
        if (name.equals("getBlockState")) {
            return state;
        }
        if (name.equals("getFluidState") || method.getReturnType() == access.fluidStateType) {
            return access.emptyFluidState;
        }
        if (name.equals("getShade")) {
            return 1.0F;
        }
        if (name.equals("getHeight")) {
            return 16;
        }
        if (name.equals("getMinBuildHeight")) {
            return 0;
        }
        return defaultValue(method.getReturnType());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        return null;
    }

    private record InspectAccess(
            ClassLoader loader,
            Class<?> levelType,
            Class<?> blockColorType,
            Method minecraftInstance,
            Method getBlockColors,
            Method getColor,
            Object blockPosZero,
            Class<?> fluidStateType,
            Object emptyFluidState
    ) {
    }

    private static final class TintPlan {
        private final Map<Integer, Tint> tints = new HashMap<>();
        private volatile int biomeIndex = -1;
        private boolean warned;
    }

    private record Tint(int colour, boolean biomeDependent) {
    }
}

package net.rasanovum.roxy.core;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class RoxyPlatform {
    private static volatile Path configDirectory;
    private static volatile Predicate<String> modLoadedChecker = id -> false;
    private static volatile RoxyMappings mappings;
    private static volatile boolean intermediaryRuntime;
    private static final Map<String, Boolean> MOD_LOADED_CACHE = new ConcurrentHashMap<>();

    private RoxyPlatform() {
    }

    public static void configure(
            Path configDir,
            Predicate<String> modLoaded,
            RoxyMappings runtimeMappings,
            boolean outputsIntermediary
    ) {
        configDirectory = configDir;
        modLoadedChecker = modLoaded == null ? id -> false : modLoaded;
        mappings = runtimeMappings;
        intermediaryRuntime = outputsIntermediary;
        MOD_LOADED_CACHE.clear();
    }

    public static Path configDir() {
        return configDirectory;
    }

    public static boolean isModLoaded(String modId) {
        if (modId == null || modId.isEmpty()) return false;
        Boolean cached = MOD_LOADED_CACHE.get(modId);
        if (cached != null) return cached;
        boolean loaded = modLoadedChecker.test(modId);
        MOD_LOADED_CACHE.put(modId, loaded);
        return loaded;
    }

    public static RoxyMappings mappings() {
        return mappings;
    }

    public static boolean isIntermediaryRuntime() {
        return intermediaryRuntime;
    }

    public static String resolveRuntimeClassName(String mojmapBinaryName) {
        if (!intermediaryRuntime || mojmapBinaryName == null) return mojmapBinaryName;
        RoxyMappings current = mappings;
        if (current == null) return mojmapBinaryName;
        String internal = mojmapBinaryName.replace('.', '/');
        String mapped = current.toIntermediary().mapClass(internal);
        return mapped.replace('/', '.');
    }

    public static String resolveRuntimeFieldName(Class<?> owner, String mojmapFieldName) {
        if (!intermediaryRuntime || owner == null || mojmapFieldName == null) return mojmapFieldName;
        RoxyMappings current = mappings;
        if (current == null) return mojmapFieldName;
        RoxyMappings toMojmap = current.toMojmap();
        RoxyMappings toIntermediary = current.toIntermediary();
        for (Class<?> candidate : runtimeHierarchy(owner)) {
            String mojmapOwner = toMojmap.mapClass(candidate.getName().replace('.', '/'));
            String mapped = toIntermediary.mapFieldNameWithoutDescriptor(mojmapOwner, mojmapFieldName);
            if (!mapped.equals(mojmapFieldName)) return mapped;
        }
        return mojmapFieldName;
    }

    public static String resolveRuntimeMethodName(Class<?> owner, String mojmapMethodName, Class<?>... parameterTypes) {
        if (!intermediaryRuntime || owner == null || mojmapMethodName == null) return mojmapMethodName;
        RoxyMappings current = mappings;
        if (current == null) return mojmapMethodName;
        RoxyMappings toMojmap = current.toMojmap();
        RoxyMappings toIntermediary = current.toIntermediary();
        String paramPrefix = parameterTypes == null ? null : buildMojmapParamPrefix(current, parameterTypes);
        for (Class<?> candidate : runtimeHierarchy(owner)) {
            String mojmapOwner = toMojmap.mapClass(candidate.getName().replace('.', '/'));
            String mapped = paramPrefix != null
                    ? toIntermediary.mapMethodNameByParams(mojmapOwner, mojmapMethodName, paramPrefix)
                    : toIntermediary.mapMethodNameWithoutDescriptor(mojmapOwner, mojmapMethodName);
            if (!mapped.equals(mojmapMethodName)) return mapped;
        }
        return mojmapMethodName;
    }

    public static String resolveRuntimeMethodNameAnyParams(Class<?> owner, String mojmapMethodName) {
        if (!intermediaryRuntime || owner == null || mojmapMethodName == null) return mojmapMethodName;
        RoxyMappings current = mappings;
        if (current == null) return mojmapMethodName;
        RoxyMappings toMojmap = current.toMojmap();
        RoxyMappings toIntermediary = current.toIntermediary();
        for (Class<?> candidate : runtimeHierarchy(owner)) {
            String mojmapOwner = toMojmap.mapClass(candidate.getName().replace('.', '/'));
            String mapped = toIntermediary.mapMethodNameWithoutDescriptor(mojmapOwner, mojmapMethodName);
            if (!mapped.equals(mojmapMethodName)) return mapped;
        }
        return mojmapMethodName;
    }

    public static List<String> resolveRuntimeMethodNamesByArity(Class<?> owner, String mojmapMethodName, int parameterCount) {
        if (!intermediaryRuntime || owner == null || mojmapMethodName == null) return List.of();
        RoxyMappings current = mappings;
        if (current == null) return List.of();
        RoxyMappings toMojmap = current.toMojmap();
        RoxyMappings toIntermediary = current.toIntermediary();
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> candidate : runtimeHierarchy(owner)) {
            String mojmapOwner = toMojmap.mapClass(candidate.getName().replace('.', '/'));
            names.addAll(toIntermediary.findMethodNamesByArity(mojmapOwner, mojmapMethodName, parameterCount));
        }
        return names.isEmpty() ? List.of() : List.copyOf(names);
    }

    private static List<Class<?>> runtimeHierarchy(Class<?> root) {
        Set<Class<?>> visited = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> ordered = new LinkedHashSet<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();
            if (current == null || current == Object.class || !visited.add(current)) continue;
            ordered.add(current);
            Class<?> superClass = current.getSuperclass();
            if (superClass != null && superClass != Object.class) queue.add(superClass);
            for (Class<?> iface : current.getInterfaces()) {
                if (iface != null) queue.add(iface);
            }
        }
        return List.copyOf(ordered);
    }

    private static String buildMojmapParamPrefix(RoxyMappings current, Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder("(");
        for (Class<?> type : parameterTypes) {
            appendMojmapDescriptor(builder, current, type);
        }
        builder.append(')');
        return builder.toString();
    }

    private static void appendMojmapDescriptor(StringBuilder builder, RoxyMappings current, Class<?> type) {
        if (type == boolean.class) {
            builder.append('Z');
        } else if (type == byte.class) {
            builder.append('B');
        } else if (type == char.class) {
            builder.append('C');
        } else if (type == short.class) {
            builder.append('S');
        } else if (type == int.class) {
            builder.append('I');
        } else if (type == long.class) {
            builder.append('J');
        } else if (type == float.class) {
            builder.append('F');
        } else if (type == double.class) {
            builder.append('D');
        } else if (type == void.class) {
            builder.append('V');
        } else if (type.isArray()) {
            builder.append('[');
            appendMojmapDescriptor(builder, current, type.getComponentType());
        } else {
            String internal = type.getName().replace('.', '/');
            String mojmap = current.toMojmap().mapClass(internal);
            builder.append('L').append(mojmap).append(';');
        }
    }
}

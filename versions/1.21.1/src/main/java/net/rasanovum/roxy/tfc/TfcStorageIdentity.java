package net.rasanovum.roxy.tfc;

import java.lang.reflect.Method;
import java.nio.file.Path;

public final class TfcStorageIdentity {
    private TfcStorageIdentity() {
    }

    public static Path resolve(Object voxyInstance, Object level) throws ReflectiveOperationException {
        if (voxyInstance == null || level == null) return null;
        ClassLoader loader = voxyInstance.getClass().getClassLoader();
        Class<?> identifierType = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier", false, loader);
        Method of = null;
        for (Method method : identifierType.getMethods()) {
            if (method.getName().equals("of") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(level)) {
                of = method;
                break;
            }
        }
        if (of == null) throw new NoSuchMethodException("WorldIdentifier.of(Level)");
        Object identifier = of.invoke(null, level);
        if (identifier == null) return null;
        String worldId = (String) identifierType.getMethod("getWorldId").invoke(identifier);
        if (worldId == null || !worldId.matches("[0-9a-f]{32}")) {
            throw new IllegalStateException("Unexpected Voxy world identifier");
        }
        Path base = (Path) voxyInstance.getClass().getMethod("getStorageBasePath").invoke(voxyInstance);
        if (base == null) return null;
        return base.toAbsolutePath().normalize().resolve("roxy-tfc").resolve(worldId).resolve("climate-v1.bin");
    }
}

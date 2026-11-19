package net.fabricmc.loader.api;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FabricLoader {
    static FabricLoader getInstance() {
        try {
            Class<?> bridge = Class.forName(
                    "net.fabricmc.loader.api.RoxyFabricLoaderBridge",
                    true,
                    FabricLoader.class.getClassLoader()
            );
            var field = bridge.getDeclaredField("INSTANCE");
            field.setAccessible(true);
            return (FabricLoader) field.get(null);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("Roxy: unable to initialize the fallback Fabric bridge", exception);
        }
    }

    boolean isModLoaded(String modId);
    EnvType getEnvironmentType();
    Optional<ModContainer> getModContainer(String modId);
    <T> List<T> getEntrypoints(String key, Class<T> type);
    Path getConfigDir();

    default Collection<ModContainer> getAllMods() {
        return List.of();
    }

    default <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        return List.of();
    }

    default Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    default boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.production;
    }
}

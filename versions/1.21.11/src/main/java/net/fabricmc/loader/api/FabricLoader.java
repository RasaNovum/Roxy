package net.fabricmc.loader.api;

import net.rasanovum.roxy.loader.RoxyFabricLoaderImpl;
import net.fabricmc.api.EnvType;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface FabricLoader {
    static FabricLoader getInstance() {
        return RoxyFabricLoaderImpl.INSTANCE;
    }

    boolean isModLoaded(String modId);
    EnvType getEnvironmentType();
    Optional<ModContainer> getModContainer(String modId);
    <T> List<T> getEntrypoints(String key, Class<T> type);
    Path getConfigDir();
}

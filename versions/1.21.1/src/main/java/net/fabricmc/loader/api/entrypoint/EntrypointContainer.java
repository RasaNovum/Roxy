package net.fabricmc.loader.api.entrypoint;

import net.fabricmc.loader.api.ModContainer;

public interface EntrypointContainer<T> {
    T getEntrypoint();
    ModContainer getProvider();

    default String getDefinition() {
        return "";
    }
}

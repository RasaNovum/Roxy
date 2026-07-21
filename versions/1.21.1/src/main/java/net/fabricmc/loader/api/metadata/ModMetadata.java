package net.fabricmc.loader.api.metadata;

import net.fabricmc.loader.api.Version;

public interface ModMetadata {
    Version getVersion();
    CustomValue getCustomValue(String key);
}

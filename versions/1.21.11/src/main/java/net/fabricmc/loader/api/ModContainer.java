package net.fabricmc.loader.api;

import net.fabricmc.loader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.List;

public interface ModContainer {
    ModMetadata getMetadata();
    List<Path> getRootPaths();
}

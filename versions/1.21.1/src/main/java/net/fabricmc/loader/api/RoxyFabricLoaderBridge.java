package net.fabricmc.loader.api;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

final class RoxyFabricLoaderBridge implements FabricLoader {
    static final RoxyFabricLoaderBridge INSTANCE = new RoxyFabricLoaderBridge();

    private RoxyFabricLoaderBridge() {
    }

    @Override
    public boolean isModLoaded(String modId) {
        ModList modList = ModList.get();
        return (modList != null && modList.isLoaded(modId))
                || modId.equals("voxy");
    }

    @Override
    public EnvType getEnvironmentType() {
        return FMLEnvironment.dist.isClient() ? EnvType.CLIENT : EnvType.SERVER;
    }

    @Override
    public Optional<ModContainer> getModContainer(String modId) {
        ModList modList = ModList.get();
        if (modList != null) {
            Optional<ModContainer> loaded = modList.getModContainerById(modId).map(RoxyModContainer::new);
            if (loaded.isPresent()) return loaded;
        }
        if (!modId.equals("voxy")) return Optional.empty();
        return Optional.of(new DiskModContainer(
                findVoxyFile().orElse(Path.of("voxy.jar"))
        ));
    }

    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        return List.of();
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    private record RoxyModContainer(net.neoforged.fml.ModContainer delegate) implements ModContainer {
        @Override
        public ModMetadata getMetadata() {
            return new RoxyModMetadata(delegate.getModInfo());
        }

        @Override
        public List<Path> getRootPaths() {
            Path file = delegate.getModInfo().getOwningFile().getFile().getFilePath();
            return List.of(file);
        }
    }

    private record RoxyModMetadata(IModInfo info) implements ModMetadata {
        @Override
        public Version getVersion() {
            String version = info.getVersion().toString();
            return () -> version;
        }

        @Override
        public CustomValue getCustomValue(String key) {
            Object value = info.getModProperties().get(key);
            String string = value == null ? "" : value.toString();
            if (key.equals("commit") && string.isBlank()) {
                string = "roxycompat0000000000000000000000000000000";
            }
            if (value == null && !key.equals("commit")) return null;
            String result = string;
            return () -> result;
        }
    }

    private record DiskModContainer(Path file) implements ModContainer {
        @Override
        public ModMetadata getMetadata() {
            String name = file.getFileName().toString();
            String version = name.startsWith("voxy-") && name.endsWith(".jar")
                    ? name.substring("voxy-".length(), name.length() - ".jar".length())
                    : "0.0.0";
            return new DiskModMetadata(version);
        }

        @Override
        public List<Path> getRootPaths() {
            return List.of(file);
        }
    }

    private record DiskModMetadata(String version) implements ModMetadata {
        @Override
        public Version getVersion() {
            return () -> version;
        }

        @Override
        public CustomValue getCustomValue(String key) {
            return key.equals("commit")
                    ? () -> "roxycompat0000000000000000000000000000000"
                    : null;
        }
    }

    private static Optional<Path> findVoxyFile() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> voxy = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false, loader);
            if (voxy.getProtectionDomain().getCodeSource() != null) {
                Path codeSource = Path.of(voxy.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (Files.exists(codeSource)) return Optional.of(codeSource);
            }
        } catch (ReflectiveOperationException | URISyntaxException | RuntimeException ignored) {
        }

        Path gameDirectory;
        try {
            gameDirectory = FMLPaths.GAMEDIR.get();
        } catch (RuntimeException exception) {
            gameDirectory = Path.of("").toAbsolutePath();
        }

        for (Path directory : List.of(
                gameDirectory.resolve("mods"),
                Path.of("").toAbsolutePath().resolve("mods")
        )) {
            if (!Files.isDirectory(directory)) continue;
            try (var files = Files.list(directory)) {
                Optional<Path> match = files
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                            return name.startsWith("voxy-") && name.endsWith(".jar");
                        })
                        .findFirst();
                if (match.isPresent()) return match;
            } catch (IOException ignored) {
            }
        }
        return Optional.empty();
    }
}

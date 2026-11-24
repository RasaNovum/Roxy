package net.fabricmc.loader.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class RoxyFabricLoaderBridge implements FabricLoader {
    static final RoxyFabricLoaderBridge INSTANCE = new RoxyFabricLoaderBridge();

    private final Map<String, List<Object>> entrypointCache = new ConcurrentHashMap<>();

    private RoxyFabricLoaderBridge() {
    }

    @Override
    public boolean isModLoaded(String modId) {
        if (modId == null) return false;
        ModList modList = ModList.get();
        return (modList != null && modList.isLoaded(modId))
                || "voxy".equals(modId);
    }

    @Override
    public EnvType getEnvironmentType() {
        return FMLEnvironment.dist.isClient() ? EnvType.CLIENT : EnvType.SERVER;
    }

    @Override
    public Optional<ModContainer> getModContainer(String modId) {
        if (modId == null) return Optional.empty();
        ModList modList = ModList.get();
        if (modList != null) {
            Optional<ModContainer> loaded = modList.getModContainerById(modId).map(RoxyModContainer::new);
            if (loaded.isPresent()) return loaded;
        }
        if (!"voxy".equals(modId)) return Optional.empty();
        return Optional.of(new DiskModContainer(
                findVoxyFile().orElse(Path.of("voxy.jar"))
        ));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        if (key == null || type == null) return List.of();
        List<Object> instances = entrypointCache.computeIfAbsent(key, RoxyFabricLoaderBridge::instantiateEntrypoints);
        List<T> result = new ArrayList<>();
        for (Object instance : instances) {
            if (type.isInstance(instance)) result.add((T) instance);
        }
        return List.copyOf(result);
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    private static List<Object> instantiateEntrypoints(String key) {
        Path modFile = resolveVoxyModFile();
        if (modFile == null) return List.of();

        List<Object> instances = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = RoxyFabricLoaderBridge.class.getClassLoader();
        for (String className : readEntrypointNames(modFile, key)) {
            try {
                Class<?> entrypointClass = Class.forName(className, true, loader);
                instances.add(entrypointClass.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException | LinkageError exception) {
                Throwable cause = exception instanceof InvocationTargetException invocation
                        && invocation.getCause() != null ? invocation.getCause() : exception;
                if (cause instanceof Error error) throw error;
                throw new RuntimeException("Roxy: unable to create Voxy " + key + " entrypoint " + className, cause);
            }
        }
        return List.copyOf(instances);
    }

    private static Path resolveVoxyModFile() {
        ModList modList = ModList.get();
        if (modList != null) {
            Path modFile = modList.getModContainerById("voxy")
                    .map(container -> container.getModInfo().getOwningFile().getFile().getFilePath())
                    .orElse(null);
            if (modFile != null && Files.exists(modFile)) return modFile;
        }
        return findVoxyFile().orElse(null);
    }

    private static List<String> readEntrypointNames(Path modFile, String key) {
        try (InputStream input = openEntry(modFile, "fabric.mod.json")) {
            if (input == null) return List.of();
            JsonObject metadata = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
            ).getAsJsonObject();
            JsonObject entrypoints = metadata.getAsJsonObject("entrypoints");
            if (entrypoints == null) return List.of();
            JsonElement value = entrypoints.get(key);
            if (value == null || !value.isJsonArray()) return List.of();

            List<String> names = new ArrayList<>();
            for (JsonElement entry : value.getAsJsonArray()) {
                if (entry.isJsonPrimitive()) {
                    names.add(entry.getAsString());
                } else if (entry.isJsonObject()) {
                    JsonElement className = entry.getAsJsonObject().get("value");
                    if (className != null && className.isJsonPrimitive()) names.add(className.getAsString());
                }
            }
            return names;
        } catch (IOException | RuntimeException exception) {
            return List.of();
        }
    }

    private static InputStream openEntry(Path file, String name) throws IOException {
        if (Files.isDirectory(file)) {
            Path target = file.resolve(name);
            return Files.isRegularFile(target) ? Files.newInputStream(target) : null;
        }

        ZipFile zip = new ZipFile(file.toFile());
        try {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                zip.close();
                return null;
            }
            InputStream input = zip.getInputStream(entry);
            return new FilterInputStream(input) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        zip.close();
                    }
                }
            };
        } catch (IOException | RuntimeException exception) {
            try {
                zip.close();
            } catch (IOException suppressed) {
                exception.addSuppressed(suppressed);
            }
            throw exception;
        }
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
            if ("commit".equals(key) && string.isBlank()) {
                string = "roxycompat0000000000000000000000000000000";
            }
            if (value == null && !"commit".equals(key)) return null;
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
            return "commit".equals(key)
                    ? () -> "roxycompat0000000000000000000000000000000"
                    : null;
        }
    }

    private static Optional<Path> findVoxyFile() {
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = RoxyFabricLoaderBridge.class.getClassLoader();
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
                            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
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

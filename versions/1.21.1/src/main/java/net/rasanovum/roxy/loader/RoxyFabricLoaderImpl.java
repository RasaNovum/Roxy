package net.rasanovum.roxy.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforgespi.language.IModInfo;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

public final class RoxyFabricLoaderImpl implements FabricLoader {
    public static final RoxyFabricLoaderImpl INSTANCE = new RoxyFabricLoaderImpl();

    private final Map<String, List<Object>> entrypointCache = new ConcurrentHashMap<>();
    private static boolean initialized;

    private RoxyFabricLoaderImpl() {
    }

    public static void initializeFabricEntrypoints() {
        INSTANCE.initializeEntrypoints();
    }

    private synchronized void initializeEntrypoints() {
        if (initialized) return;
        initialized = true;
        invokeEntrypoints("main", ModInitializer.class);
        invokeEntrypoints("client", ClientModInitializer.class);
    }

    public static CustomValue getCustomValue(ModMetadata metadata, String key) {
        CustomValue value = metadata.getCustomValue(key);
        if (value != null || !key.equals("commit")) return value;

        ModList modList = ModList.get();
        String commit = modList == null ? "" : modList.getModContainerById("voxy")
                .map(container -> container.getModInfo().getModProperties().get("commit"))
                .map(Object::toString)
                .filter(candidate -> !candidate.isBlank())
                .orElse("roxycompat0000000000000000000000000000000");
        return new StringCustomValue(commit);
    }

    private static void invokeEntrypoints(String key, Class<?> type) {
        Path modFile = voxyModFile();
        if (modFile == null) return;

        for (String className : readEntrypointNames(modFile, key)) {
            try {
                Class<?> entrypointClass = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
                Object entrypoint = entrypointClass.getDeclaredConstructor().newInstance();
                if (type.isInstance(entrypoint)) {
                    if (entrypoint instanceof ModInitializer initializer) {
                        initializer.onInitialize();
                    } else if (entrypoint instanceof ClientModInitializer initializer) {
                        initializer.onInitializeClient();
                    }
                }
            } catch (ReflectiveOperationException | LinkageError exception) {
                System.err.println("Roxy: unable to initialize Fabric entrypoint " + className + ": " + exception);
                exception.printStackTrace(System.err);
            } catch (Throwable throwable) {
                System.err.println("Roxy: Fabric " + key + " entrypoint failed: " + throwable);
                throwable.printStackTrace(System.err);
            }
        }
    }

    @Override
    public boolean isModLoaded(String modId) {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(modId);
    }

    @Override
    public EnvType getEnvironmentType() {
        return FMLEnvironment.dist.isClient() ? EnvType.CLIENT : EnvType.SERVER;
    }

    @Override
    public Optional<ModContainer> getModContainer(String modId) {
        ModList modList = ModList.get();
        if (modList != null) {
            Optional<ModContainer> container = modList.getModContainerById(modId).map(NeoModContainer::new);
            if (container.isPresent()) return container;
        }
        if (!modId.equals("voxy")) return Optional.empty();

        Path modFile = voxyModFile();
        return modFile == null ? Optional.empty() : Optional.of(new EarlyModContainer(modFile));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        List<Object> instances = entrypointCache.computeIfAbsent(key, RoxyFabricLoaderImpl::instantiateEntrypoints);
        List<T> result = new ArrayList<>();
        for (Object instance : instances) {
            if (type.isInstance(instance)) result.add((T) instance);
        }
        return List.copyOf(result);
    }

    private static List<Object> instantiateEntrypoints(String key) {
        Path modFile = voxyModFile();
        if (modFile == null) return List.of();

        List<Object> instances = new ArrayList<>();
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        for (String className : readEntrypointNames(modFile, key)) {
            try {
                Class<?> entrypointClass = Class.forName(className, true, contextLoader);
                instances.add(entrypointClass.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException | LinkageError exception) {
                System.err.println("Roxy: unable to create Fabric entrypoint " + className + ": " + exception);
            }
        }
        return List.copyOf(instances);
    }

    private static Path voxyModFile() {
        ModList modList = ModList.get();
        if (modList != null) {
            Path modFile = modList.getModContainerById("voxy")
                    .map(container -> container.getModInfo().getOwningFile().getFile().getFilePath())
                    .orElse(null);
            if (modFile != null) return modFile;
        }

        try {
            Path located = RoxyLocator.findVoxyJar();
            if (located != null) return located;
        } catch (IOException | java.net.URISyntaxException ignored) {
        }

        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> voxyClass = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon", false, loader);
            if (voxyClass.getProtectionDomain().getCodeSource() != null) {
                Path codeSource = Path.of(voxyClass.getProtectionDomain().getCodeSource().getLocation().toURI());
                if (Files.exists(codeSource)) return codeSource;
            }
        } catch (ReflectiveOperationException | java.net.URISyntaxException | RuntimeException ignored) {
        }
        return null;
    }

    private static List<String> readEntrypointNames(Path modFile, String key) {
        try (InputStream input = openEntry(modFile, "fabric.mod.json")) {
            if (input == null) return List.of();
            JsonObject metadata = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
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
        if (Files.isDirectory(file)) return Files.newInputStream(file.resolve(name));

        ZipFile zip = new ZipFile(file.toFile());
        var entry = zip.getEntry(name);
        if (entry == null) {
            zip.close();
            return null;
        }
        InputStream input = zip.getInputStream(entry);
        return new java.io.FilterInputStream(input) {
            @Override
            public void close() throws IOException {
                super.close();
                zip.close();
            }
        };
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    private record EarlyModContainer(Path file) implements ModContainer {
        @Override
        public ModMetadata getMetadata() {
            try (InputStream input = openEntry(file, "fabric.mod.json")) {
                if (input != null) {
                    return new FabricModMetadata(JsonParser.parseReader(
                            new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject());
                }
            } catch (IOException | RuntimeException ignored) {
            }
            return new FabricModMetadata(new JsonObject());
        }

        @Override
        public List<Path> getRootPaths() {
            return List.of(file);
        }
    }

    private record FabricModMetadata(JsonObject metadata) implements ModMetadata {
        @Override
        public Version getVersion() {
            JsonElement version = metadata.get("version");
            return () -> version == null ? "0.0.0" : version.getAsString();
        }

        @Override
        public CustomValue getCustomValue(String key) {
            JsonObject custom = metadata.getAsJsonObject("custom");
            JsonElement value = custom == null ? null : custom.get(key);
            if (value == null && key.equals("commit")) {
                return new StringCustomValue("roxycompat0000000000000000000000000000000");
            }
            return value == null ? null : new StringCustomValue(value.getAsString());
        }
    }

    private record NeoModContainer(net.neoforged.fml.ModContainer delegate) implements ModContainer {
        @Override
        public ModMetadata getMetadata() {
            return new NeoModMetadata(delegate.getModInfo());
        }

        @Override
        public List<Path> getRootPaths() {
            Path file = delegate.getModInfo().getOwningFile().getFile().getFilePath();
            if (Files.isRegularFile(file)) {
                try {
                    FileSystem fileSystem = FileSystems.newFileSystem(file, Map.of());
                    return List.of(fileSystem.getRootDirectories().iterator().next());
                } catch (IOException ignored) {
                }
            }
            return List.of(file);
        }
    }

    private record NeoModMetadata(IModInfo info) implements ModMetadata {
        @Override
        public Version getVersion() {
            String version = info.getVersion().toString();
            return () -> version;
        }

        @Override
        public CustomValue getCustomValue(String key) {
            Object value = info.getModProperties().get(key);
            String string = value == null ? "" : value.toString();
            return () -> string;
        }
    }

    private record StringCustomValue(String value) implements CustomValue {
        @Override
        public String getAsString() {
            return value;
        }
    }
}

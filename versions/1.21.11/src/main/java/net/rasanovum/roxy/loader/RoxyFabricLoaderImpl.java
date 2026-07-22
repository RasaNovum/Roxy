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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

public final class RoxyFabricLoaderImpl implements FabricLoader {
    public static final RoxyFabricLoaderImpl INSTANCE = new RoxyFabricLoaderImpl();

    private final Map<String, List<Object>> entrypointCache = new ConcurrentHashMap<>();
    private volatile boolean fabricEntrypointsInitialized;

    private RoxyFabricLoaderImpl() {
    }

    public static void initializeFabricEntrypoints() {
        INSTANCE.initializeEntrypoints();
    }

    private synchronized void initializeEntrypoints() {
        if (fabricEntrypointsInitialized) return;
        invokeEntrypoints("main", ModInitializer.class);
        invokeEntrypoints("client", ClientModInitializer.class);
        fabricEntrypointsInitialized = true;
    }

    private <T> void invokeEntrypoints(String key, Class<T> type) {
        for (T entrypoint : getEntrypoints(key, type)) {
            try {
                if (entrypoint instanceof ModInitializer initializer) {
                    initializer.onInitialize();
                } else if (entrypoint instanceof ClientModInitializer initializer) {
                    initializer.onInitializeClient();
                }
            } catch (Throwable throwable) {
                System.err.println("Roxy: Fabric " + key + " entrypoint failed: " + throwable);
                throwable.printStackTrace(System.err);
            }
        }
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public EnvType getEnvironmentType() {
        return FMLEnvironment.getDist().isClient() ? EnvType.CLIENT : EnvType.SERVER;
    }

    @Override
    public Optional<ModContainer> getModContainer(String modId) {
        return ModList.get().getModContainerById(modId).map(NeoModContainer::new);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        if (!key.equals("main") && !key.equals("client")) return List.of();
        List<Object> instances = entrypointCache.computeIfAbsent(key, ignored -> instantiateEntrypoints(key));
        List<T> result = new ArrayList<>();
        for (Object instance : instances) {
            if (type.isInstance(instance)) result.add((T) instance);
        }
        return List.copyOf(result);
    }

    private static List<Object> instantiateEntrypoints(String key) {
        Path modFile = voxyModFile();
        if (modFile == null) return List.of();

        List<String> classNames = readEntrypointNames(modFile, key);
        List<Object> instances = new ArrayList<>();
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        for (String className : classNames) {
            try {
                Class<?> entrypointClass = Class.forName(className, true, contextLoader);
                instances.add(entrypointClass.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException | LinkageError exception) {
                System.err.println("Roxy: unable to create Fabric entrypoint " + className + ": " + exception);
            }
        }
        return List.copyOf(instances);
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

    private static Path voxyModFile() {
        return ModList.get().getModContainerById("voxy")
                .map(container -> container.getModInfo().getOwningFile().getFile().getFilePath())
                .orElse(null);
    }

    private static InputStream openEntry(Path file, String name) throws IOException {
        if (Files.isDirectory(file)) {
            return Files.newInputStream(file.resolve(name));
        }
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
}

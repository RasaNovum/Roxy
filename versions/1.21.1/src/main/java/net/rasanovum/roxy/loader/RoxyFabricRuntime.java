package net.rasanovum.roxy.loader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;


public final class RoxyFabricRuntime {
    private static boolean initialized;

    private RoxyFabricRuntime() {
    }

    public static synchronized void initializeFabricEntrypoints() {
        if (initialized) return;
        initialized = true;

        if (hasForgifiedFabricLoader()) {
            initializeForgifiedLoader();
            invokeVoxyEntrypoints();
        } else {
            invokeFallbackLoader();
        }
    }

    public static boolean hasForgifiedFabricLoader() {
        return hasForgifiedFabricLoaderOnDisk();
    }

    private static boolean hasForgifiedFabricLoaderOnDisk() {
        List<Path> candidates = new ArrayList<>();
        addPathList(candidates, System.getProperty("fml.modFolders"));
        addPathList(candidates, System.getProperty("java.class.path"));
        addPathList(candidates, System.getenv("MOD_CLASSES"));

        Path gameDirectory = Path.of("").toAbsolutePath().normalize();
        candidates.add(gameDirectory.resolve("mods"));
        candidates.add(gameDirectory);

        for (Path candidate : candidates) {
            if (containsForgifiedFabricLoader(candidate)) return true;
        }
        return false;
    }

    private static void addPathList(List<Path> candidates, String value) {
        if (value == null || value.isBlank()) return;
        for (String entry : value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            String path = entry;
            int marker = path.indexOf("%%");
            if (marker >= 0) path = path.substring(marker + 2);
            if (!path.isBlank()) candidates.add(Path.of(path));
        }
    }

    private static boolean containsForgifiedFabricLoader(Path candidate) {
        if (candidate == null || !Files.exists(candidate)) return false;
        try {
            if (Files.isDirectory(candidate)) {
                try (var files = Files.list(candidate)) {
                    return files.filter(Files::isRegularFile)
                            .filter(file -> file.getFileName().toString().endsWith(".jar"))
                            .anyMatch(RoxyFabricRuntime::containsForgifiedFabricLoader);
                }
            }
            if (!candidate.getFileName().toString().endsWith(".jar")) return false;
            try (ZipFile zip = new ZipFile(candidate.toFile())) {
                return zip.stream().anyMatch(entry ->
                        entry.getName().startsWith("META-INF/jars/forgified-fabric-loader-")
                                && entry.getName().endsWith("-full.jar"));
            }
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void initializeForgifiedLoader() {
        try {
            Class<?> implementation = Class.forName(
                    "net.fabricmc.loader.impl.FabricLoaderImpl",
                    true,
                    RoxyFabricRuntime.class.getClassLoader()
            );
            Object instance = implementation.getField("INSTANCE").get(null);
            implementation.getMethod("setup").invoke(instance);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Roxy: unable to initialize Forgified Fabric Loader", exception);
        }
    }

    private static void invokeFallbackLoader() {
        invokeVoxyEntrypoints();
    }

    private static void invokeVoxyEntrypoints() {
        Path voxyJar;
        try {
            voxyJar = RoxyLocator.findVoxyJar();
        } catch (IOException | java.net.URISyntaxException exception) {
            throw new RuntimeException("Roxy: unable to locate Voxy entrypoints", exception);
        }
        if (voxyJar == null) return;

        invokeEntrypointNames(voxyJar, "main", "onInitialize");
        invokeEntrypointNames(voxyJar, "client", "onInitializeClient");
    }

    private static void invokeEntrypointNames(Path modFile, String key, String methodName) {
        for (String className : readEntrypointNames(modFile, key)) {
            try {
                Class<?> entrypointClass = Class.forName(
                        className,
                        true,
                        Thread.currentThread().getContextClassLoader()
                );
                Object entrypoint = entrypointClass.getDeclaredConstructor().newInstance();
                Method method = entrypointClass.getMethod(methodName);
                method.invoke(entrypoint);
            } catch (ReflectiveOperationException exception) {
                Throwable cause = exception instanceof InvocationTargetException invocation
                        && invocation.getCause() != null
                        ? invocation.getCause()
                        : exception;
                throw new RuntimeException("Roxy: Voxy " + key + " entrypoint failed: " + className, cause);
            } catch (LinkageError error) {
                throw new RuntimeException("Roxy: Voxy " + key + " entrypoint could not link: " + className, error);
            }
        }
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
                    if (className != null && className.isJsonPrimitive()) {
                        names.add(className.getAsString());
                    }
                }
            }
            return names;
        } catch (IOException | RuntimeException exception) {
            throw new RuntimeException("Roxy: unable to read Voxy " + key + " entrypoints", exception);
        }
    }

    private static InputStream openEntry(Path file, String name) throws IOException {
        if (java.nio.file.Files.isDirectory(file)) {
            return java.nio.file.Files.newInputStream(file.resolve(name));
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
}

package net.rasanovum.roxy.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class RoxyDependencyLocator implements IDependencyLocator {
    private static final String RELOCATED_JARS = "META-INF/roxy-jars/";
    private static final String FABRIC_STUBS = "META-INF/jars/roxy-fabric-stubs.jar";
    private static final String MACOS_NATIVE_PATH_PROPERTY = "roxy.macos.nativePath";
    private static final List<String> MACOS_ARM64_NATIVE_JARS = List.of(
            "roxy-lwjgl-lmdb-natives-macos-arm64.jar",
            "roxy-lwjgl-zstd-natives-macos-arm64.jar",
            "roxy-rocksdbjni-10.2.1-natives-macos-arm64.jar");
    private static final List<String> MACOS_ARM64_NATIVE_ENTRIES = List.of(
            "macos/arm64/org/lwjgl/lmdb/liblwjgl_lmdb.dylib",
            "macos/arm64/org/lwjgl/zstd/liblwjgl_zstd.dylib",
            "librocksdbjni-osx-arm64.jnilib");
    private static final List<String> MACOS_ARM64_NATIVE_FILE_HINTS = List.of(
            "lwjgl-lmdb-3.3.3-natives-macos-arm64.jar",
            "lwjgl-zstd-3.3.3-natives-macos-arm64.jar",
            "rocksdbjni-10.2.1.jar");

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        boolean forgifiedFabricLoader = containsForgifiedFabricLoader(loadedMods);
        boolean c2meOpenCl = containsC2meOpenCl(loadedMods);
        boolean voxyLoaded = containsVoxy(loadedMods);
        if (voxyLoaded && !forgifiedFabricLoader) addFallbackFabricStubs(pipeline);

        for (IModFile mod : loadedMods) {
            Path modPath = mod.getFilePath();
            if (modPath == null || !Files.exists(modPath)) continue;
            try (JarContents contents = JarContents.of(mod.getFilePath())) {
                JsonArray jars = readJarsArray(contents);
                if (jars == null) continue;
                for (JsonElement entry : jars) {
                    if (!entry.isJsonObject()) continue;
                    JsonElement file = entry.getAsJsonObject().get("file");
                    if (file == null || !file.isJsonPrimitive()) continue;
                    String fileName = file.getAsString();
                    if (!isCurrentPlatformJar(fileName) || isProvidedLibrary(fileName, c2meOpenCl)) continue;
                    try {
                        Path extracted = extract(contents, relocatedPath(fileName));
                        if (extracted == null) continue;
                        IModFile library = IModFile.create(
                                SecureJar.from(JarContents.of(extracted)),
                                JarModsDotTomlModFileReader::manifestParser,
                                IModFile.Type.GAMELIBRARY,
                                ModFileDiscoveryAttributes.DEFAULT);
                        pipeline.addModFile(library);
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        if (voxyLoaded) addPreparedMacOsArm64Natives(loadedMods, pipeline);
    }

    private static boolean containsVoxy(List<IModFile> loadedMods) {
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path == null) continue;
            if (path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("voxy")) return true;
            try (JarContents contents = JarContents.of(path);
                 InputStream input = RoxyJarContents.openFile(contents, "fabric.mod.json")) {
                if (input == null) continue;
                JsonObject metadata = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonElement id = metadata.get("id");
                if (id != null && id.isJsonPrimitive() && id.getAsString().equals("voxy")) return true;
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return false;
    }

    private static void addPreparedMacOsArm64Natives(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        if (!isMacOsArm64()) return;

        List<Integer> missingNativeIndices = new java.util.ArrayList<>();
        for (int index = 0; index < MACOS_ARM64_NATIVE_JARS.size(); index++) {
            if (!containsBundledNativeResource(
                    loadedMods,
                    MACOS_ARM64_NATIVE_FILE_HINTS.get(index),
                    MACOS_ARM64_NATIVE_ENTRIES.get(index))) {
                missingNativeIndices.add(index);
            }
        }
        if (missingNativeIndices.isEmpty()) return;

        String configuredPath = System.getProperty(MACOS_NATIVE_PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            System.err.println("[Roxy] Voxy's macOS arm64 native payloads are incomplete. Storage may fail to initialize. Run scripts/macos/prepare-natives.sh and set -D"
                    + MACOS_NATIVE_PATH_PROPERTY + "=<repo>/.roxy-macos/natives.");
            return;
        }

        Path nativeDirectory = Path.of(configuredPath).toAbsolutePath().normalize();
        for (int index : missingNativeIndices) {
            String jarName = MACOS_ARM64_NATIVE_JARS.get(index);
            Path nativeJar = nativeDirectory.resolve(jarName);
            if (!Files.isRegularFile(nativeJar)) {
                throw new IllegalStateException("[Roxy] Required macOS arm64 native payload is missing: " + nativeJar
                        + ". Run scripts/macos/prepare-natives.sh.");
            }
            try {
                validatePreparedNativeJar(nativeJar, MACOS_ARM64_NATIVE_ENTRIES.get(index));
                IModFile library = IModFile.create(
                        SecureJar.from(JarContents.of(nativeJar)),
                        JarModsDotTomlModFileReader::manifestParser,
                        IModFile.Type.GAMELIBRARY,
                        ModFileDiscoveryAttributes.DEFAULT);
                pipeline.addModFile(library);
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("[Roxy] Could not add prepared macOS native payload " + nativeJar, exception);
            }
        }
    }

    private static boolean containsBundledNativeResource(
            List<IModFile> loadedMods,
            String fileNameHint,
            String expectedEntry) {
        for (IModFile mod : loadedMods) {
            Path modPath = mod.getFilePath();
            if (modPath == null) continue;
            try (JarContents contents = JarContents.of(modPath)) {
                JsonArray jars = readJarsArray(contents);
                if (jars == null) continue;
                for (JsonElement entry : jars) {
                    if (!entry.isJsonObject()) continue;
                    JsonElement file = entry.getAsJsonObject().get("file");
                    if (file == null || !file.isJsonPrimitive()) continue;
                    String fileName = file.getAsString();
                    if (!fileName.toLowerCase(Locale.ROOT).endsWith(fileNameHint)) continue;
                    try (InputStream nested = RoxyJarContents.openFile(contents, relocatedPath(fileName));
                         ZipInputStream zip = nested == null ? null : new ZipInputStream(nested)) {
                        if (zip == null) continue;
                        ZipEntry nestedEntry;
                        while ((nestedEntry = zip.getNextEntry()) != null) {
                            if (nestedEntry.getName().equals(expectedEntry)) return true;
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return false;
    }

    private static void validatePreparedNativeJar(Path path, String expectedEntry) throws IOException {
        try (ZipFile archive = new ZipFile(path.toFile())) {
            List<String> files = archive.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .toList();
            if (!files.equals(List.of(expectedEntry))) {
                throw new IOException("Expected only native resource " + expectedEntry + " but found " + files);
            }
        }
    }

    private static boolean isMacOsArm64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return (os.contains("mac") || os.contains("darwin"))
                && (architecture.equals("aarch64") || architecture.equals("arm64"));
    }

    private static void addFallbackFabricStubs(IDiscoveryPipeline pipeline) {
        try (InputStream input = RoxyDependencyLocator.class.getResourceAsStream("/" + FABRIC_STUBS)) {
            if (input == null) {
                System.err.println("[Roxy] Fabric Loader API is unavailable and the bundled fallback stubs could not be found.");
                return;
            }
            Path extracted = Files.createTempFile("roxy-fabric-stubs-", ".jar");
            extracted.toFile().deleteOnExit();
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            IModFile library = IModFile.create(
                    SecureJar.from(JarContents.of(extracted)),
                    JarModsDotTomlModFileReader::manifestParser,
                    IModFile.Type.GAMELIBRARY,
                    ModFileDiscoveryAttributes.DEFAULT);
            pipeline.addModFile(library);
        } catch (IOException | RuntimeException exception) {
            System.err.println("[Roxy] Could not load the bundled Fabric Loader fallback stubs: " + exception);
        }
    }

    private static boolean containsForgifiedFabricLoader(List<IModFile> loadedMods) {
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path == null) continue;
            String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.startsWith("forgified-fabric-loader-")) {
                return true;
            }
            try {
                if (Files.isDirectory(path)) {
                    try (Stream<Path> files = Files.walk(path.resolve("META-INF/jars"), 1)) {
                        if (files.anyMatch(file -> isForgifiedFabricLoaderEntry(
                                path.relativize(file).toString().replace('\\', '/')
                        ))) {
                            return true;
                        }
                    }
                    continue;
                }

                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (isForgifiedFabricLoaderEntry(entry.getName())) {
                            return true;
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    private static boolean containsC2meOpenCl(List<IModFile> loadedMods) {
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path == null) continue;
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains("c2me") && name.contains("accel-opencl")) return true;
            try (JarContents contents = JarContents.of(path)) {
                if (RoxyJarContents.containsFile(
                        contents,
                        "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator"
                )) {
                    try (InputStream input = RoxyJarContents.openFile(
                            contents,
                            "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator"
                    )) {
                        if (input != null && new String(input.readAllBytes(), StandardCharsets.UTF_8)
                                .contains("c2me.opts.accel.opencl")) return true;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return false;
    }

    private static boolean isForgifiedFabricLoaderEntry(String name) {
        return name.startsWith("META-INF/jars/forgified-fabric-loader-")
                && name.endsWith("-full.jar");
    }

    private static JsonArray readJarsArray(JarContents contents) {
        try (InputStream input = RoxyJarContents.openFile(contents, "fabric.mod.json")) {
            if (input == null) return null;
            JsonObject metadata = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement jars = metadata.get("jars");
            return jars != null && jars.isJsonArray() ? jars.getAsJsonArray() : null;
        } catch (IOException | IllegalStateException e) {
            return null;
        }
    }

    private static Path extract(JarContents contents, String innerPath) throws IOException {
        String normalized = innerPath.replace('\\', '/');
        if (!RoxyJarContents.containsFile(contents, normalized)) return null;
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        Path output = Files.createTempFile("roxy-jij-", "-" + name);
        output.toFile().deleteOnExit();
        try (InputStream input = RoxyJarContents.openFile(contents, normalized)) {
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        }
        return output;
    }

    private static String relocatedPath(String originalPath) {
        String normalized = originalPath.replace('\\', '/');
        return RELOCATED_JARS + normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static boolean isCurrentPlatformJar(String path) {
        return RoxyNativePlatform.isCurrentPlatformJar(
                path,
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }

    private static boolean isProvidedLibrary(String path, boolean c2meOpenCl) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (fileName.startsWith("lz4-java-") && fileName.endsWith(".jar")) return true;
        return c2meOpenCl && fileName.startsWith("lwjgl-zstd-") && fileName.endsWith(".jar");
    }

    @Override
    public int getPriority() {
        return LOWEST_SYSTEM_PRIORITY;
    }
}

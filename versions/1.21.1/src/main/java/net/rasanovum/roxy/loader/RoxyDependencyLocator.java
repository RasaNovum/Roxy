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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class RoxyDependencyLocator implements IDependencyLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String RELOCATED_JARS = "META-INF/roxy-jars/";
    private static final String FABRIC_STUBS = "META-INF/jars/roxy-fabric-stubs.jar";
    private static final String MACOS_NATIVE_PATH_PROPERTY = "roxy.macos.nativePath";
    private static final String CANDIDATE_LOCATOR_SERVICE =
            "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator";
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
        boolean forgifiedFabricLoader = false;
        boolean c2meOpenCl = false;
        boolean voxyLoaded = false;
        List<RelocatedModJars> modsWithJars = new ArrayList<>();

        for (IModFile mod : loadedMods) {
            if (!voxyLoaded && hasModId(mod, "voxy")) voxyLoaded = true;
            Path modPath = mod.getFilePath();
            if (modPath == null || !Files.exists(modPath)) continue;
            Path fileNamePath = modPath.getFileName();
            String fileName = fileNamePath == null ? "" : fileNamePath.toString().toLowerCase(Locale.ROOT);
            if (!forgifiedFabricLoader && fileName.startsWith("forgified-fabric-loader-")) {
                forgifiedFabricLoader = true;
            }
            if (!c2meOpenCl && fileName.contains("c2me") && fileName.contains("accel-opencl")) {
                c2meOpenCl = true;
            }
            if (!forgifiedFabricLoader && hasForgifiedFabricLoaderNestedJar(modPath)) {
                forgifiedFabricLoader = true;
            }
            try (JarContents contents = JarContents.of(modPath)) {
                if (!c2meOpenCl && RoxyJarContents.containsFile(contents, CANDIDATE_LOCATOR_SERVICE)) {
                    try (InputStream input = RoxyJarContents.openFile(contents, CANDIDATE_LOCATOR_SERVICE)) {
                        if (input != null && new String(input.readAllBytes(), StandardCharsets.UTF_8)
                                .contains("c2me.opts.accel.opencl")) {
                            c2meOpenCl = true;
                        }
                    }
                }
                JsonObject fabricMeta = readFabricMetadata(contents);
                if (fabricMeta != null) {
                    if (!voxyLoaded && isVoxyFabricMetadata(fabricMeta)) voxyLoaded = true;
                    JsonElement jarsElement = fabricMeta.get("jars");
                    if (jarsElement != null && jarsElement.isJsonArray()) {
                        modsWithJars.add(new RelocatedModJars(modPath, jarsElement.getAsJsonArray()));
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }

        if (voxyLoaded && !forgifiedFabricLoader) addFallbackFabricStubs(pipeline);

        for (RelocatedModJars entry : modsWithJars) {
            try (JarContents contents = JarContents.of(entry.path())) {
                for (JsonElement jarElement : entry.jars()) {
                    if (!jarElement.isJsonObject()) continue;
                    JsonElement file = jarElement.getAsJsonObject().get("file");
                    if (file == null || !file.isJsonPrimitive()) continue;
                    String jarName = file.getAsString();
                    if (!isCurrentPlatformJar(jarName) || isProvidedLibrary(jarName, c2meOpenCl)) continue;
                    try {
                        Path extracted = extract(contents, relocatedPath(jarName));
                        if (extracted == null) continue;
                        IModFile library = IModFile.create(
                                SecureJar.from(JarContents.of(extracted)),
                                JarModsDotTomlModFileReader::manifestParser,
                                IModFile.Type.GAMELIBRARY,
                                ModFileDiscoveryAttributes.DEFAULT);
                        pipeline.addModFile(library);
                    } catch (IOException exception) {
                        LOGGER.warn("Unable to extract nested Voxy library {} from {}", jarName, entry.path().getFileName(), exception);
                    }
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Unable to inspect nested libraries in {}", entry.path().getFileName(), exception);
            }
        }
        if (voxyLoaded) addPreparedMacOsArm64Natives(loadedMods, pipeline);
    }

    private static boolean hasModId(IModFile mod, String modId) {
        try {
            var infos = mod.getModInfos();
            if (infos == null) return false;
            for (var info : infos) {
                if (modId.equals(info.getModId())) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static boolean isVoxyFabricMetadata(JsonObject metadata) {
        JsonElement id = metadata.get("id");
        return id != null && id.isJsonPrimitive() && "voxy".equals(id.getAsString());
    }

    private static JsonObject readFabricMetadata(JarContents contents) {
        try (InputStream input = RoxyJarContents.openFile(contents, "fabric.mod.json")) {
            if (input == null) return null;
            JsonElement parsed = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean hasForgifiedFabricLoaderNestedJar(Path path) {
        try {
            if (Files.isDirectory(path)) {
                Path jarsDir = path.resolve("META-INF/jars");
                if (!Files.isDirectory(jarsDir)) return false;
                try (Stream<Path> files = Files.walk(jarsDir, 1)) {
                    return files.anyMatch(file -> isForgifiedFabricLoaderEntry(
                            path.relativize(file).toString().replace('\\', '/')
                    ));
                }
            }
            if (!Files.isRegularFile(path)) return false;
            try (ZipFile zip = new ZipFile(path.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    if (isForgifiedFabricLoaderEntry(entries.nextElement().getName())) {
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private record RelocatedModJars(Path path, JsonArray jars) {
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

    private static boolean isForgifiedFabricLoaderEntry(String name) {
        return name.startsWith("META-INF/jars/forgified-fabric-loader-")
                && name.endsWith("-full.jar");
    }

    private static JsonArray readJarsArray(JarContents contents) {
        JsonObject metadata = readFabricMetadata(contents);
        if (metadata == null) return null;
        JsonElement jars = metadata.get("jars");
        return jars != null && jars.isJsonArray() ? jars.getAsJsonArray() : null;
    }

    private static Path extract(JarContents contents, String innerPath) throws IOException {
        String normalized = innerPath.replace('\\', '/');
        if (!RoxyJarContents.containsFile(contents, normalized)) return null;
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        Path output = Files.createTempFile("roxy-jij-", "-" + name);
        output.toFile().deleteOnExit();
        boolean copied = false;
        try (InputStream input = RoxyJarContents.openFile(contents, normalized)) {
            if (input == null) return null;
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            copied = true;
            return output;
        } finally {
            if (!copied) {
                try {
                    Files.deleteIfExists(output);
                } catch (IOException ignored) {
                }
            }
        }
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

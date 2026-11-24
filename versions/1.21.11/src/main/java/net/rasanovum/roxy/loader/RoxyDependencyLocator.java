package net.rasanovum.roxy.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.jarcontents.JarContents;
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
import java.util.Locale;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public final class RoxyDependencyLocator implements IDependencyLocator {
    private static final Logger LOGGER = LoggerFactory.getLogger("Roxy");
    private static final String RELOCATED_JARS = "META-INF/roxy-jars/";
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
        boolean voxyLoaded = false;
        for (IModFile mod : loadedMods) {
            if (!voxyLoaded && hasModId(mod, "voxy")) voxyLoaded = true;
            JarContents contents = mod.getContents();
            JsonObject fabricMeta = readFabricMetadata(contents);
            if (fabricMeta == null) continue;
            if (!voxyLoaded && isVoxyFabricMetadata(fabricMeta)) voxyLoaded = true;
            JsonElement jarsElement = fabricMeta.get("jars");
            if (jarsElement == null || !jarsElement.isJsonArray()) continue;
            for (JsonElement entry : jarsElement.getAsJsonArray()) {
                if (!entry.isJsonObject()) continue;
                JsonElement file = entry.getAsJsonObject().get("file");
                if (file == null || !file.isJsonPrimitive()) continue;
                String fileName = file.getAsString();
                if (!isCurrentPlatformJar(fileName) || isMinecraftProvidedLibrary(fileName)) continue;
                String relocatedPath = relocatedJarPath(fileName);
                if (relocatedPath == null || !contents.containsFile(relocatedPath)) continue;
                try {
                    Path extracted = extract(contents, relocatedPath);
                    if (extracted == null) continue;
                    IModFile library = IModFile.create(
                            JarContents.ofPath(extracted),
                            JarModsDotTomlModFileReader::manifestParser,
                            IModFile.Type.GAMELIBRARY,
                            ModFileDiscoveryAttributes.DEFAULT);
                    pipeline.addModFile(library);
                } catch (IOException | RuntimeException exception) {
                    LOGGER.warn("Roxy: failed to extract embedded dependency {}", relocatedPath, exception);
                }
            }
        }
        if (voxyLoaded) addPreparedMacOsArm64Natives(loadedMods, pipeline);
    }

    private static boolean hasModId(IModFile mod, String targetId) {
        try {
            var infos = mod.getModInfos();
            if (infos != null) {
                for (var info : infos) {
                    if (targetId.equals(info.getModId())) return true;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static boolean isVoxyFabricMetadata(JsonObject metadata) {
        JsonElement id = metadata.get("id");
        return id != null && id.isJsonPrimitive() && id.getAsString().equals("voxy");
    }

    private static String relocatedJarPath(String fileName) {
        String normalized = fileName.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.startsWith(RELOCATED_JARS)) return normalized;
        if (normalized.startsWith("META-INF/jars/")) {
            return RELOCATED_JARS + normalized.substring("META-INF/jars/".length());
        }
        return null;
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
                        JarContents.ofPath(nativeJar),
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
            JarContents contents = mod.getContents();
            JsonObject fabricMeta = readFabricMetadata(contents);
            JsonElement jarsElement = fabricMeta == null ? null : fabricMeta.get("jars");
            if (jarsElement == null || !jarsElement.isJsonArray()) continue;
            for (JsonElement entry : jarsElement.getAsJsonArray()) {
                if (!entry.isJsonObject()) continue;
                JsonElement file = entry.getAsJsonObject().get("file");
                if (file == null || !file.isJsonPrimitive()) continue;
                String fileName = file.getAsString();
                if (!fileName.toLowerCase(Locale.ROOT).endsWith(fileNameHint)) continue;
                String relocatedPath = relocatedJarPath(fileName);
                String lookupPath = relocatedPath != null && contents.containsFile(relocatedPath)
                        ? relocatedPath : fileName;
                try (InputStream nested = contents.openFile(lookupPath);
                     ZipInputStream zip = nested == null ? null : new ZipInputStream(nested)) {
                    if (zip == null) continue;
                    ZipEntry nestedEntry;
                    while ((nestedEntry = zip.getNextEntry()) != null) {
                        if (nestedEntry.getName().equals(expectedEntry)) return true;
                    }
                } catch (IOException | RuntimeException ignored) {
                }
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

    private static JsonObject readFabricMetadata(JarContents contents) {
        try (InputStream input = contents.openFile("fabric.mod.json")) {
            if (input == null) return null;
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Path extract(JarContents contents, String innerPath) throws IOException {
        String normalized = innerPath.replace('\\', '/');
        if (!contents.containsFile(normalized)) return null;
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        Path output = Files.createTempFile("roxy-jij-", "-" + name);
        output.toFile().deleteOnExit();
        try (InputStream input = contents.openFile(normalized)) {
            if (input == null) {
                Files.deleteIfExists(output);
                return null;
            }
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(output);
            } catch (IOException ignored) {
            }
            throw exception;
        }
        return output;
    }

    private static boolean isCurrentPlatformJar(String path) {
        return RoxyNativePlatform.isCurrentPlatformJar(
                path,
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }

    private static boolean isMinecraftProvidedLibrary(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        return fileName.startsWith("lz4-java-") && fileName.endsWith(".jar");
    }

    @Override
    public int getPriority() {
        return LOWEST_SYSTEM_PRIORITY;
    }
}

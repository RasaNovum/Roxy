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
import java.util.zip.ZipInputStream;

public final class RoxyDependencyLocator implements IDependencyLocator {
    private static final String RELOCATED_JARS = "META-INF/roxy-jars/";
    private static final String FABRIC_STUBS = "META-INF/jars/roxy-fabric-stubs.jar";

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        boolean forgifiedFabricLoader = containsForgifiedFabricLoader(loadedMods);
        boolean c2meOpenCl = containsC2meOpenCl(loadedMods);

        for (IModFile mod : loadedMods) {
            Path modPath = mod.getFilePath();
            if (modPath == null || !Files.exists(modPath)) continue;
            try (JarContents contents = JarContents.of(mod.getFilePath())) {
                if (!forgifiedFabricLoader) {
                    addFallbackFabricStubs(contents, pipeline);
                }

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
    }

    private static void addFallbackFabricStubs(JarContents contents, IDiscoveryPipeline pipeline) {
        try {
            Path extracted = extract(contents, FABRIC_STUBS);
            if (extracted == null) return;
            IModFile library = IModFile.create(
                    SecureJar.from(JarContents.of(extracted)),
                    JarModsDotTomlModFileReader::manifestParser,
                    IModFile.Type.GAMELIBRARY,
                    ModFileDiscoveryAttributes.DEFAULT);
            pipeline.addModFile(library);
        } catch (IOException ignored) {
        }
    }

    private static boolean containsForgifiedFabricLoader(List<IModFile> loadedMods) {
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path == null) continue;
            String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.startsWith("forgified-fabric-loader-") || fileName.startsWith("forgified-fabric-api-")) {
                return true;
            }
            try {
                if (Files.isDirectory(path)) {
                    if (Files.exists(path.resolve("net/fabricmc/loader/api/FabricLoader.class"))) return true;
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
                        if (isForgifiedFabricLoaderEntry(entry.getName())
                                || entry.getName().equals("net/fabricmc/loader/api/FabricLoader.class")) {
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
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!normalized.contains("-natives-")) return true;

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return normalized.contains("-natives-windows");
        if (os.contains("mac") || os.contains("darwin")) {
            return normalized.contains("-natives-macos") || normalized.contains("-natives-osx");
        }
        if (os.contains("linux") || os.contains("unix")) return normalized.contains("-natives-linux");
        return true;
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

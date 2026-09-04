package net.rasanovum.roxy.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class RoxyLocator implements IModFileCandidateLocator {
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        RoxyCrashReportHeader.register();
        RoxyCrashReportHeader.searching();
        readdSelf(pipeline);

        try {
            Path voxyJar = findVoxyJar();
            if (voxyJar == null) RoxyCrashReportHeader.notFound();
            else RoxyCrashReportHeader.located(voxyJar);
            if (voxyJar != null && !context.isLocated(voxyJar)) {
                pipeline.addPath(voxyJar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ON_KNOWN_INCOMPATIBILITY);
            }
        } catch (Exception e) {
            RoxyCrashReportHeader.failed(e);
            throw new RuntimeException("Roxy: failed to locate Voxy jar", e);
        }
    }

    private static void readdSelf(IDiscoveryPipeline pipeline) {
        List<Path> ownPaths = ownModPaths();
        if (ownPaths.isEmpty()) {
            return;
        }
        try {
            Path metadataJar = createMetadataMod(ownPaths);
            pipeline.addPath(metadataJar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR);
        } catch (IOException exception) {
            throw new IllegalStateException("Roxy could not create its metadata mod", exception);
        }
    }

    private static Path createMetadataMod(List<Path> ownPaths) throws IOException {
        byte[] metadata = readOwnResource(ownPaths, "META-INF/neoforge.mods.toml");
        if (metadata == null) throw new IOException("Roxy metadata is missing");
        String toml = new String(metadata, StandardCharsets.UTF_8)
                .replace("modLoader = \"javafml\"", "modLoader = \"lowcodefml\"")
                .replaceAll("(?ms)^\\[\\[mixins]]\\s*\\Rconfig\\s*=\\s*\"[^\"]+\"\\s*", "");

        Path output = Files.createTempFile("roxy-metadata-", ".jar");
        output.toFile().deleteOnExit();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
            zip.write(toml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            byte[] icon = readOwnResource(ownPaths, "assets/roxy/icon.png");
            if (icon != null) {
                zip.putNextEntry(new ZipEntry("assets/roxy/icon.png"));
                zip.write(icon);
                zip.closeEntry();
            }
        }
        return output;
    }

    private static byte[] readOwnResource(List<Path> roots, String resource) throws IOException {
        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                Path file = root.resolve(resource);
                if (Files.isRegularFile(file)) return Files.readAllBytes(file);
            } else if (Files.isRegularFile(root)) {
                try (ZipFile zip = new ZipFile(root.toFile())) {
                    ZipEntry entry = zip.getEntry(resource);
                    if (entry != null) {
                        try (InputStream input = zip.getInputStream(entry)) {
                            return input.readAllBytes();
                        }
                    }
                }
            }
        }
        return null;
    }

    private static List<Path> ownModPaths() {
        String modFolders = System.getenv("MOD_CLASSES");
        if (modFolders == null) {
            modFolders = System.getProperty("fml.modFolders", "");
        }

        List<Path> paths = new ArrayList<>();
        for (String entry : modFolders.split(File.pathSeparator)) {
            int split = entry.indexOf("%%");
            String id = split != -1 ? entry.substring(0, split) : "";
            String path = split != -1 ? entry.substring(split + 2) : entry;
            if (id.equals("roxy") && !path.isEmpty()) {
                Path candidate = Path.of(path);
                if (Files.isDirectory(candidate)) {
                    paths.add(candidate);
                }
            }
        }

        try {
            if (RoxyLocator.class.getProtectionDomain().getCodeSource() != null) {
                Path codeSource = Path.of(
                        RoxyLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI()
                ).toAbsolutePath().normalize();
                if (Files.exists(codeSource) && !paths.contains(codeSource)) {
                    paths.add(codeSource);
                }
            }
        } catch (URISyntaxException | RuntimeException ignored) {
        }

        return paths;
    }

    static Path findVoxyJar() throws IOException, URISyntaxException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Path patchedFallback = null;
        Enumeration<URL> urls = classLoader.getResources("fabric.mod.json");
        while (urls.hasMoreElements()) {
            Path candidate = pathForFabricMetadata(urls.nextElement());
            if (candidate != null && isVoxy(candidate)) {
                if (isPatchedVoxy(candidate)) {
                    if (patchedFallback == null) patchedFallback = candidate;
                    continue;
                }
                return candidate;
            }
        }

        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(System.getProperty("path.separator"))) {
            Path candidate = Path.of(entry);
            if (isVoxy(candidate)) {
                if (isPatchedVoxy(candidate)) {
                    if (patchedFallback == null) patchedFallback = candidate;
                    continue;
                }
                return candidate;
            }
        }
        return patchedFallback;
    }

    private static boolean isPatchedVoxy(Path candidate) {
        String name = candidate.getFileName() == null
                ? ""
                : candidate.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.startsWith("roxy-patched-voxy-");
    }

    private static Path pathForFabricMetadata(URL url) throws IOException, URISyntaxException {
        if (url.openConnection() instanceof JarURLConnection jarConnection) {
            return Path.of(jarConnection.getJarFileURL().toURI());
        }
        if (url.getProtocol().equals("file")) {
            Path metadata = Path.of(url.toURI());
            return metadata.getParent();
        }
        return null;
    }

    private static boolean isVoxy(Path candidate) {
        try {
            JsonObject metadata;
            if (Files.isDirectory(candidate)) {
                try (InputStream input = Files.newInputStream(candidate.resolve("fabric.mod.json"))) {
                    metadata = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                }
            } else {
                try (ZipFile zip = new ZipFile(candidate.toFile())) {
                    var entry = zip.getEntry("fabric.mod.json");
                    if (entry == null) return false;
                    try (InputStream input = zip.getInputStream(entry)) {
                        metadata = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                    }
                }
            }
            JsonElement id = metadata.get("id");
            return id != null && id.isJsonPrimitive() && id.getAsString().equals("voxy");
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public int getPriority() {
        return LOWEST_SYSTEM_PRIORITY + 1;
    }
}

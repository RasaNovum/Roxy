package net.rasanovum.roxy.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.jarcontents.JarContents;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipFile;

public final class RoxyLocator implements IModFileCandidateLocator {
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        readdSelf(pipeline);

        try {
            Path voxyJar = findVoxyJar();
            if (voxyJar != null && !context.isLocated(voxyJar)) {
                pipeline.addPath(voxyJar, ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.WARN_ON_KNOWN_INCOMPATIBILITY);
            }
        } catch (Exception e) {
            throw new RuntimeException("Roxy: failed to locate Voxy jar", e);
        }
    }

    private static void readdSelf(IDiscoveryPipeline pipeline) {
        List<Path> ownPaths = ownModPaths();
        if (ownPaths.isEmpty()) {
            return;
        }
        try {
            pipeline.addJarContent(JarContents.ofPaths(ownPaths), ModFileDiscoveryAttributes.DEFAULT, IncompatibleFileReporting.ERROR);
        } catch (IOException ignored) {
        }
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
                paths.add(Path.of(path));
            }
        }

        return paths;
    }

    private static Path findVoxyJar() throws IOException, URISyntaxException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> urls = classLoader.getResources("fabric.mod.json");
        while (urls.hasMoreElements()) {
            Path candidate = pathForFabricMetadata(urls.nextElement());
            if (candidate != null && isVoxy(candidate)) {
                return candidate;
            }
        }

        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(System.getProperty("path.separator"))) {
            Path candidate = Path.of(entry);
            if (isVoxy(candidate)) {
                return candidate;
            }
        }
        return null;
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

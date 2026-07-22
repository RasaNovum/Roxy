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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.List;

public final class RoxyDependencyLocator implements IDependencyLocator {
    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        for (IModFile mod : loadedMods) {
            JarContents contents = mod.getContents();
            JsonArray jars = readJarsArray(contents);
            if (jars == null) continue;
            for (JsonElement entry : jars) {
                if (!entry.isJsonObject()) continue;
                JsonElement file = entry.getAsJsonObject().get("file");
                if (file == null || !file.isJsonPrimitive()) continue;
                String fileName = file.getAsString();
                if (!isCurrentPlatformJar(fileName) || isMinecraftProvidedLibrary(fileName)) continue;
                try {
                    Path extracted = extract(contents, fileName);
                    if (extracted == null) continue;
                    IModFile library = IModFile.create(
                            JarContents.ofPath(extracted),
                            JarModsDotTomlModFileReader::manifestParser,
                            IModFile.Type.GAMELIBRARY,
                            ModFileDiscoveryAttributes.DEFAULT);
                    pipeline.addModFile(library);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static JsonArray readJarsArray(JarContents contents) {
        try (InputStream input = contents.openFile("fabric.mod.json")) {
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
        if (!contents.containsFile(normalized)) return null;
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        Path output = Files.createTempFile("roxy-jij-", "-" + name);
        output.toFile().deleteOnExit();
        try (InputStream input = contents.openFile(normalized)) {
            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        }
        return output;
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

    private static boolean isMinecraftProvidedLibrary(String path) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        // Minecraft 1.21.11 already supplies the org.lz4.java module.
        return fileName.startsWith("lz4-java-") && fileName.endsWith(".jar");
    }

    @Override
    public int getPriority() {
        return LOWEST_SYSTEM_PRIORITY;
    }
}

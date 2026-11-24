package net.rasanovum.roxy.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.IModFileReader;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class RoxyFabricModReader implements IModFileReader {
    private static final String MODS_TOML = "META-INF/neoforge.mods.toml";
    private static final String ACCESS_TRANSFORMER = "META-INF/accesstransformer.cfg";
    private static final String NESTED_JARS = "META-INF/jars/";
    private static final String RELOCATED_JARS = "META-INF/roxy-jars/";

    @Override
    public @Nullable IModFile read(JarContents jar, ModFileDiscoveryAttributes attributes) {
        if (!jar.containsFile("fabric.mod.json") || jar.containsFile(MODS_TOML)) {
            return null;
        }

        JsonObject fabricMetadata = readFabricModJson(jar);
        if (fabricMetadata == null) {
            return null;
        }
        if (!"voxy".equals(string(fabricMetadata, "id", null))) {
            return null;
        }

        try {
            Path patched = patchJar(jar.getPrimaryPath(), fabricMetadata);
            return JarModsDotTomlModFileReader.createModFile(JarContents.ofPath(patched), attributes);
        } catch (IOException e) {
            throw new RuntimeException("Roxy: failed to patch Fabric mod jar", e);
        }
    }

    private static Path patchJar(Path original, JsonObject fabricMetadata) throws IOException {
        String modId = string(fabricMetadata, "id", "fabricmod");
        Path patched = Files.createTempFile("roxy-patched-" + modId + "-", ".jar");
        patched.toFile().deleteOnExit();
        RoxyMappings mappings = RoxyMappings.load();
        java.util.Set<String> writtenEntries = new java.util.HashSet<>();

        try (ZipFile input = new ZipFile(original.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(patched))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.equals(MODS_TOML)
                        || entryName.equals(ACCESS_TRANSFORMER)
                        || entryName.endsWith("-refmap.json")) {
                    continue;
                }
                if (!entry.isDirectory() && entryName.endsWith(".class")) {
                    byte[] remapped;
                    String remappedName;
                    try (InputStream stream = input.getInputStream(entry)) {
                        remapped = RoxyBytecodeRemapper.remap(stream.readAllBytes(), mappings);
                        org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(remapped);
                        remappedName = reader.getClassName() + ".class";
                    } catch (RuntimeException exception) {
                        throw new IOException("Roxy: unable to remap " + entryName, exception);
                    }
                    if (writtenEntries.add(remappedName)) {
                        output.putNextEntry(new ZipEntry(remappedName));
                        output.write(remapped);
                        output.closeEntry();
                    }
                    continue;
                }
                String targetName = entryName;
                if (entryName.startsWith(NESTED_JARS) && entryName.endsWith(".jar")) {
                    targetName = RELOCATED_JARS + entryName.substring(NESTED_JARS.length());
                }
                if (!writtenEntries.add(targetName)) continue;
                output.putNextEntry(new ZipEntry(targetName));
                if (!entry.isDirectory()) {
                    try (InputStream stream = input.getInputStream(entry)) {
                        stream.transferTo(output);
                    }
                }
                output.closeEntry();
            }

            output.putNextEntry(new ZipEntry(MODS_TOML));
            output.write(buildModsToml(fabricMetadata).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            String accessTransformer = buildAccessTransformer(input, fabricMetadata, mappings);
            if (!accessTransformer.isBlank()) {
                output.putNextEntry(new ZipEntry(ACCESS_TRANSFORMER));
                output.write(accessTransformer.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(patched);
            } catch (IOException ignored) {
            }
            throw exception;
        }
        return patched;
    }

    private static String buildModsToml(JsonObject fabricMetadata) {
        String modId = string(fabricMetadata, "id", "fabricmod");
        String version = string(fabricMetadata, "version", "0.0.0");
        String name = string(fabricMetadata, "name", modId);
        String description = string(fabricMetadata, "description", "");
        String icon = string(fabricMetadata, "icon", null);
        String authors = joinAuthors(fabricMetadata);
        String commit = readCommit(fabricMetadata);
        String sodiumRange = sodiumRange(fabricMetadata);

        StringBuilder toml = new StringBuilder();
        toml.append("modLoader = \"javafml\"\n");
        toml.append("loaderVersion = \"[4,)\"\n");
        toml.append("license = \"").append(esc(string(fabricMetadata, "license", "All-Rights-Reserved"))).append("\"\n");
        toml.append("[[mods]]\n");
        toml.append("modId = \"").append(esc(modId)).append("\"\n");
        toml.append("version = \"").append(esc(version)).append("\"\n");
        toml.append("displayName = \"").append(esc(name)).append("\"\n");
        if (!authors.isEmpty()) {
            toml.append("authors = \"").append(esc(authors)).append("\"\n");
        }
        if (!description.isEmpty()) {
            toml.append("description = \"").append(esc(description)).append("\"\n");
        }
        if (icon != null) {
            toml.append("logoFile = \"").append(esc(icon)).append("\"\n");
        }
        toml.append("[modproperties.").append(modId).append("]\n");
        toml.append("commit = \"").append(esc(commit)).append("\"\n");
        toml.append("[[dependencies.").append(modId).append("]]\n");
        toml.append("modId = \"neoforge\"\ntype = \"required\"\nversionRange = \"[4,)\"\nordering = \"NONE\"\nside = \"BOTH\"\n");
        if (sodiumRange != null) {
            toml.append("[[dependencies.").append(modId).append("]]\n");
            toml.append("modId = \"sodium\"\ntype = \"required\"\nversionRange = \"")
                    .append(sodiumRange)
                    .append("\"\nordering = \"AFTER\"\nside = \"CLIENT\"\n");
        }
        for (String mixin : readMixins(fabricMetadata)) {
            toml.append("[[mixins]]\nconfig = \"").append(esc(mixin)).append("\"\n");
        }
        return toml.toString();
    }

    private static String buildAccessTransformer(ZipFile input, JsonObject fabricMetadata, RoxyMappings mappings) throws IOException {
        StringBuilder output = new StringBuilder();
        String accessWidenerName = string(fabricMetadata, "accessWidener", null);
        if (accessWidenerName != null) {
            ZipEntry entry = input.getEntry(normalizePath(accessWidenerName));
            if (entry != null) {
                output.append("# Generated by Roxy from ").append(accessWidenerName).append("\n");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(input.getInputStream(entry), StandardCharsets.UTF_8))) {
                    String line;
                    boolean header = true;
                    String namespace = "intermediary";
                    while ((line = reader.readLine()) != null) {
                        int comment = line.indexOf('#');
                        if (comment >= 0) line = line.substring(0, comment);
                        line = line.strip();
                        if (line.isEmpty()) continue;
                        if (header) {
                            header = false;
                            String[] headerParts = line.split("\\s+");
                            if (headerParts.length >= 3) namespace = headerParts[2];
                            output.append("# ").append(line).append("\n");
                            continue;
                        }
                        appendAccessEntry(output, line.split("\\s+"), namespace, mappings);
                    }
                }
            }
        }

        appendSupplementalAccessTransformer(output, mappings);
        return output.toString();
    }

    private static void appendSupplementalAccessTransformer(StringBuilder output, RoxyMappings mappings) throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        InputStream extra = loader == null ? null : loader.getResourceAsStream("roxy-extra.accesswidener");
        if (extra == null) {
            extra = RoxyFabricModReader.class.getClassLoader().getResourceAsStream("roxy-extra.accesswidener");
        }
        if (extra == null) return;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(extra, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            String namespace = "intermediary";
            while ((line = reader.readLine()) != null) {
                int comment = line.indexOf('#');
                if (comment >= 0) line = line.substring(0, comment);
                line = line.strip();
                if (line.isEmpty()) continue;
                if (header) {
                    header = false;
                    String[] headerParts = line.split("\\s+");
                    if (headerParts.length >= 3) namespace = headerParts[2];
                    continue;
                }
                appendAccessEntry(output, line.split("\\s+"), namespace, mappings);
            }
        }
    }

    private static void appendAccessEntry(StringBuilder output, String[] parts, String namespace, RoxyMappings mappings) {
        if (parts.length < 3) return;
        String access = parts[0];
        if (!access.equals("accessible") && !access.equals("extendable") && !access.equals("mutable")) return;
        String type = parts[1];
        String visibility = (access.equals("mutable") || access.equals("extendable")) ? "public-f" : "public";
        String owner = mapAccessClass(parts[2], namespace, mappings);
        switch (type) {
            case "class" -> output.append(visibility).append(' ').append(owner).append("\n");
            case "field" -> {
                if (parts.length >= 5) {
                    String sourceDescriptor = parts[4];
                    String descriptor = mapAccessDescriptor(sourceDescriptor, namespace, mappings);
                    String name = mapAccessField(parts[2], parts[3], sourceDescriptor, namespace, mappings);
                    output.append(visibility).append(' ').append(owner).append(' ').append(name)
                            .append(" # ").append(descriptor).append("\n");
                }
            }
            case "method" -> {
                if (parts.length >= 5) {
                    String sourceDescriptor = parts[4];
                    String descriptor = mapAccessDescriptor(sourceDescriptor, namespace, mappings);
                    String name = mapAccessMethod(parts[2], parts[3], sourceDescriptor, namespace, mappings);
                    output.append(visibility).append(' ').append(owner).append(' ').append(name)
                            .append(descriptor).append("\n");
                }
            }
            default -> {
            }
        }
    }

    private static String mapAccessClass(String owner, String namespace, RoxyMappings mappings) {
        String internal = owner.replace('.', '/');
        return switch (namespace) {
            case "intermediary" -> mappings.mapClass(internal).replace('/', '.');
            default -> internal.replace('/', '.');
        };
    }

    private static String mapAccessDescriptor(String descriptor, String namespace, RoxyMappings mappings) {
        return namespace.equals("intermediary") ? mappings.mapDescriptor(descriptor) : descriptor;
    }

    private static String mapAccessField(String owner, String name, String descriptor, String namespace, RoxyMappings mappings) {
        return namespace.equals("intermediary")
                ? mappings.mapFieldName(owner.replace('.', '/'), name, descriptor)
                : name;
    }

    private static String mapAccessMethod(String owner, String name, String descriptor, String namespace, RoxyMappings mappings) {
        return namespace.equals("intermediary")
                ? mappings.mapMethodName(owner.replace('.', '/'), name, descriptor)
                : name;
    }

    private static String sodiumRange(JsonObject fabricMetadata) {
        JsonObject depends = object(fabricMetadata, "depends");
        if (depends == null) return null;
        JsonElement sodium = depends.get("sodium");
        if (sodium == null) return null;

        String minimum = null;
        if (sodium.isJsonPrimitive()) {
            minimum = lowerBound(sodium.getAsString());
        } else if (sodium.isJsonArray()) {
            for (JsonElement value : sodium.getAsJsonArray()) {
                if (!value.isJsonPrimitive()) continue;
                String candidate = lowerBound(value.getAsString());
                if (candidate != null && (minimum == null || compareVersions(candidate, minimum) < 0)) {
                    minimum = candidate;
                }
            }
        }
        if (minimum == null) return null;
        if (compareVersions(minimum, "0.8.12") < 0) minimum = "0.8.12";
        return "[" + minimum + ",)";
    }

    private static String lowerBound(String constraint) {
        String value = constraint.strip();
        while (value.startsWith(">=") || value.startsWith(">") || value.startsWith("=") || value.startsWith("~") || value.startsWith("^")) {
            value = value.substring(value.startsWith(">=") ? 2 : 1).strip();
        }
        int space = value.indexOf(' ');
        if (space >= 0) value = value.substring(0, space);
        int less = value.indexOf('<');
        if (less >= 0) value = value.substring(0, less).strip();
        return value.isBlank() ? null : value;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = left.split("[.-]");
        String[] rightParts = right.split("[.-]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            String a = i < leftParts.length ? leftParts[i] : "0";
            String b = i < rightParts.length ? rightParts[i] : "0";
            try {
                int comparison = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
                if (comparison != 0) return comparison;
            } catch (NumberFormatException ignored) {
                int comparison = a.compareToIgnoreCase(b);
                if (comparison != 0) return comparison;
            }
        }
        return 0;
    }

    private static List<String> readMixins(JsonObject fabricMetadata) {
        List<String> result = new ArrayList<>();
        JsonElement mixins = fabricMetadata.get("mixins");
        if (mixins == null || !mixins.isJsonArray()) return result;
        for (JsonElement element : mixins.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString());
            } else if (element.isJsonObject() && element.getAsJsonObject().has("config")) {
                result.add(element.getAsJsonObject().get("config").getAsString());
            }
        }
        return result;
    }

    private static String readCommit(JsonObject fabricMetadata) {
        JsonObject custom = object(fabricMetadata, "custom");
        if (custom != null) {
            String commit = string(custom, "commit", null);
            if (commit != null && !commit.startsWith("$")) return commit;
        }
        return "roxycompat0000000000000000000000000000000";
    }

    private static String joinAuthors(JsonObject fabricMetadata) {
        JsonElement authors = fabricMetadata.get("authors");
        if (authors == null || !authors.isJsonArray()) return "";
        StringJoiner result = new StringJoiner(", ");
        for (JsonElement element : authors.getAsJsonArray()) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString());
            } else if (element.isJsonObject() && element.getAsJsonObject().has("name")) {
                result.add(element.getAsJsonObject().get("name").getAsString());
            }
        }
        return result.toString();
    }

    @Nullable
    private static JsonObject readFabricModJson(JarContents jar) {
        try (InputStream input = jar.openFile("fabric.mod.json")) {
            if (input == null) return null;
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | IllegalStateException e) {
            return null;
        }
    }

    private static JsonObject object(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) return fallback;
        String value = element.getAsString();
        return value.startsWith("$") ? fallback : value;
    }

    private static String normalizePath(String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    private static String esc(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public int getPriority() {
        return HIGHEST_SYSTEM_PRIORITY;
    }
}

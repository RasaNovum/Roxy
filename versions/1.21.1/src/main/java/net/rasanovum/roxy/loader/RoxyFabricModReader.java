package net.rasanovum.roxy.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.jarhandling.JarContents;
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
import java.util.Set;
import java.util.List;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class RoxyFabricModReader implements IModFileReader {
    private static final String MODS_TOML = "META-INF/neoforge.mods.toml";
    private static final String ACCESS_TRANSFORMER = "META-INF/accesstransformer.cfg";
    private static final String RELOCATED_JARS = "META-INF/roxy-jars/";
    private static final String SUPPLEMENTAL_SHADER_MIXIN = "roxy-voxy-shader.json";
    private static final String SUPPLEMENTAL_WORLDGEN_MIXIN = "roxy-voxy-worldgen.json";
    private static final String VOXY_NEOFORGE_HOST = "net/rasanovum/roxyhost/RoxyVoxyNeoForge.class";
    private static final String VOXY_NEOFORGE_HOST_RESOURCE = "roxy/embedded/RoxyVoxyNeoForge.bin";
    private static final Set<String> UNSUPPORTED_1_21_1_CLIENT_MIXINS = Set.of(
            "minecraft.MixinBlockableEventLoop",
            "minecraft.MixinGPUSelect",
            "minecraft.MixinDebugScreenEntryList",
            "minecraft.MixinGlDebug",
            "minecraft.MixinFogRenderer",
            "sodium.MixinRenderRegionManager"
    );
    private static final String SODIUM_FOG_STORAGE = "net.caffeinemc.mods.sodium.client.util.FogStorage";

    @Override
    public @Nullable IModFile read(JarContents jar, ModFileDiscoveryAttributes attributes) {
        if (!RoxyJarContents.containsFile(jar, "fabric.mod.json")
                || RoxyJarContents.containsFile(jar, MODS_TOML)) {
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
            return JarModsDotTomlModFileReader.createModFile(JarContents.of(patched), attributes);
        } catch (IOException e) {
            throw new RuntimeException("Roxy: failed to patch Fabric mod jar", e);
        }
    }

    private static Path patchJar(Path original, JsonObject fabricMetadata) throws IOException {
        String modId = string(fabricMetadata, "id", "fabricmod");
        Path patched = Files.createTempFile("roxy-patched-" + modId + "-", ".jar");
        patched.toFile().deleteOnExit();
        RoxyMappings mappings = RoxyMappings.load();
        java.util.Map<String, byte[]> remappedClasses = remapClasses(original, mappings, true);
        byte[] supplementalShaderMixin = readSupplementalShaderMixin();
        byte[] supplementalWorldgenMixin = readResource(SUPPLEMENTAL_WORLDGEN_MIXIN);

        try (ZipFile input = new ZipFile(original.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(patched))) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals(MODS_TOML)
                        || entry.getName().equals(ACCESS_TRANSFORMER)
                        || entry.getName().endsWith(".class")
                        || entry.getName().endsWith("-refmap.json")) {
                    continue;
                }
                String outputName = entry.getName();
                if (entry.getName().startsWith("META-INF/jars/") && entry.getName().endsWith(".jar")) {
                    outputName = RELOCATED_JARS + entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                }
                output.putNextEntry(new ZipEntry(outputName));
                if (!entry.isDirectory()) {
                    try (InputStream stream = input.getInputStream(entry)) {
                        byte[] contents = stream.readAllBytes();
                        if (isVoxyMixinConfig(entry.getName())) {
                            contents = filterUnsupportedClientMixins(contents);
                        }
                        output.write(contents);
                    }
                }
                output.closeEntry();
            }

            for (var remappedClass : remappedClasses.entrySet()) {
                output.putNextEntry(new ZipEntry(remappedClass.getKey()));
                output.write(remappedClass.getValue());
                output.closeEntry();
            }

            byte[] lifecycleHost = readResource(VOXY_NEOFORGE_HOST_RESOURCE);
            if (lifecycleHost == null) {
                throw new IOException("Roxy: missing Voxy NeoForge lifecycle host");
            }
            output.putNextEntry(new ZipEntry(VOXY_NEOFORGE_HOST));
            output.write(lifecycleHost);
            output.closeEntry();

            if (supplementalShaderMixin != null) {
                output.putNextEntry(new ZipEntry(SUPPLEMENTAL_SHADER_MIXIN));
                output.write(supplementalShaderMixin);
                output.closeEntry();
            }
            if (supplementalWorldgenMixin != null) {
                output.putNextEntry(new ZipEntry(SUPPLEMENTAL_WORLDGEN_MIXIN));
                output.write(supplementalWorldgenMixin);
                output.closeEntry();
            }

            output.putNextEntry(new ZipEntry(MODS_TOML));
            output.write(buildModsToml(
                    fabricMetadata,
                    supplementalShaderMixin != null,
                    supplementalWorldgenMixin != null
            ).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            String accessTransformer = buildAccessTransformer(input, fabricMetadata, mappings);
            if (!accessTransformer.isBlank()) {
                output.putNextEntry(new ZipEntry(ACCESS_TRANSFORMER));
                output.write(accessTransformer.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return patched;
    }

    private static boolean isVoxyMixinConfig(String name) {
        return name.equals("client.voxy.mixins.json") || name.equals("common.voxy.mixins.json");
    }

    private static byte[] filterUnsupportedClientMixins(byte[] input) {
        JsonObject config = JsonParser.parseString(new String(input, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonElement client = config.get("client");
        if (client == null || !client.isJsonArray()) return input;

        JsonArray filtered = new JsonArray();
        for (JsonElement mixin : client.getAsJsonArray()) {
            if (!mixin.isJsonPrimitive()
                    || !isUnsupportedClientMixin(mixin.getAsString())) {
                filtered.add(mixin);
            }
        }
        config.add("client", filtered);
        return config.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isUnsupportedClientMixin(String mixin) {
        if (UNSUPPORTED_1_21_1_CLIENT_MIXINS.contains(mixin)) return true;
        return mixin.equals("iris.MixinLevelRenderer") && !isClassAvailable(SODIUM_FOG_STORAGE);
    }

    private static boolean isClassAvailable(String className) {
        String resource = className.replace('.', '/') + ".class";
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null && context.getResource(resource) != null) return true;
        try {
            Class.forName(className, false, context == null ? RoxyFabricModReader.class.getClassLoader() : context);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    private static byte[] readSupplementalShaderMixin() throws IOException {
        return readResource(SUPPLEMENTAL_SHADER_MIXIN);
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream input = RoxyFabricModReader.class.getClassLoader().getResourceAsStream(name)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    private static java.util.Map<String, byte[]> remapClasses(
            Path original,
            RoxyMappings mappings,
            boolean patchFabricMetadata
    ) throws IOException {
        java.util.Map<String, byte[]> result = new java.util.LinkedHashMap<>();
        try (ZipFile input = new ZipFile(original.toFile())) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                try (InputStream stream = input.getInputStream(entry)) {
                    byte[] remapped = RoxyBytecodeRemapper.remap(
                            stream.readAllBytes(),
                            mappings,
                            patchFabricMetadata
                    );
                    org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(remapped);
                    result.put(reader.getClassName() + ".class", remapped);
                } catch (RuntimeException exception) {
                    throw new IOException("Roxy: unable to remap " + entry.getName(), exception);
                }
            }
        }
        return result;
    }

    private static String buildModsToml(
            JsonObject fabricMetadata,
            boolean includeSupplementalShaderMixin,
            boolean includeSupplementalWorldgenMixin
    ) {
        String modId = string(fabricMetadata, "id", "fabricmod");
        String version = string(fabricMetadata, "version", "0.0.0");
        String name = string(fabricMetadata, "name", modId);
        String description = string(fabricMetadata, "description", "");
        String icon = string(fabricMetadata, "icon", null);
        String authors = joinAuthors(fabricMetadata);
        String commit = readCommit(fabricMetadata);
        String sodiumRange = sodiumRange(fabricMetadata);
        String sodiumConfigEntrypoint = firstEntrypoint(fabricMetadata, "sodium:config_api_user");

        StringBuilder toml = new StringBuilder();
        toml.append("modLoader = \"javafml\"\n");
        toml.append("loaderVersion = \"[4,)\"\n");
        toml.append("license = \"").append(esc(string(fabricMetadata, "license", "All-Rights-Reserved"))).append("\"\n");
        if (!authors.isEmpty()) {
            toml.append("authors = \"").append(esc(authors)).append("\"\n");
        }
        toml.append("[[mods]]\n");
        toml.append("modId = \"").append(esc(modId)).append("\"\n");
        toml.append("version = \"").append(esc(version)).append("\"\n");
        toml.append("displayName = \"").append(esc(name)).append("\"\n");
        if (!description.isEmpty()) {
            toml.append("description = \"").append(esc(description)).append("\"\n");
        }
        if (icon != null) {
            toml.append("logoFile = \"").append(esc(icon)).append("\"\n");
        }
        toml.append("[modproperties.").append(modId).append("]\n");
        toml.append("commit = \"").append(esc(commit)).append("\"\n");
        if (sodiumConfigEntrypoint != null) {
            toml.append("\"sodium:config_api_user\" = \"")
                    .append(esc(sodiumConfigEntrypoint))
                    .append("\"\n");
        }
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
        if (includeSupplementalShaderMixin) {
            toml.append("[[mixins]]\nconfig = \"").append(SUPPLEMENTAL_SHADER_MIXIN).append("\"\n");
        }
        if (includeSupplementalWorldgenMixin) {
            toml.append("[[mixins]]\nconfig = \"").append(SUPPLEMENTAL_WORLDGEN_MIXIN).append("\"\n");
        }
        return toml.toString();
    }

    private static String buildAccessTransformer(ZipFile input, JsonObject fabricMetadata, RoxyMappings mappings) throws IOException {
        String accessWidenerName = string(fabricMetadata, "accessWidener", null);
        if (accessWidenerName == null) {
            return "";
        }
        ZipEntry entry = input.getEntry(normalizePath(accessWidenerName));
        if (entry == null) {
            return "";
        }

        StringBuilder output = new StringBuilder();
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

                String[] parts = line.split("\\s+");
                if (parts.length < 3) continue;
                String access = parts[0];
                if (!access.equals("accessible") && !access.equals("extendable") && !access.equals("mutable")) continue;
                String type = parts[1];
                String visibility = access.equals("mutable") ? "public-f" : "public";
                String owner = mapAccessClass(parts[2], namespace, mappings);
                switch (type) {
                    case "class" -> output.append("public ").append(owner).append("\n");
                    case "field" -> {
                        if (parts.length >= 5) {
                            String sourceDescriptor = parts[4];
                            String descriptor = mapAccessDescriptor(sourceDescriptor, namespace, mappings);
                            String name = mapAccessField(parts[2], parts[3], sourceDescriptor, namespace, mappings);
                            output.append(visibility).append(' ').append(owner).append(' ').append(name).append(" # ").append(descriptor).append("\n");
                        }
                    }
                    case "method" -> {
                        if (parts.length >= 5) {
                            String sourceDescriptor = parts[4];
                            String descriptor = mapAccessDescriptor(sourceDescriptor, namespace, mappings);
                            String name = mapAccessMethod(parts[2], parts[3], sourceDescriptor, namespace, mappings);
                            output.append(visibility).append(' ').append(owner).append(' ').append(name).append(descriptor).append("\n");
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        appendSupplementalAccessTransformer(output, mappings);
        return output.toString();
    }

    private static void appendSupplementalAccessTransformer(StringBuilder output, RoxyMappings mappings) throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        InputStream extra = loader.getResourceAsStream("roxy-extra.accesswidener");
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
        String visibility = access.equals("mutable") ? "public-f" : "public";
        String owner = mapAccessClass(parts[2], namespace, mappings);
        switch (type) {
            case "class" -> output.append("public ").append(owner).append("\n");
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
        return minimum == null ? null : "[" + minimum + ",)";
    }

    private static String lowerBound(String constraint) {
        String value = constraint.strip();
        while (value.startsWith(">=") || value.startsWith("=") || value.startsWith("~") || value.startsWith("^")) {
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

    private static String firstEntrypoint(JsonObject fabricMetadata, String key) {
        JsonObject entrypoints = object(fabricMetadata, "entrypoints");
        if (entrypoints == null) return null;
        JsonElement entries = entrypoints.get(key);
        if (entries == null || !entries.isJsonArray()) return null;
        for (JsonElement entry : entries.getAsJsonArray()) {
            if (entry.isJsonPrimitive()) return entry.getAsString();
            if (entry.isJsonObject()) {
                String value = string(entry.getAsJsonObject(), "value", null);
                if (value != null) return value;
            }
        }
        return null;
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
        try (InputStream input = RoxyJarContents.openFile(jar, "fabric.mod.json")) {
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
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public int getPriority() {
        return HIGHEST_SYSTEM_PRIORITY;
    }
}

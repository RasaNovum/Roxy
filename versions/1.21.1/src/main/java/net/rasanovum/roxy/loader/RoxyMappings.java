package net.rasanovum.roxy.loader;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;


public final class RoxyMappings {
    private static final String MINECRAFT_VERSION = "1.21.1";
    private static final String INTERMEDIARY_RESOURCE = "roxy/mappings/intermediary-1.21.1.tiny";

    private final Map<String, String> classes;
    private final Map<MemberKey, String> fields;
    private final Map<MemberKey, String> methods;
    private final Map<OwnerName, Set<String>> fieldNames;
    private final Map<OwnerName, Set<String>> methodNames;
    private final Map<String, Set<String>> globalFieldNames;
    private final Map<String, Set<String>> globalMethodNames;

    private RoxyMappings(
            Map<String, String> classes,
            Map<MemberKey, String> fields,
            Map<MemberKey, String> methods
    ) {
        this.classes = Map.copyOf(classes);
        this.fields = Map.copyOf(fields);
        this.methods = Map.copyOf(methods);
        this.fieldNames = indexNames(fields);
        this.methodNames = indexNames(methods);
        this.globalFieldNames = indexGlobalNames(fields);
        this.globalMethodNames = indexGlobalNames(methods);
    }

    public static RoxyMappings load() throws IOException {
        Path neoFormMappings = findNeoFormMapping();
        if (neoFormMappings == null && isProduction()) {
            throw new IOException(
                    "Roxy: could not locate the NeoForge 1.21.1 runtime mappings. "
                            + "Set -Droxy.neoformMappings to the matching NeoForm mappings.txt file."
            );
        }
        Path intermediaryPath = findIntermediaryMapping();
        if (intermediaryPath != null) {
            try (InputStream input = Files.newInputStream(intermediaryPath)) {
                return parseTiny(input, neoFormMappings);
            }
        }

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        InputStream resource = loader.getResourceAsStream(INTERMEDIARY_RESOURCE);
        if (resource == null) {
            resource = RoxyMappings.class.getClassLoader().getResourceAsStream(INTERMEDIARY_RESOURCE);
        }
        if (resource == null) {
            throw new IOException("Roxy: missing Fabric intermediary mapping " + INTERMEDIARY_RESOURCE);
        }
        try (InputStream mappingResource = resource) {
            return parseTiny(mappingResource, neoFormMappings);
        }
    }

    private static boolean isProduction() {
        String override = System.getProperty("roxy.runtimeNamespace", "");
        if (override.equalsIgnoreCase("official")) return true;
        if (override.equalsIgnoreCase("mojmap")) return false;
        try {
            return FMLLoader.isProduction();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static RoxyMappings parseTiny(InputStream input, Path neoFormMappings) throws IOException {
        MojmapMappings mojmap = neoFormMappings == null ? null : MojmapMappings.read(neoFormMappings);
        Map<String, String> classes = new HashMap<>();
        Map<String, String> officialToIntermediary = new HashMap<>();
        Map<MemberKey, String> fields = new HashMap<>();
        Map<MemberKey, String> methods = new HashMap<>();
        List<TinyMember> members = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            String officialOwner = null;
            String intermediaryOwner = null;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\t", -1);
                if (parts.length == 0) continue;
                if ((parts[0].equals("c") || parts[0].equals("CLASS")) && parts.length >= 3) {
                    officialOwner = parts[1];
                    intermediaryOwner = parts[2];
                    String targetOwner = mojmap == null
                            ? officialOwner
                            : mojmap.officialToMojmap.getOrDefault(officialOwner, officialOwner);
                    classes.put(intermediaryOwner, targetOwner);
                    officialToIntermediary.put(officialOwner, intermediaryOwner);
                } else if (parts.length >= 5 && parts[0].isEmpty() && (parts[1].equals("f") || parts[1].equals("m"))) {
                    if (officialOwner != null && intermediaryOwner != null) {
                        members.add(new TinyMember(
                                parts[1].equals("f"),
                                officialOwner,
                                intermediaryOwner,
                                parts[2],
                                parts[3],
                            parts[4]
                        ));
                    }
                } else if (parts.length >= 5 && (parts[0].equals("FIELD") || parts[0].equals("METHOD"))) {
                    String owner = parts[1];
                    String officialOwnerForMember = owner;
                    String intermediaryOwnerForMember = officialToIntermediary.get(owner);
                    if (intermediaryOwnerForMember == null && mojmap != null) {
                        // Some entries in Fabric's merged v1 table use the
                        // named/Mojmap owner instead of the official owner.
                        // Resolve that owner before indexing the member so
                        // inherited members (for example MinecraftServer's
                        // world-path method) remain discoverable.
                        officialOwnerForMember = mojmap.mojToOfficial.getOrDefault(
                                owner.replace('/', '.'), owner
                        );
                        intermediaryOwnerForMember = officialToIntermediary.get(officialOwnerForMember);
                    }
                    if (intermediaryOwnerForMember != null || mojmap != null) {
                        members.add(new TinyMember(
                                parts[0].equals("FIELD"),
                                officialOwnerForMember,
                                intermediaryOwnerForMember == null ? "" : intermediaryOwnerForMember,
                                parts[2],
                                parts[3],
                                parts[4]
                        ));
                    }
                }
            }
        }

        for (TinyMember member : members) {
            String intermediaryDescriptor = mapDescriptorWith(member.officialDescriptor, officialToIntermediary);
            String targetName = member.officialName;
            if (mojmap != null) {
                targetName = mojmap.members.get(new MemberKey(member.officialOwner, member.officialName, member.officialDescriptor));
                if (targetName == null) {
                    // A few inherited or synthetic members do not receive a
                    // Mojmap entry. Keeping the official name is still better
                    // than retaining an intermediary symbol in development.
                    targetName = member.officialName;
                }
            }

            MemberKey key = new MemberKey(member.intermediaryOwner, member.intermediaryName, intermediaryDescriptor);
            if (member.field) {
                fields.put(key, targetName);
            } else {
                methods.put(key, targetName);
            }
        }
        return new RoxyMappings(classes, fields, methods);
    }

    private static Map<OwnerName, Set<String>> indexNames(Map<MemberKey, String> members) {
        Map<OwnerName, Set<String>> result = new HashMap<>();
        for (MemberKey key : members.keySet()) {
            result.computeIfAbsent(new OwnerName(key.owner(), key.name()), ignored -> new HashSet<>()).add(members.get(key));
        }
        return result;
    }

    private static Map<String, Set<String>> indexGlobalNames(Map<MemberKey, String> members) {
        Map<String, Set<String>> result = new HashMap<>();
        for (MemberKey key : members.keySet()) {
            result.computeIfAbsent(key.name(), ignored -> new HashSet<>()).add(members.get(key));
        }
        return result;
    }

    private static Path findIntermediaryMapping() {
        String explicit = System.getProperty("roxy.intermediaryMapping");
        if (explicit != null && !explicit.isBlank()) {
            Path path = Paths.get(explicit);
            if (Files.isRegularFile(path)) return path;
        }

        Path home = Paths.get(System.getProperty("user.home", ""));
        Path cache = home.resolve(".gradle/caches/modules-2/files-2.1/net.fabricmc/intermediary/" + MINECRAFT_VERSION);
        if (Files.isDirectory(cache)) {
            try (Stream<Path> files = Files.walk(cache, 4)) {
                Path found = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .findFirst()
                        .orElse(null);
                if (found != null) {
                    Path extracted = extractTinyToTemp(found);
                    if (extracted != null) return extracted;
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private static Path extractTinyToTemp(Path jar) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("mappings/mappings.tiny");
            if (entry == null) return null;
            Path extracted = Files.createTempFile("roxy-intermediary-", ".tiny");
            extracted.toFile().deleteOnExit();
            try (InputStream input = zip.getInputStream(entry)) {
                Files.copy(input, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return extracted;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Path findNeoFormMapping() {
        String explicit = System.getProperty("roxy.neoformMappings");
        if (explicit != null && !explicit.isBlank()) {
            Path path = Paths.get(explicit);
            if (Files.isRegularFile(path)) return path;
        }

        Path path = Paths.get(System.getProperty("user.home", ""), ".gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client_mappings.txt");
        if (Files.isRegularFile(path)) return path;

        // Modpack launchers normally keep NeoForge's libraries outside the
        // instance directory. Modrinth, for example, places them in
        // <instance-root>/../meta/libraries, while other launchers use a
        // conventional <instance-root>/../libraries directory. Resolve the
        // game directory through NeoForge when available and check a small,
        // bounded set of ancestors instead of scanning the whole filesystem.
        Set<Path> roots = new HashSet<>();
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            if (gameDir != null) roots.add(gameDir);
        } catch (Throwable ignored) {
            // FMLPaths can be unavailable while a development classpath is
            // being assembled; the other roots remain valid in that case.
        }
        roots.add(Paths.get(System.getProperty("user.dir", "")));

        for (Path root : roots) {
            Path current = root.toAbsolutePath().normalize();
            for (int depth = 0; depth < 6 && current != null; depth++, current = current.getParent()) {
                Path found = findNeoFormMappingUnder(current.resolve("meta/libraries/net/neoforged/neoform"));
                if (found == null) {
                    found = findNeoFormMappingUnder(current.resolve("libraries/net/neoforged/neoform"));
                }
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Path findNeoFormMappingUnder(Path root) {
        if (!Files.isDirectory(root)) return null;
        String neoFormVersion = System.getProperty("fml.neoFormVersion", "").strip();
        if (!neoFormVersion.isEmpty()) {
            Path versionDirectory = root.resolve(neoFormVersion);
            try (Stream<Path> files = Files.walk(versionDirectory, 2)) {
                Path found = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("-mappings.txt"))
                        .findFirst()
                        .orElse(null);
                if (found != null) return found;
            } catch (IOException ignored) {
            }
        }
        try (Stream<Path> files = Files.walk(root, 3)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith("-mappings.txt"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    public String mapClass(String intermediaryName) {
        return classes.getOrDefault(intermediaryName, intermediaryName);
    }

    public String mapClassString(String name) {
        boolean dotted = name.indexOf('/') < 0;
        String internal = dotted ? name.replace('.', '/') : name;
        String mapped = mapClass(internal);
        return dotted ? mapped.replace('/', '.') : mapped;
    }

    public String mapDescriptor(String descriptor) {
        StringBuilder result = new StringBuilder(descriptor.length());
        for (int i = 0; i < descriptor.length(); i++) {
            char current = descriptor.charAt(i);
            result.append(current);
            if (current != 'L') continue;
            int end = descriptor.indexOf(';', i + 1);
            if (end < 0) break;
            result.append(mapClass(descriptor.substring(i + 1, end)));
            i = end;
            result.append(';');
        }
        return result.toString();
    }

    public String mapFieldName(String owner, String name, String descriptor) {
        String mapped = fields.get(new MemberKey(owner, name, descriptor));
        return mapped == null ? uniqueGlobalName(globalFieldNames, name) : mapped;
    }

    public String mapMethodName(String owner, String name, String descriptor) {
        String mapped = methods.get(new MemberKey(owner, name, descriptor));
        return mapped == null ? uniqueGlobalName(globalMethodNames, name) : mapped;
    }

    public String mapFieldNameWithoutDescriptor(String owner, String name) {
        String mapped = uniqueName(fieldNames, owner, name);
        return mapped.equals(name) ? uniqueGlobalName(globalFieldNames, name) : mapped;
    }

    public String mapMethodNameWithoutDescriptor(String owner, String name) {
        String mapped = uniqueName(methodNames, owner, name);
        return mapped.equals(name) ? uniqueGlobalName(globalMethodNames, name) : mapped;
    }

    private static String uniqueName(Map<OwnerName, Set<String>> names, String owner, String name) {
        Set<String> matches = names.get(new OwnerName(owner, name));
        if (matches == null || matches.size() != 1) return name;
        return matches.iterator().next();
    }

    private static String uniqueGlobalName(Map<String, Set<String>> names, String name) {
        Set<String> matches = names.get(name);
        if (matches == null || matches.size() != 1) return name;
        return matches.iterator().next();
    }

    public String mapMemberReference(String reference, String defaultOwner) {
        if (reference == null || reference.isBlank()) return reference;

        String owner = defaultOwner;
        String member = reference;
        String prefix = "";
        int separator = reference.indexOf(';');
        if (reference.startsWith("L") && separator >= 0) {
            prefix = "L";
            owner = reference.substring(1, separator);
            member = reference.substring(separator + 1);
        } else if (separator >= 0) {
            owner = reference.substring(0, separator);
            member = reference.substring(separator + 1);
            prefix = "owner;";
        } else {
            int dot = reference.indexOf('.');
            int paren = reference.indexOf('(');
            if (dot > 0 && (paren < 0 || dot < paren)) {
                owner = reference.substring(0, dot);
                member = reference.substring(dot + 1);
                prefix = "owner.";
            }
        }

        String descriptor = null;
        int open = member.indexOf('(');
        int colon = member.indexOf(':');
        int suffix = open >= 0 ? open : colon;
        String name = suffix >= 0 ? member.substring(0, suffix) : member;
        if (open >= 0) {
            descriptor = member.substring(open);
        } else if (colon >= 0) {
            descriptor = member.substring(colon + 1);
        }

        String mappedName;
        if (descriptor != null && open >= 0) {
            mappedName = mapMethodName(owner, name, descriptor);
            descriptor = mapDescriptor(descriptor);
        } else if (descriptor != null) {
            mappedName = mapFieldName(owner, name, descriptor);
            descriptor = mapDescriptor(descriptor);
        } else {
            mappedName = mapMethodNameWithoutDescriptor(owner, name);
            if (mappedName.equals(name)) mappedName = mapFieldNameWithoutDescriptor(owner, name);
        }

        String mappedOwner = mapClass(owner);
        String mappedMember = mappedName + (descriptor == null ? "" : (open >= 0 ? descriptor : ":" + descriptor));
        return switch (prefix) {
            case "L" -> "L" + mappedOwner + ";" + mappedMember;
            case "owner;" -> mappedOwner + ";" + mappedMember;
            case "owner." -> mappedOwner + "." + mappedMember;
            default -> mappedMember;
        };
    }

    private static String mapOfficialDescriptorToIntermediary(String descriptor, Map<String, String> classes) {
        return mapDescriptorWith(descriptor, classes);
    }

    private static String mapDescriptorWith(String descriptor, Map<String, String> classes) {
        StringBuilder result = new StringBuilder(descriptor.length());
        for (int i = 0; i < descriptor.length(); i++) {
            char current = descriptor.charAt(i);
            result.append(current);
            if (current != 'L') continue;
            int end = descriptor.indexOf(';', i + 1);
            if (end < 0) break;
            result.append(classes.getOrDefault(descriptor.substring(i + 1, end), descriptor.substring(i + 1, end)));
            i = end;
            result.append(';');
        }
        return result.toString();
    }

    private record MemberKey(String owner, String name, String descriptor) {
    }

    private record OwnerName(String owner, String name) {
    }

    private record TinyMember(
            boolean field,
            String officialOwner,
            String intermediaryOwner,
            String officialDescriptor,
            String officialName,
            String intermediaryName
    ) {
    }

    private static final class MojmapMappings {
        private final Map<String, String> officialToMojmap = new HashMap<>();
        private final Map<MemberKey, String> members = new HashMap<>();
        private final Map<String, String> mojToOfficial = new HashMap<>();

        private static MojmapMappings read(Path path) throws IOException {
            MojmapMappings result = new MojmapMappings();
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

            // NeoForm does not guarantee that a referenced class appears before
            // the class that uses it.  Collect all class names first so member
            // descriptors can be converted reliably on the second pass.
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith(" ") || !line.endsWith(":")) {
                    continue;
                }
                int arrow = line.indexOf(" -> ");
                if (arrow <= 0) continue;
                String moj = line.substring(0, arrow).strip();
                String official = line.substring(arrow + 4, line.length() - 1).strip();
                result.mojToOfficial.put(moj, official);
                result.officialToMojmap.put(official, moj.replace('.', '/'));
            }

            ClassEntry current = null;
            for (String line : lines) {
                if (line.isBlank() || line.startsWith("#")) continue;
                if (!line.startsWith(" ") && line.endsWith(":")) {
                    int arrow = line.indexOf(" -> ");
                    if (arrow > 0) {
                        String moj = line.substring(0, arrow).strip();
                        String official = line.substring(arrow + 4, line.length() - 1).strip();
                        current = new ClassEntry(moj, official);
                        continue;
                    }
                }
                if (current == null || !line.startsWith(" ")) continue;
                int arrow = line.lastIndexOf(" -> ");
                if (arrow < 0) continue;
                String source = line.substring(0, arrow).strip();
                String officialName = line.substring(arrow + 4).strip();
                if (officialName.isEmpty()) continue;
                if (source.matches("\\d+:\\d+:.*")) {
                    source = source.substring(source.indexOf(':', source.indexOf(':') + 1) + 1).strip();
                }
                MemberSource member = MemberSource.parse(source);
                if (member == null) continue;
                String descriptor = member.descriptor(result.mojToOfficial);
                if (descriptor != null) {
                    result.members.put(new MemberKey(current.official, officialName, descriptor), member.name);
                }
            }
            return result;
        }
    }

    private record ClassEntry(String mojmap, String official) {
    }

    private record MemberSource(boolean method, String type, String name, List<String> parameters) {
        private static MemberSource parse(String source) {
            int open = source.indexOf('(');
            if (open >= 0) {
                int close = source.lastIndexOf(')');
                int nameEnd = source.lastIndexOf(' ', open);
                if (close < open || nameEnd < 0) return null;
                String returnType = source.substring(0, nameEnd).strip();
                String name = source.substring(nameEnd + 1, open).strip();
                String arguments = source.substring(open + 1, close).strip();
                return new MemberSource(true, returnType, name, splitTypes(arguments));
            }
            int space = source.lastIndexOf(' ');
            if (space < 0) return null;
            return new MemberSource(false, source.substring(0, space).strip(), source.substring(space + 1).strip(), List.of());
        }

        private String descriptor(Map<String, String> mojToOfficial) {
            if (!method) {
                return typeDescriptor(type, mojToOfficial);
            }
            StringBuilder result = new StringBuilder("(");
            for (String parameter : parameters) result.append(typeDescriptor(parameter, mojToOfficial));
            result.append(')').append(typeDescriptor(type, mojToOfficial));
            return result.toString();
        }

        private static List<String> splitTypes(String input) {
            if (input.isBlank()) return List.of();
            List<String> result = new ArrayList<>();
            int genericDepth = 0;
            int start = 0;
            for (int i = 0; i < input.length(); i++) {
                char current = input.charAt(i);
                if (current == '<') genericDepth++;
                if (current == '>') genericDepth--;
                if (current == ',' && genericDepth == 0) {
                    result.add(input.substring(start, i).strip());
                    start = i + 1;
                }
            }
            result.add(input.substring(start).strip());
            return result;
        }

        private static String typeDescriptor(String raw, Map<String, String> mojToOfficial) {
            String type = raw.strip().replace("...", "[]");
            int arrayDepth = 0;
            while (type.endsWith("[]")) {
                arrayDepth++;
                type = type.substring(0, type.length() - 2).strip();
            }
            int generic = type.indexOf('<');
            if (generic >= 0) type = type.substring(0, generic).strip();
            String descriptor = switch (type) {
                case "byte" -> "B";
                case "char" -> "C";
                case "double" -> "D";
                case "float" -> "F";
                case "int" -> "I";
                case "long" -> "J";
                case "short" -> "S";
                case "boolean" -> "Z";
                case "void" -> "V";
                default -> "L" + mojToOfficial.getOrDefault(type, type).replace('.', '/') + ";";
            };
            return "[".repeat(arrayDepth) + descriptor;
        }
    }
}

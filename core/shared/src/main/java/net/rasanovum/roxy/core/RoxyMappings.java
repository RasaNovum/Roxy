package net.rasanovum.roxy.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

public final class RoxyMappings {
    private static final String AMBIGUOUS = "\u0000";

    public enum MemberKind {
        ANY,
        FIELD,
        METHOD
    }

    private final Map<String, String> classes;
    private final Map<MemberKey, String> fields;
    private final Map<MemberKey, String> methods;
    private final Map<OwnerName, String> fieldNames;
    private final Map<OwnerName, String> methodNames;
    private final Map<OwnerName, List<MethodEntry>> methodsByOwnerName;
    private final Map<OwnerName, String> methodDescriptors;
    private final Map<String, String> globalFieldNames;
    private final Map<String, String> globalMethodNames;
    private final Map<String, List<String>> hierarchy;
    private final boolean sourceIsIntermediary;
    private volatile RoxyMappings inverseView;

    private RoxyMappings(
            Map<String, String> classes,
            Map<MemberKey, String> fields,
            Map<MemberKey, String> methods,
            Map<String, List<String>> hierarchy,
            boolean sourceIsIntermediary
    ) {
        this.classes = Map.copyOf(classes);
        this.fields = Map.copyOf(fields);
        this.methods = Map.copyOf(methods);
        this.hierarchy = copyHierarchy(hierarchy);
        this.sourceIsIntermediary = sourceIsIntermediary;
        this.fieldNames = indexNames(fields);
        this.methodNames = indexNames(methods);
        this.methodsByOwnerName = indexMethodsByOwnerName(methods);
        this.methodDescriptors = sourceIsIntermediary ? indexDescriptors(methods) : Map.of();
        this.globalFieldNames = indexGlobalNames(fields, sourceIsIntermediary);
        this.globalMethodNames = indexGlobalNames(methods, sourceIsIntermediary);
    }

    public static RoxyMappings loadFabric(String minecraftVersion) throws IOException {
        return load(minecraftVersion, "fabric", null);
    }

    public static RoxyMappings loadFabric(String minecraftVersion, ClassLoader preferredLoader) throws IOException {
        return load(minecraftVersion, "fabric", preferredLoader);
    }

    public static RoxyMappings load(String minecraftVersion, String loaderName) throws IOException {
        return load(minecraftVersion, loaderName, null);
    }

    public static RoxyMappings load(String minecraftVersion, String loaderName, ClassLoader preferredLoader) throws IOException {
        String mapResource = "roxy/mappings/runtime-" + minecraftVersion + "-" + loaderName + ".map";
        String hierarchyResource = "roxy/mappings/hierarchy-" + minecraftVersion + "-" + loaderName + ".txt";
        Map<String, List<String>> namedHierarchy = Map.of();
        InputStream hierarchyStream = openResource(hierarchyResource, preferredLoader);
        if (hierarchyStream != null) {
            try (InputStream stream = hierarchyStream) {
                namedHierarchy = parseHierarchy(stream);
            }
        }
        InputStream mapStream = openResource(mapResource, preferredLoader);
        if (mapStream == null) {
            throw new IOException("Roxy: missing runtime mapping resource " + mapResource);
        }
        try (InputStream stream = mapStream) {
            RoxyMappings mappings = parseRuntimeMap(stream, namedHierarchy);
            validate(mappings, minecraftVersion);
            return mappings;
        }
    }

    public static RoxyMappings parseRuntimeMap(InputStream input, Map<String, List<String>> namedHierarchy) throws IOException {
        Map<String, String> classes = new HashMap<>(16384);
        Map<MemberKey, String> fields = new HashMap<>(65536);
        Map<MemberKey, String> methods = new HashMap<>(131072);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t", -1);
                if (parts[0].equals("C") && parts.length >= 3) {
                    classes.put(parts[1], parts[2]);
                } else if (parts[0].equals("F") && parts.length >= 5) {
                    fields.put(new MemberKey(parts[1], parts[2], parts[3]), parts[4]);
                } else if (parts[0].equals("M") && parts.length >= 5) {
                    methods.put(new MemberKey(parts[1], parts[2], parts[3]), parts[4]);
                }
            }
        }
        Map<String, String> namedToIntermediary = new HashMap<>(classes.size());
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            namedToIntermediary.putIfAbsent(entry.getValue(), entry.getKey());
        }
        Map<String, List<String>> intermediaryHierarchy = translateHierarchy(namedHierarchy, namedToIntermediary);
        RoxyMappings forward = new RoxyMappings(classes, fields, methods, intermediaryHierarchy, true);
        forward.inverseView = buildInverse(forward, namedHierarchy);
        forward.inverseView.inverseView = forward;
        return forward;
    }

    public static Map<String, List<String>> parseHierarchy(InputStream input) throws IOException {
        Map<String, List<String>> result = new HashMap<>(16384);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;
                List<String> parents = new ArrayList<>(parts.length - 1);
                for (int i = 1; i < parts.length; i++) {
                    if (!parts[i].isEmpty()) parents.add(parts[i]);
                }
                if (!parents.isEmpty()) result.put(parts[0], List.copyOf(parents));
            }
        }
        return result;
    }

    private static RoxyMappings buildInverse(RoxyMappings forward, Map<String, List<String>> namedHierarchy) {
        Map<String, String> reverseClasses = new HashMap<>(forward.classes.size());
        for (Map.Entry<String, String> entry : forward.classes.entrySet()) {
            reverseClasses.putIfAbsent(entry.getValue(), entry.getKey());
        }
        Map<MemberKey, String> reverseFields = new HashMap<>(forward.fields.size());
        for (Map.Entry<MemberKey, String> entry : forward.fields.entrySet()) {
            MemberKey key = entry.getKey();
            String namedOwner = forward.mapClass(key.owner());
            String namedDesc = forward.mapDescriptor(key.descriptor());
            reverseFields.putIfAbsent(new MemberKey(namedOwner, entry.getValue(), namedDesc), key.name());
        }
        Map<MemberKey, String> reverseMethods = new HashMap<>(forward.methods.size());
        for (Map.Entry<MemberKey, String> entry : forward.methods.entrySet()) {
            MemberKey key = entry.getKey();
            String namedOwner = forward.mapClass(key.owner());
            String namedDesc = forward.mapDescriptor(key.descriptor());
            reverseMethods.putIfAbsent(new MemberKey(namedOwner, entry.getValue(), namedDesc), key.name());
        }
        return new RoxyMappings(reverseClasses, reverseFields, reverseMethods, namedHierarchy, false);
    }

    private static Map<String, List<String>> translateHierarchy(
            Map<String, List<String>> sourceHierarchy,
            Map<String, String> classMap
    ) {
        if (sourceHierarchy == null || sourceHierarchy.isEmpty()) return Map.of();
        Map<String, List<String>> translated = new HashMap<>(sourceHierarchy.size());
        for (Map.Entry<String, List<String>> entry : sourceHierarchy.entrySet()) {
            String owner = classMap.getOrDefault(entry.getKey(), entry.getKey());
            List<String> parents = new ArrayList<>(entry.getValue().size());
            for (String parent : entry.getValue()) {
                parents.add(classMap.getOrDefault(parent, parent));
            }
            translated.put(owner, List.copyOf(parents));
        }
        return translated;
    }

    private static Map<String, List<String>> copyHierarchy(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, List<String>> copy = new HashMap<>(source.size());
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(copy);
    }

    private static void validate(RoxyMappings mappings, String minecraftVersion) throws IOException {
        String component = mappings.mapClass("net/minecraft/class_2561");
        if (!"net/minecraft/network/chat/Component".equals(component)) {
            throw new IOException("Roxy: invalid " + minecraftVersion + " runtime mappings: Component mapped to " + component);
        }
        String resourceLocation = mappings.mapClass("net/minecraft/class_2960");
        if (!"net/minecraft/resources/ResourceLocation".equals(resourceLocation)) {
            throw new IOException("Roxy: invalid " + minecraftVersion + " runtime mappings: ResourceLocation mapped to " + resourceLocation);
        }
    }

    private static InputStream openResource(String resourcePath, ClassLoader preferredLoader) {
        if (preferredLoader != null) {
            InputStream stream = preferredLoader.getResourceAsStream(resourcePath);
            if (stream != null) return stream;
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            InputStream stream = context.getResourceAsStream(resourcePath);
            if (stream != null) return stream;
        }
        return RoxyMappings.class.getClassLoader().getResourceAsStream(resourcePath);
    }

    public RoxyMappings toMojmap() {
        return sourceIsIntermediary ? this : inverseView;
    }

    public RoxyMappings toIntermediary() {
        return sourceIsIntermediary ? inverseView : this;
    }

    public int classCount() {
        return classes.size();
    }

    public int fieldCount() {
        return fields.size();
    }

    public int methodCount() {
        return methods.size();
    }

    public int hierarchyCount() {
        return hierarchy.size();
    }

    List<String> directParents(String internalName) {
        return hierarchy.get(internalName);
    }

    public String mapClass(String internalName) {
        if (internalName == null) return null;
        return classes.getOrDefault(internalName, internalName);
    }

    public String mapClassString(String name) {
        return mapClassString(name, this::mapClass);
    }

    String mapClassString(String name, UnaryOperator<String> classMapper) {
        if (name == null) return null;
        boolean dotted = name.indexOf('/') < 0;
        String internal = dotted ? name.replace('.', '/') : name;
        String mapped = classMapper.apply(internal);
        return dotted ? mapped.replace('/', '.') : mapped;
    }

    public String mapDescriptor(String descriptor) {
        return mapDescriptor(descriptor, this::mapClass);
    }

    String mapDescriptor(String descriptor, UnaryOperator<String> classMapper) {
        if (descriptor == null) return null;
        StringBuilder result = new StringBuilder(descriptor.length());
        for (int i = 0; i < descriptor.length(); i++) {
            char current = descriptor.charAt(i);
            result.append(current);
            if (current != 'L') continue;
            int end = descriptor.indexOf(';', i + 1);
            if (end < 0) {
                result.append(descriptor, i + 1, descriptor.length());
                break;
            }
            result.append(classMapper.apply(descriptor.substring(i + 1, end)));
            i = end;
            result.append(';');
        }
        return result.toString();
    }

    public String mapFieldName(String owner, String name, String descriptor) {
        String mapped = lookupMember(fields, owner, name, descriptor);
        return mapped == null ? uniqueGlobalName(globalFieldNames, name) : mapped;
    }

    public String mapMethodName(String owner, String name, String descriptor) {
        String mapped = lookupMember(methods, owner, name, descriptor);
        return mapped == null ? uniqueGlobalName(globalMethodNames, name) : mapped;
    }

    String mapInheritedFieldName(String superName, List<String> interfaces, String name, String descriptor) {
        String mapped = lookupMember(fields, superName, name, descriptor);
        if (mapped != null) return mapped;
        if (interfaces != null) {
            for (String iface : interfaces) {
                mapped = lookupMember(fields, iface, name, descriptor);
                if (mapped != null) return mapped;
            }
        }
        return null;
    }

    String mapInheritedMethodName(String superName, String[] interfaces, String name, String descriptor) {
        String mapped = lookupMember(methods, superName, name, descriptor);
        if (mapped != null) return mapped;
        if (interfaces != null) {
            for (String iface : interfaces) {
                mapped = lookupMember(methods, iface, name, descriptor);
                if (mapped != null) return mapped;
            }
        }
        return null;
    }

    String mapInheritedMethodName(String superName, List<String> interfaces, String name, String descriptor) {
        String mapped = lookupMember(methods, superName, name, descriptor);
        if (mapped != null) return mapped;
        if (interfaces != null) {
            for (String iface : interfaces) {
                mapped = lookupMember(methods, iface, name, descriptor);
                if (mapped != null) return mapped;
            }
        }
        return null;
    }

    public String mapFieldNameWithoutDescriptor(String owner, String name) {
        String mapped = lookupMemberWithoutDescriptor(fieldNames, owner, name);
        return mapped == null ? uniqueGlobalName(globalFieldNames, name) : mapped;
    }

    public String mapMethodNameWithoutDescriptor(String owner, String name) {
        String mapped = lookupMemberWithoutDescriptor(methodNames, owner, name);
        return mapped == null ? uniqueGlobalName(globalMethodNames, name) : mapped;
    }

    public String mapMethodNameByParams(String owner, String name, String parameterDescriptorPrefix) {
        if (owner == null || name == null || parameterDescriptorPrefix == null) {
            return mapMethodNameWithoutDescriptor(owner, name);
        }
        String direct = lookupMethodByParamPrefix(owner, name, parameterDescriptorPrefix);
        if (direct != null) return direct;
        List<String> directParents = hierarchy.get(owner);
        if (directParents != null && !directParents.isEmpty()) {
            Set<String> visited = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>(directParents);
            visited.add(owner);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!visited.add(current)) continue;
                String inherited = lookupMethodByParamPrefix(current, name, parameterDescriptorPrefix);
                if (inherited != null) return inherited;
                List<String> parents = hierarchy.get(current);
                if (parents != null) {
                    for (String parent : parents) {
                        if (!visited.contains(parent)) queue.add(parent);
                    }
                }
            }
        }
        return mapMethodNameWithoutDescriptor(owner, name);
    }

    public List<String> findMethodNamesByArity(String owner, String name, int parameterCount) {
        if (owner == null || name == null) return List.of();
        Set<String> results = new LinkedHashSet<>();
        collectMethodNamesByArity(owner, name, parameterCount, results);
        List<String> directParents = hierarchy.get(owner);
        if (directParents != null && !directParents.isEmpty()) {
            Set<String> visited = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>(directParents);
            visited.add(owner);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                if (!visited.add(current)) continue;
                collectMethodNamesByArity(current, name, parameterCount, results);
                List<String> parents = hierarchy.get(current);
                if (parents != null) {
                    for (String parent : parents) {
                        if (!visited.contains(parent)) queue.add(parent);
                    }
                }
            }
        }
        return results.isEmpty() ? List.of() : List.copyOf(results);
    }

    private void collectMethodNamesByArity(String owner, String name, int parameterCount, Set<String> out) {
        List<MethodEntry> entries = methodsByOwnerName.get(new OwnerName(owner, name));
        if (entries == null) return;
        for (MethodEntry entry : entries) {
            if (entry.parameterCount() == parameterCount) {
                out.add(entry.mappedName());
            }
        }
    }

    private String lookupMethodByParamPrefix(String owner, String name, String paramPrefix) {
        List<MethodEntry> entries = methodsByOwnerName.get(new OwnerName(owner, name));
        if (entries == null) return null;
        String match = null;
        for (MethodEntry entry : entries) {
            if (entry.descriptor().startsWith(paramPrefix)) {
                if (match != null && !match.equals(entry.mappedName())) return null;
                match = entry.mappedName();
            }
        }
        return match;
    }

    private String lookupMember(Map<MemberKey, String> members, String owner, String name, String descriptor) {
        if (owner == null) return null;
        String direct = members.get(new MemberKey(owner, name, descriptor));
        if (direct != null) return direct;
        List<String> directParents = hierarchy.get(owner);
        if (directParents == null || directParents.isEmpty()) return null;

        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(directParents);
        visited.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;
            String inherited = members.get(new MemberKey(current, name, descriptor));
            if (inherited != null) return inherited;
            List<String> parents = hierarchy.get(current);
            if (parents != null) {
                for (String parent : parents) {
                    if (!visited.contains(parent)) queue.add(parent);
                }
            }
        }
        return null;
    }

    private String lookupMemberWithoutDescriptor(Map<OwnerName, String> names, String owner, String name) {
        if (owner == null) return null;
        String direct = names.get(new OwnerName(owner, name));
        if (direct != null) return AMBIGUOUS.equals(direct) ? null : direct;
        List<String> directParents = hierarchy.get(owner);
        if (directParents == null || directParents.isEmpty()) return null;

        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(directParents);
        visited.add(owner);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;
            String inherited = names.get(new OwnerName(current, name));
            if (inherited != null) return AMBIGUOUS.equals(inherited) ? null : inherited;
            List<String> parents = hierarchy.get(current);
            if (parents != null) {
                for (String parent : parents) {
                    if (!visited.contains(parent)) queue.add(parent);
                }
            }
        }
        return null;
    }

    private String uniqueGlobalName(Map<String, String> names, String name) {
        if (!sourceIsIntermediary || !isIntermediaryMemberName(name)) return name;
        String match = names.get(name);
        if (match == null || AMBIGUOUS.equals(match)) return name;
        return match;
    }

    public String mapMemberReference(String reference, String defaultOwner) {
        return mapMemberReference(reference, defaultOwner, false, MemberKind.ANY, this::mapClass);
    }

    public String mapMemberReference(String reference, String defaultOwner, boolean expandIntermediaryDescriptor) {
        return mapMemberReference(reference, defaultOwner, expandIntermediaryDescriptor, MemberKind.ANY, this::mapClass);
    }

    String mapMemberReference(
            String reference,
            String defaultOwner,
            boolean expandIntermediaryDescriptor,
            MemberKind kind,
            UnaryOperator<String> classMapper
    ) {
        if (reference == null || reference.isBlank()) return reference;

        int openParen = reference.indexOf('(');
        int colonIdx = reference.indexOf(':');
        int memberBoundary = reference.length();
        if (openParen >= 0 && openParen < memberBoundary) memberBoundary = openParen;
        if (colonIdx >= 0 && colonIdx < memberBoundary) memberBoundary = colonIdx;

        String owner = defaultOwner;
        String member = reference;
        String prefix = "";
        boolean dottedOwner = false;
        int separator = reference.indexOf(';');
        if (separator >= 0 && separator < memberBoundary) {
            if (reference.startsWith("L")) {
                prefix = "L";
                owner = reference.substring(1, separator);
            } else {
                prefix = "owner;";
                owner = reference.substring(0, separator);
            }
            member = reference.substring(separator + 1);
        } else {
            int dot = memberBoundary > 0 ? reference.lastIndexOf('.', memberBoundary - 1) : -1;
            if (dot > 0) {
                String rawOwner = reference.substring(0, dot);
                dottedOwner = rawOwner.indexOf('.') >= 0;
                owner = dottedOwner ? rawOwner.replace('.', '/') : rawOwner;
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
            descriptor = mapDescriptor(descriptor, classMapper);
        } else if (descriptor != null) {
            mappedName = mapFieldName(owner, name, descriptor);
            descriptor = mapDescriptor(descriptor, classMapper);
        } else if (kind == MemberKind.FIELD) {
            mappedName = mapFieldNameWithoutDescriptor(owner, name);
        } else if (kind == MemberKind.METHOD) {
            mappedName = mapMethodNameWithoutDescriptor(owner, name);
            if (expandIntermediaryDescriptor && sourceIsIntermediary && name.startsWith("method_")) {
                String uniqueDesc = lookupMemberWithoutDescriptor(methodDescriptors, owner, name);
                if (uniqueDesc != null) {
                    descriptor = mapDescriptor(uniqueDesc, classMapper);
                    open = 0;
                }
            }
        } else {
            mappedName = mapMethodNameWithoutDescriptor(owner, name);
            if (expandIntermediaryDescriptor && sourceIsIntermediary && name.startsWith("method_")) {
                String uniqueDesc = lookupMemberWithoutDescriptor(methodDescriptors, owner, name);
                if (uniqueDesc != null) {
                    descriptor = mapDescriptor(uniqueDesc, classMapper);
                    open = 0;
                }
            }
            if (mappedName.equals(name)) mappedName = mapFieldNameWithoutDescriptor(owner, name);
        }

        String mappedOwner = owner == null ? null : classMapper.apply(owner);
        String mappedMember = mappedName + (descriptor == null ? "" : (open >= 0 ? descriptor : ":" + descriptor));
        return switch (prefix) {
            case "L" -> "L" + mappedOwner + ";" + mappedMember;
            case "owner;" -> mappedOwner + ";" + mappedMember;
            case "owner." -> (dottedOwner ? mappedOwner.replace('/', '.') : mappedOwner) + "." + mappedMember;
            default -> mappedMember;
        };
    }

    private static Map<OwnerName, List<MethodEntry>> indexMethodsByOwnerName(Map<MemberKey, String> members) {
        Map<OwnerName, List<MethodEntry>> result = new HashMap<>(members.size());
        for (Map.Entry<MemberKey, String> entry : members.entrySet()) {
            MemberKey key = entry.getKey();
            OwnerName ownerName = new OwnerName(key.owner(), key.name());
            result.computeIfAbsent(ownerName, ignored -> new ArrayList<>(1))
                    .add(new MethodEntry(key.descriptor(), entry.getValue(), countParameters(key.descriptor())));
        }
        return result;
    }

    private static int countParameters(String descriptor) {
        if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') return -1;
        int count = 0;
        for (int i = 1; i < descriptor.length(); i++) {
            char ch = descriptor.charAt(i);
            if (ch == ')') return count;
            if (ch == '[') continue;
            if (ch == 'L') {
                int end = descriptor.indexOf(';', i + 1);
                if (end < 0) return -1;
                i = end;
            }
            count++;
        }
        return -1;
    }

    private static Map<OwnerName, String> indexDescriptors(Map<MemberKey, String> members) {
        Map<OwnerName, String> result = new HashMap<>(members.size());
        for (MemberKey key : members.keySet()) {
            OwnerName ownerName = new OwnerName(key.owner(), key.name());
            String desc = key.descriptor();
            String existing = result.putIfAbsent(ownerName, desc);
            if (existing != null && !existing.equals(desc)) {
                result.put(ownerName, AMBIGUOUS);
            }
        }
        return result;
    }

    private static Map<OwnerName, String> indexNames(Map<MemberKey, String> members) {
        Map<OwnerName, String> result = new HashMap<>(members.size());
        for (Map.Entry<MemberKey, String> entry : members.entrySet()) {
            MemberKey key = entry.getKey();
            OwnerName ownerName = new OwnerName(key.owner(), key.name());
            String mapped = entry.getValue();
            String existing = result.putIfAbsent(ownerName, mapped);
            if (existing != null && !existing.equals(mapped)) {
                result.put(ownerName, AMBIGUOUS);
            }
        }
        return result;
    }

    private static Map<String, String> indexGlobalNames(Map<MemberKey, String> members, boolean sourceIsIntermediary) {
        if (!sourceIsIntermediary) return Map.of();
        Map<String, String> result = new HashMap<>(members.size());
        for (Map.Entry<MemberKey, String> entry : members.entrySet()) {
            String name = entry.getKey().name();
            if (!isIntermediaryMemberName(name)) continue;
            String mapped = entry.getValue();
            String existing = result.putIfAbsent(name, mapped);
            if (existing != null && !existing.equals(mapped)) {
                result.put(name, AMBIGUOUS);
            }
        }
        return result;
    }

    private static boolean isIntermediaryMemberName(String name) {
        return name.startsWith("field_") || name.startsWith("method_") || name.startsWith("comp_");
    }

    private record MemberKey(String owner, String name, String descriptor) {
    }

    private record OwnerName(String owner, String name) {
    }

    private record MethodEntry(String descriptor, String mappedName, int parameterCount) {
    }
}

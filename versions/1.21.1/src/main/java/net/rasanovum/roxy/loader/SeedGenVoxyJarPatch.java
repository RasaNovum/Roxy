package net.rasanovum.roxy.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class SeedGenVoxyJarPatch {
    private static final String MODS_TOML = "META-INF/neoforge.mods.toml";
    private static final String MOD_MARKER = "mobius/voxyseedgen/mixin/VoxyCullDistanceMixin.class";
    private static final String FIELD_OWNER = "me/cortex/voxy/client/config/VoxyConfig";
    private static final String FIELD_NAME = "sectionRenderDistance";
    private static final String LADDER_WRITER = "mobius/voxyseedgen/voxy/LadderWriter";
    private static final String WORLD_ENGINE = "me/cortex/voxy/common/world/WorldEngine";
    private static final String WORLD_SECTION = "me/cortex/voxy/common/world/WorldSection";
    private static final String VOXY_LOGGER = "me/cortex/voxy/common/Logger";
    private static final String SAVE_VOID = "(L" + WORLD_SECTION + ";)V";
    private static final String SAVE_BOOLEAN = "(L" + WORLD_SECTION + ";)Z";
    private static final String LOG_VOID = "([Ljava/lang/Object;)V";
    private static final String LOG_STRING = "([Ljava/lang/Object;)Ljava/lang/String;";
    private static final String PRIORITY_MIXIN = "mobius/voxyseedgen/mixin/VoxyMeshPriorityMixin";
    private static final String HELD_CLEANUP = "roxy$forgetHeld";
    private static final Map<String, Integer> EXPECTED_READS = Map.of(
            "mobius/voxyseedgen/mixin/VoxyCullDistanceMixin.class", 2,
            "mobius/voxyseedgen/voxy/RenderReach.class", 3,
            "mobius/voxyseedgen/voxy/CoarseRings.class", 2,
            "mobius/voxyseedgen/voxy/LodLevels.class", 1
    );
    private static final Pattern MOD_ID = Pattern.compile("(?m)^modId\\s*=\\s*\"voxyseedgen\"\\s*$");
    private static final Pattern VERSION = Pattern.compile("(?m)^version\\s*=\\s*\"0\\.3\\.0\"\\s*$");

    private SeedGenVoxyJarPatch() {
    }

    static boolean isTarget(Path path) throws IOException {
        try (ZipFile input = new ZipFile(path.toFile())) {
            ZipEntry metadata = input.getEntry(MODS_TOML);
            if (metadata == null || input.getEntry(MOD_MARKER) == null) return false;
            try (InputStream stream = input.getInputStream(metadata)) {
                String toml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                return MOD_ID.matcher(toml).find() && VERSION.matcher(toml).find();
            }
        }
    }

    static Path patch(Path original) throws IOException {
        if (!isTarget(original)) return null;

        Map<String, byte[]> replacements = new HashMap<>();
        Map<String, Integer> intReads = new HashMap<>();
        Map<String, Integer> floatReads = new HashMap<>();
        Map<String, Integer> voidSaves = new HashMap<>();
        Map<String, Integer> booleanSaves = new HashMap<>();
        Map<String, Integer> voidLogs = new HashMap<>();
        Map<String, Integer> stringLogs = new HashMap<>();
        boolean[] cleanupReady = new boolean[1];
        try (ZipFile input = new ZipFile(original.toFile())) {
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")
                        || !entry.getName().startsWith("mobius/voxyseedgen/")) continue;
                try (InputStream stream = input.getInputStream(entry)) {
                    ReadPatch result = patchBytecode(stream.readAllBytes(), entry.getName());
                    if (result.bytecode != null) replacements.put(entry.getName(), result.bytecode);
                    if (result.intReads > 0) intReads.put(entry.getName(), result.intReads);
                    if (result.floatReads > 0) floatReads.put(entry.getName(), result.floatReads);
                    if (result.voidSaves > 0) voidSaves.put(entry.getName(), result.voidSaves);
                    if (result.booleanSaves > 0) booleanSaves.put(entry.getName(), result.booleanSaves);
                    if (result.voidLogs > 0) voidLogs.put(entry.getName(), result.voidLogs);
                    if (result.stringLogs > 0) stringLogs.put(entry.getName(), result.stringLogs);
                    if (entry.getName().equals(LADDER_WRITER + ".class")) cleanupReady[0] = result.cleanupReady;
                }
            }
        }

        boolean originalFields = intReads.equals(EXPECTED_READS) && floatReads.isEmpty();
        boolean patchedFields = intReads.isEmpty() && floatReads.equals(EXPECTED_READS);
        boolean oldSaveAbi = voidSaves.equals(Map.of(LADDER_WRITER + ".class", 1)) && booleanSaves.isEmpty();
        boolean currentSaveAbi = voidSaves.isEmpty()
                && booleanSaves.equals(Map.of(LADDER_WRITER + ".class", 1));
        boolean oldLogAbi = voidLogs.equals(Map.of(PRIORITY_MIXIN + ".class", 1)) && stringLogs.isEmpty();
        boolean currentLogAbi = voidLogs.isEmpty()
                && stringLogs.equals(Map.of(PRIORITY_MIXIN + ".class", 1));
        if ((!originalFields && !patchedFields) || (!oldSaveAbi && !currentSaveAbi)
                || (!oldLogAbi && !currentLogAbi)) {
            throw new IOException("Roxy: unsupported Voxy SeedGen 0.3.0 field/method layout");
        }
        if (currentSaveAbi && currentLogAbi && patchedFields && cleanupReady[0]) {
            return null;
        }

        Path patched = Files.createTempFile("roxy-voxyseedgen-0.3.0-", ".jar");
        boolean complete = false;
        try {
            try (ZipFile input = new ZipFile(original.toFile());
                 ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(patched))) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    ZipEntry copy = new ZipEntry(entry.getName());
                    if (entry.getTime() >= 0) copy.setTime(entry.getTime());
                    if (entry.getComment() != null) copy.setComment(entry.getComment());
                    if (entry.getExtra() != null) copy.setExtra(entry.getExtra());
                    output.putNextEntry(copy);
                    if (!entry.isDirectory()) {
                        byte[] replacement = replacements.get(entry.getName());
                        if (replacement != null) {
                            output.write(replacement);
                        } else {
                            try (InputStream stream = input.getInputStream(entry)) {
                                stream.transferTo(output);
                            }
                        }
                    }
                    output.closeEntry();
                }
            }
            complete = true;
            patched.toFile().deleteOnExit();
            return patched;
        } finally {
            if (!complete) Files.deleteIfExists(patched);
        }
    }

    private static ReadPatch patchBytecode(byte[] input, String entryName) throws IOException {
        ClassReader reader = new ClassReader(input);
        int[] intReads = new int[1];
        int[] floatReads = new int[1];
        int[] voidSaves = new int[1];
        int[] booleanSaves = new int[1];
        int[] voidLogs = new int[1];
        int[] stringLogs = new int[1];
        boolean[] cleanupPatched = new boolean[1];
        int[] cleanupMethodCount = new int[1];
        boolean isLadderWriter = entryName.equals(LADDER_WRITER + ".class");
        boolean cleanupMarker = isLadderWriter && hasCleanupMarker(input);
        ClassWriter writer = new ClassWriter(reader, 0);
        try {
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (isLadderWriter && name.equals(HELD_CLEANUP)) {
                    if (!descriptor.equals("(I)V") || (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC))
                            != (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) {
                        throw unsupported(entryName, name, descriptor);
                    }
                    return delegate;
                }
                if (isLadderWriter && !cleanupMarker && (name.equals("endPass") || name.equals("endTile"))
                        && descriptor.equals("()V")) {
                    cleanupPatched[0] = true;
                    cleanupMethodCount[0]++;
                    return new DrainHeldVisitor(delegate, name.equals("endPass"));
                }
                if (isLadderWriter && !cleanupMarker && name.equals("release")
                        && descriptor.equals("(I)V") && (access & Opcodes.ACC_PRIVATE) != 0) {
                    cleanupPatched[0] = true;
                    cleanupMethodCount[0]++;
                    return new ReleaseHeldVisitor(delegate);
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private boolean conversionPending;

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        flushUnexpected();
                        if (opcode == Opcodes.GETFIELD
                                && owner.equals(FIELD_OWNER)
                                && fieldName.equals(FIELD_NAME)) {
                            if (fieldDescriptor.equals("F")) {
                                floatReads[0]++;
                            } else if (fieldDescriptor.equals("I")) {
                                conversionPending = true;
                                intReads[0]++;
                                super.visitFieldInsn(opcode, owner, fieldName, "F");
                                return;
                            } else {
                                throw unsupported(entryName, name, descriptor);
                            }
                        }
                        super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (conversionPending) {
                            conversionPending = false;
                            if (opcode == Opcodes.I2D) super.visitInsn(Opcodes.F2D);
                            else if (opcode != Opcodes.I2F) throw unsupported(entryName, name, descriptor);
                            return;
                        }
                        super.visitInsn(opcode);
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        flushUnexpected();
                        super.visitIntInsn(opcode, operand);
                    }

                    @Override
                    public void visitVarInsn(int opcode, int variable) {
                        flushUnexpected();
                        super.visitVarInsn(opcode, variable);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        flushUnexpected();
                        super.visitTypeInsn(opcode, type);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDescriptor, boolean isInterface) {
                        flushUnexpected();
                        if (owner.equals(WORLD_ENGINE) && methodName.equals("saveSection")
                                && (methodDescriptor.equals(SAVE_VOID) || methodDescriptor.equals(SAVE_BOOLEAN))) {
                            if (!isLadderWriter || !name.equals("markDirty")
                                    || !descriptor.equals("(L" + WORLD_SECTION + ";II)V")
                                    || opcode != Opcodes.INVOKEVIRTUAL || isInterface) {
                                throw unsupported(entryName, name, descriptor);
                            }
                            if (methodDescriptor.equals(SAVE_VOID)) {
                                voidSaves[0]++;
                                super.visitMethodInsn(opcode, owner, methodName, SAVE_BOOLEAN, false);
                                super.visitInsn(Opcodes.POP);
                                return;
                            }
                            booleanSaves[0]++;
                        }
                        if (owner.equals(VOXY_LOGGER) && methodName.equals("info")
                                && (methodDescriptor.equals(LOG_VOID) || methodDescriptor.equals(LOG_STRING))) {
                            if (!entryName.equals(PRIORITY_MIXIN + ".class")
                                    || !name.equals("voxyseedgen$neverNegative")
                                    || !descriptor.equals("(I)I") || opcode != Opcodes.INVOKESTATIC
                                    || isInterface) {
                                throw unsupported(entryName, name, descriptor);
                            }
                            if (methodDescriptor.equals(LOG_VOID)) {
                                voidLogs[0]++;
                                super.visitMethodInsn(opcode, owner, methodName, LOG_STRING, false);
                                super.visitInsn(Opcodes.POP);
                                return;
                            }
                            stringLogs[0]++;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String methodName, String methodDescriptor,
                                                      org.objectweb.asm.Handle bootstrapMethodHandle,
                                                      Object... bootstrapMethodArguments) {
                        flushUnexpected();
                        super.visitInvokeDynamicInsn(methodName, methodDescriptor,
                                bootstrapMethodHandle, bootstrapMethodArguments);
                    }

                    @Override
                    public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                        flushUnexpected();
                        super.visitJumpInsn(opcode, label);
                    }

                    @Override
                    public void visitLabel(org.objectweb.asm.Label label) {
                        flushUnexpected();
                        super.visitLabel(label);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        flushUnexpected();
                        super.visitLdcInsn(value);
                    }

                    @Override
                    public void visitIincInsn(int variable, int increment) {
                        flushUnexpected();
                        super.visitIincInsn(variable, increment);
                    }

                    @Override
                    public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label defaultLabel,
                                                     org.objectweb.asm.Label... labels) {
                        flushUnexpected();
                        super.visitTableSwitchInsn(min, max, defaultLabel, labels);
                    }

                    @Override
                    public void visitLookupSwitchInsn(org.objectweb.asm.Label defaultLabel, int[] keys,
                                                      org.objectweb.asm.Label[] labels) {
                        flushUnexpected();
                        super.visitLookupSwitchInsn(defaultLabel, keys, labels);
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                        flushUnexpected();
                        super.visitMultiANewArrayInsn(descriptor, dimensions);
                    }

                    @Override
                    public void visitEnd() {
                        flushUnexpected();
                        super.visitEnd();
                    }

                    private void flushUnexpected() {
                        if (conversionPending) throw unsupported(entryName, name, descriptor);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (isLadderWriter && cleanupPatched[0] && !cleanupMarker) {
                    emitForgetHeld(writer, reader.getClassName());
                }
                super.visitEnd();
            }
            }, 0);
        } catch (IllegalStateException exception) {
            throw new IOException(exception.getMessage(), exception);
        }

        if (intReads[0] != 0 && !EXPECTED_READS.containsKey(entryName)) {
            throw new IOException("Roxy: unexpected SeedGen render-distance reads in " + entryName);
        }
        int expected = EXPECTED_READS.getOrDefault(entryName, 0);
        if (intReads[0] > expected || floatReads[0] > expected) {
            throw new IOException("Roxy: unsupported Voxy SeedGen render-distance read count in " + entryName);
        }
        boolean cleanupReady = isLadderWriter && (cleanupMarker || cleanupPatched[0]);
        if (isLadderWriter && cleanupPatched[0] && cleanupMethodCount[0] != 3) {
            throw new IOException("Roxy: unsupported SeedGen LadderWriter cleanup method set");
        }
        boolean hasChanges = intReads[0] != 0 || voidSaves[0] != 0 || voidLogs[0] != 0
                || (isLadderWriter && cleanupPatched[0]);
        if (isLadderWriter && !cleanupReady) {
            throw new IOException("Roxy: unsupported SeedGen LadderWriter cleanup layout");
        }
        return new ReadPatch(hasChanges ? writer.toByteArray() : null,
                intReads[0], floatReads[0], voidSaves[0], booleanSaves[0],
                voidLogs[0], stringLogs[0], cleanupReady);
    }

    private static IllegalStateException unsupported(String entryName, String method, String descriptor) {
        return new IllegalStateException("Roxy: unsupported SeedGen field conversion in " + entryName
                + " " + method + descriptor);
    }

    private static boolean hasCleanupMarker(byte[] bytecode) {
        boolean[] found = new boolean[1];
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (name.equals(HELD_CLEANUP)) {
                    if (!descriptor.equals("(I)V") || (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC))
                            != (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) {
                        throw unsupported(LADDER_WRITER + ".class", name, descriptor);
                    }
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static void emitForgetHeld(ClassWriter writer, String owner) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                HELD_CLEANUP, "(I)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.DUP);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, "heldCount", "I");
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.ISUB);
        method.visitInsn(Opcodes.DUP_X1);
        method.visitFieldInsn(Opcodes.PUTFIELD, owner, "heldCount", "I");
        method.visitVarInsn(Opcodes.ISTORE, 2);

        copyHeldArray(method, owner, "heldSections", "[L" + WORLD_SECTION + ";", Opcodes.AALOAD, Opcodes.AASTORE);
        copyHeldArray(method, owner, "heldLevels", "[I", Opcodes.IALOAD, Opcodes.IASTORE);
        copyHeldArray(method, owner, "heldX", "[I", Opcodes.IALOAD, Opcodes.IASTORE);
        copyHeldArray(method, owner, "heldY", "[I", Opcodes.IALOAD, Opcodes.IASTORE);
        copyHeldArray(method, owner, "heldZ", "[I", Opcodes.IALOAD, Opcodes.IASTORE);
        copyHeldArray(method, owner, "heldFaces", "[I", Opcodes.IALOAD, Opcodes.IASTORE);
        copyHeldArray(method, owner, "heldSolid", "[Z", Opcodes.BALOAD, Opcodes.BASTORE);

        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, "heldSections", "[L" + WORLD_SECTION + ";");
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitInsn(Opcodes.ACONST_NULL);
        method.visitInsn(Opcodes.AASTORE);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(5, 3);
        method.visitEnd();
    }

    private static void copyHeldArray(MethodVisitor method, String owner, String field,
                                      String descriptor, int loadArrayElement, int storeArrayElement) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, field, descriptor);
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, owner, field, descriptor);
        method.visitVarInsn(Opcodes.ILOAD, 2);
        method.visitInsn(loadArrayElement);
        method.visitInsn(storeArrayElement);
    }

    private static final class ReleaseHeldVisitor extends MethodVisitor {
        private static final String THROWABLE = "java/lang/Throwable";
        private int sectionReleases;
        private boolean cleanupTail;
        private boolean cleanupTailReturn;
        private boolean awaitingOriginalThrow;
        private final Map<String, Integer> cleanupTailFields = new HashMap<>();
        private final org.objectweb.asm.Label releaseTryStart = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label releaseTryEnd = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label releaseHandler = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label originalThrow = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label suppressDone = new org.objectweb.asm.Label();

        private ReleaseHeldVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitTryCatchBlock(releaseTryStart, releaseTryEnd, releaseHandler, THROWABLE);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (owner.equals(WORLD_SECTION) && name.equals("release") && descriptor.equals("()I")) {
                sectionReleases++;
                if (sectionReleases > 2) throw unsupported(LADDER_WRITER + ".class", "release", "(I)V");
                super.visitVarInsn(Opcodes.ASTORE, 4);
                if (sectionReleases == 2) super.visitLabel(releaseTryStart);
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitVarInsn(Opcodes.ILOAD, 1);
                super.visitMethodInsn(Opcodes.INVOKESPECIAL, LADDER_WRITER, HELD_CLEANUP, "(I)V", false);
                super.visitVarInsn(Opcodes.ALOAD, 4);
            }
            if (!cleanupTail) super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            if (sectionReleases == 2 && owner.equals(WORLD_SECTION)
                    && name.equals("release") && descriptor.equals("()I")) {
                super.visitLabel(releaseTryEnd);
            }
        }

        @Override
        public void visitInsn(int opcode) {
            if (cleanupTail) {
                if (opcode == Opcodes.RETURN) {
                    cleanupTailReturn = true;
                    super.visitInsn(opcode);
                }
                return;
            }
            if (opcode == Opcodes.ATHROW && awaitingOriginalThrow) {
                awaitingOriginalThrow = false;
                super.visitJumpInsn(Opcodes.GOTO, originalThrow);
                super.visitLabel(releaseHandler);
                super.visitFrame(Opcodes.F_FULL, 5,
                        new Object[]{LADDER_WRITER, Opcodes.INTEGER, WORLD_SECTION, THROWABLE, WORLD_SECTION},
                        1, new Object[]{THROWABLE});
                super.visitVarInsn(Opcodes.ASTORE, 5);
                super.visitVarInsn(Opcodes.ALOAD, 3);
                super.visitVarInsn(Opcodes.ALOAD, 5);
                super.visitJumpInsn(Opcodes.IF_ACMPEQ, suppressDone);
                super.visitVarInsn(Opcodes.ALOAD, 3);
                super.visitVarInsn(Opcodes.ALOAD, 5);
                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THROWABLE, "addSuppressed",
                        "(Ljava/lang/Throwable;)V", false);
                super.visitLabel(suppressDone);
                super.visitFrame(Opcodes.F_FULL, 5,
                        new Object[]{LADDER_WRITER, Opcodes.INTEGER, WORLD_SECTION, THROWABLE, WORLD_SECTION},
                        0, null);
                super.visitLabel(originalThrow);
                super.visitVarInsn(Opcodes.ALOAD, 3);
                super.visitInsn(Opcodes.ATHROW);
                cleanupTail = true;
                return;
            }
            super.visitInsn(opcode);
            if (opcode == Opcodes.ATHROW) cleanupTail = true;
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            Map<String, Integer> expectedTail = Map.of("heldCount:GETFIELD", 1, "heldCount:PUTFIELD", 1,
                    "heldSections:GETFIELD", 3, "heldLevels:GETFIELD", 2, "heldX:GETFIELD", 2,
                    "heldY:GETFIELD", 2, "heldZ:GETFIELD", 2, "heldFaces:GETFIELD", 2,
                    "heldSolid:GETFIELD", 2);
            if (sectionReleases != 2 || !cleanupTailReturn || !cleanupTailFields.equals(expectedTail)) {
                throw unsupported(LADDER_WRITER + ".class", "release", "(I)V");
            }
            super.visitMaxs(maxStack + 2, Math.max(maxLocals, 6));
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (cleanupTail) {
                if (owner.equals(LADDER_WRITER) && (opcode == Opcodes.GETFIELD || opcode == Opcodes.PUTFIELD)) {
                    cleanupTailFields.merge(name + ":" + (opcode == Opcodes.GETFIELD ? "GETFIELD" : "PUTFIELD"),
                            1, Integer::sum);
                }
            } else {
                super.visitFieldInsn(opcode, owner, name, descriptor);
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            if (!cleanupTail) super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int variable) {
            if (cleanupTail) return;
            if (sectionReleases == 2 && opcode == Opcodes.ALOAD && variable == 3) {
                awaitingOriginalThrow = true;
                return;
            }
            super.visitVarInsn(opcode, variable);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (!cleanupTail) super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
            if (!cleanupTail) super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (!cleanupTail) super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int variable, int increment) {
            if (!cleanupTail) super.visitIincInsn(variable, increment);
        }

        @Override
        public void visitLabel(org.objectweb.asm.Label label) {
            super.visitLabel(label);
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
            if (cleanupTail) {
                super.visitFrame(Opcodes.F_FULL, 3,
                        new Object[]{LADDER_WRITER, Opcodes.INTEGER, WORLD_SECTION}, 0, null);
            } else {
                super.visitFrame(type, numLocal, local, numStack, stack);
            }
        }

        @Override
        public void visitLineNumber(int line, org.objectweb.asm.Label start) {
            super.visitLineNumber(line, start);
        }

        @Override
        public void visitLookupSwitchInsn(org.objectweb.asm.Label defaultLabel, int[] keys,
                                          org.objectweb.asm.Label[] labels) {
            if (!cleanupTail) super.visitLookupSwitchInsn(defaultLabel, keys, labels);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label defaultLabel,
                                         org.objectweb.asm.Label... labels) {
            if (!cleanupTail) super.visitTableSwitchInsn(min, max, defaultLabel, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            if (!cleanupTail) super.visitMultiANewArrayInsn(descriptor, dimensions);
        }
    }

    private static final class DrainHeldVisitor extends MethodVisitor {
        private static final String THROWABLE = "java/lang/Throwable";
        private final org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label tryStart = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label tryEnd = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label handler = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label continueLoop = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label rethrow = new org.objectweb.asm.Label();
        private final org.objectweb.asm.Label returnLabel = new org.objectweb.asm.Label();
        private final String owner;

        private final boolean drainAll;

        private DrainHeldVisitor(MethodVisitor delegate, boolean drainAll) {
            super(Opcodes.ASM9, delegate);
            this.owner = LADDER_WRITER;
            this.drainAll = drainAll;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitTryCatchBlock(tryStart, tryEnd, handler, THROWABLE);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitFieldInsn(Opcodes.GETFIELD, owner, "heldCount", "I");
            super.visitInsn(Opcodes.ICONST_1);
            super.visitInsn(Opcodes.ISUB);
            super.visitVarInsn(Opcodes.ISTORE, 1);
            super.visitInsn(Opcodes.ACONST_NULL);
            super.visitVarInsn(Opcodes.ASTORE, 2);

            super.visitLabel(loop);
            super.visitFrame(Opcodes.F_APPEND, 2,
                    new Object[]{Opcodes.INTEGER, THROWABLE}, 0, null);
            super.visitVarInsn(Opcodes.ILOAD, 1);
            super.visitJumpInsn(Opcodes.IFLT, done);
            if (!drainAll) {
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitFieldInsn(Opcodes.GETFIELD, owner, "heldLevels", "[I");
                super.visitVarInsn(Opcodes.ILOAD, 1);
                super.visitInsn(Opcodes.IALOAD);
                super.visitInsn(Opcodes.ICONST_2);
                super.visitJumpInsn(Opcodes.IF_ICMPGT, continueLoop);
            }
            super.visitLabel(tryStart);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitVarInsn(Opcodes.ILOAD, 1);
            super.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "release", "(I)V", false);
            super.visitLabel(tryEnd);
            super.visitJumpInsn(Opcodes.GOTO, continueLoop);

            super.visitLabel(handler);
            super.visitFrame(Opcodes.F_FULL, 3,
                    new Object[]{owner, Opcodes.INTEGER, THROWABLE}, 1, new Object[]{THROWABLE});
            super.visitVarInsn(Opcodes.ASTORE, 3);
            super.visitVarInsn(Opcodes.ALOAD, 2);
            super.visitJumpInsn(Opcodes.IFNONNULL, rethrow);
            super.visitVarInsn(Opcodes.ALOAD, 3);
            super.visitVarInsn(Opcodes.ASTORE, 2);
            super.visitJumpInsn(Opcodes.GOTO, continueLoop);

            super.visitLabel(rethrow);
            super.visitFrame(Opcodes.F_FULL, 4,
                    new Object[]{owner, Opcodes.INTEGER, THROWABLE, THROWABLE}, 0, null);
            super.visitVarInsn(Opcodes.ALOAD, 2);
            super.visitVarInsn(Opcodes.ALOAD, 3);
            super.visitJumpInsn(Opcodes.IF_ACMPEQ, continueLoop);
            super.visitVarInsn(Opcodes.ALOAD, 2);
            super.visitVarInsn(Opcodes.ALOAD, 3);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, THROWABLE, "addSuppressed", "(Ljava/lang/Throwable;)V", false);

            super.visitLabel(continueLoop);
            super.visitFrame(Opcodes.F_FULL, 3,
                    new Object[]{owner, Opcodes.INTEGER, THROWABLE}, 0, null);
            super.visitIincInsn(1, -1);
            super.visitJumpInsn(Opcodes.GOTO, loop);

            super.visitLabel(done);
            super.visitFrame(Opcodes.F_FULL, 3,
                    new Object[]{owner, Opcodes.INTEGER, THROWABLE}, 0, null);
            super.visitVarInsn(Opcodes.ALOAD, 2);
            super.visitJumpInsn(Opcodes.IFNULL, returnLabel);
            super.visitVarInsn(Opcodes.ALOAD, 2);
            super.visitInsn(Opcodes.ATHROW);
            super.visitLabel(returnLabel);
            super.visitFrame(Opcodes.F_FULL, 3,
                    new Object[]{owner, Opcodes.INTEGER, THROWABLE}, 0, null);
            super.visitInsn(Opcodes.RETURN);
        }

        @Override public void visitInsn(int opcode) { }
        @Override public void visitIntInsn(int opcode, int operand) { }
        @Override public void visitVarInsn(int opcode, int variable) { }
        @Override public void visitTypeInsn(int opcode, String type) { }
        @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { }
        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) { }
        @Override public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle handle, Object... args) { }
        @Override public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) { }
        @Override public void visitLabel(org.objectweb.asm.Label label) { }
        @Override public void visitLdcInsn(Object value) { }
        @Override public void visitIincInsn(int variable, int increment) { }
        @Override public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label defaultLabel, org.objectweb.asm.Label... labels) { }
        @Override public void visitLookupSwitchInsn(org.objectweb.asm.Label defaultLabel, int[] keys, org.objectweb.asm.Label[] labels) { }
        @Override public void visitMultiANewArrayInsn(String descriptor, int dimensions) { }
        @Override public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                                 org.objectweb.asm.Label handler, String type) { }
        @Override public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) { }
        @Override public void visitLineNumber(int line, org.objectweb.asm.Label start) { }
        @Override public void visitLocalVariable(String name, String descriptor, String signature,
                                                 org.objectweb.asm.Label start, org.objectweb.asm.Label end, int index) { }
        @Override public void visitMaxs(int maxStack, int maxLocals) { super.visitMaxs(3, 4); }
    }

    private record ReadPatch(byte[] bytecode, int intReads, int floatReads, int voidSaves, int booleanSaves,
                             int voidLogs, int stringLogs, boolean cleanupReady) {
    }
}

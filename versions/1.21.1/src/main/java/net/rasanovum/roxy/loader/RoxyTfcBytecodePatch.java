package net.rasanovum.roxy.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

final class RoxyTfcBytecodePatch implements Opcodes {
    private static final String BRIDGE = "net/rasanovum/roxy/tfc/TfcVoxyBridge";
    private static final String MODEL = "me/cortex/voxy/client/core/model/ModelFactory";
    private static final String MESH = "me/cortex/voxy/client/core/rendering/building/RenderDataFactory";
    private static final String BAKERY = "me/cortex/voxy/client/core/model/ModelBakerySubsystem";

    static byte[] patch(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getClassName().equals(MODEL) && !reader.getClassName().equals(MESH)
                && !reader.getClassName().equals(BAKERY)) return bytes;
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (node.name.equals(MODEL)) patchModel(node);
        else if (node.name.equals(MESH)) patchMesh(node);
        else {
            for (MethodNode method : node.methods) {
                if (!method.name.equals("<init>")) continue;
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() != RETURN) continue;
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(call("registerBakery", "(Ljava/lang/Object;)V"));
                    method.instructions.insertBefore(insn, hook);
                }
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void patchModel(ClassNode node) {
        int initialized = 0, resolved = 0, scoped = 0, uploading = 0, committed = 0;
        int lightingKeys = 0, lightingFlags = 0;
        MethodNode bake = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("processUploads")) {
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(ALOAD, 0));
                hook.add(call("uploadsStarted", "(Ljava/lang/Object;)V"));
                method.instructions.insert(hook);
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (method.name.equals("processTextureBakeResult") && insn instanceof MethodInsnNode invocation) {
                    if (invocation.owner.equals(MODEL + "$ModelEntry") && invocation.name.equals("<init>")) {
                        method.instructions.insertBefore(insn, new MethodInsnNode(INVOKESTATIC,
                                "net/rasanovum/roxy/tfc/TfcModelLighting", "modelKey", "(I)I", false));
                        lightingKeys++;
                    }
                    if (invocation.owner.equals("org/lwjgl/system/MemoryUtil") && invocation.name.equals("memPutInt")
                            && invocation.desc.equals("(JI)V") && insn.getPrevious() instanceof VarInsnNode value
                            && value.getOpcode() == ILOAD && value.var == 24
                            && value.getPrevious() instanceof VarInsnNode address
                            && address.getOpcode() == LLOAD && address.var == 15) {
                        method.instructions.insertBefore(insn, new MethodInsnNode(INVOKESTATIC,
                                "net/rasanovum/roxy/tfc/TfcModelLighting", "flags", "(I)I", false));
                        lightingFlags++;
                    }
                }
                if (method.name.equals("processUploads") && insn instanceof MethodInsnNode invocation) {
                    if (invocation.owner.equals(MODEL + "$ResultUploader") && invocation.name.equals("upload")) {
                        InsnList hook = new InsnList();
                        hook.add(new VarInsnNode(ALOAD, 0));
                        hook.add(new VarInsnNode(ALOAD, 1));
                        hook.add(call("modelUploading", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
                        method.instructions.insertBefore(insn, hook);
                        uploading++;
                    }
                    if (invocation.owner.equals("me/cortex/voxy/client/core/rendering/util/UploadStream")
                            && invocation.name.equals("commit")) {
                        InsnList hook = new InsnList();
                        hook.add(new VarInsnNode(ALOAD, 0));
                        hook.add(call("uploadsCommitted", "(Ljava/lang/Object;)V"));
                        method.instructions.insert(insn, hook);
                        committed++;
                    }
                }
                if (method.name.equals("<init>") && insn instanceof FieldInsnNode field
                        && field.getOpcode() == PUTFIELD && field.name.equals("idMappings")) {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(new InsnNode(SWAP));
                    hook.add(call("initializeFactory", "(Ljava/lang/Object;[I)[I"));
                    method.instructions.insertBefore(insn, hook);
                    initialized++;
                }
                if (method.name.equals("addEntry") && insn instanceof MethodInsnNode invocation
                        && invocation.name.equals("getBlockStateFromBlockId")) {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(call("resolveBakeState", "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;"));
                    hook.add(new TypeInsnNode(CHECKCAST, Type.getReturnType(invocation.desc).getInternalName()));
                    method.instructions.insertBefore(insn, hook);
                    method.instructions.remove(insn);
                    resolved++;
                }
                if (method.name.equals("processModelResult") && insn.getOpcode() == ANEWARRAY && scoped == 0) {
                    // The first allocation follows the non-null BlockBake check in the pinned Voxy jar.
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(new VarInsnNode(ALOAD, 1));
                    hook.add(new FieldInsnNode(GETFIELD, MODEL + "$BlockBake", "blockId", "I"));
                    hook.add(call("beginBake", "(Ljava/lang/Object;I)V"));
                    method.instructions.insertBefore(insn, hook);
                    scoped++;
                    bake = method;
                }
            }
        }
        require(initialized == 1 && resolved == 1 && scoped == 1 && uploading == 1 && committed == 1, "model factory");
        require(lightingKeys == 1 && lightingFlags == 1, "TFC ambient occlusion metadata");
        wrapScope(node, bake, "endBake", false);
    }

    private static void patchMesh(ClassNode node) {
        int resolved = 0, reads = 0, neighbors = 0;
        MethodNode mesh = null;
        for (MethodNode method : node.methods) {
            boolean neighborArray = false;
            if (method.name.equals("generateMesh")) mesh = method;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (method.name.equals("prepareSectionData") && insn.getOpcode() == IALOAD) {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(new VarInsnNode(LLOAD, 16));
                    hook.add(new VarInsnNode(ILOAD, 13));
                    hook.add(call("resolveModel", "(ILjava/lang/Object;JI)I"));
                    method.instructions.insert(insn, hook);
                    resolved++;
                }
                if (insn instanceof FieldInsnNode field && field.getOpcode() == GETFIELD
                        && field.name.equals("neighboringFaces") && !method.name.equals("acquireNeighborData")) neighborArray = true;
                if (neighborArray && insn.getOpcode() == LALOAD) {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(call("readNeighbor", "([JILjava/lang/Object;)J"));
                    method.instructions.insertBefore(insn, hook);
                    method.instructions.remove(insn);
                    neighborArray = false;
                    reads++;
                }
                if (insn instanceof MethodInsnNode invocation && invocation.owner.equals(MODEL)
                        && invocation.name.equals("getModelId")) {
                    method.instructions.insertBefore(insn, new InsnNode(DUP2));
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(call("neighborResolved", "(Ljava/lang/Object;IILjava/lang/Object;)I"));
                    method.instructions.insert(insn, hook);
                    neighbors++;
                }
            }
        }
        require(resolved == 1 && reads == 9 && neighbors == 9 && mesh != null, "section mesher: " + resolved + "/" + reads + "/" + neighbors);
        wrapScope(node, mesh, "endMesh", true);
    }

    private static void wrapScope(ClassNode node, MethodNode original, String end, boolean mesh) {
        String name = original.name;
        original.name = "roxy$tfc$" + name;
        MethodNode wrapper = new MethodNode(ASM9, original.access, name, original.desc, original.signature,
                original.exceptions.toArray(String[]::new));
        LabelNode start = new LabelNode(), finish = new LabelNode(), handler = new LabelNode();
        InsnList code = wrapper.instructions;
        code.add(start);
        if (mesh) {
            code.add(new VarInsnNode(ALOAD, 0));
            code.add(new VarInsnNode(ALOAD, 1));
            code.add(call("beginMesh", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        }
        code.add(new VarInsnNode(ALOAD, 0));
        if (mesh) code.add(new VarInsnNode(ALOAD, 1));
        code.add(new MethodInsnNode(INVOKESPECIAL, node.name, original.name, original.desc, false));
        code.add(finish);
        code.add(call(end, "()V"));
        code.add(new InsnNode(mesh ? ARETURN : IRETURN));
        code.add(handler);
        Object[] locals = mesh ? new Object[]{node.name, Type.getArgumentTypes(original.desc)[0].getInternalName()} : new Object[]{node.name};
        code.add(new FrameNode(F_NEW, locals.length, locals, 1, new Object[]{"java/lang/Throwable"}));
        code.add(call(end, "()V"));
        code.add(new InsnNode(ATHROW));
        wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, finish, handler, null));
        node.methods.add(wrapper);
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(INVOKESTATIC, BRIDGE, name, descriptor, false);
    }

    private static void require(boolean condition, String area) {
        if (!condition) throw new IllegalStateException("Unsupported Voxy TFC hook layout: " + area);
    }
}

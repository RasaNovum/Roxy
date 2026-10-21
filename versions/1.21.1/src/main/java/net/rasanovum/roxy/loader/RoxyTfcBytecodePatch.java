package net.rasanovum.roxy.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

final class RoxyTfcBytecodePatch implements Opcodes {
    private static final String BRIDGE = "net/rasanovum/roxy/tfc/TfcVoxyBridge";
    private static final String LIGHTING = "net/rasanovum/roxy/tfc/TfcModelLighting";
    private static final String MODEL = "me/cortex/voxy/client/core/model/ModelFactory";
    private static final String UPLOADER = MODEL + "$ModelBakeResultUpload";
    private static final String RESULT_UPLOADER = MODEL + "$ResultUploader";
    private static final String MODEL_STORE = "me/cortex/voxy/client/core/model/ModelStore";
    private static final String GL_BUFFER = "me/cortex/voxy/client/core/gl/GlBuffer";
    private static final String GL_TEXTURE = "me/cortex/voxy/client/core/gl/GlTexture";
    private static final String MESH = "me/cortex/voxy/client/core/rendering/building/RenderDataFactory";
    private static final String BAKERY = "me/cortex/voxy/client/core/model/ModelBakerySubsystem";

    static byte[] patch(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getClassName().equals(MODEL) && !reader.getClassName().equals(MESH)
                && !reader.getClassName().equals(BAKERY) && !reader.getClassName().equals(UPLOADER)) return bytes;
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.EXPAND_FRAMES);
        if (node.name.equals(MODEL)) patchModel(node);
        else if (node.name.equals(MESH)) patchMesh(node);
        else if (node.name.equals(UPLOADER)) patchUploader(node);
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
        int lightingKeys = 0, lightingFlags = 0, tintValues = 0, variantUploads = 0;
        MethodNode bake = null;
        for (MethodNode method : node.methods) {
            LabelNode pollAgain = null;
            if (method.name.equals("processUploads")) {
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(ALOAD, 0));
                hook.add(call("uploadsStarted", "(Ljava/lang/Object;)V"));
                method.instructions.insert(hook);
                int polls = 0;
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode invocation)
                            || !invocation.owner.equals("java/util/concurrent/ConcurrentLinkedDeque")
                            || !invocation.name.equals("poll") || !invocation.desc.equals("()Ljava/lang/Object;")) continue;
                    if (++polls != 2) continue;
                    AbstractInsnNode queue = previousRealInstruction(instruction);
                    AbstractInsnNode queueThis = previousRealInstruction(queue);
                    if (!(queue instanceof FieldInsnNode field) || field.getOpcode() != GETFIELD
                            || !field.owner.equals(MODEL) || !field.name.equals("uploadResults")
                            || !field.desc.equals("Ljava/util/concurrent/ConcurrentLinkedDeque;")
                            || !(queueThis instanceof VarInsnNode load) || load.getOpcode() != ALOAD || load.var != 0) {
                        throw new IllegalStateException("Unsupported Voxy uploader poll loop");
                    }
                    pollAgain = new LabelNode();
                    method.instructions.insertBefore(queueThis, pollAgain);
                    method.instructions.insert(pollAgain,
                            new FrameNode(F_NEW, 1, new Object[]{node.name}, 0, new Object[]{}));
                }
                if (polls != 2 || pollAgain == null) throw new IllegalStateException("Unsupported Voxy uploader poll count");
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (method.name.equals("processTextureBakeResult") && insn instanceof MethodInsnNode invocation) {
                    if (invocation.owner.equals(MODEL + "$ModelEntry") && invocation.name.equals("<init>")) {
                        method.instructions.insertBefore(insn, new MethodInsnNode(INVOKESTATIC,
                                LIGHTING, "modelKey", "(I)I", false));
                        lightingKeys++;
                    }
                    if (invocation.owner.equals("org/lwjgl/system/MemoryUtil") && invocation.name.equals("memPutInt")
                            && invocation.desc.equals("(JI)V") && isModelTintWrite(insn)) {
                        method.instructions.insertBefore(insn, new MethodInsnNode(INVOKESTATIC,
                                LIGHTING, "modelTint", "(I)I", false));
                        tintValues++;
                    }
                    if (invocation.owner.equals("org/lwjgl/system/MemoryUtil") && invocation.name.equals("memPutInt")
                            && invocation.desc.equals("(JI)V") && isModelFlagsWrite(insn)) {
                        method.instructions.insertBefore(insn, new MethodInsnNode(INVOKESTATIC,
                                "net/rasanovum/roxy/tfc/TfcModelLighting", "flags", "(I)I", false));
                        lightingFlags++;
                    }
                }
                if (method.name.equals("processUploads") && insn instanceof MethodInsnNode invocation) {
                    if (invocation.owner.equals(MODEL + "$ResultUploader") && invocation.name.equals("upload")) {
                        AbstractInsnNode storage = previousRealInstruction(insn);
                        AbstractInsnNode thisLoad = previousRealInstruction(storage);
                        AbstractInsnNode uploaderLoad = previousRealInstruction(thisLoad);
                        if (!(uploaderLoad instanceof VarInsnNode loadUploader) || loadUploader.getOpcode() != ALOAD
                                || loadUploader.var != 1 || !(thisLoad instanceof VarInsnNode loadThis)
                                || loadThis.getOpcode() != ALOAD || loadThis.var != 0
                                || !(storage instanceof FieldInsnNode field) || field.getOpcode() != GETFIELD
                                || !field.owner.equals(MODEL) || !field.name.equals("storage")
                                || !field.desc.equals("L" + MODEL_STORE + ";")) {
                            throw new IllegalStateException("Unsupported Voxy uploader invocation layout");
                        }
                        LabelNode normalUpload = new LabelNode();
                        InsnList hook = new InsnList();
                        hook.add(new VarInsnNode(ALOAD, 0));
                        hook.add(new VarInsnNode(ALOAD, 1));
                        hook.add(call("modelUploading", "(Ljava/lang/Object;Ljava/lang/Object;)I"));
                        hook.add(new InsnNode(ICONST_2));
                        hook.add(new JumpInsnNode(IF_ICMPNE, normalUpload));
                        hook.add(new VarInsnNode(ALOAD, 1));
                        hook.add(new MethodInsnNode(INVOKEINTERFACE, RESULT_UPLOADER, "free", "()V", true));
                        if (pollAgain == null) throw new IllegalStateException("Missing Voxy uploader poll target");
                        hook.add(new JumpInsnNode(GOTO, pollAgain));
                        hook.add(normalUpload);
                        hook.add(new FrameNode(F_NEW, 2,
                                new Object[]{node.name, RESULT_UPLOADER}, 0, new Object[]{}));
                        method.instructions.insertBefore(uploaderLoad, hook);
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
                    require(method.desc.equals("(I)Z") && invocation.getOpcode() == INVOKEVIRTUAL
                            && invocation.owner.equals("me/cortex/voxy/common/world/other/Mapper"), "bake state lookup");
                    LabelNode variant = new LabelNode(), ready = new LabelNode();
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(new VarInsnNode(ILOAD, 1));
                    hook.add(call("isVariantBake", "(Ljava/lang/Object;I)Z"));
                    hook.add(new JumpInsnNode(IFNE, variant));
                    method.instructions.insertBefore(insn, hook);
                    hook = new InsnList();
                    hook.add(new JumpInsnNode(GOTO, ready));
                    hook.add(variant);
                    hook.add(new FrameNode(F_NEW, 2, new Object[]{MODEL, INTEGER}, 2,
                            new Object[]{invocation.owner, INTEGER}));
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(call("resolveBakeState", "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;"));
                    hook.add(new TypeInsnNode(CHECKCAST, Type.getReturnType(invocation.desc).getInternalName()));
                    hook.add(ready);
                    hook.add(new FrameNode(F_NEW, 2, new Object[]{MODEL, INTEGER}, 1,
                            new Object[]{Type.getReturnType(invocation.desc).getInternalName()}));
                    method.instructions.insert(insn, hook);
                    resolved++;
                }
                if (method.name.equals("processModelResult") && insn.getOpcode() == ANEWARRAY && scoped == 0) {
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(new VarInsnNode(ALOAD, 1));
                    hook.add(new FieldInsnNode(GETFIELD, MODEL + "$BlockBake", "blockId", "I"));
                    hook.add(call("beginBake", "(Ljava/lang/Object;I)V"));
                    method.instructions.insertBefore(insn, hook);
                    scoped++;
                    bake = method;
                }
                if (method.name.equals("processModelResult") && insn instanceof MethodInsnNode invocation
                        && invocation.owner.equals(MODEL) && invocation.name.equals("processTextureBakeResult")) {
                    AbstractInsnNode store = nextRealInstruction(insn);
                    AbstractInsnNode load = nextRealInstruction(store);
                    AbstractInsnNode nullCheck = nextRealInstruction(load);
                    if (!(store instanceof VarInsnNode uploaderStore) || uploaderStore.getOpcode() != ASTORE
                            || uploaderStore.var != 7 || !(load instanceof VarInsnNode uploaderReload)
                            || uploaderReload.getOpcode() != ALOAD || uploaderReload.var != 7
                            || !(nullCheck instanceof JumpInsnNode nullJump) || nullCheck.getOpcode() != IFNULL) {
                        throw new IllegalStateException("Unsupported Voxy model result enqueue layout");
                    }
                    LabelNode queued = nullJump.label;
                    FrameNode queuedFrame = frameForLabel(method, queued);
                    if (queuedFrame == null) throw new IllegalStateException("Missing Voxy model enqueue frame");
                    LabelNode accepted = new LabelNode();
                    InsnList hook = new InsnList();
                    hook.add(new VarInsnNode(ALOAD, 0));
                    hook.add(new VarInsnNode(ALOAD, 1));
                    hook.add(new FieldInsnNode(GETFIELD, MODEL + "$BlockBake", "blockId", "I"));
                    hook.add(new VarInsnNode(ALOAD, 7));
                    hook.add(call("variantBakeReady", "(Ljava/lang/Object;ILjava/lang/Object;)I"));
                    hook.add(new JumpInsnNode(IFEQ, accepted));
                    hook.add(new VarInsnNode(ALOAD, 7));
                    hook.add(new JumpInsnNode(IFNULL, queued));
                    hook.add(new VarInsnNode(ALOAD, 7));
                    hook.add(new MethodInsnNode(INVOKEINTERFACE, RESULT_UPLOADER, "free", "()V", true));
                    hook.add(new JumpInsnNode(GOTO, queued));
                    method.instructions.insert(store, hook);
                    method.instructions.insertBefore(load, accepted);
                    method.instructions.insert(accepted, cloneFrame(queuedFrame));
                    variantUploads++;
                }
            }
        }
        require(initialized == 1 && resolved == 1 && scoped == 1 && uploading == 1 && committed == 1, "model factory");
        require(lightingKeys == 1 && lightingFlags == 1 && tintValues == 1, "TFC model metadata");
        require(variantUploads == 1, "TFC variant uploader registration");
        protectScope(node, bake, "endBake", false);
    }

    private static boolean isModelTintWrite(AbstractInsnNode write) {
        AbstractInsnNode value = previousRealInstruction(write);
        AbstractInsnNode modelEntry = previousRealInstruction(value);
        return value instanceof FieldInsnNode field && field.getOpcode() == GETFIELD
                && field.owner.equals(MODEL + "$ModelEntry") && field.name.equals("tintingColour")
                && field.desc.equals("I") && modelEntry instanceof VarInsnNode load
                && load.getOpcode() == ALOAD && load.var == 12;
    }

    private static boolean isModelFlagsWrite(AbstractInsnNode write) {
        AbstractInsnNode value = previousInstruction(write);
        if (value instanceof MethodInsnNode ice && ice.getOpcode() == INVOKESTATIC
                && ice.owner.equals("net/rasanovum/roxy/bridge/RoxyIceModelBridge")
                && ice.name.equals("addIceBackfaceFlag") && ice.desc.equals("(Ljava/lang/Object;I)I")) {
            AbstractInsnNode swap = previousInstruction(ice);
            AbstractInsnNode state = previousInstruction(swap);
            if (swap == null || swap.getOpcode() != SWAP || !(state instanceof VarInsnNode load)
                    || load.getOpcode() != ALOAD || load.var != 2) return false;
            value = previousInstruction(state);
        }
        AbstractInsnNode address = previousInstruction(value);
        return value instanceof VarInsnNode flags && flags.getOpcode() == ILOAD && flags.var == 24
                && address instanceof VarInsnNode pointer && pointer.getOpcode() == LLOAD && pointer.var == 15;
    }

    private static AbstractInsnNode previousInstruction(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static AbstractInsnNode previousRealInstruction(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static AbstractInsnNode nextRealInstruction(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    private static FrameNode frameForLabel(MethodNode method, LabelNode label) {
        boolean found = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction == label) found = true;
            else if (found && instruction instanceof FrameNode frame) return frame;
            else if (found && instruction.getOpcode() >= 0) return null;
        }
        return null;
    }

    private static FrameNode cloneFrame(FrameNode frame) {
        Object[] locals = frame.local == null ? new Object[0] : frame.local.toArray();
        Object[] stack = frame.stack == null ? new Object[0] : frame.stack.toArray();
        return new FrameNode(F_NEW, locals.length, locals, stack.length, stack);
    }

    private static void patchUploader(ClassNode node) {
        int patched = 0;
        String uploadDescriptor = "(L" + GL_BUFFER + ";L" + GL_BUFFER + ";L" + GL_TEXTURE + ";)V";
        for (MethodNode method : node.methods) {
            if (!method.name.equals("upload") || !method.desc.equals(uploadDescriptor)) continue;
            LabelNode atlasStart = null;
            int checks = 0;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode field) || field.getOpcode() != GETFIELD
                        || !field.owner.equals(UPLOADER) || !field.name.equals("biomeUploadIndex")
                        || !field.desc.equals("I")) continue;
                AbstractInsnNode minusOne = nextRealInstruction(field);
                AbstractInsnNode branch = nextRealInstruction(minusOne);
                if (minusOne == null || minusOne.getOpcode() != ICONST_M1
                        || !(branch instanceof JumpInsnNode jump) || branch.getOpcode() != IF_ICMPEQ) continue;
                atlasStart = jump.label;
                checks++;
            }
            if (checks != 1 || atlasStart == null) {
                throw new IllegalStateException("Unsupported Voxy model uploader layout");
            }
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(ALOAD, 0));
            hook.add(new MethodInsnNode(INVOKESTATIC, "net/rasanovum/roxy/tfc/TfcVoxyUploadPatch",
                    "atlasOnly", "(Ljava/lang/Object;)Z", false));
            hook.add(new JumpInsnNode(IFNE, atlasStart));
            method.instructions.insert(hook);
            patched++;
        }
        require(patched == 1, "TFC texture-only upload");
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
        protectScope(node, mesh, "endMesh", true);
    }

    private static void protectScope(ClassNode node, MethodNode method, String end, boolean mesh) {
        Type[] arguments = Type.getArgumentTypes(method.desc);
        Type result = Type.getReturnType(method.desc);
        require((method.access & ACC_STATIC) == 0 && arguments.length == (mesh ? 1 : 0)
                && (mesh ? arguments[0].getSort() == Type.OBJECT && result.getSort() == Type.OBJECT
                : result.getSort() == Type.BOOLEAN), "scope descriptor");
        Object[] entryLocals = mesh ? new Object[]{node.name, arguments[0].getInternalName()} : new Object[]{node.name};
        int resultSlot = Math.max(method.maxLocals, entryLocals.length);
        Object[] resultLocals = new Object[resultSlot + 1];
        java.util.Arrays.fill(resultLocals, TOP);
        System.arraycopy(entryLocals, 0, resultLocals, 0, entryLocals.length);
        resultLocals[resultSlot] = mesh ? result.getInternalName() : INTEGER;

        LabelNode start = new LabelNode(), finish = new LabelNode(), handler = new LabelNode();
        int returnOpcode = mesh ? ARETURN : IRETURN;
        int exits = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != returnOpcode) continue;
            InsnList exit = new InsnList();
            exit.add(new VarInsnNode(mesh ? ASTORE : ISTORE, resultSlot));
            exit.add(new JumpInsnNode(GOTO, finish));
            method.instructions.insertBefore(instruction, exit);
            method.instructions.remove(instruction);
            exits++;
        }
        require(exits > 0, "scope exits");

        InsnList entry = new InsnList();
        entry.add(start);
        if (mesh) {
            entry.add(new VarInsnNode(ALOAD, 0));
            entry.add(new VarInsnNode(ALOAD, 1));
            entry.add(call("beginMesh", "(Ljava/lang/Object;Ljava/lang/Object;)V"));
        }
        method.instructions.insert(entry);

        InsnList code = method.instructions;
        code.add(finish);
        code.add(new FrameNode(F_NEW, resultLocals.length, resultLocals, 0, new Object[]{}));
        code.add(call(end, "()V"));
        code.add(new VarInsnNode(mesh ? ALOAD : ILOAD, resultSlot));
        code.add(new InsnNode(returnOpcode));
        code.add(handler);
        code.add(new FrameNode(F_NEW, entryLocals.length, entryLocals, 1, new Object[]{"java/lang/Throwable"}));
        code.add(call(mesh ? "meshFailed" : end, "()V"));
        code.add(new InsnNode(ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, finish, handler, null));
        method.maxLocals = resultSlot + 1;
    }

    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(INVOKESTATIC, BRIDGE, name, descriptor, false);
    }

    private static void require(boolean condition, String area) {
        if (!condition) throw new IllegalStateException("Unsupported Voxy TFC hook layout: " + area);
    }
}

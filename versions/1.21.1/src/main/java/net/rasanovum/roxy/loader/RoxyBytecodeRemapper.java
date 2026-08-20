package net.rasanovum.roxy.loader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AnnotationRemapper;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.FieldRemapper;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class RoxyBytecodeRemapper {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
    private static final String MODIFY_ARGS = "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;";
    private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
    private static final String MODIFY_CONSTANT = "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;";
    private static final String BLOCK_COLORS = "net/minecraft/client/color/block/BlockColors";
    private static final String COLOR_COMPAT = "net/rasanovum/roxy/loader/RoxyColorCompat";
    private static final String COLOR_HELPER_NEW = "net/minecraft/class_9848";
    private static final String FABRIC_METADATA = "net/fabricmc/loader/api/metadata/ModMetadata";
    private static final String FABRIC_CUSTOM_VALUE = "net/fabricmc/loader/api/metadata/CustomValue";
    private static final String LOADER_COMPAT = "net/rasanovum/roxy/loader/RoxyFabricMetadataCompat";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String MINECRAFT_SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";
    private static final String NEWER_DISCONNECT = "disconnect(" + MINECRAFT_SCREEN + "ZZ)V";
    private static final String MC_1_21_1_DISCONNECT = "disconnect(" + MINECRAFT_SCREEN + "Z)V";
    private static final String VOXY_WORLD_MIXIN = "me/cortex/voxy/commonImpl/mixin/minecraft/MixinWorld";
    private static final String VOXY_WORLD_CALLBACK = "voxy$injectIdentifier";
    private static final String VOXY_WORLD_CALLBACK_ORIGINAL = "roxy$voxyInjectIdentifier";
    private static final String VOXY_WORLD_CALLBACK_1_21_1 =
            "(Lnet/minecraft/world/level/storage/WritableLevelData;"
                    + "Lnet/minecraft/resources/ResourceKey;"
                    + "Lnet/minecraft/core/RegistryAccess;"
                    + "Lnet/minecraft/core/Holder;"
                    + "Ljava/util/function/Supplier;ZZJILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
    private static final String VOXY_WORLD_CALLBACK_1_21_11 =
            "(Lnet/minecraft/world/level/storage/WritableLevelData;"
                    + "Lnet/minecraft/resources/ResourceKey;"
                    + "Lnet/minecraft/core/RegistryAccess;"
                    + "Lnet/minecraft/core/Holder;"
                    + "ZZJILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
    private static final String VOXY_CLIENT_LEVEL_MIXIN = "me/cortex/voxy/client/mixin/minecraft/MixinClientLevel";
    private static final String VOXY_CLIENT_LEVEL_CALLBACK = "voxy$getBottom";
    private static final String VOXY_CLIENT_LEVEL_CALLBACK_ORIGINAL = "roxy$voxyGetBottom";
    private static final String VOXY_CLIENT_LEVEL_CALLBACK_1_21_11 =
            "(Lnet/minecraft/client/multiplayer/ClientPacketListener;"
                    + "Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;"
                    + "Lnet/minecraft/resources/ResourceKey;"
                    + "Lnet/minecraft/core/Holder;IILnet/minecraft/client/renderer/LevelRenderer;ZJIL"
                    + "org/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
    private static final String VOXY_CLIENT_LEVEL_CALLBACK_1_21_1 =
            "(Lnet/minecraft/client/multiplayer/ClientPacketListener;"
                    + "Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;"
                    + "Lnet/minecraft/resources/ResourceKey;"
                    + "Lnet/minecraft/core/Holder;IILjava/util/function/Supplier;"
                    + "Lnet/minecraft/client/renderer/LevelRenderer;ZJL"
                    + "org/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
    private static final String VOXY_RENDER_SYSTEM_MIXIN = "me/cortex/voxy/client/mixin/minecraft/MixinRenderSystem";
    private static final String VOXY_RENDER_SYSTEM_CALLBACK = "voxy$injectInit";
    private static final String VOXY_RENDER_SYSTEM_CALLBACK_NEW =
            "(IZLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
    private static final String BLOCK_STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String BLOCK_STATE_COMPAT = "net/rasanovum/roxy/loader/RoxyBlockStateCompat";
    private static final String COMPOUND_TAG = "net/minecraft/nbt/CompoundTag";
    private static final String COMPOUND_TAG_COMPAT = "net/rasanovum/roxy/loader/RoxyCompoundTagCompat";
    private static final String TEXTURE_ATLAS = "net/minecraft/client/renderer/texture/TextureAtlas";
    private static final String TEXTURE_ATLAS_COMPAT = "net/rasanovum/roxy/loader/RoxyTextureAtlasCompat";
    private static final String VOXY_TEXTURE_BAKERY = "me/cortex/voxy/client/core/model/bakery/SoftwareModelTextureBakery";
    private static final String TEXTURE_COMPAT = "net/rasanovum/roxy/loader/RoxyTextureCompat";
    private static final String VOXY_RASTERIZER = "Lme/cortex/voxy/client/core/model/bakery/SoftwareRasterizer;";
    private static final String VOXY_LIGHT_MAP_HELPER = "me/cortex/voxy/client/core/rendering/util/LightMapHelper";
    private static final String LIGHT_MAP_COMPAT = "net/rasanovum/roxy/loader/RoxyLightMapCompat";
    private static final String VOXY_IRIS_PIPELINE_MIXIN =
            "me/cortex/voxy/client/mixin/iris/MixinIrisRenderingPipeline";
    private static final String VOXY_IRIS_SHADER_PATCH =
            "me/cortex/voxy/client/iris/IrisShaderPatch";
    private static final String VOXY_IRIS_SAMPLERS =
            "me/cortex/voxy/client/iris/VoxySamplers";
    private static final String VOXY_IRIS_SAMPLER_HOLDER =
            "me/cortex/voxy/client/iris/IrisVoxyRenderPipelineData$2";
    private static final String VOXY_IRIS_TEXTURE_SAMPLER =
            "me/cortex/voxy/client/iris/IrisVoxyRenderPipelineData$TextureWSampler";
    private static final String VOXY_IRIS_PIPELINE_DATA =
            "me/cortex/voxy/client/iris/IrisVoxyRenderPipelineData";
    private static final String IRIS_SAMPLER_HOLDER =
            "net/irisshaders/iris/gl/sampler/SamplerHolder";
    private static final String IRIS_GL_SAMPLER =
            "net/irisshaders/iris/gl/sampler/GlSampler";
    private static final String IRIS_SAMPLER_COMPAT =
            "net/rasanovum/roxy/loader/RoxyIrisCompat";
    private static final String GSON_BUILDER = "com/google/gson/GsonBuilder";
    private static final String GSON_STRICTNESS = "com/google/gson/Strictness";
    private static final String VOXY_VERTEX_CONSUMER = "me/cortex/voxy/client/core/model/bakery/ReuseVertexConsumer";
    private static final String VOXY_VERTEX_CONSUMER_COMPAT = "net/rasanovum/roxy/loader/RoxyBakedQuadCompat";
    private static final String BAKED_MODEL = "net/minecraft/client/resources/model/BakedModel";
    private static final String BAKED_QUAD = "net/minecraft/client/renderer/block/model/BakedQuad";
    private static final String TEXTURE_ATLAS_SPRITE = "net/minecraft/client/renderer/texture/TextureAtlasSprite";
    private static final String DIRECTION = "net/minecraft/core/Direction";
    private static final String RANDOM_SOURCE = "net/minecraft/util/RandomSource";
    private static final String RENDER_SHAPE = "net/minecraft/world/level/block/RenderShape";
    private static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    private static final String QUATERNION_FC = "Lorg/joml/Quaternionfc;";
    private static final String QUATERNION_F = "Lorg/joml/Quaternionf;";
    private static final String MATRIX4FC = "Lorg/joml/Matrix4fc;";
    private static final String MATRIX4F = "Lorg/joml/Matrix4f;";
    private static final String GL_STATE_MANAGER_NEW = "com/mojang/blaze3d/opengl/GlStateManager";
    private static final String GL_STATE_MANAGER_1_21_1 = "com/mojang/blaze3d/platform/GlStateManager";
    private static final String VOXY_RENDER_SYSTEM = "me/cortex/voxy/client/core/VoxyRenderSystem";
    private static final String VOXY_CLIENT_INSTANCE = "me/cortex/voxy/client/VoxyClientInstance";
    private static final String VOXY_CONFIG = "me/cortex/voxy/client/config/VoxyConfig";
    private static final String VOXY_CONFIG_MENU = "me/cortex/voxy/client/config/VoxyConfigMenu";
    private static final String VOXY_VIEWPORT = "me/cortex/voxy/client/core/rendering/Viewport";
    private static final String VOXY_RENDER_PROPERTIES = "me/cortex/voxy/client/core/RenderProperties";
    private static final String VOXY_IRIS_RENDER_PIPELINE = "me/cortex/voxy/client/core/IrisVoxyRenderPipeline";
    private static final String VOXY_DEFAULT_CHUNK_RENDERER = "me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer";
    private static final String SODIUM_CHUNK_RENDER_MATRICES = "net/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices";
    private static final String SODIUM_TERRAIN_RENDER_PASS = "net/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass";
    private static final String SODIUM_CAMERA_TRANSFORM = "net/caffeinemc/mods/sodium/client/render/viewport/CameraTransform";
    private static final String SODIUM_FOG_PARAMETERS_NEW = "net/caffeinemc/mods/sodium/client/util/FogParameters";
    private static final String SODIUM_FOG_PARAMETERS = "net/rasanovum/roxy/loader/RoxyFogParameters";
    private static final String CHUNK_SECTION_LAYER_NEW = "net/minecraft/class_11515";
    private static final String RENDER_TYPE = "net/minecraft/client/renderer/RenderType";
    private static final String ITEM_BLOCK_RENDER_TYPES = "net/minecraft/client/renderer/ItemBlockRenderTypes";
    private static final String RENDER_TYPE_COMPAT = "net/rasanovum/roxy/loader/RoxyRenderTypeCompat";
    private static final String VOXY_SETUP_VIEWPORT_1_21_1 =
            "(L" + SODIUM_CHUNK_RENDER_MATRICES + ";DDD)L" + VOXY_VIEWPORT + ";";

    private RoxyBytecodeRemapper() {
    }

    public static byte[] remap(byte[] input, RoxyMappings mappings) {
        return remap(input, mappings, true);
    }

    public static byte[] remap(byte[] input, RoxyMappings mappings, boolean patchFabricMetadata) {
        ClassReader reader = new ClassReader(input);
        MixinMetadata metadata = MixinMetadata.read(reader);
        String sourceClass = reader.getClassName();
        RoxyRemapper remapper = new RoxyRemapper(mappings, sourceClass, metadata);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new RoxyClassRemapper(writer, remapper, metadata), 0);
        byte[] output = patchJavaVersion(patchNeoForgeColorMapAccess(patchClasspathDirectoryScan(writer.toByteArray())));
        output = patchVoxyFogParameters(output);
        output = patchVoxyChunkSectionLayer(output);
        output = patchVoxyWorldCallback(output);
        output = patchVoxyClientLevelCallback(output);
        output = patchVoxyRenderSystemCallback(output);
        output = patchVoxyRenderSystemViewport(output);
        output = patchVoxyDefaultChunkRenderer(output);
        output = patchVoxyTextureSetup(output);
        output = patchVoxyBakedModel(output);
        output = patchVoxyMetaFromLayer(output);
        output = patchVoxyVertexConsumer(output);
        output = patchVoxyLightMapHelper(output);
        output = patchVoxyIrisPipeline(output);
        output = patchVoxyIrisDepthOutput(output);
        output = patchVoxyIrisSamplers(output);
        output = patchVoxyIrisSamplerHolder(output);
        output = patchVoxyGsonCompatibility(output);
        output = patchVoxyClientWorldPath(output);
        output = patchVoxyConfigDefaults(output);
        output = patchVoxyConfigMenu(output);
        output = patchMinecraftVersionBridges(output);
        return patchFabricMetadata ? patchFabricMetadataAccess(output) : output;
    }

    private static byte[] patchVoxyIrisDepthOutput(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_IRIS_RENDER_PIPELINE)) return input;

        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("finish") || !descriptor.equals("(L" + VOXY_VIEWPORT + ";III)V")) return delegate;

                delegate.visitCode();
                Label disableStencil = new Label();
                Label dimensionsMatch = new Label();
                Label renderDepth = new Label();
                Label depthRendered = new Label();
                Label done = new Label();

                delegate.visitVarInsn(Opcodes.ALOAD, 0);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_IRIS_RENDER_PIPELINE, "data", "L" + VOXY_IRIS_PIPELINE_DATA + ";");
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_IRIS_PIPELINE_DATA, "renderToVanillaDepth", "Z");
                delegate.visitJumpInsn(Opcodes.IFEQ, disableStencil);

                delegate.visitVarInsn(Opcodes.ILOAD, 3);
                delegate.visitVarInsn(Opcodes.ALOAD, 1);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_VIEWPORT, "width", "I");
                delegate.visitJumpInsn(Opcodes.IF_ICMPNE, dimensionsMatch);
                delegate.visitVarInsn(Opcodes.ILOAD, 4);
                delegate.visitVarInsn(Opcodes.ALOAD, 1);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_VIEWPORT, "height", "I");
                delegate.visitJumpInsn(Opcodes.IF_ICMPEQ, renderDepth);

                delegate.visitLabel(dimensionsMatch);
                delegate.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                delegate.visitVarInsn(Opcodes.ALOAD, 0);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_IRIS_RENDER_PIPELINE, "data", "L" + VOXY_IRIS_PIPELINE_DATA + ";");
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_IRIS_PIPELINE_DATA, "useViewportDims", "Z");
                delegate.visitJumpInsn(Opcodes.IFEQ, disableStencil);
                delegate.visitInsn(Opcodes.ICONST_0);
                delegate.visitInsn(Opcodes.ICONST_0);
                delegate.visitVarInsn(Opcodes.ALOAD, 1);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_VIEWPORT, "width", "I");
                delegate.visitVarInsn(Opcodes.ALOAD, 1);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_VIEWPORT, "height", "I");
                delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL45C", "glViewport", "(IIII)V", false);

                delegate.visitLabel(renderDepth);
                delegate.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                delegate.visitInsn(Opcodes.ICONST_0);
                delegate.visitInsn(Opcodes.ICONST_0);
                delegate.visitInsn(Opcodes.ICONST_0);
                delegate.visitInsn(Opcodes.ICONST_0);
                delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL45C", "glColorMask", "(ZZZZ)V", false);
                delegate.visitVarInsn(Opcodes.ALOAD, 0);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_IRIS_RENDER_PIPELINE, "depthBlit", "Lme/cortex/voxy/client/core/rendering/post/FullscreenBlit;");
                delegate.visitVarInsn(Opcodes.ALOAD, 0);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_IRIS_RENDER_PIPELINE, "fbTranslucent", "Lme/cortex/voxy/client/core/rendering/util/DepthFramebuffer;");
                delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "me/cortex/voxy/client/core/rendering/util/DepthFramebuffer", "getDepthTex", "()Lme/cortex/voxy/client/core/gl/GlTexture;", false);
                delegate.visitFieldInsn(Opcodes.GETFIELD, "me/cortex/voxy/client/core/gl/GlTexture", "id", "I");
                delegate.visitVarInsn(Opcodes.ILOAD, 2);
                delegate.visitVarInsn(Opcodes.ALOAD, 1);
                delegate.visitTypeInsn(Opcodes.NEW, "org/joml/Matrix4f");
                delegate.visitInsn(Opcodes.DUP);
                delegate.visitVarInsn(Opcodes.ALOAD, 1);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_VIEWPORT, "vanillaProjection", "Lorg/joml/Matrix4f;");
                delegate.visitMethodInsn(Opcodes.INVOKESPECIAL, "org/joml/Matrix4f", "<init>", "(Lorg/joml/Matrix4fc;)V", false);
                delegate.visitVarInsn(Opcodes.ALOAD, 1);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_VIEWPORT, "modelView", "Lorg/joml/Matrix4f;");
                delegate.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/joml/Matrix4f", "mul", "(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;", false);
                delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "me/cortex/voxy/client/core/AbstractRenderPipeline", "transformBlitDepth", "(Lme/cortex/voxy/client/core/rendering/post/FullscreenBlit;IIL" + VOXY_VIEWPORT + ";Lorg/joml/Matrix4f;)V", false);

                delegate.visitVarInsn(Opcodes.ILOAD, 3);
                delegate.visitVarInsn(Opcodes.ALOAD, 1);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_VIEWPORT, "width", "I");
                delegate.visitJumpInsn(Opcodes.IF_ICMPNE, depthRendered);
                delegate.visitVarInsn(Opcodes.ILOAD, 4);
                delegate.visitVarInsn(Opcodes.ALOAD, 1);
                delegate.visitFieldInsn(Opcodes.GETFIELD, VOXY_VIEWPORT, "height", "I");
                delegate.visitJumpInsn(Opcodes.IF_ICMPEQ, done);
                delegate.visitLabel(depthRendered);
                delegate.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                delegate.visitInsn(Opcodes.ICONST_0);
                delegate.visitInsn(Opcodes.ICONST_0);
                delegate.visitVarInsn(Opcodes.ILOAD, 3);
                delegate.visitVarInsn(Opcodes.ILOAD, 4);
                delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL45C", "glViewport", "(IIII)V", false);
                delegate.visitJumpInsn(Opcodes.GOTO, done);

                delegate.visitLabel(disableStencil);
                delegate.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                delegate.visitIntInsn(Opcodes.SIPUSH, 2960);
                delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL45C", "glDisable", "(I)V", false);
                delegate.visitIntInsn(Opcodes.SIPUSH, 2929);
                delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL45C", "glDisable", "(I)V", false);

                delegate.visitLabel(done);
                delegate.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                delegate.visitInsn(Opcodes.ICONST_1);
                delegate.visitInsn(Opcodes.ICONST_1);
                delegate.visitInsn(Opcodes.ICONST_1);
                delegate.visitInsn(Opcodes.ICONST_1);
                delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL45C", "glColorMask", "(ZZZZ)V", false);
                delegate.visitInsn(Opcodes.RETURN);
                delegate.visitMaxs(7, 5);
                delegate.visitEnd();
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyConfigDefaults(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_CONFIG)) return input;

        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("<init>") || !descriptor.equals("()V")) return delegate;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (opcode == Opcodes.PUTFIELD
                                && owner.equals(VOXY_CONFIG)
                                && name.equals("useEnvironmentalFog")
                                && descriptor.equals("Z")) {
                            super.visitInsn(Opcodes.POP);
                            super.visitInsn(Opcodes.ICONST_0);
                        }
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyConfigMenu(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_CONFIG_MENU)) return input;

        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("lambda$registerConfigLate$26")
                        || !descriptor.endsWith(")Z")) return delegate;
                delegate.visitCode();
                delegate.visitInsn(Opcodes.ICONST_1);
                delegate.visitInsn(Opcodes.IRETURN);
                delegate.visitMaxs(1, 1);
                delegate.visitEnd();
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyClientWorldPath(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_CLIENT_INSTANCE)) return input;

        ClassWriter writer = new ClassWriter(reader, 0);
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
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals("net/minecraft/client/server/IntegratedServer")
                                && name.equals("a")
                                && descriptor.equals("(Lnet/minecraft/world/level/storage/LevelResource;)Ljava/nio/file/Path;")) {
                            name = "getWorldPath";
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyGsonCompatibility(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_IRIS_SHADER_PATCH)) return input;

        ClassWriter writer = new ClassWriter(reader, 0);
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
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private boolean skippedLenientStrictness;

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (opcode == Opcodes.GETSTATIC
                                && owner.equals(GSON_STRICTNESS)
                                && name.equals("LENIENT")
                                && descriptor.equals("L" + GSON_STRICTNESS + ";")) {
                            skippedLenientStrictness = true;
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, name, descriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (skippedLenientStrictness
                                && opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(GSON_BUILDER)
                                && name.equals("setStrictness")
                                && descriptor.equals(
                                "(L" + GSON_STRICTNESS + ";)L" + GSON_BUILDER + ";")) {
                            skippedLenientStrictness = false;
                            super.visitMethodInsn(
                                    Opcodes.INVOKEVIRTUAL,
                                    GSON_BUILDER,
                                    "setLenient",
                                    "()L" + GSON_BUILDER + ";",
                                    false
                            );
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyFogParameters(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName.equals(SODIUM_FOG_PARAMETERS_NEW)) {
                    return SODIUM_FOG_PARAMETERS;
                }
                return internalName;
            }
        }), 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyChunkSectionLayer(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName.equals(CHUNK_SECTION_LAYER_NEW)) {
                    return RENDER_TYPE;
                }
                return internalName;
            }
        }), 0);
        return writer.toByteArray();
    }

    private static byte[] patchMinecraftVersionBridges(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, 0);
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
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (opcode == Opcodes.GETFIELD
                                && owner.equals(TEXTURE_ATLAS)
                                && descriptor.equals("I")
                                && (name.equals("field_64244") || name.equals("maxSupportedTextureSize"))) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    TEXTURE_ATLAS_COMPAT,
                                    "getMaxSupportedTextureSize",
                                    "(Ljava/lang/Object;)I",
                                    false
                            );
                        } else if (opcode == Opcodes.GETSTATIC
                                && owner.equals(RENDER_TYPE)
                                && descriptor.equals("L" + RENDER_TYPE + ";")) {
                            String mappedName = switch (name) {
                                case "field_60923" -> "SOLID";
                                case "field_60925" -> "CUTOUT";
                                case "field_60926" -> "TRANSLUCENT";
                                case "field_60927" -> "TRIPWIRE";
                                default -> name;
                            };
                            super.visitFieldInsn(opcode, owner, mappedName, descriptor);
                        } else {
                            super.visitFieldInsn(opcode, owner, name, descriptor);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (owner.equals(GL_STATE_MANAGER_NEW)) {
                            super.visitMethodInsn(opcode, GL_STATE_MANAGER_1_21_1, name, descriptor, false);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(MINECRAFT)
                                && name.equals("method_61966")
                                && descriptor.equals("()Lnet/minecraft/client/DeltaTracker;")) {
                            super.visitMethodInsn(opcode, owner, "getTimer", descriptor, false);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(GAME_RENDERER)
                                && name.equals("getFov")
                                && descriptor.equals("(Lnet/minecraft/client/Camera;FZ)F")) {
                            super.visitMethodInsn(opcode, owner, "roxy$getFov", descriptor, false);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(GAME_RENDERER)
                                && name.equals("getProjectionMatrix")
                                && descriptor.equals("(F)Lorg/joml/Matrix4f;")) {
                            super.visitMethodInsn(opcode, owner, "roxy$getProjectionMatrix", descriptor, false);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(DIRECTION)
                                && name.equals("method_62675")
                                && descriptor.equals("()Lnet/minecraft/core/Vec3i;")) {
                            super.visitMethodInsn(opcode, owner, "getNormal", descriptor, false);
                        } else if (opcode == Opcodes.INVOKESTATIC
                                && owner.equals(COLOR_HELPER_NEW)
                                && name.equals("method_75599")
                                && descriptor.equals("(F)I")) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    COLOR_COMPAT,
                                    "linearToSrgbChannel",
                                    "(F)I",
                                    false
                            );
                        } else if (opcode == Opcodes.INVOKESTATIC
                                && owner.equals(ITEM_BLOCK_RENDER_TYPES)
                                && descriptor.equals(
                                "(Lnet/minecraft/world/level/material/FluidState;)L" + RENDER_TYPE + ";")) {
                            super.visitMethodInsn(opcode, owner, "getRenderLayer", descriptor, false);
                        } else if (opcode == Opcodes.INVOKESTATIC
                                && owner.equals(ITEM_BLOCK_RENDER_TYPES)
                                && descriptor.equals("(L" + BLOCK_STATE + ";)L" + RENDER_TYPE + ";")) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    RENDER_TYPE_COMPAT,
                                    "getChunkRenderType",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                    false
                            );
                            super.visitTypeInsn(Opcodes.CHECKCAST, RENDER_TYPE);
                        } else if (opcode == Opcodes.INVOKEINTERFACE
                                && owner.equals("net/minecraft/core/Registry")
                                && name.equals("get")
                                && descriptor.equals(
                                "(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/Optional;")) {
                            super.visitMethodInsn(opcode, owner, "getHolder", descriptor, true);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(BAKED_QUAD)
                                && name.equals("comp_3725")
                                && descriptor.equals("()Z")) {
                            super.visitMethodInsn(opcode, owner, "isShade", descriptor, false);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(BAKED_QUAD)
                                && name.equals("comp_3724")
                                && descriptor.equals("()L" + TEXTURE_ATLAS_SPRITE + ";")) {
                            super.visitMethodInsn(opcode, owner, "getSprite", descriptor, false);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(BLOCK_STATE)
                                && name.equals("getLightBlock")
                                && descriptor.equals("()I")) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    BLOCK_STATE_COMPAT,
                                    "getLightBlock",
                                    "(Ljava/lang/Object;)I",
                                    false
                            );
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(COMPOUND_TAG)
                                && descriptor.equals("(Ljava/lang/String;I)I")) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    COMPOUND_TAG_COMPAT,
                                    "getInt",
                                    "(Ljava/lang/Object;Ljava/lang/String;I)I",
                                    false
                            );
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(COMPOUND_TAG)
                                && descriptor.equals("(Ljava/lang/String;)Ljava/util/Optional;")) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    COMPOUND_TAG_COMPAT,
                                    "getCompound",
                                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/util/Optional;",
                                    false
                            );
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(COMPOUND_TAG)
                                && descriptor.equals("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    COMPOUND_TAG_COMPAT,
                                    "getString",
                                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                                    false
                            );
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(POSE_STACK)
                                && name.equals("mulPose")
                                && descriptor.equals("(" + QUATERNION_FC + ")V")) {
                            super.visitMethodInsn(opcode, owner, name, "(" + QUATERNION_F + ")V", isInterface);
                        } else if (opcode == Opcodes.INVOKEVIRTUAL
                                && owner.equals(POSE_STACK)
                                && name.equals("mulPose")
                                && descriptor.equals("(" + MATRIX4FC + ")V")) {
                            super.visitMethodInsn(opcode, owner, name, "(" + MATRIX4F + ")V", isInterface);
                        } else {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyTextureSetup(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_TEXTURE_BAKERY)) return input;

        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (!name.equals("setupTexture") || !descriptor.equals("()V")) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, VOXY_TEXTURE_BAKERY, "rasterizer", VOXY_RASTERIZER);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        TEXTURE_COMPAT,
                        "setupBlockAtlas",
                        "(Ljava/lang/Object;)V",
                        false
                );
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(1, 1);
                method.visitEnd();
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyLightMapHelper(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_LIGHT_MAP_HELPER)) return input;

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (!name.equals("getLightmapTextureId") || !descriptor.equals("()I")) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                method.visitCode();
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        LIGHT_MAP_COMPAT,
                        "getLightmapTextureId",
                        "()I",
                        false
                );
                method.visitInsn(Opcodes.IRETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyMetaFromLayer(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_TEXTURE_BAKERY)) return input;

        String descriptor = "(L" + RENDER_TYPE + ";)I";
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String methodDescriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (!name.equals("getMetaFromLayer") || !methodDescriptor.equals(descriptor)) {
                    return super.visitMethod(access, name, methodDescriptor, signature, exceptions);
                }

                MethodVisitor method = super.visitMethod(access, name, methodDescriptor, signature, exceptions);
                Label hasDiscard = new Label();
                Label returnMeta = new Label();
                method.visitCode();
                addLayerCheck(method, "cutout", hasDiscard);
                addLayerCheck(method, "cutoutMipped", hasDiscard);
                addLayerCheck(method, "translucent", hasDiscard);
                addLayerCheck(method, "tripwire", hasDiscard);
                method.visitInsn(Opcodes.ICONST_0);
                method.visitJumpInsn(Opcodes.GOTO, returnMeta);
                method.visitLabel(hasDiscard);
                method.visitInsn(Opcodes.ICONST_1);
                method.visitLabel(returnMeta);
                method.visitInsn(Opcodes.ICONST_2);
                method.visitInsn(Opcodes.IOR);
                method.visitInsn(Opcodes.IRETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                return null;
            }

            private void addLayerCheck(MethodVisitor method, String factory, Label target) {
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        RENDER_TYPE,
                        factory,
                        "()L" + RENDER_TYPE + ";",
                        false
                );
                method.visitJumpInsn(Opcodes.IF_ACMPEQ, target);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyIrisPipeline(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_IRIS_PIPELINE_MIXIN)) return input;

        ClassWriter writer = new ClassWriter(reader, 0);
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
                if (!name.equals("voxy$injectViewportSetup")) return delegate;

                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                        AnnotationVisitor inject = super.visitAnnotation(annotationDescriptor, visible);
                        if (!INJECT.equals(annotationDescriptor)) return inject;

                        return new AnnotationVisitor(Opcodes.ASM9, inject) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                AnnotationVisitor at = super.visitAnnotation(name, descriptor);
                                if (!name.equals("at") || !AT.equals(descriptor)) return at;
                                return patchIrisActiveTextureTarget(at);
                            }

                            @Override
                            public AnnotationVisitor visitArray(String name) {
                                AnnotationVisitor atArray = super.visitArray(name);
                                if (!name.equals("at")) return atArray;

                                return new AnnotationVisitor(Opcodes.ASM9, atArray) {
                                    @Override
                                    public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
                                        AnnotationVisitor at = super.visitAnnotation(ignored, descriptor);
                                        if (!AT.equals(descriptor)) return at;
                                        return patchIrisActiveTextureTarget(at);
                                    }
                                };
                            }
                        };
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyIrisSamplers(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_IRIS_SAMPLERS)) return input;

        String newerDescriptor =
                "(Lnet/irisshaders/iris/gl/texture/TextureType;"
                        + "Ljava/util/function/IntSupplier;"
                        + "Ljava/util/function/Supplier;"
                        + "[Ljava/lang/String;)Z";
        ClassWriter writer = new ClassWriter(reader, 0);
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
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName, String fieldDescriptor) {
                        if (opcode == Opcodes.GETSTATIC
                                && owner.equals(IRIS_GL_SAMPLER)
                                && fieldName.equals("MIPPED_NEAREST_NEAREST")
                                && fieldDescriptor.equals("L" + IRIS_GL_SAMPLER + ";")) {
                            super.visitFieldInsn(opcode, owner, "MIPPED_NEAREST", fieldDescriptor);
                            return;
                        }
                        super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEINTERFACE
                                && owner.equals(IRIS_SAMPLER_HOLDER)
                                && methodName.equals("addDynamicSampler")
                                && methodDescriptor.equals(newerDescriptor)) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    IRIS_SAMPLER_COMPAT,
                                    "addDynamicSampler",
                                    "(Ljava/lang/Object;Ljava/lang/Object;"
                                            + "Ljava/util/function/IntSupplier;"
                                            + "Ljava/util/function/Supplier;"
                                            + "[Ljava/lang/String;)Z",
                                    false
                            );
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyIrisSamplerHolder(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_IRIS_SAMPLER_HOLDER)) return input;

        String textureType = "Lnet/irisshaders/iris/gl/texture/TextureType;";
        String valueUpdateNotifier = "Lnet/irisshaders/iris/gl/state/ValueUpdateNotifier;";
        String glSampler = "Lnet/irisshaders/iris/gl/sampler/GlSampler;";
        String intSupplier = "Ljava/util/function/IntSupplier;";
        String supplier = "Ljava/util/function/Supplier;";
        String names = "[Ljava/lang/String;";
        String newDynamic = "(" + textureType + intSupplier + valueUpdateNotifier + supplier + names + ")Z";
        String oldDynamic = "(" + textureType + intSupplier + glSampler + names + ")Z";
        String oldDynamicWithNotifier = "(" + textureType + intSupplier + valueUpdateNotifier + glSampler + names + ")Z";
        String newDefault = "(" + textureType + intSupplier + valueUpdateNotifier + supplier + names + ")Z";
        String oldDefault = "(" + textureType + intSupplier + valueUpdateNotifier + glSampler + names + ")Z";

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private final Set<String> methods = new HashSet<>();

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                methods.add(name + descriptor);
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                if (!methods.contains("addDynamicSampler" + oldDynamic)) {
                    addDynamicBridge(oldDynamic, false);
                }
                if (!methods.contains("addDynamicSampler" + oldDynamicWithNotifier)) {
                    addDynamicBridge(oldDynamicWithNotifier, true);
                }
                if (!methods.contains("addDefaultSampler" + oldDefault)) {
                    addDefaultBridge(oldDefault, newDefault);
                }
                super.visitEnd();
            }

            private void addDynamicBridge(String descriptor, boolean withNotifier) {
                MethodVisitor method = super.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "addDynamicSampler",
                        descriptor,
                        null,
                        null
                );
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ALOAD, 2);
                if (withNotifier) {
                    method.visitVarInsn(Opcodes.ALOAD, 3);
                    method.visitVarInsn(Opcodes.ALOAD, 4);
                    method.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            IRIS_SAMPLER_COMPAT,
                            "samplerSupplier",
                            "(Ljava/lang/Object;)Ljava/util/function/Supplier;",
                            false
                    );
                    method.visitVarInsn(Opcodes.ALOAD, 5);
                } else {
                    method.visitInsn(Opcodes.ACONST_NULL);
                    method.visitVarInsn(Opcodes.ALOAD, 3);
                    method.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            IRIS_SAMPLER_COMPAT,
                            "samplerSupplier",
                            "(Ljava/lang/Object;)Ljava/util/function/Supplier;",
                            false
                    );
                    method.visitVarInsn(Opcodes.ALOAD, 4);
                }
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_IRIS_SAMPLER_HOLDER,
                        "addDynamicSampler",
                        newDynamic,
                        false
                );
                method.visitInsn(Opcodes.IRETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
            }

            private void addDefaultBridge(String descriptor, String targetDescriptor) {
                MethodVisitor method = super.visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "addDefaultSampler",
                        descriptor,
                        null,
                        null
                );
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ALOAD, 2);
                method.visitVarInsn(Opcodes.ALOAD, 3);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        IRIS_SAMPLER_COMPAT,
                        "samplerSupplier",
                        "(Ljava/lang/Object;)Ljava/util/function/Supplier;",
                        false
                );
                method.visitVarInsn(Opcodes.ALOAD, 5);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_IRIS_SAMPLER_HOLDER,
                        "addDefaultSampler",
                        targetDescriptor,
                        false
                );
                method.visitInsn(Opcodes.IRETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static AnnotationVisitor patchIrisActiveTextureTarget(AnnotationVisitor delegate) {
        return new AnnotationVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visit(String name, Object value) {
                if (name.equals("target")
                        && value instanceof String target
                        && target.equals("Lcom/mojang/blaze3d/opengl/GlStateManager;_activeTexture(I)V")) {
                    super.visit(name, "Lcom/mojang/blaze3d/systems/RenderSystem;activeTexture(I)V");
                } else {
                    super.visit(name, value);
                }
            }
        };
    }

    private static byte[] patchVoxyBakedModel(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_TEXTURE_BAKERY)) return input;

        String descriptor = "(L" + BLOCK_STATE + ";L" + RENDER_TYPE + ";)V";
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String methodDescriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (!name.equals("bakeBlockModel") || !methodDescriptor.equals(descriptor)) {
                    return super.visitMethod(access, name, methodDescriptor, signature, exceptions);
                }

                MethodVisitor method = super.visitMethod(access, name, methodDescriptor, signature, exceptions);
                addCompatibleBakeBlockModel(method);
                return null;
            }

            private void addCompatibleBakeBlockModel(MethodVisitor method) {
                String vertexConsumer = "L" + VOXY_VERTEX_CONSUMER + ";";
                String quadList = "Ljava/util/List;";
                String quad = "L" + BAKED_QUAD + ";";
                Label visible = new Label();
                Label faceLoop = new Label();
                Label faceDone = new Label();
                Label quadLoop = new Label();
                Label quadDone = new Label();
                Label noShade = new Label();
                Label shadeValue = new Label();

                method.visitCode();

                // Preserve Voxy's early-out for invisible block states.
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        BLOCK_STATE,
                        "getRenderShape",
                        "()L" + RENDER_SHAPE + ";",
                        false
                );
                method.visitFieldInsn(
                        Opcodes.GETSTATIC,
                        RENDER_SHAPE,
                        "INVISIBLE",
                        "L" + RENDER_SHAPE + ";"
                );
                method.visitJumpInsn(Opcodes.IF_ACMPNE, visible);
                method.visitInsn(Opcodes.RETURN);

                method.visitLabel(visible);

                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        MINECRAFT,
                        "getInstance",
                        "()L" + MINECRAFT + ";",
                        false
                );
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        MINECRAFT,
                        "getBlockRenderer",
                        "()Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;",
                        false
                );
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "net/minecraft/client/renderer/block/BlockRenderDispatcher",
                        "getBlockModel",
                        "(L" + BLOCK_STATE + ";)L" + BAKED_MODEL + ";",
                        false
                );
                method.visitVarInsn(Opcodes.ASTORE, 3);

                method.visitVarInsn(Opcodes.ALOAD, 2);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        VOXY_TEXTURE_BAKERY,
                        "getMetaFromLayer",
                        "(L" + RENDER_TYPE + ";)I",
                        false
                );
                method.visitVarInsn(Opcodes.ISTORE, 4);

                method.visitLdcInsn(42L);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        RANDOM_SOURCE,
                        "create",
                        "(J)L" + RANDOM_SOURCE + ";",
                        true
                );
                method.visitVarInsn(Opcodes.ASTORE, 5);

                // Direction[] faces = {DOWN, UP, NORTH, SOUTH, WEST, EAST, null};
                method.visitIntInsn(Opcodes.BIPUSH, 7);
                method.visitTypeInsn(Opcodes.ANEWARRAY, DIRECTION);
                method.visitVarInsn(Opcodes.ASTORE, 6);
                String[] directions = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
                for (int index = 0; index < directions.length; index++) {
                    method.visitVarInsn(Opcodes.ALOAD, 6);
                    method.visitIntInsn(Opcodes.BIPUSH, index);
                    method.visitFieldInsn(
                            Opcodes.GETSTATIC,
                            DIRECTION,
                            directions[index],
                            "L" + DIRECTION + ";"
                    );
                    method.visitInsn(Opcodes.AASTORE);
                }

                method.visitInsn(Opcodes.ICONST_0);
                method.visitVarInsn(Opcodes.ISTORE, 7);
                method.visitLabel(faceLoop);
                method.visitVarInsn(Opcodes.ILOAD, 7);
                method.visitIntInsn(Opcodes.BIPUSH, 7);
                method.visitJumpInsn(Opcodes.IF_ICMPGE, faceDone);

                method.visitVarInsn(Opcodes.ALOAD, 3);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ALOAD, 6);
                method.visitVarInsn(Opcodes.ILOAD, 7);
                method.visitInsn(Opcodes.AALOAD);
                method.visitVarInsn(Opcodes.ALOAD, 5);
                method.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        BAKED_MODEL,
                        "getQuads",
                        "(L" + BLOCK_STATE + ";L" + DIRECTION + ";L" + RANDOM_SOURCE + ";)" + quadList,
                        true
                );
                method.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/List",
                        "iterator",
                        "()Ljava/util/Iterator;",
                        true
                );
                method.visitVarInsn(Opcodes.ASTORE, 8);

                method.visitLabel(quadLoop);
                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/Iterator",
                        "hasNext",
                        "()Z",
                        true
                );
                method.visitJumpInsn(Opcodes.IFEQ, quadDone);
                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        "java/util/Iterator",
                        "next",
                        "()Ljava/lang/Object;",
                        true
                );
                method.visitTypeInsn(Opcodes.CHECKCAST, BAKED_QUAD);
                method.visitVarInsn(Opcodes.ASTORE, 9);

                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, VOXY_TEXTURE_BAKERY, "vc", vertexConsumer);
                method.visitVarInsn(Opcodes.ALOAD, 9);
                method.visitVarInsn(Opcodes.ILOAD, 4);
                method.visitVarInsn(Opcodes.ALOAD, 9);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        BAKED_QUAD,
                        "isTinted",
                        "()Z",
                        false
                );
                method.visitJumpInsn(Opcodes.IFEQ, noShade);
                method.visitInsn(Opcodes.ICONST_4);
                method.visitJumpInsn(Opcodes.GOTO, shadeValue);
                method.visitLabel(noShade);
                method.visitInsn(Opcodes.ICONST_0);
                method.visitLabel(shadeValue);
                method.visitInsn(Opcodes.IOR);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_VERTEX_CONSUMER,
                        "quad",
                        "(L" + BAKED_QUAD + ";I)" + vertexConsumer,
                        false
                );
                method.visitInsn(Opcodes.POP);
                method.visitJumpInsn(Opcodes.GOTO, quadLoop);

                method.visitLabel(quadDone);
                method.visitIincInsn(7, 1);
                method.visitJumpInsn(Opcodes.GOTO, faceLoop);

                method.visitLabel(faceDone);
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyVertexConsumer(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_VERTEX_CONSUMER)) return input;

        String descriptor = "(L" + BAKED_QUAD + ";I)L" + VOXY_VERTEX_CONSUMER + ";";
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String methodDescriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (!name.equals("quad") || !methodDescriptor.equals(descriptor)) {
                    return super.visitMethod(access, name, methodDescriptor, signature, exceptions);
                }

                MethodVisitor method = super.visitMethod(access, name, methodDescriptor, signature, exceptions);
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ILOAD, 2);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        VOXY_VERTEX_CONSUMER_COMPAT,
                        "writeQuad",
                        "(Ljava/lang/Object;Ljava/lang/Object;I)Ljava/lang/Object;",
                        false
                );
                method.visitTypeInsn(Opcodes.CHECKCAST, VOXY_VERTEX_CONSUMER);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                return null;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyRenderSystemViewport(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_RENDER_SYSTEM)) return input;

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                MethodVisitor method = visitMethod(
                        Opcodes.ACC_PUBLIC,
                        "setupViewport",
                        VOXY_SETUP_VIEWPORT_1_21_1,
                        null,
                        null
                );
                addCompatibleSetupViewport(method);
                super.visitEnd();
            }

            private void addCompatibleSetupViewport(MethodVisitor method) {
                String viewportDescriptor = "()L" + VOXY_VIEWPORT + ";";
                String matrixDescriptor = "()Lorg/joml/Matrix4fc;";
                String rawViewport = "L" + VOXY_VIEWPORT + ";";
                Label viewportReady = new Label();
                Label noFrameAdvance = new Label();

                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VOXY_RENDER_SYSTEM, "getViewport", viewportDescriptor, false);
                method.visitVarInsn(Opcodes.ASTORE, 8);
                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitJumpInsn(Opcodes.IFNONNULL, viewportReady);
                method.visitInsn(Opcodes.ACONST_NULL);
                method.visitInsn(Opcodes.ARETURN);

                method.visitLabel(viewportReady);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(
                        Opcodes.GETFIELD,
                        VOXY_RENDER_SYSTEM,
                        "properties",
                        "L" + VOXY_RENDER_PROPERTIES + ";"
                );
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        SODIUM_CHUNK_RENDER_MATRICES,
                        "projection",
                        matrixDescriptor,
                        false
                );
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        VOXY_RENDER_SYSTEM,
                        "computeProjectionMat",
                        "(L" + VOXY_RENDER_PROPERTIES + ";Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;",
                        false
                );
                method.visitVarInsn(Opcodes.ASTORE, 9);

                method.visitInsn(Opcodes.ICONST_4);
                method.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
                method.visitVarInsn(Opcodes.ASTORE, 10);
                method.visitIntInsn(Opcodes.SIPUSH, 2978); // GL_VIEWPORT
                method.visitVarInsn(Opcodes.ALOAD, 10);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "org/lwjgl/opengl/GL11",
                        "glGetIntegerv",
                        "(I[I)V",
                        false
                );
                method.visitVarInsn(Opcodes.ALOAD, 10);
                method.visitInsn(Opcodes.ICONST_2);
                method.visitInsn(Opcodes.IALOAD);
                method.visitVarInsn(Opcodes.ISTORE, 11);
                method.visitVarInsn(Opcodes.ALOAD, 10);
                method.visitInsn(Opcodes.ICONST_3);
                method.visitInsn(Opcodes.IALOAD);
                method.visitVarInsn(Opcodes.ISTORE, 12);

                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(
                        Opcodes.GETFIELD,
                        VOXY_RENDER_SYSTEM,
                        "pipeline",
                        "Lme/cortex/voxy/client/core/AbstractRenderPipeline;"
                );
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "me/cortex/voxy/client/core/AbstractRenderPipeline",
                        "getRenderScalingFactor",
                        "()[F",
                        false
                );
                method.visitVarInsn(Opcodes.ASTORE, 13);
                Label noScaling = new Label();
                method.visitVarInsn(Opcodes.ALOAD, 13);
                method.visitJumpInsn(Opcodes.IFNULL, noScaling);
                method.visitVarInsn(Opcodes.ILOAD, 11);
                method.visitInsn(Opcodes.I2F);
                method.visitVarInsn(Opcodes.ALOAD, 13);
                method.visitInsn(Opcodes.ICONST_0);
                method.visitInsn(Opcodes.FALOAD);
                method.visitInsn(Opcodes.FMUL);
                method.visitInsn(Opcodes.F2I);
                method.visitVarInsn(Opcodes.ISTORE, 11);
                method.visitVarInsn(Opcodes.ILOAD, 12);
                method.visitInsn(Opcodes.I2F);
                method.visitVarInsn(Opcodes.ALOAD, 13);
                method.visitInsn(Opcodes.ICONST_1);
                method.visitInsn(Opcodes.FALOAD);
                method.visitInsn(Opcodes.FMUL);
                method.visitInsn(Opcodes.F2I);
                method.visitVarInsn(Opcodes.ISTORE, 12);
                method.visitLabel(noScaling);

                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        SODIUM_CHUNK_RENDER_MATRICES,
                        "projection",
                        matrixDescriptor,
                        false
                );
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_VIEWPORT,
                        "setVanillaProjection",
                        "(Lorg/joml/Matrix4fc;)L" + VOXY_VIEWPORT + ";",
                        false
                );
                method.visitVarInsn(Opcodes.ALOAD, 9);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_VIEWPORT,
                        "setProjection",
                        "(Lorg/joml/Matrix4f;)L" + VOXY_VIEWPORT + ";",
                        false
                );
                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitTypeInsn(Opcodes.NEW, "org/joml/Matrix4f");
                method.visitInsn(Opcodes.DUP);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        SODIUM_CHUNK_RENDER_MATRICES,
                        "modelView",
                        matrixDescriptor,
                        false
                );
                method.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        "org/joml/Matrix4f",
                        "<init>",
                        "(Lorg/joml/Matrix4fc;)V",
                        false
                );
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_VIEWPORT,
                        "setModelView",
                        "(Lorg/joml/Matrix4fc;)L" + VOXY_VIEWPORT + ";",
                        false
                );
                method.visitVarInsn(Opcodes.DLOAD, 2);
                method.visitVarInsn(Opcodes.DLOAD, 4);
                method.visitVarInsn(Opcodes.DLOAD, 6);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_VIEWPORT,
                        "setCamera",
                        "(DDD)L" + VOXY_VIEWPORT + ";",
                        false
                );
                method.visitVarInsn(Opcodes.ILOAD, 11);
                method.visitVarInsn(Opcodes.ILOAD, 12);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_VIEWPORT,
                        "setScreenSize",
                        "(II)L" + VOXY_VIEWPORT + ";",
                        false
                );
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        SODIUM_FOG_PARAMETERS,
                        "current",
                        "()L" + SODIUM_FOG_PARAMETERS + ";",
                        false
                );
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_VIEWPORT,
                        "setFogParameters",
                        "(L" + SODIUM_FOG_PARAMETERS + ";)L" + VOXY_VIEWPORT + ";",
                        false
                );
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_VIEWPORT,
                        "update",
                        viewportDescriptor,
                        false
                );
                method.visitInsn(Opcodes.POP);

                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "me/cortex/voxy/client/VoxyClient",
                        "getOcclusionDebugState",
                        "()I",
                        false
                );
                method.visitJumpInsn(Opcodes.IFNE, noFrameAdvance);
                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitInsn(Opcodes.DUP);
                method.visitFieldInsn(Opcodes.GETFIELD, VOXY_VIEWPORT, "frameId", "I");
                method.visitInsn(Opcodes.ICONST_1);
                method.visitInsn(Opcodes.IADD);
                method.visitFieldInsn(Opcodes.PUTFIELD, VOXY_VIEWPORT, "frameId", "I");
                method.visitLabel(noFrameAdvance);
                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(8, 14);
                method.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyDefaultChunkRenderer(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_DEFAULT_CHUNK_RENDERER)) return input;

        String matrices = "L" + SODIUM_CHUNK_RENDER_MATRICES + ";";
        String commandList = "Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;";
        String renderLists = "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/ChunkRenderListIterable;";
        String renderPass = "L" + SODIUM_TERRAIN_RENDER_PASS + ";";
        String camera = "L" + SODIUM_CAMERA_TRANSFORM + ";";
        String callbackInfo = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
        String handlerDescriptor = "(" + matrices + commandList + renderLists + renderPass + camera + "Z" + callbackInfo + ")V";
        String doRenderDescriptor = "(" + matrices + renderPass + camera + ")V";

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (name.equals("voxy$cancelThingie") && descriptor.contains("FogParameters")) {
                    MethodVisitor method = super.visitMethod(access, name, handlerDescriptor, null, exceptions);
                    addCancelHandler(method, access);
                    return null;
                }
                if (name.equals("voxy$injectRender") && descriptor.contains("FogParameters")) {
                    MethodVisitor method = super.visitMethod(access, name, handlerDescriptor, null, exceptions);
                    addRenderHandler(method);
                    return null;
                }
                if (name.equals("doRender") && descriptor.contains("FogParameters")) {
                    MethodVisitor method = super.visitMethod(access, name, doRenderDescriptor, null, exceptions);
                    addDoRender(method);
                    return null;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            private void addCancelHandler(MethodVisitor method, int access) {
                addHeadInjectAnnotation(method, true);
                Label done = new Label();
                method.visitCode();
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "me/cortex/voxy/client/VoxyClient",
                        "disableSodiumChunkRender",
                        "()Z",
                        false
                );
                method.visitJumpInsn(Opcodes.IFEQ, done);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        "net/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer",
                        "begin",
                        "(" + renderPass + ")V",
                        false
                );
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitVarInsn(Opcodes.ALOAD, 5);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VOXY_DEFAULT_CHUNK_RENDERER, "doRender", doRenderDescriptor, false);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        "net/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer",
                        "end",
                        "(" + renderPass + ")V",
                        false
                );
                method.visitVarInsn(Opcodes.ALOAD, 7);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "org/spongepowered/asm/mixin/injection/callback/CallbackInfo",
                        "cancel",
                        "()V",
                        false
                );
                method.visitLabel(done);
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(5, 8);
                method.visitEnd();
            }

            private void addRenderHandler(MethodVisitor method) {
                addInvokeEndInjectAnnotation(method);
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitVarInsn(Opcodes.ALOAD, 5);
                method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, VOXY_DEFAULT_CHUNK_RENDERER, "doRender", doRenderDescriptor, false);
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(4, 8);
                method.visitEnd();
            }

            private void addDoRender(MethodVisitor method) {
                method.visitAnnotation("Lorg/spongepowered/asm/mixin/Unique;", true).visitEnd();
                Label done = new Label();
                Label useCurrentViewport = new Label();
                Label render = new Label();
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 2);
                method.visitFieldInsn(
                        Opcodes.GETSTATIC,
                        "net/caffeinemc/mods/sodium/client/render/chunk/terrain/DefaultTerrainRenderPasses",
                        "CUTOUT",
                        renderPass
                );
                method.visitJumpInsn(Opcodes.IF_ACMPNE, done);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        MINECRAFT,
                        "getInstance",
                        "()L" + MINECRAFT + ";",
                        false
                );
                method.visitFieldInsn(
                        Opcodes.GETFIELD,
                        MINECRAFT,
                        "levelRenderer",
                        "Lnet/minecraft/client/renderer/LevelRenderer;"
                );
                method.visitTypeInsn(Opcodes.CHECKCAST, "me/cortex/voxy/client/core/IGetVoxyRenderSystem");
                method.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE,
                        "me/cortex/voxy/client/core/IGetVoxyRenderSystem",
                        "voxy$getRenderSystem",
                        "()L" + VOXY_RENDER_SYSTEM + ";",
                        true
                );
                method.visitVarInsn(Opcodes.ASTORE, 4);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitJumpInsn(Opcodes.IFNULL, done);
                method.visitInsn(Opcodes.ACONST_NULL);
                method.visitVarInsn(Opcodes.ASTORE, 5);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "net/rasanovum/roxy/compat/RoxyIrisViewportCompat",
                        "consumeCapturedViewport",
                        "()Z",
                        false
                );
                method.visitJumpInsn(Opcodes.IFEQ, useCurrentViewport);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_RENDER_SYSTEM,
                        "getViewport",
                        "()L" + VOXY_VIEWPORT + ";",
                        false
                );
                method.visitVarInsn(Opcodes.ASTORE, 5);
                method.visitJumpInsn(Opcodes.GOTO, render);
                method.visitLabel(useCurrentViewport);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ALOAD, 3);
                method.visitFieldInsn(Opcodes.GETFIELD, SODIUM_CAMERA_TRANSFORM, "x", "D");
                method.visitVarInsn(Opcodes.ALOAD, 3);
                method.visitFieldInsn(Opcodes.GETFIELD, SODIUM_CAMERA_TRANSFORM, "y", "D");
                method.visitVarInsn(Opcodes.ALOAD, 3);
                method.visitFieldInsn(Opcodes.GETFIELD, SODIUM_CAMERA_TRANSFORM, "z", "D");
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_RENDER_SYSTEM,
                        "setupViewport",
                        VOXY_SETUP_VIEWPORT_1_21_1,
                        false
                );
                method.visitVarInsn(Opcodes.ASTORE, 5);
                method.visitLabel(render);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "net/rasanovum/roxy/compat/RoxyFramebufferCompat",
                        "prepareVoxySource",
                        "()J",
                        false
                );
                method.visitVarInsn(Opcodes.LSTORE, 6);
                Label sourceReady = new Label();
                method.visitVarInsn(Opcodes.LLOAD, 6);
                method.visitInsn(Opcodes.L2I);
                method.visitJumpInsn(Opcodes.IFNE, sourceReady);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        MINECRAFT,
                        "getInstance",
                        "()L" + MINECRAFT + ";",
                        false
                );
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        MINECRAFT,
                        "getMainRenderTarget",
                        "()Lcom/mojang/blaze3d/pipeline/RenderTarget;",
                        false
                );
                method.visitInsn(Opcodes.ICONST_0);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        "com/mojang/blaze3d/pipeline/RenderTarget",
                        "bindWrite",
                        "(Z)V",
                        false
                );
                method.visitLabel(sourceReady);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "net/rasanovum/roxy/compat/RoxyFramebufferCompat",
                        "useMainColorAttachment",
                        "()V",
                        false
                );
                Label renderStart = new Label();
                Label renderEnd = new Label();
                Label renderFailed = new Label();
                Label renderDone = new Label();
                method.visitTryCatchBlock(renderStart, renderEnd, renderFailed, null);
                method.visitLabel(renderStart);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitVarInsn(Opcodes.ALOAD, 5);
                method.visitMethodInsn(
                        Opcodes.INVOKEVIRTUAL,
                        VOXY_RENDER_SYSTEM,
                        "renderOpaque",
                        "(L" + VOXY_VIEWPORT + ";)V",
                        false
                );
                method.visitLabel(renderEnd);
                method.visitVarInsn(Opcodes.LLOAD, 6);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "net/rasanovum/roxy/compat/RoxyFramebufferCompat",
                        "restoreFramebuffer",
                        "(J)V",
                        false
                );
                method.visitJumpInsn(Opcodes.GOTO, renderDone);
                method.visitLabel(renderFailed);
                method.visitVarInsn(Opcodes.ASTORE, 8);
                method.visitVarInsn(Opcodes.LLOAD, 6);
                method.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        "net/rasanovum/roxy/compat/RoxyFramebufferCompat",
                        "restoreFramebuffer",
                        "(J)V",
                        false
                );
                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitInsn(Opcodes.ATHROW);
                method.visitLabel(renderDone);
                method.visitLabel(done);
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(10, 9);
                method.visitEnd();
            }

            private void addHeadInjectAnnotation(MethodVisitor method, boolean cancellable) {
                AnnotationVisitor inject = method.visitAnnotation(INJECT, true);
                AnnotationVisitor methods = inject.visitArray("method");
                methods.visit(null, "render");
                methods.visitEnd();
                AnnotationVisitor at = inject.visitAnnotation("at", AT);
                at.visit("value", "HEAD");
                at.visitEnd();
                if (cancellable) inject.visit("cancellable", true);
                inject.visitEnd();
            }

            private void addInvokeEndInjectAnnotation(MethodVisitor method) {
                AnnotationVisitor inject = method.visitAnnotation(INJECT, true);
                AnnotationVisitor methods = inject.visitArray("method");
                methods.visit(null, "render");
                methods.visitEnd();
                AnnotationVisitor at = inject.visitAnnotation("at", AT);
                at.visit("value", "INVOKE");
                at.visit(
                        "target",
                        "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end"
                                + "(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V"
                );
                at.visitEnum("shift", "Lorg/spongepowered/asm/mixin/injection/At$Shift;", "BEFORE");
                at.visitEnd();
                inject.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyWorldCallback(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_WORLD_MIXIN)) return input;

        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private int callbackAccess;
            private boolean callbackFound;

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (!name.equals(VOXY_WORLD_CALLBACK)
                        || !descriptor.equals(VOXY_WORLD_CALLBACK_1_21_11)) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                callbackFound = true;
                callbackAccess = access;
                MethodVisitor original = super.visitMethod(
                        access,
                        VOXY_WORLD_CALLBACK_ORIGINAL,
                        descriptor,
                        signature,
                        exceptions
                );
                return new MethodVisitor(Opcodes.ASM9, original) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        // Strip the original Mixin annotation before adding the bridge annotation.
                        if (annotation.equals(INJECT)) return null;
                        return super.visitAnnotation(annotation, visible);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (callbackFound) {
                    addWorldCallbackBridge(this, callbackAccess);
                }
                super.visitEnd();
            }

            private void addWorldCallbackBridge(ClassVisitor visitor, int access) {
                MethodVisitor method = visitor.visitMethod(
                        access,
                        VOXY_WORLD_CALLBACK,
                        VOXY_WORLD_CALLBACK_1_21_1,
                        null,
                        null
                );
                AnnotationVisitor inject = method.visitAnnotation(INJECT, true);
                AnnotationVisitor methods = inject.visitArray("method");
                methods.visit(null, "<init>");
                methods.visitEnd();
                AnnotationVisitor at = inject.visitAnnotation("at", AT);
                at.visit("value", "RETURN");
                at.visitEnd();
                inject.visitEnd();

                boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                int firstArgument = isStatic ? 0 : 1;
                int properties = firstArgument;
                int key = properties + 1;
                int registryManager = key + 1;
                int dimensionEntry = registryManager + 1;
                int isClient = dimensionEntry + 2; // skip the Supplier
                int debugWorld = isClient + 1;
                int seed = debugWorld + 1;
                int maxChainedNeighborUpdates = seed + 2;
                int callbackInfo = maxChainedNeighborUpdates + 1;

                method.visitCode();
                if (!isStatic) method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, properties);
                method.visitVarInsn(Opcodes.ALOAD, key);
                method.visitVarInsn(Opcodes.ALOAD, registryManager);
                method.visitVarInsn(Opcodes.ALOAD, dimensionEntry);
                method.visitVarInsn(Opcodes.ILOAD, isClient);
                method.visitVarInsn(Opcodes.ILOAD, debugWorld);
                method.visitVarInsn(Opcodes.LLOAD, seed);
                method.visitVarInsn(Opcodes.ILOAD, maxChainedNeighborUpdates);
                method.visitVarInsn(Opcodes.ALOAD, callbackInfo);
                method.visitMethodInsn(
                        isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                        VOXY_WORLD_MIXIN,
                        VOXY_WORLD_CALLBACK_ORIGINAL,
                        VOXY_WORLD_CALLBACK_1_21_11,
                        false
                );
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(isStatic ? 11 : 12, callbackInfo + 1);
                method.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyClientLevelCallback(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_CLIENT_LEVEL_MIXIN)) return input;

        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private int callbackAccess;
            private boolean callbackFound;

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (!name.equals(VOXY_CLIENT_LEVEL_CALLBACK)
                        || !descriptor.equals(VOXY_CLIENT_LEVEL_CALLBACK_1_21_11)) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                callbackFound = true;
                callbackAccess = access;
                MethodVisitor original = super.visitMethod(
                        access,
                        VOXY_CLIENT_LEVEL_CALLBACK_ORIGINAL,
                        descriptor,
                        signature,
                        exceptions
                );
                return new MethodVisitor(Opcodes.ASM9, original) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        if (annotation.equals(INJECT)) return null;
                        return super.visitAnnotation(annotation, visible);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (callbackFound) addClientLevelCallbackBridge(this, callbackAccess);
                super.visitEnd();
            }

            private void addClientLevelCallbackBridge(ClassVisitor visitor, int access) {
                MethodVisitor method = visitor.visitMethod(
                        access,
                        VOXY_CLIENT_LEVEL_CALLBACK,
                        VOXY_CLIENT_LEVEL_CALLBACK_1_21_1,
                        null,
                        null
                );
                addInjectAnnotation(method, "<init>", "TAIL", null, true);

                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitVarInsn(Opcodes.ALOAD, 2);
                method.visitVarInsn(Opcodes.ALOAD, 3);
                method.visitVarInsn(Opcodes.ALOAD, 4);
                method.visitVarInsn(Opcodes.ILOAD, 5);
                method.visitVarInsn(Opcodes.ILOAD, 6);
                method.visitVarInsn(Opcodes.ALOAD, 8);
                method.visitVarInsn(Opcodes.ILOAD, 9);
                method.visitVarInsn(Opcodes.LLOAD, 10);
                method.visitInsn(Opcodes.ICONST_0);
                method.visitVarInsn(Opcodes.ALOAD, 12);
                method.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        VOXY_CLIENT_LEVEL_MIXIN,
                        VOXY_CLIENT_LEVEL_CALLBACK_ORIGINAL,
                        VOXY_CLIENT_LEVEL_CALLBACK_1_21_11,
                        false
                );
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(13, 13);
                method.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchVoxyRenderSystemCallback(byte[] input) {
        ClassReader reader = new ClassReader(input);
        if (!reader.getClassName().equals(VOXY_RENDER_SYSTEM_MIXIN)) return input;

        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private int callbackAccess;
            private boolean callbackFound;

            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                if (!name.equals(VOXY_RENDER_SYSTEM_CALLBACK) || callbackFound) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
                callbackFound = true;
                callbackAccess = access;
                return null;
            }

            @Override
            public void visitEnd() {
                if (callbackFound) {
                    MethodVisitor method = visitMethod(
                            callbackAccess,
                            VOXY_RENDER_SYSTEM_CALLBACK,
                            VOXY_RENDER_SYSTEM_CALLBACK_NEW,
                            null,
                            null
                    );
                    addInjectAnnotation(method, "initRenderer", "RETURN", 900, false);
                    method.visitCode();
                    method.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "me/cortex/voxy/client/VoxyClient",
                            "initVoxyClient",
                            "()V",
                            false
                    );
                    method.visitInsn(Opcodes.RETURN);
                    method.visitMaxs(0, 3);
                    method.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void addInjectAnnotation(
            MethodVisitor method,
            String targetMethod,
            String atValue,
            Integer order,
            boolean remap
    ) {
        AnnotationVisitor inject = method.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, targetMethod);
        methods.visitEnd();
        if (order != null) inject.visit("order", order);
        inject.visit("remap", remap);
        AnnotationVisitor at = inject.visitAnnotation("at", AT);
        at.visit("value", atValue);
        at.visitEnd();
        inject.visitEnd();
    }

    private static byte[] patchJavaVersion(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(
                    int version,
                    int access,
                    String name,
                    String signature,
                    String superName,
                    String[] interfaces
            ) {
                super.visit(version > Opcodes.V21 ? Opcodes.V21 : version, access, name, signature, superName, interfaces);
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchClasspathDirectoryScan(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(
                    int access,
                    String name,
                    String descriptor,
                    String signature,
                    String[] exceptions
            ) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ((access & Opcodes.ACC_STATIC) != 0
                        && name.equals("collectAllClasses")
                        && descriptor.equals("(Ljava/lang/String;)Ljava/util/List;")) {
                    method.visitCode();
                    method.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "java/util/List",
                            "of",
                            "()Ljava/util/List;",
                            true
                    );
                    method.visitInsn(Opcodes.ARETURN);
                    method.visitMaxs(1, 1);
                    method.visitEnd();
                    return null;
                }
                return method;
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchNeoForgeColorMapAccess(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, 0);
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
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        if (opcode == Opcodes.GETFIELD
                                && owner.equals(BLOCK_COLORS)
                                && name.equals("blockColors")
                                && descriptor.equals("Lnet/minecraft/core/IdMapper;")) {
                            visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    COLOR_COMPAT,
                                    "getColorMapper",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                                    false
                            );
                            visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/core/IdMapper");
                        } else {
                            super.visitFieldInsn(opcode, owner, name, descriptor);
                        }
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static byte[] patchFabricMetadataAccess(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(reader, 0);
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
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEINTERFACE
                                && owner.equals(FABRIC_METADATA)
                                && name.equals("getCustomValue")
                                && descriptor.equals("(Ljava/lang/String;)L" + FABRIC_CUSTOM_VALUE + ";")) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    LOADER_COMPAT,
                                    "getCustomValue",
                                    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                                    false
                            );
                            super.visitTypeInsn(Opcodes.CHECKCAST, FABRIC_CUSTOM_VALUE);
                        } else {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static final class RoxyClassRemapper extends ClassRemapper {
        private final RoxyRemapper remapper;
        private final MixinMetadata metadata;

        private RoxyClassRemapper(ClassVisitor visitor, RoxyRemapper remapper, MixinMetadata metadata) {
            super(Opcodes.ASM9, visitor, remapper);
            this.remapper = remapper;
            this.metadata = metadata;
        }

        @Override
        protected AnnotationVisitor createAnnotationRemapper(String descriptor, AnnotationVisitor visitor) {
            return new RoxyAnnotationRemapper(descriptor, visitor, remapper, metadata);
        }

        @Override
        protected AnnotationVisitor createAnnotationRemapper(AnnotationVisitor visitor) {
            return new RoxyAnnotationRemapper(null, visitor, remapper, metadata);
        }

        @Override
        protected FieldVisitor createFieldRemapper(FieldVisitor visitor) {
            return new RoxyFieldRemapper(visitor, remapper, metadata);
        }

        @Override
        protected MethodVisitor createMethodRemapper(MethodVisitor visitor) {
            return new RoxyMethodRemapper(visitor, remapper, metadata);
        }
    }

    private static final class RoxyFieldRemapper extends FieldRemapper {
        private final RoxyRemapper remapper;
        private final MixinMetadata metadata;

        private RoxyFieldRemapper(FieldVisitor visitor, RoxyRemapper remapper, MixinMetadata metadata) {
            super(Opcodes.ASM9, visitor, remapper);
            this.remapper = remapper;
            this.metadata = metadata;
        }

        @Override
        protected AnnotationVisitor createAnnotationRemapper(String descriptor, AnnotationVisitor visitor) {
            return new RoxyAnnotationRemapper(descriptor, visitor, remapper, metadata);
        }

        @Override
        protected AnnotationVisitor createAnnotationRemapper(AnnotationVisitor visitor) {
            return new RoxyAnnotationRemapper(null, visitor, remapper, metadata);
        }
    }

    private static final class RoxyMethodRemapper extends MethodRemapper {
        private final RoxyRemapper remapper;
        private final MixinMetadata metadata;

        private RoxyMethodRemapper(MethodVisitor visitor, RoxyRemapper remapper, MixinMetadata metadata) {
            super(Opcodes.ASM9, visitor, remapper);
            this.remapper = remapper;
            this.metadata = metadata;
        }

        @Override
        protected AnnotationVisitor createAnnotationRemapper(String descriptor, AnnotationVisitor visitor) {
            return new RoxyAnnotationRemapper(descriptor, visitor, remapper, metadata);
        }

        @Override
        protected AnnotationVisitor createAnnotationRemapper(AnnotationVisitor visitor) {
            return new RoxyAnnotationRemapper(null, visitor, remapper, metadata);
        }
    }

    private static final class RoxyRemapper extends Remapper {
        private final RoxyMappings mappings;
        private final String mixinClass;
        private final MixinMetadata metadata;

        private RoxyRemapper(RoxyMappings mappings, String mixinClass, MixinMetadata metadata) {
            this.mappings = mappings;
            this.mixinClass = mixinClass;
            this.metadata = metadata;
        }

        @Override
        public String map(String internalName) {
            return mappings.mapClass(internalName);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            MixinMember shadow = metadata.fieldsByName.get(name);
            if (owner.equals(mixinClass) && shadow != null) {
                String targetName = stripPrefix(name, shadow.prefix);
                String mapped = mappings.mapFieldName(metadata.targetOwner(), targetName, shadow.descriptor);
                return shadow.prefix + mapped;
            }
            return mappings.mapFieldName(owner, name, descriptor);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            MixinMember shadow = metadata.methodsByName.get(name);
            if (owner.equals(mixinClass) && shadow != null) {
                String targetName = stripPrefix(name, shadow.prefix);
                String mapped = mappings.mapMethodName(metadata.targetOwner(), targetName, shadow.descriptor);
                return shadow.prefix + mapped;
            }
            return mappings.mapMethodName(owner, name, descriptor);
        }

        private static String stripPrefix(String name, String prefix) {
            return !prefix.isEmpty() && name.startsWith(prefix) ? name.substring(prefix.length()) : name;
        }
    }

    private static final class RoxyAnnotationRemapper extends AnnotationRemapper {
        private final String descriptor;
        private final RoxyRemapper remapper;
        private final MixinMetadata metadata;

        private RoxyAnnotationRemapper(
                String descriptor,
                AnnotationVisitor visitor,
                RoxyRemapper remapper,
                MixinMetadata metadata
        ) {
            super(Opcodes.ASM9, descriptor, visitor, remapper);
            this.descriptor = descriptor;
            this.remapper = remapper;
            this.metadata = metadata;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof String string) {
                value = mapString(name, string);
            }
            super.visit(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            AnnotationVisitor delegate = super.visitArray(name);
            return new AnnotationVisitor(Opcodes.ASM9, delegate) {
                @Override
                public void visit(String ignored, Object value) {
                    if (value instanceof String string) {
                        value = mapString(name, string);
                    }
                    if (delegate != null) delegate.visit(ignored, value);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String ignored, String nestedDescriptor) {
                    AnnotationVisitor nested = delegate == null ? null : delegate.visitAnnotation(ignored, nestedDescriptor);
                    return new RoxyAnnotationRemapper(nestedDescriptor, nested, remapper, metadata);
                }

                @Override
                public void visitEnd() {
                    if (delegate != null) delegate.visitEnd();
                }
            };
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String nestedDescriptor) {
            AnnotationVisitor nested = super.visitAnnotation(name, nestedDescriptor);
            return new RoxyAnnotationRemapper(nestedDescriptor, nested, remapper, metadata);
        }

        private String mapString(String name, String value) {
            if (descriptor == null) return value;
            if (descriptor.equals(MIXIN) && name.equals("targets")) {
                return remapper.mappings.mapClassString(value);
            }
            if (name.equals("method") && isInjectionAnnotation(descriptor)) {
                String mapped = remapper.mappings.mapMemberReference(value, metadata.targetOwner());
                return adaptMinecraftDisconnect(mapped, metadata);
            }
            if (descriptor.equals(AT) && name.equals("target")) {
                return remapper.mappings.mapMemberReference(value, metadata.targetOwner());
            }
            if ((descriptor.equals(ACCESSOR) || descriptor.equals(INVOKER)) && name.equals("value")) {
                return remapper.mappings.mapMemberReference(value, metadata.targetOwner());
            }
            return value;
        }

        private static String adaptMinecraftDisconnect(String reference, MixinMetadata metadata) {
            if (metadata.targetOwner().equals(MINECRAFT) && reference.equals(NEWER_DISCONNECT)) {
                return MC_1_21_1_DISCONNECT;
            }
            return reference;
        }

        private static boolean isInjectionAnnotation(String descriptor) {
            return descriptor.equals(INJECT)
                    || descriptor.equals(REDIRECT)
                    || descriptor.equals(MODIFY_ARG)
                    || descriptor.equals(MODIFY_ARGS)
                    || descriptor.equals(MODIFY_VARIABLE)
                    || descriptor.equals(MODIFY_CONSTANT)
                    || descriptor.endsWith("/ModifyExpressionValue;")
                    || descriptor.endsWith("/ModifyReturnValue;")
                    || descriptor.endsWith("/WrapOperation;")
                    || descriptor.endsWith("/WrapWithCondition;");
        }
    }

    private static final class MixinMetadata {
        private final Set<String> targets = new HashSet<>();
        private final Map<MemberKey, MixinMember> fields = new HashMap<>();
        private final Map<MemberKey, MixinMember> methods = new HashMap<>();
        private final Map<String, MixinMember> fieldsByName = new HashMap<>();
        private final Map<String, MixinMember> methodsByName = new HashMap<>();

        private static MixinMetadata read(ClassReader reader) {
            MixinMetadata metadata = new MixinMetadata();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!descriptor.equals(MIXIN)) return null;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            addTarget(metadata, name, value);
                        }

                        @Override
                        public AnnotationVisitor visitArray(String name) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String ignored, Object value) {
                                    addTarget(metadata, name, value);
                                }
                            };
                        }
                    };
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    return new FieldVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (!annotation.equals(SHADOW)) return null;
                            MixinMember member = new MixinMember(false, descriptor, "");
                            return shadowVisitor(member, () -> {
                                metadata.fields.put(new MemberKey(name, descriptor), member);
                                metadata.fieldsByName.put(name, member);
                            });
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (annotation.equals(SHADOW)) {
                                MixinMember member = new MixinMember(true, descriptor, "");
                                return shadowVisitor(member, () -> {
                                    metadata.methods.put(new MemberKey(name, descriptor), member);
                                    metadata.methodsByName.put(name, member);
                                });
                            }
                            if (annotation.equals(OVERWRITE)) {
                                MixinMember member = new MixinMember(true, descriptor, "");
                                metadata.methods.put(new MemberKey(name, descriptor), member);
                                metadata.methodsByName.put(name, member);
                            }
                            return null;
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return metadata;
        }

        private static AnnotationVisitor shadowVisitor(MixinMember member, Runnable finish) {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if (name.equals("prefix") && value instanceof String prefix) member.prefix = prefix;
                }

                @Override
                public void visitEnd() {
                    finish.run();
                }
            };
        }

        private static void addTarget(MixinMetadata metadata, String name, Object value) {
            if (!(name.equals("value") || name.equals("targets"))) return;
            if (value instanceof Type type) {
                metadata.targets.add(type.getInternalName());
            } else if (value instanceof String string) {
                metadata.targets.add(string.replace('.', '/'));
            }
        }

        private String targetOwner() {
            return targets.stream().findFirst().orElse("");
        }
    }

    private static final class MixinMember {
        private final boolean method;
        private final String descriptor;
        private String prefix;

        private MixinMember(boolean method, String descriptor, String prefix) {
            this.method = method;
            this.descriptor = descriptor;
            this.prefix = prefix;
        }
    }

    private record MemberKey(String name, String descriptor) {
    }
}

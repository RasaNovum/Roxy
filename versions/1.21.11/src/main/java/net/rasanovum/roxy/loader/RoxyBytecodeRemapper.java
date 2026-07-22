package net.rasanovum.roxy.loader;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
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
    private static final String GL_TEXTURE = "com/mojang/blaze3d/opengl/GlTexture";
    private static final String GPU_COMPAT = "net/rasanovum/roxy/loader/RoxyGpuCompat";
    private static final String BLOCK_COLORS = "net/minecraft/client/color/block/BlockColors";
    private static final String COLOR_COMPAT = "net/rasanovum/roxy/loader/RoxyColorCompat";

    private RoxyBytecodeRemapper() {
    }

    public static byte[] remap(byte[] input, RoxyMappings mappings) {
        ClassReader reader = new ClassReader(input);
        MixinMetadata metadata = MixinMetadata.read(reader);
        String sourceClass = reader.getClassName();
        RoxyRemapper remapper = new RoxyRemapper(mappings, sourceClass, metadata);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new RoxyClassRemapper(writer, remapper, metadata), 0);
        return patchNeoForgeColorMapAccess(
                patchNeoForgeGpuTextureCasts(patchClasspathDirectoryScan(writer.toByteArray()))
        );
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

    private static byte[] patchNeoForgeGpuTextureCasts(byte[] input) {
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
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.CHECKCAST && type.equals(GL_TEXTURE)) {
                            visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    GPU_COMPAT,
                                    "unwrapGlTexture",
                                    "(Ljava/lang/Object;)L" + GL_TEXTURE + ";",
                                    false
                            );
                        } else {
                            super.visitTypeInsn(opcode, type);
                        }
                    }
                };
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
                                    "(L" + BLOCK_COLORS + ";)Lnet/minecraft/core/IdMapper;",
                                    false
                            );
                        } else {
                            super.visitFieldInsn(opcode, owner, name, descriptor);
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
                return remapper.mappings.mapMemberReference(value, metadata.targetOwner());
            }
            if (descriptor.equals(AT) && name.equals("target")) {
                return remapper.mappings.mapMemberReference(value, metadata.targetOwner());
            }
            if ((descriptor.equals(ACCESSOR) || descriptor.equals(INVOKER)) && name.equals("value")) {
                return remapper.mappings.mapMemberReference(value, metadata.targetOwner());
            }
            return value;
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

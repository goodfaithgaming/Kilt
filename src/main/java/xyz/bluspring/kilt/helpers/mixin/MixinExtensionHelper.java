package xyz.bluspring.kilt.helpers.mixin;

import org.jetbrains.annotations.ApiStatus;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.util.Annotations;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public final class MixinExtensionHelper {
    // This should be executed in the mixin plugin with the corresponding method.
    // This is only separated into this class for anyone who wants to use this code.
    private static boolean containsOpcode(InsnList list, int opcode) {
        for (AbstractInsnNode node : list) {
            if (node.getOpcode() == opcode)
                return true;
        }

        return false;
    }

    private static boolean containsThisCall(ClassNode classNode, InsnList list) {
        for (AbstractInsnNode node : list) {
            if (node.getOpcode() == Opcodes.INVOKESPECIAL && node instanceof MethodInsnNode methodInsnNode && methodInsnNode.owner.equals(classNode.name) && methodInsnNode.name.equals("<init>"))
                return true;
        }

        return false;
    }

    @ApiStatus.Internal
    public static void apply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        var classNode = mixinInfo.getClassNode(0);
        var slashedMixinClassName = mixinClassName.replaceAll("\\.", "/");
        var slashedTargetClassName = targetClassName.replaceAll("\\.", "/");

        var fieldsToRemove = new ArrayList<FieldNode>();
        var methodsToRemove = new ArrayList<MethodNode>();

        var extend = Annotations.getVisible(classNode, Extends.class);
        if (extend != null) {
            if (targetClass.superName != null && !targetClass.superName.equals("java/lang/Object"))
                throw new IllegalStateException(String.format("Class %s should not already have a super class! (tried extend by %s, has %s)", targetClassName, mixinClassName, classNode.superName));

            var visitor = new AnnotationValueVisitor();
            extend.accept(visitor);
            var className = ((Type) visitor.values.get("value")).getClassName();
            targetClass.superName = className.replace(".", "/");
        }

        for (FieldNode fieldNode : classNode.fields) {
            if (Annotations.getVisible(fieldNode, CreateStatic.class) == null)
                continue;

            fieldsToRemove.add(fieldNode);

            targetClass.fields.removeIf((field) -> field.name.equals(fieldNode.name) && field.desc.equals(fieldNode.desc));
            targetClass.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, fieldNode.name, fieldNode.desc, fieldNode.signature, fieldNode.value).visitEnd();
        }

        for (MethodNode methodNode : classNode.methods) {
            if (Annotations.getVisible(methodNode, CreateStatic.class) != null) {
                methodsToRemove.add(methodNode);
                targetClass.methods.removeIf((method) -> method.name.equals(methodNode.name) && method.desc.equals(methodNode.desc));

                var method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, methodNode.name, methodNode.desc, methodNode.signature, methodNode.exceptions != null ? methodNode.exceptions.toArray(String[]::new) : null);
                method.visitCode();

                method.instructions.add(methodNode.instructions);
                method.localVariables.addAll(methodNode.localVariables);

                method.visitEnd();

                targetClass.methods.add(method);
            } else if (Annotations.getVisible(methodNode, CreateInitializer.class) != null) {
                var initializer = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", methodNode.desc, methodNode.signature, methodNode.exceptions != null ? methodNode.exceptions.toArray(String[]::new) : null);
                initializer.visitCode();

                for (AbstractInsnNode insnNode : methodNode.instructions) {
                    if (insnNode instanceof MethodInsnNode methodInsn) {
                        // super()/this()
                        if (insnNode.getOpcode() == Opcodes.INVOKESPECIAL) {
                            if (methodInsn.owner.equals(slashedMixinClassName)) { // this()
                                initializer.visitMethodInsn(Opcodes.INVOKESPECIAL, slashedTargetClassName, "<init>", methodInsn.desc, false);
                            } else { // super()
                                initializer.visitMethodInsn(Opcodes.INVOKESPECIAL, methodInsn.owner, "<init>", methodInsn.desc, false);
                            }
                        } else {
                            if (methodInsn.owner.equals(slashedMixinClassName)) {
                                methodInsn.owner = slashedTargetClassName;
                            }

                            initializer.instructions.add(methodInsn);
                        }
                    } else {
                        if (insnNode instanceof FieldInsnNode fieldInsn) {
                            if (fieldInsn.owner.equals(slashedMixinClassName)) {
                                fieldInsn.owner = slashedTargetClassName;
                            }

                            initializer.instructions.add(fieldInsn);
                        } else {
                            initializer.instructions.add(insnNode);
                        }
                    }
                }

                initializer.visitEnd();

                initializer.localVariables = methodNode.localVariables;

                // We don't need the method's instructions anymore.
                methodNode.instructions.clear();
                targetClass.methods.add(initializer);
            } else if (Annotations.getVisible(methodNode, AbstractOverride.class) != null) {
                var originalMethods = targetClass.methods.stream().filter(a -> a.name.equals(methodNode.name) && a.desc.equals(methodNode.desc)).toList();

                if (originalMethods.isEmpty()) {
                    throw new IllegalStateException("Could not find method " + methodNode.name + methodNode.desc + " in class " + targetClass.name);
                }

                var originalMethod = originalMethods.get(0);
                targetClass.methods.remove(originalMethod);

                var node = new MethodNode(originalMethod.access & ~Opcodes.ACC_ABSTRACT, originalMethod.name, originalMethod.desc, originalMethod.signature, methodNode.exceptions != null ? methodNode.exceptions.toArray(String[]::new) : null);
                node.visitCode();

                for (AbstractInsnNode insnNode : methodNode.instructions) {
                    if (insnNode instanceof MethodInsnNode methodInsn) {
                        if (methodInsn.owner.equals(slashedMixinClassName)) {
                            methodInsn.owner = slashedTargetClassName;
                        }

                        node.instructions.add(methodInsn);
                    } else {
                        if (insnNode instanceof FieldInsnNode fieldInsn) {
                            if (fieldInsn.owner.equals(slashedMixinClassName)) {
                                fieldInsn.owner = slashedTargetClassName;
                            }

                            node.instructions.add(fieldInsn);
                        } else {
                            node.instructions.add(insnNode);
                        }
                    }
                }

                node.visitEnd();
                node.localVariables = methodNode.localVariables;

                targetClass.methods.add(node);
            }
        }

        List<MethodNode> replacementNodes = new LinkedList<>();

        if (extend != null) {
            if (targetClass.superName.equals("net/minecraftforge/common/capabilities/CapabilityProvider")) {
                for (MethodNode node : targetClass.methods.stream().filter((node) -> node.name.equals("<init>")).toList()) {
                    if (containsThisCall(targetClass, node.instructions))
                        continue;

                    methodsToRemove.add(node);

                    var instructions = node.instructions;

                    var aload = instructions.get(2);
                    var invoke = instructions.get(3);
                    var firstInsn = instructions.get(4);

                    // remove Object.<init> call
                    instructions.remove(aload);
                    instructions.remove(invoke);

                    var insnList = new InsnList();
                    insnList.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    insnList.add(new LdcInsnNode(Type.getObjectType(targetClass.name)));
                    insnList.add(new InsnNode(Opcodes.ICONST_0));
                    insnList.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, targetClass.superName, "<init>", "(Ljava/lang/Class;Z)V"));

                    instructions.insertBefore(firstInsn, insnList);

                    var newNode = new MethodNode(Opcodes.ASM9, node.access, node.name, node.desc, node.signature, node.exceptions.toArray(new String[0]));
                    newNode.instructions = instructions;
                    replacementNodes.add(newNode);
                }
            }
        }

        for (FieldNode fieldNode : fieldsToRemove) {
            classNode.fields.remove(fieldNode);
        }

        for (MethodNode methodNode : methodsToRemove) {
            classNode.methods.remove(methodNode);
            targetClass.methods.remove(methodNode);
        }

        targetClass.methods.addAll(replacementNodes);
    }
}

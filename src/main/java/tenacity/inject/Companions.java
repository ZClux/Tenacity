package tenacity.inject;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.*;

/** Converts handler bodies to static companions; game classes retain their original schema. */
final class Companions implements Opcodes, ClassFileTransformer {
    static final String ACCESS = "tenacity/inject/Access";
    static final Handle BSM = new Handle(H_INVOKESTATIC, ACCESS, "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)Ljava/lang/invoke/CallSite;", false);
    final PortPlan plan;
    final ClassLoader game;
    Companions(PortPlan plan, ClassLoader game) { this.plan = plan; this.game = game; }
    static String helper(String mixin) { return "tenacity/inject/companions/" + mixin.replace('/', '_'); }

    byte[] companion(String original) {
        ClassNode source = plan.mixins.get(original);
        ClassNode node = new ClassNode();
        source.accept(node);
        String owner = plan.targets.get(original), helper = helper(original);
        Set<String> ownMethods = new HashSet<>();
        for (MethodNode m : source.methods) if (Bytecode.annotation(m.visibleAnnotations, "Shadow") == null && Bytecode.annotation(m.invisibleAnnotations, "Shadow") == null)
            ownMethods.add(m.name + m.desc);
        node.name = helper;
        node.superName = "java/lang/Object";
        node.access = ACC_PUBLIC | ACC_SUPER;
        node.signature = null;
        node.visibleAnnotations = node.invisibleAnnotations = null;
        node.nestHostClass = null;
        node.nestMembers = null;
        node.innerClasses.clear();
        node.fields.removeIf(f -> (f.access & ACC_STATIC) == 0);
        node.methods.removeIf(m -> m.name.equals("<init>") || !ownMethods.contains(m.name + m.desc));
        for (MethodNode method : node.methods) {
            boolean instance = (method.access & ACC_STATIC) == 0;
            if (instance) method.desc = Bytecode.prepend(method.desc, "Ljava/lang/Object;");
            method.access = ACC_PUBLIC | ACC_STATIC;
            method.signature = null;
            method.visibleAnnotations = method.invisibleAnnotations = null;
            method.visibleParameterAnnotations = method.invisibleParameterAnnotations = null;
            method.localVariables = null;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof FrameNode) method.instructions.remove(instruction);
                else if (instruction instanceof FieldInsnNode f && f.owner.equals(original)) {
                    FieldNode declared = source.fields.stream().filter(v -> v.name.equals(f.name)).findFirst().orElse(null);
                    if (declared != null && (declared.access & ACC_STATIC) != 0 && !shadow(declared)) f.owner = helper;
                    else {
                        String fieldOwner = owner;
                        boolean state = declared != null && !shadow(declared);
                        if (declared == null && f.getOpcode() == GETSTATIC && !source.interfaces.isEmpty()) fieldOwner = source.interfaces.getFirst();
                        int opcode = state ? (f.getOpcode() == GETFIELD ? 1000 : 1001) : f.getOpcode();
                        String desc = switch (f.getOpcode()) {
                            case GETFIELD -> "(Ljava/lang/Object;)" + f.desc;
                            case PUTFIELD -> "(Ljava/lang/Object;" + f.desc + ")V";
                            case GETSTATIC -> "()" + f.desc; case PUTSTATIC -> "(" + f.desc + ")V";
                            default -> throw new IllegalStateException();
                        };
                        method.instructions.set(f, dynamic(desc, state ? original : fieldOwner, f.name, f.desc, opcode, owner));
                    }
                } else if (instruction instanceof MethodInsnNode m && m.owner.equals(original)) {
                    if (ownMethods.contains(m.name + m.desc)) {
                        if (m.getOpcode() != INVOKESTATIC) m.desc = Bytecode.prepend(m.desc, "Ljava/lang/Object;");
                        m.owner = helper; m.setOpcode(INVOKESTATIC); m.itf = false;
                    } else {
                        int opcode = m.getOpcode() == INVOKESTATIC ? INVOKESTATIC : INVOKEVIRTUAL;
                        String desc = opcode == INVOKESTATIC ? m.desc : Bytecode.prepend(m.desc, "Ljava/lang/Object;");
                        method.instructions.set(m, dynamic(desc, owner, m.name, m.desc, opcode, owner));
                    }
                } else if (instruction instanceof MethodInsnNode m && m.owner.startsWith("net/minecraft/") && !m.name.equals("<init>")) {
                    String desc = m.getOpcode() == INVOKESTATIC ? m.desc : Bytecode.prepend(m.desc, "Ljava/lang/Object;");
                    method.instructions.set(m, dynamic(desc, m.owner, m.name, m.desc, m.getOpcode(), owner));
                } else if (instruction instanceof FieldInsnNode f && f.owner.startsWith("net/minecraft/")) {
                    method.instructions.set(f, field(f));
                } else if (instruction instanceof InvokeDynamicInsnNode indy) {
                    retargetLambda(indy, original, helper, ownMethods);
                }
            }
        }
        adapt(node);
        return Bytecode.write(node, game);
    }
    /**
     * A lambda or method reference inside a mixin compiles to a handle on a private synthetic method of the mixin class. The copy
     * lives in another class, which may not call that private method (IllegalAccessError the first time the lambda runs), so the
     * handle is pointed at the copied method instead. A lambda that captured {@code this} receives the game object as {@code Object},
     * exactly like every other converted instance method.
     */
    private static void retargetLambda(InvokeDynamicInsnNode indy, String original, String helper, Set<String> ownMethods) {
        if (indy.bsmArgs == null) return;
        for (int i = 0; i < indy.bsmArgs.length; i++) {
            if (!(indy.bsmArgs[i] instanceof Handle handle) || !handle.getOwner().equals(original)) continue;
            if (!ownMethods.contains(handle.getName() + handle.getDesc())) throw new IllegalStateException("Lambda targets a mixin member that was not copied: " + original + "." + handle.getName() + handle.getDesc());
            if (handle.getTag() == H_NEWINVOKESPECIAL) throw new IllegalStateException("Constructor reference to a mixin class in " + original);
            boolean instance = handle.getTag() != H_INVOKESTATIC;
            indy.bsmArgs[i] = new Handle(H_INVOKESTATIC, helper, handle.getName(), instance ? Bytecode.prepend(handle.getDesc(), "Ljava/lang/Object;") : handle.getDesc(), false);
            if (!instance) continue;
            Type[] captured = Type.getArgumentTypes(indy.desc);
            if (captured.length == 0 || captured[0].getSort() != Type.OBJECT) throw new IllegalStateException("Instance lambda without a captured receiver in " + original);
            captured[0] = Type.getObjectType("java/lang/Object");
            indy.desc = Type.getMethodDescriptor(Type.getReturnType(indy.desc), captured);
        }
    }
    private static boolean shadow(FieldNode field) {
        return Bytecode.annotation(field.visibleAnnotations, "Shadow") != null || Bytecode.annotation(field.invisibleAnnotations, "Shadow") != null;
    }
    static InvokeDynamicInsnNode dynamic(String desc, String owner, String name, String memberDesc, int opcode, String caller) {
        return new InvokeDynamicInsnNode("tenacityAccess", desc, BSM, owner, name, memberDesc, opcode, caller);
    }
    static InvokeDynamicInsnNode field(FieldInsnNode f) {
        String desc = switch (f.getOpcode()) {
            case GETFIELD -> "(L" + f.owner + ";)" + f.desc;
            case PUTFIELD -> "(L" + f.owner + ";" + f.desc + ")V";
            case GETSTATIC -> "()" + f.desc; case PUTSTATIC -> "(" + f.desc + ")V";
            default -> throw new IllegalStateException();
        };
        return dynamic(desc, f.owner, f.name, f.desc, f.getOpcode(), "");
    }
    boolean adapt(ClassNode node) {
        boolean changed = false;
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof TypeInsnNode type && plan.accessors.containsKey(type.desc)) {
                InsnList before = new InsnList();
                before.add(new LdcInsnNode(type.desc));
                if (type.getOpcode() == CHECKCAST) {
                    before.add(new MethodInsnNode(INVOKESTATIC, ACCESS, "cast", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false));
                    method.instructions.insertBefore(type, before);
                } else if (type.getOpcode() == INSTANCEOF) {
                    before.add(new MethodInsnNode(INVOKESTATIC, ACCESS, "isInstance", "(Ljava/lang/Object;Ljava/lang/String;)Z", false));
                    method.instructions.insertBefore(type, before);
                    method.instructions.remove(type);
                } else throw new IllegalStateException("Unsupported accessor use " + type.desc);
                changed = true;
            } else if (instruction instanceof FieldInsnNode f && plan.widened.contains(f.owner + "." + f.name + f.desc)) {
                method.instructions.set(f, field(f)); changed = true;
            } else if (instruction instanceof MethodInsnNode m && plan.widened.contains(m.owner + "." + m.name + m.desc)) {
                String desc = m.getOpcode() == INVOKESTATIC ? m.desc : Bytecode.prepend(m.desc, "L" + m.owner + ";");
                method.instructions.set(m, dynamic(desc, m.owner, m.name, m.desc, m.getOpcode(), "")); changed = true;
            }
        }
        return changed;
    }
    static boolean clientClass(String name){return name.startsWith("tenacity/");}
    @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
        if (loader != game || name == null || name.startsWith("tenacity/inject/") || !clientClass(name) || plan.mixins.containsKey(name)) return null;
        try {
            ClassNode node = Bytecode.read(bytes);
            return adapt(node) ? Bytecode.write(node, game) : null;
        } catch (Throwable failure) {
            RuntimePort.fail("Cannot adapt " + name, failure);
            throw new ClassFormatError("Tenacity accessor port failed for " + name);
        }
    }
}

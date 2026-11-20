package tenacity.inject;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.InputStream;
import java.util.*;

/** Shared bytecode utilities. Hierarchy resolution reads class files without initializing game classes. */
final class Bytecode implements Opcodes {
    static AnnotationNode annotation(List<AnnotationNode> annotations, String suffix) {
        if (annotations != null) for (AnnotationNode a : annotations) if (a.desc.endsWith("/" + suffix + ";")) return a;
        return null;
    }
    static Object value(AnnotationNode a, String key, Object fallback) {
        if (a != null && a.values != null) for (int i = 0; i < a.values.size(); i += 2)
            if (key.equals(a.values.get(i))) return a.values.get(i + 1);
        return fallback;
    }
    @SuppressWarnings("unchecked")
    static <T> List<T> list(Object value) { return value instanceof List<?> l ? (List<T>) l : List.of((T) value); }
    static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }
    static byte[] write(ClassNode node, ClassLoader loader) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return common(a, b, loader);
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }
    private static String common(String a, String b, ClassLoader loader) {
        if (a.equals(b)) return a;
        if (assignable(a, b, loader)) return a;
        if (assignable(b, a, loader)) return b;
        if (a.startsWith("[") && b.startsWith("[")) {
            Type x = Type.getType(a), y = Type.getType(b);
            if (x.getDimensions() == y.getDimensions() && x.getElementType().getSort() == Type.OBJECT && y.getElementType().getSort() == Type.OBJECT)
                return "[".repeat(x.getDimensions()) + "L" + common(x.getElementType().getInternalName(), y.getElementType().getInternalName(), loader) + ";";
        }
        for (String p = parent(a, loader); p != null; p = parent(p, loader)) if (assignable(p, b, loader)) return p;
        return "java/lang/Object";
    }
    private static boolean assignable(String a, String b, ClassLoader loader) {
        if (a.equals(b) || a.equals("java/lang/Object")) return true;
        if (b.startsWith("[")) return a.equals("java/lang/Cloneable") || a.equals("java/io/Serializable");
        ClassReader reader = resource(b, loader);
        if (reader == null) return false;
        for (String i : reader.getInterfaces()) if (assignable(a, i, loader)) return true;
        return reader.getSuperName() != null && assignable(a, reader.getSuperName(), loader);
    }
    private static String parent(String name, ClassLoader loader) {
        if (name.startsWith("[")) return "java/lang/Object";
        ClassReader r = resource(name, loader);
        return r == null ? null : r.getSuperName();
    }
    private static ClassReader resource(String name, ClassLoader loader) {
        try (InputStream in = loader.getResourceAsStream(name + ".class")) {
            return in == null ? null : new ClassReader(in);
        } catch (Exception e) { throw new IllegalStateException("Cannot read hierarchy of " + name, e); }
    }
    static void integer(InsnList insns, int n) { insns.add(new LdcInsnNode(n)); }
    static void box(InsnList insns, Type type) {
        String wrapper = wrapper(type);
        if (wrapper != null) insns.add(new MethodInsnNode(INVOKESTATIC, wrapper, "valueOf", "(" + type.getDescriptor() + ")L" + wrapper + ";", false));
    }
    static void unbox(InsnList insns, Type type) {
        String wrapper = wrapper(type);
        if (type.getSort() == Type.VOID) { insns.add(new InsnNode(POP)); return; }
        if (wrapper == null) insns.add(new TypeInsnNode(CHECKCAST, type.getInternalName()));
        else {
            insns.add(new TypeInsnNode(CHECKCAST, wrapper));
            insns.add(new MethodInsnNode(INVOKEVIRTUAL, wrapper, type.getClassName() + "Value", "()" + type.getDescriptor(), false));
        }
    }
    private static String wrapper(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean"; case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character"; case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer"; case Type.FLOAT -> "java/lang/Float";
            case Type.LONG -> "java/lang/Long"; case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
    }
    static String prepend(String desc, String first) { return "(" + first + desc.substring(1); }
}

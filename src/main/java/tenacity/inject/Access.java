package tenacity.inject;

import java.lang.invoke.*;
import java.lang.ref.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Accessor adapters and method handles replace changes to already-loaded class schemas. */
public final class Access {
    static PortPlan plan;
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Map<String, MethodHandle> HANDLES = new ConcurrentHashMap<>();
    private static final ReferenceQueue<Object> DEAD = new ReferenceQueue<>();
    private static final Map<IdentityRef, Map<String, Object>> STATE = new HashMap<>();
    static void validateAccessors() throws Throwable {
        for (var methods : plan.accessors.values()) for (PortPlan.Member m : methods.values())
            member(m.owner(), m.name(), m.desc(), m.field() ? (m.setter() ? 181 : 180) : 182, "");
    }
    private static final class IdentityRef extends WeakReference<Object> {
        final int hash;
        IdentityRef(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) { return this == other || other instanceof IdentityRef r && get() != null && get() == r.get(); }
    }
    public static synchronized Object stateGet(Object self, String key, String descriptor) throws Throwable {
        Reference<?> ref;
        while ((ref = DEAD.poll()) != null) STATE.remove(ref);
        IdentityRef lookup = new IdentityRef(self, null);
        Map<String, Object> values = STATE.get(lookup);
        if (values != null && values.containsKey(key)) return values.get(key);
        Object value = switch (descriptor) {
            case "Z" -> false; case "B" -> (byte) 0; case "C" -> (char) 0; case "S" -> (short) 0;
            case "I" -> 0; case "J" -> 0L; case "F" -> 0f; case "D" -> 0d; default -> null;
        };
        return value;
    }
    public static synchronized void stateSet(Object self, Object value, String key) {
        Map<String, Object> values = STATE.computeIfAbsent(new IdentityRef(self, DEAD), k -> new HashMap<>());
        values.put(key, value);
    }
    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type,
                                     String owner, String member, String descriptor, int opcode, String caller) throws Throwable {
        MethodHandle handle;
        if (opcode == 1000) {
            handle = LOOKUP.findStatic(Access.class, "stateGet", MethodType.methodType(Object.class, Object.class, String.class, String.class));
            handle = MethodHandles.insertArguments(handle, 1, owner + "." + member, descriptor);
        } else if (opcode == 1001) {
            handle = LOOKUP.findStatic(Access.class, "stateSet", MethodType.methodType(void.class, Object.class, Object.class, String.class));
            handle = MethodHandles.insertArguments(handle, 2, owner + "." + member);
        } else handle = member(owner, member, descriptor, opcode, caller);
        return new ConstantCallSite(handle.asType(type));
    }
    static MethodHandle member(String owner, String name, String descriptor, int opcode, String caller) throws Throwable {
        String key = owner + "." + name + descriptor + ":" + opcode + ":" + caller;
        MethodHandle cached = HANDLES.get(key);
        if (cached != null) return cached;
        ClassLoader loader = Access.class.getClassLoader();
        Class<?> target = Class.forName(owner.replace('/', '.'), false, loader);
        MethodHandle handle;
        if (opcode >= 178 && opcode <= 181) {
            Field field = field(target, name);
            field.setAccessible(true);
            handle = opcode == 179 || opcode == 181 ? LOOKUP.unreflectSetter(field) : LOOKUP.unreflectGetter(field);
        } else {
            MethodType signature = MethodType.fromMethodDescriptorString(descriptor, loader);
            if (opcode == 183) {
                Class<?> special = Class.forName(caller.replace('/', '.'), false, loader);
                handle = MethodHandles.privateLookupIn(special, LOOKUP).findSpecial(target, name, signature, special);
            } else {
                Method method = method(target, name, signature.parameterArray());
                method.setAccessible(true);
                handle = LOOKUP.unreflect(method);
            }
        }
        HANDLES.put(key, handle);
        return handle;
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            for (Class<?> i : c.getInterfaces()) try { return field(i, name); } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static Method method(Class<?> type, String name, Class<?>[] parameters) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredMethod(name, parameters); } catch (NoSuchMethodException ignored) {}
            for (Class<?> i : c.getInterfaces()) try { return method(i, name, parameters); } catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    public static Object cast(Object self, String iface) {
        if (self == null) return null;
        ClassLoader loader = Access.class.getClassLoader();
        try {
            Class<?> type = Class.forName(iface.replace('/', '.'), false, loader);
            Class<?> target = Class.forName(plan.targets.get(iface).replace('/', '.'), false, loader);
            if (!target.isInstance(self)) throw new ClassCastException(self.getClass().getName() + " is not " + target.getName());
            return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "toString" -> "Tenacity accessor for " + self.getClass().getName();
                    case "hashCode" -> System.identityHashCode(self); case "equals" -> proxy == args[0]; default -> throw new UnsupportedOperationException();
                };
                String desc = MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
                PortPlan.Member m = plan.accessors.get(iface).get(method.getName() + desc);
                if (m == null) throw new NoSuchMethodException(iface + "." + method.getName());
                int opcode = m.field() ? (m.setter() ? 181 : 180) : 182;
                List<Object> parameters = new ArrayList<>();
                parameters.add(self);
                if (args != null) parameters.addAll(Arrays.asList(args));
                return member(m.owner(), m.name(), m.desc(), opcode, "").invokeWithArguments(parameters);
            });
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot adapt " + iface, e); }
    }
    public static boolean isInstance(Object self, String iface) throws ClassNotFoundException {
        return self != null && Class.forName(plan.targets.get(iface).replace('/', '.'), false, Access.class.getClassLoader()).isInstance(self);
    }
}

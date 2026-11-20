package tenacity.inject;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.invoke.*;
import java.util.*;

/** Shared call boundary for retransformed game methods. Disabled until initialization succeeds. */
public final class Dispatcher {
    public static volatile boolean enabled;
    private static MethodHandle[] handlers;
    private static boolean[] instances;

    static void prepare(PortPlan plan, Companions compiler, ClassLoader game) throws Throwable {
        class Loader extends ClassLoader {
            Loader() { super(game); }
            Class<?> define(String name, byte[] bytes) { return defineClass(name, bytes, 0, bytes.length); }
        }
        Loader loader = new Loader();
        Map<String, Class<?>> classes = new HashMap<>();
        for (String name : plan.mixins.keySet()) {
            if (plan.accessors.containsKey(name)) continue;
            String helper = Companions.helper(name).replace('/', '.');
            classes.put(name, loader.define(helper, compiler.companion(name)));
        }
        handlers = new MethodHandle[plan.all.size()];
        instances = new boolean[handlers.length];
        for (PortPlan.Patch patch : plan.all) {
            boolean instance = (patch.handler().access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0;
            String desc = instance ? Bytecode.prepend(patch.handler().desc, "Ljava/lang/Object;") : patch.handler().desc;
            MethodType type = MethodType.fromMethodDescriptorString(desc, game);
            handlers[patch.id()] = MethodHandles.publicLookup().findStatic(classes.get(patch.mixin()), patch.handler().name, type);
            instances[patch.id()] = instance;
        }
    }
    public static Object call(int id, Object self, Object[] arguments) throws Throwable {
        if (instances[id]) {
            Object[] withSelf = new Object[arguments.length + 1];
            withSelf[0] = self;
            System.arraycopy(arguments, 0, withSelf, 1, arguments.length);
            return handlers[id].invokeWithArguments(withSelf);
        }
        return handlers[id].invokeWithArguments(arguments);
    }
    public static Operation<Object> operation(String owner, String name, String descriptor, int opcode, String caller) {
        return arguments -> {
            try { return Access.member(owner, name, descriptor, opcode, caller).invokeWithArguments(arguments); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Wrapped Minecraft call failed", failure); }
        };
    }
}

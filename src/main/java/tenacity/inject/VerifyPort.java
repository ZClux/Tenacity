package tenacity.inject;

import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.net.*;
import java.lang.invoke.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Offline coverage and bytecode verification against the exact intermediary Minecraft jar. */
public final class VerifyPort {
    public static void main(String[] args) throws Throwable {
        Path artifact = Path.of(args[0]);
        List<URL> urls = new ArrayList<>();
        urls.add(artifact.toUri().toURL());
        for (int i = 1; i < args.length; i++) urls.add(Path.of(args[i]).toUri().toURL());
        ClassLoader resources = new URLClassLoader(urls.toArray(URL[]::new), VerifyPort.class.getClassLoader());
        PortPlan plan;
        try (JarFile jar = new JarFile(artifact.toFile())) { plan = new PortPlan(jar); }
        Companions compiler = new Companions(plan, resources);
        PatchEngine engine = new PatchEngine(plan, resources);
        int methods = 0;
        Map<String, byte[]> transformed = new HashMap<>();
        for (String target : plan.patches.keySet()) {
            try (var in = resources.getResourceAsStream(target + ".class")) {
                if (in == null) throw new IllegalStateException("Missing target " + target);
                byte[] original = in.readAllBytes();
                byte[] patched = engine.apply(target, original);
                ClassNode before = Bytecode.read(original), after = Bytecode.read(patched);
                if (before.fields.size() != after.fields.size() || before.methods.size() != after.methods.size() || !before.interfaces.equals(after.interfaces))
                    throw new IllegalStateException("Schema changed for " + target);
                for (MethodNode method : after.methods) {
                    try { new Analyzer<>(new BasicVerifier()).analyze(after.name, method); }
                    catch (AnalyzerException failure) { throw new IllegalStateException("Invalid target " + target + "." + method.name + method.desc, failure); }
                    methods++;
                }
                transformed.put(target, patched);
            }
        }
        for (String name : plan.mixins.keySet()) if (!plan.accessors.containsKey(name)) {
            byte[] bytes = compiler.companion(name);
            ClassNode helper = Bytecode.read(bytes);
            for (MethodNode method : helper.methods) {
                try { new Analyzer<>(new BasicVerifier()).analyze(helper.name, method); }
                catch (AnalyzerException failure) { throw new IllegalStateException("Invalid companion " + name + "." + method.name, failure); }
                // A lambda handle on the original mixin's private synthetic method links lazily and fails with IllegalAccessError in game.
                for (var instruction : method.instructions) if (instruction instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null)
                    for (Object argument : indy.bsmArgs) if (argument instanceof org.objectweb.asm.Handle handle && !handle.getOwner().equals(helper.name) && (plan.mixins.containsKey(handle.getOwner())))
                        throw new IllegalStateException("Companion " + name + "." + method.name + " still calls " + handle.getOwner() + "." + handle.getName() + " from a lambda");
                methods++;
            }
            transformed.put(helper.name, bytes);
        }
        List<String> missing = plan.all.stream().filter(p -> !engine.covered.contains(p.id())).map(PortPlan.Patch::label).toList();
        if (!missing.isEmpty()) throw new IllegalStateException("Missing hook coverage: " + missing);
        // Check every mod class, including modules which use private members and accessor casts.
        try (JarFile jar = new JarFile(artifact.toFile())) {
            for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class") && !e.getName().startsWith("tenacity/inject/")).toList()) {
                ClassNode node = Bytecode.read(jar.getInputStream(entry).readAllBytes());
                if (plan.mixins.containsKey(node.name)) continue;
                if (compiler.adapt(node)) {
                    byte[] adapted = Bytecode.write(node, resources);
                    transformed.put(node.name, adapted);
                    ClassNode verified = Bytecode.read(adapted);
                    for (MethodNode method : verified.methods) {
                        new Analyzer<>(new BasicVerifier()).analyze(verified.name, method);
                        methods++;
                    }
                }
            }
        }
        // Ask the JVM to verify the output as well as ASM. Child-first loading keeps the
        // intermediary artifact separate from Gradle's named development classes.
        ClassLoader verification = new URLClassLoader(urls.toArray(URL[]::new), VerifyPort.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null && (name.startsWith("tenacity.") || name.startsWith("net.minecraft.class_"))) {
                        byte[] output = transformed.get(name.replace('.', '/'));
                        if (output != null) type = defineClass(name, output, 0, output.length);
                        else try { type = findClass(name); } catch (ClassNotFoundException ignored) {}
                    }
                    if (type == null) type = super.loadClass(name, false);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
        for (String name : transformed.keySet()) {
            try { Class.forName(name.replace('/', '.'), false, verification).getDeclaredMethods(); }
            catch (Throwable failure) { throw new IllegalStateException("JVM verification failed for " + name, failure); }
        }
        var bootstrap = Class.forName("tenacity.inject.Access", true, verification).getMethod("bootstrap", MethodHandles.Lookup.class, String.class,
                MethodType.class, String.class, String.class, String.class, int.class, String.class);
        int linked = 0;
        for (var methodsByName : plan.accessors.values()) for (PortPlan.Member member : methodsByName.values()) {
            String descriptor = member.field() ? (member.setter() ? "(Ljava/lang/Object;" + member.desc() + ")V" : "(Ljava/lang/Object;)" + member.desc())
                    : Bytecode.prepend(member.desc(), "Ljava/lang/Object;");
            bootstrap.invoke(null, MethodHandles.lookup(), "accessor", MethodType.fromMethodDescriptorString(descriptor, verification),
                    member.owner(), member.name(), member.desc(), member.field() ? (member.setter() ? 181 : 180) : 182, "");
            linked++;
        }
        for (byte[] bytes : transformed.values()) {
            ClassNode node = Bytecode.read(bytes);
            for (MethodNode method : node.methods) for (var instruction : method.instructions) {
                if (!(instruction instanceof InvokeDynamicInsnNode indy) || !indy.bsm.getOwner().equals(Companions.ACCESS)) continue;
                Object[] arguments = indy.bsmArgs;
                try {
                    bootstrap.invoke(null, MethodHandles.lookup(), indy.name, MethodType.fromMethodDescriptorString(indy.desc, verification),
                            arguments[0], arguments[1], arguments[2], arguments[3], arguments[4]);
                    linked++;
                } catch (Throwable failure) { throw new IllegalStateException("Cannot link private access in " + node.name + "." + method.name + ": " + Arrays.toString(arguments), failure); }
            }
        }
        Path report = artifact.getParent().resolve("injectable-verification.txt");
        List<String> lines = new ArrayList<>();
        lines.add("Mixin declarations: " + plan.mixins.size());
        lines.add("Injector declarations covered: " + engine.covered.size() + "/" + plan.all.size());
        lines.add("Accessor interfaces: " + plan.accessors.size());
        lines.add("Minecraft target classes: " + plan.patches.size());
        lines.add("Bytecode methods checked: " + methods);
        lines.add("Game class schemas unchanged.");
        lines.add("JVM verification passed for " + transformed.size() + " transformed classes.");
        lines.add("Private-member/state call sites linked: " + linked);
        for (var patch : plan.all) lines.add("OK " + patch.label() + " [" + patch.kind() + "]");
        Files.write(report, lines);
        lines.stream().limit(8).forEach(System.out::println);
    }
}

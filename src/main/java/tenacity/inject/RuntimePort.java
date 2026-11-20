package tenacity.inject;

import tenacity.Tenacity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;

/** Installs the complete declaration-driven runtime port, then initializes on Minecraft's thread. */
public final class RuntimePort {
    private static volatile Throwable failure;
    public static volatile boolean injected;

    public static synchronized void install(Instrumentation instrumentation, Path jar) throws Throwable {
        if (injected || Tenacity.initialized()) throw new IllegalStateException("Tenacity is already loaded, including a possible mods-folder installation.");
        if (!FabricLoader.getInstance().getMappingResolver().getCurrentRuntimeNamespace().equals("intermediary"))
            throw new IllegalStateException("Use the injectable jar with a normal Fabric 1.21.11 installation, not a named development runtime.");
        String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
        if (!version.equals("1.21.11")) throw new IllegalStateException("This build targets Minecraft 1.21.11, found " + version);
        if (!FabricLoader.getInstance().isModLoaded("fabric-api")) throw new IllegalStateException("Install Fabric API for Minecraft 1.21.11 before starting the game.");
        ClassLoader game = RuntimePort.class.getClassLoader();
        PortPlan plan;
        try (JarFile archive = new JarFile(jar.toFile())) { plan = new PortPlan(archive); }
        Access.plan = plan;
        Access.validateAccessors();
        Companions companions = new Companions(plan, game);
        PatchEngine engine = new PatchEngine(plan, game);
        // Fail before modifying the JVM if any declared hook cannot be resolved.
        for (String owner : plan.patches.keySet()) {
            try (InputStream input = game.getResourceAsStream(owner + ".class")) {
                if (input == null) throw new IllegalStateException("Missing Minecraft target " + owner);
                engine.apply(owner, input.readAllBytes());
            }
        }
        List<String> missing = plan.all.stream().filter(p -> !engine.covered.contains(p.id())).map(PortPlan.Patch::label).toList();
        if (!missing.isEmpty()) throw new IllegalStateException("Unresolved Tenacity hooks: " + String.join(", ", missing));
        // Resolve the boundary classes before a transformer can encounter them recursively.
        Class.forName(Dispatcher.class.getName(), true, game);
        instrumentation.addTransformer(companions, true);
        List<Class<?>> targets = new ArrayList<>();
        try {
            // Verifying companion methods may load module classes. Adapt them from their
            // first definition, and also revisit any mod classes loaded during bootstrap.
            Dispatcher.prepare(plan, companions, game);
            instrumentation.addTransformer(engine, true);
            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                String name = type.getName().replace('.', '/');
                boolean moduleClass = Companions.clientClass(name)
                        && !name.startsWith("tenacity/inject/") && !plan.mixins.containsKey(name);
                if (type.getClassLoader() != game || !(plan.patches.containsKey(name) || moduleClass)) continue;
                if (!instrumentation.isModifiableClass(type)) {
                    if (plan.patches.containsKey(name)) throw new IllegalStateException("Unmodifiable target " + type.getName());
                    continue; // JVM-generated lambda/hidden classes forward to the adapted module methods.
                }
                targets.add(type);
            }
            instrumentation.retransformClasses(targets.toArray(Class<?>[]::new));
            if (failure != null) throw new IllegalStateException("A runtime hook failed", failure);
            injected = true;
            CompletableFuture<Void> initialized = new CompletableFuture<>();
            MinecraftClient client = MinecraftClient.getInstance();
            client.execute(() -> {
                try {
                    Tenacity.runtimePending(true);
                    new Tenacity().onInitializeClient();
                    LateResources.install(client, jar).whenComplete((unused, resourceFailure) -> client.execute(() -> {
                        try {
                            if (resourceFailure != null) throw new IllegalStateException("Tenacity asset reload failed", resourceFailure);
                            Dispatcher.enabled = true;
                            Tenacity.runtimePending(false);
                            Tenacity.LOG.info("Tenacity runtime port ready: {} injector declarations, {} accessor interfaces, {} target classes", plan.all.size(), plan.accessors.size(), plan.patches.size());
                            initialized.complete(null);
                        } catch (Throwable error) { initialized.completeExceptionally(error); }
                    }));
                } catch (Throwable error) { initialized.completeExceptionally(error); }
            });
            initialized.get(60, TimeUnit.SECONDS);
        } catch (Throwable error) {
            Dispatcher.enabled = false;
            instrumentation.removeTransformer(engine);
            instrumentation.removeTransformer(companions);
            try { if (!targets.isEmpty()) instrumentation.retransformClasses(targets.toArray(Class<?>[]::new)); }
            catch (Throwable rollback) { error.addSuppressed(rollback); }
            throw error;
        }
    }
    static void fail(String message, Throwable error) {
        failure = error;
        Dispatcher.enabled = false;
        Tenacity.LOG.error(message, error);
    }
}

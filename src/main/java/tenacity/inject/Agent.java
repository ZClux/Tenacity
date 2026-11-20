package tenacity.inject;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Runs in the system loader; all game code is subsequently loaded by Fabric's loader. */
public final class Agent {
    private static boolean installed;

    public static synchronized void agentmain(String options, Instrumentation instrumentation) throws Exception {
        try {
            install(options,instrumentation);
        } catch (Throwable failure) {
            try {
                var trace=new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                Path location=Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Files.writeString(location.resolveSibling("tenacity-agent-error.log"),trace.toString());
            } catch (Throwable logFailure) { failure.addSuppressed(logFailure); }
            if(failure instanceof Exception exception)throw exception;
            throw new IllegalStateException("Tenacity agent initialization failed",failure);
        }
    }

    private static void install(String options, Instrumentation instrumentation) throws Exception {
        if (installed) throw new IllegalStateException("Tenacity is already attached. Restart Minecraft before attaching another build.");
        if (!instrumentation.isRetransformClassesSupported()) throw new IllegalStateException("This JVM does not support class retransformation.");
        ClassLoader game = null;
        for (Class<?> c : instrumentation.getAllLoadedClasses()) {
            if (c.getName().equals("net.minecraft.class_310") || c.getName().equals("net.minecraft.client.MinecraftClient")) {
                game = c.getClassLoader();
                break;
            }
        }
        if (game == null) throw new IllegalStateException("Start Minecraft 1.21.11 with Fabric and reach the title screen first.");
        // A previous attach into this game already defined Tenacity's runtime in Fabric's loader. Those
        // classes cannot be replaced, so any newer build would silently run the old code again.
        for (Class<?> c : instrumentation.getAllLoadedClasses())
            if (c.getName().equals("tenacity.inject.RuntimePort") && c.getClassLoader() == game)
                throw new IllegalStateException("Tenacity was already injected into this Minecraft once (it failed or is still loaded). Close Minecraft completely, start it again, then inject.");
        Path jar = Path.of(Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Class<?> base = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase", false, game);
        Object launcher = base.getMethod("getLauncher").invoke(null);
        Class<?> launcherType = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncher", false, game);
        var add = launcherType.getMethod("addToClassPath", Path.class, String[].class);
        add.invoke(launcher, jar, new String[0]);
        // Fabric only expands jar-in-jar dependencies during startup discovery. Expand our bundled
        // NanoVG dependency explicitly for a late load, including its platform natives.
        Path dependencies = Files.createTempDirectory("tenacity-nanovg-");
        dependencies.toFile().deleteOnExit();
        try (JarFile archive = new JarFile(jar.toFile())) {
            for (var e : archive.stream().filter(e -> e.getName().startsWith("META-INF/jars/") && e.getName().endsWith(".jar")).toList()) {
                Path out = dependencies.resolve(Path.of(e.getName()).getFileName().toString());
                try (var in = archive.getInputStream(e)) { Files.copy(in, out); }
                out.toFile().deleteOnExit();
                add.invoke(launcher, out, new String[0]);
            }
        }
        try {
            game.loadClass("tenacity.inject.RuntimePort").getMethod("install", Instrumentation.class, Path.class)
                    .invoke(null, instrumentation, jar);
            installed = true;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("Tenacity initialization failed", cause);
        }
    }
}

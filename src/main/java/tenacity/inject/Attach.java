package tenacity.inject;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.nio.file.Path;
import java.util.List;

/** Explicit, standard JDK Attach API entrypoint. Does not modify the target's JVM settings. */
public final class Attach {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("Usage: inject.bat [Minecraft PID]");
            System.out.println("Requires Minecraft 1.21.11, Fabric, Fabric API, and a Java 21+ JDK.");
            return;
        }
        Path jar = Path.of(Attach.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (args.length > 1 || (args.length == 1 && !args[0].matches("[0-9]+"))) {
            throw new IllegalArgumentException("Usage: java --add-modules jdk.attach -jar tenacity-client-injectable.jar [Minecraft PID]");
        }
        String pid;
        if (args.length == 1) pid = args[0];
        else {
            List<VirtualMachineDescriptor> games = VirtualMachine.list().stream()
                    .filter(v -> v.displayName().contains("net.fabricmc.loader") && v.displayName().contains("KnotClient"))
                    .toList();
            if (games.size() != 1) {
                System.err.println("Expected one Fabric Minecraft process; found " + games.size() + ".");
                for (var vm : VirtualMachine.list()) System.err.println(vm.id() + "  " + vm.displayName());
                throw new IllegalStateException("Run inject.bat with the Minecraft process ID, e.g. inject.bat 1234");
            }
            pid = games.getFirst().id();
        }
        // The game keeps the agent jar open for the rest of the session and reads classes from it lazily. Attach a private copy so
        // that rebuilding or replacing the jar next to the launcher cannot corrupt a running game ("invalid LOC header" crashes).
        Path runtime = stage(jar);
        VirtualMachine vm = VirtualMachine.attach(pid);
        try {
            vm.loadAgent(runtime.toString(), "");
        } catch (com.sun.tools.attach.AgentInitializationException failed) {
            // The agent writes why it failed next to the jar it was loaded from; show that line instead of a generic message.
            String reason = "";
            try {
                Path log = runtime.resolveSibling("tenacity-agent-error.log");
                if (java.nio.file.Files.exists(log)) reason = java.nio.file.Files.readAllLines(log).stream().findFirst().orElse("");
            } catch (java.io.IOException ignored) { }
            if (reason.contains("already loaded")) {
                System.err.println("Tenacity is already loaded in this Minecraft: it was attached earlier in this session, or tenacity-client-injectable.jar is"
                        + " in the game's mods folder. Close Minecraft, make sure that jar is not in its mods folder, start the game again and inject once.");
            } else {
                System.err.println("Tenacity loaded but could not initialize inside Minecraft: " + (reason.isEmpty() ? "see Minecraft's logs/latest.log"
                        + " (search for \"Cannot transform\" or \"Tenacity\")" : reason) + ". Restart Minecraft before trying again.");
            }
            throw failed;
        } finally {
            vm.detach();
        }
        System.out.println("Tenacity attached to process " + pid + ". Check Minecraft's latest.log for initialization status.");
    }

    /** Copies the jar to a uniquely named file in the temp directory and removes copies from sessions that are certainly over. */
    private static Path stage(Path jar) throws java.io.IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "tenacity-runtime");
        java.nio.file.Files.createDirectories(dir);
        long cutoff = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000;
        try (var old = java.nio.file.Files.newDirectoryStream(dir, "tenacity-*.jar")) {
            for (Path p : old) {
                try { if (java.nio.file.Files.getLastModifiedTime(p).toMillis() < cutoff) java.nio.file.Files.deleteIfExists(p); }
                catch (java.io.IOException ignored) { }
            }
        }
        Path copy = dir.resolve("tenacity-" + Long.toString(System.currentTimeMillis(), 36) + ".jar");
        java.nio.file.Files.copy(jar, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return copy;
    }
}

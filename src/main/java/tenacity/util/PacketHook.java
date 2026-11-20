package tenacity.util;

import net.minecraft.network.packet.Packet;
import tenacity.core.Module;
import tenacity.core.ModuleManager;

/**
 * Incoming packet hook. Called from the netty thread (see ClientConnectionPacketMixin) for every received packet,
 * before it is handed to the main thread. Enabled modules implementing {@link Receiver} get it; they must hop to the
 * client thread (mc.execute) before touching the world.
 */
public final class PacketHook {
    private PacketHook() {
    }

    public interface Receiver {
        void onPacket(Packet<?> packet);
    }

    public static void dispatch(Packet<?> packet) {
        for (Module m : ModuleManager.all()) {
            if (!(m instanceof Receiver r) || !m.enabled()) continue;
            try {
                r.onPacket(packet);
            } catch (Throwable t) {
                tenacity.Tenacity.LOG.error("module {} failed on packet {}", m.name, packet.getClass().getSimpleName(), t);
            }
        }
    }
}

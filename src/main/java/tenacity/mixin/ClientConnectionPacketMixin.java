package tenacity.mixin;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.util.PacketHook;

/** Feeds every received packet to {@link PacketHook} (netty thread). */
@Mixin(ClientConnection.class)
public class ClientConnectionPacketMixin {
    @Inject(method = "handlePacket", at = @At("HEAD"))
    private static void tenacity$onPacket(Packet<?> packet, PacketListener listener, CallbackInfo ci) {
        PacketHook.dispatch(packet);
    }
}

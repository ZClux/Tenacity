package tenacity.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tenacity.util.BlockUpdateHook;

/**
 * Reports server block changes (old state, new state) on the client thread, right before the packet is applied, so
 * modules can tell what actually changed (a chest lid opening, a block placed or broken...).
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientBlockUpdateMixin {
    @Inject(method = "onBlockUpdate", at = @At("HEAD"))
    private void tenacity$blockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        var world = MinecraftClient.getInstance().world;
        if (world == null) return;
        BlockPos pos = packet.getPos();
        BlockUpdateHook.dispatch(pos, world.getBlockState(pos), packet.getState());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("HEAD"))
    private void tenacity$deltaUpdate(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        var world = MinecraftClient.getInstance().world;
        if (world == null) return;
        packet.visitUpdates((pos, state) -> BlockUpdateHook.dispatch(pos, world.getBlockState(pos), state));
    }
}

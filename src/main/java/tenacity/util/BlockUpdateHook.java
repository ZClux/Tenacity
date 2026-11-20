package tenacity.util;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import tenacity.core.Module;
import tenacity.core.ModuleManager;

/** Server block changes with the previous state (see ClientBlockUpdateMixin). Called on the client thread. */
public final class BlockUpdateHook {
    private BlockUpdateHook() {
    }

    public interface Listener {
        void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState);
    }

    public static void dispatch(BlockPos pos, BlockState oldState, BlockState newState) {
        if (oldState == newState) return;
        BlockPos p = pos.toImmutable();
        for (Module m : ModuleManager.all()) {
            if (!(m instanceof Listener l) || !m.enabled()) continue;
            try {
                l.onBlockUpdate(p, oldState, newState);
            } catch (Throwable t) {
                tenacity.Tenacity.LOG.error("module {} failed on block update", m.name, t);
            }
        }
    }
}

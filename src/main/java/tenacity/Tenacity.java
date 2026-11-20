package tenacity;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tenacity.core.Config;
import tenacity.core.ModuleManager;
import tenacity.gfx.ItemAtlas;

public final class Tenacity implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("Tenacity");
    public static final String NAME = "Tenacity";
    public static final String VERSION = "1.0";

    private static volatile boolean initialized, runtimePending;
    public static boolean initialized() { return initialized; }
    public static boolean runtimePending() { return runtimePending; }
    public static void runtimePending(boolean pending) { runtimePending = pending; }

    @Override
    public void onInitializeClient() {
        initialized = true;
        ModuleManager.init();
        net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, ctx) -> {
            if (renderer instanceof net.minecraft.client.render.entity.PlayerEntityRenderer<?> pr)
                helper.register((net.minecraft.client.render.entity.feature.FeatureRenderer) new tenacity.gfx.XrayFeature((net.minecraft.client.render.entity.feature.FeatureRendererContext) pr));
        });
        Config.load();
        tenacity.modules.DiscordRpc.start();
        ClientTickEvents.START_CLIENT_TICK.register(ModuleManager::preTick);
        ClientTickEvents.END_CLIENT_TICK.register(ModuleManager::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
            Config.save();
            ItemAtlas.close();
        });
        LOG.info("Tenacity loaded - press Right Shift in game");
    }
}

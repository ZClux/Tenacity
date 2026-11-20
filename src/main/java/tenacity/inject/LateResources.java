package tenacity.inject;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.*;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.text.Text;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Makes injected shader/texture assets visible to Minecraft's resource manager and future reloads. */
final class LateResources {
    @SuppressWarnings("unchecked")
    static CompletableFuture<Void> install(MinecraftClient client, Path artifact) throws ReflectiveOperationException {
        ResourcePackInfo info = new ResourcePackInfo("tenacity_injected_assets", Text.literal("Tenacity assets"), ResourcePackSource.BUILTIN, Optional.empty());
        ResourcePackProfile profile = new ResourcePackProfile(info, new ZipResourcePack.ZipBackedFactory(artifact),
                new ResourcePackProfile.Metadata(Text.literal("Tenacity shaders and textures"), ResourcePackCompatibility.COMPATIBLE, FeatureSet.empty(), List.of()),
                new ResourcePackPosition(true, ResourcePackProfile.InsertionPosition.TOP, true));
        ResourcePackManager manager = client.getResourcePackManager();
        Field providers = Arrays.stream(ResourcePackManager.class.getDeclaredFields()).filter(f -> Set.class.isAssignableFrom(f.getType())).findFirst().orElseThrow();
        providers.setAccessible(true);
        Set<ResourcePackProvider> updated = new LinkedHashSet<>((Set<ResourcePackProvider>) providers.get(manager));
        updated.add(consumer -> consumer.accept(profile));
        providers.set(manager, Collections.unmodifiableSet(updated));
        manager.scanPacks();
        // Reload the resource listeners directly. Minecraft's convenience method installs
        // a SplashOverlay whose fade blocks the game and is unnecessary for late assets.
        if(client.getOverlay()!=null)throw new IllegalStateException("Wait for Minecraft's current loading screen to finish before injecting");
        var resources=(ReloadableResourceManagerImpl)client.getResourceManager();
        var reload=resources.reload(net.minecraft.util.Util.getMainWorkerExecutor(),client,
                CompletableFuture.completedFuture(net.minecraft.util.Unit.INSTANCE),manager.createResourcePacks());
        return reload.whenComplete().thenRunAsync(()->{if(client.world!=null)client.worldRenderer.reload();},client);
    }
}

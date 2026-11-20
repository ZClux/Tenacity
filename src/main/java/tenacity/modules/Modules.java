package tenacity.modules;

import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.hud.Huds;

import java.util.ArrayList;
import java.util.List;

/** Creates every module of the client. */
public final class Modules {
    private Modules() {
    }

    public static List<Module> create() {
        List<Module> all = new ArrayList<>();
        // testing build: only Sprint, Auto Tool and the visuals are real, every other tab holds placeholders
        all.addAll(SeasonalShaders.create());
        all.addAll(CryptoCombat.create());
        all.addAll(CombatModules.create());
        all.addAll(CryptoDonut.create());
        all.add(new MovementModules.Sprint());
        all.add(new MovementModules.SnapTap());
        all.addAll(CryptoMovement.create());
        all.add(new PlayerModules.AutoTool());
        all.addAll(RenderModules.create());
        all.addAll(VisualModules.create());
        all.addAll(MoreVisuals.create());
        all.addAll(PremiumVisuals.create());
        all.addAll(EvenMoreVisuals.create());
        all.addAll(UltraVisuals.create());
        all.addAll(ChristmasVisuals.create());
        all.add(new SnowfallFx());
        all.addAll(HalloweenVisuals.create());
        all.add(new CrystalShader());
        all.add(new WorldStyle());
        all.add(new CreamyKeys());
        all.add(new SkyVisuals());
        all.add(new SkyLyrics());
        all.add(new MusicVisualiser());
        all.add(new CryptoFreeCam());
        all.add(new MovementModules.FreeLook());
        all.add(new Knifes());
        all.add(new tenacity.modules.donut.SusChunkFinder());
        all.add(new NameProtect());
        all.add(new NameScrambler());
        all.add(new SkinProtect());
        all.add(new Interface());
        all.addAll(Huds.create());
        all.sort(java.util.Comparator.comparing((Module m) -> m.category.ordinal()).thenComparing(m -> m.name.equals("Interface") ? " " : m.name));
        return all;
    }

}

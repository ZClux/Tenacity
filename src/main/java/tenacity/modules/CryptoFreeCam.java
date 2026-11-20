package tenacity.modules;

import net.minecraft.client.option.Perspective;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import tenacity.core.Category;
import tenacity.core.Module;
import tenacity.core.Setting;

/**
 * Crypto's FreeCam, ported 1:1: the camera flies on its own (spectator style, scroll wheel changes the speed), the player
 * stays where it is (optionally frozen), the hand and crosshair keep rendering and chunks behind walls are not culled.
 */
public final class CryptoFreeCam extends Module {
    public static CryptoFreeCam INSTANCE;

    private final Setting.Num speedSetting = num("Speed", 2, 0.1, 20, 0.1);
    private final Setting.Bool smoothingSetting = bool("Smoothing", true);
    private final Setting.Bool freezeSetting = bool("Freeze", false);
    private final Setting.Bool showPlayerModel = bool("Show Player", true);
    private final Setting.Bool ogFreecam = bool("OG Freecam", false);

    public Vec3d pos, prevPos;
    private float yaw, pitch;
    private float inputForward, inputSideways;
    private boolean inputJump, inputSneak;
    private boolean savedChunkCulling, lastOgFreecam;
    private net.minecraft.client.world.ClientWorld world;

    public CryptoFreeCam() {
        super("Freecam", "Crypto's free camera: fly the camera around while your player stays put", Category.RENDER);
        INSTANCE = this;
    }

    public static boolean on() {
        return INSTANCE != null && INSTANCE.enabled();
    }

    /** The mouse turns the camera every frame, so the newest rotation is used directly (no tick interpolation lag). */
    public float getInterpolatedYaw(float tickDelta) {
        return yaw;
    }

    public float getInterpolatedPitch(float tickDelta) {
        return pitch;
    }

    public boolean isShowPlayerModel() {
        return showPlayerModel.on();
    }

    public boolean isOgFreecam() {
        return ogFreecam.on();
    }

    public boolean isFrozen() {
        return freezeSetting.on();
    }

    @Override
    protected void onEnable() {
        if (mc.options != null && showPlayerModel.on()) mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        savedChunkCulling = mc.chunkCullingEnabled;
        lastOgFreecam = ogFreecam.on();
        mc.chunkCullingEnabled = lastOgFreecam && savedChunkCulling;
        if (mc.worldRenderer != null) mc.worldRenderer.scheduleTerrainUpdate();
        world = mc.world;
        pos = prevPos = null;
        if (mc.gameRenderer != null && mc.player != null) {
            pos = prevPos = mc.gameRenderer.getCamera().getCameraPos();
            yaw = mc.player.getYaw();
            pitch = mc.player.getPitch();
        }
    }

    @Override
    protected void onDisable() {
        if (mc.options != null) mc.options.setPerspective(Perspective.FIRST_PERSON);
        mc.chunkCullingEnabled = savedChunkCulling;
        lastOgFreecam = false;
        if (mc.worldRenderer != null) mc.worldRenderer.scheduleTerrainUpdate();
        inputForward = inputSideways = 0;
        inputJump = inputSneak = false;
        pos = prevPos = null;
    }

    @Override
    public void tick() {
        // Crypto turns FreeCam off on respawn / joining a world
        if (world != null && mc.world != world) {
            setEnabled(false);
            return;
        }
        world = mc.world;
        if (pos == null) {
            pos = prevPos = mc.gameRenderer.getCamera().getCameraPos();
            yaw = mc.player.getYaw();
            pitch = mc.player.getPitch();
        }
        boolean og = ogFreecam.on();
        if (og != lastOgFreecam) {
            mc.chunkCullingEnabled = og && savedChunkCulling;
            lastOgFreecam = og;
            if (mc.worldRenderer != null) mc.worldRenderer.scheduleTerrainUpdate();
        }
        prevPos = pos;
        float speed = speedSetting.f() * 0.2f;
        float yawRad = (float) Math.toRadians(yaw);
        double sinYaw = Math.sin(yawRad), cosYaw = Math.cos(yawRad);
        double targetX = (inputSideways * cosYaw - inputForward * sinYaw) * speed;
        double targetZ = (inputForward * cosYaw + inputSideways * sinYaw) * speed;
        double targetY = inputJump ? speed : inputSneak ? -speed : 0;
        pos = pos.add(targetX, targetY, targetZ);
    }

    /** KeyboardInput: the camera takes the movement keys, the player gets none. */
    public PlayerInput onInput(PlayerInput input) {
        inputForward = input.forward() ? 1 : input.backward() ? -1 : 0;
        inputSideways = input.left() ? 1 : input.right() ? -1 : 0;
        inputJump = input.jump();
        inputSneak = input.sneak();
        return new PlayerInput(false, false, false, false, false, false, false);
    }

    /** Mouse: turns the free camera with the scaled mouse delta (* 0.15); the player look direction stays. */
    public void onMouse(double dx, double dy) {
        if (mc.currentScreen != null) return;
        yaw += (float) dx * 0.15f;
        pitch = MathHelper.clamp(pitch + (float) dy * 0.15f, -90f, 90f);
    }

    /** Scroll wheel: changes the fly speed instead of the hotbar slot. */
    public void onScroll(double vertical) {
        speedSetting.set(Math.max(0.1, Math.min(20.0, speedSetting.value + vertical * 0.5)));
    }

    public Vec3d interpolatedPos(float tickDelta) {
        return prevPos.lerp(pos, tickDelta);
    }
}

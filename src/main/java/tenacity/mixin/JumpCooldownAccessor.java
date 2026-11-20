package tenacity.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Write access to the jump cooldown for No Jump Delay. */
@Mixin(LivingEntity.class)
public interface JumpCooldownAccessor {
    @Accessor("jumpingCooldown")
    void tenacity$setJumpingCooldown(int cooldown);
}

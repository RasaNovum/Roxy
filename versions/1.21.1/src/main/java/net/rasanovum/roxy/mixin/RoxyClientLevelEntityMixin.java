package net.rasanovum.roxy.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.rasanovum.roxy.compat.RoxyPowerGridCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class RoxyClientLevelEntityMixin {
    @Inject(method = "addEntity", at = @At("TAIL"))
    private void roxy$trackPowerGridWire(Entity entity, CallbackInfo ci) {
        RoxyPowerGridCompat.track(entity);
    }

    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void roxy$cachePowerGridWire(int id, Entity.RemovalReason reason, CallbackInfo ci) {
        ClientLevel level = (ClientLevel) (Object) this;
        RoxyPowerGridCompat.cache(level.getEntity(id), reason);
    }
}

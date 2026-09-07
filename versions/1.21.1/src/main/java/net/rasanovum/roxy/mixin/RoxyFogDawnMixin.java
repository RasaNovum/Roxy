package net.rasanovum.roxy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.rasanovum.roxy.fog.RoxyFogDawn;
import net.rasanovum.roxy.fog.RoxyVoxyFogPatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.imb11.fog.client.FogManager", remap = false)
public final class RoxyFogDawnMixin {
    @Inject(method = "getBlendFactor", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void roxy$laterDawn(ClientLevel level, CallbackInfoReturnable<Float> cir) {
        if (RoxyVoxyFogPatch.fadeFogHorizon()) {
            cir.setReturnValue(RoxyFogDawn.dayBlend(cir.getReturnValueF(), level.getDayTime()));
        }
    }

    @Inject(method = "blendFogColorWithSunriseSunsetColors", at = @At("RETURN"), require = 0, remap = false)
    private void roxy$fadeSunrise(Minecraft minecraft, float red, float green, float blue, float partialTick,
                                 CallbackInfoReturnable<float[]> cir) {
        if (minecraft.level == null || !RoxyVoxyFogPatch.fadeFogHorizon()) return;
        float strength = RoxyFogDawn.sunriseInfluence(minecraft.level.getDayTime(), partialTick);
        if (strength == 1) return;
        float[] result = cir.getReturnValue();
        result[0] = red + (result[0] - red) * strength;
        result[1] = green + (result[1] - green) * strength;
        result[2] = blue + (result[2] - blue) * strength;
    }
}

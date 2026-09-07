package net.rasanovum.roxy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import net.rasanovum.roxy.fog.RoxyVoxyFogPatch;
import net.rasanovum.roxy.fog.RoxyWeatherFog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Slice;

@Pseudo
@Mixin(targets = "dev.imb11.fog.client.FogManager", remap = false)
public final class RoxyFogWeatherMixin {
    @WrapOperation(method = "getFogSettings", remap = false, require = 0,
            slice = @Slice(from = @At(value = "FIELD", target = "Ldev/imb11/fog/client/FogManager;raininess:Ldev/imb11/fog/client/util/math/InterpolatedValue;")),
            at = @At(value = "INVOKE", target = "Ldev/imb11/fog/client/util/math/InterpolatedValue;get(F)F", ordinal = 0))
    private float roxy$weatherTiming(@Coerce Object value, float partialTick, Operation<Float> original) {
        float nativeValue = original.call(value, partialTick);
        if (!RoxyVoxyFogPatch.synchronizeWeatherColor()) return nativeValue;
        var minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        var player = minecraft.player;
        if (level == null || player == null || !level.dimensionType().hasSkyLight()
                || minecraft.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE
                || !level.getBiome(player.blockPosition()).value().hasPrecipitation()) return nativeValue;
        return RoxyWeatherFog.colorProgress(level.getRainLevel(partialTick), level.getThunderLevel(partialTick));
    }
}

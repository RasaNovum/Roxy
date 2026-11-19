package net.rasanovum.roxy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.rasanovum.roxy.compat.RoxyCompatConfig;
import net.rasanovum.roxy.shader.RoxyAmdHiZShader;
import net.rasanovum.roxy.shader.RoxyVoxyRequestShader;
import net.rasanovum.roxy.shader.RoxyFogShader;
import net.rasanovum.roxy.shader.RoxyTerrainFogShader;
import net.rasanovum.roxy.shader.RoxyIceFaceCullingShader;
import net.rasanovum.roxy.shader.RoxyModelTintShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader$ShaderLoadingParser", remap = false)
public final class RoxyShaderResourceMixin {
    @Redirect(
            method = "loadShaderAsset",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Class;getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"
            ),
            remap = false
    )
    private static InputStream roxy$resolveShaderResource(Class<?> owner, String path) {
        InputStream input = owner.getResourceAsStream(path);
        if (input != null) return roxy$patchShaderResource(path, input);

        input = roxy$openFromResourceManager(path);
        if (input != null) return roxy$patchShaderResource(path, input);

        try {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            Class<?> sodiumShaderLoader = Class.forName(
                    "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader",
                    false,
                    contextLoader
            );
            ClassLoader sodiumLoader = sodiumShaderLoader.getClassLoader();
            if (sodiumLoader != null) {
                input = sodiumLoader.getResourceAsStream(path);
                if (input != null) return roxy$patchShaderResource(path, input);
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
        }

        return roxy$patchShaderResource(
                path,
                RoxyShaderResourceMixin.class.getClassLoader().getResourceAsStream(path)
        );
    }

    private static InputStream roxy$patchShaderResource(String path, InputStream input) {
        if (input == null) return input;

        try {
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            source = RoxyAmdHiZShader.patch(path, source);
            source = RoxyVoxyRequestShader.patch(path, source);
            source = RoxyFogShader.patch(path, source);
            source = RoxyTerrainFogShader.patch(path, source);
            source = RoxyIceFaceCullingShader.patch(path, source);
            source = RoxyModelTintShader.patch(path, source);
            boolean quadUtil = path.endsWith("/assets/voxy/shaders/lod/quad_util.glsl");
            if (quadUtil) {
                source = source.replace(
                        "quad.basePoint = (quadStart*lodScale)+vec3(baseSection<<5);",
                        "quad.basePoint = (quadStart*lodScale)+vec3(baseSection<<5)-vec3(0.0, lodScale-1.0, 0.0);"
                );
            }
            if (quadUtil && RoxyCompatConfig.terrainSlabsEnabled()) {
                String quadStart = "vec3 quadStart = extractPos(rawQuad);";
                if (!source.contains(quadStart)) {
                    throw new IllegalStateException("Unsupported Voxy terrain snow quad shader layout");
                }
                source = source.replace(
                        quadStart,
                        """
                        vec3 quadStart = extractPos(rawQuad);
                        #ifdef QUAD_DATA_USE_64_BIT
                        uint loweredSnow = Eu32(rawQuad, 1, 63);
                        #else
                        uint loweredSnow = Eu32v(rawQuad, 1, 63);
                        #endif
                        if (generateAttributes && loweredSnow != 0u && quadStart.y == 0.0) {
                            quad.attributeData.x |= 0x80u;
                        }
                        quadStart.y -= float(loweredSnow) * 0.5;
                        """.stripTrailing()
                );
            }
            return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return input;
        }
    }

    private static InputStream roxy$openFromResourceManager(String path) {
        if (!path.startsWith("/assets/")) return null;

        String resourcePath = path.substring("/assets/".length());
        int separator = resourcePath.indexOf('/');
        if (separator <= 0 || separator == resourcePath.length() - 1) return null;

        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.getResourceManager() == null) return null;
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(
                    resourcePath.substring(0, separator),
                    resourcePath.substring(separator + 1)
            );
            return minecraft.getResourceManager().open(id);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}

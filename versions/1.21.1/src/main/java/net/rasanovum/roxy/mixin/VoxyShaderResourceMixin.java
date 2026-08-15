package net.rasanovum.roxy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.io.InputStream;


@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader$ShaderLoadingParser", remap = false)
public final class VoxyShaderResourceMixin {
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
            // Sodium is a required dependency for Voxy, but keep the original missing-resource failure if its classloader is unavailable.
        }

        return roxy$patchShaderResource(
                path,
                VoxyShaderResourceMixin.class.getClassLoader().getResourceAsStream(path)
        );
    }

    private static InputStream roxy$patchShaderResource(String path, InputStream input) {
        return input;
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

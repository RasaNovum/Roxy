package net.rasanovum.roxy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


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
        if (input == null || path == null
                || (!path.endsWith("assets/voxy/shaders/lod/quad_util.glsl")
                && !path.endsWith("assets/voxy/shaders/lod/gl46/quads.frag"))) {
            return input;
        }

        try (InputStream sourceInput = input) {
            String source = new String(sourceInput.readAllBytes(), StandardCharsets.UTF_8);
            String patched = source;

            if (path.endsWith("assets/voxy/shaders/lod/quad_util.glsl")) {
                patched = patched.replace(
                        """
                        vec4 getFaceSize(uint faceData) {
                            float EPSILON = 0.00005f;

                            vec4 faceOffsetsSizes = extractFaceSizes(faceData);

                            //Expand the quads by a very small amount (because of the subtraction after this also becomes an implicit add)
                            faceOffsetsSizes.xz -= vec2(EPSILON);

                            //Make the end relative to the start
                            faceOffsetsSizes.yw -= faceOffsetsSizes.xz;

                            return faceOffsetsSizes;
                        }
                        """,
                        """
                        vec4 getFaceSize(uint faceData) {
                            vec4 faceOffsetsSizes = extractFaceSizes(faceData);

                            //Make the end relative to the start
                            faceOffsetsSizes.yw -= faceOffsetsSizes.xz;

                            return faceOffsetsSizes;
                        }
                        """
                );
                patched = patched.replace(
                        """
                            vec4 faceSize = getFaceSize(faceData);
                            #ifdef USE_SINGLE_TRI
                        """,
                        """
                            vec4 faceSize = getFaceSize(faceData);
                            {
                                vec2 faceUV = faceSize.xz;
                                // Keep the seam correction constant in world-block units at every LOD.
                                float scaledEpsilon = 0.00005 / lodScale;
                                faceSize.xz -= vec2(scaledEpsilon);
                                faceSize.yw += vec2(scaledEpsilon);

                            #ifdef USE_SINGLE_TRI
                        """
                );
                patched = patched.replace(
                        "quad.uvCorner = faceSize.xz;",
                        """
                            quad.uvCorner = faceUV;
                        }
                        """
                );
            }

            if (path.endsWith("assets/voxy/shaders/lod/gl46/quads.frag")) {
                patched = patched.replace(
                        "vec2 texPos = uv2 + getBaseUV();",
                        """
                        vec2 baseUV = getBaseUV();
                        vec2 atlasTexel = 1.0 / vec2(textureSize(blockModelAtlas, 0));
                        vec2 faceCellSize = 1.0 / (vec2(3.0, 2.0) * 256.0);
                        vec2 texPos = clamp(uv2 + baseUV, baseUV + atlasTexel * 0.5, baseUV + faceCellSize - atlasTexel * 0.5);
                        """
                );
                patched = patched.replace(
                        """
                                vec2 dy = dFdy(uvSmol);//vec2(lDy, dDy);
                                colour = textureGrad(blockModelAtlas, texPos, dx, dy);
                        """,
                        """
                                vec2 dy = dFdy(uvSmol);//vec2(lDy, dDy);
                        #ifndef PATCHED_SHADER
                                // Add a small per-LOD mip bias to stabilize distant atlas sampling.
                                uint lodBits = (interData.w >> 3u) & 7u;
                                float mipBiasScale = exp2(float(lodBits) * 0.5);
                                dx *= mipBiasScale;
                                dy *= mipBiasScale;
                        #endif
                                colour = textureGrad(blockModelAtlas, texPos, dx, dy);
                        """
                );
            }

            return new ByteArrayInputStream(patched.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to patch Voxy shader resource " + path, exception);
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

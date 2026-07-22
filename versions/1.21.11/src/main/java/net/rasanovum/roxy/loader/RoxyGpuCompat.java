package net.rasanovum.roxy.loader;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlDevice;

import java.lang.reflect.InvocationTargetException;

public final class RoxyGpuCompat {
    private static final String VALIDATION_TEXTURE =
            "net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuTexture";
    private static final String VALIDATION_DEVICE =
            "net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuDevice";

    private RoxyGpuCompat() {
    }

    public static GlTexture unwrapGlTexture(Object texture) {
        return (GlTexture) unwrapValidationObject(texture, VALIDATION_TEXTURE,
                "GPU texture");
    }

    public static GlDevice unwrapGlDevice(Object device) {
        return (GlDevice) unwrapValidationObject(device, VALIDATION_DEVICE,
                "GPU device");
    }

    private static Object unwrapValidationObject(Object value, String wrapperClassName,
                                                   String valueName) {
        Object realValue = value;
        if (realValue != null && realValue.getClass().getName().equals(wrapperClassName)) {
            try {
                realValue = realValue.getClass().getMethod("getRealDevice").invoke(realValue);
            } catch (NoSuchMethodException exception) {
                try {
                    realValue = realValue.getClass().getMethod("getRealTexture").invoke(realValue);
                } catch (ReflectiveOperationException nestedException) {
                    throw reflectionFailure(valueName, nestedException);
                }
            } catch (ReflectiveOperationException exception) {
                throw reflectionFailure(valueName, exception);
            }
        }
        return realValue;
    }

    private static IllegalStateException reflectionFailure(String valueName,
                                                            ReflectiveOperationException exception) {
        Throwable cause = exception instanceof InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : exception;
        return new IllegalStateException("Unable to unwrap NeoForge GPU " + valueName
                + " validation wrapper", cause);
    }
}

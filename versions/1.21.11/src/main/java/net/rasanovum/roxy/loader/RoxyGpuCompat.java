package net.rasanovum.roxy.loader;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlTexture;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

public final class RoxyGpuCompat {
    private static final String VALIDATION_TEXTURE =
            "net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuTexture";
    private static final String VALIDATION_DEVICE =
            "net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuDevice";

    private static final ClassValue<MethodHandle> TEXTURE_UNWRAP = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            return resolveUnwrapper(type, "getRealTexture");
        }
    };

    private static final ClassValue<MethodHandle> DEVICE_UNWRAP = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> type) {
            return resolveUnwrapper(type, "getRealDevice");
        }
    };

    private RoxyGpuCompat() {
    }

    public static GlTexture unwrapGlTexture(Object texture) {
        if (texture == null || texture instanceof GlTexture) {
            return (GlTexture) texture;
        }
        if (texture.getClass().getName().equals(VALIDATION_TEXTURE)) {
            return (GlTexture) invokeUnwrapper(TEXTURE_UNWRAP.get(texture.getClass()), texture, "GPU texture");
        }
        return (GlTexture) texture;
    }

    public static GlDevice unwrapGlDevice(Object device) {
        if (device == null || device instanceof GlDevice) {
            return (GlDevice) device;
        }
        if (device.getClass().getName().equals(VALIDATION_DEVICE)) {
            return (GlDevice) invokeUnwrapper(DEVICE_UNWRAP.get(device.getClass()), device, "GPU device");
        }
        return (GlDevice) device;
    }

    private static MethodHandle resolveUnwrapper(Class<?> type, String methodName) {
        try {
            Method method = type.getMethod(methodName);
            return MethodHandles.publicLookup().unreflect(method);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to resolve NeoForge GPU validation unwrapper " + methodName, exception);
        }
    }

    private static Object invokeUnwrapper(MethodHandle handle, Object value, String valueName) {
        try {
            return handle.invoke(value);
        } catch (Error error) {
            throw error;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Unable to unwrap NeoForge GPU " + valueName + " validation wrapper", throwable);
        }
    }
}

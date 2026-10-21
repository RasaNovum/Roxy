package net.rasanovum.roxy.loader;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;

import java.util.Locale;

/**
 * Compatibility bridge for Voxy's direct JOML memory access on Apple Silicon macOS.
 * Minecraft mixins alter JOML object layouts, which makes JOML's Unsafe layout
 * checks fail during native reads or writes. These narrowly typed methods use
 * public component accessors and setters instead. Other operating systems
 * preserve the original JOML implementation.
 */
public final class RoxyJomlMemory {
    private static final boolean MACOS = isMacOS();

    private RoxyJomlMemory() {
    }

    private static boolean isMacOS() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") || os.contains("darwin");
    }

    public static Matrix4fc writeToAddress(Matrix4f value, long address) {
        if (!MACOS) return value.getToAddress(address);
        requireAddress(address);
        MemoryUtil.memPutFloat(address, value.m00());
        MemoryUtil.memPutFloat(address + 4, value.m01());
        MemoryUtil.memPutFloat(address + 8, value.m02());
        MemoryUtil.memPutFloat(address + 12, value.m03());
        MemoryUtil.memPutFloat(address + 16, value.m10());
        MemoryUtil.memPutFloat(address + 20, value.m11());
        MemoryUtil.memPutFloat(address + 24, value.m12());
        MemoryUtil.memPutFloat(address + 28, value.m13());
        MemoryUtil.memPutFloat(address + 32, value.m20());
        MemoryUtil.memPutFloat(address + 36, value.m21());
        MemoryUtil.memPutFloat(address + 40, value.m22());
        MemoryUtil.memPutFloat(address + 44, value.m23());
        MemoryUtil.memPutFloat(address + 48, value.m30());
        MemoryUtil.memPutFloat(address + 52, value.m31());
        MemoryUtil.memPutFloat(address + 56, value.m32());
        MemoryUtil.memPutFloat(address + 60, value.m33());
        return value;
    }

    public static Vector4fc writeToAddress(Vector4f value, long address) {
        if (!MACOS) return value.getToAddress(address);
        requireAddress(address);
        MemoryUtil.memPutFloat(address, value.x());
        MemoryUtil.memPutFloat(address + 4, value.y());
        MemoryUtil.memPutFloat(address + 8, value.z());
        MemoryUtil.memPutFloat(address + 12, value.w());
        return value;
    }

    public static Vector4f readFromAddress(Vector4f value, long address) {
        if (!MACOS) return value.setFromAddress(address);
        requireAddress(address);
        return value.set(MemoryUtil.memGetFloat(address), MemoryUtil.memGetFloat(address + 4),
                MemoryUtil.memGetFloat(address + 8), MemoryUtil.memGetFloat(address + 12));
    }

    public static Vector3fc writeToAddress(Vector3f value, long address) {
        if (!MACOS) return value.getToAddress(address);
        requireAddress(address);
        MemoryUtil.memPutFloat(address, value.x());
        MemoryUtil.memPutFloat(address + 4, value.y());
        MemoryUtil.memPutFloat(address + 8, value.z());
        return value;
    }

    public static Vector3f readFromAddress(Vector3f value, long address) {
        if (!MACOS) return value.setFromAddress(address);
        requireAddress(address);
        return value.set(MemoryUtil.memGetFloat(address), MemoryUtil.memGetFloat(address + 4),
                MemoryUtil.memGetFloat(address + 8));
    }

    public static Vector3ic writeToAddress(Vector3i value, long address) {
        if (!MACOS) return value.getToAddress(address);
        requireAddress(address);
        MemoryUtil.memPutInt(address, value.x());
        MemoryUtil.memPutInt(address + 4, value.y());
        MemoryUtil.memPutInt(address + 8, value.z());
        return value;
    }

    public static Vector2fc writeToAddress(Vector2f value, long address) {
        if (!MACOS) return value.getToAddress(address);
        requireAddress(address);
        MemoryUtil.memPutFloat(address, value.x());
        MemoryUtil.memPutFloat(address + 4, value.y());
        return value;
    }

    public static Vector2ic writeToAddress(Vector2i value, long address) {
        if (!MACOS) return value.getToAddress(address);
        requireAddress(address);
        MemoryUtil.memPutInt(address, value.x());
        MemoryUtil.memPutInt(address + 4, value.y());
        return value;
    }

    private static void requireAddress(long address) {
        if (address == MemoryUtil.NULL) {
            throw new IllegalArgumentException("Cannot access a JOML value at a null native address");
        }
    }
}

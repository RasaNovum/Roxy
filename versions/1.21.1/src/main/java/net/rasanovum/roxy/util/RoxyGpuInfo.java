package net.rasanovum.roxy.util;

import org.lwjgl.opengl.GL11;

import java.util.Locale;

public final class RoxyGpuInfo {
    private RoxyGpuInfo() {
    }

    public static Driver current() {
        try {
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            if (vendor == null && renderer == null) return null;
            return new Driver(vendor == null ? "" : vendor, renderer == null ? "" : renderer);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    public static boolean isAmd(String vendor, String renderer) {
        String vendorName = (vendor == null ? "" : vendor).toLowerCase(Locale.ROOT);
        String rendererName = (renderer == null ? "" : renderer).toLowerCase(Locale.ROOT);
        return vendorName.contains("advanced micro devices")
                || vendorName.startsWith("amd")
                || vendorName.contains("ati technologies")
                || rendererName.contains("radeon")
                || rendererName.contains("amd radv")
                || rendererName.startsWith("amd ")
                || rendererName.contains("(amd ");
    }

    public static boolean isMesa(String vendor, String renderer) {
        String vendorName = (vendor == null ? "" : vendor).toLowerCase(Locale.ROOT);
        String rendererName = (renderer == null ? "" : renderer).toLowerCase(Locale.ROOT);
        return vendorName.equals("amd")
                || vendorName.contains("mesa")
                || vendorName.contains("x.org")
                || rendererName.contains("mesa")
                || rendererName.contains("radeonsi")
                || rendererName.contains("radv")
                || rendererName.contains("zink");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    public static boolean isLinuxMesaAmd(Driver driver) {
        return driver != null && isLinux() && driver.isAmd() && driver.isMesa();
    }

    public record Driver(String vendor, String renderer) {
        public boolean isAmd() {
            return RoxyGpuInfo.isAmd(vendor, renderer);
        }

        public boolean isMesa() {
            return RoxyGpuInfo.isMesa(vendor, renderer);
        }
    }
}

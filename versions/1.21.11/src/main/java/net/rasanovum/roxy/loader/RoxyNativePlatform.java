package net.rasanovum.roxy.loader;

import java.util.Locale;

/** CPU and operating-system filtering for native JAR classifiers. */
final class RoxyNativePlatform {
    private RoxyNativePlatform() {
    }

    static boolean isCurrentPlatformJar(String path, String osName, String osArch) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!normalized.contains("-natives-")) return true;

        String os = osName.toLowerCase(Locale.ROOT);
        String architecture = normalizeArchitecture(osArch);
        if (os.contains("mac") || os.contains("darwin")) {
            return matchesNativePlatform(normalized, "macos", architecture)
                    || matchesNativePlatform(normalized, "osx", architecture);
        }
        if (os.contains("win")) return matchesNativePlatform(normalized, "windows", architecture);
        if (os.contains("linux") || os.contains("unix")) {
            return matchesNativePlatform(normalized, "linux", architecture);
        }
        return true;
    }

    private static String normalizeArchitecture(String architecture) {
        return switch (architecture.toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "arm", "arm32", "armv7l" -> "arm32";
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> "other";
        };
    }

    private static boolean matchesNativePlatform(String path, String platform, String architecture) {
        String marker = "-natives-" + platform;
        int platformIndex = path.indexOf(marker);
        if (platformIndex < 0) return false;
        String classifier = path.substring(platformIndex + marker.length());
        String nativeArchitecture;
        if (classifier.equals("-arm64.jar") || classifier.equals("-aarch64.jar")) {
            nativeArchitecture = "arm64";
        } else if (classifier.equals("-arm32.jar") || classifier.equals("-arm.jar")) {
            nativeArchitecture = "arm32";
        } else if (classifier.equals("-x86_64.jar") || classifier.equals("-x64.jar")) {
            nativeArchitecture = "x86_64";
        } else if (classifier.equals("-x86.jar")) {
            nativeArchitecture = "x86";
        } else if (classifier.equals(".jar")) {
            // Unqualified legacy classifiers are the platform's x86-64 build.
            nativeArchitecture = "x86_64";
        } else {
            return false;
        }
        return architecture.equals(nativeArchitecture);
    }
}

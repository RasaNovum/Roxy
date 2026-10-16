package net.rasanovum.roxy.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonArray;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.rasanovum.roxy.compat.RoxyBetterFogCompat;
import net.rasanovum.roxy.loader.RoxyMacOSGraphics;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

public final class RoxyClientWarnings {
    static final String MACOS_GUIDE_URL =
            "https://github.com/RasaNovum/Roxy/blob/macOS/Roxy-macOS-compat/setup-guide.md";
    private static final Set<String> LEGACY_WARNING_IDS = Set.of("environmental-fog", "amd-hyperz", "amd-flywheel");
    private static boolean shownThisSession;

    private RoxyClientWarnings() {
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (shownThisSession || !(event.getNewScreen() instanceof TitleScreen titleScreen)) return;
        List<Warning> warnings = detectWarnings();
        if (warnings.isEmpty()) return;

        String version = currentVersion();
        Set<String> dismissed = dismissedWarningIds(version);
        warnings.removeIf(warning -> dismissed.contains(warning.id()));
        if (warnings.isEmpty()) return;
        shownThisSession = true;
        event.setNewScreen(new RoxyWarningScreen(titleScreen, warnings, version));
    }

    static void dismiss(String version, List<Warning> warnings) {
        try {
            Path stateFile = stateFile();
            Files.createDirectories(stateFile.getParent());
            JsonObject json = new JsonObject();
            json.addProperty("dismissed_version", version);
            Set<String> ids = new HashSet<>(dismissedWarningIds(version));
            warnings.forEach(warning -> ids.add(warning.id()));
            JsonArray dismissed = new JsonArray();
            ids.stream().sorted().forEach(dismissed::add);
            json.add("dismissed_warning_ids", dismissed);
            Files.writeString(stateFile, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static List<Warning> detectWarnings() {
        List<Warning> warnings = new ArrayList<>();
        Warning macWarning = macOSWarning(RoxyMacOSGraphics.startupStatus());
        if (macWarning != null) warnings.add(macWarning);
        if (environmentalFogEnabled() && !RoxyBetterFogCompat.available()) {
            warnings.add(new Warning(
                    "environmental-fog",
                    Component.literal("use_environmental_fog").withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(" set to ").withStyle(ChatFormatting.WHITE))
                            .append(Component.literal("true").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal(" in Voxy config!").withStyle(ChatFormatting.WHITE)),
                    Component.literal("This will cause no distant LoDs to render if enabled. Set this option to ")
                            .append(Component.literal("false").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal(" in config or in the Sodium video settings. Certain fog mods may allow you to re-enable this."))
            ));
        }
        boolean amdGpu = amdGpuDetected();
        if (amdGpu && !amdNoHyperZEnabled()) {
            warnings.add(new Warning(
                    "amd-hyperz",
                    Component.literal("AMD GPU detected but no ").withStyle(ChatFormatting.WHITE)
                            .append(Component.literal("AMD_DEBUG=nohyperz").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal(" environment variable found!").withStyle(ChatFormatting.WHITE)),
                    Component.literal("AMD users, especially on older GPUs, will likely experience graphical issues with LOD rendering. Add the environment variable ")
                            .append(Component.literal("AMD_DEBUG=nohyperz").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal(" to resolve many of these issues."))
            ));
        }
        boolean createOrFlywheelPresent = ModList.get().isLoaded("create") || ModList.get().isLoaded("flywheel");
        String backendId = amdGpu && createOrFlywheelPresent ? flywheelBackendId() : null;
        if (shouldWarnAboutFlywheel(amdGpu, createOrFlywheelPresent, backendId)) {
            boolean indirectBackend = isIndirectBackend(backendId);
            warnings.add(new Warning(
                    "amd-flywheel",
                    indirectBackend
                            ? Component.literal("AMD GPU").withStyle(ChatFormatting.GOLD)
                                    .append(Component.literal(" detected with Flywheel's ").withStyle(ChatFormatting.WHITE))
                                    .append(Component.literal("Indirect").withStyle(ChatFormatting.YELLOW))
                                    .append(Component.literal(" backend!").withStyle(ChatFormatting.WHITE))
                            : Component.literal("AMD GPU & Flywheel compatibility warning").withStyle(ChatFormatting.YELLOW),
                    Component.literal("Users with AMD GPUs may encounter Flywheel rendering issues when using the ")
                            .append(Component.literal("Indirect").withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal(" backend alongside Roxy. Use the "))
                            .append(Component.literal("Instancing").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal(" backend instead to avoid these issues."))
            ));
        }
        return warnings;
    }

    private static Warning macOSWarning(RoxyMacOSGraphics.StartupStatus status) {
        String id = status.warningId();
        if (id == null) return null;
        String reason = switch (id) {
            case "macos-java-architecture" -> "This launch uses " + status.architecture()
                    + " Java. The macOS compatibility bundle requires native ARM64 Java 21.";
            case "macos-unsupported-graphics" -> "The active graphics context does not support Voxy LODs. "
                    + "Apple's built-in OpenGL 4.1 needs the macOS compatibility patch.";
            case "macos-voxy-unavailable" -> "Voxy did not enable LOD rendering after its startup checks. "
                    + "Check logs/latest.log for the reason.";
            default -> "Roxy could not verify Voxy's completed startup checks. "
                    + "Check logs/latest.log for the startup failure.";
        };
        return new Warning(id,
                Component.literal(id.equals("macos-startup-unverified")
                        ? "macOS LOD startup could not be verified" : "macOS LOD rendering is unavailable")
                        .withStyle(ChatFormatting.YELLOW),
                Component.literal(reason + "\nDetected: " + status.renderer()
                        + " / OpenGL " + status.openGlVersion() + ". ")
                        .append("\n\nUse ")
                        .append(Component.literal("Setup Guide ").withStyle(ChatFormatting.GOLD))
                        .append("to install the bundle and configure this instance's Wrapper command. Select ARM64 Java 21 and include the full script path ending in bin/roxy-macos-launch.sh.")
        );
    }

    static boolean shouldWarnAboutFlywheel(boolean amdGpu, boolean createOrFlywheelPresent, String backendId) {
        if (!amdGpu || !createOrFlywheelPresent) return false;
        return backendId == null || backendId.isBlank() || isIndirectBackend(backendId);
    }

    private static boolean isIndirectBackend(String backendId) {
        if (backendId == null || backendId.isBlank()) return false;
        String normalized = backendId.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("indirect") || normalized.endsWith(":indirect");
    }

    private static String flywheelBackendId() {
        try {
            Class<?> configClass = Class.forName("dev.engine_room.flywheel.impl.FlwConfig");
            Object config = configClass.getField("INSTANCE").get(null);
            Object backend = configClass.getMethod("backend").invoke(config);
            if (backend == null) return null;

            Class<?> backendClass = Class.forName("dev.engine_room.flywheel.api.backend.Backend");
            Object registry = backendClass.getField("REGISTRY").get(null);
            Class<?> registryClass = Class.forName("dev.engine_room.flywheel.api.registry.IdRegistry");
            Object id = registryClass.getMethod("getId", Object.class).invoke(registry, backend);
            return id == null ? null : id.toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean environmentalFogEnabled() {
        Path config = FMLPaths.CONFIGDIR.get().resolve("voxy-config.json");
        if (!Files.isRegularFile(config)) return false;
        try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return json.has("use_environmental_fog") && json.get("use_environmental_fog").getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean amdGpuDetected() {
        try {
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            String vendorName = (vendor == null ? "" : vendor).toLowerCase(Locale.ROOT);
            String rendererName = (renderer == null ? "" : renderer).toLowerCase(Locale.ROOT);
            return vendorName.contains("advanced micro devices")
                    || vendorName.startsWith("amd")
                    || vendorName.startsWith("ati technologies")
                    || rendererName.contains("radeon")
                    || rendererName.startsWith("amd ");
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean amdNoHyperZEnabled() {
        String value = System.getenv("AMD_DEBUG");
        return value != null && value.toLowerCase(Locale.ROOT).contains("nohyperz");
    }

    private static String currentVersion() {
        try {
            if (RoxyClientWarnings.class.getProtectionDomain().getCodeSource() != null) {
                Path source = Path.of(
                        RoxyClientWarnings.class.getProtectionDomain().getCodeSource().getLocation().toURI()
                );
                Path fileName = source.getFileName();
                if (fileName != null) {
                    String name = fileName.toString();
                    int marker = name.indexOf("-NeoForge-");
                    if (marker >= 0 && name.endsWith(".jar")) {
                        return name.substring(marker + "-NeoForge-".length(), name.length() - 4);
                    }
                }
            }
        } catch (URISyntaxException | RuntimeException ignored) {
        }
        return ModList.get().getModContainerById("roxy")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("development");
    }

    private static Path stateFile() {
        return FMLPaths.CONFIGDIR.get().resolve("roxy-warning-screen.json");
    }

    private static Set<String> dismissedWarningIds(String version) {
        Path stateFile = stateFile();
        if (!Files.isRegularFile(stateFile)) return Set.of();
        try (Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return dismissedWarningIds(json, version);
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private static Set<String> dismissedWarningIds(JsonObject json, String version) {
        if (!json.has("dismissed_version") || !version.equals(json.get("dismissed_version").getAsString())) {
            return Set.of();
        }
        // An old version-wide dismissal must not hide the new macOS setup warning.
        if (!json.has("dismissed_warning_ids")) return LEGACY_WARNING_IDS;
        Set<String> ids = new HashSet<>();
        json.getAsJsonArray("dismissed_warning_ids").forEach(id -> ids.add(id.getAsString()));
        return Set.copyOf(ids);
    }

    public record Warning(String id, Component title, Component tooltip) {
        public boolean macOSSetup() {
            return id.startsWith("macos-");
        }
    }
}

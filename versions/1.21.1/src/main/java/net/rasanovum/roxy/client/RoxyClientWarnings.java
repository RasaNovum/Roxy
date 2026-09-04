package net.rasanovum.roxy.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
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

public final class RoxyClientWarnings {
    private static final Path STATE_FILE = FMLPaths.CONFIGDIR.get().resolve("roxy-warning-screen.json");
    private static boolean shownThisSession;

    private RoxyClientWarnings() {
    }

    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (shownThisSession || !(event.getNewScreen() instanceof TitleScreen titleScreen)) return;
        List<Warning> warnings = detectWarnings();
        if (warnings.isEmpty()) return;

        String version = currentVersion();
        if (isDismissed(version)) return;
        shownThisSession = true;
        event.setNewScreen(new RoxyWarningScreen(titleScreen, warnings, version));
    }

    static void dismiss(String version) {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            JsonObject json = new JsonObject();
            json.addProperty("dismissed_version", version);
            Files.writeString(STATE_FILE, json.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static List<Warning> detectWarnings() {
        List<Warning> warnings = new ArrayList<>();
        if (environmentalFogEnabled()) {
            warnings.add(new Warning(
                    Component.literal("use_environmental_fog").withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(" set to ").withStyle(ChatFormatting.WHITE))
                            .append(Component.literal("true").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal(" in Voxy config!").withStyle(ChatFormatting.WHITE)),
                    Component.literal("This will cause no distant LoDs to render if enabled. Set this option to ")
                            .append(Component.literal("false").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal(" in config or in the Sodium video settings."))
            ));
        }
        if (amdGpuDetected() && !amdNoHyperZEnabled()) {
            warnings.add(new Warning(
                    Component.literal("AMD GPU detected but no ").withStyle(ChatFormatting.WHITE)
                            .append(Component.literal("AMD_DEBUG=nohyperz").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal(" environment variable found!").withStyle(ChatFormatting.WHITE)),
                    Component.literal("AMD users with Mesa drivers will likely experience graphical issues with LoD rendering. Add the environment variable ")
                            .append(Component.literal("AMD_DEBUG=nohyperz").withStyle(ChatFormatting.GOLD))
                            .append(Component.literal(" to resolve many of these issues."))
            ));
        }
        return warnings;
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

    private static boolean isDismissed(String version) {
        if (!Files.isRegularFile(STATE_FILE)) return false;
        try (Reader reader = Files.newBufferedReader(STATE_FILE, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return json.has("dismissed_version") && version.equals(json.get("dismissed_version").getAsString());
        } catch (Exception ignored) {
            return false;
        }
    }

    public record Warning(Component title, Component tooltip) {
    }
}

package net.rasanovum.roxy.tfc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Persistent client switch for the optional TFC day-cycle LoD integration. */
public final class TfcCompatConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "roxy-tfc.json";
    private static volatile Settings settings;

    private TfcCompatConfig() {}

    public static boolean installed() {
        try {
            LoadingModList mods = LoadingModList.get();
            return mods != null && mods.getModFileById("tfc") != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static synchronized Settings get() {
        if (settings == null) settings = read(configPath());
        return settings;
    }

    public static boolean enabled() {
        return installed() && get().enabled;
    }

    public static boolean showProgress() {
        return get().showProgress;
    }

    public static void setEnabled(boolean enabled) {
        get().enabled = enabled;
    }

    public static void setShowProgress(boolean showProgress) {
        get().showProgress = showProgress;
    }

    public static synchronized void save() {
        write(configPath(), get());
    }

    public static Settings read(Path path) {
        if (!Files.isRegularFile(path)) return new Settings();
        try (var reader = Files.newBufferedReader(path)) {
            Settings result = GSON.fromJson(reader, Settings.class);
            return result == null ? new Settings() : result;
        } catch (IOException | RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger("Roxy")
                    .warn("Unable to read Roxy TFC compatibility options; using defaults", exception);
            return new Settings();
        }
    }

    public static void write(Path path, Settings value) {
        Path temporary = null;
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "roxy-tfc-", ".tmp");
            Files.writeString(temporary, GSON.toJson(value));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save Roxy TFC compatibility options", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
        }
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    public static final class Settings {
        public volatile boolean enabled;
        public volatile boolean showProgress = true;
    }
}

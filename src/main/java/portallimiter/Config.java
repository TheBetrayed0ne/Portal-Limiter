package portallimiter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("portallimiter.json");

    private static volatile Config instance;
    private static Thread watchThread;

    public int maxOpenPortals = 10;
    public int maxOpenEndPortals = 3;
    /** "OLDEST" or "NEWEST": which existing portals get closed first when a config reload drops the count below the current total. */
    public String excessPortalCloseOrder = "OLDEST";

    public boolean closeOldestFirst() {
        return !"NEWEST".equalsIgnoreCase(excessPortalCloseOrder);
    }

    public static Config get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static void reload() {
        instance = load();
        PortalLimiter.enforcePortalLimits();
    }

    /**
     * Starts a background watcher that reloads the config whenever portallimiter.json changes
     * on disk, so edits take effect without needing the /portallimiter reload command. Safe to
     * call more than once; only the first call starts the watcher.
     */
    public static synchronized void startWatching() {
        if (watchThread != null) {
            return;
        }
        watchThread = new Thread(Config::watchLoop, "portallimiter-config-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private static void watchLoop() {
        Path dir = PATH.getParent();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            PortalLimiter.LOGGER.warn("Could not create config directory, config auto-reload is disabled", e);
            return;
        }

        try (WatchService watchService = dir.getFileSystem().newWatchService()) {
            dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);

            while (true) {
                WatchKey key = watchService.take();
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path changed && changed.getFileName().equals(PATH.getFileName())) {
                        relevant = true;
                    }
                }
                key.reset();

                if (relevant) {
                    // Some editors write in multiple steps (temp file + rename, truncate + write).
                    // Give that a moment to settle before reading the file back.
                    Thread.sleep(50);
                    reloadSafely();
                }
            }
        } catch (InterruptedException e) {
            // Watcher thread is being shut down.
        } catch (IOException e) {
            PortalLimiter.LOGGER.warn("PortalLimiter config watcher stopped unexpectedly", e);
        }
    }

    private static void reloadSafely() {
        try {
            Config reloaded = load();
            instance = reloaded;
            PortalLimiter.LOGGER.info("PortalLimiter config auto-reloaded from disk: max open nether portals: {}, max open end portals: {}",
                    reloaded.maxOpenPortals, reloaded.maxOpenEndPortals);
            PortalLimiter.enforcePortalLimits();
        } catch (Exception e) {
            PortalLimiter.LOGGER.warn("Failed to auto-reload PortalLimiter config (file may be mid-write or invalid), keeping previous values: {}", e.toString());
        }
    }

    private static Config load() {
        Config config = new Config();
        boolean existed = Files.exists(PATH);
        if (existed) {
            try {
                String json = Files.readString(PATH, StandardCharsets.UTF_8);
                Config loaded = GSON.fromJson(json, Config.class);
                if (loaded != null) {
                    config = loaded;
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read PortalLimiter config at " + PATH, e);
            } catch (JsonParseException e) {
                PortalLimiter.LOGGER.warn("PortalLimiter config at {} is invalid JSON, falling back to defaults: {}", PATH, e.toString());
            }
        }
        boolean changed = config.sanitize();
        if (!existed || changed) {
            config.save();
        }
        return config;
    }

    private boolean sanitize() {
        boolean changed = false;
        if (maxOpenPortals < 1) {
            maxOpenPortals = 1;
            changed = true;
        }
        if (maxOpenEndPortals < 1) {
            maxOpenEndPortals = 1;
            changed = true;
        }
        if (!"OLDEST".equalsIgnoreCase(excessPortalCloseOrder) && !"NEWEST".equalsIgnoreCase(excessPortalCloseOrder)) {
            excessPortalCloseOrder = "OLDEST";
            changed = true;
        }
        return changed;
    }

    public void save() {
        sanitize();
        try {
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write PortalLimiter config at " + PATH, e);
        }
    }
}

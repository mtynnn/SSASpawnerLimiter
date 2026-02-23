package github.io.ssaspawnerlimiter;

import github.io.ssaspawnerlimiter.command.BrigadierCommandManager;
import github.io.ssaspawnerlimiter.database.DatabaseManager;
import github.io.ssaspawnerlimiter.listener.SpawnerLimitListener;
import github.io.ssaspawnerlimiter.service.ChunkLimitService;
import github.io.ssaspawnerlimiter.service.PlayerLimitService;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import io.github.pluginlangcore.language.LanguageManager;
import io.github.pluginlangcore.language.MessageService;
import io.github.pluginlangcore.LanguageSystem;
import io.github.pluginlangcore.LanguageSystem.LanguageFileType;
import io.github.pluginupdatecore.updater.UpdateChecker;
import io.github.pluginupdatecore.updater.ConfigUpdater;
import github.nighter.smartspawner.api.SmartSpawnerAPI;
import github.nighter.smartspawner.api.SmartSpawnerProvider;

import java.util.logging.Level;

@Getter
@Accessors(chain = false)
public final class SSASpawnerLimiter extends JavaPlugin {
    @Getter
    private static SSASpawnerLimiter instance;
    private final static String MODRINTH_PROJECT_ID = "JF5xsqCk";
    private LanguageSystem languageSystem;
    private LanguageManager languageManager;
    private MessageService messageService;
    private SmartSpawnerAPI api;
    private DatabaseManager databaseManager;
    private ChunkLimitService chunkLimitService;
    private PlayerLimitService playerLimitService;
    private BrigadierCommandManager commandManager;
    private Scheduler.Task cacheCleanupTask;

    private void checkSmartSpawnerAPI() {
        api = SmartSpawnerProvider.getAPI();
        if (api == null) {
            getLogger().warning("SmartSpawner not found! This addon requires SmartSpawner to work.");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void checkPluginUpdates() {
        if (MODRINTH_PROJECT_ID.isEmpty()) {
            return;
        }
        UpdateChecker updateChecker = new UpdateChecker(this, MODRINTH_PROJECT_ID);
        updateChecker.checkForUpdates();
    }

    private void updateConfig() {
        saveDefaultConfig();
        ConfigUpdater configUpdater = new ConfigUpdater(this);
        configUpdater.checkAndUpdateConfig();
        reloadConfig();
    }

    private void initializeLanguageSystem() {
        languageSystem = LanguageSystem.builder(this)
                .defaultLocale("en_US")
                .fileTypes(LanguageFileType.MESSAGES)
                .build();

        languageManager = languageSystem.getLanguageManager();
        messageService = languageSystem.getMessageService();
    }

    private void initializeDatabase() {
        databaseManager = new DatabaseManager(this);

        // Initialize database asynchronously
        databaseManager.initialize().thenAccept(success -> {
            if (success) {
                initializeServices();
            } else {
                getLogger().severe("Failed to initialize database! Disabling plugin...");
                Scheduler.runTask(() -> getServer().getPluginManager().disablePlugin(this));
            }
        }).exceptionally(throwable -> {
            getLogger().log(Level.SEVERE, "Error initializing database", throwable);
            Scheduler.runTask(() -> getServer().getPluginManager().disablePlugin(this));
            return null;
        });
    }

    private void initializeServices() {
        // Initialize chunk limit service
        chunkLimitService = new ChunkLimitService(this, databaseManager);

        // Initialize player limit service
        playerLimitService = new PlayerLimitService(this, databaseManager);

        // Register event listeners
        Bukkit.getPluginManager().registerEvents(new SpawnerLimitListener(this, chunkLimitService, playerLimitService), this);

        // Start cache cleanup task (hardcoded: 5 minutes = 6000 ticks)
        long cleanupInterval = 6000L; // 5 minutes in ticks
        cacheCleanupTask = Scheduler.runTaskTimerAsync(() -> {
            chunkLimitService.cleanupExpiredCache();
            playerLimitService.cleanupExpiredCache();
        }, cleanupInterval, cleanupInterval);
    }

    private void initializeCommands() {
        commandManager = new BrigadierCommandManager(this);
        commandManager.registerCommands();
    }

    @Override
    public void onEnable() {
        instance = this;

        // Check for SmartSpawner API
        checkSmartSpawnerAPI();
        if (!isEnabled()) {
            return;
        }

        // Initialize configuration
        updateConfig();

        // Initialize language system
        initializeLanguageSystem();

        // Check for updates
        checkPluginUpdates();

        // Initialize database (async)
        initializeDatabase();

        // Initialize commands
        initializeCommands();

        getLogger().info("SSA Spawner Limiter has been enabled!");
    }

    @Override
    public void onDisable() {
        // Cancel cache cleanup task
        if (cacheCleanupTask != null) {
            cacheCleanupTask.cancel();
            cacheCleanupTask = null;
        }

        // Clear caches before closing database
        if (chunkLimitService != null) {
            chunkLimitService.cleanupExpiredCache();
        }
        if (playerLimitService != null) {
            playerLimitService.cleanupExpiredCache();
        }

        // Close database connection
        if (databaseManager != null) {
            databaseManager.close();
            databaseManager = null;
        }

        // Clear references for reload safety
        chunkLimitService = null;
        playerLimitService = null;
        commandManager = null;
        api = null;
        languageSystem = null;
        languageManager = null;
        messageService = null;

        getLogger().info("SSA Spawner Limiter has been disabled!");
    }
}

package github.io.ssaspawnerlimiter.listener;

import github.io.ssaspawnerlimiter.SSASpawnerLimiter;
import github.io.ssaspawnerlimiter.Scheduler;
import github.io.ssaspawnerlimiter.service.ChunkLimitService;
import github.io.ssaspawnerlimiter.service.PlayerLimitService;
import github.io.ssaspawnerlimiter.util.ChunkKey;
import github.nighter.smartspawner.api.events.SpawnerPlayerBreakEvent;
import github.nighter.smartspawner.api.events.SpawnerPlaceEvent;
import github.nighter.smartspawner.api.events.SpawnerRemoveEvent;
import github.nighter.smartspawner.api.events.SpawnerStackEvent;
import github.nighter.smartspawner.api.data.SpawnerDataDTO;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Event listener for SmartSpawner events to enforce chunk and player limits.
 * Designed to be thread-safe and work with Folia's region-based threading.
 */
public class SpawnerLimitListener implements Listener {
    private final SSASpawnerLimiter plugin;
    private final ChunkLimitService chunkLimitService;
    private final PlayerLimitService playerLimitService;

    public SpawnerLimitListener(SSASpawnerLimiter plugin, ChunkLimitService chunkLimitService, PlayerLimitService playerLimitService) {
        this.plugin = plugin;
        this.chunkLimitService = chunkLimitService;
        this.playerLimitService = playerLimitService;
    }

    /**
     * Handle spawner placement - check if limit is reached
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnerPlace(SpawnerPlaceEvent event) {
        Player player = event.getPlayer();
        Location location = event.getLocation();
        int quantity = event.getQuantity();
        UUID playerUUID = player.getUniqueId();

        // Check chunk limit synchronously for immediate cancellation
        boolean canPlaceChunk = chunkLimitService.canPlaceSpawner(player, location, quantity);

        if (!canPlaceChunk) {
            event.setCancelled(true);

            // Send chunk limit message to player on their region thread
            Scheduler.runAtLocation(player.getLocation(), () -> {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("limit", String.valueOf(chunkLimitService.getMaxSpawnersPerChunk()));
                placeholders.put("current", String.valueOf(chunkLimitService.getSpawnerCount(new ChunkKey(location))));
                plugin.getMessageService().sendMessage(player, "chunk_limit_reached", placeholders);
            });
            return;
        }

        // Check player limit synchronously for immediate cancellation
        boolean canPlacePlayer = playerLimitService.canPlaceSpawner(player, quantity);

        if (!canPlacePlayer) {
            event.setCancelled(true);

            // Send player limit message to player on their region thread
            Scheduler.runAtLocation(player.getLocation(), () -> {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("limit", String.valueOf(playerLimitService.getPlayerLimit(player)));
                placeholders.put("current", String.valueOf(playerLimitService.getPlayerSpawnerCount(playerUUID)));
                plugin.getMessageService().sendMessage(player, "player_limit_reached", placeholders);
            });
            return;
        }

        // Both limits passed - Update counts in database asynchronously (in background)
        chunkLimitService.addSpawners(location, quantity);
        playerLimitService.addSpawners(playerUUID, quantity);
    }

    /**
     * Handle spawner break - decrease count
     * Bug fix: Decrements the OWNER's count, not the BREAKER's count
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnerBreak(SpawnerPlayerBreakEvent event) {
        Player breaker = event.getPlayer();
        Location location = event.getLocation();
        int quantity = event.getQuantity();
        
        if (plugin.getConfig().getBoolean("debug", false)) {
            String entityName = event.getEntity() != null ? event.getEntity().getName() : "Unknown";
            plugin.getLogger().info(String.format(
                "[DEBUG] SpawnerBreakEvent - Player: %s, Entity: %s, Quantity: %d, Chunk: %s",
                breaker.getName(), entityName, quantity, new ChunkKey(location)
            ));
        }

        // Get the spawner owner UUID from SmartSpawner API
        UUID ownerUUID = getSpawnerOwnerUUID(location, breaker.getUniqueId());

        // Update counts asynchronously in background
        chunkLimitService.removeSpawners(location, quantity);
        playerLimitService.removeSpawners(ownerUUID, quantity);
    }

    /**
     * Get the owner UUID of a spawner at a given location.
     * Falls back to the provided fallbackUUID if the spawner or owner cannot be found.
     *
     * @param location The location of the spawner
     * @param fallbackUUID The UUID to use if the spawner owner cannot be determined
     * @return The owner's UUID, or the fallbackUUID if not found
     */
    private UUID getSpawnerOwnerUUID(Location location, UUID fallbackUUID) {
        try {
            // Get all spawners from SmartSpawner API
            var allSpawners = plugin.getApi().getAllSpawners();
            
            if (allSpawners == null) {
                return fallbackUUID;
            }

            // Find the spawner at the given location
            for (SpawnerDataDTO spawner : allSpawners) {
                Location spawnerLoc = spawner.getLocation();
                if (spawnerLoc != null && spawnerLoc.equals(location)) {
                    // Try to get owner UUID using reflection (for version compatibility)
                    UUID ownerUUID = getOwnerIdFromSpawner(spawner);
                    if (ownerUUID != null) {
                        return ownerUUID;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to get spawner owner UUID at " + location + ": " + e.getMessage());
        }
        return fallbackUUID;
    }

    /**
     * Safely get the owner UUID from a SpawnerDataDTO using reflection.
     * Tries multiple possible method names for version compatibility.
     *
     * @param spawner The spawner data object
     * @return The owner's UUID, or null if not found
     */
    private UUID getOwnerIdFromSpawner(SpawnerDataDTO spawner) {
        // Method names to try, in order of preference
        String[] methodNames = {"getOwnerId", "getOwner", "getPlacedByUUID", "getCreatorUUID"};
        
        for (String methodName : methodNames) {
            try {
                var method = spawner.getClass().getMethod(methodName);
                Object result = method.invoke(spawner);
                if (result instanceof UUID) {
                    return (UUID) result;
                }
            } catch (NoSuchMethodException | IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
                // Continue to next method name
            }
        }
        
        return null;
    }

    /**
     * Handle spawner stacking - check limit and update count
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnerStack(SpawnerStackEvent event) {
        Player player = event.getPlayer();
        Location location = event.getLocation();
        int oldQuantity = event.getOldStackSize();
        int newQuantity = event.getNewStackSize();
        int difference = newQuantity - oldQuantity;
        UUID playerUUID = player.getUniqueId();

        // Only check if we're adding to the stack
        if (difference <= 0) {
            return;
        }

        // Check if adding would exceed chunk limit (SYNC for immediate cancel)
        boolean canStackChunk = chunkLimitService.canPlaceSpawner(player, location, difference);

        if (!canStackChunk) {
            event.setCancelled(true);

            // Send chunk limit message to player on their region thread
            Scheduler.runAtLocation(player.getLocation(), () -> {
                int currentCount = chunkLimitService.getSpawnerCount(new ChunkKey(location));
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("limit", String.valueOf(chunkLimitService.getMaxSpawnersPerChunk()));
                placeholders.put("current", String.valueOf(currentCount));
                plugin.getMessageService().sendMessage(player, "chunk_limit_reached", placeholders);
            });
            return;
        }

        // Check if adding would exceed player limit (SYNC for immediate cancel)
        boolean canStackPlayer = playerLimitService.canPlaceSpawner(player, difference);

        if (!canStackPlayer) {
            event.setCancelled(true);

            // Send player limit message to player on their region thread
            Scheduler.runAtLocation(player.getLocation(), () -> {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("limit", String.valueOf(playerLimitService.getPlayerLimit(player)));
                placeholders.put("current", String.valueOf(playerLimitService.getPlayerSpawnerCount(playerUUID)));
                plugin.getMessageService().sendMessage(player, "player_limit_reached", placeholders);
            });
        }
    }

    /**
     * Handle spawner stack completion - update count
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnerStackComplete(SpawnerStackEvent event) {
        Player player = event.getPlayer();
        Location location = event.getLocation();
        int oldQuantity = event.getOldStackSize();
        int newQuantity = event.getNewStackSize();
        UUID playerUUID = player.getUniqueId();

        // Update counts asynchronously
        chunkLimitService.updateStackCount(location, oldQuantity, newQuantity);
        playerLimitService.updateStackCount(playerUUID, oldQuantity, newQuantity);
    }

    /**
     * Handle spawner removal (from GUI or other means) - decrease count
     * Bug fix: Decrements the OWNER's count, not the player performing the removal
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawnerRemove(SpawnerRemoveEvent event) {
        Player remover = event.getPlayer();
        Location location = event.getLocation();
        int changeAmount = event.getChangeAmount();

        // Get the spawner owner UUID from SmartSpawner API
        UUID ownerUUID = getSpawnerOwnerUUID(location, remover.getUniqueId());

        // changeAmount is the difference (can be negative when removing)
        chunkLimitService.removeSpawners(location, Math.abs(changeAmount));
        playerLimitService.removeSpawners(ownerUUID, Math.abs(changeAmount));
    }
}


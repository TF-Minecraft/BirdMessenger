package net.tfminecraft.birdmessenger.listener;

import dev.lone.itemsadder.api.Events.FurnitureBreakEvent;
import net.tfminecraft.birdmessenger.BirdMessenger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Protects the furniture entity as well as its solid block hitbox. */
public final class CoopFurnitureListener implements Listener {
    private final BirdMessenger plugin;

    public CoopFurnitureListener(BirdMessenger plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(FurnitureBreakEvent event) {
        String path = "iaf(" + event.getNamespacedID() + ")";
        if (path.equalsIgnoreCase(plugin.config().coopPath()) && !event.getPlayer().isSneaking()) {
            event.setCancelled(true);
        }
    }
}

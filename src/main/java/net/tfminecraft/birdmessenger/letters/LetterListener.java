package net.tfminecraft.birdmessenger.letters;

import java.util.HashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import net.tfminecraft.birdmessenger.BirdMessenger;


public class LetterListener implements Listener {

    private final BirdMessenger plugin;
    private final LetterItems items;

    public LetterListener(BirdMessenger plugin) {
        this.plugin = plugin;
        this.items = new LetterItems(plugin);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBookSign(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        // Paper exposes hotbar slots directly and maps offhand inventory slot 40 to event slot -1.
        @SuppressWarnings("removal")
        int eventSlot = event.getSlot();
        int slot = eventSlot == -1 ? 40 : eventSlot;
        if (slot < 0 || slot >= player.getInventory().getSize()) return;
        ItemStack handItem = player.getInventory().getItem(slot);
        if (!items.isLetter(handItem)) return;
        ItemStack original = handItem.clone();
        if (!event.isSigning()) {
            // Let vanilla apply the final event metadata. ArmourShop's MONITOR handler
            // can still preserve custom item components while accepting these pages.
            ItemStack restored = items.createEditedLetter(event.getNewBookMeta(), original);
            if (restored == null) {
                warn("Failed to restore edited letter for " + player.getName());
                return;
            }
            event.setNewBookMeta((BookMeta) restored.getItemMeta());
            return;
        }
        // Cancel so the vanilla written book is never produced - we hand out our own item instead.
        event.setCancelled(true);

        ItemStack sealed = items.createSealedLetter(event.getNewBookMeta(), player);
        if (sealed == null) {
            warn("Failed to create sealed letter for " + player.getName());
            sendMessage(player, LetterConfig.creationFailedMessage);
            return;
        }
        // The next-tick hop is required - setting the item inside the cancelled event does not stick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !unchanged(original, player.getInventory().getItem(slot))) return;
            player.getInventory().setItem(slot, sealed);
            sendMessage(player, LetterConfig.signedMessage);
        });
    }

    @EventHandler
    public void onBookOpen(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked != null && (clicked.getType() == Material.CHISELED_BOOKSHELF || clicked.getType() == Material.LECTERN)) return;
        // No guard on which hand was used - off-hand letters open too - but the swap below
        // must go back into that same hand, or the other hand's item gets overwritten.
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;
        ItemStack item = event.getItem();
        if (!items.isSealedLetter(item)) return;
        ItemStack original = item.clone();
        BookMeta current = (BookMeta) item.getItemMeta();
        if (current == null) return;

        Player player = event.getPlayer();
        ItemStack opened = items.createOpenedLetter(current);
        if (opened == null) {
            warn("Failed to create opened letter for " + player.getName());
            sendMessage(player, LetterConfig.openFailedMessage);
            return;
        }
        // Not cancelled - the book still opens for reading, so swap the item on the next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            ItemStack held = hand == EquipmentSlot.OFF_HAND
                    ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
            if (!unchangedOnOpen(original, held)) return;
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(opened);
            } else {
                player.getInventory().setItemInMainHand(opened);
            }
            sendMessage(player, LetterConfig.openedMessage);
        });
    }

    private static boolean unchanged(ItemStack original, ItemStack current) {
        return current != null && original.getAmount() == current.getAmount() && original.isSimilar(current);
    }

    private static boolean unchangedOnOpen(ItemStack original, ItemStack current) {
        if (unchanged(original, current)) return true;
        if (current == null || original.getType() != current.getType()
                || original.getAmount() != current.getAmount()
                || !(original.getItemMeta() instanceof BookMeta beforeMeta)
                || !(current.getItemMeta() instanceof BookMeta afterMeta)) return false;
        // Vanilla may mark a book resolved during its first read. Keep every other
        // serialized component (including pages and PDC) exact; only allow false -> true.
        var before = new HashMap<>(beforeMeta.serialize());
        var after = new HashMap<>(afterMeta.serialize());
        boolean wasResolved = Boolean.TRUE.equals(before.remove("resolved"));
        boolean nowResolved = Boolean.TRUE.equals(after.remove("resolved"));
        return !wasResolved && nowResolved && before.equals(after);
    }

    private void sendMessage(Player player, String message) {
        if (message == null || message.isEmpty()) return;
        player.sendMessage(TextUtil.color(message));
    }

    private void warn(String message) {
        BirdMessenger instance = plugin;
        if (instance != null) {
            instance.getLogger().warning(message);
        }
    }
}

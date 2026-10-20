package net.tfminecraft.birdmessenger.letters;

import java.util.HashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;


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
        if (event.isSigning() ? !items.isLetter(handItem) : !items.isEditableLetter(handItem)) return;
        ItemStack original = handItem.clone();
        // ItemsAdder rebuilds BookMeta at MONITOR, losing custom item identity.
        // Own the save for both edits and signatures so that handler and vanilla
        // cannot turn the letter into an ordinary book. That also means an edit
        // made without a character cannot be kept, so say why it was dropped.
        event.setCancelled(true);
        CharacterSession session = Characters.currentSession(player).orElse(null);
        if (session == null && Characters.available()) {
            sendMessage(player, LetterConfig.needsCharacterMessage);
            return;
        }
        if (!event.isSigning()) {
            ItemStack restored = items.createEditedLetter(event.getNewBookMeta(), original);
            if (restored == null) {
                warn("Failed to restore edited letter for " + player.getName());
                sendMessage(player, LetterConfig.creationFailedMessage);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!isCurrent(player, session) || !unchanged(original, player.getInventory().getItem(slot))) return;
                player.getInventory().setItem(slot, restored);
            });
            return;
        }
        ItemStack sealed = items.createSealedLetter(event.getNewBookMeta(), player);
        if (sealed == null) {
            warn("Failed to create sealed letter for " + player.getName());
            sendMessage(player, LetterConfig.creationFailedMessage);
            return;
        }
        // The next-tick hop is required - setting the item inside the cancelled event does not stick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!isCurrent(player, session) || !unchanged(original, player.getInventory().getItem(slot))) return;
            player.getInventory().setItem(slot, sealed);
            sendMessage(player, LetterConfig.signedMessage);
        });
    }

    // Not ignoreCancelled: a cancelled count still means the book opened.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBookOpen(PlayerStatisticIncrementEvent event) {
        // Vanilla counts a written book as used right after it opens the reading screen,
        // and nowhere else. Clicks that open a barrel, use a door, place the book on a
        // lectern or are denied by another plugin never reach this, so the seal holds.
        if (event.getStatistic() != Statistic.USE_ITEM || event.getMaterial() != Material.WRITTEN_BOOK) return;
        Player player = event.getPlayer();
        PlayerInventory inventory = player.getInventory();
        // The client only tries the off hand when the main hand item does nothing, and a
        // written book always opens, so a main-hand written book is the one being read.
        // The swap below must go back into that same hand, or the other hand's item gets overwritten.
        ItemStack mainHand = inventory.getItemInMainHand();
        EquipmentSlot hand = mainHand.getType() == Material.WRITTEN_BOOK ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        ItemStack item = hand == EquipmentSlot.HAND ? mainHand : inventory.getItemInOffHand();
        if (!items.isSealedLetter(item)) return;
        ItemStack original = item.clone();
        BookMeta current = (BookMeta) item.getItemMeta();
        if (current == null) return;

        CharacterSession session = Characters.currentSession(player).orElse(null);
        if (session == null && Characters.available()) return;
        ItemStack opened = items.createOpenedLetter(current);
        if (opened == null) {
            warn("Failed to create opened letter for " + player.getName());
            sendMessage(player, LetterConfig.openFailedMessage);
            return;
        }
        // The book is already open for reading, so swap the item on the next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!isCurrent(player, session)) return;
            ItemStack held = hand == EquipmentSlot.OFF_HAND
                    ? inventory.getItemInOffHand() : inventory.getItemInMainHand();
            if (!unchangedOnOpen(original, held)) return;
            if (hand == EquipmentSlot.OFF_HAND) {
                inventory.setItemInOffHand(opened);
            } else {
                inventory.setItemInMainHand(opened);
            }
            sendMessage(player, LetterConfig.openedMessage);
        });
    }

    private static boolean isCurrent(Player player, CharacterSession session) {
        return player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player
                && (session == null ? !Characters.available() : Characters.isCurrent(session));
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

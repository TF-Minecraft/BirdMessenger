package net.tfminecraft.birdmessenger.letters;

import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.birdmessenger.BirdMessenger;

class LetterListenerTest {
    private BirdMessenger plugin;
    private Player player;
    private PlayerInventory inventory;
    private BukkitScheduler scheduler;
    private ItemStack original;
    private ItemStack snapshot;
    private ItemStack replacement;
    private BookMeta edited;
    private final List<Runnable> pending = new ArrayList<>();

    @BeforeEach void setup() {
        pending.clear();
        plugin = mock(BirdMessenger.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        scheduler = mock(BukkitScheduler.class);
        original = mock(ItemStack.class);
        snapshot = mock(ItemStack.class);
        replacement = mock(ItemStack.class);
        edited = mock(BookMeta.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.isOnline()).thenReturn(true);
        when(inventory.getSize()).thenReturn(41);
        when(original.clone()).thenReturn(snapshot);
        when(original.getAmount()).thenReturn(1);
        when(snapshot.getAmount()).thenReturn(1);
        when(snapshot.isSimilar(original)).thenReturn(true);
        doAnswer(call -> { pending.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        doAnswer(call -> { pending.add(call.getArgument(1)); return null; })
                .when(scheduler).runTaskLater(eq(plugin), any(Runnable.class), anyLong());
    }

    @Test void unsignedEditsCancelVanillaAndRestoreOnlyTheOriginalSlot() {
        for (int slot : new int[] {0, 40}) {
            setup();
            PlayerEditBookEvent event = editEvent(slot, false);
            try (var bukkit = mockStatic(Bukkit.class);
                 var factories = mockConstruction(LetterItems.class, (items, context) -> {
                     when(items.isLetter(original)).thenReturn(true);
                     when(items.isEditableLetter(original)).thenReturn(true);
                     when(items.createEditedLetter(edited, snapshot)).thenReturn(replacement);
                 })) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                new LetterListener(plugin).onBookSign(event);
                verify(event).setCancelled(true);
                verify(event, never()).setNewBookMeta(any());
                verify(inventory, never()).setItem(anyInt(), any());
                pending.getLast().run();
                verify(inventory).setItem(slot, replacement);
            }
        }
    }

    @Test void savingLeavesMovedReplacedOrChangedStacksUntouched() {
        for (boolean signing : new boolean[] {false, true}) {
            for (int slot : new int[] {0, 40}) {
                for (String change : List.of("moved", "replaced", "mutated", "amount", "offline")) {
                    setup();
                    PlayerEditBookEvent event = editEvent(slot, signing);
                    try (var bukkit = mockStatic(Bukkit.class);
                         var factories = mockConstruction(LetterItems.class, (items, context) -> {
                             when(items.isLetter(original)).thenReturn(true);
                             when(items.isEditableLetter(original)).thenReturn(true);
                             when(items.createSealedLetter(edited, player)).thenReturn(replacement);
                             when(items.createEditedLetter(edited, snapshot)).thenReturn(replacement);
                         })) {
                        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                        new LetterListener(plugin).onBookSign(event);
                        verify(event).setCancelled(true);
                        switch (change) {
                            case "moved" -> when(inventory.getItem(slot)).thenReturn(null);
                            case "replaced" -> when(inventory.getItem(slot)).thenReturn(mock(ItemStack.class));
                            case "mutated" -> when(snapshot.isSimilar(original)).thenReturn(false);
                            case "amount" -> when(original.getAmount()).thenReturn(2);
                            case "offline" -> when(player.isOnline()).thenReturn(false);
                        }
                        pending.getLast().run();
                        verify(inventory, never()).setItem(anyInt(), any());
                    }
                }
            }
        }
    }

    @Test void signingStillReplacesTheOriginalEventSlot() {
        PlayerEditBookEvent event = editEvent(40, true);
        try (var bukkit = mockStatic(Bukkit.class);
             var factories = mockConstruction(LetterItems.class, (items, context) -> {
                 when(items.isLetter(original)).thenReturn(true);
                 when(items.createSealedLetter(edited, player)).thenReturn(replacement);
             })) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            new LetterListener(plugin).onBookSign(event);
            pending.getLast().run();
            verify(inventory).setItem(40, replacement);
            verify(original).clone();
        }
    }

    @Test void failedUnsignedSaveKeepsTheOriginalLetterAndCancelsTheDestructiveWrite() {
        PlayerEditBookEvent event = editEvent(0, false);
        try (var factories = mockConstruction(LetterItems.class, (items, context) -> {
            when(items.isEditableLetter(original)).thenReturn(true);
        })) {
            new LetterListener(plugin).onBookSign(event);
            verify(event).setCancelled(true);
            verify(event, never()).setNewBookMeta(any());
            verify(inventory, never()).setItem(anyInt(), any());
            verifyNoInteractions(scheduler);
        }
    }

    @Test void ordinaryBooksAreLeftToVanilla() {
        for (boolean signing : new boolean[] {false, true}) {
            PlayerEditBookEvent event = editEvent(0, signing);
            try (var factories = mockConstruction(LetterItems.class)) {
                new LetterListener(plugin).onBookSign(event);
                verify(event, never()).setCancelled(anyBoolean());
                verify(event, never()).setNewBookMeta(any());
                verify(inventory, never()).setItem(anyInt(), any());
                verifyNoInteractions(scheduler);
            }
        }
    }

    @Test void invalidEventSlotsRemainIgnored() {
        for (int slot : new int[] {-2, 41}) {
            PlayerEditBookEvent event = editEvent(slot, true);
            try (var factories = mockConstruction(LetterItems.class)) {
                new LetterListener(plugin).onBookSign(event);
                verifyNoInteractions(factories.constructed().getFirst());
                verify(event, never()).setCancelled(anyBoolean());
                verify(event, never()).setNewBookMeta(any());
                verifyNoInteractions(scheduler);
            }
        }
    }

    @Test void openingLeavesChangedHandsOrOfflinePlayersUntouched() {
        for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            for (String change : List.of("replaced", "mutated", "amount", "offline")) {
                setup();
                PlayerInteractEvent event = openEvent(hand);
                try (var bukkit = mockStatic(Bukkit.class);
                     var factories = mockConstruction(LetterItems.class, (items, context) -> {
                         when(items.isSealedLetter(original)).thenReturn(true);
                         when(items.createOpenedLetter(edited)).thenReturn(replacement);
                     })) {
                    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                    new LetterListener(plugin).onBookOpen(event);
                    switch (change) {
                        case "replaced" -> held(hand, mock(ItemStack.class));
                        case "mutated" -> when(snapshot.isSimilar(original)).thenReturn(false);
                        case "amount" -> when(original.getAmount()).thenReturn(2);
                        case "offline" -> when(player.isOnline()).thenReturn(false);
                    }
                    pending.getLast().run();
                    verify(inventory, never()).setItemInMainHand(any());
                    verify(inventory, never()).setItemInOffHand(any());
                }
            }
        }
    }

    @Test void openingStillReplacesOnlyTheRecordedHand() {
        for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            setup();
            PlayerInteractEvent event = openEvent(hand);
            try (var bukkit = mockStatic(Bukkit.class);
                 var factories = mockConstruction(LetterItems.class, (items, context) -> {
                     when(items.isSealedLetter(original)).thenReturn(true);
                     when(items.createOpenedLetter(edited)).thenReturn(replacement);
                 })) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                new LetterListener(plugin).onBookOpen(event);
                pending.getLast().run();
                if (hand == EquipmentSlot.HAND) {
                    verify(inventory).setItemInMainHand(replacement);
                    verify(inventory, never()).setItemInOffHand(any());
                } else {
                    verify(inventory).setItemInOffHand(replacement);
                    verify(inventory, never()).setItemInMainHand(any());
                }
                verify(original).clone();
            }
        }
    }

    @Test void openingAllowsOnlyTheVanillaResolvedFlagToChange() {
        for (String change : List.of("resolved", "pages", "pdc", "name", "type", "amount", "already-resolved")) {
            setup();
            PlayerInteractEvent event = openEvent(EquipmentSlot.HAND);
            BookMeta beforeMeta = mock(BookMeta.class);
            Map<String, Object> before = new HashMap<>(Map.of(
                    "meta-type", "BOOK_SIGNED", "pages", List.of("original text"),
                    "title", "Treaty", "author", "Alice", "display-name", "Sealed letter",
                    "PublicBukkitValues", Map.of("tfmccore:sealed_letter", 1)));
            Map<String, Object> after = new HashMap<>(before);
            after.put("resolved", true);
            when(snapshot.getType()).thenReturn(Material.WRITTEN_BOOK);
            when(original.getType()).thenReturn(Material.WRITTEN_BOOK);
            when(snapshot.getItemMeta()).thenReturn(beforeMeta);
            when(beforeMeta.serialize()).thenReturn(before);
            when(edited.serialize()).thenReturn(after);
            when(snapshot.isSimilar(original)).thenReturn(false);
            switch (change) {
                case "pages" -> after.put("pages", List.of("other letter"));
                case "pdc" -> after.put("PublicBukkitValues", Map.of("tfmccore:sealed_letter", 2));
                case "name" -> after.put("display-name", "Another name");
                case "type" -> when(original.getType()).thenReturn(Material.WRITABLE_BOOK);
                case "amount" -> when(original.getAmount()).thenReturn(2);
                case "already-resolved" -> before.put("resolved", true);
            }
            try (var bukkit = mockStatic(Bukkit.class);
                 var factories = mockConstruction(LetterItems.class, (items, context) -> {
                     when(items.isSealedLetter(original)).thenReturn(true);
                     when(items.createOpenedLetter(edited)).thenReturn(replacement);
                 })) {
                bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                new LetterListener(plugin).onBookOpen(event);
                pending.getLast().run();
                if (change.equals("resolved")) verify(inventory).setItemInMainHand(replacement);
                else verify(inventory, never()).setItemInMainHand(any());
            }
        }
    }

    private PlayerEditBookEvent editEvent(int slot, boolean signing) {
        PlayerEditBookEvent event = mock(PlayerEditBookEvent.class);
        when(event.getPlayer()).thenReturn(player);
        // Paper's packet path uses inventory slot 40, but its event factory emits -1 for offhand.
        when(event.getSlot()).thenReturn(slot == 40 ? -1 : slot);
        when(event.isSigning()).thenReturn(signing);
        when(event.getNewBookMeta()).thenReturn(edited);
        when(inventory.getItem(slot)).thenReturn(original);
        return event;
    }

    private PlayerInteractEvent openEvent(EquipmentSlot hand) {
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(hand);
        when(event.getItem()).thenReturn(original);
        when(original.getItemMeta()).thenReturn(edited);
        held(hand, original);
        return event;
    }

    private void held(EquipmentSlot hand, ItemStack stack) {
        if (hand == EquipmentSlot.HAND) when(inventory.getItemInMainHand()).thenReturn(stack);
        else when(inventory.getItemInOffHand()).thenReturn(stack);
    }
}

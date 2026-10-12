package net.tfminecraft.birdmessenger.letters;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.tlibs.character.CharacterIdentity;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;

class LetterListenerTest {
    private BirdMessenger plugin;
    private Player player;
    private PlayerInventory inventory;
    private BukkitScheduler scheduler;
    private ItemStack original;
    private ItemStack snapshot;
    private ItemStack replacement;
    private BookMeta edited;
    private ServicesManager services;
    private CharacterIdentity identity;
    private CharacterSession session;
    private final List<Runnable> pending = new ArrayList<>();

    @BeforeEach void setup() {
        pending.clear();
        plugin = mock(BirdMessenger.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        services = mock(ServicesManager.class);
        identity = mock(CharacterIdentity.class);
        session = null;
        when(identity.currentSession(player)).thenAnswer(call -> Optional.ofNullable(session));
        when(identity.isCurrent(any())).thenAnswer(call -> call.getArgument(0).equals(session));
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
            try (var bukkit = mockBukkit();
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
                    try (var bukkit = mockBukkit();
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
        try (var bukkit = mockBukkit();
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
        try (var bukkit = mockBukkit();
             var factories = mockConstruction(LetterItems.class, (items, context) -> {
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
                try (var bukkit = mockBukkit();
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
            try (var bukkit = mockBukkit();
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
            try (var bukkit = mockBukkit();
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

    @Test void failedSigningLeavesOriginalInPlaceAndDoesNotScheduleAReplacement() {
        PlayerEditBookEvent event = editEvent(0, true);
        try (var bukkit = mockBukkit();
             var factories = mockConstruction(LetterItems.class, (items, context) -> {
                 when(items.isLetter(original)).thenReturn(true);
                 when(items.createSealedLetter(edited, player)).thenReturn(null);
             })) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            new LetterListener(plugin).onBookSign(event);
            verify(event).setCancelled(true);
            verify(player).sendMessage(TextUtil.color(LetterConfig.creationFailedMessage));
            verify(inventory, never()).setItem(anyInt(), any());
            verifyNoInteractions(scheduler);
        }
    }

    @Test void failedOpeningKeepsTheOriginalSealAndReportsTheFailure() {
        PlayerInteractEvent event = openEvent(EquipmentSlot.OFF_HAND);
        try (var bukkit = mockBukkit();
             var factories = mockConstruction(LetterItems.class, (items, context) -> {
                 when(items.isSealedLetter(original)).thenReturn(true);
                 when(items.createOpenedLetter(edited)).thenReturn(null);
             })) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            new LetterListener(plugin).onBookOpen(event);
            verify(player).sendMessage(TextUtil.color(LetterConfig.openFailedMessage));
            verify(inventory, never()).setItemInOffHand(any());
            verifyNoInteractions(scheduler);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "sign", "open"})
    void delayedWritesRequireTheCapturedCharacterGenerationAndConnection(String operation) {
        for (String change : List.of("current", "invalidated", "switch-back", "provider-lost", "reconnect")) {
            setup();
            session = new CharacterSession(new CharacterRef(player.getUniqueId(), UUID.randomUUID()), 1);
            CharacterSession originalSession = session;
            when(services.load(CharacterIdentity.class)).thenReturn(identity);
            try (var bukkit = mockBukkit(); var factories = successfulFactories()) {
                schedule(operation);
                assertEquals(1, pending.size());
                switch (change) {
                    case "invalidated" -> session = null;
                    case "switch-back" -> {
                        session = new CharacterSession(new CharacterRef(player.getUniqueId(), UUID.randomUUID()), 2);
                        session = new CharacterSession(originalSession.ref(), 3);
                    }
                    case "provider-lost" -> when(services.load(CharacterIdentity.class)).thenReturn(null);
                    case "reconnect" -> {
                        Player replacementPlayer = mock(Player.class);
                        bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(replacementPlayer);
                    }
                }
                pending.getLast().run();
                if (change.equals("current")) {
                    verifyWrite(operation);
                } else {
                    verifyNoWrites();
                    verify(player, never()).sendMessage(anyString());
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "sign", "open"})
    void standaloneWritesRejectProviderArrivalAndAReplacementConnection(String operation) {
        for (String change : List.of("unchanged", "provider-arrived", "reconnect")) {
            setup();
            try (var bukkit = mockBukkit(); var factories = successfulFactories()) {
                schedule(operation);
                assertEquals(1, pending.size());
                if (change.equals("provider-arrived")) {
                    session = new CharacterSession(new CharacterRef(player.getUniqueId(), UUID.randomUUID()), 1);
                    when(services.load(CharacterIdentity.class)).thenReturn(identity);
                } else if (change.equals("reconnect")) {
                    bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(mock(Player.class));
                }
                pending.getLast().run();
                if (change.equals("unchanged")) verifyWrite(operation);
                else verifyNoWrites();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "sign", "open"})
    void pendingCharactersCannotRewriteLettersButMayStillReadThem(String operation) {
        when(services.load(CharacterIdentity.class)).thenReturn(identity);
        try (var bukkit = mockBukkit(); var factories = successfulFactories()) {
            LetterListener listener = new LetterListener(plugin);
            if (operation.equals("open")) {
                PlayerInteractEvent event = openEvent(EquipmentSlot.HAND);
                listener.onBookOpen(event);
                verify(event, never()).setCancelled(anyBoolean());
            } else {
                PlayerEditBookEvent event = editEvent(0, operation.equals("sign"));
                listener.onBookSign(event);
                verify(event).setCancelled(true);
            }
            assertTrue(pending.isEmpty());
            verifyNoWrites();
            LetterItems factory = factories.constructed().getFirst();
            verify(factory, never()).createEditedLetter(any(), any());
            verify(factory, never()).createSealedLetter(any(), any());
            verify(factory, never()).createOpenedLetter(any());
            verify(player, never()).sendMessage(anyString());
        }
    }

    private org.mockito.MockedConstruction<LetterItems> successfulFactories() {
        return mockConstruction(LetterItems.class, (items, context) -> {
            when(items.isLetter(original)).thenReturn(true);
            when(items.isEditableLetter(original)).thenReturn(true);
            when(items.isSealedLetter(original)).thenReturn(true);
            when(items.createEditedLetter(edited, snapshot)).thenReturn(replacement);
            when(items.createSealedLetter(edited, player)).thenReturn(replacement);
            when(items.createOpenedLetter(edited)).thenReturn(replacement);
        });
    }

    private void schedule(String operation) {
        LetterListener listener = new LetterListener(plugin);
        if (operation.equals("open")) {
            PlayerInteractEvent event = openEvent(EquipmentSlot.HAND);
            listener.onBookOpen(event);
            verify(event, never()).setCancelled(anyBoolean());
        } else {
            listener.onBookSign(editEvent(0, operation.equals("sign")));
        }
    }

    private void verifyWrite(String operation) {
        if (operation.equals("open")) verify(inventory).setItemInMainHand(replacement);
        else verify(inventory).setItem(0, replacement);
    }

    private void verifyNoWrites() {
        verify(inventory, never()).setItem(anyInt(), any());
        verify(inventory, never()).setItemInMainHand(any());
        verify(inventory, never()).setItemInOffHand(any());
    }

    private MockedStatic<Bukkit> mockBukkit() {
        MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(Bukkit::getServicesManager).thenReturn(services);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
        return bukkit;
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

package net.tfminecraft.birdmessenger.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.gui.CharacterPickerGui;
import net.tfminecraft.birdmessenger.gui.LetterGui;
import net.tfminecraft.birdmessenger.mail.MailService;
import net.tfminecraft.birdmessenger.mail.MailStore;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class SendSessionManagerTest {
    @Test void readySessionsRetainSelectionAndCancelExactlyOnce() {
        try (Fixture f = new Fixture()) {
            assertNull(f.sessions.get(null));
            assertNull(f.sessions.getOrCreate(null));
            assertFalse(f.sessions.remove(null));
            f.sessions.returnLetter(null, true);
            f.sessions.returnLetter(f.player, true);
            SendSession session = f.start();
            assertSame(session, f.start());
            assertEquals(f.id, session.getPlayerId());
            assertSame(f.player, session.player());
            assertEquals(f.token, session.characterSession());
            assertFalse(session.isConfirmed());
            session.setConfirmed(true);
            assertTrue(session.isConfirmed());
            session.setPickerPage(-1);
            assertEquals(0, session.getPickerPage());
            session.setPickerPage(2);
            assertEquals(2, session.getPickerPage());
            SelectedTarget selected = new SelectedTarget(UUID.randomUUID(), "character", "§aAlice", "Alice", "texture", "signature");
            session.setSelected(selected);
            assertSame(selected, session.getSelected());
            assertEquals("Alice", selected.getDisplayPlain());
            assertEquals("§aAlice", selected.getDisplayTab());
            assertEquals("texture", selected.getBaseTextureValue());
            assertEquals("signature", selected.getBaseTextureSignature());
            SelectedTarget unnamed = new SelectedTarget(f.id, "id", null, null, null, null);
            assertEquals("", unnamed.getDisplayTab());
            assertEquals("", unnamed.getDisplayPlain());
            session.setLetter(f.letter);
            f.sessions.returnLetter(f.player, true);
            f.sessions.returnLetter(f.player, true);
            assertNull(f.sessions.get(f.id));
            assertNull(session.getLetter());
            f.give.verify(() -> ItemGive.giveOrDrop(f.player, f.letter), times(1));
            verify(f.player).sendMessage("Returned");
            verify(f.player).sendMessage("Cancelled");
            verifyNoInteractions(f.store);
            f.start();
            f.sessions.returnLetter(f.player, false);
            assertNull(f.sessions.get(f.id));
        }
    }

    @Test void endRefundsOutgoingStagedLetterBeforeClosingAndIgnoresOldEnd() {
        try (Fixture f = new Fixture()) {
            SendSession session = f.start();
            LetterGui gui = mock(LetterGui.class);
            when(gui.session()).thenReturn(session);
            when(f.top.getHolder()).thenReturn(gui);
            session.setLetterInventory(f.top);
            when(f.top.getItem(LetterGui.LETTER_SLOT)).thenReturn(f.letter);
            f.current.set(null);
            f.sessions.end(f.player, f.token);
            var order = inOrder(f.top, f.player);
            order.verify(f.top).setItem(LetterGui.LETTER_SLOT, null);
            order.verify(f.player).closeInventory();
            f.give.verify(() -> ItemGive.giveOrDrop(f.player, f.letter));
            assertNull(f.sessions.get(f.id));
            f.current.set(new CharacterSession(f.token.ref(), 3));
            SendSession replacement = f.start();
            f.sessions.end(f.player, f.token);
            assertSame(replacement, f.sessions.get(f.id));
        }
    }

    @Test void staleProviderAndReconnectRefundToCapturedCharacter() {
        try (Fixture f = new Fixture()) {
            SendSession old = f.start();
            old.setLetter(f.letter);
            f.current.set(new CharacterSession(f.token.ref(), 3));
            SendSession replacement = f.start();
            assertNotSame(old, replacement);
            assertTrue(f.sessions.isCurrent(f.player, replacement));
            verify(f.store).addPending(eq(f.token.ref().characterId().toString()), argThat(p -> p.ownerUuid.equals(f.id) && p.item == f.letter && p.senderUuid == null));
            f.give.verifyNoInteractions();
            f.sessions.returnLetter(f.player, old, false);
            assertSame(replacement, f.sessions.get(f.id));
            replacement.setLetter(f.letter);
            f.bukkit.when(() -> Bukkit.getPlayer(f.id)).thenReturn(mock(Player.class));
            f.sessions.close();
            assertNull(f.sessions.get(f.id));
            verify(f.store, times(2)).addPending(anyString(), any(MailStore.PendingLetter.class));
            f.current.set(null);
            assertNull(f.start());
        }
    }

    @Test void shutdownReturnsCurrentItemsAndQueuesWhenProviderDisappears() {
        try (Fixture f = new Fixture()) {
            SendSession session = f.start();
            CharacterPickerGui picker = mock(CharacterPickerGui.class);
            when(picker.session()).thenReturn(session);
            when(f.top.getHolder()).thenReturn(picker);
            session.setLetter(f.letter);
            f.sessions.close();
            f.give.verify(() -> ItemGive.giveOrDrop(f.player, f.letter));
            verify(f.player).closeInventory();
        }
        try (Fixture f = new Fixture()) {
            SendSession stale = f.start();
            stale.setLetterInventory(f.top);
            when(f.top.getItem(LetterGui.LETTER_SLOT)).thenReturn(f.letter);
            f.current.set(null);
            f.sessions.close();
            verify(f.store).addPending(anyString(), any(MailStore.PendingLetter.class));
            verify(f.top).setItem(LetterGui.LETTER_SLOT, null);
            assertNull(f.sessions.get(f.id));
        }
    }

    @Test void replacementCreatedByRefundSurvivesAndRemainingItemsStayWithOrigin() {
        try (Fixture f = new Fixture()) {
            SendSession session = f.start();
            session.setLetter(f.letter);
            session.setLetterInventory(f.top);
            ItemStack staged = mock(ItemStack.class);
            when(staged.getType()).thenReturn(mock(Material.class));
            when(f.top.getItem(LetterGui.LETTER_SLOT)).thenReturn(staged);
            AtomicReference<SendSession> replacement = new AtomicReference<>();
            f.give.when(() -> ItemGive.giveOrDrop(f.player, staged)).thenAnswer(call -> {
                f.current.set(new CharacterSession(new CharacterRef(f.id, UUID.randomUUID()), 2));
                replacement.set(f.start());
                return null;
            });
            f.sessions.returnLetter(f.player, true);
            assertSame(replacement.get(), f.sessions.get(f.id));
            f.give.verify(() -> ItemGive.giveOrDrop(f.player, f.letter), never());
            verify(f.store).addPending(eq(f.token.ref().characterId().toString()), argThat(p -> p.item == f.letter));
            verify(f.player, never()).sendMessage("Cancelled");
        }
    }

    @Test void failedQueueKeepsLetterOwnedForRecovery() {
        try (Fixture f = new Fixture()) {
            SendSession session = f.start();
            session.setLetter(f.letter);
            f.current.set(null);
            doThrow(new IllegalStateException("recovery needed")).when(f.store).addPending(anyString(), any(MailStore.PendingLetter.class));
            f.sessions.close();
            assertNull(f.sessions.get(f.id));
            assertNull(session.getLetter());
            f.give.verifyNoInteractions();
            f.sessions.retryReturns();
            doNothing().when(f.store).addPending(anyString(), any(MailStore.PendingLetter.class));
            f.sessions.retryReturns();
            f.sessions.retryReturns();
            verify(f.store, times(3)).addPending(eq(f.token.ref().characterId().toString()), argThat(p -> p.item == f.letter));
        }
    }

    @Test void shutdownPreventsNewSessionsFromCloseCallbacks() {
        try (Fixture f = new Fixture()) {
            SendSession session = f.start();
            session.setLetter(f.letter);
            f.give.when(() -> ItemGive.giveOrDrop(f.player, f.letter)).thenAnswer(call -> {
                assertNull(f.start());
                return null;
            });
            f.sessions.close();
            assertNull(f.sessions.get(f.id));
            assertNull(f.start());
            assertFalse(f.sessions.isCurrent(f.player, session));
        }
    }

    @Test void failedSecondRefundRemainsOwnedAfterReentrantSwitchAndReplacement() {
        try (Fixture f = new Fixture()) {
            SendSession session = f.start();
            session.setLetter(f.letter);
            session.setLetterInventory(f.top);
            ItemStack staged = mock(ItemStack.class);
            when(staged.getType()).thenReturn(mock(Material.class));
            when(f.top.getItem(LetterGui.LETTER_SLOT)).thenReturn(staged);
            f.give.when(() -> ItemGive.giveOrDrop(f.player, staged)).thenAnswer(call -> {
                f.current.set(new CharacterSession(new CharacterRef(f.id, UUID.randomUUID()), 2));
                f.start();
                return null;
            });
            doThrow(new IllegalStateException("conflicting owner")).when(f.store).addPending(anyString(), any(MailStore.PendingLetter.class));
            f.sessions.returnLetter(f.player, false);
            SendSession replacement = f.sessions.get(f.id);
            assertNotSame(session, replacement);
            doNothing().when(f.store).addPending(anyString(), any(MailStore.PendingLetter.class));
            f.sessions.retryReturns();
            assertSame(replacement, f.sessions.get(f.id));
            verify(f.store, times(2)).addPending(eq(f.token.ref().characterId().toString()), argThat(p -> p.item == f.letter));
            f.give.verify(() -> ItemGive.giveOrDrop(f.player, f.letter), never());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final BirdMessenger plugin = mock(BirdMessenger.class);
        final BirdConfig config = mock(BirdConfig.class);
        final MailService mail = mock(MailService.class);
        final MailStore store = mock(MailStore.class);
        final Player player = mock(Player.class);
        final UUID id = UUID.randomUUID();
        final CharacterSession token = new CharacterSession(new CharacterRef(id, UUID.randomUUID()), 1);
        final AtomicReference<CharacterSession> current = new AtomicReference<>(token);
        final Inventory top = mock(Inventory.class);
        final ItemStack letter = mock(ItemStack.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<Characters> characters = mockStatic(Characters.class);
        final MockedStatic<ItemGive> give = mockStatic(ItemGive.class);
        final SendSessionManager sessions;
        Fixture() {
            when(plugin.config()).thenReturn(config);
            when(plugin.getLogger()).thenReturn(mock(java.util.logging.Logger.class));
            when(plugin.mail()).thenReturn(mail);
            when(mail.store()).thenReturn(store);
            when(config.msgLetterReturned()).thenReturn("Returned");
            when(config.msgLetterCancelled()).thenReturn("Cancelled");
            when(player.getUniqueId()).thenReturn(id);
            when(player.isOnline()).thenReturn(true);
            when(letter.getType()).thenReturn(mock(Material.class));
            InventoryView view = mock(InventoryView.class);
            when(view.getTopInventory()).thenReturn(top);
            when(player.getOpenInventory()).thenReturn(view);
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            characters.when(() -> Characters.currentSession(player)).thenAnswer(call -> Optional.ofNullable(current.get()));
            characters.when(() -> Characters.isCurrent(any(CharacterSession.class))).thenAnswer(call -> call.getArgument(0).equals(current.get()));
            sessions = new SendSessionManager(plugin);
        }
        SendSession start() { return sessions.getOrCreate(player); }
        @Override public void close() { give.close(); characters.close(); bukkit.close(); }
    }
}

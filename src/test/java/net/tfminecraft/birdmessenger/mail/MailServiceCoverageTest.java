package net.tfminecraft.birdmessenger.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.mail.CharacterMailTarget;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

class MailServiceCoverageTest {
  @Test
  void acceptedMailOwnsACloneAndScheduledCompletionIsIdempotent() {
    try (Fixture f = new Fixture()) {
      assertSame(f.store, f.service.store());
      assertSame(f.delivery, f.service.delivery());
      f.listRecipient();
      f.flight
          .when(() -> FlightTime.computeFlightSecondsAtSend(f.config, f.sender, f.target))
          .thenReturn(10);
      ItemStack letter = mock(ItemStack.class);
      ItemStack clone = mock(ItemStack.class);
      when(letter.clone()).thenReturn(clone);
      long before = System.currentTimeMillis();
      assertTrue(f.service.trySend(f.sender, f.origin, letter, f.target));
      assertEquals(1, f.inFlight.size());
      StoredMail mail = f.inFlight.values().iterator().next();
      assertSame(clone, mail.getItem());
      assertEquals(f.senderId, mail.getSenderUuid());
      assertEquals(f.ownerId, mail.getOwnerUuid());
      assertEquals(f.characterId.toString(), mail.getCharacterId());
      assertEquals("Recipient", mail.getAddresseeDisplayTab());
      assertTrue(mail.getDeliveryTime() >= before + 10_000);
      assertTrue(mail.getDeliveryTime() <= System.currentTimeMillis() + 10_000);
      verify(f.sender).sendMessage("Departed");
      assertEquals(1, f.scheduled.size());
      assertTrue(f.delays.getFirst() > 0 && f.delays.getFirst() <= 200);
      f.activeOwner();
      f.current.put(f.ownerId, new CharacterSession(f.ownerSession.ref(), 99L));
      f.scheduled.getFirst().run();
      f.scheduled.getFirst().run();
      assertTrue(f.inFlight.isEmpty());
      verify(f.delivery).deliver(f.owner, mail);
      verify(f.store, never()).addPending(any());
      f.discord.verify(() -> DiscordNotifyService.notifyFlightComplete(f.plugin, mail));
    }
  }

  @Test
  void absentArgumentsAndSelfAddressedMailCannotEnterTheQueue() {
    try (Fixture f = new Fixture()) {
      ItemStack letter = mock(ItemStack.class);
      assertFalse(f.service.trySend(null, null, letter, f.target));
      assertFalse(f.service.trySend(f.sender, null, letter, f.target));
      assertFalse(f.service.trySend(f.sender, f.origin, null, f.target));
      assertFalse(f.service.trySend(f.sender, f.origin, letter, null));
      SelectedTarget self = new SelectedTarget(f.senderId, "self", "Self", "Self", null, null);
      assertFalse(f.service.trySend(f.sender, f.origin, letter, self));
      f.give.verify(() -> ItemGive.giveOrDrop(f.sender, letter));
      verify(f.sender).sendMessage("Self forbidden");
      assertTrue(f.inFlight.isEmpty());
      f.characters.verifyNoInteractions();
    }
  }

  @Test
  void resumeCompletesExpiredMailAndSchedulesFutureMail() {
    try (Fixture f = new Fixture()) {
      f.service.resume();
      verifyNoInteractions(f.logger);
      StoredMail expired = f.mail(System.currentTimeMillis() - 1000);
      StoredMail future = f.mail(System.currentTimeMillis() + 60_000);
      f.inFlight.put(expired.getId(), expired);
      f.inFlight.put(future.getId(), future);
      f.service.resume();
      verify(f.store).addPending(expired);
      assertFalse(f.inFlight.containsKey(expired.getId()));
      assertTrue(f.inFlight.containsKey(future.getId()));
      assertEquals(1, f.scheduled.size());
      verify(f.logger).info("Resumed 1 in-flight letter(s).");
      verify(f.sender).sendMessage("Offline");
      f.scheduled.getFirst().run();
      verify(f.store).addPending(future);
      assertTrue(f.inFlight.isEmpty());
    }
  }

  @Test
  void pendingMailReportsCurrentCharacterAndDiscordQueueOutcome() {
    for (boolean queued : new boolean[] {true, false}) {
      try (Fixture f = new Fixture()) {
        when(f.config.discordEnabled()).thenReturn(true);
        when(f.manager.isPluginEnabled("TFMCWeb")).thenReturn(true);
        when(f.owner.isOnline()).thenReturn(true);
        StoredMail mail = f.mail(0);
        f.inFlight.put(mail.getId(), mail);
        List<Consumer<Boolean>> callbacks = new ArrayList<>();
        f.discord
            .when(() -> DiscordNotifyService.notifyFlightComplete(eq(f.plugin), eq(mail), any()))
            .thenAnswer(
                call -> {
                  callbacks.add(call.getArgument(2));
                  return null;
                });
        f.service.resume();
        assertEquals(1, callbacks.size());
        verify(f.store).addPending(mail);
        verify(f.sender, never()).sendMessage(anyString());
        callbacks.getFirst().accept(queued);
        verify(f.sender).sendMessage("Wrong character");
        verify(f.sender).sendMessage(queued ? "DM sent" : "DM failed");
      }
    }
  }

  @Test
  void offlineOrAbsentSenderReceivesNoPendingNotifications() {
    try (Fixture f = new Fixture()) {
      when(f.sender.isOnline()).thenReturn(false);
      StoredMail first = f.mail(0);
      f.inFlight.put(first.getId(), first);
      f.service.resume();
      verify(f.sender, never()).sendMessage(anyString());
      f.bukkit.when(() -> Bukkit.getPlayer(f.senderId)).thenReturn(null);
      StoredMail second = f.mail(0);
      f.inFlight.put(second.getId(), second);
      f.service.resume();
      verify(f.store).addPending(first);
      verify(f.store).addPending(second);
      verify(f.sender, never()).sendMessage(anyString());
    }
  }

  @Test
  void pendingDeliveryRequiresOnlineMatchingCharacterAndFlushChecksEachPlayer() {
    try (Fixture f = new Fixture()) {
      f.service.tryDeliverPending(null, f.ownerSession);
      f.service.tryDeliverPending(f.owner, f.ownerSession);
      when(f.owner.isOnline()).thenReturn(true);
      f.service.tryDeliverPending(f.owner, null);
      f.service.tryDeliverPending(f.owner, f.origin);
      f.service.tryDeliverPending(f.owner, f.ownerSession);
      verify(f.store, never()).pollPending(any());
      f.activeOwner();
      ItemStack letter = mock(ItemStack.class);
      f.queuePending(new MailStore.PendingLetter(f.ownerId, f.senderId, "Recipient", letter));
      f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Arrays.asList(null, f.sender, f.owner));
      f.service.flushPendingForOnlinePlayers();
      verify(f.store, times(2)).pollPending(f.ownerSession.ref());
      verify(f.delivery).deliver(f.owner, letter, f.senderId, "Recipient");
      verify(f.store, never()).takePending(anyString());
    }
  }

  @Test
  void pendingDrainStopsAtACharacterSwitchAndOldTokenCannotResumeAfterSwitchingBack() {
    try (Fixture f = new Fixture()) {
      f.activeOwner();
      ItemStack first = mock(ItemStack.class);
      ItemStack second = mock(ItemStack.class);
      f.queuePending(new MailStore.PendingLetter(f.ownerId, f.senderId, "First", first));
      f.queuePending(new MailStore.PendingLetter(f.ownerId, f.senderId, "Second", second));
      doAnswer(call -> {
        f.current.put(
            f.ownerId, new CharacterSession(new CharacterRef(f.ownerId, UUID.randomUUID()), 3L));
        return null;
      }).when(f.delivery).deliver(f.owner, first, f.senderId, "First");

      f.service.tryDeliverPending(f.owner, f.ownerSession);
      verify(f.delivery).deliver(f.owner, first, f.senderId, "First");
      verify(f.delivery, never()).deliver(f.owner, second, f.senderId, "Second");
      assertSame(second, f.pending.get(f.ownerSession.ref()).getFirst().item);
      verify(f.store).pollPending(f.ownerSession.ref());

      CharacterSession returned = new CharacterSession(f.ownerSession.ref(), 4L);
      f.current.put(f.ownerId, returned);
      f.service.tryDeliverPending(f.owner, f.ownerSession);
      assertEquals(1, f.pending.get(f.ownerSession.ref()).size());
      verify(f.store).pollPending(f.ownerSession.ref());
      f.service.tryDeliverPending(f.owner, returned);
      verify(f.delivery).deliver(f.owner, second, f.senderId, "Second");
      assertTrue(f.pending.get(f.ownerSession.ref()).isEmpty());
    }
  }

  @Test
  void pendingDrainRejectsAnOldBukkitConnectionEvenWhenTheProviderTokenStillMatches() {
    try (Fixture f = new Fixture()) {
      f.activeOwner();
      Player replacement = mock(Player.class);
      when(replacement.getUniqueId()).thenReturn(f.ownerId);
      when(replacement.isOnline()).thenReturn(true);
      f.bukkit.when(() -> Bukkit.getPlayer(f.ownerId)).thenReturn(replacement);
      f.service.tryDeliverPending(f.owner, f.ownerSession);
      verify(f.store, never()).pollPending(any());
      verifyNoInteractions(f.delivery);
    }
  }

  @Test
  void staleSenderTokenCannotSendAfterSwitchingAwayAndBackToTheSameCharacter() {
    try (Fixture f = new Fixture()) {
      ItemStack letter = mock(ItemStack.class);
      f.current.put(
          f.senderId, new CharacterSession(new CharacterRef(f.senderId, UUID.randomUUID()), 3L));
      f.current.put(f.senderId, new CharacterSession(f.origin.ref(), 4L));

      assertFalse(f.service.trySend(f.sender, f.origin, letter, f.target));
      f.assertCapturedRefund(letter);
      f.characters.verifyNoInteractions();
      f.flight.verifyNoInteractions();
    }
  }

  @Test
  void missingReadinessOrAnOldConnectionRefundsOnlyToTheCapturedCharacter() {
    for (String failure : List.of("unready", "offline", "reconnected", "missing-player")) {
      try (Fixture f = new Fixture()) {
        ItemStack letter = mock(ItemStack.class);
        Player supplied = f.sender;
        switch (failure) {
          case "unready" -> f.current.remove(f.senderId);
          case "offline" -> when(f.sender.isOnline()).thenReturn(false);
          case "reconnected" -> {
            Player replacement = mock(Player.class);
            when(replacement.getUniqueId()).thenReturn(f.senderId);
            when(replacement.isOnline()).thenReturn(true);
            f.bukkit.when(() -> Bukkit.getPlayer(f.senderId)).thenReturn(replacement);
          }
          default -> supplied = null;
        }
        assertFalse(f.service.trySend(supplied, f.origin, letter, f.target), failure);
        f.assertCapturedRefund(letter);
        f.characters.verifyNoInteractions();
      }
    }
  }

  @Test
  void characterChangesInsideSendCallbacksCannotQueueMailOrRefundTheSuccessor() {
    for (String callback : List.of("recipient-list", "flight-time", "item-snapshot")) {
      try (Fixture f = new Fixture()) {
        ItemStack letter = mock(ItemStack.class);
        when(letter.clone()).thenReturn(mock(ItemStack.class));
        Runnable switchCharacter = () -> f.current.put(
            f.senderId, new CharacterSession(new CharacterRef(f.senderId, UUID.randomUUID()), 3L));
        f.listRecipient();
        f.flight.when(() -> FlightTime.computeFlightSecondsAtSend(f.config, f.sender, f.target))
            .thenReturn(10);
        switch (callback) {
          case "recipient-list" -> f.characters.when(RPCharacters::listMailTargets).thenAnswer(call -> {
            switchCharacter.run();
            return List.of(new CharacterMailTarget(
                f.ownerId, f.characterId.toString(), "Recipient", "Recipient", null, null, null, null));
          });
          case "flight-time" -> f.flight
              .when(() -> FlightTime.computeFlightSecondsAtSend(f.config, f.sender, f.target))
              .thenAnswer(call -> {
                switchCharacter.run();
                return 10;
              });
          default -> when(letter.clone()).thenAnswer(call -> {
            switchCharacter.run();
            return mock(ItemStack.class);
          });
        }

        assertFalse(f.service.trySend(f.sender, f.origin, letter, f.target), callback);
        f.assertCapturedRefund(letter);
        verify(f.sender, never()).sendMessage("Departed");
      }
    }
  }

  @Test
  void pendingQueueFailureRetainsTheInFlightLetterForARetry() {
    for (String failure : List.of("Conflicting pending owner", "Mail requires recovery")) {
      try (Fixture f = new Fixture()) {
        StoredMail mail = f.mail(0);
        f.inFlight.put(mail.getId(), mail);
        IllegalStateException blocked = new IllegalStateException(failure);
        doThrow(blocked).when(f.store).addPending(mail);

        assertSame(blocked, assertThrows(IllegalStateException.class, f.service::resume));
        assertSame(mail, f.inFlight.get(mail.getId()));
        verify(f.store, never()).removeInFlight(any());
        verifyNoInteractions(f.delivery);
        f.discord.verifyNoInteractions();
        verify(f.sender, never()).sendMessage(anyString());

        doNothing().when(f.store).addPending(mail);
        clearInvocations(f.store);
        f.service.resume();
        var order = inOrder(f.store);
        order.verify(f.store).addPending(mail);
        order.verify(f.store).removeInFlight(mail.getId());
        assertTrue(f.inFlight.isEmpty());
      }
    }
  }

  @Test
  void zeroTimeFlightKeepsAcceptedOwnershipWhenPendingQueueConflicts() {
    try (Fixture f = new Fixture()) {
      f.listRecipient();
      f.flight.when(() -> FlightTime.computeFlightSecondsAtSend(f.config, f.sender, f.target))
          .thenReturn(0);
      ItemStack letter = mock(ItemStack.class);
      ItemStack snapshot = mock(ItemStack.class);
      when(letter.clone()).thenReturn(snapshot);
      IllegalStateException conflict = new IllegalStateException("Pending character has another owner");
      doThrow(conflict).when(f.store).addPending(any(StoredMail.class));

      assertTrue(f.service.trySend(f.sender, f.origin, letter, f.target));
      assertEquals(1, f.inFlight.size());
      StoredMail accepted = f.inFlight.values().iterator().next();
      assertSame(snapshot, accepted.getItem());
      verify(f.store).addPending(accepted);
      verify(f.store, never()).removeInFlight(any());
      verify(f.store, never()).addPending(anyString(), any(MailStore.PendingLetter.class));
      f.give.verifyNoInteractions();
      verify(f.logger).log(eq(Level.SEVERE), contains(accepted.getId().toString()), same(conflict));
    }
  }

  @Test
  void acceptedMailIsScheduledBeforeFeedbackAndFeedbackFailureCannotReturnOwnership() {
    try (Fixture f = new Fixture()) {
      f.listRecipient();
      f.flight.when(() -> FlightTime.computeFlightSecondsAtSend(f.config, f.sender, f.target))
          .thenReturn(10);
      ItemStack letter = mock(ItemStack.class);
      when(letter.clone()).thenReturn(mock(ItemStack.class));
      IllegalStateException disconnected = new IllegalStateException("Feedback unavailable");
      doThrow(disconnected).when(f.sender).sendMessage("Departed");

      assertTrue(f.service.trySend(f.sender, f.origin, letter, f.target));
      StoredMail accepted = f.inFlight.values().iterator().next();
      assertEquals(1, f.scheduled.size());
      var order = inOrder(f.store, f.scheduler, f.sender);
      order.verify(f.store).putInFlight(accepted);
      order.verify(f.scheduler).runTaskLater(eq(f.plugin), any(Runnable.class), anyLong());
      order.verify(f.sender).sendMessage("Departed");
      verify(f.logger).log(eq(Level.SEVERE), contains(accepted.getId().toString()), same(disconnected));
      f.give.verifyNoInteractions();
      verify(f.store, never()).addPending(anyString(), any(MailStore.PendingLetter.class));

      f.activeOwner();
      f.scheduled.getFirst().run();
      verify(f.delivery).deliver(f.owner, accepted);
      assertTrue(f.inFlight.isEmpty());
    }
  }

  @Test
  void failuresBeforeStoreAcceptanceStillPropagateWithoutSchedulingOrRefunding() {
    try (Fixture f = new Fixture()) {
      f.listRecipient();
      f.flight.when(() -> FlightTime.computeFlightSecondsAtSend(f.config, f.sender, f.target))
          .thenReturn(10);
      ItemStack letter = mock(ItemStack.class);
      when(letter.clone()).thenReturn(mock(ItemStack.class));
      IllegalStateException failure = new IllegalStateException("Store requires recovery");
      doThrow(failure).when(f.store).putInFlight(any());

      assertSame(failure, assertThrows(IllegalStateException.class,
          () -> f.service.trySend(f.sender, f.origin, letter, f.target)));
      assertTrue(f.inFlight.isEmpty());
      assertTrue(f.scheduled.isEmpty());
      f.give.verifyNoInteractions();
      verify(f.sender, never()).sendMessage(anyString());
      verifyNoInteractions(f.logger);
    }
  }

  @Test
  void refusalFeedbackFailureDoesNotPropagateAfterReturningTheLetter() {
    for (String refusal : List.of("self", "unavailable", "different-world")) {
      try (Fixture f = new Fixture()) {
        ItemStack letter = mock(ItemStack.class);
        SelectedTarget target = f.target;
        if (refusal.equals("self")) {
          target = new SelectedTarget(f.senderId, "self", "Self", "Self", null, null);
        } else if (refusal.equals("different-world")) {
          f.listRecipient();
          f.flight.when(() -> FlightTime.computeFlightSecondsAtSend(f.config, f.sender, f.target))
              .thenReturn(null);
        } else {
          f.characters.when(RPCharacters::listMailTargets).thenReturn(List.of());
        }
        IllegalStateException failure = new IllegalStateException("Notification failed");
        doThrow(failure).when(f.sender).sendMessage(anyString());

        assertFalse(f.service.trySend(f.sender, f.origin, letter, target), refusal);
        f.give.verify(() -> ItemGive.giveOrDrop(f.sender, letter), times(1));
        verify(f.store, never()).addPending(anyString(), any(MailStore.PendingLetter.class));
        verify(f.logger).log(eq(Level.SEVERE), contains("returning their letter"), same(failure));
        assertTrue(f.inFlight.isEmpty());
      }
    }
  }

  @Test
  void refusalFeedbackFailureDoesNotPropagateAfterQueuingACapturedRefund() {
    try (Fixture f = new Fixture()) {
      ItemStack letter = mock(ItemStack.class);
      f.characters.when(RPCharacters::listMailTargets).thenAnswer(call -> {
        f.current.remove(f.senderId);
        return List.of();
      });
      IllegalStateException failure = new IllegalStateException("Notification failed");
      doThrow(failure).when(f.sender).sendMessage("Unavailable");

      assertFalse(f.service.trySend(f.sender, f.origin, letter, f.target));
      f.assertCapturedRefund(letter);
      verify(f.logger).log(eq(Level.SEVERE), contains("returning their letter"), same(failure));
    }
  }

  @Test
  void actualRefundFailuresStillPropagateToCallerRetention() {
    for (boolean current : new boolean[] {true, false}) {
      try (Fixture f = new Fixture()) {
        ItemStack letter = mock(ItemStack.class);
        IllegalStateException failure = new IllegalStateException("Refund not accepted");
        if (current) {
          f.give.when(() -> ItemGive.giveOrDrop(f.sender, letter)).thenThrow(failure);
        } else {
          f.current.remove(f.senderId);
          doThrow(failure).when(f.store).addPending(anyString(), any(MailStore.PendingLetter.class));
        }
        SelectedTarget self = new SelectedTarget(f.senderId, "self", "Self", "Self", null, null);

        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> f.service.trySend(f.sender, f.origin, letter, self)));
        verify(f.sender, never()).sendMessage(anyString());
        verifyNoInteractions(f.logger);
        assertTrue(f.inFlight.isEmpty());
      }
    }
  }

  private static final class Fixture implements AutoCloseable {
    final BirdMessenger plugin = mock(BirdMessenger.class);
    final BirdConfig config = mock(BirdConfig.class);
    final MailStore store = mock(MailStore.class);
    final Logger logger = mock(Logger.class);
    final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    final PluginManager manager = mock(PluginManager.class);
    final UUID senderId = UUID.randomUUID();
    final UUID ownerId = UUID.randomUUID();
    final UUID characterId = UUID.randomUUID();
    final CharacterSession origin =
        new CharacterSession(new CharacterRef(senderId, UUID.randomUUID()), 1L);
    final CharacterSession ownerSession =
        new CharacterSession(new CharacterRef(ownerId, characterId), 2L);
    final Player sender = mock(Player.class);
    final Player owner = mock(Player.class);
    final SelectedTarget target =
        new SelectedTarget(ownerId, characterId.toString(), "Recipient", "Recipient", null, null);
    final Map<UUID, StoredMail> inFlight = new HashMap<>();
    final Map<UUID, CharacterSession> current = new HashMap<>();
    final Map<CharacterRef, Deque<MailStore.PendingLetter>> pending = new HashMap<>();
    final List<Runnable> scheduled = new ArrayList<>();
    final List<Long> delays = new ArrayList<>();
    final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
    final MockedStatic<RPCharacters> characters = mockStatic(RPCharacters.class);
    final MockedStatic<Characters> identities = mockStatic(Characters.class);
    final MockedStatic<FlightTime> flight = mockStatic(FlightTime.class);
    final MockedStatic<ItemGive> give = mockStatic(ItemGive.class);
    final MockedStatic<DiscordNotifyService> discord = mockStatic(DiscordNotifyService.class);
    final MockedConstruction<MailDelivery> deliveries = mockConstruction(MailDelivery.class);
    final MailService service;
    final MailDelivery delivery;

    Fixture() {
      when(plugin.config()).thenReturn(config);
      when(plugin.getLogger()).thenReturn(logger);
      when(sender.getUniqueId()).thenReturn(senderId);
      when(sender.isOnline()).thenReturn(true);
      when(owner.getUniqueId()).thenReturn(ownerId);
      when(config.msgCannotSendToSelf()).thenReturn("Self forbidden");
      when(config.msgRecipientUnavailable()).thenReturn("Unavailable");
      when(config.msgDifferentWorld()).thenReturn("Different world");
      when(config.msgBirdLeft(10)).thenReturn("Departed");
      when(config.msgWaitingForCharacter()).thenReturn("Wrong character");
      when(config.msgLetterPendingOffline()).thenReturn("Offline");
      when(config.msgDiscordDmSent()).thenReturn("DM sent");
      when(config.msgDiscordDmFailed()).thenReturn("DM failed");
      when(store.inFlight()).thenReturn(inFlight);
      doAnswer(
              call -> {
                StoredMail mail = call.getArgument(0);
                inFlight.put(mail.getId(), mail);
                return null;
              })
          .when(store)
          .putInFlight(any());
      when(store.removeInFlight(any())).thenAnswer(call -> inFlight.remove(call.getArgument(0)));
      when(store.pollPending(any())).thenAnswer(call -> {
        Deque<MailStore.PendingLetter> letters = pending.get(call.getArgument(0));
        return letters == null ? null : letters.pollFirst();
      });
      current.put(senderId, origin);
      identities.when(() -> Characters.currentSession(any(Player.class))).thenAnswer(call -> {
        Player player = call.getArgument(0);
        return Optional.ofNullable(current.get(player.getUniqueId()));
      });
      identities.when(() -> Characters.isCurrent(any(CharacterSession.class))).thenAnswer(call -> {
        CharacterSession session = call.getArgument(0);
        return session.equals(current.get(session.ref().ownerId()));
      });
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
      bukkit.when(() -> Bukkit.getPlayer(senderId)).thenReturn(sender);
      bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(owner);
      when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
          .thenAnswer(
              call -> {
                scheduled.add(call.getArgument(1));
                delays.add(call.getArgument(2));
                return null;
              });
      service = new MailService(plugin, store);
      delivery = deliveries.constructed().getFirst();
    }

    void listRecipient() {
      characters
          .when(RPCharacters::listMailTargets)
          .thenReturn(
              List.of(
                  new CharacterMailTarget(
                      ownerId, characterId.toString(), "Recipient", "Recipient", null, null, null, null)));
    }

    void activeOwner() {
      when(owner.isOnline()).thenReturn(true);
      current.put(ownerId, ownerSession);
    }

    void queuePending(MailStore.PendingLetter letter) {
      pending.computeIfAbsent(ownerSession.ref(), ignored -> new ArrayDeque<>()).add(letter);
    }

    void assertCapturedRefund(ItemStack letter) {
      ArgumentCaptor<MailStore.PendingLetter> refund = ArgumentCaptor.forClass(MailStore.PendingLetter.class);
      verify(store).addPending(eq(origin.ref().characterId().toString()), refund.capture());
      assertEquals(origin.ref().ownerId(), refund.getValue().ownerUuid);
      assertSame(letter, refund.getValue().item);
      assertNull(refund.getValue().senderUuid);
      assertTrue(inFlight.isEmpty());
      assertTrue(scheduled.isEmpty());
      give.verifyNoInteractions();
    }

    StoredMail mail(long time) {
      return new StoredMail(
          UUID.randomUUID(),
          senderId,
          ownerId,
          characterId.toString(),
          "Recipient",
          mock(ItemStack.class),
          time);
    }

    @Override
    public void close() {
      deliveries.close();
      discord.close();
      give.close();
      flight.close();
      identities.close();
      characters.close();
      bukkit.close();
    }
  }
}

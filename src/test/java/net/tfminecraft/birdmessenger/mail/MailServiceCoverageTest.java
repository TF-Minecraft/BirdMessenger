package net.tfminecraft.birdmessenger.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.mail.CharacterMailTarget;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

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
      assertTrue(f.service.trySend(f.sender, letter, f.target));
      assertEquals(1, f.inFlight.size());
      StoredMail mail = f.inFlight.values().iterator().next();
      assertSame(clone, mail.getItem());
      assertEquals(f.senderId, mail.getSenderUuid());
      assertEquals(f.ownerId, mail.getOwnerUuid());
      assertEquals("character", mail.getCharacterId());
      assertEquals("Recipient", mail.getAddresseeDisplayTab());
      assertTrue(mail.getDeliveryTime() >= before + 10_000);
      assertTrue(mail.getDeliveryTime() <= System.currentTimeMillis() + 10_000);
      verify(f.sender).sendMessage("Departed");
      assertEquals(1, f.scheduled.size());
      assertTrue(f.delays.getFirst() > 0 && f.delays.getFirst() <= 200);
      f.activeOwner();
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
      assertFalse(f.service.trySend(null, letter, f.target));
      assertFalse(f.service.trySend(f.sender, null, f.target));
      assertFalse(f.service.trySend(f.sender, letter, null));
      SelectedTarget self = new SelectedTarget(f.senderId, "self", "Self", "Self", null, null);
      assertFalse(f.service.trySend(f.sender, letter, self));
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
      f.service.tryDeliverPending(null, "character");
      f.service.tryDeliverPending(f.owner, "character");
      when(f.owner.isOnline()).thenReturn(true);
      f.service.tryDeliverPending(f.owner, null);
      f.service.tryDeliverPending(f.owner, " ");
      f.service.tryDeliverPending(f.owner, "character");
      verify(f.store, never()).takePending(anyString());
      f.activeOwner();
      ItemStack letter = mock(ItemStack.class);
      when(f.store.takePending("character"))
          .thenReturn(
              List.of(new MailStore.PendingLetter(f.ownerId, f.senderId, "Recipient", letter)));
      f.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Arrays.asList(null, f.sender, f.owner));
      f.service.flushPendingForOnlinePlayers();
      verify(f.store).takePending("character");
      verify(f.delivery).deliver(f.owner, letter, f.senderId, "Recipient");
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
    final Player sender = mock(Player.class);
    final Player owner = mock(Player.class);
    final SelectedTarget target =
        new SelectedTarget(ownerId, "character", "Recipient", "Recipient", null, null);
    final Map<UUID, StoredMail> inFlight = new HashMap<>();
    final List<Runnable> scheduled = new ArrayList<>();
    final List<Long> delays = new ArrayList<>();
    final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
    final MockedStatic<RPCharacters> characters = mockStatic(RPCharacters.class);
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
                      ownerId, "character", "Recipient", "Recipient", null, null, null, null)));
    }

    void activeOwner() {
      when(owner.isOnline()).thenReturn(true);
      RPCharacter character = mock(RPCharacter.class);
      when(character.getId()).thenReturn("character");
      characters.when(() -> RPCharacters.getActiveCharacter(owner)).thenReturn(character);
    }

    StoredMail mail(long time) {
      return new StoredMail(
          UUID.randomUUID(),
          senderId,
          ownerId,
          "character",
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
      characters.close();
      bukkit.close();
    }
  }
}

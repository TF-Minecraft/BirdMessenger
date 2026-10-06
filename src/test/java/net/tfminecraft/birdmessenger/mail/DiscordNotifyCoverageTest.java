package net.tfminecraft.birdmessenger.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.api.BirdMailBridge;
import net.tfminecraft.birdmessenger.util.LetterContents;
import net.tfminecraft.tfmcweb.mail.BirdMailGateway;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

class DiscordNotifyCoverageTest {
  @Test
  void bridgePassesTheGatewayContractAndHandlesFailure() {
    BirdMessenger plugin = mock(BirdMessenger.class);
    Logger logger = mock(Logger.class);
    when(plugin.getLogger()).thenReturn(logger);
    UUID owner = UUID.randomUUID();
    boolean previousResult = BirdMailGateway.result;
    boolean previousFail = BirdMailGateway.fail;
    Object[] previousArrival = BirdMailGateway.arrival;
    try {
      BirdMailGateway.result = true;
      BirdMailGateway.fail = false;
      assertFalse(BirdMailBridge.enqueueArrival(plugin, null, "Alice", null, null));
      assertFalse(BirdMailBridge.enqueueArrival(plugin, owner, null, null, null));
      assertFalse(BirdMailBridge.enqueueArrival(plugin, owner, " ", null, null));
      assertTrue(BirdMailBridge.enqueueArrival(plugin, owner, "Alice", "Sender", "Preview"));
      assertArrayEquals(
          new Object[] {owner, "Alice", "Sender", "Preview"}, BirdMailGateway.arrival);
      BirdMailGateway.result = false;
      assertFalse(BirdMailBridge.enqueueArrival(null, owner, "Alice", null, null));
      BirdMailGateway.fail = true;
      assertFalse(BirdMailBridge.enqueueArrival(plugin, owner, "Alice", null, null));
      verify(logger).log(eq(Level.WARNING), contains(owner.toString()), any(Throwable.class));
    } finally {
      BirdMailGateway.result = previousResult;
      BirdMailGateway.fail = previousFail;
      BirdMailGateway.arrival = previousArrival;
    }
  }

  @Test
  void absentOptionalGatewayIsReportedWithoutDisablingMail() throws Exception {
    BirdMessenger plugin = mock(BirdMessenger.class);
    Logger logger = mock(Logger.class);
    when(plugin.getLogger()).thenReturn(logger);
    String bridge = BirdMailBridge.class.getName();
    ClassLoader isolated =
        new ClassLoader(getClass().getClassLoader()) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("net.tfminecraft.tfmcweb.mail.BirdMailGateway"))
              throw new ClassNotFoundException(name);
            if (!name.equals(bridge)) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
              if (input == null) throw new ClassNotFoundException(name);
              byte[] bytes = input.readAllBytes();
              loaded =
                  defineClass(
                      name, bytes, 0, bytes.length, BirdMailBridge.class.getProtectionDomain());
              if (resolve) resolveClass(loaded);
              return loaded;
            } catch (IOException ex) {
              throw new ClassNotFoundException(name, ex);
            }
          }
        };
    Object result =
        isolated
            .loadClass(bridge)
            .getMethod(
                "enqueueArrival",
                BirdMessenger.class,
                UUID.class,
                String.class,
                String.class,
                String.class)
            .invoke(null, plugin, UUID.randomUUID(), "Alice", null, null);
    assertEquals(false, result);
    verify(logger).warning(contains("BirdMailGateway not found"));
  }

  @Test
  void disabledIntegrationsDoNotQueueAsyncWorkAndCallbacksReturnOnServerTask() {
    BirdMessenger plugin = mock(BirdMessenger.class);
    BirdConfig config = mock(BirdConfig.class);
    when(plugin.config()).thenReturn(config);
    PluginManager manager = mock(PluginManager.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    List<Runnable> serverTasks = new ArrayList<>();
    List<Boolean> outcomes = new ArrayList<>();
    StoredMail mail = mail();
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
      when(scheduler.runTask(eq(plugin), any(Runnable.class)))
          .thenAnswer(
              call -> {
                serverTasks.add(call.getArgument(1));
                return null;
              });
      DiscordNotifyService.notifyFlightComplete(null, mail, outcomes::add);
      assertTrue(serverTasks.isEmpty());
      DiscordNotifyService.notifyFlightComplete(plugin, null, outcomes::add);
      DiscordNotifyService.notifyFlightComplete(plugin, mail, outcomes::add);
      when(config.discordEnabled()).thenReturn(true);
      DiscordNotifyService.notifyFlightComplete(plugin, mail, outcomes::add);
      assertTrue(outcomes.isEmpty());
      assertEquals(3, serverTasks.size());
      serverTasks.forEach(Runnable::run);
      assertEquals(List.of(false, false, false), outcomes);
      DiscordNotifyService.notifyFlightComplete(plugin, mail);
      verify(scheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
    }
  }

  @Test
  void optionalSenderAndPreviewAreIncludedOnlyWhenConfigured() {
    BirdMessenger plugin = mock(BirdMessenger.class);
    BirdConfig config = mock(BirdConfig.class);
    when(plugin.config()).thenReturn(config);
    when(config.discordEnabled()).thenReturn(true);
    PluginManager manager = mock(PluginManager.class);
    when(manager.isPluginEnabled("TFMCWeb")).thenReturn(true);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    StoredMail mail = mail();
    List<Runnable> async = new ArrayList<>();
    List<Runnable> server = new ArrayList<>();
    List<Boolean> outcomes = new ArrayList<>();
    try (var bukkit = mockStatic(Bukkit.class);
        var bridge = mockStatic(BirdMailBridge.class);
        var previews = mockStatic(LetterContents.class)) {
      bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
      bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
      when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
          .thenAnswer(
              call -> {
                async.add(call.getArgument(1));
                return null;
              });
      when(scheduler.runTask(eq(plugin), any(Runnable.class)))
          .thenAnswer(
              call -> {
                server.add(call.getArgument(1));
                return null;
              });
      bridge
          .when(
              () ->
                  BirdMailBridge.enqueueArrival(
                      plugin, mail.getOwnerUuid(), "Recipient", null, null))
          .thenReturn(true);
      DiscordNotifyService.notifyFlightComplete(plugin, mail, outcomes::add);
      bridge.verifyNoInteractions();
      async.removeFirst().run();
      bridge.verify(
          () ->
              BirdMailBridge.enqueueArrival(plugin, mail.getOwnerUuid(), "Recipient", null, null));
      previews.verifyNoInteractions();
      assertTrue(outcomes.isEmpty());
      server.removeFirst().run();
      assertEquals(List.of(true), outcomes);
      when(config.discordIncludeSender()).thenReturn(true);
      when(config.discordIncludeContents()).thenReturn(true);
      Player online = mock(Player.class);
      when(online.getName()).thenReturn("Online sender");
      bukkit.when(() -> Bukkit.getPlayer(mail.getSenderUuid())).thenReturn(online);
      previews.when(() -> LetterContents.preview(mail.getItem(), 500)).thenReturn("Contents");
      DiscordNotifyService.notifyFlightComplete(plugin, mail);
      async.removeFirst().run();
      bridge.verify(
          () ->
              BirdMailBridge.enqueueArrival(
                  plugin, mail.getOwnerUuid(), "Recipient", "Online sender", "Contents"));
      assertTrue(server.isEmpty());
      OfflinePlayer offline = mock(OfflinePlayer.class);
      when(offline.getName()).thenReturn("Offline sender");
      bukkit.when(() -> Bukkit.getPlayer(mail.getSenderUuid())).thenReturn(null);
      bukkit.when(() -> Bukkit.getOfflinePlayer(mail.getSenderUuid())).thenReturn(offline);
      DiscordNotifyService.notifyFlightComplete(plugin, mail);
      async.removeFirst().run();
      bridge.verify(
          () ->
              BirdMailBridge.enqueueArrival(
                  plugin, mail.getOwnerUuid(), "Recipient", "Offline sender", "Contents"));
    }
  }

  private StoredMail mail() {
    return new StoredMail(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "recipient",
        "Recipient",
        mock(ItemStack.class),
        0);
  }
}

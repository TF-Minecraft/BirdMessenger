package net.tfminecraft.birdmessenger.listener;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.mail.MailService;
import net.tfminecraft.birdmessenger.session.SendSessionManager;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent.Reason;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@SuppressWarnings("deprecation")
class PlayerSessionListenerTest {
  private final BirdMessenger plugin = mock(BirdMessenger.class);
  private final SendSessionManager sessions = mock(SendSessionManager.class);
  private final MailService mail = mock(MailService.class);
  private final Player player = mock(Player.class);
  private final CharacterRef ref = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
  private final CharacterSession token = new CharacterSession(ref, 1);

  @BeforeEach
  void registerSharedHandlers() {
    when(plugin.sessions()).thenReturn(sessions);
    when(plugin.mail()).thenReturn(mail);
    when(plugin.getName()).thenReturn("BirdMessenger");
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    Server server = mock(Server.class);
    when(server.getLogger()).thenReturn(Logger.getAnonymousLogger());
    var registrations =
        new JavaPluginLoader(server)
            .createRegisteredListeners(new PlayerSessionListener(plugin), plugin);
    assertEquals(3, registrations.size());
    assertEquals(
        EventPriority.LOWEST,
        registrations.get(CharacterSessionEndEvent.class).iterator().next().getPriority());
    CharacterSessionStartEvent.getHandlerList()
        .registerAll(registrations.get(CharacterSessionStartEvent.class));
    CharacterSessionEndEvent.getHandlerList()
        .registerAll(registrations.get(CharacterSessionEndEvent.class));
    PlayerQuitEvent.getHandlerList().registerAll(registrations.get(PlayerQuitEvent.class));
  }

  @AfterEach
  void unregisterHandlers() {
    HandlerList.unregisterAll(plugin);
  }

  @ParameterizedTest
  @EnumSource(Reason.class)
  void endPassesTheOutgoingTokenBeforeNormalPriorityBodyChanges(Reason reason) throws Exception {
    List<String> order = new ArrayList<>();
    doAnswer(call -> {
      order.add("end");
      return null;
    }).when(sessions).end(player, token);
    CharacterSessionEndEvent.getHandlerList().register(
        new RegisteredListener(
            new Listener() {},
            (listener, event) -> {
              verify(sessions).end(player, token);
              order.add("normal");
            },
            EventPriority.NORMAL,
            plugin,
            false));

    dispatch(new CharacterSessionEndEvent(player, token, reason));

    assertEquals(List.of("end", "normal"), order);
    verifyNoInteractions(mail);
    verify(sessions, never()).returnLetter(any(), anyBoolean());
  }

  @Test
  void startForwardsEachExactGenerationToPendingDelivery() throws Exception {
    CharacterSession replacement = new CharacterSession(ref, 2);

    dispatch(new CharacterSessionStartEvent(player, token));
    dispatch(new CharacterSessionStartEvent(player, replacement));

    var order = inOrder(sessions, mail);
    order.verify(sessions).retryReturns();
    order.verify(mail).tryDeliverPending(same(player), same(token));
    order.verify(sessions).retryReturns();
    order.verify(mail).tryDeliverPending(same(player), same(replacement));
    order.verifyNoMoreInteractions();
  }

  @Test
  void quitUsesTheFallbackRefundWithoutStartingDelivery() throws Exception {
    dispatch(new PlayerQuitEvent(player, Component.empty()));

    verify(sessions).returnLetter(player, false);
    verifyNoMoreInteractions(sessions);
    verifyNoInteractions(mail);
  }

  private static void dispatch(Event event) throws Exception {
    for (RegisteredListener listener : event.getHandlers().getRegisteredListeners()) {
      listener.callEvent(event);
    }
  }
}

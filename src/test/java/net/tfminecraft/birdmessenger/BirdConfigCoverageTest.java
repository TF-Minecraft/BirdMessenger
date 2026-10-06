package net.tfminecraft.birdmessenger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class BirdConfigCoverageTest {
  private final YamlConfiguration yaml = new YamlConfiguration();
  private final BirdMessenger plugin = mock(BirdMessenger.class);
  private final BirdConfig config = new BirdConfig(plugin);

  BirdConfigCoverageTest() {
    when(plugin.getConfig()).thenReturn(yaml);
    when(plugin.letterItemPaths()).thenReturn(List.of("m.books.blank", "m.books.sealed"));
  }

  @Test
  void defaultsAndOverridesAreReadWithoutCaching() {
    assertEquals("iaf(tfmc:bird_coop)", config.coopPath());
    assertEquals("ia.iasurvival:letter", config.letterPath());
    assertEquals(
        List.of(
            "ia.iasurvival:letter",
            "ia.iasurvival:letter_written_letter",
            "ia.iasurvival:letter_open_letter",
            "m.books.blank",
            "m.books.sealed"),
        config.letterPaths());
    assertThrows(UnsupportedOperationException.class, () -> config.letterPaths().add("anything"));
    assertEquals(0.25, config.secondsPerBlock());
    assertEquals(30, config.minSeconds());
    assertEquals(600, config.maxSeconds());
    assertEquals(22000, config.deliveryDelayTicks());
    assertTrue(config.discordEnabled());
    assertFalse(config.discordIncludeSender());
    assertFalse(config.discordIncludeContents());
    assertEquals("Bird Messenger", config.letterTitle());
    assertEquals("Send letter", config.pickerTitle());
    assertEquals(" ", config.paneName());
    yaml.set("coop", "minecraft:barrel");
    yaml.set("letter", "m.books.custom");
    yaml.set("seconds-per-block", 0.5);
    yaml.set("min-seconds", 2);
    yaml.set("max-seconds", 40);
    yaml.set("delivery-delay-ticks", 0);
    yaml.set("discord.enabled", false);
    yaml.set("discord.include-sender", true);
    yaml.set("discord.include-contents", true);
    yaml.set("gui.letter-title", "&aMail");
    yaml.set("gui.picker-title", "&bRecipients");
    yaml.set("gui.pane-name", "&7-");
    assertEquals("minecraft:barrel", config.coopPath());
    assertEquals(List.of("m.books.custom"), config.letterPaths());
    assertEquals(0.5, config.secondsPerBlock());
    assertEquals(2, config.minSeconds());
    assertEquals(40, config.maxSeconds());
    assertEquals(1, config.deliveryDelayTicks());
    assertFalse(config.discordEnabled());
    assertTrue(config.discordIncludeSender());
    assertTrue(config.discordIncludeContents());
    assertEquals("§aMail", config.letterTitle());
    assertEquals("§bRecipients", config.pickerTitle());
    assertEquals("§7-", config.paneName());
    config.reload();
    verify(plugin).reloadConfig();
  }

  @Test
  void messagesUseConfiguredKeysAndSubstituteOnlyTheirOwnPlaceholders() {
    Map<String, Supplier<String>> messages =
        Map.ofEntries(
            Map.entry("only-letters", config::msgOnlyLetters),
            Map.entry("one-letter", config::msgOneLetter),
            Map.entry("rpc-missing", config::msgRpcMissing),
            Map.entry("no-targets", config::msgNoTargets),
            Map.entry("letter-returned", config::msgLetterReturned),
            Map.entry("letter-cancelled", config::msgLetterCancelled),
            Map.entry("picker-select", config::msgPickerSelect),
            Map.entry("picker-confirm-needed", config::msgPickerConfirmNeeded),
            Map.entry("recipient-unavailable", config::msgRecipientUnavailable),
            Map.entry("different-world", config::msgDifferentWorld),
            Map.entry("delivered-sender", config::msgDeliveredSender),
            Map.entry("waiting-for-character", config::msgWaitingForCharacter),
            Map.entry("letter-pending-offline", config::msgLetterPendingOffline),
            Map.entry("cannot-send-to-self", config::msgCannotSendToSelf),
            Map.entry("discord-dm-sent", config::msgDiscordDmSent),
            Map.entry("discord-dm-failed", config::msgDiscordDmFailed));
    for (var entry : messages.entrySet()) {
      assertFalse(entry.getValue().get().isBlank());
      yaml.set("messages." + entry.getKey(), "&a" + entry.getKey());
      assertEquals("§a" + entry.getKey(), entry.getValue().get());
    }
    assertTrue(config.msgBirdLeft(0).contains("1 second"));
    yaml.set("messages.bird-left", "&bArrives in {time}");
    assertEquals("§bArrives in 1 minute 1 second", config.msgBirdLeft(61));
    assertTrue(config.msgDeliveredRecipient(null).endsWith("for ."));
    yaml.set("messages.delivered-recipient", "&aFor {character}");
    assertEquals("§aFor Alice", config.msgDeliveredRecipient("Alice"));
    assertEquals("", BirdConfig.color(null));
    assertEquals("plain", BirdConfig.color("plain"));
  }
}

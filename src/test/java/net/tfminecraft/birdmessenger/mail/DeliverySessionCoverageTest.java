package net.tfminecraft.birdmessenger.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.session.SendSession;
import net.tfminecraft.birdmessenger.session.SendSessionManager;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.birdmessenger.util.LetterContents;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

class DeliverySessionCoverageTest {
  @Test
  void sessionsReturnLettersOnceAndPreserveSelectionUntilCleared() {
    BirdMessenger plugin = mock(BirdMessenger.class);
    BirdConfig config = mock(BirdConfig.class);
    when(plugin.config()).thenReturn(config);
    when(config.msgLetterReturned()).thenReturn("Returned");
    when(config.msgLetterCancelled()).thenReturn("Cancelled");
    SendSessionManager sessions = new SendSessionManager(plugin);
    Player player = mock(Player.class);
    UUID id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    assertNull(sessions.get(null));
    assertNull(sessions.takeLetter(id));
    sessions.clear(null);
    sessions.returnLetter(null, true);
    sessions.returnLetter(player, true);
    SendSession session = sessions.getOrCreate(id);
    assertSame(session, sessions.getOrCreate(id));
    assertEquals(id, session.getPlayerId());
    assertNull(sessions.takeLetter(id));
    assertFalse(session.isConfirmed());
    session.setConfirmed(true);
    assertTrue(session.isConfirmed());
    session.setPickerPage(-1);
    assertEquals(0, session.getPickerPage());
    session.setPickerPage(2);
    assertEquals(2, session.getPickerPage());
    SelectedTarget selected =
        new SelectedTarget(
            UUID.randomUUID(), "character", "§aAlice", "Alice", "texture", "signature");
    session.setSelected(selected);
    assertSame(selected, session.getSelected());
    assertEquals("Alice", selected.getDisplayPlain());
    assertEquals("§aAlice", selected.getDisplayTab());
    assertEquals("texture", selected.getBaseTextureValue());
    assertEquals("signature", selected.getBaseTextureSignature());
    SelectedTarget unnamed = new SelectedTarget(id, "id", null, null, null, null);
    assertEquals("", unnamed.getDisplayTab());
    assertEquals("", unnamed.getDisplayPlain());
    ItemStack letter = mock(ItemStack.class);
    session.setLetter(letter);
    assertSame(letter, sessions.takeLetter(id));
    assertNull(session.getLetter());
    sessions.returnLetter(player, true);
    assertNull(sessions.get(id));
    try (var give = mockStatic(ItemGive.class)) {
      sessions.getOrCreate(id).setLetter(letter);
      sessions.returnLetter(player, true);
      sessions.returnLetter(player, true);
      give.verify(() -> ItemGive.giveOrDrop(player, letter), times(1));
      verify(player).sendMessage("Returned");
      verify(player).sendMessage("Cancelled");
      verify(player).playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
      sessions.getOrCreate(id).setLetter(letter);
      sessions.returnLetter(player, false);
      verify(player, times(1)).sendMessage("Cancelled");
    }
    sessions.getOrCreate(id);
    sessions.clear(id);
    assertNull(sessions.get(id));
  }

  @Test
  void inventoryOverflowDropsOnlyReturnedLeftovers() {
    Player player = mock(Player.class);
    PlayerInventory inventory = mock(PlayerInventory.class);
    World world = mock(World.class);
    Location location = new Location(world, 1, 64, 2);
    when(player.getInventory()).thenReturn(inventory);
    when(player.getWorld()).thenReturn(world);
    when(player.getLocation()).thenReturn(location);
    ItemStack item = mock(ItemStack.class);
    ItemStack remainder = mock(ItemStack.class);
    when(inventory.addItem(item)).thenReturn(new HashMap<>());
    ItemGive.giveOrDrop(null, item);
    ItemGive.giveOrDrop(player, null);
    ItemGive.giveOrDrop(player, item);
    verifyNoInteractions(world);
    HashMap<Integer, ItemStack> leftovers = new HashMap<>();
    leftovers.put(0, remainder);
    when(inventory.addItem(item)).thenReturn(leftovers);
    ItemGive.giveOrDrop(player, item);
    verify(world).dropItemNaturally(location, remainder);
    verify(world, never()).dropItemNaturally(location, item);
  }

  @Test
  void previewsStripFormattingIgnoreEmptyLinesAndHonorCaps() {
    assertNull(LetterContents.preview(null, 5));
    ItemStack item = mock(ItemStack.class);
    Material material = mock(Material.class);
    when(material.isAir()).thenReturn(true);
    when(item.getType()).thenReturn(material);
    assertNull(LetterContents.preview(item, 5));
    when(material.isAir()).thenReturn(false);
    assertNull(LetterContents.preview(item, 5));
    ItemMeta meta = mock(ItemMeta.class);
    when(item.getItemMeta()).thenReturn(meta);
    assertNull(LetterContents.preview(item, 5));
    when(meta.hasDisplayName()).thenReturn(true);
    when(meta.getDisplayName()).thenReturn(null);
    assertNull(LetterContents.preview(item, 5));
    when(meta.getDisplayName()).thenReturn(" §aTreaty ");
    when(meta.hasLore()).thenReturn(true);
    when(meta.getLore()).thenReturn(Arrays.asList(null, " ", "§bSigned", "§r"));
    assertEquals("Treaty | Signed", LetterContents.preview(item, 0));
    assertEquals("Treat", LetterContents.preview(item, 5));
    when(meta.getDisplayName()).thenReturn("x".repeat(501));
    when(meta.hasLore()).thenReturn(false);
    assertEquals("x".repeat(500), LetterContents.preview(item, -1));
  }

  @Test
  void deliveryUsesACloneAndNotifiesOnlyAnOnlineSender() {
    BirdConfig config = mock(BirdConfig.class);
    when(config.msgDeliveredRecipient("§aRecipient")).thenReturn("For recipient");
    when(config.msgDeliveredRecipient("Owner")).thenReturn("For owner");
    when(config.msgDeliveredSender()).thenReturn("Delivered");
    MailDelivery delivery = new MailDelivery(config);
    Player owner = mock(Player.class);
    Player sender = mock(Player.class);
    when(owner.getName()).thenReturn("Owner");
    UUID senderId = UUID.randomUUID();
    ItemStack letter = mock(ItemStack.class);
    ItemStack copy = mock(ItemStack.class);
    when(letter.clone()).thenReturn(copy);
    try (var bukkit = mockStatic(Bukkit.class);
        var give = mockStatic(ItemGive.class)) {
      delivery.deliver(owner, (StoredMail) null);
      delivery.deliver(null, letter, senderId, "Recipient");
      delivery.deliver(owner, null, senderId, "Recipient");
      give.verifyNoInteractions();
      StoredMail mail =
          new StoredMail(
              UUID.randomUUID(),
              senderId,
              UUID.randomUUID(),
              "character",
              "§aRecipient",
              letter,
              123456L);
      assertNotNull(mail.getId());
      assertNotNull(mail.getOwnerUuid());
      assertEquals("character", mail.getCharacterId());
      assertEquals(123456L, mail.getDeliveryTime());
      bukkit.when(() -> Bukkit.getPlayer(senderId)).thenReturn(sender);
      delivery.deliver(owner, mail);
      verify(sender, never()).sendMessage(anyString());
      when(sender.isOnline()).thenReturn(true);
      delivery.deliver(owner, mail);
      verify(sender).sendMessage("Delivered");
      verify(owner, times(2)).sendMessage("For recipient");
      give.verify(() -> ItemGive.giveOrDrop(owner, copy), times(2));
      give.verify(() -> ItemGive.giveOrDrop(owner, letter), never());
      delivery.deliver(owner, letter, null, " ");
      verify(owner).sendMessage("For owner");
      assertEquals(
          "",
          new StoredMail(UUID.randomUUID(), null, UUID.randomUUID(), "id", null, letter, 0)
              .getAddresseeDisplayTab());
    }
  }

  @Test
  void characterMatchingRequiresTheCurrentCharacterAndExactId() {
    Player owner = mock(Player.class);
    RPCharacter character = mock(RPCharacter.class);
    try (var characters = mockStatic(RPCharacters.class)) {
      assertFalse(MailDelivery.isActiveCharacter(null, "id"));
      assertFalse(MailDelivery.isActiveCharacter(owner, null));
      assertFalse(MailDelivery.isActiveCharacter(owner, " "));
      assertFalse(MailDelivery.isActiveCharacter(owner, "id"));
      characters.when(() -> RPCharacters.getActiveCharacter(owner)).thenReturn(character);
      when(character.getId()).thenReturn("id");
      assertTrue(MailDelivery.isActiveCharacter(owner, "id"));
      assertFalse(MailDelivery.isActiveCharacter(owner, "other"));
    }
  }
}

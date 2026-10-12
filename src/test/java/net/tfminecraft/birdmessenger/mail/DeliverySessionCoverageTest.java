package net.tfminecraft.birdmessenger.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.birdmessenger.util.LetterContents;
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
  void returnedLetterArrivesAsTheWritersOwnUnsentLetter() {
    BirdConfig config = mock(BirdConfig.class);
    when(config.msgUnsentReturned()).thenReturn("Unsent back");
    MailDelivery delivery = new MailDelivery(config);
    Player owner = mock(Player.class);
    ItemStack letter = mock(ItemStack.class);
    ItemStack copy = mock(ItemStack.class);
    when(letter.clone()).thenReturn(copy);
    try (var give = mockStatic(ItemGive.class)) {
      delivery.returnUnsent(null, letter);
      delivery.returnUnsent(owner, null);
      give.verifyNoInteractions();
      delivery.returnUnsent(owner, letter);
      give.verify(() -> ItemGive.giveOrDrop(owner, copy));
    }
    verify(owner).sendMessage("Unsent back");
    verify(owner, never()).sendMessage(contains("letter for"));
    verify(owner).playSound((Location) isNull(), eq(Sound.ENTITY_PARROT_FLY), eq(1f), eq(1f));
  }

  @Test
  void characterMatchingRequiresReadyOwnerCanonicalIdAndCurrentConnection() {
    Player owner = mock(Player.class);
    UUID ownerId = UUID.randomUUID();
    UUID characterId = UUID.randomUUID();
    var token = new net.tfminecraft.tlibs.character.CharacterSession(
        new net.tfminecraft.tlibs.character.CharacterRef(ownerId, characterId), 1);
    when(owner.getUniqueId()).thenReturn(ownerId);
    when(owner.isOnline()).thenReturn(true);
    try (var bukkit = mockStatic(Bukkit.class);
         var characters = mockStatic(net.tfminecraft.tlibs.character.Characters.class)) {
      bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(owner);
      characters.when(() -> net.tfminecraft.tlibs.character.Characters.currentSession(owner))
          .thenReturn(java.util.Optional.empty());
      assertFalse(MailDelivery.isActiveCharacter(null, "id"));
      assertFalse(MailDelivery.isActiveCharacter(owner, null));
      assertFalse(MailDelivery.isActiveCharacter(owner, " "));
      assertFalse(MailDelivery.isActiveCharacter(owner, characterId.toString()));
      characters.when(() -> net.tfminecraft.tlibs.character.Characters.currentSession(owner))
          .thenReturn(java.util.Optional.of(token));
      characters.when(() -> net.tfminecraft.tlibs.character.Characters.isCurrent(token)).thenReturn(true);
      assertTrue(MailDelivery.isActiveCharacter(owner, characterId.toString()));
      assertTrue(MailDelivery.isActiveCharacter(owner, characterId.toString().toUpperCase(java.util.Locale.ROOT)));
      assertFalse(MailDelivery.isActiveCharacter(owner, "1-1-1-1-1"));
      assertFalse(MailDelivery.isActiveCharacter(owner, UUID.randomUUID().toString()));
      characters.when(() -> net.tfminecraft.tlibs.character.Characters.isCurrent(token)).thenReturn(false);
      assertFalse(MailDelivery.isActiveCharacter(owner, characterId.toString()));
      characters.when(() -> net.tfminecraft.tlibs.character.Characters.isCurrent(token)).thenReturn(true);
      bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(mock(Player.class));
      assertFalse(MailDelivery.isActiveCharacter(owner, characterId.toString()));
      bukkit.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(owner);
      when(owner.isOnline()).thenReturn(false);
      assertFalse(MailDelivery.isActiveCharacter(owner, characterId.toString()));
      when(owner.isOnline()).thenReturn(true);
      when(owner.getUniqueId()).thenReturn(UUID.randomUUID());
      assertFalse(MailDelivery.isActiveCharacter(owner, characterId.toString()));
    }
  }
}

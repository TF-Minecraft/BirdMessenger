package net.tfminecraft.birdmessenger.gui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.listener.CharacterActivatedListener;
import net.tfminecraft.birdmessenger.listener.CoopListener;
import net.tfminecraft.birdmessenger.listener.PlayerSessionListener;
import net.tfminecraft.birdmessenger.mail.MailService;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.session.SendSession;
import net.tfminecraft.birdmessenger.session.SendSessionManager;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.api.CharacterSkull;
import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;
import net.tfminecraft.rpcharacters.mail.CharacterMailTarget;
import net.tfminecraft.rpcharacters.objects.RPCharacter;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.BlockAPI;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

class GuiPresentationCoverageTest {
  @Test
  void letterGuiLeavesOnlyTheLetterSlotEmptyAndNamesTheBorder() {
    try (Harness h = new Harness()) {
      LetterGui gui = new LetterGui(h.plugin);
      assertEquals(9, gui.getInventory().getSize());
      assertSame(gui, gui.getInventory().getHolder());
      Map<Integer, ItemStack> slots = h.contents.get(gui.getInventory());
      assertEquals(8, slots.size());
      assertFalse(slots.containsKey(LetterGui.LETTER_SLOT));
      ItemStack pane = slots.get(0);
      assertEquals(Material.GRAY_STAINED_GLASS_PANE, pane.getType());
      assertTrue(slots.values().stream().allMatch(item -> item == pane));
      ItemMeta meta = pane.getItemMeta();
      verify(meta).setDisplayName(" ");
      verify(pane).setItemMeta(meta);
    }
  }

  @Test
  void pickerClampsPagesKeepsControlsAndMarksOnlyTheSelectedCharacter() {
    try (Harness h = new Harness()) {
      List<SelectedTarget> targets = new ArrayList<>();
      for (int i = 0; i < 46; i++)
        targets.add(
            new SelectedTarget(
                UUID.randomUUID(),
                "id" + i,
                "§aCharacter " + i,
                "Character " + i,
                "texture" + i,
                "signature" + i));
      CharacterPickerGui gui = new CharacterPickerGui(h.plugin, targets);
      assertSame(targets, gui.targets());
      assertEquals(54, gui.getInventory().getSize());
      assertEquals(1, gui.maxPage());
      Map<Integer, ItemStack> slots = h.contents.get(gui.getInventory());
      assertEquals(48, slots.size());
      assertFalse(slots.containsKey(CharacterPickerGui.SLOT_PREV));
      verify(slots.get(CharacterPickerGui.SLOT_NEXT).getItemMeta()).setDisplayName("§eNext");
      assertSame(targets.getFirst(), gui.targetAt(0, 0));
      assertSame(targets.getLast(), gui.targetAt(1, 0));
      assertNull(gui.targetAt(0, -1));
      assertNull(gui.targetAt(0, 45));
      assertNull(gui.targetAt(-1, 0));
      assertNull(gui.targetAt(2, 0));
      SendSession session = new SendSession(UUID.randomUUID());
      session.setPickerPage(99);
      session.setSelected(targets.getLast());
      CharacterPickerGui.applyPage(session, gui);
      assertEquals(4, slots.size());
      assertTrue(slots.containsKey(CharacterPickerGui.SLOT_PREV));
      assertFalse(slots.containsKey(CharacterPickerGui.SLOT_NEXT));
      ItemMeta selected = slots.get(0).getItemMeta();
      verify(selected).setDisplayName("§aCharacter 45");
      verify(selected).setLore(List.of("§aSelected"));
      verify(selected).addEnchant(Enchantment.UNBREAKING, 1, true);
      verify(selected).addItemFlags(ItemFlag.HIDE_ENCHANTS);
      verify(slots.get(CharacterPickerGui.SLOT_PREV).getItemMeta()).setDisplayName("§ePrevious");
      gui.render(-10, null);
      assertEquals(48, slots.size());
      verify(slots.get(0).getItemMeta()).setLore(List.of());
      gui.setTargets(List.of());
      gui.render(100, null);
      assertEquals(0, gui.maxPage());
      assertEquals(2, slots.size());
      verify(slots.get(CharacterPickerGui.SLOT_CANCEL).getItemMeta()).setDisplayName("§cCancel");
      verify(slots.get(CharacterPickerGui.SLOT_CONFIRM).getItemMeta()).setDisplayName("§aConfirm");
    }
  }

  @Test
  void recipientDirectorySkipsInvalidRecordsAndSortsNamesWithoutLocaleDependence() {
    try (Harness h = new Harness();
        var characters = mockStatic(RPCharacters.class)) {
      assertTrue(CharacterPickerGui.loadTargets().isEmpty());
      PluginManager manager = Bukkit.getPluginManager();
      when(manager.isPluginEnabled("RPCharacters")).thenReturn(true);
      UUID owner = UUID.randomUUID();
      CharacterMailTarget alice =
          new CharacterMailTarget(owner, "alice", "§aAlice", "Alice", null, null, "a", "s");
      CharacterMailTarget zed =
          new CharacterMailTarget(owner, "zed", "Zed", "zed", null, null, null, null);
      CharacterMailTarget invalid =
          new CharacterMailTarget(owner, null, "invalid", "invalid", null, null, null, null);
      characters
          .when(RPCharacters::listMailTargets)
          .thenReturn(Arrays.asList(zed, null, invalid, alice));
      List<SelectedTarget> targets = CharacterPickerGui.loadTargets();
      assertEquals(
          List.of("alice", "zed"), targets.stream().map(SelectedTarget::getCharacterId).toList());
      assertEquals("a", targets.getFirst().getBaseTextureValue());
      assertEquals("s", targets.getFirst().getBaseTextureSignature());
      assertEquals(owner, targets.getFirst().getOwnerUuid());
      assertEquals("§aAlice", targets.getFirst().getDisplayTab());
    }
  }

  @Test
  void coopInteractionOpensOnlyForMainHandRightClickWithoutSneaking() {
    try (Harness h = new Harness();
        var tlibs = mockStatic(TLibs.class)) {
      BlockAPI api = mock(BlockAPI.class, RETURNS_DEEP_STUBS);
      tlibs.when(TLibs::getBlockAPI).thenReturn(api);
      Block block = mock(Block.class);
      when(api.getChecker().checkBlock(block, h.plugin.config().coopPath())).thenReturn(true);
      Player player = mock(Player.class);
      PlayerInteractEvent event = mock(PlayerInteractEvent.class);
      when(event.getPlayer()).thenReturn(player);
      CoopListener listener = new CoopListener(h.plugin);
      when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
      listener.onRightClick(event);
      when(event.getAction()).thenReturn(Action.RIGHT_CLICK_BLOCK);
      listener.onRightClick(event);
      verify(event, never()).setCancelled(anyBoolean());
      when(event.getClickedBlock()).thenReturn(block);
      when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);
      listener.onRightClick(event);
      verify(event).setCancelled(true);
      verify(event).setUseItemInHand(Event.Result.DENY);
      verify(event).setUseInteractedBlock(Event.Result.DENY);
      verify(player, never()).openInventory(any(Inventory.class));
      when(event.getHand()).thenReturn(EquipmentSlot.HAND);
      when(player.isSneaking()).thenReturn(true);
      listener.onRightClick(event);
      verify(player, never()).openInventory(any(Inventory.class));
      when(player.isSneaking()).thenReturn(false);
      listener.onRightClick(event);
      verify(player)
          .openInventory(
              argThat((Inventory inventory) -> inventory.getHolder() instanceof LetterGui));
    }
  }

  @Test
  void quittingReturnsHeldMailAndActivationDeliversForTheActualCharacter() {
    BirdMessenger plugin = mock(BirdMessenger.class);
    SendSessionManager sessions = mock(SendSessionManager.class);
    MailService mail = mock(MailService.class);
    when(plugin.sessions()).thenReturn(sessions);
    when(plugin.mail()).thenReturn(mail);
    Player player = mock(Player.class);
    PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
    when(quit.getPlayer()).thenReturn(player);
    new PlayerSessionListener(plugin).onQuit(quit);
    verify(sessions).returnLetter(player, false);
    CharacterActivatedListener listener = new CharacterActivatedListener(plugin);
    CharacterActivatedEvent event = mock(CharacterActivatedEvent.class);
    listener.onCharacterActivated(event);
    when(event.getOwner()).thenReturn(player);
    listener.onCharacterActivated(event);
    verifyNoInteractions(mail);
    RPCharacter character = mock(RPCharacter.class);
    when(character.getId()).thenReturn("active");
    when(event.getCharacter()).thenReturn(character);
    listener.onCharacterActivated(event);
    verify(mail).tryDeliverPending(player, "active");
  }

  private static final class Harness implements AutoCloseable {
    final BirdMessenger plugin = mock(BirdMessenger.class);
    final Map<Inventory, Map<Integer, ItemStack>> contents = new HashMap<>();
    final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
    final MockedStatic<CharacterSkull> skulls = mockStatic(CharacterSkull.class);
    final MockedConstruction<ItemStack> items =
        mockConstruction(
            ItemStack.class,
            (item, context) -> {
              when(item.getType()).thenReturn((Material) context.arguments().getFirst());
              when(item.getItemMeta()).thenReturn(mock(ItemMeta.class));
            });

    Harness() {
      when(plugin.getConfig()).thenReturn(new YamlConfiguration());
      when(plugin.config()).thenReturn(new BirdConfig(plugin));
      PluginManager manager = mock(PluginManager.class);
      bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
      bukkit
          .when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), anyString()))
          .thenAnswer(
              call -> {
                Inventory inventory = mock(Inventory.class);
                InventoryHolder holder = call.getArgument(0);
                int size = call.getArgument(1);
                Map<Integer, ItemStack> slots = new HashMap<>();
                contents.put(inventory, slots);
                when(inventory.getHolder()).thenReturn(holder);
                when(inventory.getSize()).thenReturn(size);
                doAnswer(
                        set -> {
                          slots.put(set.getArgument(0), set.getArgument(1));
                          return null;
                        })
                    .when(inventory)
                    .setItem(anyInt(), any());
                doAnswer(
                        clear -> {
                          slots.clear();
                          return null;
                        })
                    .when(inventory)
                    .clear();
                return inventory;
              });
      skulls
          .when(() -> CharacterSkull.fromTextures(any(), any()))
          .thenAnswer(
              call -> {
                ItemStack head = mock(ItemStack.class);
                when(head.getItemMeta()).thenReturn(mock(ItemMeta.class));
                return head;
              });
    }

    @Override
    public void close() {
      items.close();
      skulls.close();
      bukkit.close();
    }
  }
}

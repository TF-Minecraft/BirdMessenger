package net.tfminecraft.birdmessenger.listener;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.gui.CharacterPickerGui;
import net.tfminecraft.birdmessenger.gui.LetterGui;
import net.tfminecraft.birdmessenger.mail.MailService;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.session.SendSession;
import net.tfminecraft.birdmessenger.session.SendSessionManager;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.birdmessenger.util.LetterItems;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

class GuiListenerCoverageTest {
  private final UUID playerId = UUID.fromString("dd74c46f-7a9a-4eb3-8da5-8482e12d0ec0");
  private final ItemStack[] topItems = new ItemStack[54];
  private final ItemStack[] playerItems = new ItemStack[41];
  private final Set<ItemStack> letters = Collections.newSetFromMap(new IdentityHashMap<>());
  private final List<Runnable> nextTick = new ArrayList<>();
  private final List<Runnable> later = new ArrayList<>();
  private BirdMessenger plugin;
  private BirdConfig config;
  private Player player;
  private PlayerInventory inventory;
  private Inventory top;
  private InventoryView view;
  private AtomicReference<Inventory> openTop;
  private SendSessionManager sessions;
  private MailService mail;
  private BukkitScheduler scheduler;
  private GuiListener listener;
  private MockedStatic<Bukkit> bukkit;
  private MockedStatic<LetterItems> letterItems;

  @BeforeEach
  void setup() {
    plugin = mock(BirdMessenger.class);
    config = mock(BirdConfig.class);
    when(plugin.config()).thenReturn(config);
    when(config.msgOnlyLetters()).thenReturn("Only letters");
    when(config.msgOneLetter()).thenReturn("One letter");
    when(config.msgLetterReturned()).thenReturn("Returned");
    when(config.msgLetterCancelled()).thenReturn("Cancelled");
    when(config.msgPickerConfirmNeeded()).thenReturn("Select a recipient");
    sessions = new SendSessionManager(plugin);
    when(plugin.sessions()).thenReturn(sessions);
    mail = mock(MailService.class);
    when(plugin.mail()).thenReturn(mail);
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(playerId);
    when(player.getLocation()).thenReturn(mock(Location.class));
    inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    when(inventory.getItem(anyInt())).thenAnswer(call -> playerItems[call.getArgument(0)]);
    when(inventory.getItemInOffHand()).thenAnswer(call -> playerItems[40]);
    doAnswer(
            call -> {
              playerItems[call.getArgument(0)] = call.getArgument(1);
              return null;
            })
        .when(inventory)
        .setItem(anyInt(), any());
    top = mock(Inventory.class);
    when(top.getHolder()).thenReturn(mock(LetterGui.class));
    when(top.getSize()).thenReturn(9);
    when(top.getMaxStackSize()).thenReturn(64);
    when(top.getItem(anyInt())).thenAnswer(call -> topItems[call.getArgument(0)]);
    doAnswer(
            call -> {
              topItems[call.getArgument(0)] = call.getArgument(1);
              return null;
            })
        .when(top)
        .setItem(anyInt(), any());
    openTop = new AtomicReference<>(top);
    view = mock(InventoryView.class);
    when(view.getTopInventory()).thenAnswer(call -> openTop.get());
    when(player.getOpenInventory()).thenReturn(view);
    scheduler = mock(BukkitScheduler.class);
    doAnswer(
            call -> {
              nextTick.add(call.getArgument(1));
              return null;
            })
        .when(scheduler)
        .runTask(eq(plugin), any(Runnable.class));
    doAnswer(
            call -> {
              later.add(call.getArgument(1));
              return null;
            })
        .when(scheduler)
        .runTaskLater(eq(plugin), any(Runnable.class), anyLong());
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
    letterItems = mockStatic(LetterItems.class);
    letterItems
        .when(() -> LetterItems.isLetter(eq(config), any()))
        .thenAnswer(call -> letters.contains(call.getArgument(1)));
    listener = new GuiListener(plugin);
  }

  @AfterEach
  void closeMocks() {
    letterItems.close();
    bukkit.close();
  }

  @Test
  void numberKeyCannotSwapANonLetterIntoTheLetterSlot() {
    ItemStack unrelated = stack(false, 3);
    playerItems[2] = unrelated;
    InventoryClickEvent event = click(null, InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY);
    when(event.getHotbarButton()).thenReturn(2);
    listener.onClick(event);
    assertTrue(event.isCancelled());
    verify(player).sendMessage("Only letters");
    assertSame(unrelated, playerItems[2]);
    assertEquals(3, unrelated.getAmount());
    assertNull(topItems[LetterGui.LETTER_SLOT]);
    assertTrue(later.isEmpty());
  }

  @Test
  void offhandCannotSwapANonLetterIntoTheLetterSlot() {
    ItemStack unrelated = stack(false, 2);
    playerItems[40] = unrelated;
    InventoryClickEvent event = click(null, InventoryAction.HOTBAR_SWAP, ClickType.SWAP_OFFHAND);
    listener.onClick(event);
    assertTrue(event.isCancelled());
    verify(player).sendMessage("Only letters");
    assertSame(unrelated, playerItems[40]);
    assertEquals(2, unrelated.getAmount());
    assertNull(topItems[LetterGui.LETTER_SLOT]);
    assertTrue(later.isEmpty());
  }

  @ParameterizedTest
  @EnumSource(
      value = InventoryAction.class,
      names = {
        "PLACE_FROM_BUNDLE",
        "PICKUP_FROM_BUNDLE",
        "PICKUP_ALL_INTO_BUNDLE",
        "PICKUP_SOME_INTO_BUNDLE",
        "PLACE_ALL_INTO_BUNDLE",
        "PLACE_SOME_INTO_BUNDLE",
        "UNKNOWN"
      })
  void unsupportedActionsCannotTransferBundledItemsIntoTheLetterSlot(InventoryAction action) {
    ItemStack diamond = stack(false, 4);
    when(diamond.getType()).thenReturn(Material.DIAMOND);
    BundleMeta contents = mock(BundleMeta.class);
    when(contents.getItems()).thenReturn(List.of(diamond));
    ItemStack bundle = stack(false, 1);
    when(bundle.getType()).thenReturn(Material.BUNDLE);
    when(bundle.getItemMeta()).thenReturn(contents);
    InventoryClickEvent event = click(bundle, action, ClickType.RIGHT);

    listener.onClick(event);

    assertTrue(event.isCancelled(), "Unvalidated bundle contents must remain with the player");
    assertNull(topItems[LetterGui.LETTER_SLOT]);
    assertSame(bundle, event.getCursor());
    assertEquals(List.of(diamond), contents.getItems());
    assertEquals(4, diamond.getAmount());
    assertTrue(later.isEmpty());
    verifyNoInteractions(mail);
  }

  @ParameterizedTest
  @EnumSource(
      value = InventoryAction.class,
      names = {
        "NOTHING",
        "PICKUP_ALL",
        "PICKUP_SOME",
        "PICKUP_HALF",
        "PICKUP_ONE",
        "DROP_ALL_CURSOR",
        "DROP_ONE_CURSOR",
        "DROP_ALL_SLOT",
        "DROP_ONE_SLOT",
        "MOVE_TO_OTHER_INVENTORY",
        "HOTBAR_MOVE_AND_READD",
        "CLONE_STACK"
      })
  void supportedRemovalAndNoopActionsRemainOwnedByBukkit(InventoryAction action) {
    ItemStack letter = stack(true, 1);
    topItems[LetterGui.LETTER_SLOT] = letter;
    InventoryClickEvent event = click(null, action, ClickType.LEFT);

    listener.onClick(event);

    assertFalse(event.isCancelled());
    assertSame(letter, topItems[LetterGui.LETTER_SLOT]);
    assertEquals(1, letter.getAmount());
    assertTrue(later.isEmpty());
    assertTrue(nextTick.isEmpty());
    verify(player, never()).closeInventory();
    verifyNoInteractions(mail);
  }

  @Test
  void bundleActionsInThePlayerInventoryDoNotAffectTheLetterSlot() {
    ItemStack letter = stack(true, 1);
    topItems[LetterGui.LETTER_SLOT] = letter;
    InventoryClickEvent event =
        click(stack(false, 1), InventoryAction.PLACE_FROM_BUNDLE, ClickType.RIGHT);
    when(event.getClickedInventory()).thenReturn(inventory);

    listener.onClick(event);

    assertFalse(event.isCancelled());
    assertSame(letter, topItems[LetterGui.LETTER_SLOT]);
    assertTrue(later.isEmpty());
  }

  @Test
  void placingAWholeLetterStackIsRejectedWithoutConsumingIt() {
    ItemStack cursor = stack(true, 4);
    InventoryClickEvent event = click(cursor, InventoryAction.PLACE_ALL, ClickType.LEFT);
    listener.onClick(event);
    assertTrue(event.isCancelled());
    verify(player).sendMessage("One letter");
    assertEquals(4, cursor.getAmount());
    assertNull(topItems[LetterGui.LETTER_SLOT]);
    assertTrue(later.isEmpty());
  }

  @Test
  void draggingMultipleLettersIntoTheSlotIsRejectedWithoutConsumingThem() {
    ItemStack cursor = stack(true, 4);
    InventoryDragEvent event =
        drag(cursor, Map.of(LetterGui.LETTER_SLOT, stack(true, 3), 12, stack(true, 1)));
    listener.onDrag(event);
    assertTrue(event.isCancelled());
    verify(player).sendMessage("One letter");
    assertEquals(4, cursor.getAmount());
    assertNull(topItems[LetterGui.LETTER_SLOT]);
  }

  @Test
  void doubleClickFromThePlayerInventoryCannotCollectDecorativePanes() {
    ItemStack pane = stack(false, 1);
    topItems[0] = pane;
    ItemStack cursor = stack(false, 1);
    InventoryClickEvent event =
        click(cursor, InventoryAction.COLLECT_TO_CURSOR, ClickType.DOUBLE_CLICK);
    when(event.getClickedInventory()).thenReturn(inventory);
    listener.onClick(event);
    assertTrue(event.isCancelled());
    assertSame(pane, topItems[0]);
    assertEquals(1, pane.getAmount());
    assertEquals(1, cursor.getAmount());
    assertTrue(later.isEmpty());
  }

  @Test
  void nonPlayersAndUnrelatedInventoriesAreIgnored() {
    InventoryClickEvent click = click(null, InventoryAction.NOTHING, ClickType.LEFT);
    InventoryDragEvent drag = drag(null, Map.of());
    InventoryCloseEvent close = closeEvent();
    HumanEntity nonPlayer = mock(HumanEntity.class);
    when(click.getWhoClicked()).thenReturn(nonPlayer);
    when(drag.getWhoClicked()).thenReturn(nonPlayer);
    when(close.getPlayer()).thenReturn(nonPlayer);
    listener.onClick(click);
    listener.onDrag(drag);
    listener.onClose(close);
    assertFalse(click.isCancelled());
    assertFalse(drag.isCancelled());
    when(top.getHolder()).thenReturn(null);
    listener.onClick(click(null, InventoryAction.PLACE_ALL, ClickType.LEFT));
    listener.onDrag(drag(null, Map.of()));
    listener.onClose(closeEvent());
    assertTrue(later.isEmpty());
    assertTrue(nextTick.isEmpty());
    assertNull(sessions.get(playerId));
    verifyNoInteractions(mail);
  }

  @Test
  void protectedSlotsAreCancelledButOrdinaryPlayerAndOutsideClicksAreAllowed() {
    InventoryClickEvent pane = click(null, InventoryAction.PICKUP_ALL, ClickType.LEFT);
    when(pane.getSlot()).thenReturn(0);
    listener.onClick(pane);
    assertTrue(pane.isCancelled());
    InventoryClickEvent bottom = click(null, InventoryAction.PICKUP_ALL, ClickType.LEFT);
    when(bottom.getClickedInventory()).thenReturn(inventory);
    listener.onClick(bottom);
    assertFalse(bottom.isCancelled());
    InventoryClickEvent outside = click(null, InventoryAction.NOTHING, ClickType.LEFT);
    when(outside.getClickedInventory()).thenReturn(null);
    listener.onClick(outside);
    assertFalse(outside.isCancelled());
    assertTrue(later.isEmpty());
  }

  @Test
  void normalNonLetterPlacementIsRejectedWithoutMutatingTheCursor() {
    ItemStack cursor = stack(false, 5);
    InventoryClickEvent event = click(cursor, InventoryAction.PLACE_ALL, ClickType.LEFT);
    listener.onClick(event);
    assertTrue(event.isCancelled());
    verify(player).sendMessage("Only letters");
    assertEquals(5, cursor.getAmount());
    assertNull(topItems[LetterGui.LETTER_SLOT]);
    assertTrue(later.isEmpty());
  }

  @Test
  void placingOneLetterAllowsVanillaTransferAndClosesTheSameGuiAfterThreeTicks() {
    ItemStack cursor = stack(true, 1);
    InventoryClickEvent event = click(cursor, InventoryAction.PLACE_ALL, ClickType.LEFT);
    listener.onClick(event);
    assertFalse(event.isCancelled());
    assertEquals(1, cursor.getAmount(), "Bukkit owns the uncancelled transfer");
    assertEquals(1, later.size());
    verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(3L));
    verify(player, never()).closeInventory();
    later.getFirst().run();
    verify(player).closeInventory();
  }

  @Test
  void delayedCloseDoesNotCloseAReplacementInventory() {
    listener.onClick(click(stack(true, 1), InventoryAction.PLACE_ALL, ClickType.LEFT));
    openTop.set(mock(Inventory.class));
    later.getFirst().run();
    verify(player, never()).closeInventory();
  }

  @Test
  void rightClickCanPlaceOneFromAStackButCannotMergeASecondLetter() {
    ItemStack cursor = stack(true, 8);
    InventoryClickEvent first = click(cursor, InventoryAction.PLACE_ONE, ClickType.RIGHT);
    listener.onClick(first);
    assertFalse(first.isCancelled());
    assertEquals(1, later.size());
    ItemStack existing = stack(true, 1);
    topItems[LetterGui.LETTER_SLOT] = existing;
    InventoryClickEvent second = click(cursor, InventoryAction.PLACE_ONE, ClickType.RIGHT);
    listener.onClick(second);
    assertTrue(second.isCancelled());
    verify(player).sendMessage("One letter");
    assertEquals(1, later.size(), "Rejected placement must not close the GUI");
    assertSame(existing, topItems[LetterGui.LETTER_SLOT]);
    assertEquals(1, existing.getAmount());
    assertEquals(8, cursor.getAmount());
  }

  @Test
  void partialPlacementHonorsTheActualMaximumStackSize() {
    ItemStack cursor = stack(true, 8);
    when(cursor.getMaxStackSize()).thenReturn(1);
    InventoryClickEvent one = click(cursor, InventoryAction.PLACE_SOME, ClickType.LEFT);
    listener.onClick(one);
    assertFalse(one.isCancelled(), "A max-stack-one item only inserts one");
    when(cursor.getMaxStackSize()).thenReturn(64);
    InventoryClickEvent multiple = click(cursor, InventoryAction.PLACE_SOME, ClickType.LEFT);
    listener.onClick(multiple);
    assertTrue(multiple.isCancelled());
    verify(player).sendMessage("One letter");
    assertEquals(8, cursor.getAmount());
    assertEquals(1, later.size());
  }

  @Test
  void oneLetterCanBeSwappedFromHotbarOrOffhand() {
    ItemStack hotbar = stack(true, 1);
    playerItems[3] = hotbar;
    InventoryClickEvent numberKey = click(null, InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY);
    when(numberKey.getHotbarButton()).thenReturn(3);
    listener.onClick(numberKey);
    assertFalse(numberKey.isCancelled());
    ItemStack offhand = stack(true, 1);
    playerItems[40] = offhand;
    InventoryClickEvent offhandSwap =
        click(null, InventoryAction.HOTBAR_SWAP, ClickType.SWAP_OFFHAND);
    listener.onClick(offhandSwap);
    assertFalse(offhandSwap.isCancelled());
    assertEquals(2, later.size());
    assertSame(hotbar, playerItems[3]);
    assertSame(offhand, playerItems[40]);
  }

  @Test
  void swappingMultipleLettersIsRejectedForBothCursorAndHotbar() {
    ItemStack stack = stack(true, 2);
    playerItems[2] = stack;
    InventoryClickEvent numberKey = click(null, InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY);
    when(numberKey.getHotbarButton()).thenReturn(2);
    listener.onClick(numberKey);
    InventoryClickEvent cursor = click(stack, InventoryAction.SWAP_WITH_CURSOR, ClickType.LEFT);
    listener.onClick(cursor);
    assertTrue(numberKey.isCancelled());
    assertTrue(cursor.isCancelled());
    verify(player, times(2)).sendMessage("One letter");
    assertEquals(2, stack.getAmount());
    assertTrue(later.isEmpty());
  }

  @Test
  void pickingUpALetterDoesNotValidateUnrelatedHotbarItemsOrCloseTheGui() {
    topItems[LetterGui.LETTER_SLOT] = stack(true, 1);
    playerItems[2] = stack(false, 3);
    InventoryClickEvent move =
        click(null, InventoryAction.HOTBAR_MOVE_AND_READD, ClickType.NUMBER_KEY);
    when(move.getHotbarButton()).thenReturn(2);
    listener.onClick(move);
    InventoryClickEvent pickup = click(null, InventoryAction.PICKUP_ALL, ClickType.LEFT);
    listener.onClick(pickup);
    InventoryClickEvent emptyHotbar =
        click(null, InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY);
    when(emptyHotbar.getHotbarButton()).thenReturn(3);
    listener.onClick(emptyHotbar);
    ItemStack air = mock(ItemStack.class);
    when(air.getType()).thenReturn(Material.AIR);
    InventoryClickEvent emptyCursor = click(air, InventoryAction.PLACE_ALL, ClickType.LEFT);
    listener.onClick(emptyCursor);
    assertFalse(move.isCancelled());
    assertFalse(pickup.isCancelled());
    assertFalse(emptyHotbar.isCancelled());
    assertFalse(emptyCursor.isCancelled());
    assertTrue(later.isEmpty());
    verify(player, never()).closeInventory();
    verify(player, never()).sendMessage(anyString());
  }

  @Test
  void dragProtectionAllowsOneLetterAndPlayerSlotsButRejectsProtectedTopSlots() {
    InventoryDragEvent bottom =
        drag(stack(false, 4), Map.of(10, stack(false, 2), 11, stack(false, 2)));
    listener.onDrag(bottom);
    assertFalse(bottom.isCancelled());
    InventoryDragEvent valid =
        drag(stack(true, 2), Map.of(LetterGui.LETTER_SLOT, stack(true, 1), 10, stack(true, 1)));
    listener.onDrag(valid);
    assertFalse(valid.isCancelled());
    InventoryDragEvent pane = drag(stack(true, 1), Map.of(0, stack(true, 1)));
    listener.onDrag(pane);
    assertTrue(pane.isCancelled());
    assertNull(topItems[0]);
    assertNull(topItems[LetterGui.LETTER_SLOT]);
  }

  @Test
  void invalidDragsAreCancelledAndOnlyNonemptyCursorsReceiveAnError() {
    InventoryDragEvent unrelated =
        drag(stack(false, 1), Map.of(LetterGui.LETTER_SLOT, stack(false, 1)));
    listener.onDrag(unrelated);
    assertTrue(unrelated.isCancelled());
    InventoryDragEvent absent = drag(null, Map.of(LetterGui.LETTER_SLOT, stack(false, 1)));
    listener.onDrag(absent);
    assertTrue(absent.isCancelled());
    ItemStack air = mock(ItemStack.class);
    when(air.getType()).thenReturn(Material.AIR);
    InventoryDragEvent empty = drag(air, Map.of(LetterGui.LETTER_SLOT, stack(false, 1)));
    listener.onDrag(empty);
    assertTrue(empty.isCancelled());
    verify(player, times(1)).sendMessage("Only letters");
    usePicker();
    InventoryDragEvent picker = drag(stack(true, 1), Map.of(10, stack(true, 1)));
    listener.onDrag(picker);
    assertTrue(picker.isCancelled());
  }

  @Test
  void shiftClickSkipsEmptyStacksAndRejectsNonLettersOrAnOccupiedLetterSlot() {
    InventoryClickEvent absent = shift(null);
    listener.onClick(absent);
    assertFalse(absent.isCancelled());
    ItemStack air = stack(false, 0);
    when(air.getType().isAir()).thenReturn(true);
    InventoryClickEvent empty = shift(air);
    listener.onClick(empty);
    assertFalse(empty.isCancelled());
    ItemStack invalid = stack(false, 4);
    InventoryClickEvent unrelated = shift(invalid);
    listener.onClick(unrelated);
    assertTrue(unrelated.isCancelled());
    verify(player).sendMessage("Only letters");
    ItemStack existing = stack(true, 1);
    topItems[LetterGui.LETTER_SLOT] = existing;
    ItemStack letter = stack(true, 2);
    InventoryClickEvent occupied = shift(letter);
    listener.onClick(occupied);
    assertTrue(occupied.isCancelled());
    verify(player).sendMessage("One letter");
    assertSame(existing, topItems[LetterGui.LETTER_SLOT]);
    assertEquals(4, invalid.getAmount());
    assertEquals(2, letter.getAmount());
    verify(player, never()).closeInventory();
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3})
  void shiftClickTransfersExactlyOneLetterAndPreservesTheRemainder(int amount) {
    ItemStack source = stack(true, amount);
    InventoryClickEvent event = shift(source);
    listener.onClick(event);
    assertTrue(event.isCancelled(), "The handler owns this transfer");
    ItemStack placed = topItems[LetterGui.LETTER_SLOT];
    assertNotSame(source, placed);
    assertEquals(1, placed.getAmount());
    assertEquals(amount - 1, source.getAmount());
    assertEquals(amount, source.getAmount() + placed.getAmount());
    if (amount == 1) {
      assertNull(event.getCurrentItem());
    } else {
      assertSame(source, event.getCurrentItem());
    }
    verify(player).closeInventory();
    verify(player).playSound(player.getLocation(), Sound.ENTITY_PARROT_FLY, 1f, 1f);
  }

  @Test
  void closingACompletedLetterGuiReturnsTheOldLetterAndStartsAFreshSession() {
    ItemStack previous = stack(true, 1);
    SendSession old = sessions.getOrCreate(playerId);
    old.setLetter(previous);
    old.setSelected(target());
    old.setConfirmed(true);
    old.setPickerPage(3);
    ItemStack placed = stack(true, 1);
    topItems[LetterGui.LETTER_SLOT] = placed;
    try (var give = mockStatic(ItemGive.class)) {
      listener.onClose(closeEvent());
      give.verify(() -> ItemGive.giveOrDrop(player, previous));
      give.verifyNoMoreInteractions();
    }
    assertNull(topItems[LetterGui.LETTER_SLOT]);
    SendSession fresh = sessions.get(playerId);
    assertNotSame(old, fresh);
    assertNotSame(placed, fresh.getLetter());
    assertEquals(1, fresh.getLetter().getAmount());
    assertNull(fresh.getSelected());
    assertFalse(fresh.isConfirmed());
    assertEquals(0, fresh.getPickerPage());
    assertEquals(1, nextTick.size());
    nextTick.getFirst().run();
    verify(plugin).openPicker(player);
  }

  @Test
  void closingAnEmptyLetterGuiDoesNotDisturbAnExistingSession() {
    SendSession session = sessions.getOrCreate(playerId);
    ItemStack letter = stack(true, 1);
    session.setLetter(letter);
    listener.onClose(closeEvent());
    assertSame(session, sessions.get(playerId));
    assertSame(letter, session.getLetter());
    assertTrue(nextTick.isEmpty());
    verifyNoInteractions(mail);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void closingWithAnUnexpectedItemReturnsItOnceAndPreservesThePendingLetter(boolean fullInventory) {
    SendSession session = sessions.getOrCreate(playerId);
    ItemStack letter = stack(true, 1);
    session.setLetter(letter);
    SelectedTarget selected = target();
    session.setSelected(selected);
    session.setConfirmed(true);
    session.setPickerPage(3);
    ItemStack unexpected = stack(false, 4);
    topItems[LetterGui.LETTER_SLOT] = unexpected;
    World world = mock(World.class);
    when(player.getWorld()).thenReturn(world);
    when(inventory.addItem(unexpected))
        .thenReturn(new HashMap<>(fullInventory ? Map.of(0, unexpected) : Map.of()));

    listener.onClose(closeEvent());
    listener.onClose(closeEvent());

    assertNull(topItems[LetterGui.LETTER_SLOT]);
    verify(inventory).addItem(unexpected);
    if (fullInventory) {
      verify(world).dropItemNaturally(player.getLocation(), unexpected);
    } else {
      verifyNoInteractions(world);
    }
    assertEquals(4, unexpected.getAmount());
    assertSame(session, sessions.get(playerId));
    assertSame(letter, session.getLetter());
    assertSame(selected, session.getSelected());
    assertTrue(session.isConfirmed());
    assertEquals(3, session.getPickerPage());
    assertTrue(nextTick.isEmpty());
    verifyNoInteractions(mail);
  }

  @Test
  void closingWithAnAirStackDoesNotCreateARefundOrSession() {
    ItemStack empty = stack(false, 0);
    when(empty.getType().isAir()).thenReturn(true);
    topItems[LetterGui.LETTER_SLOT] = empty;

    listener.onClose(closeEvent());

    verify(inventory, never()).addItem(any(ItemStack.class));
    assertNull(sessions.get(playerId));
    assertTrue(nextTick.isEmpty());
  }

  @Test
  void closingAnUnconfirmedPickerReturnsTheLetterExactlyOnce() {
    usePicker();
    listener.onClose(closeEvent());
    ItemStack letter = stack(true, 1);
    sessions.getOrCreate(playerId).setLetter(letter);
    try (var give = mockStatic(ItemGive.class)) {
      listener.onClose(closeEvent());
      listener.onClose(closeEvent());
      give.verify(() -> ItemGive.giveOrDrop(player, letter));
      give.verifyNoMoreInteractions();
    }
    assertNull(sessions.get(playerId));
    verify(player).sendMessage("Returned");
    verify(player).sendMessage("Cancelled");
  }

  @Test
  void pickerClicksWithoutASessionCloseSafelyAndBottomClicksRemainCancelled() {
    CharacterPickerGui picker = usePicker();
    InventoryClickEvent absent = pickerClick(4);
    listener.onClick(absent);
    assertTrue(absent.isCancelled());
    verify(player).closeInventory();
    clearInvocations(player);
    sessions.getOrCreate(playerId);
    InventoryClickEvent below = pickerClick(54);
    listener.onClick(below);
    assertTrue(below.isCancelled());
    verify(player, never()).closeInventory();
    verify(picker, never()).targetAt(anyInt(), anyInt());
  }

  @Test
  void pickerCancelClosesWithoutSending() {
    usePicker();
    sessions.getOrCreate(playerId).setLetter(stack(true, 1));
    listener.onClick(pickerClick(CharacterPickerGui.SLOT_CANCEL));
    verify(player).closeInventory();
    verifyNoInteractions(mail);
  }

  @Test
  void pickerPaginationClampsBothEndsAndRendersTheResultingPage() {
    CharacterPickerGui picker = usePicker();
    when(picker.maxPage()).thenReturn(2);
    SendSession session = sessions.getOrCreate(playerId);
    listener.onClick(pickerClick(CharacterPickerGui.SLOT_PREV));
    assertEquals(0, session.getPickerPage());
    verify(picker).render(0, null);
    session.setPickerPage(1);
    listener.onClick(pickerClick(CharacterPickerGui.SLOT_NEXT));
    assertEquals(2, session.getPickerPage());
    listener.onClick(pickerClick(CharacterPickerGui.SLOT_NEXT));
    assertEquals(2, session.getPickerPage());
    verify(picker, times(2)).render(2, null);
  }

  @Test
  void pickerSelectionIgnoresEmptySlotsAndUpdatesAValidRecipient() {
    CharacterPickerGui picker = usePicker();
    SendSession session = sessions.getOrCreate(playerId);
    listener.onClick(pickerClick(-999));
    assertNull(session.getSelected());
    SelectedTarget target = target();
    when(picker.targetAt(0, 4)).thenReturn(target);
    listener.onClick(pickerClick(4));
    assertSame(target, session.getSelected());
    verify(picker).render(0, target);
    verify(player).playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
  }

  @Test
  void confirmationRequiresARecipientAndAPendingLetter() {
    usePicker();
    SendSession session = sessions.getOrCreate(playerId);
    listener.onClick(pickerClick(CharacterPickerGui.SLOT_CONFIRM));
    verify(player).sendMessage("Select a recipient");
    verify(player, never()).closeInventory();
    session.setSelected(target());
    listener.onClick(pickerClick(CharacterPickerGui.SLOT_CONFIRM));
    verify(player).closeInventory();
    verifyNoInteractions(mail);
    assertFalse(session.isConfirmed());
  }

  @Test
  void successfulConfirmationCannotReturnTheLetterDuringTheCloseEvent() {
    usePicker();
    SendSession session = sessions.getOrCreate(playerId);
    ItemStack letter = stack(true, 1);
    SelectedTarget target = target();
    session.setLetter(letter);
    session.setSelected(target);
    doAnswer(
            call -> {
              listener.onClose(closeEvent());
              return null;
            })
        .when(player)
        .closeInventory();
    when(mail.trySend(player, letter, target)).thenReturn(true);
    try (var give = mockStatic(ItemGive.class)) {
      listener.onClick(pickerClick(CharacterPickerGui.SLOT_CONFIRM));
      give.verifyNoInteractions();
    }
    verify(mail).trySend(player, letter, target);
    assertTrue(session.isConfirmed());
    assertNull(sessions.get(playerId));
  }

  @Test
  void refusedConfirmationLeavesNoDuplicateLetterInTheSession() {
    usePicker();
    SendSession session = sessions.getOrCreate(playerId);
    ItemStack letter = stack(true, 1);
    SelectedTarget target = target();
    session.setLetter(letter);
    session.setSelected(target);
    doAnswer(
            call -> {
              listener.onClose(closeEvent());
              return null;
            })
        .when(player)
        .closeInventory();
    try (var give = mockStatic(ItemGive.class)) {
      when(mail.trySend(player, letter, target))
          .thenAnswer(
              call -> {
                ItemGive.giveOrDrop(player, letter);
                return false;
              });
      listener.onClick(pickerClick(CharacterPickerGui.SLOT_CONFIRM));
      assertFalse(session.isConfirmed());
      assertNull(session.getLetter());
      listener.onClose(closeEvent());
      give.verify(() -> ItemGive.giveOrDrop(player, letter));
      give.verifyNoMoreInteractions();
    }
    assertNull(sessions.get(playerId));
  }

  private InventoryCloseEvent closeEvent() {
    InventoryCloseEvent event = mock(InventoryCloseEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getInventory()).thenReturn(top);
    return event;
  }

  private CharacterPickerGui usePicker() {
    CharacterPickerGui picker = mock(CharacterPickerGui.class);
    when(top.getHolder()).thenReturn(picker);
    when(top.getSize()).thenReturn(54);
    return picker;
  }

  private InventoryClickEvent pickerClick(int slot) {
    InventoryClickEvent event = click(null, InventoryAction.PICKUP_ALL, ClickType.LEFT);
    when(event.getRawSlot()).thenReturn(slot);
    return event;
  }

  private InventoryClickEvent shift(ItemStack current) {
    InventoryClickEvent event =
        click(null, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT);
    AtomicReference<ItemStack> item = new AtomicReference<>(current);
    when(event.getClickedInventory()).thenReturn(inventory);
    when(event.isShiftClick()).thenReturn(true);
    when(event.getCurrentItem()).thenAnswer(call -> item.get());
    doAnswer(
            call -> {
              item.set(call.getArgument(0));
              return null;
            })
        .when(event)
        .setCurrentItem(any());
    return event;
  }

  private SelectedTarget target() {
    return new SelectedTarget(
        UUID.fromString("5d2794b2-128f-47b5-a4f2-ae2fbbeb2a1f"),
        "recipient",
        "Alice",
        "Alice",
        null,
        null);
  }

  private InventoryClickEvent click(ItemStack cursor, InventoryAction action, ClickType click) {
    InventoryClickEvent event = mock(InventoryClickEvent.class);
    AtomicBoolean cancelled = new AtomicBoolean();
    when(event.getWhoClicked()).thenReturn(player);
    when(event.getView()).thenReturn(view);
    when(event.getClickedInventory()).thenReturn(top);
    when(event.getSlot()).thenReturn(LetterGui.LETTER_SLOT);
    when(event.getRawSlot()).thenReturn(LetterGui.LETTER_SLOT);
    when(event.getCursor()).thenReturn(cursor);
    when(event.getAction()).thenReturn(action);
    when(event.getClick()).thenReturn(click);
    when(event.getHotbarButton()).thenReturn(-1);
    when(event.isCancelled()).thenAnswer(call -> cancelled.get());
    doAnswer(
            call -> {
              cancelled.set(call.getArgument(0));
              return null;
            })
        .when(event)
        .setCancelled(anyBoolean());
    return event;
  }

  private InventoryDragEvent drag(ItemStack cursor, Map<Integer, ItemStack> newItems) {
    InventoryDragEvent event = mock(InventoryDragEvent.class);
    AtomicBoolean cancelled = new AtomicBoolean();
    when(event.getWhoClicked()).thenReturn(player);
    when(event.getView()).thenReturn(view);
    when(event.getOldCursor()).thenReturn(cursor);
    when(event.getRawSlots()).thenReturn(newItems.keySet());
    when(event.getNewItems()).thenReturn(newItems);
    when(event.isCancelled()).thenAnswer(call -> cancelled.get());
    doAnswer(
            call -> {
              cancelled.set(call.getArgument(0));
              return null;
            })
        .when(event)
        .setCancelled(anyBoolean());
    return event;
  }

  private ItemStack stack(boolean letter, int count) {
    AtomicInteger amount = new AtomicInteger(count);
    ItemStack stack = mock(ItemStack.class);
    Material type = mock(Material.class);
    when(type.isAir()).thenReturn(false);
    when(stack.getType()).thenReturn(type);
    when(stack.getAmount()).thenAnswer(call -> amount.get());
    when(stack.getMaxStackSize()).thenReturn(64);
    doAnswer(
            call -> {
              amount.set(call.getArgument(0));
              return null;
            })
        .when(stack)
        .setAmount(anyInt());
    when(stack.clone()).thenAnswer(call -> stack(letter, amount.get()));
    if (letter) letters.add(stack);
    return stack;
  }
}

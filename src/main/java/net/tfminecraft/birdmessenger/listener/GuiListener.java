package net.tfminecraft.birdmessenger.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.gui.CharacterPickerGui;
import net.tfminecraft.birdmessenger.gui.LetterGui;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.session.SendSession;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.birdmessenger.util.LetterItems;

public final class GuiListener implements Listener {

	private final BirdMessenger plugin;

	public GuiListener(BirdMessenger plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (event.getView().getTopInventory().getHolder() instanceof LetterGui) {
			handleLetterClick(event, player);
			return;
		}
		if (event.getView().getTopInventory().getHolder() instanceof CharacterPickerGui picker) {
			handlePickerClick(event, player, picker);
		}
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		Inventory top = event.getView().getTopInventory();
		if (top.getHolder() instanceof LetterGui) {
			for (int rawSlot : event.getRawSlots()) {
				if (rawSlot >= top.getSize()) {
					continue;
				}
				if (rawSlot != LetterGui.LETTER_SLOT) {
					event.setCancelled(true);
					return;
				}
				ItemStack cursor = event.getOldCursor();
				if (!LetterItems.isLetter(plugin.config(), cursor)) {
					event.setCancelled(true);
					if (cursor != null && cursor.getType() != Material.AIR) {
						player.sendMessage(plugin.config().msgOnlyLetters());
					}
				} else if (event.getNewItems().get(rawSlot).getAmount() > 1) {
					event.setCancelled(true);
					player.sendMessage(plugin.config().msgOneLetter());
					return;
				}
			}
			return;
		}
		if (top.getHolder() instanceof CharacterPickerGui) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (!(event.getPlayer() instanceof Player player)) {
			return;
		}
		if (event.getInventory().getHolder() instanceof LetterGui) {
			ItemStack placed = event.getInventory().getItem(LetterGui.LETTER_SLOT);
			if (!LetterItems.isLetter(plugin.config(), placed)) {
				if (placed != null && !placed.getType().isAir()) {
					event.getInventory().setItem(LetterGui.LETTER_SLOT, null);
					ItemGive.giveOrDrop(player, placed);
				}
				return;
			}
			event.getInventory().setItem(LetterGui.LETTER_SLOT, null);
			// A second letter must not overwrite one that is still waiting for a recipient.
			plugin.sessions().returnLetter(player, false);
			SendSession session = plugin.sessions().getOrCreate(player.getUniqueId());
			session.setLetter(placed.clone());
			session.setSelected(null);
			session.setConfirmed(false);
			session.setPickerPage(0);
			Bukkit.getScheduler().runTask(plugin, () -> plugin.openPicker(player));
			return;
		}
		if (event.getInventory().getHolder() instanceof CharacterPickerGui) {
			SendSession session = plugin.sessions().get(player.getUniqueId());
			if (session == null || session.isConfirmed()) {
				return;
			}
			plugin.sessions().returnLetter(player, true);
		}
	}

	private void handleLetterClick(InventoryClickEvent event, Player player) {
		Inventory top = event.getView().getTopInventory();
		// Collect-to-cursor can pull protected panes from the top even when clicked below it.
		if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
			event.setCancelled(true);
			return;
		}
		if (event.getClickedInventory() == top) {
			if (event.getSlot() != LetterGui.LETTER_SLOT) {
				event.setCancelled(true);
				return;
			}
			if (!supportsLetterSlot(event.getAction())) {
				event.setCancelled(true);
				return;
			}
			ItemStack incoming = incomingItem(event, player);
			boolean letter = LetterItems.isLetter(plugin.config(), incoming);
			if (incoming != null && incoming.getType() != Material.AIR && !letter) {
				event.setCancelled(true);
				player.sendMessage(plugin.config().msgOnlyLetters());
				return;
			}
			if (letter) {
				if (placesMultiple(event, top, incoming)) {
					event.setCancelled(true);
					player.sendMessage(plugin.config().msgOneLetter());
					return;
				}
				Bukkit.getScheduler().runTaskLater(plugin, () -> {
					// The player may already have closed this GUI and moved on to another one.
					if (player.getOpenInventory().getTopInventory() == top) {
						player.closeInventory();
					}
				}, 3L);
			}
			return;
		}
		if (event.getClickedInventory() == player.getInventory() && event.isShiftClick()) {
			ItemStack current = event.getCurrentItem();
			if (current == null || current.getType().isAir()) {
				return;
			}
			event.setCancelled(true);
			if (!LetterItems.isLetter(plugin.config(), current)) {
				player.sendMessage(plugin.config().msgOnlyLetters());
				return;
			}
			ItemStack existing = top.getItem(LetterGui.LETTER_SLOT);
			if (existing != null && !existing.getType().isAir()) {
				player.sendMessage(plugin.config().msgOneLetter());
				return;
			}
			ItemStack one = current.clone();
			one.setAmount(1);
			current.setAmount(current.getAmount() - 1);
			if (current.getAmount() <= 0) {
				event.setCurrentItem(null);
			}
			top.setItem(LetterGui.LETTER_SLOT, one);
			player.closeInventory();
			player.playSound(player.getLocation(), Sound.ENTITY_PARROT_FLY, 1f, 1f);
		}
	}

	private static boolean supportsLetterSlot(InventoryAction action) {
		// Bundle contents and future actions need their own incoming-item validation.
		return switch (action) {
			case NOTHING, PICKUP_ALL, PICKUP_SOME, PICKUP_HALF, PICKUP_ONE,
					PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR,
					DROP_ALL_CURSOR, DROP_ONE_CURSOR, DROP_ALL_SLOT, DROP_ONE_SLOT,
					MOVE_TO_OTHER_INVENTORY, HOTBAR_MOVE_AND_READD, HOTBAR_SWAP, CLONE_STACK -> true;
			default -> false;
		};
	}

	private static ItemStack incomingItem(InventoryClickEvent event, Player player) {
		return switch (event.getAction()) {
			case HOTBAR_SWAP -> event.getClick() == ClickType.SWAP_OFFHAND
					? player.getInventory().getItemInOffHand()
					: event.getHotbarButton() < 0 ? null : player.getInventory().getItem(event.getHotbarButton());
			case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR -> event.getCursor();
			default -> null;
		};
	}

	private static boolean placesMultiple(InventoryClickEvent event, Inventory top, ItemStack incoming) {
		ItemStack existing = top.getItem(LetterGui.LETTER_SLOT);
		int current = existing == null ? 0 : existing.getAmount();
		return switch (event.getAction()) {
			case PLACE_ONE -> current >= 1;
			case PLACE_ALL -> (long) current + incoming.getAmount() > 1;
			case PLACE_SOME -> Math.min((long) current + incoming.getAmount(),
					Math.min(top.getMaxStackSize(), incoming.getMaxStackSize())) > 1;
			default -> incoming.getAmount() > 1;
		};
	}

	private void handlePickerClick(InventoryClickEvent event, Player player, CharacterPickerGui picker) {
		event.setCancelled(true);
		SendSession session = plugin.sessions().get(player.getUniqueId());
		if (session == null) {
			player.closeInventory();
			return;
		}
		int slot = event.getRawSlot();
		if (slot >= event.getView().getTopInventory().getSize()) {
			return;
		}
		if (slot == CharacterPickerGui.SLOT_CANCEL) {
			player.closeInventory();
			return;
		}
		if (slot == CharacterPickerGui.SLOT_PREV) {
			session.setPickerPage(session.getPickerPage() - 1);
			CharacterPickerGui.applyPage(session, picker);
			return;
		}
		if (slot == CharacterPickerGui.SLOT_NEXT) {
			session.setPickerPage(Math.min(picker.maxPage(), session.getPickerPage() + 1));
			CharacterPickerGui.applyPage(session, picker);
			return;
		}
		if (slot == CharacterPickerGui.SLOT_CONFIRM) {
			SelectedTarget selected = session.getSelected();
			if (selected == null) {
				player.sendMessage(plugin.config().msgPickerConfirmNeeded());
				return;
			}
			ItemStack letter = session.getLetter();
			if (letter == null) {
				player.closeInventory();
				return;
			}
			session.setConfirmed(true);
			player.closeInventory();
			if (!plugin.mail().trySend(player, letter, selected)) {
				session.setConfirmed(false);
				session.setLetter(null);
				return;
			}
			plugin.sessions().clear(player.getUniqueId());
			return;
		}
		SelectedTarget clicked = picker.targetAt(session.getPickerPage(), slot);
		if (clicked == null) {
			return;
		}
		session.setSelected(clicked);
		CharacterPickerGui.applyPage(session, picker);
		player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
	}
}

package net.tfminecraft.birdmessenger.listener;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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
				return;
			}
			event.getInventory().setItem(LetterGui.LETTER_SLOT, null);
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
		if (event.getClickedInventory() == top) {
			if (event.getSlot() != LetterGui.LETTER_SLOT) {
				event.setCancelled(true);
				return;
			}
			ItemStack cursor = event.getCursor();
			if (cursor != null && cursor.getType() != Material.AIR
					&& !LetterItems.isLetter(plugin.config(), cursor)) {
				event.setCancelled(true);
				player.sendMessage(plugin.config().msgOnlyLetters());
				return;
			}
			if (LetterItems.isLetter(plugin.config(), cursor)) {
				Bukkit.getScheduler().runTaskLater(plugin, () -> player.closeInventory(), 3L);
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

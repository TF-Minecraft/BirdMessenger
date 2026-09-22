package net.tfminecraft.birdmessenger.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.birdmessenger.BirdMessenger;

public final class LetterGui implements InventoryHolder {

	public static final int LETTER_SLOT = 4;

	private final Inventory inventory;

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	public LetterGui(BirdMessenger plugin) {
		this.inventory = Bukkit.createInventory(this, 9, plugin.config().letterTitle());
		ItemStack pane = pane(plugin);
		for (int i = 0; i < 9; i++) {
			if (i != LETTER_SLOT) {
				inventory.setItem(i, pane);
			}
		}
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	private static ItemStack pane(BirdMessenger plugin) {
		ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
		ItemMeta meta = pane.getItemMeta();
		if (meta != null) {
			meta.setDisplayName(plugin.config().paneName());
			pane.setItemMeta(meta);
		}
		return pane;
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}
}

package net.tfminecraft.birdmessenger.util;

import java.util.HashMap;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class ItemGive {

	private ItemGive() {}

	public static void giveOrDrop(Player player, ItemStack item) {
		if (player == null || item == null) {
			return;
		}
		HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
		if (leftovers.isEmpty()) {
			return;
		}
		for (ItemStack leftover : leftovers.values()) {
			player.getWorld().dropItemNaturally(player.getLocation(), leftover);
		}
	}
}

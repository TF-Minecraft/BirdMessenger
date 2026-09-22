package net.tfminecraft.birdmessenger.util;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.birdmessenger.BirdConfig;

public final class LetterItems {

	private LetterItems() {}

	public static boolean isLetter(BirdConfig config, ItemStack item) {
		if (item == null || item.getType().isAir() || config == null) {
			return false;
		}
		for (String path : config.letterPaths()) {
			if (TLibs.getItemAPI().getChecker().checkItemWithPath(item, path)) {
				return true;
			}
		}
		return false;
	}
}

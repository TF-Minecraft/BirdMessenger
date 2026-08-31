package net.tfminecraft.birdmessenger.util;

import org.bukkit.inventory.ItemStack;

import me.Plugins.TLibs.TLibs;
import net.tfminecraft.birdmessenger.BirdConfig;

public final class LetterItems {

	private LetterItems() {}

	public static boolean isLetter(BirdConfig config, ItemStack item) {
		if (item == null || item.getType().isAir() || config == null) {
			return false;
		}
		return TLibs.getItemAPI().getChecker().checkItemWithPath(item, config.letterPath());
	}
}

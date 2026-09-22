package net.tfminecraft.birdmessenger.util;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class LetterContents {

	private static final int DEFAULT_MAX = 500;

	private LetterContents() {}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	public static String preview(ItemStack item, int maxLength) {
		if (item == null || item.getType().isAir()) {
			return null;
		}
		int cap = maxLength > 0 ? maxLength : DEFAULT_MAX;
		List<String> parts = new ArrayList<>();
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			if (meta.hasDisplayName()) {
				String name = strip(meta.getDisplayName());
				if (!name.isEmpty()) {
					parts.add(name);
				}
			}
			if (meta.hasLore()) {
				for (String line : meta.getLore()) {
					if (line == null) {
						continue;
					}
					String plain = strip(line);
					if (!plain.isEmpty()) {
						parts.add(plain);
					}
				}
			}
		}
		if (parts.isEmpty()) {
			return null;
		}
		String joined = String.join(" | ", parts);
		if (joined.length() <= cap) {
			return joined;
		}
		return joined.substring(0, cap);
	}

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	private static String strip(String input) {
		if (input == null) {
			return "";
		}
		return ChatColor.stripColor(input).trim();
	}
}

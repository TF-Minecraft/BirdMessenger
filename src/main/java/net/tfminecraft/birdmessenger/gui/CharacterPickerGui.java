package net.tfminecraft.birdmessenger.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.tfminecraft.RPCharacters.RPCharacters;
import net.tfminecraft.RPCharacters.api.CharacterSkull;
import net.tfminecraft.RPCharacters.mail.CharacterMailTarget;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.session.SendSession;

public final class CharacterPickerGui implements InventoryHolder {

	public static final int PAGE_SIZE = 45;
	public static final int SLOT_PREV = 45;
	public static final int SLOT_CANCEL = 49;
	public static final int SLOT_CONFIRM = 50;
	public static final int SLOT_NEXT = 53;

	private final BirdMessenger plugin;
	private final List<SelectedTarget> targets;
	private final Inventory inventory;

	public CharacterPickerGui(BirdMessenger plugin, List<SelectedTarget> targets) {
		this.plugin = plugin;
		this.targets = targets;
		this.inventory = Bukkit.createInventory(this, 54, plugin.config().pickerTitle());
		render(0, null);
	}

	public List<SelectedTarget> targets() {
		return targets;
	}

	public void render(int page, SelectedTarget selected) {
		inventory.clear();
		int maxPage = maxPage();
		page = Math.max(0, Math.min(page, maxPage));
		int start = page * PAGE_SIZE;
		for (int i = 0; i < PAGE_SIZE; i++) {
			int index = start + i;
			if (index >= targets.size()) {
				break;
			}
			inventory.setItem(i, skull(targets.get(index), selected));
		}
		if (page > 0) {
			inventory.setItem(SLOT_PREV, named(Material.ARROW, ChatColor.YELLOW + "Previous"));
		}
		inventory.setItem(SLOT_CANCEL, named(Material.BARRIER, ChatColor.RED + "Cancel"));
		inventory.setItem(SLOT_CONFIRM, named(Material.LIME_WOOL, ChatColor.GREEN + "Confirm"));
		if (page < maxPage) {
			inventory.setItem(SLOT_NEXT, named(Material.ARROW, ChatColor.YELLOW + "Next"));
		}
	}

	public int maxPage() {
		if (targets.isEmpty()) {
			return 0;
		}
		return (targets.size() - 1) / PAGE_SIZE;
	}

	public SelectedTarget targetAt(int page, int slot) {
		if (slot < 0 || slot >= PAGE_SIZE) {
			return null;
		}
		int index = page * PAGE_SIZE + slot;
		if (index < 0 || index >= targets.size()) {
			return null;
		}
		return targets.get(index);
	}

	private ItemStack skull(SelectedTarget target, SelectedTarget selected) {
		ItemStack head = CharacterSkull.fromTextures(
				target.getBaseTextureValue(), target.getBaseTextureSignature());
		ItemMeta meta = head.getItemMeta();
		if (meta != null) {
			meta.setDisplayName(target.getDisplayTab());
			List<String> lore = new ArrayList<>();
			boolean isSelected = selected != null && selected.getCharacterId().equals(target.getCharacterId());
			if (isSelected) {
				lore.add(ChatColor.GREEN + "Selected");
				meta.addEnchant(Enchantment.UNBREAKING, 1, true);
				meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
			}
			meta.setLore(lore);
			head.setItemMeta(meta);
		}
		return head;
	}

	private static ItemStack named(Material material, String name) {
		ItemStack item = new ItemStack(material);
		ItemMeta meta = item.getItemMeta();
		if (meta != null) {
			meta.setDisplayName(name);
			item.setItemMeta(meta);
		}
		return item;
	}

	public static List<SelectedTarget> loadTargets() {
		List<SelectedTarget> list = new ArrayList<>();
		if (!Bukkit.getPluginManager().isPluginEnabled("RPCharacters")) {
			return list;
		}
		for (CharacterMailTarget target : RPCharacters.listMailTargets()) {
			if (target == null || target.getCharacterId() == null) {
				continue;
			}
			list.add(new SelectedTarget(
					target.getOwnerUuid(),
					target.getCharacterId(),
					target.getDisplayTab(),
					target.getDisplayPlain(),
					target.getBaseTextureValue(),
					target.getBaseTextureSignature()));
		}
		list.sort(Comparator.comparing(
				(SelectedTarget t) -> t.getDisplayPlain().toLowerCase(Locale.ROOT)));
		return list;
	}

	public static void applyPage(SendSession session, CharacterPickerGui gui) {
		gui.render(session.getPickerPage(), session.getSelected());
	}

	@Override
	public Inventory getInventory() {
		return inventory;
	}
}

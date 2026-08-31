package net.tfminecraft.birdmessenger.listener;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import me.Plugins.TLibs.TLibs;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.gui.LetterGui;

public final class CoopListener implements Listener {

	private final BirdMessenger plugin;

	public CoopListener(BirdMessenger plugin) {
		this.plugin = plugin;
	}

	/**
	 * LOWEST so cancellation runs before the letter item handler (typically NORMAL/HIGH).
	 * HIGHEST runs last and cannot stop an item use that already fired.
	 */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onRightClick(PlayerInteractEvent event) {
		if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		Block block = event.getClickedBlock();
		if (block == null) {
			return;
		}
		if (!TLibs.getBlockAPI().getChecker().checkBlock(block, plugin.config().coopPath())) {
			return;
		}

		event.setCancelled(true);
		event.setUseItemInHand(Event.Result.DENY);
		event.setUseInteractedBlock(Event.Result.DENY);

		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		Player player = event.getPlayer();
		if (player.isSneaking()) {
			return;
		}
		player.openInventory(new LetterGui(plugin).getInventory());
	}
}

package net.tfminecraft.birdmessenger.mail;

import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.api.BirdMailBridge;
import net.tfminecraft.birdmessenger.util.LetterContents;

public final class DiscordNotifyService {

	private DiscordNotifyService() {}

	public static void notifyFlightComplete(BirdMessenger plugin, StoredMail mail) {
		notifyFlightComplete(plugin, mail, null);
	}

	public static void notifyFlightComplete(
			BirdMessenger plugin,
			StoredMail mail,
			Consumer<Boolean> onQueued) {
		if (plugin == null || mail == null) {
			notifyQueued(plugin, onQueued, false);
			return;
		}
		if (!plugin.config().discordEnabled()) {
			notifyQueued(plugin, onQueued, false);
			return;
		}
		if (!Bukkit.getPluginManager().isPluginEnabled("TFMCWeb")) {
			notifyQueued(plugin, onQueued, false);
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			String senderName = null;
			if (plugin.config().discordIncludeSender()) {
				Player online = Bukkit.getPlayer(mail.getSenderUuid());
				if (online != null) {
					senderName = online.getName();
				} else {
					senderName = Bukkit.getOfflinePlayer(mail.getSenderUuid()).getName();
				}
			}
			String contents = null;
			if (plugin.config().discordIncludeContents()) {
				contents = LetterContents.preview(mail.getItem(), 500);
			}
			boolean queued = BirdMailBridge.enqueueArrival(
					plugin,
					mail.getOwnerUuid(),
					mail.getAddresseeDisplayTab(),
					senderName,
					contents);
			notifyQueued(plugin, onQueued, queued);
		});
	}

	private static void notifyQueued(BirdMessenger plugin, Consumer<Boolean> onQueued, boolean queued) {
		if (onQueued == null || plugin == null) {
			return;
		}
		Bukkit.getScheduler().runTask(plugin, () -> onQueued.accept(queued));
	}
}

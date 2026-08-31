package net.tfminecraft.birdmessenger.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.util.ItemGive;

public final class SendSessionManager {

	private final BirdMessenger plugin;
	private final Map<UUID, SendSession> sessions = new ConcurrentHashMap<>();

	public SendSessionManager(BirdMessenger plugin) {
		this.plugin = plugin;
	}

	public SendSession get(UUID playerId) {
		return playerId == null ? null : sessions.get(playerId);
	}

	public SendSession getOrCreate(UUID playerId) {
		return sessions.computeIfAbsent(playerId, SendSession::new);
	}

	public void clear(UUID playerId) {
		if (playerId != null) {
			sessions.remove(playerId);
		}
	}

	public void returnLetter(Player player, boolean cancelledMessage) {
		if (player == null) {
			return;
		}
		SendSession session = sessions.remove(player.getUniqueId());
		if (session == null || session.getLetter() == null) {
			return;
		}
		ItemGive.giveOrDrop(player, session.getLetter());
		player.sendMessage(plugin.config().msgLetterReturned());
		if (cancelledMessage) {
			player.sendMessage(plugin.config().msgLetterCancelled());
		}
		player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
	}

	public ItemStack takeLetter(UUID playerId) {
		SendSession session = sessions.get(playerId);
		if (session == null) {
			return null;
		}
		ItemStack letter = session.getLetter();
		session.setLetter(null);
		return letter;
	}
}

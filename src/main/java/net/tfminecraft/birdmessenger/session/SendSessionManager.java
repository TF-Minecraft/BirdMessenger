package net.tfminecraft.birdmessenger.session;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.logging.Level;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.gui.CharacterPickerGui;
import net.tfminecraft.birdmessenger.gui.LetterGui;
import net.tfminecraft.birdmessenger.mail.MailStore.PendingLetter;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;

public final class SendSessionManager {
	private final BirdMessenger plugin;
	private final Map<UUID, SendSession> sessions = new ConcurrentHashMap<>();
	private final List<Refund> refunds = new ArrayList<>();
	private boolean closing;
	private boolean retrying;

	private record Refund(SendSession session, ItemStack letter) {}

	public SendSessionManager(BirdMessenger plugin) { this.plugin = plugin; }

	public SendSession get(UUID playerId) {
		return playerId == null ? null : sessions.get(playerId);
	}

	public SendSession getOrCreate(Player player) {
		if (closing || player == null) return null;
		SendSession existing = get(player.getUniqueId());
		if (isCurrent(player, existing)) return existing;
		returnLetter(player, existing, false);
		if (closing || get(player.getUniqueId()) != null) return null;
		var token = Characters.currentSession(player);
		if (token.isEmpty()) return null;
		SendSession session = new SendSession(player, token.get());
		if (!session.isCurrent(player)) return null;
		sessions.put(player.getUniqueId(), session);
		return session;
	}

	public boolean isCurrent(Player player, SendSession session) {
		return !closing && player != null && session != null && get(session.getPlayerId()) == session && session.isCurrent(player);
	}

	public boolean remove(SendSession session) {
		return session != null && sessions.remove(session.getPlayerId(), session);
	}

	public void returnLetter(Player player, boolean cancelledMessage) {
		if (player != null) returnLetter(player, get(player.getUniqueId()), cancelledMessage);
	}

	public void returnLetter(Player player, SendSession session, boolean cancelledMessage) {
		finish(player, session, cancelledMessage, false);
	}

	public void end(Player player, CharacterSession token) {
		SendSession session = get(player.getUniqueId());
		if (session != null && session.characterSession().equals(token) && session.player() == player) {
			finish(player, session, false, true);
		}
	}

	public void close() {
		closing = true;
		retryReturns();
		for (SendSession session : new ArrayList<>(sessions.values())) {
			returnLetter(session.player(), session, false);
		}
	}

	private void finish(Player player, SendSession session, boolean cancelledMessage, boolean ending) {
		if (player == null || session == null || get(session.getPlayerId()) != session) return;
		var returned = new ArrayList<ItemStack>();
		Inventory inventory = session.letterInventory();
		ItemStack staged = inventory == null ? null : inventory.getItem(LetterGui.LETTER_SLOT);
		if (staged != null && !staged.getType().isAir()) {
			returned.add(staged);
			inventory.setItem(LetterGui.LETTER_SLOT, null);
		}
		if (session.getLetter() != null) {
			returned.add(session.getLetter());
			session.setLetter(null);
		}
		remove(session);
		for (ItemStack letter : returned) {
			try {
				if (canReturn(player, session, ending)) ItemGive.giveOrDrop(player, letter);
				else queueReturn(session, letter);
			} catch (RuntimeException failure) {
				retainReturn(session, letter, failure);
			}
		}
		Inventory top = player.getOpenInventory().getTopInventory();
		if ((top.getHolder() instanceof LetterGui gui && gui.session() == session)
				|| (top.getHolder() instanceof CharacterPickerGui picker && picker.session() == session)) {
			player.closeInventory();
		}
		if (!returned.isEmpty() && canReturn(player, session, ending)) {
			player.sendMessage(plugin.config().msgLetterReturned());
			if (cancelledMessage) player.sendMessage(plugin.config().msgLetterCancelled());
			player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
		}
	}

	/** Keep a rejected handoff separate from any replacement sending session. */
	public void retainReturn(SendSession session, ItemStack letter, RuntimeException failure) {
		refunds.add(new Refund(session, letter));
		plugin.getLogger().log(Level.SEVERE, "Retained unsent letter for "
				+ session.characterSession().ref() + "; retry after mail storage recovery", failure);
	}

	public void retryReturns() {
		if (retrying) return;
		retrying = true;
		try {
			for (Refund refund : new ArrayList<>(refunds)) {
				try {
					queueReturn(refund.session(), refund.letter());
					refunds.remove(refund);
				} catch (RuntimeException failure) {
					plugin.getLogger().log(Level.SEVERE, "Unsent letter still awaits mail storage recovery", failure);
				}
			}
		} finally {
			retrying = false;
		}
	}

	private static boolean canReturn(Player player, SendSession session, boolean ending) {
		return session.isCurrent(player) || (ending && session.isConnected(player)
				&& Characters.currentSession(player).isEmpty());
	}

	private void queueReturn(SendSession session, ItemStack letter) {
		var ref = session.characterSession().ref();
		plugin.mail().store().addPending(ref.characterId().toString(),
				new PendingLetter(ref.ownerId(), null, "", letter));
	}
}

package net.tfminecraft.birdmessenger.session;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.logging.Level;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.GameRule;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

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
	private final Map<UUID, PlayerDeathEvent> deaths = new ConcurrentHashMap<>();
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
		finish(player, session, cancelledMessage, false, false);
	}

	/** Returns the letter of a player who is disconnecting. */
	public void leave(Player player) {
		finish(player, get(player.getUniqueId()), false, false, true);
		deaths.remove(player.getUniqueId());
	}

	public void end(Player player, CharacterSession token, boolean leaving) {
		SendSession session = get(player.getUniqueId());
		if (session != null && session.characterSession().equals(token) && session.player() == player) {
			finish(player, session, false, true, leaving);
		}
	}

	/** Remembers the death in progress; its keep-inventory choice decides where returned letters go. */
	public void dying(PlayerDeathEvent event) {
		deaths.put(event.getEntity().getUniqueId(), event);
	}

	public void close() {
		closing = true;
		retryReturns();
		for (SendSession session : new ArrayList<>(sessions.values())) {
			finish(session.player(), session, false, false, true);
		}
	}

	private void finish(Player player, SendSession session, boolean cancelledMessage, boolean ending,
			boolean leaving) {
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
				if (canReturn(player, session, ending)) giveBack(player, session, letter, leaving);
				else queueReturn(session, letter);
			} catch (RuntimeException failure) {
				retainReturn(session, letter, failure);
			}
		}
		Inventory top = player.getOpenInventory().getTopInventory();
		InventoryHolder holder = top.getHolder(false);
		if ((holder instanceof LetterGui gui && gui.session() == session)
				|| (holder instanceof CharacterPickerGui picker && picker.session() == session)) {
			player.closeInventory();
		}
		if (!returned.isEmpty() && canReturn(player, session, ending)) {
			player.sendMessage(plugin.config().msgLetterReturned());
			if (cancelledMessage) player.sendMessage(plugin.config().msgLetterCancelled());
			player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
		}
	}

	/**
	 * Hands back a letter whose return or send failed while its character is still being played.
	 * Otherwise the letter is held in memory until mail storage accepts it.
	 */
	public void retainReturn(SendSession session, ItemStack letter, RuntimeException failure) {
		Player player = session.player();
		if (session.isCurrent(player)) {
			try {
				giveBack(player, session, letter, false);
				plugin.getLogger().log(Level.WARNING, "Handed " + describe(letter) + " back to "
						+ player.getName() + " after a mail failure", failure);
				return;
			} catch (RuntimeException again) {
				failure.addSuppressed(again);
			}
		}
		refunds.add(new Refund(session, letter));
		plugin.getLogger().log(Level.SEVERE, "Holding unsent letter " + describe(session, letter)
				+ " in memory; it is lost if the plugin stops before mail storage accepts it", failure);
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
					plugin.getLogger().log(Level.SEVERE, "Mail storage still refuses unsent letter "
							+ describe(refund.session(), refund.letter()), failure);
				}
			}
		} finally {
			retrying = false;
		}
	}

	private void giveBack(Player player, SendSession session, ItemStack letter, boolean leaving) {
		// Paper empties a dead player's inventory after the death event and after closing their menu.
		boolean inventoryLost = player.isDead() && !keepsInventory(player);
		if (leaving) {
			// Keep the letter with the outgoing character rather than dropping it where they stood.
			var leftovers = inventoryLost ? List.of(letter) : player.getInventory().addItem(letter).values();
			for (ItemStack leftover : leftovers) queueReturn(session, leftover);
		} else if (inventoryLost) {
			player.getWorld().dropItemNaturally(player.getLocation(), letter);
		} else {
			ItemGive.giveOrDrop(player, letter);
		}
	}

	private boolean keepsInventory(Player player) {
		PlayerDeathEvent death = deaths.get(player.getUniqueId());
		if (death != null) return death.getKeepInventory();
		return Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRule.KEEP_INVENTORY));
	}

	private static String describe(SendSession session, ItemStack letter) {
		var ref = session.characterSession().ref();
		return describe(letter) + " of character " + ref.characterId() + " (owner " + ref.ownerId() + ")";
	}

	private static String describe(ItemStack letter) {
		ItemMeta meta = letter.getItemMeta();
		String name = meta != null && meta.hasDisplayName()
				? " \"" + PlainTextComponentSerializer.plainText().serialize(meta.displayName()) + "\"" : "";
		return letter.getAmount() + "x " + letter.getType() + name;
	}

	private static boolean canReturn(Player player, SendSession session, boolean ending) {
		return session.isCurrent(player) || (ending && session.isConnected(player)
				&& Characters.currentSession(player).isEmpty());
	}

	private void queueReturn(SendSession session, ItemStack letter) {
		var ref = session.characterSession().ref();
		plugin.mail().store().addPending(ref.characterId().toString(),
				PendingLetter.returned(ref.ownerId(), letter));
	}
}

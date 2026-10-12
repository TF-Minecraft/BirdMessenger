package net.tfminecraft.birdmessenger.mail;

import java.util.ArrayList;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;

public final class MailService {

	private final BirdMessenger plugin;
	private final MailStore store;
	private final MailDelivery delivery;

	public MailService(BirdMessenger plugin, MailStore store) {
		this.plugin = plugin;
		this.store = store;
		this.delivery = new MailDelivery(plugin.config());
	}

	public MailStore store() {
		return store;
	}

	public MailDelivery delivery() {
		return delivery;
	}

	/**
	 * @return true if the letter was accepted for delivery; false if send was refused
	 */
	public boolean trySend(Player sender, CharacterSession origin, ItemStack letter, SelectedTarget target) {
		if (origin == null || letter == null || target == null) {
			return false;
		}
		if (!isCurrentConnection(sender, origin)) {
			refundLetter(sender, origin, letter);
			return false;
		}
		if (sender.getUniqueId().equals(target.getOwnerUuid())) {
			refundLetter(sender, origin, letter);
			notifyAfterRefund(() -> sender.sendMessage(plugin.config().msgCannotSendToSelf()));
			return false;
		}
		boolean listed = RPCharacters.listMailTargets().stream().anyMatch(recipient ->
				target.getOwnerUuid().equals(recipient.getOwnerUuid())
						&& target.getCharacterId().equals(recipient.getCharacterId()));
		if (!listed) {
			refundLetter(sender, origin, letter);
			notifyAfterRefund(() -> sender.sendMessage(plugin.config().msgRecipientUnavailable()));
			return false;
		}
		Integer flightSeconds = FlightTime.computeFlightSecondsAtSend(plugin.config(), sender, target);
		if (flightSeconds == null) {
			refundLetter(sender, origin, letter);
			notifyAfterRefund(() -> sender.sendMessage(plugin.config().msgDifferentWorld()));
			return false;
		}
		long deliveryTime = System.currentTimeMillis() + (flightSeconds * 1000L);
		UUID mailId = UUID.randomUUID();
		StoredMail mail = new StoredMail(
				mailId,
				sender.getUniqueId(),
				target.getOwnerUuid(),
				target.getCharacterId(),
				target.getDisplayTab(),
				letter.clone(),
				deliveryTime);
		if (!isCurrentConnection(sender, origin)) {
			refundLetter(sender, origin, letter);
			return false;
		}
		store.putInFlight(mail);
		// The store owns the letter now; later failures must not trigger a caller refund.
		try {
			schedule(mail);
			sender.sendMessage(plugin.config().msgBirdLeft(flightSeconds));
			sender.playSound(sender.getLocation(), Sound.ENTITY_PARROT_AMBIENT, 1f, 1.2f);
		} catch (RuntimeException ex) {
			plugin.getLogger().log(Level.SEVERE,
					"Could not finish scheduling or feedback for accepted letter " + mail.getId(), ex);
		}
		return true;
	}

	private void notifyAfterRefund(Runnable notification) {
		try {
			notification.run();
		} catch (RuntimeException ex) {
			plugin.getLogger().log(Level.SEVERE, "Could not notify sender after returning their letter", ex);
		}
	}

	private void refundLetter(Player sender, CharacterSession origin, ItemStack letter) {
		if (isCurrentConnection(sender, origin)) {
			ItemGive.giveOrDrop(sender, letter);
			return;
		}
		store.addPending(origin.ref().characterId().toString(),
				new MailStore.PendingLetter(origin.ref().ownerId(), null, "", letter));
	}

	private static boolean isCurrentConnection(Player player, CharacterSession session) {
		return player != null && session != null && player.isOnline()
				&& player.getUniqueId().equals(session.ref().ownerId())
				&& Bukkit.getPlayer(session.ref().ownerId()) == player
				&& Characters.isCurrent(session);
	}

	public void resume() {
		for (StoredMail mail : new ArrayList<>(store.inFlight().values())) {
			schedule(mail);
		}
		if (!store.inFlight().isEmpty()) {
			plugin.getLogger().info("Resumed " + store.inFlight().size() + " in-flight letter(s).");
		}
	}

	public void flushPendingForOnlinePlayers() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player == null) {
				continue;
			}
			Characters.currentSession(player).ifPresent(session -> tryDeliverPending(player, session));
		}
	}

	public void tryDeliverPending(Player owner, CharacterSession session) {
		while (isCurrentConnection(owner, session)) {
			MailStore.PendingLetter pending = store.pollPending(session.ref());
			if (pending == null) {
				return;
			}
			delivery.deliver(owner, pending.item, pending.senderUuid, pending.addresseeDisplayTab);
		}
	}

	private void schedule(StoredMail mail) {
		long remaining = mail.getDeliveryTime() - System.currentTimeMillis();
		if (remaining <= 0) {
			complete(mail);
			return;
		}
		long ticks = Math.max(1L, remaining / 50L);
		Bukkit.getScheduler().runTaskLater(plugin, () -> complete(mail), ticks);
	}

	private void complete(StoredMail mail) {
		if (!store.inFlight().containsKey(mail.getId())) {
			return;
		}
		Player owner = Bukkit.getPlayer(mail.getOwnerUuid());
		if (owner != null && owner.isOnline() && MailDelivery.isActiveCharacter(owner, mail.getCharacterId())) {
			store.removeInFlight(mail.getId());
			delivery.deliver(owner, mail);
			DiscordNotifyService.notifyFlightComplete(plugin, mail);
			return;
		}
		store.addPending(mail);
		store.removeInFlight(mail.getId());
		if (plugin.config().discordEnabled() && Bukkit.getPluginManager().isPluginEnabled("TFMCWeb")) {
			DiscordNotifyService.notifyFlightComplete(
					plugin, mail, queued -> notifySenderPending(mail, owner, queued));
		} else {
			notifySenderPending(mail, owner, null);
		}
	}

	/**
	 * @param discordQueued {@code true} if enqueued, {@code false} if attempted but failed,
	 *                      {@code null} if Discord notify was not attempted
	 */
	private void notifySenderPending(StoredMail mail, Player owner, Boolean discordQueued) {
		Player sender = Bukkit.getPlayer(mail.getSenderUuid());
		if (sender == null || !sender.isOnline()) {
			return;
		}
		boolean ownerOnline = owner != null && owner.isOnline();
		if (ownerOnline && !MailDelivery.isActiveCharacter(owner, mail.getCharacterId())) {
			sender.sendMessage(plugin.config().msgWaitingForCharacter());
		} else if (!ownerOnline) {
			sender.sendMessage(plugin.config().msgLetterPendingOffline());
		}
		if (discordQueued == null) {
			return;
		}
		if (discordQueued) {
			sender.sendMessage(plugin.config().msgDiscordDmSent());
		} else {
			sender.sendMessage(plugin.config().msgDiscordDmFailed());
		}
	}
}

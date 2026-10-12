package net.tfminecraft.birdmessenger.mail;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.session.SendSession;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.tlibs.character.Characters;

public final class MailDelivery {

	private final BirdConfig config;

	public MailDelivery(BirdConfig config) {
		this.config = config;
	}

	public static boolean isActiveCharacter(Player owner, String characterId) {
		if (owner == null || characterId == null) {
			return false;
		}
		return Characters.currentSession(owner)
				.filter(session -> characterId.equalsIgnoreCase(session.ref().characterId().toString()))
				.filter(session -> SendSession.isCurrent(owner, session))
				.isPresent();
	}

	public void deliver(Player owner, ItemStack letter, UUID senderUuid, String addresseeDisplayTab) {
		if (owner == null || letter == null) {
			return;
		}
		ItemGive.giveOrDrop(owner, letter.clone());
		String display = addresseeDisplayTab != null && !addresseeDisplayTab.isBlank()
				? addresseeDisplayTab
				: owner.getName();
		owner.sendMessage(config.msgDeliveredRecipient(display));
		owner.playSound(owner.getLocation(), Sound.ENTITY_PARROT_FLY, 1f, 1f);
		if (senderUuid != null) {
			Player sender = Bukkit.getPlayer(senderUuid);
			if (sender != null && sender.isOnline()) {
				sender.sendMessage(config.msgDeliveredSender());
				sender.playSound(sender.getLocation(), Sound.ENTITY_PARROT_FLY, 1f, 1f);
			}
		}
	}

	/** Hands back an unsent letter that waited for its writer's character to be played again. */
	public void returnUnsent(Player owner, ItemStack letter) {
		if (owner == null || letter == null) {
			return;
		}
		ItemGive.giveOrDrop(owner, letter.clone());
		owner.sendMessage(config.msgUnsentReturned());
		owner.playSound(owner.getLocation(), Sound.ENTITY_PARROT_FLY, 1f, 1f);
	}

	public void deliver(Player owner, StoredMail mail) {
		if (mail == null) {
			return;
		}
		deliver(owner, mail.getItem(), mail.getSenderUuid(), mail.getAddresseeDisplayTab());
	}
}

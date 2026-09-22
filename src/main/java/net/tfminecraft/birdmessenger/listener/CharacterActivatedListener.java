package net.tfminecraft.birdmessenger.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import net.tfminecraft.rpcharacters.lifecycle.CharacterActivatedEvent;
import net.tfminecraft.birdmessenger.BirdMessenger;

public final class CharacterActivatedListener implements Listener {

	private final BirdMessenger plugin;

	public CharacterActivatedListener(BirdMessenger plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onCharacterActivated(CharacterActivatedEvent event) {
		if (event.getOwner() == null || event.getCharacter() == null) {
			return;
		}
		plugin.mail().tryDeliverPending(event.getOwner(), event.getCharacter().getId());
	}
}

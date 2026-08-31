package net.tfminecraft.birdmessenger.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import net.tfminecraft.birdmessenger.BirdMessenger;

public final class PlayerSessionListener implements Listener {

	private final BirdMessenger plugin;

	public PlayerSessionListener(BirdMessenger plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		plugin.sessions().returnLetter(event.getPlayer(), false);
	}
}

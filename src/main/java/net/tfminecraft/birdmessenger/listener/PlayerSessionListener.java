package net.tfminecraft.birdmessenger.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import net.tfminecraft.tlibs.character.CharacterSessionStartEvent;
import net.tfminecraft.tlibs.character.CharacterSessionEndEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import net.tfminecraft.birdmessenger.BirdMessenger;

public final class PlayerSessionListener implements Listener {

	private final BirdMessenger plugin;

	public PlayerSessionListener(BirdMessenger plugin) {
		this.plugin = plugin;
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onEnd(CharacterSessionEndEvent event) {
		var reason = event.getReason();
		plugin.sessions().end(event.getPlayer(), event.getSession(),
				reason == CharacterSessionEndEvent.Reason.QUIT || reason == CharacterSessionEndEvent.Reason.SHUTDOWN);
	}

	@EventHandler(priority = EventPriority.LOWEST)
	public void onDeath(PlayerDeathEvent event) {
		plugin.sessions().dying(event);
	}

	@EventHandler
	public void onStart(CharacterSessionStartEvent event) {
		plugin.sessions().retryReturns();
		plugin.mail().tryDeliverPending(event.getPlayer(), event.getSession());
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		plugin.sessions().leave(event.getPlayer());
	}
}

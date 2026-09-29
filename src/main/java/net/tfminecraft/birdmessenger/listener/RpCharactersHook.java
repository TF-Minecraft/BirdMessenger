package net.tfminecraft.birdmessenger.listener;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

import net.tfminecraft.birdmessenger.BirdMessenger;

/**
 * Registers the RPCharacters listener whether RPCharacters enables before or after us.
 * With a load-order cycle, the legacy loader ignores our softdepend and enables it later.
 */
public final class RpCharactersHook implements Listener {

	private final BirdMessenger plugin;
	private boolean registered;

	public RpCharactersHook(BirdMessenger plugin) {
		this.plugin = plugin;
	}

	public void registerIfEnabled() {
		if (registered || !Bukkit.getPluginManager().isPluginEnabled("RPCharacters")) {
			return;
		}
		Bukkit.getPluginManager().registerEvents(new CharacterActivatedListener(plugin), plugin);
		registered = true;
	}

	@EventHandler
	public void onPluginEnable(PluginEnableEvent event) {
		if ("RPCharacters".equals(event.getPlugin().getName())) {
			registerIfEnabled();
		}
	}
}

package net.tfminecraft.birdmessenger.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import net.tfminecraft.birdmessenger.BirdMessenger;

public final class BirdMessengerCommand implements CommandExecutor, TabCompleter {

	private final BirdMessenger plugin;

	public BirdMessengerCommand(BirdMessenger plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
			sender.sendMessage("§eUsage: /birdmessenger reload");
			return true;
		}
		if (!sender.hasPermission("birdmessenger.reload")) {
			sender.sendMessage("§cYou do not have permission to reload BirdMessenger.");
			return true;
		}
		plugin.config().reload();
		sender.sendMessage("§a[BirdMessenger] Reloaded config.yml.");
		if (plugin.config().discordEnabled()) {
			if (Bukkit.getPluginManager().isPluginEnabled("TFMCWeb")) {
				sender.sendMessage("§7Discord notify: §aenabled§7 (TFMCWeb loaded).");
			} else {
				sender.sendMessage("§7Discord notify: §cenabled in config but TFMCWeb is not loaded.");
			}
		} else {
			sender.sendMessage("§7Discord notify: §edisabled§7 (set discord.enabled: true to enable).");
		}
		return true;
	}

	@Override
	public List<String> onTabComplete(
		CommandSender sender,
		Command command,
		String alias,
		String[] args
	) {
		if (args.length == 1 && sender.hasPermission("birdmessenger.reload")) {
			String prefix = args[0].toLowerCase();
			List<String> out = new ArrayList<>();
			if ("reload".startsWith(prefix)) {
				out.add("reload");
			}
			return out;
		}
		return Collections.emptyList();
	}
}

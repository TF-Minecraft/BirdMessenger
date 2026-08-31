package net.tfminecraft.birdmessenger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.RPCharacters.RPCharacters;
import net.tfminecraft.birdmessenger.command.BirdMessengerCommand;
import net.tfminecraft.birdmessenger.gui.CharacterPickerGui;
import net.tfminecraft.birdmessenger.listener.CharacterActivatedListener;
import net.tfminecraft.birdmessenger.listener.CoopListener;
import net.tfminecraft.birdmessenger.listener.GuiListener;
import net.tfminecraft.birdmessenger.listener.PlayerSessionListener;
import net.tfminecraft.birdmessenger.mail.MailService;
import net.tfminecraft.birdmessenger.mail.MailStore;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.session.SendSessionManager;

import java.util.List;

public final class BirdMessenger extends JavaPlugin {

	private BirdConfig config;
	private SendSessionManager sessions;
	private MailService mail;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		config = new BirdConfig(this);
		sessions = new SendSessionManager(this);
		MailStore store = new MailStore(this);
		store.load();
		mail = new MailService(this, store);
		Bukkit.getPluginManager().registerEvents(new CoopListener(this), this);
		Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerSessionListener(this), this);
		if (Bukkit.getPluginManager().isPluginEnabled("RPCharacters")) {
			Bukkit.getPluginManager().registerEvents(new CharacterActivatedListener(this), this);
		}
		mail.resume();
		mail.flushPendingForOnlinePlayers();
		BirdMessengerCommand command = new BirdMessengerCommand(this);
		var birdmessenger = getCommand("birdmessenger");
		if (birdmessenger != null) {
			birdmessenger.setExecutor(command);
			birdmessenger.setTabCompleter(command);
		} else {
			getLogger().warning("Command birdmessenger missing from plugin.yml");
		}
		if (config.discordEnabled()) {
			if (Bukkit.getPluginManager().isPluginEnabled("TFMCWeb")) {
				getLogger().info("BirdMessenger enabled (Discord notify on).");
			} else {
				getLogger().warning(
						"BirdMessenger discord.enabled is true but TFMCWeb is not loaded; "
								+ "offline Discord notify will not work.");
			}
		} else {
			getLogger().info(
					"BirdMessenger enabled (Discord notify off - set discord.enabled: true to enable).");
		}
	}

	@Override
	public void onDisable() {
		if (mail != null) {
			mail.store().saveAll();
		}
		getLogger().info("BirdMessenger disabled.");
	}

	public BirdConfig config() {
		return config;
	}

	public SendSessionManager sessions() {
		return sessions;
	}

	public MailService mail() {
		return mail;
	}

	public void openPicker(Player player) {
		if (player == null || !player.isOnline()) {
			return;
		}
		if (!Bukkit.getPluginManager().isPluginEnabled("RPCharacters")) {
			sessions.returnLetter(player, false);
			player.sendMessage(config.msgRpcMissing());
			return;
		}
		RPCharacters.refreshMailTargetTexturesAsync(() -> openPickerAfterTextures(player));
	}

	private void openPickerAfterTextures(Player player) {
		if (player == null || !player.isOnline()) {
			return;
		}
		List<SelectedTarget> targets = CharacterPickerGui.loadTargets();
		if (targets.isEmpty()) {
			sessions.returnLetter(player, false);
			player.sendMessage(config.msgNoTargets());
			return;
		}
		targets.removeIf(t -> player.getUniqueId().equals(t.getOwnerUuid()));
		if (targets.isEmpty()) {
			sessions.returnLetter(player, false);
			player.sendMessage(config.msgCannotSendToSelf());
			return;
		}
		player.sendMessage(config.msgPickerSelect());
		player.openInventory(new CharacterPickerGui(this, targets).getInventory());
	}
}

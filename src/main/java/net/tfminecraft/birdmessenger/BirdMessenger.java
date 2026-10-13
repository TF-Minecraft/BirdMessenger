package net.tfminecraft.birdmessenger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.birdmessenger.command.BirdMessengerCommand;
import net.tfminecraft.birdmessenger.gui.CharacterPickerGui;
import net.tfminecraft.birdmessenger.estate.EstateProbe;
import net.tfminecraft.birdmessenger.estate.LetterEstate;
import net.tfminecraft.birdmessenger.listener.CoopListener;
import net.tfminecraft.birdmessenger.listener.CoopFurnitureListener;
import net.tfminecraft.birdmessenger.listener.GuiListener;
import net.tfminecraft.birdmessenger.listener.PlayerSessionListener;
import net.tfminecraft.birdmessenger.gui.LetterGui;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.birdmessenger.mail.MailService;
import net.tfminecraft.birdmessenger.mail.MailStore;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.session.SendSession;
import net.tfminecraft.birdmessenger.session.SendSessionManager;
import net.tfminecraft.birdmessenger.letters.LetterFeature;

import java.util.List;
import java.util.Objects;

public final class BirdMessenger extends JavaPlugin {

	private BirdConfig config;
	private SendSessionManager sessions;
	private MailService mail;
	private LetterFeature letters;

	@Override
	public void onEnable() {
		try {
			// Probe API linkage; registration can arrive later and is checked per action.
			Characters.available();
		} catch (LinkageError error) {
			getLogger().severe("BirdMessenger requires TLibs 2.3.0 or newer; disabling.");
			Bukkit.getPluginManager().disablePlugin(this);
			return;
		}
		saveDefaultConfig();
		config = new BirdConfig(this);
		letters = new LetterFeature(this);
		letters.reload();
		sessions = new SendSessionManager(this);
		MailStore store = new MailStore(this);
		store.load();
		mail = new MailService(this, store);
		if (EstateProbe.available(EstateProbe.CLASSES)) {
			LetterEstate.start(this, store);
		} else {
			getLogger().warning("TLibs has no estate API (2.6.0 or later); letters for dead characters keep waiting.");
		}
		Bukkit.getPluginManager().registerEvents(new CoopListener(this), this);
		if (Bukkit.getPluginManager().getPlugin("ItemsAdder") != null) {
			Bukkit.getPluginManager().registerEvents(new CoopFurnitureListener(this), this);
		}
		Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerSessionListener(this), this);
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
		if (letters != null) {
			letters.close();
		}
		if (sessions != null) {
			sessions.close();
		}
		if (mail != null) {
			mail.store().saveAll();
		}
		getLogger().info("BirdMessenger disabled.");
	}

	public BirdConfig config() {
		return config;
	}

	/** Reload the letter signing and opening configuration. */
	public boolean reloadLettersConfig() {
		return letters != null && letters.reload();
	}

	public List<String> letterItemPaths() {
		return letters == null ? List.of() : letters.itemPaths();
	}

	public SendSessionManager sessions() {
		return sessions;
	}

	public MailService mail() {
		return mail;
	}

	public void openLetterGui(Player player) {
		var token = Characters.currentSession(player);
		if (token.isEmpty()) {
			player.sendMessage(config.msgRpcMissing());
			return;
		}
		sessions.returnLetter(player, false);
		if (!Characters.isCurrent(token.get()) || sessions.get(player.getUniqueId()) != null) return;
		SendSession session = sessions.getOrCreate(player);
		if (session == null) {
			player.sendMessage(config.msgRpcMissing());
			return;
		}
		player.openInventory(new LetterGui(this, session).getInventory());
	}

	public void openPicker(Player player, SendSession session) {
		if (!sessions.isCurrent(player, session) || session.getLetter() == null || session.isConfirmed()) {
			return;
		}
		if (player.getOpenInventory().getTopInventory().getHolder() instanceof CharacterPickerGui) {
			return;
		}
		if (!Bukkit.getPluginManager().isPluginEnabled("RPCharacters")) {
			sessions.returnLetter(player, session, false);
			player.sendMessage(config.msgRpcMissing());
			return;
		}
		List<SelectedTarget> targets = CharacterPickerGui.loadTargets();
		if (targets.isEmpty()) {
			sessions.returnLetter(player, session, false);
			player.sendMessage(config.msgNoTargets());
			return;
		}
		targets.removeIf(t -> player.getUniqueId().equals(t.getOwnerUuid()));
		if (targets.isEmpty()) {
			sessions.returnLetter(player, session, false);
			player.sendMessage(config.msgCannotSendToSelf());
			return;
		}
		CharacterPickerGui picker = new CharacterPickerGui(this, session, targets);
		if (!sessions.isCurrent(player, session)) return;
		player.sendMessage(config.msgPickerSelect());
		player.openInventory(picker.getInventory());
		// Skin lookups can take minutes, so fill in missing heads after the picker is open.
		RPCharacters.refreshMailTargetTexturesAsync(() -> refreshPickerHeads(player, picker));
	}

	private void refreshPickerHeads(Player player, CharacterPickerGui picker) {
		if (!sessions.isCurrent(player, picker.session())
				|| player.getOpenInventory().getTopInventory().getHolder() != picker) {
			return;
		}
		SendSession session = picker.session();
		List<SelectedTarget> targets = CharacterPickerGui.loadTargets();
		targets.removeIf(t -> player.getUniqueId().equals(t.getOwnerUuid()));
		picker.setTargets(targets);
		SelectedTarget selected = session.getSelected();
		if (selected != null) {
			// Drop a selection whose recipient is no longer listed.
			session.setSelected(targets.stream()
					.filter(t -> selected.getCharacterId().equals(t.getCharacterId())
							&& Objects.equals(selected.getOwnerUuid(), t.getOwnerUuid()))
					.findFirst()
					.orElse(null));
		}
		session.setPickerPage(Math.min(session.getPickerPage(), picker.maxPage()));
		CharacterPickerGui.applyPage(session, picker);
	}
}

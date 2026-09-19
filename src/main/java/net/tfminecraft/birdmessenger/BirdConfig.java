package net.tfminecraft.birdmessenger;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

import net.tfminecraft.birdmessenger.mail.FlightTime;

public final class BirdConfig {

	private final BirdMessenger plugin;

	public BirdConfig(BirdMessenger plugin) {
		this.plugin = plugin;
	}

	public void reload() {
		plugin.reloadConfig();
	}

	private FileConfiguration yaml() {
		return plugin.getConfig();
	}

	public String coopPath() {
		return yaml().getString("coop", "iaf(tfmc:bird_coop)");
	}

	public String letterPath() {
		return yaml().getString("letter", "ia.iasurvival:letter");
	}

	public List<String> letterPaths() {
		if (yaml().isList("letters")) {
			return yaml().getStringList("letters");
		}
		String legacy = letterPath();
		if ("ia.iasurvival:letter".equalsIgnoreCase(legacy)) {
			return List.of(legacy, "ia.iasurvival:letter_written_letter",
					"ia.iasurvival:letter_open_letter");
		}
		return List.of(legacy);
	}

	public double secondsPerBlock() {
		return yaml().getDouble("seconds-per-block", 0.25);
	}

	public int minSeconds() {
		return yaml().getInt("min-seconds", 30);
	}

	public int maxSeconds() {
		return yaml().getInt("max-seconds", 600);
	}

	public long deliveryDelayTicks() {
		return Math.max(1L, yaml().getLong("delivery-delay-ticks", 22000L));
	}

	public String letterTitle() {
		return color(yaml().getString("gui.letter-title", "Bird Messenger"));
	}

	public String pickerTitle() {
		return color(yaml().getString("gui.picker-title", "Send letter"));
	}

	public String paneName() {
		return color(yaml().getString("gui.pane-name", " "));
	}

	public boolean discordEnabled() {
		return yaml().getBoolean("discord.enabled", true);
	}

	public boolean discordIncludeSender() {
		return yaml().getBoolean("discord.include-sender", false);
	}

	public boolean discordIncludeContents() {
		return yaml().getBoolean("discord.include-contents", false);
	}

	public String msgOnlyLetters() {
		return message("only-letters", "&cOnly letters can be sent by bird.");
	}

	public String msgOneLetter() {
		return message("one-letter", "&cYou can only place one letter at a time.");
	}

	public String msgRpcMissing() {
		return message("rpc-missing", "&cCharacter mail is unavailable right now.");
	}

	public String msgNoTargets() {
		return message("no-targets", "&cNo characters are available to send to.");
	}

	public String msgBirdLeft(int flightSeconds) {
		String template = message("bird-left",
				"&aThe bird has left with your letter. &7Estimated arrival in {time}.");
		String time = FlightTime.formatDuration(Math.max(1, flightSeconds));
		return template.replace("{time}", time);
	}

	public String msgLetterReturned() {
		return message("letter-returned", "&eYour letter has been returned to you.");
	}

	public String msgLetterCancelled() {
		return message("letter-cancelled", "&cLetter cancelled.");
	}

	public String msgPickerSelect() {
		return message("picker-select", "&7Click a character, then confirm.");
	}

	public String msgPickerConfirmNeeded() {
		return message("picker-confirm-needed", "&cSelect a character first.");
	}

	public String msgDifferentWorld() {
		return message("different-world", "&cThe bird cannot reach them from here.");
	}

	public String msgDeliveredRecipient(String characterDisplay) {
		String template = message("delivered-recipient",
				"&bA bird lands at your feet with a letter for {character}.");
		String name = characterDisplay != null ? characterDisplay : "";
		return template.replace("{character}", name);
	}

	public String msgDeliveredSender() {
		return message("delivered-sender", "&bYour letter was delivered!");
	}

	public String msgWaitingForCharacter() {
		return message("waiting-for-character",
				"&7Your letter is waiting until they play that character.");
	}

	public String msgLetterPendingOffline() {
		return message("letter-pending-offline",
				"&7Your letter is waiting until they log in.");
	}

	public String msgCannotSendToSelf() {
		return message("cannot-send-to-self",
				"&cYou cannot send a letter to your own characters.");
	}

	public String msgDiscordDmSent() {
		return message("discord-dm-sent",
				"&7They have been sent a Discord DM about your letter.");
	}

	public String msgDiscordDmFailed() {
		return message("discord-dm-failed",
				"&7Could not notify them on Discord (they may not have a linked account).");
	}

	private String message(String key, String fallback) {
		return color(yaml().getString("messages." + key, fallback));
	}

	public static String color(String input) {
		if (input == null) {
			return "";
		}
		return ChatColor.translateAlternateColorCodes('&', input);
	}
}

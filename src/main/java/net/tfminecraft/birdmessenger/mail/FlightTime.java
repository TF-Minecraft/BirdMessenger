package net.tfminecraft.birdmessenger.mail;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import net.tfminecraft.RPCharacters.RPCharacters;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.session.SelectedTarget;

public final class FlightTime {

	private FlightTime() {}

	public static Integer flightSeconds(BirdConfig config, Location from, Location to) {
		if (config == null || from == null || to == null || from.getWorld() == null || to.getWorld() == null) {
			return null;
		}
		if (!from.getWorld().equals(to.getWorld())) {
			return null;
		}
		double distance = from.distance(to);
		int seconds = (int) Math.round(distance * config.secondsPerBlock());
		return Math.max(config.minSeconds(), Math.min(config.maxSeconds(), seconds));
	}

	public static Long deliveryTimeMillis(BirdConfig config, Location from, Location to) {
		Integer seconds = flightSeconds(config, from, to);
		if (seconds == null) {
			return null;
		}
		return System.currentTimeMillis() + (seconds * 1000L);
	}

	public static Integer computeFlightSecondsAtSend(BirdConfig config, Player sender, SelectedTarget target) {
		if (config == null || sender == null || target == null) {
			return null;
		}
		if (!Bukkit.getPluginManager().isPluginEnabled("RPCharacters")) {
			return null;
		}
		Location from = sender.getLocation();
		if (from.getWorld() == null) {
			return null;
		}
		Location to = RPCharacters.getMailTargetLocation(target.getOwnerUuid(), target.getCharacterId());
		if (to == null) {
			// No stored last location (e.g. pre-batch-1 characters): same spot => 0 blocks away.
			to = from;
		}
		return flightSeconds(config, from, to);
	}

	public static String formatDuration(int seconds) {
		if (seconds < 60) {
			return seconds + (seconds == 1 ? " second" : " seconds");
		}
		int minutes = seconds / 60;
		int remainder = seconds % 60;
		if (remainder == 0) {
			return minutes + (minutes == 1 ? " minute" : " minutes");
		}
		String minutePart = minutes + (minutes == 1 ? " minute" : " minutes");
		String secondPart = remainder + (remainder == 1 ? " second" : " seconds");
		return minutePart + " " + secondPart;
	}

	public static Long computeAtSend(BirdConfig config, Player sender, SelectedTarget target) {
		Integer seconds = computeFlightSecondsAtSend(config, sender, target);
		if (seconds == null) {
			return null;
		}
		return System.currentTimeMillis() + (seconds * 1000L);
	}
}

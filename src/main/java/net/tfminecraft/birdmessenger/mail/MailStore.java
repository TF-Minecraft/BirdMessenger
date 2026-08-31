package net.tfminecraft.birdmessenger.mail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.birdmessenger.BirdMessenger;

public final class MailStore {

	private final BirdMessenger plugin;
	private final File inFlightFile;
	private final File pendingFile;
	private final Map<UUID, StoredMail> inFlight = new ConcurrentHashMap<>();
	private final Map<String, List<PendingLetter>> pending = new ConcurrentHashMap<>();

	public MailStore(BirdMessenger plugin) {
		this.plugin = plugin;
		File data = plugin.getDataFolder();
		if (!data.exists()) {
			data.mkdirs();
		}
		this.inFlightFile = new File(data, "in_flight_mail.yml");
		this.pendingFile = new File(data, "pending_mail.yml");
	}

	public Map<UUID, StoredMail> inFlight() {
		return inFlight;
	}

	public boolean hasPending(String characterId) {
		if (characterId == null) {
			return false;
		}
		List<PendingLetter> letters = pending.get(characterId);
		return letters != null && !letters.isEmpty();
	}

	public List<PendingLetter> takePending(String characterId) {
		if (characterId == null) {
			return Collections.emptyList();
		}
		List<PendingLetter> letters = pending.remove(characterId);
		savePending();
		if (letters == null || letters.isEmpty()) {
			return Collections.emptyList();
		}
		return new ArrayList<>(letters);
	}

	public void putInFlight(StoredMail mail) {
		if (mail == null) {
			return;
		}
		inFlight.put(mail.getId(), mail);
		saveInFlight();
	}

	public StoredMail removeInFlight(UUID mailId) {
		if (mailId == null) {
			return null;
		}
		StoredMail removed = inFlight.remove(mailId);
		saveInFlight();
		return removed;
	}

	public void addPending(StoredMail mail) {
		if (mail == null || mail.getCharacterId() == null) {
			return;
		}
		pending.computeIfAbsent(mail.getCharacterId(), k -> new ArrayList<>())
				.add(new PendingLetter(
						mail.getOwnerUuid(),
						mail.getSenderUuid(),
						mail.getAddresseeDisplayTab(),
						mail.getItem().clone()));
		savePending();
	}

	public void load() {
		loadInFlight();
		loadPending();
	}

	public void saveAll() {
		saveInFlight();
		savePending();
	}

	private void saveInFlight() {
		FileConfiguration config = new YamlConfiguration();
		for (StoredMail mail : inFlight.values()) {
			String path = "mail." + mail.getId();
			config.set(path + ".sender", mail.getSenderUuid().toString());
			config.set(path + ".owner", mail.getOwnerUuid().toString());
			config.set(path + ".character-id", mail.getCharacterId());
			config.set(path + ".addressee-display", mail.getAddresseeDisplayTab());
			config.set(path + ".delivery-time", mail.getDeliveryTime());
			config.set(path + ".item", mail.getItem().serialize());
		}
		try {
			config.save(inFlightFile);
		} catch (IOException ex) {
			plugin.getLogger().log(Level.SEVERE, "Could not save in-flight mail", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private void loadInFlight() {
		inFlight.clear();
		if (!inFlightFile.exists()) {
			return;
		}
		FileConfiguration config = YamlConfiguration.loadConfiguration(inFlightFile);
		ConfigurationSection section = config.getConfigurationSection("mail");
		if (section == null) {
			return;
		}
		for (String key : section.getKeys(false)) {
			try {
				UUID id = UUID.fromString(key);
				String sender = config.getString("mail." + key + ".sender");
				String owner = config.getString("mail." + key + ".owner");
				String characterId = config.getString("mail." + key + ".character-id");
				if (sender == null || owner == null || characterId == null || characterId.isBlank()) {
					plugin.getLogger().warning("Skipping in-flight mail " + key + ": missing character id");
					continue;
				}
				String addresseeDisplay = config.getString("mail." + key + ".addressee-display", "");
				long deliveryTime = config.getLong("mail." + key + ".delivery-time");
				Object rawItem = config.get("mail." + key + ".item");
				if (rawItem == null) {
					rawItem = config.get("mail." + key + ".book");
				}
				if (!(rawItem instanceof Map)) {
					continue;
				}
				ItemStack item = ItemStack.deserialize((Map<String, Object>) rawItem);
				inFlight.put(id, new StoredMail(
						id,
						UUID.fromString(sender),
						UUID.fromString(owner),
						characterId,
						addresseeDisplay,
						item,
						deliveryTime));
			} catch (Exception ex) {
				plugin.getLogger().warning("Could not load in-flight mail " + key);
			}
		}
	}

	private void savePending() {
		FileConfiguration config = new YamlConfiguration();
		for (Map.Entry<String, List<PendingLetter>> entry : pending.entrySet()) {
			String path = "characters." + entry.getKey();
			List<PendingLetter> letters = entry.getValue();
			if (letters == null || letters.isEmpty()) {
				continue;
			}
			config.set(path + ".owner", letters.get(0).ownerUuid.toString());
			List<Map<String, Object>> letterMaps = new ArrayList<>();
			for (PendingLetter letter : letters) {
				Map<String, Object> map = new java.util.LinkedHashMap<>();
				if (letter.senderUuid != null) {
					map.put("sender", letter.senderUuid.toString());
				}
				if (letter.addresseeDisplayTab != null && !letter.addresseeDisplayTab.isBlank()) {
					map.put("addressee-display", letter.addresseeDisplayTab);
				}
				map.put("item", letter.item.serialize());
				letterMaps.add(map);
			}
			config.set(path + ".letters", letterMaps);
		}
		try {
			config.save(pendingFile);
		} catch (IOException ex) {
			plugin.getLogger().log(Level.SEVERE, "Could not save pending mail", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private void loadPending() {
		pending.clear();
		if (!pendingFile.exists()) {
			return;
		}
		FileConfiguration config = YamlConfiguration.loadConfiguration(pendingFile);
		ConfigurationSection section = config.getConfigurationSection("characters");
		if (section == null) {
			return;
		}
		for (String characterId : section.getKeys(false)) {
			try {
				String owner = config.getString("characters." + characterId + ".owner");
				if (owner == null) {
					continue;
				}
				UUID ownerUuid = UUID.fromString(owner);
				List<PendingLetter> letters = new ArrayList<>();
				List<Map<String, Object>> letterMaps =
						(List<Map<String, Object>>) config.get("characters." + characterId + ".letters");
				if (letterMaps != null) {
					for (Map<String, Object> map : letterMaps) {
						letters.add(parsePendingLetter(ownerUuid, map));
					}
				} else {
					List<Map<String, Object>> serialized =
							(List<Map<String, Object>>) config.get("characters." + characterId + ".items");
					if (serialized != null) {
						for (Map<String, Object> map : serialized) {
							letters.add(new PendingLetter(ownerUuid, null, "", ItemStack.deserialize(map)));
						}
					}
				}
				if (!letters.isEmpty()) {
					pending.put(characterId, letters);
				}
			} catch (Exception ex) {
				plugin.getLogger().warning("Could not load pending mail for " + characterId);
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static PendingLetter parsePendingLetter(UUID ownerUuid, Map<String, Object> map) {
		UUID senderUuid = null;
		Object senderRaw = map.get("sender");
		if (senderRaw != null) {
			try {
				senderUuid = UUID.fromString(senderRaw.toString());
			} catch (IllegalArgumentException ignored) {
				// legacy or corrupt
			}
		}
		String display = map.containsKey("addressee-display")
				? String.valueOf(map.get("addressee-display"))
				: "";
		Object itemRaw = map.get("item");
		ItemStack item = itemRaw instanceof Map
				? ItemStack.deserialize((Map<String, Object>) itemRaw)
				: new ItemStack(org.bukkit.Material.AIR);
		return new PendingLetter(ownerUuid, senderUuid, display, item);
	}

	public static final class PendingLetter {
		public final UUID ownerUuid;
		public final UUID senderUuid;
		public final String addresseeDisplayTab;
		public final ItemStack item;

		public PendingLetter(UUID ownerUuid, UUID senderUuid, String addresseeDisplayTab, ItemStack item) {
			this.ownerUuid = ownerUuid;
			this.senderUuid = senderUuid;
			this.addresseeDisplayTab = addresseeDisplayTab != null ? addresseeDisplayTab : "";
			this.item = item;
		}
	}
}

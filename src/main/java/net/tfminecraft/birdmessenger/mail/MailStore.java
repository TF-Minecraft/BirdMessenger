package net.tfminecraft.birdmessenger.mail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
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
	private final Set<File> recoveryRequired = new HashSet<>();

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
		PendingLetter letter = new PendingLetter(
						mail.getOwnerUuid(),
						mail.getSenderUuid(),
						mail.getAddresseeDisplayTab(),
						mail.getItem().clone());
		// Publish only complete entries: this private map always contains nonempty lists.
		pending.computeIfAbsent(mail.getCharacterId(), k -> new ArrayList<>()).add(letter);
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
		requireRecovered(inFlightFile);
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

	private void loadInFlight() {
		if (!inFlightFile.exists()) {
			recoveryRequired.remove(inFlightFile);
			inFlight.clear();
			return;
		}
		FileConfiguration config = loadFile(inFlightFile);
		if (config == null) {
			return;
		}
		recoveryRequired.remove(inFlightFile);
		inFlight.clear();
		ConfigurationSection section = config.getConfigurationSection("mail");
		if (section == null) {
			if (config.contains("mail")) preserveDamagedFile(inFlightFile);
			return;
		}
		boolean damaged = false;
		for (String key : section.getKeys(false)) {
			try {
				UUID id = UUID.fromString(key);
				String sender = config.getString("mail." + key + ".sender");
				String owner = config.getString("mail." + key + ".owner");
				String characterId = config.getString("mail." + key + ".character-id");
				if (sender == null || owner == null || characterId == null || characterId.isBlank()) {
					damaged = true;
					plugin.getLogger().warning("Skipping in-flight mail " + key + ": missing character id");
					continue;
				}
				String addresseeDisplay = config.getString("mail." + key + ".addressee-display", "");
				long deliveryTime = config.getLong("mail." + key + ".delivery-time");
				Object rawItem = config.get("mail." + key + ".item");
				if (rawItem == null) {
					rawItem = config.get("mail." + key + ".book");
				}
				if (!(rawItem instanceof Map) && !(rawItem instanceof ConfigurationSection)) {
					damaged = true;
					continue;
				}
				ItemStack item = deserializeItem(rawItem);
				inFlight.put(id, new StoredMail(
						id,
						UUID.fromString(sender),
						UUID.fromString(owner),
						characterId,
						addresseeDisplay,
						item,
						deliveryTime));
			} catch (Exception ex) {
				damaged = true;
				plugin.getLogger().warning("Could not load in-flight mail " + key);
			}
		}
		if (damaged) preserveDamagedFile(inFlightFile);
	}

	private void savePending() {
		requireRecovered(pendingFile);
		FileConfiguration config = new YamlConfiguration();
		for (Map.Entry<String, List<PendingLetter>> entry : pending.entrySet()) {
			String path = "characters." + entry.getKey();
			List<PendingLetter> letters = entry.getValue();
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
		if (!pendingFile.exists()) {
			recoveryRequired.remove(pendingFile);
			pending.clear();
			return;
		}
		FileConfiguration config = loadFile(pendingFile);
		if (config == null) {
			return;
		}
		recoveryRequired.remove(pendingFile);
		pending.clear();
		ConfigurationSection section = config.getConfigurationSection("characters");
		if (section == null) {
			if (config.contains("characters")) preserveDamagedFile(pendingFile);
			return;
		}
		boolean damaged = false;
		for (String characterId : section.getKeys(false)) {
			try {
				String owner = config.getString("characters." + characterId + ".owner");
				if (owner == null) {
					damaged = true;
					continue;
				}
				UUID ownerUuid = UUID.fromString(owner);
				List<PendingLetter> letters = new ArrayList<>();
				List<?> letterMaps = (List<?>) config.get("characters." + characterId + ".letters");
				if (letterMaps != null) {
					for (Object raw : letterMaps) {
						try {
							letters.add(parsePendingLetter(ownerUuid, (Map<String, Object>) raw));
						} catch (Exception ex) {
							damaged = true;
							plugin.getLogger().warning("Could not load pending letter for " + characterId);
						}
					}
				} else {
					List<?> serialized = (List<?>) config.get("characters." + characterId + ".items");
					if (serialized != null) {
						for (Object raw : serialized) {
							try {
								letters.add(new PendingLetter(ownerUuid, null, "", deserializeItem(raw)));
							} catch (Exception ex) {
								damaged = true;
								plugin.getLogger().warning("Could not load pending letter for " + characterId);
							}
						}
					}
				}
				if (!letters.isEmpty()) {
					pending.put(characterId, letters);
				}
			} catch (Exception ex) {
				damaged = true;
				plugin.getLogger().warning("Could not load pending mail for " + characterId);
			}
		}
		if (damaged) preserveDamagedFile(pendingFile);
	}

	private FileConfiguration loadFile(File file) {
		YamlConfiguration config = new YamlConfiguration();
		// Item component keys may contain literal dots; preserve them while reading YAML.
		config.options().pathSeparator('\0');
		try {
			config.load(file);
		} catch (IOException | InvalidConfigurationException ex) {
			plugin.getLogger().log(Level.SEVERE, "Could not load mail file " + file.getName(), ex);
			preserveDamagedFile(file);
			return null;
		}
		config.options().pathSeparator('.');
		return config;
	}

	private void preserveDamagedFile(File file) {
		// Keep the original bytes, including entries that could not be decoded, before any save.
		recoveryRequired.add(file);
		File backup = new File(file.getParentFile(), file.getName() + ".corrupt-" + UUID.randomUUID());
		try {
			if (!file.isFile()) throw new IOException("Mail path is not a regular file: " + file);
			Files.copy(file.toPath(), backup.toPath());
		} catch (IOException ex) {
			throw new IllegalStateException("Cannot preserve damaged mail file; refusing to overwrite " + file, ex);
		}
		recoveryRequired.remove(file);
		plugin.getLogger().warning("Preserved damaged mail file for recovery: " + backup.getName());
	}

	private void requireRecovered(File file) {
		if (recoveryRequired.contains(file)) {
			throw new IllegalStateException("Mail file still requires recovery; refusing to overwrite " + file);
		}
	}

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
		ItemStack item = deserializeItem(map.get("item"));
		return new PendingLetter(ownerUuid, senderUuid, display, item);
	}

	@SuppressWarnings("unchecked")
	private static ItemStack deserializeItem(Object raw) {
		Object values = serializedValue(raw);
		if (!(values instanceof Map)) {
			throw new IllegalArgumentException("Missing serialized letter item");
		}
		return ItemStack.deserialize((Map<String, Object>) values);
	}

	private static Object serializedValue(Object raw) {
		// YAML mappings outside lists become sections, including nested item components.
		if (raw instanceof ConfigurationSection section) {
			raw = section.getValues(false);
		}
		if (raw instanceof Map<?, ?> map) {
			Map<Object, Object> values = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				values.put(entry.getKey(), serializedValue(entry.getValue()));
			}
			return values;
		}
		if (raw instanceof List<?> list) {
			List<Object> values = new ArrayList<>();
			for (Object entry : list) {
				values.add(serializedValue(entry));
			}
			return values;
		}
		return raw;
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

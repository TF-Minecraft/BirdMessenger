package net.tfminecraft.birdmessenger.mail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.tlibs.character.CharacterRef;

public final class MailStore {

	private final BirdMessenger plugin;
	private final File inFlightFile;
	private final File pendingFile;
	private final File returnedFile;
	private final Map<UUID, StoredMail> inFlight = new ConcurrentHashMap<>();
	private final Map<String, List<PendingLetter>> pending = new ConcurrentHashMap<>();
	// Letters sent back to an account because their writer's character was not recorded.
	private final Map<UUID, List<PendingLetter>> returned = new ConcurrentHashMap<>();
	private final Set<File> recoveryRequired = new HashSet<>();

	public MailStore(BirdMessenger plugin) {
		this.plugin = plugin;
		File data = plugin.getDataFolder();
		if (!data.exists()) {
			data.mkdirs();
		}
		this.inFlightFile = new File(data, "in_flight_mail.yml");
		this.pendingFile = new File(data, "pending_mail.yml");
		this.returnedFile = new File(data, "returned_mail.yml");
	}

	public Map<UUID, StoredMail> inFlight() {
		return inFlight;
	}

	/**
	 * Hands this character's pending letters to {@code deliver} one at a time while {@code current}
	 * holds, then saves once. Each letter leaves the queue only just before it is handed over, and
	 * goes back to the head of its queue if the hand-over throws.
	 */
	public void drainPending(CharacterRef ref, BooleanSupplier current, Consumer<PendingLetter> deliver) {
		if (ref == null) {
			return;
		}
		boolean taken = false;
		try {
			while (current.getAsBoolean()) {
				Taken next = removePending(ref);
				if (next == null) {
					return;
				}
				taken = true;
				try {
					deliver.accept(next.letter());
				} catch (RuntimeException failure) {
					pending.computeIfAbsent(next.key(), ignored -> new ArrayList<>()).addFirst(next.letter());
					throw failure;
				}
			}
		} finally {
			if (taken) {
				savePending();
			}
		}
	}

	private record Taken(String key, PendingLetter letter) {
	}

	private Taken removePending(CharacterRef ref) {
		String characterId = ref.characterId().toString();
		for (Map.Entry<String, List<PendingLetter>> entry : pending.entrySet()) {
			if (!entry.getKey().equalsIgnoreCase(characterId)) {
				continue;
			}
			List<PendingLetter> letters = entry.getValue();
			for (int i = 0; i < letters.size(); i++) {
				if (!ref.ownerId().equals(letters.get(i).ownerUuid)) {
					continue;
				}
				requireRecovered(pendingFile);
				PendingLetter letter = letters.remove(i);
				if (letters.isEmpty()) {
					pending.remove(entry.getKey(), letters);
				}
				return new Taken(entry.getKey(), letter);
			}
		}
		return null;
	}

	public void putInFlight(StoredMail mail) {
		if (mail == null) {
			return;
		}
		requireRecovered(inFlightFile);
		inFlight.put(mail.getId(), mail);
		saveInFlight();
	}

	public StoredMail removeInFlight(UUID mailId) {
		if (mailId == null) {
			return null;
		}
		requireRecovered(inFlightFile);
		StoredMail removed = inFlight.remove(mailId);
		saveInFlight();
		return removed;
	}

	public void addPending(StoredMail mail) {
		if (mail == null || mail.getCharacterId() == null) {
			return;
		}
		addPending(mail.getCharacterId(), new PendingLetter(mail.getId(), mail.getOwnerUuid(), mail.getSenderUuid(),
				mail.getSenderCharacterId(), mail.getAddresseeDisplayTab(), mail.getItem(), false, false));
	}

	public void addPending(String characterId, PendingLetter letter) {
		if (characterId == null || letter == null) {
			return;
		}
		requireRecovered(pendingFile);
		Objects.requireNonNull(letter.ownerUuid, "Pending letter owner is required");
		enqueue(characterId, letter.copy(letter.id, letter.ownerUuid));
		savePending();
	}

	private void enqueue(String characterId, PendingLetter snapshot) {
		// Publish only complete entries: this private map always contains nonempty lists.
		pending.compute(characterId, (key, letters) -> {
			if (letters == null) {
				letters = new ArrayList<>();
			}
			for (PendingLetter existing : letters) {
				if (!snapshot.ownerUuid.equals(existing.ownerUuid)) {
					throw new IllegalStateException("Pending character group belongs to another owner");
				}
			}
			letters.add(snapshot);
			return letters;
		});
	}

	/** Where an estate sends one of the dead character's waiting letters. */
	public enum Destination {
		/** To the will heir, as ordinary mail. */
		HEIR,
		/** Back to the character that wrote it, marked as returned because the recipient died. */
		SENDER_CHARACTER,
		/** Back to the writer's account, for letters that never recorded their character. */
		SENDER_ACCOUNT
	}

	/**
	 * @param characterId the receiving character; unused for {@link Destination#SENDER_ACCOUNT}
	 * @param ownerUuid   the receiving account
	 */
	public record Move(UUID letterId, Destination destination, String characterId, UUID ownerUuid) {
	}

	/** A copy of this character's waiting letters, oldest first. */
	public List<PendingLetter> pendingFor(String characterId) {
		List<PendingLetter> letters = new ArrayList<>();
		for (Map.Entry<String, List<PendingLetter>> entry : pending.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(characterId)) {
				letters.addAll(entry.getValue());
			}
		}
		return letters;
	}

	/**
	 * This character's waiting letters, each with a stored ID. Letters queued before IDs were kept are
	 * given one and saved first, so a move interrupted between files can be recognised.
	 */
	public List<PendingLetter> identifyPending(String characterId) {
		boolean changed = false;
		for (Map.Entry<String, List<PendingLetter>> entry : pending.entrySet()) {
			if (!entry.getKey().equalsIgnoreCase(characterId)) {
				continue;
			}
			List<PendingLetter> letters = entry.getValue();
			for (int i = 0; i < letters.size(); i++) {
				if (letters.get(i).id == null) {
					requireRecovered(pendingFile);
					letters.set(i, letters.get(i).copy(UUID.randomUUID(), letters.get(i).ownerUuid));
					changed = true;
				}
			}
		}
		if (changed) {
			savePending();
		}
		return pendingFor(characterId);
	}

	/**
	 * Moves the named letters out of the dead character's queue. Letters for an account queue are saved
	 * there before the character queue drops them; {@link #load()} discards a copy left behind by a crash
	 * in between. Returns the IDs moved now.
	 */
	public List<UUID> moveEstateLetters(String characterId, List<Move> moves) {
		requireRecovered(pendingFile);
		requireRecovered(returnedFile);
		Map<UUID, Move> byId = new LinkedHashMap<>();
		for (Move move : moves) {
			byId.put(move.letterId(), move);
		}
		List<PendingLetter> taken = new ArrayList<>();
		for (PendingLetter letter : pendingFor(characterId)) {
			if (letter.id != null && byId.containsKey(letter.id)) {
				taken.add(letter);
			}
		}
		if (taken.isEmpty()) {
			return List.of();
		}
		boolean toAccounts = false;
		for (PendingLetter letter : taken) {
			Move move = byId.get(letter.id);
			if (move.destination() == Destination.SENDER_ACCOUNT) {
				returned.computeIfAbsent(move.ownerUuid(), ignored -> new ArrayList<>())
						.add(letter.copy(letter.id, move.ownerUuid()));
				toAccounts = true;
			}
		}
		if (toAccounts) {
			saveReturned();
		}
		List<UUID> moved = new ArrayList<>();
		for (PendingLetter letter : taken) {
			Move move = byId.get(letter.id);
			if (move.destination() == Destination.HEIR) {
				enqueue(move.characterId(), new PendingLetter(letter.id, move.ownerUuid(), letter.senderUuid,
						letter.senderCharacterId, "", letter.item, false, false));
			} else if (move.destination() == Destination.SENDER_CHARACTER) {
				enqueue(move.characterId(), new PendingLetter(letter.id, move.ownerUuid(), letter.senderUuid,
						letter.senderCharacterId, letter.addresseeDisplayTab, letter.item, false, true));
			}
			moved.add(letter.id);
		}
		pending.entrySet().removeIf(entry -> {
			if (!entry.getKey().equalsIgnoreCase(characterId)) {
				return false;
			}
			entry.getValue().removeIf(letter -> letter.id != null && byId.containsKey(letter.id));
			return entry.getValue().isEmpty();
		});
		savePending();
		return moved;
	}

	/**
	 * Hands this account's returned letters to {@code deliver} while {@code current} holds, then saves
	 * once. A letter goes back to the head of the queue if the hand-over throws.
	 */
	public void drainReturned(UUID account, BooleanSupplier current, Consumer<PendingLetter> deliver) {
		if (account == null) {
			return;
		}
		boolean taken = false;
		try {
			while (current.getAsBoolean()) {
				List<PendingLetter> letters = returned.get(account);
				if (letters == null) {
					return;
				}
				requireRecovered(returnedFile);
				PendingLetter letter = letters.removeFirst();
				if (letters.isEmpty()) {
					returned.remove(account, letters);
				}
				taken = true;
				try {
					deliver.accept(letter);
				} catch (RuntimeException failure) {
					returned.computeIfAbsent(account, ignored -> new ArrayList<>()).addFirst(letter);
					throw failure;
				}
			}
		} finally {
			if (taken) {
				saveReturned();
			}
		}
	}

	public void load() {
		loadInFlight();
		loadPending();
		loadReturned();
		dropMovedCopies();
	}

	public void saveAll() {
		saveInFlight();
		savePending();
		saveReturned();
	}

	// A crash after an estate saved a letter to an account queue, but before the dead character's queue
	// dropped it, leaves the same letter in both. The account copy is the moved one.
	private void dropMovedCopies() {
		Set<UUID> ids = new HashSet<>();
		returned.values().forEach(letters -> letters.forEach(letter -> ids.add(letter.id)));
		ids.remove(null);
		int before = pending.values().stream().mapToInt(List::size).sum();
		pending.values().forEach(letters -> letters.removeIf(letter -> ids.contains(letter.id)));
		pending.values().removeIf(List::isEmpty);
		if (pending.values().stream().mapToInt(List::size).sum() != before) {
			plugin.getLogger().warning("Dropped pending letters already returned to their senders' accounts.");
			savePending();
		}
	}

	private void saveInFlight() {
		requireRecovered(inFlightFile);
		FileConfiguration config = new YamlConfiguration();
		for (StoredMail mail : inFlight.values()) {
			String path = "mail." + mail.getId();
			config.set(path + ".sender", mail.getSenderUuid().toString());
			config.set(path + ".owner", mail.getOwnerUuid().toString());
			config.set(path + ".character-id", mail.getCharacterId());
			config.set(path + ".sender-character", mail.getSenderCharacterId());
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
						deliveryTime,
						config.getString("mail." + key + ".sender-character")));
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
			config.set(path + ".letters", letterMaps(letters));
		}
		try {
			config.save(pendingFile);
		} catch (IOException ex) {
			plugin.getLogger().log(Level.SEVERE, "Could not save pending mail", ex);
		}
	}

	private static List<Map<String, Object>> letterMaps(List<PendingLetter> letters) {
		List<Map<String, Object>> letterMaps = new ArrayList<>();
		for (PendingLetter letter : letters) {
			Map<String, Object> map = new java.util.LinkedHashMap<>();
			if (letter.id != null) {
				map.put("id", letter.id.toString());
			}
			if (letter.senderUuid != null) {
				map.put("sender", letter.senderUuid.toString());
			}
			if (letter.senderCharacterId != null) {
				map.put("sender-character", letter.senderCharacterId);
			}
			if (letter.addresseeDisplayTab != null && !letter.addresseeDisplayTab.isBlank()) {
				map.put("addressee-display", letter.addresseeDisplayTab);
			}
			if (letter.returned) {
				map.put("returned", true);
			}
			if (letter.recipientDied) {
				map.put("recipient-died", true);
			}
			map.put("item", letter.item.serialize());
			letterMaps.add(map);
		}
		return letterMaps;
	}

	private void saveReturned() {
		requireRecovered(returnedFile);
		FileConfiguration config = new YamlConfiguration();
		for (Map.Entry<UUID, List<PendingLetter>> entry : returned.entrySet()) {
			config.set("accounts." + entry.getKey() + ".letters", letterMaps(entry.getValue()));
		}
		try {
			config.save(returnedFile);
		} catch (IOException ex) {
			plugin.getLogger().log(Level.SEVERE, "Could not save returned mail", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private void loadReturned() {
		returned.clear();
		if (!returnedFile.exists()) {
			recoveryRequired.remove(returnedFile);
			return;
		}
		FileConfiguration config = loadFile(returnedFile);
		if (config == null) {
			return;
		}
		recoveryRequired.remove(returnedFile);
		ConfigurationSection section = config.getConfigurationSection("accounts");
		if (section == null) {
			if (config.contains("accounts")) preserveDamagedFile(returnedFile);
			return;
		}
		boolean damaged = false;
		for (String key : section.getKeys(false)) {
			try {
				UUID account = UUID.fromString(key);
				List<PendingLetter> letters = new ArrayList<>();
				for (Object raw : (List<?>) config.get("accounts." + key + ".letters")) {
					try {
						letters.add(parsePendingLetter(account, (Map<String, Object>) raw));
					} catch (Exception ex) {
						damaged = true;
						plugin.getLogger().warning("Could not load a returned letter for " + key);
					}
				}
				if (!letters.isEmpty()) {
					returned.put(account, letters);
				}
			} catch (Exception ex) {
				damaged = true;
				plugin.getLogger().warning("Could not load returned mail for " + key);
			}
		}
		if (damaged) preserveDamagedFile(returnedFile);
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
		UUID id = map.get("id") == null ? null : UUID.fromString(map.get("id").toString());
		Object senderCharacter = map.get("sender-character");
		return new PendingLetter(id, ownerUuid, senderUuid, senderCharacter == null ? null : senderCharacter.toString(),
				display, item, Boolean.TRUE.equals(map.get("returned")), Boolean.TRUE.equals(map.get("recipient-died")));
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
		/** Stored from this release on; letters queued earlier get one when an estate moves them. */
		public final UUID id;
		public final UUID ownerUuid;
		public final UUID senderUuid;
		/** The character that wrote the letter, or null for letters sent before it was recorded. */
		public final String senderCharacterId;
		public final String addresseeDisplayTab;
		public final ItemStack item;
		/** An unsent letter on its way back to the character that wrote it. */
		public final boolean returned;
		/** A sent letter coming back to its writer because the recipient died. */
		public final boolean recipientDied;

		public PendingLetter(UUID ownerUuid, UUID senderUuid, String addresseeDisplayTab, ItemStack item) {
			this(null, ownerUuid, senderUuid, null, addresseeDisplayTab, item, false, false);
		}

		PendingLetter(UUID id, UUID ownerUuid, UUID senderUuid, String senderCharacterId,
				String addresseeDisplayTab, ItemStack item, boolean returned, boolean recipientDied) {
			this.id = id;
			this.ownerUuid = ownerUuid;
			this.senderUuid = senderUuid;
			this.senderCharacterId = senderCharacterId;
			this.addresseeDisplayTab = addresseeDisplayTab != null ? addresseeDisplayTab : "";
			this.item = item;
			this.returned = returned;
			this.recipientDied = recipientDied;
		}

		public static PendingLetter returned(UUID ownerUuid, ItemStack item) {
			return new PendingLetter(null, ownerUuid, null, null, "", item, true, false);
		}

		private PendingLetter copy(UUID id, UUID ownerUuid) {
			return new PendingLetter(id, ownerUuid, senderUuid, senderCharacterId, addresseeDisplayTab, item.clone(),
					returned, recipientDied);
		}
	}
}

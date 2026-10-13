package net.tfminecraft.birdmessenger.estate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import net.tfminecraft.birdmessenger.mail.MailStore;
import net.tfminecraft.birdmessenger.util.DurableFiles;
import net.tfminecraft.birdmessenger.mail.MailStore.Destination;
import net.tfminecraft.birdmessenger.mail.MailStore.Move;
import net.tfminecraft.birdmessenger.mail.MailStore.PendingLetter;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.Characters;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.Estates;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;

/**
 * The {@code birdmessenger} estate participant: letters waiting for a dead character go to the will
 * heir, or back to whoever wrote them. Loaded only after {@link EstateProbe} finds the TLibs 2.6.0
 * estate API. Letters still in flight are left alone; they join the queue on landing and the hourly
 * reconcile moves them.
 */
public final class LetterEstate implements EstateParticipant {
	static final String ID = "birdmessenger";
	static final long HOUR_TICKS = 20L * 60 * 60;

	interface Reporter {
		boolean report(UUID deathId, String participantId, long revision, Outcome outcome);
	}

	private final MailStore store;
	private final Path journals;
	private final Logger logger;
	Predicate<EstateDecision> guard = Estates::guardHolds;
	Supplier<List<EstateDecision>> decided = Estates::decided;
	Reporter reporter = Estates::report;
	Function<UUID, Optional<CharacterInfo>> characters = Characters::info;
	// The last outcome reported per death, so the hourly pass reports only changes.
	private final Map<UUID, Map.Entry<Long, Outcome>> reported = new HashMap<>();

	LetterEstate(MailStore store, Path journals, Logger logger) {
		this.store = store;
		this.journals = journals;
		this.logger = logger;
	}

	/** Registers the participant once mail is loaded; reconciles on the first tick, then hourly. */
	public static void start(Plugin plugin, MailStore store) {
		LetterEstate estate = new LetterEstate(store, plugin.getDataFolder().toPath().resolve("estates"),
				plugin.getLogger());
		plugin.getServer().getServicesManager().register(EstateParticipant.class, estate, plugin, ServicePriority.Normal);
		plugin.getServer().getScheduler().runTaskTimer(plugin, estate::reconcile, 1L, HOUR_TICKS);
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public Outcome settle(EstateDecision decision) {
		if (!guard.test(decision)) {
			return Outcome.retry("The estate guard does not hold");
		}
		String testator = decision.testator().characterId().toString();
		Optional<CharacterRef> heir = decision.letters().character();
		try {
			// Letters with no move stay with the testator for staff; describe() lists them.
			List<Move> moves = new ArrayList<>();
			for (PendingLetter letter : store.identifyPending(testator)) {
				Move move = heir.isPresent()
						? new Move(letter.id, Destination.HEIR, heir.get().characterId().toString(), heir.get().ownerId())
						: toSender(letter);
				if (move != null) {
					moves.add(move);
				}
			}
			Set<String> planned = readJournal(decision.deathId());
			boolean planning = false;
			for (Move move : moves) {
				planning |= planned.add(move.letterId().toString());
			}
			if (planning) {
				writeJournal(decision.deathId(), planned);
			}
			store.moveEstateLetters(testator, moves);
			Set<String> waiting = new HashSet<>();
			store.pendingFor(testator).forEach(letter -> waiting.add(String.valueOf(letter.id)));
			List<String> moved = planned.stream().filter(id -> !waiting.contains(id)).toList();
			return Outcome.done(Map.of("letters", moved.size()), moved);
		} catch (IOException | RuntimeException ex) {
			logger.warning("Could not move the letters of estate " + decision.deathId() + ": " + ex);
			return Outcome.retry("Could not move a letter: " + ex.getMessage());
		}
	}

	/**
	 * Where a letter goes when it returns to its writer, or null to hold it for staff: the writer's
	 * character is dead or unknown, or the letter is the dead character's own unsent one.
	 */
	private Move toSender(PendingLetter letter) {
		if (letter.returned) {
			return null;
		}
		if (letter.senderCharacterId == null) {
			return letter.senderUuid == null ? null
					: new Move(letter.id, Destination.SENDER_ACCOUNT, null, letter.senderUuid);
		}
		Optional<CharacterInfo> writer;
		try {
			writer = characters.apply(UUID.fromString(letter.senderCharacterId));
		} catch (IllegalArgumentException ex) {
			return null;
		}
		return writer.filter(CharacterInfo::alive)
				.map(info -> new Move(letter.id, Destination.SENDER_CHARACTER, letter.senderCharacterId,
						info.ref().ownerId()))
				.orElse(null);
	}

	@Override
	public Holdings describe(UUID characterId) {
		List<String> labels = store.pendingFor(characterId.toString()).stream().map(this::label).toList();
		return labels.isEmpty() ? Holdings.NONE : new Holdings(Map.of("letters", labels.size()), labels);
	}

	// Names the writer where it can, never an account or character UUID.
	private String label(PendingLetter letter) {
		String name = letter.id == null ? "Letter" : "Letter " + letter.id.toString().substring(0, 8);
		if (letter.returned) {
			return name + ", this character's own unsent letter";
		}
		if (letter.senderCharacterId == null) {
			return letter.senderUuid == null ? name + " with no sender" : name + " from before writers were recorded";
		}
		Optional<CharacterInfo> writer;
		try {
			writer = characters.apply(UUID.fromString(letter.senderCharacterId));
		} catch (IllegalArgumentException ex) {
			writer = Optional.empty();
		}
		return writer.map(info -> name + " from " + info.name() + (info.alive() ? "" : " (dead)"))
				.orElse(name + " from an unknown writer");
	}

	/** Settles every decided estate whose guard holds and reports outcomes that changed. */
	void reconcile() {
		for (EstateDecision decision : decided.get()) {
			// One estate failing must not stop the others until the next hour.
			try {
				if (!guard.test(decision)) {
					continue;
				}
				Outcome outcome = settle(decision);
				Map.Entry<Long, Outcome> last = Map.entry(decision.revision(), outcome);
				if (!last.equals(reported.get(decision.deathId()))
						&& reporter.report(decision.deathId(), ID, decision.revision(), outcome)) {
					reported.put(decision.deathId(), last);
				}
			} catch (RuntimeException ex) {
				logger.warning("Could not reconcile the letters of estate " + decision.deathId() + ": " + ex);
			}
		}
	}

	// Every letter ID this estate has planned to move, written before the queue changes.
	private Set<String> readJournal(UUID deathId) throws IOException {
		Path file = journals.resolve(deathId + ".yml");
		Set<String> ids = new LinkedHashSet<>();
		if (Files.exists(file)) {
			YamlConfiguration journal = new YamlConfiguration();
			try {
				journal.loadFromString(Files.readString(file));
			} catch (InvalidConfigurationException ex) {
				throw new IOException("Unreadable estate journal " + file, ex);
			}
			ids.addAll(journal.getStringList("letters"));
		}
		return ids;
	}

	private void writeJournal(UUID deathId, Set<String> ids) throws IOException {
		YamlConfiguration journal = new YamlConfiguration();
		journal.set("death", deathId.toString());
		journal.set("letters", new ArrayList<>(ids));
		DurableFiles.write(journals.resolve(deathId + ".yml"), journal.saveToString());
	}
}

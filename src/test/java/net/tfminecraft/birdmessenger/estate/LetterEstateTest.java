package net.tfminecraft.birdmessenger.estate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.UnsafeValues;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.mail.MailStore;
import net.tfminecraft.birdmessenger.mail.MailStore.PendingLetter;
import net.tfminecraft.birdmessenger.mail.StoredMail;
import net.tfminecraft.tlibs.character.CharacterInfo;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterStatus;
import net.tfminecraft.tlibs.character.estate.EstateDecision;
import net.tfminecraft.tlibs.character.estate.EstateParticipant;
import net.tfminecraft.tlibs.character.estate.Holdings;
import net.tfminecraft.tlibs.character.estate.Outcome;
import net.tfminecraft.tlibs.character.estate.Recipient;

class LetterEstateTest {
  private static final CharacterRef DEAD = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
  private static final CharacterRef HEIR = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
  private static final CharacterRef WRITER = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
  private static final CharacterRef DEAD_WRITER = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
  private static final UUID DEATH = UUID.randomUUID();
  @TempDir Path root;
  private MockedStatic<Bukkit> bukkit;
  private MailStore store;
  private LetterEstate estate;
  private boolean guard = true;
  private final Map<UUID, CharacterStatus> statuses = new HashMap<>();

  @BeforeEach
  void setup() {
    BirdMessenger plugin = mock(BirdMessenger.class);
    when(plugin.getDataFolder()).thenReturn(root.toFile());
    when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
    UnsafeValues unsafe = mock(UnsafeValues.class);
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
    when(unsafe.deserializeStack(anyMap())).thenAnswer(call -> item(call.getArgument(0)));
    store = new MailStore(plugin);
    estate = new LetterEstate(store, root.resolve("estates"), Logger.getAnonymousLogger());
    estate.guard = decision -> guard;
    statuses.put(WRITER.characterId(), CharacterStatus.ALIVE);
    statuses.put(DEAD_WRITER.characterId(), CharacterStatus.DEAD);
    estate.characters = id -> Optional.ofNullable(statuses.get(id)).map(status -> new CharacterInfo(
        id.equals(WRITER.characterId()) ? WRITER : DEAD_WRITER, "Writer", status, null, Set.of(), 0, null));
  }

  @AfterEach
  void close() {
    bukkit.close();
  }

  @Test
  void willHeirReceivesEveryWaitingLetterAsOrdinaryMail() {
    UUID sent = send(WRITER);
    store.addPending(dead(), PendingLetter.returned(DEAD.ownerId(), letter("Own unsent")));

    Outcome.Done done = assertInstanceOf(Outcome.Done.class, estate.settle(decision(Recipient.character(HEIR), 0)));

    assertEquals(Map.of("letters", 2, "held", 0), done.counts());
    assertTrue(done.movedIds().contains(sent.toString()));
    assertEquals(List.of(), store.pendingFor(dead()));
    List<PendingLetter> heirs = store.pendingFor(HEIR.characterId().toString());
    assertEquals(2, heirs.size());
    assertTrue(heirs.stream().allMatch(letter -> HEIR.ownerId().equals(letter.ownerUuid) && !letter.returned));
  }

  @Test
  void withoutAWillLettersGoBackToTheirWritersOrWaitForStaff() {
    UUID toWriter = send(WRITER);
    store.addPending(dead(), new PendingLetter(DEAD.ownerId(), WRITER.ownerId(), "Dead", letter("Old")));
    UUID toDeadWriter = send(DEAD_WRITER);
    store.addPending(new StoredMail(UUID.randomUUID(), WRITER.ownerId(), DEAD.ownerId(), dead(), "Dead",
        letter("Unknown writer"), 1L, UUID.randomUUID().toString()));
    store.addPending(new StoredMail(UUID.randomUUID(), WRITER.ownerId(), DEAD.ownerId(), dead(), "Dead",
        letter("Garbled writer"), 1L, "not-a-uuid"));
    store.addPending(dead(), PendingLetter.returned(DEAD.ownerId(), letter("Own unsent")));
    store.addPending(dead(), new PendingLetter(DEAD.ownerId(), null, "Dead", letter("No sender")));

    Outcome.Done done = assertInstanceOf(Outcome.Done.class, estate.settle(decision(Recipient.SENDER, 0)));

    assertEquals(Map.of("letters", 2, "held", 5), done.counts());
    assertTrue(done.movedIds().contains(toWriter.toString()));
    PendingLetter back = store.pendingFor(WRITER.characterId().toString()).getFirst();
    assertEquals(toWriter, back.id);
    assertTrue(back.recipientDied);
    assertEquals(WRITER.ownerId(), back.ownerUuid);
    List<PendingLetter> account = new ArrayList<>();
    store.drainReturned(WRITER.ownerId(), () -> true, account::add);
    assertEquals(1, account.size());
    assertEquals(5, store.pendingFor(dead()).size());
    assertTrue(store.pendingFor(dead()).stream().anyMatch(letter -> toDeadWriter.equals(letter.id)));
  }

  @Test
  void settlingAgainMovesNothingTwiceAndReportsEveryLetterMovedSoFar() {
    UUID first = send(WRITER);
    Outcome before = estate.settle(decision(Recipient.SENDER, 0));
    assertEquals(before, estate.settle(decision(Recipient.SENDER, 1)));
    assertEquals(1, store.pendingFor(WRITER.characterId().toString()).size());

    // A letter that was in flight lands later and the next settle returns it too.
    UUID landed = send(WRITER);
    Outcome.Done done = assertInstanceOf(Outcome.Done.class, estate.settle(decision(Recipient.SENDER, 1)));
    assertEquals(Set.of(first.toString(), landed.toString()), Set.copyOf(done.movedIds()));
    assertEquals(2, store.pendingFor(WRITER.characterId().toString()).size());
  }

  @Test
  void heldLetterGoesBackOnceItsWriterLivesAgain() {
    UUID held = send(DEAD_WRITER);
    assertEquals(Map.of("letters", 0, "held", 1),
        assertInstanceOf(Outcome.Done.class, estate.settle(decision(Recipient.SENDER, 0))).counts());

    statuses.put(DEAD_WRITER.characterId(), CharacterStatus.ALIVE);
    Outcome.Done done = assertInstanceOf(Outcome.Done.class, estate.settle(decision(Recipient.SENDER, 0)));

    assertEquals(List.of(held.toString()), done.movedIds());
    assertEquals(DEAD_WRITER.ownerId(), store.pendingFor(DEAD_WRITER.characterId().toString()).getFirst().ownerUuid);
  }

  @Test
  void failingGuardMovesNothing() {
    send(WRITER);
    guard = false;
    assertEquals(Outcome.Kind.RETRY, estate.settle(decision(Recipient.SENDER, 0)).kind());
    assertEquals(1, store.pendingFor(dead()).size());
  }

  @Test
  void journalProblemsRetryWithoutMovingLetters() throws Exception {
    send(WRITER);
    Files.createDirectories(root.resolve("estates"));
    Files.writeString(root.resolve("estates").resolve(DEATH + ".yml"), "letters: [\n");
    assertEquals(Outcome.Kind.RETRY, estate.settle(decision(Recipient.SENDER, 0)).kind());
    Files.delete(root.resolve("estates").resolve(DEATH + ".yml"));
    Files.delete(root.resolve("estates"));
    Files.writeString(root.resolve("estates"), "not a folder");
    assertEquals(Outcome.Kind.RETRY, estate.settle(decision(Recipient.SENDER, 0)).kind());
    assertEquals(1, store.pendingFor(dead()).size());
  }

  @Test
  void describeListsWaitingLettersAndWhoWroteThem() {
    UUID id = send(WRITER);
    store.addPending(dead(), new PendingLetter(DEAD.ownerId(), WRITER.ownerId(), "Dead", letter("Old")));
    store.addPending(dead(), PendingLetter.returned(DEAD.ownerId(), letter("Own")));
    store.addPending(dead(), new PendingLetter(DEAD.ownerId(), null, "Dead", letter("Anonymous")));

    Holdings holdings = estate.describe(DEAD.characterId());

    assertEquals(Map.of("letters", 4), holdings.counts());
    assertEquals(List.of("Letter " + id + " from character " + WRITER.characterId(),
        "Letter from account " + WRITER.ownerId(), "Letter, this character's own unsent letter", "Letter"),
        holdings.labels());
    assertSame(Holdings.NONE, estate.describe(HEIR.characterId()));
  }

  @Test
  void reconcileSettlesDecidedEstatesAndReportsOnlyChanges() {
    send(WRITER);
    EstateDecision other = new EstateDecision(UUID.randomUUID(), HEIR, Optional.empty(), Instant.EPOCH,
        Optional.empty(), Optional.empty(), Optional.empty(), Recipient.FROZEN, Recipient.FROZEN, Recipient.FROZEN,
        Recipient.SENDER, 0);
    estate.guard = decision -> decision != other;
    estate.decided = () -> List.of(other, decision(Recipient.SENDER, 0));
    List<Object[]> reports = new ArrayList<>();
    estate.reporter = (death, participant, revision, outcome) -> reports.add(new Object[] {death, participant, revision, outcome});

    estate.reconcile();
    estate.reconcile();

    assertEquals(1, reports.size());
    assertEquals(DEATH, reports.getFirst()[0]);
    assertEquals("birdmessenger", reports.getFirst()[1]);
    assertEquals(0L, reports.getFirst()[2]);

    estate.decided = () -> List.of(decision(Recipient.SENDER, 1));
    estate.reconcile();
    assertEquals(2, reports.size());
  }

  @Test
  void reconcileReportsAgainWhenNoServiceTookTheReport() {
    estate.decided = () -> List.of(decision(Recipient.SENDER, 0));
    List<Outcome> reports = new ArrayList<>();
    estate.reporter = (death, participant, revision, outcome) -> reports.add(outcome) && false;

    estate.reconcile();
    estate.reconcile();

    assertEquals(2, reports.size());
  }

  @Test
  void startRegistersTheParticipantAndReconcilesOnTheFirstTickThenHourly() {
    Plugin plugin = mock(Plugin.class);
    Server server = mock(Server.class);
    ServicesManager services = mock(ServicesManager.class);
    BukkitScheduler scheduler = mock(BukkitScheduler.class);
    when(plugin.getServer()).thenReturn(server);
    when(plugin.getDataFolder()).thenReturn(root.toFile());
    when(server.getServicesManager()).thenReturn(services);
    when(server.getScheduler()).thenReturn(scheduler);

    LetterEstate.start(plugin, store);

    var participant = org.mockito.ArgumentCaptor.forClass(EstateParticipant.class);
    verify(services).register(eq(EstateParticipant.class), participant.capture(), eq(plugin), eq(ServicePriority.Normal));
    assertEquals("birdmessenger", participant.getValue().id());
    verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(72_000L));
  }

  @Test
  void probeFindsTheEstateApiAndMissesAbsentClasses() {
    assertTrue(EstateProbe.available(EstateProbe.CLASSES));
    assertFalse(EstateProbe.available("net.tfminecraft.tlibs.character.estate.Missing"));
  }

  private UUID send(CharacterRef writer) {
    UUID id = UUID.randomUUID();
    store.addPending(new StoredMail(id, writer.ownerId(), DEAD.ownerId(), dead(), "Dead", letter("Letter " + id), 1L,
        writer.characterId().toString()));
    return id;
  }

  private static String dead() {
    return DEAD.characterId().toString();
  }

  private EstateDecision decision(Recipient letters, long revision) {
    return new EstateDecision(DEATH, DEAD, Optional.of(Instant.EPOCH), Instant.EPOCH, Optional.empty(),
        Optional.empty(), Optional.empty(), Recipient.FROZEN, Recipient.FROZEN, Recipient.FROZEN, letters, revision);
  }

  private static ItemStack letter(String title) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("schema_version", 1);
    map.put("DataVersion", 4556);
    map.put("id", "minecraft:written_book");
    map.put("title", title);
    return item(map);
  }

  private static ItemStack item(Map<String, Object> serialized) {
    ItemStack item = mock(ItemStack.class);
    when(item.serialize()).thenReturn(serialized);
    when(item.clone()).thenAnswer(invocation -> item(serialized));
    return item;
  }
}

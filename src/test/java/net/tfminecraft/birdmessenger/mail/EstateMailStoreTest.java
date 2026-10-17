package net.tfminecraft.birdmessenger.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.mail.MailStore.Destination;
import net.tfminecraft.birdmessenger.mail.MailStore.Move;
import net.tfminecraft.birdmessenger.mail.MailStore.PendingLetter;
import net.tfminecraft.tlibs.character.CharacterRef;

/** Sending characters, letter IDs and the moves an estate makes in the mail store. */
class EstateMailStoreTest {
  private static final UUID DEAD_ACCOUNT = UUID.randomUUID();
  private static final String DEAD = UUID.randomUUID().toString();
  private static final CharacterRef HEIR = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
  private static final CharacterRef WRITER = new CharacterRef(UUID.randomUUID(), UUID.randomUUID());
  @TempDir Path root;
  private BirdMessenger plugin;
  private Logger logger;
  private MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setup() {
    plugin = mock(BirdMessenger.class);
    logger = mock(Logger.class);
    when(plugin.getDataFolder()).thenReturn(root.toFile());
    when(plugin.getLogger()).thenReturn(logger);
    UnsafeValues unsafe = mock(UnsafeValues.class);
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
    when(unsafe.deserializeStack(anyMap())).thenAnswer(call -> item(call.getArgument(0)));
  }

  @AfterEach
  void close() {
    bukkit.close();
  }

  @Test
  void theSendingCharacterSurvivesFlightAndTheQueueWithTheMailId() throws Exception {
    UUID id = UUID.randomUUID();
    StoredMail mail = new StoredMail(id, WRITER.ownerId(), DEAD_ACCOUNT, DEAD, "Dead", letter("A"), 5L,
        WRITER.characterId().toString());
    MailStore first = new MailStore(plugin);
    first.putInFlight(mail);
    MailStore restored = reload();
    assertEquals(WRITER.characterId().toString(), restored.inFlight().get(id).getSenderCharacterId());

    restored.addPending(restored.inFlight().get(id));
    PendingLetter queued = reload().pendingFor(DEAD).getFirst();
    assertEquals(id, queued.id);
    assertEquals(WRITER.characterId().toString(), queued.senderCharacterId);
    assertEquals(WRITER.ownerId(), queued.senderUuid);
    assertNull(new StoredMail(id, null, DEAD_ACCOUNT, DEAD, "", letter("B"), 1L).getSenderCharacterId());
  }

  @Test
  void lettersQueuedBeforeIdsGetOneStoredBeforeAnyMove() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("Old")));
    assertNull(store.pendingFor(DEAD).getFirst().id);

    UUID id = store.identifyPending(DEAD).getFirst().id;

    assertNotNull(id);
    assertEquals(id, reload().pendingFor(DEAD).getFirst().id);
    assertEquals(List.of(id), store.identifyPending(DEAD).stream().map(letter -> letter.id).toList());
    assertEquals(List.of(), store.identifyPending(UUID.randomUUID().toString()));
  }

  @Test
  void heirLettersBecomeOrdinaryMailForTheHeir() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("To heir")));
    store.addPending(DEAD, PendingLetter.returned(DEAD_ACCOUNT, letter("Own unsent")));
    List<PendingLetter> letters = store.identifyPending(DEAD);

    List<UUID> moved = store.moveEstateLetters(DEAD, letters.stream()
        .map(letter -> new Move(letter.id, Destination.HEIR, HEIR.characterId().toString(), HEIR.ownerId())).toList());

    assertEquals(letters.stream().map(letter -> letter.id).toList(), moved);
    assertEquals(List.of(), store.pendingFor(DEAD));
    List<PendingLetter> heirs = reload().pendingFor(HEIR.characterId().toString());
    assertEquals(2, heirs.size());
    for (PendingLetter letter : heirs) {
      assertEquals(HEIR.ownerId(), letter.ownerUuid);
      assertEquals("", letter.addresseeDisplayTab);
      assertFalse(letter.returned);
      assertFalse(letter.recipientDied);
    }
    assertEquals(WRITER.ownerId(), heirs.getFirst().senderUuid);
  }

  @Test
  void letterGoesBackToTheWritingCharacterMarkedAsReturned() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(new StoredMail(UUID.randomUUID(), WRITER.ownerId(), DEAD_ACCOUNT, DEAD, "Dead",
        letter("Back"), 1L, WRITER.characterId().toString()));
    PendingLetter letter = store.identifyPending(DEAD).getFirst();

    store.moveEstateLetters(DEAD, List.of(new Move(letter.id, Destination.SENDER_CHARACTER,
        WRITER.characterId().toString(), WRITER.ownerId())));

    PendingLetter back = reload().pendingFor(WRITER.characterId().toString()).getFirst();
    assertEquals(letter.id, back.id);
    assertEquals(WRITER.ownerId(), back.ownerUuid);
    assertTrue(back.recipientDied);
    assertEquals("Dead", back.addresseeDisplayTab);
    assertEquals(List.of(), reload().pendingFor(DEAD));
  }

  @Test
  void olderLettersGoToTheWritersAccountQueueAndArriveOnAnyCharacter() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("Old")));
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("Held")));
    List<PendingLetter> letters = store.identifyPending(DEAD);

    store.moveEstateLetters(DEAD, List.of(
        new Move(letters.getFirst().id, Destination.SENDER_ACCOUNT, null, WRITER.ownerId())));

    MailStore restored = reload();
    assertEquals(List.of(letters.get(1).id), restored.pendingFor(DEAD).stream().map(letter -> letter.id).toList());
    List<PendingLetter> handed = new ArrayList<>();
    restored.drainReturned(UUID.randomUUID(), () -> true, handed::add);
    restored.drainReturned(null, () -> true, handed::add);
    restored.drainReturned(WRITER.ownerId(), () -> false, handed::add);
    restored.drainReturned(WRITER.ownerId(), () -> true, letter -> false, handed::add);
    assertEquals(List.of(), handed);
    restored = reload();
    restored.drainReturned(WRITER.ownerId(), () -> true, handed::add);
    assertEquals(1, handed.size());
    assertEquals(letters.getFirst().id, handed.getFirst().id);
    assertEquals(WRITER.ownerId(), handed.getFirst().ownerUuid);
    restored.drainReturned(WRITER.ownerId(), () -> true, handed::add);
    assertEquals(1, reload().pendingFor(DEAD).size());
    reload().drainReturned(WRITER.ownerId(), () -> true, letter -> fail("delivered twice"));
  }

  @Test
  void aFailedHandOverKeepsTheReturnedLetterFirstInLine() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("Old")));
    UUID id = store.identifyPending(DEAD).getFirst().id;
    store.moveEstateLetters(DEAD, List.of(new Move(id, Destination.SENDER_ACCOUNT, null, WRITER.ownerId())));

    assertThrows(IllegalStateException.class, () -> store.drainReturned(WRITER.ownerId(), () -> true, letter -> {
      throw new IllegalStateException("inventory closed");
    }));

    List<PendingLetter> handed = new ArrayList<>();
    reload().drainReturned(WRITER.ownerId(), () -> true, handed::add);
    assertEquals(List.of(id), handed.stream().map(letter -> letter.id).toList());
  }

  @Test
  void crashBetweenTheTwoFilesKeepsOnlyTheAccountCopy() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("Old")));
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("Other")));
    List<PendingLetter> letters = store.identifyPending(DEAD);
    String pendingBefore = Files.readString(root.resolve("pending_mail.yml"));
    store.moveEstateLetters(DEAD, List.of(
        new Move(letters.getFirst().id, Destination.SENDER_ACCOUNT, null, WRITER.ownerId())));
    // The account queue was saved, then the process died before the dead character's queue was.
    Files.writeString(root.resolve("pending_mail.yml"), pendingBefore);

    MailStore restored = reload();

    assertEquals(List.of(letters.get(1).id), restored.pendingFor(DEAD).stream().map(letter -> letter.id).toList());
    verify(logger).warning(contains("already returned"));
    assertEquals(1, reload().pendingFor(DEAD).size());
  }

  @Test
  void movesNamingNoQueuedLetterChangeNothing() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("Old")));
    String before = Files.readString(root.resolve("pending_mail.yml"));

    assertEquals(List.of(), store.moveEstateLetters(DEAD, List.of(
        new Move(UUID.randomUUID(), Destination.HEIR, HEIR.characterId().toString(), HEIR.ownerId()))));

    assertEquals(before, Files.readString(root.resolve("pending_mail.yml")));
    assertFalse(Files.exists(root.resolve("returned_mail.yml")));
  }

  @Test
  void damagedReturnedQueueIsPreservedAndGoodLettersLoad() throws Exception {
    YamlConfiguration returned = new YamlConfiguration();
    returned.set("accounts." + WRITER.ownerId() + ".letters",
        List.of(Map.of("id", UUID.randomUUID().toString(), "item", serialized("Good")), Map.of("item", "bad")));
    returned.set("accounts.not-a-uuid.letters", List.of(Map.of("item", serialized("Lost"))));
    returned.set("accounts." + UUID.randomUUID() + ".letters", List.of(Map.of("item", "bad")));
    returned.save(root.resolve("returned_mail.yml").toFile());

    MailStore store = reload();

    List<PendingLetter> handed = new ArrayList<>();
    store.drainReturned(WRITER.ownerId(), () -> true, handed::add);
    assertEquals(1, handed.size());
    try (var files = Files.list(root)) {
      assertTrue(files.anyMatch(file -> file.getFileName().toString().startsWith("returned_mail.yml.corrupt-")));
    }
  }

  @Test
  void unreadableOrMisshapenReturnedQueueIsPreservedBeforeAnyWrite() throws Exception {
    Files.writeString(root.resolve("returned_mail.yml"), "accounts: [not, a, section]\n");
    reload();
    Files.writeString(root.resolve("returned_mail.yml"), "accounts: {\n");
    MailStore store = reload();
    Files.writeString(root.resolve("returned_mail.yml"), "other: 1\n");
    reload();
    try (var files = Files.list(root)) {
      assertEquals(2, files.filter(file -> file.getFileName().toString().startsWith("returned_mail.yml.corrupt-")).count());
    }
    // Once preserved, the queue can be written again.
    assertDoesNotThrow(store::saveAll);
  }

  @Test
  void aReturnedQueueThatCannotBeSavedMovesNothing() throws Exception {
    Files.createDirectories(root.resolve("returned_mail.yml").resolve("blocked"));
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("Old")));
    UUID id = store.identifyPending(DEAD).getFirst().id;

    assertThrows(java.io.IOException.class, () -> store.moveEstateLetters(DEAD,
        List.of(new Move(id, Destination.SENDER_ACCOUNT, null, WRITER.ownerId()))));

    assertEquals(List.of(id), store.pendingFor(DEAD).stream().map(letter -> letter.id).toList());
    List<PendingLetter> handed = new ArrayList<>();
    store.drainReturned(WRITER.ownerId(), () -> true, handed::add);
    assertEquals(List.of(), handed);
  }

  @Test
  void aPendingQueueThatCannotBeSavedPutsEveryQueueBack() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("To heir")));
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("To account")));
    List<PendingLetter> letters = store.identifyPending(DEAD);
    Path pending = root.resolve("pending_mail.yml");
    Files.delete(pending);
    Files.createDirectories(pending.resolve("blocked"));

    assertThrows(java.io.IOException.class, () -> store.moveEstateLetters(DEAD, List.of(
        new Move(letters.get(0).id, Destination.HEIR, HEIR.characterId().toString(), HEIR.ownerId()),
        new Move(letters.get(1).id, Destination.SENDER_ACCOUNT, null, WRITER.ownerId()))));

    assertEquals(2, store.pendingFor(DEAD).size());
    assertEquals(List.of(), store.pendingFor(HEIR.characterId().toString()));
    List<PendingLetter> handed = new ArrayList<>();
    store.drainReturned(WRITER.ownerId(), () -> true, handed::add);
    assertEquals(List.of(), handed);
  }

  @Test
  void aDeliveryThatCannotBeSavedIsLogged() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("Old")));
    UUID id = store.identifyPending(DEAD).getFirst().id;
    store.moveEstateLetters(DEAD, List.of(new Move(id, Destination.SENDER_ACCOUNT, null, WRITER.ownerId())));
    Path returned = root.resolve("returned_mail.yml");
    Files.delete(returned);
    Files.createDirectories(returned.resolve("blocked"));

    List<PendingLetter> handed = new ArrayList<>();
    store.drainReturned(WRITER.ownerId(), () -> true, handed::add);

    assertEquals(1, handed.size());
    verify(logger).log(eq(java.util.logging.Level.SEVERE), eq("Could not save returned mail"), any(Throwable.class));
  }

  @Test
  void idsThatCannotBeSavedAreNotKept() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("Old")));
    Path pending = root.resolve("pending_mail.yml");
    Files.delete(pending);
    Files.createDirectories(pending.resolve("blocked"));

    assertThrows(java.io.IOException.class, () -> store.identifyPending(DEAD));

    assertNull(store.pendingFor(DEAD).getFirst().id);
  }

  @Test
  void aReceivingQueueOwnedByAnotherAccountStopsTheWholeMove() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(HEIR.characterId().toString(),
        new PendingLetter(UUID.randomUUID(), WRITER.ownerId(), "Heir", letter("Odd")));
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("A")));
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("B")));
    List<PendingLetter> letters = store.identifyPending(DEAD);
    String before = Files.readString(root.resolve("pending_mail.yml"));

    assertThrows(IllegalStateException.class, () -> store.moveEstateLetters(DEAD, List.of(
        new Move(letters.get(0).id, Destination.SENDER_ACCOUNT, null, WRITER.ownerId()),
        new Move(letters.get(1).id, Destination.HEIR, HEIR.characterId().toString(), HEIR.ownerId()))));

    assertEquals(2, store.pendingFor(DEAD).size());
    assertEquals(1, store.pendingFor(HEIR.characterId().toString()).size());
    assertEquals(before, Files.readString(root.resolve("pending_mail.yml")));
    assertFalse(Files.exists(root.resolve("returned_mail.yml")));
  }

  @Test
  void movedLettersDoNotShareTheirItemWithTheOriginal() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("A")));
    PendingLetter original = store.identifyPending(DEAD).getFirst();

    store.moveEstateLetters(DEAD, List.of(
        new Move(original.id, Destination.HEIR, HEIR.characterId().toString(), HEIR.ownerId())));

    assertNotSame(original.item, store.pendingFor(HEIR.characterId().toString()).getFirst().item);
  }

  @Test
  void queuesAreReplacedWholeWithNoTemporaryFilesLeft() throws Exception {
    MailStore store = new MailStore(plugin);
    store.addPending(DEAD, new PendingLetter(DEAD_ACCOUNT, WRITER.ownerId(), "Dead", letter("A")));
    try (var files = Files.list(root)) {
      assertEquals(List.of(root.resolve("pending_mail.yml")), files.toList());
    }
  }

  private MailStore reload() {
    MailStore store = new MailStore(plugin);
    store.load();
    return store;
  }

  private static ItemStack letter(String title) {
    return item(serialized(title));
  }

  private static ItemStack item(Map<String, Object> serialized) {
    ItemStack item = mock(ItemStack.class);
    when(item.serialize()).thenReturn(serialized);
    when(item.clone()).thenAnswer(invocation -> item(serialized));
    return item;
  }

  private static Map<String, Object> serialized(String title) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("schema_version", 1);
    map.put("DataVersion", 4556);
    map.put("id", "minecraft:written_book");
    map.put("count", 1);
    map.put("title", title);
    return map;
  }
}

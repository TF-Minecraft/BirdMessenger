package net.tfminecraft.birdmessenger.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.tfminecraft.birdmessenger.BirdMessenger;
import org.bukkit.Bukkit;
import org.bukkit.UnsafeValues;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class MailStoreCoverageTest {
  private static final UUID SENDER = UUID.fromString("6b00e6b9-c3d4-4d77-8457-c17d78c20584");
  private static final UUID OWNER = UUID.fromString("cd9f54e7-947a-456d-9010-29d3e548af6b");
  private static final UUID ID = UUID.fromString("3af8adf2-d931-43ec-b54e-cbb36d1eb03c");
  private static final String CHARACTER = "character-one";
  @TempDir Path root;
  private BirdMessenger plugin;
  private UnsafeValues unsafe;
  private Logger logger;
  private MockedStatic<Bukkit> bukkit;

  @BeforeEach
  void setup() {
    plugin = mock(BirdMessenger.class);
    logger = mock(Logger.class);
    when(plugin.getDataFolder()).thenReturn(root.resolve("mail-data").toFile());
    when(plugin.getLogger()).thenReturn(logger);
    unsafe = mock(UnsafeValues.class);
    bukkit = mockStatic(Bukkit.class);
    bukkit.when(Bukkit::getUnsafe).thenReturn(unsafe);
    bukkit.when(Bukkit::getLogger).thenReturn(logger);
    when(unsafe.deserializeStack(anyMap()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> data = invocation.getArgument(0);
              if (!data.containsKey("id")) throw new IllegalArgumentException("Missing item id");
              return item(data);
            });
  }

  @AfterEach
  void closeBukkit() {
    bukkit.close();
  }

  @Test
  void inFlightSurvivesYamlRoundTripWithItsNestedContentsAndDeliveryIdentity() {
    Map<String, Object> serialized = serializedItem("Treaty");
    ItemStack original = item(serialized);
    StoredMail mail =
        new StoredMail(ID, SENDER, OWNER, CHARACTER, "Lady Alice", original, 8123456789012L);
    MailStore first = new MailStore(plugin);
    first.putInFlight(mail);
    first.saveAll();

    MailStore restored = new MailStore(plugin);
    restored.load();

    assertEquals(1, restored.inFlight().size(), "A stored letter must survive a restart");
    StoredMail result = restored.inFlight().get(ID);
    assertAll(
        () -> assertEquals(ID, result.getId()),
        () -> assertEquals(SENDER, result.getSenderUuid()),
        () -> assertEquals(OWNER, result.getOwnerUuid()),
        () -> assertEquals(CHARACTER, result.getCharacterId()),
        () -> assertEquals("Lady Alice", result.getAddresseeDisplayTab()),
        () -> assertEquals(8123456789012L, result.getDeliveryTime()),
        () -> assertEquals(serialized, result.getItem().serialize()));
    verify(unsafe).deserializeStack(serialized);
  }

  @Test
  void inFlightPreservesLiteralDotsInsideItemComponentKeys() {
    Map<String, Object> serialized = serializedItem("Custom data");
    serialized.put(
        "components",
        Map.of(
            "minecraft:custom_data", Map.of("plugin.key", Map.of("nested.key", "literal value"))));
    MailStore store = new MailStore(plugin);
    store.putInFlight(new StoredMail(ID, SENDER, OWNER, CHARACTER, "Alice", item(serialized), 11L));
    MailStore restored = new MailStore(plugin);
    restored.load();
    assertEquals(serialized, restored.inFlight().get(ID).getItem().serialize());
  }

  @Test
  void oneMalformedPendingLetterDoesNotDiscardValidSiblings() throws Exception {
    new MailStore(plugin);
    Map<String, Object> before = serializedItem("Before");
    Map<String, Object> after = serializedItem("After");
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.set("characters." + CHARACTER + ".owner", OWNER.toString());
    yaml.set(
        "characters." + CHARACTER + ".letters",
        List.of(
            Map.of("item", before, "sender", SENDER.toString(), "addressee-display", "Alice"),
            Map.of("item", Map.of("schema_version", 1)),
            Map.of("item", after)));
    yaml.save(root.resolve("mail-data/pending_mail.yml").toFile());

    MailStore restored = new MailStore(plugin);
    restored.load();

    List<MailStore.PendingLetter> letters = restored.takePending(CHARACTER);
    assertEquals(2, letters.size(), "Only the broken entry should be skipped");
    assertEquals(before, letters.get(0).item.serialize());
    assertEquals(after, letters.get(1).item.serialize());
    assertEquals(SENDER, letters.get(0).senderUuid);
    assertEquals("Alice", letters.get(0).addresseeDisplayTab);
    assertEquals(OWNER, letters.get(1).ownerUuid);
    verify(logger).warning(contains(CHARACTER));
  }

  @Test
  void oneMalformedLegacyPendingItemDoesNotDiscardValidSiblings() throws Exception {
    new MailStore(plugin);
    Map<String, Object> before = serializedItem("Legacy before");
    Map<String, Object> after = serializedItem("Legacy after");
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.set("characters." + CHARACTER + ".owner", OWNER.toString());
    yaml.set(
        "characters." + CHARACTER + ".items", List.of(before, Map.of("schema_version", 1), after));
    yaml.save(root.resolve("mail-data/pending_mail.yml").toFile());

    MailStore restored = new MailStore(plugin);
    restored.load();

    List<MailStore.PendingLetter> letters = restored.takePending(CHARACTER);
    assertEquals(2, letters.size(), "Legacy format also isolates broken entries");
    assertEquals(before, letters.get(0).item.serialize());
    assertEquals(after, letters.get(1).item.serialize());
    assertNull(letters.get(0).senderUuid);
    assertEquals("", letters.get(1).addresseeDisplayTab);
    verify(logger).warning(contains(CHARACTER));
  }

  @Test
  void coldStartKeepsAnExactRecoveryCopyBeforeReplacingMalformedYaml() throws Exception {
    new MailStore(plugin);
    String damaged = "mail: [not closed\n# original recoverable text\n";
    for (String file : List.of("in_flight_mail.yml", "pending_mail.yml")) {
      Files.writeString(root.resolve("mail-data").resolve(file), damaged);
    }
    MailStore store = new MailStore(plugin);
    store.load();
    store.takePending(CHARACTER);
    store.saveAll();
    for (String file : List.of("in_flight_mail.yml", "pending_mail.yml")) {
      assertEquals(damaged, Files.readString(recoveryCopy(file)));
      assertNotEquals(damaged, Files.readString(root.resolve("mail-data").resolve(file)));
    }
  }

  @Test
  void skippedEntriesRemainRecoverableAfterValidMailIsConsumed() throws Exception {
    new MailStore(plugin);
    YamlConfiguration pending = new YamlConfiguration();
    pending.set("characters." + CHARACTER + ".owner", OWNER.toString());
    pending.set(
        "characters." + CHARACTER + ".letters",
        List.of(
            Map.of("item", serializedItem("Good")), Map.of("item", "recoverable invalid payload")));
    pending.save(root.resolve("mail-data/pending_mail.yml").toFile());
    YamlConfiguration flight = new YamlConfiguration();
    flight.set("mail." + ID, flightRecord(serializedItem("Good flight")));
    flight.set("mail.not-a-uuid", Map.of("item", "recoverable flight payload"));
    flight.save(root.resolve("mail-data/in_flight_mail.yml").toFile());
    String originalPending = Files.readString(root.resolve("mail-data/pending_mail.yml"));
    String originalFlight = Files.readString(root.resolve("mail-data/in_flight_mail.yml"));
    MailStore store = new MailStore(plugin);
    store.load();
    assertEquals(1, store.takePending(CHARACTER).size());
    assertNotNull(store.removeInFlight(ID));
    store.saveAll();
    assertEquals(originalPending, Files.readString(recoveryCopy("pending_mail.yml")));
    assertEquals(originalFlight, Files.readString(recoveryCopy("in_flight_mail.yml")));
    MailStore restored = new MailStore(plugin);
    restored.load();
    assertFalse(restored.hasPending(CHARACTER));
    assertTrue(restored.inFlight().isEmpty());
    assertEquals(originalPending, Files.readString(recoveryCopy("pending_mail.yml")));
  }

  private Path recoveryCopy(String file) throws IOException {
    try (var files = Files.list(root.resolve("mail-data"))) {
      List<Path> copies =
          files
              .filter(path -> path.getFileName().toString().startsWith(file + ".corrupt-"))
              .toList();
      assertEquals(
          1, copies.size(), "Keep exactly one untouched recovery copy per damaged load of " + file);
      return copies.getFirst();
    }
  }

  @Test
  void invalidRootSectionsAreBackedUpBeforeAnEmptyStateCanReplaceThem() throws Exception {
    new MailStore(plugin);
    String flight = "mail: recoverable-unexpected-value\n";
    String pending = "characters: [recoverable, unexpected, list]\n";
    Files.writeString(root.resolve("mail-data/in_flight_mail.yml"), flight);
    Files.writeString(root.resolve("mail-data/pending_mail.yml"), pending);
    MailStore store = new MailStore(plugin);
    store.load();
    store.saveAll();
    assertEquals(flight, Files.readString(recoveryCopy("in_flight_mail.yml")));
    assertEquals(pending, Files.readString(recoveryCopy("pending_mail.yml")));
  }

  @Test
  void backupFailureBlocksSavesUntilTheMailFileIsRecovered() throws Exception {
    MailStore store = new MailStore(plugin);
    Path pending = root.resolve("mail-data/pending_mail.yml");
    String damaged = "characters: [unclosed\n";
    Files.writeString(pending, damaged);
    try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
      files
          .when(() -> Files.copy(eq(pending), any(Path.class)))
          .thenThrow(new IOException("Recovery destination unavailable"));
      IllegalStateException failure = assertThrows(IllegalStateException.class, store::load);
      assertEquals("Recovery destination unavailable", failure.getCause().getMessage());
      assertThrows(IllegalStateException.class, store::saveAll);
      assertEquals(damaged, Files.readString(pending));
    }
    store.load();
    store.saveAll();
    assertEquals(damaged, Files.readString(recoveryCopy("pending_mail.yml")));
    assertFalse(YamlConfiguration.loadConfiguration(pending.toFile()).contains("characters"));
  }

  @Test
  void newStoreHandlesMissingFilesAndNullRequestsWithoutCreatingMail() throws Exception {
    MailStore store = new MailStore(plugin);
    assertTrue(Files.isDirectory(root.resolve("mail-data")));
    store.load();
    store.putInFlight(null);
    store.addPending(null);
    store.addPending(
        new StoredMail(ID, SENDER, OWNER, null, "Alice", item(serializedItem("Ignored")), 1L));
    assertNull(store.removeInFlight(null));
    assertFalse(store.hasPending(null));
    assertFalse(store.hasPending(CHARACTER));
    assertTrue(store.takePending(null).isEmpty());
    assertTrue(store.inFlight().isEmpty());
    assertFalse(Files.exists(root.resolve("mail-data/in_flight_mail.yml")));
    assertFalse(Files.exists(root.resolve("mail-data/pending_mail.yml")));
    assertTrue(store.takePending(CHARACTER).isEmpty());
    store.saveAll();
    MailStore restored = new MailStore(plugin);
    restored.load();
    assertTrue(restored.inFlight().isEmpty());
    assertFalse(restored.hasPending(CHARACTER));
    verifyNoInteractions(unsafe);
  }

  @Test
  void failedItemSnapshotDoesNotLeaveAnIncompletePendingRecord() {
    MailStore store = new MailStore(plugin);
    ItemStack original = mock(ItemStack.class);
    IllegalStateException failure = new IllegalStateException("Item snapshot unavailable");
    when(original.clone()).thenThrow(failure);
    StoredMail mail = new StoredMail(ID, SENDER, OWNER, CHARACTER, "Alice", original, 12L);
    assertSame(failure, assertThrows(IllegalStateException.class, () -> store.addPending(mail)));
    assertFalse(store.hasPending(CHARACTER));
    assertDoesNotThrow(store::saveAll);
    YamlConfiguration saved =
        YamlConfiguration.loadConfiguration(root.resolve("mail-data/pending_mail.yml").toFile());
    assertFalse(saved.contains("characters"));
    MailStore restored = new MailStore(plugin);
    restored.load();
    assertFalse(restored.hasPending(CHARACTER));
  }

  @Test
  void pendingRoundTripClonesLettersPreservesOptionalMetadataAndPersistsConsumption() {
    ItemStack original = item(serializedItem("First pending letter"));
    ItemStack second = item(serializedItem("Second pending letter"));
    MailStore store = new MailStore(plugin);
    store.addPending(new StoredMail(ID, SENDER, OWNER, CHARACTER, "Lady Alice", original, 12L));
    store.addPending(new StoredMail(UUID.randomUUID(), null, OWNER, CHARACTER, null, second, 13L));
    verify(original).clone();
    verify(second).clone();
    assertTrue(store.hasPending(CHARACTER));

    MailStore restored = new MailStore(plugin);
    restored.load();
    assertTrue(restored.hasPending(CHARACTER));
    List<MailStore.PendingLetter> letters = restored.takePending(CHARACTER);
    assertEquals(2, letters.size());
    assertNotSame(original, letters.get(0).item);
    assertEquals(original.serialize(), letters.get(0).item.serialize());
    assertEquals(second.serialize(), letters.get(1).item.serialize());
    assertEquals(OWNER, letters.get(0).ownerUuid);
    assertEquals(SENDER, letters.get(0).senderUuid);
    assertEquals("Lady Alice", letters.get(0).addresseeDisplayTab);
    assertNull(letters.get(1).senderUuid);
    assertEquals("", letters.get(1).addresseeDisplayTab);
    assertFalse(restored.hasPending(CHARACTER));
    assertTrue(restored.takePending(CHARACTER).isEmpty());
    restored.load();
    assertFalse(restored.hasPending(CHARACTER), "Consumed letters must not return after restart");
    assertEquals(2, letters.size(), "Previously returned letters remain available to delivery");
  }

  @Test
  void removingAnInFlightLetterPersistsOnlyThatRemoval() {
    MailStore store = new MailStore(plugin);
    StoredMail first =
        new StoredMail(ID, SENDER, OWNER, CHARACTER, "Alice", item(serializedItem("First")), 1L);
    UUID laterId = UUID.randomUUID();
    StoredMail second =
        new StoredMail(
            laterId, SENDER, OWNER, CHARACTER, "Alice", item(serializedItem("Second")), 2L);
    store.putInFlight(first);
    store.putInFlight(second);
    assertSame(first, store.removeInFlight(ID));
    assertNull(store.removeInFlight(ID));
    MailStore restored = new MailStore(plugin);
    restored.load();
    assertEquals(java.util.Set.of(laterId), restored.inFlight().keySet());
    assertEquals(2L, restored.inFlight().get(laterId).getDeliveryTime());
  }

  @Test
  void legacyInFlightBookRestoresWhileMalformedSiblingsAreSkipped() throws Exception {
    new MailStore(plugin);
    YamlConfiguration yaml = new YamlConfiguration();
    Map<String, Object> valid = flightRecord(serializedItem("Legacy book"));
    Object item = valid.remove("item");
    valid.put("book", item);
    valid.remove("addressee-display");
    yaml.set("mail." + ID, valid);
    yaml.set("mail.not-a-uuid", flightRecord(serializedItem("Bad id")));
    for (String field : List.of("sender", "owner", "character-id")) {
      Map<String, Object> missing = flightRecord(serializedItem("Missing " + field));
      missing.remove(field);
      yaml.set("mail." + UUID.randomUUID(), missing);
    }
    for (Map.Entry<String, Object> bad :
        Map.<String, Object>of(
                "character-id",
                " ",
                "sender",
                "not-a-uuid",
                "owner",
                "not-a-uuid",
                "item",
                "not-an-item")
            .entrySet()) {
      Map<String, Object> record = flightRecord(serializedItem("Invalid " + bad.getKey()));
      record.put(bad.getKey(), bad.getValue());
      yaml.set("mail." + UUID.randomUUID(), record);
    }
    Map<String, Object> noItem = flightRecord(serializedItem("Missing item"));
    noItem.remove("item");
    yaml.set("mail." + UUID.randomUUID(), noItem);
    Map<String, Object> invalidItem = flightRecord(Map.of("schema_version", 1));
    yaml.set("mail." + UUID.randomUUID(), invalidItem);
    yaml.save(root.resolve("mail-data/in_flight_mail.yml").toFile());

    MailStore store = new MailStore(plugin);
    store.load();
    assertEquals(java.util.Set.of(ID), store.inFlight().keySet());
    assertEquals(serializedItem("Legacy book"), store.inFlight().get(ID).getItem().serialize());
    assertEquals("", store.inFlight().get(ID).getAddresseeDisplayTab());
    assertEquals(987654321L, store.inFlight().get(ID).getDeliveryTime());
    verify(logger, atLeastOnce()).warning(startsWith("Skipping in-flight mail"));
    verify(logger, atLeastOnce()).warning(startsWith("Could not load in-flight mail"));
  }

  @Test
  void malformedPendingRecordsDoNotBecomeEmptyItemsOrHideAnotherCharacter() throws Exception {
    new MailStore(plugin);
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.set("characters." + CHARACTER + ".owner", OWNER.toString());
    yaml.set(
        "characters." + CHARACTER + ".letters",
        Arrays.asList(
            "not-a-map",
            null,
            Map.of("item", "not-an-item"),
            Map.of("sender", SENDER.toString()),
            Map.of("item", serializedItem("Recoverable"), "sender", "legacy-invalid-uuid")));
    yaml.set(
        "characters.missing-owner.letters",
        List.of(Map.of("item", serializedItem("Missing owner"))));
    yaml.set("characters.invalid-owner.owner", "not-a-uuid");
    yaml.set("characters.invalid-list.owner", OWNER.toString());
    yaml.set("characters.invalid-list.letters", "not-a-list");
    yaml.set("characters.empty-list.owner", OWNER.toString());
    yaml.set("characters.empty-list.letters", List.of());
    yaml.set("characters.missing-list.owner", OWNER.toString());
    yaml.save(root.resolve("mail-data/pending_mail.yml").toFile());

    MailStore store = new MailStore(plugin);
    store.load();
    List<MailStore.PendingLetter> letters = store.takePending(CHARACTER);
    assertEquals(1, letters.size());
    assertEquals(serializedItem("Recoverable"), letters.getFirst().item.serialize());
    assertNull(
        letters.getFirst().senderUuid, "Legacy sender corruption does not destroy readable mail");
    assertEquals("", letters.getFirst().addresseeDisplayTab);
    for (String invalid :
        List.of("missing-owner", "invalid-owner", "invalid-list", "empty-list", "missing-list")) {
      assertFalse(store.hasPending(invalid), invalid);
    }
    verify(logger, times(4)).warning("Could not load pending letter for " + CHARACTER);
    verify(logger).warning("Could not load pending mail for invalid-owner");
    verify(logger).warning("Could not load pending mail for invalid-list");
  }

  @Test
  void saveFailuresLeaveInMemoryMailAvailableForRetry() throws Exception {
    MailStore store = new MailStore(plugin);
    Path inFlight = root.resolve("mail-data/in_flight_mail.yml");
    Path pending = root.resolve("mail-data/pending_mail.yml");
    Files.createDirectory(inFlight);
    Files.createDirectory(pending);
    StoredMail mail =
        new StoredMail(ID, SENDER, OWNER, CHARACTER, "Alice", item(serializedItem("Retry")), 42L);
    assertDoesNotThrow(() -> store.putInFlight(mail));
    assertDoesNotThrow(() -> store.addPending(mail));
    assertSame(mail, store.inFlight().get(ID));
    assertTrue(store.hasPending(CHARACTER));
    verify(logger)
        .log(eq(Level.SEVERE), eq("Could not save in-flight mail"), any(IOException.class));
    verify(logger).log(eq(Level.SEVERE), eq("Could not save pending mail"), any(IOException.class));

    Files.delete(inFlight);
    Files.delete(pending);
    store.saveAll();
    MailStore restored = new MailStore(plugin);
    restored.load();
    assertEquals(mail.getItem().serialize(), restored.inFlight().get(ID).getItem().serialize());
    assertEquals(
        mail.getItem().serialize(), restored.takePending(CHARACTER).getFirst().item.serialize());
  }

  @Test
  void unreadableMailFilesDoNotEraseAlreadyLoadedLetters() throws Exception {
    MailStore store = new MailStore(plugin);
    StoredMail mail =
        new StoredMail(ID, SENDER, OWNER, CHARACTER, "Alice", item(serializedItem("Keep")), 42L);
    store.putInFlight(mail);
    store.addPending(mail);
    for (String file : List.of("in_flight_mail.yml", "pending_mail.yml")) {
      Path path = root.resolve("mail-data").resolve(file);
      Files.delete(path);
      Files.createDirectory(path);
    }
    assertThrows(IllegalStateException.class, store::load);
    assertThrows(IllegalStateException.class, store::saveAll);
    assertSame(mail, store.inFlight().get(ID));
    assertTrue(store.hasPending(CHARACTER));
  }

  @Test
  void malformedYamlDoesNotEraseAlreadyLoadedLetters() throws Exception {
    MailStore store = new MailStore(plugin);
    StoredMail mail =
        new StoredMail(ID, SENDER, OWNER, CHARACTER, "Alice", item(serializedItem("Keep")), 42L);
    store.putInFlight(mail);
    store.addPending(mail);
    for (String file : List.of("in_flight_mail.yml", "pending_mail.yml")) {
      Files.writeString(root.resolve("mail-data").resolve(file), "broken: [not closed\n");
    }
    assertDoesNotThrow(store::load);
    assertSame(mail, store.inFlight().get(ID));
    assertTrue(store.hasPending(CHARACTER));
  }

  @Test
  void explicitlyEmptyFilesReplacePreviouslyLoadedMail() throws Exception {
    MailStore store = new MailStore(plugin);
    StoredMail mail =
        new StoredMail(ID, SENDER, OWNER, CHARACTER, "Alice", item(serializedItem("Old")), 42L);
    store.putInFlight(mail);
    store.addPending(mail);
    Files.writeString(root.resolve("mail-data/in_flight_mail.yml"), "# intentionally empty\n");
    Files.writeString(root.resolve("mail-data/pending_mail.yml"), "# intentionally empty\n");
    store.load();
    assertTrue(store.inFlight().isEmpty());
    assertFalse(store.hasPending(CHARACTER));
  }

  @Test
  void pendingLetterConstructorKeepsItsIdentityAndNormalizesAnAbsentDisplay() {
    ItemStack item = item(serializedItem("Legacy"));
    MailStore.PendingLetter letter = new MailStore.PendingLetter(OWNER, null, null, item);
    assertSame(item, letter.item);
    assertEquals(OWNER, letter.ownerUuid);
    assertNull(letter.senderUuid);
    assertEquals("", letter.addresseeDisplayTab);
  }

  private static ItemStack item(Map<String, Object> serialized) {
    ItemStack item = mock(ItemStack.class);
    when(item.serialize()).thenReturn(serialized);
    when(item.clone()).thenAnswer(invocation -> item(serialized));
    return item;
  }

  private static Map<String, Object> flightRecord(Map<String, Object> item) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("sender", SENDER.toString());
    record.put("owner", OWNER.toString());
    record.put("character-id", CHARACTER);
    record.put("addressee-display", "Alice");
    record.put("delivery-time", 987654321L);
    record.put("item", item);
    return record;
  }

  private static Map<String, Object> serializedItem(String title) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("schema_version", 1);
    map.put("DataVersion", 4556);
    map.put("id", "minecraft:written_book");
    map.put("count", 1);
    map.put(
        "components",
        Map.of(
            "minecraft:written_book_content",
            Map.of(
                "title", Map.of("raw", title),
                "author", "Sender",
                "pages",
                    List.of(Map.of("raw", "A letter with §aformatting"), Map.of("raw", "Page 2"))),
            "minecraft:custom_data",
            Map.of("PublicBukkitValues", Map.of("tfmccore:sealed_letter", 1))));
    return map;
  }
}

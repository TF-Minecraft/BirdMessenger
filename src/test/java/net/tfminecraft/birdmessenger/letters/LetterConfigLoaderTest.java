package net.tfminecraft.birdmessenger.letters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LetterConfigLoaderTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetConfig() throws IOException {
        // LetterConfig is static/global, so every test starts from the same baseline.
        LetterConfig.letterPath = "m.books.letter";
        LetterConfig.writtenLetterPath = "m.books.written_letter";
        LetterConfig.writtenLetterOpenPath = "m.books.written_letter_open";
        LetterConfig.hideAuthor = true;
        LetterConfig.useTitleAsName = true;
        LetterConfig.signedMessage = "&aYou have successfully signed your letter!";
        LetterConfig.openedMessage = "&7The seal breaks as you open the letter...";
        LetterConfig.creationFailedMessage = "&cFailed to create letter. Please make a ticket.";
        LetterConfig.openFailedMessage = "&cFailed to open letter. Please make a ticket.";
    }

    @Test
    void loadsFullConfiguration() throws IOException {
        Path configPath = tempDir.resolve("letters-config.yml");
        Files.writeString(configPath, """
                items:
                  letter: "ia.tfmc.letter"
                  written-letter: "ia.tfmc.written_letter"
                  written-letter-open: "ia.tfmc.written_letter_open"
                settings:
                  hide-author: false
                  use-title-as-name: false
                messages:
                  letter-signed: "&aSigned!"
                  letter-opened: "&7Opened!"
                  letter-creation-failed: "&cCreation failed."
                  letter-open-failed: "&cOpen failed."
                """);

        assertTrue(LetterConfigLoader.load(configPath.toFile(), java.util.logging.Logger.getAnonymousLogger()));

        assertEquals("ia.tfmc.letter", LetterConfig.letterPath);
        assertEquals("ia.tfmc.written_letter", LetterConfig.writtenLetterPath);
        assertEquals("ia.tfmc.written_letter_open", LetterConfig.writtenLetterOpenPath);
        assertFalse(LetterConfig.hideAuthor);
        assertFalse(LetterConfig.useTitleAsName);
        assertEquals("&aSigned!", LetterConfig.signedMessage);
        assertEquals("&7Opened!", LetterConfig.openedMessage);
        assertEquals("&cCreation failed.", LetterConfig.creationFailedMessage);
        assertEquals("&cOpen failed.", LetterConfig.openFailedMessage);
    }

    @Test
    void missingKeysKeepDefaults() throws IOException {
        Path configPath = tempDir.resolve("letters-config.yml");
        Files.writeString(configPath, """
                items:
                  letter: "ia.tfmc.letter"
                """);

        assertTrue(LetterConfigLoader.load(configPath.toFile(), java.util.logging.Logger.getAnonymousLogger()));

        assertEquals("ia.tfmc.letter", LetterConfig.letterPath);
        assertEquals("m.books.written_letter", LetterConfig.writtenLetterPath);
        assertEquals("m.books.written_letter_open", LetterConfig.writtenLetterOpenPath);
        assertTrue(LetterConfig.hideAuthor);
        assertTrue(LetterConfig.useTitleAsName);
        assertEquals("&aYou have successfully signed your letter!", LetterConfig.signedMessage);
        assertEquals("&7The seal breaks as you open the letter...", LetterConfig.openedMessage);
        assertEquals("&cFailed to create letter. Please make a ticket.", LetterConfig.creationFailedMessage);
        assertEquals("&cFailed to open letter. Please make a ticket.", LetterConfig.openFailedMessage);
    }

    @Test
    void booleanSettingsCanBeDisabled() throws IOException {
        Path configPath = tempDir.resolve("letters-config.yml");
        Files.writeString(configPath, """
                settings:
                  hide-author: false
                  use-title-as-name: false
                """);

        assertTrue(LetterConfigLoader.load(configPath.toFile(), java.util.logging.Logger.getAnonymousLogger()));

        assertFalse(LetterConfig.hideAuthor);
        assertFalse(LetterConfig.useTitleAsName);
    }

    @Test
    void messagesKeepRawColourCodes() throws IOException {
        Path configPath = tempDir.resolve("letters-config.yml");
        Files.writeString(configPath, """
                messages:
                  letter-signed: "&aSigned &#FF0000now!"
                  letter-opened: "&7The seal &#00FF00breaks..."
                  letter-creation-failed: "&cNope &#123456."
                  letter-open-failed: "&cNope &#abcdef."
                """);

        assertTrue(LetterConfigLoader.load(configPath.toFile(), java.util.logging.Logger.getAnonymousLogger()));

        // The loader must not colour messages - that happens at send time.
        assertEquals("&aSigned &#FF0000now!", LetterConfig.signedMessage);
        assertEquals("&7The seal &#00FF00breaks...", LetterConfig.openedMessage);
        assertEquals("&cNope &#123456.", LetterConfig.creationFailedMessage);
        assertEquals("&cNope &#abcdef.", LetterConfig.openFailedMessage);
    }
}

package net.tfminecraft.birdmessenger.letters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.birdmessenger.BirdMessenger;

class LetterMigrationTest {
    @TempDir Path root;

    @Test void migrationPreservesSourceAndCopiesExactConfigOnlyOnce() throws Exception {
        Path old = root.resolve("TFMCCore/letters-config.yml");
        Path current = root.resolve("BirdMessenger/letters-config.yml");
        Files.createDirectories(old.getParent());
        String original = "# live server overrides\nitems:\n  letter: ia.custom:letter\nsettings:\n  hide-author: false\n";
        Files.writeString(old, original);
        LetterFeature.migrateConfig(old, current);
        assertEquals(original, Files.readString(old));
        assertEquals(original, Files.readString(current));
        Files.writeString(current, "items: {letter: ia.new:letter}\n");
        Files.writeString(old, "items: [broken\n");
        LetterFeature.migrateConfig(old, current);
        assertEquals("items: {letter: ia.new:letter}\n", Files.readString(current));
    }

    @Test void malformedLegacyFileDoesNotCreateDestinationOrDefaults() throws Exception {
        Path old = root.resolve("old.yml");
        Path current = root.resolve("BirdMessenger/letters-config.yml");
        Files.writeString(old, "items: [broken\n");
        assertThrows(InvalidConfigurationException.class, () -> LetterFeature.migrateConfig(old, current));
        assertFalse(Files.exists(current));
        assertEquals("items: [broken\n", Files.readString(old));
    }

    @Test void mixedVersionsRequireExplicitOwnershipHandoff() throws Exception {
        assertTrue(LetterFeature.canOwnLetters(null));
        Plugin core = mock(Plugin.class);
        assertFalse(LetterFeature.canOwnLetters(core));
        when(core.getResource("plugin.yml")).thenReturn(resource("name: TFMCCore\n"));
        assertFalse(LetterFeature.canOwnLetters(core));
        when(core.getResource("plugin.yml")).thenReturn(resource("feature-owners:\n  letters: BirdMessenger\n"));
        assertTrue(LetterFeature.canOwnLetters(core));
        when(core.getResource("plugin.yml")).thenReturn(resource("feature-owners:\n  letters: AnotherPlugin\n"));
        assertFalse(LetterFeature.canOwnLetters(core));
    }

    @Test void startupMigratesBeforeDefaultsAndReloadDoesNotDuplicateListeners() throws Exception {
        BirdMessenger bird = mock(BirdMessenger.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(bird.getDataFolder()).thenReturn(root.resolve("BirdMessenger").toFile());
        when(bird.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(bird.getLogger()).thenReturn(mock(Logger.class));
        Path legacy = root.resolve("TFMCCore/letters-config.yml");
        Files.createDirectories(legacy.getParent());
        Files.writeString(legacy, "items:\n  letter: m.books.migrated\n");

        LetterFeature feature = new LetterFeature(bird);
        assertTrue(feature.reload());
        assertEquals("m.books.migrated", feature.itemPaths().getFirst());
        assertTrue(feature.reload());
        verify(manager, times(1)).registerEvents(any(LetterListener.class), same(bird));
        verify(bird, never()).saveResource(anyString(), anyBoolean());

        Files.writeString(root.resolve("BirdMessenger/letters-config.yml"), "items: [broken\n");
        assertFalse(feature.reload());
        assertEquals("m.books.migrated", feature.itemPaths().getFirst());
        verify(bird.getLogger()).severe(contains("Failed to load letters-config.yml"));
    }

    @Test void oldCoreKeepsOwnershipWithoutCopyingConfigOrRegisteringListeners() throws Exception {
        BirdMessenger bird = mock(BirdMessenger.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        Plugin core = mock(Plugin.class);
        when(bird.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.getPlugin("TFMCCore")).thenReturn(core);
        when(core.getResource("plugin.yml")).thenReturn(resource("name: TFMCCore\n"));
        when(bird.getLogger()).thenReturn(mock(Logger.class));
        LetterFeature feature = new LetterFeature(bird);
        assertFalse(feature.reload());
        assertTrue(feature.itemPaths().isEmpty());
        verify(bird, never()).getDataFolder();
        verify(manager, never()).registerEvents(any(), any());
        verify(bird.getLogger()).warning(contains("TFMCCore still owns letters"));
    }

    private static ByteArrayInputStream resource(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}

package net.tfminecraft.birdmessenger.letters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.tfminecraft.birdmessenger.BirdMessenger;

class LetterFeatureTest {
    @TempDir Path root;
    private BirdMessenger bird;
    private PluginManager manager;

    @BeforeEach void setup() {
        bird = mock(BirdMessenger.class);
        Server server = mock(Server.class);
        manager = mock(PluginManager.class);
        when(bird.getDataFolder()).thenReturn(root.toFile());
        when(bird.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(bird.getLogger()).thenReturn(mock(Logger.class));
    }

    @Test void ownConfigReloadKeepsOneListenerAndPreservesLastValidSettings() throws Exception {
        Path config = root.resolve("letters-config.yml");
        Files.writeString(config, "items:\n  letter: m.books.custom\n");
        LetterFeature feature = new LetterFeature(bird);
        assertTrue(feature.reload());
        assertEquals("m.books.custom", feature.itemPaths().getFirst());
        assertTrue(feature.reload());
        verify(manager, times(1)).registerEvents(any(LetterListener.class), same(bird));
        verify(bird, never()).saveResource(anyString(), anyBoolean());

        Files.writeString(config, "items: [broken\n");
        assertFalse(feature.reload());
        assertEquals("m.books.custom", feature.itemPaths().getFirst());
        verify(bird.getLogger()).severe(contains("Failed to load letters-config.yml"));
    }

    @Test void malformedOwnConfigDoesNotRegisterListenersAndCanBeRepaired() throws Exception {
        Path config = root.resolve("letters-config.yml");
        Files.writeString(config, "items: [broken\n");
        LetterFeature feature = new LetterFeature(bird);
        assertFalse(feature.reload());
        assertTrue(feature.itemPaths().isEmpty());
        verify(manager, never()).registerEvents(any(), any());
        verify(bird, never()).saveResource(anyString(), anyBoolean());

        Files.writeString(config, "items:\n  letter: m.books.repaired\n");
        assertTrue(feature.reload());
        assertEquals("m.books.repaired", feature.itemPaths().getFirst());
        verify(manager).registerEvents(any(LetterListener.class), same(bird));
    }

    @Test void newInstallUsesBundledConfig() throws Exception {
        doAnswer(invocation -> {
            try (var resource = getClass().getResourceAsStream("/letters-config.yml")) {
                Files.copy(resource, root.resolve("letters-config.yml"));
            }
            return null;
        }).when(bird).saveResource("letters-config.yml", false);
        LetterFeature feature = new LetterFeature(bird);
        assertTrue(feature.reload());
        verify(bird).saveResource("letters-config.yml", false);
        assertEquals("m.books.letter", feature.itemPaths().getFirst());
        verify(manager).registerEvents(any(LetterListener.class), same(bird));
    }
}

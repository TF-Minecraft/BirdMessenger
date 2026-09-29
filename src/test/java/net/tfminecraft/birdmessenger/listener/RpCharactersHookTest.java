package net.tfminecraft.birdmessenger.listener;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.bukkit.Bukkit;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import net.tfminecraft.birdmessenger.BirdMessenger;

class RpCharactersHookTest {
    private final BirdMessenger plugin = mock(BirdMessenger.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private MockedStatic<Bukkit> bukkit;
    private RpCharactersHook hook;

    @BeforeEach
    void setUp() {
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        hook = new RpCharactersHook(plugin);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void registersWhenRpCharactersEnablesAfterUs() {
        hook.registerIfEnabled();
        verify(plugins, never()).registerEvents(any(), any());

        when(plugins.isPluginEnabled("RPCharacters")).thenReturn(true);
        hook.onPluginEnable(enabled("RPCharacters"));

        verify(plugins).registerEvents(any(CharacterActivatedListener.class), eq(plugin));
    }

    @Test
    void registersOnceWhenRpCharactersIsAlreadyEnabled() {
        when(plugins.isPluginEnabled("RPCharacters")).thenReturn(true);
        hook.registerIfEnabled();
        hook.onPluginEnable(enabled("RPCharacters"));

        verify(plugins, times(1)).registerEvents(any(CharacterActivatedListener.class), eq(plugin));
    }

    @Test
    void ignoresOtherPlugins() {
        when(plugins.isPluginEnabled("RPCharacters")).thenReturn(false);
        hook.onPluginEnable(enabled("ItemsAdder"));

        verify(plugins, never()).registerEvents(any(), any());
    }

    private static PluginEnableEvent enabled(String name) {
        Plugin other = mock(Plugin.class);
        when(other.getName()).thenReturn(name);
        return new PluginEnableEvent(other);
    }
}

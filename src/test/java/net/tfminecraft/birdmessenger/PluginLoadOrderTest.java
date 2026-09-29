package net.tfminecraft.birdmessenger;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PluginLoadOrderTest {

    @Test
    void loadsAfterEveryPluginItUses() throws Exception {
        YamlConfiguration yml;
        try (var in = getClass().getClassLoader().getResourceAsStream("plugin.yml")) {
            assertNotNull(in);
            yml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        // A loadbefore here closes RPCharacters -> TLibs -> ItemsAdder -> BirdMessenger -> RPCharacters,
        // and RPCharacters must enable first for the character-activated mail listener to register.
        assertTrue(yml.getStringList("loadbefore").isEmpty());
        assertEquals(List.of("TLibs"), yml.getStringList("depend"));
        assertTrue(yml.getStringList("softdepend").containsAll(List.of("RPCharacters", "ItemsAdder")));
    }
}

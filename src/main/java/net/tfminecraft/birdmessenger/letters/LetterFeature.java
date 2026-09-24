package net.tfminecraft.birdmessenger.letters;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import net.tfminecraft.birdmessenger.BirdMessenger;

/** Owns signing/opening letters; the legacy Core tag and source config remain intact. */
public final class LetterFeature {
    private final BirdMessenger plugin;
    private LetterListener listener;

    public LetterFeature(BirdMessenger plugin) {
        this.plugin = plugin;
    }

    public boolean reload() {
        Plugin core = plugin.getServer().getPluginManager().getPlugin("TFMCCore");
        try {
            if (!canOwnLetters(core)) {
                plugin.getLogger().warning("TFMCCore still owns letters. Install the matching Core migration before enabling BirdMessenger letters.");
                return false;
            }
            Path destination = plugin.getDataFolder().toPath().resolve("letters-config.yml");
            Path legacy = core != null
                    ? core.getDataFolder().toPath().resolve("letters-config.yml")
                    : plugin.getDataFolder().toPath().resolveSibling("TFMCCore").resolve("letters-config.yml");
            migrateConfig(legacy, destination);
            if (Files.notExists(destination)) {
                plugin.saveResource("letters-config.yml", false);
            }
            if (!LetterConfigLoader.load(destination.toFile(), plugin.getLogger())) {
                return false;
            }
            if (listener == null) {
                listener = new LetterListener(plugin);
                plugin.getServer().getPluginManager().registerEvents(listener, plugin);
            }
            return true;
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().severe("Letters could not start/reload; configuration was preserved: " + ex.getMessage());
            return false;
        }
    }

    // Inspect the resource, since PluginDescriptionFile does not retain arbitrary marker fields.
    static boolean canOwnLetters(Plugin core) throws IOException, InvalidConfigurationException {
        if (core == null) return true;
        try (InputStream stream = core.getResource("plugin.yml")) {
            if (stream == null) return false;
            YamlConfiguration description = new YamlConfiguration();
            description.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return "BirdMessenger".equals(description.getString("feature-owners.letters"));
        }
    }

    static void migrateConfig(Path legacy, Path destination) throws IOException, InvalidConfigurationException {
        if (!Files.notExists(destination)) {
            if (!Files.isRegularFile(destination)) throw new IOException("Not a readable config file: " + destination);
            return;
        }
        if (Files.notExists(legacy)) return;
        if (!Files.isRegularFile(legacy)) throw new IOException("Not a readable legacy config file: " + legacy);
        // Validate before copying; a broken source must never become a fresh default config.
        new YamlConfiguration().load(legacy.toFile());
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".letters-migration-", ".tmp");
        try {
            Files.copy(legacy, temporary, StandardCopyOption.REPLACE_EXISTING);
            new YamlConfiguration().load(temporary.toFile());
            // Publish only the complete, validated copy; never overwrite a destination created meanwhile.
            Files.move(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public List<String> itemPaths() {
        if (listener == null) return List.of();
        return List.of(LetterConfig.letterPath, LetterConfig.writtenLetterPath, LetterConfig.writtenLetterOpenPath);
    }

    public void close() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
    }
}

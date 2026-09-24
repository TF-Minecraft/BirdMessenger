package net.tfminecraft.birdmessenger.letters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.bukkit.event.HandlerList;

import net.tfminecraft.birdmessenger.BirdMessenger;

/** Owns letter configuration, signing and opening. */
public final class LetterFeature {
    private final BirdMessenger plugin;
    private LetterListener listener;

    public LetterFeature(BirdMessenger plugin) {
        this.plugin = plugin;
    }

    public boolean reload() {
        Path config = plugin.getDataFolder().toPath().resolve("letters-config.yml");
        if (Files.notExists(config)) {
            plugin.saveResource("letters-config.yml", false);
        }
        if (!LetterConfigLoader.load(config.toFile(), plugin.getLogger())) {
            return false;
        }
        if (listener == null) {
            listener = new LetterListener(plugin);
            plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        }
        return true;
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

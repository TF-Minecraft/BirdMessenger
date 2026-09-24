package net.tfminecraft.birdmessenger.letters;

import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public final class LetterConfigLoader {

    private LetterConfigLoader() {}

    public static boolean load(File file, Logger logger) {
        FileConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            logger.severe("[BirdMessenger] Failed to load letters-config.yml: " + ex.getMessage());
            return false;
        }
        LetterConfig.letterPath = config.getString("items.letter", LetterConfig.letterPath);
        LetterConfig.writtenLetterPath = config.getString("items.written-letter", LetterConfig.writtenLetterPath);
        LetterConfig.writtenLetterOpenPath = config.getString("items.written-letter-open", LetterConfig.writtenLetterOpenPath);
        LetterConfig.hideAuthor = config.getBoolean("settings.hide-author", LetterConfig.hideAuthor);
        LetterConfig.useTitleAsName = config.getBoolean("settings.use-title-as-name", LetterConfig.useTitleAsName);
        LetterConfig.signedMessage = config.getString("messages.letter-signed", LetterConfig.signedMessage);
        LetterConfig.openedMessage = config.getString("messages.letter-opened", LetterConfig.openedMessage);
        LetterConfig.creationFailedMessage = config.getString("messages.letter-creation-failed", LetterConfig.creationFailedMessage);
        LetterConfig.openFailedMessage = config.getString("messages.letter-open-failed", LetterConfig.openFailedMessage);
        return true;
    }
}

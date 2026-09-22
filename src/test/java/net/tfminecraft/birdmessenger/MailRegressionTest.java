package net.tfminecraft.birdmessenger;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.lone.itemsadder.api.Events.FurnitureBreakEvent;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;
import net.tfminecraft.tlibs.objects.api.subapi.ItemChecker;
import net.tfminecraft.birdmessenger.listener.CoopFurnitureListener;
import net.tfminecraft.birdmessenger.util.LetterItems;

class MailRegressionTest {
    private BirdMessenger plugin(YamlConfiguration yaml) {
        BirdMessenger plugin = mock(BirdMessenger.class);
        when(plugin.getConfig()).thenReturn(yaml);
        when(plugin.config()).thenReturn(new BirdConfig(plugin));
        return plugin;
    }

    @Test void existingDefaultConfigAcceptsAllLetterStates() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("letter", "ia.iasurvival:letter");
        BirdConfig config = plugin(yaml).config();
        ItemAPI api = mock(ItemAPI.class);
        ItemChecker checker = mock(ItemChecker.class);
        when(api.getChecker()).thenReturn(checker);
        try (var tlibs = mockStatic(TLibs.class)) {
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            for (String id : List.of("letter", "letter_written_letter", "letter_open_letter")) {
                ItemStack item = mock(ItemStack.class);
                when(item.getType()).thenReturn(mock(Material.class));
                when(checker.checkItemWithPath(item, "ia.iasurvival:" + id)).thenReturn(true);
                assertTrue(LetterItems.isLetter(config, item), id);
            }
            ItemStack unrelated = mock(ItemStack.class);
            when(unrelated.getType()).thenReturn(mock(Material.class));
            assertFalse(LetterItems.isLetter(config, unrelated));
            assertFalse(LetterItems.isLetter(config, null));
        }
    }

    @Test void customLegacyLetterDoesNotAcceptDefaultLetters() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("letter", "ia.custom:letter");
        assertEquals(List.of("ia.custom:letter"), plugin(yaml).config().letterPaths());
    }

    @Test void explicitListOverridesLegacySettingIncludingEmptyList() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("letters", List.of("ia.custom:sealed", "ia.custom:open"));
        BirdConfig config = plugin(yaml).config();
        assertEquals(List.of("ia.custom:sealed", "ia.custom:open"), config.letterPaths());
        yaml.set("letters", List.of());
        assertTrue(config.letterPaths().isEmpty());
    }

    @Test void leftClickIsCancelledButSneakBreakingAndOtherBlocksAreNot() {
        var plugin = plugin(new YamlConfiguration());
        var listener = new net.tfminecraft.birdmessenger.listener.CoopListener(plugin);
        var block = mock(org.bukkit.block.Block.class);
        var player = mock(Player.class);
        var event = mock(org.bukkit.event.player.PlayerInteractEvent.class);
        when(event.getAction()).thenReturn(org.bukkit.event.block.Action.LEFT_CLICK_BLOCK);
        when(event.getClickedBlock()).thenReturn(block);
        when(event.getPlayer()).thenReturn(player);
        var api = mock(net.tfminecraft.tlibs.objects.api.BlockAPI.class);
        var checker = mock(net.tfminecraft.tlibs.objects.api.subapi.BlockChecker.class);
        when(api.getChecker()).thenReturn(checker);
        when(checker.checkBlock(block, "iaf(tfmc:bird_coop)")).thenReturn(true);
        try (var tlibs = mockStatic(TLibs.class)) {
            tlibs.when(TLibs::getBlockAPI).thenReturn(api);
            listener.onRightClick(event);
            verify(event).setCancelled(true);
            verify(event).setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            clearInvocations(event);
            when(player.isSneaking()).thenReturn(true);
            listener.onRightClick(event);
            verify(event, never()).setCancelled(anyBoolean());
            when(player.isSneaking()).thenReturn(false);
            when(checker.checkBlock(block, "iaf(tfmc:bird_coop)")).thenReturn(false);
            listener.onRightClick(event);
            verify(event, never()).setCancelled(anyBoolean());
            verify(player, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
        }
    }

    @Test void mailboxBreakRequiresSneakingAndOtherFurnitureIsUnaffected() {
        CoopFurnitureListener listener = new CoopFurnitureListener(plugin(new YamlConfiguration()));
        Player player = mock(Player.class);
        FurnitureBreakEvent event = mock(FurnitureBreakEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getNamespacedID()).thenReturn("tfmc:bird_coop");
        listener.onBreak(event);
        verify(event).setCancelled(true);
        clearInvocations(event);
        when(player.isSneaking()).thenReturn(true);
        listener.onBreak(event);
        verify(event, never()).setCancelled(anyBoolean());
        when(player.isSneaking()).thenReturn(false);
        when(event.getNamespacedID()).thenReturn("tfmc:chair");
        listener.onBreak(event);
        verify(event, never()).setCancelled(anyBoolean());
    }
}

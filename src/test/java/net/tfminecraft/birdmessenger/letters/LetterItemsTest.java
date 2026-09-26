package net.tfminecraft.birdmessenger.letters;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;

class LetterItemsTest {
    private LetterItems items;
    private static final NamespacedKey SEALED_KEY = NamespacedKey.fromString("tfmccore:sealed_letter");

    @BeforeEach void setup() {
        BirdMessenger plugin = mock(BirdMessenger.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        items = new LetterItems(plugin);
        LetterConfig.hideAuthor = false;
        LetterConfig.useTitleAsName = true;
    }

    @Test void recognizesPersistentSealsAndRejectsUntaggedBooks() {
        ItemStack book = mock(ItemStack.class);
        BookMeta meta = mock(BookMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(book.getType()).thenReturn(Material.WRITTEN_BOOK);
        when(book.hasItemMeta()).thenReturn(true);
        when(book.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(SEALED_KEY, PersistentDataType.BYTE)).thenReturn(true);
        assertTrue(items.isSealedLetter(book));
        when(pdc.has(SEALED_KEY, PersistentDataType.BYTE)).thenReturn(false);
        assertFalse(items.isSealedLetter(book));
    }

    @Test void sealingPreservesPagesTitleAuthorAndWritesStableKey() {
        ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        ItemStack template = mock(ItemStack.class);
        ItemStack sealed = mock(ItemStack.class);
        BookMeta target = mock(BookMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(api.getCreator().getItemFromPath(LetterConfig.writtenLetterPath)).thenReturn(template);
        when(template.clone()).thenReturn(sealed);
        when(sealed.getItemMeta()).thenReturn(target);
        when(target.getPersistentDataContainer()).thenReturn(pdc);
        BookMeta source = source();
        Player signer = mock(Player.class);
        when(signer.getName()).thenReturn("Alice");
        try (var tlibs = mockStatic(TLibs.class)) {
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            assertSame(sealed, items.createSealedLetter(source, signer));
            verify(target).setPages(List.of("Page one", "Page two"));
            verify(target).setTitle("Treaty");
            verify(target).setDisplayName("Treaty");
            verify(target).setAuthor("Alice");
            verify(pdc).set(SEALED_KEY, PersistentDataType.BYTE, (byte) 1);
            verify(template, never()).setItemMeta(any());
        }
    }

    @Test void openingPreservesContentAndAuthorWithoutAddingAnotherSeal() {
        ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        ItemStack template = mock(ItemStack.class);
        ItemStack opened = mock(ItemStack.class);
        BookMeta target = mock(BookMeta.class);
        when(api.getCreator().getItemFromPath(LetterConfig.writtenLetterOpenPath)).thenReturn(template);
        when(template.clone()).thenReturn(opened);
        when(opened.getItemMeta()).thenReturn(target);
        BookMeta source = source();
        when(source.hasAuthor()).thenReturn(true);
        when(source.getAuthor()).thenReturn("Alice");
        try (var tlibs = mockStatic(TLibs.class)) {
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            assertSame(opened, items.createOpenedLetter(source));
            verify(target).setPages(List.of("Page one", "Page two"));
            verify(target).setAuthor("Alice");
            verify(target, never()).getPersistentDataContainer();
            LetterConfig.hideAuthor = true;
            items.createOpenedLetter(source);
            verify(target).setAuthor(null);
        }
    }

    @SuppressWarnings("deprecation")
    @Test void editingClonesTheOriginalItemAndChangesOnlyRichPagesWithoutATemplate() {
        ItemStack previous = mock(ItemStack.class);
        ItemStack edited = mock(ItemStack.class);
        BookMeta target = mock(BookMeta.class);
        BookMeta source = mock(BookMeta.class);
        BookMeta.Spigot targetPages = mock(BookMeta.Spigot.class);
        BookMeta.Spigot sourcePages = mock(BookMeta.Spigot.class);
        List<BaseComponent[]> pages = List.<BaseComponent[]>of(new BaseComponent[] {
                new TextComponent("Edited letter with formatting")});
        when(previous.clone()).thenReturn(edited);
        when(edited.getItemMeta()).thenReturn(target);
        when(target.spigot()).thenReturn(targetPages);
        when(source.spigot()).thenReturn(sourcePages);
        when(sourcePages.getPages()).thenReturn(pages);
        try (var tlibs = mockStatic(TLibs.class)) {
            assertSame(edited, items.createEditedLetter(source, previous));
            verify(previous).clone();
            verifyNoMoreInteractions(previous);
            verify(targetPages).setPages(pages);
            verify(target).spigot();
            verifyNoMoreInteractions(target);
            verify(edited).setItemMeta(target);
            verify(edited, never()).setAmount(anyInt());
            tlibs.verifyNoInteractions();
        }
    }

    @Test void editableLettersIncludeMailConfigurationWithoutBroadeningSigning() {
        BirdMessenger plugin = mock(BirdMessenger.class);
        BirdConfig config = mock(BirdConfig.class);
        when(plugin.config()).thenReturn(config);
        ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        ItemStack letter = mock(ItemStack.class);
        when(letter.getType()).thenReturn(Material.WRITABLE_BOOK);
        try (var tlibs = mockStatic(TLibs.class);
             var mail = mockStatic(net.tfminecraft.birdmessenger.util.LetterItems.class)) {
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            mail.when(() -> net.tfminecraft.birdmessenger.util.LetterItems.isLetter(config, letter))
                    .thenReturn(true);
            LetterItems configured = new LetterItems(plugin);
            assertTrue(configured.isEditableLetter(letter));
            assertFalse(configured.isLetter(letter));
            mail.when(() -> net.tfminecraft.birdmessenger.util.LetterItems.isLetter(config, letter))
                    .thenReturn(false);
            assertFalse(configured.isEditableLetter(letter));
            when(letter.getType()).thenReturn(Material.WRITTEN_BOOK);
            assertFalse(configured.isEditableLetter(letter));
            assertFalse(configured.isEditableLetter(null));
        }
    }

    private BookMeta source() {
        BookMeta source = mock(BookMeta.class);
        when(source.getPages()).thenReturn(List.of("Page one", "Page two"));
        when(source.hasTitle()).thenReturn(true);
        when(source.getTitle()).thenReturn("Treaty");
        return source;
    }
}

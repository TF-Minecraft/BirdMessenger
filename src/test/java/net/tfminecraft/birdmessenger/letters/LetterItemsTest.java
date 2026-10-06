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
        when(template.getType()).thenReturn(Material.WRITTEN_BOOK);
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
        when(template.getType()).thenReturn(Material.WRITTEN_BOOK);
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
    @Test void editingClonesTheOriginalItemAndPreservesExactPageTextWithoutATemplate() {
        ItemStack previous = mock(ItemStack.class);
        ItemStack edited = mock(ItemStack.class);
        BookMeta target = mock(BookMeta.class);
        BookMeta source = mock(BookMeta.class);
        List<String> pages = List.of("Letter with trailing reset §r", "§aGreen\nSecond line", "");
        when(previous.clone()).thenReturn(edited);
        when(edited.getItemMeta()).thenReturn(target);
        when(source.getPages()).thenReturn(pages);
        try (var tlibs = mockStatic(TLibs.class)) {
            assertSame(edited, items.createEditedLetter(source, previous));
            verify(previous).clone();
            verifyNoMoreInteractions(previous);
            verify(target).setPages(pages);
            verify(source, never()).spigot();
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

    @Test void missingBookMetadataCannotReplaceTheOriginalLetter() {
        ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        ItemStack template = mock(ItemStack.class);
        ItemStack copy = mock(ItemStack.class);
        when(template.getType()).thenReturn(Material.WRITTEN_BOOK);
        when(template.clone()).thenReturn(copy);
        when(api.getCreator().getItemFromPath(anyString())).thenReturn(template);
        try (var tlibs = mockStatic(TLibs.class)) {
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            assertNull(items.createSealedLetter(source(), mock(Player.class)));
            assertNull(items.createOpenedLetter(source()));
            verify(copy, never()).setItemMeta(any());
        }
    }

    @Test void nonBookTemplatesCannotReplaceTheOriginalLetter() {
        ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        ItemStack template = mock(ItemStack.class);
        when(template.getType()).thenReturn(Material.AIR);
        when(template.clone()).thenReturn(template);
        when(api.getCreator().getItemFromPath(anyString())).thenReturn(template);
        try (var tlibs = mockStatic(TLibs.class)) {
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            assertNull(items.createSealedLetter(source(), mock(Player.class)));
            assertNull(items.createOpenedLetter(source()));
        }
    }

    @Test void providerFailuresAndMissingTemplatesDoNotProduceReplacementItems() {
        ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        ItemStack writable = mock(ItemStack.class);
        when(writable.getType()).thenReturn(Material.WRITABLE_BOOK);
        when(api.getChecker().checkItemWithPath(writable, LetterConfig.letterPath))
                .thenThrow(new IllegalStateException("Provider unavailable"));
        when(api.getCreator().getItemFromPath(anyString())).thenReturn(null);
        try (var tlibs = mockStatic(TLibs.class)) {
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            assertFalse(items.isLetter(writable));
            assertNull(items.createSealedLetter(source(), mock(Player.class)));
            assertNull(items.createOpenedLetter(source()));
            when(api.getCreator().getItemFromPath(anyString())).thenThrow(new IllegalStateException("Provider unavailable"));
            assertNull(items.createSealedLetter(source(), mock(Player.class)));
            assertNull(items.createOpenedLetter(source()));
            assertNull(new LetterItems(null).createOpenedLetter(source()));
        }
        ItemStack previous = mock(ItemStack.class);
        when(previous.clone()).thenThrow(new IllegalStateException("Item snapshot unavailable"));
        assertNull(items.createEditedLetter(source(), previous));
        verify(previous, never()).setItemMeta(any());
    }

    @Test void privateUnsignedSealsHideTheAuthorAndPreserveTemplateNameWithoutATitle() {
        ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        ItemStack template = mock(ItemStack.class);
        ItemStack copy = mock(ItemStack.class);
        BookMeta target = mock(BookMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(target.getPersistentDataContainer()).thenReturn(data);
        when(template.getType()).thenReturn(Material.WRITTEN_BOOK);
        when(template.clone()).thenReturn(copy);
        when(copy.getItemMeta()).thenReturn(target);
        when(api.getCreator().getItemFromPath(LetterConfig.writtenLetterPath)).thenReturn(template);
        BookMeta source = mock(BookMeta.class);
        when(source.getPages()).thenReturn(List.of("Private message"));
        boolean previousHide = LetterConfig.hideAuthor;
        boolean previousTitle = LetterConfig.useTitleAsName;
        try (var tlibs = mockStatic(TLibs.class)) {
            LetterConfig.hideAuthor = true;
            LetterConfig.useTitleAsName = false;
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            assertSame(copy, items.createSealedLetter(source, mock(Player.class)));
            verify(target).setAuthor(null);
            verify(target).setPages(List.of("Private message"));
            verify(target, never()).setTitle(anyString());
            verify(target, never()).setDisplayName(anyString());
            when(source.hasTitle()).thenReturn(true);
            when(source.getTitle()).thenReturn("Private title");
            assertSame(copy, items.createSealedLetter(source, mock(Player.class)));
            verify(target).setTitle("Private title");
            verify(target, never()).setDisplayName(anyString());
        } finally {
            LetterConfig.hideAuthor = previousHide;
            LetterConfig.useTitleAsName = previousTitle;
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

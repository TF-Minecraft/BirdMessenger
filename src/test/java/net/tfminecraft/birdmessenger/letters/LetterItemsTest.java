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
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;

class LetterItemsTest {
    private LetterItems items;
    private static final NamespacedKey LEGACY_KEY = NamespacedKey.fromString("tfmccore:sealed_letter");

    @BeforeEach void setup() {
        BirdMessenger plugin = mock(BirdMessenger.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        items = new LetterItems(plugin);
        LetterConfig.hideAuthor = false;
        LetterConfig.useTitleAsName = true;
    }

    @Test void recognizesExistingCoreSealsAndRejectsUntaggedBooks() {
        ItemStack book = mock(ItemStack.class);
        BookMeta meta = mock(BookMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(book.getType()).thenReturn(Material.WRITTEN_BOOK);
        when(book.hasItemMeta()).thenReturn(true);
        when(book.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(LEGACY_KEY, PersistentDataType.BYTE)).thenReturn(true);
        assertTrue(items.isSealedLetter(book));
        when(pdc.has(LEGACY_KEY, PersistentDataType.BYTE)).thenReturn(false);
        assertFalse(items.isSealedLetter(book));
    }

    @Test void sealingPreservesPagesTitleAuthorAndWritesLegacyNamespace() {
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
            verify(pdc).set(LEGACY_KEY, PersistentDataType.BYTE, (byte) 1);
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

    @Test void editingPreservesExistingNameLorePdcAndStackSize() {
        ItemAPI api = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        ItemStack template = mock(ItemStack.class);
        ItemStack edited = mock(ItemStack.class);
        ItemStack previous = mock(ItemStack.class);
        BookMeta target = mock(BookMeta.class);
        BookMeta old = mock(BookMeta.class);
        PersistentDataContainer oldPdc = mock(PersistentDataContainer.class);
        PersistentDataContainer newPdc = mock(PersistentDataContainer.class);
        when(api.getCreator().getItemFromPath(LetterConfig.letterPath)).thenReturn(template);
        when(template.clone()).thenReturn(edited);
        when(edited.getItemMeta()).thenReturn(target);
        when(previous.getItemMeta()).thenReturn(old);
        when(previous.getAmount()).thenReturn(2);
        when(old.hasDisplayName()).thenReturn(true);
        when(old.getDisplayName()).thenReturn("Custom name");
        when(old.hasLore()).thenReturn(true);
        when(old.getLore()).thenReturn(List.of("Custom lore"));
        when(old.getPersistentDataContainer()).thenReturn(oldPdc);
        when(target.getPersistentDataContainer()).thenReturn(newPdc);
        try (var tlibs = mockStatic(TLibs.class)) {
            tlibs.when(TLibs::getItemAPI).thenReturn(api);
            assertSame(edited, items.createEditedLetter(source(), previous));
            verify(edited).setAmount(2);
            verify(target).setDisplayName("Custom name");
            verify(target).setLore(List.of("Custom lore"));
            verify(oldPdc).copyTo(newPdc, true);
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

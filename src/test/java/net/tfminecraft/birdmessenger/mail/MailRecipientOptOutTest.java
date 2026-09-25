package net.tfminecraft.birdmessenger.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.mail.CharacterMailTarget;

class MailRecipientOptOutTest {
    @Test void staleSelectionReturnsLetterAndNeverQueuesDelivery() {
        BirdMessenger plugin = mock(BirdMessenger.class);
        BirdConfig config = mock(BirdConfig.class);
        when(plugin.config()).thenReturn(config);
        when(config.msgRecipientUnavailable()).thenReturn("Recipient unavailable");
        MailStore store = mock(MailStore.class);
        MailService service = new MailService(plugin, store);
        Player sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(UUID.randomUUID());
        ItemStack letter = mock(ItemStack.class);
        SelectedTarget target = new SelectedTarget(UUID.randomUUID(), "recipient", "Recipient", "Recipient", null, null);
        try (var rpc = mockStatic(RPCharacters.class); var give = mockStatic(ItemGive.class);
                var flight = mockStatic(FlightTime.class)) {
            rpc.when(RPCharacters::listMailTargets).thenReturn(List.of());
            assertFalse(service.trySend(sender, letter, target));
            give.verify(() -> ItemGive.giveOrDrop(sender, letter));
            verify(sender).sendMessage("Recipient unavailable");
            verifyNoInteractions(store);
            flight.verifyNoInteractions();
        }
    }

    @Test void listedRecipientStillReachesNormalSendValidation() {
        BirdMessenger plugin = mock(BirdMessenger.class);
        BirdConfig config = mock(BirdConfig.class);
        when(plugin.config()).thenReturn(config);
        when(config.msgDifferentWorld()).thenReturn("Different world");
        MailService service = new MailService(plugin, mock(MailStore.class));
        Player sender = mock(Player.class);
        when(sender.getUniqueId()).thenReturn(UUID.randomUUID());
        ItemStack letter = mock(ItemStack.class);
        UUID owner = UUID.randomUUID();
        SelectedTarget target = new SelectedTarget(owner, "recipient", "Recipient", "Recipient", null, null);
        CharacterMailTarget recipient = mock(CharacterMailTarget.class);
        when(recipient.getOwnerUuid()).thenReturn(owner);
        when(recipient.getCharacterId()).thenReturn("recipient");
        try (var rpc = mockStatic(RPCharacters.class); var give = mockStatic(ItemGive.class);
                var flight = mockStatic(FlightTime.class)) {
            rpc.when(RPCharacters::listMailTargets).thenReturn(List.of(recipient));
            flight.when(() -> FlightTime.computeFlightSecondsAtSend(config, sender, target)).thenReturn(null);
            assertFalse(service.trySend(sender, letter, target));
            flight.verify(() -> FlightTime.computeFlightSecondsAtSend(config, sender, target));
            verify(sender).sendMessage("Different world");
        }
    }
}

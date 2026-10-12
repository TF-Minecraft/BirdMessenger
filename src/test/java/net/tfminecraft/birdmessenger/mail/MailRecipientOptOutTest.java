package net.tfminecraft.birdmessenger.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.BirdMessenger;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.birdmessenger.util.ItemGive;
import net.tfminecraft.rpcharacters.RPCharacters;
import net.tfminecraft.rpcharacters.mail.CharacterMailTarget;
import net.tfminecraft.tlibs.character.CharacterRef;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;

class MailRecipientOptOutTest {
    @Test void staleSelectionReturnsLetterAndNeverQueuesDelivery() {
        try (Fixture f = new Fixture()) {
            f.rpc.when(RPCharacters::listMailTargets).thenReturn(List.of());
            assertFalse(f.service.trySend(f.sender, f.origin, f.letter, f.target));
            f.give.verify(() -> ItemGive.giveOrDrop(f.sender, f.letter));
            verify(f.sender).sendMessage("Recipient unavailable");
            verifyNoInteractions(f.store);
            f.flight.verifyNoInteractions();
        }
    }

    @Test void listedRecipientStillReachesNormalSendValidation() {
        try (Fixture f = new Fixture()) {
            f.rpc.when(RPCharacters::listMailTargets).thenReturn(List.of(f.recipient));
            f.flight.when(() -> FlightTime.computeFlightSecondsAtSend(f.config, f.sender, f.target))
                    .thenReturn(null);
            assertFalse(f.service.trySend(f.sender, f.origin, f.letter, f.target));
            f.flight.verify(() -> FlightTime.computeFlightSecondsAtSend(f.config, f.sender, f.target));
            f.give.verify(() -> ItemGive.giveOrDrop(f.sender, f.letter));
            verify(f.sender).sendMessage("Different world");
            verifyNoInteractions(f.store);
        }
    }

    @Test void aDifferentOwnersMatchingCharacterIdDoesNotBypassRecipientOptOut() {
        try (Fixture f = new Fixture()) {
            when(f.recipient.getOwnerUuid()).thenReturn(UUID.randomUUID());
            f.rpc.when(RPCharacters::listMailTargets).thenReturn(List.of(f.recipient));
            assertFalse(f.service.trySend(f.sender, f.origin, f.letter, f.target));
            f.give.verify(() -> ItemGive.giveOrDrop(f.sender, f.letter));
            verify(f.sender).sendMessage("Recipient unavailable");
            verifyNoInteractions(f.store);
            f.flight.verifyNoInteractions();
        }
    }

    @Test void refusalCallbacksAfterACharacterSwitchRefundOnlyTheCapturedCharacter() {
        for (String refusal : List.of("recipient-opt-out", "different-world")) {
            try (Fixture f = new Fixture()) {
                if (refusal.equals("recipient-opt-out")) {
                    f.rpc.when(RPCharacters::listMailTargets).thenAnswer(call -> {
                        f.switchCharacter();
                        return List.of();
                    });
                } else {
                    f.rpc.when(RPCharacters::listMailTargets).thenReturn(List.of(f.recipient));
                    f.flight.when(() -> FlightTime.computeFlightSecondsAtSend(f.config, f.sender, f.target))
                            .thenAnswer(call -> {
                                f.switchCharacter();
                                return null;
                            });
                }

                assertFalse(f.service.trySend(f.sender, f.origin, f.letter, f.target), refusal);
                ArgumentCaptor<MailStore.PendingLetter> refund =
                        ArgumentCaptor.forClass(MailStore.PendingLetter.class);
                verify(f.store).addPending(eq(f.origin.ref().characterId().toString()), refund.capture());
                assertEquals(f.origin.ref().ownerId(), refund.getValue().ownerUuid);
                assertSame(f.letter, refund.getValue().item);
                assertNull(refund.getValue().senderUuid);
                f.give.verifyNoInteractions();
                verify(f.store, never()).putInFlight(any());
                if (refusal.equals("recipient-opt-out")) {
                    f.flight.verifyNoInteractions();
                }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final BirdMessenger plugin = mock(BirdMessenger.class);
        final BirdConfig config = mock(BirdConfig.class);
        final MailStore store = mock(MailStore.class);
        final UUID senderId = UUID.randomUUID();
        final UUID ownerId = UUID.randomUUID();
        final Player sender = mock(Player.class);
        final ItemStack letter = mock(ItemStack.class);
        final CharacterSession origin =
                new CharacterSession(new CharacterRef(senderId, UUID.randomUUID()), 1L);
        CharacterSession current = origin;
        final SelectedTarget target = new SelectedTarget(
                ownerId, UUID.randomUUID().toString(), "Recipient", "Recipient", null, null);
        final CharacterMailTarget recipient = mock(CharacterMailTarget.class);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final MockedStatic<Characters> identities = mockStatic(Characters.class);
        final MockedStatic<RPCharacters> rpc = mockStatic(RPCharacters.class);
        final MockedStatic<ItemGive> give = mockStatic(ItemGive.class);
        final MockedStatic<FlightTime> flight = mockStatic(FlightTime.class);
        final MailService service;

        Fixture() {
            when(plugin.config()).thenReturn(config);
            when(config.msgRecipientUnavailable()).thenReturn("Recipient unavailable");
            when(config.msgDifferentWorld()).thenReturn("Different world");
            when(sender.getUniqueId()).thenReturn(senderId);
            when(sender.isOnline()).thenReturn(true);
            when(recipient.getOwnerUuid()).thenReturn(ownerId);
            when(recipient.getCharacterId()).thenReturn(target.getCharacterId());
            bukkit.when(() -> Bukkit.getPlayer(senderId)).thenReturn(sender);
            identities.when(() -> Characters.isCurrent(any(CharacterSession.class)))
                    .thenAnswer(call -> current.equals(call.getArgument(0)));
            service = new MailService(plugin, store);
        }

        void switchCharacter() {
            current = new CharacterSession(new CharacterRef(senderId, UUID.randomUUID()), 2L);
        }

        @Override public void close() {
            flight.close();
            give.close();
            rpc.close();
            identities.close();
            bukkit.close();
        }
    }
}

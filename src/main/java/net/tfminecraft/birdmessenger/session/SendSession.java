package net.tfminecraft.birdmessenger.session;

import java.util.UUID;

import org.bukkit.inventory.ItemStack;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import net.tfminecraft.tlibs.character.CharacterSession;
import net.tfminecraft.tlibs.character.Characters;

/**
 * In-progress send: letter is held here until confirm, cancel, or quit.
 */
public final class SendSession {

	private final Player player;
	private final CharacterSession characterSession;
	private Inventory letterInventory;
	private ItemStack letter;
	private SelectedTarget selected;
	private boolean confirmed;
	private int pickerPage;

	public SendSession(Player player, CharacterSession characterSession) {
		this.player = player;
		this.characterSession = characterSession;
	}

	public UUID getPlayerId() {
		return characterSession.ref().ownerId();
	}

	public Player player() { return player; }
	public CharacterSession characterSession() { return characterSession; }
	public Inventory letterInventory() { return letterInventory; }
	public void setLetterInventory(Inventory inventory) { letterInventory = inventory; }

	public boolean isCurrent(Player owner) {
		return isConnected(owner) && Characters.isCurrent(characterSession);
	}

	public boolean isConnected(Player owner) {
		return player == owner && owner.isOnline() && Bukkit.getPlayer(getPlayerId()) == owner;
	}

	public ItemStack getLetter() {
		return letter;
	}

	public void setLetter(ItemStack letter) {
		this.letter = letter;
	}

	public SelectedTarget getSelected() {
		return selected;
	}

	public void setSelected(SelectedTarget selected) {
		this.selected = selected;
	}

	public boolean isConfirmed() {
		return confirmed;
	}

	public void setConfirmed(boolean confirmed) {
		this.confirmed = confirmed;
	}

	public int getPickerPage() {
		return pickerPage;
	}

	public void setPickerPage(int pickerPage) {
		this.pickerPage = Math.max(0, pickerPage);
	}
}

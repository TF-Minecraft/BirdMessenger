package net.tfminecraft.birdmessenger.session;

import java.util.UUID;

import org.bukkit.inventory.ItemStack;

/**
 * In-progress send: letter is held here until confirm, cancel, or quit.
 */
public final class SendSession {

	private final UUID playerId;
	private ItemStack letter;
	private SelectedTarget selected;
	private boolean confirmed;
	private int pickerPage;

	public SendSession(UUID playerId) {
		this.playerId = playerId;
	}

	public UUID getPlayerId() {
		return playerId;
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

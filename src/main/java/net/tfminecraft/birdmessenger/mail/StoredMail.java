package net.tfminecraft.birdmessenger.mail;

import java.util.UUID;

import org.bukkit.inventory.ItemStack;

public final class StoredMail {

	private final UUID id;
	private final UUID senderUuid;
	private final UUID ownerUuid;
	private final String characterId;
	private final String addresseeDisplayTab;
	private final ItemStack item;
	private final long deliveryTime;
	// The character that wrote the letter; null for letters sent before characters were recorded.
	private final String senderCharacterId;

	public StoredMail(
			UUID id,
			UUID senderUuid,
			UUID ownerUuid,
			String characterId,
			String addresseeDisplayTab,
			ItemStack item,
			long deliveryTime) {
		this(id, senderUuid, ownerUuid, characterId, addresseeDisplayTab, item, deliveryTime, null);
	}

	public StoredMail(
			UUID id,
			UUID senderUuid,
			UUID ownerUuid,
			String characterId,
			String addresseeDisplayTab,
			ItemStack item,
			long deliveryTime,
			String senderCharacterId) {
		this.id = id;
		this.senderUuid = senderUuid;
		this.ownerUuid = ownerUuid;
		this.characterId = characterId;
		this.addresseeDisplayTab = addresseeDisplayTab != null ? addresseeDisplayTab : "";
		this.item = item;
		this.deliveryTime = deliveryTime;
		this.senderCharacterId = senderCharacterId;
	}

	public UUID getId() {
		return id;
	}

	public UUID getSenderUuid() {
		return senderUuid;
	}

	public UUID getOwnerUuid() {
		return ownerUuid;
	}

	public String getCharacterId() {
		return characterId;
	}

	public String getAddresseeDisplayTab() {
		return addresseeDisplayTab;
	}

	public ItemStack getItem() {
		return item;
	}

	public long getDeliveryTime() {
		return deliveryTime;
	}

	public String getSenderCharacterId() {
		return senderCharacterId;
	}
}

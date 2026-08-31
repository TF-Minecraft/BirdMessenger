package net.tfminecraft.birdmessenger.session;

import java.util.UUID;

public final class SelectedTarget {

	private final UUID ownerUuid;
	private final String characterId;
	private final String displayTab;
	private final String displayPlain;
	private final String baseTextureValue;
	private final String baseTextureSignature;

	public SelectedTarget(
			UUID ownerUuid,
			String characterId,
			String displayTab,
			String displayPlain,
			String baseTextureValue,
			String baseTextureSignature) {
		this.ownerUuid = ownerUuid;
		this.characterId = characterId;
		this.displayTab = displayTab != null ? displayTab : "";
		this.displayPlain = displayPlain != null ? displayPlain : "";
		this.baseTextureValue = baseTextureValue;
		this.baseTextureSignature = baseTextureSignature;
	}

	public UUID getOwnerUuid() {
		return ownerUuid;
	}

	public String getCharacterId() {
		return characterId;
	}

	public String getDisplayTab() {
		return displayTab;
	}

	public String getDisplayPlain() {
		return displayPlain;
	}

	public String getBaseTextureValue() {
		return baseTextureValue;
	}

	public String getBaseTextureSignature() {
		return baseTextureSignature;
	}
}

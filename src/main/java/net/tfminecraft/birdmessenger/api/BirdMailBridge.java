package net.tfminecraft.birdmessenger.api;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.tfminecraft.birdmessenger.BirdMessenger;

public final class BirdMailBridge {

	private static final String GATEWAY_CLASS = "net.tfminecraft.TFMCWeb.mail.BirdMailGateway";

	private BirdMailBridge() {}

	public static boolean enqueueArrival(
			BirdMessenger plugin,
			UUID ownerUuid,
			String addresseeCharacter,
			String senderMinecraftName,
			String contentsPreview) {
		if (ownerUuid == null || addresseeCharacter == null || addresseeCharacter.isBlank()) {
			return false;
		}
		Logger logger = plugin != null ? plugin.getLogger() : Logger.getLogger("BirdMessenger");
		try {
			Class<?> cls = Class.forName(GATEWAY_CLASS);
			Method method = cls.getMethod(
					"enqueueArrival",
					UUID.class,
					String.class,
					String.class,
					String.class);
			Object result = method.invoke(
					null, ownerUuid, addresseeCharacter, senderMinecraftName, contentsPreview);
			return Boolean.TRUE.equals(result);
		} catch (ClassNotFoundException ex) {
			logger.warning("TFMCWeb BirdMailGateway not found; Discord bird mail notify skipped.");
			return false;
		} catch (Throwable ex) {
			logger.log(Level.WARNING, "Discord bird mail notify failed for " + ownerUuid, ex);
			return false;
		}
	}
}

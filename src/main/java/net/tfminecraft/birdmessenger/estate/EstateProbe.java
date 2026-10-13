package net.tfminecraft.birdmessenger.estate;

/** Whether TLibs has the estate API (2.6.0). On older TLibs letters wait for dead characters as before. */
public final class EstateProbe {

	public static final String[] CLASSES = {
		"net.tfminecraft.tlibs.character.estate.Estates",
		"net.tfminecraft.tlibs.character.estate.EstateParticipant",
		"net.tfminecraft.tlibs.character.estate.EstateDecision",
		"net.tfminecraft.tlibs.character.estate.Outcome",
		"net.tfminecraft.tlibs.character.estate.Holdings",
	};

	private EstateProbe() {
	}

	public static boolean available(String... classes) {
		try {
			for (String name : classes) {
				Class.forName(name);
			}
			return true;
		} catch (ReflectiveOperationException | LinkageError ex) {
			return false;
		}
	}
}

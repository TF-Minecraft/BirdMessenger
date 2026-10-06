package net.tfminecraft.tfmcweb.mail;

import java.util.UUID;

/** Test-only implementation of the optional reflection gateway's public contract. */
public final class BirdMailGateway {
  public static boolean result;
  public static boolean fail;
  public static Object[] arrival;

  public static boolean enqueueArrival(
      UUID owner, String character, String sender, String preview) {
    arrival = new Object[] {owner, character, sender, preview};
    if (fail) throw new IllegalStateException("Gateway unavailable");
    return result;
  }
}

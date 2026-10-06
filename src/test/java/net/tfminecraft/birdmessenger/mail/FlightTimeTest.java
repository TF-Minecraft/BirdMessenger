package net.tfminecraft.birdmessenger.mail;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import net.tfminecraft.birdmessenger.BirdConfig;
import net.tfminecraft.birdmessenger.session.SelectedTarget;
import net.tfminecraft.rpcharacters.RPCharacters;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class FlightTimeTest {
  private final BirdConfig config = mock(BirdConfig.class);
  private final World world = mock(World.class);

  private BirdConfig configured() {
    when(config.secondsPerBlock()).thenReturn(0.25);
    when(config.minSeconds()).thenReturn(30);
    when(config.maxSeconds()).thenReturn(600);
    return config;
  }

  @Test
  void clampsBeforeNarrowingLargeDurations() {
    configured();
    when(config.secondsPerBlock()).thenReturn(100.0);
    assertEquals(
        600,
        FlightTime.flightSeconds(
            config, new Location(world, 0, 64, 0), new Location(world, 30_000_000, 64, 0)));
    when(config.secondsPerBlock()).thenReturn(Double.MAX_VALUE);
    assertEquals(
        600,
        FlightTime.flightSeconds(
            config, new Location(world, 0, 64, 0), new Location(world, 10, 64, 0)));
  }

  @Test
  void rejectsUnavailableWorldsAndRoundsAndClampsValidDistances() {
    configured();
    Location from = new Location(world, 0, 64, 0);
    assertNull(FlightTime.flightSeconds(null, from, from));
    assertNull(FlightTime.flightSeconds(config, null, from));
    assertNull(FlightTime.flightSeconds(config, from, null));
    assertNull(FlightTime.flightSeconds(config, new Location(null, 0, 0, 0), from));
    assertNull(FlightTime.flightSeconds(config, from, new Location(null, 0, 0, 0)));
    assertNull(FlightTime.flightSeconds(config, from, new Location(mock(World.class), 0, 0, 0)));
    assertEquals(30, FlightTime.flightSeconds(config, from, from));
    assertEquals(31, FlightTime.flightSeconds(config, from, new Location(world, 122, 64, 0)));
    assertEquals(600, FlightTime.flightSeconds(config, from, new Location(world, 4000, 64, 0)));
    long start = System.currentTimeMillis();
    long delivery = FlightTime.deliveryTimeMillis(config, from, from);
    assertTrue(delivery >= start + 30_000 && delivery <= System.currentTimeMillis() + 30_000);
    assertNull(FlightTime.deliveryTimeMillis(config, from, null));
  }

  @Test
  void sendsUseCharacterLocationsAndFallbackToMinimumForUnknownLocation() {
    configured();
    Player sender = mock(Player.class);
    PluginManager manager = mock(PluginManager.class);
    SelectedTarget target =
        new SelectedTarget(UUID.randomUUID(), "recipient", null, null, null, null);
    Location from = new Location(world, 0, 64, 0);
    when(sender.getLocation()).thenReturn(from);
    try (var bukkit = mockStatic(Bukkit.class);
        var characters = mockStatic(RPCharacters.class)) {
      bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
      assertNull(FlightTime.computeFlightSecondsAtSend(null, sender, target));
      assertNull(FlightTime.computeFlightSecondsAtSend(config, null, target));
      assertNull(FlightTime.computeFlightSecondsAtSend(config, sender, null));
      assertNull(FlightTime.computeFlightSecondsAtSend(config, sender, target));
      assertNull(FlightTime.computeAtSend(config, sender, target));
      when(manager.isPluginEnabled("RPCharacters")).thenReturn(true);
      when(sender.getLocation()).thenReturn(new Location(null, 0, 0, 0));
      assertNull(FlightTime.computeFlightSecondsAtSend(config, sender, target));
      when(sender.getLocation()).thenReturn(from);
      assertEquals(30, FlightTime.computeFlightSecondsAtSend(config, sender, target));
      characters
          .when(() -> RPCharacters.getMailTargetLocation(target.getOwnerUuid(), "recipient"))
          .thenReturn(new Location(world, 400, 64, 0));
      assertEquals(100, FlightTime.computeFlightSecondsAtSend(config, sender, target));
      long start = System.currentTimeMillis();
      long delivery = FlightTime.computeAtSend(config, sender, target);
      assertTrue(delivery >= start + 100_000 && delivery <= System.currentTimeMillis() + 100_000);
    }
  }

  @Test
  void formatsSingularAndPluralDurationParts() {
    assertEquals("0 seconds", FlightTime.formatDuration(0));
    assertEquals("1 second", FlightTime.formatDuration(1));
    assertEquals("59 seconds", FlightTime.formatDuration(59));
    assertEquals("1 minute", FlightTime.formatDuration(60));
    assertEquals("2 minutes", FlightTime.formatDuration(120));
    assertEquals("1 minute 1 second", FlightTime.formatDuration(61));
    assertEquals("2 minutes 2 seconds", FlightTime.formatDuration(122));
  }
}

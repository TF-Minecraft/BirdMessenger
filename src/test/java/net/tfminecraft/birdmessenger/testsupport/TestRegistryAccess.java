package net.tfminecraft.birdmessenger.testsupport;

import static org.mockito.Mockito.*;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;

/**
 * Stands in for the server's registries, which Paper finds through {@link java.util.ServiceLoader}.
 * Without it the first {@code Sound} constant a test touches fails to initialise, and every later
 * test in the same JVM gets {@code NoClassDefFoundError}. Sounds and enchantment identifiers
 * resolve; other registries remain unavailable without a server.
 */
public final class TestRegistryAccess implements RegistryAccess {

  private static final Map<NamespacedKey, Sound> SOUNDS = new ConcurrentHashMap<>();
  private static final Map<NamespacedKey, Enchantment> ENCHANTMENTS = new ConcurrentHashMap<>();

  @Override
  @Deprecated
  public <T extends Keyed> Registry<T> getRegistry(Class<T> type) {
    return registry(Sound.class.equals(type), Enchantment.class.equals(type));
  }

  @Override
  public <T extends Keyed> Registry<T> getRegistry(RegistryKey<T> key) {
    return registry(RegistryKey.SOUND_EVENT.equals(key), RegistryKey.ENCHANTMENT.equals(key));
  }

  @SuppressWarnings("unchecked")
  private static <T extends Keyed> Registry<T> registry(boolean sounds, boolean enchantments) {
    return (Registry<T>)
        Proxy.newProxyInstance(
            Registry.class.getClassLoader(),
            new Class<?>[] {Registry.class},
            (self, method, args) ->
                switch (method.getName()) {
                  case "equals" -> self == args[0];
                  case "hashCode" -> System.identityHashCode(self);
                  case "toString" -> sounds ? "TestRegistry[sounds]" : "TestRegistry[unavailable]";
                  case "get", "getOrThrow" -> {
                    NamespacedKey key = (NamespacedKey) args[0];
                    if (sounds) yield SOUNDS.computeIfAbsent(key, TestRegistryAccess::sound);
                    if (enchantments)
                      yield ENCHANTMENTS.computeIfAbsent(key, TestRegistryAccess::enchantment);
                    throw new IllegalStateException(
                        "Only sound and enchantment identifiers are available without a server");
                  }
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  private static Enchantment enchantment(NamespacedKey key) {
    return mock(
        Enchantment.class,
        invocation ->
            switch (invocation.getMethod().getName()) {
              case "getKey", "key" -> key;
              case "getName" -> key.getKey();
              default -> RETURNS_DEFAULTS.answer(invocation);
            });
  }

  private static Sound sound(NamespacedKey key) {
    return (Sound)
        Proxy.newProxyInstance(
            Sound.class.getClassLoader(),
            new Class<?>[] {Sound.class},
            (self, method, args) ->
                switch (method.getName()) {
                  case "equals" -> self == args[0];
                  case "hashCode" -> key.hashCode();
                  case "toString" -> "Sound[" + key + "]";
                  case "getKey", "key" -> key;
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }
}

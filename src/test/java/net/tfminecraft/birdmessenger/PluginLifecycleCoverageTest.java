package net.tfminecraft.birdmessenger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import net.tfminecraft.birdmessenger.command.BirdMessengerCommand;
import net.tfminecraft.birdmessenger.letters.LetterFeature;
import net.tfminecraft.birdmessenger.mail.MailService;
import net.tfminecraft.birdmessenger.mail.MailStore;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginLifecycleCoverageTest {
  @TempDir Path directory;

  @Test
  void configuredPaperClassLoaderInitializesTheRealPluginConstructor() throws Exception {
    Server server = mock(Server.class);
    Logger logger = mock(Logger.class);
    var description =
        new PluginDescriptionFile("BirdMessenger", "test", BirdMessenger.class.getName());
    try (var bukkit = mockStatic(Bukkit.class);
        var services = mockStatic(net.kyori.adventure.util.Services.class, CALLS_REAL_METHODS)) {
      bukkit
          .when(Bukkit::getUnsafe)
          .thenReturn(mock(org.bukkit.UnsafeValues.class, RETURNS_DEEP_STUBS));
      services
          .when(() -> net.kyori.adventure.util.Services.service(PluginLoader.class))
          .thenReturn(Optional.of(mock(PluginLoader.class)));
      var loader = new TestPluginLoader(description, server, logger, directory);
      JavaPlugin plugin =
          (JavaPlugin)
              loader.loadClass(BirdMessenger.class.getName()).getConstructor().newInstance();
      assertSame(plugin, loader.getPlugin());
      assertEquals("BirdMessenger", plugin.getName());
      assertEquals(directory.resolve("data").toFile(), plugin.getDataFolder());
      assertSame(description, plugin.getPluginMeta());
      plugin.onDisable();
      verify(logger).info("BirdMessenger disabled.");
    }
  }

  @Test
  void lifecycleInitializesListenersMailAndOptionalIntegrationsAndClosesResources() {
    for (int scenario = 0; scenario < 3; scenario++) {
      BirdMessenger plugin = mock(BirdMessenger.class, CALLS_REAL_METHODS);
      Logger logger = mock(Logger.class);
      doReturn(logger).when(plugin).getLogger();
      doNothing().when(plugin).saveDefaultConfig();
      YamlConfiguration yaml = new YamlConfiguration();
      yaml.set("discord.enabled", scenario != 2);
      doReturn(yaml).when(plugin).getConfig();
      PluginCommand command = mock(PluginCommand.class);
      doReturn(scenario == 1 ? null : command).when(plugin).getCommand("birdmessenger");
      PluginManager manager = mock(PluginManager.class);
      when(manager.isPluginEnabled("TFMCWeb")).thenReturn(scenario == 0);
      when(manager.getPlugin("ItemsAdder")).thenReturn(scenario == 0 ? mock(Plugin.class) : null);
      assertFalse(plugin.reloadLettersConfig());
      assertEquals(List.of(), plugin.letterItemPaths());
      plugin.onDisable();
      try (var bukkit = mockStatic(Bukkit.class);
          var features =
              mockConstruction(
                  LetterFeature.class,
                  (feature, context) -> {
                    when(feature.reload()).thenReturn(true);
                    when(feature.itemPaths()).thenReturn(List.of("letter", "sealed", "opened"));
                  });
          var stores = mockConstruction(MailStore.class);
          var services =
              mockConstruction(
                  MailService.class,
                  (service, context) ->
                      when(service.store()).thenReturn(stores.constructed().getFirst()))) {
        bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
        plugin.onEnable();
        assertNotNull(plugin.config());
        assertNotNull(plugin.sessions());
        assertSame(services.constructed().getFirst(), plugin.mail());
        verify(stores.constructed().getFirst()).load();
        verify(plugin.mail()).resume();
        verify(plugin.mail()).flushPendingForOnlinePlayers();
        verify(manager, times(scenario == 0 ? 5 : 4)).registerEvents(any(), eq(plugin));
        assertTrue(plugin.reloadLettersConfig());
        assertEquals(List.of("letter", "sealed", "opened"), plugin.letterItemPaths());
        if (scenario == 1) {
          verify(logger).warning("Command birdmessenger missing from plugin.yml");
          verify(logger).warning(contains("TFMCWeb is not loaded"));
        } else {
          verify(command).setExecutor(any(BirdMessengerCommand.class));
          verify(command).setTabCompleter(any(BirdMessengerCommand.class));
          verify(logger).info(contains(scenario == 0 ? "Discord notify on" : "Discord notify off"));
        }
        plugin.onDisable();
        verify(features.constructed().getFirst()).close();
        verify(stores.constructed().getFirst()).saveAll();
      }
    }
  }

  @Test
  void reloadCommandChecksPermissionAndReportsActualConfigAndIntegrationState() {
    BirdMessenger plugin = mock(BirdMessenger.class);
    BirdConfig config = mock(BirdConfig.class);
    when(plugin.config()).thenReturn(config);
    CommandSender sender = mock(CommandSender.class);
    BirdMessengerCommand command = new BirdMessengerCommand(plugin);
    assertTrue(command.onCommand(sender, null, "birdmessenger", new String[0]));
    assertTrue(command.onCommand(sender, null, "birdmessenger", new String[] {"invalid"}));
    verify(sender, times(2)).sendMessage("§eUsage: /birdmessenger reload");
    assertTrue(command.onCommand(sender, null, "birdmessenger", new String[] {"reload"}));
    verify(sender).sendMessage("§cYou do not have permission to reload BirdMessenger.");
    verifyNoInteractions(config);
    assertEquals(
        List.of(), command.onTabComplete(sender, null, "birdmessenger", new String[] {"r"}));
    when(sender.hasPermission("birdmessenger.reload")).thenReturn(true);
    assertEquals(
        List.of("reload"),
        command.onTabComplete(sender, null, "birdmessenger", new String[] {"RE"}));
    assertEquals(
        List.of(), command.onTabComplete(sender, null, "birdmessenger", new String[] {"x"}));
    assertEquals(List.of(), command.onTabComplete(sender, null, "birdmessenger", new String[0]));
    PluginManager manager = mock(PluginManager.class);
    try (var bukkit = mockStatic(Bukkit.class)) {
      bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
      command.onCommand(sender, null, "birdmessenger", new String[] {"RELOAD"});
      verify(sender).sendMessage(contains("Letters configuration was not reloaded"));
      verify(sender).sendMessage(contains("Discord notify: §edisabled"));
      when(plugin.reloadLettersConfig()).thenReturn(true);
      when(config.discordEnabled()).thenReturn(true);
      command.onCommand(sender, null, "birdmessenger", new String[] {"reload"});
      verify(sender).sendMessage(contains("TFMCWeb is not loaded"));
      when(manager.isPluginEnabled("TFMCWeb")).thenReturn(true);
      command.onCommand(sender, null, "birdmessenger", new String[] {"reload"});
      verify(sender).sendMessage(contains("TFMCWeb loaded"));
      verify(config, times(3)).reload();
      verify(sender, times(3)).sendMessage("§a[BirdMessenger] Reloaded config.yml.");
      verify(sender, times(2)).sendMessage("§a[BirdMessenger] Reloaded letters-config.yml.");
    }
  }

  private static final class TestPluginLoader extends ClassLoader
      implements ConfiguredPluginClassLoader {
    private final PluginDescriptionFile description;
    private final Server server;
    private final Logger logger;
    private final Path directory;
    private JavaPlugin plugin;

    TestPluginLoader(
        PluginDescriptionFile description, Server server, Logger logger, Path directory) {
      super(BirdMessenger.class.getClassLoader());
      this.description = description;
      this.server = server;
      this.logger = logger;
      this.directory = directory;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals(BirdMessenger.class.getName())) return super.loadClass(name, resolve);
      Class<?> loaded = findLoadedClass(name);
      if (loaded == null) {
        try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
          if (input == null) throw new ClassNotFoundException(name);
          byte[] bytes = input.readAllBytes();
          loaded =
              defineClass(name, bytes, 0, bytes.length, BirdMessenger.class.getProtectionDomain());
        } catch (IOException ex) {
          throw new ClassNotFoundException(name, ex);
        }
      }
      if (resolve) resolveClass(loaded);
      return loaded;
    }

    @Override
    public PluginMeta getConfiguration() {
      return description;
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve, boolean global, boolean libraries)
        throws ClassNotFoundException {
      return loadClass(name, resolve);
    }

    @Override
    public void init(JavaPlugin value) {
      plugin = value;
      value.init(
          server,
          description,
          directory.resolve("data").toFile(),
          directory.resolve("plugin.jar").toFile(),
          this,
          description,
          logger);
    }

    @Override
    public JavaPlugin getPlugin() {
      return plugin;
    }

    @Override
    public PluginClassLoaderGroup getGroup() {
      return null;
    }

    @Override
    public void close() {}
  }
}

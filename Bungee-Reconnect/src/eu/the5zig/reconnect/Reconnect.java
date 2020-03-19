package eu.the5zig.reconnect;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import eu.the5zig.reconnect.net.ReconnectBridge;
import eu.the5zig.reconnect.api.ServerReconnectEvent;
import eu.the5zig.reconnect.command.CommandReconnect;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.HandlerBoss;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class Reconnect extends Plugin implements Listener {

	private String reconnectingTitle = "Reconnecting{%dots%}";
	private String reconnectingSubtitle = "Please wait{%dots%}";
	private String reconnectingActionBar = "Reconnecting to server{%dots%}";
	private String connectingTitle = "Connecting..";
	private String connectingSubtitle = "Please wait";
	private String connectingActionBar = "Connecting you to the server..";
	private String failedTitle = "Reconnecting failed!";
	private String failedSubtitle = "";
	private String failedActionBar = "&eYou have been moved to the fallback server!";
	private int delayBeforeTrying = 60000;
	private int maxReconnectTries = 20;
	private int reconnectMillis = 5000;
	private int reconnectTimeout = 6000;
	private List<String> ignoredServers = new ArrayList<>();
	
	private String shutdownMessage = "Server closed";
	private Pattern shutdownPattern = null;
	
	/**
	 * A HashMap containing all reconnect tasks.
	 */
	private HashMap<UUID, Reconnecter> reconnecters = new HashMap<UUID, Reconnecter>();

	@Override
	public void onEnable() {
		getLogger().setLevel(Level.FINE);

		// setup Command
		getProxy().getPluginManager().registerCommand(this, new CommandReconnect(this));
		
		// load Configuration
		loadConfig();
		
		// register Listener
		getProxy().getPluginManager().registerListener(this, this);
	}
	
	/**
	 * Tries to load the config from the config file or creates a default config if the file does not exist.
	 */
	public boolean loadConfig() {
		reconnecters.keySet().forEach(uid -> cancelReconnecterFor(uid));
		try {
			if (!getDataFolder().exists() && !getDataFolder().mkdir()) {
				throw new IOException("Could not create plugin directory!");
			}
			File configFile = new File(getDataFolder(), "config.yml");
			if (configFile.exists()) {
				Configuration configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
				int pluginConfigVersion = ConfigurationProvider.getProvider(YamlConfiguration.class).load(getResourceAsStream("config.yml")).getInt("version");
				if (configuration.getInt("version") < pluginConfigVersion) {
					getLogger().info("Found an old config version! Replacing with new one...");
					File oldConfigFile = new File(getDataFolder(), "config.old.ver" + pluginConfigVersion + ".yml");
					Files.move(configFile, oldConfigFile);
					getLogger().info("A backup of your old config has been saved to " + oldConfigFile + "!");
					saveDefaultConfig(configFile);
				}
			} else {
				saveDefaultConfig(configFile);
			}
			
			Configuration configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
			
			reconnectingTitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("reconnecting-text.title", reconnectingTitle));
			reconnectingSubtitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("reconnecting-text.subtitle", reconnectingSubtitle));
			reconnectingActionBar = ChatColor.translateAlternateColorCodes('&', configuration.getString("reconnecting-text.actionbar", reconnectingActionBar));
			
			connectingTitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("connecting-text.title", connectingTitle));
			connectingSubtitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("connecting-text.subtitle", connectingSubtitle));
			connectingActionBar = ChatColor.translateAlternateColorCodes('&', configuration.getString("connecting-text.actionbar", connectingActionBar));
			
			failedTitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("failed-text.title", failedTitle));
			failedSubtitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("failed-text.subtitle", failedSubtitle));
			failedActionBar = ChatColor.translateAlternateColorCodes('&', configuration.getString("failed-text.actionbar", failedActionBar));
			
			delayBeforeTrying = configuration.getInt("delay-before-trying", delayBeforeTrying);
			maxReconnectTries = Math.max(configuration.getInt("max-reconnect-tries", maxReconnectTries), 1);
			reconnectTimeout = Math.max(configuration.getInt("reconnect-timeout", reconnectTimeout), 1000);
			reconnectMillis = Math.max(configuration.getInt("reconnect-time", reconnectMillis), reconnectTimeout);
			ignoredServers = configuration.getStringList("ignored-servers");
			String shutdownText = configuration.getString("shutdown.text");
			if (Strings.isNullOrEmpty(shutdownText)) {
				shutdownMessage = "";
				shutdownPattern = null;
			} else if (!configuration.getBoolean("shutdown.regex")) {
				shutdownMessage = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', shutdownText)); // strip all color codes
			} else {
				try {
					shutdownPattern = Pattern.compile(shutdownText);
				} catch (Exception e) {
					getLogger().warning("Could not compile shutdown regex! Please check your config! Using default shutdown message...");
					return false;
				}
			}
			return true;
		} catch (IOException e) {
			getLogger().warning("Could not load config, using default values...");
			e.printStackTrace();
			return false;
		}
	}

	private void saveDefaultConfig(File configFile) throws IOException {
		if (!configFile.createNewFile()) {
			throw new IOException("Could not create default config!");
		}
		try (InputStream is = getResourceAsStream("config.yml");
				OutputStream os = new FileOutputStream(configFile)) {
			ByteStreams.copy(is, os);
		}
	}

	@EventHandler
	public void onServerSwitch(ServerSwitchEvent event) {
		// We need to override the Downstream class of each user so that we can override the disconnect methods of it.
		// ServerSwitchEvent is called just right after the Downstream Bridge has been initialized, so we simply can
		// instantiate here our own implementation of the DownstreamBridge
		//
		// @see net.md_5.bungee.ServerConnector#L249

		UserConnection user = (UserConnection) event.getPlayer();
		ServerConnection server = user.getServer();
		ChannelWrapper ch = server.getCh();

		ReconnectBridge bridge = new ReconnectBridge(this, getProxy(), user, server);
		ch.getHandle().pipeline().get(HandlerBoss.class).setHandler(bridge);

		// Cancel the reconnect task (if any exist) and clear title and action bar.
		if (isReconnecting(user.getUniqueId())) {
			cancelReconnecterFor(user.getUniqueId());
		}
	}

	/**
	 * Checks whether the current server should be ignored and fires a ServerReconnectEvent afterwards.
	 *
	 * @param user   The User that should be reconnected.
	 * @param server The Server the User should be reconnected to.
	 * @return true, if the ignore list does not contain the server and the event hasn't been canceled.
	 */
	public boolean fireServerReconnectEvent(UserConnection user, ServerConnection server) {
		if (ignoredServers.contains(server.getInfo().getName())) {
			return false;
		}
		ServerReconnectEvent event = getProxy().getPluginManager().callEvent(new ServerReconnectEvent(user, server.getInfo()));
		return !event.isCancelled();
	}

	/**
	 * Checks if a UserConnection is still online.
	 *
	 * @param user The User that should be checked.
	 * @return true, if the UserConnection is still online.
	 */
	public boolean isUserOnline(UserConnection user) {
		return getProxy().getPlayer(user.getUniqueId()) != null;
	}

	/**
	 * Reconnects a User to a Server, as long as the user is currently online. If he isn't, his reconnect task (if he had one)
	 * will be canceled.
	 *
	 * @param user   The User that should be reconnected.
	 * @param server The Server the User should be connected to.
	 */
	public void reconnectIfOnline(UserConnection user, ServerConnection server) {
		synchronized (reconnecters) {
			if (isUserOnline(user)) {
				if (!isReconnecting(user.getUniqueId())) {
					reconnect(user, server);
				}
			} else {
				cancelReconnecterFor(user.getUniqueId());
			}	
		}
	}

	/**
	 * Reconnects the User without checking whether he's online or not.
	 *
	 * @param user   The User that should be reconnected.
	 * @param server The Server the User should be connected to.
	 */
	private void reconnect(UserConnection user, ServerConnection server) {
		synchronized (reconnecters) {
			Reconnecter reconnecter = reconnecters.get(user.getUniqueId());
			if (reconnecter == null) {
				reconnecters.put(user.getUniqueId(), reconnecter = new Reconnecter(this, getProxy(), user, server));
			}
			reconnecter.start();	
		}
	}

	/**
	 * Removes a reconnect task from the main HashMap
	 *
	 * @param uuid The UniqueId of the User.
	 */
	void cancelReconnecterFor(UUID uuid) {
		synchronized (reconnecters) {
			Reconnecter task = reconnecters.remove(uuid);
			if (task != null && getProxy().getPlayer(uuid) != null) {
				task.cancel();
			}	
		}
	}

	/**
	 * Checks whether a User has got a reconnect task.
	 *
	 * @param uuid The UniqueId of the User.
	 * @return true, if there is a task that tries to reconnect the User to a server.
	 */
	public boolean isReconnecting(UUID uuid) {
		return reconnecters.containsKey(uuid);
	}

	public String getReconnectingTitle() {
		return reconnectingTitle;
	}

	public String getReconnectingActionBar() {
		return reconnectingActionBar;
	}

	public String getConnectingTitle() {
		return connectingTitle;
	}
	
	public String getConnectingSubtitle() {
		return connectingSubtitle;
	}

	public String getConnectingActionBar() {
		return connectingActionBar;
	}

	public String getFailedTitle() {
		return failedTitle;
	}

	public String getFailedActionBar() {
		return failedActionBar;
	}

	public int getDelayBeforeTrying() {
		return delayBeforeTrying;
	}

	public int getMaxReconnectTries() {
		return maxReconnectTries;
	}

	public int getReconnectMillis() {
		return reconnectMillis;
	}

	public int getReconnectTimeout() {
		return reconnectTimeout;
	}

	public String getShutdownMessage() {
		return shutdownMessage;
	}

	public Pattern getShutdownPattern() {
		return shutdownPattern;
	}

	public boolean usesPattern() {
		return shutdownPattern != null;
	}
	
	public boolean isShutdownKick(String message) {
		if (shutdownPattern != null) {
			return shutdownPattern.matcher(message).matches();
		} else {
			return shutdownMessage.equals(message);
		}
	}

	public String getReconnectingSubtitle() {
		return reconnectingSubtitle;
	}

	public String getFailedSubtitle() {
		return failedSubtitle;
	}

}

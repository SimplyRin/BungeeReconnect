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
import net.md_5.bungee.api.config.ServerInfo;
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
import java.util.ListIterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Reconnect extends Plugin implements Listener {
	
	private String[] dots = new String[] {".", "..", "..."};
	private long dots_nanos = 1_000_000_000;
	
	private String reconnectingTitle = null, reconnectingSubtitle = null, reconnectingActionBar = null;
	
	private String connectingTitle = null, connectingSubtitle = null, connectingActionBar = null;
	
	private String failedTitle = null, failedSubtitle = null, failedActionBar = null, failedKickMessage = null;
	
	private int delayBeforeTrying = 0, reconnectTimeout = 0;
	private long nanosBetweenConnects = 0, maxReconnectNanos = 0, connctFinalizationNanos = 0;
	
	private List<String> ignoredServers = new ArrayList<>();
	
	private String shutdownMessage = "Server closed";
	private Pattern shutdownPattern = null;
	
	/**
	 * A HashMap containing all reconnect tasks.
	 */
	private HashMap<UUID, Reconnecter> reconnecters = new HashMap<UUID, Reconnecter>();
	
	private QueueManager queueManager = new QueueManager(this);

	@Override
	public void onEnable() {
		getLogger().setLevel(Level.FINE);

		// setup Command
		getProxy().getPluginManager().registerCommand(this, new CommandReconnect(this));
		
		// load Configuration
		tryReloadConfig(getLogger());
		
		
	}
	
	private void registerListener() {
		unregisterListener();
		getProxy().getPluginManager().registerListener(this, this);
	}
	
	private void unregisterListener() {
		getProxy().getPluginManager().unregisterListener(this);
	}
	
	/**
	 * Cancels all active reconnectors (if any)
	 * Tries to load the config from the config file or creates a default config if the file does not exist.
	 * Then it loads all required values into active memory and processes them as needed.
	 * @return If the configuration was successfully reloaded. if false, reconnect will disable functionality until rectified.
	 */
	public boolean tryReloadConfig(Logger log) {
		// disable listeners
		unregisterListener();
		
		// cancel all reconnecters
		synchronized (reconnecters) {
			(new ArrayList<UUID>(reconnecters.keySet())).forEach(uid -> {
				cancelReconnecterFor(uid);
			});;
		}
		
		try {
			loadConfig(log);
			registerListener();
			return true;
		} catch (Exception e) {
			log.log(Level.SEVERE, "Error while loading config, plugin functionality disabled until situation is rectified.", e);
			return false;
		}
	}
	
	private void loadConfig(Logger log) throws Exception {
		Configuration internalConfig = ConfigurationProvider.getProvider(YamlConfiguration.class).load(getResourceAsStream("config.yml"));
		
		// define config file
		File configFile = new File(getDataFolder(), "config.yml");
		// create data folder if not exists already
		if (!getDataFolder().exists() && !getDataFolder().mkdir()) {
			throw new IOException("Couldn't Mkdirs for plugin directory \"" + getDataFolder().getPath() + "\"");
		} else {
			// use the internal config in the jar for version comparison and defaults
			
			// if config file exists check if it needs updating
			if (configFile.exists()) {
				Configuration configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
				int configVersion = configuration.getInt("version");
				if (configuration.getInt("version") < internalConfig.getInt("version")) {
					log.info("Found an old config version! Replacing with new one...");
					
					// rename the old config so that values are not lost
					File oldConfigFile = new File(getDataFolder(), "config.old.ver" + configVersion + ".yml");
					Files.move(configFile, oldConfigFile);
					
					log.info("A backup of your old config has been saved to " + oldConfigFile + "!");
					saveDefaultConfig(configFile);
				}
			} else {
				saveDefaultConfig(configFile);
			}	
		}
		
		processConfig(ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile, internalConfig), log);
	}
	
	private void processConfig(Configuration configuration, Logger log) throws Exception {
		// obtain dots animation list from config
		List<String> dots = configuration.getStringList("dots-animation");
		
		// fallback to default dots animation if defined improperly to prevent AOB error
		assert(dots.size() > 0) : "\"dots-animation\" was configured improperly. It must have a size of at least 1.";
		// translate color codes
		ListIterator<String> it = dots.listIterator();
		while (it.hasNext()) {
			it.set(ChatColor.translateAlternateColorCodes('&', it.next()));
		}		
		// set array atomically as other threads may still be using it.
		this.dots = dots.toArray(new String[dots.size()]);
		
		// obtain dots animation delay from config
		dots_nanos = configuration.getInt("dots-animation-milis");
		if (dots_nanos < 50) {
			dots_nanos = 50;
			log.warning("\"dots-animation-milis\" was configured improperly. It must be 50 miliseconds or greater; The value has been clamped to 50.");
		}
		dots_nanos = TimeUnit.MILLISECONDS.toNanos(dots_nanos);
		
		// obtain reconnecting formatting from config
		reconnectingTitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("reconnecting-text.title"));
		reconnectingSubtitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("reconnecting-text.subtitle"));
		reconnectingActionBar = ChatColor.translateAlternateColorCodes('&', configuration.getString("reconnecting-text.actionbar"));
		
		// obtain connecting formatting from config
		connectingTitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("connecting-text.title"));
		connectingSubtitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("connecting-text.subtitle"));
		connectingActionBar = ChatColor.translateAlternateColorCodes('&', configuration.getString("connecting-text.actionbar"));
		
		// obtain failed formatting from config
		failedTitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("failed-text.title"));
		failedSubtitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("failed-text.subtitle"));
		failedActionBar = ChatColor.translateAlternateColorCodes('&', configuration.getString("failed-text.actionbar"));
		failedKickMessage = ChatColor.translateAlternateColorCodes('&', configuration.getString("failed-text.kick-message"));
		
		// obtain delays and timeouts from config
		delayBeforeTrying = Math.max(configuration.getInt("delay-before-trying"), 0);
		nanosBetweenConnects = TimeUnit.MILLISECONDS.toNanos(Math.max(configuration.getInt("delay-between-reconnects"), 0));
		maxReconnectNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(configuration.getInt("max-reconnect-time")), TimeUnit.MILLISECONDS.toNanos(delayBeforeTrying + reconnectTimeout));
		connctFinalizationNanos = Math.max(0, TimeUnit.MILLISECONDS.toNanos(configuration.getInt("connect-finalization-timeout")));
		reconnectTimeout = Math.max(configuration.getInt("reconnect-timeout"), 1000);
		
		// obtain ignored servers from config
		ignoredServers = configuration.getStringList("ignored-servers");
		
		// obtain shutdown values from config
		String shutdownText = ChatColor.translateAlternateColorCodes('&', configuration.getString("shutdown.text"));
		
		// check if shutdown message was defined
		if (Strings.isNullOrEmpty(shutdownText)) {
			// if it was not defined, use no message.
			shutdownMessage = "";
			shutdownPattern = null;
		} else {			
			try {
				// check if regex was not enabled for shutdown message. if so, use the shutdown message.
				if (!configuration.getBoolean("shutdown.regex")) {
					shutdownMessage = shutdownText;
				} else { // otherwise compile the regex pattern
					shutdownPattern = Pattern.compile(shutdownText);
				}	
			} catch (PatternSyntaxException e) {
				log.severe("regex \"shutdown.text\" was malformed and was unable to be compiled.");
				throw e;
			}		
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
	}

	/**
	 * Checks whether the current server should be ignored and fires a ServerReconnectEvent afterwards.
	 *
	 * @param user   The User that should be reconnected.
	 * @param server The Server the User should be reconnected to.
	 * @return true, if the ignore list does not contain the server and the event hasn't been canceled.
	 */
	public boolean fireServerReconnectEvent(UserConnection user, ServerConnection server) {
		// check if the server is supposed to be ignored for reconnects
		if (ignoredServers.contains(server.getInfo().getName())) {
			return false;
		} else { // otherwise fire off a reconnect event and check if it was cancelled
			ServerReconnectEvent event = getProxy().getPluginManager().callEvent(new ServerReconnectEvent(user, server.getInfo()));
			return !event.isCancelled();	
		}
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
		getLogger().info("Reconnecting \"" + user.getName() + "\" to \"" + server.getInfo().getName() + "\"");
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
			if (task != null) {
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
	
	public String getFailedKickMessage() {
		return failedKickMessage;
	}

	public int getDelayBeforeTrying() {
		return delayBeforeTrying;
	}
	
	public long getNanosBetweenConnects() {
		return nanosBetweenConnects;
	}
	
	public long getConnctFinalizationNanos() {
		return connctFinalizationNanos;
	}

	public int getReconnectTimeout() {
		return reconnectTimeout;
	}
	
	public long getMaxReconnectNanos() {
		return maxReconnectNanos;
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
	
	public long getDotNanos() {
		return dots_nanos;
	}
	
	public String getDots(long startTime) {
		return dots[(int) ((System.nanoTime()-startTime)/dots_nanos) % dots.length];
	}
	
	public String[] getDots() {
		// clone the array to prevent non-atomic modification as the array will be accessed by different threads.
		return dots.clone();
	}
	
	/**
	 * @param server The server this is bound to
	 * @param timeout how long will you wait in the queue
	 * @param timeoutUnit The timeunit for timeout
	 * @returns holder that can be unlocked when done.
	 */
	public Holder waitForConnect(ServerInfo server, long timeout, TimeUnit timeoutUnit) {
		return queueManager.queue(server, timeout, timeoutUnit);
	}

}

package eu.the5zig.reconnect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import eu.the5zig.reconnect.api.ServerReconnectEvent;
import eu.the5zig.reconnect.command.CommandReconnect;
import eu.the5zig.reconnect.net.ReconnectBridge;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectEvent.Reason;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.netty.HandlerBoss;

public class Reconnect extends Plugin implements Listener {
    
    private final ProxyServer bungee = ProxyServer.getInstance();
    
    private boolean debug = false;
    
    private Animations animations = new Animations(this);
    
    private String reconnectingTitle = null, reconnectingSubtitle = null, reconnectingActionBar = null;
    
    private String connectingTitle = null, connectingSubtitle = null, connectingActionBar = null;
    
    private String failedTitle = null, failedSubtitle = null, failedActionBar = null, failedKickMessage = null;
    
    private int delayBeforeTrying = 0, reconnectTimeout = 0, titleUpdateRate = 50;
    private long nanosBetweenConnects = 0, maxReconnectNanos = 0, connctFinalizationNanos = 0;
    
    private List<String> serversList = new ArrayList<>();
    private boolean whitelist = true;
    
    private String shutdownMessage = "Server closed";
    private Pattern shutdownPattern = null;
    
    /**
     * A HashMap containing all reconnect tasks.
     */
    private HashMap<UUID, Reconnecter> reconnecters = new HashMap<UUID, Reconnecter>();
    
    private QueueManager queueManager = new QueueManager(this);
    
    @Override
    public void onEnable() {
        
        // load Configuration
        if (tryReloadConfig(getLogger())) {
            // set bridges in the event of this plugin being loaded by a plugin manager
            for (ProxiedPlayer proxiedPlayer : getProxy().getPlayers()) {
                setDownstreamBridgeOf((UserConnection) proxiedPlayer);
            }
        }
        
        fixLogger();
        
        // setup Command
        getProxy().getPluginManager().registerCommand(this, new CommandReconnect(this));
    }
    
    public void fixLogger() {
        getLogger().setFilter(new Filter() {
            
            @Override
            public boolean isLoggable(LogRecord r) {
                // eat mega shit dicks bungee
                if (debug && r.getLevel().intValue() < Level.INFO.intValue()) {
                    r.setLoggerName(r.getLoggerName() + "] [" + r.getLevel().getName());
                    r.setLevel(Level.INFO);
                }
                return true;
            }
        });
    }
    
    public void debug(Object o, String m, Throwable t) {
        if (debug) {
            getLogger().log(Level.FINE, o + " " + m, t);
        }
    }
    
    public void debug(Object o, String m) {
        if (debug) {
            getLogger().log(Level.FINE, o + " " + m);
        }
    }
    
    public void debug(String m) {
        if (debug) {
            getLogger().log(Level.FINE, m);
        }
    }
    
    private void registerListener() {
        unregisterListener();
        getProxy().getPluginManager().registerListener(this, this);
    }
    
    private void unregisterListener() {
        getProxy().getPluginManager().unregisterListener(this);
    }
    
    /**
     * Cancels all active reconnectors (if any) Tries to load the config from the
     * config file or creates a default config if the file does not exist. Then it
     * loads all required values into active memory and processes them as needed.
     * 
     * @return If the configuration was successfully reloaded. if false, reconnect
     *         will disable functionality until rectified.
     */
    public boolean tryReloadConfig(Logger log) {
        // disable listeners
        unregisterListener();
        
        // cancel all reconnecters
        synchronized (reconnecters) {
            (new ArrayList<UUID>(reconnecters.keySet())).forEach(uid -> {
                cancelReconnecterFor(uid);
            });
        }
        
        try {
            loadConfig(log);
            registerListener();
            return true;
        } catch (Exception e) {
            log.log(Level.SEVERE,
                    "Error while loading config, plugin functionality disabled until situation is rectified.", e);
            return false;
        }
    }
    
    private void loadConfig(Logger log) throws Exception {
        Configuration internalConfig = ConfigurationProvider.getProvider(YamlConfiguration.class)
                .load(getResourceAsStream("config.yml"));
        
        // define config file
        File configFile = new File(getDataFolder(), "config.yml");
        // create data folder if not exists already
        if (!getDataFolder().exists() && !getDataFolder().mkdir()) {
            throw new IOException("Couldn't Mkdirs for plugin directory \"" + getDataFolder().getPath() + "\"");
        } else {
            // use the internal config in the jar for version comparison and defaults
            
            // if config file exists check if it needs updating
            if (configFile.exists()) {
                Configuration config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile);
                int configVersion = config.getInt("version");
                if (config.getInt("version") < internalConfig.getInt("version")) {
                    log.info("Found an old config version! Replacing with new one...");
                    
                    // rename the old config so that values are not lost
                    File oldConfigFile = new File(getDataFolder(), "config.old.ver." + configVersion + ".yml");
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
        
        this.debug = configuration.getBoolean("debug");
        
        Configuration animationsConfig = configuration.getSection("Animations");
        if (animationsConfig != null) {
            Animations animations = new Animations(this);
            animations.deserialize(animationsConfig);
            this.animations = animations;
        } else {
            log.warning("Animations configeration is null. Animations will not work until this is resolved.");
        }
        
        // obtain reconnecting formatting from config
        reconnectingTitle = ChatColor.translateAlternateColorCodes('&',
                configuration.getString("reconnecting-text.title"));
        reconnectingSubtitle = ChatColor.translateAlternateColorCodes('&',
                configuration.getString("reconnecting-text.subtitle"));
        reconnectingActionBar = ChatColor.translateAlternateColorCodes('&',
                configuration.getString("reconnecting-text.actionbar"));
        
        // obtain connecting formatting from config
        connectingTitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("connecting-text.title"));
        connectingSubtitle = ChatColor.translateAlternateColorCodes('&',
                configuration.getString("connecting-text.subtitle"));
        connectingActionBar = ChatColor.translateAlternateColorCodes('&',
                configuration.getString("connecting-text.actionbar"));
        
        // obtain failed formatting from config
        failedTitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("failed-text.title"));
        failedSubtitle = ChatColor.translateAlternateColorCodes('&', configuration.getString("failed-text.subtitle"));
        failedActionBar = ChatColor.translateAlternateColorCodes('&', configuration.getString("failed-text.actionbar"));
        failedKickMessage = ChatColor.translateAlternateColorCodes('&',
                configuration.getString("failed-text.kick-message"));
        
        // obtain delays and timeouts from config
        titleUpdateRate = Math.min(Math.max(configuration.getInt("title-update-rate"), 50), 5000);
        delayBeforeTrying = Math.max(configuration.getInt("delay-before-trying"), 0);
        nanosBetweenConnects = TimeUnit.MILLISECONDS
                .toNanos(Math.max(configuration.getInt("delay-between-reconnects"), 0));
        maxReconnectNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(configuration.getInt("max-reconnect-time")),
                TimeUnit.MILLISECONDS.toNanos(delayBeforeTrying + reconnectTimeout));
        connctFinalizationNanos = Math.max(0,
                TimeUnit.MILLISECONDS.toNanos(configuration.getInt("connect-finalization-timeout")));
        reconnectTimeout = Math.max(configuration.getInt("reconnect-timeout"),
                2000 + configuration.getInt("connect-finalization-timeout"));
        
        // obtain ignored/allowed servers from config
        whitelist = resolveMode(configuration.getString("servers.mode"));
        serversList = configuration.getStringList("servers.list");
        
        // obtain shutdown values from config
        String shutdownText = ChatColor.translateAlternateColorCodes('&', configuration.getString("shutdown.text"));
        
        // check if shutdown message was defined
        if (Strings.isNullOrEmpty(shutdownText)) {
            // if it was not defined, use no message.
            shutdownMessage = "";
            shutdownPattern = null;
        } else {
            try {
                // check if regex was not enabled for shutdown message. if so, use the shutdown
                // message.
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
        try (InputStream is = getResourceAsStream("config.yml"); OutputStream os = new FileOutputStream(configFile)) {
            ByteStreams.copy(is, os);
        }
    }
    
    @EventHandler()
    public void onServerSwitch(ServerSwitchEvent event) {
        // We need to override the Downstream class of each user so that we can override
        // the disconnect methods of it.
        // ServerSwitchEvent is called just right after the Downstream Bridge has been
        // initialized, so we simply can
        // instantiate here our own implementation of the DownstreamBridge
        setDownstreamBridgeOf((UserConnection) event.getPlayer());
        
        // cancel reconnecter instantly if the user switches servers
        Reconnecter re = getReconnecterFor(event.getPlayer().getUniqueId());
        if (re != null && !re.isSameInfo()) {
            re.cancel(true);
            final ServerConnection currentServer = re.getUser().getServer();
            getLogger().info("Cancelled reconnect for \"" + re.getUser().getName() + "\" on \""
                    + re.getServer().getInfo().getName() + "\" as they have switched servers to \""
                    + (currentServer == null ? "null?" : currentServer.getInfo().getName() + "\""));
        }
    }
    
    public void setDownstreamBridgeOf(UserConnection user) {
        ServerConnection con = user.getServer();
        
        ReconnectBridge bridge = new ReconnectBridge(this, getProxy(), user, con);
        con.getCh().getHandle().pipeline().get(HandlerBoss.class).setHandler(bridge);
    }
    
    private boolean resolveMode(String mode) {
        switch (mode.toLowerCase()) {
        case "whitelist":
            return true;
        case "blacklist":
            return false;
        default:
            getLogger().warning(
                    "servers.mode \"" + mode + "\" is not a valid mode. Must be either whitelist or blacklist.");
            getLogger().warning("defaulting to blacklist.");
            return false;
        }
    }
    
    public boolean isIgnoredServer(ServerInfo server) {
        return whitelist ^ serversList.contains(server.getName());
    }
    
    /**
     * fires a ServerReconnectEvent.
     *
     * @param user   The User that should be reconnected.
     * @param server The Server the User should be reconnected to.
     * @return true, if the event hasn't been canceled.
     */
    public boolean fireServerReconnectEvent(UserConnection user, ServerConnection server) {
        ServerReconnectEvent event = getProxy().getPluginManager()
                .callEvent(new ServerReconnectEvent(user, server.getInfo()));
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
     * Reconnects a User to a Server, as long as the user is currently online. If he
     * isn't, his reconnect task (if he had one) will be canceled.
     *
     * @param user   The User that should be reconnected.
     * @param server The Server the User should be connected to.
     */
    public void reconnectIfOnline(UserConnection user, ServerConnection server) {
        getLogger().info("Reconnecting \"" + user.getName() + "\" to \"" + server.getInfo().getName() + "\"");
        if (isUserOnline(user)) {
            if (!isReconnecting(user.getUniqueId())) {
                reconnect(user, server);
            }
        } else {
            debug("cannot reconnect \"" + user.getName() + "\" as they are offline.");
            cancelReconnecterFor(user.getUniqueId());
        }
    }
    
    /**
     * Gets current reconnecter for player or null if none exist
     * 
     * @param uid UUID of the player
     * @return the reconnecter or null of there is none
     */
    public Reconnecter getReconnecterFor(UUID uid) {
        synchronized (reconnecters) {
            return reconnecters.get(uid);
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
     * Fails, cancels and Removes a reconnect task from the main HashMap
     *
     * @param uuid The UniqueId of the User.
     */
    void cancelReconnecterFor(UUID uuid) {
        synchronized (reconnecters) {
            Reconnecter task = reconnecters.remove(uuid);
            if (task != null) {
                task.failReconnect();
                task.cancel();
            }
        }
    }
    
    // internal use only, does not cancel only removes.
    void remove(Reconnecter reconnecter) {
        synchronized (reconnecters) {
            reconnecters.remove(reconnecter.getUUID(), reconnecter);
        }
    }
    
    /**
     * Causes the user to fall back on the provided iterator of servers
     * 
     * @param user     The server to cause to fallback
     * @param it       the servers to fall back on
     * @param onFailed What to do if all servers fail
     */
    public void fallback(UserConnection user, Iterator<ServerInfo> it, Callable<Object> onFailed) {
        if (!it.hasNext()) {
            try {
                onFailed.call();
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }
        user.connect(it.next(), new Callback<Boolean>() {
            @Override
            public void done(Boolean done, Throwable t) {
                if (!done) {
                    fallback(user, it, onFailed);
                }
            }
        }, Reason.SERVER_DOWN_REDIRECT);
        user.sendMessage(bungee.getTranslation("server_went_down"));
    }
    
    /**
     * Checks whether a User has got a reconnect task.
     *
     * @param uuid The UniqueId of the User.
     * @return true, if there is a task that tries to reconnect the User to a
     *         server.
     */
    public boolean isReconnecting(UUID uuid) {
        synchronized (reconnecters) {
            return reconnecters.containsKey(uuid);
        }
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
    
    public int getTitleUpdateRate() {
        return titleUpdateRate;
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
            return shutdownMessage.isEmpty() || shutdownMessage.equals(message);
        }
    }
    
    public String getReconnectingSubtitle() {
        return reconnectingSubtitle;
    }
    
    public String getFailedSubtitle() {
        return failedSubtitle;
    }
    
    public Animations getAnimations() {
        return animations;
    }
    
    public String animate(Reconnecter c, String s) {
        return animations.animate(c, s);
    }
    
    public List<ServerInfo> getFallbackServersFor(UserConnection user) {
        List<ServerInfo> servers = new ArrayList<ServerInfo>();
        user.getPendingConnection().getListener().getServerPriority()
        .forEach(s -> servers.add(bungee.getServerInfo(s)));
        return servers;
    }
    
    /**
     * @param server      the server this is bound to
     * @param who         the player that is waiting
     * @param timeout     how long will you wait in the queue
     * @param timeoutUnit The timeunit for timeout
     * @returns holder that can be unlocked when done.
     */
    public Holder waitForConnect(ServerInfo server, UserConnection who, long timeout, TimeUnit timeoutUnit) {
        return queueManager.queue(server, who, timeout, timeoutUnit);
    }
    
}

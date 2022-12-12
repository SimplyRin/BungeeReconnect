package me.taucu.reconnect;

import com.google.common.base.Strings;
import dev.simplix.protocolize.api.PacketDirection;
import dev.simplix.protocolize.api.Protocol;
import dev.simplix.protocolize.api.Protocolize;
import dev.simplix.protocolize.api.SoundCategory;
import dev.simplix.protocolize.data.Sound;
import me.taucu.reconnect.api.ServerReconnectEvent;
import me.taucu.reconnect.command.CommandReconnect;
import me.taucu.reconnect.net.DownstreamInboundHandler;
import me.taucu.reconnect.packets.PS2CStopSoundPacket;
import me.taucu.reconnect.util.ConfigUtil;
import me.taucu.reconnect.util.provider.DependentData;
import me.taucu.reconnect.util.provider.DependentDataProvider;
import me.taucu.reconnect.util.provider.Music;
import me.taucu.reconnect.util.provider.MusicProvider;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ServerConnectEvent.Reason;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.event.SettingsChangedEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;
import org.bstats.bungeecord.Metrics;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class Reconnect extends Plugin implements Listener {
    
    private final ProxyServer bungee = ProxyServer.getInstance();
    
    private boolean debug = false;
    
    private Animations animations = new Animations(this);

    private MusicProvider musicProvider = null;
    
    private int delayBeforeTrying = 0, reconnectTimeout = 0, titleUpdateRate = 50;
    private long nanosBetweenConnects = 0, maxReconnectNanos = 0, connectFinalizationNanos = 0;
    
    private List<String> serversList = new ArrayList<>();
    private boolean serversListIsWhitelist = true;
    
    private String shutdownMessage = "Server closed";
    private Pattern shutdownPattern = null;

    DependentDataProvider langProvider = new DependentDataProvider(this);
    ConcurrentHashMap<Object, Locale> localeByUUID = new ConcurrentHashMap<>();
    
    /**
     * A HashMap containing all reconnect tasks.
     */
    private final HashMap<UUID, Reconnector> reconnectors = new HashMap<>();
    
    private final QueueManager queueManager = new QueueManager(this);
    
    @Override
    public void onEnable() {
        fixLogger();
        
        // setup command
        getProxy().getPluginManager().registerCommand(this, new CommandReconnect(this));

        // also registers events etc
        reload();
    }
    
    private void enableSystem() {
        disableSystem();
        // set bridges in the event of this plugin being loaded by a plugin manager
        for (ProxiedPlayer proxiedPlayer : getProxy().getPlayers()) {
            DownstreamInboundHandler.attachHandlerTo((UserConnection) proxiedPlayer, this);
        }
        
        getProxy().getPluginManager().registerListener(this, this);
    }
    
    private void disableSystem() {
        // unregister listeners
        getProxy().getPluginManager().unregisterListener(this);
        
        // detach all handlers
        getProxy().getPlayers().forEach(ucon -> DownstreamInboundHandler.detachHandlerFrom((UserConnection) ucon));
        
        // cancel all reconnectors
        getReconnectors().forEach(re -> re.cancel(true));
    }
    
    public boolean reload() {
        disableSystem();
        
        try {
            loadConfig(getLogger());
            enableSystem();
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error while loading config, plugin functionality disabled until situation is rectified.", e);
        }
        return false;
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
                if (!ConfigUtil.checkConfigVersion(config, internalConfig)) {
                    log.info("Found an old config version! Replacing with new one...");
                    File oldConfigFile = ConfigUtil.renameOldConfig(configFile);
                    log.info("A backup of your old config has been saved to " + oldConfigFile + "!");
                    ConfigUtil.copyInternalFile(configFile, "config.yml");
                }
            } else {
                ConfigUtil.copyInternalFile(configFile, "config.yml");
            }
        }
        
        processConfig(ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile, internalConfig), log);
    }

    private void processConfig(Configuration configuration, Logger log) {
        
        this.debug = configuration.getBoolean("debug");
        
        Configuration animationsConfig = configuration.getSection("Animations");
        if (animationsConfig != null) {
            Animations animations = new Animations(this);
            animations.deserialize(animationsConfig);
            this.animations = animations;
        } else {
            log.warning("Animations configuration is null. Animations will not work until this is resolved.");
        }

        try {
            if (configuration.get("Reconnection Music") instanceof Configuration && getProxy().getPluginManager().getPlugin("Protocolize") != null) {
                Configuration reconMusic = configuration.getSection("Reconnection Music");
                List<Music> musics = new ArrayList<>();
                for (String k : reconMusic.getKeys()) {
                    Configuration music = reconMusic.getSection(k);
                    try {
                        musics.add(new Music(
                                Sound.valueOf(k.toUpperCase()),
                                SoundCategory.valueOf(music.getString("Category").toUpperCase()),
                                music.getFloat("Volume"),
                                music.getFloat("Pitch")
                        ));
                    } catch (IllegalArgumentException e) {
                        log.log(Level.SEVERE, "Error while loading music \"" + k + "\"", e);
                    }
                }

                if (!musics.isEmpty()) {
                    Protocolize.protocolRegistration().registerPacket(PS2CStopSoundPacket.MAPPINGS, Protocol.PLAY, PacketDirection.CLIENTBOUND, PS2CStopSoundPacket.class);
                    this.musicProvider = new MusicProvider(musics);
                }
            }
        } catch (LinkageError e) {
            log.log(Level.SEVERE, "Failed to initialize MusicProvider due to a LinkageError", e);
        }

        String[] defaultLocale = configuration.getString("default-locale").split("_");
        if (defaultLocale.length != 2) {
            log.warning("default locale is invalid. Defaulting to \"en_US\"");
            defaultLocale = new String[] {"en", "US"};
        }
        langProvider.setDefaultLocale(new Locale(defaultLocale[0], defaultLocale[1]));
        langProvider.load();

        // obtain delays and timeouts from config
        titleUpdateRate = Math.min(Math.max(configuration.getInt("title-update-rate"), 50), 5000);
        delayBeforeTrying = Math.max(configuration.getInt("delay-before-trying"), 500);
        nanosBetweenConnects = TimeUnit.MILLISECONDS
                .toNanos(Math.max(configuration.getInt("delay-between-reconnects"), 0));
        maxReconnectNanos = Math.max(TimeUnit.MILLISECONDS.toNanos(configuration.getInt("max-reconnect-time")),
                TimeUnit.MILLISECONDS.toNanos(delayBeforeTrying + reconnectTimeout));
        connectFinalizationNanos = Math.max(0,
                TimeUnit.MILLISECONDS.toNanos(configuration.getInt("connect-finalization-timeout")));
        reconnectTimeout = Math.max(configuration.getInt("reconnect-timeout"),
                2000 + configuration.getInt("connect-finalization-timeout"));
        
        // obtain ignored/allowed servers from config
        serversListIsWhitelist = resolveMode(configuration.getString("servers.mode"));
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
                } else { // otherwise, compile the regex pattern
                    shutdownPattern = Pattern.compile(shutdownText);
                }
            } catch (PatternSyntaxException e) {
                log.severe("regex \"shutdown.text\" is malformed and was unable to be compiled.");
                throw e;
            }
        }

        Metrics metrics = new Metrics(this, 14792);
    }
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onServerSwitch(ServerSwitchEvent event) {
        debug("ON_SERVER_SWITCH from=" + (event.getFrom() != null ? event.getFrom().getName() : "null") + " to=" + event.getPlayer().getServer().getInfo().getName());
        UserConnection ucon = (UserConnection) event.getPlayer();
        
        // we need to detect exceptions and kicks before bungeecord does,
        // so we register a channel handler before HandlerBoss
        DownstreamInboundHandler.attachHandlerTo(ucon, this);
        
        // checks to see if we should cancel the reconnector if it exists
        Reconnector re = getReconnectorFor(event.getPlayer().getUniqueId());
        if (re != null && !re.isSameInfo()) {
            re.cancel(true);
            final ServerConnection currentServer = re.getUser().getServer();
            getLogger().info("Cancelled reconnect for \"" + re.getUser().getName() + "\" on \""
                    + re.getServer().getInfo().getName() + "\" as they have switched servers to \""
                    + (currentServer == null ? "null?" : currentServer.getInfo().getName() + "\""));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDisconnect(PlayerDisconnectEvent e) {
        Reconnector re = getReconnectorFor(e.getPlayer().getUniqueId());
        if (re != null) {
            getLogger().info("Cancelled reconnect for \"" + re.getUser().getName() + "\" on \""
                            + re.getServer().getInfo().getName() + "\" as they have disconnected");
            re.cancel(true);
        }
        localeByUUID.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onSettingsChange(SettingsChangedEvent event) {
        ProxiedPlayer player = event.getPlayer();
        localeByUUID.put(player.getUniqueId(), player.getLocale());

        Reconnector recon = getReconnectorFor(player.getUniqueId());
        if (recon != null) {
            recon.setData(langProvider.getForLocale(player.getLocale()));
        }
    }
    
    private boolean resolveMode(String mode) {
        switch (mode.toLowerCase()) {
        case "whitelist":
            return true;
        case "blacklist":
            return false;
        default:
            getLogger().warning("servers.mode \"" + mode + "\" is not a valid mode. Must be either whitelist or blacklist.");
            getLogger().warning("defaulting to blacklist.");
            return false;
        }
    }
    
    public boolean isIgnoredServer(ServerInfo server) {
        return serversListIsWhitelist ^ serversList.contains(server.getName());
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
        return user.isConnected();
    }
    
    /**
     * Starts the reconnect process assuming all conditions are met
     * <p>
     * These conditions include but are not limited to:
     * Server Whitelist/Blacklist, user being online
     * @param ucon the UserConnection to reconnect
     * @param server the Server they are going to reconnect to
     * @return true if a reconnect will occur, false otherwise
     */
    public boolean reconnectIfApplicable(UserConnection ucon, ServerConnection server) {
        if (isIgnoredServer(server.getInfo()) && fireServerReconnectEvent(ucon, server)) {
            debug(this, "not reconnecting because it's an ignored server, or the reconnect event has been cancelled");
        } else {
            return reconnectIfOnline(ucon, server);
        }
        return false;
    }
    
    /**
     * Reconnects a User to a Server, as long as the user is currently online. If he
     * isn't, his reconnect task (if he had one) will be canceled.
     *
     * @param user   The User that should be reconnected.
     * @param server The Server the User should be connected to.
     * @return true if the user is online or was reconnecting to this server
     */
    public boolean reconnectIfOnline(UserConnection user, ServerConnection server) {
        if (isUserOnline(user)) {
            getLogger().info("Reconnecting \"" + user.getName() + "\" to \"" + server.getInfo().getName() + "\"");
            reconnect(user, server);
            return true;
        } else {
            debug("cannot reconnect \"" + user.getName() + "\" as they are offline.");
        }
        return false;
    }
    
    /**
     * Returns a new list of all current reconnectors
     * @return list of reconnectors
     */
    public ArrayList<Reconnector> getReconnectors() {
        synchronized (reconnectors) {
            return new ArrayList<>(reconnectors.values());
        }
    }
    
    /**
     * Gets current reconnector for player or null if none exist
     * 
     * @param uid UUID of the player
     * @return the reconnector or null of there is none
     */
    public Reconnector getReconnectorFor(UUID uid) {
        synchronized (reconnectors) {
            return reconnectors.get(uid);
        }
    }
    
    /**
     * Reconnects the User without checking whether he's online or not.
     *
     * @param user   The User that should be reconnected.
     * @param server The Server the User should be connected to.
     */
    private synchronized void reconnect(UserConnection user, ServerConnection server) {

        DependentData data = langProvider.getForLocale(
            localeByUUID.get(user.getUniqueId()));
        
        Reconnector reconnector = new Reconnector(
            this, getProxy(), user, server, data == null ? langProvider.getDefault() : data);
        Reconnector current;
        synchronized (reconnectors) {
            current = reconnectors.get(user.getUniqueId());
            reconnectors.put(user.getUniqueId(), reconnector);
        }
        if (current != null) {
            current.cancel();
        }
        reconnector.start();
    }
    
    /**
     * Fails, cancels and Removes a reconnect task from the main HashMap
     *
     * @param uuid The UniqueId of the User.
     */
    public void cancelReconnectorFor(UUID uuid) {
        Reconnector task;
        synchronized (reconnectors) {
            task = reconnectors.remove(uuid);
        }
        if (task != null) {
            task.failReconnect();
            task.cancel();
        }
    }
    
    // internal use only, does not cancel only removes.
    void remove(Reconnector reconnector) {
        synchronized (reconnectors) {
            reconnectors.remove(reconnector.getUUID(), reconnector);
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
        user.connect(it.next(), (done, throwable) -> {
            if (!done) {
                fallback(user, it, onFailed);
            }
        }, Reason.SERVER_DOWN_REDIRECT);
        user.sendMessage(bungee.getTranslation("server_went_down"));
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
    
    public long getConnectFinalizationNanos() {
        return connectFinalizationNanos;
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
    
    public boolean isReconnectKick(String message) {
        if (shutdownPattern != null) {
            return shutdownPattern.matcher(message).matches();
        } else {
            return shutdownMessage.isEmpty() || shutdownMessage.equals(message);
        }
    }
  
    public Animations getAnimations() {
        return animations;
    }

    public MusicProvider getMusicProvider() {
        return musicProvider;
    }
    
    public String animate(Reconnector c, String s) {
        return animations.animate(c, s);
    }
    
    public List<ServerInfo> getFallbackServersFor(UserConnection user) {
        List<ServerInfo> servers = new ArrayList<>();
        user.getPendingConnection().getListener().getServerPriority()
        .forEach(s -> servers.add(bungee.getServerInfo(s)));
        return servers;
    }
    
    /**
     * @param server      the server this is bound to
     * @param who         the player that is waiting
     * @param timeout     how long will you wait in the queue
     * @param timeoutUnit The timeunit for timeout
     * @return holder that can be unlocked when done.
     */
    public Holder waitForConnect(ServerInfo server, UserConnection who, long timeout, TimeUnit timeoutUnit) {
        return queueManager.queue(server, who, timeout, timeoutUnit);
    }
    
    public void fixLogger() {
        getLogger().setFilter(r -> {
            // eat mega shit dicks bungee
            if (debug && r.getLevel().intValue() < Level.INFO.intValue()) {
                r.setLoggerName(r.getLoggerName() + "] [" + r.getLevel().getName());
                r.setLevel(Level.INFO);
            }
            return true;
        });
    }
    
    public void debug(Object o, String m, Throwable t) {
        if (debug) {
            StackTraceElement element = Thread.currentThread().getStackTrace()[2];
            getLogger().log(Level.FINE, o.getClass().getSimpleName() + "@" + Integer.toHexString(o.hashCode()) + ":" + element.getLineNumber() + " " + m, t);
        }
    }
    
    public void debug(Object o, String m) {
        if (debug) {
            StackTraceElement element = Thread.currentThread().getStackTrace()[2];
            getLogger().log(Level.FINE, o.getClass().getSimpleName() + "@" + Integer.toHexString(o.hashCode()) + ":" + element.getLineNumber() + " " + m);
        }
    }
    
    public void debug(String m) {
        if (debug) {
            StackTraceElement element = Thread.currentThread().getStackTrace()[2];
            String clazzName;
            try {
                clazzName = Class.forName(element.getClassName()).getSimpleName();
            } catch (ClassNotFoundException e) {
                clazzName = element.getClassName();
            }
            getLogger().log(Level.FINE, clazzName + ":" + element.getLineNumber() + " " + m);
        }
    }
    
}

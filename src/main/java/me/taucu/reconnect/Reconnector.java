package me.taucu.reconnect;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.util.internal.PlatformDependent;
import lombok.Getter;
import lombok.Setter;
import me.taucu.reconnect.net.ReconnectChannelInitializer;
import me.taucu.reconnect.util.MyPipelineUtils;
import me.taucu.reconnect.util.provider.DependentData;
import me.taucu.reconnect.util.provider.MusicProvider;
import me.taucu.reconnect.util.provider.TitleViewEntry;
import me.taucu.reconnect.util.scheduler.Sched;
import net.md_5.bungee.BungeeServerInfo;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.protocol.packet.KeepAlive;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Reconnector {
    
    private static final TextComponent EMPTY = new TextComponent("");
    private static final Random rand = new Random();
    
    private final Reconnect reconnect;
    private final Logger log;
    private final ProxyServer bungee;
    private final UserConnection user;
    private final ServerConnection currentServer;
    private final BungeeServerInfo targetInfo;

    //user can change locale hence change provider
    @Setter @Getter private DependentData data;

    // The time start() was called/object constructed.
    private long startTime = System.nanoTime();
    
    private final long updateRate;
    private final int titleStayTime;
    
    // The updates task itself, nonull once started.
    private volatile ScheduledTask updatesTask = null;
    // Whether or not to send updates (keepalive/titles etc)
    private volatile boolean updatesEnabled = false;
    
    // If this is cancelled
    private volatile boolean isCancelled = false;
    // If this is running
    private volatile boolean isRunning = false;
    
    // The current future. this is not null if the last future was a success.
    private volatile ChannelFuture channelFuture = null;
    private final Object channelSync = new Object();
    
    // last time a try was attempted
    private volatile long lastChannelTime = System.nanoTime();
    
    // The current holder if any
    private volatile Holder holder = null;
    
    // true once the user has been flagged as "joined"
    private volatile boolean joinFlag = false;
    
    /**
     * 
     * @param instance The instance of reconnect
     * @param bungee   The proxyserver
     * @param user     The user to reconnect
     * @param server   The server connection the user will try to reconnect to
     */

    public Reconnector(Reconnect instance, ProxyServer bungee, UserConnection user, ServerConnection server, DependentData data) {
        this.reconnect = instance;
        this.log = instance.getLogger();
        this.bungee = bungee;
        this.user = user;
        this.currentServer = server;
        this.targetInfo = server.getInfo();
        this.updateRate = instance.getTitleUpdateRate();
        this.titleStayTime = (int) Math.ceil(this.updateRate / 50D) + 20;
        this.data = data;
    }
    
    private final Runnable run = new Runnable() {
        long ctime = System.nanoTime();
        @Override
        public void run() {
            if (isCancelled) {
                reconnect.debug(Reconnector.this, "cancelled check is true");
                return;
            }
            final ChannelFuture future = channelFuture;
            
            // Only retry to reconnect the user if they are still online and hasn't been moved to another server etc, do this:
            if (statusCheck()) {
                // check if timeout has expired
                if (hasTimedOut()) {
                    reconnect.debug(Reconnector.this, "reconnector has exceeded max reconnect time");
                    // Proceed with plan B :(
                    failReconnect();
                } else {
                    // Check if the future is null
                    if (future != null) {
                        reconnect.debug(Reconnector.this, "channelFuture check");
                        // check if the channel has been canceled or has completed but failed or has timed out
                        if (!future.isCancelled()
                                && !(future.isDone() && !(future.isSuccess() && future.channel().isActive()))
                                && lastChannelTime + TimeUnit.MILLISECONDS.toNanos(reconnect.getReconnectTimeout()) > ctime
                                ) {
                            retry();
                            return;
                        }
                        
                        reconnect.debug(Reconnector.this, "channel future failed");
                        dropHolder();
                        tryCloseChannel(future);
                    }
                    
                    long nextFutureTime = lastChannelTime + 1_000_000_000L + reconnect.getNanosBetweenConnects();
                    if (nextFutureTime > ctime) {
                        // Attempt a reconnect
                        tryReconnect();
                    } else {
                        retry(Math.max(1, TimeUnit.NANOSECONDS.toMillis(nextFutureTime - ctime)));
                    }
                }
                return;
            } else {
                reconnect.debug(Reconnector.this, "status check returned false");
            } // Otherwise cancel this reconnector as it ain't needed no more
            cancel();
        }
    };
    
    /**
     * Used internally to determine if reconnect should continue
     * 
     * @return true if we should continue
     */
    public boolean statusCheck() {
        return isOnline() && (isSameServer() || (isSameInfo() && !joinFlag));
    }
    
    /**
     * Checks of the user is connected to the same server info as the one they lost connection to.
     * 
     * @return if the user is on the same server info
     */
    public boolean isSameInfo() {
        ServerConnection c = user.getServer();
        return c == null || targetInfo.equals(c.getInfo());
    }
    
    /**
     * Checks of the user is connected to the same server as the one they lost connection to.
     * 
     * @return if the user is on the same server connection
     */
    public boolean isSameServer() {
        return Objects.equals(user.getServer(), currentServer);
    }
    
    /**
     * checks of the user is online
     * 
     * @return if the user is online on this proxy
     */
    public boolean isOnline() {
        return reconnect.isUserOnline(user);
    }
    
    /**
     * Only when called this reconnector will attempt to function Can only be called once.
     * <p>
     * Once the reconnector is finished/cancelled This method will not work! Create a new instance instead
     */
    public synchronized void start() {
        if (isRunning && !isCancelled) {
            return;
        }
        reconnect.debug(this, "start invoked " + this);
        isRunning = true;
        startTime = System.nanoTime();
        
        currentServer.setObsolete(true);
        
        // Create runnable if not existing; send tiles and action bar updates (as well
        // as keepAlive Packets)
        startSendingUpdates();
        // invoke the "run" runnable after the delay before trying
        
        retry(reconnect.getDelayBeforeTrying());
    }
    
    /**
     * Gets the reconnect instance
     * @return the reconnect instance
     */
    public Reconnect getReconnect() {
        return reconnect;
    }
    
    /**
     * Called when a retry should occur
     */
    private void retry() {
        // invoke the "run" runnable after 250 msec
        retry(250);
    }
    
    /**
     * Called when a retry should occur
     * @param time how many milliseconds should we wait
     */
    private void retry(long time) {
        Sched.scheduleAsync(reconnect, run, time, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Abstracted logic that is called every time a new connection attempt should be made
     */
    private synchronized void tryReconnect() {
        try {
            
            // create entry in queue and wait if needed
            reconnect.debug(Reconnector.this, "enqueuing for attempt");
            holder = reconnect.waitForConnect(targetInfo, user, getRemainingTime(TimeUnit.NANOSECONDS),
                    TimeUnit.NANOSECONDS);
            
            if (!statusCheck() || holder == null) {
                reconnect.debug(Reconnector.this, "post-queue status check returned false");
                cancel();
                return;
            }
            
            // add pending connect
            user.getPendingConnects().add(targetInfo);
            
            currentServer.setObsolete(true);
            
            // Create channel initializer.
            ChannelInitializer<Channel> initializer = new ReconnectChannelInitializer(this, bungee, user, targetInfo);
            
            // Create a new Netty Bootstrap with the ReconnectChannelInitializer that uses ReconnectServerConnector
            Bootstrap bootstrap = new Bootstrap()
                    .channel(MyPipelineUtils.getChannel(targetInfo.getAddress()))
                    .group(currentServer.getCh().getHandle().eventLoop())
                    .handler(initializer)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, reconnect.getReconnectTimeout())
                    .remoteAddress(targetInfo.getAddress());
            
            if (user.getPendingConnection().getListener().isSetLocalAddress() && user.getPendingConnection().getListener().getSocketAddress() instanceof InetSocketAddress && !PlatformDependent.isWindows()) {
                bootstrap.localAddress(((InetSocketAddress) user.getPendingConnection().getListener().getSocketAddress()).getHostString(), 0);
            }
            
            // connect
            reconnect.debug(Reconnector.this, "connecting...");
            synchronized (channelSync) {
                tryCloseChannel(channelFuture);
                channelFuture = bootstrap.connect();
            }
            
            try {
                // wait for the future to finish or fail for no longer than reconnect timeout
                channelFuture.get(reconnect.getReconnectTimeout(), TimeUnit.MILLISECONDS);
                
                synchronized (channelSync) {
                    if (isCancelled || !user.isConnected()) {
                        reconnect.debug(Reconnector.this, "post-connect cancelled check returned true");
                        tryCloseChannel(channelFuture);
                        return;
                    }
                    reconnect.debug(Reconnector.this, "connection established.");
                    lastChannelTime = System.nanoTime();
                }
            } catch (Exception e) { // we ignore exceptions here as many will be thrown as some attempts fail
                reconnect.debug(Reconnector.this, "exception connecting", e);
                dropHolder();
                closeChannel(channelFuture);
            }
            
        } catch (Exception e) { // if any other exception occurs here log it.
            dropHolder();
            log.log(Level.WARNING, "unexpected exception thrown in reconnect task for \"" + user.getName() + "\" for server \"" + targetInfo.getName() + "\" : \"" + e.getMessage() + "\"", e);
        }
        // Call next retry to check the connection state etc irrelevant of the outcome of the future.
        retry();
    }
    
    /**
     * Gets the joined flag
     * @return if they have joined
     */
    public boolean getJoinFlag() {
        return joinFlag;
    }
    
    /**
     * Sets the joined flag
     * @param joined if they have joined
     */
    public void setJoinFlag(boolean joined) {
        this.joinFlag = joined;
    }
    
    /**
     * Drops the currently held lock
     */
    public void dropHolder() {
        final Holder h = holder;
        if (h != null) {
            h.unlock();
            holder = null;
        }
    }
    
    /**
     * Closes a channel while catching common exceptions.
     * 
     * @param future the future
     */
    public void tryCloseChannel(ChannelFuture future) {
        try {
            closeChannel(future);
        } catch (Exception e) {
            if (!(e instanceof InterruptedException || e instanceof IOException)) {
                log.log(Level.WARNING, "Unexpected exception while closing inactive channel", e);
            }
        }
    }
    
    /**
     * Used to cancel and close a nullable future
     * 
     * @param future The nullable future to close
     */
    public void closeChannel(ChannelFuture future) {
        synchronized (channelSync) {
            if (future != null) {
                future.channel().close();
                future.cancel(true);
            }
        }
    }
    
    /**
     * Called to cancel, close and remove the channelFuture from this reconnector.
     */
    public void removeChannel() {
        synchronized (channelSync) {
            try {
                closeChannel(channelFuture);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                channelFuture = null;
            }
        }
    }
    
    /**
     * Identical to removeChannel but will only do so if the future is incomplete /
     * inactive
     */
    public void removeChannelIfIncomplete() {
        synchronized (channelSync) {
            if (channelFuture != null && (!channelFuture.isSuccess() || !channelFuture.channel().isActive())) {
                removeChannel();
            }
        }
    }
    
    /**
     * If called and the reconnector is running this will mimic standard proxy
     * behavior and will kick the player back to fall back (if exists) if not kick
     * them to the title screen
     */
    public void failReconnect() {
        removeChannel();
        cancel();
        
        List<ServerInfo> fallbacks = reconnect.getFallbackServersFor(user);
        // remove current server from fallback servers iterator
        fallbacks.remove(targetInfo);
        
        currentServer.setObsolete(true);

        TitleViewEntry fail = data.getFailTitle();
        // Send fancy title if it's enabled in config, otherwise reset the connecting
        // title.
        if (!(fail.getActionBar().isEmpty() && fail.getSubTitle().isEmpty())) {
            user.sendTitle(createFailedTitle());
        } else {
            user.sendTitle(ProxyServer.getInstance().createTitle().reset());
        }
        // Send fancy action bar message if it's enabled in config, otherwise reset the
        // connecting action bar message.
        if (!fail.getActionBar().isEmpty()) {
            sendFailedActionBar();
        } else {
            user.sendMessage(ChatMessageType.ACTION_BAR, EMPTY);
        }
        
        reconnect.fallback(user, fallbacks.iterator(), () -> {
            user.disconnect(data.getFailKickMessage());
            return null;
        });
    }
    
    /**
     * Used to enable sending of titles, action bars and keep alives. see also:
     * {@link Reconnector#stopSendingUpdates()}
     */
    private void startSendingUpdates() {
        if (!updatesEnabled) {// Only allow invocation once
            updatesEnabled = true;
            update();
            try {
                MusicProvider provider = reconnect.getMusicProvider();
                if (provider != null) {
                    provider.playMusic(user);
                }
            } catch (Exception e) {
                log.log(Level.SEVERE, "Exception while playing reconnect music: ", e);
            }
        }
    }
    
    /**
     * Method used to queue the next update. Should not be called directly as it
     * will first wait for the set update rate before calling
     * {@link Reconnector#update()}
     */
    private void queueUpdate() {
        updatesTask = ProxyServer.getInstance().getScheduler().schedule(reconnect, this::update, updateRate, TimeUnit.MILLISECONDS);
    }
    
    /**
     * This is normally called by queueUpdate() and is the method that sends titles,
     * action bars and keep alives. see also: {@link Reconnector#queueUpdate()}
     */
    private void update() {
        TitleViewEntry reconn = data.getReconnectionTitle();
        TitleViewEntry conn = data.getConnectionTitle();

        if (statusCheck() && updatesEnabled && !isCancelled) {
            // Send keep alive packet so user will not timeout.
            user.unsafe().sendPacket(new KeepAlive(rand.nextLong()));
            ChannelFuture future = channelFuture;
            if (future == null || !future.channel().isActive()) {
                // Send fancy Title
                if (!(reconn.getTitle().isEmpty() && reconn.getSubTitle().isEmpty())) {
                    createReconnectTitle().send(user);
                }
                
                // Send fancy Action Bar Message
                if (!reconn.getActionBar().isEmpty()) {
                    sendReconnectActionBar();
                }
                
            } else {
                // Send fancy Title
                if (!(conn.getTitle().isEmpty() && conn.getSubTitle().isEmpty())) {
                    createConnectingTitle().send(user);
                }
                
                // Send fancy Action Bar Message
                if (!conn.getActionBar().isEmpty()) {
                    sendConnectActionBar();
                }
            }
            // Loop
            queueUpdate();
        } else {
            stopSendingUpdates();
        }
    }
    
    /**
     * Used to disable updates from being sent. see also:
     * {@link Reconnector#startSendingUpdates()}
     */
    private void stopSendingUpdates() {
        if (updatesTask != null) {
            updatesEnabled = false;
            updatesTask.cancel();
        }
    }
    
    /**
     * gets the time left before {@link Reconnect#getMaxReconnectNanos()} expires
     * 
     * @return The remaining time in nanoseconds
     */
    public long getRemainingTime(TimeUnit unit) {
        return unit.convert(Math.max(reconnect.getMaxReconnectNanos() - (System.nanoTime() - startTime), 0),
                TimeUnit.NANOSECONDS);
    }
    
    /**
     * @return true if {@link Reconnect#getMaxReconnectNanos()} has expired, false
     *          otherwise
     */
    public boolean hasTimedOut() {
        return getRemainingTime(TimeUnit.NANOSECONDS) < 1;
    }
    
    /**
     * Sends an Action Bar Message containing the reconnect-text to the player.
     */
    private void sendReconnectActionBar() {        
        user.sendMessage(ChatMessageType.ACTION_BAR,
                new TextComponent(reconnect.animate(this, data.getReconnectionTitle().getActionBar())));
    }
    
    /**
     * Sends an Action Bar Message containing the connect-text to the player.
     */
    private void sendConnectActionBar() {
        user.sendMessage(ChatMessageType.ACTION_BAR,
                new TextComponent(reconnect.animate(this, data.getConnectionTitle().getActionBar())));
    }
    
    /**
     * Sends an Action Bar Message containing the failed-text to the player.
     */
    private void sendFailedActionBar() {
        user.sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(data.getFailTitle().getActionBar()));
    }
    
    /**
     * Creates a Title containing the reconnect-text.
     *
     * @return a Title that can be send to the player.
     */
    private Title createReconnectTitle() {
        Title title = ProxyServer.getInstance().createTitle();
        TitleViewEntry reconn = data.getReconnectionTitle();

        title.title(new TextComponent(reconnect.animate(this, reconn.getTitle())));
        title.subTitle(new TextComponent(reconnect.animate(this, reconn.getSubTitle())));
        title.stay(titleStayTime);
        title.fadeIn(0);
        title.fadeOut(1);
        
        return title;
    }
    
    /**
     * Creates a Title containing the connecting-text.
     *
     * @return a Title that can be send to the player.
     */
    private Title createConnectingTitle() {
        TitleViewEntry conn = data.getConnectionTitle();
        Title title = ProxyServer.getInstance().createTitle();
        title.title(new TextComponent(reconnect.animate(this, conn.getTitle())));
        title.subTitle(new TextComponent(reconnect.animate(this, conn.getSubTitle())));
        title.stay(titleStayTime);
        title.fadeIn(0);
        title.fadeOut(1);
        
        return title;
    }
    
    /**
     * Created a Title containing the failed-text.
     *
     * @return a Title that can be send to the player.
     */
    private Title createFailedTitle() {
        TitleViewEntry fail = data.getFailTitle();
        Title title = ProxyServer.getInstance().createTitle();
        title.title(new TextComponent(fail.getTitle()));
        title.subTitle(new TextComponent(reconnect.animate(this, fail.getSubTitle())));
        title.stay(titleStayTime);
        title.fadeIn(0);
        title.fadeOut(1);
        
        return title;
    }
    
    /**
     * Clears all titles and action bar messages
     */
    public void clearAnimations() {
        if (reconnect.isUserOnline(user)) {
            // clear action bar
            user.sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(""));
            // clear title
            Title title = ProxyServer.getInstance().createTitle();
            title.title(new TextComponent(""));
            title.subTitle(new TextComponent(""));
            title.send(user);
        }
    }
    
    /**
     * Get the target server for this reconnector
     * 
     * @return The target of this reconnector
     */
    public BungeeServerInfo getTarget() {
        return targetInfo;
    }
    
    /**
     * Get the UUID this reconnector is trying to reconnect
     * 
     * @return The target of this reconnector
     */
    public UUID getUUID() {
        return user.getUniqueId();
    }
    
    /**
     * gets the user this reconnector is handling
     * 
     * @return the user connection
     */
    public UserConnection getUser() {
        return user;
    }
    
    /**
     * gets the server connection this reconnector is handling
     * 
     * @return the server connection
     */
    public ServerConnection getServer() {
        return currentServer;
    }
    
    /**
     * Cancels this reconnector Note: if the player is already connecting to the
     * server this will not stop it. see also {@link Reconnector#cancel(boolean force)}
     */
    public void cancel() {
        cancel(false);
    }
    
    /**
     * Gets the nanoTime when this reconnector was started.
     * 
     * @return the start nanos
     */
    public long getStartNanos() {
        return startTime;
    }
    
    /**
     * Checks if this reconnector is cancelled
     * @return true if cancelled false otherwise
     */
    public boolean isCancelled() {
        return isCancelled;
    }
    
    /**
     * Cancels this reconnector
     * 
     * @param force Should we forcefully cancel the channel even if it's active
     */
    public synchronized void cancel(boolean force) {
        reconnect.debug(Reconnector.this, "cancel invoked(force=" + force + ") : " + this);
        
        isCancelled = true;
        isRunning = false;
        updatesEnabled = false;
        
        reconnect.remove(this);
        
        stopSendingUpdates();
        clearAnimations();
        dropHolder();
        
        user.getPendingConnects().remove(targetInfo);
        
        if (force) {
            removeChannel();
        } else {
            removeChannelIfIncomplete();
        }
    }
    
    @Override
    public String toString() {
        return "Reconnector [user=" + user + ", target=" + targetInfo + ", startTime=" + startTime + ", updateRate="
                + updateRate + ", stayTime=" + titleStayTime + ", updatesTaskNull?=" + (updatesTask == null) + ", updates="
                + updatesEnabled + ", cancelled=" + isCancelled + ", running=" + isRunning + ", channelFuture=" + channelFuture
                + ", lastFutureTime=" + lastChannelTime + ", holder=" + holder + ", statusCheck()=" + statusCheck()
                + " joinFlag=" + joinFlag + "]";
        
    }
    
}
